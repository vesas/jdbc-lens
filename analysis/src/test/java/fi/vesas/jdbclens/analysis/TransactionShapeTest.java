package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionShapeTest {

    @Test
    void emptyEventsYieldEmptyList() {
        assertThat(TransactionShape.of(List.of())).isEmpty();
        assertThat(TransactionShape.of(null)).isEmpty();
    }

    @Test
    void explicitTransactionIsOneTxWithReadsAndWrites() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.PREPARE, 100L, 0, 1));
        events.add(event(1, EventType.EXECUTE_QUERY, 200L, 50L, 1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 300L, 80L, 2));
        events.add(event(1, EventType.COMMIT, 500L, 10L, -1));

        List<TransactionShape.Transaction> txs = TransactionShape.of(events);
        assertThat(txs).hasSize(1);
        TransactionShape.Transaction t = txs.get(0);
        assertThat(t.outcome()).isEqualTo(TransactionShape.Outcome.COMMIT);
        assertThat(t.reads()).isEqualTo(1);
        assertThat(t.writes()).isEqualTo(1);
        assertThat(t.prepares()).isEqualTo(1);
        assertThat(t.totalEvents()).isEqualTo(4);
    }

    @Test
    void rollbackOutcomeIsCaptured() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 100L, 50L, 1));
        events.add(event(1, EventType.ROLLBACK, 200L, 5L, -1));
        List<TransactionShape.Transaction> txs = TransactionShape.of(events);
        assertThat(txs).singleElement()
                .extracting(TransactionShape.Transaction::outcome)
                .isEqualTo(TransactionShape.Outcome.ROLLBACK);
    }

    @Test
    void autocommitOpEmitsOneTxPerExecute() {
        // Three executes, no COMMIT/ROLLBACK anywhere: autocommit mode.
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_QUERY, 100L, 50L, 1));
        events.add(event(1, EventType.EXECUTE_QUERY, 200L, 50L, 1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 300L, 80L, 2));
        List<TransactionShape.Transaction> txs = TransactionShape.of(events);
        assertThat(txs).hasSize(3);
        assertThat(txs).allSatisfy(t ->
                assertThat(t.outcome()).isEqualTo(TransactionShape.Outcome.AUTOCOMMIT));
    }

    @Test
    void twoExplicitTransactionsBackToBack() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 100L, 50L, 1));
        events.add(event(1, EventType.COMMIT, 200L, 5L, -1));
        events.add(event(1, EventType.EXECUTE_UPDATE, 300L, 40L, 2));
        events.add(event(1, EventType.COMMIT, 400L, 5L, -1));
        List<TransactionShape.Transaction> txs = TransactionShape.of(events);
        assertThat(txs).hasSize(2);
        assertThat(txs.get(0).writes()).isEqualTo(1);
        assertThat(txs.get(1).writes()).isEqualTo(1);
    }

    @Test
    void perThreadBucketing() {
        // Thread 1 runs an explicit TX; Thread 2 runs an autocommit pair.
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_UPDATE, 100L, 50L, 1));
        events.add(event(2, EventType.EXECUTE_QUERY, 110L, 30L, 2));
        events.add(event(1, EventType.COMMIT, 200L, 5L, -1));
        events.add(event(2, EventType.EXECUTE_QUERY, 210L, 30L, 2));

        List<TransactionShape.Transaction> txs = TransactionShape.of(events);
        // Thread 1: 1 explicit TX. Thread 2: 2 autocommit TXs. Total 3.
        assertThat(txs).hasSize(3);
        assertThat(txs.stream()
                .filter(t -> t.outcome() == TransactionShape.Outcome.COMMIT).count())
                .isEqualTo(1L);
        assertThat(txs.stream()
                .filter(t -> t.outcome() == TransactionShape.Outcome.AUTOCOMMIT).count())
                .isEqualTo(2L);
    }

    @Test
    void longestTemplateIsTheSlowestExecuteInTheTx() {
        List<Event> events = new ArrayList<>();
        events.add(event(1, EventType.EXECUTE_QUERY, 100L, 10L, 7));
        events.add(event(1, EventType.EXECUTE_UPDATE, 200L, 500L, 8));
        events.add(event(1, EventType.EXECUTE_QUERY, 800L, 20L, 9));
        events.add(event(1, EventType.COMMIT, 900L, 5L, -1));
        TransactionShape.Transaction t = TransactionShape.of(events).get(0);
        assertThat(t.longestTemplateSqlId()).isEqualTo(8);
        assertThat(t.longestTemplateDurationNanos()).isEqualTo(500L);
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
        e.batchSize = 0;
        return e;
    }
}
