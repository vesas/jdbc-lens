package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.StackFrameSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionTest {

    @Test
    void picksFirstNonExcludedFrame() {
        StackFrameSnapshot[] frames = {
                frame("fi.vesas.jdbcprof.capture.StackTraceInternTable", "internCurrent", 58),
                frame("fi.vesas.jdbcprof.capture.CaptureContext", "emit", 91),
                frame("fi.vesas.jdbcprof.capture.CapturingPreparedStatement", "executeQuery", 52),
                frame("com.example.OrderDao", "findById", 47),
                frame("com.example.OrderService", "load", 12),
                frame("java.lang.Thread", "run", 840)
        };

        StackFrameSnapshot site = Attribution.callSite(frames);

        assertThat(site.className()).isEqualTo("com.example.OrderDao");
        assertThat(site.methodName()).isEqualTo("findById");
        assertThat(site.lineNumber()).isEqualTo(47);
    }

    @Test
    void skipsFrameworksInDefaultExclusions() {
        StackFrameSnapshot[] frames = {
                frame("fi.vesas.jdbcprof.capture.CaptureContext", "emit", 91),
                frame("org.hibernate.internal.SessionImpl", "executeQuery", 200),
                frame("org.springframework.jdbc.core.JdbcTemplate", "query", 431),
                frame("com.example.UserRepository", "findAll", 23)
        };

        StackFrameSnapshot site = Attribution.callSite(frames);

        assertThat(site.className()).isEqualTo("com.example.UserRepository");
    }

    @Test
    void fallsBackToFirstNonJdkFrameWhenAllExcluded() {
        // Every captured frame matches an explicit exclusion prefix; the
        // fallback should return the first non-JDK frame — which in this
        // case is still a framework frame, but it's the best we have.
        StackFrameSnapshot[] frames = {
                frame("fi.vesas.jdbcprof.capture.CaptureContext", "emit", 1),
                frame("org.hibernate.internal.SessionImpl", "executeQuery", 2),
                frame("org.springframework.jdbc.core.JdbcTemplate", "query", 3)
        };

        StackFrameSnapshot site = Attribution.callSite(frames);

        assertThat(site.className()).isEqualTo("org.hibernate.internal.SessionImpl");
    }

    @Test
    void returnsNullWhenFramesEmpty() {
        assertThat(Attribution.callSite(new StackFrameSnapshot[0])).isNull();
        assertThat(Attribution.callSite(null)).isNull();
    }

    @Test
    void returnsInnermostWhenOnlyJdkFrames() {
        StackFrameSnapshot[] frames = {
                frame("java.lang.Thread", "sleep", 100),
                frame("jdk.internal.misc.Unsafe", "park", 50)
        };

        StackFrameSnapshot site = Attribution.callSite(frames);

        assertThat(site.className()).isEqualTo("java.lang.Thread");
    }

    @Test
    void honorsCustomExclusionList() {
        List<String> exclusions = List.of("com.example.internal.");
        StackFrameSnapshot[] frames = {
                frame("com.example.internal.Gateway", "dispatch", 10),
                frame("com.example.app.Handler", "handle", 20)
        };

        StackFrameSnapshot site = Attribution.callSite(frames, exclusions);

        assertThat(site.className()).isEqualTo("com.example.app.Handler");
    }

    private static StackFrameSnapshot frame(String cls, String method, int line) {
        return new StackFrameSnapshot(cls, method, line);
    }
}
