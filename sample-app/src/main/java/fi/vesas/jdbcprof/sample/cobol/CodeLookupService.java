package fi.vesas.jdbcprof.sample.cobol;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Transpiled from COBOL {@code COPY} expansion of a code-translation
 * subroutine. The original COBOL lived as a single copybook —
 * {@code COPY CODE-LOOKUP} — pasted into every paragraph that needed
 * to turn a code into a description. The transpiler faithfully
 * re-emits the copybook as a fresh Java method at every call-site,
 * rather than extracting a shared utility. Each copy opens its own
 * {@link PreparedStatement} with the same SQL text.
 *
 * <p>To the profiler this looks like a single SQL id
 * ({@code SELECT v FROM settings WHERE k = ?}) charged against three
 * distinct frames — {@link #resolveCurrencyLabel},
 * {@link #resolveStatusLabel}, {@link #resolveShippingLabel} — which
 * is exactly the call-site-attribution case the report is designed
 * to surface. The detector should also flag the queries as
 * cacheable-repeat: three distinct keys, fired once per order in the
 * outer loop.
 */
public final class CodeLookupService {

    public void seedCodes(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "MERGE INTO SETTINGS (K, V) KEY(K) VALUES (?, ?)")) {
            setRow(ps, "currency_label_EUR", "Euro");
            ps.executeUpdate();
            setRow(ps, "status_label_OPEN", "Open");
            ps.executeUpdate();
            setRow(ps, "shipping_label_STD", "Standard");
            ps.executeUpdate();
        }
    }

    private void setRow(PreparedStatement ps, String k, String v) throws SQLException {
        ps.setString(1, k);
        ps.setString(2, v);
    }

    public int enrich(Connection c, int[] orderIds) throws SQLException {
        int wsEnriched = 0;
        for (int wsOrderId : orderIds) {
            OrderRow wsOrder = readOrder(c, wsOrderId);
            if (wsOrder == null) {
                continue;
            }
            String wsCurrencyLabel = resolveCurrencyLabel(c, "currency_label_EUR");
            String wsStatusLabel = resolveStatusLabel(c, "status_label_OPEN");
            String wsShippingLabel = resolveShippingLabel(c, "shipping_label_STD");
            if (wsCurrencyLabel != null || wsStatusLabel != null || wsShippingLabel != null) {
                wsEnriched++;
            }
        }
        return wsEnriched;
    }

    private OrderRow readOrder(Connection c, int wsOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ID, AMOUNT, STATUS FROM ORDERS WHERE ID = ?")) {
            ps.setInt(1, wsOrderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new OrderRow(rs.getInt(1), rs.getBigDecimal(2), rs.getString(3))
                        : null;
            }
        }
    }

    private String resolveCurrencyLabel(Connection c, String wsKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT V FROM SETTINGS WHERE K = ?")) {
            ps.setString(1, wsKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String resolveStatusLabel(Connection c, String wsKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT V FROM SETTINGS WHERE K = ?")) {
            ps.setString(1, wsKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String resolveShippingLabel(Connection c, String wsKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT V FROM SETTINGS WHERE K = ?")) {
            ps.setString(1, wsKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private record OrderRow(int id, BigDecimal amount, String status) {
    }
}
