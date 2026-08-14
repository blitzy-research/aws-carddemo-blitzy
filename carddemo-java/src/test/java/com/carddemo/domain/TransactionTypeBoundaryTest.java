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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionType}, the sixty-byte transaction-type reference row.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVTRA03Y.cpy} describes a sixty-byte record holding a
 * two-byte transaction type code at offset zero followed by a fifty-byte description at
 * offset two, with the remaining bytes reserved. The ASCII reference fixture
 * {@code app/data/ASCII/trantype.txt} carries seven such rows in 427 bytes, whose codes
 * are the two-character values {@code 01} through {@code 07}.</p>
 *
 * <h2>Why the code is text and never a number</h2>
 *
 * <p>Each code is an external-decimal display item in the legacy layout, so its leading
 * zero occupies a real byte. Holding it as text is what keeps {@code "01"} distinct from
 * {@code "1"}; the assertions below prove that the round trip preserves the zero and
 * that the two spellings are not equal.</p>
 *
 * <h2>Why only the code participates in identity</h2>
 *
 * <p>The description is mutable, so including it in equality would let an instance's hash
 * change while the instance sits inside a hash-based collection. Identity is therefore
 * the code alone, which also matches the single-column primary key of the mapped table.
 * A test below changes the description on a stored instance and proves the instance is
 * still retrievable from a map.</p>
 */
@DisplayName("TransactionType: the sixty-byte transaction-type reference row")
class TransactionTypeBoundaryTest {

    /** Transaction type code at its contractual two characters, leading zero included. */
    private static final String TYPE_CODE = "01";

    /** A second code, used to prove that identity discriminates. */
    private static final String OTHER_TYPE_CODE = "07";

    /** Description at the contractual fifty-character width, blank padding included. */
    private static final String DESCRIPTION = "Purchase                                          ";

