package io.github.vesas.jdbcprof.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Placeholder so the JMH harness is wired end-to-end from day one.
 * Spec §11 gates Phase 2 on the 5 μs hot-path benchmark; having the
 * harness runnable before there is anything to measure avoids
 * discovering plumbing issues at the worst possible moment.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CapturePathBenchmark {

    @Benchmark
    public int baseline() {
        return 0;
    }
}
