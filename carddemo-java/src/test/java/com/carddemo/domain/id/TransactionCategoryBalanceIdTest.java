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
 * <p><strong>What this test proves.</strong> Two independent legacy authorities fix the same key
 * geometry and this test pins both, so each one checks the other rather than restating it. Copybook
 * member {@code CVTRA01Y} describes a 50-byte record whose leading key group carries an 11-digit
 * account identifier at offset 0, a 2-byte transaction type code at offset 11 and a 4-digit
 * transaction category code at offset 13, ahead of an 11-byte signed category balance at offset 17
 * and a 22-byte trailing filler at offset 28. The {@code TCATBALF} cluster definition states the
 * same geometry from the other direction, declaring {@code KEYS(17 0)} with
 * {@code RECORDSIZE(50 50)} on an {@code INDEXED} cluster. The declared key length of 17 and the
 * summed component widths of 11 + 2 + 4 therefore come from two different files, and this test
 * asserts that they agree.
 *
 * <p>A third and equally independent decomposition of the same 50 bytes appears in the file
 * description of the interest-calculation batch program, which splits the record into the same
 * 17-byte key group followed by a single 33-byte data area. Both splits have to close on 50 and
 * their two remainders have to reconcile, because 11 + 22 = 33. All three sums are asserted below.
 *
 * <p><strong>The shared COBOL group name.</strong> The key group of this record and the key group
 * of the transaction-category reference record carry the identical COBOL name, yet the two keys are
 * unrelated: this one is 17 bytes wide and leads with an account identifier, while that one is 6
 * bytes wide and carries no account identifier at all. The 6-byte form is therefore not a prefix,
 * sub-key or reusable fragment of this one. Disambiguation is by copybook member name and declared
 * group length only, never by inspecting content, and this file deliberately never names or
 * instantiates the 6-byte key's own class: the two lengths appear here as plain integers.
 *
 * <p><strong>Reference data.</strong> The fifty distinct keys asserted here are the measured key
 * column of the transaction-category-balance fixture, which holds 50 rows of 50 bytes each. Every
 * row of that fixture carries the same type code and the same category code, so the account
 * identifier alone supplies all fifty distinctions. That is precisely why the fixture on its own
 * cannot show that the type and category components participate in equality, and why the tests for
 * those two components are built from constructed variants instead. Only key values are reproduced,
 * as data; no line of legacy source is transcribed anywhere in this file.
 *
 * <p><strong>Scope.</strong> This is a pure in-process unit test. It starts no application context,
 * opens no persistence unit, reaches no database, reads no file, touches no network and runs no
 * container: the class under test is a plain serializable value holder whose only dependencies are
 * {@code java.io.Serializable} and {@code java.util.Objects}, so a plain JVM is the whole of its
 * required environment. It also performs no introspection of its own - the single metadata lookup
 * it makes, in {@link SerializationContract}, goes through the serialization API and not through the
 * low-level introspection API, so the module's zero budget for the latter is left untouched.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every expected value below is a
 * literal typed out in this source and traceable to a measured legacy fact. No expectation is
 * produced by calling the class under test, no expected value is assembled by formatting, padding or
 * repetition, and no assertion compares a computed value with a second evaluation of the same
 * computation. Where an equality or hash expectation involves two keys, the two keys are constructed
 * independently of one another.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited as provenance only and never asserted
 * against a member.
 */
@DisplayName("TransactionCategoryBalanceId :: 17-byte composite key of the category-balance table")
class TransactionCategoryBalanceIdTest {

    // Two aspects of the class under test were optional in its own contract, and this test follows
    // what the class actually declares rather than what it might have declared. First, it overrides
    // the string-representation method, so a group covering that method is present below; had it
    // inherited the default, the group would be absent and the inherited format would never be
    // asserted. Second, it exposes no mutator for any of the three components, so no mutator is
    // exercised: immutability after construction is part of the contract, and the only writer is the
    // persistence provider populating an instance it created through the no-argument constructor.

    /**
     * Width of the account identifier component: the first member of the key group declared in
     * copybook member {@code CVTRA01Y} is an 11-digit numeric field.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Width of the transaction type code component: the second member of the same key group is a
     * 2-byte alphanumeric field.
     */
    private static final int TYPE_CODE_WIDTH = 2;

