package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.List;

/**
 * A run of back-to-back short explicit transactions that share a
 * common outer application frame. Signature of the COBOL "checkpoint
 * every record" habit — a loop that does a handful of statements per
 * iteration and commits at the bottom of the loop body — and of
 * equivalent ORM mis-use where each object save is wrapped in its
 * own TX.
 *
 * <p>The fix is almost always to widen the transaction boundary so a
 * batch of records shares one commit. The overhead a single commit
 * imposes (WAL flush, client round-trip, lock release/reacquire) is
 * amortised once instead of {@code runLength} times.
 */
public record CommitPerRecordFinding(
        long opId,
        String opName,
        int runLength,
        long totalWallNanos,
        long avgTxnWallNanos,
        long totalDbTimeNanos,
        long firstTimestampNanos,
        long lastTimestampNanos,
        /** The outer application frame every TX in the run shared —
         *  usually the {@code run()}/process method holding the loop.
         *  May be {@code null} if no ancestor frame was available. */
        StackFrameSnapshot commonAncestor,
        /** The call-site of the first statement in the first TX of the
         *  run — typically the inner body of the loop. */
        StackFrameSnapshot representativeCallSite,
        int writesPerTxn,
        int readsPerTxn,
        /** Distinct SQL templates seen in one iteration of the run,
         *  capped (see {@link CommitPerRecordDetector}). Lets the
         *  report show the body of the loop without dumping every
         *  execution. */
        List<String> sampleSqls) {

    public CommitPerRecordFinding {
        sampleSqls = List.copyOf(sampleSqls);
    }
}
