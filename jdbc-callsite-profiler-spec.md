# JDBC Call-Site Profiler — Specification

## 1. Purpose

A Java library that records every JDBC operation performed by an application, attributes it to the application-level call-site that caused it, and produces an offline report surfacing performance problems — particularly N+1 query patterns, repeat queries that should be cached, and call-sites that disproportionately dominate database time.

The defining difference from existing tools is grouping by call-site rather than by query. Database slow-query logs answer "which queries are slow." APM tools answer "which HTTP endpoints are slow." This tool answers "which code in our application is producing the most database load, and is any of it pathological."

The library is intended to run in production-like environments — integration test suites, load-test runs, staging, and (optionally, with care) production traffic. This imposes a strict performance budget on the capture path and is the single most important design constraint in this document.

## 2. Non-goals

The library does not execute queries, does not modify JDBC behavior, does not replace APM tooling, and does not provide a live UI. It records, writes a binary event log, and generates static reports offline. It does not attempt to be a general-purpose profiler; CPU profiling, allocation profiling, and lock-contention profiling are handled by async-profiler, JFR, and other mature tools, and this library does not duplicate them.

The library does not capture query parameter values by default. This is an explicit choice: parameter capture is expensive (toString, allocation, string formatting) and adds data-leak risk. A developer debugging a specific issue can enable parameter capture for a bounded window; the default is off.

The library targets JDBC 4.2+ and Java 17+. Older environments are out of scope.

## 3. Core concepts

**Event.** A single JDBC operation — prepare, execute, next, close, commit, rollback — captured at the instant it occurred. Events are the raw material; everything else is derived from them.

**Call-site.** The application-level source location responsible for a JDBC event. Derived from the event's stack trace by walking upward past known JDBC, connection-pool, ORM, and framework frames to find the first frame belonging to application code. A call-site is identified by its fully-qualified class name, method name, and line number, and is the primary grouping dimension in the reports.

**SQL template.** A SQL statement with literal values redacted — `SELECT * FROM orders WHERE id = ?` rather than `SELECT * FROM orders WHERE id = 47`. For parameterized queries this is already the form JDBC sees. For ad-hoc `Statement` use, the tool redacts literal numbers, strings, and dates to produce a stable template. Grouping by template rather than by full statement is essential; without it, queries with varying parameters appear as thousands of unrelated entries.

**N+1 pattern.** A fixed outer operation that produces N rows, followed by N executions of a substantially identical inner query, one per row. The canonical symptom is a high-volume repetition of one SQL template, originating from call-sites that share a common ancestor frame within a narrow time window. Detection is a post-processing concern, not a capture-path concern.

**Logical operation.** A unit of work within which N+1 detection is scoped. Typically one HTTP request, one message-consumer invocation, or one test method. The tool does not know about these automatically; it accepts a caller-supplied operation id (set via a thread-local API) and groups events by it. Without operation ids, a fallback heuristic groups events by thread plus a time window.

## 4. Architecture

Three distinct layers, each with different performance characteristics and concerns.

**Capture layer.** Runs inline with application JDBC calls. Must be fast, allocation-light, thread-safe, and never block on I/O. Its only job is to produce events and deposit them into a per-thread ring buffer.

**Sink layer.** A background writer thread that drains per-thread ring buffers, performs lightweight deduplication (stack trace and SQL interning), and writes events to a binary log file on disk. Allowed to be modestly expensive relative to the capture layer, because it runs off the hot path.

**Analysis layer.** An offline CLI that reads the binary log, performs call-site attribution, N+1 detection, and aggregation, and produces the HTML report. Runs once, after the recorded workload completes. No performance budget to speak of; correctness and clarity dominate.

This separation is the architectural core of the design. The capture layer is the only component bound by the performance requirement; every other concern is pushed off the hot path.

## 5. Capture layer

### 5.1 Instrumentation approach

Primary approach: a wrapping `DataSource` that the application substitutes for its real one. The wrapper returns wrapping `Connection` objects, which return wrapping `Statement` and `PreparedStatement` objects, which intercept the small set of methods that matter (`execute`, `executeQuery`, `executeUpdate`, `executeBatch`, `addBatch`, `prepareStatement`, `close`, `commit`, `rollback`, `next`). This is the same pattern P6Spy uses and is well-understood.

Integration is one line in the application's connection-pool configuration: wrap the real `DataSource` with `Profiler.wrap(realDataSource)`. No bytecode weaving, no agent, no classpath surgery. Works with HikariCP, Tomcat JDBC, DBCP, and any other pool.

