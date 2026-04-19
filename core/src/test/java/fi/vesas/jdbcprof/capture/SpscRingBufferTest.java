package fi.vesas.jdbcprof.capture;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpscRingBufferTest {

    @Test
    void rejectsNonPowerOfTwoCapacity() {
        assertThatThrownBy(() -> new SpscRingBuffer(3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpscRingBuffer(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpscRingBuffer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishedEventsAreDrainedInFifoOrder() {
        SpscRingBuffer ring = new SpscRingBuffer(8);

        for (int i = 0; i < 5; i++) {
            Event slot = ring.claim();
            assertThat(slot).isNotNull();
            slot.timestampNanos = 100L + i;
            slot.sqlId = i;
            ring.publish();
        }
        assertThat(ring.size()).isEqualTo(5);

        Event[] out = newArray(8);
        int n = ring.drain(out);
        assertThat(n).isEqualTo(5);
        for (int i = 0; i < n; i++) {
            assertThat(out[i].timestampNanos).isEqualTo(100L + i);
            assertThat(out[i].sqlId).isEqualTo(i);
        }
        assertThat(ring.size()).isZero();
    }

    @Test
    void claimReturnsNullAndDropsAreCountedWhenFull() {
        SpscRingBuffer ring = new SpscRingBuffer(4);
        for (int i = 0; i < 4; i++) {
            Event slot = ring.claim();
            assertThat(slot).isNotNull();
            ring.publish();
        }
        // 5th claim without draining: full.
        assertThat(ring.claim()).isNull();
        ring.recordDrop();
        assertThat(ring.claim()).isNull();
        ring.recordDrop();
        assertThat(ring.droppedCount()).isEqualTo(2L);

        // Drain one; the next claim should succeed.
        Event[] out = newArray(1);
        assertThat(ring.drain(out)).isEqualTo(1);
        assertThat(ring.claim()).isNotNull();
    }

    @Test
    void drainIntoUndersizedArrayReportsPartial() {
        SpscRingBuffer ring = new SpscRingBuffer(16);
        for (int i = 0; i < 10; i++) {
            Event slot = ring.claim();
            slot.sqlId = i;
            ring.publish();
        }
        Event[] small = newArray(4);
        assertThat(ring.drain(small)).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            assertThat(small[i].sqlId).isEqualTo(i);
        }
        assertThat(ring.drain(small)).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            assertThat(small[i].sqlId).isEqualTo(4 + i);
        }
        assertThat(ring.drain(small)).isEqualTo(2);
    }

    @Test
    void singleProducerSingleConsumerRoundTripIsStable() throws InterruptedException {
        // Hammer the ring with one producer and one consumer thread to
        // exercise the volatile-publish / plain-local-cursor contract.
        // Ring is deliberately smaller than the total to force wrap-around.
        final int ringSize = 1024;
        final int total = 200_000;
        SpscRingBuffer ring = new SpscRingBuffer(ringSize);

        AtomicLong consumerSum = new AtomicLong(0);
        AtomicLong consumerCount = new AtomicLong(0);
        CountDownLatch done = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            Event[] batch = newArray(128);
            while (consumerCount.get() < total) {
                int n = ring.drain(batch);
                for (int i = 0; i < n; i++) {
                    consumerSum.addAndGet(batch[i].sqlId);
                }
                consumerCount.addAndGet(n);
                if (n == 0) Thread.onSpinWait();
            }
            done.countDown();
        }, "spsc-consumer");
        consumer.setDaemon(true);
        consumer.start();

        long expectedSum = 0L;
        for (int i = 0; i < total; i++) {
            expectedSum += i;
            Event slot;
            while ((slot = ring.claim()) == null) {
                Thread.onSpinWait();
            }
            slot.sqlId = i;
            ring.publish();
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(consumerCount.get()).isEqualTo(total);
        assertThat(consumerSum.get()).isEqualTo(expectedSum);
        assertThat(ring.droppedCount()).isZero();
    }

    private static Event[] newArray(int n) {
        Event[] a = new Event[n];
        for (int i = 0; i < n; i++) a[i] = new Event();
        return a;
    }
}
