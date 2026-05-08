package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatedPrepareDetectorTest {

    @Test
    void belowThresholdProducesNoFinding() {
        Map<Long, List<Event>> byOp = new HashMap<>();
        byOp.put(1L, prepareEvents(1, 10, 9));
        List<RepeatedPrepareFinding> findings = new RepeatedPrepareDetector()
                .detect(byOp, sqls(10, "SELECT ?"), Map.of(), stacks(1));
        assertThat(findings).isEmpty();
    }

    @Test
    void atThresholdProducesOneFinding() {
        Map<Long, List<Event>> byOp = new HashMap<>();
        byOp.put(1L, prepareEvents(1, 10, 10));
        List<RepeatedPrepareFinding> findings = new RepeatedPrepareDetector()
                .detect(byOp, sqls(10, "SELECT ?"), Map.of(1L, "checkout"), stacks(1));
        assertThat(findings).hasSize(1);
        RepeatedPrepareFinding f = findings.get(0);
        assertThat(f.sql()).isEqualTo("SELECT ?");
        assertThat(f.prepareCount()).isEqualTo(10);
        assertThat(f.operationName()).isEqualTo("checkout");
        assertThat(f.callSite().className()).isEqualTo("com.example.Dao");
    }

    @Test
    void twoDistinctCallSitesProduceTwoFindings() {
        List<Event> events = new ArrayList<>();
        events.addAll(prepareEvents(1, 10, 12));  // stack 1, sql 10
        events.addAll(prepareEvents(2, 10, 11));  // stack 2, sql 10 — different call-site
        Map<Long, List<Event>> byOp = new HashMap<>();
        byOp.put(1L, events);

        Map<Integer, StackFrameSnapshot[]> stacks = new HashMap<>();
        stacks.put(1, new StackFrameSnapshot[]{frame("com.example.DaoA", "find", 1)});
        stacks.put(2, new StackFrameSnapshot[]{frame("com.example.DaoB", "load", 2)});

        List<RepeatedPrepareFinding> findings = new RepeatedPrepareDetector()
                .detect(byOp, sqls(10, "SELECT ?"), Map.of(), stacks);
        assertThat(findings).hasSize(2);
        // Ranked by count descending: 12 first, then 11.
        assertThat(findings.get(0).prepareCount()).isEqualTo(12);
        assertThat(findings.get(1).prepareCount()).isEqualTo(11);
    }

    @Test
    void executeEventsAreIgnored() {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            events.add(execEvent(1, 10, 1_000));
        }
        Map<Long, List<Event>> byOp = Map.of(1L, events);
        List<RepeatedPrepareFinding> findings = new RepeatedPrepareDetector()
                .detect(byOp, sqls(10, "SELECT ?"), Map.of(), stacks(1));
        assertThat(findings).isEmpty();
    }

    @Test
    void customThresholdApplied() {
        Map<Long, List<Event>> byOp = Map.of(1L, prepareEvents(1, 10, 3));
        List<RepeatedPrepareFinding> findings = new RepeatedPrepareDetector(3)
                .detect(byOp, sqls(10, "SELECT ?"), Map.of(), stacks(1));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).prepareCount()).isEqualTo(3);
    }

    @Test
    void preparesSplitAcrossOperationsAreNotAggregated() {
        // 6 prepares in op 1 and 6 in op 2 — neither alone meets the default threshold 10.
        Map<Long, List<Event>> byOp = new HashMap<>();
        byOp.put(1L, prepareEvents(1, 10, 6));
        byOp.put(2L, prepareEvents(1, 10, 6));
        List<RepeatedPrepareFinding> findings = new RepeatedPrepareDetector()
                .detect(byOp, sqls(10, "SELECT ?"), Map.of(), stacks(1));
        assertThat(findings).isEmpty();
    }

    // --- helpers ---

    private static List<Event> prepareEvents(int stackId, int sqlId, int count) {
        List<Event> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Event e = new Event();
            e.eventType = EventType.PREPARE.code();
            e.sqlId = sqlId;
            e.stackTraceId = stackId;
            e.durationNanos = 500_000L;
            events.add(e);
        }
        return events;
    }

    private static Event execEvent(int stackId, int sqlId, long durationNanos) {
        Event e = new Event();
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = durationNanos;
        return e;
    }

    private static Map<Integer, StackFrameSnapshot[]> stacks(int stackId) {
        return Map.of(stackId, new StackFrameSnapshot[]{
                frame("com.example.Dao", "find", 1)});
    }

    private static StackFrameSnapshot frame(String cls, String method, int line) {
        return new StackFrameSnapshot(cls, method, line);
    }

    private static Map<Integer, String> sqls(int id, String sql) {
        return Map.of(id, sql);
    }
}
