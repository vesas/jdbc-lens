package fi.vesas.jdbclens.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureContextTest {

    @Test
    void operationContextIsThreadLocalAndDoesNotLeakBetweenThreads() throws Exception {
        CaptureContext ctx = new CaptureContext(16, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT 1");

        ctx.setCurrentOperation("mainOp");
        long mainInvocationId = ctx.currentInvocationId();
        int mainThreadId = (int) Thread.currentThread().threadId();
        ctx.emitNoTrace(EventType.EXECUTE_QUERY.code(), sqlId, 100L, 10L, 0, 0, 0L);

        AtomicInteger workerThreadId = new AtomicInteger();
        Thread worker = new Thread(() -> {
            workerThreadId.set((int) Thread.currentThread().threadId());
            // Worker emits without setting an operation; sentinels must remain in the event.
            ctx.emitNoTrace(EventType.EXECUTE_QUERY.code(), sqlId, 200L, 20L, 0, 0, 0L);
        }, "capture-context-worker");
        worker.start();
        worker.join();

        assertThat(ctx.allRings())
                .as("one ring is lazily registered per thread that emits")
                .hasSize(2);

        List<Event> events = drainAll(ctx);
        assertThat(events).hasSize(2);

        Event mainEvent = events.stream()
                .filter(e -> e.threadId == mainThreadId)
                .findFirst()
                .orElseThrow();
        Event workerEvent = events.stream()
                .filter(e -> e.threadId == workerThreadId.get())
                .findFirst()
                .orElseThrow();

        int mainOpId = ctx.opIntern().intern("mainOp");
        assertThat(mainEvent.operationId).isEqualTo(mainOpId);
        assertThat(mainEvent.operationInvocationId).isEqualTo(mainInvocationId);

        assertThat(workerEvent.operationId).isEqualTo(CaptureContext.NO_OPERATION);
        assertThat(workerEvent.operationInvocationId).isEqualTo(CaptureContext.NO_OPERATION_INVOCATION);
    }

    @Test
    void clearingCurrentOperationRemovesOpAndInvocationIdsFromSubsequentEvents() {
        CaptureContext ctx = new CaptureContext(16, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT 1");

        ctx.setCurrentOperation("op");
        assertThat(ctx.currentOperationId()).isNotEqualTo(CaptureContext.NO_OPERATION);

        ctx.setCurrentOperation(null);
        assertThat(ctx.currentOperationId()).isEqualTo(CaptureContext.NO_OPERATION);
        assertThat(ctx.currentInvocationId()).isEqualTo(CaptureContext.NO_OPERATION_INVOCATION);

        ctx.emitNoTrace(EventType.EXECUTE_QUERY.code(), sqlId, 1L, 1L, 0, 0, 0L);
        List<Event> events = drainAll(ctx);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).operationId).isEqualTo(CaptureContext.NO_OPERATION);
        assertThat(events.get(0).operationInvocationId).isEqualTo(CaptureContext.NO_OPERATION_INVOCATION);
    }

    @Test
    void eachOperationSetProducesAStrictlyIncreasingInvocationId() {
        CaptureContext ctx = new CaptureContext(16, 20);

        ctx.setCurrentOperation("op-a");
        long inv1 = ctx.currentInvocationId();
        ctx.setCurrentOperation("op-b");
        long inv2 = ctx.currentInvocationId();
        ctx.setCurrentOperation("op-a"); // same name as first — still a new invocation
        long inv3 = ctx.currentInvocationId();

        assertThat(inv1).isPositive();
        assertThat(inv2).isGreaterThan(inv1);
        assertThat(inv3).isGreaterThan(inv2);
    }

    @Test
    void emitWhenRingIsFullDropsEventAndIncrementsDropCount() {
        int ringCapacity = 4;
        CaptureContext ctx = new CaptureContext(ringCapacity, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT 1");

        // Fill the ring completely without draining.
        for (int i = 0; i < ringCapacity; i++) {
            ctx.emitNoTrace(EventType.EXECUTE_QUERY.code(), sqlId, i, 1L, 0, 0, 0L);
        }
        // Ring is full; this emit must be dropped rather than overwrite a live slot.
        ctx.emitNoTrace(EventType.EXECUTE_QUERY.code(), sqlId, 100L, 1L, 0, 0, 0L);

        SpscRingBuffer ring = ctx.currentRing();
        assertThat(ring.droppedCount()).isEqualTo(1L);
        assertThat(ring.size()).isEqualTo(ringCapacity);
    }

    @Test
    void emitNoTraceProducesEventWithNoStackTraceSentinel() {
        CaptureContext ctx = new CaptureContext(16, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT 1");

        ctx.emitNoTrace(EventType.EXECUTE_QUERY.code(), sqlId, 100L, 10L, 0, 0, 0L);

        List<Event> events = drainAll(ctx);
        assertThat(events).hasSize(1);
        // emitNoTrace is used for NEXT/CLOSE events where walking 30 frames per row
        // would dominate hot-path cost; NO_STACK_TRACE is the sentinel the analysis
        // layer uses to skip those events during attribution.
        assertThat(events.get(0).stackTraceId).isEqualTo(CaptureContext.NO_STACK_TRACE);
    }

    private static List<Event> drainAll(CaptureContext ctx) {
        List<Event> out = new ArrayList<>();
        Event[] buf = new Event[16];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = new Event();
        }
        for (SpscRingBuffer ring : ctx.allRings()) {
            int n;
            while ((n = ring.drain(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    Event copy = new Event();
                    copy.copyFrom(buf[i]);
                    out.add(copy);
                }
            }
        }
        return out;
    }
}