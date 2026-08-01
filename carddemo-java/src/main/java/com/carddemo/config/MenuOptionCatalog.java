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
package com.carddemo.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Configuration;

/**
 * Immutable catalog of the ten CardDemo user-menu options and the four administrator-menu options.
 *
 * <p>The two legacy authorities are {@code app/cpy/COMEN02Y.cpy}, whose group item
 * {@code CARDDEMO-MAIN-MENU-OPTIONS} on line 19 holds the <strong>user</strong> menu with a declared
 * population of <strong>10</strong> ({@code CDEMO-MENU-OPT-COUNT}, line 21) and whose redefining table
 * view on lines 87-92 names four components per entry &mdash; a two-digit option number, a 35-character
 * name, an eight-character target program name and a one-character user-type code &mdash; and
 * {@code app/cpy/COADM02Y.cpy}, whose {@code CARDDEMO-ADMIN-MENU-OPTIONS} on line 19 holds the
 * <strong>administrator</strong> menu with a declared population of <strong>4</strong>
 * ({@code CDEMO-ADMIN-OPT-COUNT}, line 20) and whose table view on lines 44-48 names only
 * <em>three</em> components. Only member names, field names, declared widths, line numbers and the menu
 * label text cross over; the label text crosses over because it is the external screen contract rather
 * than implementation detail.
 *
 * <p><strong>Populated count, never table capacity.</strong> The legacy tables are declared larger than
 * they are filled &mdash; capacity twelve and nine against populations of ten and four &mdash; and the
 * count items are the authoritative sizes. This catalog publishes exactly
 * {@link #USER_MENU_OPTION_COUNT} and {@link #ADMIN_MENU_OPTION_COUNT} entries and never pads to
 * capacity, because modelling capacity would place two additional empty rows on the user menu and five on
 * the administrator menu &mdash; a visible behavioural regression rather than a harmless generalisation.
 * Neither capacity figure is published as a value anywhere in this class; both are named here only so a
 * reader knows the tables are bigger than their contents, and {@link #findUserOption(int)} deliberately
 * reports an empty result for the unpopulated tail positions.
 *
 * <p><strong>Two entry shapes, deliberately not unified.</strong> A user entry occupies
 * {@value #USER_MENU_ENTRY_LENGTH} bytes because it carries the one-character user-type code; an
 * administrator entry occupies {@value #ADMIN_MENU_ENTRY_LENGTH} bytes because it has no such component
 * at all. {@link UserMenuOption} and {@link AdminMenuOption} therefore stay separate record types: a
 * single shared record would have to invent a user-type value for administrator rows, and inventing a
 * value the legacy layout does not contain is a fidelity defect, not a convenience.
 *
 * <p><strong>The user-type code stays raw.</strong> {@link UserMenuOption#userType()} returns the
 * one-character code exactly as the copybook literal carries it, neither parsed into an enumeration nor
 * validated against a known set, so an unrecognised code can never make this catalog fail. Interpreting
 * the code &mdash; and deciding what an unrecognised one means &mdash; belongs to the service layer, in
 * keeping with the raw-code discipline the persistence layer applies to the security record.
 *
 * <p><strong>Bean semantics.</strong> A container-managed singleton, reached by constructor injection
 * rather than static access, which mirrors how this migration treats the copybooks with wide fan-out: a
 * declaration the legacy duplicated textually in every including program becomes one injected instance.
 * The class declares no factory method, so lite mode is stated explicitly on the annotation: a full-mode
 * class is subclassed at runtime to intercept factory-method calls, which this class has none of and
 * which would forbid it from being {@code final}, and keeping it unproxied leaves the module's runtime
 * proxy and reflection surface untouched.
 *
 * <p><strong>This is a pure data catalog.</strong> Two closely related legacy behaviours belong to the
 * menu service that injects this bean rather than to the catalog, and are intentionally absent here so
 * that neither is implemented twice. The first is the "coming soon" rule:
 * {@code app/cbl/COMEN01C.cbl} line 138 compares the first five characters of the selected option's
 * target program name against a dummy-program literal and reports the option as not yet available when
 * they match. None of the fourteen entries published here targets a dummy program, so this catalog holds
 * no dummy entry, but the check itself must still be reproduced faithfully because the catalog is not the
 * only thing that can supply a program name to it. The second is blank-to-zero option normalisation:
 * {@code app/cbl/COADM01C.cbl} lines 45-46 and 123, and identically {@code app/cbl/COMEN01C.cbl} line
 * 123, normalise a blank in a right-justified two-character option field so that a single-digit entry
 * becomes a zero-filled two-digit value. That behaviour lives in
 * {@link com.carddemo.util.CobolStringUtils#rightJustifyZeroFill(String, int)}, which the caller applies
 * before looking an option number up here; this class performs no string normalisation of any kind.
 * Screen rendering, routing, authorisation and error decoration are likewise outside it.
 *
 * <p><strong>Three source anomalies are recorded rather than propagated</strong> &mdash; rows 24 to 26 of
 * the source anomaly register. The operative consequence of the first is that the commented-out
 * alternative label above user option 8 stays inactive: it is published neither as a value nor as a
 * constant nor as a conditional alternative, and option 8 is <strong>not</strong> role-gated, because
 * activating it would be feature expansion. The other two &mdash; a mislabelled title comment in both
 * copybooks and a divergent release stamp on the administrator copybook &mdash; change nothing here: the
 * data item is followed rather than the comment, and the content is migrated as found.
 *
 * <p><strong>Thread safety.</strong> Stateless and deeply immutable. Both catalogs are built once with
 * the immutable {@code java.util.List} factory and held in {@code private static final} fields; both
 * element types are records whose components are {@code int} and {@code String}; there is no setter, no
 * lazily populated field and no mutable state of any kind, so the singleton is safe for unsynchronised
 * concurrent use and the published lists cannot be modified by a caller.
 */
