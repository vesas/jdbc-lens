package fi.vesas.jdbcprof.sample;

import fi.vesas.jdbcprof.Profiler;
import fi.vesas.jdbcprof.ProfilerConfig;
import fi.vesas.jdbcprof.sample.batch.DailyReportJob;
import fi.vesas.jdbcprof.sample.batch.OutboxDispatcher;
import fi.vesas.jdbcprof.sample.batch.ReconciliationJob;
import fi.vesas.jdbcprof.sample.batch.StatementBuilder;
import fi.vesas.jdbcprof.sample.cobol.CodeLookupService;
import fi.vesas.jdbcprof.sample.cobol.CustomerMasterBatchJob;
import fi.vesas.jdbcprof.sample.cobol.MasterDetailMergeJob;
import fi.vesas.jdbcprof.sample.dao.AuditDao;
import fi.vesas.jdbcprof.sample.dao.BalanceDao;
import fi.vesas.jdbcprof.sample.dao.CustomerDao;
import fi.vesas.jdbcprof.sample.dao.ExceptionDao;
import fi.vesas.jdbcprof.sample.dao.OrderDao;
import fi.vesas.jdbcprof.sample.dao.OutboxDao;
import fi.vesas.jdbcprof.sample.dao.SessionDao;
import fi.vesas.jdbcprof.sample.dao.SettingsDao;
import fi.vesas.jdbcprof.sample.service.BulkOpsService;
import fi.vesas.jdbcprof.sample.service.CatalogService;
import fi.vesas.jdbcprof.sample.service.NotificationService;
import fi.vesas.jdbcprof.sample.service.OrderService;
import fi.vesas.jdbcprof.sample.service.ProfileService;
import fi.vesas.jdbcprof.sample.service.RefundService;
import fi.vesas.jdbcprof.sample.service.SessionService;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;

/**
 * Runs a small layered workload (dao → service → "request handler")
 * under the profiler. The layering matters: it gives the profiler a
 * real call chain to attribute each query against, so the report's
 * N+1 ancestor frames point at service-method loops rather than at
 * {@code main} itself, and the flamegraph shows depth.
 */
public final class Main {

    private static final int CUSTOMERS = 50;
    private static final String RECORDING = "sample-recording.jdbclog";

