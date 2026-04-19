package io.github.vesas.jdbcprof.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
