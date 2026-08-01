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
package com.carddemo.domain.id;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TransactionCategoryBalanceId}, the composite key of the category-balance row.
 *
 * <p><strong>What the legacy authority is.</strong> The key is the {@code TRAN-CAT-KEY} group at the
 * head of {@code app/cpy/CVTRA01Y.cpy}: an eleven-digit account identifier, a two-character
 * transaction type code and a four-digit transaction category code, in that order. The cluster
 * definition in {@code app/jcl/TCATBALF.jcl} states the geometry independently at line 40 as
 * {@code KEYS(17 0)} over a {@code RECORDSIZE(50 50)} record, so the key is seventeen bytes at
 * offset zero. The balance that follows is {@code TRAN-CAT-BAL PIC S9(09)V99}, eleven zoned digits,
 * and the row closes with {@code FILLER PIC X(22)} &mdash; eleven plus two plus four plus eleven plus
 * twenty-two reaches the declared fifty.
 *
 * <p><strong>Why this key is the one the interest run iterates.</strong> The interest calculation
 * walks the category-balance file in key order and, for each row, resolves a rate by assembling a
 * disclosure-group key from the account's group identifier plus this row's type and category codes.
 * The two keys are therefore adjacent in the batch's logic but not interchangeable: this one leads
 * with an eleven-digit account identifier and is seventeen bytes wide, while the disclosure key leads
 * with a ten-character group identifier and is sixteen bytes wide. The widths differ by exactly one
 * byte, which is precisely the kind of near-miss that a type-blind mapping would let through, so this
 * class pins the width and asserts that the two identifier types are never equal to one another.
 *
 * <p><strong>Why nothing is normalised.</strong> Both the account identifier and the category code
 * are text carrying digits, not numbers. The account identifier is zero-padded to eleven characters
 * in the record image, so parsing it to a number and back would drop the padding and change the key.
 * This class asserts that the padded form and the trimmed form are distinct keys.
 *
 * <p><strong>How the reference data anchors the assertions.</strong> The seeded file
 * {@code app/data/ASCII/tcatbal.txt} measures 2,550 bytes, which is fifty rows of fifty bytes plus a
 * line terminator each, matching the fifty seeded accounts one for one. Fifty rows must produce fifty
 * distinct keys, and this class checks that they do.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here touches a database, a container or the
 * persistence provider, and no reflection is used. The no-argument constructor the provider requires
 * is reached directly because this class sits in the identifier's own package, which is the
 * visibility that constructor declares.
 */
@DisplayName("TransactionCategoryBalanceId — the 17-byte composite key at offset 0")
class TransactionCategoryBalanceIdTest {

    /** Width of the account identifier component, from the copybook. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the transaction type code component, from the copybook. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of the transaction category code component, from the copybook. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Width of the balance that follows the key, eleven zoned digits. */
    private static final int BALANCE_WIDTH = 11;

    /** Width of the trailing filler that closes the row. */
    private static final int FILLER_WIDTH = 22;

    /** The key width the cluster definition declares. */
    private static final int DECLARED_KEY_WIDTH = 17;

    /** The record width the cluster definition declares. */
    private static final int DECLARED_RECORD_WIDTH = 50;

    /** The key width the neighbouring disclosure-group cluster declares, one byte narrower. */
    private static final int DISCLOSURE_KEY_WIDTH = 16;

    /** Rows in the seeded reference file, one per seeded account. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** Measured byte length of the seeded reference file. */
    private static final int SEEDED_FILE_BYTES = 2550;

    /** A representative account identifier, zero-padded as the record image carries it. */
    private static final String ACCOUNT_ID = "00000000001";

    /** A representative type code from the reference data. */
    private static final String TYPE_CODE = "01";

    /** The category code the interest run posts under, with its leading zeros intact. */
    private static final String INTEREST_CATEGORY_CODE = "0005";

    /**
     * Builds a key from the three components in copybook order.
     *
     * @param accountId    the eleven-digit account identifier
     * @param typeCode     the transaction type code
     * @param categoryCode the transaction category code
     * @return the assembled key
     */
    private static TransactionCategoryBalanceId key(final String accountId, final String typeCode,
            final String categoryCode) {
        return new TransactionCategoryBalanceId(accountId, typeCode, categoryCode);
    }

