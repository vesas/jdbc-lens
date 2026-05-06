package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import java.util.List;

/**
 * One entity accessed via more than one distinct SQL template
 * inside a single operation (spec extension for §8.2). A "real"
 * example: an order's owner row fetched once by {@code findName}
 * and again by {@code findEmailById} — two round trips for data
 * that could come from a single SELECT.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code opId} / {@code opName} — which logical operation
 *       this happened inside.</li>
 *   <li>{@code table}, {@code column}, {@code value} — the
 *       entity key.</li>
 *   <li>{@code templates} — the distinct templates that touched
 *       it (SQL + representative call-site).</li>
 *   <li>{@code totalEvents} / {@code totalDurationNanos} — summed
 *       across all touches of this entity in this op.</li>
 * </ul>
 */
public record EntityFinding(
        long opId,
        String opName,
        String table,
        String column,
        String value,
        List<TemplateHit> templates,
        long totalEvents,
        long totalDurationNanos) {

    public EntityFinding {
        templates = List.copyOf(templates);
    }

    public record TemplateHit(int sqlId, String sql, StackFrameSnapshot callSite,
                              long count, long totalDurationNanos) {
    }
}
