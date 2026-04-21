package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared gap arithmetic used by both the wall-vs-DB breakdown on the
 * Operations table and the idle-lock finding on explicit transactions.
 *
 * <p>Two distinct pieces of signal live here:
 *
 * <ul>
 *   <li>{@link #forOp(List)} — how much of an op's wall-clock time was
 *       spent inside the database vs. outside it (app code, network,
 *       external I/O). DB-busy across threads is the union of per-thread
 *       busy intervals, so a parallel op with two threads each busy 50 ms
 *       for an op that spanned 60 ms wall-clock reports DB=60 ms, not
 *       DB=100 ms.</li>
 *   <li>{@link #betweenAdjacent(List)} — per-thread, the gap before each
 *       event. Used inside a single transaction's timeline to spot a
 *       stretch where the app held the transaction open without doing
 *       any DB work.</li>
 * </ul>
 *
 * <p>All inputs are assumed to come from one logical scope (one op for
 * {@code forOp}, one txn for {@code betweenAdjacent}). The helper does
 * no filtering of its own — the caller decides what to feed it.
 */
public final class EventGaps {

    /** Result of decomposing an op's wall-clock time. */
    public record OpBreakdown(long wallNanos, long dbNanos, long nonDbNanos) {
        public double nonDbFraction() {
            return wallNanos <= 0L ? 0.0 : (double) nonDbNanos / (double) wallNanos;
        }

        public double dbFraction() {
            return wallNanos <= 0L ? 0.0 : (double) dbNanos / (double) wallNanos;
        }
    }

    /**
     * One gap in a timeline: the idle span between two adjacent events
     * on the same thread. The before/after events let callers name the
     * call-site that was executing when the gap began — almost always
     * where the app-side code suspected of holding the lock lives.
     */
    public record Gap(Event before, Event after, long gapNanos) {
    }

    private EventGaps() {
    }

    /**
     * Decompose an op's events into (wall, db, non-db). Handles
     * multi-threaded ops by unioning per-thread busy intervals so
     * overlapping DB work across threads doesn't inflate {@code dbNanos}
     * beyond {@code wallNanos}.
     */
    public static OpBreakdown forOp(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return new OpBreakdown(0L, 0L, 0L);
        }
        long firstTs = Long.MAX_VALUE;
        long lastTs = Long.MIN_VALUE;
        List<long[]> intervals = new ArrayList<>(events.size());
        for (Event e : events) {
            long dur = Math.max(0L, e.durationNanos);
            long start = e.timestampNanos;
            long end = start + dur;
            if (start < firstTs) firstTs = start;
            if (end > lastTs) lastTs = end;
            intervals.add(new long[] {start, end});
        }
        long wall = Math.max(0L, lastTs - firstTs);

        // Union across all threads in one sweep. Per-thread events can't
        // truly overlap (one thread = one JDBC call in flight), but parallel
        // ops run concurrent calls on different threads — their overlap is
        // one slice of DB-busy wall time, not two. A single merged sweep
        // handles both cases and absorbs any cross-event nanoTime jitter.
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long busy = 0L;
        long curStart = intervals.get(0)[0];
        long curEnd = intervals.get(0)[1];
        for (int i = 1; i < intervals.size(); i++) {
            long[] iv = intervals.get(i);
            if (iv[0] <= curEnd) {
                if (iv[1] > curEnd) curEnd = iv[1];
            } else {
                busy += curEnd - curStart;
                curStart = iv[0];
                curEnd = iv[1];
            }
        }
        busy += curEnd - curStart;
        long db = Math.min(busy, wall);
        long nonDb = Math.max(0L, wall - db);
        return new OpBreakdown(wall, db, nonDb);
    }

    /**
     * Returns one {@link Gap} per adjacent-event pair in timestamp
     * order, with {@code gapNanos >= 0}. Events are assumed to be on
     * one thread and sorted by caller. Events that overlap (shouldn't
     * happen on one thread — see {@link #forOp}) yield {@code gapNanos
     * = 0}.
     */
    public static List<Gap> betweenAdjacent(List<Event> sortedThreadEvents) {
        if (sortedThreadEvents == null || sortedThreadEvents.size() < 2) {
            return List.of();
        }
        List<Gap> gaps = new ArrayList<>(sortedThreadEvents.size() - 1);
        for (int i = 1; i < sortedThreadEvents.size(); i++) {
            Event before = sortedThreadEvents.get(i - 1);
            Event after = sortedThreadEvents.get(i);
            long beforeEnd = before.timestampNanos + Math.max(0L, before.durationNanos);
            long gap = Math.max(0L, after.timestampNanos - beforeEnd);
            gaps.add(new Gap(before, after, gap));
        }
        return gaps;
    }
}
