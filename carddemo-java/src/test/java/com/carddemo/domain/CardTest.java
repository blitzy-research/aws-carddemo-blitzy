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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link Card}, the Java carrier of the 150-byte legacy card record.
 *
 * <p><strong>Why this file exists.</strong> {@code Card} is the structural twin of {@link Account} -
 * the only other entity in the module carrying an optimistic-locking version counter - and it was the
 * one entity in the domain package with no test of its own. It carries the primary account number and
 * the card verification code, which makes its diagnostic rendering a disclosure surface rather than a
 * convenience, so the absence of a suite was the least acceptable of the coverage gaps.
 *
 * <p><strong>Every expected value here was hand-derived, never computed by the code under test.</strong>
 * Widths, offsets and the total record length come from three mutually independent artifacts of the
 * read-only legacy estate, each read directly:
 *
 * <ul>
 *   <li>the copybook {@code app/cpy/CVACT02Y.cpy}, which declares six named fields followed by a
 *       59-byte trailing filler;</li>
 *   <li>the cluster definition {@code app/jcl/CARDFILE.jcl}, which independently states
 *       {@code KEYS(16 0)} and {@code RECORDSIZE(150 150)} for the base cluster, and
 *       {@code KEYS(11 16)} with {@code NONUNIQUEKEY} and {@code UPGRADE} for the alternate index over
 *       the account identifier;</li>
 *   <li>the sequential reader {@code app/cbl/CBACT02C.cbl}, whose file section splits the same record
 *       into a 16-byte key field {@code FD-CARD-NUM} and a 134-byte remainder
 *       {@code FD-CARD-DATA}.</li>
 * </ul>
 *
 * Because those three descriptions were written independently of one another, cross-checking the width
 * arithmetic against all three is a real test rather than a restatement: the field widths must sum to
 * 150, the key plus the remainder must sum to 150, the key width must equal the first field's width,
 * and the alternate-index geometry must land exactly on the account identifier. A layout error that
 * satisfied one of those relations would violate another.
 *
 * <p><strong>No production mapper or codec is referenced.</strong> Where a seeded record is needed it
 * is sliced by offset in the test itself, through {@link SeededRecordFixture}, which is a test support
 * type rather than production code. Making a production mapper the oracle would leave the mapper and
 * this class free to be wrong together - and no card record mapper ships in this module in any case.
 *
 * <p><strong>The schema is an independent oracle too.</strong> The {@code card} table is owned by the
 * Flyway migration {@code V1__create_schema.sql}, read here through {@link SchemaColumnCatalog}. The
 * copybook widths asserted below are compared against that migration, so a width changed in the
 * migration alone fails here even though the entity and the migration would still agree with each
 * other. Provider-level agreement between the annotations and the migration is asserted separately and
 * exhaustively, for all nine entities at once, by {@code EntityPersistenceMappingTest}.
 *
 * <p><strong>Provenance.</strong> Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec;
 * upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19). Recorded here as a plain
 * identifier string for the traceability matrix. No assertion is made about that stamp: it is not
 * carried uniformly by every legacy member, so testing for it would test the estate rather than this
 * class.
 */
@DisplayName("Card :: the 150-byte legacy card record as a persistent entity")
class CardTest {

    /** Total record length, stated by the copybook header and by {@code RECORDSIZE(150 150)}. */
    private static final int RECORD_LENGTH = 150;

    /** Offset of the card number, and the key offset stated by {@code KEYS(16 0)}. */
    private static final int CARD_NUM_OFFSET = 0;

    /** Width of the card number, {@code PIC X(16)}, and the key width of {@code KEYS(16 0)}. */
    private static final int CARD_NUM_WIDTH = 16;

    /** Offset of the account identifier, and the alternate-index offset of {@code KEYS(11 16)}. */
    private static final int ACCT_ID_OFFSET = 16;

    /** Width of the account identifier, {@code PIC 9(11)}, and the alternate-index key width. */
    private static final int ACCT_ID_WIDTH = 11;

    /** Offset of the card verification code. */
    private static final int CVV_OFFSET = 27;

    /** Width of the card verification code, {@code PIC 9(03)}. */
    private static final int CVV_WIDTH = 3;

    /** Offset of the embossed cardholder name. */
    private static final int EMBOSSED_NAME_OFFSET = 30;

    /** Width of the embossed cardholder name, {@code PIC X(50)}. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** Offset of the expiration date. */
    private static final int EXPIRATION_DATE_OFFSET = 80;

    /** Width of the expiration date, {@code PIC X(10)}. */
    private static final int EXPIRATION_DATE_WIDTH = 10;

    /** Offset of the active status code. */
    private static final int ACTIVE_STATUS_OFFSET = 90;

    /** Width of the active status code, {@code PIC X(01)}. */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /** Offset of the trailing filler, which is deliberately neither an attribute nor a column. */
    private static final int FILLER_OFFSET = 91;

    /** Width of the trailing filler, {@code PIC X(59)}. */
    private static final int FILLER_WIDTH = 59;

    /** Width of {@code FD-CARD-DATA}, the remainder after the key in {@code CBACT02C}. */
    private static final int REMAINDER_WIDTH = 134;

    /** The mapped table name. */
    private static final String TABLE = "card";

    /** Records carried by the named fixture, counted directly. */
    private static final int SEEDED_RECORDS = 50;

    /** Sample records whose card number carries a leading zero, counted directly. */
    private static final int SEEDED_CARD_NUMBERS_WITH_LEADING_ZERO = 5;

    /** Sample records whose verification code carries a leading zero, counted directly. */
    private static final int SEEDED_CVVS_WITH_LEADING_ZERO = 8;

    /** Card number of the fixture's first record, hand-decoded at offset 0 for 16 bytes. */
    private static final String SEED_CARD_NUM = "0500024453765740";

    /** Account identifier of the first record, hand-decoded at offset 16 for 11 bytes. */
    private static final String SEED_ACCT_ID = "00000000050";

    /** Verification code of the first record, hand-decoded at offset 27 for 3 bytes. */
    private static final String SEED_CVV_CD = "747";

