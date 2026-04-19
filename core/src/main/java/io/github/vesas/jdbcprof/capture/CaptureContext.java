package io.github.vesas.jdbcprof.capture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared state for one profiling session: the intern tables and the
 * fleet of per-thread ring buffers (spec §4, §5.6).
 *
 * <p>One {@code CaptureContext} instance is live per call to
 * {@code Profiler.start}. Wrapping classes hold a reference to it
 * and use {@link #currentRing()} to obtain the caller thread's
 * {@link SpscRingBuffer} on the hot path. Registration is automatic
 * on first use per thread via the thread-local initializer; the
 * sink consumes from {@link #allRings()}.
 *
 * <p>The {@link ThreadLocal} holds only primitive object references,
 * so per-thread bookkeeping is cheap. Rings themselves are
 * pre-allocated at construction (slot arrays), so first-call on a
 * thread pays the allocation cost once and then amortises to zero.
 */
public final class CaptureContext {

    private final SqlInternTable sqlIntern = new SqlInternTable();
    private final StackTraceInternTable stackIntern;

    private final int ringCapacity;
    private final CopyOnWriteArrayList<SpscRingBuffer> allRings = new CopyOnWriteArrayList<>();
    private final ThreadLocal<SpscRingBuffer> threadRing;

    public CaptureContext(int ringCapacity, int stackDepthLimit) {
        this.ringCapacity = ringCapacity;
        this.stackIntern = new StackTraceInternTable(stackDepthLimit);
        // Capture the parameter (effectively final) rather than this.ringCapacity
        // so the field's definite-assignment check is satisfied and the lambda
        // doesn't re-read a field that Java memory semantics need no help with.
        final int capacity = ringCapacity;
        final CopyOnWriteArrayList<SpscRingBuffer> rings = allRings;
        this.threadRing = ThreadLocal.withInitial(() -> {
            SpscRingBuffer r = new SpscRingBuffer(capacity);
            rings.add(r);
            return r;
        });
    }

    public SpscRingBuffer currentRing() {
        return threadRing.get();
    }

    public SqlInternTable sqlIntern() {
        return sqlIntern;
    }

    public StackTraceInternTable stackIntern() {
        return stackIntern;
    }

    /**
     * Snapshot of every ring registered so far. Used by the sink thread
     * to round-robin drain. Safe to iterate concurrently with
     * producer-side registrations thanks to {@link CopyOnWriteArrayList}.
     */
    public List<SpscRingBuffer> allRings() {
        return allRings;
    }

    /**
     * Hot-path event emission. Claims a slot on the caller thread's
     * ring, populates it, and publishes. On buffer-full the event is
     * dropped (spec §5.6 current policy) and counted.
     *
     * <p>All arguments are primitives — no boxing, no varargs. The
     * stack trace is captured at the callee's frame, so wrappers pay
     * one extra frame relative to direct emission; the analysis layer
     * skips these via the configured frame exclusions (spec §8.1).
     */
    public void emit(byte eventType, int sqlId,
                     long startNanos, long durationNanos,
                     int rowsAffected, int batchSize) {
        SpscRingBuffer ring = threadRing.get();
        Event e = ring.claim();
        if (e == null) {
            ring.recordDrop();
            return;
        }
        e.timestampNanos = startNanos;
        e.threadId = (int) Thread.currentThread().getId();
        e.operationId = 0L;
        e.eventType = eventType;
        e.sqlId = sqlId;
        e.stackTraceId = stackIntern.internCurrent();
        e.durationNanos = durationNanos;
        e.rowsAffected = rowsAffected;
        e.batchSize = batchSize;
        ring.publish();
    }
}
