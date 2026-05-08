package fi.vesas.jdbclens.analysis;

import fi.vesas.jdbclens.capture.Event;
import fi.vesas.jdbclens.capture.EventType;
import fi.vesas.jdbclens.capture.StackFrameSnapshot;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlameGraphTest {

    @Test
    void emptyAggregatorGivesEmptyRoot() {
        FlameGraph.Node root = FlameGraph.build(new Aggregator(), new HashMap<>());
        assertThat(root.frame).isNull();
        assertThat(root.totalDurationNanos).isZero();
        assertThat(root.children()).isEmpty();
    }

    @Test
    void singleStackBecomesLinearChain() {
        Aggregator agg = new Aggregator();
        agg.add(exec(1, 10, 1_000));
        agg.add(exec(1, 10, 2_000));

        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1, new StackFrameSnapshot[] {
                frame("fi.vesas.jdbclens.capture.CaptureContext", "emit", 91),  // infra — stripped
                frame("com.example.Dao", "find", 47),                                  // call-site
                frame("com.example.Service", "load", 12),                              // mid
                frame("com.example.Main", "main", 5),                                  // outer
                frame("java.lang.Thread", "run", 840)                                  // JDK — stripped
        });

        FlameGraph.Node root = FlameGraph.build(agg, stacks);
        assertThat(root.totalDurationNanos).isEqualTo(3_000L);
        assertThat(root.children()).hasSize(1);

        FlameGraph.Node main = root.children().get(0);
        assertThat(main.frame.methodName()).isEqualTo("main");
        assertThat(main.totalDurationNanos).isEqualTo(3_000L);

        FlameGraph.Node svc = main.children().get(0);
        assertThat(svc.frame.methodName()).isEqualTo("load");

        FlameGraph.Node dao = svc.children().get(0);
        assertThat(dao.frame.methodName()).isEqualTo("find");
        assertThat(dao.children()).isEmpty();
    }

    @Test
    void sharedAncestorIsAccumulated() {
        // Two stacks that share Main.main / Service.load but branch at
        // the last frame. The shared prefix should carry the sum.
        Aggregator agg = new Aggregator();
        agg.add(exec(1, 10, 5_000));  // Dao.findById via stack 1
        agg.add(exec(2, 11, 3_000));  // Dao.findByEmail via stack 2

        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(
                1, new StackFrameSnapshot[] {
                        frame("com.example.Dao", "findById", 47),
                        frame("com.example.Service", "load", 12),
                        frame("com.example.Main", "main", 5)
                },
                2, new StackFrameSnapshot[] {
                        frame("com.example.Dao", "findByEmail", 99),
                        frame("com.example.Service", "load", 12),
                        frame("com.example.Main", "main", 5)
                });

        FlameGraph.Node root = FlameGraph.build(agg, stacks);
        assertThat(root.totalDurationNanos).isEqualTo(8_000L);
        FlameGraph.Node main = root.children().get(0);
        assertThat(main.totalDurationNanos).isEqualTo(8_000L);
        FlameGraph.Node svc = main.children().get(0);
        assertThat(svc.totalDurationNanos).isEqualTo(8_000L);
        assertThat(svc.children()).hasSize(2);

        FlameGraph.Node widest = svc.childrenSortedByDuration().get(0);
        assertThat(widest.frame.methodName()).isEqualTo("findById");
        assertThat(widest.totalDurationNanos).isEqualTo(5_000L);
    }

    @Test
    void stackEntirelyInfrastructureIsIgnored() {
        Aggregator agg = new Aggregator();
        agg.add(exec(1, 10, 4_000));
        Map<Integer, StackFrameSnapshot[]> stacks = Map.of(1, new StackFrameSnapshot[] {
                frame("fi.vesas.jdbclens.capture.CaptureContext", "emit", 1),
                frame("java.lang.Thread", "run", 2)
        });
        FlameGraph.Node root = FlameGraph.build(agg, stacks);
        assertThat(root.children()).isEmpty();
        assertThat(root.totalDurationNanos).isZero();
    }

    private static Event exec(int stackId, int sqlId, long durationNanos) {
        Event e = new Event();
        e.timestampNanos = 0L;
        e.threadId = 1;
        e.eventType = EventType.EXECUTE_QUERY.code();
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = durationNanos;
        e.rowsAffected = -1;
        e.batchSize = 0;
        return e;
    }

    private static StackFrameSnapshot frame(String cls, String method, int line) {
        return new StackFrameSnapshot(cls, method, line);
    }
}