    /**
     * Width of the transaction category code component: the third member of the same key group is a
     * 4-digit numeric field.
     */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * Key length declared by the {@code TCATBALF} cluster definition as the first argument of
     * {@code KEYS(17 0)}. Held separately from the three component widths on purpose: the test
     * asserts that the copybook component widths sum to this independently declared figure.
     */
    private static final int DECLARED_KEY_LENGTH = 17;

    /**
     * Key offset declared by the {@code TCATBALF} cluster definition as the second argument of
     * {@code KEYS(17 0)}. Every cluster definition in the estate declares offset 0; only the
     * alternate-index definitions carry a non-zero offset.
     */
    private static final int DECLARED_KEY_OFFSET = 0;

    /**
     * Integer digit count of the signed category balance that follows the key. Not part of the key:
     * the balance is a non-key attribute of the entity.
     */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /**
     * Fractional digit count of the same balance. The implied decimal separator of the legacy
     * picture consumes no byte of its own, which is why the stored width is the plain sum of the
     * integer and fractional digit counts.
     */
    private static final int BALANCE_DECIMAL_DIGITS = 2;

    /**
     * Stored width of the signed category balance, 9 integer digits plus 2 fractional digits. The
     * sign is overpunched into the final byte and likewise consumes no byte of its own.
     */
    private static final int BALANCE_WIDTH = BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS;

    /**
     * Width of the unnamed trailing filler that completes the record, 22 bytes. Not part of the key,
     * and carried by no property of either the key or the entity.
     */
    private static final int FILLER_WIDTH = 22;

    /**
     * Record length declared by the {@code TCATBALF} cluster definition as
     * {@code RECORDSIZE(50 50)} and restated in the copybook header. Held separately so the test can
     * assert that the field widths sum to it.
     */
    private static final int DECLARED_RECORD_LENGTH = 50;

    /**
     * Width of the single data area that follows the key in the file description of the
     * interest-calculation batch program, 33 bytes. That declaration divides the same 50-byte image
     * into two parts rather than the copybook's four, so it is an independent cross-check on the key
     * length: 17 + 33 has to reach the declared record length, and 33 has to equal the balance width
     * plus the filler width.
     */
    private static final int FILE_DESCRIPTION_DATA_WIDTH = 33;

    /**
     * Number of rows in the transaction-category-balance fixture, measured directly from the file.
     */
    private static final int REFERENCE_ROW_COUNT = 50;

    /**
     * Measured byte length of the transaction-category-balance fixture, one line terminator
     * included per row.
     */
    private static final int MEASURED_FIXTURE_BYTES = 2550;

    /**
     * The single transaction type code every row of the fixture carries. Reproduced as a data value.
     */
    private static final String FIXTURE_TYPE_CODE = "01";

    /**
     * The single transaction category code every row of the fixture carries, with its leading zeros
     * intact. Reproduced as a data value.
     */
    private static final String FIXTURE_CATEGORY_CODE = "0001";

    /**
     * Key length of the transaction-category key group declared in copybook member
     * {@code CVTRA04Y}, whose cluster definition declares {@code KEYS(6 0)}. Present only as an
     * integer, to record that it differs from this key's length despite the two groups sharing their
     * COBOL name. The 6-byte key's own class is never referenced from this file.
     */
    private static final int CATEGORY_KEY_LENGTH = 6;

    /**
     * Key length of the disclosure-group key declared in copybook member {@code CVTRA02Y}, whose
     * cluster definition declares {@code KEYS(16 0)}. Present only as an integer, for the same
     * reason as the preceding constant.
     */
    private static final int DISCLOSURE_KEY_LENGTH = 16;

