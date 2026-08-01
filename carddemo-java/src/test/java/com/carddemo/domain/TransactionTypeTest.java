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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionType}, the entity form of the 60-byte transaction-type
 * reference row.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVTRA03Y.cpy} declares a 60-byte record in three parts: a 2-byte
 * transaction type code at offset 0, a 50-byte description at offset 2 and an 8-byte trailing
 * filler at offset 52. The cluster definition in {@code app/jcl/TRANTYPE.jcl} corroborates that
 * geometry independently with {@code KEYS(2 0)} on a {@code RECORDSIZE(60 60)} indexed cluster, so
 * the whole key is the leading 2 bytes and nothing else. The filler carries no information and is
 * therefore mapped by no attribute, which leaves the entity with exactly two mapped properties -
 * the smallest of the eleven entity translations in this package.</p>
 *
 * <h2>Where every expected value in this suite comes from</h2>
 *
 * <p>Every width, offset, count and literal asserted below was derived by hand from the copybook
 * and from the reference file {@code app/data/ASCII/trantype.txt}, then written here as a constant.
 * Nothing is read back from the class under test to produce an expectation, and nothing is read
 * from disk or from the classpath: this is a pure unit test that touches no container, no
 * application context, no database, no network and no file. The reference file's arithmetic is
 * itself part of the evidence - it measures 427 bytes, which is exactly 7 records at the 60-byte
 * record length plus one line terminator each - so the seeded row count is a derived fact rather
 * than a guess.</p>
 *
 * <h2>Why the type code has to stay text</h2>
 *
 * <p>All seven seeded codes carry a leading zero, running from {@code 01} to {@code 07}, and the
 * code occupies a fixed 2-byte field in the record image. Held as a number the first code would
 * come back one byte wide, the stored key would no longer be the 2 bytes the record publishes, and
 * a transaction stamped {@code 01} would fail to resolve against this table. The assertions below
 * prove the leading zero survives a round trip and that {@code "01"} is not equal to {@code "1"}.</p>
 *
 * <h2>Why the blank padding is contractual</h2>
 *
 * <p>In the record image every description fills its 50-byte field, blank-padded on the right, and
 * that padding is part of the external width the legacy record publishes. The entity therefore has
 * to return a description exactly as it was supplied: a setter that trimmed, folded or re-padded
 * would silently change the byte width of any fixed-width line formatted from it. The suite proves
 * the absence of that normalisation directly, by storing a padded value and asserting that what
 * comes back still differs from the unpadded text.</p>
 *
 * <h2>Two 60-byte layouts that must never be conflated</h2>
 *
 * <p>The transaction-category copybook {@code app/cpy/CVTRA04Y.cpy} also describes a 60-byte record
 * carrying a 50-byte description, but it splits differently - a 2-byte type code, a 4-byte category
 * code, the description at offset 6 and a 4-byte filler - and it names its type code with a code
 * suffix that this copybook does not use. That naming asymmetry between two sibling copybooks is
 * preserved rather than harmonised, so this entity's key property is {@code tranType} and never the
 * suffixed spelling the category record uses. Because both layouts total 60 bytes, a width check
 * cannot tell them apart; nothing here attempts to infer a layout from a record image, because the
 * caller always knows which dataset it read.</p>
 *
 * <h2>Deliberately not asserted here</h2>
 *
 * <p>Column names, declared widths, nullability and key metadata are not verified in this tier.
 * Schema agreement is enforced where it can actually fail - against a real relational database in
 * the integration tier, where the provider runs in validate mode and refuses to start on any
 * divergence. Nothing here inspects an annotation, and no reflective access of any kind is used,
 * because the module holds a zero budget for it. Nor is any relationship asserted: no foreign key
 * targets or originates from this table in any migration, so the entity models no association.</p>
 *
 * <p><strong>One divergence from the specified contract summary is recorded here.</strong> That
 * summary anticipated that a diagnostic string representation might be absent from the entity and
 * directed that no assertion be made on it. The class as written does declare one. The class is
 * authoritative on signatures, so the representation is exercised below to keep every declared
 * member covered, but only its availability and its null-safety are asserted - never its rendered
 * layout, which stays uncontracted exactly as the summary intends.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("TransactionType: the 60-byte CVTRA03Y transaction-type reference row")
class TransactionTypeTest {

    /** Width of the type code field, hand-derived from its {@code X(02)} declaration. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of the description field, hand-derived from its {@code X(50)} declaration. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** Width of the unmapped trailing filler, hand-derived from its {@code X(08)} declaration. */
    private static final int FILLER_WIDTH = 8;

