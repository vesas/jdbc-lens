package fi.vesas.jdbcprof.sample.batch;

import fi.vesas.jdbcprof.sample.dao.BalanceDao;
import fi.vesas.jdbcprof.sample.dao.ExceptionDao;
import fi.vesas.jdbcprof.sample.dao.OrderDao;
import fi.vesas.jdbcprof.sample.dao.OutboxDao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Monthly "reconcile customer balances against the orders ledger"
 * batch. Enterprise-canonical shape:
 *
 * <ol>
 *   <li>For each customer id: aggregate the orders table
 *       (count + sum) with two separate round trips.</li>
 *   <li>Read the existing balance row.</li>
 *   <li>Either INSERT (first time seen) or UPDATE (overwriting every
 *       rollup column) the balance.</li>
 *   <li>If the recomputed total breaks a business threshold, record
 *       a reconciliation exception and enqueue an outbox event so
 *       the dispatcher can notify downstream.</li>
 * </ol>
 *
 * <p>Every step of that chain is a known anti-pattern: N+1 reads,
 * N+1 writes, READ-then-WRITE per row, wide UPDATEs. It's the
 * workload shape the profiler was built to shame.
 */
public final class ReconciliationJob {

    private static final BigDecimal ALERT_THRESHOLD = new BigDecimal("100.00");

    private final OrderDao orders;
    private final BalanceDao balances;
    private final ExceptionDao exceptions;
    private final OutboxDao outbox;

    public ReconciliationJob(OrderDao orders, BalanceDao balances,
                              ExceptionDao exceptions, OutboxDao outbox) {
        this.orders = orders;
        this.balances = balances;
        this.exceptions = exceptions;
        this.outbox = outbox;
    }

    public int run(Connection c, int[] customerIds) throws SQLException {
        int processed = 0;
        for (int customerId : customerIds) {
            int count = orders.countByCustomer(c, customerId);
            BigDecimal sum = orders.sumAmountByCustomer(c, customerId);
            java.sql.Timestamp lastAt = orders.latestOrderAtByCustomer(c, customerId);

            BalanceDao.Balance next = new BalanceDao.Balance(
                    customerId, count, sum, lastAt);
            BalanceDao.Balance current = balances.findByCustomer(c, customerId);
            if (current == null) {
                balances.insert(c, next);
            } else {
                balances.updateAll(c, next);
            }

            if (current != null && sum.subtract(current.lifetimeSpend())
                    .abs().compareTo(ALERT_THRESHOLD) > 0) {
                String reason = "lifetime_spend drift > " + ALERT_THRESHOLD;
                exceptions.record(c, customerId, current.lifetimeSpend(), sum, reason);
                outbox.enqueue(c, "recon.exception",
                        "customer=" + customerId + "; " + reason);
            }
            processed++;
        }
        return processed;
    }
}
