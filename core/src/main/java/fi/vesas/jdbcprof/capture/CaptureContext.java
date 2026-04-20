package fi.vesas.jdbcprof.capture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Sentinel value stored in {@link Event#operationId} when no op-id is set. */
    public static final long NO_OPERATION = -1L;

    /** Sentinel stored in {@link Event#operationInvocationId} when no op is active. */
    public static final long NO_OPERATION_INVOCATION = -1L;

    private final SqlInternTable sqlIntern = new SqlInternTable();
    private final StackTraceInternTable stackIntern;
    private final OperationInternTable opIntern = new OperationInternTable();
    private final ParameterValuesInternTable paramValuesIntern = new ParameterValuesInternTable();
    private final boolean captureParameterValues;

    private final int ringCapacity;
    private final CopyOnWriteArrayList<SpscRingBuffer> allRings = new CopyOnWriteArrayList<>();
    private final ThreadLocal<SpscRingBuffer> threadRing;
    // Long boxing here is fine — set rarely (once per operation
    // boundary, not per query) so the per-emit read below only pays a
    // Long.longValue call that the JIT strips. Initial value -1 mirrors
    // NO_OPERATION.
    private final ThreadLocal<Long> currentOperation = ThreadLocal.withInitial(() -> NO_OPERATION);
    // Per-thread current invocation id. Bumped off `invocationCounter`
    // each time setCurrentOperation() is called with a non-null name,
    // so repeated invocations of the same op name are distinguishable
    // in the event stream.
    private final ThreadLocal<Long> currentInvocation =
            ThreadLocal.withInitial(() -> NO_OPERATION_INVOCATION);
    // Session-global monotonic source for invocation ids. Single
    // AtomicLong is adequate: contention only at op boundaries, not
    // per-query, and the wrapping cost is paid once per operation.
    private final AtomicLong invocationCounter = new AtomicLong(0L);

    public CaptureContext(int ringCapacity, int stackDepthLimit) {
        this(ringCapacity, stackDepthLimit, false);
    }

    public CaptureContext(int ringCapacity, int stackDepthLimit, boolean captureParameterValues) {
        this.ringCapacity = ringCapacity;
        this.captureParameterValues = captureParameterValues;
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

    public OperationInternTable opIntern() {
        return opIntern;
    }

    public ParameterValuesInternTable paramValuesIntern() {
        return paramValuesIntern;
    }

    public boolean captureParameterValues() {
        return captureParameterValues;
    }

    /**
     * Set the current thread's operation id. {@code null} clears it
     * (future events will carry {@link #NO_OPERATION}).
     */
    public void setCurrentOperation(String name) {
        if (name == null) {
            currentOperation.set(NO_OPERATION);
            currentInvocation.set(NO_OPERATION_INVOCATION);
            return;
        }
        int id = opIntern.intern(name);
        currentOperation.set((long) id);
        currentInvocation.set(invocationCounter.incrementAndGet());
    }

    /** The current thread's invocation id, or {@link #NO_OPERATION_INVOCATION} if none. */
    public long currentInvocationId() {
        return currentInvocation.get();
    }

    /** The current thread's operation id, or {@link #NO_OPERATION} if none. */
    public long currentOperationId() {
        return currentOperation.get();
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
                     int rowsAffected, int batchSize,
                     long parameterFingerprint) {
        emit(eventType, sqlId, startNanos, durationNanos,
                rowsAffected, batchSize, parameterFingerprint, -1);
    }

    public void emit(byte eventType, int sqlId,
                     long startNanos, long durationNanos,
                     int rowsAffected, int batchSize,
                     long parameterFingerprint,
                     int parameterValuesId) {
        SpscRingBuffer ring = threadRing.get();
        Event e = ring.claim();
        if (e == null) {
            ring.recordDrop();
            return;
        }
        e.timestampNanos = startNanos;
        e.threadId = (int) Thread.currentThread().getId();
        e.operationId = currentOperation.get();
        e.operationInvocationId = currentInvocation.get();
        e.eventType = eventType;
        e.sqlId = sqlId;
        e.stackTraceId = stackIntern.internCurrent();
        e.durationNanos = durationNanos;
        e.rowsAffected = rowsAffected;
        e.batchSize = batchSize;
        e.parameterFingerprint = parameterFingerprint;
        e.parameterValuesId = parameterValuesId;
        ring.publish();
    }
}
