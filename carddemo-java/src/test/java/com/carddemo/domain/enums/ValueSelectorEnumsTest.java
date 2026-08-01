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
 * Verifies the three multi-character value enumerations and the reject-reason table.
 *
 * <p>Each replaces a fixed-width field or a numeric code that the legacy declares as literals:
 *
 * <ul>
 *   <li><strong>Transaction source</strong> from {@code TRAN-SOURCE PIC X(10)} in
 *       {@code app/cpy/CVTRA05Y.cpy}. The daily-transaction fixture carries two of the three values -
 *       two hundred and fifty point-of-sale purchases and fifty operator-originated returns - while
 *       the third is written only by the interest program when it synthesises its own transaction.</li>
 *   <li><strong>Date-format selector</strong> from the second parameter of the date-validation
 *       subprogram's linkage area. Both selectors occupy the same ten-character slot, so the compact
 *       form carries two trailing spaces.</li>
 *   <li><strong>Report period</strong> from the three branches of the reporting program that submit a
 *       job.</li>
 *   <li><strong>Reject reason</strong> from the five codes {@code CBTRN02C} moves into its
 *       {@code PIC 9(04)} reason field, each paired with the {@code PIC X(76)} description that
 *       travels beside it in the eighty-byte trailer of the four-hundred-and-thirty-byte reject
 *       record.</li>
 * </ul>
 *
 * <p><strong>Padding is part of the value, and nothing normalises it.</strong> A fixed-width field
 * reserves bytes, not characters, so a trimmed value is a different value and must not resolve. The
 * source value {@code System} additionally carries mixed case, which no upper-folding may touch.
 */
@DisplayName("Value-selector enumerations: transaction source, date format, report period, reject reason")
final class ValueSelectorEnumsTest {

    /** {@code TRAN-SOURCE PIC X(10)}. */
    private static final int ORACLE_SOURCE_WIDTH = 10;

    /** The linkage parameter slot the date-format selector fills. */
    private static final int ORACLE_SELECTOR_WIDTH = 10;

    /** The value the two hundred and fifty purchase records carry, padded to the field width. */
    private static final String ORACLE_SOURCE_POS_TERM = "POS TERM  ";

    /** The value the fifty return records carry, padded to the field width. */
    private static final String ORACLE_SOURCE_OPERATOR = "OPERATOR  ";

    /**
     * The value the interest program moves into the source field of the transaction it synthesises.
     *
     * <p>Mixed case in the source, and reproduced mixed case here. Upper-folding it would change a
     * persisted field value.
     */
    private static final String ORACLE_SOURCE_SYSTEM = "System    ";

    /** The hyphenated selector, which fills the slot exactly with no padding. */
    private static final String ORACLE_SELECTOR_HYPHENATED = "YYYY-MM-DD";

    /** The compact selector, padded to the slot width with two trailing spaces. */
    private static final String ORACLE_SELECTOR_COMPACT = "YYYYMMDD  ";

    /** The monthly branch's period value. */
    private static final String ORACLE_PERIOD_MONTHLY = "Monthly";

    /** The yearly branch's period value. */
    private static final String ORACLE_PERIOD_YEARLY = "Yearly";

    /** The custom-date branch's period value. */
    private static final String ORACLE_PERIOD_CUSTOM = "Custom";

    /** The five reason codes, in the order the validation cascade can produce them. */
    private static final List<Integer> ORACLE_REASON_CODES = List.of(100, 101, 102, 103, 109);

    /** The zero value the reason field holds while a transaction is still passing validation. */
    private static final int ORACLE_NO_REASON = 0;

    @Nested
    @DisplayName("TransactionSourceType: the ten-byte source field")
    final class TransactionSourceTypeContract {

        @Test
        @DisplayName("exactly three values exist, one per source the estate writes")
        void exactlyThreeValuesExist() {
            assertThat(TransactionSourceType.values()).hasSize(3);
            assertThat(TransactionSourceType.POS_TERM.getValue()).isEqualTo(ORACLE_SOURCE_POS_TERM);
            assertThat(TransactionSourceType.OPERATOR.getValue()).isEqualTo(ORACLE_SOURCE_OPERATOR);
            assertThat(TransactionSourceType.SYSTEM.getValue()).isEqualTo(ORACLE_SOURCE_SYSTEM);
        }

        @Test
        @DisplayName("every value fills the field exactly, padding included")
        void everyValueFillsTheFieldExactly() {
            for (final TransactionSourceType source : TransactionSourceType.values()) {
                assertThat(source.getValue()).as("%s", source).hasSize(ORACLE_SOURCE_WIDTH);
            }
            assertThat(TransactionSourceType.VALUE_LENGTH).isEqualTo(ORACLE_SOURCE_WIDTH);
        }

        @Test
        @DisplayName("the synthesised source keeps its mixed case, unfolded")
        void theSynthesisedSourceKeepsItsMixedCase() {
            assertThat(TransactionSourceType.SYSTEM.getValue())
                    .isEqualTo(ORACLE_SOURCE_SYSTEM)
                    .isNotEqualTo(ORACLE_SOURCE_SYSTEM.toUpperCase(java.util.Locale.ROOT));
        }