A secondary approach, using a `java.lang.instrument` agent with ByteBuddy to instrument `java.sql` interface implementations directly, is possible but is deferred to a later phase. Wrapping covers 95% of real-world setups and is simpler to reason about.

### 5.2 Event structure

An event is a fixed-size record, designed to be zero-allocation to produce and fast to serialize. Field-by-field:

- `timestampNanos` — long, 8 bytes, from `System.nanoTime()`
- `threadId` — int, 4 bytes
- `operationId` — long, 8 bytes, from the thread-local operation context
- `eventType` — byte, 1 byte (enum: PREPARE, EXECUTE_QUERY, EXECUTE_UPDATE, EXECUTE_BATCH, NEXT, COMMIT, ROLLBACK, CLOSE)
- `sqlId` — int, 4 bytes, index into the SQL intern table
- `stackTraceId` — int, 4 bytes, index into the stack trace intern table
- `durationNanos` — long, 8 bytes (set on completion; 0 for instantaneous events)
- `rowsAffected` — int, 4 bytes (for UPDATE/DELETE/INSERT; -1 for other types)
- `batchSize` — int, 4 bytes (for batch operations; 0 otherwise)

Total: 45 bytes per event, padded to 48. An event buffer of 1 million events consumes ~48 MB. For a one-hour capture at 1000 events/second, total volume is ~180 MB — tractable on any modern machine.

### 5.3 Hot-path performance budget

Target overhead per intercepted JDBC call: **under 5 microseconds** on commodity server hardware. This budget is generous relative to typical JDBC operation durations (100 microseconds to 100 milliseconds) but tight in absolute terms, and every hot-path decision must defend itself against it.

Specifically, the following are prohibited in the hot path:

Synchronous I/O of any kind. All writes go to an in-memory ring buffer; a background thread handles disk I/O. Blocking on a full buffer is acceptable as a backpressure mechanism but must be a rare event in steady state; default buffer sizes should make it essentially never happen at normal load.

Reflection. Every call path from the wrapping classes into the event producer uses direct method calls, never `Method.invoke` or proxy dispatch.

Allocation beyond the event record itself. String concatenation, toString calls on query objects, autoboxing of primitives in maps or lists — all banned. SQL strings are referenced by interned id, not copied. Stack traces are captured into a reusable thread-local array, hashed, and deduplicated by id.

Unbounded work. Stack trace capture is capped at a configurable depth (default 30 frames). SQL template generation for ad-hoc statements is capped at a maximum length; longer statements are truncated with a marker. Batch statements record the batch size but not the individual member statements.

Locks on shared data structures. Each capturing thread writes to its own thread-local event buffer, which is drained by the sink thread using a lock-free or single-producer/single-consumer queue (LMAX Disruptor is the reference design; a simpler hand-rolled ring is acceptable if benchmarked).

### 5.4 Stack trace capture

Stack traces are the single most expensive piece of the hot path, and careful handling here is the difference between a 2 μs tool and a 200 μs tool.

Use `StackWalker` with `Option.SHOW_REFLECT_FRAMES` disabled and `Option.RETAIN_CLASS_REFERENCE` only when needed. `StackWalker.walk(stream -> ...)` with a depth limit is substantially cheaper than `Thread.currentThread().getStackTrace()` because it can skip frames and terminate early.

Cap the depth at 30 frames by default, exposed as a configuration knob. Deeper stacks are rare; when they occur, the top 30 frames are almost always the informative ones.

Once a stack trace is captured into a thread-local reusable buffer of `StackFrame` objects, compute a 64-bit hash from the (className, methodName, lineNumber) tuples. Look up the hash in a concurrent intern map; if present, emit the existing `stackTraceId`. If absent, allocate a new id and record the full stack trace in the intern table. The intern map itself is the only synchronized structure in the capture path; a `ConcurrentHashMap` is adequate, and in practice most lookups hit after warm-up.

### 5.5 SQL interning

Symmetric to stack trace interning. For `PreparedStatement`, the SQL string is supplied by the application and is already in template form; hash it, intern it. For `Statement`, the SQL is a literal string that needs template-normalization (redacting literals) before hashing. Template normalization is a regex-and-state-machine operation; cache the result per raw-SQL-string so repeated identical raw statements don't re-normalize.

