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

import com.carddemo.domain.TransactionType;
import com.carddemo.support.SeededRecordFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Direct unit test for {@link TranTypeRecordMapper}, the mapper between the 60-byte legacy
 * transaction-type record of {@code app/cpy/CVTRA03Y.cpy} and {@link TransactionType}.
 *
 * <p><strong>Why this test exists.</strong> The mapper's declared offsets were previously attested
 * only by its own Javadoc and by an out-of-repository probe. Prose cannot fail a build. Every offset,
 * width and filler rule asserted here is driven from the shipped fixture
 * {@code app/data/ASCII/trantype.txt}, so the authority layout - not a hand-typed string that could
 * be mistyped to agree with a wrong constant - decides whether the mapper is right.
 *
 * <p><strong>The filler bound is load-bearing and was measured, not assumed.</strong> COBOL
 * {@code FILLER X(08)} carries no {@code VALUE} clause, so no byte value is canonical, and this
 * estate's fixtures genuinely disagree with one another: the master fixtures pad filler with spaces
 * while the reference-table fixtures - this one among them - pad with ASCII zero. A census over all
 * seven records confirms 56 filler bytes, every one the character {@code '0'}. {@code toRecord}
 * emits spaces. A whole-record 60-byte comparison against the fixture would therefore fail on the
 * filler alone, which is precisely why the round-trip assertion below is bounded to the mapped
 * prefix {@code [0, 52)}. That bound is asserted as a deliberate contract rather than left implicit.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("transaction-type record mapper")
class TranTypeRecordMapperTest {

    /** The shipped fixture measures 427 bytes: 7 records at a 61-byte stride. */
    private static final int SEEDED_RECORDS = 7;

    /** Fixture file name, resolved from the test classpath by the shared loader. */
    private static final String FIXTURE = "trantype.txt";

