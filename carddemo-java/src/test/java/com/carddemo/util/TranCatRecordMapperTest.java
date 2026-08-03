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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.id.TransactionCategoryId;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link TranCatRecordMapper}, the hand-written mapper between the
 * <strong>60-byte</strong> transaction-category reference record declared in copybook
 * {@code CVTRA04Y} and the {@link TransactionCategory} entity, whose identity is the
 * <strong>6-byte</strong> composite key formed by a 2-byte type code at offset 0 followed by a
 * 4-byte category code at offset 2.
 *
 * <h2>This test is an independent oracle</h2>
 *
 * <p>Every expectation below is hand-written from the copybook, the cluster definition and a
 * byte-level inspection of the shipped fixture. No expectation is produced by calling a production
 * method, no expectation is derived from another production constant, and no output is snapshotted.
 * That discipline is the whole point: an assertion of the form "offset A plus length A equals
 * offset B" holds even when every one of those constants is wrong, so this file states each offset
 * and each length as a literal instead.
 *
 * <p>No filesystem, classpath resource, database, container, network or Spring context is involved.
 * The two record images this file exercises are transcribed here as literals from a verified
 * inspection of {@code app/data/ASCII/trancatg.txt}, so the test is hermetic and its expectations
 * are visible at the point of use.
 *
 * <h2>The two collisions this layout sits at the intersection of</h2>
 *
 * <p><strong>Collision one - the key-group name.</strong> A key group named {@code TRAN-CAT-KEY} is
 * declared in two copybooks at two different widths. Here it is <strong>6</strong> bytes, a 2-byte
 * type code at offset 0 followed by a 4-byte category code at offset 2, corroborated by the cluster
 * attribute {@code KEYS(6 0)} [app/jcl/TRANCATG.jcl]. In the category-balance copybook it is
 * <strong>17</strong> bytes, corroborated by {@code KEYS(17 0)}, and its own type and category
 * components sit at offsets <strong>11</strong> and <strong>13</strong> behind an 11-byte account
 * identifier that this layout does not carry at all. The 6-byte key is therefore <em>not</em> a
 * prefix of the 17-byte key and the two align at no offset, so no key constant, key-building
 * helper, identifier class or test helper is shared between them.
 *
 * <p><strong>Collision two - the record width.</strong> The transaction-type reference layout is
 * also exactly 60 bytes, but it splits as 2 + 50 + 8 with its description at offset
 * <strong>2</strong> and an 8-byte filler run, against 2 + 4 + 50 + 4 here with the description at
 * offset <strong>6</strong> and a 4-byte filler run. <strong>A width check cannot tell the two
 * apart</strong>, and no content sniffing is attempted to make up the difference: the caller knows
 * which dataset it read and selects the mapper accordingly. One test below feeds a type-shaped
 * image to this mapper and asserts the exact four-byte shift that results, which documents the
 * consequence of a mis-selection rather than defending against it.
 *
 * <h2>The narrowest filler run in the estate, and the comparison bound it forces</h2>
 *
 * <p>The fixture's filler run is <strong>4 bytes of ASCII {@code '0'}</strong> - the narrowest of
 * any layout in the estate - while {@code toRecord} emits the module-wide default of spaces. A
 * whole-record 60-byte comparison against a raw fixture line therefore <em>fails</em>, and it fails
 * on four bytes alone, which is exceptionally easy to misdiagnose as a description-padding fault or
 * an off-by-one in the description offset. Round-trip assertions here consequently compare only the
 * mapped data prefix {@code [0, 56)} and then assert the emitted filler separately as exactly four
 * {@code 0x20} bytes. The fixture is never "corrected" and a 60-byte equality against it is never
 * asserted.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>No decimal, monetary, rate or temporal value exists anywhere in this layout, so no decimal
 * codec is referenced and no numeric conversion occurs. Both codes stay text end to end: a category
 * code of {@code 0001} keeps its leading zeros and is never parsed to an integral type, because a
 * narrowed value would no longer reconstruct the 6-byte key image from its two components. Neither
 * code becomes an enumeration, so an out-of-set code passes through unchanged. Descriptions are
 * carried verbatim - never case-folded, never normalised, never truncated and never stripped of
 * their contractual padding - and the schema declares no foreign key into or out of this table.
 *
 * <h2>Divergences this file proves, all recorded in {@code docs/decision-log.md}</h2>
 *
 * <ol>
 *   <li>A 6-byte composite key colliding by name with a 17-byte key that is not its prefix.</li>
 *   <li>A 60-byte width shared with a structurally different layout that no width check separates.</li>
 *   <li>The estate's narrowest filler run, 4 ASCII zeros in the fixture against uniform space
 *       filler on write.</li>
 *   <li>Mixed-case descriptions preserved verbatim.</li>
 *   <li>A 29-character description sitting exactly on the downstream report truncation boundary,
 *       while this mapper truncates nothing.</li>
 * </ol>
 *
 * <p><strong>Provenance.</strong> Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}. Legacy
 * artefacts are cited, never transcribed as source lines.
 */
@DisplayName("TranCatRecordMapper - the 60-byte transaction-category reference layout")
class TranCatRecordMapperTest {

    // ---------------------------------------------------------------------------------------------
    // Hand-derived layout geometry. Every value below was read from app/cpy/CVTRA04Y.cpy and
    // corroborated against app/jcl/TRANCATG.jcl; none is copied from a production constant, so an
    // assertion against one of these genuinely constrains the mapper rather than restating it.
    // ---------------------------------------------------------------------------------------------

    /** Zero-based offset of the 2-byte type code, key component 1: the record starts with it. */
    private static final int EXPECTED_TYPE_CODE_OFFSET = 0;

    /** Byte length of the type code. */
    private static final int EXPECTED_TYPE_CODE_LENGTH = 2;

    /** Zero-based offset of the 4-byte category code, key component 2: immediately after the type. */
    private static final int EXPECTED_CATEGORY_CODE_OFFSET = 2;

