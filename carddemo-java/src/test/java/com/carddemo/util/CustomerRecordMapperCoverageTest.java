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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import com.carddemo.domain.Customer;
import com.carddemo.support.SeededRecordFixture;
import jakarta.persistence.Column;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Byte-parity acceptance for the 500-byte customer record layout.
 *
 * <h2>What is under test</h2>
 * {@link CustomerRecordMapper} is the widest layout in the estate at 500 bytes and the only one whose
 * whole public surface takes a caller-supplied operation. Two of its eighteen fields are regulated -
 * the national identifier and the government-issued identifier - and the mapper never touches
 * cryptography for either: it hands the raw slice to a sealing operation on the way in and asks a
 * revealing operation for the cleartext on the way out. That indirection is the layout's defining
 * property and it is what most of this class asserts, because a mapper that sealed the wrong slice, or
 * sealed one slice twice, or quietly skipped the operation, would still produce a record of exactly the
 * right width.
 *
 * <p>Four further properties are asserted with particular care. The eighteen fields tile
 * {@code [0, 332)} with the filler carrying {@code [332, 500)}, so no byte is unaccounted for. Three
 * fields are unsigned external decimal and are placed right-justified and zero-filled while the other
 * fifteen are character data and are placed left-justified and space-padded - including the postal code
 * and the funds-transfer account identifier, which merely look numeric. An absent national identifier
 * is rendered as nine spaces rather than nine zeros, because zero-filling would fabricate a value that
 * was never held. And the single ten-byte date slice serves both copybook spellings of the same field,
 * so there is one slice and one attribute rather than two of either.
 *
 * <h2>Where the expectations come from</h2>
 * The geometry is stated as literal integers rather than read from the class under test, so a change to
 * a published offset fails here rather than being silently ratified. Field values are transcribed from
 * an independent byte-level reading of the shipped fixture {@code app/data/ASCII/custdata.txt}, whose
 * fifty records are 500 bytes each on a 501-byte stride for 25,050 bytes in total. Production output is
 * never used as its own oracle: every expected image in this class is assembled here from transcribed
 * values and declared widths.
 *
 * <h2>Why a whole-record comparison is asserted here</h2>
 * This fixture's 168 filler bytes are spaces, which is exactly what the encoder writes, so a full
 * 500-byte comparison is meaningful and is the strongest assertion available. Three of the estate's
 * other layouts hold ASCII zeros in filler that their encoders render as spaces, and for those a
 * whole-record comparison would fail for a reason that has nothing to do with the mapper. The
 * distinction is per layout and is asserted, not assumed: the filler content of every fixture record is
 * checked here directly.
 *
 * <h2>The sealing test double, and why it is not cryptography</h2>
 * Most tests here supply a deterministic, exactly reversible envelope built from a length byte, the
 * cleartext bytes and zero padding to the module's minimum body size, Base64-encoded behind the
 * {@code ENC1:} marker. It satisfies the entity's structural admission test, it lets the exact
 * cleartext handed to the operation be observed and asserted, and it keeps this class free of keys.
 * That the double is not a straw man is proved separately: one test composes the mapper with the
 * module's shipped {@link SensitiveFieldCodec} under a real key and asserts the same byte-exact round
 * trip.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Layout authority
 * {@code app/cpy/CVCUS01Y.cpy} and its unhyphenated variant {@code app/cpy/CUSTREC.cpy}; fixture
 * authority {@code app/data/ASCII/custdata.txt}.
 */
@DisplayName("CustomerRecordMapper byte-parity contract")
class CustomerRecordMapperCoverageTest {

    /** The artefact name the mapper names in every diagnostic, covering both copybook spellings. */
    private static final String EXPECTED_ARTEFACT = "CUSTOMER-RECORD (CVCUS01Y/CUSTREC)";

    /** Declared width of the whole record image. */
    private static final int EXPECTED_RECORD_WIDTH = 500;

    /** Declared width of the mapped prefix, being the eighteen fields without the trailing filler. */
    private static final int EXPECTED_MAPPED_DATA_WIDTH = 332;

    /** Declared placement of the trailing filler. */
    private static final int EXPECTED_FILLER_OFFSET = 332;

    /** Declared width of the trailing filler. */
    private static final int EXPECTED_FILLER_WIDTH = 168;

    /** Declared placement of the nine-digit customer identifier. */
    private static final int EXPECTED_CUST_ID_OFFSET = 0;

    /** Declared width of the customer identifier. */
    private static final int EXPECTED_CUST_ID_WIDTH = 9;

    /** Declared placement of the given name. */
    private static final int EXPECTED_FIRST_NAME_OFFSET = 9;

    /** Declared placement of the middle name. */
    private static final int EXPECTED_MIDDLE_NAME_OFFSET = 34;

    /** Declared placement of the family name. */
    private static final int EXPECTED_LAST_NAME_OFFSET = 59;

    /** Declared width shared by the three name fields. */
    private static final int EXPECTED_NAME_WIDTH = 25;

    /** Declared placement of the first address line. */
    private static final int EXPECTED_ADDR_LINE_1_OFFSET = 84;

    /** Declared placement of the second address line. */
    private static final int EXPECTED_ADDR_LINE_2_OFFSET = 134;

    /** Declared placement of the third address line. */
    private static final int EXPECTED_ADDR_LINE_3_OFFSET = 184;

    /** Declared width shared by the three address lines. */
    private static final int EXPECTED_ADDR_LINE_WIDTH = 50;

    /** Declared placement of the two-character state code. */
    private static final int EXPECTED_ADDR_STATE_CD_OFFSET = 234;

    /** Declared width of the state code. */
    private static final int EXPECTED_ADDR_STATE_CD_WIDTH = 2;

    /** Declared placement of the three-character country code. */
    private static final int EXPECTED_ADDR_COUNTRY_CD_OFFSET = 236;

    /** Declared width of the country code. */
    private static final int EXPECTED_ADDR_COUNTRY_CD_WIDTH = 3;

    /** Declared placement of the ten-character postal code. */
    private static final int EXPECTED_ADDR_ZIP_OFFSET = 239;

    /** Declared placement of the first telephone number. */
    private static final int EXPECTED_PHONE_NUM_1_OFFSET = 249;

    /** Declared placement of the second telephone number. */
    private static final int EXPECTED_PHONE_NUM_2_OFFSET = 264;

    /** Declared width shared by the two telephone numbers. */
    private static final int EXPECTED_PHONE_WIDTH = 15;

    /** Declared placement of the regulated national identifier. */
    private static final int EXPECTED_CUST_SSN_OFFSET = 279;

    /** Declared width of the national identifier. */
    private static final int EXPECTED_CUST_SSN_WIDTH = 9;

    /** Declared placement of the regulated government-issued identifier. */
    private static final int EXPECTED_GOVT_ISSUED_ID_OFFSET = 288;

    /** Declared width of the government-issued identifier. */
    private static final int EXPECTED_GOVT_ISSUED_ID_WIDTH = 20;

    /** Declared placement of the ten-character date of birth. */
    private static final int EXPECTED_CUST_DOB_OFFSET = 308;

    /** Declared placement of the ten-character funds-transfer account identifier. */
    private static final int EXPECTED_EFT_ACCOUNT_ID_OFFSET = 318;

    /** Declared width shared by the postal code, the date of birth and the transfer identifier. */
    private static final int EXPECTED_TEN_CHARACTER_WIDTH = 10;

    /** Declared placement of the single-character primary-cardholder indicator. */
    private static final int EXPECTED_PRI_CARD_HOLDER_IND_OFFSET = 328;

    /** Declared width of the primary-cardholder indicator. */
    private static final int EXPECTED_PRI_CARD_HOLDER_IND_WIDTH = 1;

    /** Declared placement of the three-digit credit score. */
    private static final int EXPECTED_FICO_CREDIT_SCORE_OFFSET = 329;

    /** Declared width of the credit score. */
    private static final int EXPECTED_FICO_CREDIT_SCORE_WIDTH = 3;

    /** Legacy name of the identifier field. */
    private static final String FIELD_CUST_ID = "CUST-ID";

    /** Legacy name of the given-name field. */
    private static final String FIELD_FIRST_NAME = "CUST-FIRST-NAME";

    /** Legacy name of the national-identifier field. */
    private static final String FIELD_CUST_SSN = "CUST-SSN";

    /** Legacy name of the government-issued-identifier field. */
    private static final String FIELD_GOVT_ISSUED_ID = "CUST-GOVT-ISSUED-ID";

    /** The hyphenated copybook's name for the date of birth, used by the single mapped slice. */
    private static final String FIELD_CUST_DOB_CVCUS01Y = "CUST-DOB-YYYY-MM-DD";

    /** The unhyphenated copybook's name for the same ten bytes at the same offset. */
    private static final String FIELD_CUST_DOB_CUSTREC = "CUST-DOB-YYYYMMDD";

    /** The module's protected-value envelope marker, which the entity requires and the double emits. */
    private static final String ENVELOPE_PREFIX = "ENC1:";

    /** The smallest body the entity will admit, being an initialisation vector plus a tag. */
    private static final int MINIMUM_ENVELOPE_BODY_BYTES = 28;

    /** Name of the shipped fixture that supplies the production-representative records. */
    private static final String FIXTURE_FILE = "custdata.txt";

    /** Number of records the shipped fixture holds. */
    private static final int EXPECTED_FIXTURE_RECORDS = 50;

    /** Byte count of the shipped fixture, its fifty records on a 501-byte stride. */
    private static final int EXPECTED_FIXTURE_BYTES = 25_050;

    /** The country code every fixture record carries, transcribed from a byte-level reading. */
    private static final String FIXTURE_COUNTRY_CODE = "USA";

    /** The primary-cardholder indicator every fixture record carries. */
    private static final String FIXTURE_PRIMARY_INDICATOR = "Y";

    /** Identifier of the first fixture record. */
    private static final String ROW_1_CUST_ID = "000000001";

    /** Given name of the first fixture record, before padding. */
    private static final String ROW_1_FIRST_NAME = "Immanuel";

    /** Middle name of the first fixture record, before padding. */
    private static final String ROW_1_MIDDLE_NAME = "Madeline";

    /** Family name of the first fixture record, before padding. */
    private static final String ROW_1_LAST_NAME = "Kessler";

    /** First address line of the first fixture record, before padding. */
    private static final String ROW_1_ADDR_LINE_1 = "618 Deshaun Route";

    /** Second address line of the first fixture record, before padding. */
    private static final String ROW_1_ADDR_LINE_2 = "Apt. 802";

    /** Third address line of the first fixture record, before padding. */
    private static final String ROW_1_ADDR_LINE_3 = "Altenwerthshire";

    /** State code of the first fixture record. */
    private static final String ROW_1_STATE_CODE = "NC";

    /** Postal code of the first fixture record, before padding: five digits in a ten-byte field. */
    private static final String ROW_1_ZIP = "12546";

    /** First telephone number of the first fixture record, before padding. */
    private static final String ROW_1_PHONE_1 = "(908)119-8310";

    /** Second telephone number of the first fixture record, before padding. */
    private static final String ROW_1_PHONE_2 = "(373)693-8684";

    /** National identifier of the first fixture record, exactly nine digits. */
    private static final String ROW_1_SSN = "020973888";

    /** Government-issued identifier of the first fixture record, exactly twenty characters. */
    private static final String ROW_1_GOVT_ISSUED_ID = "00000000000049368437";

    /** Date of birth of the first fixture record. */
    private static final String ROW_1_DOB = "1961-06-08";

    /** Funds-transfer account identifier of the first fixture record, exactly ten characters. */
    private static final String ROW_1_EFT_ACCOUNT_ID = "0053581756";

    /** Credit score of the first fixture record. */
    private static final String ROW_1_FICO = "274";

    /** Identifier of the fiftieth and last fixture record. */
    private static final String ROW_50_CUST_ID = "000000050";

    /** Given name of the last fixture record, before padding. */
    private static final String ROW_50_FIRST_NAME = "Aniya";

    /** Middle name of the last fixture record, before padding. */
    private static final String ROW_50_MIDDLE_NAME = "Alba";

    /** Family name of the last fixture record, before padding. */
    private static final String ROW_50_LAST_NAME = "Von";

    /** State code of the last fixture record. */
    private static final String ROW_50_STATE_CODE = "OR";

    /** National identifier of the last fixture record. */
    private static final String ROW_50_SSN = "931248469";

    /** Government-issued identifier of the last fixture record. */
    private static final String ROW_50_GOVT_ISSUED_ID = "00000000000030387824";

    /** Date of birth of the last fixture record. */
    private static final String ROW_50_DOB = "1960-12-01";

    /** Credit score of the last fixture record. */
    private static final String ROW_50_FICO = "623";

