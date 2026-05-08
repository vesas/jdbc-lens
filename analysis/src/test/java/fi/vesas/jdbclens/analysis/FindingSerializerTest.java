package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class FindingSerializerTest {

    private static String emit(java.util.function.Consumer<JsonWriter> fn) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf);
        JsonWriter jw = new JsonWriter(ps);
        fn.accept(jw);
        ps.flush();
        return buf.toString();
    }

    private static final StackFrameSnapshot FRAME =
            new StackFrameSnapshot("com.example.Dao", "find", 42);

    @Test
    void n1FindingContainsExpectedFields() {
        N1Finding f = new N1Finding(1, "SELECT ?", 0, FRAME, null, 25L, 500_000L);
        String json = emit(jw -> {
            jw.beginArray("findings");
            new FindingSerializer().n1(jw, f);
            jw.endArray();
        });
        assertThat(json).contains("\"type\":\"n1\"");
        assertThat(json).contains("\"severity\":\"MEDIUM\"");
        assertThat(json).contains("\"sql\":\"SELECT ?\"");
        assertThat(json).contains("\"count\":25");
        assertThat(json).contains("\"className\":\"com.example.Dao\"");
        assertThat(json).contains("\"lineNumber\":42");
    }

    @Test
    void n1SeverityHigh() {
        N1Finding f = new N1Finding(1, "SELECT ?", 0, FRAME, null, 150L, 1_000_000L);
        String json = emit(jw -> {
            jw.beginArray("x");
            new FindingSerializer().n1(jw, f);
            jw.endArray();
        });
        assertThat(json).contains("\"severity\":\"HIGH\"");
    }

    @Test
    void n1SeverityLow() {
        N1Finding f = new N1Finding(1, "SELECT ?", 0, FRAME, null, 5L, 100_000L);
        String json = emit(jw -> {
            jw.beginArray("x");
            new FindingSerializer().n1(jw, f);
            jw.endArray();
        });
        assertThat(json).contains("\"severity\":\"LOW\"");
    }

    @Test
    void preparedInLoopFinding() {
        StackFrameSnapshot site = new StackFrameSnapshot("com.Svc", "update", 10);
        RepeatedPrepareFinding f = new RepeatedPrepareFinding("INSERT ?", 55, 100_000L, site, "checkout");
        String json = emit(jw -> {
            jw.beginArray("x");
            new FindingSerializer().preparedInLoop(jw, f);
            jw.endArray();
        });
        assertThat(json).contains("\"type\":\"preparedInLoop\"");
        assertThat(json).contains("\"severity\":\"HIGH\"");
        assertThat(json).contains("\"prepareCount\":55");
        assertThat(json).contains("\"operationName\":\"checkout\"");
    }

    @Test
    void idleLockHighSeverity() {
        assertThat(FindingSerializer.idleLockSeverity(1_500_000_000L)).isEqualTo("HIGH");
    }

    @Test
    void idleLockMediumSeverity() {
        assertThat(FindingSerializer.idleLockSeverity(200_000_000L)).isEqualTo("MEDIUM");
    }

    @Test
    void idleLockLowSeverity() {
        assertThat(FindingSerializer.idleLockSeverity(50_000_000L)).isEqualTo("LOW");
    }

    @Test
    void nullAncestorOmitted() {
        N1Finding f = new N1Finding(1, "SELECT ?", 0, FRAME, null, 25L, 500_000L);
        String json = emit(jw -> {
            jw.beginArray("x");
            new FindingSerializer().n1(jw, f);
            jw.endArray();
        });
        assertThat(json).doesNotContain("\"ancestor\"");
    }

    @Test
    void callSiteWithEnricherNoSourceRoots() {
        // Enricher with empty source roots — should never throw, just omit snippet
        FindingSerializer ser = new FindingSerializer(
                new fi.vesas.jdbclens.analysis.source.CallSiteEnricher(java.util.List.of()));
        String json = emit(jw -> {
            jw.beginArray("x");
            ser.n1(jw, new N1Finding(1, "SELECT ?", 0, FRAME, null, 25L, 500_000L));
            jw.endArray();
        });
        assertThat(json).doesNotContain("sourceSnippet");
        assertThat(json).contains("\"className\":\"com.example.Dao\"");
    }
}
