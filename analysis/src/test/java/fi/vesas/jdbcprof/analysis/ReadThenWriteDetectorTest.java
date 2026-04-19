package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadThenWriteDetectorTest {

    private static final long NO_OP = -1L;

    @Test
    void selectThenUpdateOnSameKeyIsFlagged() {
        Harness h = new Harness();
        int readSql = h.addSql("SELECT name FROM customers WHERE id = ?");
        int writeSql = h.addSql("UPDATE customers SET name = ? WHERE id = ?");
        int pvRead = h.addParamValues("42");
        int pvWrite = h.addParamValues("Alice", "42");
        int stack = h.addStack("com.example.ProfileDao", "updateProfile", 44);
        h.addOp(1L, "update-profile");
        h.addEvent(1L, readSql, stack, pvRead, 100L, EventType.EXECUTE_QUERY);
        h.addEvent(1L, writeSql, stack, pvWrite, 200L, EventType.EXECUTE_UPDATE);

        List<ReadThenWriteFinding> findings = ReadThenWriteDetector.detect(h.toInputs());
        assertThat(findings).hasSize(1);
        ReadThenWriteFinding f = findings.get(0);
        assertThat(f.table()).isEqualTo("customers");
        assertThat(f.column()).isEqualTo("id");
        assertThat(f.value()).isEqualTo("42");
        assertThat(f.readDurationNanos()).isEqualTo(100L);
        assertThat(f.writeDurationNanos()).isEqualTo(200L);
    }

    @Test
    void differentIdsAreNotPaired() {
        Harness h = new Harness();
        int readSql = h.addSql("SELECT name FROM customers WHERE id = ?");
        int writeSql = h.addSql("UPDATE customers SET name = ? WHERE id = ?");
        int pvRead = h.addParamValues("42");
        int pvWrite = h.addParamValues("Bob", "99");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, readSql, stack, pvRead, 100L, EventType.EXECUTE_QUERY);
        h.addEvent(1L, writeSql, stack, pvWrite, 200L, EventType.EXECUTE_UPDATE);

        assertThat(ReadThenWriteDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void selectThenSelectIsNotFlagged() {
        Harness h = new Harness();
        int readA = h.addSql("SELECT name FROM customers WHERE id = ?");
        int readB = h.addSql("SELECT email FROM customers WHERE id = ?");
        int pv = h.addParamValues("42");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, readA, stack, pv, 100L, EventType.EXECUTE_QUERY);
        h.addEvent(1L, readB, stack, pv, 150L, EventType.EXECUTE_QUERY);

        assertThat(ReadThenWriteDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void writeWithoutPriorReadIsIgnored() {
        Harness h = new Harness();
        int writeSql = h.addSql("UPDATE customers SET name = ? WHERE id = ?");
        int pvWrite = h.addParamValues("Alice", "42");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, writeSql, stack, pvWrite, 200L, EventType.EXECUTE_UPDATE);

        assertThat(ReadThenWriteDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void requiresCapturedParameterValues() {
        // No paramValuesById entries: can't resolve entity keys; detector
        // returns empty rather than misfire.
        Harness h = new Harness();
        int readSql = h.addSql("SELECT name FROM customers WHERE id = ?");
        int writeSql = h.addSql("UPDATE customers SET name = ? WHERE id = ?");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        h.addEventNoParams(1L, readSql, stack, 100L, EventType.EXECUTE_QUERY);
        h.addEventNoParams(1L, writeSql, stack, 200L, EventType.EXECUTE_UPDATE);

        assertThat(ReadThenWriteDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void rankedByTotalDurationOfThePair() {
        Harness h = new Harness();
        int rA = h.addSql("SELECT name FROM customers WHERE id = ?");
        int wA = h.addSql("UPDATE customers SET name = ? WHERE id = ?");
        int rB = h.addSql("SELECT email FROM t2 WHERE id = ?");
        int wB = h.addSql("UPDATE t2 SET email = ? WHERE id = ?");
        int pv1 = h.addParamValues("1");
        int pv1w = h.addParamValues("x", "1");
        int pv2 = h.addParamValues("2");
        int pv2w = h.addParamValues("y", "2");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, rA, stack, pv1, 10L, EventType.EXECUTE_QUERY);
        h.addEvent(1L, wA, stack, pv1w, 20L, EventType.EXECUTE_UPDATE);
        h.addEvent(1L, rB, stack, pv2, 500L, EventType.EXECUTE_QUERY);
        h.addEvent(1L, wB, stack, pv2w, 2000L, EventType.EXECUTE_UPDATE);

        List<ReadThenWriteFinding> findings = ReadThenWriteDetector.detect(h.toInputs());
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).table()).isEqualTo("t2");
        assertThat(findings.get(1).table()).isEqualTo("customers");
    }

    // --- harness ---

    private static final class Harness {
        final Map<Integer, String> sqls = new HashMap<>();
        final Map<Integer, StackFrameSnapshot[]> stacks = new HashMap<>();
        final Map<Integer, ParameterValues> paramValuesById = new HashMap<>();
        final Map<Long, String> ops = new HashMap<>();
        final Map<Long, List<Event>> eventsByOp = new HashMap<>();
        int nextSqlId;
        int nextStackId;
        int nextParamValuesId;
        long ts = 1_000_000L;

        int addSql(String sql) {
            int id = nextSqlId++;
            sqls.put(id, sql);
            return id;
        }

        int addStack(String cls, String method, int line) {
            int id = nextStackId++;
            stacks.put(id, new StackFrameSnapshot[]{new StackFrameSnapshot(cls, method, line)});
            return id;
        }

        int addParamValues(String... slots) {
            int id = nextParamValuesId++;
            paramValuesById.put(id, new ParameterValues(List.of(slots)));
            return id;
        }

        void addOp(long opId, String name) {
            ops.put(opId, name);
        }

        void addEvent(long opId, int sqlId, int stackId, int pvId, long dur, EventType type) {
            Event e = baseEvent(opId, sqlId, stackId, dur, type);
            e.parameterValuesId = pvId;
            e.parameterFingerprint = 0xDEADBEEFL ^ pvId;
            eventsByOp.computeIfAbsent(opId, k -> new ArrayList<>()).add(e);
        }

        void addEventNoParams(long opId, int sqlId, int stackId, long dur, EventType type) {
            Event e = baseEvent(opId, sqlId, stackId, dur, type);
            e.parameterValuesId = -1;
            e.parameterFingerprint = 0L;
            eventsByOp.computeIfAbsent(opId, k -> new ArrayList<>()).add(e);
        }

        private Event baseEvent(long opId, int sqlId, int stackId, long dur, EventType type) {
            Event e = new Event();
            e.timestampNanos = ts;
            ts += dur;
            e.threadId = 1;
            e.operationId = opId;
            e.eventType = type.code();
            e.sqlId = sqlId;
            e.stackTraceId = stackId;
            e.durationNanos = dur;
            e.rowsAffected = -1;
            e.batchSize = 0;
            return e;
        }

        EntityAccessAudit.Inputs toInputs() {
            Map<Integer, ParameterValues> pv = paramValuesById.isEmpty()
                    ? new HashMap<>() : paramValuesById;
            return new EntityAccessAudit.Inputs(eventsByOp, sqls, stacks, pv, ops, NO_OP);
        }
    }
}
