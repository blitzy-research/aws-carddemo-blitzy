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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link RejectReason}, the five reject reason codes {@code CBTRN02C} emits into the
 * 80-byte trailer of a 430-byte reject record.
 *
 * <h2>Why the widths and the literals are asserted, not just the codes</h2>
 *
 * <p>The reject record is 430 bytes: the 350-byte daily-transaction image concatenated with a trailer
 * of {@code PIC 9(04)} reason code plus {@code PIC X(76)} description
 * ({@code app/cbl/CBTRN02C.cbl} L181-L182). Gate 1 compares those bytes. A reason code that does not
 * fit four digits, or a description that does not fit 76 characters, would produce a record of the
 * wrong shape, so both bounds are asserted here rather than left to the writer to discover at
 * runtime.</p>
 *
 * <h2>Zero is absence, not a reason</h2>
 *
 * <p>The reason field starts at zero and stays there for a transaction that posts successfully -
 * posting proceeds only when the code is zero ({@code CBTRN02C} L211, L372). Zero is therefore the
 * initial not-rejected state rather than a reject reason, and the lookup models it as absence. That
 * single assertion is what stops a future edit from adding a synthetic {@code NONE(0, ...)} constant
 * and quietly making every successful posting look like a rejection.</p>
 */
@DisplayName("RejectReason - the five CBTRN02C reject codes")
class RejectReasonSecurityTest {

    /** Declared width of the reason field, from its {@code PIC 9(04)} clause. */
    private static final int REASON_CODE_DIGITS = 4;

    /** Declared width of the description field, from its {@code PIC X(76)} clause. */
    private static final int DESCRIPTION_WIDTH = 76;

    /** Width of the daily-transaction image the trailer is appended to. */
    private static final int SOURCE_IMAGE_WIDTH = 350;

    /** Total width of a reject record. */
    private static final int REJECT_RECORD_WIDTH = 430;

    @Nested
    @DisplayName("Vocabulary recovered from the five emitting sites in CBTRN02C")
    class Vocabulary {

        @Test
        @DisplayName("exactly five constants are declared, one per site that sets a reason code")
        void exactlyFiveConstantsAreDeclared() {
            assertThat(RejectReason.values()).hasSize(5);
        }

        @Test
        @DisplayName("the five codes are 100, 101, 102, 103 and 109 in the order the validation cascade reaches them")
        void theFiveCodesAreDeclaredInCascadeOrder() {
            assertThat(Arrays.stream(RejectReason.values()).map(RejectReason::getReasonCode).toList())
                    .containsExactly(100, 101, 102, 103, 109);
        }

        @Test
        @DisplayName("the codes are not contiguous: 104 through 108 are absent because the legacy program never "
                + "emits them, and inventing them would create reasons the estate cannot produce")
        void theCodesAreDeliberatelyNotContiguous() {
            for (int code = 104; code <= 108; code++) {
                assertThat(RejectReason.byReasonCode(code)).isEmpty();
            }
        }

        @Test
        @DisplayName("each constant carries the description literal the legacy program writes, verbatim")
        void eachConstantCarriesItsLegacyDescription() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getDescription())
                    .isEqualTo("INVALID CARD NUMBER FOUND");
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getDescription())
                    .isEqualTo("OVERLIMIT TRANSACTION");
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getDescription())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("two distinct codes share the same description, 101 on the read path and 109 on the rewrite "
                + "path, and the enum keeps them as separate constants because the codes differ")
        void twoDistinctCodesShareOneDescription() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription());
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode())
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode());
        }

        @Test
        @DisplayName("all five codes are distinct, so the reverse index cannot collide")
        void allFiveCodesAreDistinct() {
            assertThat(Arrays.stream(RejectReason.values())
                    .map(RejectReason::getReasonCode).distinct().count()).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("Trailer widths, because Gate 1 compares the bytes")
    class TrailerWidths {

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("every reason code fits the four digits PIC 9(04) allows")
        void everyReasonCodeFitsFourDigits(final RejectReason reason) {
            assertThat(String.valueOf(reason.getReasonCode()).length())
                    .isLessThanOrEqualTo(REASON_CODE_DIGITS);
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("every reason code is positive, so it needs no sign position inside PIC 9(04)")
        void everyReasonCodeIsPositive(final RejectReason reason) {
            assertThat(reason.getReasonCode()).isPositive();
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("every description fits the 76 characters PIC X(76) allows, so no description is truncated "
                + "when the trailer is assembled")
        void everyDescriptionFitsSeventySixCharacters(final RejectReason reason) {
            assertThat(reason.getDescription().length()).isLessThanOrEqualTo(DESCRIPTION_WIDTH);
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("every description encodes to single-byte US-ASCII, so its character count is its byte count "
                + "in the fixed-width trailer")
        void everyDescriptionIsSingleByteAscii(final RejectReason reason) {
            assertThat(reason.getDescription().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(reason.getDescription().length());
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("every description is non-blank, because a reject record with an empty explanation is useless "
                + "to the operator who reads it")
        void everyDescriptionIsNonBlank(final RejectReason reason) {
            assertThat(reason.getDescription()).isNotBlank();
        }

        @Test
        @DisplayName("the four-digit reason plus the 76-character description sum to the 80-byte trailer, which "
                + "appended to the 350-byte image is the 430-byte reject record")
        void theTrailerWidthsSumToTheRecordWidth() {
            assertThat(REASON_CODE_DIGITS + DESCRIPTION_WIDTH).isEqualTo(80);
            assertThat(SOURCE_IMAGE_WIDTH + REASON_CODE_DIGITS + DESCRIPTION_WIDTH)
                    .isEqualTo(REJECT_RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("Lookup from a recovered reason code")
    class ReasonCodeLookup {

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("every declared code round-trips through the lookup back to its constant")
        void everyDeclaredCodeRoundTrips(final RejectReason reason) {
            assertThat(RejectReason.byReasonCode(reason.getReasonCode())).contains(reason);
        }

        @Test
        @DisplayName("zero yields an empty result, because zero is the initial not-rejected state of the reason "
                + "field rather than a reject reason")
        void zeroYieldsAnEmptyResult() {
            assertThat(RejectReason.byReasonCode(0)).isEmpty();
        }

        @Test
        @DisplayName("no synthetic constant carries the code zero, so a successfully posted transaction can never "
                + "resolve to a rejection")
        void noConstantCarriesTheCodeZero() {
            assertThat(Arrays.stream(RejectReason.values()))
                    .noneMatch(reason -> reason.getReasonCode() == 0);
        }

        @ParameterizedTest
        @ValueSource(ints = {-109, -1, 1, 99, 104, 105, 106, 107, 108, 110, 200, 999, 1000, Integer.MAX_VALUE,
                Integer.MIN_VALUE})
        @DisplayName("a code the legacy program does not emit yields an empty result rather than throwing")
        void anUnemittedCodeYieldsAnEmptyResult(final int reasonCode) {
            assertThat(RejectReason.byReasonCode(reasonCode)).isEmpty();
        }
    }
}
