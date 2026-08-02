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

import com.carddemo.domain.Account;
import com.carddemo.support.SeededRecordFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Direct unit test for {@link AccountRecordMapper}, the mapper between the 300-byte legacy account
 * record of {@code app/cpy/CVACT01Y.cpy} and {@link Account}.
 *
 * <p><strong>Five zoned-decimal fields, and truncation is the contract.</strong> The account layout
 * carries five {@code PIC S9(10)V99} money fields, each twelve encoded bytes with the sign
 * overpunched into the final digit. No {@code ROUNDED} clause exists anywhere in the estate, so a
 * COBOL store truncates toward zero, and the decoded scale must be exactly two. Every assertion below
 * pins the scale and the re-encoded bytes rather than a rounded decimal value, because a half-even
 * substitution would differ by one cent and pass a test written under the same wrong assumption.
 *
 * <p><strong>The group identifier is ten spaces on every seeded row, and that is significant.</strong>
 * It is one of only three fields in the whole seed whose trailing blanks must survive: the interest
 * program compares it against a space-padded group key, so a trimmed value would change which branch
 * the accrual takes. The test asserts the full ten-byte width on all fifty records and asserts that
 * the round trip reproduces the blanks exactly.
 *
 * <p><strong>The misspelled expiry field keeps its offset.</strong> The copybook genuinely spells the
 * field {@code ACCT-EXPIRAION-DATE}, missing the {@code T}. The layout is authoritative, so the
 * constant preserves the misspelling and offset 58 while the Java property is spelled correctly. This
 * test asserts offset 58 explicitly so a well-meaning rename cannot shift the layout.
 *
 * <p><strong>Filler is spaces here, so the round trip is whole-record.</strong> Unlike the
 * reference-table fixtures, a census over all fifty records finds 8,900 filler bytes, every one a
 * space - the same character {@code toRecord} emits. The round-trip assertion is therefore an
 * unbounded 300-byte comparison, which is a strictly stronger check than the prefix bound the
 * reference-table mappers require.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("account record mapper")
class AccountRecordMapperTest {

    /** The shipped fixture measures 15,050 bytes: 50 records at a 301-byte stride. */
    private static final int SEEDED_RECORDS = 50;

    /** Fixture file name, resolved from the test classpath by the shared loader. */
    private static final String FIXTURE = "acctdata.txt";

