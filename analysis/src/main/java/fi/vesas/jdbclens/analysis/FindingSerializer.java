package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.analysis.source.CallSiteEnricher;
import fi.vesas.jdbclens.analysis.source.SourceSnippet;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

/**
 * Serializes each finding type as a discriminated JSON object into an
 * already-open {@link JsonWriter} array. Each method calls
 * {@code beginObject()}/{@code endObject()} and emits {@code "type"}
 * and {@code "severity"} before the finding-specific fields.
 *
 * <p>Construct with a non-null {@link CallSiteEnricher} to have call-site
 * objects augmented with a {@code sourceSnippet} block.
 *
 * <p>Severity tiers:
 * <ul>
 *   <li>N+1 — HIGH ≥ 100 executions, MEDIUM ≥ 20</li>
 *   <li>PreparedInLoop — HIGH ≥ 50, MEDIUM ≥ 20</li>
 *   <li>IdleLock — HIGH ≥ 1 s gap, MEDIUM ≥ 100 ms</li>
 *   <li>All others — LOW (informational)</li>
 * </ul>
 * Duration fields are raw nanoseconds throughout.
 */
final class FindingSerializer {

    private final CallSiteEnricher enricher;

    FindingSerializer() {
        this(null);
    }

    FindingSerializer(CallSiteEnricher enricher) {
        this.enricher = enricher;
    }

    void n1(JsonWriter jw, N1Finding f) {
        jw.beginObject();
        jw.field("type", "n1");
        jw.field("severity", n1Severity(f.count()));
        jw.field("sql", f.sql());
        jw.field("sqlId", f.sqlId());
        jw.field("count", f.count());
        jw.field("totalDurationNanos", f.totalDurationNanos());
        callSite(jw, "callSite", f.representativeSite());
        callSite(jw, "ancestor", f.ancestor());
        jw.endObject();
    }

    void preparedInLoop(JsonWriter jw, RepeatedPrepareFinding f) {
        jw.beginObject();
        jw.field("type", "preparedInLoop");
        jw.field("severity", preparedInLoopSeverity(f.prepareCount()));
        jw.field("sql", f.sql());
        jw.field("prepareCount", f.prepareCount());
        jw.field("totalPrepareNanos", f.totalPrepareNanos());
        jw.field("operationName", f.operationName());
        callSite(jw, "callSite", f.callSite());
        jw.endObject();
    }

    void emulatedCursor(JsonWriter jw, EmulatedCursorFinding f) {
        jw.beginObject();
        jw.field("type", "emulatedCursor");
        jw.field("severity", "LOW");
        jw.field("table", f.table());
        jw.field("keyColumn", f.keyColumn());
        jw.field("walkSql", f.walkSql());
        jw.field("walkSqlId", f.walkSqlId());
        jw.field("walkCount", f.walkCount());
        jw.field("fetchSql", f.fetchSql());
        jw.field("fetchSqlId", f.fetchSqlId());
        jw.field("fetchCount", f.fetchCount());
        jw.field("totalDurationNanos", f.totalDurationNanos());
        jw.field("nested", f.nested());
        callSite(jw, "walkCallSite", f.walkSite());
        callSite(jw, "fetchCallSite", f.fetchSite());
        callSite(jw, "ancestor", f.ancestor());
        jw.endObject();
    }

    void redundant(JsonWriter jw, RedundantFinding f) {
        jw.beginObject();
        jw.field("type", "redundant");
        jw.field("severity", "LOW");
        jw.field("sql", f.sql());
        jw.field("sqlId", f.sqlId());
        jw.field("count", f.count());
        jw.field("totalDurationNanos", f.totalDurationNanos());
        jw.field("operationName", f.opName());
        callSite(jw, "callSite", f.callSite());
        jw.endObject();
    }

    void entityAccess(JsonWriter jw, EntityFinding f) {
        jw.beginObject();
        jw.field("type", "entityAccess");
        jw.field("severity", "LOW");
        jw.field("table", f.table());
        jw.field("column", f.column());
        jw.field("value", f.value());
        jw.field("operationName", f.opName());
        jw.field("totalEvents", f.totalEvents());
        jw.field("totalDurationNanos", f.totalDurationNanos());
        jw.beginArray("templates");
        for (EntityFinding.TemplateHit h : f.templates()) {
            jw.beginObject();
            jw.field("sql", h.sql());
            jw.field("sqlId", h.sqlId());
            jw.field("count", h.count());
            jw.field("totalDurationNanos", h.totalDurationNanos());
            callSite(jw, "callSite", h.callSite());
            jw.endObject();
        }
        jw.endArray();
        jw.endObject();
    }

