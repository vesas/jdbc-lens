package io.github.vesas.jdbcprof.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Light-touch parse of a SQL template extracting the primary table
 * and the parameter index → column name map for the WHERE clause.
 *
 * <p>Feeds the {@link EntityAccessAudit}. Deliberately heuristic:
 *
 * <ul>
 *   <li>Single-table {@code SELECT}/{@code UPDATE}/{@code DELETE}
 *       with a {@code WHERE col = ?} (or several ANDed) are
 *       recognised.</li>
 *   <li>Multi-table joins, subqueries, and anything we can't parse
 *       safely return {@code null} — the audit then skips those
 *       templates rather than mis-attribute.</li>
 * </ul>
 *
 * <p>The column names come from the SQL text, not from the
 * database schema, so they inherit whatever casing / quoting the
 * template used. Lower-cased here for stable grouping.
 */
public record TemplateShape(String table, Map<Integer, String> columnsByParamIdx) {

    private static final Pattern TRAILING_COL_EQ =
            Pattern.compile("([\\w\"]+)\\s*=\\s*$");

    public TemplateShape {
        // Preserve insertion order so param indices iterate in the
        // order they appear in the SQL — stable report output.
        columnsByParamIdx = Collections.unmodifiableMap(
                new LinkedHashMap<>(columnsByParamIdx));
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

        // Multi-table joins and subqueries would need a real parser;
        // skip them rather than guess.
        if (containsKeyword(upper, "JOIN")) {
            return null;
        }
        if (upper.contains("(SELECT ")) {
            return null;
        }

        String table = findTable(trimmed, upper);
        if (table == null) {
            return null;
        }

        int whereIdx = findKeyword(upper, "WHERE", 0);
        if (whereIdx < 0) {
            return null;
        }
        int whereEnd = findWhereEnd(upper, whereIdx + 5);

        int prefixParams = countQuestionMarks(trimmed, 0, whereIdx);
        String whereClause = trimmed.substring(whereIdx, whereEnd);

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

        if (cols.isEmpty()) {
            return null;
        }
        return new TemplateShape(table.toLowerCase(Locale.ROOT), cols);
    }

    private static String findTable(String sql, String upper) {
        Matcher m;
        if (upper.startsWith("SELECT")) {
            m = Pattern.compile("(?is)\\bFROM\\s+([\\w\"]+)").matcher(sql);
        } else if (upper.startsWith("UPDATE")) {
            m = Pattern.compile("(?is)\\bUPDATE\\s+([\\w\"]+)").matcher(sql);
        } else if (upper.startsWith("DELETE")) {
            m = Pattern.compile("(?is)\\bDELETE\\s+FROM\\s+([\\w\"]+)").matcher(sql);
        } else {
            return null;
        }
        return m.find() ? stripQuotes(m.group(1)) : null;
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
