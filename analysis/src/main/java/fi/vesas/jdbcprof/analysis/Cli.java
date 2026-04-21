package fi.vesas.jdbcprof.analysis;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

/**
 * Root of the {@code jdbc-profile} CLI. Today there is a single
 * subcommand, {@link AnalyzeCli analyze}; the root exists so the
 * canonical invocation stays {@code jdbc-profile <verb>} and so
 * future verbs (e.g. {@code source-scan}, {@code diff}) can be
 * added without churning the entry point.
 */
@Command(
        name = "jdbc-profile",
        description = "JDBC call-site profiler CLI.",
        mixinStandardHelpOptions = true,
        subcommands = {AnalyzeCli.class, HelpCommand.class})
public final class Cli {

    public static void main(String[] args) {
        int rc = new CommandLine(new Cli()).execute(args);
        System.exit(rc);
    }
}
