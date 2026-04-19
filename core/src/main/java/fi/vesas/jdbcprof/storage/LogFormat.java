package fi.vesas.jdbcprof.storage;

/**
 * On-disk constants for the binary recording format (spec §7).
 *
 * <p>The format is a header, a stream of length-prefixed records, a
 * terminating {@link #REC_END} marker, and a CRC32 computed over every
 * preceding byte. Record types are extensible: a reader that encounters
 * an unknown type can skip past it using the record's length field.
 *
 * <p>All multi-byte integers are big-endian. The event record is
 * fixed at {@link #EVENT_BYTES} bytes — 45 bytes of payload plus 3
 * bytes of trailing padding (spec §5.2).
 */
public final class LogFormat {

    private LogFormat() {
    }

    /** ASCII "JDBL" — first four bytes of every recording. */
    public static final int MAGIC = 0x4A44424C;

    /** Current on-disk format version. Bumps invalidate older readers. */
    public static final int VERSION = 3;

    /** Delta flush of {@link fi.vesas.jdbcprof.capture.SqlInternTable} entries. */
    public static final byte REC_SQL_DELTA = 1;

    /** Delta flush of {@link fi.vesas.jdbcprof.capture.StackTraceInternTable} entries. */
    public static final byte REC_STACK_DELTA = 2;

    /** Batch of captured events. */
    public static final byte REC_EVENTS = 3;

    /** Closing marker. Written exactly once, immediately before the CRC. */
    public static final byte REC_END = 4;

    /** Delta flush of {@link fi.vesas.jdbcprof.capture.OperationInternTable} entries. */
    public static final byte REC_OP_DELTA = 5;

    /** Delta flush of {@link fi.vesas.jdbcprof.capture.ParameterValuesInternTable} entries. */
    public static final byte REC_PARAM_VALUES_DELTA = 6;

    /** Fixed wire size of one event record, including trailing padding. */
    public static final int EVENT_BYTES = 60;
}
