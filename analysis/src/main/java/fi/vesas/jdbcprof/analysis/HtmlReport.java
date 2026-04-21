package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.analysis.source.JavaSourceScanner;
import fi.vesas.jdbcprof.analysis.source.SourceRootInference;
import fi.vesas.jdbcprof.analysis.source.SourceScanResult;
import fi.vesas.jdbcprof.analysis.source.SourceSqlSite;
import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;
import fi.vesas.jdbcprof.storage.BinaryLogReader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        write(input, output, List.of(), false);
    }

    public static void write(Path input, Path output,
                              List<Path> sourceRootOverrides,
                              boolean noSourceScan) throws IOException {
        Model m = Model.load(input);
        SourceScanResult scan = resolveSourceScan(m, sourceRootOverrides, noSourceScan);
        try (OutputStream os = Files.newOutputStream(output);
             PrintStream out = new PrintStream(os, false, StandardCharsets.UTF_8)) {
            renderAll(out, input, m, scan);
        }
        writeDrillDownPages(output, m);
    }

    public static void write(Path input, PrintStream out) throws IOException {
        Model m = Model.load(input);
        renderAll(out, input, m, SourceScanResult.empty());
    }

    /**
     * Resolve source-root candidates in order:
     * <ol>
     *   <li>explicit {@code --source-root} overrides from the CLI,</li>
     *   <li>roots inferred from the recording's captured classpath,</li>
     *   <li>empty (report renders runtime-only with a footer hint).</li>
     * </ol>
     */
    private static SourceScanResult resolveSourceScan(Model m,
                                                       List<Path> overrides,
                                                       boolean noScan) {
        if (noScan) {
            return SourceScanResult.empty();
        }
        List<Path> roots = overrides;
        if (roots == null || roots.isEmpty()) {
            roots = SourceRootInference.infer(m.userDir, m.javaClassPath);
        }
        if (roots.isEmpty()) {
            return SourceScanResult.empty();
        }
        List<SourceSqlSite> sites = JavaSourceScanner.scan(roots);
        return SourceScanResult.of(sites);
    }

    private static void renderAll(PrintStream out, Path input, Model m, SourceScanResult scan) {
        List<N1Finding> findings = new N1Detector().detect(m.executeAgg, m.sqls, m.stacks);
        List<EmulatedCursorFinding> emulatedCursors = new EmulatedCursorDetector()
                .detect(m.executeAgg, m.sqls, m.stacks);
        List<RedundantFinding> redundant = new RedundantQueryDetector()
                .detect(m.redundant, m.sqls, m.ops, m.stacks, NO_OPERATION);
        EntityAccessAudit.Inputs entityInputs = new EntityAccessAudit.Inputs(
                m.eventsByOp, m.sqls, m.stacks,
                m.paramValuesById, m.ops, NO_OPERATION);
        List<EntityFinding> entities = EntityAccessAudit.detect(entityInputs);
        List<ReadThenWriteFinding> readThenWrite = ReadThenWriteDetector.detect(entityInputs);
        List<OverWideUpdateFinding> overWide = new OverWideUpdateDetector()
                .detect(m.sqls, m.eventsByOp, m.stacks);
        List<WriteAmplificationFinding> writeAmp =
                WriteAmplificationDetector.detect(entityInputs);
        List<IdleLockFinding> idleLocks = new IdleLockDetector()
                .detect(m.eventsByOp, m.ops, m.sqls, m.stacks, NO_OPERATION);
        Map<Long, List<Transaction>> txByOp = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Event>> e : m.eventsByOp.entrySet()) {
            long opId = e.getKey();
            if (opId == NO_OPERATION) {
                continue;
            }
            txByOp.put(opId, Transactions.reconstruct(opId, e.getValue()));
        }
        List<CommitPerRecordFinding> commitPerRecord = new CommitPerRecordDetector()
                .detect(txByOp, m.ops, m.sqls, m.stacks);
        List<TableAccessFinding> tableAccess =
                TableAccessAudit.detect(m.sqls, m.eventsByOp, m.stacks);
        FlameGraph.Node flame = FlameGraph.build(m.executeAgg, m.stacks);
        renderHead(out, input);
        renderSummary(out, m);
        section(out, "N+1 findings", () -> renderFindings(out, findings));
        section(out, "Emulated cursors (COBOL READ NEXT)",
                () -> renderEmulatedCursors(out, emulatedCursors));
        section(out, "Redundant queries", () -> renderRedundant(out, redundant, m));
        section(out, "Entity access audit", () -> renderEntityAccess(out, entities, m));
        section(out, "Read-then-write on the same row",
                () -> renderReadThenWrite(out, readThenWrite, m));
        section(out, "Wide UPDATEs (REWRITE RECORD smell)",
                () -> renderOverWideUpdate(out, overWide));
        section(out, "Write amplification (UPDATE-then-UPDATE on same row)",
                () -> renderWriteAmplification(out, writeAmp, m));
        section(out, "Transactions holding locks during non-DB work",
                () -> renderIdleLocks(out, idleLocks));
        section(out, "Transactions",
                () -> renderTransactions(out, txByOp, commitPerRecord, m));
        section(out, "Cache candidates (per-table access)",
                () -> renderTableAccess(out, tableAccess, scan));
        section(out, "Operations", () -> renderOperations(out, m));
        section(out, "Flamegraph", () -> renderFlameGraph(out, flame));
        section(out, "(Call-site, template) pairs", () -> renderPairsTable(out, m));
        section(out, "Call-sites", () -> renderCallSitesTable(out, m));
        section(out, "Templates", () -> renderTemplatesTable(out, m));
        renderFooter(out);
    }

    // Sections are collapsible and closed by default so the reader can
    // scan titles first and expand only what matters. <details>/<summary>
    // keeps the report self-contained — no JS, no external CSS.
    private static void section(PrintStream out, String title, Runnable body) {
        out.println("<details class=\"section\"><summary>"
                + htmlEscape(title) + "</summary>");
        body.run();
        out.println("</details>");
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
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No repeated (template, parameters) pairs "
                    + "within a single operation. The detector needs op-ids — call "
                    + "<code>Profiler.currentOperation(\"name\")</code> to scope detection.</p>");
            return;
        }
        out.println("<p class=\"findings-empty\">The same template fired more than once "
                + "with <strong>identical</strong> parameters inside one logical operation "
                + "\u2014 usually a lookup that should be cached or fetched once and passed "
                + "through the call chain. Different from N+1 above: N+1 repeats a template "
                + "with <strong>different</strong> parameters (a loop over rows); a redundant "
                + "finding repeats it with <strong>identical</strong> parameters (no "
                + "caching). Cards are ranked by total DB time.</p>");
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
            out.println("      <dt>inside operation</dt><dd>"
                    + htmlEscape(f.opName() == null ? "op[" + f.opId() + "]" : f.opName())
                    + "</dd>");
            out.println("      <dt>query fires here</dt><dd>"
                    + htmlEscape(formatFrame(f.callSite())) + "</dd>");
            String valuesRow = renderValues(f, m, valuesCaptured);
            if (valuesRow != null) {
                out.println(valuesRow);
            }
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + "Cache the result for the duration of the operation, or fetch it "
                    + "once at the top and pass the value through the call chain. If the "
                    + "lookup is cheap and the call path is short, confirm with the team "
                    + "that caching is worth the invalidation cost before changing "
                    + "anything.</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static String renderValues(RedundantFinding f, Model m, boolean valuesCaptured) {
        if (!valuesCaptured) {
            return "      <dt>parameters</dt><dd class=\"muted\">(not captured \u2014 "
                    + "enable with <code>ProfilerConfig.withCaptureParameterValues(true)</code>)"
                    + "</dd>";
        }
        RedundantQueryDetector.Key key = new RedundantQueryDetector.Key(
                f.opId(), f.sqlId(), f.parameterFingerprint(), f.stackTraceId());
        Set<Integer> ids = m.valuesIdForKey.get(key);
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        List<ParameterValues> distinct = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            ParameterValues pv = m.paramValuesById.get(id);
            if (pv != null) {
                distinct.add(pv);
            }
        }
        if (distinct.isEmpty()) {
            return null;
        }
        Map<Integer, String> labels = ParamLabels.labelsFor(f.sql());
        StringBuilder sb = new StringBuilder();
        sb.append("      <dt>parameters</dt><dd>");
        if (distinct.size() > 1) {
            // A single redundant finding aggregates events by fingerprint,
            // but fingerprint is a 64-bit hash; two distinct value sets
            // can land in the same bucket. Spell that out so the reader
            // doesn't assume all N executions used one set of values.
            sb.append("<div class=\"muted\">")
                    .append(distinct.size())
                    .append(" distinct parameter sets share this fingerprint "
                            + "\u2014 redundancy count is not reliable for this finding:</div>");
        }
        for (int idx = 0; idx < distinct.size(); idx++) {
            if (idx > 0) {
                sb.append("<br>");
            }
            sb.append("<code class=\"sql\">");
            List<String> slots = distinct.get(idx).slots();
            for (int i = 0; i < slots.size(); i++) {
                if (i > 0) sb.append(", ");
                int oneBased = i + 1;
                String label = labels.get(oneBased);
                String slot = "[" + oneBased + "] "
                        + (label == null ? slots.get(i) : label + "=" + slots.get(i));
                sb.append(htmlEscape(slot));
            }
            sb.append("</code>");
        }
        sb.append("</dd>");
        return sb.toString();
    }

    private static void renderEntityAccess(PrintStream out, List<EntityFinding> findings, Model m) {
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

    private static void renderReadThenWrite(PrintStream out,
                                             List<ReadThenWriteFinding> findings, Model m) {
        if (m.paramValuesById == null || m.paramValuesById.isEmpty()) {
            out.println("<p class=\"findings-empty\">Parameter values weren't captured; "
                    + "can't match SELECT + UPDATE on the same key. Enable with "
                    + "<code>ProfilerConfig.withCaptureParameterValues(true)</code>.</p>");
            return;
        }
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No SELECT + UPDATE/DELETE pair on the "
                    + "same row inside the same operation.</p>");
            return;
        }
        out.println("<div class=\"findings\">");
        for (ReadThenWriteFinding f : findings) {
            out.println("  <div class=\"finding\">");
            String entityLabel = f.table() + "." + f.column() + " = " + f.value();
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">READ \u2192 WRITE</span> "
                    + "<span class=\"finding-time\">"
                    + htmlEscape(formatDuration(
                            f.readDurationNanos() + f.writeDurationNanos()))
                    + " total"
                    + (f.betweenNanos() > 0
                        ? " \u00B7 " + htmlEscape(formatDuration(f.betweenNanos())) + " app time between"
                        : "")
                    + "</span></div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>entity</dt><dd><code class=\"site\">"
                    + htmlEscape(entityLabel) + "</code></dd>");
            out.println("      <dt>operation</dt><dd>"
                    + htmlEscape(f.opName() == null ? "op[" + f.opId() + "]" : f.opName())
                    + "</dd>");
            out.println("      <dt>read</dt><dd><code class=\"sql\">"
                    + htmlEscape(f.readSql() == null ? "sql[" + f.readSqlId() + "]" : f.readSql())
                    + "</code> <span class=\"muted\">\u2014 "
                    + htmlEscape(formatFrame(f.readCallSite()))
                    + "</span></dd>");
            out.println("      <dt>write</dt><dd><code class=\"sql\">"
                    + htmlEscape(f.writeSql() == null ? "sql[" + f.writeSqlId() + "]" : f.writeSql())
                    + "</code> <span class=\"muted\">\u2014 "
                    + htmlEscape(formatFrame(f.writeCallSite()))
                    + "</span></dd>");
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + "Collapse into one UPDATE (with the read's conditions moved into its WHERE) "
                    + "or one <code>UPDATE \u2026 RETURNING</code>.</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static void renderEmulatedCursors(PrintStream out,
                                               List<EmulatedCursorFinding> findings) {
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No emulated-cursor pairs "
                    + "(a <code>SELECT MIN(key) … WHERE key &gt; ?</code> walk "
                    + "paired with a <code>SELECT … WHERE key = ?</code> fetch "
                    + "from the same outer method) were observed above the default "
                    + "threshold of " + EmulatedCursorDetector.DEFAULT_MIN_WALKS + " walks.</p>");
            return;
        }
        out.println("<p class=\"findings-empty\">A pair of templates whose shape is "
                + "the SQL fingerprint of a COBOL <code>READ NEXT</code> loop the "
                + "transpiler couldn't recover: one query walks a key column with "
                + "<code>MIN</code> / <code>MAX</code> and a strict inequality, the "
                + "other fetches row fields by that same key. Both queries live under "
                + "the same outer method. The fix is not the usual N+1 batch-or-join "
                + "— it's to replace the <em>pair</em> with one scrolling "
                + "<code>ResultSet</code>, or one set-oriented "
                + "<code>GROUP BY</code>. Nested pairs (a "
                + "<code>PERFORM UNTIL</code> inside a "
                + "<code>PERFORM UNTIL</code>) are flagged — those are almost "
                + "always replaceable with a single <code>JOIN … GROUP BY</code>.</p>");
        out.println("<div class=\"findings\">");
        for (EmulatedCursorFinding f : findings) {
            out.println("  <div class=\"finding\">");
            String nestedBadge = f.nested()
                    ? " <span class=\"wa-tag wa-redundant\">nested</span>"
                    : "";
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">"
                    + f.walkCount() + " walks × " + f.fetchCount() + " fetches</span> "
                    + "<span class=\"finding-time\">"
                    + htmlEscape(formatDuration(f.totalDurationNanos()))
                    + " total DB time" + nestedBadge
                    + "</span></div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>walked table</dt><dd><code class=\"site\">"
                    + htmlEscape(f.table() + "." + f.keyColumn())
                    + "</code></dd>");
            out.println("      <dt>walk</dt><dd><code class=\"sql\">"
                    + htmlEscape(f.walkSql() == null ? "sql[" + f.walkSqlId() + "]" : f.walkSql())
                    + "</code> <span class=\"muted\">— "
                    + htmlEscape(formatFrame(f.walkSite()))
                    + "</span></dd>");
            out.println("      <dt>fetch</dt><dd><code class=\"sql\">"
                    + htmlEscape(f.fetchSql() == null ? "sql[" + f.fetchSqlId() + "]" : f.fetchSql())
                    + "</code> <span class=\"muted\">— "
                    + htmlEscape(formatFrame(f.fetchSite()))
                    + "</span></dd>");
            out.println("      <dt>outer method</dt><dd>"
                    + htmlEscape(formatFrame(f.ancestor()))
                    + "</dd>");
            if (f.nested()) {
                out.println("      <dt>nested in</dt><dd>"
                        + htmlEscape(formatFrame(f.outerAncestor()))
                        + " <span class=\"muted\">— another emulated cursor "
                        + "is already walking above this one</span></dd>");
            }
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + emulatedCursorSuggestion(f) + "</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static String emulatedCursorSuggestion(EmulatedCursorFinding f) {
        if (f.nested()) {
            return "Two emulated cursors nested inside each other. Almost always a "
                    + "transpiled master/detail loop that a single "
                    + "<code>SELECT … FROM " + htmlEscape(f.table())
                    + " JOIN … GROUP BY</code> would replace — the "
                    + "transpiler couldn't recover set semantics from paragraph-by-"
                    + "paragraph source, but the analyzer can see them here.";
        }
        return "Replace the walk + fetch pair with one scrolling "
                + "<code>ResultSet</code> (<code>SELECT … FROM "
                + htmlEscape(f.table())
                + " ORDER BY " + htmlEscape(f.keyColumn())
                + "</code>) and iterate it via <code>ResultSet.next()</code>. "
                + "Each iteration then costs one row, not two round-trips.";
    }

    private static void renderOverWideUpdate(PrintStream out,
                                             List<OverWideUpdateFinding> findings) {
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No UPDATE template sets more than "
                    + OverWideUpdateDetector.DEFAULT_THRESHOLD + " columns.</p>");
            return;
        }
        out.println("<div class=\"findings\">");
        for (OverWideUpdateFinding f : findings) {
            out.println("  <div class=\"finding\">");
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">" + f.setColumnCount() + " columns SET</span> "
                    + "<span class=\"finding-time\">"
                    + f.executeCount() + " executions \u00B7 "
                    + htmlEscape(formatDuration(f.totalDurationNanos()))
                    + "</span></div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>template</dt><dd><code class=\"sql\">"
                    + htmlEscape(f.sql() == null ? "sql[" + f.sqlId() + "]" : f.sql())
                    + "</code></dd>");
            out.println("      <dt>call-site</dt><dd>"
                    + htmlEscape(formatFrame(f.representativeSite()))
                    + "</dd>");
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + "Only SET the columns that actually changed. Every wide UPDATE fires "
                    + "triggers, CDC, and replication for no-op changes and muddies audit "
                    + "diffs.</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static void renderTableAccess(PrintStream out,
                                          List<TableAccessFinding> findings,
                                          SourceScanResult scan) {
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No table activity was recorded.</p>");
            return;
        }
        out.println("<p class=\"findings-empty\">Every table touched in this "
                + "recording with its readers and writers. Tables labelled "
                + "<strong>read-only</strong> had no writes during capture \u2014 "
                + "that makes them the cleanest cache candidates, but the "
                + "label is only ever a lower bound on safety (writes outside "
                + "the recording window stay invisible). For read/write tables, "
                + "the writer call-site count is the number of places that "
                + "need invalidation (or write-through) hooks."
                + (scan.isEmpty()
                    ? " <em>Static source scan is off \u2014 pass "
                        + "<code>--source-root &lt;path&gt;</code> (or record "
                        + "with a profiler that embeds the classpath) to have "
                        + "the report cross-check against every SQL literal in "
                        + "the source tree.</em>"
                    : " <em>Source-scan is enabled: each row also shows "
                        + "<strong>static readers/writers</strong> seen in "
                        + ".java files. A <span class=\"wa-tag wa-redundant\">"
                        + "UNEXERCISED WRITES</span> badge means the source has "
                        + "writer sites that didn't run during this recording "
                        + "\u2014 the real answer to 'is it safe to cache?'.</em>")
                + "</p>");
        out.println("<div class=\"findings\">");
        for (TableAccessFinding f : findings) {
            SourceScanResult.TableSites staticSites = scan.forTable(f.table());
            boolean unexercisedWrites =
                    f.writeEvents() == 0L && !staticSites.writers().isEmpty();

            out.println("  <div class=\"finding\">");
            String badge;
            if (unexercisedWrites) {
                badge = "<span class=\"wa-tag wa-redundant\">unexercised writes</span>";
            } else if (f.readOnly()) {
                badge = "<span class=\"wa-tag wa-mergeable\">read-only in recording</span>";
            } else {
                badge = "<span class=\"wa-tag wa-overlap\">" + f.writeEvents() + " writes</span>";
            }
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\"><code class=\"site\">"
                    + htmlEscape(f.table()) + "</code></span> "
                    + "<span class=\"finding-time\">"
                    + f.readEvents() + " reads \u00B7 " + badge
                    + " \u00B7 " + f.distinctReaderCallSites() + " reader sites \u00B7 "
                    + f.distinctWriterCallSites() + " writer sites \u00B7 "
                    + f.opsWithReads() + " ops"
                    + (scan.isEmpty() ? ""
                        : " \u00B7 static: " + staticSites.readers().size() + " readers / "
                            + staticSites.writers().size() + " writers")
                    + "</span></div>");
            out.println("    <dl class=\"finding-kv\">");
            if (!f.readers().isEmpty()) {
                out.println("      <dt>readers</dt><dd>");
                for (TableAccessFinding.ReaderHit h : f.readers()) {
                    out.println("        <div><code class=\"sql\">"
                            + htmlEscape(h.sql() == null ? "sql[" + h.sqlId() + "]" : h.sql())
                            + "</code> <span class=\"muted\">\u2014 "
                            + htmlEscape(formatFrame(h.callSite()))
                            + " \u00B7 " + h.eventCount() + "\u00D7</span></div>");
                }
                out.println("      </dd>");
            }
            if (!f.writers().isEmpty()) {
                out.println("      <dt>writers</dt><dd>");
                for (TableAccessFinding.WriterHit h : f.writers()) {
                    String setInfo = h.setColumns().isEmpty() ? ""
                            : " \u00B7 SET " + htmlEscape(String.join(", ", h.setColumns()));
                    out.println("        <div><span class=\"wa-tag wa-overlap\">"
                            + htmlEscape(h.kind()) + "</span> <code class=\"sql\">"
                            + htmlEscape(h.sql() == null ? "sql[" + h.sqlId() + "]" : h.sql())
                            + "</code> <span class=\"muted\">\u2014 "
                            + htmlEscape(formatFrame(h.callSite()))
                            + " \u00B7 " + h.eventCount() + "\u00D7"
                            + setInfo + "</span></div>");
                }
                out.println("      </dd>");
            } else {
                out.println("      <dt>writers</dt><dd class=\"muted\">"
                        + "none observed \u2014 trivial cache candidate, subject "
                        + "to recording coverage.</dd>");
            }
            if (!staticSites.isEmpty()) {
                renderStaticSites(out, staticSites);
            }
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + cacheSuggestion(f, staticSites, unexercisedWrites) + "</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static void renderStaticSites(PrintStream out, SourceScanResult.TableSites s) {
        if (!s.readers().isEmpty()) {
            out.println("      <dt>static readers</dt><dd>");
            for (SourceScanResult.SiteRef ref : s.readers()) {
                out.println("        <div><span class=\"muted\">"
                        + htmlEscape(ref.file() + ":" + ref.line())
                        + "</span> <code class=\"sql\">" + htmlEscape(ref.snippet())
                        + "</code></div>");
            }
            out.println("      </dd>");
        }
        if (!s.writers().isEmpty()) {
            out.println("      <dt>static writers</dt><dd>");
            for (SourceScanResult.SiteRef ref : s.writers()) {
                out.println("        <div><span class=\"wa-tag wa-overlap\">"
                        + htmlEscape(ref.kind()) + "</span> "
                        + "<span class=\"muted\">"
                        + htmlEscape(ref.file() + ":" + ref.line())
                        + "</span> <code class=\"sql\">" + htmlEscape(ref.snippet())
                        + "</code></div>");
            }
            out.println("      </dd>");
        }
    }

    private static String cacheSuggestion(TableAccessFinding f,
                                           SourceScanResult.TableSites staticSites,
                                           boolean unexercisedWrites) {
        if (unexercisedWrites) {
            return "DANGER: runtime saw no writes, but source has "
                    + staticSites.writers().size() + " writer site(s) that "
                    + "simply weren't exercised in this recording. Treat "
                    + "<em>every</em> static writer as a required invalidation "
                    + "hook before caching \u2014 the read-only label would be "
                    + "a lie in production.";
        }
        if (f.readOnly()) {
            return "No writes observed \u2014 safe to cache for the duration "
                    + "of this workload. Verify against a longer recording before "
                    + "treating the table as immutable in production.";
        }
        if (f.writeEvents() * 4 < f.readEvents()
                && f.distinctWriterCallSites() <= 3) {
            return "Read/write ratio favours a cache. Writes come from "
                    + f.distinctWriterCallSites() + " call-site(s) \u2014 wire "
                    + "invalidation (or a write-through wrapper) at each, keyed "
                    + "by the columns shown in writer SET clauses.";
        }
        return "Writes are frequent or scattered across many call-sites. "
                + "Caching this table is only worthwhile with a single "
                + "write gateway; otherwise stale reads are likely.";
    }

    private static void renderWriteAmplification(PrintStream out,
                                                  List<WriteAmplificationFinding> findings,
                                                  Model m) {
        if (m.paramValuesById == null || m.paramValuesById.isEmpty()) {
            out.println("<p class=\"findings-empty\">Parameter values weren't captured; "
                    + "can't match repeated UPDATEs on the same row. Enable with "
                    + "<code>ProfilerConfig.withCaptureParameterValues(true)</code>.</p>");
            return;
        }
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No row was UPDATEd more than once "
                    + "inside the same operation.</p>");
            return;
        }
        out.println("<p class=\"findings-empty\">Two or more UPDATEs landed on the "
                + "same row inside one operation \u2014 each a separate round-trip, "
                + "trigger fire, CDC event, and replication message. The "
                + "<strong>overlap</strong> label tells you the flavour: "
                + "<em>mergeable</em> means disjoint SET columns that collapse "
                + "cleanly into one UPDATE; <em>overlapping</em> means some columns "
                + "get written twice (the last write wins \u2014 often a stale copy "
                + "from a different service); <em>redundant</em> means the exact "
                + "same SET clause fired more than once.</p>");
        out.println("<div class=\"findings\">");
        for (WriteAmplificationFinding f : findings) {
            out.println("  <div class=\"finding\">");
            String entityLabel = f.table() + "." + f.column() + " = " + f.value();
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">"
                    + f.hits().size() + "\u00D7 UPDATE</span> "
                    + "<span class=\"finding-time\">"
                    + htmlEscape(formatDuration(f.totalDurationNanos()))
                    + " total DB time \u00B7 " + overlapLabel(f.overlap())
                    + "</span></div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>entity</dt><dd><code class=\"site\">"
                    + htmlEscape(entityLabel) + "</code></dd>");
            out.println("      <dt>operation</dt><dd>"
                    + htmlEscape(f.opName() == null ? "op[" + f.opId() + "]" : f.opName())
                    + "</dd>");
            out.println("      <dt>touched by</dt><dd>");
            for (WriteAmplificationFinding.UpdateHit h : f.hits()) {
                out.println("        <div><code class=\"sql\">"
                        + htmlEscape(h.sql() == null ? "sql[" + h.sqlId() + "]" : h.sql())
                        + "</code> <span class=\"muted\">\u2014 "
                        + htmlEscape(formatFrame(h.callSite()))
                        + " \u00B7 "
                        + htmlEscape(formatDuration(h.durationNanos()))
                        + "</span></div>");
            }
            out.println("      </dd>");
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + writeAmpSuggestion(f.overlap())
                    + "</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    private static String overlapLabel(WriteAmplificationFinding.Overlap o) {
        return switch (o) {
            case DISJOINT -> "<span class=\"wa-tag wa-mergeable\">mergeable</span>";
            case OVERLAPPING -> "<span class=\"wa-tag wa-overlap\">overlapping</span>";
            case IDENTICAL -> "<span class=\"wa-tag wa-redundant\">redundant</span>";
        };
    }

    private static String writeAmpSuggestion(WriteAmplificationFinding.Overlap o) {
        return switch (o) {
            case DISJOINT -> "Collapse into one UPDATE that sets all of the "
                    + "columns at once. One round-trip, one trigger fire, one "
                    + "CDC event.";
            case OVERLAPPING -> "The later UPDATE silently overwrites values "
                    + "the earlier one set. Usually two services racing to own "
                    + "the same column \u2014 decide who does, and collapse the "
                    + "rest into one UPDATE.";
            case IDENTICAL -> "Every UPDATE here sets the same columns \u2014 "
                    + "at most one of them is doing useful work. Fetch the row "
                    + "state once and only write when something actually "
                    + "changed.";
        };
    }

    private static void renderIdleLocks(PrintStream out, List<IdleLockFinding> findings) {
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No explicit transaction held "
                    + "locks across an idle gap longer than "
                    + formatDuration(IdleLockDetector.DEFAULT_THRESHOLD_NANOS)
                    + ". Either all TXs ran back-to-back DB calls, or the app "
                    + "uses autocommit throughout (see drill-down for shape).</p>");
            return;
        }
        out.println("<p class=\"findings-empty\">Each TX below committed or rolled "
                + "back at least one write, but between two of its statements the app "
                + "spent real time <strong>outside</strong> the database — usually an "
                + "HTTP call, a file read, or heavy CPU work. Row/page locks taken by "
                + "the earlier writes were held the whole time. The call-site listed "
                + "below each bar is where execution was when the gap started: that's "
                + "almost always where the non-DB work is happening.</p>");
        out.println("<div class=\"findings\">");
        for (IdleLockFinding f : findings) {
            double idlePct = f.txDurationNanos() <= 0 ? 0.0
                    : 100.0 * f.maxIdleGapNanos() / f.txDurationNanos();
            out.println("  <div class=\"finding\">");
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">"
                    + htmlEscape(formatDuration(f.maxIdleGapNanos()))
                    + " idle</span> "
                    + "<span class=\"finding-time\">"
                    + String.format(Locale.ROOT, "%.0f%%", idlePct)
                    + " of a " + htmlEscape(formatDuration(f.txDurationNanos()))
                    + " transaction"
                    + "</span></div>");
            out.println("    " + renderTxGantt(f));
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>operation</dt><dd>"
                    + htmlEscape(f.opName() == null ? "op[" + f.opId() + "]" : f.opName())
                    + "</dd>");
            out.println("      <dt>app was here</dt><dd>"
                    + htmlEscape(formatFrame(f.siteBeforeGap()))
                    + "</dd>");
            if (f.sqlBeforeGap() != null) {
                out.println("      <dt>last statement</dt><dd><code class=\"sql\">"
                        + htmlEscape(f.sqlBeforeGap()) + "</code></dd>");
            }
            if (f.sqlAfterGap() != null) {
                out.println("      <dt>next statement</dt><dd><code class=\"sql\">"
                        + htmlEscape(f.sqlAfterGap()) + "</code></dd>");
            }
            if (!f.writes().isEmpty()) {
                out.println("      <dt>locks held</dt><dd>");
                for (IdleLockFinding.WriteRef w : f.writes()) {
                    out.println("        <div><code class=\"sql\">"
                            + htmlEscape(w.sql()) + "</code>"
                            + (w.count() > 1
                                ? " <span class=\"muted\">\u00D7 " + w.count() + "</span>"
                                : "")
                            + "</div>");
                }
                out.println("      </dd>");
            }
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + "Move the non-DB work outside the transaction, or split into "
                    + "two transactions around the gap. If the inner work must run "
                    + "atomically, commit first and retry compensating work on failure."
                    + "</dd>");
            out.println("    </dl>");
            out.println("  </div>");
        }
        out.println("</div>");
    }

    /**
     * Horizontal proportional bar: one cell per {@link
     * IdleLockFinding.Segment}, width = segment duration / TX duration.
     * Colours are driven by segment kind so the viewer sees the write /
     * query / idle mix at a glance without reading the legend.
     */
    private static String renderTxGantt(IdleLockFinding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"tx-bar\" role=\"img\" aria-label=\"Transaction timeline: ")
                .append(htmlEscape(formatDuration(f.txDurationNanos())))
                .append(" total, ")
                .append(htmlEscape(formatDuration(f.maxIdleGapNanos())))
                .append(" idle\">");
        long total = Math.max(1L, f.txDurationNanos());
        for (IdleLockFinding.Segment seg : f.segments()) {
            double pct = 100.0 * seg.durationNanos() / total;
            String cls = "tx-seg tx-" + seg.kind().name().toLowerCase(Locale.ROOT).replace('_', '-');
            if (seg.isMaxGap()) {
                cls += " tx-max-gap";
            }
            String tip;
            if (seg.kind() == IdleLockFinding.SegmentKind.IDLE) {
                tip = "IDLE " + formatDuration(seg.durationNanos())
                        + (seg.isMaxGap() ? " \u2190 longest gap" : "");
            } else {
                tip = seg.kind().name() + " " + formatDuration(seg.durationNanos())
                        + (seg.sql() != null ? " \u2014 " + seg.sql() : "");
            }
            sb.append(String.format(Locale.ROOT,
                    "<span class=\"%s\" style=\"flex:%.4f 0 0\" title=\"%s\"></span>",
                    cls, Math.max(0.0001, pct), htmlEscape(tip)));
        }
        sb.append("</div>");
        sb.append("<div class=\"tx-legend\">");
        sb.append("<span class=\"tx-legend-item\"><i class=\"tx-seg tx-execute-query\"></i> read</span>");
        sb.append("<span class=\"tx-legend-item\"><i class=\"tx-seg tx-execute-update\"></i> write</span>");
        sb.append("<span class=\"tx-legend-item\"><i class=\"tx-seg tx-idle\"></i> idle</span>");
        sb.append("<span class=\"tx-legend-item\"><i class=\"tx-seg tx-max-gap\"></i> longest gap</span>");
        sb.append("<span class=\"tx-legend-item\"><i class=\"tx-seg tx-commit\"></i> commit</span>");
        sb.append("</div>");
        return sb.toString();
    }

    private static void renderTransactions(PrintStream out,
                                           Map<Long, List<Transaction>> txByOp,
                                           List<CommitPerRecordFinding> commitPerRecord,
                                           Model m) {
        List<Transaction> all = new ArrayList<>();
        for (List<Transaction> list : txByOp.values()) {
            all.addAll(list);
        }
        if (all.isEmpty()) {
            out.println("<p class=\"findings-empty\">No explicit transactions were "
                    + "observed. Every JDBC thread in this recording stayed in "
                    + "autocommit mode, so each statement is its own transaction. "
                    + "If that's unexpected, check that the app actually calls "
                    + "<code>setAutoCommit(false)</code> before doing multi-statement "
                    + "work.</p>");
            return;
        }
        out.println("<p class=\"findings-empty\">Every explicit transaction reconstructed "
                + "from <code>COMMIT</code> / <code>ROLLBACK</code> boundaries. The "
                + "overview shows the overall transaction shape of the workload; the "
                + "finding cards below flag runs of many short back-to-back transactions "
                + "that suggest an over-narrow TX boundary (commit-per-record).</p>");

        renderTransactionOverview(out, all);

        if (commitPerRecord.isEmpty()) {
            out.println("<p class=\"findings-empty\">No commit-per-record runs detected. "
                    + "Runs must contain at least "
                    + CommitPerRecordDetector.DEFAULT_MIN_RUN_LENGTH
                    + " consecutive short TXs sharing an outer call-site.</p>");
        } else {
            out.println("<h3>Commit-per-record runs</h3>");
            out.println("<p class=\"findings-empty\">Each card below is a run of many "
                    + "short explicit transactions fired back-to-back from the same "
                    + "outer method. Each TX did only a handful of statements and "
                    + "committed — the signature of a loop that commits every "
                    + "iteration (transpiled COBOL <code>EXEC SQL COMMIT</code>, or a "
                    + "per-object ORM save inside a <code>for</code>). Cards are "
                    + "ranked by total wall time the pattern consumed.</p>");
            out.println("<div class=\"findings\">");
            for (CommitPerRecordFinding f : commitPerRecord) {
                renderCommitPerRecordCard(out, f);
            }
            out.println("</div>");
        }

        renderTopTransactionsTable(out, all, m);
    }

    private static void renderTransactionOverview(PrintStream out, List<Transaction> all) {
        int total = all.size();
        int committed = 0;
        int rolledBack = 0;
        int open = 0;
        long totalDbTime = 0L;
        long totalWall = 0L;
        int writes = 0;
        int reads = 0;
        long[] walls = new long[total];
        for (int i = 0; i < total; i++) {
            Transaction tx = all.get(i);
            if (tx.committed()) committed++;
            else if (tx.rolledBack()) rolledBack++;
            else open++;
            totalDbTime += tx.dbTimeNanos();
            long w = tx.wallClockNanos();
            totalWall += w;
            walls[i] = w;
            writes += tx.writeCount();
            reads += tx.readCount();
        }
        long median = percentile(walls, 0.50);
        long p99 = percentile(walls, 0.99);

        out.println("<div class=\"tx-overview\">");
        out.println("  <dl class=\"finding-kv\">");
        out.println("    <dt>explicit transactions</dt><dd>" + total + "</dd>");
        out.println("    <dt>committed / rolled back / open</dt><dd>"
                + committed + " / " + rolledBack + " / " + open + "</dd>");
        out.println("    <dt>median wall-clock</dt><dd>"
                + htmlEscape(formatDuration(median)) + "</dd>");
        out.println("    <dt>p99 wall-clock</dt><dd>"
                + htmlEscape(formatDuration(p99)) + "</dd>");
        out.println("    <dt>total wall time inside TXs</dt><dd>"
                + htmlEscape(formatDuration(totalWall)) + "</dd>");
        out.println("    <dt>total DB time inside TXs</dt><dd>"
                + htmlEscape(formatDuration(totalDbTime)) + "</dd>");
        out.println("    <dt>writes / reads inside TXs</dt><dd>"
                + writes + " / " + reads + "</dd>");
        out.println("  </dl>");
        out.println("</div>");
    }

    private static long percentile(long[] sortedCandidate, double fraction) {
        if (sortedCandidate.length == 0) return 0L;
        long[] copy = sortedCandidate.clone();
        java.util.Arrays.sort(copy);
        int idx = (int) Math.min(copy.length - 1, Math.max(0, Math.round(fraction * (copy.length - 1))));
        return copy[idx];
    }

    private static void renderCommitPerRecordCard(PrintStream out,
                                                  CommitPerRecordFinding f) {
        out.println("  <div class=\"finding\">");
        out.println("    <div class=\"finding-head\">"
                + "<span class=\"finding-count\">" + f.runLength() + "×</span> "
                + "<span class=\"finding-time\">"
                + htmlEscape(formatDuration(f.totalWallNanos()))
                + " total wall · "
                + htmlEscape(formatDuration(f.avgTxnWallNanos()))
                + " avg per TX</span></div>");
        out.println("    <dl class=\"finding-kv\">");
        out.println("      <dt>inside operation</dt><dd>"
                + htmlEscape(f.opName() == null ? "op[" + f.opId() + "]" : f.opName())
                + "</dd>");
        out.println("      <dt>loop body here</dt><dd>"
                + htmlEscape(formatFrame(f.representativeCallSite())) + "</dd>");
        out.println("      <dt>outer loop (probable fix)</dt><dd>"
                + (f.commonAncestor() == null
                    ? "<span class=\"muted\">same as query site</span>"
                    : htmlEscape(formatFrame(f.commonAncestor())))
                + "</dd>");
        out.println("      <dt>per-TX shape</dt><dd>"
                + f.writesPerTxn() + " writes · "
                + f.readsPerTxn() + " reads</dd>");
        if (!f.sampleSqls().isEmpty()) {
            out.println("      <dt>statements in one iteration</dt><dd>");
            for (String s : f.sampleSqls()) {
                out.println("        <div><code class=\"sql\">"
                        + htmlEscape(s) + "</code></div>");
            }
            out.println("      </dd>");
        }
        out.println("      <dt>total DB time in run</dt><dd>"
                + htmlEscape(formatDuration(f.totalDbTimeNanos())) + "</dd>");
        out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                + "Widen the transaction boundary so a batch of records shares one "
                + "<code>commit()</code>. Each commit costs a WAL flush and a "
                + "client round-trip; amortising that across 50–500 records "
                + "typically reclaims most of the run's wall time. If atomicity is "
                + "per-record by design, consider whether the <em>commit</em> is "
                + "what's needed or whether a savepoint + single outer commit would "
                + "suffice."
                + "</dd>");
        out.println("    </dl>");
        out.println("  </div>");
    }

    private static void renderTopTransactionsTable(PrintStream out,
                                                   List<Transaction> all,
                                                   Model m) {
        if (all.isEmpty()) {
            return;
        }
        List<Transaction> top = new ArrayList<>(all);
        top.sort(Comparator.comparingLong(Transaction::wallClockNanos).reversed());
        int cap = Math.min(20, top.size());
        top = top.subList(0, cap);

        out.println("<h3>Longest transactions</h3>");
        out.println("<p class=\"findings-empty\">Top " + cap
                + " explicit transactions by wall-clock duration. Useful for "
                + "eyeballing outliers the detector did not otherwise flag.</p>");
        out.println("<table>");
        out.println("  <thead><tr>");
        out.println("    <th>operation</th>");
        out.println("    <th>thread</th>");
        out.println("    <th>wall</th>");
        out.println("    <th>DB</th>");
        out.println("    <th>writes</th>");
        out.println("    <th>reads</th>");
        out.println("    <th>outcome</th>");
        out.println("    <th>opening call-site</th>");
        out.println("  </tr></thead>");
        out.println("  <tbody>");
        for (Transaction tx : top) {
            String opName = m.ops.get(tx.opId());
            if (opName == null) opName = "op[" + tx.opId() + "]";
            StackFrameSnapshot[] frames = m.stacks.get(tx.firstStackId());
            StackFrameSnapshot site = Attribution.callSite(frames);
            String outcome = tx.committed() ? "commit"
                    : tx.rolledBack() ? "rollback" : "open";
            out.println("    <tr>");
            out.println("      <td>" + htmlEscape(opName) + "</td>");
            out.println("      <td>" + tx.threadId() + "</td>");
            out.println("      <td data-raw=\"" + tx.wallClockNanos() + "\">"
                    + htmlEscape(formatDuration(tx.wallClockNanos())) + "</td>");
            out.println("      <td data-raw=\"" + tx.dbTimeNanos() + "\">"
                    + htmlEscape(formatDuration(tx.dbTimeNanos())) + "</td>");
            out.println("      <td>" + tx.writeCount() + "</td>");
            out.println("      <td>" + tx.readCount() + "</td>");
            out.println("      <td>" + outcome + "</td>");
            out.println("      <td>" + htmlEscape(formatFrame(site)) + "</td>");
            out.println("    </tr>");
        }
        out.println("  </tbody>");
        out.println("</table>");
    }

    private static void renderOperations(PrintStream out, Model m) {
        boolean anyOp = m.ops != null && !m.ops.isEmpty();
        if (!anyOp) {
            out.println("<p class=\"findings-empty\">No op-ids were recorded. "
                    + "Call <code>Profiler.currentOperation(\"name\")</code> "
                    + "at the start of each logical unit (request, test, job) "
                    + "to group events.</p>");
            return;
        }
        renderInvocationSwimlanes(out, m);
        out.println("<p class=\"findings-empty\">"
                + "<strong>Wall</strong> is the clock time the op took end-to-end. "
                + "<strong>DB</strong> is how much of that was spent inside JDBC calls. "
                + "The split bar is DB (blue) vs. non-DB (gray) — a mostly-gray bar "
                + "means the app, network, or external I/O is where the time went, "
                + "not the database.</p>");
        out.println("<table>");
        out.println("  <thead><tr>"
                + "<th>Operation</th>"
                + "<th class=\"num\">Wall</th>"
                + "<th class=\"num\" data-default-sort=\"desc\" aria-sort=\"desc\">DB</th>"
                + "<th class=\"num\">Non-DB</th>"
                + "<th>DB vs. non-DB</th>"
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
                    List<Event> opEvents = m.eventsByOp.getOrDefault(opId, List.of());
                    EventGaps.OpBreakdown br = EventGaps.forOp(opEvents);
                    out.println("    <tr>"
                            + labelCell
                            + tdDuration(br.wallNanos())
                            + tdDuration(br.dbNanos())
                            + tdDuration(br.nonDbNanos())
                            + "<td class=\"split-cell\" data-raw=\""
                            + br.nonDbNanos() + "\">"
                            + renderSplitBar(br) + "</td>"
                            + tdCount(s.count)
                            + tdCount(s.distinctSqls.size())
                            + "</tr>");
                });
        out.println("  </tbody>");
        out.println("</table>");
    }

    /**
     * Two-segment horizontal bar: DB (blue) vs. non-DB (gray). Width
     * of each side is the fraction of wall time. A tiny visual that
     * makes "this op is 95% app code" and "this op is 100% DB" jump
     * out without the reader having to compare two numbers.
     */
    private static String renderSplitBar(EventGaps.OpBreakdown br) {
        if (br.wallNanos() <= 0L) {
            return "<span class=\"muted\">\u2014</span>";
        }
        double dbPct = 100.0 * br.dbFraction();
        double nonDbPct = 100.0 * br.nonDbFraction();
        String title = "DB " + formatDuration(br.dbNanos())
                + " (" + String.format(Locale.ROOT, "%.0f%%", dbPct) + ") \u00B7 non-DB "
                + formatDuration(br.nonDbNanos())
                + " (" + String.format(Locale.ROOT, "%.0f%%", nonDbPct) + ")";
        return "<div class=\"split-bar\" title=\"" + htmlEscape(title) + "\">"
                + String.format(Locale.ROOT,
                        "<span class=\"split-db\" style=\"flex:%.4f 0 0\"></span>", Math.max(0.0001, dbPct))
                + String.format(Locale.ROOT,
                        "<span class=\"split-app\" style=\"flex:%.4f 0 0\"></span>", Math.max(0.0001, nonDbPct))
                + "</div>";
    }

    private static void renderInvocationSwimlanes(PrintStream out, Model m) {
        if (m.invStats.isEmpty()) {
            return;
        }
        long globalFirst = m.firstTs;
        long globalSpan = Math.max(1L, m.lastTs - m.firstTs);

        Map<Long, List<InvStats>> byName = new HashMap<>();
        Map<Long, Long> dbTimeByName = new HashMap<>();
        for (InvStats is : m.invStats.values()) {
            byName.computeIfAbsent(is.nameId, k -> new ArrayList<>()).add(is);
            dbTimeByName.merge(is.nameId, is.totalDurationNanos, Long::sum);
        }
        List<Long> nameIds = new ArrayList<>(byName.keySet());
        nameIds.sort((a, b) -> Long.compare(
                dbTimeByName.getOrDefault(b, 0L),
                dbTimeByName.getOrDefault(a, 0L)));

        int viewW = 1000;
        int labelW = 220;
        int barColW = viewW - labelW;
        int laneH = 14;
        int barH = 10;
        int headerH = 18;
        int viewH = headerH + nameIds.size() * laneH + 4;

        out.println("<p class=\"findings-empty\">"
                + "One bar per invocation of <code>Profiler.currentOperation(name)</code>. "
                + "Position is wall-clock start; width is that invocation's duration. "
                + "Hover a bar for details.</p>");
        out.println("<svg class=\"swimlanes\" viewBox=\"0 0 " + viewW + " " + viewH
                + "\" preserveAspectRatio=\"none\" role=\"img\" "
                + "aria-label=\"Operation invocation swim-lanes\" "
                + "style=\"width:100%;height:auto;font-family:var(--mono);\">");
        out.println("  <line x1=\"" + labelW + "\" y1=\"" + headerH
                + "\" x2=\"" + viewW + "\" y2=\"" + headerH
                + "\" stroke=\"#ccc\" stroke-width=\"0.5\"/>");
        out.println("  <text x=\"" + labelW + "\" y=\"" + (headerH - 4)
                + "\" font-size=\"9\" fill=\"#888\">0 ms</text>");
        out.println("  <text x=\"" + viewW + "\" y=\"" + (headerH - 4)
                + "\" font-size=\"9\" fill=\"#888\" text-anchor=\"end\">"
                + htmlEscape(formatDuration(globalSpan)) + "</text>");

        int row = 0;
        for (Long nameId : nameIds) {
            String label = m.ops.getOrDefault(nameId, "op[" + nameId + "]");
            int yCenter = headerH + row * laneH + laneH / 2;
            out.println("  <text x=\"" + (labelW - 8) + "\" y=\"" + (yCenter + 3)
                    + "\" font-size=\"10\" text-anchor=\"end\" fill=\"#333\">"
                    + htmlEscape(label) + "</text>");
            out.println("  <line x1=\"" + labelW + "\" y1=\"" + (yCenter + laneH / 2)
                    + "\" x2=\"" + viewW + "\" y2=\"" + (yCenter + laneH / 2)
                    + "\" stroke=\"#eee\" stroke-width=\"0.3\"/>");
            for (InvStats is : byName.get(nameId)) {
                double xFrac = (double) (is.firstTs - globalFirst) / (double) globalSpan;
                double wFrac = (double) (is.lastTs - is.firstTs) / (double) globalSpan;
                double x = labelW + xFrac * barColW;
                double w = Math.max(1.0, wFrac * barColW);
                String tooltip = label
                        + "  span=" + formatDuration(is.lastTs - is.firstTs)
                        + "  events=" + is.count
                        + "  DB time=" + formatDuration(is.totalDurationNanos);
                out.println(String.format(Locale.ROOT,
                        "  <rect class=\"inv-bar\" x=\"%.2f\" y=\"%d\" width=\"%.2f\" height=\"%d\" fill=\"#4a90d9\" fill-opacity=\"0.75\"><title>%s</title></rect>",
                        x, yCenter - barH / 2, w, barH, htmlEscape(tooltip)));
            }
            row++;
        }
        out.println("</svg>");
    }

    private static void renderFlameGraph(PrintStream out, FlameGraph.Node root) {
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
        // (execute-event-key) -> every distinct paramValuesId seen for
        // this key, in insertion order. Usually one entry. More than one
        // means a fingerprint collision or a regression in fingerprint
        // composition: events that the detector counted as "the same
        // query with the same parameters" actually bound different
        // values, and the report must surface that instead of silently
        // showing whichever set arrived first.
        Map<RedundantQueryDetector.Key, Set<Integer>> valuesIdForKey = new HashMap<>();
        // Every event, bucketed by op-id. Memory cost is linear in
        // total events — fine for the recording sizes the Phase-2
        // report is meant for (test-suite scale, not all-day prod).
        // Streaming drill-down is left for later.
        Map<Long, List<Event>> eventsByOp = new HashMap<>();
        // Per-invocation aggregates (one entry per distinct
        // operationInvocationId seen). Drives the swim-lane strip in
        // the Operations section — separate invocations of the same
        // op name share an `operationId` but get distinct entries here.
        Map<Long, InvStats> invStats = new HashMap<>();
        // Environment snapshot from the one-shot REC_RECORDING_META
        // record. Empty strings when the recording predates the record
        // type; SourceRootInference treats them as "no info."
        String userDir = "";
        String javaClassPath = "";
        String javaCommand = "";

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
                public void onRecordingMeta(String userDir, String classpath, String command) {
                    m.userDir = userDir == null ? "" : userDir;
                    m.javaClassPath = classpath == null ? "" : classpath;
                    m.javaCommand = command == null ? "" : command;
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
                        if (e.operationInvocationId >= 0L) {
                            InvStats is = m.invStats.computeIfAbsent(
                                    e.operationInvocationId, k -> new InvStats());
                            if (is.count == 0L) {
                                is.nameId = e.operationId;
                                is.firstTs = e.timestampNanos;
                                is.lastTs = end;
                            } else {
                                if (e.timestampNanos < is.firstTs) {
                                    is.firstTs = e.timestampNanos;
                                }
                                if (end > is.lastTs) {
                                    is.lastTs = end;
                                }
                            }
                            is.count++;
                            is.totalDurationNanos += dur;
                        }
                        if (isExecute(e.eventType) && e.parameterFingerprint != 0L) {
                            RedundantQueryDetector.Key key = new RedundantQueryDetector.Key(
                                    e.operationId, e.sqlId, e.parameterFingerprint, e.stackTraceId);
                            RedundantQueryDetector.Stats rs = m.redundant.computeIfAbsent(
                                    key, k -> new RedundantQueryDetector.Stats());
                            rs.count++;
                            rs.totalDurationNanos += dur;
                            if (e.parameterValuesId >= 0) {
                                m.valuesIdForKey
                                        .computeIfAbsent(key, k -> new LinkedHashSet<>())
                                        .add(e.parameterValuesId);
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

    /** One invocation of {@code Profiler.currentOperation(name)}: event span on
     *  the wall-clock timeline, the name-id it ran under, and a small activity
     *  summary for the swim-lane tooltip. */
    private static final class InvStats {
        long nameId;
        long firstTs;
        long lastTs;
        long count;
        long totalDurationNanos;
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
                details.section {
                  margin: 24px 0 0 0;
                  border-bottom: 1px solid var(--border);
                }
                details.section > summary {
                  font-size: 16px;
                  font-weight: 600;
                  cursor: pointer;
                  padding: 8px 0;
                  list-style: none;
                  display: flex;
                  align-items: center;
                  user-select: none;
                }
                details.section > summary::-webkit-details-marker { display: none; }
                details.section > summary::before {
                  content: "\\25B8";
                  display: inline-block;
                  width: 14px;
                  margin-right: 10px;
                  color: var(--fg-muted);
                  font-size: 12px;
                  text-align: center;
                }
                details.section[open] > summary::before { content: "\\25BE"; }
                details.section > summary:hover { color: var(--accent); }
                details.section > *:not(summary) { margin-bottom: 12px; }
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
                /* Two-segment wall-clock split used in the Operations table
                   and as a drill-down hero bar. flex basis 0 + flex-grow =
                   proportional widths without having to compute percents. */
                .split-bar {
                  display: flex;
                  width: 100%;
                  min-width: 80px;
                  height: 10px;
                  border-radius: 3px;
                  overflow: hidden;
                  background: var(--border);
                }
                .split-db { background: #4a90d9; }
                .split-app { background: #d0d7de; }
                td.split-cell { width: 120px; }
                .split-hero {
                  display: flex;
                  width: 100%;
                  height: 20px;
                  border-radius: 4px;
                  overflow: hidden;
                  background: var(--border);
                  margin: 8px 0 6px 0;
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
                  padding: 0 6px;
                }
                .split-hero .split-db { background: #4a90d9; }
                .split-hero .split-app { background: #8a94a0; }
                /* Idle-lock finding: mini-Gantt of a single transaction. */
                .tx-bar {
                  display: flex;
                  width: 100%;
                  height: 22px;
                  border-radius: 4px;
                  overflow: hidden;
                  background: var(--border);
                  margin: 10px 0 4px 0;
                }
                .tx-bar > .tx-seg { display: block; min-width: 1px; }
                .tx-seg.tx-execute-query  { background: #4a90d9; }
                .tx-seg.tx-execute-update { background: #2e7d32; }
                .tx-seg.tx-execute-batch  { background: #1b5e20; }
                .tx-seg.tx-prepare        { background: #bdbdbd; }
                .tx-seg.tx-commit         { background: #424242; }
                .tx-seg.tx-rollback       { background: #b71c1c; }
                .tx-seg.tx-idle           { background: #ffcc80; }
                .tx-seg.tx-max-gap        { background: #e53935; }
                .tx-seg.tx-other          { background: #9e9e9e; }
                .tx-legend {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 12px;
                  margin-bottom: 10px;
                  font-size: 11px;
                  color: var(--fg-muted);
                }
                .tx-legend-item { display: inline-flex; align-items: center; gap: 4px; }
                .tx-legend-item i {
                  display: inline-block;
                  width: 12px;
                  height: 12px;
                  border-radius: 2px;
                }
                /* Overlap classification pill on write-amplification findings. */
                .wa-tag {
                  display: inline-block;
                  padding: 1px 8px;
                  border-radius: 10px;
                  font-size: 11px;
                  font-weight: 600;
                  text-transform: uppercase;
                  letter-spacing: 0.04em;
                  color: #fff;
                  font-family: var(--mono);
                }
                .wa-tag.wa-mergeable { background: #2e7d32; }
                .wa-tag.wa-overlap   { background: #ef6c00; }
                .wa-tag.wa-redundant { background: #c62828; }
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
        if (findings.isEmpty()) {
            out.println("<p class=\"findings-empty\">No N+1 patterns detected above the default "
                    + "thresholds (count \u2265 10, share \u2265 0.9).</p>");
            return;
        }
        out.println("<p class=\"findings-empty\">A template fired many times from one "
                + "call-site \u2014 usually a loop that runs one query per row instead "
                + "of one batched query. Parameters typically differ per call (one ID "
                + "per row). Cards are ranked by total DB time; count is across the "
                + "whole recording. See <em>Redundant queries</em> below for the "
                + "identical-parameters variant.</p>");
        out.println("<div class=\"findings\">");
        for (N1Finding f : findings) {
            out.println("  <div class=\"finding\">");
            out.println("    <div class=\"finding-head\">"
                    + "<span class=\"finding-count\">" + f.count() + "\u00D7</span> "
                    + "<span class=\"finding-time\">" + htmlEscape(formatDuration(f.totalDurationNanos())) + " total DB time</span>"
                    + "</div>");
            out.println("    <dl class=\"finding-kv\">");
            out.println("      <dt>template</dt><dd>" + tdContent(sqlLabel(f.sql(), f.sqlId())) + "</dd>");
            out.println("      <dt>query fires here</dt><dd>"
                    + htmlEscape(formatFrame(f.representativeSite())) + "</dd>");
            String ancestorCell;
            if (f.ancestor() != null && !f.ancestor().equals(f.representativeSite())) {
                ancestorCell = htmlEscape(formatFrame(f.ancestor()));
            } else {
                ancestorCell = "<span class=\"muted\">(same as query site \u2014 "
                        + "no outer application frame above it)</span>";
            }
            out.println("      <dt>outer loop (probable fix)</dt><dd>"
                    + ancestorCell + "</dd>");
            out.println("      <dt>suggestion</dt><dd class=\"muted\">"
                    + "Replace the inner query with a batched/join query that returns "
                    + "all rows in one round-trip, or fetch the full collection once "
                    + "and pass it through.</dd>");
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