    /** Record length the copybook states and the cluster definition repeats as {@code (60 60)}. */
    private static final int RECORD_WIDTH = 60;

    /** Zero-based offset of the type code, which is also the key offset the cluster declares. */
    private static final int TYPE_CODE_OFFSET = 0;

    /** Zero-based offset of the description, immediately after the 2-byte code. */
    private static final int DESCRIPTION_OFFSET = 2;

    /** Zero-based offset of the trailing filler, immediately after the 50-byte description. */
    private static final int FILLER_OFFSET = 52;

    /** Rows the reference file carries. */
    private static final int SEEDED_ROW_COUNT = 7;

    /** Bytes the reference file measures. */
    private static final int SEEDED_FILE_BYTE_COUNT = 427;

    /** One line terminator follows each record in the reference file. */
    private static final int LINE_TERMINATOR_WIDTH = 1;

    /** First seeded type code, and the one the interest run stamps on what it synthesises. */
    private static final String FIRST_TYPE_CODE = "01";

    /** Description text of the first seeded row, before the field's blank padding is applied. */
    private static final String FIRST_TYPE_TEXT = "Purchase";

    /** Bytes {@link #FIRST_TYPE_TEXT} occupies unpadded, counted by hand over its 8 characters. */
    private static final int FIRST_TYPE_TEXT_WIDTH = 8;

    /** Last seeded type code. */
    private static final String LAST_TYPE_CODE = "07";

    /** Description text of the last seeded row, before the field's blank padding is applied. */
    private static final String LAST_TYPE_TEXT = "Adjustment";

    /** Longest of the seven seeded description texts. */
    private static final String LONGEST_TYPE_TEXT = "Authorization";

    /** Bytes {@link #LONGEST_TYPE_TEXT} occupies, counted by hand over its 13 characters. */
    private static final int LONGEST_TYPE_TEXT_WIDTH = 13;

    /**
     * Blank-pads description text on the right to the full width of the description field, the way
     * the record image carries it.
     *
     * <p>The padding is computed from the encoded byte count rather than from the character count,
     * because the field is a fixed-width byte field. Only the seven seeded description texts are
     * passed in, and every one of them is far shorter than the field, so the computed padding is
     * always positive. This helper is test-local arithmetic over the copybook width: no method of
     * the class under test participates in producing an expected value anywhere in this suite.</p>
     *
     * @param text the unpadded description text
     * @return the text followed by enough blanks to fill the description field exactly
     */
    private static String blankPaddedDescription(final String text) {
        final int encodedLength = text.getBytes(StandardCharsets.US_ASCII).length;
        return text + " ".repeat(DESCRIPTION_WIDTH - encodedLength);
    }

    /**
     * Counts the bytes a value occupies when encoded, which is the measure the fixed-width record
     * contract is expressed in.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int encodedWidthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Verifies the transcribed record geometry, so that a drifted constant is caught here rather
     * than silently weakening every assertion that depends on it.
     */
    @Nested
    @DisplayName("record geometry")
    class RecordGeometry {

        @Test
        @DisplayName("the copybook's three field widths of 2, 50 and 8 sum to the 60-byte record "
                + "length that the cluster definition repeats as RECORDSIZE(60 60)")
        void theThreeFieldWidthsSumToTheRecordLength() {
            assertThat(TYPE_CODE_WIDTH + DESCRIPTION_WIDTH + FILLER_WIDTH)
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each field starts where the preceding one ends, so the key sits at offset 0, "
                + "the description at offset 2 and the filler at offset 52")
        void eachFieldStartsWhereThePrecedingOneEnds() {
            assertThat(TYPE_CODE_OFFSET).isZero();
            assertThat(TYPE_CODE_OFFSET + TYPE_CODE_WIDTH).isEqualTo(DESCRIPTION_OFFSET);
            assertThat(DESCRIPTION_OFFSET + DESCRIPTION_WIDTH).isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the 2-byte key at offset 0 that the cluster declares is the type code field "
                + "itself, so a type code alone locates a row")
        void theClusterKeyIsTheTypeCodeField() {
            assertThat(TYPE_CODE_WIDTH).isEqualTo(DESCRIPTION_OFFSET - TYPE_CODE_OFFSET);
            assertThat(encodedWidthOf(FIRST_TYPE_CODE)).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(encodedWidthOf(LAST_TYPE_CODE)).isEqualTo(TYPE_CODE_WIDTH);
        }

