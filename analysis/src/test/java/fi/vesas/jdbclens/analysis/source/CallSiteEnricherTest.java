package fi.vesas.jdbclens.analysis.source;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CallSiteEnricherTest {

    @TempDir
    Path root;

    @Test
    void returnsNullForEmptySourceRoots() {
        CallSiteEnricher e = new CallSiteEnricher(List.of());
        StackFrameSnapshot frame = new StackFrameSnapshot("com.example.Dao", "find", 5);
        assertThat(e.enrich(frame)).isNull();
    }

    @Test
    void returnsNullForNullFrame() {
        CallSiteEnricher e = new CallSiteEnricher(List.of(root));
        assertThat(e.enrich(null)).isNull();
    }

    @Test
    void findsFileAndExtractsWindow() throws IOException {
        // Create com/example/Dao.java under root with 10 lines
        Path pkg = root.resolve("com").resolve("example");
        Files.createDirectories(pkg);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("line").append(i).append('\n');
        }
        Files.writeString(pkg.resolve("Dao.java"), sb.toString(), StandardCharsets.UTF_8);

        CallSiteEnricher e = new CallSiteEnricher(List.of(root));
        StackFrameSnapshot frame = new StackFrameSnapshot("com.example.Dao", "find", 5);
        SourceSnippet snippet = e.enrich(frame);

        assertThat(snippet).isNotNull();
        assertThat(snippet.callLine()).isEqualTo(5);
        assertThat(snippet.startLine()).isEqualTo(1);   // max(1, 5-4)
        assertThat(snippet.endLine()).isEqualTo(9);     // min(10, 5+4)
        assertThat(snippet.text()).contains("line5");
        assertThat(snippet.text()).contains("line1");
        assertThat(snippet.text()).contains("line9");
        assertThat(snippet.file()).isEqualTo(pkg.resolve("Dao.java"));
    }

    @Test
    void innerClassStrippedToTopLevel() throws IOException {
        Path pkg = root.resolve("com").resolve("example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Dao.java"), "line1\nline2\nline3\n",
                StandardCharsets.UTF_8);

        CallSiteEnricher e = new CallSiteEnricher(List.of(root));
        // Inner class: com.example.Dao$Builder — should resolve to Dao.java
        StackFrameSnapshot frame = new StackFrameSnapshot("com.example.Dao$Builder", "build", 2);
        SourceSnippet snippet = e.enrich(frame);

        assertThat(snippet).isNotNull();
        assertThat(snippet.callLine()).isEqualTo(2);
        assertThat(snippet.text()).contains("line2");
    }

    @Test
    void lineNumberOutOfRangeReturnsNull() throws IOException {
        Path pkg = root.resolve("com").resolve("example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Dao.java"), "only one line\n", StandardCharsets.UTF_8);

        CallSiteEnricher e = new CallSiteEnricher(List.of(root));
        StackFrameSnapshot frame = new StackFrameSnapshot("com.example.Dao", "find", 999);
        assertThat(e.enrich(frame)).isNull();
    }

    @Test
    void customContextWidth() throws IOException {
        Path pkg = root.resolve("com").resolve("example");
        Files.createDirectories(pkg);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("line").append(i).append('\n');
        }
        Files.writeString(pkg.resolve("Dao.java"), sb.toString(), StandardCharsets.UTF_8);

        CallSiteEnricher e = new CallSiteEnricher(List.of(root));
        StackFrameSnapshot frame = new StackFrameSnapshot("com.example.Dao", "find", 10);
        SourceSnippet snippet = e.enrich(frame, 2);

        assertThat(snippet.startLine()).isEqualTo(8);
        assertThat(snippet.endLine()).isEqualTo(12);
    }
}
