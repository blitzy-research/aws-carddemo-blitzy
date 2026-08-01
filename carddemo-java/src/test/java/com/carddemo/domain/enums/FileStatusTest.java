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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code FileStatus}, the raw level of the two-level status model.
 *
 * <p>This type holds the raw two-character {@code FILE STATUS} vocabulary only. It is deliberately
 * <em>not</em> the level the programs branch on: they normalise a raw code into {@code APPL-RESULT}
 * first and then test the condition names {@code APPL-AOK} and {@code APPL-EOF}
 * ({@code app/cbl/CBACT01C.cbl} lines 90 to 114). Both levels exist in the translation because
 * collapsing them would erase the end-of-file-versus-error distinction that every batch read loop
 * depends on.
 *
 * <p><strong>Only one code is success and only one is end of file.</strong> The qualified variants
 * {@code 01}, {@code 02}, {@code 04}, {@code 05} and {@code 12} exist in the vocabulary but are
 * failures, because the normalisation cascade tests for the literal {@code '00'} and the literal
 * {@code '10'} and moves twelve for everything else. A predicate that treated any {@code 0x} code as
 * success, or {@code 12} as an at-end condition, would let a failed read look like a healthy one.
 *
 * <p><strong>Two codes are documented but never compared.</strong> The estate compares only
 * {@code 00}, {@code 01}, {@code 02}, {@code 04}, {@code 05}, {@code 10}, {@code 12}, {@code 23} and
 * {@code 31} anywhere in its source. The duplicate-key and file-not-found codes are named in the
 * project's own earlier specification but appear in no comparison, so they are carried as vocabulary
 * and no code path may depend on them. That divergence is recorded in the project decision log.
 */
@DisplayName("FileStatus: the raw two-character status vocabulary")
final class FileStatusTest {

    /** {@code FILE STATUS IS ACCTFILE-STATUS} is a two-byte field split into two one-byte items. */
    private static final int ORACLE_CODE_WIDTH = 2;

    /** The literal the normalisation cascade tests first; the only success code. */
    private static final String ORACLE_SUCCESS = "00";

    /** The literal the cascade tests second; the only at-end code. */
    private static final String ORACLE_END_OF_FILE = "10";

    /** The record-not-found literal, compared exactly once, in the interest program's fallback. */
    private static final String ORACLE_RECORD_NOT_FOUND = "23";

    /**
     * Every code the estate actually compares, in ascending order.
     *
     * <p>Counted across all twenty-eight programs: {@code '00'} seventy-three times, {@code '10'}
     * seven times and {@code '23'} once in status-test context, with the remainder appearing in the
     * source without being the subject of a comparison.
     */
    private static final List<String> ORACLE_CODES_COMPARED_IN_SOURCE =
            List.of("00", "01", "02", "04", "05", "10", "12", "23", "31");

    /** The two codes named in the earlier specification that no source comparison uses. */
    private static final List<String> ORACLE_CODES_DOCUMENTED_BUT_NOT_COMPARED = List.of("22", "35");

    @Nested
    @DisplayName("the vocabulary")
    final class Vocabulary {

        @Test
        @DisplayName("every code is exactly the width of the legacy status field")
        void everyCodeIsExactlyTheFieldWidth() {
            for (final FileStatus status : FileStatus.values()) {
                assertThat(status.getCode()).as("%s", status).hasSize(ORACLE_CODE_WIDTH);
            }
        }

        @Test
        @DisplayName("no two constants share a code")
        void noTwoConstantsShareACode() {
            final Set<String> codes = new LinkedHashSet<>();
            for (final FileStatus status : FileStatus.values()) {
                codes.add(status.getCode());
            }

            assertThat(codes).hasSameSizeAs(FileStatus.values());
        }

        @Test
        @DisplayName("every code the estate compares is present in the vocabulary")
        void everyComparedCodeIsPresent() {
            for (final String code : ORACLE_CODES_COMPARED_IN_SOURCE) {
                assertThat(FileStatus.fromCode(code)).as("compared code %s", code).isPresent();
            }
        }

        @Test
        @DisplayName("the two documented-but-uncompared codes are carried as vocabulary")
        void theDocumentedButUncomparedCodesAreCarried() {
            for (final String code : ORACLE_CODES_DOCUMENTED_BUT_NOT_COMPARED) {
                assertThat(FileStatus.fromCode(code)).as("documented code %s", code).isPresent();
            }
        }

