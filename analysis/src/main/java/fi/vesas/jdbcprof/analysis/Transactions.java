package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs explicit JDBC transactions from a flat event stream.
 * Shared by every detector that reasons about what happened between
 * two commit/rollback boundaries — {@link IdleLockDetector} (idle
 * time holding locks), {@link CommitPerRecordDetector} (rapid-fire
 * short TXs), and whatever else comes next.
 *
 * <p>Rules mirror the earlier private logic in {@code IdleLockDetector}:
 * events are grouped by thread, sorted by timestamp, and cut into
 * slices at each {@code COMMIT} / {@code ROLLBACK}. A thread that
 * never produced either boundary is autocommit-only and contributes
 * nothing. A thread that produced at least one boundary and has
 * trailing events after it yields a final "open" transaction so
 * callers can decide whether to analyse it.
 */
public final class Transactions {

    private Transactions() {
    }

    public static List<Transaction> reconstruct(long opId, List<Event> opEvents) {
        if (opEvents == null || opEvents.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<Event>> byThread = new HashMap<>();
        for (Event e : opEvents) {
            byThread.computeIfAbsent(e.threadId, k -> new ArrayList<>()).add(e);
        }
        List<Transaction> out = new ArrayList<>();
        for (List<Event> threadEvents : byThread.values()) {
            threadEvents.sort(Comparator.comparingLong(e -> e.timestampNanos));
            boolean sawBoundary = false;
            List<Event> current = new ArrayList<>();
            for (Event e : threadEvents) {
                current.add(e);
                if (e.eventType == EventType.COMMIT.code()
                        || e.eventType == EventType.ROLLBACK.code()) {
                    out.add(buildTxn(opId, current));
                    current = new ArrayList<>();
                    sawBoundary = true;
                }
            }
            if (!sawBoundary) {
                continue;
            }
            if (!current.isEmpty()) {
                out.add(buildTxn(opId, current));
            }
        }
        return out;
    }

    private static Transaction buildTxn(long opId, List<Event> events) {
        int threadId = events.get(0).threadId;
        long start = events.get(0).timestampNanos;
        Event last = events.get(events.size() - 1);
        long end = last.timestampNanos + Math.max(0L, last.durationNanos);

        long dbTime = 0L;
        int queryCount = 0;
        int writeCount = 0;
        int readCount = 0;
        int firstStackId = -1;
        for (Event e : events) {
            dbTime += Math.max(0L, e.durationNanos);
            byte t = e.eventType;
            if (t == EventType.EXECUTE_QUERY.code()) {
                queryCount++;
                readCount++;
            } else if (t == EventType.EXECUTE_UPDATE.code()
                    || t == EventType.EXECUTE_BATCH.code()) {
                queryCount++;
                writeCount++;
            }
            // The representative call-site for "what work did this TX
            // actually do" is the first statement's stack, not the
            // commit's — commit() is plumbing, the body is the loop.
            if (firstStackId < 0
                    && e.stackTraceId >= 0
                    && t != EventType.COMMIT.code()
                    && t != EventType.ROLLBACK.code()) {
                firstStackId = e.stackTraceId;
            }
        }

        Transaction.Terminator term;
        if (last.eventType == EventType.COMMIT.code()) {
            term = Transaction.Terminator.COMMITTED;
        } else if (last.eventType == EventType.ROLLBACK.code()) {
            term = Transaction.Terminator.ROLLED_BACK;
        } else {
            term = Transaction.Terminator.OPEN;
        }

        int lastStackId = last.stackTraceId;
        if (firstStackId < 0) {
            firstStackId = lastStackId;
        }

        return new Transaction(
                opId, threadId, events,
                start, end, dbTime,
                queryCount, writeCount, readCount,
                term, firstStackId, lastStackId);
    }
}
