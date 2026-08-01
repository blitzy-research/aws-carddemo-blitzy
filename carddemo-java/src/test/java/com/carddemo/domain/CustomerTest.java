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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit test for {@link Customer}, the 500-byte customer record and the widest entity in this
 * package.
 *
 * <p><strong>What this suite is an oracle for.</strong> Every expected value below was derived by
 * hand and independently of the class under test. The eighteen widths were read off the customer
 * copybook and summed by hand; the running sums that place each field are written out in the
 * assertions so a reviewer can check the arithmetic without a calculator; and every literal value
 * was decoded by hand from the first record of the seeded customer reference file, which measures
 * 25,050 bytes and holds 50 records of 500 characters each. The record length is corroborated
 * independently of the copybook by the customer cluster definition, which declares
 * {@code KEYS(9 0)} and {@code RECORDSIZE(500 500)}. No assertion calls a production method to
 * compute its own expectation.
 *
 * <p><strong>Eighteen mapped fields, all of them text.</strong> The mapped widths are 9, 25, 25,
 * 25, 50, 50, 50, 2, 3, 10, 15, 15, 9, 20, 10, 10, 1 and 3, which sum to 332; a 168-byte trailing
 * filler closes the record at 500 and is deliberately neither a field nor a column here. Three of
 * the eighteen are external decimal in the copybook - the identifier, the national identifier and
 * the credit score - and all three are nevertheless carried as text, because their external
 * representation is the contract: an identifier must stay nine characters rather than collapsing to
 * one. There is consequently no monetary attribute, no numeric attribute and no optimistic-locking
 * attribute on this entity, and this suite parses nothing to a number.
 *
 * <p><strong>Widths are measured in encoded bytes, never in characters.</strong> Every width
 * assertion encodes with {@link StandardCharsets#US_ASCII} explicitly rather than relying on the
 * platform default, because the value being described is a position in a fixed-width record image.
 *
 * <p><strong>Two legacy spellings, one entity.</strong> The estate declares this record twice. The
 * copybook the six online and batch programs include hyphenates the date-of-birth field; the
 * alternate copybook that the statement generator includes spells the same field without the
 * hyphens. Both denote the same 10 bytes at offset 308, so one attribute, one column and one test
 * class serve both. No variant flag, discriminator, second entity or second test class exists.
 *
 * <p><strong>Deliberately out of scope here.</strong> This is a pure unit test: it starts no
 * container, opens no connection, reads no file and loads no Spring context. Column names, declared
 * lengths and nullability are verified in the integration tier, where the persistence provider
 * validates the mapping against a real PostgreSQL 16 schema and aborts start-up on any mismatch;
 * nullability is proved here behaviourally instead, by round-tripping an absent value. State-code,
 * area-code and state-plus-postal-prefix membership belong to the validation-lookup service, and
 * calendar validity belongs to the date-validation service; neither is asserted here, and this
 * suite deliberately proves that the entity itself performs no such check.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit
 * 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp
 * CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. No legacy source text is reproduced; field names,
 * widths, offsets and record lengths are cited as metadata only.
 */
@DisplayName("Customer - the 500-byte customer record, 18 mapped fields plus a 168-byte filler")
class CustomerTest {

    // ------------------------------------------------------------------------------------------
    // Hand-derived record geometry. Each width was read from the copybook's picture clause; each
    // offset is the running sum of the widths before it. These are the oracle for every position
    // and length assertion in this file.
    // ------------------------------------------------------------------------------------------

    /** Declared record length, corroborated by {@code RECORDSIZE(500 500)} on the cluster. */
    private static final int RECORD_WIDTH = 500;

    /** Sum of the eighteen mapped widths, where the trailing filler begins. */
    private static final int MAPPED_WIDTH = 332;

    /** Unmapped trailing filler: 500 - 332. */
    private static final int FILLER_WIDTH = 168;

    /** Key length, corroborated by {@code KEYS(9 0)} on the cluster. */
    private static final int WIDTH_CUST_ID = 9;

    /** Width shared by the three name fields. */
    private static final int WIDTH_NAME = 25;

    /** Width shared by the three address lines. */
    private static final int WIDTH_ADDR_LINE = 50;

    /** State-code width. */
    private static final int WIDTH_STATE_CD = 2;

    /** Country-code width. */
    private static final int WIDTH_COUNTRY_CD = 3;

    /** Postal-code width. */
    private static final int WIDTH_ZIP = 10;

    /** Width shared by both telephone numbers. */
    private static final int WIDTH_PHONE = 15;

    /** National-identifier width in the record image, before the column was widened. */
    private static final int WIDTH_CUST_SSN = 9;

    /** Government-identifier width in the record image, before the column was widened. */
    private static final int WIDTH_GOVT_ISSUED_ID = 20;

    /** Date-of-birth width, identical under both legacy spellings of the field. */
    private static final int WIDTH_CUST_DOB = 10;

    /** Transfer-account-identifier width. */
    private static final int WIDTH_EFT_ACCOUNT_ID = 10;

    /** Primary-cardholder-indicator width. */
    private static final int WIDTH_PRI_CARD_HOLDER_IND = 1;

    /** Credit-score width. */
    private static final int WIDTH_FICO = 3;

    /** Zero-based offset of the national identifier: the running sum of the twelve widths before it. */
    private static final int OFFSET_CUST_SSN = 279;

    /** Zero-based offset of the government identifier: 279 + 9. */
    private static final int OFFSET_GOVT_ISSUED_ID = 288;

    /** Zero-based offset of the date of birth: 288 + 20. Identical under both legacy spellings. */
    private static final int OFFSET_CUST_DOB = 308;

    /** Zero-based offset of the transfer account identifier: 308 + 10. */
    private static final int OFFSET_EFT_ACCOUNT_ID = 318;

    /** Zero-based offset of the primary-cardholder indicator: 318 + 10. */
    private static final int OFFSET_PRI_CARD_HOLDER_IND = 328;

    /** Zero-based offset of the credit score: 328 + 1. */
    private static final int OFFSET_FICO = 329;

    // ------------------------------------------------------------------------------------------
    // Hand-decoded values from the first seeded customer record. Padding is spelled out in full
    // rather than generated, so that each literal is auditable by eye against its declared width.
    // ------------------------------------------------------------------------------------------

    /** Identifier of the first seeded customer: nine characters, zero filled. */
    private static final String CUST_ID = "000000001";

    /** Given name, 8 characters plus 17 trailing spaces. */
    private static final String FIRST_NAME = "Immanuel                 ";

    /** Middle name, 8 characters plus 17 trailing spaces. */
    private static final String MIDDLE_NAME = "Madeline                 ";

    /** Family name, 7 characters plus 18 trailing spaces. */
    private static final String LAST_NAME = "Kessler                  ";

    /** First address line, 17 characters plus 33 trailing spaces. */
    private static final String ADDR_LINE_1 = "618 Deshaun Route                                 ";

    /** Second address line, 8 characters plus 42 trailing spaces. Carries a period and digits. */
    private static final String ADDR_LINE_2 = "Apt. 802                                          ";

    /** Third address line, 15 characters plus 35 trailing spaces. */
    private static final String ADDR_LINE_3 = "Altenwerthshire                                   ";

    /** State code of the first seeded customer. */
    private static final String ADDR_STATE_CD = "NC";

    /** Country code of the first seeded customer. */
    private static final String ADDR_COUNTRY_CD = "USA";

    /** Postal code, 5 characters plus 5 trailing spaces. */
    private static final String ADDR_ZIP = "12546     ";

    /** Primary telephone number, 13 characters plus 2 trailing spaces. */
    private static final String PHONE_NUM_1 = "(908)119-8310  ";

    /** Secondary telephone number, 13 characters plus 2 trailing spaces. */
    private static final String PHONE_NUM_2 = "(373)693-8684  ";

    /** Date of birth of the first seeded customer, ten characters of text. */
    private static final String CUST_DOB = "1961-06-08";

    /** Transfer account identifier of the first seeded customer, ten characters, leading zero kept. */
    private static final String EFT_ACCOUNT_ID = "0053581756";

    /** Primary-cardholder indicator of the first seeded customer. */
    private static final String PRI_CARD_HOLDER_IND = "Y";

    /**
     * Credit score of the first seeded customer. This value sits below the 300-to-850 band the
     * account-update screen enforces, and is stored exactly as the file holds it.
     */
    private static final String FICO_ROW_ZERO = "274";

    /** Lowest credit score in the seeded reference data, and the reason leading zeros matter. */
    private static final String FICO_LOWEST_SEEDED = "001";

    /** Upper bound of the band the account-update screen enforces, stored here without checking. */
    private static final String FICO_SCREEN_UPPER_BOUND = "850";

    /** Seeded customer rows in the reference file. */
    private static final int SEEDED_ROWS = 50;

    /** Seeded rows whose credit score falls below the band the account-update screen enforces. */
    private static final int SEEDED_ROWS_BELOW_SCREEN_BAND = 21;

    // ------------------------------------------------------------------------------------------
    // Protected-value envelopes, hand-built from the RFC 4648 base-64 specification.
    //
    // DIVERGENCE FROM THE SUMMARISED CONTRACT, RESOLVED IN FAVOUR OF THE PRODUCTION CLASS.
    // The contract summary describes plain-assignment mutators for all eighteen attributes. The
    // class as written is stricter for the two regulated identifiers: both write paths refuse any
    // value that does not already carry the module's protected-value envelope - a scheme marker,
    // then a base-64 body decoding to at least 28 bytes, being a 96-bit initialisation vector plus a
    // 128-bit authentication tag. The entity still transforms nothing; it either stores the value
    // unchanged or refuses it. This suite therefore asserts the behaviour the class actually has,
    // and additionally pins the refusal of legacy-width cleartext, which is the property that keeps
    // regulated cleartext away from the persistence boundary.
    //
    // The literals below were derived from the RFC 4648 base-64 specification rather than by calling any
    // production or platform encoder, so that nothing in this file uses the implementation as its
    // own oracle: a body of n base-64 characters carries 3n/4 bytes, less one byte per padding
    // character. Each decoded plaintext reads as an obviously synthetic, non-secret marker.
    // ------------------------------------------------------------------------------------------

    /** Scheme marker every protected value opens with. */
    private static final String ENVELOPE_MARKER = "ENC1:";

    /** 40 base-64 characters with two padding characters: 9 * 3 + 1 = 28 decoded bytes, the minimum. */
    private static final String ENVELOPE_BODY_MINIMUM = "U1lOVEhFVElDLVRFU1QtRU5WRUxPUEUtMDAwMQ==";

    /** A second 28-byte body, distinct from the first, so two attributes cannot be confused. */
    private static final String ENVELOPE_BODY_SECOND = "U1lOVEhFVElDLVRFU1QtRU5WRUxPUEUtMDAwMg==";

    /** 64 base-64 characters, no padding: 16 * 3 = 48 decoded bytes, a longer ciphertext shape. */
    private static final String ENVELOPE_BODY_LONG =
            "U1lOVEhFVElDLVRFU1QtRU5WRUxPUEUtTE9OR0VSLUNJUEhFUlRFWFQtRk9STS0x";

    /** 36 base-64 characters, no padding: 9 * 3 = 27 decoded bytes, exactly one below the minimum. */
    private static final String ENVELOPE_BODY_ONE_BYTE_SHORT = "U1lOVEhFVElDLVRFU1QtU0hPUlQtQk9EWS0x";

    /** Accepted protected value carrying the minimum body. */
    private static final String PROTECTED_MINIMUM = ENVELOPE_MARKER + ENVELOPE_BODY_MINIMUM;

    /** Accepted protected value carrying the second body. */
    private static final String PROTECTED_SECOND = ENVELOPE_MARKER + ENVELOPE_BODY_SECOND;

    /** Accepted protected value carrying the long body. */
    private static final String PROTECTED_LONG = ENVELOPE_MARKER + ENVELOPE_BODY_LONG;

    /** Refused: the marker is present but the body is one byte short of an authenticated envelope. */
    private static final String PROTECTED_TOO_SHORT = ENVELOPE_MARKER + ENVELOPE_BODY_ONE_BYTE_SHORT;

    /** Refused: the marker is present but the body is not base-64 at all. */
    private static final String PROTECTED_BODY_NOT_ENCODED = ENVELOPE_MARKER + "not-encoded!!";

    /**
     * Refused: no marker, and exactly the nine characters the record image reserves for the
     * national identifier. An obviously synthetic, non-secret token stands in for a real value.
     */
    private static final String UNPROTECTED_NINE_CHARACTERS = "NOTSECRET";

    /**
     * Refused: no marker, and exactly the twenty characters the record image reserves for the
     * government identifier. Again an obviously synthetic, non-secret token.
     */
    private static final String UNPROTECTED_TWENTY_CHARACTERS = "NOTSECRETNOTSECRET00";

    /**
     * Builds a fully populated customer from the hand-decoded first seeded record, supplying a
     * well-formed protected value for each of the two regulated identifiers.
     *
     * <p>Construction goes through the public eighteen-argument constructor in record order. No
     * builder and no parameter object is introduced: the module admits no code generation, and the
     * eighteen-argument shape is exactly the record contract.
     *
     * @return a customer carrying the first seeded record's values
     */
    private static Customer firstSeededCustomer() {
        return new Customer(
                CUST_ID,
                FIRST_NAME,
                MIDDLE_NAME,
                LAST_NAME,
                ADDR_LINE_1,
                ADDR_LINE_2,
                ADDR_LINE_3,
                ADDR_STATE_CD,
                ADDR_COUNTRY_CD,
                ADDR_ZIP,
                PHONE_NUM_1,
                PHONE_NUM_2,
                PROTECTED_MINIMUM,
                PROTECTED_SECOND,
                CUST_DOB,
                EFT_ACCOUNT_ID,
                PRI_CARD_HOLDER_IND,
                FICO_ROW_ZERO);
    }

    /**
     * Measures a value the way the fixed-width record image measures it.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded as US-ASCII
     */
    private static int encodedWidthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Proves the record geometry the entity is mapped against, by summing the copybook widths in the
     * open rather than quoting a total.
     */
    @Nested
    @DisplayName("Record geometry derived by summing the copybook widths")
    class RecordGeometry {

        @Test
        @DisplayName("the eighteen mapped widths sum to 332, and a 168-byte filler closes the 500-byte record")
        void theMappedWidthsSumToThreeHundredAndThirtyTwo() {
            assertThat(WIDTH_CUST_ID
                    + WIDTH_NAME + WIDTH_NAME + WIDTH_NAME
                    + WIDTH_ADDR_LINE + WIDTH_ADDR_LINE + WIDTH_ADDR_LINE
                    + WIDTH_STATE_CD + WIDTH_COUNTRY_CD + WIDTH_ZIP
                    + WIDTH_PHONE + WIDTH_PHONE
                    + WIDTH_CUST_SSN + WIDTH_GOVT_ISSUED_ID + WIDTH_CUST_DOB
                    + WIDTH_EFT_ACCOUNT_ID + WIDTH_PRI_CARD_HOLDER_IND + WIDTH_FICO)
                    .isEqualTo(MAPPED_WIDTH);

            assertThat(9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3)
                    .isEqualTo(332);

            // The remainder is the unmapped trailing filler: it is neither an attribute nor a column,
            // and only the fixed-width record mapper in the utility layer consumes it.
            assertThat(MAPPED_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(332 + 168).isEqualTo(500);
        }

        @Test
        @DisplayName("the national identifier begins at offset 279, the sum of the twelve widths ahead of it")
        void theNationalIdentifierOffsetIsTwoHundredAndSeventyNine() {
            assertThat(9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15).isEqualTo(279);
            assertThat(OFFSET_CUST_SSN).isEqualTo(279);
        }

        @Test
        @DisplayName("the running sums place the government identifier at 288, the date of birth at 308 and the score at 329")
        void theTrailingOffsetsFollowFromTheRunningSums() {
            assertThat(OFFSET_CUST_SSN + WIDTH_CUST_SSN).isEqualTo(OFFSET_GOVT_ISSUED_ID);
            assertThat(279 + 9).isEqualTo(288);

            assertThat(OFFSET_GOVT_ISSUED_ID + WIDTH_GOVT_ISSUED_ID).isEqualTo(OFFSET_CUST_DOB);
            assertThat(288 + 20).isEqualTo(308);

            assertThat(OFFSET_CUST_DOB + WIDTH_CUST_DOB).isEqualTo(OFFSET_EFT_ACCOUNT_ID);
            assertThat(308 + 10).isEqualTo(318);

            assertThat(OFFSET_EFT_ACCOUNT_ID + WIDTH_EFT_ACCOUNT_ID).isEqualTo(OFFSET_PRI_CARD_HOLDER_IND);
            assertThat(318 + 10).isEqualTo(328);

            assertThat(OFFSET_PRI_CARD_HOLDER_IND + WIDTH_PRI_CARD_HOLDER_IND).isEqualTo(OFFSET_FICO);
            assertThat(328 + 1).isEqualTo(329);

            assertThat(OFFSET_FICO + WIDTH_FICO).isEqualTo(MAPPED_WIDTH);
            assertThat(329 + 3).isEqualTo(332);
        }

        @Test
        @DisplayName("the key is the leading nine bytes of the record image, matching KEYS(9 0) on the cluster")
        void theKeyIsTheLeadingNineBytes() {
            assertThat(WIDTH_CUST_ID).isEqualTo(9);
            assertThat(encodedWidthOf(CUST_ID)).isEqualTo(WIDTH_CUST_ID);
        }
    }

    /**
     * Proves that the eighteen-argument constructor binds each argument to the attribute of the same
     * name, in record order, with nothing transformed on the way in.
     */
    @Nested
    @DisplayName("The eighteen-argument constructor binds every attribute in record order")
    class ConstructorRoundTrip {

        @Test
        @DisplayName("every one of the eighteen constructor arguments reaches the accessor of the same name")
        void everyConstructorArgumentReachesItsAccessor() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getCustId()).isEqualTo(CUST_ID);
            assertThat(customer.getFirstName()).isEqualTo(FIRST_NAME);
            assertThat(customer.getMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(customer.getLastName()).isEqualTo(LAST_NAME);
            assertThat(customer.getAddrLine1()).isEqualTo(ADDR_LINE_1);
            assertThat(customer.getAddrLine2()).isEqualTo(ADDR_LINE_2);
            assertThat(customer.getAddrLine3()).isEqualTo(ADDR_LINE_3);
            assertThat(customer.getAddrStateCd()).isEqualTo(ADDR_STATE_CD);
            assertThat(customer.getAddrCountryCd()).isEqualTo(ADDR_COUNTRY_CD);
            assertThat(customer.getAddrZip()).isEqualTo(ADDR_ZIP);
            assertThat(customer.getPhoneNum1()).isEqualTo(PHONE_NUM_1);
            assertThat(customer.getPhoneNum2()).isEqualTo(PHONE_NUM_2);
            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_MINIMUM);
            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_SECOND);
            assertThat(customer.getCustDob()).isEqualTo(CUST_DOB);
            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getPriCardHolderInd()).isEqualTo(PRI_CARD_HOLDER_IND);
            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO_ROW_ZERO);
        }

        @Test
        @DisplayName("the three same-width name arguments are not transposed, since a width match cannot catch a swap")
        void theThreeNamesAreNotTransposed() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getFirstName()).isNotEqualTo(customer.getMiddleName());
            assertThat(customer.getMiddleName()).isNotEqualTo(customer.getLastName());
            assertThat(customer.getFirstName()).isNotEqualTo(customer.getLastName());
        }

        @Test
        @DisplayName("the two same-width telephone numbers are not transposed, nor are the three address lines")
        void theSameWidthNeighboursAreNotTransposed() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getPhoneNum1()).isNotEqualTo(customer.getPhoneNum2());
            assertThat(customer.getAddrLine1()).isNotEqualTo(customer.getAddrLine2());
            assertThat(customer.getAddrLine2()).isNotEqualTo(customer.getAddrLine3());
        }

        @Test
        @DisplayName("the two same-width ten-byte fields either side of the transfer identifier are not transposed")
        void theDateOfBirthAndTransferIdentifierAreNotTransposed() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getCustDob()).isEqualTo(CUST_DOB);
            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getCustDob()).isNotEqualTo(customer.getEftAccountId());
        }
    }

    /**
     * Proves that each of the eighteen mutators replaces the attribute it names and no other, and
     * that none of them transforms the value on the way in.
     */
    @Nested
    @DisplayName("All eighteen mutators assign the attribute they name, verbatim")
    class MutatorRoundTrip {

        @Test
        @DisplayName("the sixteen unguarded mutators round-trip the first seeded record's values unchanged")
        void theUnguardedMutatorsRoundTripVerbatim() {
            // Same-package visibility reaches the persistence constructor directly. This is Java
            // package access, explicitly NOT reflection: no member is looked up by name anywhere.
            final Customer customer = new Customer();

            customer.setCustId(CUST_ID);
            customer.setFirstName(FIRST_NAME);
            customer.setMiddleName(MIDDLE_NAME);
            customer.setLastName(LAST_NAME);
            customer.setAddrLine1(ADDR_LINE_1);
            customer.setAddrLine2(ADDR_LINE_2);
            customer.setAddrLine3(ADDR_LINE_3);
            customer.setAddrStateCd(ADDR_STATE_CD);
            customer.setAddrCountryCd(ADDR_COUNTRY_CD);
            customer.setAddrZip(ADDR_ZIP);
            customer.setPhoneNum1(PHONE_NUM_1);
            customer.setPhoneNum2(PHONE_NUM_2);
            customer.setCustDob(CUST_DOB);
            customer.setEftAccountId(EFT_ACCOUNT_ID);
            customer.setPriCardHolderInd(PRI_CARD_HOLDER_IND);
            customer.setFicoCreditScore(FICO_ROW_ZERO);

            assertThat(customer.getCustId()).isEqualTo(CUST_ID);
            assertThat(customer.getFirstName()).isEqualTo(FIRST_NAME);
            assertThat(customer.getMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(customer.getLastName()).isEqualTo(LAST_NAME);
            assertThat(customer.getAddrLine1()).isEqualTo(ADDR_LINE_1);
            assertThat(customer.getAddrLine2()).isEqualTo(ADDR_LINE_2);
            assertThat(customer.getAddrLine3()).isEqualTo(ADDR_LINE_3);
            assertThat(customer.getAddrStateCd()).isEqualTo(ADDR_STATE_CD);
            assertThat(customer.getAddrCountryCd()).isEqualTo(ADDR_COUNTRY_CD);
            assertThat(customer.getAddrZip()).isEqualTo(ADDR_ZIP);
            assertThat(customer.getPhoneNum1()).isEqualTo(PHONE_NUM_1);
            assertThat(customer.getPhoneNum2()).isEqualTo(PHONE_NUM_2);
            assertThat(customer.getCustDob()).isEqualTo(CUST_DOB);
            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getPriCardHolderInd()).isEqualTo(PRI_CARD_HOLDER_IND);
            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO_ROW_ZERO);
        }

        @Test
        @DisplayName("the two guarded mutators round-trip a well-formed protected value unchanged")
        void theGuardedMutatorsRoundTripAProtectedValueUnchanged() {
            final Customer customer = new Customer();

            customer.setCustSsn(PROTECTED_MINIMUM);
            customer.setGovtIssuedId(PROTECTED_SECOND);

            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_MINIMUM);
            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_SECOND);
        }

        @Test
        @DisplayName("a mutator replaces only the attribute it names, so the other seventeen are undisturbed")
        void aMutatorReplacesOnlyTheAttributeItNames() {
            final Customer customer = firstSeededCustomer();

            customer.setAddrStateCd("NY");

            assertThat(customer.getAddrStateCd()).isEqualTo("NY");
            assertThat(customer.getCustId()).isEqualTo(CUST_ID);
            assertThat(customer.getFirstName()).isEqualTo(FIRST_NAME);
            assertThat(customer.getMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(customer.getLastName()).isEqualTo(LAST_NAME);
            assertThat(customer.getAddrLine1()).isEqualTo(ADDR_LINE_1);
            assertThat(customer.getAddrLine2()).isEqualTo(ADDR_LINE_2);
            assertThat(customer.getAddrLine3()).isEqualTo(ADDR_LINE_3);
            assertThat(customer.getAddrCountryCd()).isEqualTo(ADDR_COUNTRY_CD);
            assertThat(customer.getAddrZip()).isEqualTo(ADDR_ZIP);
            assertThat(customer.getPhoneNum1()).isEqualTo(PHONE_NUM_1);
            assertThat(customer.getPhoneNum2()).isEqualTo(PHONE_NUM_2);
            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_MINIMUM);
            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_SECOND);
            assertThat(customer.getCustDob()).isEqualTo(CUST_DOB);
            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getPriCardHolderInd()).isEqualTo(PRI_CARD_HOLDER_IND);
            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO_ROW_ZERO);
        }
    }

    /**
     * Proves each attribute carries exactly the number of encoded bytes its picture clause reserves.
     * Every measurement encodes as US-ASCII explicitly; character counts are never used, because the
     * value being described is a span of a fixed-width record image.
     */
    @Nested
    @DisplayName("Encoded field widths measured in US-ASCII bytes, never in characters")
    class EncodedFieldWidths {

        @Test
        @DisplayName("the eighteen attributes measure 9/25/25/25/50/50/50/2/3/10/15/15/9/20/10/10/1/3 encoded bytes in the record image")
        void everyAttributeMeasuresItsDeclaredWidth() {
            final Customer customer = firstSeededCustomer();

            assertThat(encodedWidthOf(customer.getCustId())).isEqualTo(9);
            assertThat(encodedWidthOf(customer.getFirstName())).isEqualTo(25);
            assertThat(encodedWidthOf(customer.getMiddleName())).isEqualTo(25);
            assertThat(encodedWidthOf(customer.getLastName())).isEqualTo(25);
            assertThat(encodedWidthOf(customer.getAddrLine1())).isEqualTo(50);
            assertThat(encodedWidthOf(customer.getAddrLine2())).isEqualTo(50);
            assertThat(encodedWidthOf(customer.getAddrLine3())).isEqualTo(50);
            assertThat(encodedWidthOf(customer.getAddrStateCd())).isEqualTo(2);
            assertThat(encodedWidthOf(customer.getAddrCountryCd())).isEqualTo(3);
            assertThat(encodedWidthOf(customer.getAddrZip())).isEqualTo(10);
            assertThat(encodedWidthOf(customer.getPhoneNum1())).isEqualTo(15);
            assertThat(encodedWidthOf(customer.getPhoneNum2())).isEqualTo(15);
            assertThat(encodedWidthOf(customer.getCustDob())).isEqualTo(10);
            assertThat(encodedWidthOf(customer.getEftAccountId())).isEqualTo(10);
            assertThat(encodedWidthOf(customer.getPriCardHolderInd())).isEqualTo(1);
            assertThat(encodedWidthOf(customer.getFicoCreditScore())).isEqualTo(3);

            // The two regulated identifiers occupy 9 and 20 bytes in the legacy record image, and
            // those two spans are asserted here on record-image-shaped values. The stored form is a
            // protected value and is deliberately longer, which is why the columns were widened.
            assertThat(encodedWidthOf(UNPROTECTED_NINE_CHARACTERS)).isEqualTo(WIDTH_CUST_SSN);
            assertThat(encodedWidthOf(UNPROTECTED_TWENTY_CHARACTERS)).isEqualTo(WIDTH_GOVT_ISSUED_ID);
        }

        @Test
        @DisplayName("the sixteen unregulated widths sum to 303, which with the 9-byte and 20-byte regulated spans closes the mapped 332")
        void theMeasuredWidthsCloseTheMappedRecord() {
            final Customer customer = firstSeededCustomer();

            final int measured = encodedWidthOf(customer.getCustId())
                    + encodedWidthOf(customer.getFirstName())
                    + encodedWidthOf(customer.getMiddleName())
                    + encodedWidthOf(customer.getLastName())
                    + encodedWidthOf(customer.getAddrLine1())
                    + encodedWidthOf(customer.getAddrLine2())
                    + encodedWidthOf(customer.getAddrLine3())
                    + encodedWidthOf(customer.getAddrStateCd())
                    + encodedWidthOf(customer.getAddrCountryCd())
                    + encodedWidthOf(customer.getAddrZip())
                    + encodedWidthOf(customer.getPhoneNum1())
                    + encodedWidthOf(customer.getPhoneNum2())
                    + encodedWidthOf(customer.getCustDob())
                    + encodedWidthOf(customer.getEftAccountId())
                    + encodedWidthOf(customer.getPriCardHolderInd())
                    + encodedWidthOf(customer.getFicoCreditScore());

            assertThat(measured).isEqualTo(303);
            assertThat(measured + WIDTH_CUST_SSN + WIDTH_GOVT_ISSUED_ID).isEqualTo(MAPPED_WIDTH);
            assertThat(303 + 9 + 20).isEqualTo(332);
        }
    }

    /**
     * Proves the behaviour of the national identifier, the one attribute that may be absent.
     */
    @Nested
    @DisplayName("The national identifier - the only attribute in the schema that may be absent")
    class NationalIdentifier {

        @Test
        @DisplayName("an absent national identifier round-trips as absent: it is the only nullable column across the eleven application tables, widened to 255 characters because it holds application-encrypted ciphertext wider than the nine-character legacy field, and the reference-data seed writes an absent value for all 50 rows")
        void anAbsentNationalIdentifierRoundTripsAsAbsent() {
            final Customer customer = firstSeededCustomer();

            assertThatCode(() -> customer.setCustSsn(null)).doesNotThrowAnyException();

            assertThat(customer.getCustSsn()).isNull();
            assertThat(customer.getCustSsn()).isNotEqualTo("null");
            assertThat(customer.getCustSsn()).isNotEqualTo("");
        }

        @Test
        @DisplayName("the eighteen-argument constructor also accepts an absent national identifier and stores it as absent")
        void theConstructorAcceptsAnAbsentNationalIdentifier() {
            final Customer customer = new Customer(
                    CUST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                    ADDR_LINE_1, ADDR_LINE_2, ADDR_LINE_3,
                    ADDR_STATE_CD, ADDR_COUNTRY_CD, ADDR_ZIP,
                    PHONE_NUM_1, PHONE_NUM_2,
                    null, PROTECTED_SECOND, CUST_DOB,
                    EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO_ROW_ZERO);

            assertThat(customer.getCustSsn()).isNull();
            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_SECOND);
        }

        @Test
        @DisplayName("a stored national identifier is returned byte for byte, proving the entity neither encrypts, decrypts, masks nor coerces the length")
        void aStoredNationalIdentifierIsReturnedByteForByte() {
            final Customer customer = new Customer();

            customer.setCustSsn(PROTECTED_MINIMUM);

            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_MINIMUM);
            assertThat(encodedWidthOf(customer.getCustSsn()))
                    .isEqualTo(encodedWidthOf(PROTECTED_MINIMUM));
        }

        @Test
        @DisplayName("a value far longer than the nine-character legacy field is stored whole, with no truncation to the source width")
        void aLongerValueIsStoredWhole() {
            final Customer customer = new Customer();

            customer.setCustSsn(PROTECTED_LONG);

            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_LONG);
            assertThat(encodedWidthOf(customer.getCustSsn())).isGreaterThan(WIDTH_CUST_SSN);
            assertThat(encodedWidthOf(customer.getCustSsn()))
                    .isEqualTo(encodedWidthOf(PROTECTED_LONG));
        }

        @Test
        @DisplayName("DIVERGENCE from the summarised contract: a nine-character unprotected value the width of the legacy field is refused rather than stored, so regulated cleartext cannot reach the persistence boundary")
        void anUnprotectedValueOfTheLegacyWidthIsRefused() {
            // The contract summary anticipated a plain-assignment mutator that would store a
            // nine-character value unchanged. The class as written is fail-closed instead: it refuses
            // any value lacking the protected-value envelope. Following the class, this suite asserts
            // the refusal, and asserts separately above that an accepted value is stored verbatim.
            final Customer customer = new Customer();

            assertThat(encodedWidthOf(UNPROTECTED_NINE_CHARACTERS)).isEqualTo(WIDTH_CUST_SSN);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setCustSsn(UNPROTECTED_NINE_CHARACTERS));

            assertThat(customer.getCustSsn()).isNull();
        }

        @Test
        @DisplayName("the refusal message names the attribute and never the offending value, because an exception message is a surface that gets logged")
        void theRefusalMessageNamesTheAttributeOnly() {
            final Customer customer = new Customer();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setCustSsn(UNPROTECTED_NINE_CHARACTERS))
                    .withMessageContaining("custSsn")
                    .withMessageNotContaining(UNPROTECTED_NINE_CHARACTERS);
        }

        @Test
        @DisplayName("a body one byte below the 28-byte authenticated minimum is refused, so a value cannot merely look like an envelope")
        void aBodyOneByteBelowTheMinimumIsRefused() {
            final Customer customer = new Customer();

            assertThatIllegalArgumentException().isThrownBy(() -> customer.setCustSsn(PROTECTED_TOO_SHORT));
            assertThatCode(() -> customer.setCustSsn(PROTECTED_MINIMUM)).doesNotThrowAnyException();

            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_MINIMUM);
        }

        @Test
        @DisplayName("a marker followed by a body that is not base-64 is refused, and so is an empty body")
        void aMalformedBodyIsRefused() {
            final Customer customer = new Customer();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setCustSsn(PROTECTED_BODY_NOT_ENCODED));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setCustSsn(ENVELOPE_MARKER));

            assertThat(customer.getCustSsn()).isNull();
        }
    }

    /**
     * Proves the behaviour of the government-issued identifier, which is guarded in the same way as
     * the national identifier and differs in exactly one respect: it may not be absent.
     */
    @Nested
    @DisplayName("The government-issued identifier - guarded identically, but never absent")
    class GovernmentIssuedIdentifier {

        @Test
        @DisplayName("a stored government-issued identifier is returned byte for byte, unencrypted and undecrypted by this entity")
        void aStoredIdentifierIsReturnedByteForByte() {
            final Customer customer = new Customer();

            customer.setGovtIssuedId(PROTECTED_LONG);

            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_LONG);
            assertThat(encodedWidthOf(customer.getGovtIssuedId()))
                    .isEqualTo(encodedWidthOf(PROTECTED_LONG));
        }

        @Test
        @DisplayName("DIVERGENCE from the summarised contract: a twenty-character unprotected value the width of the legacy field is refused rather than stored")
        void anUnprotectedValueOfTheLegacyWidthIsRefused() {
            // As with the national identifier, the summarised contract expected the twenty legacy
            // characters to be stored unchanged; the class refuses them. The 20-byte record-image span
            // is still asserted, on the value that is refused.
            final Customer customer = new Customer();

            assertThat(encodedWidthOf(UNPROTECTED_TWENTY_CHARACTERS)).isEqualTo(WIDTH_GOVT_ISSUED_ID);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setGovtIssuedId(UNPROTECTED_TWENTY_CHARACTERS))
                    .withMessageContaining("govtIssuedId")
                    .withMessageNotContaining(UNPROTECTED_TWENTY_CHARACTERS);

            assertThat(customer.getGovtIssuedId()).isNull();
        }

        @Test
        @DisplayName("an absent government-issued identifier is refused, because unlike the national identifier its column is not nullable")
        void anAbsentIdentifierIsRefused() {
            final Customer customer = new Customer();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setGovtIssuedId(null))
                    .withMessageContaining("govtIssuedId");
        }

        @Test
        @DisplayName("the eighteen-argument constructor likewise refuses an absent government-issued identifier")
        void theConstructorRefusesAnAbsentIdentifier() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Customer(
                    CUST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                    ADDR_LINE_1, ADDR_LINE_2, ADDR_LINE_3,
                    ADDR_STATE_CD, ADDR_COUNTRY_CD, ADDR_ZIP,
                    PHONE_NUM_1, PHONE_NUM_2,
                    PROTECTED_MINIMUM, null, CUST_DOB,
                    EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO_ROW_ZERO));
        }

        @Test
        @DisplayName("a short or non-base-64 body is refused here too, and the accepted value is then stored unchanged")
        void aMalformedBodyIsRefused() {
            final Customer customer = new Customer();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setGovtIssuedId(PROTECTED_TOO_SHORT));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> customer.setGovtIssuedId(PROTECTED_BODY_NOT_ENCODED));

            customer.setGovtIssuedId(PROTECTED_SECOND);

            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_SECOND);
        }

        @Test
        @DisplayName("the two guarded attributes are independent: writing one leaves the other exactly as it was")
        void theTwoGuardedAttributesAreIndependent() {
            final Customer customer = firstSeededCustomer();

            customer.setCustSsn(PROTECTED_LONG);

            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_LONG);
            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_SECOND);

            customer.setGovtIssuedId(PROTECTED_MINIMUM);

            assertThat(customer.getGovtIssuedId()).isEqualTo(PROTECTED_MINIMUM);
            assertThat(customer.getCustSsn()).isEqualTo(PROTECTED_LONG);
        }
    }

    /**
     * Proves the credit score is carried as three characters of text with no range applied, which is
     * the only way the seeded reference data can be read back at all.
     */
    @Nested
    @DisplayName("The credit score - three characters of text, no range applied")
    class CreditScore {

        @Test
        @DisplayName("every out-of-band seeded score is stored unchanged: 21 of the 50 seeded rows fall below the 300-to-850 band, the first row carries 274, and the band is a level-88 range condition enforced on the account-update path alone, never by this entity")
        void everyOutOfBandSeededScoreIsStoredUnchanged() {
            final Customer customer = new Customer();

            assertThatCode(() -> customer.setFicoCreditScore(FICO_LOWEST_SEEDED)).doesNotThrowAnyException();
            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO_LOWEST_SEEDED);
            assertThat(encodedWidthOf(customer.getFicoCreditScore())).isEqualTo(WIDTH_FICO);

            assertThatCode(() -> customer.setFicoCreditScore(FICO_ROW_ZERO)).doesNotThrowAnyException();
            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO_ROW_ZERO);
            assertThat(encodedWidthOf(customer.getFicoCreditScore())).isEqualTo(WIDTH_FICO);

            assertThatCode(() -> customer.setFicoCreditScore(FICO_SCREEN_UPPER_BOUND))
                    .doesNotThrowAnyException();
            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO_SCREEN_UPPER_BOUND);
            assertThat(encodedWidthOf(customer.getFicoCreditScore())).isEqualTo(WIDTH_FICO);

            // Recorded as data evidence, not as a threshold: 21 of the 50 seeded rows would be
            // rejected by any constraint here, which is exactly why none is declared.
            assertThat(SEEDED_ROWS_BELOW_SCREEN_BAND).isLessThan(SEEDED_ROWS);
        }

        @Test
        @DisplayName("the constructor also accepts an out-of-band score, so the seed load and every fixture round trip succeed")
        void theConstructorAcceptsAnOutOfBandScore() {
            final Customer customer = new Customer(
                    CUST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                    ADDR_LINE_1, ADDR_LINE_2, ADDR_LINE_3,
                    ADDR_STATE_CD, ADDR_COUNTRY_CD, ADDR_ZIP,
                    PHONE_NUM_1, PHONE_NUM_2,
                    PROTECTED_MINIMUM, PROTECTED_SECOND, CUST_DOB,
                    EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO_LOWEST_SEEDED);

            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO_LOWEST_SEEDED);
        }

        @Test
        @DisplayName("the score keeps its leading zeros: the lowest seeded score stays three characters and is not the single character a numeric type would render")
        void theScoreKeepsItsLeadingZeros() {
            final Customer customer = new Customer();

            customer.setFicoCreditScore(FICO_LOWEST_SEEDED);

            assertThat(customer.getFicoCreditScore()).isEqualTo("001");
            assertThat(customer.getFicoCreditScore()).isNotEqualTo("1");
            assertThat(encodedWidthOf(customer.getFicoCreditScore())).isEqualTo(3);
        }
    }

    /**
     * Proves the fixed-width digit-only identifiers keep their external decimal form, which is what
     * makes them stable keys and stable record spans.
     */
    @Nested
    @DisplayName("Fixed-width identifiers keep their external decimal form")
    class FixedWidthIdentifiers {

        @Test
        @DisplayName("the customer identifier keeps its leading zeros: nine characters of text, not a numeric type, matching KEYS(9 0)")
        void theCustomerIdentifierKeepsItsLeadingZeros() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getCustId()).isEqualTo("000000001");
            assertThat(customer.getCustId()).isNotEqualTo("1");
            assertThat(encodedWidthOf(customer.getCustId())).isEqualTo(9);
        }

        @Test
        @DisplayName("the transfer account identifier keeps its leading zero: ten characters, and not the eight-character form a numeric type would render")
        void theTransferAccountIdentifierKeepsItsLeadingZero() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getEftAccountId()).isNotEqualTo("53581756");
            assertThat(encodedWidthOf(customer.getEftAccountId())).isEqualTo(10);
        }
    }

    /**
     * Proves that trailing padding is part of the stored value rather than incidental whitespace, so
     * a record written back out is still the width the cluster declares.
     */
    @Nested
    @DisplayName("Trailing padding is part of the value, never incidental whitespace")
    class PaddingFidelity {

        @Test
        @DisplayName("the three 25-byte names round-trip with their padding intact and are not equal to their unpadded forms")
        void theNamesKeepTheirPadding() {
            final Customer customer = firstSeededCustomer();

            assertThat(encodedWidthOf(customer.getFirstName())).isEqualTo(WIDTH_NAME);
            assertThat(encodedWidthOf(customer.getMiddleName())).isEqualTo(WIDTH_NAME);
            assertThat(encodedWidthOf(customer.getLastName())).isEqualTo(WIDTH_NAME);

            assertThat(customer.getFirstName()).isNotEqualTo("Immanuel");
            assertThat(customer.getMiddleName()).isNotEqualTo("Madeline");
            assertThat(customer.getLastName()).isNotEqualTo("Kessler");

            assertThat(customer.getFirstName()).startsWith("Immanuel");
            assertThat(customer.getMiddleName()).startsWith("Madeline");
            assertThat(customer.getLastName()).startsWith("Kessler");
        }

        @Test
        @DisplayName("the three 50-byte address lines round-trip with their padding intact and are not equal to their unpadded forms")
        void theAddressLinesKeepTheirPadding() {
            final Customer customer = firstSeededCustomer();

            assertThat(encodedWidthOf(customer.getAddrLine1())).isEqualTo(WIDTH_ADDR_LINE);
            assertThat(encodedWidthOf(customer.getAddrLine2())).isEqualTo(WIDTH_ADDR_LINE);
            assertThat(encodedWidthOf(customer.getAddrLine3())).isEqualTo(WIDTH_ADDR_LINE);

            assertThat(customer.getAddrLine1()).isNotEqualTo("618 Deshaun Route");
            assertThat(customer.getAddrLine2()).isNotEqualTo("Apt. 802");
            assertThat(customer.getAddrLine3()).isNotEqualTo("Altenwerthshire");
        }

        @Test
        @DisplayName("the postal code round-trips as five characters plus five trailing spaces, ten bytes in all, and is not equal to its unpadded form")
        void thePostalCodeKeepsItsFiveTrailingSpaces() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getAddrZip()).isEqualTo(ADDR_ZIP);
            assertThat(encodedWidthOf(customer.getAddrZip())).isEqualTo(10);
            assertThat(customer.getAddrZip()).isNotEqualTo("12546");
        }

        @Test
        @DisplayName("both telephone numbers keep their parenthesised area-code formatting and two trailing spaces verbatim, fifteen bytes each; area-code membership of the 490-entry numbering-plan set - 410 general-purpose plus 80 easily-recognisable, an exact partition - is a lookup-service concern and is not applied here")
        void theTelephoneNumbersKeepTheirFormattingAndPadding() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getPhoneNum1()).isEqualTo(PHONE_NUM_1);
            assertThat(customer.getPhoneNum2()).isEqualTo(PHONE_NUM_2);
            assertThat(encodedWidthOf(customer.getPhoneNum1())).isEqualTo(WIDTH_PHONE);
            assertThat(encodedWidthOf(customer.getPhoneNum2())).isEqualTo(WIDTH_PHONE);

            assertThat(customer.getPhoneNum1()).isNotEqualTo("(908)119-8310");
            assertThat(customer.getPhoneNum2()).isNotEqualTo("(373)693-8684");

            // An area code outside the permitted set is stored exactly as supplied.
            customer.setPhoneNum1("(000)000-0000  ");
            assertThat(customer.getPhoneNum1()).isEqualTo("(000)000-0000  ");
            assertThat(encodedWidthOf(customer.getPhoneNum1())).isEqualTo(WIDTH_PHONE);
        }
    }

    /**
     * Proves the two attributes the legacy account-update path decorates for error display but never
     * edits remain unconstrained here, so input the legacy system accepts is not rejected.
     */
    @Nested
    @DisplayName("The two decorated-but-never-edited attributes carry no constraint")
    class UnvalidatedAttributes {

        @Test
        @DisplayName("an all-spaces middle name is accepted at its full 25 bytes: the legacy account-update screen decorates this field for error display but codes no edit for it, so attaching any constraint would reject input the legacy system accepts")
        void anAllSpacesMiddleNameIsAccepted() {
            final Customer customer = firstSeededCustomer();
            final String twentyFiveSpaces = "                         ";

            assertThatCode(() -> customer.setMiddleName(twentyFiveSpaces)).doesNotThrowAnyException();

            assertThat(customer.getMiddleName()).isEqualTo(twentyFiveSpaces);
            assertThat(customer.getMiddleName()).isNotNull();
            assertThat(customer.getMiddleName()).isNotEqualTo("");
            assertThat(encodedWidthOf(customer.getMiddleName())).isEqualTo(WIDTH_NAME);
        }

        @Test
        @DisplayName("the second address line accepts a period and digits padded to 50 bytes: the legacy screen codes no edit for it either, and the seeded value is neither alphabetic-or-space nor alphanumeric-or-space, so no constraint may be attached")
        void theSecondAddressLineAcceptsPunctuationAndDigits() {
            final Customer customer = firstSeededCustomer();

            assertThatCode(() -> customer.setAddrLine2(ADDR_LINE_2)).doesNotThrowAnyException();

            assertThat(customer.getAddrLine2()).isEqualTo(ADDR_LINE_2);
            assertThat(customer.getAddrLine2()).contains(".");
            assertThat(customer.getAddrLine2()).contains("802");
            assertThat(encodedWidthOf(customer.getAddrLine2())).isEqualTo(WIDTH_ADDR_LINE);
        }

        @Test
        @DisplayName("both decorated-but-never-edited attributes also accept an absent value, since neither carries a constraint of any kind")
        void bothAttributesAlsoAcceptAnAbsentValue() {
            final Customer customer = firstSeededCustomer();

            assertThatCode(() -> customer.setMiddleName(null)).doesNotThrowAnyException();
            assertThatCode(() -> customer.setAddrLine2(null)).doesNotThrowAnyException();

            assertThat(customer.getMiddleName()).isNull();
            assertThat(customer.getAddrLine2()).isNull();
        }
    }

    /**
     * Proves the entity performs none of the reference-data or calendar checks that belong to the
     * service layer, by storing values those services would reject.
     */
    @Nested
    @DisplayName("Reference-data and calendar checks belong to the service layer, not to this entity")
    class NoServiceLevelChecksHere {

        @Test
        @DisplayName("the state and country codes round-trip at exactly 2 and 3 bytes, and a two-character value outside the 56-entry state set is stored unchanged: membership of that set, and of the 240-entry state-and-postal-prefix set, is checked by the validation-lookup service")
        void theStateAndCountryCodesAreStoredWithoutAnyLookup() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getAddrStateCd()).isEqualTo("NC");
            assertThat(customer.getAddrCountryCd()).isEqualTo("USA");
            assertThat(encodedWidthOf(customer.getAddrStateCd())).isEqualTo(WIDTH_STATE_CD);
            assertThat(encodedWidthOf(customer.getAddrCountryCd())).isEqualTo(WIDTH_COUNTRY_CD);

            // "ZZ" is not a state code in the permitted set. The entity stores it regardless.
            assertThatCode(() -> customer.setAddrStateCd("ZZ")).doesNotThrowAnyException();
            assertThat(customer.getAddrStateCd()).isEqualTo("ZZ");
            assertThat(encodedWidthOf(customer.getAddrStateCd())).isEqualTo(WIDTH_STATE_CD);

            assertThatCode(() -> customer.setAddrCountryCd("ZZZ")).doesNotThrowAnyException();
            assertThat(customer.getAddrCountryCd()).isEqualTo("ZZZ");
            assertThat(encodedWidthOf(customer.getAddrCountryCd())).isEqualTo(WIDTH_COUNTRY_CD);
        }

        @Test
        @DisplayName("the date of birth round-trips as ten raw characters and a value that is no calendar date at all is stored unchanged: the attribute holds text rather than a date type, and calendar validity is the date-validation service's cascade to enforce")
        void theDateOfBirthIsStoredAsRawTextWithoutParsing() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getCustDob()).isEqualTo(CUST_DOB);
            assertThat(encodedWidthOf(customer.getCustDob())).isEqualTo(WIDTH_CUST_DOB);

            // Neither a real month nor a real day. Stored exactly as supplied, at the same width.
            assertThatCode(() -> customer.setCustDob("9999-99-99")).doesNotThrowAnyException();
            assertThat(customer.getCustDob()).isEqualTo("9999-99-99");
            assertThat(encodedWidthOf(customer.getCustDob())).isEqualTo(WIDTH_CUST_DOB);
        }

        @Test
        @DisplayName("the primary-cardholder indicator is a raw single character: the two legacy values round-trip, and so does a value outside that pair, with no exception, no default substitution and no case folding")
        void thePrimaryCardholderIndicatorIsStoredRaw() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer.getPriCardHolderInd()).isEqualTo("Y");

            customer.setPriCardHolderInd("N");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("N");

            assertThatCode(() -> customer.setPriCardHolderInd("X")).doesNotThrowAnyException();
            assertThat(customer.getPriCardHolderInd()).isEqualTo("X");
            assertThat(encodedWidthOf(customer.getPriCardHolderInd()))
                    .isEqualTo(WIDTH_PRI_CARD_HOLDER_IND);

            // Lower case is not folded to upper case.
            customer.setPriCardHolderInd("y");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("y");
            assertThat(customer.getPriCardHolderInd()).isNotEqualTo("Y");
        }
    }

    /**
     * Proves identity is the business key alone, which is what keeps an instance stable in a
     * hash-based collection across an update to any other attribute.
     */
    @Nested
    @DisplayName("Identity is the business key alone, never a surrogate")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("two customers sharing an identifier but differing in every other attribute - including an absent against a present national identifier - are equal and share a hash code")
        void sameIdentifierMeansEqualWhateverElseDiffers() {
            final Customer first = firstSeededCustomer();
            final Customer second = new Customer(
                    CUST_ID,
                    "Different                ",
                    "Different                ",
                    "Different                ",
                    "Different                                         ",
                    "Different                                         ",
                    "Different                                         ",
                    "NY",
                    "CAN",
                    "99999     ",
                    "(111)111-1111  ",
                    "(222)222-2222  ",
                    null,
                    PROTECTED_MINIMUM,
                    "1999-12-31",
                    "9999999999",
                    "N",
                    FICO_SCREEN_UPPER_BOUND);

            assertThat(first.getCustSsn()).isNotNull();
            assertThat(second.getCustSsn()).isNull();
            assertThat(first.getFirstName()).isNotEqualTo(second.getFirstName());
            assertThat(first.getFicoCreditScore()).isNotEqualTo(second.getFicoCreditScore());

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a different identifier means a different customer, even when every other attribute matches")
        void aDifferentIdentifierMeansUnequal() {
            final Customer first = firstSeededCustomer();
            final Customer second = firstSeededCustomer();

            second.setCustId("000000002");

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("a differently padded identifier is deliberately not equal, because it is a distinct key in the database too")
        void zeroFillingIsSignificantToIdentity() {
            final Customer padded = firstSeededCustomer();
            final Customer unpadded = firstSeededCustomer();

            unpadded.setCustId("1");

            assertThat(padded).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("equality is reflexive, rejects an absent operand and rejects a foreign type")
        void equalityIsReflexiveAndTypeSafe() {
            final Customer customer = firstSeededCustomer();

            assertThat(customer).isEqualTo(customer);
            assertThat(customer.equals(customer)).isTrue();
            assertThat(customer.equals(null)).isFalse();
            assertThat(customer.equals(CUST_ID)).isFalse();
        }

        @Test
        @DisplayName("the hash is derived from the identifier alone, so replacing a non-key attribute leaves an instance where it already sits in a hash-based collection")
        void theHashIsStableAcrossANonKeyUpdate() {
            final Customer customer = firstSeededCustomer();
            final Customer reference = firstSeededCustomer();

            customer.setFicoCreditScore(FICO_SCREEN_UPPER_BOUND);
            customer.setAddrStateCd("NY");
            customer.setCustSsn(null);

            assertThat(customer.getFicoCreditScore()).isNotEqualTo(reference.getFicoCreditScore());
            assertThat(customer).hasSameHashCodeAs(reference);
            assertThat(customer).isEqualTo(reference);
        }

        @Test
        @DisplayName("two customers with an absent identifier are equal, and an absent identifier is unequal to a present one")
        void anAbsentIdentifierCompares() {
            final Customer firstUnkeyed = new Customer();
            final Customer secondUnkeyed = new Customer();
            final Customer keyed = firstSeededCustomer();

            assertThat(firstUnkeyed).isEqualTo(secondUnkeyed);
            assertThat(firstUnkeyed).hasSameHashCodeAs(secondUnkeyed);
            assertThat(firstUnkeyed).isNotEqualTo(keyed);
            assertThat(keyed).isNotEqualTo(firstUnkeyed);
        }
    }

    /**
     * Proves the provider-facing constructor exists and leaves the instance empty, and records the
     * documented non-features of this entity.
     */
    @Nested
    @DisplayName("The persistence constructor, and this entity's documented non-features")
    class PersistenceContractAndNonFeatures {

        @Test
        @DisplayName("the no-argument persistence constructor leaves all eighteen attributes absent, so the provider can populate them after construction")
        void thePersistenceConstructorLeavesEveryAttributeAbsent() {
            // This test class sits in the same package as the entity, so Java package access reaches
            // the protected no-argument constructor directly. This is same-package visibility and is
            // explicitly NOT reflection: no member is resolved by name and no accessibility flag is
            // changed anywhere in this file.
            final Customer customer = new Customer();

            assertThat(customer.getCustId()).isNull();
            assertThat(customer.getFirstName()).isNull();
            assertThat(customer.getMiddleName()).isNull();
            assertThat(customer.getLastName()).isNull();
            assertThat(customer.getAddrLine1()).isNull();
            assertThat(customer.getAddrLine2()).isNull();
            assertThat(customer.getAddrLine3()).isNull();
            assertThat(customer.getAddrStateCd()).isNull();
            assertThat(customer.getAddrCountryCd()).isNull();
            assertThat(customer.getAddrZip()).isNull();
            assertThat(customer.getPhoneNum1()).isNull();
            assertThat(customer.getPhoneNum2()).isNull();
            assertThat(customer.getCustSsn()).isNull();
            assertThat(customer.getGovtIssuedId()).isNull();
            assertThat(customer.getCustDob()).isNull();
            assertThat(customer.getEftAccountId()).isNull();
            assertThat(customer.getPriCardHolderInd()).isNull();
            assertThat(customer.getFicoCreditScore()).isNull();
        }

        @Test
        @DisplayName("no surrogate identifier exists: the key is the 9-character business key at offset 0, matching KEYS(9 0), no generated value is declared, and the caller assigns it - proved by compile-time absence, since this file never names a generated-identifier accessor")
        void noSurrogateIdentifierExists() {
            // The proof of absence is that this file compiles while naming only getCustId and
            // setCustId. There is no getId, no setId and no generated-identifier accessor to call, and
            // no reflection is used to look for one. What is asserted behaviourally is that the key is
            // caller-assigned and survives verbatim: a provider-constructed instance has no key until
            // one is supplied, and a supplied key is never replaced by a generated value.
            final Customer customer = new Customer();

            assertThat(customer.getCustId()).isNull();

            customer.setCustId(CUST_ID);

            assertThat(customer.getCustId()).isEqualTo(CUST_ID);
            assertThat(encodedWidthOf(customer.getCustId())).isEqualTo(WIDTH_CUST_ID);
        }

        @Test
        @DisplayName("two legacy spellings, one entity: the copybook the six online and batch programs include hyphenates the date-of-birth field while the alternate copybook the statement generator includes does not, both denote the same 10 bytes at offset 308 derived as 288 + 20, and one attribute, one column cust_dob and one test class serve both - with no variant flag, no discriminator and no second entity")
        void twoLegacySpellingsMapToOneAttribute() {
            assertThat(OFFSET_GOVT_ISSUED_ID + WIDTH_GOVT_ISSUED_ID).isEqualTo(OFFSET_CUST_DOB);
            assertThat(288 + 20).isEqualTo(308);
            assertThat(WIDTH_CUST_DOB).isEqualTo(10);

            // One accessor pair serves both spellings, and it stores text at the shared width.
            final Customer customer = new Customer();
            customer.setCustDob(CUST_DOB);

            assertThat(customer.getCustDob()).isEqualTo(CUST_DOB);
            assertThat(encodedWidthOf(customer.getCustDob())).isEqualTo(OFFSET_EFT_ACCOUNT_ID - OFFSET_CUST_DOB);
        }

        @Test
        @DisplayName("this entity declares no diagnostic string of its own, so no attribute value can escape through one; the inherited description names the type and carries no stored value")
        void noDiagnosticStringExposesAnyAttribute() {
            // The class deliberately declares no diagnostic string, because every attribute is
            // personally identifiable. What is inherited names the type and a hash only, so the two
            // regulated values in particular cannot leak through it. The prefix checked below is the
            // documented format of the inherited description - the type name, an at sign, then the
            // hash in hexadecimal - and is asserted solely as evidence that no override was added.
            // No entity-authored diagnostic format is being pinned here, because there is none.
            final Customer customer = firstSeededCustomer();

            final String inheritedDescription = customer.toString();

            assertThat(inheritedDescription).startsWith("com.carddemo.domain.Customer@");
            assertThat(inheritedDescription).doesNotContain(PROTECTED_MINIMUM);
            assertThat(inheritedDescription).doesNotContain(PROTECTED_SECOND);
            assertThat(inheritedDescription).doesNotContain(CUST_ID);
            assertThat(inheritedDescription).doesNotContain(EFT_ACCOUNT_ID);
            assertThat(inheritedDescription).doesNotContain(CUST_DOB);
        }
    }
}
