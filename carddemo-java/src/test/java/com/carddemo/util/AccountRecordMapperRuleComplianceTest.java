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
import java.util.List;

import com.carddemo.domain.Account;
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
 * Unit tests for {@link AccountRecordMapper}, which maps the three-hundred-byte
 * {@code ACCOUNT-RECORD} declared by {@code app/cpy/CVACT01Y.cpy} onto {@link Account} and back.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>This layout carries five of the estate's ten zoned-decimal money fields.</strong>
 * {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} are each {@code PIC S9(10)V99},
 * twelve bytes of {@code USAGE DISPLAY} with the sign overpunched into the final byte. Two of them
 * are the operands of the overlimit test in {@code app/cbl/CBTRN02C.cbl} at line 403, which is
 * evaluated strictly left to right into a two-decimal field with no {@code ROUNDED} clause. A wrong
 * scale or a wrong rounding mode here would change which daily transactions the posting job rejects,
 * so the scale is asserted on every decode and the truncation direction is asserted against an
 * explicit half-even comparison rather than merely stated.
 *
 * <p><strong>The round trip is byte-identical, and that is the strongest available parity
 * evidence.</strong> All fifty sample records carry spaces in the hundred-and-seventy-eight-byte
 * trailing filler, which is exactly what the mapper emits, so a decode-then-encode cycle reproduces
 * every one of the three hundred bytes. The tests assert that over the whole file rather than over a
 * single record, because a single record cannot distinguish a correct offset table from one whose
 * errors happen to cancel on that row.
 *
 * <p><strong>The copybook misspelling is preserved on purpose.</strong> {@code CVACT01Y} declares
 * {@code ACCT-EXPIRAION-DATE}, missing the {@code T}. The mapper keeps that spelling in its offset
 * constant and in its diagnostics, because the constant names a position in a byte layout that must
 * stay compatible, while {@link Account} exposes the correctly spelled property. Both spellings are
 * asserted so that a well-meaning correction to either one is caught.
 *
 * <p><strong>Refusals here are {@link IllegalArgumentException}, not
 * {@link NullPointerException}.</strong> This mapper reports an absent value as an argument problem
 * and names both the copybook field and the Java property in the same message, which differs from the
 * sibling mappers that use {@code Objects.requireNonNull}. The tests assert the exception type as
 * well as the text so that the two conventions cannot be conflated.
 */
@DisplayName("AccountRecordMapper - the three-hundred-byte CVACT01Y record and its five money fields")
class AccountRecordMapperRuleComplianceTest {

    /** The fixture whose bytes are the decode authority for this layout. */
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/input/acctdata.txt");

    /** The record count the fixture carries, as measured from its byte length. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** The stride between fixture records: the record width plus one line terminator. */
    private static final int FIXTURE_STRIDE = AccountRecordMapper.RECORD_LENGTH + 1;

    /** The diagnostic subject the mapper names, which it holds privately. */
    private static final String ARTEFACT = "ACCOUNT-RECORD (CVACT01Y)";

    /** The postal code every sample account carries, at its declared width. */
    private static final String FIXTURE_ZIP = "A000000000";

    /** The group identifier every sample account carries: ten spaces, never trimmed. */
    private static final String FIXTURE_GROUP_ID = "          ";

