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
package com.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.domain.id.TransactionCategoryId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TransactionCategory}, the 60-byte transaction-category reference row, and for
 * its two-part 6-byte composite key {@link TransactionCategoryId}.
 *
 * <p><strong>Provenance.</strong> Legacy checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19). That stamp is recorded here
 * as documentation only and is never asserted against a source member, because it is not universal
 * across the estate.
 *
 * <p><strong>Independent oracle.</strong> Every expected value in this suite was hand-derived from the
 * legacy copybook {@code CVTRA04Y}, from the {@code TRANCATG} cluster definition, and from the seeded
 * reference fixture, then written here as a literal constant. No expectation is computed by calling a
 * method on the classes under test, nothing is snapshotted from their output, and no assertion compares
 * a value with itself. The hand-derived geometry is:
 *
 * <table border="1">
 *   <caption>Verified layout of the 60-byte transaction-category record</caption>
 *   <tr><th>Field</th><th>Zero-based offset</th><th>Width</th></tr>
 *   <tr><td>key group (2 components)</td><td>0</td><td>6</td></tr>
 *   <tr><td>transaction type code</td><td>0</td><td>2</td></tr>
 *   <tr><td>transaction category code</td><td>2</td><td>4</td></tr>
 *   <tr><td>category description</td><td>6</td><td>50</td></tr>
 *   <tr><td>trailing filler, unmapped</td><td>56</td><td>4</td></tr>
 * </table>
 *
 * <p>2 + 4 + 50 + 4 = 60, and the cluster definition corroborates the key independently by declaring a
 * key length of 6 at offset 0 on a 60-byte record.
 *
 * <p><strong>Name collision one: the key group name is overloaded in the legacy source.</strong> The
 * copybook behind this entity names its key group with the same COBOL group name used by the separate
 * transaction-category-balance copybook, yet the two keys are structurally unrelated. This key has two
 * components totalling 6 bytes at offsets 0 and 2. The other has three components totalling 17 bytes at
 * offsets 0, 11 and 13, leading with an 11-digit account identifier this key does not contain at all, so
 * the 6-byte key is not a prefix, sub-key or reusable fragment of the 17-byte one and the two align at
 * no offset. This suite asserts the 6-byte sum and asserts that 6 is not 17. The balance key class is
 * deliberately never imported or referenced here; the two key classes share no supertype beyond
 * {@link Object} and none may be introduced.
 *
 * <p><strong>Name collision two: two different 60-byte layouts coexist.</strong> The transaction-type
 * reference record is also 60 bytes, but it is 2 + 50 + 8 with its description at offset 2 and 8 filler
 * bytes, against this record's 2 + 4 + 50 + 4 with its description at offset 6 and 4 filler bytes. A
 * record length therefore cannot distinguish the two, and the filler widths differ by only four bytes.
 * Accordingly this suite never assembles or compares a whole 60-byte record image and never infers a
 * layout from content: record-image assembly and offset slicing belong to the fixed-width record mapper
 * in the utility layer, and importing that mapper here would make it the oracle for its own layout.
 *
 * <p><strong>Also distinct from the transaction-type key.</strong> The first key component of this
 * entity carries the legacy {@code -CD} suffix; the transaction-type table's single-column key does not.
 * The two are not interchangeable.
 *
 * <p><strong>Scope.</strong> A pure unit test. It starts no application context, opens no database or
 * network connection, reads no file, and uses no container, no mocking and no reflection. Column names,
 * declared lengths and nullability are deliberately not verified here: that mapping layer is verified in
 * the integration tier against a real database by schema validation at startup, which also proves the
 * identifier-class-to-entity field name and type correspondence.
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 */
@DisplayName("TransactionCategory and its 6-byte two-part composite key")
class TransactionCategoryTest {

    // ------------------------------------------------------------------------------------------
    // Hand-derived layout constants. These ARE the oracle: each was read off the copybook and the
    // cluster definition by hand, never produced by the code under test.
    // ------------------------------------------------------------------------------------------

    /** Width of the transaction type code, key component 1, at offset 0. */
    private static final int TYPE_CD_WIDTH = 2;

    /** Width of the transaction category code, key component 2, at offset 2. */
    private static final int CAT_CD_WIDTH = 4;

    /** Width of the category description at offset 6. */
    private static final int DESC_WIDTH = 50;

    /** Declared key length of this record's key group, corroborated by the cluster definition. */
    private static final int KEY_WIDTH = 6;

    /**
     * Declared key length of the <em>other</em> legacy key group that shares this one's COBOL group
     * name: three components, 11 + 2 + 4, on the transaction-category-balance record. Held here only so
     * the suite can assert the two lengths differ. The balance key class itself is never referenced.
     */
    private static final int OTHER_SAME_NAMED_KEY_WIDTH = 17;

    /** Seeded row taken from the reference fixture: transaction type code of the first record. */
    private static final String ROW_A_TYPE_CD = "01";

