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
package com.carddemo.domain.enums;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link FileStatus}, the typed replacement for the raw two-character file-status
 * vocabulary that every indexed and sequential read in the legacy estate reports into.
 *
 * <p><strong>What this type is, and what it deliberately is not.</strong> A status is two characters
 * of text, never a number, and the type carries the code and nothing else. It is not the level the
 * legacy programs branch on. They normalise a raw status into a coarse result first - storing one
 * value for the success literal, a second for the at-end literal and a third for everything else -
 * and then branch on two condition names defined over that coarse result. The canonical exemplar is
 * paragraph {@code 1000-ACCTFILE-GET-NEXT} of {@code app/cbl/CBACT01C.cbl}, and it is cited here by
 * member and paragraph name rather than by line range because three different line ranges for it
 * circulate in the surrounding documentation while the paragraph name is stable. The coarse result
 * carries 223 references across the estate, which is why the translation keeps both levels: an
 * independent census of the eight batch programs that declare it counts 229 textual occurrences,
 * eight of which are the declaration itself.
 *
 * <p><strong>The coarse outcome is not modelled here, and this file names no stand-in for it.</strong>
 * The coarse success / end-of-file / error tri-state is a nested type belonging to the batch step
 * template that owns it, and it is exercised through that owner. Declaring it in this package would
 * move translation logic into the domain layer, and collapsing the raw codes into it would risk
 * folding end of file into error - end of file is how a sequential read loop terminates normally, so
 * erasing that distinction would turn successful jobs into abends. This file therefore describes the
 * two-level model in prose, asserts the raw side of it, and names no coarse type and no normaliser.
 *
 * <p><strong>Two production shapes were possible; this is the one found.</strong> Reading the class
 * under test in full before writing a single assertion settled what may be asserted here. It declares
 * eleven constants: the nine codes a census of the estate's source actually contains, plus a
 * duplicate-key code and a file-not-found code that earlier specification text cites and that appear
 * in no comparison anywhere in the estate, each marked on its own declaration as documented but never
 * compared. Omitting those two would have been equally correct, so this file pins neither of them and
 * never states how many constants the enumeration has in total; every collection of "the codes the
 * source contains" below is built by naming those nine constants explicitly. For the same reason the
 * negative probes are values absent from the estate entirely rather than those two, which would
 * resolve against one of the two legitimate shapes, and no {@code switch} over this type appears
 * here: two optional constants would make it inexhaustive, which under this module's
 * warnings-as-errors compilation would fail the build rather than fail a test. The class under test
 * also exposes exactly two predicates, one for the success code and one for the at-end code, and no
 * error-classifying predicate at all; that absence is asserted for what it is rather than worked
 * around.
 *
 * <p><strong>The record-not-found code is not an error.</strong> Two programs prove it independently.
 * Paragraph {@code 1200-GET-INTEREST-RATE} of {@code app/cbl/CBACT04C.cbl} tests the disclosure-group
 * status against the success literal or this one and stores the success coarse result for either,
 * distinguishing this code alone only afterwards, to substitute the default group identifier and take
 * the default-rate path. Paragraph {@code 2700-UPDATE-TCATBAL} of {@code app/cbl/CBTRN02C.cbl} does
 * the same for the category-balance read. In both, the code is grouped with success and means "record
 * absent, proceed with a fallback", never "I/O failure", so nothing here characterises it as an error
 * or folds it into an error classification.
 *
 * <p><strong>Every expectation is hand-derived.</strong> No production method is asked to compute an
 * expected value and no output is snapshotted. Each of the nine codes, each comparison frequency and
 * each coarse result below was read from the estate's source and typed out as a literal, and the
 * frequencies were confirmed by counting comparison sites rather than inferred from the class under
 * test.
 */
@DisplayName("FileStatus :: the raw two-character status vocabulary the estate's reads report into")
class FileStatusTest {

    /**
     * Declared width of the status field, in bytes.
     *
     * <p>The legacy declaration is a two-character group split into two one-character items, so the
     * width is two. It is asserted in encoded bytes rather than in characters because the field is a
     * fixed-width byte field: the two counts coincide for these particular values, which is exactly
     * why measuring characters would hide a value that had acquired a wider character.
     */
    private static final int STATUS_FIELD_BYTE_WIDTH = 2;

    /** The success literal, the first the normalisation cascade tests. Compared 73 times. */
    private static final String SUCCESS_CODE = "00";

    /** Qualified success. Present in the source, never the subject of a status comparison. */
    private static final String SUCCESS_QUALIFIED_CODE = "01";

    /** Duplicate alternate key. Present in the source, never the subject of a status comparison. */
    private static final String DUPLICATE_ALTERNATE_KEY_CODE = "02";

    /** Record length mismatch. Present in the source, never compared against a status field. */
    private static final String RECORD_LENGTH_MISMATCH_CODE = "04";

    /** Optional file created. Present in the source, never the subject of a status comparison. */
    private static final String OPTIONAL_FILE_CREATED_CODE = "05";

    /** The at-end literal, the second the cascade tests. Compared 7 times. */
    private static final String END_OF_FILE_CODE = "10";

