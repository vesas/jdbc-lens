package io.github.vesas.jdbcprof.sample;

import io.github.vesas.jdbcprof.Profiler;
import io.github.vesas.jdbcprof.ProfilerConfig;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Runs a small workload against H2 under the profiler so a fresh
 * recording has schema, seed, a classic N+1 loop, and a batched
 * insert to look at. Writes to {@code sample-recording.jdbclog} in
 * the current working directory and prints its absolute path.
 */
public final class Main {

    private static final int CUSTOMERS = 50;
    private static final int ORDERS_PER_CUSTOMER = 4;
    private static final String RECORDING = "sample-recording.jdbclog";

    public static void main(String[] args) throws Exception {
        Path log = Path.of(RECORDING).toAbsolutePath();

        // 1. Start the profiler.
        Profiler.start(ProfilerConfig.defaults(log));

        // 2. Wrap the single DataSource seam.
        DataSources.set(Profiler.wrap(DataSources.get()));

        // 3. Run a workload through the wrapped source.
        DataSource ds = DataSources.get();
        try (Connection c = ds.getConnection()) {
            createSchema(c);
            seed(c);
            classicN1Loop(c);
            batchedInserts(c);
            updateSessions(c);
        }

        // 4. Stop the profiler (the shutdown hook would also handle this).
        Profiler.stop();

        System.out.println("recording: " + log);
        System.out.println("report it: ./gradlew :analysis:run --args=\""
                + log + " -o report.html\"");
    }

    private static void createSchema(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(100))");
            s.execute("CREATE TABLE orders ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "customer_id INT, amount DECIMAL(10,2))");
            s.execute("CREATE TABLE sessions ("
                    + "id INT PRIMARY KEY, last_seen TIMESTAMP)");
        }
    }

    private static void seed(Connection c) throws Exception {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO customers (id, name) VALUES (?, ?)")) {
            for (int i = 1; i <= CUSTOMERS; i++) {
                ins.setInt(1, i);
                ins.setString(2, "customer-" + i);
                ins.executeUpdate();
            }
        }
    }

    /**
     * Textbook N+1: one row per iteration instead of a single
     * batched query. The profiler should surface this as a high-count
     * (call-site, template) pair dominating DB time.
     */
    private static void classicN1Loop(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM customers WHERE id = ?")) {
            for (int id = 1; id <= CUSTOMERS; id++) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getString(1);
                    }
                }
            }
        }
    }

    private static void batchedInserts(Connection c) throws Exception {
        c.setAutoCommit(false);
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO orders (customer_id, amount) VALUES (?, ?)")) {
            for (int cid = 1; cid <= CUSTOMERS; cid++) {
                for (int k = 0; k < ORDERS_PER_CUSTOMER; k++) {
                    ins.setInt(1, cid);
                    ins.setBigDecimal(2, new java.math.BigDecimal("19.95"));
                    ins.addBatch();
                }
            }
            ins.executeBatch();
        }
        c.commit();
        c.setAutoCommit(true);
    }

    private static void updateSessions(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sessions SET last_seen = CURRENT_TIMESTAMP WHERE id = ?")) {
            for (int id = 1; id <= 5; id++) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
    }

    private Main() {
    }
}