The intern tables grow monotonically during a recording. This is acceptable: real applications use a bounded set of SQL templates and a bounded set of call-site stack traces. Cardinality in both tables should plateau within the first few thousand events.

### 5.6 Ring buffer design

Per-thread, SPSC (single-producer, single-consumer). The producer is the application thread executing JDBC; the consumer is the sink writer thread.

Two realistic implementations:

A hand-rolled power-of-two-sized array with atomic head/tail indices. Simple, ~200 lines, adequate for this use case.

LMAX Disruptor. More capability than needed (it supports multi-consumer, event chaining, etc.) but battle-tested at well-known performance levels.

Start with the hand-rolled version; migrate to Disruptor if benchmarks show the simple implementation is a bottleneck.

Buffer size: 65,536 events per thread by default. At 48 bytes per event this is 3 MB per thread. For 100 threads, 300 MB of buffer memory — acceptable for a profiling tool, configurable downward.

Backpressure policy when a buffer is full: the default is to drop the oldest events (ring-buffer overwrite) and increment a dropped-events counter. An optional strict mode blocks the producer until the sink catches up, for cases where completeness is more important than application latency.

## 6. Sink layer

A single daemon thread started at profiler initialization. It iterates over the registered thread-local buffers, drains events in batches, and writes them to a binary log file using a length-prefixed frame format:

```
[4-byte magic] [4-byte version]
[intern-table section]
  [SQL template table — count-prefixed array of (id, hash, string)]
  [stack trace table — count-prefixed array of (id, hash, frame-array)]
[event section]
  [count-prefixed array of 48-byte event records]
[4-byte checksum]
```

Intern tables are written incrementally as new entries are added — the sink tracks which ids have been flushed and writes only deltas in subsequent flushes.

Writing is buffered (64 KB OS-level buffer) and uses `FileChannel` with direct `ByteBuffer` writes for throughput. The sink thread flushes every 100 ms or when a buffer fills, whichever comes first.

On shutdown (JVM hook or explicit `Profiler.stop()`), the sink drains all buffers, writes final intern-table state, writes a closing marker, and closes the file.

## 7. Storage format

A single binary file per recording. The name encodes the start timestamp and the application-supplied label: `recording-2026-04-19T14-23-05-integration-tests.jdbclog`.

Format is the one described above: header, intern tables, events, checksum. Self-contained — the analysis layer reads this one file and needs nothing else. Compression is deferred; the format is simple enough that gzipping the whole file after the recording completes is a reasonable optional step and halves the on-disk size.

## 8. Analysis layer

Entirely offline. A CLI `jdbc-profile analyze recording.jdbclog -o report.html` reads the log, runs the analysis passes, and emits a self-contained HTML file.

### 8.1 Call-site attribution

For each event, walk its stack trace from the bottom up. Skip frames matching a configurable exclusion list:

- `java.sql.*`, `javax.sql.*`
- `com.zaxxer.hikari.*`, `org.apache.tomcat.jdbc.*`, `org.apache.commons.dbcp.*`
- `org.hibernate.*`, `jakarta.persistence.*`, `javax.persistence.*`
- `org.springframework.jdbc.*`, `org.springframework.orm.*`
- The profiler's own package

The first non-excluded frame is the call-site. Record it as a `(className, methodName, lineNumber)` triple.

If every frame is excluded (rare, but possible in heavily framework-driven code), fall back to the first non-JDK frame.

### 8.2 Grouping

Group events along three axes independently:

- By call-site: "all events originating from `OrderService.loadAll:47`"
- By SQL template: "all events executing `SELECT * FROM orders WHERE customer_id = ?`"
- By (call-site, SQL template) pair: "all events from this call-site executing this template"

The (call-site, template) grouping is the most informative. A single call-site emitting many templates is an interesting pattern (a mapper class doing different things); a single template from many call-sites is also interesting (a widely used helper). Both views should be available in the report.

### 8.3 N+1 detection

Within each logical operation (bounded by operation id, or by thread-plus-time-window fallback), look for N+1 patterns using this rule:

If a single SQL template is executed more than threshold-N times (default 10), *and* more than fraction-F of those executions originate from call-sites descending from a common ancestor frame (default 0.9), flag the group as a candidate N+1.

Rank candidates by total database time, not by count — a thousand-query N+1 that takes 2 ms total is less important than a 50-query N+1 that takes 12 seconds.

