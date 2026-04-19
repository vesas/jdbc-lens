package io.github.vesas.jdbcprof.analysis;

import io.github.vesas.jdbcprof.ProfilerConfig;
import io.github.vesas.jdbcprof.capture.StackFrameSnapshot;

import java.util.List;

/**
 * Call-site attribution (spec §8.1). Given the captured frames of a
 * single event, return the frame that should be presented to the
 * developer as the origin of the query.
 *
 * <p>Primary pass: scan frames from the innermost outward and return
 * the first one that does not match the configured exclusion list.
 * The default list filters out JDBC internals, connection pools, ORM
 * frameworks, Spring data access, and the profiler's own package.
 *
 * <p>Fallback (every frame primary-excluded): scan again but only
 * skip frames that can never be a meaningful call-site — the JDK,
 * {@code java.sql.} / {@code javax.sql.}, and the profiler itself.
 * This lets framework frames through when the app is so framework-
 * heavy the stack depth didn't reach user code; they are still more
 * useful to the reader than seeing {@code CaptureContext.emit}.
 *
 * <p>Last resort (no frames, or every frame is JDK/JDBC/profiler):
 * return the innermost captured frame. A bad attribution is more
 * useful than none; the report can mark it as "unattributed."
 */
public final class Attribution {

    private static final String[] JDK_PREFIXES = {
            "java.", "jdk.", "sun.", "javax."
    };

    // Prefixes that can never be a useful call-site even in the
    // fallback pass — they're profiler/JDBC infrastructure by design.
    private static final String[] INFRA_PREFIXES = {
            "io.github.vesas.jdbcprof.",
            "java.sql.",
            "javax.sql."
    };

    private Attribution() {
    }

    public static StackFrameSnapshot callSite(StackFrameSnapshot[] frames) {
        return callSite(frames, ProfilerConfig.defaultFrameExclusions());
    }

    public static StackFrameSnapshot callSite(StackFrameSnapshot[] frames,
                                              List<String> exclusions) {
        if (frames == null || frames.length == 0) {
            return null;
        }
        for (StackFrameSnapshot f : frames) {
            if (!matchesAny(f, exclusions)) {
                return f;
            }
        }
        for (StackFrameSnapshot f : frames) {
            if (!isJdk(f) && !isInfra(f)) {
                return f;
            }
        }
        return frames[0];
    }

    private static boolean matchesAny(StackFrameSnapshot f, List<String> prefixes) {
        String cls = f.className();
        // Index-based loop: avoids the Iterator allocation the report
        // will see per event. Attribution runs offline but the report
        // still calls it millions of times for a large recording.
        for (int i = 0; i < prefixes.size(); i++) {
            if (cls.startsWith(prefixes.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdk(StackFrameSnapshot f) {
        return startsWithAny(f.className(), JDK_PREFIXES);
    }

    private static boolean isInfra(StackFrameSnapshot f) {
        return startsWithAny(f.className(), INFRA_PREFIXES);
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
