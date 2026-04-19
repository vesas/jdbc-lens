package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flamegraph / icicle view over the recording (spec §8.4, §9).
 *
 * <p>Tree semantics: nodes are application stack frames; a node's
 * weight is the <em>inclusive</em> total database time of execute
 * events whose stack passes through that frame. JDK and profiler /
 * JDBC infrastructure frames are stripped so only application code
 * appears.
 *
 * <p>Rendering: nested flexbox divs where each child's
 * {@code flex-grow} is the frame's weight. Widest child first so the
 * eye lands on the hot path. Colors are a pastel HSL hash of
 * {@code class + method} so a given method keeps the same colour
 * across rows. No JavaScript — the icicle is static; tooltips are
 * native HTML {@code title} attributes.
 */
public final class FlameGraph {

    /** A single tree node. Exposed for tests. */
    public static final class Node {
        public final StackFrameSnapshot frame; // null for the synthetic root
        public long totalDurationNanos;
        // Insertion-ordered so the build is deterministic; sort-by-weight
        // happens lazily at render time.
        private final Map<FrameKey, Node> childrenByKey = new LinkedHashMap<>();

        Node(StackFrameSnapshot frame) {
            this.frame = frame;
        }

        public List<Node> children() {
            return new ArrayList<>(childrenByKey.values());
        }

        public List<Node> childrenSortedByDuration() {
            List<Node> sorted = children();
            sorted.sort(Comparator.comparingLong((Node n) -> n.totalDurationNanos).reversed());
            return sorted;
        }
    }

    private record FrameKey(String className, String methodName, int lineNumber) {
        static FrameKey of(StackFrameSnapshot f) {
            return new FrameKey(f.className(), f.methodName(), f.lineNumber());
        }
    }

    private FlameGraph() {
    }

    /**
     * Build a tree from an execute-only {@link Aggregator} and the
     * stack intern table. The caller must not include PREPARE / NEXT /
     * CLOSE events in {@code executeAgg} — those would distort the
     * weights.
     */
    public static Node build(Aggregator executeAgg,
                             Map<Integer, StackFrameSnapshot[]> stacks) {
        Node root = new Node(null);
        for (var entry : executeAgg.topPairs(Integer.MAX_VALUE)) {
            long dur = entry.getValue().totalDurationNanos();
            int stackId = entry.getKey().stackTraceId();
            StackFrameSnapshot[] frames = stacks.get(stackId);
            if (frames == null) {
                continue;
            }

            // Application path only (strip JDK + infra), outermost first
            // so the tree grows downward from main/Thread.run toward the
            // call-site leaves.
            List<StackFrameSnapshot> app = new ArrayList<>();
            for (StackFrameSnapshot f : frames) {
                if (Attribution.isApplicationFrame(f)) {
                    app.add(f);
                }
            }
            if (app.isEmpty()) {
                continue;
            }
            Collections.reverse(app);

            root.totalDurationNanos += dur;
            Node current = root;
            for (StackFrameSnapshot f : app) {
                FrameKey key = FrameKey.of(f);
                Node child = current.childrenByKey.get(key);
                if (child == null) {
                    child = new Node(f);
                    current.childrenByKey.put(key, child);
                }
                child.totalDurationNanos += dur;
                current = child;
            }
        }
        return root;
    }

    /**
     * Emit the tree as HTML — a nested {@code <div class="fg-node">}
     * structure. {@link HtmlReport} supplies the surrounding section
     * heading and CSS.
     */
    public static void renderHtml(PrintStream out, Node root) {
        if (root.childrenByKey.isEmpty()) {
            out.println("<p class=\"findings-empty\">No application frames recorded.</p>");
            return;
        }
        out.println("<div class=\"fg\">");
        renderNode(out, root, true);
        out.println("</div>");
    }

    private static void renderNode(PrintStream out, Node node, boolean isRoot) {
        if (isRoot) {
            out.println("  <div class=\"fg-node fg-root\" style=\"--w:1000\">");
            out.println("    <div class=\"fg-label\" title=\"" + htmlEscape(rootTitle(node))
                    + "\">all app code \u2014 " + htmlEscape(formatDuration(node.totalDurationNanos))
                    + "</div>");
            if (!node.childrenByKey.isEmpty()) {
                out.println("    <div class=\"fg-children\">");
                for (Node child : node.childrenSortedByDuration()) {
                    renderNode(out, child, false);
                }
                out.println("    </div>");
            }
            out.println("  </div>");
            return;
        }
        long weight = Math.max(1L, node.totalDurationNanos);
        int hue = hueFor(node.frame);
        String label = node.frame.methodName() + " \u2014 "
                + formatDuration(node.totalDurationNanos);
        String title = node.frame.className() + "." + node.frame.methodName()
                + ":" + node.frame.lineNumber()
                + " \u2014 " + formatDuration(node.totalDurationNanos);

        out.printf(Locale.ROOT, "    <div class=\"fg-node\" style=\"--w:%d; --hue:%d\">%n",
                weight, hue);
        out.println("      <div class=\"fg-label\" title=\"" + htmlEscape(title) + "\">"
                + htmlEscape(label) + "</div>");
        if (!node.childrenByKey.isEmpty()) {
            out.println("      <div class=\"fg-children\">");
            for (Node child : node.childrenSortedByDuration()) {
                renderNode(out, child, false);
            }
            out.println("      </div>");
        }
        out.println("    </div>");
    }

    private static String rootTitle(Node root) {
        return "Flamegraph root \u2014 total DB time across application frames: "
                + formatDuration(root.totalDurationNanos);
    }

    private static int hueFor(StackFrameSnapshot f) {
        // Stable pseudo-random hue per (class, method). Line number is
        // deliberately ignored so every call-site of the same method
        // shares a colour — easier to track across rows.
        int h = f.className().hashCode() * 31 + f.methodName().hashCode();
        int mod = h % 360;
        return mod < 0 ? mod + 360 : mod;
    }

    private static String formatDuration(long nanos) {
        if (nanos >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
        }
        if (nanos >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
        }
        if (nanos >= 1_000L) {
            return String.format(Locale.ROOT, "%.2f us", nanos / 1_000.0);
        }
        return nanos + " ns";
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
