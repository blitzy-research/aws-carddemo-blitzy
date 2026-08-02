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
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.Customer;
import com.carddemo.support.SeededRecordFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link CustomerRecordMapper} against the shipped customer fixture, which is the record
 * image the legacy estate actually produced.
 *
 * <p>The subject is the 500-byte {@code CUSTOMER-RECORD} layout of {@code CVCUS01Y}. Every offset
 * assertion here is a restatement of that copybook, and every field assertion compares the mapper's
 * output against a slice of {@code app/data/ASCII/custdata.txt} taken independently by
 * {@link SeededRecordFixture}, so a silently shifted offset cannot pass: the fixture is the
 * authority and the mapper is the thing under test.</p>
 *
 * <p>Two windows of this record are unlike every other field in the estate. {@code CUST-SSN} at
 * offset 279 and {@code CUST-GOVT-ISSUED-ID} at offset 288 are regulated identity data, so the
 * mapper never binds their cleartext to an entity. On the parse side it hands each slice to a
 * caller-supplied sealing operation and stores only what comes back; on the emit side it hands the
 * stored envelope to a caller-supplied revealing operation and places what comes back. This test
 * exercises that boundary with a real authenticated codec rather than an identity function, so the
 * envelope shape the entity enforces is genuinely satisfied rather than side-stepped.</p>
 *
 * <p>The absent-identifier path deserves particular attention and is covered by
 * {@link EmittingARecord} below. Both regulated columns are nullable and every seeded row stores
 * neither identifier, so an entity holding {@code null} for either one is the common case rather
 * than an edge case. The mapper renders an absent identifier as a field of spaces and deliberately
 * does not use numeric placement on that path, because zero-filling would fabricate a value that
 * was never held. That asymmetry between the two placements is the single most load-bearing detail
 * in this class, and it is asserted directly.</p>
 */
@DisplayName("CustomerRecordMapper - the 500-byte CVCUS01Y record layout")
class CustomerRecordMapperTest {

    /** The shipped fixture, whose every record measures the declared width. */
    private static final String FIXTURE_FILE = "custdata.txt";

