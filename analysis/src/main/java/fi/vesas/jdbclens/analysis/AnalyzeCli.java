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
 * (spec §8). Reads a recording and produces either a text dump
 * (stdout or a {@code .txt} file) or an HTML report (when the
 * output path ends with {@code .html}/{@code .htm}).
 *
 * <p>Mounted under the root {@link Cli} so the canonical invocation
 * is {@code jdbc-profile analyze <recording>} as documented in the
 * project README and CLAUDE.md. A thin {@link #main} is retained so
 * the subcommand can still be run standalone when convenient.
 */
@Command(
        name = "analyze",
        description = "Read a jdbc-prof recording and render it "
                + "(text dump by default; HTML when output ends in .html).",
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

    @Override
    public Integer call() throws IOException {
        if (output == null) {
            TextDumper.dump(recording, System.out);
            return 0;
        }
        if (isHtml(output)) {
            HtmlReport.write(recording, output, sourceRoots, noSourceScan);
            return 0;
        }
        try (OutputStream os = Files.newOutputStream(output);
             PrintStream ps = new PrintStream(os, false, StandardCharsets.UTF_8)) {
            TextDumper.dump(recording, ps);
        }
        return 0;
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
