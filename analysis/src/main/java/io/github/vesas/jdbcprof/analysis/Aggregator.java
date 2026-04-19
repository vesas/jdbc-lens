package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates events along the three axes the report cares about
 * (spec §8.2): by (call-site, template) pair, by call-site alone, by
 * template alone. Each bucket carries a count and a total duration;
 * queries are ranked by total duration because spec §8.3 makes
 * "total DB time" the important ordering.
 *
 * <p>Pure offline code. The analysis layer has no performance budget
 * worth defending — readability wins over micro-optimisation here.
 */
public final class Aggregator {

    public record PairKey(int stackTraceId, int sqlId) {
    }

    public static final class Stats {
        private long count;
        private long totalDurationNanos;

        public long count() {
            return count;
        }

        public long totalDurationNanos() {
            return totalDurationNanos;
        }

        private void add(long durationNanos) {
            count++;
            totalDurationNanos += durationNanos;
        }
    }

    private final Map<PairKey, Stats> byPair = new HashMap<>();
    private final Map<Integer, Stats> byStack = new HashMap<>();
    private final Map<Integer, Stats> bySql = new HashMap<>();

    public void add(Event e) {
        // Treat negative durations defensively; the capture path
        // computes (t1 - t0) from System.nanoTime so under normal
        // conditions durations are non-negative, but we don't want a
        // single out-of-order clock sample to corrupt the totals.
        long dur = Math.max(0L, e.durationNanos);

        byPair.computeIfAbsent(new PairKey(e.stackTraceId, e.sqlId), k -> new Stats()).add(dur);
        byStack.computeIfAbsent(e.stackTraceId, k -> new Stats()).add(dur);
        bySql.computeIfAbsent(e.sqlId, k -> new Stats()).add(dur);
    }

    public List<Map.Entry<PairKey, Stats>> topPairs(int n) {
        return topEntries(byPair, n);
    }

    public List<Map.Entry<Integer, Stats>> topCallSites(int n) {
        return topEntries(byStack, n);
    }

    public List<Map.Entry<Integer, Stats>> topTemplates(int n) {
        return topEntries(bySql, n);
    }

    private static <K> List<Map.Entry<K, Stats>> topEntries(Map<K, Stats> m, int n) {
        return m.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<K, Stats>>comparingLong(
                                e -> -e.getValue().totalDurationNanos)
                        .thenComparingLong(e -> -e.getValue().count))
                .limit(n)
                .toList();
    }
}
