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
package com.carddemo.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.support.SeededRecordFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Direct unit test for {@link DisclosureGroupRecordMapper}, the mapper between the 50-byte legacy
 * disclosure-group record of {@code app/cpy/CVTRA02Y.cpy} and {@link DisclosureGroup}.
 *
 * <p><strong>The trailing blanks in the group identifier are behaviourally significant.</strong> This
 * is one of only three fields in the whole seed whose trailing spaces must survive. The interest
 * program's file-status {@code '23'} recovery re-probes with the space-padded literal
 * {@code "DEFAULT   "}, so trimming the stored identifier would silently disable the fallback and no
 * account would ever find a rate. This test asserts that the mapper neither trims on the way in nor
 * re-derives on the way out, for all fifty-one records.
 *
 * <p><strong>The rate is zoned decimal and a zero rate is a value, not an absence.</strong>
 * {@code DIS-INT-RATE} is {@code PIC S9(04)V99} - six encoded bytes with the sign overpunched into
 * the final digit - so 15.00 is the image {@code 00150{}. Thirty of the fifty-one records carry a
 * genuine {@code 0.00}. Decoding one of those to {@code null}, to an absent value or to an unscaled
 * zero would each be a defect, so the assertions pin the value, the scale and the re-encoded bytes.
 *
 * <p><strong>Reachability, asserted at the mapper layer.</strong> The rate images present in the
 * fixture are exactly three, and the {@code (01, 0001)} type and category that every seeded balance
 * uses resolves to 15.00 under both {@code A000000000} and {@code DEFAULT   } but to 0.00 under
 * {@code ZEROAPR   }. That is the fact the accrual documentation depends on, so it is pinned here as
 * data rather than left to prose.
 *
 * <p><strong>The filler bound was measured.</strong> A census over all fifty-one records finds 1,428
 * filler bytes, every one the character {@code '0'}, while {@code toRecord} emits spaces. Round-trip
 * assertions are therefore bounded to the mapped prefix {@code [0, 22)}.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("disclosure-group record mapper")
class DisclosureGroupRecordMapperTest {

    /** The shipped fixture measures 2,601 bytes: 51 records at a 51-byte stride. */
    private static final int SEEDED_RECORDS = 51;

    /** Fixture file name, resolved from the test classpath by the shared loader. */
    private static final String FIXTURE = "discgrp.txt";

    /** The character the reference-table fixtures use for filler. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /** The group whose identifier fills its ten bytes without padding. */
    private static final String DIRECT_HIT_GROUP = "A000000000";

    /** The fallback group, space-padded to the full ten-byte key width. */
    private static final String FALLBACK_GROUP = "DEFAULT   ";

    /** The zero-rate group, space-padded to the full ten-byte key width. */
    private static final String ZERO_RATE_GROUP = "ZEROAPR   ";

    /** The single type and category combination every seeded category balance carries. */
    private static final String SEEDED_TYPE = "01";

    /** The single category code every seeded category balance carries. */
    private static final String SEEDED_CATEGORY = "0001";

    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE, DisclosureGroupRecordMapper.RECORD_LENGTH);
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the key, the rate and the filler exactly tile the 50-byte record")
        void theKeyRateAndFillerTileTheRecord() {
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET
                    + DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET
                    + DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET
                    + DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.KEY_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET
                    + DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.FILLER_OFFSET);
            assertThat(DisclosureGroupRecordMapper.FILLER_OFFSET
                    + DisclosureGroupRecordMapper.FILLER_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the copybook widths are reproduced literally: 10 + 2 + 4 key, 6 rate, 28 filler, "
                + "50 total")
        void theCopybookWidthsAreReproduced() {
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH).isEqualTo(10);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH).isEqualTo(4);
            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH).isEqualTo(16);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH).isEqualTo(6);
            assertThat(DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH).isEqualTo(22);
            assertThat(DisclosureGroupRecordMapper.FILLER_LENGTH).isEqualTo(28);
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the rate width matches the codec's own width for the PIC S9(04)V99 clause")
        void theRateWidthMatchesTheCodecWidth() {
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.INTEREST_RATE_WIDTH)
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99);
        }
    }

    @Nested
    @DisplayName("parsing the shipped fixture")
    class ParsingTheShippedFixture {

        @Test
        @DisplayName("the fixture holds exactly fifty-one records, each measuring the declared 50 bytes")
        void theFixtureHoldsFiftyOneRecordsAtTheDeclaredWidth() {
            final SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(loaded.recordWidth()).isEqualTo(DisclosureGroupRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("each parsed key component equals the fixture slice at the mapper's own offset")
        void eachParsedKeyComponentEqualsTheFixtureSlice() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final DisclosureGroup parsed =
                        DisclosureGroupRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getDisAcctGroupId())
                        .as("group id of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET,
                                DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH));
                assertThat(parsed.getDisTranTypeCd())
                        .as("type code of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET,
                                DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH));
                assertThat(parsed.getDisTranCatCd())
                        .as("category code of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET,
                                DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH));
            }
        }

        @Test
        @DisplayName("the group identifier is never trimmed, so the padded fallback key survives - "
                + "trimming it would silently disable the status-23 re-probe")
        void theGroupIdentifierIsNeverTrimmed() {
            final SeededRecordFixture loaded = fixture();
            final Set<String> identifiers = new HashSet<>();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final DisclosureGroup parsed =
                        DisclosureGroupRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getDisAcctGroupId())
                        .as("group id width of record %d", ordinal)
                        .hasSize(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH);
                identifiers.add(parsed.getDisAcctGroupId());
            }

            assertThat(identifiers)
                    .containsExactlyInAnyOrder(DIRECT_HIT_GROUP, FALLBACK_GROUP, ZERO_RATE_GROUP);
            assertThat(FALLBACK_GROUP).endsWith("   ");
            assertThat(ZERO_RATE_GROUP).endsWith("   ");
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points agree with the string entry point")
        void theByteEntryPointsAgreeWithTheStringEntryPoint() {
            final String image = fixture().record(1);
            final byte[] encoded = image.getBytes(StandardCharsets.US_ASCII);
            final byte[] framed = new byte[encoded.length + 7];
            System.arraycopy(encoded, 0, framed, 7, encoded.length);

            final String canonical = DisclosureGroupRecordMapper.toRecord(
                    DisclosureGroupRecordMapper.fromRecord(image));

            assertThat(DisclosureGroupRecordMapper.toRecord(
                    DisclosureGroupRecordMapper.fromRecord(encoded))).isEqualTo(canonical);
            assertThat(DisclosureGroupRecordMapper.toRecord(
                    DisclosureGroupRecordMapper.fromRecord(framed, 7))).isEqualTo(canonical);
        }
    }

    @Nested
    @DisplayName("the zoned-decimal rate")
    class TheZonedDecimalRate {

        @Test
        @DisplayName("every parsed rate carries scale exactly two, never a rescaled or unscaled zero")
        void everyParsedRateCarriesScaleTwo() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final DisclosureGroup parsed =
                        DisclosureGroupRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getDisIntRate())
                        .as("rate of record %d", ordinal)
                        .isNotNull();
                assertThat(parsed.getDisIntRate().scale())
                        .as("rate scale of record %d", ordinal)
                        .isEqualTo(2);
            }
        }

        @Test
        @DisplayName("the fifteen-per-cent image 00150{ decodes to exactly 15.00 with the sign "
                + "overpunched into the final digit")
        void theFifteenPerCentImageDecodesExactly() {
            final SeededRecordFixture loaded = fixture();
            final int ordinal = ordinalOf(loaded, DIRECT_HIT_GROUP, SEEDED_TYPE, SEEDED_CATEGORY);

            assertThat(loaded.field(ordinal, DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                    DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)).isEqualTo("00150{");
            assertThat(DisclosureGroupRecordMapper.fromRecord(loaded.record(ordinal)).getDisIntRate())
                    .isEqualByComparingTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("a zero rate decodes to a genuine 0.00 rather than to null or an absent value")
        void aZeroRateDecodesToAGenuineZero() {
            final SeededRecordFixture loaded = fixture();
            final int ordinal = ordinalOf(loaded, ZERO_RATE_GROUP, SEEDED_TYPE, SEEDED_CATEGORY);

            assertThat(loaded.field(ordinal, DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                    DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)).isEqualTo("00000{");

            final BigDecimal rate =
                    DisclosureGroupRecordMapper.fromRecord(loaded.record(ordinal)).getDisIntRate();

            assertThat(rate).isNotNull();
            assertThat(rate.signum()).isZero();
            assertThat(rate.scale()).isEqualTo(2);
            assertThat(rate.toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("the seeded type and category resolves to 15.00 under both the direct-hit and the "
                + "fallback group but to 0.00 under the zero-rate group")
        void theSeededTypeAndCategoryResolvesPerGroup() {
            final SeededRecordFixture loaded = fixture();

            assertThat(rateOf(loaded, DIRECT_HIT_GROUP))
                    .isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(rateOf(loaded, FALLBACK_GROUP))
                    .isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(rateOf(loaded, ZERO_RATE_GROUP))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("re-encoding reproduces the original six rate bytes for every record, overpunch "
                + "character included")
        void reEncodingReproducesTheOriginalRateBytes() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String original = loaded.record(ordinal);
                final String emitted = DisclosureGroupRecordMapper.toRecord(
                        DisclosureGroupRecordMapper.fromRecord(original));

                assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                        DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH))
                        .as("rate bytes of record %d", ordinal)
                        .isEqualTo(original.substring(
                                DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                                DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH));
            }
        }
    }

    @Nested
    @DisplayName("the key projection")
    class TheKeyProjection {

        @Test
        @DisplayName("the projected key equals one built from the parsed components, and both keep the "
                + "padded group identifier")
        void theProjectedKeyEqualsOneBuiltFromTheParsedComponents() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String image = loaded.record(ordinal);
                final DisclosureGroup parsed = DisclosureGroupRecordMapper.fromRecord(image);

                final DisclosureGroupId projected = DisclosureGroupRecordMapper.keyFromRecord(image);

                assertThat(projected)
                        .as("key of record %d", ordinal)
                        .isEqualTo(new DisclosureGroupId(parsed.getDisAcctGroupId(),
                                parsed.getDisTranTypeCd(), parsed.getDisTranCatCd()));
                assertThat(projected).isEqualTo(parsed.toId());
            }
        }

        @Test
        @DisplayName("a padded group key is deliberately unequal to its trimmed form, because the two "
                + "are distinct values in the database")
        void aPaddedKeyIsUnequalToItsTrimmedForm() {
            final DisclosureGroupId padded =
                    new DisclosureGroupId(FALLBACK_GROUP, SEEDED_TYPE, SEEDED_CATEGORY);
            final DisclosureGroupId trimmed =
                    new DisclosureGroupId(FALLBACK_GROUP.trim(), SEEDED_TYPE, SEEDED_CATEGORY);

            assertThat(padded).isNotEqualTo(trimmed);
        }
    }

    @Nested
    @DisplayName("emitting a record")
    class EmittingARecord {

        @Test
        @DisplayName("every fixture record round-trips byte-identically over the mapped prefix")
        void everyFixtureRecordRoundTripsOverTheMappedPrefix() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String original = loaded.record(ordinal);
                final String emitted = DisclosureGroupRecordMapper.toRecord(
                        DisclosureGroupRecordMapper.fromRecord(original));

                assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                        .as("emitted width of record %d", ordinal)
                        .hasSize(DisclosureGroupRecordMapper.RECORD_LENGTH);
                assertThat(emitted.substring(0, DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH))
                        .as("mapped prefix of record %d", ordinal)
                        .isEqualTo(original.substring(0,
                                DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH));
            }
        }

        @Test
        @DisplayName("the emitted filler is spaces while the fixture's is ASCII zero, so the prefix "
                + "bound is a real contract rather than caution")
        void theEmittedFillerDiffersFromTheFixtureFiller() {
            final String original = fixture().record(1);
            final String emitted = DisclosureGroupRecordMapper.toRecord(
                    DisclosureGroupRecordMapper.fromRecord(original));

            assertThat(emitted.substring(DisclosureGroupRecordMapper.FILLER_OFFSET))
                    .isEqualTo(String.valueOf(DisclosureGroupRecordMapper.FILLER_CHARACTER)
                            .repeat(DisclosureGroupRecordMapper.FILLER_LENGTH));
            assertThat(original.substring(DisclosureGroupRecordMapper.FILLER_OFFSET))
                    .isEqualTo(String.valueOf(FIXTURE_FILLER_CHARACTER)
                            .repeat(DisclosureGroupRecordMapper.FILLER_LENGTH));
            assertThat(emitted).isNotEqualTo(original);
        }

        @Test
        @DisplayName("a negative rate, which the fixture never carries, still encodes and decodes")
        void aNegativeRateStillRoundTrips() {
            final DisclosureGroup subject = new DisclosureGroup(FALLBACK_GROUP, SEEDED_TYPE,
                    SEEDED_CATEGORY, new BigDecimal("-12.34"));

            final String emitted = DisclosureGroupRecordMapper.toRecord(subject);

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(DisclosureGroupRecordMapper.RECORD_LENGTH);
            assertThat(DisclosureGroupRecordMapper.fromRecord(emitted).getDisIntRate())
                    .isEqualByComparingTo(new BigDecimal("-12.34"));
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 50 bytes as the string one")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final DisclosureGroup subject =
                    DisclosureGroupRecordMapper.fromRecord(fixture().record(1));

            assertThat(DisclosureGroupRecordMapper.toRecordBytes(subject))
                    .isEqualTo(DisclosureGroupRecordMapper.toRecord(subject)
                            .getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {

        @Test
        @DisplayName("a short image is refused rather than silently padded")
        void aShortImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(FALLBACK_GROUP))
                    .withMessageContaining(
                            String.valueOf(DisclosureGroupRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("a long image is refused rather than silently truncated")
        void aLongImageIsRefused() {
            final String overlong = "0".repeat(DisclosureGroupRecordMapper.RECORD_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(overlong));
        }

        @Test
        @DisplayName("the key projection applies the same width rule as the full parse")
        void theKeyProjectionAppliesTheSameWidthRule() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.keyFromRecord(FALLBACK_GROUP));
        }

        @Test
        @DisplayName("a null image raises deterministically rather than yielding a partial entity")
        void aNullImageRaisesDeterministically() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord((byte[]) null));
        }
    }

    /**
     * Returns the one-based ordinal of the fixture record carrying the supplied composite key.
     *
     * @param loaded   the loaded fixture
     * @param group    the ten-byte group identifier, padding included
     * @param type     the two-byte transaction type code
     * @param category the four-byte transaction category code
     * @return the one-based ordinal of the matching record
     */
    private static int ordinalOf(final SeededRecordFixture loaded, final String group,
            final String type, final String category) {
        for (int ordinal = 1; ordinal <= loaded.recordCount(); ordinal++) {
            final DisclosureGroup candidate =
                    DisclosureGroupRecordMapper.fromRecord(loaded.record(ordinal));
            if (candidate.getDisAcctGroupId().equals(group)
                    && candidate.getDisTranTypeCd().equals(type)
                    && candidate.getDisTranCatCd().equals(category)) {
                return ordinal;
            }
        }
        throw new AssertionError("the fixture carries no record for group '" + group
                + "' on type " + type + " and category " + category);
    }

    /**
     * Returns the rate the supplied group carries on the single type and category every seeded
     * category balance uses.
     *
     * @param loaded the loaded fixture
     * @param group  the ten-byte group identifier, padding included
     * @return the decoded rate
     */
    private static BigDecimal rateOf(final SeededRecordFixture loaded, final String group) {
        final int ordinal = ordinalOf(loaded, group, SEEDED_TYPE, SEEDED_CATEGORY);
        return DisclosureGroupRecordMapper.fromRecord(loaded.record(ordinal)).getDisIntRate();
    }
}
