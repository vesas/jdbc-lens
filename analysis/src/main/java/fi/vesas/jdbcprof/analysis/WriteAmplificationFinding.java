package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.List;

/**
 * Two or more UPDATEs on the same row inside one operation. Usually
 * separate services each setting "their" columns without knowing the
 * others run; collapsible into a single UPDATE that sets everything
 * at once. Distinct from {@link OverWideUpdateFinding} (one call
 * setting too many columns) and {@link ReadThenWriteFinding} (SELECT
 * + UPDATE on the same row).
 *
 * <p>The {@link Overlap} classification tells you the flavour:
 * {@link Overlap#DISJOINT disjoint} column sets merge cleanly;
 * {@link Overlap#OVERLAPPING overlapping} means some columns are
 * written twice (the later write wins — usually a bug or stale
 * copy); {@link Overlap#IDENTICAL identical} SET clauses are pure
 * redundancy.
 */
public record WriteAmplificationFinding(
        long opId,
        String opName,
        String table,
        String column,
        String value,
        List<UpdateHit> hits,
        long totalDurationNanos,
        Overlap overlap) {

    public WriteAmplificationFinding {
        hits = List.copyOf(hits);
    }

    /** One UPDATE execution inside the finding. */
    public record UpdateHit(
            int sqlId,
            String sql,
            List<String> setColumns,
            StackFrameSnapshot callSite,
            long durationNanos,
            long timestampNanos) {
        public UpdateHit {
            setColumns = List.copyOf(setColumns);
        }
    }

    public enum Overlap {
        /** Each UPDATE sets a disjoint set of columns — merges cleanly. */
        DISJOINT,
        /** Some columns are set more than once across the UPDATEs. */
        OVERLAPPING,
        /** Every UPDATE sets exactly the same columns — pure redundancy. */
        IDENTICAL
    }
}
