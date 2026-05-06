package fi.vesas.jdbclens.comparison;

/**
 * Which (if any) JDBC interceptor wraps the DataSource for a run.
 *
 * <p>{@link #NONE} is the baseline — application code talking straight
 * to the H2 driver. {@link #JDBCPROF} wraps with our profiler (recording
 * to a tmpfile log). {@link #P6SPY} wraps with P6Spy's {@code P6DataSource}
 * configured to log every statement to a file. The intent is to compare
 * the steady-state overhead of two interceptors that are both "on and
 * recording," not to compare an idle pass-through against an active one.
 */
public enum Mode {
    NONE,
    JDBCPROF,
    P6SPY
}
