package fi.vesas.jdbclens.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParamLabelsTest {

    @Test
    void simpleWhereEquals() {
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "SELECT name FROM customers WHERE id = ?");
        assertThat(labels).containsExactly(Map.entry(1, "id"));
    }

    @Test
    void multipleAndedPredicatesMapDistinctIndices() {
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?");
        assertThat(labels).containsExactly(
                Map.entry(1, "a"),
                Map.entry(2, "b"),
                Map.entry(3, "c"));
    }

    @Test
    void dottedQualifiersArePreserved() {
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "SELECT A.name FROM t A WHERE A.PS = ? AND A.MOYT = ?");
        assertThat(labels).containsExactly(
                Map.entry(1, "A.PS"),
                Map.entry(2, "A.MOYT"));
    }

    @Test
    void toDateUnwrapsToTheColumnOnTheOtherSide() {
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "SELECT 1 FROM t WHERE d <= TO_DATE(?, 'YYYY.MM.DD')");
        // The placeholder must label as the compared column, not
        // "TO_DATE" and not the format literal.
        assertThat(labels).containsExactly(Map.entry(1, "d"));
    }

    @Test
    void inListMapsAllPlaceholdersToTheSameColumn() {
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "SELECT 1 FROM t WHERE status IN (?, ?, ?)");
        assertThat(labels).containsExactly(
                Map.entry(1, "status"),
                Map.entry(2, "status"),
                Map.entry(3, "status"));
    }

    @Test
    void betweenMapsBothBounds() {
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "SELECT 1 FROM t WHERE x BETWEEN ? AND ?");
        assertThat(labels).containsExactly(
                Map.entry(1, "x"),
                Map.entry(2, "x"));
    }

    @Test
    void setClauseLabelsEachUpdatedColumn() {
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "UPDATE t SET name = ?, email = ? WHERE id = ?");
        assertThat(labels).containsExactly(
                Map.entry(1, "name"),
                Map.entry(2, "email"),
                Map.entry(3, "id"));
    }

    @Test
    void subqueryPlaceholdersResolveAgainstTheirOwnWhere() {
        String sql = "SELECT A.PER FROM PS_PER A "
                + "WHERE A.PS = ? AND A.PTM_KIIN = ? AND A.MOYT = ? "
                + "AND A.ALKUPVM = (SELECT MIN(B.ALKUPVM) FROM PS_PER B "
                + "WHERE B.PS = ? AND B.MOYT = ? AND B.PTM_KIIN = ? "
                + "AND B.ALKUPVM <= TO_DATE(?, 'YYYY.MM.DD') "
                + "AND B.LOPPUPVM >= TO_DATE(?, 'YYYY.MM.DD') "
                + "AND (B.VARS_POIS = 'V' OR (B.VARS_POIS = 'P' AND B.AR = ?))) "
                + "AND (A.VARS_POIS = 'V' OR (A.VARS_POIS = 'P' AND A.AR = ?))";
        Map<Integer, String> labels = ParamLabels.labelsFor(sql);

        assertThat(labels).containsEntry(1, "A.PS");
        assertThat(labels).containsEntry(2, "A.PTM_KIIN");
        assertThat(labels).containsEntry(3, "A.MOYT");
        assertThat(labels).containsEntry(4, "B.PS");
        assertThat(labels).containsEntry(5, "B.MOYT");
        assertThat(labels).containsEntry(6, "B.PTM_KIIN");
        assertThat(labels).containsEntry(7, "B.ALKUPVM");
        assertThat(labels).containsEntry(8, "B.LOPPUPVM");
        assertThat(labels).containsEntry(9, "B.AR");
        assertThat(labels).containsEntry(10, "A.AR");
    }

    @Test
    void selectListPlaceholderHasNoColumnAndIsOmitted() {
        // "SELECT ? AS v" — the placeholder isn't bound to any
        // specific column, so it must not be mislabeled (e.g. as
        // "SELECT").
        Map<Integer, String> labels = ParamLabels.labelsFor("SELECT ? AS v");
        assertThat(labels).isEmpty();
    }

    @Test
    void stringLiteralBeforePlaceholderDoesNotBleedIntoLabel() {
        // The literal "V" mustn't become the label for the second
        // placeholder.
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "SELECT 1 FROM t WHERE status = 'V' AND id = ?");
        assertThat(labels).containsExactly(Map.entry(1, "id"));
    }

    @Test
    void nullAndEmptyReturnEmptyMap() {
        assertThat(ParamLabels.labelsFor(null)).isEmpty();
        assertThat(ParamLabels.labelsFor("")).isEmpty();
        assertThat(ParamLabels.labelsFor("   ")).isEmpty();
    }

    @Test
    void insertValuesLeavesSlotsUnlabeledForNow() {
        // Matching VALUES(?, ?, ?) positions to the INSERT column
        // list is possible but not implemented — verify today's
        // behaviour so a future implementation tightens this test.
        Map<Integer, String> labels = ParamLabels.labelsFor(
                "INSERT INTO t (a, b, c) VALUES (?, ?, ?)");
        assertThat(labels).isEmpty();
    }
}
