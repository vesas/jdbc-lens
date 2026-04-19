package io.github.vesas.jdbcprof.capture;

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
import java.util.Calendar;
import java.util.Objects;

/**
 * Wraps a real {@link PreparedStatement}. The SQL is pre-interned at
 * prepare time and carried as {@code sqlId} on every execution so the
 * hot path does no string work (spec §5.5). Inherits
 * {@link CapturingStatement} so the ad-hoc {@code execute(String)}
 * overloads, {@code close}, warning/fetch settings, and the
 * {@link java.sql.Statement} contract all come through unchanged.
 *
 * <p>Each {@code setXxx} update keeps a per-index 64-bit hash of the
 * current binding. On execute those slots are folded into a single
 * {@code parameterFingerprint} attached to the event — the analysis
 * layer uses this to distinguish "50 calls, 50 different params"
 * (N+1) from "50 calls, same params" (redundant query).
 */
final class CapturingPreparedStatement extends CapturingStatement implements PreparedStatement {

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
    private final int sqlId;

    private long[] paramHashes;

    CapturingPreparedStatement(PreparedStatement delegate, CaptureContext ctx, int sqlId) {
        super(delegate, ctx);
        this.ps = delegate;
        this.sqlId = sqlId;
        this.lastSqlId = sqlId;
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
        long fp = fingerprint();
        long t0 = System.nanoTime();
        ResultSet rs = ps.executeQuery();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_QUERY, sqlId, t0, t1 - t0, -1, 0, fp);
        return wrapResultSet(rs);
    }

    @Override
    public int executeUpdate() throws SQLException {
        long fp = fingerprint();
        long t0 = System.nanoTime();
        int rows = ps.executeUpdate();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_UPDATE, sqlId, t0, t1 - t0, rows, 0, fp);
        return rows;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        long fp = fingerprint();
        long t0 = System.nanoTime();
        long rows = ps.executeLargeUpdate();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_UPDATE, sqlId, t0, t1 - t0, clampRows(rows), 0, fp);
        return rows;
    }

    @Override
    public boolean execute() throws SQLException {
        long fp = fingerprint();
        long t0 = System.nanoTime();
        boolean hasResultSet = ps.execute();
        long t1 = System.nanoTime();
        emitExecuteUnknown(sqlId, t0, t1, hasResultSet, fp);
        return hasResultSet;
    }

    @Override
    public void addBatch() throws SQLException {
        ps.addBatch();
        batchSize++;
    }

    @Override
    public int[] executeBatch() throws SQLException {
        // A batch's fingerprint is ill-defined — every bound row in the
        // batch has its own parameters and addBatch() doesn't surface
        // them to us. Pass 0L; the analysis layer ignores fingerprint
        // for EXECUTE_BATCH.
        int size = batchSize;
        long t0 = System.nanoTime();
        int[] counts = ps.executeBatch();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_BATCH, sqlId, t0, t1 - t0, -1, size, 0L);
        batchSize = 0;
        return counts;
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        int size = batchSize;
        long t0 = System.nanoTime();
        long[] counts = ps.executeLargeBatch();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_BATCH, sqlId, t0, t1 - t0, -1, size, 0L);
        batchSize = 0;
        return counts;
    }

    // --- parameter setters: capture fingerprint + delegate ---

    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException {
        setSlot(parameterIndex, TAG_NULL, sqlType);
        ps.setNull(parameterIndex, sqlType);
    }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setSlot(parameterIndex, TAG_NULL, sqlType ^ (typeName == null ? 0L : typeName.hashCode()));
        ps.setNull(parameterIndex, sqlType, typeName);
    }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        setSlot(parameterIndex, TAG_BOOLEAN, x ? 1L : 0L);
        ps.setBoolean(parameterIndex, x);
    }
    @Override public void setByte(int parameterIndex, byte x) throws SQLException {
        setSlot(parameterIndex, TAG_BYTE, x);
        ps.setByte(parameterIndex, x);
    }
    @Override public void setShort(int parameterIndex, short x) throws SQLException {
        setSlot(parameterIndex, TAG_SHORT, x);
        ps.setShort(parameterIndex, x);
    }
    @Override public void setInt(int parameterIndex, int x) throws SQLException {
        setSlot(parameterIndex, TAG_INT, x);
        ps.setInt(parameterIndex, x);
    }
    @Override public void setLong(int parameterIndex, long x) throws SQLException {
        setSlot(parameterIndex, TAG_LONG, x);
        ps.setLong(parameterIndex, x);
    }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException {
        setSlot(parameterIndex, TAG_FLOAT, Float.floatToIntBits(x));
        ps.setFloat(parameterIndex, x);
    }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException {
        setSlot(parameterIndex, TAG_DOUBLE, Double.doubleToLongBits(x));
        ps.setDouble(parameterIndex, x);
    }
    @Override public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        setSlot(parameterIndex, TAG_BIGDECIMAL, Objects.hashCode(x));
        ps.setBigDecimal(parameterIndex, x);
    }
    @Override public void setString(int parameterIndex, String x) throws SQLException {
        setSlot(parameterIndex, TAG_STRING, Objects.hashCode(x));
        ps.setString(parameterIndex, x);
    }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        setSlot(parameterIndex, TAG_BYTES, x == null ? 0L : Arrays.hashCode(x));
        ps.setBytes(parameterIndex, x);
    }
    @Override public void setDate(int parameterIndex, Date x) throws SQLException {
        setSlot(parameterIndex, TAG_DATE, x == null ? 0L : x.getTime());
        ps.setDate(parameterIndex, x);
    }
    @Override public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        setSlot(parameterIndex, TAG_DATE, x == null ? 0L : x.getTime());
        ps.setDate(parameterIndex, x, cal);
    }
    @Override public void setTime(int parameterIndex, Time x) throws SQLException {
        setSlot(parameterIndex, TAG_TIME, x == null ? 0L : x.getTime());
        ps.setTime(parameterIndex, x);
    }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        setSlot(parameterIndex, TAG_TIME, x == null ? 0L : x.getTime());
        ps.setTime(parameterIndex, x, cal);
    }
    @Override public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setSlot(parameterIndex, TAG_TIMESTAMP, x == null ? 0L : x.getTime());
        ps.setTimestamp(parameterIndex, x);
    }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        setSlot(parameterIndex, TAG_TIMESTAMP, x == null ? 0L : x.getTime());
        ps.setTimestamp(parameterIndex, x, cal);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setSlot(parameterIndex, TAG_STREAM, System.identityHashCode(x) ^ (long) length);
        ps.setAsciiStream(parameterIndex, x, length);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setSlot(parameterIndex, TAG_STREAM, System.identityHashCode(x) ^ length);
        ps.setAsciiStream(parameterIndex, x, length);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        setSlot(parameterIndex, TAG_STREAM, System.identityHashCode(x));
        ps.setAsciiStream(parameterIndex, x);
    }
    @Override @SuppressWarnings("deprecation")
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setSlot(parameterIndex, TAG_STREAM, System.identityHashCode(x) ^ (long) length);
        ps.setUnicodeStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setSlot(parameterIndex, TAG_STREAM, System.identityHashCode(x) ^ (long) length);
        ps.setBinaryStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setSlot(parameterIndex, TAG_STREAM, System.identityHashCode(x) ^ length);
        ps.setBinaryStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        setSlot(parameterIndex, TAG_STREAM, System.identityHashCode(x));
        ps.setBinaryStream(parameterIndex, x);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        setSlot(parameterIndex, TAG_READER, System.identityHashCode(reader) ^ (long) length);
        ps.setCharacterStream(parameterIndex, reader, length);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        setSlot(parameterIndex, TAG_READER, System.identityHashCode(reader) ^ length);
        ps.setCharacterStream(parameterIndex, reader, length);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setSlot(parameterIndex, TAG_READER, System.identityHashCode(reader));
        ps.setCharacterStream(parameterIndex, reader);
    }
    @Override public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        setSlot(parameterIndex, TAG_READER, System.identityHashCode(value) ^ length);
        ps.setNCharacterStream(parameterIndex, value, length);
    }
    @Override public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        setSlot(parameterIndex, TAG_READER, System.identityHashCode(value));
        ps.setNCharacterStream(parameterIndex, value);
    }
    @Override public void clearParameters() throws SQLException {
        if (paramHashes != null) {
            Arrays.fill(paramHashes, 0L);
        }
        ps.clearParameters();
    }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setSlot(parameterIndex, TAG_OBJECT, Objects.hashCode(x) ^ (long) targetSqlType);
        ps.setObject(parameterIndex, x, targetSqlType);
    }
    @Override public void setObject(int parameterIndex, Object x) throws SQLException {
        setSlot(parameterIndex, TAG_OBJECT, Objects.hashCode(x));
        ps.setObject(parameterIndex, x);
    }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setSlot(parameterIndex, TAG_OBJECT,
                Objects.hashCode(x) ^ ((long) targetSqlType << 32) ^ scaleOrLength);
        ps.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        setSlot(parameterIndex, TAG_OBJECT,
                Objects.hashCode(x) ^ Objects.hashCode(targetSqlType) ^ scaleOrLength);
        ps.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
        setSlot(parameterIndex, TAG_OBJECT, Objects.hashCode(x) ^ Objects.hashCode(targetSqlType));
        ps.setObject(parameterIndex, x, targetSqlType);
    }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException {
        setSlot(parameterIndex, TAG_REF, System.identityHashCode(x));
        ps.setRef(parameterIndex, x);
    }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException {
        setSlot(parameterIndex, TAG_BLOB, System.identityHashCode(x));
        ps.setBlob(parameterIndex, x);
    }
    @Override public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        setSlot(parameterIndex, TAG_BLOB, System.identityHashCode(inputStream) ^ length);
        ps.setBlob(parameterIndex, inputStream, length);
    }
    @Override public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        setSlot(parameterIndex, TAG_BLOB, System.identityHashCode(inputStream));
        ps.setBlob(parameterIndex, inputStream);
    }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException {
        setSlot(parameterIndex, TAG_CLOB, System.identityHashCode(x));
        ps.setClob(parameterIndex, x);
    }
    @Override public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setSlot(parameterIndex, TAG_CLOB, System.identityHashCode(reader) ^ length);
        ps.setClob(parameterIndex, reader, length);
    }
    @Override public void setClob(int parameterIndex, Reader reader) throws SQLException {
        setSlot(parameterIndex, TAG_CLOB, System.identityHashCode(reader));
        ps.setClob(parameterIndex, reader);
    }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException {
        setSlot(parameterIndex, TAG_NCLOB, System.identityHashCode(value));
        ps.setNClob(parameterIndex, value);
    }
    @Override public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setSlot(parameterIndex, TAG_NCLOB, System.identityHashCode(reader) ^ length);
        ps.setNClob(parameterIndex, reader, length);
    }
    @Override public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        setSlot(parameterIndex, TAG_NCLOB, System.identityHashCode(reader));
        ps.setNClob(parameterIndex, reader);
    }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException {
        setSlot(parameterIndex, TAG_ARRAY, System.identityHashCode(x));
        ps.setArray(parameterIndex, x);
    }
    @Override public void setURL(int parameterIndex, URL x) throws SQLException {
        setSlot(parameterIndex, TAG_URL, Objects.hashCode(x));
        ps.setURL(parameterIndex, x);
    }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException {
        setSlot(parameterIndex, TAG_ROWID, Objects.hashCode(x));
        ps.setRowId(parameterIndex, x);
    }
    @Override public void setNString(int parameterIndex, String value) throws SQLException {
        setSlot(parameterIndex, TAG_NSTRING, Objects.hashCode(value));
        ps.setNString(parameterIndex, value);
    }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        setSlot(parameterIndex, TAG_SQLXML, System.identityHashCode(xmlObject));
        ps.setSQLXML(parameterIndex, xmlObject);
    }

    @Override public ResultSetMetaData getMetaData() throws SQLException { return ps.getMetaData(); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { return ps.getParameterMetaData(); }
}
