package fi.vesas.jdbclens.bench;

import fi.vesas.jdbclens.capture.CaptureContext;
import fi.vesas.jdbclens.capture.CapturingDataSource;
import fi.vesas.jdbclens.sink.Sink;
import fi.vesas.jdbclens.storage.BinaryLogWriter;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Sanity benchmark for the capture hot path: wrapped vs unwrapped
 * PreparedStatement execute + ResultSet.next. The delta between the
 * two numbers is the profiler's per-call overhead; spec §5.3 targets
 * mean &lt; 5 000 ns, p99 &lt; 20 000 ns.
 *
 * <p>One pair of benchmarks intentionally — rigorous validation happens
 * by running the profiler against a real application, not by sweeping
 * the JMH matrix.
 *
 * <p>The sink runs on a daemon thread writing to a temp file so the
 * measured thread never touches disk. The ring is sized generously so
 * it stays ahead of the sink's drain cadence — if events drop, the
 * drop-newest path changes the cost profile and skews the reading.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CapturePathBenchmark {

    private static final int RING_CAPACITY = 1 << 20;
    private static final int STACK_DEPTH = 30;
    private static final int SINK_FLUSH_MS = 20;
    private static final int SINK_DRAIN_BATCH = 65_536;
    private static final String PREPARED_SQL = "SELECT x FROM t WHERE id = ?";

    private Path logFile;
    private CaptureContext ctx;
    private BinaryLogWriter writer;
    private Sink sink;

    private Connection rawConn;
    private Connection wrappedConn;
    private PreparedStatement rawPs;
    private PreparedStatement wrappedPs;

    @Setup(Level.Trial)
    public void setup() throws IOException, SQLException {
        logFile = Files.createTempFile("jdbcprof-bench-", ".jdbclog");
        ctx = new CaptureContext(RING_CAPACITY, STACK_DEPTH);
        writer = new BinaryLogWriter(logFile);
        sink = new Sink(ctx, writer, SINK_FLUSH_MS, SINK_DRAIN_BATCH);
        sink.start();

        DataSource raw = NoopJdbc.dataSource();
        CaptureContext bound = ctx;
        DataSource wrapped = new CapturingDataSource(raw, () -> bound);
        rawConn = raw.getConnection();
        wrappedConn = wrapped.getConnection();
        rawPs = rawConn.prepareStatement(PREPARED_SQL);
        wrappedPs = wrappedConn.prepareStatement(PREPARED_SQL);
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException, SQLException {
        rawPs.close();
        wrappedPs.close();
        rawConn.close();
        wrappedConn.close();
        sink.stop();
        Files.deleteIfExists(logFile);
    }

    @Benchmark
    public void unwrappedPrepared(Blackhole bh) throws SQLException {
        ResultSet rs = rawPs.executeQuery();
        bh.consume(rs.next());
        rs.close();
    }

    @Benchmark
    public void wrappedPrepared(Blackhole bh) throws SQLException {
        ResultSet rs = wrappedPs.executeQuery();
        bh.consume(rs.next());
        rs.close();
    }
}
