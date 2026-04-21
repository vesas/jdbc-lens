package fi.vesas.jdbcprof.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateShapeTest {

    @Test
    void selectByIdExtractsTableAndColumn() {
        TemplateShape s = TemplateShape.of("SELECT name FROM customers WHERE id = ?");
        assertThat(s).isNotNull();
        assertThat(s.table()).isEqualTo("customers");
        assertThat(s.columnsByParamIdx()).containsExactly(Map.entry(1, "id"));
    }

    @Test
    void updateWithSetBeforeWhereUsesCorrectParamIndex() {
        TemplateShape s = TemplateShape.of(
                "UPDATE customers SET name = ?, email = ? WHERE id = ?");
        assertThat(s).isNotNull();
        assertThat(s.table()).isEqualTo("customers");
        // SET has params 1 and 2; WHERE id = ? is param 3.
        assertThat(s.columnsByParamIdx()).containsExactly(Map.entry(3, "id"));
    }

    @Test
    void deleteExtractsTable() {
        TemplateShape s = TemplateShape.of("DELETE FROM sessions WHERE id = ?");
        assertThat(s).isNotNull();
        assertThat(s.table()).isEqualTo("sessions");
        assertThat(s.columnsByParamIdx()).containsExactly(Map.entry(1, "id"));
    }

    @Test
    void multipleAndedConditionsAllCaptured() {
        TemplateShape s = TemplateShape.of(
                "SELECT * FROM orders WHERE customer_id = ? AND status = ?");
        assertThat(s).isNotNull();
        assertThat(s.columnsByParamIdx())
                .containsExactly(Map.entry(1, "customer_id"), Map.entry(2, "status"));
    }

    @Test
    void orderByAndLimitDoNotLeakIntoWhere() {
        TemplateShape s = TemplateShape.of(
                "SELECT id, amount FROM orders WHERE customer_id = ? ORDER BY id DESC LIMIT ?");
        assertThat(s).isNotNull();
        // LIMIT param 2 must NOT be part of the WHERE column map.
        assertThat(s.columnsByParamIdx()).containsExactly(Map.entry(1, "customer_id"));
    }

    @Test
    void joinIsSkipped() {
        TemplateShape s = TemplateShape.of(
                "SELECT o.id, c.name FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.id = ?");
        assertThat(s).isNull();
    }

    @Test
    void subquerySkipped() {
        TemplateShape s = TemplateShape.of(
                "SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE active = ?)");
        assertThat(s).isNull();
    }

    @Test
    void noWhereReturnsNull() {
        assertThat(TemplateShape.of("SELECT id, customer_id FROM orders ORDER BY id DESC LIMIT ?"))
                .isNull();
    }

    @Test
    void insertReturnsNull() {
        assertThat(TemplateShape.of("INSERT INTO customers (id, name) VALUES (?, ?)")).isNull();
    }

    @Test
    void nullAndEmptyReturnNull() {
        assertThat(TemplateShape.of(null)).isNull();
        assertThat(TemplateShape.of("")).isNull();
        assertThat(TemplateShape.of("   ")).isNull();
    }

    @Test
    void lowercasesColumnAndTableForStableGrouping() {
        TemplateShape s = TemplateShape.of("select Name from Customers where ID = ?");
        assertThat(s).isNotNull();
        assertThat(s.table()).isEqualTo("customers");
        assertThat(s.columnsByParamIdx()).containsExactly(Map.entry(1, "id"));
    }

    @Test
    void kindIsRecognisedForEachStatementShape() {
        assertThat(TemplateShape.of("SELECT a FROM t WHERE id = ?").kind())
                .isEqualTo(TemplateShape.Kind.SELECT);
        assertThat(TemplateShape.of("UPDATE t SET a = ? WHERE id = ?").kind())
                .isEqualTo(TemplateShape.Kind.UPDATE);
        assertThat(TemplateShape.of("DELETE FROM t WHERE id = ?").kind())
                .isEqualTo(TemplateShape.Kind.DELETE);
    }

    @Test
    void setColumnsExtractedForUpdate() {
        TemplateShape s = TemplateShape.of(
                "UPDATE customers SET name = ?, email = ?, phone = ? WHERE id = ?");
        assertThat(s).isNotNull();
        assertThat(s.setColumns()).containsExactly("name", "email", "phone");
    }

    @Test
    void setColumnParseTolerantOfFunctionsWithCommas() {
        // Commas inside parens mustn't be treated as SET-column separators.
        TemplateShape s = TemplateShape.of(
                "UPDATE t SET last_touched = GREATEST(a, b), counter = counter + 1 WHERE id = ?");
        assertThat(s).isNotNull();
        assertThat(s.setColumns()).containsExactly("last_touched", "counter");
    }

    @Test
    void setColumnsEmptyForSelectAndDelete() {
        assertThat(TemplateShape.of("SELECT a FROM t WHERE id = ?").setColumns()).isEmpty();
        assertThat(TemplateShape.of("DELETE FROM t WHERE id = ?").setColumns()).isEmpty();
    }

    @Test
    void referencedTablesSingleSelect() {
        assertThat(TemplateShape.referencedTables(
                "SELECT id, name FROM customers WHERE id = ?"))
                .containsExactly("customers");
    }

    @Test
    void referencedTablesJoinExposesBothSides() {
        assertThat(TemplateShape.referencedTables(
                "SELECT o.id, c.name FROM orders o JOIN customers c "
                        + "ON c.id = o.customer_id WHERE o.id = ?"))
                .containsExactlyInAnyOrder("orders", "customers");
    }

    @Test
    void referencedTablesSubqueryIncluded() {
        assertThat(TemplateShape.referencedTables(
                "SELECT * FROM orders WHERE customer_id IN "
                        + "(SELECT id FROM customers WHERE active = ?)"))
                .containsExactlyInAnyOrder("orders", "customers");
    }

    @Test
    void referencedTablesForUpdateStatement() {
        assertThat(TemplateShape.referencedTables(
                "UPDATE customers SET name = ? WHERE id = ?"))
                .containsExactly("customers");
    }

    @Test
    void referencedTablesForDeleteStatement() {
        assertThat(TemplateShape.referencedTables(
                "DELETE FROM sessions WHERE id = ?"))
                .containsExactly("sessions");
    }

    @Test
    void referencedTablesForInsertStatement() {
        assertThat(TemplateShape.referencedTables(
                "INSERT INTO audit_log (actor, action) VALUES (?, ?)"))
                .containsExactly("audit_log");
    }

    @Test
    void referencedTablesForUpdateWithJoinedSource() {
        // UPDATE with a WHERE subquery: both tables referenced.
        assertThat(TemplateShape.referencedTables(
                "UPDATE orders SET status = ? "
                        + "WHERE customer_id IN (SELECT id FROM customers WHERE active = ?)"))
                .containsExactlyInAnyOrder("orders", "customers");
    }

    @Test
    void referencedTablesIgnoresForUpdateClause() {
        // "FOR UPDATE" is a locking modifier on a SELECT, not a
        // separate statement \u2014 must not produce a phantom
        // "UPDATE" target.
        Set<String> tables = TemplateShape.referencedTables(
                "SELECT id FROM orders WHERE id = ? FOR UPDATE");
        assertThat(tables).containsExactly("orders");
    }

    @Test
    void referencedTablesReturnsEmptyForNullOrBlank() {
        assertThat(TemplateShape.referencedTables(null)).isEmpty();
        assertThat(TemplateShape.referencedTables("")).isEmpty();
        assertThat(TemplateShape.referencedTables("   ")).isEmpty();
    }

    @Test
    void writeTargetIdentifiesUpdate() {
        assertThat(TemplateShape.writeTarget(
                "UPDATE customers SET name = ? WHERE id = ?"))
                .isEqualTo("customers");
    }

    @Test
    void writeTargetIdentifiesDelete() {
        assertThat(TemplateShape.writeTarget(
                "DELETE FROM sessions WHERE id = ?"))
                .isEqualTo("sessions");
    }

    @Test
    void writeTargetIdentifiesInsert() {
        assertThat(TemplateShape.writeTarget(
                "INSERT INTO audit_log (actor, action) VALUES (?, ?)"))
                .isEqualTo("audit_log");
    }

    @Test
    void writeTargetNullForSelect() {
        assertThat(TemplateShape.writeTarget(
                "SELECT * FROM customers WHERE id = ?"))
                .isNull();
    }

    @Test
    void writeTargetNullForSelectForUpdate() {
        // FOR UPDATE is a lock clause on a SELECT \u2014 no write target.
        assertThat(TemplateShape.writeTarget(
                "SELECT id FROM orders WHERE id = ? FOR UPDATE"))
                .isNull();
    }
}
