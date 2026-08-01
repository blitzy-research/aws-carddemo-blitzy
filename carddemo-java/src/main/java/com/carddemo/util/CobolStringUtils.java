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
 * breakdown <b>3 alphabetic + 2 alphanumeric + 2 upper-fold</b>, which is why <b>four</b> primitives
 * exist here rather than three: covering the two alphanumeric sites with an alphabetic-only predicate
 * would reject digits the legacy system accepts. Two further textual matches at
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
 * masquerade as a valid blank one, and the legacy programs distinguish those two states. Empty and
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
}
