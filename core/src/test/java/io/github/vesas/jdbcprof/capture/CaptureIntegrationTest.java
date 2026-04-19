package io.github.vesas.jdbcprof.capture;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of the capture wrappers against a real driver
 * (in-memory H2). Verifies that each JDBC operation of interest
 * produces the expected event type carrying the correct sqlId, so the
 * ~1000 lines of pass-through delegation across {@link
 * CapturingDataSource}, {@link CapturingConnection}, {@link
 * CapturingStatement}, {@link CapturingPreparedStatement}, and
 * {@link CapturingResultSet} actually route through the
 * instrumented methods rather than the superclass defaults.
 */
class CaptureIntegrationTest {

    private CaptureContext ctx;
    private CapturingDataSource ds;

    @BeforeEach
    void setUp() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:cap_" + UUID.randomUUID().toString().replace('-', '_')
                + ";DB_CLOSE_DELAY=-1");
        ctx = new CaptureContext(64, 20);
        ds = new CapturingDataSource(h2, ctx);
    }

    @Test
    void preparedStatementRecordsPrepareQueryNextAndClose() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT ? AS v")) {
            ps.setInt(1, 42);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getInt(1);
                }
            }
        }

        List<Event> events = drainAll();

        int sqlId = ctx.sqlIntern().intern("SELECT ? AS v");

        assertThat(events).extracting(e -> EventType.fromCode(e.eventType))
                .contains(EventType.PREPARE, EventType.EXECUTE_QUERY,
                          EventType.NEXT, EventType.CLOSE);

        assertThat(eventsOfType(events, EventType.PREPARE))
                .singleElement()
                .satisfies(e -> assertThat(e.sqlId).isEqualTo(sqlId));
        assertThat(eventsOfType(events, EventType.EXECUTE_QUERY))
                .singleElement()
                .satisfies(e -> assertThat(e.sqlId).isEqualTo(sqlId));

        List<Event> nexts = eventsOfType(events, EventType.NEXT);
        assertThat(nexts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(nexts).allSatisfy(e -> assertThat(e.sqlId).isEqualTo(sqlId));

        assertThat(events).allSatisfy(e -> assertThat(e.durationNanos).isGreaterThanOrEqualTo(0L));
    }

    @Test
    void adHocStatementNormalizesLiteralsToSameTemplate() throws Exception {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t(id INT)");
            st.executeUpdate("INSERT INTO t VALUES (42)");
            st.executeUpdate("INSERT INTO t VALUES (99)");
        }

        List<Event> events = drainAll();

        int templateId = ctx.sqlIntern().intern("INSERT INTO t VALUES (?)");
        List<Event> updates = eventsOfType(events, EventType.EXECUTE_UPDATE);

        assertThat(updates).hasSizeGreaterThanOrEqualTo(2);
        long inserts = updates.stream().filter(e -> e.sqlId == templateId).count();
        assertThat(inserts).isEqualTo(2L);
    }

    @Test
    void executeBatchRecordsBatchSize() throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (Statement ddl = conn.createStatement()) {
                ddl.execute("CREATE TABLE t(id INT)");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
                ps.setInt(1, 1); ps.addBatch();
                ps.setInt(1, 2); ps.addBatch();
                ps.setInt(1, 3); ps.addBatch();
                ps.executeBatch();
            }
        }

        List<Event> batches = eventsOfType(drainAll(), EventType.EXECUTE_BATCH);
        assertThat(batches).singleElement()
                .satisfies(e -> {
                    assertThat(e.batchSize).isEqualTo(3);
                    assertThat(ctx.sqlIntern().get(e.sqlId))
                            .isEqualTo("INSERT INTO t VALUES (?)");
                });
    }

    @Test
    void commitAndRollbackAreCaptured() throws Exception {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE t(id INT)");
            }
            conn.commit();
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
            }
            conn.rollback();
        }

        List<Event> events = drainAll();
        assertThat(eventsOfType(events, EventType.COMMIT)).hasSize(1);
        assertThat(eventsOfType(events, EventType.ROLLBACK)).hasSize(1);
    }

    @Test
    void resultSetWrapsAndAttributesNextToProducingStatement() throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE t(id INT)");
                st.executeUpdate("INSERT INTO t VALUES (1)");
                st.executeUpdate("INSERT INTO t VALUES (2)");
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM t ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    rs.getInt(1);
                    count++;
                }
                assertThat(count).isEqualTo(2);
            }
        }

        int selectId = ctx.sqlIntern().intern("SELECT id FROM t ORDER BY id");
        List<Event> nexts = eventsOfType(drainAll(), EventType.NEXT);

        // Two rows + one terminating false-returning next(), all attributed to the SELECT.
        assertThat(nexts).hasSize(3);
        assertThat(nexts).allSatisfy(e -> assertThat(e.sqlId).isEqualTo(selectId));
    }

    private List<Event> drainAll() {
        List<Event> out = new ArrayList<>();
        Event[] buf = new Event[64];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = new Event();
        }
        for (SpscRingBuffer ring : ctx.allRings()) {
            int n;
            while ((n = ring.drain(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    Event copy = new Event();
                    copy.copyFrom(buf[i]);
                    out.add(copy);
                }
            }
        }
        return out;
    }

    private static List<Event> eventsOfType(List<Event> events, EventType type) {
        byte code = type.code();
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.eventType == code) {
                out.add(e);
            }
        }
        return out;
    }
}
