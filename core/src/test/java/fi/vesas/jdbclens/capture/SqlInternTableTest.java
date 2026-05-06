package fi.vesas.jdbclens.capture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlInternTableTest {

    @Test
    void distinctStringsGetDistinctMonotonicIds() {
        SqlInternTable t = new SqlInternTable();
        assertThat(t.intern("a")).isZero();
        assertThat(t.intern("b")).isEqualTo(1);
        assertThat(t.intern("c")).isEqualTo(2);
        assertThat(t.size()).isEqualTo(3);
    }

    @Test
    void repeatedInternReturnsSameId() {
        SqlInternTable t = new SqlInternTable();
        int first = t.intern("SELECT 1");
        int second = t.intern("SELECT 1");
        assertThat(second).isEqualTo(first);
        assertThat(t.size()).isEqualTo(1);
    }

    @Test
    void getReturnsEntryByIdInInsertionOrder() {
        SqlInternTable t = new SqlInternTable();
        t.intern("a");
        t.intern("b");
        t.intern("c");
        assertThat(t.get(0)).isEqualTo("a");
        assertThat(t.get(1)).isEqualTo("b");
        assertThat(t.get(2)).isEqualTo("c");
    }

    @Test
    void entriesSinceReturnsDelta() {
        SqlInternTable t = new SqlInternTable();
        t.intern("a");
        t.intern("b");
        List<String> deltaFrom1 = t.entriesSince(1);
        assertThat(deltaFrom1).containsExactly("b");

        t.intern("c");
        t.intern("d");
        List<String> deltaFrom2 = t.entriesSince(2);
        assertThat(deltaFrom2).containsExactly("c", "d");

        assertThat(t.entriesSince(4)).isEmpty();
    }

    @Test
    void entriesSinceRejectsNegative() {
        SqlInternTable t = new SqlInternTable();
        t.intern("a");
        assertThatThrownBy(() -> t.entriesSince(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullInput() {
        SqlInternTable t = new SqlInternTable();
        assertThatThrownBy(() -> t.intern(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentInternsPreserveUniqueness() throws InterruptedException {
        // N threads each intern the same set of strings in different orders.
        // After all are done, exactly |set| ids exist and every call returned
        // one of them; the intern's contract is that identical strings always
        // map to the same id regardless of who won the race.
        final int distinct = 200;
        final int threads = 8;
        SqlInternTable t = new SqlInternTable();
        ConcurrentHashMap<String, Integer> observed = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int tid = 0; tid < threads; tid++) {
                final int offset = tid;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < distinct; i++) {
                            String s = "sql-" + ((i + offset * 13) % distinct);
                            int id = t.intern(s);
                            Integer prev = observed.put(s, id);
                            if (prev != null && prev != id) {
                                throw new AssertionError(
                                        "id for " + s + " changed: " + prev + " -> " + id);
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
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

        assertThat(t.size()).isEqualTo(distinct);
        assertThat(observed).hasSize(distinct);
        // Ids must cover [0, distinct) exactly once.
        boolean[] seen = new boolean[distinct];
        for (int id : observed.values()) {
            assertThat(id).isBetween(0, distinct - 1);
            assertThat(seen[id]).isFalse();
            seen[id] = true;
        }
    }
}
