package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.analysis.source.CallSiteEnricher;
import fi.vesas.jdbclens.analysis.source.SourceRootInference;
import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a machine-readable JSON report from a {@code .jdbclog}
 * recording. Designed for LLM-agent consumption: every finding
 * carries its call-site in structured form so the agent can locate
 * source files without parsing HTML or free text.
 *
 * <p>Output shape:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "recordingFile": "...",
 *   "recordedAt": { "userDir":"...", "javaCommand":"...", "classPath":"..." },
 *   "summary": { ... },
 *   "findings": [ ... ],       // HIGH first, then by totalDurationNanos desc
 *   "topCallSites": [ ... ],
 *   "topTemplates": [ ... ]
 * }
 * }</pre>
 */
public final class JsonReport {

    private JsonReport() {}

    public static void write(Path input, PrintStream out) throws IOException {
        write(input, out, List.of(), false);
    }

    public static void write(Path input, PrintStream out,
                              List<Path> sourceRootOverrides,
                              boolean noSourceScan) throws IOException {
        AnalysisModel m = AnalysisModel.load(input);
        CallSiteEnricher enricher = null;
        if (!noSourceScan) {
            List<Path> roots = sourceRootOverrides.isEmpty()
                    ? SourceRootInference.infer(m.userDir, m.javaClassPath)
                    : sourceRootOverrides;
            if (!roots.isEmpty()) {
                enricher = new CallSiteEnricher(roots);
            }
        }
        render(m, input, out, enricher);
    }

    static void render(AnalysisModel m, Path inputPath, PrintStream out) {
        render(m, inputPath, out, null);
    }

    static void render(AnalysisModel m, Path inputPath, PrintStream out, CallSiteEnricher enricher) {
        // Run all detectors (same set as HtmlReport)
        List<N1Finding> n1 = new N1Detector().detect(m.executeAgg, m.sqls, m.stacks);
        List<RepeatedPrepareFinding> preparedInLoop = new RepeatedPrepareDetector()
                .detect(m.eventsByOp, m.sqls, m.ops, m.stacks);
        List<EmulatedCursorFinding> emulatedCursors = new EmulatedCursorDetector()
                .detect(m.executeAgg, m.sqls, m.stacks);
        List<RedundantFinding> redundant = new RedundantQueryDetector()
                .detect(m.redundant, m.sqls, m.ops, m.stacks, AnalysisModel.NO_OPERATION);
        EntityAccessAudit.Inputs entityInputs = new EntityAccessAudit.Inputs(
                m.eventsByOp, m.sqls, m.stacks,
                m.paramValuesById, m.ops, AnalysisModel.NO_OPERATION);
        List<EntityFinding> entities = EntityAccessAudit.detect(entityInputs);
        List<ReadThenWriteFinding> readThenWrite = ReadThenWriteDetector.detect(entityInputs);
        List<OverWideUpdateFinding> overWide = new OverWideUpdateDetector()
                .detect(m.sqls, m.eventsByOp, m.stacks);
        List<WriteAmplificationFinding> writeAmp =
                WriteAmplificationDetector.detect(entityInputs);
        List<IdleLockFinding> idleLocks = new IdleLockDetector()
                .detect(m.eventsByOp, m.ops, m.sqls, m.stacks, AnalysisModel.NO_OPERATION);
        Map<Long, List<Transaction>> txByOp = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Event>> e : m.eventsByOp.entrySet()) {
            long opId = e.getKey();
            if (opId == AnalysisModel.NO_OPERATION) continue;
            txByOp.put(opId, Transactions.reconstruct(opId, e.getValue()));
        }
        List<CommitPerRecordFinding> commitPerRecord = new CommitPerRecordDetector()
                .detect(txByOp, m.ops, m.sqls, m.stacks);

        // Merged + sorted findings: HIGH first, then MEDIUM, then LOW;
        // within each tier by totalDurationNanos descending.
        List<RankedFinding> ranked = new ArrayList<>();
        n1.forEach(f            -> ranked.add(new RankedFinding(
                FindingSerializer.n1Severity(f.count()),           f.totalDurationNanos(), f)));
        preparedInLoop.forEach(f -> ranked.add(new RankedFinding(
                FindingSerializer.preparedInLoopSeverity(f.prepareCount()), f.totalPrepareNanos(), f)));
        idleLocks.forEach(f     -> ranked.add(new RankedFinding(
                FindingSerializer.idleLockSeverity(f.maxIdleGapNanos()), f.txDurationNanos(), f)));
        emulatedCursors.forEach(f -> ranked.add(new RankedFinding("LOW", f.totalDurationNanos(), f)));
        redundant.forEach(f     -> ranked.add(new RankedFinding("LOW", f.totalDurationNanos(), f)));
        entities.forEach(f      -> ranked.add(new RankedFinding("LOW", f.totalDurationNanos(), f)));
        readThenWrite.forEach(f -> ranked.add(new RankedFinding("LOW", 0L, f)));
        overWide.forEach(f      -> ranked.add(new RankedFinding("LOW", f.totalDurationNanos(), f)));
        writeAmp.forEach(f      -> ranked.add(new RankedFinding("LOW", f.totalDurationNanos(), f)));
        commitPerRecord.forEach(f -> ranked.add(new RankedFinding("LOW", f.totalDbTimeNanos(), f)));
        ranked.sort(Comparator
                .comparingInt(RankedFinding::severityRank)
                .thenComparingLong((RankedFinding r) -> -r.totalDurationNanos));