        @Test
        @DisplayName("only the synthesised source reports system generated")
        void onlyTheSynthesisedSourceReportsSystemGenerated() {
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated()).isTrue();
            assertThat(TransactionSourceType.POS_TERM.isSystemGenerated()).isFalse();
            assertThat(TransactionSourceType.OPERATOR.isSystemGenerated())
                    .as("an operator-originated return is entered by a person, not by the system")
                    .isFalse();
        }

        @Test
        @DisplayName("every value round-trips, padding intact")
        void everyValueRoundTrips() {
            for (final TransactionSourceType source : TransactionSourceType.values()) {
                assertThat(TransactionSourceType.fromValue(source.getValue()))
                        .as("%s", source).contains(source);
            }
        }

        @Test
        @DisplayName("a trimmed value does not resolve, because the field reserves bytes")
        void aTrimmedValueDoesNotResolve() {
            assertThat(TransactionSourceType.fromValue("POS TERM")).isEmpty();
            assertThat(TransactionSourceType.fromValue("OPERATOR")).isEmpty();
            assertThat(TransactionSourceType.fromValue("System")).isEmpty();
        }

        @Test
        @DisplayName("a re-cased value does not resolve")
        void aReCasedValueDoesNotResolve() {
            assertThat(TransactionSourceType.fromValue("SYSTEM    ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("pos term  ")).isEmpty();
        }

        @Test
        @DisplayName("an absent or unknown value resolves to nothing")
        void anAbsentOrUnknownValueResolvesToNothing() {
            assertThat(TransactionSourceType.fromValue(null)).isEmpty();
            assertThat(TransactionSourceType.fromValue("")).isEmpty();
            assertThat(TransactionSourceType.fromValue("ATM       ")).isEmpty();
        }

        @Test
        @DisplayName("the two fixture sources are the two non-system sources")
        void theTwoFixtureSourcesAreTheNonSystemOnes() {
            final Set<TransactionSourceType> nonSystem = new LinkedHashSet<>();
            for (final TransactionSourceType source : TransactionSourceType.values()) {
                if (!source.isSystemGenerated()) {
                    nonSystem.add(source);
                }
            }

            assertThat(nonSystem).containsExactly(TransactionSourceType.POS_TERM,
                    TransactionSourceType.OPERATOR);
        }
    }

    @Nested
    @DisplayName("DateFormat: the ten-character linkage selector")
    final class DateFormatContract {

        @Test
        @DisplayName("exactly two selectors exist")
        void exactlyTwoSelectorsExist() {
            assertThat(DateFormat.values()).hasSize(2);
            assertThat(DateFormat.YYYY_MM_DD.getValue()).isEqualTo(ORACLE_SELECTOR_HYPHENATED);
            assertThat(DateFormat.YYYYMMDD.getValue()).isEqualTo(ORACLE_SELECTOR_COMPACT);
        }

        @Test
        @DisplayName("both selectors fill the linkage slot exactly")
        void bothSelectorsFillTheSlotExactly() {
            for (final DateFormat format : DateFormat.values()) {
                assertThat(format.getValue()).as("%s", format).hasSize(ORACLE_SELECTOR_WIDTH);
            }
        }

        @Test
        @DisplayName("the compact selector carries the two trailing spaces the slot requires")
        void theCompactSelectorCarriesItsTrailingSpaces() {
            assertThat(DateFormat.YYYYMMDD.getValue()).endsWith("  ").startsWith("YYYYMMDD");
        }

        @Test
        @DisplayName("the hyphenated selector needs no padding and receives none")
        void theHyphenatedSelectorNeedsNoPadding() {
            assertThat(DateFormat.YYYY_MM_DD.getValue()).doesNotEndWith(" ");
        }

        @Test
        @DisplayName("both selectors round-trip, padding intact")
        void bothSelectorsRoundTrip() {
            for (final DateFormat format : DateFormat.values()) {
                assertThat(DateFormat.fromValue(format.getValue())).as("%s", format).contains(format);
            }
        }

        @Test
        @DisplayName("the unpadded compact form does not resolve, because the slot is fixed width")
        void theUnpaddedCompactFormDoesNotResolve() {
            assertThat(DateFormat.fromValue("YYYYMMDD"))
                    .as("a caller must pad to the slot width before resolving")
                    .isEmpty();
        }

        @Test
        @DisplayName("a reversed or unknown picture does not resolve")
        void aReversedOrUnknownPictureDoesNotResolve() {
            assertThat(DateFormat.fromValue("DD-MM-YYYY")).isEmpty();
            assertThat(DateFormat.fromValue("MM/DD/YYYY")).isEmpty();
            assertThat(DateFormat.fromValue("          ")).isEmpty();
        }

        @Test
        @DisplayName("an absent or empty selector resolves to nothing")
        void anAbsentOrEmptySelectorResolvesToNothing() {
            assertThat(DateFormat.fromValue(null)).isEmpty();
            assertThat(DateFormat.fromValue("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("ReportPeriod: the three branches that submit a job")
    final class ReportPeriodContract {

        @Test
        @DisplayName("exactly three periods exist, one per submitting branch")
        void exactlyThreePeriodsExist() {
            assertThat(ReportPeriod.values()).hasSize(3);
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo(ORACLE_PERIOD_MONTHLY);
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo(ORACLE_PERIOD_YEARLY);
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo(ORACLE_PERIOD_CUSTOM);
        }

        @Test
        @DisplayName("every period round-trips through its value")
        void everyPeriodRoundTrips() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(ReportPeriod.fromValue(period.getValue())).as("%s", period)
                        .contains(period);
            }
        }

        @Test
        @DisplayName("the values keep their leading capital, unfolded")
        void theValuesKeepTheirLeadingCapital() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(period.getValue()).as("%s", period).matches("[A-Z][a-z]+");
            }
        }

        @Test
        @DisplayName("a re-cased or unknown value resolves to nothing")
        void aReCasedOrUnknownValueResolvesToNothing() {
            assertThat(ReportPeriod.fromValue("MONTHLY")).isEmpty();
            assertThat(ReportPeriod.fromValue("monthly")).isEmpty();
            assertThat(ReportPeriod.fromValue("Weekly")).isEmpty();
            assertThat(ReportPeriod.fromValue("Monthly ")).isEmpty();
        }

        @Test
        @DisplayName("an absent or empty value resolves to nothing")
        void anAbsentOrEmptyValueResolvesToNothing() {
            assertThat(ReportPeriod.fromValue(null)).isEmpty();
            assertThat(ReportPeriod.fromValue("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("RejectReason: the five codes and their descriptions")
    final class RejectReasonContract {

        @Test
        @DisplayName("exactly five reasons exist, one per reject branch of the posting program")
        void exactlyFiveReasonsExist() {
            assertThat(RejectReason.values()).hasSize(ORACLE_REASON_CODES.size());
        }

        @Test
        @DisplayName("the five codes are exactly those the posting program moves")
        void theFiveCodesAreExactlyThoseMoved() {
            final List<Integer> codes = List.of(RejectReason.values()).stream()
                    .map(RejectReason::getReasonCode)
                    .toList();

            assertThat(codes).containsExactlyElementsOf(ORACLE_REASON_CODES);
        }

        @Test
        @DisplayName("each code carries the description that travels beside it in the trailer")
        void eachCodeCarriesItsDescription() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getReasonCode()).isEqualTo(100);
            assertThat(RejectReason.INVALID_CARD_NUMBER.getDescription())
                    .isEqualTo("INVALID CARD NUMBER FOUND");
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode()).isEqualTo(101);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getReasonCode()).isEqualTo(102);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getDescription())
                    .isEqualTo("OVERLIMIT TRANSACTION");
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getReasonCode())
                    .isEqualTo(103);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()).isEqualTo(109);
        }

        @Test
        @DisplayName("every description fits the description field of the eighty-byte trailer")
        void everyDescriptionFitsTheTrailerField() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription()).as("%s", reason)
                        .isNotBlank()
                        .hasSizeLessThanOrEqualTo(76);
            }
        }

        @Test
        @DisplayName("two distinct reasons share one description, because the source reuses the text")
        void twoDistinctReasonsShareOneDescription() {
            // The read failure and the rewrite failure are separate branches with separate codes but
            // the same displayed text; collapsing them would lose which branch rejected the record.
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription());
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode());
        }

        @Test
        @DisplayName("every reason resolves from its own code")
        void everyReasonResolvesFromItsCode() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(RejectReason.byReasonCode(reason.getReasonCode()))
                        .as("%s", reason).contains(reason);
            }
        }

        @Test
        @DisplayName("the numbering has a genuine gap, so codes 104 to 108 resolve to nothing")
        void theNumberingHasAGenuineGap() {
            for (int code = 104; code <= 108; code++) {
                assertThat(RejectReason.byReasonCode(code)).as("code %d", code).isEmpty();
            }
        }

        @Test
        @DisplayName("zero resolves to nothing, because it means the record is still passing")
        void zeroResolvesToNothing() {
            // Posting proceeds only while the reason field is zero, so zero is the absence of a
            // reason rather than a reason of its own.
            assertThat(RejectReason.byReasonCode(ORACLE_NO_REASON)).isEmpty();
        }

        @Test
        @DisplayName("a code outside the field's range resolves to nothing rather than raising")
        void aCodeOutsideTheRangeResolvesToNothing() {
            assertThat(RejectReason.byReasonCode(-1)).isEmpty();
            assertThat(RejectReason.byReasonCode(9999)).isEmpty();
            assertThat(RejectReason.byReasonCode(Integer.MAX_VALUE)).isEmpty();
        }

        @Test
        @DisplayName("every code fits the four-digit reason field")
        void everyCodeFitsTheFourDigitField() {
            for (final RejectReason reason : RejectReason.values()) {
                assertThat(reason.getReasonCode()).as("%s", reason).isBetween(0, 9999);
            }
        }
    }
}
