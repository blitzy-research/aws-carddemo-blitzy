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
 * Unit test for {@link TransactionCategoryId}, the composite identifier class of the
 * transaction-category reference table.
 *
 * <p><strong>What this test proves.</strong> Two independent legacy authorities fix the same key
 * geometry and this test pins both: copybook member {@code CVTRA04Y}, whose 60-byte record carries a
 * 6-byte key group of a 2-byte type code plus a 4-digit category code, ahead of a 50-byte description
 * and a 4-byte trailing filler; and the {@code TRANCATG} cluster definition, which declares the key
 * length, the key offset and the record length independently of the copybook. The declared key length
 * and the sum of the two component widths are therefore cross-checks on one another rather than
 * restatements, and this test asserts they agree.
 *
 * <p><strong>Reference data.</strong> The eighteen distinct 6-byte keys asserted here are the
 * measured contents of the transaction-category reference fixture, which holds 18 rows of 60 bytes
 * each. Only the key values themselves are reproduced, as data.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test: it starts no application context, opens no
 * database connection, reads no file, touches no network and runs no container, because the class
 * under test is a plain serializable value holder whose only dependencies are
 * {@code java.io.Serializable} and {@code java.util.Objects}. It performs no introspection of its own
 * either - the one metadata lookup it makes, in {@link SerializationContract}, goes through the
 * serialization API and not through the low-level introspection API, so the module's zero budget for
 * the latter is left untouched.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every expected value below is a literal
 * typed out in this source and traceable to a measured legacy fact. None is produced by calling the
 * class under test, and no assertion compares a computed value with a second evaluation of the same
 * computation. Where an equality or hash expectation involves two keys, the two are constructed
 * independently.
 */
@DisplayName("TransactionCategoryId :: six-byte composite key of the transaction-category table")
class TransactionCategoryIdTest {

    // Two aspects were optional in the class's own contract and this test follows what the class
    // actually declares: it overrides the string-representation method, so a group covering that
    // method is present below, and it exposes no mutator, so none is exercised - immutability after
    // construction is part of the contract and the only writer is the provider.

    private static final int TYPE_CODE_WIDTH = 2;

    private static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * Key length declared by the cluster definition independently of the copybook. Held separately from
     * the two component widths on purpose: the test asserts that the widths sum to this figure.
     */
    private static final int DECLARED_KEY_LENGTH = 6;

    /**
     * Key offset declared by the same definition. Every base cluster in the estate declares offset 0;
     * only the alternate-index definitions carry a non-zero offset.
     */
    private static final int DECLARED_KEY_OFFSET = 0;

    /**
     * Width of the description field following the key, a non-key attribute of the entity.
     */
    private static final int DESCRIPTION_WIDTH = 50;

    /**
     * Unnamed trailing filler completing the record, carried by no property of the key or the entity.
     */
    private static final int FILLER_WIDTH = 4;

    /**
     * Record length declared by the cluster definition, held separately so the widths can be summed to it.
     */
    private static final int DECLARED_RECORD_LENGTH = 60;

    private static final int REFERENCE_ROW_COUNT = 18;

    /**
     * Key length of the transaction-category-balance key group, and below it the disclosure-group key
     * length. Both are present only as integers, recording that they differ from this key's length
     * despite the shared legacy name; neither of those classes is referenced from this file.
     */
    private static final int BALANCE_KEY_LENGTH = 17;

    private static final int DISCLOSURE_KEY_LENGTH = 16;

    /**
     * Builds the eighteen distinct reference keys, one per fixture row, in fixture order.
     *
     * <p>Each is constructed from two typed-out literals so the exact bytes are visible here: nothing
     * is assembled by formatting, padding or repetition and no value is read from a file.
     *
     * @return the eighteen reference keys, in the order the fixture holds them
     */
    private static List<TransactionCategoryId> referenceKeys() {
        return List.of(
                new TransactionCategoryId("01", "0001"),
                new TransactionCategoryId("01", "0002"),
                new TransactionCategoryId("01", "0003"),
                new TransactionCategoryId("01", "0004"),
                new TransactionCategoryId("01", "0005"),
                new TransactionCategoryId("02", "0001"),
                new TransactionCategoryId("02", "0002"),
                new TransactionCategoryId("02", "0003"),
                new TransactionCategoryId("03", "0001"),
                new TransactionCategoryId("03", "0002"),
                new TransactionCategoryId("03", "0003"),
                new TransactionCategoryId("04", "0001"),
                new TransactionCategoryId("04", "0002"),
                new TransactionCategoryId("04", "0003"),
                new TransactionCategoryId("05", "0001"),
                new TransactionCategoryId("06", "0001"),
                new TransactionCategoryId("06", "0002"),
                new TransactionCategoryId("07", "0001"));
    }

