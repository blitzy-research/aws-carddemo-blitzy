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
package com.carddemo.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.carddemo.domain.id.DisclosureGroupId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link DisclosureGroup}, the 50-byte disclosure-group interest-rate row, together
 * with its 16-byte three-part composite key {@link DisclosureGroupId}.
 *
 * <p><strong>Provenance.</strong> The behaviour pinned here was derived from the legacy CardDemo
 * mainframe estate at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19). Those identifiers are recorded
 * here as documentation only. No test below asserts a release stamp on a source member, because the
 * stamp is not carried uniformly across the estate.
 *
 * <p><strong>What this suite is for.</strong> Two facts about this record make it the most
 * parity-critical row in the domain package, and both fail silently rather than loudly:
 *
 * <ol>
 *   <li>The rate occupies <em>six</em> bytes - four integer digits and two decimal digits - and maps
 *       to the only precision-six exact numeric column in the schema. Every other amount in the
 *       estate is eleven or twelve bytes wide. A reader that assumed eleven bytes would run past the
 *       rate into the trailing filler, parse without complaint, and return a value wrong by a factor
 *       of one hundred thousand. Only an explicit width assertion catches that, so this suite makes
 *       the width an assertion rather than an assumption.</li>
 *   <li>The account group identifier is space-padded to its full ten characters, and that padding is
 *       part of the key rather than incidental whitespace. The interest-accrual program's
 *       default-group fallback depends on it. Trimming anywhere - in a constructor, an accessor,
 *       {@code equals} or {@code hashCode} - would break the fallback without producing an
 *       error.</li>
 * </ol>
 *
 * <p><strong>Independent oracle.</strong> Every expected value below was hand-derived from the
 * disclosure-group copybook {@code app/cpy/CVTRA02Y.cpy} (record length 50), from the cluster
 * definition {@code app/jcl/DISCGRP.jcl} ({@code KEYS(16 0)}, {@code RECORDSIZE(50 50)}), from the
 * seeded reference file {@code app/data/ASCII/discgrp.txt} (2,601 bytes = 51 records at 50 bytes plus
 * one line terminator each), and from the file-section layout of the interest program
 * {@code app/cbl/CBACT04C.cbl}. No production method is ever called to produce its own expected
 * value, no output is snapshotted, and no assertion has the shape {@code f(x) == f(x)}.
 *
 * <p><strong>Deliberately out of scope here.</strong> Nothing in this file decodes a fixed-width
 * record image, computes interest, or inspects a column name, length or nullability. Zoned-decimal
 * decoding and scale truncation belong to the codec in the utility layer; the accrual arithmetic and
 * the status-23 fallback belong to the interest-calculation service; and the object-relational
 * mapping is verified in the integration tier, where schema validation against a real PostgreSQL
 * instance fails start-up on any mismatch - including the identifier-class-to-entity match, which is
 * resolved by field name and field type. Importing any of those collaborators would make another
 * class this suite's oracle, so none is imported.
 *
 * <p><strong>Two divergences from the written contract summary, resolved in favour of the
 * production classes as their signatures are authoritative.</strong>
 *
 * <ul>
 *   <li>{@code DisclosureGroupId}'s no-argument constructor is {@code protected}, not {@code public},
 *       and the key class lives in a different package from this test. It is therefore not reachable
 *       by a direct call from here. See {@link ProtectedKeyConstructorProbe} for the zero-reflection
 *       technique used instead.</li>
 *   <li>Both classes declare {@code toString()}, although the summary allowed it to be absent.
 *       Nothing here asserts its format; {@link DiagnosticRendering} makes only the one behavioural
 *       claim that matters to this record, namely that no trimming leaks into it.</li>
 * </ul>
 *
 * <p>This is a pure in-process unit test. It starts no container, opens no socket, reads no file,
 * builds no application context and uses no reflection.
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 */
@DisplayName("DisclosureGroup - 50-byte disclosure-group rate row with a 16-byte three-part key")
class DisclosureGroupTest {

    // Hand-derived layout constants. Sources: the disclosure-group copybook (record
    // length 50), the cluster definition (KEYS(16 0), RECORDSIZE(50 50)) and the
    // interest program's file section, which agree three ways on component order and
    // width. These are immutable primitives and interned string literals only - this
    // suite holds no cache and no mutable static state.

    /** Account group identifier: key part 1, 10 bytes at offset 0. */
    private static final int GROUP_ID_WIDTH = 10;

    /** Transaction type code: key part 2, 2 bytes at offset 10. */
    private static final int TRAN_TYPE_WIDTH = 2;

    /** Transaction category code: key part 3, 4 bytes at offset 12. */
    private static final int TRAN_CAT_WIDTH = 4;

    /** Integer digits declared by the signed rate field. */
    private static final int RATE_INTEGER_DIGITS = 4;

    /** Decimal digits declared by the signed rate field, and therefore the stored scale. */
    private static final int RATE_DECIMAL_DIGITS = 2;

    /** Declared key length: the cluster definition states 16 beginning at offset 0. */
    private static final int DECLARED_KEY_WIDTH = 16;

    /** Declared record length: the copybook header and the cluster definition both state 50. */
    private static final int DECLARED_RECORD_WIDTH = 50;

    /** Width of the trailing filler at offset 22, which is deliberately not persisted. */
    private static final int FILLER_WIDTH = 28;

    /**
     * Rate width that a reader must never assume: the wider signed nine-integer-digit amount used
     * by transaction and balance fields elsewhere in the estate.
     */
    private static final int WIDER_AMOUNT_WIDTH_NINE_DIGITS = 11;

    /**
     * Rate width that a reader must never assume: the wider signed ten-integer-digit amount used by
     * the account balance and limit fields elsewhere in the estate.
     */
    private static final int WIDER_AMOUNT_WIDTH_TEN_DIGITS = 12;

    /** Key width of the transaction-category-balance record - a different, longer key. */
    private static final int SIBLING_BALANCE_KEY_WIDTH = 17;

    /** Key width of the transaction-category record - a different, shorter key. */
    private static final int SIBLING_CATEGORY_KEY_WIDTH = 6;

    /** Records in the seeded reference file: 2,601 bytes divided by 50 bytes plus a terminator. */
    private static final int SEEDED_RECORD_COUNT = 51;

    /** Distinct account group identifiers in the seeded reference file. */
    private static final int SEEDED_GROUP_COUNT = 3;

    /** Rows carried by each of the three seeded groups. */
    private static final int SEEDED_ROWS_PER_GROUP = 17;

