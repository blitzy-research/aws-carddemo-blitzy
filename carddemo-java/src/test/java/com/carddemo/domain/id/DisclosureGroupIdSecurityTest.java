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
 * Unit test for {@link DisclosureGroupId}, the composite identifier of the disclosure-group table
 * derived from copybook member {@code CVTRA02Y}.
 *
 * <h2>The padding on the account group identifier is load-bearing</h2>
 *
 * <p>The interest-calculation program {@code CBACT04C} falls back to a default disclosure group when
 * the account's own group lookup returns status {@code 23} ({@code app/cbl/CBACT04C.cbl} L415-L460).
 * The group identifier it probes with is the ten-character, <em>space-padded</em> literal, because the
 * field is {@code PIC X(10)} and the shorter literal is padded to fill it. The seeded data confirms
 * this shape: {@code app/data/ASCII/discgrp.txt} holds three consecutive seventeen-row groups keyed
 * {@code A}, {@code DEFAULT} and {@code ZEROAPR}, each padded to the field width.</p>
 *
 * <p>The consequence for this key class is that trimming would be a defect, not a tidy-up: a trimmed
 * {@code "DEFAULT"} and a padded {@code "DEFAULT   "} must remain <em>different</em> keys, because
 * only one of them is what the record image actually holds. The assertions below pin exactly that,
 * and the diagnostic rendering is asserted to quote its values so a significant trailing space stays
 * visible in a log line and in an assertion failure.</p>
 */
@DisplayName("DisclosureGroupId - the CVTRA02Y composite key")
class DisclosureGroupIdSecurityTest {

    /** Legacy width of the account group identifier component, from {@code PIC X(10)}. */
    private static final int GROUP_ID_WIDTH = 10;

    /** Legacy width of the transaction type code component, from {@code PIC X(02)}. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Legacy width of the transaction category code component, from {@code PIC 9(04)}. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** The default group identifier at its padded field width, as the fallback path probes it. */
    private static final String DEFAULT_GROUP_PADDED = "DEFAULT   ";

    /** The zero-rate group identifier at its padded field width, present in the seeded data. */
    private static final String ZERO_APR_GROUP_PADDED = "ZEROAPR   ";

    /** A transaction type code at its legacy width. */
    private static final String TYPE_CODE = "01";

    /** A transaction category code at its legacy width, leading zeros included. */
    private static final String CATEGORY_CODE = "0005";

