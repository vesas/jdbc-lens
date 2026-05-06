package fi.vesas.jdbcprof.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns a compact int id to each distinct application stack trace
 * observed during recording (spec §5.4). Events reference the id;
 * the frame snapshots live once here.
 *
 * <h2>Hot-path design</h2>
 *
 * Hits (trace already seen) take a single {@link StackWalker} pass
 * that folds frame digests into a 64-bit hash and a {@link
 * ConcurrentHashMap} lookup. No snapshot objects are allocated on
 * this path.
 *
 * <p>The hash function deliberately uses {@link
 * StackWalker.StackFrame#getByteCodeIndex bci} and the declaring
 * {@link Class}'s identity rather than line numbers and class names.
 * Line-number resolution forces a {@code LineNumberTable} scan in
 * the class metadata — the dominant per-frame cost in the previous
 * design — and cached String hashCodes still go through one extra
 * field read each. Identity hash on a {@link Class} is a single
 * native field, and bci is a primitive on the frame: both are
 * essentially free, dropping the per-frame work an order of
 * magnitude. The hash basis is purely in-memory; on-disk snapshots
 * keep human-readable class names and line numbers (see below).
 *
 * <p>Misses take a second walk to snapshot frames into immutable
 * records and an insertion under {@code synchronized (this)}. The
 * snapshot path uses {@link StackWalker.StackFrame#getLineNumber}
 * deliberately — line numbers are the form the report needs, the
 * cost is paid once per distinct trace, and snapshots plateau after
 * warm-up for any real application (spec §5.5).
 *
 * <p>64-bit hash collisions are treated as matches. At expected
 * trace cardinality (thousands) the collision probability is on the
 * order of 10⁻¹³; upgrading to keyed-by-(hash, frames) dedup is
 * deferred until benchmarks show it matters.
 *
 * <p>The {@code skipFrames} constructor parameter drops a fixed
 * number of frames at the bottom of every walk — used by
 * {@link CaptureContext} to elide its own dispatch frames so neither
 * the hash nor the snapshot wastes work materializing them. The
 * skipped frames would be filtered by {@code Attribution} at report time
 * anyway; doing it at capture time saves the per-frame
 * {@link StackWalker.StackFrame} materialization that the analyzer
 * never used. {@link java.util.stream.Stream#skip(long) Stream.skip}
 * advances the underlying iterator without calling
 * {@code getDeclaringClass()} / {@code getMethodName()} /
 * {@code getByteCodeIndex()}, so the skipped-frame cost truly is zero.
 *
 * <h2>Known hot-path allocations</h2>
 *
 * The Stream pipeline used for folding frame digests and the lambda
 * capturing {@code maxDepth} both allocate small objects per call.
 * HotSpot's escape analysis typically eliminates them; benchmarks
 * will confirm and we can inline a custom {@code Consumer} if
 * necessary.
 */
public final class StackTraceInternTable {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    // RETAIN_CLASS_REFERENCE so frameDigest can call getDeclaringClass()
    // and use its identity hash for the per-frame digest. Without this
    // option the StackWalker hides Class references and we'd have to go
    // back through getClassName().hashCode() — the slower path the
    // previous version of this class used.
    private static final StackWalker WALKER = StackWalker.getInstance(
            StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final int maxDepth;
    private final int skipFrames;
    private final ConcurrentHashMap<Long, Integer> byHash = new ConcurrentHashMap<>();
    private final List<StackFrameSnapshot[]> entries = new ArrayList<>();

    public StackTraceInternTable(int maxDepth) {
        this(maxDepth, 0);
    }

    public StackTraceInternTable(int maxDepth, int skipFrames) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
        if (skipFrames < 0) {
            throw new IllegalArgumentException("skipFrames must be >= 0, got " + skipFrames);
        }
        this.maxDepth = maxDepth;
        this.skipFrames = skipFrames;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int internCurrent() {
        final int depth = maxDepth;
        final int skip = skipFrames;

        long hash = WALKER.walk(stream ->
                stream.skip(skip)
                        .limit(depth)
                        .mapToLong(StackTraceInternTable::frameDigest)
                        .reduce(FNV_OFFSET, StackTraceInternTable::mix));

        Integer hit = byHash.get(hash);
        if (hit != null) {
            return hit;
        }

        StackFrameSnapshot[] snapshot = WALKER.walk(stream ->
                stream.skip(skip)
                        .limit(depth)
                        .map(StackTraceInternTable::snapshot)
                        .toArray(StackFrameSnapshot[]::new));

        synchronized (this) {
            hit = byHash.get(hash);
            if (hit != null) {
                return hit;
            }
            int id = entries.size();
            entries.add(snapshot);
            byHash.put(hash, id);
            return id;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized StackFrameSnapshot[] get(int id) {
        return entries.get(id);
    }

    /**
     * Snapshot of entries with id {@code >= since}. The sink uses this
     * to flush intern-table deltas (spec §6). The returned list is a
     * stable copy; entry contents are immutable records.
     */
    public synchronized List<StackFrameSnapshot[]> entriesSince(int since) {
        int n = entries.size();
        if (since < 0) {
            throw new IllegalArgumentException("since must be >= 0, got " + since);
        }
        if (since >= n) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(entries.subList(since, n)));
    }

    private static long frameDigest(StackWalker.StackFrame f) {
        // Packs three component identities into one long. The Class
        // identity hash and bci are both primitive field reads; the
        // method-name hashCode is cached on the interned String after
        // the first access. No LineNumberTable scan, no allocation.
        long d = ((long) System.identityHashCode(f.getDeclaringClass())) << 32;
        d ^= f.getMethodName().hashCode();
        d ^= (long) f.getByteCodeIndex() << 16;
        return d;
    }

    private static long mix(long h, long v) {
        h ^= v;
        return h * FNV_PRIME;
    }

    private static StackFrameSnapshot snapshot(StackWalker.StackFrame f) {
        return new StackFrameSnapshot(f.getClassName(), f.getMethodName(), f.getLineNumber());
    }
}
