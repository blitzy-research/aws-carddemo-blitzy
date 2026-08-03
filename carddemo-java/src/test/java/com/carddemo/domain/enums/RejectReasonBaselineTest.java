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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link RejectReason}, the typed replacement for the five reject codes the daily
 * transaction posting program writes into the trailer of a rejected record.
 *
 * <p><strong>What this test proves.</strong> The reject record produced by the posting program is 430
 * bytes wide: the 350-byte source image of the daily transaction followed by an 80-byte failure
 * trailer whose first four bytes are a zero-filled numeric reason code and whose remaining 76 bytes
 * are a fixed description. Both halves of the trailer are contractual, and both are byte-compared by
 * the end-to-end parity gate, so the code values, the description texts and the association between
 * them all have to be exact. This test pins the closed five-member vocabulary, the five numeric
 * codes 100, 101, 102, 103 and 109, the declaration order in which the posting program's validation
 * cascade reaches them, the description text carried by each, and the fact that the reason code fits
 * the four-byte field while the description fits the seventy-six-byte field.
 *
 * <p><strong>The duplicate description is deliberate.</strong> Two distinct codes - the read failure
 * and the rewrite failure - carry the identical description. That is a property of the legacy
 * program, which reaches the same missing-account condition from two different points in the posting
 * flow and reports it with the same words, and it is asserted here rather than corrected, because
 * changing either text would break the byte comparison of the trailer.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * connection, reads no file and performs no introspection. Every expected value is a literal typed
 * out in this source; no expectation is produced by calling the type under test.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited as provenance only and never asserted
 * against a member.
 */
@DisplayName("RejectReason - the five reject codes of the 430-byte reject record trailer")
class RejectReasonBaselineTest {

    /** Width of the numeric reason code within the failure trailer. */
    private static final int REASON_CODE_FIELD_WIDTH = 4;

    /** Width of the description within the failure trailer. */
    private static final int DESCRIPTION_FIELD_WIDTH = 76;

    /** Width of the daily-transaction source image that precedes the trailer. */
    private static final int SOURCE_IMAGE_WIDTH = 350;

    /** Total width of the failure trailer. */
    private static final int TRAILER_WIDTH = 80;

    /** Total width of one reject record. */
    private static final int REJECT_RECORD_WIDTH = 430;

    /** Description shared by the read failure and the rewrite failure. */
    private static final String MISSING_ACCOUNT_DESCRIPTION = "ACCOUNT RECORD NOT FOUND";

    @Nested
    @DisplayName("Vocabulary and declaration order")
    class Vocabulary {

