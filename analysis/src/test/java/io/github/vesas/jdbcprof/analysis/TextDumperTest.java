package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.EventType;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;
import io.github.vesas.jdbcprof.storage.BinaryLogWriter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextDumperTest {

    @Test
    void dumpsInternTablesAndEvents() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-dumper-", ".jdbclog");
        try {
            writeRecording(tmp);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                TextDumper.dump(tmp, ps);
            }
            String out = bytes.toString(StandardCharsets.UTF_8);

            assertThat(out)
                    .contains("SQL templates: 2")
                    .contains("[0] SELECT * FROM orders WHERE id = ?")
                    .contains("[1] INSERT INTO audit VALUES (?, ?)")
                    .contains("Stack traces: 1")
                    .contains("call-site=com.example.OrderDao.findById:47")
                    .contains("com.example.OrderService.load:12")
                    .contains("Events: 2")
                    .contains("PREPARE")
                    .contains("EXECUTE_QUERY")
                    .contains("T42")
                    .contains("Summary")
                    .contains("(call-site, template):")
                    .contains("call-site:")
                    .contains("template:");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeRecording(Path path) throws Exception {
        try (BinaryLogWriter w = new BinaryLogWriter(path)) {
            w.writeSqlDelta(0, List.of(
                    "SELECT * FROM orders WHERE id = ?",
                    "INSERT INTO audit VALUES (?, ?)"));
            StackFrameSnapshot[] frames = new StackFrameSnapshot[]{
                    new StackFrameSnapshot("com.example.OrderDao", "findById", 47),
                    new StackFrameSnapshot("com.example.OrderService", "load", 12)
            };
            w.writeStackDelta(0, Collections.singletonList(frames));
            Event[] batch = new Event[]{
                    buildEvent(1000L, 42, EventType.PREPARE.code(), 0, 0, 12_340L),
                    buildEvent(2234L, 42, EventType.EXECUTE_QUERY.code(), 0, 0, 45_678L)
            };
            w.writeEvents(batch, batch.length);
        }
    }

    private static Event buildEvent(long ts, int thread, byte kind,
                                    int sqlId, int stackId, long duration) {
        Event e = new Event();
        e.timestampNanos = ts;
        e.threadId = thread;
        e.eventType = kind;
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = duration;
        e.rowsAffected = -1;
        e.batchSize = 0;
        return e;
    }
}
