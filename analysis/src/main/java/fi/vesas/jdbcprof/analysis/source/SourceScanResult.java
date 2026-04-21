package fi.vesas.jdbcprof.analysis.source;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-table aggregation of the raw {@link SourceSqlSite}s produced
 * by {@link JavaSourceScanner}. Mirrors the shape of
 * {@code TableAccessFinding} so the HTML renderer can line static
 * and runtime information up row-by-row.
 */
public final class SourceScanResult {

    private final Map<String, TableSites> byTable;

    private SourceScanResult(Map<String, TableSites> byTable) {
        this.byTable = byTable;
    }

    /** Case-insensitive lookup; never returns null (empty TableSites when unknown). */
    public TableSites forTable(String table) {
        TableSites hit = byTable.get(table == null ? "" : table.toLowerCase());
        return hit == null ? TableSites.EMPTY : hit;
    }

    /** All tables observed across the source scan, in a stable order. */
    public List<String> tables() {
        return List.copyOf(byTable.keySet());
    }

    public boolean isEmpty() {
        return byTable.isEmpty();
    }

    public static SourceScanResult of(List<SourceSqlSite> sites) {
        // Tree-ordered for stable display — tables ordered alphabetically.
        Map<String, TableSites.Builder> builders = new TreeMap<>();
        for (SourceSqlSite site : sites) {
            if (site.writeTarget() != null) {
                builders.computeIfAbsent(site.writeTarget(), k -> new TableSites.Builder())
                        .addWriter(site);
            }
            for (String table : site.readTables()) {
                // A DML's primary target also appears in readTables for
                // statements like UPDATE...FROM; record it as a writer
                // there, not a reader.
                if (table.equals(site.writeTarget())) {
                    continue;
                }
                builders.computeIfAbsent(table, k -> new TableSites.Builder())
                        .addReader(site);
            }
        }
        Map<String, TableSites> out = new LinkedHashMap<>();
        for (Map.Entry<String, TableSites.Builder> entry : builders.entrySet()) {
            out.put(entry.getKey(), entry.getValue().build());
        }
        return new SourceScanResult(out);
    }

    /** Empty result used when scanning is skipped or yields nothing. */
    public static SourceScanResult empty() {
        return new SourceScanResult(Map.of());
    }

    public record TableSites(List<SiteRef> readers, List<SiteRef> writers) {
        public static final TableSites EMPTY = new TableSites(List.of(), List.of());

        public TableSites {
            readers = List.copyOf(readers);
            writers = List.copyOf(writers);
        }

        public boolean isEmpty() {
            return readers.isEmpty() && writers.isEmpty();
        }

        static final class Builder {
            private final List<SiteRef> readers = new java.util.ArrayList<>();
            private final List<SiteRef> writers = new java.util.ArrayList<>();

            void addReader(SourceSqlSite s) {
                readers.add(SiteRef.of(s));
            }

            void addWriter(SourceSqlSite s) {
                writers.add(SiteRef.of(s));
            }

            TableSites build() {
                return new TableSites(readers, writers);
            }
        }
    }

    /**
     * Display-facing view of a {@link SourceSqlSite} — just the file,
     * line, kind, and collapsed snippet. Keeps the renderer decoupled
     * from the raw scanner record.
     */
    public record SiteRef(Path file, int line, String kind, String snippet) {
        static SiteRef of(SourceSqlSite s) {
            return new SiteRef(s.file(), s.line(), s.kind(), s.snippet());
        }
    }
}
