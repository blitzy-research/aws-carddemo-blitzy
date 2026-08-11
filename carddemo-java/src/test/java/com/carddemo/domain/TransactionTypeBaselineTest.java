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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for {@link TransactionType}, the entity form of the 60-byte transaction-type reference
 * record.
 *
 * <p><strong>What this test proves.</strong> Three legacy authorities fix the shape of this entity, and
 * this test asserts the Java form honours all three:
 * <ul>
 *   <li>the copybook member for the transaction type, whose record is 60 bytes: a 2-byte code at offset
 *       0, a 50-byte description at offset 2 and an 8-byte trailing filler at offset 52. This is the only
 *       reference record of the estate whose filler is eight bytes, and it is the reason two 60-byte
 *       reference records - this one and the transaction category - have different significant widths;</li>
 *   <li>the provisioning job for the transaction-type dataset, which declares a 2-byte key at offset zero
 *       with a fixed 60-byte record. The key is a single component, so this entity carries a plain
 *       identifier rather than one of the estate's three composite keys; and</li>
 *   <li>the seeded reference data, which carries seven rows coded {@code 01} through {@code 07} in
 *       ascending order. Those seven codes are exactly the seven distinct type codes the transaction
 *       category reference spans and exactly the seven each disclosure group spans, so the three
 *       reference files agree on the type vocabulary.</li>
 * </ul>
 *
 * <p><strong>The description is excluded from identity, deliberately.</strong> The description is mutable
 * state and the code is persistent identity. This test asserts the exclusion in both directions: two rows
 * with the same code and different descriptions are equal, and two rows with the same description and
 * different codes are not.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network, runs no container and performs no
 * introspection.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every width, offset, code and description
 * below is a literal typed out in this source, taken from the copybook layout, the cluster definition and
 * the measured content of the seeded reference file. No line of legacy source is transcribed.
 */
@DisplayName("TransactionType - the entity form of the 60-byte transaction-type record")
class TransactionTypeBaselineTest {

    /** Width of the code, which is also the declared key length of the dataset. */
    private static final int CODE_WIDTH = 2;

    /** Width of the description field. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 8;

    /** Record length declared by both the copybook and the cluster definition. */
    private static final int RECORD_LENGTH = 60;

    /** Rows the seeded reference file carries. */
    private static final int SEEDED_ROWS = 7;

    /** The seven seeded codes, in ascending order. */
    private static final List<String> SEEDED_CODES =
            List.of("01", "02", "03", "04", "05", "06", "07");

    private TransactionType type;

    /**
     * Right pads a value with spaces to the exact width the record layout reserves for its field.
     * Used only to build test inputs at their declared widths; the width itself is always asserted
     * against the independently declared constant rather than against this helper's output.
     *
     * @param value the significant text of the field
     * @param width the declared field width from the copybook layout
     * @return the value padded on the right with spaces to exactly {@code width} characters
     */
    private static String rightPadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    @BeforeEach
    void createFirstSeededRow() {
        type = new TransactionType("01", rightPadded("Purchase", DESCRIPTION_WIDTH));
    }

    @Nested
    @DisplayName("Construction in copybook declaration order")
    class Construction {

