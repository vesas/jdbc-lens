package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the "emulated cursor" pair a COBOL-to-Java transpiler emits
 * when {@code READ NEXT} against a sequential file has to survive the
 * move to SQL (spec §8.3 extension).
 *
 * <p>The signature is always two templates issued from the same outer
 * method:
 *
 * <ul>
 *   <li><strong>Walk</strong>:
 *       {@code SELECT MIN(key) FROM T WHERE ... key > ?} (or the
 *       symmetric {@code MAX(key) ... key < ?}). On each iteration the
 *       parameter is the key returned the previous iteration; the
 *       loop stops when the result is {@code NULL}. This is
 *       emulating a sequential scan.</li>
 *   <li><strong>Fetch</strong>: {@code SELECT ... FROM T WHERE key = ?}
 *       against the same table, using the value the walk just
 *       returned. This is emulating {@code ResultSet.getXxx}.</li>
 * </ul>
 *
 * <p>A generic {@link N1Detector} correctly flags both templates as
 * N+1s from the same loop, but that label suggests the usual ORM
 * lazy-load fix (batch / join the inner query). The emulated-cursor
 * signature has a different fix: replace the <em>pair</em> with a
 * single scrolling {@code ResultSet} or a set-oriented
 * {@code GROUP BY} — the transpiler lost set semantics, not batching.
 *
 * <p>Heuristic, with no access to event return values: matching relies
 * on template shape plus co-attribution to the same outer application
 * frame. If two pairs co-exist and the inner pair's frames still
 * contain the outer pair's method, the inner pair is labelled
 * {@code nested} — the COBOL {@code PERFORM UNTIL} inside
 * {@code PERFORM UNTIL} case that is almost always a candidate for a
 * single {@code JOIN ... GROUP BY}.
 *
 * <p>Pure offline code; no hot-path impact.
 */
public final class EmulatedCursorDetector {

    /** A walk template must have executed at least this many times
     *  before we flag it. Below that the pattern is indistinguishable
     *  from one-off lookups. */
    public static final int DEFAULT_MIN_WALKS = 5;

    /** The paired fetch must have executed at least this fraction of
     *  the walk's executions. The walk usually runs {@code n + 1}
     *  times to detect end-of-data, so perfect co-execution is
     *  roughly {@code n / (n+1)}; the floor is generous to allow
     *  occasional misses (e.g. the fetch bailed early for a row). */
    public static final double DEFAULT_MIN_PAIR_RATIO = 0.5;

