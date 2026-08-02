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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.id.DisclosureGroupId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DisclosureGroupRecordMapper}, which maps the fifty-byte
 * {@code DIS-GROUP-RECORD} declared by {@code app/cpy/CVTRA02Y.cpy} onto {@link DisclosureGroup}
 * and back.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>This is the interest-rate lookup, so its decode is on the interest path.</strong>
 * {@code app/cbl/CBACT04C.cbl} reads this record to obtain {@code DIS-INT-RATE}, a
 * {@code PIC S9(04)V99} zoned decimal held in six bytes, and then computes
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at line 464 without a {@code ROUNDED} clause. A rate
 * decoded at the wrong scale, or re-encoded with the wrong rounding mode, would move the truncation
 * point of that expression and change every interest figure the batch tier produces. The scale and
 * the rounding mode are therefore asserted directly rather than assumed.
 *
 * <p><strong>The fixture proves two CBACT04C branches are reachable.</strong> The sample file carries
 * three complete groups of seventeen rows each: an account-specific group, a {@code DEFAULT} group,
 * and a {@code ZEROAPR} group whose every rate is zero. The presence of the first two makes the
 * not-found fallback to the default group exercisable from seed data alone, and the third makes the
 * zero-rate skip branch exercisable. Both facts are asserted here so that a later change to the
 * fixture cannot silently remove the coverage those branches depend on.
 *
 * <p><strong>The key has its own reader.</strong> The first sixteen bytes are the composite key, and
 * the mapper publishes a key-only reader alongside the whole-record one. The tests assert that the
 * key reader agrees with the record reader on every fixture row rather than merely that it returns
 * three non-null strings, because a drift between the two offsets would break keyed lookup while
 * leaving whole-record decoding intact.
 *
 * <p><strong>The emitted filler deliberately differs from the fixture's.</strong> All fifty-one
 * sample records carry twenty-eight ASCII zeros in the trailing filler; the mapper emits twenty-eight
 * spaces. A decode-then-encode cycle is therefore byte-identical across the twenty-two mapped bytes
 * and normalising across the final twenty-eight, and that is asserted explicitly rather than smoothed
 * over with a comparison that trims.
 */
@DisplayName("DisclosureGroupRecordMapper - the fifty-byte CVTRA02Y record and its sixteen-byte key")
class DisclosureGroupRecordMapperRuleComplianceTest {

    /** The fixture whose bytes are the decode authority for this layout. */
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/input/discgrp.txt");

    /** The record count the fixture carries, as measured from its byte length. */
    private static final int FIXTURE_RECORD_COUNT = 51;

    /** The stride between fixture records: the record width plus one line terminator. */
    private static final int FIXTURE_STRIDE = DisclosureGroupRecordMapper.RECORD_LENGTH + 1;

    /** The number of rows each of the fixture's three groups carries. */
    private static final int ROWS_PER_FIXTURE_GROUP = 17;

    /** The account-specific group key the fixture's first block carries, at its declared width. */
    private static final String ACCOUNT_GROUP_KEY = "A000000000";

    /** The fallback group key {@code CBACT04C} reaches for on a not-found lookup. */
    private static final String DEFAULT_GROUP_KEY = "DEFAULT   ";

    /** The zero-rate group key that makes the interest skip branch reachable. */
    private static final String ZERO_RATE_GROUP_KEY = "ZEROAPR   ";

    /** The filler character the sample file carries, which the mapper does not reproduce. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /**
     * Reads one record image from the fixture by ordinal, excluding the line terminator.
     *
     * @param ordinal the zero-based record position
     * @return the fifty-byte record image
     * @throws IOException when the fixture cannot be read
     */
    private static String fixtureRecord(final int ordinal) throws IOException {
        final byte[] file = Files.readAllBytes(FIXTURE);
        return new String(file, ordinal * FIXTURE_STRIDE,
                DisclosureGroupRecordMapper.RECORD_LENGTH, StandardCharsets.US_ASCII);
    }

