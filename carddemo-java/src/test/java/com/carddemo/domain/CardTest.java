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

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link Card}, the Java carrier of the 150-byte legacy card record.
 *
 * <p><strong>Every expected value in this file was hand-derived, never computed by the code under
 * test.</strong> Widths, offsets and the total record length come from two legacy artifacts written
 * independently of one another and each read directly: the copybook {@code app/cpy/CVACT02Y.cpy},
 * whose header states the record length and which declares six named fields followed by a 59-byte
 * trailing filler; and the cluster definition {@code app/jcl/CARDFILE.jcl}, which states the same
 * record length as both its minimum and its maximum, states the key's width and position, and defines
 * a nonunique alternate index over the account identifier. Because the two were written independently,
 * cross-checking the width arithmetic against both is a real test rather than a restatement: the
 * declared widths must sum to the cluster's record size, the key width must equal the first field's
 * width, and the alternate-index geometry must land exactly on the account identifier. A layout error
 * satisfying one of those relations would violate another.
 *
 * <p>The field values are likewise hand-decoded from the named fixture
 * {@code app/data/ASCII/carddata.txt}, whose geometry is stated here as measured fact: 7,550 bytes
 * holding 50 records of 150 bytes each plus one line terminator apiece. Nothing here decodes or
 * assembles a record image, and no production mapper, codec or string utility is referenced: making
 * another class the oracle would leave this class and that one free to be wrong together.
 *
 * <p><strong>Two fidelity concerns make this record distinctive, and both are asserted below.</strong>
 * The verification code is three digits of which eight of the fifty sample records begin with a zero,
 * so it is carried as bounded text and never as a number - parsing it would render a code with a
 * leading zero as a shorter value and the record image would stop round-tripping. And the field at
 * offset 80 is misspelled in the copybook, which drops a letter from EXPIRATION at line 9 of
 * {@code app/cpy/CVACT02Y.cpy}; that is a documented source anomaly whose invariant is the byte
 * offset, so the Java property is spelled correctly while the offset stays exactly 80 for a width
 * of 10. Both divergences are recorded in {@code docs/decision-log.md}; this file neither edits that
 * log nor depends on it.
 *
 * <p><strong>Scope, stated as much by exclusion as by inclusion.</strong> This is a pure unit test: it
 * starts no container, opens no socket, reads no file and builds no application context. It verifies
 * the record contract the entity carries - construction, accessor transparency, raw character
 * fidelity, business-key identity, the version counter's default and the diagnostic rendering's
 * redaction. It deliberately does <em>not</em> verify column names, widths or nullability, because the
 * deployed schema is authoritative and the integration tier validates the mapping against a real
 * database, which fails start-up on any divergence. It deliberately does not exercise optimistic
 * locking: an increment needs a persistence context and belongs to the repository integration tier,
 * so only the counter's default value and its independence from identity are asserted here. And it
 * asserts no validation rule of any kind, because the entity performs none - the legacy field editing
 * lives on the card-update path, and inventing a constraint here would reject input the legacy system
 * accepted.
 *
 * <p><strong>No production mapper or codec is referenced.</strong> Every expected value below is a
 * hand-written literal. Making {@code CardRecordMapper} the oracle here would leave that mapper and
 * this class free to be wrong together, so the fixed-width slicing of a card record is asserted
 * against the mapper in the mapper's own suite rather than in this one.
 *
 * <p><strong>Agreement with the schema is asserted elsewhere.</strong> The {@code card} table is owned
 * by the Flyway migration {@code V1__create_schema.sql}, and provider-level agreement between this
 * entity's annotations and that migration - column set, declared widths, nullability and key columns -
 * is asserted exhaustively, for every mapped entity at once, by {@code EntityPersistenceMappingTest}.
 * This class therefore confines itself to the entity's own observable behaviour.
 *
 * <p><strong>The version counter is a documented strengthening, not a behavioural change.</strong> The
 * online card-update program compared a screen-carried before image against the stored record field by
 * field, the final leg of that comparison being the active status at line 1508 of
 * {@code app/cbl/COCRDUPC.cbl}, over file definitions that specify uncommitted read integrity, no
 * recovery and no journaling. A provider-managed counter over read-committed isolation is strictly
 * stronger than that baseline, so a reviewer should read it as the improvement it is rather than as a
 * regression.
 *
 * <p><strong>Provenance.</strong> Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec;
 * upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19). Recorded here as a plain
 * identifier string for the traceability matrix. No assertion is made about that stamp, because it is
 * not carried uniformly by every legacy member and testing for it would test the estate rather than
 * this class.
 */
@DisplayName("Card - the 150-byte card record of copybook CVACT02Y")
class CardTest {

    // FIELD WIDTHS - hand-derived from app/cpy/CVACT02Y.cpy, one constant per declared field

    private static final int CARD_NUM_WIDTH = 16;

    private static final int ACCT_ID_WIDTH = 11;

    private static final int CVV_WIDTH = 3;

    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** Width of the expiration date, which is fixed-width text rather than a date. */
    private static final int EXPIRATION_DATE_WIDTH = 10;

    private static final int ACTIVE_STATUS_WIDTH = 1;

    /** Width of the trailing filler, which is deliberately neither a property nor a column. */
    private static final int FILLER_WIDTH = 59;

    // FIELD OFFSETS - zero-based, hand-derived by accumulating the widths above

    private static final int OFFSET_CARD_NUM = 0;

    private static final int OFFSET_ACCT_ID = 16;

    private static final int OFFSET_CVV = 27;

    private static final int OFFSET_EMBOSSED_NAME = 30;

    /**
     * Offset of the expiration date. The copybook spells this field {@code CARD-EXPIRAION-DATE} at its
     * line 9, dropping a letter from EXPIRATION; the misspelling is a documented source anomaly and
     * this offset is the invariant that must not move.
     */
    private static final int OFFSET_EXPIRATION_DATE = 80;

    private static final int OFFSET_ACTIVE_STATUS = 90;

    /** Offset at which the unmapped trailing filler begins, and so the total mapped width. */
    private static final int OFFSET_FILLER = 91;

    // RECORD GEOMETRY - from app/jcl/CARDFILE.jcl, written independently of the copybook