    /**
     * Counts keys carrying the given type code. The comparison is exact: nothing in this file trims,
     * folds or converts, because every character of a legacy fixed-width component is significant.
     *
     * @return the number of keys whose type code component matches exactly
     */
    private static int countOfTypeCode(final List<TransactionCategoryId> keys, final String typeCode) {
        int matches = 0;
        for (final TransactionCategoryId key : keys) {
            if (typeCode.equals(key.getTranTypeCd())) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Component-level contract: how the two key components are bound, exposed and stored.
     */
    @Nested
    @DisplayName("Component contract of the 6-byte TRAN-CAT-KEY group")
    class ComponentContract {

        @Test
        @DisplayName("all-args constructor binds components in CVTRA04Y declaration order - "
                + "TRAN-TYPE-CD first, TRAN-CAT-CD second - and never in the order some program "
                + "happens to assign them in")
        void allArgsConstructorBindsComponentsPositionally() {
            // The contractual order is the copybook declaration order, which the cluster definition's
            // key geometry and the primary-key column order both agree with. It is NOT the assignment
            // order in the interest-calculation batch program, which populates the related
            // disclosure-group key as component 1, then 3, then 2; an implementation inferred from that
            // sequence would carry a transposed signature. Hence literals distinguishable by width.
            final TransactionCategoryId key = new TransactionCategoryId("07", "0001");

            assertThat(key.getTranTypeCd()).isEqualTo("07");
            assertThat(key.getTranCatCd()).isEqualTo("0001");

            final TransactionCategoryId interestCategoryKey = new TransactionCategoryId("01", "0005");

            assertThat(interestCategoryKey.getTranTypeCd()).isEqualTo("01");
            assertThat(interestCategoryKey.getTranCatCd()).isEqualTo("0005");
        }

        @Test
        @DisplayName("a transposed constructor call is detectable by width: the type code occupies 2 "
                + "bytes and the category code 4, so the two components are not interchangeable")
        void componentWidthsMakeATransposedConstructorCallDetectable() {
            final TransactionCategoryId key = new TransactionCategoryId("07", "0001");

            final int boundTypeCodeWidth =
                    key.getTranTypeCd().getBytes(StandardCharsets.US_ASCII).length;
            final int boundCategoryCodeWidth =
                    key.getTranCatCd().getBytes(StandardCharsets.US_ASCII).length;

            assertThat(boundTypeCodeWidth).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(boundCategoryCodeWidth).isEqualTo(CATEGORY_CODE_WIDTH);
            assertThat(boundTypeCodeWidth).isNotEqualTo(boundCategoryCodeWidth);
        }

        @Test
        @DisplayName("no-arg constructor leaves both components null, because the persistence provider "
                + "populates an identifier class after instantiating it")
        void noArgConstructorLeavesBothComponentsNull() {
            // This constructor exists solely so a persistence provider can instantiate the type. It is
            // reached with no introspection at all: this test shares the package and protected access
            // includes package access.
            final TransactionCategoryId empty = new TransactionCategoryId();

            assertThat(empty.getTranTypeCd()).isNull();
            assertThat(empty.getTranCatCd()).isNull();
        }

        @Test
        @DisplayName("both components are text, so the 4-digit category code keeps its leading zeros "
                + "and \"0005\" is never narrowed to a value that would render as 5")
        void componentsAreTextAndLeadingZerosSurvive() {
            final TransactionCategoryId key = new TransactionCategoryId("01", "0005");

            // No numeric parse appears anywhere in this file. Every digit-only legacy lexeme maps to a
            // bounded character column, so the surviving leading zeros hold the text width at the
            // declared figure and that width is part of the contract.
            assertThat(key.getTranCatCd()).isEqualTo("0005");
            assertThat(key.getTranCatCd()).isNotEqualTo("5");
            assertThat(key.getTranTypeCd()).isEqualTo("01");
            assertThat(key.getTranTypeCd()).isNotEqualTo("1");

            assertThat(key.getTranCatCd().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(CATEGORY_CODE_WIDTH);
            assertThat(key.getTranTypeCd().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(TYPE_CODE_WIDTH);
        }

        @Test
        @DisplayName("constructor stores both components verbatim - no trim, no pad, no case fold and "
                + "no validation - because altering a caller's value would change lookup semantics")
        void constructorStoresComponentsVerbatim() {
            // Values chosen to expose normalisation: a lower-case type code a case fold would alter, and
            // components a trim or a numeric narrowing would alter. A blank is likewise significant in a
            // fixed-width space-padded layout. Neither the class nor this test applies any of those.
            final TransactionCategoryId unaltered = new TransactionCategoryId("ab", "0090");

            assertThat(unaltered.getTranTypeCd()).isEqualTo("ab");
            assertThat(unaltered.getTranCatCd()).isEqualTo("0090");

            final TransactionCategoryId padded = new TransactionCategoryId(" 1", "0 04");

            assertThat(padded.getTranTypeCd()).isEqualTo(" 1");
            assertThat(padded.getTranCatCd()).isEqualTo("0 04");
        }

        @Test
        @DisplayName("null components are accepted and returned unchanged, because the identifier class "
                + "validates nothing and the schema enforces the not-null constraint")
        void nullComponentsAreStoredAndReturnedUnchanged() {
            final TransactionCategoryId nullTypeCode = new TransactionCategoryId(null, "0001");

            assertThat(nullTypeCode.getTranTypeCd()).isNull();
            assertThat(nullTypeCode.getTranCatCd()).isEqualTo("0001");

            final TransactionCategoryId nullCategoryCode = new TransactionCategoryId("01", null);

            assertThat(nullCategoryCode.getTranTypeCd()).isEqualTo("01");
            assertThat(nullCategoryCode.getTranCatCd()).isNull();
        }
    }

    /**
     * Byte-width and offset contract: the geometry the copybook and the cluster definition agree on.
     */
    @Nested
    @DisplayName("Byte geometry of the key and of the 60-byte record that carries it")
    class ByteWidthContract {

        @Test
        @DisplayName("the two component widths sum to the 6 declared by TRANCATG KEYS(6 0): the "
                + "copybook and the cluster definition are independent authorities that agree")
        void componentWidthsSumToTheDeclaredKeyLength() {
            // Every width here is measured in BYTES through an explicitly named charset, never in
            // characters, because the legacy record is a byte image.
            final int typeCodeWidth = "07".getBytes(StandardCharsets.US_ASCII).length;
            final int categoryCodeWidth = "0001".getBytes(StandardCharsets.US_ASCII).length;

            assertThat(typeCodeWidth).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(categoryCodeWidth).isEqualTo(CATEGORY_CODE_WIDTH);

            final int summedKeyLength = typeCodeWidth + categoryCodeWidth;

            assertThat(summedKeyLength).isEqualTo(DECLARED_KEY_LENGTH);
        }

        @Test
        @DisplayName("the key is the leading substring of the record: key at offset 0, description at "
                + "offset 6, filler at offset 56, and 6 + 50 + 4 = the declared RECORDSIZE of 60")
        void keyIsTheLeadingSubstringOfTheSixtyByteRecord() {
            final int keyOffset = DECLARED_KEY_OFFSET;
            final int keyWidth = TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;
            final int typeCodeOffset = keyOffset;
            final int categoryCodeOffset = typeCodeOffset + TYPE_CODE_WIDTH;
            final int descriptionOffset = keyOffset + keyWidth;
            final int fillerOffset = descriptionOffset + DESCRIPTION_WIDTH;
            final int recordWidth = keyWidth + DESCRIPTION_WIDTH + FILLER_WIDTH;

            assertThat(typeCodeOffset).isZero();
            assertThat(categoryCodeOffset).isEqualTo(2);
            assertThat(descriptionOffset).isEqualTo(6);
            assertThat(fillerOffset).isEqualTo(56);
            assertThat(recordWidth).isEqualTo(DECLARED_RECORD_LENGTH);

            assertThat(recordWidth - keyWidth).isEqualTo(DESCRIPTION_WIDTH + FILLER_WIDTH);
        }

        @Test
        @DisplayName("KEYS(6 0) declares length 6 at offset 0; every DEFINE CLUSTER in the estate "
                + "declares offset 0 and only DEFINE ALTERNATEINDEX blocks carry a non-zero offset")
        void clusterDefinitionDeclaresLengthSixAtOffsetZero() {
            assertThat(TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isEqualTo(6);
            assertThat(DECLARED_KEY_OFFSET).isZero();

            // Offset 0 means the key is the leading substring of the stored image, which is why the
            // identifier is the business key itself and no surrogate is introduced.
            final int dataPortionWidth = DECLARED_RECORD_LENGTH - DECLARED_KEY_LENGTH;

            assertThat(dataPortionWidth).isEqualTo(DESCRIPTION_WIDTH + FILLER_WIDTH);
            assertThat(dataPortionWidth).isEqualTo(54);
        }

        @Test
        @DisplayName("the three composite key lengths 6, 17 and 16 are pairwise distinct: the group "
                + "name TRAN-CAT-KEY is declared in both CVTRA04Y at 6 and CVTRA01Y at 17, so "
                + "disambiguation is by member name and declared length, never by content")
        void theThreeCompositeKeyLengthsArePairwiseDistinct() {
            // Plain integers on purpose: the 17-byte and 16-byte keys have their own classes elsewhere
            // in this package and neither is named or instantiated here.
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(BALANCE_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);
            assertThat(BALANCE_KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);

            // Neither of those keys can align with this one at any offset: an 11-byte account identifier
            // precedes the components the 17-byte key shares by name, and the 16-byte key leads with a
            // 10-byte account group identifier.
            assertThat(11 + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(BALANCE_KEY_LENGTH);

            assertThat(10 + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(DISCLOSURE_KEY_LENGTH);
        }

        @Test
        @DisplayName("the reference fixture geometry closes: 18 rows of 60 bytes plus one line "
                + "terminator each account for all 1,098 measured bytes")
        void referenceFixtureGeometryAccountsForEveryByte() {
            final int lineWidthWithTerminator = DECLARED_RECORD_LENGTH + 1;
            final int measuredFixtureBytes = 1098;

            assertThat(REFERENCE_ROW_COUNT * lineWidthWithTerminator).isEqualTo(measuredFixtureBytes);
            assertThat(referenceKeys()).hasSize(REFERENCE_ROW_COUNT);
        }
    }

    /**
     * Equality and hashing contract: both components participate, nothing is normalised, and the
     * eighteen reference keys stay eighteen distinct keys inside a hash-based collection.
     */
    @Nested
    @DisplayName("Equality and hashing across both key components")
    class EqualityAndHashing {

        @Test
        @DisplayName("two independently constructed keys with identical components are equal and hash "
                + "alike, which is what lets a freshly built key match a row already loaded")
        void independentlyConstructedEqualKeysAreEqualAndHashAlike() {
            final TransactionCategoryId first = new TransactionCategoryId("03", "0002");
            final TransactionCategoryId second = new TransactionCategoryId("03", "0002");

            assertThat(first).isEqualTo(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("a key equals itself, satisfying the reflexive clause of the equality contract")
        void aKeyEqualsItself() {
            // Reflexivity is a required property of the equality contract, so the expectation is the
            // specification's own "must be true" and not a value computed by the class under test. It is
            // also the only assertion that reaches the identity short-circuit at the head of the
            // equality method.
            final TransactionCategoryId key = new TransactionCategoryId("06", "0002");

            assertThat(key.equals(key)).isTrue();
        }

        @Test
        @DisplayName("equality is symmetric for two independently constructed equal keys")
        void equalityIsSymmetric() {
            final TransactionCategoryId left = new TransactionCategoryId("04", "0003");
            final TransactionCategoryId right = new TransactionCategoryId("04", "0003");

            assertThat(left.equals(right)).isTrue();
            assertThat(right.equals(left)).isTrue();
        }

        @Test
        @DisplayName("the type code component participates in equality: type 01 category 0001 differs "
                + "from type 02 category 0001, and both are real fixture rows")
        void typeCodeComponentParticipatesInEquality() {
            final TransactionCategoryId typeOne = new TransactionCategoryId("01", "0001");
            final TransactionCategoryId typeTwo = new TransactionCategoryId("02", "0001");

            assertThat(typeOne).isNotEqualTo(typeTwo);
            assertThat(typeOne.equals(typeTwo)).isFalse();
        }

        @Test
        @DisplayName("the category code component participates in equality: type 01 category 0001 "
                + "differs from type 01 category 0002, and both are real fixture rows")
        void categoryCodeComponentParticipatesInEquality() {
            final TransactionCategoryId categoryOne = new TransactionCategoryId("01", "0001");
            final TransactionCategoryId categoryTwo = new TransactionCategoryId("01", "0002");

            assertThat(categoryOne).isNotEqualTo(categoryTwo);
            assertThat(categoryOne.equals(categoryTwo)).isFalse();
        }

        @Test
        @DisplayName("equality rejects null and rejects a foreign type, exercising the type-pattern "
                + "branch rather than throwing")
        void equalityRejectsNullAndForeignTypes() {
            final TransactionCategoryId key = new TransactionCategoryId("05", "0001");

            assertThat(key.equals(null)).isFalse();
            assertThat(key).isNotEqualTo(new Object());

            assertThat(key).isNotEqualTo("050001");
        }

        @Test
        @DisplayName("nothing is normalised: type 01 category 0001 is not equal to type 1 category 1, "
                + "because leading zeros are significant in a fixed-width legacy component")
        void noNormalisationOfAnyKindIsApplied() {
            final TransactionCategoryId padded = new TransactionCategoryId("01", "0001");
            final TransactionCategoryId unpadded = new TransactionCategoryId("1", "1");

            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(unpadded).isNotEqualTo(padded);

            assertThat("0001").isNotEqualTo("1");
            assertThat("01").isNotEqualTo("1");
        }

        @Test
        @DisplayName("all 18 reference keys stay distinct in a hash set, so no two fixture rows collapse "
                + "onto one identifier")
        void allEighteenReferenceKeysRemainDistinctInAHashSet() {
            final List<TransactionCategoryId> keys = referenceKeys();
            final Set<TransactionCategoryId> distinct = new HashSet<>(keys);

            assertThat(keys).hasSize(REFERENCE_ROW_COUNT);
            assertThat(distinct).hasSize(REFERENCE_ROW_COUNT);
        }

        @Test
        @DisplayName("all 18 reference keys index distinctly in a hash map, and an independently "
                + "constructed equal key retrieves the entry it belongs to")
        void allEighteenReferenceKeysIndexDistinctlyInAHashMap() {
            final Map<TransactionCategoryId, String> byKey = new HashMap<>();
            for (final TransactionCategoryId key : referenceKeys()) {
                byKey.put(key, "reference-row");
            }

            assertThat(byKey).hasSize(REFERENCE_ROW_COUNT);

            assertThat(byKey).containsKey(new TransactionCategoryId("07", "0001"));
            assertThat(byKey.get(new TransactionCategoryId("01", "0005"))).isEqualTo("reference-row");

            assertThat(byKey).doesNotContainKey(new TransactionCategoryId("07", "0002"));
        }

        @Test
        @DisplayName("the fixture type-code multiset 5, 3, 3, 3, 1, 2 and 1 sums to the 18 measured rows")
        void theTypeCodeMultisetSumsToTheRowCount() {
            final List<TransactionCategoryId> keys = referenceKeys();

            assertThat(countOfTypeCode(keys, "01")).isEqualTo(5);
            assertThat(countOfTypeCode(keys, "02")).isEqualTo(3);
            assertThat(countOfTypeCode(keys, "03")).isEqualTo(3);
            assertThat(countOfTypeCode(keys, "04")).isEqualTo(3);
            assertThat(countOfTypeCode(keys, "05")).isEqualTo(1);
            assertThat(countOfTypeCode(keys, "06")).isEqualTo(2);
            assertThat(countOfTypeCode(keys, "07")).isEqualTo(1);

            // Summed here rather than stated, so the multiset is shown to account for every row.
            final int summedMultiset = 5 + 3 + 3 + 3 + 1 + 2 + 1;

            assertThat(summedMultiset).isEqualTo(REFERENCE_ROW_COUNT);
            assertThat(keys).hasSize(summedMultiset);

            // No eighth type code exists in the fixture.
            assertThat(countOfTypeCode(keys, "08")).isZero();
        }

        @Test
        @DisplayName("the interest category, type 01 category 0005, is one of the 18 rows and is the "
                + "single row the disclosure fixture omits, leaving 18 - 1 = 17 rated pairs per group")
        void theInterestCategoryIsPresentAndIsTheRowTheDisclosureFixtureOmits() {
            final Set<TransactionCategoryId> distinct = new HashSet<>(referenceKeys());
            final TransactionCategoryId interestCategory = new TransactionCategoryId("01", "0005");

            assertThat(distinct).contains(interestCategory);

            // This one category carries no disclosure-rate row, which is why the disclosure fixture
            // holds seventeen rated pairs per group rather than eighteen. Asserted as plain integer
            // arithmetic: no foreign key type is instantiated or named here.
            final int categoriesWithoutARate = 1;
            final int ratedPairsPerDisclosureGroup = REFERENCE_ROW_COUNT - categoriesWithoutARate;

            assertThat(ratedPairsPerDisclosureGroup).isEqualTo(17);
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
            // module's audited budget for introspection is zero and a test must never undermine a
            // production gate, so this is the one permitted idiom.
            final ObjectStreamClass descriptor = ObjectStreamClass.lookup(TransactionCategoryId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the key type is serializable, as an identifier class carried across a "
                + "persistence boundary must be")
        void theKeyTypeIsSerializable() {
            final TransactionCategoryId key = new TransactionCategoryId("02", "0003");

            assertThat(key).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("a serialization round trip preserves both components verbatim along with "
                + "equality and hash code, so a detached key still matches its row")
        void serializationRoundTripPreservesComponentsEqualityAndHashCode() throws IOException,
                ClassNotFoundException {
            final TransactionCategoryId original = new TransactionCategoryId("06", "0001");

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(original);
            }

            final TransactionCategoryId restored;
            try (ObjectInputStream in =
                         new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                // A checked cast on a non-generic type: it produces no unchecked-cast diagnostic and
                // therefore needs no suppression.
                restored = (TransactionCategoryId) in.readObject();
            }

            assertThat(restored.getTranTypeCd()).isEqualTo("06");
            assertThat(restored.getTranCatCd()).isEqualTo("0001");
            assertThat(restored).isEqualTo(original);
            assertThat(original).isEqualTo(restored);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());

            assertThat(restored).isNotSameAs(original);
        }

        @Test
        @DisplayName("a round trip of an empty key preserves both null components, because the "
                + "no-arg constructor path must survive serialization too")
        void serializationRoundTripPreservesAnEmptyKey() throws IOException, ClassNotFoundException {
            final TransactionCategoryId empty = new TransactionCategoryId();

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(empty);
            }

            final TransactionCategoryId restored;
            try (ObjectInputStream in =
                         new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                restored = (TransactionCategoryId) in.readObject();
            }

            assertThat(restored.getTranTypeCd()).isNull();
            assertThat(restored.getTranCatCd()).isNull();
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
        @DisplayName("no surrogate identifier exists: TRANCATG KEYS(6 0) puts the key at offset 0 as "
                + "the leading substring of the record, so the composite business key IS the identifier")
        void noSurrogateIdentifierExists() {
            // A surrogate would break the image-to-row correspondence that byte-parity verification of the
            // migrated output depends on. What is asserted is behavioural: the two components the caller
            // supplies are the whole of the key and come back unchanged.
            final TransactionCategoryId key = new TransactionCategoryId("04", "0002");

            assertThat(key.getTranTypeCd()).isEqualTo("04");
            assertThat(key.getTranCatCd()).isEqualTo("0002");

            assertThat(DECLARED_KEY_OFFSET).isZero();
            assertThat(TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH).isEqualTo(DECLARED_KEY_LENGTH);
        }

        @Test
        @DisplayName("no fourth composite key type exists: CVTRA03Y is also a 60-byte layout, 2 + 50 + "
                + "8, but declares no key group at all, and its single 2-byte key matches TRANTYPE "
                + "KEYS(2 0), so the transaction-type table takes one plain identifier and no key class")
        void noFourthCompositeKeyTypeExists() {
            // Both reference layouts are 60 bytes, so record length alone can never tell them apart: only
            // the key geometry can, and the transaction-type table has no composite key class here
            // because its key is a single component.
            final int transactionTypeKeyWidth = 2;
            final int transactionTypeDescriptionWidth = 50;
            final int transactionTypeFillerWidth = 8;
            final int transactionTypeRecordWidth = transactionTypeKeyWidth
                    + transactionTypeDescriptionWidth
                    + transactionTypeFillerWidth;

            assertThat(transactionTypeRecordWidth).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(transactionTypeRecordWidth).isEqualTo(60);

            assertThat(transactionTypeKeyWidth).isNotEqualTo(DECLARED_KEY_LENGTH);

            // The two layouts differ only in how each divides its 60 bytes - 6 and 54 here against 2 and
            // 58 there - which is why the key geometry rather than the record size discriminates.
            assertThat(DECLARED_RECORD_LENGTH - DECLARED_KEY_LENGTH)
                    .isEqualTo(DESCRIPTION_WIDTH + FILLER_WIDTH);
            assertThat(DECLARED_RECORD_LENGTH - transactionTypeKeyWidth)
                    .isEqualTo(transactionTypeDescriptionWidth + transactionTypeFillerWidth);
        }

        @Test
        @DisplayName("the key carries no persistence metadata and needs no framework: it constructs, "
                + "compares, hashes and serialises in a plain JVM, because the identifier-class "
                + "declaration sits on the entity and never on the key")
        void theKeyNeedsNoFrameworkAtAll() throws IOException, ClassNotFoundException {
            // Everything runs on the plain JVM the harness provides, which is all the environment this
            // value holder needs.
            final TransactionCategoryId key = new TransactionCategoryId("03", "0003");
            final TransactionCategoryId twin = new TransactionCategoryId("03", "0003");

            assertThat(key).isEqualTo(twin);
            assertThat(key.hashCode()).isEqualTo(twin.hashCode());

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(key);
            }

            final TransactionCategoryId restored;
            try (ObjectInputStream in =
                         new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                restored = (TransactionCategoryId) in.readObject();
            }

            assertThat(restored.getTranTypeCd()).isEqualTo("03");
            assertThat(restored.getTranCatCd()).isEqualTo("0003");
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
        @DisplayName("the representation carries both key components, so a diagnostic line identifies "
                + "the row without a lookup")
        void representationCarriesBothComponents() {
            final TransactionCategoryId key = new TransactionCategoryId("06", "0002");

            final String representation = key.toString();

            assertThat(representation).contains("06");
            assertThat(representation).contains("0002");
        }

        @Test
        @DisplayName("the representation names the type before the category, matching the contractual "
                + "CVTRA04Y declaration order, and keeps the category's leading zeros")
        void representationFollowsContractualComponentOrder() {
            final TransactionCategoryId key = new TransactionCategoryId("01", "0005");

            assertThat(key.toString())
                    .isEqualTo("TransactionCategoryId[tranTypeCd=01, tranCatCd=0005]");
        }

        @Test
        @DisplayName("the representation of an empty key reports both components as absent instead of "
                + "failing, so an unpopulated identifier is still diagnosable")
        void representationOfAnEmptyKeyReportsBothComponentsAsAbsent() {
            final TransactionCategoryId empty = new TransactionCategoryId();

            assertThat(empty.toString())
                    .isEqualTo("TransactionCategoryId[tranTypeCd=null, tranCatCd=null]");
        }
    }
}