    /**
     * Reads every record image the fixture carries.
     *
     * @return the fifty-one record images in file order
     * @throws IOException when the fixture cannot be read
     */
    private static List<String> allFixtureRecords() throws IOException {
        final List<String> records = new ArrayList<>();
        for (int ordinal = 0; ordinal < FIXTURE_RECORD_COUNT; ordinal++) {
            records.add(fixtureRecord(ordinal));
        }
        return records;
    }

    /**
     * Assembles a well-formed record image with the mapper's own space filler.
     *
     * @param groupId the account group identifier, at its declared width
     * @param typeCode the two-character transaction type code
     * @param categoryCode the four-character transaction category code
     * @param rateImage the six-byte zoned decimal rate image
     * @return a fifty-byte record image
     */
    private static String recordImage(final String groupId, final String typeCode,
            final String categoryCode, final String rateImage) {
        return groupId + typeCode + categoryCode + rateImage
                + " ".repeat(DisclosureGroupRecordMapper.FILLER_LENGTH);
    }

    /**
     * Builds a disclosure group whose four attributes are all present.
     *
     * @param groupId the account group identifier
     * @param typeCode the transaction type code
     * @param categoryCode the transaction category code
     * @param rate the interest rate
     * @return a fully populated disclosure group
     */
    private static DisclosureGroup aGroup(final String groupId, final String typeCode,
            final String categoryCode, final String rate) {
        return new DisclosureGroup(groupId, typeCode, categoryCode, new BigDecimal(rate));
    }

    // =================================================================================
    // The published layout
    // =================================================================================

    @Nested
    @DisplayName("The published layout")
    class ThePublishedLayout {

        @Test
        @DisplayName("the record is the fifty bytes CVTRA02Y declares")
        void theRecordIsFiftyBytes() {
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the three key fields occupy the leading sixteen bytes without a gap")
        void theKeyFieldsAreContiguousFromTheStart() {
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET).isZero();
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET
                            + DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET
                            + DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH);
        }

