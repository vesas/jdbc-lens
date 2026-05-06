package fi.vesas.jdbclens.comparison;

import com.p6spy.engine.spy.P6DataSource;

import fi.vesas.jdbclens.Profiler;
import fi.vesas.jdbclens.ProfilerConfig;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Builds a fresh, isolated H2 in-memory DataSource per run, then layers
 * the appropriate JDBC interceptor on top.
 *
 * <p>Each run gets its own DB name (random suffix) so warmup state from
 * a previous run cannot leak into the measurement run via H2's
 * {@code DB_CLOSE_DELAY=-1} retention.
 */
final class DataSourceFactory {

    private DataSourceFactory() {
    }

    static Handle build(Mode mode, String runId) {
        String dbName = "cmp_" + runId + "_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");

        return switch (mode) {
            case NONE -> new Handle(h2, null);
            case JDBCPROF -> {
                Path log = Path.of("build", "comparison-" + runId + ".jdbclog").toAbsolutePath();
                Profiler.start(ProfilerConfig.defaults(log));
                yield new Handle(Profiler.wrap(h2), log);
            }
            case P6SPY -> {
                P6DataSource wrapped = new P6DataSource(h2);
                yield new Handle(wrapped, null);
            }
        };
    }

    /**
     * Tear down whatever {@link #build} installed. {@link #JDBCPROF}
     * needs a {@code Profiler.stop()} to flush the recording; the others
     * are stateless wrappers and just need to be dropped.
     */
    static void teardown(Mode mode) {
        if (mode == Mode.JDBCPROF) {
            Profiler.stop();
        }
    }

    record Handle(DataSource dataSource, Path recordingFile) {}
}