    /** Seeded row taken from the reference fixture: category code of the first record. */
    private static final String ROW_A_CAT_CD = "0001";

    /** Seeded row taken from the reference fixture: description text of the first record, 19 characters. */
    private static final String ROW_A_DESC = "Regular Sales Draft";

    /** Seeded row taken from the reference fixture: transaction type code of the last record. */
    private static final String ROW_B_TYPE_CD = "07";

    /** Seeded row taken from the reference fixture: category code of the last record. */
    private static final String ROW_B_CAT_CD = "0001";

    /**
     * Seeded row taken from the reference fixture: description text of the last record, exactly 29
     * characters and deliberately mixed case, with lower-case letters opening its second, third and
     * fourth words. It is reproduced verbatim and never title-cased.
     *
     * <p>Context only, asserted nowhere in this suite: 29 is also the width the downstream report
     * formatter truncates a description to, which makes this particular value sit exactly on that
     * boundary. Truncation is the report formatter's concern, so nothing here truncates anything.
     */
    private static final String ROW_B_DESC = "Sales draft credit adjustment";

    /**
     * The first seeded description at its full external width: 19 characters of text followed by 31
     * spaces, which is 50. The arithmetic is hand-derived and is self-checked by an assertion on the
     * encoded byte length of this very constant.
     */
    private static final String ROW_A_DESC_PADDED = ROW_A_DESC + " ".repeat(31);

    /**
     * The last seeded description at its full external width: 29 characters of text followed by 21
     * spaces, which is 50.
     */
    private static final String ROW_B_DESC_PADDED = ROW_B_DESC + " ".repeat(21);

    /**
     * A title-cased rewriting of the last seeded description, present purely as a negative expectation.
     * The stored value must never fold to this.
     */
    private static final String ROW_B_DESC_TITLE_CASED = "Sales Draft Credit Adjustment";

    /**
     * The zero-suppressed rendering of a four-digit category code. The legacy field is left zero-filled
     * and is mapped to a bounded character column rather than a numeric one, so this value must never be
     * interchangeable with the zero-filled form.
     */
    private static final String CAT_CD_ZERO_SUPPRESSED = "1";

    /** The version identifier the key class declares for serialization. */
    private static final long DECLARED_SERIAL_VERSION_UID = 1L;

    /**
     * Reaches the key class's {@code protected} no-argument constructor the way the persistence provider
     * does, using nothing but plain Java inheritance.
     *
     * <p><strong>Why this exists.</strong> An identifier class must offer a no-argument constructor for
     * the provider to instantiate before it populates the components, which is precisely why the key is
     * a plain class and not a compact data carrier - a record cannot supply one. That constructor is
     * declared {@code protected} and the key class lives in a different package from this test, so a
     * direct {@code new} of it does not compile here. This is a documented divergence from the contract
     * summary this suite was written against, which assumed the constructor would be reachable
     * directly; the constructor as actually written is protected, and the code compiled against is the
     * code as written.
     *
     * <p>A subclass may invoke a protected superclass constructor through {@code super()} across package
     * boundaries, so the {@code super()} call below both proves at compile time that the no-argument
     * constructor exists and executes it at run time. No reflection of any kind is involved: there is no
     * reflective member access and no dynamic class lookup by name anywhere in this file. The class is {@code final} so that no constructor here can leak a partly
     * built instance, and it declares its own serialization version identifier because it inherits
     * serializability and the build compiles with all lint categories promoted to errors.
     *
     * <p>To be explicit about what is deliberately absent: this file performs no reflective member
     * access, no reflective instantiation and no dynamic class lookup by name anywhere. The
     * introspection package is never imported and the module's zero budget for low-level introspection
     * is therefore untouched by this suite.
     */
    private static final class ProviderInstantiatedKey extends TransactionCategoryId {

        /** Explicit version identifier; inherited serializability would otherwise raise a warning. */
        private static final long serialVersionUID = 1L;

        /** Invokes the protected no-argument constructor of the key class under test. */
        ProviderInstantiatedKey() {
            super();
        }
    }

    @Nested
    @DisplayName("Record geometry: the key is 2 + 4 bytes at offset 0 of a 60-byte record")
    class RecordGeometry {

        @Test
        @DisplayName("the two key component widths sum to the declared key length of 6, which the "
                + "cluster definition corroborates by declaring a key length of 6 at offset 0")
        void keyComponentWidthsSumToSix() {
            assertThat(TYPE_CD_WIDTH + CAT_CD_WIDTH).isEqualTo(KEY_WIDTH);
            assertThat(KEY_WIDTH).isEqualTo(6);
        }

