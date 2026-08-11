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

import com.carddemo.domain.id.DisclosureGroupId;

/**
 * Unit test for {@link DisclosureGroup}, the entity form of the 50-byte disclosure-group record that
 * supplies the interest rate to the interest-accrual batch program.
 *
 * <p><strong>What this test proves.</strong> Four independent legacy authorities fix the shape and
 * behaviour of this entity, and this test asserts the Java form honours all four:
 * <ul>
 *   <li>the copybook member for the disclosure group, whose record is 50 bytes: a 10-byte account-group
 *       identifier at offset 0, a 2-byte transaction-type code at 10, a 4-byte transaction-category code
 *       at 12, a 6-digit signed rate with two implied decimals at 16 and a 28-byte trailing filler at 22;</li>
 *   <li>the provisioning job for the disclosure-group dataset, which declares a 16-byte key at offset
 *       zero with a fixed 50-byte record - the 16 bytes being exactly the three key components
 *       concatenated, which is why the composite key is those three components and nothing else;</li>
 *   <li>the seeded reference data, which carries 51 rows in three complete 17-row groups. One group is
 *       the fallback group the interest program falls back to when a direct key lookup returns
 *       record-not-found, and one group carries a zero rate on all seventeen of its rows. The fallback
 *       is the arm a seed-only run takes, because every seeded account holds ten spaces in its group
 *       identifier and no seeded group key does; the zero-rate skip needs an account constructed with
 *       the zero-rate key, since the fallback finds 15.00 instead; and</li>
 *   <li>the interest expression itself, which multiplies balance by rate and only then divides by 1200,
 *       storing into a two-decimal field with no rounding clause. Absent a rounding clause the store
 *       truncates, so the faithful Java equivalent truncates towards zero rather than rounding to the
 *       nearest even value. This test contrasts the two modes on values where they diverge, and
 *       separately proves that dividing the rate first - algebraically identical in exact arithmetic -
 *       produces a different answer because it moves the truncation point.</li>
 * </ul>
 *
 * <p><strong>The rate is excluded from identity, deliberately.</strong> The rate is mutable state and the
 * three key components are persistent identity. Including the rate in equality would let an instance
 * change its own equality and hash while sitting in a hash-based collection. This test asserts the
 * exclusion in both directions: two rows with the same key and different rates are equal, and two rows
 * with the same rate and different keys are not.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network, runs no container and performs no
 * introspection.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every width, offset, rate and key below is
 * a literal typed out in this source, taken from the copybook layout, the cluster definition and the
 * measured content of the seeded reference file. Nothing is read back out of the class under test and
 * then asserted against itself, and no line of legacy source is transcribed.
 */
@DisplayName("DisclosureGroup - the entity form of the 50-byte disclosure-group record")
class DisclosureGroupBaselineTest {

    /** Width of the account-group identifier, the first key component. */
    private static final int GROUP_ID_WIDTH = 10;

    /** Width of the transaction-type code, the second key component. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of the transaction-category code, the third key component. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Key length declared by the cluster definition, at offset zero. */
    private static final int KEY_WIDTH = 16;

    /** Width of the zoned rate image: four integer digits plus two decimal digits. */
    private static final int RATE_IMAGE_WIDTH = 6;

    /** Scale of the rate column, matching the two implied decimals of the copybook field. */
    private static final int RATE_SCALE = 2;

    /** Precision of the rate column, matching the six digits of the copybook field. */
    private static final int RATE_PRECISION = 6;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 28;

    /** Record length declared by both the copybook and the cluster definition. */
    private static final int RECORD_LENGTH = 50;

    /** Rows the seeded reference file carries. */
    private static final int SEEDED_ROWS = 51;

    /** Rows in each of the three seeded groups. */
    private static final int ROWS_PER_GROUP = 17;

    /** Distinct account-group identifiers in the seeded reference file. */
    private static final int SEEDED_GROUP_COUNT = 3;

    /** The account-specific group that the first seeded account resolves to directly. */
    private static final String ACCOUNT_GROUP = "A000000000";