    /** The group identifier every seeded account carries - ten spaces, and behaviourally significant. */
    private static final String BLANK_GROUP_IDENTIFIER = "          ";

    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE, AccountRecordMapper.RECORD_LENGTH);
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the twelve mapped fields and the filler exactly tile the 300-byte record with no "
                + "gap and no overlap")
        void theMappedFieldsAndFillerTileTheRecord() {
            assertThat(AccountRecordMapper.ACCT_ID_OFFSET + AccountRecordMapper.ACCT_ID_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET);
            assertThat(AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET
                    + AccountRecordMapper.ACCT_ACTIVE_STATUS_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_CURR_BAL_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_OFFSET
                    + AccountRecordMapper.ACCT_CURR_BAL_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET
                    + AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET
                    + AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET
                    + AccountRecordMapper.ACCT_OPEN_DATE_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET
                    + AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET
                    + AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET
                    + AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET
                    + AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET);
            assertThat(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET
                    + AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH)
                    .isEqualTo(AccountRecordMapper.ACCT_GROUP_ID_OFFSET);
            assertThat(AccountRecordMapper.ACCT_GROUP_ID_OFFSET
                    + AccountRecordMapper.ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(AccountRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(AccountRecordMapper.FILLER_OFFSET);
            assertThat(AccountRecordMapper.FILLER_OFFSET + AccountRecordMapper.FILLER_LENGTH)
                    .isEqualTo(AccountRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key and the remainder sum to the record width, matching the KEYS(11 0) "
                + "cluster declaration")
        void theKeyAndRemainderSumToTheRecordWidth() {
            assertThat(AccountRecordMapper.KEY_LENGTH).isEqualTo(11);
            assertThat(AccountRecordMapper.DATA_LENGTH).isEqualTo(289);
            assertThat(AccountRecordMapper.KEY_LENGTH + AccountRecordMapper.DATA_LENGTH)
                    .isEqualTo(AccountRecordMapper.RECORD_LENGTH);
            assertThat(AccountRecordMapper.ACCT_ID_OFFSET).isZero();
            assertThat(AccountRecordMapper.ACCT_ID_LENGTH).isEqualTo(AccountRecordMapper.KEY_LENGTH);
        }

        @Test
        @DisplayName("all five money fields declare the codec's own PIC S9(10)V99 width of twelve")
        void allFiveMoneyFieldsDeclareTheCodecWidth() {
            assertThat(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH)
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_10_V99)
                    .isEqualTo(12);
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("the misspelled expiry field keeps offset 58 and width 10, so the layout cannot "
                + "shift behind a corrected Java name")
        void theMisspelledExpiryFieldKeepsItsOffset() {
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET).isEqualTo(58);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("parsing the shipped fixture")
    class ParsingTheShippedFixture {

        @Test
        @DisplayName("the fixture holds exactly fifty records, each measuring the declared 300 bytes")
        void theFixtureHoldsFiftyRecordsAtTheDeclaredWidth() {
            final SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(loaded.recordWidth()).isEqualTo(AccountRecordMapper.RECORD_LENGTH);
            assertThat(loaded.impliedByteCount()).isEqualTo(15_050);
        }

        @Test
        @DisplayName("every character field equals the fixture slice taken at the mapper's own declared "
                + "offset, so all twelve offsets are proven against the authority layout")
        void everyCharacterFieldEqualsTheFixtureSliceAtTheDeclaredOffset() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final Account parsed = AccountRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getAcctId())
                        .as("id of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, AccountRecordMapper.ACCT_ID_OFFSET,
                                AccountRecordMapper.ACCT_ID_LENGTH));
                assertThat(parsed.getAcctActiveStatus())
                        .as("status of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET,
                                AccountRecordMapper.ACCT_ACTIVE_STATUS_LENGTH));
                assertThat(parsed.getAcctOpenDate())
                        .as("open date of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, AccountRecordMapper.ACCT_OPEN_DATE_OFFSET,
                                AccountRecordMapper.ACCT_OPEN_DATE_LENGTH));
                assertThat(parsed.getAcctExpirationDate())
                        .as("expiry date of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET,
                                AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH));
                assertThat(parsed.getAcctReissueDate())
                        .as("reissue date of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET,
                                AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH));
                assertThat(parsed.getAcctAddrZip())
                        .as("zip of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET,
                                AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH));
                assertThat(parsed.getAcctGroupId())
                        .as("group id of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal, AccountRecordMapper.ACCT_GROUP_ID_OFFSET,
                                AccountRecordMapper.ACCT_GROUP_ID_LENGTH));
            }
        }

        @Test
        @DisplayName("the eleven-digit identifier keeps its significant leading zeros")
        void theIdentifierKeepsItsLeadingZeros() {
            final SeededRecordFixture loaded = fixture();

            final Account first = AccountRecordMapper.fromRecord(loaded.record(1));

            assertThat(first.getAcctId())
                    .hasSize(AccountRecordMapper.ACCT_ID_LENGTH)
                    .isEqualTo("00000000001");
        }

        @Test
        @DisplayName("the group identifier arrives as ten spaces on every seeded record and is never "
                + "trimmed, because the accrual lookup compares it against a padded key")
        void theGroupIdentifierArrivesAsTenSpacesAndIsNeverTrimmed() {
            final SeededRecordFixture loaded = fixture();
            final Set<String> identifiers = new HashSet<>();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final Account parsed = AccountRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getAcctGroupId())
                        .as("group id width of record %d", ordinal)
                        .hasSize(AccountRecordMapper.ACCT_GROUP_ID_LENGTH);
                identifiers.add(parsed.getAcctGroupId());
            }

            assertThat(identifiers).containsExactly(BLANK_GROUP_IDENTIFIER);
            assertThat(BLANK_GROUP_IDENTIFIER).isBlank().isNotEmpty();
        }

        @Test
        @DisplayName("the optimistic-locking version is not carried by the record image, so the mapper "
                + "leaves it at its unsaved default")
        void theVersionIsNotCarriedByTheRecordImage() {
            final Account parsed = AccountRecordMapper.fromRecord(fixture().record(1));

            assertThat(parsed.getVersion()).isZero();
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points agree with the string entry point")
        void theByteEntryPointsAgreeWithTheStringEntryPoint() {
            final String image = fixture().record(1);
            final byte[] encoded = image.getBytes(StandardCharsets.US_ASCII);
            final byte[] framed = new byte[encoded.length + 11];
            System.arraycopy(encoded, 0, framed, 11, encoded.length);

            assertThat(AccountRecordMapper.toRecord(AccountRecordMapper.fromRecord(encoded)))
                    .isEqualTo(image);
            assertThat(AccountRecordMapper.toRecord(AccountRecordMapper.fromRecord(framed, 11)))
                    .isEqualTo(image);
        }
    }

    @Nested
    @DisplayName("the five zoned-decimal money fields")
    class TheFiveZonedDecimalMoneyFields {

        @Test
        @DisplayName("every decoded money value carries scale exactly two on every seeded record")
        void everyDecodedMoneyValueCarriesScaleTwo() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final Account parsed = AccountRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getAcctCurrBal().scale())
                        .as("balance scale of record %d", ordinal).isEqualTo(2);
                assertThat(parsed.getAcctCreditLimit().scale())
                        .as("credit-limit scale of record %d", ordinal).isEqualTo(2);
                assertThat(parsed.getAcctCashCreditLimit().scale())
                        .as("cash-limit scale of record %d", ordinal).isEqualTo(2);
                assertThat(parsed.getAcctCurrCycCredit().scale())
                        .as("cycle-credit scale of record %d", ordinal).isEqualTo(2);
                assertThat(parsed.getAcctCurrCycDebit().scale())
                        .as("cycle-debit scale of record %d", ordinal).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("the first record's overpunched images decode to their exact decimal values")
        void theFirstRecordsImagesDecodeExactly() {
            final SeededRecordFixture loaded = fixture();

            assertThat(loaded.field(1, AccountRecordMapper.ACCT_CURR_BAL_OFFSET,
                    AccountRecordMapper.ACCT_CURR_BAL_LENGTH)).isEqualTo("00000001940{");

            final Account first = AccountRecordMapper.fromRecord(loaded.record(1));

            assertThat(first.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("194.00"));
            assertThat(first.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("2020.00"));
            assertThat(first.getAcctCashCreditLimit()).isEqualByComparingTo(new BigDecimal("1020.00"));
            assertThat(first.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(first.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("re-encoding reproduces all five original overpunched images byte for byte")
        void reEncodingReproducesTheOriginalMoneyImages() {
            final SeededRecordFixture loaded = fixture();
            final int[][] windows = {
                {AccountRecordMapper.ACCT_CURR_BAL_OFFSET, AccountRecordMapper.ACCT_CURR_BAL_LENGTH},
                {AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET,
                    AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH},
                {AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET,
                    AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH},
                {AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET,
                    AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH},
                {AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET,
                    AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH},
            };

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String original = loaded.record(ordinal);
                final String emitted =
                        AccountRecordMapper.toRecord(AccountRecordMapper.fromRecord(original));

                for (final int[] window : windows) {
                    assertThat(emitted.substring(window[0], window[0] + window[1]))
                            .as("money window at offset %d of record %d", window[0], ordinal)
                            .isEqualTo(original.substring(window[0], window[0] + window[1]));
                }
            }
        }

        @Test
        @DisplayName("a negative balance, which the seed never carries, still encodes and decodes")
        void aNegativeBalanceStillRoundTrips() {
            final Account subject = AccountRecordMapper.fromRecord(fixture().record(1));
            subject.setAcctCurrBal(new BigDecimal("-4321.09"));

            final String emitted = AccountRecordMapper.toRecord(subject);

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(AccountRecordMapper.RECORD_LENGTH);
            assertThat(AccountRecordMapper.fromRecord(emitted).getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("-4321.09"));
        }
    }

    @Nested
    @DisplayName("emitting a record")
    class EmittingARecord {

        @Test
        @DisplayName("every one of the fifty fixture records round-trips byte-identically across the "
                + "whole 300-byte image, filler included, because this fixture pads with spaces")
        void everyFixtureRecordRoundTripsAcrossTheWholeImage() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String original = loaded.record(ordinal);

                final String emitted =
                        AccountRecordMapper.toRecord(AccountRecordMapper.fromRecord(original));

                assertThat(emitted)
                        .as("whole-record round trip of record %d", ordinal)
                        .isEqualTo(original);
                assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                        .as("emitted width of record %d", ordinal)
                        .hasSize(AccountRecordMapper.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the emitted filler is 178 spaces, matching this fixture's own filler")
        void theEmittedFillerIsSpaces() {
            final String original = fixture().record(1);
            final String emitted =
                    AccountRecordMapper.toRecord(AccountRecordMapper.fromRecord(original));

            assertThat(emitted.substring(AccountRecordMapper.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(AccountRecordMapper.FILLER_LENGTH))
                    .isEqualTo(original.substring(AccountRecordMapper.FILLER_OFFSET));
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 300 bytes as the string one")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final Account subject = AccountRecordMapper.fromRecord(fixture().record(1));

            assertThat(AccountRecordMapper.toRecordBytes(subject))
                    .isEqualTo(AccountRecordMapper.toRecord(subject)
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
                    .isThrownBy(() -> AccountRecordMapper.fromRecord("00000000001"))
                    .withMessageContaining(String.valueOf(AccountRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("a long image is refused rather than silently truncated")
        void aLongImageIsRefused() {
            final String overlong = "0".repeat(AccountRecordMapper.RECORD_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(overlong));
        }

        @Test
        @DisplayName("a byte range that runs past the end of the buffer is refused")
        void aByteRangePastTheEndIsRefused() {
            final byte[] encoded = fixture().record(1).getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(encoded, 1));
        }

        @Test
        @DisplayName("a null image raises deterministically rather than yielding a partial entity")
        void aNullImageRaisesDeterministically() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((byte[]) null));
        }
    }
}
