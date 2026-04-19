package io.github.vesas.jdbcprof;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for a recording session. See spec §10, §5.3, §5.6, §8.3.
 *
 * <p>Defaults mirror the spec. No validation is performed yet; validation
 * moves in when {@link Profiler#start(ProfilerConfig)} becomes real.
 *
 * @param outputFile         path of the binary recording (spec §7)
 * @param ringBufferCapacity events per thread-local ring (spec §5.6 — default 65_536)
 * @param stackDepthLimit    max frames captured per event (spec §5.4 — default 30)
 * @param frameExclusions    packages/classes walked past during attribution (spec §8.1)
 * @param n1MinCount         minimum repetitions before a template is an N+1 candidate (spec §8.3 — default 10)
 * @param n1AncestorFraction fraction of repetitions that must share an ancestor frame (spec §8.3 — default 0.9)
 */
public record ProfilerConfig(
        Path outputFile,
        int ringBufferCapacity,
        int stackDepthLimit,
        List<String> frameExclusions,
        int n1MinCount,
        double n1AncestorFraction) {

    private static final List<String> DEFAULT_EXCLUSIONS = List.of(
            "java.sql.",
            "javax.sql.",
            "com.zaxxer.hikari.",
            "org.apache.tomcat.jdbc.",
            "org.apache.commons.dbcp.",
            "org.hibernate.",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.springframework.jdbc.",
            "org.springframework.orm.",
            "io.github.vesas.jdbcprof.capture.",
            "io.github.vesas.jdbcprof.sink.",
            "io.github.vesas.jdbcprof.storage.");

    public static ProfilerConfig defaults(Path outputFile) {
        return new ProfilerConfig(
                outputFile,
                65_536,
                30,
                DEFAULT_EXCLUSIONS,
                10,
                0.9);
    }

    /**
     * The default frame-exclusion list shared between the capture
     * configuration and the offline analysis layer (spec §8.1).
     */
    public static List<String> defaultFrameExclusions() {
        return DEFAULT_EXCLUSIONS;
    }
}
