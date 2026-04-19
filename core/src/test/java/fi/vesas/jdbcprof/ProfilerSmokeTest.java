package fi.vesas.jdbcprof;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;
import fi.vesas.jdbcprof.storage.BinaryLogReader;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfilerSmokeTest {

    @AfterEach
    void ensureStopped() {
        // Defensive: if a test throws mid-session, leave nothing running.
        try {
            Profiler.stop();
        } catch (Exception ignored) {
        }
    }

    @Test
    void wrapRejectsNull() {
        assertThatThrownBy(() -> Profiler.wrap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrapBeforeStartIsRejected() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:noop");
        assertThatThrownBy(() -> Profiler.wrap(h2))
                .isInstanceOf(IllegalStateException.class);
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

    @Test
    void endToEndProducesReadableRecording(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("e2e.jdbclog");
        ProfilerConfig cfg = new ProfilerConfig(
                log, 64, 20,
                ProfilerConfig.defaults(log).frameExclusions(),
                10, 0.9, false);

        Profiler.start(cfg);
        try {
            DataSource ds = Profiler.wrap(newH2());
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT ? AS v")) {
                ps.setInt(1, 7);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getInt(1);
                    }
                }
            }
        } finally {
            Profiler.stop();
        }

        Collected c = readAll(log);
        assertThat(c.sqls).contains("SELECT ? AS v");
        List<EventType> types = c.events.stream()
                .map(e -> EventType.fromCode(e.eventType))
                .toList();
        assertThat(types).contains(EventType.PREPARE, EventType.EXECUTE_QUERY, EventType.NEXT);
    }

    @Test
    void doubleStartRejected(@TempDir Path tmp) {
        ProfilerConfig cfg = ProfilerConfig.defaults(tmp.resolve("dbl.jdbclog"));
        Profiler.start(cfg);
        try {
            assertThatThrownBy(() -> Profiler.start(cfg))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            Profiler.stop();
        }
    }

    @Test
    void doubleStopIsNoOp(@TempDir Path tmp) {
        Profiler.start(ProfilerConfig.defaults(tmp.resolve("dbl-stop.jdbclog")));
        Profiler.stop();
        Profiler.stop(); // second call: no exception, no side effect
    }

    private static DataSource newH2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:prof_" + UUID.randomUUID().toString().replace('-', '_')
                + ";DB_CLOSE_DELAY=-1");
        return h2;
    }

    @Test
    void operationIdRoundTripsThroughBinaryLog(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("op.jdbclog");
        Profiler.start(ProfilerConfig.defaults(log));
        try {
            DataSource ds = Profiler.wrap(newH2());
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT ? AS v")) {

                Profiler.currentOperation("op-a");
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rs.getInt(1); }

                Profiler.currentOperation("op-b");
                ps.setInt(1, 2);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rs.getInt(1); }

                Profiler.currentOperation(null);
                ps.setInt(1, 3);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rs.getInt(1); }
            }
        } finally {
            Profiler.stop();
        }

        Collected c = readAll(log);
        assertThat(c.ops).containsExactly("op-a", "op-b");

        // Every EXECUTE_QUERY event carries the operation id that was
        // active on its thread at the time of capture. Event order in
        // the log is producer order per thread.
        long[] execOps = c.events.stream()
                .filter(e -> EventType.fromCode(e.eventType) == EventType.EXECUTE_QUERY)
                .mapToLong(e -> e.operationId)
                .toArray();
        assertThat(execOps).hasSize(3);
        assertThat(execOps[0]).isEqualTo(0L);   // first interned → id 0
        assertThat(execOps[1]).isEqualTo(1L);   // second interned
        assertThat(execOps[2]).isEqualTo(-1L);  // cleared with null
    }

    @Test
    void parameterValuesRoundTripWhenCaptureEnabled(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("pv.jdbclog");
        ProfilerConfig cfg = ProfilerConfig.defaults(log).withCaptureParameterValues(true);
        Profiler.start(cfg);
        try {
            DataSource ds = Profiler.wrap(newH2());
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT ? AS v")) {
                ps.setInt(1, 42);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rs.getInt(1); }
            }
        } finally {
            Profiler.stop();
        }

        Collected c = readAll(log);
        assertThat(c.paramValues).hasSize(1);
        assertThat(c.paramValues.get(0).slots()).containsExactly("42");

        int executeValuesId = c.events.stream()
                .filter(e -> EventType.fromCode(e.eventType) == EventType.EXECUTE_QUERY)
                .mapToInt(e -> e.parameterValuesId)
                .findFirst().orElseThrow();
        assertThat(executeValuesId).isEqualTo(0);
    }

    private static Collected readAll(Path log) throws IOException {
        Collected c = new Collected();
        new BinaryLogReader(log).read(new BinaryLogReader.Handler() {
            @Override public void onSqlDelta(int firstId, List<String> sqls) {
                c.sqls.addAll(sqls);
            }
            @Override public void onStackDelta(int firstId, List<StackFrameSnapshot[]> stacks) {
                c.stacks.addAll(stacks);
            }
            @Override public void onOpDelta(int firstId, List<String> names) {
                c.ops.addAll(names);
            }
            @Override public void onParamValuesDelta(int firstId, List<ParameterValues> entries) {
                c.paramValues.addAll(entries);
            }
            @Override public void onEvents(List<Event> batch) {
                c.events.addAll(batch);
            }
        });
        return c;
    }

    private static final class Collected {
        final List<String> sqls = new ArrayList<>();
        final List<StackFrameSnapshot[]> stacks = new ArrayList<>();
        final List<String> ops = new ArrayList<>();
        final List<ParameterValues> paramValues = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
    }
}
