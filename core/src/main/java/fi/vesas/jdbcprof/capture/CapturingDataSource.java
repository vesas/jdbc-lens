package fi.vesas.jdbcprof.capture;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Wraps an application-supplied {@link DataSource}. Every
 * {@link Connection} handed out is a {@link CapturingConnection} bound
 * to the same {@link CaptureContext} so events reach a single sink
 * (spec §5.1).
 */
public final class CapturingDataSource implements DataSource {

    private final DataSource delegate;
    private final CaptureContext ctx;

    public CapturingDataSource(DataSource delegate, CaptureContext ctx) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.delegate = delegate;
        this.ctx = ctx;
    }

    public DataSource delegate() {
        return delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return new CapturingConnection(delegate.getConnection(), ctx);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return new CapturingConnection(delegate.getConnection(username, password), ctx);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
