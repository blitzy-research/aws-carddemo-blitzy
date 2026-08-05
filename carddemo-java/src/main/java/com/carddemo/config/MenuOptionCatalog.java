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

import com.carddemo.service.MenuOptionSource;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Immutable catalog of the ten CardDemo user-menu options and the four administrator-menu options,
 * declared by {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}. The menu label text is the
 * only content carried across verbatim, because it is the external screen contract rather than
 * implementation detail.
 *
 * <p><strong>Where this bean sits, and why that costs no layering violation.</strong> The technical
 * specification lists this catalog under {@code config}, and that is where it is. The specification also
 * forbids any package from depending upward, so the consuming service may not import this package: the
 * contract it consumes is {@link MenuOptionSource}, which the service package owns and this class
 * implements. The dependency therefore runs downward - configuration to service - which is the direction
 * the composition root already runs in, exactly as {@code FlywayConfig} and
 * {@code SeededIdentifierSealingCallback} depend downward on the field-encryption service. Both
 * requirements are met at once and neither is reinterpreted.
 *
 * <p>The two entry shapes and their field-width validation belong to the contract rather than to this
 * class, so a consumer names one type and this class supplies the values. They remain reachable through
 * this class's own name because a nested type declared on an interface is inherited by its
 * implementations.
 *
 * <p><strong>Populated count, never table capacity.</strong> The legacy tables are declared larger than
 * they are filled &mdash; capacity twelve and nine against populations of ten and four &mdash; and the
 * count items are the authoritative sizes. This catalog publishes exactly
 * {@link MenuOptionSource#USER_MENU_OPTION_COUNT} and {@link MenuOptionSource#ADMIN_MENU_OPTION_COUNT}
 * entries and never pads to capacity, because modelling capacity would place two additional empty rows on
 * the user menu and five on the administrator menu &mdash; a visible behavioural regression rather than a
 * harmless generalisation. Neither capacity figure is published as a value anywhere in this class, and
 * {@link #findUserOption(int)} deliberately reports an empty result for the unpopulated tail positions.
 *
 * <p><strong>Two entry shapes, deliberately not unified.</strong> A user entry carries a one-character
 * user-type code and an administrator entry does not, because the two copybooks declare different
 * layouts. Giving the administrator entry a defaulted code would add a field the source does not have.
 *
 * <p><strong>Source anomalies recorded rather than propagated.</strong> The commented-out alternative
 * label above user option 8 stays inactive: it is published neither as a value nor as a constant nor as a
 * conditional alternative, and option 8 is <strong>not</strong> role-gated, because activating either
 * would be feature expansion. The mislabelled title comment in both copybooks and the divergent release
 * stamp on the administrator copybook change nothing here: the data item is followed rather than the
 * comment, and the content is migrated as found.
 *
 * <p>Stateless and deeply immutable. Both catalogs are built once with the immutable
 * {@code java.util.List} factory and held in {@code private static final} fields, both element types are
 * records of {@code int} and {@code String}, and there is no setter and no lazily populated field, so the
 * singleton is safe for unsynchronised concurrent use and the published lists cannot be modified by a
 * caller.
 *
 * <p>Provenance: {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}, read as read-only
 * reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@Component
public final class MenuOptionCatalog implements MenuOptionSource {

    /**
     * The ten populated entries the user table declares in {@code app/cpy/COMEN02Y.cpy}, in declaration
     * order. <strong>The order is contractual:</strong> the legacy screen renders the rows in table order
     * and the operator selects by the number printed beside the row, so the sequence is never sorted,
     * re-indexed or renumbered. Built with the immutable list factory, so it needs no defensive copy to be
     * genuinely unmodifiable.
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
     * declaration order. As with the user menu that order is contractual and is never re-sorted. Each
     * entry carries three components only; there is no user-type code to carry.
     */
    private static final List<AdminMenuOption> ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)", "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)", "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));

    static {
        // The copybooks state their population twice: as an explicit count item and as the number of
        // entries actually written out. The catalogs transcribe the entries and the contract's count
        // constants transcribe the count items, so their agreement is a property of the source and is
        // verified here at class initialisation.
        requireDeclaredPopulation(USER_MENU_OPTIONS.size(), USER_MENU_OPTION_COUNT,
                "CDEMO-MENU-OPT-COUNT");
        requireDeclaredPopulation(ADMIN_MENU_OPTIONS.size(), ADMIN_MENU_OPTION_COUNT,
                "CDEMO-ADMIN-OPT-COUNT");
    }

    /**
     * Creates the catalog singleton. Declared explicitly, with no parameters because this catalog sits at
     * the base of the dependency graph and has no collaborators, so that the absence of collaborators is a
     * visible property and a consumer has an unambiguous single constructor to inject through.
     */
    public MenuOptionCatalog() {
    }

    /**
     * {@inheritDoc}
     *
     * <p>The list is unmodifiable and is the same instance on every call, which is safe precisely because
     * nothing in it can be mutated; a caller needing a different order must derive its own collection.
     */
    @Override
    public List<UserMenuOption> userMenuOptions() {
        return USER_MENU_OPTIONS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AdminMenuOption> adminMenuOptions() {
        return ADMIN_MENU_OPTIONS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read from the catalog itself rather than restated, so it can never disagree with the list it
     * enumerates.
     */
    @Override
    public int userMenuOptionCount() {
        return USER_MENU_OPTIONS.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read from the catalog itself rather than restated, so it can never disagree with the list it
     * enumerates.
     */
    @Override
    public int adminMenuOptionCount() {
        return ADMIN_MENU_OPTIONS.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lookup walks the catalog in order and compares the number component, which is what the legacy
     * program does when it indexes its table: there is no map, no index and no reflection behind this.
     */
    @Override
    public Optional<UserMenuOption> findUserOption(final int number) {
        for (final UserMenuOption option : USER_MENU_OPTIONS) {
            if (option.number() == number) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Behaves exactly as {@link #findUserOption(int)} does over the administrator catalog: ordered
     * comparison, no index, and an empty result for any number no populated entry carries, including the
     * unpopulated tail positions.
     */
    @Override
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
     * @param actual        the number of entries transcribed here
     * @param declared      the population the copybook's count item declares
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
}
