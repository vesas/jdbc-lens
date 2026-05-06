package fi.vesas.jdbcprof.sample.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Maintenance operations that exercise DELETE statements — absent
 * everywhere else in the sample app. {@link #trimAuditLog} is the
 * deliberate read-then-delete shape: SELECT COUNT first, then DELETE,
 * which the analyzer can flag as a read-then-write pair on the same table.
 */
public final class PurgeService {

    public int purgeProcessedOutboxEvents(Connection c, Timestamp before) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM outbox_events WHERE status = 'SENT' AND processed_at < ?")) {
            ps.setTimestamp(1, before);
            return ps.executeUpdate();
        }
    }

    public int trimAuditLog(Connection c, int maxRows) throws SQLException {
        int count;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM audit");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            count = rs.getInt(1);
        }
        int excess = count - maxRows;
        if (excess <= 0) {
            return 0;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM audit WHERE id IN (SELECT id FROM audit ORDER BY id LIMIT ?)")) {
            ps.setInt(1, excess);
            return ps.executeUpdate();
        }
    }
}
