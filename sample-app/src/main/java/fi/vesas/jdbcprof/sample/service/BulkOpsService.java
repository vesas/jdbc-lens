package fi.vesas.jdbcprof.sample.service;

import fi.vesas.jdbcprof.sample.dao.CustomerDao;
import fi.vesas.jdbcprof.sample.dao.OrderDao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Bulk maintenance jobs shaped the way legacy back-office code
 * typically is: iterate a collection of ids in the app, and issue one
 * UPDATE per row inside the Java loop. Both methods below are
 * deliberate N+1 writers \u2014 they're what the analyzer should
 * flag as row-by-row cursor loops begging to be collapsed into a
 * single bulk UPDATE.
 *
 * <p>{@link #applyFlatDiscount} pairs a recent-orders SELECT with an
 * UPDATE-amount per row. {@link #migrateTimezone} is the full
 * READ-then-REWRITE smell: fetch every column of a customer,
 * overwrite one, write every column back.
 */
public final class BulkOpsService {

    private final OrderDao orders;
    private final CustomerDao customers;

    public BulkOpsService(OrderDao orders, CustomerDao customers) {
        this.orders = orders;
        this.customers = customers;
    }

    /**
     * Classic "loop and update" shape: one SELECT to get the batch,
     * then one UPDATE per row in application code. A single
     * {@code UPDATE orders SET amount = amount * (1 - ?) WHERE id IN (...)}
     * would do the same work in one round trip.
     */
    public int applyFlatDiscount(Connection c, int limit, BigDecimal discount) throws SQLException {
        List<OrderDao.Summary> recent = orders.findRecent(c, limit);
        BigDecimal keepFactor = BigDecimal.ONE.subtract(discount);
        int touched = 0;
        for (OrderDao.Summary o : recent) {
            OrderDao.LatestOrder current = orders.findLatestByCustomer(c, o.customerId());
            if (current == null) {
                continue;
            }
            BigDecimal next = current.amount().multiply(keepFactor).setScale(2, RoundingMode.HALF_UP);
            orders.updateAmount(c, o.id(), next);
            touched++;
        }
        return touched;
    }

    /**
     * The COBOL-rewrite shape at its purest: for every id in the
     * input, read the full record, swap one field in memory, and
     * rewrite the entire record. Triggers both the "read-then-write"
     * and "over-wide UPDATE" detectors once per iteration, and
     * produces obvious cache-invalidation hotspots because every
     * customer column gets touched.
     */
    public int migrateTimezone(Connection c, int[] customerIds, String newTimezone) throws SQLException {
        int touched = 0;
        for (int id : customerIds) {
            CustomerDao.Profile current = customers.findFullById(c, id);
            if (current == null) {
                continue;
            }
            CustomerDao.Profile updated = new CustomerDao.Profile(
                    current.id(),
                    current.name(),
                    current.email(),
                    current.phone(),
                    current.addressLine(),
                    current.city(),
                    current.state(),
                    current.postalCode(),
                    current.country(),
                    newTimezone);
            customers.updateAll(c, updated);
            touched++;
        }
        return touched;
    }
}
