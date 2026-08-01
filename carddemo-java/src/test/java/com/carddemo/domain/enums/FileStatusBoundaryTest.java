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
import java.util.EnumSet;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileStatus}, the raw two-byte file-status vocabulary of the
 * batch tier.
 *
 * <h2>What is under test</h2>
 *
 * <p>Each batch program declares a two-byte status field on its file and then
 * normalises it into a coarser result variable before branching, as
 * {@code app/cbl/CBACT01C.cbl} does at lines 90 through 114: the success code
 * becomes zero, the end-of-file code becomes sixteen and anything else becomes
 * twelve, after which the program tests the named conditions rather than the raw
 * code. This enum models the raw layer only. The coarser tri-state layer is a
 * separate type, because collapsing the two would erase the end-of-file versus
 * error distinction that every batch read loop depends on.</p>
 *
 * <h2>Two codes are documented but never compared, and that is asserted here</h2>
 *
 * <p>A census of the estate found that only three codes are ever compared in a
 * status test: the success code seventy-three times, the end-of-file code seven
 * times and the record-not-found code exactly once, in the disclosure-group default
 * fallback of {@code app/cbl/CBACT04C.cbl}. The full literal vocabulary appearing
 * anywhere in the source adds the qualified-success, duplicate-alternate-key,
 * record-length-mismatch, optional-file-created, at-end-qualified and
 * permanent-error codes. The duplicate-key and file-not-found codes appear in the
 * technical specification but in no COBOL statement, so they are defined as
 * documented-but-unexercised values and no production branch may depend on them.
 * This class asserts that they are present and that they behave as ordinary
 * non-success, non-end-of-file codes, which is the whole of their contract.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("FileStatus: the raw two-byte file-status vocabulary of the batch tier")
class FileStatusBoundaryTest {

    /** Number of status codes the type models. */
    private static final int STATUS_COUNT = 11;

    /** Width of the two-byte status field, in bytes. */
    private static final int STATUS_FIELD_WIDTH = 2;

    /** The three codes the estate actually compares in a status test. */
    private static final EnumSet<FileStatus> COMPARED_IN_SOURCE =
            EnumSet.of(FileStatus.SUCCESS, FileStatus.END_OF_FILE, FileStatus.RECORD_NOT_FOUND);

    /** The two codes the specification names but no COBOL statement compares. */
    private static final EnumSet<FileStatus> DOCUMENTED_BUT_UNEXERCISED =
            EnumSet.of(FileStatus.DUPLICATE_KEY, FileStatus.FILE_NOT_FOUND);

    @Nested
    @DisplayName("the modelled vocabulary")
    class ModelledVocabulary {

        @Test
        @DisplayName("eleven codes are modelled, covering the observed literals and the two documented ones")
        void elevenCodesAreModelled() {
            assertThat(FileStatus.values()).hasSize(STATUS_COUNT);
        }

        @Test
        @DisplayName("the success code is the two-character zero literal")
        void theSuccessCodeIsTheZeroLiteral() {
            assertThat(FileStatus.SUCCESS.getCode()).isEqualTo("00");
        }

        @Test
        @DisplayName("the end-of-file code is the two-character ten literal")
        void theEndOfFileCodeIsTheTenLiteral() {
            assertThat(FileStatus.END_OF_FILE.getCode()).isEqualTo("10");
        }

        @Test
        @DisplayName("the record-not-found code is the literal the interest fallback tests")
        void theRecordNotFoundCodeIsTheFallbackLiteral() {
            assertThat(FileStatus.RECORD_NOT_FOUND.getCode()).isEqualTo("23");
        }

        @Test
        @DisplayName("every code occupies exactly two bytes, matching the split status field")
        void everyCodeOccupiesTwoBytes() {
            for (FileStatus status : FileStatus.values()) {
                assertThat(status.getCode().getBytes(StandardCharsets.US_ASCII))
                        .hasSize(STATUS_FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("every code is two digits, so neither byte is alphabetic")
        void everyCodeIsTwoDigits() {
            for (FileStatus status : FileStatus.values()) {
                assertThat(status.getCode()).containsOnlyDigits();
            }
        }

        @Test
        @DisplayName("every code is distinct, so a raw status resolves to one constant")
        void everyCodeIsDistinct() {
            assertThat(Arrays.stream(FileStatus.values()).map(FileStatus::getCode))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the declaration order follows ascending code order")
        void theDeclarationOrderFollowsAscendingCodeOrder() {
            assertThat(Arrays.stream(FileStatus.values()).map(FileStatus::getCode)).isSorted();
        }
    }

    @Nested
    @DisplayName("the two coarse predicates")
    class CoarsePredicates {

        @Test
        @DisplayName("only the success code reports success")
        void onlyTheSuccessCodeReportsSuccess() {
            assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isSuccess))
                    .containsExactly(FileStatus.SUCCESS);
        }

        @Test
        @DisplayName("only the end-of-file code reports end of file")
        void onlyTheEndOfFileCodeReportsEndOfFile() {
            assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isEndOfFile))
                    .containsExactly(FileStatus.END_OF_FILE);
        }

