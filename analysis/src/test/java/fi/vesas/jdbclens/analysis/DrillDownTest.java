package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.ParameterValues;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DrillDownTest {

    @Test
    void timelineSeparatesConsecutiveInvocations(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("ops").resolve("processOrder.html");

        Event a = event(1_000L, 0L, 42L);
        Event b = event(1_500L, 0L, 42L);
        Event c = event(2_000L, 0L, 43L);
        Event d = event(2_500L, 0L, 44L);

        DrillDown.Inputs in = new DrillDown.Inputs(
                0L,
                "processOrder",
                List.of(a, b, c, d),
                Map.of(0, "SELECT 1"),
                Map.of(0, new StackFrameSnapshot[]{
                        new StackFrameSnapshot("com.example.Svc", "run", 10)
                }),
                Map.<Integer, ParameterValues>of(),
                "../report.html");

        DrillDown.write(out, in);
        String html = Files.readString(out);

        assertThat(html).contains("Timeline");
        assertThat(countOccurrences(html, "<tr class=\"inv-divider\">"))
                .as("divider emitted between invocations, not before the first")
                .isEqualTo(2);
        assertThat(html).contains("Invocation 2 of 3");
        assertThat(html).contains("Invocation 3 of 3");
    }

    @Test
    void singleInvocationGetsNoDivider(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("ops").resolve("onlyOne.html");

        DrillDown.Inputs in = new DrillDown.Inputs(
                0L,
                "onlyOne",
                List.of(event(1_000L, 0L, 7L), event(1_500L, 0L, 7L)),
                Map.of(0, "SELECT 1"),
                Map.of(0, new StackFrameSnapshot[]{
                        new StackFrameSnapshot("com.example.Svc", "run", 10)
                }),
                Map.<Integer, ParameterValues>of(),
                "../report.html");

        DrillDown.write(out, in);
        String html = Files.readString(out);

        assertThat(html).doesNotContain("<tr class=\"inv-divider\">");
    }

    private static Event event(long ts, long opId, long invocationId) {
        Event e = new Event();
        e.timestampNanos = ts;
        e.threadId = 1;
        e.operationId = opId;
        e.operationInvocationId = invocationId;
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.sqlId = 0;
        e.stackTraceId = 0;
        e.durationNanos = 500L;
        e.rowsAffected = -1;
        e.batchSize = 0;
        return e;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
