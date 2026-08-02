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
package com.carddemo.domain.enums;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link CardStatus}, the typed replacement for the CardDemo card active-status
 * vocabulary.
 *
 * <p>The type stands in for the legacy field {@code CARD-ACTIVE-STATUS}, a one-byte alphanumeric
 * field declared as the sixth of seven fields in the card copybook {@code CVACT02Y} and occupying
 * byte 91 of the 150-byte {@code CARD-RECORD} image. It replaces character comparison with two named
 * constants and a predicate, which is the translation the migration mandates for a legacy
 * condition-name vocabulary: a level-88 value list becomes enum constants carrying predicate
 * methods, and a set-to-true becomes an enum assignment.
 *
 * <p><strong>The vocabulary was recovered, not read.</strong> Three verified negative findings mean
 * the two admitted codes cannot be read off the record layout, so this class re-derives them from
 * the same evidence the production type cites and never from the production type itself. The
 * copybook attaches no level-88 condition name to the field at all, so it enumerates no permitted
 * value; the field is never compared against a literal anywhere in the estate, its eight references
 * being one declaration, six moves and a single field-to-field comparison against a before-image
 * that serves the card-update optimistic-lock check; and all 50 seeded card records carry one and
 * the same code at byte 91, so the seed data reveals one code and cannot reveal the other. The
 * vocabulary therefore comes from the card-update program, the only one that validates this field,
 * which routes the submitted value through a one-character yes/no check field initialised to the
 * inactive code and guarded by a level-88 condition name admitting exactly two values, having first
 * treated low-values, spaces and zeros as a not-supplied condition. The account-update program's
 * shared yes/no editor paragraph corroborates the same two-value vocabulary independently, and both
 * paragraphs carry a leading comment stating the same requirement in words.
 *
 * <p><strong>Validation-flag states are excluded on purpose.</strong> A not-OK state and a blank
 * state sit beside the two real codes in the legacy editors, and in the account-update program they
 * share a single level-88 group with them on one one-character work field, which is what makes
 * admitting them a live hazard rather than a theoretical one. Neither is a card status: both record
 * an outcome of validation, that a submitted value failed it or that the field was left blank, and
 * neither is ever written to byte 91 of the 150-byte record. Admitting either would invent a card
 * state the estate does not have. They belong to the field-error surface, which exposes them per
 * field as MISSING and INVALID, so below they appear only as rejected lookup inputs.
 *
 * <p><strong>Why an unmapped code must not throw.</strong> Only the online card-update program
 * validates this field. The batch programs that read card records take the status straight from the
 * file without validating it, and the relational column replacing byte 91 is a plain one-character
 * column carrying no check constraint, so an out-of-vocabulary byte <em>persists successfully</em>
 * at the database layer and reaches the Java layer intact. Every lookup assertion below therefore
 * proves absorption rather than rejection: an unmapped code yields an empty result and never an
 * exception, and there is deliberately no synthetic catch-all constant to absorb a miss.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test: it starts no application context, opens no
 * database, queue or socket, reads no file, and uses no mocking framework, because the type under
 * test is a value type with no collaborator. It deliberately asserts nothing about persistence
 * mapping - the type carries no persistence annotation and no attribute converter, the card entity
 * keeps the status as a raw one-character column, and the correspondence between entity and schema
 * is asserted by {@code EntityPersistenceMappingTest}, which compares the mapping the persistence
 * provider computes against the shipped migration {@code V1__create_schema.sql}, rather than here.
 *
 * <p><strong>Legacy provenance.</strong> Repository checkout
 * 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp CardDemo_v1.0-15-g27d6c6f-68
 * dated 2022-07-19. Recorded here as prose only. The stamp is never asserted against a source
 * member, because the estate does not carry it uniformly.
 *
 * @see CardStatus
 */
@DisplayName("CardStatus :: typed card active-status vocabulary")
class CardStatusTest {

