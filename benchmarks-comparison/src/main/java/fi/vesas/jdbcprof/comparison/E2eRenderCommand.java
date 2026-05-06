package fi.vesas.jdbclens.comparison;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Renders {@code results-e2e.json} into an SVG bar chart suitable for
 * the README. Single panel, one bar per mode, value = mean wall-clock
 * milliseconds, with min-max whiskers and overhead-vs-baseline labels.
 *
 * <p>Kept separate from {@link E2eRunCommand} so the chart can be
 * iterated on without re-launching the (slow) subprocess matrix.
 */
@Command(
        name = "render-e2e",
        description = "Render results-e2e.json into an SVG bar chart.",
        mixinStandardHelpOptions = true)
final class E2eRenderCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            description = "Path to results-e2e.json produced by `comparison run-e2e`.",
            arity = "0..1",
            paramLabel = "RESULTS")
    Path input = Path.of("results-e2e.json");

    @Option(names = {"-o", "--out"},
            description = "SVG output path (default: ${DEFAULT-VALUE}).",
            defaultValue = "docs/img/end-to-end.svg")
    Path out;

    @Override
    public Integer call() throws Exception {
        List<E2eResult> results = E2eJsonIO.read(input.toAbsolutePath());
        if (results.isEmpty()) {
            System.err.println("no results in " + input);
            return 2;
        }
        SvgRenderer.renderE2e(results, out.toAbsolutePath());
        System.out.println("wrote " + out.toAbsolutePath());
        return 0;
    }
}
