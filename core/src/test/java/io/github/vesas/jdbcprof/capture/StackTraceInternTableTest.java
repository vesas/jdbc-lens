package io.github.vesas.jdbcprof.capture;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackTraceInternTableTest {

    @Test
    void firstInternReturnsZero() {
        StackTraceInternTable t = new StackTraceInternTable(30);
        assertThat(t.internCurrent()).isZero();
    }

    @Test
    void repeatedCallsFromSameSiteReturnSameId() {
        // Calling internCurrent twice from the exact same bytecode location
        // (inside a loop) must collapse to a single id.
        StackTraceInternTable t = new StackTraceInternTable(30);
        int[] ids = new int[2];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = captureVia(t);
        }
        assertThat(ids[0]).isEqualTo(ids[1]);
        assertThat(t.size()).isEqualTo(1);
    }

    @Test
    void differentCallSitesReturnDistinctIds() {
        StackTraceInternTable t = new StackTraceInternTable(30);
        int a = captureVia(t);
        int b = captureViaOtherMethod(t);
        assertThat(a).isNotEqualTo(b);
        assertThat(t.size()).isEqualTo(2);
    }

    @Test
    void snapshotFramesCarryExpectedIdentity() {
        StackTraceInternTable t = new StackTraceInternTable(30);
        int id = captureVia(t);
        StackFrameSnapshot[] frames = t.get(id);

        assertThat(frames).isNotEmpty();
        assertThat(frames[0].className())
                .isEqualTo("io.github.vesas.jdbcprof.capture.StackTraceInternTable");
        assertThat(frames[0].methodName()).isEqualTo("internCurrent");

        assertThat(frames[1].className())
                .isEqualTo("io.github.vesas.jdbcprof.capture.StackTraceInternTableTest");
        assertThat(frames[1].methodName()).isEqualTo("captureVia");
    }

    @Test
    void depthLimitCapsFrameCount() {
        StackTraceInternTable t = new StackTraceInternTable(2);
        int id = t.internCurrent();
        StackFrameSnapshot[] frames = t.get(id);
        assertThat(frames).hasSize(2);
    }

    @Test
    void entriesSinceReturnsDelta() {
        StackTraceInternTable t = new StackTraceInternTable(30);
        int first = captureVia(t);
        int second = captureViaOtherMethod(t);
        assertThat(t.entriesSince(0)).hasSize(2);
        assertThat(t.entriesSince(1)).hasSize(1);
        assertThat(t.entriesSince(2)).isEmpty();
        // The single entry at index 1 is the second trace, which came
        // through a different helper.
        StackFrameSnapshot[] delta = t.entriesSince(1).get(0);
        assertThat(delta[1].methodName()).isEqualTo("captureViaOtherMethod");
        // Guard against unused-warning on returned ids.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void constructorRejectsBadDepth() {
        assertThatThrownBy(() -> new StackTraceInternTable(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StackTraceInternTable(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentSameSiteCallsAllCollapse() throws InterruptedException {
        // N threads each intern at the same call site; all should see
        // the same id even under race on first insertion.
        final int threads = 8;
        final int perThread = 500;
        StackTraceInternTable t = new StackTraceInternTable(30);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Integer> seen = new HashSet<>();
        AtomicInteger failures = new AtomicInteger(0);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            int id = captureVia(t);
                            synchronized (seen) {
                                seen.add(id);
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isZero();
        assertThat(seen).hasSize(1);
    }

    // Helpers: separate methods so the call-site tests can distinguish
    // identical-site from different-site traces.

    private int captureVia(StackTraceInternTable t) {
        return t.internCurrent();
    }

    private int captureViaOtherMethod(StackTraceInternTable t) {
        return t.internCurrent();
    }
}
