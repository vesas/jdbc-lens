package fi.vesas.jdbclens.analysis.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceScannerTest {

    @Test
    void capturesSelectAndUpdateLiteralsInSameFile(@TempDir Path tmp) throws Exception {
        Path f = write(tmp, "Dao.java",
                """
                package x;
                class Dao {
                    String a = "SELECT id, name FROM customers WHERE id = ?";
                    String b = "UPDATE customers SET name = ? WHERE id = ?";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(2);
        assertThat(sites.get(0).readTables()).containsExactly("customers");
        assertThat(sites.get(0).writeTarget()).isNull();
        assertThat(sites.get(0).kind()).isEqualTo("SELECT");
        assertThat(sites.get(0).line()).isEqualTo(3);
        assertThat(sites.get(1).writeTarget()).isEqualTo("customers");
        assertThat(sites.get(1).kind()).isEqualTo("UPDATE");
        assertThat(sites.get(1).line()).isEqualTo(4);
    }

    @Test
    void ignoresNonSqlLiterals(@TempDir Path tmp) throws Exception {
        Path f = write(tmp, "X.java",
                """
                class X {
                    String greeting = "hello world";
                    String message = "Error: could not load";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).isEmpty();
    }

    @Test
    void blockCommentContainingSqlIsSkipped(@TempDir Path tmp) throws Exception {
        Path f = write(tmp, "C.java",
                """
                class C {
                    /* SELECT * FROM hidden_table WHERE id = ? */
                    String real = "SELECT id FROM visible WHERE id = ?";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).readTables()).containsExactly("visible");
    }

    @Test
    void lineCommentContainingSqlIsSkipped(@TempDir Path tmp) throws Exception {
        Path f = write(tmp, "L.java",
                """
                class L {
                    // SELECT * FROM commented_out WHERE id = ?
                    String real = "DELETE FROM actual_table WHERE id = ?";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).writeTarget()).isEqualTo("actual_table");
        assertThat(sites.get(0).kind()).isEqualTo("DELETE");
    }

    @Test
    void stringContainingSlashSlashDoesNotStartComment(@TempDir Path tmp) throws Exception {
        Path f = write(tmp, "U.java",
                """
                class U {
                    String url = "http://example.com";
                    String q = "SELECT id FROM urls WHERE id = ?";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).readTables()).containsExactly("urls");
    }

    @Test
    void charLiteralContainingQuoteDoesNotConfuseLexer(@TempDir Path tmp) throws Exception {
        Path f = write(tmp, "Q.java",
                """
                class Q {
                    char dq = '\\"';
                    String q = "SELECT id FROM quoted WHERE id = ?";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).readTables()).containsExactly("quoted");
    }

    @Test
    void textBlockSqlIsCaptured(@TempDir Path tmp) throws Exception {
        // Use concatenation to avoid nested text blocks in the source.
        String source = "class T {\n"
                + "    String q = \"\"\"\n"
                + "        SELECT id, name\n"
                + "          FROM customers\n"
                + "         WHERE active = ?\n"
                + "        \"\"\";\n"
                + "}\n";
        Path f = tmp.resolve("T.java");
        Files.writeString(f, source, StandardCharsets.UTF_8);

        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).readTables()).containsExactly("customers");
        assertThat(sites.get(0).kind()).isEqualTo("SELECT");
    }

    @Test
    void escapedQuoteInsideStringDoesNotTerminateEarly(@TempDir Path tmp) throws Exception {
        // If the lexer wrongly terminated the first literal at the
        // escaped \", the tail " SELECT id FROM real WHERE id=?"
        // would surface as its own site containing table "real".
        // Correct handling yields exactly one site \u2014 the second
        // literal with table "other".
        Path f = write(tmp, "E.java",
                """
                class E {
                    String a = "prefix \\"noise\\" SELECT id FROM real WHERE id=?";
                    String b = "SELECT id FROM other WHERE id=?";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).readTables()).containsExactly("other");
    }

    @Test
    void insertLiteralProducesWriteTarget(@TempDir Path tmp) throws Exception {
        Path f = write(tmp, "I.java",
                """
                class I {
                    String sql = "INSERT INTO events (id, kind) VALUES (?, ?)";
                }
                """);
        List<SourceSqlSite> sites = new ArrayList<>();
        JavaSourceScanner.scanFile(f, sites);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).writeTarget()).isEqualTo("events");
        assertThat(sites.get(0).kind()).isEqualTo("INSERT");
    }

    @Test
    void walksDirectoryRecursively(@TempDir Path tmp) throws Exception {
        Path nested = tmp.resolve("pkg/sub");
        Files.createDirectories(nested);
        write(nested, "Deep.java", """
                class Deep {
                    String q = "SELECT id FROM nested_table WHERE id = ?";
                }
                """);
        List<SourceSqlSite> sites = JavaSourceScanner.scan(List.of(tmp));
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).readTables()).containsExactly("nested_table");
    }

    @Test
    void nonExistentRootIsIgnored(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");
        assertThat(JavaSourceScanner.scan(List.of(missing))).isEmpty();
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }
}
