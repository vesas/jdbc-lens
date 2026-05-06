package fi.vesas.jdbcprof.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monotonic, thread-safe mapping from operation name (a free-form
 * string the application supplies, e.g. {@code "/orders/checkout"} or
 * a test method name) to a compact int id (spec §3, §10).
 *
 * <p>Same shape as {@link SqlInternTable} but exercised far less
 * often — applications call {@link fi.vesas.jdbcprof.Profiler#currentOperation(String)}
 * at operation boundaries, not per-query. So the
 * {@link ConcurrentHashMap} hit path is comfortable and the
 * synchronized miss path is acceptable.
 *
 * <p>Ids start at {@code 0}; {@code -1} is reserved by {@link Event}
 * to mean "no operation set" so the same field can distinguish a
 * missing op from a valid one.
 */
public final class OperationInternTable {

    private final ConcurrentHashMap<String, Integer> index = new ConcurrentHashMap<>();
    private final List<String> entries = new ArrayList<>();

    public int intern(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        Integer hit = index.get(name);
        if (hit != null) {
            return hit;
        }
        synchronized (this) {
            hit = index.get(name);
            if (hit != null) {
                return hit;
            }
            int id = entries.size();
            entries.add(name);
            index.put(name, id);
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
     * Immutable snapshot of entries with id {@code >= since}. Used by
     * the sink to flush intern-table deltas to the binary log.
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
