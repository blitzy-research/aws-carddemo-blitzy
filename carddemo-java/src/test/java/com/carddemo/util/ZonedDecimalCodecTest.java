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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for {@link ZonedDecimalCodec}, the single point at which a legacy zoned-decimal field
 * image becomes a {@link BigDecimal} and becomes a field image again.
 *
 * <p><strong>Why this test carries the weight it does.</strong> The codec is the only place in the
 * module where a scale is ever applied, which is what stops any one caller introducing a second
 * rounding policy, so byte parity for every monetary and rate value in the migration rests on it. A
 * wrong rounding mode would differ by one cent on roughly half of all interest computations, and a
 * test written under the same wrong assumption would never see it. Every expectation below was
 * therefore derived by hand from the verified field layouts and the sample bytes, and re-derived
 * independently before being written down. No assertion asks the codec what its own answer ought to
 * be.</p>
 *
 * <p><strong>Rounding is truncation toward zero.</strong> A search for the {@code ROUNDED} keyword
 * across every program and copybook in the estate returns zero occurrences, so a COBOL store
 * truncates and {@link RoundingMode#DOWN} is the only faithful mode (decision D-02). The value
 * {@code 1.015} is asserted below precisely because truncation keeps {@code 1.01} while both
 * conventional half-rounding policies - half away from zero and half to even - would carry it to
 * {@code 1.02}, so one case rules out both and a mode swap cannot pass unnoticed. No decimal
 * expectation in this file is built from a binary approximation: every literal is either a decimal
 * string or a {@link BigInteger} paired with an explicit scale.</p>
 *
 * <p><strong>Overpunched signs.</strong> The sign is folded into the final digit byte and there is no
 * separate sign byte, so <code>&#123;</code> and {@code A} through {@code I} carry a positive digit 0
 * through 9 while <code>&#125;</code> and {@code J} through {@code R} carry a negative digit 0
 * through 9 (decision D-01); a plain trailing digit denotes an unsigned, positive field. All twenty
 * overpunch characters occur in the shipped daily-transaction sample: a census of the final byte of
 * the amount field, at zero-based offset 142 of the 350-byte record, across all 300 records finds
 * every positive form (250 amounts) and every negative form (50 amounts). Each of the twenty is
 * therefore exercised below by a case rather than left to a synthetic sample, positive and negative
 * zero included, because both are real observed codes.</p>
 *
 * <p><strong>No packed decimal, and none may be expected.</strong> A search for {@code COMP-3} across
 * the estate finds ten textual sites in five programs - one in the card-list program, four in the
 * account-update program, two in the transaction-report program, two in the statement program and one
 * in the bill-payment program - and none at all in the copybook tree. The codec documents nine
 * declaration sites, which is the same evidence counted by declaration rather than by textual
 * occurrence. Either count carries the same load-bearing conclusion: no {@code COMP-3} field is ever
 * written to a file, so the module carries no binary-coded-decimal decoder and this file asserts
 * none (decision D-01).</p>
 *
 * <p><strong>One exception type on every rejection path.</strong> The codec raises
 * {@link IllegalArgumentException} for a malformed image, a wrong width, a bad byte, a wrong scale
 * and a {@code null} argument alike, so a caller has exactly one type to handle; decision D-11
 * records why a module exception type is not used, and decision D-16 records why no message echoes
 * the rejected value. The assertions below therefore expect {@link IllegalArgumentException} on the
 * {@code null} paths as well.</p>
 *
 * <p><strong>Zero always renders its decimals.</strong> A search for {@code BLANK WHEN ZERO} across
 * the estate returns zero occurrences, so a zero amount is never blanked. That is why a zero decodes
 * to {@code 0.00} at the monetary scale and re-encodes to an all-zero image rather than to spaces.</p>
 *
 * <p><strong>Scope.</strong> This is a pure unit test. It starts no application context, opens no
 * database, touches no filesystem, reaches no network, spawns no container and uses no reflection, so
 * it cannot erode the module's zero-reflection budget. It makes no assertion about elapsed time,
 * throughput or memory, because no such figure exists anywhere in the estate to assert against.</p>
 *
 * <p><strong>Where faithful translation beats idiomatic Java, and where that is recorded.</strong>
 * Five decisions in this area resolve in favour of the legacy behaviour rather than the
 * conventional Java choice, and each is a documented entry in {@code docs/decision-log.md} rather
 * than a silent preference. First, arithmetic truncates instead of rounding, because no
 * {@code ROUNDED} clause exists anywhere in the estate. Second, there is no binary-coded-decimal
 * decoder, because no packed field is ever written to a file. Third, a malformed image raises
 * {@link IllegalArgumentException} rather than a {@code com.carddemo.exception} type, because a
 * keyed or sequential record is fixed length by construction and a short record therefore has no
 * legacy antecedent to model. Fourth, the negative-zero sign is carried beside the amount in the
 * codec's decoded-value carrier rather than discarded, because {@code BigDecimal} has one zero
 * while the legacy image has two and byte parity is owed to both. Fifth, scaling is centralised in
 * the codec so that no other class can introduce a second rounding policy. This file proves all
 * five; it does not edit the decision log.</p>
 *
 * <p>Provenance: the legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Field lengths, byte offsets, scales, sign
 * codes and measured code frequencies cross the boundary into this file; no line of COBOL, job
 * control or copybook source does.</p>
 */
@DisplayName("ZonedDecimalCodec :: zoned decimal to BigDecimal and back, byte for byte")
class ZonedDecimalCodecTest {

    /** Diagnostic field name of the daily-transaction amount, declared {@code PIC S9(09)V99}. */
    private static final String DALYTRAN_AMT = "DALYTRAN-AMT";

    /** Diagnostic field name of the account current balance, declared {@code PIC S9(10)V99}. */
    private static final String ACCT_CURR_BAL = "ACCT-CURR-BAL";

    /** Diagnostic field name of the disclosure-group rate, declared {@code PIC S9(04)V99}. */
    private static final String DIS_INT_RATE = "DIS-INT-RATE";

    /** Diagnostic field name of the transaction category balance, {@code PIC S9(09)V99}. */
    private static final String TRAN_CAT_BAL = "TRAN-CAT-BAL";

    /** Diagnostic field name of the working overlimit basis field, {@code PIC S9(09)V99}. */
    private static final String WS_TEMP_BAL = "WS-TEMP-BAL";

    /** Diagnostic field name of the working monthly interest field, {@code PIC S9(09)V99}. */
    private static final String WS_MONTHLY_INT = "WS-MONTHLY-INT";

    /**
     * Diagnostic field name of the transaction category code, declared {@code PIC 9(04)}. It is
     * unsigned and has no implied decimals, so it is the estate's own example of a scale of zero.
     */
    private static final String TRAN_CAT_CD = "TRAN-CAT-CD";

    /** Verified record length of the daily-transaction record. */
    private static final int DAILY_TRANSACTION_RECORD_LENGTH = 350;

    /** Verified zero-based offset of the amount within the daily-transaction record. */
    private static final int DALYTRAN_AMT_OFFSET = 132;

    /** Verified record length of the account record. */
    private static final int ACCOUNT_RECORD_LENGTH = 300;

    /** Verified zero-based offset of the current balance within the account record. */
    private static final int ACCT_CURR_BAL_OFFSET = 12;

    /** Verified record length of the disclosure-group record. */
    private static final int DISCLOSURE_RECORD_LENGTH = 50;

    /** Verified zero-based offset of the rate within the disclosure-group record. */
    private static final int DIS_INT_RATE_OFFSET = 16;

    /** Verified record length of the transaction category balance record. */
    private static final int CATEGORY_BALANCE_RECORD_LENGTH = 50;

    /** Verified zero-based offset of the balance within the category balance record. */
    private static final int TRAN_CAT_BAL_OFFSET = 17;

    /**
     * Builds a fixed-width record image of {@code recordLength} bytes carrying {@code fieldImage}
     * at {@code fieldOffset}, so that the byte-range entry point can be exercised against the real
     * record geometry rather than against a bare field.
     *
     * <p>The bytes outside the field are spaces, which is deliberate: the codec must read exactly
     * the slice it was asked for, and a neighbouring space would be rejected outright if the slice
     * boundaries were wrong by even one byte.</p>
     *
     * @param  recordLength total length of the record image in bytes
     * @param  fieldOffset  zero-based byte offset at which the field image begins
     * @param  fieldImage   the field image to place, encoded as US-ASCII
     * @return a fresh record image
     */
    private static byte[] recordImageWith(int recordLength, int fieldOffset, String fieldImage) {
        byte[] fieldBytes = fieldImage.getBytes(StandardCharsets.US_ASCII);
        byte[] recordImage = new byte[recordLength];
        for (int index = 0; index < recordImage.length; index++) {
            recordImage[index] = (byte) ' ';
        }
        System.arraycopy(fieldBytes, 0, recordImage, fieldOffset, fieldBytes.length);
        return recordImage;
    }

    @Nested
    @DisplayName("Rounding policy :: no ROUNDED clause exists, so every store truncates")
    class RoundingPolicy {

        @Test
        @DisplayName("the published rounding mode is identically RoundingMode.DOWN")
        void publishesTruncationTowardZeroAsTheOnlySanctionedMode() {
            // An enum constant, so identity is the strongest available assertion and the one that
            // matters: a mode that merely compares equal would still be a different constant.
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE).isSameAs(RoundingMode.DOWN);
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE).isEqualTo(RoundingMode.DOWN);
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE.name()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("the canonical scale is 2, being the V99 of every money and rate picture")
        void publishesTheMonetaryScaleOfTwo() {
            assertThat(ZonedDecimalCodec.MONETARY_SCALE).isEqualTo(2);
        }

        @ParameterizedTest(name = "[{index}] {0} truncates to {1}")
        @CsvSource({
            "1.005, 1.00",
            "1.015, 1.01",
            "2.999, 2.99",
            "0.999, 0.99",
            "9.9999, 9.99",
        })
        @DisplayName("a store into a V99 field discards the surplus digits of a positive value")
        void truncatesAPositiveValueTowardZero(String raw, String expected) {
            BigDecimal actual = ZonedDecimalCodec.toMonetaryScale(new BigDecimal(raw));
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @ParameterizedTest(name = "[{index}] {0} truncates to {1}")
        @CsvSource({
            "-1.005, -1.00",
            "-2.999, -2.99",
            "-0.019, -0.01",
            "-9.9999, -9.99",
        })
        @DisplayName("truncation of a negative value is toward zero, so the magnitude shrinks")
        void truncatesANegativeValueTowardZero(String raw, String expected) {
            BigDecimal actual = ZonedDecimalCodec.toMonetaryScale(new BigDecimal(raw));
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
            assertThat(actual.abs()).isLessThan(new BigDecimal(raw).abs());
        }

        @Test
        @DisplayName("truncation contradicts both conventional half-rounding policies at once")
        void truncationContradictsBothHalfPolicies() {
            // Derived by hand. 1.015 at scale 2 has an exact tie in the discarded digit. Rounding
            // half away from zero carries to 1.02. Rounding half to even also carries to 1.02,
            // because the digit that would be retained, 1, is odd. Truncation keeps 1.01. One
            // value therefore falsifies both alternatives, so substituting either mode in the
            // codec fails this test rather than silently shifting a cent.
            BigDecimal tie = new BigDecimal("1.015");
            BigDecimal truncated = ZonedDecimalCodec.toMonetaryScale(tie);
            assertThat(truncated).isEqualTo(new BigDecimal("1.01"));
            assertThat(truncated).isNotEqualByComparingTo(new BigDecimal("1.02"));

            // A second, non-tie witness: 2.999 carries to 3.00 under either half policy.
            assertThat(ZonedDecimalCodec.toMonetaryScale(new BigDecimal("2.999")))
                    .isEqualTo(new BigDecimal("2.99"))
                    .isNotEqualByComparingTo(new BigDecimal("3.00"));

            // And a negative witness: -1.005 truncates toward zero to -1.00, never to -1.01.
            assertThat(ZonedDecimalCodec.toMonetaryScale(new BigDecimal("-1.005")))
                    .isEqualTo(new BigDecimal("-1.00"))
                    .isNotEqualByComparingTo(new BigDecimal("-1.01"));
        }

        @Test
        @DisplayName("a value already at the monetary scale is returned unchanged and still at 2")
        void leavesAValueAlreadyAtTheMonetaryScaleUntouched() {
            BigDecimal alreadyExact = new BigDecimal("123.45");
            BigDecimal actual = ZonedDecimalCodec.toMonetaryScale(alreadyExact);
            assertThat(actual).isEqualTo(alreadyExact);
            assertThat(actual).isEqualByComparingTo(alreadyExact);
            assertThat(actual.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("raising the scale is exact and simply appends the implied decimal digits")
        void raisingTheScaleIsExact() {
            BigDecimal actual = ZonedDecimalCodec.toScale(new BigDecimal("7"), 2);
            assertThat(actual).isEqualTo(new BigDecimal("7.00"));
            assertThat(actual).isEqualByComparingTo(new BigDecimal("7"));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("lowering the scale to zero discards the decimals without rounding")
        void loweringTheScaleToZeroDiscardsTheDecimals() {
            BigDecimal actual = ZonedDecimalCodec.toScale(new BigDecimal("1.999"), 0);
            assertThat(actual).isEqualTo(new BigDecimal("1"));
            assertThat(actual.scale()).isZero();
        }

        @Test
        @DisplayName("a magnitude below one cent collapses onto zero, which has no negative form")
        void aNegativeMagnitudeBelowOneCentCollapsesOntoPlainZero() {
            // -0.009 truncated toward zero has no hundredths left, and BigDecimal has no negative
            // zero, so the result is plain 0.00 with a zero signum rather than a signed zero. This
            // is the computed path: there is no field image here, so there is no negative-zero byte
            // to preserve. Preserving that byte is the decoded-value carrier's job, and it is
            // proved separately against a real image.
            BigDecimal actual = ZonedDecimalCodec.toMonetaryScale(new BigDecimal("-0.009"));
            assertThat(actual).isEqualTo(new BigDecimal("0.00"));
            assertThat(actual.scale()).isEqualTo(2);
            assertThat(actual.signum()).isZero();
        }
    }

    @Nested
    @DisplayName("Field geometry :: PIC S9(n)V99 occupies exactly n + 2 encoded bytes")
    class FieldGeometry {

        @Test
        @DisplayName("PIC S9(10)V99 is 12 bytes, the shape of all five account money fields")
        void publishesTheTwelveByteAccountShape() {
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_10_V99).isEqualTo(12);
            // The current balance, the credit limit, the cash credit limit, the current cycle
            // credit and the current cycle debit all share this one shape on the 300-byte record.
            assertThat(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH).isEqualTo(12);
        }

        @Test
        @DisplayName("PIC S9(09)V99 is 11 bytes, shared by three persisted amount fields")
        void publishesTheElevenByteAmountShape() {
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99).isEqualTo(11);
            assertThat(ZonedDecimalCodec.TRANSACTION_AMOUNT_WIDTH).isEqualTo(11);
            assertThat(ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH).isEqualTo(11);
            assertThat(ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH).isEqualTo(11);
        }

        @Test
        @DisplayName("PIC S9(04)V99 is 6 bytes and is the estate's only rate shape")
        void publishesTheSixByteRateShape() {
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99).isEqualTo(6);
            assertThat(ZonedDecimalCodec.INTEREST_RATE_WIDTH).isEqualTo(6);
        }

        @Test
        @DisplayName("the daily-transaction amount shares the transaction amount's shape exactly")
        void theDailyTransactionAmountSharesTheTransactionAmountShape() {
            // The daily-transaction layout is byte-for-byte identical to the transaction layout,
            // which is why the two amount fields are the same shape at the same record offset.
            assertThat(ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH)
                    .isEqualTo(ZonedDecimalCodec.TRANSACTION_AMOUNT_WIDTH);
            assertThat(DALYTRAN_AMT_OFFSET).isEqualTo(132);
            assertThat(DAILY_TRANSACTION_RECORD_LENGTH).isEqualTo(350);
        }

        @Test
        @DisplayName("every published width is its integer digit count plus the two V99 decimals")
        void everyWidthIsTheIntegerDigitCountPlusTwo() {
            // A zoned field spends one byte per digit and no byte at all on a sign or a decimal
            // point, because the sign is overpunched and the point is implied. The integer digit
            // counts 10, 9 and 4 are read straight off the picture clauses.
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_10_V99 - ZonedDecimalCodec.MONETARY_SCALE)
                    .isEqualTo(10);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99 - ZonedDecimalCodec.MONETARY_SCALE)
                    .isEqualTo(9);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99 - ZonedDecimalCodec.MONETARY_SCALE)
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("the three shapes are mutually distinct, so a mis-copied slice cannot pass")
        void theThreeShapesAreMutuallyDistinct() {
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99)
                    .isNotEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99)
                    .isNotEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_10_V99);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99)
                    .isNotEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_10_V99);
            assertThat(new int[] {
                ZonedDecimalCodec.WIDTH_PIC_S9_04_V99,
                ZonedDecimalCodec.WIDTH_PIC_S9_09_V99,
                ZonedDecimalCodec.WIDTH_PIC_S9_10_V99,
            }).containsExactly(6, 11, 12).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Overpunched signs :: the final byte carries both the low-order digit and the sign")
    class OverpunchedSigns {

        @ParameterizedTest(name = "[{index}] {0} decodes to {1}")
        @CsvSource({
            "0000000012{, 1.20",
            "0000000012A, 1.21",
            "0000000012B, 1.22",
            "0000000012C, 1.23",
            "0000000012D, 1.24",
            "0000000012E, 1.25",
            "0000000012F, 1.26",
            "0000000012G, 1.27",
            "0000000012H, 1.28",
            "0000000012I, 1.29",
        })
        @DisplayName("all ten positive overpunch characters decode to their signed digit")
        void decodesEveryPositiveOverpunchCharacter(String image, String expected) {
            // Derived by hand for each row. The ten leading bytes are the plain digits 0000000012
            // and the trailing character supplies the low-order digit d together with a positive
            // sign, so the unsigned eleven-digit string is 000000001-2-d. Read at scale 2 that is
            // 12d hundredths, hence 1.20 through 1.29 as the digit runs 0 through 9.
            BigDecimal actual = ZonedDecimalCodec.decode(image, 11, 2, DALYTRAN_AMT);
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
            assertThat(actual.signum()).isPositive();
            // Encoding is the exact inverse, so the image must come back byte for byte.
            assertThat(ZonedDecimalCodec.encode(actual, 11, 2, DALYTRAN_AMT)).isEqualTo(image);
        }

        @ParameterizedTest(name = "[{index}] {0} decodes to {1}")
        @CsvSource({
            "0000000012}, -1.20",
            "0000000012J, -1.21",
            "0000000012K, -1.22",
            "0000000012L, -1.23",
            "0000000012M, -1.24",
            "0000000012N, -1.25",
            "0000000012O, -1.26",
            "0000000012P, -1.27",
            "0000000012Q, -1.28",
            "0000000012R, -1.29",
        })
        @DisplayName("all ten negative overpunch characters decode to their signed digit")
        void decodesEveryNegativeOverpunchCharacter(String image, String expected) {
            // Same derivation as the positive rows, with the trailing character drawn from the
            // negative table, so the whole value is negated rather than only its last digit.
            BigDecimal actual = ZonedDecimalCodec.decode(image, 11, 2, DALYTRAN_AMT);
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
            assertThat(actual.signum()).isNegative();
            assertThat(ZonedDecimalCodec.encode(actual, 11, 2, DALYTRAN_AMT)).isEqualTo(image);
        }

        @ParameterizedTest(name = "[{index}] {0} decodes to {1}")
        @CsvSource({
            "00000000120, 1.20",
            "00000000127, 1.27",
            "00000000129, 1.29",
        })
        @DisplayName("an unsigned field ending in a plain digit is accepted and treated as positive")
        void decodesAnUnsignedFieldEndingInAPlainDigit(String image, String expected) {
            BigDecimal actual = ZonedDecimalCodec.decode(image, 11, 2, DALYTRAN_AMT);
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
            assertThat(actual.signum()).isPositive();
        }

        @Test
        @DisplayName("an unsigned image re-encodes to the signed form, because every field is signed")
        void anUnsignedImageReEncodesToTheSignedForm() {
            // The unsigned trailing digit 7 decodes to the same value as the positive overpunch
            // for 7, so encoding emits the overpunched form. That is the correct direction: every
            // signed field in the estate is written overpunched, positive values included.
            BigDecimal fromUnsigned = ZonedDecimalCodec.decode("00000000127", 11, 2, DALYTRAN_AMT);
            assertThat(fromUnsigned).isEqualTo(new BigDecimal("1.27"));
            assertThat(ZonedDecimalCodec.encode(fromUnsigned, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000012G");
        }

        @Test
        @DisplayName("an all-zero image ending in the positive-zero character decodes to 0.00")
        void decodesPositiveZero() {
            BigDecimal actual = ZonedDecimalCodec.decode("0000000000{", 11, 2, DALYTRAN_AMT);
            assertThat(actual).isEqualTo(new BigDecimal("0.00"));
            assertThat(actual).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(actual.scale()).isEqualTo(2);
            assertThat(actual.signum()).isZero();
        }

        @Test
        @DisplayName("the positive-zero image round-trips byte for byte through the signed path")
        void positiveZeroRoundTripsByteForByte() {
            ZonedDecimalCodec.ZonedValue decoded =
                    ZonedDecimalCodec.decodeSigned("0000000000{", 11, 2, DALYTRAN_AMT);
            assertThat(decoded.value()).isEqualTo(new BigDecimal("0.00"));
            assertThat(decoded.value().scale()).isEqualTo(2);
            assertThat(decoded.negativeZero()).isFalse();
            assertThat(ZonedDecimalCodec.encodeSigned(decoded, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000000{");
        }

        @Test
        @DisplayName("the negative-zero image round-trips byte for byte and is never re-emitted as"
                + " positive zero")
        void negativeZeroRoundTripsByteForByte() {
            // Byte parity is the contract, and it holds for an all-zero field too. The legacy image
            // distinguishes the negative-zero character from the positive-zero character, so an
            // image read from a record must be re-emitted with the character it arrived with.
            // BigDecimal has one zero, so the sign travels beside the amount rather than inside it.
            ZonedDecimalCodec.ZonedValue decoded =
                    ZonedDecimalCodec.decodeSigned("0000000000}", 11, 2, DALYTRAN_AMT);
            assertThat(decoded.value()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(decoded.value()).isEqualTo(new BigDecimal("0.00"));
            assertThat(decoded.value().scale()).isEqualTo(2);
            assertThat(decoded.value().signum()).isZero();
            assertThat(decoded.negativeZero()).isTrue();

            assertThat(ZonedDecimalCodec.encodeSigned(decoded, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000000}")
                    .isNotEqualTo("0000000000{");
            assertThat(ZonedDecimalCodec.encodeSignedToBytes(decoded, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000000}".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the two zero images are distinguished by the signed path and only by it")
        void theTwoZeroImagesAreDistinguishedOnlyByTheSignedPath() {
            ZonedDecimalCodec.ZonedValue positive =
                    ZonedDecimalCodec.decodeSigned("0000000000{", 11, 2, DALYTRAN_AMT);
            ZonedDecimalCodec.ZonedValue negative =
                    ZonedDecimalCodec.decodeSigned("0000000000}", 11, 2, DALYTRAN_AMT);
            assertThat(positive.value()).isEqualTo(negative.value());
            assertThat(positive).isNotEqualTo(negative);

            // The value-only path returns the amount alone, so the two images become
            // indistinguishable after decoding and a zero encodes as the positive-zero character.
            // That is why a caller that may write the field back must use the signed path.
            BigDecimal fromPositive = ZonedDecimalCodec.decode("0000000000{", 11, 2, DALYTRAN_AMT);
            BigDecimal fromNegative = ZonedDecimalCodec.decode("0000000000}", 11, 2, DALYTRAN_AMT);
            assertThat(fromPositive).isEqualTo(fromNegative);
            assertThat(ZonedDecimalCodec.encode(fromNegative, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000000{");
        }

        @Test
        @DisplayName("the byte-array signed path agrees with the string signed path")
        void theByteArraySignedPathAgreesWithTheStringSignedPath() {
            byte[] record = "XX0000000000}YY".getBytes(StandardCharsets.US_ASCII);
            ZonedDecimalCodec.ZonedValue fromRecord =
                    ZonedDecimalCodec.decodeSigned(record, 2, 11, 2, DALYTRAN_AMT);
            assertThat(fromRecord.value()).isEqualTo(new BigDecimal("0.00"));
            assertThat(fromRecord.negativeZero()).isTrue();
            assertThat(fromRecord)
                    .isEqualTo(ZonedDecimalCodec.decodeSigned("0000000000}", 11, 2, DALYTRAN_AMT));
            assertThat(ZonedDecimalCodec.encodeSigned(fromRecord, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000000}");
        }

        @Test
        @DisplayName("a non-zero amount carries its own sign, so the negative-zero marker is refused")
        void aNonZeroAmountRefusesTheNegativeZeroMarker() {
            // The marker describes the one case the amount cannot express. Pairing it with a
            // non-zero amount would state two signs at once, so it is rejected rather than ignored.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ZonedDecimalCodec.ZonedValue(
                            new BigDecimal("-919.00"), true))
                    .withMessageContaining("negative-zero marker")
                    .withMessageNotContaining("919");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ZonedDecimalCodec.ZonedValue(new BigDecimal("0.01"), true))
                    .withMessageContaining("only to an amount of zero");
        }

        @Test
        @DisplayName("a decoded value with no amount is rejected rather than carried as null")
        void aDecodedValueWithNoAmountIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ZonedDecimalCodec.ZonedValue(null, false))
                    .withMessageContaining("must not be null");
        }

        @Test
        @DisplayName("a zero amount accepts either marker, and each selects its own final byte")
        void aZeroAmountAcceptsEitherMarker() {
            ZonedDecimalCodec.ZonedValue positive =
                    new ZonedDecimalCodec.ZonedValue(new BigDecimal("0.00"), false);
            ZonedDecimalCodec.ZonedValue negative =
                    new ZonedDecimalCodec.ZonedValue(new BigDecimal("0.00"), true);
            assertThat(ZonedDecimalCodec.encodeSigned(positive, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000000{");
            assertThat(ZonedDecimalCodec.encodeSigned(negative, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000000000}");
            assertThat(ZonedDecimalCodec.encodeSigned(negative, 12, 2, "ACCT-CURR-BAL"))
                    .isEqualTo("00000000000}");
            assertThat(ZonedDecimalCodec.encodeSigned(negative, 6, 2, "DIS-INT-RATE"))
                    .isEqualTo("00000}");
        }

        @Test
        @DisplayName("the marker changes nothing for an amount that already carries a sign")
        void theMarkerChangesNothingForASignedAmount() {
            ZonedDecimalCodec.ZonedValue negative =
                    ZonedDecimalCodec.decodeSigned("0000009190}", 11, 2, DALYTRAN_AMT);
            assertThat(negative.value()).isEqualTo(new BigDecimal("-919.00"));
            assertThat(negative.negativeZero()).isFalse();
            assertThat(ZonedDecimalCodec.encodeSigned(negative, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000009190}");

            ZonedDecimalCodec.ZonedValue positive =
                    ZonedDecimalCodec.decodeSigned("0000005047G", 11, 2, DALYTRAN_AMT);
            assertThat(positive.value()).isEqualTo(new BigDecimal("504.77"));
            assertThat(positive.negativeZero()).isFalse();
            assertThat(ZonedDecimalCodec.encodeSigned(positive, 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000005047G");
        }

        @Test
        @DisplayName("the signed encode entry points reject a missing value like the plain ones do")
        void theSignedEncodeEntryPointsRejectAMissingValue() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.encodeSigned(null, 11, 2, DALYTRAN_AMT))
                    .withMessageContaining("must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            ZonedDecimalCodec.encodeSignedToBytes(null, 11, 2, DALYTRAN_AMT))
                    .withMessageContaining("must not be null");
        }

        @Test
        @DisplayName("a trailing negative-zero character on a non-zero image is an ordinary amount")
        void aTrailingNegativeZeroCharacterOnANonZeroImageIsAnOrdinaryNegativeAmount() {
            // Read the negative-zero character narrowly. It means "negative, low-order digit
            // zero", so it marks a negative zero only when every other digit is zero too. Six of
            // the 300 shipped daily-transaction amounts end in it and none is a negative zero;
            // they are ordinary negative amounts whose cent digit happens to be zero. Treating
            // the character itself as a negative zero would flip the sign of those records.
            BigDecimal actual = ZonedDecimalCodec.decodeMonetary("0000009190}", 11, DALYTRAN_AMT);
            assertThat(actual).isEqualTo(new BigDecimal("-919.00"));
            assertThat(actual.signum()).isNegative();
            assertThat(ZonedDecimalCodec.encodeMonetary(actual, 11, DALYTRAN_AMT))
                    .isEqualTo("0000009190}");
        }
    }

    @Nested
    @DisplayName("Verified fixture images :: real bytes from the shipped sample data")
    class VerifiedFixtureImages {

        @Test
        @DisplayName("the first daily-transaction amount decodes to 504.77, not to 500.47")
        void decodesTheFirstDailyTransactionAmount() {
            // Derivation, worked by hand from the overpunch convention (decision D-01) rather than
            // copied, because the arithmetic is easy to invert:
            //
            //   image            0000005047G      eleven bytes
            //   leading ten      0000005047       plain digits, taken verbatim
            //   trailing byte    G                positive overpunch for the low-order digit 7
            //   unsigned digits  00000050477      the ten digits with that 7 appended
            //   at scale 2       50477 hundredths
            //   value            504.77
            //
            // The inverted answer, 500.47, comes from keeping the four visible digits 5047 and then
            // inserting a decimal point, which drops the overpunched digit entirely. Both are
            // asserted below so the distinction cannot quietly regress.
            BigDecimal expected = new BigDecimal(new BigInteger("50477"), 2);
            assertThat(expected).isEqualTo(new BigDecimal("504.77"));
            assertThat(expected).isNotEqualByComparingTo(new BigDecimal("500.47"));

            BigDecimal actual = ZonedDecimalCodec.decode("0000005047G", 11, 2, DALYTRAN_AMT);
            assertThat(actual).isEqualTo(expected);
            assertThat(actual.scale()).isEqualTo(2);
        }

        @ParameterizedTest(name = "[{index}] {0} decodes to {1}")
        @CsvSource({
            "0000005047G, 504.77",
            "0000009190}, -919.00",
            "0000000678H, 67.88",
            "0000000567P, -56.77",
            "0000005358Q, -535.88",
            "0000009456O, -945.66",
            "0000000349I, 34.99",
            "0000006032B, 603.22",
        })
        @DisplayName("every verified 11-byte daily-transaction amount decodes to its hand-derived value")
        void decodesEveryVerifiedElevenByteAmount(String image, String expected) {
            // Each row is an image lifted byte for byte out of the shipped 300-record fixture and
            // decoded independently: append the overpunched digit to the ten leading digits, read
            // the resulting eleven digits as hundredths, and negate when the trailing character
            // comes from the negative table.
            BigDecimal actual = ZonedDecimalCodec.decodeMonetary(image, 11, DALYTRAN_AMT);
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @ParameterizedTest(name = "[{index}] {0} decodes to {1}")
        @CsvSource({
            "00000001940{, 194.00",
            "00000020200{, 2020.00",
            "00000010200{, 1020.00",
            "00000000000{, 0.00",
        })
        @DisplayName("every verified 12-byte account money image decodes to its hand-derived value")
        void decodesEveryVerifiedTwelveByteAccountAmount(String image, String expected) {
            // Taken from the first account record: the current balance at offset 12, the credit
            // limit at 24, the cash credit limit at 36 and the current cycle credit at 78, each
            // twelve bytes wide.
            BigDecimal actual =
                    ZonedDecimalCodec.decodeMonetary(image, ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH,
                            ACCT_CURR_BAL);
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @ParameterizedTest(name = "[{index}] {0} decodes to {1}")
        @CsvSource({
            "00150{, 15.00",
            "00000{, 0.00",
        })
        @DisplayName("both verified 6-byte disclosure rate images decode to their hand-derived value")
        void decodesEveryVerifiedSixByteRate(String image, String expected) {
            // The shipped disclosure fixture holds three complete seventeen-row groups. The first
            // row of the first group carries the fifteen percent rate; the zero-rate group carries
            // the all-zero image, which is what makes the interest program's zero-rate branch
            // reachable from seed data alone.
            BigDecimal actual =
                    ZonedDecimalCodec.decodeMonetary(image, ZonedDecimalCodec.INTEREST_RATE_WIDTH,
                            DIS_INT_RATE);
            assertThat(actual).isEqualTo(new BigDecimal(expected));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the verified 11-byte category balance image decodes to zero at scale 2")
        void decodesTheVerifiedCategoryBalance() {
            BigDecimal actual = ZonedDecimalCodec.decodeMonetary("0000000000{",
                    ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH, TRAN_CAT_BAL);
            assertThat(actual).isEqualTo(new BigDecimal("0.00"));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the byte-range entry point reads the amount from its offset in a 350-byte record")
        void decodesFromAByteRangeInsideTheDailyTransactionRecord() {
            byte[] recordImage = recordImageWith(DAILY_TRANSACTION_RECORD_LENGTH,
                    DALYTRAN_AMT_OFFSET, "0000005047G");

            BigDecimal fromRange = ZonedDecimalCodec.decode(recordImage, DALYTRAN_AMT_OFFSET,
                    ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH, 2, DALYTRAN_AMT);
            assertThat(fromRange).isEqualTo(new BigDecimal("504.77"));
            assertThat(fromRange.scale()).isEqualTo(2);

            // All four decode entry points must agree on the same field. Each is independently
            // anchored to the hand-derived literal above, so agreement here is a cross-check on the
            // overloads rather than a substitute for an oracle.
            assertThat(ZonedDecimalCodec.decodeMonetary(recordImage, DALYTRAN_AMT_OFFSET,
                    ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH, DALYTRAN_AMT))
                    .isEqualTo(new BigDecimal("504.77"))
                    .isEqualTo(fromRange);
            assertThat(ZonedDecimalCodec.decode("0000005047G", 11, 2, DALYTRAN_AMT))
                    .isEqualTo(new BigDecimal("504.77"))
                    .isEqualTo(fromRange);
            assertThat(ZonedDecimalCodec.decodeMonetary("0000005047G", 11, DALYTRAN_AMT))
                    .isEqualTo(new BigDecimal("504.77"))
                    .isEqualTo(fromRange);
        }

        @Test
        @DisplayName("the byte-range entry point reads the account balance from the 300-byte record")
        void decodesFromAByteRangeInsideTheAccountRecord() {
            byte[] recordImage = recordImageWith(ACCOUNT_RECORD_LENGTH, ACCT_CURR_BAL_OFFSET,
                    "00000001940{");
            BigDecimal actual = ZonedDecimalCodec.decodeMonetary(recordImage, ACCT_CURR_BAL_OFFSET,
                    ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH, ACCT_CURR_BAL);
            assertThat(actual).isEqualTo(new BigDecimal("194.00"));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the byte-range entry point reads the rate from the 50-byte disclosure record")
        void decodesFromAByteRangeInsideTheDisclosureRecord() {
            byte[] recordImage = recordImageWith(DISCLOSURE_RECORD_LENGTH, DIS_INT_RATE_OFFSET,
                    "00150{");
            BigDecimal actual = ZonedDecimalCodec.decode(recordImage, DIS_INT_RATE_OFFSET,
                    ZonedDecimalCodec.INTEREST_RATE_WIDTH, 2, DIS_INT_RATE);
            assertThat(actual).isEqualTo(new BigDecimal("15.00"));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the byte-range entry point reads the balance from the 50-byte category record")
        void decodesFromAByteRangeInsideTheCategoryBalanceRecord() {
            byte[] recordImage = recordImageWith(CATEGORY_BALANCE_RECORD_LENGTH,
                    TRAN_CAT_BAL_OFFSET, "0000000000{");
            BigDecimal actual = ZonedDecimalCodec.decodeMonetary(recordImage, TRAN_CAT_BAL_OFFSET,
                    ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH, TRAN_CAT_BAL);
            assertThat(actual).isEqualTo(new BigDecimal("0.00"));
            assertThat(actual.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("decoding from a record image leaves the caller's bytes untouched")
        void decodingLeavesTheRecordImageUntouched() {
            byte[] recordImage = recordImageWith(DAILY_TRANSACTION_RECORD_LENGTH,
                    DALYTRAN_AMT_OFFSET, "0000005047G");
            byte[] before = recordImage.clone();

            ZonedDecimalCodec.decode(recordImage, DALYTRAN_AMT_OFFSET, 11, 2, DALYTRAN_AMT);

            assertThat(recordImage).containsExactly(before);
        }
    }

    @Nested
    @DisplayName("Encoding :: the exact inverse of decoding, at the declared byte width")
    class Encoding {

        @ParameterizedTest(name = "[{index}] {1} encodes to {0} at width {2}")
        @CsvSource({
            "0000005047G, 504.77, 11, DALYTRAN-AMT",
            "0000009190}, -919.00, 11, DALYTRAN-AMT",
            "0000000678H, 67.88, 11, DALYTRAN-AMT",
            "0000000567P, -56.77, 11, DALYTRAN-AMT",
            "0000005358Q, -535.88, 11, DALYTRAN-AMT",
            "0000009456O, -945.66, 11, DALYTRAN-AMT",
            "0000000349I, 34.99, 11, DALYTRAN-AMT",
            "0000006032B, 603.22, 11, DALYTRAN-AMT",
            "00000001940{, 194.00, 12, ACCT-CURR-BAL",
            "00000020200{, 2020.00, 12, ACCT-CREDIT-LIMIT",
            "00000010200{, 1020.00, 12, ACCT-CASH-CREDIT-LIMIT",
            "00000000000{, 0.00, 12, ACCT-CURR-CYC-CREDIT",
            "0000000000{, 0.00, 11, TRAN-CAT-BAL",
            "00150{, 15.00, 6, DIS-INT-RATE",
            "00000{, 0.00, 6, DIS-INT-RATE",
        })
        @DisplayName("every verified fixture image is reproduced byte for byte from its value")
        void reproducesEveryVerifiedFixtureImage(String image, String value, int width,
                String fieldName) {
            BigDecimal source = new BigDecimal(value);

            String encoded = ZonedDecimalCodec.encodeMonetary(source, width, fieldName);
            assertThat(encoded).isEqualTo(image);
            // Widths are measured in encoded bytes, never in characters, so that a multi-byte
            // character could never slip through a fixed-width field unnoticed.
            assertThat(encoded.getBytes(StandardCharsets.US_ASCII)).hasSize(width);

            byte[] encodedBytes = ZonedDecimalCodec.encodeMonetaryToBytes(source, width, fieldName);
            assertThat(encodedBytes).hasSize(width);
            assertThat(encodedBytes).containsExactly(image.getBytes(StandardCharsets.US_ASCII));

            // Decoding the freshly encoded image returns the value it started from, which closes
            // the loop in both directions on a literal that was derived by hand.
            assertThat(ZonedDecimalCodec.decodeMonetary(encoded, width, fieldName))
                    .isEqualTo(source);
        }

        @ParameterizedTest(name = "[{index}] {1} encodes to {0}")
        @CsvSource({
            "0000000012}, -1.20",
            "0000000012J, -1.21",
            "0000000012R, -1.29",
            "0000000000J, -0.01",
            "0000012542P, -1254.279",
        })
        @DisplayName("a negative value places the negative overpunch last and plain digits before it")
        void encodesANegativeValueWithTheNegativeOverpunchLast(String image, String value) {
            // The last row also proves the truncation happens before the image is laid out:
            // -1254.279 truncates toward zero to -1254.27, whose unscaled digits are 125427, whose
            // low-order digit 7 is written as the negative overpunch for 7, leaving 12542 as the
            // plain digits before it and five zeros of padding in front of those.
            String encoded = ZonedDecimalCodec.encodeMonetary(new BigDecimal(value), 11,
                    WS_TEMP_BAL);
            assertThat(encoded).isEqualTo(image);
            assertThat(encoded.getBytes(StandardCharsets.US_ASCII)).hasSize(11);
            // Every byte but the last is a plain ASCII digit.
            assertThat(encoded.substring(0, 10)).containsOnlyDigits();
        }

        @ParameterizedTest(name = "[{index}] zero at width {0} encodes to {1}")
        @CsvSource({
            "6, 00000{",
            "11, 0000000000{",
            "12, 00000000000{",
        })
        @DisplayName("zero renders its decimals at every width and never blanks the field")
        void encodesZeroAtEveryWidth(int width, String image) {
            // No field in the estate is declared BLANK WHEN ZERO, so a zero amount is written as
            // an all-zero image carrying the positive-zero overpunch, never as spaces.
            assertThat(ZonedDecimalCodec.encodeMonetary(BigDecimal.ZERO, width, DIS_INT_RATE))
                    .isEqualTo(image);
            assertThat(ZonedDecimalCodec.encodeMonetary(new BigDecimal("0.00"), width,
                    DIS_INT_RATE)).isEqualTo(image);
        }

        @Test
        @DisplayName("a value carrying more decimals than the field truncates before it is written")
        void truncatesBeforeWritingTheImage() {
            // 504.7799 truncated toward zero at scale 2 is 504.77, which is exactly the first
            // amount of the shipped fixture, so the image is the fixture image itself.
            assertThat(ZonedDecimalCodec.encode(new BigDecimal("504.7799"), 11, 2, DALYTRAN_AMT))
                    .isEqualTo("0000005047G");
            // And a value whose surplus digits would carry under a half policy still truncates.
            assertThat(ZonedDecimalCodec.encode(new BigDecimal("504.7799"), 11, 2, DALYTRAN_AMT))
                    .isNotEqualTo("0000005047H");
        }

        @Test
        @DisplayName("a value that exactly fills the rate field's digits encodes without complaint")
        void encodesAValueThatExactlyFillsTheField() {
            // Six digits at scale 2 is the whole capacity of the six-byte rate field: 999999
            // hundredths is 9999.99, whose low-order digit 9 becomes the positive overpunch for 9.
            assertThat(ZonedDecimalCodec.encodeMonetary(new BigDecimal("9999.99"), 6,
                    DIS_INT_RATE)).isEqualTo("99999I");
        }

        @Test
        @DisplayName("the general form honours a scale of zero, which an unsigned picture implies")
        void honoursAScaleOfZero() {
            // The transaction category code is four unsigned digits with no implied decimals, so
            // its real image is plain digits in every byte, the trailing byte included. Decoding
            // that form reads 42 at scale 0.
            assertThat(ZonedDecimalCodec.decode("0042", 4, 0, TRAN_CAT_CD))
                    .isEqualTo(new BigDecimal("42"));
            assertThat(ZonedDecimalCodec.decode("0042", 4, 0, TRAN_CAT_CD).scale()).isZero();

            // Encoding always emits the signed, overpunched form, even for a value that arrived
            // unsigned. That is the codec's documented direction rather than an oversight: 42.99
            // truncates at scale 0 to 42, whose low-order digit 2 becomes the positive overpunch
            // for 2, leaving 4 as the only plain digit in front of the two bytes of padding.
            assertThat(ZonedDecimalCodec.encode(new BigDecimal("42.99"), 4, 0, TRAN_CAT_CD))
                    .isEqualTo("004B");
            assertThat(ZonedDecimalCodec.encodeToBytes(new BigDecimal("42.99"), 4, 0, TRAN_CAT_CD))
                    .containsExactly("004B".getBytes(StandardCharsets.US_ASCII));

            // The two images therefore carry the same arithmetic value even though their bytes
            // differ in the final position, which is exactly what the unsigned form means.
            assertThat(ZonedDecimalCodec.decode("004B", 4, 0, TRAN_CAT_CD))
                    .isEqualTo(new BigDecimal("42"))
                    .isEqualTo(ZonedDecimalCodec.decode("0042", 4, 0, TRAN_CAT_CD));
        }

        @Test
        @DisplayName("each encode hands back a fresh array, so no caller can corrupt another's buffer")
        void handsBackAFreshArrayEachTime() {
            byte[] first = ZonedDecimalCodec.encodeMonetaryToBytes(new BigDecimal("504.77"), 11,
                    DALYTRAN_AMT);
            byte[] second = ZonedDecimalCodec.encodeMonetaryToBytes(new BigDecimal("504.77"), 11,
                    DALYTRAN_AMT);
            assertThat(first).isNotSameAs(second).containsExactly(second);

            first[0] = (byte) '9';
            assertThat(second[0]).isEqualTo((byte) '0');
            assertThat(new String(second, StandardCharsets.US_ASCII)).isEqualTo("0000005047G");
        }
    }

    @Nested
    @DisplayName("Failure contract :: nothing is padded, coerced or silently corrected")
    class FailureContract {

        @Test
        @DisplayName("a short image is rejected, naming the field, the expected width and the actual")
        void rejectsAnImageThatIsTooShort() {
            // Ten bytes offered where eleven are declared. A short or long fixed-width field has
            // no legacy antecedent, because a keyed or sequential record is fixed length by
            // construction, so this guards against a slicing defect on the Java side rather than
            // translating a legacy file status. That is why it is a plain argument rejection and
            // not one of the module's file-status exception types.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode("000000504G", 11, 2, DALYTRAN_AMT))
                    .withMessage("zoned decimal field 'DALYTRAN-AMT': expected exactly 11 encoded"
                            + " byte(s) but received 10");
        }

        @Test
        @DisplayName("a long image is rejected just as firmly and is never trimmed to fit")
        void rejectsAnImageThatIsTooLong() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            ZonedDecimalCodec.decodeMonetary("00000050477G", 11, DALYTRAN_AMT))
                    .withMessageContaining(DALYTRAN_AMT)
                    .withMessageContaining("expected exactly 11 encoded byte(s)")
                    .withMessageContaining("but received 12");
        }

        @Test
        @DisplayName("a space anywhere before the final byte is rejected at its own offset")
        void rejectsASpaceBeforeTheFinalByte() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode("000000 047G", 11, 2, DALYTRAN_AMT))
                    .withMessageContaining(DALYTRAN_AMT)
                    .withMessageContaining("expected an ASCII digit")
                    .withMessageContaining("at zero-based offset 6")
                    .withMessageEndingWith("0x20 (' ')");
        }

        @Test
        @DisplayName("an overpunch character before the final byte is rejected, being legal only last")
        void rejectsAnOverpunchCharacterBeforeTheFinalByte() {
            // The sign lives in the final byte and nowhere else, so a sign character found in an
            // interior position is malformed data rather than a second sign.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode("0000005O47G", 11, 2, DALYTRAN_AMT))
                    .withMessageContaining("at zero-based offset 7")
                    .withMessageEndingWith("0x4F ('O')");
        }

        @Test
        @DisplayName("a control byte is reported as hexadecimal only, so it cannot corrupt a log line")
        void reportsAControlByteAsHexadecimalOnly() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            ZonedDecimalCodec.decode("00\u000150{", 6, 2, DIS_INT_RATE))
                    .withMessageContaining(DIS_INT_RATE)
                    .withMessageContaining("at zero-based offset 2")
                    .withMessageEndingWith("0x01");
        }

        @Test
        @DisplayName("a byte above the printable range is also reported as hexadecimal only")
        void reportsAByteAboveThePrintableRangeAsHexadecimalOnly() {
            // The delete character sits inside the US-ASCII range, so it survives the character
            // check and reaches the digit check, but it is not printable, so the diagnostic must
            // still quote it as a bare hexadecimal value rather than embedding it in a log line.
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            ZonedDecimalCodec.decode("00\u007f50{", 6, 2, DIS_INT_RATE))
                    .withMessageContaining("at zero-based offset 2")
                    .withMessageEndingWith("0x7F");
        }

        @Test
        @DisplayName("a byte outside US-ASCII inside a record image is rejected by the digit check")
        void rejectsAByteOutsideAsciiInsideARecordImage() {
            // The character-range guard belongs to the String entry point, because only a String
            // can carry a code point that US-ASCII cannot express. A raw record image is already
            // bytes, so a byte with the high bit set simply fails the digit check instead, and is
            // reported unsigned so that the sign of the Java byte cannot leak into the message.
            byte[] recordImage = {'0', '0', (byte) 0xE9, '5', '0', '{'};
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(recordImage, 0, 6, 2, DIS_INT_RATE))
                    .withMessageContaining("expected an ASCII digit")
                    .withMessageContaining("at zero-based offset 2")
                    .withMessageEndingWith("0xE9");
        }

        @Test
        @DisplayName("the byte-range diagnostic reports the offset within the field, not the record")
        void reportsTheOffsetWithinTheFieldRatherThanTheRecord() {
            // The malformed byte sits at record offset 138, but the field starts at 132, so the
            // diagnostic must read 6. A message that quoted 138 would send a reader hunting in the
            // wrong field of the record.
            byte[] recordImage = recordImageWith(DAILY_TRANSACTION_RECORD_LENGTH,
                    DALYTRAN_AMT_OFFSET, "000000 047G");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(recordImage, DALYTRAN_AMT_OFFSET, 11,
                            2, DALYTRAN_AMT))
                    .withMessageContaining("at zero-based offset 6")
                    .withMessageNotContaining("offset 138");
        }

        @ParameterizedTest(name = "[{index}] {0} ends in {2}, which is not a sign character")
        @CsvSource({
            "0000005047*, 0x2A, *",
            "0000005047g, 0x67, g",
            "0000005047+, 0x2B, +",
            "0000005047., 0x2E, .",
        })
        @DisplayName("a final byte that is neither a digit nor an overpunch character is rejected")
        void rejectsAnInvalidFinalByte(String image, String expectedHex, String expectedCharacter) {
            // The overpunch table is uppercase, so a lowercase letter is not a sign character and
            // must not be folded into one. The expected byte description is assembled here from a
            // hexadecimal literal and the character itself, so that the parenthesised form the
            // diagnostic uses is spelled out in the test rather than carried through the data
            // table, where the quote characters would collide with the column syntax.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(image, 11, 2, DALYTRAN_AMT))
                    .withMessageContaining("expected an ASCII digit or an overpunched sign"
                            + " character")
                    .withMessageContaining("at zero-based offset 10")
                    .withMessageEndingWith(expectedHex + " ('" + expectedCharacter + "')");
        }

        @Test
        @DisplayName("a trailing space is rejected and is never read as an implied positive zero")
        void rejectsATrailingSpace() {
            // Written as a plain literal rather than as a table row, because a trailing space in a
            // data column would be trimmed away before the codec ever saw it and the case would
            // silently become a width mismatch instead of the sign-character rejection it is meant
            // to be. A space is not an overpunch character, so a field padded with one is malformed
            // rather than an unsigned zero.
            String imageWithTrailingSpace = "0000005047 ";
            assertThat(imageWithTrailingSpace.getBytes(StandardCharsets.US_ASCII)).hasSize(11);
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            ZonedDecimalCodec.decode(imageWithTrailingSpace, 11, 2, DALYTRAN_AMT))
                    .withMessageContaining("expected an ASCII digit or an overpunched sign"
                            + " character")
                    .withMessageContaining("at zero-based offset 10")
                    .withMessageEndingWith("0x20 (' ')");
        }

        @Test
        @DisplayName("a character US-ASCII cannot represent is rejected rather than transcoded")
        void rejectsACharacterOutsideTheAsciiRange() {
            // Encoding it would substitute a replacement byte and the caller would never learn the
            // data had been altered, so the image is refused instead.
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            ZonedDecimalCodec.decode("000000504\u00e9G", 11, 2, DALYTRAN_AMT))
                    .withMessageContaining(DALYTRAN_AMT)
                    .withMessageContaining("character U+00E9")
                    .withMessageContaining("at zero-based character index 9")
                    .withMessageContaining("cannot be represented in US-ASCII");
        }

        @Test
        @DisplayName("a null image is refused on the same code path as malformed data")
        void refusesANullImage() {
            // Every rejection path, null included, raises the same argument rejection so that a
            // caller has exactly one exception type to handle (decision D-11), and the message names
            // the field without echoing the image (decision D-16).
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(null, 11, 2, DALYTRAN_AMT))
                    .withMessage("zoned decimal field 'DALYTRAN-AMT': the field image must not be"
                            + " null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decodeMonetary(null, 11, DALYTRAN_AMT))
                    .withMessageContaining("the field image must not be null");
        }

        @Test
        @DisplayName("a null record image is refused before any slice is attempted")
        void refusesANullRecordImage() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(null, 0, 11, 2, DALYTRAN_AMT))
                    .withMessageContaining(DALYTRAN_AMT)
                    .withMessageContaining("the record image must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decodeMonetary(null, 0, 11, DALYTRAN_AMT))
                    .withMessageContaining("the record image must not be null");
        }

        @Test
        @DisplayName("a null value to encode is refused by every encoding entry point")
        void refusesANullValueToEncode() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.encodeToBytes(null, 11, 2, DALYTRAN_AMT))
                    .withMessage("zoned decimal field 'DALYTRAN-AMT': the value to encode must not"
                            + " be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.encode(null, 11, 2, DALYTRAN_AMT))
                    .withMessageContaining("the value to encode must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.encodeMonetary(null, 11, DALYTRAN_AMT))
                    .withMessageContaining("the value to encode must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            ZonedDecimalCodec.encodeMonetaryToBytes(null, 11, DALYTRAN_AMT))
                    .withMessageContaining("the value to encode must not be null");
        }

        @Test
        @DisplayName("a null value to rescale is refused by both scaling entry points")
        void refusesANullValueToRescale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.toScale(null, 2))
                    .withMessage("the value to rescale must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.toMonetaryScale(null))
                    .withMessage("the value to rescale must not be null");
        }

        @ParameterizedTest(name = "[{index}] width {0} is refused")
        @CsvSource({"0", "-1"})
        @DisplayName("a width below one encoded byte is refused, since no field is narrower")
        void refusesAWidthBelowOneByte(int width) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode("0", width, 0, DALYTRAN_AMT))
                    .withMessageContaining(DALYTRAN_AMT)
                    .withMessageContaining("the field width must be at least one encoded byte")
                    .withMessageEndingWith("but was " + width);
        }

        @ParameterizedTest(name = "[{index}] scale {0} is refused for a two-byte field")
        @CsvSource({"-1", "3"})
        @DisplayName("a scale outside zero through the field width is refused")
        void refusesAScaleOutsideTheFieldWidth(int scale) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode("00", 2, scale, DALYTRAN_AMT))
                    .withMessageContaining("the scale must be between 0 and the field width 2"
                            + " inclusive")
                    .withMessageEndingWith("but was " + scale);
        }

        @Test
        @DisplayName("a negative target scale is refused by the rescaling entry point")
        void refusesANegativeTargetScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.toScale(BigDecimal.ONE, -1))
                    .withMessage("the target scale must not be negative but was -1");
        }

        @Test
        @DisplayName("a slice that falls outside the record image is refused, naming both extents")
        void refusesASliceThatFallsOutsideTheRecordImage() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(new byte[10], 0, 11, 2,
                            DALYTRAN_AMT))
                    .withMessage("zoned decimal field 'DALYTRAN-AMT': a 11-byte field at zero-based"
                            + " offset 0 falls outside a record image of 10 byte(s)");
        }

        @Test
        @DisplayName("a negative offset is refused rather than wrapped or clamped")
        void refusesANegativeOffset() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(new byte[20], -1, 11, 2,
                            DALYTRAN_AMT))
                    .withMessageContaining("at zero-based offset -1")
                    .withMessageContaining("falls outside a record image of 20 byte(s)");
        }

        @Test
        @DisplayName("a slice that overruns the end of a real record image is refused")
        void refusesASliceThatOverrunsTheEndOfTheRecord() {
            byte[] recordImage = recordImageWith(DAILY_TRANSACTION_RECORD_LENGTH,
                    DALYTRAN_AMT_OFFSET, "0000005047G");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode(recordImage, 340, 11, 2,
                            DALYTRAN_AMT))
                    .withMessageContaining("at zero-based offset 340")
                    .withMessageContaining("falls outside a record image of 350 byte(s)");
        }

        @Test
        @DisplayName("a value too large for the field is refused, never truncated on the left")
        void refusesAValueTooLargeForTheField() {
            // Silently dropping a high-order digit would corrupt the amount rather than fail, so
            // the rejection is mandatory. 99999.99 needs seven digits at scale 2 and the rate field
            // holds six. The diagnostic omits the value itself so a rejection cannot leak account
            // data into a log.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.encodeMonetary(new BigDecimal("99999.99"),
                            6, DIS_INT_RATE))
                    .withMessage("zoned decimal field 'DIS-INT-RATE': the value needs 7 digit(s) at"
                            + " scale 2 but the field holds only 6")
                    .withMessageNotContaining("99999");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.encodeMonetaryToBytes(
                            new BigDecimal("-99999.99"), 6, DIS_INT_RATE))
                    .withMessageContaining("but the field holds only 6");
        }

        @ParameterizedTest(name = "[{index}] a field name of [{0}] degrades to the substitute label")
        @CsvSource(value = {"NULL", "''", "'   '"}, nullValues = "NULL")
        @DisplayName("a missing field name degrades to a substitute label rather than masking the fault")
        void degradesAMissingFieldNameToASubstituteLabel(String fieldName) {
            // Building a diagnostic must never itself fail, or the real defect would be hidden
            // behind a secondary failure.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ZonedDecimalCodec.decode("0000005047*", 11, 2, fieldName))
                    .withMessageStartingWith("zoned decimal field (unnamed field):")
                    .withMessageEndingWith("0x2A ('*')");
        }
    }

    @Nested
    @DisplayName("Order sensitivity :: truncation makes arithmetic non-associative")
    class OrderSensitivity {

        @Test
        @DisplayName("the interest expression must multiply before it divides, or a cent is lost")
        void theInterestExpressionMustMultiplyBeforeItDivides() {
            // The accrual multiplies the category balance by the disclosure rate first and divides
            // by 1200 second, storing into a two-decimal field. Rearranging it to divide the rate
            // first is algebraically identical in exact arithmetic, but it moves the truncation
            // point, and truncation is not associative. The two orderings are worked by hand:
            //
            //   faithful     100.00 x 1.50 = 150.0000 ; 150.0000 / 1200 = 0.1250 exactly ;
            //                truncated at scale 2 -> 0.12
            //   rearranged   1.50 / 1200 = 0.00125 exactly ; truncated at scale 2 -> 0.00 ;
            //                100.00 x 0.00 = 0.0000 ; truncated -> 0.00
            //
            // Both divisions are exact, so nothing but the placement of the truncation differs,
            // and the results are twelve cents apart. Neither expectation is asked of the codec.
            BigDecimal categoryBalance = new BigDecimal("100.00");
            BigDecimal disclosureRate = new BigDecimal("1.50");
            BigDecimal monthsTimesPercent = new BigDecimal("1200");

            BigDecimal faithful = ZonedDecimalCodec.toMonetaryScale(
                    categoryBalance.multiply(disclosureRate).divide(monthsTimesPercent));
            BigDecimal rearranged = ZonedDecimalCodec.toMonetaryScale(categoryBalance.multiply(
                    ZonedDecimalCodec.toMonetaryScale(
                            disclosureRate.divide(monthsTimesPercent))));

            assertThat(faithful).isEqualTo(new BigDecimal("0.12"));
            assertThat(faithful.scale()).isEqualTo(2);
            assertThat(rearranged).isEqualTo(new BigDecimal("0.00"));
            assertThat(faithful).isNotEqualByComparingTo(rearranged);

            // The faithful figure is what the legacy program stored, so it is also what the field
            // image must carry: twelve hundredths, low-order digit 2, positive overpunch B.
            assertThat(ZonedDecimalCodec.encodeMonetary(faithful, 11, WS_MONTHLY_INT))
                    .isEqualTo("0000000001B");
        }

        @Test
        @DisplayName("the overlimit basis is evaluated strictly left to right into a V99 field")
        void theOverlimitBasisIsEvaluatedStrictlyLeftToRight() {
            // Cycle credit, minus cycle debit, plus the daily transaction amount, in that order,
            // stored into a two-decimal working field. Worked by hand:
            //   1000.00 - 250.50 = 749.50 ; 749.50 + 504.77 = 1254.27
            // The daily amount is the first amount of the shipped fixture, so the whole basis is
            // anchored to real bytes. Reordering the operands would change which transactions the
            // posting program rejects for exceeding the credit limit.
            BigDecimal cycleCredit = new BigDecimal("1000.00");
            BigDecimal cycleDebit = new BigDecimal("250.50");
            BigDecimal dailyAmount = ZonedDecimalCodec.decodeMonetary("0000005047G", 11,
                    DALYTRAN_AMT);
            assertThat(dailyAmount).isEqualTo(new BigDecimal("504.77"));

            BigDecimal basis = ZonedDecimalCodec.toMonetaryScale(
                    cycleCredit.subtract(cycleDebit).add(dailyAmount));

            assertThat(basis).isEqualTo(new BigDecimal("1254.27"));
            assertThat(basis.scale()).isEqualTo(2);
            assertThat(ZonedDecimalCodec.encodeMonetary(basis, 11, WS_TEMP_BAL))
                    .isEqualTo("0000012542G");
        }

        @Test
        @DisplayName("truncating an intermediate result is not the same as truncating once at the store")
        void truncatingAnIntermediateIsNotTheSameAsTruncatingOnceAtTheStore() {
            // Three amounts each carrying a third of a cent. Summed exactly and truncated once,
            // the fractions accumulate to a whole cent: 0.334 + 0.333 + 0.334 = 1.001, truncated
            // to 1.00. Truncated individually first, each loses its fraction: 0.33 + 0.33 + 0.33 =
            // 0.99. The two answers differ by a cent, which is why the codec is the only place a
            // scale may be applied and why no caller may truncate on its own initiative.
            BigDecimal first = new BigDecimal("0.334");
            BigDecimal second = new BigDecimal("0.333");
            BigDecimal third = new BigDecimal("0.334");

            BigDecimal truncatedOnce =
                    ZonedDecimalCodec.toMonetaryScale(first.add(second).add(third));
            BigDecimal truncatedEachTime = ZonedDecimalCodec.toMonetaryScale(
                    ZonedDecimalCodec.toMonetaryScale(first)
                            .add(ZonedDecimalCodec.toMonetaryScale(second))
                            .add(ZonedDecimalCodec.toMonetaryScale(third)));

            assertThat(truncatedOnce).isEqualTo(new BigDecimal("1.00"));
            assertThat(truncatedEachTime).isEqualTo(new BigDecimal("0.99"));
            assertThat(truncatedOnce).isNotEqualByComparingTo(truncatedEachTime);
        }
    }
}
