/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */
package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.carddemo.exception.AbendException;
import com.carddemo.service.StatementDataAccessService.StatementFileRequest;
import com.carddemo.service.StatementDataAccessService.StatementFileResponse;
import com.carddemo.util.TransactionRecordMapper;

/**
 * Behavioural suite over {@link StatementGenerationService}, the translation of
 * {@code [app/cbl/CBSTM03A.CBL]} - 924 lines, 25 paragraphs.
 *
 * <p>Legacy provenance: AWS CardDemo z/OS estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <h2>What this suite is for</h2>
 *
 * <p>The service under test is a hand-rolled state machine rather than a loop, and the property that
 * matters most about it cannot be observed from its output alone: the dispatcher at
 * {@code [app/cbl/CBSTM03A.CBL:L296]} must be <em>re-entered</em> after every state change, because four
 * of the source's backward jumps target it. A translation that folded each phase into the phase that
 * triggered it would produce the same records for the same input and still be wrong, so the first test
 * below asserts the observed phase sequence rather than the records. That is the decisive structural
 * assertion in this file.
 *
 * <p>The remaining tests guard the behaviours a well-meaning tidy-up would most likely destroy: the
 * initial phase, the three arms of the inner read loop, the two accepted statuses of the guard-form
 * sites against the single accepted status of the selection-form reads, the two record widths measured
 * in <em>encoded bytes</em>, the two banners' different padding splits, the duplicated and triplicated
 * rule-line writes, the delimiter of two adjacent spaces, the absence of the populated-but-never-written
 * group, and the blanking of the payload before every call.
 *
 * <h2>How the collaborator is driven</h2>
 *
 * <p>{@code StatementDataAccessService} is replaced by a scripted stand-in that behaves like the four
 * files: sequential reads advance a position and end with the at-end status, keyed reads answer from a
 * fixed image, and opens and closes report a parameterised status. The abend service is the real one, so
 * an abend really does log and raise rather than being asserted against a mock.
 *
 * <p>Record images come from the committed fixtures under {@code src/test/resources/fixtures/input},
 * which are the byte-accurate sequential datasets of the legacy estate, and the last three tests compare
 * what the service emits against the golden expected-output files under
 * {@code src/test/resources/fixtures/expected}. Only the card-number field of a transaction image is
 * rewritten, so that the tabulated card matches the cross-reference record the statement is built for.
 */
@DisplayName("StatementGenerationService :: the statement state machine, its two record widths "
        + "and the oddities that must survive")
class StatementGenerationServiceTest {

    private static final String STATUS_OK = "00";
    private static final String STATUS_LENGTH = "04";
    private static final String STATUS_EOF = "10";
    private static final String STATUS_BAD = "31";
    private static final int PAYLOAD = StatementDataAccessService.PAYLOAD_WIDTH;

    /** Frozen source identity threaded through every data-access call in this service-level suite. */
    private static final StatementTransactionSource TRANSACTION_SOURCE =
            position -> java.util.Optional.empty();

    private static final UnaryOperator<String> REVEALER = envelope -> {
        if (envelope != null && envelope.startsWith("ENC1:")) {
            return new String(Base64.getDecoder().decode(envelope.substring(5)),
                    StandardCharsets.US_ASCII).trim();
        }
        return envelope;
    };

