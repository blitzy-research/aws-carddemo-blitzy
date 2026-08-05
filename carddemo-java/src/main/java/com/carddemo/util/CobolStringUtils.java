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

import java.util.Objects;

/**
 * Faithful Java equivalents of the COBOL {@code INSPECT} primitives exercised by the CardDemo estate,
 * and the single translation point for construct-mapping row 9 of the migration requirement -
 * <em>STRING / UNSTRING / INSPECT to String utility methods, with delimiter behaviour, pointer
 * semantics and tallying logic identical</em>.
 *
 * <p><strong>Verified {@code INSPECT} census.</strong> A mechanical scan of every non-comment line
 * under {@code app/cbl/} and {@code app/cpy/} finds exactly <b>11</b> {@code INSPECT} statements and
 * <b>zero</b> {@code UNSTRING} statements, with no {@code INSPECT} anywhere in {@code app/cpy/}. The 11
 * decompose as <b>7 {@code CONVERTING}</b> plus <b>3 {@code REPLACING}</b> plus <b>1
 * {@code TALLYING}</b>. Reading the {@code FROM} table at each {@code CONVERTING} site gives the
 * breakdown <b>3 alphabetic + 2 alphanumeric + 2 upper-fold</b>, which is why <b>four</b>
 * {@code INSPECT} primitives exist here rather than three: covering the two alphanumeric sites with an
 * alphabetic-only predicate would reject digits the legacy system accepts. Two further textual matches at
 * {@code [app/cbl/COACTUPC.cbl:L584]} and {@code [app/cbl/COACTUPC.cbl:L605]} are comment banners, not
 * statements.
 *
 * <p><strong>Site-to-primitive mapping.</strong> All 11 sites are accounted for and each of the eight
 * owned here maps to exactly one primitive: {@link #isAlphaOrSpace(String)} covers
 * {@code [app/cbl/COACTUPC.cbl:L1927]}, {@code [app/cbl/COACTUPC.cbl:L2033]} and
 * {@code [app/cbl/COCRDUPC.cbl:L825]}; {@link #isAlphaNumericOrSpace(String)} covers
 * {@code [app/cbl/COACTUPC.cbl:L1985]} and {@code [app/cbl/COACTUPC.cbl:L2081]};
 * {@link #asciiUpperFold(String)} covers {@code [app/cbl/COCRDUPC.cbl:L1357]} and
 * {@code [app/cbl/COCRDUPC.cbl:L1500]}; {@link #rightJustifyZeroFill(String, int)} covers
 * {@code [app/cbl/COADM01C.cbl:L123]} and {@code [app/cbl/COMEN01C.cbl:L123]}.
 *
 * <p><strong>A second family: the signed-amount screen lexeme.</strong> Three further primitives -
 * {@link #isUnsuppliedNumericLexeme(String)}, {@link #isNumericLexeme(String)} and
 * {@link #plainDecimalOfNumericLexeme(String)} - translate paragraph {@code 1250-EDIT-SIGNED-9V2} at
 * {@code [app/cbl/COACTUPC.cbl:L2180-L2220]}, the estate's only edit for a signed amount typed at a
 * terminal. It is invoked five times, once per monetary field of the account-update screen
 * ({@code [app/cbl/COACTUPC.cbl:L1485]}, 1498, 1510, 1517 and 1524), and it produces <b>three</b>
 * mutually exclusive states rather than the pass-or-fail a Java validator would produce: BLANK when the
 * field was not supplied, NOT-OK when what was supplied is not a number, and VALID otherwise, with two
 * different operator messages for the two failures. Those three states are why the request contract
 * carries the 15-character lexeme rather than a decoded number - a number cannot represent the middle
 * state, and a body that failed to bind could not report it. Decision log entry DL-078 records the
 * contract change and the grammar these three primitives accept.
 *
 * <p>They belong here rather than with the record codec because both questions they answer are
 * questions about characters - is this field blank, and is this text a well-formed number - and neither
 * chooses a scale or performs arithmetic. The conversion stops at a plain decimal <em>string</em> for
 * the same reason: turning that string into a value at the estate's mandatory scale and truncation is
 * the codec's job, and {@code ZonedDecimalCodec.fromNumericLexeme(String)} is the single caller that
 * does it. The COBOL functions being translated, {@code TEST-NUMVAL-C} and {@code NUMVAL-C}, are
 * intrinsic functions over an alphanumeric argument, so a string-handling home is the faithful one.
 *
 * <p>Two sites are <em>deliberately absent</em>: the {@code TALLYING} site at
 * {@code [app/cbl/COCRDLIC.cbl:L1079]} and the selection-bitmap {@code REPLACING} site at
 * {@code [app/cbl/COCRDLIC.cbl:L1090]}, both inside paragraph {@code 2250-EDIT-ARRAY} at
 * {@code [app/cbl/COCRDLIC.cbl:L1073]}. Both carry screen-page knowledge - the seven-row card-list
 * page, whose {@code PIC X(7)} flag fields sit at {@code [app/cbl/COCRDLIC.cbl:L72]} and
 * {@code [app/cbl/COCRDLIC.cbl:L83]} and whose loop bound is at
 * {@code [app/cbl/COCRDLIC.cbl:L1099]} - and the error they raise names the offending row indices, so
 * they belong to the card-list service. This class therefore exposes <b>no</b> tally helper and
 * <b>no</b> selection-bitmap helper.
 *
 * <p><strong>The character tables.</strong> The legacy tables are strict ASCII, declared as literal
 * {@code PIC X(n)} values at {@code [app/cbl/COACTUPC.cbl:L586-L593]} and re-declared inline at
 * {@code [app/cbl/COCRDUPC.cbl:L255-L263]}: an upper table of 26 characters, a lower table of 26, a
 * digit table of 10, their alphabetic concatenation of 52 and their alphanumeric concatenation of 62.
 * The receiving tables are all-spaces fields of matching width at
 * {@code [app/cbl/COACTUPC.cbl:L607-L612]}. The two programs differ only in field naming - the
 * card-update program names its all-spaces receiver {@code LIT-ALL-SPACES-TO} where the account-update
 * program names it {@code LIT-ALPHA-SPACES-TO} - and the tables themselves are identical. A
 * numeric-only pair of tables is <em>declared</em> at {@code [app/cbl/COACTUPC.cbl:L609]} and
 * {@code [app/cbl/COACTUPC.cbl:L612]} but appears in <b>no</b> {@code INSPECT} statement anywhere in
 * the estate, so <b>no numeric-only primitive exists here</b> - the same treatment decision D-22
 * applies to status codes that are named outside the source but compared nowhere in it.
 *
 * <p><strong>Parity trap one - embedded spaces pass the alphabetic check</strong> (decision D-17). The
 * legacy alphabetic test is not a character-class predicate: it blanks every table character in place
 * and then asserts the trimmed remainder has length zero. Because a character that was <em>already</em>
 * a space also survives as a space and is likewise trimmed away, <b>embedded spaces pass</b>. The
 * faithful predicate is therefore "every character is a letter <em>or a space</em>", and the estate's
 * own comments say as much at {@code [app/cbl/COACTUPC.cbl:L1924]},
 * {@code [app/cbl/COACTUPC.cbl:L2030]} and {@code [app/cbl/COCRDUPC.cbl:L822]}. Writing
 * {@code chars().allMatch(Character::isLetter)} instead is <b>forbidden</b>: it would reject
 * {@code "MARY ANN"}, which the legacy system accepts. This is not hypothetical - the embossed-name
 * field is {@code PIC X(50)} at byte offset 31 of the 150-byte card record
 * ({@code [app/cpy/CVACT02Y.cpy]}), row 0 of {@code [app/data/ASCII/carddata.txt]} carries the value
 * {@code "Aniya Von"}, and <b>all 50 of the 50 rows</b> in that fixture carry an embedded space, so an
 * alphabetic-only predicate would reject every single live row.
 *
 * <p><strong>Parity trap two - the upper fold is a table, not a locale operation</strong> (decision
 * D-18). {@link String#toUpperCase()} is <b>forbidden</b> here, and so is its
 * {@code java.util.Locale#ROOT} overload {@link String#toUpperCase(java.util.Locale)}: the no-argument
 * form is locale-sensitive and <em>both</em> forms are Unicode-aware, transforming characters the
 * 26-character legacy table leaves untouched and potentially changing the length of the result - which
 * matters because the folded value is written back into a fixed 50-byte field. See
 * {@link #asciiUpperFold(String)}. The menu-option lexeme handled by
 * {@link #rightJustifyZeroFill(String, int)} is decision D-19.
 *
 * <p><strong>Two source anomalies.</strong> Anomaly 18: the comment at
 * {@code [app/cbl/COACTUPC.cbl:L2078]} reads "Only Alphabets and space allowed" while the statement
 * immediately below it at {@code [app/cbl/COACTUPC.cbl:L2079-L2082]} converts with the
 * <b>62-character alphanumeric</b> table. The code governs and the comment is stale, so the site is
 * covered by {@link #isAlphaNumericOrSpace(String)}. Anomaly 19: the validation flag condition names in
 * the account-update program are misspelled {@code ALPHNANUM}, for instance at
 * {@code [app/cbl/COACTUPC.cbl:L1995]}, {@code [app/cbl/COACTUPC.cbl:L2063]},
 * {@code [app/cbl/COACTUPC.cbl:L2072]} and {@code [app/cbl/COACTUPC.cbl:L2091]}; flag state is a
 * field-error-decoration and validation-exception concern and nothing is generated for it here.
 *
 * <p><strong>Out of scope by design.</strong> No date parsing or formatting - that is the date
 * validation service, whose cascade lives in {@code [app/cpy/CSUTLDPY.cpy]}; no {@code STRING}
 * concatenation helper, because the estate's {@code STRING} statements assemble operator messages and
 * are owned by the services that emit them; and no tokeniser of any kind, because the estate contains
 * <b>zero</b> {@code UNSTRING} statements and inventing one would be feature expansion. The work fields
 * backing the account-update edit paragraphs come from {@code [app/cpy/CSUTLDWY.cpy]}, which that
 * program alone includes with quoted syntax at {@code [app/cbl/COACTUPC.cbl:L166]}; that copybook
 * declares data rather than behaviour, so nothing here derives from it.
 *
 * <p><strong>Contract notes.</strong> Every method is pure - no input or output, no clock, no
 * environment, no randomness, no logging and no mutable state - so the class is inherently thread-safe
 * and each primitive is separately testable. A {@code null} argument raises
 * {@link NullPointerException} by way of {@link Objects#requireNonNull(Object, String)} rather than
 * being silently coerced to the empty string: a silent default would let a missing screen field
 * masquerade as a valid blank one, and the legacy programs distinguish those two states. The single
 * documented exception is {@link #isUnsuppliedNumericLexeme(String)}, whose whole subject is the
 * not-supplied state that {@code LOW-VALUES} represents, so for that one predicate an absent field and
 * a blank field genuinely are the same thing and {@code null} is an accepted value. Empty and
 * all-space input, by contrast, is a legitimate value that the predicates accept - deciding whether a
 * field <em>must</em> be supplied is the caller's job, never this class's. Every width, byte offset,
 * field length, statement count and fixture row count quoted in this file is factual layout evidence
 * drawn from the legacy record definitions and sample data, not a service level or tuning figure.
 */