Each flagged candidate produces a report entry with the SQL template, the representative call-site, the count, the total duration, and the common ancestor frame (which is usually the actual bug — the outer loop that should have been doing a batched query).

### 8.4 Flamegraph construction

Build a call tree where each node is a call-site (in the traditional flamegraph, each node is a frame; here it's a collapsed frame at the application level) and the node's weight is the total database time attributable to events originating from that site. Render as a standard flamegraph using the existing d3-flame-graph library or brendangregg/FlameGraph.

This visualization is the report's centerpiece. A developer looking at the flamegraph sees immediately where the application's database time is spent, in terms of their own code.

## 9. Report format

A single self-contained HTML file. Sections:

**Summary.** One screen. Recording duration, total events, total database time, top three call-sites by time, top three flagged N+1 patterns. Designed to be screenshottable.

**Flamegraph.** Full-page interactive flamegraph of call-sites by total database time. Click any node to drill down into the queries and events at that site.

**Call-sites table.** Every call-site, sortable by total time, query count, unique template count. Click any row to expand into the templates executed from that site and their individual statistics.

**Templates table.** Every SQL template, sortable by total time, execution count, originating call-site count. The inverse view of the call-sites table.

**N+1 findings.** Each flagged pattern as its own card: template, common-ancestor call-site, count, total time, first and last occurrence timestamp, a link to the representative call-site in the source tree.

**Operations timeline.** If operation ids were provided, a Gantt-style timeline of operations with their query counts and durations. Reveals outlier operations at a glance.

All HTML renders offline with no network calls. All CSS and JavaScript are inlined. The file opens in any browser with zero setup.

## 10. Public API

Minimal. The library exposes four entry points.

`Profiler.wrap(DataSource real)` — returns a wrapping `DataSource`. The only required integration step.

`Profiler.start(ProfilerConfig config)` — begins recording. `ProfilerConfig` carries output file path, buffer sizes, stack depth, exclusion list, and N+1 thresholds. A default config with sensible values is provided.

`Profiler.stop()` — flushes buffers, closes the output file, and stops the sink thread. Also runs automatically on JVM shutdown via a hook.

`Profiler.currentOperation(String operationId)` — sets a thread-local operation id. Invoked from the application's request/message/test boundaries. A JUnit 5 extension, a Servlet filter, and a Spring interceptor are provided as opt-in adapters that set this automatically.

## 11. Implementation phases

**Phase 1 — capture and storage.** Wrapping DataSource, event model, per-thread ring buffers, sink thread, binary log format. End-state: running the profiler against a test application produces a binary log file. No report yet. Benchmark the hot path rigorously at this stage; the 5 μs target is the gate that admits the project to phase 2.

**Phase 2 — analysis and reports.** CLI, call-site attribution, grouping, flamegraph generation, HTML report template. End-state: running `jdbc-profile analyze` on a phase-1 log produces a usable HTML report without N+1 detection.

**Phase 3 — N+1 detection.** Operation-id API, JUnit/Servlet/Spring adapters, N+1 detection logic, findings section of the report. End-state: running the profiler against an application with known N+1 issues flags them correctly in the report.

**Phase 4 — polish and ergonomics.** Configuration via properties file, optional parameter capture with redaction rules, optional JFR event emission for integration with JMC, Gradle plugin for running test suites under the profiler, documentation with worked examples. End-state: a developer can integrate and produce a useful report in under ten minutes.

Subsequent phases (Java agent variant, streaming mode for long-running captures, comparison mode between two recordings) are beyond the scope of this spec and should be scoped separately if pursued.

## 12. Performance validation plan

Because the 5 μs hot-path target is the spec's central claim, validation is a first-class concern, not an afterthought.

Benchmark harness: JMH, with a mock `DataSource` that executes a no-op query returning instantly. Measure the wrapped-vs-unwrapped difference. This isolates the profiler overhead from real JDBC cost.

Scenarios: single-threaded simple statement, single-threaded prepared statement with parameters, multi-threaded mix (10 threads hammering), cold-start vs warmed-up (intern tables populated), with and without operation-id set.

Reporting: each scenario produces a mean and 99th-percentile overhead in nanoseconds. The mean target is 5 μs; the 99th percentile target is 20 μs (allowing for occasional intern-table resizes and GC interactions).

If a scenario fails the target, the profiler enters an investigation cycle before proceeding — flamegraph the profiler itself, identify the hot spot, fix it, rerun. This discipline is what keeps the claim honest.