@Configuration(proxyBeanMethods = false)
public final class MenuOptionCatalog {

    /**
     * Number of populated user-menu entries, from {@code CDEMO-MENU-OPT-COUNT} in
     * {@code app/cpy/COMEN02Y.cpy} line 21.
     *
     * <p>This is the populated count and not the twelve-entry capacity of the redefining table, which
     * this class never publishes.</p>
     */
    public static final int USER_MENU_OPTION_COUNT = 10;

    /**
     * Number of populated administrator-menu entries, from {@code CDEMO-ADMIN-OPT-COUNT} in
     * {@code app/cpy/COADM02Y.cpy} line 20.
     *
     * <p>This is the populated count and not the nine-entry capacity of the redefining table, which this
     * class never publishes.</p>
     */
    public static final int ADMIN_MENU_OPTION_COUNT = 4;

    /**
     * Declared width of the option-number component in both tables, from the {@code PIC 9(02)} clauses of
     * {@code CDEMO-MENU-OPT-NUM} and {@code CDEMO-ADMIN-OPT-NUM}. Two digits is what bounds a valid option
     * number, which is why {@link #OPTION_NUMBER_MAXIMUM} is derived from this width rather than from the
     * populated counts.
     */
    public static final int OPTION_NUMBER_WIDTH = 2;

    /**
     * Highest option number that a field of {@link #OPTION_NUMBER_WIDTH} digits can hold.
     *
     * <p>An option number is validated against the field it occupies rather than against the current
     * population, because the number is part of the record layout while the population is not. Requesting
     * a number in range that no populated entry carries is an ordinary miss, not a failure.</p>
     */
    public static final int OPTION_NUMBER_MAXIMUM = 99;

    /**
     * Declared width of the option-name component in both tables, from the {@code PIC X(35)} clauses of
     * {@code CDEMO-MENU-OPT-NAME} and {@code CDEMO-ADMIN-OPT-NAME}.
     *
     * <p>Every label literal in both copybooks is written out at exactly this width, space-filled on the
     * right. The labels published by this catalog carry the visible text without that fill, because a
     * REST payload has no fixed-width field to fill; {@link UserMenuOption#paddedLabel()} and
     * {@link AdminMenuOption#paddedLabel()} reproduce the fixed-width form for a caller that needs the
     * legacy field image.</p>
     */
    public static final int OPTION_LABEL_WIDTH = 35;

    /**
     * Declared width of the target-program-name component in both tables, from the {@code PIC X(08)}
     * clauses of {@code CDEMO-MENU-OPT-PGMNAME} and {@code CDEMO-ADMIN-OPT-PGMNAME}.
     *
     * <p>All fourteen program names occupy this width exactly, so no padding accessor is offered for
     * them: there is nothing to pad.</p>
     */
    public static final int OPTION_PROGRAM_NAME_WIDTH = 8;