    // Seeded fixture lexemes, transcribed verbatim from the reference file. The two
    // padded identifiers carry exactly three trailing spaces each, bringing them to the
    // full ten characters. They are written out in full rather than assembled, so that
    // the padding is visible in the source and cannot drift.

    /** First seeded group identifier: ten characters with no padding required. */
    private static final String GROUP_ID_A = "A000000000";

    /** Second seeded group identifier at its true ten-character width. */
    private static final String GROUP_ID_DEFAULT_PADDED = "DEFAULT   ";

    /** The seven-character literal a legacy alphanumeric move starts from. Never a valid key. */
    private static final String GROUP_ID_DEFAULT_UNPADDED = "DEFAULT";

    /** Third seeded group identifier at its true ten-character width. */
    private static final String GROUP_ID_ZEROAPR_PADDED = "ZEROAPR   ";

    /** The seven-character shortened form of the third identifier. Never a valid key. */
    private static final String GROUP_ID_ZEROAPR_UNPADDED = "ZEROAPR";

    /** Transaction type code carried by the first row of every seeded group. */
    private static final String TRAN_TYPE = "01";

    /** Transaction category code carried by the first row of every seeded group. */
    private static final String TRAN_CAT = "0001";

    /** Second seeded transaction category code, used where a differing component is needed. */
    private static final String TRAN_CAT_OTHER = "0002";

    /** The category code stripped of its leading zeros. Never a valid key component. */
    private static final String TRAN_CAT_WITHOUT_LEADING_ZEROS = "1";

    /** Second transaction type code, used where a differing component is needed. */
    private static final String TRAN_TYPE_OTHER = "02";

    // Hand-derived rate values. The seeded rate images are cited as evidence only; this
    // suite never decodes one. Under the overpunch convention the trailing byte carries
    // both the low-order digit and the sign, with '{' encoding a positive zero, so the
    // image 00150{ is the unsigned digit string 001500 read at scale two, which is
    // 15.00, and 00000{ is 000000 at scale two, which is 0.00. Every BigDecimal below
    // is built from a string literal. No approximate binary numeric type - primitive or
    // boxed - appears anywhere in this file, because such a type cannot reproduce the
    // legacy decimal representation exactly.

    /** Rate of the first seeded row, hand-derived from the image {@code 00150{}. */
    private static final String RATE_FIFTEEN = "15.00";

    /** The same magnitude written without a scale, used to prove scale identity matters. */
    private static final String RATE_FIFTEEN_UNSCALED = "15";

    /** Rate of every row in the third seeded group, hand-derived from the image {@code 00000{}. */
    private static final String RATE_ZERO = "0.00";

    /** A negative rate. The field is signed, so the sign must survive even though no row uses one. */
    private static final String RATE_NEGATIVE_FIFTEEN = "-15.00";

    /** Second seeded rate, hand-derived from the image {@code 00250{}, used as a differing value. */
    private static final String RATE_TWENTY_FIVE = "25.00";

    /** A one-decimal value, used to prove the entity does not widen a scale. */
    private static final String RATE_SCALE_ONE = "1.5";

    /** A three-decimal value, used to prove the entity neither truncates nor rounds. */
    private static final String RATE_SCALE_THREE = "2.999";

    /** What a truncating store to scale two would have produced from {@link #RATE_SCALE_THREE}. */
    private static final String RATE_SCALE_THREE_IF_TRUNCATED = "2.99";

    /** What a rounding store to scale two would have produced from {@link #RATE_SCALE_THREE}. */
    private static final String RATE_SCALE_THREE_IF_ROUNDED = "3.00";

