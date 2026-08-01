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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.carddemo.domain.enums.TransactionSourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link DailyTransaction}, the Java realisation of the 350-byte
 * {@code DALYTRAN-RECORD} layout declared by copybook member {@code CVTRA06Y}.
 *
 * <h2>Why this is a separate type from the posted transaction, and why that matters here</h2>
 *
 * <p>This layout is byte-for-byte identical to the posted-transaction layout, differing only in field
 * prefix. It is nevertheless a separate entity because it is a separate dataset with a separate
 * lifecycle: rows arrive here, are validated, and are then either posted or rejected. The consequence
 * for this test is that the assertions below are about the <em>input</em> record's fidelity - the two
 * timestamp fields at their 26-byte width, the amount at scale two, and the card number at 16 - rather
 * than about any posting outcome, which belongs to the posting service.</p>
 *
 * <h2>The two offsets that the external sort specifications depend on</h2>
 *
 * <p>Two fields of this layout are addressed positionally by the legacy sort specifications: the card
 * number and the processing timestamp. Both are fixed-width character fields whose padding is
 * significant, so the assertions pin the widths and pin that no accessor trims them.</p>
 *
 * <h2>Redaction is not optional on this type</h2>
 *
 * <p>Three of this record's fields are individually sufficient to make a log line a disclosure: the
 * card number is a primary account number, the amount is financial data, and the merchant name plus
 * city localise the cardholder. The diagnostic rendering therefore carries only the identifier and the
 * two classification codes, and that is asserted rather than assumed.</p>
 */
@DisplayName("DailyTransaction - the 350-byte CVTRA06Y daily transaction record")
class DailyTransactionSecurityTest {

    private static final String TRANSACTION_ID = "0000000000000001";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0005";
    private static final String SOURCE = "POS TERM  ";
    private static final String DESCRIPTION = "PURCHASE AT MERCHANT";
    private static final BigDecimal AMOUNT = new BigDecimal("104.50");
    private static final String MERCHANT_ID = "000000123";
    private static final String MERCHANT_NAME = "ACME SUPPLY COMPANY";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101-0000";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String ORIGINATION_TIMESTAMP = "2022-07-19 10:15:30.000000";
    private static final String PROCESSING_TIMESTAMP = "2022-07-19 23:59:59.999999";

    /** A fully populated daily transaction at the widths the record layout declares. */
    private static DailyTransaction transaction() {
        return new DailyTransaction(TRANSACTION_ID, TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                AMOUNT, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                ORIGINATION_TIMESTAMP, PROCESSING_TIMESTAMP);
    }

    @Nested
    @DisplayName("Construction from the record image")
    class Construction {

