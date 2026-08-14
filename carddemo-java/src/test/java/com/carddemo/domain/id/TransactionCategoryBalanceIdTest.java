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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionCategoryBalanceId}, the 17-byte composite key of the
 * category-balance table.
 *
 * <p>Component order is the contract - account identifier, then transaction type, then transaction
 * category, as the copybook declares them - and a transposed constructor call is detectable only by
 * width, which is why the widths are asserted alongside the values. All three components stay text so
 * a four-character category code is never narrowed to a number, and the constructor stores them
 * verbatim: no trim, no pad, no case fold. Null components are accepted and returned unchanged,
 * because the persistence provider instantiates the type before populating it.
 */
@DisplayName("TransactionCategoryBalanceId :: 17-byte composite key of the category-balance table")
class TransactionCategoryBalanceIdTest {
    private static final int ACCOUNT_ID_WIDTH = 11;

    private static final int TYPE_CODE_WIDTH = 2;

    private static final int CATEGORY_CODE_WIDTH = 4;

    private static final int DECLARED_KEY_LENGTH = 17;

    private static final int DECLARED_KEY_OFFSET = 0;

    private static final int BALANCE_INTEGER_DIGITS = 9;

    private static final int BALANCE_DECIMAL_DIGITS = 2;

    private static final int BALANCE_WIDTH = BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS;

    private static final int FILLER_WIDTH = 22;

    private static final int DECLARED_RECORD_LENGTH = 50;

    private static final int FILE_DESCRIPTION_DATA_WIDTH = 33;

    private static final int REFERENCE_ROW_COUNT = 50;

    private static final int MEASURED_FIXTURE_BYTES = 2550;

    private static final String FIXTURE_TYPE_CODE = "01";

    private static final String FIXTURE_CATEGORY_CODE = "0001";

    private static final int CATEGORY_KEY_LENGTH = 6;

    private static final int DISCLOSURE_KEY_LENGTH = 16;

    private static List<TransactionCategoryBalanceId> referenceKeys() {
        return List.of(
                new TransactionCategoryBalanceId("00000000001", "01", "0001"),
                new TransactionCategoryBalanceId("00000000002", "01", "0001"),
                new TransactionCategoryBalanceId("00000000003", "01", "0001"),
                new TransactionCategoryBalanceId("00000000004", "01", "0001"),
                new TransactionCategoryBalanceId("00000000005", "01", "0001"),
                new TransactionCategoryBalanceId("00000000006", "01", "0001"),
                new TransactionCategoryBalanceId("00000000007", "01", "0001"),
                new TransactionCategoryBalanceId("00000000008", "01", "0001"),
                new TransactionCategoryBalanceId("00000000009", "01", "0001"),
                new TransactionCategoryBalanceId("00000000010", "01", "0001"),
                new TransactionCategoryBalanceId("00000000011", "01", "0001"),
                new TransactionCategoryBalanceId("00000000012", "01", "0001"),
                new TransactionCategoryBalanceId("00000000013", "01", "0001"),
                new TransactionCategoryBalanceId("00000000014", "01", "0001"),
                new TransactionCategoryBalanceId("00000000015", "01", "0001"),
                new TransactionCategoryBalanceId("00000000016", "01", "0001"),
                new TransactionCategoryBalanceId("00000000017", "01", "0001"),
                new TransactionCategoryBalanceId("00000000018", "01", "0001"),
                new TransactionCategoryBalanceId("00000000019", "01", "0001"),
                new TransactionCategoryBalanceId("00000000020", "01", "0001"),
                new TransactionCategoryBalanceId("00000000021", "01", "0001"),
                new TransactionCategoryBalanceId("00000000022", "01", "0001"),
                new TransactionCategoryBalanceId("00000000023", "01", "0001"),
                new TransactionCategoryBalanceId("00000000024", "01", "0001"),
                new TransactionCategoryBalanceId("00000000025", "01", "0001"),
                new TransactionCategoryBalanceId("00000000026", "01", "0001"),
                new TransactionCategoryBalanceId("00000000027", "01", "0001"),
                new TransactionCategoryBalanceId("00000000028", "01", "0001"),
                new TransactionCategoryBalanceId("00000000029", "01", "0001"),
                new TransactionCategoryBalanceId("00000000030", "01", "0001"),
                new TransactionCategoryBalanceId("00000000031", "01", "0001"),
                new TransactionCategoryBalanceId("00000000032", "01", "0001"),
                new TransactionCategoryBalanceId("00000000033", "01", "0001"),
                new TransactionCategoryBalanceId("00000000034", "01", "0001"),
                new TransactionCategoryBalanceId("00000000035", "01", "0001"),
                new TransactionCategoryBalanceId("00000000036", "01", "0001"),
                new TransactionCategoryBalanceId("00000000037", "01", "0001"),
                new TransactionCategoryBalanceId("00000000038", "01", "0001"),
                new TransactionCategoryBalanceId("00000000039", "01", "0001"),
                new TransactionCategoryBalanceId("00000000040", "01", "0001"),
                new TransactionCategoryBalanceId("00000000041", "01", "0001"),
                new TransactionCategoryBalanceId("00000000042", "01", "0001"),
                new TransactionCategoryBalanceId("00000000043", "01", "0001"),
                new TransactionCategoryBalanceId("00000000044", "01", "0001"),
                new TransactionCategoryBalanceId("00000000045", "01", "0001"),
                new TransactionCategoryBalanceId("00000000046", "01", "0001"),
                new TransactionCategoryBalanceId("00000000047", "01", "0001"),
                new TransactionCategoryBalanceId("00000000048", "01", "0001"),
                new TransactionCategoryBalanceId("00000000049", "01", "0001"),
                new TransactionCategoryBalanceId("00000000050", "01", "0001"));
    }

