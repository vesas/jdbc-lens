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
import java.util.Calendar;

/**
 * Wraps a real {@link PreparedStatement}. The SQL is pre-interned at
 * prepare time and carried as {@code sqlId} on every execution so the
 * hot path does no string work (spec §5.5). Inherits
 * {@link CapturingStatement} so the ad-hoc {@code execute(String)}
 * overloads, {@code close}, warning/fetch settings, and the
 * {@link java.sql.Statement} contract all come through unchanged.
 */
final class CapturingPreparedStatement extends CapturingStatement implements PreparedStatement {

    private static final byte EXECUTE_QUERY = (byte) EventType.EXECUTE_QUERY.ordinal();
    private static final byte EXECUTE_UPDATE = (byte) EventType.EXECUTE_UPDATE.ordinal();
    private static final byte EXECUTE_BATCH = (byte) EventType.EXECUTE_BATCH.ordinal();

    private final PreparedStatement ps;
    private final int sqlId;

    CapturingPreparedStatement(PreparedStatement delegate, CaptureContext ctx, int sqlId) {
        super(delegate, ctx);
        this.ps = delegate;
        this.sqlId = sqlId;
        this.lastSqlId = sqlId;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        long t0 = System.nanoTime();
        ResultSet rs = ps.executeQuery();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_QUERY, sqlId, t0, t1 - t0, -1, 0);
        return wrapResultSet(rs);
    }

    @Override
    public int executeUpdate() throws SQLException {
        long t0 = System.nanoTime();
        int rows = ps.executeUpdate();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_UPDATE, sqlId, t0, t1 - t0, rows, 0);
        return rows;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        long t0 = System.nanoTime();
        long rows = ps.executeLargeUpdate();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_UPDATE, sqlId, t0, t1 - t0, clampRows(rows), 0);
        return rows;
    }

    @Override
    public boolean execute() throws SQLException {
        long t0 = System.nanoTime();
        boolean hasResultSet = ps.execute();
        long t1 = System.nanoTime();
        emitExecuteUnknown(sqlId, t0, t1, hasResultSet);
        return hasResultSet;
    }

    @Override
    public void addBatch() throws SQLException {
        ps.addBatch();
        batchSize++;
    }

    @Override
    public int[] executeBatch() throws SQLException {
        int size = batchSize;
        long t0 = System.nanoTime();
        int[] counts = ps.executeBatch();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_BATCH, sqlId, t0, t1 - t0, -1, size);
        batchSize = 0;
        return counts;
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        int size = batchSize;
        long t0 = System.nanoTime();
        long[] counts = ps.executeLargeBatch();
        long t1 = System.nanoTime();
        ctx.emit(EXECUTE_BATCH, sqlId, t0, t1 - t0, -1, size);
        batchSize = 0;
        return counts;
    }

    // --- parameter setters: pure delegation ---

    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException { ps.setNull(parameterIndex, sqlType); }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException { ps.setNull(parameterIndex, sqlType, typeName); }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException { ps.setBoolean(parameterIndex, x); }
    @Override public void setByte(int parameterIndex, byte x) throws SQLException { ps.setByte(parameterIndex, x); }
    @Override public void setShort(int parameterIndex, short x) throws SQLException { ps.setShort(parameterIndex, x); }
    @Override public void setInt(int parameterIndex, int x) throws SQLException { ps.setInt(parameterIndex, x); }
    @Override public void setLong(int parameterIndex, long x) throws SQLException { ps.setLong(parameterIndex, x); }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException { ps.setFloat(parameterIndex, x); }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException { ps.setDouble(parameterIndex, x); }
    @Override public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException { ps.setBigDecimal(parameterIndex, x); }
    @Override public void setString(int parameterIndex, String x) throws SQLException { ps.setString(parameterIndex, x); }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException { ps.setBytes(parameterIndex, x); }
    @Override public void setDate(int parameterIndex, Date x) throws SQLException { ps.setDate(parameterIndex, x); }
    @Override public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException { ps.setDate(parameterIndex, x, cal); }
    @Override public void setTime(int parameterIndex, Time x) throws SQLException { ps.setTime(parameterIndex, x); }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException { ps.setTime(parameterIndex, x, cal); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException { ps.setTimestamp(parameterIndex, x); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException { ps.setTimestamp(parameterIndex, x, cal); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException { ps.setAsciiStream(parameterIndex, x, length); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException { ps.setAsciiStream(parameterIndex, x, length); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException { ps.setAsciiStream(parameterIndex, x); }
    @Override @SuppressWarnings("deprecation")
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException { ps.setUnicodeStream(parameterIndex, x, length); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException { ps.setBinaryStream(parameterIndex, x, length); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException { ps.setBinaryStream(parameterIndex, x, length); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException { ps.setBinaryStream(parameterIndex, x); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException { ps.setCharacterStream(parameterIndex, reader, length); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException { ps.setCharacterStream(parameterIndex, reader, length); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException { ps.setCharacterStream(parameterIndex, reader); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException { ps.setNCharacterStream(parameterIndex, value, length); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException { ps.setNCharacterStream(parameterIndex, value); }
    @Override public void clearParameters() throws SQLException { ps.clearParameters(); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException { ps.setObject(parameterIndex, x, targetSqlType); }
    @Override public void setObject(int parameterIndex, Object x) throws SQLException { ps.setObject(parameterIndex, x); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException { ps.setObject(parameterIndex, x, targetSqlType, scaleOrLength); }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException { ps.setObject(parameterIndex, x, targetSqlType, scaleOrLength); }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException { ps.setObject(parameterIndex, x, targetSqlType); }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException { ps.setRef(parameterIndex, x); }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException { ps.setBlob(parameterIndex, x); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException { ps.setBlob(parameterIndex, inputStream, length); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException { ps.setBlob(parameterIndex, inputStream); }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException { ps.setClob(parameterIndex, x); }
    @Override public void setClob(int parameterIndex, Reader reader, long length) throws SQLException { ps.setClob(parameterIndex, reader, length); }
    @Override public void setClob(int parameterIndex, Reader reader) throws SQLException { ps.setClob(parameterIndex, reader); }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException { ps.setNClob(parameterIndex, value); }
    @Override public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException { ps.setNClob(parameterIndex, reader, length); }
    @Override public void setNClob(int parameterIndex, Reader reader) throws SQLException { ps.setNClob(parameterIndex, reader); }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException { ps.setArray(parameterIndex, x); }
    @Override public void setURL(int parameterIndex, URL x) throws SQLException { ps.setURL(parameterIndex, x); }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException { ps.setRowId(parameterIndex, x); }
    @Override public void setNString(int parameterIndex, String value) throws SQLException { ps.setNString(parameterIndex, value); }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException { ps.setSQLXML(parameterIndex, xmlObject); }

    @Override public ResultSetMetaData getMetaData() throws SQLException { return ps.getMetaData(); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { return ps.getParameterMetaData(); }
}