    /**
     * Declared width of the user-type component, from the {@code PIC X(01)} clause of
     * {@code CDEMO-MENU-OPT-USRTYPE} in {@code app/cpy/COMEN02Y.cpy} line 92.
     *
     * <p>Present in the user table only, which is why the two entry types are modelled separately.</p>
     */
    public static final int USER_OPTION_USER_TYPE_WIDTH = 1;

    /**
     * Length in bytes of one user-menu table entry: option number, option name, program name and
     * user-type code laid end to end. Derived from the four declared widths so the figure cannot
     * contradict them.
     */
    public static final int USER_MENU_ENTRY_LENGTH =
            OPTION_NUMBER_WIDTH + OPTION_LABEL_WIDTH + OPTION_PROGRAM_NAME_WIDTH
                    + USER_OPTION_USER_TYPE_WIDTH;

    /**
     * Length in bytes of one administrator-menu table entry: option number, option name and program name
     * laid end to end, with no user-type component. Derived from the three declared widths, and therefore
     * exactly {@link #USER_OPTION_USER_TYPE_WIDTH} byte shorter than {@link #USER_MENU_ENTRY_LENGTH}.
     */
    public static final int ADMIN_MENU_ENTRY_LENGTH =
            OPTION_NUMBER_WIDTH + OPTION_LABEL_WIDTH + OPTION_PROGRAM_NAME_WIDTH;

    /**
     * The raw one-character user-type code that every one of the ten user-menu entries carries, exactly as
     * the copybook literals write it.
     *
     * <p>Published as the literal code rather than an enumeration constant, so this configuration-layer
     * catalog stays free of any dependency on the domain layer and the meaning of the code is decided in
     * exactly one place. All ten entries carry this same code, including option 8, whose inactive
     * commented-out variant would have suggested otherwise.</p>
     */
    public static final String STANDARD_USER_TYPE_CODE = "U";