    void readThenWrite(JsonWriter jw, ReadThenWriteFinding f) {
        jw.beginObject();
        jw.field("type", "readThenWrite");
        jw.field("severity", "LOW");
        jw.field("table", f.table());
        jw.field("column", f.column());
        jw.field("value", f.value());
        jw.field("operationName", f.opName());
        jw.field("readSql", f.readSql());
        jw.field("readSqlId", f.readSqlId());
        jw.field("readDurationNanos", f.readDurationNanos());
        jw.field("writeSql", f.writeSql());
        jw.field("writeSqlId", f.writeSqlId());
        jw.field("writeDurationNanos", f.writeDurationNanos());
        jw.field("betweenNanos", f.betweenNanos());
        callSite(jw, "readCallSite", f.readCallSite());
        callSite(jw, "writeCallSite", f.writeCallSite());
        jw.endObject();
    }

    void overWideUpdate(JsonWriter jw, OverWideUpdateFinding f) {
        jw.beginObject();
        jw.field("type", "overWideUpdate");
        jw.field("severity", "LOW");
        jw.field("sql", f.sql());
        jw.field("sqlId", f.sqlId());
        jw.field("table", f.table());
        jw.field("setColumnCount", f.setColumnCount());
        jw.field("executeCount", f.executeCount());
        jw.field("totalDurationNanos", f.totalDurationNanos());
        callSite(jw, "callSite", f.representativeSite());
        jw.endObject();
    }

    void writeAmplification(JsonWriter jw, WriteAmplificationFinding f) {
        jw.beginObject();
        jw.field("type", "writeAmplification");
        jw.field("severity", "LOW");
        jw.field("table", f.table());
        jw.field("column", f.column());
        jw.field("value", f.value());
        jw.field("operationName", f.opName());
        jw.field("overlap", f.overlap().name());
        jw.field("hitCount", f.hits().size());
        jw.field("totalDurationNanos", f.totalDurationNanos());
        jw.endObject();
    }

    void idleLock(JsonWriter jw, IdleLockFinding f) {
        jw.beginObject();
        jw.field("type", "idleLock");
        jw.field("severity", idleLockSeverity(f.maxIdleGapNanos()));
        jw.field("operationName", f.opName());
        jw.field("txDurationNanos", f.txDurationNanos());
        jw.field("maxIdleGapNanos", f.maxIdleGapNanos());
        jw.field("totalIdleNanos", f.totalIdleNanos());
        jw.field("sqlBeforeGap", f.sqlBeforeGap());
        jw.field("sqlAfterGap", f.sqlAfterGap());
        callSite(jw, "siteBeforeGap", f.siteBeforeGap());
        jw.endObject();
    }

    void commitPerRecord(JsonWriter jw, CommitPerRecordFinding f) {
        jw.beginObject();
        jw.field("type", "commitPerRecord");
        jw.field("severity", "LOW");
        jw.field("operationName", f.opName());
        jw.field("runLength", f.runLength());
        jw.field("writesPerTxn", f.writesPerTxn());
        jw.field("readsPerTxn", f.readsPerTxn());
        jw.field("totalWallNanos", f.totalWallNanos());
        jw.field("avgTxnWallNanos", f.avgTxnWallNanos());
        jw.field("totalDbTimeNanos", f.totalDbTimeNanos());
        callSite(jw, "commonAncestor", f.commonAncestor());
        callSite(jw, "callSite", f.representativeCallSite());
        jw.endObject();
    }

    // --- shared helpers ---

    void callSite(JsonWriter jw, String key, StackFrameSnapshot frame) {
        if (frame == null) return;
        jw.beginObject(key);
        jw.field("className",  frame.className());
        jw.field("methodName", frame.methodName());
        jw.field("lineNumber", frame.lineNumber());
        if (enricher != null) {
            SourceSnippet snippet = enricher.enrich(frame);
            if (snippet != null) {
                jw.beginObject("sourceSnippet");
                jw.field("file",      snippet.file().toString());
                jw.field("startLine", snippet.startLine());
                jw.field("endLine",   snippet.endLine());
                jw.field("callLine",  snippet.callLine());
                jw.field("text",      snippet.text());
                jw.endObject();
            }
        }
        jw.endObject();
    }

    // --- severity helpers ---

    static String n1Severity(long count) {
        if (count >= 100) return "HIGH";
        if (count >= 20)  return "MEDIUM";
        return "LOW";
    }

    static String preparedInLoopSeverity(int count) {
        if (count >= 50) return "HIGH";
        if (count >= 20) return "MEDIUM";
        return "LOW";
    }

    static String idleLockSeverity(long maxGapNanos) {
        if (maxGapNanos >= 1_000_000_000L) return "HIGH";
        if (maxGapNanos >= 100_000_000L)   return "MEDIUM";
        return "LOW";
    }
}
