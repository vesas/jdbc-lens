package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-operation drill-down HTML. One file per op-id, listing that
 * op's events in timeline order with durations, templates, call-sites,
 * and (when captured) bound parameters. The main report
 * ({@link HtmlReport}) links here from the Operations table.
 *
 * <p>Purpose: in a large application the headline report aggregates
 * thousands of operations into means that wash out what actually
 * happened in any one slow request. Drilling into a single op lets a
 * reader answer "this request was slow — what did it do?" without
 * grepping the raw {@code .jdbclog}.
 */
public final class DrillDown {

    private static final int MAX_SQL_CHARS = 140;
    private static final int MAX_PARAM_CHARS = 80;

    public record Inputs(
            long opId,
            String opName,
            List<Event> events,
            Map<Integer, String> sqls,
            Map<Integer, StackFrameSnapshot[]> stacks,
            Map<Integer, ParameterValues> paramValuesById,
            String backLinkHref) {
    }

    private DrillDown() {
    }

    public static Path fileFor(Path opsDir, String opName) {
        return opsDir.resolve(slug(opName) + ".html");
    }

    public static void write(Path file, Inputs in) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream os = Files.newOutputStream(file);
             PrintStream out = new PrintStream(os, false, StandardCharsets.UTF_8)) {
            writeTo(out, in);
        }
    }

    private static void writeTo(PrintStream out, Inputs in) {
        List<Event> timeline = new ArrayList<>(in.events);
        timeline.sort(Comparator.comparingLong(e -> e.timestampNanos));

        long firstTs = timeline.isEmpty() ? 0L : timeline.get(0).timestampNanos;
        EventGaps.OpBreakdown breakdown = EventGaps.forOp(timeline);

        // Pre-pass: collect invocation order + per-invocation summaries so
        // the divider row between invocations can show "N of M, X events,
        // Y ms" without a second scan during the render loop.
        List<Long> invOrder = new ArrayList<>();
        Map<Long, long[]> invSummary = new HashMap<>(); // invId -> [eventCount, totalDurNs]
        for (Event e : timeline) {
            long inv = e.operationInvocationId;
            long[] s = invSummary.get(inv);
            if (s == null) {
                s = new long[2];
                invSummary.put(inv, s);
                invOrder.add(inv);
            }
            s[0]++;
            s[1] += Math.max(0L, e.durationNanos);
        }
        Map<Long, Integer> invIndex = new HashMap<>();
        for (int i = 0; i < invOrder.size(); i++) {
            invIndex.put(invOrder.get(i), i + 1);
        }
        int totalInvocations = invOrder.size();

        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"en\">");
        out.println("<head>");
        out.println("<meta charset=\"utf-8\">");
        out.println("<title>jdbc-prof op: " + htmlEscape(in.opName) + "</title>");
        out.println("<style>");
        out.println(sharedCss());
        out.println("</style>");
        out.println("</head>");
        out.println("<body>");

        out.println("<nav class=\"back\"><a href=\"" + htmlEscape(in.backLinkHref)
                + "\">\u2190 back to report</a></nav>");
        out.println("<h1>op: " + htmlEscape(in.opName) + "</h1>");
        double dbPct = breakdown.dbFraction() * 100.0;
        double nonDbPct = breakdown.nonDbFraction() * 100.0;
        out.println("<div class=\"meta\">"
                + timeline.size() + " events \u00B7 "
                + htmlEscape(formatDuration(breakdown.wallNanos())) + " wall \u00B7 "
                + htmlEscape(formatDuration(breakdown.dbNanos())) + " DB ("
                + String.format(Locale.ROOT, "%.0f%%", dbPct) + ") \u00B7 "
                + htmlEscape(formatDuration(breakdown.nonDbNanos())) + " non-DB ("
                + String.format(Locale.ROOT, "%.0f%%", nonDbPct) + ")</div>");
        if (breakdown.wallNanos() > 0) {
            out.println("<div class=\"split-hero\" title=\""
                    + "DB " + htmlEscape(formatDuration(breakdown.dbNanos()))
                    + " \u00B7 non-DB " + htmlEscape(formatDuration(breakdown.nonDbNanos()))
                    + "\">"
                    + String.format(Locale.ROOT,
                        "<span class=\"split-db\" style=\"flex:%.4f 0 0\">%s</span>",
                        Math.max(0.0001, dbPct),
                        dbPct >= 8.0 ? htmlEscape("DB " + formatDuration(breakdown.dbNanos())) : "")
                    + String.format(Locale.ROOT,
                        "<span class=\"split-app\" style=\"flex:%.4f 0 0\">%s</span>",
                        Math.max(0.0001, nonDbPct),
                        nonDbPct >= 8.0 ? htmlEscape("non-DB " + formatDuration(breakdown.nonDbNanos())) : "")
                    + "</div>");
        }

        renderTransactions(out, timeline, in.sqls);

        out.println("<h2>Timeline</h2>");
        out.println("<p class=\"meta\"><strong>gap</strong> is idle time since the "
                + "previous event on the same thread \u2014 "
                + "highlighted rows had <em>noticeably more</em> app/non-DB time "
                + "before them than the rest. Follow those to find where the op "
                + "lost its wall-clock time outside the database.</p>");
        out.println("<table>");
        out.println("  <thead><tr>"
                + "<th class=\"num\">+offset</th>"
                + "<th class=\"num\">gap</th>"
                + "<th>event</th>"
                + "<th class=\"num\">duration</th>"
                + "<th>template</th>"
                + "<th>params</th>"
                + "<th>call-site</th>"
                + "</tr></thead>");
        out.println("  <tbody>");
        // Per-thread previous-event end, for the "gap since last event
        // on this thread" column. Cross-thread gaps aren't interesting
        // here — each thread is its own lock-holding actor.
        Map<Integer, Long> lastEndByThread = new HashMap<>();
        // Threshold for highlighting a gap row: 5 ms. Small enough to
        // flag any human-visible pause, large enough to ignore GC/JIT
        // noise between adjacent statements.
        final long GAP_HIGHLIGHT_NANOS = 5_000_000L;
        long prevInv = Long.MIN_VALUE;
        int eventIndex = 0;
        for (Event e : timeline) {
            eventIndex++;
            long inv = e.operationInvocationId;
            if (totalInvocations > 1 && inv != prevInv && prevInv != Long.MIN_VALUE) {
                Integer idx = invIndex.get(inv);
                long[] s = invSummary.get(inv);
                String label = idx == null
                        ? "next invocation"
                        : "Invocation " + idx + " of " + totalInvocations
                                + " \u00B7 " + s[0] + " event" + (s[0] == 1L ? "" : "s")
                                + " \u00B7 " + formatDuration(s[1]) + " DB time";
                out.println("    <tr class=\"inv-divider\">"
                        + "<td colspan=\"7\">" + htmlEscape(label) + "</td>"
                        + "</tr>");
                // Reset the per-thread gap tracking across invocation
                // boundaries — a gap that straddles two invocations is
                // not "idle within this invocation."
                lastEndByThread.clear();
            }
            prevInv = inv;
            long offset = e.timestampNanos - firstTs;
            String kind = EventType.fromCode(e.eventType).name();
            String sql = e.sqlId >= 0 ? in.sqls.getOrDefault(e.sqlId, "sql[" + e.sqlId + "]") : "";
            String params = formatParams(e.parameterValuesId, in.paramValuesById);
            String site = callSite(e.stackTraceId, in.stacks);

            Long prevEnd = lastEndByThread.get(e.threadId);
            long gap = prevEnd == null ? -1L : Math.max(0L, e.timestampNanos - prevEnd);
            String gapCell;
            if (gap < 0L) {
                gapCell = "<td class=\"num muted\" data-raw=\"0\">\u2014</td>";
            } else {
                gapCell = "<td class=\"num\" data-raw=\"" + gap + "\">"
                        + htmlEscape(formatDuration(gap)) + "</td>";
            }
            String rowClass = gap >= GAP_HIGHLIGHT_NANOS ? " class=\"gap-row\"" : "";
            lastEndByThread.put(e.threadId, e.timestampNanos + Math.max(0L, e.durationNanos));

            out.println("    <tr id=\"event-" + eventIndex + "\"" + rowClass + ">"
                    + "<td class=\"num\" data-raw=\"" + offset + "\">" + htmlEscape(formatDuration(offset)) + "</td>"
                    + gapCell
                    + "<td>" + htmlEscape(kind) + "</td>"
                    + "<td class=\"num\" data-raw=\"" + e.durationNanos + "\">"
                    + htmlEscape(formatDuration(e.durationNanos)) + "</td>"
                    + "<td>" + (sql.isEmpty() ? "<span class=\"muted\">\u2014</span>"
                            : "<code class=\"sql\">" + htmlEscape(truncate(sql, MAX_SQL_CHARS)) + "</code>") + "</td>"
                    + "<td>" + params + "</td>"
                    + "<td><code class=\"site\">" + htmlEscape(site) + "</code></td>"
                    + "</tr>");
        }
        out.println("  </tbody>");
        out.println("</table>");

        out.println("<footer>Generated by jdbc-prof analyze</footer>");
        out.println("</body>");
        out.println("</html>");
    }

    private static void renderTransactions(PrintStream out, List<Event> timeline,
                                           Map<Integer, String> sqls) {
        List<TransactionShape.Transaction> txs = TransactionShape.of(timeline);
        if (txs.isEmpty()) {
            return;
        }
        boolean anyExplicit = txs.stream()
                .anyMatch(t -> t.outcome() != TransactionShape.Outcome.AUTOCOMMIT);
        out.println("<h2>Transactions</h2>");
        if (!anyExplicit) {
            out.println("<p class=\"findings-empty\">Autocommit mode \u2014 every statement "
                    + "was its own transaction. Group related writes in one explicit "
                    + "transaction (and reads outside it) to cut round-trips.</p>");
        }
        // Max-idle gap per TX: re-walk this op's events sliced on
        // commit/rollback boundaries and compute the biggest gap
        // inside each explicit TX. Cheap (single pass over events
        // already in memory) and surfaces the same signal the
        // idle-lock finding flags — but inline, per TX, so a reader
        // skimming a single op's shape can see it here too.
        long idleThresholdNs = IdleLockDetector.DEFAULT_THRESHOLD_NANOS;
        List<Long> maxIdlePerTx = computeMaxIdlePerTx(timeline, txs);

        out.println("<table>");
        out.println("  <thead><tr>"
                + "<th class=\"num\">#</th>"
                + "<th class=\"num\">duration</th>"
                + "<th class=\"num\">reads</th>"
                + "<th class=\"num\">writes</th>"
                + "<th class=\"num\">max idle</th>"
                + "<th>outcome</th>"
                + "<th>longest template</th>"
                + "</tr></thead>");
        out.println("  <tbody>");
        int idx = 0;
        for (TransactionShape.Transaction t : txs) {
            idx++;
            long maxIdle = idx - 1 < maxIdlePerTx.size() ? maxIdlePerTx.get(idx - 1) : 0L;
            boolean highlightIdle = maxIdle >= idleThresholdNs
                    && t.outcome() != TransactionShape.Outcome.AUTOCOMMIT
                    && t.writes() >= 1;
            String longest = t.longestTemplateSqlId() >= 0
                    ? sqls.getOrDefault(t.longestTemplateSqlId(),
                            "sql[" + t.longestTemplateSqlId() + "]")
                    : "";
            String idleCell;
            if (t.outcome() == TransactionShape.Outcome.AUTOCOMMIT) {
                idleCell = "<td class=\"num muted\" data-raw=\"0\">\u2014</td>";
            } else {
                idleCell = "<td class=\"num" + (highlightIdle ? " idle-bad" : "")
                        + "\" data-raw=\"" + maxIdle + "\">"
                        + htmlEscape(formatDuration(maxIdle)) + "</td>";
            }
            out.println("    <tr" + (highlightIdle ? " class=\"tx-flagged\"" : "") + ">"
                    + "<td class=\"num\">" + idx + "</td>"
                    + "<td class=\"num\" data-raw=\"" + t.durationNanos() + "\">"
                    + htmlEscape(formatDuration(t.durationNanos())) + "</td>"
                    + "<td class=\"num\">" + t.reads() + "</td>"
                    + "<td class=\"num\">" + t.writes() + "</td>"
                    + idleCell
                    + "<td>" + htmlEscape(t.outcome().name().toLowerCase(Locale.ROOT)) + "</td>"
                    + "<td>" + (longest.isEmpty() ? "<span class=\"muted\">\u2014</span>"
                            : "<code class=\"sql\">"
                                    + htmlEscape(truncate(longest, MAX_SQL_CHARS)) + "</code>")
                    + "</td>"
                    + "</tr>");
        }
        out.println("  </tbody>");
        out.println("</table>");
    }

    /**
     * For each transaction in {@code txs} (same order), the biggest
     * inter-event gap inside it. Autocommit TXs get 0 (one event each,
     * no gap to measure).
     */
    private static List<Long> computeMaxIdlePerTx(List<Event> opEvents,
                                                   List<TransactionShape.Transaction> txs) {
        List<Long> out = new ArrayList<>(txs.size());
        for (TransactionShape.Transaction t : txs) {
            if (t.outcome() == TransactionShape.Outcome.AUTOCOMMIT) {
                out.add(0L);
                continue;
            }
            List<Event> inTx = new ArrayList<>();
            for (Event e : opEvents) {
                if (e.threadId != t.threadId()) continue;
                if (e.timestampNanos < t.startTimestampNanos()) continue;
                if (e.timestampNanos > t.endTimestampNanos()) continue;
                inTx.add(e);
            }
            inTx.sort(Comparator.comparingLong(e -> e.timestampNanos));
            long max = 0L;
            for (EventGaps.Gap g : EventGaps.betweenAdjacent(inTx)) {
                if (g.gapNanos() > max) max = g.gapNanos();
            }
            out.add(max);
        }
        return out;
    }

    private static String formatParams(int valuesId, Map<Integer, ParameterValues> byId) {
        if (valuesId < 0) {
            return "<span class=\"muted\">\u2014</span>";
        }
        ParameterValues pv = byId.get(valuesId);
        if (pv == null) {
            return "<span class=\"muted\">\u2014</span>";
        }
        StringBuilder sb = new StringBuilder("<code class=\"sql\">");
        List<String> slots = pv.slots();
        int emitted = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (emitted > 0) sb.append(", ");
            sb.append(htmlEscape(truncate("[" + (i + 1) + "] " + slots.get(i), MAX_PARAM_CHARS)));
            emitted++;
        }
        sb.append("</code>");
        return sb.toString();
    }

    private static String callSite(int stackTraceId, Map<Integer, StackFrameSnapshot[]> stacks) {
        StackFrameSnapshot[] frames = stacks.get(stackTraceId);
        if (frames == null) {
            return "stack[" + stackTraceId + "]";
        }
        StackFrameSnapshot site = Attribution.callSite(frames);
        if (site == null) {
            return "(unattributed)";
        }
        return site.className() + '.' + site.methodName() + ':' + site.lineNumber();
    }

    /**
     * Slugify an op name to a safe filename. Collapses non-word
     * characters to {@code _} and trims the run; the head keeps
     * its case so the filename is still recognisable.
     */
    static String slug(String opName) {
        if (opName == null || opName.isEmpty()) {
            return "op";
        }
        StringBuilder sb = new StringBuilder(opName.length());
        boolean lastWasUnderscore = false;
        for (int i = 0; i < opName.length(); i++) {
            char c = opName.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.';
            if (ok) {
                sb.append(c);
                lastWasUnderscore = false;
            } else if (!lastWasUnderscore) {
                sb.append('_');
                lastWasUnderscore = true;
            }
        }
        // Trim leading / trailing underscores.
        int start = 0;
        while (start < sb.length() && sb.charAt(start) == '_') start++;
        int end = sb.length();
        while (end > start && sb.charAt(end - 1) == '_') end--;
        String result = sb.substring(start, end);
        if (result.isEmpty()) {
            return "op";
        }
        if (result.length() > 80) {
            result = result.substring(0, 80);
        }
        return result;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "\u2026";
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
        if (s == null) return "";
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

    private static String sharedCss() {
        // Kept close to HtmlReport's CSS so the two pages look like
        // one tool. Duplication is cheaper than carving out a shared
        // resource file while the report is this small.
        return """
                :root {
                  --fg: #1b1f23;
                  --fg-muted: #57606a;
                  --bg: #ffffff;
                  --bg-alt: #f6f8fa;
                  --border: #d0d7de;
                  --accent: #0969da;
                  --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  padding: 24px;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
                  color: var(--fg);
                  background: var(--bg);
                  line-height: 1.45;
                }
                nav.back { margin-bottom: 16px; font-size: 13px; }
                nav.back a { color: var(--accent); text-decoration: none; }
                nav.back a:hover { text-decoration: underline; }
                h1 { margin: 0 0 4px 0; font-size: 20px; }
                h2 { margin: 24px 0 10px 0; font-size: 15px; border-bottom: 1px solid var(--border); padding-bottom: 6px; }
                .meta { color: var(--fg-muted); font-size: 13px; margin-bottom: 16px; }
                table { width: 100%; border-collapse: collapse; font-size: 12px; }
                th, td {
                  text-align: left;
                  padding: 4px 8px;
                  border-bottom: 1px solid var(--border);
                  vertical-align: top;
                }
                th { background: var(--bg-alt); font-weight: 600; font-size: 11px;
                     text-transform: uppercase; letter-spacing: 0.04em; }
                th.num, td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
                tr:nth-child(even) td { background: var(--bg-alt); }
                tr.inv-divider td {
                  background: var(--bg);
                  border-top: 2px solid var(--accent);
                  border-bottom: none;
                  padding: 8px 8px 4px 8px;
                  font-size: 11px;
                  font-weight: 600;
                  color: var(--fg-muted);
                  text-transform: uppercase;
                  letter-spacing: 0.06em;
                }
                code.sql, code.site { font-family: var(--mono); font-size: 12px; }
                code.sql { white-space: pre-wrap; word-break: break-word; }
                .muted { color: var(--fg-muted); }
                /* Hero split bar at the top of each drill-down: big, labelled. */
                .split-hero {
                  display: flex;
                  width: 100%;
                  height: 22px;
                  border-radius: 4px;
                  overflow: hidden;
                  background: var(--border);
                  margin: 8px 0 16px 0;
                  font-family: var(--mono);
                  font-size: 11px;
                  color: #fff;
                }
                .split-hero > span {
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  white-space: nowrap;
                  overflow: hidden;
                  padding: 0 8px;
                }
                .split-hero .split-db { background: #4a90d9; }
                .split-hero .split-app { background: #8a94a0; }
                /* Timeline row with a noticeable gap before it — highlights the
                   non-DB pause so the reader's eye jumps to it. */
                tr.gap-row td {
                  background: #fff8e1 !important;
                  border-top: 2px solid #e2a03f;
                }
                tr.gap-row td:nth-child(2) {
                  font-weight: 700;
                  color: #8a5a00;
                }
                /* Transaction row flagged by the idle-lock detector. */
                tr.tx-flagged td {
                  background: #ffebee !important;
                }
                td.idle-bad { color: #b71c1c; font-weight: 700; }
                /* Row highlighted when reached via #event-N anchor from the
                   main report. Deliberately stronger than gap-row so the
                   targeted row is unambiguous. */
                tr:target td {
                  background: #fff3bf !important;
                  outline: 2px solid #e2a03f;
                  outline-offset: -2px;
                }
                footer { margin-top: 32px; font-size: 12px; color: var(--fg-muted); text-align: center; }
                """;
    }
}
