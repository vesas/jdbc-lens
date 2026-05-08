package fi.vesas.jdbclens.analysis.source;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Maps a {@link StackFrameSnapshot} to a {@link SourceSnippet} by
 * searching a set of source roots for the corresponding {@code .java}
 * file and extracting an 8-line window around the call-site line.
 *
 * <p>The algorithm:
 * <ol>
 *   <li>Strip any {@code $Inner} suffix (inner classes share a file).</li>
 *   <li>Replace {@code .} separators with {@code /} and append {@code .java}.</li>
 *   <li>Try each source root; return the first existing file.</li>
 *   <li>Return {@code null} on miss.</li>
 * </ol>
 */
public final class CallSiteEnricher {

    private static final int DEFAULT_CONTEXT = 4;

    private final List<Path> sourceRoots;

    public CallSiteEnricher(List<Path> sourceRoots) {
        this.sourceRoots = List.copyOf(sourceRoots);
    }

    /** 8-line window (4 above, 4 below the call line). */
    public SourceSnippet enrich(StackFrameSnapshot frame) {
        return enrich(frame, DEFAULT_CONTEXT);
    }

    /**
     * @param ctx lines of context above and below the call line (so the
     *            window is {@code 2*ctx + 1} lines)
     */
    public SourceSnippet enrich(StackFrameSnapshot frame, int ctx) {
        if (frame == null) return null;
        Path file = locate(frame.className());
        if (file == null) return null;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        int callLine = frame.lineNumber();
        if (callLine <= 0 || callLine > lines.size()) return null;

        int startLine = Math.max(1, callLine - ctx);
        int endLine   = Math.min(lines.size(), callLine + ctx);

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            sb.append(lines.get(i - 1)).append('\n');
        }
        return new SourceSnippet(file, startLine, endLine, callLine, sb.toString());
    }

    private Path locate(String className) {
        // Strip inner class suffix: com.example.Dao$Builder -> com.example.Dao
        int dollar = className.indexOf('$');
        String topLevel = dollar >= 0 ? className.substring(0, dollar) : className;
        String relativePath = topLevel.replace('.', '/') + ".java";
        for (Path root : sourceRoots) {
            Path candidate = root.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
