package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

/**
 * One emulated-cursor pair surfaced by {@link EmulatedCursorDetector}
 * (spec §8.3 extension for COBOL-transpiled code).
 *
 * <p>The signature is a {@code SELECT MIN|MAX(key) ... WHERE key &gt; ?}
 * walk ({@code walkSql}) paired with a {@code SELECT ... WHERE key = ?}
 * fetch ({@code fetchSql}) on the same table, issued interleaved from
 * the same outer method — the SQL shape of a COBOL {@code READ NEXT}
 * loop that the transpiler could not collapse back into a single
 * {@code ResultSet} scan.
 *
 * <p>{@code nested} is true when this pair runs inside another emulated
 * cursor in the same recording (the transpiled {@code PERFORM UNTIL}
 * inside {@code PERFORM UNTIL} pattern); {@code outerAncestor} is then
 * the outer pair's shared method. A nested pair is the classic
 * candidate for replacement with a single {@code JOIN ... GROUP BY}.
 */
public record EmulatedCursorFinding(
        int walkSqlId,
        String walkSql,
        StackFrameSnapshot walkSite,
        int fetchSqlId,
        String fetchSql,
        StackFrameSnapshot fetchSite,
        String table,
        String keyColumn,
        StackFrameSnapshot ancestor,
        long walkCount,
        long fetchCount,
        long totalDurationNanos,
        boolean nested,
        StackFrameSnapshot outerAncestor) {
}
