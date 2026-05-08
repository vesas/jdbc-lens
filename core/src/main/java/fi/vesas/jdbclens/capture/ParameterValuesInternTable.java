package fi.vesas.jdbclens.capture;

import java.util.List;

/**
 * Interns distinct {@link ParameterValues} bindings seen during a
 * recording when
 * {@link fi.vesas.jdbclens.ProfilerConfig#captureParameterValues()}
 * is enabled.
 *
 * <p>Content-keyed lookup, monotonic dense int ids, delta snapshotting
 * for the sink. The hot path is a lock-free lookup; the miss path
 * synchronises on {@code this} to assign a new id.
 */
public final class ParameterValuesInternTable {

    private final InternTable<ParameterValues> table = new InternTable<>();

    public int intern(ParameterValues values) {
        return table.intern(values);
    }

    public int size() {
        return table.size();
    }

    public ParameterValues get(int id) {
        return table.get(id);
    }

    public List<ParameterValues> entriesSince(int since) {
        return table.entriesSince(since);
    }
}
