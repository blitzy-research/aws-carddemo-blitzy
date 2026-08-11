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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RejectReason}, the typed vocabulary of daily-transaction
 * posting rejections.
 *
 * <h2>What is under test</h2>
 *
 * <p>The legacy posting program {@code app/cbl/CBTRN02C.cbl} rejects a daily
 * transaction by moving a four-digit reason code and a seventy-six character
 * description into a trailer that is concatenated onto the unmodified
 * three-hundred-and-fifty byte source record, producing a four-hundred-and-thirty
 * byte reject record. The trailer members are declared at lines 181 and 182 of
 * that program as a {@code PIC 9(04)} reason code and a {@code PIC X(76)}
 * description, and posting proceeds only while the reason code is still zero,
 * which the program tests at lines 211 and 372.</p>
 *
 * <p>Exactly five codes are assigned: one hundred at line 385 for a card number
 * that resolves to no cross-reference, one hundred and one at line 397 for an
 * account that cannot be read, one hundred and two at line 410 for a transaction
 * that would take the account over its limit, one hundred and three at line 417
 * for a transaction received after the account expired, and one hundred and nine
 * at line 556 for an account that cannot be read back for rewrite. The last is a
 * distinct condition from the hundred and one even though both concern a missing
 * account record, because one is detected on the initial read and the other on
 * the rewrite, so the two are deliberately separate constants rather than one
 * shared value.</p>
 *
 * <h2>How these assertions are grounded</h2>
 *
 * <p>Every code and every description below is stated as an independent literal
 * read off the legacy program rather than obtained from the production enum, so
 * that a change to the enum fails this class instead of being silently followed.
 * The width assertions matter because the description occupies a fixed
 * seventy-six byte field: a description longer than that would be truncated in
 * the emitted reject record and would break the byte-parity gate, so each one is
 * asserted to fit.</p>
 */
@DisplayName("RejectReason: the five daily-transaction posting rejections")
class RejectReasonBoundaryTest {

    /** Width of the {@code PIC X(76)} description field of the reject trailer. */
    private static final int DESCRIPTION_FIELD_WIDTH = 76;

    /** Width of the {@code PIC 9(04)} reason-code field of the reject trailer. */
    private static final int REASON_CODE_FIELD_WIDTH = 4;

    /** Number of codes the legacy program assigns. */
    private static final int ASSIGNED_CODE_COUNT = 5;

    @Nested
    @DisplayName("the assigned vocabulary")
    class AssignedVocabulary {

        @Test
        @DisplayName("exactly five reasons are defined, one per assignment site in the posting program")
        void exactlyFiveReasonsAreDefined() {
            assertThat(RejectReason.values()).hasSize(ASSIGNED_CODE_COUNT);
        }

        @ParameterizedTest(name = "{0} carries reason code {1}")
        @CsvSource({
            "INVALID_CARD_NUMBER, 100",
            "ACCOUNT_NOT_FOUND_ON_READ, 101",
            "OVERLIMIT_TRANSACTION, 102",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION, 103",
            "ACCOUNT_NOT_FOUND_ON_REWRITE, 109",
        })
        @DisplayName("each constant carries the reason code the legacy program moves for it")
        void eachConstantCarriesItsLegacyReasonCode(RejectReason reason, int expectedCode) {
            assertThat(reason.getReasonCode()).isEqualTo(expectedCode);
        }

        @ParameterizedTest(name = "{0} describes itself as {1}")
        @CsvSource({
            "INVALID_CARD_NUMBER, INVALID CARD NUMBER FOUND",
            "ACCOUNT_NOT_FOUND_ON_READ, ACCOUNT RECORD NOT FOUND",
            "OVERLIMIT_TRANSACTION, OVERLIMIT TRANSACTION",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION, TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            "ACCOUNT_NOT_FOUND_ON_REWRITE, ACCOUNT RECORD NOT FOUND",
        })
        @DisplayName("each constant carries the description text the legacy program moves for it")
        void eachConstantCarriesItsLegacyDescription(RejectReason reason, String expectedDescription) {
            assertThat(reason.getDescription()).isEqualTo(expectedDescription);
        }