    /** Byte length of the category code, carried as text so its leading zeros survive. */
    private static final int EXPECTED_CATEGORY_CODE_LENGTH = 4;

    /** Zero-based offset of the description: 6, immediately after the 6-byte composite key. */
    private static final int EXPECTED_DESCRIPTION_OFFSET = 6;

    /** Byte length of the description. */
    private static final int EXPECTED_DESCRIPTION_LENGTH = 50;

    /** Zero-based offset of the trailing filler run: 56, immediately after the description. */
    private static final int EXPECTED_FILLER_OFFSET = 56;

    /** Byte length of the trailing filler run - the narrowest in the estate. */
    private static final int EXPECTED_FILLER_LENGTH = 4;

    /** Composite key width: the 2-byte type code plus the 4-byte category code. */
    private static final int EXPECTED_KEY_WIDTH = 6;

    /** Mapped data width: the 6-byte key plus the 50-byte description, filler excluded. */
    private static final int EXPECTED_MAPPED_DATA_WIDTH = 56;

    /** Stored record width, corroborated by the cluster's fixed record length. */
    private static final int EXPECTED_RECORD_WIDTH = 60;

    /** Key length declared by the cluster attribute {@code KEYS(6 0)}. */
    private static final int CLUSTER_DECLARED_KEY_LENGTH = 6;

    /** Key offset declared by the cluster attribute {@code KEYS(6 0)}. */
    private static final int CLUSTER_DECLARED_KEY_OFFSET = 0;

    // ---------------------------------------------------------------------------------------------
    // Sibling geometry, stated here purely so the two collisions can be asserted as inequalities.
    // These are deliberately local literals rather than imports: sharing a constant with either
    // sibling layout is exactly the defect these assertions exist to prevent.
    // ---------------------------------------------------------------------------------------------

    /** Description offset of the transaction-type layout: 2, four bytes ahead of this layout's 6. */
    private static final int TYPE_LAYOUT_DESCRIPTION_OFFSET = 2;

    /** Filler width of the transaction-type layout: 8, twice this layout's 4. */
    private static final int TYPE_LAYOUT_FILLER_LENGTH = 8;

    /** Record width of the transaction-type layout: also 60, which is why no width check separates them. */
    private static final int TYPE_LAYOUT_RECORD_WIDTH = 60;

    /** Key width of the category-balance layout: 17, and the 6-byte key is not a prefix of it. */
    private static final int BALANCE_LAYOUT_KEY_WIDTH = 17;

    /** Type-code offset inside the category-balance key: 11, behind an 11-byte account identifier. */
    private static final int BALANCE_LAYOUT_TYPE_CODE_OFFSET = 11;

    /** Category-code offset inside the category-balance key: 13. */
    private static final int BALANCE_LAYOUT_CATEGORY_CODE_OFFSET = 13;

    // ---------------------------------------------------------------------------------------------
    // Diagnostic text, hand-written from the copybook name rather than read back from the mapper.
    // ---------------------------------------------------------------------------------------------

    /** The record artefact name a rejection is expected to carry. */
    private static final String EXPECTED_ARTEFACT = "TRAN-CAT-RECORD (CVTRA04Y)";

    /** The key artefact name, which states the 6-byte width so it cannot be read as the 17-byte key. */
    private static final String EXPECTED_KEY_ARTEFACT = "TRAN-CAT-KEY (CVTRA04Y, 6 bytes)";

    // ---------------------------------------------------------------------------------------------
    // Fixture accounting, measured rather than assumed. app/data/ASCII/trancatg.txt measures 1,098
    // bytes and holds 18 records at a 61-byte stride: the 60-byte record plus one 0x0A terminator,
    // which is a record separator and never record content. The seed migration loads exactly those
    // 18 rows. These are factual layout figures and carry no service-level meaning whatsoever.
    // ---------------------------------------------------------------------------------------------

    /** Records in the shipped fixture, and rows the reference seed inserts. */
    private static final int SEEDED_ROW_COUNT = 18;

    /** The fixture's per-record stride: the 60-byte record plus its single terminator byte. */
    private static final int FIXTURE_STRIDE = 61;

    /** The fixture's measured size in bytes. */
    private static final int FIXTURE_SIZE_IN_BYTES = 1098;

    // ---------------------------------------------------------------------------------------------
    // The two verified record images, transcribed field by field from a byte-level inspection of the
    // fixture. Each description is assembled as its text plus an explicit space pad, so the padding
    // is visible and its width is asserted below rather than trusted.
    // ---------------------------------------------------------------------------------------------

    /** Type code of the fixture's first record. */
    private static final String FIRST_ROW_TYPE_CODE = "01";

    /** Category code of the fixture's first record, leading zeros intact. */
    private static final String FIRST_ROW_CATEGORY_CODE = "0001";

    /** Description text of the fixture's first record, 19 bytes before padding. */
    private static final String FIRST_ROW_DESCRIPTION_TEXT = "Regular Sales Draft";

    /** The same description as it appears in the record: 19 bytes of text plus 50 - 19 = 31 spaces. */
    private static final String FIRST_ROW_DESCRIPTION_FIELD =
            FIRST_ROW_DESCRIPTION_TEXT + " ".repeat(31);

    /** Type code of the mixed-case record, the fixture's last. */
    private static final String MIXED_CASE_ROW_TYPE_CODE = "07";

    /** Category code of the mixed-case record; the same category code under a different type. */
    private static final String MIXED_CASE_ROW_CATEGORY_CODE = "0001";

    /**
     * Description text of the mixed-case record: exactly 29 bytes, mixed case throughout, and one of
     * the two longest of the 18 seeded descriptions. Its lower-case letters are load bearing.
     */
    private static final String MIXED_CASE_ROW_DESCRIPTION_TEXT = "Sales draft credit adjustment";

    /** The same description as it appears in the record: 29 bytes of text plus 50 - 29 = 21 spaces. */
    private static final String MIXED_CASE_ROW_DESCRIPTION_FIELD =
            MIXED_CASE_ROW_DESCRIPTION_TEXT + " ".repeat(21);

