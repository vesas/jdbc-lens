package fi.vesas.jdbclens.capture;

import fi.vesas.jdbclens.Profiler;
import fi.vesas.jdbclens.ProfilerConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.vesas.jdbclens.storage.BinaryLogReader;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks on the parameterFingerprint field. The fingerprint
 * is computed inside {@code CapturingPreparedStatement} and plumbed
 * through to the event record, so the cleanest way to verify it is to
 * run a real execute and read the event back from the binary log.
 */
class CapturingPreparedStatementTest {

    @AfterEach
    void ensureStopped() {
        try { Profiler.stop(); } catch (Exception ignored) { }
    }

    @Test
    void sameParamsProduceSameFingerprint(@TempDir Path tmp) throws Exception {
        List<Long> fingerprints = recordExecutes(tmp, "fp-same.jdbclog",
                ps -> { ps.setInt(1, 42); ps.executeQuery().close(); },
                ps -> { ps.setInt(1, 42); ps.executeQuery().close(); });
        assertThat(fingerprints).hasSize(2);
        assertThat(fingerprints.get(0)).isNotZero();
        assertThat(fingerprints.get(0)).isEqualTo(fingerprints.get(1));
    }

    @Test
    void differentParamsProduceDifferentFingerprint(@TempDir Path tmp) throws Exception {
        List<Long> fingerprints = recordExecutes(tmp, "fp-diff.jdbclog",
                ps -> { ps.setInt(1, 42); ps.executeQuery().close(); },
                ps -> { ps.setInt(1, 99); ps.executeQuery().close(); });
        assertThat(fingerprints).hasSize(2);
        assertThat(fingerprints.get(0)).isNotEqualTo(fingerprints.get(1));
    }

    @Test
    void rebindingOneParamChangesFingerprint(@TempDir Path tmp) throws Exception {
        List<Long> fingerprints = recordExecutes(tmp, "fp-rebind.jdbclog",
                ps -> { ps.setInt(1, 1); ps.executeQuery().close(); },
                ps -> { ps.setInt(1, 2); ps.executeQuery().close(); });
        assertThat(fingerprints).hasSize(2);
        assertThat(fingerprints.get(0)).isNotEqualTo(fingerprints.get(1));
    }

    @Test
    void clearParametersResetsFingerprint(@TempDir Path tmp) throws Exception {
        List<Long> fingerprints = recordExecutes(tmp, "fp-clear.jdbclog",
                ps -> { ps.setInt(1, 42); ps.executeQuery().close(); },
                ps -> { ps.clearParameters(); ps.setInt(1, 42); ps.executeQuery().close(); });
        // After clearParameters + re-setting the same value, the
        // fingerprint should equal the original execute's.
        assertThat(fingerprints.get(0)).isEqualTo(fingerprints.get(1));
    }

    @Test
    void executesWithoutParamsReportZero(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("fp-none.jdbclog");
        Profiler.start(ProfilerConfig.defaults(log));
        try {
            DataSource ds = Profiler.wrap(newH2());
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT 1 AS v")) {
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rs.getInt(1); }
            }
        } finally {
            Profiler.stop();
        }
        List<Long> fingerprints = collectExecuteFingerprints(log);
        assertThat(fingerprints).singleElement().isEqualTo(0L);
    }

    // --- test plumbing ---

    @FunctionalInterface
    private interface PsAction {
        void run(PreparedStatement ps) throws Exception;
    }

    /**
     * Runs each action against a fresh PreparedStatement that SELECTs
     * its parameter verbatim. Returns the parameterFingerprint of each
     * EXECUTE_QUERY event in the resulting recording, in order.
     */
    private static List<Long> recordExecutes(Path tmp, String file, PsAction... actions) throws Exception {
        Path log = tmp.resolve(file);
        Profiler.start(ProfilerConfig.defaults(log));
        try {
            DataSource ds = Profiler.wrap(newH2());
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT ? AS v")) {
                for (PsAction a : actions) {
                    a.run(ps);
                }
            }
        } finally {
            Profiler.stop();
        }
        return collectExecuteFingerprints(log);
    }

    private static List<Long> collectExecuteFingerprints(Path log) throws Exception {
        List<Long> fingerprints = new ArrayList<>();
        new BinaryLogReader(log).read(new BinaryLogReader.Handler() {
            @Override public void onEvents(List<Event> batch) {
                for (Event e : batch) {
                    if (EventType.fromCode(e.eventType) == EventType.EXECUTE_QUERY) {
                        fingerprints.add(e.parameterFingerprint);
                    }
                }
            }
        });
        return fingerprints;
    }

    private static DataSource newH2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:fp_" + UUID.randomUUID().toString().replace('-', '_')
                + ";DB_CLOSE_DELAY=-1");
        return h2;
    }
}
