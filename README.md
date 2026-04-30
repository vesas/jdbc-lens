# JDBC Profiler

Records every JDBC query your app runs, attributes it to the call-site
in your code, and produces an HTML report.

## Try it in 30 seconds

A bundled sample app talks to H2 and runs a classic N+1 loop under
the profiler:

```
./gradlew :sample-app:run
./gradlew :analysis:run --args="sample-app/sample-recording.jdbclog -o sample-app/sample-report.html"
```

Open `sample-app/sample-report.html` in any browser. Look at
`Main.classicN1Loop:84` in the `(call-site, template) pairs` table —
that's the 50× repeated `SELECT name FROM customers WHERE id = ?`
the profiler flagged.

## Use it in another project

### 1. Publish the library to your Maven Local (one-off)

From the `jdbc-prof` repo:

```
./gradlew :core:publishToMavenLocal
```

This installs `fi.vesas:jdbc-prof-core:0.1.0-SNAPSHOT` into
`~/.m2/repository/`.

### 2. Depend on it from your project

In your project's `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation("fi.vesas:jdbc-prof-core:0.1.0-SNAPSHOT")
}
```

Use `implementation` instead if you want the profiler outside tests.

### 3. Start the profiler and wrap your DataSource

Before the workload you want to profile:

```java
import fi.vesas.jdbcprof.Profiler;
import fi.vesas.jdbcprof.ProfilerConfig;
import javax.sql.DataSource;
import java.nio.file.Path;

Profiler.start(ProfilerConfig.defaults(Path.of("recording.jdbclog")));
DataSource profiled = Profiler.wrap(yourRealDataSource);
// hand `profiled` to everything that talks to the database
```

`Profiler.wrap` may be called before or after `Profiler.start` —
wrapped DataSources resolve the session lazily, so they pass through
when no profiler is running and start capturing once `start()` fires.
The JVM shutdown hook calls `Profiler.stop()` automatically, or call
it yourself when the workload is done.

### 4. Run your tests / workload

Every query that flows through `profiled` is recorded to
`recording.jdbclog`.

### 5. Generate the report

From the `jdbc-prof` repo:

```
./gradlew :analysis:run --args="/path/to/recording.jdbclog -o report.html"
```

Open `report.html` in any browser. The file is self-contained — no
network calls, no external CSS or JS.

## What you get

- Event count, total database time, wall time.
- Top call-sites by time.
- Sortable tables: `(call-site, template)` pairs, call-sites alone,
  templates alone.

## Requirements

- Java 21+
- Gradle 8+
