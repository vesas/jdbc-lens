package fi.vesas.jdbclens.sample.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class AuditDao {

    public void log(Connection c, String kind, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit (kind, payload, ts) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, kind);
            ps.setString(2, payload);
            ps.executeUpdate();
        }
    }
}