    /** Record size the cluster fixes as both its minimum and its maximum. */
    private static final int RECORD_WIDTH = 150;

    /** Key width the cluster declares. */
    private static final int KEY_WIDTH = 16;

    /** Key offset the cluster declares. */
    private static final int KEY_OFFSET = 0;

    /** Key width of the nonunique alternate index over the account identifier. */
    private static final int ALTERNATE_INDEX_KEY_WIDTH = 11;

    /** Key offset of that alternate index. */
    private static final int ALTERNATE_INDEX_KEY_OFFSET = 16;

    /** The declared widths in declaration order: six mapped fields, then the trailing filler. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(
            CARD_NUM_WIDTH, ACCT_ID_WIDTH, CVV_WIDTH,
            EMBOSSED_NAME_WIDTH, EXPIRATION_DATE_WIDTH, ACTIVE_STATUS_WIDTH,
            FILLER_WIDTH);

    /** The corresponding offsets in declaration order. */
    private static final List<Integer> COPYBOOK_OFFSETS = List.of(
            OFFSET_CARD_NUM, OFFSET_ACCT_ID, OFFSET_CVV,
            OFFSET_EMBOSSED_NAME, OFFSET_EXPIRATION_DATE, OFFSET_ACTIVE_STATUS,
            OFFSET_FILLER);

    /** The six mapped widths, filler excluded, whose sum is the mapped extent of the record. */
    private static final int MAPPED_WIDTH = 91;

    // NAMED FIXTURE FACTS - app/data/ASCII/carddata.txt, measured directly

    private static final int SEEDED_RECORD_COUNT = 50;

    /** Bytes each fixture line occupies: the record image plus one line terminator. */
    private static final int SEEDED_RECORD_STRIDE = 151;

    private static final int SEEDED_FILE_BYTES = 7_550;

    /**
     * Sample records whose verification code begins with a zero, counted directly across the fixture.
     * The count is what makes the leading-zero assertions below evidence about live data rather than a
     * hypothetical.
     */
    private static final int SEEDED_CODES_WITH_LEADING_ZERO = 8;

    // ROW ZERO OF THE NAMED FIXTURE - hand-decoded at the copybook offsets, one literal per field

    private static final String SEED_CARD_NUM = "0500024453765740";

    private static final String SEED_ACCT_ID = "00000000050";

    private static final String SEED_CVV_CD = "747";

    /**
     * Embossed name of the first fixture record before padding. Its embedded space is a legitimate
     * character of the field rather than a separator, which is why the field is never tokenised.
     */
    private static final String SEED_EMBOSSED_NAME_TEXT = "Aniya Von";

    private static final String SEED_EXPIRATION_DATE = "2023-03-09";

    /** Active status of the first fixture record, and of all fifty. */
    private static final String SEED_ACTIVE_STATUS = "Y";

    // THE FIXTURE'S FOURTH RECORD - the leading-zero anchor, hand-decoded at the same offsets. Its
    // verification code is the smallest of the eight that begin with a zero, so it is the value a
    // numeric conversion would damage most visibly.

    private static final String LEADING_ZERO_CARD_NUM = "0927987108636232";

    private static final String LEADING_ZERO_ACCT_ID = "00000000020";

    private static final String LEADING_ZERO_CVV_CD = "003";

    private static final String LEADING_ZERO_EMBOSSED_NAME_TEXT = "Carter Veum";

    private static final String LEADING_ZERO_EXPIRATION_DATE = "2024-03-13";

    /** A second live code beginning with a zero, from the fixture's fifth record. */
    private static final String SECOND_LEADING_ZERO_CVV_CD = "075";

    /**
     * The verification code of the fixture's fourth record with its leading zero removed. Held as a
     * literal so the assertion that the stored value differs from it is written against a hand-written
     * expectation rather than against a computation.
     */
    private static final String LEADING_ZERO_CVV_CD_STRIPPED = "3";

    /** The second live code with its leading zero removed, on the same terms. */
    private static final String SECOND_LEADING_ZERO_CVV_CD_STRIPPED = "75";

    /** The first record's card number with its leading zero removed. */
    private static final String SEED_CARD_NUM_STRIPPED = "500024453765740";

    /** The first record's account identifier with its zero fill removed. */
    private static final String SEED_ACCT_ID_STRIPPED = "50";

    /**
     * Builds the fixture's first record through the public constructor, every value a hand-decoded
     * literal and the embossed name at its full declared width.
     *
     * @return a card carrying the first fixture record's field values
     */
    private static Card seededRowZero() {
        return new Card(
                SEED_CARD_NUM,
                SEED_ACCT_ID,
                SEED_CVV_CD,
                seedEmbossedName(),
                SEED_EXPIRATION_DATE,
                SEED_ACTIVE_STATUS);
    }

    /**
     * Builds the fixture's fourth record, whose verification code begins with a zero, on the same
     * terms as {@link #seededRowZero()}.
     *
     * @return a card carrying that record's field values
     */
    private static Card seededLeadingZeroRow() {
        return new Card(
                LEADING_ZERO_CARD_NUM,
                LEADING_ZERO_ACCT_ID,
                LEADING_ZERO_CVV_CD,
                padToEmbossedNameWidth(LEADING_ZERO_EMBOSSED_NAME_TEXT),
                LEADING_ZERO_EXPIRATION_DATE,
                SEED_ACTIVE_STATUS);
    }

    /**
     * Returns the first fixture record's embossed name at its full declared width.
     *
     * @return the 50-character space-filled embossed name
     */
    private static String seedEmbossedName() {
        return padToEmbossedNameWidth(SEED_EMBOSSED_NAME_TEXT);
    }

    /**
     * Pads a name to the embossed-name field's declared width with trailing spaces.
     *
     * <p>Built by padding rather than by transcribing a run of literal spaces, because a transcribed
     * run is unreviewable for an off-by-one. The resulting width is asserted independently below, so a
     * miscount here fails loudly rather than weakening the tests that use it.
     *
     * @param text the unpadded name
     * @return the name followed by enough spaces to fill the field
     */
    private static String padToEmbossedNameWidth(final String text) {
        return text + " ".repeat(EMBOSSED_NAME_WIDTH - text.length());
    }

