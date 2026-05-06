package fi.vesas.jdbclens.comparison;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tight loop of single-row {@code SELECT}s by primary key against a
 * 1000-row table. Stresses pure per-call interceptor cost: one
 * {@code prepareStatement} + {@code setInt} + {@code executeQuery} +
 * {@code next} + {@code getString} + close, repeated.
 *
 * <p>This is the shape that hurts an interceptor most — minimal driver
 * work per call, so any per-call overhead is highly visible in the
 * total wall time.
 */
final class PointReadWorkload implements Workload {

    private static final int ROWS = 1000;
    private static final String SELECT = "SELECT name FROM cust WHERE id = ?";

    @Override
    public String name() {
        return "point-read";
    }

    @Override
    public int opsPerIteration() {
        // prepare + execute + close ≈ accounted as one operation by both
        // interceptors (one statement boundary). Close is paired
        // implicitly by try-with-resources.
        return 1;
    }

    @Override
    public void setup(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE cust (id INT PRIMARY KEY, name VARCHAR(64))");
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO cust (id, name) VALUES (?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    ins.setInt(1, i);
                    ins.setString(2, "name-" + i);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    @Override
    public void iterate(DataSource ds, int iterations) throws SQLException {
        try (Connection c = ds.getConnection()) {
            for (int i = 0; i < iterations; i++) {
                try (PreparedStatement ps = c.prepareStatement(SELECT)) {
                    ps.setInt(1, (i % ROWS) + 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            // touch the column so the driver actually
                            // materialises the row — keeps the workload
                            // honest under a smart driver.
                            String s = rs.getString(1);
                            if (s == null) {
                                throw new IllegalStateException("missing row " + i);
                            }
                        }
                    }
                }
            }
        }
    }
}
