package fi.vesas.jdbclens.comparison;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Emits a grouped bar chart as a self-contained SVG file.
 *
 * <p>Each scenario gets a group of three bars (NONE / JDBCPROF / P6SPY),
 * Y axis is nanoseconds per JDBC operation (lower is better). The SVG
 * is intentionally vanilla — no external CSS, no fonts referenced, no
 * scripts — so it embeds cleanly in GitHub-rendered Markdown.
 */
final class SvgRenderer {

    // Layout constants. Tuned once for a chart that reads cleanly at
    // README width (~720px on github.com) without needing zoom.
    // Canvas is generous: tall enough that the bar value labels never
    // crowd the legend at the top, wide enough that the per-bar
    // labels don't overlap each other at the bottom.
    private static final int WIDTH = 960;
    private static final int HEIGHT = 520;
    private static final int PAD_LEFT = 90;
    private static final int PAD_RIGHT = 40;
    private static final int PAD_TOP = 110;
    private static final int PAD_BOTTOM = 110;
    private static final int BAR_WIDTH = 60;
    private static final int BAR_GAP = 8;
    private static final int GROUP_GAP = 60;

    private static final Map<Mode, String> COLORS = Map.of(
            Mode.NONE, "#9aa0a6",
            Mode.JDBCPROF, "#1a73e8",
            Mode.P6SPY, "#f29900");

    private SvgRenderer() {
    }