    /**
     * Builds the reference row used across the assertions.
     *
     * @return a populated transaction type
     */
    private static TransactionType referenceRow() {
        return new TransactionType(TYPE_CODE, DESCRIPTION);
    }

    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @Test
        @DisplayName("both attributes are returned exactly as supplied")
        void bothAttributesAreReturnedAsSupplied() {
            TransactionType row = referenceRow();

            assertThat(row.getTranType()).isEqualTo(TYPE_CODE);
            assertThat(row.getTranTypeDesc()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("the code keeps its leading zero, so it stays two characters wide")
        void theCodeKeepsItsLeadingZero() {
            assertThat(referenceRow().getTranType())
                    .isEqualTo("01")
                    .hasSize(2)
                    .isNotEqualTo("1");
        }

        @Test
        @DisplayName("the description keeps its blank padding at the full fifty characters")
        void theDescriptionKeepsItsBlankPadding() {
            assertThat(referenceRow().getTranTypeDesc()).hasSize(50).endsWith("   ");
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an unpopulated row")
        void theNoArgumentConstructorYieldsAnUnpopulatedRow() {
            TransactionType row = new TransactionType();

            assertThat(row.getTranType()).isNull();
            assertThat(row.getTranTypeDesc()).isNull();
        }

        @Test
        @DisplayName("a null attribute is carried through rather than rejected or defaulted")
        void aNullAttributeIsCarriedThrough() {
            TransactionType row = new TransactionType(null, null);

            assertThat(row.getTranType()).isNull();
            assertThat(row.getTranTypeDesc()).isNull();
        }

        @Test
        @DisplayName("the code setter replaces the value verbatim, with no normalisation")
        void theCodeSetterReplacesTheValueVerbatim() {
            TransactionType row = referenceRow();

            row.setTranType(" 7");

            assertThat(row.getTranType()).isEqualTo(" 7");
        }

        @Test
        @DisplayName("the description setter replaces the value verbatim, with no normalisation")
        void theDescriptionSetterReplacesTheValueVerbatim() {
            TransactionType row = referenceRow();

            row.setTranTypeDesc("credit  ");

            assertThat(row.getTranTypeDesc()).isEqualTo("credit  ");
        }

        @Test
        @DisplayName("either attribute may be set back to null")
        void eitherAttributeMayBeSetBackToNull() {
            TransactionType row = referenceRow();

            row.setTranType(null);
            row.setTranTypeDesc(null);

            assertThat(row.getTranType()).isNull();
            assertThat(row.getTranTypeDesc()).isNull();
        }
    }

    @Nested
    @DisplayName("identity derived from the code alone")
    class Identity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            TransactionType row = referenceRow();

            assertThat(row).isEqualTo(row);
        }

        @Test
        @DisplayName("two rows sharing the code are equal in both directions")
        void twoRowsSharingTheCodeAreEqual() {
            TransactionType first = referenceRow();
            TransactionType second = referenceRow();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three rows sharing the code")
        void equalityIsTransitive() {
            TransactionType first = referenceRow();
            TransactionType second = referenceRow();
            TransactionType third = referenceRow();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing description does not break equality, because it is excluded")
        void aDifferingDescriptionDoesNotBreakEquality() {
            assertThat(referenceRow())
                    .isEqualTo(new TransactionType(TYPE_CODE, "something else"))
                    .hasSameHashCodeAs(new TransactionType(TYPE_CODE, "something else"));
        }

        @Test
        @DisplayName("a differing code breaks equality")
        void aDifferingCodeBreaksEquality() {
            assertThat(referenceRow())
                    .isNotEqualTo(new TransactionType(OTHER_TYPE_CODE, DESCRIPTION));
        }

        @Test
        @DisplayName("a zero-suppressed code is not equal to its padded spelling")
        void aZeroSuppressedCodeIsNotEqualToItsPaddedSpelling() {
            assertThat(referenceRow()).isNotEqualTo(new TransactionType("1", DESCRIPTION));
        }

        @Test
        @DisplayName("a null reference is not equal to any row")
        void aNullReferenceIsNotEqualToAnyRow() {
            assertThat(referenceRow()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to a row carrying the same code")
        void anUnrelatedTypeIsNotEqualToARow() {
            assertThat(referenceRow()).isNotEqualTo(TYPE_CODE);
        }

        @Test
        @DisplayName("two unpopulated rows are equal, so a provider-instantiated row is coherent")
        void twoUnpopulatedRowsAreEqual() {
            assertThat(new TransactionType())
                    .isEqualTo(new TransactionType())
                    .hasSameHashCodeAs(new TransactionType());
        }

        @Test
        @DisplayName("an unpopulated row is not equal to a populated one")
        void anUnpopulatedRowIsNotEqualToAPopulatedOne() {
            assertThat(new TransactionType()).isNotEqualTo(referenceRow());
        }

        @Test
        @DisplayName("equal rows hash alike and hashing is stable across calls")
        void equalRowsHashAlike() {
            TransactionType row = referenceRow();

            assertThat(row).hasSameHashCodeAs(referenceRow());
            assertThat(row.hashCode()).isEqualTo(row.hashCode());
        }

        @Test
        @DisplayName("mutating the description leaves the row retrievable from a hash map")
        void mutatingTheDescriptionLeavesTheRowRetrievable() {
            TransactionType row = referenceRow();
            Map<TransactionType, String> index = new HashMap<>();
            index.put(row, "seeded");

            row.setTranTypeDesc("edited after insertion");

            assertThat(index).containsEntry(row, "seeded");
            assertThat(index).containsEntry(referenceRow(), "seeded");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and lists both attributes")
        void theRenderingNamesTheTypeAndListsBothAttributes() {
            assertThat(referenceRow().toString()).isEqualTo(
                    "TransactionType[tranType=" + TYPE_CODE
                            + ", tranTypeDesc=" + DESCRIPTION + "]");
        }

        @Test
        @DisplayName("the description is rendered as stored, blank padding included")
        void theDescriptionIsRenderedAsStored() {
            assertThat(referenceRow().toString()).contains(DESCRIPTION);
        }

        @Test
        @DisplayName("an unpopulated row renders without throwing")
        void anUnpopulatedRowRendersWithoutThrowing() {
            assertThat(new TransactionType().toString())
                    .isEqualTo("TransactionType[tranType=null, tranTypeDesc=null]");
        }
    }
}
