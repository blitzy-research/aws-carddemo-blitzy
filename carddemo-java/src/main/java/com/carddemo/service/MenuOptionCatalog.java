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
package com.carddemo.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Immutable catalog of the ten CardDemo user-menu options and the four administrator-menu options,
 * declared by {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}. The menu label text is the
 * only content carried across verbatim, because it is the external screen contract rather than
 * implementation detail.
 *
 * <p><strong>Why this bean lives in the service package and not the configuration package.</strong> The
 * technical specification lists it under {@code config}, and an earlier revision declared it there as a
 * {@code @Configuration}. That placement made {@code MenuService} import from {@code config} while
 * {@code config} already imports from {@code service} - {@code FlywayConfig} and
 * {@code SeededIdentifierSealingCallback} both depend on {@code SensitiveFieldEncryptionService} - which
 * closed a package cycle between the two. Those two configuration classes are a composition root reaching
 * downward, which is the legitimate direction; the single upward edge from {@code MenuService} was the one
 * that had to go, and removing it leaves {@code config} depending downward only.
 *
 * <p>The specification's own layering rule is the tie-breaker: it states that no package may depend
 * upward, and that rule is stronger than a suggested file location, so the location moves and the rule
 * holds. The service package is also where this module already keeps immutable reference-data catalogs of
 * exactly this kind - {@code ValidationLookupService} holds the area-code, state and state-plus-ZIP tables
 * and {@code MessageCatalogService} holds the common message text, both annotated {@code @Service} - so
 * this is the established convention rather than a new one. The utility package was rejected because it
 * declares no Spring stereotype anywhere and hosts no injected bean, and the domain package was rejected
 * because it imports no Spring type beyond the persistence annotations. The class name and both nested
 * record names are unchanged, so every consumer sees the same type it always did. Recorded in
 * {@code docs/decision-log.md}.
 *
 * <p><strong>Populated count, never table capacity.</strong> The legacy tables are declared larger than
 * they are filled &mdash; capacity twelve and nine against populations of ten and four &mdash; and the
 * count items are the authoritative sizes. This catalog publishes exactly {@link #USER_MENU_OPTION_COUNT}
 * and {@link #ADMIN_MENU_OPTION_COUNT} entries and never pads to capacity, because modelling capacity would
 * place two additional empty rows on the user menu and five on the administrator menu &mdash; a visible
 * behavioural regression rather than a harmless generalisation. Neither capacity figure is published as a
 * value anywhere in this class, and {@link #findUserOption(int)} deliberately reports an empty result for
 * the unpopulated tail positions.
 *
 * <p><strong>Two entry shapes, deliberately not unified.</strong> A user entry carries a one-character
 * user-type code and an administrator entry has no such component at all, so {@link UserMenuOption} and
 * {@link AdminMenuOption} stay separate record types: a single shared record would have to invent a
 * user-type value for administrator rows, and inventing a value the legacy layout does not contain is a
 * fidelity defect rather than a convenience.
 *
 * <p><strong>The user-type code stays raw.</strong> {@link UserMenuOption#userType()} returns the
 * one-character code exactly as the copybook literal carries it, neither parsed into an enumeration nor
 * validated against a known set, so an unrecognised code can never make this catalog fail. Interpreting the
 * code belongs to the service layer, in keeping with the raw-code discipline the persistence layer applies
 * to the security record.
 *
 * <p>A container-managed singleton reached by constructor injection rather than static access, which mirrors
 * how this migration treats the copybooks with wide fan-out: a declaration the legacy duplicated textually
 * in every including program becomes one injected instance. Lite mode is stated explicitly on the
 * annotation because the class declares no factory method: a full-mode class is subclassed at runtime to
 * intercept factory-method calls, which would forbid it from being {@code final}, and keeping it unproxied
 * leaves the module's runtime proxy and reflection surface untouched.
 *
 * <p><strong>This is a pure data catalog.</strong> Two closely related legacy behaviours belong to the menu
 * service that injects this bean, and are intentionally absent here so neither is implemented twice. The
 * first is the "coming soon" rule: the selected option's target program name has its first five characters
 * compared against a dummy-program literal at {@code app/cbl/COMEN01C.cbl:L146} and identically at
 * {@code app/cbl/COADM01C.cbl:L138}, and on a match the option is reported as not available instead of
 * being dispatched, {@code app/cbl/COMEN01C.cbl:L157-L164}. None of the fourteen entries published here
 * targets a dummy program, so this catalog holds no dummy entry, but the check must still be reproduced
 * faithfully because the catalog is not the only thing that can supply a program name to it. The second is
 * blank-to-zero option normalisation, {@code app/cbl/COADM01C.cbl:L45-L46, L123} and identically
 * {@code app/cbl/COMEN01C.cbl:L123}, which turns a single-digit entry in a right-justified two-character
 * field into a zero-filled two-digit value; that behaviour lives in
 * {@link com.carddemo.util.CobolStringUtils#rightJustifyZeroFill(String, int)} and the caller applies it
 * before looking an option number up here. This class performs no string normalisation of any kind, and
 * screen rendering, routing, authorisation and error decoration are likewise outside it.
 *
 * <p><strong>Three source anomalies are recorded rather than propagated</strong> &mdash; rows 24 to 26 of
 * the source anomaly register. The operative consequence of the first is that the commented-out alternative
 * label above user option 8 stays inactive: it is published neither as a value nor as a constant nor as a
 * conditional alternative, and option 8 is <strong>not</strong> role-gated, because activating it would be
 * feature expansion. The other two &mdash; a mislabelled title comment in both copybooks and a divergent
 * release stamp on the administrator copybook &mdash; change nothing here: the data item is followed rather
 * than the comment, and the content is migrated as found.
 *
 * <p>Stateless and deeply immutable. Both catalogs are built once with the immutable {@code java.util.List}
 * factory and held in {@code private static final} fields, both element types are records of {@code int}
 * and {@code String}, and there is no setter and no lazily populated field, so the singleton is safe for
 * unsynchronised concurrent use and the published lists cannot be modified by a caller.
 */
@Service
public final class MenuOptionCatalog {

    /**
     * Number of populated user-menu entries declared in {@code app/cpy/COMEN02Y.cpy}. This is the
     * populated count and not the twelve-entry capacity of the
     * redefining table, which this class never publishes.
     */
    public static final int USER_MENU_OPTION_COUNT = 10;

    /**
     * Number of populated administrator-menu entries declared in {@code app/cpy/COADM02Y.cpy}. This is the
     * populated count and not the nine-entry capacity of the
     * redefining table, which this class never publishes.
     */
    public static final int ADMIN_MENU_OPTION_COUNT = 4;

    /**
     * Declared digit width of the option-number component in both tables. Two digits is what bounds a valid
     * option number, which is why {@link #OPTION_NUMBER_MAXIMUM} is derived from this width rather than from
     * the populated counts.
     */
    public static final int OPTION_NUMBER_WIDTH = 2;

    /**
     * Highest option number a field of {@link #OPTION_NUMBER_WIDTH} digits can hold. An option number is
     * validated against the field it occupies rather than against the population, because the number is part
     * of the record layout while the population is not; a number in range that no populated entry carries is
     * an ordinary miss and not a failure.
     */
    public static final int OPTION_NUMBER_MAXIMUM = 99;

    /**
     * Declared character width of the option-name component in both tables. Every label literal in both
     * copybooks is written out at exactly this width, space-filled on the right. The labels published here
     * carry the visible text without that fill, because a REST payload has no fixed-width field to fill;
     * {@link UserMenuOption#paddedLabel()} and {@link AdminMenuOption#paddedLabel()} reproduce the
     * fixed-width form for a caller that needs the legacy field image.
     */
    public static final int OPTION_LABEL_WIDTH = 35;

    /**
     * Declared character width of the target-program-name component in both tables. All fourteen program
     * names occupy this width exactly, so no padding accessor is offered for them: there is nothing to pad.
     */
    public static final int OPTION_PROGRAM_NAME_WIDTH = 8;

    /**
     * Declared character width of the user-type component in {@code app/cpy/COMEN02Y.cpy}. Present in the
     * user table only, which is why the two entry types are
     * modelled separately.
     */
    public static final int USER_OPTION_USER_TYPE_WIDTH = 1;

    /**
     * Length in bytes of one user-menu table entry: option number, option name, program name and user-type
     * code laid end to end. Derived from the four declared widths so the figure cannot contradict them.
     */
    public static final int USER_MENU_ENTRY_LENGTH =
            OPTION_NUMBER_WIDTH + OPTION_LABEL_WIDTH + OPTION_PROGRAM_NAME_WIDTH
                    + USER_OPTION_USER_TYPE_WIDTH;

    /**
     * Length in bytes of one administrator-menu table entry, with no user-type component. Derived from the
     * three declared widths, and therefore exactly {@link #USER_OPTION_USER_TYPE_WIDTH} byte shorter than
     * {@link #USER_MENU_ENTRY_LENGTH}.
     */
    public static final int ADMIN_MENU_ENTRY_LENGTH =
            OPTION_NUMBER_WIDTH + OPTION_LABEL_WIDTH + OPTION_PROGRAM_NAME_WIDTH;

    /**
     * The raw one-character user-type code that every one of the ten user-menu entries carries, exactly as
     * the copybook literals write it. Published as the literal code rather than an enumeration constant so
     * this configuration-layer catalog stays free of any dependency on the domain layer. All ten entries
     * carry this same code, including option 8, whose inactive commented-out variant would have suggested
     * otherwise.
     */
    public static final String STANDARD_USER_TYPE_CODE = "U";

    /**
     * The ten populated entries the user table declares in {@code app/cpy/COMEN02Y.cpy}, in declaration
     * order. <strong>The order is contractual:</strong> the legacy
     * screen renders the rows in table order and the operator selects by the number printed beside the row,
     * so the sequence is never sorted, re-indexed or renumbered. Built with the immutable list factory, so
     * it needs no defensive copy to be genuinely unmodifiable.
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
     * The four populated entries the administrator table declares in {@code app/cpy/COADM02Y.cpy}, in
     * declaration order. As with the user menu that order is contractual and is never re-sorted. Each entry
     * carries three components only; there is no user-type code to carry.
     */
    private static final List<AdminMenuOption> ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)", "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)", "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));

    static {
        // The copybooks state their population twice: as an explicit count item and as the number of entries
        // actually written out. The catalogs transcribe the entries and the count constants transcribe the
        // count items, so their agreement is a property of the source and is verified here at class
        // initialisation.
        requireDeclaredPopulation(USER_MENU_OPTIONS.size(), USER_MENU_OPTION_COUNT, "CDEMO-MENU-OPT-COUNT");
        requireDeclaredPopulation(
                ADMIN_MENU_OPTIONS.size(), ADMIN_MENU_OPTION_COUNT, "CDEMO-ADMIN-OPT-COUNT");
    }

    /**
     * Creates the catalog singleton. Declared explicitly, with no parameters because this catalog sits at
     * the base of the dependency graph and has no collaborators, so that the absence of collaborators is a
     * visible property and a consumer has an unambiguous single constructor to inject through.
     */
    public MenuOptionCatalog() {
    }

    /**
     * Returns the populated user-menu options in copybook order, position 1 first. The list is unmodifiable
     * and is the same instance on every call, which is safe precisely because nothing in it can be mutated;
     * a caller needing a different order must derive its own collection.
     *
     * @return an unmodifiable list of exactly {@link #USER_MENU_OPTION_COUNT} entries, in copybook order
     */
    public List<UserMenuOption> userMenuOptions() {
        return USER_MENU_OPTIONS;
    }

    /**
     * Returns the populated administrator-menu options in copybook order, position 1 first. The element type
     * carries no user-type code, mirroring the legacy record layout.
     *
     * @return an unmodifiable list of exactly {@link #ADMIN_MENU_OPTION_COUNT} entries, in copybook order
     */
    public List<AdminMenuOption> adminMenuOptions() {
        return ADMIN_MENU_OPTIONS;
    }

    /**
     * @return the populated user-menu option count, read from the catalog itself rather than restated, so it
     *         can never disagree with the list it enumerates
     */
    public int userMenuOptionCount() {
        return USER_MENU_OPTIONS.size();
    }

    /**
     * @return the populated administrator-menu option count, read from the catalog itself rather than
     *         restated, so it can never disagree with the list it enumerates
     */
    public int adminMenuOptionCount() {
        return ADMIN_MENU_OPTIONS.size();
    }

    /**
     * Finds the user-menu option carrying the given option number. Lookup walks the catalog in order and
     * compares the number component, which is what the legacy program does when it indexes its table: there
     * is no map, no index and no reflection behind this. An unknown number yields an empty result rather
     * than an exception, because an operator typing an out-of-range option is ordinary input and not a
     * programming error &mdash; and that deliberately includes the unpopulated tail positions of the legacy
     * table, which are within its declared capacity yet carry no option.
     *
     * @param number the option number as printed beside the menu row, already normalised by the caller if it
     *               arrived as a blank-padded field
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
     * Finds the administrator-menu option carrying the given option number, behaving exactly as
     * {@link #findUserOption(int)} does over the administrator catalog: ordered comparison, no index, and an
     * empty result for any number no populated entry carries, including the unpopulated tail positions.
     *
     * @param number the option number as printed beside the menu row, already normalised by the caller if it
     *               arrived as a blank-padded field
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
     * @param countItemName legacy field name of that count item, so a failure names its own authority
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
     * Validates an option number against the two-digit field it occupies in the legacy table. The bound is
     * the field's capacity rather than the population, because the number belongs to the record layout while
     * the population does not. Zero and negatives are rejected because the legacy tables number their rows
     * from one.
     *
     * @return {@code number}, unchanged, so the caller can validate and assign in one expression
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
     * Validates a text component against its declared width. The value is checked and returned unchanged:
     * nothing here trims, pads, folds case or otherwise normalises it, because a catalog that silently
     * rewrote its own labels would stop being a faithful transcription. A value shorter than the declared
     * width is accepted, since COBOL space-fills a short alphanumeric value into the field rather than
     * rejecting it.
     *
     * @return {@code value}, unchanged
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
     * Validates the one-character user-type code against its declared width, without interpreting the
     * character. Only the width is enforced; the code is not compared against any known set of values, so an
     * unrecognised code passes through untouched and is left for the service layer to interpret. This is the
     * same discipline the persistence layer applies to the legacy security record, and it is what prevents
     * an unfamiliar code from turning into a startup failure.
     *
     * @return {@code value}, unchanged
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
     * left-justifies a short alphanumeric value and space-fills it to its declared width. The fill count is
     * derived arithmetically so that no space in this source file is ever counted by eye.
     */
    private static String padToWidth(final String text, final int width) {
        final int fill = width - text.length();
        return fill <= 0 ? text : text + " ".repeat(fill);
    }

    /**
     * One entry of the user menu, mirroring the four components the redefining table view of
     * {@code app/cpy/COMEN02Y.cpy} declares and together occupying
     * {@value MenuOptionCatalog#USER_MENU_ENTRY_LENGTH} bytes. Deliberately <em>not</em> shared with
     * {@link AdminMenuOption}, which has no user-type component at all.
     *
     * @param label       the visible option text, carried without the trailing space fill the copybook
     *                    literal writes; see {@link #paddedLabel()}
     * @param programName the target program name, which the service layer also tests for the legacy
     *                    dummy-program marker
     * @param userType    the raw one-character code, uninterpreted
     */
    public record UserMenuOption(int number, String label, String programName, String userType) {

        /**
         * Validates every component against the field it occupies, without modifying any of them.
         */
        public UserMenuOption {
            number = requireOptionNumber(number, "CDEMO-MENU-OPT-NUM");
            label = requireFieldText(label, "CDEMO-MENU-OPT-NAME", OPTION_LABEL_WIDTH);
            programName =
                    requireFieldText(programName, "CDEMO-MENU-OPT-PGMNAME", OPTION_PROGRAM_NAME_WIDTH);
            userType = requireUserTypeCode(userType);
        }

        /**
         * Returns the label as the legacy fixed-width field image, space-filled on the right to
         * {@link MenuOptionCatalog#OPTION_LABEL_WIDTH} characters, for a caller that has to reproduce the
         * field image rather than render a payload value. Derived on each call rather than stored, so there
         * is exactly one authority for the text and no padded copy that could drift from it.
         */
        public String paddedLabel() {
            return padToWidth(label, OPTION_LABEL_WIDTH);
        }
    }

    /**
     * One entry of the administrator menu, mirroring the three components the redefining table view of
     * {@code app/cpy/COADM02Y.cpy} declares and together occupying
     * {@value MenuOptionCatalog#ADMIN_MENU_ENTRY_LENGTH} bytes.
     *
     * <p>There is no user-type component here, and none is invented. The legacy record layout carries no
     * per-row code to copy, so who may reach these options is decided where routes are secured rather than
     * by anything this row could say; defaulting an administrator code into the entry would look harmless
     * and would quietly add a field the source layout does not have.
     *
     * @param label       the visible option text, carried without the trailing space fill the copybook
     *                    literal writes; see {@link #paddedLabel()}
     * @param programName the target program name, which the service layer also tests for the legacy
     *                    dummy-program marker
     */
    public record AdminMenuOption(int number, String label, String programName) {

        /**
         * Validates every component against the field it occupies, without modifying any of them.
         */
        public AdminMenuOption {
            number = requireOptionNumber(number, "CDEMO-ADMIN-OPT-NUM");
            label = requireFieldText(label, "CDEMO-ADMIN-OPT-NAME", OPTION_LABEL_WIDTH);
            programName =
                    requireFieldText(programName, "CDEMO-ADMIN-OPT-PGMNAME", OPTION_PROGRAM_NAME_WIDTH);
        }

        /**
         * Returns the label as the legacy fixed-width field image, space-filled on the right to
         * {@link MenuOptionCatalog#OPTION_LABEL_WIDTH} characters. Derived on each call rather than stored,
         * for the same reason as on the user-menu entry.
         */
        public String paddedLabel() {
            return padToWidth(label, OPTION_LABEL_WIDTH);
        }
    }
}
