package fi.vesas.jdbclens.analysis;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class JsonWriterTest {

    private static String emit(java.util.function.Consumer<JsonWriter> fn) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf);
        JsonWriter jw = new JsonWriter(ps);
        fn.accept(jw);
        ps.flush();
        return buf.toString();
    }

    @Test
    void emptyObject() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.endObject();
        });
        assertThat(json).isEqualTo("{}");
    }

    @Test
    void singleStringField() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.field("key", "value");
            jw.endObject();
        });
        assertThat(json).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void multiplePrimitivesCommaSeparated() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.field("a", 1L);
            jw.field("b", true);
            jw.field("c", "hello");
            jw.endObject();
        });
        assertThat(json).isEqualTo("{\"a\":1,\"b\":true,\"c\":\"hello\"}");
    }

    @Test
    void nullStringFieldSkipped() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.field("present", "yes");
            jw.field("absent", (String) null);
            jw.endObject();
        });
        assertThat(json).isEqualTo("{\"present\":\"yes\"}");
    }

    @Test
    void nestedObject() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.beginObject("inner");
            jw.field("x", 42L);
            jw.endObject();
            jw.field("after", "ok");
            jw.endObject();
        });
        assertThat(json).isEqualTo("{\"inner\":{\"x\":42},\"after\":\"ok\"}");
    }

    @Test
    void arrayOfObjects() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.beginArray("items");
            jw.beginObject();
            jw.field("n", 1L);
            jw.endObject();
            jw.beginObject();
            jw.field("n", 2L);
            jw.endObject();
            jw.endArray();
            jw.endObject();
        });
        assertThat(json).isEqualTo("{\"items\":[{\"n\":1},{\"n\":2}]}");
    }

    @Test
    void stringEscaping() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.field("s", "a\"b\\c\nd\re");
            jw.endObject();
        });
        assertThat(json).isEqualTo("{\"s\":\"a\\\"b\\\\c\\nd\\re\"}");
    }

    @Test
    void controlCharEscaped() {
        String json = emit(jw -> {
            jw.beginObject();
            jw.field("s", "");
            jw.endObject();
        });
        assertThat(json).isEqualTo("{\"s\":\"\\u0001\"}");
    }
}
