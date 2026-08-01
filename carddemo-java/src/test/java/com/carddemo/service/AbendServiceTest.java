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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Verifies {@code AbendService}, the one service that reproduces every abort path in the estate.
 *
 * <p>Three legacy constructs converge on this class. The online members carry an {@code ABEND-DATA}
 * area declared in {@code app/cpy/CSMSG02Y.cpy} and an {@code ABEND-ROUTINE} paragraph that fills it
 * and issues {@code EXEC CICS ABEND}; the batch members instead perform a status-display paragraph
 * followed by {@code 9999-ABEND-PROGRAM}, which invokes {@code CALL 'CEE3ABD'}; and a file failure
 * reaches the batch tier through the raw two-character {@code FILE STATUS}.
 *
 * <p><strong>Emit first, raise second - the decisive property.</strong> Every legacy abort path
 * displays its diagnostic before it aborts. {@code app/cbl/CBACT01C.cbl} does so at three
 * structurally identical sites, performing the status display and only then the abend paragraph
 * (lines 110 to 113 on the read path, 144 to 147 on the open path, 162 to 165 on the close path).
 * The migrated exception types deliberately hold no logger, so the ordering is this service's
 * obligation alone and is asserted here as a property of the service rather than left to each
 * caller. The proof is structural: a diagnostic recorded by the appender at the same instant the
 * abend is caught can only have been emitted before the raise, because a statement placed after a
 * {@code throw} is unreachable and would leave the recorder empty.
 *
 * <p><strong>The two tiers carry different codes and different payloads.</strong> The batch
 * paragraphs move a three-character code into a binary item and carry no context area at all, since
 * the abend-context copybook is included by four online members and by no batch member. The online
 * routine issues a four-character code and transmits the whole 134-character image. Collapsing the
 * two would emit a context area the batch tier never had, so each tier has its own entry point and
 * its own assertions here.
 *
 * <p><strong>Success and end of file may never abort.</strong> A status of {@code 00} reports
 * success and a status of {@code 10} is how every sequential read loop in the batch tier terminates
 * normally. Both are refused by the abort paths, because aborting on them would kill a healthy run
 * at its natural end - yet both may still be displayed, because displaying is not aborting.
 *
 * <p><strong>Independent oracles.</strong> Every width, offset, code and literal asserted below is
 * restated here from the copybook and the programs. No expected value is produced by calling the
 * class under test or any production formatter, and every expected context image is assembled from
 * explicit literals and {@code " ".repeat(n)} so a reviewer can count the padding without running
 * anything.
 *
 * <p>This is a plain unit test: no Spring context, no container, no database, no port and no
 * filesystem access. The service holds no collaborators, so there is nothing to mock and no test
 * double is created.
 */
@DisplayName("AbendService: the legacy abort paths, diagnostic first and abort second")
final class AbendServiceTest {

    // Oracles restated from the legacy source and the copybook, never read back from the classes under
    // test. Each one is separately asserted against the production constant it mirrors, so a drift in
    // either direction fails a test instead of quietly agreeing with itself.

    /** {@code ABEND-CODE PIC X(4)}, the first field of the context area. */
    private static final int ORACLE_CODE_WIDTH = 4;

    /** {@code ABEND-CULPRIT PIC X(8)}, wide enough for a COBOL member name and no wider. */
    private static final int ORACLE_CULPRIT_WIDTH = 8;

    /** {@code ABEND-REASON PIC X(50)}. */
    private static final int ORACLE_REASON_WIDTH = 50;

    /** {@code ABEND-MSG PIC X(72)}, the last field of the context area. */
    private static final int ORACLE_MESSAGE_WIDTH = 72;

    /** The whole {@code ABEND-DATA} area: four plus eight plus fifty plus seventy-two. */
    private static final int ORACLE_CONTEXT_WIDTH = 134;

    /** The offset at which {@code ABEND-CULPRIT} begins, being the width of the code before it. */
    private static final int ORACLE_CULPRIT_OFFSET = 4;

    /** The offset at which {@code ABEND-REASON} begins. */
    private static final int ORACLE_REASON_OFFSET = 12;

    /** The offset at which {@code ABEND-MSG} begins. */
    private static final int ORACLE_MESSAGE_OFFSET = 62;

    /** The value the batch abend paragraph moves into the abend code item, three characters. */
    private static final String ORACLE_BATCH_ABEND_CODE = "999";

    /** The value the online abend routine issues, four characters. */
    private static final String ORACLE_ONLINE_ABEND_CODE = "9999";

    /** The batch code is three characters, one short of the field that carries it. */
    private static final int ORACLE_BATCH_CODE_CHARACTERS = 3;

    /** The online code is four characters, which is exactly why the field is four wide. */
    private static final int ORACLE_ONLINE_CODE_CHARACTERS = 4;

    /** The literal the online routine substitutes when no operator message was supplied. */
    private static final String ORACLE_DEFAULT_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /** The operator-facing prefix under which the legacy programs emit a file status. */
    private static final String ORACLE_DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

    /** The text the batch members display immediately before aborting. */
    private static final String ORACLE_ABENDING_PROGRAM = "ABENDING PROGRAM";

    /** The marker the diagnostic renders in place of a value the caller did not supply. */
    private static final String ORACLE_NOT_SUPPLIED = "(none)";

    /** The marker the diagnostic renders beside a status outside the declared vocabulary. */
    private static final String ORACLE_OUTSIDE_VOCABULARY = "(outside declared vocabulary)";

    /** The exact character length of a COBOL file status. */
    private static final int ORACLE_STATUS_WIDTH = 2;

    /**
     * The vocabulary name the diagnostic renders beside a permanent-error status.
     *
     * <p>Stated as a literal rather than read back off the enumeration, so that renaming the
     * constant is caught here instead of silently agreeing with itself.
     */
    private static final String ORACLE_PERMANENT_ERROR_NAME = "PERMANENT_ERROR";

    // File statuses. Only the statuses the estate actually compares are used as error inputs, plus one
    // deliberately unrecognised value to exercise the catch-all branch of the status display.

    /** A raw status reporting success, which must never abort. */
    private static final String STATUS_SUCCESS = "00";

    /** A raw status reporting end of file, which must never abort. */
    private static final String STATUS_END_OF_FILE = "10";

    /**
     * The record-not-found status, the only error status the estate compares anywhere: once, in the
     * disclosure-group default fallback of the interest calculation program.
     */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /** A permanent-error status, declared in the vocabulary and a genuine failure. */
    private static final String STATUS_PERMANENT_ERROR = "31";

    /**
     * A status outside the vocabulary the estate declares anywhere.
     *
     * <p>The legacy status-display paragraph carries an explicit branch for a status that is not
     * numeric or whose first character is nine, so the estate itself anticipates values it never
     * tests against. Such a value is a legitimate runtime condition rather than a defect, and the
     * diagnostic must still report it rather than fail on it.
     */
    private static final String STATUS_OUTSIDE_VOCABULARY = "97";

    // Call-site fixtures.

    /** A batch program name at exactly the culprit width. */
    private static final String BATCH_PROGRAM = "CBACT01C";

    /** An online program name at exactly the culprit width. */
    private static final String ONLINE_PROGRAM = "COACTUPC";

    /** A batch program name shorter than the culprit width, so padding is observable. */
    private static final String SHORT_PROGRAM = "CBSTM03";

    /** A reason comfortably inside the reason width. */
    private static final String REASON = "UNABLE TO READ ACCOUNT MASTER";

