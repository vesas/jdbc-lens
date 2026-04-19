package io.github.vesas.jdbcprof.sink;

import io.github.vesas.jdbcprof.capture.CaptureContext;
import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.SpscRingBuffer;
import io.github.vesas.jdbcprof.capture.SqlInternTable;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;
import io.github.vesas.jdbcprof.capture.StackTraceInternTable;
import io.github.vesas.jdbcprof.storage.BinaryLogWriter;

import java.io.IOException;
import java.util.List;

/**
 * Background drainer (spec §6). Wakes up periodically, pulls new
 * intern-table entries and events off the per-thread rings, and writes
 * them through a {@link BinaryLogWriter}.
 *
 * <p>Single daemon consumer across all rings. Producers are application
 * threads writing into their own {@link SpscRingBuffer}; the sink is
 * the only reader, so ring draining is lock-free on both sides.
 *
 * <p>Flush order per tick: SQL delta → stack delta → events. Intern
 * deltas must precede the events that reference them so the analysis
 * layer can resolve ids during a single forward pass.
 */
public final class Sink {

    private static final int DEFAULT_FLUSH_MILLIS = 100;
    private static final int DEFAULT_DRAIN_BATCH = 4096;

    private final CaptureContext ctx;
    private final BinaryLogWriter writer;
    private final int flushMillis;
    private final Event[] drainBuf;

    private int lastSqlId;
    private int lastStackId;

    private volatile Thread worker;
    private volatile boolean stopping;
    private volatile IOException failure;

    public Sink(CaptureContext ctx, BinaryLogWriter writer) {
        this(ctx, writer, DEFAULT_FLUSH_MILLIS, DEFAULT_DRAIN_BATCH);
    }

    public Sink(CaptureContext ctx, BinaryLogWriter writer, int flushMillis, int drainBatchSize) {
        this.ctx = ctx;
        this.writer = writer;
        this.flushMillis = flushMillis;
        this.drainBuf = new Event[drainBatchSize];
        for (int i = 0; i < drainBatchSize; i++) {
            this.drainBuf[i] = new Event();
        }
    }

    public synchronized void start() {
        if (worker != null) {
            throw new IllegalStateException("sink already started");
        }
        Thread t = new Thread(this::run, "jdbcprof-sink");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private void run() {
        try {
            while (!stopping) {
                flushOnce();
                try {
                    Thread.sleep(flushMillis);
                } catch (InterruptedException ignored) {
                    // Interruption is how stop() wakes us; loop condition handles the rest.
                }
            }
            flushOnce();
        } catch (IOException e) {
            failure = e;
        }
    }

    /**
     * Single drain + write cycle. Exposed for deterministic tests;
     * also called internally by the worker loop and by {@link #stop()}
     * for the final flush.
     */
    public synchronized void flushOnce() throws IOException {
        flushSqlDelta();
        flushStackDelta();
        flushEvents();
    }

    private void flushSqlDelta() throws IOException {
        SqlInternTable sqlIntern = ctx.sqlIntern();
        List<String> delta = sqlIntern.entriesSince(lastSqlId);
        if (!delta.isEmpty()) {
            writer.writeSqlDelta(lastSqlId, delta);
            lastSqlId += delta.size();
        }
    }

    private void flushStackDelta() throws IOException {
        StackTraceInternTable stackIntern = ctx.stackIntern();
        List<StackFrameSnapshot[]> delta = stackIntern.entriesSince(lastStackId);
        if (!delta.isEmpty()) {
            writer.writeStackDelta(lastStackId, delta);
            lastStackId += delta.size();
        }
    }

    private void flushEvents() throws IOException {
        for (SpscRingBuffer ring : ctx.allRings()) {
            int n;
            while ((n = ring.drain(drainBuf)) > 0) {
                writer.writeEvents(drainBuf, n);
                if (n < drainBuf.length) {
                    break;
                }
            }
        }
    }

    /**
     * Signal shutdown, wait for the worker to finish, then do a final
     * flush-and-close. Safe to call multiple times — subsequent calls
     * after the first successful stop are no-ops.
     *
     * <p>Deliberately NOT {@code synchronized} on the instance: the
     * worker's post-loop {@link #flushOnce()} is synchronized, and if
     * {@code stop} held the same monitor across {@link Thread#join()}
     * the worker could never acquire it to finish. We narrow the
     * monitor to the state-transition bookkeeping and do the
     * {@code join} lock-free.
     */
    public void stop() throws IOException {
        Thread t;
        synchronized (this) {
            t = worker;
            if (t == null) {
                if (!stopping) {
                    stopping = true;
                    flushOnce();
                }
                writer.close();
                return;
            }
            stopping = true;
        }
        t.interrupt();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while stopping sink", e);
        }
        IOException f;
        synchronized (this) {
            worker = null;
            f = failure;
            failure = null;
        }
        if (f != null) {
            try {
                writer.close();
            } catch (IOException suppress) {
                f.addSuppressed(suppress);
            }
            throw f;
        }
        writer.close();
    }
}
