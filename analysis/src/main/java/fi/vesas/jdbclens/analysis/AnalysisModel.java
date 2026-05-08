package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.ParameterValues;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;
import fi.vesas.jdbclens.storage.BinaryLogReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory model of a single recording, loaded from a {@code .jdbclog}
 * file. Shared by all report renderers (HTML, JSON, text) so the
 * loading logic lives once.
 */
final class AnalysisModel {

    /** Sentinel operationId for events recorded outside any named operation. */
    static final long NO_OPERATION = -1L;

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

    static AnalysisModel load(Path input) throws IOException {
        AnalysisModel m = new AnalysisModel();
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
                    // Events that the capture path emitted with no
                    // stack (NEXT, CLOSE — the analyzer doesn't
                    // attribute against their call-sites) carry
                    // CaptureContext.NO_STACK_TRACE. Keep them out
                    // of the by-stack rollups so the top-callsites
                    // ranking and the cardinality maps don't grow
                    // a synthetic "stack[-1]" bucket.
                    boolean hasStack = e.stackTraceId >= 0;
                    if (hasStack) {
                        m.agg.add(e);
                    }
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
                    if (hasStack) {
                        m.templatesPerStack
                                .computeIfAbsent(e.stackTraceId, k -> new HashSet<>())
                                .add(e.sqlId);
                        m.stacksPerTemplate
                                .computeIfAbsent(e.sqlId, k -> new HashSet<>())
                                .add(e.stackTraceId);
                    }
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

    static boolean isExecute(byte eventTypeCode) {
        byte c = eventTypeCode;
        return c == EventType.EXECUTE_QUERY.code()
                || c == EventType.EXECUTE_UPDATE.code()
                || c == EventType.EXECUTE_BATCH.code();
    }

    static final class OpStats {
        long count;
        long totalDurationNanos;
        Set<Integer> distinctSqls = new HashSet<>();
    }

    /** One invocation of {@code Profiler.currentOperation(name)}: event span on
     *  the wall-clock timeline, the name-id it ran under, and a small activity
     *  summary for the swim-lane tooltip. */
    static final class InvStats {
        long nameId;
        long firstTs;
        long lastTs;
        long count;
        long totalDurationNanos;
    }
}