    /**
     * Builds the fifty distinct reference keys, one per row of the transaction-category-balance
     * fixture, in fixture order.
     *
     * <p>Each key is constructed explicitly from three typed-out literals so that the exact bytes of
     * every component are visible in this source. No value is assembled by formatting, padding or
     * repetition, no loop synthesises an account identifier, and no value is read from a file: the
     * fixture was measured once during analysis and its key column is reproduced here as data.
     *
     * <p>The type code and the category code are identical on every row, which is a measured
     * property of the fixture rather than a simplification made here.
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
     * Counts how many of the supplied keys carry both the given type code and the given category
     * code, by exact string comparison on each component.
     *
     * <p>The comparison is exact on purpose: no trimming, folding or numeric conversion is applied
     * anywhere in this file, because every character of a legacy fixed-width component is
     * significant.
     *
     * @param keys         the keys to scan
     * @param typeCode     the 2-byte type code to match
     * @param categoryCode the 4-byte category code to match
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
     * Collects the distinct account identifier components of the supplied keys.
     *
     * <p>Used to show that the account identifier is the component supplying every distinction among
     * the fixture rows, given that the other two components are constant across all of them.
     *
     * @param keys the keys to scan
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
            // The contractual order is the copybook DECLARATION order, which is what the cluster
            // definition's key geometry and the primary-key column order both agree with. It is NOT
            // the assignment order seen in the interest-calculation batch program: that program
            // populates the related disclosure-group key as component 1, then component 3, then
            // component 2. An implementation inferred from that assignment sequence would carry a
            // transposed signature, so all three positions are pinned here with literals whose
            // widths as well as whose values are mutually distinguishable.
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000042", "07", "0003");

            assertThat(key.getTrancatAcctId()).isEqualTo("00000000042");
            assertThat(key.getTrancatTypeCd()).isEqualTo("07");
            assertThat(key.getTrancatCd()).isEqualTo("0003");

            // A second, independent case taken straight from the fixture, whose components cannot be
            // confused with the first triple.
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

            // Every width here is measured in BYTES through an explicitly named charset, never in
            // characters, because the legacy record is a byte image.
            final int boundAccountIdWidth =
                    key.getTrancatAcctId().getBytes(StandardCharsets.US_ASCII).length;
            final int boundTypeCodeWidth =
                    key.getTrancatTypeCd().getBytes(StandardCharsets.US_ASCII).length;
            final int boundCategoryCodeWidth =
                    key.getTrancatCd().getBytes(StandardCharsets.US_ASCII).length;

            assertThat(boundAccountIdWidth).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(boundTypeCodeWidth).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(boundCategoryCodeWidth).isEqualTo(CATEGORY_CODE_WIDTH);

            // Pairwise distinct, which is what makes any swap of two arguments visible.
            assertThat(boundAccountIdWidth).isNotEqualTo(boundTypeCodeWidth);
            assertThat(boundAccountIdWidth).isNotEqualTo(boundCategoryCodeWidth);
            assertThat(boundTypeCodeWidth).isNotEqualTo(boundCategoryCodeWidth);
        }

        @Test
        @DisplayName("no-arg constructor leaves all three components null, because the persistence "
                + "provider populates an identifier class after instantiating it")
        void noArgConstructorLeavesAllThreeComponentsNull() {
            // This constructor exists solely because an identifier class must be instantiable by the
            // persistence provider through a no-argument constructor, and it is the reason the type
            // is a class rather than a record. It is declared protected, and it is reachable from
            // here with no introspection at all because this test shares the class's package and
            // protected access includes package access.
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

            // The surviving leading zeros are what hold the text widths at the declared figures.
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
            // Values deliberately chosen to expose any normalisation: a lower-case type code that a
            // case fold would alter, and components that a trim or a numeric narrowing would alter.
            // The class under test applies none of those, and neither does this test.
            final TransactionCategoryBalanceId unaltered =
                    new TransactionCategoryBalanceId("0000000004x", "ab", "0090");

            assertThat(unaltered.getTrancatAcctId()).isEqualTo("0000000004x");
            assertThat(unaltered.getTrancatTypeCd()).isEqualTo("ab");
            assertThat(unaltered.getTrancatCd()).isEqualTo("0090");

            // A blank-bearing value is also stored exactly as supplied: the legacy layout is
            // fixed-width and space-padded, so a blank is a significant character, not noise.
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
            // Each offset is derived by summing the widths that precede it, so no offset appears as
            // an unexplained literal.
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

            // The balance width is the plain sum of its digit counts because the implied decimal
            // separator consumes no byte and the sign is overpunched into the final byte.
            assertThat(BALANCE_WIDTH).isEqualTo(BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS);
            assertThat(BALANCE_WIDTH).isEqualTo(11);

            // The two non-key fields account for the whole of the record beyond the key, so nothing
            // in the 50-byte image is unaccounted for.
            assertThat(recordWidth - keyWidth).isEqualTo(BALANCE_WIDTH + FILLER_WIDTH);
        }

        @Test
        @DisplayName("the batch file description splits the same 50 bytes into 17 + 33 and reconciles "
                + "with the copybook's 11 + 22 = 33; its data field carries a doubled name prefix in "
                + "two programs, an anomaly recorded in docs/decision-log.md and never propagated")
        void theFileDescriptionSplitReconcilesWithTheCopybookSplit() {
            // The file description of the interest-calculation batch program, and of the posting
            // program alongside it, names that trailing data field with its prefix doubled. Only the
            // NAME is malformed; the declared width is correct. The malformation is therefore
            // recorded in docs/decision-log.md and is not carried into Java, where the three key
            // components are named for the copybook fields and the remainder is the entity's balance
            // property. This test never edits that log; it only cites it.
            final int keyWidth = ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;

            assertThat(keyWidth + FILE_DESCRIPTION_DATA_WIDTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(DECLARED_KEY_LENGTH + FILE_DESCRIPTION_DATA_WIDTH).isEqualTo(50);

            // The reconciliation: the batch program's single data area is exactly the copybook's
            // balance plus its trailing filler, which is what makes the two splits the same split.
            assertThat(BALANCE_WIDTH + FILLER_WIDTH).isEqualTo(FILE_DESCRIPTION_DATA_WIDTH);
            assertThat(BALANCE_WIDTH + FILLER_WIDTH).isEqualTo(33);
        }

        @Test
        @DisplayName("KEYS(17 0) declares length 17 at offset 0; every DEFINE CLUSTER in the estate "
                + "declares offset 0 and only DEFINE ALTERNATEINDEX blocks carry a non-zero offset")
        void clusterDefinitionDeclaresLengthSeventeenAtOffsetZero() {
            // The first argument of KEYS is the key length and the second is the key offset. The
            // length is corroborated independently by the copybook, whose three component widths sum
            // to the same figure, so these two authorities check one another.
            assertThat(ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isEqualTo(17);
            assertThat(DECLARED_KEY_OFFSET).isZero();

            // Because the offset is 0, the key starts where the record starts: it is the leading
            // substring of the stored image and the remaining bytes are the data portion. That is why
            // the identifier is the business key itself and no surrogate is introduced.
            final int dataPortionWidth = DECLARED_RECORD_LENGTH - DECLARED_KEY_LENGTH;

            assertThat(dataPortionWidth).isEqualTo(BALANCE_WIDTH + FILLER_WIDTH);
            assertThat(dataPortionWidth).isEqualTo(FILE_DESCRIPTION_DATA_WIDTH);
        }

        @Test
        @DisplayName("the three composite key lengths 17, 16 and 6 are pairwise distinct: the same "
                + "COBOL group name is declared in CVTRA01Y at 17 and in CVTRA04Y at 6, so "
                + "disambiguation is by member name and declared length, never by content")
        void theThreeCompositeKeyLengthsArePairwiseDistinct() {
            // Asserted as plain integers on purpose. The 6-byte and 16-byte keys have their own
            // classes elsewhere in this package, and this file deliberately never names or
            // instantiates either of them: the 6-byte key is not a prefix, sub-key or reusable
            // fragment of this one, whose leading component is an 11-digit account identifier that
            // the 6-byte key does not carry at all.
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);
            assertThat(CATEGORY_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);

            // The 6-byte key's arithmetic is this key's trailing two components and nothing else,
            // which is exactly why it cannot align with this key at any offset.
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

            // Every one of the fifty keys measures the declared 17 bytes, so no row of the fixture
            // carries a component at an off-contract width.
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
            // Built separately from typed-out literals, so neither key is derived from the other and
            // neither hash code is compared with a second evaluation of itself.
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
            // Reflexivity is a required property of the equality contract, so the expectation here is
            // the specification's own "must be true" and not a value computed by the class under test.
            // It is also the only assertion that reaches the identity short-circuit at the head of the
            // equality method, and it is deliberately NOT the proof that two equal keys compare equal
            // - that proof is the preceding test, which builds its two keys independently.
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
            // The fixture holds one type code across all fifty rows. A defect that dropped this
            // component from equality or hashing would therefore pass every fixture-derived assertion
            // in this file untouched. Only a constructed variant exposes it.
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

            // A plain string carrying the same seventeen bytes as the concatenated key is still a
            // foreign type and must not compare equal to the key.
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

            // Documented at the string level as well, so the semantic is explicit rather than implied:
            // the class simply compares the components it was given, and these components differ.
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

            // The fifty distinctions come from the account identifier column and from nowhere else, so
            // a defect that dropped that component from equality or hashing would collapse these
            // fifty keys onto one.
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

            // Retrieval by a key built fresh from literals is the real proof that equality and hashing
            // cooperate: a mismatch between them would leave the entry unreachable.
            assertThat(byKey)
                    .containsKey(new TransactionCategoryBalanceId("00000000001", "01", "0001"));
            assertThat(byKey.get(new TransactionCategoryBalanceId("00000000050", "01", "0001")))
                    .isEqualTo("reference-row");

            // Triples the fixture does not contain must be absent, so the map is not matching
            // indiscriminately: one account beyond the seeded range, and two variants that differ from
            // a seeded row only in a component the fixture holds constant.
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

            // No second pair occurs anywhere in the fixture, in either component.
            assertThat(countOfTypeAndCategory(keys, "02", FIXTURE_CATEGORY_CODE)).isZero();
            assertThat(countOfTypeAndCategory(keys, FIXTURE_TYPE_CODE, "0002")).isZero();

            // The interest-calculation batch program moves this key's type and category components
            // into the disclosure-group key's corresponding components at identical declared widths,
            // which is why these same two lexemes legitimately appear in the tests of both keys. That
            // is correspondence between two layouts, not duplication of one.
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

            // An empty key is not equal to a populated one, so the all-null state is a distinct value
            // rather than a wildcard.
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
            // The lookup below goes through the serialization metadata API, which is NOT the low-level
            // introspection API: no class, constructor, field or method is reflected over here. The
            // module's audited budget for introspection is zero, and a test must never undermine a
            // production gate to make an assertion easier, so this is the one permitted idiom.
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

            // Components are compared against the literals the original was built from, not against
            // the original's own accessors, so the expectation is independent of the round trip.
            assertThat(restored.getTrancatAcctId()).isEqualTo("00000000042");
            assertThat(restored.getTrancatTypeCd()).isEqualTo("07");
            assertThat(restored.getTrancatCd()).isEqualTo("0003");
            assertThat(restored).isEqualTo(original);
            assertThat(original).isEqualTo(restored);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());

            // The restored instance is a genuinely separate object, so the equality above is value
            // equality and not an identity coincidence.
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
     * Documented absence: each test proves something real while recording, by never naming it, a thing
     * that deliberately does not exist.
     */
    @Nested
    @DisplayName("Deliberate absences recorded by this key type")
    class DocumentedAbsence {

