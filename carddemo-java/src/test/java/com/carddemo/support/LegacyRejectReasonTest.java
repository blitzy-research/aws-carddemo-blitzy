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
package com.carddemo.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.domain.enums.RejectReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The reject-reason oracle, verified against the member it was typed out from, and paired with the
 * module's own vocabulary exactly once.
 *
 * <h2>Why an oracle needs its own specification</h2>
 * {@link LegacyRejectReason} exists so that no reject expectation in this suite is derived from the type
 * under test. That buys nothing if the oracle is itself wrong: a mistyped literal here would simply move
 * the whole suite's expectations to a different wrong value. So the literals are not merely trusted, they
 * are read back out of the read-only member at the lines each constant cites, in the first group below.
 * That makes the oracle a <em>verified restatement</em> of {@code app/cbl/CBTRN02C.cbl} rather than a
 * second opinion about it.
 *
 * <h2>The one place the two vocabularies meet</h2>
 * The second group is the single pairing of the oracle with {@code com.carddemo.domain.enums.RejectReason}
 * in the whole suite, and its direction is deliberate: the oracle is the <strong>expected</strong> value
 * and the module's enumeration is the <strong>actual</strong> one. Every fixture, trailer and 430-byte
 * expectation elsewhere is built from the oracle, so a wrong digit or a paraphrased description in the
 * module now fails here and nowhere else - instead of moving the produced record and the expected record
 * together and passing a byte comparison that checked nothing.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The member is read to confirm metadata - a
 * numeric literal and a quoted description at a cited line - and no implementation line is transcribed
 * into this file or into any assertion message.
 */
@DisplayName("the reject-reason oracle is what the member says, and the module agrees with it")
class LegacyRejectReasonTest {

    /** Reasons the estate sets, which is what makes "five and no more" a claim rather than a count. */
    private static final int REASONS_THE_MEMBER_SETS = 5;

    /** Encoded width of the description carried by code 100. */
    private static final int INVALID_CARD_DESCRIPTION_BYTES = 25;

    /** Encoded width of the description carried by codes 101 and 109 alike. */
    private static final int ACCOUNT_NOT_FOUND_DESCRIPTION_BYTES = 24;

    /** Encoded width of the description carried by code 102. */
    private static final int OVERLIMIT_DESCRIPTION_BYTES = 21;

    /** Encoded width of the description carried by code 103, the longest of the five. */
    private static final int AFTER_EXPIRATION_DESCRIPTION_BYTES = 42;

    /** Creates the specification. */
    LegacyRejectReasonTest() {
        super();
    }