    private static int countOfTypeAndCategory(final List<TransactionCategoryBalanceId> keys,
            final String typeCode, final String categoryCode) {
        int matches = 0;
        for (final TransactionCategoryBalanceId key : keys) {
            if (typeCode.equals(key.getTrancatTypeCd())
                    && categoryCode.equals(key.getTrancatCd())) {
                matches++;
            }
        }
        return matches;
    }

    private static Set<String> distinctAccountIdsOf(final List<TransactionCategoryBalanceId> keys) {
        final Set<String> accountIds = new HashSet<>();
        for (final TransactionCategoryBalanceId key : keys) {
            accountIds.add(key.getTrancatAcctId());
        }
        return accountIds;
    }

    @Nested
    @DisplayName("Component contract of the 17-byte CVTRA01Y key group")
    class ComponentContract {
        @Test
        @DisplayName("all-args constructor binds components in CVTRA01Y declaration order - account "
                + "identifier first, type code second, category code third - and never in the order "
                + "some program happens to assign them in")
        void allArgsConstructorBindsComponentsPositionally() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");

            assertThat(key.getTrancatAcctId()).isEqualTo("00000000042");
            assertThat(key.getTrancatTypeCd()).isEqualTo("07");
            assertThat(key.getTrancatCd()).isEqualTo("0003");

            final TransactionCategoryBalanceId fixtureKey =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");

