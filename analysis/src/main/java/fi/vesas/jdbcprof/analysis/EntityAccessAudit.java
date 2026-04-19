package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the "every service does its own SELECT" smell: inside one
 * operation, the same entity (identified by table + column + value)
 * is fetched through more than one distinct SQL template. Prescribes
 * a structural fix: pull the columns once, pass the row through the
 * call chain.
 *
 * <p>Requires recordings made with
 * {@code captureParameterValues = true} — without it we have the
 * combined fingerprint of a parameter binding but no way to read an
 * individual slot, which is the identifier we need to match entities
 * across different templates.
 */
public final class EntityAccessAudit {

    public static final class Inputs {
        public final Map<Long, List<Event>> eventsByOp;
        public final Map<Integer, String> sqls;
        public final Map<Integer, StackFrameSnapshot[]> stacks;
        public final Map<Integer, ParameterValues> paramValuesById;
        public final Map<Long, String> ops;
        public final long noOperationSentinel;

        public Inputs(Map<Long, List<Event>> eventsByOp,
                      Map<Integer, String> sqls,
                      Map<Integer, StackFrameSnapshot[]> stacks,
                      Map<Integer, ParameterValues> paramValuesById,
                      Map<Long, String> ops,
                      long noOperationSentinel) {
            this.eventsByOp = eventsByOp;
            this.sqls = sqls;
            this.stacks = stacks;
            this.paramValuesById = paramValuesById;
            this.ops = ops;
            this.noOperationSentinel = noOperationSentinel;
        }
    }

    private EntityAccessAudit() {
    }

    public static List<EntityFinding> detect(Inputs in) {
        if (in.paramValuesById == null || in.paramValuesById.isEmpty()) {
            return List.of();
        }

        // Cache TemplateShape per sqlId. We'll see each template many
        // times per op; parsing once per process is plenty.
        Map<Integer, TemplateShape> shapeCache = new HashMap<>();

        List<EntityFinding> findings = new ArrayList<>();

        for (Map.Entry<Long, List<Event>> opEntry : in.eventsByOp.entrySet()) {
            long opId = opEntry.getKey();
            if (opId == in.noOperationSentinel) {
                continue;
            }
            List<Event> events = opEntry.getValue();
            if (events.size() < 2) {
                continue;
            }

            // For this op, accumulate entity keys → templates touching them.
            // Using LinkedHashMap so finding order is deterministic when
            // total duration ties.
            Map<EntityKey, EntityAccum> perEntity = new LinkedHashMap<>();

            for (Event e : events) {
                if (!isQueryEvent(e.eventType)) {
                    continue;
                }
                if (e.sqlId < 0 || e.parameterValuesId < 0) {
                    continue;
                }
                String sql = in.sqls.get(e.sqlId);
                if (sql == null) {
                    continue;
                }
                TemplateShape shape = shapeCache.computeIfAbsent(e.sqlId,
                        id -> TemplateShape.of(sql));
                if (shape == null || shape.columnsByParamIdx().isEmpty()) {
                    continue;
                }
                ParameterValues pv = in.paramValuesById.get(e.parameterValuesId);
                if (pv == null) {
                    continue;
                }
                List<String> slots = pv.slots();
                for (Map.Entry<Integer, String> colEntry : shape.columnsByParamIdx().entrySet()) {
                    int paramIdx = colEntry.getKey();
                    if (paramIdx < 1 || paramIdx > slots.size()) {
                        continue;
                    }
                    String value = slots.get(paramIdx - 1);
                    EntityKey key = new EntityKey(shape.table(), colEntry.getValue(), value);
                    EntityAccum accum = perEntity.computeIfAbsent(key, k -> new EntityAccum());
                    accum.record(e);
                }
            }

            for (Map.Entry<EntityKey, EntityAccum> ek : perEntity.entrySet()) {
                EntityAccum a = ek.getValue();
                if (a.templateTouches.size() < 2) {
                    continue;
                }
                EntityKey key = ek.getKey();
                List<EntityFinding.TemplateHit> hits = a.templateTouches.entrySet().stream()
                        .map(th -> {
                            int sqlId = th.getKey();
                            TemplateTouch t = th.getValue();
                            StackFrameSnapshot callSite = Attribution.callSite(
                                    in.stacks.get(t.representativeStackId));
                            return new EntityFinding.TemplateHit(
                                    sqlId,
                                    in.sqls.get(sqlId),
                                    callSite,
                                    t.count,
                                    t.totalDurationNanos);
                        })
                        .sorted(Comparator.comparingLong(
                                (EntityFinding.TemplateHit h) -> h.totalDurationNanos()).reversed())
                        .toList();
                findings.add(new EntityFinding(
                        opId,
                        in.ops.get(opId),
                        key.table, key.column, key.value,
                        hits,
                        a.totalEvents,
                        a.totalDurationNanos));
            }
        }

        findings.sort(Comparator
                .comparingLong((EntityFinding f) -> -f.totalDurationNanos())
                .thenComparingInt(f -> -f.templates().size()));
        return findings;
    }

    private static boolean isQueryEvent(byte eventTypeCode) {
        byte c = eventTypeCode;
        return c == EventType.EXECUTE_QUERY.code()
                || c == EventType.EXECUTE_UPDATE.code()
                || c == EventType.EXECUTE_BATCH.code();
    }

    private record EntityKey(String table, String column, String value) {
    }

    private static final class TemplateTouch {
        int representativeStackId = -1;
        long count;
        long totalDurationNanos;
    }

    private static final class EntityAccum {
        final Map<Integer, TemplateTouch> templateTouches = new LinkedHashMap<>();
        long totalEvents;
        long totalDurationNanos;

        void record(Event e) {
            TemplateTouch t = templateTouches.computeIfAbsent(e.sqlId, k -> new TemplateTouch());
            t.count++;
            long dur = Math.max(0L, e.durationNanos);
            t.totalDurationNanos += dur;
            if (t.representativeStackId < 0) {
                t.representativeStackId = e.stackTraceId;
            }
            totalEvents++;
            totalDurationNanos += dur;
        }
    }
}
