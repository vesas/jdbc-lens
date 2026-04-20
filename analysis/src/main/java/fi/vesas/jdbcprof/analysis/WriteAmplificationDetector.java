package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds UPDATE-then-UPDATE on the same row inside one operation —
 * "write amplification": two (or more) round-trips writing the same
 * entity, usually mergeable into a single UPDATE. Surfaces accidental
 * double-writes (e.g. two services each setting "their" columns on
 * the same row without knowing the other runs) and trigger/CDC
 * volume wins.
 *
 * <p>Requires {@code captureParameterValues = true} — same precondition
 * as {@link ReadThenWriteDetector}, since we identify rows by the
 * bound value in the WHERE clause.
 */
public final class WriteAmplificationDetector {

    private WriteAmplificationDetector() {
    }

    public static List<WriteAmplificationFinding> detect(EntityAccessAudit.Inputs in) {
        if (in.paramValuesById == null || in.paramValuesById.isEmpty()) {
            return List.of();
        }
        Map<Integer, TemplateShape> shapeCache = new HashMap<>();
        List<WriteAmplificationFinding> findings = new ArrayList<>();

        for (Map.Entry<Long, List<Event>> opEntry : in.eventsByOp.entrySet()) {
            long opId = opEntry.getKey();
            if (opId == in.noOperationSentinel) {
                continue;
            }
            List<Event> events = new ArrayList<>(opEntry.getValue());
            events.sort(Comparator.comparingLong((Event e) -> e.timestampNanos));

            // (table, column, value) -> ordered list of hits.
            Map<EntityKey, List<WriteAmplificationFinding.UpdateHit>> perKey =
                    new LinkedHashMap<>();

            for (Event e : events) {
                if (e.eventType != EventType.EXECUTE_UPDATE.code()) {
                    continue;
                }
                if (e.sqlId < 0 || e.parameterValuesId < 0) {
                    continue;
                }
                TemplateShape shape = shapeCache.computeIfAbsent(e.sqlId,
                        id -> TemplateShape.of(in.sqls.get(id)));
                if (shape == null || shape.kind() != TemplateShape.Kind.UPDATE
                        || shape.columnsByParamIdx().isEmpty()) {
                    continue;
                }
                ParameterValues pv = in.paramValuesById.get(e.parameterValuesId);
                if (pv == null) {
                    continue;
                }
                List<String> slots = pv.slots();
                StackFrameSnapshot site = Attribution.callSite(in.stacks.get(e.stackTraceId));
                WriteAmplificationFinding.UpdateHit hit = new WriteAmplificationFinding.UpdateHit(
                        e.sqlId,
                        in.sqls.get(e.sqlId),
                        shape.setColumns(),
                        site,
                        Math.max(0L, e.durationNanos),
                        e.timestampNanos);
                for (Map.Entry<Integer, String> colEntry : shape.columnsByParamIdx().entrySet()) {
                    int idx = colEntry.getKey();
                    if (idx < 1 || idx > slots.size()) {
                        continue;
                    }
                    EntityKey key = new EntityKey(shape.table(), colEntry.getValue(), slots.get(idx - 1));
                    perKey.computeIfAbsent(key, k -> new ArrayList<>()).add(hit);
                }
            }

            for (Map.Entry<EntityKey, List<WriteAmplificationFinding.UpdateHit>> bucket : perKey.entrySet()) {
                List<WriteAmplificationFinding.UpdateHit> hits = bucket.getValue();
                if (hits.size() < 2) {
                    continue;
                }
                EntityKey k = bucket.getKey();
                long total = 0L;
                for (WriteAmplificationFinding.UpdateHit h : hits) {
                    total += h.durationNanos();
                }
                WriteAmplificationFinding.Overlap overlap = classify(hits);
                findings.add(new WriteAmplificationFinding(
                        opId,
                        in.ops.get(opId),
                        k.table, k.column, k.value,
                        hits,
                        total,
                        overlap));
            }
        }

        findings.sort(Comparator
                .comparingLong((WriteAmplificationFinding f) -> -f.totalDurationNanos())
                .thenComparingInt(f -> -f.hits().size()));
        return findings;
    }

    private static WriteAmplificationFinding.Overlap classify(
            List<WriteAmplificationFinding.UpdateHit> hits) {
        // Compare SET-column lists as order-insensitive sets: two
        // UPDATEs that set {status, shipped_at} and {shipped_at, status}
        // are the same amplification pattern even if written in
        // different order.
        List<Set<String>> sets = new ArrayList<>(hits.size());
        for (WriteAmplificationFinding.UpdateHit h : hits) {
            sets.add(new HashSet<>(h.setColumns()));
        }
        Set<String> first = sets.get(0);
        boolean allEqual = true;
        boolean anyIntersect = false;
        for (int i = 0; i < sets.size(); i++) {
            if (!sets.get(i).equals(first)) {
                allEqual = false;
            }
            for (int j = i + 1; j < sets.size(); j++) {
                if (!java.util.Collections.disjoint(sets.get(i), sets.get(j))) {
                    anyIntersect = true;
                }
            }
        }
        if (allEqual && !first.isEmpty()) {
            return WriteAmplificationFinding.Overlap.IDENTICAL;
        }
        if (anyIntersect) {
            return WriteAmplificationFinding.Overlap.OVERLAPPING;
        }
        return WriteAmplificationFinding.Overlap.DISJOINT;
    }

    private record EntityKey(String table, String column, String value) {
    }
}
