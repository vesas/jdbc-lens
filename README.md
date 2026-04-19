# jdbc-prof

Records every JDBC query your app runs, attributes it to the call-site
in your code, and produces an HTML report.

## Use it in another project

### 1. Reference this repo as a Gradle composite build

In your project's `settings.gradle.kts`:

```kotlin
includeBuild("../path/to/jdbc-prof")
```

### 2. Depend on the core module

In your project's `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("io.github.vesas:core")
}
```

Use `implementation` instead if you want the profiler outside tests.

### 3. Start the profiler and wrap your DataSource

Before the workload you want to profile:

```java
import io.github.vesas.jdbcprof.Profiler;
import io.github.vesas.jdbcprof.ProfilerConfig;
import javax.sql.DataSource;
import java.nio.file.Path;

Profiler.start(ProfilerConfig.defaults(Path.of("recording.jdbclog")));
DataSource profiled = Profiler.wrap(yourRealDataSource);
// hand `profiled` to everything that talks to the database
```

`Profiler.start` must run before `wrap`. The JVM shutdown hook calls
`Profiler.stop()` automatically, or call it yourself when the
workload is done.

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

- Java 17+
- Gradle 8+