    // MIN(col) ... WHERE ... col > ?   or   MAX(col) ... WHERE ... col < ?
    // - Case-insensitive, DOTALL so the BETWEEN FROM/WHERE can span lines.
    // - The aggregated column (group 2) must match the WHERE column
    //   (group 4) — we re-check that in code, not in the regex.
    // - The FROM target (group 3) is the walked table.
    // - The comparison operator (group 5) is captured only so we can
    //   sanity-check consistency with MIN/MAX downstream.
    private static final Pattern WALK_PATTERN = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(MIN|MAX)\\s*\\(\\s*([\\w\"]+)\\s*\\)"
                    + "\\s+FROM\\s+([\\w\"]+)\\b.*?"
                    + "\\bWHERE\\b.*?\\b([\\w\"]+)\\s*([<>])\\s*\\?");

    private final int minWalks;
    private final double minPairRatio;

    public EmulatedCursorDetector() {
        this(DEFAULT_MIN_WALKS, DEFAULT_MIN_PAIR_RATIO);
    }

    public EmulatedCursorDetector(int minWalks, double minPairRatio) {
        this.minWalks = minWalks;
        this.minPairRatio = minPairRatio;
    }

    /**
     * The caller must feed the same execute-only aggregator used by
     * {@link N1Detector} — PREPARE / NEXT / CLOSE events would dilute
     * counts and split each template across many stacks, hiding the
     * walk/fetch co-execution the detector relies on.
     */
    public List<EmulatedCursorFinding> detect(
            Aggregator agg,
            Map<Integer, String> sqls,
            Map<Integer, StackFrameSnapshot[]> stacks) {

        // 1) Classify each (stack, sqlId) pair by template shape.
        List<PairObs> walks = new ArrayList<>();
        List<PairObs> fetches = new ArrayList<>();
        for (var entry : agg.topPairs(Integer.MAX_VALUE)) {
            Aggregator.PairKey k = entry.getKey();
            if (k.sqlId() < 0) {
                continue;
            }
            String sql = sqls.get(k.sqlId());
            if (sql == null) {
                continue;
            }
            StackFrameSnapshot[] frames = stacks.get(k.stackTraceId());
            if (frames == null) {
                continue;
            }
            StackFrameSnapshot site = Attribution.callSite(frames);
            StackFrameSnapshot ancestor = Attribution.ancestorFrame(frames, site);
            if (ancestor == null) {
                // No outer application frame — can't attribute the
                // loop, so co-attribution would be meaningless.
                continue;
            }

            WalkInfo walk = classifyWalk(sql);
            if (walk != null) {
                walks.add(new PairObs(
                        k.sqlId(), sql, site, ancestor, frames,
                        entry.getValue().count(),
                        entry.getValue().totalDurationNanos(),
                        walk.table(), walk.keyColumn()));
                continue;
            }
            FetchInfo fetch = classifyFetch(sql);
            if (fetch != null) {
                fetches.add(new PairObs(
                        k.sqlId(), sql, site, ancestor, frames,
                        entry.getValue().count(),
                        entry.getValue().totalDurationNanos(),
                        fetch.table(), fetch.keyColumn()));
            }
        }

        // 2) Match each walk to its co-located fetch. Co-location =
        //    same application ancestor frame + same table + same key.
        List<MatchedPair> pairs = new ArrayList<>();
        for (PairObs walk : walks) {
            if (walk.count < minWalks) {
                continue;
            }
            PairObs bestFetch = null;
            for (PairObs fetch : fetches) {
                if (!walk.table.equals(fetch.table)) {
                    continue;
                }
                if (!walk.keyColumn.equals(fetch.keyColumn)) {
                    continue;
                }
                if (!sameFrame(walk.ancestor, fetch.ancestor)) {
                    continue;
                }
                if ((double) fetch.count / (double) walk.count < minPairRatio) {
                    continue;
                }
                // Prefer the fetch with the closest execution count to the
                // walk — it's almost certainly the paired READ NEXT target.
                if (bestFetch == null
                        || Math.abs(fetch.count - walk.count)
                                < Math.abs(bestFetch.count - walk.count)) {
                    bestFetch = fetch;
                }
            }
            if (bestFetch != null) {
                pairs.add(new MatchedPair(walk, bestFetch));
            }
        }

        // 3) Nested-cursor labelling. An inner pair's frames still
        //    contain the outer pair's ancestor (the outer method that
        //    ran the outer walk/fetch and then descended). For each
        //    pair, scan every other pair's ancestor against this
        //    pair's walk frames; the first hit tags it as nested.
        List<EmulatedCursorFinding> findings = new ArrayList<>(pairs.size());
        for (MatchedPair inner : pairs) {
            StackFrameSnapshot outerAncestor = null;
            for (MatchedPair outer : pairs) {
                if (outer == inner) {
                    continue;
                }
                if (sameFrame(inner.walk.ancestor, outer.walk.ancestor)) {
                    // Same loop method — they're siblings, not nested.
                    continue;
                }
                if (framesContain(inner.walk.frames, outer.walk.ancestor)) {
                    outerAncestor = outer.walk.ancestor;
                    break;
                }
            }
            findings.add(toFinding(inner, outerAncestor));
        }

        findings.sort(Comparator
                .comparingLong(EmulatedCursorFinding::totalDurationNanos).reversed());
        return findings;
    }

    private static EmulatedCursorFinding toFinding(
            MatchedPair pair, StackFrameSnapshot outerAncestor) {
        return new EmulatedCursorFinding(
                pair.walk.sqlId, pair.walk.sql, pair.walk.site,
                pair.fetch.sqlId, pair.fetch.sql, pair.fetch.site,
                pair.walk.table, pair.walk.keyColumn,
                pair.walk.ancestor,
                pair.walk.count, pair.fetch.count,
                pair.walk.totalDurationNanos + pair.fetch.totalDurationNanos,
                outerAncestor != null, outerAncestor);
    }

    private static boolean framesContain(StackFrameSnapshot[] frames,
                                         StackFrameSnapshot target) {
        if (frames == null || target == null) {
            return false;
        }
        for (StackFrameSnapshot f : frames) {
            if (sameFrame(f, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameFrame(StackFrameSnapshot a, StackFrameSnapshot b) {
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.className(), b.className())
                && Objects.equals(a.methodName(), b.methodName());
    }

    private static WalkInfo classifyWalk(String sql) {
        Matcher m = WALK_PATTERN.matcher(sql);
        if (!m.find()) {
            return null;
        }
        String agg = m.group(1).toUpperCase(Locale.ROOT);
        String aggCol = stripQuotes(m.group(2)).toLowerCase(Locale.ROOT);
        String table = stripQuotes(m.group(3)).toLowerCase(Locale.ROOT);
        String whereCol = stripQuotes(m.group(4)).toLowerCase(Locale.ROOT);
        String op = m.group(5);
        if (!aggCol.equals(whereCol)) {
            return null;
        }
        // A forward walk pairs MIN with >, a reverse walk pairs MAX
        // with <. The opposite combinations (MIN with <, MAX with >)
        // would just re-read the same row and aren't READ NEXT shapes.
        boolean forward = "MIN".equals(agg) && ">".equals(op);
        boolean reverse = "MAX".equals(agg) && "<".equals(op);
        if (!forward && !reverse) {
            return null;
        }
        return new WalkInfo(table, aggCol);
    }

    private static FetchInfo classifyFetch(String sql) {
        TemplateShape shape = TemplateShape.of(sql);
        if (shape == null || shape.kind() != TemplateShape.Kind.SELECT) {
            return null;
        }
        Map<Integer, String> cols = shape.columnsByParamIdx();
        if (cols.size() != 1) {
            // Multi-key WHEREs (composite PK or extra filters) fall
            // outside the READ-NEXT emulation shape — leave them to
            // the generic N+1 detector.
            return null;
        }
        String keyCol = cols.values().iterator().next();
        return new FetchInfo(shape.table(), keyCol);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private record WalkInfo(String table, String keyColumn) {
    }

    private record FetchInfo(String table, String keyColumn) {
    }

    private record MatchedPair(PairObs walk, PairObs fetch) {
    }

    /**
     * Intermediate observation for a single (stack, sqlId) pair that
     * matched either the walk or the fetch shape. Kept flat (no
     * inheritance) so the matching loop stays readable; {@code table}
     * and {@code keyColumn} are the dimensions both kinds join on.
     */
    private static final class PairObs {
        final int sqlId;
        final String sql;
        final StackFrameSnapshot site;
        final StackFrameSnapshot ancestor;
        final StackFrameSnapshot[] frames;
        final long count;
        final long totalDurationNanos;
        final String table;
        final String keyColumn;

        PairObs(int sqlId, String sql,
                StackFrameSnapshot site, StackFrameSnapshot ancestor,
                StackFrameSnapshot[] frames,
                long count, long totalDurationNanos,
                String table, String keyColumn) {
            this.sqlId = sqlId;
            this.sql = sql;
            this.site = site;
            this.ancestor = ancestor;
            this.frames = frames;
            this.count = count;
            this.totalDurationNanos = totalDurationNanos;
            this.table = table;
            this.keyColumn = keyColumn;
        }
    }
}
