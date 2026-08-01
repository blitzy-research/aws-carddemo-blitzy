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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DisclosureGroupId}, the composite identifier class of the disclosure-group
 * reference table that supplies the interest rate to the interest-calculation run.
 *
 * <p><strong>What this test proves.</strong> Two independent legacy authorities fix the same key
 * geometry, and this test asserts that they agree rather than restating one of them twice:
 * <ul>
 *   <li>the copybook member {@code CVTRA02Y}, whose record length is 50 and whose key group
 *       {@code DIS-GROUP-KEY} is 16 bytes wide - a 10-byte {@code DIS-ACCT-GROUP-ID} at offset 0, a
 *       2-byte {@code DIS-TRAN-TYPE-CD} at offset 10 and a 4-digit {@code DIS-TRAN-CAT-CD} at offset
 *       12 - ahead of a 6-byte zoned rate field at offset 16 and a 28-byte trailing filler at offset
 *       22; and</li>
 *   <li>the {@code DISCGRP} indexed cluster definition in the batch job library, which declares
 *       {@code KEYS(16 0)} together with {@code RECORDSIZE(50 50)}.</li>
 * </ul>
 *
 * <p><strong>Why the leading component is ten bytes and not shorter.</strong> The account group
 * identifier is a padded text field, and the reference fixture uses that padding: two of its three
 * group values are shorter words padded out to ten bytes. Treating the component as trimmed text
 * would make two distinct stored keys compare equal to values they are not, so this test asserts the
 * padding is carried verbatim into the identifier and participates in equality.
 *
 * <p><strong>Reference data.</strong> The disclosure-group fixture holds 51 rows of 50 bytes, forming
 * three complete seventeen-row groups under the account group values reproduced below. One of the
 * three is the fallback group the interest program reaches when a direct lookup misses, and one
 * carries a zero rate throughout, which is what makes both the fallback branch and the zero-rate skip
 * branch reachable from seed data alone. Only key values are reproduced here, as data; no line of
 * legacy source is transcribed anywhere in this file.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network and runs no container: the class under test
 * is a plain serializable value holder whose only dependencies are {@code java.io.Serializable} and
 * {@code java.util.Objects}. The one metadata lookup it makes, in {@link SerializationContract},
 * goes through the serialization API rather than the low-level introspection API, so the module's
 * zero budget for the latter is left untouched.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited as provenance only and never asserted
 * against a member.
 */
@DisplayName("DisclosureGroupId - the 16-byte composite key of the 50-byte disclosure-group record")
class DisclosureGroupIdBaselineTest {

    /** Declared width of the account group identifier component. */
    private static final int GROUP_ID_WIDTH = 10;

    /** Declared width of the transaction type component. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Declared width of the transaction category component. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Key length declared by the cluster definition. */
    private static final int DECLARED_KEY_LENGTH = 16;

    /** Record length declared by the cluster definition and by the copybook. */
    private static final int RECORD_LENGTH = 50;

    /** Width of the zoned interest-rate field that follows the key. */
    private static final int RATE_FIELD_WIDTH = 6;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 28;

    /** Rows measured in the disclosure-group reference fixture. */
    private static final int FIXTURE_ROWS = 51;

    /** Rows measured within each account group of the fixture. */
    private static final int FIXTURE_ROWS_PER_GROUP = 17;

    /** The account group value carried by the first group of the fixture. */
    private static final String ACCOUNT_GROUP = "A000000000";

    /** The fallback account group value the interest program reaches on a lookup miss. */
    private static final String DEFAULT_GROUP = "DEFAULT   ";

    /** The account group value whose rate is zero throughout the fixture. */
    private static final String ZERO_RATE_GROUP = "ZEROAPR   ";

    /** The three account group values measured in the fixture, in fixture order. */
    private static final List<String> FIXTURE_GROUPS =
            List.of(ACCOUNT_GROUP, DEFAULT_GROUP, ZERO_RATE_GROUP);

    /** Transaction type of the purchase rows the interest run reads first. */
    private static final String PURCHASE_TYPE = "01";

    /** Transaction category of the first purchase row. */
    private static final String FIRST_CATEGORY = "0001";

    /** Transaction category of the second purchase row. */
    private static final String SECOND_CATEGORY = "0002";

