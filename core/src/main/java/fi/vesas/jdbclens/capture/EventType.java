package fi.vesas.jdbclens.capture;

/**
 * JDBC operation kinds captured by the profiler (spec §5.2).
 *
 * <p>The ordinal of each constant is the stable byte encoding used in
 * the on-disk event record. New values must be appended; reordering
 * breaks all recordings written by prior versions.
 */
public enum EventType {
    PREPARE,
    EXECUTE_QUERY,
    EXECUTE_UPDATE,
    EXECUTE_BATCH,
    NEXT,
    COMMIT,
    ROLLBACK,
    CLOSE;

    private static final EventType[] VALUES = values();

    public byte code() {
        return (byte) ordinal();
    }

    public static EventType fromCode(byte code) {
        int idx = code & 0xFF;
        if (idx >= VALUES.length) {
            throw new IllegalArgumentException("unknown EventType code: " + idx);
        }
        return VALUES[idx];
    }
}
