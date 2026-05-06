package fi.vesas.jdbcprof.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ParameterValuesInternTableTest {

    @Test
    void firstInternedValueGetsIdZero() {
        var t = new ParameterValuesInternTable();
        assertThat(t.intern(pv("42"))).isZero();
    }

    @Test
    void identicalSlotsMappedToSameId() {
        // Record equality is content-keyed; two distinct ParameterValues
        // objects with the same slots must intern to the same id.
        var t = new ParameterValuesInternTable();
        int id1 = t.intern(pv("Alice", "30"));
        int id2 = t.intern(pv("Alice", "30"));
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void differentSlotsGetDifferentIds() {
        var t = new ParameterValuesInternTable();
        int id1 = t.intern(pv("1"));
        int id2 = t.intern(pv("2"));
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void sameFirstSlotButDifferentSecondSlotAreDifferent() {
        // Guards against fingerprint collisions where only a later slot differs.
        var t = new ParameterValuesInternTable();
        int id1 = t.intern(pv("Alice", "London"));
        int id2 = t.intern(pv("Alice", "Paris"));
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void getReturnsValueById() {
        var t = new ParameterValuesInternTable();
        ParameterValues v = pv("42", "hello");
        t.intern(v);
        assertThat(t.get(0)).isEqualTo(v);
    }

    @Test
    void getOutOfBoundsThrows() {
        var t = new ParameterValuesInternTable();
        t.intern(pv("x"));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> t.get(1));
    }

    @Test
    void internNullThrows() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ParameterValuesInternTable().intern(null));
    }

    @Test
    void entriesSinceReturnsDelta() {
        var t = new ParameterValuesInternTable();
        ParameterValues a = pv("1");
        ParameterValues b = pv("2");
        t.intern(a);
        int mark = t.size();
        t.intern(b);
        assertThat(t.entriesSince(mark)).containsExactly(b);
    }

    @Test
    void entriesSinceAtSizeReturnsEmpty() {
        var t = new ParameterValuesInternTable();
        t.intern(pv("x"));
        assertThat(t.entriesSince(t.size())).isEmpty();
    }

    @Test
    void entriesSinceNegativeThrows() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ParameterValuesInternTable().entriesSince(-1));
    }

    @Test
    void concurrentInternSameValueYieldsOneId() throws InterruptedException {
        // Double-checked-locking in the miss path must assign a single id even
        // when many threads race through it simultaneously with equal bindings.
        var t = new ParameterValuesInternTable();
        ParameterValues v = pv("99", "ACME");
        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> ids = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            Thread th = new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                // Each thread interns a freshly constructed but content-equal value.
                ids.add(t.intern(pv("99", "ACME")));
            });
            th.start();
            threads.add(th);
        }
        ready.await();
        start.countDown();
        for (Thread th : threads) th.join();

        assertThat(ids).as("all threads must see the same id for equal ParameterValues").hasSize(1);
        assertThat(t.size()).isEqualTo(1);
    }

    // Slot index 0 is unused (JDBC parameters are 1-based); mirror that convention
    // here so tests reflect real usage patterns captured by CapturingPreparedStatement.
    private static ParameterValues pv(String... params) {
        List<String> slots = new ArrayList<>();
        slots.add(""); // slot 0 unused
        for (String p : params) slots.add(p);
        return new ParameterValues(slots);
    }
}