        @Test
        @DisplayName("no surrogate identifier exists: TCATBALF KEYS(17 0) puts the key at offset 0 as "
                + "the leading substring of the record, and the batch split 17 + 33 = 50 confirms it, "
                + "so the composite business key IS the identifier")
        void noSurrogateIdentifierExists() {
            // A surrogate would break the image-to-row correspondence that byte-parity verification of
            // the migrated output depends on. The class exposes no aggregate identifier accessor
            // beyond the three components, and no generated-value accessor of any kind - an absence
            // this file proves by never mentioning one, rather than by inspecting the class.
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId("00000000033", "01", "0001");

            assertThat(key.getTrancatAcctId()).isEqualTo("00000000033");
            assertThat(key.getTrancatTypeCd()).isEqualTo("01");
            assertThat(key.getTrancatCd()).isEqualTo("0001");

            // Offset 0 is what makes the business key and the record share a starting position, and
            // both independent splits of the record agree on where the key ends.
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
            // Asserted as plain integers, and the 6-byte key's own class is never named or
            // instantiated anywhere in this file. That is the whole proof of the absence: there is no
            // type to name in common, so none is named.
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isEqualTo(17);
            assertThat(CATEGORY_KEY_LENGTH).isEqualTo(6);

            // The two width sums differ by exactly the leading account identifier this key carries and
            // the other does not, so neither key is a prefix or a fragment of the other.
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
            // No application context is started, no persistence unit is opened, no database is reached
            // and no container is launched anywhere in this test class. Everything below runs on the
            // plain JVM the test harness already provides, which is the whole of the behavioural proof
            // that this type is annotation-free and dependency-free. Column names, column widths,
            // nullability and every other mapping concern belong to the entity and to the schema
            // validation performed in the integration tier, and none of them is asserted here.
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

            // The expected value is typed out here rather than assembled, and it is derived from the
            // single concatenated return the class under test declares, which makes the format
            // unambiguous. Both the ordering of the three components and their surviving leading zeros
            // are visible in it.
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
