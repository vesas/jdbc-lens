package fi.vesas.jdbclens.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interns distinct {@link ParameterValues} bindings seen during a
 * recording when
 * {@link fi.vesas.jdbclens.ProfilerConfig#captureParameterValues()}
 * is enabled.
 *
 * <p>Same shape as {@link SqlInternTable}: content-keyed lookup,
 * monotonic dense int ids, delta snapshotting for the sink. The hot
 * path is a {@link ConcurrentHashMap} {@code get}; the miss path
 * synchronises on {@code this} to assign a new id.
 */
public final class ParameterValuesInternTable {

    private final ConcurrentHashMap<ParameterValues, Integer> index = new ConcurrentHashMap<>();
    private final List<ParameterValues> entries = new ArrayList<>();

    public int intern(ParameterValues values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        Integer hit = index.get(values);
        if (hit != null) {
            return hit;
        }
        synchronized (this) {
            hit = index.get(values);
            if (hit != null) {
                return hit;
            }
            int id = entries.size();
            entries.add(values);
            index.put(values, id);
            return id;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized ParameterValues get(int id) {
        return entries.get(id);
    }

    public synchronized List<ParameterValues> entriesSince(int since) {
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