    /** Unpadded embossed name of the first record, hand-decoded at offset 30. */
    private static final String SEED_EMBOSSED_NAME_TEXT = "Aniya Von";

    /** Expiration date of the first record, hand-decoded at offset 80 for 10 bytes. */
    private static final String SEED_EXPIRATION_DATE = "2023-03-09";

    /** Active status of the first record, hand-decoded at offset 90; all fifty records carry it. */
    private static final String SEED_ACTIVE_STATUS = "Y";

    /** Card number of the fixture's third record, the first whose verification code leads with zero. */
    private static final String LEADING_ZERO_CVV_CARD_NUM = "0923877193247330";

    /** Verification code of that third record: three digits, the first of them zero. */
    private static final String LEADING_ZERO_CVV = "028";

    /** The migration that owns the {@code card} table, read as an independent oracle. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The named fixture, whose loader independently rejects any record not measuring 150 bytes. */
    private static final SeededRecordFixture SEED =
            SeededRecordFixture.load("carddata.txt", RECORD_LENGTH);

    /**
     * Returns the embossed name of the fixture's first record at its full declared width.
     *
     * <p>Built by padding rather than by transcribing forty-one literal spaces, because a transcribed
     * run of spaces is unreadable and impossible to review for an off-by-one.
     *
     * @return the 50-character space-filled embossed name
     */
    private static String seedEmbossedName() {
        return SEED_EMBOSSED_NAME_TEXT + " ".repeat(EMBOSSED_NAME_WIDTH - SEED_EMBOSSED_NAME_TEXT.length());
    }

    /**
     * Builds the entity for the fixture's first record from hand-written literals.
     *
     * @return a card carrying the first seeded record's six business values
     */
    private static Card seededRowOne() {
        return new Card(SEED_CARD_NUM,
                SEED_ACCT_ID,
                SEED_CVV_CD,
                seedEmbossedName(),
                SEED_EXPIRATION_DATE,
                SEED_ACTIVE_STATUS);
    }

