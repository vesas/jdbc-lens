package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.ProfilerConfig;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * N+1 detection over already-aggregated recording data (spec §8.3).
 *
 * <p>Rule: a SQL template executed at least {@code minCount} times
 * where at least {@code minFraction} of those executions originate
 * from a single dominant {@code (stack, template)} pair is flagged
 * as an N+1 candidate. The dominant stack's attributed call-site
 * answers "where does the query happen"; the frame immediately above
 * it in the same stack answers "who's running the loop."
 *
 * <p>Findings are ranked by total duration — per spec §8.3, a 1000-
 * query N+1 taking 2 ms matters less than a 50-query one taking 12 s.
 */
public final class N1Detector {

    private final int minCount;
    private final double minFraction;

    public N1Detector() {
        this(ProfilerConfig.defaultN1MinCount(),
             ProfilerConfig.defaultN1AncestorFraction());
    }

    public N1Detector(int minCount, double minFraction) {
        this.minCount = minCount;
        this.minFraction = minFraction;
    }

    /**
     * The caller is responsible for feeding {@code agg} only with events
     * that represent query executions (EXECUTE_QUERY / EXECUTE_UPDATE /
     * EXECUTE_BATCH). Including PREPARE, NEXT, or CLOSE would inflate
     * the per-template count and scatter it across many stacks, masking
     * real N+1 patterns.
     */
    public List<N1Finding> detect(Aggregator agg,
                                  Map<Integer, String> sqls,
                                  Map<Integer, StackFrameSnapshot[]> stacks) {
        // One pass over all pairs: for each sqlId, remember the pair with the
        // largest count (ties broken by duration). Tracking both avoids a
        // second nested sweep once we have per-template totals.
        Map<Integer, Map.Entry<Aggregator.PairKey, Aggregator.Stats>> dominant = new HashMap<>();
        for (var entry : agg.topPairs(Integer.MAX_VALUE)) {
            Aggregator.PairKey k = entry.getKey();
            var existing = dominant.get(k.sqlId());
            if (existing == null || entry.getValue().count() > existing.getValue().count()) {
                dominant.put(k.sqlId(), entry);
            }
        }

        List<N1Finding> findings = new ArrayList<>();
        for (var templateEntry : agg.topTemplates(Integer.MAX_VALUE)) {
            int sqlId = templateEntry.getKey();
            long totalCount = templateEntry.getValue().count();
            long totalDuration = templateEntry.getValue().totalDurationNanos();
            if (totalCount < minCount) {
                continue;
            }
            if (sqlId < 0) {
                // Events without a SQL (COMMIT/ROLLBACK/CLOSE) aren't queries.
                continue;
            }
            var dom = dominant.get(sqlId);
            if (dom == null) {
                continue;
            }
            long domCount = dom.getValue().count();
            if ((double) domCount / (double) totalCount < minFraction) {
                continue;
            }
            int stackId = dom.getKey().stackTraceId();
            StackFrameSnapshot[] frames = stacks.get(stackId);
            StackFrameSnapshot site = Attribution.callSite(frames);
            StackFrameSnapshot ancestor = Attribution.ancestorFrame(frames, site);
            findings.add(new N1Finding(
                    sqlId,
                    sqls.get(sqlId),
                    stackId,
                    site,
                    ancestor,
                    totalCount,
                    totalDuration));
        }

        findings.sort(Comparator.comparingLong(N1Finding::totalDurationNanos).reversed());
        return findings;
    }
}