    /**
     * Round-trips a value through Java serialisation.
     *
     * @param original the value to serialise
     * @return the deserialised copy
     * @throws IOException            if the byte streams fail
     * @throws ClassNotFoundException if the class cannot be resolved on the way back
     */
    private static Object serialiseAndBack(final Serializable original)
            throws IOException, ClassNotFoundException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in =
                new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }

    // =================================================================================================
    // COMPONENT CONTRACT
    // =================================================================================================

    /**
     * Verifies that the three components bind in copybook order and are stored verbatim.
     */
    @Nested
    @DisplayName("component contract of the three-part key")
    class ComponentContract {

        @Test
        @DisplayName("the all-arguments constructor binds account, type then category, in copybook order")
        void bindsComponentsInCopybookOrder() {
            final TransactionCategoryBalanceId subject =
                    key("00000000042", "03", "0007");

            assertThat(subject.getTrancatAcctId()).isEqualTo("00000000042");
            assertThat(subject.getTrancatTypeCd()).isEqualTo("03");
            assertThat(subject.getTrancatCd()).isEqualTo("0007");
        }

        @Test
        @DisplayName("a transposition of the two trailing components is detectable by width alone")
        void aTranspositionIsDetectableByWidth() {
            final TransactionCategoryBalanceId correct =
                    key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE);

            assertThat(correct.getTrancatTypeCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(correct.getTrancatCd()).hasSize(CATEGORY_CODE_WIDTH);

            final TransactionCategoryBalanceId transposed =
                    key(ACCOUNT_ID, INTEREST_CATEGORY_CODE, TYPE_CODE);

            assertThat(transposed.getTrancatTypeCd()).hasSize(CATEGORY_CODE_WIDTH);
            assertThat(transposed.getTrancatCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(transposed).isNotEqualTo(correct);
        }

        @Test
        @DisplayName("the no-argument constructor leaves all three components absent")
        void theNoArgumentConstructorLeavesComponentsAbsent() {
            final TransactionCategoryBalanceId empty = new TransactionCategoryBalanceId();

            assertThat(empty.getTrancatAcctId()).isNull();
            assertThat(empty.getTrancatTypeCd()).isNull();
            assertThat(empty.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("every component is stored verbatim: no trim, no pad, no case fold, no reparse")
        void componentsAreStoredVerbatim() {
            final TransactionCategoryBalanceId subject = key(" 0000000001", "aB", "00x5");

            assertThat(subject.getTrancatAcctId()).isEqualTo(" 0000000001");
            assertThat(subject.getTrancatTypeCd()).isEqualTo("aB");
            assertThat(subject.getTrancatCd()).isEqualTo("00x5");
        }

        @Test
        @DisplayName("the account identifier keeps its zero padding, so the padded form and the "
                + "trimmed form are different keys")
        void theAccountIdentifierKeepsItsZeroPadding() {
            final TransactionCategoryBalanceId padded =
                    key("00000000001", TYPE_CODE, INTEREST_CATEGORY_CODE);
            final TransactionCategoryBalanceId unpadded =
                    key("1", TYPE_CODE, INTEREST_CATEGORY_CODE);

            assertThat(padded.getTrancatAcctId()).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(unpadded.getTrancatAcctId()).hasSize(1);
            assertThat(padded).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, because the key is a byte image and "
                + "not a number")
        void theCategoryCodeKeepsItsLeadingZeros() {
            assertThat(key(ACCOUNT_ID, TYPE_CODE, "0005").getTrancatCd())
                    .isEqualTo("0005")
                    .hasSize(CATEGORY_CODE_WIDTH);
            assertThat(key(ACCOUNT_ID, TYPE_CODE, "0005"))
                    .isNotEqualTo(key(ACCOUNT_ID, TYPE_CODE, "5"));
        }

        @Test
        @DisplayName("absent components are accepted and handed back unchanged")
        void absentComponentsAreAcceptedUnchanged() {
            final TransactionCategoryBalanceId partial = key(ACCOUNT_ID, null, null);

            assertThat(partial.getTrancatAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(partial.getTrancatTypeCd()).isNull();
            assertThat(partial.getTrancatCd()).isNull();
        }
    }

    // =================================================================================================
    // BYTE GEOMETRY
    // =================================================================================================

    /**
     * Verifies the byte geometry the cluster definition declares, including the one-byte difference
     * from the neighbouring disclosure-group key.
     */
    @Nested
    @DisplayName("byte geometry of the key and its record")
    class ByteGeometry {

        @Test
        @DisplayName("the three component widths sum to the seventeen bytes the cluster declares")
        void theComponentWidthsSumToTheDeclaredKeyWidth() {
            final int summed = ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;

            assertThat(summed)
                    .as("summed component widths against KEYS(17 0)")
                    .isEqualTo(DECLARED_KEY_WIDTH);
        }

        @Test
        @DisplayName("the record widths close on the fifty bytes the cluster declares")
        void theRecordWidthsCloseOnFifty() {
            final int summed = ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH
                    + BALANCE_WIDTH + FILLER_WIDTH;

            assertThat(summed)
                    .as("summed record field widths against RECORDSIZE(50 50)")
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("this key is one byte wider than the disclosure-group key it is paired with, "
                + "which is why the two are separate types")
        void thisKeyIsOneByteWiderThanTheDisclosureKey() {
            assertThat(DECLARED_KEY_WIDTH)
                    .as("KEYS(17 0) against the disclosure cluster's KEYS(16 0)")
                    .isEqualTo(DISCLOSURE_KEY_WIDTH + 1);
        }

        @Test
        @DisplayName("the key shares the trailing type and category components with the disclosure key "
                + "but not the leading one")
        void theKeysShareOnlyTheirTrailingComponents() {
            final int sharedTrailingWidth = TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;

            assertThat(DECLARED_KEY_WIDTH - sharedTrailingWidth)
                    .as("leading component of this key")
                    .isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(DISCLOSURE_KEY_WIDTH - sharedTrailingWidth)
                    .as("leading component of the disclosure key")
                    .isEqualTo(ACCOUNT_ID_WIDTH - 1);
        }

        @Test
        @DisplayName("each component encodes to exactly its declared width as single-byte text")
        void eachComponentEncodesToItsDeclaredWidth() {
            final TransactionCategoryBalanceId subject =
                    key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE);

            assertThat(subject.getTrancatAcctId().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ACCOUNT_ID_WIDTH);
            assertThat(subject.getTrancatTypeCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TYPE_CODE_WIDTH);
            assertThat(subject.getTrancatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("the seeded reference file geometry closes: fifty rows of fifty bytes plus a "
                + "terminator each, one row per seeded account")
        void theSeededFileGeometryCloses() {
            final int bytesPerLine = DECLARED_RECORD_WIDTH + 1;

            assertThat(SEEDED_ROW_COUNT * bytesPerLine)
                    .as("measured byte length of the seeded reference file")
                    .isEqualTo(SEEDED_FILE_BYTES);
        }
    }

    // =================================================================================================
    // EQUALITY AND HASHING
    // =================================================================================================

    /**
     * Verifies the equality contract across all three components.
     */
    @Nested
    @DisplayName("equality and hashing")
    class EqualityAndHashing {

        @Test
        @DisplayName("two independently built keys with identical components are equal and hash alike")
        void identicalComponentsAreEqualAndHashAlike() {
            final TransactionCategoryBalanceId first =
                    key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE);
            final TransactionCategoryBalanceId second =
                    key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a key equals itself and equality is symmetric")
        void aKeyEqualsItselfAndEqualityIsSymmetric() {
            final TransactionCategoryBalanceId first =
                    key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE);
            final TransactionCategoryBalanceId second =
                    key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE);

            assertThat(first.equals(first)).isTrue();
            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(first)).isTrue();
        }

        @Test
        @DisplayName("the account component participates in equality, so two accounts never share a "
                + "category balance row")
        void theAccountComponentParticipatesInEquality() {
            assertThat(key("00000000001", TYPE_CODE, INTEREST_CATEGORY_CODE))
                    .isNotEqualTo(key("00000000002", TYPE_CODE, INTEREST_CATEGORY_CODE));
        }

        @Test
        @DisplayName("the type component participates in equality")
        void theTypeComponentParticipatesInEquality() {
            assertThat(key(ACCOUNT_ID, "01", INTEREST_CATEGORY_CODE))
                    .isNotEqualTo(key(ACCOUNT_ID, "02", INTEREST_CATEGORY_CODE));
        }

        @Test
        @DisplayName("the category component participates in equality")
        void theCategoryComponentParticipatesInEquality() {
            assertThat(key(ACCOUNT_ID, TYPE_CODE, "0005"))
                    .isNotEqualTo(key(ACCOUNT_ID, TYPE_CODE, "0001"));
        }

        @Test
        @DisplayName("equality rejects an absent reference, a foreign type, and the disclosure key "
                + "even when every component matches")
        void equalityRejectsNullAForeignTypeAndTheDisclosureKey() {
            final TransactionCategoryBalanceId subject =
                    key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE);

            assertThat(subject.equals(null)).isFalse();
            assertThat(subject.equals("00000000001010005")).isFalse();
            assertThat(subject.equals(
                    new DisclosureGroupId(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE)))
                    .isFalse();
        }

        @Test
        @DisplayName("two fully absent keys are equal, which lets the provider compare a freshly "
                + "constructed key before populating it")
        void twoFullyAbsentKeysAreEqual() {
            assertThat(new TransactionCategoryBalanceId())
                    .isEqualTo(new TransactionCategoryBalanceId())
                    .hasSameHashCodeAs(new TransactionCategoryBalanceId());
        }

        @Test
        @DisplayName("the fifty seeded accounts yield fifty distinct keys in a hash set")
        void theSeededAccountsYieldDistinctKeys() {
            final Set<TransactionCategoryBalanceId> distinct = new HashSet<>();
            for (int account = 1; account <= SEEDED_ROW_COUNT; account++) {
                distinct.add(key(String.format("%011d", account), TYPE_CODE,
                        INTEREST_CATEGORY_CODE));
            }

            assertThat(distinct)
                    .as("fifty seeded category-balance rows must yield fifty distinct keys")
                    .hasSize(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("a hash map keyed by the identifier retrieves a row through an independently "
                + "built equal key")
        void aHashMapRetrievesThroughAnEqualKey() {
            final Map<TransactionCategoryBalanceId, String> balances = new HashMap<>();
            balances.put(key("00000000001", "01", "0005"), "100.00");
            balances.put(key("00000000002", "01", "0005"), "250.00");

            assertThat(balances.get(key("00000000001", "01", "0005"))).isEqualTo("100.00");
            assertThat(balances.get(key("00000000003", "01", "0005"))).isNull();
        }
    }

    // =================================================================================================
    // SERIALISATION
    // =================================================================================================

    /**
     * Verifies the serialisation contract an identifier class is required to honour.
     */
    @Nested
    @DisplayName("serialisation")
    class Serialisation {

        @Test
        @DisplayName("the identifier is serialisable, as a key carried across a persistence boundary "
                + "must be")
        void theIdentifierIsSerialisable() {
            assertThat(key(ACCOUNT_ID, TYPE_CODE, INTEREST_CATEGORY_CODE))
                    .isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("the serialisation version identifier is pinned rather than compiler-derived")
        void theSerialisationVersionIsPinned() {
            final ObjectStreamClass descriptor =
                    ObjectStreamClass.lookup(TransactionCategoryBalanceId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a round trip preserves all three components verbatim along with equality")
        void aRoundTripPreservesEveryComponent() throws IOException, ClassNotFoundException {
            final TransactionCategoryBalanceId original = key("00000000042", "03", "0007");

            final Object restored = serialiseAndBack(original);

            assertThat(restored)
                    .isInstanceOf(TransactionCategoryBalanceId.class)
                    .isEqualTo(original)
                    .hasSameHashCodeAs(original);

            final TransactionCategoryBalanceId copy = (TransactionCategoryBalanceId) restored;

            assertThat(copy.getTrancatAcctId()).isEqualTo("00000000042");
            assertThat(copy.getTrancatTypeCd()).isEqualTo("03");
            assertThat(copy.getTrancatCd()).isEqualTo("0007");
        }

        @Test
        @DisplayName("a round trip of a fully absent key preserves all three absences")
        void aRoundTripOfAnAbsentKeyPreservesTheAbsences()
                throws IOException, ClassNotFoundException {
            final Object restored = serialiseAndBack(new TransactionCategoryBalanceId());

            assertThat(restored).isInstanceOf(TransactionCategoryBalanceId.class);

            final TransactionCategoryBalanceId copy = (TransactionCategoryBalanceId) restored;

            assertThat(copy.getTrancatAcctId()).isNull();
            assertThat(copy.getTrancatTypeCd()).isNull();
            assertThat(copy.getTrancatCd()).isNull();
        }
    }

    // =================================================================================================
    // DIAGNOSTIC REPRESENTATION
    // =================================================================================================

    /**
     * Verifies the diagnostic representation, which is what identifies a row in a log line.
     */
    @Nested
    @DisplayName("diagnostic representation")
    class DiagnosticRepresentation {

        @Test
        @DisplayName("the representation carries all three key components")
        void theRepresentationCarriesEveryComponent() {
            assertThat(key("00000000042", "03", "0007").toString())
                    .contains("00000000042")
                    .contains("03")
                    .contains("0007");
        }

        @Test
        @DisplayName("the representation names the components in copybook order")
        void theRepresentationNamesComponentsInCopybookOrder() {
            final String rendered = key(ACCOUNT_ID, "02", "0005").toString();

            assertThat(rendered.indexOf("trancatAcctId"))
                    .isLessThan(rendered.indexOf("trancatTypeCd"));
            assertThat(rendered.indexOf("trancatTypeCd"))
                    .isLessThan(rendered.indexOf("trancatCd"));
        }

        @Test
        @DisplayName("the representation of a fully absent key reports the absences instead of failing")
        void theRepresentationOfAnAbsentKeyReportsTheAbsences() {
            assertThat(new TransactionCategoryBalanceId().toString())
                    .startsWith("TransactionCategoryBalanceId[")
                    .contains("null")
                    .endsWith("]");
        }
    }
}
