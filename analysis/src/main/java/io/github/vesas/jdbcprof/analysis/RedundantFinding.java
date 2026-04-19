package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;

/**
 * One redundant-query pattern surfaced by {@link RedundantQueryDetector}:
 * the same SQL template executed with the same parameter binding from
 * the same call-site more than once inside the same operation. Prime
 * caching candidate — each repeat is almost certainly asking the
 * database a question the application already knows the answer to.
 */
public record RedundantFinding(
        long opId,
        String opName,
        int sqlId,
        String sql,
        long parameterFingerprint,
        int stackTraceId,
        StackFrameSnapshot callSite,
        long count,
        long totalDurationNanos) {
}
