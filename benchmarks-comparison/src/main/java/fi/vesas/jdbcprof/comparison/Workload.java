package fi.vesas.jdbcprof.comparison;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * One JDBC workload shape that we want to measure overhead for.
 *
 * <p>Lifecycle is split deliberately: {@link #setup} happens once and
 * is not timed (schema + seed); {@link #iterate} is called many times
 * and only its wall clock matters. The interceptor is in place for
 * both phases — that's how it would be in a real app — but only the
 * iteration phase is measured.
 */
interface Workload {

    String name();

    /**
     * Roughly how many JDBC operations one {@code iterate(1)} call
     * issues. Used to convert per-iteration timings into per-operation
     * overhead numbers in the report.
     */
    int opsPerIteration();

    void setup(DataSource ds) throws SQLException;

    void iterate(DataSource ds, int iterations) throws SQLException;
}
