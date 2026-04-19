package io.github.vesas.jdbcprof.analysis;

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
import java.util.concurrent.Callable;

/**
 * CLI entry point for the offline analysis stage (spec §8).
 *
 * <p>Phase 2 starter: dumps a recording to human-readable text via
 * {@link TextDumper}. The HTML report and attribution passes land in
 * follow-up commits; the command name stays {@code analyze} so the
 * later HTML behaviour is a format toggle rather than a rename.
 */
@Command(
        name = "jdbc-profile analyze",
        description = "Read a jdbc-prof recording and dump it as text.",
        mixinStandardHelpOptions = true)
public final class AnalyzeCli implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<recording>",
            description = "Path to a .jdbclog file produced by the profiler.")
    private Path recording;

    @Option(names = {"-o", "--output"}, paramLabel = "<file>",
            description = "Write output to this file (default: stdout).")
    private Path output;

    @Override
    public Integer call() throws IOException {
        if (output == null) {
            TextDumper.dump(recording, System.out);
            return 0;
        }
        if (isHtml(output)) {
            HtmlReport.write(recording, output);
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
