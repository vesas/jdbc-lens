package fi.vesas.jdbcprof.sample;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;

/**
 * Single, global access point for the sample app's {@link DataSource}.
 * Application code calls {@link #get()}; the test-harness / profiler
 * integration calls {@link #set(DataSource)} once at startup to swap
 * in a wrapped instance.
 *
 * <p>Having one seam like this is the real integration ergonomic the
 * profiler depends on. Application code never has to know whether the
 * DataSource is wrapped.
 */
public final class DataSources {

    private static volatile DataSource current;

    private DataSources() {
    }

    public static synchronized DataSource get() {
        if (current == null) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            current = ds;
        }
        return current;
    }

    public static synchronized void set(DataSource ds) {
        current = ds;
    }
}
