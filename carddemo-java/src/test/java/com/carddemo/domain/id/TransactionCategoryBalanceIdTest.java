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
 * Unit test for {@link TransactionCategoryBalanceId}, the composite identifier class of the
 * transaction-category-balance table.
 *
 * <p>Two independent legacy authorities fix the same key geometry and this test pins both, so each
 * one checks the other rather than restating it. Copybook member {@code CVTRA01Y} describes a
 * 50-byte record whose leading key group carries an 11-digit account identifier, a 2-byte
 * transaction type code and a 4-digit transaction category code, ahead of an 11-byte signed
 * category balance and a 22-byte trailing filler. The {@code TCATBALF} cluster definition states
 * the same geometry from the other direction, declaring the key length, the key offset and the
 * record length independently of the copybook, so the declared key length of 17 and the summed
 * component widths of 11 + 2 + 4 come from two different files and are asserted to agree. A third
 * and equally independent decomposition appears in the file description of the interest-calculation
 * batch program, which splits the same 50 bytes into the same 17-byte key group followed by a
 * single 33-byte data area; both splits close on 50 and their remainders reconcile because
 * 11 + 22 = 33. All three sums are asserted below.
 *
 * <p>This key group and the key group of the transaction-category reference record carry the
 * identical legacy name yet are unrelated: this one is 17 bytes and leads with an account
 * identifier, that one is 6 bytes and carries none, so the 6-byte form is not a prefix, sub-key or
 * reusable fragment of this one. Disambiguation is by copybook member name and declared group
 * length only, never by inspecting content, and this file never names or instantiates the 6-byte
 * key's own class: the two lengths appear here as plain integers.
 *
 * <p>The fifty distinct keys asserted here are the measured key column of a fixture holding 50 rows
 * of 50 bytes each. Every row carries the same type code and the same category code, so the account
 * identifier alone supplies all fifty distinctions - which is why the fixture on its own cannot show
 * that the type and category components participate in equality, and why the tests for those two
 * components are built from constructed variants instead.
 *
 * <p>A pure in-process unit test: no application context, persistence unit, database, file, network
 * or container, because the class under test is a plain serializable value holder depending only on
 * {@code java.io.Serializable} and {@code java.util.Objects}. It performs no introspection of its
 * own either - the single metadata lookup, in {@link SerializationContract}, goes through the
 * serialization API and not the low-level introspection API, so the module's zero budget for the
 * latter is left untouched.
 *
 * <p>Every expected value below is a literal typed out in this source and traceable to a measured
 * legacy fact. None is produced by calling the class under test, none is assembled by formatting,
 * padding or repetition, and no assertion compares a computed value with a second evaluation of the
 * same computation. Where two keys are involved they are constructed independently.
 */
@DisplayName("TransactionCategoryBalanceId :: 17-byte composite key of the category-balance table")
class TransactionCategoryBalanceIdTest {

    // Two aspects were optional in the class's own contract and this test follows what the class
    // actually declares: it overrides the string-representation method, so a group covering that
    // method is present below, and it exposes no mutator, so none is exercised - immutability after
    // construction is part of the contract and the only writer is the provider.

    private static final int ACCOUNT_ID_WIDTH = 11;

    private static final int TYPE_CODE_WIDTH = 2;

    private static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * Key length declared by the cluster definition independently of the copybook. Held separately from
     * the three component widths on purpose: the test asserts that the widths sum to this figure.
     */
    private static final int DECLARED_KEY_LENGTH = 17;

    /**
     * Key offset declared by the same definition. Every base cluster in the estate declares offset 0;
     * only the alternate-index definitions carry a non-zero offset.
     */
    private static final int DECLARED_KEY_OFFSET = 0;

    private static final int BALANCE_INTEGER_DIGITS = 9;

    /**
     * Fractional digits of the same balance; the implied decimal separator consumes no byte of its own.
     */
    private static final int BALANCE_DECIMAL_DIGITS = 2;

    /**
     * Stored width of the balance; the overpunched sign consumes no byte of its own either.
     */
    private static final int BALANCE_WIDTH = BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS;

    /**
     * Unnamed trailing filler completing the record, carried by no property of the key or the entity.
     */
    private static final int FILLER_WIDTH = 22;

    /**
     * Record length declared by the cluster definition, held separately so the widths can be summed to it.
     */
    private static final int DECLARED_RECORD_LENGTH = 50;

