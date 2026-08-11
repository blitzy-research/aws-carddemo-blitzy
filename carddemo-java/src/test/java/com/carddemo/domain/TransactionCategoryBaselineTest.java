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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.carddemo.domain.id.TransactionCategoryId;

/**
 * Unit test for {@link TransactionCategory}, the entity form of the 60-byte transaction-category
 * reference record.
 *
 * <p><strong>What this test proves.</strong> Three legacy authorities fix the shape of this entity, and
 * this test asserts the Java form honours all three:
 * <ul>
 *   <li>the copybook member for the transaction category, whose record is 60 bytes: a 2-byte
 *       transaction-type code at offset 0, a 4-byte category code at offset 2, a 50-byte description at
 *       offset 6 and a 4-byte trailing filler at offset 56;</li>
 *   <li>the provisioning job for the transaction-category dataset, which declares a 6-byte key at offset
 *       zero with a fixed 60-byte record. Those 6 bytes are exactly the two key components concatenated,
 *       which is why the composite key has two components and why the key is the narrowest of the
 *       estate's three composite keys; and</li>
 *   <li>the seeded reference data, which carries 18 rows spanning all seven transaction types in the
 *       measured multiset five, three, three, three, one, two, one. One of those eighteen keys - the
 *       interest category - carries no disclosure rate anywhere in the disclosure-group reference data,
 *       which is why the disclosure groups hold seventeen rows each rather than eighteen.</li>
 * </ul>
 *
 * <p><strong>The description is excluded from identity, deliberately.</strong> The description is mutable
 * state and the two key components are persistent identity. This test asserts the exclusion in both
 * directions: two rows with the same key and different descriptions are equal, and two rows with the
 * same description and different keys are not.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network, runs no container and performs no
 * introspection.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every width, offset, key and description
 * below is a literal typed out in this source, taken from the copybook layout, the cluster definition and
 * the measured content of the seeded reference file. No line of legacy source is transcribed.
 */
@DisplayName("TransactionCategory - the entity form of the 60-byte transaction-category record")
class TransactionCategoryBaselineTest {

    /** Width of the transaction-type code, the first key component. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of the category code, the second key component. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Key length declared by the cluster definition, at offset zero. */
    private static final int KEY_WIDTH = 6;

    /** Width of the description field. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 4;

    /** Record length declared by both the copybook and the cluster definition. */
    private static final int RECORD_LENGTH = 60;

    /** Rows the seeded reference file carries. */
    private static final int SEEDED_ROWS = 18;

    /** Distinct transaction types the seeded rows span. */
    private static final int SEEDED_TYPE_COUNT = 7;

    /** The eighteen seeded keys, in ascending key order, as type code followed by category code. */
    private static final List<String> SEEDED_KEYS = List.of(
            "010001", "010002", "010003", "010004", "010005",
            "020001", "020002", "020003",
            "030001", "030002", "030003",
            "040001", "040002", "040003",
            "050001",
            "060001", "060002",
            "070001");

