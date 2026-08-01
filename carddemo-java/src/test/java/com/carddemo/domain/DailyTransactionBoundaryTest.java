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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DailyTransaction}, the three-hundred-fifty-byte daily-transaction
 * record that the posting run consumes.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVTRA06Y.cpy} describes a three-hundred-fifty-byte record
 * that is byte for byte identical in layout to the posted-transaction record of
 * {@code app/cpy/CVTRA05Y.cpy}, differing only in its field-name prefix. It is retained as
 * a separate entity because it is a distinct dataset with a distinct lifecycle. The ASCII
 * fixture {@code app/data/ASCII/dailytran.txt} carries three hundred such records in
 * 105,300 bytes — two hundred fifty point-of-sale purchases and fifty operator-originated
 * returns — so both signed directions of the amount reach the posting path.</p>
 *
 * <h2>Why the amount is a scaled decimal and is never rescaled here</h2>
 *
 * <p>The legacy amount field is {@code PIC S9(09)V99} zoned decimal. Because no
 * {@code ROUNDED} clause exists anywhere in the estate, every store into a two-decimal
 * field truncates, and that truncation belongs to the zoned-decimal codec of the utility
 * layer rather than to this entity. The entity stores whatever scale it is handed, and the
 * assertions below prove that a negative amount — the operator return — is carried through
 * rather than clamped.</p>
 *
 * <h2>Why the diagnostic rendering omits most of the record</h2>
 *
 * <p>The record carries a primary account number and a monetary amount, neither of which
 * belongs in a log line or an exception message. The rendering is therefore limited to the
 * identifier and the two classification codes, and a test below proves that no card
 * number, amount or merchant value reaches it.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("DailyTransaction: the three-hundred-fifty-byte daily-transaction record")
class DailyTransactionBoundaryTest {

    /** Transaction identifier at its contractual sixteen characters. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** Transaction type code at its contractual two characters. */
    private static final String TYPE_CODE = "01";

    /** Transaction category code at its contractual four characters. */
    private static final String CATEGORY_CODE = "0005";

    /** Origin descriptor at its contractual ten characters, padding included. */
    private static final String SOURCE = "POS TERM  ";

    /** Description at a representative width below the hundred-character column. */
    private static final String DESCRIPTION = "Grocery purchase";

    /** Amount at the contractual scale of two. */
    private static final BigDecimal AMOUNT = new BigDecimal("42.75");

    /** Merchant identifier at its contractual nine characters. */
    private static final String MERCHANT_ID = "000000123";

    /** Merchant name at a representative width below the fifty-character column. */
    private static final String MERCHANT_NAME = "Corner Store";

    /** Merchant city at a representative width below the fifty-character column. */
    private static final String MERCHANT_CITY = "Seattle";

    /** Merchant ZIP at its contractual ten characters, padding included. */
    private static final String MERCHANT_ZIP = "98101     ";

    /** Card number at its contractual sixteen characters. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Origination timestamp at its contractual twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2022-01-01 10:00:00.000000";

    /** Processing timestamp at its contractual twenty-six characters. */
    private static final String PROCESS_TIMESTAMP = "2022-01-02 03:00:00.000000";

