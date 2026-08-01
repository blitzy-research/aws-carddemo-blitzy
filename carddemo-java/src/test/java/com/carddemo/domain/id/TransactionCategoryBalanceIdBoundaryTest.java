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
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionCategoryBalanceId}, the seventeen-byte composite key
 * of the transaction-category-balance record.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVTRA01Y.cpy} describes a fifty-byte record whose leading
 * seventeen bytes form the key: an eleven-byte account identifier at offset zero, a
 * two-byte transaction type code at offset eleven and a four-byte transaction category
 * code at offset thirteen. The cluster definition in {@code app/jcl/TCATBALF.jcl}
 * corroborates the width and offset with {@code KEYS(17 0)} over an indexed cluster, and
 * the file description in the interest-calculation batch program splits the same
 * fifty-byte image into a seventeen-byte key and a remainder.</p>
 *
 * <h2>Why external representation is preserved exactly</h2>
 *
 * <p>Every component is text rather than a number because the fixed-width layout gives
 * leading zeros meaning: an account identifier of eleven digits must remain eleven
 * characters wide and a four-character category code must not collapse to one. Nothing
 * trims, pads or folds case, least of all inside equality and hashing, where
 * normalisation would make two distinct rows share one identity in the persistence
 * context. The assertions below prove each of those properties directly.</p>
 *
 * <h2>The deliberate name collision with the transaction-category key</h2>
 *
 * <p>Two copybooks in the estate name their key group identically while describing
 * unrelated keys: this one is seventeen bytes wide and leads with an account identifier,
 * whereas the transaction-category key is six bytes wide and carries no account
 * identifier at all. The two Java types share no supertype beyond {@code Object}, and a
 * test below proves that neither is equal to the other even when handed the same
 * component values, so the collision cannot silently merge two identities.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("TransactionCategoryBalanceId: the seventeen-byte composite key of the balance record")
class TransactionCategoryBalanceIdBoundaryTest {

    /** Account identifier component, at its contractual eleven characters. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Transaction type code component, at its contractual two characters. */
    private static final String TYPE_CODE = "01";

    /** Transaction category code component, at its contractual four characters. */
    private static final String CATEGORY_CODE = "0005";

