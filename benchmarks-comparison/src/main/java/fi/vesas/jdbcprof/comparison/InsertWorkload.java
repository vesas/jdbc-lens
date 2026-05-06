package fi.vesas.jdbclens.comparison;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tight loop of single-row {@code INSERT}s with two parameters,
 * autocommit on, no batching. Exercises the interceptor's parameter-
 * capture path: each iteration runs {@code setInt}, {@code setString},
 * and {@code executeUpdate}, which is where P6Spy's per-parameter
 * tracking and our parameter-value capture both have to do work.
 *
 * <p>Autocommit per row is deliberate — it's the shape that falls out
 * of naive write code, and the one that a profiler is most often asked
 * to diagnose.
 */
final class InsertWorkload implements Workload {

    private static final String INSERT = "INSERT INTO evt (id, payload) VALUES (?, ?)";

    @Override
    public String name() {
        return "insert";
    }

    @Override
    public int opsPerIteration() {
        return 1;
    }

    @Override
    public void setup(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE evt (id INT PRIMARY KEY, payload VARCHAR(128))");
        }
    }

    @Override
    public void iterate(DataSource ds, int iterations) throws SQLException {
        try (Connection c = ds.getConnection()) {
            for (int i = 0; i < iterations; i++) {
                try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                    ps.setInt(1, i);
                    ps.setString(2, "payload-" + i);
                    ps.executeUpdate();
                }
            }
            // Truncate so the next measurement run starts from an empty
            // table and pk collisions don't pollute timings.
            try (Statement s = c.createStatement()) {
                s.execute("TRUNCATE TABLE evt");
            }
        }
    }
}
