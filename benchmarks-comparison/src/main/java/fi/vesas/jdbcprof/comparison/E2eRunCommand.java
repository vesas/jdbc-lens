package fi.vesas.jdbcprof.comparison;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * End-to-end wall-clock benchmark. For each mode (none / jdbc-prof /
 * P6Spy) launches the {@code sample-app} workload as a fresh
 * subprocess multiple times, discards the warmup runs, and records
 * the timed runs. Each subprocess is a cold JVM — that's the point of
 * "end-to-end" here, since enabling a profiler in CI pays the JVM
 * startup, JIT cold-start, and recording-init costs every time.
 *
 * <p>The wall clock spans {@link ProcessBuilder#start()} to
 * {@link Process#waitFor()} returning. JVM launch, classpath scan,
 * profiler {@code start()}, the layered DAO/service workload, and the
 * profiler's shutdown-hook flush are all inside the measurement.
 *
 * <p>Output is a JSON document at {@code --out} (default
 * {@code results-e2e.json}) consumable by {@link E2eRenderCommand}.
 */
@Command(
        name = "run-e2e",
        description = "End-to-end wall-clock comparison: launch sample-app under each mode "
                + "as a subprocess, discard warmup runs, record timed runs.",
        mixinStandardHelpOptions = true)
final class E2eRunCommand implements Callable<Integer> {

    @Option(names = {"-o", "--out"},
            description = "Path for the e2e results JSON (default: ${DEFAULT-VALUE}).",
            defaultValue = "results-e2e.json")
    Path out;

    @Option(names = "--warmup",
            description = "Warmup runs per mode, results discarded (default: ${DEFAULT-VALUE}).",
            defaultValue = "3")
    int warmup;

    @Option(names = "--repeats",
            description = "Timed runs per mode (default: ${DEFAULT-VALUE}).",
            defaultValue = "7")
    int repeats;

    @Option(names = "--scratch-dir",
            description = "Working directory for each subprocess. Recordings and P6Spy "
                    + "logs land here. (default: ${DEFAULT-VALUE})",
            defaultValue = "build/e2e")
    Path scratchDir;

    @Override
    public Integer call() throws Exception {
        if (warmup < 0 || repeats < 1) {
            throw new IllegalArgumentException(
                    "warmup must be >=0 and repeats must be >=1");
        }
        Files.createDirectories(scratchDir.toAbsolutePath());
        // spy.properties points at "build/p6spy.log" — a relative path
        // intended for the in-process bench whose CWD is the project
        // root. The subprocess CWD is scratchDir, so for P6Spy mode to
        // succeed the same relative path has to resolve here too.
        // Pre-creating the directory is cheaper than overriding
        // -Dspy.properties at runtime and matches the existing
        // behavior of the in-process bench.
        Files.createDirectories(scratchDir.toAbsolutePath().resolve("build"));

        String javaBin = javaExecutable();
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException(
                    "java.class.path is empty — run via `gradle :benchmarks-comparison:run`");
        }

        List<E2eResult> results = new ArrayList<>(Mode.values().length);
        long t0 = System.currentTimeMillis();

        for (Mode mode : Mode.values()) {
            System.out.println("[e2e] " + mode + " - "
                    + warmup + " warmup + " + repeats + " timed");
            results.add(measure(mode, javaBin, classpath));
        }

        E2eJsonIO.write(out.toAbsolutePath(), results);
        long elapsedMs = System.currentTimeMillis() - t0;

        System.out.println();
        printSummary(results);
        System.out.println();
        System.out.println("wrote " + out.toAbsolutePath()
                + " (" + (elapsedMs / 1000) + " s total)");
        System.out.println("render with: ./gradlew :benchmarks-comparison:run "
                + "--args=\"render-e2e " + out + "\"");
        return 0;
    }

    private E2eResult measure(Mode mode, String javaBin, String classpath) throws Exception {
        for (int i = 0; i < warmup; i++) {
            long ns = runOnce(mode, javaBin, classpath);
            System.out.printf(Locale.ROOT, "  warmup[%d] %s%n", i, formatMs(ns));
        }
        long[] runs = new long[repeats];
        for (int i = 0; i < repeats; i++) {
            runs[i] = runOnce(mode, javaBin, classpath);
            System.out.printf(Locale.ROOT, "   timed[%d] %s%n", i, formatMs(runs[i]));
        }
        return summarise(mode, runs);
    }

    private long runOnce(Mode mode, String javaBin, String classpath)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                "-Djdbcprof.mode=" + mode.name().toLowerCase(Locale.ROOT),
                "fi.vesas.jdbcprof.sample.Main");
        pb.directory(scratchDir.toAbsolutePath().toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // Surface stderr so a crashing run doesn't fail silently — the
        // wall-clock would still be recorded but the run would be
        // meaningless.
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        long t0 = System.nanoTime();
        Process p = pb.start();
        int rc = p.waitFor();
        long t1 = System.nanoTime();
        if (rc != 0) {
            throw new IllegalStateException(
                    "sample-app under mode=" + mode + " exited with rc=" + rc);
        }
        return t1 - t0;
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            throw new IllegalStateException("java.home not set");
        }
        boolean isWindows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        return Path.of(javaHome, "bin", isWindows ? "java.exe" : "java")
                .toAbsolutePath().toString();
    }

    private static E2eResult summarise(Mode mode, long[] runs) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        double sum = 0;
        for (long r : runs) {
            if (r < min) min = r;
            if (r > max) max = r;
            sum += r;
        }
        double mean = sum / runs.length;
        double sqsum = 0;
        for (long r : runs) {
            double d = r - mean;
            sqsum += d * d;
        }
        double stddev = Math.sqrt(sqsum / runs.length);
        return new E2eResult(mode, runs, mean, min, max, stddev);
    }

    private static void printSummary(List<E2eResult> results) {
        E2eResult baseline = null;
        for (E2eResult r : results) {
            if (r.mode() == Mode.NONE) {
                baseline = r;
                break;
            }
        }
        System.out.printf(Locale.ROOT, "  %-10s  %-10s  %-10s  %-10s  %s%n",
                "mode", "mean", "min", "max", "vs baseline");
        for (E2eResult r : results) {
            String overhead = (baseline == null || r == baseline)
                    ? "-"
                    : String.format(Locale.ROOT, "+%.0f%%",
                            (r.meanNanos() - baseline.meanNanos())
                                    / baseline.meanNanos() * 100.0);
            System.out.printf(Locale.ROOT, "  %-10s  %-10s  %-10s  %-10s  %s%n",
                    r.mode(),
                    formatMs(r.meanNanos()),
                    formatMs(r.minNanos()),
                    formatMs(r.maxNanos()),
                    overhead);
        }
    }

    private static String formatMs(double nanos) {
        return String.format(Locale.ROOT, "%.0f ms", nanos / 1_000_000.0);
    }
}
