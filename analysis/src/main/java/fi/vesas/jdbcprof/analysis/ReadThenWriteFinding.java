package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

/**
 * One read-then-write pair on the same entity inside a single
 * operation: a SELECT on {@code (table, column, value)} followed by
 * an UPDATE/DELETE on the same key. Legacy translation of READ +
 * MODIFY + REWRITE that usually collapses to a single {@code UPDATE
 * … RETURNING} or an {@code UPDATE} whose WHERE already
 * incorporates the checks the read was doing.
 */
public record ReadThenWriteFinding(
        long opId,
        String opName,
        String table,
        String column,
        String value,
        int readSqlId,
        String readSql,
        StackFrameSnapshot readCallSite,
        long readDurationNanos,
        int writeSqlId,
        String writeSql,
        StackFrameSnapshot writeCallSite,
        long writeDurationNanos,
        long betweenNanos) {
}
