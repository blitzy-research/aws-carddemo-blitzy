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

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the three composite primary keys, each taken from a {@code KEY} group inside a record
 * layout rather than invented as a surrogate.
 *
 * <p>Three of the eleven clusters are keyed on more than one field, and in every case the key is the
 * leading substring of the record image:
 *
 * <ul>
 *   <li>{@code TRAN-CAT-KEY} in {@code app/cpy/CVTRA01Y.cpy} is an eleven-digit account identifier,
 *       a two-character type code and a four-digit category code - seventeen bytes of the fifty-byte
 *       transaction-category-balance record.</li>
 *   <li>{@code DIS-GROUP-KEY} in {@code app/cpy/CVTRA02Y.cpy} is a ten-character group identifier,
 *       a two-character type code and a four-digit category code - sixteen bytes of the fifty-byte
 *       disclosure-group record.</li>
 *   <li>{@code TRAN-CAT-KEY} in {@code app/cpy/CVTRA04Y.cpy} is a two-character type code and a
 *       four-digit category code - six bytes of the sixty-byte transaction-category record.</li>
 * </ul>
 *
 * <p><strong>No surrogate identifier is introduced anywhere.</strong> The cluster definitions state
 * the key as an offset and a length into the record image, so the business key <em>is</em> the
 * primary key. A generated identifier would break the correspondence between the record image and the
 * table row that byte-level parity depends on.
 *
 * <p><strong>Value semantics are load bearing for JPA.</strong> A composite identifier class must
 * implement equality and hashing over every key component, must be serialisable, and must expose a
 * no-argument constructor for the persistence provider. A key that compared by reference would make
 * every lookup miss.
 */
@DisplayName("Composite primary keys: the three multi-field cluster keys")
final class CompositeKeyTest {

    /** {@code TRANCAT-ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID = "00000000011";

    /** {@code TRANCAT-TYPE-CD PIC X(02)}, and the type the interest program writes. */
    private static final String TYPE_CODE = "01";

    /** {@code TRANCAT-CD PIC 9(04)}, and the category the interest program writes. */
    private static final String CATEGORY_CODE = "0005";

    /**
     * {@code DIS-ACCT-GROUP-ID PIC X(10)} as the seed fixture actually spells it.
     *
     * <p>One of exactly three group identifiers in the fixture, each heading seventeen consecutive
     * rows. The ten-character field is space padded, and the padding is part of the key.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT   ";

    /** The zero-rate group, which makes the interest program's rate-is-zero branch reachable. */
    private static final String ZERO_RATE_GROUP_ID = "ZEROAPR   ";

    /** The explicitly keyed group, which makes the direct-hit branch reachable. */
    private static final String DIRECT_HIT_GROUP_ID = "A000000000";

