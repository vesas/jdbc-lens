package fi.vesas.jdbcprof.analysis.source;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-discovers source-root directories from the environment
 * snapshot embedded in the recording ({@code user.dir} +
 * {@code java.class.path}). Maps build-output directories to their
 * conventional sibling source directories for Gradle and Maven
 * layouts.
 *
 * <p>The mapping is deliberately conservative — only rewrites we can
 * be confident about. A classpath entry that doesn't match a known
 * shape is silently dropped; results are filtered to directories that
 * actually exist on the current filesystem. When analyze runs on a
 * different machine from the recording, the filter catches that and
 * the caller sees an empty list.
 */
public final class SourceRootInference {

    // Matches Gradle's standard output layout. The "{main,test}" part
    // is captured so it can route to the matching src/<set>/java.
    private static final String GRADLE_MARKER = "/build/classes/";

    private static final String MAVEN_MAIN_MARKER = "/target/classes";
    private static final String MAVEN_TEST_MARKER = "/target/test-classes";

    private SourceRootInference() {
    }

    /**
     * Produce the list of source directories implied by the classpath.
     * Entries are returned in classpath order, deduplicated, and
     * filtered to existing directories.
     */
    public static List<Path> infer(String userDir, String classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return List.of();
        }
        Path base = (userDir == null || userDir.isEmpty())
                ? null
                : Path.of(userDir).toAbsolutePath();

        Set<Path> out = new LinkedHashSet<>();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isEmpty()) {
                continue;
            }
            Path p = Path.of(entry);
            if (!p.isAbsolute() && base != null) {
                p = base.resolve(p);
            }
            p = p.normalize();
            Path mapped = mapClasspathEntry(p);
            if (mapped != null && Files.isDirectory(mapped)) {
                out.add(mapped);
            }
        }
        return new ArrayList<>(out);
    }

    private static Path mapClasspathEntry(Path entry) {
        // Work on the normalised forward-slash form so the markers are
        // the same on Windows and Unix.
        String s = entry.toString().replace('\\', '/');

        int gradle = s.indexOf(GRADLE_MARKER);
        if (gradle > 0) {
            String moduleRoot = s.substring(0, gradle);
            String tail = s.substring(gradle + GRADLE_MARKER.length());
            // tail looks like "java/main" or "java/test" — second part
            // is the source-set name.
            int slash = tail.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String set = tail.substring(slash + 1);
            int setEnd = set.indexOf('/');
            if (setEnd >= 0) {
                set = set.substring(0, setEnd);
            }
            if (set.isEmpty()) {
                return null;
            }
            return Path.of(moduleRoot, "src", set, "java");
        }

        // Maven main. Accept exact match or a trailing slash.
        int mvnMain = s.indexOf(MAVEN_MAIN_MARKER);
        if (mvnMain > 0 && atBoundary(s, mvnMain + MAVEN_MAIN_MARKER.length())) {
            return Path.of(s.substring(0, mvnMain), "src", "main", "java");
        }
        int mvnTest = s.indexOf(MAVEN_TEST_MARKER);
        if (mvnTest > 0 && atBoundary(s, mvnTest + MAVEN_TEST_MARKER.length())) {
            return Path.of(s.substring(0, mvnTest), "src", "test", "java");
        }
        return null;
    }

    private static boolean atBoundary(String s, int idx) {
        return idx == s.length() || s.charAt(idx) == '/';
    }
}
