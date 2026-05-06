package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmulatedCursorDetectorTest {

    // Mirrors the shape emitted by a COBOL->Java transpiler: a MIN-walk
    // over the key column and a fetch keyed by it, both in the same
    // outer method. Compare MasterDetailMergeJob in the sample app.
    private static final String CUSTOMER_WALK =
            "SELECT MIN(ID) FROM CUSTOMERS WHERE ID > ?";
    private static final String CUSTOMER_FETCH =
            "SELECT ID, NAME FROM CUSTOMERS WHERE ID = ?";
    private static final String ORDER_WALK =
            "SELECT MIN(ID) FROM ORDERS WHERE CUSTOMER_ID = ? AND ID > ?";
    private static final String ORDER_FETCH =
            "SELECT ID, AMOUNT, STATUS FROM ORDERS WHERE ID = ?";

    @Test
    void singleWalkFetchPairFlaggedWhenCoLocatedAndCoExecuted() {
        Aggregator agg = new Aggregator();
        // 10 walks, 10 fetches, same outer method.
        for (int i = 0; i < 10; i++) agg.add(exec(/*stack*/1, /*sql*/1, 100L));
        for (int i = 0; i < 10; i++) agg.add(exec(/*stack*/2, /*sql*/2, 200L));

        Map<Integer, String> sqls = Map.of(1, CUSTOMER_WALK, 2, CUSTOMER_FETCH);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.Job", "readNextMaster",
                        "com.example.Job", "run"),
                2, stack("com.example.Job", "readMasterFields",
                        "com.example.Job", "run"));

        List<EmulatedCursorFinding> findings = new EmulatedCursorDetector()
                .detect(agg, sqls, stacks);

        assertThat(findings).hasSize(1);
        EmulatedCursorFinding f = findings.get(0);
        assertThat(f.walkSqlId()).isEqualTo(1);
        assertThat(f.fetchSqlId()).isEqualTo(2);
        assertThat(f.table()).isEqualTo("customers");
        assertThat(f.keyColumn()).isEqualTo("id");
        assertThat(f.walkCount()).isEqualTo(10L);
        assertThat(f.fetchCount()).isEqualTo(10L);
        assertThat(f.totalDurationNanos()).isEqualTo(10L * 100L + 10L * 200L);
        assertThat(f.ancestor().methodName()).isEqualTo("run");
        assertThat(f.nested()).isFalse();
        assertThat(f.outerAncestor()).isNull();
    }

    @Test
    void nestedMasterDetailLoopsFlagsTheInnerPairAsNested() {
        Aggregator agg = new Aggregator();
        // Outer master loop: 5 walks, 5 fetches under run().
        for (int i = 0; i < 5; i++) agg.add(exec(1, 1, 100L));
        for (int i = 0; i < 5; i++) agg.add(exec(2, 2, 100L));
        // Inner detail loop: 20 walks and 20 fetches under
        // processDetailRecords(), itself called from run().
        for (int i = 0; i < 20; i++) agg.add(exec(3, 3, 100L));
        for (int i = 0; i < 20; i++) agg.add(exec(4, 4, 100L));

        Map<Integer, String> sqls = Map.of(
                1, CUSTOMER_WALK, 2, CUSTOMER_FETCH,
                3, ORDER_WALK, 4, ORDER_FETCH);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.Job", "readNextMaster",
                        "com.example.Job", "run"),
                2, stack("com.example.Job", "readMasterFields",
                        "com.example.Job", "run"),
                3, stack("com.example.Job", "readNextDetail",
                        "com.example.Job", "processDetailRecords",
                        "com.example.Job", "run"),
                4, stack("com.example.Job", "readDetailFields",
                        "com.example.Job", "processDetailRecords",
                        "com.example.Job", "run"));

        List<EmulatedCursorFinding> findings = new EmulatedCursorDetector()
                .detect(agg, sqls, stacks);

        assertThat(findings).hasSize(2);

        EmulatedCursorFinding inner = findings.stream()
                .filter(x -> "orders".equals(x.table()))
                .findFirst().orElseThrow();
        assertThat(inner.nested()).isTrue();
        assertThat(inner.outerAncestor().methodName()).isEqualTo("run");
        assertThat(inner.ancestor().methodName()).isEqualTo("processDetailRecords");

        EmulatedCursorFinding outer = findings.stream()
                .filter(x -> "customers".equals(x.table()))
                .findFirst().orElseThrow();
        assertThat(outer.nested()).isFalse();
        assertThat(outer.outerAncestor()).isNull();
    }

    @Test
    void pureN1WithoutWalkShapeIsNotFlagged() {
        // Generic ORM N+1: a by-id fetch repeated in a loop, no MIN/MAX
        // walk. N1Detector would flag this; EmulatedCursorDetector must
        // stay out of its way — the fix is different (batch / JOIN).
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 10; i++) agg.add(exec(1, 1, 100L));

        Map<Integer, String> sqls = Map.of(
                1, "SELECT NAME FROM CUSTOMERS WHERE ID = ?");
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.Dao", "findOne",
                        "com.example.Service", "loadAll"));

        List<EmulatedCursorFinding> findings = new EmulatedCursorDetector()
                .detect(agg, sqls, stacks);

        assertThat(findings).isEmpty();
    }

    @Test
    void walkWithoutPairedFetchIsNotFlagged() {
        // A MIN-walk by itself isn't the pathology — a report that
        // only walks keys without also fetching rows might be a
        // pagination oddity, not the READ NEXT transpile signature.
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 10; i++) agg.add(exec(1, 1, 100L));

        Map<Integer, String> sqls = Map.of(1, CUSTOMER_WALK);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.Dao", "nextId",
                        "com.example.Service", "run"));

        List<EmulatedCursorFinding> findings = new EmulatedCursorDetector()
                .detect(agg, sqls, stacks);

        assertThat(findings).isEmpty();
    }

    @Test
    void walkAndFetchUnderDifferentOuterMethodsDoNotPair() {
        // Walk lives under run(), fetch lives under a different outer
        // method — they're not iterated together, so they don't form
        // an emulated cursor even though their shapes match.
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 10; i++) agg.add(exec(1, 1, 100L));
        for (int i = 0; i < 10; i++) agg.add(exec(2, 2, 100L));

        Map<Integer, String> sqls = Map.of(1, CUSTOMER_WALK, 2, CUSTOMER_FETCH);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.JobA", "readNextMaster",
                        "com.example.JobA", "runA"),
                2, stack("com.example.JobB", "readMasterFields",
                        "com.example.JobB", "runB"));

        assertThat(new EmulatedCursorDetector().detect(agg, sqls, stacks)).isEmpty();
    }

    @Test
    void reverseWalkWithMaxAndLessThanAlsoRecognised() {
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 10; i++) agg.add(exec(1, 1, 100L));
        for (int i = 0; i < 10; i++) agg.add(exec(2, 2, 100L));

        Map<Integer, String> sqls = Map.of(
                1, "SELECT MAX(ID) FROM CUSTOMERS WHERE ID < ?",
                2, CUSTOMER_FETCH);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.Job", "readPrevMaster",
                        "com.example.Job", "run"),
                2, stack("com.example.Job", "readMasterFields",
                        "com.example.Job", "run"));

        List<EmulatedCursorFinding> findings = new EmulatedCursorDetector()
                .detect(agg, sqls, stacks);
        assertThat(findings).hasSize(1);
    }

    @Test
    void belowMinWalksProducesNoFinding() {
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 4; i++) agg.add(exec(1, 1, 100L));
        for (int i = 0; i < 4; i++) agg.add(exec(2, 2, 100L));

        Map<Integer, String> sqls = Map.of(1, CUSTOMER_WALK, 2, CUSTOMER_FETCH);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.Job", "readNextMaster",
                        "com.example.Job", "run"),
                2, stack("com.example.Job", "readMasterFields",
                        "com.example.Job", "run"));

        assertThat(new EmulatedCursorDetector().detect(agg, sqls, stacks)).isEmpty();
    }

    @Test
    void mismatchedWalkAndFetchCountsDropBelowPairRatio() {
        // 20 walks but only 2 fetches — they aren't iterated together.
        // Default ratio is 0.5; 2/20 = 0.1 is well below.
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 20; i++) agg.add(exec(1, 1, 100L));
        for (int i = 0; i < 2; i++) agg.add(exec(2, 2, 100L));

        Map<Integer, String> sqls = Map.of(1, CUSTOMER_WALK, 2, CUSTOMER_FETCH);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, stack("com.example.Job", "readNextMaster",
                        "com.example.Job", "run"),
                2, stack("com.example.Job", "readMasterFields",
                        "com.example.Job", "run"));

        assertThat(new EmulatedCursorDetector().detect(agg, sqls, stacks)).isEmpty();
    }

    // --- helpers ---

    private static Event exec(int stackId, int sqlId, long duration) {
        Event e = new Event();
        e.timestampNanos = 0L;
        e.threadId = 1;
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = duration;
        e.rowsAffected = -1;
        e.batchSize = 0;
        return e;
    }

    /** Frames are in innermost-first order, matching how capture
     *  records them. Pairs of (class, method) expand into one frame
     *  each at line 1 — the detector ignores line numbers. */
    private static StackFrameSnapshot[] stack(String... classAndMethodPairs) {
        if (classAndMethodPairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected pairs of (class, method)");
        }
        List<StackFrameSnapshot> out = new ArrayList<>(classAndMethodPairs.length / 2);
        for (int i = 0; i < classAndMethodPairs.length; i += 2) {
            out.add(new StackFrameSnapshot(
                    classAndMethodPairs[i],
                    classAndMethodPairs[i + 1],
                    1));
        }
        return out.toArray(new StackFrameSnapshot[0]);
    }

    // Kept around as a reminder that the detector currently uses the
    // Aggregator interface and does not scan raw events. Having it
    // here means future per-event assertions (e.g. operation-scoped
    // filtering) can grow without refactoring the helper section.
    @SuppressWarnings("unused")
    private static Map<Integer, String> sqlMap(int id, String sql) {
        Map<Integer, String> m = new HashMap<>();
        m.put(id, sql);
        return m;
    }
}