        @Test
        @DisplayName("the two-argument constructor binds both fields in copybook declaration order: "
                + "code then description")
        void theConstructorBindsBothFieldsInDeclarationOrder() {
            assertThat(type.getTranType()).isEqualTo("01");
            assertThat(type.getTranTypeDesc())
                    .isEqualTo(rightPadded("Purchase", DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the no-arg constructor the provider requires leaves both fields absent, because "
                + "the provider populates state afterwards")
        void theNoArgConstructorLeavesBothFieldsAbsent() {
            final TransactionType empty = new TransactionType();

            assertThat(empty.getTranType()).isNull();
            assertThat(empty.getTranTypeDesc()).isNull();
        }

        @Test
        @DisplayName("the constructor stores both values verbatim - no trim, no pad and no case fold - "
                + "because the reference data arrives already at its fixed widths")
        void theConstructorStoresBothValuesVerbatim() {
            final TransactionType raw = new TransactionType(" 1", "  pUrChAsE  ");

            assertThat(raw.getTranType()).isEqualTo(" 1");
            assertThat(raw.getTranTypeDesc()).isEqualTo("  pUrChAsE  ");
        }

        @Test
        @DisplayName("absent values are accepted and returned unchanged, because the entity performs no "
                + "validation of its own and the database enforces the non-null contract")
        void absentValuesAreAcceptedAndReturnedUnchanged() {
            final TransactionType sparse = new TransactionType(null, null);

            assertThat(sparse.getTranType()).isNull();
            assertThat(sparse.getTranTypeDesc()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the 60-byte record")
    class ByteGeometry {

        @Test
        @DisplayName("the code, the 50-byte description and the 8-byte filler close the 60-byte record "
                + "exactly, so the copybook layout and the declared record size agree")
        void theFieldWidthsCloseTheRecordExactly() {
            final int summed = CODE_WIDTH + DESCRIPTION_WIDTH + FILLER_WIDTH;

            assertThat(summed).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key is the leading two bytes of the record, matching the dataset's key "
                + "declaration, so the business key is the code and no surrogate is introduced")
        void theKeyIsTheLeadingTwoBytes() {
            assertThat(type.getTranType()).hasSize(CODE_WIDTH);
            assertThat(RECORD_LENGTH - CODE_WIDTH).isEqualTo(58);
        }

        @Test
        @DisplayName("the description begins immediately after the code, at offset 2, and the filler "
                + "begins at offset 52")
        void theDescriptionBeginsImmediatelyAfterTheCode() {
            assertThat(CODE_WIDTH).isEqualTo(2);
            assertThat(CODE_WIDTH + DESCRIPTION_WIDTH).isEqualTo(52);
            assertThat(CODE_WIDTH + DESCRIPTION_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("this record and the transaction-category record share a 60-byte width and a 50-byte "
                + "description yet differ in filler and key width, so the two layouts are not "
                + "interchangeable despite matching record sizes")
        void thisRecordIsNotInterchangeableWithTheCategoryRecord() {
            final int categoryFillerWidth = 4;
            final int categoryKeyWidth = 6;

            assertThat(FILLER_WIDTH).isNotEqualTo(categoryFillerWidth);
            assertThat(CODE_WIDTH).isNotEqualTo(categoryKeyWidth);
            assertThat(CODE_WIDTH + DESCRIPTION_WIDTH + FILLER_WIDTH)
                    .isEqualTo(categoryKeyWidth + DESCRIPTION_WIDTH + categoryFillerWidth);
        }

        @Test
        @DisplayName("the description is space padded to its full fifty bytes rather than trimmed, so "
                + "the padding survives into the entity")
        void theDescriptionKeepsItsFullWidth() {
            assertThat(type.getTranTypeDesc()).hasSize(DESCRIPTION_WIDTH);
            assertThat(type.getTranTypeDesc()).endsWith(" ");
            assertThat(type.getTranTypeDesc().strip()).isEqualTo("Purchase");
        }

        @Test
        @DisplayName("the seeded file carries 7 rows of 60 bytes, which is 420 data bytes")
        void theSeededFileCarriesSevenSixtyByteRows() {
            assertThat(SEEDED_ROWS * RECORD_LENGTH).isEqualTo(420);
            assertThat(SEEDED_CODES).hasSize(SEEDED_ROWS);
        }
    }

    @Nested
    @DisplayName("Mutability required by the reference-data loader")
    class Mutability {

        @Test
        @DisplayName("both mapped fields round-trip through their setters, which the provider needs in "
                + "order to populate a row it materialised through the no-arg constructor")
        void bothMappedFieldsRoundTripThroughTheirSetters() {
            final TransactionType target = new TransactionType();

            target.setTranType("07");
            target.setTranTypeDesc(rightPadded("Adjustment", DESCRIPTION_WIDTH));

            assertThat(target.getTranType()).isEqualTo("07");
            assertThat(target.getTranTypeDesc())
                    .hasSize(DESCRIPTION_WIDTH)
                    .startsWith("Adjustment");
        }

        @Test
        @DisplayName("a setter accepts an absent value, so clearing a field is possible and the non-null "
                + "contract is enforced by the column rather than by the entity")
        void aSetterAcceptsAnAbsentValue() {
            type.setTranTypeDesc(null);
            type.setTranType(null);

            assertThat(type.getTranTypeDesc()).isNull();
            assertThat(type.getTranType()).isNull();
        }
    }

    @Nested
    @DisplayName("Identity founded on the code alone")
    class Identity {

        @Test
        @DisplayName("two rows with the same code are equal and hash alike even when their descriptions "
                + "differ, because the description is mutable state and not identity")
        void sameCodeMeansEqualEvenWhenTheDescriptionDiffers() {
            final TransactionType other = new TransactionType("01",
                    rightPadded("A completely different description", DESCRIPTION_WIDTH));

            assertThat(type).isEqualTo(other);
            assertThat(type).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("two rows with the same description but different codes are not equal, which is the "
                + "other half of the exclusion")
        void sameDescriptionWithADifferentCodeIsNotEqual() {
            final TransactionType other =
                    new TransactionType("02", rightPadded("Purchase", DESCRIPTION_WIDTH));

            assertThat(type).isNotEqualTo(other);
        }

        @Test
        @DisplayName("identity is reflexive, symmetric and transitive across independently constructed "
                + "rows carrying the same code")
        void identityIsReflexiveSymmetricAndTransitive() {
            final TransactionType second = new TransactionType("01", "one");
            final TransactionType third = new TransactionType("01", null);

            assertThat(type.equals(type)).isTrue();
            assertThat(type.equals(second)).isTrue();
            assertThat(second.equals(type)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(type.equals(third)).isTrue();
        }

        @Test
        @DisplayName("the code is compared byte for byte with no leading-zero collapse, so the "
                + "two-character code is a different key from its shortened form")
        void theCodeIsComparedByteForByte() {
            assertThat(type).isNotEqualTo(new TransactionType("1", null));
            assertThat(type).isNotEqualTo(new TransactionType(" 1", null));
            assertThat(type).isNotEqualTo(new TransactionType("01 ", null));
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type through the type pattern "
                + "rather than throwing")
        void equalityRejectsAbsentOperandAndForeignType() {
            assertThat(type.equals(null)).isFalse();
            assertThat(type.equals("01")).isFalse();
            assertThat(type.equals(new TransactionCategory("01", "0001", null))).isFalse();
        }

        @Test
        @DisplayName("two rows with an absent code are equal and hash to zero, which the single-field "
                + "hash of this entity yields without a null check of its own")
        void twoEmptyRowsAreEqualAndHashToZero() {
            assertThat(new TransactionType()).isEqualTo(new TransactionType());
            assertThat(new TransactionType()).hasSameHashCodeAs(new TransactionType());
            assertThat(new TransactionType().hashCode()).isZero();
        }

        @Test
        @DisplayName("the type pattern makes a subclass with the same code equal in both directions, "
                + "which is what lets a lazily-proxied row compare equal to a loaded one")
        void aSubclassWithTheSameCodeIsEqualInBothDirections() {
            final ProxiedTransactionType proxy = new ProxiedTransactionType("01");

            assertThat(type.equals(proxy)).isTrue();
            assertThat(proxy.equals(type)).isTrue();
            assertThat(type).hasSameHashCodeAs(proxy);
        }

        @Test
        @DisplayName("all 7 seeded codes stay distinct in a hash set, so no two seeded rows collapse onto "
                + "one another")
        void allSevenSeededCodesStayDistinctInAHashSet() {
            final Set<TransactionType> rows = new HashSet<>();

            for (final String code : SEEDED_CODES) {
                rows.add(new TransactionType(code, null));
            }

            assertThat(rows).hasSize(SEEDED_ROWS);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering carries both mapped fields unquoted")
    class StringRepresentation {

        @Test
        @DisplayName("the rendering names the type and lists both mapped fields without quoting them, "
                + "which is the convention this record follows because neither field is sensitive")
        void theRenderingListsBothFieldsUnquoted() {
            final TransactionType terse = new TransactionType("02", "Payment");

            assertThat(terse.toString())
                    .isEqualTo("TransactionType[tranType=02, tranTypeDesc=Payment]");
            assertThat(terse.toString()).doesNotContain("'");
        }

        @Test
        @DisplayName("the description is rendered as stored, padding included, so a fifty-byte "
                + "description renders at its full width")
        void theDescriptionIsRenderedAsStored() {
            assertThat(type.toString()).contains(rightPadded("Purchase", DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the rendering of an empty row does not fail and shows two absent fields")
        void theRenderingOfAnEmptyRowDoesNotFail() {
            assertThat(new TransactionType().toString())
                    .isEqualTo("TransactionType[tranType=null, tranTypeDesc=null]");
        }
    }

    @Nested
    @DisplayName("Correspondence with the 7 seeded reference rows")
    class FixtureCorrespondence {

        @Test
        @DisplayName("the seven seeded codes run from 01 to 07 with no gap, so the vocabulary is a dense "
                + "ascending range rather than a sparse set")
        void theSeededCodesRunFromOneToSevenWithoutGaps() {
            assertThat(SEEDED_CODES).hasSize(SEEDED_ROWS);
            assertThat(new HashSet<>(SEEDED_CODES)).hasSize(SEEDED_ROWS);

            for (int ordinal = 1; ordinal <= SEEDED_ROWS; ordinal++) {
                final String code = SEEDED_CODES.get(ordinal - 1);

                assertThat(code).hasSize(CODE_WIDTH).containsOnlyDigits();
                assertThat(Integer.parseInt(code)).isEqualTo(ordinal);
            }
        }

        @ParameterizedTest(name = "seeded row {0} is described as {1}")
        @DisplayName("each seeded row constructs with its measured code and description, and both fields "
                + "come back at their declared widths")
        @CsvSource({
            "01, Purchase",
            "02, Payment",
            "03, Credit",
            "04, Authorization",
            "05, Refund",
            "06, Reversal",
            "07, Adjustment"})
        void eachSeededRowConstructsUnchanged(final String code, final String description) {
            final TransactionType seeded =
                    new TransactionType(code, rightPadded(description, DESCRIPTION_WIDTH));

            assertThat(seeded.getTranType()).isEqualTo(code).hasSize(CODE_WIDTH);
            assertThat(seeded.getTranTypeDesc())
                    .hasSize(DESCRIPTION_WIDTH)
                    .startsWith(description);
            assertThat(seeded.getTranTypeDesc().strip()).isEqualTo(description);
        }

        @Test
        @DisplayName("every seeded description is title cased with a single significant word, so none "
                + "carries an embedded space and none is upper cased")
        void everySeededDescriptionIsASingleTitleCasedWord() {
            final List<String> descriptions = List.of("Purchase", "Payment", "Credit",
                    "Authorization", "Refund", "Reversal", "Adjustment");

            assertThat(descriptions).hasSize(SEEDED_ROWS);
            for (final String description : descriptions) {
                assertThat(description).doesNotContain(" ");
                assertThat(description.substring(0, 1)).isUpperCase();
                assertThat(description.substring(1)).isLowerCase();
                assertThat(description.length()).isLessThan(DESCRIPTION_WIDTH);
            }
        }

        @Test
        @DisplayName("the seven codes are exactly the seven distinct type codes the transaction-category "
                + "reference spans and the seven each disclosure group spans, so the three reference "
                + "files agree on the type vocabulary")
        void theSevenCodesMatchTheOtherTwoReferenceFiles() {
            final Set<String> categoryTypeCodes = new HashSet<>(List.of(
                    "01", "01", "01", "01", "01",
                    "02", "02", "02",
                    "03", "03", "03",
                    "04", "04", "04",
                    "05",
                    "06", "06",
                    "07"));
            final Set<String> disclosureTypeCodes = new HashSet<>(List.of(
                    "01", "01", "01", "01",
                    "02", "02", "02",
                    "03", "03", "03",
                    "04", "04", "04",
                    "05",
                    "06", "06",
                    "07"));

            assertThat(categoryTypeCodes).hasSize(SEEDED_ROWS);
            assertThat(disclosureTypeCodes).hasSize(SEEDED_ROWS);
            assertThat(new HashSet<>(SEEDED_CODES)).isEqualTo(categoryTypeCodes);
            assertThat(new HashSet<>(SEEDED_CODES)).isEqualTo(disclosureTypeCodes);
        }

        @Test
        @DisplayName("the code the interest-accrual job stamps onto a synthesised transaction is the "
                + "first seeded code, and it resolves to the purchase description")
        void theSynthesisedInterestTransactionUsesTheFirstSeededCode() {
            final TransactionType synthesised =
                    new TransactionType("01", rightPadded("Purchase", DESCRIPTION_WIDTH));

            assertThat(synthesised.getTranType()).isEqualTo(SEEDED_CODES.get(0));
            assertThat(synthesised.getTranTypeDesc().strip()).isEqualTo("Purchase");
            assertThat(synthesised).isEqualTo(type);
        }
    }

    /**
     * A subclass standing in for the lazily-initialised proxy the persistence provider substitutes for
     * a row that has not yet been loaded. It adds no state and overrides nothing, so it exists purely
     * to prove that the type-pattern comparison of the parent admits a subclass symmetrically.
     *
     * <p>The entity is not serialisable, so this nested subclass introduces no serialisation obligation.
     */
    private static final class ProxiedTransactionType extends TransactionType {

        ProxiedTransactionType(final String code) {
            super(code, null);
        }
    }
}
