package fi.vesas.jdbcprof.analysis.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRootInferenceTest {

    @Test
    void gradleLayoutMapsToSrcMainJava(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("app");
        Files.createDirectories(module.resolve("build/classes/java/main"));
        Files.createDirectories(module.resolve("src/main/java"));
        String classpath = module.resolve("build/classes/java/main").toString();

        List<Path> roots = SourceRootInference.infer(tmp.toString(), classpath);
        assertThat(roots).containsExactly(module.resolve("src/main/java"));
    }

    @Test
    void gradleMultiModuleMapsEachModule(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("core");
        Path b = tmp.resolve("analysis");
        Files.createDirectories(a.resolve("build/classes/java/main"));
        Files.createDirectories(a.resolve("src/main/java"));
        Files.createDirectories(b.resolve("build/classes/java/main"));
        Files.createDirectories(b.resolve("src/main/java"));
        String classpath = a.resolve("build/classes/java/main")
                + File.pathSeparator + b.resolve("build/classes/java/main");

        List<Path> roots = SourceRootInference.infer(tmp.toString(), classpath);
        assertThat(roots).containsExactlyInAnyOrder(
                a.resolve("src/main/java"),
                b.resolve("src/main/java"));
    }

    @Test
    void gradleTestSetMapsToSrcTestJava(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("mod");
        Files.createDirectories(module.resolve("build/classes/java/test"));
        Files.createDirectories(module.resolve("src/test/java"));
        String classpath = module.resolve("build/classes/java/test").toString();

        List<Path> roots = SourceRootInference.infer(tmp.toString(), classpath);
        assertThat(roots).containsExactly(module.resolve("src/test/java"));
    }

    @Test
    void mavenLayoutMaps(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("svc");
        Files.createDirectories(module.resolve("target/classes"));
        Files.createDirectories(module.resolve("target/test-classes"));
        Files.createDirectories(module.resolve("src/main/java"));
        Files.createDirectories(module.resolve("src/test/java"));
        String classpath = module.resolve("target/classes")
                + File.pathSeparator + module.resolve("target/test-classes");

        List<Path> roots = SourceRootInference.infer(tmp.toString(), classpath);
        assertThat(roots).containsExactlyInAnyOrder(
                module.resolve("src/main/java"),
                module.resolve("src/test/java"));
    }

    @Test
    void nonExistentMappedDirsAreFilteredOut(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("ghost");
        Files.createDirectories(module.resolve("build/classes/java/main"));
        // Note: no src/main/java — simulating off-machine analyze.
        String classpath = module.resolve("build/classes/java/main").toString();

        assertThat(SourceRootInference.infer(tmp.toString(), classpath)).isEmpty();
    }

    @Test
    void jarsAndUnknownEntriesAreIgnored(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("m");
        Files.createDirectories(module.resolve("build/classes/java/main"));
        Files.createDirectories(module.resolve("src/main/java"));
        Path jar = tmp.resolve("lib/postgres.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "");
        String classpath = jar
                + File.pathSeparator + module.resolve("build/classes/java/main")
                + File.pathSeparator + "/does/not/exist";

        List<Path> roots = SourceRootInference.infer(tmp.toString(), classpath);
        assertThat(roots).containsExactly(module.resolve("src/main/java"));
    }

    @Test
    void emptyInputsReturnEmpty() {
        assertThat(SourceRootInference.infer(null, null)).isEmpty();
        assertThat(SourceRootInference.infer("", "")).isEmpty();
        assertThat(SourceRootInference.infer("/some/dir", "")).isEmpty();
    }

    @Test
    void relativeClasspathResolvedAgainstUserDir(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("rel");
        Files.createDirectories(module.resolve("build/classes/java/main"));
        Files.createDirectories(module.resolve("src/main/java"));
        // Classpath entry as if Gradle launched with `java -cp build/classes/java/main`.
        List<Path> roots = SourceRootInference.infer(
                module.toString(), "build/classes/java/main");
        assertThat(roots).containsExactly(module.resolve("src/main/java"));
    }
}
