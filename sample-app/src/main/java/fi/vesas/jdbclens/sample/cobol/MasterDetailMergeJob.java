package fi.vesas.jdbclens.sample.cobol;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Transpiled from the classic two-file merge:
 *
 * <pre>
 *     PERFORM UNTIL WS-MASTER-EOF
 *         READ CUSTOMER-MASTER NEXT RECORD
 *             AT END MOVE 'Y' TO WS-MASTER-EOF
 *         END-READ
 *         PERFORM UNTIL WS-DETAIL-EOF
 *             READ ORDER-DETAIL NEXT RECORD
 *                 AT END MOVE 'Y' TO WS-DETAIL-EOF
 *             END-READ
 *             ADD WS-AMOUNT TO WS-RUN-TOTAL
 *         END-PERFORM
 *         PERFORM WRITE-MASTER-BREAK
 *     END-PERFORM.
 * </pre>
 *
 * <p>A modern SQL author would write one {@code SELECT customer_id,
 * SUM(amount) ... GROUP BY customer_id} and a single insert per
 * master. The transpiler cannot recover set semantics from the
 * paragraph-by-paragraph source, so it emits two nested cursor
 * emulations — one for the master file, one for the detail file —
 * each walking via {@code SELECT MIN(ID) WHERE ID > ?} instead of
 * iterating a {@link ResultSet}. The result is N+1², with a deep
 * frame stack the profiler can attribute against
 * {@code processMasterRecord} and {@code processDetailRecord}.
 */
public final class MasterDetailMergeJob {

    public int run(Connection c, int maxMasters) throws SQLException {
        int wsMasterCount = 0;
        int wsLastMasterKey = 0;
        while (wsMasterCount < maxMasters) {
            int wsMasterId = readNextMaster(c, wsLastMasterKey);
            if (wsMasterId == 0) {
                break;
            }
            MasterFields wsMaster = readMasterFields(c, wsMasterId);
            if (wsMaster == null) {
                break;
            }
            BigDecimal wsRunTotal = processDetailRecords(c, wsMasterId);
            writeMasterBreak(c, wsMaster, wsRunTotal);
            wsLastMasterKey = wsMasterId;
            wsMasterCount++;
        }
        return wsMasterCount;
    }

    private BigDecimal processDetailRecords(Connection c, int wsMasterId) throws SQLException {
        BigDecimal wsRunTotal = BigDecimal.ZERO;
        int wsLastDetailKey = 0;
        while (true) {
            int wsDetailId = readNextDetail(c, wsMasterId, wsLastDetailKey);
            if (wsDetailId == 0) {
                break;
            }
            DetailFields wsDetail = readDetailFields(c, wsDetailId);
            if (wsDetail == null) {
                break;
            }
            if (wsDetail.amount() != null) {
                wsRunTotal = wsRunTotal.add(wsDetail.amount());
            }
            wsLastDetailKey = wsDetailId;
        }
        return wsRunTotal;
    }

    private int readNextMaster(Connection c, int wsLastKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MIN(ID) FROM CUSTOMERS WHERE ID > ?")) {
            ps.setInt(1, wsLastKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                int wsNext = rs.getInt(1);
                return rs.wasNull() ? 0 : wsNext;
            }
        }
    }

    private MasterFields readMasterFields(Connection c, int wsMasterId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ID, NAME FROM CUSTOMERS WHERE ID = ?")) {
            ps.setInt(1, wsMasterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new MasterFields(rs.getInt(1), rs.getString(2))
                        : null;
            }
        }
    }

    private int readNextDetail(Connection c, int wsMasterId, int wsLastDetailKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MIN(ID) FROM ORDERS WHERE CUSTOMER_ID = ? AND ID > ?")) {
            ps.setInt(1, wsMasterId);
            ps.setInt(2, wsLastDetailKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                int wsNext = rs.getInt(1);
                return rs.wasNull() ? 0 : wsNext;
            }
        }
    }

    private DetailFields readDetailFields(Connection c, int wsDetailId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ID, AMOUNT, STATUS FROM ORDERS WHERE ID = ?")) {
            ps.setInt(1, wsDetailId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new DetailFields(
                                rs.getInt(1),
                                rs.getBigDecimal(2),
                                rs.getString(3))
                        : null;
            }
        }
    }

    private void writeMasterBreak(Connection c, MasterFields wsMaster, BigDecimal wsRunTotal)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO AUDIT (KIND, PAYLOAD, TS) VALUES (?, ?, ?)")) {
            ps.setString(1, "master-detail-rollup");
            ps.setString(2, "customer=" + wsMaster.id()
                    + " name=" + wsMaster.name()
                    + " total=" + wsRunTotal.toPlainString());
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    private record MasterFields(int id, String name) {
    }

    private record DetailFields(int id, BigDecimal amount, String status) {
    }
}
