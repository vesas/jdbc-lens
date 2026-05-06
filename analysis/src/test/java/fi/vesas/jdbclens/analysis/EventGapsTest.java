package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventGapsTest {

    @Test
    void emptyOpReturnsZeros() {
        EventGaps.OpBreakdown br = EventGaps.forOp(List.of());
        assertThat(br.wallNanos()).isZero();
        assertThat(br.dbNanos()).isZero();
        assertThat(br.nonDbNanos()).isZero();
        assertThat(br.nonDbFraction()).isZero();
    }

    @Test
    void singleEventHasAllWallInDb() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, 1000L, 200L));
        EventGaps.OpBreakdown br = EventGaps.forOp(events);
        assertThat(br.wallNanos()).isEqualTo(200L);
        assertThat(br.dbNanos()).isEqualTo(200L);
        assertThat(br.nonDbNanos()).isZero();
    }

    @Test
    void opDominatedByAppWorkReportsHighNonDbFraction() {
        // 10ms DB out of 1000ms wall-clock = 99% non-DB.
        List<Event> events = new ArrayList<>();
        events.add(event(1, 0L, 5_000_000L));           // 0-5ms
        events.add(event(1, 995_000_000L, 5_000_000L)); // 995ms-1000ms
        EventGaps.OpBreakdown br = EventGaps.forOp(events);
        assertThat(br.wallNanos()).isEqualTo(1_000_000_000L);
        assertThat(br.dbNanos()).isEqualTo(10_000_000L);
        assertThat(br.nonDbNanos()).isEqualTo(990_000_000L);
        assertThat(br.nonDbFraction()).isGreaterThan(0.98);
    }

    @Test
    void multiThreadDbIsUnionNotSum() {
        // Two threads both busy 100ms simultaneously during a 100ms op.
        // DB=100ms (union), NOT 200ms (sum). Non-DB=0.
        List<Event> events = new ArrayList<>();
        events.add(event(1, 0L, 100_000_000L));
        events.add(event(2, 0L, 100_000_000L));
        EventGaps.OpBreakdown br = EventGaps.forOp(events);
        assertThat(br.wallNanos()).isEqualTo(100_000_000L);
        assertThat(br.dbNanos()).isEqualTo(100_000_000L);
        assertThat(br.nonDbNanos()).isZero();
    }

    @Test
    void multiThreadOverlapWithIdleTailCountedAsUnion() {
        // Thread 1 busy [0,10ms] and [90,100ms]; thread 2 busy [0,10ms].
        // True DB-busy union = [0,10] ∪ [90,100] = 20ms. Summing per-thread
        // busy would give 30ms — the 0-10ms slice double-counted across
        // threads — and min-clamp to wall (100ms) wouldn't hide it.
        List<Event> events = new ArrayList<>();
        events.add(event(1, 0L, 10_000_000L));
        events.add(event(1, 90_000_000L, 10_000_000L));
        events.add(event(2, 0L, 10_000_000L));
        EventGaps.OpBreakdown br = EventGaps.forOp(events);
        assertThat(br.wallNanos()).isEqualTo(100_000_000L);
        assertThat(br.dbNanos()).isEqualTo(20_000_000L);
        assertThat(br.nonDbNanos()).isEqualTo(80_000_000L);
    }

    @Test
    void adjacentGapsBetweenEventsOnOneThread() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, 0L, 10L));    // ends at 10
        events.add(event(1, 50L, 5L));    // 40ns gap, ends at 55
        events.add(event(1, 200L, 1L));   // 145ns gap
        List<EventGaps.Gap> gaps = EventGaps.betweenAdjacent(events);
        assertThat(gaps).hasSize(2);
        assertThat(gaps.get(0).gapNanos()).isEqualTo(40L);
        assertThat(gaps.get(1).gapNanos()).isEqualTo(145L);
    }

    @Test
    void overlappingEventsProduceZeroGap() {
        // Nominally shouldn't happen on one thread; guard defensively.
        List<Event> events = new ArrayList<>();
        events.add(event(1, 0L, 100L));
        events.add(event(1, 50L, 1L));
        List<EventGaps.Gap> gaps = EventGaps.betweenAdjacent(events);
        assertThat(gaps).singleElement()
                .extracting(EventGaps.Gap::gapNanos).isEqualTo(0L);
    }

    private static Event event(int threadId, long ts, long dur) {
        Event e = new Event();
        e.timestampNanos = ts;
        e.threadId = threadId;
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.durationNanos = dur;
        e.sqlId = -1;
        e.stackTraceId = 0;
        e.rowsAffected = -1;
        return e;
    }
}
