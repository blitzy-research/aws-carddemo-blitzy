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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

import com.carddemo.domain.Customer;
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
 * Unit tests for {@link CustomerRecordMapper}, which maps the five-hundred-byte
 * {@code CUSTOMER-RECORD} declared by {@code app/cpy/CVCUS01Y.cpy} and its {@code CUSTREC} variant
 * onto {@link Customer} and back.
 *
 * <p><strong>This is the widest layout in the estate and the only one with regulated fields.</strong>
 * Eighteen mapped fields fill three hundred and thirty-two bytes, followed by a hundred and
 * sixty-eight of filler. Two of the eighteen — the national identifier and the government-issued
 * identifier — are stored unprotected in the legacy record and are protected at rest in the target,
 * so the mapper does not read or write them directly. It funnels each through a caller-supplied
 * operator: a sealer on the way in and a revealer on the way out. That indirection is the whole point
 * of the design, so the tests supply a genuine inverse pair rather than an identity function, and
 * assert the resulting round trip is byte-identical across the entire sample file.
 *
 * <p><strong>An identity function does not work here, and that is by design.</strong>
 * {@link Customer} refuses to store either regulated attribute unless it carries the module's
 * protected-value envelope, so a sealer that returns its input unchanged is rejected by the entity
 * constructor before the mapper ever completes. The inverse pair used below reproduces the envelope
 * the entity requires, which is what lets these tests exercise the real path instead of a stub.
 *
 * <p><strong>The national identifier is the only nullable column, and it has its own emit
 * path.</strong> When it is absent the emitter writes spaces through the alphanumeric path rather
 * than zeros through the numeric one, so an absent identifier is distinguishable from a zero-valued
 * one in the byte image. Both branches are asserted.
 *
 * <p><strong>One guard is deliberately not exercised, and the reason is recorded rather than
 * hidden.</strong> The emitter checks the government-issued identifier for absence, but
 * {@link Customer} rejects {@code null} for that attribute in both its constructor and its setter, so
 * no reachable instance can carry one. The guard is defensive depth, not dead code that a test could
 * legitimately reach, and no test here pretends otherwise.
 *
 * <p><strong>Two date-of-birth field names are published because two copybooks disagree.</strong>
 * {@code CVCUS01Y} names the field one way and {@code CUSTREC} another, for the same offset and the
 * same width. Both names are published so a diagnostic can cite whichever copybook the caller is
 * reading against; the mapper itself cites the first. Both constants are asserted.
 */
@DisplayName("CustomerRecordMapper - the five-hundred-byte CVCUS01Y record and its two regulated fields")
class CustomerRecordMapperRuleComplianceTest {

    /** The fixture whose bytes are the decode authority for this layout. */
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/input/custdata.txt");

    /** The record count the fixture carries, as measured from its byte length. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** The stride between fixture records: the record width plus one line terminator. */
    private static final int FIXTURE_STRIDE = CustomerRecordMapper.RECORD_WIDTH + 1;

    /** The envelope marker the entity requires on every protected value. */
    private static final String PROTECTED_VALUE_PREFIX = "ENC1:";

    /** The minimum decoded body length the entity requires inside a protected envelope. */
    private static final int PROTECTED_VALUE_MINIMUM_BYTES = 28;

    /**
     * Seals a cleartext value into the protected-value envelope the entity requires. The body is
     * zero-padded up to the entity's minimum so that short values such as a nine-digit national
     * identifier still satisfy the envelope contract, and {@link #reveal} strips that padding again.
     *
     * @param cleartext the value as it appears in the record image
     * @return the sealed envelope
     */
    private static String seal(final String cleartext) {
        final byte[] raw = cleartext.getBytes(StandardCharsets.US_ASCII);
        final byte[] body = new byte[Math.max(PROTECTED_VALUE_MINIMUM_BYTES, raw.length)];
        System.arraycopy(raw, 0, body, 0, raw.length);
        return PROTECTED_VALUE_PREFIX + Base64.getEncoder().encodeToString(body);
    }

    /**
     * Reveals a sealed value, undoing {@link #seal} exactly.
     *
     * @param envelope the sealed envelope
     * @return the cleartext as it appeared in the record image
     */
    private static String reveal(final String envelope) {
        final byte[] body = Base64.getDecoder()
                .decode(envelope.substring(PROTECTED_VALUE_PREFIX.length()));
        int end = body.length;
        while (end > 0 && body[end - 1] == 0) {
            end--;
        }
        return new String(body, 0, end, StandardCharsets.US_ASCII);
    }

    /** The sealer the decode tests supply. */
    private static UnaryOperator<String> sealer() {
        return CustomerRecordMapperRuleComplianceTest::seal;
    }

    /** The revealer the encode tests supply, the exact inverse of the sealer. */
    private static UnaryOperator<String> revealer() {
        return CustomerRecordMapperRuleComplianceTest::reveal;
    }

    /**
     * Reads one record image from the fixture by ordinal, excluding the line terminator.
     *
     * @param ordinal the zero-based record position
     * @return the five-hundred-byte record image
     * @throws IOException when the fixture cannot be read
     */
    private static String fixtureRecord(final int ordinal) throws IOException {
        final byte[] file = Files.readAllBytes(FIXTURE);
        return new String(file, ordinal * FIXTURE_STRIDE, CustomerRecordMapper.RECORD_WIDTH,
                StandardCharsets.US_ASCII);
    }

