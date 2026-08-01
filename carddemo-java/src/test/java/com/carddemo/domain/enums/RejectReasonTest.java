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
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link RejectReason}, the five failure reasons the posting run can stamp onto a rejected
 * daily transaction.
 *
 * <p><strong>Where the five reasons come from.</strong> The posting program declares a two-field
 * validation trailer at {@code app/cbl/CBTRN02C.cbl} lines 181 and 182 &mdash; a four-digit numeric
 * reason followed by a seventy-six character description &mdash; and stamps it at five sites: reason 100
 * at line 385, reason 101 at line 397, reason 102 at line 410, reason 103 at line 417 and reason 109 at
 * line 556. There is no sixth site, so the vocabulary is closed at five.
 *
 * <p><strong>Why the numbering has a hole.</strong> The reasons run 100, 101, 102, 103 and then jump to
 * 109. Codes 104 through 108 are never assigned. That gap is in the legacy and is preserved rather than
 * closed, because the reason code is written into a rejected record that leaves the system, and
 * renumbering 109 down to 104 would change a byte in an external file for no behavioural gain.
 *
 * <p><strong>Why the trailer's two widths are asserted here.</strong> The reject record is the 350-byte
 * daily-transaction image followed by the 80-byte trailer, for 430 bytes in total &mdash; declared as
 * {@code REJECT-TRAN-DATA PIC X(350)} plus {@code VALIDATION-TRAILER PIC X(80)} at lines 178 and 179.
 * The trailer itself is 4 plus 76, which is where the two constraints this class enforces originate:
 * every reason code must render in four digits, and every description must fit inside seventy-six
 * characters. A code needing five digits or a description needing seventy-seven characters would
 * silently truncate on the way to disk.
 *
 * <p><strong>Why zero is not a reason.</strong> The posting program initialises the trailer to reason
 * zero with a blank description at lines 208 and 209, and posts a transaction only while the reason is
 * still zero, tested at lines 211 and 372. Zero is therefore the sentinel meaning "nothing went wrong",
 * not a reason. No constant may carry it, and this class asserts that.
 *
 * <p><strong>Why two reasons share a description.</strong> Reason 101 and reason 109 both describe an
 * absent account record, because the account can be missing at two different moments: when the posting
 * run first reads it, and again when the run tries to write the updated balance back. The two moments
 * need distinguishing in the reject file, so the codes differ while the description does not. The lookup
 * is keyed on the numeric code, which is why the shared description costs nothing. This class asserts
 * both that the descriptions genuinely collide and that the two reasons remain separately resolvable.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here claims which validation stage runs first,
 * because ordering belongs to the validation cascade rather than to this vocabulary. Nothing claims a
 * severity ranking among the five, because the legacy assigns none.
 */
@DisplayName("RejectReason — the five reasons the posting run stamps on a rejected transaction")
class RejectReasonTest {

    /** Width of {@code WS-VALIDATION-FAIL-REASON}, declared {@code PIC 9(04)}. */
    private static final int REASON_CODE_DIGITS = 4;

    /** Width of {@code WS-VALIDATION-FAIL-REASON-DESC}, declared {@code PIC X(76)}. */
    private static final int DESCRIPTION_WIDTH = 76;

    /** Width of {@code VALIDATION-TRAILER}, declared {@code PIC X(80)}. */
    private static final int TRAILER_WIDTH = 80;

    /** Width of {@code REJECT-TRAN-DATA}, the copied daily-transaction image. */
    private static final int REJECTED_IMAGE_WIDTH = 350;

    /** Width of the whole {@code REJECT-RECORD}. */
    private static final int REJECT_RECORD_WIDTH = 430;

    /** The reason the posting program moves in to mean "nothing went wrong". */
    private static final int CLEAN_RECORD_SENTINEL = 0;

    /** The five reason codes in the order the posting program's stamp sites appear. */
    private static final List<Integer> LEGACY_REASON_CODES = List.of(100, 101, 102, 103, 109);

