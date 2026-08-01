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
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TransactionCategoryBalanceId}, the composite identifier of the
 * transaction-category-balance table derived from copybook member {@code CVTRA01Y}.
 *
 * <h2>Why an equality test on a key class is not busywork</h2>
 *
 * <p>A JPA {@code @IdClass} is the persistence context's identity map key. If {@code equals} omitted
 * one of the three components, two genuinely distinct rows would compare equal and the provider would
 * hand back the wrong entity - silently, and only under load. The assertions below therefore prove
 * each component participates <em>individually</em>, by holding two of the three constant and varying
 * the third, rather than by the weaker check that two fully identical keys are equal.</p>
 *
 * <h2>The comparison is exact, and that is a fixed-width requirement</h2>
 *
 * <p>The legacy key is 17 bytes: an 11-byte account identifier at offset 0, a 2-byte type code at
 * offset 11 and a 4-byte category code at offset 13. Every component is retained as text precisely so
 * that leading zeros survive, which means {@code "0005"} and {@code "5"} are different category codes
 * and a trimming comparison would merge two distinct rows. No component is trimmed, padded or case
 * folded before comparison, and this test pins that.</p>
 */
@DisplayName("TransactionCategoryBalanceId - the CVTRA01Y composite key")
class TransactionCategoryBalanceIdSecurityTest {

    /** Legacy width of the account identifier component, from {@code PIC 9(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Legacy width of the transaction type code component, from {@code PIC X(02)}. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Legacy width of the transaction category code component, from {@code PIC 9(04)}. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** An account identifier at its legacy width, leading zeros included. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A transaction type code at its legacy width. */
    private static final String TYPE_CODE = "01";

    /** A transaction category code at its legacy width, leading zeros included. */
    private static final String CATEGORY_CODE = "0005";

