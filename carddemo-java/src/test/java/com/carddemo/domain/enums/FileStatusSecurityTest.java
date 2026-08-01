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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link FileStatus}, the raw two-character COBOL file status.
 *
 * <h2>Why this enum is only half of the I/O model, and why the test says so</h2>
 *
 * <p>The batch programs never branch on the raw status directly. Each normalises it first -
 * {@code app/cbl/CBACT01C.cbl} L90-L114 maps {@code 00} onto zero, {@code 10} onto sixteen and
 * <em>everything else</em> onto twelve - and then branches on the level-88 names {@code APPL-AOK}
 * and {@code APPL-EOF}. {@code APPL-RESULT} is referenced 223 times estate-wide, and it is what the
 * programs actually test.</p>
 *
 * <p>The two predicates on this enum are the raw half of that model, and their narrowness is the
 * point. {@link FileStatus#isSuccess()} is true for {@code 00} <em>alone</em>, not for the other
 * status class 0 codes, because the normalisation treats every status other than {@code 00} and
 * {@code 10} as the error result. Widening either predicate would silently convert errors into
 * successes, so the assertions below pin each predicate to exactly one constant and prove the
 * remaining nine answer false to both.</p>
 *
 * <h2>Four codes are declared but unexercised, and that is deliberate</h2>
 *
 * <p>A census of the estate found only three literals compared in status-test context: {@code 00}
 * seventy-three times, {@code 10} seven times, and {@code 23} exactly once - the disclosure-group
 * default fallback in {@code CBACT04C}. The remaining declared codes are documented vocabulary a
 * live data set can return; {@code 22} and {@code 35} in particular are asserted by AAP 0.7.3 to be
 * documented-but-unexercised, so no code path may depend on them. This test therefore asserts they
 * <em>resolve</em> without asserting any behaviour is keyed off them.</p>
 */
@DisplayName("FileStatus - the raw two-character COBOL file status")
class FileStatusSecurityTest {

    /** Declared width of the split two-byte status field, from {@code 05 ACCTFILE-STAT1 PIC X} twice. */
    private static final int CODE_WIDTH = 2;

    /** The three literals a census found actually compared in status-test context. */
    private static final List<String> LITERALS_COMPARED_IN_THE_ESTATE = List.of("00", "10", "23");

    @Nested
    @DisplayName("Vocabulary recovered from the status literals present in the source")
    class Vocabulary {

        @Test
        @DisplayName("eleven constants are declared, one per literal appearing anywhere in the estate")
        void elevenConstantsAreDeclared() {
            assertThat(FileStatus.values()).hasSize(11);
        }

        @Test
        @DisplayName("the constants carry exactly the eleven literals the source contains, in ascending code order")
        void theConstantsCarryTheElevenSourceLiterals() {
            assertThat(Arrays.stream(FileStatus.values()).map(FileStatus::getCode).toList())
                    .containsExactly("00", "01", "02", "04", "05", "10", "12", "22", "23", "31", "35");
        }

        @Test
        @DisplayName("the constant names describe the condition rather than repeating the code, so a call site reads "
                + "as a condition and not as a magic number")
        void theConstantNamesDescribeTheCondition() {
            assertThat(Arrays.stream(FileStatus.values()).map(Enum::name).toList())
                    .containsExactly("SUCCESS", "SUCCESS_QUALIFIED", "DUPLICATE_ALTERNATE_KEY",
                            "RECORD_LENGTH_MISMATCH", "OPTIONAL_FILE_CREATED", "END_OF_FILE",
                            "AT_END_QUALIFIED", "DUPLICATE_KEY", "RECORD_NOT_FOUND",
                            "PERMANENT_ERROR", "FILE_NOT_FOUND");
        }

        @ParameterizedTest
        @EnumSource(FileStatus.class)
        @DisplayName("every code is exactly two characters wide with any leading zero preserved, matching the split "
                + "two-byte status field")
        void everyCodeIsExactlyTwoCharactersWide(final FileStatus status) {
            assertThat(status.getCode()).hasSize(CODE_WIDTH);
            assertThat(status.getCode().getBytes(StandardCharsets.US_ASCII)).hasSize(CODE_WIDTH);
        }

        @Test
        @DisplayName("the single-digit statuses keep their leading zero, so 00 is not 0 and 01 is not 1")
        void singleDigitStatusesKeepTheirLeadingZero() {
            assertThat(FileStatus.SUCCESS.getCode()).isEqualTo("00");
            assertThat(FileStatus.SUCCESS_QUALIFIED.getCode()).isEqualTo("01");
            assertThat(FileStatus.DUPLICATE_ALTERNATE_KEY.getCode()).isEqualTo("02");
            assertThat(FileStatus.RECORD_LENGTH_MISMATCH.getCode()).isEqualTo("04");
            assertThat(FileStatus.OPTIONAL_FILE_CREATED.getCode()).isEqualTo("05");
        }

        @Test
        @DisplayName("all eleven codes are distinct, which the unmodifiable-map collector enforces at class "
                + "initialisation rather than leaving one constant unreachable by lookup")
        void allElevenCodesAreDistinct() {
            assertThat(Arrays.stream(FileStatus.values()).map(FileStatus::getCode).distinct().count())
                    .isEqualTo(FileStatus.values().length);
        }
    }

    @Nested
    @DisplayName("Tolerant lookup from a raw status code")
    class CodeLookup {

        @ParameterizedTest
        @EnumSource(FileStatus.class)
        @DisplayName("every declared code round-trips through the lookup back to its constant")
        void everyDeclaredCodeRoundTrips(final FileStatus status) {
            assertThat(FileStatus.fromCode(status.getCode())).contains(status);
        }

        @Test
        @DisplayName("the three literals a census found compared in the estate all resolve, so the paths that exist "
                + "today are covered by the vocabulary")
        void theThreeComparedLiteralsAllResolve() {
            assertThat(LITERALS_COMPARED_IN_THE_ESTATE)
                    .allSatisfy(code -> assertThat(FileStatus.fromCode(code)).isPresent());
        }

        @Test
        @DisplayName("the two documented-but-unexercised codes 22 and 35 resolve as vocabulary, without any "
                + "behaviour being keyed off them")
        void theTwoDocumentedButUnexercisedCodesResolve() {
            assertThat(FileStatus.fromCode("22")).contains(FileStatus.DUPLICATE_KEY);
            assertThat(FileStatus.fromCode("35")).contains(FileStatus.FILE_NOT_FOUND);
            assertThat(FileStatus.DUPLICATE_KEY.isSuccess()).isFalse();
            assertThat(FileStatus.DUPLICATE_KEY.isEndOfFile()).isFalse();
            assertThat(FileStatus.FILE_NOT_FOUND.isSuccess()).isFalse();
            assertThat(FileStatus.FILE_NOT_FOUND.isEndOfFile()).isFalse();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an unset status field yields an empty result rather than throwing, so it cannot fail the very "
                + "diagnostic path that exists to report it")
        void anUnsetStatusFieldYieldsAnEmptyResult(final String code) {
            assertThat(FileStatus.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "  ", "0", "1", "9", "03", "06", "07", "08", "09", "11", "13",
                "20", "21", "24", "30", "32", "34", "36", "37", "39", "41", "42", "43", "44", "46",
                "47", "48", "49", "91", "99", "000", " 00", "00 ", "aa"})
        @DisplayName("a code a live data set can return but this enum does not declare yields an empty result, "
                + "because a status field is not repaired and not guessed")
        void anUndeclaredCodeYieldsAnEmptyResult(final String code) {
            assertThat(FileStatus.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("no trimming is applied, so a padded code does not resolve to the code it pads")
        void noTrimmingIsApplied() {
            assertThat(FileStatus.fromCode("00")).contains(FileStatus.SUCCESS);
            assertThat(FileStatus.fromCode("0 ")).isEmpty();
            assertThat(FileStatus.fromCode(" 0")).isEmpty();
        }
    }

    @Nested
    @DisplayName("The success predicate is narrow on purpose")
    class SuccessPredicate {

        @Test
        @DisplayName("only the 00 constant is a success")
        void onlyTheZeroZeroConstantIsASuccess() {
            assertThat(FileStatus.SUCCESS.isSuccess()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = FileStatus.class, names = "SUCCESS", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("no other constant is a success, including the other status class 0 codes 01, 02, 04 and 05, "
                + "because the CBACT01C normalisation maps every status but 00 and 10 onto the error result")
        void noOtherConstantIsASuccess(final FileStatus status) {
            assertThat(status.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the eleven constants reports itself a success, so the predicate cannot have "
                + "been widened without this test failing")
        void exactlyOneConstantReportsItselfASuccess() {
            assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isSuccess).count())
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The end-of-file predicate is equally narrow")
    class EndOfFilePredicate {

        @Test
        @DisplayName("only the 10 constant is at end of file")
        void onlyTheOneZeroConstantIsAtEndOfFile() {
            assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = FileStatus.class, names = "END_OF_FILE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("no other constant is at end of file, including the qualified at-end code 12")
        void noOtherConstantIsAtEndOfFile(final FileStatus status) {
            assertThat(status.isEndOfFile()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the eleven constants reports itself at end of file")
        void exactlyOneConstantReportsItselfAtEndOfFile() {
            assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isEndOfFile).count())
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The two predicates together, which is how every batch read loop uses them")
    class TheTwoPredicatesTogether {

        @Test
        @DisplayName("no constant is both a success and at end of file, so the two loop tests are disjoint")
        void noConstantIsBothSuccessAndEndOfFile() {
            assertThat(Arrays.stream(FileStatus.values()))
                    .noneMatch(status -> status.isSuccess() && status.isEndOfFile());
        }

        @Test
        @DisplayName("nine of the eleven constants answer false to both predicates, which is the terminal class the "
                + "normalisation collapses onto the error result")
        void nineConstantsAnswerFalseToBothPredicates() {
            assertThat(Arrays.stream(FileStatus.values())
                    .filter(status -> !status.isSuccess() && !status.isEndOfFile())
                    .count()).isEqualTo(9L);
        }

        @Test
        @DisplayName("the two codes the estate compares most, 00 and 10, are precisely the two the predicates "
                + "recognise, which is the correspondence the normalisation relies on")
        void theTwoRecognisedCodesAreTheTwoTheEstateCompares() {
            assertThat(Arrays.stream(FileStatus.values())
                    .filter(status -> status.isSuccess() || status.isEndOfFile())
                    .map(FileStatus::getCode)
                    .toList()).containsExactly("00", "10");
        }
    }
}
