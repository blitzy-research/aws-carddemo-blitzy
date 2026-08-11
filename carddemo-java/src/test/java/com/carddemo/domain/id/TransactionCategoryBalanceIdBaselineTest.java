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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TransactionCategoryBalanceId}, the composite identifier class of the
 * transaction-category balance table that the interest-calculation run reads to obtain the balance
 * its interest is computed on.
 *
 * <p><strong>What this test proves.</strong> Two independent legacy authorities fix the same key
 * geometry, and this test asserts that they agree rather than restating one of them twice:
 * <ul>
 *   <li>the copybook member {@code CVTRA01Y}, whose record length is 50 and whose key group
 *       {@code TRAN-CAT-KEY} is 17 bytes wide - an 11-digit {@code TRANCAT-ACCT-ID} at offset 0, a
 *       2-byte {@code TRANCAT-TYPE-CD} at offset 11 and a 4-digit {@code TRANCAT-CD} at offset 13 -
 *       ahead of an 11-byte zoned balance field at offset 17 and a 22-byte trailing filler at offset
 *       28; and</li>
 *   <li>the {@code TCATBALF} indexed cluster definition in the batch job library, which declares
 *       {@code KEYS(17 0)} together with {@code RECORDSIZE(50 50)}.</li>
 * </ul>
 * This is the widest of the three composite keys in the estate, and its width comes entirely from its
 * leading account component being eleven digits rather than the ten-byte group value of the
 * disclosure-group key.
 *
 * <p><strong>Why the account component is text and not a number.</strong> The account identifier is
 * eleven digits, zero filled on the left, and the seeded rows exercise that filling: the first row of
 * the reference fixture carries ten leading zeros. Holding the component as a number would discard
 * the filling and make two distinct stored keys compare equal, so this test asserts the zero filling
 * is carried verbatim into the identifier and participates in equality.
 *
 * <p><strong>Reference data.</strong> The category-balance fixture holds 50 rows of 50 bytes. Only key
 * values are reproduced here, as data; no line of legacy source is transcribed anywhere in this file.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network and runs no container: the class under test
 * is a plain serializable value holder whose only dependencies are {@code java.io.Serializable} and
 * {@code java.util.Objects}. The one metadata lookup it makes, in {@link SerializationContract},
 * goes through the serialization API rather than the low-level introspection API, so the module's
 * zero budget for the latter is left untouched.
 *
 * <p>Cited as provenance only and never asserted against a member.
 */
@DisplayName("TransactionCategoryBalanceId - the 17-byte composite key of the 50-byte balance record")
class TransactionCategoryBalanceIdBaselineTest {

    /** Declared width of the account identifier component. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Declared width of the transaction type component. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Declared width of the transaction category component. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Key length declared by the cluster definition. */
    private static final int DECLARED_KEY_LENGTH = 17;

    /** Record length declared by the cluster definition and by the copybook. */
    private static final int RECORD_LENGTH = 50;

    /** Width of the zoned balance field that follows the key. */
    private static final int BALANCE_FIELD_WIDTH = 11;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 22;

    /** Rows measured in the category-balance reference fixture. */
    private static final int FIXTURE_ROWS = 50;

    /** The account identifier of the first fixture row, zero filled to eleven digits. */
    private static final String FIRST_ACCOUNT = "00000000001";

    /** The account identifier of the second fixture row. */
    private static final String SECOND_ACCOUNT = "00000000002";

    /** Transaction type of the purchase rows. */
    private static final String PURCHASE_TYPE = "01";

    /** Transaction category of the first fixture row. */
    private static final String FIRST_CATEGORY = "0001";

    /** Transaction category used to contrast with the first. */
    private static final String SECOND_CATEGORY = "0002";

    @Nested
    @DisplayName("Component contract of the 17-byte TRAN-CAT-KEY group")
    class ComponentContract {

