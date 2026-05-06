package fi.vesas.jdbclens.sample.batch;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Top-level batch orchestrator. The split into {@link #run},
 * {@link #processCustomers}, and {@link #processOne} is deliberate —
 * each method adds a distinct frame to the stack, so the flamegraph
 * shows the orchestration layer as real rows you can point at when
 * you say "the time was spent inside {@code processCustomers}."
 */
public final class DailyReportJob {

    private final StatementBuilder builder;

    public DailyReportJob(StatementBuilder builder) {
        this.builder = builder;
    }

    public void run(Connection c, int[] customerIds) throws SQLException {
        processCustomers(c, customerIds);
    }

    private void processCustomers(Connection c, int[] ids) throws SQLException {
        for (int id : ids) {
            processOne(c, id);
        }
    }

    private void processOne(Connection c, int customerId) throws SQLException {
        builder.buildFor(c, customerId);
    }
}
