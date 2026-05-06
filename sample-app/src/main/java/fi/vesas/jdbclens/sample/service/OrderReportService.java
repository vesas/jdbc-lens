package fi.vesas.jdbclens.sample.service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import fi.vesas.jdbclens.sample.dao.CustomerDao;

/**
 * The first JOIN query in the sample app, paired with a deliberate N+1
 * follow-up. {@link #topCustomersBySpend} is the efficient path — one
 * GROUP BY + JOIN. {@link #buildTopReport} calls it and then re-fetches
 * every customer individually, throwing away the joined data the first
 * query already returned. The profiler sees the JOIN as one call-site
 * and the N customer lookups as N calls from the loop call-site.
 */
public final class OrderReportService {

    public record TopCustomer(int customerId, String name, int orderCount, BigDecimal totalSpend) {}

    private final CustomerDao customers;

    public OrderReportService(CustomerDao customers) {
        this.customers = customers;
    }

    public List<TopCustomer> topCustomersBySpend(Connection c, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT c.id, c.name, COUNT(o.id), SUM(o.amount) "
                        + "FROM orders o "
                        + "JOIN customers c ON c.id = o.customer_id "
                        + "GROUP BY c.id, c.name "
                        + "ORDER BY SUM(o.amount) DESC "
                        + "LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<TopCustomer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new TopCustomer(rs.getInt(1), rs.getString(2),
                            rs.getInt(3), rs.getBigDecimal(4)));
                }
                return out;
            }
        }
    }

    /**
     * Bad version: runs the JOIN, then re-fetches each customer's full
     * profile in a loop — data the JOIN already returned. Deliberate
     * N+1 on top of an otherwise-efficient aggregate query.
     */
    public List<TopCustomer> buildTopReport(Connection c, int limit) throws SQLException {
        List<TopCustomer> top = topCustomersBySpend(c, limit);
        for (TopCustomer row : top) {
            customers.findFullById(c, row.customerId());
        }
        return top;
    }
}
