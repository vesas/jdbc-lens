package fi.vesas.jdbcprof.sample.batch;

import fi.vesas.jdbcprof.sample.dao.AuditDao;
import fi.vesas.jdbcprof.sample.dao.OutboxDao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Drains the transactional outbox: fetch a batch of pending events,
 * "deliver" each (in this sample, that means appending to the audit
 * table to stand in for the real outbound call), mark sent. One
 * UPDATE per event, classic row-by-row dispatcher shape.
 *
 * <p>Stops when a drain returns fewer events than {@code batchSize},
 * matching how a real dispatcher tick works. A simulated failure
 * rate flips a few events into the FAILED state so the runtime
 * audit has both {@code markSent} and {@code markFailed} call-sites.
 */
public final class OutboxDispatcher {

    private final OutboxDao outbox;
    private final AuditDao audit;

    public OutboxDispatcher(OutboxDao outbox, AuditDao audit) {
        this.outbox = outbox;
        this.audit = audit;
    }

    public int drain(Connection c, int batchSize) throws SQLException {
        int dispatched = 0;
        int tick = 0;
        while (true) {
            List<OutboxDao.Event> batch = outbox.listPending(c, batchSize);
            if (batch.isEmpty()) {
                return dispatched;
            }
            for (OutboxDao.Event e : batch) {
                // Deterministic "transient failure" on every 7th event
                // so both the sent and failed UPDATE paths light up
                // at runtime without depending on external state.
                boolean simulatedFailure = (tick + 1) % 7 == 0;
                if (simulatedFailure) {
                    outbox.markFailed(c, e.id(), "simulated downstream timeout");
                } else {
                    audit.log(c, "outbox." + e.kind(), e.payload());
                    outbox.markSent(c, e.id());
                    dispatched++;
                }
                tick++;
            }
            if (batch.size() < batchSize) {
                return dispatched;
            }
        }
    }
}
