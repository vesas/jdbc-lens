package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Slices the events of a single operation into transactions so the
 * drill-down page can surface long-held TXs, read-heavy TXs, and
 * autocommit-per-statement ops.
 *
 * <p>Since we don't capture {@code setAutoCommit} directly, the
 * heuristic is: a run of events on one thread ending in a
 * {@code COMMIT} or {@code ROLLBACK} is one explicit transaction. If
 * a thread's run of events never hits a commit/rollback, we treat
 * that thread as being in autocommit mode — each EXECUTE* is its own
 * logical TX (a well-known source of unnecessary round-trips).
 */
public final class TransactionShape {

    public enum Outcome { COMMIT, ROLLBACK, AUTOCOMMIT }

    public record Transaction(
            int threadId,
            long startTimestampNanos,
            long endTimestampNanos,
            long durationNanos,
            int reads,
            int writes,
            int prepares,
            int totalEvents,
            Outcome outcome,
            int longestTemplateSqlId,
            long longestTemplateDurationNanos) {
    }

    private TransactionShape() {
    }

    public static List<Transaction> of(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<Event>> perThread = new HashMap<>();
        for (Event e : events) {
            perThread.computeIfAbsent(e.threadId, k -> new ArrayList<>()).add(e);
        }
        List<Transaction> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Event>> threadEntry : perThread.entrySet()) {
            int threadId = threadEntry.getKey();
            List<Event> threadEvents = threadEntry.getValue();
            threadEvents.sort((a, b) -> Long.compare(a.timestampNanos, b.timestampNanos));

            TxAccum current = null;
            boolean sawBoundary = false;
            for (Event e : threadEvents) {
                byte kind = e.eventType;
                if (current == null) {
                    current = new TxAccum(threadId, e.timestampNanos);
                }
                current.add(e);
                if (kind == EventType.COMMIT.code() || kind == EventType.ROLLBACK.code()) {
                    out.add(current.finish(kind == EventType.COMMIT.code()
                            ? Outcome.COMMIT : Outcome.ROLLBACK));
                    current = null;
                    sawBoundary = true;
                }
            }

            if (current != null) {
                if (sawBoundary) {
                    // Trailing run after at least one explicit commit: leave
                    // it as an open TX — uncommitted by the time the op ended.
                    out.add(current.finish(Outcome.COMMIT));
                } else {
                    // Thread never hit a commit/rollback: autocommit mode.
                    // Every execute stands alone; emit one TX per execute.
                    out.addAll(autocommitTxs(threadId, threadEvents));
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.startTimestampNanos(), b.startTimestampNanos()));
        return out;
    }

    private static List<Transaction> autocommitTxs(int threadId, List<Event> events) {
        List<Transaction> out = new ArrayList<>();
        for (Event e : events) {
            if (!isExecute(e.eventType)) {
                continue;
            }
            long dur = Math.max(0L, e.durationNanos);
            boolean isWrite = e.eventType == EventType.EXECUTE_UPDATE.code()
                    || e.eventType == EventType.EXECUTE_BATCH.code();
            out.add(new Transaction(
                    threadId,
                    e.timestampNanos,
                    e.timestampNanos + dur,
                    dur,
                    isWrite ? 0 : 1,
                    isWrite ? 1 : 0,
                    0,
                    1,
                    Outcome.AUTOCOMMIT,
                    e.sqlId,
                    dur));
        }
        return out;
    }

    private static boolean isExecute(byte code) {
        return code == EventType.EXECUTE_QUERY.code()
                || code == EventType.EXECUTE_UPDATE.code()
                || code == EventType.EXECUTE_BATCH.code();
    }

    private static final class TxAccum {
        final int threadId;
        final long start;
        long end;
        int reads;
        int writes;
        int prepares;
        int total;
        int longestSqlId = -1;
        long longestDur;

        TxAccum(int threadId, long startTs) {
            this.threadId = threadId;
            this.start = startTs;
            this.end = startTs;
        }

        void add(Event e) {
            long dur = Math.max(0L, e.durationNanos);
            long maybeEnd = e.timestampNanos + dur;
            if (maybeEnd > end) {
                end = maybeEnd;
            }
            total++;
            byte k = e.eventType;
            if (k == EventType.PREPARE.code()) {
                prepares++;
            } else if (k == EventType.EXECUTE_QUERY.code() || k == EventType.NEXT.code()) {
                reads++;
            } else if (k == EventType.EXECUTE_UPDATE.code() || k == EventType.EXECUTE_BATCH.code()) {
                writes++;
            }
            if (e.sqlId >= 0 && dur > longestDur) {
                longestDur = dur;
                longestSqlId = e.sqlId;
            }
        }

        Transaction finish(Outcome outcome) {
            return new Transaction(
                    threadId,
                    start,
                    end,
                    Math.max(0L, end - start),
                    reads,
                    writes,
                    prepares,
                    total,
                    outcome,
                    longestSqlId,
                    longestDur);
        }
    }
}
