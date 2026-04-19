package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.capture.Event;
import io.github.vesas.jdbcprof.capture.EventType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorTest {

    @Test
    void ranksPairsByTotalDuration() {
        Aggregator agg = new Aggregator();
        // Pair (stack=0, sql=0): 3 events, total 3000ns.
        agg.add(event(0, 0, 1000));
        agg.add(event(0, 0, 1000));
        agg.add(event(0, 0, 1000));
        // Pair (stack=1, sql=1): 1 event, total 10000ns (slower total).
        agg.add(event(1, 1, 10_000));
        // Pair (stack=2, sql=0): 2 events, total 500ns.
        agg.add(event(2, 0, 250));
        agg.add(event(2, 0, 250));

        List<Map.Entry<Aggregator.PairKey, Aggregator.Stats>> top = agg.topPairs(10);

        assertThat(top).hasSize(3);
        assertThat(top.get(0).getKey()).isEqualTo(new Aggregator.PairKey(1, 1));
        assertThat(top.get(0).getValue().totalDurationNanos()).isEqualTo(10_000L);
        assertThat(top.get(1).getKey()).isEqualTo(new Aggregator.PairKey(0, 0));
        assertThat(top.get(1).getValue().count()).isEqualTo(3L);
        assertThat(top.get(2).getKey()).isEqualTo(new Aggregator.PairKey(2, 0));
    }

    @Test
    void callSiteAndTemplateAxesMergeAcrossPairs() {
        Aggregator agg = new Aggregator();
        // Same SQL template (sqlId=5), different call-sites.
        agg.add(event(0, 5, 1000));
        agg.add(event(1, 5, 2000));
        // A different template from call-site 0.
        agg.add(event(0, 7, 4000));

        // By template: sqlId=5 has two events (3000ns); sqlId=7 has one (4000ns).
        List<Map.Entry<Integer, Aggregator.Stats>> templates = agg.topTemplates(10);
        assertThat(templates).hasSize(2);
        assertThat(templates.get(0).getKey()).isEqualTo(7);
        assertThat(templates.get(0).getValue().totalDurationNanos()).isEqualTo(4000L);
        assertThat(templates.get(1).getKey()).isEqualTo(5);
        assertThat(templates.get(1).getValue().count()).isEqualTo(2L);

        // By call-site: stack=0 has two events (5000ns); stack=1 has one (2000ns).
        List<Map.Entry<Integer, Aggregator.Stats>> sites = agg.topCallSites(10);
        assertThat(sites).hasSize(2);
        assertThat(sites.get(0).getKey()).isEqualTo(0);
        assertThat(sites.get(0).getValue().totalDurationNanos()).isEqualTo(5000L);
        assertThat(sites.get(1).getKey()).isEqualTo(1);
    }

    @Test
    void clampsNegativeDurationToZero() {
        Aggregator agg = new Aggregator();
        agg.add(event(0, 0, -42));
        agg.add(event(0, 0, 100));
        Aggregator.Stats s = agg.topPairs(1).get(0).getValue();
        assertThat(s.count()).isEqualTo(2L);
        assertThat(s.totalDurationNanos()).isEqualTo(100L);
    }

    @Test
    void limitTopNRespectsBound() {
        Aggregator agg = new Aggregator();
        for (int i = 0; i < 5; i++) {
            agg.add(event(i, i, (long) (i + 1) * 1000));
        }
        assertThat(agg.topPairs(3)).hasSize(3);
        assertThat(agg.topPairs(3).get(0).getKey().stackTraceId()).isEqualTo(4);
    }

    @Test
    void emptyAggregatorReturnsEmptyLists() {
        Aggregator agg = new Aggregator();
        assertThat(agg.topPairs(10)).isEmpty();
        assertThat(agg.topCallSites(10)).isEmpty();
        assertThat(agg.topTemplates(10)).isEmpty();
    }

    private static Event event(int stackId, int sqlId, long duration) {
        Event e = new Event();
        e.timestampNanos = 0L;
        e.threadId = 1;
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = duration;
        e.rowsAffected = -1;
        e.batchSize = 0;
        return e;
    }
}
