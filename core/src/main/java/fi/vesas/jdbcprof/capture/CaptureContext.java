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
 * <p>Per-thread state — ring buffer, current operation id, current
 * invocation id — lives in a single {@link ThreadState} object held
 * in one {@link ThreadLocal}. The hot path's {@link #emit} therefore
 * pays one {@code ThreadLocal.get()} per call, not three. Op/invocation
 * ids are primitive {@code long} fields, so the
 * {@link #setCurrentOperation} path doesn't allocate either.
 */
public final class CaptureContext {

    /** Sentinel value stored in {@link Event#operationId} when no op-id is set. */
    public static final long NO_OPERATION = -1L;

    /** Sentinel stored in {@link Event#operationInvocationId} when no op is active. */
    public static final long NO_OPERATION_INVOCATION = -1L;

    /**
     * Sentinel stored in {@link Event#stackTraceId} when the emitting
     * site asked us to skip the stack walk — used for events whose
     * call-site is not what the analyzer attributes against (NEXT, the
     * various CLOSE events). Walking 30 frames per emit dominates the
     * hot-path cost, so the wrappers for those events use the
     * {@link #emitNoTrace} overload to skip it.
     */
    public static final int NO_STACK_TRACE = -1;

    /**
     * Number of bottom-of-stack frames {@link StackTraceInternTable} is
     * told to drop on every walk. Three matches the depth of dispatch
     * frames between the {@code WALKER.walk(...)} site and the user's
     * code on the most direct emit path
     * ({@code wrapper.executeQuery → CaptureContext.emit(8-arg)
     * → StackTraceInternTable.internCurrent}). Paths that go through
     * an extra layer (e.g. {@code resolveSqlId}, the 7-arg
     * {@code emit} overload) leave one or two of our own frames at
     * the bottom of the snapshot — harmless, since
     * {@link fi.vesas.jdbcprof.analysis.Attribution Attribution}
     * filters them out by package prefix at report time. Picking a
     * larger number to cover those longer paths would risk eating into
     * application code on the shortest path.
     */
    private static final int INFRA_FRAMES_TO_SKIP = 3;

    private final SqlInternTable sqlIntern = new SqlInternTable();
    private final StackTraceInternTable stackIntern;
    private final OperationInternTable opIntern = new OperationInternTable();
    private final ParameterValuesInternTable paramValuesIntern = new ParameterValuesInternTable();
    private final boolean captureParameterValues;

    private final int ringCapacity;
    private final CopyOnWriteArrayList<SpscRingBuffer> allRings = new CopyOnWriteArrayList<>();
    private final ThreadLocal<ThreadState> threadState;
    // Session-global monotonic source for invocation ids. Single
    // AtomicLong is adequate: contention only at op boundaries, not
    // per-query, and the wrapping cost is paid once per operation.
    private final AtomicLong invocationCounter = new AtomicLong(0L);

    /**
     * Per-thread mutable bag: the ring buffer (final, registered once
     * at first use), the cached thread id (final, captured once at
     * construction), plus the current operation/invocation ids
     * (primitive longs, written only by the owning thread via
     * {@link #setCurrentOperation}). No synchronisation: every
     * mutation is on the same thread that reads it.
     */
    private static final class ThreadState {
        final SpscRingBuffer ring;
        final int threadId;
        long operationId = NO_OPERATION;
        long invocationId = NO_OPERATION_INVOCATION;

        ThreadState(SpscRingBuffer ring) {
            this.ring = ring;
            this.threadId = (int) Thread.currentThread().threadId();
        }
    }

    public CaptureContext(int ringCapacity, int stackDepthLimit) {
        this(ringCapacity, stackDepthLimit, false);
    }

    public CaptureContext(int ringCapacity, int stackDepthLimit, boolean captureParameterValues) {
        this.ringCapacity = ringCapacity;
        this.captureParameterValues = captureParameterValues;
        this.stackIntern = new StackTraceInternTable(stackDepthLimit, INFRA_FRAMES_TO_SKIP);
        // Capture the parameters as effectively final locals so the
        // initializer lambda doesn't re-read instance fields it has no
        // ordering guarantees about.
        final int capacity = ringCapacity;
        final CopyOnWriteArrayList<SpscRingBuffer> rings = allRings;
        this.threadState = ThreadLocal.withInitial(() -> {
            SpscRingBuffer r = new SpscRingBuffer(capacity);
            rings.add(r);
            return new ThreadState(r);
        });
    }

    public SpscRingBuffer currentRing() {
        return threadState.get().ring;
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
        ThreadState ts = threadState.get();
        if (name == null) {
            ts.operationId = NO_OPERATION;
            ts.invocationId = NO_OPERATION_INVOCATION;
            return;
        }
        int id = opIntern.intern(name);
        ts.operationId = id;
        ts.invocationId = invocationCounter.incrementAndGet();
    }

    /** The current thread's invocation id, or {@link #NO_OPERATION_INVOCATION} if none. */
    public long currentInvocationId() {
        return threadState.get().invocationId;
    }

    /** The current thread's operation id, or {@link #NO_OPERATION} if none. */
    public long currentOperationId() {
        return threadState.get().operationId;
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
        emit(eventType, sqlId, startNanos, durationNanos,
                rowsAffected, batchSize, parameterFingerprint,
                parameterValuesId, stackIntern.internCurrent());
    }

    /**
     * Emit variant that takes the {@code stackTraceId} as a precomputed
     * argument instead of calling {@link StackTraceInternTable#internCurrent}.
     * Pass {@link #NO_STACK_TRACE} to record an event without a stack —
     * appropriate for NEXT and CLOSE events, whose call-sites the
     * analyzer does not attribute against. Skipping the 30-frame walk
     * for these is the largest single hot-path saving available
     * without changing the data model.
     */
    public void emitNoTrace(byte eventType, int sqlId,
                            long startNanos, long durationNanos,
                            int rowsAffected, int batchSize,
                            long parameterFingerprint) {
        emit(eventType, sqlId, startNanos, durationNanos,
                rowsAffected, batchSize, parameterFingerprint,
                -1, NO_STACK_TRACE);
    }

    private void emit(byte eventType, int sqlId,
                      long startNanos, long durationNanos,
                      int rowsAffected, int batchSize,
                      long parameterFingerprint,
                      int parameterValuesId,
                      int stackTraceId) {
        ThreadState ts = threadState.get();
        SpscRingBuffer ring = ts.ring;
        Event e = ring.claim();
        if (e == null) {
            ring.recordDrop();
            return;
        }
        e.timestampNanos = startNanos;
        e.threadId = ts.threadId;
        e.operationId = ts.operationId;
        e.operationInvocationId = ts.invocationId;
        e.eventType = eventType;
        e.sqlId = sqlId;
        e.stackTraceId = stackTraceId;
        e.durationNanos = durationNanos;
        e.rowsAffected = rowsAffected;
        e.batchSize = batchSize;
        e.parameterFingerprint = parameterFingerprint;
        e.parameterValuesId = parameterValuesId;
        ring.publish();
    }
}