    /**
     * Returns the encoded byte width of a fixed-width value.
     *
     * <p>Width is measured in encoded bytes rather than by the string's own length, because the
     * legacy contract is a byte contract: the record image, the declared key length and the declared
     * record length are all counts of bytes. For values drawn from this single-byte fixed-width
     * record the two happen to agree, and measuring bytes states the intent rather than relying on
     * that coincidence.
     *
     * @param value the fixed-width value to measure; never trimmed, stripped or normalised
     * @return the number of bytes {@code value} occupies in the record image
     */
    private static int encodedWidthOf(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Minimal subclass of {@link DisclosureGroupId} that exists solely to reach that class's
     * {@code protected} no-argument constructor.
     *
     * <p><strong>Why this exists.</strong> The persistence provider requires an identifier class to
     * have a no-argument constructor, which is why the key is a plain class rather than a record. The
     * production class declares that constructor {@code protected}, and it lives in a different
     * package from this test, so {@code new DisclosureGroupId()} does not compile here. A protected
     * constructor is, however, reachable from a subclass body in any package through an explicit
     * superclass constructor invocation. Declaring this subclass therefore proves at
     * <em>compile time</em> that the no-argument constructor exists, and instantiating it proves at
     * <em>run time</em> that it leaves every component unset.
     *
     * <p><strong>This is inheritance, not reflection.</strong> No member is looked up by name, no
     * accessibility is overridden, and no member of the runtime reflection API is referenced
     * anywhere in this file. The module's audit requirement of zero reflection is preserved.
     *
     * <p>The superclass is serializable, so this subclass declares its own serialization identity;
     * omitting it would raise a lint warning, and the build promotes warnings to errors.
     */
    private static final class ProtectedKeyConstructorProbe extends DisclosureGroupId {

        /** Serialization identity of the probe itself. Never persisted or transmitted. */
        private static final long serialVersionUID = 1L;

        /** Invokes the superclass's {@code protected} no-argument constructor. */
        ProtectedKeyConstructorProbe() {
            super();
        }
    }

    /** Foreign type used to prove that equality rejects an unrelated class rather than throwing. */
    private static final String FOREIGN_KEY_RENDERING = "DEFAULT   010001";

    @Nested
    @DisplayName("Record layout geometry")
    class RecordLayoutGeometry {

        @Test
        @DisplayName("the three key components are exactly 10, 2 and 4 bytes wide, as the copybook "
                + "declares them: a 10-byte group identifier, a 2-byte transaction type and a "
                + "4-byte transaction category")
        void keyComponentWidthsAreTenTwoAndFour() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(encodedWidthOf(row.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(encodedWidthOf(row.getDisTranTypeCd())).isEqualTo(TRAN_TYPE_WIDTH);
            assertThat(encodedWidthOf(row.getDisTranCatCd())).isEqualTo(TRAN_CAT_WIDTH);

            assertThat(GROUP_ID_WIDTH).isEqualTo(10);
            assertThat(TRAN_TYPE_WIDTH).isEqualTo(2);
            assertThat(TRAN_CAT_WIDTH).isEqualTo(4);
        }

        @Test
        @DisplayName("the three key components sum to the 16-byte key the cluster definition "
                + "declares with KEYS(16 0), which is neither the 17-byte category-balance key nor "
                + "the 6-byte category key")
        void keyComponentsSumToSixteenAndDifferFromBothSiblingKeys() {
            assertThat(GROUP_ID_WIDTH + TRAN_TYPE_WIDTH + TRAN_CAT_WIDTH)
                    .isEqualTo(DECLARED_KEY_WIDTH);
            assertThat(DECLARED_KEY_WIDTH).isEqualTo(16);

            assertThat(DECLARED_KEY_WIDTH).isNotEqualTo(SIBLING_BALANCE_KEY_WIDTH);
            assertThat(DECLARED_KEY_WIDTH).isNotEqualTo(SIBLING_CATEGORY_KEY_WIDTH);
        }

        @Test
        @DisplayName("the rate is 6 bytes - 4 integer digits plus 2 decimal digits - making it the "
                + "only precision-6 column in the schema; an 11-byte read would silently absorb "
                + "five bytes of trailing filler and be wrong by a factor of one hundred thousand")
        void rateIsSixBytesWideAndNeitherElevenNorTwelve() {
            assertThat(RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS).isEqualTo(6);

            assertThat(RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS)
                    .isNotEqualTo(WIDER_AMOUNT_WIDTH_NINE_DIGITS);
            assertThat(RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS)
                    .isNotEqualTo(WIDER_AMOUNT_WIDTH_TEN_DIGITS);

            assertThat(WIDER_AMOUNT_WIDTH_NINE_DIGITS).isEqualTo(11);
            assertThat(WIDER_AMOUNT_WIDTH_TEN_DIGITS).isEqualTo(12);
        }

        @Test
        @DisplayName("the four mapped fields sum to 22 bytes, and the remaining 28 bytes of the "
                + "50-byte record are the unmapped trailing filler at offset 22")
        void mappedWidthsSumToTwentyTwoWithinAFiftyByteRecord() {
            int mapped = GROUP_ID_WIDTH
                    + TRAN_TYPE_WIDTH
                    + TRAN_CAT_WIDTH
                    + RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS;

            assertThat(mapped).isEqualTo(22);
            assertThat(mapped + FILLER_WIDTH).isEqualTo(DECLARED_RECORD_WIDTH);
            assertThat(DECLARED_RECORD_WIDTH - mapped).isEqualTo(FILLER_WIDTH);
            assertThat(FILLER_WIDTH).isEqualTo(28);
        }

        @Test
        @DisplayName("the key is the leading substring of the record: KEYS(16 0) places it at "
                + "offset 0, so the rate begins at offset 16 and the filler at offset 22")
        void keyOccupiesTheLeadingSixteenBytesSoTheRateBeginsAtOffsetSixteen() {
            int groupIdOffset = 0;
            int tranTypeOffset = groupIdOffset + GROUP_ID_WIDTH;
            int tranCatOffset = tranTypeOffset + TRAN_TYPE_WIDTH;
            int rateOffset = tranCatOffset + TRAN_CAT_WIDTH;
            int fillerOffset = rateOffset + RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS;

            assertThat(groupIdOffset).isZero();
            assertThat(tranTypeOffset).isEqualTo(10);
            assertThat(tranCatOffset).isEqualTo(12);
            assertThat(rateOffset).isEqualTo(16);
            assertThat(rateOffset).isEqualTo(DECLARED_KEY_WIDTH);
            assertThat(fillerOffset).isEqualTo(22);
        }
    }

    @Nested
    @DisplayName("Entity construction and field access")
    class EntityConstructionAndAccess {

        @Test
        @DisplayName("the all-argument constructor takes the three key components in copybook order "
                + "followed by the rate, and stores all four verbatim: the first seeded row is group "
                + "A000000000, type 01, category 0001 at a rate of 15.00")
        void allArgumentConstructorRoundTripsAllFourProperties() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(row.getDisAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(row.getDisTranTypeCd()).isEqualTo(TRAN_TYPE);
            assertThat(row.getDisTranCatCd()).isEqualTo(TRAN_CAT);
            assertThat(row.getDisIntRate()).isEqualTo(new BigDecimal(RATE_FIFTEEN));
        }

        @Test
        @DisplayName("all four setters store their argument verbatim, performing no trimming, "
                + "padding, case folding, validation or rescaling of any kind")
        void allFourSettersRoundTripVerbatim() {
            DisclosureGroup row = new DisclosureGroup();

            row.setDisAcctGroupId(GROUP_ID_DEFAULT_PADDED);
            row.setDisTranTypeCd(TRAN_TYPE);
            row.setDisTranCatCd(TRAN_CAT);
            row.setDisIntRate(new BigDecimal(RATE_ZERO));

            assertThat(row.getDisAcctGroupId()).isEqualTo(GROUP_ID_DEFAULT_PADDED);
            assertThat(encodedWidthOf(row.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(row.getDisTranTypeCd()).isEqualTo(TRAN_TYPE);
            assertThat(row.getDisTranCatCd()).isEqualTo(TRAN_CAT);
            assertThat(row.getDisIntRate()).isEqualTo(new BigDecimal(RATE_ZERO));
        }

        @Test
        @DisplayName("each setter replaces only its own field, so overwriting the rate leaves the "
                + "three key components of an already-populated row untouched")
        void replacingTheRateLeavesTheKeyComponentsUntouched() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_ZEROAPR_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_ZERO));

            row.setDisIntRate(new BigDecimal(RATE_TWENTY_FIVE));

            assertThat(row.getDisAcctGroupId()).isEqualTo(GROUP_ID_ZEROAPR_PADDED);
            assertThat(row.getDisTranTypeCd()).isEqualTo(TRAN_TYPE);
            assertThat(row.getDisTranCatCd()).isEqualTo(TRAN_CAT);
            assertThat(row.getDisIntRate()).isEqualTo(new BigDecimal(RATE_TWENTY_FIVE));
        }

        @Test
        @DisplayName("the persistence provider's no-argument constructor exists and yields an "
                + "entirely unset row, so an unpopulated instance is distinguishable from a genuine "
                + "zero rate such as the one every row of the ZEROAPR group carries")
        void noArgumentConstructorYieldsAnAllNullInstance() {
            // The entity's no-argument constructor is protected, and this test class sits in
            // com.carddemo.domain - the SAME package as the entity. Java package access therefore
            // reaches a protected member directly. This is ordinary same-package visibility and is
            // explicitly NOT reflection: nothing is looked up by name and no accessibility is
            // overridden.
            DisclosureGroup row = new DisclosureGroup();

            assertThat(row.getDisAcctGroupId()).isNull();
            assertThat(row.getDisTranTypeCd()).isNull();
            assertThat(row.getDisTranCatCd()).isNull();
            assertThat(row.getDisIntRate()).isNull();
        }

        @Test
        @DisplayName("the row's identity is its business key alone: because the cluster definition "
                + "makes the key the leading 16 bytes of the record image, no surrogate or "
                + "generated identifier exists to accompany it")
        void noSurrogateIdentifierExists() {
            // This test proves the absence of a surrogate identifier by COMPILE-TIME absence, which
            // is the strongest available evidence and needs no reflection. Nowhere in this file is a
            // generated-identifier accessor named or invoked; had one been added to the entity, this
            // suite would still compile, but the entity's own contract would then contradict the
            // 16-byte leading key that the cluster definition declares. What is asserted here is the
            // positive consequence: every part of a row's identity is reachable through the three
            // business-key components, so a row can be addressed without any provider-assigned
            // value.
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(row.getDisAcctGroupId()).isNotNull();
            assertThat(row.getDisTranTypeCd()).isNotNull();
            assertThat(row.getDisTranCatCd()).isNotNull();

            int reconstructedKeyWidth = encodedWidthOf(row.getDisAcctGroupId())
                    + encodedWidthOf(row.getDisTranTypeCd())
                    + encodedWidthOf(row.getDisTranCatCd());

            assertThat(reconstructedKeyWidth).isEqualTo(DECLARED_KEY_WIDTH);
        }
    }

    @Nested
    @DisplayName("Space-padded key components")
    class SpacePaddedKeyComponents {

        @Test
        @DisplayName("the ten-character \"DEFAULT   \" is NOT the seven-character \"DEFAULT\": the "
                + "interest program reacts to a missing rate row - file status 23, which it treats "
                + "as non-fatal - by moving the seven-character DEFAULT literal into a 10-byte "
                + "alphanumeric group-identifier field, and a legacy alphanumeric move "
                + "left-justifies and space-pads to the receiving field's width, so the key it then "
                + "re-reads with is genuinely ten characters and never seven; the two are different "
                + "keys, they name different rows, and nothing in this record may trim either")
        void paddedDefaultGroupIdIsNotTheUnpaddedLiteral() {
            DisclosureGroup padded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup unpadded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_UNPADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(padded.getDisAcctGroupId()).isNotEqualTo(unpadded.getDisAcctGroupId());

            assertThat(encodedWidthOf(padded.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(encodedWidthOf(padded.getDisAcctGroupId())).isEqualTo(10);
            assertThat(encodedWidthOf(unpadded.getDisAcctGroupId())).isEqualTo(7);

            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(unpadded).isNotEqualTo(padded);
        }

        @Test
        @DisplayName("the ten-character \"ZEROAPR   \" is NOT the seven-character \"ZEROAPR\": the "
                + "third seeded group carries three trailing spaces exactly as the second does, so "
                + "the same byte-for-byte comparison applies to it")
        void paddedZeroAprGroupIdIsNotTheUnpaddedLiteral() {
            DisclosureGroup padded = new DisclosureGroup(
                    GROUP_ID_ZEROAPR_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_ZERO));
            DisclosureGroup unpadded = new DisclosureGroup(
                    GROUP_ID_ZEROAPR_UNPADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_ZERO));

            assertThat(padded.getDisAcctGroupId()).isNotEqualTo(unpadded.getDisAcctGroupId());

            assertThat(encodedWidthOf(padded.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(encodedWidthOf(unpadded.getDisAcctGroupId())).isEqualTo(7);

            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(unpadded).isNotEqualTo(padded);
        }

        @Test
        @DisplayName("all three seeded group identifiers are exactly ten bytes wide: the reference "
                + "file holds three groups of seventeen rows each, fifty-one rows in total, keyed "
                + "A000000000, \"DEFAULT   \" and \"ZEROAPR   \" - the latter two padded")
        void allThreeSeededGroupIdentifiersAreExactlyTenBytes() {
            assertThat(encodedWidthOf(GROUP_ID_A)).isEqualTo(GROUP_ID_WIDTH);
            assertThat(encodedWidthOf(GROUP_ID_DEFAULT_PADDED)).isEqualTo(GROUP_ID_WIDTH);
            assertThat(encodedWidthOf(GROUP_ID_ZEROAPR_PADDED)).isEqualTo(GROUP_ID_WIDTH);

            assertThat(SEEDED_GROUP_COUNT * SEEDED_ROWS_PER_GROUP).isEqualTo(SEEDED_RECORD_COUNT);
            assertThat(SEEDED_GROUP_COUNT).isEqualTo(3);
            assertThat(SEEDED_ROWS_PER_GROUP).isEqualTo(17);
            assertThat(SEEDED_RECORD_COUNT).isEqualTo(51);
        }

        @Test
        @DisplayName("a group identifier survives a setter with its trailing spaces intact, because "
                + "every seeded account row carries ten spaces in its own group identifier, which is "
                + "what makes the seeded data exercise the default-fallback path and nothing else")
        void aPaddedGroupIdentifierSurvivesTheSetterUntouched() {
            DisclosureGroup row = new DisclosureGroup();

            row.setDisAcctGroupId(GROUP_ID_DEFAULT_PADDED);

            assertThat(row.getDisAcctGroupId()).isEqualTo(GROUP_ID_DEFAULT_PADDED);
            assertThat(row.getDisAcctGroupId()).isNotEqualTo(GROUP_ID_DEFAULT_UNPADDED);
            assertThat(encodedWidthOf(row.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
        }

        @Test
        @DisplayName("the transaction category code keeps its leading zeros: the copybook types the "
                + "field as a zero-filled four-digit external decimal, so 0001 stays four "
                + "characters and is not the one-character 1 that a numeric column would have "
                + "stored")
        void categoryCodeKeepsItsLeadingZeros() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(row.getDisTranCatCd()).isEqualTo(TRAN_CAT);
            assertThat(encodedWidthOf(row.getDisTranCatCd())).isEqualTo(TRAN_CAT_WIDTH);
            assertThat(encodedWidthOf(row.getDisTranCatCd())).isEqualTo(4);

            assertThat(row.getDisTranCatCd()).isNotEqualTo(TRAN_CAT_WITHOUT_LEADING_ZEROS);
            assertThat(encodedWidthOf(TRAN_CAT_WITHOUT_LEADING_ZEROS)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Rate fidelity")
    class RateFidelity {

        @Test
        @DisplayName("the rate round-trips with both its value and its scale intact: the first "
                + "seeded row's rate is 15.00 at scale two, matching the field's two declared "
                + "decimal digits, and it is deliberately not the same object as the scale-free 15")
        void rateRoundTripsWithValueAndScaleIntact() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            BigDecimal stored = row.getDisIntRate();

            assertThat(stored).isNotNull();
            assertThat(stored.compareTo(new BigDecimal(RATE_FIFTEEN))).isZero();
            assertThat(stored.scale()).isEqualTo(RATE_DECIMAL_DIGITS);
            assertThat(stored.scale()).isEqualTo(2);

            // Numerically equal but not equal as values: BigDecimal equality includes the scale, and
            // the scale is the contract here, so the unscaled form must not satisfy it.
            assertThat(stored.compareTo(new BigDecimal(RATE_FIFTEEN_UNSCALED))).isZero();
            assertThat(stored).isNotEqualTo(new BigDecimal(RATE_FIFTEEN_UNSCALED));
        }

        @Test
        @DisplayName("a zero rate is a genuine present value and never null, absent or invalid: it "
                + "is precisely how every one of the seventeen rows in the ZEROAPR group makes the "
                + "accrual skip branch reachable from seeded data, because interest is computed only "
                + "when the rate is non-zero")
        void zeroRateIsALegitimatePresentValue() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_ZEROAPR_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_ZERO));

            BigDecimal stored = row.getDisIntRate();

            assertThat(stored).isNotNull();
            assertThat(stored.compareTo(BigDecimal.ZERO)).isZero();
            assertThat(stored.signum()).isZero();
            assertThat(stored.scale()).isEqualTo(RATE_DECIMAL_DIGITS);
            assertThat(stored).isEqualTo(new BigDecimal(RATE_ZERO));
        }

        @Test
        @DisplayName("a zero rate is distinguishable from an unset rate: the no-argument constructor "
                + "leaves the field null rather than seeding a zero, so an unpopulated row cannot be "
                + "mistaken for a ZEROAPR row")
        void zeroRateIsDistinguishableFromAnUnsetRate() {
            DisclosureGroup unpopulated = new DisclosureGroup();
            DisclosureGroup zeroRated = new DisclosureGroup(
                    GROUP_ID_ZEROAPR_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_ZERO));

            assertThat(unpopulated.getDisIntRate()).isNull();
            assertThat(zeroRated.getDisIntRate()).isNotNull();
        }

        @Test
        @DisplayName("a negative rate round-trips including its sign: the copybook declares the "
                + "field signed, so a negative value must be representable even though no seeded row "
                + "carries one")
        void negativeRateRoundTripsIncludingItsSign() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_NEGATIVE_FIFTEEN));

            BigDecimal stored = row.getDisIntRate();

            assertThat(stored).isNotNull();
            assertThat(stored.compareTo(new BigDecimal(RATE_NEGATIVE_FIFTEEN))).isZero();
            assertThat(stored.signum()).isEqualTo(-1);
            assertThat(stored.compareTo(BigDecimal.ZERO)).isNegative();
            assertThat(stored.scale()).isEqualTo(RATE_DECIMAL_DIGITS);
            assertThat(stored).isEqualTo(new BigDecimal(RATE_NEGATIVE_FIFTEEN));
        }

