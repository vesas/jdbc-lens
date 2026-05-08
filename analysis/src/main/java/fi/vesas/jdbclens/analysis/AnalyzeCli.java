package fi.vesas.jdbclens.analysis;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The {@code analyze} subcommand of the {@code jdbc-profile} CLI
 * (spec §8). Reads a recording and produces a text dump, HTML report,
 * or machine-readable JSON ({@code --format json}).
 *
 * <p>Mounted under the root {@link Cli} so the canonical invocation
 * is {@code jdbc-profile analyze <recording>} as documented in the
 * project README and CLAUDE.md. A thin {@link #main} is retained so
 * the subcommand can still be run standalone when convenient.
 */
@Command(
        name = "analyze",
        description = "Read a jdbc-prof recording and render it "
                + "(text dump by default; --format json for LLM-agent use; "
                + "HTML when output ends in .html).",
        mixinStandardHelpOptions = true)
public final class AnalyzeCli implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<recording>",
            description = "Path to a .jdbclog file produced by the profiler.")
    private Path recording;

    @Option(names = {"-o", "--output"}, paramLabel = "<file>",
            description = "Write output to this file (default: stdout).")
    private Path output;

    @Option(names = "--source-root", paramLabel = "<path>",
            description = "Source root to scan for SQL literals. Repeatable. "
                    + "When omitted, the analyzer auto-infers roots from the "
                    + "classpath captured in the recording.")
    private List<Path> sourceRoots = new ArrayList<>();

    @Option(names = "--no-source-scan",
            description = "Disable static source scanning entirely. The Cache "
                    + "Candidates section shows runtime data only.")
    private boolean noSourceScan;

    @Option(names = "--format", paramLabel = "<format>",
            description = "Output format: text (default), html, json.",
            defaultValue = "")
    private String format;

    @Override
    public Integer call() throws IOException {
        boolean wantJson = "json".equalsIgnoreCase(format);
        boolean wantHtml = "html".equalsIgnoreCase(format)
                || (output != null && isHtml(output));

        if (wantJson) {
            PrintStream dest = outputStream(output);
            JsonReport.write(recording, dest, sourceRoots, noSourceScan);
            if (output != null) dest.close();
            return 0;
        }
        if (wantHtml) {
            if (output == null) {
                System.err.println("--format html requires -o <file>");
                return 1;
            }
            HtmlReport.write(recording, output, sourceRoots, noSourceScan);
            return 0;
        }
        // default: text
        PrintStream dest = outputStream(output);
        TextDumper.dump(recording, dest);
        if (output != null) dest.close();
        return 0;
    }

    private static PrintStream outputStream(Path path) throws IOException {
        if (path == null) return System.out;
        return new PrintStream(Files.newOutputStream(path), false, StandardCharsets.UTF_8);
    }

    private static boolean isHtml(Path p) {
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    public static void main(String[] args) {
        int rc = new CommandLine(new AnalyzeCli()).execute(args);
        System.exit(rc);
    }
}
