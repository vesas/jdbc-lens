package fi.vesas.jdbclens.sample.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Per-customer rollup of order activity. Updated by the monthly
 * reconciliation job. Exposes the classic "READ then INSERT-or-UPDATE"
 * upsert shape that's endemic to enterprise batch code \u2014 two
 * round trips per row, pure N+1 writer when looped over many
 * customers.
 */
public final class BalanceDao {

    public record Balance(
            int customerId,
            int ordersCount,
            BigDecimal lifetimeSpend,
            Timestamp lastOrderAt) {
    }

    public Balance findByCustomer(Connection c, int customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT customer_id, orders_count, lifetime_spend, last_order_at "
                        + "FROM customer_balances WHERE customer_id = ?")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Balance(rs.getInt(1), rs.getInt(2),
                        rs.getBigDecimal(3), rs.getTimestamp(4));
            }
        }
    }

    public void insert(Connection c, Balance b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO customer_balances "
                        + "(customer_id, orders_count, lifetime_spend, last_order_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, b.customerId());
            ps.setInt(2, b.ordersCount());
            ps.setBigDecimal(3, b.lifetimeSpend());
            ps.setTimestamp(4, b.lastOrderAt());
            ps.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    /**
     * Overwrites every rollup column. Wide-UPDATE by design \u2014
     * the reconciliation job has no idea which of the three
     * aggregate fields actually shifted, so it rewrites all of them.
     */
    public void updateAll(Connection c, Balance b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE customer_balances SET orders_count = ?, "
                        + "lifetime_spend = ?, last_order_at = ?, updated_at = ? "
                        + "WHERE customer_id = ?")) {
            ps.setInt(1, b.ordersCount());
            ps.setBigDecimal(2, b.lifetimeSpend());
            ps.setTimestamp(3, b.lastOrderAt());
            ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            ps.setInt(5, b.customerId());
            ps.executeUpdate();
        }
    }
}
