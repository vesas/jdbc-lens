# jdbc-lens — ideas backlog

Parking lot for features and fixes that were discussed but not yet
built. Grouped by the kind of user problem they solve. The spec
(`jdbc-callsite-profiler-spec.md`) is still the source of truth for
anything spec-shaped; entries here are either app-side analyses that
go beyond the spec, or spec items we haven't hit yet.

## Highest signal for large legacy / COBOL-converted apps

These codebases have characteristic shapes — copybook duplication
(the same SELECT written 5–20 different ways), `REWRITE RECORD`
semantics (READ-then-UPDATE-all-columns per row), `PERFORM` loops
translated as row-by-row cursor iteration, poor statement reuse,
no caching culture, flat procedural ops. The analyses that would
surface the most actionable refactoring in that context are, in
order of expected impact:

1. **Template equivalence clustering** — groups textually-different
   templates that do the same work. One cluster often collapses 15
   modules into a shared helper.
2. **Op-level query fingerprint** — finds duplicated logic across
   code paths. COBOL-to-Java conversions frequently ship the same
   procedure implemented in many places because the original COBOL
   had many copies.
3. **Master-detail cursor loops** — the PERFORM-loop shape. Almost
   always rewriteable as a JOIN or bulk `MERGE`.
4. **Read-then-write of the same row** + **Over-wide UPDATE** —
   together, these pinpoint the `REWRITE RECORD` anti-pattern.
5. **Cross-op reference-data fetches** — every legacy app hits the
   same settings / lookup tables thousands of times. Biggest easy
   win.

