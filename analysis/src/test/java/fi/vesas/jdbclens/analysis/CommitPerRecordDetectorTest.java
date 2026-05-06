package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommitPerRecordDetectorTest {

    @Test
    void flagsLongRunOfShortTxsSharingAncestor() {
        // A 20-TX run inside BatchJob.run: each iteration is one
        // EXECUTE_UPDATE + COMMIT. All TXs share run() as the outer
        // ancestor of the inner helper frames.
        StackFrameSnapshot[] innerFrames = {
                new StackFrameSnapshot("fi.example.BatchJob", "rewriteRecord", 120),
                new StackFrameSnapshot("fi.example.BatchJob", "run", 50)
        };
        StackFrameSnapshot[] commitFrames = {
                new StackFrameSnapshot("fi.example.BatchJob", "checkpoint", 200),
                new StackFrameSnapshot("fi.example.BatchJob", "run", 50)
        };
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, innerFrames,
                2, commitFrames);
        Map<Integer, String> sqls = Map.of(
                10, "UPDATE customers SET name = ? WHERE id = ?");

        List<Transaction> txs = new ArrayList<>();
        long ts = 0L;
        for (int i = 0; i < 20; i++) {
            List<Event> events = List.of(
                    event(1, EventType.EXECUTE_UPDATE, ts, 500_000L, 10, 1),
                    event(1, EventType.COMMIT, ts + 1_000_000L, 500_000L, -1, 2));
            txs.add(txn(events));
            ts += 3_000_000L; // 3 ms between TX starts — well under the 50ms gap cap
        }

        List<CommitPerRecordFinding> out = new CommitPerRecordDetector()
                .detect(Map.of(7L, txs), Map.of(7L, "batch-run"), sqls, stacks);

        assertThat(out).hasSize(1);
        CommitPerRecordFinding f = out.get(0);
        assertThat(f.runLength()).isEqualTo(20);
        assertThat(f.opName()).isEqualTo("batch-run");
        assertThat(f.commonAncestor()).isNotNull();
        assertThat(f.commonAncestor().methodName()).isEqualTo("run");
        assertThat(f.writesPerTxn()).isEqualTo(1);
        assertThat(f.sampleSqls()).containsExactly("UPDATE customers SET name = ? WHERE id = ?");
    }

    @Test
    void ignoresSingleLargeTxWithManyQueries() {
        // One TX with ten queries does not match — wrong shape.
        StackFrameSnapshot[] frames = {
                new StackFrameSnapshot("fi.example.BulkJob", "run", 1)
        };
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1, frames);
        Map<Integer, String> sqls = Map.of(10, "SELECT * FROM t WHERE id = ?");

        List<Event> events = new ArrayList<>();
        long ts = 0L;
        for (int i = 0; i < 10; i++) {
            events.add(event(1, EventType.EXECUTE_QUERY, ts, 1_000_000L, 10, 1));
            ts += 2_000_000L;
        }
        events.add(event(1, EventType.COMMIT, ts, 500_000L, -1, 1));

        List<Transaction> txs = List.of(txn(events));

        List<CommitPerRecordFinding> out = new CommitPerRecordDetector()
                .detect(Map.of(7L, txs), Map.of(7L, "bulk"), sqls, stacks);

        assertThat(out).isEmpty();
    }

    @Test
    void producesOneFindingPerDistinctAncestorRun() {
        // Two distinct runs in the same operation, different ancestors.
        // Detector must emit two findings and not bleed the runs into
        // one.
        StackFrameSnapshot[] jobAFrames = {
                new StackFrameSnapshot("fi.example.JobA", "inner", 10),
                new StackFrameSnapshot("fi.example.JobA", "run", 5)
        };
        StackFrameSnapshot[] jobACommit = {
                new StackFrameSnapshot("fi.example.JobA", "commit", 15),
                new StackFrameSnapshot("fi.example.JobA", "run", 5)
        };
        StackFrameSnapshot[] jobBFrames = {
                new StackFrameSnapshot("fi.example.JobB", "inner", 30),
                new StackFrameSnapshot("fi.example.JobB", "run", 25)
        };
        StackFrameSnapshot[] jobBCommit = {
                new StackFrameSnapshot("fi.example.JobB", "commit", 35),
                new StackFrameSnapshot("fi.example.JobB", "run", 25)
        };
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, jobAFrames, 2, jobACommit,
                3, jobBFrames, 4, jobBCommit);
        Map<Integer, String> sqls = Map.of(10, "UPDATE a SET x = ?", 11, "UPDATE b SET y = ?");

        List<Transaction> txs = new ArrayList<>();
        long ts = 0L;
        // 10 TXs through JobA.run.
        for (int i = 0; i < 10; i++) {
            txs.add(txn(List.of(
                    event(1, EventType.EXECUTE_UPDATE, ts, 500_000L, 10, 1),
                    event(1, EventType.COMMIT, ts + 1_000_000L, 500_000L, -1, 2))));
            ts += 3_000_000L;
        }
        // Larger gap than maxInterTxnGapNanos — forces a break.
        ts += 200_000_000L;
        // 10 TXs through JobB.run.
        for (int i = 0; i < 10; i++) {
            txs.add(txn(List.of(
                    event(1, EventType.EXECUTE_UPDATE, ts, 500_000L, 11, 3),
                    event(1, EventType.COMMIT, ts + 1_000_000L, 500_000L, -1, 4))));
            ts += 3_000_000L;
        }

        List<CommitPerRecordFinding> out = new CommitPerRecordDetector()
                .detect(Map.of(7L, txs), Map.of(7L, "two-jobs"), sqls, stacks);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(f -> f.commonAncestor().className())
                .containsExactlyInAnyOrder("fi.example.JobA", "fi.example.JobB");
        assertThat(out).allSatisfy(f -> {
            assertThat(f.runLength()).isEqualTo(10);
            assertThat(f.commonAncestor().methodName()).isEqualTo("run");
        });
    }

    @Test
    void emptyTxListProducesNoFindings() {
        // No transactions means the autocommit-only case surfaces as
        // an empty list in txByOp — detector must ignore it.
        List<CommitPerRecordFinding> out = new CommitPerRecordDetector()
                .detect(Map.of(7L, List.of()), Map.of(7L, "autocommit"),
                        Map.of(), Map.of());
        assertThat(out).isEmpty();
    }

    private static Event event(int threadId, EventType type, long ts, long dur,
                               int sqlId, int stackTraceId) {
        Event e = new Event();
        e.timestampNanos = ts;
        e.threadId = threadId;
        e.eventType = type.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackTraceId;
        e.durationNanos = dur;
        e.rowsAffected = -1;
        return e;
    }

    /**
     * Build a {@link Transaction} directly from its events by running
     * the shared reconstructor on a single-thread event list of one
     * TX. Keeps the test isolated from any private test builder and
     * guarantees the same stats the production code computes.
     */
    private static Transaction txn(List<Event> events) {
        List<Transaction> list = Transactions.reconstruct(7L, new ArrayList<>(events));
        if (list.size() != 1) {
            throw new AssertionError("expected exactly one reconstructed txn, got " + list.size());
        }
        return list.get(0);
    }

}