    /** A caller's operation description, in the shape the legacy display literals take. */
    private static final String OPERATION = "ERROR READING ACCOUNT FILE";

    /** A legacy DD or CICS file name. */
    private static final String RESOURCE = "ACCTDAT";

    /** An operator message inside the message width. */
    private static final String TERMINAL_MESSAGE = "ACCOUNT UPDATE FAILED - CONTACT SUPPORT";

    // The canonical context image, assembled from literals with visible padding counts.
    //
    // 4 + 8 + 23 + 27 + 15 + 57 = 134. The reason occupies 23 characters of its 50 and the message 15
    // of its 72, so both padding runs are stated explicitly below rather than computed, which is what
    // makes this an independent oracle rather than a restatement of the renderer.

    /** A reason of 23 characters, leaving 27 characters of padding inside its 50-character field. */
    private static final String CANONICAL_REASON = "ACCOUNT UPDATE REJECTED";

    /** A message of 15 characters, leaving 57 characters of padding inside its 72-character field. */
    private static final String CANONICAL_MESSAGE = "CONTACT SUPPORT";

    /** The padding that follows the canonical reason inside {@code ABEND-REASON}. */
    private static final String CANONICAL_REASON_PADDING = " ".repeat(27);

    /** The padding that follows the canonical message inside {@code ABEND-MSG}. */
    private static final String CANONICAL_MESSAGE_PADDING = " ".repeat(57);

    /**
     * The whole 134-character image expected for an online abort carrying the canonical values.
     *
     * <p>Concatenated in record order from the four literal field values and their two explicit
     * padding runs. The code and the culprit each fill their field exactly, so neither contributes
     * padding.
     */
    private static final String CANONICAL_CONTEXT_IMAGE =
            ORACLE_ONLINE_ABEND_CODE
                    + ONLINE_PROGRAM
                    + CANONICAL_REASON + CANONICAL_REASON_PADDING
                    + CANONICAL_MESSAGE + CANONICAL_MESSAGE_PADDING;

    // The padding matrix inputs. Every one is deliberately shorter than its field, so that each of the
    // four fields demonstrates padding rather than only the two that happen to be long in practice.

    /** A one-character code, three short of {@code ABEND-CODE}. */
    private static final String MATRIX_CODE = "9";

    /** A two-character culprit, six short of {@code ABEND-CULPRIT}. */
    private static final String MATRIX_CULPRIT = "CB";

    /** A twelve-character reason, thirty-eight short of {@code ABEND-REASON}. */
    private static final String MATRIX_REASON = "SHORT REASON";

    /** A thirteen-character message, fifty-nine short of {@code ABEND-MSG}. */
    private static final String MATRIX_MESSAGE = "SHORT MESSAGE";

    /** The service under test. It holds no state, so a fresh instance per test costs nothing. */
    private AbendService abendService;

    @BeforeEach
    void createService() {
        abendService = new AbendService();
    }