        @Test
        @DisplayName("the thirteen-argument constructor assigns every field to its own accessor, so none of the "
                + "eleven same-typed string arguments is transposed")
        void everyFieldLandsOnItsOwnAccessor() {
            final DailyTransaction subject = transaction();
            assertThat(subject.getDalytranId()).isEqualTo(TRANSACTION_ID);
            assertThat(subject.getDalytranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(subject.getDalytranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(subject.getDalytranSource()).isEqualTo(SOURCE);
            assertThat(subject.getDalytranDesc()).isEqualTo(DESCRIPTION);
            assertThat(subject.getDalytranAmt()).isEqualTo(AMOUNT);
            assertThat(subject.getDalytranMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(subject.getDalytranMerchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(subject.getDalytranMerchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(subject.getDalytranMerchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(subject.getDalytranCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(subject.getDalytranOrigTs()).isEqualTo(ORIGINATION_TIMESTAMP);
            assertThat(subject.getDalytranProcTs()).isEqualTo(PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("all twelve string fixtures are distinguishable, so a transposition among the same-typed "
                + "arguments would be caught rather than hidden")
        void allStringFixturesAreDistinguishable() {
            assertThat(Set.of(TRANSACTION_ID, TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                    MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                    ORIGINATION_TIMESTAMP, PROCESSING_TIMESTAMP)).hasSize(12);
        }

        @Test
        @DisplayName("the two timestamps are distinguishable, which matters because the sort specifications address "
                + "them at different offsets")
        void theTwoTimestampsAreDistinguishable() {
            assertThat(ORIGINATION_TIMESTAMP).isNotEqualTo(PROCESSING_TIMESTAMP);
            final DailyTransaction subject = transaction();
            assertThat(subject.getDalytranOrigTs()).isNotEqualTo(subject.getDalytranProcTs());
        }

        @Test
        @DisplayName("the provider constructor leaves every field unset, because the provider assigns state after "
                + "construction")
        void theProviderConstructorLeavesEveryFieldUnset() {
            final DailyTransaction subject = new DailyTransaction();
            assertThat(subject.getDalytranId()).isNull();
            assertThat(subject.getDalytranTypeCd()).isNull();
            assertThat(subject.getDalytranCatCd()).isNull();
            assertThat(subject.getDalytranSource()).isNull();
            assertThat(subject.getDalytranDesc()).isNull();
            assertThat(subject.getDalytranAmt()).isNull();
            assertThat(subject.getDalytranMerchantId()).isNull();
            assertThat(subject.getDalytranMerchantName()).isNull();
            assertThat(subject.getDalytranMerchantCity()).isNull();
            assertThat(subject.getDalytranMerchantZip()).isNull();
            assertThat(subject.getDalytranCardNum()).isNull();
            assertThat(subject.getDalytranOrigTs()).isNull();
            assertThat(subject.getDalytranProcTs()).isNull();
        }
    }

    @Nested
    @DisplayName("Field widths carried through from the record layout")
    class RecordLayoutWidths {

        @Test
        @DisplayName("the transaction identifier is 16 characters and keeps its leading zeros, because the key is a "
                + "substring of the record image rather than a number")
        void theIdentifierIsSixteenCharactersWithLeadingZeros() {
            assertThat(TRANSACTION_ID).hasSize(16).startsWith("0");
            assertThat(transaction().getDalytranId()).isEqualTo(TRANSACTION_ID).isNotEqualTo("1");
        }

        @Test
        @DisplayName("the type code is 2 characters and the category code is 4, matching PIC X(2) and PIC X(4)")
        void theClassificationCodesCarryTheirDeclaredWidths() {
            assertThat(transaction().getDalytranTypeCd()).hasSize(2);
            assertThat(transaction().getDalytranCatCd()).hasSize(4);
        }

        @Test
        @DisplayName("the source is 10 characters and keeps its trailing padding, matching the source-type images "
                + "the estate actually writes")
        void theSourceIsTenCharactersAndKeepsItsPadding() {
            final DailyTransaction subject = transaction();
            assertThat(subject.getDalytranSource())
                    .hasSize(TransactionSourceType.VALUE_LENGTH)
                    .isEqualTo("POS TERM  ")
                    .isNotEqualTo("POS TERM");
        }

        @Test
        @DisplayName("the source value resolves against the declared source-type vocabulary, so the fixture is a "
                + "real image rather than an invented one")
        void theSourceValueResolvesAgainstTheDeclaredVocabulary() {
            assertThat(TransactionSourceType.fromValue(transaction().getDalytranSource()))
                    .contains(TransactionSourceType.POS_TERM);
        }

        @Test
        @DisplayName("the card number is 16 characters, the width the sort specification addresses positionally")
        void theCardNumberIsSixteenCharacters() {
            assertThat(transaction().getDalytranCardNum()).hasSize(16);
        }

        @Test
        @DisplayName("both timestamps are 26 characters, the width the alternate index on the processing timestamp "
                + "declares")
        void bothTimestampsAreTwentySixCharacters() {
            assertThat(transaction().getDalytranOrigTs()).hasSize(26);
            assertThat(transaction().getDalytranProcTs()).hasSize(26);
        }

        @Test
        @DisplayName("the merchant ZIP is 10 characters, matching PIC X(10)")
        void theMerchantZipIsTenCharacters() {
            assertThat(transaction().getDalytranMerchantZip()).hasSize(10);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "1", "00000000000000000000000000000000"})
        @DisplayName("the entity stores an identifier of any width verbatim, because the column length polices the "
                + "width rather than the entity re-policing it")
        void theEntityStoresAnIdentifierOfAnyWidthVerbatim(final String candidate) {
            final DailyTransaction subject = new DailyTransaction();
            subject.setDalytranId(candidate);
            assertThat(subject.getDalytranId()).isEqualTo(candidate);
        }

        @Test
        @DisplayName("no accessor trims a fixed-width value, so significant padding survives the round trip in "
                + "every direction")
        void noAccessorTrimsAFixedWidthValue() {
            final DailyTransaction subject = new DailyTransaction();
            subject.setDalytranSource("  padded  ");
            subject.setDalytranMerchantName("  spaced name  ");
            subject.setDalytranProcTs("  2022-07-19 00:00:00.0  ");
            assertThat(subject.getDalytranSource()).isEqualTo("  padded  ");
            assertThat(subject.getDalytranMerchantName()).isEqualTo("  spaced name  ");
            assertThat(subject.getDalytranProcTs()).isEqualTo("  2022-07-19 00:00:00.0  ");
        }
    }

    @Nested
    @DisplayName("The amount: scale is carried, never coerced")
    class MonetaryScale {

        @Test
        @DisplayName("the amount fixture is at scale two, matching the V99 clause on PIC S9(09)V99")
        void theAmountFixtureIsAtScaleTwo() {
            assertThat(AMOUNT.scale()).isEqualTo(2);
            assertThat(transaction().getDalytranAmt().scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.0", "0.000", "104.5", "104.500", "-104.50", "999999999.99"})
        @DisplayName("an off-scale amount is stored exactly as supplied and is not silently normalised, because "
                + "scale coercion belongs to the codec rather than to the entity")
        void anOffScaleAmountIsStoredExactlyAsSupplied(final String literal) {
            final BigDecimal supplied = new BigDecimal(literal);
            final DailyTransaction subject = new DailyTransaction();
            subject.setDalytranAmt(supplied);
            assertThat(subject.getDalytranAmt()).isSameAs(supplied);
            assertThat(subject.getDalytranAmt().scale()).isEqualTo(supplied.scale());
            assertThat(subject.getDalytranAmt().toPlainString()).isEqualTo(literal);
        }

        @Test
        @DisplayName("the amount is an exact decimal, not a floating-point substitution")
        void theAmountIsAnExactDecimal() {
            assertThat(transaction().getDalytranAmt()).isInstanceOf(BigDecimal.class);
            assertThat(transaction().getDalytranAmt().unscaledValue().longValueExact())
                    .isEqualTo(10450L);
        }

        @Test
        @DisplayName("a negative amount keeps its sign, which is how the 50 operator-originated returns in the "
                + "reference fixture are represented")
        void aNegativeAmountKeepsItsSign() {
            final DailyTransaction subject = new DailyTransaction();
            subject.setDalytranAmt(new BigDecimal("-0.01"));
            assertThat(subject.getDalytranAmt().signum()).isNegative();
        }

        @Test
        @DisplayName("the widest value the PIC S9(09)V99 clause admits is stored without loss")
        void theWidestAdmissibleAmountIsStoredWithoutLoss() {
            final BigDecimal widest = new BigDecimal("999999999.99");
            final DailyTransaction subject = new DailyTransaction();
            subject.setDalytranAmt(widest);
            assertThat(subject.getDalytranAmt()).isEqualTo(widest);
            assertThat(subject.getDalytranAmt().precision()).isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("Mutability through the setters")
    class Mutation {

        @Test
        @DisplayName("all thirteen setters are individually effective, walking a blank instance up to a full one")
        void allThirteenSettersAreIndividuallyEffective() {
            final DailyTransaction subject = new DailyTransaction();
            subject.setDalytranId(TRANSACTION_ID);
            subject.setDalytranTypeCd(TYPE_CODE);
            subject.setDalytranCatCd(CATEGORY_CODE);
            subject.setDalytranSource(SOURCE);
            subject.setDalytranDesc(DESCRIPTION);
            subject.setDalytranAmt(AMOUNT);
            subject.setDalytranMerchantId(MERCHANT_ID);
            subject.setDalytranMerchantName(MERCHANT_NAME);
            subject.setDalytranMerchantCity(MERCHANT_CITY);
            subject.setDalytranMerchantZip(MERCHANT_ZIP);
            subject.setDalytranCardNum(CARD_NUMBER);
            subject.setDalytranOrigTs(ORIGINATION_TIMESTAMP);
            subject.setDalytranProcTs(PROCESSING_TIMESTAMP);

            assertThat(subject.getDalytranId()).isEqualTo(TRANSACTION_ID);
            assertThat(subject.getDalytranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(subject.getDalytranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(subject.getDalytranSource()).isEqualTo(SOURCE);
            assertThat(subject.getDalytranDesc()).isEqualTo(DESCRIPTION);
            assertThat(subject.getDalytranAmt()).isEqualTo(AMOUNT);
            assertThat(subject.getDalytranMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(subject.getDalytranMerchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(subject.getDalytranMerchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(subject.getDalytranMerchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(subject.getDalytranCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(subject.getDalytranOrigTs()).isEqualTo(ORIGINATION_TIMESTAMP);
            assertThat(subject.getDalytranProcTs()).isEqualTo(PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("a setter changes exactly its own field and leaves the other twelve untouched")
        void aSetterChangesExactlyItsOwnField() {
            final DailyTransaction subject = transaction();
            subject.setDalytranSource("OPERATOR  ");
            assertThat(subject.getDalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(subject.getDalytranId()).isEqualTo(TRANSACTION_ID);
            assertThat(subject.getDalytranAmt()).isEqualTo(AMOUNT);
            assertThat(subject.getDalytranProcTs()).isEqualTo(PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("a setter accepts null, because nullability is enforced by the column")
        void aSetterAcceptsNull() {
            final DailyTransaction subject = transaction();
            subject.setDalytranAmt(null);
            subject.setDalytranCardNum(null);
            subject.setDalytranProcTs(null);
            assertThat(subject.getDalytranAmt()).isNull();
            assertThat(subject.getDalytranCardNum()).isNull();
            assertThat(subject.getDalytranProcTs()).isNull();
        }
    }

    @Nested
    @DisplayName("Identity: the primary key alone")
    class Identity {

        @Test
        @DisplayName("a record equals itself")
        void aRecordEqualsItself() {
            final DailyTransaction subject = transaction();
            assertThat(subject).isEqualTo(subject);
        }

        @Test
        @DisplayName("two records with the same identifier are equal even when every other field differs")
        void thePrimaryKeyAloneDeterminesEquality() {
            final DailyTransaction left = transaction();
            final DailyTransaction right = new DailyTransaction(TRANSACTION_ID, "02", "0001",
                    "OPERATOR  ", "RETURN", BigDecimal.ZERO, "999999999", "OTHER", "PORTLAND",
                    "97201-0000", "5555555555554444", "1999-12-31 00:00:00.000000",
                    "1999-12-31 00:00:00.000000");
            assertThat(left).isEqualTo(right);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two records with different identifiers are unequal even when every other field matches")
        void aDifferentIdentifierMakesTwoRecordsUnequal() {
            final DailyTransaction left = transaction();
            final DailyTransaction right = new DailyTransaction("0000000000000002", TYPE_CODE,
                    CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                    MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER, ORIGINATION_TIMESTAMP,
                    PROCESSING_TIMESTAMP);
            assertThat(left).isNotEqualTo(right);
        }

        @Test
        @DisplayName("the identifier is compared exactly, so a shortened form is a different record")
        void theIdentifierIsComparedExactly() {
            final DailyTransaction padded = new DailyTransaction();
            padded.setDalytranId(TRANSACTION_ID);
            final DailyTransaction shortened = new DailyTransaction();
            shortened.setDalytranId("1");
            assertThat(padded).isNotEqualTo(shortened);
        }

        @Test
        @DisplayName("a record is not equal to null and not equal to a foreign type")
        void aRecordIsNotEqualToNullOrAForeignType() {
            final DailyTransaction subject = transaction();
            assertThat(subject).isNotEqualTo(null);
            assertThat(subject.equals(TRANSACTION_ID)).isFalse();
            assertThat(subject.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("two unset records are equal, because both carry a null key")
        void twoUnsetRecordsAreEqual() {
            assertThat(new DailyTransaction()).isEqualTo(new DailyTransaction());
        }

        @Test
        @DisplayName("changing the amount or the card number does not change the hash code, which is why neither "
                + "participates: a record in a set must not move")
        void changingAMutableFieldDoesNotChangeTheHashCode() {
            final DailyTransaction subject = transaction();
            final int before = subject.hashCode();
            subject.setDalytranAmt(new BigDecimal("-999999999.99"));
            subject.setDalytranCardNum("0000000000000000");
            subject.setDalytranProcTs("1970-01-01 00:00:00.000000");
            assertThat(subject.hashCode()).isEqualTo(before);
        }

        @Test
        @DisplayName("a record remains findable in a hash set after every mutable field has changed")
        void aRecordRemainsFindableInAHashSetAfterMutation() {
            final Set<DailyTransaction> transactions = new HashSet<>();
            final DailyTransaction subject = transaction();
            transactions.add(subject);
            subject.setDalytranAmt(BigDecimal.ZERO);
            subject.setDalytranMerchantName("CHANGED");
            assertThat(transactions).contains(subject);
            assertThat(transactions.contains(transaction())).isTrue();
        }

        @Test
        @DisplayName("a record is usable as a map key across a state change")
        void aRecordIsUsableAsAMapKeyAcrossAStateChange() {
            final Map<DailyTransaction, String> byRecord = new HashMap<>();
            final DailyTransaction subject = transaction();
            byRecord.put(subject, "staged");
            subject.setDalytranAmt(BigDecimal.ONE);
            assertThat(byRecord.get(subject)).isEqualTo("staged");
            assertThat(byRecord.get(transaction())).isEqualTo("staged");
        }

        @Test
        @DisplayName("the hash code is stable across repeated invocations")
        void theHashCodeIsStableAcrossRepeatedInvocations() {
            final DailyTransaction subject = transaction();
            assertThat(subject.hashCode()).isEqualTo(subject.hashCode());
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering: the identifier and the two classification codes only")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and carries the identifier, the type code and the category code")
        void theRenderingCarriesTheKeyAndTheClassificationCodes() {
            assertThat(transaction().toString())
                    .isEqualTo("DailyTransaction[dalytranId=" + TRANSACTION_ID
                            + ", dalytranTypeCd=" + TYPE_CODE
                            + ", dalytranCatCd=" + CATEGORY_CODE + "]");
        }

        @Test
        @DisplayName("the card number never appears in the rendering, because it is a primary account number")
        void theCardNumberNeverAppearsInTheRendering() {
            assertThat(transaction().toString())
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("4111")
                    .doesNotContain("dalytranCardNum");
        }

        @Test
        @DisplayName("the amount never appears in the rendering, because it is financial data")
        void theAmountNeverAppearsInTheRendering() {
            assertThat(transaction().toString())
                    .doesNotContain("104.50")
                    .doesNotContain("dalytranAmt");
        }

        @Test
        @DisplayName("no merchant value appears in the rendering, because a merchant name and city together "
                + "localise the cardholder")
        void noMerchantValueAppearsInTheRendering() {
            assertThat(transaction().toString())
                    .doesNotContain(MERCHANT_ID)
                    .doesNotContain(MERCHANT_NAME)
                    .doesNotContain(MERCHANT_CITY)
                    .doesNotContain(MERCHANT_ZIP);
        }

        @Test
        @DisplayName("neither timestamp nor the description appears in the rendering")
        void neitherTimestampNorTheDescriptionAppearsInTheRendering() {
            assertThat(transaction().toString())
                    .doesNotContain(ORIGINATION_TIMESTAMP)
                    .doesNotContain(PROCESSING_TIMESTAMP)
                    .doesNotContain(DESCRIPTION);
        }

        @Test
        @DisplayName("the component list carries no credential or financial marker")
        void theComponentListCarriesNothingSensitive() {
            final String rendering = transaction().toString();
            final String componentList =
                    rendering.substring(rendering.indexOf('[') + 1, rendering.length() - 1);
            assertThat(componentList.toUpperCase(Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "$2A$", "CARDNUM", "AMT");
        }

        @Test
        @DisplayName("an unset record renders without throwing, so a diagnostic during provider instantiation is "
                + "safe")
        void anUnsetRecordRendersWithoutThrowing() {
            assertThat(new DailyTransaction().toString())
                    .startsWith("DailyTransaction[")
                    .contains("null")
                    .endsWith("]");
        }

        @Test
        @DisplayName("a hostile value planted in a redacted field still cannot reach the rendering")
        void aHostileValuePlantedInARedactedFieldCannotReachTheRendering() {
            final DailyTransaction subject = transaction();
            subject.setDalytranCardNum("CANARY-CARD-NUM-");
            subject.setDalytranMerchantName("CANARY-MERCHANT");
            subject.setDalytranDesc("CANARY-DESCRIPTION");
            assertThat(subject.toString()).doesNotContain("CANARY");
        }
    }
}