    private static DisclosureGroupId key() {
        return new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, CATEGORY_CODE);
    }

    @Nested
    @DisplayName("Construction and verbatim component retention")
    class Construction {

        @Test
        @DisplayName("the three-argument constructor stores each component verbatim, in record-image order")
        void theThreeArgumentConstructorStoresEachComponentVerbatim() {
            final DisclosureGroupId id = key();
            assertThat(id.getDisAcctGroupId()).isEqualTo(DEFAULT_GROUP_PADDED);
            assertThat(id.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(id.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the no-argument constructor exists for the provider and leaves every component unset")
        void theNoArgumentConstructorLeavesEveryComponentUnset() {
            final DisclosureGroupId id = new DisclosureGroupId();
            assertThat(id.getDisAcctGroupId()).isNull();
            assertThat(id.getDisTranTypeCd()).isNull();
            assertThat(id.getDisTranCatCd()).isNull();
        }

        @Test
        @DisplayName("the ten-character padded default group identifier is retained with all three trailing spaces, "
                + "because that padding is what the fallback path probes with")
        void theDefaultGroupPaddingIsRetained() {
            final DisclosureGroupId id = key();
            assertThat(id.getDisAcctGroupId()).hasSize(GROUP_ID_WIDTH).endsWith("   ");
            assertThat(id.getDisAcctGroupId().strip()).isEqualTo("DEFAULT");
        }

        @Test
        @DisplayName("leading zeros on the category code survive, because the component is retained as text")
        void leadingZerosOnTheCategoryCodeSurvive() {
            assertThat(new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, "0001").getDisTranCatCd())
                    .isEqualTo("0001");
        }

        @Test
        @DisplayName("a null component is accepted rather than rejected, because the provider populates the key "
                + "after construction")
        void aNullComponentIsAccepted() {
            final DisclosureGroupId id = new DisclosureGroupId(null, null, null);
            assertThat(id.getDisAcctGroupId()).isNull();
            assertThat(id.getDisTranTypeCd()).isNull();
            assertThat(id.getDisTranCatCd()).isNull();
        }

        @Test
        @DisplayName("the three legacy component widths sum to the 16-byte key the cluster definition declares")
        void theComponentWidthsSumToTheKeyWidth() {
            assertThat(GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(16);
            assertThat(DEFAULT_GROUP_PADDED).hasSize(GROUP_ID_WIDTH);
            assertThat(ZERO_APR_GROUP_PADDED).hasSize(GROUP_ID_WIDTH);
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
            final DisclosureGroupId id = key();
            assertThat(id).isEqualTo(id);
        }

        @Test
        @DisplayName("two keys built from the same three components are equal and the relation is symmetric")
        void twoIdenticalKeysAreEqualBothWays() {
            assertThat(key()).isEqualTo(key());
            assertThat(key()).isEqualTo(key());
        }

        @Test
        @DisplayName("equality is transitive across three separately constructed but identical keys")
        void equalityIsTransitive() {
            final DisclosureGroupId first = key();
            final DisclosureGroupId second = key();
            final DisclosureGroupId third = key();
            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("varying the account group identifier alone breaks equality, so the default group and the "
                + "zero-rate group are distinct keys")
        void theGroupIdentifierParticipates() {
            assertThat(key())
                    .isNotEqualTo(new DisclosureGroupId(ZERO_APR_GROUP_PADDED, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("varying the transaction type code alone breaks equality")
        void theTypeCodeParticipates() {
            assertThat(key())
                    .isNotEqualTo(new DisclosureGroupId(DEFAULT_GROUP_PADDED, "02", CATEGORY_CODE));
        }

        @Test
        @DisplayName("varying the transaction category code alone breaks equality")
        void theCategoryCodeParticipates() {
            assertThat(key())
                    .isNotEqualTo(new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, "0006"));
        }

        @Test
        @DisplayName("the padded group identifier is not equal to its trimmed form, which is the merge a trimming "
                + "comparison would cause on the very key the interest fallback probes with")
        void thePaddedGroupIdentifierIsNotItsTrimmedForm() {
            assertThat(new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, CATEGORY_CODE))
                    .isNotEqualTo(new DisclosureGroupId("DEFAULT", TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("comparison is exact rather than numeric, so the zero-padded category code 0005 is not the "
                + "unpadded code 5")
        void comparisonIsExactRatherThanNumeric() {
            assertThat(new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, "0005"))
                    .isNotEqualTo(new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, "5"));
        }

        @Test
        @DisplayName("comparison applies no case folding, so a lower-case group identifier is a different key")
        void comparisonAppliesNoCaseFolding() {
            assertThat(new DisclosureGroupId("default   ", TYPE_CODE, CATEGORY_CODE))
                    .isNotEqualTo(key());
        }

        @Test
        @DisplayName("a key is not equal to null")
        void aKeyIsNotEqualToNull() {
            assertThat(key()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("a key is not equal to an unrelated type, including a sibling key class carrying the same three "
                + "component values")
        void aKeyIsNotEqualToAnUnrelatedType() {
            assertThat(key()).isNotEqualTo(DEFAULT_GROUP_PADDED + TYPE_CODE + CATEGORY_CODE);
            assertThat((Object) key()).isNotEqualTo(
                    new TransactionCategoryBalanceId(DEFAULT_GROUP_PADDED, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("two fully unset keys are equal, and an unset key is not equal to a populated one")
        void unsetKeysCompareConsistently() {
            assertThat(new DisclosureGroupId())
                    .isEqualTo(new DisclosureGroupId())
                    .isNotEqualTo(key());
        }
    }

    @Nested
    @DisplayName("Hashing, computed from untrimmed values so it agrees with equality")
    class Hashing {

        @Test
        @DisplayName("equal keys hash equally")
        void equalKeysHashEqually() {
            assertThat(key()).hasSameHashCodeAs(key());
        }

        @Test
        @DisplayName("the hash is stable across repeated invocations on the same instance")
        void theHashIsStable() {
            final DisclosureGroupId id = key();
            assertThat(id.hashCode()).isEqualTo(id.hashCode()).isEqualTo(id.hashCode());
        }

        @Test
        @DisplayName("an unset key hashes without throwing, because the components are hashed null-safely")
        void anUnsetKeyHashesWithoutThrowing() {
            assertThat(new DisclosureGroupId().hashCode())
                    .isEqualTo(new DisclosureGroupId().hashCode());
        }

        @Test
        @DisplayName("the key works as a hash-map key: an equal instance retrieves the mapped rate row")
        void theKeyWorksAsAHashMapKey() {
            final Map<DisclosureGroupId, String> map = new HashMap<>();
            map.put(key(), "disclosure row");
            assertThat(map).containsEntry(key(), "disclosure row");
        }

        @Test
        @DisplayName("the three seeded group identifiers plus a component variation occupy four distinct hash-set "
                + "slots, so a lookup on one cannot return another")
        void theSeededGroupsAreDistinctInASet() {
            final HashSet<DisclosureGroupId> keys = new HashSet<>();
            keys.add(new DisclosureGroupId("A         ", TYPE_CODE, CATEGORY_CODE));
            keys.add(new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, CATEGORY_CODE));
            keys.add(new DisclosureGroupId(ZERO_APR_GROUP_PADDED, TYPE_CODE, CATEGORY_CODE));
            keys.add(new DisclosureGroupId(DEFAULT_GROUP_PADDED, TYPE_CODE, "0006"));
            keys.add(key());
            assertThat(keys).hasSize(4);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering, which quotes its values so padding stays visible")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and lists all three components")
        void theRenderingListsAllThreeComponents() {
            assertThat(key().toString())
                    .startsWith("DisclosureGroupId[")
                    .contains("disAcctGroupId='" + DEFAULT_GROUP_PADDED + "'")
                    .contains("disTranTypeCd='" + TYPE_CODE + "'")
                    .contains("disTranCatCd='" + CATEGORY_CODE + "'")
                    .endsWith("]");
        }

        @Test
        @DisplayName("each value is quoted, so a significant trailing space on the group identifier remains visible "
                + "rather than vanishing at the end of a log line")
        void eachValueIsQuotedSoTrailingSpacesStayVisible() {
            assertThat(key().toString()).contains("'DEFAULT   '");
            assertThat(key().toString()).doesNotContain("'DEFAULT'");
        }

        @Test
        @DisplayName("the rendering of an unset key does not throw and reports each component as absent")
        void theRenderingOfAnUnsetKeyDoesNotThrow() {
            assertThat(new DisclosureGroupId().toString())
                    .contains("disAcctGroupId='null'")
                    .contains("disTranTypeCd='null'")
                    .contains("disTranCatCd='null'");
        }

        @Test
        @DisplayName("the rendering carries no credential and no interest rate, because this key holds neither")
        void theRenderingCarriesNothingSensitive() {
            assertThat(key().toString().toUpperCase(java.util.Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "RATE", "$2A$");
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
            assertThat(ObjectStreamClass.lookup(DisclosureGroupId.class).getSerialVersionUID())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a key survives a serialization round trip equal to the original, with the group identifier's "
                + "padding intact")
        void aKeySurvivesASerializationRoundTrip() throws IOException, ClassNotFoundException {
            final DisclosureGroupId original = key();
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
                    .isInstanceOf(DisclosureGroupId.class)
                    .isEqualTo(original)
                    .hasSameHashCodeAs(original);
            assertThat(((DisclosureGroupId) restored).getDisAcctGroupId())
                    .isEqualTo(DEFAULT_GROUP_PADDED)
                    .hasSize(GROUP_ID_WIDTH);
        }
    }
}