    private static TransactionCategoryBalanceId key() {
        return new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);
    }

    @Nested
    @DisplayName("Construction and component retention")
    class Construction {

        @Test
        @DisplayName("the three-argument constructor stores each component verbatim, in the contractual key order")
        void theThreeArgumentConstructorStoresEachComponentVerbatim() {
            final TransactionCategoryBalanceId id = key();
            assertThat(id.getTrancatAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(id.getTrancatTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(id.getTrancatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the no-argument constructor exists for the provider and leaves every component unset, which is "
                + "why this type is a class rather than a record")
        void theNoArgumentConstructorLeavesEveryComponentUnset() {
            final TransactionCategoryBalanceId id = new TransactionCategoryBalanceId();
            assertThat(id.getTrancatAcctId()).isNull();
            assertThat(id.getTrancatTypeCd()).isNull();
            assertThat(id.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("no trimming is applied, so a padded component is retained with its padding intact")
        void noTrimmingIsApplied() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId("  11  ", " 1", "5   ");
            assertThat(id.getTrancatAcctId()).isEqualTo("  11  ");
            assertThat(id.getTrancatTypeCd()).isEqualTo(" 1");
            assertThat(id.getTrancatCd()).isEqualTo("5   ");
        }

        @Test
        @DisplayName("leading zeros survive, because every component is retained as text rather than as a number")
        void leadingZerosSurvive() {
            final TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId("00000000011", "01", "0005");
            assertThat(id.getTrancatAcctId()).startsWith("0");
            assertThat(id.getTrancatTypeCd()).startsWith("0");
            assertThat(id.getTrancatCd()).startsWith("0");
        }

        @Test
        @DisplayName("a null component is accepted rather than rejected, because the provider populates the key "
                + "after construction and validation belongs to the caller")
        void aNullComponentIsAccepted() {
            final TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(null, null, null);
            assertThat(id.getTrancatAcctId()).isNull();
            assertThat(id.getTrancatTypeCd()).isNull();
            assertThat(id.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("the three legacy component widths sum to the 17-byte key the cluster definition declares")
        void theComponentWidthsSumToTheKeyWidth() {
            assertThat(ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(17);
            assertThat(ACCOUNT_ID).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(TYPE_CODE).hasSize(TYPE_CODE_WIDTH);
            assertThat(CATEGORY_CODE).hasSize(CATEGORY_CODE_WIDTH);
        }
    }

    @Nested
    @DisplayName("Equality, with every component proven to participate")
    class Equality {

        @Test
        @DisplayName("a key equals itself")
        void aKeyEqualsItself() {
            final TransactionCategoryBalanceId id = key();
            assertThat(id).isEqualTo(id);
        }

        @Test
        @DisplayName("two keys built from the same three components are equal and the relation is symmetric")
        void twoIdenticalKeysAreEqualBothWays() {
            final TransactionCategoryBalanceId first = key();
            final TransactionCategoryBalanceId second = key();
            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three separately constructed but identical keys")
        void equalityIsTransitive() {
            final TransactionCategoryBalanceId first = key();
            final TransactionCategoryBalanceId second = key();
            final TransactionCategoryBalanceId third = key();
            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("varying the account identifier alone breaks equality, so that component participates")
        void theAccountIdentifierParticipates() {
            assertThat(key())
                    .isNotEqualTo(new TransactionCategoryBalanceId("00000000012", TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("varying the transaction type code alone breaks equality, so that component participates")
        void theTypeCodeParticipates() {
            assertThat(key())
                    .isNotEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID, "02", CATEGORY_CODE));
        }

        @Test
        @DisplayName("varying the transaction category code alone breaks equality, so that component participates")
        void theCategoryCodeParticipates() {
            assertThat(key())
                    .isNotEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, "0006"));
        }

        @Test
        @DisplayName("a key is not equal to null")
        void aKeyIsNotEqualToNull() {
            assertThat(key()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("a key is not equal to an unrelated type, including a sibling key class with the same three "
                + "component values")
        void aKeyIsNotEqualToAnUnrelatedType() {
            assertThat(key()).isNotEqualTo(ACCOUNT_ID + TYPE_CODE + CATEGORY_CODE);
            assertThat((Object) key())
                    .isNotEqualTo(new DisclosureGroupId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("comparison is exact rather than numeric, so the zero-padded category code 0005 is not the "
                + "unpadded code 5 - the merge a trimming comparison would cause")
        void comparisonIsExactRatherThanNumeric() {
            assertThat(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, "0005"))
                    .isNotEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, "5"));
        }

        @Test
        @DisplayName("comparison applies no case folding, so a lower-case type code is a different key")
        void comparisonAppliesNoCaseFolding() {
            assertThat(new TransactionCategoryBalanceId(ACCOUNT_ID, "AB", CATEGORY_CODE))
                    .isNotEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID, "ab", CATEGORY_CODE));
        }

        @Test
        @DisplayName("two fully unset keys are equal, and an unset key is not equal to a populated one")
        void unsetKeysCompareConsistently() {
            assertThat(new TransactionCategoryBalanceId())
                    .isEqualTo(new TransactionCategoryBalanceId())
                    .isNotEqualTo(key());
        }
    }

    @Nested
    @DisplayName("Hashing, which must agree with equality for the identity map to work")
    class Hashing {

        @Test
        @DisplayName("equal keys hash equally")
        void equalKeysHashEqually() {
            assertThat(key()).hasSameHashCodeAs(key());
        }

        @Test
        @DisplayName("the hash is stable across repeated invocations on the same instance")
        void theHashIsStable() {
            final TransactionCategoryBalanceId id = key();
            assertThat(id.hashCode()).isEqualTo(id.hashCode()).isEqualTo(id.hashCode());
        }

        @Test
        @DisplayName("an unset key hashes without throwing, because the components are hashed null-safely")
        void anUnsetKeyHashesWithoutThrowing() {
            assertThat(new TransactionCategoryBalanceId().hashCode())
                    .isEqualTo(new TransactionCategoryBalanceId().hashCode());
        }

        @Test
        @DisplayName("the key works as a hash-map key: an equal instance retrieves the mapped value")
        void theKeyWorksAsAHashMapKey() {
            final Map<TransactionCategoryBalanceId, String> map = new HashMap<>();
            map.put(key(), "balance row");
            assertThat(map).containsEntry(key(), "balance row");
        }

        @Test
        @DisplayName("three keys differing in one component each occupy three distinct hash-set slots")
        void keysDifferingInOneComponentAreDistinctInASet() {
            final HashSet<TransactionCategoryBalanceId> keys = new HashSet<>();
            keys.add(key());
            keys.add(new TransactionCategoryBalanceId("00000000012", TYPE_CODE, CATEGORY_CODE));
            keys.add(new TransactionCategoryBalanceId(ACCOUNT_ID, "02", CATEGORY_CODE));
            keys.add(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, "0006"));
            keys.add(key());
            assertThat(keys).hasSize(4);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and lists all three components, so an assertion failure "
                + "identifies which component differs")
        void theRenderingListsAllThreeComponents() {
            assertThat(key().toString())
                    .startsWith("TransactionCategoryBalanceId[")
                    .contains("trancatAcctId=" + ACCOUNT_ID)
                    .contains("trancatTypeCd=" + TYPE_CODE)
                    .contains("trancatCd=" + CATEGORY_CODE)
                    .endsWith("]");
        }

        @Test
        @DisplayName("the rendering of an unset key does not throw and reports each component as absent")
        void theRenderingOfAnUnsetKeyDoesNotThrow() {
            assertThat(new TransactionCategoryBalanceId().toString())
                    .contains("trancatAcctId=null")
                    .contains("trancatTypeCd=null")
                    .contains("trancatCd=null");
        }

        /**
         * The sweep below is applied to the bracketed component list rather than to the whole
         * rendering, and deliberately so. The type's own simple name contains the word
         * {@code Balance}, so an unscoped sweep for a monetary token would match the class name and
         * report a leak that does not exist - the same substring collision that makes a naive
         * containment assertion untrustworthy. Scoping to the body asserts what is actually at
         * stake: that no <em>value</em> in the rendering is a credential or an amount.
         */
        @Test
        @DisplayName("the component list carries no credential and no monetary amount, because this key holds "
                + "neither, so nothing here requires redaction")
        void theRenderingCarriesNothingSensitive() {
            final String rendering = key().toString();
            final String componentList =
                    rendering.substring(rendering.indexOf('[') + 1, rendering.length() - 1);
            assertThat(componentList.toUpperCase(java.util.Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "AMOUNT", "$2A$")
                    .doesNotContain("BAL=", "AMT=");
        }
    }

    @Nested
    @DisplayName("Serializability, which the provider requires of an id class")
    class Serializability {

        @Test
        @DisplayName("the key is serializable")
        void theKeyIsSerializable() {
            assertThat(key()).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("an explicit serialVersionUID of 1 is declared, which -Xlint:all -Werror requires of a "
                + "serializable class")
        void anExplicitSerialVersionUidIsDeclared() {
            assertThat(ObjectStreamClass.lookup(TransactionCategoryBalanceId.class).getSerialVersionUID())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a key survives a serialization round trip equal to the original, components intact")
        void aKeySurvivesASerializationRoundTrip() throws IOException, ClassNotFoundException {
            final TransactionCategoryBalanceId original = key();
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(original);
            }
            final Object restored;
            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                restored = in.readObject();
            }
            assertThat(restored)
                    .isInstanceOf(TransactionCategoryBalanceId.class)
                    .isEqualTo(original)
                    .hasSameHashCodeAs(original);
        }
    }
}
