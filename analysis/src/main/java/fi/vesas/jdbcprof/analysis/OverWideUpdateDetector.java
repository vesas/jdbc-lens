package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags UPDATE templates whose SET clause touches more than
 * {@link #DEFAULT_THRESHOLD} columns. In legacy codebases this is
 * the {@code REWRITE RECORD} anti-pattern: the app always overwrites
 * the entire row, so every UPDATE fires triggers, CDC, and
 * replication for columns that almost never actually changed.
 *
 * <p>The detector only reads templates and their aggregated execute
 * counts — no per-event scan required.
 */
public final class OverWideUpdateDetector {

    public static final int DEFAULT_THRESHOLD = 6;

    private final int threshold;

    public OverWideUpdateDetector() {
        this(DEFAULT_THRESHOLD);
    }

    public OverWideUpdateDetector(int threshold) {
        this.threshold = threshold;
    }

    public List<OverWideUpdateFinding> detect(
            Map<Integer, String> sqls,
            Map<Long, List<Event>> eventsByOp,
            Map<Integer, StackFrameSnapshot[]> stacks) {

        // (sqlId) -> (stackId -> count). Picks the dominant stack for
        // the representative call-site.
        Map<Integer, Map<Integer, Long>> stackCountsBySql = new HashMap<>();
        Map<Integer, long[]> execCountAndDur = new HashMap<>();

        for (List<Event> events : eventsByOp.values()) {
            for (Event e : events) {
                if (e.eventType != EventType.EXECUTE_UPDATE.code()) {
                    continue;
                }
                if (e.sqlId < 0) {
                    continue;
                }
                long[] acc = execCountAndDur.computeIfAbsent(e.sqlId, k -> new long[2]);
                acc[0]++;
                acc[1] += Math.max(0L, e.durationNanos);
                stackCountsBySql
                        .computeIfAbsent(e.sqlId, k -> new HashMap<>())
                        .merge(e.stackTraceId, 1L, Long::sum);
            }
        }

        List<OverWideUpdateFinding> findings = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : sqls.entrySet()) {
            int sqlId = entry.getKey();
            String sql = entry.getValue();
            TemplateShape shape = TemplateShape.of(sql);
            if (shape == null || shape.kind() != TemplateShape.Kind.UPDATE) {
                continue;
            }
            if (shape.setColumns().size() < threshold) {
                continue;
            }
            long[] acc = execCountAndDur.getOrDefault(sqlId, new long[]{0L, 0L});
            int dominantStackId = dominantStack(stackCountsBySql.get(sqlId));
            StackFrameSnapshot site = dominantStackId < 0 ? null
                    : Attribution.callSite(stacks.get(dominantStackId));
            findings.add(new OverWideUpdateFinding(
                    sqlId,
                    sql,
                    shape.table(),
                    shape.setColumns(),
                    site,
                    acc[0],
                    acc[1]));
        }

        findings.sort(Comparator
                .comparingInt(OverWideUpdateFinding::setColumnCount).reversed()
                .thenComparing(Comparator.comparingLong(
                        OverWideUpdateFinding::totalDurationNanos).reversed()));
        return findings;
    }

    private static int dominantStack(Map<Integer, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return -1;
        }
        int best = -1;
        long bestCount = -1L;
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }
}
