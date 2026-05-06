package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionsTest {

    private static final long OP_ID = 7L;

    @Test
    void emptyEventListReturnsEmpty() {
        assertThat(Transactions.reconstruct(OP_ID, List.of())).isEmpty();
    }

    @Test
    void nullEventListReturnsEmpty() {
        assertThat(Transactions.reconstruct(OP_ID, null)).isEmpty();
    }

    @Test
    void autocommitOnlyThreadContributesNoTransactions() {
        // A thread that never emits COMMIT or ROLLBACK is operating in autocommit
        // mode. There are no lock boundaries to analyse, so reconstruct() must
        // exclude it entirely.
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_QUERY, 0L, 5_000_000L, 1),
                event(1, EventType.EXECUTE_UPDATE, 10_000_000L, 3_000_000L, 2)
        );
        assertThat(Transactions.reconstruct(OP_ID, events)).isEmpty();
    }

    @Test
    void singleCommittedTransaction() {
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_UPDATE, 0L, 10_000_000L, 1),
                event(1, EventType.COMMIT, 20_000_000L, 1_000_000L, -1)
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);

        assertThat(txns).hasSize(1);
        Transaction tx = txns.get(0);
        assertThat(tx.terminator()).isEqualTo(Transaction.Terminator.COMMITTED);
        assertThat(tx.opId()).isEqualTo(OP_ID);
        assertThat(tx.threadId()).isEqualTo(1);
        assertThat(tx.queryCount()).isEqualTo(1);
        assertThat(tx.writeCount()).isEqualTo(1);
        assertThat(tx.readCount()).isEqualTo(0);
    }

    @Test
    void singleRolledBackTransaction() {
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_QUERY, 0L, 5_000_000L, 1),
                event(1, EventType.ROLLBACK, 10_000_000L, 500_000L, -1)
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);

        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).terminator()).isEqualTo(Transaction.Terminator.ROLLED_BACK);
        assertThat(txns.get(0).readCount()).isEqualTo(1);
        assertThat(txns.get(0).writeCount()).isEqualTo(0);
    }

    @Test
    void trailingEventsAfterLastCommitYieldOpenTransaction() {
        // Thread commits TX1, then starts work on TX2 but the operation ends
        // without a second COMMIT. The trailing events must produce an OPEN transaction
        // so callers can decide whether to analyse it separately.
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_UPDATE, 0L, 5_000_000L, 1),
                event(1, EventType.COMMIT, 10_000_000L, 1_000_000L, -1),
                event(1, EventType.EXECUTE_UPDATE, 20_000_000L, 5_000_000L, 1) // open TX2
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);

        assertThat(txns).hasSize(2);
        Optional<Transaction> committed = txns.stream()
                .filter(t -> t.terminator() == Transaction.Terminator.COMMITTED).findFirst();
        Optional<Transaction> open = txns.stream()
                .filter(t -> t.terminator() == Transaction.Terminator.OPEN).findFirst();

        assertThat(committed).isPresent();
        assertThat(open).isPresent();
        assertThat(open.get().queryCount()).isEqualTo(1);
    }

    @Test
    void multipleCommitsOnOneThreadYieldMultipleTransactions() {
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_QUERY, 0L, 5_000_000L, 1),
                event(1, EventType.COMMIT, 10_000_000L, 1_000_000L, -1),
                event(1, EventType.EXECUTE_UPDATE, 20_000_000L, 5_000_000L, 2),
                event(1, EventType.COMMIT, 30_000_000L, 1_000_000L, -1)
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);

        assertThat(txns).hasSize(2);
        assertThat(txns).allMatch(t -> t.terminator() == Transaction.Terminator.COMMITTED);
        // First TX: read-only. Second TX: write-only.
        assertThat(txns.get(0).readCount()).isEqualTo(1);
        assertThat(txns.get(0).writeCount()).isEqualTo(0);
        assertThat(txns.get(1).writeCount()).isEqualTo(1);
        assertThat(txns.get(1).readCount()).isEqualTo(0);
    }

    @Test
    void twoThreadsYieldIndependentTransactions() {
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_UPDATE, 0L, 10_000_000L, 1),
                event(1, EventType.COMMIT, 15_000_000L, 1_000_000L, -1),
                event(2, EventType.EXECUTE_QUERY, 0L, 5_000_000L, 2),
                event(2, EventType.COMMIT, 8_000_000L, 1_000_000L, -1)
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);

        assertThat(txns).hasSize(2);
        Optional<Transaction> t1 = txns.stream().filter(t -> t.threadId() == 1).findFirst();
        Optional<Transaction> t2 = txns.stream().filter(t -> t.threadId() == 2).findFirst();
        assertThat(t1).isPresent();
        assertThat(t2).isPresent();
        assertThat(t1.get().writeCount()).isEqualTo(1);
        assertThat(t2.get().readCount()).isEqualTo(1);
    }

    @Test
    void autocommitThreadIsIgnoredEvenWhenOtherThreadHasExplicitTx() {
        // Thread 1 is autocommit; thread 2 has an explicit transaction.
        // Only thread 2 should contribute a Transaction record.
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_QUERY, 0L, 5_000_000L, 1), // no COMMIT
                event(2, EventType.EXECUTE_UPDATE, 0L, 5_000_000L, 2),
                event(2, EventType.COMMIT, 10_000_000L, 1_000_000L, -1)
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);

        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).threadId()).isEqualTo(2);
    }

    @Test
    void dbTimeIncludesAllEventDurationsIncludingCommit() {
        // dbTimeNanos accumulates every event's duration, including COMMIT/ROLLBACK,
        // because the caller is waiting for the DB roundtrip in all cases.
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_UPDATE, 0L, 10_000_000L, 1),
                event(1, EventType.COMMIT, 15_000_000L, 3_000_000L, -1)
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);

        assertThat(txns.get(0).dbTimeNanos()).isEqualTo(13_000_000L);
    }

    @Test
    void wallClockSpansFromFirstEventStartToLastEventEnd() {
        // startNanos = first event's timestamp; endNanos = last event's timestamp + duration.
        List<Event> events = List.of(
                event(1, EventType.EXECUTE_QUERY, 100_000_000L, 10_000_000L, 1),
                event(1, EventType.COMMIT, 200_000_000L, 5_000_000L, -1)
        );
        List<Transaction> txns = Transactions.reconstruct(OP_ID, events);
        Transaction tx = txns.get(0);

        assertThat(tx.startNanos()).isEqualTo(100_000_000L);
        assertThat(tx.endNanos()).isEqualTo(205_000_000L);
        assertThat(tx.wallClockNanos()).isEqualTo(105_000_000L);
    }

    @Test
    void firstStackIdIsFromFirstNonBoundaryEvent() {
        // The representative call-site for "what work did this TX do" is the first
        // statement's stack, not the COMMIT's (spec intent: body is the loop, not
        // the plumbing). COMMIT and ROLLBACK events must be excluded from firstStackId.
        Event query = event(1, EventType.EXECUTE_QUERY, 0L, 5_000_000L, 1);
        query.stackTraceId = 42;
        Event commit = event(1, EventType.COMMIT, 10_000_000L, 1_000_000L, -1);
        commit.stackTraceId = 99;

        List<Transaction> txns = Transactions.reconstruct(OP_ID, List.of(query, commit));

        assertThat(txns.get(0).firstStackId()).isEqualTo(42);
        assertThat(txns.get(0).lastStackId()).isEqualTo(99); // last event's stack
    }

    @Test
    void negativeDurationInEventIsClampedToZeroForDbTime() {
        Event update = event(1, EventType.EXECUTE_UPDATE, 0L, -1L, 1); // negative duration
        Event commit = event(1, EventType.COMMIT, 5_000_000L, 1_000_000L, -1);

        List<Transaction> txns = Transactions.reconstruct(OP_ID, List.of(update, commit));

        // The negative duration must be clamped to 0; only the commit's 1ms counts.
        assertThat(txns.get(0).dbTimeNanos()).isEqualTo(1_000_000L);
    }

    // --- helper ---

    private static Event event(int threadId, EventType type, long ts, long dur, int sqlId) {
        Event e = new Event();
        e.timestampNanos = ts;
        e.threadId = threadId;
        e.eventType = type.code();
        e.sqlId = sqlId;
        e.stackTraceId = -1;
        e.durationNanos = dur;
        e.rowsAffected = -1;
        return e;
    }
}
