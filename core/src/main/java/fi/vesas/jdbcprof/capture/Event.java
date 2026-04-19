package fi.vesas.jdbcprof.capture;

/**
 * One JDBC operation captured at the instant it occurred (spec §5.2).
 *
 * <p>Intentionally a mutable POJO with public fields. The hot path
 * writes directly into slots owned by {@link SpscRingBuffer} — getters
 * and setters would both add bytecode for no semantic benefit and
 * tempt allocations. The class is internal; no one outside the
 * library's own packages should touch it.
 *
 * <p>Wire size: 45 bytes of payload, padded to 48 in the binary log
 * (spec §5.2). The in-memory object is larger due to Java object
 * headers; that is acceptable because slots are pre-allocated once
 * per ring and never copied during capture.
 */
public final class Event {

    public long timestampNanos;
    public int threadId;
    public long operationId;
    public byte eventType;
    public int sqlId;
    public int stackTraceId;
    public long durationNanos;
    public int rowsAffected;
    public int batchSize;
    /**
     * 64-bit hash of the parameter binding in effect at execute time
     * (spec §5.5 extension). Zero for events that have no bound
     * parameters (PREPARE, NEXT, COMMIT, ROLLBACK, CLOSE, or ad-hoc
     * {@link java.sql.Statement} executes).
     */
    public long parameterFingerprint;

    /**
     * Id into {@link ParameterValuesInternTable} when value capture
     * is enabled ({@code ProfilerConfig.captureParameterValues}); {@code
     * -1} otherwise.
     */
    public int parameterValuesId;

    public Event() {
        // Fields default to zero; rowsAffected stays 0 until set by the producer.
    }

    public void reset() {
        timestampNanos = 0L;
        threadId = 0;
        operationId = 0L;
        eventType = 0;
        sqlId = 0;
        stackTraceId = 0;
        durationNanos = 0L;
        rowsAffected = 0;
        batchSize = 0;
        parameterFingerprint = 0L;
        parameterValuesId = -1;
    }

    public void copyFrom(Event other) {
        this.timestampNanos = other.timestampNanos;
        this.threadId = other.threadId;
        this.operationId = other.operationId;
        this.eventType = other.eventType;
        this.sqlId = other.sqlId;
        this.stackTraceId = other.stackTraceId;
        this.durationNanos = other.durationNanos;
        this.rowsAffected = other.rowsAffected;
        this.batchSize = other.batchSize;
        this.parameterFingerprint = other.parameterFingerprint;
        this.parameterValuesId = other.parameterValuesId;
    }
}