    /**
     * Builds the reference key used by the equality assertions.
     *
     * @return a key carrying the three reference component values
     */
    private static TransactionCategoryBalanceId referenceKey() {
        return new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);
    }

    @Nested
    @DisplayName("component carriage")
    class ComponentCarriage {

        @Test
        @DisplayName("the three components are returned exactly as supplied")
        void theThreeComponentsAreReturnedAsSupplied() {
            TransactionCategoryBalanceId key = referenceKey();

            assertThat(key.getTrancatAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(key.getTrancatTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getTrancatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the account identifier keeps every leading zero, so it stays eleven wide")
        void theAccountIdentifierKeepsEveryLeadingZero() {
            TransactionCategoryBalanceId key = referenceKey();

            assertThat(key.getTrancatAcctId())
                    .isEqualTo("00000000001")
                    .hasSize(11)
                    .isNotEqualTo("1");
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, so it stays four wide")
        void theCategoryCodeKeepsItsLeadingZeros() {
            assertThat(referenceKey().getTrancatCd())
                    .isEqualTo("0005")
                    .hasSize(4)
                    .isNotEqualTo("5");
        }

        @Test
        @DisplayName("trailing space padding is retained rather than trimmed")
        void trailingPaddingIsRetained() {
            TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("1          ", TYPE_CODE, CATEGORY_CODE);

            assertThat(key.getTrancatAcctId()).isEqualTo("1          ").hasSize(11);
        }

        @Test
        @DisplayName("no component is case folded")
        void noComponentIsCaseFolded() {
            TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("0000000000a", "ab", "000c");

            assertThat(key.getTrancatAcctId()).isEqualTo("0000000000a");
            assertThat(key.getTrancatTypeCd()).isEqualTo("ab");
            assertThat(key.getTrancatCd()).isEqualTo("000c");
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an all-null key")
        void theNoArgumentConstructorYieldsAnAllNullKey() {
            TransactionCategoryBalanceId key = new TransactionCategoryBalanceId();

            assertThat(key.getTrancatAcctId()).isNull();
            assertThat(key.getTrancatTypeCd()).isNull();
            assertThat(key.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("a null component is carried through rather than rejected or defaulted")
        void aNullComponentIsCarriedThrough() {
            TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId(null, TYPE_CODE, null);

            assertThat(key.getTrancatAcctId()).isNull();
            assertThat(key.getTrancatTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getTrancatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("equality across all three components")
    class Equality {

        @Test
        @DisplayName("a key equals itself")
        void aKeyEqualsItself() {
            TransactionCategoryBalanceId key = referenceKey();

            assertThat(key).isEqualTo(key);
        }

        @Test
        @DisplayName("two keys with identical components are equal in both directions")
        void twoIdenticalKeysAreEqualInBothDirections() {
            TransactionCategoryBalanceId first = referenceKey();
            TransactionCategoryBalanceId second = referenceKey();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three identical keys")
        void equalityIsTransitive() {
            TransactionCategoryBalanceId first = referenceKey();
            TransactionCategoryBalanceId second = referenceKey();
            TransactionCategoryBalanceId third = referenceKey();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing account identifier alone breaks equality")
        void aDifferingAccountIdentifierBreaksEquality() {
            assertThat(referenceKey()).isNotEqualTo(
                    new TransactionCategoryBalanceId("00000000002", TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("a differing transaction type component alone breaks equality")
        void aDifferingTypeComponentBreaksEquality() {
            assertThat(referenceKey()).isNotEqualTo(
                    new TransactionCategoryBalanceId(ACCOUNT_ID, "02", CATEGORY_CODE));
        }

        @Test
        @DisplayName("a differing category component alone breaks equality")
        void aDifferingCategoryComponentBreaksEquality() {
            assertThat(referenceKey()).isNotEqualTo(
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, "0006"));
        }

        @Test
        @DisplayName("a zero-suppressed account identifier is not equal to its padded form")
        void aZeroSuppressedAccountIdentifierIsNotEqualToItsPaddedForm() {
            assertThat(referenceKey())
                    .isNotEqualTo(new TransactionCategoryBalanceId("1", TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("a null reference is not equal to any key")
        void aNullReferenceIsNotEqualToAnyKey() {
            assertThat(referenceKey()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to a key, even with the same component values")
        void anUnrelatedTypeIsNotEqualToAKey() {
            assertThat(referenceKey()).isNotEqualTo(ACCOUNT_ID + TYPE_CODE + CATEGORY_CODE);
        }

        @Test
        @DisplayName("the six-byte transaction-category key is not equal despite the name collision")
        void theTransactionCategoryKeyIsNotEqualDespiteTheNameCollision() {
            TransactionCategoryBalanceId balanceKey = referenceKey();
            TransactionCategoryId categoryKey = new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE);

            assertThat(balanceKey).isNotEqualTo(categoryKey);
            assertThat(categoryKey).isNotEqualTo(balanceKey);
        }

        @Test
        @DisplayName("the sibling disclosure-group key is not equal to a balance key")
        void theSiblingDisclosureGroupKeyIsNotEqual() {
            TransactionCategoryBalanceId balanceKey = referenceKey();
            DisclosureGroupId disclosureKey =
                    new DisclosureGroupId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(balanceKey).isNotEqualTo(disclosureKey);
            assertThat(disclosureKey).isNotEqualTo(balanceKey);
        }

        @Test
        @DisplayName("two all-null keys are equal, so a provider-instantiated key behaves coherently")
        void twoAllNullKeysAreEqual() {
            assertThat(new TransactionCategoryBalanceId())
                    .isEqualTo(new TransactionCategoryBalanceId());
        }

        @Test
        @DisplayName("an all-null key is not equal to a populated key")
        void anAllNullKeyIsNotEqualToAPopulatedKey() {
            assertThat(new TransactionCategoryBalanceId()).isNotEqualTo(referenceKey());
        }
    }

    @Nested
    @DisplayName("hashing consistent with equality")
    class Hashing {

        @Test
        @DisplayName("equal keys hash alike")
        void equalKeysHashAlike() {
            assertThat(referenceKey()).hasSameHashCodeAs(referenceKey());
        }

        @Test
        @DisplayName("hashing is stable across repeated calls on the same key")
        void hashingIsStableAcrossCalls() {
            TransactionCategoryBalanceId key = referenceKey();

            assertThat(key.hashCode()).isEqualTo(key.hashCode());
        }

        @Test
        @DisplayName("an all-null key hashes without throwing")
        void anAllNullKeyHashesWithoutThrowing() {
            assertThat(new TransactionCategoryBalanceId().hashCode())
                    .isEqualTo(new TransactionCategoryBalanceId().hashCode());
        }

        @Test
        @DisplayName("a key round-trips as a hash-map key, which is what a persistence context needs")
        void aKeyRoundTripsAsAHashMapKey() {
            Map<TransactionCategoryBalanceId, String> index = new HashMap<>();
            index.put(referenceKey(), "balance row");

            assertThat(index).containsEntry(referenceKey(), "balance row");
            assertThat(index.get(new TransactionCategoryBalanceId("1", TYPE_CODE, CATEGORY_CODE)))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and lists all three components unquoted")
        void theRenderingNamesTheTypeAndListsAllThreeComponents() {
            assertThat(referenceKey().toString()).isEqualTo(
                    "TransactionCategoryBalanceId[trancatAcctId=" + ACCOUNT_ID
                            + ", trancatTypeCd=" + TYPE_CODE
                            + ", trancatCd=" + CATEGORY_CODE + "]");
        }

        @Test
        @DisplayName("the rendering lists exactly three assignments and no monetary value")
        void theRenderingListsExactlyThreeAssignments() {
            String rendered = referenceKey().toString();

            assertThat(rendered).containsOnlyOnce("trancatAcctId=");
            assertThat(rendered).containsOnlyOnce("trancatTypeCd=");
            assertThat(rendered).containsOnlyOnce("trancatCd=");
            assertThat(rendered.chars().filter(character -> character == '=').count()).isEqualTo(3);
            assertThat(rendered).doesNotContain(".");
        }

        @Test
        @DisplayName("an all-null key renders without throwing")
        void anAllNullKeyRendersWithoutThrowing() {
            assertThat(new TransactionCategoryBalanceId().toString()).isEqualTo(
                    "TransactionCategoryBalanceId[trancatAcctId=null, "
                            + "trancatTypeCd=null, trancatCd=null]");
        }
    }

    @Nested
    @DisplayName("serializability, which a composite key must offer")
    class Serializability {

        @Test
        @DisplayName("the type is serializable")
        void theTypeIsSerializable() {
            assertThat(referenceKey()).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("a key survives a serialization round trip with its components and equality intact")
        void aKeySurvivesASerializationRoundTrip() throws IOException, ClassNotFoundException {
            TransactionCategoryBalanceId original = referenceKey();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ObjectOutputStream writer = new ObjectOutputStream(sink)) {
                writer.writeObject(original);
            }

            Object restored;
            try (ObjectInputStream reader =
                         new ObjectInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
                restored = reader.readObject();
            }

            assertThat(restored)
                    .isInstanceOf(TransactionCategoryBalanceId.class)
                    .isEqualTo(original)
                    .hasSameHashCodeAs(original);
            assertThat(((TransactionCategoryBalanceId) restored).getTrancatAcctId())
                    .isEqualTo(ACCOUNT_ID);
        }
    }
}