    /**
     * A fixed key for the test's sealing and revealing pair. A literal key is correct here because
     * the test needs a deterministic round trip, not secrecy; production key material is supplied
     * by configuration and never appears in source.
     */
    private static final byte[] KEY = new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
    };

    /** Seals a regulated cleartext into the module's protected-value envelope. */
    private static final UnaryOperator<String> SEALER = value -> SensitiveFieldCodec.protect(value, KEY);

    /** Recovers the cleartext a sealed envelope carries. */
    private static final UnaryOperator<String> REVEALER = envelope -> SensitiveFieldCodec.reveal(envelope, KEY);

    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE_FILE, CustomerRecordMapper.RECORD_WIDTH);
    }

    private static String spaces(int count) {
        return " ".repeat(count);
    }

    @Nested
    @DisplayName("the declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("every field offset is the sum of the widths that precede it, so the mapped "
                + "prefix tiles the record with no gap and no overlap")
        void offsetsTileTheMappedPrefix() {
            int cursor = 0;
            cursor += assertFieldAt(CustomerRecordMapper.CUST_ID_OFFSET,
                    CustomerRecordMapper.CUST_ID_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.FIRST_NAME_OFFSET,
                    CustomerRecordMapper.FIRST_NAME_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.MIDDLE_NAME_OFFSET,
                    CustomerRecordMapper.MIDDLE_NAME_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.LAST_NAME_OFFSET,
                    CustomerRecordMapper.LAST_NAME_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.ADDR_LINE_1_OFFSET,
                    CustomerRecordMapper.ADDR_LINE_1_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.ADDR_LINE_2_OFFSET,
                    CustomerRecordMapper.ADDR_LINE_2_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.ADDR_LINE_3_OFFSET,
                    CustomerRecordMapper.ADDR_LINE_3_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.ADDR_STATE_CD_OFFSET,
                    CustomerRecordMapper.ADDR_STATE_CD_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.ADDR_COUNTRY_CD_OFFSET,
                    CustomerRecordMapper.ADDR_COUNTRY_CD_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.ADDR_ZIP_OFFSET,
                    CustomerRecordMapper.ADDR_ZIP_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.PHONE_NUM_1_OFFSET,
                    CustomerRecordMapper.PHONE_NUM_1_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.PHONE_NUM_2_OFFSET,
                    CustomerRecordMapper.PHONE_NUM_2_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.CUST_SSN_OFFSET,
                    CustomerRecordMapper.CUST_SSN_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET,
                    CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.CUST_DOB_OFFSET,
                    CustomerRecordMapper.CUST_DOB_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.EFT_ACCOUNT_ID_OFFSET,
                    CustomerRecordMapper.EFT_ACCOUNT_ID_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.PRI_CARD_HOLDER_IND_OFFSET,
                    CustomerRecordMapper.PRI_CARD_HOLDER_IND_LENGTH, cursor);
            cursor += assertFieldAt(CustomerRecordMapper.FICO_CREDIT_SCORE_OFFSET,
                    CustomerRecordMapper.FICO_CREDIT_SCORE_LENGTH, cursor);

            assertThat(cursor)
                    .as("the eighteen mapped fields must exactly fill the mapped data width")
                    .isEqualTo(CustomerRecordMapper.MAPPED_DATA_WIDTH);
        }

        private int assertFieldAt(int declaredOffset, int declaredLength, int expectedOffset) {
            assertThat(declaredOffset)
                    .as("a field declared %d bytes wide must begin where its predecessors end",
                            declaredLength)
                    .isEqualTo(expectedOffset);
            return declaredLength;
        }

        @Test
        @DisplayName("the widths are the CVCUS01Y widths, character for character")
        void widthsAreTheCopybookWidths() {
            assertThat(CustomerRecordMapper.CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CustomerRecordMapper.FIRST_NAME_LENGTH).isEqualTo(25);
            assertThat(CustomerRecordMapper.MIDDLE_NAME_LENGTH).isEqualTo(25);
            assertThat(CustomerRecordMapper.LAST_NAME_LENGTH).isEqualTo(25);
            assertThat(CustomerRecordMapper.ADDR_LINE_1_LENGTH).isEqualTo(50);
            assertThat(CustomerRecordMapper.ADDR_LINE_2_LENGTH).isEqualTo(50);
            assertThat(CustomerRecordMapper.ADDR_LINE_3_LENGTH).isEqualTo(50);
            assertThat(CustomerRecordMapper.ADDR_STATE_CD_LENGTH).isEqualTo(2);
            assertThat(CustomerRecordMapper.ADDR_COUNTRY_CD_LENGTH).isEqualTo(3);
            assertThat(CustomerRecordMapper.ADDR_ZIP_LENGTH).isEqualTo(10);
            assertThat(CustomerRecordMapper.PHONE_NUM_1_LENGTH).isEqualTo(15);
            assertThat(CustomerRecordMapper.PHONE_NUM_2_LENGTH).isEqualTo(15);
            assertThat(CustomerRecordMapper.CUST_DOB_LENGTH).isEqualTo(10);
            assertThat(CustomerRecordMapper.EFT_ACCOUNT_ID_LENGTH).isEqualTo(10);
            assertThat(CustomerRecordMapper.PRI_CARD_HOLDER_IND_LENGTH).isEqualTo(1);
            assertThat(CustomerRecordMapper.FICO_CREDIT_SCORE_LENGTH).isEqualTo(3);
        }

        @Test
        @DisplayName("the two regulated windows sit exactly where the copybook puts them, because "
                + "an off-by-one here would seal the wrong bytes")
        void regulatedWindowsSitWhereTheCopybookPutsThem() {
            assertThat(CustomerRecordMapper.CUST_SSN_OFFSET).isEqualTo(279);
            assertThat(CustomerRecordMapper.CUST_SSN_LENGTH).isEqualTo(9);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET).isEqualTo(288);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH).isEqualTo(20);
        }

        @Test
        @DisplayName("the filler occupies the record tail, so the mapped data and the filler "
                + "together account for all 500 bytes")
        void fillerOccupiesTheRecordTail() {
            assertThat(CustomerRecordMapper.MAPPED_DATA_WIDTH).isEqualTo(332);
            assertThat(CustomerRecordMapper.FILLER_OFFSET)
                    .isEqualTo(CustomerRecordMapper.MAPPED_DATA_WIDTH);
            assertThat(CustomerRecordMapper.FILLER_LENGTH).isEqualTo(168);
            assertThat(CustomerRecordMapper.FILLER_OFFSET + CustomerRecordMapper.FILLER_LENGTH)
                    .isEqualTo(CustomerRecordMapper.RECORD_WIDTH);
            assertThat(CustomerRecordMapper.RECORD_WIDTH).isEqualTo(500);
        }

        @Test
        @DisplayName("both copybook spellings of the date-of-birth field are published, because "
                + "CVCUS01Y and CUSTREC name the same ten bytes differently")
        void bothDateOfBirthSpellingsArePublished() {
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CVCUS01Y).isEqualTo("CUST-DOB-YYYY-MM-DD");
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CUSTREC).isEqualTo("CUST-DOB-YYYYMMDD");
            assertThat(CustomerRecordMapper.ARTEFACT).contains("CVCUS01Y", "CUSTREC");
        }
    }

    @Nested
    @DisplayName("parsing the shipped fixture")
    class ParsingTheShippedFixture {

        @Test
        @DisplayName("the fixture holds fifty records, each measuring the declared 500 bytes")
        void theFixtureHoldsFiftyRecords() {
            SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(50);
            assertThat(loaded.recordWidth()).isEqualTo(CustomerRecordMapper.RECORD_WIDTH);
            assertThat(loaded.impliedByteCount()).isEqualTo(25_050);
        }

        @Test
        @DisplayName("every unregulated field of the first record equals the slice the fixture "
                + "reports at that offset")
        void everyUnregulatedFieldEqualsItsSlice() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            assertThat(customer.getCustId()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.CUST_ID_OFFSET,
                            CustomerRecordMapper.CUST_ID_LENGTH));
            assertThat(customer.getFirstName()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.FIRST_NAME_OFFSET,
                            CustomerRecordMapper.FIRST_NAME_LENGTH));
            assertThat(customer.getMiddleName()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.MIDDLE_NAME_OFFSET,
                            CustomerRecordMapper.MIDDLE_NAME_LENGTH));
            assertThat(customer.getLastName()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.LAST_NAME_OFFSET,
                            CustomerRecordMapper.LAST_NAME_LENGTH));
            assertThat(customer.getAddrLine1()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.ADDR_LINE_1_OFFSET,
                            CustomerRecordMapper.ADDR_LINE_1_LENGTH));
            assertThat(customer.getAddrLine2()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.ADDR_LINE_2_OFFSET,
                            CustomerRecordMapper.ADDR_LINE_2_LENGTH));
            assertThat(customer.getAddrLine3()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.ADDR_LINE_3_OFFSET,
                            CustomerRecordMapper.ADDR_LINE_3_LENGTH));
            assertThat(customer.getAddrStateCd()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.ADDR_STATE_CD_OFFSET,
                            CustomerRecordMapper.ADDR_STATE_CD_LENGTH));
            assertThat(customer.getAddrCountryCd()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.ADDR_COUNTRY_CD_OFFSET,
                            CustomerRecordMapper.ADDR_COUNTRY_CD_LENGTH));
            assertThat(customer.getAddrZip()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.ADDR_ZIP_OFFSET,
                            CustomerRecordMapper.ADDR_ZIP_LENGTH));
            assertThat(customer.getPhoneNum1()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.PHONE_NUM_1_OFFSET,
                            CustomerRecordMapper.PHONE_NUM_1_LENGTH));
            assertThat(customer.getPhoneNum2()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.PHONE_NUM_2_OFFSET,
                            CustomerRecordMapper.PHONE_NUM_2_LENGTH));
            assertThat(customer.getCustDob()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.CUST_DOB_OFFSET,
                            CustomerRecordMapper.CUST_DOB_LENGTH));
            assertThat(customer.getEftAccountId()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.EFT_ACCOUNT_ID_OFFSET,
                            CustomerRecordMapper.EFT_ACCOUNT_ID_LENGTH));
            assertThat(customer.getPriCardHolderInd()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.PRI_CARD_HOLDER_IND_OFFSET,
                            CustomerRecordMapper.PRI_CARD_HOLDER_IND_LENGTH));
            assertThat(customer.getFicoCreditScore()).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.FICO_CREDIT_SCORE_OFFSET,
                            CustomerRecordMapper.FICO_CREDIT_SCORE_LENGTH));
        }

        @Test
        @DisplayName("a display-text field keeps the trailing blanks the record carries, because "
                + "the mapper reads a fixed-width slice and never trims it")
        void displayTextKeepsItsTrailingBlanks() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            assertThat(customer.getFirstName())
                    .hasSize(CustomerRecordMapper.FIRST_NAME_LENGTH)
                    .endsWith(" ")
                    .isEqualTo("Immanuel" + spaces(17));
        }

        @Test
        @DisplayName("a zoned identifier keeps its leading zeros, because they are record bytes "
                + "rather than an arithmetic artefact")
        void identifiersKeepTheirLeadingZeros() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            assertThat(customer.getCustId()).isEqualTo("000000001").startsWith("00000000");
            assertThat(customer.getEftAccountId()).isEqualTo("0053581756");
            assertThat(customer.getFicoCreditScore()).isEqualTo("274");
        }

        @Test
        @DisplayName("all fifty records parse, and each yields the identity of the row the fixture "
                + "holds at that ordinal")
        void allFiftyRecordsParse() {
            SeededRecordFixture loaded = fixture();
            List<String> parsedIdentifiers = new ArrayList<>();

            for (int ordinal = 1; ordinal <= loaded.recordCount(); ordinal++) {
                Customer customer = CustomerRecordMapper.fromRecord(loaded.record(ordinal), SEALER);
                parsedIdentifiers.add(customer.getCustId());
                assertThat(customer.getCustId()).isEqualTo(
                        loaded.field(ordinal, CustomerRecordMapper.CUST_ID_OFFSET,
                                CustomerRecordMapper.CUST_ID_LENGTH));
            }

            assertThat(parsedIdentifiers).hasSize(50).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points agree with the String entry point")
        void everyEntryPointAgrees() {
            SeededRecordFixture loaded = fixture();
            String image = loaded.record(1);
            byte[] bytes = image.getBytes(StandardCharsets.US_ASCII);
            byte[] offsetBuffer = new byte[bytes.length + 7];
            System.arraycopy(bytes, 0, offsetBuffer, 7, bytes.length);

            Customer fromString = CustomerRecordMapper.fromRecord(image, SEALER);
            Customer fromBytes = CustomerRecordMapper.fromRecord(bytes, SEALER);
            Customer fromRange = CustomerRecordMapper.fromRecord(offsetBuffer, 7, SEALER);

            assertThat(fromBytes.getCustId()).isEqualTo(fromString.getCustId());
            assertThat(fromRange.getCustId()).isEqualTo(fromString.getCustId());
            assertThat(fromBytes.getAddrZip()).isEqualTo(fromString.getAddrZip());
            assertThat(fromRange.getPhoneNum1()).isEqualTo(fromString.getPhoneNum1());
            assertThat(fromRange.getFicoCreditScore()).isEqualTo(fromString.getFicoCreditScore());
        }
    }

    @Nested
    @DisplayName("the regulated-identity boundary")
    class TheRegulatedIdentityBoundary {

        @Test
        @DisplayName("the entity holds a sealed envelope rather than the cleartext the record "
                + "carried, for both regulated windows")
        void theEntityHoldsEnvelopesRatherThanCleartext() {
            SeededRecordFixture loaded = fixture();
            String nationalCleartext = loaded.field(1, CustomerRecordMapper.CUST_SSN_OFFSET,
                    CustomerRecordMapper.CUST_SSN_LENGTH);
            String governmentCleartext = loaded.field(1, CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET,
                    CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH);

            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            assertThat(customer.getCustSsn())
                    .startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX)
                    .isNotEqualTo(nationalCleartext)
                    .doesNotContain(nationalCleartext);
            assertThat(customer.getGovtIssuedId())
                    .startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX)
                    .isNotEqualTo(governmentCleartext)
                    .doesNotContain(governmentCleartext);
        }

        @Test
        @DisplayName("revealing what the mapper sealed returns the record bytes exactly, so the "
                + "boundary loses nothing")
        void revealingWhatWasSealedReturnsTheRecordBytes() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            assertThat(REVEALER.apply(customer.getCustSsn())).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.CUST_SSN_OFFSET,
                            CustomerRecordMapper.CUST_SSN_LENGTH));
            assertThat(REVEALER.apply(customer.getGovtIssuedId())).isEqualTo(
                    loaded.field(1, CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET,
                            CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH));
        }

        @Test
        @DisplayName("the sealing operation is applied exactly twice for one record - once per "
                + "regulated window and to no other field")
        void theSealerIsAppliedExactlyTwice() {
            SeededRecordFixture loaded = fixture();
            List<String> sealed = new ArrayList<>();
            UnaryOperator<String> countingSealer = value -> {
                sealed.add(value);
                return SensitiveFieldCodec.protect(value, KEY);
            };

            CustomerRecordMapper.fromRecord(loaded.record(1), countingSealer);

            assertThat(sealed).containsExactly(
                    loaded.field(1, CustomerRecordMapper.CUST_SSN_OFFSET,
                            CustomerRecordMapper.CUST_SSN_LENGTH),
                    loaded.field(1, CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET,
                            CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH));
        }

        @Test
        @DisplayName("a sealing operation that drops the national identifier is rejected, naming "
                + "that field, because a regulated value is sealed and never dropped")
        void aSealerThatDropsTheNationalIdentifierIsRejected() {
            SeededRecordFixture loaded = fixture();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(loaded.record(1),
                            value -> null))
                    .withMessageContaining("CUST-SSN")
                    .withMessageContaining("never dropped");
        }

        @Test
        @DisplayName("a sealing operation that drops only the government identifier is rejected, "
                + "naming that field rather than the one that succeeded")
        void aSealerThatDropsOnlyTheGovernmentIdentifierIsRejected() {
            SeededRecordFixture loaded = fixture();
            UnaryOperator<String> selectiveSealer = value ->
                    value.length() == CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH
                            ? null
                            : SensitiveFieldCodec.protect(value, KEY);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(loaded.record(1),
                            selectiveSealer))
                    .withMessageContaining("CUST-GOVT-ISSUED-ID");
        }
    }

    @Nested
    @DisplayName("emitting a record")
    class EmittingARecord {

        @Test
        @DisplayName("sealing on the way in and revealing on the way out reproduces all 500 bytes "
                + "of the fixture record, filler included")
        void theWholeRecordRoundTrips() {
            SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= loaded.recordCount(); ordinal++) {
                String image = loaded.record(ordinal);
                Customer customer = CustomerRecordMapper.fromRecord(image, SEALER);

                String emitted = CustomerRecordMapper.toRecord(customer, REVEALER);

                assertThat(emitted)
                        .as("record %d must round trip byte for byte", ordinal)
                        .hasSize(CustomerRecordMapper.RECORD_WIDTH)
                        .isEqualTo(image);
            }
        }

        @Test
        @DisplayName("the byte-emitting entry point agrees with the String one")
        void theByteEmittingEntryPointAgrees() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            byte[] emitted = CustomerRecordMapper.toRecordBytes(customer, REVEALER);

            assertThat(emitted).hasSize(CustomerRecordMapper.RECORD_WIDTH);
            assertThat(new String(emitted, StandardCharsets.US_ASCII))
                    .isEqualTo(CustomerRecordMapper.toRecord(customer, REVEALER));
        }

        @Test
        @DisplayName("an absent government identifier renders its twenty bytes as spaces, not as "
                + "zeros, because zero-filling would fabricate a value that was never held")
        void anAbsentGovernmentIdentifierRendersAsSpaces() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);
            customer.setGovtIssuedId(null);

            String emitted = CustomerRecordMapper.toRecord(customer, REVEALER);

            assertThat(emitted).hasSize(CustomerRecordMapper.RECORD_WIDTH);
            assertThat(emitted.substring(CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET,
                    CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET
                            + CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH))
                    .isEqualTo(spaces(CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH))
                    .doesNotContain("0");
        }

        @Test
        @DisplayName("an absent national identifier renders its nine bytes as spaces, symmetrically "
                + "with the government identifier")
        void anAbsentNationalIdentifierRendersAsSpaces() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);
            customer.setCustSsn(null);

            String emitted = CustomerRecordMapper.toRecord(customer, REVEALER);

            assertThat(emitted).hasSize(CustomerRecordMapper.RECORD_WIDTH);
            assertThat(emitted.substring(CustomerRecordMapper.CUST_SSN_OFFSET,
                    CustomerRecordMapper.CUST_SSN_OFFSET + CustomerRecordMapper.CUST_SSN_LENGTH))
                    .isEqualTo(spaces(CustomerRecordMapper.CUST_SSN_LENGTH))
                    .doesNotContain("0");
        }

        @Test
        @DisplayName("an entity holding neither identifier - which is what every seeded row holds - "
                + "emits both windows blank and leaves every other field untouched")
        void anEntityHoldingNeitherIdentifierEmitsBothWindowsBlank() {
            SeededRecordFixture loaded = fixture();
            String image = loaded.record(1);
            Customer customer = CustomerRecordMapper.fromRecord(image, SEALER);
            customer.setCustSsn(null);
            customer.setGovtIssuedId(null);

            String emitted = CustomerRecordMapper.toRecord(customer, REVEALER);

            int regulatedFrom = CustomerRecordMapper.CUST_SSN_OFFSET;
            int regulatedTo = CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET
                    + CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH;
            assertThat(emitted).hasSize(CustomerRecordMapper.RECORD_WIDTH);
            assertThat(emitted.substring(regulatedFrom, regulatedTo))
                    .isEqualTo(spaces(regulatedTo - regulatedFrom));
            assertThat(emitted.substring(0, regulatedFrom))
                    .as("the bytes before the regulated windows are unaffected")
                    .isEqualTo(image.substring(0, regulatedFrom));
            assertThat(emitted.substring(regulatedTo))
                    .as("the bytes after the regulated windows are unaffected")
                    .isEqualTo(image.substring(regulatedTo));
        }

        @Test
        @DisplayName("the revealing operation is not consulted for an absent identifier, so it "
                + "never sees a null argument")
        void theRevealerIsNotConsultedForAnAbsentIdentifier() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);
            customer.setCustSsn(null);
            customer.setGovtIssuedId(null);
            List<String> revealed = new ArrayList<>();
            UnaryOperator<String> countingRevealer = envelope -> {
                revealed.add(envelope);
                return SensitiveFieldCodec.reveal(envelope, KEY);
            };

            CustomerRecordMapper.toRecord(customer, countingRevealer);

            assertThat(revealed).isEmpty();
        }

        @Test
        @DisplayName("the revealing operation is consulted once per identifier that is present")
        void theRevealerIsConsultedOncePerPresentIdentifier() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);
            List<String> revealed = new ArrayList<>();
            UnaryOperator<String> countingRevealer = envelope -> {
                revealed.add(envelope);
                return SensitiveFieldCodec.reveal(envelope, KEY);
            };

            CustomerRecordMapper.toRecord(customer, countingRevealer);

            assertThat(revealed).containsExactly(customer.getCustSsn(), customer.getGovtIssuedId());
        }

        @Test
        @DisplayName("a revealing operation that drops a present identifier is rejected, because a "
                + "fixed-width field cannot be left unwritten")
        void aRevealerThatDropsAPresentIdentifierIsRejected() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, envelope -> null))
                    .withMessageContaining("CUST-SSN")
                    .withMessageContaining("unwritten");
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {

        @Test
        @DisplayName("an image shorter than the declared record width is rejected, and the message "
                + "names the width the layout requires")
        void aShortImageIsRejected() {
            String shortImage = "0".repeat(CustomerRecordMapper.RECORD_WIDTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(shortImage, SEALER))
                    .withMessageContaining(String.valueOf(CustomerRecordMapper.RECORD_WIDTH));
        }

        @Test
        @DisplayName("an image longer than the declared record width is rejected rather than "
                + "silently truncated")
        void aLongImageIsRejected() {
            String longImage = "0".repeat(CustomerRecordMapper.RECORD_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(longImage, SEALER))
                    .withMessageContaining(String.valueOf(CustomerRecordMapper.RECORD_WIDTH));
        }

        @Test
        @DisplayName("an empty image is rejected")
        void anEmptyImageIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord("", SEALER))
                    .withMessageContaining(String.valueOf(CustomerRecordMapper.RECORD_WIDTH));
        }

        @Test
        @DisplayName("a null image, a null sealing operation, a null entity and a null revealing "
                + "operation are each rejected by name")
        void nullArgumentsAreRejectedByName() {
            SeededRecordFixture loaded = fixture();
            Customer customer = CustomerRecordMapper.fromRecord(loaded.record(1), SEALER);

            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord((String) null, SEALER))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord((byte[]) null, SEALER))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(loaded.record(1), null))
                    .withMessageContaining("regulatedFieldSealer");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(null, REVEALER))
                    .withMessageContaining("customer");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, null))
                    .withMessageContaining("regulatedFieldRevealer");
        }

        @Test
        @DisplayName("a byte range that runs past the end of its buffer is rejected")
        void aByteRangePastTheBufferEndIsRejected() {
            SeededRecordFixture loaded = fixture();
            byte[] bytes = loaded.record(1).getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(bytes, 1, SEALER));
        }
    }
}
