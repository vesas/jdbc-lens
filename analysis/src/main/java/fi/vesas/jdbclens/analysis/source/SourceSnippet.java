package fi.vesas.jdbclens.analysis.source;

import java.nio.file.Path;

/**
 * A window of source lines surrounding a call-site. Used by the JSON
 * report so an LLM agent can read the relevant code without resolving
 * class names to file paths itself.
 *
 * @param file      absolute path to the {@code .java} file
 * @param startLine 1-based line number of the first line in {@code text}
 * @param endLine   1-based line number of the last line in {@code text}
 * @param callLine  1-based line number of the actual call-site within the window
 * @param text      raw source text for the window, including newlines
 */
public record SourceSnippet(Path file, int startLine, int endLine, int callLine, String text) {
}
