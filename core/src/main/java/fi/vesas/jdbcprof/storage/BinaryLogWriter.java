package fi.vesas.jdbcprof.storage;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Streams the binary recording format (spec §6, §7) to a file. Runs
 * on the sink thread; not safe for concurrent use.
 *
 * <p>Layout: magic + version, then a sequence of records ({@link
 * LogFormat#REC_SQL_DELTA}, {@link LogFormat#REC_STACK_DELTA},
 * {@link LogFormat#REC_EVENTS}), a terminating {@link LogFormat#REC_END},
 * and a CRC32 over everything above. Each record is a 1-byte type, a
 * 4-byte payload length, and the payload.
 *
 * <p>A 64 KB direct {@link ByteBuffer} batches writes before they hit
 * the {@link FileChannel} (spec §6). The CRC is fed incrementally each
 * time the buffer flushes so no second pass over the data is needed at
 * close.
 */
public final class BinaryLogWriter implements Closeable {

    private static final int BUFFER_BYTES = 64 * 1024;

    private final FileChannel channel;
    private final ByteBuffer buf;
    private final CRC32 crc = new CRC32();

    private boolean closed;

    public BinaryLogWriter(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        this.buf = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.BIG_ENDIAN);
        writeHeader();
    }

    private void writeHeader() throws IOException {
        put4(LogFormat.MAGIC);
        put4(LogFormat.VERSION);
    }

    /**
     * Emit the one-shot environment snapshot that powers source-root
     * auto-discovery at analyze time. Intended to be called exactly
     * once, immediately after construction, before any deltas or
     * events. Null inputs are normalised to empty strings so the
     * reader contract never sees a null.
     */
    public void writeRecordingMeta(String userDir, String classpath, String command)
            throws IOException {
        byte[] u = nullSafe(userDir);
        byte[] c = nullSafe(classpath);
        byte[] cmd = nullSafe(command);
        int payloadLen = 4 + u.length + 4 + c.length + 4 + cmd.length;
        put1(LogFormat.REC_RECORDING_META);
        put4(payloadLen);
        put4(u.length);
        putBytes(u);
        put4(c.length);
        putBytes(c);
        put4(cmd.length);
        putBytes(cmd);
    }

    private static byte[] nullSafe(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Emit a SQL intern-table delta. {@code firstId} is the id of the
     * first entry in {@code sqls}; subsequent entries are {@code
     * firstId+1}, {@code firstId+2}, … (intern ids are dense —
     * {@link fi.vesas.jdbcprof.capture.SqlInternTable}).
     */
    public void writeSqlDelta(int firstId, List<String> sqls) throws IOException {
        if (sqls.isEmpty()) {
            return;
        }
        byte[][] encoded = new byte[sqls.size()][];
        int payloadLen = 4 + 4; // firstId + count
        for (int i = 0; i < sqls.size(); i++) {
            encoded[i] = sqls.get(i).getBytes(StandardCharsets.UTF_8);
            payloadLen += 4 + encoded[i].length;
        }
        put1(LogFormat.REC_SQL_DELTA);
        put4(payloadLen);
        put4(firstId);
        put4(sqls.size());
        for (byte[] bytes : encoded) {
            put4(bytes.length);
            putBytes(bytes);
        }
    }

    /**
     * Emit an operation intern-table delta. Same wire shape as
     * {@link #writeSqlDelta} — readers can share the same parser.
     */
    public void writeOpDelta(int firstId, List<String> names) throws IOException {
        if (names.isEmpty()) {
            return;
        }
        byte[][] encoded = new byte[names.size()][];
        int payloadLen = 4 + 4; // firstId + count
        for (int i = 0; i < names.size(); i++) {
            encoded[i] = names.get(i).getBytes(StandardCharsets.UTF_8);
            payloadLen += 4 + encoded[i].length;
        }
        put1(LogFormat.REC_OP_DELTA);
        put4(payloadLen);
        put4(firstId);
        put4(names.size());
        for (byte[] bytes : encoded) {
            put4(bytes.length);
            putBytes(bytes);
        }
    }

    /**
     * Emit a parameter-values intern-table delta. Each entry is a list
     * of UTF-8 display strings, one per bound PreparedStatement index.
     * Only written when {@code captureParameterValues} is enabled.
     */
    public void writeParamValuesDelta(int firstId, List<ParameterValues> entries) throws IOException {
        if (entries.isEmpty()) {
            return;
        }
        byte[][][] encoded = new byte[entries.size()][][];
        int payloadLen = 4 + 4; // firstId + count
        for (int i = 0; i < entries.size(); i++) {
            List<String> slots = entries.get(i).slots();
            encoded[i] = new byte[slots.size()][];
            payloadLen += 4; // slot count
            for (int j = 0; j < slots.size(); j++) {
                encoded[i][j] = slots.get(j).getBytes(StandardCharsets.UTF_8);
                payloadLen += 4 + encoded[i][j].length;
            }
        }
        put1(LogFormat.REC_PARAM_VALUES_DELTA);
        put4(payloadLen);
        put4(firstId);
        put4(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            byte[][] slots = encoded[i];
            put4(slots.length);
            for (byte[] bytes : slots) {
                put4(bytes.length);
                putBytes(bytes);
            }
        }
    }

    /**
     * Emit a stack-trace intern-table delta. Analogous to
     * {@link #writeSqlDelta}.
     */
    public void writeStackDelta(int firstId, List<StackFrameSnapshot[]> stacks) throws IOException {
        if (stacks.isEmpty()) {
            return;
        }
        byte[][][] encoded = new byte[stacks.size()][][];
        int payloadLen = 4 + 4; // firstId + count
        for (int i = 0; i < stacks.size(); i++) {
            StackFrameSnapshot[] frames = stacks.get(i);
            encoded[i] = new byte[frames.length * 2][];
            payloadLen += 2; // frame count
            for (int j = 0; j < frames.length; j++) {
                encoded[i][2 * j] = frames[j].className().getBytes(StandardCharsets.UTF_8);
                encoded[i][2 * j + 1] = frames[j].methodName().getBytes(StandardCharsets.UTF_8);
                payloadLen += 2 + encoded[i][2 * j].length
                        + 2 + encoded[i][2 * j + 1].length
                        + 4; // line number
            }
        }
        put1(LogFormat.REC_STACK_DELTA);
        put4(payloadLen);
        put4(firstId);
        put4(stacks.size());
        for (int i = 0; i < stacks.size(); i++) {
            StackFrameSnapshot[] frames = stacks.get(i);
            put2(frames.length);
            for (int j = 0; j < frames.length; j++) {
                byte[] cls = encoded[i][2 * j];
                byte[] mth = encoded[i][2 * j + 1];
                put2(cls.length);
                putBytes(cls);
                put2(mth.length);
                putBytes(mth);
                put4(frames[j].lineNumber());
            }
        }
    }

    /**
     * Emit a batch of {@code count} events. Each event is encoded in
     * the fixed 68-byte layout described in spec §5.2.
     */
    public void writeEvents(Event[] batch, int count) throws IOException {
        if (count <= 0) {
            return;
        }
        int payloadLen = 4 + count * LogFormat.EVENT_BYTES;
        put1(LogFormat.REC_EVENTS);
        put4(payloadLen);
        put4(count);
        for (int i = 0; i < count; i++) {
            putEvent(batch[i]);
        }
    }

    private void putEvent(Event e) throws IOException {
        put8(e.timestampNanos);
        put4(e.threadId);
        put8(e.operationId);
        put8(e.operationInvocationId);
        put1(e.eventType);
        put4(e.sqlId);
        put4(e.stackTraceId);
        put8(e.durationNanos);
        put4(e.rowsAffected);
        put4(e.batchSize);
        put8(e.parameterFingerprint);
        put4(e.parameterValuesId);
        // 3 bytes of padding to land on a 68-byte boundary.
        put1((byte) 0);
        put1((byte) 0);
        put1((byte) 0);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        put1(LogFormat.REC_END);
        put4(0);
        flushBuffer();
        // CRC32 of everything above; write the checksum raw (not through
        // the buffer) so it isn't itself folded into the running CRC.
        ByteBuffer sum = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        sum.putInt((int) crc.getValue());
        sum.flip();
        while (sum.hasRemaining()) {
            channel.write(sum);
        }
        channel.force(true);
        channel.close();
    }

    // --- internal ByteBuffer plumbing ---

    private void ensure(int bytes) throws IOException {
        if (buf.remaining() < bytes) {
            flushBuffer();
        }
    }

    private void put1(byte v) throws IOException {
        ensure(1);
        buf.put(v);
    }

    private void put1(int v) throws IOException {
        put1((byte) v);
    }

    private void put2(int v) throws IOException {
        ensure(2);
        buf.putShort((short) v);
    }

    private void put4(int v) throws IOException {
        ensure(4);
        buf.putInt(v);
    }

    private void put8(long v) throws IOException {
        ensure(8);
        buf.putLong(v);
    }

    private void putBytes(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            if (buf.remaining() == 0) {
                flushBuffer();
            }
            int chunk = Math.min(buf.remaining(), bytes.length - offset);
            buf.put(bytes, offset, chunk);
            offset += chunk;
        }
    }

    private void flushBuffer() throws IOException {
        if (buf.position() == 0) {
            return;
        }
        buf.flip();
        // CRC32#update(ByteBuffer) advances position; use a duplicate so
        // the original buffer is still writable below.
        ByteBuffer crcView = buf.duplicate();
        crc.update(crcView);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        buf.clear();
    }
}