        @Test
        @DisplayName("the vocabulary is the compared codes plus the two documented ones, and no more")
        void theVocabularyIsExactlyThoseTwoSets() {
            final Set<String> expected = new LinkedHashSet<>(ORACLE_CODES_COMPARED_IN_SOURCE);
            expected.addAll(ORACLE_CODES_DOCUMENTED_BUT_NOT_COMPARED);
            final Set<String> actual = new LinkedHashSet<>();
            for (final FileStatus status : FileStatus.values()) {
                actual.add(status.getCode());
            }

            assertThat(actual)
                    .as("no code may be invented beyond what the source or the specification names")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("the constants are declared in ascending code order, as the source lists them")
        void theConstantsAreDeclaredInAscendingCodeOrder() {
            String previous = null;
            for (final FileStatus status : FileStatus.values()) {
                if (previous != null) {
                    assertThat(status.getCode()).isGreaterThan(previous);
                }
                previous = status.getCode();
            }
        }
    }

    @Nested
    @DisplayName("fromCode: exact matching over the raw characters")
    final class FromCode {

        @Test
        @DisplayName("every constant round-trips through its own code")
        void everyConstantRoundTrips() {
            for (final FileStatus status : FileStatus.values()) {
                assertThat(FileStatus.fromCode(status.getCode())).as("%s", status).contains(status);
            }
        }

        @Test
        @DisplayName("a code outside the vocabulary yields no constant, never an exception")
        void aCodeOutsideTheVocabularyYieldsNothing() {
            assertThat(FileStatus.fromCode("99")).isEmpty();
            assertThat(FileStatus.fromCode("XY")).isEmpty();
            assertThat(FileStatus.fromCode("")).isEmpty();
        }

        @Test
        @DisplayName("an absent code yields no constant, because a diagnostic must not fail")
        void anAbsentCodeYieldsNothing() {
            assertThat(FileStatus.fromCode(null)).isEmpty();
        }

        @Test
        @DisplayName("matching is on the exact two characters: no trimming and no zero stripping")
        void matchingIsOnTheExactCharacters() {
            assertThat(FileStatus.fromCode("0")).as("one character cannot fill a two-byte field")
                    .isEmpty();
            assertThat(FileStatus.fromCode(" 0")).isEmpty();
            assertThat(FileStatus.fromCode("00 ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the two predicates, each true for exactly one constant")
    final class Predicates {

        @Test
        @DisplayName("success is true for the success code alone")
        void successIsTrueForTheSuccessCodeAlone() {
            for (final FileStatus status : FileStatus.values()) {
                assertThat(status.isSuccess())
                        .as("%s reports success", status)
                        .isEqualTo(ORACLE_SUCCESS.equals(status.getCode()));
            }
            assertThat(FileStatus.SUCCESS.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("the qualified success codes are failures, because the cascade tests a literal")
        void theQualifiedSuccessCodesAreFailures() {
            assertThat(FileStatus.SUCCESS_QUALIFIED.isSuccess()).isFalse();
            assertThat(FileStatus.DUPLICATE_ALTERNATE_KEY.isSuccess()).isFalse();
            assertThat(FileStatus.RECORD_LENGTH_MISMATCH.isSuccess()).isFalse();
            assertThat(FileStatus.OPTIONAL_FILE_CREATED.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("end of file is true for the at-end code alone")
        void endOfFileIsTrueForTheAtEndCodeAlone() {
            for (final FileStatus status : FileStatus.values()) {
                assertThat(status.isEndOfFile())
                        .as("%s reports end of file", status)
                        .isEqualTo(ORACLE_END_OF_FILE.equals(status.getCode()));
            }
            assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue();
        }

        @Test
        @DisplayName("the qualified at-end code is a failure, not an at-end condition")
        void theQualifiedAtEndCodeIsAFailure() {
            // Treating this as at end would terminate a read loop early and report a partial file as
            // a complete one.
            assertThat(FileStatus.AT_END_QUALIFIED.isEndOfFile()).isFalse();
            assertThat(FileStatus.AT_END_QUALIFIED.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("no constant reports both success and end of file")
        void noConstantReportsBoth() {
            for (final FileStatus status : FileStatus.values()) {
                assertThat(status.isSuccess() && status.isEndOfFile()).as("%s", status).isFalse();
            }
        }

        @Test
        @DisplayName("exactly nine of the eleven constants are neither success nor end of file")
        void exactlyNineConstantsAreFailures() {
            final long failures = List.of(FileStatus.values()).stream()
                    .filter(status -> !status.isSuccess() && !status.isEndOfFile())
                    .count();

            assertThat(failures).isEqualTo(FileStatus.values().length - 2L);
        }

        @Test
        @DisplayName("the record-not-found code is a failure at this level, whatever a caller does")
        void theRecordNotFoundCodeIsAFailureAtThisLevel() {
            // The interest program accepts it as its default-group fallback, but that is the caller's
            // own rule and not a property of the raw status.
            assertThat(FileStatus.fromCode(ORACLE_RECORD_NOT_FOUND))
                    .contains(FileStatus.RECORD_NOT_FOUND);
            assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse();
            assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse();
        }
    }
}
