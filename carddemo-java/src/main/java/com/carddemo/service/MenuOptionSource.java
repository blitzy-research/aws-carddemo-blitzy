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

/**
 * The service-tier contract over the two legacy menu option tables, and the home of both entry shapes.
 *
 * <p><strong>Why a contract exists separately from the catalog that holds the data.</strong> The
 * technical specification places the catalog bean at {@code config/MenuOptionCatalog}, and it also states
 * that no package may depend upward - a service may not import from the configuration package. Those two
 * requirements are satisfied together and neither is weakened: the <em>data</em> lives in the
 * configuration package exactly where the specification puts it, and the <em>contract</em> lives here,
 * where the service that consumes it can name it. {@code MenuService} therefore depends on this
 * interface, the configuration bean implements it, and the container supplies the implementation without
 * either side importing the other's package.
 *
 * <p><strong>The two entry records and their field-width validation live here</strong>, because they are
 * the values this contract passes and a consumer of the contract must be able to name their type. They
 * are inherited by any implementation, so an implementation's own name resolves them too.
 *
 * <p>Populated counts, never table capacity. The legacy tables are declared larger than they are filled
 * &mdash; capacity twelve and nine against populations of ten and four &mdash; and the count items
 * declared in {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy} are the authoritative sizes.
 * An implementation publishes exactly {@link #USER_MENU_OPTION_COUNT} and
 * {@link #ADMIN_MENU_OPTION_COUNT} entries and never pads to capacity, because modelling capacity would
 * place two additional empty rows on the user menu and five on the administrator menu - a visible
 * behavioural regression rather than a harmless generalisation.
 *
 * <p>Every implementation is required to be immutable and to publish unmodifiable lists in copybook
 * order, because the legacy screen renders the rows in table order and the operator selects by the number
 * printed beside the row.
 *
 * <p>Provenance: {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}, read as read-only
 * reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook text is transcribed beyond the
 * menu label literals, which are the external screen contract.
 *
 * @since 1.0.0
 */
public interface MenuOptionSource {

    /**
     * Number of populated user-menu entries declared in {@code app/cpy/COMEN02Y.cpy}. This is the
     * populated count and not the twelve-entry capacity of the redefining table, which this contract
     * never publishes.
     */
    int USER_MENU_OPTION_COUNT = 10;

    /**
     * Number of populated administrator-menu entries declared in {@code app/cpy/COADM02Y.cpy}. This is
     * the populated count and not the nine-entry capacity of the redefining table.
     */
    int ADMIN_MENU_OPTION_COUNT = 4;

    /**
     * Declared digit width of the option-number component in both tables. Two digits is what bounds a
     * valid option number, which is why {@link #OPTION_NUMBER_MAXIMUM} is derived from this width rather
     * than from the populated counts.
     */
    int OPTION_NUMBER_WIDTH = 2;

    /**
     * Highest option number a field of {@link #OPTION_NUMBER_WIDTH} digits can hold. An option number is
     * validated against the field it occupies rather than against the population, because the number is
     * part of the record layout while the population is not; a number in range that no populated entry
     * carries is an ordinary miss and not a failure.
     */
    int OPTION_NUMBER_MAXIMUM = 99;

    /** Declared character width of the option-name component in both tables. */
    int OPTION_LABEL_WIDTH = 35;

    /** Declared character width of the target program-name component in both tables. */
    int OPTION_PROGRAM_NAME_WIDTH = 8;

    /** Declared character width of the user-type component the user table alone carries. */
    int USER_OPTION_USER_TYPE_WIDTH = 1;

    /** Byte length of one user-menu entry: number, label, program name and user-type code. */
    int USER_MENU_ENTRY_LENGTH =
            OPTION_NUMBER_WIDTH + OPTION_LABEL_WIDTH + OPTION_PROGRAM_NAME_WIDTH
                    + USER_OPTION_USER_TYPE_WIDTH;

    /** Byte length of one administrator-menu entry: number, label and program name. */
    int ADMIN_MENU_ENTRY_LENGTH =
            OPTION_NUMBER_WIDTH + OPTION_LABEL_WIDTH + OPTION_PROGRAM_NAME_WIDTH;

    /**
     * The one-character user-type code every populated user-menu entry carries in
     * {@code app/cpy/COMEN02Y.cpy}. Published because the entries transcribe it and a reader of the
     * catalog should not have to count characters in a literal to learn which code that is.
     */
    String STANDARD_USER_TYPE_CODE = "U";

    /**
     * Returns the populated user-menu options in copybook order, position 1 first.
     *
     * @return an unmodifiable list of exactly {@link #USER_MENU_OPTION_COUNT} entries, in copybook order
     */
    List<UserMenuOption> userMenuOptions();

    /**
     * Returns the populated administrator-menu options in copybook order, position 1 first. The element
     * type carries no user-type code, mirroring the legacy record layout.
     *
     * @return an unmodifiable list of exactly {@link #ADMIN_MENU_OPTION_COUNT} entries, in copybook order
     */
    List<AdminMenuOption> adminMenuOptions();

