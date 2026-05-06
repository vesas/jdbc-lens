package fi.vesas.jdbcprof.comparison;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal JSON read/write for the {@code results.json} shape this
 * module produces. We intentionally do not pull a JSON library in —
 * the schema is fixed, the sizes are tiny (single-digit KB), and the
 * core module's no-runtime-deps discipline (CLAUDE.md) is worth
 * matching here too even though this module isn't core.
 */
final class JsonIO {

    private JsonIO() {
    }

    static void write(Path file, List<Result> results) throws IOException {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n  \"results\": [");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            sb.append(i == 0 ? "\n    " : ",\n    ");
            sb.append("{");
            sb.append("\"scenario\":").append(quote(r.scenario())).append(",");
            sb.append("\"mode\":").append(quote(r.mode().name())).append(",");
            sb.append("\"iterations\":").append(r.iterations()).append(",");
            sb.append("\"repeats\":").append(r.repeats()).append(",");
            sb.append("\"meanNanosPerOp\":").append(num(r.meanNanosPerOp())).append(",");
            sb.append("\"p50NanosPerOp\":").append(num(r.p50NanosPerOp())).append(",");
            sb.append("\"p95NanosPerOp\":").append(num(r.p95NanosPerOp())).append(",");
            sb.append("\"p99NanosPerOp\":").append(num(r.p99NanosPerOp())).append(",");
            sb.append("\"meanThroughputOpsPerSec\":").append(num(r.meanThroughputOpsPerSec()));
            sb.append("}");
        }
        sb.append("\n  ]\n}\n");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Reads back the file written by {@link #write}. The parser is
     * shape-specific and brittle on purpose — if the schema ever needs
     * to grow beyond flat objects, replace this with a real parser
     * rather than stretching the regex.
     */
    static List<Result> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<Result> out = new ArrayList<>();
        int i = text.indexOf('[');
        if (i < 0) {
            throw new IOException("not a results.json: no '[' found");
        }
        int end = text.lastIndexOf(']');
        String body = text.substring(i + 1, end);
        int cursor = 0;
        while (cursor < body.length()) {
            int objStart = body.indexOf('{', cursor);
            if (objStart < 0) {
                break;
            }
            int objEnd = body.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = body.substring(objStart + 1, objEnd);
            out.add(parseObject(obj));
            cursor = objEnd + 1;
        }
        return out;
    }

    private static Result parseObject(String obj) {
        return new Result(
                strField(obj, "scenario"),
                Mode.valueOf(strField(obj, "mode")),
                (int) numField(obj, "iterations"),
                (int) numField(obj, "repeats"),
                numField(obj, "meanNanosPerOp"),
                numField(obj, "p50NanosPerOp"),
                numField(obj, "p95NanosPerOp"),
                numField(obj, "p99NanosPerOp"),
                numField(obj, "meanThroughputOpsPerSec"));
    }

    private static String strField(String obj, String name) {
        String key = "\"" + name + "\":";
        int k = obj.indexOf(key);
        if (k < 0) {
            throw new IllegalArgumentException("missing field " + name);
        }
        int q1 = obj.indexOf('"', k + key.length());
        int q2 = obj.indexOf('"', q1 + 1);
        return obj.substring(q1 + 1, q2);
    }

    private static double numField(String obj, String name) {
        String key = "\"" + name + "\":";
        int k = obj.indexOf(key);
        if (k < 0) {
            throw new IllegalArgumentException("missing field " + name);
        }
        int start = k + key.length();
        int end = start;
        while (end < obj.length()) {
            char ch = obj.charAt(end);
            if (ch == ',' || ch == '}') {
                break;
            }
            end++;
        }
        return Double.parseDouble(obj.substring(start, end).trim());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"', '\\' -> sb.append('\\').append(ch);
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        // Locale.ROOT keeps the decimal as '.' regardless of the JVM
        // default — JSON parsers don't accept ',' as a separator.
        return String.format(Locale.ROOT, "%.3f", d);
    }
}