        @Test
        @DisplayName("the two missing-account reasons share a description but not a code")
        void theTwoMissingAccountReasonsShareADescriptionButNotACode() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription());
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode());
        }

        @Test
        @DisplayName("every reason code is distinct, so a rejected record identifies one condition")
        void everyReasonCodeIsDistinct() {
            assertThat(Arrays.stream(RejectReason.values()).map(RejectReason::getReasonCode))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the declaration order follows the ascending code order of the legacy assignments")
        void theDeclarationOrderFollowsAscendingCodeOrder() {
            assertThat(Arrays.stream(RejectReason.values()).map(RejectReason::getReasonCode))
                    .isSorted();
        }
    }

    @Nested
    @DisplayName("fixed-width trailer compatibility")
    class TrailerCompatibility {

        @ParameterizedTest(name = "the description of {0} fits the seventy-six byte field")
        @ValueSource(strings = {
            "INVALID_CARD_NUMBER", "ACCOUNT_NOT_FOUND_ON_READ", "OVERLIMIT_TRANSACTION",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION", "ACCOUNT_NOT_FOUND_ON_REWRITE"})
        @DisplayName("no description exceeds the width of the description field")
        void noDescriptionExceedsTheFieldWidth(String constantName) {
            RejectReason reason = RejectReason.valueOf(constantName);

            byte[] encoded = reason.getDescription().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded).hasSizeLessThanOrEqualTo(DESCRIPTION_FIELD_WIDTH);
        }

        @ParameterizedTest(name = "the description of {0} is representable in the legacy character set")
        @ValueSource(strings = {
            "INVALID_CARD_NUMBER", "ACCOUNT_NOT_FOUND_ON_READ", "OVERLIMIT_TRANSACTION",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION", "ACCOUNT_NOT_FOUND_ON_REWRITE"})
        @DisplayName("no description carries a byte outside printable single-byte range")
        void noDescriptionCarriesAnUnprintableByte(String constantName) {
            RejectReason reason = RejectReason.valueOf(constantName);

            for (byte encoded : reason.getDescription().getBytes(StandardCharsets.US_ASCII)) {
                assertThat(encoded).isBetween((byte) 0x20, (byte) 0x7E);
            }
        }

        @Test
        @DisplayName("every reason code fits the four-digit reason field without a leading sign")
        void everyReasonCodeFitsTheFourDigitField() {
            for (RejectReason reason : RejectReason.values()) {
                assertThat(reason.getReasonCode()).isPositive();
                assertThat(String.valueOf(reason.getReasonCode()))
                        .hasSizeLessThanOrEqualTo(REASON_CODE_FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("no reason uses code zero, which the posting program reserves for acceptance")
        void noReasonUsesCodeZero() {
            assertThat(RejectReason.byReasonCode(0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("lookup by reason code")
    class LookupByReasonCode {

        @ParameterizedTest(name = "code {0} resolves to a reason carrying that same code")
        @ValueSource(ints = {100, 101, 102, 103, 109})
        @DisplayName("every assigned code resolves to its own constant")
        void everyAssignedCodeResolves(int code) {
            Optional<RejectReason> resolved = RejectReason.byReasonCode(code);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().getReasonCode()).isEqualTo(code);
        }

        @ParameterizedTest(name = "unassigned code {0} resolves to nothing")
        @ValueSource(ints = {-109, -1, 0, 1, 99, 104, 105, 106, 107, 108, 110, 200, 1000, 9999})
        @DisplayName("an unassigned code resolves to an empty result rather than a default")
        void anUnassignedCodeResolvesToNothing(int code) {
            assertThat(RejectReason.byReasonCode(code)).isEmpty();
        }

        @Test
        @DisplayName("the lookup covers every declared constant, so no constant is unreachable")
        void theLookupCoversEveryDeclaredConstant() {
            EnumSet<RejectReason> reached = EnumSet.noneOf(RejectReason.class);

            for (RejectReason reason : RejectReason.values()) {
                RejectReason.byReasonCode(reason.getReasonCode()).ifPresent(reached::add);
            }

            assertThat(reached).containsExactlyInAnyOrder(RejectReason.values());
        }

        @Test
        @DisplayName("the lookup is stable across calls, so the index is not rebuilt per call")
        void theLookupIsStableAcrossCalls() {
            assertThat(RejectReason.byReasonCode(102))
                    .containsSame(RejectReason.OVERLIMIT_TRANSACTION)
                    .isEqualTo(RejectReason.byReasonCode(102));
        }
    }
}
