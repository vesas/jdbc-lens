package fi.vesas.jdbcprof.storage;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reads a recording written by {@link BinaryLogWriter}. Phase 1 uses it
 * only for round-trip tests; the analysis layer (Phase 2) will extend
 * or replace it with a streaming variant.
 *
 * <p>The whole file is loaded into memory on {@link #read(Handler)} —
 * fine for gigabyte-scale recordings in a development environment, to
 * be revisited for Phase 2 streaming reads.
 */
public final class BinaryLogReader {

    /** Callback interface invoked once per record during {@link #read}. */
    public interface Handler {
        default void onSqlDelta(int firstId, List<String> sqls) {}
        default void onStackDelta(int firstId, List<StackFrameSnapshot[]> stacks) {}
        default void onOpDelta(int firstId, List<String> names) {}
        default void onParamValuesDelta(int firstId, List<ParameterValues> entries) {}
        default void onEvents(List<Event> events) {}
        /**
         * One-shot environment snapshot written at recording start.
         * Present in recordings produced by profiler builds that ship
         * {@link LogFormat#REC_RECORDING_META}; older recordings never
         * fire this callback.
         */
        default void onRecordingMeta(String userDir, String classpath, String command) {}
    }

    private final Path path;

    public BinaryLogReader(Path path) {
        this.path = path;
    }

    public void read(Handler handler) throws IOException {
        byte[] all = Files.readAllBytes(path);
        if (all.length < 4 + 4 + 1 + 4 + 4) {
            throw new IOException("recording too short: " + all.length + " bytes");
        }
        verifyChecksum(all);

        ByteBuffer bb = ByteBuffer.wrap(all, 0, all.length - 4).order(ByteOrder.BIG_ENDIAN);

        int magic = bb.getInt();
        if (magic != LogFormat.MAGIC) {
            throw new IOException(String.format("bad magic: 0x%08X", magic));
        }
        int version = bb.getInt();
        if (version != LogFormat.VERSION) {
            throw new IOException("unsupported version: " + version);
        }

        while (bb.hasRemaining()) {
            byte type = bb.get();
            int length = bb.getInt();
            int recordEnd = bb.position() + length;
            switch (type) {
                case LogFormat.REC_SQL_DELTA -> readSqlDelta(bb, handler);
                case LogFormat.REC_STACK_DELTA -> readStackDelta(bb, handler);
                case LogFormat.REC_OP_DELTA -> readOpDelta(bb, handler);
                case LogFormat.REC_PARAM_VALUES_DELTA -> readParamValuesDelta(bb, handler);
                case LogFormat.REC_RECORDING_META -> readRecordingMeta(bb, handler);
                case LogFormat.REC_EVENTS -> readEvents(bb, handler);
                case LogFormat.REC_END -> {
                    if (bb.position() != recordEnd || bb.hasRemaining()) {
                        throw new IOException("data past END marker at offset " + bb.position());
                    }
                    return;
                }
                default -> bb.position(recordEnd); // unknown — skip
            }
            if (bb.position() != recordEnd) {
                throw new IOException("record of type " + type
                        + " mis-consumed: expected end at " + recordEnd
                        + " but position is " + bb.position());
            }
        }
        throw new IOException("recording ended without END marker");
    }

    private static void verifyChecksum(byte[] all) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(all, 0, all.length - 4);
        int stored = ByteBuffer.wrap(all, all.length - 4, 4)
                .order(ByteOrder.BIG_ENDIAN).getInt();
        int computed = (int) crc.getValue();
        if (stored != computed) {
            throw new IOException(String.format(
                    "checksum mismatch: stored=0x%08X computed=0x%08X", stored, computed));
        }
    }

    private static void readRecordingMeta(ByteBuffer bb, Handler handler) {
        String userDir = readUtf(bb);
        String classpath = readUtf(bb);
        String command = readUtf(bb);
        handler.onRecordingMeta(userDir, classpath, command);
    }

    private static String readUtf(ByteBuffer bb) {
        int len = bb.getInt();
        byte[] bytes = new byte[len];
        bb.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void readSqlDelta(ByteBuffer bb, Handler handler) {
        int firstId = bb.getInt();
        int count = bb.getInt();
        List<String> sqls = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = bb.getInt();
            byte[] bytes = new byte[len];
            bb.get(bytes);
            sqls.add(new String(bytes, StandardCharsets.UTF_8));
        }
        handler.onSqlDelta(firstId, sqls);
    }

    private static void readParamValuesDelta(ByteBuffer bb, Handler handler) {
        int firstId = bb.getInt();
        int count = bb.getInt();
        List<ParameterValues> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int slotCount = bb.getInt();
            List<String> slots = new ArrayList<>(slotCount);
            for (int j = 0; j < slotCount; j++) {
                int len = bb.getInt();
                byte[] bytes = new byte[len];
                bb.get(bytes);
                slots.add(new String(bytes, StandardCharsets.UTF_8));
            }
            entries.add(new ParameterValues(slots));
        }
        handler.onParamValuesDelta(firstId, entries);
    }

    private static void readOpDelta(ByteBuffer bb, Handler handler) {
        int firstId = bb.getInt();
        int count = bb.getInt();
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = bb.getInt();
            byte[] bytes = new byte[len];
            bb.get(bytes);
            names.add(new String(bytes, StandardCharsets.UTF_8));
        }
        handler.onOpDelta(firstId, names);
    }

    private static void readStackDelta(ByteBuffer bb, Handler handler) {
        int firstId = bb.getInt();
        int count = bb.getInt();
        List<StackFrameSnapshot[]> stacks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int frameCount = bb.getShort() & 0xFFFF;
            StackFrameSnapshot[] frames = new StackFrameSnapshot[frameCount];
            for (int j = 0; j < frameCount; j++) {
                int clsLen = bb.getShort() & 0xFFFF;
                byte[] clsBytes = new byte[clsLen];
                bb.get(clsBytes);
                int mthLen = bb.getShort() & 0xFFFF;
                byte[] mthBytes = new byte[mthLen];
                bb.get(mthBytes);
                int line = bb.getInt();
                frames[j] = new StackFrameSnapshot(
                        new String(clsBytes, StandardCharsets.UTF_8),
                        new String(mthBytes, StandardCharsets.UTF_8),
                        line);
            }
            stacks.add(frames);
        }
        handler.onStackDelta(firstId, stacks);
    }

    private static void readEvents(ByteBuffer bb, Handler handler) {
        int count = bb.getInt();
        List<Event> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Event e = new Event();
            e.timestampNanos = bb.getLong();
            e.threadId = bb.getInt();
            e.operationId = bb.getLong();
            e.operationInvocationId = bb.getLong();
            e.eventType = bb.get();
            e.sqlId = bb.getInt();
            e.stackTraceId = bb.getInt();
            e.durationNanos = bb.getLong();
            e.rowsAffected = bb.getInt();
            e.batchSize = bb.getInt();
            e.parameterFingerprint = bb.getLong();
            e.parameterValuesId = bb.getInt();
            bb.position(bb.position() + 3); // skip padding
            events.add(e);
        }
        handler.onEvents(events);
    }
}