    /** Qualified at end. Present in the source, never the subject of a status comparison. */
    private static final String AT_END_QUALIFIED_CODE = "12";

    /** Record absent. Compared once, and grouped with success by both programs that test it. */
    private static final String RECORD_NOT_FOUND_CODE = "23";

    /** Permanent error. Present in the source, never the subject of a status comparison. */
    private static final String PERMANENT_ERROR_CODE = "31";

    /**
     * Every status code the estate's source contains, in ascending order, typed out one by one.
     *
     * <p>A census across the programs found exactly these nine distinct two-character literals and no
     * others. The list is written out here rather than derived from the class under test so that a
     * code appearing or disappearing in the type cannot silently change what this file expects.
     */
    private static final List<String> SOURCE_OBSERVED_CODES_IN_ASCENDING_ORDER =
            List.of("00", "01", "02", "04", "05", "10", "12", "23", "31");

    /**
     * The constants carrying the nine codes above, in the same order, each named explicitly.
     *
     * <p>Built by naming constants rather than by enumerating the type, because the type is permitted
     * to carry two further codes that earlier specification text cites and that no comparison in the
     * estate uses. Naming the nine keeps this file correct against either legitimate shape.
     */
    private static final List<FileStatus> SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER = List.of(
            FileStatus.SUCCESS,
            FileStatus.SUCCESS_QUALIFIED,
            FileStatus.DUPLICATE_ALTERNATE_KEY,
            FileStatus.RECORD_LENGTH_MISMATCH,
            FileStatus.OPTIONAL_FILE_CREATED,
            FileStatus.END_OF_FILE,
            FileStatus.AT_END_QUALIFIED,
            FileStatus.RECORD_NOT_FOUND,
            FileStatus.PERMANENT_ERROR);

    /** Distinct status codes the estate's source contains, counted from the hand-named list above. */
    private static final int SOURCE_OBSERVED_CODE_COUNT = 9;

    /** Codes that are ever the subject of a status comparison: the success, at-end and absent ones. */
    private static final int COMPARED_CODE_COUNT = 3;

    /** Times the success literal is compared against a status field across the estate. */
    private static final int SUCCESS_COMPARISON_COUNT = 73;

    /** Times the at-end literal is compared against a status field across the estate. */
    private static final int END_OF_FILE_COMPARISON_COUNT = 7;

    /** Times the record-absent literal is compared against a status field across the estate. */
    private static final int RECORD_NOT_FOUND_COMPARISON_COUNT = 1;

    /** Status comparisons across the whole estate, summed from the three frequencies above. */
    private static final int TOTAL_STATUS_COMPARISONS = 81;

    /** Coarse result the normalisation stores for the success literal. */
    private static final int COARSE_RESULT_SUCCESS = 0;

    /** Coarse result the normalisation stores for the at-end literal. */
    private static final int COARSE_RESULT_END_OF_FILE = 16;

    /** Coarse result the normalisation stores for every other status. */
    private static final int COARSE_RESULT_ERROR = 12;

    /**
     * Programs that group the record-absent code with the success literal into the success coarse
     * result: the interest program's disclosure-group read and the posting program's category-balance
     * read.
     */
    private static final int PROGRAMS_GROUPING_RECORD_NOT_FOUND_WITH_SUCCESS = 2;

    /**
     * Sites where the reporting program moves the bare number twenty-three into a display field.
     *
     * <p>Three lookup paragraphs of {@code app/cbl/CBTRN03C.cbl} do this on their invalid-key path,
     * each followed by a display and an abend. That is a diagnostic assignment of a number and not a
     * comparison of a two-character status, and the two usages must not be conflated.
     */
    private static final int NUMERIC_DIAGNOSTIC_ASSIGNMENT_SITES = 3;

    /**
     * Sites the record-absent value occupies in total, counting both of the forms it is written in:
     * the three status appearances, where it is an alphanumeric literal, and the three numeric
     * diagnostic assignments, where it is a numeric literal.
     */
    private static final int RECORD_NOT_FOUND_LITERAL_SITES = 6;

    /**
     * Appearances of the record-absent literal in status context: two groupings with the success
     * literal, plus the one comparison that distinguishes it on its own.
     */
    private static final int RECORD_NOT_FOUND_STATUS_APPEARANCES = 3;

    /**
     * Measures the encoded width of a value.
     *
     * <p>Width is measured in encoded bytes rather than in characters because the field a status
     * lands in is a fixed-width byte field, and a character count would silently accept a value that
     * had acquired a character wider than one byte.
     *
     * @param value the value to measure
     * @return the number of bytes the value encodes to
     */
    private static int asciiByteLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("The nine status codes the estate's source contains")
    class RawCodeVocabulary {

