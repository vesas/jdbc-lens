package fi.vesas.jdbcprof.analysis;

import fi.vesas.jdbcprof.capture.Event;
import fi.vesas.jdbcprof.capture.EventType;
import fi.vesas.jdbcprof.capture.ParameterValues;
import fi.vesas.jdbcprof.capture.StackFrameSnapshot;
import fi.vesas.jdbcprof.storage.BinaryLogWriter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReportTest {

    @Test
    void writesSelfContainedHtmlWithTablesAndSummary() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-html-", ".jdbclog");
        try {
            writeRecording(tmp);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            assertThat(html)
                    .startsWith("<!DOCTYPE html>")
                    .contains("<title>jdbc-prof report:")
                    .contains("<style>")
                    .contains("<script>")
                    .contains("jdbc-prof report")
                    .contains("Top call-sites by DB time")
                    .contains("(Call-site, template) pairs")
                    .contains("<summary>Call-sites</summary>")
                    .contains("<summary>Templates</summary>")
                    .contains("com.example.OrderDao.findById:47")
                    .contains("SELECT * FROM orders WHERE id = ?")
                    .contains("INSERT INTO audit VALUES")
                    .contains("</html>");
            assertThat(html.stripTrailing()).endsWith("</html>");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void escapesHtmlSpecialCharsInSqlAndFrames() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-html-esc-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT * FROM t WHERE name = '<script>' AND id > 1"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.Dao<T>", "find", 1)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                Event[] batch = { buildEvent(1000L, 1, EventType.EXECUTE_QUERY.code(), 0, 0, 500L) };
                w.writeEvents(batch, 1);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            // Raw special chars that would break HTML should be absent.
            assertThat(html).doesNotContain("<script>'");
            // Escaped forms should be present instead.
            assertThat(html).contains("&lt;script&gt;");
            assertThat(html).contains("&gt;");
            assertThat(html).contains("com.example.Dao&lt;T&gt;");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void swimLaneRendersOneBarPerInvocation() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-html-swim-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT 1"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.Service", "run", 10)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                w.writeOpDelta(0, List.of("processOrder"));

                Event[] batch = {
                        inv(1_000L, 500L, 0L, 100L),
                        inv(2_000L, 400L, 0L, 101L),
                        inv(3_000L, 600L, 0L, 102L)
                };
                w.writeEvents(batch, batch.length);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            assertThat(html).contains("class=\"swimlanes\"");
            int bars = countOccurrences(html, "class=\"inv-bar\"");
            assertThat(bars)
                    .as("one rect per invocation")
                    .isEqualTo(3);
            assertThat(html).contains(">processOrder<");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Event inv(long ts, long duration, long opId, long invocationId) {
        Event e = buildEvent(ts, 1, EventType.EXECUTE_QUERY.code(), 0, 0, duration);
        e.operationId = opId;
        e.operationInvocationId = invocationId;
        return e;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    @Test
    void n1CardRendersLedeAndPlainLanguageLabelsAndSuggestion() throws Exception {
        // Tier-1 clarity pass for the N+1 section. A reader with no
        // knowledge of the term "N+1" should still be able to tell (a)
        // what the cards represent, (b) which frame is where the query
        // runs, (c) which frame is likely the fix, and (d) what to
        // actually do about it. The ancestor row must always render so
        // cards don't silently lose their most informative frame when
        // site == ancestor.
        Path tmp = Files.createTempFile("jdbcprof-html-n1-tier1-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT name FROM orders WHERE id = ?"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.OrderDao", "findById", 47),
                        new StackFrameSnapshot("com.example.OrderService", "loadAll", 12)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                // Default N1 threshold is 10; emit twelve to be safely over.
                Event[] batch = new Event[12];
                for (int i = 0; i < batch.length; i++) {
                    batch[i] = buildEvent(1_000L + i, 1,
                            EventType.EXECUTE_QUERY.code(), 0, 0, 5_000L);
                }
                w.writeEvents(batch, batch.length);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            int sectionStart = html.indexOf(">N+1 findings<");
            assertThat(sectionStart).as("N+1 section must exist").isGreaterThanOrEqualTo(0);
            int sectionEnd = html.indexOf("<details class=\"section\">", sectionStart + 1);
            if (sectionEnd < 0) {
                sectionEnd = html.length();
            }
            String section = html.substring(sectionStart, sectionEnd);

            assertThat(section)
                    .as("lede must explain what an N+1 finding represents")
                    .contains("loop that runs one query per row");
            assertThat(section)
                    .as("call-site row must use plain-language label")
                    .contains("query fires here");
            assertThat(section)
                    .as("ancestor row must use plain-language label")
                    .contains("outer loop (probable fix)");
            assertThat(section)
                    .as("ancestor row must render the actual ancestor frame")
                    .contains("com.example.OrderService.loadAll:12");
            assertThat(section)
                    .as("suggestion row must offer an actionable fix")
                    .contains("batched/join query");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void n1CardRendersAncestorRowEvenWhenSameAsCallSite() throws Exception {
        // A single-frame stack (or a stack where every frame above the
        // call-site is JDK/infra) produces ancestor == callSite. Today
        // that makes the ancestor row disappear with no explanation.
        // The row must still render and tell the reader why it's empty.
        Path tmp = Files.createTempFile("jdbcprof-html-n1-noancestor-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT 1"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.Solo", "main", 1)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                Event[] batch = new Event[12];
                for (int i = 0; i < batch.length; i++) {
                    batch[i] = buildEvent(1_000L + i, 1,
                            EventType.EXECUTE_QUERY.code(), 0, 0, 5_000L);
                }
                w.writeEvents(batch, batch.length);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            int sectionStart = html.indexOf(">N+1 findings<");
            int sectionEnd = html.indexOf("<details class=\"section\">", sectionStart + 1);
            if (sectionEnd < 0) {
                sectionEnd = html.length();
            }
            String section = html.substring(sectionStart, sectionEnd);

            assertThat(section).contains("outer loop (probable fix)");
            assertThat(section)
                    .as("degenerate case must be explained, not left blank")
                    .contains("same as query site");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void redundantSectionRendersLedeWithContrastToN1AndSuggestion() throws Exception {
        // Tier-1 clarity pass for the redundant-queries section: a lede
        // that names the pattern AND contrasts it with the N+1 pattern
        // above, plain-language row labels matching the N+1 section's
        // voice, and an actionable suggestion row.
        Path tmp = Files.createTempFile("jdbcprof-html-redundant-clarity-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT email FROM customers WHERE id = ?"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.CustomerDao", "findEmail", 33)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                w.writeOpDelta(0, List.of("checkout"));
                w.writeParamValuesDelta(0, List.of(new ParameterValues(List.of("42"))));

                long fp = 0xCAFEBABECAFEBABEL;
                Event[] batch = {
                        redundant(1_000L, 500L, 0L, 100L, 0, 0, fp, 0),
                        redundant(2_000L, 500L, 0L, 100L, 0, 0, fp, 0),
                        redundant(3_000L, 500L, 0L, 100L, 0, 0, fp, 0)
                };
                w.writeEvents(batch, batch.length);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            int sectionStart = html.indexOf(">Redundant queries<");
            assertThat(sectionStart).isGreaterThanOrEqualTo(0);
            int sectionEnd = html.indexOf("<details class=\"section\">", sectionStart + 1);
            if (sectionEnd < 0) {
                sectionEnd = html.length();
            }
            String section = html.substring(sectionStart, sectionEnd);

            assertThat(section)
                    .as("lede must explain what 'redundant' means here")
                    .contains("identical")
                    .contains("logical operation");
            assertThat(section)
                    .as("lede must contrast with N+1 so the reader can place the finding")
                    .contains("N+1");
            assertThat(section)
                    .as("call-site row must use plain-language label matching N+1 voice")
                    .contains("query fires here");
            assertThat(section)
                    .as("operation row must read as 'inside operation <name>'")
                    .contains("inside operation");
            assertThat(section)
                    .as("suggestion row must offer an actionable fix")
                    .contains("Cache the result");
            assertThat(section)
                    .as("bound values row label should be 'parameters', not 'params'")
                    .contains("<dt>parameters</dt>");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void redundantFindingMustReflectValuesOfEveryAggregatedEvent() throws Exception {
        // The redundant-queries section displays one set of bound values
        // per finding. The reader treats those as the values used by all
        // N aggregated executions. That assumption holds only if
        // fingerprint uniquely identifies value identity. A 64-bit hash
        // can collide, and fingerprint composition could regress; today
        // Model.load stores the first parameterValuesId it sees for each
        // Key via putIfAbsent and silently drops any later divergent id.
        // This test records three events under the same
        // (opId, sqlId, fingerprint, stackId) but with two different
        // parameterValuesIds pointing to different display values, and
        // insists the rendered report does not lie about what was bound.
        Path tmp = Files.createTempFile("jdbcprof-html-redundant-values-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT name FROM customers WHERE id = ?"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.CustomerDao", "findById", 42)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                w.writeOpDelta(0, List.of("checkout"));
                w.writeParamValuesDelta(0, List.of(
                        new ParameterValues(List.of("67270")),
                        new ParameterValues(List.of("99999"))));

                long fp = 0xDEADBEEFCAFEBABEL;
                Event[] batch = {
                        redundant(1_000L, 500L, 0L, 100L, 0, 0, fp, 0),
                        redundant(2_000L, 500L, 0L, 100L, 0, 0, fp, 0),
                        redundant(3_000L, 500L, 0L, 100L, 0, 0, fp, 1)
                };
                w.writeEvents(batch, batch.length);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            assertThat(html)
                    .as("the divergent bound value must appear somewhere in the report "
                            + "so the reader is not misled into thinking all aggregated "
                            + "executions used the first event's values")
                    .contains("99999");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void redundantFindingParamDisplayMatchesACapturedValueSet() throws Exception {
        // Baseline: when every event under the same Key genuinely shares
        // the same parameterValuesId, that set of display strings must
        // show up intact in the redundant-queries section. If this test
        // ever fails, something is mis-threading the values through the
        // Model and the renderer would be making up numbers. Tight
        // positive assertion so the negative test above doesn't pass
        // vacuously (e.g., by the renderer dumping every captured
        // paramValues regardless of which finding owns it).
        Path tmp = Files.createTempFile("jdbcprof-html-redundant-match-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of("SELECT email FROM customers WHERE id = ?"));
                StackFrameSnapshot[] frames = {
                        new StackFrameSnapshot("com.example.CustomerDao", "findEmail", 33)
                };
                w.writeStackDelta(0, Collections.singletonList(frames));
                w.writeOpDelta(0, List.of("checkout"));
                w.writeParamValuesDelta(0, List.of(
                        new ParameterValues(List.of("67270"))));

                long fp = 0x1234567890ABCDEFL;
                Event[] batch = {
                        redundant(1_000L, 500L, 0L, 100L, 0, 0, fp, 0),
                        redundant(2_000L, 500L, 0L, 100L, 0, 0, fp, 0),
                        redundant(3_000L, 500L, 0L, 100L, 0, 0, fp, 0)
                };
                w.writeEvents(batch, batch.length);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            int redundantHeader = html.indexOf(">Redundant queries<");
            assertThat(redundantHeader)
                    .as("redundant queries section should exist")
                    .isGreaterThanOrEqualTo(0);
            int nextSectionAt = html.indexOf("<details class=\"section\">",
                    redundantHeader + 1);
            if (nextSectionAt < 0) {
                nextSectionAt = html.length();
            }
            String redundantSection = html.substring(redundantHeader, nextSectionAt);
            assertThat(redundantSection)
                    .as("captured value 67270 must appear inside the redundant finding")
                    .contains("67270");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Event redundant(long ts, long duration, long opId, long invocationId,
                                   int sqlId, int stackId, long fingerprint,
                                   int parameterValuesId) {
        Event e = buildEvent(ts, 1, EventType.EXECUTE_QUERY.code(), sqlId, stackId, duration);
        e.operationId = opId;
        e.operationInvocationId = invocationId;
        e.parameterFingerprint = fingerprint;
        e.parameterValuesId = parameterValuesId;
        return e;
    }

    @Test
    void emulatedCursorSectionFlagsNestedMasterDetailPair() throws Exception {
        // End-to-end: craft a recording that mirrors the transpiled
        // master/detail shape from the cobol sample (walk + fetch on
        // CUSTOMERS inside run(); walk + fetch on ORDERS inside
        // processDetailRecords(), itself called from run()). The
        // Emulated cursors section must appear and mark the inner
        // pair as nested.
        Path tmp = Files.createTempFile("jdbcprof-html-emulcursor-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of(
                        "SELECT MIN(ID) FROM CUSTOMERS WHERE ID > ?",
                        "SELECT ID, NAME FROM CUSTOMERS WHERE ID = ?",
                        "SELECT MIN(ID) FROM ORDERS WHERE CUSTOMER_ID = ? AND ID > ?",
                        "SELECT ID, AMOUNT, STATUS FROM ORDERS WHERE ID = ?"));
                StackFrameSnapshot[] masterWalk = {
                        new StackFrameSnapshot("com.example.MergeJob", "readNextMaster", 10),
                        new StackFrameSnapshot("com.example.MergeJob", "run", 5)
                };
                StackFrameSnapshot[] masterFetch = {
                        new StackFrameSnapshot("com.example.MergeJob", "readMasterFields", 20),
                        new StackFrameSnapshot("com.example.MergeJob", "run", 5)
                };
                StackFrameSnapshot[] detailWalk = {
                        new StackFrameSnapshot("com.example.MergeJob", "readNextDetail", 30),
                        new StackFrameSnapshot("com.example.MergeJob", "processDetailRecords", 25),
                        new StackFrameSnapshot("com.example.MergeJob", "run", 5)
                };
                StackFrameSnapshot[] detailFetch = {
                        new StackFrameSnapshot("com.example.MergeJob", "readDetailFields", 40),
                        new StackFrameSnapshot("com.example.MergeJob", "processDetailRecords", 25),
                        new StackFrameSnapshot("com.example.MergeJob", "run", 5)
                };
                w.writeStackDelta(0, List.of(masterWalk, masterFetch, detailWalk, detailFetch));

                // 10 master walks/fetches, 40 detail walks/fetches.
                Event[] batch = new Event[100];
                int idx = 0;
                for (int i = 0; i < 10; i++) {
                    batch[idx++] = buildEvent(1_000L + i, 1,
                            EventType.EXECUTE_QUERY.code(), 0, 0, 500L);
                    batch[idx++] = buildEvent(2_000L + i, 1,
                            EventType.EXECUTE_QUERY.code(), 1, 1, 500L);
                }
                for (int i = 0; i < 40; i++) {
                    batch[idx++] = buildEvent(3_000L + i, 1,
                            EventType.EXECUTE_QUERY.code(), 2, 2, 500L);
                    batch[idx++] = buildEvent(4_000L + i, 1,
                            EventType.EXECUTE_QUERY.code(), 3, 3, 500L);
                }
                w.writeEvents(batch, idx);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            int sectionStart = html.indexOf(">Emulated cursors (COBOL READ NEXT)<");
            assertThat(sectionStart)
                    .as("Emulated cursors section must exist")
                    .isGreaterThanOrEqualTo(0);
            int sectionEnd = html.indexOf("<details class=\"section\">", sectionStart + 1);
            if (sectionEnd < 0) {
                sectionEnd = html.length();
            }
            String section = html.substring(sectionStart, sectionEnd);

            assertThat(section)
                    .as("lede must name the transpile shape so readers can place it")
                    .contains("COBOL")
                    .contains("READ NEXT");
            assertThat(section)
                    .as("at least one card must carry the nested badge for the orders pair")
                    .contains(">nested<");
            assertThat(section)
                    .as("inner pair must point at processDetailRecords as its outer method")
                    .contains("processDetailRecords");
            assertThat(section)
                    .as("inner pair must name the enclosing outer cursor (run)")
                    .contains("nested in");
            assertThat(section)
                    .as("suggestion must recommend the set-oriented rewrite for nested pairs")
                    .contains("GROUP BY");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void transactionsSectionFlagsCommitPerRecordRun() throws Exception {
        // End-to-end: a transpiled-COBOL-style workload that commits
        // after every record must produce a Transactions section with
        // the commit-per-record finding naming the outer method as
        // the common ancestor.
        Path tmp = Files.createTempFile("jdbcprof-html-txns-", ".jdbclog");
        try {
            try (BinaryLogWriter w = new BinaryLogWriter(tmp)) {
                w.writeSqlDelta(0, List.of(
                        "UPDATE customers SET name = ?, country = ? WHERE id = ?"));
                StackFrameSnapshot[] innerFrames = {
                        new StackFrameSnapshot(
                                "fi.vesas.jdbcprof.sample.cobol.CustomerMasterBatchJob",
                                "rewriteMasterRecord", 107),
                        new StackFrameSnapshot(
                                "fi.vesas.jdbcprof.sample.cobol.CustomerMasterBatchJob",
                                "run", 43)
                };
                StackFrameSnapshot[] commitFrames = {
                        new StackFrameSnapshot(
                                "fi.vesas.jdbcprof.sample.cobol.CustomerMasterBatchJob",
                                "checkpointRecord", 127),
                        new StackFrameSnapshot(
                                "fi.vesas.jdbcprof.sample.cobol.CustomerMasterBatchJob",
                                "run", 43)
                };
                w.writeStackDelta(0, List.of(innerFrames, commitFrames));
                w.writeOpDelta(0, List.of("customer-master-batch"));

                // 15 back-to-back TXs: UPDATE + COMMIT each, same thread.
                int count = 15;
                Event[] batch = new Event[count * 2];
                long ts = 1_000_000L;
                for (int i = 0; i < count; i++) {
                    Event update = buildEvent(ts, 1,
                            EventType.EXECUTE_UPDATE.code(), 0, 0, 500_000L);
                    update.operationId = 0L;
                    update.operationInvocationId = 100L;
                    Event commit = buildEvent(ts + 1_000_000L, 1,
                            EventType.COMMIT.code(), -1, 1, 500_000L);
                    commit.operationId = 0L;
                    commit.operationInvocationId = 100L;
                    batch[i * 2] = update;
                    batch[i * 2 + 1] = commit;
                    ts += 3_000_000L;
                }
                w.writeEvents(batch, batch.length);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);

            int sectionStart = html.indexOf(">Transactions<");
            assertThat(sectionStart)
                    .as("Transactions section must exist")
                    .isGreaterThanOrEqualTo(0);
            int sectionEnd = html.indexOf("<details class=\"section\">", sectionStart + 1);
            if (sectionEnd < 0) {
                sectionEnd = html.length();
            }
            String section = html.substring(sectionStart, sectionEnd);

            assertThat(section)
                    .as("overview must show the explicit-TX count")
                    .contains("explicit transactions")
                    .contains(">15<");
            assertThat(section)
                    .as("commit-per-record finding must appear")
                    .contains("Commit-per-record runs");
            assertThat(section)
                    .as("finding card must name the outer method as common ancestor")
                    .contains("CustomerMasterBatchJob.run:43");
            assertThat(section)
                    .as("card must link to the inner call-site of the loop body")
                    .contains("rewriteMasterRecord");
            assertThat(section)
                    .as("suggestion must recommend widening the TX boundary")
                    .contains("Widen the transaction boundary");
            assertThat(section)
                    .as("longest-transactions table must list TXs from this workload")
                    .contains("Longest transactions")
                    .contains("customer-master-batch");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void sortSortablesCarryRawNumericAttributes() throws Exception {
        Path tmp = Files.createTempFile("jdbcprof-html-sort-", ".jdbclog");
        try {
            writeRecording(tmp);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                HtmlReport.write(tmp, ps);
            }
            String html = bytes.toString(StandardCharsets.UTF_8);
            // Numeric cells must expose the raw value so the client-side
            // sort compares ns, not the "82.5 ms" formatted string.
            assertThat(html).contains("data-raw=\"");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeRecording(Path path) throws Exception {
        try (BinaryLogWriter w = new BinaryLogWriter(path)) {
            w.writeSqlDelta(0, List.of(
                    "SELECT * FROM orders WHERE id = ?",
                    "INSERT INTO audit VALUES (?, ?)"));
            StackFrameSnapshot[] frames = {
                    new StackFrameSnapshot("com.example.OrderDao", "findById", 47),
                    new StackFrameSnapshot("com.example.OrderService", "load", 12)
            };
            w.writeStackDelta(0, Collections.singletonList(frames));
            Event[] batch = {
                    buildEvent(1000L, 42, EventType.PREPARE.code(), 0, 0, 12_340L),
                    buildEvent(2234L, 42, EventType.EXECUTE_QUERY.code(), 0, 0, 45_678L),
                    buildEvent(3500L, 42, EventType.EXECUTE_UPDATE.code(), 1, 0, 2_000L)
            };
            w.writeEvents(batch, batch.length);
        }
    }

    private static Event buildEvent(long ts, int thread, byte kind,
                                    int sqlId, int stackId, long duration) {
        Event e = new Event();
        e.timestampNanos = ts;
        e.threadId = thread;
        e.eventType = kind;
        e.sqlId = sqlId;
        e.stackTraceId = stackId;
        e.durationNanos = duration;
        e.rowsAffected = -1;
        e.batchSize = 0;
        return e;
    }
}