    /**
     * Reads one line of the read-only member, 1-based as the citations state it.
     *
     * @param  lineNumber the 1-based line to read
     * @return that line, with any carriage return removed and nothing else changed
     * @throws IOException if the member cannot be read
     */
    private static String memberLine(final int lineNumber) throws IOException {
        final List<String> lines = new ArrayList<>(Files.readAllLines(
                TestDataFactory.legacyMember(LegacyRejectReason.MEMBER), StandardCharsets.ISO_8859_1));
        assertThat(lines.size())
                .as("%s must carry at least %d lines for the citation to be readable at all",
                        LegacyRejectReason.MEMBER, Integer.valueOf(lineNumber))
                .isGreaterThanOrEqualTo(lineNumber);
        final String line = lines.get(lineNumber - 1);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    @Nested
    @DisplayName("verified against the member it restates")
    class VerifiedAgainstTheMember {

        /** Creates the nested specification. */
        VerifiedAgainstTheMember() {
            // Intentionally empty: this group holds no state of its own.
        }

        @Test
        @DisplayName("every cited line of the member genuinely moves that constant's code, so a "
                + "mistyped citation cannot pass as a citation")
        void everyCitedLineMovesItsOwnCode() throws IOException {
            for (final LegacyRejectReason reason : LegacyRejectReason.values()) {
                final String line = memberLine(reason.line());

                assertThat(line)
                        .as("%s line %d is cited as where code %d is set; if it is not, every"
                                + " expectation built on this oracle is anchored to a line that says"
                                + " something else", LegacyRejectReason.MEMBER,
                                Integer.valueOf(reason.line()), Integer.valueOf(reason.code()))
                        .contains(Integer.toString(reason.code()));
                assertThat(line.trim())
                        .as("and the cited line is a move into the reason field rather than a comment"
                                + " that happens to mention the number - a commented-out line would"
                                + " otherwise satisfy a substring check")
                        .doesNotStartWith("*")
                        .contains("WS-VALIDATION-FAIL-REASON");
            }
        }

        @Test
        @DisplayName("every description text appears verbatim in the member immediately below the line "
                + "that sets its code, quoted as the member quotes it")
        void everyDescriptionAppearsVerbatimBelowItsCode() throws IOException {
            for (final LegacyRejectReason reason : LegacyRejectReason.values()) {
                final String following = memberLine(reason.line() + 1);

                assertThat(following)
                        .as("the member sets the code and then moves the description on the next line."
                                + " The text is compared character for character because it is written"
                                + " into a 76-byte field an operator reads, so a paraphrase is a"
                                + " contract change and not a wording preference. Code %d, %s line %d",
                                Integer.valueOf(reason.code()), LegacyRejectReason.MEMBER,
                                Integer.valueOf(reason.line() + 1))
                        .contains("'" + reason.description() + "'");
            }
        }

        @Test
        @DisplayName("the two field widths the oracle publishes are the picture clauses the member "
                + "declares, at the lines it declares them")
        void theFieldWidthsAreTheMembersOwnPictureClauses() throws IOException {
            assertThat(memberLine(LegacyRejectReason.REASON_FIELD_DECLARATION_LINE))
                    .as("the reason field is declared as %d numeric digits, which is what makes the"
                            + " emitted form four characters zero-filled rather than a rendered integer",
                            Integer.valueOf(LegacyRejectReason.REASON_CODE_DIGITS))
                    .contains("WS-VALIDATION-FAIL-REASON")
                    .contains("PIC 9(0" + LegacyRejectReason.REASON_CODE_DIGITS + ")");
            assertThat(memberLine(LegacyRejectReason.DESCRIPTION_FIELD_DECLARATION_LINE))
                    .as("and the description field is declared as %d characters, which is why a shorter"
                            + " text is blank-padded rather than trimmed",
                            Integer.valueOf(LegacyRejectReason.DESCRIPTION_CHARACTERS))
                    .contains("WS-VALIDATION-FAIL-REASON-DESC")
                    .contains("PIC X(" + LegacyRejectReason.DESCRIPTION_CHARACTERS + ")");
        }

        @Test
        @DisplayName("the member sets five reason codes and no sixth, so the oracle is complete as well "
                + "as correct")
        void theMemberSetsExactlyTheFiveCodesTheOracleCarries() throws IOException {
            final List<String> settingLines = new ArrayList<>();
            for (final String line : Files.readAllLines(
                    TestDataFactory.legacyMember(LegacyRejectReason.MEMBER),
                    StandardCharsets.ISO_8859_1)) {
                final String text = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                final String trimmed = text.trim();
                if (!trimmed.startsWith("*") && trimmed.contains("TO WS-VALIDATION-FAIL-REASON")
                        && !trimmed.contains("WS-VALIDATION-FAIL-REASON-DESC")) {
                    settingLines.add(trimmed);
                }
            }

            final List<Integer> nonZeroCodes = new ArrayList<>();
            for (final String line : settingLines) {
                for (final LegacyRejectReason reason : LegacyRejectReason.values()) {
                    if (line.contains(" " + reason.code() + " TO ")) {
                        nonZeroCodes.add(Integer.valueOf(reason.code()));
                    }
                }
            }

            assertThat(nonZeroCodes)
                    .as("every non-zero code the member moves into the reason field must be one this"
                            + " oracle carries, and all five must be moved. A code in the member that"
                            + " the oracle omits is a refusal no fixture can reach and no expectation"
                            + " can describe")
                    .containsExactlyInAnyOrderElementsOf(LegacyRejectReason.codes());
            assertThat(settingLines)
                    .as("and the member also resets the field to the no-rejection value, which is why"
                            + " that value is part of the vocabulary and not one of the five")
                    .anySatisfy(line -> assertThat(line)
                            .contains(" " + LegacyRejectReason.NO_REASON_CODE + " TO "));
        }
    }

    @Nested
    @DisplayName("well formed in itself")
    class WellFormed {

        /** Creates the nested specification. */
        WellFormed() {
            // Intentionally empty: this group holds no state of its own.
        }

        @Test
        @DisplayName("carries five reasons with five distinct codes in ascending order, none of them the "
                + "no-rejection value")
        void theFiveCodesAreDistinctAscendingAndNonZero() {
            assertThat(LegacyRejectReason.values()).hasSize(REASONS_THE_MEMBER_SETS);
            assertThat(LegacyRejectReason.codes())
                    .as("five distinct codes, ascending, and none of them the value that means nothing"
                            + " was refused - posting proceeds only on that value, so a refusal sharing"
                            + " it would post")
                    .hasSize(REASONS_THE_MEMBER_SETS)
                    .doesNotHaveDuplicates()
                    .isSorted()
                    .doesNotContain(Integer.valueOf(LegacyRejectReason.NO_REASON_CODE));
        }

        @Test
        @DisplayName("renders every code as four digits zero-filled on the left, which is the field the "
                + "record actually carries")
        void everyCodeRendersAsFourZeroFilledDigits() {
            for (final LegacyRejectReason reason : LegacyRejectReason.values()) {
                assertThat(reason.fourDigitForm())
                        .as("emitted form of code %d", Integer.valueOf(reason.code()))
                        .hasSize(LegacyRejectReason.REASON_CODE_DIGITS)
                        .containsOnlyDigits()
                        .isEqualTo("0" + reason.code());
            }
        }

        @Test
        @DisplayName("carries descriptions at the five measured widths, every one of them fitting the "
                + "76-character field with room for its blank padding")
        void theDescriptionsAreAtTheirMeasuredWidths() {
            assertThat(descriptionBytes(LegacyRejectReason.INVALID_CARD_NUMBER))
                    .isEqualTo(INVALID_CARD_DESCRIPTION_BYTES);
            assertThat(descriptionBytes(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_READ))
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION_BYTES);
            assertThat(descriptionBytes(LegacyRejectReason.OVERLIMIT_TRANSACTION))
                    .isEqualTo(OVERLIMIT_DESCRIPTION_BYTES);
            assertThat(descriptionBytes(LegacyRejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION))
                    .isEqualTo(AFTER_EXPIRATION_DESCRIPTION_BYTES);
            assertThat(descriptionBytes(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE))
                    .isEqualTo(ACCOUNT_NOT_FOUND_DESCRIPTION_BYTES);
            for (final LegacyRejectReason reason : LegacyRejectReason.values()) {
                assertThat(descriptionBytes(reason))
                        .as("description of code %d must fit the %d-character field; a longer text"
                                + " would be truncated into the record and the truncation would be the"
                                + " contract", Integer.valueOf(reason.code()),
                                Integer.valueOf(LegacyRejectReason.DESCRIPTION_CHARACTERS))
                        .isLessThanOrEqualTo(LegacyRejectReason.DESCRIPTION_CHARACTERS);
                assertThat(reason.description())
                        .as("and it is stored untrimmed and unpadded, because the padding belongs to the"
                                + " field and not to the text")
                        .isEqualTo(reason.description().strip());
                assertThat(reason.trigger())
                        .as("every reason states the condition that fires it, or a fixture claiming to"
                                + " reach it could not be read against anything")
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("keeps the two account-not-found reasons as one text under two codes, because they "
                + "differ in when the lookup failed and not in what an operator is told")
        void theTwoAccountNotFoundReasonsShareTheirTextAndNotTheirCode() {
            assertThat(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.description())
                    .isEqualTo(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_READ.description());
            assertThat(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.code())
                    .as("folding these two together would erase the distinction between a lookup that"
                            + " failed before the posting arithmetic and one that failed after it")
                    .isNotEqualTo(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_READ.code());
            assertThat(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.line())
                    .as("and they are set at different lines of the member, which is where that"
                            + " distinction comes from")
                    .isNotEqualTo(LegacyRejectReason.ACCOUNT_NOT_FOUND_ON_READ.line());
        }

        @Test
        @DisplayName("resolves each of its own codes and refuses one it does not carry")
        void resolutionByCodeIsTotalOverItsOwnCodesAndNothingElse() {
            for (final LegacyRejectReason reason : LegacyRejectReason.values()) {
                assertThat(LegacyRejectReason.byCode(reason.code())).contains(reason);
            }
            assertThat(LegacyRejectReason.byCode(LegacyRejectReason.NO_REASON_CODE))
                    .as("the no-rejection value resolves to no reason, because nothing was refused")
                    .isEmpty();
            assertThat(LegacyRejectReason.byCode(9999))
                    .as("and a code the member never sets resolves to nothing rather than to the"
                            + " nearest reason")
                    .isEmpty();
        }

        /**
         * The encoded width of one reason's description, which is the only width a fixed field is
         * measured in.
         *
         * @param  reason the reason
         * @return the description's length in US-ASCII bytes
         */
        private int descriptionBytes(final LegacyRejectReason reason) {
            return reason.description().getBytes(StandardCharsets.US_ASCII).length;
        }
    }

    @Nested
    @DisplayName("paired with the module's own vocabulary, exactly once and in one direction")
    class PairedWithTheModule {

        /** Creates the nested specification. */
        PairedWithTheModule() {
            // Intentionally empty: this group holds no state of its own.
        }

        @Test
        @DisplayName("every legacy code resolves to a module constant carrying that code, that "
                + "description and that name")
        void theModuleAgreesWithTheOracleOnEveryReason() {
            for (final LegacyRejectReason reason : LegacyRejectReason.values()) {
                assertThat(RejectReason.byReasonCode(reason.code()))
                        .as("the module must recognise legacy code %d, which %s sets at line %d when %s",
                                Integer.valueOf(reason.code()), LegacyRejectReason.MEMBER,
                                Integer.valueOf(reason.line()), reason.trigger())
                        .isPresent();
                final RejectReason resolved = RejectReason.byReasonCode(reason.code()).orElseThrow();

                assertThat(resolved.getReasonCode())
                        .as("the constant resolved for legacy code %d must carry that same code",
                                Integer.valueOf(reason.code()))
                        .isEqualTo(reason.code());
                assertThat(resolved.getDescription())
                        .as("and the description the estate writes into the 76-byte field, character for"
                                + " character. This is the assertion the whole oracle exists for: the"
                                + " expected value is the legacy literal and the actual value is the"
                                + " module's, so a paraphrase in the module fails here instead of being"
                                + " written into both the produced record and its expectation")
                        .isEqualTo(reason.description());
                assertThat(resolved.name())
                        .as("and the two are paired by name as well as by code, so a reviewer can read"
                                + " one against the other without a lookup table")
                        .isEqualTo(reason.name());
            }
        }

        @Test
        @DisplayName("the module declares no sixth reason the estate never sets, and no legacy reason "
                + "the module cannot name")
        void neitherVocabularyCarriesSomethingTheOtherDoesNot() {
            assertThat(RejectReason.values())
                    .as("a reason the module recognises but the estate never sets is a refusal that"
                            + " cannot happen, and would be reachable by no fixture and provable by no"
                            + " test")
                    .hasSameSizeAs(LegacyRejectReason.values());
            final List<Integer> moduleCodes = new ArrayList<>(RejectReason.values().length);
            for (final RejectReason reason : RejectReason.values()) {
                moduleCodes.add(Integer.valueOf(reason.getReasonCode()));
            }
            assertThat(moduleCodes)
                    .as("and the two code sets are equal, not merely equinumerous")
                    .containsExactlyInAnyOrderElementsOf(LegacyRejectReason.codes());
        }

        @Test
        @DisplayName("the no-rejection value the oracle publishes is the value the posting service "
                + "proceeds on, so the two agree on what 'not refused' means")
        void theNoRejectionValueAgreesWithTheServiceThatActsOnIt() {
            assertThat(TestDataFactory.NO_REJECT_REASON_CODE)
                    .as("the factory's no-rejection value is the oracle's, and posting proceeds only on"
                            + " it - a mismatch here would let a refused record post")
                    .isEqualTo(LegacyRejectReason.NO_REASON_CODE);
            assertThat(RejectReason.byReasonCode(LegacyRejectReason.NO_REASON_CODE))
                    .as("and the module resolves it to no reason, because it is the absence of one")
                    .isEmpty();
        }
    }
}
