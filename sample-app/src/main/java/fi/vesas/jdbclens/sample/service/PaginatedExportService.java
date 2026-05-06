package fi.vesas.jdbclens.sample.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * OFFSET-based pagination loop — a hidden sequential scan. Each page
 * re-scans all prior rows, and the profiler sees N near-identical
 * queries with incrementing OFFSET parameters from the same call-site.
 * With 200 customers and pageSize=20, this produces 10 page queries.
 */
public final class PaginatedExportService {

    private record CustomerRow(int id, String name, String email) {}

    public int exportAll(Connection c, int pageSize) throws SQLException {
        int offset = 0;
        int total = 0;
        while (true) {
            List<CustomerRow> page = fetchPage(c, pageSize, offset);
            if (page.isEmpty()) {
                break;
            }
            total += page.size();
            offset += pageSize;
            if (page.size() < pageSize) {
                break;
            }
        }
        return total;
    }

    private List<CustomerRow> fetchPage(Connection c, int limit, int offset) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, email FROM customers LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<CustomerRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new CustomerRow(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
                return out;
            }
        }
    }
}