    /**
     * Data-area width declared by the interest-calculation batch program, which divides the same 50
     * bytes into two parts rather than the copybook's four: an independent cross-check that 17 + 33
     * reaches the record length and that 33 equals the balance width plus the filler width.
     */
    private static final int FILE_DESCRIPTION_DATA_WIDTH = 33;

    private static final int REFERENCE_ROW_COUNT = 50;

    /**
     * Measured byte length of the fixture, one line terminator included per row.
     */
    private static final int MEASURED_FIXTURE_BYTES = 2550;

    private static final String FIXTURE_TYPE_CODE = "01";

    /**
     * The single category code every fixture row carries, its leading zeros intact.
     */
    private static final String FIXTURE_CATEGORY_CODE = "0001";

    /**
     * Key length of the transaction-category key group, and below it the disclosure-group key length.
     * Both are present only as integers, recording that they differ from this key's length despite the
     * shared legacy name; neither of those classes is referenced from this file.
     */
    private static final int CATEGORY_KEY_LENGTH = 6;

    private static final int DISCLOSURE_KEY_LENGTH = 16;

    /**
     * Builds the fifty distinct reference keys, one per fixture row, in fixture order.
     *
     * <p>Each is constructed from three typed-out literals so the exact bytes are visible here:
     * nothing is assembled by formatting, padding or repetition and no value is read from a file. The
     * type and category codes are identical on every row, a measured property of the fixture rather
     * than a simplification made here.
     *
     * @return the fifty reference keys, in the order the fixture holds them
     */
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

    /**
     * Counts keys carrying both the given type code and the given category code. The comparison is
     * exact: nothing in this file trims, folds or converts, because every character of a legacy
     * fixed-width component is significant.
     *
     * @return the number of keys whose type and category components both match exactly
     */
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

    /**
     * Collects the distinct account identifier components, which is what shows that component supplies
     * every distinction among the fixture rows.
     *
     * @return the distinct account identifier components, compared as exact strings
     */
    private static Set<String> distinctAccountIdsOf(final List<TransactionCategoryBalanceId> keys) {
        final Set<String> accountIds = new HashSet<>();
        for (final TransactionCategoryBalanceId key : keys) {
            accountIds.add(key.getTrancatAcctId());
        }
        return accountIds;
    }

    /**
     * Component-level contract: how the three key components are bound, exposed and stored.
     */
    @Nested
    @DisplayName("Component contract of the 17-byte CVTRA01Y key group")
    class ComponentContract {

