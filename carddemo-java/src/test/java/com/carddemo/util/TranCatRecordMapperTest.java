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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.TransactionCategory;
import com.carddemo.support.SeededRecordFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Direct unit test for {@link TranCatRecordMapper}, the mapper between the 60-byte legacy
 * transaction-category record of {@code app/cpy/CVTRA04Y.cpy} and {@link TransactionCategory}.
 *
 * <p><strong>The key-width collision this test exists to guard.</strong> Two different copybooks
 * declare a group named {@code TRAN-CAT-KEY} at two different widths: six bytes here
 * ({@code TRAN-TYPE-CD X(02)} plus {@code TRAN-CAT-CD 9(04)}) and seventeen bytes in
 * {@code app/cpy/CVTRA01Y.cpy}, where the group leads with an eleven-byte account identifier that
 * this layout does not have at all. The six-byte key is therefore <em>not</em> a prefix of the
 * seventeen-byte key, and conflating the two would silently mis-slice both layouts. The provisioning
 * jobs attest both widths independently - {@code KEYS(6 0)} against {@code KEYS(17 0)}. This test
 * asserts the six-byte width explicitly and asserts that it is not seventeen, so a future edit that
 * "unified" the two constants would fail here rather than in production.
 *
 * <p><strong>The filler bound was measured, not assumed.</strong> A census over all eighteen fixture
 * records finds 72 filler bytes, every one the character {@code '0'}, while {@code toRecord} emits
 * spaces. A whole-record 60-byte comparison against the fixture would fail on filler alone, so the
 * round-trip assertion is bounded to the mapped prefix {@code [0, 56)} and that bound is asserted as
 * a deliberate contract.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("transaction-category record mapper")
class TranCatRecordMapperTest {

    /** The shipped fixture measures 1,098 bytes: 18 records at a 61-byte stride. */
    private static final int SEEDED_RECORDS = 18;

    /** Fixture file name, resolved from the test classpath by the shared loader. */
    private static final String FIXTURE = "trancatg.txt";