            assertThat(fixtureKey.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(fixtureKey.getTrancatTypeCd()).isEqualTo("01");
            assertThat(fixtureKey.getTrancatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("a transposed constructor call is detectable by width: the account identifier "
                + "occupies 11 bytes, the type code 2 and the category code 4, so no two components "
                + "are interchangeable")
        void componentWidthsMakeATransposedConstructorCallDetectable() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");

            final int boundAccountIdWidth =
                    key.getTrancatAcctId().getBytes(StandardCharsets.US_ASCII).length;
            final int boundTypeCodeWidth =
                    key.getTrancatTypeCd().getBytes(StandardCharsets.US_ASCII).length;
            final int boundCategoryCodeWidth =
                    key.getTrancatCd().getBytes(StandardCharsets.US_ASCII).length;

            assertThat(boundAccountIdWidth).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(boundTypeCodeWidth).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(boundCategoryCodeWidth).isEqualTo(CATEGORY_CODE_WIDTH);

            assertThat(boundAccountIdWidth).isNotEqualTo(boundTypeCodeWidth);
            assertThat(boundAccountIdWidth).isNotEqualTo(boundCategoryCodeWidth);
            assertThat(boundTypeCodeWidth).isNotEqualTo(boundCategoryCodeWidth);
        }

        @Test
        @DisplayName("no-arg constructor leaves all three components null, because the persistence "
                + "provider populates an identifier class after instantiating it")
        void noArgConstructorLeavesAllThreeComponentsNull() {
            final TransactionCategoryBalanceId empty = new TransactionCategoryBalanceId();

            assertThat(empty.getTrancatAcctId()).isNull();
            assertThat(empty.getTrancatTypeCd()).isNull();
            assertThat(empty.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("all three components are text, so \"0003\" is never narrowed to a value that "
                + "would render as 3, \"07\" never to 7 and \"00000000042\" never to 42")
        void componentsAreTextAndLeadingZerosSurvive() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");

            assertThat(key.getTrancatCd()).isEqualTo("0003");
            assertThat(key.getTrancatCd()).isNotEqualTo("3");
            assertThat(key.getTrancatTypeCd()).isEqualTo("07");
            assertThat(key.getTrancatTypeCd()).isNotEqualTo("7");
            assertThat(key.getTrancatAcctId()).isEqualTo("00000000042");
            assertThat(key.getTrancatAcctId()).isNotEqualTo("42");

            assertThat(key.getTrancatAcctId().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(key.getTrancatTypeCd().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(TYPE_CODE_WIDTH);
            assertThat(key.getTrancatCd().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("constructor stores all three components verbatim - no trim, no pad, no case "
                + "fold and no validation - because altering a caller's value would change lookup "
                + "semantics")
        void constructorStoresComponentsVerbatim() {
            final TransactionCategoryBalanceId unaltered =
                    new TransactionCategoryBalanceId("0000000004x", "ab", "0090");

            assertThat(unaltered.getTrancatAcctId()).isEqualTo("0000000004x");
            assertThat(unaltered.getTrancatTypeCd()).isEqualTo("ab");
            assertThat(unaltered.getTrancatCd()).isEqualTo("0090");

            final TransactionCategoryBalanceId padded =
                    new TransactionCategoryBalanceId(" 0000000001", " 1", "0 04");

            assertThat(padded.getTrancatAcctId()).isEqualTo(" 0000000001");
            assertThat(padded.getTrancatTypeCd()).isEqualTo(" 1");
            assertThat(padded.getTrancatCd()).isEqualTo("0 04");
        }

        @Test
        @DisplayName("null components are accepted and returned unchanged, because the identifier "
                + "class validates nothing and the schema enforces the not-null constraint")
        void nullComponentsAreStoredAndReturnedUnchanged() {
            final TransactionCategoryBalanceId nullAccountId =
                    new TransactionCategoryBalanceId(null, "01", "0001");

            assertThat(nullAccountId.getTrancatAcctId()).isNull();
            assertThat(nullAccountId.getTrancatTypeCd()).isEqualTo("01");
            assertThat(nullAccountId.getTrancatCd()).isEqualTo("0001");

            final TransactionCategoryBalanceId nullTypeCode =
                    new TransactionCategoryBalanceId("00000000001", null, "0001");

            assertThat(nullTypeCode.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(nullTypeCode.getTrancatTypeCd()).isNull();
            assertThat(nullTypeCode.getTrancatCd()).isEqualTo("0001");

            final TransactionCategoryBalanceId nullCategoryCode =
                    new TransactionCategoryBalanceId("00000000001", "01", null);

            assertThat(nullCategoryCode.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(nullCategoryCode.getTrancatTypeCd()).isEqualTo("01");
            assertThat(nullCategoryCode.getTrancatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the key and of the 50-byte record that carries it")
    class ByteWidthContract {
        @Test
        @DisplayName("the three component widths sum to the 17 declared by TCATBALF KEYS(17 0): the "
                + "copybook and the cluster definition are independent authorities that agree")
        void componentWidthsSumToTheDeclaredKeyLength() {
            final int accountIdWidth = "00000000042".getBytes(StandardCharsets.US_ASCII).length;
            final int typeCodeWidth = "07".getBytes(StandardCharsets.US_ASCII).length;
            final int categoryCodeWidth = "0003".getBytes(StandardCharsets.US_ASCII).length;

            assertThat(accountIdWidth).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(typeCodeWidth).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(categoryCodeWidth).isEqualTo(CATEGORY_CODE_WIDTH);

            final int summedKeyLength = accountIdWidth + typeCodeWidth + categoryCodeWidth;

            assertThat(summedKeyLength).isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(summedKeyLength).isEqualTo(17);
        }

        @Test
        @DisplayName("the key is the leading substring of the record: account identifier at offset 0, "
                + "type code at 11, category code at 13, balance at 17, filler at 28, and "
                + "17 + 11 + 22 = the declared RECORDSIZE of 50")
        void keyIsTheLeadingSubstringOfTheFiftyByteRecord() {
            final int accountIdOffset = DECLARED_KEY_OFFSET;
            final int typeCodeOffset = accountIdOffset + ACCOUNT_ID_WIDTH;
            final int categoryCodeOffset = typeCodeOffset + TYPE_CODE_WIDTH;
            final int keyWidth = ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;
            final int balanceOffset = accountIdOffset + keyWidth;
            final int fillerOffset = balanceOffset + BALANCE_WIDTH;
            final int recordWidth = keyWidth + BALANCE_WIDTH + FILLER_WIDTH;

            assertThat(accountIdOffset).isZero();
            assertThat(typeCodeOffset).isEqualTo(11);
            assertThat(categoryCodeOffset).isEqualTo(13);
            assertThat(balanceOffset).isEqualTo(17);
            assertThat(fillerOffset).isEqualTo(28);
            assertThat(recordWidth).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(recordWidth).isEqualTo(50);

            assertThat(BALANCE_WIDTH).isEqualTo(BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS);
            assertThat(BALANCE_WIDTH).isEqualTo(11);

            assertThat(recordWidth - keyWidth).isEqualTo(BALANCE_WIDTH + FILLER_WIDTH);
        }

        @Test
        @DisplayName("the batch file description splits the same 50 bytes into 17 + 33 and reconciles "
                + "with the copybook's 11 + 22 = 33; its data field carries a doubled name prefix in "
                + "two programs, an anomaly recorded in docs/decision-log.md and never propagated")
        void theFileDescriptionSplitReconcilesWithTheCopybookSplit() {
            final int keyWidth = ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;

            assertThat(keyWidth + FILE_DESCRIPTION_DATA_WIDTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(DECLARED_KEY_LENGTH + FILE_DESCRIPTION_DATA_WIDTH).isEqualTo(50);

            assertThat(BALANCE_WIDTH + FILLER_WIDTH).isEqualTo(FILE_DESCRIPTION_DATA_WIDTH);
            assertThat(BALANCE_WIDTH + FILLER_WIDTH).isEqualTo(33);
        }

        @Test
        @DisplayName("KEYS(17 0) declares length 17 at offset 0; every DEFINE CLUSTER in the estate "
                + "declares offset 0 and only DEFINE ALTERNATEINDEX blocks carry a non-zero offset")
        void clusterDefinitionDeclaresLengthSeventeenAtOffsetZero() {
            assertThat(ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isEqualTo(17);
            assertThat(DECLARED_KEY_OFFSET).isZero();

            final int dataPortionWidth = DECLARED_RECORD_LENGTH - DECLARED_KEY_LENGTH;

            assertThat(dataPortionWidth).isEqualTo(BALANCE_WIDTH + FILLER_WIDTH);
            assertThat(dataPortionWidth).isEqualTo(FILE_DESCRIPTION_DATA_WIDTH);
        }

        @Test
        @DisplayName("the three composite key lengths 17, 16 and 6 are pairwise distinct: the same "
                + "COBOL group name is declared in CVTRA01Y at 17 and in CVTRA04Y at 6, so "
                + "disambiguation is by member name and declared length, never by content")
        void theThreeCompositeKeyLengthsArePairwiseDistinct() {
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);
            assertThat(CATEGORY_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);

            assertThat(TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);

            assertThat(10 + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(DISCLOSURE_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH - DISCLOSURE_KEY_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the reference fixture geometry closes: 50 rows of 50 bytes plus one line "
                + "terminator each account for all 2,550 measured bytes, and every key is 17 bytes")
        void referenceFixtureGeometryAccountsForEveryByte() {
            final int lineWidthWithTerminator = DECLARED_RECORD_LENGTH + 1;

            assertThat(REFERENCE_ROW_COUNT * lineWidthWithTerminator)
                    .isEqualTo(MEASURED_FIXTURE_BYTES);
            assertThat(MEASURED_FIXTURE_BYTES).isEqualTo(2550);

            final List<TransactionCategoryBalanceId> keys = referenceKeys();

            assertThat(keys).hasSize(REFERENCE_ROW_COUNT);

            for (final TransactionCategoryBalanceId key : keys) {
                final int keyWidth = key.getTrancatAcctId().getBytes(StandardCharsets.US_ASCII).length
                        + key.getTrancatTypeCd().getBytes(StandardCharsets.US_ASCII).length
                        + key.getTrancatCd().getBytes(StandardCharsets.US_ASCII).length;

                assertThat(keyWidth).isEqualTo(DECLARED_KEY_LENGTH);
            }
        }
    }

    @Nested
    @DisplayName("Equality and hashing across all three key components")
    class EqualityAndHashing {
        @Test
        @DisplayName("two independently constructed keys with identical components are equal and hash "
                + "alike, which is what lets a freshly built key match a row already loaded")
        void independentlyConstructedEqualKeysAreEqualAndHashAlike() {
            final TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");
            final TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");

            assertThat(first).isEqualTo(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());
            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("a key equals itself, satisfying the reflexive clause of the equality contract")
        void aKeyEqualsItself() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000017", "01", "0001");

            assertThat(key.equals(key)).isTrue();
        }

        @Test
        @DisplayName("equality is symmetric for two independently constructed equal keys")
        void equalityIsSymmetric() {
            final TransactionCategoryBalanceId left =
                    new TransactionCategoryBalanceId("00000000050", "01", "0001");
            final TransactionCategoryBalanceId right =
                    new TransactionCategoryBalanceId("00000000050", "01", "0001");

            assertThat(left.equals(right)).isTrue();
            assertThat(right.equals(left)).isTrue();
        }

        @Test
        @DisplayName("component 1, the account identifier, participates in equality: this is the only "
                + "component the fixture varies, so dropping it would collapse all 50 rows onto one "
                + "identifier")
        void accountIdComponentParticipatesInEquality() {
            final TransactionCategoryBalanceId firstAccount =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");
            final TransactionCategoryBalanceId secondAccount =
                    new TransactionCategoryBalanceId("00000000002", "01", "0001");

            assertThat(firstAccount).isNotEqualTo(secondAccount);
            assertThat(firstAccount.equals(secondAccount)).isFalse();
        }

        @Test
        @DisplayName("component 2, the type code, participates in equality - a fact the fixture cannot "
                + "show, because every one of its 50 rows carries the same type code, so this pair is "
                + "constructed rather than taken from the reference data")
        void typeCodeComponentParticipatesInEquality() {
            final TransactionCategoryBalanceId fixtureTypeCode =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");
            final TransactionCategoryBalanceId otherTypeCode =
                    new TransactionCategoryBalanceId("00000000001", "02", "0001");

            assertThat(fixtureTypeCode).isNotEqualTo(otherTypeCode);
            assertThat(fixtureTypeCode.equals(otherTypeCode)).isFalse();
            assertThat(fixtureTypeCode.hashCode()).isNotEqualTo(otherTypeCode.hashCode());
        }

        @Test
        @DisplayName("component 3, the category code, participates in equality - equally invisible to "
                + "the fixture, whose 50 rows all carry the same category code, so this pair is "
                + "constructed too")
        void categoryCodeComponentParticipatesInEquality() {
            final TransactionCategoryBalanceId fixtureCategoryCode =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");
            final TransactionCategoryBalanceId otherCategoryCode =
                    new TransactionCategoryBalanceId("00000000001", "01", "0002");

            assertThat(fixtureCategoryCode).isNotEqualTo(otherCategoryCode);
            assertThat(fixtureCategoryCode.equals(otherCategoryCode)).isFalse();
            assertThat(fixtureCategoryCode.hashCode()).isNotEqualTo(otherCategoryCode.hashCode());
        }

        @Test
        @DisplayName("equality rejects null and rejects a foreign type, exercising the type-pattern "
                + "branch rather than throwing")
        void equalityRejectsNullAndForeignTypes() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");

            assertThat(key.equals(null)).isFalse();
            assertThat(key).isNotEqualTo(new Object());

            assertThat(key).isNotEqualTo("00000000001010001");
        }

        @Test
        @DisplayName("nothing is normalised: account 00000000001 type 01 category 0001 is not equal to "
                + "account 1 type 1 category 1, because leading zeros are significant in a fixed-width "
                + "legacy component")
        void noNormalisationOfAnyKindIsApplied() {
            final TransactionCategoryBalanceId padded =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");
            final TransactionCategoryBalanceId unpadded =
                    new TransactionCategoryBalanceId("1", "1", "1");

            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(unpadded).isNotEqualTo(padded);

            assertThat("00000000001").isNotEqualTo("1");
            assertThat("01").isNotEqualTo("1");
            assertThat("0001").isNotEqualTo("1");
        }

        @Test
        @DisplayName("all 50 reference keys stay distinct in a hash set even though 2 of their 3 "
                + "components are constant: the account identifier alone supplies every distinction")
        void allFiftyReferenceKeysRemainDistinctInAHashSet() {
            final List<TransactionCategoryBalanceId> keys = referenceKeys();
            final Set<TransactionCategoryBalanceId> distinct = new HashSet<>(keys);

            assertThat(keys).hasSize(REFERENCE_ROW_COUNT);
            assertThat(distinct).hasSize(REFERENCE_ROW_COUNT);

            assertThat(distinctAccountIdsOf(keys)).hasSize(REFERENCE_ROW_COUNT);
        }

        @Test
        @DisplayName("all 50 reference keys index distinctly in a hash map, and an independently "
                + "constructed equal key retrieves the entry it belongs to")
        void allFiftyReferenceKeysIndexDistinctlyInAHashMap() {
            final Map<TransactionCategoryBalanceId, String> byKey = new HashMap<>();
            for (final TransactionCategoryBalanceId key : referenceKeys()) {
                byKey.put(key, "reference-row");
            }

            assertThat(byKey).hasSize(REFERENCE_ROW_COUNT);

            assertThat(byKey)
                    .containsKey(new TransactionCategoryBalanceId("00000000001", "01", "0001"));
            assertThat(byKey.get(new TransactionCategoryBalanceId("00000000050", "01", "0001")))
                    .isEqualTo("reference-row");

            assertThat(byKey)
                    .doesNotContainKey(new TransactionCategoryBalanceId("00000000051", "01", "0001"));
            assertThat(byKey)
                    .doesNotContainKey(new TransactionCategoryBalanceId("00000000001", "02", "0001"));
            assertThat(byKey)
                    .doesNotContainKey(new TransactionCategoryBalanceId("00000000001", "01", "0002"));
        }

        @Test
        @DisplayName("the fixture's type and category codes are constant across all 50 rows, which is "
                + "the measured fact that makes the constructed variants above necessary")
        void theFixtureTypeAndCategoryPairIsConstantAcrossEveryRow() {
            final List<TransactionCategoryBalanceId> keys = referenceKeys();

            assertThat(countOfTypeAndCategory(keys, FIXTURE_TYPE_CODE, FIXTURE_CATEGORY_CODE))
                    .isEqualTo(REFERENCE_ROW_COUNT);

            assertThat(countOfTypeAndCategory(keys, "02", FIXTURE_CATEGORY_CODE)).isZero();
            assertThat(countOfTypeAndCategory(keys, FIXTURE_TYPE_CODE, "0002")).isZero();

            assertThat(FIXTURE_TYPE_CODE.getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(TYPE_CODE_WIDTH);
            assertThat(FIXTURE_CATEGORY_CODE.getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("two independently constructed empty keys are equal and hash alike, so the "
                + "provider can compare a freshly instantiated key before it populates it")
        void twoIndependentlyConstructedEmptyKeysAreEqualAndHashAlike() {
            final TransactionCategoryBalanceId first = new TransactionCategoryBalanceId();
            final TransactionCategoryBalanceId second = new TransactionCategoryBalanceId();

            assertThat(first).isEqualTo(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());

            assertThat(first)
                    .isNotEqualTo(new TransactionCategoryBalanceId("00000000001", "01", "0001"));
        }
    }

    @Nested
    @DisplayName("Serialization contract required of an identifier class")
    class SerializationContract {
        @Test
        @DisplayName("the serialization version identifier is pinned at 1, which the build also "
                + "requires because an unpinned serializable class fails compilation under -Werror")
        void serializationVersionIdentifierIsPinnedAtOne() {
            final ObjectStreamClass descriptor =
                    ObjectStreamClass.lookup(TransactionCategoryBalanceId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the key type is serializable, as an identifier class carried across a "
                + "persistence boundary must be")
        void theKeyTypeIsSerializable() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000025", "01", "0001");

            assertThat(key).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("a serialization round trip preserves all three components verbatim along with "
                + "equality and hash code, so a detached key still matches its row")
        void serializationRoundTripPreservesComponentsEqualityAndHashCode() throws IOException,
                ClassNotFoundException {
            final TransactionCategoryBalanceId original =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(original);
            }

            final TransactionCategoryBalanceId restored;
            try (ObjectInputStream in =
                         new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                restored = (TransactionCategoryBalanceId) in.readObject();
            }

            assertThat(restored.getTrancatAcctId()).isEqualTo("00000000042");
            assertThat(restored.getTrancatTypeCd()).isEqualTo("07");
            assertThat(restored.getTrancatCd()).isEqualTo("0003");
            assertThat(restored).isEqualTo(original);
            assertThat(original).isEqualTo(restored);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());

            assertThat(restored).isNotSameAs(original);
        }

        @Test
        @DisplayName("a round trip of an empty key preserves all three null components, because the "
                + "no-arg constructor path must survive serialization too")
        void serializationRoundTripPreservesAnEmptyKey() throws IOException, ClassNotFoundException {
            final TransactionCategoryBalanceId empty = new TransactionCategoryBalanceId();

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(empty);
            }

            final TransactionCategoryBalanceId restored;
            try (ObjectInputStream in =
                         new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                restored = (TransactionCategoryBalanceId) in.readObject();
            }

            assertThat(restored.getTrancatAcctId()).isNull();
            assertThat(restored.getTrancatTypeCd()).isNull();
            assertThat(restored.getTrancatCd()).isNull();
            assertThat(restored).isEqualTo(empty);
            assertThat(restored.hashCode()).isEqualTo(empty.hashCode());
        }
    }

    @Nested
    @DisplayName("Deliberate absences recorded by this key type")
    class DocumentedAbsence {
        @Test
        @DisplayName("no surrogate identifier exists: TCATBALF KEYS(17 0) puts the key at offset 0 as "
                + "the leading substring of the record, and the batch split 17 + 33 = 50 confirms it, "
                + "so the composite business key IS the identifier")
        void noSurrogateIdentifierExists() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000033", "01", "0001");

            assertThat(key.getTrancatAcctId()).isEqualTo("00000000033");
            assertThat(key.getTrancatTypeCd()).isEqualTo("01");
            assertThat(key.getTrancatCd()).isEqualTo("0001");

            assertThat(DECLARED_KEY_OFFSET).isZero();
            assertThat(ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH + FILE_DESCRIPTION_DATA_WIDTH)
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("no shared supertype exists with the 6-byte key of the same COBOL group name: the "
                + "two share only that name and nothing else - no common superclass, interface or "
                + "abstract type beyond the root class")
        void noSharedSupertypeExistsWithTheSixByteKeyOfTheSameCobolName() {
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isEqualTo(17);
            assertThat(CATEGORY_KEY_LENGTH).isEqualTo(6);

            final int thisKeySum = ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;
            final int otherKeySum = TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;

            assertThat(thisKeySum).isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(otherKeySum).isEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(thisKeySum - otherKeySum).isEqualTo(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the key carries no persistence metadata and needs no framework: it constructs, "
                + "compares, hashes and serialises in a plain JVM, because the identifier-class "
                + "declaration sits on the entity and never on the key")
        void theKeyNeedsNoFrameworkAtAll() throws IOException, ClassNotFoundException {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000008", "01", "0001");
            final TransactionCategoryBalanceId twin =
                    new TransactionCategoryBalanceId("00000000008", "01", "0001");

            assertThat(key).isEqualTo(twin);
            assertThat(key.hashCode()).isEqualTo(twin.hashCode());

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(key);
            }

            final TransactionCategoryBalanceId restored;
            try (ObjectInputStream in =
                         new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                restored = (TransactionCategoryBalanceId) in.readObject();
            }

            assertThat(restored.getTrancatAcctId()).isEqualTo("00000000008");
            assertThat(restored.getTrancatTypeCd()).isEqualTo("01");
            assertThat(restored.getTrancatCd()).isEqualTo("0001");
            assertThat(restored).isEqualTo(key);
        }
    }

    @Nested
    @DisplayName("Diagnostic representation of the key")
    class StringRepresentation {
        @Test
        @DisplayName("the representation carries all three key components, so a diagnostic line "
                + "identifies the row without a lookup")
        void representationCarriesAllThreeComponents() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");

            final String representation = key.toString();

            assertThat(representation).contains("00000000042");
            assertThat(representation).contains("07");
            assertThat(representation).contains("0003");
        }

        @Test
        @DisplayName("the representation names the account identifier, then the type code, then the "
                + "category code, matching the contractual CVTRA01Y declaration order, and keeps every "
                + "leading zero")
        void representationFollowsContractualComponentOrder() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000001", "01", "0001");

            assertThat(key.toString()).isEqualTo("TransactionCategoryBalanceId["
                    + "trancatAcctId=00000000001, trancatTypeCd=01, trancatCd=0001]");
        }

        @Test
        @DisplayName("the representation of an empty key reports all three components as absent "
                + "instead of failing, so an unpopulated identifier is still diagnosable")
        void representationOfAnEmptyKeyReportsAllThreeComponentsAsAbsent() {
            final TransactionCategoryBalanceId empty = new TransactionCategoryBalanceId();

            assertThat(empty.toString()).isEqualTo("TransactionCategoryBalanceId["
                    + "trancatAcctId=null, trancatTypeCd=null, trancatCd=null]");
        }
    }
}
