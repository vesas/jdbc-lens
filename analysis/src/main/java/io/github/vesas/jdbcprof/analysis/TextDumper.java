package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.EventType;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;
import io.github.vesas.jdbcprof.storage.BinaryLogReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * First-cut Phase 2 consumer: reads a {@code .jdbclog} and emits a
 * human-readable text listing of the SQL intern table, the stack-trace
 * intern table, and the event stream. No attribution, no grouping, no
 * N+1 detection — just enough to eyeball what the capture layer
 * recorded.
 *
 * <p>Implemented as two passes over the file. The first pass builds
 * the intern tables and counts events; the second streams events out
 * line-by-line without accumulating them in memory. The spec's one-
 * pass-with-forward-resolvable-ids guarantee (deltas precede events —
 * see {@link io.github.vesas.jdbcprof.sink.Sink} and
 * {@link BinaryLogReader}) would let us emit inline in a single pass
 * too; two passes is simpler and costs an extra file read per dump.
 */
public final class TextDumper {

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
        reader.read(new BinaryLogReader.Handler() {
            @Override
            public void onEvents(List<Event> events) {
                for (Event e : events) {
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