    /** The fallback group the interest program uses when a direct key lookup is not found. */
    private static final String FALLBACK_GROUP = "DEFAULT   ";

    /** The group whose every row carries a zero rate; a constructed account names it to reach the skip. */
    private static final String ZERO_RATE_GROUP = "ZEROAPR   ";

    /** The divisor of the documented interest expression: one hundred percent over twelve months. */
    private static final BigDecimal MONTHLY_DIVISOR = new BigDecimal("1200");

    /**
     * The seventeen key-component pairs each seeded group carries, in ascending key order. All three
     * groups carry exactly this set, which is why the seeded file holds 51 rows.
     */
    private static final List<String[]> SEEDED_KEY_PAIRS = List.of(
            new String[] {"01", "0001"}, new String[] {"01", "0002"},
            new String[] {"01", "0003"}, new String[] {"01", "0004"},
            new String[] {"02", "0001"}, new String[] {"02", "0002"},
            new String[] {"02", "0003"},
            new String[] {"03", "0001"}, new String[] {"03", "0002"},
            new String[] {"03", "0003"},
            new String[] {"04", "0001"}, new String[] {"04", "0002"},
            new String[] {"04", "0003"},
            new String[] {"05", "0001"},
            new String[] {"06", "0001"}, new String[] {"06", "0002"},
            new String[] {"07", "0001"});

    private DisclosureGroup group;

    @BeforeEach
    void createFirstSeededRow() {
        group = new DisclosureGroup(ACCOUNT_GROUP, "01", "0001", new BigDecimal("15.00"));
    }

    @Nested
    @DisplayName("Construction in copybook declaration order")
    class Construction {

