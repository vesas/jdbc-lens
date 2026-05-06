package fi.vesas.jdbcprof.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OperationInternTableTest {

    @Test
    void firstInternedNameGetsIdZero() {
        var t = new OperationInternTable();
        assertThat(t.intern("op-a")).isZero();
    }

    @Test
    void sameNameReturnsSameId() {
        var t = new OperationInternTable();
        assertThat(t.intern("checkout")).isEqualTo(t.intern("checkout"));
    }

    @Test
    void distinctNamesGetSequentialIds() {
        var t = new OperationInternTable();
        assertThat(t.intern("a")).isEqualTo(0);
        assertThat(t.intern("b")).isEqualTo(1);
        assertThat(t.intern("c")).isEqualTo(2);
    }

    @Test
    void getReturnsNameForId() {
        var t = new OperationInternTable();
        t.intern("first");
        t.intern("second");
        assertThat(t.get(0)).isEqualTo("first");
        assertThat(t.get(1)).isEqualTo("second");
    }

    @Test
    void getOutOfBoundsThrows() {
        var t = new OperationInternTable();
        t.intern("only");
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> t.get(1));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> t.get(-1));
    }

    @Test
    void internNullThrows() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new OperationInternTable().intern(null));
    }

    @Test
    void entriesSinceZeroReturnsAll() {
        var t = new OperationInternTable();
        t.intern("a");
        t.intern("b");
        t.intern("c");
        assertThat(t.entriesSince(0)).containsExactly("a", "b", "c");
    }

    @Test
    void entriesSinceReturnsOnlyNewEntries() {
        var t = new OperationInternTable();
        t.intern("a");
        t.intern("b");
        int mid = t.size(); // 2
        t.intern("c");
        t.intern("d");
        assertThat(t.entriesSince(mid)).containsExactly("c", "d");
    }

    @Test
    void entriesSinceAtSizeReturnsEmpty() {
        var t = new OperationInternTable();
        t.intern("x");
        assertThat(t.entriesSince(t.size())).isEmpty();
    }

    @Test
    void entriesSinceBeyondSizeReturnsEmpty() {
        var t = new OperationInternTable();
        t.intern("x");
        assertThat(t.entriesSince(100)).isEmpty();
    }

    @Test
    void entriesSinceNegativeThrows() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new OperationInternTable().entriesSince(-1));
    }

    @Test
    void entriesSinceResultIsUnmodifiable() {
        var t = new OperationInternTable();
        t.intern("a");
        List<String> snapshot = t.entriesSince(0);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> snapshot.add("injected"));
    }

    @Test
    void sizeReflectsNumberOfDistinctNamesInterned() {
        var t = new OperationInternTable();
        assertThat(t.size()).isZero();
        t.intern("a");
        t.intern("a"); // duplicate — should not grow size
        t.intern("b");
        assertThat(t.size()).isEqualTo(2);
    }

    @Test
    void concurrentInternSameNameYieldsOneId() throws InterruptedException {
        // Double-checked-locking in the miss path must assign a single id even
        // when many threads race through it simultaneously.
        var t = new OperationInternTable();
        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> ids = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            Thread th = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                ids.add(t.intern("shared-op"));
            });
            th.start();
            threads.add(th);
        }
        ready.await();
        start.countDown();
        for (Thread th : threads) th.join();

        assertThat(ids).as("all threads must see the same id for the same name").hasSize(1);
        assertThat(t.size()).isEqualTo(1);
    }

    @Test
    void concurrentInternDistinctNamesAreAllPresent() throws InterruptedException {
        var t = new OperationInternTable();
        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> ids = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String name = "op-" + i;
            Thread th = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                ids.add(t.intern(name));
            });
            th.start();
            threads.add(th);
        }
        ready.await();
        start.countDown();
        for (Thread th : threads) th.join();

        assertThat(ids).hasSize(threadCount);
        assertThat(t.size()).isEqualTo(threadCount);
    }
}
