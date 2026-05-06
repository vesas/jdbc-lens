package fi.vesas.jdbcprof.comparison;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

/**
 * Entry point for the head-to-head comparison harness.
 *
 * <p>Verbs:
 * <ul>
 *   <li>{@code run} / {@code render} — micro-comparison: per-op cost
 *       on tight synthetic workloads, in-process.</li>
 *   <li>{@code run-e2e} / {@code render-e2e} — end-to-end wall clock:
 *       launch the layered {@code sample-app} workload as a fresh
 *       subprocess under each mode, including JVM startup and
 *       profiler init in the measurement.</li>
 * </ul>
 *
 * <p>The run/render split is deliberate in both cases: the run step is
 * slow and machine-dependent; render is fast, deterministic, and
 * rerunnable after tweaking the chart presentation without
 * re-measuring.
 */
@Command(
        name = "comparison",
        description = "Measure jdbc-prof vs P6Spy overhead on a small JDBC workload.",
        mixinStandardHelpOptions = true,
        subcommands = {
                RunCommand.class,
                RenderCommand.class,
                E2eRunCommand.class,
                E2eRenderCommand.class,
                HelpCommand.class
        })
public final class Cli {

    public static void main(String[] args) {
        int rc = new CommandLine(new Cli()).execute(args);
        System.exit(rc);
    }
}