    /**
     * Reads every record image the fixture carries.
     *
     * @return the fifty record images in file order
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
     * Pads a value on the right with spaces to a declared field width.
     *
     * @param value the value to pad
     * @param width the declared field width
     * @return the value padded to the width
     */
    private static String spacePadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * The eighteen constructor arguments of a nominal customer, in the copybook's own field order.
     * The two regulated positions carry sealed envelopes because the entity accepts nothing else.
     * Tests that need one attribute absent copy this array and null a single position, which keeps
     * the eighteen-argument constructor written once rather than once per case.
     *
     * @return the eighteen attribute values in constructor order
     */
    private static String[] nominalAttributes() {
        return new String[] {
            "000000001", "Immanuel", "Madeline", "Kessler",
            "123 Main Street", "Suite 4", "Raleigh",
            "NC", "USA", "12546     ",
            "555-111-2222", "555-333-4444",
            seal("020973888"), seal("00000000000049368437"),
            "1961-06-08", "0053581756",
            "Y", "274",
        };
    }

    /**
     * Builds a customer from the eighteen attribute values, positionally, in constructor order.
     *
     * @param attributes the eighteen attribute values
     * @return the customer
     */
    private static Customer customerFrom(final String[] attributes) {
        return new Customer(attributes[0], attributes[1], attributes[2], attributes[3],
                attributes[4], attributes[5], attributes[6],
                attributes[7], attributes[8], attributes[9],
                attributes[10], attributes[11],
                attributes[12], attributes[13],
                attributes[14], attributes[15],
                attributes[16], attributes[17]);
    }

    /**
     * Builds a nominal customer whose every attribute is present.
     *
     * @return the customer
     */
    private static Customer aNominalCustomer() {
        return customerFrom(nominalAttributes());
    }

    /**
     * Builds a customer with exactly one constructor position set to {@code null}.
     *
     * @param position the zero-based constructor position to null
     * @return the customer
     */
    private static Customer customerWithout(final int position) {
        final String[] attributes = nominalAttributes();
        attributes[position] = null;
        return customerFrom(attributes);
    }

    // =================================================================================
    // The published layout
    // =================================================================================

    @Nested
    @DisplayName("The published layout")
    class ThePublishedLayout {

        @Test
        @DisplayName("the record is five hundred bytes: three hundred and thirty-two mapped plus filler")
        void theRecordIsFiveHundredBytes() {
            assertThat(CustomerRecordMapper.MAPPED_DATA_WIDTH).isEqualTo(332);
            assertThat(CustomerRecordMapper.FILLER_LENGTH).isEqualTo(168);
            assertThat(CustomerRecordMapper.RECORD_WIDTH)
                    .isEqualTo(CustomerRecordMapper.MAPPED_DATA_WIDTH
                            + CustomerRecordMapper.FILLER_LENGTH)
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("the eighteen mapped fields run contiguously from offset zero without a gap")
        void theEighteenFieldsRunContiguouslyFromZero() {
            assertThat(CustomerRecordMapper.CUST_ID_OFFSET).isZero();
            assertThat(CustomerRecordMapper.FIRST_NAME_OFFSET)
                    .isEqualTo(CustomerRecordMapper.CUST_ID_OFFSET
                            + CustomerRecordMapper.CUST_ID_LENGTH);
            assertThat(CustomerRecordMapper.MIDDLE_NAME_OFFSET)
                    .isEqualTo(CustomerRecordMapper.FIRST_NAME_OFFSET
                            + CustomerRecordMapper.FIRST_NAME_LENGTH);
            assertThat(CustomerRecordMapper.LAST_NAME_OFFSET)
                    .isEqualTo(CustomerRecordMapper.MIDDLE_NAME_OFFSET
                            + CustomerRecordMapper.MIDDLE_NAME_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_1_OFFSET)
                    .isEqualTo(CustomerRecordMapper.LAST_NAME_OFFSET
                            + CustomerRecordMapper.LAST_NAME_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_2_OFFSET)
                    .isEqualTo(CustomerRecordMapper.ADDR_LINE_1_OFFSET
                            + CustomerRecordMapper.ADDR_LINE_1_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_LINE_3_OFFSET)
                    .isEqualTo(CustomerRecordMapper.ADDR_LINE_2_OFFSET
                            + CustomerRecordMapper.ADDR_LINE_2_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_STATE_CD_OFFSET)
                    .isEqualTo(CustomerRecordMapper.ADDR_LINE_3_OFFSET
                            + CustomerRecordMapper.ADDR_LINE_3_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_COUNTRY_CD_OFFSET)
                    .isEqualTo(CustomerRecordMapper.ADDR_STATE_CD_OFFSET
                            + CustomerRecordMapper.ADDR_STATE_CD_LENGTH);
            assertThat(CustomerRecordMapper.ADDR_ZIP_OFFSET)
                    .isEqualTo(CustomerRecordMapper.ADDR_COUNTRY_CD_OFFSET
                            + CustomerRecordMapper.ADDR_COUNTRY_CD_LENGTH);
            assertThat(CustomerRecordMapper.PHONE_NUM_1_OFFSET)
                    .isEqualTo(CustomerRecordMapper.ADDR_ZIP_OFFSET
                            + CustomerRecordMapper.ADDR_ZIP_LENGTH);
            assertThat(CustomerRecordMapper.PHONE_NUM_2_OFFSET)
                    .isEqualTo(CustomerRecordMapper.PHONE_NUM_1_OFFSET
                            + CustomerRecordMapper.PHONE_NUM_1_LENGTH);
            assertThat(CustomerRecordMapper.CUST_SSN_OFFSET)
                    .isEqualTo(CustomerRecordMapper.PHONE_NUM_2_OFFSET
                            + CustomerRecordMapper.PHONE_NUM_2_LENGTH);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET)
                    .isEqualTo(CustomerRecordMapper.CUST_SSN_OFFSET
                            + CustomerRecordMapper.CUST_SSN_LENGTH);
            assertThat(CustomerRecordMapper.CUST_DOB_OFFSET)
                    .isEqualTo(CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET
                            + CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH);
            assertThat(CustomerRecordMapper.EFT_ACCOUNT_ID_OFFSET)
                    .isEqualTo(CustomerRecordMapper.CUST_DOB_OFFSET
                            + CustomerRecordMapper.CUST_DOB_LENGTH);
            assertThat(CustomerRecordMapper.PRI_CARD_HOLDER_IND_OFFSET)
                    .isEqualTo(CustomerRecordMapper.EFT_ACCOUNT_ID_OFFSET
                            + CustomerRecordMapper.EFT_ACCOUNT_ID_LENGTH);
            assertThat(CustomerRecordMapper.FICO_CREDIT_SCORE_OFFSET)
                    .isEqualTo(CustomerRecordMapper.PRI_CARD_HOLDER_IND_OFFSET
                            + CustomerRecordMapper.PRI_CARD_HOLDER_IND_LENGTH);
        }

