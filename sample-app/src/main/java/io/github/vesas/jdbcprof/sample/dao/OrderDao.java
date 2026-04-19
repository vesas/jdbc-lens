package io.github.vesas.jdbcprof.sample.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class OrderDao {

    public record Summary(int id, int customerId) {
    }

    public List<Summary> findRecent(Connection c, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, customer_id FROM orders ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Summary> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Summary(rs.getInt(1), rs.getInt(2)));
                }
                return out;
            }
        }
    }

    public int countByCustomer(Connection c, int customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM orders WHERE customer_id = ?")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void insert(Connection c, int customerId, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders (customer_id, amount) VALUES (?, ?)")) {
            ps.setInt(1, customerId);
            ps.setBigDecimal(2, amount);
            ps.executeUpdate();
        }
    }
}