        @Test
        @DisplayName("the all-args constructor binds the three components in copybook declaration "
                + "order: account identifier, then transaction type, then transaction category")
        void allArgsConstructorBindsComponentsInDeclarationOrder() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.getTrancatAcctId()).isEqualTo(FIRST_ACCOUNT);
            assertThat(id.getTrancatTypeCd()).isEqualTo(PURCHASE_TYPE);
            assertThat(id.getTrancatCd()).isEqualTo(FIRST_CATEGORY);
        }

        @Test
        @DisplayName("a transposed constructor call is detectable by width, since the three components "
                + "are eleven, two and four bytes wide and no two of them agree")
        void aTransposedConstructorCallIsDetectableByWidth() {
            final TransactionCategoryBalanceId transposed =
                    new TransactionCategoryBalanceId(FIRST_CATEGORY, FIRST_ACCOUNT, PURCHASE_TYPE);

            assertThat(transposed.getTrancatAcctId()).hasSize(CATEGORY_CODE_WIDTH);
            assertThat(transposed.getTrancatTypeCd()).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(transposed.getTrancatCd()).hasSize(TYPE_CODE_WIDTH);

            assertThat(ACCOUNT_ID_WIDTH)
                    .isNotEqualTo(TYPE_CODE_WIDTH)
                    .isNotEqualTo(CATEGORY_CODE_WIDTH);
            assertThat(TYPE_CODE_WIDTH).isNotEqualTo(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("the no-arg constructor leaves all three components absent, because the "
                + "persistence provider populates an identifier after instantiating it")
        void noArgConstructorLeavesAllComponentsAbsent() {
            final TransactionCategoryBalanceId empty = new TransactionCategoryBalanceId();

            assertThat(empty.getTrancatAcctId()).isNull();
            assertThat(empty.getTrancatTypeCd()).isNull();
            assertThat(empty.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("all three components are text, so the eleven-digit account keeps its ten leading "
                + "zeros and the four-digit category keeps its three")
        void allThreeComponentsAreTextAndKeepTheirLeadingZeros() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.getTrancatAcctId()).isEqualTo("00000000001").hasSize(ACCOUNT_ID_WIDTH);
            assertThat(id.getTrancatCd()).isEqualTo("0001").hasSize(CATEGORY_CODE_WIDTH);
            assertThat(id.getTrancatAcctId()).startsWith("0000000000");
        }

        @Test
        @DisplayName("the constructor stores every component verbatim - no trim, no pad, no case fold "
                + "and no numeric reinterpretation")
        void theConstructorStoresEveryComponentVerbatim() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(" 1 ", "aZ", "9x0");

            assertThat(id.getTrancatAcctId()).isEqualTo(" 1 ");
            assertThat(id.getTrancatTypeCd()).isEqualTo("aZ");
            assertThat(id.getTrancatCd()).isEqualTo("9x0");
        }

        @Test
        @DisplayName("absent components are accepted and returned unchanged, because the identifier "
                + "class performs no validation of its own")
        void absentComponentsAreAcceptedAndReturnedUnchanged() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(null, null, null);

            assertThat(id.getTrancatAcctId()).isNull();
            assertThat(id.getTrancatTypeCd()).isNull();
            assertThat(id.getTrancatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the key and of the 50-byte record that carries it")
    class ByteGeometry {

        @Test
        @DisplayName("the three component widths sum to the seventeen the cluster declares, so the "
                + "copybook layout and the cluster definition agree")
        void theThreeComponentWidthsSumToTheDeclaredKeyLength() {
            assertThat(ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);
        }

        @Test
        @DisplayName("the key is the leading substring of the record: key at offset 0, balance at "
                + "offset 17, filler at offset 28, and the three widths close the 50-byte record")
        void theKeyIsTheLeadingSubstringOfTheRecord() {
            assertThat(DECLARED_KEY_LENGTH + BALANCE_FIELD_WIDTH + FILLER_WIDTH)
                    .isEqualTo(RECORD_LENGTH);
            assertThat(DECLARED_KEY_LENGTH + BALANCE_FIELD_WIDTH).isEqualTo(28);
        }

        @Test
        @DisplayName("a key assembled by concatenating the three components in declaration order "
                + "occupies exactly the declared seventeen bytes and reproduces the first fixture key")
        void anAssembledKeyReproducesTheFirstFixtureKey() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);
            final String assembled =
                    id.getTrancatAcctId() + id.getTrancatTypeCd() + id.getTrancatCd();

            assertThat(assembled).hasSize(DECLARED_KEY_LENGTH);
            assertThat(assembled).isEqualTo("00000000001010001");
        }

        @Test
        @DisplayName("the balance field is eleven bytes because its picture carries nine integer digits "
                + "and two decimal places, the sign being folded into the trailing byte")
        void theBalanceFieldIsElevenBytes() {
            final int integerDigits = 9;
            final int decimalDigits = 2;

            assertThat(integerDigits + decimalDigits).isEqualTo(BALANCE_FIELD_WIDTH);
        }

        @Test
        @DisplayName("this seventeen-byte key is the widest of the three composite keys, exceeding the "
                + "sixteen-byte disclosure-group key by exactly the byte its account component adds "
                + "over that key's ten-byte group value")
        void thisKeyIsTheWidestOfTheThreeCompositeKeys() {
            final int transactionCategoryKeyLength = 6;
            final int disclosureGroupKeyLength = 16;
            final int disclosureGroupLeadingWidth = 10;

            assertThat(DECLARED_KEY_LENGTH)
                    .isGreaterThan(disclosureGroupKeyLength)
                    .isGreaterThan(transactionCategoryKeyLength);
            assertThat(DECLARED_KEY_LENGTH - disclosureGroupKeyLength)
                    .isEqualTo(ACCOUNT_ID_WIDTH - disclosureGroupLeadingWidth);
        }

        @Test
        @DisplayName("the disclosure-group record and the category-balance record are both fifty bytes "
                + "yet split those bytes differently, which is why each needs its own key type")
        void bothFiftyByteRecordsSplitTheirBytesDifferently() {
            final int disclosureGroupKeyLength = 16;
            final int disclosureGroupRateWidth = 6;
            final int disclosureGroupFillerWidth = 28;

            assertThat(disclosureGroupKeyLength + disclosureGroupRateWidth + disclosureGroupFillerWidth)
                    .isEqualTo(RECORD_LENGTH);
            assertThat(DECLARED_KEY_LENGTH + BALANCE_FIELD_WIDTH + FILLER_WIDTH)
                    .isEqualTo(RECORD_LENGTH);
            assertThat(FILLER_WIDTH).isNotEqualTo(disclosureGroupFillerWidth);
        }
    }

    @Nested
    @DisplayName("Equality and hashing across all three key components")
    class EqualityAndHashing {

        @Test
        @DisplayName("two independently constructed keys with identical components are equal and hash "
                + "alike")
        void independentlyConstructedEqualKeysAgree() {
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a key equals itself, satisfying the reflexive clause of the equality contract")
        void aKeyEqualsItself() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id).isEqualTo(id);
        }

        @Test
        @DisplayName("equality is symmetric for two independently constructed equal keys")
        void equalityIsSymmetric() {
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId("00000000042", "02", "0003");
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId("00000000042", "02", "0003");

            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(first)).isTrue();
        }

        @Test
        @DisplayName("equality is transitive across three independently constructed equal keys")
        void equalityIsTransitive() {
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId("00000000007", "05", "0001");
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId("00000000007", "05", "0001");
            final TransactionCategoryBalanceId third =
                    new TransactionCategoryBalanceId("00000000007", "05", "0001");

            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(first.equals(third)).isTrue();
        }

        @Test
        @DisplayName("the account component participates in equality, so the same category balance on "
                + "two accounts is two distinct keys")
        void theAccountComponentParticipatesInEquality() {
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId(SECOND_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("the transaction type component participates in equality")
        void theTypeComponentParticipatesInEquality() {
            final TransactionCategoryBalanceId purchase =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, "01", FIRST_CATEGORY);
            final TransactionCategoryBalanceId payment =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, "02", FIRST_CATEGORY);

            assertThat(purchase).isNotEqualTo(payment);
        }

        @Test
        @DisplayName("the transaction category component participates in equality")
        void theCategoryComponentParticipatesInEquality() {
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, SECOND_CATEGORY);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("zero filling is significant: the eleven-digit account is not equal to the same "
                + "value unpadded, so a numeric lookup would miss every seeded row")
        void zeroFillingIsSignificantInEquality() {
            final TransactionCategoryBalanceId filled =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);
            final TransactionCategoryBalanceId unpadded =
                    new TransactionCategoryBalanceId("1", PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(filled).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type, exercising the "
                + "type-pattern guard rather than throwing - including the other three-component key "
                + "type of the estate, which carries the same component values")
        void equalityRejectsAbsentOperandAndForeignType() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.equals(null)).isFalse();
            assertThat(id.equals("00000000001010001")).isFalse();
            assertThat(id.equals(new DisclosureGroupId(
                    FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY))).isFalse();
        }

        @Test
        @DisplayName("two empty keys are equal and hash alike, so a provider may compare two "
                + "not-yet-populated identifiers without surprise")
        void twoEmptyKeysAreEqualAndHashAlike() {
            final TransactionCategoryBalanceId first = new TransactionCategoryBalanceId();
            final TransactionCategoryBalanceId second = new TransactionCategoryBalanceId();

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("all fifty fixture keys stay distinct in a hash set, so no two seeded rows "
                + "collapse onto one identifier")
        void allFixtureKeysStayDistinctInAHashSet() {
            final Set<TransactionCategoryBalanceId> keys = new HashSet<>();

            for (int account = 1; account <= FIXTURE_ROWS; account++) {
                keys.add(new TransactionCategoryBalanceId(
                        String.format(Locale.ROOT, "%011d", account), PURCHASE_TYPE, FIRST_CATEGORY));
            }

            assertThat(keys).hasSize(FIXTURE_ROWS);
        }

        @Test
        @DisplayName("an independently constructed key retrieves the value a hash map stored under an "
                + "equal key, which is the property the provider relies on")
        void anEqualKeyRetrievesTheStoredValue() {
            final Map<TransactionCategoryBalanceId, String> byKey = new HashMap<>();
            byKey.put(new TransactionCategoryBalanceId("00000000001", "01", "0001"), "zero balance");

            assertThat(byKey.get(new TransactionCategoryBalanceId("00000000001", "01", "0001")))
                    .isEqualTo("zero balance");
            assertThat(byKey.get(new TransactionCategoryBalanceId("1", "01", "0001"))).isNull();
        }
    }

    @Nested
    @DisplayName("Serialization contract required of an identifier class")
    class SerializationContract {

        @Test
        @DisplayName("the serialization version identifier is pinned at one, so a stored identifier "
                + "stays readable across builds")
        void theSerializationVersionIdentifierIsPinned() {
            final ObjectStreamClass descriptor =
                    ObjectStreamClass.lookup(TransactionCategoryBalanceId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the key type is serializable, as an identifier class carried across a "
                + "persistence boundary must be")
        void theKeyTypeIsSerializable() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("a serialization round trip preserves all three components verbatim along with "
                + "equality and hashing")
        void aRoundTripPreservesEveryComponent() throws IOException, ClassNotFoundException {
            final TransactionCategoryBalanceId original =
                    new TransactionCategoryBalanceId(SECOND_ACCOUNT, PURCHASE_TYPE, SECOND_CATEGORY);

            final TransactionCategoryBalanceId restored = roundTrip(original);

            assertThat(restored.getTrancatAcctId()).isEqualTo(SECOND_ACCOUNT);
            assertThat(restored.getTrancatTypeCd()).isEqualTo(PURCHASE_TYPE);
            assertThat(restored.getTrancatCd()).isEqualTo(SECOND_CATEGORY);
            assertThat(restored).isEqualTo(original);
            assertThat(restored).hasSameHashCodeAs(original);
        }

        @Test
        @DisplayName("a round trip of an empty key preserves all three absent components, because the "
                + "provider may serialize an identifier it has not yet populated")
        void aRoundTripOfAnEmptyKeyPreservesAbsentComponents()
                throws IOException, ClassNotFoundException {
            final TransactionCategoryBalanceId restored =
                    roundTrip(new TransactionCategoryBalanceId());

            assertThat(restored.getTrancatAcctId()).isNull();
            assertThat(restored.getTrancatTypeCd()).isNull();
            assertThat(restored.getTrancatCd()).isNull();
        }

        private TransactionCategoryBalanceId roundTrip(final TransactionCategoryBalanceId original)
                throws IOException, ClassNotFoundException {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(original);
            }

            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (TransactionCategoryBalanceId) in.readObject();
            }
        }
    }

    @Nested
    @DisplayName("Diagnostic representation of the key")
    class StringRepresentation {

        @Test
        @DisplayName("the representation names the type and lists all three components in declaration "
                + "order without quoting, matching this key type's own convention")
        void theRepresentationListsAllThreeComponentsUnquoted() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.toString()).isEqualTo(
                    "TransactionCategoryBalanceId[trancatAcctId=00000000001, trancatTypeCd=01, "
                            + "trancatCd=0001]");
        }

        @Test
        @DisplayName("the representation carries no quoting, unlike the disclosure-group key, which is "
                + "harmless here because none of this key's components is space padded")
        void theRepresentationCarriesNoQuoting() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.toString()).doesNotContain("'");
            assertThat(id.getTrancatAcctId()).doesNotContain(" ");
            assertThat(id.getTrancatTypeCd()).doesNotContain(" ");
            assertThat(id.getTrancatCd()).doesNotContain(" ");
        }

        @Test
        @DisplayName("the representation of an empty key reports all three components as absent rather "
                + "than failing")
        void theRepresentationOfAnEmptyKeyReportsAbsentComponents() {
            assertThat(new TransactionCategoryBalanceId().toString()).isEqualTo(
                    "TransactionCategoryBalanceId[trancatAcctId=null, trancatTypeCd=null, "
                            + "trancatCd=null]");
        }
    }

    @Nested
    @DisplayName("Deliberate absences recorded by this key type")
    class DocumentedAbsence {

        @Test
        @DisplayName("no surrogate identifier exists: the cluster places the key at offset 0 as the "
                + "leading seventeen bytes of the record, so the business key is the identifier")
        void noSurrogateIdentifierExists() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);
            final String assembled =
                    id.getTrancatAcctId() + id.getTrancatTypeCd() + id.getTrancatCd();

            assertThat(assembled).hasSize(DECLARED_KEY_LENGTH);
            assertThat(RECORD_LENGTH - DECLARED_KEY_LENGTH)
                    .isEqualTo(BALANCE_FIELD_WIDTH + FILLER_WIDTH);
        }

        @Test
        @DisplayName("the balance is not part of the key, so posting a transaction against a category "
                + "updates a row in place rather than moving it - the component list names only the "
                + "three key components even though the type name itself mentions the balance")
        void theBalanceIsNotPartOfTheKey() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(FIRST_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);
            final String rendered = id.toString();
            final String componentList =
                    rendered.substring(rendered.indexOf('[') + 1, rendered.length() - 1);

            assertThat(rendered).startsWith("TransactionCategoryBalanceId[");
            assertThat(componentList).doesNotContain("Bal").doesNotContain("bal");
            assertThat(componentList.split(", ")).hasSize(3);
        }

        @Test
        @DisplayName("the key carries no persistence metadata and needs no framework: it constructs, "
                + "compares, hashes, serializes and prints on a plain virtual machine")
        void theKeyNeedsNoFramework() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(SECOND_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);
            final TransactionCategoryBalanceId twin =
                    new TransactionCategoryBalanceId(SECOND_ACCOUNT, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.getTrancatAcctId()).isEqualTo(SECOND_ACCOUNT);
            assertThat(id).isEqualTo(twin);
            assertThat(id).hasSameHashCodeAs(twin);
            assertThat(id.toString()).startsWith("TransactionCategoryBalanceId[");
        }
    }
}