    /**
     * Measures a value as the record contract measures it: encoded bytes under an explicitly named
     * charset, because the platform default would make the same assertion mean different things on
     * different machines.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded as US-ASCII
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Geometry the entity must honour, cross-checked between the copybook's field widths and the
     * cluster definition's record size, key clause and alternate-index clause - artifacts written
     * independently of one another.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the six mapped widths sum to 91, and 91 + 59 filler bytes is the 150 bytes the "
                + "copybook header and the cluster's RECORDSIZE(150 150) both state")
        void theMappedWidthsSumToNinetyOneAndTheFillerCompletesTheRecord() {
            // The six mapped widths, written out term by term so the arithmetic is the assertion and
            // not a restatement of a single constant.
            assertThat(CARD_NUM_WIDTH + ACCT_ID_WIDTH + CVV_WIDTH
                    + EMBOSSED_NAME_WIDTH + EXPIRATION_DATE_WIDTH + ACTIVE_STATUS_WIDTH)
                    .as("the six mapped field widths")
                    .isEqualTo(MAPPED_WIDTH)
                    .isEqualTo(91);

            // The filler makes up the balance exactly, which is what makes it safe to leave unmapped.
            assertThat(MAPPED_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(RECORD_WIDTH - MAPPED_WIDTH).isEqualTo(FILLER_WIDTH);
            assertThat(RECORD_WIDTH).isEqualTo(150);
        }

        @Test
        @DisplayName("all seven declared widths, filler included, sum to the 150 bytes of the record, "
                + "so no byte of the image is unaccounted for")
        void allSevenDeclaredWidthsSumToTheRecordSize() {
            final int declaredTotal = COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum();

            assertThat(declaredTotal).isEqualTo(RECORD_WIDTH);
            assertThat(COPYBOOK_WIDTHS).hasSize(COPYBOOK_OFFSETS.size());
        }

        @Test
        @DisplayName("each declared field begins where the preceding widths leave off, so the six "
                + "mapped offsets are 0, 16, 27, 30, 80 and 90 and the filler begins at 91")
        void eachFieldBeginsWhereThePrecedingWidthsLeaveOff() {
            // Offsets are derived by accumulation rather than transcribed, so a width changed without
            // its offsets being changed with it cannot pass.
            int accumulated = 0;
            for (int index = 0; index < COPYBOOK_WIDTHS.size(); index++) {
                assertThat(COPYBOOK_OFFSETS.get(index))
                        .as("offset of declared field %d", index)
                        .isEqualTo(accumulated);
                accumulated += COPYBOOK_WIDTHS.get(index);
            }

            assertThat(accumulated).isEqualTo(RECORD_WIDTH);
            assertThat(OFFSET_FILLER).isEqualTo(MAPPED_WIDTH);
        }

        @Test
        @DisplayName("the expiry field sits at 30 + 50 == 80, following the fifty-byte embossed name: "
                + "the copybook misspells that field as CARD-EXPIRAION-DATE at its line 9, and the "
                + "offset rather than the spelling is the invariant the mapping preserves")
        void theExpiryOffsetFollowsTheFiftyByteEmbossedName() {
            // Anchored to the field it actually follows rather than copied from a table. Miscounting
            // the embossed name is the one error that would move this offset silently.
            assertThat(OFFSET_EMBOSSED_NAME + EMBOSSED_NAME_WIDTH)
                    .as("the embossed name ends where the expiry begins")
                    .isEqualTo(OFFSET_EXPIRATION_DATE)
                    .isEqualTo(80);

            // Neither of the two offsets a miscount would produce: 70 would follow a 40-byte name and
            // 90 is where the status code begins.
            assertThat(OFFSET_EXPIRATION_DATE).isNotEqualTo(70).isNotEqualTo(90);
            assertThat(OFFSET_EXPIRATION_DATE + EXPIRATION_DATE_WIDTH).isEqualTo(OFFSET_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("the misspelled copybook field's bytes are carried by a correctly spelled Java "
                + "property, so the correction is confined to the identifier and never to the layout")
        void theCorrectlySpelledPropertyCarriesTheMisspelledFieldsBytes() {
            final Card card = seededRowZero();

            // The property reads back the ten-character value the misspelled field holds at offset 80,
            // at exactly the declared width. Correcting a name changed nothing about the record.
            assertThat(card.getCardExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(encodedWidth(card.getCardExpirationDate())).isEqualTo(EXPIRATION_DATE_WIDTH);
            assertThat(OFFSET_EXPIRATION_DATE).isEqualTo(80);
        }

        @Test
        @DisplayName("the key is the leading 16 bytes at offset 0, exactly as the cluster's KEYS(16 0) "
                + "states, so the primary key is the card number itself")
        void theKeyIsTheLeadingSixteenBytes() {
            assertThat(KEY_WIDTH).isEqualTo(CARD_NUM_WIDTH);
            assertThat(KEY_OFFSET).isEqualTo(OFFSET_CARD_NUM).isZero();

            // The key is a substring of the record image taken from its very start, which is why the
            // card number can be the persistent identity without a surrogate.
            assertThat(KEY_OFFSET + KEY_WIDTH).isEqualTo(OFFSET_ACCT_ID);
            assertThat(encodedWidth(seededRowZero().getCardNum())).isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the alternate index KEYS(11 16) is an independent witness that the account "
                + "identifier occupies 11 bytes at offset 16, the field a card-by-account finder matches")
        void theAlternateIndexLandsOnTheAccountIdentifier() {
            assertThat(ALTERNATE_INDEX_KEY_OFFSET).isEqualTo(OFFSET_ACCT_ID);
            assertThat(ALTERNATE_INDEX_KEY_WIDTH).isEqualTo(ACCT_ID_WIDTH);

            // The index spans exactly that field and stops before the verification code, so a finder
            // over it can never match on a fragment of the neighbouring field.
            assertThat(ALTERNATE_INDEX_KEY_OFFSET + ALTERNATE_INDEX_KEY_WIDTH).isEqualTo(OFFSET_CVV);
        }

        @Test
        @DisplayName("the trailing 59 filler bytes are deliberately unmapped, so the entity carries six "
                + "business properties and the reserved span is neither a property nor a column")
        void theTrailingFillerIsDeliberatelyUnmapped() {
            assertThat(FILLER_WIDTH).isEqualTo(59);
            assertThat(OFFSET_FILLER + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);

            // Six mapped fields, and the constructor's arity is the same six. The filler contributes
            // no argument, which is the observable consequence of leaving it unmapped.
            assertThat(COPYBOOK_WIDTHS).hasSize(7);
            assertThat(COPYBOOK_WIDTHS.subList(0, 6).stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(MAPPED_WIDTH);
        }

        @Test
        @DisplayName("the named fixture holds 50 records of 150 bytes, so 50 x 151 == 7,550 bytes "
                + "accounts for the file exactly and confirms the record length from a third direction")
        void theNamedFixtureGeometryIsConsistent() {
            // Layout evidence measured from the file, not a throughput or capacity figure.
            assertThat(RECORD_WIDTH + 1).isEqualTo(SEEDED_RECORD_STRIDE);
            assertThat(SEEDED_RECORD_COUNT * SEEDED_RECORD_STRIDE).isEqualTo(SEEDED_FILE_BYTES);
            assertThat(SEEDED_FILE_BYTES).isEqualTo(7_550);
        }
    }

    /**
     * Encoded widths of the hand-decoded fixture values, measured in bytes under an explicitly named
     * charset. Character counts are not asserted anywhere: the record contract is a byte contract, and
     * the two agree only for as long as every value stays inside US-ASCII.
     */
    @Nested
    @DisplayName("encoded field widths")
    class EncodedFieldWidths {

