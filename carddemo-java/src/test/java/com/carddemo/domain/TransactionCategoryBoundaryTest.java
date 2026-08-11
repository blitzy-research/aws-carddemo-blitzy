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

import java.util.HashMap;
import java.util.Map;

import com.carddemo.domain.id.TransactionCategoryId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionCategory}, the sixty-byte transaction-category
 * reference row.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVTRA04Y.cpy} describes a sixty-byte record whose six-byte
 * key group holds a two-byte transaction type code at offset zero and a four-byte
 * category code at offset two, followed by a fifty-byte description at offset six. The
 * cluster definition in {@code app/jcl/TRANCATG.jcl} corroborates the key with
 * {@code KEYS(6 0)}, and the ASCII reference fixture
 * {@code app/data/ASCII/trancatg.txt} carries eighteen such rows in 1,098 bytes.</p>
 *
 * <h2>Why the key is composite and why only the key participates in identity</h2>
 *
 * <p>Neither component alone identifies a row: several categories share a type code, and
 * category codes repeat across type codes. Identity is therefore the pair, and the
 * description is deliberately excluded because it is mutable and would let an instance's
 * hash change while the instance sits in a hash-based collection. A test below edits the
 * description after insertion and proves the row is still retrievable.</p>
 *
 * <h2>Why every component is compared exactly</h2>
 *
 * <p>Each component is a fixed-width display item, so a leading zero occupies a real
 * byte and a four-character category code must never collapse to one. Nothing trims,
 * pads or folds case, and the assertions below prove that the padded and zero-suppressed
 * spellings are not equal.</p>
 */
@DisplayName("TransactionCategory: the sixty-byte transaction-category reference row")
class TransactionCategoryBoundaryTest {

    /** Transaction type code component at its contractual two characters. */
    private static final String TYPE_CODE = "01";

    /** Category code component at its contractual four characters, leading zeros included. */
    private static final String CATEGORY_CODE = "0005";

    /** Description at the contractual fifty-character width, blank padding included. */
    private static final String DESCRIPTION = "Restaurant                                        ";

