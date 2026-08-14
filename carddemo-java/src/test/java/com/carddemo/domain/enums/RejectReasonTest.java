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
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link RejectReason}, the typed replacement for the validation-failure vocabulary
 * that the legacy daily-transaction posting program stamps into the trailer of every reject record
 * it writes.
 *
 * <p><strong>What the vocabulary feeds.</strong> The reject record is 430 bytes: a 350-byte verbatim
 * copy of the daily-transaction image followed by an 80-byte validation trailer, and the trailer is
 * itself a four-digit numeric reason code declared {@code PIC 9(04)} followed by a fixed-width
 * description declared {@code PIC X(76)}. Four plus seventy-six is eighty, and three hundred fifty
 * plus eighty is four hundred thirty; both sums are derived below rather than quoted, because a code
 * or a description that drifted by one byte would shift the trailer and change the record width.
 *
 * <p><strong>Two production shapes were possible; this is the one found.</strong> Reading the class
 * under test before writing a single assertion settled two questions that change what may be
 * asserted here. First, the reason code is stored as an {@code int} and exposed by
 * {@link RejectReason#getReasonCode()}, not as a pre-formatted four-character string, so this class
 * asserts the plain numbers and treats the zero-padded emission form as documentation rather than as
 * behaviour of this type. Second, the class under test carries no sixth "no reject" constant: it
 * models reason code zero as the absence of a reason, so
 * {@link RejectReason#byReasonCode(int)} reports zero as empty exactly as it reports any other code
 * the legacy program never emits. That omission is a legitimate shape, and the test that would have
 * pinned a blank-described no-reject constant is therefore deliberately not written; the zero probe
 * lives with the other unrecognised codes instead. The class under test also exposes no predicate
 * method, so there is no predicate to exercise; its whole public surface is the two accessors and the
 * one static lookup, and all three are exercised here.
 *
 * <p><strong>Every expectation is hand-derived.</strong> No production component is asked to compute
 * an expected value. In particular the batch step that assembles and writes the reject record is
 * never invoked: the zero-padded four-digit forms asserted below are typed out as literals derived by
 * inspection from the codes, and the description widths are measured from literals typed out here,
 * never from anything a formatter produced.
 *
 * <p><strong>Why no total constant count is asserted.</strong> The count is deliberately absent. A
 * no-reject constant for code zero would be a legitimate production shape, so any assertion pinning
 * the total number of constants would fail against one of two equally correct implementations while
 * proving nothing about the five reasons that matter. Every collection of "the five legacy reasons"
 * below is therefore built by naming those five constants explicitly, and no size assertion is ever
 * made over the enumeration's own constant list. For the same reason no {@code switch} over this type
 * appears here: an optional sixth constant would make it inexhaustive, which under the module's
 * warnings-as-errors compilation would fail the build rather than fail a test.
 */
@DisplayName("RejectReason :: the five reject reasons stamped into the 80-byte validation trailer")
class RejectReasonTest {

    /** Declared width of the reason-code field of the trailer, read from {@code PIC 9(04)}. */
    private static final int REASON_CODE_WIDTH = 4;

    /** Declared width of the description field of the trailer, read from {@code PIC X(76)}. */
    private static final int DESCRIPTION_WIDTH = 76;

    /** Declared width of the validation trailer as a whole, read from {@code PIC X(80)}. */
    private static final int VALIDATION_TRAILER_WIDTH = 80;

    /** Declared width of the copied daily-transaction image, read from {@code PIC X(350)}. */
    private static final int SOURCE_IMAGE_WIDTH = 350;

    /** Width of the whole reject record, the image and the trailer taken together. */
    private static final int REJECT_RECORD_WIDTH = 430;

    /**
     * Field widths of the daily-transaction record in declaration order, read from the copybook that
     * declares it. The sixth entry is the signed amount field counted at its display width; decoding
     * that field belongs to another component and is not exercised here.
     *
     * <p>Present so that the 350-byte image quoted by the reject-record declaration is corroborated
     * by addition over the layout that produces it, rather than taken on trust from one figure.
     */
    private static final int[] DAILY_IMAGE_FIELD_WIDTHS_IN_ORDER = {
        16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20
    };

    /** Fields the daily-transaction record declares. */
    private static final int DAILY_IMAGE_FIELD_COUNT = 14;

    /** Reason code raised when the card cross-reference read fails on an invalid key. */
    private static final int INVALID_CARD_NUMBER_CODE = 100;

    /** Reason code raised when the account read fails on an invalid key, during validation. */
    private static final int ACCOUNT_NOT_FOUND_ON_READ_CODE = 101;

    /** Reason code raised when the transaction would take the account past its credit limit. */
    private static final int OVERLIMIT_TRANSACTION_CODE = 102;

    /** Reason code raised when the transaction arrives after the account expiration date. */
    private static final int TRANSACTION_AFTER_EXPIRATION_CODE = 103;

    /** Reason code raised when the account rewrite fails on an invalid key, after posting. */
    private static final int ACCOUNT_NOT_FOUND_ON_REWRITE_CODE = 109;

    /** Description text carried by reason code 100, typed out verbatim. */
    private static final String INVALID_CARD_NUMBER_DESCRIPTION = "INVALID CARD NUMBER FOUND";

    /**
     * Description text carried by reason codes 101 and 109 alike, typed out verbatim once because
     * the two codes carry byte-identical text. The shared spelling is data, not redundancy.
     */
    private static final String ACCOUNT_NOT_FOUND_DESCRIPTION = "ACCOUNT RECORD NOT FOUND";

    /** Description text carried by reason code 102, typed out verbatim. */
    private static final String OVERLIMIT_TRANSACTION_DESCRIPTION = "OVERLIMIT TRANSACTION";

    /** Description text carried by reason code 103, typed out verbatim. */
    private static final String TRANSACTION_AFTER_EXPIRATION_DESCRIPTION =
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /** Encoded width of the description carried by reason code 100. */
    private static final int INVALID_CARD_NUMBER_DESCRIPTION_BYTES = 25;

    /** Encoded width of the description carried by reason codes 101 and 109. */
    private static final int ACCOUNT_NOT_FOUND_DESCRIPTION_BYTES = 24;

    /** Encoded width of the description carried by reason code 102. */
    private static final int OVERLIMIT_TRANSACTION_DESCRIPTION_BYTES = 21;

    /** Encoded width of the description carried by reason code 103, the longest of the five. */
    private static final int TRANSACTION_AFTER_EXPIRATION_DESCRIPTION_BYTES = 42;

    /**
     * Emitted form of reason code 100, typed out by inspection from the code and the four-digit
     * width of its field. No formatter produced it and none is used to check it.
     */
    private static final String FOUR_DIGIT_FORM_OF_INVALID_CARD_NUMBER = "0100";

    /** Emitted form of reason code 101, typed out by inspection. */
    private static final String FOUR_DIGIT_FORM_OF_ACCOUNT_NOT_FOUND_ON_READ = "0101";

    /** Emitted form of reason code 102, typed out by inspection. */
    private static final String FOUR_DIGIT_FORM_OF_OVERLIMIT_TRANSACTION = "0102";

    /** Emitted form of reason code 103, typed out by inspection. */
    private static final String FOUR_DIGIT_FORM_OF_TRANSACTION_AFTER_EXPIRATION = "0103";

    /** Emitted form of reason code 109, typed out by inspection. */
    private static final String FOUR_DIGIT_FORM_OF_ACCOUNT_NOT_FOUND_ON_REWRITE = "0109";

    /** The single leading zero every one of the five three-digit codes acquires when emitted. */
    private static final String LEADING_ZERO = "0";

    /**
     * The five legacy reject reasons in ascending code order, named one by one.
     *
     * <p>Built by naming the constants rather than by asking the enumeration for its constant list,
     * so that this collection means "the five reasons the legacy program defines" and keeps that
     * meaning whether or not the class under test also models the no-reject state.
     */
    private static final List<RejectReason> FIVE_LEGACY_REASONS_IN_CODE_ORDER = List.of(
            RejectReason.INVALID_CARD_NUMBER,
            RejectReason.ACCOUNT_NOT_FOUND_ON_READ,
            RejectReason.OVERLIMIT_TRANSACTION,
            RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION,
            RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

    /** The five codes in the same order, typed out a second time as a cross-check. */
    private static final List<Integer> FIVE_CODES_IN_ORDER = List.of(100, 101, 102, 103, 109);

    /** The five descriptions in the same order, typed out a second time as a cross-check. */
    private static final List<String> FIVE_DESCRIPTIONS_IN_ORDER = List.of(
            "INVALID CARD NUMBER FOUND",
            "ACCOUNT RECORD NOT FOUND",
            "OVERLIMIT TRANSACTION",
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            "ACCOUNT RECORD NOT FOUND");

    /** The five encoded description widths in the same order, hand-counted. */
    private static final List<Integer> FIVE_DESCRIPTION_BYTE_WIDTHS_IN_ORDER =
            List.of(25, 24, 21, 42, 24);

    /** The five emitted four-digit forms in the same order, typed out by inspection. */
    private static final List<String> FOUR_DIGIT_FORMS_IN_ORDER =
            List.of("0100", "0101", "0102", "0103", "0109");

    /** Stable identifiers of the five constants in the same order. */
    private static final List<String> FIVE_IDENTIFIERS_IN_ORDER = List.of(
            "INVALID_CARD_NUMBER",
            "ACCOUNT_NOT_FOUND_ON_READ",
            "OVERLIMIT_TRANSACTION",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION",
            "ACCOUNT_NOT_FOUND_ON_REWRITE");

    /**
     * The four reasons the validation cascade can raise, named one by one.
     *
     * <p>The cascade is the only path that runs before the driver decides whether to post or to
     * reject, so these four are the only reasons that can reach a written reject record. Code 109 is
     * deliberately absent; the reason why is proved in {@link DefinedButNeverEmitted}.
     */
    private static final List<RejectReason> REASONS_THE_VALIDATION_CASCADE_CAN_RAISE = List.of(
            RejectReason.INVALID_CARD_NUMBER,
            RejectReason.ACCOUNT_NOT_FOUND_ON_READ,
            RejectReason.OVERLIMIT_TRANSACTION,
            RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);

    /**
     * Size of the hand-named five-reason collection above.
     *
     * <p>This is the size of a list built here by naming five constants, which is a property of this
     * test's own oracle. It is never used as, and must not be read as, a total count of the
     * enumeration's constants.
     */
    private static final int LEGACY_REASON_COUNT = 5;

    /** Reasons the validation cascade can raise, counted from the hand-named list above. */
    private static final int VALIDATION_CASCADE_REASON_COUNT = 4;

    /** Distinct description spellings among the five reasons: five texts, one of them shared. */
    private static final int DISTINCT_DESCRIPTION_COUNT = 4;

    /** Times the shared account-not-found spelling occurs among the five reasons. */
    private static final int SHARED_DESCRIPTION_OCCURRENCES = 2;

    /**
     * Measures the encoded width of a value.
     *
     * <p>Width is measured in encoded bytes rather than in characters because the field this value
     * lands in is a fixed-width byte field. The character count and the byte count coincide for
     * these particular values, which is exactly why measuring characters would hide a value that had
     * acquired a non-ASCII character.
     *
     * @param value the value to measure
     * @return the number of bytes the value encodes to
     */
    private static int asciiByteLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Sums a run of declared field widths so that every width asserted below is arrived at by
     * addition over the layout rather than quoted as a bare number.
     *
     * @param widths declared field widths in declaration order
     * @return the summed width
     */
    private static int sumOfWidths(final int[] widths) {
        int summed = 0;
        for (final int width : widths) {
            summed += width;
        }
        return summed;
    }

    @Nested
    @DisplayName("Vocabulary of the reject-reason field")
    class Vocabulary {

        @Test
        @DisplayName("all five reasons the posting program stamps exist, so every code the trailer "
                + "can carry has a constant to name it")
        void allFiveReasonsExist() {
            // Containment, never an exact match and never a size: a sixth constant modelling the
            // no-reject state would be a legitimate shape and must not fail this assertion.
            assertThat(RejectReason.values())
                    .as("the reject reasons available to name a stamped reason code")
                    .contains(
                            RejectReason.INVALID_CARD_NUMBER,
                            RejectReason.ACCOUNT_NOT_FOUND_ON_READ,
                            RejectReason.OVERLIMIT_TRANSACTION,
                            RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION,
                            RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            assertThat(FIVE_LEGACY_REASONS_IN_CODE_ORDER)
                    .as("the five reasons named explicitly by this test rather than discovered")
                    .hasSize(LEGACY_REASON_COUNT)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the cross-reference read failure carries code 100, stamped when the card "
                + "number on the daily record resolves to no cross-reference entry")
        void theCrossReferenceReadFailureCarriesCodeOneHundred() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getReasonCode())
                    .as("code stamped when the cross-reference read fails on an invalid key")
                    .isEqualTo(INVALID_CARD_NUMBER_CODE)
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("the account read failure carries code 101, stamped during validation when the "
                + "account the cross-reference points at does not exist")
        void theAccountReadFailureCarriesCodeOneHundredAndOne() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode())
                    .as("code stamped when the account read fails on an invalid key")
                    .isEqualTo(ACCOUNT_NOT_FOUND_ON_READ_CODE)
                    .isEqualTo(101);
        }

        @Test
        @DisplayName("the overlimit failure carries code 102, stamped on the negative branch of the "
                + "credit-limit comparison")
        void theOverlimitFailureCarriesCodeOneHundredAndTwo() {
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getReasonCode())
                    .as("code stamped when the credit-limit comparison fails")
                    .isEqualTo(OVERLIMIT_TRANSACTION_CODE)
                    .isEqualTo(102);
        }

        @Test
        @DisplayName("the expiration failure carries code 103, stamped on the negative branch of the "
                + "expiry comparison the same account read evaluates next")
        void theExpirationFailureCarriesCodeOneHundredAndThree() {
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getReasonCode())
                    .as("code stamped when the transaction arrives after the expiration date")
                    .isEqualTo(TRANSACTION_AFTER_EXPIRATION_CODE)
                    .isEqualTo(103);
        }

        @Test
        @DisplayName("the account rewrite failure carries code 109, stamped after posting when the "
                + "updated balances cannot be written back")
        void theAccountRewriteFailureCarriesCodeOneHundredAndNine() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())
                    .as("code stamped when the account rewrite fails on an invalid key")
                    .isEqualTo(ACCOUNT_NOT_FOUND_ON_REWRITE_CODE)
                    .isEqualTo(109);
        }

        @Test
        @DisplayName("the five codes are distinct and ascend 100, 101, 102, 103, 109, so the gap "
                + "between the validation codes and the rewrite code is preserved rather than closed")
        void theFiveCodesAreDistinctAndAscendWithTheirGap() {
            assertThat(FIVE_CODES_IN_ORDER)
                    .as("the five codes hand-typed in ascending order")
                    .hasSize(LEGACY_REASON_COUNT)
                    .doesNotHaveDuplicates()
                    .containsExactly(100, 101, 102, 103, 109)
                    .isSorted();

            for (int index = 0; index < FIVE_LEGACY_REASONS_IN_CODE_ORDER.size(); index++) {
                assertThat(FIVE_LEGACY_REASONS_IN_CODE_ORDER.get(index).getReasonCode())
                        .as("code of the reason named at position %d of the five", index)
                        .isEqualTo(FIVE_CODES_IN_ORDER.get(index));
            }
        }

        @Test
        @DisplayName("each reason keeps a stable identifier, so the traceability matrix can name a "
                + "stamp site without depending on either the code or the description text")
        void eachReasonKeepsAStableIdentifier() {
            for (int index = 0; index < FIVE_LEGACY_REASONS_IN_CODE_ORDER.size(); index++) {
                assertThat(FIVE_LEGACY_REASONS_IN_CODE_ORDER.get(index).name())
                        .as("identifier of the reason named at position %d of the five", index)
                        .isEqualTo(FIVE_IDENTIFIERS_IN_ORDER.get(index));
            }

            assertThat(FIVE_IDENTIFIERS_IN_ORDER)
                    .as("the five identifiers, which stay distinct even where the text does not")
                    .hasSize(LEGACY_REASON_COUNT)
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Description text of the reject-reason field")
    class DescriptionText {

        @Test
        @DisplayName("code 100 reads INVALID CARD NUMBER FOUND, the text the cross-reference read "
                + "failure moves into the description field")
        void codeOneHundredReadsInvalidCardNumberFound() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getDescription())
                    .as("description stamped alongside code 100")
                    .isEqualTo(INVALID_CARD_NUMBER_DESCRIPTION)
                    .isEqualTo("INVALID CARD NUMBER FOUND");
        }

        @Test
        @DisplayName("code 101 reads ACCOUNT RECORD NOT FOUND, the text the account read failure "
                + "moves into the description field")
        void codeOneHundredAndOneReadsAccountRecordNotFound() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                    .as("description stamped alongside code 101")
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION)
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("code 102 reads OVERLIMIT TRANSACTION, the text the credit-limit failure moves "
                + "into the description field")
        void codeOneHundredAndTwoReadsOverlimitTransaction() {
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getDescription())
                    .as("description stamped alongside code 102")
                    .isEqualTo(OVERLIMIT_TRANSACTION_DESCRIPTION)
                    .isEqualTo("OVERLIMIT TRANSACTION");
        }

        @Test
        @DisplayName("code 103 reads TRANSACTION RECEIVED AFTER ACCT EXPIRATION, keeping the "
                + "abbreviated middle word the legacy text uses")
        void codeOneHundredAndThreeReadsTransactionReceivedAfterAcctExpiration() {
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getDescription())
                    .as("description stamped alongside code 103")
                    .isEqualTo(TRANSACTION_AFTER_EXPIRATION_DESCRIPTION)
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        }

        @Test
        @DisplayName("code 109 reads ACCOUNT RECORD NOT FOUND, the same text code 101 carries, "
                + "because the rewrite failure moves the same literal")
        void codeOneHundredAndNineReadsAccountRecordNotFound() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .as("description stamped alongside code 109")
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION)
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("the five descriptions are the five hand-typed literals in code order, so no "
                + "text has drifted in spelling, spacing or letter case")
        void theFiveDescriptionsAreTheFiveHandTypedLiterals() {
            for (int index = 0; index < FIVE_LEGACY_REASONS_IN_CODE_ORDER.size(); index++) {
                assertThat(FIVE_LEGACY_REASONS_IN_CODE_ORDER.get(index).getDescription())
                        .as("description of the reason named at position %d of the five", index)
                        .isEqualTo(FIVE_DESCRIPTIONS_IN_ORDER.get(index));
            }
        }

        @Test
        @DisplayName("no description is blank, because a reject record with an empty description "
                + "field would give an operator a code and nothing to read")
        void noDescriptionIsBlank() {
            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                assertThat(reason.getDescription())
                        .as("description of reason code %d", reason.getReasonCode())
                        .isNotNull()
                        .isNotEmpty()
                        .isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("Encoded width of the description text")
    class DescriptionGeometry {

        @Test
        @DisplayName("the code 100 description encodes to 25 bytes")
        void theCodeOneHundredDescriptionEncodesToTwentyFiveBytes() {
            assertThat(asciiByteLength(RejectReason.INVALID_CARD_NUMBER.getDescription()))
                    .as("encoded width of the description stamped alongside code 100")
                    .isEqualTo(INVALID_CARD_NUMBER_DESCRIPTION_BYTES)
                    .isEqualTo(25);
        }

        @Test
        @DisplayName("the code 101 description encodes to 24 bytes")
        void theCodeOneHundredAndOneDescriptionEncodesToTwentyFourBytes() {
            assertThat(asciiByteLength(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription()))
                    .as("encoded width of the description stamped alongside code 101")
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION_BYTES)
                    .isEqualTo(24);
        }

        @Test
        @DisplayName("the code 102 description encodes to 21 bytes, the narrowest of the five")
        void theCodeOneHundredAndTwoDescriptionEncodesToTwentyOneBytes() {
            assertThat(asciiByteLength(RejectReason.OVERLIMIT_TRANSACTION.getDescription()))
                    .as("encoded width of the description stamped alongside code 102")
                    .isEqualTo(OVERLIMIT_TRANSACTION_DESCRIPTION_BYTES)
                    .isEqualTo(21);
        }

        @Test
        @DisplayName("the code 103 description encodes to 42 bytes, the widest of the five")
        void theCodeOneHundredAndThreeDescriptionEncodesToFortyTwoBytes() {
            assertThat(asciiByteLength(
                    RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getDescription()))
                    .as("encoded width of the description stamped alongside code 103")
                    .isEqualTo(TRANSACTION_AFTER_EXPIRATION_DESCRIPTION_BYTES)
                    .isEqualTo(42);
        }

        @Test
        @DisplayName("the code 109 description encodes to 24 bytes, the same width as code 101 "
                + "because it is the same text")
        void theCodeOneHundredAndNineDescriptionEncodesToTwentyFourBytes() {
            assertThat(asciiByteLength(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription()))
                    .as("encoded width of the description stamped alongside code 109")
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION_BYTES)
                    .isEqualTo(24);
        }

        @Test
        @DisplayName("the five encoded widths are 25, 24, 21, 42 and 24 in code order, measured on "
                + "the encoded bytes rather than on the character count")
        void theFiveEncodedWidthsAreTwentyFiveTwentyFourTwentyOneFortyTwoAndTwentyFour() {
            assertThat(FIVE_DESCRIPTION_BYTE_WIDTHS_IN_ORDER)
                    .as("the five hand-counted widths in code order")
                    .hasSize(LEGACY_REASON_COUNT)
                    .containsExactly(25, 24, 21, 42, 24);

            for (int index = 0; index < FIVE_LEGACY_REASONS_IN_CODE_ORDER.size(); index++) {
                assertThat(asciiByteLength(
                        FIVE_LEGACY_REASONS_IN_CODE_ORDER.get(index).getDescription()))
                        .as("encoded width of the description named at position %d of the five",
                                index)
                        .isEqualTo(FIVE_DESCRIPTION_BYTE_WIDTHS_IN_ORDER.get(index));
            }
        }

        @Test
        @DisplayName("every description fits the 76-byte description field, so none of the five "
                + "overflows the trailer and shifts the record width")
        void everyDescriptionFitsTheSeventySixByteField() {
            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                assertThat(asciiByteLength(reason.getDescription()))
                        .as("encoded width of the description stamped alongside code %d",
                                reason.getReasonCode())
                        .isPositive()
                        .isLessThanOrEqualTo(DESCRIPTION_WIDTH)
                        .isLessThanOrEqualTo(76);
            }
        }

        @Test
        @DisplayName("the widest description, the 42-byte expiration text, still leaves 34 of the 76 "
                + "bytes of the description field unused")
        void theWidestDescriptionLeavesThirtyFourBytesOfTheFieldUnused() {
            final int widest =
                    asciiByteLength(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION
                            .getDescription());

            assertThat(widest)
                    .as("widest of the five descriptions, measured in encoded bytes")
                    .isEqualTo(TRANSACTION_AFTER_EXPIRATION_DESCRIPTION_BYTES)
                    .isEqualTo(42);

            assertThat(DESCRIPTION_WIDTH - widest)
                    .as("bytes of the description field the widest description leaves unused")
                    .isEqualTo(34)
                    .isPositive();

            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                assertThat(asciiByteLength(reason.getDescription()))
                        .as("width of code %d measured against the widest of the five",
                                reason.getReasonCode())
                        .isLessThanOrEqualTo(widest);
            }
        }
    }

    @Nested
    @DisplayName("Geometry of the 430-byte reject record")
    class TrailerGeometry {

        @Test
        @DisplayName("the four-byte reason code and the 76-byte description sum to the 80-byte "
                + "validation trailer")
        void theTwoTrailerFieldsSumToEightyBytes() {
            assertThat(REASON_CODE_WIDTH + DESCRIPTION_WIDTH)
                    .as("the trailer width, added up from the two fields that make it")
                    .isEqualTo(VALIDATION_TRAILER_WIDTH)
                    .isEqualTo(80);

            assertThat(REASON_CODE_WIDTH)
                    .as("width of the numeric reason-code field")
                    .isEqualTo(4);
            assertThat(DESCRIPTION_WIDTH)
                    .as("width of the fixed-width description field")
                    .isEqualTo(76);
        }

        @Test
        @DisplayName("the 350-byte daily-transaction image and the 80-byte trailer sum to the "
                + "430-byte reject record")
        void theImageAndTheTrailerSumToFourHundredAndThirtyBytes() {
            assertThat(SOURCE_IMAGE_WIDTH + VALIDATION_TRAILER_WIDTH)
                    .as("the reject-record width, added up from the image and the trailer")
                    .isEqualTo(REJECT_RECORD_WIDTH)
                    .isEqualTo(430);

            assertThat(SOURCE_IMAGE_WIDTH + REASON_CODE_WIDTH + DESCRIPTION_WIDTH)
                    .as("the same width, added up from the image and the two trailer fields")
                    .isEqualTo(REJECT_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the fourteen declared field widths of the daily-transaction record sum to the "
                + "350-byte image the reject record copies verbatim")
        void theFourteenDeclaredWidthsSumToTheThreeHundredAndFiftyByteImage() {
            assertThat(DAILY_IMAGE_FIELD_WIDTHS_IN_ORDER)
                    .as("declared field widths of the copied image, in declaration order")
                    .hasSize(DAILY_IMAGE_FIELD_COUNT);

            assertThat(sumOfWidths(DAILY_IMAGE_FIELD_WIDTHS_IN_ORDER))
                    .as("image width, added up over the layout that produces it")
                    .isEqualTo(SOURCE_IMAGE_WIDTH)
                    .isEqualTo(350);

            assertThat(sumOfWidths(DAILY_IMAGE_FIELD_WIDTHS_IN_ORDER) + VALIDATION_TRAILER_WIDTH)
                    .as("reject-record width, added up over the layout plus the trailer")
                    .isEqualTo(REJECT_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the trailer is the tail of the record, occupying one-based bytes 351 through "
                + "430, with the reason code first and the description behind it")
        void theTrailerOccupiesTheLastEightyBytesOfTheRecord() {
            assertThat(SOURCE_IMAGE_WIDTH + 1)
                    .as("one-based first byte of the validation trailer")
                    .isEqualTo(351);

            assertThat(SOURCE_IMAGE_WIDTH + VALIDATION_TRAILER_WIDTH)
                    .as("one-based last byte of the validation trailer")
                    .isEqualTo(430);

            assertThat(SOURCE_IMAGE_WIDTH + REASON_CODE_WIDTH + 1)
                    .as("one-based first byte of the description field")
                    .isEqualTo(355);
        }
    }

    @Nested
    @DisplayName("The description text codes 101 and 109 share")
    class SharedDescriptionText {

        @Test
        @DisplayName("codes 101 and 109 are two separate reasons that carry one identical text, "
                + "because 101 reports a failed account read during validation while 109 reports a "
                + "failed account rewrite after posting, and collapsing them would stop one of the "
                + "five codes ever being stamped")
        void theReadFailureAndTheRewriteFailureAreSeparateReasonsSharingOneText() {
            // The single most important assertion in this class. The shared spelling is a
            // source-level coincidence between two different operations on the account record, not
            // evidence of one reason wearing two names. Aliasing either constant to the other,
            // having one delegate to the other, or rewording a description to tell them apart would
            // all compile and would all break the trailer contract. The faithful-over-idiomatic tie
            // break that keeps them separate is recorded in docs/decision-log.md.
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ)
                    .as("the reason raised by a failed account read")
                    .isNotSameAs(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode())
                    .as("code of the read failure, which must not become the rewrite code")
                    .isEqualTo(101)
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode());

            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())
                    .as("code of the rewrite failure, which must not become the read code")
                    .isEqualTo(109);

            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                    .as("text the two reasons share, which must stay identical")
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION)
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");

            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.name())
                    .as("identifier of the read failure, distinct from the rewrite failure")
                    .isEqualTo("ACCOUNT_NOT_FOUND_ON_READ")
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.name());
        }

        @Test
        @DisplayName("the shared text occurs on exactly two of the five reasons, so five stamped "
                + "texts resolve to four distinct spellings")
        void theSharedTextOccursOnExactlyTwoOfTheFiveReasons() {
            int occurrences = 0;
            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                if (ACCOUNT_NOT_FOUND_DESCRIPTION.equals(reason.getDescription())) {
                    occurrences++;
                }
            }

            assertThat(occurrences)
                    .as("reasons among the five that carry the account-not-found text")
                    .isEqualTo(SHARED_DESCRIPTION_OCCURRENCES)
                    .isEqualTo(2);

            assertThat(FIVE_DESCRIPTIONS_IN_ORDER.stream().distinct().toList())
                    .as("distinct spellings among the five hand-typed texts")
                    .hasSize(DISTINCT_DESCRIPTION_COUNT)
                    .containsExactly(
                            "INVALID CARD NUMBER FOUND",
                            "ACCOUNT RECORD NOT FOUND",
                            "OVERLIMIT TRANSACTION",
                            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

            assertThat(FIVE_LEGACY_REASONS_IN_CODE_ORDER.stream()
                    .map(RejectReason::getDescription)
                    .distinct()
                    .toList())
                    .as("distinct spellings among the five stamped texts")
                    .hasSize(DISTINCT_DESCRIPTION_COUNT);
        }

        @Test
        @DisplayName("the account-not-found text is the only collision, because the other three "
                + "texts occur once each among the five reasons")
        void theAccountNotFoundTextIsTheOnlyCollision() {
            assertThat(FIVE_DESCRIPTIONS_IN_ORDER)
                    .as("occurrences of each hand-typed text among the five")
                    .containsOnlyOnce("INVALID CARD NUMBER FOUND")
                    .containsOnlyOnce("OVERLIMIT TRANSACTION")
                    .containsOnlyOnce("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

            assertThat(FIVE_DESCRIPTIONS_IN_ORDER)
                    .as("the one text that appears twice")
                    .filteredOn(ACCOUNT_NOT_FOUND_DESCRIPTION::equals)
                    .hasSize(SHARED_DESCRIPTION_OCCURRENCES);
        }

        @Test
        @DisplayName("both reasons that share the text stay separately resolvable, because the "
                + "lookup is keyed on the reason code the two do not share")
        void bothReasonsSharingTheTextStaySeparatelyResolvable() {
            assertThat(RejectReason.byReasonCode(ACCOUNT_NOT_FOUND_ON_READ_CODE))
                    .as("reason resolved from code 101, which must not be the rewrite failure")
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ)
                    .isNotEqualTo(Optional.of(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE));

            assertThat(RejectReason.byReasonCode(ACCOUNT_NOT_FOUND_ON_REWRITE_CODE))
                    .as("reason resolved from code 109, which must not be the read failure")
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)
                    .isNotEqualTo(Optional.of(RejectReason.ACCOUNT_NOT_FOUND_ON_READ));
        }
    }

    @Nested
    @DisplayName("Resolution of a reason code recovered from a reject record")
    class CodeKeyedResolution {

        @Test
        @DisplayName("code 100 resolves to the cross-reference read failure")
        void codeOneHundredResolvesToTheCrossReferenceReadFailure() {
            final Optional<RejectReason> resolved =
                    RejectReason.byReasonCode(INVALID_CARD_NUMBER_CODE);

            assertThat(resolved)
                    .as("reason resolved from code 100")
                    .isPresent()
                    .contains(RejectReason.INVALID_CARD_NUMBER);
        }

        @Test
        @DisplayName("code 102 resolves to the overlimit failure")
        void codeOneHundredAndTwoResolvesToTheOverlimitFailure() {
            assertThat(RejectReason.byReasonCode(OVERLIMIT_TRANSACTION_CODE))
                    .as("reason resolved from code 102")
                    .isPresent()
                    .contains(RejectReason.OVERLIMIT_TRANSACTION);
        }

        @Test
        @DisplayName("code 103 resolves to the expiration failure")
        void codeOneHundredAndThreeResolvesToTheExpirationFailure() {
            assertThat(RejectReason.byReasonCode(TRANSACTION_AFTER_EXPIRATION_CODE))
                    .as("reason resolved from code 103")
                    .isPresent()
                    .contains(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
        }

        @Test
        @DisplayName("all five codes resolve to the reason that carries them, so a code recovered "
                + "from a stamped trailer names its reason again")
        void allFiveCodesResolveToTheReasonThatCarriesThem() {
            for (int index = 0; index < FIVE_CODES_IN_ORDER.size(); index++) {
                assertThat(RejectReason.byReasonCode(FIVE_CODES_IN_ORDER.get(index)))
                        .as("reason resolved from the code at position %d of the five", index)
                        .isPresent()
                        .contains(FIVE_LEGACY_REASONS_IN_CODE_ORDER.get(index));
            }
        }

        @Test
        @DisplayName("every reason round trips through its own code, so no two reasons collide in "
                + "the code-keyed index and none of the five is lost from it")
        void everyReasonRoundTripsThroughItsOwnCode() {
            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                assertThat(RejectReason.byReasonCode(reason.getReasonCode()))
                        .as("reason resolved from the code reason %s carries", reason.name())
                        .contains(reason);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 99, 104, 105, 106, 107, 108, 110, 200, 999, 1000, 9999, -1, -109})
        @DisplayName("a code the posting program never stamps resolves to nothing, including zero, "
                + "which is the initial not-rejected state of the field rather than a sixth reason")
        void aCodeTheProgramNeverStampsResolvesToNothing(final int unstampedCode) {
            assertThat(RejectReason.byReasonCode(unstampedCode))
                    .as("reason resolved from the unstamped code %d", unstampedCode)
                    .isNotNull()
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 99, 104, 110, 999})
        @DisplayName("resolving an unrecognised code reports absence instead of throwing, so a "
                + "trailer carrying an unexpected code stays readable")
        void resolvingAnUnrecognisedCodeReportsRatherThanThrows(final int unstampedCode) {
            assertThatCode(() -> RejectReason.byReasonCode(unstampedCode))
                    .as("resolving the unstamped code %d", unstampedCode)
                    .doesNotThrowAnyException();

            assertThat(RejectReason.byReasonCode(unstampedCode))
                    .as("result of resolving the unstamped code %d", unstampedCode)
                    .isEmpty();
        }

        @Test
        @DisplayName("resolution never returns null, so a caller reading a recovered code always "
                + "has a result to interrogate")
        void resolutionNeverReturnsNull() {
            assertThat(RejectReason.byReasonCode(INVALID_CARD_NUMBER_CODE))
                    .as("result of resolving a stamped code")
                    .isNotNull();

            assertThat(RejectReason.byReasonCode(Integer.MAX_VALUE))
                    .as("result of resolving the largest representable code")
                    .isNotNull()
                    .isEmpty();

            assertThat(RejectReason.byReasonCode(Integer.MIN_VALUE))
                    .as("result of resolving the smallest representable code")
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The four-digit form the reason code is emitted in")
    class FourDigitEmissionForm {

        @Test
        @DisplayName("each reason stores the plain number rather than a pre-formatted field image, "
                + "because the reason-code field is numeric and four digits wide")
        void eachReasonStoresThePlainNumber() {
            // The class under test stores an int, so the assertions here are on the numbers. The
            // zero-padded image each number is written as is documented below and typed out by
            // inspection; producing that image belongs to the batch step that assembles the reject
            // record, which this class never invokes and never imitates.
            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                assertThat(reason.getReasonCode())
                        .as("code stored by reason %s", reason.name())
                        .isBetween(100, 999);
            }

            assertThat(FIVE_CODES_IN_ORDER)
                    .as("the five stored numbers, none of them a four-character image")
                    .containsExactly(100, 101, 102, 103, 109);
        }

        @Test
        @DisplayName("the five emitted images are 0100, 0101, 0102, 0103 and 0109, each one the "
                + "stored number with the single leading zero the four-digit field requires")
        void theFiveEmittedImagesCarryASingleLeadingZero() {
            assertThat(FOUR_DIGIT_FORMS_IN_ORDER)
                    .as("the five emitted images, typed out by inspection from the codes")
                    .hasSize(LEGACY_REASON_COUNT)
                    .containsExactly("0100", "0101", "0102", "0103", "0109");

            assertThat(FOUR_DIGIT_FORM_OF_INVALID_CARD_NUMBER)
                    .as("emitted image of code 100")
                    .isEqualTo("0100");
            assertThat(FOUR_DIGIT_FORM_OF_ACCOUNT_NOT_FOUND_ON_READ)
                    .as("emitted image of code 101")
                    .isEqualTo("0101");
            assertThat(FOUR_DIGIT_FORM_OF_OVERLIMIT_TRANSACTION)
                    .as("emitted image of code 102")
                    .isEqualTo("0102");
            assertThat(FOUR_DIGIT_FORM_OF_TRANSACTION_AFTER_EXPIRATION)
                    .as("emitted image of code 103")
                    .isEqualTo("0103");
            assertThat(FOUR_DIGIT_FORM_OF_ACCOUNT_NOT_FOUND_ON_REWRITE)
                    .as("emitted image of code 109")
                    .isEqualTo("0109");

            for (final String emitted : FOUR_DIGIT_FORMS_IN_ORDER) {
                assertThat(emitted)
                        .as("emitted image %s", emitted)
                        .startsWith(LEADING_ZERO);
            }
        }

        @Test
        @DisplayName("each emitted image is exactly four bytes wide, matching the four-digit "
                + "reason-code field, so the description behind it never shifts")
        void eachEmittedImageIsExactlyFourBytesWide() {
            for (final String emitted : FOUR_DIGIT_FORMS_IN_ORDER) {
                assertThat(asciiByteLength(emitted))
                        .as("encoded width of the emitted image %s", emitted)
                        .isEqualTo(REASON_CODE_WIDTH)
                        .isEqualTo(4);
            }
        }

        @Test
        @DisplayName("each emitted image denotes the number its reason stores, read back as a "
                + "number rather than produced by formatting one")
        void eachEmittedImageDenotesTheStoredNumber() {
            // Reading the hand-typed image back as a number is the safe direction: it compares two
            // independently derived values without ever asking a formatter to manufacture the
            // expectation this test is supposed to supply.
            for (int index = 0; index < FOUR_DIGIT_FORMS_IN_ORDER.size(); index++) {
                assertThat(Integer.parseInt(FOUR_DIGIT_FORMS_IN_ORDER.get(index)))
                        .as("number denoted by the emitted image at position %d of the five", index)
                        .isEqualTo(FIVE_CODES_IN_ORDER.get(index))
                        .isEqualTo(FIVE_LEGACY_REASONS_IN_CODE_ORDER.get(index).getReasonCode());
            }
        }
    }

    @Nested
    @DisplayName("Code 109, defined but never written to a reject file")
    class DefinedButNeverEmitted {

        @Test
        @DisplayName("code 109 is fully defined even though the posting program can never write it "
                + "to a reject record, because the driver has already chosen posting over rejecting "
                + "by the time the account rewrite can stamp it")
        void codeOneHundredAndNineIsFullyDefinedThoughNeverWritten() {
            // Traced end to end in the posting program. The driver blanks the reason code and the
            // description, performs the validation paragraph, and then tests the reason code: when
            // it is still zero the posting paragraph runs, and otherwise the reject counter is
            // incremented and the reject-write paragraph runs. The validation paragraph reaches only
            // the cross-reference lookup and the account lookup, so it can stamp only 100, 101, 102
            // or 103. Code 109 is stamped by the account-rewrite paragraph, which the posting
            // paragraph performs - that is, only on the branch where the reject-write paragraph was
            // not taken. The branch is therefore already decided before 109 can exist, so 109 is
            // never emitted on unmodified fixtures.
            //
            // No synthetic emission path was invented for it, the legacy branch was not "fixed",
            // and nothing here asserts or simulates 109 appearing in a reject file. The constant is
            // preserved because the vocabulary is contractual; the preservation is recorded in
            // docs/decision-log.md.
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())
                    .as("code of the rewrite failure, defined whether or not it is ever written")
                    .isEqualTo(ACCOUNT_NOT_FOUND_ON_REWRITE_CODE)
                    .isEqualTo(109);

            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .as("description of the rewrite failure")
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION)
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");

            assertThat(asciiByteLength(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription()))
                    .as("encoded width of the rewrite failure description")
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION_BYTES)
                    .isEqualTo(24);

            assertThat(RejectReason.byReasonCode(ACCOUNT_NOT_FOUND_ON_REWRITE_CODE))
                    .as("the rewrite failure resolved from its code, so a trailer that did somehow "
                            + "carry 109 would still be readable")
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
        }

        @Test
        @DisplayName("only the four validation reasons can reach a written reject record, because "
                + "the validation paragraph reaches only the cross-reference and account lookups")
        void onlyTheFourValidationReasonsCanReachAWrittenRejectRecord() {
            assertThat(REASONS_THE_VALIDATION_CASCADE_CAN_RAISE)
                    .as("reasons the validation paragraph can stamp before the driver branches")
                    .hasSize(VALIDATION_CASCADE_REASON_COUNT)
                    .doesNotHaveDuplicates()
                    .containsExactly(
                            RejectReason.INVALID_CARD_NUMBER,
                            RejectReason.ACCOUNT_NOT_FOUND_ON_READ,
                            RejectReason.OVERLIMIT_TRANSACTION,
                            RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION)
                    .doesNotContain(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            assertThat(REASONS_THE_VALIDATION_CASCADE_CAN_RAISE.stream()
                    .map(RejectReason::getReasonCode)
                    .toList())
                    .as("codes the validation paragraph can stamp")
                    .containsExactly(100, 101, 102, 103)
                    .doesNotContain(ACCOUNT_NOT_FOUND_ON_REWRITE_CODE);

            assertThat(FIVE_LEGACY_REASONS_IN_CODE_ORDER)
                    .as("the five defined reasons, of which the four above are the writable subset")
                    .hasSize(LEGACY_REASON_COUNT)
                    .containsAll(REASONS_THE_VALIDATION_CASCADE_CAN_RAISE)
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            assertThat(LEGACY_REASON_COUNT - VALIDATION_CASCADE_REASON_COUNT)
                    .as("defined reasons that no reject record can carry")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("What this vocabulary deliberately does not carry")
    class DeliberatelyNotCarried {

        @Test
        @DisplayName("no index over these reasons can be keyed on description text, because the two "
                + "account failures would collide and one of the five codes would be lost")
        void noIndexOverTheseReasonsCanBeKeyedOnDescriptionText() {
            // Recorded by demonstration rather than by inspecting the class under test: a
            // description-keyed index is impossible here, so none is built, asserted or assumed
            // anywhere in this class, and no test resolves a reason from its text. Resolution is
            // keyed on the reason code only. Nothing below uses reflection to prove that absence -
            // the proof is the collision itself plus the fact that no such lookup is ever named.
            final List<RejectReason> sharingTheText = FIVE_LEGACY_REASONS_IN_CODE_ORDER.stream()
                    .filter(reason -> ACCOUNT_NOT_FOUND_DESCRIPTION.equals(reason.getDescription()))
                    .toList();

            assertThat(sharingTheText)
                    .as("reasons a description key would have to distinguish and could not")
                    .hasSize(SHARED_DESCRIPTION_OCCURRENCES)
                    .containsExactly(
                            RejectReason.ACCOUNT_NOT_FOUND_ON_READ,
                            RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            assertThat(sharingTheText.stream().map(RejectReason::getDescription).distinct().toList())
                    .as("distinct text among the colliding reasons, which is one text for two "
                            + "reasons")
                    .hasSize(1);

            assertThat(sharingTheText.stream().map(RejectReason::getReasonCode).distinct().toList())
                    .as("distinct codes among the colliding reasons, which is what the index is "
                            + "keyed on instead")
                    .hasSize(SHARED_DESCRIPTION_OCCURRENCES)
                    .containsExactly(101, 109);
        }

        @Test
        @DisplayName("no padding, zero-filling or truncation happens in this vocabulary, because "
                + "the reason code stays a bare number and the description stays unpadded")
        void noPaddingZeroFillingOrTruncationHappensHere() {
            // Zero-filling the code to its four digits and blank-filling the description to its 76
            // are the responsibility of the batch step that assembles and writes the reject record.
            // This class therefore contains no formatting call, no repetition helper and no padding
            // or truncation helper of any kind, and it never invokes the writing step to obtain an
            // expectation. The assertions below are the positive proof that the values arrive here
            // unpadded and untruncated.
            for (int index = 0; index < FIVE_LEGACY_REASONS_IN_CODE_ORDER.size(); index++) {
                final RejectReason reason = FIVE_LEGACY_REASONS_IN_CODE_ORDER.get(index);

                assertThat(asciiByteLength(reason.getDescription()))
                        .as("width of the description of code %d, unpadded and untruncated",
                                reason.getReasonCode())
                        .isEqualTo(FIVE_DESCRIPTION_BYTE_WIDTHS_IN_ORDER.get(index))
                        .isLessThan(DESCRIPTION_WIDTH);

                assertThat(reason.getDescription())
                        .as("description of code %d, which carries no filler of its own",
                                reason.getReasonCode())
                        .doesNotEndWith(" ")
                        .doesNotStartWith(" ");

                assertThat(reason.getReasonCode())
                        .as("code %d, stored as a number rather than a four-character image",
                                reason.getReasonCode())
                        .isLessThan(1000);
            }
        }

        @Test
        @DisplayName("there is no reason constant for code zero, because zero is the not-rejected "
                + "state the driver initialises the field to rather than a sixth reject reason")
        void thereIsNoReasonConstantForCodeZero() {
            // The class under test models the not-rejected state as absence, which is one of two
            // legitimate shapes: a sixth constant carrying code zero and an empty description would
            // have been equally acceptable. Because this one omits it, the test that would have
            // pinned a blank-described constant is deliberately not written, and the zero probe
            // lives here and with the other unrecognised codes instead. For the same reason no
            // assertion anywhere in this class pins a total number of constants, and no switch over
            // this type appears here at all.
            assertThat(RejectReason.byReasonCode(0))
                    .as("reason resolved from the not-rejected code zero")
                    .isNotNull()
                    .isEmpty();

            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                assertThat(reason.getReasonCode())
                        .as("code of reason %s, which is a reject reason and so cannot be zero",
                                reason.name())
                        .isNotZero()
                        .isPositive();

                assertThat(reason.getDescription())
                        .as("description of reason %s, which no reject reason leaves blank",
                                reason.name())
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("the vocabulary carries no synthetic fallback reason, because the posting "
                + "program stamps five codes and invents none for the cases it does not detect")
        void theVocabularyCarriesNoSyntheticFallbackReason() {
            assertThat(FIVE_IDENTIFIERS_IN_ORDER)
                    .as("identifiers of the five reasons, none of them a synthetic fallback")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "DEFAULT", "NO_REJECT", "OK");

            for (final RejectReason reason : FIVE_LEGACY_REASONS_IN_CODE_ORDER) {
                assertThat(reason.getDescription())
                        .as("description of reason %s, which reports a detected failure rather "
                                + "than a fallback", reason.name())
                        .isNotEqualTo("NO REJECT")
                        .isNotEqualTo("OK")
                        .isNotEqualTo("UNKNOWN");
            }
        }
    }
}
