package fi.vesas.jdbcprof.storage;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinaryLogWriterTest {

    @Test
    void roundTripsHeaderSqlStackAndEvents(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("rt.jdbclog");

        try (BinaryLogWriter w = new BinaryLogWriter(file)) {
            w.writeSqlDelta(0, List.of("SELECT 1", "INSERT INTO t VALUES (?)"));
            List<StackFrameSnapshot[]> stacks = new ArrayList<>();
            stacks.add(new StackFrameSnapshot[]{
                    new StackFrameSnapshot("com.example.Foo", "bar", 42),
                    new StackFrameSnapshot("com.example.Baz", "run", 7)
            });
            w.writeStackDelta(0, stacks);
            Event[] batch = new Event[2];
            batch[0] = event(100L, 1, (byte) EventType.EXECUTE_QUERY.ordinal(), 0, 0, 250L, -1, 0);
            batch[0].operationInvocationId = 17L;
            batch[1] = event(200L, 1, (byte) EventType.EXECUTE_UPDATE.ordinal(), 1, 0, 300L, 1, 0);
            batch[1].operationInvocationId = 18L;
            w.writeEvents(batch, 2);
        }

        Collector c = new Collector();
        new BinaryLogReader(file).read(c);

        assertThat(c.sqlDeltas).hasSize(1);
        assertThat(c.sqlDeltas.get(0).firstId).isZero();
        assertThat(c.sqlDeltas.get(0).sqls)
                .containsExactly("SELECT 1", "INSERT INTO t VALUES (?)");

        assertThat(c.stackDeltas).hasSize(1);
        assertThat(c.stackDeltas.get(0).stacks).hasSize(1);
        StackFrameSnapshot[] frames = c.stackDeltas.get(0).stacks.get(0);
        assertThat(frames).extracting(StackFrameSnapshot::className)
                .containsExactly("com.example.Foo", "com.example.Baz");
        assertThat(frames[0].lineNumber()).isEqualTo(42);

        assertThat(c.events).hasSize(2);
        assertThat(c.events.get(0).timestampNanos).isEqualTo(100L);
        assertThat(c.events.get(0).operationInvocationId).isEqualTo(17L);
        assertThat(c.events.get(1).sqlId).isEqualTo(1);
        assertThat(c.events.get(1).rowsAffected).isEqualTo(1);
        assertThat(c.events.get(1).operationInvocationId).isEqualTo(18L);
    }

    @Test
    void emptyDeltasDoNotWriteRecords(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("empty.jdbclog");
        try (BinaryLogWriter w = new BinaryLogWriter(file)) {
            w.writeSqlDelta(0, List.of());
            w.writeStackDelta(0, List.of());
            w.writeEvents(new Event[0], 0);
        }

        Collector c = new Collector();
        new BinaryLogReader(file).read(c);
        assertThat(c.sqlDeltas).isEmpty();
        assertThat(c.stackDeltas).isEmpty();
        assertThat(c.events).isEmpty();
    }

    @Test
    void corruptedTailTripsChecksum(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("corrupt.jdbclog");
        try (BinaryLogWriter w = new BinaryLogWriter(file)) {
            w.writeSqlDelta(0, List.of("SELECT 1"));
        }
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 5] ^= (byte) 0xFF; // flip a bit inside the SQL text
        Files.write(file, bytes);

        assertThatThrownBy(() -> new BinaryLogReader(file).read(new Collector()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum mismatch");
    }

    private static Event event(long timestampNanos, int threadId, byte eventType,
                               int sqlId, int stackTraceId, long durationNanos,
                               int rowsAffected, int batchSize) {
        Event e = new Event();
        e.timestampNanos = timestampNanos;
        e.threadId = threadId;
        e.operationId = 0L;
        e.eventType = eventType;
        e.sqlId = sqlId;
        e.stackTraceId = stackTraceId;
        e.durationNanos = durationNanos;
        e.rowsAffected = rowsAffected;
        e.batchSize = batchSize;
        return e;
    }

    private static final class Collector implements BinaryLogReader.Handler {
        final List<SqlDelta> sqlDeltas = new ArrayList<>();
        final List<StackDelta> stackDeltas = new ArrayList<>();
        final List<Event> events = new ArrayList<>();

        @Override public void onSqlDelta(int firstId, List<String> sqls) {
            sqlDeltas.add(new SqlDelta(firstId, sqls));
        }
        @Override public void onStackDelta(int firstId, List<StackFrameSnapshot[]> stacks) {
            stackDeltas.add(new StackDelta(firstId, stacks));
        }
        @Override public void onEvents(List<Event> batch) {
            events.addAll(batch);
        }
    }

    private record SqlDelta(int firstId, List<String> sqls) {}
    private record StackDelta(int firstId, List<StackFrameSnapshot[]> stacks) {}
}
