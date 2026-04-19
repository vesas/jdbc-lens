package io.github.vesas.jdbcprof.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Light-touch parse of a SQL template: what kind of statement it is,
 * the primary table, the parameter index → column name map for the
 * WHERE clause, and (for UPDATEs) the list of columns in the SET
 * clause.
 *
 * <p>Feeds {@link EntityAccessAudit}, {@link OverWideUpdateDetector},
 * and {@link ReadThenWriteDetector}. Deliberately heuristic:
 *
 * <ul>
 *   <li>Single-table {@code SELECT}/{@code UPDATE}/{@code DELETE}
 *       with a {@code WHERE col = ?} (or several ANDed) are
 *       recognised.</li>
 *   <li>Multi-table joins, subqueries, and anything we can't parse
 *       safely return {@code null} — downstream analyses skip those
 *       templates rather than mis-attribute.</li>
 * </ul>
 *
 * <p>Column and table names come from the SQL text, not from the
 * database schema, so they inherit whatever casing / quoting the
 * template used. Lower-cased here for stable grouping.
 */
public record TemplateShape(
        Kind kind,
        String table,
        Map<Integer, String> columnsByParamIdx,
        List<String> setColumns) {

    public enum Kind { SELECT, UPDATE, DELETE }

    private static final Pattern TRAILING_COL_EQ =
            Pattern.compile("([\\w\"]+)\\s*=\\s*$");
    private static final Pattern LEADING_COL_EQ =
            Pattern.compile("^\\s*([\\w\"]+)\\s*=");

    public TemplateShape {
        columnsByParamIdx = Collections.unmodifiableMap(
                new LinkedHashMap<>(columnsByParamIdx));
        setColumns = List.copyOf(setColumns);
    }

    public static TemplateShape of(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);

        if (containsKeyword(upper, "JOIN") || upper.contains("(SELECT ")) {
            return null;
        }

        Kind kind = findKind(upper);
        if (kind == null) {
            return null;
        }
        String table = findTable(trimmed, upper, kind);
        if (table == null) {
            return null;
        }

        int whereIdx = findKeyword(upper, "WHERE", 0);
        if (whereIdx < 0) {
            return null;
        }
        int whereEnd = findWhereEnd(upper, whereIdx + 5);
        int prefixParams = countQuestionMarks(trimmed, 0, whereIdx);
        Map<Integer, String> cols = parseWhereColumns(
                trimmed.substring(whereIdx, whereEnd), prefixParams);
        if (cols.isEmpty()) {
            return null;
        }

        List<String> setCols = List.of();
        if (kind == Kind.UPDATE) {
            setCols = parseSetColumns(trimmed, upper, whereIdx);
        }

        return new TemplateShape(kind, table.toLowerCase(Locale.ROOT), cols, setCols);
    }

    private static Kind findKind(String upper) {
        if (upper.startsWith("SELECT")) {
            return Kind.SELECT;
        }
        if (upper.startsWith("UPDATE")) {
            return Kind.UPDATE;
        }
        if (upper.startsWith("DELETE")) {
            return Kind.DELETE;
        }
        return null;
    }

    private static String findTable(String sql, String upper, Kind kind) {
        Matcher m = switch (kind) {
            case SELECT -> Pattern.compile("(?is)\\bFROM\\s+([\\w\"]+)").matcher(sql);
            case UPDATE -> Pattern.compile("(?is)\\bUPDATE\\s+([\\w\"]+)").matcher(sql);
            case DELETE -> Pattern.compile("(?is)\\bDELETE\\s+FROM\\s+([\\w\"]+)").matcher(sql);
        };
        return m.find() ? stripQuotes(m.group(1)) : null;
    }

    private static Map<Integer, String> parseWhereColumns(String whereClause, int prefixParams) {
        Map<Integer, String> cols = new LinkedHashMap<>();
        int paramIdx = prefixParams;
        int cursor = 0;
        while (true) {
            int q = whereClause.indexOf('?', cursor);
            if (q < 0) {
                break;
            }
            paramIdx++;
            Matcher m = TRAILING_COL_EQ.matcher(whereClause.substring(0, q));
            if (m.find()) {
                String col = stripQuotes(m.group(1)).toLowerCase(Locale.ROOT);
                if (!col.isEmpty()) {
                    cols.put(paramIdx, col);
                }
            }
            cursor = q + 1;
        }
        return cols;
    }

    private static List<String> parseSetColumns(String sql, String upper, int whereIdx) {
        int setIdx = findKeyword(upper, "SET", 0);
        if (setIdx < 0 || setIdx >= whereIdx) {
            return List.of();
        }
        int setStart = setIdx + 3;
        String clause = sql.substring(setStart, whereIdx).trim();
        List<String> out = new ArrayList<>();
        for (String part : splitTopLevelCommas(clause)) {
            Matcher m = LEADING_COL_EQ.matcher(part);
            if (m.find()) {
                String col = stripQuotes(m.group(1)).toLowerCase(Locale.ROOT);
                if (!col.isEmpty()) {
                    out.add(col);
                }
            }
        }
        return out;
    }

    private static List<String> splitTopLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static int findKeyword(String upper, String keyword, int from) {
        int i = from;
        while (i < upper.length()) {
            int hit = upper.indexOf(keyword, i);
            if (hit < 0) {
                return -1;
            }
            boolean leftOk = hit == 0 || !isWordChar(upper.charAt(hit - 1));
            int after = hit + keyword.length();
            boolean rightOk = after == upper.length() || !isWordChar(upper.charAt(after));
            if (leftOk && rightOk) {
                return hit;
            }
            i = hit + 1;
        }
        return -1;
    }

    private static boolean containsKeyword(String upper, String keyword) {
        return findKeyword(upper, keyword, 0) >= 0;
    }

    private static int findWhereEnd(String upper, int from) {
        int end = upper.length();
        for (String stop : new String[]{
                "ORDER BY", "GROUP BY", "HAVING", "LIMIT", "OFFSET",
                "FETCH", "FOR UPDATE", "FOR SHARE", "UNION", "INTERSECT", "EXCEPT"}) {
            int i = findKeyword(upper, stop, from);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return end;
    }

    private static int countQuestionMarks(String s, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '?') {
                n++;
            }
        }
        return n;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
