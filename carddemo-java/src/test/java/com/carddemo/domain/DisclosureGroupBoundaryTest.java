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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import com.carddemo.domain.id.DisclosureGroupId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DisclosureGroup}, the fifty-byte disclosure-group row that
 * supplies the interest rate to the accrual run.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVTRA02Y.cpy} describes a fifty-byte record whose sixteen
 * leading bytes form the key — a ten-byte account group identifier at offset zero, a
 * two-byte transaction type code at offset ten and a four-byte category code at offset
 * twelve — followed by the rate field {@code DIS-INT-RATE PIC S9(04)V99}. The ASCII
 * fixture {@code app/data/ASCII/discgrp.txt} carries fifty-one such rows in 2,601 bytes,
 * arranged as three consecutive seventeen-row groups keyed {@code A}, {@code DEFAULT} and
 * {@code ZEROAPR}.</p>
 *
 * <h2>Why the rate is a scaled decimal and is never rescaled here</h2>
 *
 * <p>The legacy field is zoned decimal with two implied decimal places and no
 * {@code ROUNDED} clause anywhere in the estate, so the value is carried as a
 * {@link BigDecimal} whose scale is fixed by the codec of the utility layer rather than by
 * this entity. The entity therefore stores whatever scale it is handed, and the
 * assertions below prove that it neither rescales nor range-checks — a genuine zero rate
 * must load, because the {@code ZEROAPR} group in the fixture depends on it and the
 * accrual program skips accrual only when the rate it read is zero.</p>
 *
 * <h2>Why the rate is excluded from identity and from the diagnostic rendering</h2>
 *
 * <p>The rate is mutable state, so including it in equality would let an instance change
 * its own hash across a flush and corrupt any collection already holding it. It is also
 * financial data, which has no place in an incidental log line. Both exclusions are
 * asserted directly below.</p>
 */
@DisplayName("DisclosureGroup: the fifty-byte disclosure-group row carrying the accrual rate")
class DisclosureGroupBoundaryTest {

    /** Account group identifier at its contractual ten characters. */
    private static final String GROUP_ID = "A         ";

    /** The default group literal padded to the full ten-character component width. */
    private static final String PADDED_DEFAULT_GROUP_ID = "DEFAULT   ";

    /** Transaction type code at its contractual two characters. */
    private static final String TYPE_CODE = "01";

    /** Category code at its contractual four characters. */
    private static final String CATEGORY_CODE = "0005";

    /** A representative rate at the contractual scale of two. */
    private static final BigDecimal RATE = new BigDecimal("12.50");

