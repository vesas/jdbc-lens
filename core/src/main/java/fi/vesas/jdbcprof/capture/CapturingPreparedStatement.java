package fi.vesas.jdbcprof.capture;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Objects;
import java.util.Calendar;
import java.util.function.Supplier;

/**
 * Wraps a real {@link PreparedStatement}. When a session is active at
 * prepare time the SQL is interned then and carried as {@code sqlId}
 * on every execution so the hot path does no string work (spec §5.5).
 * When no session is active at prepare time, the raw template is kept
 * and interned lazily on the first execute that sees a live session —
 * keeping the ids stable across the statement's lifetime.
 *
 * <p>Inherits {@link CapturingStatement} so the ad-hoc
 * {@code execute(String)} overloads, {@code close}, warning/fetch
 * settings, and the {@link java.sql.Statement} contract all come
 * through unchanged.
 *
 * <p>Each {@code setXxx} update keeps a per-index 64-bit hash of the
 * current binding. On execute those slots are folded into a single
 * {@code parameterFingerprint} attached to the event — the analysis
 * layer uses this to distinguish "50 calls, 50 different params"
 * (N+1) from "50 calls, same params" (redundant query).
 *
 * <p>Hot-path note: setters branch on {@link #captureValuesActive}
 * before computing display strings. With value capture off (the
 * default), {@code Integer.toString(x)} / {@code Long.toString(x)} /
 * {@code String.valueOf(x)} would otherwise be evaluated as setter
 * arguments and immediately discarded — one allocation per JDBC call,
 * for nothing. The branch keeps the fingerprint update path
 * allocation-free in the default configuration.
 */
final class CapturingPreparedStatement extends CapturingStatement implements PreparedStatement {

    private static final byte PREPARE = (byte) EventType.PREPARE.ordinal();
    private static final byte EXECUTE_QUERY = (byte) EventType.EXECUTE_QUERY.ordinal();
    private static final byte EXECUTE_UPDATE = (byte) EventType.EXECUTE_UPDATE.ordinal();
    private static final byte EXECUTE_BATCH = (byte) EventType.EXECUTE_BATCH.ordinal();

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    // Type tags keep the fingerprint for (tag=INT, value=0) distinct
    // from (tag=NULL) and from an unset slot. All tags are non-zero.
    private static final long TAG_NULL = 0x01L;
    private static final long TAG_BOOLEAN = 0x02L;
    private static final long TAG_BYTE = 0x03L;
    private static final long TAG_SHORT = 0x04L;
    private static final long TAG_INT = 0x05L;
    private static final long TAG_LONG = 0x06L;
    private static final long TAG_FLOAT = 0x07L;
    private static final long TAG_DOUBLE = 0x08L;
    private static final long TAG_BIGDECIMAL = 0x09L;
    private static final long TAG_STRING = 0x0AL;
    private static final long TAG_BYTES = 0x0BL;
    private static final long TAG_DATE = 0x0CL;
    private static final long TAG_TIME = 0x0DL;
    private static final long TAG_TIMESTAMP = 0x0EL;
    private static final long TAG_STREAM = 0x0FL;
    private static final long TAG_READER = 0x10L;
    private static final long TAG_OBJECT = 0x11L;
    private static final long TAG_REF = 0x12L;
    private static final long TAG_BLOB = 0x13L;
    private static final long TAG_CLOB = 0x14L;
    private static final long TAG_NCLOB = 0x15L;
    private static final long TAG_ARRAY = 0x16L;
    private static final long TAG_URL = 0x17L;
    private static final long TAG_ROWID = 0x18L;
    private static final long TAG_NSTRING = 0x19L;
    private static final long TAG_SQLXML = 0x1AL;

    private final PreparedStatement ps;
    private final String sql;
    private int sqlId;

    private long[] paramHashes;
    private String[] paramValues;
    private int maxIndex;