        @Test
        @DisplayName("the qualified-success code does not report success, as the normalisation requires")
        void theQualifiedSuccessCodeDoesNotReportSuccess() {
            assertThat(FileStatus.SUCCESS_QUALIFIED.isSuccess()).isFalse();
            assertThat(FileStatus.SUCCESS_QUALIFIED.isEndOfFile()).isFalse();
        }

        @Test
        @DisplayName("the at-end-qualified code does not report end of file, so the two are not conflated")
        void theAtEndQualifiedCodeDoesNotReportEndOfFile() {
            assertThat(FileStatus.AT_END_QUALIFIED.isEndOfFile()).isFalse();
            assertThat(FileStatus.AT_END_QUALIFIED.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("no code reports both success and end of file")
        void noCodeReportsBothSuccessAndEndOfFile() {
            for (FileStatus status : FileStatus.values()) {
                assertThat(status.isSuccess() && status.isEndOfFile()).isFalse();
            }
        }

        @Test
        @DisplayName("the two predicates leave nine codes as the error class, matching the coarse twelve")
        void theTwoPredicatesLeaveNineCodesAsTheErrorClass() {
            assertThat(Arrays.stream(FileStatus.values())
                    .filter(status -> !status.isSuccess() && !status.isEndOfFile()))
                    .hasSize(STATUS_COUNT - 2);
        }
    }

    @Nested
    @DisplayName("the codes the estate actually compares")
    class ComparedInSource {

        @ParameterizedTest(name = "the compared literal [{0}] resolves")
        @ValueSource(strings = {"00", "10", "23"})
        @DisplayName("each of the three compared literals resolves to a modelled code")
        void eachComparedLiteralResolves(String code) {
            assertThat(FileStatus.fromCode(code)).isPresent();
        }

        @Test
        @DisplayName("the three compared codes are the success, end-of-file and record-not-found constants")
        void theThreeComparedCodesAreTheExpectedConstants() {
            assertThat(COMPARED_IN_SOURCE)
                    .containsExactly(FileStatus.SUCCESS, FileStatus.END_OF_FILE,
                            FileStatus.RECORD_NOT_FOUND);
        }

        @ParameterizedTest(name = "the observed literal [{0}] resolves")
        @ValueSource(strings = {"00", "01", "02", "04", "05", "10", "12", "23", "31"})
        @DisplayName("every literal appearing anywhere in the estate resolves to a modelled code")
        void everyObservedLiteralResolves(String code) {
            assertThat(FileStatus.fromCode(code)).isPresent();
        }
    }

    @Nested
    @DisplayName("the codes documented but never compared")
    class DocumentedButUnexercised {

        @Test
        @DisplayName("the duplicate-key code is defined, so the documented vocabulary is complete")
        void theDuplicateKeyCodeIsDefined() {
            assertThat(FileStatus.DUPLICATE_KEY.getCode()).isEqualTo("22");
        }

        @Test
        @DisplayName("the file-not-found code is defined, so the documented vocabulary is complete")
        void theFileNotFoundCodeIsDefined() {
            assertThat(FileStatus.FILE_NOT_FOUND.getCode()).isEqualTo("35");
        }

        @Test
        @DisplayName("both documented codes behave as ordinary error-class codes and carry no special case")
        void bothDocumentedCodesBehaveAsOrdinaryErrorCodes() {
            for (FileStatus status : DOCUMENTED_BUT_UNEXERCISED) {
                assertThat(status.isSuccess()).isFalse();
                assertThat(status.isEndOfFile()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("resolution from a raw code")
    class ResolutionFromCode {

        @Test
        @DisplayName("every modelled code resolves to its own constant")
        void everyModelledCodeResolves() {
            for (FileStatus status : FileStatus.values()) {
                Optional<FileStatus> resolved = FileStatus.fromCode(status.getCode());

                assertThat(resolved).containsSame(status);
            }
        }

        @ParameterizedTest(name = "the unmodelled code [{0}] resolves to nothing")
        @ValueSource(strings = {
            "03", "06", "07", "08", "09", "11", "13", "20", "21", "24", "30", "34", "37", "39",
            "41", "42", "43", "44", "46", "47", "48", "49", "9A", "0", "000", " 0", "0 ", "  ",
            "ab", "AB"})
        @DisplayName("an unmodelled code resolves to an empty result rather than a catch-all constant")
        void anUnmodelledCodeResolvesToNothing(String code) {
            assertThat(FileStatus.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a null code resolves to an empty result rather than throwing")
        void aNullCodeResolvesToNothing(String code) {
            assertThat(FileStatus.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @EmptySource
        @DisplayName("an empty code resolves to an empty result rather than throwing")
        void anEmptyCodeResolvesToNothing(String code) {
            assertThat(FileStatus.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("resolution is width-strict, so a single-digit code is refused rather than padded")
        void resolutionIsWidthStrict() {
            assertThat(FileStatus.fromCode("0")).isEmpty();
            assertThat(FileStatus.fromCode("1")).isEmpty();
            assertThat(FileStatus.fromCode("023")).isEmpty();
        }
    }
}
