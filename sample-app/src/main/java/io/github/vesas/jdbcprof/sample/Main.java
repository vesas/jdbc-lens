package io.github.vesas.jdbcprof.sample;

import io.github.vesas.jdbcprof.Profiler;
import io.github.vesas.jdbcprof.ProfilerConfig;
import io.github.vesas.jdbcprof.sample.dao.CustomerDao;
import io.github.vesas.jdbcprof.sample.dao.OrderDao;
import io.github.vesas.jdbcprof.sample.dao.SessionDao;
import io.github.vesas.jdbcprof.sample.dao.SettingsDao;
import io.github.vesas.jdbcprof.sample.service.CatalogService;
import io.github.vesas.jdbcprof.sample.service.OrderService;
import io.github.vesas.jdbcprof.sample.service.SessionService;

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
        OrderService orderService = new OrderService(orders, customers, settings);
        CatalogService catalogService = new CatalogService(orders);
        SessionService sessionService = new SessionService(sessionDao);

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
