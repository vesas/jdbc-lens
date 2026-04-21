package fi.vesas.jdbcprof.analysis.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One SQL string literal found in source code: the file and line it
 * lives on, the tables it references, its DML write target (if any),
 * and a short snippet for display. Feeds {@link SourceScanResult}.
 *
 * <p>{@code kind} is one of {@code SELECT}, {@code UPDATE},
 * {@code DELETE}, {@code INSERT}, or {@code OTHER} when the lexer
 * saw a SQL-keyword-prefixed literal but the shape parser couldn't
 * classify it.
 */
public record SourceSqlSite(
        Path file,
        int line,
        Set<String> readTables,
        String writeTarget,
        String kind,
        String snippet) {

    public SourceSqlSite {
        readTables = Set.copyOf(readTables);
    }

    public boolean isWrite() {
        return writeTarget != null;
    }

    /** Every table touched by this site (readers and the write target). */
    public List<String> allTables() {
        if (writeTarget == null) {
            return List.copyOf(readTables);
        }
        if (readTables.contains(writeTarget)) {
            return List.copyOf(readTables);
        }
        List<String> merged = new java.util.ArrayList<>(readTables.size() + 1);
        merged.addAll(readTables);
        merged.add(writeTarget);
        return List.copyOf(merged);
    }
}