public final class CobolStringUtils {

    /**
     * The 26 upper-case ASCII letters, in the order the legacy table declares them
     * ({@code [app/cbl/COACTUPC.cbl:L588]}, {@code [app/cbl/COCRDUPC.cbl:L260]}). This is the
     * {@code TO} table of the upper fold.
     */
    private static final String ASCII_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * The 26 lower-case ASCII letters, in the order the legacy table declares them
     * ({@code [app/cbl/COACTUPC.cbl:L590]}, {@code [app/cbl/COCRDUPC.cbl:L262]}). This is the
     * {@code FROM} table of the upper fold.
     */
    private static final String ASCII_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /**
     * The 10 ASCII digits ({@code [app/cbl/COACTUPC.cbl:L592]}). Present only as the tail of the
     * alphanumeric table; the numeric-only tables that the source also declares are never
     * inspected, so no digit-only primitive is exposed.
     */
    private static final String ASCII_DIGITS = "0123456789";

    /**
     * The 52-character alphabetic {@code FROM} table &mdash; upper followed by lower, matching the
     * group declared at {@code [app/cbl/COACTUPC.cbl:L587]} and spelled out inline at
     * {@code [app/cbl/COCRDUPC.cbl:L257]}.
     */
    private static final String ALPHA_TABLE = ASCII_UPPER + ASCII_LOWER;

