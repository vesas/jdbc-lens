package io.github.vesas.jdbcprof.sample;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema and seed data for the sample workload. Kept separate from
 * {@code Main} so the orchestrator reads as a sequence of business
 * operations rather than DDL noise.
 */
final class Bootstrap {

    private Bootstrap() {
    }

    static void createSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(100))");
            s.execute("CREATE TABLE orders ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "customer_id INT, amount DECIMAL(10,2))");
            s.execute("CREATE TABLE sessions ("
                    + "id INT PRIMARY KEY, last_seen TIMESTAMP)");
            s.execute("CREATE TABLE settings (k VARCHAR(100) PRIMARY KEY, v VARCHAR(100))");
            s.execute("INSERT INTO settings (k, v) VALUES "
                    + "('max_order_value', '10000'), "
                    + "('default_currency', 'EUR')");
        }
    }
}
