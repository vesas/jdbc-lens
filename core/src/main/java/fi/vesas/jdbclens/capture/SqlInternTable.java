package fi.vesas.jdbclens.capture;

import java.util.List;

/**
 * Monotonic, thread-safe mapping from SQL text to a compact int id
 * (spec §5.5). Events reference the id; the text lives once here.
 *
 * <p>Hot path (lookup hit) is lock-free. Lock path (first insertion
 * of a distinct string) is cheap because real applications plateau to
 * a bounded template set within a few thousand events.
 *
 * <p>Callers are responsible for normalising ad-hoc {@code Statement}
 * SQL via {@link SqlTemplateNormalizer} before interning;
 * {@code PreparedStatement} SQL is already in template form and is
 * interned as-is.
 */
public final class SqlInternTable {

    private final InternTable<String> table = new InternTable<>();

    public int intern(String sql) {
        return table.intern(sql);
    }

    public int size() {
        return table.size();
    }

    public String get(int id) {
        return table.get(id);
    }

    /**
     * Returns an immutable snapshot of entries with id {@code >= since}.
     * Used by the sink to flush intern-table deltas.
     */
    public List<String> entriesSince(int since) {
        return table.entriesSince(since);
    }
}
