# CLAUDE.md — JDBC Call-Site Profiler

## What this is

A Java library that records JDBC operations, attributes them to the
application call-site that caused them, and produces an offline HTML
report surfacing N+1 patterns, cacheable repeat queries, and
call-sites that dominate database time.

**The spec is the source of truth: `jdbc-callsite-profiler-spec.md`.**
This file exists to keep future sessions oriented, not to duplicate
the spec. When they disagree, the spec wins.

## Architectural non-negotiables

Three layers, each with different performance characteristics:

- **Capture** — runs inline with application JDBC calls. Bound by a
  **5 μs/call mean, 20 μs p99** overhead budget (spec §5.3, §12).
  This is the single most important design constraint.
- **Sink** — a background daemon thread that drains per-thread ring
  buffers and writes the binary log. May be modestly expensive.
- **Analysis** — offline CLI that reads the log and produces the HTML
  report. No performance budget; correctness and clarity dominate.

### Hot-path prohibitions (capture layer)

Lifted from spec §5.3 — every hot-path change must defend itself
against these:

- No synchronous I/O of any kind. Writes go to an in-memory ring
  buffer; the sink thread handles disk.
- No reflection. No `Method.invoke`, no proxy dispatch.
- No allocation beyond the event record itself. No string
  concatenation, no `toString` on query objects, no autoboxing in
  maps/lists. SQL strings referenced by interned id, not copied.
  Stack traces captured into a reusable thread-local array.
- No unbounded work. Stack trace depth capped (default 30). SQL
  template generation for ad-hoc `Statement` capped by length.
- No locks on shared structures. Per-thread SPSC ring buffers; the
  only synchronized structure is the intern map (`ConcurrentHashMap`).

If a change to the capture layer cannot be argued to respect all of
the above, it doesn't belong there — push it to the sink or analysis
layer.

## Module map

- `core/` — capture, sink, storage (binary log format). The whole
  published library. No runtime dependencies beyond the JDK.
- `analysis/` — the `jdbc-profile analyze` CLI, call-site
  attribution, N+1 detection, HTML report generation. Depends on
  `core/` for the log format.
- `benchmarks/` — JMH harness for the 5 μs target. Per spec §11,
  Phase 1 is gated on these benchmarks meeting the budget.

## Coordinates

- Group: `fi.vesas`
- Namespace: `fi.vesas.jdbcprof`
- Java toolchain: 17 (spec §2 targets JDBC 4.2+ and Java 17+).
  Gradle provisions it via toolchains — the JDK on `PATH` can be
  newer.

## Common commands

From the repo root:

- `./gradlew build` — compile everything, run tests.
- `./gradlew :core:test` — unit tests for the library only.
- `./gradlew :benchmarks:jmh` — run the JMH suite.
- `./gradlew :analysis:run --args="analyze path/to/recording.jdbclog -o report.html"`
  — run the analyze CLI (once implemented).

## Current phase

**Phase 1 — capture and storage** (spec §11).

End-state for this phase: running the profiler against a test
application produces a binary `.jdbclog` file, and the JMH harness
demonstrates the 5 μs hot-path budget is met. No HTML report yet.

Phases 2–4 (analysis/report, N+1 detection, polish) are scoped in
spec §11 and should not be started until Phase 1's benchmark gate is
met.

## Conventions

- Public API lives in `fi.vesas.jdbcprof` (spec §10). Keep it
  minimal — four entry points today.
- Internal packages: `fi.vesas.jdbcprof.capture`,
  `.sink`, `.storage`, `.analysis`. Do not leak internal types
  through the public API.
- Tests: JUnit 5 + AssertJ. Benchmarks: JMH.
- No comments that restate what the code does. Comments explain *why*
  a hot-path decision is shaped the way it is — that context is
  load-bearing and easy to lose.