    private static final UnaryOperator<String> SEALER = cleartext -> {
        byte[] padded = new byte[32];
        byte[] raw = (cleartext == null ? "" : cleartext).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, padded.length));
        for (int i = raw.length; i < padded.length; i++) {
            padded[i] = ' ';
        }
        return "ENC1:" + Base64.getEncoder().encodeToString(padded);
    };

    private static List<String> fixture(String name) {
        try (InputStream in = StatementGenerationServiceTest.class
                .getResourceAsStream("/fixtures/input/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }

    private static String pad(String value, int width) {
        StringBuilder builder = new StringBuilder(width);
        builder.append(value);
        while (builder.length() < width) {
            builder.append(' ');
        }
        builder.setLength(width);
        return builder.toString();
    }

    /**
     * Overwrites a fixed-width slice of a fixture image.
     *
     * <p>Used only to shape an input: the card-number field of a transaction image is rewritten so that
     * the tabulated card matches the cross-reference record the statement is built for. Nothing about the
     * service under test performs slicing of its own.
     *
     * @param image  the record image
     * @param offset zero-based byte offset of the field
     * @param value  the replacement value, exactly as wide as the field
     * @return the rewritten image
     */
    private static String overwrite(String image, int offset, String value) {
        StringBuilder builder = new StringBuilder(image);
        builder.replace(offset, offset + value.length(), value);
        return builder.toString();
    }

    /**
     * A scripted stand-in for the four files the statement feature reads.
     *
     * <p>Sequential reads answer from a list at the position the parameter object carries and advance it,
     * ending with the at-end status once the list is exhausted; keyed reads answer from a fixed image;
     * opens and closes report the status the test parameterised. Every request is recorded so a test can
     * assert what the service asked for as well as what it produced.
     */
    private static final class Script {

        private final List<StatementFileRequest> requests = new ArrayList<>();
        private final List<String> trnxImages;
        private final List<String> xrefImages;
        private final String custImage;
        private final String acctImage;
        private final String acceptedStatus;
        private final int badTrnxReadOrdinal;
        private int trnxReads;

        Script(List<String> trnxImages, List<String> xrefImages, String custImage, String acctImage,
                String acceptedStatus, int badTrnxReadOrdinal) {
            this.trnxImages = trnxImages;
            this.xrefImages = xrefImages;
            this.custImage = custImage;
            this.acctImage = acctImage;
            this.acceptedStatus = acceptedStatus;
            this.badTrnxReadOrdinal = badTrnxReadOrdinal;
        }

        StatementFileResponse answer(StatementFileRequest request) {
            requests.add(request);
            String dd = request.ddName();
            if (request.hasOperation(StatementDataAccessService.OPERATION_OPEN)
                    || request.hasOperation(StatementDataAccessService.OPERATION_CLOSE)) {
                return new StatementFileResponse(dd, acceptedStatus, pad("", PAYLOAD), 0);
            }
            int position = request.sequentialPosition();
            if (dd.equals(StatementDataAccessService.DD_TRNXFILE)) {
                int ordinal = trnxReads++;
                if (ordinal == badTrnxReadOrdinal) {
                    return new StatementFileResponse(dd, STATUS_BAD, request.payload(), position);
                }
                // The priming read at L748 is guard-form and accepts 00 or 04; the loop reads at L837
                // are selection-form and accept 00 alone, so only the priming read carries the
                // parameterised status.
                String status = ordinal == 0 ? acceptedStatus : STATUS_OK;
                if (position < trnxImages.size()) {
                    return new StatementFileResponse(dd, status, pad(trnxImages.get(position), PAYLOAD),
                            position + 1);
                }
                return new StatementFileResponse(dd, STATUS_EOF, request.payload(), position);
            }
            if (dd.equals(StatementDataAccessService.DD_XREFFILE)) {
                if (position < xrefImages.size()) {
                    return new StatementFileResponse(dd, STATUS_OK,
                            pad(xrefImages.get(position), PAYLOAD), position + 1);
                }
                return new StatementFileResponse(dd, STATUS_EOF, request.payload(), position);
            }
            if (dd.equals(StatementDataAccessService.DD_CUSTFILE)) {
                return new StatementFileResponse(dd, STATUS_OK, pad(custImage, PAYLOAD), position);
            }
            if (dd.equals(StatementDataAccessService.DD_ACCTFILE)) {
                return new StatementFileResponse(dd, STATUS_OK, pad(acctImage, PAYLOAD), position);
            }
            throw new AssertionError("unexpected DD name " + dd);
        }
    }

    /**
     * A service wired to a scripted collaborator, together with that script.
     *
     * @param service the service under test
     * @param script  the script it reads through, for asserting on the requests it made
     */
    private record Harness(StatementGenerationService service, Script script) { }

    private static Harness harness(String acceptedStatus, int badTrnxReadOrdinal,
            int transactionCount) {
        String xref36 = fixture("cardxref.txt").get(0);
        String cardNumber = xref36.substring(0, 16);
        String xref50 = pad(xref36, 50);
        List<String> dailytran = fixture("dailytran.txt");
        List<String> trnxImages = new ArrayList<>();
        for (int index = 0; index < transactionCount; index++) {
            trnxImages.add(TransactionRecordMapper.projectStatementWorkRecord(
                    overwrite(dailytran.get(index), 262, cardNumber)));
        }
        String custImage = fixture("custdata.txt").get(0);
        String acctImage = fixture("acctdata.txt").get(0);
        Script script = new Script(trnxImages, List.of(xref50), custImage, acctImage, acceptedStatus,
                badTrnxReadOrdinal);
        StatementDataAccessService dataAccess = Mockito.mock(StatementDataAccessService.class);
        Mockito.when(dataAccess.execute(Mockito.any(), Mockito.same(TRANSACTION_SOURCE)))
                .thenAnswer(invocation -> script.answer(invocation.getArgument(0)));
        return new Harness(new StatementGenerationService(dataAccess, new AbendService()), script);
    }

    @Test
    @DisplayName("the dispatcher is re-entered after every state change, and its six clauses run in source order")
    void dispatchSequence() {
        Harness harness = harness(STATUS_OK, -1, 2);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        assertThat(run.dispatchedPhases()).containsExactly("TRNXFILE", "READTRNX", "XREFFILE",
                "CUSTFILE", "ACCTFILE", "TERMINATED");
    }

    @Test
    @DisplayName("the initial state is the transaction-file phase the field is initialised to")
    void initialState() {
        Harness harness = harness(STATUS_OK, -1, 1);
        assertThat(harness.service()
                .generate(TRANSACTION_SOURCE, REVEALER, SEALER).dispatchedPhases().get(0))
                .isEqualTo("TRNXFILE");
    }

    @Test
    @DisplayName("the inner read loop iterates on success and leaves the phase at end of file")
    void innerLoopIteratesAndExits() {
        Harness harness = harness(STATUS_OK, -1, 5);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        assertThat(run.cardsTabulated()).isEqualTo(1);
        assertThat(run.transactionsTabulated()).isEqualTo(5);
        assertThat(run.transactionSummaries())
                .hasSize(5)
                .hasOnlyElementsOfType(StatementLineSummary.class)
                .extracting(StatementLineSummary::transactionId)
                .containsExactlyElementsOf(fixture("dailytran.txt").subList(0, 5).stream()
                        .map(image -> image.substring(0, 16))
                        .toList());
        assertThat(run.transactionSummaries())
                .extracting(StatementLineSummary::cardNumber)
                .containsOnly(fixture("cardxref.txt").get(0).substring(0, 16));
        assertThat(run.statementsWritten()).isEqualTo(1);
    }

    @Test
    @DisplayName("the inner read loop abends on any status that is neither success nor end of file")
    void innerLoopAbends() {
        Harness harness = harness(STATUS_OK, 2, 5);
        assertThatThrownBy(() ->
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER))
                .isInstanceOf(AbendException.class);
    }

    @Test
    @DisplayName("the record-length status is accepted at every open, every close and the priming read")
    void recordLengthMismatchAccepted() {
        Harness harness = harness(STATUS_LENGTH, -1, 2);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        assertThat(run.statementsWritten()).isEqualTo(1);
        assertThat(run.transactionsTabulated()).isEqualTo(2);
    }

    @Test
    @DisplayName("the record-length status is not accepted on an inner-loop read, because that test is selection-form")
    void recordLengthMismatchRejectedInLoop() {
        String xref36 = fixture("cardxref.txt").get(0);
        String cardNumber = xref36.substring(0, 16);
        List<String> dailytran = fixture("dailytran.txt");
        List<String> trnxImages = List.of(
                TransactionRecordMapper.projectStatementWorkRecord(
                        overwrite(dailytran.get(0), 262, cardNumber)),
                TransactionRecordMapper.projectStatementWorkRecord(
                        overwrite(dailytran.get(1), 262, cardNumber)));
        Script script = new Script(trnxImages, List.of(pad(xref36, 50)),
                fixture("custdata.txt").get(0), fixture("acctdata.txt").get(0), STATUS_OK, -1);
        StatementDataAccessService dataAccess = Mockito.mock(StatementDataAccessService.class);
        Mockito.when(dataAccess.execute(Mockito.any(), Mockito.same(TRANSACTION_SOURCE)))
                .thenAnswer(invocation -> {
            StatementFileRequest request = invocation.getArgument(0);
            StatementFileResponse response = script.answer(request);
            boolean loopRead = request.hasOperation(StatementDataAccessService.OPERATION_READ)
                    && request.ddName().equals(StatementDataAccessService.DD_TRNXFILE)
                    && script.trnxReads > 1;
            return loopRead
                    ? new StatementFileResponse(response.ddName(), STATUS_LENGTH, response.payload(),
                            response.sequentialPosition())
                    : response;
                });
        StatementGenerationService service =
                new StatementGenerationService(dataAccess, new AbendService());
        assertThatThrownBy(() -> service.generate(TRANSACTION_SOURCE, REVEALER, SEALER))
                .isInstanceOf(AbendException.class);
    }

    @Test
    @DisplayName("every plain record is 80 encoded bytes and every HTML record is 100")
    void recordWidths() {
        Harness harness = harness(STATUS_OK, -1, 3);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        assertThat(run.statementRecords()).isNotEmpty()
                .allSatisfy(record -> assertThat(record.getBytes(StandardCharsets.US_ASCII))
                        .hasSize(80));
        assertThat(run.htmlRecords()).isNotEmpty()
                .allSatisfy(record -> assertThat(record.getBytes(StandardCharsets.US_ASCII))
                        .hasSize(100));
    }

    @Test
    @DisplayName("the two banners keep their different padding splits, 31/18/31 and 32/16/32")
    void bannerSplits() {
        Harness harness = harness(STATUS_OK, -1, 2);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        String start = run.statementRecords().get(0);
        assertThat(start.substring(0, 31)).isEqualTo("*".repeat(31));
        assertThat(start.substring(31, 49)).isEqualTo("START OF STATEMENT");
        assertThat(start.substring(49, 80)).isEqualTo("*".repeat(31));
        String end = run.statementRecords().get(run.statementRecords().size() - 1);
        assertThat(end.substring(0, 32)).isEqualTo("*".repeat(32));
        assertThat(end.substring(32, 48)).isEqualTo("END OF STATEMENT");
        assertThat(end.substring(48, 80)).isEqualTo("*".repeat(32));
    }

    @Test
    @DisplayName("the duplicated and triplicated rule-line writes survive, in their source positions")
    void repeatedWrites() {
        Harness harness = harness(STATUS_OK, -1, 2);
        List<String> records = harness.service()
                .generate(TRANSACTION_SOURCE, REVEALER, SEALER).statementRecords();
        assertThat(records).hasSize(21);
        String rule = "-".repeat(80);
        assertThat(records.get(5)).isEqualTo(rule);
        assertThat(records.get(6)).contains("Basic Details");
        assertThat(records.get(7)).isEqualTo(rule);
        assertThat(records.get(11)).isEqualTo(rule);
        assertThat(records.get(12)).contains("TRANSACTION SUMMARY");
        assertThat(records.get(13)).isEqualTo(rule);
        assertThat(records.get(14)).contains("Tran ID");
        assertThat(records.get(15)).isEqualTo(rule);
        assertThat(records.get(18)).isEqualTo(rule);
        assertThat(records.get(19)).startsWith("Total EXP:");
    }

    @Test
    @DisplayName("the name line stops at the first pair of adjacent spaces, not at the first single one")
    void doubleSpaceDelimiter() {
        Harness harness = harness(STATUS_OK, -1, 1);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        String custImage = fixture("custdata.txt").get(0);
        String first = custImage.substring(9, 34).trim();
        String middle = custImage.substring(34, 59).trim();
        String last = custImage.substring(59, 84).trim();
        String expected = pad("<p style=\"font-size:16px\">" + first + " " + middle + " " + last
                + "  </p>", 100);
        String nameLine = run.htmlRecords().stream()
                .filter(record -> record.contains(last)).findFirst().orElseThrow();
        assertThat(nameLine).isEqualTo(expected);
        assertThat(nameLine).contains(first).contains(middle).contains(last);
    }

    @Test
    @DisplayName("the populated-but-never-written group appears nowhere in the output")
    void neverWrittenGroupAbsent() {
        Harness harness = harness(STATUS_OK, -1, 1);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        assertThat(run.htmlRecords())
                .filteredOn(record -> record.startsWith("<p style=\"font-size:16px\">"))
                .allSatisfy(record -> assertThat(record).contains("</p>"));
    }

    @Test
    @DisplayName("the payload is blanked before every call, and neither dead operation is ever named")
    void payloadBlankedAndNoDeadOperations() {
        Harness harness = harness(STATUS_OK, -1, 3);
        harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        List<StatementFileRequest> requests = harness.script().requests;
        assertThat(requests).isNotEmpty()
                .allSatisfy(request -> assertThat(request.payload()).isEqualTo(pad("", PAYLOAD)))
                .noneSatisfy(request -> assertThat(
                        request.hasOperation(StatementDataAccessService.OPERATION_WRITE)).isTrue())
                .noneSatisfy(request -> assertThat(
                        request.hasOperation(StatementDataAccessService.OPERATION_REWRITE)).isTrue());
        assertThat(requests).extracting(request -> request.ddName() + "/" + request.operation())
                .contains("TRNXFILE/O", "TRNXFILE/R", "XREFFILE/O", "XREFFILE/R", "CUSTFILE/O",
                        "CUSTFILE/K", "ACCTFILE/O", "ACCTFILE/K", "TRNXFILE/C", "XREFFILE/C",
                        "CUSTFILE/C", "ACCTFILE/C");
    }

    @Test
    @DisplayName("the HTML preamble and tail are emitted in source order")
    void htmlOrder() {
        Harness harness = harness(STATUS_OK, -1, 1);
        List<String> html = harness.service()
                .generate(TRANSACTION_SOURCE, REVEALER, SEALER).htmlRecords();
        assertThat(html.get(0)).startsWith("<!DOCTYPE html>");
        assertThat(html.get(7)).startsWith("<table  align=\"center\"");
        assertThat(html.get(html.size() - 1)).startsWith("</html>");
        assertThat(html.get(html.size() - 2)).startsWith("</body>");
        assertThat(html.get(html.size() - 3)).startsWith("</table>");
    }

    private static List<String> golden(String name) {
        try (InputStream in = StatementGenerationServiceTest.class
                .getResourceAsStream("/fixtures/expected/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }

    @Test
    @DisplayName("record counts per statement follow the same formula as the golden fixtures")
    void goldenRecordCountFormula() {
        List<String> goldenText = golden("statement.txt");
        List<String> goldenHtml = golden("statement-html.txt");
        int textBlock = 1;
        while (textBlock < goldenText.size()
                && !goldenText.get(textBlock).startsWith("*".repeat(31) + "START")) {
            textBlock++;
        }
        int htmlBlock = 1;
        while (htmlBlock < goldenHtml.size()
                && !goldenHtml.get(htmlBlock).startsWith("<!DOCTYPE html>")) {
            htmlBlock++;
        }
        int goldenTransactions = textBlock - 19;
        assertThat(goldenTransactions).isPositive();
        assertThat(htmlBlock).isEqualTo(64 + 11 * goldenTransactions);
        for (int transactions = 1; transactions <= 4; transactions++) {
            Harness harness = harness(STATUS_OK, -1, transactions);
            StatementGenerationService.StatementRun run =
                    harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
            assertThat(run.statementRecords()).hasSize(19 + transactions);
            assertThat(run.htmlRecords()).hasSize(64 + 11 * transactions);
        }
    }

    @Test
    @DisplayName("every invariant record emitted appears verbatim in the golden fixtures")
    void goldenInvariantRecords() {
        Harness harness = harness(STATUS_OK, -1, 3);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        List<String> goldenText = golden("statement.txt");
        List<String> goldenHtml = golden("statement-html.txt");
        List<String> invariantText = List.of(run.statementRecords().get(0),
                run.statementRecords().get(5), run.statementRecords().get(6),
                run.statementRecords().get(12), run.statementRecords().get(14),
                run.statementRecords().get(run.statementRecords().size() - 1));
        assertThat(goldenText).containsAll(invariantText);
        List<String> invariantHtml = new ArrayList<>();
        for (String record : run.htmlRecords()) {
            if (com.carddemo.util.StatementHtmlTemplates.fixedTemplates().contains(record)) {
                invariantHtml.add(record);
            }
        }
        assertThat(invariantHtml).hasSizeGreaterThan(40);
        assertThat(goldenHtml).containsAll(invariantHtml);
    }

    @Test
    @DisplayName("composed lines carry the same prefixes and field widths as the golden fixtures")
    void goldenComposedPrefixes() {
        Harness harness = harness(STATUS_OK, -1, 2);
        StatementGenerationService.StatementRun run =
                harness.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        assertThat(run.statementRecords().get(8)).startsWith("Account ID         :");
        assertThat(run.statementRecords().get(9)).startsWith("Current Balance    :");
        assertThat(run.statementRecords().get(10)).startsWith("FICO Score         :");
        assertThat(run.htmlRecords().get(10)).startsWith("<h3>Statement for Account Number: ");
        List<String> basics = run.htmlRecords().stream()
                .filter(record -> record.startsWith("<p>Account ID")
                        || record.startsWith("<p>Current Balance")
                        || record.startsWith("<p>FICO Score"))
                .toList();
        assertThat(basics).hasSize(3);
        assertThat(basics.get(0)).startsWith("<p>Account ID         : ");
        assertThat(basics.get(1)).startsWith("<p>Current Balance    : ");
        assertThat(basics.get(2)).startsWith("<p>FICO Score         : ");
        List<String> goldenHtml = golden("statement-html.txt");
        for (String basic : basics) {
            String prefix = basic.substring(0, 24);
            assertThat(goldenHtml).anySatisfy(line -> assertThat(line).startsWith(prefix));
        }
        // The 49-byte transaction detail field: <p> + 49 + </p> = 56 significant characters.
        String detail = run.htmlRecords().stream()
                .filter(record -> record.startsWith("<p>") && record.indexOf("</p>") == 52)
                .findFirst().orElseThrow();
        assertThat(detail).hasSize(100);
        assertThat(goldenHtml).anySatisfy(line -> assertThat(line.indexOf("</p>")).isEqualTo(52));
    }

    @Test
    @DisplayName("the service holds no mutable state, so a second run repeats the first exactly")
    void statelessAcrossRuns() {
        Harness first = harness(STATUS_OK, -1, 2);
        StatementGenerationService.StatementRun runOne =
                first.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        StatementGenerationService.StatementRun runTwo =
                first.service().generate(TRANSACTION_SOURCE, REVEALER, SEALER);
        assertThat(runTwo.dispatchedPhases()).isEqualTo(runOne.dispatchedPhases());
        assertThat(runTwo.statementRecords()).hasSameSizeAs(runOne.statementRecords());
        assertThat(runTwo.transactionsTabulated()).isEqualTo(runOne.transactionsTabulated());
    }
}