    /**
     * @return the populated user-menu option count, read from the catalog itself rather than restated, so
     *         it can never disagree with the list it enumerates
     */
    int userMenuOptionCount();

    /**
     * @return the populated administrator-menu option count, read from the catalog itself rather than
     *         restated, so it can never disagree with the list it enumerates
     */
    int adminMenuOptionCount();

    /**
     * Finds the user-menu option carrying the given option number.
     *
     * <p>An unknown number yields an empty result rather than an exception, because an operator typing an
     * out-of-range option is ordinary input and not a programming error - and that deliberately includes
     * the unpopulated tail positions of the legacy table, which are within its declared capacity yet
     * carry no option.
     *
     * @param number the option number as printed beside the menu row, already normalised by the caller if
     *               it arrived as a blank-padded field
     * @return the matching entry, or an empty result when no populated entry carries that number
     */
    Optional<UserMenuOption> findUserOption(int number);

    /**
     * Finds the administrator-menu option carrying the given option number, behaving exactly as
     * {@link #findUserOption(int)} does over the administrator catalog.
     *
     * @param number the option number as printed beside the menu row, already normalised by the caller if
     *               it arrived as a blank-padded field
     * @return the matching entry, or an empty result when no populated entry carries that number
     */
    Optional<AdminMenuOption> findAdminOption(int number);

    /**
     * Validates an option number against the two-digit field it occupies in the legacy table. The bound
     * is the field's capacity rather than the population, because the number belongs to the record layout
     * while the population does not. Zero and negatives are rejected because the legacy tables number
     * their rows from one.
     *
     * @param number        the transcribed option number
     * @param componentName legacy field name of the component, so a failure names its own authority
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
     * @param value         the transcribed text
     * @param componentName legacy field name of the component
     * @param declaredWidth the field width the component occupies
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
     * character. Only the width is enforced; the code is not compared against any known set of values, so
     * an unrecognised code passes through untouched and is left for the service layer to interpret. This
     * is the same discipline the persistence layer applies to the legacy security record, and it is what
     * prevents an unfamiliar code from turning into a startup failure.
     *
     * @param value the transcribed code
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
     * left-justifies a short alphanumeric value and space-fills it to its declared width. The fill count
     * is derived arithmetically so that no space in this source file is ever counted by eye.
     *
     * @param text  the value to pad
     * @param width the declared field width
     * @return the field image
     */
    private static String padToWidth(final String text, final int width) {
        final int fill = width - text.length();
        return fill <= 0 ? text : text + " ".repeat(fill);
    }

    /**
     * One entry of the user menu, mirroring the four components the redefining table view of
     * {@code app/cpy/COMEN02Y.cpy} declares and together occupying
     * {@value MenuOptionSource#USER_MENU_ENTRY_LENGTH} bytes. Deliberately <em>not</em> shared with
     * {@link AdminMenuOption}, which has no user-type component at all.
     *
     * @param number      the option number as printed beside the menu row
     * @param label       the visible option text, carried without the trailing space fill the copybook
     *                    literal writes; see {@link #paddedLabel()}
     * @param programName the target program name, which the service layer also tests for the legacy
     *                    dummy-program marker
     * @param userType    the raw one-character code, uninterpreted
     */
    record UserMenuOption(int number, String label, String programName, String userType) {

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
         * {@link MenuOptionSource#OPTION_LABEL_WIDTH} characters, for a caller that has to reproduce the
         * field image rather than render a payload value. Derived on each call rather than stored, so
         * there is exactly one authority for the text and no padded copy that could drift from it.
         *
         * @return the label as its fixed-width field image
         */
        public String paddedLabel() {
            return padToWidth(label, OPTION_LABEL_WIDTH);
        }
    }

    /**
     * One entry of the administrator menu, mirroring the three components the redefining table view of
     * {@code app/cpy/COADM02Y.cpy} declares and together occupying
     * {@value MenuOptionSource#ADMIN_MENU_ENTRY_LENGTH} bytes.
     *
     * <p>There is no user-type component here, and none is invented. The legacy record layout carries no
     * per-row code to copy, so who may reach these options is decided where routes are secured rather
     * than by anything this row could say; defaulting an administrator code into the entry would look
     * harmless and would quietly add a field the source layout does not have.
     *
     * @param number      the option number as printed beside the menu row
     * @param label       the visible option text, carried without the trailing space fill the copybook
     *                    literal writes; see {@link #paddedLabel()}
     * @param programName the target program name, which the service layer also tests for the legacy
     *                    dummy-program marker
     */
    record AdminMenuOption(int number, String label, String programName) {

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
         * {@link MenuOptionSource#OPTION_LABEL_WIDTH} characters. Derived on each call rather than
         * stored, for the same reason as on the user-menu entry.
         *
         * @return the label as its fixed-width field image
         */
        public String paddedLabel() {
            return padToWidth(label, OPTION_LABEL_WIDTH);
        }
    }
}