    /** The character the reference-table fixtures use for filler, measured over all seven records. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE, TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the mapped prefix and the filler exactly tile the 60-byte record with no gap "
                + "and no overlap")
        void theMappedPrefixAndFillerTileTheRecord() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET
                    + TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET
                    + TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET
                    + TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the copybook widths are reproduced literally: 2 + 50 mapped, 8 filler, 60 total")
        void theCopybookWidthsAreReproduced() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH).isEqualTo(2);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH).isEqualTo(50);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH).isEqualTo(8);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH).isEqualTo(52);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH).isEqualTo(60);
        }

        @Test
        @DisplayName("the emitted filler character is a space, which is what makes the fixture "
                + "comparison bound necessary")
        void theEmittedFillerCharacterIsASpace() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER).isEqualTo(' ');
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER)
                    .isNotEqualTo(FIXTURE_FILLER_CHARACTER);
        }
    }

    @Nested
    @DisplayName("parsing the shipped fixture")
    class ParsingTheShippedFixture {

        @Test
        @DisplayName("the fixture holds exactly seven records, each measuring the declared 60 bytes")
        void theFixtureHoldsSevenRecordsAtTheDeclaredWidth() {
            final SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(loaded.recordWidth()).isEqualTo(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
            for (final String record : loaded.records()) {
                assertThat(record.getBytes(StandardCharsets.US_ASCII))
                        .hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("each parsed field equals the fixture slice taken at the mapper's own declared "
                + "offset, so the offsets are proven against the authority layout")
        void eachParsedFieldEqualsTheFixtureSliceAtTheDeclaredOffset() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final TransactionType parsed = TranTypeRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getTranType())
                        .as("code of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET,
                                TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH));
                assertThat(parsed.getTranTypeDesc())
                        .as("description of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET,
                                TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH));
            }
        }

        @Test
        @DisplayName("the two-character code keeps its leading zero rather than being narrowed to a "
                + "number and re-formatted")
        void theCodeKeepsItsLeadingZero() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final TransactionType parsed = TranTypeRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getTranType())
                        .as("code of record %d", ordinal)
                        .hasSize(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH);
            }
            assertThat(TranTypeRecordMapper.fromRecord(loaded.record(1)).getTranType())
                    .startsWith("0");
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points agree with the string entry point")
        void theByteEntryPointsAgreeWithTheStringEntryPoint() {
            final SeededRecordFixture loaded = fixture();
            final String image = loaded.record(1);
            final byte[] encoded = image.getBytes(StandardCharsets.US_ASCII);
            final byte[] framed = new byte[encoded.length + 5];
            System.arraycopy(encoded, 0, framed, 5, encoded.length);

            final TransactionType fromString = TranTypeRecordMapper.fromRecord(image);
            final TransactionType fromBytes = TranTypeRecordMapper.fromRecord(encoded);
            final TransactionType fromRange = TranTypeRecordMapper.fromRecord(framed, 5);

            assertThat(TranTypeRecordMapper.toRecord(fromBytes))
                    .isEqualTo(TranTypeRecordMapper.toRecord(fromString));
            assertThat(TranTypeRecordMapper.toRecord(fromRange))
                    .isEqualTo(TranTypeRecordMapper.toRecord(fromString));
        }
    }

    @Nested
    @DisplayName("emitting a record")
    class EmittingARecord {

        @Test
        @DisplayName("every fixture record round-trips byte-identically over the mapped prefix, the "
                + "only window where the fixture and the emitter agree on filler")
        void everyFixtureRecordRoundTripsOverTheMappedPrefix() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String original = loaded.record(ordinal);
                final String emitted =
                        TranTypeRecordMapper.toRecord(TranTypeRecordMapper.fromRecord(original));

                assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                        .as("emitted width of record %d", ordinal)
                        .hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
                assertThat(emitted.substring(0, TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH))
                        .as("mapped prefix of record %d", ordinal)
                        .isEqualTo(original.substring(0,
                                TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH));
            }
        }

        @Test
        @DisplayName("the emitted filler is spaces while the fixture's is ASCII zero, so a "
                + "whole-record comparison would fail - the bound is a real contract, not caution")
        void theEmittedFillerDiffersFromTheFixtureFiller() {
            final SeededRecordFixture loaded = fixture();
            final String original = loaded.record(1);
            final String emitted =
                    TranTypeRecordMapper.toRecord(TranTypeRecordMapper.fromRecord(original));

            assertThat(emitted.substring(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET))
                    .isEqualTo(String.valueOf(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER)
                            .repeat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH));
            assertThat(original.substring(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET))
                    .isEqualTo(String.valueOf(FIXTURE_FILLER_CHARACTER)
                            .repeat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH));
            assertThat(emitted).isNotEqualTo(original);
        }

        @Test
        @DisplayName("a description shorter than fifty characters is space-padded to the full field "
                + "width on output rather than stored short in the image")
        void aShortDescriptionIsPaddedToTheFullFieldWidth() {
            final TransactionType subject = new TransactionType("07", "REFUND");

            final String emitted = TranTypeRecordMapper.toRecord(subject);

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
            assertThat(emitted.substring(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET,
                    TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH))
                    .isEqualTo("REFUND" + " ".repeat(
                            TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH - "REFUND".length()));
            assertThat(TranTypeRecordMapper.fromRecord(emitted).getTranTypeDesc())
                    .isEqualTo("REFUND" + " ".repeat(
                            TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH - "REFUND".length()));
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 60 bytes as the string one")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final TransactionType subject =
                    TranTypeRecordMapper.fromRecord(fixture().record(1));

            assertThat(TranTypeRecordMapper.toRecordBytes(subject))
                    .isEqualTo(TranTypeRecordMapper.toRecord(subject)
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
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord("01"))
                    .withMessageContaining(
                            String.valueOf(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH));
        }

        @Test
        @DisplayName("a long image is refused rather than silently truncated")
        void aLongImageIsRefused() {
            final String overlong = "0".repeat(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(overlong));
        }

        @Test
        @DisplayName("an empty image is refused")
        void anEmptyImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(""));
        }

        @Test
        @DisplayName("a null image raises deterministically rather than yielding a partial entity")
        void aNullImageRaisesDeterministically() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((byte[]) null));
        }
    }
}