    public static void main(String[] args) throws Exception {
        Path log = Path.of(RECORDING).toAbsolutePath();

        Profiler.start(ProfilerConfig.defaults(log).withCaptureParameterValues(true));
        DataSources.set(Profiler.wrap(DataSources.get()));

        DataSource ds = DataSources.get();
        CustomerDao customers = new CustomerDao();
        OrderDao orders = new OrderDao();
        SessionDao sessionDao = new SessionDao();
        SettingsDao settings = new SettingsDao();
        AuditDao audit = new AuditDao();
        NotificationService notifications = new NotificationService(audit, customers);
        OrderService orderService = new OrderService(orders, customers, settings, notifications);
        CatalogService catalogService = new CatalogService(orders);
        SessionService sessionService = new SessionService(sessionDao);
        RefundService refundService = new RefundService(orders, audit);
        ProfileService profileService = new ProfileService(customers);
        BulkOpsService bulkOps = new BulkOpsService(orders, customers);
        OutboxDao outboxDao = new OutboxDao();
        BalanceDao balanceDao = new BalanceDao();
        ExceptionDao exceptionDao = new ExceptionDao();
        ReconciliationJob reconciliationJob = new ReconciliationJob(
                orders, balanceDao, exceptionDao, outboxDao);
        OutboxDispatcher outboxDispatcher = new OutboxDispatcher(outboxDao, audit);
        DailyReportJob reportJob = new DailyReportJob(
                new StatementBuilder(customers, orders, audit));
        CustomerMasterBatchJob customerMasterJob = new CustomerMasterBatchJob();
        MasterDetailMergeJob masterDetailJob = new MasterDetailMergeJob();
        CodeLookupService codeLookupService = new CodeLookupService();

        try (Connection c = ds.getConnection()) {
            Profiler.currentOperation("schema");
            Bootstrap.createSchema(c);

            Profiler.currentOperation("seed-customers");
            customers.seed(c, CUSTOMERS);

            Profiler.currentOperation("seed-orders");
            for (int cid = 1; cid <= CUSTOMERS; cid++) {
                orders.insert(c, cid, new java.math.BigDecimal("19.95"));
            }

            Profiler.currentOperation("list-dashboard");
            orderService.listDashboard(c, CUSTOMERS);

            Profiler.currentOperation("place-orders");
            orderService.placeOrders(c, CUSTOMERS);

            Profiler.currentOperation("render-hot-item");
            catalogService.renderFeaturedItem(c, 1, 10);

            Profiler.currentOperation("heartbeat");
            sessionService.refresh(c, 1, 2, 3, 4, 5);

            // Each checkout / refund / profile update is logically its
            // own user-facing operation. Calling currentOperation() per
            // invocation gives the report a distinct invocation id for
            // each, so the drill-down timeline shows per-invocation
            // dividers and the swim-lane shows one bar per call.
            Profiler.currentOperation("complete-checkout");
            orderService.completeCheckout(c, 7, new java.math.BigDecimal("59.90"));
            Profiler.currentOperation("complete-checkout");
            orderService.completeCheckout(c, 12, new java.math.BigDecimal("149.00"));
            Profiler.currentOperation("complete-checkout");
            orderService.completeCheckout(c, 23, new java.math.BigDecimal("39.95"));

            Profiler.currentOperation("refund-latest");
            refundService.refundLatest(c, 7);
            Profiler.currentOperation("refund-latest");
            refundService.refundLatest(c, 12);
            Profiler.currentOperation("refund-latest");
            refundService.refundLatest(c, 23);

            Profiler.currentOperation("finalize-shipment");
            orderService.finalizeShipment(c, 1);
            Profiler.currentOperation("finalize-shipment");
            orderService.finalizeShipment(c, 2);
            Profiler.currentOperation("finalize-shipment");
            orderService.finalizeShipment(c, 3);

            Profiler.currentOperation("update-profile");
            profileService.updatePhone(c, 7, "+1-555-1001");
            Profiler.currentOperation("update-profile");
            profileService.updatePhone(c, 12, "+1-555-1002");
            Profiler.currentOperation("update-profile");
            profileService.updatePhone(c, 23, "+1-555-1003");

            // Two bulk jobs shaped as row-by-row cursor loops \u2014
            // one SELECT/UPDATE pair per iteration. The analyzer
            // should flag the {@code orders} UPDATE as an N+1 writer
            // and the {@code customers} loop as stacked
            // read-then-write + wide UPDATE.
            Profiler.currentOperation("apply-flat-discount");
            bulkOps.applyFlatDiscount(c, 20, new java.math.BigDecimal("0.10"));

            Profiler.currentOperation("migrate-timezone");
            bulkOps.migrateTimezone(c,
                    new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                    "America/New_York");

            // Prime the outbox with a handful of pre-existing events
            // so the dispatcher has a non-empty queue on first tick
            // independent of what reconciliation produces.
            Profiler.currentOperation("outbox-seed");
            for (int i = 0; i < 8; i++) {
                outboxDao.enqueue(c, "seed.notification",
                        "prewarm event " + i);
            }

            // Monthly reconciliation \u2014 per-customer aggregate,
            // upsert via READ-then-WRITE, tripped threshold enqueues
            // an outbox event for the dispatcher to pick up.
            Profiler.currentOperation("monthly-reconciliation");
            int[] reconBatch = new int[20];
            for (int i = 0; i < reconBatch.length; i++) {
                reconBatch[i] = i + 1;
            }
            reconciliationJob.run(c, reconBatch);

            // Dispatcher tick \u2014 drains the outbox (seed events +
            // anything reconciliation added). Deliberate row-by-row
            // UPDATE shape; each event is one markSent/markFailed
            // round trip.
            Profiler.currentOperation("outbox-dispatch");
            outboxDispatcher.drain(c, 10);

            // COBOL-transpiled batch shapes. The three jobs below
            // deliberately emit the query patterns that fall out of a
            // paragraph-by-paragraph translation: next-key cursor
            // emulation, per-record commits, INVALID KEY post-checks,
            // nested master/detail walks, and COPY-expanded SQL
            // repeated at multiple call-sites.
            Profiler.currentOperation("schema-extend");
            codeLookupService.seedCodes(c);

            Profiler.currentOperation("post-master-update");
            customerMasterJob.run(c, 12);

            Profiler.currentOperation("match-master-detail");
            masterDetailJob.run(c, 10);

            Profiler.currentOperation("enrich-with-codes");
            codeLookupService.enrich(c, new int[] {
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});

            Profiler.currentOperation("daily-report");
            reportJob.run(c, new int[] {1, 7, 12, 23, 42});

            Profiler.currentOperation(null);
        }

        Profiler.stop();

        System.out.println("recording: " + log);
        System.out.println("report it: ./gradlew :analysis:run --args=\""
                + log + " -o report.html\"");
    }

    private Main() {
    }
}