    /**
     * Builds the reference row used across the assertions.
     *
     * @return a populated disclosure group
     */
    private static DisclosureGroup referenceRow() {
        return new DisclosureGroup(GROUP_ID, TYPE_CODE, CATEGORY_CODE, RATE);
    }

    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @Test
        @DisplayName("all four attributes are returned exactly as supplied")
        void allFourAttributesAreReturnedAsSupplied() {
            DisclosureGroup row = referenceRow();

            assertThat(row.getDisAcctGroupId()).isEqualTo(GROUP_ID);
            assertThat(row.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(row.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(row.getDisIntRate()).isEqualTo(RATE);
        }

        @Test
        @DisplayName("the group identifier keeps its trailing padding at the full ten characters")
        void theGroupIdentifierKeepsItsPadding() {
            DisclosureGroup row = new DisclosureGroup(
                    PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE, RATE);

            assertThat(row.getDisAcctGroupId())
                    .isEqualTo(PADDED_DEFAULT_GROUP_ID)
                    .hasSize(10)
                    .endsWith("   ");
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, so it stays four characters wide")
        void theCategoryCodeKeepsItsLeadingZeros() {
            assertThat(referenceRow().getDisTranCatCd())
                    .isEqualTo("0005")
                    .hasSize(4)
                    .isNotEqualTo("5");
        }

        @Test
        @DisplayName("the rate is stored at the scale supplied and is not rescaled")
        void theRateIsStoredAtTheScaleSupplied() {
            DisclosureGroup row = new DisclosureGroup(GROUP_ID, TYPE_CODE, CATEGORY_CODE,
                    new BigDecimal("7.5"));

            assertThat(row.getDisIntRate()).isEqualTo(new BigDecimal("7.5"));
            assertThat(row.getDisIntRate().scale()).isEqualTo(1);
        }

        @Test
        @DisplayName("a genuine zero rate loads, because the zero-rate reference group depends on it")
        void aGenuineZeroRateLoads() {
            DisclosureGroup row = new DisclosureGroup("ZEROAPR   ", TYPE_CODE, CATEGORY_CODE,
                    new BigDecimal("0.00"));

            assertThat(row.getDisIntRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.getDisIntRate().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("a negative rate is stored rather than clamped, because the field is signed")
        void aNegativeRateIsStoredRatherThanClamped() {
            DisclosureGroup row = new DisclosureGroup(GROUP_ID, TYPE_CODE, CATEGORY_CODE,
                    new BigDecimal("-1.25"));

            assertThat(row.getDisIntRate()).isEqualTo(new BigDecimal("-1.25"));
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an unpopulated row")
        void theNoArgumentConstructorYieldsAnUnpopulatedRow() {
            DisclosureGroup row = new DisclosureGroup();

            assertThat(row.getDisAcctGroupId()).isNull();
            assertThat(row.getDisTranTypeCd()).isNull();
            assertThat(row.getDisTranCatCd()).isNull();
            assertThat(row.getDisIntRate()).isNull();
        }

        @Test
        @DisplayName("each setter replaces its value verbatim, with no normalisation")
        void eachSetterReplacesItsValueVerbatim() {
            DisclosureGroup row = referenceRow();

            row.setDisAcctGroupId("ZEROAPR   ");
            row.setDisTranTypeCd(" 2");
            row.setDisTranCatCd("  15");
            row.setDisIntRate(new BigDecimal("0.000"));

            assertThat(row.getDisAcctGroupId()).isEqualTo("ZEROAPR   ");
            assertThat(row.getDisTranTypeCd()).isEqualTo(" 2");
            assertThat(row.getDisTranCatCd()).isEqualTo("  15");
            assertThat(row.getDisIntRate()).isEqualTo(new BigDecimal("0.000"));
        }

        @Test
        @DisplayName("every attribute may be set back to null")
        void everyAttributeMayBeSetBackToNull() {
            DisclosureGroup row = referenceRow();

            row.setDisAcctGroupId(null);
            row.setDisTranTypeCd(null);
            row.setDisTranCatCd(null);
            row.setDisIntRate(null);

            assertThat(row.getDisAcctGroupId()).isNull();
            assertThat(row.getDisTranTypeCd()).isNull();
            assertThat(row.getDisTranCatCd()).isNull();
            assertThat(row.getDisIntRate()).isNull();
        }
    }

    @Nested
    @DisplayName("projection onto the composite key")
    class KeyProjection {

        @Test
        @DisplayName("the projection carries all three key components verbatim")
        void theProjectionCarriesAllThreeKeyComponents() {
            DisclosureGroupId key = referenceRow().toId();

            assertThat(key.getDisAcctGroupId()).isEqualTo(GROUP_ID);
            assertThat(key.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the projection equals a key built directly from the same components")
        void theProjectionEqualsADirectlyBuiltKey() {
            DisclosureGroupId expected =
                    new DisclosureGroupId(GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(referenceRow().toId()).isEqualTo(expected).hasSameHashCodeAs(expected);
        }

        @Test
        @DisplayName("the projection omits the rate, which is not part of the key")
        void theProjectionOmitsTheRate() {
            DisclosureGroup other = new DisclosureGroup(GROUP_ID, TYPE_CODE, CATEGORY_CODE,
                    new BigDecimal("99.99"));

            assertThat(referenceRow().toId()).isEqualTo(other.toId());
        }

        @Test
        @DisplayName("a fresh projection is produced on each call rather than a shared instance")
        void aFreshProjectionIsProducedOnEachCall() {
            DisclosureGroup row = referenceRow();

            assertThat(row.toId()).isNotSameAs(row.toId()).isEqualTo(row.toId());
        }

        @Test
        @DisplayName("the padded default group projects a key distinct from its unpadded spelling")
        void thePaddedDefaultGroupProjectsADistinctKey() {
            DisclosureGroup padded = new DisclosureGroup(
                    PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE, RATE);
            DisclosureGroup unpadded =
                    new DisclosureGroup("DEFAULT", TYPE_CODE, CATEGORY_CODE, RATE);

            assertThat(padded.toId()).isNotEqualTo(unpadded.toId());
        }

        @Test
        @DisplayName("an unpopulated row projects an all-null key rather than throwing")
        void anUnpopulatedRowProjectsAnAllNullKey() {
            DisclosureGroupId key = new DisclosureGroup().toId();

            assertThat(key.getDisAcctGroupId()).isNull();
            assertThat(key.getDisTranTypeCd()).isNull();
            assertThat(key.getDisTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("identity derived from the key components alone")
    class Identity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            DisclosureGroup row = referenceRow();

            assertThat(row).isEqualTo(row);
        }

        @Test
        @DisplayName("two rows sharing all three key components are equal in both directions")
        void twoRowsSharingTheKeyAreEqual() {
            DisclosureGroup first = referenceRow();
            DisclosureGroup second = referenceRow();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three rows sharing the key")
        void equalityIsTransitive() {
            DisclosureGroup first = referenceRow();
            DisclosureGroup second = referenceRow();
            DisclosureGroup third = referenceRow();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing rate does not break equality, because the rate is excluded")
        void aDifferingRateDoesNotBreakEquality() {
            DisclosureGroup other = new DisclosureGroup(GROUP_ID, TYPE_CODE, CATEGORY_CODE,
                    new BigDecimal("0.00"));

            assertThat(referenceRow()).isEqualTo(other).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a differing group identifier alone breaks equality")
        void aDifferingGroupIdentifierBreaksEquality() {
            assertThat(referenceRow()).isNotEqualTo(
                    new DisclosureGroup("B         ", TYPE_CODE, CATEGORY_CODE, RATE));
        }

        @Test
        @DisplayName("a differing type code alone breaks equality")
        void aDifferingTypeCodeBreaksEquality() {
            assertThat(referenceRow())
                    .isNotEqualTo(new DisclosureGroup(GROUP_ID, "02", CATEGORY_CODE, RATE));
        }

        @Test
        @DisplayName("a differing category code alone breaks equality")
        void aDifferingCategoryCodeBreaksEquality() {
            assertThat(referenceRow())
                    .isNotEqualTo(new DisclosureGroup(GROUP_ID, TYPE_CODE, "0006", RATE));
        }

        @Test
        @DisplayName("the padded and unpadded default group spellings are not equal")
        void thePaddedAndUnpaddedDefaultSpellingsAreNotEqual() {
            DisclosureGroup padded = new DisclosureGroup(
                    PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE, RATE);
            DisclosureGroup unpadded =
                    new DisclosureGroup("DEFAULT", TYPE_CODE, CATEGORY_CODE, RATE);

            assertThat(padded).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("a null reference is not equal to any row")
        void aNullReferenceIsNotEqualToAnyRow() {
            assertThat(referenceRow()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to a row, and neither is its own key type")
        void anUnrelatedTypeIsNotEqualToARow() {
            DisclosureGroup row = referenceRow();

            assertThat(row).isNotEqualTo(GROUP_ID + TYPE_CODE + CATEGORY_CODE);
            assertThat(row).isNotEqualTo(row.toId());
        }

        @Test
        @DisplayName("two unpopulated rows are equal, so a provider-instantiated row is coherent")
        void twoUnpopulatedRowsAreEqual() {
            assertThat(new DisclosureGroup())
                    .isEqualTo(new DisclosureGroup())
                    .hasSameHashCodeAs(new DisclosureGroup());
        }

        @Test
        @DisplayName("an unpopulated row is not equal to a populated one")
        void anUnpopulatedRowIsNotEqualToAPopulatedOne() {
            assertThat(new DisclosureGroup()).isNotEqualTo(referenceRow());
        }

        @Test
        @DisplayName("equal rows hash alike and hashing is stable across calls")
        void equalRowsHashAlike() {
            DisclosureGroup row = referenceRow();

            assertThat(row).hasSameHashCodeAs(referenceRow());
            assertThat(row.hashCode()).isEqualTo(row.hashCode());
        }

        @Test
        @DisplayName("mutating the rate leaves the row retrievable from a hash map")
        void mutatingTheRateLeavesTheRowRetrievable() {
            DisclosureGroup row = referenceRow();
            Map<DisclosureGroup, String> index = new HashMap<>();
            index.put(row, "seeded");

            row.setDisIntRate(new BigDecimal("0.00"));

            assertThat(index).containsEntry(row, "seeded");
            assertThat(index).containsEntry(referenceRow(), "seeded");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and quotes the three key components")
        void theRenderingNamesTheTypeAndQuotesTheKeyComponents() {
            assertThat(referenceRow().toString()).isEqualTo(
                    "DisclosureGroup[disAcctGroupId='" + GROUP_ID
                            + "', disTranTypeCd='" + TYPE_CODE
                            + "', disTranCatCd='" + CATEGORY_CODE + "']");
        }

        @Test
        @DisplayName("the rendering omits the rate, because it is financial data")
        void theRenderingOmitsTheRate() {
            assertThat(referenceRow().toString())
                    .doesNotContain("12.50")
                    .doesNotContain("disIntRate");
        }

        @Test
        @DisplayName("quoting keeps significant trailing spaces visible in a log line")
        void quotingKeepsTrailingSpacesVisible() {
            String rendered = new DisclosureGroup(
                    PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE, RATE).toString();

            assertThat(rendered).contains("disAcctGroupId='DEFAULT   '");
        }

        @Test
        @DisplayName("an unpopulated row renders without throwing")
        void anUnpopulatedRowRendersWithoutThrowing() {
            assertThat(new DisclosureGroup().toString())
                    .isEqualTo("DisclosureGroup[disAcctGroupId='null', "
                            + "disTranTypeCd='null', disTranCatCd='null']");
        }
    }
}
