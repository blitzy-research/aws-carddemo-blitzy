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
 * Unit tests for {@link AccountStatus}, the typed replacement for the CardDemo account
 * active-status vocabulary.
 *
 * <p>The type stands in for the legacy field {@code ACCT-ACTIVE-STATUS}, a one-byte alphanumeric
 * field declared in the account copybook and occupying byte 12 of the 300-byte account record. It
 * replaces character comparison with two named constants and a predicate, which is the translation
 * the migration mandates for a legacy condition-name vocabulary: a level-88 value list becomes enum
 * constants with predicate methods, and a set-to-true becomes an enum assignment.
 *
 * <p><strong>The vocabulary was recovered, not read.</strong> Three verified negative findings mean
 * the two admitted codes cannot be read off the record layout, so this class re-derives them from
 * the same evidence the production type cites and never from the production type itself: the
 * copybook attaches no level-88 condition name to the field, so it enumerates no permitted value at
 * all; the field is never compared against a literal anywhere in the estate, its six references
 * being a declaration, three moves, a field-to-field comparison against a before-image and a
 * diagnostic display; and all 50 seeded account records carry a single distinct value at byte 12, so
 * the seed data reveals one code and cannot reveal the other. The vocabulary therefore comes from
 * the account-update program, the only one that validates this field, which routes the submitted
 * status through a shared yes/no editor paragraph whose two level-88 names each admit exactly two
 * values, and whose leading comment states the same requirement in words.
 *
 * <p><strong>Why an unmapped code must not throw.</strong> Only the online update program validates
 * this field. The batch programs that read account records take the status straight from the file
 * without validating it, and the relational column is a plain one-character string carrying no check
 * constraint, so an out-of-vocabulary byte <em>persists successfully</em> at the database layer and
 * reaches the Java layer intact. Every lookup assertion below therefore proves absorption rather
 * than rejection: an unmapped code yields an empty result and never an exception.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test: it starts no application context, opens no
 * database, queue or socket, reads no file, and uses no mocking framework, because the type under
 * test is a value type with no collaborator. It deliberately asserts nothing about persistence
 * mapping - the type carries no persistence annotation and no attribute converter, the account
 * entity keeps the status as a raw one-character column, and the correspondence between entity and
 * schema is asserted by {@code EntityPersistenceMappingTest}, which compares the mapping the
 * persistence provider computes against the shipped migration {@code V1__create_schema.sql}, rather
 * than here.
 *
 * @see AccountStatus
 */
@DisplayName("AccountStatus :: typed account active-status vocabulary")
class AccountStatusTest {

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
     * the resulting code point, because a char is inherently one byte wide in that encoding and
     * measuring it alone would prove nothing.
     */

    /**
     * Raw code of the active status.
     *
     * <p>Admitted by {@code FLG-ACCT-STATUS-ISVALID} and the only value present
     * in the seeded account data.</p>
     */
    private static final char ACTIVE_CODE = 'Y';

    /**
     * Raw code of the inactive status.
     *
     * <p>Admitted by {@code FLG-ACCT-STATUS-ISVALID} and used as the initial
     * value of the shared yes/no work field, but absent from the seeded account
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
     * <p>Two, and only two. The validation-flag states that share the level-88
     * group with the two real codes are states of the flag rather than values of
     * the status, and are deliberately absent from the constant set.</p>
     */
    private static final int EXPECTED_CONSTANT_COUNT = 2;

    /** Declared width of {@code ACCT-ACTIVE-STATUS} in bytes. */
    private static final int STATUS_FIELD_WIDTH = 1;

    /**
     * Declared width of {@code ACCT-ID} in bytes.
     *
     * <p>The eleven-digit account key is the only field declared ahead of the
     * status, so it alone determines where the status begins.</p>
     */
    private static final int ACCT_ID_WIDTH = 11;

    /**
     * Documented one-based byte offset of {@code ACCT-ACTIVE-STATUS} within
     * {@code ACCOUNT-RECORD}, as cited by the type under test.
     *
     * <p>The layout test below derives this figure by summation rather than
     * trusting it, so the constant is the claim being checked and not the
     * evidence.</p>
     */
    private static final int DOCUMENTED_ONE_BASED_STATUS_OFFSET = 12;

    /** Documented record length of {@code ACCOUNT-RECORD}, from its own banner. */
    private static final int DOCUMENTED_RECORD_LENGTH = 300;