        @Test
        @DisplayName("the three name fields are each twenty-five bytes wide")
        void theThreeNameFieldsAreEachTwentyFiveBytes() {
            assertThat(CustomerRecordMapper.FIRST_NAME_LENGTH).isEqualTo(25);
            assertThat(CustomerRecordMapper.MIDDLE_NAME_LENGTH).isEqualTo(25);
            assertThat(CustomerRecordMapper.LAST_NAME_LENGTH).isEqualTo(25);
        }

        @Test
        @DisplayName("the three address lines are each fifty bytes wide")
        void theThreeAddressLinesAreEachFiftyBytes() {
            assertThat(CustomerRecordMapper.ADDR_LINE_1_LENGTH).isEqualTo(50);
            assertThat(CustomerRecordMapper.ADDR_LINE_2_LENGTH).isEqualTo(50);
            assertThat(CustomerRecordMapper.ADDR_LINE_3_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the remaining declared widths are the ones CVCUS01Y publishes")
        void theRemainingDeclaredWidthsAreTheCopybookWidths() {
            assertThat(CustomerRecordMapper.CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CustomerRecordMapper.ADDR_STATE_CD_LENGTH).isEqualTo(2);
            assertThat(CustomerRecordMapper.ADDR_COUNTRY_CD_LENGTH).isEqualTo(3);
            assertThat(CustomerRecordMapper.ADDR_ZIP_LENGTH).isEqualTo(10);
            assertThat(CustomerRecordMapper.PHONE_NUM_1_LENGTH).isEqualTo(15);
            assertThat(CustomerRecordMapper.PHONE_NUM_2_LENGTH).isEqualTo(15);
            assertThat(CustomerRecordMapper.CUST_SSN_LENGTH).isEqualTo(9);
            assertThat(CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH).isEqualTo(20);
            assertThat(CustomerRecordMapper.CUST_DOB_LENGTH).isEqualTo(10);
            assertThat(CustomerRecordMapper.EFT_ACCOUNT_ID_LENGTH).isEqualTo(10);
            assertThat(CustomerRecordMapper.PRI_CARD_HOLDER_IND_LENGTH).isEqualTo(1);
            assertThat(CustomerRecordMapper.FICO_CREDIT_SCORE_LENGTH).isEqualTo(3);
        }

        @Test
        @DisplayName("filler begins where the mapped fields end")
        void fillerBeginsWhereTheMappedFieldsEnd() {
            assertThat(CustomerRecordMapper.FILLER_OFFSET)
                    .isEqualTo(CustomerRecordMapper.MAPPED_DATA_WIDTH);
        }

        @Test
        @DisplayName("both copybooks' date-of-birth field names are published for the same offset")
        void bothDateOfBirthFieldNamesArePublished() {
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CVCUS01Y)
                    .isEqualTo("CUST-DOB-YYYY-MM-DD");
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CUSTREC)
                    .isEqualTo("CUST-DOB-YYYYMMDD");
            assertThat(CustomerRecordMapper.CUST_DOB_FIELD_CVCUS01Y)
                    .isNotEqualTo(CustomerRecordMapper.CUST_DOB_FIELD_CUSTREC);
        }

        @Test
        @DisplayName("the artefact name cites both copybooks that share this layout")
        void theArtefactNameCitesBothCopybooks() {
            assertThat(CustomerRecordMapper.ARTEFACT)
                    .isEqualTo("CUSTOMER-RECORD (CVCUS01Y/CUSTREC)")
                    .contains("CVCUS01Y")
                    .contains("CUSTREC");
        }