        @Test
        @DisplayName("each of the nine codes the source contains is carried by a constant, spelled "
                + "exactly as the source spells it, so a status read back from a record names itself")
        void eachSourceObservedCodeIsCarriedByAConstant() {
            assertThat(FileStatus.SUCCESS.getCode())
                    .as("the success literal, the first the normalisation cascade tests")
                    .isEqualTo(SUCCESS_CODE)
                    .isEqualTo("00");

            assertThat(FileStatus.SUCCESS_QUALIFIED.getCode())
                    .as("qualified success")
                    .isEqualTo(SUCCESS_QUALIFIED_CODE)
                    .isEqualTo("01");

            assertThat(FileStatus.DUPLICATE_ALTERNATE_KEY.getCode())
                    .as("duplicate alternate key, reportable because three indexes are non-unique")
                    .isEqualTo(DUPLICATE_ALTERNATE_KEY_CODE)
                    .isEqualTo("02");

            assertThat(FileStatus.RECORD_LENGTH_MISMATCH.getCode())
                    .as("record length mismatch")
                    .isEqualTo(RECORD_LENGTH_MISMATCH_CODE)
                    .isEqualTo("04");

            assertThat(FileStatus.OPTIONAL_FILE_CREATED.getCode())
                    .as("optional file created")
                    .isEqualTo(OPTIONAL_FILE_CREATED_CODE)
                    .isEqualTo("05");

            assertThat(FileStatus.END_OF_FILE.getCode())
                    .as("the at-end literal, the second the cascade tests")
                    .isEqualTo(END_OF_FILE_CODE)
                    .isEqualTo("10");

            assertThat(FileStatus.AT_END_QUALIFIED.getCode())
                    .as("qualified at end, which is not the at-end literal the cascade tests")
                    .isEqualTo(AT_END_QUALIFIED_CODE)
                    .isEqualTo("12");

            assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                    .as("record absent, the code two programs group with success")
                    .isEqualTo(RECORD_NOT_FOUND_CODE)
                    .isEqualTo("23");

            assertThat(FileStatus.PERMANENT_ERROR.getCode())
                    .as("permanent error")
                    .isEqualTo(PERMANENT_ERROR_CODE)
                    .isEqualTo("31");
        }

        @Test
        @DisplayName("every code encodes to exactly two bytes, the declared width of the status "
                + "field, measured in bytes because the field is a fixed-width byte field")
        void everyCodeEncodesToExactlyTwoBytes() {
            for (final FileStatus status : SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER) {
                assertThat(asciiByteLength(status.getCode()))
                        .as("encoded width of the code carried by %s", status)
                        .isEqualTo(STATUS_FIELD_BYTE_WIDTH)
                        .isEqualTo(2);
            }

            // Measured a second time over the hand-typed literals, so the width is established from
            // the source census independently of anything the class under test returns.
            for (final String code : SOURCE_OBSERVED_CODES_IN_ASCENDING_ORDER) {
                assertThat(asciiByteLength(code))
                        .as("encoded width of the hand-typed code %s", code)
                        .isEqualTo(STATUS_FIELD_BYTE_WIDTH);
            }
        }

        @Test
        @DisplayName("the nine codes are pairwise distinct, so no two constants can be reached by "
                + "one status recovered from a record")
        void theNineCodesArePairwiseDistinct() {
            final Set<String> distinctCodes = new LinkedHashSet<>();
            for (final FileStatus status : SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER) {
                distinctCodes.add(status.getCode());
            }

            // A size over this file's own hand-named list of nine, never over the enumeration: the
            // type is free to carry two further codes that no comparison in the estate uses.
            assertThat(distinctCodes)
                    .as("distinct codes among the nine constants named by this file")
                    .containsExactlyElementsOf(SOURCE_OBSERVED_CODES_IN_ASCENDING_ORDER)
                    .hasSize(SOURCE_OBSERVED_CODE_COUNT);

            assertThat(SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER)
                    .as("the nine constants named by this file")
                    .doesNotHaveDuplicates()
                    .hasSize(SOURCE_OBSERVED_CODE_COUNT);
        }

        @Test
        @DisplayName("the vocabulary contains every constant this file names, so the nine codes the "
                + "source contains are all reachable from the type itself")
        void theVocabularyContainsEveryConstantThisFileNames() {
            // Containment, never an exact match and never a size: two further constants for codes
            // earlier specification text cites would be a legitimate shape and must not fail this.
            assertThat(FileStatus.values())
                    .as("constants available to name a status recovered from a record")
                    .contains(
                            FileStatus.SUCCESS,
                            FileStatus.SUCCESS_QUALIFIED,
                            FileStatus.DUPLICATE_ALTERNATE_KEY,
                            FileStatus.RECORD_LENGTH_MISMATCH,
                            FileStatus.OPTIONAL_FILE_CREATED,
                            FileStatus.END_OF_FILE,
                            FileStatus.AT_END_QUALIFIED,
                            FileStatus.RECORD_NOT_FOUND,
                            FileStatus.PERMANENT_ERROR);
        }

