package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates every observed JDBC event by the table(s) it touched,
 * splitting readers from writers, so the report can answer "is table
 * X safe to cache?" with a concrete per-site breakdown.
 *
 * <p>The read side is deliberately greedy: a template that joins
 * three tables counts as a read of all three. Missing a real read
 * would be worse than over-reporting one — the point is to rule out
 * false "this table looks read-only" conclusions.
 *
 * <p>The write side is precise: {@code UPDATE} / {@code DELETE FROM} /
 * {@code INSERT INTO} each have a single primary target, extracted
 * by {@link TemplateShape#writeTarget}. Other tables referenced by
 * the same DML statement (WHERE subquery, UPDATE ... FROM joins) are
 * attributed as reads too — the same execution consulted them.
 */
public final class TableAccessAudit {

    private TableAccessAudit() {
    }

    public static List<TableAccessFinding> detect(
            Map<Integer, String> sqls,
            Map<Long, List<Event>> eventsByOp,
            Map<Integer, StackFrameSnapshot[]> stacks) {

        // Per-sql caches so we parse each template once.
        Map<Integer, Set<String>> readTablesBySql = new HashMap<>();
        Map<Integer, String> writeTargetBySql = new HashMap<>();
        Map<Integer, TemplateShape> shapeBySql = new HashMap<>();

        Map<String, TableAccum> perTable = new LinkedHashMap<>();

        for (Map.Entry<Long, List<Event>> opEntry : eventsByOp.entrySet()) {
            long opId = opEntry.getKey();
            for (Event e : opEntry.getValue()) {
                if (e.sqlId < 0) {
                    continue;
                }
                boolean isRead = e.eventType == EventType.EXECUTE_QUERY.code();
                boolean isWrite = e.eventType == EventType.EXECUTE_UPDATE.code()
                        || e.eventType == EventType.EXECUTE_BATCH.code();
                if (!isRead && !isWrite) {
                    continue;
                }

                String sql = sqls.get(e.sqlId);
                if (sql == null) {
                    continue;
                }

                Set<String> referenced = readTablesBySql.computeIfAbsent(
                        e.sqlId, id -> TemplateShape.referencedTables(sql));
                if (referenced.isEmpty()) {
                    continue;
                }

                if (isRead) {
                    for (String t : referenced) {
                        perTable.computeIfAbsent(t, k -> new TableAccum())
                                .recordRead(e.sqlId, e.stackTraceId, opId);
                    }
                    continue;
                }

                // Write event. Resolve the write target; any other
                // referenced tables are reads (WHERE subquery, joined
                // source in an UPDATE … FROM, etc.).
                String target = writeTargetBySql.computeIfAbsent(
                        e.sqlId, id -> TemplateShape.writeTarget(sql));
                if (target != null) {
                    String kind = classifyWriteKind(sql);
                    TemplateShape shape = shapeBySql.computeIfAbsent(
                            e.sqlId, id -> TemplateShape.of(sql));
                    List<String> setCols = shape == null ? List.of() : shape.setColumns();
                    perTable.computeIfAbsent(target, k -> new TableAccum())
                            .recordWrite(e.sqlId, e.stackTraceId, kind, setCols);
                }
                for (String t : referenced) {
                    if (!t.equals(target)) {
                        perTable.computeIfAbsent(t, k -> new TableAccum())
                                .recordRead(e.sqlId, e.stackTraceId, opId);
                    }
                }
            }
        }

        List<TableAccessFinding> findings = new ArrayList<>(perTable.size());
        for (Map.Entry<String, TableAccum> entry : perTable.entrySet()) {
            findings.add(entry.getValue().freeze(entry.getKey(), sqls, stacks));
        }
        // Sort: prefer tables with the highest read volume; read-only
        // tables (writeEvents == 0) bubble above equally-busy read/write
        // tables because they're the cleanest cache candidates.
        findings.sort(Comparator
                .comparingInt((TableAccessFinding f) -> f.readOnly() ? 0 : 1)
                .thenComparing(Comparator.comparingLong(TableAccessFinding::readEvents).reversed())
                .thenComparing(TableAccessFinding::table));
        return findings;
    }

    private static String classifyWriteKind(String sql) {
        String upper = sql.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("UPDATE")) {
            return "UPDATE";
        }
        if (upper.startsWith("DELETE")) {
            return "DELETE";
        }
        if (upper.startsWith("INSERT")) {
            return "INSERT";
        }
        return "WRITE";
    }

    private static final class TableAccum {
        // Keyed by (sqlId, stackId) to dedupe the per-pair counts.
        private final Map<Long, ReaderEntry> readers = new LinkedHashMap<>();
        private final Map<Long, WriterEntry> writers = new LinkedHashMap<>();
        private final Set<Long> opsWithReads = new HashSet<>();
        private long readEvents;
        private long writeEvents;

        void recordRead(int sqlId, int stackId, long opId) {
            long key = pairKey(sqlId, stackId);
            ReaderEntry r = readers.get(key);
            if (r == null) {
                r = new ReaderEntry(sqlId, stackId);
                readers.put(key, r);
            }
            r.count++;
            readEvents++;
            opsWithReads.add(opId);
        }

        void recordWrite(int sqlId, int stackId, String kind, List<String> setCols) {
            long key = pairKey(sqlId, stackId);
            WriterEntry w = writers.get(key);
            if (w == null) {
                w = new WriterEntry(sqlId, stackId, kind, setCols);
                writers.put(key, w);
            }
            w.count++;
            writeEvents++;
        }

        TableAccessFinding freeze(String table,
                                  Map<Integer, String> sqls,
                                  Map<Integer, StackFrameSnapshot[]> stacks) {
            List<TableAccessFinding.ReaderHit> readerHits = new ArrayList<>(readers.size());
            Set<Integer> readerSites = new HashSet<>();
            Set<Integer> readerTemplates = new HashSet<>();
            for (ReaderEntry r : readers.values()) {
                readerHits.add(new TableAccessFinding.ReaderHit(
                        r.sqlId,
                        sqls.get(r.sqlId),
                        Attribution.callSite(stacks.get(r.stackId)),
                        r.count));
                readerSites.add(r.stackId);
                readerTemplates.add(r.sqlId);
            }
            readerHits.sort(Comparator
                    .comparingLong((TableAccessFinding.ReaderHit h) -> h.eventCount()).reversed());

            List<TableAccessFinding.WriterHit> writerHits = new ArrayList<>(writers.size());
            Set<Integer> writerSites = new HashSet<>();
            for (WriterEntry w : writers.values()) {
                writerHits.add(new TableAccessFinding.WriterHit(
                        w.sqlId,
                        sqls.get(w.sqlId),
                        w.kind,
                        w.setColumns,
                        Attribution.callSite(stacks.get(w.stackId)),
                        w.count));
                writerSites.add(w.stackId);
            }
            writerHits.sort(Comparator
                    .comparingLong((TableAccessFinding.WriterHit h) -> h.eventCount()).reversed());

            return new TableAccessFinding(
                    table,
                    readEvents,
                    writeEvents,
                    readerTemplates.size(),
                    readerSites.size(),
                    writerSites.size(),
                    opsWithReads.size(),
                    readerHits,
                    writerHits);
        }

        private static long pairKey(int sqlId, int stackId) {
            return (((long) sqlId) << 32) | (stackId & 0xFFFFFFFFL);
        }
    }

    private static final class ReaderEntry {
        final int sqlId;
        final int stackId;
        long count;

        ReaderEntry(int sqlId, int stackId) {
            this.sqlId = sqlId;
            this.stackId = stackId;
        }
    }

    private static final class WriterEntry {
        final int sqlId;
        final int stackId;
        final String kind;
        final List<String> setColumns;
        long count;

        WriterEntry(int sqlId, int stackId, String kind, List<String> setColumns) {
            this.sqlId = sqlId;
            this.stackId = stackId;
            this.kind = kind;
            this.setColumns = setColumns;
        }
    }
}
