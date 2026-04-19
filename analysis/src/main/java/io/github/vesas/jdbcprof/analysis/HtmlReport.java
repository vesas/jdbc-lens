package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.EventType;
import io.github.vesas.jdbcprof.capture.ParameterValues;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;
import io.github.vesas.jdbcprof.storage.BinaryLogReader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2 HTML renderer (spec §9). Produces a single self-contained
 * HTML file — no external CSS, no external JS, no network calls —
 * with a summary card, N+1 findings, and three sortable tables:
 * (call-site, template) pairs, call-sites alone, templates alone.
 *
 * <p>Flamegraph and expandable rows are still deferred.
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static void write(Path input, Path output) throws IOException {
        Model m = Model.load(input);
        try (OutputStream os = Files.newOutputStream(output);
             PrintStream out = new PrintStream(os, false, StandardCharsets.UTF_8)) {
            renderAll(out, input, m);
        }
        writeDrillDownPages(output, m);
    }

    public static void write(Path input, PrintStream out) throws IOException {
        Model m = Model.load(input);
        renderAll(out, input, m);
    }

    private static void renderAll(PrintStream out, Path input, Model m) {
        List<N1Finding> findings = new N1Detector().detect(m.executeAgg, m.sqls, m.stacks);
        List<RedundantFinding> redundant = new RedundantQueryDetector()
                .detect(m.redundant, m.sqls, m.ops, m.stacks, NO_OPERATION);
        List<EntityFinding> entities = EntityAccessAudit.detect(
                new EntityAccessAudit.Inputs(
                        m.eventsByOp, m.sqls, m.stacks,
                        m.paramValuesById, m.ops, NO_OPERATION));
        FlameGraph.Node flame = FlameGraph.build(m.executeAgg, m.stacks);
        renderHead(out, input);
        renderSummary(out, m);
        renderFindings(out, findings);
        renderRedundant(out, redundant, m);
        renderEntityAccess(out, entities, m);
        renderOperations(out, m);
        renderFlameGraph(out, flame);
        renderPairsTable(out, m);
        renderCallSitesTable(out, m);
        renderTemplatesTable(out, m);
        renderFooter(out);
    }

    private static void writeDrillDownPages(Path mainReportFile, Model m) throws IOException {
        Path parent = mainReportFile.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        Path opsDir = parent.resolve("ops");
        String backLink = "../" + mainReportFile.getFileName().toString();
        for (Map.Entry<Long, OpStats> entry : m.opStats.entrySet()) {
            long opId = entry.getKey();
            if (opId == NO_OPERATION) {
                continue;
            }
            String opName = m.ops.get(opId);
            if (opName == null) {
                opName = "op-" + opId;
            }
            List<Event> events = m.eventsByOp.getOrDefault(opId, List.of());
            DrillDown.write(DrillDown.fileFor(opsDir, opName),
                    new DrillDown.Inputs(
                            opId, opName, events, m.sqls, m.stacks,
                            m.paramValuesById, backLink));
        }
    }

    private static void renderRedundant(PrintStream out, List<RedundantFinding> findings, Model m) {
        out.println("<h2>Redundant queries</h2>");
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No repeated (template, parameters) pairs "
                    + "within a single operation. The detector needs op-ids — call "
                    + "<code>Profiler.currentOperation(\"name\")</code> to scope detection.</p>");
            return;
        }
        boolean valuesCaptured = !m.paramValuesById.isEmpty();
        out.println("<div class=\"findings\">");
        for (RedundantFinding f : findings) {
            out.println("  <div class=\"finding\">");
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">" + f.count() + "\u00D7</span> "
                    + "<span class=\"finding-time\">"
                    + htmlEscape(formatDuration(f.totalDurationNanos()))
                    + " total DB time</span></div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>template</dt><dd><code class=\"sql\">"
                    + htmlEscape(f.sql() == null ? "sql[" + f.sqlId() + "]" : f.sql())
                    + "</code></dd>");
            out.println("      <dt>operation</dt><dd>"
                    + htmlEscape(f.opName() == null ? "op[" + f.opId() + "]" : f.opName())
                    + "</dd>");
            out.println("      <dt>call-site</dt><dd>"
                    + htmlEscape(formatFrame(f.callSite())) + "</dd>");
            String valuesRow = renderValues(f, m, valuesCaptured);
            if (valuesRow != null) {
                out.println(valuesRow);
            }
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static String renderValues(RedundantFinding f, Model m, boolean valuesCaptured) {
        RedundantQueryDetector.Key key = new RedundantQueryDetector.Key(
                f.opId(), f.sqlId(), f.parameterFingerprint(), f.stackTraceId());
        Integer id = m.valuesIdForKey.get(key);
        if (id == null || !valuesCaptured) {
            if (!valuesCaptured) {
                return "      <dt>params</dt><dd class=\"muted\">(not captured \u2014 "
                        + "enable with <code>ProfilerConfig.withCaptureParameterValues(true)</code>)"
                        + "</dd>";
            }
            return null;
        }
        ParameterValues pv = m.paramValuesById.get(id);
        if (pv == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("      <dt>params</dt><dd><code class=\"sql\">");
        List<String> slots = pv.slots();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(htmlEscape("[" + (i + 1) + "] " + slots.get(i)));
        }
        sb.append("</code></dd>");
        return sb.toString();
    }

    private static void renderEntityAccess(PrintStream out, List<EntityFinding> findings, Model m) {
        out.println("<h2>Entity access audit</h2>");
        if (m.paramValuesById == null || m.paramValuesById.isEmpty()) {
            out.println("<p class=\"findings-empty\">Parameter values were not captured, "
                    + "so the audit cannot tell which entity each query touched. "
                    + "Enable with <code>ProfilerConfig.withCaptureParameterValues(true)</code>.</p>");
            return;
        }
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No entity was accessed by more than one "
                    + "distinct template within the same operation.</p>");
            return;
        }
        out.println("<div class=\"findings\">");
        for (EntityFinding f : findings) {
            out.println("  <div class=\"finding\">");
            String entityLabel = f.table() + "." + f.column() + " = " + f.value();
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">" + f.templates().size() + " templates</span> "
                    + "<span class=\"finding-time\">"
                    + htmlEscape(formatDuration(f.totalDurationNanos()))
                    + " total DB time \u00B7 " + f.totalEvents() + " events</span></div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>entity</dt><dd><code class=\"site\">"
                    + htmlEscape(entityLabel) + "</code></dd>");
            out.println("      <dt>operation</dt><dd>"
                    + htmlEscape(f.opName() == null ? "op[" + f.opId() + "]" : f.opName())
                    + "</dd>");
            out.println("      <dt>touched by</dt><dd>");
            for (EntityFinding.TemplateHit h : f.templates()) {
                out.println("        <div><code class=\"sql\">"
                        + htmlEscape(h.sql() == null ? "sql[" + h.sqlId() + "]" : h.sql())
                        + "</code> <span class=\"muted\">\u2014 "
                        + htmlEscape(formatFrame(h.callSite()))
                        + " \u00B7 " + h.count() + "\u00D7 \u00B7 "
                        + htmlEscape(formatDuration(h.totalDurationNanos()))
                        + "</span></div>");
            }
            out.println("      </dd>");
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + "Fetch all columns once and pass the row through the call chain.</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static void renderOperations(PrintStream out, Model m) {
        out.println("<h2>Operations</h2>");
        boolean anyOp = m.ops != null && !m.ops.isEmpty();
        if (!anyOp) {
            out.println("<p class=\"findings-empty\">No op-ids were recorded. "
                    + "Call <code>Profiler.currentOperation(\"name\")</code> "
                    + "at the start of each logical unit (request, test, job) "
                    + "to group events.</p>");
            return;
        }
        out.println("<table>");
        out.println("  <thead><tr>"
                + "<th>Operation</th>"
                + "<th class=\"num\" data-default-sort=\"desc\" aria-sort=\"desc\">DB time</th>"
                + "<th class=\"num\">Events</th>"
                + "<th class=\"num\">Templates</th>"
                + "</tr></thead>");
        out.println("  <tbody>");
        m.opStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(
                        b.getValue().totalDurationNanos,
                        a.getValue().totalDurationNanos))
                .forEach(entry -> {
                    long opId = entry.getKey();
                    OpStats s = entry.getValue();
                    boolean noOp = opId == NO_OPERATION;
                    String label = noOp
                            ? "(no operation)"
                            : m.ops.getOrDefault(opId, "op[" + opId + "]");
                    String labelCell;
                    if (noOp) {
                        labelCell = "<td class=\"muted\">" + htmlEscape(label) + "</td>";
                    } else {
                        String href = "ops/" + DrillDown.slug(label) + ".html";
                        labelCell = "<td><a href=\"" + htmlEscape(href) + "\">"
                                + "<code class=\"site\">" + htmlEscape(label) + "</code></a></td>";
                    }
                    out.println("    <tr>"
                            + labelCell
                            + tdDuration(s.totalDurationNanos)
                            + tdCount(s.count)
                            + tdCount(s.distinctSqls.size())
                            + "</tr>");
                });
        out.println("  </tbody>");
        out.println("</table>");
    }

    private static void renderFlameGraph(PrintStream out, FlameGraph.Node root) {
        out.println("<h2>Flamegraph</h2>");
        out.println("<p class=\"findings-empty\">"
                + "Widths are total DB time. Hover any frame for its full class + line.</p>");
        FlameGraph.renderHtml(out, root);
    }

    /** Sentinel in Event.operationId meaning "no op was set." Mirrors
     *  {@code CaptureContext.NO_OPERATION}; duplicated here to avoid an
     *  extra cross-module import for a single constant. */
    private static final long NO_OPERATION = -1L;

    private static final class Model {
        Path source;
        Map<Integer, String> sqls = new HashMap<>();
        Map<Integer, StackFrameSnapshot[]> stacks = new HashMap<>();
        // Op-id → name. Events with operationId = -1 ("no op") are not
        // in this map; renderers display them as "(no operation)".
        Map<Long, String> ops = new HashMap<>();
        Map<Long, OpStats> opStats = new HashMap<>();
        Aggregator agg = new Aggregator();
        // Same shape as `agg` but restricted to actual query executions
        // (PREPARE / NEXT / CLOSE / COMMIT / ROLLBACK dropped). Spec §8.3
        // counts "executions" — PREPARE and NEXT would inflate the count
        // and split each template across many stacks, masking real N+1s.
        Aggregator executeAgg = new Aggregator();
        long firstTs = Long.MAX_VALUE;
        long lastTs = Long.MIN_VALUE;
        long totalDurationNanos;
        int eventCount;
        // Cardinality for the tables: how many distinct templates each
        // call-site uses, and how many call-sites each template shows up
        // from. Spec §9 calls these the "unique template count" and
        // "originating call-site count" columns.
        Map<Integer, Set<Integer>> templatesPerStack = new HashMap<>();
        Map<Integer, Set<Integer>> stacksPerTemplate = new HashMap<>();
        // Count + duration per (op-id, sql-id, fingerprint, stack-id)
        // so the redundant-query detector has what it needs.
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> redundant = new HashMap<>();
        // Populated only when the recording was produced with
        // captureParameterValues = true. Otherwise empty, and the
        // report renders a hint about how to turn value capture on.
        Map<Integer, ParameterValues> paramValuesById = new HashMap<>();
        // (execute-event-key) -> one paramValuesId seen for this key.
        // Used by the report to pair a redundant-queries card with the
        // actual bound values.
        Map<RedundantQueryDetector.Key, Integer> valuesIdForKey = new HashMap<>();
        // Every event, bucketed by op-id. Memory cost is linear in
        // total events — fine for the recording sizes the Phase-2
        // report is meant for (test-suite scale, not all-day prod).
        // Streaming drill-down is left for later.
        Map<Long, List<Event>> eventsByOp = new HashMap<>();

        static Model load(Path input) throws IOException {
            Model m = new Model();
            m.source = input;
            BinaryLogReader reader = new BinaryLogReader(input);
            reader.read(new BinaryLogReader.Handler() {
                @Override
                public void onSqlDelta(int firstId, List<String> entries) {
                    for (int i = 0; i < entries.size(); i++) {
                        m.sqls.put(firstId + i, entries.get(i));
                    }
                }

                @Override
                public void onStackDelta(int firstId, List<StackFrameSnapshot[]> entries) {
                    for (int i = 0; i < entries.size(); i++) {
                        m.stacks.put(firstId + i, entries.get(i));
                    }
                }

                @Override
                public void onOpDelta(int firstId, List<String> names) {
                    for (int i = 0; i < names.size(); i++) {
                        m.ops.put((long) (firstId + i), names.get(i));
                    }
                }

                @Override
                public void onParamValuesDelta(int firstId, List<ParameterValues> entries) {
                    for (int i = 0; i < entries.size(); i++) {
                        m.paramValuesById.put(firstId + i, entries.get(i));
                    }
                }

                @Override
                public void onEvents(List<Event> events) {
                    for (Event e : events) {
                        m.agg.add(e);
                        if (isExecute(e.eventType)) {
                            m.executeAgg.add(e);
                        }
                        m.eventCount++;
                        long dur = Math.max(0L, e.durationNanos);
                        m.totalDurationNanos += dur;
                        if (e.timestampNanos < m.firstTs) {
                            m.firstTs = e.timestampNanos;
                        }
                        long end = e.timestampNanos + dur;
                        if (end > m.lastTs) {
                            m.lastTs = end;
                        }
                        m.templatesPerStack
                                .computeIfAbsent(e.stackTraceId, k -> new HashSet<>())
                                .add(e.sqlId);
                        m.stacksPerTemplate
                                .computeIfAbsent(e.sqlId, k -> new HashSet<>())
                                .add(e.stackTraceId);
                        OpStats os = m.opStats.computeIfAbsent(e.operationId, k -> new OpStats());
                        os.count++;
                        os.totalDurationNanos += dur;
                        if (e.sqlId >= 0) {
                            os.distinctSqls.add(e.sqlId);
                        }
                        m.eventsByOp.computeIfAbsent(e.operationId, k -> new ArrayList<>()).add(e);
                        if (isExecute(e.eventType) && e.parameterFingerprint != 0L) {
                            RedundantQueryDetector.Key key = new RedundantQueryDetector.Key(
                                    e.operationId, e.sqlId, e.parameterFingerprint, e.stackTraceId);
                            RedundantQueryDetector.Stats rs = m.redundant.computeIfAbsent(
                                    key, k -> new RedundantQueryDetector.Stats());
                            rs.count++;
                            rs.totalDurationNanos += dur;
                            if (e.parameterValuesId >= 0) {
                                m.valuesIdForKey.putIfAbsent(key, e.parameterValuesId);
                            }
                        }
                    }
                }
            });
            return m;
        }

        long wallNanos() {
            if (eventCount == 0) {
                return 0L;
            }
            return Math.max(0L, lastTs - firstTs);
        }

        private static boolean isExecute(byte eventTypeCode) {
            byte c = eventTypeCode;
            return c == EventType.EXECUTE_QUERY.code()
                    || c == EventType.EXECUTE_UPDATE.code()
                    || c == EventType.EXECUTE_BATCH.code();
        }
    }

    private static final class OpStats {
        long count;
        long totalDurationNanos;
        Set<Integer> distinctSqls = new HashSet<>();
    }

    // --- rendering ---

    private static void renderHead(PrintStream out, Path input) {
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"en\">");
        out.println("<head>");
        out.println("<meta charset=\"utf-8\">");
        out.println("<title>jdbc-prof report: " + htmlEscape(input.getFileName().toString()) + "</title>");
        out.println("<style>");
        out.println("""
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
                h1 { margin: 0 0 4px 0; font-size: 20px; }
                h2 { margin: 32px 0 12px 0; font-size: 16px; border-bottom: 1px solid var(--border); padding-bottom: 6px; }
                .meta { color: var(--fg-muted); font-size: 13px; margin-bottom: 20px; }
                .meta code { font-family: var(--mono); background: var(--bg-alt); padding: 1px 5px; border-radius: 3px; }
                .stats {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
                  gap: 12px;
                  margin-bottom: 16px;
                }
                .stat {
                  background: var(--bg-alt);
                  border: 1px solid var(--border);
                  border-radius: 6px;
                  padding: 12px 14px;
                }
                .stat-label { font-size: 12px; color: var(--fg-muted); text-transform: uppercase; letter-spacing: 0.04em; }
                .stat-value { font-size: 20px; font-weight: 600; font-family: var(--mono); margin-top: 2px; }
                ol.top { padding-left: 20px; margin: 8px 0 0 0; }
                ol.top li { margin: 2px 0; }
                ol.top code { font-family: var(--mono); font-size: 12px; color: var(--fg-muted); }
                .findings { display: grid; gap: 12px; margin: 8px 0 0 0; }
                .finding {
                  border: 1px solid #f0c36d;
                  background: #fff8e1;
                  border-left: 4px solid #e2a03f;
                  border-radius: 6px;
                  padding: 12px 14px;
                }
                .finding-head {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 12px;
                  align-items: baseline;
                  margin-bottom: 6px;
                }
                .finding-count { font-weight: 600; font-family: var(--mono); }
                .finding-time { color: var(--fg-muted); font-family: var(--mono); }
                .finding-kv { display: grid; grid-template-columns: 80px 1fr; gap: 4px 10px; font-size: 13px; }
                .finding-kv dt { color: var(--fg-muted); }
                .finding-kv dd { margin: 0; font-family: var(--mono); word-break: break-word; }
                .findings-empty { color: var(--fg-muted); font-size: 13px; font-style: italic; }
                .fg {
                  border: 1px solid var(--border);
                  border-radius: 4px;
                  background: var(--border);
                  font-family: var(--mono);
                  font-size: 11px;
                  overflow: hidden;
                  margin-top: 10px;
                }
                .fg-node {
                  display: flex;
                  flex-direction: column;
                  min-width: 1px;
                  overflow: hidden;
                }
                .fg-root { width: 100%; }
                .fg-children {
                  display: flex;
                  flex-direction: row;
                  width: 100%;
                  gap: 1px;
                  background: var(--border);
                }
                .fg-children > .fg-node {
                  flex-grow: var(--w);
                  flex-shrink: 1;
                  flex-basis: 0;
                }
                .fg-label {
                  background: hsl(var(--hue, 210), 55%, 72%);
                  color: #1b1f23;
                  padding: 3px 6px;
                  white-space: nowrap;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  line-height: 1.5;
                  cursor: default;
                }
                .fg-root > .fg-label {
                  background: var(--bg-alt);
                  color: var(--fg-muted);
                  font-size: 12px;
                  border-bottom: 1px solid var(--border);
                }
                table {
                  width: 100%;
                  border-collapse: collapse;
                  font-size: 13px;
                }
                th, td {
                  text-align: left;
                  padding: 6px 10px;
                  border-bottom: 1px solid var(--border);
                  vertical-align: top;
                }
                th {
                  background: var(--bg-alt);
                  font-weight: 600;
                  font-size: 12px;
                  text-transform: uppercase;
                  letter-spacing: 0.04em;
                  cursor: pointer;
                  user-select: none;
                  position: sticky;
                  top: 0;
                }
                th.num, td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
                th[aria-sort="asc"]::after { content: " \\25B2"; color: var(--accent); }
                th[aria-sort="desc"]::after { content: " \\25BC"; color: var(--accent); }
                tr:nth-child(even) td { background: var(--bg-alt); }
                code.sql { font-family: var(--mono); font-size: 12px; white-space: pre-wrap; word-break: break-word; }
                code.site { font-family: var(--mono); font-size: 12px; }
                .muted { color: var(--fg-muted); }
                footer { margin-top: 48px; font-size: 12px; color: var(--fg-muted); text-align: center; }
                """);
        out.println("</style>");
        out.println("</head>");
        out.println("<body>");
    }

    private static void renderSummary(PrintStream out, Model m) {
        out.println("<h1>jdbc-prof report</h1>");
        out.println("<div class=\"meta\">Recording: <code>" + htmlEscape(m.source.toString()) + "</code></div>");

        out.println("<div class=\"stats\">");
        stat(out, "Events", Long.toString(m.eventCount));
        stat(out, "DB time", formatDuration(m.totalDurationNanos));
        stat(out, "Wall time", formatDuration(m.wallNanos()));
        stat(out, "Templates", Integer.toString(m.sqls.size()));
        stat(out, "Call-sites", Integer.toString(m.stacks.size()));
        out.println("</div>");

        out.println("<h2>Top call-sites by DB time</h2>");
        out.println("<ol class=\"top\">");
        for (var entry : m.agg.topCallSites(3)) {
            StackFrameSnapshot[] frames = m.stacks.get(entry.getKey());
            StackFrameSnapshot site = Attribution.callSite(frames);
            out.println("  <li><code>" + htmlEscape(formatFrame(site)) + "</code> "
                    + "<span class=\"muted\">— "
                    + formatDuration(entry.getValue().totalDurationNanos())
                    + " across " + entry.getValue().count() + " events</span></li>");
        }
        out.println("</ol>");
    }

    private static void stat(PrintStream out, String label, String value) {
        out.println("  <div class=\"stat\">");
        out.println("    <div class=\"stat-label\">" + htmlEscape(label) + "</div>");
        out.println("    <div class=\"stat-value\">" + htmlEscape(value) + "</div>");
        out.println("  </div>");
    }

    private static void renderFindings(PrintStream out, List<N1Finding> findings) {
        out.println("<h2>N+1 findings</h2>");
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No N+1 patterns detected above the default "
                    + "thresholds (count \u2265 10, share \u2265 0.9).</p>");
            return;
        }
        out.println("<div class=\"findings\">");
        for (N1Finding f : findings) {
            out.println("  <div class=\"finding\">");
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">" + f.count() + "\u00D7</span> "
                    + "<span class=\"finding-time\">" + htmlEscape(formatDuration(f.totalDurationNanos())) + " total DB time</span>"
                    + "</div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>template</dt><dd>" + tdContent(sqlLabel(f.sql(), f.sqlId())) + "</dd>");
            out.println("      <dt>call-site</dt><dd>" + htmlEscape(formatFrame(f.representativeSite())) + "</dd>");
            if (f.ancestor() != null && !f.ancestor().equals(f.representativeSite())) {
                out.println("      <dt>ancestor</dt><dd>" + htmlEscape(formatFrame(f.ancestor())) + "</dd>");
            }
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static String tdContent(String s) {
        return "<code class=\"sql\">" + htmlEscape(s) + "</code>";
    }

    private static String sqlLabel(String sql, int sqlId) {
        if (sql != null) return sql;
        if (sqlId < 0) return "(no SQL)";
        return "sql[" + sqlId + "]";
    }

    private static void renderPairsTable(PrintStream out, Model m) {
        out.println("<h2>(Call-site, template) pairs</h2>");
        out.println("<table>");
        out.println("  <thead><tr>"
                + "<th>Call-site</th>"
                + "<th>Template</th>"
                + "<th class=\"num\" data-default-sort=\"desc\" aria-sort=\"desc\">DB time</th>"
                + "<th class=\"num\">Count</th>"
                + "</tr></thead>");
        out.println("  <tbody>");
        for (var entry : m.agg.topPairs(Integer.MAX_VALUE)) {
            Aggregator.PairKey k = entry.getKey();
            Aggregator.Stats s = entry.getValue();
            out.println("    <tr>"
                    + tdSite(m, k.stackTraceId())
                    + tdSql(m, k.sqlId())
                    + tdDuration(s.totalDurationNanos())
                    + tdCount(s.count())
                    + "</tr>");
        }
        out.println("  </tbody>");
        out.println("</table>");
    }

    private static void renderCallSitesTable(PrintStream out, Model m) {
        out.println("<h2>Call-sites</h2>");
        out.println("<table>");
        out.println("  <thead><tr>"
                + "<th>Call-site</th>"
                + "<th class=\"num\" data-default-sort=\"desc\" aria-sort=\"desc\">DB time</th>"
                + "<th class=\"num\">Count</th>"
                + "<th class=\"num\">Templates</th>"
                + "</tr></thead>");
        out.println("  <tbody>");
        for (var entry : m.agg.topCallSites(Integer.MAX_VALUE)) {
            int stackId = entry.getKey();
            Aggregator.Stats s = entry.getValue();
            int templates = m.templatesPerStack.getOrDefault(stackId, Set.of()).size();
            out.println("    <tr>"
                    + tdSite(m, stackId)
                    + tdDuration(s.totalDurationNanos())
                    + tdCount(s.count())
                    + tdCount(templates)
                    + "</tr>");
        }
        out.println("  </tbody>");
        out.println("</table>");
    }

    private static void renderTemplatesTable(PrintStream out, Model m) {
        out.println("<h2>Templates</h2>");
        out.println("<table>");
        out.println("  <thead><tr>"
                + "<th>Template</th>"
                + "<th class=\"num\" data-default-sort=\"desc\" aria-sort=\"desc\">DB time</th>"
                + "<th class=\"num\">Count</th>"
                + "<th class=\"num\">Call-sites</th>"
                + "</tr></thead>");
        out.println("  <tbody>");
        for (var entry : m.agg.topTemplates(Integer.MAX_VALUE)) {
            int sqlId = entry.getKey();
            Aggregator.Stats s = entry.getValue();
            int sites = m.stacksPerTemplate.getOrDefault(sqlId, Set.of()).size();
            out.println("    <tr>"
                    + tdSql(m, sqlId)
                    + tdDuration(s.totalDurationNanos())
                    + tdCount(s.count())
                    + tdCount(sites)
                    + "</tr>");
        }
        out.println("  </tbody>");
        out.println("</table>");
    }

    private static String tdSite(Model m, int stackId) {
        StackFrameSnapshot[] frames = m.stacks.get(stackId);
        String label = frames == null ? "(stack " + stackId + ")"
                : formatFrame(Attribution.callSite(frames));
        return "<td><code class=\"site\">" + htmlEscape(label) + "</code></td>";
    }

    private static String tdSql(Model m, int sqlId) {
        if (sqlId < 0) {
            return "<td class=\"muted\">(no SQL)</td>";
        }
        String sql = m.sqls.get(sqlId);
        String label = sql == null ? "(sql " + sqlId + ")" : sql;
        return "<td><code class=\"sql\">" + htmlEscape(label) + "</code></td>";
    }

    private static String tdDuration(long nanos) {
        return "<td class=\"num\" data-raw=\"" + nanos + "\">"
                + htmlEscape(formatDuration(nanos)) + "</td>";
    }

    private static String tdCount(long n) {
        return "<td class=\"num\" data-raw=\"" + n + "\">" + n + "</td>";
    }

    private static void renderFooter(PrintStream out) {
        out.println("<footer>Generated by jdbc-prof analyze</footer>");
        out.println("<script>");
        out.println("""
                // Click a column header to toggle sort. data-raw on a cell,
                // if present, overrides the visible text — lets us sort by
                // raw nanoseconds while showing "82.5 ms" in the cell.
                document.querySelectorAll('table').forEach(function (table) {
                  const ths = table.querySelectorAll('thead th');
                  ths.forEach(function (th, idx) {
                    th.addEventListener('click', function () {
                      const current = th.getAttribute('aria-sort');
                      const asc = current !== 'asc';
                      ths.forEach(function (other) { other.removeAttribute('aria-sort'); });
                      th.setAttribute('aria-sort', asc ? 'asc' : 'desc');
                      const tbody = table.querySelector('tbody');
                      const rows = Array.from(tbody.querySelectorAll('tr'));
                      const isNum = th.classList.contains('num');
                      rows.sort(function (a, b) {
                        const av = cellKey(a.children[idx], isNum);
                        const bv = cellKey(b.children[idx], isNum);
                        if (isNum) return asc ? av - bv : bv - av;
                        return asc ? String(av).localeCompare(String(bv)) : String(bv).localeCompare(String(av));
                      });
                      rows.forEach(function (r) { tbody.appendChild(r); });
                    });
                  });
                  // Apply each table's initial sort on load so the default
                  // "DB time desc" ordering matches what the header claims.
                  ths.forEach(function (th) {
                    if (th.dataset.defaultSort) {
                      // The rows are already pre-sorted server-side, so
                      // we just leave the aria-sort attribute alone.
                    }
                  });
                });
                function cellKey(td, isNum) {
                  const raw = td.dataset.raw;
                  if (raw !== undefined) return isNum ? Number(raw) : raw;
                  return isNum ? Number(td.textContent.trim()) : td.textContent.trim();
                }
                """);
        out.println("</script>");
        out.println("</body>");
        out.println("</html>");
    }

    // --- formatting helpers ---

    private static String formatFrame(StackFrameSnapshot f) {
        if (f == null) {
            return "(unattributed)";
        }
        return f.className() + '.' + f.methodName() + ':' + f.lineNumber();
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