    /**
     * Declared field widths of {@code ACCOUNT-RECORD} in declaration order.
     *
     * <p>Eleven-digit key; one-byte status; three twelve-byte signed amounts;
     * three ten-byte dates; two further twelve-byte signed amounts; a ten-byte
     * postal code; a ten-byte group identifier; and a one-hundred-and-seventy
     * eight-byte trailing filler. Each signed amount is a ten-digit-plus-two
     * decimal zoned field under display usage, which occupies twelve bytes
     * because the sign is overpunched into the final digit rather than taking a
     * byte of its own.</p>
     */
    private static final int[] ACCOUNT_RECORD_FIELD_WIDTHS = {
        11, 1, 12, 12, 12, 10, 10, 10, 12, 12, 10, 10, 178,
    };

    /** Fields the account copybook declares, counting the trailing filler. */
    private static final int ACCOUNT_RECORD_FIELD_COUNT = 13;

    /** Records in the seeded account fixture. */
    private static final int SEEDED_ACCOUNT_RECORD_COUNT = 50;

    @Nested
    @DisplayName("Vocabulary recovered from the account-update editor")
    class Vocabulary {

        @Test
        @DisplayName("the two constants carry the raw codes Y and N that FLG-ACCT-STATUS-ISVALID admits")
        void bothConstantsCarryTheirLegacyRawCode() {
            assertThat(AccountStatus.ACTIVE.getCode())
                    .as("raw code stored at byte 12 for an active account")
                    .isEqualTo(ACTIVE_CODE);
            assertThat(AccountStatus.INACTIVE.getCode())
                    .as("raw code stored at byte 12 for an account that is not active")
                    .isEqualTo(INACTIVE_CODE);
        }

        @Test
        @DisplayName("the vocabulary admits exactly two constants, because the level-88 value list names exactly "
                + "two codes")
        void theVocabularyAdmitsExactlyTwoConstants() {
            final AccountStatus[] constants = AccountStatus.values();

            assertThat(constants)
                    .as("constants translated from FLG-ACCT-STATUS-ISVALID")
                    .hasSize(EXPECTED_CONSTANT_COUNT)
                    .containsExactly(AccountStatus.ACTIVE, AccountStatus.INACTIVE);
        }

        @Test
        @DisplayName("the enum exposes no constant for the NOT-OK flag state 0 and none for the BLANK flag state B, "
                + "because both are states of the validation flag and are never stored in ACCT-ACTIVE-STATUS")
        void neitherValidationFlagStateIsAStatusConstant() {
            // The two states named FLG-ACCT-STATUS-NOT-OK and
            // FLG-ACCT-STATUS-BLANK share a level-88 group with the two real
            // codes, which is what makes admitting them a live hazard. They
            // record that a submitted value failed validation and that a field
            // was left blank; neither is ever written to the 300-byte account
            // record, so admitting either would invent an account state the
            // estate does not have. They belong to the field-error surface,
            // which exposes them per field as MISSING and INVALID.
            //
            // Absence is proved from the outside and without reflection: the
            // constant set is exhaustively enumerated, so a third constant
            // would fail the first assertion, and the two flag characters
            // appear here only as rejected lookup inputs.
            assertThat(AccountStatus.values())
                    .as("the complete constant set, which a flag state would enlarge")
                    .hasSize(EXPECTED_CONSTANT_COUNT);

            assertThat(AccountStatus.fromCode('0'))
                    .as("the NOT-OK validation flag state is not a status")
                    .isEmpty();
            assertThat(AccountStatus.fromCode('B'))
                    .as("the BLANK validation flag state is not a status")
                    .isEmpty();
            assertThat(AccountStatus.fromCode("0"))
                    .as("the NOT-OK validation flag state is not a status column value")
                    .isEmpty();
            assertThat(AccountStatus.fromCode("B"))
                    .as("the BLANK validation flag state is not a status column value")
                    .isEmpty();
        }

