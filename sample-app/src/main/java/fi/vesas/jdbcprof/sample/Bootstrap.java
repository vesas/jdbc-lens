package fi.vesas.jdbcprof.sample;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
            s.execute("CREATE TABLE customers ("
                    + "id INT PRIMARY KEY, "
                    + "name VARCHAR(100), "
                    + "email VARCHAR(150), "
                    + "phone VARCHAR(30), "
                    + "address_line VARCHAR(200), "
                    + "city VARCHAR(80), "
                    + "state VARCHAR(40), "
                    + "postal_code VARCHAR(20), "
                    + "country VARCHAR(40), "
                    + "timezone VARCHAR(40))");
            s.execute("CREATE TABLE orders ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "customer_id INT, "
                    + "amount DECIMAL(10,2), "
                    + "status VARCHAR(20), "
                    + "shipped_at TIMESTAMP, "
                    + "refunded BOOLEAN DEFAULT FALSE)");
            s.execute("CREATE TABLE sessions ("
                    + "id INT PRIMARY KEY, last_seen TIMESTAMP)");
            s.execute("CREATE TABLE settings (k VARCHAR(100) PRIMARY KEY, v VARCHAR(100))");
            s.execute("INSERT INTO settings (k, v) VALUES "
                    + "('max_order_value', '10000'), "
                    + "('default_currency', 'EUR')");
            s.execute("CREATE TABLE audit ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "kind VARCHAR(50), payload VARCHAR(500), ts TIMESTAMP)");
            // Enterprise tables: transactional outbox, per-customer
            // balance rollup, reconciliation exception log. Together
            // with BalanceDao / OutboxDao / ExceptionDao they wire the
            // sample into two enterprise shapes most real Java
            // back-offices have: outbox dispatch and nightly
            // reconciliation.
            s.execute("CREATE TABLE outbox_events ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "kind VARCHAR(50), payload VARCHAR(500), "
                    + "status VARCHAR(20) DEFAULT 'PENDING', "
                    + "attempts INT DEFAULT 0, "
                    + "last_error VARCHAR(200), "
                    + "created_at TIMESTAMP, processed_at TIMESTAMP)");
            s.execute("CREATE TABLE customer_balances ("
                    + "customer_id INT PRIMARY KEY, "
                    + "orders_count INT, "
                    + "lifetime_spend DECIMAL(14,2), "
                    + "last_order_at TIMESTAMP, "
                    + "updated_at TIMESTAMP)");
            s.execute("CREATE TABLE reconciliation_exceptions ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "customer_id INT, "
                    + "expected DECIMAL(14,2), "
                    + "actual DECIMAL(14,2), "
                    + "reason VARCHAR(200), "
                    + "detected_at TIMESTAMP)");
            s.execute("CREATE TABLE products ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "sku VARCHAR(30), "
                    + "name VARCHAR(100), "
                    + "category VARCHAR(50), "
                    + "price DECIMAL(10,2), "
                    + "stock_qty INT)");
        }
    }

    static void seedProducts(Connection c) throws SQLException {
        boolean prev = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO products (sku, name, category, price, stock_qty) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            String[][] electronics = {
                {"ELEC-001","Wireless Headphones Model 1","Electronics","99.99","50"},
                {"ELEC-002","Wireless Headphones Model 2","Electronics","119.99","40"},
                {"ELEC-003","Wireless Headphones Model 3","Electronics","139.99","30"},
                {"ELEC-004","Bluetooth Speaker Model 1","Electronics","49.99","80"},
                {"ELEC-005","Bluetooth Speaker Model 2","Electronics","69.99","60"},
                {"ELEC-006","Bluetooth Speaker Model 3","Electronics","89.99","45"},
                {"ELEC-007","USB-C Hub Model 1","Electronics","29.99","100"},
                {"ELEC-008","USB-C Hub Model 2","Electronics","39.99","90"},
                {"ELEC-009","Wireless Charger Model 1","Electronics","24.99","120"},
                {"ELEC-010","Wireless Charger Model 2","Electronics","34.99","95"},
                {"ELEC-011","Smart Watch Model 1","Electronics","199.99","25"},
                {"ELEC-012","Smart Watch Model 2","Electronics","249.99","20"},
                {"ELEC-013","Smart Watch Model 3","Electronics","299.99","15"},
                {"ELEC-014","Laptop Stand Model 1","Electronics","44.99","70"},
                {"ELEC-015","Laptop Stand Model 2","Electronics","59.99","55"},
                {"ELEC-016","Mechanical Keyboard Model 1","Electronics","79.99","35"},
                {"ELEC-017","Mechanical Keyboard Model 2","Electronics","109.99","28"},
            };
            String[][] clothing = {
                {"CLTH-001","Cotton T-Shirt 1","Clothing","19.99","200"},
                {"CLTH-002","Cotton T-Shirt 2","Clothing","19.99","200"},
                {"CLTH-003","Cotton T-Shirt 3","Clothing","19.99","180"},
                {"CLTH-004","Slim Fit Jeans 1","Clothing","49.99","120"},
                {"CLTH-005","Slim Fit Jeans 2","Clothing","54.99","110"},
                {"CLTH-006","Slim Fit Jeans 3","Clothing","59.99","100"},
                {"CLTH-007","Wool Sweater 1","Clothing","69.99","75"},
                {"CLTH-008","Wool Sweater 2","Clothing","79.99","65"},
                {"CLTH-009","Running Jacket 1","Clothing","89.99","50"},
                {"CLTH-010","Running Jacket 2","Clothing","99.99","45"},
                {"CLTH-011","Linen Shirt 1","Clothing","39.99","90"},
                {"CLTH-012","Linen Shirt 2","Clothing","44.99","85"},
                {"CLTH-013","Cargo Shorts 1","Clothing","34.99","110"},
                {"CLTH-014","Cargo Shorts 2","Clothing","39.99","100"},
                {"CLTH-015","Canvas Sneakers 1","Clothing","59.99","80"},
                {"CLTH-016","Canvas Sneakers 2","Clothing","64.99","70"},
                {"CLTH-017","Canvas Sneakers 3","Clothing","69.99","60"},
            };
            String[][] books = {
                {"BOOK-001","Java Programming Vol 1","Books","39.99","300"},
                {"BOOK-002","Java Programming Vol 2","Books","39.99","280"},
                {"BOOK-003","Java Programming Vol 3","Books","39.99","260"},
                {"BOOK-004","Java Programming Vol 4","Books","34.99","240"},
                {"BOOK-005","Design Patterns in Practice","Books","44.99","150"},
                {"BOOK-006","Clean Architecture Guide","Books","34.99","180"},
                {"BOOK-007","SQL Performance Explained","Books","29.99","200"},
                {"BOOK-008","Database Internals","Books","49.99","120"},
                {"BOOK-009","Distributed Systems Vol 1","Books","44.99","90"},
                {"BOOK-010","Distributed Systems Vol 2","Books","44.99","80"},
                {"BOOK-011","Algorithms and Data Structures","Books","54.99","160"},
                {"BOOK-012","Operating Systems Concepts","Books","59.99","100"},
                {"BOOK-013","Computer Networks Handbook","Books","49.99","90"},
                {"BOOK-014","Refactoring Workbook","Books","29.99","200"},
                {"BOOK-015","Test-Driven Development Guide","Books","24.99","220"},
                {"BOOK-016","Continuous Delivery Handbook","Books","34.99","170"},
            };
            for (String[][] category : new String[][][]{electronics, clothing, books}) {
                for (String[] row : category) {
                    ps.setString(1, row[0]);
                    ps.setString(2, row[1]);
                    ps.setString(3, row[2]);
                    ps.setBigDecimal(4, new BigDecimal(row[3]));
                    ps.setInt(5, Integer.parseInt(row[4]));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            c.commit();
        } finally {
            c.setAutoCommit(prev);
        }
    }
}
