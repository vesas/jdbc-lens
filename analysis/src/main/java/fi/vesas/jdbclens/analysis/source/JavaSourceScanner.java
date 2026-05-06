package fi.vesas.jdbclens.analysis.source;

import fi.vesas.jdbclens.analysis.TemplateShape;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Walks source roots, finds every Java string literal (including
 * text blocks) that looks like SQL, and classifies it with the same
 * {@link TemplateShape} parser used by the runtime audit. The output
 * feeds {@link SourceScanResult}.
 *
 * <p>Lexer is hand-rolled and deliberately small: it knows about
 * line / block comments, char literals, string literals with simple
 * escape handling, and Java 15 text blocks. Anything more exotic
 * (Unicode escapes that produce a quote, preprocessor-style tricks)
 * is out of scope for v1 — the goal is finding the copybook-
 * duplicated SQL that dominates legacy codebases, not parsing every
 * pathological string in the grammar.
 */
public final class JavaSourceScanner {

    private JavaSourceScanner() {
    }

    /** SQL-keyword prefixes that make a literal worth classifying. */
    private static final String[] SQL_PREFIXES =
            {"SELECT", "UPDATE", "DELETE", "INSERT", "WITH"};

    public static List<SourceSqlSite> scan(List<Path> roots) {
        List<SourceSqlSite> out = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().endsWith(".java")) {
                            scanFile(file, out);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("walking " + root, e);
            }
        }
        return out;
    }

    static void scanFile(Path file, List<SourceSqlSite> out) {
        String src;
        try {
            src = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Unreadable file — skip, don't fail the whole scan.
            return;
        }
        Lexer lx = new Lexer(src);
        while (lx.advance()) {
            String literal = lx.currentLiteral();
            if (literal == null) {
                continue;
            }
            if (!looksLikeSql(literal)) {
                continue;
            }
            Set<String> readTables = TemplateShape.referencedTables(literal);
            String writeTarget = TemplateShape.writeTarget(literal);
            if (readTables.isEmpty() && writeTarget == null) {
                continue;
            }
            out.add(new SourceSqlSite(
                    file,
                    lx.currentLine(),
                    readTables,
                    writeTarget,
                    classifyKind(literal),
                    snippet(literal)));
        }
    }

    private static boolean looksLikeSql(String s) {
        String trimmed = s.stripLeading();
        if (trimmed.length() < 6) {
            return false;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (String prefix : SQL_PREFIXES) {
            if (upper.startsWith(prefix)
                    && (upper.length() == prefix.length()
                        || !Character.isLetterOrDigit(upper.charAt(prefix.length())))) {
                return true;
            }
        }
        return false;
    }

    private static String classifyKind(String sql) {
        String upper = sql.stripLeading().toUpperCase(Locale.ROOT);
        if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
            return "SELECT";
        }
        if (upper.startsWith("UPDATE")) {
            return "UPDATE";
        }
        if (upper.startsWith("DELETE")) {
            return "DELETE";
        }
        if (upper.startsWith("INSERT")) {
            return "INSERT";
        }
        return "OTHER";
    }

    private static String snippet(String sql) {
        String collapsed = sql.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 140
                ? collapsed
                : collapsed.substring(0, 137) + "...";
    }

    /**
     * Minimal Java source lexer. Yields one string literal (or text
     * block) per {@link #advance} call that returns {@code true} with
     * {@link #currentLiteral()} non-null; skips over comments, char
     * literals, and non-literal code silently.
     */
    static final class Lexer {
        private final String src;
        private int pos;
        private int line = 1;
        private String literal;
        private int literalLine;

        Lexer(String src) {
            this.src = src;
        }

        String currentLiteral() {
            return literal;
        }

        int currentLine() {
            return literalLine;
        }

        /**
         * Advances to the next token of interest. Returns {@code true}
         * if another literal was consumed (which may or may not be a
         * SQL candidate — caller filters); {@code false} at EOF.
         */
        boolean advance() {
            literal = null;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                // Line comment.
                if (c == '/' && peek(1) == '/') {
                    skipLineComment();
                    continue;
                }
                // Block comment.
                if (c == '/' && peek(1) == '*') {
                    skipBlockComment();
                    continue;
                }
                // Text block (Java 15+). Must check before regular string.
                if (c == '"' && peek(1) == '"' && peek(2) == '"') {
                    int startLine = line;
                    String text = readTextBlock();
                    literal = text;
                    literalLine = startLine;
                    return true;
                }
                // String literal.
                if (c == '"') {
                    int startLine = line;
                    String s = readString();
                    literal = s;
                    literalLine = startLine;
                    return true;
                }
                // Char literal.
                if (c == '\'') {
                    skipCharLiteral();
                    continue;
                }
                if (c == '\n') {
                    line++;
                }
                pos++;
            }
            return false;
        }

        private char peek(int offset) {
            int idx = pos + offset;
            return idx < src.length() ? src.charAt(idx) : '\0';
        }

        private void skipLineComment() {
            pos += 2;
            while (pos < src.length() && src.charAt(pos) != '\n') {
                pos++;
            }
        }

        private void skipBlockComment() {
            pos += 2;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '*' && peek(1) == '/') {
                    pos += 2;
                    return;
                }
                if (c == '\n') {
                    line++;
                }
                pos++;
            }
        }

        private void skipCharLiteral() {
            pos++; // opening '
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\\') {
                    pos += 2;
                    continue;
                }
                if (c == '\'') {
                    pos++;
                    return;
                }
                if (c == '\n') {
                    line++;
                }
                pos++;
            }
        }

        private String readString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening "
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\\') {
                    char next = peek(1);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '\'' -> sb.append('\'');
                        default -> {
                            // Unrecognised \x — emit the char so
                            // SQL keywords that happened to be
                            // preceded by an escape stay visible.
                            if (next != '\0') {
                                sb.append(next);
                            }
                        }
                    }
                    pos += 2;
                    continue;
                }
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\n') {
                    line++;
                }
                sb.append(c);
                pos++;
            }
            return sb.toString();
        }

        private String readTextBlock() {
            StringBuilder sb = new StringBuilder();
            pos += 3; // opening """
            // Java's text-block spec requires a line terminator after
            // the opening """; we simply consume it if present so the
            // content starts cleanly.
            if (pos < src.length() && src.charAt(pos) == '\n') {
                line++;
                pos++;
            } else if (pos < src.length() && src.charAt(pos) == '\r') {
                pos++;
                if (pos < src.length() && src.charAt(pos) == '\n') {
                    line++;
                    pos++;
                }
            }
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '"' && peek(1) == '"' && peek(2) == '"') {
                    pos += 3;
                    return sb.toString();
                }
                if (c == '\\') {
                    char next = peek(1);
                    if (next == 'n') {
                        sb.append('\n');
                        pos += 2;
                        continue;
                    }
                    if (next == '"') {
                        sb.append('"');
                        pos += 2;
                        continue;
                    }
                    if (next == '\\') {
                        sb.append('\\');
                        pos += 2;
                        continue;
                    }
                    // Other escapes — skip the backslash and keep going.
                    pos++;
                    continue;
                }
                if (c == '\n') {
                    line++;
                }
                sb.append(c);
                pos++;
            }
            return sb.toString();
        }
    }
}
