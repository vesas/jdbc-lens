package io.github.vesas.jdbcprof.capture;

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
 * <p>Misses take a second walk to snapshot frames into immutable
 * records and an insertion under {@code synchronized (this)}. Misses
 * plateau after warm-up for any real application (spec §5.5).
 *
 * <p>64-bit hash collisions are treated as matches. At expected
 * trace cardinality (thousands) the collision probability is on the
 * order of 10⁻¹³; upgrading to keyed-by-(hash, frames) dedup is
 * deferred until benchmarks show it matters.
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
    private static final StackWalker WALKER = StackWalker.getInstance();

    private final int maxDepth;
    private final ConcurrentHashMap<Long, Integer> byHash = new ConcurrentHashMap<>();
    private final List<StackFrameSnapshot[]> entries = new ArrayList<>();

    public StackTraceInternTable(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int internCurrent() {
        final int depth = maxDepth;

        long hash = WALKER.walk(stream ->
                stream.limit(depth)
                        .mapToLong(StackTraceInternTable::frameDigest)
                        .reduce(FNV_OFFSET, StackTraceInternTable::mix));

        Integer hit = byHash.get(hash);
        if (hit != null) {
            return hit;
        }

        StackFrameSnapshot[] snapshot = WALKER.walk(stream ->
                stream.limit(depth)
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
        // Packs three component identities into one long. Cached String
        // hashCodes make this allocation-free once per frame.
        long d = ((long) f.getClassName().hashCode()) << 32;
        d ^= f.getMethodName().hashCode();
        d ^= (long) f.getLineNumber() << 16;
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