    CapturingPreparedStatement(PreparedStatement delegate, Supplier<CaptureContext> ctxSupplier,
                               String sql, int sqlId) {
        super(delegate, ctxSupplier);
        this.ps = delegate;
        this.sql = sql;
        this.sqlId = sqlId;
        this.lastSqlId = sqlId;
    }

    /**
     * Returns an interned id for {@link #sql}, interning on first call
     * if it wasn't done at prepare time. The session seen here may be
     * different from the one at prepare time (or may have just become
     * active) — either way we intern against the current session so
     * later events reference an id it knows about.
     */
    private int resolveSqlId(CaptureContext ctx) {
        int id = sqlId;
        if (id < 0) {
            id = ctx.sqlIntern().intern(sql);
            sqlId = id;
            lastSqlId = id;
            // The PREPARE event is best-effort: we missed the real
            // prepare call, so emit a zero-duration marker so the
            // analysis layer still sees this template's lifecycle.
            ctx.emit(PREPARE, id, 0L, 0L, -1, 0, 0L);
        }
        return id;
    }

    /**
     * True iff a profiler session is live and has opted in to keeping
     * display strings for parameter values. Read once per setter call
     * so the toString allocation only happens when capture is on.
     */
    private boolean captureValuesActive() {
        CaptureContext ctx = ctxSupplier.get();
        return ctx != null && ctx.captureParameterValues();
    }

    private void setSlot(int parameterIndex, long tag, long valueHash) {
        // Parameter indices are 1-based in JDBC. Keep the array
        // 1-based too so lookups don't need to shift.
        if (paramHashes == null || parameterIndex >= paramHashes.length) {
            int newLen = Math.max(parameterIndex + 1, paramHashes == null ? 4 : paramHashes.length * 2);
            long[] bigger = new long[newLen];
            if (paramHashes != null) {
                System.arraycopy(paramHashes, 0, bigger, 0, paramHashes.length);
            }
            paramHashes = bigger;
        }
        paramHashes[parameterIndex] = (tag * FNV_PRIME) ^ valueHash;
        if (parameterIndex > maxIndex) {
            maxIndex = parameterIndex;
        }
    }

    /**
     * Variant that also stores a display string for the value. Only
     * call this when {@link #captureValuesActive} is true — the
     * display argument is otherwise wasted work.
     */
    private void setSlotAndDisplay(int parameterIndex, long tag, long valueHash, String display) {
        setSlot(parameterIndex, tag, valueHash);
        if (paramValues == null || parameterIndex >= paramValues.length) {
            int newLen = Math.max(parameterIndex + 1, paramValues == null ? 4 : paramValues.length * 2);
            String[] bigger = new String[newLen];
            if (paramValues != null) {
                System.arraycopy(paramValues, 0, bigger, 0, paramValues.length);
            }
            paramValues = bigger;
        }
        paramValues[parameterIndex] = display;
    }

    private int internCurrentValues(CaptureContext ctx) {
        if (!ctx.captureParameterValues() || paramValues == null || maxIndex == 0) {
            return -1;
        }
        java.util.ArrayList<String> snapshot = new java.util.ArrayList<>(maxIndex);
        for (int i = 1; i <= maxIndex; i++) {
            String v = i < paramValues.length ? paramValues[i] : null;
            snapshot.add(v == null ? "(unset)" : v);
        }
        return ctx.paramValuesIntern().intern(new ParameterValues(snapshot));
    }

