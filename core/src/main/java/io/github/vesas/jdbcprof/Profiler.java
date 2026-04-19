package io.github.vesas.jdbcprof;

import io.github.vesas.jdbcprof.capture.CaptureContext;
import io.github.vesas.jdbcprof.capture.CapturingDataSource;
import io.github.vesas.jdbcprof.sink.Sink;
import io.github.vesas.jdbcprof.storage.BinaryLogWriter;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Public entry points for the JDBC call-site profiler (spec §10).
 *
 * <p>Lifecycle: {@link #start(ProfilerConfig)} installs a session,
 * {@link #wrap(DataSource)} binds application DataSources to it,
 * {@link #stop()} drains and closes the recording. The session is a
 * process-wide singleton — there is no scenario in Phase 1 for
 * multiple concurrent recordings.
 *
 * <p>A JVM shutdown hook calls {@link #stop()} automatically so a
 * recording is flushed even if the application terminates without
 * an orderly teardown.
 */
public final class Profiler {

    private static volatile Session session;
    private static final Object LOCK = new Object();

    private Profiler() {
    }

    /**
     * Begins recording. Must be called before {@link #wrap(DataSource)}
     * so that wrapped DataSources bind to a live session.
     */
    public static void start(ProfilerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        synchronized (LOCK) {
            if (session != null) {
                throw new IllegalStateException("Profiler already started");
            }
            CaptureContext ctx = new CaptureContext(
                    config.ringBufferCapacity(), config.stackDepthLimit(),
                    config.captureParameterValues());
            BinaryLogWriter writer;
            try {
                writer = new BinaryLogWriter(config.outputFile());
            } catch (IOException e) {
                throw new UncheckedIOException("failed to open " + config.outputFile(), e);
            }
            Sink sink = new Sink(ctx, writer);
            Thread hook = new Thread(Profiler::stopQuietly, "jdbcprof-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            session = new Session(ctx, sink, hook);
            sink.start();
        }
    }

    /**
     * Flushes buffers, closes the recording, and stops the sink thread.
     * Safe to call multiple times — subsequent calls after the first
     * successful stop are no-ops.
     */
    public static void stop() {
        Session s;
        synchronized (LOCK) {
            s = session;
            if (s == null) {
                return;
            }
            session = null;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(s.shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM already shutting down — the hook is running us.
        }
        try {
            s.sink.stop();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to stop profiler", e);
        }
    }

    /**
     * Wraps a real {@link DataSource} so every JDBC operation flowing
     * through it is captured. Requires {@link #start(ProfilerConfig)}
     * to have been called; events from a DataSource wrapped before
     * {@code start} have nowhere to go.
     */
    public static DataSource wrap(DataSource real) {
        if (real == null) {
            throw new IllegalArgumentException("real DataSource must not be null");
        }
        Session s = session;
        if (s == null) {
            throw new IllegalStateException("Profiler.start() must be called before wrap()");
        }
        return new CapturingDataSource(real, s.ctx);
    }

    /**
     * Sets a thread-local operation id that subsequent events on this
     * thread are attributed to (spec §3, §10). Pass {@code null} to
     * clear the id. Silent no-op when no profiler session is active —
     * production code that wires the id via a servlet filter or test
     * extension does not have to special-case "profiler disabled."
     */
    public static void currentOperation(String operationId) {
        Session s = session;
        if (s == null) {
            return;
        }
        s.ctx.setCurrentOperation(operationId);
    }

    private static void stopQuietly() {
        try {
            stop();
        } catch (RuntimeException ignored) {
            // Shutdown-hook failure must not prevent JVM exit.
        }
    }

    private record Session(CaptureContext ctx, Sink sink, Thread shutdownHook) {}
}