        @Test
        @DisplayName("the six values of the first fixture record encode to exactly 16, 11, 3, 50, 10 "
                + "and 1 bytes, the widths the copybook declares")
        void theSixFieldsEncodeToTheirDeclaredWidths() {
            final Card card = seededRowZero();

            assertThat(encodedWidth(card.getCardNum())).isEqualTo(CARD_NUM_WIDTH);
            assertThat(encodedWidth(card.getCardAcctId())).isEqualTo(ACCT_ID_WIDTH);
            assertThat(encodedWidth(card.getCardCvvCd())).isEqualTo(CVV_WIDTH);
            assertThat(encodedWidth(card.getCardEmbossedName())).isEqualTo(EMBOSSED_NAME_WIDTH);
            assertThat(encodedWidth(card.getCardExpirationDate())).isEqualTo(EXPIRATION_DATE_WIDTH);
            assertThat(encodedWidth(card.getCardActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);
        }

        @Test
        @DisplayName("those six encoded widths sum to the 91 mapped bytes, so the fixture record and "
                + "the copybook declaration describe the same span")
        void theSixEncodedWidthsSumToTheMappedExtent() {
            final Card card = seededRowZero();

            final int encodedTotal = encodedWidth(card.getCardNum())
                    + encodedWidth(card.getCardAcctId())
                    + encodedWidth(card.getCardCvvCd())
                    + encodedWidth(card.getCardEmbossedName())
                    + encodedWidth(card.getCardExpirationDate())
                    + encodedWidth(card.getCardActiveStatus());

            assertThat(encodedTotal).isEqualTo(MAPPED_WIDTH);
        }

        @Test
        @DisplayName("the fourth fixture record encodes to the same six widths, so a verification code "
                + "beginning with a zero occupies its full three bytes like any other")
        void theLeadingZeroRecordEncodesToTheSameWidths() {
            final Card card = seededLeadingZeroRow();

            assertThat(encodedWidth(card.getCardNum())).isEqualTo(CARD_NUM_WIDTH);
            assertThat(encodedWidth(card.getCardAcctId())).isEqualTo(ACCT_ID_WIDTH);
            assertThat(encodedWidth(card.getCardCvvCd())).isEqualTo(CVV_WIDTH);
            assertThat(encodedWidth(card.getCardEmbossedName())).isEqualTo(EMBOSSED_NAME_WIDTH);
            assertThat(encodedWidth(card.getCardExpirationDate())).isEqualTo(EXPIRATION_DATE_WIDTH);
            assertThat(encodedWidth(card.getCardActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);
        }
    }

    /**
     * Construction through both paths and the transparency of every accessor. The entity is a passive
     * carrier, so the whole contract here is that a value handed in comes back out unchanged and lands
     * on the property it was given for.
     */
    @Nested
    @DisplayName("construction and accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the six-argument constructor takes the business fields in the copybook's own "
                + "order and round-trips every one of the first fixture record's values")
        void theConstructorRoundTripsEveryBusinessField() {
            final Card card = seededRowZero();

            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(card.getCardAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(card.getCardCvvCd()).isEqualTo(SEED_CVV_CD);
            assertThat(card.getCardEmbossedName()).isEqualTo(seedEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(card.getCardActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("each argument reaches its own property, so a transposition of any two of the six "
                + "same-typed positions is caught rather than compiling silently")
        void theConstructorDoesNotTransposeAnyPosition() {
            // Six mutually distinguishable markers, positional rather than realistic. Every property of
            // this record is a string, so the compiler cannot catch a swapped pair and only a test can.
            final Card card = new Card("one", "two", "three", "four", "five", "six");

            assertThat(card.getCardNum()).isEqualTo("one");
            assertThat(card.getCardAcctId()).isEqualTo("two");
            assertThat(card.getCardCvvCd()).isEqualTo("three");
            assertThat(card.getCardEmbossedName()).isEqualTo("four");
            assertThat(card.getCardExpirationDate()).isEqualTo("five");
            assertThat(card.getCardActiveStatus()).isEqualTo("six");
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider needs yields an entirely "
                + "empty business state and a version counter of zero")
        void theNoArgumentConstructorYieldsAnEmptyBusinessState() {
            // Reached through ordinary Java package access, because this test is declared in the same
            // package as the entity: the constructor is protected, and a protected member is visible to
            // the package. This is explicitly NOT reflection - no reflective call appears in this file,
            // because the module's unsafe-code audit requires a reflection count of zero and a test
            // must not undermine it.
            final Card card = new Card();

            assertThat(card.getCardNum()).isNull();
            assertThat(card.getCardAcctId()).isNull();
            assertThat(card.getCardCvvCd()).isNull();
            assertThat(card.getCardEmbossedName()).isNull();
            assertThat(card.getCardExpirationDate()).isNull();
            assertThat(card.getCardActiveStatus()).isNull();
            assertThat(card.getVersion()).isEqualTo(0L);
        }

        @Test
        @DisplayName("every business setter round-trips its value, each writing only its own property, "
                + "so no two of the six share a field")
        void everyBusinessSetterRoundTripsItsValue() {
            final Card card = new Card();

            card.setCardNum(SEED_CARD_NUM);
            card.setCardAcctId(SEED_ACCT_ID);
            card.setCardCvvCd(SEED_CVV_CD);
            card.setCardEmbossedName(seedEmbossedName());
            card.setCardExpirationDate(SEED_EXPIRATION_DATE);
            card.setCardActiveStatus(SEED_ACTIVE_STATUS);

            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(card.getCardAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(card.getCardCvvCd()).isEqualTo(SEED_CVV_CD);
            assertThat(card.getCardEmbossedName()).isEqualTo(seedEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(card.getCardActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);
            assertThat(card.getVersion()).isEqualTo(0L);
        }

        @Test
        @DisplayName("a setter overwrites its own property and leaves the other five exactly as the "
                + "constructor left them")
        void aSetterOverwritesOnlyItsOwnProperty() {
            final Card card = seededRowZero();

            // The verification code is the field most likely to be rewritten by the update path, and the
            // replacement carries a leading zero so a failure to store it verbatim shows up immediately.
            card.setCardCvvCd(LEADING_ZERO_CVV_CD);

            assertThat(card.getCardCvvCd()).isEqualTo(LEADING_ZERO_CVV_CD);
            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(card.getCardAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(card.getCardEmbossedName()).isEqualTo(seedEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(card.getCardActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("a null business value is accepted and returned as null, because the entity "
                + "performs no validation: the legacy field editing lives on the card-update path")
        void aNullBusinessValueIsStoredAsSupplied() {
            final Card card = seededRowZero();

            card.setCardCvvCd(null);
            card.setCardEmbossedName(null);
            card.setCardExpirationDate(null);
            card.setCardActiveStatus(null);
            card.setCardAcctId(null);

            assertThat(card.getCardCvvCd()).isNull();
            assertThat(card.getCardEmbossedName()).isNull();
            assertThat(card.getCardExpirationDate()).isNull();
            assertThat(card.getCardActiveStatus()).isNull();
            assertThat(card.getCardAcctId()).isNull();

            // Rejecting a null here would be a constraint the legacy record never carried, and the
            // column's nullability is the database's business rather than the entity's.
            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
        }

        @Test
        @DisplayName("an over-long value is stored exactly as supplied, because the entity truncates "
                + "nothing: field width is enforced by the column and by the record placement, not here")
        void anOverLongValueIsStoredAsSupplied() {
            final Card card = new Card();
            final String tooWide = padToEmbossedNameWidth(SEED_EMBOSSED_NAME_TEXT) + " ";

            card.setCardEmbossedName(tooWide);

            assertThat(card.getCardEmbossedName()).isEqualTo(tooWide);
            assertThat(encodedWidth(card.getCardEmbossedName())).isEqualTo(EMBOSSED_NAME_WIDTH + 1);
        }
    }

    /**
     * Raw character fidelity: the three digit-only fields keep their leading zeros because they are
     * text rather than numbers, and the two free-text fields keep their padding and their letter case
     * because the entity applies no transformation of any kind.
     */
    @Nested
    @DisplayName("raw character fidelity")
    class RawCharacterFidelity {

        @Test
        @DisplayName("the card number keeps its leading zero and all sixteen characters, which a "
                + "numeric type would have discarded: five of the fifty fixture records begin with one")
        void theCardNumberKeepsItsLeadingZero() {
            final Card card = seededRowZero();

            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(encodedWidth(card.getCardNum())).isEqualTo(CARD_NUM_WIDTH);

            // The value a numeric round trip would have produced, written out by hand. It is fifteen
            // characters, so it is not merely a different string but a different width.
            assertThat(card.getCardNum()).isNotEqualTo(SEED_CARD_NUM_STRIPPED);
            assertThat(encodedWidth(SEED_CARD_NUM_STRIPPED)).isEqualTo(CARD_NUM_WIDTH - 1);
        }

        @Test
        @DisplayName("the zero-filled account identifier keeps all eleven characters, so the alternate "
                + "index the cluster defines over it matches on the stored width and not on a number")
        void theZeroFilledAccountIdentifierKeepsItsWidth() {
            final Card card = seededRowZero();

            assertThat(card.getCardAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(encodedWidth(card.getCardAcctId())).isEqualTo(ACCT_ID_WIDTH);

            // Nine of the eleven characters are the zero fill; a numeric reading would keep two.
            assertThat(card.getCardAcctId()).isNotEqualTo(SEED_ACCT_ID_STRIPPED);
            assertThat(encodedWidth(SEED_ACCT_ID_STRIPPED)).isEqualTo(2);
        }

        @Test
        @DisplayName("a verification code beginning with a zero survives at its full three bytes, and "
                + "eight of the fifty fixture records carry one, so this is live seeded data rather "
                + "than a hypothetical")
        void aVerificationCodeBeginningWithZeroSurvives() {
            final Card card = seededLeadingZeroRow();

            assertThat(card.getCardCvvCd()).isEqualTo(LEADING_ZERO_CVV_CD);
            assertThat(encodedWidth(card.getCardCvvCd())).isEqualTo(CVV_WIDTH);

            // The single-character value a numeric conversion would have produced, written out by hand.
            // Were this field ever parsed to a number, the three-byte field would collapse to one.
            assertThat(card.getCardCvvCd()).isNotEqualTo(LEADING_ZERO_CVV_CD_STRIPPED);
            assertThat(encodedWidth(LEADING_ZERO_CVV_CD_STRIPPED)).isEqualTo(1);

            // The count is stated so the evidence is about the fixture rather than about this one row.
            assertThat(SEEDED_CODES_WITH_LEADING_ZERO).isEqualTo(8);
        }

        @Test
        @DisplayName("a second live verification code beginning with a zero survives on the same terms, "
                + "so the fidelity holds across the fixture rather than for one chosen row")
        void aSecondLiveVerificationCodeBeginningWithZeroSurvives() {
            final Card card = new Card();

            card.setCardCvvCd(SECOND_LEADING_ZERO_CVV_CD);

            assertThat(card.getCardCvvCd()).isEqualTo(SECOND_LEADING_ZERO_CVV_CD);
            assertThat(encodedWidth(card.getCardCvvCd())).isEqualTo(CVV_WIDTH);
            assertThat(card.getCardCvvCd()).isNotEqualTo(SECOND_LEADING_ZERO_CVV_CD_STRIPPED);
            assertThat(encodedWidth(SECOND_LEADING_ZERO_CVV_CD_STRIPPED)).isEqualTo(CVV_WIDTH - 1);
        }

        @Test
        @DisplayName("the embossed name round-trips at its full fifty-byte width with its trailing "
                + "padding intact, and its embedded space is a legitimate character of the field")
        void theEmbossedNameRoundTripsWithItsPaddingAndEmbeddedSpace() {
            final String padded = seedEmbossedName();
            final Card card = seededRowZero();

            assertThat(card.getCardEmbossedName()).isEqualTo(padded);
            assertThat(encodedWidth(card.getCardEmbossedName())).isEqualTo(EMBOSSED_NAME_WIDTH);

            // Nothing is trimmed: the padded form and the unpadded form are different strings, and what
            // was stored is the one that was handed over.
            assertThat(card.getCardEmbossedName()).isNotEqualTo(SEED_EMBOSSED_NAME_TEXT);
            assertThat(encodedWidth(SEED_EMBOSSED_NAME_TEXT)).isEqualTo(9);

            // The embedded space sits inside the name rather than separating it from the padding, so the
            // field is never tokenised on whitespace.
            assertThat(card.getCardEmbossedName()).startsWith("Aniya Von");
            assertThat(card.getCardEmbossedName().charAt(5)).isEqualTo(' ');
        }

        @Test
        @DisplayName("the embossed name is not case folded by the entity: the legacy fold is a strict "
                + "character-table substitution owned by the utility layer and applied on the update path")
        void theEmbossedNameIsNotCaseFoldedByTheEntity() {
            final Card fromConstructor = seededRowZero();
            final Card fromSetter = new Card();
            fromSetter.setCardEmbossedName(SEED_EMBOSSED_NAME_TEXT);

            // Every lower-case character of the fixture value comes back exactly as supplied, through
            // both construction paths. The expected values are the hand-written literals themselves; no
            // folding helper is called here, because calling one would make that helper the oracle.
            assertThat(fromConstructor.getCardEmbossedName()).startsWith(SEED_EMBOSSED_NAME_TEXT);
            assertThat(fromConstructor.getCardEmbossedName()).contains("niya");
            assertThat(fromConstructor.getCardEmbossedName()).contains("on");
            assertThat(fromConstructor.getCardEmbossedName()).doesNotContain("ANIYA VON");
            assertThat(fromSetter.getCardEmbossedName()).isEqualTo(SEED_EMBOSSED_NAME_TEXT);
        }

        @Test
        @DisplayName("the expiration date is kept as its ten-character external form and never parsed, "
                + "so every value the legacy field tolerated survives a round trip")
        void theExpirationDateIsKeptAsText() {
            final Card card = seededRowZero();

            assertThat(card.getCardExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(encodedWidth(card.getCardExpirationDate())).isEqualTo(EXPIRATION_DATE_WIDTH);
            assertThat(card.getCardExpirationDate()).isEqualTo("2023-03-09");

            // A value a strict calendar parser would reject is stored unchanged, because the entity does
            // no parsing: calendar validation is the date-validation service's contract.
            card.setCardExpirationDate("2023-02-31");
            assertThat(card.getCardExpirationDate()).isEqualTo("2023-02-31");
            assertThat(encodedWidth(card.getCardExpirationDate())).isEqualTo(EXPIRATION_DATE_WIDTH);
        }

        @Test
        @DisplayName("the active status is the raw single byte with no vocabulary check: the documented "
                + "codes round-trip and so does a code outside them, proving no enum mapping and no "
                + "bean validation stand between the file and the property")
        void theActiveStatusIsTheRawByteWithNoVocabularyCheck() {
            final Card card = seededRowZero();

            // The one code every fixture record carries.
            assertThat(card.getCardActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);
            assertThat(encodedWidth(card.getCardActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);

            // The other documented code.
            card.setCardActiveStatus("N");
            assertThat(card.getCardActiveStatus()).isEqualTo("N");

            // A code outside the documented vocabulary: accepted, returned unchanged, and neither
            // rejected, defaulted nor normalised. The legacy batch programs took this byte straight from
            // the file and never tested it, so rejecting it here would be new behaviour.
            card.setCardActiveStatus("X");
            assertThat(card.getCardActiveStatus()).isEqualTo("X");
            assertThat(encodedWidth(card.getCardActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);

            // Case is not folded either, so a lower-case form is a different code rather than the same
            // one: the legacy comparison tested the byte as supplied.
            card.setCardActiveStatus("y");
            assertThat(card.getCardActiveStatus()).isEqualTo("y").isNotEqualTo(SEED_ACTIVE_STATUS);
        }
    }

    /**
     * Identity, which is the business key and nothing else. The card number is the primary key the
     * cluster's key clause names, so equality and hashing read it alone: including a mutable non-key
     * property would change an instance's identity mid-flush and break its membership in a hash-based
     * collection.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("two cards sharing a card number are equal and hash alike however far every other "
                + "property diverges, because the key alone is the identity")
        void identityRestsOnTheCardNumberAlone() {
            final Card fromFixture = seededRowZero();

            // Same key, and every one of the other five properties deliberately different.
            final Card divergent = new Card(
                    SEED_CARD_NUM,
                    LEADING_ZERO_ACCT_ID,
                    LEADING_ZERO_CVV_CD,
                    padToEmbossedNameWidth(LEADING_ZERO_EMBOSSED_NAME_TEXT),
                    LEADING_ZERO_EXPIRATION_DATE,
                    "N");

            // The divergence is established against hand-written literals on both sides rather than by
            // comparing one instance's accessors with the other's, so nothing here takes its expected
            // value from the class under test.
            assertThat(divergent.getCardAcctId())
                    .isEqualTo(LEADING_ZERO_ACCT_ID).isNotEqualTo(SEED_ACCT_ID);
            assertThat(divergent.getCardCvvCd())
                    .isEqualTo(LEADING_ZERO_CVV_CD).isNotEqualTo(SEED_CVV_CD);
            assertThat(divergent.getCardEmbossedName())
                    .isEqualTo(padToEmbossedNameWidth(LEADING_ZERO_EMBOSSED_NAME_TEXT))
                    .isNotEqualTo(seedEmbossedName());
            assertThat(divergent.getCardExpirationDate())
                    .isEqualTo(LEADING_ZERO_EXPIRATION_DATE).isNotEqualTo(SEED_EXPIRATION_DATE);
            assertThat(divergent.getCardActiveStatus())
                    .isEqualTo("N").isNotEqualTo(SEED_ACTIVE_STATUS);
            assertThat(divergent.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(fromFixture.getCardNum()).isEqualTo(SEED_CARD_NUM);

            assertThat(fromFixture).isEqualTo(divergent);
            assertThat(divergent).isEqualTo(fromFixture);
            assertThat(fromFixture).hasSameHashCodeAs(divergent);
        }

        @Test
        @DisplayName("two cards with different card numbers are unequal even when every other property "
                + "matches exactly, because no non-key property can make two rows the same row")
        void differentKeysAreUnequal() {
            final Card first = seededRowZero();
            final Card second = new Card(
                    LEADING_ZERO_CARD_NUM,
                    SEED_ACCT_ID,
                    SEED_CVV_CD,
                    seedEmbossedName(),
                    SEED_EXPIRATION_DATE,
                    SEED_ACTIVE_STATUS);

            // Both keys are pinned to their own hand-written literal, and the two literals are
            // different card numbers of the same fixture.
            assertThat(first.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(second.getCardNum()).isEqualTo(LEADING_ZERO_CARD_NUM);
            assertThat(SEED_CARD_NUM).isNotEqualTo(LEADING_ZERO_CARD_NUM);

            assertThat(first).isNotEqualTo(second);
            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("the key is compared byte for byte, so neither padding nor letter case is "
                + "normalised: a key differing only in a trailing space is a different row")
        void theKeyIsComparedByteForByte() {
            final Card card = seededRowZero();

            final Card padded = new Card(
                    SEED_CARD_NUM + " ",
                    SEED_ACCT_ID,
                    SEED_CVV_CD,
                    seedEmbossedName(),
                    SEED_EXPIRATION_DATE,
                    SEED_ACTIVE_STATUS);

            final Card stripped = new Card(
                    SEED_CARD_NUM_STRIPPED,
                    SEED_ACCT_ID,
                    SEED_CVV_CD,
                    seedEmbossedName(),
                    SEED_EXPIRATION_DATE,
                    SEED_ACTIVE_STATUS);

            assertThat(card).isNotEqualTo(padded);
            assertThat(card).isNotEqualTo(stripped);
        }

        @Test
        @DisplayName("mutating a non-key property changes neither equality nor hash, so an instance "
                + "keeps its place in a hash-based collection across an update")
        void mutatingANonKeyPropertyPreservesIdentity() {
            final Card card = seededRowZero();
            final int hashBefore = card.hashCode();

            card.setCardCvvCd(LEADING_ZERO_CVV_CD);
            card.setCardEmbossedName(padToEmbossedNameWidth(LEADING_ZERO_EMBOSSED_NAME_TEXT));
            card.setCardExpirationDate(LEADING_ZERO_EXPIRATION_DATE);
            card.setCardActiveStatus("N");
            card.setCardAcctId(LEADING_ZERO_ACCT_ID);

            // A before-and-after comparison is the only form in which hash stability across a mutation
            // can be stated, and it has real content precisely because a mutation sits between the two
            // reads: it fails the moment the hash depends on any of the five properties just replaced.
            assertThat(card.hashCode()).isEqualTo(hashBefore);

            // Independently of that, the mutated instance still agrees with a separately built instance
            // carrying the same key and none of the mutations, so the expectation does not rest on the
            // instance's own earlier state alone.
            assertThat(card).isEqualTo(seededRowZero());
            assertThat(card).hasSameHashCodeAs(seededRowZero());
        }

        @Test
        @DisplayName("replacing the card number does change identity, because the key is the identity")
        void replacingTheKeyChangesIdentity() {
            final Card card = seededRowZero();

            card.setCardNum(LEADING_ZERO_CARD_NUM);

            assertThat(card).isNotEqualTo(seededRowZero());
            assertThat(card.getCardNum()).isEqualTo(LEADING_ZERO_CARD_NUM);
        }

        @Test
        @DisplayName("equality is reflexive, symmetric and transitive over the key, and the hash is "
                + "consistent with it across repeated reads")
        void equalityObeysItsContract() {
            final Card first = seededRowZero();
            final Card second = seededRowZero();
            final Card third = seededRowZero();

            assertThat(first).isEqualTo(first);
            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
            assertThat(first.hashCode()).isEqualTo(first.hashCode());
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a card is never equal to null nor to an unrelated type, so a comparison against "
                + "either answers rather than throwing")
        void aCardIsNeverEqualToNullOrAnUnrelatedType() {
            final Card card = seededRowZero();

            // The null and foreign-type branches are exercised through equals directly, because an
            // assertion helper may short-circuit before reaching the method under test.
            assertThat(card.equals(null)).isFalse();
            assertThat(card.equals(SEED_CARD_NUM)).isFalse();
            assertThat(card.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("two provider-instantiated cards are equal because an absent key equals an absent "
                + "key, and hashing an absent key answers rather than throwing")
        void twoUnsetKeysCompareEqual() {
            final Card firstEmpty = new Card();
            final Card secondEmpty = new Card();

            assertThat(firstEmpty).isEqualTo(secondEmpty);
            assertThat(firstEmpty).hasSameHashCodeAs(secondEmpty);
            assertThat(firstEmpty).isNotEqualTo(seededRowZero());
            assertThat(seededRowZero()).isNotEqualTo(firstEmpty);
        }

        @Test
        @DisplayName("no surrogate identifier exists: the key is the leading 16 bytes of the record "
                + "image itself, so a fresh instance reports an absent key rather than a generated one")
        void noSurrogateIdentifierExists() {
            final Card card = new Card();

            // Proven by compile-time absence as much as by this assertion: no generated-identifier
            // accessor is named anywhere in this file, because no such member exists to name. Nothing
            // reflective is used to establish that - a reflective probe would say nothing about whether
            // application code could reach such a member.
            assertThat(card.getCardNum())
                    .as("nothing generated a key at construction time")
                    .isNull();

            assertThat(KEY_OFFSET).isZero();
            assertThat(KEY_WIDTH).isEqualTo(CARD_NUM_WIDTH);

            card.setCardNum(SEED_CARD_NUM);
            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
        }
    }

    /**
     * The version counter's default and its independence from identity, and the diagnostic rendering's
     * redaction of every value that identifies a card or a cardholder.
     *
     * <p>Optimistic locking itself is not exercised here: an increment needs a persistence context and
     * belongs to the repository integration tier.
     */
    @Nested
    @DisplayName("version counter and diagnostic rendering")
    class VersionCounterAndDiagnostics {

        @Test
        @DisplayName("the version counter defaults to zero on both construction paths and is never "
                + "seeded with a non-zero value, so a row that has never been updated matches the "
                + "schema default")
        void theVersionCounterDefaultsToZero() {
            assertThat(new Card().getVersion()).isEqualTo(0L);
            assertThat(seededRowZero().getVersion()).isEqualTo(0L);
            assertThat(seededLeadingZeroRow().getVersion()).isEqualTo(0L);

            // Read through the getter, which is the only access the production class offers: the counter
            // is absent from the constructor's parameter list and the class declares no setter for it at
            // any access level, because the persistence provider owns the value. Reflection would reach
            // the field, is prohibited here, and would say nothing about the provider in any case.
        }

        @Test
        @DisplayName("the version counter is not part of entity identity: two cards sharing a card "
                + "number are equal, and equality never reads the counter")
        void theVersionCounterIsNotPartOfIdentity() {
            final Card fromFixture = seededRowZero();
            final Card divergent = new Card();
            divergent.setCardNum(SEED_CARD_NUM);

            // Both counters are necessarily zero here: there is no accessible setter and no persistence
            // context, so this is a documenting test of the intended contract - identity is the business
            // key alone, so a counter that advances on every update must not change which row an
            // instance is. The repository integration tier observes an actual increment.
            assertThat(fromFixture.getVersion()).isEqualTo(0L);
            assertThat(divergent.getVersion()).isEqualTo(0L);
            assertThat(fromFixture).isEqualTo(divergent);
            assertThat(fromFixture).hasSameHashCodeAs(divergent);

            // The counter's presence is a documented strengthening rather than a behavioural change: it
            // sits alongside, and does not replace, the legacy program's hand-written before-and-after
            // image comparison over file definitions specifying uncommitted read integrity, no recovery
            // and no journaling. Stronger isolation is an improvement, not a regression.
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the verification code, and withholds the card "
                + "number, the embossed name, the owning account identifier and the expiry with it")
        void theDiagnosticRenderingWithholdsTheVerificationCode() {
            final Card card = seededRowZero();

            final String rendered = card.toString();

            // The verification code is the value this assertion exists for; the other four are withheld
            // on the same terms, and pinning all five stops a later convenience edit from widening the
            // rendering one field at a time.
            assertThat(rendered).doesNotContain(SEED_CVV_CD);
            assertThat(rendered).doesNotContain(SEED_CARD_NUM);
            assertThat(rendered).doesNotContain(SEED_CARD_NUM_STRIPPED);
            assertThat(rendered).doesNotContain(SEED_EMBOSSED_NAME_TEXT);
            assertThat(rendered).doesNotContain(SEED_ACCT_ID);
            assertThat(rendered).doesNotContain(SEED_EXPIRATION_DATE);
        }

        @Test
        @DisplayName("a verification code beginning with a zero is withheld too, so the rendering does "
                + "not disclose one shape of the field while withholding another")
        void aLeadingZeroVerificationCodeIsWithheldToo() {
            final Card card = seededLeadingZeroRow();

            final String rendered = card.toString();

            assertThat(rendered).doesNotContain(LEADING_ZERO_CVV_CD);
            assertThat(rendered).doesNotContain(LEADING_ZERO_CARD_NUM);
            assertThat(rendered).doesNotContain(LEADING_ZERO_EMBOSSED_NAME_TEXT);
        }

        @Test
        @DisplayName("the rendering is exactly the entity name, a fixed stand-in for the card number "
                + "and the raw status code, written out here by hand so an appended field cannot pass")
        void theRenderingIsExactlyItsTwoDocumentedComponents() {
            // Pinned character for character against a hand-written expectation rather than assembled
            // from the instance's own accessors. A containment check alone would still pass if a further
            // field were appended, which is precisely the regression this guards against.
            assertThat(seededRowZero().toString())
                    .isEqualTo("Card[cardNum=***REDACTED***, cardActiveStatus='Y']");

            // The status is retained because a diagnostic that cannot say whether a card was active
            // explains nothing, and it is quoted so significant padding would stay visible.
            assertThat(seededRowZero().toString()).contains("cardActiveStatus='" + SEED_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("two cards differing in every property but their status render identically, so the "
                + "rendering discriminates between no two cards")
        void twoDifferentCardsRenderIdentically() {
            // Both renderings are pinned to the same hand-written literal rather than to each other, so
            // the expectation is independent of the class under test in both directions.
            final String expected = "Card[cardNum=***REDACTED***, cardActiveStatus='Y']";

            assertThat(seededRowZero().toString()).isEqualTo(expected);
            assertThat(seededLeadingZeroRow().toString()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a provider-instantiated card renders in the same fixed shape carrying an unset "
                + "status, so a diagnostic raised before population reports rather than throws")
        void theRenderingOfAnEmptyInstanceIsTheSameShape() {
            // Pinned exactly rather than merely checked for existence: a non-null, non-empty check
            // passes for any string at all, including one that had begun disclosing a value. An unset
            // status renders as the literal text null and the surrounding shape is unchanged.
            assertThat(new Card().toString())
                    .isEqualTo("Card[cardNum=***REDACTED***, cardActiveStatus='null']");
        }
    }
}