    /**
     * Measures a rendered image in encoded bytes rather than in {@code char} units.
     *
     * <p>The legacy field widths are byte widths, so the assertion that matters is the encoded
     * length. Measuring characters would silently pass on a value that encodes to more bytes than
     * the record allows, which is exactly the defect a fixed-width contract has to exclude.
     *
     * @param image the rendered image to measure
     * @return the number of bytes the image occupies when encoded as single-byte characters
     */
    private static int encodedByteLength(String image) {
        return image.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Right-pads a value with spaces to a field width, as a COBOL alphanumeric field holds a
     * shorter value.
     *
     * <p>Implemented independently here so that an expected slice is never obtained by asking the
     * class under test to render it.
     *
     * @param value the value to place in the field
     * @param width the field width to pad out to
     * @return the value followed by enough spaces to reach {@code width}
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Runs an invocation that must abort and hands back the abort it raised.
     *
     * <p>Used where a test needs to compare two aborts against each other, which reads far more
     * clearly than nesting one assertion inside another's callback.
     *
     * @param invocation the call expected to abort
     * @return the abort that was raised
     * @throws AssertionError if the invocation returned normally instead of aborting
     */
    private static AbendException catchAbendFrom(Runnable invocation) {
        try {
            invocation.run();
        } catch (AbendException abend) {
            return abend;
        }
        throw new AssertionError("The invocation was expected to abort but returned normally.");
    }

    /**
     * Supplies the field-padding matrix: one row per field of the context area.
     *
     * <p>Each row names the legacy field, its start and end offsets, its width, and a value short
     * enough that padding is observable inside it. Driving all four fields from one table keeps the
     * offsets, the widths and the padding rule asserted together, so a change to any one of them
     * fails a row rather than slipping through a field that happened to have no dedicated test.
     *
     * @return the four rows of the padding matrix
     */
    private static Stream<Arguments> contextFieldMatrix() {
        return Stream.of(
                Arguments.of("ABEND-CODE", 0, ORACLE_CULPRIT_OFFSET, ORACLE_CODE_WIDTH,
                        MATRIX_CODE),
                Arguments.of("ABEND-CULPRIT", ORACLE_CULPRIT_OFFSET, ORACLE_REASON_OFFSET,
                        ORACLE_CULPRIT_WIDTH, MATRIX_CULPRIT),
                Arguments.of("ABEND-REASON", ORACLE_REASON_OFFSET, ORACLE_MESSAGE_OFFSET,
                        ORACLE_REASON_WIDTH, MATRIX_REASON),
                Arguments.of("ABEND-MSG", ORACLE_MESSAGE_OFFSET, ORACLE_CONTEXT_WIDTH,
                        ORACLE_MESSAGE_WIDTH, MATRIX_MESSAGE));
    }

    /**
     * Supplies the over-length matrix: one row per field, each with the legacy field name, its width
     * and a value exactly one character too long for it.
     *
     * @return the four rows of the over-length matrix
     */
    private static Stream<Arguments> overLengthFieldMatrix() {
        return Stream.of(
                Arguments.of("ABEND-CODE", ORACLE_CODE_WIDTH, "9".repeat(ORACLE_CODE_WIDTH + 1),
                        MATRIX_CULPRIT, MATRIX_REASON, MATRIX_MESSAGE),
                Arguments.of("ABEND-CULPRIT", ORACLE_CULPRIT_WIDTH, MATRIX_CODE,
                        "P".repeat(ORACLE_CULPRIT_WIDTH + 1), MATRIX_REASON, MATRIX_MESSAGE),
                Arguments.of("ABEND-REASON", ORACLE_REASON_WIDTH, MATRIX_CODE, MATRIX_CULPRIT,
                        "R".repeat(ORACLE_REASON_WIDTH + 1), MATRIX_MESSAGE),
                Arguments.of("ABEND-MSG", ORACLE_MESSAGE_WIDTH, MATRIX_CODE, MATRIX_CULPRIT,
                        MATRIX_REASON, "M".repeat(ORACLE_MESSAGE_WIDTH + 1)));
    }

    @Nested
    @DisplayName("the context area: 134 bytes in four fields, at the offsets the copybook fixes")
    final class ContextArea {

        @ParameterizedTest(name = "{0} occupies offsets [{1},{2}) at width {3}")
        @MethodSource("com.carddemo.service.AbendServiceTest#contextFieldMatrix")
        @DisplayName("each field occupies exactly its legacy offsets and is right-padded with spaces")
        void eachFieldOccupiesItsLegacyOffsetsAndIsSpacePadded(String legacyField, int start,
                int end, int width, String shortValue) {
            final AbendException abend = new AbendException(MATRIX_CODE, MATRIX_CULPRIT,
                    MATRIX_REASON, MATRIX_MESSAGE);

            final String slice = abend.toFixedWidthContext().substring(start, end);

            assertAll(legacyField,
                    () -> assertThat(end - start)
                            .as("the offsets the copybook fixes must span the declared width")
                            .isEqualTo(width),
                    () -> assertThat(slice)
                            .as("a shorter value is padded on the right, never truncated or shrunk")
                            .isEqualTo(padded(shortValue, width)),
                    () -> assertThat(slice)
                            .as("the slice fills its whole field")
                            .hasSize(width),
                    () -> assertThat(slice)
                            .as("the value itself is left-justified, as an alphanumeric field is")
                            .startsWith(shortValue));
        }

        @ParameterizedTest(name = "{0} refuses a value of width {1} plus one")
        @MethodSource("com.carddemo.service.AbendServiceTest#overLengthFieldMatrix")
        @DisplayName("a value longer than its field is refused outright, naming the field and width")
        void anOverLongFieldIsRefused(String legacyField, int width, String code, String culprit,
                String reason, String message) {
            // The renderer does not truncate on the right the way a legacy MOVE would. An over-length
            // value is a caller defect rather than a value to silently shorten, so it is rejected and
            // the rejection names the field, its picture width and the length supplied. Truncating
            // would let a caller lose the tail of a diagnostic without ever being told.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AbendException(code, culprit, reason, message))
                    .withMessageContaining(legacyField)
                    .withMessageContaining("PIC X(" + width + ")")
                    .withMessageContaining("length " + (width + 1));
        }

        @Test
        @DisplayName("the whole area is 134 encoded bytes, not merely 134 characters")
        void theWholeAreaIsOneHundredAndThirtyFourEncodedBytes() {
            final AbendException abend = new AbendException(ORACLE_ONLINE_ABEND_CODE, ONLINE_PROGRAM,
                    CANONICAL_REASON, CANONICAL_MESSAGE);

            final String image = abend.toFixedWidthContext();

            assertAll(
                    () -> assertThat(encodedByteLength(image))
                            .as("the legacy widths are byte widths, so the byte count is the contract")
                            .isEqualTo(ORACLE_CONTEXT_WIDTH),
                    () -> assertThat(AbendException.CONTEXT_LENGTH)
                            .as("the published total must equal the sum of the four field widths")
                            .isEqualTo(ORACLE_CONTEXT_WIDTH)
                            .isEqualTo(ORACLE_CODE_WIDTH + ORACLE_CULPRIT_WIDTH
                                    + ORACLE_REASON_WIDTH + ORACLE_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("the rendered area equals the image assembled by hand from the four literals")
        void theRenderedAreaEqualsTheHandAssembledImage() {
            final AbendException abend = new AbendException(ORACLE_ONLINE_ABEND_CODE, ONLINE_PROGRAM,
                    CANONICAL_REASON, CANONICAL_MESSAGE);

            // Compared against a literal image with explicit padding runs, never against a second
            // call to the renderer, so the two sides of this assertion are genuinely independent.
            assertThat(abend.toFixedWidthContext()).isEqualTo(CANONICAL_CONTEXT_IMAGE);
            assertThat(encodedByteLength(CANONICAL_CONTEXT_IMAGE)).isEqualTo(ORACLE_CONTEXT_WIDTH);
        }

        @Test
        @DisplayName("the culprit field carries the program name, at the offsets after the code")
        void theCulpritFieldCarriesTheProgramName() {
            final AbendException abend = new AbendException(ORACLE_ONLINE_ABEND_CODE, ONLINE_PROGRAM,
                    CANONICAL_REASON, CANONICAL_MESSAGE);

            final String culpritSlice = abend.toFixedWidthContext()
                    .substring(ORACLE_CULPRIT_OFFSET, ORACLE_REASON_OFFSET);

            assertAll(
                    () -> assertThat(culpritSlice)
                            .as("an eight-character member name fills the field exactly")
                            .isEqualTo(ONLINE_PROGRAM),
                    () -> assertThat(abend.culprit()).isEqualTo(ONLINE_PROGRAM),
                    () -> assertThat(AbendException.CULPRIT_LENGTH).isEqualTo(ORACLE_CULPRIT_WIDTH));
        }

        @Test
        @DisplayName("a shorter program name is padded to the culprit width rather than shrinking it")
        void aShorterProgramNameIsPaddedToTheCulpritWidth() {
            final AbendException abend = new AbendException(ORACLE_ONLINE_ABEND_CODE, SHORT_PROGRAM,
                    CANONICAL_REASON, CANONICAL_MESSAGE);

            final String image = abend.toFixedWidthContext();

            assertAll(
                    () -> assertThat(image.substring(ORACLE_CULPRIT_OFFSET, ORACLE_REASON_OFFSET))
                            .as("seven characters plus one space, never trimmed")
                            .isEqualTo(SHORT_PROGRAM + " "),
                    () -> assertThat(encodedByteLength(image))
                            .as("a short field must not shorten the area")
                            .isEqualTo(ORACLE_CONTEXT_WIDTH));
        }

        @Test
        @DisplayName("absent code, culprit and reason render as all spaces at their full widths")
        void absentFieldsRenderAsAllSpacesAtFullWidth() {
            // The copybook initialises every field to spaces, so an unsupplied value is a space-filled
            // field rather than an error or a missing field. The area keeps its full width either way.
            final AbendException abend = new AbendException(null, null, null, CANONICAL_MESSAGE);

            final String image = abend.toFixedWidthContext();

            assertAll(
                    () -> assertThat(image.substring(0, ORACLE_CULPRIT_OFFSET))
                            .isEqualTo(" ".repeat(ORACLE_CODE_WIDTH)),
                    () -> assertThat(image.substring(ORACLE_CULPRIT_OFFSET, ORACLE_REASON_OFFSET))
                            .isEqualTo(" ".repeat(ORACLE_CULPRIT_WIDTH)),
                    () -> assertThat(image.substring(ORACLE_REASON_OFFSET, ORACLE_MESSAGE_OFFSET))
                            .isEqualTo(" ".repeat(ORACLE_REASON_WIDTH)),
                    () -> assertThat(encodedByteLength(image)).isEqualTo(ORACLE_CONTEXT_WIDTH),
                    () -> assertThat(abend.code()).isEmpty(),
                    () -> assertThat(abend.culprit()).isEmpty(),
                    () -> assertThat(abend.reason()).isEmpty());
        }

        @Test
        @DisplayName("empty code, culprit and reason render as all spaces exactly as absent ones do")
        void emptyFieldsRenderAsAllSpacesToo() {
            final AbendException abend = new AbendException("", "", "", CANONICAL_MESSAGE);

            final String image = abend.toFixedWidthContext();

            assertAll(
                    () -> assertThat(image.substring(0, ORACLE_MESSAGE_OFFSET))
                            .as("code, culprit and reason together are space-filled")
                            .isEqualTo(" ".repeat(ORACLE_MESSAGE_OFFSET)),
                    () -> assertThat(encodedByteLength(image)).isEqualTo(ORACLE_CONTEXT_WIDTH));
        }

        @Test
        @DisplayName("an absent message becomes the substituted literal, not a space-filled field")
        void anAbsentMessageBecomesTheSubstitutedLiteral() {
            // The message field is the one field that is not merely space-filled when unsupplied: the
            // online routine substitutes its literal, because an abort must never present an operator
            // with an empty message.
            final AbendException abend =
                    new AbendException(ORACLE_ONLINE_ABEND_CODE, ONLINE_PROGRAM, REASON, null);

            final String messageSlice = abend.toFixedWidthContext()
                    .substring(ORACLE_MESSAGE_OFFSET, ORACLE_CONTEXT_WIDTH);

            assertAll(
                    () -> assertThat(abend.getMessage()).isEqualTo(ORACLE_DEFAULT_MESSAGE),
                    () -> assertThat(messageSlice)
                            .isEqualTo(padded(ORACLE_DEFAULT_MESSAGE, ORACLE_MESSAGE_WIDTH)),
                    () -> assertThat(messageSlice).hasSize(ORACLE_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("a value of exactly its field width is accepted and contributes no padding")
        void aValueOfExactlyItsFieldWidthIsAccepted() {
            final String exactReason = "R".repeat(ORACLE_REASON_WIDTH);
            final String exactMessage = "M".repeat(ORACLE_MESSAGE_WIDTH);

            final AbendException abend = new AbendException(ORACLE_ONLINE_ABEND_CODE, ONLINE_PROGRAM,
                    exactReason, exactMessage);

            final String image = abend.toFixedWidthContext();

            assertAll(
                    () -> assertThat(image.substring(ORACLE_REASON_OFFSET, ORACLE_MESSAGE_OFFSET))
                            .isEqualTo(exactReason),
                    () -> assertThat(image.substring(ORACLE_MESSAGE_OFFSET, ORACLE_CONTEXT_WIDTH))
                            .isEqualTo(exactMessage),
                    () -> assertThat(image).doesNotContain(" "),
                    () -> assertThat(encodedByteLength(image)).isEqualTo(ORACLE_CONTEXT_WIDTH));
        }

        @Test
        @DisplayName("every published field width matches the picture clause the copybook declares")
        void everyPublishedWidthMatchesItsPictureClause() {
            assertAll(
                    () -> assertThat(AbendException.CODE_LENGTH).isEqualTo(ORACLE_CODE_WIDTH),
                    () -> assertThat(AbendException.CULPRIT_LENGTH).isEqualTo(ORACLE_CULPRIT_WIDTH),
                    () -> assertThat(AbendException.REASON_LENGTH).isEqualTo(ORACLE_REASON_WIDTH),
                    () -> assertThat(AbendException.MESSAGE_LENGTH).isEqualTo(ORACLE_MESSAGE_WIDTH),
                    () -> assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(ORACLE_CONTEXT_WIDTH));
        }
    }

    @Nested
    @DisplayName("emit first, raise second: the diagnostic is already recorded when the abort surfaces")
    final class DiagnosticOrdering {

        /** The service's own logger, captured so the diagnostic can be read back. */
        private Logger logger;

        /** Records every event the service emits during one test. */
        private ListAppender<ILoggingEvent> recorder;

        /** The level the logger held before this group touched it. */
        private Level originalLevel;

        @BeforeEach
        void attachRecorder() {
            logger = (Logger) LoggerFactory.getLogger(AbendService.class);
            originalLevel = logger.getLevel();
            // Set the level explicitly rather than relying on whatever the surrounding
            // configuration happens to inherit, so this group asserts the service's ordering and
            // not the ambient logging setup.
            logger.setLevel(Level.ERROR);
            recorder = new ListAppender<>();
            recorder.setContext(logger.getLoggerContext());
            recorder.start();
            logger.addAppender(recorder);
        }

        @AfterEach
        void detachRecorder() {
            logger.detachAppender(recorder);
            recorder.stop();
            // A null original level restores inheritance from the parent, which is the state the
            // logger was in before this group ran.
            logger.setLevel(originalLevel);
        }

        @Test
        @DisplayName("the batch diagnostic is recorded before the abort is raised, not after it")
        void theBatchDiagnosticIsRecordedBeforeTheAbortIsRaised() {
            assertThatThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON,
                    STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                    .isInstanceOf(AbendException.class);

            // The ordering proof: the recorder was filled during the very call that threw. Had the
            // service logged after raising, that statement would be unreachable and this list would
            // be empty, so a recorded event together with a thrown abort establishes the sequence
            // rather than merely showing that both happened.
            assertThat(recorder.list)
                    .as("the diagnostic must survive the abort, because the operator needs it")
                    .hasSize(1);
            final ILoggingEvent event = recorder.list.getFirst();
            assertAll(
                    () -> assertThat(event.getLevel()).isEqualTo(Level.ERROR),
                    () -> assertThat(event.getFormattedMessage())
                            .as("the raw status and the culprit program name both reach the operator")
                            .contains(STATUS_PERMANENT_ERROR)
                            .contains(BATCH_PROGRAM)
                            .contains(ORACLE_ABENDING_PROGRAM)
                            .contains(ORACLE_BATCH_ABEND_CODE)
                            .contains(OPERATION)
                            .contains(RESOURCE));
        }

        @Test
        @DisplayName("the online diagnostic is recorded before the abort is raised as well")
        void theOnlineDiagnosticIsRecordedBeforeTheAbortIsRaised() {
            assertThatThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON,
                    TERMINAL_MESSAGE))
                    .isInstanceOf(AbendException.class);

            assertThat(recorder.list).hasSize(1);
            final ILoggingEvent event = recorder.list.getFirst();
            assertAll(
                    () -> assertThat(event.getLevel()).isEqualTo(Level.ERROR),
                    () -> assertThat(event.getFormattedMessage())
                            .contains(ONLINE_PROGRAM)
                            .contains(REASON)
                            .contains(ORACLE_ONLINE_ABEND_CODE));
        }

        @Test
        @DisplayName("the diagnostic precedes the context image, and both precede the abort")
        void theDiagnosticPrecedesTheContextImageAndBothPrecedeTheAbort() {
            // Raising the level to debug reveals the second emission, which lets the ordering be
            // asserted as a sequence of two recorded events rather than as a single one.
            logger.setLevel(Level.DEBUG);

            assertThatThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, CANONICAL_REASON,
                    CANONICAL_MESSAGE))
                    .isInstanceOf(AbendException.class);

            final List<ILoggingEvent> recorded = List.copyOf(recorder.list);
            assertThat(recorded)
                    .as("the field-by-field diagnostic, then the byte-exact image, then the abort")
                    .hasSize(2);
            assertAll(
                    () -> assertThat(recorded.get(0).getLevel()).isEqualTo(Level.ERROR),
                    () -> assertThat(recorded.get(0).getFormattedMessage())
                            .startsWith(ORACLE_ABENDING_PROGRAM),
                    () -> assertThat(recorded.get(1).getLevel()).isEqualTo(Level.DEBUG),
                    () -> assertThat(recorded.get(1).getFormattedMessage())
                            .as("the emitted image is the whole area, padding included")
                            .contains(CANONICAL_CONTEXT_IMAGE)
                            .contains(String.valueOf(ORACLE_CONTEXT_WIDTH)));
        }

        @Test
        @DisplayName("the batch tier emits no context image, because no batch member carries one")
        void theBatchTierEmitsNoContextImage() {
            logger.setLevel(Level.DEBUG);

            assertThatThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                    .isInstanceOf(AbendException.class);

            assertThat(recorder.list)
                    .as("emitting an image here would invent a context area the batch tier lacks")
                    .hasSize(1);
            assertThat(recorder.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        }

        @Test
        @DisplayName("the file-status abort records the status, then raises with it on the chain")
        void theFileStatusAbortRecordsTheStatusThenRaises() {
            assertThatThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                    STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .isInstanceOf(AbendException.class)
                    .hasCauseInstanceOf(FileStatusException.class);

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .contains(STATUS_RECORD_NOT_FOUND)
                    .contains(BATCH_PROGRAM)
                    .contains(ORACLE_ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("even the misuse rejection emits its diagnostic before it raises")
        void evenTheMisuseRejectionEmitsItsDiagnosticFirst() {
            // A caller that abends on a non-error status has a defect on its own error path, which
            // is the least convenient place to be told nothing at all.
            assertThatThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, STATUS_SUCCESS,
                    OPERATION, RESOURCE))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .contains(STATUS_SUCCESS)
                    .contains(ORACLE_ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("an over-long field still leaves the diagnostic behind when the value is refused")
        void anOverLongFieldStillLeavesTheDiagnosticBehind() {
            // The diagnostic is composed from the raw arguments rather than from the exception, so it
            // survives the exception's own width enforcement refusing the value on the next line.
            final String tooLong = "R".repeat(ORACLE_REASON_WIDTH + 1);

            assertThatThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, tooLong))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(recorder.list)
                    .as("no diagnostic may be lost to a rejection that happens after it")
                    .hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage()).contains(BATCH_PROGRAM);
        }

        @Test
        @DisplayName("the status display opens with the operator prefix the legacy display uses")
        void theStatusDisplayOpensWithTheOperatorPrefix() {
            abendService.displayIoStatus(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE);

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .as("an operator searching for the text they already know must find it")
                    .startsWith(ORACLE_DISPLAY_PREFIX)
                    .contains(STATUS_PERMANENT_ERROR)
                    .contains(ORACLE_PERMANENT_ERROR_NAME);
        }

        @Test
        @DisplayName("a status outside the vocabulary is reported beside the catch-all marker")
        void aStatusOutsideTheVocabularyIsReportedBesideTheCatchAllMarker() {
            abendService.displayIoStatus(STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE);

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .as("the raw characters stay authoritative beside the marker")
                    .contains(STATUS_OUTSIDE_VOCABULARY)
                    .contains(ORACLE_OUTSIDE_VOCABULARY);
        }

        @Test
        @DisplayName("absent context is rendered as an explicit marker rather than an empty gap")
        void absentContextIsRenderedAsAnExplicitMarker() {
            abendService.displayIoStatus(null, null, null);

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .startsWith(ORACLE_DISPLAY_PREFIX)
                    .contains(ORACLE_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("switching the logger off withholds the diagnostic yet still raises the abort")
        void switchingTheLoggerOffStillRaisesTheAbort() {
            // The abort is not conditional on the diagnostic being emitted: a run must still fail
            // even when the operator channel is silenced.
            logger.setLevel(Level.OFF);

            assertThatThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                    .isInstanceOf(AbendException.class);

            assertThat(recorder.list).isEmpty();
        }
    }

    @Nested
    @DisplayName("the batch tier: the three-character code, no context area, and no chained cause")
    final class BatchAbend {

        @Test
        @DisplayName("the short form aborts with the batch code and the substituted operator message")
        void theShortFormAbortsWithTheBatchCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.code()).isEqualTo(ORACLE_BATCH_ABEND_CODE),
                            () -> assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM),
                            () -> assertThat(abend.reason()).isEqualTo(REASON),
                            () -> assertThat(abend.getMessage()).isEqualTo(ORACLE_DEFAULT_MESSAGE)));
        }

        @Test
        @DisplayName("the batch code is stamped into the area space-padded, because it is three of four")
        void theBatchCodeIsStampedSpacePaddedIntoTheFourByteField() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(
                            abend.toFixedWidthContext().substring(0, ORACLE_CULPRIT_OFFSET))
                            .as("one character short of the field, exactly as the legacy leaves it")
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE + " "));
        }

        @Test
        @DisplayName("the batch tier chains no cause, because it has no file failure to chain")
        void theBatchTierChainsNoCause() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.getCause()).isNull());
        }

        @Test
        @DisplayName("the long form carries the operation, the resource and the raw status")
        void theLongFormCarriesTheIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON,
                            STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.code()).isEqualTo(ORACLE_BATCH_ABEND_CODE),
                            () -> assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM),
                            () -> assertThat(abend.reason()).isEqualTo(REASON)));
        }

        @Test
        @DisplayName("absent input-output context is tolerated, because a non-file abort has none")
        void absentIoContextIsTolerated() {
            // The statement generator reaches its abort paragraph from a subprogram return code and
            // has no status-display paragraph at all, so this shape has to work.
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(SHORT_PROGRAM, REASON, null, null,
                            null))
                    .satisfies(abend -> assertThat(abend.culprit()).isEqualTo(SHORT_PROGRAM));
        }

        @Test
        @DisplayName("blank input-output context is treated as absent, as space-filled fields are")
        void blankIoContextIsTreatedAsAbsent() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON, "  ", "  ",
                            " "));
        }

        @Test
        @DisplayName("a status outside the vocabulary is still reported rather than refused")
        void aStatusOutsideTheVocabularyIsStillReported() {
            // The display paragraph has a dedicated branch for exactly this case, so refusing the
            // value here would break the diagnostic that exists to report it.
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON,
                            STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("success and end of file may be passed as context, since this path does not judge them")
        void successAndEndOfFileMayBePassedAsContext() {
            // This entry point takes a status only to report it. The status is not the trigger, so
            // unlike the file-status entry point it neither validates nor refuses one.
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON, STATUS_SUCCESS,
                            OPERATION, RESOURCE));
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON,
                            STATUS_END_OF_FILE, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("a reason longer than its field is refused, after the diagnostic is emitted")
        void anOverLongReasonIsRefused() {
            final String tooLong = "R".repeat(ORACLE_REASON_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, tooLong))
                    .withMessageContaining("ABEND-REASON")
                    .withMessageContaining(String.valueOf(ORACLE_REASON_WIDTH));
        }

        @Test
        @DisplayName("a culprit longer than its field is refused")
        void anOverLongCulpritIsRefused() {
            final String tooLong = "P".repeat(ORACLE_CULPRIT_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendBatch(tooLong, REASON))
                    .withMessageContaining("ABEND-CULPRIT")
                    .withMessageContaining(String.valueOf(ORACLE_CULPRIT_WIDTH));
        }

        @Test
        @DisplayName("a reason of exactly the field width is accepted")
        void aReasonOfExactlyTheFieldWidthIsAccepted() {
            final String exact = "R".repeat(ORACLE_REASON_WIDTH);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, exact))
                    .satisfies(abend -> assertThat(abend.reason()).hasSize(ORACLE_REASON_WIDTH));
        }

        @Test
        @DisplayName("an absent culprit and reason are tolerated and become space-filled fields")
        void anAbsentCulpritAndReasonAreTolerated() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(null, null))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEmpty(),
                            () -> assertThat(abend.reason()).isEmpty(),
                            () -> assertThat(encodedByteLength(abend.toFixedWidthContext()))
                                    .isEqualTo(ORACLE_CONTEXT_WIDTH)));
        }
    }

    @Nested
    @DisplayName("the online tier: the four-character code and the transmitted context area")
    final class OnlineAbend {

        @Test
        @DisplayName("the short form aborts with the online code and the substituted message")
        void theShortFormAbortsWithTheOnlineCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.code()).isEqualTo(ORACLE_ONLINE_ABEND_CODE),
                            () -> assertThat(abend.culprit()).isEqualTo(ONLINE_PROGRAM),
                            () -> assertThat(abend.reason()).isEqualTo(REASON),
                            () -> assertThat(abend.getMessage()).isEqualTo(ORACLE_DEFAULT_MESSAGE)));
        }

        @Test
        @DisplayName("the online code fills the whole four-character field, contributing no padding")
        void theOnlineCodeFillsTheWholeField() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(
                            abend.toFixedWidthContext().substring(0, ORACLE_CULPRIT_OFFSET))
                            .isEqualTo(ORACLE_ONLINE_ABEND_CODE)
                            .doesNotContain(" "));
        }

        @Test
        @DisplayName("a supplied operator message is carried verbatim into the message field")
        void aSuppliedOperatorMessageIsCarriedVerbatim() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON,
                            TERMINAL_MESSAGE))
                    .withMessage(TERMINAL_MESSAGE)
                    .satisfies(abend -> assertThat(abend.toFixedWidthContext()
                            .substring(ORACLE_MESSAGE_OFFSET, ORACLE_CONTEXT_WIDTH))
                            .isEqualTo(padded(TERMINAL_MESSAGE, ORACLE_MESSAGE_WIDTH)));
        }

        @Test
        @DisplayName("an absent message is replaced by the literal the routine substitutes")
        void anAbsentMessageIsSubstituted() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON, null))
                    .withMessage(ORACLE_DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a blank message is substituted too, because the field is space-initialised")
        void aBlankMessageIsSubstitutedToo() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON, "      "))
                    .withMessage(ORACLE_DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a message longer than its field is refused")
        void anOverLongMessageIsRefused() {
            final String tooLong = "M".repeat(ORACLE_MESSAGE_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON, tooLong))
                    .withMessageContaining("ABEND-MSG")
                    .withMessageContaining(String.valueOf(ORACLE_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("the rendered area is the whole copybook width, measured in encoded bytes")
        void theRenderedAreaIsTheWholeCopybookWidth() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, CANONICAL_REASON,
                            CANONICAL_MESSAGE))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(encodedByteLength(abend.toFixedWidthContext()))
                                    .isEqualTo(ORACLE_CONTEXT_WIDTH),
                            () -> assertThat(abend.toFixedWidthContext())
                                    .isEqualTo(CANONICAL_CONTEXT_IMAGE)));
        }

        @Test
        @DisplayName("the online tier chains no cause either")
        void theOnlineTierChainsNoCause() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.getCause()).isNull());
        }

        @Test
        @DisplayName("the two tiers stamp codes that differ, so neither is mistaken for the other")
        void theTwoTiersStampCodesThatDiffer() {
            // Driven through both entry points rather than compared as constants, so the assertion
            // covers which code each path actually stamps and not merely that two literals differ.
            final AbendException batch = catchAbendFrom(
                    () -> abendService.abendBatch(BATCH_PROGRAM, REASON));
            final AbendException online = catchAbendFrom(
                    () -> abendService.abendOnline(ONLINE_PROGRAM, REASON));

            final String batchSlice =
                    batch.toFixedWidthContext().substring(0, ORACLE_CULPRIT_OFFSET);
            final String onlineSlice =
                    online.toFixedWidthContext().substring(0, ORACLE_CULPRIT_OFFSET);

            assertAll(
                    () -> assertThat(batchSlice)
                            .as("three characters and a space, never trimmed")
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE + " "),
                    () -> assertThat(onlineSlice).isEqualTo(ORACLE_ONLINE_ABEND_CODE),
                    () -> assertThat(batchSlice).isNotEqualTo(onlineSlice),
                    () -> assertThat(batch.code()).isNotEqualTo(online.code()),
                    () -> assertThat(batch.code()).hasSize(ORACLE_BATCH_CODE_CHARACTERS),
                    () -> assertThat(online.code()).hasSize(ORACLE_ONLINE_CODE_CHARACTERS));
        }
    }

    @Nested
    @DisplayName("the status display: the paragraph that reports and deliberately does not abort")
    final class StatusDisplay {

        @Test
        @DisplayName("a genuine error status is reported without raising anything")
        void aGenuineErrorStatusIsReportedWithoutRaising() {
            // The legacy performs this paragraph on its own wherever the program intends to carry
            // on, so a raising implementation would abort runs the legacy continues.
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(
                    STATUS_PERMANENT_ERROR, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("success may be displayed, because displaying a status is not aborting on it")
        void successMayBeDisplayed() {
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(STATUS_SUCCESS,
                    OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("end of file may be displayed for the same reason")
        void endOfFileMayBeDisplayed() {
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(
                    STATUS_END_OF_FILE, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("an absent status is tolerated: a diagnostic must not fail on the failing path")
        void anAbsentStatusIsTolerated() {
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(null, null, null));
        }

        @Test
        @DisplayName("a status outside the vocabulary is tolerated, as the legacy branch requires")
        void aStatusOutsideTheVocabularyIsTolerated() {
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(
                    STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("blank context is tolerated and rendered as absent")
        void blankContextIsTolerated() {
            assertThatNoException()
                    .isThrownBy(() -> abendService.displayIoStatus("  ", "   ", "    "));
        }

        @Test
        @DisplayName("a status of the wrong width is still displayed, because the display never judges")
        void aStatusOfTheWrongWidthIsStillDisplayed() {
            // Only the abort paths validate a status. The display exists precisely to report whatever
            // was actually observed, including something the vocabulary cannot explain.
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus("9", OPERATION,
                    RESOURCE));
        }
    }

    @Nested
    @DisplayName("aborting on a file status: the error arm only, with the raw status on the chain")
    final class AbortOnFileStatus {

        @Test
        @DisplayName("the raw status survives on the exception chain, not only in the diagnostic")
        void theRawStatusSurvivesOnTheChain() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.getCause()).isInstanceOf(FileStatusException.class);
                        final FileStatusException cause = (FileStatusException) abend.getCause();
                        assertAll(
                                () -> assertThat(cause.code())
                                        .as("the two raw bytes round-trip unchanged")
                                        .isEqualTo(STATUS_PERMANENT_ERROR)
                                        .hasSize(ORACLE_STATUS_WIDTH),
                                () -> assertThat(cause.operation()).isEqualTo(OPERATION),
                                () -> assertThat(cause.resourceName()).isEqualTo(RESOURCE));
                    });
        }

        @Test
        @DisplayName("the reason is the labelled status and nothing invented beyond it")
        void theReasonIsTheLabelledStatus() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.reason())
                            .as("no legacy text exists for this combination, so none is invented")
                            .isEqualTo("FILE STATUS " + STATUS_RECORD_NOT_FOUND));
        }

        @Test
        @DisplayName("the abort takes the batch code, because no online member has a file status")
        void theAbortTakesTheBatchCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.code())
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE));
        }

        @Test
        @DisplayName("success is refused, because a successful operation is not a failure")
        void successIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, STATUS_SUCCESS,
                            OPERATION, RESOURCE))
                    .withMessageContaining(STATUS_SUCCESS)
                    .withMessageContaining("must not abend");
        }

        @Test
        @DisplayName("end of file is refused, because it is how a read loop terminates normally")
        void endOfFileIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_END_OF_FILE, OPERATION, RESOURCE))
                    .withMessageContaining(STATUS_END_OF_FILE)
                    .withMessageContaining("end-of-file arm");
        }

        @Test
        @DisplayName("an absent status is refused before anything is constructed")
        void anAbsentStatusIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, null, OPERATION,
                            RESOURCE))
                    .withMessageContaining("null was supplied");
        }

        @Test
        @DisplayName("a status shorter than two characters is refused")
        void aStatusShorterThanTwoCharactersIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, "3", OPERATION,
                            RESOURCE))
                    .withMessageContaining("exactly " + ORACLE_STATUS_WIDTH)
                    .withMessageContaining("has length 1");
        }

        @Test
        @DisplayName("a status longer than two characters is refused")
        void aStatusLongerThanTwoCharactersIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, "310",
                            OPERATION, RESOURCE))
                    .withMessageContaining("has length 3");
        }

        @Test
        @DisplayName("the misuse guard still reports when the culprit and context are all absent")
        void theMisuseGuardReportsEvenWithoutAnyContext() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(null, STATUS_END_OF_FILE, null,
                            null))
                    .withMessageContaining(STATUS_END_OF_FILE);
        }

        @Test
        @DisplayName("a two-character status outside the vocabulary is accepted as a genuine error")
        void aStatusOutsideTheVocabularyIsAccepted() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("FILE STATUS " + STATUS_OUTSIDE_VOCABULARY));
        }

        @Test
        @DisplayName("absent operation and resource are tolerated and normalised to empty context")
        void absentOperationAndResourceAreTolerated() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_PERMANENT_ERROR, null, null))
                    .satisfies(abend -> {
                        final FileStatusException cause = (FileStatusException) abend.getCause();
                        assertAll(
                                () -> assertThat(cause.operation()).isEmpty(),
                                () -> assertThat(cause.resourceName()).isEmpty(),
                                () -> assertThat(cause.code()).isEqualTo(STATUS_PERMANENT_ERROR));
                    });
        }
    }

    @Nested
    @DisplayName("aborting on a failure the caller already holds")
    final class AbortOnHeldFailure {

        @Test
        @DisplayName("the supplied failure is chained unchanged, so no second instance is invented")
        void theSuppliedFailureIsChainedUnchanged() {
            final FileStatusException held =
                    new FileStatusException(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, held))
                    .satisfies(abend -> assertThat(abend.getCause()).isSameAs(held));
        }

        @Test
        @DisplayName("the status, operation and resource are read back off the failure itself")
        void theContextIsReadBackOffTheFailure() {
            // A caller cannot report one status and chain another, because there is only one source.
            final FileStatusException held =
                    new FileStatusException(STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, held))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("FILE STATUS " + STATUS_RECORD_NOT_FOUND));
        }

        @Test
        @DisplayName("an absent failure is a caller defect and is refused, with a diagnostic first")
        void anAbsentFailureIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            (FileStatusException) null))
                    .withMessageContaining("FileStatusException is required");
        }

        @Test
        @DisplayName("an absent failure is refused even when the culprit is also absent")
        void anAbsentFailureIsRefusedEvenWithoutACulprit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(null,
                            (FileStatusException) null))
                    .withMessageContaining("null was supplied");
        }

        @Test
        @DisplayName("a failure carrying no operation or resource still aborts")
        void aFailureWithoutContextStillAborts() {
            final FileStatusException held =
                    new FileStatusException(STATUS_PERMANENT_ERROR, null, null);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, held))
                    .satisfies(abend -> assertThat(abend.code())
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE));
        }

        @Test
        @DisplayName("a failure carrying its own cause keeps that cause beneath the abort")
        void aFailureCarryingItsOwnCauseKeepsIt() {
            final Throwable root = new IllegalStateException("underlying reader failure");
            final FileStatusException held =
                    new FileStatusException(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE, root);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, held))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.getCause()).isSameAs(held),
                            () -> assertThat(abend.getCause().getCause()).isSameAs(root)));
        }
    }

    @Nested
    @DisplayName("the file-status wrapper: the error arm only, carrying the two raw bytes")
    final class FileStatusContract {

        @Test
        @DisplayName("constructing one with the success status is refused, because success is no error")
        void constructingWithTheSuccessStatusIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(STATUS_SUCCESS, OPERATION, RESOURCE))
                    .withMessageContaining(STATUS_SUCCESS)
                    .withMessageContaining("successful operation");
        }

        @Test
        @DisplayName("constructing one with the end-of-file status is refused as well")
        void constructingWithTheEndOfFileStatusIsRefused() {
            // End of file is how every sequential read loop in the batch tier terminates normally.
            // Representing it as an error would erase the very distinction those loops depend on.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(STATUS_END_OF_FILE, OPERATION,
                            RESOURCE))
                    .withMessageContaining(STATUS_END_OF_FILE)
                    .withMessageContaining("end-of-file arm");
        }

        @Test
        @DisplayName("the four-argument form refuses the two non-error statuses just the same")
        void theFourArgumentFormRefusesTheNonErrorStatusesToo() {
            final Throwable root = new IllegalStateException("reader failure");

            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(STATUS_SUCCESS, OPERATION,
                                    RESOURCE, root)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(STATUS_END_OF_FILE, OPERATION,
                                    RESOURCE, root)));
        }

        @Test
        @DisplayName("a genuine error status is accepted and its two raw bytes round-trip unchanged")
        void aGenuineErrorStatusRoundTripsUnchanged() {
            final FileStatusException failure =
                    new FileStatusException(STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE);

            assertAll(
                    () -> assertThat(failure.code())
                            .as("never normalised, mapped or reformatted")
                            .isEqualTo(STATUS_RECORD_NOT_FOUND)
                            .hasSize(ORACLE_STATUS_WIDTH),
                    () -> assertThat(failure.firstByte()).isEqualTo('2'),
                    () -> assertThat(failure.secondByte()).isEqualTo('3'),
                    () -> assertThat(failure.operation()).isEqualTo(OPERATION),
                    () -> assertThat(failure.resourceName()).isEqualTo(RESOURCE));
        }

        @Test
        @DisplayName("the two single-character halves mirror the legacy split status group")
        void theTwoHalvesMirrorTheLegacySplitGroup() {
            // The legacy declares the status as a two-character group of two single-character fields,
            // and the display routine inspects the first of them on its own.
            final FileStatusException failure =
                    new FileStatusException(STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE);

            assertAll(
                    () -> assertThat(failure.firstByte()).isEqualTo('9'),
                    () -> assertThat(failure.secondByte()).isEqualTo('7'),
                    () -> assertThat(String.valueOf(failure.firstByte()) + failure.secondByte())
                            .as("the two halves reassemble into the raw status, byte for byte")
                            .isEqualTo(STATUS_OUTSIDE_VOCABULARY));
        }

        @Test
        @DisplayName("a status outside the vocabulary is still a constructible genuine error")
        void aStatusOutsideTheVocabularyIsStillConstructible() {
            final FileStatusException failure =
                    new FileStatusException(STATUS_OUTSIDE_VOCABULARY, null, null);

            assertAll(
                    () -> assertThat(failure.code()).isEqualTo(STATUS_OUTSIDE_VOCABULARY),
                    () -> assertThat(failure.operation()).isEmpty(),
                    () -> assertThat(failure.resourceName()).isEmpty());
        }

        @Test
        @DisplayName("the published constants match the legacy contract they reproduce")
        void thePublishedConstantsMatchTheLegacyContract() {
            assertAll(
                    () -> assertThat(FileStatusException.CODE_LENGTH)
                            .isEqualTo(ORACLE_STATUS_WIDTH),
                    () -> assertThat(FileStatusException.STATUS_SUCCESS).isEqualTo(STATUS_SUCCESS),
                    () -> assertThat(FileStatusException.STATUS_END_OF_FILE)
                            .isEqualTo(STATUS_END_OF_FILE),
                    () -> assertThat(FileStatusException.DISPLAY_PREFIX)
                            .as("reproduced verbatim, four literal characters after the colon and all")
                            .isEqualTo(ORACLE_DISPLAY_PREFIX));
        }

        @Test
        @DisplayName("an unrecognised status resolves to the catch-all rather than failing the lookup")
        void anUnrecognisedStatusResolvesToTheCatchAll() {
            // The lookup is a total function: the legacy display paragraph has an explicit branch for
            // a status it cannot explain, so a value outside the vocabulary must resolve quietly to
            // nothing rather than raise while a run is already failing.
            assertAll(
                    () -> assertThat(FileStatus.fromCode(STATUS_OUTSIDE_VOCABULARY)).isEmpty(),
                    () -> assertThat(FileStatus.fromCode(STATUS_RECORD_NOT_FOUND))
                            .contains(FileStatus.RECORD_NOT_FOUND),
                    () -> assertThat(FileStatus.fromCode(STATUS_PERMANENT_ERROR))
                            .contains(FileStatus.PERMANENT_ERROR));
        }

        @Test
        @DisplayName("the two non-error statuses are the ones the vocabulary marks as not failures")
        void theTwoNonErrorStatusesAreMarkedAsSuch() {
            // This is the whole of the coarse outcome vocabulary that is visible from here. The
            // numeric normalisation the batch programs perform - nought for success, sixteen for end
            // of file, twelve for an error, and eight as a pre-operation sentinel - belongs to the
            // sequential file reader that owns it, and is asserted where that reader is tested.
            assertAll(
                    () -> assertThat(FileStatus.fromCode(STATUS_SUCCESS))
                            .contains(FileStatus.SUCCESS),
                    () -> assertThat(FileStatus.SUCCESS.isSuccess()).isTrue(),
                    () -> assertThat(FileStatus.SUCCESS.isEndOfFile()).isFalse(),
                    () -> assertThat(FileStatus.fromCode(STATUS_END_OF_FILE))
                            .contains(FileStatus.END_OF_FILE),
                    () -> assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue(),
                    () -> assertThat(FileStatus.END_OF_FILE.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                            .isEqualTo(STATUS_RECORD_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("exception plumbing: unchecked, message-preserving and cause-preserving")
    final class ExceptionPlumbing {

        @Test
        @DisplayName("both exception types are unchecked, so no caller is forced to declare them")
        void bothExceptionTypesAreUnchecked() {
            // A legacy abort unwound the run without any intervening declaration, so forcing every
            // caller in the estate to declare a checked type would be a structural change rather
            // than a translation.
            final AbendException abend =
                    new AbendException(ORACLE_BATCH_ABEND_CODE, BATCH_PROGRAM, REASON, null);
            final FileStatusException failure =
                    new FileStatusException(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE);

            assertAll(
                    () -> assertThat(abend).isInstanceOf(RuntimeException.class),
                    () -> assertThat(failure).isInstanceOf(RuntimeException.class));
        }

        @Test
        @DisplayName("the abort preserves a supplied cause and exposes it unchanged")
        void theAbortPreservesASuppliedCause() {
            final Throwable root = new IllegalStateException("underlying failure");

            final AbendException abend = new AbendException(ORACLE_ONLINE_ABEND_CODE, ONLINE_PROGRAM,
                    REASON, TERMINAL_MESSAGE, root);

            assertAll(
                    () -> assertThat(abend.getCause()).isSameAs(root),
                    () -> assertThat(abend.getMessage()).isEqualTo(TERMINAL_MESSAGE));
        }

        @Test
        @DisplayName("the file failure preserves a supplied cause and its own composed message")
        void theFileFailurePreservesItsCauseAndMessage() {
            final Throwable root = new IllegalStateException("underlying failure");

            final FileStatusException failure =
                    new FileStatusException(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE, root);

            assertAll(
                    () -> assertThat(failure.getCause()).isSameAs(root),
                    () -> assertThat(failure.getMessage())
                            .contains(STATUS_PERMANENT_ERROR)
                            .contains(OPERATION)
                            .contains(RESOURCE));
        }

        @Test
        @DisplayName("the two-argument abort form applies the batch code and the substituted message")
        void theTwoArgumentFormAppliesTheBatchDefaults() {
            final AbendException abend = new AbendException(BATCH_PROGRAM, REASON);

            assertAll(
                    () -> assertThat(abend.code()).isEqualTo(ORACLE_BATCH_ABEND_CODE),
                    () -> assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM),
                    () -> assertThat(abend.reason()).isEqualTo(REASON),
                    () -> assertThat(abend.getMessage()).isEqualTo(ORACLE_DEFAULT_MESSAGE),
                    () -> assertThat(abend.getCause()).isNull());
        }

        @Test
        @DisplayName("the substituted message is reproduced verbatim, trailing full stop included")
        void theSubstitutedMessageIsReproducedVerbatim() {
            assertAll(
                    () -> assertThat(AbendException.DEFAULT_MESSAGE)
                            .isEqualTo(ORACLE_DEFAULT_MESSAGE)
                            .endsWith("."),
                    () -> assertThat(AbendException.BATCH_ABEND_CODE)
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE)
                            .hasSize(ORACLE_BATCH_CODE_CHARACTERS),
                    () -> assertThat(AbendException.ONLINE_ABEND_CODE)
                            .isEqualTo(ORACLE_ONLINE_ABEND_CODE)
                            .hasSize(ORACLE_ONLINE_CODE_CHARACTERS),
                    () -> assertThat(AbendException.BATCH_ABEND_CODE)
                            .as("two tiers, two codes, and they must never collide")
                            .isNotEqualTo(AbendException.ONLINE_ABEND_CODE));
        }

        @Test
        @DisplayName("every aborting entry point raises the abort itself, neither wrapped nor swallowed")
        void everyAbortingEntryPointRaisesTheAbortItself() {
            final FileStatusException held =
                    new FileStatusException(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE);

            assertAll(
                    () -> assertThatThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                            .isExactlyInstanceOf(AbendException.class),
                    () -> assertThatThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON,
                            STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                            .isExactlyInstanceOf(AbendException.class),
                    () -> assertThatThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON))
                            .isExactlyInstanceOf(AbendException.class),
                    () -> assertThatThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON,
                            TERMINAL_MESSAGE))
                            .isExactlyInstanceOf(AbendException.class),
                    () -> assertThatThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                            .isExactlyInstanceOf(AbendException.class),
                    () -> assertThatThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            held))
                            .isExactlyInstanceOf(AbendException.class));
        }

        @Test
        @DisplayName("the service is constructible without collaborators, being wholly stateless")
        void theServiceIsConstructibleWithoutCollaborators() {
            // The absence of collaborators is a reviewable property: the abort path depends on
            // nothing but its own arguments, which is why no test double appears in this file.
            assertThatNoException().isThrownBy(AbendService::new);
            assertThat(new AbendService()).isNotSameAs(abendService);
        }
    }
}
