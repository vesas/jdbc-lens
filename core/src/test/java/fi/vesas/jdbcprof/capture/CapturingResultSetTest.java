package fi.vesas.jdbcprof.capture;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CapturingResultSetTest {

    @Test
    void nextWithNegativeSqlIdDelegatesWithoutEmittingEvent() throws Exception {
        CaptureContext ctx = new CaptureContext(16, 20);
        AtomicBoolean delegateCalled = new AtomicBoolean();
        ResultSet stub = stubNextReturning(delegateCalled, true);

        // sqlId=-1 means the producing statement had no live session at prepare time;
        // the result set must skip the emit entirely.
        CapturingResultSet crs = new CapturingResultSet(stub, () -> ctx, -1);
        boolean row = crs.next();

        assertThat(row).isTrue();
        assertThat(delegateCalled.get()).isTrue();
        // ctxSupplier is never called when sqlId<0, so no ring is registered.
        assertThat(ctx.allRings()).isEmpty();
    }

    @Test
    void nextWithActiveCaptureContextEmitsNextEvent() throws Exception {
        CaptureContext ctx = new CaptureContext(16, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT ?");
        ResultSet stub = stubNextReturning(new AtomicBoolean(), true);

        CapturingResultSet crs = new CapturingResultSet(stub, () -> ctx, sqlId);
        crs.next();

        List<Event> events = drainAll(ctx);
        assertThat(events).hasSize(1);
        assertThat(EventType.fromCode(events.get(0).eventType)).isEqualTo(EventType.NEXT);
        assertThat(events.get(0).sqlId).isEqualTo(sqlId);
    }

    @Test
    void nextEmittedEventCarriesNoStackTrace() throws Exception {
        // next() is in a hot inner loop; the spec says not to walk 30 frames
        // per row iteration. The stackTraceId must be the NO_STACK_TRACE sentinel.
        CaptureContext ctx = new CaptureContext(16, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT 1");
        ResultSet stub = stubNextReturning(new AtomicBoolean(), true);

        CapturingResultSet crs = new CapturingResultSet(stub, () -> ctx, sqlId);
        crs.next();

        List<Event> events = drainAll(ctx);
        assertThat(events.get(0).stackTraceId).isEqualTo(CaptureContext.NO_STACK_TRACE);
    }

    @Test
    void nextReturnValuePassesThroughFromDelegate() throws Exception {
        CaptureContext ctx = new CaptureContext(16, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT 1");
        // Simulate a result set with one row: first next()=true, second=false.
        ResultSet stub = stubNextReturning(new AtomicBoolean(), true, false);

        CapturingResultSet crs = new CapturingResultSet(stub, () -> ctx, sqlId);
        assertThat(crs.next()).isTrue();
        assertThat(crs.next()).isFalse();
    }

    @Test
    void nextWithNullContextFromSupplierDelegatesWithoutEmittingEvent() throws Exception {
        // Simulates Profiler.stop() being called while a result set is still open:
        // the ctxSupplier returns null (no active session), so NEXT must not be recorded.
        int sqlId = 0; // non-negative, so the sqlId guard doesn't short-circuit first
        AtomicBoolean delegateCalled = new AtomicBoolean();
        AtomicBoolean supplierCalled = new AtomicBoolean();
        ResultSet stub = stubNextReturning(delegateCalled, false);

        CapturingResultSet crs = new CapturingResultSet(stub, () -> { supplierCalled.set(true); return null; }, sqlId);
        boolean row = crs.next();

        assertThat(row).isFalse();
        assertThat(supplierCalled.get()).isTrue();
        assertThat(delegateCalled.get()).isTrue();
        // No event ring to drain; the emit was skipped entirely.
    }

    @Test
    void closeWithNegativeSqlIdDelegatesWithoutEmittingEvent() throws Exception {
        CaptureContext ctx = new CaptureContext(16, 20);
        AtomicBoolean closeCalled = new AtomicBoolean();
        ResultSet stub = stubClose(closeCalled);

        CapturingResultSet crs = new CapturingResultSet(stub, () -> ctx, -1);
        crs.close();

        assertThat(closeCalled.get()).isTrue();
        assertThat(ctx.allRings()).isEmpty();
    }

    @Test
    void closeWithActiveCaptureContextEmitsCloseEvent() throws Exception {
        CaptureContext ctx = new CaptureContext(16, 20);
        int sqlId = ctx.sqlIntern().intern("SELECT 1");
        AtomicBoolean closeCalled = new AtomicBoolean();
        ResultSet stub = stubClose(closeCalled);

        CapturingResultSet crs = new CapturingResultSet(stub, () -> ctx, sqlId);
        crs.close();

        assertThat(closeCalled.get()).isTrue();
        List<Event> events = drainAll(ctx);
        assertThat(events).hasSize(1);
        assertThat(EventType.fromCode(events.get(0).eventType)).isEqualTo(EventType.CLOSE);
        assertThat(events.get(0).sqlId).isEqualTo(sqlId);
        assertThat(events.get(0).stackTraceId).isEqualTo(CaptureContext.NO_STACK_TRACE);
    }

    // --- helpers ---

    private static ResultSet stubNextReturning(AtomicBoolean delegateCalled, boolean... nextValues) {
        AtomicInteger idx = new AtomicInteger(0);
        return (ResultSet) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        delegateCalled.set(true);
                        int i = idx.getAndIncrement();
                        yield i < nextValues.length ? nextValues[i] : false;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet stubClose(AtomicBoolean closeCalled) {
        return (ResultSet) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> { closeCalled.set(true); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<Event> drainAll(CaptureContext ctx) {
        List<Event> out = new ArrayList<>();
        Event[] buf = new Event[16];
        for (int i = 0; i < buf.length; i++) buf[i] = new Event();
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
