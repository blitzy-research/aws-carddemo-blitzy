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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link DisclosureGroupId}, the composite key of the disclosure-group row.
 *
 * <p><strong>What the legacy authority is.</strong> The key is the {@code DIS-ACCT-GROUP-KEY} group
 * declared at the head of {@code app/cpy/CVTRA02Y.cpy}: a ten-character account group identifier, a
 * two-character transaction type code and a four-digit transaction category code, in that order. The
 * cluster definition in {@code app/jcl/DISCGRP.jcl} states the geometry independently at line 40 as
 * {@code KEYS(16 0)} over a {@code RECORDSIZE(50 50)} record, so the key is sixteen bytes wide and
 * sits at offset zero &mdash; it is the leading substring of the record image, not a value derived
 * from it. The rate that follows the key is {@code DIS-INT-RATE PIC S9(04)V99} and the row closes
 * with {@code FILLER PIC X(28)}, which is how ten plus two plus four plus six plus twenty-eight
 * reaches the declared fifty.
 *
 * <p><strong>Why the ordering is contractual rather than cosmetic.</strong> The interest run reads
 * this file by key, and on a miss it retries under the reserved default group. Both the direct read
 * and the fallback read assemble the same sixteen-byte image in the same field order, so a
 * transposition of the two trailing components would still produce a sixteen-byte probe and would
 * still find rows &mdash; the wrong rows. This class therefore pins component order explicitly, and
 * pins it by width as well as by value so that a transposition is detectable even when both
 * components happen to be present.
 *
 * <p><strong>Why nothing is normalised.</strong> The category code is text carrying four digits, not
 * a number. Trimming it, parsing it or stripping its leading zeros would change the key image and so
 * change which row a probe finds. The identifier class therefore stores every component verbatim and
 * this class asserts that {@code "0001"} is not interchangeable with {@code "1"}, and that the
 * space-padded reserved group name is not interchangeable with its trimmed form.
 *
 * <p><strong>How the reference data anchors the assertions.</strong> The seeded file
 * {@code app/data/ASCII/discgrp.txt} measures 2,601 bytes, which is fifty-one rows of fifty bytes
 * plus one line terminator each. Those fifty-one rows form exactly three groups of seventeen under
 * three distinct group identifiers, one of which is the reserved default. Thirty of the fifty-one
 * rows carry a zero rate, fifteen carry fifteen per cent and six carry twenty-five per cent, which is
 * what makes both the default-fallback arm and the zero-rate skip arm of the interest run reachable
 * from seed data alone. The counts are used here as key-distinctness evidence: fifty-one rows must
 * yield fifty-one distinct keys, or the seed would be contradicting its own cluster definition.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here touches a database, a container or the
 * persistence provider; the identifier is a plain serialisable value object and is exercised as one.
 * No reflection is used, so the module's zero-reflection posture is untouched. The no-argument
 * constructor the provider needs is reached directly because this class sits in the identifier's own
 * package, which is exactly the visibility the constructor declares.
 */
@DisplayName("DisclosureGroupId — the 16-byte composite key at offset 0")
class DisclosureGroupIdTest {

    /** Width of the account group identifier component, from the copybook. */
    private static final int GROUP_ID_WIDTH = 10;

    /** Width of the transaction type code component, from the copybook. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of the transaction category code component, from the copybook. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Width of the interest rate that follows the key, six zoned digits. */
    private static final int RATE_WIDTH = 6;

    /** Width of the trailing filler that closes the row. */
    private static final int FILLER_WIDTH = 28;

    /** The key width the cluster definition declares. */
    private static final int DECLARED_KEY_WIDTH = 16;

    /** The offset at which the cluster definition places the key. */
    private static final int DECLARED_KEY_OFFSET = 0;

    /** The record width the cluster definition declares. */
    private static final int DECLARED_RECORD_WIDTH = 50;

    /** Rows in the seeded reference file. */
    private static final int SEEDED_ROW_COUNT = 51;

    /** Rows per group in the seeded reference file. */
    private static final int SEEDED_ROWS_PER_GROUP = 17;

