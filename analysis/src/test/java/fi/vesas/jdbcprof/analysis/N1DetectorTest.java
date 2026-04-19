package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class N1DetectorTest {

    // Default thresholds: count >= 10, dominant share >= 0.9.

    @Test
    void belowMinCountProducesNoFinding() {
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 9; i++) {
            agg.add(exec(1, 2, 1_000));
        }
        List<N1Finding> findings = new N1Detector().detect(agg, sqls(2, "SELECT"), stacks(1));
        assertThat(findings).isEmpty();
    }

    @Test
    void splitCallSitesBelowFractionProducesNoFinding() {
        // 10 events total, 5 from stack 1 and 5 from stack 2 — dominant
        // share is 0.5, well below the 0.9 threshold.
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 5; i++) agg.add(exec(1, 2, 1_000));
        for (int i = 0; i < 5; i++) agg.add(exec(2, 2, 1_000));
        Map<Integer, StackFrameSnapshot[]> stacks = new HashMap<>();
        stacks.put(1, new StackFrameSnapshot[] { frame("com.example.A", "a", 1) });
        stacks.put(2, new StackFrameSnapshot[] { frame("com.example.B", "b", 2) });
        List<N1Finding> findings = new N1Detector().detect(agg, sqls(2, "SELECT"), stacks);
        assertThat(findings).isEmpty();
    }

    @Test
    void dominantCallSiteAboveFractionFlagged() {
        Aggregator agg = new Aggregator();
        // 10 events, 10 from stack 1 — share = 1.0, above 0.9.
        for (int i = 0; i < 10; i++) agg.add(exec(1, 2, 1_000));
        StackFrameSnapshot site = frame("com.example.Dao", "findOne", 47);
        StackFrameSnapshot loop = frame("com.example.Service", "fetchAll", 12);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1, new StackFrameSnapshot[] { site, loop });

        List<N1Finding> findings = new N1Detector().detect(agg, sqls(2, "SELECT name FROM t"), stacks);

        assertThat(findings).hasSize(1);
        N1Finding f = findings.get(0);
        assertThat(f.sqlId()).isEqualTo(2);
        assertThat(f.count()).isEqualTo(10L);
        assertThat(f.representativeSite()).isEqualTo(site);
        assertThat(f.ancestor()).isEqualTo(loop);
    }

    @Test
    void multipleFindingsRankedByDuration() {
        Aggregator agg = new Aggregator();
        // Template 1: 50 events × 100ns = 5 us total.
        for (int i = 0; i < 50; i++) agg.add(exec(1, 1, 100));
        // Template 2: 12 events × 5 ms = 60 ms total — slower total, smaller count.
        for (int i = 0; i < 12; i++) agg.add(exec(2, 2, 5_000_000));

        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, new StackFrameSnapshot[] { frame("com.example.A", "a", 1) },
                2, new StackFrameSnapshot[] { frame("com.example.B", "b", 2) });

        List<N1Finding> findings = new N1Detector().detect(agg,
                sqls(1, "SELECT small", 2, "SELECT big"), stacks);

        assertThat(findings).hasSize(2);
        // Ranked by total duration, big one first.
        assertThat(findings.get(0).sqlId()).isEqualTo(2);
        assertThat(findings.get(1).sqlId()).isEqualTo(1);
    }

    @Test
    void customThresholdsApply() {
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 3; i++) agg.add(exec(1, 2, 1_000));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, new StackFrameSnapshot[] { frame("com.example.A", "a", 1) });
        // Lower the min-count to 3 — three events through the same call-site qualify.
        List<N1Finding> findings = new N1Detector(3, 0.9)
                .detect(agg, sqls(2, "SELECT"), stacks);
        assertThat(findings).hasSize(1);
    }

    @Test
    void ignoresEventsWithoutSql() {
        Aggregator agg = new Aggregator();
        // 12 COMMIT-ish events (sqlId = -1) — not a query, should not flag.
        for (int i = 0; i < 12; i++) agg.add(exec(1, -1, 1_000));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, new StackFrameSnapshot[] { frame("com.example.Tx", "commit", 8) });
        List<N1Finding> findings = new N1Detector().detect(agg, Map.of(), stacks);
        assertThat(findings).isEmpty();
    }

    // --- helpers ---

    private static Event exec(int stackId, int sqlId, long durationNanos) {
        Event e = new Event();
        e.timestampNanos = 0L;
        e.threadId = 1;
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = durationNanos;
        e.rowsAffected = -1;
        e.batchSize = 0;
        return e;
    }

    private static StackFrameSnapshot frame(String cls, String method, int line) {
        return new StackFrameSnapshot(cls, method, line);
    }

    private static Map<Integer, String> sqls(int id, String sql) {
        Map<Integer, String> m = new HashMap<>();
        m.put(id, sql);
        return m;
    }

    private static Map<Integer, String> sqls(int id1, String s1, int id2, String s2) {
        Map<Integer, String> m = new HashMap<>();
        m.put(id1, s1);
        m.put(id2, s2);
        return m;
    }

    private static Map<Integer, StackFrameSnapshot[]> stacks(int stackId) {
        Map<Integer, StackFrameSnapshot[]> m = new HashMap<>();
        m.put(stackId, new StackFrameSnapshot[] { frame("com.example.Dao", "find", 1) });
        return m;
    }
}
