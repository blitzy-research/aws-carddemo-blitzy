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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link CobolStringUtils}, the translation point of construct-mapping row 9 of the
 * migration requirement &mdash; <em>STRING / UNSTRING / INSPECT to String utility methods, with
 * delimiter behaviour, pointer semantics and tallying logic identical</em>.
 *
 * <p><strong>Why this test is written the way it is.</strong> Every one of the four primitives under
 * test is a documented parity trap: each has a natural-looking Java equivalent that compiles, reads
 * well, and accepts or transforms a different set of values than the legacy program does. A test
 * written against the idiomatic assumption passes while the defect ships. Every expectation below is
 * therefore derived from the legacy mechanism &mdash; the character tables, the substitution rule
 * and the fixed-field justification rule &mdash; and never from what a Java developer would expect
 * the method to do.
 *
 * <p><strong>Independent-oracle discipline.</strong> No assertion calls a method of
 * {@link CobolStringUtils} to compute the value it then compares against, and no expectation is a
 * captured snapshot of an earlier run. Expected values are written out as literals, or derived from
 * this class's own hand-written copies of the character tables, or derived from the ASCII code chart
 * by an explicit range test. The range test is deliberately a <em>different</em> mechanism from the
 * table lookup the production code uses, so the two cannot fail in the same direction together.
 *
 * <p><strong>Scope: exactly four primitives.</strong> Reading the {@code FROM} table at each of the
 * seven {@code CONVERTING} sites gives three alphabetic, two <em>alphanumeric</em> and two
 * upper-fold, so the contract is {@link CobolStringUtils#isAlphaOrSpace(String)},
 * {@link CobolStringUtils#isAlphaNumericOrSpace(String)},
 * {@link CobolStringUtils#asciiUpperFold(String)} and
 * {@link CobolStringUtils#rightJustifyZeroFill(String, int)}, one nested group each. The single
 * private table-membership helper shared by the first two predicates is exercised only through them,
 * because reaching a private member from a test would require reflection and the unsafe-code audit
 * budget for reflection is zero.
 *
 * <p><strong>Deliberately absent, and asserted nowhere.</strong> No numeric-only predicate, because
 * the numeric-only table pair declared at {@code [app/cbl/COACTUPC.cbl:L609]} and
 * {@code [app/cbl/COACTUPC.cbl:L612]} appears in no {@code INSPECT} statement anywhere in the
 * estate. No tokeniser, because the estate contains <b>zero</b> {@code UNSTRING} statements. No
 * selection-bitmap helper and no tally helper: the {@code REPLACING} site at
 * {@code [app/cbl/COCRDLIC.cbl:L1090]} and the {@code TALLYING} site at
 * {@code [app/cbl/COCRDLIC.cbl:L1079]}, both inside paragraph {@code 2250-EDIT-ARRAY} at
 * {@code [app/cbl/COCRDLIC.cbl:L1073]}, carry seven-row card-list page knowledge and belong to the
 * card-list service. No date handling, which belongs to the date-validation service. And no
 * pad-to-width helper: the tail-fill behaviour of the {@code STRING} statement at
 * {@code [app/cbl/CBACT04C.cbl:L485]} through {@code [app/cbl/CBACT04C.cbl:L489]}, where a
 * 13-character literal and an 11-character account identifier are written into a {@code PIC X(100)}
 * field with no pointer, no overflow clause and no preceding re-initialisation so that positions 25
 * through 100 retain whatever they already held, belongs to the interest-calculation service.
 *
 * <p><strong>How the banned idioms are named.</strong> Two Java idioms are forbidden here: the
 * Unicode letter-class predicates from {@code java.lang.Character}, and the library upper-casing
 * method on {@link String} in either its ambient-locale or its explicit-locale form. Both are
 * described in prose rather than spelled out, so that a mechanical self-audit which greps the source
 * for those identifiers finds no hit and cannot be satisfied by a comment that merely mentions them.
 * The tests that guard against each are named for the behaviour they protect.
 *
 * <p>This is a pure unit test: no container, no Spring context, no database, no queue, no network
 * and no file system, and no elapsed-time or throughput assertion.
 */
@DisplayName("CobolStringUtils - faithful COBOL INSPECT primitives")
class CobolStringUtilsTest {

    /**
     * This test's own copy of the 26-character upper-case table, matching the literal declared at
     * {@code [app/cbl/COACTUPC.cbl:L588]} and re-declared inline at
     * {@code [app/cbl/COCRDUPC.cbl:L260]}. Duplicating it here rather than reading the production
     * constant is deliberate: it makes the expectation independent of the code under test, so a
     * corrupted table in either place is a failing test rather than a silent agreement.
     */
    private static final String UPPER_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * This test's own copy of the 26-character lower-case table
     * ({@code [app/cbl/COACTUPC.cbl:L590]}, {@code [app/cbl/COCRDUPC.cbl:L262]}). Positionally
     * paired with {@link #UPPER_TABLE}, which is exactly the pairing the upper fold applies.
     */
    private static final String LOWER_TABLE = "abcdefghijklmnopqrstuvwxyz";

    /**
     * This test's own copy of the 10-character digit table
     * ({@code [app/cbl/COACTUPC.cbl:L592]}). It is the tail of the 62-character alphanumeric table
     * and appears in no other role, since the numeric-only tables are never inspected.
     */
    private static final String DIGIT_TABLE = "0123456789";

    /** Number of characters in each of the two case tables; the tables must be equal-length. */
    private static final int CASE_TABLE_SIZE = 26;

    /** Number of characters in the digit table. */
    private static final int DIGIT_TABLE_SIZE = 10;

    /** Size of the alphabetic {@code FROM} table, {@code PIC X(52)}. */
    private static final int ALPHA_TABLE_SIZE = 52;

    /** Size of the alphanumeric {@code FROM} table, {@code PIC X(62)}. */
    private static final int ALPHANUMERIC_TABLE_SIZE = 62;

    /**
     * One past the highest ASCII code point. The membership sweeps walk {@code 0} through
     * {@code 127} so that every character the legacy single-byte field can hold is classified
     * exactly once, including the control codes.
     */
    private static final int ASCII_CODE_POINT_LIMIT = 128;

    /**
     * Width of the persisted embossed-name field, {@code CARD-EMBOSSED-NAME}, and of the work field
     * the card-update program edits, {@code CARD-NAME-CHECK PIC X(50)} at
     * {@code [app/cbl/COCRDUPC.cbl:L87]}. The fold is applied in place to a field of this width, so
     * a transformation that changed the length would corrupt the record.
     */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * The embossed name carried by row 0 of {@code [app/data/ASCII/carddata.txt]}, at byte offset
     * 31 of the 150-byte card record. It contains an embedded space, as do all 50 of that fixture's
     * 50 rows, which is why an alphabetic-only character-class predicate would reject every live
     * row.
     */
    private static final String FIXTURE_EMBOSSED_NAME = "Aniya Von";

    /** The same name folded, written out by hand rather than computed. */
    private static final String FIXTURE_EMBOSSED_NAME_FOLDED = "ANIYA VON";

    /** Width of the menu-option receiver, {@code PIC X(02)}, the only width in current use. */
    private static final int MENU_OPTION_WIDTH = 2;

    /**
     * LATIN SMALL LETTER SHARP S, U+00DF. Written as an escape so this source file stays pure ASCII
     * and cannot be altered by an encoding change; the compiler resolves the escape while reading
     * the file, so the assertion sees the character itself. This is the sharpest length test
     * available: the library upper-casing method expands it into two letters, while a
     * 26-character table substitution leaves it untouched.
     */
    private static final String SHARP_S = "\u00df";

    /** LATIN SMALL LETTER N WITH TILDE, U+00F1 &mdash; a letter outside the 52-character table. */
    private static final String N_TILDE = "\u00f1";

    /**
     * LATIN CAPITAL LETTER E WITH ACUTE, U+00C9 &mdash; already upper case, and still outside the
     * table, so it must neither pass the predicates nor be altered by the fold.
     */
    private static final String E_ACUTE = "\u00c9";

    /**
     * LATIN SMALL LETTER DOTLESS I, U+0131. The library upper-casing method maps it to the ASCII
     * capital I; the legacy table does not contain it, so the fold must leave it alone.
     */
    private static final String DOTLESS_I = "\u0131";

    /**
     * ARABIC-INDIC DIGIT THREE, U+0663 &mdash; a Unicode digit that is not one of the ten ASCII
     * digits in the table, so the alphanumeric predicate must reject it.
     */
    private static final String ARABIC_INDIC_DIGIT_THREE = "\u0663";

    /** FULLWIDTH DIGIT ONE, U+FF11 &mdash; likewise a Unicode digit outside the ASCII table. */
    private static final String FULLWIDTH_DIGIT_ONE = "\uff11";

    /**
     * The six punctuation characters used throughout as rejection cases. Each occurs in real
     * cardholder and address data, which is why rejecting them is a behaviour worth pinning: hyphen,
     * full stop, apostrophe, comma, solidus and number sign.
     */
    private static final String[] PUNCTUATION = {"-", ".", "'", ",", "/", "#"};

    /**
     * Builds a right-space-padded fixed-width image, the way a {@code MOVE} into a
     * {@code PIC X(n)} field leaves an alphanumeric field. This is test scaffolding over
     * {@link String#repeat(int)}; it is not a production padding helper and no such helper exists in
     * {@link CobolStringUtils}, because no legacy site asks for one.
     *
     * @param value the value to place at the left of the field
     * @param width the field width in character positions
     * @return {@code value} followed by enough spaces to reach {@code width}
     */
    private static String spacePaddedTo(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Independent classification of a single character as a member of the 52-character alphabetic
     * table or a space, derived from the ASCII code chart rather than from a table lookup.
     *
     * @param candidate the character to classify
     * @return {@code true} when the character is an ASCII letter or a space
     */
    private static boolean isTableAlphaOrSpace(final char candidate) {
        return (candidate >= 'A' && candidate <= 'Z')
                || (candidate >= 'a' && candidate <= 'z')
                || candidate == ' ';
    }

    /**
     * Independent classification of a single character as a member of the 62-character alphanumeric
     * table or a space, derived from the ASCII code chart rather than from a table lookup.
     *
     * @param candidate the character to classify
     * @return {@code true} when the character is an ASCII letter, an ASCII digit or a space
     */
    private static boolean isTableAlphaNumericOrSpace(final char candidate) {
        return isTableAlphaOrSpace(candidate) || (candidate >= '0' && candidate <= '9');
    }

    @Test
    @DisplayName("the hand-written oracle tables have the widths the legacy PIC clauses declare")
    void oracleTablesMatchTheDeclaredWidths() {
        // Guards this test's own evidence before it is used to judge the production code. The
        // legacy widths are PIC X(26), PIC X(26), PIC X(10), and the two concatenations PIC X(52)
        // and PIC X(62).
        assertThat(UPPER_TABLE).hasSize(CASE_TABLE_SIZE);
        assertThat(LOWER_TABLE).hasSize(CASE_TABLE_SIZE);
        assertThat(DIGIT_TABLE).hasSize(DIGIT_TABLE_SIZE);
        assertThat(CASE_TABLE_SIZE + CASE_TABLE_SIZE).isEqualTo(ALPHA_TABLE_SIZE);
        assertThat(ALPHA_TABLE_SIZE + DIGIT_TABLE_SIZE).isEqualTo(ALPHANUMERIC_TABLE_SIZE);
        // The two CONVERTING operands must be the same size, which is what makes the fold a
        // position-for-position substitution.
        assertThat(UPPER_TABLE.length()).isEqualTo(LOWER_TABLE.length());
    }

    @Test
    @DisplayName("the embossed-name fixture is 9 characters with an embedded space and pads to 50")
    void fixtureEmbossedNameMatchesTheRecordLayout() {
        // Pins the evidence the alphabetic parity trap rests on: the live value contains a space,
        // and the field it lives in is 50 positions wide.
        assertThat(FIXTURE_EMBOSSED_NAME).contains(" ");
        assertThat(FIXTURE_EMBOSSED_NAME_FOLDED).contains(" ");
        assertThat(FIXTURE_EMBOSSED_NAME).hasSameSizeAs(FIXTURE_EMBOSSED_NAME_FOLDED);
        assertThat(spacePaddedTo(FIXTURE_EMBOSSED_NAME, EMBOSSED_NAME_WIDTH))
                .hasSize(EMBOSSED_NAME_WIDTH);
    }

    /**
     * {@link CobolStringUtils#isAlphaOrSpace(String)} &mdash; the 52-character alphabetic edit.
     *
     * <p>Legacy sites, all three converting the alphabetic table to spaces and then asserting that
     * the trimmed remainder is empty: {@code [app/cbl/COACTUPC.cbl:L1927]} in paragraph
     * {@code 1225-EDIT-ALPHA-REQD} ({@code [app/cbl/COACTUPC.cbl:L1898]} to
     * {@code [app/cbl/COACTUPC.cbl:L1951]}), {@code [app/cbl/COACTUPC.cbl:L2033]} in paragraph
     * {@code 1235-EDIT-ALPHA-OPT} ({@code [app/cbl/COACTUPC.cbl:L2012]} to
     * {@code [app/cbl/COACTUPC.cbl:L2057]}), and {@code [app/cbl/COCRDUPC.cbl:L825]} in paragraph
     * {@code 1230-EDIT-NAME} ({@code [app/cbl/COCRDUPC.cbl:L806]} to
     * {@code [app/cbl/COCRDUPC.cbl:L841]}).
     *
     * <p><b>The mechanism, and why embedded spaces pass.</b> The legacy edit is not a
     * character-class test: it blanks every table character in place and then asks whether the
     * trimmed remainder has length zero. A character that was <em>already</em> a space survives the
     * conversion as a space and is trimmed away exactly as a blanked letter is, so the faithful
     * predicate is "every character is a letter <em>or a space</em>" &mdash; which is also what the
     * estate's own comments at those three sites state.
     */
    @Nested
    @DisplayName("isAlphaOrSpace - 52-character alphabetic table, spaces included")
    class IsAlphaOrSpace {

        @ParameterizedTest(name = "[{index}] \"{0}\" is accepted")
        @ValueSource(strings = {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "abcdefghijklmnopqrstuvwxyz",
            "MaryAnn",
            "Smith",
            "vON",
        })
        @DisplayName("accepts values built only from the 52 table characters, in any case")
        void acceptsLettersInAnyCase(final String value) {
            assertThat(CobolStringUtils.isAlphaOrSpace(value)).isTrue();
        }

        @Test
        @DisplayName("accepts an embedded space, so a two-part personal name is valid")
        void acceptsAnEmbeddedSpace() {
            // Derived from the mechanism, not from intuition: the space is left as a space by the
            // conversion and is then removed by the trim, so the remainder is empty and the edit
            // passes. Rejecting these would reject data the legacy system stores today.
            assertThat(CobolStringUtils.isAlphaOrSpace("MARY ANN")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace("Mary Ann")).isTrue();
        }

        @Test
        @DisplayName("accepts the live embossed name from row 0 of the card fixture")
        void acceptsTheLiveFixtureEmbossedName() {
            assertThat(CobolStringUtils.isAlphaOrSpace(FIXTURE_EMBOSSED_NAME)).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace(FIXTURE_EMBOSSED_NAME_FOLDED)).isTrue();
        }

        @Test
        @DisplayName("a character-class predicate over letters alone would be a regression, "
                + "because every one of the 50 live embossed names holds an embedded space")
        void aLetterOnlyCharacterClassPredicateWouldRejectLiveData() {
            // This is the guard test for the first parity trap. The forbidden implementation is a
            // stream over the characters asserting that each satisfies the Unicode letter-class
            // predicate of java.lang.Character. It would reject both values below - one a textbook
            // two-part name, the other the actual stored value in row 0 of the card fixture, in a
            // file whose every row contains an embedded space. Accepting them is the contract.
            assertThat(CobolStringUtils.isAlphaOrSpace("MARY ANN")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace(FIXTURE_EMBOSSED_NAME)).isTrue();
        }

        @Test
        @DisplayName("accepts leading, trailing and repeated interior spaces")
        void acceptsSpacesInEveryPosition() {
            assertThat(CobolStringUtils.isAlphaOrSpace(" LEADING")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace("TRAILING ")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace("TWO  INTERIOR  SPACES")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace(" SURROUNDED ")).isTrue();
        }

        @Test
        @DisplayName("accepts an empty value and an all-space value, because presence is the "
                + "caller's concern")
        void acceptsAbsenceOfContent() {
            // The legacy conversion is never reached for a blank field: the required variants
            // reject blank input beforehand and the optional variants accept it and exit early.
            // Whether a field must be supplied belongs to the account-update and card-update
            // services, so this predicate reports only on the character class.
            assertThat(CobolStringUtils.isAlphaOrSpace("")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace(" ")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace("  ")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace(" ".repeat(EMBOSSED_NAME_WIDTH))).isTrue();
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" is rejected because it holds a digit")
        @ValueSource(strings = {"1", "MARY1", "MARY ANN 2", "0", "9", "A1"})
        @DisplayName("rejects any ASCII digit, because digits are absent from the 52-character "
                + "table")
        void rejectsDigits(final String value) {
            assertThat(CobolStringUtils.isAlphaOrSpace(value)).isFalse();
        }

        @Test
        @DisplayName("rejects punctuation that occurs in real name and address data")
        void rejectsPunctuation() {
            for (final String mark : PUNCTUATION) {
                assertThat(CobolStringUtils.isAlphaOrSpace(mark))
                        .as("bare punctuation \"%s\"", mark)
                        .isFalse();
                assertThat(CobolStringUtils.isAlphaOrSpace("MARY" + mark + "ANN"))
                        .as("punctuation \"%s\" embedded in a name", mark)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("rejects a horizontal tab, which is whitespace but is not the space character")
        void rejectsTab() {
            // The table admits exactly one whitespace character, the space at ASCII 32. A tab
            // survives the conversion and survives the trim of a fixed alphanumeric field, so the
            // remainder is not empty and the edit fails.
            assertThat(CobolStringUtils.isAlphaOrSpace("\t")).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace("MARY\tANN")).isFalse();
        }

        @Test
        @DisplayName("rejects non-ASCII letters, proving membership is the strict 52-character "
                + "table and not a Unicode letter class")
        void rejectsNonAsciiLetters() {
            // A Unicode-aware letter test would accept every one of these. The legacy table
            // contains none of them, so each must fail.
            assertThat(CobolStringUtils.isAlphaOrSpace(E_ACUTE)).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace(N_TILDE)).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace(SHARP_S)).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace(DOTLESS_I)).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace("JOS" + E_ACUTE)).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace("MU" + N_TILDE + "OZ")).isFalse();
        }

        @Test
        @DisplayName("accepts each of the 52 table characters on its own")
        void acceptsEveryTableCharacterIndividually() {
            for (int position = 0; position < CASE_TABLE_SIZE; position++) {
                assertThat(CobolStringUtils.isAlphaOrSpace(
                                String.valueOf(UPPER_TABLE.charAt(position))))
                        .as("upper-case table position %d", position)
                        .isTrue();
                assertThat(CobolStringUtils.isAlphaOrSpace(
                                String.valueOf(LOWER_TABLE.charAt(position))))
                        .as("lower-case table position %d", position)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("classifies all 128 ASCII characters exactly as the table plus space does")
        void classifiesEveryAsciiCharacterAsTheTableDoes() {
            // The expectation comes from an explicit range test over the ASCII code chart, a
            // different mechanism from the table lookup the production code performs, so the two
            // cannot drift together. Control codes are swept too, because the legacy field is a
            // single-byte alphanumeric field that can hold them.
            for (int code = 0; code < ASCII_CODE_POINT_LIMIT; code++) {
                final char candidate = (char) code;
                assertThat(CobolStringUtils.isAlphaOrSpace(String.valueOf(candidate)))
                        .as("ASCII code point %d", code)
                        .isEqualTo(isTableAlphaOrSpace(candidate));
            }
        }

        @Test
        @DisplayName("rejects a value in which only one character offends")
        void rejectsAValueWithASingleOffendingCharacter() {
            // Confirms the edit is universally quantified: one non-table, non-space character is
            // enough to fail, wherever it sits.
            assertThat(CobolStringUtils.isAlphaOrSpace("1ARYANN")).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace("MARYANN1")).isFalse();
            assertThat(CobolStringUtils.isAlphaOrSpace("MARY1ANN")).isFalse();
        }

        @Test
        @DisplayName("rejects a null value, because an absent field is not a blank field")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolStringUtils.isAlphaOrSpace(null))
                    .withMessageStartingWith("value must not be null");
        }
    }

    /**
     * {@link CobolStringUtils#isAlphaNumericOrSpace(String)} &mdash; the 62-character alphanumeric
     * edit.
     *
     * <p>Legacy sites, identical in shape to the alphabetic edit but converting the wider table:
     * {@code [app/cbl/COACTUPC.cbl:L1985]} in paragraph {@code 1230-EDIT-ALPHANUM-REQD}
     * ({@code [app/cbl/COACTUPC.cbl:L1955]} to {@code [app/cbl/COACTUPC.cbl:L2009]}), and
     * {@code [app/cbl/COACTUPC.cbl:L2081]} in paragraph {@code 1240-EDIT-ALPHANUM-OPT}
     * ({@code [app/cbl/COACTUPC.cbl:L2061]} to {@code [app/cbl/COACTUPC.cbl:L2105]}).
     *
     * <p><b>Why this group exists at all.</b> Two of the five non-fold conversion sites convert the
     * 62-character table rather than the 52-character one, which the {@code FROM} operand states
     * outright. Routing them through the alphabetic predicate would reject digits the legacy system
     * accepts, which is a behavioural regression rather than a tidy-up.
     *
     * <p><b>Source anomaly 18 &mdash; the code governs, not the comment.</b> The comment at
     * {@code [app/cbl/COACTUPC.cbl:L2078]} claims the second site permits letters and spaces only,
     * while the statement immediately below it, at {@code [app/cbl/COACTUPC.cbl:L2079]} through
     * {@code [app/cbl/COACTUPC.cbl:L2082]}, moves the alphanumeric group into the 62-character
     * {@code FROM} field and converts against it. The comment is stale, exactly as the transposed
     * macro comments at {@code [app/cbl/COACTUPC.cbl:L3427]} through
     * {@code [app/cbl/COACTUPC.cbl:L3435]} are, so the alphanumeric semantic is correct and
     * {@link #digitsSeparateTheTwoPredicates()} pins it against a later reader who trusts the
     * comment.
     *
     * <p>Source anomaly 19, the misspelled validation-flag condition names at that site, is a
     * field-error-decoration and validation-exception concern, so nothing is asserted about it here.
     */
    @Nested
    @DisplayName("isAlphaNumericOrSpace - 62-character alphanumeric table, spaces included")
    class IsAlphaNumericOrSpace {

        @ParameterizedTest(name = "[{index}] \"{0}\" is accepted")
        @ValueSource(strings = {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "abcdefghijklmnopqrstuvwxyz",
            "0123456789",
            "A1 B2",
            "APT 4B",
            "PO BOX 12345",
            "Suite 200",
        })
        @DisplayName("accepts letters, digits and spaces in any combination")
        void acceptsLettersDigitsAndSpaces(final String value) {
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(value)).isTrue();
        }

        @Test
        @DisplayName("accepts each of the 62 table characters on its own")
        void acceptsEveryTableCharacterIndividually() {
            for (int position = 0; position < CASE_TABLE_SIZE; position++) {
                assertThat(CobolStringUtils.isAlphaNumericOrSpace(
                                String.valueOf(UPPER_TABLE.charAt(position))))
                        .as("upper-case table position %d", position)
                        .isTrue();
                assertThat(CobolStringUtils.isAlphaNumericOrSpace(
                                String.valueOf(LOWER_TABLE.charAt(position))))
                        .as("lower-case table position %d", position)
                        .isTrue();
            }
            for (int position = 0; position < DIGIT_TABLE_SIZE; position++) {
                assertThat(CobolStringUtils.isAlphaNumericOrSpace(
                                String.valueOf(DIGIT_TABLE.charAt(position))))
                        .as("digit table position %d", position)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("accepts an embedded space for the same reason the alphabetic edit does")
        void acceptsAnEmbeddedSpace() {
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("A1 B2")).isTrue();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(" 1 A ")).isTrue();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("TWO  INTERIOR  SPACES  2")).isTrue();
        }

        @Test
        @DisplayName("accepts an empty value and an all-space value")
        void acceptsAbsenceOfContent() {
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("")).isTrue();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(" ")).isTrue();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("  ")).isTrue();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(" ".repeat(EMBOSSED_NAME_WIDTH)))
                    .isTrue();
        }

        @Test
        @DisplayName("rejects punctuation, so the wider table is wider by exactly the ten digits")
        void rejectsPunctuation() {
            for (final String mark : PUNCTUATION) {
                assertThat(CobolStringUtils.isAlphaNumericOrSpace(mark))
                        .as("bare punctuation \"%s\"", mark)
                        .isFalse();
                assertThat(CobolStringUtils.isAlphaNumericOrSpace("APT" + mark + "4B"))
                        .as("punctuation \"%s\" embedded in an address line", mark)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("rejects a horizontal tab")
        void rejectsTab() {
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("\t")).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("APT\t4B")).isFalse();
        }

        @Test
        @DisplayName("rejects non-ASCII letters and non-ASCII digits, proving neither Unicode "
                + "character class was used")
        void rejectsNonAsciiLettersAndDigits() {
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(E_ACUTE)).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(N_TILDE)).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(SHARP_S)).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(DOTLESS_I)).isFalse();
            // Both of these are Unicode digits. The legacy table holds only the ten ASCII digits,
            // so a Unicode digit test would wrongly accept them.
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(ARABIC_INDIC_DIGIT_THREE)).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace(FULLWIDTH_DIGIT_ONE)).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("APT " + FULLWIDTH_DIGIT_ONE))
                    .isFalse();
        }

        @Test
        @DisplayName("classifies all 128 ASCII characters exactly as the table plus space does")
        void classifiesEveryAsciiCharacterAsTheTableDoes() {
            for (int code = 0; code < ASCII_CODE_POINT_LIMIT; code++) {
                final char candidate = (char) code;
                assertThat(CobolStringUtils.isAlphaNumericOrSpace(String.valueOf(candidate)))
                        .as("ASCII code point %d", code)
                        .isEqualTo(isTableAlphaNumericOrSpace(candidate));
            }
        }

        @Test
        @DisplayName("digits are the only difference between the two predicates, which is the "
                + "52-versus-62 distinction and the resolution of source anomaly 18")
        void digitsSeparateTheTwoPredicates() {
            // A digit-bearing value is rejected by the narrower edit and accepted by the wider one.
            // If a future change collapsed the two predicates into one - the mistake the stale
            // comment at the fourth conversion site invites - this assertion fails.
            assertThat(CobolStringUtils.isAlphaOrSpace("APT 4B")).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("APT 4B")).isTrue();

            // Every one of the ten digits behaves the same way, so the distinction is the whole
            // digit table and not one stray character.
            for (int position = 0; position < DIGIT_TABLE_SIZE; position++) {
                final String digit = String.valueOf(DIGIT_TABLE.charAt(position));
                assertThat(CobolStringUtils.isAlphaOrSpace(digit))
                        .as("digit \"%s\" must fail the alphabetic edit", digit)
                        .isFalse();
                assertThat(CobolStringUtils.isAlphaNumericOrSpace(digit))
                        .as("digit \"%s\" must pass the alphanumeric edit", digit)
                        .isTrue();
            }

            // Outside the digits the two agree, in both directions.
            assertThat(CobolStringUtils.isAlphaOrSpace("MARY ANN")).isTrue();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("MARY ANN")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace("MARY-ANN")).isFalse();
            assertThat(CobolStringUtils.isAlphaNumericOrSpace("MARY-ANN")).isFalse();
        }

        @Test
        @DisplayName("rejects a null value, because an absent field is not a blank field")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolStringUtils.isAlphaNumericOrSpace(null))
                    .withMessageStartingWith("value must not be null");
        }
    }

    /**
     * {@link CobolStringUtils#asciiUpperFold(String)} &mdash; the 26-character embossed-name upper
     * fold.
     *
     * <p>Legacy sites, both applying the fold in place to the persisted {@code PIC X(50)}
     * embossed-name field: {@code [app/cbl/COCRDUPC.cbl:L1357]}, immediately before the old-image
     * capture at {@code [app/cbl/COCRDUPC.cbl:L1360]}; and {@code [app/cbl/COCRDUPC.cbl:L1500]}, the
     * first statement of paragraph {@code 9300-CHECK-CHANGE-IN-REC} at
     * {@code [app/cbl/COCRDUPC.cbl:L1498]}, which runs before the before-and-after image comparison
     * at {@code [app/cbl/COCRDUPC.cbl:L1503]} through {@code [app/cbl/COCRDUPC.cbl:L1508]}.
     *
     * <p><b>The mechanism.</b> Each input character is looked up in the 26-character lower table and,
     * when found, replaced by the character at the same position of the 26-character upper table; a
     * character absent from the lower table is never touched. That is a position-for-position
     * substitution over a closed set, not a general case-mapping operation.
     *
     * <p><b>Why the library upper-casing method is forbidden.</b> Its no-argument form varies with
     * the ambient default locale, and <em>both</em> forms &mdash; including the explicit root-locale
     * overload &mdash; are Unicode-aware. Two consequences are fatal: they transform characters this
     * table leaves alone, and they can expand one character into several, changing the length of a
     * value that must occupy exactly 50 positions of a card record. The sharp-s case below is the
     * decisive demonstration &mdash; the library method turns it into two letters, the table leaves
     * it as one.
     */
    @Nested
    @DisplayName("asciiUpperFold - strict 26-character table substitution")
    class AsciiUpperFold {

        @Test
        @DisplayName("folds the whole lower-case alphabet onto the whole upper-case alphabet")
        void foldsTheWholeAlphabet() {
            // Both operands are literals written out by hand; nothing is computed from the code
            // under test.
            assertThat(CobolStringUtils.asciiUpperFold("abcdefghijklmnopqrstuvwxyz"))
                    .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        }

        @Test
        @DisplayName("folds each of the 26 characters onto the character at the same table position")
        void foldsEachCharacterPositionForPosition() {
            // This is the substitution rule stated directly: position n of the lower table maps to
            // position n of the upper table. The pairing comes from this test's own tables.
            for (int position = 0; position < CASE_TABLE_SIZE; position++) {
                final String source = String.valueOf(LOWER_TABLE.charAt(position));
                final String expected = String.valueOf(UPPER_TABLE.charAt(position));
                assertThat(CobolStringUtils.asciiUpperFold(source))
                        .as("table position %d", position)
                        .isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("leaves an already-upper-case value untouched")
        void leavesUpperCaseUntouched() {
            assertThat(CobolStringUtils.asciiUpperFold("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
                    .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
            assertThat(CobolStringUtils.asciiUpperFold(FIXTURE_EMBOSSED_NAME_FOLDED))
                    .isEqualTo(FIXTURE_EMBOSSED_NAME_FOLDED);
        }

        @Test
        @DisplayName("folds a mixed-case value one character at a time")
        void foldsMixedCase() {
            assertThat(CobolStringUtils.asciiUpperFold(FIXTURE_EMBOSSED_NAME))
                    .isEqualTo(FIXTURE_EMBOSSED_NAME_FOLDED);
            assertThat(CobolStringUtils.asciiUpperFold("Mary Ann")).isEqualTo("MARY ANN");
            assertThat(CobolStringUtils.asciiUpperFold("vON")).isEqualTo("VON");
        }

        @Test
        @DisplayName("leaves digits, spaces and punctuation untouched, because none is in the "
                + "26-character table")
        void leavesNonLetterAsciiUntouched() {
            assertThat(CobolStringUtils.asciiUpperFold("0123456789")).isEqualTo("0123456789");
            assertThat(CobolStringUtils.asciiUpperFold("   ")).isEqualTo("   ");
            assertThat(CobolStringUtils.asciiUpperFold("-.',/#")).isEqualTo("-.',/#");
            assertThat(CobolStringUtils.asciiUpperFold("apt 4b-2")).isEqualTo("APT 4B-2");
        }

        @Test
        @DisplayName("leaves every non-table ASCII character untouched while folding the 26")
        void foldsOnlyTheTableCharactersAcrossTheWholeAsciiRange() {
            // Expectation derived from the ASCII code chart: only the 26 lower-case letters change,
            // and each becomes the letter 32 code points below it. Everything else, control codes
            // included, is emitted as it arrived.
            for (int code = 0; code < ASCII_CODE_POINT_LIMIT; code++) {
                final char candidate = (char) code;
                final String source = String.valueOf(candidate);
                final String expected = candidate >= 'a' && candidate <= 'z'
                        ? String.valueOf(UPPER_TABLE.charAt(candidate - 'a'))
                        : source;
                assertThat(CobolStringUtils.asciiUpperFold(source))
                        .as("ASCII code point %d", code)
                        .isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("leaves non-ASCII characters untouched, including the sharp s that the "
                + "library method would expand into two letters")
        void leavesNonAsciiUntouched() {
            assertThat(CobolStringUtils.asciiUpperFold(SHARP_S)).isEqualTo(SHARP_S);
            assertThat(CobolStringUtils.asciiUpperFold(N_TILDE)).isEqualTo(N_TILDE);
            assertThat(CobolStringUtils.asciiUpperFold(E_ACUTE)).isEqualTo(E_ACUTE);
            assertThat(CobolStringUtils.asciiUpperFold(DOTLESS_I)).isEqualTo(DOTLESS_I);
        }

        @Test
        @DisplayName("a locale-aware, Unicode-aware case mapping is forbidden here: it alters "
                + "characters the table leaves alone and can change the length of a 50-byte field")
        void aUnicodeCaseMappingWouldBreakByteParity() {
            // Guard test for the second parity trap. The forbidden implementation is the library
            // case-mapping method on String, in either its ambient-locale form or its explicit
            // root-locale form. Each assertion below is a value on which that method's result
            // differs from the legacy table's result.
            //
            //   sharp s     - the library method yields two capital letters, growing the value by
            //                 one position and pushing the field past its 50-byte boundary
            //   dotless i   - the library method yields the ASCII capital I, a character the legacy
            //                 table never produces from this input
            //   n with tilde and e with acute - the library method upper-cases them; the table,
            //                 which contains neither, cannot
            assertThat(CobolStringUtils.asciiUpperFold(SHARP_S)).isEqualTo(SHARP_S);
            assertThat(CobolStringUtils.asciiUpperFold(SHARP_S)).hasSize(1);
            assertThat(CobolStringUtils.asciiUpperFold(DOTLESS_I)).isEqualTo(DOTLESS_I);
            assertThat(CobolStringUtils.asciiUpperFold(DOTLESS_I)).isNotEqualTo("I");
            assertThat(CobolStringUtils.asciiUpperFold(N_TILDE)).isEqualTo(N_TILDE);
            assertThat(CobolStringUtils.asciiUpperFold(E_ACUTE)).isEqualTo(E_ACUTE);

            // A realistic mixed value: the ASCII letters fold, the accented letter does not, and
            // the length is unchanged.
            final String mixed = "gro" + SHARP_S + " stra" + SHARP_S + "e";
            final String expected = "GRO" + SHARP_S + " STRA" + SHARP_S + "E";
            assertThat(CobolStringUtils.asciiUpperFold(mixed)).isEqualTo(expected);
            assertThat(CobolStringUtils.asciiUpperFold(mixed)).hasSameSizeAs(mixed);
        }

        @Test
        @DisplayName("preserves the character count exactly, for ASCII and non-ASCII input alike")
        void preservesLengthExactly() {
            final String[] samples = {
                "",
                "a",
                "abcdefghijklmnopqrstuvwxyz",
                FIXTURE_EMBOSSED_NAME,
                "apt 4b-2",
                SHARP_S,
                "gro" + SHARP_S,
                N_TILDE + E_ACUTE + DOTLESS_I,
                spacePaddedTo(FIXTURE_EMBOSSED_NAME, EMBOSSED_NAME_WIDTH),
            };
            for (final String sample : samples) {
                assertThat(CobolStringUtils.asciiUpperFold(sample))
                        .as("length of the fold of \"%s\"", sample)
                        .hasSameSizeAs(sample);
            }
        }

        @Test
        @DisplayName("folds a 50-position embossed-name image to exactly 50 bytes with its "
                + "trailing spaces intact")
        void foldsTheEmbossedNameFieldAtItsRealWidth() {
            // The real field width. Both the input and the expected output are built by hand at 50
            // positions; nothing is trimmed on either side of the comparison, because a fixed-width
            // image is only equal when its padding is equal too.
            final String image = spacePaddedTo(FIXTURE_EMBOSSED_NAME, EMBOSSED_NAME_WIDTH);
            final String expected =
                    spacePaddedTo(FIXTURE_EMBOSSED_NAME_FOLDED, EMBOSSED_NAME_WIDTH);
            assertThat(image).hasSize(EMBOSSED_NAME_WIDTH);

            final String folded = CobolStringUtils.asciiUpperFold(image);

            assertThat(folded).isEqualTo(expected);
            assertThat(folded).hasSize(EMBOSSED_NAME_WIDTH);
            assertThat(folded.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EMBOSSED_NAME_WIDTH);
            assertThat(folded).startsWith(FIXTURE_EMBOSSED_NAME_FOLDED);
            assertThat(folded).endsWith(" ");
            assertThat(folded.charAt(EMBOSSED_NAME_WIDTH - 1)).isEqualTo(' ');
        }

        @Test
        @DisplayName("returns the empty value for empty input")
        void returnsEmptyForEmptyInput() {
            assertThat(CobolStringUtils.asciiUpperFold("")).isEmpty();
        }

        @Test
        @DisplayName("rejects a null value, because an absent field is not a blank field")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolStringUtils.asciiUpperFold(null))
                    .withMessageStartingWith("value must not be null");
        }
    }

    /**
     * {@link CobolStringUtils#rightJustifyZeroFill(String, int)} &mdash; the menu-option
     * normalisation, step three of a four-step legacy idiom.
     *
     * <p>The receiving field is declared right-justified and two positions wide at
     * {@code [app/cbl/COADM01C.cbl:L45]} and {@code [app/cbl/COMEN01C.cbl:L45]}, and the numeric
     * field it feeds is two digits wide at {@code [app/cbl/COADM01C.cbl:L46]} and
     * {@code [app/cbl/COMEN01C.cbl:L46]}. Inside paragraph {@code PROCESS-ENTER-KEY}
     * ({@code [app/cbl/COADM01C.cbl:L115]}, {@code [app/cbl/COMEN01C.cbl:L115]}) the two programs
     * are line-for-line identical:
     * <ol>
     *   <li>a backward scan finds the last non-space position, flooring the index at one
     *       ({@code [app/cbl/COADM01C.cbl:L117]} to {@code [app/cbl/COADM01C.cbl:L121]});</li>
     *   <li>that prefix is moved into the right-justified receiver
     *       ({@code [app/cbl/COADM01C.cbl:L122]});</li>
     *   <li><b>this method</b> &mdash; every space in the receiver becomes a zero
     *       ({@code [app/cbl/COADM01C.cbl:L123]}, {@code [app/cbl/COMEN01C.cbl:L123]});</li>
     *   <li>the receiver is moved into the numeric option field
     *       ({@code [app/cbl/COADM01C.cbl:L124]}).</li>
     * </ol>
     *
     * <p>Steps one, two and four belong to the menu service, and so does everything the normalised
     * value is then judged against: the non-numeric test, the upper bound against the option count,
     * the rejection of a zero option, and the operator message at
     * {@code [app/cbl/COADM01C.cbl:L131]}. This method has no opinion about whether {@code "00"} is
     * a usable option &mdash; it only produces it.
     *
     * <p><b>The counter-intuitive part.</b> A right-justified receiver keeps the sender's
     * <em>rightmost</em> characters, so a sender longer than the receiver loses its <em>leading</em>
     * excess rather than its trailing excess. A right-truncating implementation would look more
     * natural and would silently diverge, so the left truncation is pinned by
     * {@link #truncatesOnTheLeftNotTheRight()}.
     */
    @Nested
    @DisplayName("rightJustifyZeroFill - right-justify into a fixed field, then zero-fill spaces")
    class RightJustifyZeroFill {

        /**
         * The normalisation table, hand-derived one row at a time. The middle column records the
         * intermediate right-justified receiver so the derivation is auditable: the result is that
         * receiver with every space turned into a zero.
         *
         * <pre>
         *   value   width   right-justified   result
         *   "4"       2         " 4"           "04"
         *   "5"       2         " 5"           "05"
         *   "12"      2         "12"           "12"
         *   ""        2         "  "           "00"
         *   " "       2         "  "           "00"
         *   "  "      2         "  "           "00"
         * </pre>
         *
         * @return one argument triple per table row
         */
        static Stream<Arguments> menuOptionNormalisationTable() {
            return Stream.of(
                    Arguments.of("4", MENU_OPTION_WIDTH, "04"),
                    Arguments.of("5", MENU_OPTION_WIDTH, "05"),
                    Arguments.of("12", MENU_OPTION_WIDTH, "12"),
                    Arguments.of("", MENU_OPTION_WIDTH, "00"),
                    Arguments.of(" ", MENU_OPTION_WIDTH, "00"),
                    Arguments.of("  ", MENU_OPTION_WIDTH, "00"));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" at width {1} becomes \"{2}\"")
        @MethodSource("menuOptionNormalisationTable")
        @DisplayName("normalises every row of the hand-derived menu-option table")
        void normalisesTheMenuOptionTable(
                final String value, final int width, final String expected) {
            assertThat(CobolStringUtils.rightJustifyZeroFill(value, width)).isEqualTo(expected);
        }

        @Test
        @DisplayName("pads a single significant character on the left, so option 4 becomes 04")
        void padsASingleCharacterOnTheLeft() {
            // Spelled out separately from the table because this is the behaviour the whole idiom
            // exists for: a one-key entry has to reach the two-digit numeric field as 0n.
            assertThat(CobolStringUtils.rightJustifyZeroFill("4", MENU_OPTION_WIDTH))
                    .isEqualTo("04");
            assertThat(CobolStringUtils.rightJustifyZeroFill("5", MENU_OPTION_WIDTH))
                    .isEqualTo("05");
            assertThat(CobolStringUtils.rightJustifyZeroFill("9", MENU_OPTION_WIDTH))
                    .isEqualTo("09");
        }

        @Test
        @DisplayName("leaves a value that exactly fills the field unchanged")
        void leavesAnExactFitUnchanged() {
            assertThat(CobolStringUtils.rightJustifyZeroFill("12", MENU_OPTION_WIDTH))
                    .isEqualTo("12");
            assertThat(CobolStringUtils.rightJustifyZeroFill("10", MENU_OPTION_WIDTH))
                    .isEqualTo("10");
        }

        @Test
        @DisplayName("turns an empty or all-space value into all zeros")
        void turnsAbsenceIntoZeros() {
            // The backward scan in the caller floors its index at one, so a wholly blank entry
            // arrives here as a blank of length one rather than as an empty value. Both shapes are
            // covered, and both normalise to the same all-zero receiver.
            assertThat(CobolStringUtils.rightJustifyZeroFill("", MENU_OPTION_WIDTH))
                    .isEqualTo("00");
            assertThat(CobolStringUtils.rightJustifyZeroFill(" ", MENU_OPTION_WIDTH))
                    .isEqualTo("00");
            assertThat(CobolStringUtils.rightJustifyZeroFill("  ", MENU_OPTION_WIDTH))
                    .isEqualTo("00");
        }

        @Test
        @DisplayName("truncates on the LEFT, not the right, keeping the rightmost characters")
        void truncatesOnTheLeftNotTheRight() {
            // Hand-derived: a right-justified receiver two positions wide, fed a three-character
            // sender, retains the last two characters and discards the first. Truncating on the
            // right would yield "12" here and would be wrong.
            assertThat(CobolStringUtils.rightJustifyZeroFill("123", MENU_OPTION_WIDTH))
                    .isEqualTo("23");
            assertThat(CobolStringUtils.rightJustifyZeroFill("123", MENU_OPTION_WIDTH))
                    .isNotEqualTo("12");
            assertThat(CobolStringUtils.rightJustifyZeroFill("987654", MENU_OPTION_WIDTH))
                    .isEqualTo("54");
            assertThat(CobolStringUtils.rightJustifyZeroFill("12345", 3)).isEqualTo("345");
            assertThat(CobolStringUtils.rightJustifyZeroFill("42", 1)).isEqualTo("2");
        }

        @Test
        @DisplayName("replaces EVERY space, including an interior or trailing one from the sender")
        void replacesEverySpaceNotOnlyTheLeftFill() {
            // Hand-derived, receiver by receiver:
            //   "1 "  at width 2 -> receiver "1 "  -> "10"
            //   "1 2" at width 3 -> receiver "1 2" -> "102"
            //   " 1 " at width 3 -> receiver " 1 " -> "010"
            //   "1 2" at width 5 -> receiver "  1 2" -> "00102"
            assertThat(CobolStringUtils.rightJustifyZeroFill("1 ", MENU_OPTION_WIDTH))
                    .isEqualTo("10");
            assertThat(CobolStringUtils.rightJustifyZeroFill("1 2", 3)).isEqualTo("102");
            assertThat(CobolStringUtils.rightJustifyZeroFill(" 1 ", 3)).isEqualTo("010");
            assertThat(CobolStringUtils.rightJustifyZeroFill("1 2", 5)).isEqualTo("00102");
            // No space can survive the replacement, whatever the input.
            assertThat(CobolStringUtils.rightJustifyZeroFill(" 1 ", 4)).doesNotContain(" ");
        }

        @Test
        @DisplayName("works at width 1")
        void worksAtWidthOne() {
            assertThat(CobolStringUtils.rightJustifyZeroFill("7", 1)).isEqualTo("7");
            assertThat(CobolStringUtils.rightJustifyZeroFill("", 1)).isEqualTo("0");
            assertThat(CobolStringUtils.rightJustifyZeroFill(" ", 1)).isEqualTo("0");
        }

        @Test
        @DisplayName("works at a width wider than the current caller uses")
        void worksAtAWiderWidth() {
            // Hand-derived at width 5: "42" retains two positions, three of left fill become zeros.
            assertThat(CobolStringUtils.rightJustifyZeroFill("42", 5)).isEqualTo("00042");
            assertThat(CobolStringUtils.rightJustifyZeroFill("", 5)).isEqualTo("00000");
            assertThat(CobolStringUtils.rightJustifyZeroFill("     ", 5)).isEqualTo("00000");
            assertThat(CobolStringUtils.rightJustifyZeroFill("12345", 5)).isEqualTo("12345");
        }

        @Test
        @DisplayName("preserves non-digit characters, because the legacy field imposes no "
                + "character restriction of its own")
        void preservesNonDigitCharacters() {
            // The receiver is an alphanumeric field, and the numeric test the caller applies
            // afterwards is what rejects a non-numeric entry. Filtering here would be a behaviour
            // the legacy program does not have, and it would hide the very input the caller must
            // reject.
            assertThat(CobolStringUtils.rightJustifyZeroFill("A", MENU_OPTION_WIDTH))
                    .isEqualTo("0A");
            assertThat(CobolStringUtils.rightJustifyZeroFill("AB", MENU_OPTION_WIDTH))
                    .isEqualTo("AB");
            assertThat(CobolStringUtils.rightJustifyZeroFill("-1", MENU_OPTION_WIDTH))
                    .isEqualTo("-1");
        }

        @Test
        @DisplayName("always returns exactly the requested number of positions, in characters and "
                + "in encoded bytes")
        void alwaysReturnsTheRequestedWidth() {
            final String[] senders = {"", " ", "4", "12", "123", "1 2", "     ", "987654"};
            final int[] widths = {1, MENU_OPTION_WIDTH, 3, 5};
            for (final int width : widths) {
                for (final String sender : senders) {
                    final String receiver = CobolStringUtils.rightJustifyZeroFill(sender, width);
                    assertThat(receiver)
                            .as("\"%s\" at width %d", sender, width)
                            .hasSize(width);
                    // Every sender above is ASCII, so a character position and an encoded byte are
                    // the same thing here - which is the property that lets the value be written
                    // into a single-byte fixed field without a length surprise.
                    assertThat(receiver.getBytes(StandardCharsets.US_ASCII))
                            .as("encoded byte count of \"%s\" at width %d", sender, width)
                            .hasSize(width);
                }
            }
        }

        @Test
        @DisplayName("rejects a width of zero, naming the offending parameter")
        void rejectsZeroWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolStringUtils.rightJustifyZeroFill("4", 0))
                    .withMessageStartingWith("width")
                    .withMessageContaining("0");
        }

        @Test
        @DisplayName("rejects a negative width, naming the offending parameter")
        void rejectsNegativeWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolStringUtils.rightJustifyZeroFill("4", -1))
                    .withMessageStartingWith("width")
                    .withMessageContaining("-1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolStringUtils.rightJustifyZeroFill("", -128))
                    .withMessageStartingWith("width");
        }

        @Test
        @DisplayName("rejects a null value, because an absent field is not a blank field")
        void rejectsNull() {
            // Asserted at a valid width, so the outcome cannot depend on which of the two argument
            // checks the implementation happens to perform first.
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolStringUtils.rightJustifyZeroFill(null,
                            MENU_OPTION_WIDTH))
                    .withMessageStartingWith("value must not be null");
        }
    }
}