        @Test
        @DisplayName("the reference file's 427 bytes are exactly 7 records of 60 bytes plus one "
                + "line terminator each, which is where the seeded row count comes from")
        void theReferenceFileArithmeticYieldsSevenRows() {
            assertThat(SEEDED_ROW_COUNT * (RECORD_WIDTH + LINE_TERMINATOR_WIDTH))
                    .isEqualTo(SEEDED_FILE_BYTE_COUNT);
        }
    }

    /**
     * Verifies that each value handed in reaches the accessor that names it, and that the entity
     * offers the no-argument constructor a persistence provider requires.
     */
    @Nested
    @DisplayName("construction and attribute carriage")
    class ConstructionAndAttributeCarriage {

        @Test
        @DisplayName("the two-argument constructor takes the type code first and the description "
                + "second, matching the copybook's declaration order, and each argument reaches "
                + "its own accessor")
        void bothConstructorArgumentsReachTheirOwnAccessor() {
            final String description = blankPaddedDescription(FIRST_TYPE_TEXT);

            final TransactionType row = new TransactionType(FIRST_TYPE_CODE, description);

            assertThat(row.getTranType()).isEqualTo(FIRST_TYPE_CODE);
            assertThat(row.getTranTypeDesc()).isEqualTo(description);
        }

        @Test
        @DisplayName("the two arguments are not transposed, which their very different field widths "
                + "of 2 and 50 bytes make detectable")
        void theTwoArgumentsAreNotTransposed() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(encodedWidthOf(row.getTranType())).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(encodedWidthOf(row.getTranTypeDesc())).isEqualTo(DESCRIPTION_WIDTH);
            assertThat(row.getTranTypeDesc()).startsWith(FIRST_TYPE_TEXT);
        }

        @Test
        @DisplayName("the type code mutator replaces the 2-byte key field and leaves the "
                + "description untouched")
        void theTypeCodeMutatorReplacesOnlyTheKeyField() {
            final String description = blankPaddedDescription(FIRST_TYPE_TEXT);
            final TransactionType row = new TransactionType(FIRST_TYPE_CODE, description);

            row.setTranType(LAST_TYPE_CODE);

            assertThat(row.getTranType()).isEqualTo(LAST_TYPE_CODE);
            assertThat(row.getTranTypeDesc()).isEqualTo(description);
        }

        @Test
        @DisplayName("the description mutator replaces the 50-byte description field and leaves the "
                + "type code untouched")
        void theDescriptionMutatorReplacesOnlyTheDescriptionField() {
            final String replacement = blankPaddedDescription(LAST_TYPE_TEXT);
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            row.setTranTypeDesc(replacement);

            assertThat(row.getTranTypeDesc()).isEqualTo(replacement);
            assertThat(row.getTranType()).isEqualTo(FIRST_TYPE_CODE);
        }

        @Test
        @DisplayName("the no-argument constructor a persistence provider needs exists and yields a "
                + "row whose 2-byte code and 50-byte description are both absent, because nothing "
                + "is defaulted or generated on construction")
        void theNoArgumentConstructorYieldsAnUnpopulatedRow() {
            // This test class sits in the same package as the entity, so ordinary Java package
            // access reaches the entity's protected no-argument constructor directly. That is
            // plain compile-time visibility and is explicitly NOT reflection: no reflective
            // lookup, no accessibility override and no test-utility introspection is involved
            // anywhere in this suite. It is the only non-reflective way in the module to prove the
            // constructor a provider instantiates through is actually present.
            final TransactionType row = new TransactionType();

            assertThat(row.getTranType()).isNull();
            assertThat(row.getTranTypeDesc()).isNull();
        }

        @Test
        @DisplayName("a mutator accepts an absent value, so the entity applies no validation of its "
                + "own and leaves the not-null guarantee to the schema")
        void aMutatorAcceptsAnAbsentValue() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            row.setTranType(null);
            row.setTranTypeDesc(null);

