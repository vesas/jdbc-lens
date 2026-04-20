package fi.vesas.jdbcprof.capture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventTest {

    @Test
    void resetClearsAllFields() {
        Event e = populated();
        e.reset();
        assertThat(e.timestampNanos).isZero();
        assertThat(e.threadId).isZero();
        assertThat(e.operationId).isZero();
        assertThat(e.operationInvocationId).isEqualTo(-1L);
        assertThat(e.eventType).isZero();
        assertThat(e.sqlId).isZero();
        assertThat(e.stackTraceId).isZero();
        assertThat(e.durationNanos).isZero();
        assertThat(e.rowsAffected).isZero();
        assertThat(e.batchSize).isZero();
    }

    @Test
    void copyFromDuplicatesAllFields() {
        Event src = populated();
        Event dst = new Event();
        dst.copyFrom(src);
        assertThat(dst.timestampNanos).isEqualTo(src.timestampNanos);
        assertThat(dst.threadId).isEqualTo(src.threadId);
        assertThat(dst.operationId).isEqualTo(src.operationId);
        assertThat(dst.operationInvocationId).isEqualTo(src.operationInvocationId);
        assertThat(dst.eventType).isEqualTo(src.eventType);
        assertThat(dst.sqlId).isEqualTo(src.sqlId);
        assertThat(dst.stackTraceId).isEqualTo(src.stackTraceId);
        assertThat(dst.durationNanos).isEqualTo(src.durationNanos);
        assertThat(dst.rowsAffected).isEqualTo(src.rowsAffected);
        assertThat(dst.batchSize).isEqualTo(src.batchSize);
    }

    @Test
    void eventTypeCodeRoundTrips() {
        for (EventType t : EventType.values()) {
            assertThat(EventType.fromCode(t.code())).isEqualTo(t);
        }
    }

    private static Event populated() {
        Event e = new Event();
        e.timestampNanos = 1_000_000_001L;
        e.threadId = 42;
        e.operationId = 7L;
        e.operationInvocationId = 11L;
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.sqlId = 3;
        e.stackTraceId = 9;
        e.durationNanos = 123_456L;
        e.rowsAffected = 4;
        e.batchSize = 2;
        return e;
    }
}
