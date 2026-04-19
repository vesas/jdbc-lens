package fi.vesas.jdbcprof.capture;

/**
 * Collapses literal values in ad-hoc {@code Statement} SQL into {@code ?}
 * placeholders so queries that differ only by parameter value group
 * together (spec §3, §5.5).
 *
 * <p>Scope is intentionally narrow — this is not a SQL parser. The
 * goal is a deterministic template so {@code WHERE id = 47} and
 * {@code WHERE id = 99} produce the same grouping key. The state
 * machine recognises:
 *
 * <ul>
 *   <li>Single-quoted string literals (with the standard {@code ''}
 *       escape) — redacted to {@code ?}.</li>
 *   <li>Numeric literals: integer, decimal, scientific (e/E with
 *       optional sign), and hex ({@code 0x...}) — redacted to {@code ?}.</li>
 *   <li>Double-quoted and backtick-quoted identifiers — preserved
 *       verbatim.</li>
 *   <li>{@code --} line comments and {@code /* ... *\/} block comments
 *       — preserved verbatim.</li>
 *   <li>Existing {@code ?} placeholders — preserved.</li>
 * </ul>
 *
 * <p>Statements longer than the configured cap are truncated with a
 * marker so pathologically long dynamic SQL cannot blow up the intern
 * table (spec §5.3).
 */
public final class SqlTemplateNormalizer {

    /** Spec §5.3 prohibits unbounded work; 2048 chars is ample for any real query. */
    public static final int DEFAULT_MAX_LENGTH = 2048;

    private static final String TRUNCATION_MARKER = " /* [truncated] */";

    private SqlTemplateNormalizer() {
    }

    public static String normalize(String raw) {
        return normalize(raw, DEFAULT_MAX_LENGTH);
    }

    public static String normalize(String raw, int maxLength) {
        if (raw == null) {
            throw new IllegalArgumentException("raw must not be null");
        }
        if (maxLength < 16) {
            throw new IllegalArgumentException("maxLength must be >= 16, got " + maxLength);
        }

        int scanEnd = Math.min(raw.length(), maxLength);
        StringBuilder out = new StringBuilder(scanEnd);

        int i = 0;
        while (i < scanEnd) {
            char c = raw.charAt(i);

            // Line comment: -- ... to end-of-line. Preserve.
            if (c == '-' && i + 1 < scanEnd && raw.charAt(i + 1) == '-') {
                while (i < scanEnd && raw.charAt(i) != '\n') {
                    out.append(raw.charAt(i));
                    i++;
                }
                continue;
            }

            // Block comment: /* ... */. Preserve.
            if (c == '/' && i + 1 < scanEnd && raw.charAt(i + 1) == '*') {
                out.append(raw, i, i + 2);
                i += 2;
                while (i < scanEnd) {
                    char cc = raw.charAt(i);
                    out.append(cc);
                    i++;
                    if (cc == '*' && i < scanEnd && raw.charAt(i) == '/') {
                        out.append('/');
                        i++;
                        break;
                    }
                }
                continue;
            }

            // Quoted identifiers: "..." (ANSI) and `...` (MySQL). Preserve.
            if (c == '"' || c == '`') {
                char quote = c;
                out.append(c);
                i++;
                while (i < scanEnd) {
                    char cc = raw.charAt(i);
                    out.append(cc);
                    i++;
                    if (cc == quote) {
                        break;
                    }
                }
                continue;
            }

            // String literal: '...'. Redact, handling '' escape.
            if (c == '\'') {
                out.append('?');
                i++;
                while (i < scanEnd) {
                    char cc = raw.charAt(i);
                    if (cc == '\'') {
                        if (i + 1 < scanEnd && raw.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                continue;
            }

            // Numeric literal: must start with a digit, or 0x hex. A leading
            // +/- is left in place (it is an operator in context, and keeping
            // it in the template preserves the shape of the expression).
            if (isDigit(c)) {
                i = consumeNumber(raw, i, scanEnd);
                out.append('?');
                continue;
            }

            out.append(c);
            i++;
        }

        if (raw.length() > maxLength) {
            out.append(TRUNCATION_MARKER);
        }
        return out.toString();
    }

    private static int consumeNumber(String raw, int start, int end) {
        int i = start;
        // Hex: 0x / 0X prefix, then hex digits.
        if (i + 1 < end && raw.charAt(i) == '0'
                && (raw.charAt(i + 1) == 'x' || raw.charAt(i + 1) == 'X')) {
            i += 2;
            while (i < end && isHexDigit(raw.charAt(i))) {
                i++;
            }
            return i;
        }
        // Integer / decimal part.
        while (i < end && isDigit(raw.charAt(i))) {
            i++;
        }
        if (i < end && raw.charAt(i) == '.') {
            i++;
            while (i < end && isDigit(raw.charAt(i))) {
                i++;
            }
        }
        // Scientific: e[+-]?digits
        if (i < end && (raw.charAt(i) == 'e' || raw.charAt(i) == 'E')) {
            int save = i;
            i++;
            if (i < end && (raw.charAt(i) == '+' || raw.charAt(i) == '-')) {
                i++;
            }
            if (i < end && isDigit(raw.charAt(i))) {
                while (i < end && isDigit(raw.charAt(i))) {
                    i++;
                }
            } else {
                // Not actually scientific notation; back out.
                i = save;
            }
        }
        return i;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