    /**
     * Reads one record image from the fixture by ordinal, excluding the line terminator.
     *
     * @param ordinal the zero-based record position
     * @return the three-hundred-byte record image
     * @throws IOException when the fixture cannot be read
     */
    private static String fixtureRecord(final int ordinal) throws IOException {
        final byte[] file = Files.readAllBytes(FIXTURE);
        return new String(file, ordinal * FIXTURE_STRIDE, AccountRecordMapper.RECORD_LENGTH,
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
     * Builds an account whose twelve mapped attributes are all present. Arguments are laid out in
     * the copybook's own field order, in rows of four, so that a reader can check a call site against
     * the layout without counting positions.
     *
     * @param acctId the eleven-digit account identifier
     * @param acctActiveStatus the one-character active status
     * @param currBal the current balance
     * @param creditLimit the credit limit
     * @param cashCreditLimit the cash credit limit
     * @param openDate the open date
     * @param expirationDate the expiration date, spelled correctly on the entity
     * @param reissueDate the reissue date
     * @param currCycCredit the current cycle credit
     * @param currCycDebit the current cycle debit
     * @param addrZip the postal code
     * @param groupId the account group identifier
     * @return a fully populated account
     */
    private static Account anAccount(
            final String acctId, final String acctActiveStatus,
            final String currBal, final String creditLimit,
            final String cashCreditLimit, final String openDate,
            final String expirationDate, final String reissueDate,
            final String currCycCredit, final String currCycDebit,
            final String addrZip, final String groupId) {
        return new Account(acctId, acctActiveStatus,
                new BigDecimal(currBal), new BigDecimal(creditLimit),
                new BigDecimal(cashCreditLimit), openDate,
                expirationDate, reissueDate,
                new BigDecimal(currCycCredit), new BigDecimal(currCycDebit),
                addrZip, groupId);
    }

    /**
     * Builds a nominal account with every field present, for tests that vary only one thing.
     *
     * @return a fully populated account
     */
    private static Account aNominalAccount() {
        return anAccount("00000000001", "Y", "194.00", "2020.00", "1020.00", "2014-11-20",
                "2025-05-20", "2025-05-20", "0.00", "0.00", FIXTURE_ZIP, FIXTURE_GROUP_ID);
    }

    /**
     * Assembles a well-formed record image at the declared field widths with space filler.
     *
     * @param acctId the eleven-digit account identifier
     * @param status the one-character active status
     * @param currBalImage the twelve-byte current balance image
     * @return a three-hundred-byte record image
     */
    private static String recordImage(final String acctId, final String status,
            final String currBalImage) {
        return acctId + status + currBalImage
                + "00000020200{" + "00000010200{"
                + "2014-11-20" + "2025-05-20" + "2025-05-20"
                + "00000000000{" + "00000000000{"
                + FIXTURE_ZIP + FIXTURE_GROUP_ID
                + " ".repeat(AccountRecordMapper.FILLER_LENGTH);
    }

    /** The five zoned-decimal field offsets, in copybook order. */
    private static int[] monetaryOffsets() {
        return new int[] {
            AccountRecordMapper.ACCT_CURR_BAL_OFFSET,
            AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET,
            AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET,
            AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET,
            AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET,
        };
    }

    /**
     * Collects the five decoded amounts of an account, in copybook order.
     *
     * @param account the decoded account
     * @return the five amounts
     */
    private static List<BigDecimal> monetaryValuesOf(final Account account) {
        return List.of(account.getAcctCurrBal(), account.getAcctCreditLimit(),
                account.getAcctCashCreditLimit(), account.getAcctCurrCycCredit(),
                account.getAcctCurrCycDebit());
    }

    // =================================================================================
    // The published layout
    // =================================================================================

    @Nested
    @DisplayName("The published layout")
    class ThePublishedLayout {

        @Test
        @DisplayName("the record is three hundred bytes, split as an eleven-byte key and its data")
        void theRecordIsThreeHundredBytes() {
            assertThat(AccountRecordMapper.RECORD_LENGTH).isEqualTo(300);
            assertThat(AccountRecordMapper.KEY_LENGTH).isEqualTo(11);
            assertThat(AccountRecordMapper.DATA_LENGTH).isEqualTo(289);
            assertThat(AccountRecordMapper.KEY_LENGTH + AccountRecordMapper.DATA_LENGTH)
                    .isEqualTo(AccountRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key length is the width of the account identifier field")
        void theKeyLengthIsTheAccountIdentifierWidth() {
            assertThat(AccountRecordMapper.KEY_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_ID_LENGTH);
            assertThat(AccountRecordMapper.ACCT_ID_OFFSET).isZero();
        }

        @Test
        @DisplayName("the twelve mapped fields run contiguously from offset zero without a gap")
        void theTwelveFieldsRunContiguouslyFromZero() {
            assertThat(AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_ID_OFFSET
                            + AccountRecordMapper.ACCT_ID_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET
                            + AccountRecordMapper.ACCT_ACTIVE_STATUS_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_CURR_BAL_OFFSET
                            + AccountRecordMapper.ACCT_CURR_BAL_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET
                            + AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET
                            + AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET
                            + AccountRecordMapper.ACCT_OPEN_DATE_LENGTH);
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET
                            + AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET
                            + AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET
                            + AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET
                            + AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_GROUP_ID_OFFSET)
                    .isEqualTo(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET
                            + AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH);
        }

        @Test
        @DisplayName("the five money fields are each the twelve bytes PIC S9(10)V99 needs")
        void theFiveMoneyFieldsAreEachTwelveBytes() {
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_LENGTH).isEqualTo(12);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH).isEqualTo(12);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH).isEqualTo(12);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH).isEqualTo(12);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH).isEqualTo(12);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_10_V99).isEqualTo(12);
        }

        @Test
        @DisplayName("the three date fields are each ten bytes wide")
        void theThreeDateFieldsAreEachTenBytes() {
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_LENGTH).isEqualTo(10);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH).isEqualTo(10);
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("the mapped prefix ends where the group identifier ends")
        void theMappedPrefixEndsWhereTheGroupIdentifierEnds() {
            assertThat(AccountRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_GROUP_ID_OFFSET
                            + AccountRecordMapper.ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(122);
        }

        @Test
        @DisplayName("filler occupies the whole balance of the record")
        void fillerOccupiesTheBalanceOfTheRecord() {
            assertThat(AccountRecordMapper.FILLER_OFFSET)
                    .isEqualTo(AccountRecordMapper.MAPPED_PREFIX_LENGTH);
            assertThat(AccountRecordMapper.FILLER_LENGTH)
                    .isEqualTo(AccountRecordMapper.RECORD_LENGTH
                            - AccountRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(178);
        }

        @Test
        @DisplayName("the copybook's misspelled expiration field keeps its spelling in the layout")
        void theMisspelledExpirationFieldKeepsItsSpelling() throws NoSuchFieldException {
            assertThat(AccountRecordMapper.class.getField("ACCT_EXPIRAION_DATE_OFFSET")).isNotNull();
            assertThat(AccountRecordMapper.class.getField("ACCT_EXPIRAION_DATE_LENGTH")).isNotNull();
            assertThat(Account.class.getMethods())
                    .anySatisfy(method ->
                            assertThat(method.getName()).isEqualTo("getAcctExpirationDate"));
        }

        @Test
        @DisplayName("the mapper is a final class whose sole constructor is private")
        void theMapperIsAFinalClassWithAPrivateConstructor() throws NoSuchMethodException,
                InvocationTargetException, InstantiationException, IllegalAccessException {
            assertThat(Modifier.isFinal(AccountRecordMapper.class.getModifiers())).isTrue();

            final Constructor<?>[] constructors =
                    AccountRecordMapper.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();

            final Constructor<AccountRecordMapper> constructor =
                    AccountRecordMapper.class.getDeclaredConstructor();
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
        @DisplayName("the fixture carries fifty records of exactly the declared width")
        void theFixtureCarriesFiftyRecordsOfTheDeclaredWidth() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            assertThat(file).hasSize(FIXTURE_RECORD_COUNT * FIXTURE_STRIDE);
            assertThat(allFixtureRecords())
                    .hasSize(FIXTURE_RECORD_COUNT)
                    .allSatisfy(image ->
                            assertThat(image).hasSize(AccountRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("the first record decodes to the documented twelve attributes")
        void theFirstRecordDecodesToTheDocumentedAttributes() throws IOException {
            final Account account = AccountRecordMapper.fromRecord(fixtureRecord(0));

            assertThat(account.getAcctId()).isEqualTo("00000000001");
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo("194.00");
            assertThat(account.getAcctCreditLimit()).isEqualByComparingTo("2020.00");
            assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo("1020.00");
            assertThat(account.getAcctOpenDate()).isEqualTo("2014-11-20");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctReissueDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
            assertThat(account.getAcctAddrZip()).isEqualTo(FIXTURE_ZIP);
            assertThat(account.getAcctGroupId()).isEqualTo(FIXTURE_GROUP_ID);
        }

        @Test
        @DisplayName("every amount on every record decodes at monetary scale two")
        void everyAmountDecodesAtMonetaryScaleTwo() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(monetaryValuesOf(AccountRecordMapper.fromRecord(image)))
                        .hasSize(5)
                        .allSatisfy(amount -> assertThat(amount.scale())
                                .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE));
            }
        }

        @Test
        @DisplayName("every one of the fifty records decodes without refusal")
        void everyRecordDecodesWithoutRefusal() throws IOException {
            final List<Account> decoded = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                decoded.add(AccountRecordMapper.fromRecord(image));
            }

            assertThat(decoded).hasSize(FIXTURE_RECORD_COUNT).allSatisfy(account -> {
                assertThat(account.getAcctId()).hasSize(AccountRecordMapper.ACCT_ID_LENGTH);
                assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
                assertThat(monetaryValuesOf(account)).doesNotContainNull();
            });
        }

        @Test
        @DisplayName("the fifty account identifiers are one through fifty, zero-filled to eleven")
        void theFiftyAccountIdentifiersRunOneThroughFifty() throws IOException {
            final List<String> identifiers = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                identifiers.add(AccountRecordMapper.fromRecord(image).getAcctId());
            }

            assertThat(identifiers).doesNotHaveDuplicates();
            assertThat(identifiers.get(0)).isEqualTo("00000000001");
            assertThat(identifiers.get(FIXTURE_RECORD_COUNT - 1)).isEqualTo("00000000050");
        }

        @Test
        @DisplayName("the group identifier decodes as ten spaces and is not trimmed away")
        void theGroupIdentifierDecodesAsSpacesAndIsNotTrimmed() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(AccountRecordMapper.fromRecord(image).getAcctGroupId())
                        .hasSize(AccountRecordMapper.ACCT_GROUP_ID_LENGTH)
                        .isBlank();
            }
        }

        @Test
        @DisplayName("the postal code decodes verbatim at its declared width")
        void thePostalCodeDecodesVerbatim() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(AccountRecordMapper.fromRecord(image).getAcctAddrZip())
                        .isEqualTo(FIXTURE_ZIP)
                        .hasSize(AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH);
            }
        }

        @Test
        @DisplayName("every sampled amount carries the positive overpunch for a zero final digit")
        void everySampledAmountCarriesAPositiveOverpunch() throws IOException {
            for (final String image : allFixtureRecords()) {
                for (final int offset : monetaryOffsets()) {
                    assertThat(image.charAt(offset + AccountRecordMapper.ACCT_CURR_BAL_LENGTH - 1))
                            .isEqualTo('{');
                }
                assertThat(monetaryValuesOf(AccountRecordMapper.fromRecord(image)))
                        .allSatisfy(amount -> assertThat(amount.signum()).isNotNegative());
            }
        }

        @Test
        @DisplayName("decoding from a byte array agrees with decoding from a string")
        void decodingFromBytesAgreesWithDecodingFromAString() throws IOException {
            for (final String image : allFixtureRecords()) {
                final Account fromString = AccountRecordMapper.fromRecord(image);
                final Account fromBytes = AccountRecordMapper
                        .fromRecord(image.getBytes(StandardCharsets.US_ASCII));

                assertThat(fromBytes.getAcctId()).isEqualTo(fromString.getAcctId());
                assertThat(fromBytes.getAcctCurrBal()).isEqualTo(fromString.getAcctCurrBal());
                assertThat(fromBytes.getAcctGroupId()).isEqualTo(fromString.getAcctGroupId());
            }
        }

        @Test
        @DisplayName("decoding a slice of the whole file agrees with decoding the isolated record")
        void decodingASliceAgreesWithTheIsolatedRecord() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            for (int ordinal = 0; ordinal < FIXTURE_RECORD_COUNT; ordinal++) {
                final Account sliced =
                        AccountRecordMapper.fromRecord(file, ordinal * FIXTURE_STRIDE);
                final Account isolated = AccountRecordMapper.fromRecord(fixtureRecord(ordinal));

                assertThat(sliced.getAcctId()).isEqualTo(isolated.getAcctId());
                assertThat(sliced.getAcctCurrBal()).isEqualTo(isolated.getAcctCurrBal());
                assertThat(sliced.getAcctExpirationDate())
                        .isEqualTo(isolated.getAcctExpirationDate());
            }
        }

        @Test
        @DisplayName("the fixture's own filler is spaces, which is what the mapper emits")
        void theFixtureFillerIsSpaces() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(image.substring(AccountRecordMapper.FILLER_OFFSET))
                        .hasSize(AccountRecordMapper.FILLER_LENGTH)
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
                assertThat(AccountRecordMapper
                        .toRecord(AccountRecordMapper.fromRecord(image)))
                        .isEqualTo(image);
            }
        }

        @Test
        @DisplayName("the byte-form cycle is byte-identical too")
        void theByteFormCycleIsByteIdentical() throws IOException {
            for (final String image : allFixtureRecords()) {
                final byte[] original = image.getBytes(StandardCharsets.US_ASCII);

                assertThat(AccountRecordMapper
                        .toRecordBytes(AccountRecordMapper.fromRecord(original)))
                        .isEqualTo(original);
            }
        }

        @Test
        @DisplayName("the byte emitter agrees with the string emitter")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final Account account = aNominalAccount();

            assertThat(AccountRecordMapper.toRecordBytes(account))
                    .isEqualTo(AccountRecordMapper.toRecord(account)
                            .getBytes(StandardCharsets.US_ASCII))
                    .hasSize(AccountRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the account identifier is right-justified and zero-padded, because it is numeric")
        void theAccountIdentifierIsZeroPadded() {
            final Account account = anAccount("7", "Y", "1.00", "2.00", "3.00", "2014-11-20",
                    "2025-05-20", "2025-05-20", "0.00", "0.00", FIXTURE_ZIP, FIXTURE_GROUP_ID);

            assertThat(AccountRecordMapper.toRecord(account)
                    .substring(AccountRecordMapper.ACCT_ID_OFFSET,
                            AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET))
                    .isEqualTo("00000000007");
        }

        @Test
        @DisplayName("the character fields are left-justified and space-padded")
        void theCharacterFieldsAreSpacePadded() {
            final Account account = anAccount("00000000001", "Y", "1.00", "2.00", "3.00", "2014",
                    "2025", "2026", "0.00", "0.00", "Z", "G");

            final String emitted = AccountRecordMapper.toRecord(account);

            assertThat(emitted.substring(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET,
                    AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET))
                    .isEqualTo(spacePadded("2014", AccountRecordMapper.ACCT_OPEN_DATE_LENGTH));
            assertThat(emitted.substring(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET,
                    AccountRecordMapper.ACCT_GROUP_ID_OFFSET))
                    .isEqualTo(spacePadded("Z", AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH));
            assertThat(emitted.substring(AccountRecordMapper.ACCT_GROUP_ID_OFFSET,
                    AccountRecordMapper.MAPPED_PREFIX_LENGTH))
                    .isEqualTo(spacePadded("G", AccountRecordMapper.ACCT_GROUP_ID_LENGTH));
        }

        @Test
        @DisplayName("the emitted filler is a hundred and seventy-eight spaces")
        void theEmittedFillerIsSpaces() {
            assertThat(AccountRecordMapper.toRecord(aNominalAccount())
                    .substring(AccountRecordMapper.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(AccountRecordMapper.FILLER_LENGTH));
        }

        @ParameterizedTest
        @CsvSource({
            "194.00, 00000001940{",
            "0.00, 00000000000{",
            "0.01, 00000000000A",
            "0.09, 00000000000I",
            "-0.01, 00000000000J",
            "-194.00, 00000001940}",
            "-0.09, 00000000000R",
            "9999999999.99, 99999999999I",
        })
        @DisplayName("the current balance carries the overpunched sign its value calls for")
        void theCurrentBalanceCarriesTheOverpunchedSign(final String balance,
                final String expectedImage) {
            final Account account = anAccount("00000000001", "Y", balance, "2.00", "3.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "0.00", "0.00", FIXTURE_ZIP,
                    FIXTURE_GROUP_ID);

            assertThat(AccountRecordMapper.toRecord(account)
                    .substring(AccountRecordMapper.ACCT_CURR_BAL_OFFSET,
                            AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET))
                    .isEqualTo(expectedImage);
        }

        @ParameterizedTest
        @CsvSource({
            "194.006, 00000001940{",
            "194.009, 00000001940{",
            "194.005, 00000001940{",
            "-194.006, 00000001940}",
            "-194.009, 00000001940}",
            "0.009, 00000000000{",
        })
        @DisplayName("a balance finer than a cent truncates toward zero, never rounds half-even")
        void aFinerBalanceTruncatesTowardZero(final String balance, final String expectedImage) {
            final Account account = anAccount("00000000001", "Y", balance, "2.00", "3.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "0.00", "0.00", FIXTURE_ZIP,
                    FIXTURE_GROUP_ID);

            final String emitted = AccountRecordMapper.toRecord(account)
                    .substring(AccountRecordMapper.ACCT_CURR_BAL_OFFSET,
                            AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET);

            assertThat(emitted).isEqualTo(expectedImage);

            final String halfEvenImage = ZonedDecimalCodec.encodeMonetary(
                    new BigDecimal(balance).setScale(ZonedDecimalCodec.MONETARY_SCALE,
                            RoundingMode.HALF_EVEN),
                    AccountRecordMapper.ACCT_CURR_BAL_LENGTH, "comparison");
            if (!halfEvenImage.equals(expectedImage)) {
                assertThat(emitted).isNotEqualTo(halfEvenImage);
            }
        }

        @Test
        @DisplayName("a negative-zero balance image decodes to an unsigned zero at monetary scale")
        void aNegativeZeroBalanceImageDecodesToAnUnsignedZero() {
            final Account account = AccountRecordMapper
                    .fromRecord(recordImage("00000000001", "Y", "00000000000}"));

            assertThat(account.getAcctCurrBal().signum()).isZero();
            assertThat(account.getAcctCurrBal().scale())
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
        }

        @Test
        @DisplayName("a re-decode of an emitted image recovers all twelve attributes")
        void aReDecodeRecoversAllTwelveAttributes() throws IOException {
            for (final String image : allFixtureRecords()) {
                final Account original = AccountRecordMapper.fromRecord(image);
                final Account reDecoded = AccountRecordMapper
                        .fromRecord(AccountRecordMapper.toRecord(original));

                assertThat(reDecoded.getAcctId()).isEqualTo(original.getAcctId());
                assertThat(reDecoded.getAcctActiveStatus())
                        .isEqualTo(original.getAcctActiveStatus());
                assertThat(reDecoded.getAcctCurrBal()).isEqualTo(original.getAcctCurrBal());
                assertThat(reDecoded.getAcctCreditLimit()).isEqualTo(original.getAcctCreditLimit());
                assertThat(reDecoded.getAcctCashCreditLimit())
                        .isEqualTo(original.getAcctCashCreditLimit());
                assertThat(reDecoded.getAcctOpenDate()).isEqualTo(original.getAcctOpenDate());
                assertThat(reDecoded.getAcctExpirationDate())
                        .isEqualTo(original.getAcctExpirationDate());
                assertThat(reDecoded.getAcctReissueDate()).isEqualTo(original.getAcctReissueDate());
                assertThat(reDecoded.getAcctCurrCycCredit())
                        .isEqualTo(original.getAcctCurrCycCredit());
                assertThat(reDecoded.getAcctCurrCycDebit())
                        .isEqualTo(original.getAcctCurrCycDebit());
                assertThat(reDecoded.getAcctAddrZip()).isEqualTo(original.getAcctAddrZip());
                assertThat(reDecoded.getAcctGroupId()).isEqualTo(original.getAcctGroupId());
            }
        }

        @Test
        @DisplayName("each of the five money fields lands at its own offset, not another's")
        void eachMoneyFieldLandsAtItsOwnOffset() {
            final Account account = anAccount("00000000001", "Y", "1.00", "2.00", "3.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "4.00", "5.00", FIXTURE_ZIP,
                    FIXTURE_GROUP_ID);

            final String emitted = AccountRecordMapper.toRecord(account);
            final int width = AccountRecordMapper.ACCT_CURR_BAL_LENGTH;

            assertThat(emitted.substring(AccountRecordMapper.ACCT_CURR_BAL_OFFSET,
                    AccountRecordMapper.ACCT_CURR_BAL_OFFSET + width)).isEqualTo("00000000010{");
            assertThat(emitted.substring(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET,
                    AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET + width))
                    .isEqualTo("00000000020{");
            assertThat(emitted.substring(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET,
                    AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET + width))
                    .isEqualTo("00000000030{");
            assertThat(emitted.substring(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET,
                    AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET + width))
                    .isEqualTo("00000000040{");
            assertThat(emitted.substring(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET,
                    AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET + width))
                    .isEqualTo("00000000050{");
        }

        @Test
        @DisplayName("each of the three date fields lands at its own offset, not another's")
        void eachDateFieldLandsAtItsOwnOffset() {
            final Account account = anAccount("00000000001", "Y", "1.00", "2.00", "3.00",
                    "1111-11-11", "2222-22-22", "3333-33-33", "0.00", "0.00", FIXTURE_ZIP,
                    FIXTURE_GROUP_ID);

            final String emitted = AccountRecordMapper.toRecord(account);

            assertThat(emitted.substring(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET,
                    AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET)).isEqualTo("1111-11-11");
            assertThat(emitted.substring(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET,
                    AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET)).isEqualTo("2222-22-22");
            assertThat(emitted.substring(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET,
                    AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET)).isEqualTo("3333-33-33");
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
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((String) null))
                    .withMessage("recordImage must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((byte[]) null))
                    .withMessage("recordImage must not be null");
        }

        @Test
        @DisplayName("a null buffer is refused by the slicing reader under its own parameter name")
        void aNullBufferIsRefusedByTheSlicingReader() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(null, 0))
                    .withMessage("buffer must not be null");
        }

        @Test
        @DisplayName("a null account is refused by both emitters")
        void aNullAccountIsRefusedByBothEmitters() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.toRecord(null))
                    .withMessage("account must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.toRecordBytes(null))
                    .withMessage("account must not be null");
        }

        @ParameterizedTest
        @CsvSource({
            "ACCT-ID,             acctId",
            "ACCT-ACTIVE-STATUS,  acctActiveStatus",
            "ACCT-OPEN-DATE,      acctOpenDate",
            "ACCT-EXPIRAION-DATE, acctExpirationDate",
            "ACCT-REISSUE-DATE,   acctReissueDate",
            "ACCT-ADDR-ZIP,       acctAddrZip",
            "ACCT-GROUP-ID,       acctGroupId",
        })
        @DisplayName("each absent character field is refused by copybook field and Java property")
        void eachAbsentCharacterFieldIsRefusedByName(final String fieldName,
                final String propertyName) {
            final Account account = new Account(
                    "acctId".equals(propertyName) ? null : "00000000001",
                    "acctActiveStatus".equals(propertyName) ? null : "Y",
                    new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("3.00"),
                    "acctOpenDate".equals(propertyName) ? null : "2014-11-20",
                    "acctExpirationDate".equals(propertyName) ? null : "2025-05-20",
                    "acctReissueDate".equals(propertyName) ? null : "2025-05-20",
                    new BigDecimal("0.00"), new BigDecimal("0.00"),
                    "acctAddrZip".equals(propertyName) ? null : FIXTURE_ZIP,
                    "acctGroupId".equals(propertyName) ? null : FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(account))
                    .withMessage(ARTEFACT + " field '" + fieldName + "' (Account." + propertyName
                            + ") must not be null: a fixed-width record has no representation for an"
                            + " absent value, so an unset character field is presented as spaces"
                            + " rather than as null");
        }

        @ParameterizedTest
        @CsvSource({
            "ACCT-CURR-BAL,          acctCurrBal",
            "ACCT-CREDIT-LIMIT,      acctCreditLimit",
            "ACCT-CASH-CREDIT-LIMIT, acctCashCreditLimit",
            "ACCT-CURR-CYC-CREDIT,   acctCurrCycCredit",
            "ACCT-CURR-CYC-DEBIT,    acctCurrCycDebit",
        })
        @DisplayName("each absent amount is refused with the zoned-decimal wording")
        void eachAbsentAmountIsRefusedByName(final String fieldName, final String propertyName) {
            final Account account = new Account("00000000001", "Y",
                    "acctCurrBal".equals(propertyName) ? null : new BigDecimal("1.00"),
                    "acctCreditLimit".equals(propertyName) ? null : new BigDecimal("2.00"),
                    "acctCashCreditLimit".equals(propertyName) ? null : new BigDecimal("3.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20",
                    "acctCurrCycCredit".equals(propertyName) ? null : new BigDecimal("0.00"),
                    "acctCurrCycDebit".equals(propertyName) ? null : new BigDecimal("0.00"),
                    FIXTURE_ZIP, FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(account))
                    .withMessage(ARTEFACT + " field '" + fieldName + "' (Account." + propertyName
                            + ") must not be null: a zoned-decimal field has no representation for"
                            + " an absent amount, so an unset amount is presented as zero rather"
                            + " than as null");
        }

        @Test
        @DisplayName("an absent amount is also refused by the byte emitter")
        void anAbsentAmountIsAlsoRefusedByTheByteEmitter() {
            final Account account = new Account("00000000001", "Y", null, new BigDecimal("2.00"),
                    new BigDecimal("3.00"), "2014-11-20", "2025-05-20", "2025-05-20",
                    new BigDecimal("0.00"), new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecordBytes(account))
                    .withMessageContaining("ACCT-CURR-BAL")
                    .withMessageContaining("Account.acctCurrBal");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 122, 299, 350})
        @DisplayName("an image of the wrong width is refused rather than padded or truncated")
        void anImageOfTheWrongWidthIsRefused(final int width) {
            final String image = "0".repeat(width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(image))
                    .withMessageContaining(ARTEFACT)
                    .withMessageContaining("must be exactly "
                            + AccountRecordMapper.RECORD_LENGTH + " encoded bytes in US-ASCII")
                    .withMessageContaining("image is " + width + " encoded bytes")
                    .withMessageContaining("never padded or truncated to fit");
        }

        @Test
        @DisplayName("an image overlong by one byte cites the sample file's three-hundred-and-one stride")
        void anImageOverlongByOneByteCitesTheFixtureStride() throws IOException {
            final String withTerminator = fixtureRecord(0) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("unstripped 0x0A line terminator")
                    .withMessageContaining("stride is " + FIXTURE_STRIDE + " bytes")
                    .withMessageContaining("only the leading "
                            + AccountRecordMapper.RECORD_LENGTH + " are the record");
        }

        @Test
        @DisplayName("an image short by any amount carries no line-terminator hint")
        void aShortImageCarriesNoTerminatorHint() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper
                            .fromRecord("0".repeat(AccountRecordMapper.RECORD_LENGTH - 1)))
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain("0x0A"));
        }

        @Test
        @DisplayName("a byte array of the wrong length is refused by the same diagnostic")
        void aByteArrayOfTheWrongLengthIsRefused() {
            final byte[] shortImage = new byte[AccountRecordMapper.RECORD_LENGTH - 1];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(shortImage))
                    .withMessageContaining("image is " + shortImage.length + " encoded bytes");
        }

        @Test
        @DisplayName("the width diagnostic reports lengths only and never echoes record content")
        void theWidthDiagnosticNeverEchoesRecordContent() {
            final String hostile = "SECRET\r\nINJECTED\u0000PAYLOAD";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(hostile))
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
        @DisplayName("a non-digit inside an amount names the offending position, not the record")
        void aNonDigitInsideAnAmountNamesThePosition() {
            final String image = recordImage("00000000001", "Y", "0000000 940{");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(image))
                    .withMessageContaining("ACCT-CURR-BAL")
                    .withMessageContaining("zero-based offset 7")
                    .withMessageContaining("0x20")
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain(FIXTURE_ZIP));
        }

        @Test
        @DisplayName("an unrepresentable final byte in an amount is refused as a sign character")
        void anUnrepresentableFinalByteInAnAmountIsRefused() {
            final String image = recordImage("00000000001", "Y", "00000001940*");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(image))
                    .withMessageContaining("ACCT-CURR-BAL")
                    .withMessageContaining("overpunched sign character")
                    .withMessageContaining("zero-based offset "
                            + (AccountRecordMapper.ACCT_CURR_BAL_LENGTH - 1));
        }

        @Test
        @DisplayName("an amount needing more than eleven digits at scale two is refused")
        void anAmountNeedingMoreDigitsIsRefused() {
            final Account account = anAccount("00000000001", "Y", "99999999999.99", "2.00", "3.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "0.00", "0.00", FIXTURE_ZIP,
                    FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(account))
                    .withMessageContaining("ACCT-CURR-BAL")
                    .withMessageContaining("needs 13 digit(s) at scale "
                            + ZonedDecimalCodec.MONETARY_SCALE)
                    .withMessageContaining("field holds only "
                            + AccountRecordMapper.ACCT_CURR_BAL_LENGTH);
        }

        @Test
        @DisplayName("a character value wider than its field is refused rather than truncated")
        void aCharacterValueWiderThanItsFieldIsRefused() {
            final Account account = anAccount("00000000001", "Y", "1.00", "2.00", "3.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "0.00", "0.00",
                    "ELEVENCHARS", FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(account))
                    .withMessageContaining("ACCT-ADDR-ZIP")
                    .withMessageContaining("field width is "
                            + AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH + " encoded bytes")
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a character US-ASCII cannot represent is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            final Account account = anAccount("00000000001", "Y", "1.00", "2.00", "3.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "0.00", "0.00",
                    "Z\u00e9", FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(account))
                    .withMessageContaining("ACCT-ADDR-ZIP")
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("must never be transcoded silently");
        }

        @Test
        @DisplayName("a slice that runs past the buffer end is refused with indices, not content")
        void aSliceRunningPastTheBufferEndIsRefused() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);
            final int tooFar = file.length - 10;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(file, tooFar))
                    .withMessageContaining("does not fit inside the supplied buffer")
                    .withMessageContaining("from=" + tooFar)
                    .withMessageContaining("recordWidth=" + AccountRecordMapper.RECORD_LENGTH)
                    .withMessageContaining("buffer length=" + file.length);
        }

        @Test
        @DisplayName("a negative slice start is refused before any byte is read")
        void aNegativeSliceStartIsRefused() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(file, -1))
                    .withMessageContaining("start index must not be negative")
                    .withMessageContaining("from=-1");
        }
    }
}
