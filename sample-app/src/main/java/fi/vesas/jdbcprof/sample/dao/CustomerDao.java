package fi.vesas.jdbcprof.sample.dao;

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

    public record Profile(
            int id, String name, String email, String phone,
            String addressLine, String city, String state,
            String postalCode, String country, String timezone) {
    }

    public Profile findFullById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, email, phone, address_line, city, state, "
                        + "postal_code, country, timezone FROM customers WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Profile(rs.getInt(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6),
                                rs.getString(7), rs.getString(8), rs.getString(9),
                                rs.getString(10))
                        : null;
            }
        }
    }

    /**
     * The legacy {@code REWRITE RECORD} shape: overwrite every column
     * regardless of what actually changed. Target for both the
     * over-wide UPDATE detector (9 columns SET) and, when paired
     * with {@link #findFullById}, the read-then-write detector.
     */
    public void updateAll(Connection c, Profile p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE customers SET name = ?, email = ?, phone = ?, "
                        + "address_line = ?, city = ?, state = ?, postal_code = ?, "
                        + "country = ?, timezone = ? WHERE id = ?")) {
            ps.setString(1, p.name());
            ps.setString(2, p.email());
            ps.setString(3, p.phone());
            ps.setString(4, p.addressLine());
            ps.setString(5, p.city());
            ps.setString(6, p.state());
            ps.setString(7, p.postalCode());
            ps.setString(8, p.country());
            ps.setString(9, p.timezone());
            ps.setInt(10, p.id());
            ps.executeUpdate();
        }
    }

    public void seed(Connection c, int count) throws SQLException {
        boolean prev = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO customers (id, name, email, phone, address_line, "
                        + "city, state, postal_code, country, timezone) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 1; i <= count; i++) {
                ps.setInt(1, i);
                ps.setString(2, "customer-" + i);
                ps.setString(3, "customer-" + i + "@example.com");
                ps.setString(4, "+1-555-0" + String.format("%03d", i));
                ps.setString(5, i + " Main St");
                ps.setString(6, "Springfield");
                ps.setString(7, "IL");
                ps.setString(8, "62704");
                ps.setString(9, "US");
                ps.setString(10, "America/Chicago");
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } finally {
            c.setAutoCommit(prev);
        }
    }
}
