package fi.vesas.jdbcprof.comparison;

/**
 * One mode's worth of end-to-end measurements. Each {@code runs[i]} is
 * the wall-clock nanoseconds for one full execution of {@code sample-app}
 * — JVM startup, classpath scan, profiler init (if any), the workload,
 * and shutdown-hook flush all included. The summary stats are computed
 * over the timed runs only; warmup runs are dropped before {@code runs}
 * is populated.
 */
record E2eResult(
        Mode mode,
        long[] runsNanos,
        double meanNanos,
        long minNanos,
        long maxNanos,
        double stddevNanos) {
}
