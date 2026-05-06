package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverWideUpdateDetectorTest {

    @Test
    void flagsUpdateWithManyColumns() {
        Map<Integer, String> sqls = new HashMap<>();
        int wideId = 1;
        sqls.put(wideId, "UPDATE customers SET a=?, b=?, c=?, d=?, e=?, f=?, g=? WHERE id=?");
        Map<Long, List<Event>> events = Map.of(1L, List.of(
                execUpdate(wideId, 1, 100L),
                execUpdate(wideId, 1, 200L)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1,
                new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.Dao", "rewrite", 30)});

        List<OverWideUpdateFinding> findings = new OverWideUpdateDetector()
                .detect(sqls, events, stacks);
        assertThat(findings).hasSize(1);
        OverWideUpdateFinding f = findings.get(0);
        assertThat(f.setColumnCount()).isEqualTo(7);
        assertThat(f.executeCount()).isEqualTo(2L);
        assertThat(f.totalDurationNanos()).isEqualTo(300L);
        assertThat(f.representativeSite()).isNotNull();
    }

    @Test
    void doesNotFlagNarrowUpdates() {
        Map<Integer, String> sqls = Map.of(1, "UPDATE t SET a=?, b=? WHERE id=?");
        Map<Long, List<Event>> events = Map.of(1L, List.of(execUpdate(1, 1, 50L)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1, new StackFrameSnapshot[]{});

        assertThat(new OverWideUpdateDetector().detect(sqls, events, stacks)).isEmpty();
    }

    @Test
    void doesNotFlagSelectOrDelete() {
        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(1, "SELECT a, b, c, d, e, f, g, h FROM t WHERE id=?");
        sqls.put(2, "DELETE FROM t WHERE id=?");
        Map<Long, List<Event>> events = Map.of();
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of();
        assertThat(new OverWideUpdateDetector().detect(sqls, events, stacks)).isEmpty();
    }

    @Test
    void thresholdCanBeTuned() {
        Map<Integer, String> sqls = Map.of(1, "UPDATE t SET a=?, b=?, c=? WHERE id=?");
        Map<Long, List<Event>> events = Map.of(1L, List.of(execUpdate(1, 1, 50L)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1,
                new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.Dao", "x", 1)});
        // Threshold 3 — our 3-column UPDATE qualifies.
        List<OverWideUpdateFinding> findings = new OverWideUpdateDetector(3)
                .detect(sqls, events, stacks);
        assertThat(findings).hasSize(1);
        // Threshold 4 — too narrow to flag.
        assertThat(new OverWideUpdateDetector(4).detect(sqls, events, stacks)).isEmpty();
    }

    @Test
    void dominantStackSelectedAsRepresentative() {
        Map<Integer, String> sqls = Map.of(1,
                "UPDATE t SET a=?, b=?, c=?, d=?, e=?, f=? WHERE id=?");
        Map<Long, List<Event>> events = Map.of(1L, List.of(
                execUpdate(1, 10, 100L),
                execUpdate(1, 10, 100L),
                execUpdate(1, 20, 100L)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                10, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.A", "x", 1)},
                20, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.B", "y", 2)});

        OverWideUpdateFinding f = new OverWideUpdateDetector()
                .detect(sqls, events, stacks).get(0);
        assertThat(f.representativeSite().className()).isEqualTo("com.example.A");
    }

    private static Event execUpdate(int sqlId, int stackId, long duration) {
        Event e = new Event();
        e.timestampNanos = 0L;
        e.threadId = 1;
        e.operationId = 1L;
        e.eventType = EventType.EXECUTE_UPDATE.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = duration;
        e.rowsAffected = 1;
        e.batchSize = 0;
        return e;
    }
}
