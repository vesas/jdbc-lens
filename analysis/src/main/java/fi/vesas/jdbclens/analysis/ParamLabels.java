package fi.vesas.jdbclens.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort column-label lookup for the '?' placeholders in a SQL
 * template. Used by {@link HtmlReport} to prefix captured parameter
 * values with the column name they were bound to, so that the reader
 * of the report doesn't have to count '?' markers in the template to
 * work out which value is which.
 *
 * <p>Runs once per redundant-queries finding at report-render time —
 * not on the capture hot path, so there is no overhead budget.
 *
 * <p>The walker is deliberately conservative: slots that cannot be
 * resolved are simply absent from the returned map. A missing label
 * is harmless (the report falls back to {@code [n] value}); a wrong
 * label would mislead the reader. Real SQL parsing is out of scope;
 * this covers common single-table predicates, subqueries, INs, and
 * function-wrapped placeholders like {@code TO_DATE(?, 'fmt')}.
 */
public final class ParamLabels {

    private ParamLabels() {
    }

    /**
     * Keywords that can sit between the column reference and the
     * placeholder feeding it. The backward walk steps over these
     * while looking for the label.
     */
    private static final Set<String> BRIDGE_KEYWORDS = Set.of(
            "IN", "LIKE", "BETWEEN", "AND", "OR", "NOT", "SET");

