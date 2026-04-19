# jdbc-prof — ideas backlog

Parking lot for features and fixes that were discussed but not yet
built. Grouped by the kind of user problem they solve. The spec
(`jdbc-callsite-profiler-spec.md`) is still the source of truth for
anything spec-shaped; entries here are either app-side analyses that
go beyond the spec, or spec items we haven't hit yet.

## App-side: tell the user *what to change*

- **N+1 → concrete JOIN suggestion.** Pair each N+1 finding with the
  parent query (the previous query on the same thread whose row
  count matches the child count) and emit a replacement SQL
  snippet: `SELECT p.*, c.name FROM parent p JOIN child c ON …`,
  or a batched `WHERE id IN (?,?,…)`. Heuristic; not always right;
  extremely useful as a first-draft patch.
- **Prepared-statement reuse ratio.** Per template, count(PREPARE)
  vs count(EXECUTE*). Ratio near 1 means the app re-prepares every
  call — Oracle does a hard parse per call. Flag low-reuse
  templates and link to the statement-cache remediation.
- **Cursor over-fetch.** For each EXECUTE_QUERY, count NEXT events.
  A query fetching 10,000 rows in an op that doesn't need them all
  is a missing `LIMIT` / pagination. Weakest signal (we can't see
  what the app did with the rows) but high-fetch queries are
  always worth a look.
- **Cross-reference entity across identifier columns.** Current
  entity-access audit keys on `(table, column, value)`. Real
  identity is "the same row" — e.g., `orders.id = 42` and
  `orders WHERE customer_id = X ORDER BY id DESC LIMIT 1` can
  return the same row under different keys. Needs result-set
  value tracking to correlate.
- **Rule-based recommendations engine.** Combine detected patterns
  into a single "Actions" view: for each finding, show the
  suggested code change as a copy-pastable diff.

## DB-side: tell the user *why*

- **Oracle plan + V$SQL enrichment.** Opt-in CLI flag
  `--oracle-url=…` that connects post-recording and fetches, per
  unique template, `DBMS_XPLAN.DISPLAY_CURSOR(sql_id)` plus V$SQL
  runtime stats (`elapsed_time`, `cpu_time`, `buffer_gets`,
  `disk_reads`, `rows_processed`, `executions`). Bakes plan + Oracle-
  side numbers into the report. 80% of Oracle perf issues live in
  the plan.
- **Wait-event breakdown** per op from `V$SESSION_EVENT`. Tells you
  whether the time went to IO, CPU, or locks.
- **Stale-stats check** against `ALL_TAB_STATISTICS.LAST_ANALYZED`.
  Old stats = bad plans.

## Workflow

- **Before/after diff mode.** Two recordings, per-template delta.
  "You fixed template X (500 ms → 5 ms); you regressed template Y
  (50 ms → 300 ms)." Closes the loop on every optimization.
- **Search / filter** boxes in the HTML tables. Big reports (1000+
  templates, 10000+ call-sites) become unreadable without filters.
- **Operations timeline** Gantt view (spec §9). Duration per op
  laid out over wall time; reveals outliers at a glance.

## Capture coverage gaps

- **Connection-pool wait time.** Wrap `DataSource.getConnection` to
  measure acquire latency. A major category of prod slowness is
  pool contention; currently invisible.
- **Per-template p50 / p95 / p99.** Mean is useless when p99 is
  what pages oncall.
- **Slow-query section** in the report: every execute over a
  configurable threshold, listed with its full context.
- **`setAutoCommit` as a first-class captured event.** Today the
  transaction-shape analysis infers autocommit mode from the
  absence of COMMIT/ROLLBACK. Directly capturing `setAutoCommit`
  lets us draw TX boundaries precisely.

## Phase 1 / 3 debt

- **Ring drop-oldest.** `SpscRingBuffer` currently drops the newest
  event when full; spec §5.6 wants drop-oldest semantics.
- **JUnit / Servlet / Spring adapters.** Thin glue that calls
  `Profiler.currentOperation` at the right boundaries so app code
  doesn't have to.
- **Streaming drill-down** for huge recordings. Today `Model`
  holds `eventsByOp` in memory; for multi-GB recordings we want a
  second pass over the file per drill-down page.

## Other

- **JMH benchmark suite against spec §12's 5 μs / 20 μs target.**
  Current benchmark is a sanity check, not a gate. Expanding it is
  what the spec originally gated Phase 2 on.
