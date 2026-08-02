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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.carddemo.domain.id.DisclosureGroupId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link DisclosureGroup}, the Java realisation of the 50-byte
 * {@code DIS-GROUP-RECORD} layout declared by copybook member {@code CVTRA02Y}.
 *
 * <h2>Why the padded group identifier is the interesting case</h2>
 *
 * <p>The reference fixture holds three consecutive seventeen-row groups keyed {@code A},
 * {@code DEFAULT} and {@code ZEROAPR}, each key stored at the full ten-byte declared width. The
 * interest program looks the key up directly and, on a not-found status, retries against the default
 * group. Collapsing a padded key onto its trimmed form would make that fallback appear to resolve keys
 * it does not resolve, so the assertions below pin that {@code "DEFAULT   "} and {@code "DEFAULT"} are
 * <em>different</em> keys - in the entity, in the composite identifier it produces, and in a hash set.</p>
 *
 * <h2>The rate is the one field that must not participate in identity</h2>
 *
 * <p>The rate is mutable state and is also the single input to the monthly-interest computation. It is
 * excluded from equality and hashing so that a row already sitting in a set cannot move, and it is
 * excluded from the diagnostic rendering because it is financial data. Both exclusions are asserted.</p>
 */
@DisplayName("DisclosureGroup - the 50-byte CVTRA02Y disclosure group record")
class DisclosureGroupSecurityTest {

    private static final String GROUP_ID = "A         ";
    private static final String DEFAULT_GROUP_ID = "DEFAULT   ";
    private static final String ZERO_APR_GROUP_ID = "ZEROAPR   ";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0005";
    private static final BigDecimal RATE = new BigDecimal("18.99");

    /** A fully populated disclosure group row at the widths the record layout declares. */
    private static DisclosureGroup group() {
        return new DisclosureGroup(GROUP_ID, TYPE_CODE, CATEGORY_CODE, RATE);
    }

    @Nested
    @DisplayName("Construction from the record image")
    class Construction {