    /** The five descriptions, verbatim, in the same order. */
    private static final List<String> LEGACY_DESCRIPTIONS = List.of(
            "INVALID CARD NUMBER FOUND",
            "ACCOUNT RECORD NOT FOUND",
            "OVERLIMIT TRANSACTION",
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            "ACCOUNT RECORD NOT FOUND");

    // =================================================================================================
    // VOCABULARY
    // =================================================================================================

    /**
     * Verifies the five reasons, their codes and their descriptions.
     */
    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly five reasons exist, one per stamp site in the posting program")
        void exactlyFiveReasonsExist() {
            assertThat(RejectReason.values()).hasSize(LEGACY_REASON_CODES.size());
        }

        @Test
        @DisplayName("each reason carries its legacy code")
        void eachReasonCarriesItsLegacyCode() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getReasonCode()).isEqualTo(100);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode()).isEqualTo(101);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getReasonCode()).isEqualTo(102);
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getReasonCode()).isEqualTo(103);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()).isEqualTo(109);
        }

        @Test
        @DisplayName("each reason carries its legacy description verbatim, upper case included")
        void eachReasonCarriesItsLegacyDescription() {
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
        @DisplayName("the declared codes match the legacy list element for element and in stamp order")
        void theDeclaredCodesMatchTheLegacyList() {
            final List<Integer> declared = List.of(RejectReason.values()).stream()
                    .map(RejectReason::getReasonCode)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(LEGACY_REASON_CODES);
        }

        @Test
        @DisplayName("the declared descriptions match the legacy list element for element")
        void theDeclaredDescriptionsMatchTheLegacyList() {
            final List<String> declared = List.of(RejectReason.values()).stream()
                    .map(RejectReason::getDescription)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(LEGACY_DESCRIPTIONS);
        }

        @Test
        @DisplayName("every description is upper case, because the trailer is written as a fixed-width "
                + "record rather than displayed")
        void everyDescriptionIsUpperCase() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription())
                        .as("description of %s", reason.name())
                        .isEqualTo(reason.getDescription().toUpperCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("no description is blank, because a blank description is the clean-record sentinel")
        void noDescriptionIsBlank() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription())
                        .as("description of %s", reason.name())
                        .isNotBlank()
                        .isEqualTo(reason.getDescription().strip());
            }
        }
    }

    // =================================================================================================
    // NUMBERING
    // =================================================================================================

    /**
     * Verifies the numbering, including the hole the legacy leaves and the sentinel it reserves.
     */
    @Nested
    @DisplayName("numbering")
    class Numbering {

        @Test
        @DisplayName("the five codes are distinct, so a stamped record identifies its failure exactly")
        void theFiveCodesAreDistinct() {
            final List<Integer> declared = List.of(RejectReason.values()).stream()
                    .map(RejectReason::getReasonCode)
                    .toList();

            assertThat(declared).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("no reason carries the clean-record sentinel of zero")
        void noReasonCarriesTheSentinel() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getReasonCode())
                        .as("code of %s", reason.name())
                        .isNotEqualTo(CLEAN_RECORD_SENTINEL)
                        .isPositive();
            }
            assertThat(RejectReason.byReasonCode(CLEAN_RECORD_SENTINEL)).isEmpty();
        }

        @Test
        @DisplayName("the numbering keeps its hole: 104 through 108 are unassigned, exactly as in the "
                + "legacy")
        void theNumberingKeepsItsHole() {
            for (int unassigned = 104; unassigned <= 108; unassigned++) {
                assertThat(RejectReason.byReasonCode(unassigned))
                        .as("code %d must remain unassigned", unassigned)
                        .isEmpty();
            }
            assertThat(RejectReason.byReasonCode(103)).isPresent();
            assertThat(RejectReason.byReasonCode(109)).isPresent();
        }

        @Test
        @DisplayName("the codes ascend across the vocabulary, so the declaration order is also the "
                + "numeric order")
        void theCodesAscend() {
            final List<Integer> declared = List.of(RejectReason.values()).stream()
                    .map(RejectReason::getReasonCode)
                    .toList();

            assertThat(declared).isSorted();
        }

        @Test
        @DisplayName("the two rewrite-versus-read reasons are numerically adjacent to nothing, which is "
                + "why the hole cannot be closed silently")
        void theHoleSeparatesTheTwoAbsentAccountReasons() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()
                    - RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode())
                    .isEqualTo(8);
        }
    }

    // =================================================================================================
    // TRAILER GEOMETRY
    // =================================================================================================

    /**
     * Verifies that every reason fits the eighty-byte trailer it is written into.
     */
    @Nested
    @DisplayName("the eighty-byte trailer")
    class TrailerGeometry {

        @Test
        @DisplayName("the trailer's two fields sum to its declared eighty bytes")
        void theTrailerFieldsSumToEighty() {
            assertThat(REASON_CODE_DIGITS + DESCRIPTION_WIDTH).isEqualTo(TRAILER_WIDTH);
        }

        @Test
        @DisplayName("the rejected image and the trailer sum to the four-hundred-and-thirty-byte reject "
                + "record")
        void theImageAndTrailerSumToTheRejectRecord() {
            assertThat(REJECTED_IMAGE_WIDTH + TRAILER_WIDTH).isEqualTo(REJECT_RECORD_WIDTH);
        }

        @Test
        @DisplayName("every reason code renders inside four digits, so none truncates in the numeric "
                + "slot")
        void everyCodeRendersInFourDigits() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(Integer.toString(reason.getReasonCode()))
                        .as("digit width of %s", reason.name())
                        .hasSizeLessThanOrEqualTo(REASON_CODE_DIGITS);
            }
        }

        @Test
        @DisplayName("every reason code zero-fills to exactly four digits, matching the numeric picture")
        void everyCodeZeroFillsToFourDigits() {
            assertThat(String.format(Locale.ROOT, "%04d",
                    RejectReason.INVALID_CARD_NUMBER.getReasonCode())).isEqualTo("0100");
            assertThat(String.format(Locale.ROOT, "%04d",
                    RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())).isEqualTo("0109");

            for (final RejectReason reason : RejectReason.values()) {
                assertThat(String.format(Locale.ROOT, "%04d", reason.getReasonCode()))
                        .as("zero-filled code of %s", reason.name())
                        .hasSize(REASON_CODE_DIGITS)
                        .containsOnlyDigits();
            }
        }

        @Test
        @DisplayName("every description fits inside seventy-six characters, so none truncates in the "
                + "description slot")
        void everyDescriptionFitsTheSlot() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription())
                        .as("character width of %s", reason.name())
                        .hasSizeLessThanOrEqualTo(DESCRIPTION_WIDTH);
            }
        }

        @Test
        @DisplayName("every description fits inside seventy-six encoded bytes, so no character costs "
                + "two")
        void everyDescriptionFitsTheSlotInBytes() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of %s", reason.name())
                        .hasSizeLessThanOrEqualTo(DESCRIPTION_WIDTH);
            }
        }

        @Test
        @DisplayName("the longest description is the account-expiration text at forty-two characters, "
                + "leaving thirty-four characters of headroom")
        void theLongestDescriptionIsTheExpirationText() {
            final RejectReason longest = List.of(RejectReason.values()).stream()
                    .reduce((left, right) -> right.getDescription().length()
                            > left.getDescription().length() ? right : left)
                    .orElseThrow();

            assertThat(longest).isEqualTo(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
            assertThat(longest.getDescription()).hasSize(42);
            assertThat(DESCRIPTION_WIDTH - longest.getDescription().length()).isEqualTo(34);
        }

        @Test
        @DisplayName("a reason blank-fills into the trailer at exactly eighty bytes")
        void aReasonBlankFillsTheTrailerToEighty() {
            for (final RejectReason reason : RejectReason.values()) {
                final String trailer = String.format(Locale.ROOT, "%04d", reason.getReasonCode())
                        + padRight(reason.getDescription(), DESCRIPTION_WIDTH);

                assertThat(trailer)
                        .as("assembled trailer for %s", reason.name())
                        .hasSize(TRAILER_WIDTH)
                        .startsWith(String.format(Locale.ROOT, "%04d", reason.getReasonCode()));
                assertThat(trailer.substring(REASON_CODE_DIGITS).strip())
                        .isEqualTo(reason.getDescription());
            }
        }
    }

    // =================================================================================================
    // SHARED DESCRIPTION
    // =================================================================================================

    /**
     * Verifies the deliberate description collision between the read failure and the rewrite failure.
     */
    @Nested
    @DisplayName("the shared absent-account description")
    class SharedDescription {

        @Test
        @DisplayName("the read failure and the rewrite failure genuinely share one description")
        void theTwoAbsentAccountReasonsShareOneDescription() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription());
        }

        @Test
        @DisplayName("the collision is the only one, so no other pair of reasons is indistinguishable "
                + "by description")
        void theCollisionIsTheOnlyOne() {
            final List<String> descriptions = List.of(RejectReason.values()).stream()
                    .map(RejectReason::getDescription)
                    .toList();

            assertThat(descriptions).hasSize(5);
            assertThat(descriptions.stream().distinct().toList()).hasSize(4);
        }

        @Test
        @DisplayName("despite the shared description the two reasons remain separately resolvable, "
                + "because the index is keyed on the numeric code")
        void theTwoReasonsRemainSeparatelyResolvable() {
            assertThat(RejectReason.byReasonCode(101))
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            assertThat(RejectReason.byReasonCode(109))
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ)
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
        }
    }

    // =================================================================================================
    // LOOKUP
    // =================================================================================================

    /**
     * Verifies the lookup from a stamped numeric code back to a reason.
     */
    @Nested
    @DisplayName("lookup from a stamped code")
    class Lookup {

        @Test
        @DisplayName("all five legacy codes resolve to their reason")
        void allFiveCodesResolve() {
            assertThat(RejectReason.byReasonCode(100))
                    .contains(RejectReason.INVALID_CARD_NUMBER);
            assertThat(RejectReason.byReasonCode(101))
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            assertThat(RejectReason.byReasonCode(102))
                    .contains(RejectReason.OVERLIMIT_TRANSACTION);
            assertThat(RejectReason.byReasonCode(103))
                    .contains(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
            assertThat(RejectReason.byReasonCode(109))
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
        }

        @Test
        @DisplayName("every reason round-trips through its own code")
        void everyReasonRoundTrips() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(RejectReason.byReasonCode(reason.getReasonCode()))
                        .as("round trip of %s", reason.name())
                        .contains(reason);
            }
        }

        @Test
        @DisplayName("a code outside the vocabulary resolves to nothing rather than throwing")
        void anUnknownCodeResolvesToNothing() {
            assertThat(RejectReason.byReasonCode(99)).isEmpty();
            assertThat(RejectReason.byReasonCode(110)).isEmpty();
            assertThat(RejectReason.byReasonCode(1)).isEmpty();
            assertThat(RejectReason.byReasonCode(9999)).isEmpty();
        }

        @Test
        @DisplayName("a negative code resolves to nothing, because the numeric picture is unsigned")
        void aNegativeCodeResolvesToNothing() {
            assertThat(RejectReason.byReasonCode(-100)).isEmpty();
            assertThat(RejectReason.byReasonCode(Integer.MIN_VALUE)).isEmpty();
        }

        @Test
        @DisplayName("a code beyond four digits resolves to nothing, because it could never have been "
                + "written into the trailer")
        void aCodeBeyondFourDigitsResolvesToNothing() {
            assertThat(RejectReason.byReasonCode(10_000)).isEmpty();
            assertThat(RejectReason.byReasonCode(Integer.MAX_VALUE)).isEmpty();
        }

        @Test
        @DisplayName("the lookup never returns null, so a caller may chain on the result")
        void theLookupNeverReturnsNull() {
            assertThat(RejectReason.byReasonCode(100)).isNotNull();
            assertThat(RejectReason.byReasonCode(0)).isNotNull();
            assertThat(RejectReason.byReasonCode(-1)).isNotNull();
        }
    }

    /**
     * Blank-fills a value on the right to a fixed width, the way a fixed-width character field is
     * written.
     *
     * @param value the value to fill
     * @param width the target width, which must be at least the value's own length
     * @return the value followed by enough blanks to reach the target width
     */
    private static String padRight(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }
}