    /**
     * The 62-character alphanumeric {@code FROM} table &mdash; the 52 alphabetic characters
     * followed by the 10 digits, matching the group declared at
     * {@code [app/cbl/COACTUPC.cbl:L586]}.
     */
    private static final String ALPHANUMERIC_TABLE = ALPHA_TABLE + ASCII_DIGITS;

    /** The space character, which every predicate here admits and the zero fill replaces. */
    private static final char SPACE = ' ';

    /**
     * The replacement character of the menu-option normalisation
     * ({@code [app/cbl/COADM01C.cbl:L123]}, {@code [app/cbl/COMEN01C.cbl:L123]}).
     */
    private static final char ZERO_FILL = '0';

    /**
     * The character the error-decoration macro {@code app/cpy/CSSETATY.cpy} writes into a screen field
     * whose validation flag was BLANK, seen at {@code [app/cpy/CSSETATY.cpy:L18-L27]}. It arrives back
     * on the next turn in the field it decorated, and the map-to-working-storage move at
     * {@code [app/cbl/COACTUPC.cbl:L1073]} tests for it explicitly, so it is a marker rather than data.
     */
    private static final char DECORATION_MARKER = '*';

    /**
     * The COBOL currency sign. No {@code SPECIAL-NAMES} paragraph and therefore no {@code CURRENCY
     * SIGN} clause exists anywhere under {@code app/cbl/} or {@code app/cpy/}, so the language default
     * is in force estate-wide.
     */
    private static final char CURRENCY_SIGN = '$';

    /**
     * The COBOL decimal point. {@code DECIMAL-POINT IS COMMA} appears nowhere in the estate, so the
     * language default is in force and the comma keeps its digit-separator role.
     */
    private static final char DECIMAL_POINT = '.';

    /** The digit separator permitted inside the integer part of a {@code NUMVAL-C} argument. */
    private static final char DIGIT_SEPARATOR = ',';

    /** Leading or trailing plus sign of a {@code NUMVAL-C} argument. */
    private static final char PLUS_SIGN = '+';

    /** Leading or trailing minus sign of a {@code NUMVAL-C} argument. */
    private static final char MINUS_SIGN = '-';

    /** Not instantiable: this is a stateless collection of pure primitives. */
    private CobolStringUtils() {
        // No instance state exists, so no instance is ever required.
    }

