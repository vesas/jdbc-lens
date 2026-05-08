package fi.vesas.jdbclens.capture;

import java.util.List;

/**
 * Monotonic, thread-safe mapping from operation name (a free-form
 * string the application supplies, e.g. {@code "/orders/checkout"} or
 * a test method name) to a compact int id (spec §3, §10).
 *
 * <p>Exercised far less often than {@link SqlInternTable} — applications
 * call {@link fi.vesas.jdbclens.Profiler#currentOperation(String)} at
 * operation boundaries, not per-query.
 *
 * <p>Ids start at {@code 0}; {@code -1} is reserved by {@link Event}
 * to mean "no operation set" so the same field can distinguish a
 * missing op from a valid one.
 */
public final class OperationInternTable {

    private final InternTable<String> table = new InternTable<>();

    public int intern(String name) {
        return table.intern(name);
    }

    public int size() {
        return table.size();
    }

    public String get(int id) {
        return table.get(id);
    }

    /**
     * Immutable snapshot of entries with id {@code >= since}. Used by
     * the sink to flush intern-table deltas to the binary log.
     */
    public List<String> entriesSince(int since) {
        return table.entriesSince(since);
    }
}
