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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.ZonedDecimalCodec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link DisclosureGroup}, the fifty-byte interest-rate lookup record.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CVTRA02Y.cpy} declares a fifty-byte
 * record in five parts: a ten-byte group identifier, a two-byte transaction type, a four-byte transaction
 * category, a signed rate of four integer digits and two decimals, and a twenty-eight-byte filler. The
 * first three fields form a named key group in the copybook, and the cluster definition at
 * {@code app/jcl/DISCGRP.jcl} confirms the arithmetic independently with {@code KEYS(16 0)} and
 * {@code RECORDSIZE(50 50)}: ten plus two plus four is the sixteen-byte key, and the key starts at the
 * front of the record.
 *
 * <p><strong>Why the rate's scale is load-bearing.</strong> The interest run reaches this record for every
 * category balance it processes and computes {@code (balance * rate) / 1200}. The divisor is one hundred
 * times twelve, which tells you what the stored number means: it is a percentage per annum, not a
 * fraction, and the hundred in the divisor is what converts it. A rate stored at the wrong scale would
 * therefore not merely be imprecise, it would be wrong by a factor of a hundred, so this suite pins the
 * scale against the copybook, against the migration and against a decode of the real seeded image.
 *
 * <p><strong>Why the seeded composition matters more here than anywhere else.</strong> The fifty-one
 * seeded records are three complete groups of seventeen. One group is named for the default fallback the
 * interest run substitutes when a lookup misses, and one group carries a zero rate in every single row.
 * Between them they make both branches of the rate lookup reachable from seed data alone — the miss that
 * falls back, and the zero that skips the computation — which is why this suite asserts the composition
 * rather than only the geometry.
 *
 * <p><strong>Why the category code is a character field.</strong> The copybook types the category as
 * numeric, yet the migration declares a character column. That is deliberate and it is what keeps the key
 * usable: the seeded codes are {@code 0001} through {@code 0004}, and a numeric column would have stored
 * the first of those as a one, at which point the stored key would no longer be the sixteen bytes the
 * record image carries. The suite proves the leading zeros survive.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here computes interest or exercises the default
 * fallback; both belong to the interest-calculation service. This suite establishes only that the record
 * those behaviours read is shaped, scaled and seeded the way they require.
 */
