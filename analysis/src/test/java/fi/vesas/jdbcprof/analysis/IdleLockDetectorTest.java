package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdleLockDetectorTest {

    private static final long NO_OP = -1L;

    @Test
    void emptyInputProducesNoFindings() {
        List<IdleLockFinding> out = new IdleLockDetector()
                .detect(Map.of(), Map.of(), Map.of(), Map.of(), NO_OP);
        assertThat(out).isEmpty();
    }

    @Test
    void flagsWriteTxWithLongGap() {
        // UPDATE at t=0 (dur 10ms), 200ms idle, COMMIT.
        long threshold = 50_000_000L;
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 0L, 10_000_000L, 1));
        events.add(event(1, EventType.COMMIT, 210_000_000L, 1_000_000L, -1));
        Map<Long, List<Event>> byOp = Map.of(7L, events);
        Map<Long, String> opNames = Map.of(7L, "refund-latest");
        Map<Integer, String> sqls = Map.of(1, "UPDATE orders SET refunded_at=? WHERE id=?");
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of();

        List<IdleLockFinding> out = new IdleLockDetector(threshold)
                .detect(byOp, opNames, sqls, stacks, NO_OP);

        assertThat(out).hasSize(1);
        IdleLockFinding f = out.get(0);
        assertThat(f.opName()).isEqualTo("refund-latest");
        assertThat(f.maxIdleGapNanos()).isEqualTo(200_000_000L);
        assertThat(f.writes()).hasSize(1);
        assertThat(f.writes().get(0).sql()).contains("UPDATE orders");
    }

    @Test
    void ignoresReadOnlyExplicitTx() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_QUERY, 0L, 10_000_000L, 1));
        events.add(event(1, EventType.COMMIT, 300_000_000L, 1_000_000L, -1));
        Map<Long, List<Event>> byOp = Map.of(7L, events);
        List<IdleLockFinding> out = new IdleLockDetector()
                .detect(byOp, Map.of(7L, "read-only"), Map.of(1, "SELECT 1"),
                        Map.of(), NO_OP);
        assertThat(out).isEmpty();
    }

    @Test
    void ignoresAutocommitSequence() {
        // Three autocommit UPDATEs with huge gaps between them — no COMMIT
        // event anywhere, so nothing is holding a lock across the gaps.
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 0L, 10_000_000L, 1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 500_000_000L, 10_000_000L, 1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 1_000_000_000L, 10_000_000L, 1));
        Map<Long, List<Event>> byOp = Map.of(7L, events);
        List<IdleLockFinding> out = new IdleLockDetector()
                .detect(byOp, Map.of(7L, "autocommit"), Map.of(1, "UPDATE x SET y=?"),
                        Map.of(), NO_OP);
        assertThat(out).isEmpty();
    }

    @Test
    void ignoresShortGaps() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 0L, 10_000_000L, 1));
        // 20ms gap — below the 50ms default.
        events.add(event(1, EventType.EXECUTE_UPDATE, 30_000_000L, 5_000_000L, 1));
        events.add(event(1, EventType.COMMIT, 40_000_000L, 1_000_000L, -1));
        Map<Long, List<Event>> byOp = Map.of(7L, events);
        List<IdleLockFinding> out = new IdleLockDetector()
                .detect(byOp, Map.of(7L, "fast"), Map.of(1, "UPDATE x SET y=?"),
                        Map.of(), NO_OP);
        assertThat(out).isEmpty();
    }

    @Test
    void segmentsCoverExecutesAndGaps() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 0L, 10_000_000L, 1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 100_000_000L, 10_000_000L, 2));
        events.add(event(1, EventType.COMMIT, 115_000_000L, 1_000_000L, -1));
        Map<Long, List<Event>> byOp = Map.of(7L, events);
        List<IdleLockFinding> out = new IdleLockDetector(50_000_000L)
                .detect(byOp, Map.of(7L, "op"),
                        Map.of(1, "UPDATE a SET x=?", 2, "UPDATE b SET y=?"),
                        Map.of(), NO_OP);
        assertThat(out).hasSize(1);
        IdleLockFinding f = out.get(0);
        // Segments: update, idle, update, (small gap), commit.
        // At minimum we should see: update, idle, update, commit.
        assertThat(f.segments()).extracting(IdleLockFinding.Segment::kind)
                .contains(IdleLockFinding.SegmentKind.EXECUTE_UPDATE,
                        IdleLockFinding.SegmentKind.IDLE,
                        IdleLockFinding.SegmentKind.COMMIT);
        // Exactly one segment should be tagged as the max gap.
        long maxCount = f.segments().stream()
                .filter(IdleLockFinding.Segment::isMaxGap).count();
        assertThat(maxCount).isEqualTo(1L);
    }

    @Test
    void writesAreDedupedAndCounted() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 0L, 1_000_000L, 1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 2_000_000L, 1_000_000L, 1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 4_000_000L, 1_000_000L, 2));
        events.add(event(1, EventType.COMMIT, 200_000_000L, 1_000_000L, -1));
        Map<Long, List<Event>> byOp = Map.of(7L, events);
        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(1, "UPDATE a SET x=?");
        sqls.put(2, "UPDATE b SET y=?");
        List<IdleLockFinding> out = new IdleLockDetector(50_000_000L)
                .detect(byOp, Map.of(7L, "op"), sqls, Map.of(), NO_OP);
        IdleLockFinding f = out.get(0);
        assertThat(f.writes()).hasSize(2);
        assertThat(f.writes().get(0).count()).isEqualTo(2L);
        assertThat(f.writes().get(1).count()).isEqualTo(1L);
    }

    private static Event event(int threadId, EventType type, long ts, long dur, int sqlId) {
        Event e = new Event();
        e.timestampNanos = ts;
        e.threadId = threadId;
        e.eventType = type.code();
        e.sqlId = sqlId;
        e.stackTraceId = 0;
        e.durationNanos = dur;
        e.rowsAffected = -1;
        return e;
    }
}
