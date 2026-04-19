package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

/**
 * One N+1 pattern surfaced by {@link N1Detector} (spec §8.3).
 *
 * <p>{@code representativeSite} is the attributed call-site of the
 * dominant stack — where the query is issued. {@code ancestor} is
 * the next application frame above it and is usually the actual bug
 * (the outer loop); it may be {@code null} if the call-site is
 * already the top frame or every frame above it is JDK.
 */
public record N1Finding(
        int sqlId,
        String sql,
        int dominantStackId,
        StackFrameSnapshot representativeSite,
        StackFrameSnapshot ancestor,
        long count,
        long totalDurationNanos) {
}
