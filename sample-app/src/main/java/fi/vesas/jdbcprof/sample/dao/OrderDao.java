package fi.vesas.jdbcprof.sample.dao;

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

    public record LatestOrder(int id, BigDecimal amount) {
    }

    public LatestOrder findLatestByCustomer(Connection c, int customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, amount FROM orders WHERE customer_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new LatestOrder(rs.getInt(1), rs.getBigDecimal(2)) : null;
            }
        }
    }

    public void markRefunded(Connection c, int orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET refunded = TRUE WHERE id = ?")) {
            ps.setInt(1, orderId);
            ps.executeUpdate();
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

    public BigDecimal sumAmountByCustomer(Connection c, int customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT SUM(amount) FROM orders WHERE customer_id = ?")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return BigDecimal.ZERO;
                }
                BigDecimal v = rs.getBigDecimal(1);
                return v == null ? BigDecimal.ZERO : v;
            }
        }
    }

    public java.sql.Timestamp latestOrderAtByCustomer(Connection c, int customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT shipped_at FROM orders WHERE customer_id = ? "
                        + "ORDER BY id DESC LIMIT 1")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1) : null;
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

    public void updateStatus(Connection c, int orderId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
    }

    public void markShipped(Connection c, int orderId, java.sql.Timestamp when) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET shipped_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, when);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
    }

    public void updateAmount(Connection c, int orderId, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET amount = ? WHERE id = ?")) {
            ps.setBigDecimal(1, amount);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
    }
}
