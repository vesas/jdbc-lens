package io.github.vesas.jdbcprof.sample.service;

import io.github.vesas.jdbcprof.sample.dao.AuditDao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Thin layer on top of {@link AuditDao} — exists to show the profiler
 * how a real call chain looks in the report. When
 * {@link #recordCheckout} fires, the stack captured with each audit
 * INSERT reads:
 *
 * <pre>
 * AuditDao.log
 * NotificationService.recordCheckout
 * OrderService.completeCheckout
 * Main.main
 * </pre>
 *
 * which is exactly the kind of attribution the report is meant to
 * surface on real applications.
 */
public final class NotificationService {

    private final AuditDao audit;

    public NotificationService(AuditDao audit) {
        this.audit = audit;
    }

    public void recordCheckout(Connection c, int customerId, BigDecimal amount) throws SQLException {
        audit.log(c, "checkout-started", "customer=" + customerId);
        audit.log(c, "checkout-completed", "customer=" + customerId + " amount=" + amount);
    }
}