    /*
     * THE INDEPENDENT ORACLE.
     *
     * Every expected value in this class is a literal hand-derived from the legacy artefacts.
     * Nothing here asks the type under test to supply its own expected value, nothing snapshots its
     * output, and no assertion compares one production call against another.
     *
     * Two places where faithful translation and idiomatic Java diverge are asserted below, and both
     * are recorded in docs/decision-log.md rather than settled by taste here: the two
     * validation-flag characters are excluded from the constant set even though the legacy level-88
     * group lists them alongside the two real codes, and an unmapped code yields an empty result
     * instead of the exception an idiomatic lookup would raise.
     *
     * The width assertion encodes the raw char to bytes at an explicit US-ASCII boundary and pins
     * the resulting code point, because a char is inherently one byte wide in that encoding, so a
     * width measurement alone says nothing about which byte lands in the record.
     *
     * Two independent derivations of the record length are used on purpose. The offset test sums the
     * five field widths declared ahead of the status; the layout test sums the seven-element width
     * array. Arriving at 150 twice by different routes is what makes the byte 91 claim evidence
     * rather than restatement.
     */

    /**
     * Raw code of the active status.
     *
     * <p>Admitted by the card-update program's yes/no condition name and the
     * only value present in the seeded card data.</p>
     */
    private static final char ACTIVE_CODE = 'Y';

    /**
     * Raw code of the inactive status.
     *
     * <p>Admitted by the same condition name and used as the initial value of
     * the one-character yes/no check field, but absent from the seeded card
     * data.</p>
     */
    private static final char INACTIVE_CODE = 'N';

    /** The active status as a raw one-character column value. */
    private static final String ACTIVE_COLUMN_VALUE = "Y";

    /** The inactive status as a raw one-character column value. */
    private static final String INACTIVE_COLUMN_VALUE = "N";

    /**
     * ASCII code point of the active code.
     *
     * <p>Taken from the ASCII table, not from the type under test, so that the
     * encoded record byte is pinned by an independent authority.</p>
     */
    private static final byte ACTIVE_CODE_ASCII_BYTE = (byte) 0x59;

    /** ASCII code point of the inactive code. */
    private static final byte INACTIVE_CODE_ASCII_BYTE = (byte) 0x4E;

    /**
     * Number of constants the vocabulary admits.
     *
     * <p>Two, and only two. The validation-flag states that sit beside the two
     * real codes in the legacy editors are states of the flag rather than values
     * of the status, and are deliberately absent from the constant set.</p>
     */
    private static final int EXPECTED_CONSTANT_COUNT = 2;

    /** Constants for which the active predicate is expected to hold. */
    private static final int EXPECTED_ACTIVE_CONSTANT_COUNT = 1;

    /** Declared width of {@code CARD-ACTIVE-STATUS} in bytes. */
    private static final int STATUS_FIELD_WIDTH = 1;

    /** Declared width of {@code CARD-NUM}, the first field of the record. */
    private static final int CARD_NUM_WIDTH = 16;

    /** Declared width of {@code CARD-ACCT-ID}, the eleven-digit account key. */
    private static final int CARD_ACCT_ID_WIDTH = 11;

    /** Declared width of {@code CARD-CVV-CD}, the three-digit verification code. */
    private static final int CARD_CVV_CD_WIDTH = 3;

    /** Declared width of {@code CARD-EMBOSSED-NAME}. */
    private static final int CARD_EMBOSSED_NAME_WIDTH = 50;

    /**
     * Declared width of the expiry date field, the last field ahead of the
     * status.
     *
     * <p>The copybook misspells that field's name. The misspelling is an
     * anomaly of the card entity's field mapping and is not this type's
     * concern; only the ten-byte width matters here, because it is the final
     * term of the summation that locates the status byte.</p>
     */
    private static final int CARD_EXPIRY_DATE_WIDTH = 10;

    /** Declared width of the trailing filler that closes the record. */
    private static final int TRAILING_FILLER_WIDTH = 59;

    /**
     * Documented one-based byte offset of {@code CARD-ACTIVE-STATUS} within
     * {@code CARD-RECORD}, as cited by the type under test.
     *
     * <p>The layout tests below derive this figure by summation rather than
     * trusting it, so the constant is the claim being checked and not the
     * evidence.</p>
     */
    private static final int DOCUMENTED_ONE_BASED_STATUS_OFFSET = 91;

    /** The same offset expressed zero-based, which is how a mapper slices. */
    private static final int DOCUMENTED_ZERO_BASED_STATUS_OFFSET = 90;