    @Nested
    @DisplayName("Component contract of the 16-byte DIS-GROUP-KEY group")
    class ComponentContract {

        @Test
        @DisplayName("the all-args constructor binds the three components in copybook declaration "
                + "order: group identifier, then transaction type, then transaction category")
        void allArgsConstructorBindsComponentsInDeclarationOrder() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.getDisAcctGroupId()).isEqualTo(ACCOUNT_GROUP);
            assertThat(id.getDisTranTypeCd()).isEqualTo(PURCHASE_TYPE);
            assertThat(id.getDisTranCatCd()).isEqualTo(FIRST_CATEGORY);
        }

        @Test
        @DisplayName("a transposed constructor call is detectable by width, since the three components "
                + "are ten, two and four bytes wide and no two of them agree")
        void aTransposedConstructorCallIsDetectableByWidth() {
            final DisclosureGroupId transposed =
                    new DisclosureGroupId(FIRST_CATEGORY, ACCOUNT_GROUP, PURCHASE_TYPE);

            assertThat(transposed.getDisAcctGroupId()).hasSize(CATEGORY_CODE_WIDTH);
            assertThat(transposed.getDisTranTypeCd()).hasSize(GROUP_ID_WIDTH);
            assertThat(transposed.getDisTranCatCd()).hasSize(TYPE_CODE_WIDTH);

            assertThat(GROUP_ID_WIDTH).isNotEqualTo(TYPE_CODE_WIDTH).isNotEqualTo(CATEGORY_CODE_WIDTH);
            assertThat(TYPE_CODE_WIDTH).isNotEqualTo(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("the no-arg constructor leaves all three components absent, because the "
                + "persistence provider populates an identifier after instantiating it")
        void noArgConstructorLeavesAllComponentsAbsent() {
            final DisclosureGroupId empty = new DisclosureGroupId();

            assertThat(empty.getDisAcctGroupId()).isNull();
            assertThat(empty.getDisTranTypeCd()).isNull();
            assertThat(empty.getDisTranCatCd()).isNull();
        }

        @Test
        @DisplayName("all three components are text, so the four-digit category code keeps its leading "
                + "zeros and the padded group value keeps its trailing spaces")
        void allThreeComponentsAreTextAndKeepTheirShape() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(DEFAULT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.getDisTranCatCd()).startsWith("000").hasSize(CATEGORY_CODE_WIDTH);
            assertThat(id.getDisAcctGroupId()).endsWith("   ").hasSize(GROUP_ID_WIDTH);
            assertThat(id.getDisTranTypeCd()).isEqualTo("01");
        }

        @Test
        @DisplayName("the constructor stores every component verbatim - no trim, no pad, no case fold "
                + "and no numeric reinterpretation")
        void theConstructorStoresEveryComponentVerbatim() {
            final DisclosureGroupId id = new DisclosureGroupId("  mIxEd  ", "aZ", "9x0");

            assertThat(id.getDisAcctGroupId()).isEqualTo("  mIxEd  ");
            assertThat(id.getDisTranTypeCd()).isEqualTo("aZ");
            assertThat(id.getDisTranCatCd()).isEqualTo("9x0");
        }

        @Test
        @DisplayName("absent components are accepted and returned unchanged, because the identifier "
                + "class performs no validation of its own")
        void absentComponentsAreAcceptedAndReturnedUnchanged() {
            final DisclosureGroupId id = new DisclosureGroupId(null, null, null);

            assertThat(id.getDisAcctGroupId()).isNull();
            assertThat(id.getDisTranTypeCd()).isNull();
            assertThat(id.getDisTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the key and of the 50-byte record that carries it")
    class ByteGeometry {

        @Test
        @DisplayName("the three component widths sum to the sixteen the cluster declares, so the "
                + "copybook layout and the cluster definition agree")
        void theThreeComponentWidthsSumToTheDeclaredKeyLength() {
            assertThat(GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);
        }

        @Test
        @DisplayName("the key is the leading substring of the record: key at offset 0, rate at offset "
                + "16, filler at offset 22, and the three widths close the 50-byte record exactly")
        void theKeyIsTheLeadingSubstringOfTheRecord() {
            assertThat(DECLARED_KEY_LENGTH + RATE_FIELD_WIDTH + FILLER_WIDTH)
                    .isEqualTo(RECORD_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isEqualTo(16);
            assertThat(DECLARED_KEY_LENGTH + RATE_FIELD_WIDTH).isEqualTo(22);
        }

        @Test
        @DisplayName("a key assembled by concatenating the three components in declaration order "
                + "occupies exactly the declared sixteen bytes")
        void anAssembledKeyOccupiesTheDeclaredWidth() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ZERO_RATE_GROUP, PURCHASE_TYPE, SECOND_CATEGORY);
            final String assembled =
                    id.getDisAcctGroupId() + id.getDisTranTypeCd() + id.getDisTranCatCd();

            assertThat(assembled).hasSize(DECLARED_KEY_LENGTH);
            assertThat(assembled).isEqualTo("ZEROAPR   010002");
        }

        @Test
        @DisplayName("the rate field is six bytes because its picture carries four integer digits and "
                + "two decimal places, which is the widest the interest computation may read")
        void theRateFieldIsSixBytes() {
            final int integerDigits = 4;
            final int decimalDigits = 2;

            assertThat(integerDigits + decimalDigits).isEqualTo(RATE_FIELD_WIDTH);
        }

        @Test
        @DisplayName("this sixteen-byte key is distinct in length from the six-byte transaction-category "
                + "key and the seventeen-byte category-balance key, so the three composite keys of the "
                + "estate cannot be confused with one another")
        void theThreeCompositeKeyLengthsArePairwiseDistinct() {
            final int transactionCategoryKeyLength = 6;
            final int categoryBalanceKeyLength = 17;

            assertThat(DECLARED_KEY_LENGTH)
                    .isNotEqualTo(transactionCategoryKeyLength)
                    .isNotEqualTo(categoryBalanceKeyLength);
            assertThat(transactionCategoryKeyLength).isNotEqualTo(categoryBalanceKeyLength);
        }

        @Test
        @DisplayName("the reference fixture geometry closes: three groups of seventeen rows make the "
                + "fifty-one rows measured in the file")
        void theReferenceFixtureGeometryCloses() {
            assertThat(FIXTURE_GROUPS.size() * FIXTURE_ROWS_PER_GROUP).isEqualTo(FIXTURE_ROWS);
        }
    }

    @Nested
    @DisplayName("Equality and hashing across all three key components")
    class EqualityAndHashing {

        @Test
        @DisplayName("two independently constructed keys with identical components are equal and hash "
                + "alike")
        void independentlyConstructedEqualKeysAgree() {
            final DisclosureGroupId first =
                    new DisclosureGroupId("A000000000", "01", "0001");
            final DisclosureGroupId second =
                    new DisclosureGroupId("A000000000", "01", "0001");

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a key equals itself, satisfying the reflexive clause of the equality contract")
        void aKeyEqualsItself() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id).isEqualTo(id);
        }

        @Test
        @DisplayName("equality is symmetric for two independently constructed equal keys")
        void equalityIsSymmetric() {
            final DisclosureGroupId first =
                    new DisclosureGroupId("DEFAULT   ", "02", "0003");
            final DisclosureGroupId second =
                    new DisclosureGroupId("DEFAULT   ", "02", "0003");

            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(first)).isTrue();
        }

        @Test
        @DisplayName("equality is transitive across three independently constructed equal keys")
        void equalityIsTransitive() {
            final DisclosureGroupId first = new DisclosureGroupId("ZEROAPR   ", "03", "0002");
            final DisclosureGroupId second = new DisclosureGroupId("ZEROAPR   ", "03", "0002");
            final DisclosureGroupId third = new DisclosureGroupId("ZEROAPR   ", "03", "0002");

            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(first.equals(third)).isTrue();
        }

        @Test
        @DisplayName("the group component participates in equality, so a purchase rate under the "
                + "fallback group is not the same key as the same rate under a named group")
        void theGroupComponentParticipatesInEquality() {
            final DisclosureGroupId named =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);
            final DisclosureGroupId fallback =
                    new DisclosureGroupId(DEFAULT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(named).isNotEqualTo(fallback);
        }

        @Test
        @DisplayName("the transaction type component participates in equality")
        void theTypeComponentParticipatesInEquality() {
            final DisclosureGroupId purchase =
                    new DisclosureGroupId(ACCOUNT_GROUP, "01", FIRST_CATEGORY);
            final DisclosureGroupId payment =
                    new DisclosureGroupId(ACCOUNT_GROUP, "02", FIRST_CATEGORY);

            assertThat(purchase).isNotEqualTo(payment);
        }

        @Test
        @DisplayName("the transaction category component participates in equality")
        void theCategoryComponentParticipatesInEquality() {
            final DisclosureGroupId first =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);
            final DisclosureGroupId second =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, SECOND_CATEGORY);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("padding is significant: the fallback group value padded to ten bytes is not equal "
                + "to the same word trimmed, so a trimmed lookup would miss the seeded row")
        void paddingIsSignificantInEquality() {
            final DisclosureGroupId padded =
                    new DisclosureGroupId(DEFAULT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);
            final DisclosureGroupId trimmed =
                    new DisclosureGroupId("DEFAULT", PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(padded).isNotEqualTo(trimmed);
        }

        @Test
        @DisplayName("nothing is normalised: a category of 0001 is not equal to a category of 1, "
                + "because the component is text and not a number")
        void nothingIsNormalisedInEquality() {
            final DisclosureGroupId zeroFilled =
                    new DisclosureGroupId(ACCOUNT_GROUP, "01", "0001");
            final DisclosureGroupId unpadded =
                    new DisclosureGroupId(ACCOUNT_GROUP, "1", "1");

            assertThat(zeroFilled).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type, exercising the "
                + "type-pattern guard rather than throwing")
        void equalityRejectsAbsentOperandAndForeignType() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.equals(null)).isFalse();
            assertThat(id.equals("A00000000001 0001")).isFalse();
            assertThat(id.equals(new TransactionCategoryBalanceId(
                    ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY))).isFalse();
        }

        @Test
        @DisplayName("two empty keys are equal and hash alike, so a provider may compare two "
                + "not-yet-populated identifiers without surprise")
        void twoEmptyKeysAreEqualAndHashAlike() {
            final DisclosureGroupId first = new DisclosureGroupId();
            final DisclosureGroupId second = new DisclosureGroupId();

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("all fifty-one fixture keys stay distinct in a hash set, so no two seeded rows "
                + "collapse onto one identifier")
        void allFixtureKeysStayDistinctInAHashSet() {
            final Set<DisclosureGroupId> keys = new HashSet<>();

            for (final String group : FIXTURE_GROUPS) {
                for (int category = 1; category <= FIXTURE_ROWS_PER_GROUP; category++) {
                    keys.add(new DisclosureGroupId(group, PURCHASE_TYPE,
                            String.format("%04d", category)));
                }
            }

            assertThat(keys).hasSize(FIXTURE_ROWS);
        }

        @Test
        @DisplayName("an independently constructed key retrieves the value a hash map stored under an "
                + "equal key, which is the property the provider relies on")
        void anEqualKeyRetrievesTheStoredValue() {
            final Map<DisclosureGroupId, String> byKey = new HashMap<>();
            byKey.put(new DisclosureGroupId("ZEROAPR   ", "01", "0001"), "zero rate");

            assertThat(byKey.get(new DisclosureGroupId("ZEROAPR   ", "01", "0001")))
                    .isEqualTo("zero rate");
            assertThat(byKey.get(new DisclosureGroupId("ZEROAPR", "01", "0001"))).isNull();
        }
    }

    @Nested
    @DisplayName("Serialization contract required of an identifier class")
    class SerializationContract {

        @Test
        @DisplayName("the serialization version identifier is pinned at one, so a stored identifier "
                + "stays readable across builds")
        void theSerializationVersionIdentifierIsPinned() {
            final ObjectStreamClass descriptor = ObjectStreamClass.lookup(DisclosureGroupId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the key type is serializable, as an identifier class carried across a "
                + "persistence boundary must be")
        void theKeyTypeIsSerializable() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("a serialization round trip preserves all three components verbatim along with "
                + "equality and hashing")
        void aRoundTripPreservesEveryComponent() throws IOException, ClassNotFoundException {
            final DisclosureGroupId original =
                    new DisclosureGroupId(DEFAULT_GROUP, PURCHASE_TYPE, SECOND_CATEGORY);

            final DisclosureGroupId restored = roundTrip(original);

            assertThat(restored.getDisAcctGroupId()).isEqualTo(DEFAULT_GROUP);
            assertThat(restored.getDisTranTypeCd()).isEqualTo(PURCHASE_TYPE);
            assertThat(restored.getDisTranCatCd()).isEqualTo(SECOND_CATEGORY);
            assertThat(restored).isEqualTo(original);
            assertThat(restored).hasSameHashCodeAs(original);
        }

        @Test
        @DisplayName("a round trip of an empty key preserves all three absent components, because the "
                + "provider may serialize an identifier it has not yet populated")
        void aRoundTripOfAnEmptyKeyPreservesAbsentComponents()
                throws IOException, ClassNotFoundException {
            final DisclosureGroupId restored = roundTrip(new DisclosureGroupId());

            assertThat(restored.getDisAcctGroupId()).isNull();
            assertThat(restored.getDisTranTypeCd()).isNull();
            assertThat(restored.getDisTranCatCd()).isNull();
        }

        private DisclosureGroupId roundTrip(final DisclosureGroupId original)
                throws IOException, ClassNotFoundException {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(original);
            }

            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (DisclosureGroupId) in.readObject();
            }
        }
    }

    @Nested
    @DisplayName("Diagnostic representation of the key")
    class StringRepresentation {

        @Test
        @DisplayName("the representation names the type and quotes all three components in declaration "
                + "order, so a diagnostic line identifies the seeded row unambiguously")
        void theRepresentationQuotesAllThreeComponentsInOrder() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.toString()).isEqualTo(
                    "DisclosureGroupId[disAcctGroupId='A000000000', disTranTypeCd='01', "
                            + "disTranCatCd='0001']");
        }

        @Test
        @DisplayName("the quoting makes the ten-byte padding visible, which a bare representation "
                + "would hide and which matters because the padding participates in equality")
        void theQuotingMakesPaddingVisible() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(DEFAULT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.toString()).contains("disAcctGroupId='DEFAULT   '");
        }

        @Test
        @DisplayName("the representation of an empty key reports all three components as absent rather "
                + "than failing")
        void theRepresentationOfAnEmptyKeyReportsAbsentComponents() {
            assertThat(new DisclosureGroupId().toString()).isEqualTo(
                    "DisclosureGroupId[disAcctGroupId='null', disTranTypeCd='null', "
                            + "disTranCatCd='null']");
        }
    }

    @Nested
    @DisplayName("Deliberate absences recorded by this key type")
    class DocumentedAbsence {

        @Test
        @DisplayName("no surrogate identifier exists: the cluster places the key at offset 0 as the "
                + "leading sixteen bytes of the record, so the business key is the identifier")
        void noSurrogateIdentifierExists() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);
            final String assembled =
                    id.getDisAcctGroupId() + id.getDisTranTypeCd() + id.getDisTranCatCd();

            assertThat(assembled).hasSize(DECLARED_KEY_LENGTH);
            assertThat(RECORD_LENGTH - DECLARED_KEY_LENGTH)
                    .isEqualTo(RATE_FIELD_WIDTH + FILLER_WIDTH);
        }

        @Test
        @DisplayName("the interest rate is not part of the key, so updating a rate does not move the "
                + "row: the rate sits after the key in the record and outside the identifier")
        void theInterestRateIsNotPartOfTheKey() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ACCOUNT_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.toString()).doesNotContain("Rate");
            assertThat(id.toString()).doesNotContain("rate");
        }

        @Test
        @DisplayName("the key carries no persistence metadata and needs no framework: it constructs, "
                + "compares, hashes, serializes and prints on a plain virtual machine")
        void theKeyNeedsNoFramework() {
            final DisclosureGroupId id =
                    new DisclosureGroupId(ZERO_RATE_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            final DisclosureGroupId twin =
                    new DisclosureGroupId(ZERO_RATE_GROUP, PURCHASE_TYPE, FIRST_CATEGORY);

            assertThat(id.getDisAcctGroupId()).isEqualTo(ZERO_RATE_GROUP);
            assertThat(id).isEqualTo(twin);
            assertThat(id).hasSameHashCodeAs(twin);
            assertThat(id.toString()).startsWith("DisclosureGroupId[");
        }
    }
}