The rest of the backlog below includes these plus more general
ideas that matter less in a legacy context (chatty-service, lock-
holding-across-app-work, transaction sandwich — all assume OO
services and Spring-style transactional boundaries the legacy code
usually doesn't have).

## App-side: tell the user *what to change*

### Locality & co-access

- **Adjacent-call-site table co-touch.** Two consecutive events in
  the same op from *different* call-sites but the same table. If
  the pattern repeats across ops, those two call-sites are
  practically joined at the hip — make them share state, or have
  one fetch for both.
- **Table co-touch graph.** Aggregate "tables touched together
  inside the same op within Xms" into a graph. Nodes = tables,
  edges = co-access frequency. Clusters that the service layout
  doesn't match = the service boundaries are wrong.
  `customers + orders + shipping_address` co-touch on every
  checkout → one read model, not three services each fetching
  their piece.
- **Table-pair N+1.** One SELECT on table A returning N rows,
  followed by N SELECTs on table B keyed by A's results. We flag
  template N+1 today but not the cross-table shape — it's the one
  most often fixable with a JOIN.
- **Cross-reference entity across identifier columns.** Current
  entity-access audit keys on `(table, column, value)`. Real
  identity is "the same row" — e.g., `orders.id = 42` and
  `orders WHERE customer_id = X ORDER BY id DESC LIMIT 1` can
  return the same row under different keys. Needs result-set
  value tracking to correlate.
- **Same-record across tables in one op.** Within one operation,
  the same key value (e.g., `42`) appears as a bound parameter in
  queries against multiple tables — `customers WHERE id=42`,
  `orders WHERE customer_id=42`, `audit WHERE actor_id=42`.
  Classic "process one record end-to-end" procedural shape.
  Strong candidate for a stored procedure or a single
  transactional method that does the whole thing instead of
  shuttling the same id through several app-layer calls.

### Wasted-work patterns

- **Read-then-write of the same row.** `SELECT … WHERE id = ?`
  followed by `UPDATE … WHERE id = ?` in the same op on the same
  key. Often collapsible to a single `UPDATE … RETURNING` or an
  `UPDATE` with computed fields.
- **Write-then-read of the same row.** `UPDATE … SET x=? WHERE id=?`
  in an op, followed by `SELECT … WHERE id=?` returning data the
  app just wrote. Extra round trip.
- **Dead writes.** `EXECUTE_UPDATE` events with `rowsAffected = 0`.
  Almost always a bug in the WHERE clause or a redundant no-op.
  We already capture rowsAffected — this is a one-line analysis.
- **Nil reads.** `EXECUTE_QUERY` with zero `NEXT` events returning
  a row. "Does it exist" lookups that could be replaced with a
  boolean flag, a cache, or a presence index.
- **Cross-op reference-data fetches.** Same (template, fingerprint)
  pair fires across many ops. Settings, feature flags, lookups —
  classic cache candidates that should live in memory, not on the
  wire.
- **Cursor over-fetch.** For each `EXECUTE_QUERY`, count `NEXT`
  events. A query fetching 10,000 rows in an op that doesn't need
  them all is a missing `LIMIT` / pagination. Weakest signal (we
  can't see what the app did with the rows) but high-fetch queries
  are always worth a look.
- **Prepared-statement reuse ratio.** Per template, count(PREPARE)
  vs count(EXECUTE*). Ratio near 1 means the app re-prepares every
  call — Oracle does a hard parse per call. Flag low-reuse
  templates and link to the statement-cache remediation.
- **Over-wide UPDATE (`REWRITE RECORD` smell).**
  `UPDATE t SET c1=?, c2=?, …, cN=? WHERE id=?` with N above a
  threshold (say 8). The legacy REWRITE-whole-record translation —
  every column gets overwritten regardless of what actually
  changed. Wastes write bandwidth, fires triggers / CDC /
  replication for no-op changes, and masks real diffs in audit
  logs that depend on "column changed" detection.
- **Master-detail cursor loops.** A long-running
  `EXECUTE_QUERY` (the "master" cursor, many `NEXT` events) with
  secondary `EXECUTE_QUERY` / `EXECUTE_UPDATE` on other tables
  interleaved between the NEXTs, keyed off the master's rows.
  Textbook COBOL master-file + detail-file translation into
  Java. Almost always collapsible to a `JOIN` or a bulk `MERGE`.

### Sequence patterns

- **N-gram access-sequence mining.** Sliding window over each
  op's event stream: find ordered sequences of template ids (n=2,
  3, 5, 7…) that recur across many ops. Each recurring n-gram is
  a helper / stored procedure / consolidated query candidate.
  Especially strong in legacy codebases where the same 5-7 query
  boilerplate is inlined in dozens of modules because the COBOL
  original was copied-and-tweaked rather than abstracted.
- **Op-level query fingerprint.** Hash the ordered sequence of
  template ids per op into one value. Two ops with the same
  fingerprint do the same DB work from different code paths —
  duplicated endpoints / duplicated logic. In large legacy apps
  this finds modules that implement the same workflow under
  different names because the original code got copied and
  half-renamed during conversion. Near-matches (one template
  different) are also worth flagging — often the only real
  difference between two ops is which audit table they write.
- **Ping-pong chains.** Query A returns value V → query B keyed by
  V returns W → query C keyed by W. A sequential fetch chain is
  almost always collapsible with a join or correlated subquery.

### Transactional / structural

- **Lock holding across app work.** `SELECT … FOR UPDATE` followed
  by a long gap (no DB activity) before the matching `UPDATE` /
  `COMMIT`. A row lock held while the application thinks.
- **Transaction sandwich.** Reads at the top of a TX, writes in
  the middle, reads at the bottom. Outer reads almost always
  belong outside the TX.
- **Connection hoarding.** Connection open for the whole op but
  queries cluster at one end — acquired too early or held too
  long. Acquire just-in-time.
- **Chatty service.** One op fires >K distinct templates from the
  same service class. Suggests the service has too many reasons
  to exist; split.

### Cross-op / structural

- **Write amplification per op.** Number of writes per user-
  visible action. 50 writes in one op is almost always event
  proliferation or over-normalization.
- **Template equivalence clustering.** Group templates that do
  semantically the same work despite textual differences —
  whitespace, alias names, column order in a SELECT list, inline
  subquery vs JOIN, uppercase vs lowercase keywords. In legacy
  codebases the same SELECT is often written 10–20 textually
  different ways because copybooks shared field definitions but
  not code. Each cluster is a consolidation target — a shared
  helper or DAO method waiting to be extracted.
- **Column-subset subsumption.** Special case of the above:
  `SELECT a, b, c FROM t WHERE id=?` strictly contains
  `SELECT a FROM t WHERE id=?` on the same row. The narrower
  one can be dropped outright.
- **Query diversity per table.** Distinct templates touching
  table X. Very high count → probably needs a read model. Very
  low count + high row counts → that one query is doing too much.
- **Stack-path duplication.** Group distinct stacks that end up
  doing the same DB work — hidden duplication across the
  codebase.

### Recommendations & simulators

- **N+1 → concrete JOIN suggestion.** Pair each N+1 finding with
  its parent query (previous query on the same thread returning N
  rows) and emit a replacement SQL snippet: `SELECT p.*, c.name
  FROM parent p JOIN child c ON …`, or a batched
  `WHERE id IN (?,?,…)`. Heuristic; extremely useful as a
  first-draft patch.
- **What-if savings simulator.** For each N+1 / redundant /
  read-then-write finding, compute expected savings: "batching
  saves ~N-1 round-trips, estimated K ms per invocation, M ms
  total over the recording." Makes the ROI of each fix numeric.
- **App-time vs DB-time split per op.** Pie / bar of: EXECUTE
  durations vs NEXT durations vs inferred app-time gaps. Reveals
  where the wall time actually goes. If app time dominates, DB
  isn't your problem. If NEXT dominates, fetch fewer rows.
- **Rule-based recommendations engine.** Combine detected
  patterns into a single "Actions" view: for each finding, show
  the suggested code change as a copy-pastable diff.

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
