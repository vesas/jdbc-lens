package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.ProfilerConfig;
import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects {@code PreparedStatement} objects created inside a loop.
 *
 * <p>Signal: the same {@code (sqlId, stackTraceId)} pair appears in
 * {@code minCount} or more PREPARE events within one logical operation.
 * Every repetition beyond the first is pure overhead — the database
 * parses and plans the same query again, and many drivers allocate a
 * server-side cursor handle per prepare that can exhaust per-connection
 * limits under load.
 *
 * <p>Fix is always cheap: hoist the {@code prepareStatement()} call
 * above the loop and reuse the {@link java.sql.PreparedStatement}.
 */
public final class RepeatedPrepareDetector {

    private final int minCount;

    public RepeatedPrepareDetector() {
        this(ProfilerConfig.defaultN1MinCount());
    }

    public RepeatedPrepareDetector(int minCount) {
        this.minCount = minCount;
    }

    public List<RepeatedPrepareFinding> detect(
            Map<Long, List<Event>> eventsByOp,
            Map<Integer, String> sqls,
            Map<Long, String> ops,
            Map<Integer, StackFrameSnapshot[]> stacks) {

        record Key(long operationId, int sqlId, int stackTraceId) {}

        Map<Key, long[]> groups = new HashMap<>();
        for (Map.Entry<Long, List<Event>> opEntry : eventsByOp.entrySet()) {
            long opId = opEntry.getKey();
            for (Event e : opEntry.getValue()) {
                if (e.eventType != EventType.PREPARE.code()) continue;
                if (e.sqlId < 0 || e.stackTraceId < 0) continue;
                long[] stats = groups.computeIfAbsent(
                        new Key(opId, e.sqlId, e.stackTraceId), k -> new long[2]);
                stats[0]++;
                stats[1] += Math.max(0L, e.durationNanos);
            }
        }

        List<RepeatedPrepareFinding> findings = new ArrayList<>();
        for (Map.Entry<Key, long[]> entry : groups.entrySet()) {
            long count = entry.getValue()[0];
            if (count < minCount) continue;
            Key k = entry.getKey();
            StackFrameSnapshot[] frames = stacks.get(k.stackTraceId());
            findings.add(new RepeatedPrepareFinding(
                    sqls.get(k.sqlId()),
                    (int) count,
                    entry.getValue()[1],
                    Attribution.callSite(frames),
                    ops.get(k.operationId())));
        }

        findings.sort(Comparator.comparingInt(RepeatedPrepareFinding::prepareCount).reversed());
        return findings;
    }
}
