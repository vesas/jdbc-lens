package io.github.vesas.jdbcprof.capture;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-producer single-consumer ring buffer of {@link Event} slots
 * (spec §5.6).
 *
 * <p>The producer is an application thread publishing JDBC events.
 * The consumer is the profiler's sink thread. Capacity is a
 * power of two so positioning reduces to a bitmask.
 *
 * <p>Slots are pre-allocated once and reused in place. The producer
 * calls {@link #claim()} to obtain the next writable slot, populates
 * its fields directly, and calls {@link #publish()} to advance the
 * tail cursor. The consumer calls {@link #drain(Event[])} to copy
 * ready events into its own array.
 *
 * <p>Producer and consumer each maintain a plain-long local cursor
 * and only publish updates via {@link AtomicLong#lazySet(long)}
 * (release-store). The cross-thread reads use {@code get()}
 * (volatile). This is the standard SPSC pattern: no CAS, no locks.
 *
 * <p><b>Overflow policy (TODO: align with spec §5.6).</b> This
 * implementation drops the <i>newest</i> event when full —
 * {@link #claim()} returns {@code null} and the caller must invoke
 * {@link #recordDrop()}. The spec's default is drop-<i>oldest</i>
 * (ring-buffer overwrite). Implementing overwrite safely requires
 * per-slot sequence numbers so the consumer can detect torn reads
 * against a producer that is rewriting the slot being drained;
 * postponed to a later Phase 1 iteration once capture-path
 * benchmarks tell us whether drop-newest is adequate at steady-state
 * load.
 */
public final class SpscRingBuffer {

    private final Event[] slots;
    private final int mask;

    // Published cursors (release-store on write, acquire-load on read).
    private final AtomicLong tail = new AtomicLong(0L);
    private final AtomicLong head = new AtomicLong(0L);

    // Producer- and consumer-local caches; never touched by the other side.
    private long producerTail = 0L;
    private long consumerHead = 0L;

    private long droppedCount = 0L; // producer-only writes; published via volatile read in droppedCount().
    private final AtomicLong droppedPublished = new AtomicLong(0L);

    public SpscRingBuffer(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two >= 2, got " + capacity);
        }
        this.slots = new Event[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new Event();
        }
        this.mask = capacity - 1;
    }

    public int capacity() {
        return slots.length;
    }

    public long droppedCount() {
        return droppedPublished.get();
    }

    /**
     * Approximate size as seen by any observer. Safe to call from
     * either side; returns a momentarily-stale value.
     */
    public int size() {
        long t = tail.get();
        long h = head.get();
        return (int) (t - h);
    }

    /**
     * Producer: obtain the next writable slot, or {@code null} if the
     * ring is full. On null, the caller must invoke
     * {@link #recordDrop()} to account for the lost event.
     */
    public Event claim() {
        long h = head.get();
        if (producerTail - h >= slots.length) {
            return null;
        }
        return slots[(int) (producerTail & mask)];
    }

    /** Producer: publish the slot most recently returned by {@link #claim()}. */
    public void publish() {
        producerTail++;
        tail.lazySet(producerTail);
    }

    /** Producer: record that an event was dropped (buffer full). */
    public void recordDrop() {
        droppedCount++;
        droppedPublished.lazySet(droppedCount);
    }

    /**
     * Consumer: copy up to {@code out.length} events into {@code out}
     * and advance the read cursor by that many slots. Returns the
     * number of events copied. The caller owns {@code out} and its
     * elements; the consumer's view is independent of the producer's
     * continued writes into other slots.
     */
    public int drain(Event[] out) {
        long t = tail.get();
        long available = t - consumerHead;
        if (available <= 0L) {
            return 0;
        }
        int toCopy = (int) Math.min(available, out.length);
        for (int i = 0; i < toCopy; i++) {
            out[i].copyFrom(slots[(int) ((consumerHead + i) & mask)]);
        }
        consumerHead += toCopy;
        head.lazySet(consumerHead);
        return toCopy;
    }
}
