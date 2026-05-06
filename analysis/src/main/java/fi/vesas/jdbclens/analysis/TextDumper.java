package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;
import fi.vesas.jdbclens.storage.BinaryLogReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 2 text consumer: reads a {@code .jdbclog} and emits a human-
 * readable listing of the SQL intern table, the stack-trace intern
 * table, the event stream, and an aggregated summary (spec §8.2) of
 * the top (call-site, template) pairs, call-sites, and templates by
 * total duration. No flamegraph, no N+1 detection yet.
 *
 * <p>Implemented as two passes over the file. The first pass builds
 * the intern tables and counts events; the second streams events out
 * line-by-line (without holding them in memory) and feeds the
 * aggregator. The spec's one-pass-with-forward-resolvable-ids
 * guarantee (deltas precede events — see
 * {@link fi.vesas.jdbcprof.sink.Sink} and
 * {@link BinaryLogReader}) would let us emit inline in a single pass
 * too; two passes is simpler and costs an extra file read per dump.
 */
public final class TextDumper {

    private static final int SUMMARY_TOP_N = 10;

    private TextDumper() {
    }

    public static void dump(Path input, PrintStream out) throws IOException {
        BinaryLogReader reader = new BinaryLogReader(input);

        Map<Integer, String> sqls = new HashMap<>();
        Map<Integer, StackFrameSnapshot[]> stacks = new HashMap<>();
        long[] firstTs = { Long.MAX_VALUE };
        int[] eventCount = { 0 };

        reader.read(new BinaryLogReader.Handler() {
            @Override
            public void onSqlDelta(int firstId, List<String> entries) {
                for (int i = 0; i < entries.size(); i++) {
                    sqls.put(firstId + i, entries.get(i));
                }
            }

            @Override
            public void onStackDelta(int firstId, List<StackFrameSnapshot[]> entries) {
                for (int i = 0; i < entries.size(); i++) {
                    stacks.put(firstId + i, entries.get(i));
                }
            }

            @Override
            public void onEvents(List<Event> events) {
                for (Event e : events) {
                    eventCount[0]++;
                    if (e.timestampNanos < firstTs[0]) {
                        firstTs[0] = e.timestampNanos;
                    }
                }
            }
        });

        out.println("# jdbc-prof recording: " + input);
        out.println("# SQL templates: " + sqls.size());
        for (int id = 0; id < sqls.size(); id++) {
            out.println("  [" + id + "] " + sqls.get(id));
        }
        out.println("# Stack traces: " + stacks.size());
        for (int id = 0; id < stacks.size(); id++) {
            StackFrameSnapshot[] frames = stacks.get(id);
            StackFrameSnapshot site = Attribution.callSite(frames);
            out.println("  [" + id + "] call-site=" + formatFrame(site));
            out.println("      " + formatStack(frames));
        }
        out.println("# Events: " + eventCount[0]);

        long t0 = eventCount[0] == 0 ? 0L : firstTs[0];
        Aggregator agg = new Aggregator();
        reader.read(new BinaryLogReader.Handler() {
            @Override
            public void onEvents(List<Event> events) {
                for (Event e : events) {
                    agg.add(e);
                    String kind = EventType.fromCode(e.eventType).name();
                    out.printf(Locale.ROOT,
                            "  +%-12d T%-5d %-14s sql=%-4d stack=%-4d dur=%dns%n",
                            e.timestampNanos - t0,
                            e.threadId,
                            kind,
                            e.sqlId,
                            e.stackTraceId,
                            e.durationNanos);
                }
            }
        });

        emitSummary(out, agg, sqls, stacks);
    }

    private static void emitSummary(PrintStream out,
                                    Aggregator agg,
                                    Map<Integer, String> sqls,
                                    Map<Integer, StackFrameSnapshot[]> stacks) {
        out.println("# Summary — top " + SUMMARY_TOP_N + " by total duration");
        out.println("# (call-site, template):");
        for (var entry : agg.topPairs(SUMMARY_TOP_N)) {
            Aggregator.PairKey k = entry.getKey();
            Aggregator.Stats s = entry.getValue();
            out.printf(Locale.ROOT, "  %s %5dx  %s  |  %s%n",
                    formatDuration(s.totalDurationNanos()),
                    s.count(),
                    siteLabel(k.stackTraceId(), stacks),
                    sqlLabel(k.sqlId(), sqls));
        }
        out.println("# call-site:");
        for (var entry : agg.topCallSites(SUMMARY_TOP_N)) {
            Aggregator.Stats s = entry.getValue();
            out.printf(Locale.ROOT, "  %s %5dx  %s%n",
                    formatDuration(s.totalDurationNanos()),
                    s.count(),
                    siteLabel(entry.getKey(), stacks));
        }
        out.println("# template:");
        for (var entry : agg.topTemplates(SUMMARY_TOP_N)) {
            Aggregator.Stats s = entry.getValue();
            out.printf(Locale.ROOT, "  %s %5dx  %s%n",
                    formatDuration(s.totalDurationNanos()),
                    s.count(),
                    sqlLabel(entry.getKey(), sqls));
        }
    }

    private static String siteLabel(int stackTraceId,
                                    Map<Integer, StackFrameSnapshot[]> stacks) {
        StackFrameSnapshot[] frames = stacks.get(stackTraceId);
        if (frames == null) {
            return "stack[" + stackTraceId + "]";
        }
        return formatFrame(Attribution.callSite(frames));
    }

    private static String sqlLabel(int sqlId, Map<Integer, String> sqls) {
        if (sqlId < 0) {
            return "(no SQL)";
        }
        String sql = sqls.get(sqlId);
        return sql == null ? "sql[" + sqlId + "]" : sql;
    }

    private static String formatDuration(long nanos) {
        // Fixed 12-char column: right-aligned number + unit.
        if (nanos >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%8.2f s ", nanos / 1_000_000_000.0);
        }
        if (nanos >= 1_000_000L) {
            return String.format(Locale.ROOT, "%8.2f ms", nanos / 1_000_000.0);
        }
        if (nanos >= 1_000L) {
            return String.format(Locale.ROOT, "%8.2f us", nanos / 1_000.0);
        }
        return String.format(Locale.ROOT, "%8d ns", nanos);
    }

    private static String formatStack(StackFrameSnapshot[] frames) {
        if (frames == null || frames.length == 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(frames.length * 48);
        for (int i = 0; i < frames.length; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(formatFrame(frames[i]));
        }
        return sb.toString();
    }

    private static String formatFrame(StackFrameSnapshot f) {
        if (f == null) {
            return "(none)";
        }
        return f.className() + '.' + f.methodName() + ':' + f.lineNumber();
    }
}