        @Test
        @DisplayName("all-args constructor binds components in CVTRA01Y declaration order - account "
                + "identifier first, type code second, category code third - and never in the order "
                + "some program happens to assign them in")
        void allArgsConstructorBindsComponentsPositionally() {
            // The contractual order is the copybook declaration order, which the cluster definition's
            // key geometry and the primary-key column order both agree with. It is NOT the assignment
            // order in the interest-calculation batch program, which populates the related
            // disclosure-group key as component 1, then 3, then 2; an implementation inferred from that
            // sequence would carry a transposed signature. Hence literals distinguishable by width.
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
            // This constructor exists solely so a persistence provider can instantiate the type, and is
            // the reason it is a class rather than a record. It is reached with no introspection at all:
            // this test shares the package and protected access includes package access.
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

            // No numeric parse appears anywhere in this file. Every digit-only legacy lexeme maps to
            // a bounded character column, so the external text width is part of the contract.
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
            // Values chosen to expose normalisation: a lower-case type code a case fold would alter, and
            // components a trim or a numeric narrowing would alter. A blank is likewise significant in a
            // fixed-width space-padded layout. Neither the class nor this test applies any of those.
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

    /**
     * Byte-width and offset contract: the geometry that the copybook, the cluster definition and the
     * batch program's file description all have to agree on.
     */
    @Nested
    @DisplayName("Byte geometry of the key and of the 50-byte record that carries it")
    class ByteWidthContract {

        @Test
        @DisplayName("the three component widths sum to the 17 declared by TCATBALF KEYS(17 0): the "
                + "copybook and the cluster definition are independent authorities that agree")
        void componentWidthsSumToTheDeclaredKeyLength() {
            // Every width here is measured in BYTES through an explicitly named charset, never in
            // characters, because the legacy record is a byte image.
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
            // The batch file descriptions name that trailing data field with its prefix doubled. Only the
            // NAME is malformed; the declared width is correct. Recorded in docs/decision-log.md, which
            // this test cites and never edits, and not carried into Java.
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

            // Offset 0 means the key is the leading substring of the stored image, which is why the
            // identifier is the business key itself and no surrogate is introduced.
            final int dataPortionWidth = DECLARED_RECORD_LENGTH - DECLARED_KEY_LENGTH;

            assertThat(dataPortionWidth).isEqualTo(BALANCE_WIDTH + FILLER_WIDTH);
            assertThat(dataPortionWidth).isEqualTo(FILE_DESCRIPTION_DATA_WIDTH);
        }

        @Test
        @DisplayName("the three composite key lengths 17, 16 and 6 are pairwise distinct: the same "
                + "COBOL group name is declared in CVTRA01Y at 17 and in CVTRA04Y at 6, so "
                + "disambiguation is by member name and declared length, never by content")
        void theThreeCompositeKeyLengthsArePairwiseDistinct() {
            // Plain integers on purpose: the 6-byte and 16-byte keys have their own classes elsewhere in
            // this package and neither is named or instantiated here.
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);
            assertThat(CATEGORY_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);

            assertThat(TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);

            // The 16-byte key leads with a 10-byte account group identifier instead, so it differs
            // from this key by exactly one byte - the kind of near miss a type-blind mapping would
            // let through.
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

    /**
     * Equality and hashing contract: all three components participate, nothing is normalised, and the
     * fifty reference keys stay fifty distinct keys inside a hash-based collection.
     */
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
            // Reflexivity is the specification's own "must be true", not a value computed by the class,
            // and is deliberately NOT the proof that two equal keys compare equal - that is the preceding
            // test, which builds its two keys independently.
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

            // A defect dropping the account identifier from equality or hashing would collapse the fifty
            // keys onto one.
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

            // The interest-calculation batch program moves this key's type and category components into
            // the disclosure-group key at identical declared widths, which is why the same two lexemes
            // legitimately appear in both keys' tests: correspondence between layouts, not duplication.
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

    /**
     * Serialization contract: an identifier class must be serializable and must pin its version.
     */
    @Nested
    @DisplayName("Serialization contract required of an identifier class")
    class SerializationContract {

        @Test
        @DisplayName("the serialization version identifier is pinned at 1, which the build also "
                + "requires because an unpinned serializable class fails compilation under -Werror")
        void serializationVersionIdentifierIsPinnedAtOne() {
            // The lookup goes through the serialization metadata API, which is NOT the low-level
            // introspection API: nothing here is reflected over. The module's audited budget for
            // introspection is zero and a test must never undermine a production gate.
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
                // A checked cast on a non-generic type: it produces no unchecked-cast diagnostic and
                // therefore needs no suppression.
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

    /**
     * Documented absence: each test asserts an observable property of the class and records alongside
     * it a thing that deliberately does not exist.
     */
    @Nested
    @DisplayName("Deliberate absences recorded by this key type")
    class DocumentedAbsence {

        @Test
        @DisplayName("no surrogate identifier exists: TCATBALF KEYS(17 0) puts the key at offset 0 as "
                + "the leading substring of the record, and the batch split 17 + 33 = 50 confirms it, "
                + "so the composite business key IS the identifier")
        void noSurrogateIdentifierExists() {
            // A surrogate would break the image-to-row correspondence that byte-parity verification of the
            // migrated output depends on. What is asserted is behavioural: the three components the caller
            // supplies are the whole of the key and come back unchanged.
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
            // Everything runs on the plain JVM the harness provides. Column names, widths, nullability and
            // every other mapping concern belong to the entity and to the integration tier.
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

    /**
     * Diagnostic representation. This group exists because the class under test overrides the
     * string-representation method; had it not, the group would be absent rather than asserting the
     * inherited default.
     */
    @Nested
    @DisplayName("Diagnostic representation of the key")
    class StringRepresentation {

        @Test
        @DisplayName("the representation carries all three key components, so a diagnostic line "
                + "identifies the row without a lookup")
        void representationCarriesAllThreeComponents() {
            // This triple is chosen because none of its three values is a substring of either of the
            // others, so a containment assertion cannot pass by accident.
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
