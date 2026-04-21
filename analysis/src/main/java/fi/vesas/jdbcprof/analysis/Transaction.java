package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;

import java.util.List;

/**
 * One reconstructed JDBC transaction: a per-thread slice of events
 * ending at a {@code COMMIT} or {@code ROLLBACK}, or a trailing open
 * run that saw no terminator. Produced by
 * {@link Transactions#reconstruct(long, List)} so multiple detectors
 * can share the same notion of "a transaction."
 *
 * <p>Autocommit "transactions" are not represented here — a thread
 * that never emitted COMMIT or ROLLBACK contributes no
 * {@link Transaction} records, matching the earlier IdleLock-only
 * behaviour.
 *
 * <p>Counts count only events that actually touched the database
 * ({@code EXECUTE_QUERY}, {@code EXECUTE_UPDATE}, {@code
 * EXECUTE_BATCH}). {@code PREPARE}, {@code NEXT} and {@code CLOSE} are
 * bookkeeping and would inflate "work per TX" measurements.
 */
public record Transaction(
        long opId,
        int threadId,
        List<Event> events,
        long startNanos,
        long endNanos,
        long dbTimeNanos,
        int queryCount,
        int writeCount,
        int readCount,
        Terminator terminator,
        int firstStackId,
        int lastStackId) {

    public enum Terminator { COMMITTED, ROLLED_BACK, OPEN }

    public Transaction {
        events = List.copyOf(events);
    }

    public long wallClockNanos() {
        return Math.max(0L, endNanos - startNanos);
    }

    public boolean committed() {
        return terminator == Terminator.COMMITTED;
    }

    public boolean rolledBack() {
        return terminator == Terminator.ROLLED_BACK;
    }

    public boolean open() {
        return terminator == Terminator.OPEN;
    }
}
