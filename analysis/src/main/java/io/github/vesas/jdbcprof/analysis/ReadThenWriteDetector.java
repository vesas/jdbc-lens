package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.EventType;
import io.github.vesas.jdbcprof.capture.ParameterValues;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds READ-then-WRITE pairs on the same entity inside one
 * operation: a SELECT on {@code (table, column, value)} followed by
 * an UPDATE or DELETE on the same key. Legacy "fetch a row to look
 * at it, then write it back" pattern — almost always collapsible
 * into a single {@code UPDATE … RETURNING} or an {@code UPDATE}
 * that incorporates the check in its WHERE.
 *
 * <p>Requires {@code captureParameterValues = true} to match entity
 * keys across queries. Falls silently empty otherwise (the report
 * shows a hint).
 */
public final class ReadThenWriteDetector {

    private ReadThenWriteDetector() {
    }

    public static List<ReadThenWriteFinding> detect(EntityAccessAudit.Inputs in) {
        if (in.paramValuesById == null || in.paramValuesById.isEmpty()) {
            return List.of();
        }
        Map<Integer, TemplateShape> shapeCache = new HashMap<>();
        List<ReadThenWriteFinding> findings = new ArrayList<>();

        for (Map.Entry<Long, List<Event>> opEntry : in.eventsByOp.entrySet()) {
            long opId = opEntry.getKey();
            if (opId == in.noOperationSentinel) {
                continue;
            }
            List<Event> events = new ArrayList<>(opEntry.getValue());
            events.sort(Comparator.comparingLong((Event e) -> e.timestampNanos));

            // For each entity key seen in this op, remember the last read
            // event that touched it. When a write on the same key comes
            // along, pair them.
            Map<EntityKey, Event> pendingRead = new HashMap<>();
            for (Event e : events) {
                if (e.sqlId < 0 || e.parameterValuesId < 0) {
                    continue;
                }
                TemplateShape shape = shapeCache.computeIfAbsent(e.sqlId,
                        id -> TemplateShape.of(in.sqls.get(id)));
                if (shape == null || shape.columnsByParamIdx().isEmpty()) {
                    continue;
                }
                ParameterValues pv = in.paramValuesById.get(e.parameterValuesId);
                if (pv == null) {
                    continue;
                }
                List<String> slots = pv.slots();
                // Iterate identifier columns of this template in order.
                for (Map.Entry<Integer, String> colEntry : shape.columnsByParamIdx().entrySet()) {
                    int idx = colEntry.getKey();
                    if (idx < 1 || idx > slots.size()) {
                        continue;
                    }
                    EntityKey key = new EntityKey(shape.table(), colEntry.getValue(), slots.get(idx - 1));
                    if (e.eventType == EventType.EXECUTE_QUERY.code()) {
                        pendingRead.put(key, e);
                    } else if (e.eventType == EventType.EXECUTE_UPDATE.code()) {
                        Event read = pendingRead.remove(key);
                        if (read == null) {
                            continue;
                        }
                        findings.add(build(opId, in, key, read, e));
                    }
                }
            }
        }

        findings.sort(Comparator.comparingLong(
                (ReadThenWriteFinding f) -> f.readDurationNanos() + f.writeDurationNanos()).reversed());
        return findings;
    }

    private static ReadThenWriteFinding build(long opId,
                                              EntityAccessAudit.Inputs in,
                                              EntityKey key,
                                              Event read,
                                              Event write) {
        StackFrameSnapshot readSite = Attribution.callSite(in.stacks.get(read.stackTraceId));
        StackFrameSnapshot writeSite = Attribution.callSite(in.stacks.get(write.stackTraceId));
        long between = Math.max(0L, write.timestampNanos
                - (read.timestampNanos + Math.max(0L, read.durationNanos)));
        return new ReadThenWriteFinding(
                opId,
                in.ops.get(opId),
                key.table,
                key.column,
                key.value,
                read.sqlId,
                in.sqls.get(read.sqlId),
                readSite,
                Math.max(0L, read.durationNanos),
                write.sqlId,
                in.sqls.get(write.sqlId),
                writeSite,
                Math.max(0L, write.durationNanos),
                between);
    }

    private record EntityKey(String table, String column, String value) {
    }
}
