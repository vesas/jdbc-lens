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

class WriteAmplificationDetectorTest {

    private static final long NO_OP = -1L;

    @Test
    void disjointColumnsFlaggedAsMergeable() {
        Harness h = new Harness();
        int statusSql = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int shipSql = h.addSql("UPDATE orders SET shipped_at = ? WHERE id = ?");
        int stack = h.addStack("com.example.OrderService", "finalizeCheckout", 33);
        h.addOp(1L, "finalize-checkout");
        h.addEvent(1L, statusSql, stack, h.addParamValues("SHIPPED", "7"), 100L);
        h.addEvent(1L, shipSql, stack, h.addParamValues("2026-04-20", "7"), 200L);

        List<WriteAmplificationFinding> out =
                WriteAmplificationDetector.detect(h.toInputs());
        assertThat(out).hasSize(1);
        WriteAmplificationFinding f = out.get(0);
        assertThat(f.table()).isEqualTo("orders");
        assertThat(f.column()).isEqualTo("id");
        assertThat(f.value()).isEqualTo("7");
        assertThat(f.hits()).hasSize(2);
        assertThat(f.overlap()).isEqualTo(WriteAmplificationFinding.Overlap.DISJOINT);
        assertThat(f.totalDurationNanos()).isEqualTo(300L);
    }

    @Test
    void overlappingColumnsClassifiedAsOverlapping() {
        Harness h = new Harness();
        int a = h.addSql("UPDATE orders SET status = ?, amount = ? WHERE id = ?");
        int b = h.addSql("UPDATE orders SET status = ?, shipped_at = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, a, stack, h.addParamValues("SHIPPED", "10", "7"), 100L);
        h.addEvent(1L, b, stack, h.addParamValues("DONE", "2026-04-20", "7"), 100L);

        List<WriteAmplificationFinding> out =
                WriteAmplificationDetector.detect(h.toInputs());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).overlap())
                .isEqualTo(WriteAmplificationFinding.Overlap.OVERLAPPING);
    }

    @Test
    void identicalSetClausesClassifiedAsIdentical() {
        Harness h = new Harness();
        int sql = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, sql, stack, h.addParamValues("SHIPPED", "7"), 100L);
        h.addEvent(1L, sql, stack, h.addParamValues("DONE", "7"), 100L);

        List<WriteAmplificationFinding> out =
                WriteAmplificationDetector.detect(h.toInputs());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).overlap())
                .isEqualTo(WriteAmplificationFinding.Overlap.IDENTICAL);
    }

    @Test
    void differentEntityKeysAreNotPaired() {
        Harness h = new Harness();
        int a = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int b = h.addSql("UPDATE orders SET shipped_at = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, a, stack, h.addParamValues("SHIPPED", "7"), 100L);
        h.addEvent(1L, b, stack, h.addParamValues("2026-04-20", "8"), 100L);

        assertThat(WriteAmplificationDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void singleUpdateDoesNotFire() {
        Harness h = new Harness();
        int sql = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, sql, stack, h.addParamValues("SHIPPED", "7"), 100L);

        assertThat(WriteAmplificationDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void crossOpUpdatesOnSameRowAreNotPaired() {
        Harness h = new Harness();
        int sql = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op-a");
        h.addOp(2L, "op-b");
        h.addEvent(1L, sql, stack, h.addParamValues("A", "7"), 100L);
        h.addEvent(2L, sql, stack, h.addParamValues("B", "7"), 100L);

        assertThat(WriteAmplificationDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void selectFollowedByUpdateDoesNotFire() {
        // Covered by ReadThenWriteDetector; write-amp only looks at
        // UPDATE-UPDATE, not SELECT-UPDATE.
        Harness h = new Harness();
        int readSql = h.addSql("SELECT status FROM orders WHERE id = ?");
        int writeSql = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, readSql, stack, h.addParamValues("7"), 100L, EventType.EXECUTE_QUERY);
        h.addEvent(1L, writeSql, stack, h.addParamValues("SHIPPED", "7"), 100L,
                EventType.EXECUTE_UPDATE);

        assertThat(WriteAmplificationDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void requiresCapturedParameterValues() {
        Harness h = new Harness();
        int a = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int b = h.addSql("UPDATE orders SET shipped_at = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op");
        h.addEventNoParams(1L, a, stack, 100L);
        h.addEventNoParams(1L, b, stack, 100L);

        assertThat(WriteAmplificationDetector.detect(h.toInputs())).isEmpty();
    }

    @Test
    void rankedByTotalDbTime() {
        Harness h = new Harness();
        int smallA = h.addSql("UPDATE orders SET status = ? WHERE id = ?");
        int smallB = h.addSql("UPDATE orders SET shipped_at = ? WHERE id = ?");
        int bigA = h.addSql("UPDATE customers SET name = ? WHERE id = ?");
        int bigB = h.addSql("UPDATE customers SET email = ? WHERE id = ?");
        int stack = h.addStack("com.example.X", "y", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, smallA, stack, h.addParamValues("SHIPPED", "7"), 10L);
        h.addEvent(1L, smallB, stack, h.addParamValues("2026", "7"), 10L);
        h.addEvent(1L, bigA, stack, h.addParamValues("Alice", "42"), 500L);
        h.addEvent(1L, bigB, stack, h.addParamValues("a@b", "42"), 2000L);

        List<WriteAmplificationFinding> out =
                WriteAmplificationDetector.detect(h.toInputs());
        assertThat(out).hasSize(2);
        assertThat(out.get(0).table()).isEqualTo("customers");
        assertThat(out.get(1).table()).isEqualTo("orders");
    }

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

        void addEvent(long opId, int sqlId, int stackId, int pvId, long dur) {
            addEvent(opId, sqlId, stackId, pvId, dur, EventType.EXECUTE_UPDATE);
        }

        void addEvent(long opId, int sqlId, int stackId, int pvId, long dur, EventType type) {
            Event e = baseEvent(opId, sqlId, stackId, dur, type);
            e.parameterValuesId = pvId;
            e.parameterFingerprint = 0xDEADBEEFL ^ pvId;
            eventsByOp.computeIfAbsent(opId, k -> new ArrayList<>()).add(e);
        }

        void addEventNoParams(long opId, int sqlId, int stackId, long dur) {
            Event e = baseEvent(opId, sqlId, stackId, dur, EventType.EXECUTE_UPDATE);
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
