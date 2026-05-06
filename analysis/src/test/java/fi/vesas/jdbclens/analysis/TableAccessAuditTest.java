package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TableAccessAuditTest {

    @Test
    void readOnlyTableHasNoWriters() {
        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(1, "SELECT code, label FROM country_codes WHERE code = ?");
        Map<Long, List<Event>> events = Map.of(1L, List.of(
                exec(EventType.EXECUTE_QUERY, 1, 10),
                exec(EventType.EXECUTE_QUERY, 1, 10),
                exec(EventType.EXECUTE_QUERY, 1, 10)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(10,
                new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.LookupDao", "byCode", 20)});

        List<TableAccessFinding> findings = TableAccessAudit.detect(sqls, events, stacks);
        TableAccessFinding country = findByTable(findings, "country_codes");
        assertThat(country.readOnly()).isTrue();
        assertThat(country.readEvents()).isEqualTo(3L);
        assertThat(country.writers()).isEmpty();
        assertThat(country.readers()).hasSize(1);
    }

    @Test
    void joinedReadShowsBothTablesAsReaders() {
        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(1, "SELECT o.id, c.name FROM orders o "
                + "JOIN customers c ON c.id = o.customer_id WHERE o.id = ?");
        Map<Long, List<Event>> events = Map.of(1L, List.of(
                exec(EventType.EXECUTE_QUERY, 1, 10)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(10,
                new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.OrderDao", "withCustomer", 55)});

        List<TableAccessFinding> findings = TableAccessAudit.detect(sqls, events, stacks);
        // Without referencedTables this JOIN would have been completely
        // invisible to a per-table view \u2014 regression guard.
        assertThat(findings).extracting(TableAccessFinding::table)
                .contains("orders", "customers");
        TableAccessFinding customers = findByTable(findings, "customers");
        assertThat(customers.readers()).hasSize(1);
        assertThat(customers.readOnly()).isTrue();
    }

    @Test
    void singleWriterReadHeavyLooksLikeGoodCacheCandidate() {
        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(1, "SELECT * FROM customers WHERE id = ?");
        sqls.put(2, "UPDATE customers SET last_login = ? WHERE id = ?");
        List<Event> ev = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ev.add(exec(EventType.EXECUTE_QUERY, 1, 10));
        }
        ev.add(exec(EventType.EXECUTE_UPDATE, 2, 20));
        Map<Long, List<Event>> events = Map.of(1L, ev);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                10, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.CustomerDao", "byId", 30)},
                20, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.LoginService", "markLogin", 40)});

        TableAccessFinding customers = findByTable(
                TableAccessAudit.detect(sqls, events, stacks), "customers");
        assertThat(customers.readEvents()).isEqualTo(20L);
        assertThat(customers.writeEvents()).isEqualTo(1L);
        assertThat(customers.distinctWriterCallSites()).isEqualTo(1);
        assertThat(customers.writers()).hasSize(1);
        TableAccessFinding.WriterHit w = customers.writers().get(0);
        assertThat(w.kind()).isEqualTo("UPDATE");
        assertThat(w.setColumns()).containsExactly("last_login");
    }

    @Test
    void manyWriterCallSitesMakesCachingRisky() {
        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(1, "SELECT id FROM orders WHERE id = ?");
        sqls.put(2, "UPDATE orders SET status = ? WHERE id = ?");
        sqls.put(3, "UPDATE orders SET shipped_at = ? WHERE id = ?");
        sqls.put(4, "DELETE FROM orders WHERE id = ?");
        sqls.put(5, "INSERT INTO orders (id, customer_id) VALUES (?, ?)");
        List<Event> ev = List.of(
                exec(EventType.EXECUTE_QUERY, 1, 10),
                exec(EventType.EXECUTE_UPDATE, 2, 20),
                exec(EventType.EXECUTE_UPDATE, 3, 30),
                exec(EventType.EXECUTE_UPDATE, 4, 40),
                exec(EventType.EXECUTE_UPDATE, 5, 50));
        Map<Long, List<Event>> events = Map.of(1L, ev);
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                10, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.A", "a", 1)},
                20, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.B", "b", 2)},
                30, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.C", "c", 3)},
                40, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.D", "d", 4)},
                50, new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.E", "e", 5)});

        TableAccessFinding orders = findByTable(
                TableAccessAudit.detect(sqls, events, stacks), "orders");
        assertThat(orders.distinctWriterCallSites()).isEqualTo(4);
        assertThat(orders.writers()).extracting(TableAccessFinding.WriterHit::kind)
                .containsExactlyInAnyOrder("UPDATE", "UPDATE", "DELETE", "INSERT");
    }

    @Test
    void updateWithSubqueryAttributesReadAndWriteSeparately() {
        Map<Integer, String> sqls = new HashMap<>();
        sqls.put(1, "UPDATE orders SET status = ? WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE active = ?)");
        Map<Long, List<Event>> events = Map.of(1L, List.of(
                exec(EventType.EXECUTE_UPDATE, 1, 10)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(10,
                new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.Bulk", "deactivate", 7)});

        List<TableAccessFinding> findings = TableAccessAudit.detect(sqls, events, stacks);
        TableAccessFinding orders = findByTable(findings, "orders");
        assertThat(orders.writeEvents()).isEqualTo(1L);
        assertThat(orders.readEvents()).isZero();
        TableAccessFinding customers = findByTable(findings, "customers");
        assertThat(customers.readEvents()).isEqualTo(1L);
        assertThat(customers.readOnly()).isTrue();
    }

    @Test
    void prepareAndNextEventsAreIgnored() {
        Map<Integer, String> sqls = Map.of(1, "SELECT * FROM widgets WHERE id = ?");
        Map<Long, List<Event>> events = Map.of(1L, List.of(
                exec(EventType.PREPARE, 1, 10),
                exec(EventType.EXECUTE_QUERY, 1, 10),
                exec(EventType.NEXT, 1, 10),
                exec(EventType.CLOSE, 1, 10)));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(10,
                new StackFrameSnapshot[]{new StackFrameSnapshot("com.example.X", "y", 1)});

        TableAccessFinding f = findByTable(
                TableAccessAudit.detect(sqls, events, stacks), "widgets");
        assertThat(f.readEvents()).isEqualTo(1L);
    }

    private static Event exec(EventType type, int sqlId, int stackId) {
        Event e = new Event();
        e.threadId = 1;
        e.operationId = 1L;
        e.eventType = type.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = 100L;
        return e;
    }

    private static TableAccessFinding findByTable(List<TableAccessFinding> findings, String table) {
        return findings.stream()
                .filter(f -> f.table().equals(table))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + table
                        + "; got: " + findings.stream().map(TableAccessFinding::table).toList()));
    }
}
