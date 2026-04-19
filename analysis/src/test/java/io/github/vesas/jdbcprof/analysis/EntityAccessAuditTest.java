package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.EventType;
import io.github.vesas.jdbcprof.capture.ParameterValues;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityAccessAuditTest {

    private static final long NO_OP = -1L;

    @Test
    void sameEntityViaTwoTemplatesInOneOpIsFlagged() {
        Harness h = new Harness();
        int sqlName = h.addSql("SELECT name FROM customers WHERE id = ?");
        int sqlEmail = h.addSql("SELECT email FROM customers WHERE id = ?");
        int pv42 = h.addParamValues("42");
        int stackA = h.addStack("com.example.dao.CustomerDao", "findName", 10);
        int stackB = h.addStack("com.example.dao.CustomerDao", "findEmail", 20);
        long op = 1L;
        h.addOp(op, "checkout");
        h.addEvent(op, sqlName, stackA, pv42, 1000L);
        h.addEvent(op, sqlEmail, stackB, pv42, 1500L);

        List<EntityFinding> findings = EntityAccessAudit.detect(h.toInputs());
        assertThat(findings).hasSize(1);
        EntityFinding f = findings.get(0);
        assertThat(f.table()).isEqualTo("customers");
        assertThat(f.column()).isEqualTo("id");
        assertThat(f.value()).isEqualTo("42");
        assertThat(f.templates()).hasSize(2);
        assertThat(f.totalEvents()).isEqualTo(2L);
        assertThat(f.totalDurationNanos()).isEqualTo(2500L);
    }

    @Test
    void sameTemplateAccessedTwiceIsNotAnEntityFinding() {
        // That's the redundant-queries job, not this one. Entity audit
        // only flags CROSS-template access of the same entity.
        Harness h = new Harness();
        int sqlName = h.addSql("SELECT name FROM customers WHERE id = ?");
        int pv42 = h.addParamValues("42");
        int stack = h.addStack("com.example.dao.CustomerDao", "findName", 10);
        h.addOp(1L, "op");
        h.addEvent(1L, sqlName, stack, pv42, 1000L);
        h.addEvent(1L, sqlName, stack, pv42, 1000L);

        assertThat(EntityAccessAudit.detect(h.toInputs())).isEmpty();
    }

    @Test
    void differentEntitiesAreNotCombined() {
        Harness h = new Harness();
        int sqlName = h.addSql("SELECT name FROM customers WHERE id = ?");
        int sqlEmail = h.addSql("SELECT email FROM customers WHERE id = ?");
        int pv1 = h.addParamValues("1");
        int pv2 = h.addParamValues("2");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, sqlName, stack, pv1, 1000L);
        h.addEvent(1L, sqlEmail, stack, pv2, 1000L);

        // id=1 is only touched by one template, id=2 is only touched by one
        // template. Neither crosses the "≥2 templates" threshold.
        assertThat(EntityAccessAudit.detect(h.toInputs())).isEmpty();
    }

    @Test
    void eventsWithoutParamValuesAreSkipped() {
        Harness h = new Harness();
        int sqlName = h.addSql("SELECT name FROM customers WHERE id = ?");
        int sqlEmail = h.addSql("SELECT email FROM customers WHERE id = ?");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        // valuesId < 0 means value capture was off.
        h.addEventNoParams(1L, sqlName, stack, 1000L);
        h.addEventNoParams(1L, sqlEmail, stack, 1500L);

        assertThat(EntityAccessAudit.detect(h.toInputs())).isEmpty();
    }

    @Test
    void eventsOutsideAnyOpAreIgnored() {
        Harness h = new Harness();
        int sqlName = h.addSql("SELECT name FROM customers WHERE id = ?");
        int sqlEmail = h.addSql("SELECT email FROM customers WHERE id = ?");
        int pv1 = h.addParamValues("1");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addEvent(NO_OP, sqlName, stack, pv1, 1000L);
        h.addEvent(NO_OP, sqlEmail, stack, pv1, 1000L);

        assertThat(EntityAccessAudit.detect(h.toInputs())).isEmpty();
    }

    @Test
    void unparseableTemplatesAreSilentlySkipped() {
        Harness h = new Harness();
        // Subquery — TemplateShape returns null.
        int sqlA = h.addSql(
                "SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE active = ?)");
        int sqlB = h.addSql("SELECT name FROM customers WHERE id = ?");
        int pvA = h.addParamValues("true");
        int pvB = h.addParamValues("42");
        int stack = h.addStack("com.example.Dao", "x", 1);
        h.addOp(1L, "op");
        h.addEvent(1L, sqlA, stack, pvA, 1000L);
        h.addEvent(1L, sqlB, stack, pvB, 1000L);

        // Only sqlB is parseable; only one template touches any entity
        // key. No finding.
        assertThat(EntityAccessAudit.detect(h.toInputs())).isEmpty();
    }

    @Test
    void rankedByTotalDurationAcrossTemplates() {
        Harness h = new Harness();
        int s1 = h.addSql("SELECT name FROM customers WHERE id = ?");
        int s2 = h.addSql("SELECT email FROM customers WHERE id = ?");
        int s3 = h.addSql("SELECT status FROM customers WHERE id = ?");
        int pv1 = h.addParamValues("1");
        int pv2 = h.addParamValues("2");
        int stack = h.addStack("com.example.Dao", "x", 1);

        h.addOp(1L, "cheap-op");
        h.addEvent(1L, s1, stack, pv1, 100L);
        h.addEvent(1L, s2, stack, pv1, 100L);

        h.addOp(2L, "expensive-op");
        h.addEvent(2L, s1, stack, pv2, 5_000L);
        h.addEvent(2L, s2, stack, pv2, 5_000L);
        h.addEvent(2L, s3, stack, pv2, 5_000L);

        List<EntityFinding> findings = EntityAccessAudit.detect(h.toInputs());
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).opName()).isEqualTo("expensive-op");
        assertThat(findings.get(0).templates()).hasSize(3);
        assertThat(findings.get(1).opName()).isEqualTo("cheap-op");
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
        long timestampCursor = 1_000_000L;

        int addSql(String sql) {
            int id = nextSqlId++;
            sqls.put(id, sql);
            return id;
        }

        int addStack(String className, String method, int line) {
            int id = nextStackId++;
            stacks.put(id, new StackFrameSnapshot[]{
                    new StackFrameSnapshot(className, method, line)
            });
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

        void addEvent(long opId, int sqlId, int stackId, int paramValuesId, long duration) {
            Event e = new Event();
            e.timestampNanos = timestampCursor;
            timestampCursor += duration;
            e.threadId = 1;
            e.operationId = opId;
            e.eventType = EventType.EXECUTE_QUERY.code();
            e.sqlId = sqlId;
            e.stackTraceId = stackId;
            e.durationNanos = duration;
            e.rowsAffected = -1;
            e.batchSize = 0;
            e.parameterFingerprint = 0xDEADBEEFL ^ (long) paramValuesId;
            e.parameterValuesId = paramValuesId;
            eventsByOp.computeIfAbsent(opId, k -> new ArrayList<>()).add(e);
        }

        void addEventNoParams(long opId, int sqlId, int stackId, long duration) {
            Event e = new Event();
            e.timestampNanos = timestampCursor;
            timestampCursor += duration;
            e.threadId = 1;
            e.operationId = opId;
            e.eventType = EventType.EXECUTE_QUERY.code();
            e.sqlId = sqlId;
            e.stackTraceId = stackId;
            e.durationNanos = duration;
            e.rowsAffected = -1;
            e.batchSize = 0;
            e.parameterFingerprint = 0L;
            e.parameterValuesId = -1;
            eventsByOp.computeIfAbsent(opId, k -> new ArrayList<>()).add(e);
        }

        EntityAccessAudit.Inputs toInputs() {
            return new EntityAccessAudit.Inputs(
                    eventsByOp, sqls, stacks, paramValuesById, ops, NO_OP);
        }
    }
}
