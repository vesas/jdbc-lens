package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags explicit transactions that contained at least one write and
 * at least one idle gap longer than {@link #DEFAULT_THRESHOLD_NANOS}.
 * The assumption is that the app is doing non-DB work — an HTTP
 * call, a file read, CPU-bound logic — while the transaction sits
 * open holding whatever row/page locks its earlier writes took.
 *
 * <p>Autocommit "transactions" are excluded because each autocommit
 * statement is its own transaction and can't hold locks across a
 * gap. Reads-only explicit transactions are also excluded: they take
 * shared locks at most (and often none at all), so a long idle gap
 * there is mostly a latency story, not a contention story.
 */
public final class IdleLockDetector {

    public static final long DEFAULT_THRESHOLD_NANOS = 50_000_000L; // 50 ms

    private final long thresholdNanos;

    public IdleLockDetector() {
        this(DEFAULT_THRESHOLD_NANOS);
    }

    public IdleLockDetector(long thresholdNanos) {
        this.thresholdNanos = thresholdNanos;
    }

    public List<IdleLockFinding> detect(
            Map<Long, List<Event>> eventsByOp,
            Map<Long, String> opNames,
            Map<Integer, String> sqls,
            Map<Integer, StackFrameSnapshot[]> stacks,
            long noOperationSentinel) {

        List<IdleLockFinding> out = new ArrayList<>();
        for (Map.Entry<Long, List<Event>> entry : eventsByOp.entrySet()) {
            long opId = entry.getKey();
            if (opId == noOperationSentinel) {
                continue;
            }
            List<Event> opEvents = entry.getValue();
            if (opEvents == null || opEvents.isEmpty()) {
                continue;
            }
            for (List<Event> txn : sliceExplicitTransactions(opEvents)) {
                IdleLockFinding f = analyseTxn(opId, opNames.get(opId), txn, sqls, stacks);
                if (f != null) {
                    out.add(f);
                }
            }
        }

        // Rank by max gap first (the worst held-lock case should lead),
        // then by total idle time for tie-breaks.
        out.sort(Comparator
                .comparingLong(IdleLockFinding::maxIdleGapNanos).reversed()
                .thenComparing(Comparator.comparingLong(
                        IdleLockFinding::totalIdleNanos).reversed()));
        return out;
    }

    /**
     * Group events by thread, sort by timestamp, and cut each thread's
     * run into explicit transactions (events ending in COMMIT or
     * ROLLBACK). Autocommit threads — no commit/rollback anywhere —
     * are skipped: nothing to analyse.
     */
    private static List<List<Event>> sliceExplicitTransactions(List<Event> events) {
        Map<Integer, List<Event>> byThread = new HashMap<>();
        for (Event e : events) {
            byThread.computeIfAbsent(e.threadId, k -> new ArrayList<>()).add(e);
        }
        List<List<Event>> out = new ArrayList<>();
        for (List<Event> threadEvents : byThread.values()) {
            threadEvents.sort(Comparator.comparingLong(e -> e.timestampNanos));
            boolean sawBoundary = false;
            List<Event> current = new ArrayList<>();
            for (Event e : threadEvents) {
                current.add(e);
                if (e.eventType == EventType.COMMIT.code()
                        || e.eventType == EventType.ROLLBACK.code()) {
                    out.add(current);
                    current = new ArrayList<>();
                    sawBoundary = true;
                }
            }
            if (!sawBoundary) {
                // Autocommit — no explicit TX to analyse on this thread.
                continue;
            }
            if (!current.isEmpty()) {
                // Trailing run after at least one commit/rollback but
                // not yet closed. Still a TX; let the detector see it.
                out.add(current);
            }
        }
        return out;
    }

    private IdleLockFinding analyseTxn(long opId,
                                       String opName,
                                       List<Event> txn,
                                       Map<Integer, String> sqls,
                                       Map<Integer, StackFrameSnapshot[]> stacks) {
        if (txn.size() < 2) {
            return null;
        }
        boolean hasWrite = false;
        for (Event e : txn) {
            if (e.eventType == EventType.EXECUTE_UPDATE.code()
                    || e.eventType == EventType.EXECUTE_BATCH.code()) {
                hasWrite = true;
                break;
            }
        }
        if (!hasWrite) {
            return null;
        }

        List<EventGaps.Gap> gaps = EventGaps.betweenAdjacent(txn);
        long maxGap = 0L;
        long totalIdle = 0L;
        EventGaps.Gap worst = null;
        for (EventGaps.Gap g : gaps) {
            totalIdle += g.gapNanos();
            if (g.gapNanos() > maxGap) {
                maxGap = g.gapNanos();
                worst = g;
            }
        }
        if (maxGap < thresholdNanos) {
            return null;
        }

        Event first = txn.get(0);
        Event last = txn.get(txn.size() - 1);
        long txStart = first.timestampNanos;
        long txEnd = last.timestampNanos + Math.max(0L, last.durationNanos);
        long txDur = Math.max(0L, txEnd - txStart);

        StackFrameSnapshot siteBefore = worst == null ? null
                : Attribution.callSite(stacks.get(worst.before().stackTraceId));
        String sqlBefore = worst == null ? null : sqls.get(worst.before().sqlId);
        String sqlAfter = worst == null ? null : sqls.get(worst.after().sqlId);

        List<IdleLockFinding.Segment> segments = buildSegments(txn, sqls, worst);
        List<IdleLockFinding.WriteRef> writes = summariseWrites(txn, sqls);

        return new IdleLockFinding(
                opId,
                opName,
                txStart,
                txDur,
                maxGap,
                totalIdle,
                siteBefore,
                sqlBefore,
                sqlAfter,
                segments,
                writes);
    }

    private static List<IdleLockFinding.Segment> buildSegments(
            List<Event> txn, Map<Integer, String> sqls, EventGaps.Gap worst) {
        List<IdleLockFinding.Segment> out = new ArrayList<>();
        for (int i = 0; i < txn.size(); i++) {
            Event e = txn.get(i);
            if (i > 0) {
                Event prev = txn.get(i - 1);
                long prevEnd = prev.timestampNanos + Math.max(0L, prev.durationNanos);
                long gap = Math.max(0L, e.timestampNanos - prevEnd);
                if (gap > 0L) {
                    boolean isMax = worst != null
                            && worst.before() == prev && worst.after() == e;
                    out.add(new IdleLockFinding.Segment(
                            IdleLockFinding.SegmentKind.IDLE, gap, null, isMax));
                }
            }
            long dur = Math.max(0L, e.durationNanos);
            String sql = e.sqlId >= 0 ? sqls.get(e.sqlId) : null;
            out.add(new IdleLockFinding.Segment(kindOf(e.eventType), dur, sql, false));
        }
        return out;
    }

    private static List<IdleLockFinding.WriteRef> summariseWrites(
            List<Event> txn, Map<Integer, String> sqls) {
        // LinkedHashMap preserves the order in which each distinct
        // write first appeared in the TX, so the card reads top-to-
        // bottom the way the developer wrote the code.
        Map<String, long[]> byWrite = new LinkedHashMap<>(); // sql -> [count, totalDur]
        for (Event e : txn) {
            if (e.eventType != EventType.EXECUTE_UPDATE.code()
                    && e.eventType != EventType.EXECUTE_BATCH.code()) {
                continue;
            }
            String sql = e.sqlId >= 0 ? sqls.get(e.sqlId) : null;
            if (sql == null) {
                sql = "sql[" + e.sqlId + "]";
            }
            long[] acc = byWrite.computeIfAbsent(sql, k -> new long[2]);
            acc[0]++;
            acc[1] += Math.max(0L, e.durationNanos);
        }
        List<IdleLockFinding.WriteRef> out = new ArrayList<>(byWrite.size());
        for (Map.Entry<String, long[]> e : byWrite.entrySet()) {
            out.add(new IdleLockFinding.WriteRef(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    private static IdleLockFinding.SegmentKind kindOf(byte eventTypeCode) {
        if (eventTypeCode == EventType.EXECUTE_QUERY.code()) return IdleLockFinding.SegmentKind.EXECUTE_QUERY;
        if (eventTypeCode == EventType.EXECUTE_UPDATE.code()) return IdleLockFinding.SegmentKind.EXECUTE_UPDATE;
        if (eventTypeCode == EventType.EXECUTE_BATCH.code()) return IdleLockFinding.SegmentKind.EXECUTE_BATCH;
        if (eventTypeCode == EventType.PREPARE.code()) return IdleLockFinding.SegmentKind.PREPARE;
        if (eventTypeCode == EventType.COMMIT.code()) return IdleLockFinding.SegmentKind.COMMIT;
        if (eventTypeCode == EventType.ROLLBACK.code()) return IdleLockFinding.SegmentKind.ROLLBACK;
        return IdleLockFinding.SegmentKind.OTHER;
    }
}