    /** The character the reference-table fixtures use for filler, measured over all eighteen records. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /** The width of the unrelated seventeen-byte {@code TRAN-CAT-KEY} of {@code CVTRA01Y}. */
    private static final int UNRELATED_CATEGORY_BALANCE_KEY_WIDTH = 17;

    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE, TranCatRecordMapper.RECORD_WIDTH);
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the mapped prefix and the filler exactly tile the 60-byte record")
        void theMappedPrefixAndFillerTileTheRecord() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET
                    + TranCatRecordMapper.TRAN_TYPE_CD_LENGTH)
                    .isEqualTo(TranCatRecordMapper.TRAN_CAT_CD_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_OFFSET
                    + TranCatRecordMapper.TRAN_CAT_CD_LENGTH)
                    .isEqualTo(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET
                    + TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH)
                    .isEqualTo(TranCatRecordMapper.MAPPED_DATA_WIDTH)
                    .isEqualTo(TranCatRecordMapper.FILLER_OFFSET);
            assertThat(TranCatRecordMapper.FILLER_OFFSET + TranCatRecordMapper.FILLER_LENGTH)
                    .isEqualTo(TranCatRecordMapper.RECORD_WIDTH);
        }

        @Test
        @DisplayName("the copybook widths are reproduced literally: 2 + 4 + 50 mapped, 4 filler, 60 total")
        void theCopybookWidthsAreReproduced() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_LENGTH).isEqualTo(4);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH).isEqualTo(50);
            assertThat(TranCatRecordMapper.FILLER_LENGTH).isEqualTo(4);
            assertThat(TranCatRecordMapper.MAPPED_DATA_WIDTH).isEqualTo(56);
            assertThat(TranCatRecordMapper.RECORD_WIDTH).isEqualTo(60);
        }

        @Test
        @DisplayName("the type-and-category key is six bytes and is deliberately NOT the seventeen-byte "
                + "key of the category-balance layout, whose leading account identifier it lacks")
        void theKeyIsSixBytesAndNotSeventeen() {
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH).isEqualTo(6);
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH)
                    .isNotEqualTo(UNRELATED_CATEGORY_BALANCE_KEY_WIDTH);
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH
                    + TranCatRecordMapper.TRAN_CAT_CD_LENGTH)
                    .isEqualTo(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH);
            assertThat(TranCatRecordMapper.KEY_ARTEFACT).contains("6");
        }

        @Test
        @DisplayName("the emitted filler character is a space, unlike the fixture's ASCII zero")
        void theEmittedFillerCharacterIsASpace() {
            assertThat(TranCatRecordMapper.EMITTED_FILLER_CHARACTER).isEqualTo(' ');
            assertThat(TranCatRecordMapper.EMITTED_FILLER_CHARACTER)
                    .isNotEqualTo(FIXTURE_FILLER_CHARACTER);
        }
    }

    @Nested
    @DisplayName("parsing the shipped fixture")
    class ParsingTheShippedFixture {

        @Test
        @DisplayName("the fixture holds exactly eighteen records, each measuring the declared 60 bytes")
        void theFixtureHoldsEighteenRecordsAtTheDeclaredWidth() {
            final SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(loaded.recordWidth()).isEqualTo(TranCatRecordMapper.RECORD_WIDTH);
        }

        @Test
        @DisplayName("each parsed field equals the fixture slice taken at the mapper's own declared offset")
        void eachParsedFieldEqualsTheFixtureSliceAtTheDeclaredOffset() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final TransactionCategory parsed =
                        TranCatRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getTranTypeCd())
                        .as("type code of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, TranCatRecordMapper.TRAN_TYPE_CD_OFFSET,
                                TranCatRecordMapper.TRAN_TYPE_CD_LENGTH));
                assertThat(parsed.getTranCatCd())
                        .as("category code of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, TranCatRecordMapper.TRAN_CAT_CD_OFFSET,
                                TranCatRecordMapper.TRAN_CAT_CD_LENGTH));
                assertThat(parsed.getTranCatTypeDesc())
                        .as("description of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET,
                                TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH));
            }
        }

        @Test
        @DisplayName("both key components keep their significant leading zeros")
        void bothKeyComponentsKeepTheirLeadingZeros() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final TransactionCategory parsed =
                        TranCatRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getTranTypeCd())
                        .as("type code width of record %d", ordinal)
                        .hasSize(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH);
                assertThat(parsed.getTranCatCd())
                        .as("category code width of record %d", ordinal)
                        .hasSize(TranCatRecordMapper.TRAN_CAT_CD_LENGTH);
            }
            assertThat(TranCatRecordMapper.fromRecord(loaded.record(1)).getTranCatCd())
                    .startsWith("000");
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points agree with the string entry point")
        void theByteEntryPointsAgreeWithTheStringEntryPoint() {
            final String image = fixture().record(1);
            final byte[] encoded = image.getBytes(StandardCharsets.US_ASCII);
            final byte[] framed = new byte[encoded.length + 3];
            System.arraycopy(encoded, 0, framed, 3, encoded.length);

            final String canonical =
                    TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(image));

            assertThat(TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(encoded)))
                    .isEqualTo(canonical);
            assertThat(TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(framed, 3)))
                    .isEqualTo(canonical);
        }
    }

    @Nested
    @DisplayName("the six-byte key projection")
    class TheSixByteKeyProjection {

        @Test
        @DisplayName("the key image is exactly the leading six bytes of the record, so it is the "
                + "record's own key rather than a re-derived value")
        void theKeyImageIsTheLeadingSixBytesOfTheRecord() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String image = loaded.record(ordinal);
                final TransactionCategory parsed = TranCatRecordMapper.fromRecord(image);

                final String key = TranCatRecordMapper.typeAndCategoryKeyImage(parsed);

                assertThat(key.getBytes(StandardCharsets.US_ASCII))
                        .as("key width of record %d", ordinal)
                        .hasSize(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH);
                assertThat(key)
                        .as("key of record %d", ordinal)
                        .isEqualTo(image.substring(0,
                                TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH));
            }
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
                final String emitted =
                        TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(original));

                assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                        .as("emitted width of record %d", ordinal)
                        .hasSize(TranCatRecordMapper.RECORD_WIDTH);
                assertThat(emitted.substring(0, TranCatRecordMapper.MAPPED_DATA_WIDTH))
                        .as("mapped prefix of record %d", ordinal)
                        .isEqualTo(original.substring(0, TranCatRecordMapper.MAPPED_DATA_WIDTH));
            }
        }

        @Test
        @DisplayName("the emitted filler is spaces while the fixture's is ASCII zero, so the prefix "
                + "bound is a real contract rather than caution")
        void theEmittedFillerDiffersFromTheFixtureFiller() {
            final String original = fixture().record(1);
            final String emitted =
                    TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(original));

            assertThat(emitted.substring(TranCatRecordMapper.FILLER_OFFSET))
                    .isEqualTo(String.valueOf(TranCatRecordMapper.EMITTED_FILLER_CHARACTER)
                            .repeat(TranCatRecordMapper.FILLER_LENGTH));
            assertThat(original.substring(TranCatRecordMapper.FILLER_OFFSET))
                    .isEqualTo(String.valueOf(FIXTURE_FILLER_CHARACTER)
                            .repeat(TranCatRecordMapper.FILLER_LENGTH));
            assertThat(emitted).isNotEqualTo(original);
        }

        @Test
        @DisplayName("a short description is space-padded to the full fifty-byte field on output")
        void aShortDescriptionIsPaddedToTheFullFieldWidth() {
            final TransactionCategory subject = new TransactionCategory("01", "0002", "CASH ADVANCE");

            final String emitted = TranCatRecordMapper.toRecord(subject);

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TranCatRecordMapper.RECORD_WIDTH);
            assertThat(emitted.substring(0, TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH))
                    .isEqualTo("010002");
            assertThat(emitted.substring(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET,
                    TranCatRecordMapper.MAPPED_DATA_WIDTH))
                    .isEqualTo("CASH ADVANCE" + " ".repeat(
                            TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH - "CASH ADVANCE".length()));
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 60 bytes as the string one")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final TransactionCategory subject = TranCatRecordMapper.fromRecord(fixture().record(1));

            assertThat(TranCatRecordMapper.toRecordBytes(subject))
                    .isEqualTo(TranCatRecordMapper.toRecord(subject)
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
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord("010001"))
                    .withMessageContaining(String.valueOf(TranCatRecordMapper.RECORD_WIDTH));
        }

        @Test
        @DisplayName("a long image is refused rather than silently truncated")
        void aLongImageIsRefused() {
            final String overlong = "0".repeat(TranCatRecordMapper.RECORD_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(overlong));
        }

        @Test
        @DisplayName("a null image raises deterministically rather than yielding a partial entity")
        void aNullImageRaisesDeterministically() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((byte[]) null));
        }
    }
}