        @Test
        @DisplayName("the 6-byte key is not the 17-byte key that shares its COBOL group name, so the "
                + "two same-named legacy key groups are never conflated")
        void sixByteKeyIsNotTheSeventeenByteKeyOfTheSameName() {
            // COLLISION ONE. The other group of the same name has three components, 11 + 2 + 4 = 17,
            // and leads with an 11-digit account identifier absent from this key, so it cannot be a
            // prefix of, or aligned with, this one at any offset. Only the two lengths are compared;
            // the other key's class is deliberately not referenced anywhere in this file.
            assertThat(KEY_WIDTH).isNotEqualTo(OTHER_SAME_NAMED_KEY_WIDTH);
            assertThat(OTHER_SAME_NAMED_KEY_WIDTH).isEqualTo(11 + TYPE_CD_WIDTH + CAT_CD_WIDTH);
            assertThat(KEY_WIDTH).isLessThan(OTHER_SAME_NAMED_KEY_WIDTH);
        }

        @Test
        @DisplayName("the hand-derived padded descriptions are exactly 50 encoded bytes, which "
                + "self-checks the oracle's own padding arithmetic")
        void handDerivedPaddedDescriptionsAreFiftyBytes() {
            assertThat(ROW_A_DESC_PADDED.getBytes(StandardCharsets.US_ASCII)).hasSize(DESC_WIDTH);
            assertThat(ROW_B_DESC_PADDED.getBytes(StandardCharsets.US_ASCII)).hasSize(DESC_WIDTH);
        }

        @Test
        @DisplayName("the last seeded description is exactly 29 encoded bytes before padding, the "
                + "boundary value the estate happens to seed")
        void theLastSeededDescriptionIsTwentyNineBytes() {
            assertThat(ROW_B_DESC.getBytes(StandardCharsets.US_ASCII)).hasSize(29);
            assertThat(ROW_A_DESC.getBytes(StandardCharsets.US_ASCII)).hasSize(19);
        }
    }

    @Nested
    @DisplayName("Entity construction and access: three attributes in copybook order")
    class EntityConstructionAndAccess {

        @Test
        @DisplayName("the all-args constructor takes the two key components in key order then the "
                + "description, and every argument reaches its own accessor")
        void allArgsConstructorRoundTripsAllThreeAttributes() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(row.getTranTypeCd()).isEqualTo(ROW_A_TYPE_CD);
            assertThat(row.getTranCatCd()).isEqualTo(ROW_A_CAT_CD);
            assertThat(row.getTranCatTypeDesc()).isEqualTo(ROW_A_DESC_PADDED);
        }