    static void render(List<Result> results, Path out) throws IOException {
        Map<String, Map<Mode, Result>> byScenario = group(results);
        double maxNs = 0;
        for (Result r : results) {
            maxNs = Math.max(maxNs, r.meanNanosPerOp());
        }
        // Round the y-axis up to a tidy number so gridlines land on
        // round values rather than weird fractional ticks.
        double yMax = niceCeiling(maxNs);

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(WIDTH).append(' ').append(HEIGHT)
                .append("\" font-family=\"system-ui, -apple-system, Segoe UI, Roboto, sans-serif\">\n");
        sb.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");

        sb.append("  <text x=\"").append(WIDTH / 2)
                .append("\" y=\"32\" text-anchor=\"middle\" font-size=\"18\" font-weight=\"600\" fill=\"#202124\">")
                .append("JDBC interceptor overhead — ns per operation (lower is better)")
                .append("</text>\n");

        appendLegend(sb);
        appendYAxis(sb, yMax);

        int chartLeft = PAD_LEFT;
        int chartRight = WIDTH - PAD_RIGHT;
        int chartTop = PAD_TOP;
        int chartBottom = HEIGHT - PAD_BOTTOM;
        int chartHeight = chartBottom - chartTop;

        int scenarioCount = byScenario.size();
        int groupWidth = scenarioCount == 0 ? 0
                : (chartRight - chartLeft - GROUP_GAP * (scenarioCount + 1)) / scenarioCount;

        int x = chartLeft + GROUP_GAP;
        for (Map.Entry<String, Map<Mode, Result>> e : byScenario.entrySet()) {
            int barX = x + (groupWidth - (BAR_WIDTH * 3 + BAR_GAP * 2)) / 2;
            for (Mode mode : Mode.values()) {
                Result r = e.getValue().get(mode);
                double ns = r == null ? 0 : r.meanNanosPerOp();
                int barH = (int) Math.round(chartHeight * (ns / yMax));
                int barY = chartBottom - barH;
                sb.append("  <rect x=\"").append(barX)
                        .append("\" y=\"").append(barY)
                        .append("\" width=\"").append(BAR_WIDTH)
                        .append("\" height=\"").append(barH)
                        .append("\" fill=\"").append(COLORS.get(mode))
                        .append("\" rx=\"3\"/>\n");
                sb.append("  <text x=\"").append(barX + BAR_WIDTH / 2)
                        .append("\" y=\"").append(barY - 8)
                        .append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"#3c4043\">")
                        .append(formatNs(ns))
                        .append("</text>\n");
                barX += BAR_WIDTH + BAR_GAP;
            }
            sb.append("  <text x=\"").append(x + groupWidth / 2)
                    .append("\" y=\"").append(chartBottom + 30)
                    .append("\" text-anchor=\"middle\" font-size=\"13\" font-weight=\"500\" fill=\"#202124\">")
                    .append(escape(e.getKey()))
                    .append("</text>\n");

            // Overhead percentages for the two interceptor bars,
            // relative to the NONE baseline within this scenario.
            Result base = e.getValue().get(Mode.NONE);
            if (base != null && base.meanNanosPerOp() > 0) {
                int overheadY = chartBottom + 54;
                int barXLabel = x + (groupWidth - (BAR_WIDTH * 3 + BAR_GAP * 2)) / 2
                        + BAR_WIDTH + BAR_GAP + BAR_WIDTH / 2;
                Result our = e.getValue().get(Mode.JDBCPROF);
                if (our != null) {
                    sb.append("  <text x=\"").append(barXLabel)
                            .append("\" y=\"").append(overheadY)
                            .append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"#5f6368\">")
                            .append(formatOverhead(our, base))
                            .append("</text>\n");
                }
                Result p6 = e.getValue().get(Mode.P6SPY);
                if (p6 != null) {
                    sb.append("  <text x=\"").append(barXLabel + BAR_WIDTH + BAR_GAP)
                            .append("\" y=\"").append(overheadY)
                            .append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"#5f6368\">")
                            .append(formatOverhead(p6, base))
                            .append("</text>\n");
                }
            }
            x += groupWidth + GROUP_GAP;
        }

        sb.append("</svg>\n");

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, Map<Mode, Result>> group(List<Result> results) {
        // LinkedHashMap preserves the insertion order so the chart's
        // scenario columns follow the order produced by RunCommand.
        Map<String, Map<Mode, Result>> out = new LinkedHashMap<>();
        for (Result r : results) {
            out.computeIfAbsent(r.scenario(), k -> new LinkedHashMap<>())
                    .put(r.mode(), r);
        }
        return out;
    }

    private static void appendLegend(StringBuilder sb) {
        int legendY = 70;
        int x = PAD_LEFT;
        for (Mode mode : Mode.values()) {
            sb.append("  <rect x=\"").append(x)
                    .append("\" y=\"").append(legendY - 10)
                    .append("\" width=\"14\" height=\"14\" rx=\"2\" fill=\"")
                    .append(COLORS.get(mode))
                    .append("\"/>\n");
            sb.append("  <text x=\"").append(x + 20)
                    .append("\" y=\"").append(legendY + 1)
                    .append("\" font-size=\"12\" fill=\"#3c4043\">")
                    .append(legendLabel(mode))
                    .append("</text>\n");
            x += 200;
        }
    }

    private static String legendLabel(Mode mode) {
        return switch (mode) {
            case NONE -> "no interceptor";
            case JDBCPROF -> "jdbc-prof (this library)";
            case P6SPY -> "P6Spy 3.9.1";
        };
    }

    /**
     * Short label used directly under each bar — kept narrow so three
     * adjacent bars don't have their captions colliding. The full
     * descriptive form lives in {@link #legendLabel}.
     */
    private static String barLabel(Mode mode) {
        return switch (mode) {
            case NONE -> "no profiler";
            case JDBCPROF -> "jdbc-prof";
            case P6SPY -> "P6Spy";
        };
    }

    private static void appendYAxis(StringBuilder sb, double yMax) {
        int chartLeft = PAD_LEFT;
        int chartTop = PAD_TOP;
        int chartBottom = HEIGHT - PAD_BOTTOM;
        // Pick a single unit for every tick on this axis based on the
        // chart's range. Mixing units across ticks (e.g., "1500 ms" at
        // the top and "0 ns" at the bottom) is jarring; the bottom
        // tick should read "0 ms" to match the rest.
        String unit;
        double divisor;
        if (yMax >= 1_000_000) { unit = "ms"; divisor = 1_000_000; }
        else if (yMax >= 1_000) { unit = "µs"; divisor = 1_000; }
        else { unit = "ns"; divisor = 1; }
        int ticks = 4;
        for (int i = 0; i <= ticks; i++) {
            double frac = (double) i / ticks;
            double value = yMax * (1 - frac);
            int y = chartTop + (int) Math.round((chartBottom - chartTop) * frac);
            sb.append("  <line x1=\"").append(chartLeft)
                    .append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(WIDTH - PAD_RIGHT)
                    .append("\" y2=\"").append(y)
                    .append("\" stroke=\"#e8eaed\" stroke-width=\"1\"/>\n");
            sb.append("  <text x=\"").append(chartLeft - 8)
                    .append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"end\" font-size=\"11\" fill=\"#5f6368\">")
                    .append(formatTick(value / divisor, unit))
                    .append("</text>\n");
        }
    }

    private static String formatTick(double scaled, String unit) {
        if (scaled == Math.floor(scaled)) {
            return String.format(Locale.ROOT, "%.0f %s", scaled, unit);
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, unit);
    }

    private static String formatNs(double ns) {
        if (ns >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f ms", ns / 1_000_000);
        }
        if (ns >= 1_000) {
            return String.format(Locale.ROOT, "%.1f µs", ns / 1_000);
        }
        return String.format(Locale.ROOT, "%.0f ns", ns);
    }

    private static String formatOverhead(Result r, Result base) {
        double overhead = (r.meanNanosPerOp() - base.meanNanosPerOp())
                / base.meanNanosPerOp() * 100.0;
        return String.format(Locale.ROOT, "+%.0f%%", overhead);
    }

    private static double niceCeiling(double v) {
        if (v <= 0) {
            return 1;
        }
        double mag = Math.pow(10, Math.floor(Math.log10(v)));
        double normalized = v / mag;
        double niceNorm;
        if (normalized <= 1) {
            niceNorm = 1;
        } else if (normalized <= 2) {
            niceNorm = 2;
        } else if (normalized <= 5) {
            niceNorm = 5;
        } else {
            niceNorm = 10;
        }
        return niceNorm * mag;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Renders the end-to-end document: one bar per mode, mean wall-
     * clock on the y-axis, min/max whiskers, baseline-relative
     * overhead labels under non-NONE bars.
     */
    static void renderE2e(List<E2eResult> results, Path out) throws IOException {
        Map<Mode, E2eResult> byMode = new LinkedHashMap<>();
        for (E2eResult r : results) {
            byMode.put(r.mode(), r);
        }
        double maxNs = 0;
        for (E2eResult r : results) {
            maxNs = Math.max(maxNs, r.maxNanos());
        }
        double yMax = niceCeiling(maxNs);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(WIDTH).append(' ').append(HEIGHT)
                .append("\" font-family=\"system-ui, -apple-system, Segoe UI, Roboto, sans-serif\">\n");
        sb.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");

        sb.append("  <text x=\"").append(WIDTH / 2)
                .append("\" y=\"32\" text-anchor=\"middle\" font-size=\"18\" font-weight=\"600\" fill=\"#202124\">")
                .append("End-to-end run — wall clock per full sample-app execution (lower is better)")
                .append("</text>\n");

        appendLegend(sb);
        appendYAxis(sb, yMax);

        int chartLeft = PAD_LEFT;
        int chartRight = WIDTH - PAD_RIGHT;
        int chartTop = PAD_TOP;
        int chartBottom = HEIGHT - PAD_BOTTOM;
        int chartHeight = chartBottom - chartTop;
        int chartWidth = chartRight - chartLeft;

        // One slot per mode, bar centred in its slot. Spreading bars
        // across the full chart width (rather than packing them with
        // BAR_GAP between) gives each bar's bottom labels ~chartWidth/N
        // of horizontal room, so "no profiler" / "jdbc-prof" / "P6Spy"
        // and the +XX% overhead lines underneath don't collide.
        Mode[] modes = Mode.values();
        int slotWidth = chartWidth / modes.length;

        E2eResult baseline = byMode.get(Mode.NONE);
        for (int slotIdx = 0; slotIdx < modes.length; slotIdx++) {
            Mode mode = modes[slotIdx];
            E2eResult r = byMode.get(mode);
            int slotCenter = chartLeft + slotWidth * slotIdx + slotWidth / 2;
            int barX = slotCenter - BAR_WIDTH / 2;
            if (r == null) {
                continue;
            }
            int barH = (int) Math.round(chartHeight * (r.meanNanos() / yMax));
            int barY = chartBottom - barH;
            sb.append("  <rect x=\"").append(barX)
                    .append("\" y=\"").append(barY)
                    .append("\" width=\"").append(BAR_WIDTH)
                    .append("\" height=\"").append(barH)
                    .append("\" fill=\"").append(COLORS.get(mode))
                    .append("\" rx=\"3\"/>\n");

            // Min/max whisker. The bar's height is the mean; the
            // whisker shows the spread across timed runs so the chart
            // is honest about variance.
            int minY = chartBottom - (int) Math.round(chartHeight * (r.minNanos() / yMax));
            int maxY = chartBottom - (int) Math.round(chartHeight * (r.maxNanos() / yMax));
            int centerX = barX + BAR_WIDTH / 2;
            sb.append("  <line x1=\"").append(centerX)
                    .append("\" y1=\"").append(maxY)
                    .append("\" x2=\"").append(centerX)
                    .append("\" y2=\"").append(minY)
                    .append("\" stroke=\"#202124\" stroke-width=\"1.5\"/>\n");
            int capHalf = 8;
            sb.append("  <line x1=\"").append(centerX - capHalf)
                    .append("\" y1=\"").append(maxY)
                    .append("\" x2=\"").append(centerX + capHalf)
                    .append("\" y2=\"").append(maxY)
                    .append("\" stroke=\"#202124\" stroke-width=\"1.5\"/>\n");
            sb.append("  <line x1=\"").append(centerX - capHalf)
                    .append("\" y1=\"").append(minY)
                    .append("\" x2=\"").append(centerX + capHalf)
                    .append("\" y2=\"").append(minY)
                    .append("\" stroke=\"#202124\" stroke-width=\"1.5\"/>\n");

            // Mean label above the whisker so it doesn't collide.
            sb.append("  <text x=\"").append(centerX)
                    .append("\" y=\"").append(maxY - 8)
                    .append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"#3c4043\">")
                    .append(formatNs(r.meanNanos()))
                    .append("</text>\n");

            // Short mode label under the bar — full descriptive form
            // is already in the legend, so the per-bar caption can be
            // narrow enough not to overlap its neighbours.
            sb.append("  <text x=\"").append(centerX)
                    .append("\" y=\"").append(chartBottom + 30)
                    .append("\" text-anchor=\"middle\" font-size=\"13\" font-weight=\"500\" fill=\"#202124\">")
                    .append(barLabel(mode))
                    .append("</text>\n");

            // Overhead-vs-baseline percentage for non-NONE modes.
            if (baseline != null && mode != Mode.NONE && baseline.meanNanos() > 0) {
                double overhead = (r.meanNanos() - baseline.meanNanos())
                        / baseline.meanNanos() * 100.0;
                sb.append("  <text x=\"").append(centerX)
                        .append("\" y=\"").append(chartBottom + 54)
                        .append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"#5f6368\">")
                        .append(String.format(Locale.ROOT, "+%.0f%% vs baseline", overhead))
                        .append("</text>\n");
            }
        }

        sb.append("</svg>\n");

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