    /**
     * Builds the reference row used across the assertions.
     *
     * @return a populated transaction category
     */
    private static TransactionCategory referenceRow() {
        return new TransactionCategory(TYPE_CODE, CATEGORY_CODE, DESCRIPTION);
    }

    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @Test
        @DisplayName("all three attributes are returned exactly as supplied")
        void allThreeAttributesAreReturnedAsSupplied() {
            TransactionCategory row = referenceRow();

            assertThat(row.getTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(row.getTranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(row.getTranCatTypeDesc()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, so it stays four characters wide")
        void theCategoryCodeKeepsItsLeadingZeros() {
            assertThat(referenceRow().getTranCatCd())
                    .isEqualTo("0005")
                    .hasSize(4)
                    .isNotEqualTo("5");
        }

        @Test
        @DisplayName("the type code keeps its leading zero, so it stays two characters wide")
        void theTypeCodeKeepsItsLeadingZero() {
            assertThat(referenceRow().getTranTypeCd()).isEqualTo("01").hasSize(2);
        }

        @Test
        @DisplayName("the description keeps its blank padding at the full fifty characters")
        void theDescriptionKeepsItsBlankPadding() {
            assertThat(referenceRow().getTranCatTypeDesc()).hasSize(50).endsWith("   ");
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an unpopulated row")
        void theNoArgumentConstructorYieldsAnUnpopulatedRow() {
            TransactionCategory row = new TransactionCategory();

            assertThat(row.getTranTypeCd()).isNull();
            assertThat(row.getTranCatCd()).isNull();
            assertThat(row.getTranCatTypeDesc()).isNull();
        }

        @Test
        @DisplayName("each setter replaces its value verbatim, with no normalisation")
        void eachSetterReplacesItsValueVerbatim() {
            TransactionCategory row = referenceRow();

            row.setTranTypeCd(" 2");
            row.setTranCatCd("  15");
            row.setTranCatTypeDesc("edited");

            assertThat(row.getTranTypeCd()).isEqualTo(" 2");
            assertThat(row.getTranCatCd()).isEqualTo("  15");
            assertThat(row.getTranCatTypeDesc()).isEqualTo("edited");
        }

        @Test
        @DisplayName("every attribute may be set back to null")
        void everyAttributeMayBeSetBackToNull() {
            TransactionCategory row = referenceRow();

            row.setTranTypeCd(null);
            row.setTranCatCd(null);
            row.setTranCatTypeDesc(null);

            assertThat(row.getTranTypeCd()).isNull();
            assertThat(row.getTranCatCd()).isNull();
            assertThat(row.getTranCatTypeDesc()).isNull();
        }
    }

    @Nested
    @DisplayName("projection onto the composite key")
    class KeyProjection {

        @Test
        @DisplayName("the projection carries both key components verbatim")
        void theProjectionCarriesBothKeyComponents() {
            TransactionCategoryId key = referenceRow().toId();

            assertThat(key.getTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the projection equals a key built directly from the same components")
        void theProjectionEqualsADirectlyBuiltKey() {
            assertThat(referenceRow().toId())
                    .isEqualTo(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE))
                    .hasSameHashCodeAs(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("the projection omits the description, which is not part of the key")
        void theProjectionOmitsTheDescription() {
            TransactionCategory first = referenceRow();
            TransactionCategory second =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "a different description");

            assertThat(first.toId()).isEqualTo(second.toId());
        }

        @Test
        @DisplayName("a fresh projection is produced on each call rather than a shared instance")
        void aFreshProjectionIsProducedOnEachCall() {
            TransactionCategory row = referenceRow();

            assertThat(row.toId()).isNotSameAs(row.toId()).isEqualTo(row.toId());
        }

        @Test
        @DisplayName("an unpopulated row projects an all-null key rather than throwing")
        void anUnpopulatedRowProjectsAnAllNullKey() {
            TransactionCategoryId key = new TransactionCategory().toId();

            assertThat(key.getTranTypeCd()).isNull();
            assertThat(key.getTranCatCd()).isNull();
        }

        @Test
        @DisplayName("the projection reflects a setter applied after construction")
        void theProjectionReflectsASetterAppliedAfterConstruction() {
            TransactionCategory row = referenceRow();

            row.setTranCatCd("0006");

            assertThat(row.toId()).isEqualTo(new TransactionCategoryId(TYPE_CODE, "0006"));
        }
    }

    @Nested
    @DisplayName("identity derived from the key pair alone")
    class Identity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            TransactionCategory row = referenceRow();

            assertThat(row).isEqualTo(row);
        }

        @Test
        @DisplayName("two rows sharing both key components are equal in both directions")
        void twoRowsSharingBothKeyComponentsAreEqual() {
            TransactionCategory first = referenceRow();
            TransactionCategory second = referenceRow();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three rows sharing the key")
        void equalityIsTransitive() {
            TransactionCategory first = referenceRow();
            TransactionCategory second = referenceRow();
            TransactionCategory third = referenceRow();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing description does not break equality, because it is excluded")
        void aDifferingDescriptionDoesNotBreakEquality() {
            TransactionCategory other =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "something else");

            assertThat(referenceRow()).isEqualTo(other).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a differing type code alone breaks equality")
        void aDifferingTypeCodeBreaksEquality() {
            assertThat(referenceRow())
                    .isNotEqualTo(new TransactionCategory("02", CATEGORY_CODE, DESCRIPTION));
        }

        @Test
        @DisplayName("a differing category code alone breaks equality")
        void aDifferingCategoryCodeBreaksEquality() {
            assertThat(referenceRow())
                    .isNotEqualTo(new TransactionCategory(TYPE_CODE, "0006", DESCRIPTION));
        }

        @Test
        @DisplayName("a zero-suppressed category code is not equal to its padded spelling")
        void aZeroSuppressedCategoryCodeIsNotEqual() {
            assertThat(referenceRow())
                    .isNotEqualTo(new TransactionCategory(TYPE_CODE, "5", DESCRIPTION));
        }

        @Test
        @DisplayName("a null reference is not equal to any row")
        void aNullReferenceIsNotEqualToAnyRow() {
            assertThat(referenceRow()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to a row, and neither is its own key type")
        void anUnrelatedTypeIsNotEqualToARow() {
            TransactionCategory row = referenceRow();

            assertThat(row).isNotEqualTo(TYPE_CODE + CATEGORY_CODE);
            assertThat(row).isNotEqualTo(row.toId());
        }

        @Test
        @DisplayName("two unpopulated rows are equal, so a provider-instantiated row is coherent")
        void twoUnpopulatedRowsAreEqual() {
            assertThat(new TransactionCategory())
                    .isEqualTo(new TransactionCategory())
                    .hasSameHashCodeAs(new TransactionCategory());
        }

        @Test
        @DisplayName("an unpopulated row is not equal to a populated one")
        void anUnpopulatedRowIsNotEqualToAPopulatedOne() {
            assertThat(new TransactionCategory()).isNotEqualTo(referenceRow());
        }

        @Test
        @DisplayName("equal rows hash alike and hashing is stable across calls")
        void equalRowsHashAlike() {
            TransactionCategory row = referenceRow();

            assertThat(row).hasSameHashCodeAs(referenceRow());
            assertThat(row.hashCode()).isEqualTo(row.hashCode());
        }

        @Test
        @DisplayName("mutating the description leaves the row retrievable from a hash map")
        void mutatingTheDescriptionLeavesTheRowRetrievable() {
            TransactionCategory row = referenceRow();
            Map<TransactionCategory, String> index = new HashMap<>();
            index.put(row, "seeded");

            row.setTranCatTypeDesc("edited after insertion");

            assertThat(index).containsEntry(row, "seeded");
            assertThat(index).containsEntry(referenceRow(), "seeded");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and lists all three attributes")
        void theRenderingNamesTheTypeAndListsAllThreeAttributes() {
            assertThat(referenceRow().toString()).isEqualTo(
                    "TransactionCategory[tranTypeCd=" + TYPE_CODE
                            + ", tranCatCd=" + CATEGORY_CODE
                            + ", tranCatTypeDesc=" + DESCRIPTION + "]");
        }

        @Test
        @DisplayName("the description is rendered as stored, blank padding included")
        void theDescriptionIsRenderedAsStored() {
            assertThat(referenceRow().toString()).contains(DESCRIPTION);
        }

        @Test
        @DisplayName("an unpopulated row renders without throwing")
        void anUnpopulatedRowRendersWithoutThrowing() {
            assertThat(new TransactionCategory().toString()).isEqualTo(
                    "TransactionCategory[tranTypeCd=null, tranCatCd=null, tranCatTypeDesc=null]");
        }
    }
}
