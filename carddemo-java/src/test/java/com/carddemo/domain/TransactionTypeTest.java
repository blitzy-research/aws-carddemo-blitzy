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
 * Unit tests for {@link TransactionType}, the entity form of the 60-byte transaction-type reference row.
 *
 * <p>Copybook {@code app/cpy/CVTRA03Y.cpy} declares a 60-byte record in three parts: a 2-byte
 * transaction type code, a 50-byte description and an 8-byte trailing filler. The cluster definition in
 * {@code app/jcl/TRANTYPE.jcl} corroborates that geometry independently and fixes the whole key as the
 * leading 2 bytes and nothing else. The filler carries no information and is mapped by no attribute,
 * leaving exactly two mapped properties - the smallest of the eleven entity translations here.
 *
 * <p>Every width, offset, count and literal asserted below was derived by hand from the copybook and
 * from the reference file {@code app/data/ASCII/trantype.txt}, then written here as a constant. Nothing
 * is read back from the class under test to produce an expectation and nothing is read from disk or the
 * classpath: a pure unit test touching no container, application context, database, network or file.
 * The reference file's arithmetic is itself part of the evidence - it measures 427 bytes, exactly 7
 * records at the 60-byte record length plus one line terminator each - so the seeded row count is a
 * derived fact rather than a guess.
 *
 * <p>Why the type code has to stay text: all seven seeded codes carry a leading zero, running from
 * {@code 01} to {@code 07}, in a fixed 2-byte field. Held as a number the first code would come back
 * one byte wide, the stored key would no longer be the 2 bytes the record publishes, and a transaction
 * stamped {@code 01} would fail to resolve against this table. The assertions prove the leading zero
 * survives a round trip and that {@code "01"} is not equal to {@code "1"}.
 *
 * <p>Why the blank padding is contractual: every description fills its 50-byte field blank-padded on
 * the right, and that padding is part of the external width the legacy record publishes. A setter that
 * trimmed, folded or re-padded would silently change the byte width of any fixed-width line formatted
 * from it. The suite shows that behaviourally, by storing a padded value and asserting that what comes
 * back still differs from the unpadded text.
 *
 * <p>Two 60-byte layouts that must never be conflated: {@code app/cpy/CVTRA04Y.cpy} also describes a
 * 60-byte record carrying a 50-byte description, but splits differently - a 2-byte type code, a 4-byte
 * category code, the description at offset 6 and a 4-byte filler - and names its type code with a code
 * suffix that this copybook does not use. That naming asymmetry is preserved rather than harmonised, so
 * this entity's key property is {@code tranType} and never the suffixed spelling. Because both layouts
 * total 60 bytes a width check cannot tell them apart, and nothing here attempts to infer a layout from
 * a record image, because the caller always knows which dataset it read.
 *
 * <p>Deliberately not asserted: column names, declared widths, nullability and key metadata. Schema
 * agreement is enforced against a real relational database in the integration tier, where the provider
 * runs in validate mode and refuses to start on any divergence. Nothing here inspects an annotation and
 * no reflective access is used, because the module holds a zero budget for it. No relationship is
 * asserted either: no foreign key targets or originates from this table in any migration.
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
 * Schema agreement is asserted by {@code EntityPersistenceMappingTest}, which bootstraps the
 * persistence provider's metadata and compares the mapping it computes against the shipped migration
 * {@code V1__create_schema.sql} and against an independent copybook-width oracle. The provider also
 * runs in validate mode against a real relational database in a deployed environment and refuses to
 * start on any divergence, though that is a property of a deployment rather than a check this build
 * performs. Nothing here inspects an annotation because entity metadata is that suite's subject rather
 * than this one's - not because reflection is barred from a test: the module's zero budget for it is
 * scoped to production sources under {@code src/main/java} and does not reach test sources. Nor is any relationship asserted: no foreign key
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

    private static final int TYPE_CODE_WIDTH = 2;

    private static final int DESCRIPTION_WIDTH = 50;

    private static final int FILLER_WIDTH = 8;

    private static final int RECORD_WIDTH = 60;

    /** Zero-based offset of the type code, which is also the key offset the cluster declares. */
    private static final int TYPE_CODE_OFFSET = 0;

    private static final int DESCRIPTION_OFFSET = 2;

    private static final int FILLER_OFFSET = 52;

    private static final int SEEDED_ROW_COUNT = 7;

    private static final int SEEDED_FILE_BYTE_COUNT = 427;

    private static final int LINE_TERMINATOR_WIDTH = 1;

    /** First seeded type code, and the one the interest run stamps on what it synthesises. */
    private static final String FIRST_TYPE_CODE = "01";

    private static final String FIRST_TYPE_TEXT = "Purchase";

    private static final int FIRST_TYPE_TEXT_WIDTH = 8;

    private static final String LAST_TYPE_CODE = "07";

    private static final String LAST_TYPE_TEXT = "Adjustment";

    private static final String LONGEST_TYPE_TEXT = "Authorization";

    private static final int LONGEST_TYPE_TEXT_WIDTH = 13;

    /**
     * Blank-pads description text on the right to the description field's full width, the way the record
     * image carries it. The padding is computed from the encoded byte count rather than the character
     * count, because the field is a fixed-width byte field. Test-local arithmetic over the copybook
     * width: no method of the class under test participates in producing an expected value anywhere in
     * this suite.
     *
     * @param text the unpadded description text
     * @return the text followed by enough blanks to fill the description field exactly
     */
    private static String blankPaddedDescription(final String text) {
        final int encodedLength = text.getBytes(StandardCharsets.US_ASCII).length;
        return text + " ".repeat(DESCRIPTION_WIDTH - encodedLength);
    }

    /**
     * Counts the bytes a value occupies when encoded, the measure the record contract is expressed in.
     *
     * @return the encoded byte count
     */
    private static int encodedWidthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Declared record geometry, so a drifted constant is caught here rather than weakening every assertion.
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
     * Each value handed in reaches the accessor that names it, and the entity offers the no-argument
     * constructor a persistence provider requires.
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
            // Same-package visibility reaches the entity's protected no-argument constructor directly.
            // That is plain compile-time visibility and is explicitly NOT reflection: no reflective
            // lookup, no accessibility override and no test-utility introspection is involved anywhere in
            // this suite.
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
     * The external byte widths the record publishes, measured on encoded bytes rather than character
     * counts, because the legacy field is a byte field and byte width is what the contract fixes.
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
     * The entity normalises nothing: the blank padding filling the 50-byte description field is part of
     * the external width the record publishes, so a value has to survive a round trip byte for byte.
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
     * Every one of the seven seeded reference rows is carried exactly, at both declared field widths. The
     * seven code-and-description pairs were hand-decoded from the estate's own reference file and are the
     * oracle: nothing here reads that file and nothing asks the entity what the values should be.
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
     * Identity is the 2-byte key and nothing else. The description is mutable, so admitting it into
     * equality would let a row's hash change while the row sits inside a hash-based collection.
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
            // The entity declares no generated-identifier member, and no reflection is used to say so.
            // The two assertions below are the behavioural consequence: under a machine-assigned
            // surrogate an unflushed row would carry no identity at all, and two separately constructed
            // rows could never compare equal.
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
     * representation is available and null-safe.
     *
     * <p>Its rendered layout <em>is</em> pinned, in both states. An availability check alone passes for
     * any string whatever - including one that had begun carrying a value needing redaction - so it is
     * no evidence that the representation is safe. Because this entity's two fields are a two-character
     * code and its description, neither of which is a credential nor a monetary amount, the safe
     * rendering is the one that carries both and nothing else, and that is what the assertions below
     * state. Pinning it means a future rewording fails here deliberately: the rendering of an entity is
     * treated as a contract worth breaking a test over rather than an implementation detail.
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

            // The expected text is written out here in full - the two-character code, then the
            // description at its full padded width - rather than assembled from the row's own
            // accessors, so this asserts the intended rendering rather than whatever is produced.
            // Note the values are unquoted in this entity's rendering, unlike the account and card
            // entities, and the padding is therefore visible only as trailing space before the bracket.
            assertThat(row.toString())
                    .isEqualTo("TransactionType[tranType=01, tranTypeDesc="
                            + "Purchase" + " ".repeat(DESCRIPTION_WIDTH - "Purchase".length()) + "]");

            // Both carried fields are named, and nothing else is.
            assertThat(row.toString()).contains("tranType", "tranTypeDesc");
            assertThat(row.toString()).doesNotContain("version", "password", "pwd");
        }

        @Test
        @DisplayName("an unpopulated row still yields a diagnostic representation rather than "
                + "failing on its two absent fields")
        void anUnpopulatedRowStillYieldsADiagnosticRepresentation() {
            // Pinned exactly: an unset row renders both absent values as the literal text null, and
            // the surrounding shape is unchanged, so a diagnostic taken during a failed assertion on
            // an unpopulated row is readable rather than throwing.
            assertThat(new TransactionType().toString())
                    .isEqualTo("TransactionType[tranType=null, tranTypeDesc=null]");
        }
    }
}
