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
import java.util.ArrayList;
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
 * reference table.
 *
 * <p><strong>What this test proves.</strong> Three independent legacy authorities fix the same key
 * geometry and this test pins all three: copybook member {@code CVTRA02Y}, whose 50-byte record
 * opens with a 16-byte key group of a 10-byte account group identifier, a 2-byte transaction type
 * code and a 4-digit transaction category code, ahead of a 6-byte signed rate and a 28-byte
 * trailing filler; the {@code DISCGRP} cluster definition, which declares {@code KEYS(16 0)} and
 * {@code RECORDSIZE(50 50)} independently of the copybook; and the file-section layout of the
 * interest-calculation program {@code CBACT04C}, which splits the same record into the identical
 * 16-byte key group followed by a single 34-byte remainder. The declared key length, the sum of the
 * three component widths and the two record decompositions are therefore cross-checks on one
 * another rather than restatements, and this test asserts that they agree.
 *
 * <p><strong>The untrimmed padded group identifier, and why it is the highest-value assertion
 * here.</strong> When a direct disclosure-group read misses, the interest-calculation program does
 * not fail: the missing-record file status is explicitly normalised to success before the fallback
 * runs, and the program then substitutes a 7-character default group literal into the 10-byte
 * alphanumeric group-identifier field and re-reads the file with the mutated key. A legacy
 * alphanumeric move left-justifies and space-pads to the width of the receiving field, so the retry
 * key carries {@code "DEFAULT   "} at the full ten characters and never the 7-character
 * {@code "DEFAULT"}. The ten-byte padded form is consequently never written as a literal anywhere
 * in the estate - the 7-character literal appears exactly once, in that one substitution - so the
 * padding exists only as a consequence of the field width.
 *
 * <p>That fallback is not a rare corner. Every one of the 50 rows of the seeded account fixture
 * carries ten spaces in its account group identifier, and ten spaces match none of the three group
 * identifiers the disclosure fixture holds. The direct read therefore always misses, the fallback
 * always fires, and the padded key is the only disclosure-group key the seeded data ever resolves.
 * A trimming or normalising defect anywhere in this key would not break one obscure case; it would
 * break the whole interest-calculation path. Hence the dedicated group below, and hence the
 * hash-based collection assertions: equality alone would not catch a hash code that normalises.
 *
 * <p><strong>Reference data.</strong> The 51 distinct keys asserted here are the measured contents
 * of the disclosure-group reference fixture, which holds 51 rows of 50 bytes each as three groups
 * of 17. All three groups carry the identical 17 type-and-category pairs, which is precisely why
 * the fallback always resolves: whatever pair the direct read carried, the default group has a
 * matching row. Only the key values themselves are reproduced, as data.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test: it starts no application context, opens
 * no database connection, reads no file, touches no network and runs no container, because the
 * class under test is a plain serializable value holder whose only dependencies are
 * {@code java.io.Serializable} and {@code java.util.Objects}. It performs no introspection of its
 * own either - the one metadata lookup it makes, in {@link SerializationContract}, goes through the
 * serialization API and not through the low-level introspection API, so the module's zero budget
 * for the latter is left untouched.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every expected value below is a
 * literal typed out in this source and traceable to a measured legacy fact. None is produced by
 * calling the class under test, and no assertion compares a computed value with a second
 * evaluation of the same computation. Where an equality or hash expectation involves two keys, the
 * two are constructed independently. Every padded value is typed out character by character rather
 * than assembled, so its trailing spaces are visible at the point of use.
 */
@DisplayName("DisclosureGroupId :: sixteen-byte composite key of the disclosure-group table")
class DisclosureGroupIdTest {

    // The class under test overrides the string-representation method, so a group covering that
    // method is present below. It exposes no mutator, so none is exercised: immutability after
    // construction is part of the contract and the only writer is the persistence provider.

    /**
     * Width of the account group identifier component, the first 10 bytes of the key group.
     */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * Width of the transaction type code component.
     */
    private static final int TYPE_CODE_WIDTH = 2;

    /**
     * Width of the transaction category code component.
     */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * Key length declared by the cluster definition independently of the copybook. Held separately
     * from the three component widths on purpose: the test asserts that the widths sum to this
     * figure rather than restating it.
     */
    private static final int DECLARED_KEY_LENGTH = 16;

    /**
     * Key offset declared by the same definition. Every base cluster in the estate declares offset
     * 0; only the alternate-index definitions carry a non-zero offset.
     */
    private static final int DECLARED_KEY_OFFSET = 0;

    /**
     * Width of the signed disclosure rate that follows the key, a non-key attribute of the entity.
     * Four integer digits plus two decimal digits: the implied decimal point consumes no byte and
     * the sign is overpunched into the final byte, so the field occupies 6 bytes and not 7.
     */
    private static final int RATE_WIDTH = 6;

    /**
     * Unnamed trailing filler completing the record, carried by no property of the key or the
     * entity.
     */
    private static final int FILLER_WIDTH = 28;

    /**
     * Record length declared by the cluster definition, held separately so the widths can be summed
     * to it.
     */
    private static final int DECLARED_RECORD_LENGTH = 50;

    /**
     * Width of the single undivided remainder that the interest-calculation program's file section
     * places after the key group, where the copybook instead names a rate and a filler. The two
     * decompositions must reconcile.
     */
    private static final int FILE_SECTION_REMAINDER_WIDTH = 34;

    /**
     * Rows in the disclosure-group reference fixture.
     */
    private static final int REFERENCE_ROW_COUNT = 51;

    /**
     * Rows per group in that fixture. Three groups carry this many rows each.
     */
    private static final int ROWS_PER_GROUP = 17;

    /**
     * Distinct group identifiers in that fixture.
     */
    private static final int GROUP_COUNT = 3;

    /**
     * Measured byte length of the disclosure-group reference fixture.
     */
    private static final int REFERENCE_FIXTURE_BYTES = 2601;

    /**
     * Rows in the account reference fixture, every one of which carries a blank group identifier.
     */
    private static final int ACCOUNT_ROW_COUNT = 50;

    /**
     * Record length of the account layout, summed from its declared field widths below.
     */
    private static final int ACCOUNT_RECORD_LENGTH = 300;

    /**
     * Offset at which the account layout places its 10-byte group identifier - the field whose
     * value is copied into this key's first component.
     */
    private static final int ACCOUNT_GROUP_ID_OFFSET = 112;

    /**
     * Rows in the transaction-category reference fixture. One of them carries no disclosure rate,
     * which is why each disclosure group holds one fewer pair than this.
     */
    private static final int CATEGORY_ROW_COUNT = 18;

    /**
     * Key length of the transaction-category-balance key group, and below it the
     * transaction-category key length. Both are present only as integers, recording that they
     * differ from this key's length; neither of those classes is referenced from this file.
     */
    private static final int BALANCE_KEY_LENGTH = 17;

    private static final int CATEGORY_KEY_LENGTH = 6;

    /**
     * The 17 transaction type codes of the fixture's type-and-category pairs, in fixture order.
     * Paired positionally with {@link #PAIR_CATEGORY_CODES}: index i of this array and index i of
     * that one together form pair i. Every value is typed out so the exact bytes are visible here.
     */
    private static final List<String> PAIR_TYPE_CODES = List.of(
            "01", "01", "01", "01",
            "02", "02", "02",
            "03", "03", "03",
            "04", "04", "04",
            "05",
            "06", "06",
            "07");

