package io.github.vesas.jdbcprof;

import javax.sql.DataSource;

/**
 * Public entry points for the JDBC call-site profiler. See spec §10.
 *
 * <p>All methods are stubs at this stage — Phase 1 (capture + storage)
 * fills them in. The shape is frozen so downstream adapters and docs
 * can compile against it.
 */
public final class Profiler {

    private Profiler() {
    }

    /**
     * Wraps a real {@link DataSource} so that every JDBC operation
     * performed through it is recorded. The only required integration
     * step for applications.
     */
    public static DataSource wrap(DataSource real) {
        if (real == null) {
            throw new IllegalArgumentException("real DataSource must not be null");
        }
        throw new UnsupportedOperationException("phase 1 not implemented");
    }

    /**
     * Begins recording. Must be called once before any wrapped
     * DataSource produces events that should be captured.
     */
    public static void start(ProfilerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        throw new UnsupportedOperationException("phase 1 not implemented");
    }

    /**
     * Flushes buffers, closes the output file, stops the sink thread.
     * Also runs automatically on JVM shutdown via a hook.
     */
    public static void stop() {
        throw new UnsupportedOperationException("phase 1 not implemented");
    }

    /**
     * Sets a thread-local operation id used to scope N+1 detection
     * (spec §3, §8.3). Typically invoked from request/message/test
     * boundaries.
     */
    public static void currentOperation(String operationId) {
        throw new UnsupportedOperationException("phase 3 not implemented");
    }
}
