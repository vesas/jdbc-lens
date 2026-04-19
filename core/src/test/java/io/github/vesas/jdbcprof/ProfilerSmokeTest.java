package io.github.vesas.jdbcprof;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfilerSmokeTest {

    @Test
    void wrapRejectsNull() {
        assertThatThrownBy(() -> Profiler.wrap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stopIsStubbed() {
        assertThatThrownBy(Profiler::stop)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultsReflectSpec() {
        ProfilerConfig cfg = ProfilerConfig.defaults(Path.of("x.jdbclog"));
        assertThat(cfg.ringBufferCapacity()).isEqualTo(65_536);
        assertThat(cfg.stackDepthLimit()).isEqualTo(30);
        assertThat(cfg.n1MinCount()).isEqualTo(10);
        assertThat(cfg.n1AncestorFraction()).isEqualTo(0.9);
        assertThat(cfg.frameExclusions()).contains("java.sql.", "org.hibernate.");
    }
}
