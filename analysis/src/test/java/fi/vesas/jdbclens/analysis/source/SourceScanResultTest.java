package fi.vesas.jdbclens.analysis.source;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SourceScanResultTest {

    @Test
    void groupsReadersAndWritersByTable() {
        SourceSqlSite r = new SourceSqlSite(Path.of("Dao.java"), 10,
                Set.of("customers"), null, "SELECT",
                "SELECT id FROM customers WHERE id = ?");
        SourceSqlSite w = new SourceSqlSite(Path.of("Service.java"), 20,
                Set.of("customers"), "customers", "UPDATE",
                "UPDATE customers SET name = ? WHERE id = ?");

        SourceScanResult res = SourceScanResult.of(List.of(r, w));
        SourceScanResult.TableSites sites = res.forTable("customers");
        assertThat(sites.readers()).hasSize(1);
        assertThat(sites.readers().get(0).line()).isEqualTo(10);
        assertThat(sites.writers()).hasSize(1);
        assertThat(sites.writers().get(0).line()).isEqualTo(20);
        assertThat(sites.writers().get(0).kind()).isEqualTo("UPDATE");
    }

    @Test
    void joinedTableCountsAsReader() {
        SourceSqlSite join = new SourceSqlSite(Path.of("Report.java"), 5,
                Set.of("orders", "customers"), null, "SELECT",
                "SELECT o.id FROM orders o JOIN customers c ON ...");

        SourceScanResult res = SourceScanResult.of(List.of(join));
        assertThat(res.forTable("orders").readers()).hasSize(1);
        assertThat(res.forTable("customers").readers()).hasSize(1);
        assertThat(res.forTable("orders").writers()).isEmpty();
        assertThat(res.forTable("customers").writers()).isEmpty();
    }

    @Test
    void updateTargetIsWriterEvenWhenAlsoInReadSet() {
        // Mimics what TemplateShape returns for an UPDATE: writeTarget
        // is the DML target; readTables contains it plus any subquery
        // tables. Only the target counts as a writer; only the extras
        // count as readers.
        SourceSqlSite upd = new SourceSqlSite(Path.of("Bulk.java"), 7,
                Set.of("orders", "customers"), "orders", "UPDATE",
                "UPDATE orders SET status = ? WHERE customer_id IN (...)");

        SourceScanResult res = SourceScanResult.of(List.of(upd));
        assertThat(res.forTable("orders").writers()).hasSize(1);
        assertThat(res.forTable("orders").readers()).isEmpty();
        assertThat(res.forTable("customers").writers()).isEmpty();
        assertThat(res.forTable("customers").readers()).hasSize(1);
    }

    @Test
    void emptyInputProducesEmptyResult() {
        SourceScanResult res = SourceScanResult.of(List.of());
        assertThat(res.isEmpty()).isTrue();
        assertThat(res.forTable("anything").isEmpty()).isTrue();
    }

    @Test
    void unknownTableReturnsEmptySites() {
        SourceScanResult res = SourceScanResult.empty();
        assertThat(res.forTable("nope")).isSameAs(SourceScanResult.TableSites.EMPTY);
    }

    @Test
    void tableListIsStablyOrdered() {
        SourceSqlSite a = new SourceSqlSite(Path.of("A.java"), 1,
                Set.of("z_table"), null, "SELECT", "...");
        SourceSqlSite b = new SourceSqlSite(Path.of("B.java"), 2,
                Set.of("a_table"), null, "SELECT", "...");
        SourceSqlSite c = new SourceSqlSite(Path.of("C.java"), 3,
                Set.of("m_table"), null, "SELECT", "...");

        // Alphabetical per TreeMap semantics.
        assertThat(SourceScanResult.of(List.of(a, b, c)).tables())
                .containsExactly("a_table", "m_table", "z_table");
    }
}
