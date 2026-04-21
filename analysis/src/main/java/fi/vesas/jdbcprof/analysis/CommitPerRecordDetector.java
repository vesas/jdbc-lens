package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Flags the "commit per record" anti-pattern: a run of short
 * back-to-back explicit transactions emitted from the same outer
 * application frame. Typical source is a transpiled COBOL paragraph
 * (<code>PERFORM ... UNTIL ...</code> with an <code>EXEC SQL
 * COMMIT</code> inside the loop) or an ORM used naively
 * (<code>txTemplate.execute</code> inside a <code>for</code>).
 *
 * <p>The signature, paraphrased:
 * <ul>
 *   <li>Many short TXs in a row on the same thread within one
 *       logical operation.</li>
 *   <li>Each TX does only a handful of statements (roughly the body
 *       of one loop iteration) and finishes within a tight wall-clock
 *       budget.</li>
 *   <li>The gap between TXs is small — control returns straight to
 *       the top of the loop.</li>
 *   <li>Every TX in the run shares a common outer application frame
 *       (or the same call-site, when no ancestor is reachable). That
 *       shared frame is almost always the {@code run()}/process
 *       method holding the loop — the actual bug.</li>
 * </ul>
 *
 * <p>The {@link IdleLockDetector} looks <em>inside</em> one long
 * transaction for idle gaps. This detector looks <em>across</em> a
 * sequence of short ones for the "commit every iteration" shape —
 * the opposite operational problem, same underlying symptom of
 * transactions being the wrong size.
 *
 * <p>Pure offline work; no hot-path impact.
 */
public final class CommitPerRecordDetector {

    /** A run must contain at least this many consecutive transactions
     *  before we flag it. Below that, a developer looking at the
     *  report can't tell "deliberate unit of work" apart from
     *  "accidentally committed in a loop." */
    public static final int DEFAULT_MIN_RUN_LENGTH = 8;

    /** A TX with more statements than this is likely carrying more
     *  real work than just one iteration, so it doesn't fit the
     *  commit-per-record shape. */
    public static final int DEFAULT_MAX_QUERIES_PER_TXN = 5;

    /** Per-TX wall-clock budget. Longer TXs are either doing real
     *  multi-statement work or waiting on external systems — both
     *  cases mean "this isn't a bare commit-in-a-loop." */
    public static final long DEFAULT_MAX_TXN_WALL_NANOS = 20_000_000L; // 20 ms

    /** Maximum gap between the end of one TX and the start of the
     *  next. Wider gaps mean the loop body is doing real work
     *  outside the DB, which is a different pattern. */
    public static final long DEFAULT_MAX_INTER_TXN_GAP_NANOS = 50_000_000L; // 50 ms

    /** Cap on the number of distinct templates sampled from one TX
     *  to describe the loop body in the finding card. */
    private static final int SAMPLE_SQL_CAP = 5;

    private final int minRunLength;
    private final int maxQueriesPerTxn;
    private final long maxTxnWallNanos;
    private final long maxInterTxnGapNanos;

    public CommitPerRecordDetector() {
        this(DEFAULT_MIN_RUN_LENGTH, DEFAULT_MAX_QUERIES_PER_TXN,
                DEFAULT_MAX_TXN_WALL_NANOS, DEFAULT_MAX_INTER_TXN_GAP_NANOS);
    }

    public CommitPerRecordDetector(int minRunLength, int maxQueriesPerTxn,
                                   long maxTxnWallNanos, long maxInterTxnGapNanos) {
        this.minRunLength = minRunLength;
        this.maxQueriesPerTxn = maxQueriesPerTxn;
        this.maxTxnWallNanos = maxTxnWallNanos;
        this.maxInterTxnGapNanos = maxInterTxnGapNanos;
    }

    public List<CommitPerRecordFinding> detect(
            Map<Long, List<Transaction>> txByOp,
            Map<Long, String> opNames,
            Map<Integer, String> sqls,
            Map<Integer, StackFrameSnapshot[]> stacks) {

        List<CommitPerRecordFinding> out = new ArrayList<>();
        for (Map.Entry<Long, List<Transaction>> entry : txByOp.entrySet()) {
            long opId = entry.getKey();
            List<Transaction> txs = entry.getValue();
            if (txs == null || txs.isEmpty()) {
                continue;
            }

            Map<Integer, List<Transaction>> byThread = new HashMap<>();
            for (Transaction tx : txs) {
                byThread.computeIfAbsent(tx.threadId(), k -> new ArrayList<>()).add(tx);
            }
            for (List<Transaction> perThread : byThread.values()) {
                perThread.sort(Comparator.comparingLong(Transaction::startNanos));
                collectRuns(opId, opNames.get(opId), perThread, sqls, stacks, out);
            }
        }

        // Rank by total wall time the pattern consumed — a 200-TX run at
        // 1 ms each dominates a 10-TX run at 2 ms each.
        out.sort(Comparator.comparingLong(CommitPerRecordFinding::totalWallNanos).reversed());
        return out;
    }