    /** The all-upper rendering of the mixed-case description, written out rather than folded. */
    private static final String MIXED_CASE_ROW_DESCRIPTION_UPPER_FORM =
            "SALES DRAFT CREDIT ADJUSTMENT";

    /** The all-lower rendering of the mixed-case description, written out rather than folded. */
    private static final String MIXED_CASE_ROW_DESCRIPTION_LOWER_FORM =
            "sales draft credit adjustment";

    /** The fixture's 4-byte filler run: four ASCII {@code '0'} characters, not spaces. */
    private static final String FIXTURE_FILLER_FIELD = "0000";

    /** The 4-byte filler run this mapper emits: four spaces, the module-wide default. */
    private static final String EMITTED_FILLER_FIELD = "    ";

    /** The fixture's first record image, exactly 60 bytes, terminator already stripped. */
    private static final String FIRST_ROW_IMAGE = FIRST_ROW_TYPE_CODE + FIRST_ROW_CATEGORY_CODE
            + FIRST_ROW_DESCRIPTION_FIELD + FIXTURE_FILLER_FIELD;

    /** The mixed-case record image, exactly 60 bytes, terminator already stripped. */
    private static final String MIXED_CASE_ROW_IMAGE = MIXED_CASE_ROW_TYPE_CODE
            + MIXED_CASE_ROW_CATEGORY_CODE + MIXED_CASE_ROW_DESCRIPTION_FIELD
            + FIXTURE_FILLER_FIELD;

    /** The mapped data prefix of the first record, exactly 56 bytes: key then description. */
    private static final String FIRST_ROW_MAPPED_PREFIX =
            FIRST_ROW_TYPE_CODE + FIRST_ROW_CATEGORY_CODE + FIRST_ROW_DESCRIPTION_FIELD;

    /** What this mapper is expected to emit for the first record: mapped prefix then space filler. */
    private static final String FIRST_ROW_EMITTED_IMAGE =
            FIRST_ROW_MAPPED_PREFIX + EMITTED_FILLER_FIELD;

