package fi.vesas.jdbclens.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monotonic, thread-safe mapping from SQL text to a compact int id
 * (spec §5.5). Events reference the id; the text lives once here.
 *
 * <p>Hot path (lookup hit) is lock-free via {@link ConcurrentHashMap}.
 * Lock path (first insertion of a distinct string) synchronises on
 * {@code this} — cheap because real applications plateau to a bounded
 * template set within a few thousand events.
 *
 * <p>Id assignment is monotonic and gap-free: entry {@code i} in
 * {@link #entriesSince(int)} always has id {@code since + i}. The
 * sink relies on this to write intern-table deltas (spec §6).
 *
 * <p>Callers are responsible for normalising ad-hoc {@code Statement}
 * SQL via {@link SqlTemplateNormalizer} before interning;
 * {@code PreparedStatement} SQL is already in template form and is
 * interned as-is.
 */
public final class SqlInternTable {

    private final ConcurrentHashMap<String, Integer> index = new ConcurrentHashMap<>();
    private final List<String> entries = new ArrayList<>();

    public int intern(String sql) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }
        Integer hit = index.get(sql);
        if (hit != null) {
            return hit;
        }
        synchronized (this) {
            hit = index.get(sql);
            if (hit != null) {
                return hit;
            }
            int id = entries.size();
            entries.add(sql);
            index.put(sql, id);
            return id;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized String get(int id) {
        return entries.get(id);
    }

    /**
     * Returns an immutable snapshot of entries with id {@code >= since}.
     * Used by the sink to flush intern-table deltas.
     */
    public synchronized List<String> entriesSince(int since) {
        int n = entries.size();
        if (since >= n) {
            return List.of();
        }
        if (since < 0) {
            throw new IllegalArgumentException("since must be >= 0, got " + since);
        }
        return Collections.unmodifiableList(new ArrayList<>(entries.subList(since, n)));
    }
}
