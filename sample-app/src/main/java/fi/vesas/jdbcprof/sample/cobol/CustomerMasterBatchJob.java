package fi.vesas.jdbcprof.sample.cobol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Transpiled from the COBOL paragraph shape:
 *
 * <pre>
 *     OPEN INPUT CUSTOMER-MASTER.
 *     PERFORM UNTIL WS-EOF
 *         READ CUSTOMER-MASTER NEXT RECORD
 *             AT END MOVE 'Y' TO WS-EOF
 *         END-READ
 *         PERFORM REWRITE-MASTER
 *         EXEC SQL COMMIT END-EXEC
 *     END-PERFORM.
 *     CLOSE CUSTOMER-MASTER.
 * </pre>
 *
 * <p>The naive Java that falls out of such a transpile does not know
 * about JDBC cursors or batched UPDATEs. Each COBOL verb becomes its
 * own {@code EXEC SQL} block, which in turn becomes its own
 * {@link PreparedStatement} prepared-and-closed inside the loop. The
 * results are visible to the profiler as:
 *
 * <ul>
 *   <li>A "next-key probe" SELECT and a "read record" SELECT — two
 *       reads per iteration just to walk the table.</li>
 *   <li>A "REWRITE" UPDATE touching the columns COBOL would have
 *       rewritten in its record buffer.</li>
 *   <li>An "INVALID KEY" COUNT(*) after every UPDATE, the transpiled
 *       equivalent of the COBOL {@code INVALID KEY} handler.</li>
 *   <li>A {@code COMMIT} per record — COBOL's checkpoint-every-record
 *       habit, which the profiler sees as rapid-fire transaction
 *       boundaries.</li>
 * </ul>
 */
public final class CustomerMasterBatchJob {

    public int run(Connection c, int maxRecords) throws SQLException {
        boolean wsPrevAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        int wsProcessed = 0;
        try {
            int wsLastKey = 0;
            while (wsProcessed < maxRecords) {
                int wsCustId = readNextKey(c, wsLastKey);
                if (wsCustId == 0) {
                    break;
                }
                MasterRecord wsRec = readMasterRecord(c, wsCustId);
                if (wsRec == null) {
                    break;
                }
                MasterRecord wsMutated = new MasterRecord(
                        wsRec.id(),
                        wsRec.name() + "*",
                        wsRec.email(),
                        wsRec.country() == null ? null : wsRec.country().toUpperCase());
                rewriteMasterRecord(c, wsMutated);
                invalidKeyCheck(c, wsCustId);
                checkpointRecord(c);
                wsLastKey = wsCustId;
                wsProcessed++;
            }
            return wsProcessed;
        } finally {
            c.setAutoCommit(wsPrevAutoCommit);
        }
    }

    private int readNextKey(Connection c, int wsLastKey) throws SQLException {
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

    private MasterRecord readMasterRecord(Connection c, int wsCustId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ID, NAME, EMAIL, COUNTRY FROM CUSTOMERS WHERE ID = ?")) {
            ps.setInt(1, wsCustId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new MasterRecord(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4));
            }
        }
    }

    private void rewriteMasterRecord(Connection c, MasterRecord wsRec) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE CUSTOMERS SET NAME = ?, COUNTRY = ? WHERE ID = ?")) {
            ps.setString(1, wsRec.name());
            ps.setString(2, wsRec.country());
            ps.setInt(3, wsRec.id());
            ps.executeUpdate();
        }
    }

    private void invalidKeyCheck(Connection c, int wsCustId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM CUSTOMERS WHERE ID = ?")) {
            ps.setInt(1, wsCustId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rs.getInt(1);
            }
        }
    }

    private void checkpointRecord(Connection c) throws SQLException {
        c.commit();
    }

    private record MasterRecord(int id, String name, String email, String country) {
    }
}
