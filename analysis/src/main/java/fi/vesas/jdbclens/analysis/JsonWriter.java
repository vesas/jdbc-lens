package fi.vesas.jdbclens.analysis;

import java.io.PrintStream;

/**
 * Streaming JSON emitter that wraps a {@link PrintStream}. Handles
 * comma placement and string escaping; produces compact, valid JSON.
 *
 * <p>Call sequence must respect JSON grammar — no validation is done.
 * Depth limit is 8 levels, sufficient for the report schema.
 */
final class JsonWriter {

    private final PrintStream out;
    // commaNeeded[i] = true once the first element of nesting level i has been emitted
    private final boolean[] commaNeeded = new boolean[8];
    private int depth = 0;

    JsonWriter(PrintStream out) {
        this.out = out;
    }

    void beginObject() {
        emitComma();
        out.print('{');
        commaNeeded[depth++] = false;
    }

    void beginObject(String key) {
        emitComma();
        out.print('"');
        emitEscaped(key);
        out.print("\":{");
        commaNeeded[depth++] = false;
    }

    void endObject() {
        --depth;
        out.print('}');
        markComma();
    }

    void beginArray(String key) {
        emitComma();
        out.print('"');
        emitEscaped(key);
        out.print("\":[");
        commaNeeded[depth++] = false;
    }

    void endArray() {
        --depth;
        out.print(']');
        markComma();
    }

    /** Skips the field entirely when {@code value} is {@code null}. */
    void field(String key, String value) {
        if (value == null) return;
        emitComma();
        out.print('"');
        emitEscaped(key);
        out.print("\":\"");
        emitEscaped(value);
        out.print('"');
        markComma();
    }

    void field(String key, long value) {
        emitComma();
        out.print('"');
        emitEscaped(key);
        out.print("\":");
        out.print(value);
        markComma();
    }

    void field(String key, boolean value) {
        emitComma();
        out.print('"');
        emitEscaped(key);
        out.print("\":");
        out.print(value);
        markComma();
    }

    void newline() {
        out.println();
    }

    private void emitComma() {
        if (depth > 0 && commaNeeded[depth - 1]) {
            out.print(',');
        }
    }

    private void markComma() {
        if (depth > 0) {
            commaNeeded[depth - 1] = true;
        }
    }

    private void emitEscaped(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.print("\\\"");
                case '\\' -> out.print("\\\\");
                case '\n' -> out.print("\\n");
                case '\r' -> out.print("\\r");
                case '\t' -> out.print("\\t");
                default -> {
                    if (c < 0x20) {
                        out.printf("\\u%04x", (int) c);
                    } else {
                        out.print(c);
                    }
                }
            }
        }
    }
}