    /**
     * Loads the shipped fixture, declaring the width the reader must find in every record.
     *
     * @return the loaded fixture
     */
    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE_FILE, EXPECTED_RECORD_WIDTH);
    }

    /**
     * Repeats a character into a run of a given width.
     *
     * @param character the character to repeat
     * @param width     the number of repetitions
     * @return a string of exactly {@code width} copies of {@code character}
     */
    private static String run(final char character, final int width) {
        return String.valueOf(character).repeat(width);
    }

    /**
     * Renders a character field the way the encoder does: left-justified and padded with spaces.
     *
     * @param value the value to place
     * @param width the declared field width
     * @return the value followed by enough spaces to reach {@code width}
     */
    private static String padded(final String value, final int width) {
        return value + run(' ', width - value.length());
    }

    /**
     * Renders an unsigned external decimal field the way the encoder does: right-justified and
     * zero-filled, so that the value sits flush against the field's trailing edge.
     *
     * @param value the digits to place
     * @param width the declared field width
     * @return enough leading zeros to reach {@code width}, followed by the value
     */
    private static String zeroFilled(final String value, final int width) {
        return run('0', width - value.length()) + value;
    }

    /**
     * Seals cleartext into a structurally valid protected-value envelope without any cryptography.
     *
     * <p>The body is a single length byte, the cleartext bytes, and zero padding out to the module's
     * minimum body size. That makes the transformation deterministic and exactly reversible for any
     * printable slice, including one that is entirely spaces, which no length-free scheme could
     * recover.
     *
     * @param cleartext the raw slice to seal; must not be {@code null} and must be at most 255 bytes
     * @return an envelope the entity will admit and {@link #unseal(String)} will invert
     */
    private static String seal(final String cleartext) {
        byte[] raw = cleartext.getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[Math.max(raw.length + 1, MINIMUM_ENVELOPE_BODY_BYTES)];
        body[0] = (byte) raw.length;
        System.arraycopy(raw, 0, body, 1, raw.length);
        return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body);
    }

    /**
     * Recovers the exact cleartext a call to {@link #seal(String)} sealed.
     *
     * @param envelope an envelope produced by {@link #seal(String)}; must not be {@code null}
     * @return the original cleartext, byte for byte
     */
    private static String unseal(final String envelope) {
        byte[] body = Base64.getDecoder()
                .decode(envelope.substring(ENVELOPE_PREFIX.length()));
        return new String(body, 1, body[0] & 0xFF, StandardCharsets.US_ASCII);
    }

    /**
     * Assembles a canonical 500-byte record image from the transcribed first fixture row.
     *
     * <p>Assembled here from transcribed values and declared widths rather than read from the fixture,
     * so that the expectation is independent of the file as well as of the mapper.
     *
     * @return a well-formed record image of exactly 500 bytes
     */
    private static String canonicalImage() {
        return ROW_1_CUST_ID
                + padded(ROW_1_FIRST_NAME, EXPECTED_NAME_WIDTH)
                + padded(ROW_1_MIDDLE_NAME, EXPECTED_NAME_WIDTH)
                + padded(ROW_1_LAST_NAME, EXPECTED_NAME_WIDTH)
                + padded(ROW_1_ADDR_LINE_1, EXPECTED_ADDR_LINE_WIDTH)
                + padded(ROW_1_ADDR_LINE_2, EXPECTED_ADDR_LINE_WIDTH)
                + padded(ROW_1_ADDR_LINE_3, EXPECTED_ADDR_LINE_WIDTH)
                + ROW_1_STATE_CODE
                + FIXTURE_COUNTRY_CODE
                + padded(ROW_1_ZIP, EXPECTED_TEN_CHARACTER_WIDTH)
                + padded(ROW_1_PHONE_1, EXPECTED_PHONE_WIDTH)
                + padded(ROW_1_PHONE_2, EXPECTED_PHONE_WIDTH)
                + ROW_1_SSN
                + ROW_1_GOVT_ISSUED_ID
                + ROW_1_DOB
                + ROW_1_EFT_ACCOUNT_ID
                + FIXTURE_PRIMARY_INDICATOR
                + ROW_1_FICO
                + run(' ', EXPECTED_FILLER_WIDTH);
    }

    /**
     * Assembles a 500-byte image in which every field carries a distinct marker of its own declared
     * width, so that a mis-declared offset cannot be masked by a neighbour holding the same bytes.
     *
     * <p>The three unsigned external decimal fields carry digits, because they are placed numerically
     * on the way out and a letter there would prove nothing about justification.
     *
     * @return a well-formed record image of exactly 500 bytes
     */
    private static String markedImage() {
        return "111111111"
                + run('A', EXPECTED_NAME_WIDTH)
                + run('B', EXPECTED_NAME_WIDTH)
                + run('C', EXPECTED_NAME_WIDTH)
                + run('D', EXPECTED_ADDR_LINE_WIDTH)
                + run('E', EXPECTED_ADDR_LINE_WIDTH)
                + run('F', EXPECTED_ADDR_LINE_WIDTH)
                + "GH"
                + "IJK"
                + run('L', EXPECTED_TEN_CHARACTER_WIDTH)
                + run('M', EXPECTED_PHONE_WIDTH)
                + run('N', EXPECTED_PHONE_WIDTH)
                + "222222222"
                + run('P', EXPECTED_GOVT_ISSUED_ID_WIDTH)
                + run('Q', EXPECTED_TEN_CHARACTER_WIDTH)
                + run('R', EXPECTED_TEN_CHARACTER_WIDTH)
                + "S"
                + "333"
                + run(' ', EXPECTED_FILLER_WIDTH);
    }

    /**
     * Builds a customer whose eighteen attributes hold exactly what the canonical image holds, with
     * both regulated attributes already sealed.
     *
     * @return a customer that encodes back to {@link #canonicalImage()}
     */
    private static Customer canonicalEntity() {
        return new Customer(
                ROW_1_CUST_ID,
                padded(ROW_1_FIRST_NAME, EXPECTED_NAME_WIDTH),
                padded(ROW_1_MIDDLE_NAME, EXPECTED_NAME_WIDTH),
                padded(ROW_1_LAST_NAME, EXPECTED_NAME_WIDTH),
                padded(ROW_1_ADDR_LINE_1, EXPECTED_ADDR_LINE_WIDTH),
                padded(ROW_1_ADDR_LINE_2, EXPECTED_ADDR_LINE_WIDTH),
                padded(ROW_1_ADDR_LINE_3, EXPECTED_ADDR_LINE_WIDTH),
                ROW_1_STATE_CODE,
                FIXTURE_COUNTRY_CODE,
                padded(ROW_1_ZIP, EXPECTED_TEN_CHARACTER_WIDTH),
                padded(ROW_1_PHONE_1, EXPECTED_PHONE_WIDTH),
                padded(ROW_1_PHONE_2, EXPECTED_PHONE_WIDTH),
                seal(ROW_1_SSN),
                seal(ROW_1_GOVT_ISSUED_ID),
                ROW_1_DOB,
                ROW_1_EFT_ACCOUNT_ID,
                FIXTURE_PRIMARY_INDICATOR,
                ROW_1_FICO);
    }

    /**
     * Supplies every fixture ordinal, so that each production record is asserted as its own test.
     *
     * @return the fifty one-based ordinals of the shipped fixture
     */
    static Stream<Arguments> fixtureOrdinals() {
        List<Arguments> ordinals = new ArrayList<>(EXPECTED_FIXTURE_RECORDS);
        for (int ordinal = 1; ordinal <= EXPECTED_FIXTURE_RECORDS; ordinal++) {
            ordinals.add(Arguments.of(ordinal));
        }
        return ordinals.stream();
    }

    /**
     * Supplies the sixteen attributes whose absence the composer must refuse, each paired with the
     * legacy field it feeds and a mutator that clears it.
     *
     * <p>The two regulated attributes are absent from this list for different reasons. The national
     * identifier is legitimately nullable and has its own rendering, asserted separately. The
     * government-issued identifier is not nullable and the entity refuses absence before a record is
     * ever composed, so the composer's own guard for it is unreachable defence in depth.
     *
     * @return sixteen triples of property name, legacy field name and clearing mutator
     */
    static Stream<Arguments> nullableAttributes() {
        return Stream.of(
                Arguments.of("custId", FIELD_CUST_ID,
                        (Consumer<Customer>) customer -> customer.setCustId(null)),
                Arguments.of("firstName", FIELD_FIRST_NAME,
                        (Consumer<Customer>) customer -> customer.setFirstName(null)),
                Arguments.of("middleName", "CUST-MIDDLE-NAME",
                        (Consumer<Customer>) customer -> customer.setMiddleName(null)),
                Arguments.of("lastName", "CUST-LAST-NAME",
                        (Consumer<Customer>) customer -> customer.setLastName(null)),
                Arguments.of("addrLine1", "CUST-ADDR-LINE-1",
                        (Consumer<Customer>) customer -> customer.setAddrLine1(null)),
                Arguments.of("addrLine2", "CUST-ADDR-LINE-2",
                        (Consumer<Customer>) customer -> customer.setAddrLine2(null)),
                Arguments.of("addrLine3", "CUST-ADDR-LINE-3",
                        (Consumer<Customer>) customer -> customer.setAddrLine3(null)),
                Arguments.of("addrStateCd", "CUST-ADDR-STATE-CD",
                        (Consumer<Customer>) customer -> customer.setAddrStateCd(null)),
                Arguments.of("addrCountryCd", "CUST-ADDR-COUNTRY-CD",
                        (Consumer<Customer>) customer -> customer.setAddrCountryCd(null)),
                Arguments.of("addrZip", "CUST-ADDR-ZIP",
                        (Consumer<Customer>) customer -> customer.setAddrZip(null)),
                Arguments.of("phoneNum1", "CUST-PHONE-NUM-1",
                        (Consumer<Customer>) customer -> customer.setPhoneNum1(null)),
                Arguments.of("phoneNum2", "CUST-PHONE-NUM-2",
                        (Consumer<Customer>) customer -> customer.setPhoneNum2(null)),
                Arguments.of("custDob", FIELD_CUST_DOB_CVCUS01Y,
                        (Consumer<Customer>) customer -> customer.setCustDob(null)),
                Arguments.of("eftAccountId", "CUST-EFT-ACCOUNT-ID",
                        (Consumer<Customer>) customer -> customer.setEftAccountId(null)),
                Arguments.of("priCardHolderInd", "CUST-PRI-CARD-HOLDER-IND",
                        (Consumer<Customer>) customer -> customer.setPriCardHolderInd(null)),
                Arguments.of("ficoCreditScore", "CUST-FICO-CREDIT-SCORE",
                        (Consumer<Customer>) customer -> customer.setFicoCreditScore(null)));
    }

    /**
     * A sealing or revealing operation that records every value handed to it and then delegates to the
     * deterministic envelope, so that the mapper's use of the operation is observable.
     */
    private static final class RecordingOperator implements UnaryOperator<String> {

        /** Every value the mapper handed to this operation, in invocation order. */
        private final List<String> received = new ArrayList<>();

        /** The transformation to apply after recording. */
        private final UnaryOperator<String> delegate;

        /**
         * Creates a recording operation over a delegate.
         *
         * @param delegate the transformation to apply after recording; must not be {@code null}
         */
        private RecordingOperator(final UnaryOperator<String> delegate) {
            this.delegate = delegate;
        }

        /**
         * Creates a recording sealer that produces admissible envelopes.
         *
         * @return a recording operation that seals
         */
        static RecordingOperator sealing() {
            return new RecordingOperator(CustomerRecordMapperCoverageTest::seal);
        }

        /**
         * Creates a recording revealer that inverts {@link CustomerRecordMapperCoverageTest#seal(String)}.
         *
         * @return a recording operation that reveals
         */
        static RecordingOperator revealing() {
            return new RecordingOperator(CustomerRecordMapperCoverageTest::unseal);
        }

        @Override
        public String apply(final String value) {
            received.add(value);
            return delegate.apply(value);
        }

        /**
         * Returns the values handed to this operation, in invocation order.
         *
         * @return an unmodifiable view of the recorded values
         */
        List<String> received() {
            return List.copyOf(received);
        }
    }

    /**
     * Pins the published geometry of the layout, so that a change to any offset or width is a failure
     * here rather than a silent change of contract.
     */
    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        /** The record width is the copybook's, and the mapper publishes it. */
        @Test
        @DisplayName("publishes the 500-byte record width the copybook declares")
        void theRecordWidthIsFiveHundred() {
            assertThat(CustomerRecordMapper.RECORD_WIDTH)
                    .as("the customer record is 500 bytes in CVCUS01Y and its unhyphenated variant, "
                            + "and the fixture's 25,050 bytes over fifty records confirm it")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        /** The mapped prefix and the filler must tile the record exactly. */
        @Test
        @DisplayName("accounts for all 500 bytes as 332 mapped plus 168 filler")
        void theMappedPrefixAndFillerTileTheRecord() {
            assertThat(CustomerRecordMapper.MAPPED_DATA_WIDTH)
                    .as("the eighteen fields occupy the leading 332 bytes")
                    .isEqualTo(EXPECTED_MAPPED_DATA_WIDTH);
            assertThat(CustomerRecordMapper.FILLER_OFFSET)
                    .as("the filler begins where the mapped prefix ends, with no gap and no overlap")
                    .isEqualTo(EXPECTED_FILLER_OFFSET);
            assertThat(CustomerRecordMapper.FILLER_LENGTH)
                    .as("the filler carries the remainder of the record")
                    .isEqualTo(EXPECTED_FILLER_WIDTH);
            assertThat(CustomerRecordMapper.MAPPED_DATA_WIDTH + CustomerRecordMapper.FILLER_LENGTH)
                    .as("mapped prefix plus filler must be the whole record, or some byte of the "
                            + "layout is unaccounted for")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        /**
         * Every published offset compared against the copybook literal.
         *
         * <p>This is a load-bearing assertion for this layout in particular: the mapper derives each
         * offset from the one before it, so widening any single field silently shifts every field after
         * it. Comparing against literals transcribed from the copybook is what turns that shift into a
         * failure.
         */
        @Test
        @DisplayName("publishes offsets that agree with the copybook, field by field")
        void everyPublishedOffsetAgreesWithTheCopybook() {
            assertThat(CustomerRecordMapper.CUST_ID_OFFSET).as("CUST-ID offset")
                    .isEqualTo(EXPECTED_CUST_ID_OFFSET);
            assertThat(CustomerRecordMapper.FIRST_NAME_OFFSET).as("CUST-FIRST-NAME offset")
                    .isEqualTo(EXPECTED_FIRST_NAME_OFFSET);
            assertThat(CustomerRecordMapper.MIDDLE_NAME_OFFSET).as("CUST-MIDDLE-NAME offset")
                    .isEqualTo(EXPECTED_MIDDLE_NAME_OFFSET);
            assertThat(CustomerRecordMapper.LAST_NAME_OFFSET).as("CUST-LAST-NAME offset")
                    .isEqualTo(EXPECTED_LAST_NAME_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_LINE_1_OFFSET).as("CUST-ADDR-LINE-1 offset")
                    .isEqualTo(EXPECTED_ADDR_LINE_1_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_LINE_2_OFFSET).as("CUST-ADDR-LINE-2 offset")
                    .isEqualTo(EXPECTED_ADDR_LINE_2_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_LINE_3_OFFSET).as("CUST-ADDR-LINE-3 offset")
                    .isEqualTo(EXPECTED_ADDR_LINE_3_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_STATE_CD_OFFSET).as("CUST-ADDR-STATE-CD offset")
                    .isEqualTo(EXPECTED_ADDR_STATE_CD_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_COUNTRY_CD_OFFSET).as("CUST-ADDR-COUNTRY-CD offset")
                    .isEqualTo(EXPECTED_ADDR_COUNTRY_CD_OFFSET);
            assertThat(CustomerRecordMapper.ADDR_ZIP_OFFSET).as("CUST-ADDR-ZIP offset")
                    .isEqualTo(EXPECTED_ADDR_ZIP_OFFSET);
            assertThat(CustomerRecordMapper.PHONE_NUM_1_OFFSET).as("CUST-PHONE-NUM-1 offset")
                    .isEqualTo(EXPECTED_PHONE_NUM_1_OFFSET);
            assertThat(CustomerRecordMapper.PHONE_NUM_2_OFFSET).as("CUST-PHONE-NUM-2 offset")
                    .isEqualTo(EXPECTED_PHONE_NUM_2_OFFSET);
            assertThat(CustomerRecordMapper.CUST_SSN_OFFSET).as("CUST-SSN offset")
                    .isEqualTo(EXPECTED_CUST_SSN_OFFSET);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET).as("CUST-GOVT-ISSUED-ID offset")
                    .isEqualTo(EXPECTED_GOVT_ISSUED_ID_OFFSET);
            assertThat(CustomerRecordMapper.CUST_DOB_OFFSET).as("date-of-birth offset")
                    .isEqualTo(EXPECTED_CUST_DOB_OFFSET);
            assertThat(CustomerRecordMapper.EFT_ACCOUNT_ID_OFFSET).as("CUST-EFT-ACCOUNT-ID offset")
                    .isEqualTo(EXPECTED_EFT_ACCOUNT_ID_OFFSET);
            assertThat(CustomerRecordMapper.PRI_CARD_HOLDER_IND_OFFSET)
                    .as("CUST-PRI-CARD-HOLDER-IND offset")
                    .isEqualTo(EXPECTED_PRI_CARD_HOLDER_IND_OFFSET);
            assertThat(CustomerRecordMapper.FICO_CREDIT_SCORE_OFFSET)
                    .as("CUST-FICO-CREDIT-SCORE offset")
                    .isEqualTo(EXPECTED_FICO_CREDIT_SCORE_OFFSET);
        }

        /** Every published width compared against the copybook literal. */
        @Test
        @DisplayName("publishes widths that agree with the copybook, field by field")
        void everyPublishedWidthAgreesWithTheCopybook() {
            assertThat(CustomerRecordMapper.CUST_ID_LENGTH).as("CUST-ID width")
                    .isEqualTo(EXPECTED_CUST_ID_WIDTH);
            assertThat(CustomerRecordMapper.FIRST_NAME_LENGTH).as("CUST-FIRST-NAME width")
                    .isEqualTo(EXPECTED_NAME_WIDTH);
            assertThat(CustomerRecordMapper.MIDDLE_NAME_LENGTH).as("CUST-MIDDLE-NAME width")
                    .isEqualTo(EXPECTED_NAME_WIDTH);
            assertThat(CustomerRecordMapper.LAST_NAME_LENGTH).as("CUST-LAST-NAME width")
                    .isEqualTo(EXPECTED_NAME_WIDTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_1_LENGTH).as("CUST-ADDR-LINE-1 width")
                    .isEqualTo(EXPECTED_ADDR_LINE_WIDTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_2_LENGTH).as("CUST-ADDR-LINE-2 width")
                    .isEqualTo(EXPECTED_ADDR_LINE_WIDTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_3_LENGTH).as("CUST-ADDR-LINE-3 width")
                    .isEqualTo(EXPECTED_ADDR_LINE_WIDTH);
            assertThat(CustomerRecordMapper.ADDR_STATE_CD_LENGTH).as("CUST-ADDR-STATE-CD width")
                    .isEqualTo(EXPECTED_ADDR_STATE_CD_WIDTH);
            assertThat(CustomerRecordMapper.ADDR_COUNTRY_CD_LENGTH).as("CUST-ADDR-COUNTRY-CD width")
                    .isEqualTo(EXPECTED_ADDR_COUNTRY_CD_WIDTH);
            assertThat(CustomerRecordMapper.ADDR_ZIP_LENGTH).as("CUST-ADDR-ZIP width")
                    .isEqualTo(EXPECTED_TEN_CHARACTER_WIDTH);
            assertThat(CustomerRecordMapper.PHONE_NUM_1_LENGTH).as("CUST-PHONE-NUM-1 width")
                    .isEqualTo(EXPECTED_PHONE_WIDTH);
            assertThat(CustomerRecordMapper.PHONE_NUM_2_LENGTH).as("CUST-PHONE-NUM-2 width")
                    .isEqualTo(EXPECTED_PHONE_WIDTH);
            assertThat(CustomerRecordMapper.CUST_SSN_LENGTH).as("CUST-SSN width")
                    .isEqualTo(EXPECTED_CUST_SSN_WIDTH);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH).as("CUST-GOVT-ISSUED-ID width")
                    .isEqualTo(EXPECTED_GOVT_ISSUED_ID_WIDTH);
            assertThat(CustomerRecordMapper.CUST_DOB_LENGTH).as("date-of-birth width")
                    .isEqualTo(EXPECTED_TEN_CHARACTER_WIDTH);
            assertThat(CustomerRecordMapper.EFT_ACCOUNT_ID_LENGTH).as("CUST-EFT-ACCOUNT-ID width")
                    .isEqualTo(EXPECTED_TEN_CHARACTER_WIDTH);
            assertThat(CustomerRecordMapper.PRI_CARD_HOLDER_IND_LENGTH)
                    .as("CUST-PRI-CARD-HOLDER-IND width")
                    .isEqualTo(EXPECTED_PRI_CARD_HOLDER_IND_WIDTH);
            assertThat(CustomerRecordMapper.FICO_CREDIT_SCORE_LENGTH)
                    .as("CUST-FICO-CREDIT-SCORE width")
                    .isEqualTo(EXPECTED_FICO_CREDIT_SCORE_WIDTH);
        }

        /**
         * The eighteen fields must tile the mapped prefix with no gap and no overlap, which the
         * offset and width assertions above cannot show on their own.
         *
         * @param offset the declared zero-based offset of a field
         * @param width  the declared width of that field
         */
        @ParameterizedTest(name = "[{0}, {0}+{1}) lies inside the mapped prefix")
        @CsvSource({
            "0,9", "9,25", "34,25", "59,25", "84,50", "134,50", "184,50", "234,2", "236,3",
            "239,10", "249,15", "264,15", "279,9", "288,20", "308,10", "318,10", "328,1", "329,3",
        })
        @DisplayName("keeps every field inside the mapped prefix")
        void everyFieldLiesInsideTheMappedPrefix(final int offset, final int width) {
            assertThat(offset + width)
                    .as("a field ending past byte %d would overlap the filler",
                            EXPECTED_MAPPED_DATA_WIDTH)
                    .isLessThanOrEqualTo(EXPECTED_MAPPED_DATA_WIDTH);
        }

        /**
         * The eighteen widths must sum to the mapped prefix exactly, which is the arithmetic that
         * proves the tiling leaves no unclaimed byte between fields.
         */
        @Test
        @DisplayName("sums the eighteen field widths to the mapped prefix exactly")
        void theEighteenWidthsSumToTheMappedPrefix() {
            int summed = EXPECTED_CUST_ID_WIDTH
                    + EXPECTED_NAME_WIDTH * 3
                    + EXPECTED_ADDR_LINE_WIDTH * 3
                    + EXPECTED_ADDR_STATE_CD_WIDTH
                    + EXPECTED_ADDR_COUNTRY_CD_WIDTH
                    + EXPECTED_TEN_CHARACTER_WIDTH
                    + EXPECTED_PHONE_WIDTH * 2
                    + EXPECTED_CUST_SSN_WIDTH
                    + EXPECTED_GOVT_ISSUED_ID_WIDTH
                    + EXPECTED_TEN_CHARACTER_WIDTH
                    + EXPECTED_TEN_CHARACTER_WIDTH
                    + EXPECTED_PRI_CARD_HOLDER_IND_WIDTH
                    + EXPECTED_FICO_CREDIT_SCORE_WIDTH;

            assertThat(summed)
                    .as("eighteen fields tiling [0, %d) leaves no byte unclaimed and claims none twice",
                            EXPECTED_MAPPED_DATA_WIDTH)
                    .isEqualTo(EXPECTED_MAPPED_DATA_WIDTH);
        }

        /**
         * The two images this class assembles from declared widths must both be exactly one record
         * long, which is an independent check that the widths above are internally consistent.
         */
        @Test
        @DisplayName("assembles both test images to exactly one record width")
        void theAssembledImagesAreExactlyOneRecordWide() {
            assertThat(canonicalImage())
                    .as("the canonical image is assembled from declared widths, so its length is a "
                            + "direct check that the widths sum to the record width")
                    .hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(markedImage())
                    .as("the marked image is assembled the same way and must agree")
                    .hasSize(EXPECTED_RECORD_WIDTH);
        }

        /** Both copybook spellings of the date field are published, because both estates exist. */
        @Test
        @DisplayName("names both copybook spellings of the single date-of-birth slice")
        void bothDateSpellingsArePublished() {
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CVCUS01Y)
                    .as("the hyphenated copybook spells the field this way")
                    .isEqualTo(FIELD_CUST_DOB_CVCUS01Y);
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CUSTREC)
                    .as("the unhyphenated copybook spells the same ten bytes this way")
                    .isEqualTo(FIELD_CUST_DOB_CUSTREC);
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CVCUS01Y)
                    .as("the two spellings are genuinely different names for one slice, which is why "
                            + "both are published rather than one being preferred")
                    .isNotEqualTo(CustomerRecordMapper.CUST_DOB_FIELD_CUSTREC);
        }

        /** The artefact name is what every diagnostic leads with, so it is part of the contract. */
        @Test
        @DisplayName("names both copybooks in the artefact used by every diagnostic")
        void theArtefactNamesBothCopybooks() {
            assertThat(CustomerRecordMapper.ARTEFACT)
                    .as("a diagnostic that named only one copybook would send a reader to the wrong "
                            + "layout half the time")
                    .isEqualTo(EXPECTED_ARTEFACT)
                    .contains("CVCUS01Y")
                    .contains("CUSTREC");
        }
    }

    /**
     * Decoding a record image into an entity: each field read from its own offset, at its own width,
     * untrimmed.
     */
    @Nested
    @DisplayName("decoding")
    class Decoding {

        /**
         * Reads a marked image in which no two fields share content, so a mis-declared offset cannot
         * be masked by a neighbour.
         */
        @Test
        @DisplayName("reads every field from its own offset at its own width")
        void everyFieldIsReadFromItsOwnOffset() {
            Customer mapped = CustomerRecordMapper.fromRecord(markedImage(),
                    CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getCustId()).as("CUST-ID").isEqualTo("111111111");
            assertThat(mapped.getFirstName()).as("CUST-FIRST-NAME")
                    .isEqualTo(run('A', EXPECTED_NAME_WIDTH));
            assertThat(mapped.getMiddleName()).as("CUST-MIDDLE-NAME")
                    .isEqualTo(run('B', EXPECTED_NAME_WIDTH));
            assertThat(mapped.getLastName()).as("CUST-LAST-NAME")
                    .isEqualTo(run('C', EXPECTED_NAME_WIDTH));
            assertThat(mapped.getAddrLine1()).as("CUST-ADDR-LINE-1")
                    .isEqualTo(run('D', EXPECTED_ADDR_LINE_WIDTH));
            assertThat(mapped.getAddrLine2()).as("CUST-ADDR-LINE-2")
                    .isEqualTo(run('E', EXPECTED_ADDR_LINE_WIDTH));
            assertThat(mapped.getAddrLine3()).as("CUST-ADDR-LINE-3")
                    .isEqualTo(run('F', EXPECTED_ADDR_LINE_WIDTH));
            assertThat(mapped.getAddrStateCd()).as("CUST-ADDR-STATE-CD").isEqualTo("GH");
            assertThat(mapped.getAddrCountryCd()).as("CUST-ADDR-COUNTRY-CD").isEqualTo("IJK");
            assertThat(mapped.getAddrZip()).as("CUST-ADDR-ZIP")
                    .isEqualTo(run('L', EXPECTED_TEN_CHARACTER_WIDTH));
            assertThat(mapped.getPhoneNum1()).as("CUST-PHONE-NUM-1")
                    .isEqualTo(run('M', EXPECTED_PHONE_WIDTH));
            assertThat(mapped.getPhoneNum2()).as("CUST-PHONE-NUM-2")
                    .isEqualTo(run('N', EXPECTED_PHONE_WIDTH));
            assertThat(unseal(mapped.getCustSsn())).as("CUST-SSN, through the envelope")
                    .isEqualTo("222222222");
            assertThat(unseal(mapped.getGovtIssuedId())).as("CUST-GOVT-ISSUED-ID, through the envelope")
                    .isEqualTo(run('P', EXPECTED_GOVT_ISSUED_ID_WIDTH));
            assertThat(mapped.getCustDob()).as("the single date slice")
                    .isEqualTo(run('Q', EXPECTED_TEN_CHARACTER_WIDTH));
            assertThat(mapped.getEftAccountId()).as("CUST-EFT-ACCOUNT-ID")
                    .isEqualTo(run('R', EXPECTED_TEN_CHARACTER_WIDTH));
            assertThat(mapped.getPriCardHolderInd()).as("CUST-PRI-CARD-HOLDER-IND").isEqualTo("S");
            assertThat(mapped.getFicoCreditScore()).as("CUST-FICO-CREDIT-SCORE").isEqualTo("333");
        }

        /** The transcribed first fixture row, decoded from an independently assembled image. */
        @Test
        @DisplayName("decodes the canonical record to its transcribed values")
        void theCanonicalRecordDecodesToItsTranscribedValues() {
            Customer mapped = CustomerRecordMapper.fromRecord(canonicalImage(),
                    CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(mapped.getFirstName()).isEqualTo(padded(ROW_1_FIRST_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getMiddleName()).isEqualTo(padded(ROW_1_MIDDLE_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getLastName()).isEqualTo(padded(ROW_1_LAST_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getAddrStateCd()).isEqualTo(ROW_1_STATE_CODE);
            assertThat(mapped.getAddrCountryCd()).isEqualTo(FIXTURE_COUNTRY_CODE);
            assertThat(mapped.getCustDob()).isEqualTo(ROW_1_DOB);
            assertThat(mapped.getEftAccountId()).isEqualTo(ROW_1_EFT_ACCOUNT_ID);
            assertThat(mapped.getPriCardHolderInd()).isEqualTo(FIXTURE_PRIMARY_INDICATOR);
            assertThat(mapped.getFicoCreditScore()).isEqualTo(ROW_1_FICO);
            assertThat(unseal(mapped.getCustSsn())).isEqualTo(ROW_1_SSN);
            assertThat(unseal(mapped.getGovtIssuedId())).isEqualTo(ROW_1_GOVT_ISSUED_ID);
        }

        /**
         * Trailing spaces are content, not noise: the record image is the contract, and a mapper that
         * trimmed would make the encode side unable to reproduce the image it read.
         */
        @Test
        @DisplayName("keeps every field untrimmed, trailing spaces included")
        void fieldsAreReadUntrimmed() {
            Customer mapped = CustomerRecordMapper.fromRecord(canonicalImage(),
                    CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getFirstName())
                    .as("the given name occupies its full declared width, padding included")
                    .hasSize(EXPECTED_NAME_WIDTH)
                    .endsWith(" ");
            assertThat(mapped.getAddrZip())
                    .as("a five-digit postal code in a ten-byte character field keeps its five "
                            + "trailing spaces, because the field is character data and not a number")
                    .hasSize(EXPECTED_TEN_CHARACTER_WIDTH)
                    .isEqualTo(ROW_1_ZIP + run(' ', 5));
            assertThat(mapped.getPhoneNum1())
                    .as("the telephone number keeps its two trailing spaces")
                    .hasSize(EXPECTED_PHONE_WIDTH)
                    .isEqualTo(ROW_1_PHONE_1 + "  ");
        }

        /**
         * The unhyphenated copybook names the same ten bytes, so there must be exactly one attribute
         * carrying them: two attributes would double-count the slice.
         */
        @Test
        @DisplayName("serves both copybook date spellings from one slice and one attribute")
        void oneSliceServesBothDateSpellings() {
            String image = canonicalImage();

            assertThat(image.substring(EXPECTED_CUST_DOB_OFFSET,
                    EXPECTED_CUST_DOB_OFFSET + EXPECTED_TEN_CHARACTER_WIDTH))
                    .as("the ten bytes both copybooks name")
                    .isEqualTo(ROW_1_DOB);
            assertThat(CustomerRecordMapper.fromRecord(image, CustomerRecordMapperCoverageTest::seal)
                    .getCustDob())
                    .as("one attribute carries those ten bytes, whichever copybook the caller thinks "
                            + "in, because the offset and the width are the same in both")
                    .isEqualTo(ROW_1_DOB);
        }

        /** Decoding is a pure function of the image and the operation, so it must be repeatable. */
        @Test
        @DisplayName("decodes the same image to equal values every time")
        void decodingIsRepeatable() {
            String image = canonicalImage();

            Customer first = CustomerRecordMapper.fromRecord(image, CustomerRecordMapperCoverageTest::seal);
            Customer second = CustomerRecordMapper.fromRecord(image, CustomerRecordMapperCoverageTest::seal);

            assertThat(second.getCustId()).isEqualTo(first.getCustId());
            assertThat(second.getFirstName()).isEqualTo(first.getFirstName());
            assertThat(unseal(second.getCustSsn()))
                    .as("the deterministic double seals to the same envelope, so the cleartext behind "
                            + "it must agree")
                    .isEqualTo(unseal(first.getCustSsn()));
        }

        /** The byte-array overload must agree with the string overload on the same bytes. */
        @Test
        @DisplayName("agrees between the string and byte-array overloads")
        void bothWholeRecordOverloadsAgree() {
            String image = canonicalImage();

            Customer fromText = CustomerRecordMapper.fromRecord(image, CustomerRecordMapperCoverageTest::seal);
            Customer fromBytes = CustomerRecordMapper.fromRecord(
                    image.getBytes(StandardCharsets.US_ASCII), CustomerRecordMapperCoverageTest::seal);

            assertThat(fromBytes.getCustId()).isEqualTo(fromText.getCustId());
            assertThat(fromBytes.getFirstName()).isEqualTo(fromText.getFirstName());
            assertThat(fromBytes.getFicoCreditScore()).isEqualTo(fromText.getFicoCreditScore());
            assertThat(unseal(fromBytes.getGovtIssuedId()))
                    .isEqualTo(unseal(fromText.getGovtIssuedId()));
        }
    }

    /**
     * The sealing operation's contract on the way in: which slices reach it, in what form, how often,
     * and what happens when it misbehaves.
     */
    @Nested
    @DisplayName("regulated field sealing")
    class RegulatedFieldSealing {

        /**
         * The two regulated slices, and only those, must reach the operation. A mapper that sealed a
         * name would leak nothing but would corrupt the record; one that failed to seal an identifier
         * would put cleartext behind the persistence boundary.
         */
        @Test
        @DisplayName("hands exactly the two regulated slices to the sealing operation")
        void onlyTheTwoRegulatedSlicesAreSealed() {
            RecordingOperator sealer = RecordingOperator.sealing();

            CustomerRecordMapper.fromRecord(canonicalImage(), sealer);

            assertThat(sealer.received())
                    .as("the national identifier and the government-issued identifier are the only "
                            + "regulated fields in this layout, so the operation must see exactly two "
                            + "values and they must be those two, in record order")
                    .containsExactly(ROW_1_SSN, ROW_1_GOVT_ISSUED_ID);
        }

        /**
         * The operation receives the raw slice, exactly as it sits in the record, because a trimmed or
         * reformatted value could not be sealed and later reproduced byte for byte.
         */
        @Test
        @DisplayName("hands the raw slice to the sealing operation, at its declared width")
        void theSealingOperationReceivesTheRawSlice() {
            RecordingOperator sealer = RecordingOperator.sealing();

            CustomerRecordMapper.fromRecord(markedImage(), sealer);

            assertThat(sealer.received().get(0))
                    .as("the national identifier arrives at its full declared width")
                    .hasSize(EXPECTED_CUST_SSN_WIDTH)
                    .isEqualTo("222222222");
            assertThat(sealer.received().get(1))
                    .as("the government-issued identifier arrives at its full declared width")
                    .hasSize(EXPECTED_GOVT_ISSUED_ID_WIDTH)
                    .isEqualTo(run('P', EXPECTED_GOVT_ISSUED_ID_WIDTH));
        }

        /** No slice is sealed twice, which would double the work and could double a nonce. */
        @Test
        @DisplayName("seals each regulated slice exactly once")
        void eachRegulatedSliceIsSealedOnce() {
            RecordingOperator sealer = RecordingOperator.sealing();

            CustomerRecordMapper.fromRecord(canonicalImage(), sealer);

            assertThat(sealer.received())
                    .as("two regulated fields, two invocations, no repetition")
                    .hasSize(2)
                    .doesNotHaveDuplicates();
        }

        /** The sealed value, not the cleartext, is what the entity ends up holding. */
        @Test
        @DisplayName("stores the sealed envelope rather than the cleartext")
        void theEntityHoldsTheSealedEnvelope() {
            Customer mapped = CustomerRecordMapper.fromRecord(canonicalImage(),
                    CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getCustSsn())
                    .as("the stored national identifier must carry the envelope marker")
                    .startsWith(ENVELOPE_PREFIX)
                    .isNotEqualTo(ROW_1_SSN)
                    .doesNotContain(ROW_1_SSN);
            assertThat(mapped.getGovtIssuedId())
                    .as("the stored government-issued identifier must carry the envelope marker")
                    .startsWith(ENVELOPE_PREFIX)
                    .isNotEqualTo(ROW_1_GOVT_ISSUED_ID)
                    .doesNotContain(ROW_1_GOVT_ISSUED_ID);
        }

        /**
         * The double must produce envelopes the shipped module recognises, otherwise every assertion
         * that rests on it would be testing a shape the production entity would never see.
         */
        @Test
        @DisplayName("produces envelopes the module's own shape test admits")
        void theDoubleProducesEnvelopesTheModuleRecognises() {
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(seal(ROW_1_SSN)))
                    .as("a sealing double whose output the module would reject would make every "
                            + "assertion in this class vacuous")
                    .isTrue();
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(seal(ROW_1_GOVT_ISSUED_ID)))
                    .isTrue();
            assertThat(unseal(seal(run(' ', EXPECTED_CUST_SSN_WIDTH))))
                    .as("the double must invert exactly even for an all-space slice, which a "
                            + "length-free scheme could not recover")
                    .isEqualTo(run(' ', EXPECTED_CUST_SSN_WIDTH));
        }

        /**
         * A sealing operation that returns nothing is a broken collaborator, and the mapper names the
         * operation and the field rather than letting the entity complain about its own attribute
         * several frames away.
         */
        @Test
        @DisplayName("refuses a sealing operation that returns nothing, naming the field")
        void aSealingOperationThatReturnsNothingIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(canonicalImage(), value -> null))
                    .withMessage("the sealing operation returned no value for " + EXPECTED_ARTEFACT
                            + " field '" + FIELD_CUST_SSN + "'; a regulated value must be sealed into "
                            + "the module's protected-value envelope, never dropped");
        }

        /**
         * A sealing operation that passes cleartext through is the failure this indirection exists to
         * prevent, and the entity refuses it.
         *
         * @param passthroughDescription what the misbehaving operation returns, named for the report
         * @param returned               the value the operation returns
         */
        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "cleartext with no envelope marker,020973888",
            "an unmarked but plausible token,ENC0:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        })
        @DisplayName("refuses a sealing operation that does not seal")
        void aSealingOperationThatDoesNotSealIsRefused(final String passthroughDescription,
                final String returned) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("returning %s would put a regulated value behind the persistence boundary "
                            + "unprotected", passthroughDescription)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(canonicalImage(),
                            value -> returned))
                    .withMessageContaining("custSsn")
                    .withMessageContaining("must be an encrypted value carrying the " + ENVELOPE_PREFIX
                            + " envelope");
        }

        /** A marked value whose body is not Base64 is refused, and the refusal does not echo it. */
        @Test
        @DisplayName("refuses a marked value whose body is not Base64 without echoing it")
        void aMarkedValueWithABadBodyIsRefused() {
            String hostileBody = ENVELOPE_PREFIX + "!!!!not-base-64!!!!";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(canonicalImage(),
                            value -> hostileBody))
                    .withMessageContaining("is not valid Base64")
                    .withMessageNotContaining(hostileBody)
                    .withMessageNotContaining(ROW_1_SSN);
        }

        /** A marked value too short to be an authenticated ciphertext is refused. */
        @Test
        @DisplayName("refuses a marked value too short to be an authenticated ciphertext")
        void aMarkedValueTooShortIsRefused() {
            String shortEnvelope = ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(new byte[MINIMUM_ENVELOPE_BODY_BYTES - 1]);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a body shorter than an initialisation vector plus a tag cannot be a sealed "
                            + "value, whatever marker it carries")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(canonicalImage(),
                            value -> shortEnvelope))
                    .withMessageContaining("too short to be an authenticated ciphertext");
        }

        /**
         * Decoding always seals, even when the slice is blank. The asymmetry with the encode side,
         * which tolerates an absent identifier, is deliberate and is pinned here so it cannot drift:
         * the record image has no way to say "absent", only "blank".
         */
        @Test
        @DisplayName("seals a blank national identifier slice rather than treating it as absent")
        void aBlankRegulatedSliceIsStillSealed() {
            String blankSsnImage = canonicalImage().substring(0, EXPECTED_CUST_SSN_OFFSET)
                    + run(' ', EXPECTED_CUST_SSN_WIDTH)
                    + canonicalImage().substring(EXPECTED_CUST_SSN_OFFSET + EXPECTED_CUST_SSN_WIDTH);
            RecordingOperator sealer = RecordingOperator.sealing();

            Customer mapped = CustomerRecordMapper.fromRecord(blankSsnImage, sealer);

            assertThat(sealer.received().get(0))
                    .as("the blank slice is handed to the operation exactly as it sits in the record")
                    .isEqualTo(run(' ', EXPECTED_CUST_SSN_WIDTH));
            assertThat(mapped.getCustSsn())
                    .as("decoding never produces an absent identifier: it seals whatever the record "
                            + "holds, because a blank field and an absent value are different things")
                    .isNotNull()
                    .startsWith(ENVELOPE_PREFIX);
        }
    }

    /**
     * The revealing operation's contract on the way out: when it is consulted, when it is deliberately
     * not, and what happens when it misbehaves.
     */
    @Nested
    @DisplayName("regulated field revealing")
    class RegulatedFieldRevealing {

        /** Both envelopes are opened when both are held, in record order. */
        @Test
        @DisplayName("consults the revealing operation for both held envelopes, in record order")
        void bothHeldEnvelopesAreRevealed() {
            RecordingOperator revealer = RecordingOperator.revealing();

            CustomerRecordMapper.toRecord(canonicalEntity(), revealer);

            assertThat(revealer.received())
                    .as("the national identifier precedes the government-issued identifier in the "
                            + "record, and the composer places fields in record order")
                    .containsExactly(seal(ROW_1_SSN), seal(ROW_1_GOVT_ISSUED_ID));
        }

        /**
         * When no national identifier is held there is no envelope to open, so the operation must not
         * be asked to open one. Consulting it would force every caller to handle a null argument.
         */
        @Test
        @DisplayName("does not consult the revealing operation for an absent national identifier")
        void anAbsentIdentifierIsNotRevealed() {
            Customer withoutIdentifier = canonicalEntity();
            withoutIdentifier.setCustSsn(null);
            RecordingOperator revealer = RecordingOperator.revealing();

            CustomerRecordMapper.toRecord(withoutIdentifier, revealer);

            assertThat(revealer.received())
                    .as("only the government-issued identifier is held, so the operation is consulted "
                            + "exactly once and never with a null envelope")
                    .containsExactly(seal(ROW_1_GOVT_ISSUED_ID));
        }

        /** A revealing operation that returns nothing leaves a field unwritten, which is refused. */
        @Test
        @DisplayName("refuses a revealing operation that returns nothing, naming the field")
        void aRevealingOperationThatReturnsNothingIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(canonicalEntity(), value -> null))
                    .withMessage("the revealing operation returned no value for " + EXPECTED_ARTEFACT
                            + " field '" + FIELD_CUST_SSN + "'; a fixed-width field cannot be left "
                            + "unwritten, and a record is never partially composed");
        }

        /**
         * A revealed value wider than its field cannot be placed, and truncating it would leave the
         * record the right width and the wrong content.
         */
        @Test
        @DisplayName("refuses a revealed value wider than its field")
        void anOverWideRevealedValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(canonicalEntity(),
                            value -> "0123456789"))
                    .withMessageContaining("value does not fit " + EXPECTED_ARTEFACT
                            + " record image field '" + FIELD_CUST_SSN + "'")
                    .withMessageContaining("field width is " + EXPECTED_CUST_SSN_WIDTH
                            + " encoded bytes but the value is 10 encoded bytes")
                    .withMessageContaining("never truncated to fit");
        }

        /**
         * Revealing is what makes the round trip possible, so the revealed cleartext must be exactly
         * the slice that was sealed - not a normalised or re-padded form of it.
         */
        @Test
        @DisplayName("places exactly the revealed cleartext back into the record")
        void theRevealedCleartextIsPlacedVerbatim() {
            String image = CustomerRecordMapper.toRecord(canonicalEntity(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_CUST_SSN_OFFSET,
                    EXPECTED_CUST_SSN_OFFSET + EXPECTED_CUST_SSN_WIDTH))
                    .as("the national identifier window holds the revealed digits and nothing else")
                    .isEqualTo(ROW_1_SSN);
            assertThat(image.substring(EXPECTED_GOVT_ISSUED_ID_OFFSET,
                    EXPECTED_GOVT_ISSUED_ID_OFFSET + EXPECTED_GOVT_ISSUED_ID_WIDTH))
                    .as("the government-issued identifier window holds the revealed characters")
                    .isEqualTo(ROW_1_GOVT_ISSUED_ID);
        }
    }

    /**
     * The rendering an absent regulated identifier gets, and where the mandate on one of them lives.
     *
     * <p>This layout tolerates absence in both regulated identifiers and renders it as an all-space
     * field of the declared width. That is the layout's most easily broken property. Zero-filling an
     * absent national identifier would produce nine zeros, which reads back as a real nine-digit value
     * that was never held; and because every seeded row in the shipped database stores no national
     * identifier, that fabrication would be the common case rather than an edge case.
     *
     * <p>The two identifiers differ at the schema rather than here. The national identifier's column
     * permits absence and its seeded value is absent; the government-issued identifier's column is
     * mandatory and its seeded value is a pre-sealed envelope. Neither difference reaches this layer,
     * and the last test in this group is what records why: a layout translator composes a record from
     * whatever an in-memory instance holds, and whether that instance may be persisted is the column's
     * question and not its.
     */
    @Nested
    @DisplayName("absent regulated identifiers")
    class AbsentNationalIdentifier {

        /** Absence renders as spaces, which is COBOL's rendering of an unset character field. */
        @Test
        @DisplayName("renders an absent national identifier as nine spaces, not nine zeros")
        void absenceRendersAsSpaces() {
            Customer withoutIdentifier = canonicalEntity();
            withoutIdentifier.setCustSsn(null);

            String image = CustomerRecordMapper.toRecord(withoutIdentifier,
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_CUST_SSN_OFFSET,
                    EXPECTED_CUST_SSN_OFFSET + EXPECTED_CUST_SSN_WIDTH))
                    .as("nine spaces say 'unset'; nine zeros would say 'the value is 000000000', "
                            + "which is a different and false claim")
                    .isEqualTo(run(' ', EXPECTED_CUST_SSN_WIDTH))
                    .isNotEqualTo(run('0', EXPECTED_CUST_SSN_WIDTH));
        }

        /** Absence must not disturb any other byte of the record. */
        @Test
        @DisplayName("leaves every byte outside the identifier window untouched")
        void absenceDisturbsNoOtherByte() {
            Customer withoutIdentifier = canonicalEntity();
            withoutIdentifier.setCustSsn(null);
            String expected = canonicalImage();

            String image = CustomerRecordMapper.toRecord(withoutIdentifier,
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image).hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(image.substring(0, EXPECTED_CUST_SSN_OFFSET))
                    .as("everything before the identifier window is unchanged")
                    .isEqualTo(expected.substring(0, EXPECTED_CUST_SSN_OFFSET));
            assertThat(image.substring(EXPECTED_CUST_SSN_OFFSET + EXPECTED_CUST_SSN_WIDTH))
                    .as("everything after the identifier window is unchanged")
                    .isEqualTo(expected.substring(EXPECTED_CUST_SSN_OFFSET + EXPECTED_CUST_SSN_WIDTH));
        }

        /**
         * A record whose identifier window is blank survives a decode and a re-encode unchanged, which
         * is what makes the blank window safe to hold in a seeded dataset.
         */
        @Test
        @DisplayName("round-trips a blank identifier window unchanged")
        void aBlankIdentifierWindowRoundTrips() {
            String blankSsnImage = canonicalImage().substring(0, EXPECTED_CUST_SSN_OFFSET)
                    + run(' ', EXPECTED_CUST_SSN_WIDTH)
                    + canonicalImage().substring(EXPECTED_CUST_SSN_OFFSET + EXPECTED_CUST_SSN_WIDTH);

            String reEncoded = CustomerRecordMapper.toRecord(
                    CustomerRecordMapper.fromRecord(blankSsnImage, CustomerRecordMapperCoverageTest::seal),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(reEncoded)
                    .as("a blank slice is sealed on the way in and revealed on the way out, and nine "
                            + "spaces placed into a nine-byte field are nine spaces however the field "
                            + "is justified")
                    .isEqualTo(blankSsnImage);
        }

        /**
         * Absence of the government-issued identifier is refused by the column, not by this layer.
         *
         * <p>The two regulated identifiers are treated alike by the entity and by the mapper and
         * differently by the schema. {@code cust_ssn} is the schema's one intentionally nullable
         * column; {@code govt_issued_id} is {@code NOT NULL}, and the reference seed carries a
         * pre-sealed envelope for every one of the fifty rows so that a mandatory protected column can
         * be seeded without any cleartext reaching a checked-in artifact.
         *
         * <p>Neither the entity nor the mapper restates that mandatory column, and that is deliberate
         * rather than a gap. An in-memory customer assembled at a boundary may legitimately not yet
         * carry a protected value - the entity accepts absence and refuses only cleartext, which is the
         * guard that genuinely belongs at that boundary - and a fixed-width record has to be composable
         * from whatever the instance holds. Refusing absence in the setter would make such an instance
         * unusable before it had ever been offered to the column that actually requires a value, and
         * would put a persistence constraint into a layout translator that has no business restating
         * one. The two assertions below therefore pin where the mandate does live: on the column
         * mapping, which is what a schema-validating provider and the migration both read.
         *
         * <p>What the entity does refuse is cleartext, in either identifier. That refusal is asserted
         * here as well, because it is the half of the contract this layer owns and the half that would
         * be silently lost if the guard were ever relaxed to a plain null check.
         */
        @Test
        @DisplayName("leaves an absent government-issued identifier to the mandatory column and "
                + "refuses cleartext at the entity boundary")
        void theGovernmentIssuedIdentifierIsMandatoryAtTheColumnRatherThanTheSetter()
                throws NoSuchFieldException {
            Customer customer = canonicalEntity();

            customer.setGovtIssuedId(null);
            assertThat(customer.getGovtIssuedId())
                    .as("absence is representable in memory, which is what makes the twenty-space "
                            + "rendering asserted above reachable at all")
                    .isNull();

            Column mapping = Customer.class.getDeclaredField("govtIssuedId")
                    .getAnnotation(Column.class);
            assertThat(mapping).isNotNull();
            assertThat(mapping.name()).isEqualTo("govt_issued_id");
            assertThat(mapping.nullable())
                    .as("the mandate lives on the column, so persisting an absent identifier is "
                            + "refused at the persistence boundary rather than in this layer")
                    .isFalse();
            assertThat(Customer.class.getDeclaredField("custSsn").getAnnotation(Column.class)
                            .nullable())
                    .as("and the national identifier's column is the one that does permit absence, "
                            + "which is the difference between the two seeds rather than a difference "
                            + "in kind between the two fields")
                    .isTrue();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("what this boundary does refuse is cleartext, and the message names the "
                            + "attribute rather than the offending value")
                    .isThrownBy(() -> customer.setGovtIssuedId("00000000000049368437"))
                    .withMessageContaining("govtIssuedId")
                    .withMessageContaining(ENVELOPE_PREFIX)
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain("00000000000049368437"));
        }
    }

    /**
     * How each field is justified when a value narrower than its field is placed.
     *
     * <p>Three fields are unsigned external decimal and are zero-filled from the left; the other
     * fifteen are character data and are space-padded on the right. Two of those fifteen - the postal
     * code and the funds-transfer account identifier - hold values that look numeric, and the copybook
     * nonetheless declares them as character data. Getting either of those two wrong would produce a
     * record of the right width whose postal code read {@code 0000012546}.
     */
    @Nested
    @DisplayName("justification policy")
    class JustificationPolicy {

        /**
         * Builds a customer whose narrow values expose justification, with both regulated attributes
         * sealed so the entity will admit them.
         *
         * @return a customer holding values narrower than their fields
         */
        private Customer narrowValued() {
            return new Customer(
                    "1",
                    "Ann",
                    "B",
                    "Cee",
                    "L1",
                    "L2",
                    "L3",
                    "NC",
                    "USA",
                    ROW_1_ZIP,
                    ROW_1_PHONE_1,
                    ROW_1_PHONE_2,
                    seal(ROW_1_SSN),
                    seal("49368437"),
                    ROW_1_DOB,
                    "53581756",
                    "Y",
                    "7");
        }

        /** The identifier is unsigned external decimal, so it is right-justified and zero-filled. */
        @Test
        @DisplayName("zero-fills the customer identifier from the left")
        void theIdentifierIsZeroFilled() {
            String image = CustomerRecordMapper.toRecord(narrowValued(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_CUST_ID_OFFSET,
                    EXPECTED_CUST_ID_OFFSET + EXPECTED_CUST_ID_WIDTH))
                    .as("an unsigned external decimal field carries its value flush against the "
                            + "trailing edge, with leading zeros")
                    .isEqualTo(zeroFilled("1", EXPECTED_CUST_ID_WIDTH))
                    .isEqualTo("000000001");
        }

        /** The credit score is unsigned external decimal for the same reason. */
        @Test
        @DisplayName("zero-fills the credit score from the left")
        void theCreditScoreIsZeroFilled() {
            String image = CustomerRecordMapper.toRecord(narrowValued(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_FICO_CREDIT_SCORE_OFFSET,
                    EXPECTED_FICO_CREDIT_SCORE_OFFSET + EXPECTED_FICO_CREDIT_SCORE_WIDTH))
                    .as("a one-digit score in a three-digit field is 007, not '7  '")
                    .isEqualTo("007");
        }

        /** The national identifier is unsigned external decimal when it is held. */
        @Test
        @DisplayName("zero-fills a held national identifier from the left")
        void aHeldNationalIdentifierIsZeroFilled() {
            Customer customer = narrowValued();
            customer.setCustSsn(seal("973888"));

            String image = CustomerRecordMapper.toRecord(customer, CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_CUST_SSN_OFFSET,
                    EXPECTED_CUST_SSN_OFFSET + EXPECTED_CUST_SSN_WIDTH))
                    .as("a six-digit identifier in a nine-digit field is zero-filled, which is the "
                            + "one place zero-filling is correct because a value is genuinely held")
                    .isEqualTo("000973888");
        }

        /**
         * The character fields are left-justified and space-padded, including the two that look
         * numeric.
         *
         * @param fieldDescription the field being checked, named for the report
         * @param offset           the declared offset of the field
         * @param width            the declared width of the field
         * @param narrowValue      the value the entity holds, narrower than the field
         */
        @ParameterizedTest(name = "{0} is left-justified and space-padded")
        @CsvSource({
            "CUST-FIRST-NAME,9,25,Ann",
            "CUST-MIDDLE-NAME,34,25,B",
            "CUST-LAST-NAME,59,25,Cee",
            "CUST-ADDR-LINE-1,84,50,L1",
            "CUST-ADDR-LINE-2,134,50,L2",
            "CUST-ADDR-LINE-3,184,50,L3",
            "CUST-ADDR-ZIP,239,10,12546",
            "CUST-EFT-ACCOUNT-ID,318,10,53581756",
        })
        @DisplayName("space-pads every character field on the right")
        void characterFieldsAreSpacePadded(final String fieldDescription, final int offset,
                final int width, final String narrowValue) {
            String image = CustomerRecordMapper.toRecord(narrowValued(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(offset, offset + width))
                    .as("%s is character data, so a narrow value keeps its position and the field is "
                            + "padded on the right", fieldDescription)
                    .isEqualTo(padded(narrowValue, width));
        }

        /**
         * Stated separately because it is the specific confusion the copybook invites: the postal code
         * and the transfer identifier look numeric and are not.
         */
        @Test
        @DisplayName("treats the postal code and transfer identifier as character data")
        void theNumericLookingCharacterFieldsAreNotZeroFilled() {
            String image = CustomerRecordMapper.toRecord(narrowValued(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_ADDR_ZIP_OFFSET,
                    EXPECTED_ADDR_ZIP_OFFSET + EXPECTED_TEN_CHARACTER_WIDTH))
                    .as("the copybook declares the postal code as character data, so a five-digit "
                            + "code is padded and never rendered as 0000012546")
                    .isEqualTo("12546     ")
                    .isNotEqualTo("0000012546");
            assertThat(image.substring(EXPECTED_EFT_ACCOUNT_ID_OFFSET,
                    EXPECTED_EFT_ACCOUNT_ID_OFFSET + EXPECTED_TEN_CHARACTER_WIDTH))
                    .as("the transfer identifier is character data for the same reason")
                    .isEqualTo("53581756  ")
                    .isNotEqualTo("0053581756");
        }

        /** The government-issued identifier is character data, so it pads rather than zero-fills. */
        @Test
        @DisplayName("space-pads the government-issued identifier")
        void theGovernmentIssuedIdentifierIsSpacePadded() {
            String image = CustomerRecordMapper.toRecord(narrowValued(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_GOVT_ISSUED_ID_OFFSET,
                    EXPECTED_GOVT_ISSUED_ID_OFFSET + EXPECTED_GOVT_ISSUED_ID_WIDTH))
                    .as("the field is twenty bytes of character data, so an eight-character value "
                            + "keeps its position")
                    .isEqualTo(padded("49368437", EXPECTED_GOVT_ISSUED_ID_WIDTH));
        }

        /** The filler is named and written as spaces, so it never inherits stale bytes. */
        @Test
        @DisplayName("writes the trailing filler as 168 spaces")
        void theFillerIsWrittenAsSpaces() {
            String image = CustomerRecordMapper.toRecord(narrowValued(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image.substring(EXPECTED_FILLER_OFFSET))
                    .as("the filler is placed explicitly rather than left to a buffer's initial state, "
                            + "so that the placements plus the filler account for all 500 bytes")
                    .hasSize(EXPECTED_FILLER_WIDTH)
                    .isEqualTo(run(' ', EXPECTED_FILLER_WIDTH));
        }
    }

    /**
     * Decoding a record that sits at an offset inside a larger buffer, which is how a blocked
     * sequential read presents records.
     */
    @Nested
    @DisplayName("buffer decoding")
    class BufferDecoding {

        /** A buffer of three records surrounded by sentinel bytes on both sides. */
        private static final int SENTINEL_PREFIX_WIDTH = 500;

        /**
         * Builds a buffer holding the canonical record at a non-zero offset, with sentinel bytes
         * before and after so that reading past either edge is detectable.
         *
         * @return a buffer wider than one record
         */
        private byte[] surroundedBuffer() {
            byte[] buffer = new byte[SENTINEL_PREFIX_WIDTH + EXPECTED_RECORD_WIDTH
                    + SENTINEL_PREFIX_WIDTH];
            Arrays.fill(buffer, (byte) '#');
            System.arraycopy(canonicalImage().getBytes(StandardCharsets.US_ASCII), 0,
                    buffer, SENTINEL_PREFIX_WIDTH, EXPECTED_RECORD_WIDTH);
            return buffer;
        }

        /** The window is read from the declared start index and nowhere else. */
        @Test
        @DisplayName("reads exactly the window that begins at the given index")
        void theWindowIsReadFromTheGivenIndex() {
            Customer mapped = CustomerRecordMapper.fromRecord(surroundedBuffer(),
                    SENTINEL_PREFIX_WIDTH, CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getCustId())
                    .as("a window read from the wrong index would pick up sentinel bytes")
                    .isEqualTo(ROW_1_CUST_ID);
            assertThat(mapped.getFirstName()).isEqualTo(padded(ROW_1_FIRST_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getFicoCreditScore()).isEqualTo(ROW_1_FICO);
            assertThat(unseal(mapped.getCustSsn())).isEqualTo(ROW_1_SSN);
        }

        /** The window overload must agree with the whole-image overload on the same bytes. */
        @Test
        @DisplayName("agrees with the whole-image overload on the same bytes")
        void theWindowOverloadAgreesWithTheWholeImageOverload() {
            Customer fromWindow = CustomerRecordMapper.fromRecord(surroundedBuffer(),
                    SENTINEL_PREFIX_WIDTH, CustomerRecordMapperCoverageTest::seal);
            Customer fromWhole = CustomerRecordMapper.fromRecord(canonicalImage(),
                    CustomerRecordMapperCoverageTest::seal);

            assertThat(fromWindow.getCustId()).isEqualTo(fromWhole.getCustId());
            assertThat(fromWindow.getAddrLine1()).isEqualTo(fromWhole.getAddrLine1());
            assertThat(fromWindow.getPriCardHolderInd()).isEqualTo(fromWhole.getPriCardHolderInd());
            assertThat(unseal(fromWindow.getGovtIssuedId()))
                    .isEqualTo(unseal(fromWhole.getGovtIssuedId()));
        }

        /** A window at index zero is the ordinary case and must behave identically. */
        @Test
        @DisplayName("reads a window that begins at index zero")
        void aWindowAtIndexZeroIsRead() {
            Customer mapped = CustomerRecordMapper.fromRecord(
                    canonicalImage().getBytes(StandardCharsets.US_ASCII), 0,
                    CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getCustId()).isEqualTo(ROW_1_CUST_ID);
        }

        /** A negative start index is a caller error and is named as one. */
        @Test
        @DisplayName("refuses a negative start index")
        void aNegativeStartIndexIsRefused() {
            byte[] buffer = surroundedBuffer();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(buffer, -1,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessage(EXPECTED_ARTEFACT
                            + " record image start index must not be negative: from=-1");
        }

        /** A window that would run past the buffer's end is refused, with the arithmetic reported. */
        @Test
        @DisplayName("refuses a window that would overrun the buffer, reporting the arithmetic")
        void anOverrunningWindowIsRefused() {
            byte[] buffer = surroundedBuffer();
            int tooFar = buffer.length - EXPECTED_RECORD_WIDTH + 1;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(buffer, tooFar,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessage(EXPECTED_ARTEFACT
                            + " record image does not fit inside the supplied buffer: from=" + tooFar
                            + ", recordWidth=" + EXPECTED_RECORD_WIDTH
                            + ", buffer length=" + buffer.length);
        }

        /**
         * The window overload deliberately does not check the whole buffer's width, because a blocked
         * read hands it a buffer of many records. This test pins that: a buffer that is not a whole
         * multiple of the record width is still readable at a valid index.
         */
        @Test
        @DisplayName("accepts a buffer whose length is not a multiple of the record width")
        void theBufferNeedNotBeAWholeNumberOfRecords() {
            byte[] ragged = new byte[EXPECTED_RECORD_WIDTH + 7];
            Arrays.fill(ragged, (byte) '#');
            System.arraycopy(canonicalImage().getBytes(StandardCharsets.US_ASCII), 0, ragged, 0,
                    EXPECTED_RECORD_WIDTH);

            assertThat(CustomerRecordMapper.fromRecord(ragged, 0, CustomerRecordMapperCoverageTest::seal)
                    .getCustId())
                    .as("the window overload validates the window, not the buffer, because a blocked "
                            + "read legitimately presents trailing bytes that are not record content")
                    .isEqualTo(ROW_1_CUST_ID);
        }
    }

    /**
     * Input the mapper must refuse rather than accommodate, and the diagnostics it refuses with.
     *
     * <p>A fixed-width record is never padded or truncated to fit, because either accommodation would
     * produce a record of the declared width whose fields no longer line up with the layout. Unlike
     * three of its sibling layouts, this mapper states no width diagnostic of its own: it delegates the
     * whole check to the shared reader, and the wording asserted here is therefore the reader's.
     */
    @Nested
    @DisplayName("malformed input")
    class MalformedInput {

        /**
         * An image of the wrong width is refused, with the supplied length reported so that a caller
         * can see the discrepancy without the record being echoed.
         *
         * @param suppliedWidth the width of the malformed image
         */
        @ParameterizedTest(name = "an image of {0} bytes is refused")
        @ValueSource(ints = {0, 1, 332, 499})
        @DisplayName("refuses an image narrower than the record")
        void anUnderWideImageIsRefused(final int suppliedWidth) {
            String truncated = canonicalImage().substring(0, suppliedWidth);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(truncated,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessage(EXPECTED_ARTEFACT + " record image must be exactly "
                            + EXPECTED_RECORD_WIDTH + " encoded bytes in US-ASCII, but the supplied "
                            + "image is " + suppliedWidth + " encoded bytes; fixed-width records are "
                            + "never padded or truncated on input");
        }

        /**
         * An overshoot of exactly one byte is almost always an unstripped line terminator, and the
         * diagnostic says so, because that is the mistake a caller reading the shipped fixture makes.
         */
        @Test
        @DisplayName("names the line terminator when the overshoot is exactly one byte")
        void aOneByteOvershootNamesTheLineTerminator() {
            String withTerminator = canonicalImage() + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(withTerminator,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessageContaining("the supplied image is " + (EXPECTED_RECORD_WIDTH + 1)
                            + " encoded bytes")
                    .withMessageContaining("an overshoot of exactly one byte is usually an unstripped "
                            + "0x0A line terminator");
        }

        /** An overshoot of more than one byte gets the plain diagnostic, without the hint. */
        @Test
        @DisplayName("omits the line-terminator hint when the overshoot is more than one byte")
        void aLargerOvershootOmitsTheHint() {
            String twoOver = canonicalImage() + "\r\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(twoOver,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessageContaining("the supplied image is " + (EXPECTED_RECORD_WIDTH + 2)
                            + " encoded bytes")
                    .withMessageNotContaining("overshoot of exactly one byte");
        }

        /** The byte-array overload applies the same width rule. */
        @Test
        @DisplayName("applies the same width rule to the byte-array overload")
        void theByteArrayOverloadAppliesTheSameWidthRule() {
            byte[] tooShort = new byte[EXPECTED_RECORD_WIDTH - 1];
            Arrays.fill(tooShort, (byte) ' ');

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(tooShort,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH
                            + " encoded bytes");
        }

        /** A null image is a caller error distinct from a malformed one, and is typed as one. */
        @Test
        @DisplayName("refuses a null record image")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord((String) null,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessage("recordImage must not be null");
        }

        /** A null buffer is refused before any index arithmetic is attempted. */
        @Test
        @DisplayName("refuses a null buffer")
        void aNullBufferIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord((byte[]) null, 0,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessage("buffer must not be null");
        }

        /**
         * The sealing operation is not optional: without it a regulated slice could only be stored as
         * cleartext, so its absence is refused rather than defaulted.
         */
        @Test
        @DisplayName("refuses a missing sealing operation on every decoding overload")
        void aMissingSealingOperationIsRefused() {
            byte[] bytes = canonicalImage().getBytes(StandardCharsets.US_ASCII);

            assertThatNullPointerException()
                    .as("there is no default sealing operation, because any default would either be "
                            + "cryptography this class must not own or a passthrough that leaks")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(canonicalImage(), null))
                    .withMessage("regulatedFieldSealer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(bytes, null))
                    .withMessage("regulatedFieldSealer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(bytes, 0, null))
                    .withMessage("regulatedFieldSealer must not be null");
        }

        /** A null entity is refused by both encoding overloads. */
        @Test
        @DisplayName("refuses a null entity on both encoding overloads")
        void aNullEntityIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(null,
                            CustomerRecordMapperCoverageTest::unseal))
                    .withMessage("customer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecordBytes(null,
                            CustomerRecordMapperCoverageTest::unseal))
                    .withMessage("customer must not be null");
        }

        /** A missing revealing operation is refused by both encoding overloads. */
        @Test
        @DisplayName("refuses a missing revealing operation on both encoding overloads")
        void aMissingRevealingOperationIsRefused() {
            Customer customer = canonicalEntity();

            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, null))
                    .withMessage("regulatedFieldRevealer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecordBytes(customer, null))
                    .withMessage("regulatedFieldRevealer must not be null");
        }

        /**
         * Every attribute that feeds a non-nullable column must be present before a record can be
         * composed, and the diagnostic names both the Java property and the legacy field so that a
         * reader can find either end of the mapping.
         *
         * @param propertyName the Java property that is absent
         * @param fieldName    the legacy field it feeds
         * @param clear        a mutator that clears the property
         */
        @ParameterizedTest(name = "an absent {0} is refused")
        @MethodSource("com.carddemo.util.CustomerRecordMapperCoverageTest#nullableAttributes")
        @DisplayName("refuses an absent value for every non-nullable attribute")
        void anAbsentNonNullableAttributeIsRefused(final String propertyName, final String fieldName,
                final Consumer<Customer> clear) {
            Customer customer = canonicalEntity();
            clear.accept(customer);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer,
                            CustomerRecordMapperCoverageTest::unseal))
                    .withMessage("customer attribute '" + propertyName + "' is absent, so "
                            + EXPECTED_ARTEFACT + " field '" + fieldName + "' cannot be composed; "
                            + "every column behind this layout except the national identifier is not "
                            + "nullable");
        }

        /**
         * A value wider than its field cannot be placed, and the diagnostic reports the two widths
         * rather than showing the value, which for this layout could be personal data.
         */
        @Test
        @DisplayName("refuses a value wider than its field, reporting widths rather than content")
        void anOverWideValueIsRefused() {
            Customer customer = canonicalEntity();
            customer.setFirstName(run('X', EXPECTED_NAME_WIDTH + 1));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer,
                            CustomerRecordMapperCoverageTest::unseal))
                    .withMessage("value does not fit " + EXPECTED_ARTEFACT + " record image field '"
                            + FIELD_FIRST_NAME + "': field width is " + EXPECTED_NAME_WIDTH
                            + " encoded bytes but the value is " + (EXPECTED_NAME_WIDTH + 1)
                            + " encoded bytes; a fixed-width field is never truncated to fit, because "
                            + "a truncated value would leave the record the right width and the wrong "
                            + "content");
        }

        /**
         * The refusal of an over-wide personal-data field must not print the value, because a
         * diagnostic is read by humans and written to logs.
         */
        @Test
        @DisplayName("does not echo personal data when refusing an over-wide value")
        void theOverWideRefusalDoesNotEchoPersonalData() {
            String hostileName = "Immanuel Kessler of 618 Deshaun Route";
            Customer customer = canonicalEntity();
            customer.setFirstName(hostileName);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer,
                            CustomerRecordMapperCoverageTest::unseal))
                    .withMessageNotContaining(hostileName)
                    .withMessageContaining("field width is " + EXPECTED_NAME_WIDTH);
        }
    }

    /**
     * Input that is not 7-bit ASCII, in both directions.
     *
     * <p>This layout is the one where the question is realistic rather than theoretical: it carries
     * three names and three address lines, so an accented character is the sort of value a caller would
     * plausibly supply. The record must be refused rather than transcoded, because a transcoding
     * substitution would produce a record of exactly the right width whose content silently differs
     * from what the caller supplied - and because a byte above {@code 0x7F} in an inbound image means
     * the data is in another encoding altogether rather than being a record to slice.
     */
    @Nested
    @DisplayName("non-ASCII input")
    class NonAsciiInput {

        /**
         * Splices a value into the given-name field of the canonical image, keeping the record exactly
         * one width long.
         *
         * @param name the 25-character value to place
         * @return a 500-character image carrying that given name
         */
        private String withGivenName(final String name) {
            return canonicalImage().substring(0, EXPECTED_FIRST_NAME_OFFSET)
                    + name
                    + canonicalImage().substring(EXPECTED_FIRST_NAME_OFFSET + EXPECTED_NAME_WIDTH);
        }

        /**
         * A character US-ASCII cannot represent is refused on the way in, with its index and code unit
         * reported so the offending position is identifiable without echoing the record.
         */
        @Test
        @DisplayName("refuses a character US-ASCII cannot represent, naming its index and code unit")
        void aNonRepresentableCharacterIsRefusedOnDecode() {
            String accented = withGivenName(padded("Z\u00dcRICH", EXPECTED_NAME_WIDTH));

            assertThat(accented)
                    .as("the substitution keeps the image exactly one record long, so the width guard "
                            + "cannot be what refuses it")
                    .hasSize(EXPECTED_RECORD_WIDTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("measuring and validating by encoding rather than by character count is what "
                            + "makes a silent substitution impossible")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(accented,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessage(EXPECTED_ARTEFACT + " contains a character that US-ASCII cannot "
                            + "represent at index " + (EXPECTED_FIRST_NAME_OFFSET + 1)
                            + " (code unit 0xDC); CardDemo fixed-width records are 7-bit ASCII and "
                            + "must never be transcoded silently");
        }

        /**
         * A byte above {@code 0x7F} is refused, because it indicates another encoding. {@code 0xC1} is
         * EBCDIC {@code 'A'}, which is exactly the mistake a caller reading an unconverted mainframe
         * dataset would make.
         */
        @Test
        @DisplayName("refuses a byte above 0x7F, naming its index and value")
        void aHighByteIsRefusedOnDecode() {
            byte[] image = canonicalImage().getBytes(StandardCharsets.US_ASCII);
            image[EXPECTED_FIRST_NAME_OFFSET] = (byte) 0xC1;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("0xC1 is EBCDIC 'A', and reading it as ASCII would silently corrupt the name")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(image,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessage(EXPECTED_ARTEFACT + " record image contains a non-ASCII byte at index "
                            + EXPECTED_FIRST_NAME_OFFSET + " (0xC1); CardDemo fixed-width records are "
                            + "7-bit ASCII, so a byte above 0x7F indicates data in another encoding "
                            + "rather than a record to slice");
        }

        /**
         * The reported index is relative to the record window rather than to the buffer, so that a
         * blocked read reports a position a reader can locate inside the record layout.
         */
        @Test
        @DisplayName("reports a window-relative index when reading from a buffer")
        void theReportedIndexIsWindowRelative() {
            int windowStart = EXPECTED_RECORD_WIDTH;
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH * 3];
            Arrays.fill(buffer, (byte) ' ');
            System.arraycopy(canonicalImage().getBytes(StandardCharsets.US_ASCII), 0, buffer,
                    windowStart, EXPECTED_RECORD_WIDTH);
            buffer[windowStart + EXPECTED_FIRST_NAME_OFFSET] = (byte) 0x80;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an index counted from the buffer's start would send a reader to the wrong "
                            + "field of the layout")
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(buffer, windowStart,
                            CustomerRecordMapperCoverageTest::seal))
                    .withMessageContaining("non-ASCII byte at index " + EXPECTED_FIRST_NAME_OFFSET
                            + " (0x80)");
        }

        /** The same rule applies on the way out, and the diagnostic names the field rather than the record. */
        @Test
        @DisplayName("refuses a non-representable character in an attribute, naming the field")
        void aNonRepresentableAttributeIsRefusedOnEncode() {
            Customer customer = canonicalEntity();
            customer.setFirstName(padded("Z\u00dcRICH", EXPECTED_NAME_WIDTH));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("on the way out the offending value belongs to a known field, so the "
                            + "diagnostic can name it")
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer,
                            CustomerRecordMapperCoverageTest::unseal))
                    .withMessage(EXPECTED_ARTEFACT + " field '" + FIELD_FIRST_NAME + "' contains a "
                            + "character that US-ASCII cannot represent at index 1 (code unit 0xDC); "
                            + "CardDemo fixed-width records are 7-bit ASCII and must never be "
                            + "transcoded silently");
        }

        /**
         * A revealing operation that hands back a non-representable character is refused too, which
         * matters because that value comes from outside the module entirely.
         */
        @Test
        @DisplayName("refuses a non-representable character returned by the revealing operation")
        void aNonRepresentableRevealedValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a revealed value is external input and is held to the same encoding rule as "
                            + "an attribute")
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(canonicalEntity(),
                            envelope -> "0209738\u00dc8"))
                    .withMessageContaining("field '" + FIELD_CUST_SSN + "' contains a character that "
                            + "US-ASCII cannot represent")
                    .withMessageContaining("(code unit 0xDC)");
        }

        /**
         * A C0 control byte is accepted, which is deliberate rather than an oversight: the rule for
         * record data is 7-bit ASCII, and the narrower printable-only rule belongs to the statement
         * templates whose output is markup. Pinned so that a future tightening is a conscious change
         * rather than an accident, and so that a reader does not mistake the asymmetry for a gap.
         */
        @Test
        @DisplayName("accepts a C0 control byte, because the record rule is 7-bit and not printable-only")
        void aControlByteIsAccepted() {
            byte[] image = canonicalImage().getBytes(StandardCharsets.US_ASCII);
            image[EXPECTED_FIRST_NAME_OFFSET] = 0x07;

            Customer mapped = CustomerRecordMapper.fromRecord(image, CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getFirstName())
                    .as("the byte is inside the 7-bit range, so it is record content as far as this "
                            + "layer is concerned; rejecting it would be a policy this layer does not "
                            + "hold")
                    .startsWith("\u0007")
                    .hasSize(EXPECTED_NAME_WIDTH);
        }
    }

    /**
     * The shipped production-representative fixture, read record by record.
     *
     * <p>Every one of the fifty records is asserted as its own named test rather than inside a loop, so
     * that a failure identifies the record rather than reporting the first of an unknown number.
     */
    @Nested
    @DisplayName("reference fixture")
    class ReferenceFixture {

        /** The fixture's shape is part of the contract, since seeded volumes are asserted elsewhere. */
        @Test
        @DisplayName("holds fifty 500-byte records on a 501-byte stride")
        void theFixtureHasTheDeclaredShape() {
            SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount())
                    .as("the customer dataset holds fifty rows, matching the account and card datasets")
                    .isEqualTo(EXPECTED_FIXTURE_RECORDS);
            assertThat(loaded.recordWidth()).isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(loaded.impliedByteCount())
                    .as("fifty records of 500 bytes each, plus one terminator per record, is 25,050 "
                            + "bytes, which is the size of the shipped file")
                    .isEqualTo(EXPECTED_FIXTURE_BYTES);
        }

        /**
         * Every record must round-trip byte for byte through a decode and a re-encode.
         *
         * @param ordinal the one-based ordinal of the fixture record under test
         */
        @ParameterizedTest(name = "record {0} round-trips byte for byte")
        @MethodSource("com.carddemo.util.CustomerRecordMapperCoverageTest#fixtureOrdinals")
        @DisplayName("round-trips every production record byte for byte")
        void everyProductionRecordRoundTrips(final int ordinal) {
            String original = fixture().record(ordinal);

            String reEncoded = CustomerRecordMapper.toRecord(
                    CustomerRecordMapper.fromRecord(original, CustomerRecordMapperCoverageTest::seal),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(reEncoded)
                    .as("a whole-record comparison is legitimate for this layout because its filler is "
                            + "spaces, which is exactly what the encoder writes")
                    .isEqualTo(original);
        }

        /**
         * The filler of every record must be spaces, which is the premise the whole-record comparison
         * above rests on. Asserted rather than assumed, because three sibling layouts hold ASCII zeros
         * there and for those a whole-record comparison would fail for an unrelated reason.
         */
        @Test
        @DisplayName("carries 168 spaces of filler in every record")
        void everyRecordCarriesSpaceFiller() {
            SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= EXPECTED_FIXTURE_RECORDS; ordinal++) {
                assertThat(loaded.field(ordinal, EXPECTED_FILLER_OFFSET, EXPECTED_FILLER_WIDTH))
                        .as("record %d filler", ordinal)
                        .isEqualTo(run(' ', EXPECTED_FILLER_WIDTH));
            }
        }

        /** The first record's transcribed values, field by field. */
        @Test
        @DisplayName("decodes the first record to its transcribed values")
        void theFirstRecordDecodesToItsTranscribedValues() {
            Customer mapped = CustomerRecordMapper.fromRecord(fixture().record(1),
                    CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(mapped.getFirstName()).isEqualTo(padded(ROW_1_FIRST_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getMiddleName()).isEqualTo(padded(ROW_1_MIDDLE_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getLastName()).isEqualTo(padded(ROW_1_LAST_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getAddrLine1())
                    .isEqualTo(padded(ROW_1_ADDR_LINE_1, EXPECTED_ADDR_LINE_WIDTH));
            assertThat(mapped.getAddrLine2())
                    .isEqualTo(padded(ROW_1_ADDR_LINE_2, EXPECTED_ADDR_LINE_WIDTH));
            assertThat(mapped.getAddrLine3())
                    .isEqualTo(padded(ROW_1_ADDR_LINE_3, EXPECTED_ADDR_LINE_WIDTH));
            assertThat(mapped.getAddrStateCd()).isEqualTo(ROW_1_STATE_CODE);
            assertThat(mapped.getAddrCountryCd()).isEqualTo(FIXTURE_COUNTRY_CODE);
            assertThat(mapped.getAddrZip()).isEqualTo(padded(ROW_1_ZIP, EXPECTED_TEN_CHARACTER_WIDTH));
            assertThat(mapped.getPhoneNum1()).isEqualTo(padded(ROW_1_PHONE_1, EXPECTED_PHONE_WIDTH));
            assertThat(mapped.getPhoneNum2()).isEqualTo(padded(ROW_1_PHONE_2, EXPECTED_PHONE_WIDTH));
            assertThat(unseal(mapped.getCustSsn())).isEqualTo(ROW_1_SSN);
            assertThat(unseal(mapped.getGovtIssuedId())).isEqualTo(ROW_1_GOVT_ISSUED_ID);
            assertThat(mapped.getCustDob()).isEqualTo(ROW_1_DOB);
            assertThat(mapped.getEftAccountId()).isEqualTo(ROW_1_EFT_ACCOUNT_ID);
            assertThat(mapped.getPriCardHolderInd()).isEqualTo(FIXTURE_PRIMARY_INDICATOR);
            assertThat(mapped.getFicoCreditScore()).isEqualTo(ROW_1_FICO);
        }

        /** The last record's transcribed values, so that both ends of the file are pinned. */
        @Test
        @DisplayName("decodes the fiftieth record to its transcribed values")
        void theLastRecordDecodesToItsTranscribedValues() {
            Customer mapped = CustomerRecordMapper.fromRecord(
                    fixture().record(EXPECTED_FIXTURE_RECORDS), CustomerRecordMapperCoverageTest::seal);

            assertThat(mapped.getCustId()).isEqualTo(ROW_50_CUST_ID);
            assertThat(mapped.getFirstName()).isEqualTo(padded(ROW_50_FIRST_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getMiddleName())
                    .isEqualTo(padded(ROW_50_MIDDLE_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getLastName()).isEqualTo(padded(ROW_50_LAST_NAME, EXPECTED_NAME_WIDTH));
            assertThat(mapped.getAddrStateCd()).isEqualTo(ROW_50_STATE_CODE);
            assertThat(unseal(mapped.getCustSsn())).isEqualTo(ROW_50_SSN);
            assertThat(unseal(mapped.getGovtIssuedId())).isEqualTo(ROW_50_GOVT_ISSUED_ID);
            assertThat(mapped.getCustDob()).isEqualTo(ROW_50_DOB);
            assertThat(mapped.getFicoCreditScore()).isEqualTo(ROW_50_FICO);
        }

        /** The identifiers are contiguous, which is what makes the seeded row count checkable. */
        @Test
        @DisplayName("carries contiguous identifiers from 000000001 to 000000050")
        void theIdentifiersAreContiguous() {
            SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= EXPECTED_FIXTURE_RECORDS; ordinal++) {
                assertThat(loaded.field(ordinal, EXPECTED_CUST_ID_OFFSET, EXPECTED_CUST_ID_WIDTH))
                        .as("record %d identifier", ordinal)
                        .isEqualTo(zeroFilled(String.valueOf(ordinal), EXPECTED_CUST_ID_WIDTH));
            }
        }

        /**
         * The fixture's regulated windows are populated, which is worth pinning because the seeded
         * database stores no national identifier: the absence arises in the seed migration and not in
         * this file, and a reader comparing the two would otherwise suspect the fixture.
         */
        @Test
        @DisplayName("populates the national identifier window in every record")
        void theNationalIdentifierWindowIsPopulated() {
            SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= EXPECTED_FIXTURE_RECORDS; ordinal++) {
                assertThat(loaded.field(ordinal, EXPECTED_CUST_SSN_OFFSET, EXPECTED_CUST_SSN_WIDTH))
                        .as("record %d national identifier is nine digits in the file, whatever the "
                                + "seeded database chooses to store", ordinal)
                        .hasSize(EXPECTED_CUST_SSN_WIDTH)
                        .containsOnlyDigits();
                assertThat(loaded.field(ordinal, EXPECTED_GOVT_ISSUED_ID_OFFSET,
                        EXPECTED_GOVT_ISSUED_ID_WIDTH))
                        .as("record %d government-issued identifier fills its twenty bytes", ordinal)
                        .hasSize(EXPECTED_GOVT_ISSUED_ID_WIDTH)
                        .containsOnlyDigits();
            }
        }

        /**
         * Composition facts that make the fixture representative: one country, one cardholder
         * indicator, and a credit-score range that spans the field's width.
         */
        @Test
        @DisplayName("carries one country code and one cardholder indicator throughout")
        void theInvariantColumnsAreInvariant() {
            SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= EXPECTED_FIXTURE_RECORDS; ordinal++) {
                assertThat(loaded.field(ordinal, EXPECTED_ADDR_COUNTRY_CD_OFFSET,
                        EXPECTED_ADDR_COUNTRY_CD_WIDTH))
                        .as("record %d country code", ordinal)
                        .isEqualTo(FIXTURE_COUNTRY_CODE);
                assertThat(loaded.field(ordinal, EXPECTED_PRI_CARD_HOLDER_IND_OFFSET,
                        EXPECTED_PRI_CARD_HOLDER_IND_WIDTH))
                        .as("record %d cardholder indicator", ordinal)
                        .isEqualTo(FIXTURE_PRIMARY_INDICATOR);
                assertThat(loaded.field(ordinal, EXPECTED_FICO_CREDIT_SCORE_OFFSET,
                        EXPECTED_FICO_CREDIT_SCORE_WIDTH))
                        .as("record %d credit score occupies its three digits", ordinal)
                        .hasSize(EXPECTED_FICO_CREDIT_SCORE_WIDTH)
                        .containsOnlyDigits();
            }
        }
    }

    /**
     * Whole-record equivalence in both directions, including composition with the module's shipped
     * codec so that the deterministic double used elsewhere is shown not to be a straw man.
     */
    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        /** The canonical image survives a decode and a re-encode unchanged. */
        @Test
        @DisplayName("round-trips the canonical image unchanged")
        void theCanonicalImageRoundTrips() {
            String original = canonicalImage();

            String reEncoded = CustomerRecordMapper.toRecord(
                    CustomerRecordMapper.fromRecord(original, CustomerRecordMapperCoverageTest::seal),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(reEncoded).isEqualTo(original);
        }

        /** The marked image survives too, which proves no field is read or written at a shifted offset. */
        @Test
        @DisplayName("round-trips the marked image unchanged")
        void theMarkedImageRoundTrips() {
            String original = markedImage();

            String reEncoded = CustomerRecordMapper.toRecord(
                    CustomerRecordMapper.fromRecord(original, CustomerRecordMapperCoverageTest::seal),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(reEncoded)
                    .as("every field holds a distinct marker, so any shifted offset would place one "
                            + "marker where another belongs")
                    .isEqualTo(original);
        }

        /** Encoding an entity assembled here produces exactly the image it was assembled from. */
        @Test
        @DisplayName("encodes the canonical entity to the canonical image")
        void theCanonicalEntityEncodesToTheCanonicalImage() {
            String image = CustomerRecordMapper.toRecord(canonicalEntity(),
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(image)
                    .as("the entity and the image are assembled independently from the same "
                            + "transcribed values, so agreement is a real check and not a tautology")
                    .isEqualTo(canonicalImage());
        }

        /** The byte overload must produce the same bytes as the string overload, in US-ASCII. */
        @Test
        @DisplayName("produces identical bytes from both encoding overloads")
        void bothEncodingOverloadsProduceTheSameBytes() {
            Customer customer = canonicalEntity();

            String asText = CustomerRecordMapper.toRecord(customer, CustomerRecordMapperCoverageTest::unseal);
            byte[] asBytes = CustomerRecordMapper.toRecordBytes(customer,
                    CustomerRecordMapperCoverageTest::unseal);

            assertThat(asBytes)
                    .as("the record is US-ASCII, so the two overloads must agree byte for byte")
                    .hasSize(EXPECTED_RECORD_WIDTH)
                    .isEqualTo(asText.getBytes(StandardCharsets.US_ASCII));
        }

        /**
         * The same round trip through the module's real codec under a real key.
         *
         * <p>This is what shows the deterministic double is an adequate stand-in rather than a shape
         * the production entity would never see. The codec chooses a fresh initialisation vector per
         * call, so the stored envelope differs between runs while the composition of sealing and
         * revealing stays the identity - which is exactly the property the mapper relies on.
         */
        @Test
        @DisplayName("round-trips through the module's shipped codec under a real key")
        void theRoundTripHoldsThroughTheShippedCodec() {
            byte[] key = new byte[SensitiveFieldCodec.KEY_LENGTH_BYTES];
            for (int index = 0; index < key.length; index++) {
                key[index] = (byte) index;
            }
            String original = canonicalImage();

            Customer sealed = CustomerRecordMapper.fromRecord(original,
                    cleartext -> SensitiveFieldCodec.protect(cleartext, key));
            String reEncoded = CustomerRecordMapper.toRecord(sealed,
                    envelope -> SensitiveFieldCodec.reveal(envelope, key));

            assertThat(sealed.getCustSsn())
                    .as("the real codec produces a genuine envelope, not a reformatting of the digits")
                    .startsWith(ENVELOPE_PREFIX)
                    .doesNotContain(ROW_1_SSN);
            assertThat(reEncoded)
                    .as("byte-exact equivalence must not depend on which sealing operation the caller "
                            + "supplies, only on that operation being an inverse pair")
                    .isEqualTo(original);
        }

        /**
         * Two successive real-codec seals of the same cleartext differ, and both still reveal to it -
         * which is why the mapper compares nothing about the envelope and only ever hands it back.
         */
        @Test
        @DisplayName("tolerates a non-deterministic sealing operation")
        void aNonDeterministicSealingOperationIsTolerated() {
            byte[] key = new byte[SensitiveFieldCodec.KEY_LENGTH_BYTES];
            Arrays.fill(key, (byte) 7);

            Customer first = CustomerRecordMapper.fromRecord(canonicalImage(),
                    cleartext -> SensitiveFieldCodec.protect(cleartext, key));
            Customer second = CustomerRecordMapper.fromRecord(canonicalImage(),
                    cleartext -> SensitiveFieldCodec.protect(cleartext, key));

            assertThat(second.getCustSsn())
                    .as("a fresh initialisation vector per call means the stored envelopes differ, and "
                            + "the mapper must never depend on them matching")
                    .isNotEqualTo(first.getCustSsn());
            assertThat(SensitiveFieldCodec.reveal(second.getCustSsn(), key))
                    .as("both envelopes nonetheless carry the same cleartext")
                    .isEqualTo(SensitiveFieldCodec.reveal(first.getCustSsn(), key));
        }
    }
}