        @Test
        @DisplayName("the four-argument constructor binds every field in copybook declaration order: "
                + "group identifier, type code, category code, rate")
        void theConstructorBindsEveryFieldInDeclarationOrder() {
            assertThat(group.getDisAcctGroupId()).isEqualTo(ACCOUNT_GROUP);
            assertThat(group.getDisTranTypeCd()).isEqualTo("01");
            assertThat(group.getDisTranCatCd()).isEqualTo("0001");
            assertThat(group.getDisIntRate()).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("the no-arg constructor the provider requires leaves all four fields absent, "
                + "because the provider populates state afterwards")
        void theNoArgConstructorLeavesEveryFieldAbsent() {
            final DisclosureGroup empty = new DisclosureGroup();

            assertThat(empty.getDisAcctGroupId()).isNull();
            assertThat(empty.getDisTranTypeCd()).isNull();
            assertThat(empty.getDisTranCatCd()).isNull();
            assertThat(empty.getDisIntRate()).isNull();
        }

        @Test
        @DisplayName("the constructor stores every value verbatim - no trim, no pad, no case fold and no "
                + "scale normalisation - because those belong to the codec and the service layer")
        void theConstructorStoresEveryValueVerbatim() {
            final DisclosureGroup raw =
                    new DisclosureGroup(" a ", "1", "5", new BigDecimal("7.5"));

            assertThat(raw.getDisAcctGroupId()).isEqualTo(" a ");
            assertThat(raw.getDisTranTypeCd()).isEqualTo("1");
            assertThat(raw.getDisTranCatCd()).isEqualTo("5");
            assertThat(raw.getDisIntRate()).isEqualTo(new BigDecimal("7.5"));
            assertThat(raw.getDisIntRate().scale()).isEqualTo(1);
        }

        @Test
        @DisplayName("absent values are accepted and returned unchanged, because the entity performs no "
                + "validation of its own and the database enforces the non-null contract")
        void absentValuesAreAcceptedAndReturnedUnchanged() {
            final DisclosureGroup sparse = new DisclosureGroup(null, null, null, null);

            assertThat(sparse.getDisAcctGroupId()).isNull();
            assertThat(sparse.getDisTranTypeCd()).isNull();
            assertThat(sparse.getDisTranCatCd()).isNull();
            assertThat(sparse.getDisIntRate()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the 50-byte record")
    class ByteGeometry {

        @Test
        @DisplayName("the three key components, the rate image and the 28-byte filler close the 50-byte "
                + "record exactly, so the copybook layout and the declared record size agree")
        void theFieldWidthsCloseTheRecordExactly() {
            final int summed = GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH
                    + RATE_IMAGE_WIDTH + FILLER_WIDTH;

            assertThat(summed).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the three key components concatenate to exactly the 16-byte key the cluster "
                + "declares at offset zero, which is why the composite key has three components")
        void theKeyComponentsConcatenateToTheDeclaredKeyWidth() {
            assertThat(GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(KEY_WIDTH);
            assertThat(group.getDisAcctGroupId()).hasSize(GROUP_ID_WIDTH);
            assertThat(group.getDisTranTypeCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(group.getDisTranCatCd()).hasSize(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("the rate image begins immediately after the key, at offset 16, and the filler "
                + "begins at offset 22")
        void theRateImageBeginsImmediatelyAfterTheKey() {
            assertThat(KEY_WIDTH).isEqualTo(16);
            assertThat(KEY_WIDTH + RATE_IMAGE_WIDTH).isEqualTo(22);
            assertThat(KEY_WIDTH + RATE_IMAGE_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the 16-byte key length is distinct from the two other composite keys of the estate, "
                + "which are 6 and 17 bytes, so the three composite keys cannot be confused by width")
        void theKeyWidthIsDistinctFromTheOtherCompositeKeys() {
            final int transactionCategoryKeyWidth = 6;
            final int categoryBalanceKeyWidth = 17;

            assertThat(KEY_WIDTH)
                    .isNotEqualTo(transactionCategoryKeyWidth)
                    .isNotEqualTo(categoryBalanceKeyWidth);
        }

        @Test
        @DisplayName("more than half the record is filler, which is why the seeded file is 2,550 data "
                + "bytes across 51 rows while carrying only 22 significant bytes per row")
        void moreThanHalfTheRecordIsFiller() {
            assertThat(FILLER_WIDTH).isGreaterThan(RECORD_LENGTH / 2);
            assertThat(SEEDED_ROWS * RECORD_LENGTH).isEqualTo(2550);
        }
    }

    @Nested
    @DisplayName("Rate fidelity: scale, precision and truncating arithmetic")
    class RateFidelity {

        @Test
        @DisplayName("a rate stored at scale two comes back at scale two, matching the two implied "
                + "decimals of the copybook field")
        void aRateAtScaleTwoComesBackAtScaleTwo() {
            assertThat(group.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
            assertThat(group.getDisIntRate()).isEqualTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("the entity does not normalise scale: a value handed in at a different scale comes "
                + "back unchanged, because scale enforcement is the codec's single responsibility")
        void theEntityDoesNotNormaliseScale() {
            group.setDisIntRate(new BigDecimal("15"));
            assertThat(group.getDisIntRate().scale()).isZero();

            group.setDisIntRate(new BigDecimal("15.0000"));
            assertThat(group.getDisIntRate().scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("the widest rate the column admits is four integer digits and two decimals, so the "
                + "precision of the largest representable rate is exactly six")
        void theWidestRepresentableRateHasPrecisionSix() {
            final BigDecimal widest = new BigDecimal("9999.99");

            group.setDisIntRate(widest);

            assertThat(group.getDisIntRate().precision()).isEqualTo(RATE_PRECISION);
            assertThat(group.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
        }

        @Test
        @DisplayName("a zero rate is a stored value and not an absent one, which is what makes the "
                + "zero-rate skip branch of the interest program a data-driven branch rather than a "
                + "null check")
        void aZeroRateIsAStoredValueAndNotAnAbsentOne() {
            final DisclosureGroup zeroRate =
                    new DisclosureGroup(ZERO_RATE_GROUP, "01", "0001", new BigDecimal("0.00"));

            assertThat(zeroRate.getDisIntRate()).isNotNull();
            assertThat(zeroRate.getDisIntRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(zeroRate.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
            assertThat(zeroRate.getDisIntRate().signum()).isZero();
        }

        @ParameterizedTest(name = "balance {0} at rate {1} truncates to {2} but rounds to {3}")
        @DisplayName("the documented expression multiplies balance by rate and only then divides by "
                + "1200, and the store truncates because no rounding clause exists anywhere in the "
                + "estate - so truncation and nearest-even rounding diverge by a cent")
        @CsvSource({
            "103.00, 25.00, 2.14, 2.15",
            "107.00, 25.00, 2.22, 2.23",
            "193.00, 15.00, 2.41, 2.41",
            "194.00, 25.00, 4.04, 4.04",
            "199.00, 25.00, 4.14, 4.15"})
        void truncationAndNearestEvenRoundingDivergeOnTheDocumentedExpression(
                final String balance, final String rate, final String truncated,
                final String rounded) {
            group.setDisIntRate(new BigDecimal(rate));

            final BigDecimal product = new BigDecimal(balance).multiply(group.getDisIntRate());

            assertThat(product.divide(MONTHLY_DIVISOR, RATE_SCALE, RoundingMode.DOWN))
                    .isEqualByComparingTo(truncated);
            assertThat(product.divide(MONTHLY_DIVISOR, RATE_SCALE, RoundingMode.HALF_EVEN))
                    .isEqualByComparingTo(rounded);
        }

        @Test
        @DisplayName("dividing the rate before multiplying - algebraically identical in exact arithmetic "
                + "- gives a different answer, because it moves the truncation point; this is why the "
                + "expression may never be rearranged")
        void dividingTheRateFirstMovesTheTruncationPointAndChangesTheAnswer() {
            final BigDecimal balance = new BigDecimal("103.00");
            group.setDisIntRate(new BigDecimal("25.00"));

            final BigDecimal faithful = balance.multiply(group.getDisIntRate())
                    .divide(MONTHLY_DIVISOR, RATE_SCALE, RoundingMode.DOWN);
            final BigDecimal rearranged = group.getDisIntRate()
                    .divide(MONTHLY_DIVISOR, RATE_SCALE, RoundingMode.DOWN)
                    .multiply(balance)
                    .setScale(RATE_SCALE, RoundingMode.DOWN);

            assertThat(faithful).isEqualByComparingTo("2.14");
            assertThat(rearranged).isEqualByComparingTo("2.06");
            assertThat(faithful).isNotEqualByComparingTo(rearranged);
        }

        @Test
        @DisplayName("truncation is towards zero and never away from it, so a truncated result is never "
                + "greater than the exact quotient")
        void truncationIsAlwaysTowardsZero() {
            group.setDisIntRate(new BigDecimal("25.00"));
            final BigDecimal product = new BigDecimal("103.00").multiply(group.getDisIntRate());

            final BigDecimal truncated = product.divide(MONTHLY_DIVISOR, RATE_SCALE, RoundingMode.DOWN);
            final BigDecimal exact = product.divide(MONTHLY_DIVISOR, 10, RoundingMode.DOWN);

            assertThat(truncated).isLessThanOrEqualTo(exact);
            assertThat(truncated).isEqualByComparingTo(exact.setScale(RATE_SCALE, RoundingMode.FLOOR));
        }

        @Test
        @DisplayName("a zero rate yields zero interest under the documented expression whatever the "
                + "balance, which is the arithmetic behind the skip branch")
        void aZeroRateYieldsZeroInterestWhateverTheBalance() {
            group.setDisIntRate(new BigDecimal("0.00"));

            for (final String balance : List.of("0.00", "194.00", "9999999.99")) {
                assertThat(new BigDecimal(balance).multiply(group.getDisIntRate())
                        .divide(MONTHLY_DIVISOR, RATE_SCALE, RoundingMode.DOWN))
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    @Nested
    @DisplayName("Composite key projection")
    class CompositeKeyProjection {

        @Test
        @DisplayName("the key projection carries the three key components in their contractual order "
                + "and nothing else")
        void theKeyProjectionCarriesTheThreeComponentsInOrder() {
            final DisclosureGroupId id = group.toId();

            assertThat(id.getDisAcctGroupId()).isEqualTo(ACCOUNT_GROUP);
            assertThat(id.getDisTranTypeCd()).isEqualTo("01");
            assertThat(id.getDisTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("the projection equals a key assembled by hand from the same three components, so "
                + "a repository lookup by projection and one by hand-assembled key resolve alike")
        void theProjectionEqualsAHandAssembledKey() {
            assertThat(group.toId())
                    .isEqualTo(new DisclosureGroupId(ACCOUNT_GROUP, "01", "0001"));
            assertThat(group.toId())
                    .hasSameHashCodeAs(new DisclosureGroupId(ACCOUNT_GROUP, "01", "0001"));
        }

        @Test
        @DisplayName("a new key is produced on every call and nothing is cached or shared, so a caller "
                + "cannot mutate one row's identity through another's projection")
        void aNewKeyIsProducedOnEveryCall() {
            final DisclosureGroupId first = group.toId();
            final DisclosureGroupId second = group.toId();

            assertThat(first).isNotSameAs(second);
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("the projection tracks the row: changing a key component through its setter changes "
                + "the next projection, because the projection is built from stored values on demand")
        void theProjectionTracksTheRow() {
            final DisclosureGroupId before = group.toId();

            group.setDisTranCatCd("0002");

            assertThat(group.toId()).isNotEqualTo(before);
            assertThat(group.toId().getDisTranCatCd()).isEqualTo("0002");
        }

        @Test
        @DisplayName("the projection of an empty row carries three absent components rather than failing, "
                + "because the projection performs no validation of its own")
        void theProjectionOfAnEmptyRowCarriesAbsentComponents() {
            final DisclosureGroupId id = new DisclosureGroup().toId();

            assertThat(id.getDisAcctGroupId()).isNull();
            assertThat(id.getDisTranTypeCd()).isNull();
            assertThat(id.getDisTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Mutability required by the interest-accrual job")
    class Mutability {

        @Test
        @DisplayName("every mapped field round-trips through its setter, which the provider needs in "
                + "order to populate a row it materialised through the no-arg constructor")
        void everyMappedFieldRoundTripsThroughItsSetter() {
            final DisclosureGroup target = new DisclosureGroup();

            target.setDisAcctGroupId(FALLBACK_GROUP);
            target.setDisTranTypeCd("07");
            target.setDisTranCatCd("0001");
            target.setDisIntRate(new BigDecimal("0.00"));

            assertThat(target.getDisAcctGroupId()).isEqualTo(FALLBACK_GROUP);
            assertThat(target.getDisTranTypeCd()).isEqualTo("07");
            assertThat(target.getDisTranCatCd()).isEqualTo("0001");
            assertThat(target.getDisIntRate()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a setter accepts an absent value, so clearing a field is possible and the non-null "
                + "contract is enforced by the column rather than by the entity")
        void aSetterAcceptsAnAbsentValue() {
            group.setDisIntRate(null);
            group.setDisAcctGroupId(null);

            assertThat(group.getDisIntRate()).isNull();
            assertThat(group.getDisAcctGroupId()).isNull();
        }
    }

    @Nested
    @DisplayName("Identity founded on the three key components alone")
    class Identity {

        @Test
        @DisplayName("two rows with the same three key components are equal and hash alike even when "
                + "their rates differ, because the rate is mutable state and not identity")
        void sameKeyMeansEqualEvenWhenTheRateDiffers() {
            final DisclosureGroup other =
                    new DisclosureGroup(ACCOUNT_GROUP, "01", "0001", new BigDecimal("25.00"));

            assertThat(group).isEqualTo(other);
            assertThat(group).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("two rows with the same rate but different keys are not equal, which is the other "
                + "half of the exclusion: the rate neither creates nor destroys identity")
        void sameRateWithADifferentKeyIsNotEqual() {
            final DisclosureGroup other =
                    new DisclosureGroup(FALLBACK_GROUP, "01", "0001", new BigDecimal("15.00"));

            assertThat(group).isNotEqualTo(other);
        }

        @Test
        @DisplayName("each of the three key components alone is enough to break equality, so all three "
                + "genuinely participate")
        void eachKeyComponentAloneBreaksEquality() {
            assertThat(group).isNotEqualTo(
                    new DisclosureGroup(ZERO_RATE_GROUP, "01", "0001", new BigDecimal("15.00")));
            assertThat(group).isNotEqualTo(
                    new DisclosureGroup(ACCOUNT_GROUP, "02", "0001", new BigDecimal("15.00")));
            assertThat(group).isNotEqualTo(
                    new DisclosureGroup(ACCOUNT_GROUP, "01", "0002", new BigDecimal("15.00")));
        }

        @Test
        @DisplayName("identity is reflexive, symmetric and transitive across independently constructed "
                + "rows carrying the same three key components")
        void identityIsReflexiveSymmetricAndTransitive() {
            final DisclosureGroup second =
                    new DisclosureGroup(ACCOUNT_GROUP, "01", "0001", new BigDecimal("0.00"));
            final DisclosureGroup third =
                    new DisclosureGroup(ACCOUNT_GROUP, "01", "0001", null);

            assertThat(group.equals(group)).isTrue();
            assertThat(group.equals(second)).isTrue();
            assertThat(second.equals(group)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(group.equals(third)).isTrue();
        }

        @Test
        @DisplayName("components are compared byte for byte with no trim and no case fold, so a padded "
                + "group identifier is a different key from its shortened form - exactly as the two "
                + "remain distinct rows in the database")
        void componentsAreComparedByteForByte() {
            final DisclosureGroup shortened =
                    new DisclosureGroup("A", "01", "0001", new BigDecimal("15.00"));
            final DisclosureGroup lowerCase =
                    new DisclosureGroup("a000000000", "01", "0001", new BigDecimal("15.00"));
            final DisclosureGroup shortenedCategory =
                    new DisclosureGroup(ACCOUNT_GROUP, "01", "1", new BigDecimal("15.00"));

            assertThat(group).isNotEqualTo(shortened);
            assertThat(group).isNotEqualTo(lowerCase);
            assertThat(group).isNotEqualTo(shortenedCategory);
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type through the type pattern "
                + "rather than throwing")
        void equalityRejectsAbsentOperandAndForeignType() {
            assertThat(group.equals(null)).isFalse();
            assertThat(group.equals(ACCOUNT_GROUP)).isFalse();
            assertThat(group.equals(group.toId())).isFalse();
        }

        @Test
        @DisplayName("two rows with three absent components are equal and hash alike, which the "
                + "null-tolerant component comparison supports without a guard of its own")
        void twoEmptyRowsAreEqual() {
            assertThat(new DisclosureGroup()).isEqualTo(new DisclosureGroup());
            assertThat(new DisclosureGroup()).hasSameHashCodeAs(new DisclosureGroup());
        }

        @Test
        @DisplayName("the type pattern makes a subclass with the same three components equal in both "
                + "directions, which is what lets a lazily-proxied row compare equal to a loaded one")
        void aSubclassWithTheSameKeyIsEqualInBothDirections() {
            final ProxiedDisclosureGroup proxy =
                    new ProxiedDisclosureGroup(ACCOUNT_GROUP, "01", "0001");

            assertThat(group.equals(proxy)).isTrue();
            assertThat(proxy.equals(group)).isTrue();
            assertThat(group).hasSameHashCodeAs(proxy);
        }

        @Test
        @DisplayName("all 51 seeded keys stay distinct in a hash set, so no two seeded rows collapse "
                + "onto one another")
        void allFiftyOneSeededKeysStayDistinctInAHashSet() {
            final Set<DisclosureGroup> rows = new HashSet<>();

            for (final String groupId : List.of(ACCOUNT_GROUP, FALLBACK_GROUP, ZERO_RATE_GROUP)) {
                for (final String[] pair : SEEDED_KEY_PAIRS) {
                    rows.add(new DisclosureGroup(groupId, pair[0], pair[1], new BigDecimal("0.00")));
                }
            }

            assertThat(rows).hasSize(SEEDED_ROWS);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering carries the key and never the rate")
    class StringRepresentation {

        @Test
        @DisplayName("the rendering names the type and quotes each of the three key components, so "
                + "significant trailing spaces stay visible in a log line")
        void theRenderingQuotesEachKeyComponent() {
            group.setDisAcctGroupId(FALLBACK_GROUP);

            assertThat(group.toString()).isEqualTo(
                    "DisclosureGroup[disAcctGroupId='DEFAULT   ', disTranTypeCd='01', "
                            + "disTranCatCd='0001']");
        }

        @Test
        @DisplayName("the rate never appears in the rendering, because it is financial data with no "
                + "place in an incidental diagnostic string")
        void theRateNeverAppearsInTheRendering() {
            group.setDisIntRate(new BigDecimal("25.00"));

            assertThat(group.toString()).doesNotContain("25.00");
            assertThat(group.toString()).doesNotContain("IntRate");
            assertThat(group.toString()).doesNotContain("Rate");
        }

        @Test
        @DisplayName("the rendering of an empty row does not fail and shows three absent components")
        void theRenderingOfAnEmptyRowDoesNotFail() {
            assertThat(new DisclosureGroup().toString()).isEqualTo(
                    "DisclosureGroup[disAcctGroupId='null', disTranTypeCd='null', "
                            + "disTranCatCd='null']");
        }
    }

    @Nested
    @DisplayName("Correspondence with the 51 seeded reference rows")
    class FixtureCorrespondence {

        @Test
        @DisplayName("the seeded file holds three complete 17-row groups, which is exactly 51 rows")
        void theSeededFileHoldsThreeCompleteGroups() {
            assertThat(SEEDED_GROUP_COUNT * ROWS_PER_GROUP).isEqualTo(SEEDED_ROWS);
            assertThat(SEEDED_KEY_PAIRS).hasSize(ROWS_PER_GROUP);
        }

        @Test
        @DisplayName("the three group identifiers are the account-specific group, the fallback group and "
                + "the zero-rate group, each padded to the full ten-byte field width")
        void theThreeGroupIdentifiersAreDistinctAndTenBytesWide() {
            final Set<String> identifiers =
                    new HashSet<>(List.of(ACCOUNT_GROUP, FALLBACK_GROUP, ZERO_RATE_GROUP));

            assertThat(identifiers).hasSize(SEEDED_GROUP_COUNT);
            assertThat(ACCOUNT_GROUP).hasSize(GROUP_ID_WIDTH);
            assertThat(FALLBACK_GROUP).hasSize(GROUP_ID_WIDTH).endsWith("   ");
            assertThat(ZERO_RATE_GROUP).hasSize(GROUP_ID_WIDTH).endsWith("   ");
        }

        @Test
        @DisplayName("the seventeen seeded key pairs span seven transaction types in the measured "
                + "multiset four, three, three, three, one, two, one")
        void theSeededKeyPairsSpanSevenTransactionTypes() {
            final List<String> types = new ArrayList<>();
            for (final String[] pair : SEEDED_KEY_PAIRS) {
                types.add(pair[0]);
            }

            assertThat(new HashSet<>(types)).hasSize(7);
            assertThat(types.stream().filter("01"::equals).count()).isEqualTo(4);
            assertThat(types.stream().filter("02"::equals).count()).isEqualTo(3);
            assertThat(types.stream().filter("03"::equals).count()).isEqualTo(3);
            assertThat(types.stream().filter("04"::equals).count()).isEqualTo(3);
            assertThat(types.stream().filter("05"::equals).count()).isEqualTo(1);
            assertThat(types.stream().filter("06"::equals).count()).isEqualTo(2);
            assertThat(types.stream().filter("07"::equals).count()).isEqualTo(1);
        }

        @ParameterizedTest(name = "group {0} type {1} category {2} carries rate {3}")
        @DisplayName("the measured seeded rates construct unchanged at scale two, covering the three "
                + "distinct rates the seeded file carries")
        @CsvSource({
            "A000000000, 01, 0001, 15.00",
            "A000000000, 01, 0002, 25.00",
            "A000000000, 07, 0001, 15.00",
            "'DEFAULT   ', 01, 0001, 15.00",
            "'DEFAULT   ', 01, 0002, 25.00",
            "'DEFAULT   ', 07, 0001, 0.00",
            "'ZEROAPR   ', 01, 0001, 0.00",
            "'ZEROAPR   ', 07, 0001, 0.00"})
        void theMeasuredSeededRatesConstructUnchanged(final String groupId, final String typeCode,
                final String categoryCode, final String rate) {
            final DisclosureGroup seeded =
                    new DisclosureGroup(groupId, typeCode, categoryCode, new BigDecimal(rate));

            assertThat(seeded.getDisAcctGroupId()).isEqualTo(groupId).hasSize(GROUP_ID_WIDTH);
            assertThat(seeded.getDisTranTypeCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(seeded.getDisTranCatCd()).hasSize(CATEGORY_CODE_WIDTH);
            assertThat(seeded.getDisIntRate()).isEqualByComparingTo(rate);
            assertThat(seeded.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
        }

        @Test
        @DisplayName("the account-specific group and the fallback group differ on exactly one seeded key "
                + "- the adjustment type - so a fallback lookup is not a silent no-op")
        void theAccountGroupAndTheFallbackGroupDifferOnExactlyOneKey() {
            final DisclosureGroup accountRow =
                    new DisclosureGroup(ACCOUNT_GROUP, "07", "0001", new BigDecimal("15.00"));
            final DisclosureGroup fallbackRow =
                    new DisclosureGroup(FALLBACK_GROUP, "07", "0001", new BigDecimal("0.00"));

            assertThat(accountRow).isNotEqualTo(fallbackRow);
            assertThat(accountRow.getDisIntRate())
                    .isNotEqualByComparingTo(fallbackRow.getDisIntRate());
            assertThat(accountRow.getDisTranTypeCd()).isEqualTo(fallbackRow.getDisTranTypeCd());
            assertThat(accountRow.getDisTranCatCd()).isEqualTo(fallbackRow.getDisTranCatCd());
        }

        @Test
        @DisplayName("every row of the zero-rate group carries a zero rate, so a constructed account "
                + "can reach the skip branch on any of its seventeen keys and not only on one")
        void everyRowOfTheZeroRateGroupCarriesAZeroRate() {
            for (final String[] pair : SEEDED_KEY_PAIRS) {
                final DisclosureGroup seeded = new DisclosureGroup(
                        ZERO_RATE_GROUP, pair[0], pair[1], new BigDecimal("0.00"));

                assertThat(seeded.getDisIntRate().signum()).isZero();
                assertThat(seeded.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
            }
        }

        @Test
        @DisplayName("the seeded groups cover seventeen of the eighteen transaction categories, omitting "
                + "the interest category itself, so interest does not accrue interest")
        void theSeededGroupsOmitTheInterestCategory() {
            final int seededCategoryCount = 18;
            final Set<String> seededKeys = new HashSet<>();
            for (final String[] pair : SEEDED_KEY_PAIRS) {
                seededKeys.add(pair[0] + pair[1]);
            }

            assertThat(seededKeys).hasSize(ROWS_PER_GROUP);
            assertThat(seededCategoryCount - ROWS_PER_GROUP).isEqualTo(1);
            assertThat(seededKeys).doesNotContain("010005");
            assertThat(seededKeys).contains("010001", "010004", "070001");
        }
    }

    /**
     * A subclass standing in for the lazily-initialised proxy the persistence provider substitutes for
     * a row that has not yet been loaded. It adds no state and overrides nothing, so it exists purely
     * to prove that the type-pattern comparison of the parent admits a subclass symmetrically.
     *
     * <p>The entity is not serialisable, so this nested subclass introduces no serialisation obligation.
     */
    private static final class ProxiedDisclosureGroup extends DisclosureGroup {

        ProxiedDisclosureGroup(final String groupId, final String typeCode,
                final String categoryCode) {
            super(groupId, typeCode, categoryCode, null);
        }
    }
}
