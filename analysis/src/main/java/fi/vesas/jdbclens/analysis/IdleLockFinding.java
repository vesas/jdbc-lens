package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.List;

/**
 * A transaction held open across a long stretch of non-DB work. The
 * suspicion is that the app is doing something outside the database
 * (HTTP call, file I/O, heavy CPU) while the DB still holds whatever
 * write locks the transaction's prior UPDATE/INSERT/DELETE claimed —
 * a classic contributor to production deadlocks and tail latency.
 *
 * <p>The call-site immediately before the biggest gap is the
 * load-bearing piece for the reader: that's where the suspicious
 * work is happening in the application's own code.
 */
public record IdleLockFinding(
        long opId,
        String opName,
        long txStartNanos,
        long txDurationNanos,
        long maxIdleGapNanos,
        long totalIdleNanos,
        StackFrameSnapshot siteBeforeGap,
        String sqlBeforeGap,
        String sqlAfterGap,
        /** Timestamped segments covering the full TX duration: each
         *  EXECUTE/COMMIT/ROLLBACK event, each gap between them, and
         *  any trailing idle before the terminator. Lets the report
         *  draw a proportional mini-Gantt without recomputing. */
        List<Segment> segments,
        /** Every write template the TX executed, in order — "what's
         *  locked while the gap sits there." De-duplicated SQL text
         *  so the card can list each once with a count. */
        List<WriteRef> writes) {

    public enum SegmentKind { EXECUTE_QUERY, EXECUTE_UPDATE, EXECUTE_BATCH, PREPARE, IDLE, COMMIT, ROLLBACK, OTHER }

    public record Segment(SegmentKind kind, long durationNanos, String sql, boolean isMaxGap) {
    }

    public record WriteRef(String sql, long count, long totalDurationNanos) {
    }

    public IdleLockFinding {
        segments = List.copyOf(segments);
        writes = List.copyOf(writes);
    }
}
