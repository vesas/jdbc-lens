package io.github.vesas.jdbcprof.sample.service;

import io.github.vesas.jdbcprof.sample.dao.CustomerDao;
import io.github.vesas.jdbcprof.sample.dao.OrderDao;
import io.github.vesas.jdbcprof.sample.dao.SettingsDao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class OrderService {

    private final OrderDao orders;
    private final CustomerDao customers;
    private final SettingsDao settings;

    public OrderService(OrderDao orders, CustomerDao customers, SettingsDao settings) {
        this.orders = orders;
        this.customers = customers;
        this.settings = settings;
    }

    /**
     * Textbook N+1. {@link OrderDao#findRecent} returns a batch of
     * order summaries; the service loops and asks the customer DAO
     * for one name at a time. The ancestor frame the report should
     * point at is this method's loop — not the DAO method — because
     * the DAO is just doing what it's told.
     */
    public void listDashboard(Connection c, int limit) throws SQLException {
        List<OrderDao.Summary> recent = orders.findRecent(c, limit);
        for (OrderDao.Summary o : recent) {
            customers.findById(c, o.customerId());
        }
    }

    /**
     * Plants two patterns side by side: a redundant
     * {@code SELECT v FROM settings WHERE k = 'max_order_value'}
     * per loop iteration (same params — cache candidate) and a
     * non-redundant INSERT that varies the customer id (N+1-shaped
     * batching opportunity).
     */
    public void placeOrders(Connection c, int customerCount) throws SQLException {
        BigDecimal unitPrice = new BigDecimal("19.95");
        for (int cid = 1; cid <= customerCount; cid++) {
            settings.getByKey(c, "max_order_value");
            orders.insert(c, cid, unitPrice);
        }
    }
}