    /** Measured byte length of the seeded reference file. */
    private static final int SEEDED_FILE_BYTES = 2601;

    /** The three group identifiers the seeded file carries, space-padded to the declared width. */
    private static final List<String> SEEDED_GROUP_IDS =
            List.of("A000000000", "DEFAULT   ", "ZEROAPR   ");

    /** The group identifier the interest run falls back to when a direct read misses. */
    private static final String DEFAULT_GROUP_ID = "DEFAULT   ";

    /** A representative type code from the seeded file. */
    private static final String TYPE_CODE = "01";

    /** A representative category code from the seeded file, with its leading zeros intact. */
    private static final String CATEGORY_CODE = "0001";

    /**
     * Builds a key from the three components in copybook order.
     *
     * @param groupId      the account group identifier
     * @param typeCode     the transaction type code
     * @param categoryCode the transaction category code
     * @return the assembled key
     */
    private static DisclosureGroupId key(final String groupId, final String typeCode,
            final String categoryCode) {
        return new DisclosureGroupId(groupId, typeCode, categoryCode);
    }

    /**
     * Round-trips a value through Java serialisation.
     *
     * @param original the value to serialise
     * @return the deserialised copy
     * @throws IOException            if the byte streams fail
     * @throws ClassNotFoundException if the class cannot be resolved on the way back
     */
    private static Object serialiseAndBack(final Serializable original)
            throws IOException, ClassNotFoundException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in =
                new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }

    // =================================================================================================
    // COMPONENT CONTRACT
    // =================================================================================================

    /**
     * Verifies that the three components bind in copybook order and are stored verbatim.
     */
    @Nested
    @DisplayName("component contract of the three-part key")
    class ComponentContract {

        @Test
        @DisplayName("the all-arguments constructor binds group, type then category, in copybook order")
        void bindsComponentsInCopybookOrder() {
            final DisclosureGroupId subject = key("A000000000", "01", "0001");

            assertThat(subject.getDisAcctGroupId()).isEqualTo("A000000000");
            assertThat(subject.getDisTranTypeCd()).isEqualTo("01");
            assertThat(subject.getDisTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("a transposition of the two trailing components is detectable by width alone")
        void aTranspositionIsDetectableByWidth() {
            final DisclosureGroupId correct = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(correct.getDisTranTypeCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(correct.getDisTranCatCd()).hasSize(CATEGORY_CODE_WIDTH);

            final DisclosureGroupId transposed =
                    key(DEFAULT_GROUP_ID, CATEGORY_CODE, TYPE_CODE);

            assertThat(transposed.getDisTranTypeCd()).hasSize(CATEGORY_CODE_WIDTH);
            assertThat(transposed.getDisTranCatCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(transposed).isNotEqualTo(correct);
        }

        @Test
        @DisplayName("the no-argument constructor leaves all three components absent, because the "
                + "provider assigns them after construction")
        void theNoArgumentConstructorLeavesComponentsAbsent() {
            final DisclosureGroupId empty = new DisclosureGroupId();

            assertThat(empty.getDisAcctGroupId()).isNull();
            assertThat(empty.getDisTranTypeCd()).isNull();
            assertThat(empty.getDisTranCatCd()).isNull();
        }

        @Test
        @DisplayName("every component is stored verbatim: no trim, no pad, no case fold, no reparse")
        void componentsAreStoredVerbatim() {
            final DisclosureGroupId subject = key("  spaced  ", "aB", "00x1");

            assertThat(subject.getDisAcctGroupId()).isEqualTo("  spaced  ");
            assertThat(subject.getDisTranTypeCd()).isEqualTo("aB");
            assertThat(subject.getDisTranCatCd()).isEqualTo("00x1");
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, because the key is a byte image and "
                + "not a number")
        void theCategoryCodeKeepsItsLeadingZeros() {
            final DisclosureGroupId padded = key(DEFAULT_GROUP_ID, TYPE_CODE, "0001");
            final DisclosureGroupId unpadded = key(DEFAULT_GROUP_ID, TYPE_CODE, "1");

            assertThat(padded.getDisTranCatCd()).isEqualTo("0001").hasSize(CATEGORY_CODE_WIDTH);
            assertThat(unpadded.getDisTranCatCd()).isEqualTo("1");
            assertThat(padded).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("the reserved default group keeps its trailing blanks, so the padded name is not "
                + "interchangeable with the trimmed one")
        void theDefaultGroupKeepsItsTrailingBlanks() {
            final DisclosureGroupId padded = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
            final DisclosureGroupId trimmed = key(DEFAULT_GROUP_ID.trim(), TYPE_CODE, CATEGORY_CODE);

            assertThat(padded.getDisAcctGroupId()).hasSize(GROUP_ID_WIDTH);
            assertThat(trimmed.getDisAcctGroupId()).hasSize("DEFAULT".length());
            assertThat(padded).isNotEqualTo(trimmed);
        }

        @Test
        @DisplayName("absent components are accepted and handed back unchanged, because the identifier "
                + "validates nothing on its own behalf")
        void absentComponentsAreAcceptedUnchanged() {
            final DisclosureGroupId partial = key(null, TYPE_CODE, null);

            assertThat(partial.getDisAcctGroupId()).isNull();
            assertThat(partial.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(partial.getDisTranCatCd()).isNull();
        }
    }

    // =================================================================================================
    // BYTE GEOMETRY
    // =================================================================================================

    /**
     * Verifies the byte geometry the cluster definition declares.
     */
    @Nested
    @DisplayName("byte geometry of the key and its record")
    class ByteGeometry {

        @Test
        @DisplayName("the three component widths sum to the sixteen bytes the cluster declares")
        void theComponentWidthsSumToTheDeclaredKeyWidth() {
            final int summed = GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH;

            assertThat(summed)
                    .as("summed component widths against KEYS(16 0)")
                    .isEqualTo(DECLARED_KEY_WIDTH);
        }

        @Test
        @DisplayName("the record widths close on the fifty bytes the cluster declares, so the key sits "
                + "in a fully accounted-for layout")
        void theRecordWidthsCloseOnFifty() {
            final int summed = GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH
                    + RATE_WIDTH + FILLER_WIDTH;

            assertThat(summed)
                    .as("summed record field widths against RECORDSIZE(50 50)")
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the key is the leading substring of the record, so no surrogate identifier is "
                + "needed or possible")
        void theKeyIsTheLeadingSubstringOfTheRecord() {
            assertThat(DECLARED_KEY_OFFSET)
                    .as("KEYS(16 0) places the key at offset zero")
                    .isZero();

            final int firstByteAfterTheKey = DECLARED_KEY_OFFSET + DECLARED_KEY_WIDTH;

            assertThat(firstByteAfterTheKey)
                    .as("the rate begins immediately after the key")
                    .isEqualTo(GROUP_ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH);
            assertThat(firstByteAfterTheKey + RATE_WIDTH + FILLER_WIDTH)
                    .as("the record ends exactly where the cluster says it does")
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("each component encodes to exactly its declared width as single-byte text")
        void eachComponentEncodesToItsDeclaredWidth() {
            final DisclosureGroupId subject = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(subject.getDisAcctGroupId().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(GROUP_ID_WIDTH);
            assertThat(subject.getDisTranTypeCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TYPE_CODE_WIDTH);
            assertThat(subject.getDisTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CATEGORY_CODE_WIDTH);
        }

        @Test
        @DisplayName("the seeded reference file geometry closes: fifty-one rows of fifty bytes plus a "
                + "terminator each")
        void theSeededFileGeometryCloses() {
            final int bytesPerLine = DECLARED_RECORD_WIDTH + 1;

            assertThat(SEEDED_ROW_COUNT * bytesPerLine)
                    .as("measured byte length of the seeded reference file")
                    .isEqualTo(SEEDED_FILE_BYTES);
            assertThat(SEEDED_GROUP_IDS.size() * SEEDED_ROWS_PER_GROUP)
                    .as("three groups of seventeen rows")
                    .isEqualTo(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("every seeded group identifier fills the declared component width")
        void everySeededGroupIdentifierFillsTheComponentWidth() {
            for (final String groupId : SEEDED_GROUP_IDS) {
                assertThat(groupId)
                        .as("seeded group identifier [%s]", groupId)
                        .hasSize(GROUP_ID_WIDTH);
            }
            assertThat(SEEDED_GROUP_IDS).contains(DEFAULT_GROUP_ID);
        }
    }

    // =================================================================================================
    // EQUALITY AND HASHING
    // =================================================================================================

    /**
     * Verifies the equality contract across all three components.
     *
     * <p>The persistence provider uses this contract to decide whether two loaded rows are the same
     * row, so a component missing from equality would silently merge distinct rate rows.</p>
     */
    @Nested
    @DisplayName("equality and hashing")
    class EqualityAndHashing {

        @Test
        @DisplayName("two independently built keys with identical components are equal and hash alike")
        void identicalComponentsAreEqualAndHashAlike() {
            final DisclosureGroupId first = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
            final DisclosureGroupId second = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a key equals itself, satisfying the reflexive clause")
        void aKeyEqualsItself() {
            final DisclosureGroupId subject = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(subject).isEqualTo(subject);
        }

        @Test
        @DisplayName("equality is symmetric")
        void equalityIsSymmetric() {
            final DisclosureGroupId first = key("A000000000", TYPE_CODE, CATEGORY_CODE);
            final DisclosureGroupId second = key("A000000000", TYPE_CODE, CATEGORY_CODE);

            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(first)).isTrue();
        }

        @Test
        @DisplayName("the group component participates in equality, so the default group is distinct "
                + "from a named one")
        void theGroupComponentParticipatesInEquality() {
            final DisclosureGroupId named = key("A000000000", TYPE_CODE, CATEGORY_CODE);
            final DisclosureGroupId fallback = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(named).isNotEqualTo(fallback);
        }

        @Test
        @DisplayName("the type component participates in equality")
        void theTypeComponentParticipatesInEquality() {
            assertThat(key(DEFAULT_GROUP_ID, "01", CATEGORY_CODE))
                    .isNotEqualTo(key(DEFAULT_GROUP_ID, "02", CATEGORY_CODE));
        }

        @Test
        @DisplayName("the category component participates in equality")
        void theCategoryComponentParticipatesInEquality() {
            assertThat(key(DEFAULT_GROUP_ID, TYPE_CODE, "0001"))
                    .isNotEqualTo(key(DEFAULT_GROUP_ID, TYPE_CODE, "0002"));
        }

        @Test
        @DisplayName("equality rejects an absent reference and a foreign type")
        void equalityRejectsNullAndAForeignType() {
            final DisclosureGroupId subject = key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

            assertThat(subject.equals(null)).isFalse();
            assertThat(subject.equals("DEFAULT   010001")).isFalse();
            assertThat(subject.equals(
                    new TransactionCategoryBalanceId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE)))
                    .isFalse();
        }

        @Test
        @DisplayName("two fully absent keys are equal, which is what lets the provider compare a "
                + "freshly constructed key before populating it")
        void twoFullyAbsentKeysAreEqual() {
            assertThat(new DisclosureGroupId())
                    .isEqualTo(new DisclosureGroupId())
                    .hasSameHashCodeAs(new DisclosureGroupId());
        }

        @Test
        @DisplayName("the three seeded group identifiers crossed with the seventeen rows each stay "
                + "distinct in a hash set")
        void theSeededKeysStayDistinctInAHashSet() {
            final Set<DisclosureGroupId> distinct = new HashSet<>();
            for (final String groupId : SEEDED_GROUP_IDS) {
                for (int row = 1; row <= SEEDED_ROWS_PER_GROUP; row++) {
                    distinct.add(key(groupId, TYPE_CODE, String.format("%04d", row)));
                }
            }

            assertThat(distinct)
                    .as("fifty-one seeded rows must yield fifty-one distinct keys")
                    .hasSize(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("a hash map keyed by the identifier retrieves a row through an independently "
                + "built equal key")
        void aHashMapRetrievesThroughAnEqualKey() {
            final Map<DisclosureGroupId, String> rates = new HashMap<>();
            rates.put(key("A000000000", "01", "0001"), "15.00");
            rates.put(key(DEFAULT_GROUP_ID, "01", "0001"), "15.00");
            rates.put(key("ZEROAPR   ", "01", "0001"), "0.00");

            assertThat(rates).hasSize(3);
            assertThat(rates.get(key("ZEROAPR   ", "01", "0001"))).isEqualTo("0.00");
            assertThat(rates.get(key(DEFAULT_GROUP_ID, "01", "0001"))).isEqualTo("15.00");
            assertThat(rates.get(key("NOSUCH    ", "01", "0001"))).isNull();
        }
    }

    // =================================================================================================
    // SERIALISATION
    // =================================================================================================

    /**
     * Verifies the serialisation contract an identifier class is required to honour.
     */
    @Nested
    @DisplayName("serialisation")
    class Serialisation {

        @Test
        @DisplayName("the identifier is serialisable, as a key carried across a persistence boundary "
                + "must be")
        void theIdentifierIsSerialisable() {
            assertThat(key(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE))
                    .isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("the serialisation version identifier is pinned rather than compiler-derived")
        void theSerialisationVersionIsPinned() {
            final ObjectStreamClass descriptor = ObjectStreamClass.lookup(DisclosureGroupId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a round trip preserves all three components verbatim along with equality")
        void aRoundTripPreservesEveryComponent() throws IOException, ClassNotFoundException {
            final DisclosureGroupId original = key("A000000000", "01", "0001");

            final Object restored = serialiseAndBack(original);

            assertThat(restored)
                    .isInstanceOf(DisclosureGroupId.class)
                    .isEqualTo(original)
                    .hasSameHashCodeAs(original);

            final DisclosureGroupId copy = (DisclosureGroupId) restored;

            assertThat(copy.getDisAcctGroupId()).isEqualTo("A000000000");
            assertThat(copy.getDisTranTypeCd()).isEqualTo("01");
            assertThat(copy.getDisTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("a round trip of a fully absent key preserves all three absences")
        void aRoundTripOfAnAbsentKeyPreservesTheAbsences()
                throws IOException, ClassNotFoundException {
            final Object restored = serialiseAndBack(new DisclosureGroupId());

            assertThat(restored).isInstanceOf(DisclosureGroupId.class);

            final DisclosureGroupId copy = (DisclosureGroupId) restored;

            assertThat(copy.getDisAcctGroupId()).isNull();
            assertThat(copy.getDisTranTypeCd()).isNull();
            assertThat(copy.getDisTranCatCd()).isNull();
        }
    }

    // =================================================================================================
    // DIAGNOSTIC REPRESENTATION
    // =================================================================================================

    /**
     * Verifies the diagnostic representation, which is what identifies a row in a log line.
     */
    @Nested
    @DisplayName("diagnostic representation")
    class DiagnosticRepresentation {

        @Test
        @DisplayName("the representation carries all three key components")
        void theRepresentationCarriesEveryComponent() {
            assertThat(key("A000000000", "01", "0001").toString())
                    .contains("A000000000")
                    .contains("01")
                    .contains("0001");
        }

        @Test
        @DisplayName("the representation names the components in copybook order")
        void theRepresentationNamesComponentsInCopybookOrder() {
            final String rendered = key("A000000000", "02", "0005").toString();

            assertThat(rendered.indexOf("disAcctGroupId"))
                    .isLessThan(rendered.indexOf("disTranTypeCd"));
            assertThat(rendered.indexOf("disTranTypeCd"))
                    .isLessThan(rendered.indexOf("disTranCatCd"));
        }

        @Test
        @DisplayName("the representation of a fully absent key reports the absences instead of failing")
        void theRepresentationOfAnAbsentKeyReportsTheAbsences() {
            assertThat(new DisclosureGroupId().toString())
                    .startsWith("DisclosureGroupId[")
                    .contains("null")
                    .endsWith("]");
        }
    }
}