    /**
     * Keywords that terminate the backward walk with "unknown": a
     * placeholder sitting in a {@code SELECT} list or {@code VALUES}
     * clause isn't bound to one specific column.
     */
    private static final Set<String> STOP_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "VALUES", "GROUP", "ORDER",
            "HAVING", "BY", "LIMIT", "OFFSET", "FETCH", "UNION",
            "INTERSECT", "EXCEPT", "ON", "JOIN", "LEFT", "RIGHT",
            "INNER", "OUTER", "FULL", "CROSS", "AS", "WITH", "CASE",
            "WHEN", "THEN", "ELSE", "END", "DISTINCT", "INSERT",
            "INTO", "UPDATE", "DELETE", "RETURNING");

    /**
     * Functions that wrap a placeholder but preserve the column
     * meaning around them: {@code B.ALKUPVM <= TO_DATE(?, 'fmt')}
     * should label the placeholder "B.ALKUPVM", not "TO_DATE" and
     * not the format literal.
     */
    private static final Set<String> TRANSPARENT_FUNCTIONS = Set.of(
            "TO_DATE", "TO_CHAR", "TO_NUMBER", "TO_TIMESTAMP",
            "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM",
            "CAST", "COALESCE", "NVL", "NULLIF",
            "DATE", "TIMESTAMP", "SUBSTR", "SUBSTRING");

    /**
     * Returns a map from 1-based parameter index to a best-effort
     * column label. Positions the walker cannot resolve are absent;
     * null/empty input yields an empty map.
     */
    public static Map<Integer, String> labelsFor(String sql) {
        if (sql == null || sql.isEmpty()) {
            return Map.of();
        }
        List<Token> tokens = tokenize(sql);
        if (tokens.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> labels = new LinkedHashMap<>();
        // Each open paren pushes a frame: the upper-cased function
        // name if the paren was opened by `IDENT(`, otherwise null.
        ArrayList<String> funcStack = new ArrayList<>();
        int paramIdx = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.kind) {
                case LPAREN -> {
                    String fn = null;
                    if (i > 0 && tokens.get(i - 1).kind == Kind.IDENT) {
                        String prev = tokens.get(i - 1).text.toUpperCase(Locale.ROOT);
                        // Keywords like IN, VALUES, SET shouldn't be
                        // treated as function-call openers — they're
                        // syntax, not transparent wrappers.
                        if (!BRIDGE_KEYWORDS.contains(prev) && !STOP_KEYWORDS.contains(prev)) {
                            fn = prev;
                        }
                    }
                    funcStack.add(fn);
                }
                case RPAREN -> {
                    if (!funcStack.isEmpty()) {
                        funcStack.remove(funcStack.size() - 1);
                    }
                }
                case QMARK -> {
                    paramIdx++;
                    int anchor = i;
                    if (!funcStack.isEmpty()) {
                        String fn = funcStack.get(funcStack.size() - 1);
                        if (fn != null && TRANSPARENT_FUNCTIONS.contains(fn)) {
                            anchor = stepPastEnclosingCall(tokens, i);
                        }
                    }
                    String label = resolveLabel(tokens, anchor);
                    if (label != null) {
                        labels.putIfAbsent(paramIdx, label);
                    }
                }
                default -> {
                }
            }
        }
        return Collections.unmodifiableMap(labels);
    }

    /**
     * Returns the token index of the function identifier whose
     * enclosing LPAREN matches the deepest unmatched open paren
     * before {@code fromInclusive}. Used to unwrap transparent
     * function calls like {@code TO_DATE(?, 'fmt')} so the backward
     * walk can find the column on the other side of the comparison.
     */
    private static int stepPastEnclosingCall(List<Token> tokens, int fromInclusive) {
        int depth = 0;
        int j = fromInclusive - 1;
        while (j >= 0) {
            Token tk = tokens.get(j);
            if (tk.kind == Kind.RPAREN) {
                depth++;
            } else if (tk.kind == Kind.LPAREN) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            j--;
        }
        // j now sits on the LPAREN; j-1 is the function identifier.
        return j > 0 ? j - 1 : fromInclusive;
    }

    private static String resolveLabel(List<Token> tokens, int anchor) {
        int j = anchor - 1;
        while (j >= 0) {
            Token t = tokens.get(j);
            switch (t.kind) {
                case IDENT -> {
                    String up = t.text.toUpperCase(Locale.ROOT);
                    if (BRIDGE_KEYWORDS.contains(up)) {
                        j--;
                        continue;
                    }
                    if (STOP_KEYWORDS.contains(up)) {
                        return null;
                    }
                    return t.text;
                }
                case OPERATOR, COMMA, LPAREN, QMARK, NUMBER, STRING -> j--;
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    // ---------- Tokeniser ----------

    private enum Kind {
        IDENT, NUMBER, STRING, OPERATOR, COMMA, LPAREN, RPAREN, QMARK, OTHER
    }

    private record Token(Kind kind, String text) {
    }

    private static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        // Doubled single quote is the SQL escape for a
                        // literal quote inside the string.
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                out.add(new Token(Kind.STRING, ""));
                continue;
            }
            if (c == '"') {
                int start = i;
                i++;
                while (i < n && sql.charAt(i) != '"') {
                    i++;
                }
                if (i < n) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, sql.substring(start, i)));
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i + 1 < n) {
                    i += 2;
                }
                continue;
            }
            if (c == '?') {
                out.add(new Token(Kind.QMARK, "?"));
                i++;
                continue;
            }
            if (c == '(') {
                out.add(new Token(Kind.LPAREN, "("));
                i++;
                continue;
            }
            if (c == ')') {
                out.add(new Token(Kind.RPAREN, ")"));
                i++;
                continue;
            }
            if (c == ',') {
                out.add(new Token(Kind.COMMA, ","));
                i++;
                continue;
            }
            if (isIdentStart(c)) {
                int start = i;
                i++;
                while (i < n && isIdentPart(sql.charAt(i))) {
                    i++;
                }
                // Dotted qualifier: A.PS or schema.table.col. Glued
                // together so the whole chain is a single identifier
                // token; the label reader wants "A.PS", not "A".
                while (i < n && sql.charAt(i) == '.'
                        && i + 1 < n && isIdentStart(sql.charAt(i + 1))) {
                    i++;
                    while (i < n && isIdentPart(sql.charAt(i))) {
                        i++;
                    }
                }
                out.add(new Token(Kind.IDENT, sql.substring(start, i)));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Kind.NUMBER, sql.substring(start, i)));
                continue;
            }
            if (isOperatorChar(c)) {
                int start = i;
                while (i < n && isOperatorChar(sql.charAt(i))) {
                    i++;
                }
                out.add(new Token(Kind.OPERATOR, sql.substring(start, i)));
                continue;
            }
            out.add(new Token(Kind.OTHER, String.valueOf(c)));
            i++;
        }
        return out;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isOperatorChar(char c) {
        return c == '=' || c == '<' || c == '>' || c == '!'
                || c == '+' || c == '-' || c == '*' || c == '/'
                || c == '%' || c == '|';
    }
}
