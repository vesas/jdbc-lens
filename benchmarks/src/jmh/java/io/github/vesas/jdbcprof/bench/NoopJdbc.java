package io.github.vesas.jdbcprof.bench;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Minimal no-op JDBC stack used as the subject for benchmarking spec §12.
 * Only the methods the capture path exercises are implemented; everything
 * else throws. Keeping the noop implementations in the benchmark module
 * means a wrapped call measures profiler overhead plus a constant few-ns
 * delegate dispatch, and an unwrapped call measures only the delegate
 * dispatch — the difference isolates profiler overhead per spec §12.
 */
final class NoopJdbc {

    private NoopJdbc() {
    }

    static DataSource dataSource() {
        return new NoopDataSource();
    }

    private static UnsupportedOperationException uoe() {
        return new UnsupportedOperationException("benchmark noop");
    }

    static final class NoopDataSource implements DataSource {
        @Override public Connection getConnection() { return new NoopConnection(); }
        @Override public Connection getConnection(String u, String p) { return new NoopConnection(); }
        @Override public PrintWriter getLogWriter() { throw uoe(); }
        @Override public void setLogWriter(PrintWriter out) { throw uoe(); }
        @Override public void setLoginTimeout(int seconds) { throw uoe(); }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { throw uoe(); }
        @Override public <T> T unwrap(Class<T> iface) { throw uoe(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static final class NoopConnection implements Connection {
        @Override public Statement createStatement() { return new NoopStatement(); }
        @Override public Statement createStatement(int a, int b) { return new NoopStatement(); }
        @Override public Statement createStatement(int a, int b, int c) { return new NoopStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) { return new NoopPreparedStatement(); }
        @Override public PreparedStatement prepareStatement(String sql, int a) { return new NoopPreparedStatement(); }
        @Override public PreparedStatement prepareStatement(String sql, int[] a) { return new NoopPreparedStatement(); }
        @Override public PreparedStatement prepareStatement(String sql, String[] a) { return new NoopPreparedStatement(); }
        @Override public PreparedStatement prepareStatement(String sql, int a, int b) { return new NoopPreparedStatement(); }
        @Override public PreparedStatement prepareStatement(String sql, int a, int b, int c) { return new NoopPreparedStatement(); }
        @Override public CallableStatement prepareCall(String sql) { throw uoe(); }
        @Override public CallableStatement prepareCall(String sql, int a, int b) { throw uoe(); }
        @Override public CallableStatement prepareCall(String sql, int a, int b, int c) { throw uoe(); }
        @Override public void commit() { /* no-op */ }
        @Override public void rollback() { /* no-op */ }
        @Override public void rollback(Savepoint s) { /* no-op */ }
        @Override public void close() { /* no-op */ }
        @Override public String nativeSQL(String sql) { return sql; }
        @Override public void setAutoCommit(boolean b) { /* no-op */ }
        @Override public boolean getAutoCommit() { return true; }
        @Override public boolean isClosed() { return false; }
        @Override public DatabaseMetaData getMetaData() { throw uoe(); }
        @Override public void setReadOnly(boolean b) { /* no-op */ }
        @Override public boolean isReadOnly() { return false; }
        @Override public void setCatalog(String s) { /* no-op */ }
        @Override public String getCatalog() { return null; }
        @Override public void setTransactionIsolation(int i) { /* no-op */ }
        @Override public int getTransactionIsolation() { return 0; }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { /* no-op */ }
        @Override public Map<String, Class<?>> getTypeMap() { throw uoe(); }
        @Override public void setTypeMap(Map<String, Class<?>> m) { /* no-op */ }
        @Override public void setHoldability(int i) { /* no-op */ }
        @Override public int getHoldability() { return 0; }
        @Override public Savepoint setSavepoint() { throw uoe(); }
        @Override public Savepoint setSavepoint(String n) { throw uoe(); }
        @Override public void releaseSavepoint(Savepoint s) { /* no-op */ }
        @Override public Clob createClob() { throw uoe(); }
        @Override public Blob createBlob() { throw uoe(); }
        @Override public NClob createNClob() { throw uoe(); }
        @Override public SQLXML createSQLXML() { throw uoe(); }
        @Override public boolean isValid(int t) { return true; }
        @Override public void setClientInfo(String n, String v) throws SQLClientInfoException { /* no-op */ }
        @Override public void setClientInfo(Properties p) throws SQLClientInfoException { /* no-op */ }
        @Override public String getClientInfo(String n) { return null; }
        @Override public Properties getClientInfo() { return new Properties(); }
        @Override public Array createArrayOf(String t, Object[] e) { throw uoe(); }
        @Override public Struct createStruct(String t, Object[] a) { throw uoe(); }
        @Override public void setSchema(String s) { /* no-op */ }
        @Override public String getSchema() { return null; }
        @Override public void abort(Executor e) { /* no-op */ }
        @Override public void setNetworkTimeout(Executor e, int ms) { /* no-op */ }
        @Override public int getNetworkTimeout() { return 0; }
        @Override public <T> T unwrap(Class<T> iface) { throw uoe(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static class NoopStatement implements Statement {
        @Override public ResultSet executeQuery(String sql) { return new NoopResultSet(); }
        @Override public int executeUpdate(String sql) { return 0; }
        @Override public int executeUpdate(String sql, int a) { return 0; }
        @Override public int executeUpdate(String sql, int[] a) { return 0; }
        @Override public int executeUpdate(String sql, String[] a) { return 0; }
        @Override public boolean execute(String sql) { return false; }
        @Override public boolean execute(String sql, int a) { return false; }
        @Override public boolean execute(String sql, int[] a) { return false; }
        @Override public boolean execute(String sql, String[] a) { return false; }
        @Override public void close() { /* no-op */ }
        @Override public int getMaxFieldSize() { return 0; }
        @Override public void setMaxFieldSize(int m) { /* no-op */ }
        @Override public int getMaxRows() { return 0; }
        @Override public void setMaxRows(int m) { /* no-op */ }
        @Override public void setEscapeProcessing(boolean b) { /* no-op */ }
        @Override public int getQueryTimeout() { return 0; }
        @Override public void setQueryTimeout(int s) { /* no-op */ }
        @Override public void cancel() { /* no-op */ }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { /* no-op */ }
        @Override public void setCursorName(String n) { /* no-op */ }
        @Override public ResultSet getResultSet() { return new NoopResultSet(); }
        @Override public int getUpdateCount() { return -1; }
        @Override public boolean getMoreResults() { return false; }
        @Override public boolean getMoreResults(int c) { return false; }
        @Override public void setFetchDirection(int d) { /* no-op */ }
        @Override public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
        @Override public void setFetchSize(int r) { /* no-op */ }
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
        @Override public int getResultSetType() { return ResultSet.TYPE_FORWARD_ONLY; }
        @Override public void addBatch(String sql) { /* no-op */ }
        @Override public void clearBatch() { /* no-op */ }
        @Override public int[] executeBatch() { return new int[0]; }
        @Override public Connection getConnection() { throw uoe(); }
        @Override public ResultSet getGeneratedKeys() { return new NoopResultSet(); }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void setPoolable(boolean p) { /* no-op */ }
        @Override public boolean isPoolable() { return false; }
        @Override public void closeOnCompletion() { /* no-op */ }
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public <T> T unwrap(Class<T> iface) { throw uoe(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static final class NoopPreparedStatement extends NoopStatement implements PreparedStatement {
        @Override public ResultSet executeQuery() { return new NoopResultSet(); }
        @Override public int executeUpdate() { return 0; }
        @Override public boolean execute() { return false; }
        @Override public void addBatch() { /* no-op */ }
        @Override public void clearParameters() { /* no-op */ }
        @Override public ResultSetMetaData getMetaData() { throw uoe(); }
        @Override public ParameterMetaData getParameterMetaData() { throw uoe(); }
        @Override public void setNull(int i, int t) { /* no-op */ }
        @Override public void setNull(int i, int t, String n) { /* no-op */ }
        @Override public void setBoolean(int i, boolean x) { /* no-op */ }
        @Override public void setByte(int i, byte x) { /* no-op */ }
        @Override public void setShort(int i, short x) { /* no-op */ }
        @Override public void setInt(int i, int x) { /* no-op */ }
        @Override public void setLong(int i, long x) { /* no-op */ }
        @Override public void setFloat(int i, float x) { /* no-op */ }
        @Override public void setDouble(int i, double x) { /* no-op */ }
        @Override public void setBigDecimal(int i, BigDecimal x) { /* no-op */ }
        @Override public void setString(int i, String x) { /* no-op */ }
        @Override public void setBytes(int i, byte[] x) { /* no-op */ }
        @Override public void setDate(int i, Date x) { /* no-op */ }
        @Override public void setDate(int i, Date x, Calendar c) { /* no-op */ }
        @Override public void setTime(int i, Time x) { /* no-op */ }
        @Override public void setTime(int i, Time x, Calendar c) { /* no-op */ }
        @Override public void setTimestamp(int i, Timestamp x) { /* no-op */ }
        @Override public void setTimestamp(int i, Timestamp x, Calendar c) { /* no-op */ }
        @Override public void setAsciiStream(int i, InputStream x, int l) { /* no-op */ }
        @Override public void setAsciiStream(int i, InputStream x, long l) { /* no-op */ }
        @Override public void setAsciiStream(int i, InputStream x) { /* no-op */ }
        @Override @SuppressWarnings("deprecation")
        public void setUnicodeStream(int i, InputStream x, int l) { /* no-op */ }
        @Override public void setBinaryStream(int i, InputStream x, int l) { /* no-op */ }
        @Override public void setBinaryStream(int i, InputStream x, long l) { /* no-op */ }
        @Override public void setBinaryStream(int i, InputStream x) { /* no-op */ }
        @Override public void setCharacterStream(int i, Reader r, int l) { /* no-op */ }
        @Override public void setCharacterStream(int i, Reader r, long l) { /* no-op */ }
        @Override public void setCharacterStream(int i, Reader r) { /* no-op */ }
        @Override public void setNCharacterStream(int i, Reader r, long l) { /* no-op */ }
        @Override public void setNCharacterStream(int i, Reader r) { /* no-op */ }
        @Override public void setObject(int i, Object x, int t) { /* no-op */ }
        @Override public void setObject(int i, Object x) { /* no-op */ }
        @Override public void setObject(int i, Object x, int t, int s) { /* no-op */ }
        @Override public void setObject(int i, Object x, SQLType t, int s) { /* no-op */ }
        @Override public void setObject(int i, Object x, SQLType t) { /* no-op */ }
        @Override public void setRef(int i, Ref x) { /* no-op */ }
        @Override public void setBlob(int i, Blob x) { /* no-op */ }
        @Override public void setBlob(int i, InputStream s, long l) { /* no-op */ }
        @Override public void setBlob(int i, InputStream s) { /* no-op */ }
        @Override public void setClob(int i, Clob x) { /* no-op */ }
        @Override public void setClob(int i, Reader r, long l) { /* no-op */ }
        @Override public void setClob(int i, Reader r) { /* no-op */ }
        @Override public void setNClob(int i, NClob x) { /* no-op */ }
        @Override public void setNClob(int i, Reader r, long l) { /* no-op */ }
        @Override public void setNClob(int i, Reader r) { /* no-op */ }
        @Override public void setArray(int i, Array x) { /* no-op */ }
        @Override public void setURL(int i, URL x) { /* no-op */ }
        @Override public void setRowId(int i, RowId x) { /* no-op */ }
        @Override public void setNString(int i, String x) { /* no-op */ }
        @Override public void setSQLXML(int i, SQLXML x) { /* no-op */ }
    }

    static final class NoopResultSet implements ResultSet {
        @Override public boolean next() { return false; }
        @Override public void close() { /* no-op */ }
        @Override public boolean wasNull() { return false; }
        @Override public boolean isClosed() { return false; }
        @Override public Statement getStatement() { return null; }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { /* no-op */ }
        @Override public String getCursorName() { return null; }
        @Override public ResultSetMetaData getMetaData() { throw uoe(); }
        @Override public int findColumn(String columnLabel) { throw uoe(); }
        @Override public boolean isBeforeFirst() { return false; }
        @Override public boolean isAfterLast() { return true; }
        @Override public boolean isFirst() { return false; }
        @Override public boolean isLast() { return false; }
        @Override public void beforeFirst() { /* no-op */ }
        @Override public void afterLast() { /* no-op */ }
        @Override public boolean first() { return false; }
        @Override public boolean last() { return false; }
        @Override public int getRow() { return 0; }
        @Override public boolean absolute(int row) { return false; }
        @Override public boolean relative(int rows) { return false; }
        @Override public boolean previous() { return false; }
        @Override public void setFetchDirection(int d) { /* no-op */ }
        @Override public int getFetchDirection() { return FETCH_FORWARD; }
        @Override public void setFetchSize(int r) { /* no-op */ }
        @Override public int getFetchSize() { return 0; }
        @Override public int getType() { return TYPE_FORWARD_ONLY; }
        @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
        @Override public int getHoldability() { return 0; }
        @Override public boolean rowUpdated() { return false; }
        @Override public boolean rowInserted() { return false; }
        @Override public boolean rowDeleted() { return false; }
        @Override public <T> T unwrap(Class<T> iface) { throw uoe(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }

        // Everything below is unused by the benchmarks; UOE keeps the surface honest.
        @Override public String getString(int c) { throw uoe(); }
        @Override public boolean getBoolean(int c) { throw uoe(); }
        @Override public byte getByte(int c) { throw uoe(); }
        @Override public short getShort(int c) { throw uoe(); }
        @Override public int getInt(int c) { throw uoe(); }
        @Override public long getLong(int c) { throw uoe(); }
        @Override public float getFloat(int c) { throw uoe(); }
        @Override public double getDouble(int c) { throw uoe(); }
        @Override @SuppressWarnings("deprecation")
        public BigDecimal getBigDecimal(int c, int s) { throw uoe(); }
        @Override public byte[] getBytes(int c) { throw uoe(); }
        @Override public Date getDate(int c) { throw uoe(); }
        @Override public Time getTime(int c) { throw uoe(); }
        @Override public Timestamp getTimestamp(int c) { throw uoe(); }
        @Override public InputStream getAsciiStream(int c) { throw uoe(); }
        @Override @SuppressWarnings("deprecation")
        public InputStream getUnicodeStream(int c) { throw uoe(); }
        @Override public InputStream getBinaryStream(int c) { throw uoe(); }
        @Override public String getString(String c) { throw uoe(); }
        @Override public boolean getBoolean(String c) { throw uoe(); }
        @Override public byte getByte(String c) { throw uoe(); }
        @Override public short getShort(String c) { throw uoe(); }
        @Override public int getInt(String c) { throw uoe(); }
        @Override public long getLong(String c) { throw uoe(); }
        @Override public float getFloat(String c) { throw uoe(); }
        @Override public double getDouble(String c) { throw uoe(); }
        @Override @SuppressWarnings("deprecation")
        public BigDecimal getBigDecimal(String c, int s) { throw uoe(); }
        @Override public byte[] getBytes(String c) { throw uoe(); }
        @Override public Date getDate(String c) { throw uoe(); }
        @Override public Time getTime(String c) { throw uoe(); }
        @Override public Timestamp getTimestamp(String c) { throw uoe(); }
        @Override public InputStream getAsciiStream(String c) { throw uoe(); }
        @Override @SuppressWarnings("deprecation")
        public InputStream getUnicodeStream(String c) { throw uoe(); }
        @Override public InputStream getBinaryStream(String c) { throw uoe(); }
        @Override public Object getObject(int c) { throw uoe(); }
        @Override public Object getObject(String c) { throw uoe(); }
        @Override public Reader getCharacterStream(int c) { throw uoe(); }
        @Override public Reader getCharacterStream(String c) { throw uoe(); }
        @Override public BigDecimal getBigDecimal(int c) { throw uoe(); }
        @Override public BigDecimal getBigDecimal(String c) { throw uoe(); }
        @Override public Object getObject(int c, Map<String, Class<?>> m) { throw uoe(); }
        @Override public Ref getRef(int c) { throw uoe(); }
        @Override public Blob getBlob(int c) { throw uoe(); }
        @Override public Clob getClob(int c) { throw uoe(); }
        @Override public Array getArray(int c) { throw uoe(); }
        @Override public Object getObject(String c, Map<String, Class<?>> m) { throw uoe(); }
        @Override public Ref getRef(String c) { throw uoe(); }
        @Override public Blob getBlob(String c) { throw uoe(); }
        @Override public Clob getClob(String c) { throw uoe(); }
        @Override public Array getArray(String c) { throw uoe(); }
        @Override public Date getDate(int c, Calendar cal) { throw uoe(); }
        @Override public Date getDate(String c, Calendar cal) { throw uoe(); }
        @Override public Time getTime(int c, Calendar cal) { throw uoe(); }
        @Override public Time getTime(String c, Calendar cal) { throw uoe(); }
        @Override public Timestamp getTimestamp(int c, Calendar cal) { throw uoe(); }
        @Override public Timestamp getTimestamp(String c, Calendar cal) { throw uoe(); }
        @Override public URL getURL(int c) { throw uoe(); }
        @Override public URL getURL(String c) { throw uoe(); }
        @Override public RowId getRowId(int c) { throw uoe(); }
        @Override public RowId getRowId(String c) { throw uoe(); }
        @Override public NClob getNClob(int c) { throw uoe(); }
        @Override public NClob getNClob(String c) { throw uoe(); }
        @Override public SQLXML getSQLXML(int c) { throw uoe(); }
        @Override public SQLXML getSQLXML(String c) { throw uoe(); }
        @Override public String getNString(int c) { throw uoe(); }
        @Override public String getNString(String c) { throw uoe(); }
        @Override public Reader getNCharacterStream(int c) { throw uoe(); }
        @Override public Reader getNCharacterStream(String c) { throw uoe(); }
        @Override public <T> T getObject(int c, Class<T> type) { throw uoe(); }
        @Override public <T> T getObject(String c, Class<T> type) { throw uoe(); }

        @Override public void insertRow() { /* no-op */ }
        @Override public void updateRow() { /* no-op */ }
        @Override public void deleteRow() { /* no-op */ }
        @Override public void refreshRow() { /* no-op */ }
        @Override public void cancelRowUpdates() { /* no-op */ }
        @Override public void moveToInsertRow() { /* no-op */ }
        @Override public void moveToCurrentRow() { /* no-op */ }
        @Override public void updateNull(int c) { /* no-op */ }
        @Override public void updateBoolean(int c, boolean x) { /* no-op */ }
        @Override public void updateByte(int c, byte x) { /* no-op */ }
        @Override public void updateShort(int c, short x) { /* no-op */ }
        @Override public void updateInt(int c, int x) { /* no-op */ }
        @Override public void updateLong(int c, long x) { /* no-op */ }
        @Override public void updateFloat(int c, float x) { /* no-op */ }
        @Override public void updateDouble(int c, double x) { /* no-op */ }
        @Override public void updateBigDecimal(int c, BigDecimal x) { /* no-op */ }
        @Override public void updateString(int c, String x) { /* no-op */ }
        @Override public void updateBytes(int c, byte[] x) { /* no-op */ }
        @Override public void updateDate(int c, Date x) { /* no-op */ }
        @Override public void updateTime(int c, Time x) { /* no-op */ }
        @Override public void updateTimestamp(int c, Timestamp x) { /* no-op */ }
        @Override public void updateAsciiStream(int c, InputStream x, int l) { /* no-op */ }
        @Override public void updateBinaryStream(int c, InputStream x, int l) { /* no-op */ }
        @Override public void updateCharacterStream(int c, Reader x, int l) { /* no-op */ }
        @Override public void updateObject(int c, Object x, int s) { /* no-op */ }
        @Override public void updateObject(int c, Object x) { /* no-op */ }
        @Override public void updateNull(String c) { /* no-op */ }
        @Override public void updateBoolean(String c, boolean x) { /* no-op */ }
        @Override public void updateByte(String c, byte x) { /* no-op */ }
        @Override public void updateShort(String c, short x) { /* no-op */ }
        @Override public void updateInt(String c, int x) { /* no-op */ }
        @Override public void updateLong(String c, long x) { /* no-op */ }
        @Override public void updateFloat(String c, float x) { /* no-op */ }
        @Override public void updateDouble(String c, double x) { /* no-op */ }
        @Override public void updateBigDecimal(String c, BigDecimal x) { /* no-op */ }
        @Override public void updateString(String c, String x) { /* no-op */ }
        @Override public void updateBytes(String c, byte[] x) { /* no-op */ }
        @Override public void updateDate(String c, Date x) { /* no-op */ }
        @Override public void updateTime(String c, Time x) { /* no-op */ }
        @Override public void updateTimestamp(String c, Timestamp x) { /* no-op */ }
        @Override public void updateAsciiStream(String c, InputStream x, int l) { /* no-op */ }
        @Override public void updateBinaryStream(String c, InputStream x, int l) { /* no-op */ }
        @Override public void updateCharacterStream(String c, Reader x, int l) { /* no-op */ }
        @Override public void updateObject(String c, Object x, int s) { /* no-op */ }
        @Override public void updateObject(String c, Object x) { /* no-op */ }
        @Override public void updateRef(int c, Ref x) { /* no-op */ }
        @Override public void updateRef(String c, Ref x) { /* no-op */ }
        @Override public void updateBlob(int c, Blob x) { /* no-op */ }
        @Override public void updateBlob(String c, Blob x) { /* no-op */ }
        @Override public void updateClob(int c, Clob x) { /* no-op */ }
        @Override public void updateClob(String c, Clob x) { /* no-op */ }
        @Override public void updateArray(int c, Array x) { /* no-op */ }
        @Override public void updateArray(String c, Array x) { /* no-op */ }
        @Override public void updateRowId(int c, RowId x) { /* no-op */ }
        @Override public void updateRowId(String c, RowId x) { /* no-op */ }
        @Override public void updateNString(int c, String x) { /* no-op */ }
        @Override public void updateNString(String c, String x) { /* no-op */ }
        @Override public void updateNClob(int c, NClob x) { /* no-op */ }
        @Override public void updateNClob(String c, NClob x) { /* no-op */ }
        @Override public void updateSQLXML(int c, SQLXML x) { /* no-op */ }
        @Override public void updateSQLXML(String c, SQLXML x) { /* no-op */ }
        @Override public void updateAsciiStream(int c, InputStream x, long l) { /* no-op */ }
        @Override public void updateBinaryStream(int c, InputStream x, long l) { /* no-op */ }
        @Override public void updateCharacterStream(int c, Reader x, long l) { /* no-op */ }
        @Override public void updateAsciiStream(String c, InputStream x, long l) { /* no-op */ }
        @Override public void updateBinaryStream(String c, InputStream x, long l) { /* no-op */ }
        @Override public void updateCharacterStream(String c, Reader x, long l) { /* no-op */ }
        @Override public void updateBlob(int c, InputStream s, long l) { /* no-op */ }
        @Override public void updateBlob(String c, InputStream s, long l) { /* no-op */ }
        @Override public void updateClob(int c, Reader r, long l) { /* no-op */ }
        @Override public void updateClob(String c, Reader r, long l) { /* no-op */ }
        @Override public void updateNClob(int c, Reader r, long l) { /* no-op */ }
        @Override public void updateNClob(String c, Reader r, long l) { /* no-op */ }
        @Override public void updateNCharacterStream(int c, Reader r, long l) { /* no-op */ }
        @Override public void updateNCharacterStream(String c, Reader r, long l) { /* no-op */ }
        @Override public void updateAsciiStream(int c, InputStream x) { /* no-op */ }
        @Override public void updateBinaryStream(int c, InputStream x) { /* no-op */ }
        @Override public void updateCharacterStream(int c, Reader x) { /* no-op */ }
        @Override public void updateAsciiStream(String c, InputStream x) { /* no-op */ }
        @Override public void updateBinaryStream(String c, InputStream x) { /* no-op */ }
        @Override public void updateCharacterStream(String c, Reader x) { /* no-op */ }
        @Override public void updateBlob(int c, InputStream s) { /* no-op */ }
        @Override public void updateBlob(String c, InputStream s) { /* no-op */ }
        @Override public void updateClob(int c, Reader r) { /* no-op */ }
        @Override public void updateClob(String c, Reader r) { /* no-op */ }
        @Override public void updateNClob(int c, Reader r) { /* no-op */ }
        @Override public void updateNClob(String c, Reader r) { /* no-op */ }
        @Override public void updateNCharacterStream(int c, Reader r) { /* no-op */ }
        @Override public void updateNCharacterStream(String c, Reader r) { /* no-op */ }
    }
}