        @Test
        @DisplayName("each raw code is exactly one byte wide and encodes to its ASCII code point, matching PIC X(01)")
        void eachRawCodeIsExactlyOneByteWide() {
            for (final AccountStatus status : AccountStatus.values()) {
                final byte[] encoded =
                        String.valueOf(status.getCode()).getBytes(StandardCharsets.US_ASCII);

                assertThat(encoded)
                        .as("US-ASCII record encoding of the code carried by %s", status.name())
                        .hasSize(STATUS_FIELD_WIDTH);
            }

            final byte[] activeEncoded =
                    String.valueOf(AccountStatus.ACTIVE.getCode()).getBytes(StandardCharsets.US_ASCII);
            final byte[] inactiveEncoded =
                    String.valueOf(AccountStatus.INACTIVE.getCode()).getBytes(StandardCharsets.US_ASCII);

            assertThat(activeEncoded[0])
                    .as("ASCII code point written to byte 12 for an active account")
                    .isEqualTo(ACTIVE_CODE_ASCII_BYTE);
            assertThat(inactiveEncoded[0])
                    .as("ASCII code point written to byte 12 for an account that is not active")
                    .isEqualTo(INACTIVE_CODE_ASCII_BYTE);
        }

        @Test
        @DisplayName("the constants are declared in the order the level-88 value list names them, Y before N")
        void constantsAreDeclaredInLevel88ListOrder() {
            assertThat(AccountStatus.ACTIVE.ordinal())
                    .as("position of the code the level-88 list names first")
                    .isZero();
            assertThat(AccountStatus.INACTIVE.ordinal())
                    .as("position of the code the level-88 list names second")
                    .isEqualTo(1);

            assertThat(AccountStatus.ACTIVE.name()).isEqualTo("ACTIVE");
            assertThat(AccountStatus.INACTIVE.name()).isEqualTo("INACTIVE");
        }
    }

    @Nested
    @DisplayName("Position of the status field inside the 300-byte ACCOUNT-RECORD")
    class RecordLayout {

        @Test
        @DisplayName("ACCT-ACTIVE-STATUS begins at byte 12 of the 300-byte ACCOUNT-RECORD, immediately after the "
                + "eleven-digit ACCT-ID")
        void activeStatusBeginsAtByteTwelve() {
            // ACCT-ID is the only field declared ahead of the status, so the
            // bytes preceding the status are exactly the width of that key. The
            // one-based offset is therefore that count plus one and the
            // zero-based offset is that count unchanged. The documented figure
            // is the claim under check; the summation is the evidence.
            final int bytesPrecedingStatus = ACCT_ID_WIDTH;
            final int derivedOneBasedOffset = bytesPrecedingStatus + 1;

            assertThat(derivedOneBasedOffset)
                    .as("one-based byte offset derived by summing the widths declared ahead of the status")
                    .isEqualTo(DOCUMENTED_ONE_BASED_STATUS_OFFSET);
            assertThat(bytesPrecedingStatus)
                    .as("zero-based byte offset of the status")
                    .isEqualTo(DOCUMENTED_ONE_BASED_STATUS_OFFSET - 1);

            final int lastByteOfStatus = derivedOneBasedOffset + STATUS_FIELD_WIDTH - 1;

            assertThat(lastByteOfStatus)
                    .as("the one-byte status field neither straddles a boundary nor overruns the record")
                    .isEqualTo(DOCUMENTED_ONE_BASED_STATUS_OFFSET)
                    .isLessThanOrEqualTo(DOCUMENTED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the thirteen declared field widths of ACCOUNT-RECORD sum to the 300 bytes its own banner "
                + "states, so the status byte sits inside a fully accounted-for layout")
        void declaredFieldWidthsSumToTheRecordLength() {
            int summedWidth = 0;
            for (final int fieldWidth : ACCOUNT_RECORD_FIELD_WIDTHS) {
                summedWidth += fieldWidth;
            }

            assertThat(ACCOUNT_RECORD_FIELD_WIDTHS)
                    .as("every field the account copybook declares, including the trailing filler")
                    .hasSize(ACCOUNT_RECORD_FIELD_COUNT);
            assertThat(summedWidth)
                    .as("summed declared field widths against the documented record length")
                    .isEqualTo(DOCUMENTED_RECORD_LENGTH);
            assertThat(ACCOUNT_RECORD_FIELD_WIDTHS[0])
                    .as("width of the first declared field, which is the account key")
                    .isEqualTo(ACCT_ID_WIDTH);
            assertThat(ACCOUNT_RECORD_FIELD_WIDTHS[1])
                    .as("width of the second declared field, which is the status")
                    .isEqualTo(STATUS_FIELD_WIDTH);

            // Ties the layout back to the type under test: whichever constant an
            // account carries, its code fills the second declared field exactly,
            // so no constant can overrun into the balance that follows it and
            // none can leave the field short.
            for (final AccountStatus status : AccountStatus.values()) {
                assertThat(String.valueOf(status.getCode()).getBytes(StandardCharsets.US_ASCII))
                        .as("the code carried by %s fills the second declared field exactly", status.name())
                        .hasSize(ACCOUNT_RECORD_FIELD_WIDTHS[1]);
            }
        }
    }

