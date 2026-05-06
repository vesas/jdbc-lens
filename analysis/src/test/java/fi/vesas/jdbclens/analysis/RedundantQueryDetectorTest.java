package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedundantQueryDetectorTest {

    private static final long NO_OP = -1L;

    @Test
    void singleExecuteIsNotFlagged() {
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(1L, 0, 0xAAAAL, 1), 1, 1000L);
        List<RedundantFinding> findings = detect(agg, Map.of(0, "SELECT x"), Map.of(1L, "op"),
                Map.of(1, stackA()));
        assertThat(findings).isEmpty();
    }

    @Test
    void twoIdenticalExecutesInSameOpAreFlagged() {
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(1L, 0, 0xAAAAL, 1), 2, 5000L);
        List<RedundantFinding> findings = detect(agg, Map.of(0, "SELECT x"), Map.of(1L, "my-op"),
                Map.of(1, stackA()));
        assertThat(findings).hasSize(1);
        RedundantFinding f = findings.get(0);
        assertThat(f.count()).isEqualTo(2L);
        assertThat(f.totalDurationNanos()).isEqualTo(5000L);
        assertThat(f.sql()).isEqualTo("SELECT x");
        assertThat(f.opName()).isEqualTo("my-op");
    }

    @Test
    void differentFingerprintsAreSeparateKeysBothBelowThreshold() {
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(1L, 0, 0xAAAAL, 1), 1, 1000L);
        put(agg, key(1L, 0, 0xBBBBL, 1), 1, 1000L);
        List<RedundantFinding> findings = detect(agg, Map.of(0, "SELECT x"), Map.of(1L, "op"),
                Map.of(1, stackA()));
        assertThat(findings).isEmpty();
    }

    @Test
    void executesOutsideAnyOpAreIgnored() {
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(NO_OP, 0, 0xAAAAL, 1), 5, 5000L);
        List<RedundantFinding> findings = detect(agg, Map.of(0, "SELECT x"), Map.of(),
                Map.of(1, stackA()));
        assertThat(findings).isEmpty();
    }

    @Test
    void zeroFingerprintIsIgnored() {
        // Execute events with no bound parameters have fingerprint 0.
        // They should not be surfaced as redundant even if the same
        // (op, sql, stack) happens repeatedly.
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(1L, 0, 0L, 1), 10, 10_000L);
        List<RedundantFinding> findings = detect(agg, Map.of(0, "SELECT x"), Map.of(1L, "op"),
                Map.of(1, stackA()));
        assertThat(findings).isEmpty();
    }

    @Test
    void findingsAreRankedByTotalDuration() {
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(1L, 0, 0xAAAAL, 1), 3, 300L);
        put(agg, key(2L, 1, 0xBBBBL, 2), 2, 10_000L);

        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(0, "SELECT cheap");
        sqls.put(1, "SELECT expensive");
        Map<Long, String> ops = new HashMap<>();
        ops.put(1L, "op-a");
        ops.put(2L, "op-b");
        Map<Integer, StackFrameSnapshot[]> stacks = new HashMap<>();
        stacks.put(1, stackA());
        stacks.put(2, stackA());

        List<RedundantFinding> findings = detect(agg, sqls, ops, stacks);
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).sql()).isEqualTo("SELECT expensive");
        assertThat(findings.get(1).sql()).isEqualTo("SELECT cheap");
    }

    @Test
    void customMinCountApplies() {
        // With min-count = 5, two repeats should not flag.
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(1L, 0, 0xAAAAL, 1), 2, 2000L);

        List<RedundantFinding> findings = new RedundantQueryDetector(5)
                .detect(agg, Map.of(0, "SELECT x"), Map.of(1L, "op"),
                        Map.of(1, stackA()), NO_OP);
        assertThat(findings).isEmpty();
    }

    // --- helpers ---

    private static List<RedundantFinding> detect(
            Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg,
            Map<Integer, String> sqls,
            Map<Long, String> opNames,
            Map<Integer, StackFrameSnapshot[]> stacks) {
        return new RedundantQueryDetector().detect(agg, sqls, opNames, stacks, NO_OP);
    }

    private static RedundantQueryDetector.Key key(long op, int sql, long fp, int stack) {
        return new RedundantQueryDetector.Key(op, sql, fp, stack);
    }

    private static void put(Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> m,
                            RedundantQueryDetector.Key k, long count, long duration) {
        RedundantQueryDetector.Stats s = new RedundantQueryDetector.Stats();
        s.count = count;
        s.totalDurationNanos = duration;
        m.put(k, s);
    }

    @Test
    void samePatternInTwoOpsProducesTwoSeparateFindings() {
        // The same (sql, fingerprint, stack) appearing 3 times in op1 AND 3 times
        // in op2 must produce two independent findings — one per op — each with
        // count=3. A regression that dropped opId from the Key would merge them
        // into one finding with count=6.
        Map<RedundantQueryDetector.Key, RedundantQueryDetector.Stats> agg = new HashMap<>();
        put(agg, key(1L, 0, 0xAAAAL, 1), 3, 3_000L);
        put(agg, key(2L, 0, 0xAAAAL, 1), 3, 6_000L);

        Map<Integer, String> sqls = Map.of(0, "SELECT x");
        Map<Long, String> ops = new HashMap<>();
        ops.put(1L, "op-one");
        ops.put(2L, "op-two");
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1, stackA());

        List<RedundantFinding> findings = new RedundantQueryDetector()
                .detect(agg, sqls, ops, stacks, NO_OP);

        assertThat(findings).hasSize(2);
        // Ranked by total duration desc: op-two (6000) before op-one (3000).
        assertThat(findings.get(0).opName()).isEqualTo("op-two");
        assertThat(findings.get(0).count()).isEqualTo(3L);
        assertThat(findings.get(1).opName()).isEqualTo("op-one");
        assertThat(findings.get(1).count()).isEqualTo(3L);
    }

    private static StackFrameSnapshot[] stackA() {
        return new StackFrameSnapshot[] {
                new StackFrameSnapshot("com.example.Dao", "findById", 47),
                new StackFrameSnapshot("com.example.Service", "load", 12)
        };
    }
}
