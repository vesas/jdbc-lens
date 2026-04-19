package fi.vesas.jdbcprof.sample.service;

import fi.vesas.jdbcprof.sample.dao.CustomerDao;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Showcases the COBOL-era "READ record, modify in memory, REWRITE
 * record" flow. The Java translation is typically:
 *
 * <pre>
 *   Profile p = customers.findFullById(c, id);        // READ
 *   p = p.withPhone(newPhone);                        // modify
 *   customers.updateAll(c, p);                        // REWRITE
 * </pre>
 *
 * — two anti-patterns stacked on top of each other. The profiler
 * flags them as:
 *
 * <ul>
 *   <li><b>Read-then-write on the same row</b> — SELECT and UPDATE
 *       on the same {@code customers.id} inside one op.</li>
 *   <li><b>Wide UPDATE</b> — 9 columns SET regardless of which one
 *       actually changed.</li>
 * </ul>
 *
 * <p>Together they're the single biggest volume driver for CDC /
 * replication / audit noise in legacy apps that haven't been
 * modernised.
 */
public final class ProfileService {

    private final CustomerDao customers;

    public ProfileService(CustomerDao customers) {
        this.customers = customers;
    }

    public void updatePhone(Connection c, int customerId, String newPhone) throws SQLException {
        CustomerDao.Profile current = customers.findFullById(c, customerId);
        if (current == null) {
            return;
        }
        CustomerDao.Profile updated = new CustomerDao.Profile(
                current.id(),
                current.name(),
                current.email(),
                newPhone,
                current.addressLine(),
                current.city(),
                current.state(),
                current.postalCode(),
                current.country(),
                current.timezone());
        customers.updateAll(c, updated);
    }
}
