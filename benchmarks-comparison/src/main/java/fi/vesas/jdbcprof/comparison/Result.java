package fi.vesas.jdbclens.comparison;

/**
 * One scenario × mode measurement.
 *
 * <p>{@code meanNanosPerOp} is the headline number for the chart;
 * percentiles are recorded for completeness so a future "p99 overhead"
 * variant of the chart can be added without re-running.
 */
record Result(
        String scenario,
        Mode mode,
        int iterations,
        int repeats,
        double meanNanosPerOp,
        double p50NanosPerOp,
        double p95NanosPerOp,
        double p99NanosPerOp,
        double meanThroughputOpsPerSec) {
}