        @Test
        @DisplayName("the mapper is a final class whose private constructor refuses instantiation")
        void theMapperIsFinalAndRefusesInstantiation() throws NoSuchMethodException {
            assertThat(Modifier.isFinal(CustomerRecordMapper.class.getModifiers())).isTrue();

            final Constructor<CustomerRecordMapper> constructor =
                    CustomerRecordMapper.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class)
                    .satisfies(wrapper -> assertThat(wrapper.getCause()).hasMessage(
                            "CustomerRecordMapper is a static utility and is not instantiable"));
        }
    }

    // =================================================================================
    // Decoding the real fixture
    // =================================================================================

    @Nested
    @DisplayName("Decoding the real fixture")
    class DecodingTheRealFixture {

        @Test
        @DisplayName("the fixture carries fifty records of exactly the declared width")
        void theFixtureCarriesFiftyRecordsOfTheDeclaredWidth() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            assertThat(file).hasSize(FIXTURE_RECORD_COUNT * FIXTURE_STRIDE);
            assertThat(allFixtureRecords())
                    .hasSize(FIXTURE_RECORD_COUNT)
                    .allSatisfy(image ->
                            assertThat(image).hasSize(CustomerRecordMapper.RECORD_WIDTH));
        }

        @Test
        @DisplayName("the first record decodes to the documented plain attributes")
        void theFirstRecordDecodesToTheDocumentedPlainAttributes() throws IOException {
            final Customer customer =
                    CustomerRecordMapper.fromRecord(fixtureRecord(0), sealer());

            assertThat(customer.getCustId()).isEqualTo("000000001");
            assertThat(customer.getFirstName())
                    .isEqualTo(spacePadded("Immanuel", CustomerRecordMapper.FIRST_NAME_LENGTH));
            assertThat(customer.getAddrCountryCd()).isEqualTo("USA");
            assertThat(customer.getCustDob()).isEqualTo("1961-06-08");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("Y");
            assertThat(customer.getFicoCreditScore()).isEqualTo("274");
        }

        @Test
        @DisplayName("the two regulated fields arrive sealed, never as the cleartext the record held")
        void theTwoRegulatedFieldsArriveSealed() throws IOException {
            final Customer customer =
                    CustomerRecordMapper.fromRecord(fixtureRecord(0), sealer());

            assertThat(customer.getCustSsn()).startsWith(PROTECTED_VALUE_PREFIX);
            assertThat(customer.getGovtIssuedId()).startsWith(PROTECTED_VALUE_PREFIX);
            assertThat(customer.getCustSsn()).doesNotContain("020973888");
            assertThat(reveal(customer.getCustSsn())).isEqualTo("020973888");
            assertThat(reveal(customer.getGovtIssuedId())).isEqualTo("00000000000049368437");
        }

        @Test
        @DisplayName("the sealer is handed the raw field content at its declared width")
        void theSealerIsHandedTheRawFieldContent() throws IOException {
            final List<String> observed = new ArrayList<>();

            CustomerRecordMapper.fromRecord(fixtureRecord(0), cleartext -> {
                observed.add(cleartext);
                return seal(cleartext);
            });

            assertThat(observed).hasSize(2);
            assertThat(observed.get(0)).hasSize(CustomerRecordMapper.CUST_SSN_LENGTH);
            assertThat(observed.get(1)).hasSize(CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH);
            assertThat(observed).containsExactly("020973888", "00000000000049368437");
        }

        @Test
        @DisplayName("every one of the fifty records decodes without refusal")
        void everyRecordDecodesWithoutRefusal() throws IOException {
            final List<Customer> decoded = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                decoded.add(CustomerRecordMapper.fromRecord(image, sealer()));
            }

            assertThat(decoded).hasSize(FIXTURE_RECORD_COUNT).allSatisfy(customer -> {
                assertThat(customer.getCustId()).hasSize(CustomerRecordMapper.CUST_ID_LENGTH);
                assertThat(customer.getAddrCountryCd()).isEqualTo("USA");
                assertThat(customer.getCustSsn()).startsWith(PROTECTED_VALUE_PREFIX);
                assertThat(customer.getGovtIssuedId()).startsWith(PROTECTED_VALUE_PREFIX);
            });
        }

        @Test
        @DisplayName("field values are returned verbatim, so trailing spaces are not trimmed away")
        void fieldValuesAreReturnedVerbatim() throws IOException {
            final Customer customer =
                    CustomerRecordMapper.fromRecord(fixtureRecord(0), sealer());

            assertThat(customer.getFirstName())
                    .hasSize(CustomerRecordMapper.FIRST_NAME_LENGTH)
                    .endsWith(" ");
            assertThat(customer.getAddrLine1()).hasSize(CustomerRecordMapper.ADDR_LINE_1_LENGTH);
            assertThat(customer.getAddrZip()).hasSize(CustomerRecordMapper.ADDR_ZIP_LENGTH);
        }

        @Test
        @DisplayName("the fifty customer identifiers are all distinct")
        void theFiftyCustomerIdentifiersAreDistinct() throws IOException {
            final List<String> identifiers = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                identifiers.add(CustomerRecordMapper.fromRecord(image, sealer()).getCustId());
            }

            assertThat(identifiers).hasSize(FIXTURE_RECORD_COUNT).doesNotHaveDuplicates();
            assertThat(identifiers.get(0)).isEqualTo("000000001");
        }

        @Test
        @DisplayName("decoding from a byte array agrees with decoding from a string")
        void decodingFromBytesAgreesWithDecodingFromAString() throws IOException {
            for (final String image : allFixtureRecords()) {
                final Customer fromString = CustomerRecordMapper.fromRecord(image, sealer());
                final Customer fromBytes = CustomerRecordMapper
                        .fromRecord(image.getBytes(StandardCharsets.US_ASCII), sealer());

                assertThat(fromBytes.getCustId()).isEqualTo(fromString.getCustId());
                assertThat(fromBytes.getLastName()).isEqualTo(fromString.getLastName());
                assertThat(fromBytes.getCustSsn()).isEqualTo(fromString.getCustSsn());
            }
        }

        @Test
        @DisplayName("decoding a slice of the whole file agrees with decoding the isolated record")
        void decodingASliceAgreesWithTheIsolatedRecord() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            for (int ordinal = 0; ordinal < FIXTURE_RECORD_COUNT; ordinal++) {
                final Customer sliced =
                        CustomerRecordMapper.fromRecord(file, ordinal * FIXTURE_STRIDE, sealer());
                final Customer isolated =
                        CustomerRecordMapper.fromRecord(fixtureRecord(ordinal), sealer());

                assertThat(sliced.getCustId()).isEqualTo(isolated.getCustId());
                assertThat(sliced.getFicoCreditScore()).isEqualTo(isolated.getFicoCreditScore());
                assertThat(sliced.getGovtIssuedId()).isEqualTo(isolated.getGovtIssuedId());
            }
        }

        @Test
        @DisplayName("the fixture's own filler is spaces, which is what the mapper emits")
        void theFixtureFillerIsSpaces() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(image.substring(CustomerRecordMapper.FILLER_OFFSET))
                        .hasSize(CustomerRecordMapper.FILLER_LENGTH)
                        .isBlank();
            }
        }
    }

    // =================================================================================
    // Encoding and the byte-identical round trip
    // =================================================================================

    @Nested
    @DisplayName("Encoding and the byte-identical round trip")
    class EncodingAndTheByteIdenticalRoundTrip {

        @Test
        @DisplayName("every one of the fifty records survives a decode-then-encode cycle byte for byte")
        void everyRecordSurvivesTheCycleByteForByte() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(CustomerRecordMapper.toRecord(
                        CustomerRecordMapper.fromRecord(image, sealer()), revealer()))
                        .isEqualTo(image);
            }
        }

        @Test
        @DisplayName("the byte-form cycle is byte-identical too")
        void theByteFormCycleIsByteIdentical() throws IOException {
            for (final String image : allFixtureRecords()) {
                final byte[] original = image.getBytes(StandardCharsets.US_ASCII);

                assertThat(CustomerRecordMapper.toRecordBytes(
                        CustomerRecordMapper.fromRecord(original, sealer()), revealer()))
                        .isEqualTo(original);
            }
        }

        @Test
        @DisplayName("the byte emitter agrees with the string emitter")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final Customer customer = aNominalCustomer();

            assertThat(CustomerRecordMapper.toRecordBytes(customer, revealer()))
                    .isEqualTo(CustomerRecordMapper.toRecord(customer, revealer())
                            .getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CustomerRecordMapper.RECORD_WIDTH);
        }

        @Test
        @DisplayName("the revealer is handed the sealed envelope, not the cleartext")
        void theRevealerIsHandedTheSealedEnvelope() {
            final List<String> observed = new ArrayList<>();

            CustomerRecordMapper.toRecord(aNominalCustomer(), envelope -> {
                observed.add(envelope);
                return reveal(envelope);
            });

            assertThat(observed).hasSize(2);
            assertThat(observed).allSatisfy(envelope ->
                    assertThat(envelope).startsWith(PROTECTED_VALUE_PREFIX));
        }

        @Test
        @DisplayName("the identifier and the credit score are right-justified and zero-padded")
        void theNumericFieldsAreZeroPadded() {
            final String[] attributes = nominalAttributes();
            attributes[0] = "7";
            attributes[17] = "5";

            final String emitted = CustomerRecordMapper.toRecord(customerFrom(attributes),
                    revealer());

            assertThat(emitted.substring(CustomerRecordMapper.CUST_ID_OFFSET,
                    CustomerRecordMapper.FIRST_NAME_OFFSET)).isEqualTo("000000007");
            assertThat(emitted.substring(CustomerRecordMapper.FICO_CREDIT_SCORE_OFFSET,
                    CustomerRecordMapper.MAPPED_DATA_WIDTH)).isEqualTo("005");
        }

        @Test
        @DisplayName("the character fields are left-justified and space-padded")
        void theCharacterFieldsAreSpacePadded() {
            final String emitted = CustomerRecordMapper.toRecord(aNominalCustomer(), revealer());

            assertThat(emitted.substring(CustomerRecordMapper.FIRST_NAME_OFFSET,
                    CustomerRecordMapper.MIDDLE_NAME_OFFSET))
                    .isEqualTo(spacePadded("Immanuel", CustomerRecordMapper.FIRST_NAME_LENGTH));
            assertThat(emitted.substring(CustomerRecordMapper.ADDR_LINE_1_OFFSET,
                    CustomerRecordMapper.ADDR_LINE_2_OFFSET))
                    .isEqualTo(spacePadded("123 Main Street",
                            CustomerRecordMapper.ADDR_LINE_1_LENGTH));
            assertThat(emitted.substring(CustomerRecordMapper.ADDR_STATE_CD_OFFSET,
                    CustomerRecordMapper.ADDR_COUNTRY_CD_OFFSET)).isEqualTo("NC");
        }

        @Test
        @DisplayName("the emitted filler is a hundred and sixty-eight spaces")
        void theEmittedFillerIsSpaces() {
            assertThat(CustomerRecordMapper.toRecord(aNominalCustomer(), revealer())
                    .substring(CustomerRecordMapper.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CustomerRecordMapper.FILLER_LENGTH));
        }

        @Test
        @DisplayName("an absent national identifier is emitted as spaces, not as zeros")
        void anAbsentNationalIdentifierIsEmittedAsSpaces() {
            final Customer customer = customerWithout(12);

            assertThat(customer.getCustSsn()).isNull();

            final String emitted = CustomerRecordMapper.toRecord(customer, revealer());

            assertThat(emitted.substring(CustomerRecordMapper.CUST_SSN_OFFSET,
                    CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET))
                    .isEqualTo(" ".repeat(CustomerRecordMapper.CUST_SSN_LENGTH))
                    .isNotEqualTo("0".repeat(CustomerRecordMapper.CUST_SSN_LENGTH));
        }

        @Test
        @DisplayName("an absent national identifier bypasses the revealer entirely")
        void anAbsentNationalIdentifierBypassesTheRevealer() {
            final List<String> observed = new ArrayList<>();

            CustomerRecordMapper.toRecord(customerWithout(12), envelope -> {
                observed.add(envelope);
                return reveal(envelope);
            });

            assertThat(observed).hasSize(1);
            assertThat(reveal(observed.get(0))).isEqualTo("00000000000049368437");
        }

        @Test
        @DisplayName("a present national identifier is emitted through the numeric path")
        void aPresentNationalIdentifierIsEmittedThroughTheNumericPath() {
            final String[] attributes = nominalAttributes();
            attributes[12] = seal("123");

            final String emitted = CustomerRecordMapper.toRecord(customerFrom(attributes),
                    revealer());

            assertThat(emitted.substring(CustomerRecordMapper.CUST_SSN_OFFSET,
                    CustomerRecordMapper.GOVT_ISSUED_ID_OFFSET)).isEqualTo("000000123");
        }

        @Test
        @DisplayName("a re-decode of an emitted image recovers every plain attribute")
        void aReDecodeRecoversEveryPlainAttribute() throws IOException {
            for (final String image : allFixtureRecords()) {
                final Customer original = CustomerRecordMapper.fromRecord(image, sealer());
                final Customer reDecoded = CustomerRecordMapper.fromRecord(
                        CustomerRecordMapper.toRecord(original, revealer()), sealer());

                assertThat(reDecoded.getCustId()).isEqualTo(original.getCustId());
                assertThat(reDecoded.getFirstName()).isEqualTo(original.getFirstName());
                assertThat(reDecoded.getMiddleName()).isEqualTo(original.getMiddleName());
                assertThat(reDecoded.getLastName()).isEqualTo(original.getLastName());
                assertThat(reDecoded.getAddrLine1()).isEqualTo(original.getAddrLine1());
                assertThat(reDecoded.getAddrLine2()).isEqualTo(original.getAddrLine2());
                assertThat(reDecoded.getAddrLine3()).isEqualTo(original.getAddrLine3());
                assertThat(reDecoded.getAddrStateCd()).isEqualTo(original.getAddrStateCd());
                assertThat(reDecoded.getAddrCountryCd()).isEqualTo(original.getAddrCountryCd());
                assertThat(reDecoded.getAddrZip()).isEqualTo(original.getAddrZip());
                assertThat(reDecoded.getPhoneNum1()).isEqualTo(original.getPhoneNum1());
                assertThat(reDecoded.getPhoneNum2()).isEqualTo(original.getPhoneNum2());
                assertThat(reDecoded.getCustDob()).isEqualTo(original.getCustDob());
                assertThat(reDecoded.getEftAccountId()).isEqualTo(original.getEftAccountId());
                assertThat(reDecoded.getPriCardHolderInd())
                        .isEqualTo(original.getPriCardHolderInd());
                assertThat(reDecoded.getFicoCreditScore())
                        .isEqualTo(original.getFicoCreditScore());
            }
        }

        @Test
        @DisplayName("a re-decode also recovers both regulated attributes through the envelope")
        void aReDecodeRecoversBothRegulatedAttributes() throws IOException {
            final Customer original =
                    CustomerRecordMapper.fromRecord(fixtureRecord(0), sealer());

            final Customer reDecoded = CustomerRecordMapper.fromRecord(
                    CustomerRecordMapper.toRecord(original, revealer()), sealer());

            assertThat(reveal(reDecoded.getCustSsn())).isEqualTo(reveal(original.getCustSsn()));
            assertThat(reveal(reDecoded.getGovtIssuedId()))
                    .isEqualTo(reveal(original.getGovtIssuedId()));
        }

        @Test
        @DisplayName("each of the three address lines lands at its own offset, not another's")
        void eachAddressLineLandsAtItsOwnOffset() {
            final String[] attributes = nominalAttributes();
            attributes[4] = "LINE-ONE";
            attributes[5] = "LINE-TWO";
            attributes[6] = "LINE-THREE";

            final String emitted = CustomerRecordMapper.toRecord(customerFrom(attributes),
                    revealer());

            assertThat(emitted.substring(CustomerRecordMapper.ADDR_LINE_1_OFFSET,
                    CustomerRecordMapper.ADDR_LINE_2_OFFSET))
                    .isEqualTo(spacePadded("LINE-ONE", CustomerRecordMapper.ADDR_LINE_1_LENGTH));
            assertThat(emitted.substring(CustomerRecordMapper.ADDR_LINE_2_OFFSET,
                    CustomerRecordMapper.ADDR_LINE_3_OFFSET))
                    .isEqualTo(spacePadded("LINE-TWO", CustomerRecordMapper.ADDR_LINE_2_LENGTH));
            assertThat(emitted.substring(CustomerRecordMapper.ADDR_LINE_3_OFFSET,
                    CustomerRecordMapper.ADDR_STATE_CD_OFFSET))
                    .isEqualTo(spacePadded("LINE-THREE", CustomerRecordMapper.ADDR_LINE_3_LENGTH));
        }
    }

    // =================================================================================
    // Refusals and their diagnostics
    // =================================================================================

    @Nested
    @DisplayName("Refusals and their diagnostics")
    class RefusalsAndTheirDiagnostics {

        @Test
        @DisplayName("a null record image is refused by both whole-image readers")
        void aNullRecordImageIsRefusedByBothWholeImageReaders() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper
                            .fromRecord((String) null, sealer()))
                    .withMessage("recordImage must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper
                            .fromRecord((byte[]) null, sealer()))
                    .withMessage("recordImage must not be null");
        }

        @Test
        @DisplayName("a null buffer is refused by the slicing reader under its own parameter name")
        void aNullBufferIsRefusedByTheSlicingReader() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(null, 0, sealer()))
                    .withMessage("buffer must not be null");
        }

        @Test
        @DisplayName("an absent sealer is refused by all three readers")
        void anAbsentSealerIsRefusedByAllThreeReaders() throws IOException {
            final String image = fixtureRecord(0);
            final byte[] bytes = image.getBytes(StandardCharsets.US_ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(image, null))
                    .withMessage("regulatedFieldSealer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(bytes, null))
                    .withMessage("regulatedFieldSealer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(bytes, 0, null))
                    .withMessage("regulatedFieldSealer must not be null");
        }

        @Test
        @DisplayName("an absent revealer is refused by both emitters")
        void anAbsentRevealerIsRefusedByBothEmitters() {
            final Customer customer = aNominalCustomer();

            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, null))
                    .withMessage("regulatedFieldRevealer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecordBytes(customer, null))
                    .withMessage("regulatedFieldRevealer must not be null");
        }

        @Test
        @DisplayName("a null customer is refused by both emitters")
        void aNullCustomerIsRefusedByBothEmitters() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(null, revealer()))
                    .withMessage("customer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecordMapper.toRecordBytes(null, revealer()))
                    .withMessage("customer must not be null");
        }

        @Test
        @DisplayName("a sealer that drops a value is refused rather than allowed to lose it")
        void aSealerThatDropsAValueIsRefused() throws IOException {
            final String image = fixtureRecord(0);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(image, cleartext -> null))
                    .withMessage("the sealing operation returned no value for "
                            + CustomerRecordMapper.ARTEFACT + " field 'CUST-SSN'; a regulated value"
                            + " must be sealed into the module's protected-value envelope, never"
                            + " dropped");
        }

        @Test
        @DisplayName("the dropped-seal refusal does not echo the cleartext it was handed")
        void theDroppedSealRefusalDoesNotEchoTheCleartext() throws IOException {
            final String image = fixtureRecord(0);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(image, cleartext -> null))
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain("020973888"));
        }

        @Test
        @DisplayName("a revealer that drops a value is refused rather than leaving a field unwritten")
        void aRevealerThatDropsAValueIsRefused() {
            final Customer customer = aNominalCustomer();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, envelope -> null))
                    .withMessage("the revealing operation returned no value for "
                            + CustomerRecordMapper.ARTEFACT + " field 'CUST-SSN'; a fixed-width"
                            + " field cannot be left unwritten, and a record is never partially"
                            + " composed");
        }

        @Test
        @DisplayName("a dropped reveal on the government identifier names that field instead")
        void aDroppedRevealOnTheGovernmentIdentifierNamesThatField() {
            final Customer customer = customerWithout(12);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, envelope -> null))
                    .withMessageContaining("CUST-GOVT-ISSUED-ID")
                    .withMessageContaining("a record is never partially composed");
        }

        @Test
        @DisplayName("the dropped-reveal refusal does not echo the envelope it was handed")
        void theDroppedRevealRefusalDoesNotEchoTheEnvelope() {
            final Customer customer = aNominalCustomer();
            final String envelope = customer.getCustSsn();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, ignored -> null))
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain(envelope));
        }

        @ParameterizedTest
        @CsvSource({
            "0,  custId,           CUST-ID",
            "1,  firstName,        CUST-FIRST-NAME",
            "2,  middleName,       CUST-MIDDLE-NAME",
            "3,  lastName,         CUST-LAST-NAME",
            "4,  addrLine1,        CUST-ADDR-LINE-1",
            "5,  addrLine2,        CUST-ADDR-LINE-2",
            "6,  addrLine3,        CUST-ADDR-LINE-3",
            "7,  addrStateCd,      CUST-ADDR-STATE-CD",
            "8,  addrCountryCd,    CUST-ADDR-COUNTRY-CD",
            "9,  addrZip,          CUST-ADDR-ZIP",
            "10, phoneNum1,        CUST-PHONE-NUM-1",
            "11, phoneNum2,        CUST-PHONE-NUM-2",
            "14, custDob,          CUST-DOB-YYYY-MM-DD",
            "15, eftAccountId,     CUST-EFT-ACCOUNT-ID",
            "16, priCardHolderInd, CUST-PRI-CARD-HOLDER-IND",
            "17, ficoCreditScore,  CUST-FICO-CREDIT-SCORE",
        })
        @DisplayName("each absent non-nullable attribute is refused by property and copybook field")
        void eachAbsentNonNullableAttributeIsRefusedByName(final int position,
                final String propertyName, final String fieldName) {
            final Customer customer = customerWithout(position);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, revealer()))
                    .withMessage("customer attribute '" + propertyName + "' is absent, so "
                            + CustomerRecordMapper.ARTEFACT + " field '" + fieldName + "' cannot be"
                            + " composed; every column behind this layout except the national"
                            + " identifier is not nullable");
        }

        @Test
        @DisplayName("an absent attribute is also refused by the byte emitter")
        void anAbsentAttributeIsAlsoRefusedByTheByteEmitter() {
            final Customer customer = customerWithout(1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecordBytes(customer, revealer()))
                    .withMessageContaining("firstName")
                    .withMessageContaining("CUST-FIRST-NAME");
        }

        @Test
        @DisplayName("the absent-attribute refusal never echoes another attribute's value")
        void theAbsentAttributeRefusalNeverEchoesAnotherValue() {
            final Customer customer = customerWithout(1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.toRecord(customer, revealer()))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain("Kessler");
                        assertThat(refusal.getMessage()).doesNotContain("020973888");
                        assertThat(refusal.getMessage()).doesNotContain(PROTECTED_VALUE_PREFIX);
                    });
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 332, 499, 600})
        @DisplayName("an image of the wrong width is refused rather than padded or truncated")
        void anImageOfTheWrongWidthIsRefused(final int width) {
            final String image = "0".repeat(width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(image, sealer()))
                    .withMessageContaining(CustomerRecordMapper.ARTEFACT)
                    .withMessageContaining("must be exactly "
                            + CustomerRecordMapper.RECORD_WIDTH + " encoded bytes")
                    .withMessageContaining("is " + width + " encoded bytes")
                    .withMessageContaining("never padded or truncated on input");
        }

        @Test
        @DisplayName("an image overlong by one byte is diagnosed as an unstripped line terminator")
        void anImageOverlongByOneByteHintsAtTheLineTerminator() throws IOException {
            final String withTerminator = fixtureRecord(0) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(withTerminator, sealer()))
                    .withMessageContaining("0x0A line terminator")
                    .withMessageContaining("record separator and never record content");
        }

        @Test
        @DisplayName("the width diagnostic reports lengths only and never echoes record content")
        void theWidthDiagnosticNeverEchoesRecordContent() {
            final String hostile = "SECRET\r\nINJECTED\u0000PAYLOAD";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(hostile, sealer()))
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
        @DisplayName("a value wider than its field is refused rather than truncated to fit")
        void aValueWiderThanItsFieldIsRefused() {
            final String[] attributes = nominalAttributes();
            attributes[7] = "NCX";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper
                            .toRecord(customerFrom(attributes), revealer()))
                    .withMessageContaining("CUST-ADDR-STATE-CD")
                    .withMessageContaining("field width is "
                            + CustomerRecordMapper.ADDR_STATE_CD_LENGTH + " encoded bytes")
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a character US-ASCII cannot represent is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            final String[] attributes = nominalAttributes();
            attributes[1] = "Immanu\u00e9l";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper
                            .toRecord(customerFrom(attributes), revealer()))
                    .withMessageContaining("CUST-FIRST-NAME")
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("must never be transcoded silently");
        }

        @Test
        @DisplayName("a slice that runs past the buffer end is refused with indices, not content")
        void aSliceRunningPastTheBufferEndIsRefused() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);
            final int tooFar = file.length - 10;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(file, tooFar, sealer()))
                    .withMessageContaining("does not fit inside the supplied buffer")
                    .withMessageContaining("from=" + tooFar)
                    .withMessageContaining("recordWidth=" + CustomerRecordMapper.RECORD_WIDTH)
                    .withMessageContaining("buffer length=" + file.length)
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain("Immanuel"));
        }

        @Test
        @DisplayName("a negative slice start is refused before any byte is read")
        void aNegativeSliceStartIsRefused() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CustomerRecordMapper.fromRecord(file, -1, sealer()))
                    .withMessageContaining("start index must not be negative")
                    .withMessageContaining("from=-1");
        }
    }
}
