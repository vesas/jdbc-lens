package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.EventType;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;
import io.github.vesas.jdbcprof.storage.BinaryLogReader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        try (OutputStream os = Files.newOutputStream(output);
             PrintStream out = new PrintStream(os, false, StandardCharsets.UTF_8)) {
            write(input, out);
        }
    }

    public static void write(Path input, PrintStream out) throws IOException {
        Model m = Model.load(input);
        List<N1Finding> findings = new N1Detector().detect(m.executeAgg, m.sqls, m.stacks);
        renderHead(out, input);
        renderSummary(out, m);
        renderFindings(out, findings);
        renderPairsTable(out, m);
        renderCallSitesTable(out, m);
        renderTemplatesTable(out, m);
        renderFooter(out);
    }

    private static final class Model {
        Path source;
        Map<Integer, String> sqls = new HashMap<>();
        Map<Integer, StackFrameSnapshot[]> stacks = new HashMap<>();
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