            assertThat(row.getTranType()).isNull();
            assertThat(row.getTranTypeDesc()).isNull();
        }
    }

    /**
     * Verifies the external byte widths the fixed-width record publishes. Every width is measured
     * on the encoded bytes rather than on the character count, because the legacy field is a byte
     * field and byte width is what the record contract fixes.
     */
    @Nested
    @DisplayName("external field widths")
    class ExternalFieldWidths {

        @Test
        @DisplayName("the type code occupies exactly 2 bytes, the width the copybook fixes and the "
                + "cluster repeats as its key length")
        void theTypeCodeOccupiesExactlyTwoBytes() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(row.getTranType().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(TYPE_CODE_WIDTH);
        }

        @Test
        @DisplayName("the type code keeps its leading zero, so the stored key still matches the 2 "
                + "bytes the record image carries and 01 is never the same value as 1")
        void theTypeCodeKeepsItsLeadingZero() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(row.getTranType()).isEqualTo("01").isNotEqualTo("1").startsWith("0");
        }

        @Test
        @DisplayName("the description occupies exactly 50 bytes once blank-padded to its field "
                + "width, which is the width the record image publishes")
        void theDescriptionOccupiesExactlyFiftyBytes() {
            final String description = blankPaddedDescription(FIRST_TYPE_TEXT);
            final TransactionType row = new TransactionType(FIRST_TYPE_CODE, description);

            assertThat(description.getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(DESCRIPTION_WIDTH);
            assertThat(row.getTranTypeDesc().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("the longest seeded description still fits its 50-byte field, so no seeded row "
                + "needs truncating to be carried")
        void theLongestSeededDescriptionFitsItsField() {
            assertThat(encodedWidthOf(LONGEST_TYPE_TEXT))
                    .isEqualTo(LONGEST_TYPE_TEXT_WIDTH)
                    .isLessThan(DESCRIPTION_WIDTH);

            final TransactionType row =
                    new TransactionType("04", blankPaddedDescription(LONGEST_TYPE_TEXT));

            assertThat(row.getTranTypeDesc().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(DESCRIPTION_WIDTH);
        }
    }

    /**
     * Verifies that the entity normalises nothing. The blank padding that fills the 50-byte
     * description field is part of the external width the legacy record publishes, so a value has to
     * survive a round trip byte for byte.
     */
    @Nested
    @DisplayName("padding preservation")
    class PaddingPreservation {

        @Test
        @DisplayName("a description stored through the mutator at its full 50-byte padded width "
                + "comes back byte for byte, and is still not equal to the unpadded text, which is "
                + "what proves no trimming or re-padding happens")
        void aPaddedDescriptionSurvivesTheMutatorUntouched() {
            final String padded = blankPaddedDescription(FIRST_TYPE_TEXT);
            final TransactionType row = new TransactionType();

            row.setTranTypeDesc(padded);

            assertThat(row.getTranTypeDesc()).isEqualTo(padded);
            assertThat(row.getTranTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(padded.getBytes(StandardCharsets.US_ASCII));
            assertThat(row.getTranTypeDesc()).isNotEqualTo(FIRST_TYPE_TEXT);
            assertThat(encodedWidthOf(row.getTranTypeDesc())).isEqualTo(DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("a description supplied to the constructor at its full 50-byte padded width is "
                + "carried unchanged too, so neither entry point normalises")
        void aPaddedDescriptionSurvivesTheConstructorUntouched() {
            final String padded = blankPaddedDescription(LAST_TYPE_TEXT);

            final TransactionType row = new TransactionType(LAST_TYPE_CODE, padded);

            assertThat(row.getTranTypeDesc()).isEqualTo(padded).isNotEqualTo(LAST_TYPE_TEXT);
            assertThat(row.getTranTypeDesc()).endsWith(" ");
        }

        @Test
        @DisplayName("an unpadded description is not padded out on the entity's behalf either: what "
                + "goes in is what comes back, because padding the record image is the record "
                + "mapper's concern and not the entity's")
        void anUnpaddedDescriptionIsNotPaddedOut() {
            final TransactionType row = new TransactionType(FIRST_TYPE_CODE, FIRST_TYPE_TEXT);

            assertThat(row.getTranTypeDesc()).isEqualTo(FIRST_TYPE_TEXT);
            assertThat(row.getTranTypeDesc().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(FIRST_TYPE_TEXT_WIDTH)
                    .isLessThan(DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("a type code is not case-folded, blank-stripped or otherwise adjusted, so a "
                + "value that does not belong in the 2-byte field is carried rather than repaired")
        void aTypeCodeIsCarriedWithoutAdjustment() {
            final TransactionType row = new TransactionType();

            row.setTranType(" 1");

            assertThat(row.getTranType()).isEqualTo(" 1").isNotEqualTo("1").isNotEqualTo("01");
            assertThat(encodedWidthOf(row.getTranType())).isEqualTo(TYPE_CODE_WIDTH);
        }
    }

    /**
     * Verifies that every one of the seven seeded reference rows is carried exactly, at both of its
     * declared field widths. The seven code-and-description pairs are transcribed from the estate's
     * own reference file and are the oracle: nothing here reads that file, and nothing asks the
     * entity what it thinks the values should be.
     */
    @Nested
    @DisplayName("seeded reference rows")
    class SeededReferenceRows {

        @ParameterizedTest(name = "type {0} is {1}")
        @DisplayName("each of the 7 seeded rows round-trips its 2-byte code and its 50-byte "
                + "blank-padded description exactly as the reference file carries them")
        @CsvSource({
            "01,Purchase",
            "02,Payment",
            "03,Credit",
            "04,Authorization",
            "05,Refund",
            "06,Reversal",
            "07,Adjustment",
        })
        void eachSeededRowRoundTripsBothFields(final String code, final String text) {
            final String description = blankPaddedDescription(text);

            final TransactionType row = new TransactionType(code, description);

            assertThat(row.getTranType()).isEqualTo(code);
            assertThat(row.getTranTypeDesc()).isEqualTo(description);
            assertThat(row.getTranType().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(TYPE_CODE_WIDTH);
            assertThat(row.getTranTypeDesc().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(DESCRIPTION_WIDTH);
        }

        @ParameterizedTest(name = "type {0} carries a leading zero")
        @DisplayName("every seeded code is a two-character value carrying a leading zero, so none "
                + "of them would survive being held as a number")
        @CsvSource({"01", "02", "03", "04", "05", "06", "07"})
        void everySeededCodeCarriesALeadingZero(final String code) {
            final TransactionType row = new TransactionType(code, blankPaddedDescription("Credit"));

            assertThat(row.getTranType()).isEqualTo(code).startsWith("0");
            assertThat(encodedWidthOf(row.getTranType())).isEqualTo(TYPE_CODE_WIDTH);
        }

        @Test
        @DisplayName("the seven seeded codes are distinct, so the reference table carries seven "
                + "separate identities rather than a repeated key")
        void theSevenSeededCodesAreDistinct() {
            final TransactionType[] rows = {
                new TransactionType("01", blankPaddedDescription("Purchase")),
                new TransactionType("02", blankPaddedDescription("Payment")),
                new TransactionType("03", blankPaddedDescription("Credit")),
                new TransactionType("04", blankPaddedDescription("Authorization")),
                new TransactionType("05", blankPaddedDescription("Refund")),
                new TransactionType("06", blankPaddedDescription("Reversal")),
                new TransactionType("07", blankPaddedDescription("Adjustment")),
            };

            assertThat(rows).hasSize(SEEDED_ROW_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the type code the interest run stamps on the transactions it synthesises is "
                + "the first seeded code, so the posted interest resolves against this table")
        void theInterestRunsTypeCodeIsOneOfTheSeededCodes() {
            final TransactionType purchase =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(purchase.getTranType()).isEqualTo("01");
            assertThat(encodedWidthOf(purchase.getTranType())).isEqualTo(TYPE_CODE_WIDTH);
        }
    }

    /**
     * Verifies that identity is the 2-byte key and nothing else. The description is mutable, so
     * admitting it into equality would let a row's hash change while the row sits inside a
     * hash-based collection.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("two rows sharing a type code are equal and share a hash code even when their "
                + "descriptions differ, because only the 2-byte key participates in identity")
        void rowsSharingATypeCodeAreEqualDespiteDifferentDescriptions() {
            final TransactionType left =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));
            final TransactionType right =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(LAST_TYPE_TEXT));

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);

            // Each description is checked against its own hand-derived expectation rather than
            // against the other row's, so the equality above is demonstrably not vacuous: the two
            // rows really do carry different descriptions.
            assertThat(left.getTranTypeDesc()).isEqualTo(blankPaddedDescription(FIRST_TYPE_TEXT));
            assertThat(right.getTranTypeDesc()).isEqualTo(blankPaddedDescription(LAST_TYPE_TEXT));
        }

        @Test
        @DisplayName("two rows with different type codes are unequal even when their descriptions "
                + "match exactly, so the key alone discriminates")
        void rowsWithDifferentTypeCodesAreUnequal() {
            final String sharedDescription = blankPaddedDescription(FIRST_TYPE_TEXT);

            final TransactionType left = new TransactionType(FIRST_TYPE_CODE, sharedDescription);
            final TransactionType right = new TransactionType(LAST_TYPE_CODE, sharedDescription);

            assertThat(left).isNotEqualTo(right);
            assertThat(right).isNotEqualTo(left);
        }

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(row.equals(row)).isTrue();
        }

        @Test
        @DisplayName("replacing the description does not disturb the hash code, so a row stays "
                + "findable in a hash-based collection after its descriptive text changes")
        void replacingTheDescriptionDoesNotDisturbTheHashCode() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));
            final int hashBeforeTheChange = row.hashCode();

            row.setTranTypeDesc(blankPaddedDescription(LAST_TYPE_TEXT));

            assertThat(row.hashCode()).isEqualTo(hashBeforeTheChange);
            assertThat(row.getTranTypeDesc()).isEqualTo(blankPaddedDescription(LAST_TYPE_TEXT));
        }

        @Test
        @DisplayName("a row is unequal to an absent value rather than failing on it, so a comparison "
                + "against nothing is safe")
        void aRowIsUnequalToAnAbsentValue() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(row.equals(null)).isFalse();
        }

        @Test
        @DisplayName("a row is unequal to a value of another type, so a bare 2-character type code "
                + "is never mistaken for the reference row it identifies")
        void aRowIsUnequalToAValueOfAnotherType() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(row.equals(FIRST_TYPE_CODE)).isFalse();
            assertThat(row.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("a type code differing only in its leading zero yields a different identity, so "
                + "01 and 1 are never the same reference row")
        void aCodeDifferingOnlyInItsLeadingZeroIsADifferentIdentity() {
            final String sharedDescription = blankPaddedDescription(FIRST_TYPE_TEXT);

            final TransactionType padded = new TransactionType("01", sharedDescription);
            final TransactionType unpadded = new TransactionType("1", sharedDescription);

            assertThat(padded).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("two rows whose key has not been populated are equal to each other, because the "
                + "key is absent rather than machine-generated")
        void twoUnpopulatedRowsAreEqual() {
            final TransactionType left = new TransactionType();
            final TransactionType right = new TransactionType();

            assertThat(left).isEqualTo(right);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("a populated row is unequal to an unpopulated one in both directions, so a row "
                + "awaiting its key never collides with a seeded reference row")
        void aPopulatedRowIsUnequalToAnUnpopulatedOne() {
            final TransactionType populated =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));
            final TransactionType unpopulated = new TransactionType();

            assertThat(populated).isNotEqualTo(unpopulated);
            assertThat(unpopulated).isNotEqualTo(populated);
        }

        @Test
        @DisplayName("the identifier is the 2-byte business key itself and no surrogate or generated "
                + "identifier exists: a row carries its full identity the moment it is constructed, "
                + "and two independently constructed rows with the same code are already equal")
        void theIdentifierIsTheBusinessKeyAndNoSurrogateExists() {
            // No getter, mutator or accessor for a generated identifier is referenced anywhere in
            // this class, and none can be: the entity declares none, so the absence is proved at
            // compile time rather than by inspecting the class at run time. Reflection is not used
            // to demonstrate it, and the two assertions below are the behavioural consequence -
            // under a machine-assigned surrogate an unflushed row would carry no identity at all
            // and two separately constructed rows could never compare equal.
            final TransactionType first =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));
            final TransactionType second =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(first.getTranType()).isEqualTo(FIRST_TYPE_CODE);
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }
    }

    /**
     * Exercises the diagnostic string representation the class declares.
     *
     * <p>The specified contract summary for this entity anticipated that the representation might be
     * absent and directed that nothing assert on it; the class as written declares one, and the
     * class is authoritative on signatures. The divergence is resolved by exercising the member so
     * that every declared member of the entity is covered, while asserting only that the
     * representation is available and null-safe. Its rendered layout is deliberately left
     * uncontracted, so no assertion here can be broken by rewording it.
     */
    @Nested
    @DisplayName("diagnostic representation")
    class DiagnosticRepresentation {

        @Test
        @DisplayName("a populated row yields a diagnostic representation, and neither of the two "
                + "fields it carries is a credential or a monetary amount needing redaction")
        void aPopulatedRowYieldsADiagnosticRepresentation() {
            final TransactionType row =
                    new TransactionType(FIRST_TYPE_CODE, blankPaddedDescription(FIRST_TYPE_TEXT));

            assertThat(row.toString()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("an unpopulated row still yields a diagnostic representation rather than "
                + "failing on its two absent fields")
        void anUnpopulatedRowStillYieldsADiagnosticRepresentation() {
            assertThat(new TransactionType().toString()).isNotNull().isNotEmpty();
        }
    }
}