    private void collectRuns(long opId, String opName,
                             List<Transaction> sorted,
                             Map<Integer, String> sqls,
                             Map<Integer, StackFrameSnapshot[]> stacks,
                             List<CommitPerRecordFinding> out) {
        int i = 0;
        while (i < sorted.size()) {
            Transaction start = sorted.get(i);
            if (!isCandidate(start)) {
                i++;
                continue;
            }
            StackFrameSnapshot[] startFrames = stacks.get(start.firstStackId());
            StackFrameSnapshot startSite = Attribution.callSite(startFrames);
            if (startSite == null) {
                i++;
                continue;
            }
            StackFrameSnapshot ancestor = Attribution.ancestorFrame(startFrames, startSite);

            int runEnd = i;
            Transaction prev = start;
            for (int j = i + 1; j < sorted.size(); j++) {
                Transaction next = sorted.get(j);
                if (!isCandidate(next)) {
                    break;
                }
                long gap = next.startNanos() - prev.endNanos();
                if (gap > maxInterTxnGapNanos) {
                    break;
                }
                StackFrameSnapshot[] nextFrames = stacks.get(next.firstStackId());
                if (!sharesLoopContext(nextFrames, ancestor, startSite)) {
                    break;
                }
                runEnd = j;
                prev = next;
            }

            int runLen = runEnd - i + 1;
            if (runLen >= minRunLength) {
                out.add(buildFinding(opId, opName,
                        sorted.subList(i, runEnd + 1),
                        ancestor, startSite, sqls));
            }
            i = runEnd + 1;
        }
    }

    private boolean isCandidate(Transaction tx) {
        if (tx.open()) {
            // No terminator yet — not a "commit" per record.
            return false;
        }
        if (tx.queryCount() > maxQueriesPerTxn) {
            return false;
        }
        return tx.wallClockNanos() <= maxTxnWallNanos;
    }

    /**
     * Returns true if the next TX's stack shares the loop context of
     * the run. Prefer the outer ancestor when we have one — a loop
     * body will legitimately change its innermost call-site between
     * iterations (e.g. different helper methods), but the ancestor
     * stays put. Fall back to innermost-site equality when no
     * ancestor is reachable.
     */
    private static boolean sharesLoopContext(StackFrameSnapshot[] nextFrames,
                                             StackFrameSnapshot ancestor,
                                             StackFrameSnapshot startSite) {
        if (nextFrames == null) {
            return false;
        }
        if (ancestor != null) {
            return framesContain(nextFrames, ancestor);
        }
        StackFrameSnapshot nextSite = Attribution.callSite(nextFrames);
        return sameFrame(nextSite, startSite);
    }

    private static CommitPerRecordFinding buildFinding(
            long opId, String opName,
            List<Transaction> run,
            StackFrameSnapshot ancestor,
            StackFrameSnapshot site,
            Map<Integer, String> sqls) {
        int runLen = run.size();
        long firstTs = run.get(0).startNanos();
        long lastTs = run.get(runLen - 1).endNanos();
        long totalWall = Math.max(0L, lastTs - firstTs);

        long sumPerTxnWall = 0L;
        long sumDb = 0L;
        long totalWrites = 0L;
        long totalReads = 0L;
        for (Transaction tx : run) {
            sumPerTxnWall += tx.wallClockNanos();
            sumDb += tx.dbTimeNanos();
            totalWrites += tx.writeCount();
            totalReads += tx.readCount();
        }
        long avgWall = sumPerTxnWall / runLen;

        List<String> sampleSqls = new ArrayList<>();
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (Event e : run.get(0).events()) {
            if (e.sqlId < 0 || !seen.add(e.sqlId)) {
                continue;
            }
            String s = sqls.get(e.sqlId);
            if (s != null) {
                sampleSqls.add(s);
            }
            if (sampleSqls.size() >= SAMPLE_SQL_CAP) {
                break;
            }
        }

        return new CommitPerRecordFinding(
                opId, opName,
                runLen, totalWall, avgWall, sumDb,
                firstTs, lastTs,
                ancestor, site,
                (int) (totalWrites / runLen),
                (int) (totalReads / runLen),
                sampleSqls);
    }

    private static boolean framesContain(StackFrameSnapshot[] frames,
                                         StackFrameSnapshot target) {
        if (frames == null || target == null) {
            return false;
        }
        for (StackFrameSnapshot f : frames) {
            if (sameFrame(f, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameFrame(StackFrameSnapshot a, StackFrameSnapshot b) {
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.className(), b.className())
                && Objects.equals(a.methodName(), b.methodName());
    }
}
