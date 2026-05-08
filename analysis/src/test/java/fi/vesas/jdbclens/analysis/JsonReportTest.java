package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;
import fi.vesas.jdbclens.storage.BinaryLogWriter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReportTest {

    @Test
    void outputIsValidJsonWithExpectedTopLevelKeys() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-json-", ".jdbclog");
        try {
            writeRecording(tmp);
            String json = render(tmp);

            // Top-level structural fields must be present
            assertThat(json).contains("\"schemaVersion\":1");
            assertThat(json).contains("\"recordingFile\":");
            assertThat(json).contains("\"recordedAt\":");
            assertThat(json).contains("\"summary\":");
            assertThat(json).contains("\"findings\":");
            assertThat(json).contains("\"topCallSites\":");
            assertThat(json).contains("\"topTemplates\":");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void summaryContainsEventCountAndDistinctTemplates() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-json-summary-", ".jdbclog");
        try {
            writeRecording(tmp);
            String json = render(tmp);

            assertThat(json).contains("\"totalEvents\":");
            assertThat(json).contains("\"distinctSqlTemplates\":");
            assertThat(json).contains("\"findingCounts\":");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void n1FindingAppearsInOutput() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-json-n1-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT * FROM orders WHERE id = ?"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.OrderDao", "findById", 47),
                        new StackFrameSnapshot("com.example.OrderService", "loadAll", 12)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                // 12 executions — above default N+1 threshold of 10
                Event[] batch = new Event[12];
                for (int i = 0; i < 12; i++) {
                    batch[i] = buildEvent(1_000L + i, 1,
                            EventType.EXECUTE_QUERY.code(), 0, 0, 5_000L);
                }
                w.writeEvents(batch, batch.length);
            }
            String json = render(tmp);

            assertThat(json).contains("\"type\":\"n1\"");
            assertThat(json).contains("\"count\":12");
            assertThat(json).contains("\"className\":\"com.example.OrderDao\"");
            assertThat(json).contains("\"methodName\":\"findById\"");
            assertThat(json).contains("\"lineNumber\":47");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void topCallSitesAndTemplatesPresent() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-json-top-", ".jdbclog");
        try {
            writeRecording(tmp);
            String json = render(tmp);

            // Top call-sites must reference real class from recording
            assertThat(json).contains("\"className\":\"com.example.OrderDao\"");
            // Top templates must include the SQL
            assertThat(json).contains("\"sql\":\"SELECT * FROM orders WHERE id = ?\"");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void jsonIsWellFormedNoUnbalancedBraces() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-json-wf-", ".jdbclog");
        try {
            writeRecording(tmp);
            String json = render(tmp);

            int opens  = countChar(json, '{');
            int closes = countChar(json, '}');
            assertThat(opens).as("object braces balanced").isEqualTo(closes);

            int arrOpen  = countChar(json, '[');
            int arrClose = countChar(json, ']');
            assertThat(arrOpen).as("array brackets balanced").isEqualTo(arrClose);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void emptyRecordingProducesValidEmptyFindings() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-json-empty-", ".jdbclog");
        try {
            // Minimal recording: just one SQL, no events
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT 1"));
            }
            String json = render(tmp);

            assertThat(json).contains("\"findings\":[]");
            assertThat(json).contains("\"schemaVersion\":1");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // --- helpers ---

    private static String render(Path path) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(buf, false, StandardCharsets.UTF_8)) {
            JsonReport.write(path, ps);
        }
        return buf.toString(StandardCharsets.UTF_8);
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
        e.operationId = AnalysisModel.NO_OPERATION;
        e.operationInvocationId = -1L;
        e.parameterFingerprint = 0L;
        e.parameterValuesId = -1;
        return e;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}