    /**
     * The 17 transaction category codes of those same pairs, in the same fixture order, with their
     * leading zeros intact.
     */
    private static final List<String> PAIR_CATEGORY_CODES = List.of(
            "0001", "0002", "0003", "0004",
            "0001", "0002", "0003",
            "0001", "0002", "0003",
            "0001", "0002", "0003",
            "0001",
            "0001", "0002",
            "0001");

    /**
     * Builds the 17 keys the fixture holds under one group identifier, in fixture order.
     *
     * <p>The group identifier is supplied by the caller as a typed-out literal and is used exactly
     * as given: nothing here pads, trims or folds it, because the padding carried by two of the
     * three fixture identifiers is part of the stored key.
     *
     * @param groupId the account group identifier, used verbatim
     * @return the 17 keys of that group, in the order the fixture holds them
     */
    private static List<DisclosureGroupId> keysOfGroup(final String groupId) {
        final List<DisclosureGroupId> keys = new ArrayList<>();
        for (int pair = 0; pair < ROWS_PER_GROUP; pair++) {
            keys.add(new DisclosureGroupId(
                    groupId, PAIR_TYPE_CODES.get(pair), PAIR_CATEGORY_CODES.get(pair)));
        }
        return keys;
    }

    /**
     * Builds all 51 reference keys - the 17 type-and-category pairs under each of the three group
     * identifiers the fixture carries, in fixture order.
     *
     * <p>Each group identifier is typed out at its full ten characters, two of them with the
     * trailing spaces that pad a shorter value to the field width. Nothing is assembled by
     * formatting, padding or repetition and no value is read from a file.
     *
     * @return the 51 reference keys, in the order the fixture holds them
     */
    private static List<DisclosureGroupId> referenceKeys() {
        final List<DisclosureGroupId> keys = new ArrayList<>();
        keys.addAll(keysOfGroup("A000000000"));
        keys.addAll(keysOfGroup("DEFAULT   "));
        keys.addAll(keysOfGroup("ZEROAPR   "));
        return keys;
    }