    /**
     * Reproduces the legacy alphabetic edit: <b>every character must be an ASCII letter or a
     * space</b>.
     *
     * <p>Legacy call sites, all of the form "convert the 52-character alphabetic table to spaces, then
     * assert the trimmed remainder is empty": {@code [app/cbl/COACTUPC.cbl:L1927]} in paragraph
     * {@code 1225-EDIT-ALPHA-REQD} ({@code [app/cbl/COACTUPC.cbl:L1898-L1951]}),
     * {@code [app/cbl/COACTUPC.cbl:L2033]} in paragraph {@code 1235-EDIT-ALPHA-OPT}
     * ({@code [app/cbl/COACTUPC.cbl:L2012-L2057]}), and {@code [app/cbl/COCRDUPC.cbl:L825]} in
     * paragraph {@code 1230-EDIT-NAME} ({@code [app/cbl/COCRDUPC.cbl:L806-L841]}).
     *
     * <p><b>Embedded spaces pass, and that is deliberate</b> (decision D-17). The legacy idiom blanks
     * the table characters and then trims, so a character that was already a space is trimmed away
     * exactly as a blanked letter is. {@code "MARY ANN"} is therefore accepted, and so is
     * {@code "Aniya Von"} - the embossed name in row 0 of {@code [app/data/ASCII/carddata.txt]}, a
     * fixture in which all 50 of the 50 rows contain an embedded space. Implementing this as
     * {@code chars().allMatch(Character::isLetter)} is forbidden: it would reject data the legacy
     * system stores today.
     *
     * <p><b>Membership is strict ASCII.</b> {@link Character#isLetter(char)} is Unicode-aware and would
     * admit letters the 52-character legacy table does not contain, so it is not used. A character
     * outside {@code A}-{@code Z}, {@code a}-{@code z} and the space - including any accented letter,
     * any non-Latin letter and either half of a surrogate pair - fails.
     *
     * <p><b>Caller boundary.</b> This is the character-class predicate only. Empty and all-space input
     * pass, because the legacy {@code INSPECT} is never reached for a blank field: the required
     * variants reject blank input beforehand ({@code [app/cbl/COACTUPC.cbl:L1903-L1922]}) while the
     * optional variants accept it and exit early ({@code [app/cbl/COACTUPC.cbl:L2017-L2028]}). Deciding
     * whether a field must be supplied, and composing the failure message, belongs to the
     * account-update and card-update services.
     *
     * @param value the candidate field value; must not be {@code null}
     * @return {@code true} when every character is an ASCII letter or a space, {@code false}
     *         otherwise
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static boolean isAlphaOrSpace(final String value) {
        Objects.requireNonNull(value, "value must not be null: an absent field is not a blank field");
        return allInTableOrSpace(value, ALPHA_TABLE);
    }

    /**
     * Reproduces the legacy alphanumeric edit: <b>every character must be an ASCII letter, an ASCII
     * digit or a space</b>.
     *
     * <p>Legacy call sites, identical in shape to the alphabetic edit but converting the
     * 62-character table: {@code [app/cbl/COACTUPC.cbl:L1985]} in paragraph
     * {@code 1230-EDIT-ALPHANUM-REQD} ({@code [app/cbl/COACTUPC.cbl:L1955]} to
     * {@code [app/cbl/COACTUPC.cbl:L2009]}), and {@code [app/cbl/COACTUPC.cbl:L2081]} in paragraph
     * {@code 1240-EDIT-ALPHANUM-OPT} ({@code [app/cbl/COACTUPC.cbl:L2061]} to
     * {@code [app/cbl/COACTUPC.cbl:L2105]}). The first site's comment at
     * {@code [app/cbl/COACTUPC.cbl:L1981]} states the intent plainly.
     *
     * <p><b>This method is separate from the alphabetic predicate for a reason.</b> Two of the five
     * non-fold {@code CONVERTING} sites convert the 62-character table rather than the 52-character
     * one, so routing them through {@link #isAlphaOrSpace(String)} would reject digits the legacy
     * system accepts. Source anomaly 18 sits on the second of them: the comment at
     * {@code [app/cbl/COACTUPC.cbl:L2078]} claims only letters and spaces are allowed, while the
     * statement at {@code [app/cbl/COACTUPC.cbl:L2079-L2082]} uses the alphanumeric table. The code
     * governs and the stale comment is recorded as a finding rather than followed.
     *
     * <p>Embedded spaces pass and membership is strict ASCII, for the same reasons set out on
     * {@link #isAlphaOrSpace(String)}. {@link Character#isLetterOrDigit(char)} and
     * {@link Character#isDigit(char)} are Unicode-aware and are therefore not used.
     *
     * @param value the candidate field value; must not be {@code null}
     * @return {@code true} when every character is an ASCII letter, an ASCII digit or a space,
     *         {@code false} otherwise
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static boolean isAlphaNumericOrSpace(final String value) {
        Objects.requireNonNull(value, "value must not be null: an absent field is not a blank field");
        return allInTableOrSpace(value, ALPHANUMERIC_TABLE);
    }

    /**
     * Reproduces the legacy embossed-name upper fold: a <b>strict 26-character table
     * substitution</b> of the lower-case ASCII letters onto the upper-case ASCII letters. Every
     * character absent from the {@code FROM} table is emitted unchanged.
     *
     * <p>Legacy call sites, both applying the fold in place to the persisted {@code PIC X(50)}
     * embossed-name field: {@code [app/cbl/COCRDUPC.cbl:L1357]}, immediately before the old-image
     * capture at {@code [app/cbl/COCRDUPC.cbl:L1360]}; and {@code [app/cbl/COCRDUPC.cbl:L1500]}, the
     * first statement of paragraph {@code 9300-CHECK-CHANGE-IN-REC}
     * ({@code [app/cbl/COCRDUPC.cbl:L1498]}), which runs before the before-and-after image comparison
     * at {@code [app/cbl/COCRDUPC.cbl:L1503-L1508]}.
     *
     * <p><b>{@link String#toUpperCase()} is forbidden here, and so is the
     * {@code java.util.Locale#ROOT} overload.</b> The no-argument form varies with the ambient default
     * locale - under a Turkish locale it maps {@code i} to a dotted capital - and <em>both</em> forms
     * are Unicode-aware: they upper-case characters this table leaves alone and can expand one
     * character into several, for example the German sharp s into a two-letter sequence. Either effect
     * changes the length of a value that must fit a fixed 50-byte field, and either is a byte-parity
     * failure.
     *
     * <p><b>Length is preserved exactly.</b> The substitution is one table character for one table
     * character, so the returned value has the same number of characters as the input and, because
     * every substituted character is a single-byte ASCII letter both before and after, the same encoded
     * byte count in any ASCII-compatible charset. The {@code FROM} and {@code TO} tables are
     * equal-length 26-character constants by declaration, mirroring the COBOL rule that the two
     * {@code CONVERTING} operands be the same size. Applying the fold in place to the entity field,
     * capturing the old image and performing the optimistic before-and-after comparison belong to the
     * card-update service; any handling of a card's embossed name must route through this method and
     * never through {@link String#toUpperCase()}.
     *
     * @param value the value to fold; must not be {@code null}
     * @return the value with every lower-case ASCII letter replaced by its upper-case counterpart
     *         and every other character left untouched
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String asciiUpperFold(final String value) {
        Objects.requireNonNull(value, "value must not be null: an absent field is not a blank field");
        char[] folded = null;
        for (int index = 0; index < value.length(); index++) {
            // COBOL CONVERTING semantics: locate the character in the FROM table and emit the
            // character occupying the same position in the TO table. A character that is not in the
            // FROM table is never touched, which is precisely why a locale-aware or Unicode-aware
            // upper-casing routine cannot be substituted here.
            final int position = ASCII_LOWER.indexOf(value.charAt(index));
            if (position >= 0) {
                if (folded == null) {
                    folded = value.toCharArray();
                }
                folded[index] = ASCII_UPPER.charAt(position);
            }
        }
        // No table character occurred, so the value is already its own fold.
        return folded == null ? value : new String(folded);
    }

    /**
     * Reproduces step three of the legacy menu-option normalisation: <b>right-justify the value into
     * {@code width} character positions, then replace every space with {@code '0'}</b>.
     *
     * <p>The legacy idiom is four steps, of which this method is the third. The receiving field is
     * declared {@code PIC X(02) JUST RIGHT} at {@code [app/cbl/COADM01C.cbl:L45]} and
     * {@code [app/cbl/COMEN01C.cbl:L45]}, and the numeric field it feeds is {@code PIC 9(02)} at
     * {@code [app/cbl/COADM01C.cbl:L46]} and {@code [app/cbl/COMEN01C.cbl:L46]}. Inside paragraph
     * {@code PROCESS-ENTER-KEY} ({@code [app/cbl/COADM01C.cbl:L115]},
     * {@code [app/cbl/COMEN01C.cbl:L115]}) the two programs are identical: a backward scan finds the
     * last non-space character with the index floored at one
     * ({@code [app/cbl/COADM01C.cbl:L117-L121]}); that prefix is moved into the right-justified
     * receiver ({@code [app/cbl/COADM01C.cbl:L122]}); <b>this method</b> turns every space in the
     * receiver into a zero ({@code [app/cbl/COADM01C.cbl:L123]},
     * {@code [app/cbl/COMEN01C.cbl:L123]}); and the receiver is moved into the numeric option field
     * ({@code [app/cbl/COADM01C.cbl:L124]}). Steps one, two and four, the numeric and range validation
     * and the operator message at {@code [app/cbl/COADM01C.cbl:L131]} all belong to the menu service.
     *
     * <p><b>{@code JUST RIGHT} truncates on the LEFT.</b> A sender shorter than the receiver lands
     * in the rightmost positions and the left is space-filled; a sender <em>longer</em> than the
     * receiver loses its leading excess, not its trailing excess. That is counter-intuitive, and a
     * right-truncating implementation would silently diverge, so the left truncation is reproduced
     * rather than corrected. The space replacement then applies to <b>every</b> space in the
     * receiver, not merely to the left fill, so an interior or trailing space from the sender is
     * zeroed too.
     *
     * <p>Worked examples at the width the only current caller uses: {@code "5"} yields
     * {@code "05"}; {@code ""} and {@code " "} both yield {@code "00"}; {@code "12"} yields
     * {@code "12"}; {@code "1 "} yields {@code "10"}; and {@code "123"} yields {@code "23"} because
     * the leading character is truncated away.
     *
     * <p><b>Width is measured in {@code PIC X(n)} character positions.</b> The legacy receiver is a
     * single-byte alphanumeric field fed from a single-byte terminal field, so positions, characters and
     * encoded bytes coincide for every value that field can carry. No charset policy is applied here -
     * that belongs to the fixed-width record layer - and no additional input restriction is imposed,
     * because inventing one would add a behaviour the legacy program does not have.
     *
     * @param value the sending value; must not be {@code null}
     * @param width the receiving field width in character positions; must be positive
     * @return a string of exactly {@code width} characters, right-justified from {@code value} with
     *         every space replaced by {@code '0'}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code width} is not positive
     */
    public static String rightJustifyZeroFill(final String value, final int width) {
        Objects.requireNonNull(value, "value must not be null: an absent field is not a blank field");
        if (width <= 0) {
            throw new IllegalArgumentException(
                    "width must be a positive PIC X(n) character-position count but was " + width);
        }
        final char[] receiver = new char[width];
        // JUST RIGHT retains the sender's RIGHTMOST characters; anything that does not fit is lost
        // from the LEFT, and a short sender leaves the LEFT of the receiver space-filled.
        final int retained = Math.min(value.length(), width);
        final int leftFill = width - retained;
        for (int index = 0; index < leftFill; index++) {
            receiver[index] = SPACE;
        }
        value.getChars(value.length() - retained, value.length(), receiver, leftFill);
        // INSPECT ... REPLACING ALL ' ' BY '0' - every space, including any the sender supplied.
        for (int index = 0; index < width; index++) {
            if (receiver[index] == SPACE) {
                receiver[index] = ZERO_FILL;
            }
        }
        return new String(receiver);
    }