        @Test
        @DisplayName("the setter never widens a scale: a one-decimal value comes back at scale one "
                + "rather than being padded out to the field's two declared decimal digits, because "
                + "scaling is the codec's exclusive responsibility and not the entity's")
        void setterDoesNotWidenAScale() {
            DisclosureGroup row = new DisclosureGroup();

            row.setDisIntRate(new BigDecimal(RATE_SCALE_ONE));

            BigDecimal stored = row.getDisIntRate();

            assertThat(stored).isEqualTo(new BigDecimal(RATE_SCALE_ONE));
            assertThat(stored.scale()).isEqualTo(1);
            assertThat(stored.scale()).isNotEqualTo(RATE_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the setter neither truncates nor rounds: a three-decimal value comes back as "
                + "2.999 at scale three, not as the 2.99 a truncating store would produce nor the "
                + "3.00 a rounding store would produce - the estate declares no rounding clause "
                + "anywhere, so that truncation happens once, in the codec, and never here")
        void setterNeitherTruncatesNorRounds() {
            DisclosureGroup row = new DisclosureGroup();

            row.setDisIntRate(new BigDecimal(RATE_SCALE_THREE));

            BigDecimal stored = row.getDisIntRate();

            assertThat(stored).isEqualTo(new BigDecimal(RATE_SCALE_THREE));
            assertThat(stored.scale()).isEqualTo(3);

            assertThat(stored).isNotEqualTo(new BigDecimal(RATE_SCALE_THREE_IF_TRUNCATED));
            assertThat(stored.compareTo(new BigDecimal(RATE_SCALE_THREE_IF_TRUNCATED)))
                    .isNotZero();

            assertThat(stored).isNotEqualTo(new BigDecimal(RATE_SCALE_THREE_IF_ROUNDED));
            assertThat(stored.compareTo(new BigDecimal(RATE_SCALE_THREE_IF_ROUNDED))).isNotZero();
        }

        @Test
        @DisplayName("the all-argument constructor also applies no scaling and no rounding: a "
                + "one-decimal rate stays at scale one and a three-decimal rate stays 2.999 at scale "
                + "three, so neither construction path can quietly impose a rounding policy that "
                + "belongs solely to the codec")
        void constructorAppliesNeitherScalingNorRounding() {
            DisclosureGroup narrow = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_SCALE_ONE));

