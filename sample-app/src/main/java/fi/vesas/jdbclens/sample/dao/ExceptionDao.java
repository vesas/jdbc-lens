package fi.vesas.jdbclens.sample.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Write-side DAO for reconciliation exceptions. Each mismatch
 * detected by the reconciliation job produces one row here, plus an
 * outbox event that eventually drops a notification into the audit
 * table. Enterprise parlance: "tripped a control; raise an
 * exception; notify downstream."
 */
public final class ExceptionDao {

    public void record(Connection c, int customerId,
                       BigDecimal expected, BigDecimal actual,
                       String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO reconciliation_exceptions "
                        + "(customer_id, expected, actual, reason, detected_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, customerId);
            ps.setBigDecimal(2, expected);
            ps.setBigDecimal(3, actual);
            ps.setString(4, reason);
            ps.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }
}