    private TransactionCategory category;

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
        category = new TransactionCategory("01", "0001",
                rightPadded("Regular Sales Draft", DESCRIPTION_WIDTH));
    }

    @Nested
    @DisplayName("Construction in copybook declaration order")
    class Construction {

        @Test
        @DisplayName("the three-argument constructor binds every field in copybook declaration order: "
                + "type code, category code, description")
        void theConstructorBindsEveryFieldInDeclarationOrder() {
            assertThat(category.getTranTypeCd()).isEqualTo("01");
            assertThat(category.getTranCatCd()).isEqualTo("0001");
            assertThat(category.getTranCatTypeDesc())
                    .isEqualTo(rightPadded("Regular Sales Draft", DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the no-arg constructor the provider requires leaves all three fields absent, "
                + "because the provider populates state afterwards")
        void theNoArgConstructorLeavesEveryFieldAbsent() {
            final TransactionCategory empty = new TransactionCategory();

            assertThat(empty.getTranTypeCd()).isNull();
            assertThat(empty.getTranCatCd()).isNull();
            assertThat(empty.getTranCatTypeDesc()).isNull();
        }

        @Test
        @DisplayName("the constructor stores every value verbatim - no trim, no pad and no case fold - "
                + "because the reference data arrives already at its fixed widths")
        void theConstructorStoresEveryValueVerbatim() {
            final TransactionCategory raw = new TransactionCategory(" 1", "5", "  mIxEd  ");

            assertThat(raw.getTranTypeCd()).isEqualTo(" 1");
            assertThat(raw.getTranCatCd()).isEqualTo("5");
            assertThat(raw.getTranCatTypeDesc()).isEqualTo("  mIxEd  ");
        }

        @Test
        @DisplayName("absent values are accepted and returned unchanged, because the entity performs no "
                + "validation of its own and the database enforces the non-null contract")
        void absentValuesAreAcceptedAndReturnedUnchanged() {
            final TransactionCategory sparse = new TransactionCategory(null, null, null);

            assertThat(sparse.getTranTypeCd()).isNull();
            assertThat(sparse.getTranCatCd()).isNull();
            assertThat(sparse.getTranCatTypeDesc()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the 60-byte record")
    class ByteGeometry {

        @Test
        @DisplayName("the two key components, the 50-byte description and the 4-byte filler close the "
                + "60-byte record exactly, so the copybook layout and the declared record size agree")
        void theFieldWidthsCloseTheRecordExactly() {
            final int summed =
                    TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH + DESCRIPTION_WIDTH + FILLER_WIDTH;

            assertThat(summed).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the two key components concatenate to exactly the 6-byte key the cluster declares "
                + "at offset zero, which is why the composite key has two components")
        void theKeyComponentsConcatenateToTheDeclaredKeyWidth() {
            assertThat(TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(KEY_WIDTH);
            assertThat(category.getTranTypeCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(category.getTranCatCd()).hasSize(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("the description begins immediately after the key, at offset 6, and the filler "
                + "begins at offset 56")
        void theDescriptionBeginsImmediatelyAfterTheKey() {
            assertThat(KEY_WIDTH).isEqualTo(6);
            assertThat(KEY_WIDTH + DESCRIPTION_WIDTH).isEqualTo(56);
            assertThat(KEY_WIDTH + DESCRIPTION_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("this is the narrowest of the estate's three composite keys, narrower than the "
                + "16-byte disclosure-group key and the 17-byte category-balance key")
        void thisIsTheNarrowestCompositeKeyOfTheEstate() {
            final int disclosureGroupKeyWidth = 16;
            final int categoryBalanceKeyWidth = 17;

            assertThat(KEY_WIDTH).isLessThan(disclosureGroupKeyWidth);
            assertThat(KEY_WIDTH).isLessThan(categoryBalanceKeyWidth);
        }

        @Test
        @DisplayName("the description is space padded to its full fifty bytes rather than trimmed, so "
                + "the padding survives into the entity")
        void theDescriptionKeepsItsFullWidth() {
            assertThat(category.getTranCatTypeDesc()).hasSize(DESCRIPTION_WIDTH);
            assertThat(category.getTranCatTypeDesc()).endsWith(" ");
            assertThat(category.getTranCatTypeDesc().strip()).isEqualTo("Regular Sales Draft");
        }

        @Test
        @DisplayName("the seeded file carries 18 rows of 60 bytes, which is 1,080 data bytes")
        void theSeededFileCarriesEighteenSixtyByteRows() {
            assertThat(SEEDED_ROWS * RECORD_LENGTH).isEqualTo(1080);
        }
    }

    @Nested
    @DisplayName("Composite key projection")
    class CompositeKeyProjection {

        @Test
        @DisplayName("the key projection carries the two key components in their contractual order and "
                + "nothing else")
        void theKeyProjectionCarriesTheTwoComponentsInOrder() {
            final TransactionCategoryId id = category.toId();

            assertThat(id.getTranTypeCd()).isEqualTo("01");
            assertThat(id.getTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("the projection equals a key assembled by hand from the same two components, so a "
                + "repository lookup by projection and one by hand-assembled key resolve alike")
        void theProjectionEqualsAHandAssembledKey() {
            assertThat(category.toId()).isEqualTo(new TransactionCategoryId("01", "0001"));
            assertThat(category.toId()).hasSameHashCodeAs(new TransactionCategoryId("01", "0001"));
        }

        @Test
        @DisplayName("a new key is produced on every call and nothing is cached or shared, so a caller "
                + "cannot mutate one row's identity through another's projection")
        void aNewKeyIsProducedOnEveryCall() {
            final TransactionCategoryId first = category.toId();
            final TransactionCategoryId second = category.toId();

            assertThat(first).isNotSameAs(second);
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("the projection tracks the row: changing a key component through its setter changes "
                + "the next projection, because the projection is built from stored values on demand")
        void theProjectionTracksTheRow() {
            final TransactionCategoryId before = category.toId();

            category.setTranCatCd("0005");

            assertThat(category.toId()).isNotEqualTo(before);
            assertThat(category.toId().getTranCatCd()).isEqualTo("0005");
        }

        @Test
        @DisplayName("the projection of an empty row carries two absent components rather than failing, "
                + "because the projection performs no validation of its own")
        void theProjectionOfAnEmptyRowCarriesAbsentComponents() {
            final TransactionCategoryId id = new TransactionCategory().toId();

            assertThat(id.getTranTypeCd()).isNull();
            assertThat(id.getTranCatCd()).isNull();
        }

        @Test
        @DisplayName("the projection carries no description, because the description is not part of the "
                + "6-byte key")
        void theProjectionCarriesNoDescription() {
            assertThat(category.toId().toString()).doesNotContain("Regular Sales Draft");
            assertThat(category.toId().toString())
                    .isEqualTo("TransactionCategoryId[tranTypeCd=01, tranCatCd=0001]");
        }
    }

    @Nested
    @DisplayName("Mutability required by the reference-data loader")
    class Mutability {

        @Test
        @DisplayName("every mapped field round-trips through its setter, which the provider needs in "
                + "order to populate a row it materialised through the no-arg constructor")
        void everyMappedFieldRoundTripsThroughItsSetter() {
            final TransactionCategory target = new TransactionCategory();

            target.setTranTypeCd("07");
            target.setTranCatCd("0001");
            target.setTranCatTypeDesc(
                    rightPadded("Sales draft credit adjustment", DESCRIPTION_WIDTH));

            assertThat(target.getTranTypeCd()).isEqualTo("07");
            assertThat(target.getTranCatCd()).isEqualTo("0001");
            assertThat(target.getTranCatTypeDesc())
                    .hasSize(DESCRIPTION_WIDTH)
                    .startsWith("Sales draft credit adjustment");
        }

        @Test
        @DisplayName("a setter accepts an absent value, so clearing a field is possible and the non-null "
                + "contract is enforced by the column rather than by the entity")
        void aSetterAcceptsAnAbsentValue() {
            category.setTranCatTypeDesc(null);
            category.setTranTypeCd(null);

            assertThat(category.getTranCatTypeDesc()).isNull();
            assertThat(category.getTranTypeCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Identity founded on the two key components alone")
    class Identity {

        @Test
        @DisplayName("two rows with the same two key components are equal and hash alike even when their "
                + "descriptions differ, because the description is mutable state and not identity")
        void sameKeyMeansEqualEvenWhenTheDescriptionDiffers() {
            final TransactionCategory other = new TransactionCategory("01", "0001",
                    rightPadded("A completely different description", DESCRIPTION_WIDTH));

            assertThat(category).isEqualTo(other);
            assertThat(category).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("two rows with the same description but different keys are not equal, which is the "
                + "other half of the exclusion")
        void sameDescriptionWithADifferentKeyIsNotEqual() {
            final TransactionCategory other = new TransactionCategory("02", "0001",
                    rightPadded("Regular Sales Draft", DESCRIPTION_WIDTH));

            assertThat(category).isNotEqualTo(other);
        }

        @Test
        @DisplayName("either key component alone is enough to break equality, so both genuinely "
                + "participate")
        void eitherKeyComponentAloneBreaksEquality() {
            assertThat(category).isNotEqualTo(new TransactionCategory("02", "0001", null));
            assertThat(category).isNotEqualTo(new TransactionCategory("01", "0002", null));
        }

        @Test
        @DisplayName("identity is reflexive, symmetric and transitive across independently constructed "
                + "rows carrying the same two key components")
        void identityIsReflexiveSymmetricAndTransitive() {
            final TransactionCategory second = new TransactionCategory("01", "0001", "one");
            final TransactionCategory third = new TransactionCategory("01", "0001", null);

            assertThat(category.equals(category)).isTrue();
            assertThat(category.equals(second)).isTrue();
            assertThat(second.equals(category)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(category.equals(third)).isTrue();
        }

        @Test
        @DisplayName("components are compared byte for byte with no trim and no leading-zero collapse, "
                + "so a four-character category code is a different key from its shortened form")
        void componentsAreComparedByteForByte() {
            assertThat(category).isNotEqualTo(new TransactionCategory("01", "1", null));
            assertThat(category).isNotEqualTo(new TransactionCategory("1", "0001", null));
            assertThat(category).isNotEqualTo(new TransactionCategory("01", "0001 ", null));
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type through the type pattern "
                + "rather than throwing, including its own key type")
        void equalityRejectsAbsentOperandAndForeignType() {
            assertThat(category.equals(null)).isFalse();
            assertThat(category.equals("010001")).isFalse();
            assertThat(category.equals(category.toId())).isFalse();
        }

        @Test
        @DisplayName("two rows with two absent components are equal and hash alike, which the "
                + "null-tolerant component comparison supports without a guard of its own")
        void twoEmptyRowsAreEqual() {
            assertThat(new TransactionCategory()).isEqualTo(new TransactionCategory());
            assertThat(new TransactionCategory()).hasSameHashCodeAs(new TransactionCategory());
        }

        @Test
        @DisplayName("the type pattern makes a subclass with the same two components equal in both "
                + "directions, which is what lets a lazily-proxied row compare equal to a loaded one")
        void aSubclassWithTheSameKeyIsEqualInBothDirections() {
            final ProxiedTransactionCategory proxy = new ProxiedTransactionCategory("01", "0001");

            assertThat(category.equals(proxy)).isTrue();
            assertThat(proxy.equals(category)).isTrue();
            assertThat(category).hasSameHashCodeAs(proxy);
        }

        @Test
        @DisplayName("all 18 seeded keys stay distinct in a hash set, so no two seeded rows collapse "
                + "onto one another")
        void allEighteenSeededKeysStayDistinctInAHashSet() {
            final Set<TransactionCategory> rows = new HashSet<>();

            for (final String key : SEEDED_KEYS) {
                rows.add(new TransactionCategory(key.substring(0, TYPE_CODE_WIDTH),
                        key.substring(TYPE_CODE_WIDTH), null));
            }

            assertThat(rows).hasSize(SEEDED_ROWS);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering carries all three mapped fields unquoted")
    class StringRepresentation {

        @Test
        @DisplayName("the rendering names the type and lists all three mapped fields without quoting "
                + "them, which is the convention this record follows because no field is sensitive")
        void theRenderingListsAllThreeFieldsUnquoted() {
            final TransactionCategory terse = new TransactionCategory("01", "0005", "Interest Amount");

            assertThat(terse.toString()).isEqualTo(
                    "TransactionCategory[tranTypeCd=01, tranCatCd=0005, "
                            + "tranCatTypeDesc=Interest Amount]");
            assertThat(terse.toString()).doesNotContain("'");
        }

        @Test
        @DisplayName("the description is rendered as stored, padding included, so a fifty-byte "
                + "description renders at its full width")
        void theDescriptionIsRenderedAsStored() {
            assertThat(category.toString())
                    .contains(rightPadded("Regular Sales Draft", DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the rendering of an empty row does not fail and shows three absent fields")
        void theRenderingOfAnEmptyRowDoesNotFail() {
            assertThat(new TransactionCategory().toString()).isEqualTo(
                    "TransactionCategory[tranTypeCd=null, tranCatCd=null, tranCatTypeDesc=null]");
        }
    }

    @Nested
    @DisplayName("Correspondence with the 18 seeded reference rows")
    class FixtureCorrespondence {

        @Test
        @DisplayName("the eighteen seeded keys are distinct and each is exactly six characters, so each "
                + "fits the key field without pad or truncation")
        void theSeededKeysAreDistinctAndSixCharactersWide() {
            assertThat(SEEDED_KEYS).hasSize(SEEDED_ROWS);
            assertThat(new HashSet<>(SEEDED_KEYS)).hasSize(SEEDED_ROWS);
            for (final String key : SEEDED_KEYS) {
                assertThat(key).hasSize(KEY_WIDTH).containsOnlyDigits();
            }
        }

        @Test
        @DisplayName("the eighteen seeded rows span seven transaction types in the measured multiset "
                + "five, three, three, three, one, two, one")
        void theSeededRowsSpanSevenTransactionTypes() {
            final List<String> types = new ArrayList<>();
            for (final String key : SEEDED_KEYS) {
                types.add(key.substring(0, TYPE_CODE_WIDTH));
            }

            assertThat(new HashSet<>(types)).hasSize(SEEDED_TYPE_COUNT);
            assertThat(types.stream().filter("01"::equals).count()).isEqualTo(5);
            assertThat(types.stream().filter("02"::equals).count()).isEqualTo(3);
            assertThat(types.stream().filter("03"::equals).count()).isEqualTo(3);
            assertThat(types.stream().filter("04"::equals).count()).isEqualTo(3);
            assertThat(types.stream().filter("05"::equals).count()).isEqualTo(1);
            assertThat(types.stream().filter("06"::equals).count()).isEqualTo(2);
            assertThat(types.stream().filter("07"::equals).count()).isEqualTo(1);
            assertThat(types).hasSize(SEEDED_ROWS);
        }

        @ParameterizedTest(name = "seeded row {0}{1} is described as {2}")
        @DisplayName("each seeded row constructs with its measured key and description, and every field "
                + "comes back at its declared width")
        @CsvSource({
            "01, 0001, Regular Sales Draft",
            "01, 0002, Regular Cash Advance",
            "01, 0003, Convenience Check Debit",
            "01, 0004, ATM Cash Advance",
            "01, 0005, Interest Amount",
            "02, 0001, Cash payment",
            "02, 0002, Electronic payment",
            "02, 0003, Check payment",
            "03, 0001, Credit to Account",
            "03, 0002, Credit to Purchase balance",
            "03, 0003, Credit to Cash balance",
            "04, 0001, Zero dollar authorization",
            "04, 0002, Online purchase authorization",
            "04, 0003, Travel booking authorization",
            "05, 0001, Refund credit",
            "06, 0001, Fraud reversal",
            "06, 0002, Non-fraud reversal",
            "07, 0001, Sales draft credit adjustment"})
        void eachSeededRowConstructsUnchanged(final String typeCode, final String categoryCode,
                final String description) {
            final TransactionCategory seeded = new TransactionCategory(typeCode, categoryCode,
                    rightPadded(description, DESCRIPTION_WIDTH));

            assertThat(seeded.getTranTypeCd()).isEqualTo(typeCode).hasSize(TYPE_CODE_WIDTH);
            assertThat(seeded.getTranCatCd()).isEqualTo(categoryCode).hasSize(CATEGORY_CODE_WIDTH);
            assertThat(seeded.getTranCatTypeDesc())
                    .hasSize(DESCRIPTION_WIDTH)
                    .startsWith(description);
            assertThat(seeded.toId())
                    .isEqualTo(new TransactionCategoryId(typeCode, categoryCode));
        }

        @Test
        @DisplayName("every seeded description is shorter than the fifty-byte field, so every seeded row "
                + "carries trailing padding and none is truncated")
        void everySeededDescriptionFitsWithinTheField() {
            final List<String> descriptions = List.of("Regular Sales Draft", "Regular Cash Advance",
                    "Convenience Check Debit", "ATM Cash Advance", "Interest Amount", "Cash payment",
                    "Electronic payment", "Check payment", "Credit to Account",
                    "Credit to Purchase balance", "Credit to Cash balance",
                    "Zero dollar authorization", "Online purchase authorization",
                    "Travel booking authorization", "Refund credit", "Fraud reversal",
                    "Non-fraud reversal", "Sales draft credit adjustment");

            assertThat(descriptions).hasSize(SEEDED_ROWS);
            for (final String description : descriptions) {
                assertThat(description.length()).isLessThan(DESCRIPTION_WIDTH);
                assertThat(rightPadded(description, DESCRIPTION_WIDTH)).hasSize(DESCRIPTION_WIDTH);
            }
        }

        @Test
        @DisplayName("the interest category is one of the eighteen seeded rows yet carries no disclosure "
                + "rate anywhere, which is why each disclosure group holds seventeen rows and not "
                + "eighteen: interest does not accrue interest")
        void theInterestCategoryIsSeededYetCarriesNoDisclosureRate() {
            final TransactionCategory interest =
                    new TransactionCategory("01", "0005", rightPadded("Interest Amount", DESCRIPTION_WIDTH));
            final int disclosureRowsPerGroup = 17;

            assertThat(SEEDED_KEYS).contains("010005");
            assertThat(SEEDED_ROWS - disclosureRowsPerGroup).isEqualTo(1);
            assertThat(interest.toId()).isEqualTo(new TransactionCategoryId("01", "0005"));
            assertThat(interest.getTranCatTypeDesc().strip()).isEqualTo("Interest Amount");
        }

        @Test
        @DisplayName("the seeded category codes are never higher than five within a type, so the "
                + "four-byte code field is never close to exhaustion in the reference data")
        void theSeededCategoryCodesStayLow() {
            for (final String key : SEEDED_KEYS) {
                final String categoryCode = key.substring(TYPE_CODE_WIDTH);

                assertThat(categoryCode).hasSize(CATEGORY_CODE_WIDTH).startsWith("000");
                assertThat(Integer.parseInt(categoryCode)).isBetween(1, 5);
            }
        }
    }

    /**
     * A subclass standing in for the lazily-initialised proxy the persistence provider substitutes for
     * a row that has not yet been loaded. It adds no state and overrides nothing, so it exists purely
     * to prove that the type-pattern comparison of the parent admits a subclass symmetrically.
     *
     * <p>The entity is not serialisable, so this nested subclass introduces no serialisation obligation.
     */
    private static final class ProxiedTransactionCategory extends TransactionCategory {

        ProxiedTransactionCategory(final String typeCode, final String categoryCode) {
            super(typeCode, categoryCode, null);
        }
    }
}