    /**
     * Reproduces an ordinary COBOL {@code MOVE} of an alphanumeric sender into a {@code PIC X(n)}
     * receiver: <b>left-justify into {@code width} character positions, space-fill the remainder, and
     * truncate on the RIGHT anything that does not fit</b>.
     *
     * <p><strong>Why this is a required translation step and not defensive padding.</strong> Every
     * identifier the estate keys a record on is a field of a fixed-width record, and every screen item
     * that feeds one is declared at that same width, so the terminal delivered an eight-position value
     * whether the operator typed eight characters or one. The sign-on identifier is the clearest case:
     * the credential record declares {@code SEC-USR-PWD}'s sibling key {@code SEC-USR-ID} as
     * {@code PIC X(08)} at {@code [app/cpy/CSUSR01Y.cpy:L1-L14]}, and the sign-on program moves the
     * transmitted screen item straight into it before comparing. A REST caller has no terminal to
     * space-fill for it, so a four-character identifier arrives four characters long. That value is not
     * a shorter form of the key - it is a <em>different</em> key, and a store whose column is
     * variable-length will not match it against the eight-position row, will not find it for an update
     * or a delete, and will refuse it on insert against the declared-width constraint. This method is
     * the one place that difference is resolved.
     *
     * <p><strong>Right truncation, deliberately.</strong> A plain {@code MOVE} into an alphanumeric
     * receiver keeps the sender's <em>leftmost</em> characters and loses the excess from the right -
     * the exact opposite of {@link #rightJustifyZeroFill(String, int)}, whose receiver carries
     * {@code JUST RIGHT} and therefore truncates on the left. The two are not interchangeable and the
     * asymmetry is the reason both exist.
     *
     * <p><strong>What it does not do.</strong> It does not trim, fold case, reject a blank, validate a
     * character class or treat an absent value as a blank one. A blank or wholly-space sender is a
     * legitimate value here and becomes an all-space receiver, because the legacy move behaves that way
     * and because the emptiness tests that produce operator messages read the <em>raw</em> transmitted
     * value before this transformation is applied - reversing that order would replace a "please enter"
     * message with a fruitless lookup. Case folding, where a program performs one, is
     * {@link #asciiUpperFold(String)} and is applied separately in the order the program applies it.
     *
     * <p>Worked examples at width eight: {@code "ADMIN001"} yields {@code "ADMIN001"} unchanged;
     * {@code "USER1"} yields {@code "USER1   "}; {@code ""} and {@code "  "} yield eight spaces;
     * {@code "TOOLONGIDENTIFIER"} yields {@code "TOOLONGI"} because the excess is lost from the right.
     *
     * <p><strong>Width is measured in {@code PIC X(n)} character positions.</strong> The receivers this
     * serves are single-byte alphanumeric fields fed from single-byte terminal fields, so positions,
     * characters and encoded bytes coincide for every value such a field can carry. No charset policy is
     * applied here - that belongs to the fixed-width record layer.
     *
     * @param value the sending value; must not be {@code null}
     * @param width the receiving field width in character positions; must be positive
     * @return a string of exactly {@code width} characters, left-justified from {@code value} and
     *         space-filled on the right
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code width} is not positive
     */
    public static String leftJustifySpaceFill(final String value, final int width) {
        Objects.requireNonNull(value, "value must not be null: an absent field is not a blank field");
        if (width <= 0) {
            throw new IllegalArgumentException(
                    "width must be a positive PIC X(n) character-position count but was " + width);
        }
        if (value.length() == width) {
            // Already exactly the receiver's width, which is the case for every value that came out of
            // a record image or out of the store. Returned as-is so the common path allocates nothing.
            return value;
        }
        final char[] receiver = new char[width];
        // A plain MOVE keeps the sender's LEFTMOST characters; anything that does not fit is lost from
        // the RIGHT, and a short sender leaves the RIGHT of the receiver space-filled.
        final int retained = Math.min(value.length(), width);
        value.getChars(0, retained, receiver, 0);
        for (int index = retained; index < width; index++) {
            receiver[index] = SPACE;
        }
        return new String(receiver);
    }