    /** The 6-byte key image of the first record. */
    private static final String FIRST_ROW_KEY_IMAGE = "010001";

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("every field offset is the hand-verified copybook offset: 0, 2, 6 and 56")
        void everyFieldOffsetIsTheHandVerifiedCopybookOffset() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET)
                    .as("TRAN-TYPE-CD offset")
                    .isEqualTo(EXPECTED_TYPE_CODE_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_OFFSET)
                    .as("TRAN-CAT-CD offset")
                    .isEqualTo(EXPECTED_CATEGORY_CODE_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET)
                    .as("TRAN-CAT-TYPE-DESC offset")
                    .isEqualTo(EXPECTED_DESCRIPTION_OFFSET);
            assertThat(TranCatRecordMapper.FILLER_OFFSET)
                    .as("FILLER offset")
                    .isEqualTo(EXPECTED_FILLER_OFFSET);
        }

        @Test
        @DisplayName("every field length is the hand-verified copybook length: 2, 4, 50 and 4")
        void everyFieldLengthIsTheHandVerifiedCopybookLength() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH)
                    .as("TRAN-TYPE-CD length")
                    .isEqualTo(EXPECTED_TYPE_CODE_LENGTH);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_LENGTH)
                    .as("TRAN-CAT-CD length")
                    .isEqualTo(EXPECTED_CATEGORY_CODE_LENGTH);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH)
                    .as("TRAN-CAT-TYPE-DESC length")
                    .isEqualTo(EXPECTED_DESCRIPTION_LENGTH);
            assertThat(TranCatRecordMapper.FILLER_LENGTH)
                    .as("FILLER length")
                    .isEqualTo(EXPECTED_FILLER_LENGTH);
        }

        @Test
        @DisplayName("the record is 60 bytes of which 56 are mapped, and 2 + 4 = 6, 6 + 50 = 56, "
                + "56 + 4 = 60")
        void theRecordIsSixtyBytesOfWhichFiftySixAreMapped() {
            assertThat(TranCatRecordMapper.RECORD_WIDTH).isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(TranCatRecordMapper.MAPPED_DATA_WIDTH).isEqualTo(EXPECTED_MAPPED_DATA_WIDTH);
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH).isEqualTo(EXPECTED_KEY_WIDTH);

            // The three sums are stated with literals on both sides so that a wrong constant cannot
            // satisfy them by cancelling out against another wrong constant.
            assertThat(EXPECTED_TYPE_CODE_LENGTH + EXPECTED_CATEGORY_CODE_LENGTH).isEqualTo(6);
            assertThat(EXPECTED_KEY_WIDTH + EXPECTED_DESCRIPTION_LENGTH).isEqualTo(56);
            assertThat(EXPECTED_MAPPED_DATA_WIDTH + EXPECTED_FILLER_LENGTH).isEqualTo(60);
        }

        @Test
        @DisplayName("the cluster attests a 6-byte key at offset 0, so the identifier is the legacy "
                + "business key and no surrogate key is introduced")
        void theClusterAttestsASixByteKeyAtOffsetZero() {
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH)
                    .as("key length from KEYS(6 0)")
                    .isEqualTo(CLUSTER_DECLARED_KEY_LENGTH);
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET)
                    .as("key offset from KEYS(6 0) - the key is the record's leading substring")
                    .isEqualTo(CLUSTER_DECLARED_KEY_OFFSET);

            // Because the key starts at offset 0, the stored key is literally the first six bytes of
            // the record image, which is what makes the business key usable as the identifier. A
            // surrogate identifier would break that record-image-to-row correspondence, so the
            // entity exposes no generated identifier at all - a structural fact settled by
            // compilation rather than by inspecting the class at run time.
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);
            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(decoded))
                    .isEqualTo(FIRST_ROW_KEY_IMAGE);
        }

        @Test
        @DisplayName("the emitted filler character is a space, and the fixture's is ASCII zero")
        void theEmittedFillerCharacterIsASpace() {
            assertThat(TranCatRecordMapper.EMITTED_FILLER_CHARACTER).isEqualTo(' ');
            assertThat(TranCatRecordMapper.EMITTED_FILLER_CHARACTER).isNotEqualTo('0');
        }

        @Test
        @DisplayName("both diagnostic artefact names identify the copybook, and the key name states "
                + "its 6-byte width so it cannot be read as the 17-byte key")
        void bothArtefactNamesIdentifyTheCopybook() {
            assertThat(TranCatRecordMapper.ARTEFACT).isEqualTo(EXPECTED_ARTEFACT);
            assertThat(TranCatRecordMapper.KEY_ARTEFACT).isEqualTo(EXPECTED_KEY_ARTEFACT);
        }
    }

    @Nested
    @DisplayName("collision guards")
    class CollisionGuards {

        @Test
        @DisplayName("collision one, the TRAN-CAT-KEY name shared with the 17-byte category-balance "
                + "key, and collision two, the 60-byte width shared with the transaction-type "
                + "layout: description at 6 not 2, filler 4 not 8, key 6 not 17, key components at "
                + "0 and 2 not 11 and 13")
        void bothCollisionsAreGuardedAtOnce() {
            // Collision two - the 60-byte width is shared, so geometry is the only discriminator.
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET)
                    .as("description offset is 6 here, not the type layout's 2")
                    .isEqualTo(EXPECTED_DESCRIPTION_OFFSET)
                    .isNotEqualTo(TYPE_LAYOUT_DESCRIPTION_OFFSET);
            assertThat(TranCatRecordMapper.FILLER_LENGTH)
                    .as("filler is 4 bytes here, not the type layout's 8")
                    .isEqualTo(EXPECTED_FILLER_LENGTH)
                    .isNotEqualTo(TYPE_LAYOUT_FILLER_LENGTH);
            assertThat(TranCatRecordMapper.RECORD_WIDTH)
                    .as("both layouts are 60 bytes, which is precisely why a width check is useless")
                    .isEqualTo(TYPE_LAYOUT_RECORD_WIDTH);

            // Collision one - the name is shared but neither the width nor the offsets are.
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH)
                    .as("key is 6 bytes here, not the category-balance layout's 17")
                    .isEqualTo(EXPECTED_KEY_WIDTH)
                    .isNotEqualTo(BALANCE_LAYOUT_KEY_WIDTH);
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET)
                    .as("type code sits at 0 here, not at the category-balance layout's 11")
                    .isEqualTo(EXPECTED_TYPE_CODE_OFFSET)
                    .isNotEqualTo(BALANCE_LAYOUT_TYPE_CODE_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_OFFSET)
                    .as("category code sits at 2 here, not at the category-balance layout's 13")
                    .isEqualTo(EXPECTED_CATEGORY_CODE_OFFSET)
                    .isNotEqualTo(BALANCE_LAYOUT_CATEGORY_CODE_OFFSET);

            // The 6-byte key is not a prefix of the 17-byte one: the wider key leads with an 11-byte
            // account identifier, so its type-and-category pair begins where this whole record's key
            // has already ended. Stated as an inequality of offsets rather than of widths, because a
            // width comparison alone would be satisfied by a genuine prefix relationship.
            assertThat(BALANCE_LAYOUT_TYPE_CODE_OFFSET)
                    .as("the wider key's type component starts beyond this key's entire 6 bytes")
                    .isGreaterThan(EXPECTED_KEY_WIDTH);
        }

        @Test
        @DisplayName("no content sniffing: a transaction-type-shaped image of the same 60 bytes is "
                + "accepted silently and mis-sliced by exactly four bytes, because the caller - not "
                + "the mapper - selects the layout")
        void aTypeShapedImageIsAcceptedSilentlyAndMisSlicedByFourBytes() {
            // A transaction-type record splits 2 + 50 + 8: a 2-byte code, a 50-byte description at
            // offset 2, then 8 filler bytes at offset 52. Assembled here by hand as a 60-byte image.
            final String typeShapedImage = "05" + ("Refund credit" + " ".repeat(37)) + "00000000";
            assertThat(typeShapedImage.getBytes(StandardCharsets.US_ASCII))
                    .as("the wrong-layout image really is 60 bytes, so a width check passes")
                    .hasSize(EXPECTED_RECORD_WIDTH);

            final TransactionCategory misSliced = TranCatRecordMapper.fromRecord(typeShapedImage);

            // Read through this layout's offsets the description shifts left by four bytes: its first
            // four characters are swallowed into the category code, and four of the type layout's
            // filler bytes are pulled into the tail of the description. Nothing raises, nothing is
            // detected, and the result looks entirely plausible - which is the exact reason this
            // mapper must never guess at a layout and the caller must always choose it.
            assertThat(misSliced.getTranTypeCd()).isEqualTo("05");
            assertThat(misSliced.getTranCatCd()).isEqualTo("Refu");
            assertThat(misSliced.getTranCatTypeDesc())
                    .isEqualTo("nd credit" + " ".repeat(37) + "0000");
            assertThat(misSliced.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
        }
    }

    @Nested
    @DisplayName("decoding the verified fixture records")
    class DecodingTheVerifiedFixtureRecords {

        @Test
        @DisplayName("the transcribed record images really are 60 bytes and their description fields "
                + "really are 50, so the literals below are trustworthy expectations")
        void theTranscribedImagesAreExactlySixtyBytes() {
            assertThat(FIRST_ROW_DESCRIPTION_FIELD.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
            assertThat(MIXED_CASE_ROW_DESCRIPTION_FIELD.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
            assertThat(FIRST_ROW_IMAGE.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(MIXED_CASE_ROW_IMAGE.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(FIRST_ROW_MAPPED_PREFIX.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_MAPPED_DATA_WIDTH);
        }

        @Test
        @DisplayName("the fixture's first record decodes to type 01, category 0001 and the 50-byte "
                + "space-padded description Regular Sales Draft")
        void theFirstFixtureRecordDecodesExactly() {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);

            assertThat(decoded.getTranTypeCd()).isEqualTo("01");
            assertThat(decoded.getTranCatCd()).isEqualTo("0001");
            assertThat(decoded.getTranCatTypeDesc())
                    .isEqualTo("Regular Sales Draft" + " ".repeat(31));
            assertThat(decoded.getTranTypeCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_TYPE_CODE_LENGTH);
            assertThat(decoded.getTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_CATEGORY_CODE_LENGTH);
            assertThat(decoded.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("the mixed-case record decodes to type 07, category 0001 and the 50-byte "
                + "space-padded description Sales draft credit adjustment, casing intact")
        void theMixedCaseFixtureRecordDecodesExactly() {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(MIXED_CASE_ROW_IMAGE);

            assertThat(decoded.getTranTypeCd()).isEqualTo("07");
            assertThat(decoded.getTranCatCd()).isEqualTo("0001");
            assertThat(decoded.getTranCatTypeDesc())
                    .isEqualTo("Sales draft credit adjustment" + " ".repeat(21));
            assertThat(decoded.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("the same category code 0001 appears under two different type codes, so the "
                + "category code alone is not the key and both components are needed")
        void theSameCategoryCodeAppearsUnderTwoTypeCodes() {
            final TransactionCategory first = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);
            final TransactionCategory mixedCase =
                    TranCatRecordMapper.fromRecord(MIXED_CASE_ROW_IMAGE);

            assertThat(first.getTranCatCd()).isEqualTo("0001");
            assertThat(mixedCase.getTranCatCd()).isEqualTo("0001");
            assertThat(first.getTranTypeCd()).isEqualTo("01");
            assertThat(mixedCase.getTranTypeCd()).isEqualTo("07");
            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(first)).isEqualTo("010001");
            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(mixedCase)).isEqualTo("070001");
            assertThat(first).isNotEqualTo(mixedCase);
        }

        @Test
        @DisplayName("the category code keeps its leading zeros: 0001 stays text and is not equal "
                + "to 1")
        void theCategoryCodeKeepsItsLeadingZeros() {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);

            assertThat(decoded.getTranCatCd())
                    .isInstanceOf(String.class)
                    .isEqualTo("0001")
                    .startsWith("000")
                    .isNotEqualTo("1");
            assertThat(decoded.getTranCatCd().getBytes(StandardCharsets.US_ASCII))
                    .as("a code parsed to an integral type and re-rendered would be one byte, not 4")
                    .hasSize(EXPECTED_CATEGORY_CODE_LENGTH);
        }

        @Test
        @DisplayName("the description survives untrimmed: 50 encoded bytes, and not equal to the "
                + "19-byte text it pads")
        void theDescriptionSurvivesUntrimmed() {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);

            assertThat(decoded.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
            assertThat(decoded.getTranCatTypeDesc())
                    .as("the contractual trailing pad is part of the field value")
                    .isNotEqualTo(FIRST_ROW_DESCRIPTION_TEXT)
                    .startsWith(FIRST_ROW_DESCRIPTION_TEXT)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("mixed case is preserved verbatim: the description equals neither its all-upper "
                + "nor its all-lower rendering")
        void mixedCaseIsPreservedVerbatim() {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(MIXED_CASE_ROW_IMAGE);

            assertThat(decoded.getTranCatTypeDesc())
                    .startsWith(MIXED_CASE_ROW_DESCRIPTION_TEXT)
                    .isNotEqualTo(MIXED_CASE_ROW_DESCRIPTION_UPPER_FORM + " ".repeat(21))
                    .isNotEqualTo(MIXED_CASE_ROW_DESCRIPTION_LOWER_FORM + " ".repeat(21))
                    .doesNotContain(MIXED_CASE_ROW_DESCRIPTION_UPPER_FORM)
                    .doesNotContain(MIXED_CASE_ROW_DESCRIPTION_LOWER_FORM);
            assertThat(MIXED_CASE_ROW_DESCRIPTION_TEXT)
                    .as("the transcribed literal itself is genuinely mixed case")
                    .isNotEqualTo(MIXED_CASE_ROW_DESCRIPTION_UPPER_FORM)
                    .isNotEqualTo(MIXED_CASE_ROW_DESCRIPTION_LOWER_FORM);
        }

        @Test
        @DisplayName("the description text is exactly 29 bytes, the width the daily transaction "
                + "report narrows it to - against 15 for the sibling type description - and this "
                + "mapper narrows nothing, returning all 50 bytes")
        void theDescriptionSitsExactlyOnTheReportTruncationBoundary() {
            final int reportDescriptionWidth = 29;
            final int reportTypeDescriptionWidth = 15;

            assertThat(MIXED_CASE_ROW_DESCRIPTION_TEXT.getBytes(StandardCharsets.US_ASCII))
                    .as("the description text sits precisely on the report's truncation boundary")
                    .hasSize(reportDescriptionWidth);
            assertThat(reportDescriptionWidth)
                    .as("one report line narrows two descriptions to two different widths")
                    .isNotEqualTo(reportTypeDescriptionWidth);

            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(MIXED_CASE_ROW_IMAGE);

            // Narrowing belongs to the report line formatter, never here: this mapper hands back the
            // whole 50-byte field so the formatter still has every byte it might need.
            assertThat(decoded.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
            assertThat(decoded.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII).length)
                    .isGreaterThan(reportDescriptionWidth);
        }

        @Test
        @DisplayName("neither code is enumerated: an out-of-set type ZZ and category 9999 map "
                + "through unchanged and unvalidated")
        void outOfSetCodesMapThroughUnchanged() {
            final String outOfSetImage =
                    "ZZ" + "9999" + ("Unlisted category" + " ".repeat(33)) + FIXTURE_FILLER_FIELD;
            assertThat(outOfSetImage.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_RECORD_WIDTH);

            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(outOfSetImage);

            assertThat(decoded.getTranTypeCd()).isEqualTo("ZZ");
            assertThat(decoded.getTranCatCd()).isEqualTo("9999");
            assertThat(decoded.getTranCatTypeDesc())
                    .isEqualTo("Unlisted category" + " ".repeat(33));
            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(decoded)).isEqualTo("ZZ9999");
        }

        @Test
        @DisplayName("fixture accounting: 18 records at a 61-byte stride account for all 1,098 "
                + "measured bytes, which corroborates the 60-byte record independently")
        void fixtureAccountingCorroboratesTheRecordWidth() {
            // These are measured layout facts - a byte count, a record count and a stride - and carry
            // no service-level or performance meaning of any kind.
            assertThat(SEEDED_ROW_COUNT).isEqualTo(18);
            assertThat(FIXTURE_STRIDE)
                    .as("the stride is the 60-byte record plus one 0x0A terminator")
                    .isEqualTo(EXPECTED_RECORD_WIDTH + 1);
            assertThat(SEEDED_ROW_COUNT * FIXTURE_STRIDE)
                    .as("18 x 61 accounts for every byte of the fixture, leaving no slack")
                    .isEqualTo(FIXTURE_SIZE_IN_BYTES);
        }
    }

    @Nested
    @DisplayName("the four-byte filler divergence")
    class TheFourByteFillerDivergence {

        @Test
        @DisplayName("the fixture's filler bytes 56-59 are ASCII zero while toRecord emits exactly "
                + "four 0x20 bytes: a deliberate divergence whose four-byte delta is easy to "
                + "misdiagnose as a description-padding fault or an off-by-one")
        void theFixtureFillerIsAsciiZeroWhileTheEmittedFillerIsFourSpaces() {
            final byte[] fixtureBytes = FIRST_ROW_IMAGE.getBytes(StandardCharsets.US_ASCII);
            assertThat(fixtureBytes).hasSize(EXPECTED_RECORD_WIDTH);

            // The fixture's four filler bytes, read one at a time at their absolute offsets 56, 57,
            // 58 and 59, are each ASCII '0' - decimal 48 - and not the space this mapper writes.
            assertThat(fixtureBytes[56]).isEqualTo((byte) '0');
            assertThat(fixtureBytes[57]).isEqualTo((byte) '0');
            assertThat(fixtureBytes[58]).isEqualTo((byte) '0');
            assertThat(fixtureBytes[59]).isEqualTo((byte) '0');

            final byte[] emitted =
                    TranCatRecordMapper.toRecordBytes(TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE));
            assertThat(emitted).hasSize(EXPECTED_RECORD_WIDTH);

            // What this mapper emits over the very same four offsets: 0x20, four times, exactly.
            assertThat(emitted[56]).isEqualTo((byte) 0x20);
            assertThat(emitted[57]).isEqualTo((byte) 0x20);
            assertThat(emitted[58]).isEqualTo((byte) 0x20);
            assertThat(emitted[59]).isEqualTo((byte) 0x20);
            assertThat(emitted).endsWith(EMITTED_FILLER_FIELD.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("a whole-record 60-byte comparison against the raw fixture line therefore FAILS "
                + "by design, differing on those four bytes and no others, so comparisons are bounded "
                + "to the mapped prefix [0, 56) and the fixture is never corrected")
        void aWholeRecordComparisonAgainstTheFixtureDiffersOnFourBytesByDesign() {
            final byte[] fixtureBytes = FIRST_ROW_IMAGE.getBytes(StandardCharsets.US_ASCII);
            final byte[] emitted =
                    TranCatRecordMapper.toRecordBytes(TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE));

            // Documenting the divergence rather than tolerating it: the two 60-byte images are not
            // equal, and this assertion records that the inequality is intended.
            assertThat(emitted)
                    .as("the documented filler divergence, not a mapping defect")
                    .isNotEqualTo(fixtureBytes);

            // Exactly four bytes differ, and every one of them lies inside the filler run. Counted
            // one offset at a time so the count is evidence rather than an assumption; with only four
            // filler bytes in this layout - the narrowest run in the estate - a bare inequality would
            // be far too weak to distinguish this from a genuine off-by-one in the description.
            int differingOffsets = 0;
            for (int offset = 0; offset < EXPECTED_RECORD_WIDTH; offset++) {
                if (emitted[offset] != fixtureBytes[offset]) {
                    differingOffsets++;
                    assertThat(offset)
                            .as("every differing offset must lie inside the filler run at 56")
                            .isGreaterThanOrEqualTo(EXPECTED_FILLER_OFFSET);
                }
            }
            assertThat(differingOffsets)
                    .as("precisely the four filler bytes differ, and nothing else")
                    .isEqualTo(EXPECTED_FILLER_LENGTH);

            // And over the mapped prefix the two images are byte-identical, which is the bound every
            // round-trip assertion for this layout uses.
            assertThat(emitted).startsWith(
                    FIRST_ROW_MAPPED_PREFIX.getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Nested
    @DisplayName("encoding the record image")
    class EncodingTheRecordImage {

        @Test
        @DisplayName("toRecord reproduces the mapped data prefix [0, 56) byte for byte under US-ASCII")
        void toRecordReproducesTheMappedDataPrefix() {
            final byte[] expectedPrefix =
                    FIRST_ROW_MAPPED_PREFIX.getBytes(StandardCharsets.US_ASCII);
            assertThat(expectedPrefix).hasSize(EXPECTED_MAPPED_DATA_WIDTH);

            final byte[] emitted =
                    TranCatRecordMapper.toRecordBytes(TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE));

            assertThat(emitted).startsWith(expectedPrefix);
        }

        @Test
        @DisplayName("toRecord emits exactly 60 encoded bytes: the 56-byte mapped prefix then four "
                + "0x20 filler bytes")
        void toRecordEmitsExactlySixtyEncodedBytes() {
            final String emitted =
                    TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE));

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_RECORD_WIDTH)
                    .isEqualTo(FIRST_ROW_EMITTED_IMAGE.getBytes(StandardCharsets.US_ASCII));
            assertThat(emitted).isEqualTo(FIRST_ROW_EMITTED_IMAGE);
        }

        @Test
        @DisplayName("the mixed-case record re-emits its 29-character description with casing and "
                + "padding intact")
        void theMixedCaseRecordReEmitsItsDescriptionIntact() {
            final String expected = MIXED_CASE_ROW_TYPE_CODE + MIXED_CASE_ROW_CATEGORY_CODE
                    + MIXED_CASE_ROW_DESCRIPTION_FIELD + EMITTED_FILLER_FIELD;

            final String emitted =
                    TranCatRecordMapper.toRecord(
                            TranCatRecordMapper.fromRecord(MIXED_CASE_ROW_IMAGE));

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_RECORD_WIDTH)
                    .isEqualTo(expected.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the byte-emitting entry point agrees with the string one on all 60 bytes")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);

            assertThat(TranCatRecordMapper.toRecordBytes(decoded))
                    .hasSize(EXPECTED_RECORD_WIDTH)
                    .isEqualTo(FIRST_ROW_EMITTED_IMAGE.getBytes(StandardCharsets.US_ASCII));
            assertThat(TranCatRecordMapper.toRecord(decoded).getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(TranCatRecordMapper.toRecordBytes(decoded));
        }

        @Test
        @DisplayName("the 6-byte key image is the record's own leading substring, six encoded bytes "
                + "and not seventeen")
        void theKeyImageIsSixEncodedBytes() {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);

            final String keyImage = TranCatRecordMapper.typeAndCategoryKeyImage(decoded);

            assertThat(keyImage).isEqualTo(FIRST_ROW_KEY_IMAGE);
            assertThat(keyImage.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_KEY_WIDTH);
            assertThat(keyImage.getBytes(StandardCharsets.US_ASCII).length)
                    .as("six bytes, never the category-balance layout's seventeen")
                    .isNotEqualTo(BALANCE_LAYOUT_KEY_WIDTH);
            assertThat(FIRST_ROW_IMAGE.getBytes(StandardCharsets.US_ASCII))
                    .as("the key image is literally the first six bytes of the record")
                    .startsWith(keyImage.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("an entity built through the public all-arguments constructor encodes correctly, "
                + "proving the three arguments follow copybook order - type, category, description - "
                + "and that the two key components are set individually rather than as an id object")
        void theAllArgumentsConstructorFollowsCopybookOrder() {
            // Copybook order: TRAN-TYPE-CD, then TRAN-CAT-CD, then TRAN-CAT-TYPE-DESC. Three distinct
            // values are used so a transposition cannot pass unnoticed, and the description is handed
            // over already padded to its full 50-byte field width.
            final TransactionCategory built = new TransactionCategory(
                    MIXED_CASE_ROW_TYPE_CODE,
                    MIXED_CASE_ROW_CATEGORY_CODE,
                    MIXED_CASE_ROW_DESCRIPTION_FIELD);

            assertThat(built.getTranTypeCd()).isEqualTo("07");
            assertThat(built.getTranCatCd()).isEqualTo("0001");
            assertThat(built.getTranCatTypeDesc())
                    .isEqualTo("Sales draft credit adjustment" + " ".repeat(21));

            // The two key components live on the entity itself under an identifier class, so the
            // identifier is derived from those two fields rather than assigned as an object. The
            // entity exposes no identifier mutator at all - a fact settled by compilation here rather
            // than by any reflective probe - and its identifier projection reports the same two
            // components in the same contractual order.
            final TransactionCategoryId identity = built.toId();
            assertThat(identity.getTranTypeCd()).isEqualTo("07");
            assertThat(identity.getTranCatCd()).isEqualTo("0001");
            assertThat(identity).isEqualTo(new TransactionCategoryId("07", "0001"));

            final String emitted = TranCatRecordMapper.toRecord(built);

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_RECORD_WIDTH)
                    .startsWith("070001".getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo((MIXED_CASE_ROW_TYPE_CODE + MIXED_CASE_ROW_CATEGORY_CODE
                            + MIXED_CASE_ROW_DESCRIPTION_FIELD + EMITTED_FILLER_FIELD)
                            .getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("a description shorter than the field is space-padded to the full 50 bytes on "
                + "output, and a category code shorter than four is zero-padded on the left")
        void shortValuesArePaddedToTheirFieldWidths() {
            final TransactionCategory built = new TransactionCategory("02", "1", "Cash payment");

            final String emitted = TranCatRecordMapper.toRecord(built);

            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_RECORD_WIDTH)
                    .isEqualTo(("02" + "0001" + ("Cash payment" + " ".repeat(38))
                            + EMITTED_FILLER_FIELD).getBytes(StandardCharsets.US_ASCII));
            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(built))
                    .as("a right-justified zero pad rebuilds the very key image the fixture stores")
                    .isEqualTo("020001");
        }
    }

    @Nested
    @DisplayName("agreement across the three decoding entry points")
    class AgreementAcrossTheDecodingEntryPoints {

        @Test
        @DisplayName("the string, byte-array and byte-range entry points yield equal entities, with "
                + "the description compared explicitly because entity equality covers the key alone")
        void allThreeEntryPointsYieldEqualEntities() {
            final byte[] recordBytes = FIRST_ROW_IMAGE.getBytes(StandardCharsets.US_ASCII);

            // A two-record buffer laid out exactly as the fixture is: each 60-byte record followed by
            // one 0x0A terminator, giving the 61-byte stride. Stride arithmetic belongs to the caller,
            // and the range entry point reads exactly 60 bytes so the terminator is never mapped.
            final byte[] twoRecordBuffer =
                    (FIRST_ROW_IMAGE + "\n" + MIXED_CASE_ROW_IMAGE + "\n")
                            .getBytes(StandardCharsets.US_ASCII);
            assertThat(twoRecordBuffer).hasSize(2 * FIXTURE_STRIDE);

            final TransactionCategory fromText = TranCatRecordMapper.fromRecord(FIRST_ROW_IMAGE);
            final TransactionCategory fromBytes = TranCatRecordMapper.fromRecord(recordBytes);
            final TransactionCategory fromRange = TranCatRecordMapper.fromRecord(twoRecordBuffer, 0);

            assertThat(fromBytes).isEqualTo(fromText);
            assertThat(fromRange).isEqualTo(fromText);
            assertThat(fromBytes).hasSameHashCodeAs(fromText);
            assertThat(fromRange).hasSameHashCodeAs(fromText);

            // Entity equality is defined over the two key components only, so the description must be
            // compared on its own or an entry point that mis-sliced it would still appear to agree.
            assertThat(fromText.getTranCatTypeDesc())
                    .isEqualTo("Regular Sales Draft" + " ".repeat(31));
            assertThat(fromBytes.getTranCatTypeDesc()).isEqualTo(fromText.getTranCatTypeDesc());
            assertThat(fromRange.getTranCatTypeDesc()).isEqualTo(fromText.getTranCatTypeDesc());
        }

        @Test
        @DisplayName("the byte-range entry point reads the second record at the 61-byte stride and "
                + "leaves the 0x0A terminator behind")
        void theByteRangeEntryPointReadsTheSecondRecordAtTheStride() {
            final byte[] twoRecordBuffer =
                    (FIRST_ROW_IMAGE + "\n" + MIXED_CASE_ROW_IMAGE + "\n")
                            .getBytes(StandardCharsets.US_ASCII);

            final TransactionCategory second =
                    TranCatRecordMapper.fromRecord(twoRecordBuffer, FIXTURE_STRIDE);

            assertThat(second.getTranTypeCd()).isEqualTo("07");
            assertThat(second.getTranCatCd()).isEqualTo("0001");
            assertThat(second.getTranCatTypeDesc())
                    .as("no terminator byte leaked into the 50-byte description field")
                    .isEqualTo("Sales draft credit adjustment" + " ".repeat(21))
                    .doesNotContain("\n");
            assertThat(second.getTranCatTypeDesc().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPECTED_DESCRIPTION_LENGTH);
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {

        @Test
        @DisplayName("a 59-byte image is refused rather than padded, the message naming the artefact, "
                + "the expected width of 60 and the actual encoded length of 59")
        void aFiftyNineByteImageIsRefused() {
            final String tooShort =
                    FIRST_ROW_TYPE_CODE + FIRST_ROW_CATEGORY_CODE + FIRST_ROW_DESCRIPTION_FIELD
                            + "000";
            assertThat(tooShort.getBytes(StandardCharsets.US_ASCII)).hasSize(59);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("TRAN-CAT-RECORD (CVTRA04Y)")
                    .withMessageContaining("must be exactly 60 encoded bytes")
                    .withMessageContaining("is 59 encoded bytes");
        }

        @Test
        @DisplayName("a 61-byte image is refused rather than truncated, and the diagnostic names the "
                + "unstripped 0x0A terminator as the usual cause of a one-byte overshoot")
        void aSixtyOneByteImageIsRefused() {
            final String tooLong = FIRST_ROW_IMAGE + "0";
            assertThat(tooLong.getBytes(StandardCharsets.US_ASCII)).hasSize(61);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(tooLong))
                    .withMessageContaining("TRAN-CAT-RECORD (CVTRA04Y)")
                    .withMessageContaining("must be exactly 60 encoded bytes")
                    .withMessageContaining("is 61 encoded bytes")
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("an unstripped terminator makes a fixture line 61 bytes, so the whole line is "
                + "refused and the caller must strip it")
        void anUnstrippedTerminatorIsRefused() {
            final String terminatedLine = FIRST_ROW_IMAGE + "\n";
            assertThat(terminatedLine.getBytes(StandardCharsets.US_ASCII)).hasSize(FIXTURE_STRIDE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(terminatedLine))
                    .withMessageContaining("is 61 encoded bytes");
        }

        @Test
        @DisplayName("a wrong-length byte image and a wrong-length byte range are refused too")
        void wrongLengthByteInputsAreRefused() {
            final byte[] tooShortBytes =
                    (FIRST_ROW_TYPE_CODE + FIRST_ROW_CATEGORY_CODE).getBytes(
                            StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(tooShortBytes))
                    .withMessageContaining("is 6 encoded bytes");

            final byte[] oneRecordBuffer = FIRST_ROW_IMAGE.getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a range that runs past the end of the buffer cannot yield a whole record")
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(oneRecordBuffer, 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a negative start index is refused outright")
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(oneRecordBuffer, -1));
        }

        @Test
        @DisplayName("null is refused deterministically by every entry point rather than yielding a "
                + "partly populated entity or a partly filled image")
        void nullIsRefusedByEveryEntryPoint() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((byte[]) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((byte[]) null, 0));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecordBytes(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(null));
        }

        @Test
        @DisplayName("an entity with a missing mapped value is refused on encode, the diagnostic "
                + "naming the absent legacy field")
        void anEntityWithAMissingValueIsRefusedOnEncode() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory(null, FIRST_ROW_CATEGORY_CODE,
                                    FIRST_ROW_DESCRIPTION_FIELD)))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory(FIRST_ROW_TYPE_CODE, null,
                                    FIRST_ROW_DESCRIPTION_FIELD)))
                    .withMessageContaining("TRAN-CAT-CD");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory(FIRST_ROW_TYPE_CODE, FIRST_ROW_CATEGORY_CODE,
                                    null)))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC");
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than truncated, because a "
                + "truncated field leaves the record exactly the right width while carrying a wrong "
                + "value")
        void anOverWideValueIsRefusedRatherThanTruncated() {
            final String overWideDescription = "x".repeat(EXPECTED_DESCRIPTION_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory(FIRST_ROW_TYPE_CODE, FIRST_ROW_CATEGORY_CODE,
                                    overWideDescription)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory("ABC", FIRST_ROW_CATEGORY_CODE,
                                    FIRST_ROW_DESCRIPTION_FIELD)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(
                            new TransactionCategory(FIRST_ROW_TYPE_CODE, "00001",
                                    FIRST_ROW_DESCRIPTION_FIELD)));
        }
    }

}