        @Test
        @DisplayName("exactly five reasons exist, in the order the posting program's validation "
                + "cascade reaches them: card number, account read, overlimit, expiration, rewrite")
        void exactlyFiveReasonsExistInCascadeOrder() {
            assertThat(RejectReason.values()).containsExactly(
                    RejectReason.INVALID_CARD_NUMBER,
                    RejectReason.ACCOUNT_NOT_FOUND_ON_READ,
                    RejectReason.OVERLIMIT_TRANSACTION,
                    RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION,
                    RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
        }

        @Test
        @DisplayName("the enumeration is closed at five members, because the posting program emits no "
                + "sixth code and inventing one would be feature expansion")
        void theEnumerationIsClosedAtFiveMembers() {
            assertThat(RejectReason.values()).hasSize(5);
        }

        @Test
        @DisplayName("the five numeric codes are 100, 101, 102, 103 and 109 in declaration order, "
                + "with 109 deliberately outside the contiguous run")
        void theFiveNumericCodesAreExactlyAsEmitted() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getReasonCode()).isEqualTo(100);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode()).isEqualTo(101);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getReasonCode()).isEqualTo(102);
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getReasonCode()).isEqualTo(103);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()).isEqualTo(109);
        }

        @Test
        @DisplayName("the five codes are pairwise distinct, so the four-byte trailer field identifies "
                + "the failing check unambiguously")
        void theFiveCodesArePairwiseDistinct() {
            assertThat(Arrays.stream(RejectReason.values())
                    .map(RejectReason::getReasonCode)
                    .distinct()
                    .count())
                    .isEqualTo(5L);
        }

        @Test
        @DisplayName("codes ascend in declaration order, so the cascade order and the numeric order "
                + "agree and a reader can infer one from the other")
        void codesAscendInDeclarationOrder() {
            final RejectReason[] reasons = RejectReason.values();

            for (int index = 1; index < reasons.length; index++) {
                assertThat(reasons[index].getReasonCode())
                        .isGreaterThan(reasons[index - 1].getReasonCode());
            }
        }

        @Test
        @DisplayName("zero is not a reject reason, because the posting program treats a zero reason "
                + "code as the signal to post rather than to reject")
        void zeroIsNotARejectReason() {
            assertThat(Arrays.stream(RejectReason.values()).anyMatch(reason -> reason.getReasonCode() == 0))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Description text carried in the 76-byte trailer field")
    class DescriptionText {

        @Test
        @DisplayName("each reason carries its exact description, reproduced verbatim because the "
                + "trailer is byte-compared by the parity gate")
        void eachReasonCarriesItsExactDescription() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getDescription())
                    .isEqualTo("INVALID CARD NUMBER FOUND");
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                    .isEqualTo(MISSING_ACCOUNT_DESCRIPTION);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getDescription())
                    .isEqualTo("OVERLIMIT TRANSACTION");
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getDescription())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(MISSING_ACCOUNT_DESCRIPTION);
        }

        @Test
        @DisplayName("the read failure and the rewrite failure share one description: the same "
                + "missing-account condition is reached from two points in the posting flow and is "
                + "reported with the same words, which is preserved rather than disambiguated")
        void theReadAndRewriteFailuresShareOneDescription() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription());
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode());
        }

        @Test
        @DisplayName("four distinct description texts serve five codes, exactly because of that one "
                + "shared pair")
        void fourDistinctDescriptionsServeFiveCodes() {
            assertThat(Arrays.stream(RejectReason.values())
                    .map(RejectReason::getDescription)
                    .distinct()
                    .count())
                    .isEqualTo(4L);
        }

        @Test
        @DisplayName("every description is upper case with no leading or trailing padding, so writing "
                + "it into the trailer needs a single right pad and no other adjustment")
        void everyDescriptionIsUpperCaseAndUnpadded() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription()).isUpperCase();
                assertThat(reason.getDescription()).isEqualTo(reason.getDescription().trim());
            }
        }

        @Test
        @DisplayName("every description fits the 76-byte trailer field, the longest being the "
                + "42-character expiration text")
        void everyDescriptionFitsTheTrailerField() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription().length())
                        .isLessThanOrEqualTo(DESCRIPTION_FIELD_WIDTH);
            }

            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getDescription())
                    .hasSize(42);
        }
    }

    @Nested
    @DisplayName("Resolution of a stored reason code")
    class ReasonCodeResolution {

        @Test
        @DisplayName("each of the five codes resolves to its own reason")
        void eachOfTheFiveCodesResolves() {
            assertThat(RejectReason.byReasonCode(100)).contains(RejectReason.INVALID_CARD_NUMBER);
            assertThat(RejectReason.byReasonCode(101)).contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            assertThat(RejectReason.byReasonCode(102)).contains(RejectReason.OVERLIMIT_TRANSACTION);
            assertThat(RejectReason.byReasonCode(103))
                    .contains(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
            assertThat(RejectReason.byReasonCode(109))
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
        }

        @ParameterizedTest(name = "reason code {0} does not resolve")
        @DisplayName("a code outside the five yields no reason rather than throwing, so an unexpected "
                + "trailer value is reported by the caller instead of aborting the lookup - the gap "
                + "between 103 and 109 is genuinely empty")
        @ValueSource(ints = {0, 1, 99, 104, 105, 106, 107, 108, 110, 200, 1000, -1, -100})
        void aCodeOutsideTheFiveYieldsNoReason(final int reasonCode) {
            assertThat(RejectReason.byReasonCode(reasonCode)).isEmpty();
        }

        @Test
        @DisplayName("resolution returns the singleton member rather than a copy, so identity "
                + "comparison remains valid for callers that switch on the result")
        void resolutionReturnsTheSingletonMember() {
            final Optional<RejectReason> resolved = RejectReason.byReasonCode(102);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow()).isSameAs(RejectReason.OVERLIMIT_TRANSACTION);
        }

        @Test
        @DisplayName("every member's own code round-trips through resolution, so the index covers the "
                + "whole vocabulary and not a subset of it")
        void everyMembersOwnCodeRoundTrips() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(RejectReason.byReasonCode(reason.getReasonCode())).contains(reason);
            }
        }
    }

    @Nested
    @DisplayName("Geometry of the reject record the reason is written into")
    class RejectRecordGeometry {

        @Test
        @DisplayName("the trailer is the four-byte reason code plus the seventy-six-byte description, "
                + "totalling eighty bytes")
        void theTrailerIsCodePlusDescription() {
            assertThat(REASON_CODE_FIELD_WIDTH + DESCRIPTION_FIELD_WIDTH).isEqualTo(TRAILER_WIDTH);
        }

        @Test
        @DisplayName("the reject record is the 350-byte source image followed by the 80-byte trailer, "
                + "totalling the 430 bytes the parity gate compares")
        void theRejectRecordIsImagePlusTrailer() {
            assertThat(SOURCE_IMAGE_WIDTH + TRAILER_WIDTH).isEqualTo(REJECT_RECORD_WIDTH);
        }

        @Test
        @DisplayName("every reason code is a three-digit value that zero fills into the four-byte "
                + "numeric field without truncation")
        void everyReasonCodeZeroFillsIntoFourBytes() {
            for (final RejectReason reason : RejectReason.values()) {
                final String zeroFilled = String.format(Locale.ROOT, "%04d", reason.getReasonCode());

                assertThat(zeroFilled).hasSize(REASON_CODE_FIELD_WIDTH);
                assertThat(zeroFilled).startsWith("0");
                assertThat(Integer.parseInt(zeroFilled)).isEqualTo(reason.getReasonCode());
            }
        }

        @Test
        @DisplayName("the four-byte numeric field can carry every reason code, since the largest is "
                + "109 and the field holds up to 9999")
        void theNumericFieldCanCarryEveryReasonCode() {
            final int fieldCapacity = 9999;

            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getReasonCode()).isBetween(0, fieldCapacity);
            }
        }
    }
}