            assertThat(narrow.getDisIntRate()).isEqualTo(new BigDecimal(RATE_SCALE_ONE));
            assertThat(narrow.getDisIntRate().scale()).isEqualTo(1);

            DisclosureGroup wide = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_SCALE_THREE));

            BigDecimal stored = wide.getDisIntRate();

            assertThat(stored).isEqualTo(new BigDecimal(RATE_SCALE_THREE));
            assertThat(stored.scale()).isEqualTo(3);
            assertThat(stored).isNotEqualTo(new BigDecimal(RATE_SCALE_THREE_IF_TRUNCATED));
            assertThat(stored).isNotEqualTo(new BigDecimal(RATE_SCALE_THREE_IF_ROUNDED));
        }

        @Test
        @DisplayName("the rate accepts a value at the full four integer digits the field declares, "
                + "so a rate at the top of the range is representable without loss")
        void rateAcceptsTheFullFourIntegerDigits() {
            String widestRate = "9999.99";

            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(widestRate));

            BigDecimal stored = row.getDisIntRate();

            assertThat(stored).isEqualTo(new BigDecimal(widestRate));
            assertThat(stored.scale()).isEqualTo(RATE_DECIMAL_DIGITS);
            assertThat(stored.precision()).isEqualTo(RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS);
            assertThat(stored.precision()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("Entity identity is the composite key alone")
    class EntityIdentity {

        @Test
        @DisplayName("two rows sharing all three key components are equal and share a hash code even "
                + "when their rates differ, because the record's identity is the 16-byte key the "
                + "cluster definition declares and the rate is mutable non-key state")
        void rowsWithTheSameKeyAreEqualDespiteDifferingRates() {
            DisclosureGroup fifteenPercent = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup twentyFivePercent = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_TWENTY_FIVE));

            assertThat(fifteenPercent).isEqualTo(twentyFivePercent);
            assertThat(twentyFivePercent).isEqualTo(fifteenPercent);
            assertThat(fifteenPercent).hasSameHashCodeAs(twentyFivePercent);

            assertThat(fifteenPercent.getDisIntRate())
                    .isNotEqualTo(twentyFivePercent.getDisIntRate());
        }

        @Test
        @DisplayName("a differing group identifier alone makes two rows unequal: it is the first of "
                + "the three key components and is nonunique on its own, recurring seventeen times "
                + "per group in the seeded data")
        void aDifferingGroupIdentifierAloneMakesRowsUnequal() {
            DisclosureGroup first = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup second = new DisclosureGroup(
                    GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(first).isNotEqualTo(second);
            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("a differing transaction type code alone makes two rows unequal: it is the "
                + "second key component, two bytes at offset 10")
        void aDifferingTransactionTypeAloneMakesRowsUnequal() {
            DisclosureGroup first = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup second = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE_OTHER, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(first).isNotEqualTo(second);
            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("a differing transaction category code alone makes two rows unequal: it is the "
                + "third key component, four bytes at offset 12, and the seeded groups run through "
                + "consecutive category codes")
        void aDifferingTransactionCategoryAloneMakesRowsUnequal() {
            DisclosureGroup first = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup second = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT_OTHER, new BigDecimal(RATE_FIFTEEN));

            assertThat(first).isNotEqualTo(second);
            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("equality is reflexive, rejects null and rejects a foreign type rather than "
                + "throwing, so a row is safe to place in a collection alongside anything else")
        void equalityIsReflexiveNullSafeAndForeignTypeSafe() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(row.equals(row)).isTrue();
            assertThat(row.equals(null)).isFalse();
            assertThat(row.equals(FOREIGN_KEY_RENDERING)).isFalse();
            assertThat(row.equals(row.toId())).isFalse();
        }

        @Test
        @DisplayName("hashing agrees with equality for a padded group identifier and separates it "
                + "from the shortened form, so the padding participates in hashing exactly as it "
                + "participates in comparison")
        void hashingAgreesWithEqualityForPaddedIdentifiers() {
            DisclosureGroup padded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup samePadded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_TWENTY_FIVE));
            DisclosureGroup shortened = new DisclosureGroup(
                    GROUP_ID_DEFAULT_UNPADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(padded).hasSameHashCodeAs(samePadded);
            assertThat(padded.hashCode()).isNotEqualTo(shortened.hashCode());
        }

        @Test
        @DisplayName("two rows differing only in the padding of the group identifier are distinct "
                + "map keys, so a lookup by the seven-character literal would never reach the "
                + "ten-character row the interest fallback re-reads")
        void paddedAndUnpaddedRowsAreDistinctMapKeys() {
            DisclosureGroup padded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup unpadded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_UNPADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            Map<DisclosureGroup, String> byRow = new HashMap<>();
            byRow.put(padded, GROUP_ID_DEFAULT_PADDED);
            byRow.put(unpadded, GROUP_ID_DEFAULT_UNPADDED);

            assertThat(byRow).hasSize(2);
            assertThat(byRow.get(padded)).isEqualTo(GROUP_ID_DEFAULT_PADDED);
            assertThat(byRow.get(unpadded)).isEqualTo(GROUP_ID_DEFAULT_UNPADDED);
        }
    }

    @Nested
    @DisplayName("Key extracted from a populated row")
    class ExtractedKey {

        @Test
        @DisplayName("a row yields a key carrying its three components in copybook order, so the "
                + "16-byte key image reconstructs from the row without a surrogate identifier")
        void rowYieldsAKeyCarryingItsThreeComponentsInOrder() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            DisclosureGroupId extracted = row.toId();

            // The expected key is built independently from the same hand-derived fixture literals
            // that were passed to the row, not from anything the row computed.
            assertThat(extracted).isEqualTo(new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT));
            assertThat(extracted.getDisAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(extracted.getDisTranTypeCd()).isEqualTo(TRAN_TYPE);
            assertThat(extracted.getDisTranCatCd()).isEqualTo(TRAN_CAT);
        }

        @Test
        @DisplayName("an extracted key carries a padded group identifier at its full ten bytes and "
                + "keeps the category code's leading zeros, because extraction copies the components "
                + "rather than normalising them")
        void extractedKeyPreservesPaddingAndLeadingZeros() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            DisclosureGroupId extracted = row.toId();

            assertThat(extracted.getDisAcctGroupId()).isEqualTo(GROUP_ID_DEFAULT_PADDED);
            assertThat(extracted.getDisAcctGroupId()).isNotEqualTo(GROUP_ID_DEFAULT_UNPADDED);
            assertThat(encodedWidthOf(extracted.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);

            assertThat(extracted.getDisTranCatCd()).isEqualTo(TRAN_CAT);
            assertThat(encodedWidthOf(extracted.getDisTranCatCd())).isEqualTo(TRAN_CAT_WIDTH);

            assertThat(extracted)
                    .isNotEqualTo(new DisclosureGroupId(
                            GROUP_ID_DEFAULT_UNPADDED, TRAN_TYPE, TRAN_CAT));
        }

        @Test
        @DisplayName("extraction produces a fresh key on each call and retains no shared instance, "
                + "while two keys extracted from the same row are equal because the components are "
                + "unchanged")
        void extractionProducesAFreshButEqualKeyEachTime() {
            DisclosureGroup row = new DisclosureGroup(
                    GROUP_ID_ZEROAPR_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_ZERO));

            // Both extractions are compared against one independently constructed key rather than
            // against each other, so the expectation never comes from the implementation itself.
            DisclosureGroupId expected =
                    new DisclosureGroupId(GROUP_ID_ZEROAPR_PADDED, TRAN_TYPE, TRAN_CAT);

            DisclosureGroupId first = row.toId();
            DisclosureGroupId second = row.toId();

            assertThat(first).isNotSameAs(second);
            assertThat(first).isEqualTo(expected);
            assertThat(second).isEqualTo(expected);
            assertThat(first).hasSameHashCodeAs(expected);
            assertThat(second).hasSameHashCodeAs(expected);
        }

        @Test
        @DisplayName("an unpopulated row yields a key with three unset components, so extraction "
                + "reports the row's state rather than inventing a placeholder key")
        void unpopulatedRowYieldsAKeyWithUnsetComponents() {
            DisclosureGroup row = new DisclosureGroup();

            DisclosureGroupId extracted = row.toId();

            assertThat(extracted.getDisAcctGroupId()).isNull();
            assertThat(extracted.getDisTranTypeCd()).isNull();
            assertThat(extracted.getDisTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Composite key contract")
    class CompositeKeyContract {

        /** Serialization identity declared by the production key class, transcribed by hand. */
        private static final long DECLARED_SERIAL_VERSION_UID = 1L;

        @Test
        @DisplayName("the all-argument constructor takes the three components in the order they "
                + "occupy the record image - group identifier, transaction type, transaction "
                + "category - and stores each verbatim")
        void allArgumentConstructorRoundTripsAllThreeComponents() {
            DisclosureGroupId key = new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT);

            assertThat(key.getDisAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(key.getDisTranTypeCd()).isEqualTo(TRAN_TYPE);
            assertThat(key.getDisTranCatCd()).isEqualTo(TRAN_CAT);

            assertThat(encodedWidthOf(key.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(encodedWidthOf(key.getDisTranTypeCd())).isEqualTo(TRAN_TYPE_WIDTH);
            assertThat(encodedWidthOf(key.getDisTranCatCd())).isEqualTo(TRAN_CAT_WIDTH);
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider requires exists and "
                + "yields three unset components, which is why the key is a plain class rather than "
                + "a record - a record has no no-argument constructor to offer")
        void noArgumentConstructorExistsAndYieldsUnsetComponents() {
            // The production no-argument constructor is protected, and the key class lives in
            // com.carddemo.domain.id while this test lives in com.carddemo.domain, so a direct
            // `new DisclosureGroupId()` does not compile from here. This is a documented divergence
            // from the written contract summary, which described the constructor as public; the
            // production signature is authoritative, so the test adapts rather than the class.
            // Instantiating the probe subclass invokes that protected constructor through an
            // explicit superclass constructor invocation, which is permitted from a subclass body in
            // any package. Declaring the subclass is compile-time proof the constructor exists;
            // running it is runtime proof it leaves every component unset. This is inheritance, NOT
            // reflection - nothing is looked up by name and no accessibility is overridden.
            DisclosureGroupId key = new ProtectedKeyConstructorProbe();

            assertThat(key.getDisAcctGroupId()).isNull();
            assertThat(key.getDisTranTypeCd()).isNull();
            assertThat(key.getDisTranCatCd()).isNull();
        }

        @Test
        @DisplayName("two keys with all three components matching are equal, share a hash code, and "
                + "a key equals itself")
        void keysWithAllThreeComponentsMatchingAreEqual() {
            DisclosureGroupId key = new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId same = new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT);

            assertThat(key.equals(key)).isTrue();
            assertThat(key).isEqualTo(same);
            assertThat(same).isEqualTo(key);
            assertThat(key).hasSameHashCodeAs(same);
        }

        @Test
        @DisplayName("a differing group identifier alone makes two keys unequal, so the first key "
                + "component is genuinely compared")
        void aDifferingGroupIdentifierAloneMakesKeysUnequal() {
            DisclosureGroupId key = new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId other =
                    new DisclosureGroupId(GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT);

            assertThat(key).isNotEqualTo(other);
            assertThat(other).isNotEqualTo(key);
        }

        @Test
        @DisplayName("a differing transaction type code alone makes two keys unequal, so the second "
                + "key component is genuinely compared")
        void aDifferingTransactionTypeAloneMakesKeysUnequal() {
            DisclosureGroupId key = new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId other =
                    new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE_OTHER, TRAN_CAT);

            assertThat(key).isNotEqualTo(other);
            assertThat(other).isNotEqualTo(key);
        }

        @Test
        @DisplayName("a differing transaction category code alone makes two keys unequal, so the "
                + "third key component is genuinely compared")
        void aDifferingTransactionCategoryAloneMakesKeysUnequal() {
            DisclosureGroupId key = new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId other =
                    new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT_OTHER);

            assertThat(key).isNotEqualTo(other);
            assertThat(other).isNotEqualTo(key);
        }

        @Test
        @DisplayName("a key rejects null and rejects the flattened text of its own components "
                + "rather than throwing, so the concatenated key image is not mistaken for a key")
        void keyIsNullSafeAndForeignTypeSafe() {
            DisclosureGroupId key =
                    new DisclosureGroupId(GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT);

            assertThat(key.equals(null)).isFalse();
            assertThat(key.equals(FOREIGN_KEY_RENDERING)).isFalse();

            // The foreign value is exactly the sixteen bytes the three components occupy in the
            // record image, which is why it is the sharpest available negative case.
            assertThat(encodedWidthOf(FOREIGN_KEY_RENDERING)).isEqualTo(DECLARED_KEY_WIDTH);
        }

        @Test
        @DisplayName("the ten-character \"DEFAULT   \" key is NOT the seven-character \"DEFAULT\" "
                + "key and the two are distinct map entries: a runtime lookup by the unpadded "
                + "identifier would resolve nothing, because the identifier the interest program "
                + "re-reads with is the space-padded ten-character form a legacy alphanumeric move "
                + "produces")
        void paddedAndUnpaddedKeysAreDistinctMapKeys() {
            DisclosureGroupId padded =
                    new DisclosureGroupId(GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId unpadded =
                    new DisclosureGroupId(GROUP_ID_DEFAULT_UNPADDED, TRAN_TYPE, TRAN_CAT);

            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(unpadded).isNotEqualTo(padded);

            Map<DisclosureGroupId, String> byKey = new HashMap<>();
            byKey.put(padded, GROUP_ID_DEFAULT_PADDED);
            byKey.put(unpadded, GROUP_ID_DEFAULT_UNPADDED);

            assertThat(byKey).hasSize(2);
            assertThat(byKey.get(padded)).isEqualTo(GROUP_ID_DEFAULT_PADDED);
            assertThat(byKey.get(unpadded)).isEqualTo(GROUP_ID_DEFAULT_UNPADDED);
        }

        @Test
        @DisplayName("nothing in the key normalises a component: a trailing-space value is returned "
                + "with its spaces intact and compares unequal to its shortened form, so neither the "
                + "constructor nor equality nor hashing trims")
        void keyNormalisesNothing() {
            DisclosureGroupId padded =
                    new DisclosureGroupId(GROUP_ID_ZEROAPR_PADDED, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId shortened =
                    new DisclosureGroupId(GROUP_ID_ZEROAPR_UNPADDED, TRAN_TYPE, TRAN_CAT);

            assertThat(padded.getDisAcctGroupId()).isEqualTo(GROUP_ID_ZEROAPR_PADDED);
            assertThat(encodedWidthOf(padded.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(encodedWidthOf(shortened.getDisAcctGroupId())).isEqualTo(7);

            assertThat(padded).isNotEqualTo(shortened);
            assertThat(padded.hashCode()).isNotEqualTo(shortened.hashCode());
        }

        @Test
        @DisplayName("leading zeros in the transaction category code are significant: a key on 0001 "
                + "is not a key on 1, which is what a numeric key component would have collapsed it "
                + "to")
        void leadingZerosInTheCategoryCodeAreSignificant() {
            DisclosureGroupId zeroFilled = new DisclosureGroupId(GROUP_ID_A, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId collapsed = new DisclosureGroupId(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT_WITHOUT_LEADING_ZEROS);

            assertThat(zeroFilled).isNotEqualTo(collapsed);
            assertThat(collapsed).isNotEqualTo(zeroFilled);

            assertThat(encodedWidthOf(zeroFilled.getDisTranCatCd())).isEqualTo(TRAN_CAT_WIDTH);
            assertThat(encodedWidthOf(collapsed.getDisTranCatCd())).isEqualTo(1);
        }

        @Test
        @DisplayName("the key declares an explicit, stable serialization identity, which the build "
                + "requires because a serializable class without one raises a lint warning and "
                + "warnings fail the build")
        void serialVersionUidIsExplicitAndStable() {
            // ObjectStreamClass is the sanctioned serialization-metadata API of the java.io package.
            // It is NOT part of the runtime reflection API, so reading the serialization identity
            // through it satisfies the module's zero-reflection constraint: no member is looked up by
            // name and no accessibility is overridden. The expected value was transcribed by hand
            // from the production class rather than computed from it.
            ObjectStreamClass descriptor = ObjectStreamClass.lookup(DisclosureGroupId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(DECLARED_SERIAL_VERSION_UID);
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a key survives a Java serialization round trip and a padded group identifier "
                + "comes back with its trailing spaces intact, so the padding travels with the key "
                + "rather than being a local artefact")
        void keySurvivesASerializationRoundTripWithPaddingIntact()
                throws IOException, ClassNotFoundException {
            DisclosureGroupId original =
                    new DisclosureGroupId(GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT);

            byte[] serialised;
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(original);
                out.flush();
                serialised = bytes.toByteArray();
            }

            DisclosureGroupId restored;
            try (ByteArrayInputStream bytes = new ByteArrayInputStream(serialised);
                    ObjectInputStream in = new ObjectInputStream(bytes)) {
                restored = (DisclosureGroupId) in.readObject();
            }

            assertThat(restored).isNotSameAs(original);
            assertThat(restored).isEqualTo(original);
            assertThat(restored).hasSameHashCodeAs(original);

            assertThat(restored.getDisAcctGroupId()).isEqualTo(GROUP_ID_DEFAULT_PADDED);
            assertThat(encodedWidthOf(restored.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(restored.getDisAcctGroupId()).isNotEqualTo(GROUP_ID_DEFAULT_UNPADDED);

            assertThat(restored.getDisTranTypeCd()).isEqualTo(TRAN_TYPE);
            assertThat(restored.getDisTranCatCd()).isEqualTo(TRAN_CAT);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("a row's diagnostic rendering does not normalise the group identifier: the "
                + "padded and shortened forms render differently, so no trimming leaks into the one "
                + "place a reviewer is most likely to read a key from")
        void rowRenderingDoesNotNormaliseThePaddedGroupIdentifier() {
            // The written contract summary allowed toString() to be absent; both production classes
            // declare it, so per the same mandate the file follows the classes. Nothing here pins the
            // format - only the behavioural claim that the rendering preserves what the key
            // preserves.
            DisclosureGroup padded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup unpadded = new DisclosureGroup(
                    GROUP_ID_DEFAULT_UNPADDED, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));

            assertThat(padded.toString()).isNotEqualTo(unpadded.toString());
        }

        @Test
        @DisplayName("a key's diagnostic rendering likewise does not normalise the group identifier, "
                + "so a padded key and its shortened form remain distinguishable in a failure "
                + "message")
        void keyRenderingDoesNotNormaliseThePaddedGroupIdentifier() {
            DisclosureGroupId padded =
                    new DisclosureGroupId(GROUP_ID_DEFAULT_PADDED, TRAN_TYPE, TRAN_CAT);
            DisclosureGroupId unpadded =
                    new DisclosureGroupId(GROUP_ID_DEFAULT_UNPADDED, TRAN_TYPE, TRAN_CAT);

            assertThat(padded.toString()).isNotEqualTo(unpadded.toString());
        }

        @Test
        @DisplayName("two rows differing only in their rate render identically, because the "
                + "rendering carries the key and deliberately omits the financial value")
        void rowRenderingOmitsTheRate() {
            DisclosureGroup fifteenPercent = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_FIFTEEN));
            DisclosureGroup twentyFivePercent = new DisclosureGroup(
                    GROUP_ID_A, TRAN_TYPE, TRAN_CAT, new BigDecimal(RATE_TWENTY_FIVE));

            assertThat(fifteenPercent.toString()).isEqualTo(twentyFivePercent.toString());
        }
    }
}