    @Nested
    @DisplayName("Active predicate translated from the level-88 condition name")
    class ActivePredicate {

        @Test
        @DisplayName("only the Y constant is active, and the N constant is not")
        void onlyTheActiveConstantIsActive() {
            assertThat(AccountStatus.ACTIVE.isActive())
                    .as("account carrying the active code at byte 12")
                    .isTrue();
            assertThat(AccountStatus.INACTIVE.isActive())
                    .as("account carrying the inactive code at byte 12")
                    .isFalse();
        }

        @Test
        @DisplayName("exactly one of the two constants reports itself active, so the predicate partitions the "
                + "whole vocabulary rather than merely answering for one constant")
        void exactlyOneConstantReportsItselfActive() {
            int activeCount = 0;
            for (final AccountStatus status : AccountStatus.values()) {
                if (status.isActive()) {
                    activeCount++;
                }
            }

            assertThat(activeCount)
                    .as("constants for which the predicate holds")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the raw column value Y is active and the raw column value N is not, answered directly from "
                + "the one-character column without resolving a constant first")
        void rawColumnValuesAnswerThePredicateDirectly() {
            assertThat(AccountStatus.isActiveCode(ACTIVE_COLUMN_VALUE))
                    .as("active code as it is held in the one-character status column")
                    .isTrue();
            assertThat(AccountStatus.isActiveCode(INACTIVE_COLUMN_VALUE))
                    .as("inactive code as it is held in the one-character status column")
                    .isFalse();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "B", "y", "n", " ", "", "A", "1", "YY", "YN", "Y "})
        @DisplayName("the predicate answers false for any column value outside the vocabulary, so an unvalidated "
                + "byte that the batch readers let through is never reported active")
        void predicateAnswersFalseForAnyValueOutsideTheVocabulary(final String columnValue) {
            assertThat(AccountStatus.isActiveCode(columnValue)).isFalse();
        }
    }

    @Nested
    @DisplayName("Tolerant lookup from a raw code to a constant")
    class CodeLookup {

        @Test
        @DisplayName("both raw codes the level-88 value list admits resolve to their constant")
        void bothRawCodesResolveToTheirConstant() {
            final Optional<AccountStatus> active = AccountStatus.fromCode(ACTIVE_CODE);
            final Optional<AccountStatus> inactive = AccountStatus.fromCode(INACTIVE_CODE);

            assertThat(active)
                    .as("code read from byte 12 of an active account record")
                    .contains(AccountStatus.ACTIVE);
            assertThat(inactive)
                    .as("code read from byte 12 of an account record that is not active")
                    .contains(AccountStatus.INACTIVE);
        }

        @Test
        @DisplayName("both one-character column values resolve to their constant, so a value taken straight from "
                + "the one-character status column needs no conversion at the call site")
        void bothColumnValuesResolveToTheirConstant() {
            assertThat(AccountStatus.fromCode(ACTIVE_COLUMN_VALUE)).contains(AccountStatus.ACTIVE);
            assertThat(AccountStatus.fromCode(INACTIVE_COLUMN_VALUE)).contains(AccountStatus.INACTIVE);
        }

        @ParameterizedTest
        @ValueSource(chars = {'0', 'B', 'y', 'n', 'A', 'Z', '1', '9', '*', ' '})
        @DisplayName("a raw code outside the vocabulary yields an empty result, because the batch readers accept "
                + "whatever the account file holds and the status column carries no check constraint")
        void rawCodesOutsideTheVocabularyResolveToEmpty(final char rawCode) {
            assertThat(AccountStatus.fromCode(rawCode)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "B", "y", "n", " ", "", "A", "*", "YN", "NY"})
        @DisplayName("a column value that is absent, empty, over-length or outside the vocabulary yields an empty "
                + "result")
        void columnValuesOutsideTheVocabularyResolveToEmpty(final String columnValue) {
            assertThat(AccountStatus.fromCode(columnValue)).isEmpty();
        }

