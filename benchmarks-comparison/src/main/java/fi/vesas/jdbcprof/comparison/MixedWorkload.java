package fi.vesas.jdbcprof.comparison;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Read-then-write per iteration: {@code SELECT} the current value,
 * compute a new value in Java, {@code UPDATE}. This is the cursor-
 * loop shape the analyzer flags as N+1 in the sample app, and the
 * one most people are profiling for.
 *
 * <p>Two JDBC operations per iteration, so the per-op overhead is
 * roughly {@code (modeTime - baseTime) / (iterations * 2)}.
 */
final class MixedWorkload implements Workload {

    private static final int ROWS = 500;

    @Override
    public String name() {
        return "mixed-read-write";
    }

    @Override
    public int opsPerIteration() {
        return 2;
    }

    @Override
    public void setup(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE acct (id INT PRIMARY KEY, balance BIGINT)");
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO acct (id, balance) VALUES (?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    ins.setInt(1, i);
                    ins.setLong(2, 1000L);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    @Override
    public void iterate(DataSource ds, int iterations) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement read = c.prepareStatement(
                     "SELECT balance FROM acct WHERE id = ?");
             PreparedStatement write = c.prepareStatement(
                     "UPDATE acct SET balance = ? WHERE id = ?")) {
            for (int i = 0; i < iterations; i++) {
                int id = (i % ROWS) + 1;
                long current;
                read.setInt(1, id);
                try (ResultSet rs = read.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("missing acct " + id);
                    }
                    current = rs.getLong(1);
                }
                write.setLong(1, current + 1);
                write.setInt(2, id);
                write.executeUpdate();
            }
        }
    }
}
