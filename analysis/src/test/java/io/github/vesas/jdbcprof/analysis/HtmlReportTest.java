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

class HtmlReportTest {

    @Test
    void writesSelfContainedHtmlWithTablesAndSummary() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-html-", ".jdbclog");
        try {
            writeRecording(tmp);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            assertThat(html)
                    .startsWith("<!DOCTYPE html>")
                    .contains("<title>jdbc-prof report:")
                    .contains("<style>")
                    .contains("<script>")
                    .contains("jdbc-prof report")
                    .contains("Top call-sites by DB time")
                    .contains("(Call-site, template) pairs")
                    .contains("<h2>Call-sites</h2>")
                    .contains("<h2>Templates</h2>")
                    .contains("com.example.OrderDao.findById:47")
                    .contains("SELECT * FROM orders WHERE id = ?")
                    .contains("INSERT INTO audit VALUES")
                    .contains("</html>");
            assertThat(html.stripTrailing()).endsWith("</html>");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void escapesHtmlSpecialCharsInSqlAndFrames() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-html-esc-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT * FROM t WHERE name = '<script>' AND id > 1"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.Dao<T>", "find", 1)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                Event[] batch = { buildEvent(1000L, 1, EventType.EXECUTE_QUERY.code(), 0, 0, 500L) };
                w.writeEvents(batch, 1);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            // Raw special chars that would break HTML should be absent.
            assertThat(html).doesNotContain("<script>'");
            // Escaped forms should be present instead.
            assertThat(html).contains("&lt;script&gt;");
            assertThat(html).contains("&gt;");
            assertThat(html).contains("com.example.Dao&lt;T&gt;");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void sortSortablesCarryRawNumericAttributes() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-html-sort-", ".jdbclog");
        try {
            writeRecording(tmp);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);
            // Numeric cells must expose the raw value so the client-side
            // sort compares ns, not the "82.5 ms" formatted string.
            assertThat(html).contains("data-raw=\"");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeRecording(Path path) throws Exception {
        try (BinaryLogWriter w = new BinaryLogWriter(path)) {
            w.writeSqlDelta(0, List.of(
                    "SELECT * FROM orders WHERE id = ?",
                    "INSERT INTO audit VALUES (?, ?)"));
            StackFrameSnapshot[] frames = {
                    new StackFrameSnapshot("com.example.OrderDao", "findById", 47),
                    new StackFrameSnapshot("com.example.OrderService", "load", 12)
            };
            w.writeStackDelta(0, Collections.singletonList(frames));
            Event[] batch = {
                    buildEvent(1000L, 42, EventType.PREPARE.code(), 0, 0, 12_340L),
                    buildEvent(2234L, 42, EventType.EXECUTE_QUERY.code(), 0, 0, 45_678L),
                    buildEvent(3500L, 42, EventType.EXECUTE_UPDATE.code(), 1, 0, 2_000L)
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
