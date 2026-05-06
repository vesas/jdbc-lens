package fi.vesas.jdbclens.sample.service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

import fi.vesas.jdbclens.sample.dao.AuditDao;
import fi.vesas.jdbclens.sample.dao.CustomerDao;

/**
 * Thin layer on top of {@link AuditDao}. Exists to show the profiler
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
 * <p>Also fetches the customer's email before logging — a deliberate
 * "second access" of the same customer row via a different template,
 * so the entity-access audit has something to flag in the demo.
 */
public final class NotificationService {

    private final AuditDao audit;
    private final CustomerDao customers;

    public NotificationService(AuditDao audit, CustomerDao customers) {
        this.audit = audit;
        this.customers = customers;
    }

    public void recordCheckout(Connection c, int customerId, BigDecimal amount) throws SQLException {
        String email = customers.findEmailById(c, customerId);
        audit.log(c, "checkout-started", "customer=" + customerId + " email=" + email);
        audit.log(c, "checkout-completed",
                "customer=" + customerId + " email=" + email + " amount=" + amount);
    }
}
