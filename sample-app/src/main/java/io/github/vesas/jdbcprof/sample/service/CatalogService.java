package io.github.vesas.jdbcprof.sample.service;

import io.github.vesas.jdbcprof.sample.dao.OrderDao;

import java.sql.Connection;
import java.sql.SQLException;

public final class CatalogService {

    private final OrderDao orders;

    public CatalogService(OrderDao orders) {
        this.orders = orders;
    }

    /**
     * Pretends to render a "featured item" card for one hot customer
     * {@code renders} times in quick succession. Each render asks for
     * the same order count with the same parameter — a cache miss
     * the profiler surfaces as a redundant-query finding.
     *
     * <p>Intentionally uses a <em>different</em> SQL template than
     * {@link OrderService#listDashboard} so the dashboard N+1 is
     * measured cleanly and not diluted by these calls.
     */
    public void renderFeaturedItem(Connection c, int customerId, int renders) throws SQLException {
        for (int i = 0; i < renders; i++) {
            orders.countByCustomer(c, customerId);
        }
    }
}
