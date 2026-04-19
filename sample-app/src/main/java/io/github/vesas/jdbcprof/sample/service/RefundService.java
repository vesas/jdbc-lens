package io.github.vesas.jdbcprof.sample.service;

import io.github.vesas.jdbcprof.sample.dao.AuditDao;
import io.github.vesas.jdbcprof.sample.dao.OrderDao;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Explicit-transaction flow. Demonstrates the transaction-shape view:
 * the drill-down for {@code refund-latest} shows one TX per call,
 * each with 1 read + 2 writes + a commit outcome.
 *
 * <p>Also exhibits the entity-access pattern at the order level: the
 * order is loaded via {@link OrderDao#findLatestByCustomer} then
 * touched again via {@link OrderDao#markRefunded} — two templates on
 * the same {@code orders.id}, flagged by the audit.
 */
public final class RefundService {

    private final OrderDao orders;
    private final AuditDao audit;

    public RefundService(OrderDao orders, AuditDao audit) {
        this.orders = orders;
        this.audit = audit;
    }

    public void refundLatest(Connection c, int customerId) throws SQLException {
        boolean prev = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            OrderDao.LatestOrder latest = orders.findLatestByCustomer(c, customerId);
            if (latest != null) {
                orders.markRefunded(c, latest.id());
                audit.log(c, "refund",
                        "customer=" + customerId + " order=" + latest.id()
                                + " amount=" + latest.amount());
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(prev);
        }
    }
}