    /** {@code TRANCAT-ACCT-ID PIC 9(11)} is eleven bytes. */
    private static final int ORACLE_ACCOUNT_ID_WIDTH = 11;

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} is ten bytes. */
    private static final int ORACLE_GROUP_ID_WIDTH = 10;

    /** Both type-code fields are {@code PIC X(02)}. */
    private static final int ORACLE_TYPE_CODE_WIDTH = 2;

    /** All three category-code fields are {@code PIC 9(04)}. */
    private static final int ORACLE_CATEGORY_CODE_WIDTH = 4;

    @Nested
    @DisplayName("TransactionCategoryBalanceId: the seventeen-byte three-part key")
    final class TransactionCategoryBalanceKey {

        @Test
        @DisplayName("every component is reported back verbatim")
        void everyComponentIsReportedBack() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(key.getTrancatAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(key.getTrancatTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getTrancatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the three components together occupy seventeen bytes of the record image")
        void theThreeComponentsOccupySeventeenBytes() {
            assertThat(ACCOUNT_ID).hasSize(ORACLE_ACCOUNT_ID_WIDTH);
            assertThat(TYPE_CODE).hasSize(ORACLE_TYPE_CODE_WIDTH);
            assertThat(CATEGORY_CODE).hasSize(ORACLE_CATEGORY_CODE_WIDTH);
            assertThat(ORACLE_ACCOUNT_ID_WIDTH + ORACLE_TYPE_CODE_WIDTH
                    + ORACLE_CATEGORY_CODE_WIDTH).isEqualTo(17);
        }

        @Test
        @DisplayName("two keys with the same three components are equal and hash alike")
        void twoKeysWithTheSameComponentsAreEqual() {
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in any one component makes the keys unequal")
        void aDifferenceInAnyComponentMakesKeysUnequal() {
            final TransactionCategoryBalanceId base =
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(base).isNotEqualTo(
                    new TransactionCategoryBalanceId("00000000012", TYPE_CODE, CATEGORY_CODE));
            assertThat(base).isNotEqualTo(
                    new TransactionCategoryBalanceId(ACCOUNT_ID, "02", CATEGORY_CODE));
            assertThat(base).isNotEqualTo(
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, "0006"));
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(key.equals(key)).isTrue();
            assertThat(key.equals(null)).isFalse();
            assertThat(key.equals("not a key")).isFalse();
            assertThat(key).isNotEqualTo(
                    new DisclosureGroupId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("a key with absent components is comparable rather than fatal")
        void aKeyWithAbsentComponentsIsComparable() {
            // The persistence provider populates a key field by field, so a partially built key must
            // not raise from equality or hashing while it is still being filled.
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId(null, null, null);
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId(null, null, null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("the rendering names every component, so a failed lookup is diagnosable")
        void theRenderingNamesEveryComponent() {
            final String rendered =
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE)
                            .toString();

            assertThat(rendered).contains(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE)
                    .contains("TransactionCategoryBalanceId");
        }

        @Test
        @DisplayName("the key works as a map key, which is what a repository lookup relies on")
        void theKeyWorksAsAMapKey() {
            final Map<TransactionCategoryBalanceId, String> byKey = new HashMap<>();
            byKey.put(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE),
                    "balance row");

            assertThat(byKey.get(
                    new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE)))
                    .isEqualTo("balance row");
        }

        @Test
        @DisplayName("the key is serialisable, as a composite identifier class must be")
        void theKeyIsSerialisable() {
            assertThat(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE))
                    .isInstanceOf(java.io.Serializable.class);
        }
    }

    @Nested
    @DisplayName("DisclosureGroupId: the sixteen-byte three-part key")
    final class DisclosureGroupKey {

        @Test
        @DisplayName("every component is reported back verbatim, padding included")
        void everyComponentIsReportedBackWithPadding() {
            final DisclosureGroupId key =
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(key.getDisAcctGroupId())
                    .isEqualTo(DEFAULT_GROUP_ID)
                    .hasSize(ORACLE_GROUP_ID_WIDTH)
                    .endsWith("   ");
            assertThat(key.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the three fixture group identifiers are distinct keys at the same type and category")
        void theThreeFixtureGroupsAreDistinctKeys() {
            // The seed fixture holds three consecutive seventeen-row groups, which is what makes the
            // direct-hit branch, the default-fallback branch and the zero-rate branch all reachable.
            final DisclosureGroupId directHit =
                    new DisclosureGroupId(DIRECT_HIT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
            final DisclosureGroupId fallback =
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
            final DisclosureGroupId zeroRate =
                    new DisclosureGroupId(ZERO_RATE_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(directHit).isNotEqualTo(fallback).isNotEqualTo(zeroRate);
            assertThat(fallback).isNotEqualTo(zeroRate);
        }

        @Test
        @DisplayName("all three fixture identifiers fill the ten-character field exactly")
        void allThreeFixtureIdentifiersFillTheField() {
            assertThat(DIRECT_HIT_GROUP_ID).hasSize(ORACLE_GROUP_ID_WIDTH);
            assertThat(DEFAULT_GROUP_ID).hasSize(ORACLE_GROUP_ID_WIDTH);
            assertThat(ZERO_RATE_GROUP_ID).hasSize(ORACLE_GROUP_ID_WIDTH);
        }

        @Test
        @DisplayName("the padding is part of the key, so a trimmed identifier is a different key")
        void thePaddingIsPartOfTheKey() {
            assertThat(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE))
                    .isNotEqualTo(new DisclosureGroupId("DEFAULT", TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("two keys with the same three components are equal and hash alike")
        void twoKeysWithTheSameComponentsAreEqual() {
            final DisclosureGroupId first =
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
            final DisclosureGroupId second =
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in any one component makes the keys unequal")
        void aDifferenceInAnyComponentMakesKeysUnequal() {
            final DisclosureGroupId base =
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(base).isNotEqualTo(
                    new DisclosureGroupId(ZERO_RATE_GROUP_ID, TYPE_CODE, CATEGORY_CODE));
            assertThat(base).isNotEqualTo(
                    new DisclosureGroupId(DEFAULT_GROUP_ID, "02", CATEGORY_CODE));
            assertThat(base).isNotEqualTo(
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, "0006"));
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final DisclosureGroupId key =
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(key.equals(key)).isTrue();
            assertThat(key.equals(null)).isFalse();
            assertThat(key.equals(DEFAULT_GROUP_ID)).isFalse();
        }

        @Test
        @DisplayName("a key with absent components is comparable rather than fatal")
        void aKeyWithAbsentComponentsIsComparable() {
            final DisclosureGroupId first = new DisclosureGroupId(null, null, null);

            assertThat(first).isEqualTo(new DisclosureGroupId(null, null, null));
            assertThat(first).isNotEqualTo(
                    new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("the rendering names every component")
        void theRenderingNamesEveryComponent() {
            assertThat(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE).toString())
                    .contains(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE)
                    .contains("DisclosureGroupId");
        }

        @Test
        @DisplayName("the key works as a map key")
        void theKeyWorksAsAMapKey() {
            final Map<DisclosureGroupId, String> byKey = new HashMap<>();
            byKey.put(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE), "rate row");

            assertThat(byKey.get(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE)))
                    .isEqualTo("rate row");
        }

        @Test
        @DisplayName("the key is serialisable")
        void theKeyIsSerialisable() {
            assertThat(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE))
                    .isInstanceOf(java.io.Serializable.class);
        }
    }

    @Nested
    @DisplayName("TransactionCategoryId: the six-byte two-part key")
    final class TransactionCategoryKey {

        @Test
        @DisplayName("both components are reported back verbatim")
        void bothComponentsAreReportedBack() {
            final TransactionCategoryId key = new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE);

            assertThat(key.getTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the two components together occupy six bytes of the record image")
        void theTwoComponentsOccupySixBytes() {
            assertThat(ORACLE_TYPE_CODE_WIDTH + ORACLE_CATEGORY_CODE_WIDTH).isEqualTo(6);
        }

        @Test
        @DisplayName("two keys with the same components are equal and hash alike")
        void twoKeysWithTheSameComponentsAreEqual() {
            final TransactionCategoryId first =
                    new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE);

            assertThat(first)
                    .isEqualTo(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE))
                    .hasSameHashCodeAs(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("a difference in either component makes the keys unequal")
        void aDifferenceInEitherComponentMakesKeysUnequal() {
            final TransactionCategoryId base =
                    new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE);

            assertThat(base).isNotEqualTo(new TransactionCategoryId("02", CATEGORY_CODE));
            assertThat(base).isNotEqualTo(new TransactionCategoryId(TYPE_CODE, "0006"));
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final TransactionCategoryId key = new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE);

            assertThat(key.equals(key)).isTrue();
            assertThat(key.equals(null)).isFalse();
            assertThat(key.equals(TYPE_CODE)).isFalse();
        }

        @Test
        @DisplayName("the rendering names both components and the key works as a map key")
        void theRenderingNamesBothComponents() {
            final TransactionCategoryId key = new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE);
            final Map<TransactionCategoryId, String> byKey = new HashMap<>();
            byKey.put(key, "category row");

            assertThat(key.toString()).contains(TYPE_CODE, CATEGORY_CODE)
                    .contains("TransactionCategoryId");
            assertThat(byKey.get(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE)))
                    .isEqualTo("category row");
            assertThat(key).isInstanceOf(java.io.Serializable.class);
        }
    }
}
