package fi.vesas.jdbclens.sample;

import com.p6spy.engine.spy.P6DataSource;

import fi.vesas.jdbclens.Profiler;
import fi.vesas.jdbclens.ProfilerConfig;
import fi.vesas.jdbclens.sample.batch.DailyReportJob;
import fi.vesas.jdbclens.sample.batch.OutboxDispatcher;
import fi.vesas.jdbclens.sample.batch.ReconciliationJob;
import fi.vesas.jdbclens.sample.batch.StatementBuilder;
import fi.vesas.jdbclens.sample.cobol.CodeLookupService;
import fi.vesas.jdbclens.sample.cobol.CustomerMasterBatchJob;
import fi.vesas.jdbclens.sample.cobol.MasterDetailMergeJob;
import fi.vesas.jdbclens.sample.dao.AuditDao;
import fi.vesas.jdbclens.sample.dao.BalanceDao;
import fi.vesas.jdbclens.sample.dao.CustomerDao;
import fi.vesas.jdbclens.sample.dao.ExceptionDao;
import fi.vesas.jdbclens.sample.dao.OrderDao;
import fi.vesas.jdbclens.sample.dao.OutboxDao;
import fi.vesas.jdbclens.sample.dao.ProductDao;
import fi.vesas.jdbclens.sample.dao.SessionDao;
import fi.vesas.jdbclens.sample.dao.SettingsDao;
import fi.vesas.jdbclens.sample.service.BulkOpsService;
import fi.vesas.jdbclens.sample.service.CatalogService;
import fi.vesas.jdbclens.sample.service.NotificationService;
import fi.vesas.jdbclens.sample.service.OrderReportService;
import fi.vesas.jdbclens.sample.service.OrderService;
import fi.vesas.jdbclens.sample.service.PaginatedExportService;
import fi.vesas.jdbclens.sample.service.ProductSearchService;
import fi.vesas.jdbclens.sample.service.ProfileService;
import fi.vesas.jdbclens.sample.service.PurgeService;
import fi.vesas.jdbclens.sample.service.RefundService;
import fi.vesas.jdbclens.sample.service.SessionService;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Locale;

/**
 * Runs a small layered workload (dao → service → "request handler")
 * under the profiler. The layering matters: it gives the profiler a
 * real call chain to attribute each query against, so the report's
 * N+1 ancestor frames point at service-method loops rather than at
 * {@code main} itself, and the flamegraph shows depth.
 *
 * <p>The DataSource wrapping is selected by the {@code jdbcprof.mode}
 * system property:
 * <ul>
 *   <li>{@code jdbcprof} (default) — wrap with the in-tree profiler
 *       and write a {@code .jdbclog} recording. This is the demo flow
 *       you get from {@code ./gradlew :sample-app:run}.</li>
 *   <li>{@code none} — no wrapping, no recording. Used by the end-to-
 *       end benchmark in {@code :benchmarks-comparison} as the
 *       baseline subprocess.</li>
 *   <li>{@code p6spy} — wrap with {@link P6DataSource} so the same
 *       harness can compare wall-clock against P6Spy with stack-trace
 *       capture on (see {@code spy.properties}).</li>
 * </ul>
 * The {@link Profiler#currentOperation} calls scattered through the
 * workload are silent no-ops when no profiler session is active, so
 * the same Main body runs unmodified across all three modes.
 */
public final class Main {

    private static final int CUSTOMERS = 200;
    // Per-iteration counts for the request-handler-shaped operations.
    // Scaled to give the end-to-end benchmark a workload that spans
    // multiple seconds even on a fast machine, without changing the
    // mix of JDBC patterns the analyzer sees.
    private static final int CHECKOUTS = 25;
    private static final int REFUNDS = 25;
    private static final int SHIPMENTS = 25;
    private static final int PROFILE_UPDATES = 25;
    private static final String RECORDING = "sample-recording.jdbclog";