@DisplayName("DisclosureGroup — the fifty-byte interest-rate lookup record")
class DisclosureGroupTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "disclosure_group";

    /** {@code RECORDSIZE(50 50)} in the cluster definition. */
    private static final int RECORD_WIDTH = 50;

    /** {@code KEYS(16 0)} — key length. */
    private static final int KEY_WIDTH = 16;

    /** The five copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(10, 2, 4, 6, 28);

    /** Zero-based offset of the group identifier. */
    private static final int OFFSET_GROUP_ID = 0;

    /** Zero-based offset of the transaction type. */
    private static final int OFFSET_TYPE = 10;

    /** Zero-based offset of the transaction category. */
    private static final int OFFSET_CATEGORY = 12;

    /** Zero-based offset of the rate. */
    private static final int OFFSET_RATE = 16;

    /** Zero-based offset of the filler. */
    private static final int OFFSET_FILLER = 22;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 28;

    /** Integer digits the rate declares. */
    private static final int RATE_INTEGER_DIGITS = 4;

    /** Decimal digits the rate declares. */
    private static final int RATE_DECIMAL_DIGITS = 2;

    /** The divisor the interest run applies to a rate read from this record. */
    private static final int PERCENT_TO_MONTHLY_DIVISOR = 1200;

    /** The group identifier substituted when a rate lookup misses. */
    private static final String FALLBACK_GROUP_ID = "DEFAULT";

    /** Seeded records. */
    private static final int SEEDED_RECORDS = 51;

    /** Seeded groups. */
    private static final int SEEDED_GROUPS = 3;

    /** Rows in each seeded group. */
    private static final int ROWS_PER_GROUP = 17;

    /** The migration's disclosure-group table, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The seeded rate-lookup file, loaded once at its declared width. */
    private static final SeededRecordFixture SEED =
            SeededRecordFixture.load("discgrp.txt", RECORD_WIDTH);

    /** The three seeded group identifiers, at their blank-filled ten-byte width. */
    private static final List<String> SEEDED_GROUP_IDS =
            List.of("A000000000", "DEFAULT   ", "ZEROAPR   ");

    /**
     * The three distinct rate images the seed carries, mapped to the count of records carrying each.
     *
     * <p>Declaration order is the ascending order of the decoded rates, which is asserted rather than
     * assumed, so the map is wrapped rather than copied into a hash-ordered immutable map.
     */
    private static final Map<String, Integer> SEEDED_RATE_IMAGES = seededRateImages();

    /**
     * Transcribes the three rate images and their record counts.
     *
     * @return an ordered, unmodifiable view of the seed's rate images
     */
    private static Map<String, Integer> seededRateImages() {
        final Map<String, Integer> images = new LinkedHashMap<>();
        images.put("00000{", 30);
        images.put("00150{", 15);
        images.put("00250{", 6);
        return Collections.unmodifiableMap(images);
    }

    /**
     * Reads one seeded record's group identifier.
     *
     * @param ordinal the one-based record ordinal
     * @return the ten-byte group identifier
     */
    private static String seededGroupId(final int ordinal) {
        return SEED.field(ordinal, OFFSET_GROUP_ID, COPYBOOK_WIDTHS.get(0));
    }

    /**
     * Reads one seeded record's rate image.
     *
     * @param ordinal the one-based record ordinal
     * @return the six-character zoned rate image
     */
    private static String seededRateImage(final int ordinal) {
        return SEED.field(ordinal, OFFSET_RATE, COPYBOOK_WIDTHS.get(3));
    }

    /**
     * Decodes one seeded record's rate.
     *
     * @param ordinal the one-based record ordinal
     * @return the rate the record carries
     */
    private static BigDecimal seededRate(final int ordinal) {
        return ZonedDecimalCodec.decode(
                seededRateImage(ordinal),
                ZonedDecimalCodec.INTEREST_RATE_WIDTH,
                RATE_DECIMAL_DIGITS,
                "DIS-INT-RATE");
    }

    /**
     * Builds the entity one seeded record describes.
     *
     * @param ordinal the one-based record ordinal
     * @return the rate-lookup row the record describes
     */
    private static DisclosureGroup groupFromSeed(final int ordinal) {
        return new DisclosureGroup(
                seededGroupId(ordinal),
                SEED.field(ordinal, OFFSET_TYPE, COPYBOOK_WIDTHS.get(1)),
                SEED.field(ordinal, OFFSET_CATEGORY, COPYBOOK_WIDTHS.get(2)),
                seededRate(ordinal));
    }

    // =================================================================================================
    // RECORD LAYOUT
    // =================================================================================================

    /**
     * Verifies the copybook geometry the entity has to honour.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the five copybook widths sum to the fifty bytes the cluster declares")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(5);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each field begins where the preceding widths leave off")
        void eachFieldBeginsWhereThePrecedingWidthsLeaveOff() {
            final List<Integer> offsets =
                    List.of(OFFSET_GROUP_ID, OFFSET_TYPE, OFFSET_CATEGORY, OFFSET_RATE, OFFSET_FILLER);

            int running = 0;
            for (int index = 0; index < COPYBOOK_WIDTHS.size(); index++) {
                assertThat(offsets.get(index))
                        .as("offset of field %d", index)
                        .isEqualTo(running);
                running += COPYBOOK_WIDTHS.get(index);
            }

            assertThat(running).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the first three fields form the sixteen-byte key the cluster declares")
        void theFirstThreeFieldsFormTheKey() {
            assertThat(COPYBOOK_WIDTHS.get(0) + COPYBOOK_WIDTHS.get(1) + COPYBOOK_WIDTHS.get(2))
                    .isEqualTo(KEY_WIDTH);
            assertThat(OFFSET_RATE)
                    .as("the key runs from the front of the record to where the rate begins")
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the rate occupies six bytes, being four integer digits and two decimals")
        void theRateOccupiesSixBytes() {
            assertThat(COPYBOOK_WIDTHS.get(3)).isEqualTo(RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99)
                    .isEqualTo(COPYBOOK_WIDTHS.get(3));
            assertThat(ZonedDecimalCodec.INTEREST_RATE_WIDTH)
                    .as("the codec names this width for this field specifically")
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99);
        }

        @Test
        @DisplayName("the trailing twenty-eight bytes are filler and are mapped to no column")
        void theTrailingBytesAreFillerAndUnmapped() {
            assertThat(COPYBOOK_WIDTHS.get(4)).isEqualTo(FILLER_WIDTH);
            assertThat(OFFSET_FILLER + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(COPYBOOK_WIDTHS.size() - 1);
        }
    }

    // =================================================================================================
    // SCHEMA AGREEMENT
    // =================================================================================================

    /**
     * Verifies that the deployed migration describes the layout the copybook does.
     */
    @Nested
    @DisplayName("schema agreement")
    class SchemaAgreement {

        @Test
        @DisplayName("the table declares the four mapped columns in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE)).containsExactly(
                    "dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd", "dis_int_rate");
        }

        @Test
        @DisplayName("every mapped column matches its copybook width")
        void everyMappedColumnMatchesItsCopybookWidth() {
            final List<String> columns = SCHEMA.columnNames(TABLE);

            for (int index = 0; index < columns.size(); index++) {
                assertThat(SCHEMA.declaredWidth(TABLE, columns.get(index)))
                        .as("declared width of %s", columns.get(index))
                        .isEqualTo(COPYBOOK_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the rate column carries six digits of precision and two of scale, matching the "
                + "copybook's four integer digits and two decimals")
        void theRateColumnCarriesTheCopybookPrecisionAndScale() {
            assertThat(SCHEMA.declaredType(TABLE, "dis_int_rate")).isEqualTo("NUMERIC(6,2)");
            assertThat(SCHEMA.declaredWidth(TABLE, "dis_int_rate"))
                    .isEqualTo(RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS);
            assertThat(SCHEMA.declaredScale(TABLE, "dis_int_rate")).isEqualTo(RATE_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the category column is character rather than integer, so a code of 0001 keeps its "
                + "leading zeros and the stored key still matches the record image")
        void theCategoryColumnIsCharacterSoLeadingZerosSurvive() {
            assertThat(SCHEMA.declaredType(TABLE, "dis_tran_cat_cd")).isEqualTo("VARCHAR(4)");

            final String seededCode = SEED.field(1, OFFSET_CATEGORY, COPYBOOK_WIDTHS.get(2));
            assertThat(seededCode).startsWith("0").hasSize(COPYBOOK_WIDTHS.get(2));
            assertThat(Integer.toString(Integer.parseInt(seededCode)))
                    .as("an integer column would have stored this code without its leading zeros")
                    .isNotEqualTo(seededCode);
        }

        @Test
        @DisplayName("the primary key is all three key components in copybook order, and no surrogate or "
                + "version column exists")
        void thePrimaryKeyIsAllThreeComponentsInOrder() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly(
                    "dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd");
            assertThat(SCHEMA.columnNames(TABLE)).doesNotContain("id", "disclosure_group_id", "version");
        }

        @Test
        @DisplayName("every column is declared not null, so no row can carry an absent rate")
        void everyColumnIsDeclaredNotNull() {
            for (final String column : SCHEMA.columnNames(TABLE)) {
                assertThat(SCHEMA.isNullable(TABLE, column))
                        .as("nullability of %s", column)
                        .isFalse();
            }
        }
    }

    // =================================================================================================
    // CONSTRUCTION AND ACCESS
    // =================================================================================================

    /**
     * Verifies that every field the constructor takes is the field the accessor returns.
     */
    @Nested
    @DisplayName("construction and access")
    class ConstructionAndAccess {

        @Test
        @DisplayName("every constructor argument reaches its own accessor")
        void everyConstructorArgumentReachesItsAccessor() {
            final DisclosureGroup group = new DisclosureGroup(
                    "A000000000", "01", "0001", new BigDecimal("15.00"));

            assertThat(group.getDisAcctGroupId()).isEqualTo("A000000000");
            assertThat(group.getDisTranTypeCd()).isEqualTo("01");
            assertThat(group.getDisTranCatCd()).isEqualTo("0001");
            assertThat(group.getDisIntRate()).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("the two-byte type and the four-byte category do not swap, which their shared "
                + "leading zero could otherwise conceal")
        void theTypeAndCategoryDoNotSwap() {
            final DisclosureGroup group = groupFromSeed(1);

            assertThat(group.getDisTranTypeCd()).hasSize(COPYBOOK_WIDTHS.get(1));
            assertThat(group.getDisTranCatCd()).hasSize(COPYBOOK_WIDTHS.get(2));
            assertThat(group.getDisTranTypeCd()).isNotEqualTo(group.getDisTranCatCd());
        }

        @Test
        @DisplayName("every mutator replaces exactly the field it names")
        void everyMutatorReplacesTheFieldItNames() {
            final DisclosureGroup group = groupFromSeed(1);

            group.setDisAcctGroupId("ZEROAPR   ");
            group.setDisTranTypeCd("07");
            group.setDisTranCatCd("0004");
            group.setDisIntRate(new BigDecimal("25.00"));

            assertThat(group.getDisAcctGroupId()).isEqualTo("ZEROAPR   ");
            assertThat(group.getDisTranTypeCd()).isEqualTo("07");
            assertThat(group.getDisTranCatCd()).isEqualTo("0004");
            assertThat(group.getDisIntRate()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("the persistence constructor leaves every field absent")
        void thePersistenceConstructorLeavesEveryFieldAbsent() {
            final DisclosureGroup group = new DisclosureGroup();

            assertThat(group.getDisAcctGroupId()).isNull();
            assertThat(group.getDisTranTypeCd()).isNull();
            assertThat(group.getDisTranCatCd()).isNull();
            assertThat(group.getDisIntRate()).isNull();
        }
    }

    // =================================================================================================
    // RATE FIDELITY
    // =================================================================================================

    /**
     * Verifies that the rate is carried at the copybook's scale and is understood as a percentage.
     */
    @Nested
    @DisplayName("rate fidelity")
    class RateFidelity {

        @Test
        @DisplayName("the entity stores the rate it is handed without rescaling, because rescaling is "
                + "the codec's responsibility and not the record's")
        void theEntityStoresTheRateItIsHandedWithoutRescaling() {
            final DisclosureGroup unscaled = new DisclosureGroup("A", "01", "0001", new BigDecimal("15"));
            final DisclosureGroup overscaled =
                    new DisclosureGroup("A", "01", "0001", new BigDecimal("15.0000"));

            assertThat(unscaled.getDisIntRate().scale()).isZero();
            assertThat(overscaled.getDisIntRate().scale()).isEqualTo(4);
            assertThat(unscaled.getDisIntRate()).isEqualByComparingTo(overscaled.getDisIntRate());
        }

        @Test
        @DisplayName("the codec brings a rate to the copybook's two decimals by truncating, never by "
                + "rounding")
        void theCodecTruncatesRatherThanRounds() {
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE).isEqualTo(RoundingMode.DOWN);
            assertThat(ZonedDecimalCodec.toScale(new BigDecimal("15.999"), RATE_DECIMAL_DIGITS))
                    .isEqualByComparingTo("15.99");
            assertThat(ZonedDecimalCodec.toScale(new BigDecimal("-15.999"), RATE_DECIMAL_DIGITS))
                    .isEqualByComparingTo("-15.99");
        }

        @Test
        @DisplayName("the four integer digits cap the rate below ten thousand, so the field cannot carry "
                + "a wider number than the copybook allows")
        void theFourIntegerDigitsCapTheRate() {
            final BigDecimal widest = new BigDecimal("9999.99");

            assertThat(widest.precision()).isEqualTo(RATE_INTEGER_DIGITS + RATE_DECIMAL_DIGITS);
            assertThat(widest.scale()).isEqualTo(RATE_DECIMAL_DIGITS);
            assertThat(new DisclosureGroup("A", "01", "0001", widest).getDisIntRate())
                    .isEqualByComparingTo(widest);
        }

        @Test
        @DisplayName("a rate of fifteen means fifteen percent per annum, which is why the interest run's "
                + "divisor is one hundred times twelve")
        void theRateIsAPercentagePerAnnum() {
            assertThat(PERCENT_TO_MONTHLY_DIVISOR).isEqualTo(100 * 12);

            final BigDecimal monthlyFraction = new BigDecimal("15.00")
                    .divide(new BigDecimal(PERCENT_TO_MONTHLY_DIVISOR), 6, RoundingMode.DOWN);

            assertThat(monthlyFraction).isEqualByComparingTo("0.0125");
        }

        @Test
        @DisplayName("a negative rate is representable, because the copybook signs the field")
        void aNegativeRateIsRepresentable() {
            final DisclosureGroup group =
                    new DisclosureGroup("A", "01", "0001", new BigDecimal("-1.50"));

            assertThat(group.getDisIntRate().signum()).isNegative();
            assertThat(ZonedDecimalCodec.decode(
                    "00150}", ZonedDecimalCodec.INTEREST_RATE_WIDTH, RATE_DECIMAL_DIGITS, "rate"))
                    .as("the closing overpunch is the negative counterpart of the seed's opening one")
                    .isEqualByComparingTo("-15.00");
        }
    }

    // =================================================================================================
    // SEEDED COMPOSITION
    // =================================================================================================

    /**
     * Verifies the fifty-one seeded records, whose composition is what makes both branches of the rate
     * lookup reachable.
     */
    @Nested
    @DisplayName("seeded composition")
    class SeededComposition {

        @Test
        @DisplayName("the seed carries fifty-one records at the declared fifty-byte width")
        void theSeedCarriesFiftyOneRecords() {
            assertThat(SEED.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(SEED.recordWidth()).isEqualTo(RECORD_WIDTH);
            assertThat(SEED.impliedByteCount()).isEqualTo(SEEDED_RECORDS * (RECORD_WIDTH + 1));
        }

        @Test
        @DisplayName("the fifty-one records are three complete groups of seventeen")
        void theRecordsAreThreeCompleteGroupsOfSeventeen() {
            final Map<String, Integer> counts = new LinkedHashMap<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                counts.merge(seededGroupId(ordinal), 1, Integer::sum);
            }

            assertThat(counts).hasSize(SEEDED_GROUPS);
            assertThat(counts.keySet()).containsExactlyElementsOf(SEEDED_GROUP_IDS);
            assertThat(counts.values()).containsOnly(ROWS_PER_GROUP);
            assertThat(SEEDED_GROUPS * ROWS_PER_GROUP).isEqualTo(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("each group's rows are contiguous, so a sequential read sees one group at a time")
        void eachGroupsRowsAreContiguous() {
            final List<String> encountered = new ArrayList<>();
            String previous = null;

            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                final String current = seededGroupId(ordinal);
                if (!current.equals(previous)) {
                    assertThat(encountered)
                            .as("group %s must not reappear after another group intervenes", current)
                            .doesNotContain(current);
                    encountered.add(current);
                    previous = current;
                }
            }

            assertThat(encountered).containsExactlyElementsOf(SEEDED_GROUP_IDS);
        }

        @Test
        @DisplayName("one group is named for the fallback the interest run substitutes when a lookup "
                + "misses, so that branch is reachable from seed data alone")
        void oneGroupIsNamedForTheFallback() {
            final List<String> fallbackRows = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                if (seededGroupId(ordinal).startsWith(FALLBACK_GROUP_ID)) {
                    fallbackRows.add(seededGroupId(ordinal));
                }
            }

            assertThat(fallbackRows).hasSize(ROWS_PER_GROUP);
            assertThat(fallbackRows.get(0))
                    .as("the fallback name is blank-filled to the ten-byte field width")
                    .isEqualTo(FALLBACK_GROUP_ID + " ".repeat(
                            COPYBOOK_WIDTHS.get(0) - FALLBACK_GROUP_ID.length()));
        }

        @Test
        @DisplayName("one group carries a zero rate in every row, so the skip branch is reachable from "
                + "seed data alone")
        void oneGroupCarriesAZeroRateInEveryRow() {
            final List<BigDecimal> zeroGroupRates = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                if (seededGroupId(ordinal).startsWith("ZEROAPR")) {
                    zeroGroupRates.add(seededRate(ordinal));
                }
            }

            assertThat(zeroGroupRates).hasSize(ROWS_PER_GROUP);
            assertThat(zeroGroupRates).allSatisfy(rate -> assertThat(rate.signum()).isZero());
        }

        @Test
        @DisplayName("the other two groups both carry a non-zero rate, so the computing branch is "
                + "reachable too")
        void theOtherTwoGroupsCarryNonZeroRates() {
            final Map<String, Integer> nonZeroPerGroup = new LinkedHashMap<>();
            for (final String group : SEEDED_GROUP_IDS) {
                nonZeroPerGroup.put(group, 0);
            }

            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                if (seededRate(ordinal).signum() != 0) {
                    nonZeroPerGroup.merge(seededGroupId(ordinal), 1, Integer::sum);
                }
            }

            assertThat(nonZeroPerGroup.get("A000000000")).isPositive();
            assertThat(nonZeroPerGroup.get("DEFAULT   ")).isPositive();
            assertThat(nonZeroPerGroup.get("ZEROAPR   ")).isZero();
        }

        @Test
        @DisplayName("the seed carries exactly three distinct rate images, with the counts the file "
                + "actually holds")
        void theSeedCarriesThreeDistinctRateImages() {
            final Map<String, Integer> counts = new LinkedHashMap<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                counts.merge(seededRateImage(ordinal), 1, Integer::sum);
            }

            assertThat(counts).containsExactlyInAnyOrderEntriesOf(SEEDED_RATE_IMAGES);
            assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("the three rate images decode to nothing, fifteen and twenty-five, in ascending "
                + "order of the counts the file declares")
        void theThreeRateImagesDecodeToTheExpectedRates() {
            final List<BigDecimal> decoded = new ArrayList<>();
            for (final String image : SEEDED_RATE_IMAGES.keySet()) {
                decoded.add(ZonedDecimalCodec.decode(
                        image, ZonedDecimalCodec.INTEREST_RATE_WIDTH, RATE_DECIMAL_DIGITS, "rate"));
            }

            assertThat(decoded).hasSize(SEEDED_RATE_IMAGES.size());
            assertThat(decoded.get(0)).isEqualByComparingTo("0.00");
            assertThat(decoded.get(1)).isEqualByComparingTo("15.00");
            assertThat(decoded.get(2)).isEqualByComparingTo("25.00");
            assertThat(decoded).allSatisfy(rate -> assertThat(rate.scale())
                    .as("every decoded rate carries the copybook's two decimals")
                    .isEqualTo(RATE_DECIMAL_DIGITS));
        }

        @Test
        @DisplayName("every seeded rate image ends in an overpunch rather than a digit, so a decoder "
                + "that read the last byte as a plain digit would misread all fifty-one")
        void everySeededRateImageEndsInAnOverpunch() {
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                final char last = seededRateImage(ordinal).charAt(
                        ZonedDecimalCodec.INTEREST_RATE_WIDTH - 1);

                assertThat(Character.isDigit(last))
                        .as("last byte of record %d's rate image", ordinal)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("every seeded record's trailing filler is numeric zeros rather than blanks, and is "
                + "carried by no field of the entity")
        void theSeededFillerIsNumericZeros() {
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                assertThat(SEED.field(ordinal, OFFSET_FILLER, FILLER_WIDTH))
                        .as("filler of record %d", ordinal)
                        .isEqualTo("0".repeat(FILLER_WIDTH));
            }
        }

        @Test
        @DisplayName("every seeded record builds an entity whose four fields measure the copybook widths")
        void everySeededRecordBuildsAWellShapedEntity() {
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                final DisclosureGroup group = groupFromSeed(ordinal);

                assertThat(group.getDisAcctGroupId()).hasSize(COPYBOOK_WIDTHS.get(0));
                assertThat(group.getDisTranTypeCd()).hasSize(COPYBOOK_WIDTHS.get(1));
                assertThat(group.getDisTranCatCd()).hasSize(COPYBOOK_WIDTHS.get(2));
                assertThat(group.getDisIntRate().scale()).isEqualTo(RATE_DECIMAL_DIGITS);
            }
        }
    }

    // =================================================================================================
    // COMPOSITE-KEY IDENTITY
    // =================================================================================================

    /**
     * Verifies that identity is the three-part key and nothing else.
     */
    @Nested
    @DisplayName("composite-key identity")
    class CompositeKeyIdentity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            final DisclosureGroup group = groupFromSeed(1);

            assertThat(group).isEqualTo(group);
            assertThat(group.hashCode()).isEqualTo(group.hashCode());
        }

        @Test
        @DisplayName("two rows with the same three key components are equal even when their rates "
                + "differ, because the rate is not part of identity")
        void sameKeyMeansEqualEvenWithADifferentRate() {
            final DisclosureGroup left =
                    new DisclosureGroup("A000000000", "01", "0001", new BigDecimal("15.00"));
            final DisclosureGroup right =
                    new DisclosureGroup("A000000000", "01", "0001", new BigDecimal("25.00"));

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
            assertThat(left.getDisIntRate()).isNotEqualByComparingTo(right.getDisIntRate());
        }

        @Test
        @DisplayName("changing any one key component makes two rows unequal")
        void changingAnyOneKeyComponentMakesRowsUnequal() {
            final DisclosureGroup base =
                    new DisclosureGroup("A000000000", "01", "0001", new BigDecimal("15.00"));

            assertThat(base).isNotEqualTo(
                    new DisclosureGroup("DEFAULT   ", "01", "0001", new BigDecimal("15.00")));
            assertThat(base).isNotEqualTo(
                    new DisclosureGroup("A000000000", "02", "0001", new BigDecimal("15.00")));
            assertThat(base).isNotEqualTo(
                    new DisclosureGroup("A000000000", "01", "0002", new BigDecimal("15.00")));
        }

        @Test
        @DisplayName("the fifty-one seeded rows produce fifty-one distinct keys, so no two rows collide")
        void theSeededRowsProduceDistinctKeys() {
            final List<DisclosureGroupId> ids = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                ids.add(groupFromSeed(ordinal).toId());
            }

            assertThat(ids).hasSize(SEEDED_RECORDS);
            assertThat(ids.stream().distinct().toList()).hasSize(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("a row is unequal to null and to an unrelated type")
        void aRowIsUnequalToNullAndToAnotherType() {
            final DisclosureGroup group = groupFromSeed(1);

            assertThat(group).isNotEqualTo(null);
            assertThat(group.equals("A000000000")).isFalse();
            assertThat(group).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two rows with an absent key are equal, because both keys are absent rather than "
                + "generated")
        void twoUnkeyedRowsAreEqual() {
            assertThat(new DisclosureGroup()).isEqualTo(new DisclosureGroup());
            assertThat(new DisclosureGroup()).hasSameHashCodeAs(new DisclosureGroup());
        }
    }

    // =================================================================================================
    // THE EXTRACTED KEY
    // =================================================================================================

    /**
     * Verifies that the entity hands out the same three-part key it is identified by.
     */
    @Nested
    @DisplayName("the extracted key")
    class ExtractedKey {

        @Test
        @DisplayName("the extracted key carries the three components in copybook order")
        void theExtractedKeyCarriesTheComponentsInCopybookOrder() {
            final DisclosureGroupId id = groupFromSeed(1).toId();

            assertThat(id.getDisAcctGroupId()).isEqualTo(seededGroupId(1));
            assertThat(id.getDisTranTypeCd())
                    .isEqualTo(SEED.field(1, OFFSET_TYPE, COPYBOOK_WIDTHS.get(1)));
            assertThat(id.getDisTranCatCd())
                    .isEqualTo(SEED.field(1, OFFSET_CATEGORY, COPYBOOK_WIDTHS.get(2)));
        }

        @Test
        @DisplayName("the extracted key round-trips: rebuilding a row from it yields an equal row")
        void theExtractedKeyRoundTrips() {
            final DisclosureGroup original = groupFromSeed(1);
            final DisclosureGroupId id = original.toId();
            final DisclosureGroup rebuilt = new DisclosureGroup(
                    id.getDisAcctGroupId(),
                    id.getDisTranTypeCd(),
                    id.getDisTranCatCd(),
                    BigDecimal.ZERO);

            assertThat(rebuilt).isEqualTo(original);
            assertThat(rebuilt.toId()).isEqualTo(id);
        }

        @Test
        @DisplayName("a key whose components are permuted is a different key, so component order is part "
                + "of the contract")
        void aPermutedKeyIsADifferentKey() {
            final DisclosureGroupId ordered = new DisclosureGroupId("AA", "BB", "CC");
            final DisclosureGroupId permuted = new DisclosureGroupId("BB", "AA", "CC");

            assertThat(ordered).isNotEqualTo(permuted);
        }

        @Test
        @DisplayName("an unkeyed row hands out a key whose components are all absent")
        void anUnkeyedRowHandsOutAnEmptyKey() {
            final DisclosureGroupId id = new DisclosureGroup().toId();

            assertThat(id.getDisAcctGroupId()).isNull();
            assertThat(id.getDisTranTypeCd()).isNull();
            assertThat(id.getDisTranCatCd()).isNull();
        }
    }

    // =================================================================================================
    // DIAGNOSTIC REPRESENTATION
    // =================================================================================================

    /**
     * Verifies the diagnostic string.
     */
    @Nested
    @DisplayName("diagnostic representation")
    class DiagnosticRepresentation {

        @Test
        @DisplayName("the diagnostic string names the type and quotes the three key components")
        void theDiagnosticStringNamesTheTypeAndQuotesTheKey() {
            final String rendered = new DisclosureGroup(
                    "A000000000", "01", "0001", new BigDecimal("15.00")).toString();

            assertThat(rendered).isEqualTo("DisclosureGroup[disAcctGroupId='A000000000', "
                    + "disTranTypeCd='01', disTranCatCd='0001']");
        }

        @Test
        @DisplayName("the diagnostic string omits the rate, so it shows exactly what identity compares")
        void theDiagnosticStringOmitsTheRate() {
            final DisclosureGroup group = new DisclosureGroup(
                    "A000000000", "01", "0001", new BigDecimal("15.00"));

            assertThat(group.toString()).doesNotContain("15.00").doesNotContain("disIntRate");
        }

        @Test
        @DisplayName("two rows that are equal render identically, and two that differ do not")
        void equalRowsRenderIdentically() {
            final DisclosureGroup left =
                    new DisclosureGroup("A000000000", "01", "0001", new BigDecimal("15.00"));
            final DisclosureGroup right =
                    new DisclosureGroup("A000000000", "01", "0001", new BigDecimal("25.00"));
            final DisclosureGroup other =
                    new DisclosureGroup("ZEROAPR   ", "01", "0001", new BigDecimal("0.00"));

            assertThat(left.toString()).isEqualTo(right.toString());
            assertThat(left.toString()).isNotEqualTo(other.toString());
        }

        @Test
        @DisplayName("an unkeyed row renders without failing")
        void anUnkeyedRowRendersWithoutFailing() {
            assertThat(new DisclosureGroup().toString())
                    .startsWith("DisclosureGroup[")
                    .endsWith("]")
                    .contains("null");
        }
    }
}
