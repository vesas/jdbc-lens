package fi.vesas.jdbcprof.capture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlTemplateNormalizerTest {

    @Test
    void integerLiteralRedacted() {
        assertThat(normalize("SELECT * FROM orders WHERE id = 47"))
                .isEqualTo("SELECT * FROM orders WHERE id = ?");
    }

    @Test
    void stringLiteralRedacted() {
        assertThat(normalize("WHERE name = 'foo'"))
                .isEqualTo("WHERE name = ?");
    }

    @Test
    void escapedQuoteInsideStringLiteralHandled() {
        assertThat(normalize("WHERE name = 'O''Brien'"))
                .isEqualTo("WHERE name = ?");
    }

    @Test
    void multipleLiteralsRedactedIndependently() {
        assertThat(normalize("WHERE id = 47 AND name = 'foo' AND price < 19.99"))
                .isEqualTo("WHERE id = ? AND name = ? AND price < ?");
    }

    @Test
    void decimalLiteralRedacted() {
        assertThat(normalize("WHERE price = 19.99"))
                .isEqualTo("WHERE price = ?");
    }

    @Test
    void scientificNotationRedacted() {
        assertThat(normalize("WHERE x = 1.5e10"))
                .isEqualTo("WHERE x = ?");
        assertThat(normalize("WHERE x = 2.3e-5"))
                .isEqualTo("WHERE x = ?");
        assertThat(normalize("WHERE x = 7E+3"))
                .isEqualTo("WHERE x = ?");
    }

    @Test
    void hexLiteralRedacted() {
        assertThat(normalize("WHERE flags = 0xFF"))
                .isEqualTo("WHERE flags = ?");
        assertThat(normalize("WHERE flags = 0Xabc123"))
                .isEqualTo("WHERE flags = ?");
    }

    @Test
    void leadingSignPreservedAsOperator() {
        // The template keeps the operator shape; only the digit run is collapsed.
        assertThat(normalize("WHERE balance = -100"))
                .isEqualTo("WHERE balance = -?");
        assertThat(normalize("WHERE balance = +50"))
                .isEqualTo("WHERE balance = +?");
    }

    @Test
    void quotedIdentifiersPreserved() {
        assertThat(normalize("SELECT \"first name\" FROM users"))
                .isEqualTo("SELECT \"first name\" FROM users");
        assertThat(normalize("SELECT `user` FROM t WHERE `id` = 1"))
                .isEqualTo("SELECT `user` FROM t WHERE `id` = ?");
    }

    @Test
    void lineCommentsPreserved() {
        assertThat(normalize("SELECT 1 -- comment with 'strings' and 99 digits\nFROM t"))
                .isEqualTo("SELECT ? -- comment with 'strings' and 99 digits\nFROM t");
    }

    @Test
    void blockCommentsPreserved() {
        assertThat(normalize("SELECT /* x = 1 and 'foo' */ 2 FROM t"))
                .isEqualTo("SELECT /* x = 1 and 'foo' */ ? FROM t");
    }

    @Test
    void existingPlaceholdersUnchanged() {
        assertThat(normalize("WHERE id = ? AND name = ?"))
                .isEqualTo("WHERE id = ? AND name = ?");
    }

    @Test
    void stableAcrossDifferingLiteralValues() {
        // Grouping requirement: two statements that differ only by parameter
        // values must normalise to the same template.
        String a = "SELECT * FROM orders WHERE id = 47 AND name = 'alice'";
        String b = "SELECT * FROM orders WHERE id = 99 AND name = 'bob'";
        assertThat(normalize(a)).isEqualTo(normalize(b));
    }

    @Test
    void truncationMarkerAppendedForLongStatements() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("SELECT * FROM t; ");
        }
        String raw = sb.toString();
        String normalised = SqlTemplateNormalizer.normalize(raw, 64);
        assertThat(normalised).endsWith(" /* [truncated] */");
        assertThat(normalised.length()).isLessThanOrEqualTo(64 + " /* [truncated] */".length());
    }

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> SqlTemplateNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTinyMaxLength() {
        assertThatThrownBy(() -> SqlTemplateNormalizer.normalize("x", 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String normalize(String s) {
        return SqlTemplateNormalizer.normalize(s);
    }
}