    private enum Mode { NONE, JDBCPROF, P6SPY }

    public static void main(String[] args) throws Exception {
        Mode mode = parseMode(System.getProperty("jdbcprof.mode", "jdbcprof"));
        Path log = Path.of(RECORDING).toAbsolutePath();

        switch (mode) {
            case NONE -> {
                // Bare DataSource, no profiler. Baseline run.
            }
            case JDBCPROF -> {
                Profiler.start(ProfilerConfig.defaults(log).withCaptureParameterValues(true));
                DataSources.set(Profiler.wrap(DataSources.get()));
            }
            case P6SPY -> {
                DataSources.set(new P6DataSource(DataSources.get()));
            }
        }

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
        ProductDao productDao = new ProductDao();
        ProductSearchService productSearch = new ProductSearchService(productDao);
        PurgeService purgeService = new PurgeService();
        OrderReportService orderReportService = new OrderReportService(customers);
        PaginatedExportService paginatedExportService = new PaginatedExportService();

        try (Connection c = ds.getConnection()) {
            Profiler.currentOperation("schema");
            Bootstrap.createSchema(c);

            Profiler.currentOperation("seed-customers");
            customers.seed(c, CUSTOMERS);

            Profiler.currentOperation("seed-orders");
            for (int cid = 1; cid <= CUSTOMERS; cid++) {
                orders.insert(c, cid, new java.math.BigDecimal("19.95"));
            }

            Profiler.currentOperation("seed-products");
            Bootstrap.seedProducts(c);

            Profiler.currentOperation("product-search");
            productSearch.searchByKeyword(c, "Wireless");
            productSearch.searchByKeyword(c, "Java");
            productSearch.searchByKeyword(c, "Model");
            productSearch.batchFetchByIds(c, java.util.List.of(1, 3, 5, 8, 12, 15, 20, 25, 30, 35));

            Profiler.currentOperation("list-dashboard");
            orderService.listDashboard(c, CUSTOMERS);

            Profiler.currentOperation("place-orders");
            orderService.placeOrders(c, CUSTOMERS);

            Profiler.currentOperation("render-hot-item");
            catalogService.renderFeaturedItem(c, 1, 10);

            Profiler.currentOperation("heartbeat");
            int[] heartbeatIds = new int[30];
            for (int i = 0; i < heartbeatIds.length; i++) {
                heartbeatIds[i] = i + 1;
            }
            sessionService.refresh(c, heartbeatIds);

            // Each checkout / refund / profile update is logically its
            // own user-facing operation. Calling currentOperation() per
            // invocation gives the report a distinct invocation id for
            // each, so the drill-down timeline shows per-invocation
            // dividers and the swim-lane shows one bar per call.
            for (int i = 0; i < CHECKOUTS; i++) {
                Profiler.currentOperation("complete-checkout");
                int cid = ((i * 7) % CUSTOMERS) + 1;
                java.math.BigDecimal amount = new java.math.BigDecimal(
                        String.format(java.util.Locale.ROOT, "%.2f", 19.95 + i * 5.0));
                orderService.completeCheckout(c, cid, amount);
            }

            for (int i = 0; i < REFUNDS; i++) {
                Profiler.currentOperation("refund-latest");
                int cid = ((i * 11) % CUSTOMERS) + 1;
                refundService.refundLatest(c, cid);
            }

            for (int i = 0; i < SHIPMENTS; i++) {
                Profiler.currentOperation("finalize-shipment");
                int cid = ((i * 13) % CUSTOMERS) + 1;
                orderService.finalizeShipment(c, cid);
            }

            for (int i = 0; i < PROFILE_UPDATES; i++) {
                Profiler.currentOperation("update-profile");
                int cid = ((i * 17) % CUSTOMERS) + 1;
                profileService.updatePhone(c, cid,
                        String.format(java.util.Locale.ROOT, "+1-555-%04d", 1000 + i));
            }

            // Two bulk jobs shaped as row-by-row cursor loops \u2014
            // one SELECT/UPDATE pair per iteration. The analyzer
            // should flag the {@code orders} UPDATE as an N+1 writer
            // and the {@code customers} loop as stacked
            // read-then-write + wide UPDATE.
            Profiler.currentOperation("apply-flat-discount");
            bulkOps.applyFlatDiscount(c, 100, new java.math.BigDecimal("0.10"));

            Profiler.currentOperation("migrate-timezone");
            int[] migrateIds = new int[60];
            for (int i = 0; i < migrateIds.length; i++) {
                migrateIds[i] = i + 1;
            }
            bulkOps.migrateTimezone(c, migrateIds, "America/New_York");

            // Prime the outbox with a handful of pre-existing events
            // so the dispatcher has a non-empty queue on first tick
            // independent of what reconciliation produces.
            Profiler.currentOperation("outbox-seed");
            for (int i = 0; i < 50; i++) {
                outboxDao.enqueue(c, "seed.notification",
                        "prewarm event " + i);
            }

            // Monthly reconciliation \u2014 per-customer aggregate,
            // upsert via READ-then-WRITE, tripped threshold enqueues
            // an outbox event for the dispatcher to pick up.
            Profiler.currentOperation("monthly-reconciliation");
            int[] reconBatch = new int[100];
            for (int i = 0; i < reconBatch.length; i++) {
                reconBatch[i] = i + 1;
            }
            reconciliationJob.run(c, reconBatch);

            // Dispatcher tick \u2014 drains the outbox (seed events +
            // anything reconciliation added). Deliberate row-by-row
            // UPDATE shape; each event is one markSent/markFailed
            // round trip.
            Profiler.currentOperation("outbox-dispatch");
            outboxDispatcher.drain(c, 60);

            Profiler.currentOperation("purge");
            purgeService.purgeProcessedOutboxEvents(c,
                    new java.sql.Timestamp(System.currentTimeMillis()));
            purgeService.trimAuditLog(c, 200);

            // COBOL-transpiled batch shapes. The three jobs below
            // deliberately emit the query patterns that fall out of a
            // paragraph-by-paragraph translation: next-key cursor
            // emulation, per-record commits, INVALID KEY post-checks,
            // nested master/detail walks, and COPY-expanded SQL
            // repeated at multiple call-sites.
            Profiler.currentOperation("schema-extend");
            codeLookupService.seedCodes(c);

            Profiler.currentOperation("post-master-update");
            customerMasterJob.run(c, 60);

            Profiler.currentOperation("match-master-detail");
            masterDetailJob.run(c, 50);

            Profiler.currentOperation("enrich-with-codes");
            int[] enrichIds = new int[60];
            for (int i = 0; i < enrichIds.length; i++) {
                enrichIds[i] = i + 1;
            }
            codeLookupService.enrich(c, enrichIds);

            Profiler.currentOperation("daily-report");
            int[] reportIds = new int[30];
            for (int i = 0; i < reportIds.length; i++) {
                reportIds[i] = ((i * 7) % CUSTOMERS) + 1;
            }
            reportJob.run(c, reportIds);

            Profiler.currentOperation("order-report");
            orderReportService.buildTopReport(c, 10);

            Profiler.currentOperation("paginated-export");
            paginatedExportService.exportAll(c, 20);

            Profiler.currentOperation(null);
        }

        if (mode == Mode.JDBCPROF) {
            Profiler.stop();
            System.out.println("recording: " + log);
            System.out.println("report it: ./gradlew :analysis:run --args=\""
                    + log + " -o report.html\"");
        } else {
            System.out.println("mode=" + mode.name().toLowerCase(Locale.ROOT)
                    + " — no recording produced");
        }
    }

    private static Mode parseMode(String raw) {
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown jdbcprof.mode '" + raw
                            + "'; expected one of: none, jdbcprof, p6spy", e);
        }
    }

    private Main() {
    }
}