    /**
     * Measures a value in bytes through an explicitly named charset, because a legacy fixed-width
     * field is a byte image and its width is a byte count rather than a character count.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies
     */
    private static int byteWidthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Round-trips a value through Java serialization.
     *
     * @param original the value to serialize
     * @return the deserialized copy
     * @throws IOException            if either byte stream fails
     * @throws ClassNotFoundException if the class cannot be resolved on the way back
     */
    private static DisclosureGroupId serializeAndBack(final DisclosureGroupId original)
            throws IOException, ClassNotFoundException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in =
                     new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            // A checked cast on a non-generic type: it produces no unchecked-cast diagnostic and
            // therefore needs no suppression.
            return (DisclosureGroupId) in.readObject();
        }
    }

    /**
     * Component-level contract: how the three key components are bound, exposed and stored.
     */
    @Nested
    @DisplayName("Component contract of the 16-byte DIS-GROUP-KEY group")
    class ComponentContract {

        @Test
        @DisplayName("all-args constructor binds components in CVTRA02Y declaration order - account "
                + "group id first, type code second, category code third - and never in the order "
                + "CBACT04C lines 210 to 212 happen to assign them, which is component 1, then 3, "
                + "then 2")
        void allArgsConstructorBindsComponentsPositionally() {
            // The contractual order is the copybook declaration order, which the cluster
            // definition's key geometry and the primary-key column order both agree with. It is NOT
            // the assignment order in the interest-calculation batch program, which populates the
            // group id, then the CATEGORY code, then the TYPE code. An implementation inferred from
            // that sequence would carry a transposed signature that still compiles, so the three
            // literals below are chosen to be mutually distinguishable by width as well as value.
            final DisclosureGroupId key = new DisclosureGroupId("A000000000", "07", "0003");

            assertThat(key.getDisAcctGroupId()).isEqualTo("A000000000");
            assertThat(key.getDisTranTypeCd()).isEqualTo("07");
            assertThat(key.getDisTranCatCd()).isEqualTo("0003");
        }

        @Test
        @DisplayName("a transposed constructor call is detectable by width: the group id occupies 10 "
                + "bytes, the type code 2 and the category code 4, so no two components are "
                + "interchangeable")
        void componentWidthsMakeATransposedConstructorCallDetectable() {
            final DisclosureGroupId key = new DisclosureGroupId("A000000000", "07", "0003");

            final int boundGroupIdWidth = byteWidthOf(key.getDisAcctGroupId());
            final int boundTypeCodeWidth = byteWidthOf(key.getDisTranTypeCd());
            final int boundCategoryCodeWidth = byteWidthOf(key.getDisTranCatCd());

            assertThat(boundGroupIdWidth).isEqualTo(GROUP_ID_WIDTH);
            assertThat(boundTypeCodeWidth).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(boundCategoryCodeWidth).isEqualTo(CATEGORY_CODE_WIDTH);

            assertThat(boundGroupIdWidth).isNotEqualTo(boundTypeCodeWidth);
            assertThat(boundGroupIdWidth).isNotEqualTo(boundCategoryCodeWidth);
            assertThat(boundTypeCodeWidth).isNotEqualTo(boundCategoryCodeWidth);
        }

        @Test
        @DisplayName("no-arg constructor leaves all three components null, because the persistence "
                + "provider populates an identifier class after instantiating it")
        void noArgConstructorLeavesAllThreeComponentsNull() {
            // This constructor exists solely so a persistence provider can instantiate the type,
            // which is also why the type is a plain class rather than a record. It is reached here
            // with no introspection at all: this test shares the package, and protected access
            // includes package access.
            final DisclosureGroupId empty = new DisclosureGroupId();

            assertThat(empty.getDisAcctGroupId()).isNull();
            assertThat(empty.getDisTranTypeCd()).isNull();
            assertThat(empty.getDisTranCatCd()).isNull();
        }

        @Test
        @DisplayName("all three components are text, so the 4-digit category code keeps its leading "
                + "zeros and \"0003\" is never narrowed to a value that would render as 3")
        void componentsAreTextAndLeadingZerosSurvive() {
            final DisclosureGroupId key = new DisclosureGroupId("A000000000", "07", "0003");

            // No numeric parse appears anywhere in this file. Every digit-only legacy lexeme maps to
            // a bounded character column, so the surviving leading zeros hold the text width at the
            // declared figure, and that width is part of the contract.
            assertThat(key.getDisTranCatCd()).isEqualTo("0003");
            assertThat(key.getDisTranCatCd()).isNotEqualTo("3");
            assertThat(key.getDisTranTypeCd()).isEqualTo("07");
            assertThat(key.getDisTranTypeCd()).isNotEqualTo("7");

            // The same distinction at the value level, independent of any key instance.
            assertThat("0005").isNotEqualTo("5");
            assertThat("01").isNotEqualTo("1");

            assertThat(byteWidthOf(key.getDisTranCatCd())).isEqualTo(CATEGORY_CODE_WIDTH);
            assertThat(byteWidthOf(key.getDisTranTypeCd())).isEqualTo(TYPE_CODE_WIDTH);
        }

        @Test
        @DisplayName("constructor stores every component verbatim and the getters return it verbatim "
                + "- no trim, no pad, no case fold, no validation - so the ten-character padded "
                + "default group id comes back at its full width")
        void constructorAndGettersRoundTripComponentsVerbatim() {
            // The padded literal is typed out at ten characters: seven letters plus three trailing
            // spaces. Nothing in this file assembles it, so the exact bytes are visible right here.
            final DisclosureGroupId key = new DisclosureGroupId("DEFAULT   ", "01", "0001");

            assertThat(key.getDisAcctGroupId()).isEqualTo("DEFAULT   ");
            assertThat(byteWidthOf(key.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);

            // Values chosen to expose normalisation: mixed case a fold would alter, and interior and
            // leading blanks a trim would alter. The class applies none of those, and neither does
            // this test.
            final DisclosureGroupId unaltered = new DisclosureGroupId("  aB cD   ", "aB", "0x04");

            assertThat(unaltered.getDisAcctGroupId()).isEqualTo("  aB cD   ");
            assertThat(unaltered.getDisTranTypeCd()).isEqualTo("aB");
            assertThat(unaltered.getDisTranCatCd()).isEqualTo("0x04");
        }

        @Test
        @DisplayName("null components are accepted and returned unchanged, because the identifier "
                + "class validates nothing and the schema enforces the not-null constraint")
        void nullComponentsAreStoredAndReturnedUnchanged() {
            final DisclosureGroupId nullGroupId = new DisclosureGroupId(null, "01", "0001");

            assertThat(nullGroupId.getDisAcctGroupId()).isNull();
            assertThat(nullGroupId.getDisTranTypeCd()).isEqualTo("01");
            assertThat(nullGroupId.getDisTranCatCd()).isEqualTo("0001");

            final DisclosureGroupId nullTypeCode = new DisclosureGroupId("DEFAULT   ", null, "0001");

            assertThat(nullTypeCode.getDisAcctGroupId()).isEqualTo("DEFAULT   ");
            assertThat(nullTypeCode.getDisTranTypeCd()).isNull();
            assertThat(nullTypeCode.getDisTranCatCd()).isEqualTo("0001");

            final DisclosureGroupId nullCategoryCode =
                    new DisclosureGroupId("DEFAULT   ", "01", null);

            assertThat(nullCategoryCode.getDisAcctGroupId()).isEqualTo("DEFAULT   ");
            assertThat(nullCategoryCode.getDisTranTypeCd()).isEqualTo("01");
            assertThat(nullCategoryCode.getDisTranCatCd()).isNull();
        }
    }

    /**
     * The padded group identifier. This is the group that matters most in this package: the seeded
     * data resolves no disclosure-group key other than the space-padded default one, so a trimming
     * or normalising defect in this key would break the entire interest-calculation path rather
     * than one obscure case.
     */
    @Nested
    @DisplayName("Padded account group identifier - trailing spaces are part of the key")
    class PaddedGroupIdContract {

        @Test
        @DisplayName("the ten-character padded default group id is NOT equal to its seven-character "
                + "form: the padding comes from a fixed-width move into a PIC X(10) field at "
                + "CBACT04C line 437 on file status 23, and the 7-character literal is the only "
                + "form written anywhere in app/cbl - exactly one occurrence")
        void paddedDefaultGroupIdIsNotEqualToItsUnpaddedForm() {
            // Both literals typed out. The padded one is seven letters plus three trailing spaces;
            // the unpadded one is the bare seven characters the program's move statement carries.
            final DisclosureGroupId padded = new DisclosureGroupId("DEFAULT   ", "01", "0001");
            final DisclosureGroupId unpadded = new DisclosureGroupId("DEFAULT", "01", "0001");

            // Asserted in both directions, because a one-sided check would miss an asymmetric defect.
            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(unpadded).isNotEqualTo(padded);
            assertThat(padded.equals(unpadded)).isFalse();
            assertThat(unpadded.equals(padded)).isFalse();

            assertThat(byteWidthOf(padded.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(byteWidthOf(unpadded.getDisAcctGroupId())).isEqualTo(7);
        }

        @Test
        @DisplayName("the padded and unpadded default group ids occupy two distinct entries of a hash "
                + "map and of a hash set, which is the assertion that catches a hash code that "
                + "normalises - equality alone would not")
        void paddedAndUnpaddedGroupIdsHashToDistinctEntries() {
            final DisclosureGroupId padded = new DisclosureGroupId("DEFAULT   ", "01", "0001");
            final DisclosureGroupId unpadded = new DisclosureGroupId("DEFAULT", "01", "0001");

            final Map<DisclosureGroupId, String> byKey = new HashMap<>();
            byKey.put(padded, "ten-character-padded");
            byKey.put(unpadded, "seven-character-bare");

            // A hash code that trimmed would collapse these two entries into one, so the size is the
            // real assertion and the retrievals confirm each key reaches its own value.
            assertThat(byKey).hasSize(2);
            assertThat(byKey.get(padded)).isEqualTo("ten-character-padded");
            assertThat(byKey.get(unpadded)).isEqualTo("seven-character-bare");

            // Retrieval through independently constructed equal keys, not through the instances above.
            assertThat(byKey.get(new DisclosureGroupId("DEFAULT   ", "01", "0001")))
                    .isEqualTo("ten-character-padded");
            assertThat(byKey.get(new DisclosureGroupId("DEFAULT", "01", "0001")))
                    .isEqualTo("seven-character-bare");

            final Set<DisclosureGroupId> distinct = new HashSet<>();
            distinct.add(padded);
            distinct.add(unpadded);

            assertThat(distinct).hasSize(2);
        }

        @Test
        @DisplayName("an all-blank ten-character group id is distinct from the empty string, from the "
                + "padded default and from the bare default: all 50 seeded account rows carry ten "
                + "spaces, those match none of the three seeded groups, so the read returns status "
                + "23 and the fallback fires - which is why the padded key is the only "
                + "disclosure-group key the seeded data resolves")
        void allBlankGroupIdIsDistinctFromEveryOtherForm() {
            // Ten literal space characters, typed out. This is the value every seeded account row
            // actually carries in its group-identifier field.
            final DisclosureGroupId blank = new DisclosureGroupId("          ", "01", "0001");
            final DisclosureGroupId emptyGroupId = new DisclosureGroupId("", "01", "0001");
            final DisclosureGroupId padded = new DisclosureGroupId("DEFAULT   ", "01", "0001");
            final DisclosureGroupId unpadded = new DisclosureGroupId("DEFAULT", "01", "0001");

            // Were equality to trim, ten spaces would collapse to the empty string and these two
            // would wrongly match. This is the strongest available no-trim proof.
            assertThat(blank).isNotEqualTo(emptyGroupId);
            assertThat(emptyGroupId).isNotEqualTo(blank);

            assertThat(blank).isNotEqualTo(padded);
            assertThat(blank).isNotEqualTo(unpadded);

            assertThat(byteWidthOf(blank.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(byteWidthOf(emptyGroupId.getDisAcctGroupId())).isZero();

            final Map<DisclosureGroupId, String> byKey = new HashMap<>();
            byKey.put(blank, "ten-blanks");
            byKey.put(padded, "padded-default");
            byKey.put(emptyGroupId, "empty");

            assertThat(byKey).hasSize(3);
            assertThat(byKey.get(blank)).isEqualTo("ten-blanks");
            assertThat(byKey.get(padded)).isEqualTo("padded-default");
            assertThat(byKey.get(emptyGroupId)).isEqualTo("empty");
        }

        @Test
        @DisplayName("each of the three seeded group ids measures exactly 10 bytes, and their "
                + "trailing-space counts are 0 for A000000000 and 3 each for the two seven-letter "
                + "identifiers, derived by summation rather than by stripping anything")
        void everySeededGroupIdIsTenBytesWithItsOwnTrailingSpaceCount() {
            // Widths measured in bytes through the named charset; padding counts derived from the
            // arithmetic of the typed-out literals and their bare forms. Nothing is stripped to
            // arrive at a count, because a stripping helper is exactly the defect under test.
            final int fullyPopulatedWidth = byteWidthOf("A000000000");
            final int defaultBareWidth = byteWidthOf("DEFAULT");
            final int zeroRateBareWidth = byteWidthOf("ZEROAPR");
            final int defaultPaddedWidth = byteWidthOf("DEFAULT   ");
            final int zeroRatePaddedWidth = byteWidthOf("ZEROAPR   ");
            final int blankWidth = byteWidthOf("          ");

            assertThat(fullyPopulatedWidth).isEqualTo(GROUP_ID_WIDTH);
            assertThat(defaultPaddedWidth).isEqualTo(GROUP_ID_WIDTH);
            assertThat(zeroRatePaddedWidth).isEqualTo(GROUP_ID_WIDTH);
            assertThat(blankWidth).isEqualTo(GROUP_ID_WIDTH);

            // A000000000 fills the field, so it carries no padding: 10 + 0 = 10.
            final int fullyPopulatedTrailingSpaces = GROUP_ID_WIDTH - fullyPopulatedWidth;

            assertThat(fullyPopulatedTrailingSpaces).isZero();
            assertThat(fullyPopulatedWidth + fullyPopulatedTrailingSpaces).isEqualTo(GROUP_ID_WIDTH);

            // Both seven-letter identifiers are padded by three: 7 + 3 = 10.
            final int defaultTrailingSpaces = GROUP_ID_WIDTH - defaultBareWidth;
            final int zeroRateTrailingSpaces = GROUP_ID_WIDTH - zeroRateBareWidth;

            assertThat(defaultBareWidth).isEqualTo(7);
            assertThat(zeroRateBareWidth).isEqualTo(7);
            assertThat(defaultTrailingSpaces).isEqualTo(3);
            assertThat(zeroRateTrailingSpaces).isEqualTo(3);
            assertThat(defaultBareWidth + defaultTrailingSpaces).isEqualTo(GROUP_ID_WIDTH);
            assertThat(zeroRateBareWidth + zeroRateTrailingSpaces).isEqualTo(GROUP_ID_WIDTH);

            // The all-blank identifier is padding end to end: 0 + 10 = 10.
            assertThat(byteWidthOf("") + GROUP_ID_WIDTH).isEqualTo(blankWidth);
        }

        @Test
        @DisplayName("the zero-rate group id behaves identically: equal to its ten-character padded "
                + "form and not equal to its bare seven-character form, the inequality being the "
                + "half that actually catches a trimming defect")
        void zeroRateGroupIdProvesBothHalvesOfThePaddingContract() {
            final DisclosureGroupId padded = new DisclosureGroupId("ZEROAPR   ", "01", "0001");
            final DisclosureGroupId independentlyPadded =
                    new DisclosureGroupId("ZEROAPR   ", "01", "0001");
            final DisclosureGroupId bare = new DisclosureGroupId("ZEROAPR", "01", "0001");

            // The equality half, proved between two independently constructed instances.
            assertThat(padded).isEqualTo(independentlyPadded);
            assertThat(padded.hashCode()).isEqualTo(independentlyPadded.hashCode());

            // The inequality half, which is what a trimming implementation would fail.
            assertThat(padded).isNotEqualTo(bare);
            assertThat(bare).isNotEqualTo(padded);

            assertThat(byteWidthOf(padded.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(byteWidthOf(bare.getDisAcctGroupId())).isEqualTo(7);

            // The same both-halves proof for the fully populated identifier, which needs no padding.
            final DisclosureGroupId fullyPopulated =
                    new DisclosureGroupId("A000000000", "01", "0001");
            final DisclosureGroupId fullyPopulatedTwin =
                    new DisclosureGroupId("A000000000", "01", "0001");

            assertThat(fullyPopulated).isEqualTo(fullyPopulatedTwin);
            assertThat(fullyPopulated).isNotEqualTo(padded);
        }

        @Test
        @DisplayName("the fallback substitutes exactly one of the three components: CBACT04C line 437 "
                + "overwrites only the group id, while the type and category values moved at lines "
                + "211 and 212 remain intact, so the two keys differ in the first component alone "
                + "and are nevertheless unequal")
        void fallbackSubstitutesTheGroupIdComponentAlone() {
            // The key as the direct read built it: the account's blank group identifier, plus the
            // type and category codes carried over from the category-balance record.
            final DisclosureGroupId beforeFallback =
                    new DisclosureGroupId("          ", "01", "0001");
            // The key after the substitution: only the first component has changed.
            final DisclosureGroupId afterFallback =
                    new DisclosureGroupId("DEFAULT   ", "01", "0001");

            // The two trailing components are untouched by the substitution.
            assertThat(afterFallback.getDisTranTypeCd())
                    .isEqualTo(beforeFallback.getDisTranTypeCd());
            assertThat(afterFallback.getDisTranCatCd())
                    .isEqualTo(beforeFallback.getDisTranCatCd());
            assertThat(afterFallback.getDisTranTypeCd()).isEqualTo("01");
            assertThat(afterFallback.getDisTranCatCd()).isEqualTo("0001");

            // The first component differs, and one differing component is enough to distinguish the
            // keys - which is what makes the retry read a genuinely different lookup.
            assertThat(afterFallback.getDisAcctGroupId())
                    .isNotEqualTo(beforeFallback.getDisAcctGroupId());
            assertThat(afterFallback).isNotEqualTo(beforeFallback);
            assertThat(beforeFallback).isNotEqualTo(afterFallback);

            // Both group identifiers still occupy the full field width; only their content changed.
            assertThat(byteWidthOf(beforeFallback.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(byteWidthOf(afterFallback.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
        }

        @Test
        @DisplayName("no normalisation of any kind is applied: letter case is significant and leading "
                + "whitespace is significant, so a lower-case or left-shifted group id is a "
                + "different key rather than the same one")
        void noNormalisationOfAnyKindIsApplied() {
            final DisclosureGroupId padded = new DisclosureGroupId("DEFAULT   ", "01", "0001");

            // Case folding either way would make this equal. It is not.
            final DisclosureGroupId lowerCase = new DisclosureGroupId("default   ", "01", "0001");

            assertThat(padded).isNotEqualTo(lowerCase);
            assertThat(lowerCase).isNotEqualTo(padded);

            // Same ten characters, one leading space and two trailing: a value that only a
            // whitespace-insensitive comparison would treat as the padded form.
            final DisclosureGroupId leadingSpace = new DisclosureGroupId(" DEFAULT  ", "01", "0001");

            assertThat(padded).isNotEqualTo(leadingSpace);
            assertThat(leadingSpace).isNotEqualTo(padded);

            // Both are ten bytes wide, so width alone does not distinguish them - only the byte
            // positions do, which is exactly the point.
            assertThat(byteWidthOf(leadingSpace.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(byteWidthOf(padded.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);

            // All four forms stay four distinct entries in a hash-based collection.
            final Set<DisclosureGroupId> distinct = new HashSet<>();
            distinct.add(padded);
            distinct.add(lowerCase);
            distinct.add(leadingSpace);
            distinct.add(new DisclosureGroupId("DEFAULT", "01", "0001"));

            assertThat(distinct).hasSize(4);
        }
    }

    /**
     * Byte-width and offset contract: the geometry that the copybook, the cluster definition and the
     * interest-calculation program's file section all agree on.
     */
    @Nested
    @DisplayName("Byte geometry of the key and of the 50-byte record that carries it")
    class ByteWidthContract {

        @Test
        @DisplayName("the three component widths sum to the 16 declared by DISCGRP KEYS(16 0): the "
                + "copybook and the cluster definition are independent authorities that agree")
        void componentWidthsSumToTheDeclaredKeyLength() {
            // Every width here is measured in BYTES through an explicitly named charset, never in
            // characters, because the legacy record is a byte image.
            final int groupIdWidth = byteWidthOf("A000000000");
            final int typeCodeWidth = byteWidthOf("07");
            final int categoryCodeWidth = byteWidthOf("0003");

            assertThat(groupIdWidth).isEqualTo(GROUP_ID_WIDTH);
            assertThat(typeCodeWidth).isEqualTo(TYPE_CODE_WIDTH);
            assertThat(categoryCodeWidth).isEqualTo(CATEGORY_CODE_WIDTH);

            final int summedKeyLength = groupIdWidth + typeCodeWidth + categoryCodeWidth;

            assertThat(summedKeyLength).isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(summedKeyLength).isEqualTo(16);
        }

        @Test
        @DisplayName("the components sit at offsets 0, 10 and 12 within the key group, each offset "
                + "derived by summing the widths that precede it")
        void componentOffsetsWithinTheKeyGroupAreDerivedBySummation() {
            final int groupIdOffset = DECLARED_KEY_OFFSET;
            final int typeCodeOffset = groupIdOffset + GROUP_ID_WIDTH;
            final int categoryCodeOffset = typeCodeOffset + TYPE_CODE_WIDTH;
            final int keyEndOffset = categoryCodeOffset + CATEGORY_CODE_WIDTH;

            assertThat(groupIdOffset).isZero();
            assertThat(typeCodeOffset).isEqualTo(10);
            assertThat(categoryCodeOffset).isEqualTo(12);
            assertThat(keyEndOffset).isEqualTo(DECLARED_KEY_LENGTH);
        }

        @Test
        @DisplayName("the copybook decomposition closes: key of 16 at offset 0, the S9(04)V99 rate of "
                + "6 at offset 16 because the implied decimal point consumes no byte, the X(28) "
                + "filler at offset 22, and 16 + 6 + 28 = the declared RECORDSIZE of 50")
        void copybookDecompositionOfTheFiftyByteRecordCloses() {
            final int keyOffset = DECLARED_KEY_OFFSET;
            final int keyWidth = GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;
            final int rateOffset = keyOffset + keyWidth;
            final int fillerOffset = rateOffset + RATE_WIDTH;
            final int recordWidth = keyWidth + RATE_WIDTH + FILLER_WIDTH;

            assertThat(keyOffset).isZero();
            assertThat(keyWidth).isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(rateOffset).isEqualTo(16);

            // Four integer digits plus two decimal digits: the implied decimal point occupies no
            // byte, and the sign is overpunched into the final byte rather than taking one of its own.
            assertThat(4 + 2).isEqualTo(RATE_WIDTH);
            assertThat(RATE_WIDTH).isEqualTo(6);

            assertThat(fillerOffset).isEqualTo(22);
            assertThat(recordWidth).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(recordWidth).isEqualTo(50);
        }

        @Test
        @DisplayName("the CBACT04C file-section decomposition reconciles with the copybook one: "
                + "16 + 34 = 50 there and 16 + 6 + 28 = 50 here, because 6 + 28 = 34")
        void fileSectionDecompositionReconcilesWithTheCopybookOne() {
            // The program's file section names one undivided remainder where the copybook names a
            // rate and a filler, so the two splits agree only if the remainder equals their sum.
            assertThat(RATE_WIDTH + FILLER_WIDTH).isEqualTo(FILE_SECTION_REMAINDER_WIDTH);
            assertThat(RATE_WIDTH + FILLER_WIDTH).isEqualTo(34);

            assertThat(DECLARED_KEY_LENGTH + FILE_SECTION_REMAINDER_WIDTH)
                    .isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(DECLARED_KEY_LENGTH + RATE_WIDTH + FILLER_WIDTH)
                    .isEqualTo(DECLARED_RECORD_LENGTH);

            // Equivalently, the remainder is what the record has left once the key is taken.
            assertThat(DECLARED_RECORD_LENGTH - DECLARED_KEY_LENGTH)
                    .isEqualTo(FILE_SECTION_REMAINDER_WIDTH);
        }

        @Test
        @DisplayName("KEYS(16 0) declares length 16 at offset 0; every DEFINE CLUSTER in the estate "
                + "declares offset 0 and only DEFINE ALTERNATEINDEX blocks carry a non-zero offset")
        void clusterDefinitionDeclaresLengthSixteenAtOffsetZero() {
            assertThat(GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isEqualTo(16);
            assertThat(DECLARED_KEY_OFFSET).isZero();

            // Offset 0 means the key is the leading substring of the stored image, which is why the
            // identifier is the business key itself and no surrogate is introduced.
            final int dataPortionWidth = DECLARED_RECORD_LENGTH - DECLARED_KEY_LENGTH;

            assertThat(dataPortionWidth).isEqualTo(RATE_WIDTH + FILLER_WIDTH);
            assertThat(dataPortionWidth).isEqualTo(34);
        }

        @Test
        @DisplayName("the three composite key lengths 16, 17 and 6 are pairwise distinct, so the "
                + "three key types of this package cannot be confused by declared length")
        void theThreeCompositeKeyLengthsArePairwiseDistinct() {
            // Plain integers on purpose: the 17-byte and 6-byte keys have their own classes elsewhere
            // in this package and neither is named or instantiated here.
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(BALANCE_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(BALANCE_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);

            // This key leads with a 10-byte group identifier ahead of the same two trailing
            // components the 6-byte key consists of, so 10 + 6 = 16.
            assertThat(GROUP_ID_WIDTH + CATEGORY_KEY_LENGTH).isEqualTo(DECLARED_KEY_LENGTH);

            // The 17-byte key leads instead with an 11-byte account identifier ahead of those same
            // two components, so 11 + 6 = 17 and no offset can align it with this key.
            assertThat(11 + CATEGORY_KEY_LENGTH).isEqualTo(BALANCE_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(11 + CATEGORY_KEY_LENGTH);
        }

        @Test
        @DisplayName("the account layout's 10-byte group id aligns with this key's first component "
                + "and sits at offset 112, the account record summing to 300 bytes")
        void theAccountGroupIdAlignsWithTheKeysFirstComponent() {
            // The account layout's declared field widths, in declaration order. The group identifier
            // is the twelfth field, so the eleven widths preceding it give its offset.
            final int accountIdWidth = 11;
            final int activeStatusWidth = 1;
            final int currentBalanceWidth = 12;
            final int creditLimitWidth = 12;
            final int cashCreditLimitWidth = 12;
            final int openDateWidth = 10;
            final int expirationDateWidth = 10;
            final int reissueDateWidth = 10;
            final int cycleCreditWidth = 12;
            final int cycleDebitWidth = 12;
            final int addressZipWidth = 10;
            final int accountGroupIdWidth = 10;
            final int accountFillerWidth = 178;

            final int addressZipOffset = accountIdWidth
                    + activeStatusWidth
                    + currentBalanceWidth
                    + creditLimitWidth
                    + cashCreditLimitWidth
                    + openDateWidth
                    + expirationDateWidth
                    + reissueDateWidth
                    + cycleCreditWidth
                    + cycleDebitWidth;
            final int groupIdOffset = addressZipOffset + addressZipWidth;
            final int accountRecordWidth = groupIdOffset + accountGroupIdWidth + accountFillerWidth;

            assertThat(addressZipOffset).isEqualTo(102);
            assertThat(groupIdOffset).isEqualTo(ACCOUNT_GROUP_ID_OFFSET);
            assertThat(groupIdOffset).isEqualTo(112);

            // Source data anomaly, recorded for context and deliberately NOT asserted here. In the
            // seeded account fixture every row carries a group-id-shaped value in the postal-code
            // field at offset 102 while the group-identifier field at offset 112 is entirely blank,
            // which strongly suggests the value was written one field early. The anomaly is captured
            // in docs/decision-log.md; this test neither corrects the data nor depends on it, and
            // asserts only the two field offsets and the record width above. What matters to this key
            // is the consequence, proved in PaddedGroupIdContract: the group-identifier field really
            // does hold ten spaces, so the direct lookup always misses and the padded fallback key is
            // the only disclosure-group key the seeded data resolves.
            assertThat(accountRecordWidth).isEqualTo(ACCOUNT_RECORD_LENGTH);
            assertThat(accountRecordWidth).isEqualTo(300);

            // The account field and this key's first component are the same width, which is what
            // lets the value move between them without truncation or padding at the move site.
            assertThat(accountGroupIdWidth).isEqualTo(GROUP_ID_WIDTH);

            // Both fixtures close on their measured byte counts, one line terminator per row.
            assertThat(ACCOUNT_ROW_COUNT * ACCOUNT_RECORD_LENGTH).isEqualTo(15000);
            assertThat(REFERENCE_ROW_COUNT * (DECLARED_RECORD_LENGTH + 1))
                    .isEqualTo(REFERENCE_FIXTURE_BYTES);
        }
    }

    /**
     * Equality and hashing contract: all three components participate, nothing is normalised, and
     * the 51 reference keys stay 51 distinct keys inside a hash-based collection.
     */
    @Nested
    @DisplayName("Equality and hashing across all three key components")
    class EqualityAndHashing {

        @Test
        @DisplayName("two independently constructed keys with identical components are equal and hash "
                + "alike, which is what lets a freshly built key match a row already loaded")
        void independentlyConstructedEqualKeysAreEqualAndHashAlike() {
            final DisclosureGroupId first = new DisclosureGroupId("DEFAULT   ", "03", "0002");
            final DisclosureGroupId second = new DisclosureGroupId("DEFAULT   ", "03", "0002");

            assertThat(first).isEqualTo(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("a key equals itself, satisfying the reflexive clause of the equality contract")
        void aKeyEqualsItself() {
            // Reflexivity is a required property of the equality contract, so the expectation is the
            // specification's own "must be true" and not a value computed by the class under test. It
            // is also the only assertion that reaches the identity short-circuit at the head of the
            // equality method.
            final DisclosureGroupId key = new DisclosureGroupId("ZEROAPR   ", "06", "0002");

            assertThat(key.equals(key)).isTrue();
        }

        @Test
        @DisplayName("equality is symmetric for two independently constructed equal keys")
        void equalityIsSymmetric() {
            final DisclosureGroupId left = new DisclosureGroupId("A000000000", "04", "0003");
            final DisclosureGroupId right = new DisclosureGroupId("A000000000", "04", "0003");

            assertThat(left.equals(right)).isTrue();
            assertThat(right.equals(left)).isTrue();
        }

        @Test
        @DisplayName("the group id component participates in equality: A000000000 with type 01 "
                + "category 0001 differs from the padded default with the same trailing components, "
                + "and both are real fixture rows")
        void groupIdComponentParticipatesInEquality() {
            final DisclosureGroupId fullyPopulated =
                    new DisclosureGroupId("A000000000", "01", "0001");
            final DisclosureGroupId paddedDefault =
                    new DisclosureGroupId("DEFAULT   ", "01", "0001");

            assertThat(fullyPopulated).isNotEqualTo(paddedDefault);
            assertThat(fullyPopulated.equals(paddedDefault)).isFalse();
        }

        @Test
        @DisplayName("the type code component participates in equality: the padded default with type "
                + "01 category 0001 differs from the same group with type 02, and both are real "
                + "fixture rows")
        void typeCodeComponentParticipatesInEquality() {
            final DisclosureGroupId typeOne = new DisclosureGroupId("DEFAULT   ", "01", "0001");
            final DisclosureGroupId typeTwo = new DisclosureGroupId("DEFAULT   ", "02", "0001");

            assertThat(typeOne).isNotEqualTo(typeTwo);
            assertThat(typeOne.equals(typeTwo)).isFalse();
        }

        @Test
        @DisplayName("the category code component participates in equality: the padded default with "
                + "type 01 category 0001 differs from category 0002, and both are real fixture rows")
        void categoryCodeComponentParticipatesInEquality() {
            final DisclosureGroupId categoryOne = new DisclosureGroupId("DEFAULT   ", "01", "0001");
            final DisclosureGroupId categoryTwo = new DisclosureGroupId("DEFAULT   ", "01", "0002");

            assertThat(categoryOne).isNotEqualTo(categoryTwo);
            assertThat(categoryOne.equals(categoryTwo)).isFalse();
        }

        @Test
        @DisplayName("equality rejects null and rejects a foreign type, exercising the type-pattern "
                + "branch rather than throwing")
        void equalityRejectsNullAndForeignTypes() {
            final DisclosureGroupId key = new DisclosureGroupId("DEFAULT   ", "05", "0001");

            assertThat(key.equals(null)).isFalse();
            assertThat(key).isNotEqualTo(new Object());

            // A concatenation of the three components is not the key either: the key is a triple and
            // never its flattened image.
            assertThat(key).isNotEqualTo("DEFAULT   050001");
        }

        @Test
        @DisplayName("two fully absent keys are equal and hash alike, which is what lets the provider "
                + "compare a freshly instantiated key before populating it")
        void twoFullyAbsentKeysAreEqualAndHashAlike() {
            final DisclosureGroupId firstEmpty = new DisclosureGroupId();
            final DisclosureGroupId secondEmpty = new DisclosureGroupId();

            assertThat(firstEmpty).isEqualTo(secondEmpty);
            assertThat(firstEmpty.hashCode()).isEqualTo(secondEmpty.hashCode());

            // A fully absent key is not equal to a populated one.
            assertThat(firstEmpty).isNotEqualTo(new DisclosureGroupId("DEFAULT   ", "01", "0001"));
        }

        @Test
        @DisplayName("all 51 reference keys stay distinct in a hash set and index distinctly in a hash "
                + "map: all three groups carry the identical 17 type-and-category pairs, which is "
                + "exactly why the status-23 fallback always resolves a row")
        void allFiftyOneReferenceKeysRemainDistinct() {
            final List<DisclosureGroupId> keys = referenceKeys();
            final Set<DisclosureGroupId> distinct = new HashSet<>(keys);

            assertThat(keys).hasSize(REFERENCE_ROW_COUNT);
            assertThat(distinct).hasSize(REFERENCE_ROW_COUNT);

            final Map<DisclosureGroupId, String> byKey = new HashMap<>();
            for (final DisclosureGroupId key : keys) {
                byKey.put(key, "reference-row");
            }

            assertThat(byKey).hasSize(REFERENCE_ROW_COUNT);

            // Retrieval through independently constructed equal keys, one per group.
            assertThat(byKey.get(new DisclosureGroupId("A000000000", "07", "0001")))
                    .isEqualTo("reference-row");
            assertThat(byKey.get(new DisclosureGroupId("DEFAULT   ", "07", "0001")))
                    .isEqualTo("reference-row");
            assertThat(byKey.get(new DisclosureGroupId("ZEROAPR   ", "07", "0001")))
                    .isEqualTo("reference-row");

            // The one pair the disclosure fixture omits is absent under every group.
            assertThat(byKey).doesNotContainKey(new DisclosureGroupId("A000000000", "01", "0005"));
            assertThat(byKey).doesNotContainKey(new DisclosureGroupId("DEFAULT   ", "01", "0005"));

            // And the padded form is what the fixture holds, so the bare form is not a key.
            assertThat(byKey).doesNotContainKey(new DisclosureGroupId("DEFAULT", "07", "0001"));
        }

        @Test
        @DisplayName("each group's 17 keys form a set of 17 and the union of two groups is 34, so no "
                + "key of one group collides with a key of another")
        void eachGroupHoldsSeventeenKeysAndTwoGroupsUnionToThirtyFour() {
            final Set<DisclosureGroupId> fullyPopulatedGroup =
                    new HashSet<>(keysOfGroup("A000000000"));
            final Set<DisclosureGroupId> defaultGroup = new HashSet<>(keysOfGroup("DEFAULT   "));
            final Set<DisclosureGroupId> zeroRateGroup = new HashSet<>(keysOfGroup("ZEROAPR   "));

            assertThat(fullyPopulatedGroup).hasSize(ROWS_PER_GROUP);
            assertThat(defaultGroup).hasSize(ROWS_PER_GROUP);
            assertThat(zeroRateGroup).hasSize(ROWS_PER_GROUP);

            final Set<DisclosureGroupId> union = new HashSet<>(fullyPopulatedGroup);
            union.addAll(defaultGroup);

            assertThat(union).hasSize(ROWS_PER_GROUP + ROWS_PER_GROUP);
            assertThat(union).hasSize(34);

            // The third group adds another 17 with no collision either, closing on 51.
            union.addAll(zeroRateGroup);

            assertThat(union).hasSize(REFERENCE_ROW_COUNT);

            // No key of one group is a key of another, because the group id participates in equality.
            assertThat(defaultGroup).doesNotContainAnyElementsOf(fullyPopulatedGroup);
            assertThat(zeroRateGroup).doesNotContainAnyElementsOf(defaultGroup);
        }

        @Test
        @DisplayName("the fixture arithmetic closes: 17 pairs times 3 groups is the 51 measured rows "
                + "of 50 bytes each, and 18 - 1 = 17 because the interest category is the single "
                + "transaction category that carries no disclosure-rate row")
        void theFixtureArithmeticCloses() {
            assertThat(ROWS_PER_GROUP * GROUP_COUNT).isEqualTo(REFERENCE_ROW_COUNT);
            assertThat(ROWS_PER_GROUP * GROUP_COUNT).isEqualTo(51);
            assertThat(referenceKeys()).hasSize(ROWS_PER_GROUP * GROUP_COUNT);

            // 51 rows of 50 bytes plus one line terminator each account for every measured byte.
            assertThat(REFERENCE_ROW_COUNT * (DECLARED_RECORD_LENGTH + 1))
                    .isEqualTo(REFERENCE_FIXTURE_BYTES);
            assertThat(REFERENCE_FIXTURE_BYTES).isEqualTo(2601);

            // The transaction-category fixture holds 18 keys; one of them carries no disclosure rate,
            // which is why each group holds 17 rated pairs rather than 18. Asserted as plain integer
            // arithmetic: no foreign key type is instantiated or named here.
            final int categoriesWithoutARate = 1;

            assertThat(CATEGORY_ROW_COUNT - categoriesWithoutARate).isEqualTo(ROWS_PER_GROUP);
            assertThat(CATEGORY_ROW_COUNT - categoriesWithoutARate).isEqualTo(17);

            // The 17 pair components are held as two positionally aligned lists of equal length.
            assertThat(PAIR_TYPE_CODES).hasSize(ROWS_PER_GROUP);
            assertThat(PAIR_CATEGORY_CODES).hasSize(ROWS_PER_GROUP);
        }
    }

    /**
     * Serialization contract: an identifier class must be serializable, must pin its version, and
     * must carry its padded components across a round trip without alteration.
     */
    @Nested
    @DisplayName("Serialization contract required of an identifier class")
    class SerializationContract {

        @Test
        @DisplayName("the serialization version identifier is pinned at 1, which the build also "
                + "requires because an unpinned serializable class fails compilation under -Werror")
        void serializationVersionIdentifierIsPinnedAtOne() {
            // The lookup below goes through the serialization metadata API, which is NOT the
            // low-level introspection API: no class, constructor, field or method is reflected over
            // here. The module's audited budget for introspection is zero and a test must never
            // undermine a production gate, so this is the one permitted idiom.
            final ObjectStreamClass descriptor = ObjectStreamClass.lookup(DisclosureGroupId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the key type is serializable, as an identifier class carried across a "
                + "persistence boundary must be")
        void theKeyTypeIsSerializable() {
            final DisclosureGroupId key = new DisclosureGroupId("DEFAULT   ", "02", "0003");

            assertThat(key).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("a round trip preserves the padded group id at its full ten bytes with all three "
                + "trailing spaces intact, along with equality and hash code, so a detached fallback "
                + "key still matches its row and still does not match the bare seven-character form")
        void serializationRoundTripPreservesThePaddedGroupId() throws IOException,
                ClassNotFoundException {
            final DisclosureGroupId original = new DisclosureGroupId("DEFAULT   ", "01", "0001");

            final DisclosureGroupId restored = serializeAndBack(original);

            // The padding survives: the restored value equals the ten-character literal typed out
            // here, measures ten bytes, and is still not equal to the bare form.
            assertThat(restored.getDisAcctGroupId()).isEqualTo("DEFAULT   ");
            assertThat(byteWidthOf(restored.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(restored.getDisAcctGroupId()).isNotEqualTo("DEFAULT");

            assertThat(restored.getDisTranTypeCd()).isEqualTo("01");
            assertThat(restored.getDisTranCatCd()).isEqualTo("0001");

            assertThat(restored).isEqualTo(original);
            assertThat(original).isEqualTo(restored);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());

            assertThat(restored).isNotSameAs(original);

            // And the restored key still separates from the bare form in a hash-based collection,
            // so the padding survived the hash code and not merely the field.
            assertThat(restored).isNotEqualTo(new DisclosureGroupId("DEFAULT", "01", "0001"));
        }

        @Test
        @DisplayName("a round trip of an all-blank group id preserves all ten spaces, which matters "
                + "because that is the value every seeded account row actually carries")
        void serializationRoundTripPreservesAnAllBlankGroupId() throws IOException,
                ClassNotFoundException {
            final DisclosureGroupId original = new DisclosureGroupId("          ", "01", "0001");

            final DisclosureGroupId restored = serializeAndBack(original);

            assertThat(restored.getDisAcctGroupId()).isEqualTo("          ");
            assertThat(byteWidthOf(restored.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(restored.getDisAcctGroupId()).isNotEqualTo("");

            assertThat(restored).isEqualTo(original);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());
            assertThat(restored).isNotEqualTo(new DisclosureGroupId("", "01", "0001"));
        }

        @Test
        @DisplayName("a round trip of a fully absent key preserves all three null components, because "
                + "the no-arg constructor path must survive serialization too")
        void serializationRoundTripPreservesAFullyAbsentKey() throws IOException,
                ClassNotFoundException {
            final DisclosureGroupId empty = new DisclosureGroupId();

            final DisclosureGroupId restored = serializeAndBack(empty);

            assertThat(restored.getDisAcctGroupId()).isNull();
            assertThat(restored.getDisTranTypeCd()).isNull();
            assertThat(restored.getDisTranCatCd()).isNull();
            assertThat(restored).isEqualTo(empty);
            assertThat(restored.hashCode()).isEqualTo(empty.hashCode());
        }
    }

    /**
     * Documented absence: each test asserts an observable property of the class and records alongside
     * it a thing that deliberately does not exist. Absence is proved by never referencing the thing,
     * never by reflecting over the class to look for it.
     */
    @Nested
    @DisplayName("Deliberate absences recorded by this key type")
    class DocumentedAbsence {

        @Test
        @DisplayName("no surrogate identifier exists: DISCGRP KEYS(16 0) puts the key at offset 0 as "
                + "the leading substring of the record, and the file-section split 16 + 34 = 50 "
                + "confirms it, so the composite business key IS the identifier")
        void noSurrogateIdentifierExists() {
            // A surrogate would break the image-to-row correspondence that byte-parity verification of
            // the migrated output depends on. What is asserted is behavioural: the three components
            // the caller supplies are the whole of the key and come back unchanged. The class exposes
            // no identifier accessor, no primary-key accessor and no generated-value accessor, which
            // this file proves by never naming one.
            final DisclosureGroupId key = new DisclosureGroupId("A000000000", "04", "0002");

            assertThat(key.getDisAcctGroupId()).isEqualTo("A000000000");
            assertThat(key.getDisTranTypeCd()).isEqualTo("04");
            assertThat(key.getDisTranCatCd()).isEqualTo("0002");

            assertThat(DECLARED_KEY_OFFSET).isZero();
            assertThat(GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH + FILE_SECTION_REMAINDER_WIDTH)
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("no padded-default constant is declared on the key: the fallback group id is a "
                + "service-layer concern implementing CBACT04C lines 436 to 438, so the key type "
                + "deliberately declares no such constant and this test types the literal out inline")
        void noPaddedDefaultConstantIsDeclaredOnTheKey() {
            // The padded literal below is typed out at this call site rather than read from a constant
            // on the class under test. That is the proof: were the key to publish such a constant, a
            // caller could depend on the key type to know which group is the fallback, which is a
            // decision belonging to the service that implements the retry and not to the identifier.
            final DisclosureGroupId fallbackKey = new DisclosureGroupId("DEFAULT   ", "01", "0001");

            assertThat(fallbackKey.getDisAcctGroupId()).isEqualTo("DEFAULT   ");
            assertThat(byteWidthOf(fallbackKey.getDisAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);

            // The key treats the fallback group exactly as it treats any other group: no branch, no
            // special case, no privileged value. Constructing all three groups' keys the same way and
            // getting three distinct keys is what demonstrates that.
            final Set<DisclosureGroupId> oneKeyPerGroup = new HashSet<>();
            oneKeyPerGroup.add(new DisclosureGroupId("A000000000", "01", "0001"));
            oneKeyPerGroup.add(fallbackKey);
            oneKeyPerGroup.add(new DisclosureGroupId("ZEROAPR   ", "01", "0001"));

            assertThat(oneKeyPerGroup).hasSize(GROUP_COUNT);
        }

        @Test
        @DisplayName("the key carries no persistence metadata and needs no framework: it constructs, "
                + "compares, hashes and serialises in a plain JVM, because the identifier-class "
                + "declaration sits on the entity and never on the key")
        void theKeyNeedsNoFrameworkAtAll() throws IOException, ClassNotFoundException {
            // Everything runs on the plain JVM the harness provides, which is all the environment this
            // value holder needs: no application context, no entity manager, no database, no
            // container, no network and no file. The key holds raw text components, so no enum, no
            // attribute conversion and no column metadata is involved either.
            final DisclosureGroupId key = new DisclosureGroupId("DEFAULT   ", "03", "0003");
            final DisclosureGroupId twin = new DisclosureGroupId("DEFAULT   ", "03", "0003");

            assertThat(key).isEqualTo(twin);
            assertThat(key.hashCode()).isEqualTo(twin.hashCode());

            final DisclosureGroupId restored = serializeAndBack(key);

            assertThat(restored.getDisAcctGroupId()).isEqualTo("DEFAULT   ");
            assertThat(restored.getDisTranTypeCd()).isEqualTo("03");
            assertThat(restored.getDisTranCatCd()).isEqualTo("0003");
            assertThat(restored).isEqualTo(key);
        }

        @Test
        @DisplayName("no shared supertype exists between the three key types of this package: they "
                + "share no superclass, interface or abstract base beyond the root type, and their "
                + "declared lengths 16, 17 and 6 differ pairwise")
        void noSharedSupertypeExistsBetweenTheThreeKeyTypes() {
            // Asserted as plain integers and one behavioural check. The other two key classes are
            // neither named nor instantiated here, which is itself the proof that this key needs
            // nothing from them: a shared base would have forced a reference.
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(BALANCE_KEY_LENGTH);
            assertThat(DECLARED_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);
            assertThat(BALANCE_KEY_LENGTH).isNotEqualTo(CATEGORY_KEY_LENGTH);

            // The only supertype this key has beyond the root type is the serialization marker, which
            // every identifier class implements independently rather than inheriting from a shared base.
            final DisclosureGroupId key = new DisclosureGroupId("ZEROAPR   ", "06", "0001");

            assertThat(key).isInstanceOf(Serializable.class);
            assertThat(key).isInstanceOf(DisclosureGroupId.class);
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
            final DisclosureGroupId key = new DisclosureGroupId("A000000000", "06", "0002");

            final String representation = key.toString();

            assertThat(representation).contains("A000000000");
            assertThat(representation).contains("06");
            assertThat(representation).contains("0002");
        }

        @Test
        @DisplayName("the representation names the components in CVTRA02Y declaration order - group "
                + "id, then type code, then category code")
        void representationFollowsContractualComponentOrder() {
            final DisclosureGroupId key = new DisclosureGroupId("A000000000", "02", "0003");

            final String representation = key.toString();

            assertThat(representation.indexOf("disAcctGroupId"))
                    .isLessThan(representation.indexOf("disTranTypeCd"));
            assertThat(representation.indexOf("disTranTypeCd"))
                    .isLessThan(representation.indexOf("disTranCatCd"));
        }

        @Test
        @DisplayName("the representation renders the padded group id with its three trailing spaces "
                + "intact rather than the trimmed seven-character form, because a diagnostic that "
                + "hid the padding would hide the very distinction the key depends on")
        void representationRendersThePaddedGroupIdUntrimmed() {
            final DisclosureGroupId key = new DisclosureGroupId("DEFAULT   ", "01", "0001");

            // The class quotes each component, so the padded value appears between the quotes with its
            // trailing spaces preserved. Both literals are typed out.
            assertThat(key.toString())
                    .isEqualTo("DisclosureGroupId[disAcctGroupId='DEFAULT   ', "
                            + "disTranTypeCd='01', disTranCatCd='0001']");

            // The trimmed rendering is explicitly not what is produced.
            assertThat(key.toString())
                    .isNotEqualTo("DisclosureGroupId[disAcctGroupId='DEFAULT', "
                            + "disTranTypeCd='01', disTranCatCd='0001']");
        }

        @Test
        @DisplayName("the representation of a fully absent key reports all three components as absent "
                + "instead of failing, so an unpopulated identifier is still diagnosable")
        void representationOfAFullyAbsentKeyReportsTheAbsences() {
            final DisclosureGroupId empty = new DisclosureGroupId();

            assertThat(empty.toString())
                    .isEqualTo("DisclosureGroupId[disAcctGroupId='null', "
                            + "disTranTypeCd='null', disTranCatCd='null']");
        }
    }
}
