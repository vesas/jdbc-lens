package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.List;

/**
 * All observed access to one table across the recording, split into
 * readers and writers with call-site attribution. Powers the
 * cache-safety section of the report: a row-by-row answer to "who
 * reads this table, who writes it, and can I safely put a cache in
 * front of it?"
 *
 * <p>"Read-only in this recording" is the strongest cache signal but
 * only ever a lower bound — writes that weren't exercised during
 * capture stay invisible. Flag it; don't claim global read-only-ness.
 */
public record TableAccessFinding(
        String table,
        long readEvents,
        long writeEvents,
        int distinctReadTemplates,
        int distinctReaderCallSites,
        int distinctWriterCallSites,
        int opsWithReads,
        List<ReaderHit> readers,
        List<WriterHit> writers) {

    public TableAccessFinding {
        readers = List.copyOf(readers);
        writers = List.copyOf(writers);
    }

    public boolean readOnly() {
        return writeEvents == 0L;
    }

    /**
     * A single (template, call-site) pair that reads this table, with
     * the total number of read events attributed to it. A template
     * that joins the table counts here too — regex extraction doesn't
     * distinguish "primary FROM" from "joined".
     */
    public record ReaderHit(
            int sqlId,
            String sql,
            StackFrameSnapshot callSite,
            long eventCount) {
    }

    /**
     * A single (template, call-site) pair that writes this table. The
     * {@code kind} is the DML verb ({@code UPDATE} / {@code DELETE} /
     * {@code INSERT}). {@code setColumns} is populated for UPDATEs
     * only; empty otherwise. Both inform cache-invalidation design —
     * an UPDATE that only touches column X can often be scoped to
     * invalidate fewer cache entries than one that rewrites every
     * column.
     */
    public record WriterHit(
            int sqlId,
            String sql,
            String kind,
            List<String> setColumns,
            StackFrameSnapshot callSite,
            long eventCount) {

        public WriterHit {
            setColumns = List.copyOf(setColumns);
        }
    }
}