    /**
     * Reports whether a signed-amount screen lexeme carries the legacy <em>not supplied</em> state, the
     * first of the three outcomes paragraph {@code 1250-EDIT-SIGNED-9V2} can reach
     * ({@code [app/cbl/COACTUPC.cbl:L2180-L2220]}).
     *
     * <p><strong>Two legacy tests compose into one.</strong> The map-to-working-storage move tests the
     * transmitted field first - {@code IF ACRDLIMI OF CACTUPAI = '*' OR = SPACES} moves
     * {@code LOW-VALUES} into the work field rather than the lexeme
     * ({@code [app/cbl/COACTUPC.cbl:L1073]}, and identically at lines 1087, 1101, 1115 and 1130) - and
     * the edit paragraph then tests {@code IF WS-EDIT-SIGNED-NUMBER-9V2-X EQUAL LOW-VALUES OR EQUAL
     * SPACES}. Composed, exactly three transmitted shapes reach the BLANK state: an absent field, an
     * all-space field, and a field holding the decoration marker. This predicate is that composition,
     * which is why it is one method rather than two.
     *
     * <p><strong>{@code null} is a value here, not a programming error.</strong> Every other primitive
     * in this class rejects {@code null}, because for those an absent field and a blank field are
     * different things. For this one they are the same thing: {@code LOW-VALUES} <em>is</em> how the
     * legacy represents "the terminal transmitted nothing for this field", and the paragraph tests for
     * it in the same breath as spaces. A JSON body that omits the component therefore reaches the same
     * state by the same rule, and raising {@link NullPointerException} instead would turn a legitimate
     * operator action into a fault.
     *
     * <p><strong>Marker matching is positional, not a trim.</strong> COBOL extends the literal
     * {@code '*'} with spaces to the 15-character width of the field it is compared against, so the
     * marker must occupy the <em>first</em> position with only spaces after it. {@code "*"} and
     * {@code "*    "} are the marker; {@code " *"} and {@code "*1"} are not, and neither is blank.
     *
     * @param lexeme the raw screen lexeme, or {@code null} when the component was not transmitted
     * @return {@code true} when the lexeme is absent, all spaces, or the decoration marker followed
     *         only by spaces
     */
    public static boolean isUnsuppliedNumericLexeme(final String lexeme) {
        if (lexeme == null) {
            return true;
        }
        int index = 0;
        if (!lexeme.isEmpty() && lexeme.charAt(index) == DECORATION_MARKER) {
            index++;
        }
        for (; index < lexeme.length(); index++) {
            if (lexeme.charAt(index) != SPACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code FUNCTION TEST-NUMVAL-C(lexeme) = 0} predicate, which separates the second and third
     * outcomes of paragraph {@code 1250-EDIT-SIGNED-9V2} ({@code [app/cbl/COACTUPC.cbl:L2201]}): a
     * lexeme that satisfies it is VALID and is decoded, and one that does not is NOT-OK and is
     * decorated. The paragraph reaches this test only for a lexeme that
     * {@link #isUnsuppliedNumericLexeme(String)} has already rejected, so a caller runs the two in that
     * order.
     *
     * <p><strong>The accepted grammar.</strong> This is the {@code NUMVAL-C} argument format: optional
     * spaces, an optional leading sign, an optional currency sign, a mantissa, optional spaces, an
     * optional trailing sign, optional spaces, and nothing else. The mantissa is one or more digits
     * that may carry single digit separators between them, optionally followed by a decimal point and
     * further digits, or a decimal point followed by digits with no integer part. A sign may be
     * leading <em>or</em> trailing but never both, and the trailing position additionally accepts the
     * two-character credit and debit marks, which denote a negative value.
     *
     * <p><strong>Deliberate strictness.</strong> The credit and debit marks are matched in upper case
     * only, exactly as the language writes them; accepting a lower-case spelling would be a behaviour
     * the estate does not have. A separator is rejected where no digit precedes it, where another
     * separator precedes it, and where no digit follows it, so {@code ",1"}, {@code "1,,2"} and
     * {@code "1,"} are all invalid. A separator inside the fractional part is invalid because the
     * language permits it only in the integer part. A lexeme with no digit at all is invalid, which
     * covers the all-space argument the paragraph has already diverted to the BLANK state.
     *
     * <p><strong>No magnitude judgement is made here.</strong> The screen field is 15 characters wide
     * while the persisted field is {@code PIC S9(10)V99}, so a well-formed lexeme can be wider than
     * the record can hold. The legacy paragraph does not test magnitude either - it tests form only -
     * so neither does this predicate, and the consequence is documented on
     * {@code ZonedDecimalCodec.fromNumericLexeme(String)}.
     *
     * @param lexeme the raw screen lexeme, or {@code null} when the component was not transmitted
     * @return {@code true} when the lexeme is a well-formed {@code NUMVAL-C} argument
     */
    public static boolean isNumericLexeme(final String lexeme) {
        return lexeme != null && scanNumericLexeme(lexeme, null);
    }

    /**
     * The {@code FUNCTION NUMVAL-C(lexeme)} conversion, expressed as a plain unscaled decimal string:
     * an optional minus sign, at least one integer digit, and an optional fractional part. Separators
     * and the currency sign are removed, a trailing sign or credit or debit mark becomes a leading
     * minus, and a mantissa with no integer part gains a single leading zero.
     *
     * <p>The result is returned as a string rather than as a number so that this class keeps its
     * character-handling remit and no decimal scale is ever chosen here. The one caller that turns it
     * into a value is {@code ZonedDecimalCodec.fromNumericLexeme(String)}, which is the module's single
     * point of decimal truth and the only place a scale may be applied.
     *
     * <p>No digit is added, removed or reordered, so the returned string carries every digit the
     * operator typed, including leading zeros. Deciding what the persisted field can hold belongs to
     * the codec and to the record layer, not here.
     *
     * @param lexeme the raw screen lexeme; must not be {@code null}
     * @return the equivalent plain decimal string, suitable for {@link String}-based construction of an
     *         exact decimal value
     * @throws NullPointerException if {@code lexeme} is {@code null}
     * @throws IllegalArgumentException if {@code lexeme} is not a well-formed {@code NUMVAL-C}
     *         argument, which a caller avoids by testing {@link #isNumericLexeme(String)} first
     */
    public static String plainDecimalOfNumericLexeme(final String lexeme) {
        Objects.requireNonNull(lexeme, "lexeme must not be null: an absent field is not a blank field");
        final StringBuilder plain = new StringBuilder(lexeme.length() + 2);
        if (!scanNumericLexeme(lexeme, plain)) {
            // The message names neither the lexeme nor any digit of it: a rejection must not leak a
            // monetary value into a log, which is the same rule decision D-16 applies to the codec.
            throw new IllegalArgumentException(
                    "screen lexeme is not a well-formed FUNCTION NUMVAL-C argument; "
                            + "test isNumericLexeme before converting");
        }
        return plain.toString();
    }

    /**
     * Shared membership test behind {@link #isAlphaOrSpace(String)} and
     * {@link #isAlphaNumericOrSpace(String)}, so the two predicates cannot drift apart.
     *
     * <p>This is the faithful reading of "convert the table to spaces, then assert the trimmed
     * remainder is empty": the remainder can only be empty when every character was either in the
     * table or already a space. Membership is tested against the supplied table literal, never
     * against a Unicode character-class method and never against a regular expression, either of
     * which would silently widen the accepted set beyond the strict ASCII table the source declares.
     *
     * @param value the candidate value, already checked to be non-{@code null}
     * @param table the strict ASCII {@code FROM} table to test membership against
     * @return {@code true} when every character of {@code value} is in {@code table} or is a space
     */
    private static boolean allInTableOrSpace(final String value, final String table) {
        for (int index = 0; index < value.length(); index++) {
            final char candidate = value.charAt(index);
            if (candidate != SPACE && table.indexOf(candidate) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The one implementation of the {@code NUMVAL-C} argument grammar, shared by
     * {@link #isNumericLexeme(String)} and {@link #plainDecimalOfNumericLexeme(String)} so that the
     * test and the conversion cannot disagree about what a valid lexeme is.
     *
     * @param lexeme the raw screen lexeme, already checked to be non-{@code null}
     * @param plain  receives the equivalent plain decimal string when the scan succeeds, or
     *               {@code null} when the caller wants the verdict only; left untouched on failure
     * @return {@code true} when the whole lexeme is consumed by the grammar
     */
    private static boolean scanNumericLexeme(final String lexeme, final StringBuilder plain) {
        final int length = lexeme.length();
        int index = skipSpaces(lexeme, 0);

        // Leading sign, then currency sign - that order and no other, as the language declares it.
        boolean negative = false;
        boolean signed = false;
        if (index < length && (lexeme.charAt(index) == PLUS_SIGN || lexeme.charAt(index) == MINUS_SIGN)) {
            negative = lexeme.charAt(index) == MINUS_SIGN;
            signed = true;
            index = skipSpaces(lexeme, index + 1);
        }
        if (index < length && lexeme.charAt(index) == CURRENCY_SIGN) {
            index = skipSpaces(lexeme, index + 1);
        }

        // Integer part: digits carrying single separators between them and nowhere else.
        final StringBuilder integerDigits = new StringBuilder(length);
        boolean separatorPending = false;
        while (index < length) {
            final char candidate = lexeme.charAt(index);
            if (ASCII_DIGITS.indexOf(candidate) >= 0) {
                integerDigits.append(candidate);
                separatorPending = false;
                index++;
            } else if (candidate == DIGIT_SEPARATOR && integerDigits.length() > 0 && !separatorPending) {
                separatorPending = true;
                index++;
            } else {
                break;
            }
        }
        if (separatorPending) {
            return false;
        }

        // Fractional part: a decimal point and the digits that follow it, separators not permitted.
        final StringBuilder fractionDigits = new StringBuilder(length);
        if (index < length && lexeme.charAt(index) == DECIMAL_POINT) {
            index++;
            while (index < length && ASCII_DIGITS.indexOf(lexeme.charAt(index)) >= 0) {
                fractionDigits.append(lexeme.charAt(index));
                index++;
            }
        }
        if (integerDigits.length() == 0 && fractionDigits.length() == 0) {
            return false;
        }

        index = skipSpaces(lexeme, index);

        // Trailing sign or credit or debit mark, permitted only when no leading sign was given.
        if (index < length) {
            final char trailing = lexeme.charAt(index);
            if (trailing == PLUS_SIGN || trailing == MINUS_SIGN) {
                if (signed) {
                    return false;
                }
                negative = trailing == MINUS_SIGN;
                index++;
            } else if (index + 1 < length && isCreditOrDebitMark(lexeme, index)) {
                if (signed) {
                    return false;
                }
                negative = true;
                index += 2;
            } else {
                return false;
            }
            index = skipSpaces(lexeme, index);
        }

        if (index != length) {
            return false;
        }

        if (plain != null) {
            if (negative) {
                plain.append(MINUS_SIGN);
            }
            plain.append(integerDigits.length() == 0 ? String.valueOf(ZERO_FILL) : integerDigits);
            if (fractionDigits.length() > 0) {
                plain.append(DECIMAL_POINT).append(fractionDigits);
            }
        }
        return true;
    }

    /**
     * Advances past a run of spaces, which the {@code NUMVAL-C} grammar permits at every boundary.
     *
     * @param lexeme the lexeme being scanned
     * @param from   the position to start at
     * @return the position of the first character at or after {@code from} that is not a space, or the
     *         length of the lexeme when none remains
     */
    private static int skipSpaces(final String lexeme, final int from) {
        int index = from;
        while (index < lexeme.length() && lexeme.charAt(index) == SPACE) {
            index++;
        }
        return index;
    }

    /**
     * Reports whether the two characters at the given position are the credit or the debit mark, both
     * of which denote a negative value in the trailing position of a {@code NUMVAL-C} argument. Only
     * the upper-case spellings the language declares are matched.
     *
     * @param lexeme the lexeme being scanned
     * @param at     the position of the first of the two characters; the caller guarantees a second
     *               character exists
     * @return {@code true} when the pair is the credit mark or the debit mark
     */
    private static boolean isCreditOrDebitMark(final String lexeme, final int at) {
        final char first = lexeme.charAt(at);
        final char second = lexeme.charAt(at + 1);
        return (first == 'C' && second == 'R') || (first == 'D' && second == 'B');
    }
}