        FindingSerializer ser = new FindingSerializer(enricher);
        JsonWriter jw = new JsonWriter(out);
        jw.beginObject();
        jw.field("schemaVersion", 1L);
        jw.field("recordingFile", inputPath == null ? null : inputPath.toString());

        jw.beginObject("recordedAt");
        jw.field("userDir",     m.userDir.isEmpty()       ? null : m.userDir);
        jw.field("javaCommand", m.javaCommand.isEmpty()   ? null : m.javaCommand);
        jw.field("classPath",   m.javaClassPath.isEmpty() ? null : m.javaClassPath);
        jw.endObject();

        // summary
        jw.beginObject("summary");
        jw.field("totalEvents", m.eventCount);
        jw.field("totalDurationNanos", m.totalDurationNanos);
        jw.field("wallClockNanos", m.wallNanos());
        jw.field("distinctSqlTemplates", m.sqls.size());
        jw.field("distinctCallSites", m.stacks.size());
        jw.beginObject("findingCounts");
        jw.field("n1",              n1.size());
        jw.field("preparedInLoop",  preparedInLoop.size());
        jw.field("emulatedCursor",  emulatedCursors.size());
        jw.field("redundant",       redundant.size());
        jw.field("entityAccess",    entities.size());
        jw.field("readThenWrite",   readThenWrite.size());
        jw.field("overWideUpdate",  overWide.size());
        jw.field("writeAmp",        writeAmp.size());
        jw.field("idleLock",        idleLocks.size());
        jw.field("commitPerRecord", commitPerRecord.size());
        jw.endObject();
        jw.endObject();

        // findings array
        jw.beginArray("findings");
        for (RankedFinding r : ranked) {
            Object f = r.finding;
            if (f instanceof N1Finding x)                      ser.n1(jw, x);
            else if (f instanceof RepeatedPrepareFinding x)    ser.preparedInLoop(jw, x);
            else if (f instanceof EmulatedCursorFinding x)     ser.emulatedCursor(jw, x);
            else if (f instanceof RedundantFinding x)          ser.redundant(jw, x);
            else if (f instanceof EntityFinding x)             ser.entityAccess(jw, x);
            else if (f instanceof ReadThenWriteFinding x)      ser.readThenWrite(jw, x);
            else if (f instanceof OverWideUpdateFinding x)     ser.overWideUpdate(jw, x);
            else if (f instanceof WriteAmplificationFinding x) ser.writeAmplification(jw, x);
            else if (f instanceof IdleLockFinding x)           ser.idleLock(jw, x);
            else if (f instanceof CommitPerRecordFinding x)    ser.commitPerRecord(jw, x);
        }
        jw.endArray();

        // top call-sites
        jw.beginArray("topCallSites");
        for (Map.Entry<Integer, Aggregator.Stats> e : m.executeAgg.topCallSites(20)) {
            StackFrameSnapshot[] frames = m.stacks.get(e.getKey());
            if (frames == null) continue;
            StackFrameSnapshot site = Attribution.callSite(frames);
            if (site == null) continue;
            jw.beginObject();
            ser.callSite(jw, "callSite", site);
            jw.field("count", e.getValue().count());
            jw.field("totalDurationNanos", e.getValue().totalDurationNanos());
            jw.endObject();
        }
        jw.endArray();

        // top SQL templates
        jw.beginArray("topTemplates");
        for (Map.Entry<Integer, Aggregator.Stats> e : m.executeAgg.topTemplates(20)) {
            int sqlId = e.getKey();
            String sql = m.sqls.get(sqlId);
            if (sql == null) continue;
            jw.beginObject();
            jw.field("sqlId", sqlId);
            jw.field("sql", sql);
            jw.field("count", e.getValue().count());
            jw.field("totalDurationNanos", e.getValue().totalDurationNanos());
            jw.endObject();
        }
        jw.endArray();

        jw.endObject();
        jw.newline();
    }

    private record RankedFinding(String severity, long totalDurationNanos, Object finding) {
        int severityRank() {
            return switch (severity) {
                case "HIGH"   -> 0;
                case "MEDIUM" -> 1;
                default       -> 2;
            };
        }
    }
}
