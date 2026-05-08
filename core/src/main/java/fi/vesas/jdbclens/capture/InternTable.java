package fi.vesas.jdbclens.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monotonic, thread-safe mapping from a value to a compact int id.
 * Hot-path lookup is lock-free via {@link ConcurrentHashMap}; first
 * insertion of a distinct value synchronises on {@code this}.
 *
 * <p>Id assignment is monotonic and gap-free: entry {@code i} in
 * {@link #entriesSince(int)} always has id {@code since + i}. The
 * sink relies on this to write intern-table deltas (spec §6).
 */
final class InternTable<T> {

    private final ConcurrentHashMap<T, Integer> index = new ConcurrentHashMap<>();
    private final List<T> entries = new ArrayList<>();

    public int intern(T value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        Integer hit = index.get(value);
        if (hit != null) return hit;
        synchronized (this) {
            hit = index.get(value);
            if (hit != null) return hit;
            int id = entries.size();
            entries.add(value);
            index.put(value, id);
            return id;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized T get(int id) {
        return entries.get(id);
    }

    /**
     * Returns an immutable snapshot of entries with id {@code >= since}.
     * Used by the sink to flush intern-table deltas.
     */
    public synchronized List<T> entriesSince(int since) {
        int n = entries.size();
        if (since >= n) return List.of();
        if (since < 0) throw new IllegalArgumentException("since must be >= 0, got " + since);
        return Collections.unmodifiableList(new ArrayList<>(entries.subList(since, n)));
    }
}
