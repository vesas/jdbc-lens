package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

/**
 * A SQL template that was prepared more than once from the same call-site
 * within one logical operation — a {@code PreparedStatement} constructed
 * inside a loop instead of above it (spec §8.3).
 */
public record RepeatedPrepareFinding(
        String sql,
        int prepareCount,
        long totalPrepareNanos,
        StackFrameSnapshot callSite,
        String operationName) {
}
