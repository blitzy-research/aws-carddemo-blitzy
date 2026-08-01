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
 * Unit tests for {@link DisclosureGroupId}, the sixteen-byte composite key of the
 * disclosure-group record.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVTRA02Y.cpy} declares a fifty-byte disclosure-group
 * record whose leading sixteen bytes form the key group: a ten-byte account group
 * identifier at offset zero, a two-byte transaction type code at offset ten and a
 * four-byte transaction category code at offset twelve. The cluster definition in
 * {@code app/jcl/DISCGRP.jcl} corroborates the width and the offset with
 * {@code KEYS(16 0)} over an indexed cluster.</p>
 *
 * <h2>Why exact comparison matters here more than anywhere else in the estate</h2>
 *
 * <p>The interest-calculation program falls back to a default disclosure group when a
 * direct read returns a record-not-found status, and it does so by substituting a
 * seven-character default literal into the ten-byte alphanumeric component, which
 * leaves three trailing spaces in the key. If this type trimmed, padded or folded any
 * component, the padded and unpadded spellings would compare equal in Java while
 * remaining distinct in the fixed-width record, and the fallback would resolve rows the
 * legacy program never reaches. Every assertion below therefore compares values byte
 * for byte and deliberately proves that the two spellings are <em>not</em> equal.</p>
 *
 * <h2>Why the type is a class rather than a record</h2>
 *
 * <p>A persistence provider instantiates a composite-key class reflectively through a
 * no-argument constructor before populating its fields, which a record cannot offer.
 * The no-argument constructor is therefore exercised here from the same package it is
 * declared in, and the resulting all-null key is asserted to behave coherently rather
 * than to throw.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("DisclosureGroupId: the sixteen-byte composite key of the disclosure-group record")
class DisclosureGroupIdBoundaryTest {

    /** Account group identifier component, at its contractual ten characters. */
    private static final String GROUP_ID = "A         ";

    /** The default group literal padded to the full ten-character component width. */
    private static final String PADDED_DEFAULT_GROUP_ID = "DEFAULT   ";

    /** The default group literal as the legacy program spells the substituted value. */
    private static final String UNPADDED_DEFAULT_GROUP_ID = "DEFAULT";

    /** Transaction type code component, at its contractual two characters. */
    private static final String TYPE_CODE = "01";

    /** Transaction category code component, at its contractual four characters. */
    private static final String CATEGORY_CODE = "0005";

    /**
     * Builds the reference key used by the equality assertions.
     *
     * @return a key carrying the three reference component values
     */
    private static DisclosureGroupId referenceKey() {
        return new DisclosureGroupId(GROUP_ID, TYPE_CODE, CATEGORY_CODE);
    }

    @Nested
    @DisplayName("component carriage")
    class ComponentCarriage {