    private long fingerprint() {
        if (paramHashes == null) {
            return 0L;
        }
        long h = FNV_OFFSET;
        boolean any = false;
        for (int i = 0; i < paramHashes.length; i++) {
            long slot = paramHashes[i];
            if (slot == 0L) {
                continue;
            }
            any = true;
            h ^= (long) i;
            h *= FNV_PRIME;
            h ^= slot;
            h *= FNV_PRIME;
        }
        return any ? h : 0L;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        CaptureContext ctx = ctxSupplier.get();
        if (ctx == null) {
            return wrapResultSet(ps.executeQuery());
        }
        int id = resolveSqlId(ctx);
        long fp = fingerprint();
        int valuesId = internCurrentValues(ctx);
        long t0 = System.nanoTime();
        ResultSet rs = ps.executeQuery();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_QUERY, id, t0, t1 - t0, -1, 0, fp, valuesId);
        return wrapResultSet(rs);
    }

    @Override
    public int executeUpdate() throws SQLException {
        CaptureContext ctx = ctxSupplier.get();
        if (ctx == null) {
            return ps.executeUpdate();
        }
        int id = resolveSqlId(ctx);
        long fp = fingerprint();
        int valuesId = internCurrentValues(ctx);
        long t0 = System.nanoTime();
        int rows = ps.executeUpdate();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_UPDATE, id, t0, t1 - t0, rows, 0, fp, valuesId);
        return rows;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        CaptureContext ctx = ctxSupplier.get();
        if (ctx == null) {
            return ps.executeLargeUpdate();
        }
        int id = resolveSqlId(ctx);
        long fp = fingerprint();
        int valuesId = internCurrentValues(ctx);
        long t0 = System.nanoTime();
        long rows = ps.executeLargeUpdate();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_UPDATE, id, t0, t1 - t0, clampRows(rows), 0, fp, valuesId);
        return rows;
    }

    @Override
    public boolean execute() throws SQLException {
        CaptureContext ctx = ctxSupplier.get();
        if (ctx == null) {
            return ps.execute();
        }
        int id = resolveSqlId(ctx);
        long fp = fingerprint();
        int valuesId = internCurrentValues(ctx);
        long t0 = System.nanoTime();
        boolean hasResultSet = ps.execute();
        long t1 = System.nanoTime();
        if (hasResultSet) {
            ctx.emit(EXECUTE_QUERY, id, t0, t1 - t0, -1, 0, fp, valuesId);
        } else {
            int rows = ps.getUpdateCount();
            ctx.emit(EXECUTE_UPDATE, id, t0, t1 - t0, rows, 0, fp, valuesId);
        }
        return hasResultSet;
    }

    @Override
    public void addBatch() throws SQLException {
        ps.addBatch();
        batchSize++;
    }

    @Override
    public int[] executeBatch() throws SQLException {
        CaptureContext ctx = ctxSupplier.get();
        if (ctx == null) {
            int[] counts = ps.executeBatch();
            batchSize = 0;
            return counts;
        }
        // A batch's fingerprint is ill-defined — every bound row in the
        // batch has its own parameters and addBatch() doesn't surface
        // them to us. Pass 0L; the analysis layer ignores fingerprint
        // for EXECUTE_BATCH.
        int id = resolveSqlId(ctx);
        int size = batchSize;
        long t0 = System.nanoTime();
        int[] counts = ps.executeBatch();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_BATCH, id, t0, t1 - t0, -1, size, 0L);
        batchSize = 0;
        return counts;
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        CaptureContext ctx = ctxSupplier.get();
        if (ctx == null) {
            long[] counts = ps.executeLargeBatch();
            batchSize = 0;
            return counts;
        }
        int id = resolveSqlId(ctx);
        int size = batchSize;
        long t0 = System.nanoTime();
        long[] counts = ps.executeLargeBatch();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_BATCH, id, t0, t1 - t0, -1, size, 0L);
        batchSize = 0;
        return counts;
    }

    // --- parameter setters: capture fingerprint (+ display when enabled) + delegate ---

    private static String bytesDisplay(byte[] x) {
        return x == null ? "null" : "<" + x.length + " bytes>";
    }

    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException {
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_NULL, sqlType, "null");
        } else {
            setSlot(parameterIndex, TAG_NULL, sqlType);
        }
        ps.setNull(parameterIndex, sqlType);
    }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        long hash = sqlType ^ (typeName == null ? 0L : typeName.hashCode());
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_NULL, hash, "null");
        } else {
            setSlot(parameterIndex, TAG_NULL, hash);
        }
        ps.setNull(parameterIndex, sqlType, typeName);
    }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_BOOLEAN, x ? 1L : 0L, Boolean.toString(x));
        } else {
            setSlot(parameterIndex, TAG_BOOLEAN, x ? 1L : 0L);
        }
        ps.setBoolean(parameterIndex, x);
    }
    @Override public void setByte(int parameterIndex, byte x) throws SQLException {
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_BYTE, x, Byte.toString(x));
        } else {
            setSlot(parameterIndex, TAG_BYTE, x);
        }
        ps.setByte(parameterIndex, x);
    }
    @Override public void setShort(int parameterIndex, short x) throws SQLException {
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_SHORT, x, Short.toString(x));
        } else {
            setSlot(parameterIndex, TAG_SHORT, x);
        }
        ps.setShort(parameterIndex, x);
    }
    @Override public void setInt(int parameterIndex, int x) throws SQLException {
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_INT, x, Integer.toString(x));
        } else {
            setSlot(parameterIndex, TAG_INT, x);
        }
        ps.setInt(parameterIndex, x);
    }
    @Override public void setLong(int parameterIndex, long x) throws SQLException {
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_LONG, x, Long.toString(x));
        } else {
            setSlot(parameterIndex, TAG_LONG, x);
        }
        ps.setLong(parameterIndex, x);
    }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException {
        long hash = Float.floatToIntBits(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_FLOAT, hash, Float.toString(x));
        } else {
            setSlot(parameterIndex, TAG_FLOAT, hash);
        }
        ps.setFloat(parameterIndex, x);
    }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException {
        long hash = Double.doubleToLongBits(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_DOUBLE, hash, Double.toString(x));
        } else {
            setSlot(parameterIndex, TAG_DOUBLE, hash);
        }
        ps.setDouble(parameterIndex, x);
    }
    @Override public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        long hash = Objects.hashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_BIGDECIMAL, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_BIGDECIMAL, hash);
        }
        ps.setBigDecimal(parameterIndex, x);
    }
    @Override public void setString(int parameterIndex, String x) throws SQLException {
        long hash = Objects.hashCode(x);
        if (captureValuesActive()) {
            // String.valueOf(x) returns x itself when x != null and the
            // four-character literal "null" otherwise — no allocation
            // in the common path. Still cheaper to gate, since the
            // hashCode lookup is also wasted when capture is off.
            setSlotAndDisplay(parameterIndex, TAG_STRING, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_STRING, hash);
        }
        ps.setString(parameterIndex, x);
    }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        long hash = x == null ? 0L : Arrays.hashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_BYTES, hash, bytesDisplay(x));
        } else {
            setSlot(parameterIndex, TAG_BYTES, hash);
        }
        ps.setBytes(parameterIndex, x);
    }
    @Override public void setDate(int parameterIndex, Date x) throws SQLException {
        long hash = x == null ? 0L : x.getTime();
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_DATE, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_DATE, hash);
        }
        ps.setDate(parameterIndex, x);
    }
    @Override public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        long hash = x == null ? 0L : x.getTime();
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_DATE, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_DATE, hash);
        }
        ps.setDate(parameterIndex, x, cal);
    }
    @Override public void setTime(int parameterIndex, Time x) throws SQLException {
        long hash = x == null ? 0L : x.getTime();
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_TIME, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_TIME, hash);
        }
        ps.setTime(parameterIndex, x);
    }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        long hash = x == null ? 0L : x.getTime();
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_TIME, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_TIME, hash);
        }
        ps.setTime(parameterIndex, x, cal);
    }
    @Override public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        long hash = x == null ? 0L : x.getTime();
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_TIMESTAMP, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_TIMESTAMP, hash);
        }
        ps.setTimestamp(parameterIndex, x);
    }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        long hash = x == null ? 0L : x.getTime();
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_TIMESTAMP, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_TIMESTAMP, hash);
        }
        ps.setTimestamp(parameterIndex, x, cal);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        long hash = System.identityHashCode(x) ^ (long) length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_STREAM, hash, "<InputStream>");
        } else {
            setSlot(parameterIndex, TAG_STREAM, hash);
        }
        ps.setAsciiStream(parameterIndex, x, length);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        long hash = System.identityHashCode(x) ^ length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_STREAM, hash, "<InputStream>");
        } else {
            setSlot(parameterIndex, TAG_STREAM, hash);
        }
        ps.setAsciiStream(parameterIndex, x, length);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        long hash = System.identityHashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_STREAM, hash, "<InputStream>");
        } else {
            setSlot(parameterIndex, TAG_STREAM, hash);
        }
        ps.setAsciiStream(parameterIndex, x);
    }
    @Override @SuppressWarnings("deprecation")
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        long hash = System.identityHashCode(x) ^ (long) length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_STREAM, hash, "<InputStream>");
        } else {
            setSlot(parameterIndex, TAG_STREAM, hash);
        }
        ps.setUnicodeStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        long hash = System.identityHashCode(x) ^ (long) length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_STREAM, hash, "<InputStream>");
        } else {
            setSlot(parameterIndex, TAG_STREAM, hash);
        }
        ps.setBinaryStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        long hash = System.identityHashCode(x) ^ length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_STREAM, hash, "<InputStream>");
        } else {
            setSlot(parameterIndex, TAG_STREAM, hash);
        }
        ps.setBinaryStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        long hash = System.identityHashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_STREAM, hash, "<InputStream>");
        } else {
            setSlot(parameterIndex, TAG_STREAM, hash);
        }
        ps.setBinaryStream(parameterIndex, x);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        long hash = System.identityHashCode(reader) ^ (long) length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_READER, hash, "<Reader>");
        } else {
            setSlot(parameterIndex, TAG_READER, hash);
        }
        ps.setCharacterStream(parameterIndex, reader, length);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        long hash = System.identityHashCode(reader) ^ length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_READER, hash, "<Reader>");
        } else {
            setSlot(parameterIndex, TAG_READER, hash);
        }
        ps.setCharacterStream(parameterIndex, reader, length);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        long hash = System.identityHashCode(reader);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_READER, hash, "<Reader>");
        } else {
            setSlot(parameterIndex, TAG_READER, hash);
        }
        ps.setCharacterStream(parameterIndex, reader);
    }
    @Override public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        long hash = System.identityHashCode(value) ^ length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_READER, hash, "<Reader>");
        } else {
            setSlot(parameterIndex, TAG_READER, hash);
        }
        ps.setNCharacterStream(parameterIndex, value, length);
    }
    @Override public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        long hash = System.identityHashCode(value);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_READER, hash, "<Reader>");
        } else {
            setSlot(parameterIndex, TAG_READER, hash);
        }
        ps.setNCharacterStream(parameterIndex, value);
    }
    @Override public void clearParameters() throws SQLException {
        if (paramHashes != null) {
            Arrays.fill(paramHashes, 0L);
        }
        if (paramValues != null) {
            Arrays.fill(paramValues, null);
        }
        maxIndex = 0;
        ps.clearParameters();
    }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        long hash = Objects.hashCode(x) ^ (long) targetSqlType;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_OBJECT, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_OBJECT, hash);
        }
        ps.setObject(parameterIndex, x, targetSqlType);
    }
    @Override public void setObject(int parameterIndex, Object x) throws SQLException {
        long hash = Objects.hashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_OBJECT, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_OBJECT, hash);
        }
        ps.setObject(parameterIndex, x);
    }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        long hash = Objects.hashCode(x) ^ ((long) targetSqlType << 32) ^ scaleOrLength;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_OBJECT, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_OBJECT, hash);
        }
        ps.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        long hash = Objects.hashCode(x) ^ Objects.hashCode(targetSqlType) ^ scaleOrLength;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_OBJECT, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_OBJECT, hash);
        }
        ps.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
        long hash = Objects.hashCode(x) ^ Objects.hashCode(targetSqlType);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_OBJECT, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_OBJECT, hash);
        }
        ps.setObject(parameterIndex, x, targetSqlType);
    }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException {
        long hash = System.identityHashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_REF, hash, "<Ref>");
        } else {
            setSlot(parameterIndex, TAG_REF, hash);
        }
        ps.setRef(parameterIndex, x);
    }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException {
        long hash = System.identityHashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_BLOB, hash, "<Blob>");
        } else {
            setSlot(parameterIndex, TAG_BLOB, hash);
        }
        ps.setBlob(parameterIndex, x);
    }
    @Override public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        long hash = System.identityHashCode(inputStream) ^ length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_BLOB, hash, "<Blob>");
        } else {
            setSlot(parameterIndex, TAG_BLOB, hash);
        }
        ps.setBlob(parameterIndex, inputStream, length);
    }
    @Override public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        long hash = System.identityHashCode(inputStream);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_BLOB, hash, "<Blob>");
        } else {
            setSlot(parameterIndex, TAG_BLOB, hash);
        }
        ps.setBlob(parameterIndex, inputStream);
    }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException {
        long hash = System.identityHashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_CLOB, hash, "<Clob>");
        } else {
            setSlot(parameterIndex, TAG_CLOB, hash);
        }
        ps.setClob(parameterIndex, x);
    }
    @Override public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        long hash = System.identityHashCode(reader) ^ length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_CLOB, hash, "<Clob>");
        } else {
            setSlot(parameterIndex, TAG_CLOB, hash);
        }
        ps.setClob(parameterIndex, reader, length);
    }
    @Override public void setClob(int parameterIndex, Reader reader) throws SQLException {
        long hash = System.identityHashCode(reader);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_CLOB, hash, "<Clob>");
        } else {
            setSlot(parameterIndex, TAG_CLOB, hash);
        }
        ps.setClob(parameterIndex, reader);
    }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException {
        long hash = System.identityHashCode(value);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_NCLOB, hash, "<NClob>");
        } else {
            setSlot(parameterIndex, TAG_NCLOB, hash);
        }
        ps.setNClob(parameterIndex, value);
    }
    @Override public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        long hash = System.identityHashCode(reader) ^ length;
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_NCLOB, hash, "<NClob>");
        } else {
            setSlot(parameterIndex, TAG_NCLOB, hash);
        }
        ps.setNClob(parameterIndex, reader, length);
    }
    @Override public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        long hash = System.identityHashCode(reader);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_NCLOB, hash, "<NClob>");
        } else {
            setSlot(parameterIndex, TAG_NCLOB, hash);
        }
        ps.setNClob(parameterIndex, reader);
    }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException {
        long hash = System.identityHashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_ARRAY, hash, "<Array>");
        } else {
            setSlot(parameterIndex, TAG_ARRAY, hash);
        }
        ps.setArray(parameterIndex, x);
    }
    @Override public void setURL(int parameterIndex, URL x) throws SQLException {
        long hash = Objects.hashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_URL, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_URL, hash);
        }
        ps.setURL(parameterIndex, x);
    }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException {
        long hash = Objects.hashCode(x);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_ROWID, hash, String.valueOf(x));
        } else {
            setSlot(parameterIndex, TAG_ROWID, hash);
        }
        ps.setRowId(parameterIndex, x);
    }
    @Override public void setNString(int parameterIndex, String value) throws SQLException {
        long hash = Objects.hashCode(value);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_NSTRING, hash, String.valueOf(value));
        } else {
            setSlot(parameterIndex, TAG_NSTRING, hash);
        }
        ps.setNString(parameterIndex, value);
    }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        long hash = System.identityHashCode(xmlObject);
        if (captureValuesActive()) {
            setSlotAndDisplay(parameterIndex, TAG_SQLXML, hash, "<SQLXML>");
        } else {
            setSlot(parameterIndex, TAG_SQLXML, hash);
        }
        ps.setSQLXML(parameterIndex, xmlObject);
    }

    @Override public ResultSetMetaData getMetaData() throws SQLException { return ps.getMetaData(); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { return ps.getParameterMetaData(); }
}
