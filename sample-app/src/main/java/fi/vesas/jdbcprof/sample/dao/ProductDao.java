package fi.vesas.jdbcprof.sample.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProductDao {

    public record Product(int id, String name, BigDecimal price) {}

    public List<Product> findByNameLike(Connection c, String pattern) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, price FROM products WHERE name LIKE ?")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                List<Product> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Product(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)));
                }
                return out;
            }
        }
    }

    /**
     * Batch fetch by id list — one IN-clause query instead of N point
     * lookups. Contrasts with the N+1 LIKE loop in ProductSearchService.
     */
    public List<Product> findByIds(Connection c, List<Integer> ids) throws SQLException {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, price FROM products WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setInt(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Product> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Product(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)));
                }
                return out;
            }
        }
    }
}
