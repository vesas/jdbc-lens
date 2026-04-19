package fi.vesas.jdbcprof.capture;

import java.util.List;

/**
 * Immutable snapshot of one PreparedStatement's parameter binding at
 * execute time. Each slot is a display string — {@code "42"},
 * {@code "Alice"}, {@code "null"}, {@code "<8 bytes>"}. Position 0 is
 * unused (JDBC parameter indices are 1-based); later positions mirror
 * the parameter index.
 *
 * <p>Record equality is content-based, which is what the intern table
 * relies on to dedup identical bindings.
 *
 * <p>Only produced when
 * {@link fi.vesas.jdbcprof.ProfilerConfig#captureParameterValues()}
 * is true; otherwise the capture path keeps no slot strings and the
 * intern table stays empty.
 */
public record ParameterValues(List<String> slots) {

    public ParameterValues {
        slots = List.copyOf(slots);
    }
}