    /**
     * Builds the entity for a seeded record by slicing the fixture at the copybook offsets.
     *
     * <p>The slicing happens here, in the test, rather than in any production mapper, so the fixture
     * and the entity remain independent of one another.
     *
     * @param ordinal the one-based record ordinal within the fixture
     * @return a card carrying that record's six business values
     */
    private static Card slicedFromFixture(final int ordinal) {
        return new Card(SEED.field(ordinal, CARD_NUM_OFFSET, CARD_NUM_WIDTH),
                SEED.field(ordinal, ACCT_ID_OFFSET, ACCT_ID_WIDTH),
                SEED.field(ordinal, CVV_OFFSET, CVV_WIDTH),
                SEED.field(ordinal, EMBOSSED_NAME_OFFSET, EMBOSSED_NAME_WIDTH),
                SEED.field(ordinal, EXPIRATION_DATE_OFFSET, EXPIRATION_DATE_WIDTH),
                SEED.field(ordinal, ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_WIDTH));
    }

    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the six declared field widths and the trailing filler sum to the 150 bytes the "
                + "copybook header and the cluster definition both state")
        void theDeclaredWidthsSumToTheRecordLength() {
            final int declared = CARD_NUM_WIDTH
                    + ACCT_ID_WIDTH
                    + CVV_WIDTH
                    + EMBOSSED_NAME_WIDTH
                    + EXPIRATION_DATE_WIDTH
                    + ACTIVE_STATUS_WIDTH
                    + FILLER_WIDTH;

            assertThat(declared).isEqualTo(RECORD_LENGTH);

            // 16 + 11 + 3 + 50 + 10 + 1 = 91 named bytes, leaving exactly the 59-byte filler.
            assertThat(declared - FILLER_WIDTH).isEqualTo(FILLER_OFFSET);
        }

        @Test
        @DisplayName("the fields are contiguous, each beginning exactly where the previous one ends, "
                + "so no gap or overlap can hide in the layout")
        void theFieldsAreContiguous() {
            assertThat(CARD_NUM_OFFSET).isZero();
            assertThat(CARD_NUM_OFFSET + CARD_NUM_WIDTH).isEqualTo(ACCT_ID_OFFSET);
            assertThat(ACCT_ID_OFFSET + ACCT_ID_WIDTH).isEqualTo(CVV_OFFSET);
            assertThat(CVV_OFFSET + CVV_WIDTH).isEqualTo(EMBOSSED_NAME_OFFSET);
            assertThat(EMBOSSED_NAME_OFFSET + EMBOSSED_NAME_WIDTH).isEqualTo(EXPIRATION_DATE_OFFSET);
            assertThat(EXPIRATION_DATE_OFFSET + EXPIRATION_DATE_WIDTH).isEqualTo(ACTIVE_STATUS_OFFSET);
            assertThat(ACTIVE_STATUS_OFFSET + ACTIVE_STATUS_WIDTH).isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_WIDTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key that the cluster definition declares is exactly the leading card-number "
                + "field, and the reader's remainder accounts for every remaining byte")
        void theKeyIsTheLeadingFieldAndTheRemainderAccountsForTheRest() {
            // KEYS(16 0): width 16 at offset 0. That is the card-number field, byte for byte.
            assertThat(CARD_NUM_OFFSET).isZero();
            assertThat(CARD_NUM_WIDTH).isEqualTo(16);

            // CBACT02C splits the same record as FD-CARD-NUM X(16) + FD-CARD-DATA X(134).
            assertThat(CARD_NUM_WIDTH + REMAINDER_WIDTH).isEqualTo(RECORD_LENGTH);
            assertThat(REMAINDER_WIDTH).isEqualTo(RECORD_LENGTH - CARD_NUM_WIDTH);

            // The remainder therefore begins where the key ends, which is where the account
            // identifier begins. Three independent artifacts agree on that boundary.
            assertThat(CARD_NUM_WIDTH).isEqualTo(ACCT_ID_OFFSET);
        }

        @Test
        @DisplayName("the alternate index over the account identifier lands exactly on that field, so "
                + "a card-by-account finder reproduces the legacy access path rather than approximating it")
        void theAlternateIndexLandsOnTheAccountIdentifier() {
            // KEYS(11 16): width 11 at offset 16, declared NONUNIQUEKEY with UPGRADE.
            assertThat(ACCT_ID_OFFSET).isEqualTo(16);
            assertThat(ACCT_ID_WIDTH).isEqualTo(11);

            // Nonunique is the right declaration because many cards may be issued against one
            // account, which is why the derived finder returns a page rather than a single row.
            assertThat(ACCT_ID_OFFSET + ACCT_ID_WIDTH).isLessThan(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the trailing filler is mapped by neither the entity nor the schema, so its 59 "
                + "bytes are reserved rather than silently carried")
        void theTrailingFillerIsUnmapped() {
            // Seven columns exist and none of them corresponds to the filler: six business fields
            // plus the version counter, which has no record position at all.
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(7);
            assertThat(SCHEMA.columnNames(TABLE))
                    .noneMatch(column -> column.contains("filler"));

            // The filler occupies the tail of the image, from offset 91 to the end.
            assertThat(FILLER_OFFSET + FILLER_WIDTH).isEqualTo(RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("schema agreement")
    class SchemaAgreement {

        @Test
        @DisplayName("the migration defines exactly the seven columns this entity maps, named as the "
                + "copybook fields are named")
        void theMigrationDefinesTheSevenMappedColumns() {
            assertThat(SCHEMA.columnNames(TABLE))
                    .containsExactlyInAnyOrder("card_num",
                            "card_acct_id",
                            "card_cvv_cd",
                            "card_embossed_name",
                            "card_expiration_date",
                            "card_active_status",
                            "version");
        }

        @Test
        @DisplayName("every character column is declared at exactly its copybook field width, so a "
                + "width changed in the migration alone fails here")
        void everyCharacterColumnMatchesItsCopybookWidth() {
            assertThat(SCHEMA.declaredWidth(TABLE, "card_num")).isEqualTo(CARD_NUM_WIDTH);
            assertThat(SCHEMA.declaredWidth(TABLE, "card_acct_id")).isEqualTo(ACCT_ID_WIDTH);
            assertThat(SCHEMA.declaredWidth(TABLE, "card_cvv_cd")).isEqualTo(CVV_WIDTH);
            assertThat(SCHEMA.declaredWidth(TABLE, "card_embossed_name"))
                    .isEqualTo(EMBOSSED_NAME_WIDTH);
            assertThat(SCHEMA.declaredWidth(TABLE, "card_expiration_date"))
                    .isEqualTo(EXPIRATION_DATE_WIDTH);
            assertThat(SCHEMA.declaredWidth(TABLE, "card_active_status"))
                    .isEqualTo(ACTIVE_STATUS_WIDTH);
        }

        @Test
        @DisplayName("the two digit-only legacy fields are character columns rather than numeric ones, "
                + "because their leading zeros are contractual")
        void theDigitOnlyFieldsAreCharacterColumns() {
            // CARD-ACCT-ID is PIC 9(11) and CARD-CVV-CD is PIC 9(03) - numeric picture clauses - yet
            // both must survive a round trip with leading zeros intact, so both are character
            // columns. This is the decisive reason the entity types them as text.
            assertThat(SCHEMA.declaredType(TABLE, "card_acct_id")).startsWith("VARCHAR");
            assertThat(SCHEMA.declaredType(TABLE, "card_cvv_cd")).startsWith("VARCHAR");
            assertThat(SCHEMA.declaredType(TABLE, "card_num")).startsWith("VARCHAR");
        }

        @Test
        @DisplayName("the version counter is an integral column and carries no width, distinguishing "
                + "it from every field that has a record position")
        void theVersionCounterIsIntegral() {
            assertThat(SCHEMA.declaredType(TABLE, "version")).isEqualTo("BIGINT");
        }

        @Test
        @DisplayName("no column of this table is nullable, matching six fixed-width fields that are "
                + "always present plus a provider-managed counter")
        void noColumnIsNullable() {
            for (final String column : SCHEMA.columnNames(TABLE)) {
                assertThat(SCHEMA.isNullable(TABLE, column))
                        .as("nullability of card.%s", column)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the primary key is the card number alone, never a surrogate, so the row and the "
                + "record image keep their correspondence")
        void thePrimaryKeyIsTheBusinessKeyAlone() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly("card_num");
        }
    }

    @Nested
    @DisplayName("construction and accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the six-argument constructor assigns the business fields in record order, so an "
                + "argument transposition is visible rather than silent")
        void theConstructorAssignsInRecordOrder() {
            final Card card = seededRowOne();

            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(card.getCardAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(card.getCardCvvCd()).isEqualTo(SEED_CVV_CD);
            assertThat(card.getCardEmbossedName()).isEqualTo(seedEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(card.getCardActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("each argument reaches its own attribute, proven by six mutually distinct values "
                + "that could not be confused with one another")
        void eachArgumentReachesItsOwnAttribute() {
            // Deliberately distinctive values: no one of them is a substring of another, so a
            // transposed pair cannot accidentally satisfy this test.
            final Card card = new Card("NUMBER0000000001",
                    "ACCTID00001",
                    "QVZ",
                    "EMBOSSEDNAME",
                    "EXPIRYTOKN",
                    "S");

            assertThat(card.getCardNum()).isEqualTo("NUMBER0000000001");
            assertThat(card.getCardAcctId()).isEqualTo("ACCTID00001");
            assertThat(card.getCardCvvCd()).isEqualTo("QVZ");
            assertThat(card.getCardEmbossedName()).isEqualTo("EMBOSSEDNAME");
            assertThat(card.getCardExpirationDate()).isEqualTo("EXPIRYTOKN");
            assertThat(card.getCardActiveStatus()).isEqualTo("S");
        }

        @Test
        @DisplayName("the provider's no-argument constructor yields an entity with every attribute "
                + "unset and the counter at zero")
        void theProviderConstructorYieldsAnUnsetEntity() {
            final Card card = new Card();

            assertThat(card.getCardNum()).isNull();
            assertThat(card.getCardAcctId()).isNull();
            assertThat(card.getCardCvvCd()).isNull();
            assertThat(card.getCardEmbossedName()).isNull();
            assertThat(card.getCardExpirationDate()).isNull();
            assertThat(card.getCardActiveStatus()).isNull();
            assertThat(card.getVersion()).isZero();
        }

        @Test
        @DisplayName("every mutator replaces exactly its own attribute and leaves the other five "
                + "untouched")
        void everyMutatorReplacesOnlyItsOwnAttribute() {
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

            // Replacing one attribute disturbs nothing else.
            card.setCardCvvCd("000");
            assertThat(card.getCardCvvCd()).isEqualTo("000");
            assertThat(card.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(card.getCardEmbossedName()).isEqualTo(seedEmbossedName());
        }

        @Test
        @DisplayName("the entity neither validates nor rejects a null, because field editing belongs "
                + "to the request path and not to the carrier")
        void theEntityToleratesNulls() {
            final Card card = new Card(null, null, null, null, null, null);

            assertThat(card.getCardNum()).isNull();
            assertThat(card.getCardCvvCd()).isNull();

            // A mutator accepts null for the same reason.
            final Card populated = seededRowOne();
            populated.setCardEmbossedName(null);
            assertThat(populated.getCardEmbossedName()).isNull();
            assertThat(populated.getCardNum()).isEqualTo(SEED_CARD_NUM);
        }

        @Test
        @DisplayName("the entity computes nothing: an over-long value is stored exactly as supplied, "
                + "because width enforcement belongs to the column and the mapper")
        void theEntityComputesNothing() {
            final String overLong = "X".repeat(RECORD_LENGTH);
            final Card card = new Card();

            card.setCardNum(overLong);

            // No truncation, no padding, no validation. The column rejects an over-long value at
            // write time; the carrier does not pretend to.
            assertThat(card.getCardNum()).isEqualTo(overLong).hasSize(RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("raw character fidelity")
    class RawCharacterFidelity {

        @Test
        @DisplayName("a leading zero on the card number survives, which a numeric type would have "
                + "destroyed")
        void aLeadingZeroOnTheCardNumberSurvives() {
            final Card card = seededRowOne();

            assertThat(card.getCardNum())
                    .isEqualTo(SEED_CARD_NUM)
                    .startsWith("0")
                    .hasSize(CARD_NUM_WIDTH);

            // Read as a number this value would be 500024453765740 - fifteen digits - and the
            // sixteen-character external form would be unrecoverable.
            assertThat(Long.toString(Long.parseLong(SEED_CARD_NUM)))
                    .hasSize(CARD_NUM_WIDTH - 1)
                    .isNotEqualTo(SEED_CARD_NUM);
        }

        @Test
        @DisplayName("a leading zero on the verification code survives, so the code 028 never becomes "
                + "the number 28")
        void aLeadingZeroOnTheVerificationCodeSurvives() {
            final Card card = new Card(LEADING_ZERO_CVV_CARD_NUM,
                    SEED_ACCT_ID,
                    LEADING_ZERO_CVV,
                    seedEmbossedName(),
                    SEED_EXPIRATION_DATE,
                    SEED_ACTIVE_STATUS);

            assertThat(card.getCardCvvCd())
                    .isEqualTo(LEADING_ZERO_CVV)
                    .startsWith("0")
                    .hasSize(CVV_WIDTH);

            assertThat(Integer.toString(Integer.parseInt(LEADING_ZERO_CVV)))
                    .isEqualTo("28")
                    .isNotEqualTo(LEADING_ZERO_CVV);
        }

        @Test
        @DisplayName("the zero-filled account identifier keeps all eleven characters, so the derived "
                + "finder matches the stored form exactly")
        void theZeroFilledAccountIdentifierKeepsItsWidth() {
            final Card card = seededRowOne();

            assertThat(card.getCardAcctId())
                    .isEqualTo(SEED_ACCT_ID)
                    .startsWith("0")
                    .hasSize(ACCT_ID_WIDTH);

            // The significant digits are the last two; the nine leading zeros are padding that is
            // part of the value rather than incidental.
            assertThat(SEED_ACCT_ID).endsWith("50");
        }

        @Test
        @DisplayName("trailing padding on the embossed name is preserved, so a padded name never "
                + "compares equal to its trimmed form")
        void trailingPaddingOnTheEmbossedNameIsPreserved() {
            final Card card = seededRowOne();
            final String stored = card.getCardEmbossedName();

            assertThat(stored)
                    .hasSize(EMBOSSED_NAME_WIDTH)
                    .startsWith(SEED_EMBOSSED_NAME_TEXT)
                    .endsWith(" ")
                    .isNotEqualTo(SEED_EMBOSSED_NAME_TEXT);

            assertThat(stored.strip()).isEqualTo(SEED_EMBOSSED_NAME_TEXT);
            assertThat(stored.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EMBOSSED_NAME_WIDTH);
        }

        @Test
        @DisplayName("the embossed name is not case folded by the entity, because the legacy fold is a "
                + "character-table substitution performed on the update path")
        void theEmbossedNameIsNotCaseFolded() {
            final Card card = seededRowOne();

            // Every one of the fifty sample names carries lower-case characters, so a fold applied
            // here would alter stored data for all of them.
            assertThat(card.getCardEmbossedName()).isNotEqualTo(seedEmbossedName().toUpperCase(Locale.ROOT));
            assertThat(card.getCardEmbossedName()).containsPattern("[a-z]");

            // A mixed-case value passes through untouched, in either direction.
            final Card mutated = new Card();
            mutated.setCardEmbossedName("mCdonald o'Brien");
            assertThat(mutated.getCardEmbossedName()).isEqualTo("mCdonald o'Brien");
        }

        @Test
        @DisplayName("the expiration date is kept as its ten-character external form and never parsed, "
                + "so a value a strict parser would reject still round trips")
        void theExpirationDateIsKeptAsText() {
            final Card card = seededRowOne();

            assertThat(card.getCardExpirationDate())
                    .isEqualTo(SEED_EXPIRATION_DATE)
                    .hasSize(EXPIRATION_DATE_WIDTH);

            // A calendar-invalid value is stored unchanged: strict parsing lives in the date
            // validation service, and rejecting it here would be new behavior.
            final Card odd = new Card();
            odd.setCardExpirationDate("2023-02-30");
            assertThat(odd.getCardExpirationDate()).isEqualTo("2023-02-30");
        }

        @Test
        @DisplayName("the active status is held as the raw byte and never translated, so a code outside "
                + "the documented vocabulary flows through exactly as the legacy file carried it")
        void theActiveStatusIsHeldAsTheRawByte() {
            assertThat(seededRowOne().getCardActiveStatus())
                    .isEqualTo(SEED_ACTIVE_STATUS)
                    .hasSize(ACTIVE_STATUS_WIDTH);

            // An unmapped code is accepted. An enum-mapped attribute could not carry it, which is
            // the reason this attribute is a raw one-character string.
            final Card unmapped = new Card();
            unmapped.setCardActiveStatus("Q");
            assertThat(unmapped.getCardActiveStatus()).isEqualTo("Q");

            // Case is not folded either: the legacy comparison tested the byte as supplied, so a
            // lower-case form is a different code rather than the same one.
            unmapped.setCardActiveStatus("y");
            assertThat(unmapped.getCardActiveStatus()).isEqualTo("y").isNotEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("seeded composition")
    class SeededComposition {

        @Test
        @DisplayName("the named fixture holds fifty records of exactly 150 bytes, accounting for its "
                + "full 7,550-byte size")
        void theFixtureGeometryMatchesTheLayout() {
            assertThat(SEED.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(SEED.recordWidth()).isEqualTo(RECORD_LENGTH);

            // Fifty records of 150 bytes plus one line terminator each.
            assertThat(SEED.impliedByteCount()).isEqualTo(SEEDED_RECORDS * (RECORD_LENGTH + 1));
            assertThat(SEED.impliedByteCount()).isEqualTo(7550);
        }

        @Test
        @DisplayName("the first record decodes at the copybook offsets to exactly the hand-written "
                + "literals used throughout this file")
        void theFirstRecordDecodesToTheExpectedLiterals() {
            assertThat(SEED.field(1, CARD_NUM_OFFSET, CARD_NUM_WIDTH)).isEqualTo(SEED_CARD_NUM);
            assertThat(SEED.field(1, ACCT_ID_OFFSET, ACCT_ID_WIDTH)).isEqualTo(SEED_ACCT_ID);
            assertThat(SEED.field(1, CVV_OFFSET, CVV_WIDTH)).isEqualTo(SEED_CVV_CD);
            assertThat(SEED.field(1, EMBOSSED_NAME_OFFSET, EMBOSSED_NAME_WIDTH))
                    .isEqualTo(seedEmbossedName());
            assertThat(SEED.field(1, EXPIRATION_DATE_OFFSET, EXPIRATION_DATE_WIDTH))
                    .isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(SEED.field(1, ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_WIDTH))
                    .isEqualTo(SEED_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("the trailing filler is blank in every seeded record, confirming the 59 reserved "
                + "bytes carry no data that the mapping drops")
        void theTrailingFillerIsBlankInEverySeededRecord() {
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                assertThat(SEED.field(ordinal, FILLER_OFFSET, FILLER_WIDTH))
                        .as("filler of record %d", ordinal)
                        .isEqualTo(" ".repeat(FILLER_WIDTH));
            }
        }

        @Test
        @DisplayName("every seeded card number is sixteen digits and five of them lead with a zero, so "
                + "the numeric-typing hazard is demonstrated rather than hypothetical")
        void everySeededCardNumberIsSixteenDigitsAndSomeLeadWithZero() {
            int leadingZeros = 0;
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String cardNum = SEED.field(ordinal, CARD_NUM_OFFSET, CARD_NUM_WIDTH);

                assertThat(cardNum)
                        .as("card number of record %d", ordinal)
                        .hasSize(CARD_NUM_WIDTH)
                        .containsOnlyDigits();

                if (cardNum.startsWith("0")) {
                    leadingZeros++;
                }
            }

            assertThat(leadingZeros).isEqualTo(SEEDED_CARD_NUMBERS_WITH_LEADING_ZERO);
        }

        @Test
        @DisplayName("all fifty account identifiers are zero-filled to eleven digits, so the stored "
                + "width is uniform across the whole fixture")
        void everySeededAccountIdentifierIsZeroFilled() {
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                assertThat(SEED.field(ordinal, ACCT_ID_OFFSET, ACCT_ID_WIDTH))
                        .as("account identifier of record %d", ordinal)
                        .hasSize(ACCT_ID_WIDTH)
                        .containsOnlyDigits()
                        .startsWith("0");
            }
        }

        @Test
        @DisplayName("exactly eight seeded verification codes carry a leading zero, the count the "
                + "entity's own reasoning cites")
        void exactlyEightSeededVerificationCodesLeadWithZero() {
            int leadingZeros = 0;
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String cvv = SEED.field(ordinal, CVV_OFFSET, CVV_WIDTH);

                assertThat(cvv)
                        .as("verification code of record %d", ordinal)
                        .hasSize(CVV_WIDTH)
                        .containsOnlyDigits();

                if (cvv.startsWith("0")) {
                    leadingZeros++;
                }
            }

            assertThat(leadingZeros).isEqualTo(SEEDED_CVVS_WITH_LEADING_ZERO);
        }

        @Test
        @DisplayName("every seeded record carries the single active status code Y, so the vocabulary "
                + "the fixture exercises is recorded rather than assumed broader")
        void everySeededRecordCarriesTheSameActiveStatus() {
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                assertThat(SEED.field(ordinal, ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_WIDTH))
                        .as("active status of record %d", ordinal)
                        .isEqualTo(SEED_ACTIVE_STATUS);
            }
        }

        @Test
        @DisplayName("every seeded embossed name is space-filled to fifty characters and carries "
                + "lower-case text, so no fold may be applied at rest")
        void everySeededEmbossedNameIsPaddedAndMixedCase() {
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String name = SEED.field(ordinal, EMBOSSED_NAME_OFFSET, EMBOSSED_NAME_WIDTH);

                assertThat(name)
                        .as("embossed name of record %d", ordinal)
                        .hasSize(EMBOSSED_NAME_WIDTH)
                        .endsWith(" ")
                        .containsPattern("[a-z]");
            }
        }

        @Test
        @DisplayName("an entity sliced from a seeded record carries every byte of that record's six "
                + "fields, so the layout and the carrier agree on real data")
        void anEntitySlicedFromASeededRecordCarriesEveryField() {
            final Card first = slicedFromFixture(1);

            assertThat(first.getCardNum()).isEqualTo(SEED_CARD_NUM);
            assertThat(first.getCardAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(first.getCardCvvCd()).isEqualTo(SEED_CVV_CD);
            assertThat(first.getCardEmbossedName()).isEqualTo(seedEmbossedName());
            assertThat(first.getCardExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(first.getCardActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);

            // The reassembled six fields plus the blank filler restore the whole record image, so
            // nothing in the record is unaccounted for by the mapping.
            final String reassembled = first.getCardNum()
                    + first.getCardAcctId()
                    + first.getCardCvvCd()
                    + first.getCardEmbossedName()
                    + first.getCardExpirationDate()
                    + first.getCardActiveStatus()
                    + " ".repeat(FILLER_WIDTH);

            assertThat(reassembled).isEqualTo(SEED.record(1)).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("all fifty seeded records reassemble byte for byte from their sliced entities, so "
                + "no record in the fixture escapes the layout")
        void allSeededRecordsReassembleByteForByte() {
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final Card card = slicedFromFixture(ordinal);
                final String reassembled = card.getCardNum()
                        + card.getCardAcctId()
                        + card.getCardCvvCd()
                        + card.getCardEmbossedName()
                        + card.getCardExpirationDate()
                        + card.getCardActiveStatus()
                        + SEED.field(ordinal, FILLER_OFFSET, FILLER_WIDTH);

                assertThat(reassembled)
                        .as("reassembled image of record %d", ordinal)
                        .isEqualTo(SEED.record(ordinal));
            }
        }

        @Test
        @DisplayName("the fifty seeded card numbers are distinct, so each is a usable primary key and "
                + "the fixture cannot mask a key collision")
        void theSeededCardNumbersAreDistinct() {
            final Set<String> keys = new LinkedHashSet<>();
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                keys.add(SEED.field(ordinal, CARD_NUM_OFFSET, CARD_NUM_WIDTH));
            }

            assertThat(keys).hasSize(SEEDED_RECORDS);
        }
    }

    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("two cards sharing a card number are equal and hash alike, however far their other "
                + "five fields diverge")
        void identityRestsOnTheCardNumberAlone() {
            final Card fromFixture = seededRowOne();
            final Card divergent = new Card(SEED_CARD_NUM,
                    "99999999999",
                    "999",
                    "SOMEONE ELSE ENTIRELY",
                    "1999-12-31",
                    "N");

            assertThat(fromFixture).isEqualTo(divergent);
            assertThat(fromFixture).hasSameHashCodeAs(divergent);
        }

        @Test
        @DisplayName("two cards with different card numbers are unequal even when every other field "
                + "matches exactly")
        void differentKeysAreUnequal() {
            final Card first = seededRowOne();
            final Card second = new Card(LEADING_ZERO_CVV_CARD_NUM,
                    SEED_ACCT_ID,
                    SEED_CVV_CD,
                    seedEmbossedName(),
                    SEED_EXPIRATION_DATE,
                    SEED_ACTIVE_STATUS);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("mutating a non-key field changes neither equality nor hash, so an instance's "
                + "membership of a hash-based collection survives an update")
        void mutatingANonKeyFieldPreservesIdentity() {
            final Card card = seededRowOne();
            final Card reference = seededRowOne();
            final int hashBefore = card.hashCode();

            card.setCardAcctId("00000000099");
            card.setCardCvvCd("000");
            card.setCardEmbossedName("A DIFFERENT NAME");
            card.setCardExpirationDate("2030-01-01");
            card.setCardActiveStatus("N");

            assertThat(card.hashCode()).isEqualTo(hashBefore);
            assertThat(card).isEqualTo(reference);

            final Set<Card> set = new HashSet<>();
            set.add(reference);
            assertThat(set).contains(card);
        }

        @Test
        @DisplayName("replacing the card number does change identity, because the key is the identity")
        void replacingTheKeyChangesIdentity() {
            final Card card = seededRowOne();
            final Card reference = seededRowOne();

            card.setCardNum(LEADING_ZERO_CVV_CARD_NUM);

            assertThat(card).isNotEqualTo(reference);
            assertThat(card.hashCode()).isNotEqualTo(reference.hashCode());
        }

        @Test
        @DisplayName("the key is compared byte for byte, so neither padding nor case is normalised "
                + "away and Java equality agrees with the database's notion of the same row")
        void theKeyIsComparedByteForByte() {
            final Card exact = new Card(SEED_CARD_NUM, null, null, null, null, null);
            final Card padded = new Card(SEED_CARD_NUM + " ", null, null, null, null, null);
            final Card trimmed = new Card(SEED_CARD_NUM.substring(1), null, null, null, null, null);

            assertThat(exact).isNotEqualTo(padded);
            assertThat(exact).isNotEqualTo(trimmed);

            // Card numbers are digit-only in the fixture, so a case comparison needs a letter-bearing
            // key; the legacy field is alphanumeric, so this is a legal value.
            final Card upper = new Card("ABCDEF0000000001", null, null, null, null, null);
            final Card lower = new Card("abcdef0000000001", null, null, null, null, null);
            assertThat(upper).isNotEqualTo(lower);
        }

        @Test
        @DisplayName("equality is reflexive, symmetric and transitive over the key, and consistent "
                + "across repeated evaluation")
        void equalityObeysItsContract() {
            final Card first = seededRowOne();
            final Card second = seededRowOne();
            final Card third = seededRowOne();

            assertThat(first).isEqualTo(first);
            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);

            assertThat(first.equals(second)).isTrue();
            assertThat(first.equals(second)).isTrue();
            assertThat(first.hashCode()).isEqualTo(first.hashCode());
        }

        @Test
        @DisplayName("a card is never equal to null nor to an unrelated type, and the comparison "
                + "reports rather than throws")
        void aCardIsNeverEqualToNullOrAnUnrelatedType() {
            final Card card = seededRowOne();

            assertThat(card.equals(null)).isFalse();
            assertThat(card.equals(SEED_CARD_NUM)).isFalse();
            assertThat(card).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two cards with an unset key are equal to each other, which is the documented "
                + "consequence of comparing the key with null-tolerant equality")
        void twoUnsetKeysCompareEqual() {
            final Card first = new Card();
            final Card second = new Card();

            // Recorded rather than asserted as desirable: an entity is only ever keyless before the
            // mapper assigns the key, and two such instances are indistinguishable by definition.
            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);

            // A keyless instance is not equal to a keyed one, in either direction.
            final Card keyed = seededRowOne();
            assertThat(first).isNotEqualTo(keyed);
            assertThat(keyed).isNotEqualTo(first);
        }

        @Test
        @DisplayName("equality is symmetric with a subclass instance sharing the key, because the "
                + "comparison tests the type by pattern rather than by exact class")
        void equalityIsSymmetricWithASubclass() {
            final Card base = new Card(SEED_CARD_NUM, null, null, null, null, null);
            final Card subclassed = new ProxyLikeCard(SEED_CARD_NUM);

            // A getClass() comparison would make this asymmetric, which matters because the
            // persistence provider may hand back a proxy rather than the exact class.
            assertThat(base).isEqualTo(subclassed);
            assertThat(subclassed).isEqualTo(base);
            assertThat(base).hasSameHashCodeAs(subclassed);
        }
    }

    @Nested
    @DisplayName("version counter and diagnostic rendering")
    class VersionCounterAndDiagnostics {

        @Test
        @DisplayName("the version counter defaults to zero on both construction paths, so a row that "
                + "has never been updated matches the schema default")
        void theVersionCounterDefaultsToZero() {
            assertThat(new Card().getVersion()).isZero();
            assertThat(seededRowOne().getVersion()).isZero();

            // The counter is absent from the constructor's parameter list and has no mutator: the
            // persistence provider owns it. That absence is a compile-time property enforced by
            // javac - a call to a setter here would not compile - so it is stated rather than
            // asserted. A reflective write would prove nothing about the provider's behaviour.
        }

        @Test
        @DisplayName("the version counter is not part of entity identity, so a counter that advances "
                + "on every update never changes which row an instance is")
        void theVersionCounterIsNotPartOfIdentity() {
            final Card fromFixture = seededRowOne();
            final Card keyOnly = new Card();
            keyOnly.setCardNum(SEED_CARD_NUM);

            assertThat(fromFixture.getVersion()).isZero();
            assertThat(keyOnly.getVersion()).isZero();
            assertThat(fromFixture).isEqualTo(keyOnly);
            assertThat(fromFixture).hasSameHashCodeAs(keyOnly);

            // Both counters are necessarily zero here, because no mutator exists and no persistence
            // context is in scope. The counter's presence is a documented strengthening rather than a
            // behavioural change: it replaces the hand-written before-and-after image comparison the
            // legacy card-update program performed, against file definitions that specified
            // uncommitted read integrity, no recovery and no journaling.
        }

        /**
         * The rendering names the key and withholds its value, keeping only the raw status.
         *
         * <p>An earlier form of this method printed the card number in full, on the reasoning that it
         * is the row's identity and a diagnostic without it identifies nothing. That reasoning does not
         * survive contact with how a rendering actually escapes: an entity reaches a failed assertion
         * message, a provider diagnostic, an interpolated exception message or a structured log event
         * without its author choosing to disclose anything, so the only reliable place to withhold a
         * primary account number is the rendering itself. A leading or trailing fragment was rejected as
         * a compromise, because a fragment of a card number is still card data and a rendered length
         * still discriminates between candidate values.</p>
         *
         * <p>The status code is retained. It is the one mapped value that neither identifies a
         * cardholder nor keys a record, and a diagnostic that cannot say whether a card was active
         * explains nothing. It stays quoted and untrimmed so significant padding is visible.</p>
         *
         * <p>This changes no stored, mapped or transmitted value; {@link Card#getCardNum()} still
         * returns the untouched key, asserted below. The schema still applies no field-level protection
         * to the card number, because the legacy design applies none and inventing one would be feature
         * expansion - that residual gap remains recorded as unclosed in {@code docs/decision-log.md}.
         * What changes is only that an unintended rendering can no longer be the thing that widens
         * it.</p>
         */
        @Test
        @DisplayName("the diagnostic rendering names the card number, withholds its value and keeps "
                + "the raw status, in a fixed shape asserted character for character")
        void theDiagnosticRenderingIsExactlyTheKeyAndStatus() {
            // The expected string is written out here in full rather than assembled from the
            // instance's own accessors, so this asserts the intended rendering rather than whatever
            // the method happens to produce.
            assertThat(seededRowOne().toString())
                    .isEqualTo("Card[cardNum=***REDACTED***, cardActiveStatus='Y']");
            assertThat(seededRowOne().getCardNum())
                    .as("withholding is confined to the rendering: the accessor is untouched, and "
                            + "code that genuinely needs the key calls it")
                    .isEqualTo(SEED_CARD_NUM);
        }

        @Test
        @DisplayName("the verification code is absent from the diagnostic rendering, and so is every "
                + "other withheld field, proven with values that could not appear by coincidence")
        void theVerificationCodeAndEveryOtherFieldAreWithheld() {
            // Deliberately distinctive tokens: none is a substring of another, and none can occur in
            // the expected rendering by accident, so an accidental disclosure cannot pass unnoticed.
            final Card hostile = new Card("CARDNUMTOKEN0001",
                    "ACCTIDTOKEN",
                    "QVZ",
                    "EMBOSSEDNAMETOKEN" + " ".repeat(33),
                    "EXPIRYTOKN",
                    "S");

            final String rendered = hostile.toString();

            assertThat(rendered).isEqualTo("Card[cardNum=***REDACTED***, cardActiveStatus='S']");

            // Five of the six business values must never reach a diagnostic line, and all five are
            // asserted in one breath so that a future widening of this rendering fails here. The card
            // number is included: it is named in the rendering but its value is not written.
            assertThat(rendered).doesNotContain("CARDNUMTOKEN0001");
            assertThat(rendered).doesNotContain("QVZ");
            assertThat(rendered).doesNotContain("ACCTIDTOKEN");
            assertThat(rendered).doesNotContain("EMBOSSEDNAMETOKEN");
            assertThat(rendered).doesNotContain("EXPIRYTOKN");

            // Only two of the six business values are named at all, and only one of those two carries
            // its value.
            assertThat(rendered).contains("cardNum", "cardActiveStatus");
            assertThat(rendered).doesNotContain("cardCvvCd", "cardEmbossedName",
                    "cardExpirationDate", "cardAcctId");

            // Every withheld value is still returned by its accessor, so this is a rendering decision
            // and not a loss of data.
            assertThat(hostile.getCardNum()).isEqualTo("CARDNUMTOKEN0001");
            assertThat(hostile.getCardCvvCd()).isEqualTo("QVZ");
            assertThat(hostile.getCardAcctId()).isEqualTo("ACCTIDTOKEN");
            assertThat(hostile.getCardExpirationDate()).isEqualTo("EXPIRYTOKN");
        }

        @Test
        @DisplayName("the real seeded verification code and card number are both absent, so the "
                + "guarantee holds for fixture data and not only for synthetic tokens")
        void theSeededVerificationCodeIsAbsent() {
            final String rendered = seededRowOne().toString();

            assertThat(rendered).doesNotContain(SEED_CARD_NUM);
            assertThat(rendered).doesNotContain(SEED_EMBOSSED_NAME_TEXT);
            assertThat(rendered).doesNotContain(SEED_EXPIRATION_DATE);

            // The seeded code 747 does not occur anywhere in this record's card number, so its
            // absence from the rendering is a real observation rather than an artefact.
            assertThat(SEED_CARD_NUM).doesNotContain(SEED_CVV_CD);
            assertThat(rendered).doesNotContain(SEED_CVV_CD);
        }

        /**
         * The status is quoted and untrimmed; the card number's padding is withheld along with it.
         *
         * <p>Quoting exists so that significant padding stays visible to a reader, and it applies to the
         * one value the rendering still prints. It cannot apply to the card number, because the stand-in
         * replaces the value whole - which is deliberate: a rendered length discriminates between
         * candidate card numbers as surely as a fragment does, so a padded key and an unpadded one must
         * render identically. That is asserted here directly.</p>
         */
        @Test
        @DisplayName("the printed status is quoted and untrimmed, while a padded card number renders "
                + "identically to an unpadded one so no length is disclosed")
        void bothRenderedValuesAreQuotedAndUntrimmed() {
            final Card padded = new Card("12345" + " ".repeat(11),
                    SEED_ACCT_ID,
                    SEED_CVV_CD,
                    seedEmbossedName(),
                    SEED_EXPIRATION_DATE,
                    SEED_ACTIVE_STATUS);

            assertThat(padded.toString())
                    .isEqualTo("Card[cardNum=***REDACTED***, cardActiveStatus='Y']");

            // Neither the value nor its width reaches the text.
            assertThat(padded.toString()).doesNotContain("12345");
            assertThat(padded.toString())
                    .as("a padded key and a short one render identically, so the stand-in discloses "
                            + "no length")
                    .isEqualTo(new Card("12345", SEED_ACCT_ID, SEED_CVV_CD, seedEmbossedName(),
                            SEED_EXPIRATION_DATE, SEED_ACTIVE_STATUS).toString());

            // The padding is still inside the accessor, which is what makes the rendering a decision
            // about disclosure rather than a loss of significant characters.
            assertThat(padded.getCardNum()).isEqualTo("12345" + " ".repeat(11));

            // The status keeps its quotes, which is what would make its own padding visible.
            assertThat(padded.toString()).contains("cardActiveStatus='Y'");
        }

        @Test
        @DisplayName("the diagnostic rendering of a provider-instantiated instance is the same fixed "
                + "shape carrying unset values, so an assertion failure reports rather than throws")
        void theDiagnosticRenderingOfAnEmptyInstanceIsTheSameShape() {
            // Pinned exactly rather than merely checked for existence: an unset entity renders its
            // status as the literal text null, and the surrounding shape is unchanged. The stand-in
            // stands in for the card number whether or not one is set, which is what keeps presence
            // itself undisclosed - a stand-in that appeared only when a key existed would leak one bit.
            assertThat(new Card().toString())
                    .isEqualTo("Card[cardNum=***REDACTED***, cardActiveStatus='null']");
            assertThat(new Card().toString())
                    .as("the withholding is unconditional, so an unset key is indistinguishable "
                            + "from a set one in the rendering")
                    .contains("cardNum=***REDACTED***");
        }
    }

    /**
     * A subclass standing in for the kind of instance a persistence provider may hand back.
     *
     * <p>Used only to observe that equality is symmetric across a subclass sharing the key, which is
     * the practical consequence of testing the type by pattern rather than by exact class.
     */
    private static final class ProxyLikeCard extends Card {

        /**
         * Creates a subclassed card carrying only a key.
         *
         * @param cardNum the card number to carry
         */
        private ProxyLikeCard(final String cardNum) {
            super(cardNum, null, null, null, null, null);
        }
    }
}
