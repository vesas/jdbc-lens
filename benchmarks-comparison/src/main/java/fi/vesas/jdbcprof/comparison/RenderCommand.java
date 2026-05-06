package fi.vesas.jdbcprof.comparison;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Renders {@code results.json} into an SVG bar chart for the README.
 *
 * <p>Kept separate from {@link RunCommand} so chart presentation can
 * be iterated on without re-running the (slow) measurement step.
 */
@Command(
        name = "render",
        description = "Render results.json into an SVG bar chart.",
        mixinStandardHelpOptions = true)
final class RenderCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            description = "Path to results.json produced by `comparison run`.",
            arity = "0..1",
            paramLabel = "RESULTS")
    Path input = Path.of("results.json");

    @Option(names = {"-o", "--out"},
            description = "SVG output path (default: ${DEFAULT-VALUE}).",
            defaultValue = "docs/img/overhead-vs-p6spy.svg")
    Path out;

    @Override
    public Integer call() throws Exception {
        List<Result> results = JsonIO.read(input.toAbsolutePath());
        if (results.isEmpty()) {
            System.err.println("no results in " + input);
            return 2;
        }
        SvgRenderer.render(results, out.toAbsolutePath());
        System.out.println("wrote " + out.toAbsolutePath());
        return 0;
    }
}