        @Test
        @DisplayName("the key length is the sum of its three component widths")
        void theKeyLengthIsTheSumOfItsComponents() {
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH).isEqualTo(10);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH).isEqualTo(4);
            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH).isEqualTo(16);
            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH
                            + DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH
                            + DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH);
        }

        @Test
        @DisplayName("the rate follows the key and is the six bytes PIC S9(04)V99 needs")
        void theRateFollowsTheKey() {
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET)
                    .isEqualTo(DisclosureGroupRecordMapper.KEY_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH).isEqualTo(6);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99);
        }

        @Test
        @DisplayName("the mapped prefix ends where the rate ends")
        void theMappedPrefixEndsWhereTheRateEnds() {
            assertThat(DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET
                            + DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .isEqualTo(22);
        }

        @Test
        @DisplayName("filler occupies the balance of the record and is emitted as spaces")
        void fillerOccupiesTheBalanceOfTheRecord() {
            assertThat(DisclosureGroupRecordMapper.FILLER_OFFSET)
                    .isEqualTo(DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH);
            assertThat(DisclosureGroupRecordMapper.FILLER_LENGTH)
                    .isEqualTo(DisclosureGroupRecordMapper.RECORD_LENGTH
                            - DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(28);
            assertThat(DisclosureGroupRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
        }

        @Test
        @DisplayName("the artefact name cites the copybook the layout comes from")
        void theArtefactNameCitesTheCopybook() {
            assertThat(DisclosureGroupRecordMapper.ARTEFACT)
                    .isEqualTo("DIS-GROUP-RECORD (CVTRA02Y)");
        }

        @Test
        @DisplayName("the mapper is a final class whose sole constructor is private")
        void theMapperIsAFinalClassWithAPrivateConstructor() throws NoSuchMethodException,
                InvocationTargetException, InstantiationException, IllegalAccessException {
            assertThat(Modifier.isFinal(DisclosureGroupRecordMapper.class.getModifiers())).isTrue();

            final Constructor<?>[] constructors =
                    DisclosureGroupRecordMapper.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();

            final Constructor<DisclosureGroupRecordMapper> constructor =
                    DisclosureGroupRecordMapper.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }
    }

    // =================================================================================
    // Decoding the real fixture
    // =================================================================================

    @Nested
    @DisplayName("Decoding the real fixture")
    class DecodingTheRealFixture {

        @Test
        @DisplayName("the fixture carries fifty-one records of exactly the declared width")
        void theFixtureCarriesFiftyOneRecordsOfTheDeclaredWidth() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            assertThat(file).hasSize(FIXTURE_RECORD_COUNT * FIXTURE_STRIDE);
            assertThat(allFixtureRecords())
                    .hasSize(FIXTURE_RECORD_COUNT)
                    .allSatisfy(image -> assertThat(image)
                            .hasSize(DisclosureGroupRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("the first record decodes to the documented account-group row")
        void theFirstRecordDecodesToTheDocumentedRow() throws IOException {
            final DisclosureGroup group =
                    DisclosureGroupRecordMapper.fromRecord(fixtureRecord(0));

            assertThat(group.getDisAcctGroupId()).isEqualTo(ACCOUNT_GROUP_KEY);
            assertThat(group.getDisTranTypeCd()).isEqualTo("01");
            assertThat(group.getDisTranCatCd()).isEqualTo("0001");
            assertThat(group.getDisIntRate()).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("the rate is decoded at monetary scale two, as PIC S9(04)V99 requires")
        void theRateIsDecodedAtMonetaryScaleTwo() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(DisclosureGroupRecordMapper.fromRecord(image).getDisIntRate().scale())
                        .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
            }
        }

        @Test
        @DisplayName("every one of the fifty-one records decodes without refusal")
        void everyRecordDecodesWithoutRefusal() throws IOException {
            final List<DisclosureGroup> decoded = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                decoded.add(DisclosureGroupRecordMapper.fromRecord(image));
            }

            assertThat(decoded)
                    .hasSize(FIXTURE_RECORD_COUNT)
                    .allSatisfy(group -> {
                        assertThat(group.getDisAcctGroupId())
                                .hasSize(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH);
                        assertThat(group.getDisTranTypeCd())
                                .hasSize(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH);
                        assertThat(group.getDisTranCatCd())
                                .hasSize(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH);
                        assertThat(group.getDisIntRate()).isNotNull();
                    });
        }

        @Test
        @DisplayName("the fixture carries exactly three groups of seventeen rows each")
        void theFixtureCarriesThreeGroupsOfSeventeenRows() throws IOException {
            final Map<String, Integer> rowsByGroup = new LinkedHashMap<>();
            for (final String image : allFixtureRecords()) {
                final String key =
                        DisclosureGroupRecordMapper.fromRecord(image).getDisAcctGroupId();
                rowsByGroup.merge(key, 1, Integer::sum);
            }

            assertThat(rowsByGroup)
                    .hasSize(3)
                    .containsOnlyKeys(ACCOUNT_GROUP_KEY, DEFAULT_GROUP_KEY, ZERO_RATE_GROUP_KEY)
                    .allSatisfy((key, rows) -> assertThat(rows)
                            .isEqualTo(ROWS_PER_FIXTURE_GROUP));
        }

        @Test
        @DisplayName("the DEFAULT group is present, so the CBACT04C fallback branch is reachable")
        void theDefaultGroupIsPresent() throws IOException {
            final List<DisclosureGroup> defaultRows = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                final DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(image);
                if (DEFAULT_GROUP_KEY.equals(group.getDisAcctGroupId())) {
                    defaultRows.add(group);
                }
            }

            assertThat(defaultRows).hasSize(ROWS_PER_FIXTURE_GROUP);
            assertThat(DEFAULT_GROUP_KEY)
                    .startsWith("DEFAULT")
                    .hasSize(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH);
        }

        @Test
        @DisplayName("the ZEROAPR group carries only zero rates, so the skip branch is reachable")
        void theZeroRateGroupCarriesOnlyZeroRates() throws IOException {
            final List<DisclosureGroup> zeroRateRows = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                final DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(image);
                if (ZERO_RATE_GROUP_KEY.equals(group.getDisAcctGroupId())) {
                    zeroRateRows.add(group);
                }
            }

            assertThat(zeroRateRows).hasSize(ROWS_PER_FIXTURE_GROUP);
            assertThat(zeroRateRows).allSatisfy(group ->
                    assertThat(group.getDisIntRate().signum()).isZero());
        }

        @Test
        @DisplayName("the account group also carries non-zero rates, so both arms differ")
        void theAccountGroupCarriesNonZeroRates() throws IOException {
            final List<BigDecimal> rates = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                final DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(image);
                if (ACCOUNT_GROUP_KEY.equals(group.getDisAcctGroupId())) {
                    rates.add(group.getDisIntRate());
                }
            }

            assertThat(rates).hasSize(ROWS_PER_FIXTURE_GROUP);
            assertThat(rates).anySatisfy(rate -> assertThat(rate.signum()).isEqualTo(1));
            assertThat(rates).anySatisfy(rate -> assertThat(rate.signum()).isZero());
        }

        @Test
        @DisplayName("decoding from a byte array agrees with decoding from a string")
        void decodingFromBytesAgreesWithDecodingFromAString() throws IOException {
            for (final String image : allFixtureRecords()) {
                final DisclosureGroup fromString =
                        DisclosureGroupRecordMapper.fromRecord(image);
                final DisclosureGroup fromBytes = DisclosureGroupRecordMapper
                        .fromRecord(image.getBytes(StandardCharsets.US_ASCII));

                assertThat(fromBytes.getDisAcctGroupId()).isEqualTo(fromString.getDisAcctGroupId());
                assertThat(fromBytes.getDisTranTypeCd()).isEqualTo(fromString.getDisTranTypeCd());
                assertThat(fromBytes.getDisTranCatCd()).isEqualTo(fromString.getDisTranCatCd());
                assertThat(fromBytes.getDisIntRate()).isEqualTo(fromString.getDisIntRate());
            }
        }

        @Test
        @DisplayName("decoding a slice of the whole file agrees with decoding the isolated record")
        void decodingASliceAgreesWithTheIsolatedRecord() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            for (int ordinal = 0; ordinal < FIXTURE_RECORD_COUNT; ordinal++) {
                final DisclosureGroup sliced =
                        DisclosureGroupRecordMapper.fromRecord(file, ordinal * FIXTURE_STRIDE);
                final DisclosureGroup isolated =
                        DisclosureGroupRecordMapper.fromRecord(fixtureRecord(ordinal));

                assertThat(sliced.getDisAcctGroupId()).isEqualTo(isolated.getDisAcctGroupId());
                assertThat(sliced.getDisTranTypeCd()).isEqualTo(isolated.getDisTranTypeCd());
                assertThat(sliced.getDisTranCatCd()).isEqualTo(isolated.getDisTranCatCd());
                assertThat(sliced.getDisIntRate()).isEqualTo(isolated.getDisIntRate());
            }
        }

        @Test
        @DisplayName("the fixture's own filler is zeros, which the mapper does not reproduce")
        void theFixtureFillerIsZeros() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(image.substring(DisclosureGroupRecordMapper.FILLER_OFFSET))
                        .isEqualTo(String.valueOf(FIXTURE_FILLER_CHARACTER)
                                .repeat(DisclosureGroupRecordMapper.FILLER_LENGTH));
            }
            assertThat(FIXTURE_FILLER_CHARACTER)
                    .isNotEqualTo(DisclosureGroupRecordMapper.FILLER_CHARACTER);
        }
    }

    // =================================================================================
    // The sixteen-byte composite key
    // =================================================================================

    @Nested
    @DisplayName("The sixteen-byte composite key")
    class TheSixteenByteCompositeKey {

        @Test
        @DisplayName("the key reader recovers the three components of the first record")
        void theKeyReaderRecoversTheThreeComponents() throws IOException {
            final DisclosureGroupId key =
                    DisclosureGroupRecordMapper.keyFromRecord(fixtureRecord(0));

            assertThat(key.getDisAcctGroupId()).isEqualTo(ACCOUNT_GROUP_KEY);
            assertThat(key.getDisTranTypeCd()).isEqualTo("01");
            assertThat(key.getDisTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("the concatenated key is exactly the leading sixteen bytes of the record")
        void theConcatenatedKeyIsTheLeadingSixteenBytes() throws IOException {
            for (final String image : allFixtureRecords()) {
                final DisclosureGroupId key = DisclosureGroupRecordMapper.keyFromRecord(image);

                assertThat(key.getDisAcctGroupId() + key.getDisTranTypeCd()
                        + key.getDisTranCatCd())
                        .isEqualTo(image.substring(0, DisclosureGroupRecordMapper.KEY_LENGTH));
            }
        }

        @Test
        @DisplayName("the key reader agrees with the record reader on every fixture row")
        void theKeyReaderAgreesWithTheRecordReader() throws IOException {
            for (final String image : allFixtureRecords()) {
                final DisclosureGroupId key = DisclosureGroupRecordMapper.keyFromRecord(image);
                final DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(image);

                assertThat(key.getDisAcctGroupId()).isEqualTo(group.getDisAcctGroupId());
                assertThat(key.getDisTranTypeCd()).isEqualTo(group.getDisTranTypeCd());
                assertThat(key.getDisTranCatCd()).isEqualTo(group.getDisTranCatCd());
            }
        }

        @Test
        @DisplayName("keys read from the same image are equal and share a hash code")
        void keysReadFromTheSameImageAreEqual() throws IOException {
            final String image = fixtureRecord(0);

            final DisclosureGroupId first = DisclosureGroupRecordMapper.keyFromRecord(image);
            final DisclosureGroupId second = DisclosureGroupRecordMapper.keyFromRecord(image);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isEqualTo(first);
            assertThat(first).isNotEqualTo(null);
            assertThat(first).isNotEqualTo("not a key");
        }

        @Test
        @DisplayName("keys read from different rows of the same group differ")
        void keysFromDifferentRowsDiffer() throws IOException {
            final DisclosureGroupId first = DisclosureGroupRecordMapper.keyFromRecord(
                    fixtureRecord(0));
            final DisclosureGroupId second = DisclosureGroupRecordMapper.keyFromRecord(
                    fixtureRecord(1));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("all fifty-one fixture keys are distinct")
        void allFixtureKeysAreDistinct() throws IOException {
            final List<DisclosureGroupId> keys = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                keys.add(DisclosureGroupRecordMapper.keyFromRecord(image));
            }

            assertThat(keys).hasSize(FIXTURE_RECORD_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the key renders its three components without inventing a fourth")
        void theKeyRendersItsThreeComponents() throws IOException {
            final DisclosureGroupId key =
                    DisclosureGroupRecordMapper.keyFromRecord(fixtureRecord(0));

            assertThat(key.toString())
                    .isEqualTo("DisclosureGroupId[disAcctGroupId='" + ACCOUNT_GROUP_KEY
                            + "', disTranTypeCd='01', disTranCatCd='0001']");
        }
    }

    // =================================================================================
    // Encoding and the round trip
    // =================================================================================

    @Nested
    @DisplayName("Encoding and the round trip")
    class EncodingAndTheRoundTrip {

        @Test
        @DisplayName("encoding reproduces the mapped prefix of every fixture record byte for byte")
        void encodingReproducesTheMappedPrefixOfEveryFixtureRecord() throws IOException {
            for (final String image : allFixtureRecords()) {
                final String emitted = DisclosureGroupRecordMapper
                        .toRecord(DisclosureGroupRecordMapper.fromRecord(image));

                assertThat(emitted.substring(0, DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH))
                        .isEqualTo(image.substring(0,
                                DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH));
            }
        }

        @Test
        @DisplayName("the emitted record is the declared width even though the filler is rewritten")
        void theEmittedRecordIsTheDeclaredWidth() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(DisclosureGroupRecordMapper
                        .toRecord(DisclosureGroupRecordMapper.fromRecord(image)))
                        .hasSize(DisclosureGroupRecordMapper.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the emitted filler is twenty-eight spaces, normalising the fixture's zeros")
        void theEmittedFillerIsSpaces() throws IOException {
            final String image = fixtureRecord(0);

            final String emitted = DisclosureGroupRecordMapper
                    .toRecord(DisclosureGroupRecordMapper.fromRecord(image));

            assertThat(emitted.substring(DisclosureGroupRecordMapper.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(DisclosureGroupRecordMapper.FILLER_LENGTH));
            assertThat(emitted).isNotEqualTo(image);
        }

        @Test
        @DisplayName("the byte emitter agrees with the string emitter")
        void theByteEmitterAgreesWithTheStringEmitter() throws IOException {
            for (final String image : allFixtureRecords()) {
                final DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(image);

                assertThat(DisclosureGroupRecordMapper.toRecordBytes(group))
                        .isEqualTo(DisclosureGroupRecordMapper.toRecord(group)
                                .getBytes(StandardCharsets.US_ASCII));
            }
        }

        @Test
        @DisplayName("a re-decode of an emitted image recovers the same four attributes")
        void aReDecodeOfAnEmittedImageRecoversTheSameAttributes() throws IOException {
            for (final String image : allFixtureRecords()) {
                final DisclosureGroup original = DisclosureGroupRecordMapper.fromRecord(image);
                final DisclosureGroup reDecoded = DisclosureGroupRecordMapper
                        .fromRecord(DisclosureGroupRecordMapper.toRecord(original));

                assertThat(reDecoded.getDisAcctGroupId()).isEqualTo(original.getDisAcctGroupId());
                assertThat(reDecoded.getDisTranTypeCd()).isEqualTo(original.getDisTranTypeCd());
                assertThat(reDecoded.getDisTranCatCd()).isEqualTo(original.getDisTranCatCd());
                assertThat(reDecoded.getDisIntRate()).isEqualTo(original.getDisIntRate());
            }
        }

        @Test
        @DisplayName("the group identifier and type code are left-justified and space-padded")
        void theGroupIdentifierAndTypeCodeAreSpacePadded() {
            final String emitted =
                    DisclosureGroupRecordMapper.toRecord(aGroup("G", "1", "0001", "1.00"));

            assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET,
                    DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET)).isEqualTo("G         ");
            assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET,
                    DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET)).isEqualTo("1 ");
        }

        @Test
        @DisplayName("the category code is right-justified and zero-padded, because it is numeric")
        void theCategoryCodeIsZeroPadded() {
            final String emitted =
                    DisclosureGroupRecordMapper.toRecord(aGroup("G", "01", "7", "1.00"));

            assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET,
                    DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET)).isEqualTo("0007");
        }

        @ParameterizedTest
        @CsvSource({
            "15.00,  00150{",
            "25.00,  00250{",
            "0.00,   00000{",
            "0.01,   00000A",
            "9999.99, 99999I",
            "-15.00,  00150}",
            "-15.01,  00150J",
            "-0.09,   00000R",
        })
        @DisplayName("the rate carries the overpunched sign its value calls for")
        void theRateCarriesTheOverpunchedSign(final String rate, final String expectedImage) {
            final String emitted =
                    DisclosureGroupRecordMapper.toRecord(aGroup("G", "01", "0001", rate));

            assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                    DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)).isEqualTo(expectedImage);
        }

        @ParameterizedTest
        @CsvSource({
            "15.006, 00150{",
            "15.009, 00150{",
            "15.005, 00150{",
            "-15.006, 00150}",
            "-15.009, 00150}",
            "0.009,  00000{",
        })
        @DisplayName("a rate finer than a hundredth truncates toward zero, never rounds half-even")
        void aFinerRateTruncatesTowardZero(final String rate, final String expectedImage) {
            final String emitted =
                    DisclosureGroupRecordMapper.toRecord(aGroup("G", "01", "0001", rate));

            assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                    DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)).isEqualTo(expectedImage);

            final String halfEvenImage = ZonedDecimalCodec.encodeMonetary(
                    new BigDecimal(rate).setScale(ZonedDecimalCodec.MONETARY_SCALE,
                            RoundingMode.HALF_EVEN),
                    DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH, "comparison");
            if (!halfEvenImage.equals(expectedImage)) {
                assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                        DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH))
                        .isNotEqualTo(halfEvenImage);
            }
        }

        @Test
        @DisplayName("a negative rate finer than a hundredth truncates to an unsigned zero image")
        void aNegativeSubHundredthRateTruncatesToAnUnsignedZero() {
            final String emitted =
                    DisclosureGroupRecordMapper.toRecord(aGroup("G", "01", "0001", "-0.001"));

            assertThat(emitted.substring(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET,
                    DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)).isEqualTo("00000{");
        }

        @Test
        @DisplayName("a negative-zero rate image decodes to an unsigned zero at monetary scale")
        void aNegativeZeroRateImageDecodesToAnUnsignedZero() {
            final DisclosureGroup group = DisclosureGroupRecordMapper
                    .fromRecord(recordImage(ACCOUNT_GROUP_KEY, "01", "0001", "00000}"));

            assertThat(group.getDisIntRate().signum()).isZero();
            assertThat(group.getDisIntRate().scale())
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
        }

        @Test
        @DisplayName("a synthesised record survives a decode-then-encode cycle byte for byte")
        void aSynthesisedRecordSurvivesTheCycleByteForByte() {
            final String image = recordImage(DEFAULT_GROUP_KEY, "07", "0004", "00250{");

            assertThat(DisclosureGroupRecordMapper
                    .toRecord(DisclosureGroupRecordMapper.fromRecord(image)))
                    .isEqualTo(image);
        }
    }

    // =================================================================================
    // Refusals and their diagnostics
    // =================================================================================

    @Nested
    @DisplayName("Refusals and their diagnostics")
    class RefusalsAndTheirDiagnostics {

        @Test
        @DisplayName("a null record image is refused by every reader")
        void aNullRecordImageIsRefusedByEveryReader() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord((String) null))
                    .withMessage("recordImage must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord((byte[]) null))
                    .withMessage("recordImage must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.keyFromRecord(null))
                    .withMessage("recordImage must not be null");
        }

        @Test
        @DisplayName("a null buffer is refused by the slicing reader under its own parameter name")
        void aNullBufferIsRefusedByTheSlicingReader() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(null, 0))
                    .withMessage("buffer must not be null");
        }

        @Test
        @DisplayName("a null group is refused by both emitters")
        void aNullGroupIsRefusedByBothEmitters() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(null))
                    .withMessage("group must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecordBytes(null))
                    .withMessage("group must not be null");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "DIS-ACCT-GROUP-ID", "DIS-TRAN-TYPE-CD", "DIS-TRAN-CAT-CD", "DIS-INT-RATE"})
        @DisplayName("each absent mapped field is refused by its own copybook field name")
        void eachAbsentMappedFieldIsRefusedByName(final String fieldName) {
            final DisclosureGroup group = new DisclosureGroup(
                    "DIS-ACCT-GROUP-ID".equals(fieldName) ? null : ACCOUNT_GROUP_KEY,
                    "DIS-TRAN-TYPE-CD".equals(fieldName) ? null : "01",
                    "DIS-TRAN-CAT-CD".equals(fieldName) ? null : "0001",
                    "DIS-INT-RATE".equals(fieldName) ? null : new BigDecimal("15.00"));

            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(group))
                    .withMessage(DisclosureGroupRecordMapper.ARTEFACT
                            + " cannot be encoded because " + fieldName
                            + " is null; every mapped field of a fixed-width record must be"
                            + " present");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 49, 51, 100})
        @DisplayName("an image of the wrong width is refused rather than padded or truncated")
        void anImageOfTheWrongWidthIsRefused(final int width) {
            final String image = "0".repeat(width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(image))
                    .withMessageContaining(DisclosureGroupRecordMapper.ARTEFACT)
                    .withMessageContaining("must be exactly "
                            + DisclosureGroupRecordMapper.RECORD_LENGTH + " encoded bytes")
                    .withMessageContaining("is " + width + " encoded bytes")
                    .withMessageContaining("never padded or truncated on input");
        }

        @Test
        @DisplayName("an image overlong by one byte is diagnosed as an unstripped line terminator")
        void anImageOverlongByOneByteHintsAtTheLineTerminator() throws IOException {
            final String withTerminator = fixtureRecord(0) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("0x0A line terminator")
                    .withMessageContaining("record separator and never record content");
        }

        @Test
        @DisplayName("the width diagnostic reports lengths only and never echoes record content")
        void theWidthDiagnosticNeverEchoesRecordContent() {
            final String hostile =
                    "SECRET\r\nINJECTED\u0000PAYLOAD that must never reach a diagnostic";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(hostile))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain("SECRET");
                        assertThat(refusal.getMessage()).doesNotContain("INJECTED");
                        assertThat(refusal.getMessage()).doesNotContain("PAYLOAD");
                        assertThat(refusal.getMessage()).doesNotContain("\r");
                        assertThat(refusal.getMessage()).doesNotContain("\n");
                        assertThat(refusal.getMessage()).doesNotContain("\u0000");
                    });
        }

        @Test
        @DisplayName("a non-digit inside the rate names the offending position, not the record")
        void aNonDigitInsideTheRateNamesThePosition() {
            final String image = recordImage(ACCOUNT_GROUP_KEY, "01", "0001", "00 50{");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(image))
                    .withMessageContaining("DIS-INT-RATE")
                    .withMessageContaining("zero-based offset 2")
                    .withMessageContaining("0x20")
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain(ACCOUNT_GROUP_KEY));
        }

        @Test
        @DisplayName("an unrepresentable final byte in the rate is refused as a sign character")
        void anUnrepresentableFinalByteInTheRateIsRefused() {
            final String image = recordImage(ACCOUNT_GROUP_KEY, "01", "0001", "00150*");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(image))
                    .withMessageContaining("DIS-INT-RATE")
                    .withMessageContaining("overpunched sign character")
                    .withMessageContaining("zero-based offset "
                            + (DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH - 1));
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than truncated to fit")
        void aValueWiderThanItsFieldIsRefused() {
            final DisclosureGroup overlong = aGroup("ELEVENCHARS", "01", "0001", "15.00");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(overlong))
                    .withMessageContaining("DIS-ACCT-GROUP-ID")
                    .withMessageContaining("field width is "
                            + DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH
                            + " encoded bytes")
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a rate needing more than four integral digits is refused")
        void aRateNeedingMoreIntegralDigitsIsRefused() {
            final DisclosureGroup tooLarge = aGroup(ACCOUNT_GROUP_KEY, "01", "0001", "10000.00");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(tooLarge))
                    .withMessageContaining("DIS-INT-RATE")
                    .withMessageContaining("needs 7 digit(s) at scale "
                            + ZonedDecimalCodec.MONETARY_SCALE)
                    .withMessageContaining("field holds only "
                            + DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH);
        }

        @Test
        @DisplayName("a character US-ASCII cannot represent is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            final DisclosureGroup accented = aGroup("G\u00e9", "01", "0001", "15.00");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(accented))
                    .withMessageContaining("DIS-ACCT-GROUP-ID")
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("index 1")
                    .withMessageContaining("must never be transcoded silently");
        }

        @Test
        @DisplayName("a slice that runs past the buffer end is refused with indices, not content")
        void aSliceRunningPastTheBufferEndIsRefused() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);
            final int tooFar = file.length - 10;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(file, tooFar))
                    .withMessageContaining("does not fit inside the supplied buffer")
                    .withMessageContaining("from=" + tooFar)
                    .withMessageContaining("recordWidth="
                            + DisclosureGroupRecordMapper.RECORD_LENGTH)
                    .withMessageContaining("buffer length=" + file.length)
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain(ACCOUNT_GROUP_KEY));
        }

        @Test
        @DisplayName("a negative slice start is refused before any byte is read")
        void aNegativeSliceStartIsRefused() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(file, -1))
                    .withMessageContaining("start index must not be negative")
                    .withMessageContaining("from=-1");
        }
    }
}
