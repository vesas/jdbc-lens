package fi.vesas.jdbclens.sample.batch;

import java.sql.Connection;
import java.sql.SQLException;

import fi.vesas.jdbclens.sample.dao.AuditDao;
import fi.vesas.jdbclens.sample.dao.CustomerDao;
import fi.vesas.jdbclens.sample.dao.OrderDao;

/**
 * Builds one customer's daily statement by composing three existing
 * DAO calls. The public {@link #buildFor} delegates to three private
 * helpers so each step shows up as its own frame in the flamegraph —
 * the shape is a realistic orchestration layer rather than one
 * monolithic method.
 */
public final class StatementBuilder {

    private final CustomerDao customers;
    private final OrderDao orders;
    private final AuditDao audit;

    public StatementBuilder(CustomerDao customers, OrderDao orders, AuditDao audit) {
        this.customers = customers;
        this.orders = orders;
        this.audit = audit;
    }

    public void buildFor(Connection c, int customerId) throws SQLException {
        CustomerDao.Profile profile = loadProfile(c, customerId);
        if (profile == null) {
            return;
        }
        int orderCount = loadSpending(c, customerId);
        recordStatement(c, profile, orderCount);
    }

    private CustomerDao.Profile loadProfile(Connection c, int id) throws SQLException {
        return customers.findFullById(c, id);
    }

    private int loadSpending(Connection c, int id) throws SQLException {
        return orders.countByCustomer(c, id);
    }

    private void recordStatement(Connection c, CustomerDao.Profile p, int orderCount)
            throws SQLException {
        audit.log(c, "statement",
                "customer=" + p.id() + " orders=" + orderCount + " email=" + p.email());
    }
}
