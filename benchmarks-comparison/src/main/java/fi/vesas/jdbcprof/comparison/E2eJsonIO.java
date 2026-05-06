package fi.vesas.jdbclens.comparison;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JSON read/write for the end-to-end results document. Same
 * deliberately-tiny shape-specific approach as {@link JsonIO} —
 * the document is a few KB at most, and matching the no-runtime-deps
 * discipline of {@code :core} keeps this module tidy.
 */
final class E2eJsonIO {

    private E2eJsonIO() {
    }

    static void write(Path file, List<E2eResult> results) throws IOException {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n  \"results\": [");
        for (int i = 0; i < results.size(); i++) {
            E2eResult r = results.get(i);
            sb.append(i == 0 ? "\n    " : ",\n    ");
            sb.append("{");
            sb.append("\"mode\":\"").append(r.mode().name()).append("\",");
            sb.append("\"meanNanos\":").append(num(r.meanNanos())).append(",");
            sb.append("\"minNanos\":").append(r.minNanos()).append(",");
            sb.append("\"maxNanos\":").append(r.maxNanos()).append(",");
            sb.append("\"stddevNanos\":").append(num(r.stddevNanos())).append(",");
            sb.append("\"runsNanos\":[");
            long[] runs = r.runsNanos();
            for (int j = 0; j < runs.length; j++) {
                if (j > 0) sb.append(',');
                sb.append(runs[j]);
            }
            sb.append("]");
            sb.append("}");
        }
        sb.append("\n  ]\n}\n");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    static List<E2eResult> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<E2eResult> out = new ArrayList<>();
        int arrStart = text.indexOf('[');
        int arrEnd = text.lastIndexOf(']');
        if (arrStart < 0 || arrEnd < 0) {
            throw new IOException("not an e2e results.json: missing array brackets");
        }
        String body = text.substring(arrStart + 1, arrEnd);
        int cursor = 0;
        while (cursor < body.length()) {
            int objStart = body.indexOf('{', cursor);
            if (objStart < 0) break;
            int objEnd = matchingBrace(body, objStart);
            if (objEnd < 0) break;
            String obj = body.substring(objStart + 1, objEnd);
            out.add(parseObject(obj));
            cursor = objEnd + 1;
        }
        return out;
    }

    private static E2eResult parseObject(String obj) {
        Mode mode = Mode.valueOf(strField(obj, "mode"));
        double mean = numField(obj, "meanNanos");
        long min = (long) numField(obj, "minNanos");
        long max = (long) numField(obj, "maxNanos");
        double stddev = numField(obj, "stddevNanos");
        long[] runs = longArrayField(obj, "runsNanos");
        return new E2eResult(mode, runs, mean, min, max, stddev);
    }

    private static String strField(String obj, String name) {
        String key = "\"" + name + "\":\"";
        int k = obj.indexOf(key);
        if (k < 0) throw new IllegalArgumentException("missing " + name);
        int start = k + key.length();
        int end = obj.indexOf('"', start);
        return obj.substring(start, end);
    }

    private static double numField(String obj, String name) {
        String key = "\"" + name + "\":";
        int k = obj.indexOf(key);
        if (k < 0) throw new IllegalArgumentException("missing " + name);
        int start = k + key.length();
        int end = start;
        while (end < obj.length()) {
            char ch = obj.charAt(end);
            if (ch == ',' || ch == '}' || ch == ']') break;
            end++;
        }
        return Double.parseDouble(obj.substring(start, end).trim());
    }

    private static long[] longArrayField(String obj, String name) {
        String key = "\"" + name + "\":[";
        int k = obj.indexOf(key);
        if (k < 0) throw new IllegalArgumentException("missing " + name);
        int start = k + key.length();
        int end = obj.indexOf(']', start);
        String body = obj.substring(start, end).trim();
        if (body.isEmpty()) return new long[0];
        String[] parts = body.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Long.parseLong(parts[i].trim());
        }
        return out;
    }

    private static int matchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        return String.format(Locale.ROOT, "%.3f", d);
    }
}