    /**
     * The ten populated entries of {@code CDEMO-MENU-OPTIONS-DATA}, in the order the copybook declares
     * them on lines 25 to 84 of {@code app/cpy/COMEN02Y.cpy}.
     *
     * <p>The order is contractual: the legacy screen renders the rows in table order and the operator
     * selects by the number printed beside the row, so the sequence is never sorted, re-indexed or
     * renumbered. The list is built with the immutable list factory, so it needs no defensive copy to be
     * genuinely unmodifiable.</p>
     */
    private static final List<UserMenuOption> USER_MENU_OPTIONS = List.of(
            new UserMenuOption(1, "Account View", "COACTVWC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(2, "Account Update", "COACTUPC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(3, "Credit Card List", "COCRDLIC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(4, "Credit Card View", "COCRDSLC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(5, "Credit Card Update", "COCRDUPC", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(6, "Transaction List", "COTRN00C", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(7, "Transaction View", "COTRN01C", STANDARD_USER_TYPE_CODE),
            // Option 8 takes the label live at app/cpy/COMEN02Y.cpy line 70. The inactive alternative on
            // line 69 stays inactive and the option is not role-gated: anomaly 24.
            new UserMenuOption(8, "Transaction Add", "COTRN02C", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(9, "Transaction Reports", "CORPT00C", STANDARD_USER_TYPE_CODE),
            new UserMenuOption(10, "Bill Payment", "COBIL00C", STANDARD_USER_TYPE_CODE));

    /**
     * The four populated entries of {@code CDEMO-ADMIN-OPTIONS-DATA}, in the order the copybook declares
     * them on lines 24 to 42 of {@code app/cpy/COADM02Y.cpy}.
     *
     * <p>As with the user menu the order is contractual and is never re-sorted. Each entry carries three
     * components only; there is no user-type code to carry.</p>
     */
    private static final List<AdminMenuOption> ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)", "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)", "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));

    static {
        // The copybooks state their population twice: as an explicit count item and as the number of
        // entries actually written out. The catalogs above transcribe the entries and the count constants
        // transcribe the count items, so their agreement is a property of the source, verified here at
        // class initialisation. It cannot be reached while the two declarations above stay consistent.
        requireDeclaredPopulation(USER_MENU_OPTIONS.size(), USER_MENU_OPTION_COUNT, "CDEMO-MENU-OPT-COUNT");
        requireDeclaredPopulation(
                ADMIN_MENU_OPTIONS.size(), ADMIN_MENU_OPTION_COUNT, "CDEMO-ADMIN-OPT-COUNT");
    }

    /**
     * Creates the catalog singleton.
     *
     * <p>This catalog sits at the base of the dependency graph and has no collaborators, so constructor
     * injection contributes no parameters. Declared explicitly rather than left implicit so that the
     * absence of collaborators is a visible, reviewable property, and so that a consumer has an
     * unambiguous single constructor to inject this bean through.</p>
     */
    public MenuOptionCatalog() {
        // No collaborators to inject: both catalogs are declared in this source file and are immutable.
    }

    /**
     * Returns the populated user-menu options in copybook order, position 1 first.
     *
     * <p>The returned list is unmodifiable and is the same instance on every call, which is safe precisely
     * because nothing in it can be mutated. Callers must not attempt to reorder or filter it in place;
     * a caller that needs a different order must derive its own collection.</p>
     *
     * @return an unmodifiable list of exactly {@link #USER_MENU_OPTION_COUNT} entries, in copybook order,
     *         never {@code null} and never empty
     */
    public List<UserMenuOption> userMenuOptions() {
        return USER_MENU_OPTIONS;
    }

    /**
     * Returns the populated administrator-menu options in copybook order, position 1 first.
     *
     * <p>The returned list is unmodifiable and is the same instance on every call. Its element type
     * carries no user-type code, mirroring the legacy record layout.</p>
     *
     * @return an unmodifiable list of exactly {@link #ADMIN_MENU_OPTION_COUNT} entries, in copybook order,
     *         never {@code null} and never empty
     */
    public List<AdminMenuOption> adminMenuOptions() {
        return ADMIN_MENU_OPTIONS;
    }

    /**
     * Returns how many user-menu options are populated, read from the catalog itself rather than restated,
     * so that the value a caller sees can never disagree with the list it enumerates.
     *
     * @return {@link #USER_MENU_OPTION_COUNT}
     */
    public int userMenuOptionCount() {
        return USER_MENU_OPTIONS.size();
    }

    /**
     * Returns how many administrator-menu options are populated, read from the catalog itself rather than
     * restated, so that the value a caller sees can never disagree with the list it enumerates.
     *
     * @return {@link #ADMIN_MENU_OPTION_COUNT}
     */
    public int adminMenuOptionCount() {
        return ADMIN_MENU_OPTIONS.size();
    }

    /**
     * Finds the user-menu option carrying the given option number.
     *
     * <p>Lookup walks the catalog in order and compares the number component, which is the same thing the
     * legacy program does when it indexes its table: there is no map, no index and no reflection behind
     * this. An unknown number yields an empty result rather than an exception, because an operator typing
     * an out-of-range option is ordinary input and not a programming error. That deliberately includes the
     * unpopulated tail positions of the legacy table: they are within its declared capacity yet carry no
     * option, so they are reported as absent exactly as a number far outside the range would be.</p>
     *
     * @param number the option number as printed beside the menu row, already normalised by the caller if
     *               it arrived as a blank-padded field
     * @return the matching option, or an empty {@code Optional} if no populated entry carries that number
     */
    public Optional<UserMenuOption> findUserOption(final int number) {
        for (final UserMenuOption option : USER_MENU_OPTIONS) {
            if (option.number() == number) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the administrator-menu option carrying the given option number.
     *
     * <p>Behaves exactly as {@link #findUserOption(int)} does, over the administrator catalog: ordered
     * comparison, no index, and an empty result for any number that no populated entry carries, including
     * the unpopulated tail positions of the legacy table.</p>
     *
     * @param number the option number as printed beside the menu row, already normalised by the caller if
     *               it arrived as a blank-padded field
     * @return the matching option, or an empty {@code Optional} if no populated entry carries that number
     */
    public Optional<AdminMenuOption> findAdminOption(final int number) {
        for (final AdminMenuOption option : ADMIN_MENU_OPTIONS) {
            if (option.number() == number) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /**
     * Verifies that a catalog's transcribed entry count matches the population its copybook count item
     * declares.
     *
     * @param actual        number of entries actually transcribed into the catalog
     * @param declared      population declared by the copybook count item
     * @param countItemName legacy field name of that count item, so a failure names its own authority
     * @throws IllegalStateException if the two disagree
     */
    private static void requireDeclaredPopulation(
            final int actual, final int declared, final String countItemName) {
        if (actual != declared) {
            throw new IllegalStateException(
                    "Menu catalog transcription mismatch: " + actual + " entries were transcribed but "
                            + countItemName + " declares " + declared);
        }
    }

    /**
     * Validates an option number against the two-digit field it occupies in the legacy table.
     *
     * <p>The bound is the field's capacity rather than the current population, because the number belongs
     * to the record layout while the population does not. Zero and negatives are rejected because the
     * legacy tables number their rows from one.</p>
     *
     * @param number        the option number to validate
     * @param componentName legacy field name of the number component, used in the failure message
     * @return {@code number}, unchanged, so the caller can validate and assign in one expression
     * @throws IllegalArgumentException if the number cannot occupy the declared field
     */
    private static int requireOptionNumber(final int number, final String componentName) {
        if (number < 1 || number > OPTION_NUMBER_MAXIMUM) {
            throw new IllegalArgumentException(
                    componentName + " must be between 1 and " + OPTION_NUMBER_MAXIMUM + " to fit its "
                            + OPTION_NUMBER_WIDTH + "-digit field, but was " + number);
        }
        return number;
    }

    /**
     * Validates a text component against the width its {@code PIC} clause declares.
     *
     * <p>The value is checked and returned unchanged: nothing here trims, pads, folds case or otherwise
     * normalises it, because a catalog that silently rewrote its own labels would stop being a faithful
     * transcription. A value shorter than the declared width is accepted, since COBOL space-fills a short
     * alphanumeric value into the field rather than rejecting it.</p>
     *
     * @param value         the component value to validate
     * @param componentName legacy field name of the component, used in the failure message
     * @param declaredWidth width declared by that component's {@code PIC} clause
     * @return {@code value}, unchanged
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank or wider than the declared field
     */
    private static String requireFieldText(
            final String value, final String componentName, final int declaredWidth) {
        Objects.requireNonNull(value, componentName + " must be supplied");
        if (value.isBlank()) {
            throw new IllegalArgumentException(componentName + " must not be blank");
        }
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException(
                    componentName + " must fit its " + declaredWidth + "-character field, but was "
                            + value.length() + " characters long");
        }
        return value;
    }

    /**
     * Validates the one-character user-type code against the width its {@code PIC} clause declares,
     * without interpreting the character.
     *
     * <p>Only the width is enforced. The code is not compared against any known set of values, so an
     * unrecognised code passes through untouched and is left for the service layer to interpret. This is
     * the same discipline the persistence layer applies to the legacy security record, and it is what
     * prevents an unfamiliar code from turning into a startup failure.</p>
     *
     * @param value the raw one-character code to validate
     * @return {@code value}, unchanged
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly one character long
     */
    private static String requireUserTypeCode(final String value) {
        Objects.requireNonNull(value, "CDEMO-MENU-OPT-USRTYPE must be supplied");
        if (value.length() != USER_OPTION_USER_TYPE_WIDTH) {
            throw new IllegalArgumentException(
                    "CDEMO-MENU-OPT-USRTYPE must be exactly " + USER_OPTION_USER_TYPE_WIDTH
                            + " character long, but was " + value.length() + " characters long");
        }
        return value;
    }

    /**
     * Right-pads {@code text} with spaces to exactly {@code width} characters, reproducing how COBOL
     * left-justifies a short alphanumeric value and space-fills it to its declared width.
     *
     * <p>The fill count is derived arithmetically so that no space in this source file is ever counted by
     * eye, and the result is exactly {@code width} characters whenever the input fits, which the record
     * constructors already guarantee.</p>
     *
     * @param text  the visible text to place in the field
     * @param width the field's declared width
     * @return the field image
     */
    private static String padToWidth(final String text, final int width) {
        final int fill = width - text.length();
        return fill <= 0 ? text : text + " ".repeat(fill);
    }

    /**
     * One entry of the user menu, mirroring the four components the redefining table view declares on
     * lines 87-92 of {@code app/cpy/COMEN02Y.cpy}: a two-digit number, a 35-character name, an
     * eight-character target program name and a one-character user-type code, together occupying
     * {@value MenuOptionCatalog#USER_MENU_ENTRY_LENGTH} bytes.
     *
     * <p>This type is deliberately <em>not</em> shared with {@link AdminMenuOption}, which has no
     * user-type component at all.</p>
     *
     * @param number      the option number as printed beside the menu row, from
     *                    {@code CDEMO-MENU-OPT-NUM}; between 1 and {@link #OPTION_NUMBER_MAXIMUM}
     * @param label       the visible option text from {@code CDEMO-MENU-OPT-NAME}, carried without the
     *                    trailing space fill the copybook literal writes; see {@link #paddedLabel()}
     * @param programName the target program name from {@code CDEMO-MENU-OPT-PGMNAME}, which the service
     *                    layer also tests for the legacy dummy-program marker
     * @param userType    the raw one-character code from {@code CDEMO-MENU-OPT-USRTYPE}, uninterpreted
     */
    public record UserMenuOption(int number, String label, String programName, String userType) {

        /**
         * Validates every component against the field it occupies, without modifying any of them.
         *
         * @throws NullPointerException     if any text component is {@code null}
         * @throws IllegalArgumentException if the number cannot occupy its two-digit field, if the label
         *                                  or program name is blank or too wide, or if the user-type code
         *                                  is not exactly one character
         */
        public UserMenuOption {
            number = requireOptionNumber(number, "CDEMO-MENU-OPT-NUM");
            label = requireFieldText(label, "CDEMO-MENU-OPT-NAME", OPTION_LABEL_WIDTH);
            programName =
                    requireFieldText(programName, "CDEMO-MENU-OPT-PGMNAME", OPTION_PROGRAM_NAME_WIDTH);
            userType = requireUserTypeCode(userType);
        }

        /**
         * Returns the label as the legacy fixed-width field image: the visible text, space-filled on the
         * right to {@link MenuOptionCatalog#OPTION_LABEL_WIDTH} characters.
         *
         * <p>Provided for a caller that has to reproduce the field image rather than render a payload
         * value. It is derived on each call rather than stored, so there is exactly one authority for the
         * text and no padded copy that could drift from it.</p>
         *
         * @return the label at its declared field width, never {@code null}
         */
        public String paddedLabel() {
            return padToWidth(label, OPTION_LABEL_WIDTH);
        }
    }

    /**
     * One entry of the administrator menu, mirroring the three components the redefining table view
     * declares on lines 44-48 of {@code app/cpy/COADM02Y.cpy}: a two-digit number, a 35-character name and
     * an eight-character target program name, together occupying
     * {@value MenuOptionCatalog#ADMIN_MENU_ENTRY_LENGTH} bytes.
     *
     * <p>There is no user-type component here, and none is invented. The legacy record layout carries no
     * per-row code to copy, so who may reach these options is decided where routes are secured rather
     * than by anything this row could say. Defaulting an administrator code into the entry would look
     * harmless and would quietly add a field the source layout does not have.</p>
     *
     * @param number      the option number as printed beside the menu row, from
     *                    {@code CDEMO-ADMIN-OPT-NUM}; between 1 and {@link #OPTION_NUMBER_MAXIMUM}
     * @param label       the visible option text from {@code CDEMO-ADMIN-OPT-NAME}, carried without the
     *                    trailing space fill the copybook literal writes; see {@link #paddedLabel()}
     * @param programName the target program name from {@code CDEMO-ADMIN-OPT-PGMNAME}, which the service
     *                    layer also tests for the legacy dummy-program marker
     */
    public record AdminMenuOption(int number, String label, String programName) {

        /**
         * Validates every component against the field it occupies, without modifying any of them.
         *
         * @throws NullPointerException     if any text component is {@code null}
         * @throws IllegalArgumentException if the number cannot occupy its two-digit field, or if the
         *                                  label or program name is blank or too wide
         */
        public AdminMenuOption {
            number = requireOptionNumber(number, "CDEMO-ADMIN-OPT-NUM");
            label = requireFieldText(label, "CDEMO-ADMIN-OPT-NAME", OPTION_LABEL_WIDTH);
            programName =
                    requireFieldText(programName, "CDEMO-ADMIN-OPT-PGMNAME", OPTION_PROGRAM_NAME_WIDTH);
        }

        /**
         * Returns the label as the legacy fixed-width field image: the visible text, space-filled on the
         * right to {@link MenuOptionCatalog#OPTION_LABEL_WIDTH} characters.
         *
         * <p>Derived on each call rather than stored, for the same reason as on the user-menu entry.</p>
         *
         * @return the label at its declared field width, never {@code null}
         */
        public String paddedLabel() {
            return padToWidth(label, OPTION_LABEL_WIDTH);
        }
    }
}
