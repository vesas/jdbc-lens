package io.github.vesas.jdbcprof.sample.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class CustomerDao {

    public String findById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM customers WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public String findEmailById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT email FROM customers WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void seed(Connection c, int count) throws SQLException {
        boolean prev = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO customers (id, name, email) VALUES (?, ?, ?)")) {
            for (int i = 1; i <= count; i++) {
                ps.setInt(1, i);
                ps.setString(2, "customer-" + i);
                ps.setString(3, "customer-" + i + "@example.com");
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } finally {
            c.setAutoCommit(prev);
        }
    }
}
