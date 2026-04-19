package fi.vesas.jdbcprof.sample.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class SessionDao {

    public void touchLastSeen(Connection c, int sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sessions SET last_seen = CURRENT_TIMESTAMP WHERE id = ?")) {
            ps.setInt(1, sessionId);
            ps.executeUpdate();
        }
    }
}