        @Test
        @DisplayName("the two key components do not swap, which a 2-byte and a 4-byte value make "
                + "detectable in a positional constructor call")
        void theKeyComponentsDoNotSwapPositions() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            // Differing widths are what make a transposition visible at all: a 2-byte value cannot
            // silently occupy the 4-byte slot.
            assertThat(row.getTranTypeCd()).hasSize(TYPE_CD_WIDTH);
            assertThat(row.getTranCatCd()).hasSize(CAT_CD_WIDTH);
            assertThat(row.getTranTypeCd()).isNotEqualTo(row.getTranCatCd());
        }

        @Test
        @DisplayName("all three setters replace the attribute each names and store the value verbatim, "
                + "because the copybook fields are plain character data with no normalisation")
        void allThreeSettersRoundTrip() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            row.setTranTypeCd(ROW_B_TYPE_CD);
            row.setTranCatCd(ROW_B_CAT_CD);
            row.setTranCatTypeDesc(ROW_B_DESC_PADDED);

            assertThat(row.getTranTypeCd()).isEqualTo(ROW_B_TYPE_CD);
            assertThat(row.getTranCatCd()).isEqualTo(ROW_B_CAT_CD);
            assertThat(row.getTranCatTypeDesc()).isEqualTo(ROW_B_DESC_PADDED);
        }

        @Test
        @DisplayName("the persistence provider's no-arg constructor yields a row with all three "
                + "attributes absent, ready to be populated after instantiation")
        void theNoArgConstructorYieldsAnAllNullRow() {
            // The entity's no-argument constructor is declared protected, and this test class sits in
            // the SAME package as the entity, so plain Java package access reaches it directly.
            // This is same-package visibility and explicitly NOT reflection: no introspection type,
            // no dynamic class lookup by name and no reflective member access appears in this file.
            final TransactionCategory row = new TransactionCategory();

            assertThat(row.getTranTypeCd()).isNull();
            assertThat(row.getTranCatCd()).isNull();
            assertThat(row.getTranCatTypeDesc()).isNull();
        }

        @Test
        @DisplayName("both seeded reference rows round-trip, including the pair that shares a category "
                + "code across two different type codes")
        void bothSeededRowsRoundTrip() {
            final TransactionCategory first =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final TransactionCategory last =
                    new TransactionCategory(ROW_B_TYPE_CD, ROW_B_CAT_CD, ROW_B_DESC_PADDED);

            assertThat(first.getTranTypeCd()).isEqualTo("01");
            assertThat(first.getTranCatCd()).isEqualTo("0001");
            assertThat(first.getTranCatTypeDesc()).isEqualTo(ROW_A_DESC_PADDED);

            assertThat(last.getTranTypeCd()).isEqualTo("07");
            assertThat(last.getTranCatCd()).isEqualTo("0001");
            assertThat(last.getTranCatTypeDesc()).isEqualTo(ROW_B_DESC_PADDED);

            // The same category code under two different type codes describes two different things,
            // which is exactly why the key needs both components.
            assertThat(first.getTranCatCd()).isEqualTo(last.getTranCatCd());
            assertThat(first.getTranTypeCd()).isNotEqualTo(last.getTranTypeCd());
            assertThat(first.getTranCatTypeDesc()).isNotEqualTo(last.getTranCatTypeDesc());
        }

        @Test
        @DisplayName("every attribute may be set back to absent, because the entity validates nothing "
                + "and the copybook imposes no presence rule on a character field")
        void everyAttributeMayBeSetBackToNull() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            row.setTranTypeCd(null);
            row.setTranCatCd(null);
            row.setTranCatTypeDesc(null);

            assertThat(row.getTranTypeCd()).isNull();
            assertThat(row.getTranCatCd()).isNull();
            assertThat(row.getTranCatTypeDesc()).isNull();
        }
    }

    @Nested
    @DisplayName("Fixed-width carriage: 2 / 4 / 50 encoded bytes, stored exactly as supplied")
    class EntityFixedWidthCarriage {

        @Test
        @DisplayName("the three attributes carry exactly 2, 4 and 50 encoded bytes, measured on the "
                + "encoded form rather than on a character count")
        void theThreeAttributesCarryTheirDeclaredByteWidths() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            // Byte widths, not character counts: the legacy record is a fixed-width byte image, so the
            // encoded length is the quantity the layout actually constrains.
            assertThat(row.getTranTypeCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TYPE_CD_WIDTH);
            assertThat(row.getTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CAT_CD_WIDTH);
            assertThat(row.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(DESC_WIDTH);
        }

        @Test
        @DisplayName("the 4-digit category code keeps its leading zeros, because the zero-filled "
                + "legacy field is mapped to a bounded character column and never to a numeric type")
        void theCategoryCodeKeepsItsLeadingZeros() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(row.getTranCatCd()).isEqualTo("0001");
            assertThat(row.getTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CAT_CD_WIDTH);

            // Were the code stored as a number, "0001" would come back rendering as 1, the 6-byte key
            // image would no longer reconstruct from its two components, and the composite lookup
            // would miss. So 1 must never stand where 0001 belongs.
            assertThat(row.getTranCatCd()).isNotEqualTo(CAT_CD_ZERO_SUPPRESSED);
        }

        @Test
        @DisplayName("the 2-byte type code keeps its own leading zero for the same reason")
        void theTypeCodeKeepsItsLeadingZero() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(row.getTranTypeCd()).isEqualTo("01");
            assertThat(row.getTranTypeCd()).isNotEqualTo(CAT_CD_ZERO_SUPPRESSED);
        }

        @Test
        @DisplayName("a mixed-case description survives verbatim and is never folded to title case, "
                + "because the seeded text is stored exactly as the estate holds it")
        void aMixedCaseDescriptionSurvivesVerbatim() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_B_TYPE_CD, ROW_B_CAT_CD, ROW_B_DESC);

            assertThat(row.getTranCatTypeDesc()).isEqualTo(ROW_B_DESC);
            assertThat(row.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(ROW_B_DESC.getBytes(StandardCharsets.US_ASCII));
            assertThat(row.getTranCatTypeDesc()).isNotEqualTo(ROW_B_DESC_TITLE_CASED);
        }

        @Test
        @DisplayName("trailing space padding to the 50-byte external width survives, because trimming "
                + "would destroy padding the fixed-width output contract depends on")
        void trailingSpacePaddingSurvives() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_B_TYPE_CD, ROW_B_CAT_CD, ROW_B_DESC_PADDED);

            assertThat(row.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(ROW_B_DESC_PADDED.getBytes(StandardCharsets.US_ASCII));
            assertThat(row.getTranCatTypeDesc()).isEqualTo(ROW_B_DESC_PADDED);

            // A trimming, stripping or normalising mutator would collapse the padded form onto the
            // unpadded one. It must not.
            assertThat(row.getTranCatTypeDesc()).isNotEqualTo(ROW_B_DESC);
            assertThat(row.getTranCatTypeDesc()).hasSize(DESC_WIDTH);
        }

        @Test
        @DisplayName("a description supplied through the setter is padded no more and trimmed no less "
                + "than one supplied through the constructor")
        void theSetterAppliesNoPaddingAndNoTrimming() {
            final TransactionCategory row = new TransactionCategory();

            row.setTranCatTypeDesc(ROW_A_DESC);
            assertThat(row.getTranCatTypeDesc()).isEqualTo(ROW_A_DESC);
            assertThat(row.getTranCatTypeDesc()).hasSize(19);

            row.setTranCatTypeDesc(ROW_A_DESC_PADDED);
            assertThat(row.getTranCatTypeDesc()).isEqualTo(ROW_A_DESC_PADDED);
            assertThat(row.getTranCatTypeDesc()).hasSize(DESC_WIDTH);
        }
    }

    @Nested
    @DisplayName("Entity identity: the composite key alone decides equality")
    class EntityIdentity {

        @Test
        @DisplayName("two rows sharing both key components are equal and hash alike even when their "
                + "descriptions differ, because only the key group identifies a record")
        void sameKeyDifferentDescriptionMeansEqual() {
            final TransactionCategory one =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final TransactionCategory other =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_B_DESC_PADDED);

            assertThat(one.getTranCatTypeDesc()).isNotEqualTo(other.getTranCatTypeDesc());
            assertThat(one).isEqualTo(other);
            assertThat(one).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a differing type code alone breaks equality, proving key component 1 "
                + "participates independently")
        void aDifferingTypeCodeBreaksEquality() {
            final TransactionCategory one =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final TransactionCategory other =
                    new TransactionCategory(ROW_B_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(one).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a differing category code alone breaks equality, proving key component 2 "
                + "participates independently")
        void aDifferingCategoryCodeBreaksEquality() {
            final TransactionCategory one =
                    new TransactionCategory(ROW_A_TYPE_CD, "0001", ROW_A_DESC_PADDED);
            final TransactionCategory other =
                    new TransactionCategory(ROW_A_TYPE_CD, "0005", ROW_A_DESC_PADDED);

            assertThat(one).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a zero-suppressed category code does not identify the zero-filled row, so the "
                + "leading zeros are significant to identity and not only to storage")
        void aZeroSuppressedCategoryCodeIsADifferentRow() {
            final TransactionCategory zeroFilled =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final TransactionCategory zeroSuppressed =
                    new TransactionCategory(ROW_A_TYPE_CD, CAT_CD_ZERO_SUPPRESSED, ROW_A_DESC_PADDED);

            assertThat(zeroFilled).isNotEqualTo(zeroSuppressed);
        }

        @Test
        @DisplayName("a row equals itself, exercising the identity short-circuit of the equality test")
        void aRowEqualsItself() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(row.equals(row)).isTrue();
            assertThat(row).hasSameHashCodeAs(row);
        }

        @Test
        @DisplayName("a row is unequal to an absent reference and to a foreign type, so neither an "
                + "absent value nor an unrelated object can ever be mistaken for a category row")
        void aRowIsUnequalToNullAndToAForeignType() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(row.equals(null)).isFalse();
            assertThat(row.equals("010001")).isFalse();
        }

        @Test
        @DisplayName("equality is symmetric and transitive across independently built rows carrying "
                + "the same key")
        void equalityIsSymmetricAndTransitive() {
            final TransactionCategory one =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final TransactionCategory two =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final TransactionCategory three =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_B_DESC_PADDED);

            assertThat(one).isEqualTo(two);
            assertThat(two).isEqualTo(one);
            assertThat(two).isEqualTo(three);
            assertThat(one).isEqualTo(three);
        }

        @Test
        @DisplayName("two separate unpopulated rows are equal, because an absent key equals an absent "
                + "key and the comparison is null-safe on both components")
        void twoUnpopulatedRowsAreEqual() {
            // Two DISTINCT instances, bound to named locals so it is unmistakable that this compares
            // two separate objects rather than an expression with itself. The claim is falsifiable: an
            // equality test using reference comparison on the components, or one that dereferenced them
            // without a null guard, would fail here.
            final TransactionCategory oneEmpty = new TransactionCategory();
            final TransactionCategory anotherEmpty = new TransactionCategory();

            assertThat(oneEmpty).isNotSameAs(anotherEmpty);
            assertThat(oneEmpty).isEqualTo(anotherEmpty);
            assertThat(oneEmpty).hasSameHashCodeAs(anotherEmpty);
            assertThat(oneEmpty).isNotEqualTo(
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED));
        }

        @Test
        @DisplayName("no surrogate identifier exists: identity is the legacy business key itself, so "
                + "two independently constructed rows carrying the same key are the same row")
        void noSurrogateIdentifierExists() {
            // Documenting test. The absence of a surrogate is proved by COMPILE-TIME ABSENCE: this file
            // never references a generated-identifier accessor of any kind, because none is declared -
            // such a call would not compile. No reflection is used to look for one.
            //
            // Behaviourally, a machine-assigned identifier participating in identity would make two
            // separately constructed rows unequal even when their business key matched. They are equal,
            // so nothing beyond the two key components takes part. Keeping the business key as the
            // identifier is what preserves the record-image-to-row correspondence that byte-parity
            // verification of the migrated output depends on.
            final TransactionCategory one =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final TransactionCategory other =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(one).isNotSameAs(other);
            assertThat(one).isEqualTo(other);
            assertThat(one).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a row stays findable in a hash map after its description has been mutated, "
                + "because the mutable description is excluded from the hash")
        void aRowStaysFindableAfterItsDescriptionChanges() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);
            final Map<TransactionCategory, String> index = new HashMap<>();
            index.put(row, ROW_A_DESC);

            row.setTranCatTypeDesc(ROW_B_DESC_PADDED);

            assertThat(index).containsKey(row);
            assertThat(index.get(row)).isEqualTo(ROW_A_DESC);
        }
    }

    @Nested
    @DisplayName("Key projection: the row hands out its own 2-component key")
    class ExtractedCompositeKey {

        @Test
        @DisplayName("the projected key carries the two components in key order and carries no "
                + "description, because the description is not part of the key group")
        void theProjectedKeyCarriesBothComponentsInKeyOrder() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            final TransactionCategoryId key = row.toId();

            // Compared against hand-derived literals, never against a value the row computed.
            assertThat(key.getTranTypeCd()).isEqualTo("01");
            assertThat(key.getTranCatCd()).isEqualTo("0001");
            assertThat(key.getTranTypeCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TYPE_CD_WIDTH);
            assertThat(key.getTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CAT_CD_WIDTH);
        }

        @Test
        @DisplayName("the projected key equals one assembled by hand from the same two literals, and "
                + "does not equal one whose components are transposed")
        void theProjectedKeyEqualsAHandAssembledKey() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            assertThat(row.toId()).isEqualTo(new TransactionCategoryId("01", "0001"));
            assertThat(row.toId()).isNotEqualTo(new TransactionCategoryId("0001", "01"));
        }

        @Test
        @DisplayName("a fresh key is produced on every call, so no instance is cached and no mutable "
                + "static state backs the projection")
        void aFreshKeyIsProducedOnEveryCall() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            final TransactionCategoryId first = row.toId();
            final TransactionCategoryId second = row.toId();

            assertThat(first).isNotSameAs(second);
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("the projection follows a key component replaced after construction")
        void theProjectionFollowsAMutatedKeyComponent() {
            final TransactionCategory row =
                    new TransactionCategory(ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED);

            row.setTranTypeCd(ROW_B_TYPE_CD);

            assertThat(row.toId().getTranTypeCd()).isEqualTo("07");
            assertThat(row.toId().getTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("an unpopulated row projects a key with both components absent")
        void anUnpopulatedRowProjectsAnAbsentKey() {
            final TransactionCategoryId key = new TransactionCategory().toId();

            assertThat(key.getTranTypeCd()).isNull();
            assertThat(key.getTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering of the row and of its key")
    class DiagnosticRendering {

        @Test
        @DisplayName("the row's diagnostic rendering reveals all three attributes, none of which is a "
                + "credential or a monetary value needing redaction")
        void theRowRenderingRevealsAllThreeAttributes() {
            // DIVERGENCE, noted per the read-the-production-class mandate: the contract summary this
            // suite was written against said a diagnostic rendering might be absent and should not be
            // asserted on. Both classes do in fact declare one, so it is exercised here for coverage of
            // every public member - but deliberately without pinning the exact format. Only the
            // presence of the hand-derived values is asserted, so a future wording change cannot make
            // this brittle.
            final String rendered = new TransactionCategory(
                    ROW_A_TYPE_CD, ROW_A_CAT_CD, ROW_A_DESC_PADDED).toString();

            assertThat(rendered).isNotNull();
            assertThat(rendered).contains("01");
            assertThat(rendered).contains("0001");
            assertThat(rendered).contains(ROW_A_DESC);
        }

        @Test
        @DisplayName("the key's diagnostic rendering reveals both key components and nothing that "
                + "requires redaction")
        void theKeyRenderingRevealsBothComponents() {
            final String rendered = new TransactionCategoryId(ROW_B_TYPE_CD, ROW_B_CAT_CD).toString();

            assertThat(rendered).isNotNull();
            assertThat(rendered).contains("07");
            assertThat(rendered).contains("0001");
        }

        @Test
        @DisplayName("an unpopulated row and an unpopulated key both render without failing")
        void unpopulatedInstancesRenderWithoutFailing() {
            assertThat(new TransactionCategory().toString()).isNotNull();
            assertThat(new ProviderInstantiatedKey().toString()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Composite key contract: two text components, 2 + 4, compared exactly")
    class CompositeKeyContract {

        @Test
        @DisplayName("the all-args constructor binds the 2-byte type code then the 4-byte category "
                + "code, in the key order the cluster definition fixes")
        void allArgsConstructorRoundTripsBothComponents() {
            final TransactionCategoryId key =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);

            assertThat(key.getTranTypeCd()).isEqualTo("01");
            assertThat(key.getTranCatCd()).isEqualTo("0001");
            assertThat(key.getTranTypeCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TYPE_CD_WIDTH);
            assertThat(key.getTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CAT_CD_WIDTH);
        }

        @Test
        @DisplayName("the no-arg constructor the identifier-class contract mandates exists and leaves "
                + "both components absent")
        void theNoArgConstructorExistsAndYieldsAbsentComponents() {
            // An identifier class must offer a no-argument constructor so the persistence provider can
            // instantiate it before populating the components. That requirement is exactly why this key
            // is a plain class and NOT a record: a record cannot declare a no-argument constructor
            // alongside its components.
            //
            // The constructor is protected and the key class lives in a neighbouring package, so it
            // cannot be invoked directly from here - a direct call does not compile. It is reached
            // instead through a subclass's super() call, which is plain Java inheritance and NOT
            // reflection. See ProviderInstantiatedKey for the full rationale.
            final TransactionCategoryId key = new ProviderInstantiatedKey();

            assertThat(key.getTranTypeCd()).isNull();
            assertThat(key.getTranCatCd()).isNull();
        }

        @Test
        @DisplayName("two independently built keys carrying the same components are equal and hash "
                + "alike, so both components take part in equality")
        void equalKeysAreEqualAndHashAlike() {
            final TransactionCategoryId one =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);
            final TransactionCategoryId other =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);

            assertThat(one).isNotSameAs(other);
            assertThat(one).isEqualTo(other);
            assertThat(other).isEqualTo(one);
            assertThat(one).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a key equals itself, exercising the identity short-circuit of the equality test")
        void aKeyEqualsItself() {
            final TransactionCategoryId key =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);

            assertThat(key.equals(key)).isTrue();
        }

        @Test
        @DisplayName("the type code component participates in equality independently of the category "
                + "code")
        void theTypeCodeComponentParticipatesIndependently() {
            final TransactionCategoryId one =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);
            final TransactionCategoryId other =
                    new TransactionCategoryId(ROW_B_TYPE_CD, ROW_A_CAT_CD);

            // The category component is pinned to the hand-derived literal on BOTH keys first, so the
            // held-constant precondition rests on the literal rather than on a comparison of two
            // production calls, and only the type code can account for the inequality below.
            assertThat(one.getTranCatCd()).isEqualTo("0001");
            assertThat(other.getTranCatCd()).isEqualTo("0001");
            assertThat(one.getTranTypeCd()).isEqualTo("01");
            assertThat(other.getTranTypeCd()).isEqualTo("07");
            assertThat(one).isNotEqualTo(other);
        }

        @Test
        @DisplayName("the category code component participates in equality independently of the type "
                + "code")
        void theCategoryCodeComponentParticipatesIndependently() {
            final TransactionCategoryId one = new TransactionCategoryId(ROW_A_TYPE_CD, "0001");
            final TransactionCategoryId other = new TransactionCategoryId(ROW_A_TYPE_CD, "0005");

            // Same pinning discipline: the type component is fixed to its literal on both keys, so only
            // the category code can account for the inequality. Both category values are seeded ones -
            // 0001 opens the reference file and 0005 is the interest category.
            assertThat(one.getTranTypeCd()).isEqualTo("01");
            assertThat(other.getTranTypeCd()).isEqualTo("01");
            assertThat(one.getTranCatCd()).isEqualTo("0001");
            assertThat(other.getTranCatCd()).isEqualTo("0005");
            assertThat(one).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a key is unequal to an absent reference and to a foreign type, including the "
                + "6-character string that spells the very key image it stands for")
        void aKeyIsUnequalToNullAndToAForeignType() {
            final TransactionCategoryId key =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);

            assertThat(key.equals(null)).isFalse();
            assertThat(key.equals("010001")).isFalse();
        }

        @Test
        @DisplayName("leading zeros are significant in the key: the zero-filled category code is a "
                + "different key from its zero-suppressed rendering")
        void leadingZerosAreSignificantInTheKey() {
            final TransactionCategoryId zeroFilled =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);
            final TransactionCategoryId zeroSuppressed =
                    new TransactionCategoryId(ROW_A_TYPE_CD, CAT_CD_ZERO_SUPPRESSED);

            assertThat(zeroFilled).isNotEqualTo(zeroSuppressed);
            assertThat(zeroSuppressed).isNotEqualTo(zeroFilled);
        }

        @Test
        @DisplayName("the zero-filled and zero-suppressed category codes are not interchangeable as "
                + "map keys, so a lookup on one can never resolve to the other")
        void theTwoRenderingsAreNotInterchangeableAsMapKeys() {
            final TransactionCategoryId zeroFilled =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);
            final TransactionCategoryId zeroSuppressed =
                    new TransactionCategoryId(ROW_A_TYPE_CD, CAT_CD_ZERO_SUPPRESSED);

            final Map<TransactionCategoryId, String> index = new HashMap<>();
            index.put(zeroFilled, ROW_A_CAT_CD);
            index.put(zeroSuppressed, CAT_CD_ZERO_SUPPRESSED);

            // Two distinct keys occupy two distinct slots. Note that unequal keys are PERMITTED, though
            // never required, to produce different hash codes, so this suite asserts the map-level
            // consequence - which holds however the two happen to hash - rather than pinning hash
            // values that carry no contractual guarantee.
            assertThat(index).hasSize(2);
            assertThat(index.get(zeroFilled)).isEqualTo("0001");
            assertThat(index.get(zeroSuppressed)).isEqualTo("1");
        }

        @Test
        @DisplayName("no normalisation is applied anywhere in the key: a component carrying trailing "
                + "spaces is not equal to the same component without them")
        void noNormalisationIsAppliedInsideTheKey() {
            // Proves the constructor, the equality test and the hash all leave a value exactly as
            // supplied - no trimming, stripping or trailing-space removal of any kind. Widths are
            // enforced by the schema, never by this class, which is why an over-wide value is simply
            // carried verbatim here.
            final TransactionCategoryId spaced = new TransactionCategoryId("01 ", ROW_A_CAT_CD);
            final TransactionCategoryId unspaced =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);

            assertThat(spaced).isNotEqualTo(unspaced);
            assertThat(spaced.getTranTypeCd()).isEqualTo("01 ");
            assertThat(spaced.getTranTypeCd()).hasSize(3);

            final TransactionCategoryId spacedCategory =
                    new TransactionCategoryId(ROW_A_TYPE_CD, "0001 ");

            assertThat(spacedCategory).isNotEqualTo(unspaced);
            assertThat(spacedCategory.getTranCatCd()).isEqualTo("0001 ");
        }

        @Test
        @DisplayName("both components are stored verbatim, absent values included, because the key "
                + "validates nothing")
        void absentComponentsAreStoredAndReturnedUnchanged() {
            final TransactionCategoryId noType = new TransactionCategoryId(null, ROW_A_CAT_CD);
            final TransactionCategoryId noCategory = new TransactionCategoryId(ROW_A_TYPE_CD, null);

            assertThat(noType.getTranTypeCd()).isNull();
            assertThat(noType.getTranCatCd()).isEqualTo("0001");
            assertThat(noCategory.getTranTypeCd()).isEqualTo("01");
            assertThat(noCategory.getTranCatCd()).isNull();
            assertThat(noType).isNotEqualTo(noCategory);
        }
    }

    @Nested
    @DisplayName("Composite key serialization: an identifier class must be serializable")
    class CompositeKeySerialization {

        @Test
        @DisplayName("the key declares an explicit, stable serialization version identifier of 1")
        void theKeyDeclaresAnExplicitSerialVersionUid() {
            // java.io.ObjectStreamClass is the sanctioned serialization-metadata API and is NOT
            // reflection: it does not belong to the introspection package, it performs no member
            // lookup on behalf of the caller, and using it therefore leaves the module's
            // zero-reflection position untouched. The expected value is the literal the key class
            // declares, read by hand.
            final ObjectStreamClass descriptor = ObjectStreamClass.lookup(TransactionCategoryId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(DECLARED_SERIAL_VERSION_UID);
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a key survives a full serialization round trip and deserializes to an equal key, "
                + "proving serializability is honoured end to end")
        void aKeySurvivesASerializationRoundTrip() throws IOException, ClassNotFoundException {
            final TransactionCategoryId original =
                    new TransactionCategoryId(ROW_A_TYPE_CD, ROW_A_CAT_CD);

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(original);
            }

            final TransactionCategoryId restored;
            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                restored = (TransactionCategoryId) in.readObject();
            }

            assertThat(restored).isNotSameAs(original);
            assertThat(restored).isEqualTo(original);
            assertThat(restored).hasSameHashCodeAs(original);
            assertThat(restored.getTranTypeCd()).isEqualTo("01");
            assertThat(restored.getTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("a round trip preserves the leading zeros of the 4-digit category component, so "
                + "the 6-byte key image still reconstructs after transport")
        void aRoundTripPreservesLeadingZeros() throws IOException, ClassNotFoundException {
            final TransactionCategoryId original = new TransactionCategoryId(ROW_B_TYPE_CD, "0005");

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(original);
            }

            final TransactionCategoryId restored;
            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                restored = (TransactionCategoryId) in.readObject();
            }

            assertThat(restored.getTranTypeCd()).isEqualTo("07");
            assertThat(restored.getTranCatCd()).isEqualTo("0005");
            assertThat(restored.getTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CAT_CD_WIDTH);
            assertThat(restored.getTranCatCd()).isNotEqualTo("5");
        }
    }
}