        @Test
        @DisplayName("the three values the editor treats as not supplied, namely LOW-VALUES, SPACES and ZEROS, "
                + "each yield an empty result, mirroring the editor testing absence before validity")
        void theNotSuppliedTriadResolvesToEmpty() {
            assertThat(AccountStatus.fromCode(Character.MIN_VALUE))
                    .as("LOW-VALUES in the one-byte status field")
                    .isEmpty();
            assertThat(AccountStatus.fromCode(' '))
                    .as("SPACES in the one-byte status field")
                    .isEmpty();
            assertThat(AccountStatus.fromCode('0'))
                    .as("ZEROS in the one-byte status field")
                    .isEmpty();
        }

        @Test
        @DisplayName("lookup applies no case folding, so a lowercase y is not an active status")
        void lookupAppliesNoCaseFolding() {
            assertThat(AccountStatus.fromCode('y'))
                    .as("lowercase form of the active code as a raw character")
                    .isEmpty();
            assertThat(AccountStatus.fromCode("y"))
                    .as("lowercase form of the active code as a column value")
                    .isEmpty();
            assertThat(AccountStatus.isActiveCode("y"))
                    .as("lowercase form of the active code is not active")
                    .isFalse();
            assertThat(AccountStatus.fromCode('n'))
                    .as("lowercase form of the inactive code as a raw character")
                    .isEmpty();
            assertThat(AccountStatus.fromCode("n"))
                    .as("lowercase form of the inactive code as a column value")
                    .isEmpty();
        }

        @Test
        @DisplayName("an over-length column value is rejected rather than truncated, because the legacy system "
                + "performs no truncation on a one-byte field")
        void overLengthColumnValueIsRejectedRatherThanTruncated() {
            assertThat(AccountStatus.fromCode("YY")).isEmpty();
            assertThat(AccountStatus.fromCode("YN")).isEmpty();
            assertThat(AccountStatus.fromCode("Y ")).isEmpty();
        }

        @Test
        @DisplayName("an absent column value yields an empty result and an inactive answer rather than an "
                + "exception")
        void absentColumnValueResolvesToEmpty() {
            final String absentColumnValue = null;

            assertThat(AccountStatus.fromCode(absentColumnValue)).isEmpty();
            assertThat(AccountStatus.isActiveCode(absentColumnValue)).isFalse();
        }

        @Test
        @DisplayName("lookup never throws for any unmapped input, which is what lets an unvalidated file-sourced "
                + "byte flow through the Java layer exactly as it flows through the legacy system")
        void lookupNeverThrows() {
            final String absentColumnValue = null;

            assertThatCode(() -> AccountStatus.fromCode('#')).doesNotThrowAnyException();
            assertThatCode(() -> AccountStatus.fromCode(Character.MIN_VALUE)).doesNotThrowAnyException();
            assertThatCode(() -> AccountStatus.fromCode(Character.MAX_VALUE)).doesNotThrowAnyException();
            assertThatCode(() -> AccountStatus.fromCode("")).doesNotThrowAnyException();
            assertThatCode(() -> AccountStatus.fromCode("an unmapped value")).doesNotThrowAnyException();
            assertThatCode(() -> AccountStatus.fromCode(absentColumnValue)).doesNotThrowAnyException();
            assertThatCode(() -> AccountStatus.isActiveCode(absentColumnValue)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("resolving the sole code carried by all 50 seeded account records yields the same active "
                + "constant every time, so the code index is built once and never mutated")
        void repeatedResolutionOfTheSeededCodeIsStable() {
            // The seeded account fixture holds 50 records of 300 bytes and every
            // one of them carries the active code at byte 12. The fixture itself
            // is never read here: the code is restated as a literal and the
            // record count drives the repetition, so a lazily populated or
            // mutable index would show up as a differing later answer.
            for (int recordIndex = 1; recordIndex <= SEEDED_ACCOUNT_RECORD_COUNT; recordIndex++) {
                assertThat(AccountStatus.fromCode(ACTIVE_CODE))
                        .as("seeded account record %d of %d", recordIndex, SEEDED_ACCOUNT_RECORD_COUNT)
                        .contains(AccountStatus.ACTIVE);
            }
        }
    }
}
