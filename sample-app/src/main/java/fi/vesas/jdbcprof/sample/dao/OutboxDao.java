package fi.vesas.jdbcprof.sample.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional-outbox table access. The enterprise pattern: business
 * code writes a row into {@code outbox_events} inside the same TX as
 * the state change it describes; a separate dispatcher drains the
 * outbox asynchronously so external delivery doesn't block the
 * caller. DAO stays dumb — status transitions live in the dispatcher.
 */
public final class OutboxDao {

    public record Event(int id, String kind, String payload, int attempts) {
    }

    public void enqueue(Connection c, String kind, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO outbox_events (kind, payload, status, created_at) "
                        + "VALUES (?, ?, 'PENDING', ?)")) {
            ps.setString(1, kind);
            ps.setString(2, payload);
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    public List<Event> listPending(Connection c, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, kind, payload, attempts FROM outbox_events "
                        + "WHERE status = ? ORDER BY id LIMIT ?")) {
            ps.setString(1, "PENDING");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Event> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Event(rs.getInt(1), rs.getString(2),
                            rs.getString(3), rs.getInt(4)));
                }
                return out;
            }
        }
    }

    public void markSent(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox_events SET status = 'SENT', processed_at = ? "
                        + "WHERE id = ?")) {
            ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void markFailed(Connection c, int id, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox_events SET status = 'FAILED', "
                        + "attempts = attempts + 1, last_error = ? WHERE id = ?")) {
            ps.setString(1, error);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }
}