    /** Documented record length of {@code CARD-RECORD}, from its own banner. */
    private static final int DOCUMENTED_RECORD_LENGTH = 150;

    /**
     * Declared field widths of {@code CARD-RECORD} in declaration order.
     *
     * <p>Sixteen-byte card number; eleven-digit account key; three-digit
     * verification code; fifty-byte embossed name; ten-byte expiry date;
     * one-byte status; and a fifty-nine-byte trailing filler.</p>
     */
    private static final int[] CARD_RECORD_FIELD_WIDTHS = {
        16, 11, 3, 50, 10, 1, 59,
    };

    /** Fields the card copybook declares, counting the trailing filler. */
    private static final int CARD_RECORD_FIELD_COUNT = 7;

    /**
     * Zero-based position of the status within the declared field order.
     *
     * <p>The status is the sixth of the seven declared fields.</p>
     */
    private static final int STATUS_FIELD_INDEX = 5;

    /** Zero-based position of the trailing filler within the declared field order. */
    private static final int TRAILING_FILLER_INDEX = 6;

    /** Records in the seeded card fixture. */
    private static final int SEEDED_CARD_RECORD_COUNT = 50;

    @Nested
    @DisplayName("Vocabulary recovered from the card-update editor")
    class Vocabulary {

        @Test
        @DisplayName("the two constants carry the raw codes Y and N that the card-update yes/no condition name "
                + "admits")
        void bothConstantsCarryTheirLegacyRawCode() {
            assertThat(CardStatus.Y.getCode())
                    .as("raw code stored at byte 91 for a card that is active")
                    .isEqualTo(ACTIVE_CODE);
            assertThat(CardStatus.N.getCode())
                    .as("raw code stored at byte 91 for a card that is not active")
                    .isEqualTo(INACTIVE_CODE);
        }

        @Test
        @DisplayName("the vocabulary admits exactly two constants, because the level-88 value list names exactly "
                + "two codes")
        void theVocabularyAdmitsExactlyTwoConstants() {
            final CardStatus[] constants = CardStatus.values();

            assertThat(constants)
                    .as("constants translated from the card-update yes/no condition name")
                    .hasSize(EXPECTED_CONSTANT_COUNT)
                    .containsExactly(CardStatus.Y, CardStatus.N);
        }

        @Test
        @DisplayName("each constant is named after the raw byte it stores, so a record image and a constant name "
                + "cannot drift apart by a single character")
        void eachConstantIsNamedAfterTheRawByteItStores() {
            // This enumeration names its constants after their codes rather than
            // after their meaning, which is unusual for Java and deliberate here:
            // the constant name and the stored byte coincide, so the mapping
            // between the record image and the type cannot be got wrong by one
            // character. The expected names are literals, not the codes read back
            // off the type, so a rename would fail this test rather than pass it
            // by construction.
            assertThat(CardStatus.Y.name())
                    .as("name of the constant carrying the active code")
                    .isEqualTo("Y");
            assertThat(CardStatus.N.name())
                    .as("name of the constant carrying the inactive code")
                    .isEqualTo("N");
        }

