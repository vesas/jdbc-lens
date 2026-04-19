package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.List;

/**
 * An UPDATE template whose SET clause mentions more columns than the
 * detector's threshold. Legacy COBOL conversions often translate
 * {@code REWRITE RECORD} as "overwrite every column" regardless of
 * what actually changed — wastes write volume, fires triggers and
 * replication for no-op changes, and masks real diffs in audit
 * logs.
 *
 * <p>The representative call-site is the {@link Attribution}-selected
 * frame of the stack most frequently associated with this template's
 * executes.
 */
public record OverWideUpdateFinding(
        int sqlId,
        String sql,
        String table,
        List<String> setColumns,
        StackFrameSnapshot representativeSite,
        long executeCount,
        long totalDurationNanos) {

    public OverWideUpdateFinding {
        setColumns = List.copyOf(setColumns);
    }

    public int setColumnCount() {
        return setColumns.size();
    }
}