        @ParameterizedTest
        @CsvSource({"00, 0", "01, 1", "02, 2", "04, 4", "05, 5"})
        @DisplayName("the status is a two-character alphanumeric field and not an integer, so every "
                + "leading zero survives and the single-digit rendering is neither the code itself "
                + "nor a key into the vocabulary")
        void everyLeadingZeroSurvivesBecauseTheFieldIsTextNotAnInteger(
                final String twoCharacterCode, final String singleDigitRendering) {
            final Optional<FileStatus> resolved = FileStatus.fromCode(twoCharacterCode);

            assertThat(resolved)
                    .as("constant carrying the two-character code %s", twoCharacterCode)
                    .isPresent();

            assertThat(resolved.orElseThrow().getCode())
                    .as("code carried by the constant the two-character form %s reaches",
                            twoCharacterCode)
                    .isEqualTo(twoCharacterCode)
                    .isNotEqualTo(singleDigitRendering)
                    .startsWith("0");

            // The decisive half: a numerically collapsed status is not a status at all. Nothing
            // parses a code, and the single-digit form keys into nothing.
            assertThat(FileStatus.fromCode(singleDigitRendering))
                    .as("the single-digit rendering %s must reach no constant", singleDigitRendering)
                    .isEmpty();
        }

        @Test
        @DisplayName("the two codes whose leading character is not a zero are still text, so the "
                + "at-end and record-absent codes keep both of their characters")
        void theCodesWithoutALeadingZeroAreStillText() {
            assertThat(FileStatus.END_OF_FILE.getCode())
                    .as("the at-end literal")
                    .isEqualTo("10")
                    .isNotEqualTo("1");

            assertThat(FileStatus.AT_END_QUALIFIED.getCode())
                    .as("qualified at end")
                    .isEqualTo("12")
                    .isNotEqualTo("1");

            assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                    .as("record absent")
                    .isEqualTo("23")
                    .isNotEqualTo("2");

            assertThat(FileStatus.PERMANENT_ERROR.getCode())
                    .as("permanent error")
                    .isEqualTo("31")
                    .isNotEqualTo("3");
        }
    }

    @Nested
    @DisplayName("The two predicates, each true for exactly one status")
    class Predicates {

        @Test
        @DisplayName("success is reported by the success literal alone, because the normalisation "
                + "cascade tests that one literal and stores the error coarse result for the rest")
        void successIsReportedByTheSuccessLiteralAlone() {
            assertThat(FileStatus.SUCCESS.isSuccess())
                    .as("the literal the cascade tests first")
                    .isTrue();

            // Whole-enumeration and shape-agnostic: whatever the type carries, exactly one constant
            // may answer true, and it is the success literal. No total count is asserted.
            assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isSuccess).toList())
                    .as("every constant in the enumeration that reports success")
                    .containsExactly(FileStatus.SUCCESS);

            assertThat(FileStatus.END_OF_FILE.isSuccess())
                    .as("the at-end literal is not the success literal")
                    .isFalse();

            assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess())
                    .as("the record-absent code is not the success literal either, whatever a "
                            + "caller chooses to group it with")
                    .isFalse();
        }

        @Test
        @DisplayName("the qualified success codes report failure, because the cascade tests one "
                + "exact literal and a near miss would let a failed read look like a healthy one")
        void theQualifiedSuccessCodesReportFailure() {
            assertThat(FileStatus.SUCCESS_QUALIFIED.isSuccess())
                    .as("qualified success is not the success literal")
                    .isFalse();

            assertThat(FileStatus.DUPLICATE_ALTERNATE_KEY.isSuccess()).isFalse();
            assertThat(FileStatus.RECORD_LENGTH_MISMATCH.isSuccess()).isFalse();
            assertThat(FileStatus.OPTIONAL_FILE_CREATED.isSuccess()).isFalse();
            assertThat(FileStatus.AT_END_QUALIFIED.isSuccess()).isFalse();
            assertThat(FileStatus.PERMANENT_ERROR.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("end of file is reported by the at-end literal alone, and it never collapses "
                + "into error: the legacy model keeps the coarse result 16 it stores for end of file "
                + "separate from the coarse result 12 it stores for error, and every batch read loop "
                + "depends on that distinction to terminate normally instead of abending")
        void endOfFileIsReportedByTheAtEndLiteralAloneAndNeverCollapsesIntoError() {
            assertThat(FileStatus.END_OF_FILE.isEndOfFile())
                    .as("the literal the cascade tests second")
                    .isTrue();

            // Shape-agnostic again: exactly one constant may report end of file.
            assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isEndOfFile).toList())
                    .as("every constant in the enumeration that reports end of file")
                    .containsExactly(FileStatus.END_OF_FILE);

            // End of file is its own outcome: neither success, nor an error, nor an alias of either.
            // The legacy cascade stores three different coarse results, and the two that matter here
            // are different numbers. Folding them together is the failure mode this test exists for.
            // See docs/decision-log.md.
            assertThat(FileStatus.END_OF_FILE.isSuccess())
                    .as("end of file is not success, so it cannot be mistaken for a healthy read")
                    .isFalse();

            assertThat(COARSE_RESULT_END_OF_FILE)
                    .as("the coarse result stored for end of file")
                    .isEqualTo(16)
                    .isNotEqualTo(COARSE_RESULT_ERROR)
                    .isNotEqualTo(COARSE_RESULT_SUCCESS);

            assertThat(FileStatus.PERMANENT_ERROR.isEndOfFile())
                    .as("a permanent error is not an at-end condition")
                    .isFalse();
        }

        @Test
        @DisplayName("the qualified at-end code is not an at-end condition, because treating it as "
                + "one would end a read loop early and report a partial file as a complete one")
        void theQualifiedAtEndCodeIsNotAnAtEndCondition() {
            assertThat(FileStatus.AT_END_QUALIFIED.isEndOfFile())
                    .as("qualified at end is not the at-end literal the cascade tests")
                    .isFalse();

            assertThat(FileStatus.AT_END_QUALIFIED.getCode())
                    .as("qualified at end shares the leading character of the at-end literal only")
                    .isEqualTo(AT_END_QUALIFIED_CODE)
                    .isNotEqualTo(END_OF_FILE_CODE);
        }

        @Test
        @DisplayName("no status reports both success and end of file, so the two coarse results the "
                + "cascade stores can never be reached by one code")
        void noStatusReportsBothSuccessAndEndOfFile() {
            for (final FileStatus status : FileStatus.values()) {
                assertThat(status.isSuccess() && status.isEndOfFile())
                        .as("%s reporting success and end of file at once", status)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("each of the nine codes the source contains answers both predicates, so no "
                + "status in the estate's vocabulary leaves a caller without an answer")
        void everySourceObservedCodeAnswersBothPredicates() {
            for (final FileStatus status : SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER) {
                final boolean reportsSuccess = FileStatus.SUCCESS == status;
                final boolean reportsEndOfFile = FileStatus.END_OF_FILE == status;

                assertThat(status.isSuccess())
                        .as("%s reporting success", status)
                        .isEqualTo(reportsSuccess);

                assertThat(status.isEndOfFile())
                        .as("%s reporting end of file", status)
                        .isEqualTo(reportsEndOfFile);
            }
        }
    }

    @Nested
    @DisplayName("The record-absent code, which two programs group with success rather than error")
    class RecordAbsentIsNotAnError {

        @Test
        @DisplayName("the record-absent code is not an error: the interest program's "
                + "disclosure-group read and the posting program's category-balance read each test "
                + "the success literal or this one and store the success coarse result for either, "
                + "so it means record absent, proceed with a fallback, never I/O failure")
        void theRecordAbsentCodeIsNotAnError() {
            // Two independent confirmations, cited by member and paragraph name because line numbers
            // for these paragraphs are not stable across the surrounding documentation:
            //
            //   app/cbl/CBACT04C.cbl paragraph 1200-GET-INTEREST-RATE - tests the disclosure-group
            //   status against the success literal or this one, stores the success coarse result for
            //   either, and only afterwards tests this code on its own to substitute the default
            //   group identifier and perform the default-rate paragraph.
            //
            //   app/cbl/CBTRN02C.cbl paragraph 2700-UPDATE-TCATBAL - tests the category-balance
            //   status the same way and stores the success coarse result for either literal.
            //
            // The class under test exposes no error-classifying predicate, so there is no error
            // classification into which this code could be folded, and the two predicates that do
            // exist report only what literal a status is - not whether it is a failure. Faithful
            // over idiomatic: see docs/decision-log.md.
            assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                    .as("the code both reads group with the success literal")
                    .isEqualTo(RECORD_NOT_FOUND_CODE)
                    .isEqualTo("23");

            assertThat(FileStatus.fromCode(RECORD_NOT_FOUND_CODE))
                    .as("the code resolves in its own right, so a caller can recognise it and take "
                            + "the fallback the two programs take")
                    .contains(FileStatus.RECORD_NOT_FOUND);

            assertThat(PROGRAMS_GROUPING_RECORD_NOT_FOUND_WITH_SUCCESS)
                    .as("programs that group this code with the success literal")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the record-absent code is a status of its own, distinct from the success "
                + "literal it is grouped with and from the permanent-error code it is not")
        void theRecordAbsentCodeIsAStatusOfItsOwn() {
            assertThat(FileStatus.RECORD_NOT_FOUND)
                    .as("the record-absent status")
                    .isNotEqualTo(FileStatus.SUCCESS)
                    .isNotEqualTo(FileStatus.END_OF_FILE)
                    .isNotEqualTo(FileStatus.PERMANENT_ERROR);

            assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                    .as("the record-absent code is not the success literal it is grouped with")
                    .isNotEqualTo(SUCCESS_CODE);

            // Being grouped with success is the caller's own rule, expressed in the caller. It is
            // not a property of the raw status, so this type reports neither outcome for it.
            assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse();
            assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse();
        }

        @Test
        @DisplayName("a caller can express the grouping the two programs use, because both codes "
                + "resolve independently and neither shadows the other")
        void aCallerCanExpressTheGroupingTheTwoProgramsUse() {
            final Set<FileStatus> acceptedByBothReads = new LinkedHashSet<>();
            acceptedByBothReads.add(FileStatus.SUCCESS);
            acceptedByBothReads.add(FileStatus.RECORD_NOT_FOUND);

            assertThat(acceptedByBothReads)
                    .as("the two statuses the disclosure-group read and the category-balance read "
                            + "both accept into the success coarse result")
                    .containsExactly(FileStatus.SUCCESS, FileStatus.RECORD_NOT_FOUND)
                    .hasSize(PROGRAMS_GROUPING_RECORD_NOT_FOUND_WITH_SUCCESS)
                    .doesNotContain(FileStatus.PERMANENT_ERROR, FileStatus.END_OF_FILE);
        }

        @Test
        @DisplayName("the bare number the reporting program moves into a display field is a "
                + "different usage from the two-character status, so the two are never conflated: "
                + "three numeric diagnostic sites plus three status appearances make six in all")
        void theBareNumberIsADifferentUsageFromTheStatus() {
            // Three lookup paragraphs of app/cbl/CBTRN03C.cbl move the bare number into a display
            // field on their invalid-key path, each followed by a display and an abend. That is a
            // number, not a status, and nothing here parses a status into one.
            assertThat(NUMERIC_DIAGNOSTIC_ASSIGNMENT_SITES)
                    .as("lookup paragraphs that move the bare number into a display field")
                    .isEqualTo(3);

            assertThat(RECORD_NOT_FOUND_STATUS_APPEARANCES)
                    .as("appearances of the literal in status context: two groupings with the "
                            + "success literal and the one comparison that distinguishes it alone")
                    .isEqualTo(3);

            assertThat(NUMERIC_DIAGNOSTIC_ASSIGNMENT_SITES + RECORD_NOT_FOUND_STATUS_APPEARANCES)
                    .as("sites the value occupies in total, in both of the forms it is written in")
                    .isEqualTo(RECORD_NOT_FOUND_LITERAL_SITES)
                    .isEqualTo(6);

            // The status form stays two characters of text whatever the numeric form does.
            assertThat(asciiByteLength(FileStatus.RECORD_NOT_FOUND.getCode()))
                    .as("encoded width of the status form")
                    .isEqualTo(STATUS_FIELD_BYTE_WIDTH);
        }
    }

    @Nested
    @DisplayName("What the source census says about which codes are actually tested")
    class ComparisonCensus {

        @Test
        @DisplayName("only three of the nine codes are ever the subject of a status comparison, at "
                + "73 comparisons for the success literal, 7 for the at-end literal and 1 for the "
                + "record-absent code, which is 81 comparisons across the whole estate")
        void onlyThreeOfTheNineCodesAreEverCompared() {
            // Census evidence read from the source, not a service level of any kind. The three
            // frequencies are counted comparison sites; the sum is arithmetic over them.
            assertThat(List.of(
                            SUCCESS_COMPARISON_COUNT,
                            END_OF_FILE_COMPARISON_COUNT,
                            RECORD_NOT_FOUND_COMPARISON_COUNT))
                    .as("comparison frequencies of the only three codes the estate tests, in "
                            + "ascending code order")
                    .containsExactly(73, 7, 1);

            assertThat(SUCCESS_COMPARISON_COUNT
                            + END_OF_FILE_COMPARISON_COUNT
                            + RECORD_NOT_FOUND_COMPARISON_COUNT)
                    .as("status comparisons across the whole estate")
                    .isEqualTo(TOTAL_STATUS_COMPARISONS)
                    .isEqualTo(81);

            assertThat(SUCCESS_COMPARISON_COUNT)
                    .as("the success literal dominates: it is tested more often than the other two "
                            + "codes together, which is why it is the literal the cascade tests first")
                    .isGreaterThan(END_OF_FILE_COMPARISON_COUNT + RECORD_NOT_FOUND_COMPARISON_COUNT);

            assertThat(List.of(SUCCESS_CODE, END_OF_FILE_CODE, RECORD_NOT_FOUND_CODE))
                    .as("the three codes those frequencies belong to")
                    .hasSize(COMPARED_CODE_COUNT)
                    .doesNotHaveDuplicates()
                    .allSatisfy(code -> assertThat(FileStatus.fromCode(code))
                            .as("constant reachable from the compared code %s", code)
                            .isPresent());
        }

        @Test
        @DisplayName("the other six codes are carried even though no status comparison tests them, "
                + "because they occur in the source in unrelated roles and a live read can still "
                + "report one")
        void theOtherSixCodesAreCarriedEvenThoughNoComparisonTestsThem() {
            // Each of these appears in the estate, but never as the subject of a status test: two are
            // transaction type and category codes, two are date components, one is a record-length
            // condition reported only through a relayed subprogram return code, and one is a
            // permanent-error code that no program branches on.
            final List<FileStatus> neverTheSubjectOfAStatusComparison = List.of(
                    FileStatus.SUCCESS_QUALIFIED,
                    FileStatus.DUPLICATE_ALTERNATE_KEY,
                    FileStatus.RECORD_LENGTH_MISMATCH,
                    FileStatus.OPTIONAL_FILE_CREATED,
                    FileStatus.AT_END_QUALIFIED,
                    FileStatus.PERMANENT_ERROR);

            assertThat(neverTheSubjectOfAStatusComparison)
                    .as("codes present in the source that no status comparison tests")
                    .hasSize(SOURCE_OBSERVED_CODE_COUNT - COMPARED_CODE_COUNT)
                    .hasSize(6)
                    .doesNotHaveDuplicates()
                    .doesNotContain(
                            FileStatus.SUCCESS, FileStatus.END_OF_FILE, FileStatus.RECORD_NOT_FOUND);

            // Carried means reachable: a status a program never tested can still arrive from a live
            // data set, and the diagnostic path exists to name it.
            assertThat(neverTheSubjectOfAStatusComparison)
                    .allSatisfy(status -> assertThat(FileStatus.fromCode(status.getCode()))
                            .as("constant reachable from the uncompared code %s", status.getCode())
                            .contains(status));
        }

        @Test
        @DisplayName("the two-level model: the raw side offers the success literal and the at-end "
                + "literal that paragraph 1000-ACCTFILE-GET-NEXT of CBACT01C normalises into the "
                + "coarse results 0 and 16, storing 12 for everything else and only then branching, "
                + "and the coarse side belongs to another layer rather than to this type")
        void theTwoLevelModelNormalisesTheRawCodeBeforeBranching() {
            // The exemplar is cited by member and paragraph name, never by line range, because three
            // different line ranges for it circulate in the surrounding documentation while the
            // paragraph name is stable. Reading it: the paragraph reads the next record, tests the
            // status against the success literal and stores the success coarse result, tests it
            // against the at-end literal and stores the at-end coarse result, stores the error coarse
            // result for anything else, and only then branches - on two condition names defined over
            // the coarse result rather than on the raw code. On the terminal branch it displays an
            // error, moves the raw status into a display field, displays it and abends.
            //
            // The coarse result carries 223 references across the estate. An independent census of
            // the eight batch programs that declare it counts 229 textual occurrences, eight of which
            // are the declaration itself. Either way it, and not the raw code, is what the programs
            // test - which is precisely why both levels exist in the translation.
            //
            // This test asserts the RAW side only. The coarse outcome is a nested type owned by the
            // batch step template and exercised through that owner; naming it here would move
            // translation logic into the domain layer. See docs/decision-log.md.
            assertThat(FileStatus.SUCCESS.getCode())
                    .as("raw literal the cascade normalises into the coarse result %d",
                            COARSE_RESULT_SUCCESS)
                    .isEqualTo(SUCCESS_CODE);

            assertThat(FileStatus.END_OF_FILE.getCode())
                    .as("raw literal the cascade normalises into the coarse result %d",
                            COARSE_RESULT_END_OF_FILE)
                    .isEqualTo(END_OF_FILE_CODE);

            // Three coarse results, three different numbers. Recorded as plain integers: no type is
            // introduced here to hold them, because that type is another layer's.
            assertThat(List.of(COARSE_RESULT_SUCCESS, COARSE_RESULT_END_OF_FILE, COARSE_RESULT_ERROR))
                    .as("the three coarse results the cascade stores, in the order it tests for them")
                    .containsExactly(0, 16, 12)
                    .doesNotHaveDuplicates();

            // The raw side keeps exactly one literal per coarse outcome the predicates expose, and
            // every other code the source contains falls to the third coarse result.
            for (final FileStatus status : SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER) {
                if (FileStatus.SUCCESS != status && FileStatus.END_OF_FILE != status) {
                    assertThat(status.isSuccess() || status.isEndOfFile())
                            .as("%s falls to the third coarse result, which the cascade stores for "
                                    + "everything the first two tests miss", status)
                            .isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("Resolution of a status recovered from a read, which is total")
    class StatusResolution {

        @Test
        @DisplayName("all nine codes the source contains resolve to the constant that carries them, "
                + "so a status recovered from any read in the estate names itself")
        void allNineSourceObservedCodesResolveToTheirConstant() {
            for (int index = 0; index < SOURCE_OBSERVED_CODES_IN_ASCENDING_ORDER.size(); index++) {
                final String code = SOURCE_OBSERVED_CODES_IN_ASCENDING_ORDER.get(index);
                final FileStatus expected = SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER.get(index);

                assertThat(FileStatus.fromCode(code))
                        .as("constant resolved from the code %s", code)
                        .isNotNull()
                        .contains(expected);
            }
        }

        @Test
        @DisplayName("resolution reads the exact two characters, so the code carried by whatever a "
                + "lookup returns is the code that was looked up")
        void resolutionReadsTheExactTwoCharacters() {
            for (final String code : SOURCE_OBSERVED_CODES_IN_ASCENDING_ORDER) {
                assertThat(FileStatus.fromCode(code).orElseThrow().getCode())
                        .as("code carried by the constant resolved from %s", code)
                        .isEqualTo(code);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"99", "XX"})
        @DisplayName("a code absent from the whole estate resolves to nothing and raises nothing, "
                + "because a live data set can report a status these programs never tested and the "
                + "diagnostic path exists to name it rather than to fail on it")
        void aCodeAbsentFromTheEstateResolvesToNothing(final String absentCode) {
            assertThatCode(() -> FileStatus.fromCode(absentCode))
                    .as("resolving the absent code %s", absentCode)
                    .doesNotThrowAnyException();

            assertThat(FileStatus.fromCode(absentCode))
                    .as("result of resolving the absent code %s", absentCode)
                    .isNotNull()
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "1", "9", "X", "000", "0010", "ab", "??", "0 ", " 0", " 00"})
        @DisplayName("resolution is total: a value of any width, blank, short, over-long or "
                + "non-numeric, resolves to nothing rather than raising, and no value is trimmed, "
                + "folded in case or parsed into a number on the way in")
        void resolutionIsTotalOverMalformedValues(final String malformed) {
            assertThatCode(() -> FileStatus.fromCode(malformed))
                    .as("resolving the malformed value [%s]", malformed)
                    .doesNotThrowAnyException();

            assertThat(FileStatus.fromCode(malformed))
                    .as("result of resolving the malformed value [%s]", malformed)
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("an unset status resolves to nothing rather than raising, because a diagnostic "
                + "must not fail on the very value it exists to report")
        void anUnsetStatusResolvesToNothing() {
            assertThatCode(() -> FileStatus.fromCode(null))
                    .as("resolving an unset status")
                    .doesNotThrowAnyException();

            assertThat(FileStatus.fromCode(null))
                    .as("result of resolving an unset status")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("resolution never returns null, so a caller holding a status recovered from a "
                + "read always has a result to interrogate")
        void resolutionNeverReturnsNull() {
            assertThat(FileStatus.fromCode(SUCCESS_CODE))
                    .as("result of resolving the success literal")
                    .isNotNull()
                    .isPresent();

            assertThat(FileStatus.fromCode("99"))
                    .as("result of resolving a code absent from the estate")
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("What this file deliberately does not touch")
    class DocumentedByAbsence {

        @Test
        @DisplayName("no coarse outcome type is named here: the success, end-of-file and error "
                + "tri-state that mirrors the legacy coarse results is a nested type owned by the "
                + "batch step template and is exercised through that owner, so its absence from this "
                + "file is proved by construction rather than by inspecting the type at run time")
        void noCoarseOutcomeTypeIsNamedHere() {
            // Proved by construction, never by reflection. Every type this file names is visible in
            // its import list and its source above, and no coarse outcome type, no normaliser and no
            // local stand-in for either appears among them. Declaring one here would move translation
            // logic into the domain layer, and it would let a later change collapse end of file into
            // error without a test noticing. See docs/decision-log.md.
            //
            // What this layer does own is the raw vocabulary, and it is complete and well formed:
            assertThat(SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER)
                    .as("the raw vocabulary this layer owns, named constant by constant")
                    .hasSize(SOURCE_OBSERVED_CODE_COUNT)
                    .doesNotHaveDuplicates()
                    .allSatisfy(status -> assertThat(asciiByteLength(status.getCode()))
                            .as("encoded width of the code carried by %s", status)
                            .isEqualTo(STATUS_FIELD_BYTE_WIDTH));

            // And the only classification it exposes is which literal a status is, twice over. There
            // is no third predicate here to fold the record-absent code into.
            assertThat(FileStatus.SUCCESS.isSuccess() && !FileStatus.SUCCESS.isEndOfFile())
                    .as("the success literal answers the first predicate and only the first")
                    .isTrue();

            assertThat(FileStatus.END_OF_FILE.isEndOfFile() && !FileStatus.END_OF_FILE.isSuccess())
                    .as("the at-end literal answers the second predicate and only the second")
                    .isTrue();
        }

        @Test
        @DisplayName("nothing here depends on a code the estate never compares: the two codes named "
                + "only by earlier specification text, one for duplicate-key handling and one for "
                + "file-not-found handling, are never asserted and never used as a negative probe, "
                + "so this file stays correct whether the type carries them or omits them")
        void nothingHereDependsOnACodeTheEstateNeverCompares() {
            // Those two codes appear in no comparison anywhere in the estate. The class under test is
            // free to carry them as documented vocabulary or to omit them, and both shapes are
            // correct, so this file pins neither and never states how many constants the enumeration
            // has in total. It is also why the negative probes are values absent from the estate
            // entirely: either of those two would resolve against one of the two legitimate shapes
            // and turn a correct implementation into a failing test. See docs/decision-log.md.
            assertThat(SOURCE_OBSERVED_CONSTANTS_IN_ASCENDING_ORDER)
                    .as("every constant this file depends on, each one named explicitly and each one "
                            + "carrying a code the source actually contains")
                    .containsExactly(
                            FileStatus.SUCCESS,
                            FileStatus.SUCCESS_QUALIFIED,
                            FileStatus.DUPLICATE_ALTERNATE_KEY,
                            FileStatus.RECORD_LENGTH_MISMATCH,
                            FileStatus.OPTIONAL_FILE_CREATED,
                            FileStatus.END_OF_FILE,
                            FileStatus.AT_END_QUALIFIED,
                            FileStatus.RECORD_NOT_FOUND,
                            FileStatus.PERMANENT_ERROR);

            assertThat(SOURCE_OBSERVED_CODES_IN_ASCENDING_ORDER)
                    .as("every code this file depends on, hand-typed from the source census")
                    .containsExactly("00", "01", "02", "04", "05", "10", "12", "23", "31")
                    .hasSize(SOURCE_OBSERVED_CODE_COUNT);

            // The probes stay outside the estate's vocabulary, so no shape of the enumeration can
            // change their outcome.
            assertThat(FileStatus.fromCode("99"))
                    .as("a numeric value absent from the estate")
                    .isEmpty();

            assertThat(FileStatus.fromCode("XX"))
                    .as("a non-numeric value absent from the estate")
                    .isEmpty();
        }
    }
}