        @Test
        @DisplayName("the constants are declared in the order the level-88 value list names them, Y before N")
        void constantsAreDeclaredInLevel88ListOrder() {
            assertThat(CardStatus.Y.ordinal())
                    .as("position of the code the level-88 list names first")
                    .isZero();
            assertThat(CardStatus.N.ordinal())
                    .as("position of the code the level-88 list names second")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("each raw code is exactly one byte wide and encodes to its ASCII code point, matching "
                + "PIC X(01) at byte 91")
        void eachRawCodeIsExactlyOneByteWide() {
            for (final CardStatus status : CardStatus.values()) {
                final byte[] encoded =
                        String.valueOf(status.getCode()).getBytes(StandardCharsets.US_ASCII);

                assertThat(encoded)
                        .as("US-ASCII record encoding of the code carried by %s", status.name())
                        .hasSize(STATUS_FIELD_WIDTH);
            }

            // A width measurement alone says nothing about which byte lands in the
            // record, because a char is inherently one byte wide in this encoding. The
            // code points below are read from the ASCII table and pin the byte that
            // actually reaches offset 91, which is the part a wrong constant could get
            // wrong.
            final byte[] activeEncoded =
                    String.valueOf(CardStatus.Y.getCode()).getBytes(StandardCharsets.US_ASCII);
            final byte[] inactiveEncoded =
                    String.valueOf(CardStatus.N.getCode()).getBytes(StandardCharsets.US_ASCII);

            assertThat(activeEncoded[0])
                    .as("ASCII code point written to byte 91 for a card that is active")
                    .isEqualTo(ACTIVE_CODE_ASCII_BYTE);
            assertThat(inactiveEncoded[0])
                    .as("ASCII code point written to byte 91 for a card that is not active")
                    .isEqualTo(INACTIVE_CODE_ASCII_BYTE);
        }

        @Test
        @DisplayName("the enum exposes no constant for the NOT-OK flag state 0 and none for the BLANK flag "
                + "state B, because both are states of the validation flag and are never stored in "
                + "CARD-ACTIVE-STATUS")
        void neitherValidationFlagStateIsAStatusConstant() {
            // In the account-update program the not-OK state and the blank state
            // share a single level-88 group with the two real codes on one
            // one-character work field, which is what makes admitting them a live
            // hazard; the card-update program keeps its equivalent states on a
            // separate flag field and codes its blank state as a space. Either
            // way both record an outcome of validation rather than a card state,
            // and neither is ever written to byte 91 of the 150-byte record, so
            // admitting either would invent a card state the estate does not have.
            // They belong to the field-error surface, which exposes them per field
            // as MISSING and INVALID.
            //
            // Absence is proved from the outside and without reflection: the
            // constant set is exhaustively enumerated, so a third constant would
            // fail the size assertion, and the two flag characters appear here
            // only as rejected lookup inputs.
            assertThat(CardStatus.values())
                    .as("the complete constant set, which a flag state would enlarge")
                    .hasSize(EXPECTED_CONSTANT_COUNT);

            assertThat(CardStatus.fromCode('0'))
                    .as("the NOT-OK validation flag state is not a card status")
                    .isEmpty();
            assertThat(CardStatus.fromCode('B'))
                    .as("the BLANK validation flag state is not a card status")
                    .isEmpty();
            assertThat(CardStatus.fromCode("0"))
                    .as("the NOT-OK validation flag state is not a status column value")
                    .isEmpty();
            assertThat(CardStatus.fromCode("B"))
                    .as("the BLANK validation flag state is not a status column value")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Position of the status field inside the 150-byte CARD-RECORD")
    class RecordLayout {

        @Test
        @DisplayName("CARD-ACTIVE-STATUS begins at byte 91 of the 150-byte CARD-RECORD, immediately after the "
                + "ten-byte expiry date")
        void activeStatusBeginsAtByteNinetyOne() {
            // Five fields are declared ahead of the status, so the bytes preceding
            // it are exactly the sum of those five widths. The one-based offset is
            // therefore that count plus one and the zero-based offset is that count
            // unchanged. The documented figures are the claims under check; the
            // summation is the evidence.
            final int bytesPrecedingStatus = CARD_NUM_WIDTH
                    + CARD_ACCT_ID_WIDTH
                    + CARD_CVV_CD_WIDTH
                    + CARD_EMBOSSED_NAME_WIDTH
                    + CARD_EXPIRY_DATE_WIDTH;

            assertThat(bytesPrecedingStatus)
                    .as("bytes consumed by the five fields declared ahead of the status")
                    .isEqualTo(DOCUMENTED_ZERO_BASED_STATUS_OFFSET);

            final int derivedOneBasedOffset = bytesPrecedingStatus + 1;

            assertThat(derivedOneBasedOffset)
                    .as("one-based byte offset derived by summing the widths declared ahead of the status")
                    .isEqualTo(DOCUMENTED_ONE_BASED_STATUS_OFFSET);
            assertThat(bytesPrecedingStatus)
                    .as("zero-based byte offset a fixed-width mapper slices at")
                    .isEqualTo(DOCUMENTED_ONE_BASED_STATUS_OFFSET - 1);

            final int lastByteOfStatus = derivedOneBasedOffset + STATUS_FIELD_WIDTH - 1;

            assertThat(lastByteOfStatus)
                    .as("the one-byte status field neither straddles a boundary nor overruns the record")
                    .isEqualTo(DOCUMENTED_ONE_BASED_STATUS_OFFSET)
                    .isLessThanOrEqualTo(DOCUMENTED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the fifty-nine-byte trailing filler closes the 150-byte CARD-RECORD from the status byte "
                + "onward, so byte 91 sits inside a layout with no unaccounted bytes")
        void theTrailingFillerClosesTheRecordFromTheStatusByte() {
            // The status is the last byte before the filler, so the status offset
            // plus the filler width must land exactly on the declared record
            // length: 91 + 59 = 150. A filler one byte out in either direction
            // would move every mapper's slice and is what this closure check
            // catches.
            assertThat(DOCUMENTED_ONE_BASED_STATUS_OFFSET + TRAILING_FILLER_WIDTH)
                    .as("status offset plus trailing filler width against the documented record length")
                    .isEqualTo(DOCUMENTED_RECORD_LENGTH);

            assertThat(DOCUMENTED_RECORD_LENGTH - DOCUMENTED_ONE_BASED_STATUS_OFFSET)
                    .as("bytes remaining after the status byte, which the filler must account for in full")
                    .isEqualTo(TRAILING_FILLER_WIDTH);
        }

        @Test
        @DisplayName("the seven declared field widths of CARD-RECORD sum to the 150 bytes its own banner states, "
                + "so the status byte sits inside a fully accounted-for layout")
        void declaredFieldWidthsSumToTheRecordLength() {
            int summedWidth = 0;
            for (final int fieldWidth : CARD_RECORD_FIELD_WIDTHS) {
                summedWidth += fieldWidth;
            }

            assertThat(CARD_RECORD_FIELD_WIDTHS)
                    .as("every field the card copybook declares, including the trailing filler")
                    .hasSize(CARD_RECORD_FIELD_COUNT);
            assertThat(summedWidth)
                    .as("summed declared field widths against the documented record length")
                    .isEqualTo(DOCUMENTED_RECORD_LENGTH);
            assertThat(CARD_RECORD_FIELD_WIDTHS[STATUS_FIELD_INDEX])
                    .as("width of the sixth declared field, which is the status")
                    .isEqualTo(STATUS_FIELD_WIDTH);
            assertThat(CARD_RECORD_FIELD_WIDTHS[TRAILING_FILLER_INDEX])
                    .as("width of the seventh declared field, which is the trailing filler")
                    .isEqualTo(TRAILING_FILLER_WIDTH);

            // Ties the layout back to the type under test: whichever constant a
            // card carries, its code fills the sixth declared field exactly, so no
            // constant can overrun into the filler that follows it and none can
            // leave the field short.
            for (final CardStatus status : CardStatus.values()) {
                assertThat(String.valueOf(status.getCode()).getBytes(StandardCharsets.US_ASCII))
                        .as("the code carried by %s fills the sixth declared field exactly", status.name())
                        .hasSize(CARD_RECORD_FIELD_WIDTHS[STATUS_FIELD_INDEX]);
            }
        }
    }

    @Nested
    @DisplayName("Active predicate translated from the level-88 condition name")
    class ActivePredicate {

        @Test
        @DisplayName("only the Y constant is active, and the N constant is not")
        void onlyTheActiveConstantIsActive() {
            assertThat(CardStatus.Y.isActive())
                    .as("card carrying the active code at byte 91")
                    .isTrue();
            assertThat(CardStatus.N.isActive())
                    .as("card carrying the inactive code at byte 91")
                    .isFalse();
        }

        @Test
        @DisplayName("exactly one of the two constants reports itself active, so the predicate partitions the "
                + "whole vocabulary rather than merely answering for one constant")
        void exactlyOneConstantReportsItselfActive() {
            int activeCount = 0;
            for (final CardStatus status : CardStatus.values()) {
                if (status.isActive()) {
                    activeCount++;
                }
            }

            assertThat(activeCount)
                    .as("constants for which the predicate holds")
                    .isEqualTo(EXPECTED_ACTIVE_CONSTANT_COUNT);
        }

        @Test
        @DisplayName("the raw column value Y is active and the raw column value N is not, composed from the "
                + "lookup and the predicate exactly as the documented caller idiom composes them")
        void rawColumnValuesAnswerThePredicateThroughTheComposedIdiom() {
            // The type exposes no boolean-returning convenience over a raw code,
            // so a caller composes the tolerant lookup with the predicate. That
            // composition is the contract this test pins, because it is the form
            // the service layer uses when it holds the status as a one-character
            // column value.
            final boolean activeForActiveCode = CardStatus.fromCode(ACTIVE_COLUMN_VALUE)
                    .map(CardStatus::isActive)
                    .orElse(false);
            final boolean activeForInactiveCode = CardStatus.fromCode(INACTIVE_COLUMN_VALUE)
                    .map(CardStatus::isActive)
                    .orElse(false);

            assertThat(activeForActiveCode)
                    .as("active code as it is held in the one-character status column")
                    .isTrue();
            assertThat(activeForInactiveCode)
                    .as("inactive code as it is held in the one-character status column")
                    .isFalse();
        }

        @Test
        @DisplayName("the composed predicate answers false for an absent column value rather than throwing, so a "
                + "card whose status was never populated is simply not active")
        void theComposedPredicateAnswersFalseForAnAbsentValue() {
            final String absentColumnValue = null;

            final boolean activeForAbsentValue = CardStatus.fromCode(absentColumnValue)
                    .map(CardStatus::isActive)
                    .orElse(false);

            assertThat(activeForAbsentValue)
                    .as("absent status column value")
                    .isFalse();
        }

        @Test
        @DisplayName("neither validation flag state is active, and a lowercase y is not active either, because no "
                + "case folding is applied before the predicate is reached")
        void flagStatesAndLowercaseCodesAreNotActive() {
            final boolean activeForNotOkFlagState =
                    CardStatus.fromCode('0').map(CardStatus::isActive).orElse(false);
            final boolean activeForBlankFlagState =
                    CardStatus.fromCode('B').map(CardStatus::isActive).orElse(false);
            final boolean activeForLowercaseActiveCode =
                    CardStatus.fromCode('y').map(CardStatus::isActive).orElse(false);

            assertThat(activeForNotOkFlagState)
                    .as("NOT-OK validation flag state reaching the predicate")
                    .isFalse();
            assertThat(activeForBlankFlagState)
                    .as("BLANK validation flag state reaching the predicate")
                    .isFalse();
            assertThat(activeForLowercaseActiveCode)
                    .as("lowercase form of the active code is not an active card")
                    .isFalse();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "B", "y", "n", " ", "", "A", "1", "YY", "YN", "Y "})
        @DisplayName("the composed predicate answers false for any column value outside the vocabulary, so an "
                + "unvalidated byte that the batch card readers let through is never reported active")
        void composedPredicateAnswersFalseForAnyValueOutsideTheVocabulary(final String columnValue) {
            final boolean active =
                    CardStatus.fromCode(columnValue).map(CardStatus::isActive).orElse(false);

            assertThat(active).isFalse();
        }
    }

    @Nested
    @DisplayName("Tolerant lookup from a raw code to a constant")
    class CodeLookup {

        @Test
        @DisplayName("both raw codes the level-88 value list admits resolve to their constant")
        void bothRawCodesResolveToTheirConstant() {
            final Optional<CardStatus> active = CardStatus.fromCode(ACTIVE_CODE);
            final Optional<CardStatus> inactive = CardStatus.fromCode(INACTIVE_CODE);

            assertThat(active)
                    .as("code read from byte 91 of a card record for an active card")
                    .contains(CardStatus.Y);
            assertThat(inactive)
                    .as("code read from byte 91 of a card record for a card that is not active")
                    .contains(CardStatus.N);
        }

        @Test
        @DisplayName("both one-character column values resolve to their constant, so a value taken straight from "
                + "the one-character status column needs no conversion at the call site")
        void bothColumnValuesResolveToTheirConstant() {
            assertThat(CardStatus.fromCode(ACTIVE_COLUMN_VALUE))
                    .as("active code as a one-character column value")
                    .contains(CardStatus.Y);
            assertThat(CardStatus.fromCode(INACTIVE_COLUMN_VALUE))
                    .as("inactive code as a one-character column value")
                    .contains(CardStatus.N);
        }

        @ParameterizedTest
        @ValueSource(chars = {'0', 'B', 'y', 'n', 'A', 'Z', '1', '9', '*', ' '})
        @DisplayName("a raw code outside the vocabulary yields an empty result, because the batch card readers "
                + "accept whatever the card file holds and the status column carries no check constraint")
        void rawCodesOutsideTheVocabularyResolveToEmpty(final char rawCode) {
            assertThat(CardStatus.fromCode(rawCode)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "B", "y", "n", " ", "", "A", "*", "YN", "NY"})
        @DisplayName("a column value that is absent, empty, over-length or outside the vocabulary yields an empty "
                + "result")
        void columnValuesOutsideTheVocabularyResolveToEmpty(final String columnValue) {
            assertThat(CardStatus.fromCode(columnValue)).isEmpty();
        }

        @Test
        @DisplayName("the three values the card-status editor treats as not supplied, namely LOW-VALUES, SPACES "
                + "and ZEROS, each yield an empty result, mirroring the editor testing absence before validity")
        void theNotSuppliedTriadResolvesToEmpty() {
            assertThat(CardStatus.fromCode(Character.MIN_VALUE))
                    .as("LOW-VALUES in the one-byte status field")
                    .isEmpty();
            assertThat(CardStatus.fromCode(' '))
                    .as("SPACES in the one-byte status field")
                    .isEmpty();
            assertThat(CardStatus.fromCode('0'))
                    .as("ZEROS in the one-byte status field")
                    .isEmpty();
        }

        @Test
        @DisplayName("lookup applies no case folding, so a lowercase y is not an active status")
        void lookupAppliesNoCaseFolding() {
            assertThat(CardStatus.fromCode('y'))
                    .as("lowercase form of the active code as a raw character")
                    .isEmpty();
            assertThat(CardStatus.fromCode("y"))
                    .as("lowercase form of the active code as a column value")
                    .isEmpty();
            assertThat(CardStatus.fromCode('n'))
                    .as("lowercase form of the inactive code as a raw character")
                    .isEmpty();
            assertThat(CardStatus.fromCode("n"))
                    .as("lowercase form of the inactive code as a column value")
                    .isEmpty();
        }

        @Test
        @DisplayName("an over-length column value is rejected rather than truncated, because the legacy system "
                + "performs no truncation on a one-byte field")
        void overLengthColumnValueIsRejectedRatherThanTruncated() {
            assertThat(CardStatus.fromCode("YY")).isEmpty();
            assertThat(CardStatus.fromCode("YN")).isEmpty();
            assertThat(CardStatus.fromCode("Y ")).isEmpty();
            assertThat(CardStatus.fromCode(" Y")).isEmpty();
        }

        @Test
        @DisplayName("an absent column value yields an empty result rather than an exception")
        void absentColumnValueResolvesToEmpty() {
            final String absentColumnValue = null;

            assertThat(CardStatus.fromCode(absentColumnValue))
                    .as("absent status column value")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("lookup never throws for any unmapped input, which is what lets an unvalidated file-sourced "
                + "byte flow through the Java layer exactly as it flows through the legacy system")
        void lookupNeverThrows() {
            final String absentColumnValue = null;

            assertThatCode(() -> CardStatus.fromCode('0')).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode('B')).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode('#')).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode(Character.MIN_VALUE)).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode(Character.MAX_VALUE)).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode("")).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode("0")).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode("B")).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode("an unmapped value")).doesNotThrowAnyException();
            assertThatCode(() -> CardStatus.fromCode(absentColumnValue)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("resolving the sole code carried by all 50 seeded card records yields the same active "
                + "constant every time, so the code index is built once and never mutated")
        void repeatedResolutionOfTheSeededCodeIsStable() {
            // The seeded card fixture holds 50 records of 150 bytes and every one
            // of them carries the active code at byte 91. The fixture itself is
            // never read here: the code is restated as a literal and the record
            // count drives the repetition, so a lazily populated or mutable index
            // would show up as a differing later answer.
            for (int recordIndex = 1; recordIndex <= SEEDED_CARD_RECORD_COUNT; recordIndex++) {
                assertThat(CardStatus.fromCode(ACTIVE_CODE))
                        .as("seeded card record %d of %d", recordIndex, SEEDED_CARD_RECORD_COUNT)
                        .contains(CardStatus.Y);
            }
        }
    }
}