        @Test
        @DisplayName("the three components are returned exactly as supplied")
        void theThreeComponentsAreReturnedAsSupplied() {
            DisclosureGroupId key = referenceKey();

            assertThat(key.getDisAcctGroupId()).isEqualTo(GROUP_ID);
            assertThat(key.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("trailing space padding on the account group component is retained, never trimmed")
        void trailingPaddingOnTheGroupComponentIsRetained() {
            DisclosureGroupId key =
                    new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(key.getDisAcctGroupId())
                    .isEqualTo(PADDED_DEFAULT_GROUP_ID)
                    .hasSize(10)
                    .endsWith("   ");
        }

        @Test
        @DisplayName("a leading zero on the category code component survives, so the code stays four wide")
        void aLeadingZeroOnTheCategoryComponentSurvives() {
            DisclosureGroupId key = new DisclosureGroupId(GROUP_ID, TYPE_CODE, "0005");

            assertThat(key.getDisTranCatCd()).isEqualTo("0005").isNotEqualTo("5");
        }

        @Test
        @DisplayName("no component is case folded")
        void noComponentIsCaseFolded() {
            DisclosureGroupId key = new DisclosureGroupId("zeroapr   ", "aa", "000a");

            assertThat(key.getDisAcctGroupId()).isEqualTo("zeroapr   ");
            assertThat(key.getDisTranTypeCd()).isEqualTo("aa");
            assertThat(key.getDisTranCatCd()).isEqualTo("000a");
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an all-null key")
        void theNoArgumentConstructorYieldsAnAllNullKey() {
            DisclosureGroupId key = new DisclosureGroupId();

            assertThat(key.getDisAcctGroupId()).isNull();
            assertThat(key.getDisTranTypeCd()).isNull();
            assertThat(key.getDisTranCatCd()).isNull();
        }

        @Test
        @DisplayName("a null component is carried through rather than rejected or defaulted")
        void aNullComponentIsCarriedThrough() {
            DisclosureGroupId key = new DisclosureGroupId(null, null, null);

            assertThat(key.getDisAcctGroupId()).isNull();
            assertThat(key.getDisTranTypeCd()).isNull();
            assertThat(key.getDisTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("equality across all three components")
    class Equality {

        @Test
        @DisplayName("a key equals itself")
        void aKeyEqualsItself() {
            DisclosureGroupId key = referenceKey();

            assertThat(key).isEqualTo(key);
        }

        @Test
        @DisplayName("two keys with identical components are equal in both directions")
        void twoIdenticalKeysAreEqualInBothDirections() {
            DisclosureGroupId first = referenceKey();
            DisclosureGroupId second = referenceKey();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three identical keys")
        void equalityIsTransitive() {
            DisclosureGroupId first = referenceKey();
            DisclosureGroupId second = referenceKey();
            DisclosureGroupId third = referenceKey();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing account group component alone breaks equality")
        void aDifferingGroupComponentBreaksEquality() {
            assertThat(referenceKey())
                    .isNotEqualTo(new DisclosureGroupId("B         ", TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("a differing transaction type component alone breaks equality")
        void aDifferingTypeComponentBreaksEquality() {
            assertThat(referenceKey())
                    .isNotEqualTo(new DisclosureGroupId(GROUP_ID, "02", CATEGORY_CODE));
        }

        @Test
        @DisplayName("a differing category component alone breaks equality")
        void aDifferingCategoryComponentBreaksEquality() {
            assertThat(referenceKey())
                    .isNotEqualTo(new DisclosureGroupId(GROUP_ID, TYPE_CODE, "0006"));
        }

        @Test
        @DisplayName("the padded and unpadded default group spellings are not equal")
        void thePaddedAndUnpaddedDefaultSpellingsAreNotEqual() {
            DisclosureGroupId padded =
                    new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
            DisclosureGroupId unpadded =
                    new DisclosureGroupId(UNPADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(padded).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("a null reference is not equal to any key")
        void aNullReferenceIsNotEqualToAnyKey() {
            assertThat(referenceKey()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to a key, even with the same component values")
        void anUnrelatedTypeIsNotEqualToAKey() {
            assertThat(referenceKey()).isNotEqualTo(GROUP_ID + TYPE_CODE + CATEGORY_CODE);
        }

        @Test
        @DisplayName("the sibling category-balance key is not equal to a disclosure-group key")
        void theSiblingCategoryBalanceKeyIsNotEqual() {
            DisclosureGroupId disclosure = referenceKey();
            TransactionCategoryBalanceId sibling =
                    new TransactionCategoryBalanceId(GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(disclosure).isNotEqualTo(sibling);
            assertThat(sibling).isNotEqualTo(disclosure);
        }

        @Test
        @DisplayName("two all-null keys are equal, so a provider-instantiated key behaves coherently")
        void twoAllNullKeysAreEqual() {
            assertThat(new DisclosureGroupId()).isEqualTo(new DisclosureGroupId());
        }

        @Test
        @DisplayName("an all-null key is not equal to a populated key")
        void anAllNullKeyIsNotEqualToAPopulatedKey() {
            assertThat(new DisclosureGroupId()).isNotEqualTo(referenceKey());
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
            DisclosureGroupId key = referenceKey();

            assertThat(key.hashCode()).isEqualTo(key.hashCode());
        }

        @Test
        @DisplayName("an all-null key hashes without throwing")
        void anAllNullKeyHashesWithoutThrowing() {
            assertThat(new DisclosureGroupId().hashCode())
                    .isEqualTo(new DisclosureGroupId().hashCode());
        }

        @Test
        @DisplayName("a key round-trips as a hash-map key, which is what a persistence context needs")
        void aKeyRoundTripsAsAHashMapKey() {
            Map<DisclosureGroupId, String> index = new HashMap<>();
            index.put(referenceKey(), "twelve percent");

            assertThat(index).containsEntry(referenceKey(), "twelve percent");
            assertThat(index.get(new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE))).isNull();
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and quotes all three components")
        void theRenderingNamesTheTypeAndQuotesAllThreeComponents() {
            assertThat(referenceKey().toString()).isEqualTo(
                    "DisclosureGroupId[disAcctGroupId='" + GROUP_ID
                            + "', disTranTypeCd='" + TYPE_CODE
                            + "', disTranCatCd='" + CATEGORY_CODE + "']");
        }

        @Test
        @DisplayName("quoting keeps significant trailing spaces visible in a log line")
        void quotingKeepsTrailingSpacesVisible() {
            String rendered =
                    new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE)
                            .toString();

            assertThat(rendered).contains("disAcctGroupId='DEFAULT   '");
        }

        @Test
        @DisplayName("an all-null key renders without throwing")
        void anAllNullKeyRendersWithoutThrowing() {
            assertThat(new DisclosureGroupId().toString())
                    .isEqualTo("DisclosureGroupId[disAcctGroupId='null', "
                            + "disTranTypeCd='null', disTranCatCd='null']");
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
            DisclosureGroupId original =
                    new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
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
                    .isInstanceOf(DisclosureGroupId.class)
                    .isEqualTo(original)
                    .hasSameHashCodeAs(original);
            assertThat(((DisclosureGroupId) restored).getDisAcctGroupId())
                    .isEqualTo(PADDED_DEFAULT_GROUP_ID);
        }
    }
}
