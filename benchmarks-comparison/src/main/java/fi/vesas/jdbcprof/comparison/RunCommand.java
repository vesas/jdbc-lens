package fi.vesas.jdbcprof.comparison;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Executes the (scenario × mode) matrix and writes {@code results.json}.
 *
 * <p>The measurement pattern is:
 * <ol>
 *   <li>Build a fresh DataSource per (scenario, mode) — schema and
 *       seed run through the wrapper so the wrapper's startup cost is
 *       not folded into iteration timings.</li>
 *   <li>Run {@code --warmup} iterate-passes and discard the timings
 *       (lets the JIT specialise on the wrapped call path).</li>
 *   <li>Run {@code --repeats} iterate-passes, record per-pass wall
 *       time, derive mean throughput and percentiles.</li>
 * </ol>
 *
 * <p>Each pass executes {@code --iterations} JDBC operations. Defaults
 * are tuned so a baseline pass on H2 takes roughly half a second —
 * long enough to drown out timer jitter, short enough that the full
 * 9-cell run finishes in a couple of minutes.
 */
@Command(
        name = "run",
        description = "Run the scenario × mode matrix and write results.json.",
        mixinStandardHelpOptions = true)
final class RunCommand implements Callable<Integer> {

    @Option(names = {"-o", "--out"},
            description = "Path for the results JSON (default: ${DEFAULT-VALUE}).",
            defaultValue = "results.json")
    Path out;

    @Option(names = "--iterations",
            description = "JDBC operations per measurement pass (default: ${DEFAULT-VALUE}).",
            defaultValue = "5000")
    int iterations;

    @Option(names = "--warmup",
            description = "Warmup passes before measurement (default: ${DEFAULT-VALUE}).",
            defaultValue = "3")
    int warmup;

    @Option(names = "--repeats",
            description = "Measurement passes per (scenario, mode) (default: ${DEFAULT-VALUE}).",
            defaultValue = "5")
    int repeats;

    @Option(names = "--scenarios",
            description = "Comma-separated subset of scenario names to run "
                    + "(default: all).",
            split = ",")
    List<String> scenarioFilter;

    @Override
    public Integer call() throws Exception {
        List<Workload> all = List.of(
                new PointReadWorkload(),
                new InsertWorkload(),
                new MixedWorkload());
        List<Workload> selected = filterScenarios(all);
        Mode[] modes = Mode.values();

        List<Result> results = new ArrayList<>(selected.size() * modes.length);
        long t0 = System.currentTimeMillis();

        for (Workload w : selected) {
            for (Mode mode : modes) {
                System.out.println("[run] " + w.name() + " / " + mode);
                results.add(measure(w, mode));
            }
        }

        JsonIO.write(out.toAbsolutePath(), results);
        long elapsedMs = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("wrote " + out.toAbsolutePath()
                + " (" + results.size() + " rows, "
                + (elapsedMs / 1000) + " s total)");
        System.out.println("render with: ./gradlew :benchmarks-comparison:run "
                + "--args=\"render " + out + "\"");
        return 0;
    }

    private List<Workload> filterScenarios(List<Workload> all) {
        if (scenarioFilter == null || scenarioFilter.isEmpty()) {
            return all;
        }
        List<Workload> kept = new ArrayList<>();
        for (Workload w : all) {
            if (scenarioFilter.contains(w.name())) {
                kept.add(w);
            }
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException(
                    "no scenarios matched filter " + scenarioFilter);
        }
        return kept;
    }

    private Result measure(Workload w, Mode mode) throws Exception {
        String runId = w.name().replaceAll("[^a-z0-9]", "_") + "_" + mode.name().toLowerCase();
        DataSourceFactory.Handle handle = DataSourceFactory.build(mode, runId);
        try {
            DataSource ds = handle.dataSource();
            w.setup(ds);

            for (int i = 0; i < warmup; i++) {
                w.iterate(ds, iterations);
            }

            double[] passNanos = new double[repeats];
            for (int i = 0; i < repeats; i++) {
                long t = System.nanoTime();
                w.iterate(ds, iterations);
                passNanos[i] = System.nanoTime() - t;
            }

            return summarise(w, mode, iterations, passNanos);
        } finally {
            DataSourceFactory.teardown(mode);
        }
    }

    private Result summarise(Workload w, Mode mode, int iterations, double[] passNanos) {
        int ops = iterations * w.opsPerIteration();
        double[] perOpNanos = new double[passNanos.length];
        double sumPerOp = 0;
        double sumPassSec = 0;
        for (int i = 0; i < passNanos.length; i++) {
            perOpNanos[i] = passNanos[i] / ops;
            sumPerOp += perOpNanos[i];
            sumPassSec += passNanos[i] / 1_000_000_000.0;
        }
        double meanPerOp = sumPerOp / passNanos.length;
        double meanThroughput = (ops * passNanos.length) / sumPassSec;

        // Percentiles across pass-level per-op means. With repeats=5
        // these are coarse — they exist to flag a single very slow
        // pass, not to characterise the latency distribution. Real
        // percentile work belongs in JMH if it's ever needed.
        double[] sorted = Arrays.copyOf(perOpNanos, perOpNanos.length);
        Arrays.sort(sorted);
        return new Result(
                w.name(), mode, iterations, repeats,
                meanPerOp,
                pct(sorted, 50),
                pct(sorted, 95),
                pct(sorted, 99),
                meanThroughput);
    }

    private static double pct(double[] sorted, int p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int idx = (int) Math.ceil((p / 100.0) * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx];
    }
}