        @Test
        @DisplayName("the four-argument constructor assigns every field to its own accessor")
        void everyFieldLandsOnItsOwnAccessor() {
            final DisclosureGroup subject = group();
            assertThat(subject.getDisAcctGroupId()).isEqualTo(GROUP_ID);
            assertThat(subject.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(subject.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(subject.getDisIntRate()).isEqualTo(RATE);
        }

        @Test
        @DisplayName("the three key fixtures are distinguishable, so a transposition among the same-typed key "
                + "arguments would be caught")
        void theThreeKeyFixturesAreDistinguishable() {
            assertThat(Set.of(GROUP_ID, TYPE_CODE, CATEGORY_CODE)).hasSize(3);
        }

        @Test
        @DisplayName("the provider constructor leaves every field unset")
        void theProviderConstructorLeavesEveryFieldUnset() {
            final DisclosureGroup subject = new DisclosureGroup();
            assertThat(subject.getDisAcctGroupId()).isNull();
            assertThat(subject.getDisTranTypeCd()).isNull();
            assertThat(subject.getDisTranCatCd()).isNull();
            assertThat(subject.getDisIntRate()).isNull();
        }

        @Test
        @DisplayName("all four setters are individually effective")
        void allFourSettersAreIndividuallyEffective() {
            final DisclosureGroup subject = new DisclosureGroup();
            subject.setDisAcctGroupId(GROUP_ID);
            subject.setDisTranTypeCd(TYPE_CODE);
            subject.setDisTranCatCd(CATEGORY_CODE);
            subject.setDisIntRate(RATE);
            assertThat(subject).isEqualTo(group());
            assertThat(subject.getDisIntRate()).isEqualTo(RATE);
        }

        @Test
        @DisplayName("a setter accepts null, because nullability is enforced by the column")
        void aSetterAcceptsNull() {
            final DisclosureGroup subject = group();
            subject.setDisIntRate(null);
            subject.setDisTranCatCd(null);
            assertThat(subject.getDisIntRate()).isNull();
            assertThat(subject.getDisTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Field widths carried through from the record layout")
    class RecordLayoutWidths {

        @Test
        @DisplayName("the group identifier is 10 characters and keeps its trailing padding, which is real data in a "
                + "fixed-width record")
        void theGroupIdentifierIsTenCharactersAndKeepsItsPadding() {
            assertThat(group().getDisAcctGroupId()).hasSize(10).isEqualTo("A         ");
            assertThat(group().getDisAcctGroupId()).isNotEqualTo("A");
        }

        @Test
        @DisplayName("the three fixture group keys are each 10 characters, matching the seeded groups A, DEFAULT and "
                + "ZEROAPR")
        void theThreeSeededGroupKeysAreEachTenCharacters() {
            assertThat(GROUP_ID).hasSize(10);
            assertThat(DEFAULT_GROUP_ID).hasSize(10);
            assertThat(ZERO_APR_GROUP_ID).hasSize(10);
        }

        @Test
        @DisplayName("the type code is 2 characters and the category code is 4")
        void theClassificationCodesCarryTheirDeclaredWidths() {
            assertThat(group().getDisTranTypeCd()).hasSize(2);
            assertThat(group().getDisTranCatCd()).hasSize(4);
        }

        @Test
        @DisplayName("no accessor trims a fixed-width value")
        void noAccessorTrimsAFixedWidthValue() {
            final DisclosureGroup subject = new DisclosureGroup();
            subject.setDisAcctGroupId("  padded  ");
            assertThat(subject.getDisAcctGroupId()).isEqualTo("  padded  ");
        }
    }

    @Nested
    @DisplayName("The rate: scale carried, sign preserved, never coerced")
    class RateFidelity {

        @Test
        @DisplayName("the rate fixture is at scale two, matching the V99 clause on PIC S9(04)V99")
        void theRateFixtureIsAtScaleTwo() {
            assertThat(RATE.scale()).isEqualTo(2);
            assertThat(group().getDisIntRate().scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "0.000", "18.9", "18.900", "-1.50", "9999.99"})
        @DisplayName("an off-scale rate is stored exactly as supplied and is not silently normalised, because scale "
                + "coercion belongs to the codec")
        void anOffScaleRateIsStoredExactlyAsSupplied(final String literal) {
            final BigDecimal supplied = new BigDecimal(literal);
            final DisclosureGroup subject = new DisclosureGroup();
            subject.setDisIntRate(supplied);
            assertThat(subject.getDisIntRate()).isSameAs(supplied);
            assertThat(subject.getDisIntRate().scale()).isEqualTo(supplied.scale());
            assertThat(subject.getDisIntRate().toPlainString()).isEqualTo(literal);
        }

        @Test
        @DisplayName("a zero rate is stored as zero rather than as null, so what the zero-rate skip branch "
                + "of the interest program reads is a genuine row value and never a null")
        void aZeroRateIsStoredAsZero() {
            final DisclosureGroup subject = new DisclosureGroup(ZERO_APR_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("0.00"));
            assertThat(subject.getDisIntRate()).isNotNull();
            assertThat(subject.getDisIntRate().signum()).isZero();
            assertThat(subject.getDisIntRate().toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("the widest value the PIC S9(04)V99 clause admits is stored without loss")
        void theWidestAdmissibleRateIsStoredWithoutLoss() {
            final BigDecimal widest = new BigDecimal("9999.99");
            final DisclosureGroup subject = new DisclosureGroup();
            subject.setDisIntRate(widest);
            assertThat(subject.getDisIntRate()).isEqualTo(widest);
            assertThat(subject.getDisIntRate().precision()).isEqualTo(6);
        }

        @Test
        @DisplayName("a negative rate keeps its sign, because the layout is a SIGNED zoned decimal")
        void aNegativeRateKeepsItsSign() {
            final DisclosureGroup subject = new DisclosureGroup();
            subject.setDisIntRate(new BigDecimal("-0.01"));
            assertThat(subject.getDisIntRate().signum()).isNegative();
        }

        @Test
        @DisplayName("the rate is an exact decimal, so the monthly-interest expression can truncate at the point "
                + "the legacy COMPUTE truncates rather than at a binary rounding boundary")
        void theRateIsAnExactDecimal() {
            final BigDecimal balance = new BigDecimal("100.00");
            final BigDecimal monthly = balance.multiply(group().getDisIntRate())
                    .divide(new BigDecimal("1200"), 2, RoundingMode.DOWN);
            assertThat(monthly).isEqualByComparingTo("1.58");
            assertThat(monthly.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("The composite identifier this row produces")
    class CompositeIdentifier {

        @Test
        @DisplayName("the identifier carries the three key components in declaration order")
        void theIdentifierCarriesTheThreeKeyComponents() {
            final DisclosureGroupId id = group().toId();
            assertThat(id.getDisAcctGroupId()).isEqualTo(GROUP_ID);
            assertThat(id.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(id.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the identifier equals one assembled by hand from the same three components")
        void theIdentifierEqualsOneAssembledByHand() {
            assertThat(group().toId())
                    .isEqualTo(new DisclosureGroupId(GROUP_ID, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("a new identifier is produced on every call, so nothing is cached or shared between rows")
        void aNewIdentifierIsProducedOnEveryCall() {
            final DisclosureGroup subject = group();
            final DisclosureGroupId first = subject.toId();
            final DisclosureGroupId second = subject.toId();
            assertThat(first).isEqualTo(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("the identifier carries the padding through, so a padded group key does not silently become its "
                + "trimmed form on the way to a repository lookup")
        void theIdentifierCarriesThePaddingThrough() {
            final DisclosureGroup padded = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, RATE);
            assertThat(padded.toId())
                    .isEqualTo(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE))
                    .isNotEqualTo(new DisclosureGroupId("DEFAULT", TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("the identifier of an unset row carries three nulls rather than throwing")
        void theIdentifierOfAnUnsetRowCarriesThreeNulls() {
            final DisclosureGroupId id = new DisclosureGroup().toId();
            assertThat(id.getDisAcctGroupId()).isNull();
            assertThat(id.getDisTranTypeCd()).isNull();
            assertThat(id.getDisTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Identity: the three key components, exactly, and never the rate")
    class Identity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            final DisclosureGroup subject = group();
            assertThat(subject).isEqualTo(subject);
        }

        @Test
        @DisplayName("two rows with the same three key components are equal even when their rates differ, because "
                + "the rate is mutable state rather than identity")
        void theThreeKeyComponentsAloneDetermineEquality() {
            final DisclosureGroup left = group();
            final DisclosureGroup right = new DisclosureGroup(GROUP_ID, TYPE_CODE, CATEGORY_CODE,
                    new BigDecimal("0.00"));
            assertThat(left).isEqualTo(right);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("the group identifier participates individually")
        void theGroupIdentifierParticipatesIndividually() {
            assertThat(group()).isNotEqualTo(
                    new DisclosureGroup(ZERO_APR_GROUP_ID, TYPE_CODE, CATEGORY_CODE, RATE));
        }

        @Test
        @DisplayName("the type code participates individually")
        void theTypeCodeParticipatesIndividually() {
            assertThat(group()).isNotEqualTo(
                    new DisclosureGroup(GROUP_ID, "02", CATEGORY_CODE, RATE));
        }

        @Test
        @DisplayName("the category code participates individually")
        void theCategoryCodeParticipatesIndividually() {
            assertThat(group()).isNotEqualTo(
                    new DisclosureGroup(GROUP_ID, TYPE_CODE, "0001", RATE));
        }

        @Test
        @DisplayName("a padded group key is NOT equal to its trimmed form, which is what keeps the status-23 "
                + "fallback from appearing to resolve keys it does not resolve")
        void aPaddedGroupKeyIsNotEqualToItsTrimmedForm() {
            final DisclosureGroup padded = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, RATE);
            final DisclosureGroup trimmed = new DisclosureGroup("DEFAULT", TYPE_CODE,
                    CATEGORY_CODE, RATE);
            assertThat(padded).isNotEqualTo(trimmed);
            assertThat(new HashSet<>(Set.of(padded)).contains(trimmed)).isFalse();
        }

        @Test
        @DisplayName("a category code is compared exactly, so a zero-padded value is not equal to its numeric "
                + "shorthand")
        void aCategoryCodeIsComparedExactly() {
            assertThat(group()).isNotEqualTo(
                    new DisclosureGroup(GROUP_ID, TYPE_CODE, "5", RATE));
        }

        @Test
        @DisplayName("a row is not equal to null and not equal to a foreign type - notably not to its own "
                + "composite identifier, which carries the same three components")
        void aRowIsNotEqualToNullOrAForeignType() {
            final DisclosureGroup subject = group();
            assertThat(subject).isNotEqualTo(null);
            assertThat(subject.equals(subject.toId())).isFalse();
            assertThat(subject.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("two unset rows are equal, because all three key components are null on both")
        void twoUnsetRowsAreEqual() {
            assertThat(new DisclosureGroup()).isEqualTo(new DisclosureGroup());
        }

        @Test
        @DisplayName("changing the rate does not change the hash code, so a row already in a set cannot move")
        void changingTheRateDoesNotChangeTheHashCode() {
            final DisclosureGroup subject = group();
            final int before = subject.hashCode();
            subject.setDisIntRate(new BigDecimal("-9999.99"));
            assertThat(subject.hashCode()).isEqualTo(before);
        }

        @Test
        @DisplayName("a row remains findable in a hash set after its rate has changed")
        void aRowRemainsFindableInAHashSetAfterItsRateHasChanged() {
            final Set<DisclosureGroup> rows = new HashSet<>();
            final DisclosureGroup subject = group();
            rows.add(subject);
            subject.setDisIntRate(BigDecimal.ZERO);
            assertThat(rows).contains(subject);
            assertThat(rows.contains(group())).isTrue();
        }

        @Test
        @DisplayName("the hash code is stable across repeated invocations")
        void theHashCodeIsStableAcrossRepeatedInvocations() {
            final DisclosureGroup subject = group();
            assertThat(subject.hashCode()).isEqualTo(subject.hashCode());
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering: the three key components, quoted, and no rate")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering carries the three key components, each quoted so trailing padding stays visible")
        void theRenderingCarriesTheThreeKeyComponentsQuoted() {
            assertThat(group().toString())
                    .isEqualTo("DisclosureGroup[disAcctGroupId='" + GROUP_ID
                            + "', disTranTypeCd='" + TYPE_CODE
                            + "', disTranCatCd='" + CATEGORY_CODE + "']");
        }

        @Test
        @DisplayName("the quoting makes the padding visible, so a padded key and a trimmed key render differently")
        void theQuotingMakesThePaddingVisible() {
            final String padded = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE,
                    RATE).toString();
            final String trimmed = new DisclosureGroup("DEFAULT", TYPE_CODE, CATEGORY_CODE,
                    RATE).toString();
            assertThat(padded).isNotEqualTo(trimmed).contains("'DEFAULT   '");
            assertThat(trimmed).contains("'DEFAULT'");
        }

        @Test
        @DisplayName("the rate never appears in the rendering, because it is financial data")
        void theRateNeverAppearsInTheRendering() {
            assertThat(group().toString())
                    .doesNotContain("18.99")
                    .doesNotContain("disIntRate");
        }

        @Test
        @DisplayName("a hostile value planted in the rate cannot reach the rendering")
        void aHostileValuePlantedInTheRateCannotReachTheRendering() {
            final DisclosureGroup subject = group();
            subject.setDisIntRate(new BigDecimal("1234.56"));
            assertThat(subject.toString()).doesNotContain("1234.56");
        }

        @Test
        @DisplayName("the component list carries no credential or financial marker")
        void theComponentListCarriesNothingSensitive() {
            final String rendering = group().toString();
            final String componentList =
                    rendering.substring(rendering.indexOf('[') + 1, rendering.length() - 1);
            assertThat(componentList.toUpperCase(Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "$2A$", "RATE", "AMT");
        }

        @Test
        @DisplayName("an unset row renders without throwing")
        void anUnsetRowRendersWithoutThrowing() {
            assertThat(new DisclosureGroup().toString())
                    .startsWith("DisclosureGroup[")
                    .contains("null")
                    .endsWith("]");
        }
    }
}