    /**
     * Builds the reference record used across the assertions.
     *
     * @return a fully populated daily transaction
     */
    private static DailyTransaction referenceRecord() {
        return new DailyTransaction(TRANSACTION_ID, TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                AMOUNT, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                ORIGIN_TIMESTAMP, PROCESS_TIMESTAMP);
    }

    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @Test
        @DisplayName("all thirteen constructor attributes are returned exactly as supplied")
        void allThirteenAttributesAreReturnedAsSupplied() {
            DailyTransaction record = referenceRecord();

            assertThat(record.getDalytranId()).isEqualTo(TRANSACTION_ID);
            assertThat(record.getDalytranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(record.getDalytranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(record.getDalytranSource()).isEqualTo(SOURCE);
            assertThat(record.getDalytranDesc()).isEqualTo(DESCRIPTION);
            assertThat(record.getDalytranAmt()).isEqualTo(AMOUNT);
            assertThat(record.getDalytranMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(record.getDalytranMerchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(record.getDalytranMerchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(record.getDalytranMerchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(record.getDalytranCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(record.getDalytranOrigTs()).isEqualTo(ORIGIN_TIMESTAMP);
            assertThat(record.getDalytranProcTs()).isEqualTo(PROCESS_TIMESTAMP);
        }

        @Test
        @DisplayName("the identifier keeps every leading zero, so it stays sixteen characters wide")
        void theIdentifierKeepsEveryLeadingZero() {
            assertThat(referenceRecord().getDalytranId())
                    .isEqualTo("0000000000000001")
                    .hasSize(16)
                    .isNotEqualTo("1");
        }

        @Test
        @DisplayName("the origin descriptor keeps its trailing padding at the full ten characters")
        void theOriginDescriptorKeepsItsPadding() {
            assertThat(referenceRecord().getDalytranSource())
                    .isEqualTo("POS TERM  ")
                    .hasSize(10)
                    .endsWith("  ");
        }

        @Test
        @DisplayName("both timestamps keep their full twenty-six characters")
        void bothTimestampsKeepTheirFullWidth() {
            DailyTransaction record = referenceRecord();

            assertThat(record.getDalytranOrigTs()).hasSize(26);
            assertThat(record.getDalytranProcTs()).hasSize(26);
        }

        @Test
        @DisplayName("the amount is stored at the scale supplied and is not rescaled")
        void theAmountIsStoredAtTheScaleSupplied() {
            DailyTransaction record = referenceRecord();

            record.setDalytranAmt(new BigDecimal("1.5"));

            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("1.5"));
            assertThat(record.getDalytranAmt().scale()).isEqualTo(1);
        }

        @Test
        @DisplayName("a negative amount is carried through, which the operator return depends on")
        void aNegativeAmountIsCarriedThrough() {
            DailyTransaction record = new DailyTransaction(TRANSACTION_ID, TYPE_CODE, CATEGORY_CODE,
                    "OPERATOR  ", "Return", new BigDecimal("-42.75"), MERCHANT_ID, MERCHANT_NAME,
                    MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER, ORIGIN_TIMESTAMP, PROCESS_TIMESTAMP);

            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("-42.75"));
            assertThat(record.getDalytranSource()).isEqualTo("OPERATOR  ");
        }

        @Test
        @DisplayName("a zero amount is stored at its supplied scale")
        void aZeroAmountIsStoredAtItsSuppliedScale() {
            DailyTransaction record = referenceRecord();

            record.setDalytranAmt(new BigDecimal("0.00"));

            assertThat(record.getDalytranAmt()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(record.getDalytranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an unpopulated record")
        void theNoArgumentConstructorYieldsAnUnpopulatedRecord() {
            DailyTransaction record = new DailyTransaction();

            assertThat(record.getDalytranId()).isNull();
            assertThat(record.getDalytranTypeCd()).isNull();
            assertThat(record.getDalytranCatCd()).isNull();
            assertThat(record.getDalytranSource()).isNull();
            assertThat(record.getDalytranDesc()).isNull();
            assertThat(record.getDalytranAmt()).isNull();
            assertThat(record.getDalytranMerchantId()).isNull();
            assertThat(record.getDalytranMerchantName()).isNull();
            assertThat(record.getDalytranMerchantCity()).isNull();
            assertThat(record.getDalytranMerchantZip()).isNull();
            assertThat(record.getDalytranCardNum()).isNull();
            assertThat(record.getDalytranOrigTs()).isNull();
            assertThat(record.getDalytranProcTs()).isNull();
        }

        @Test
        @DisplayName("every setter replaces its value verbatim, with no normalisation")
        void everySetterReplacesItsValueVerbatim() {
            DailyTransaction record = new DailyTransaction();

            record.setDalytranId(" 1             ");
            record.setDalytranTypeCd(" 2");
            record.setDalytranCatCd("  15");
            record.setDalytranSource("System    ");
            record.setDalytranDesc("  padded description  ");
            record.setDalytranAmt(new BigDecimal("0.010"));
            record.setDalytranMerchantId("00000000 ");
            record.setDalytranMerchantName("  merchant  ");
            record.setDalytranMerchantCity("  city  ");
            record.setDalytranMerchantZip("  00000   ");
            record.setDalytranCardNum("0000000000000000");
            record.setDalytranOrigTs("1970-01-01 00:00:00.000000");
            record.setDalytranProcTs("1970-01-02 00:00:00.000000");

            assertThat(record.getDalytranId()).isEqualTo(" 1             ");
            assertThat(record.getDalytranTypeCd()).isEqualTo(" 2");
            assertThat(record.getDalytranCatCd()).isEqualTo("  15");
            assertThat(record.getDalytranSource()).isEqualTo("System    ");
            assertThat(record.getDalytranDesc()).isEqualTo("  padded description  ");
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("0.010"));
            assertThat(record.getDalytranMerchantId()).isEqualTo("00000000 ");
            assertThat(record.getDalytranMerchantName()).isEqualTo("  merchant  ");
            assertThat(record.getDalytranMerchantCity()).isEqualTo("  city  ");
            assertThat(record.getDalytranMerchantZip()).isEqualTo("  00000   ");
            assertThat(record.getDalytranCardNum()).isEqualTo("0000000000000000");
            assertThat(record.getDalytranOrigTs()).isEqualTo("1970-01-01 00:00:00.000000");
            assertThat(record.getDalytranProcTs()).isEqualTo("1970-01-02 00:00:00.000000");
        }

        @Test
        @DisplayName("every attribute may be set back to null")
        void everyAttributeMayBeSetBackToNull() {
            DailyTransaction record = referenceRecord();

            record.setDalytranId(null);
            record.setDalytranTypeCd(null);
            record.setDalytranCatCd(null);
            record.setDalytranSource(null);
            record.setDalytranDesc(null);
            record.setDalytranAmt(null);
            record.setDalytranMerchantId(null);
            record.setDalytranMerchantName(null);
            record.setDalytranMerchantCity(null);
            record.setDalytranMerchantZip(null);
            record.setDalytranCardNum(null);
            record.setDalytranOrigTs(null);
            record.setDalytranProcTs(null);

            assertThat(record.getDalytranId()).isNull();
            assertThat(record.getDalytranAmt()).isNull();
            assertThat(record.getDalytranCardNum()).isNull();
            assertThat(record.getDalytranProcTs()).isNull();
        }
    }

    @Nested
    @DisplayName("identity derived from the primary key alone")
    class Identity {

        @Test
        @DisplayName("a record equals itself")
        void aRecordEqualsItself() {
            DailyTransaction record = referenceRecord();

            assertThat(record).isEqualTo(record);
        }

        @Test
        @DisplayName("two records sharing the identifier are equal in both directions")
        void twoRecordsSharingTheIdentifierAreEqual() {
            DailyTransaction first = referenceRecord();
            DailyTransaction second = referenceRecord();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three records sharing the identifier")
        void equalityIsTransitive() {
            DailyTransaction first = referenceRecord();
            DailyTransaction second = referenceRecord();
            DailyTransaction third = referenceRecord();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing identifier breaks equality")
        void aDifferingIdentifierBreaksEquality() {
            DailyTransaction other = referenceRecord();
            other.setDalytranId("0000000000000002");

            assertThat(referenceRecord()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a differing amount does not break equality, because it is excluded")
        void aDifferingAmountDoesNotBreakEquality() {
            DailyTransaction other = referenceRecord();
            other.setDalytranAmt(new BigDecimal("-1.00"));

            assertThat(referenceRecord()).isEqualTo(other).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a null reference is not equal to any record")
        void aNullReferenceIsNotEqualToAnyRecord() {
            assertThat(referenceRecord()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to a record carrying the same identifier")
        void anUnrelatedTypeIsNotEqualToARecord() {
            assertThat(referenceRecord()).isNotEqualTo(TRANSACTION_ID);
        }

        @Test
        @DisplayName("two unpopulated records are equal, so a provider-instantiated row is coherent")
        void twoUnpopulatedRecordsAreEqual() {
            assertThat(new DailyTransaction())
                    .isEqualTo(new DailyTransaction())
                    .hasSameHashCodeAs(new DailyTransaction());
        }

        @Test
        @DisplayName("an unpopulated record is not equal to a populated one")
        void anUnpopulatedRecordIsNotEqualToAPopulatedOne() {
            assertThat(new DailyTransaction()).isNotEqualTo(referenceRecord());
        }

        @Test
        @DisplayName("equal records hash alike and hashing is stable across calls")
        void equalRecordsHashAlike() {
            DailyTransaction record = referenceRecord();

            assertThat(record).hasSameHashCodeAs(referenceRecord());
            assertThat(record.hashCode()).isEqualTo(record.hashCode());
        }

        @Test
        @DisplayName("mutating every non-key attribute leaves the record retrievable from a map")
        void mutatingEveryNonKeyAttributeLeavesTheRecordRetrievable() {
            DailyTransaction record = referenceRecord();
            Map<DailyTransaction, String> index = new HashMap<>();
            index.put(record, "seeded");

            record.setDalytranTypeCd("02");
            record.setDalytranCatCd("0006");
            record.setDalytranSource("OPERATOR  ");
            record.setDalytranDesc("edited");
            record.setDalytranAmt(BigDecimal.ZERO);
            record.setDalytranMerchantId("999999999");
            record.setDalytranMerchantName("other");
            record.setDalytranMerchantCity("other city");
            record.setDalytranMerchantZip("00000     ");
            record.setDalytranCardNum("5555555555554444");
            record.setDalytranOrigTs("1970-01-01 00:00:00.000000");
            record.setDalytranProcTs("1970-01-01 00:00:00.000000");

            assertThat(index).containsEntry(record, "seeded");
            assertThat(index).containsEntry(referenceRecord(), "seeded");
        }
    }

    @Nested
    @DisplayName("redacted diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering carries the identifier and the two classification codes only")
        void theRenderingCarriesTheIdentifierAndClassificationCodesOnly() {
            assertThat(referenceRecord().toString()).isEqualTo(
                    "DailyTransaction[dalytranId=" + TRANSACTION_ID
                            + ", dalytranTypeCd=" + TYPE_CODE
                            + ", dalytranCatCd=" + CATEGORY_CODE + "]");
        }

        @Test
        @DisplayName("the card number never reaches the rendering")
        void theCardNumberNeverReachesTheRendering() {
            assertThat(referenceRecord().toString())
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("dalytranCardNum");
        }

        @Test
        @DisplayName("the amount never reaches the rendering")
        void theAmountNeverReachesTheRendering() {
            assertThat(referenceRecord().toString())
                    .doesNotContain("42.75")
                    .doesNotContain("dalytranAmt");
        }

        @Test
        @DisplayName("no merchant value reaches the rendering")
        void noMerchantValueReachesTheRendering() {
            assertThat(referenceRecord().toString())
                    .doesNotContain(MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, "98101");
        }

        @Test
        @DisplayName("an unpopulated record renders without throwing")
        void anUnpopulatedRecordRendersWithoutThrowing() {
            assertThat(new DailyTransaction().toString()).isEqualTo(
                    "DailyTransaction[dalytranId=null, dalytranTypeCd=null, dalytranCatCd=null]");
        }
    }
}
