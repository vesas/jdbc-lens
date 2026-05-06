package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Detects queries that are executed more than once with identical
 * parameters inside the same logical operation. The caller builds a
 * per-{@link Key} count/duration map from execute events; the detector
 * filters for repeats and ranks by total duration.
 *
 * <p>Only events with a non-zero {@code parameterFingerprint} and a
 * set {@code operationId} are considered. Events without bound
 * parameters (ad-hoc {@code Statement} execute, etc.) and events
 * captured outside any op-id boundary are intentionally excluded —
 * "same query, same params" is only interesting as a cache-miss
 * signal within a single logical unit.
 */
public final class RedundantQueryDetector {

    public static final int DEFAULT_MIN_COUNT = 2;

    private final int minCount;

    public RedundantQueryDetector() {
        this(DEFAULT_MIN_COUNT);
    }

    public RedundantQueryDetector(int minCount) {
        this.minCount = minCount;
    }

    public record Key(long opId, int sqlId, long parameterFingerprint, int stackTraceId) {
    }

    public static final class Stats {
        public long count;
        public long totalDurationNanos;
    }

    public List<RedundantFinding> detect(Map<Key, Stats> aggregated,
                                         Map<Integer, String> sqls,
                                         Map<Long, String> opNames,
                                         Map<Integer, StackFrameSnapshot[]> stacks,
                                         long noOperationSentinel) {
        List<RedundantFinding> out = new ArrayList<>();
        for (var entry : aggregated.entrySet()) {
            Key k = entry.getKey();
            Stats s = entry.getValue();
            if (s.count < minCount) {
                continue;
            }
            if (k.opId() == noOperationSentinel) {
                continue;
            }
            if (k.parameterFingerprint() == 0L) {
                continue;
            }
            if (k.sqlId() < 0) {
                continue;
            }
            StackFrameSnapshot[] frames = stacks.get(k.stackTraceId());
            StackFrameSnapshot site = Attribution.callSite(frames);
            out.add(new RedundantFinding(
                    k.opId(),
                    opNames.get(k.opId()),
                    k.sqlId(),
                    sqls.get(k.sqlId()),
                    k.parameterFingerprint(),
                    k.stackTraceId(),
                    site,
                    s.count,
                    s.totalDurationNanos));
        }
        out.sort(Comparator.comparingLong(RedundantFinding::totalDurationNanos).reversed());
        return out;
    }
}
