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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link MenuOptionCatalog}, the migrated form of the two CardDemo menu-option copybooks.
 *
 * <h2>What is under test</h2>
 * {@link MenuOptionCatalog} publishes two catalogs, and this class is their sole owner in the test estate.
 * The legacy authorities, cited as metadata only, are:
 * <ul>
 *   <li>{@code app/cpy/COMEN02Y.cpy} &mdash; the <strong>user</strong> menu. Group item
 *       {@code CARDDEMO-MAIN-MENU-OPTIONS} on line 19, declared population
 *       {@code CDEMO-MENU-OPT-COUNT} on line 21, and ten entries written out on lines 25 to 84. Each entry
 *       carries four components: {@code CDEMO-MENU-OPT-NUM}, {@code CDEMO-MENU-OPT-NAME},
 *       {@code CDEMO-MENU-OPT-PGMNAME} and {@code CDEMO-MENU-OPT-USRTYPE}.</li>
 *   <li>{@code app/cpy/COADM02Y.cpy} &mdash; the <strong>administrator</strong> menu. Group item
 *       {@code CARDDEMO-ADMIN-MENU-OPTIONS} on line 19, declared population
 *       {@code CDEMO-ADMIN-OPT-COUNT} on line 20, and four entries written out on lines 24 to 42. Each
 *       entry carries only three components: {@code CDEMO-ADMIN-OPT-NUM}, {@code CDEMO-ADMIN-OPT-NAME} and
 *       {@code CDEMO-ADMIN-OPT-PGMNAME}. There is no user-type component anywhere in that member.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} &mdash; the shared communication area, whose
 *       {@code CDEMO-USER-TYPE} item on line 26 carries the two condition names that give the user-type
 *       codes their meaning: an administrator code on line 27 and a standard-user code on line 28.</li>
 * </ul>
 * No copybook is read at run time and no COBOL statement is reproduced here. Only member names, field
 * names, declared widths, line numbers and the menu label text cross over, and the label text crosses over
 * because it is the external screen contract rather than implementation detail.
 *
 * <h2>This class also owns the admin-gate assertions</h2>
 * The administrator gate is asserted here rather than in a class of its own, because it is a property of
 * these two catalogs and of nothing else: every user entry carries the standard-user code and no
 * administrator entry carries a user-type code at all. A second test class for the gate would duplicate
 * ownership of the same production class. What the gate is <em>not</em> is a routing decision: because the
 * administrator rows publish no code, who may reach them cannot be derived from a row and must come from
 * the authenticated principal, which {@code com.carddemo.config.SecurityConfig} owns.
 *
 * <h2>The surplus table slots, and why they must stay unrepresented</h2>
 * Both copybooks declare a redefining table larger than the group it redefines. A user entry occupies
 * {@value #EXPECTED_USER_ENTRY_LENGTH} bytes, so the ten written entries occupy
 * {@value #EXPECTED_USER_DATA_GROUP_LENGTH} bytes, while the redefining table spans
 * {@value #EXPECTED_USER_TABLE_SPAN} bytes &mdash; {@value #EXPECTED_USER_TABLE_SURPLUS_BYTES} bytes more
 * than the group it overlays. An administrator entry occupies {@value #EXPECTED_ADMIN_ENTRY_LENGTH} bytes,
 * so its four entries occupy {@value #EXPECTED_ADMIN_DATA_GROUP_LENGTH} bytes against a table span of
 * {@value #EXPECTED_ADMIN_TABLE_SPAN} bytes, a surplus of
 * {@value #EXPECTED_ADMIN_TABLE_SURPLUS_BYTES} bytes.
 *
 * <p>The surplus positions are therefore not blank storage inside the declared group: they overlay
 * whatever happens to follow it, so reading them in the legacy program is undefined. That is why the
 * catalog publishes the declared populations and never the table capacities, and why a lookup for a
 * surplus position must report an absent option rather than a blank placeholder. Both capacities appear in
 * this class only as the bound of the surplus range and as the subject of negative assertions; neither is
 * ever an expected count.</p>
 *
 * <h2>The inactive alternative label for user option 8</h2>
 * In {@code app/cpy/COMEN02Y.cpy} the line above option 8's live label is a COBOL comment carrying a
 * different label for that same option, one that would describe it as restricted to administrators. It is
 * inactive in the legacy source and must stay inactive: activating it would be feature expansion, it would
 * change the administrator gate for an option the user menu genuinely offers to standard users, and the
 * migration mandate forbids adding behaviour the source does not have. The inactive text is named in this
 * class only inside negative assertions, which is a contract assertion rather than transcribed source.
 *
 * <h2>Source anomalies recorded, not propagated</h2>
 * <ol>
 *   <li>Line 2 of {@code app/cpy/COMEN02Y.cpy} carries an administrator-menu title comment even though
 *       that member declares the main-menu group item and holds the ten user options. The data item is
 *       correct and the comment is a copy-and-paste defect. Nothing in this class takes its wording from
 *       that comment, and no name, display name or claim here describes the user catalog as an
 *       administrator catalog.</li>
 *   <li>The trailer of {@code app/cpy/COADM02Y.cpy} on line 50 records a later upstream release stamp than
 *       the rest of the estate carries. A release stamp is a traceability-matrix header string and is not
 *       universal across the estate, so no assertion in this class references a stamp on any member, and
 *       the divergence is left exactly as found.</li>
 * </ol>
 *
 * <h2>How these tests are written</h2>
 * <ul>
 *   <li>Every expected value is a literal declared in this class, so the oracle is independent of the code
 *       it judges. No expected label, program name, code or count is obtained by calling
 *       {@link MenuOptionCatalog} or any other production type.</li>
 *   <li>The two record shapes are proved to differ by record deconstruction rather than by reflection: a
 *       record pattern must name every component of its record, so
 *       {@link #describeShape(Object)} stops compiling the moment either shape gains or loses a component.
 *       No reflective lookup, no accessibility override and no dynamic class loading appears anywhere in
 *       this class, so it adds nothing to the module's low-level-code audit.</li>
 *   <li>This is a plain unit test. It starts no container, opens no connection, binds no port, loads no
 *       application context and reads no file, because the class under test has no collaborators.</li>
 * </ul>
 *
 * <h2>Provenance</h2>
 * Legacy authorities read at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}.
 */
@DisplayName("Menu option catalog: ten user options, four administrator options, and no surplus slots")
class MenuOptionCatalogTest {

    /**
     * Populated size of the user catalog, from {@code CDEMO-MENU-OPT-COUNT} in
     * {@code app/cpy/COMEN02Y.cpy} line 21 and confirmed by counting the entries written out on lines 25
     * to 84. Declared here as a literal so it is an oracle rather than an echo.
     */
    private static final int EXPECTED_USER_OPTION_COUNT = 10;

    /**
     * Populated size of the administrator catalog, from {@code CDEMO-ADMIN-OPT-COUNT} in
     * {@code app/cpy/COADM02Y.cpy} line 20 and confirmed by counting the entries written out on lines 24
     * to 42.
     */
    private static final int EXPECTED_ADMIN_OPTION_COUNT = 4;

    /**
     * Capacity of the redefining user table, from the {@code OCCURS} clause on line 88 of
     * {@code app/cpy/COMEN02Y.cpy}.
     *
     * <p>This is a table capacity and never an expected count. It is used here only to bound the surplus
     * range that {@link SurplusSlots} probes and as the subject of negative assertions proving the catalog
     * does not publish it.</p>
     */
    private static final int USER_TABLE_DECLARED_CAPACITY = 12;

    /**
     * Capacity of the redefining administrator table, from the {@code OCCURS} clause on line 45 of
     * {@code app/cpy/COADM02Y.cpy}.
     *
     * <p>As with the user table this is a capacity and never an expected count, and it is used only to
     * bound the surplus range and to state a negative.</p>
     */
    private static final int ADMIN_TABLE_DECLARED_CAPACITY = 9;

    /**
     * Declared width of the option-number component in both tables, from its {@code PIC 9(02)} clause.
     */
    private static final int EXPECTED_OPTION_NUMBER_WIDTH = 2;

    /**
     * Highest value a two-digit option-number field can hold, and therefore the upper bound the catalog
     * validates an option number against.
     */
    private static final int EXPECTED_OPTION_NUMBER_MAXIMUM = 99;

    /**
     * Declared width of the option-name component in both tables, from its {@code PIC X(35)} clause. Every
     * label literal in both copybooks is written out at exactly this width, space-filled on the right.
     */
    private static final int EXPECTED_LABEL_WIDTH = 35;

    /**
     * Declared width of the target-program-name component in both tables, from its {@code PIC X(08)}
     * clause. All fourteen program names occupy this width exactly.
     */
    private static final int EXPECTED_PROGRAM_NAME_WIDTH = 8;

    /**
     * Declared width of the user-type component, from its {@code PIC X(01)} clause. This component exists
     * in the user table only.
     */
    private static final int EXPECTED_USER_TYPE_WIDTH = 1;

    /**
     * Bytes occupied by one user-table entry: option number, option name, program name and user-type code
     * laid end to end.
     */
    private static final int EXPECTED_USER_ENTRY_LENGTH = 46;

    /**
     * Bytes occupied by one administrator-table entry: option number, option name and program name laid
     * end to end, with no user-type code. Exactly one byte shorter than a user entry.
     */
    private static final int EXPECTED_ADMIN_ENTRY_LENGTH = 45;

    /**
     * Bytes occupied by the ten written user entries, and therefore the length of the group the user table
     * redefines.
     */
    private static final int EXPECTED_USER_DATA_GROUP_LENGTH = 460;

    /**
     * Bytes spanned by the redefining user table at its declared capacity.
     */
    private static final int EXPECTED_USER_TABLE_SPAN = 552;

    /**
     * Bytes by which the redefining user table overruns the group it redefines. Those bytes are the
     * surplus positions, and they are undefined overlay storage rather than blank entries.
     */
    private static final int EXPECTED_USER_TABLE_SURPLUS_BYTES = 92;

    /**
     * Bytes occupied by the four written administrator entries.
     */
    private static final int EXPECTED_ADMIN_DATA_GROUP_LENGTH = 180;

    /**
     * Bytes spanned by the redefining administrator table at its declared capacity.
     */
    private static final int EXPECTED_ADMIN_TABLE_SPAN = 405;

    /**
     * Bytes by which the redefining administrator table overruns the group it redefines.
     */
    private static final int EXPECTED_ADMIN_TABLE_SURPLUS_BYTES = 225;

    /**
     * The raw one-character standard-user code every user entry carries, from the {@code VALUE} clause of
     * each entry's user-type component and given its meaning by the standard-user condition name on line
     * 28 of {@code app/cpy/COCOM01Y.cpy}. Asserted as a {@code String} because the catalog publishes the
     * code raw and leaves interpretation to the service layer.
     */
    private static final String EXPECTED_STANDARD_USER_TYPE_CODE = "U";

    /**
     * The raw one-character administrator code, from the administrator condition name on line 27 of
     * {@code app/cpy/COCOM01Y.cpy}. It appears in this class only in negative assertions: no user-menu
     * entry carries it, and the administrator catalog carries no user-type code at all.
     */
    private static final String ADMIN_USER_TYPE_CODE = "A";

    /**
     * Number of the user option whose alternative label is commented out in the legacy source.
     */
    private static final int OPTION_EIGHT_NUMBER = 8;

    /**
     * The live label of user option 8, from the uncommented literal in {@code app/cpy/COMEN02Y.cpy}.
     */
    private static final String EXPECTED_OPTION_EIGHT_LABEL = "Transaction Add";

    /**
     * The full text of the inactive alternative label for user option 8. Named here solely so that a
     * negative assertion can prove no catalog value ever equals or contains it.
     */
    private static final String INACTIVE_OPTION_EIGHT_LABEL = "Transaction Add (Admin Only)";

    /**
     * The distinguishing fragment of the inactive alternative label. No user-menu label may contain it,
     * and no administrator label contains it either, so the assertion is unambiguous across both catalogs.
     */
    private static final String ADMIN_ONLY_MARKER = "(Admin Only)";

    /**
     * The fragment every administrator label carries, which is what makes the administrator labels
     * distinguishable from the inactive user-option label without any risk of collision.
     */
    private static final String SECURITY_MARKER = "(Security)";

    /**
     * Number of components a user-menu entry declares, from the four subordinate items of the redefining
     * table view on lines 89 to 92 of {@code app/cpy/COMEN02Y.cpy}.
     */
    private static final int EXPECTED_USER_COMPONENT_COUNT = 4;

    /**
     * Number of components an administrator-menu entry declares, from the three subordinate items of the
     * redefining table view on lines 46 to 48 of {@code app/cpy/COADM02Y.cpy}. One fewer than a user entry,
     * and the missing one is the user-type code.
     */
    private static final int EXPECTED_ADMIN_COMPONENT_COUNT = 3;

    /**
     * Separator used when {@link #describeShape(Object)} renders a deconstructed entry, chosen because it
     * appears in no label, program name or user-type code.
     */
    private static final String COMPONENT_SEPARATOR = "|";

    /**
     * Tag {@link #describeShape(Object)} prefixes onto a four-component user entry.
     */
    private static final String USER_SHAPE_TAG = "user";

    /**
     * Tag {@link #describeShape(Object)} prefixes onto a three-component administrator entry.
     */
    private static final String ADMIN_SHAPE_TAG = "admin";

    /**
     * Tag {@link #describeShape(Object)} returns for anything that is neither entry shape. Reaching it is
     * how a test proves that a user entry does not satisfy the administrator shape and vice versa.
     */
    private static final String UNRECOGNISED_SHAPE_TAG = "unrecognised";

    /**
     * The catalog under test. Rebuilt before each test so that no test can depend on another having run
     * and so that the public no-argument constructor is exercised every time.
     */
    private MenuOptionCatalog catalog;

    /**
     * Builds a fresh catalog before each test.
     *
     * <p>Direct construction is deliberate. The catalog has no collaborators, so there is nothing to mock,
     * no container to start and no application context to load; constructing it here is both sufficient
     * and the strongest available statement that this test needs no framework to run.</p>
     */
    @BeforeEach
    void createCatalog() {
        catalog = new MenuOptionCatalog();
    }

    /**
     * Renders an entry by deconstructing it, so that the component set of each record shape is checked by
     * the compiler rather than by reflection.
     *
     * <p>A record pattern must name every component of the record it deconstructs, in declaration order.
     * The user arm therefore names four components and the administrator arm three, and this method stops
     * compiling if either record gains, loses or reorders a component. That is the reflection-free proof
     * that the administrator entry has no user-type component: were one added, the three-component pattern
     * below would no longer match its record.</p>
     *
     * <p>The final arm is reachable and load-bearing rather than defensive: passing a user entry to a test
     * that expects the administrator shape lands there, which is how the two shapes are shown to be
     * genuinely distinct instead of merely differently named.</p>
     *
     * @param option the entry to deconstruct, of either shape or of neither
     * @return the shape tag followed by every component value, separated by {@link #COMPONENT_SEPARATOR},
     *         or {@link #UNRECOGNISED_SHAPE_TAG} if the argument matches neither shape
     */
    private static String describeShape(final Object option) {
        return switch (option) {
            case MenuOptionCatalog.UserMenuOption(
                    int number, String label, String programName, String userType) ->
                    USER_SHAPE_TAG + COMPONENT_SEPARATOR + number + COMPONENT_SEPARATOR + label
                            + COMPONENT_SEPARATOR + programName + COMPONENT_SEPARATOR + userType;
            case MenuOptionCatalog.AdminMenuOption(int number, String label, String programName) ->
                    ADMIN_SHAPE_TAG + COMPONENT_SEPARATOR + number + COMPONENT_SEPARATOR + label
                            + COMPONENT_SEPARATOR + programName;
            default -> UNRECOGNISED_SHAPE_TAG;
        };
    }

    /**
     * Builds the fixed-width field image a label occupies in the legacy record: the visible text, space
     * filled on the right to the declared field width.
     *
     * <p>The fill count is derived from the expected label declared in this class, never from anything the
     * catalog returns, so the oracle stays independent. The fill is written as a repeat count so that no
     * trailing space in this source file has to survive an editor.</p>
     *
     * @param expectedLabel the visible label text, as this class declares it
     * @return the label at {@link #EXPECTED_LABEL_WIDTH} characters
     */
    private static String fieldImageOf(final String expectedLabel) {
        return expectedLabel + " ".repeat(EXPECTED_LABEL_WIDTH - expectedLabel.length());
    }

    /**
     * Supplies the ten user-menu entries as literals, in the order {@code app/cpy/COMEN02Y.cpy} writes them
     * out on lines 25 to 84.
     *
     * <p>Every value is written out here rather than derived, which is what makes this table an independent
     * oracle. Note in particular the fourth row: its label names a card <em>view</em> while its target
     * program is the card-detail program. The label is reproduced exactly as the copybook writes it and is
     * deliberately not harmonised with the program name.</p>
     *
     * @return one row per user-menu entry, as list position, option number, label and target program name
     */
    private static Stream<Arguments> userMenuRows() {
        return Stream.of(
                Arguments.of(0, 1, "Account View", "COACTVWC"),
                Arguments.of(1, 2, "Account Update", "COACTUPC"),
                Arguments.of(2, 3, "Credit Card List", "COCRDLIC"),
                Arguments.of(3, 4, "Credit Card View", "COCRDSLC"),
                Arguments.of(4, 5, "Credit Card Update", "COCRDUPC"),
                Arguments.of(5, 6, "Transaction List", "COTRN00C"),
                Arguments.of(6, 7, "Transaction View", "COTRN01C"),
                Arguments.of(7, OPTION_EIGHT_NUMBER, EXPECTED_OPTION_EIGHT_LABEL, "COTRN02C"),
                Arguments.of(8, 9, "Transaction Reports", "CORPT00C"),
                Arguments.of(9, 10, "Bill Payment", "COBIL00C"));
    }

    /**
     * Supplies the four administrator-menu entries as literals, in the order
     * {@code app/cpy/COADM02Y.cpy} writes them out on lines 24 to 42. No user-type code is supplied,
     * because the source record has no component to supply one from.
     *
     * @return one row per administrator-menu entry, as list position, option number, label and target
     *         program name
     */
    private static Stream<Arguments> adminMenuRows() {
        return Stream.of(
                Arguments.of(0, 1, "User List (Security)", "COUSR00C"),
                Arguments.of(1, 2, "User Add (Security)", "COUSR01C"),
                Arguments.of(2, 3, "User Update (Security)", "COUSR02C"),
                Arguments.of(3, 4, "User Delete (Security)", "COUSR03C"));
    }

    @ParameterizedTest(name = "user option {1} is {2} and targets {3}")
    @MethodSource("userMenuRows")
    @DisplayName("every user-menu entry reproduces its copybook number, label, target program and "
            + "standard-user code at its declared position")
    void everyUserMenuEntryReproducesItsCopybookRow(final int position, final int expectedNumber,
            final String expectedLabel, final String expectedProgramName) {

        final List<MenuOptionCatalog.UserMenuOption> options = catalog.userMenuOptions();

        assertThat(options)
                .as("the user catalog must publish every populated entry before a position is read")
                .hasSize(EXPECTED_USER_OPTION_COUNT);

        final MenuOptionCatalog.UserMenuOption option = options.get(position);

        assertThat(option.number()).as("option number at position %d", position).isEqualTo(expectedNumber);
        assertThat(option.label()).as("label of option %d", expectedNumber).isEqualTo(expectedLabel);
        assertThat(option.programName())
                .as("target program of option %d", expectedNumber)
                .isEqualTo(expectedProgramName)
                .hasSize(EXPECTED_PROGRAM_NAME_WIDTH);
        assertThat(option.userType())
                .as("user-type code of option %d", expectedNumber)
                .isEqualTo(EXPECTED_STANDARD_USER_TYPE_CODE);
        assertThat(option.paddedLabel())
                .as("fixed-width field image of the label of option %d", expectedNumber)
                .isEqualTo(fieldImageOf(expectedLabel))
                .hasSize(EXPECTED_LABEL_WIDTH);
        assertThat(catalog.findUserOption(expectedNumber))
                .as("looking option %d up by number must find the same entry", expectedNumber)
                .contains(option);
        assertThat(describeShape(option))
                .as("option %d must deconstruct into its four declared components", expectedNumber)
                .isEqualTo(USER_SHAPE_TAG + COMPONENT_SEPARATOR + expectedNumber + COMPONENT_SEPARATOR
                        + expectedLabel + COMPONENT_SEPARATOR + expectedProgramName + COMPONENT_SEPARATOR
                        + EXPECTED_STANDARD_USER_TYPE_CODE);
    }

    @ParameterizedTest(name = "administrator option {1} is {2} and targets {3}")
    @MethodSource("adminMenuRows")
    @DisplayName("every administrator-menu entry reproduces its copybook number, label and target program "
            + "at its declared position, and carries no user-type code")
    void everyAdminMenuEntryReproducesItsCopybookRow(final int position, final int expectedNumber,
            final String expectedLabel, final String expectedProgramName) {

        final List<MenuOptionCatalog.AdminMenuOption> options = catalog.adminMenuOptions();

        assertThat(options)
                .as("the administrator catalog must publish every populated entry before a position is read")
                .hasSize(EXPECTED_ADMIN_OPTION_COUNT);

        final MenuOptionCatalog.AdminMenuOption option = options.get(position);

        assertThat(option.number()).as("option number at position %d", position).isEqualTo(expectedNumber);
        assertThat(option.label())
                .as("label of administrator option %d", expectedNumber)
                .isEqualTo(expectedLabel)
                .contains(SECURITY_MARKER);
        assertThat(option.programName())
                .as("target program of administrator option %d", expectedNumber)
                .isEqualTo(expectedProgramName)
                .hasSize(EXPECTED_PROGRAM_NAME_WIDTH);
        assertThat(option.paddedLabel())
                .as("fixed-width field image of the label of administrator option %d", expectedNumber)
                .isEqualTo(fieldImageOf(expectedLabel))
                .hasSize(EXPECTED_LABEL_WIDTH);
        assertThat(catalog.findAdminOption(expectedNumber))
                .as("looking administrator option %d up by number must find the same entry", expectedNumber)
                .contains(option);
        assertThat(describeShape(option))
                .as("administrator option %d must deconstruct into its three declared components, with no "
                        + "fourth component to render", expectedNumber)
                .isEqualTo(ADMIN_SHAPE_TAG + COMPONENT_SEPARATOR + expectedNumber + COMPONENT_SEPARATOR
                        + expectedLabel + COMPONENT_SEPARATOR + expectedProgramName);
    }

    /**
     * Counts the components in a rendering produced by {@link #describeShape(Object)}.
     *
     * <p>The rendering is a shape tag followed by one separator per component, and no label, program name
     * or user-type code in either copybook contains the separator, so counting separators counts
     * components. This is how a test states the size of a record's component set without reflection: the
     * arity is fixed at compile time by the record patterns and merely confirmed here at run time.</p>
     *
     * @param rendered a rendering returned by {@link #describeShape(Object)}
     * @return the number of components the rendering carries
     */
    private static int renderedComponentCount(final String rendered) {
        int count = 0;
        int index = rendered.indexOf(COMPONENT_SEPARATOR);
        while (index >= 0) {
            count++;
            index = rendered.indexOf(COMPONENT_SEPARATOR, index + COMPONENT_SEPARATOR.length());
        }
        return count;
    }

    /**
     * The ten populated user-menu entries and the properties they hold collectively.
     */
    @Nested
    @DisplayName("The user menu: the ten options the main-menu copybook writes out")
    class UserCatalog {

        @Test
        @DisplayName("the catalog publishes exactly ten options, which is the population its count item "
                + "declares")
        void theCatalogPublishesExactlyTenOptions() {
            assertThat(catalog.userMenuOptions())
                    .as("published user options")
                    .isNotNull()
                    .hasSize(EXPECTED_USER_OPTION_COUNT)
                    .doesNotContainNull();
            assertThat(catalog.userMenuOptionCount())
                    .as("reported user option count")
                    .isEqualTo(EXPECTED_USER_OPTION_COUNT);
            assertThat(MenuOptionCatalog.USER_MENU_OPTION_COUNT)
                    .as("published user option count constant")
                    .isEqualTo(EXPECTED_USER_OPTION_COUNT);
        }

        @Test
        @DisplayName("the options are numbered one upwards with no gap, no renumbering and no sorting")
        void theOptionsAreNumberedOneUpwardsWithNoGap() {
            final List<MenuOptionCatalog.UserMenuOption> options = catalog.userMenuOptions();

            for (int position = 0; position < EXPECTED_USER_OPTION_COUNT; position++) {
                assertThat(options.get(position).number())
                        .as("option number at position %d", position)
                        .isEqualTo(position + 1);
            }
            assertThat(options.get(0).number()).as("first option number").isEqualTo(1);
            assertThat(options.get(EXPECTED_USER_OPTION_COUNT - 1).number())
                    .as("last option number")
                    .isEqualTo(EXPECTED_USER_OPTION_COUNT);
        }

        @Test
        @DisplayName("every label is the visible text without the trailing space fill its picture clause "
                + "would apply")
        void everyLabelIsPublishedWithoutItsTrailingSpaceFill() {
            for (final MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                assertThat(option.label())
                        .as("label of option %d", option.number())
                        .isNotBlank()
                        .doesNotStartWith(" ")
                        .doesNotEndWith(" ");
                assertThat(option.label().length())
                        .as("label of option %d must be shorter than its declared field", option.number())
                        .isLessThan(EXPECTED_LABEL_WIDTH);
            }
        }

        @Test
        @DisplayName("the first option's label is exactly its copybook text and carries no padding at all")
        void theFirstOptionLabelCarriesNoPadding() {
            final MenuOptionCatalog.UserMenuOption first = catalog.userMenuOptions().get(0);

            assertThat(first.label()).isEqualTo("Account View");
            assertThat(first.label().length()).isLessThan(EXPECTED_LABEL_WIDTH);
            assertThat(first.paddedLabel())
                    .isEqualTo(fieldImageOf("Account View"))
                    .hasSize(EXPECTED_LABEL_WIDTH)
                    .startsWith("Account View")
                    .endsWith(" ");
        }

        @Test
        @DisplayName("the fourth option keeps the card-view label its copybook writes, unharmonised with "
                + "the card-detail program it targets")
        void theFourthOptionKeepsItsCopybookLabel() {
            final MenuOptionCatalog.UserMenuOption fourth = catalog.userMenuOptions().get(3);

            assertThat(fourth.number()).isEqualTo(4);
            assertThat(fourth.label()).isEqualTo("Credit Card View").isNotEqualTo("Credit Card Detail");
            assertThat(fourth.programName()).isEqualTo("COCRDSLC");
        }

        @Test
        @DisplayName("every target program name occupies the full eight characters its picture clause "
                + "declares, and no two options target the same program")
        void everyTargetProgramNameOccupiesItsFullDeclaredWidth() {
            final List<String> programNames = new ArrayList<>();

            for (final MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                assertThat(option.programName())
                        .as("target program of option %d", option.number())
                        .isNotBlank()
                        .hasSize(EXPECTED_PROGRAM_NAME_WIDTH)
                        .doesNotContain(" ");
                programNames.add(option.programName());
            }
            assertThat(programNames)
                    .as("target program names")
                    .hasSize(EXPECTED_USER_OPTION_COUNT)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every entry deconstructs into exactly its four declared components")
        void everyEntryDeconstructsIntoFourComponents() {
            for (final MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                final String rendered = describeShape(option);

                assertThat(rendered).as("rendering of option %d", option.number())
                        .startsWith(USER_SHAPE_TAG)
                        .isNotEqualTo(UNRECOGNISED_SHAPE_TAG);
                assertThat(renderedComponentCount(rendered))
                        .as("component count of option %d", option.number())
                        .isEqualTo(EXPECTED_USER_COMPONENT_COUNT);
            }
        }
    }

    /**
     * The four populated administrator-menu entries and the properties they hold collectively.
     */
    @Nested
    @DisplayName("The administrator menu: the four options the administrator copybook writes out")
    class AdminCatalog {

        @Test
        @DisplayName("the catalog publishes exactly four options, which is the population its count item "
                + "declares")
        void theCatalogPublishesExactlyFourOptions() {
            assertThat(catalog.adminMenuOptions())
                    .as("published administrator options")
                    .isNotNull()
                    .hasSize(EXPECTED_ADMIN_OPTION_COUNT)
                    .doesNotContainNull();
            assertThat(catalog.adminMenuOptionCount())
                    .as("reported administrator option count")
                    .isEqualTo(EXPECTED_ADMIN_OPTION_COUNT);
            assertThat(MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT)
                    .as("published administrator option count constant")
                    .isEqualTo(EXPECTED_ADMIN_OPTION_COUNT);
        }

        @Test
        @DisplayName("the options are numbered one upwards with no gap and in copybook order")
        void theOptionsAreNumberedOneUpwardsInCopybookOrder() {
            final List<MenuOptionCatalog.AdminMenuOption> options = catalog.adminMenuOptions();

            for (int position = 0; position < EXPECTED_ADMIN_OPTION_COUNT; position++) {
                assertThat(options.get(position).number())
                        .as("administrator option number at position %d", position)
                        .isEqualTo(position + 1);
            }
            assertThat(options.get(0).number()).as("first administrator option number").isEqualTo(1);
            assertThat(options.get(EXPECTED_ADMIN_OPTION_COUNT - 1).number())
                    .as("last administrator option number")
                    .isEqualTo(EXPECTED_ADMIN_OPTION_COUNT);
        }

        @Test
        @DisplayName("every administrator label carries the security qualifier its copybook text includes")
        void everyAdministratorLabelCarriesTheSecurityQualifier() {
            for (final MenuOptionCatalog.AdminMenuOption option : catalog.adminMenuOptions()) {
                assertThat(option.label())
                        .as("label of administrator option %d", option.number())
                        .isNotBlank()
                        .contains(SECURITY_MARKER)
                        .endsWith(SECURITY_MARKER)
                        .doesNotStartWith(" ")
                        .doesNotEndWith(" ");
                assertThat(option.label().length())
                        .as("label of administrator option %d must fit its declared field", option.number())
                        .isLessThan(EXPECTED_LABEL_WIDTH);
            }
        }

        @Test
        @DisplayName("every administrator option targets a distinct eight-character user-administration "
                + "program")
        void everyAdministratorOptionTargetsADistinctProgram() {
            final List<String> programNames = new ArrayList<>();

            for (final MenuOptionCatalog.AdminMenuOption option : catalog.adminMenuOptions()) {
                assertThat(option.programName())
                        .as("target program of administrator option %d", option.number())
                        .isNotBlank()
                        .hasSize(EXPECTED_PROGRAM_NAME_WIDTH)
                        .doesNotContain(" ");
                programNames.add(option.programName());
            }
            assertThat(programNames)
                    .as("administrator target program names")
                    .hasSize(EXPECTED_ADMIN_OPTION_COUNT)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every entry deconstructs into exactly its three declared components, one fewer than "
                + "a user entry")
        void everyEntryDeconstructsIntoThreeComponents() {
            for (final MenuOptionCatalog.AdminMenuOption option : catalog.adminMenuOptions()) {
                final String rendered = describeShape(option);

                assertThat(rendered).as("rendering of administrator option %d", option.number())
                        .startsWith(ADMIN_SHAPE_TAG)
                        .isNotEqualTo(UNRECOGNISED_SHAPE_TAG);
                assertThat(renderedComponentCount(rendered))
                        .as("component count of administrator option %d", option.number())
                        .isEqualTo(EXPECTED_ADMIN_COMPONENT_COUNT);
            }
            assertThat(EXPECTED_USER_COMPONENT_COUNT - EXPECTED_ADMIN_COMPONENT_COUNT)
                    .as("a user entry carries exactly one component an administrator entry does not")
                    .isEqualTo(EXPECTED_USER_TYPE_WIDTH);
        }
    }

    /**
     * The positions the legacy redefining tables can address but never populate.
     *
     * <p>Those positions overlay storage beyond the group each table redefines, so the legacy program has
     * nothing defined to read there. The catalog must therefore report them absent rather than blank, and
     * must never publish a table capacity as though it were a population.</p>
     */
    @Nested
    @DisplayName("The surplus table slots: addressable by the legacy tables, populated by neither copybook")
    class SurplusSlots {

        @Test
        @DisplayName("the user catalog publishes its declared population and never its table capacity")
        void theUserCatalogPublishesItsPopulationAndNeverItsCapacity() {
            assertThat(catalog.userMenuOptionCount())
                    .as("reported user option count")
                    .isEqualTo(EXPECTED_USER_OPTION_COUNT)
                    .isNotEqualTo(USER_TABLE_DECLARED_CAPACITY);
            assertThat(catalog.userMenuOptions().size())
                    .as("size of the published user list")
                    .isEqualTo(EXPECTED_USER_OPTION_COUNT)
                    .isNotEqualTo(USER_TABLE_DECLARED_CAPACITY);
            assertThat(MenuOptionCatalog.USER_MENU_OPTION_COUNT)
                    .as("published user option count constant")
                    .isNotEqualTo(USER_TABLE_DECLARED_CAPACITY);
        }

        @Test
        @DisplayName("the administrator catalog publishes its declared population and never its table "
                + "capacity")
        void theAdminCatalogPublishesItsPopulationAndNeverItsCapacity() {
            assertThat(catalog.adminMenuOptionCount())
                    .as("reported administrator option count")
                    .isEqualTo(EXPECTED_ADMIN_OPTION_COUNT)
                    .isNotEqualTo(ADMIN_TABLE_DECLARED_CAPACITY);
            assertThat(catalog.adminMenuOptions().size())
                    .as("size of the published administrator list")
                    .isEqualTo(EXPECTED_ADMIN_OPTION_COUNT)
                    .isNotEqualTo(ADMIN_TABLE_DECLARED_CAPACITY);
            assertThat(MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT)
                    .as("published administrator option count constant")
                    .isNotEqualTo(ADMIN_TABLE_DECLARED_CAPACITY);
        }

        @Test
        @DisplayName("no value the catalog publishes anywhere is either table capacity")
        void noPublishedValueIsEitherTableCapacity() {
            final int[] publishedNumbers = {
                MenuOptionCatalog.USER_MENU_OPTION_COUNT,
                MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT,
                MenuOptionCatalog.OPTION_NUMBER_WIDTH,
                MenuOptionCatalog.OPTION_NUMBER_MAXIMUM,
                MenuOptionCatalog.OPTION_LABEL_WIDTH,
                MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH,
                MenuOptionCatalog.USER_OPTION_USER_TYPE_WIDTH,
                MenuOptionCatalog.USER_MENU_ENTRY_LENGTH,
                MenuOptionCatalog.ADMIN_MENU_ENTRY_LENGTH,
                catalog.userMenuOptionCount(),
                catalog.adminMenuOptionCount(),
                catalog.userMenuOptions().size(),
                catalog.adminMenuOptions().size(),
            };

            assertThat(publishedNumbers)
                    .as("every numeric value the catalog publishes")
                    .isNotEmpty()
                    .doesNotContain(USER_TABLE_DECLARED_CAPACITY, ADMIN_TABLE_DECLARED_CAPACITY);
        }

        @Test
        @DisplayName("no published option number reaches into the surplus range of either table")
        void noPublishedOptionNumberReachesTheSurplusRange() {
            for (final MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                assertThat(option.number())
                        .as("user option number")
                        .isBetween(1, EXPECTED_USER_OPTION_COUNT);
            }
            for (final MenuOptionCatalog.AdminMenuOption option : catalog.adminMenuOptions()) {
                assertThat(option.number())
                        .as("administrator option number")
                        .isBetween(1, EXPECTED_ADMIN_OPTION_COUNT);
            }
        }

        @Test
        @DisplayName("every surplus user slot reports an absent option rather than a blank placeholder")
        void everySurplusUserSlotReportsAnAbsentOption() {
            for (int slot = EXPECTED_USER_OPTION_COUNT + 1; slot <= USER_TABLE_DECLARED_CAPACITY; slot++) {
                final Optional<MenuOptionCatalog.UserMenuOption> found = catalog.findUserOption(slot);

                assertThat(found).as("surplus user slot %d", slot).isEmpty();
            }
            assertThat(catalog.findUserOption(EXPECTED_USER_OPTION_COUNT))
                    .as("the last populated user slot must still be found")
                    .isPresent();
        }

        @Test
        @DisplayName("every surplus administrator slot reports an absent option rather than a blank "
                + "placeholder")
        void everySurplusAdminSlotReportsAnAbsentOption() {
            for (int slot = EXPECTED_ADMIN_OPTION_COUNT + 1; slot <= ADMIN_TABLE_DECLARED_CAPACITY; slot++) {
                final Optional<MenuOptionCatalog.AdminMenuOption> found = catalog.findAdminOption(slot);

                assertThat(found).as("surplus administrator slot %d", slot).isEmpty();
            }
            assertThat(catalog.findAdminOption(EXPECTED_ADMIN_OPTION_COUNT))
                    .as("the last populated administrator slot must still be found")
                    .isPresent();
        }

        @Test
        @DisplayName("a slot number below one, or far beyond the declared field, reports an absent option "
                + "rather than failing")
        void anOutOfRangeSlotNumberReportsAnAbsentOption() {
            assertThat(catalog.findUserOption(0)).as("user slot zero").isEmpty();
            assertThat(catalog.findUserOption(-1)).as("negative user slot").isEmpty();
            assertThat(catalog.findUserOption(EXPECTED_OPTION_NUMBER_MAXIMUM))
                    .as("the highest slot a two-digit field can hold")
                    .isEmpty();
            assertThat(catalog.findUserOption(Integer.MAX_VALUE)).as("largest user slot").isEmpty();
            assertThat(catalog.findUserOption(Integer.MIN_VALUE)).as("smallest user slot").isEmpty();

            assertThat(catalog.findAdminOption(0)).as("administrator slot zero").isEmpty();
            assertThat(catalog.findAdminOption(-1)).as("negative administrator slot").isEmpty();
            assertThat(catalog.findAdminOption(EXPECTED_OPTION_NUMBER_MAXIMUM))
                    .as("the highest administrator slot a two-digit field can hold")
                    .isEmpty();
            assertThat(catalog.findAdminOption(Integer.MAX_VALUE))
                    .as("largest administrator slot")
                    .isEmpty();
            assertThat(catalog.findAdminOption(Integer.MIN_VALUE))
                    .as("smallest administrator slot")
                    .isEmpty();
        }
    }

    /**
     * The commented-out alternative label for user option 8.
     *
     * <p>The legacy source carries it as a COBOL comment on the line above the live label. It stays
     * inactive: publishing it would restrict an option the user menu genuinely offers to standard users,
     * which is a behaviour change the no-feature-expansion boundary forbids.</p>
     */
    @Nested
    @DisplayName("User option 8: only its live label, never the commented-out alternative")
    class InactiveOptionEightLabel {

        @Test
        @DisplayName("option 8 carries the live label from the uncommented literal and nothing else")
        void optionEightCarriesOnlyItsLiveLabel() {
            final MenuOptionCatalog.UserMenuOption option =
                    catalog.findUserOption(OPTION_EIGHT_NUMBER).orElseThrow();

            assertThat(option.number()).isEqualTo(OPTION_EIGHT_NUMBER);
            assertThat(option.label())
                    .as("live label of option 8")
                    .isEqualTo(EXPECTED_OPTION_EIGHT_LABEL)
                    .isNotEqualTo(INACTIVE_OPTION_EIGHT_LABEL)
                    .doesNotContain(ADMIN_ONLY_MARKER);
            assertThat(option.programName()).isEqualTo("COTRN02C");
        }

        @Test
        @DisplayName("option 8 is offered to standard users, so activating the alternative label would "
                + "change who may reach it")
        void optionEightIsOfferedToStandardUsers() {
            final MenuOptionCatalog.UserMenuOption option =
                    catalog.findUserOption(OPTION_EIGHT_NUMBER).orElseThrow();

            assertThat(option.userType())
                    .as("user-type code of option 8")
                    .isEqualTo(EXPECTED_STANDARD_USER_TYPE_CODE)
                    .isNotEqualTo(ADMIN_USER_TYPE_CODE);
            assertThat(catalog.userMenuOptions())
                    .as("option 8 belongs to the user menu, not the administrator menu")
                    .contains(option);
        }

        @Test
        @DisplayName("no user-menu value carries the inactive alternative label, in either its visible or "
                + "its fixed-width form")
        void noUserMenuValueCarriesTheInactiveAlternativeLabel() {
            for (final MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                assertThat(option.label())
                        .as("label of option %d", option.number())
                        .isNotEqualTo(INACTIVE_OPTION_EIGHT_LABEL)
                        .doesNotContain(ADMIN_ONLY_MARKER);
                assertThat(option.paddedLabel())
                        .as("field image of the label of option %d", option.number())
                        .isNotEqualTo(fieldImageOf(INACTIVE_OPTION_EIGHT_LABEL))
                        .doesNotContain(ADMIN_ONLY_MARKER);
            }
        }

        @Test
        @DisplayName("no administrator-menu value carries the inactive alternative label either, and every "
                + "administrator label is distinguished by its security qualifier")
        void noAdminMenuValueCarriesTheInactiveAlternativeLabel() {
            for (final MenuOptionCatalog.AdminMenuOption option : catalog.adminMenuOptions()) {
                assertThat(option.label())
                        .as("label of administrator option %d", option.number())
                        .isNotEqualTo(INACTIVE_OPTION_EIGHT_LABEL)
                        .doesNotContain(ADMIN_ONLY_MARKER)
                        .contains(SECURITY_MARKER);
                assertThat(option.paddedLabel())
                        .as("field image of the label of administrator option %d", option.number())
                        .doesNotContain(ADMIN_ONLY_MARKER);
            }
        }
    }

    /**
     * The administrator gate, asserted here because it is a property of these two catalogs alone.
     *
     * <p>The legacy gate reads a per-row user-type code and admits the row when the code is the
     * administrator one. Every user row carries the standard-user code, and no administrator row carries a
     * code at all, so the gate cannot be derived from a row and must come from the authenticated
     * principal.</p>
     */
    @Nested
    @DisplayName("The administrator gate: no row can grant administrator access to itself")
    class AdminGate {

        @Test
        @DisplayName("no user-menu option carries the administrator user-type code")
        void noUserMenuOptionCarriesTheAdministratorCode() {
            for (final MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                assertThat(option.userType())
                        .as("user-type code of option %d", option.number())
                        .isNotEqualTo(ADMIN_USER_TYPE_CODE)
                        .isNotEqualToIgnoringCase(ADMIN_USER_TYPE_CODE);
            }
        }

        @Test
        @DisplayName("every user-menu option carries the standard-user code as a raw one-character string, "
                + "not as an enumeration constant")
        void everyUserMenuOptionCarriesTheStandardUserCodeRaw() {
            for (final MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                final String userType = option.userType();

                assertThat(userType)
                        .as("user-type code of option %d", option.number())
                        .isEqualTo(EXPECTED_STANDARD_USER_TYPE_CODE)
                        .hasSize(EXPECTED_USER_TYPE_WIDTH)
                        .isNotInstanceOf(Enum.class);
            }
            assertThat(MenuOptionCatalog.STANDARD_USER_TYPE_CODE)
                    .as("published standard-user code constant")
                    .isEqualTo(EXPECTED_STANDARD_USER_TYPE_CODE)
                    .hasSize(EXPECTED_USER_TYPE_WIDTH)
                    .isNotInstanceOf(Enum.class);
        }

        @Test
        @DisplayName("the administrator catalog publishes no user-type code at all, so gating cannot be "
                + "read off a row")
        void theAdministratorCatalogPublishesNoUserTypeCode() {
            for (final MenuOptionCatalog.AdminMenuOption option : catalog.adminMenuOptions()) {
                final String rendered = describeShape(option);

                assertThat(renderedComponentCount(rendered))
                        .as("administrator option %d publishes three components and no fourth",
                                option.number())
                        .isEqualTo(EXPECTED_ADMIN_COMPONENT_COUNT)
                        .isNotEqualTo(EXPECTED_USER_COMPONENT_COUNT);
                assertThat(rendered)
                        .as("rendering of administrator option %d", option.number())
                        .doesNotEndWith(COMPONENT_SEPARATOR + EXPECTED_STANDARD_USER_TYPE_CODE)
                        .doesNotEndWith(COMPONENT_SEPARATOR + ADMIN_USER_TYPE_CODE)
                        .endsWith(COMPONENT_SEPARATOR + option.programName());
            }
        }

        @Test
        @DisplayName("a user entry and an administrator entry are never interchangeable, because their "
                + "component sets differ")
        void theTwoEntryShapesAreNeverInterchangeable() {
            final MenuOptionCatalog.UserMenuOption userOption = catalog.userMenuOptions().get(0);
            final MenuOptionCatalog.AdminMenuOption adminOption = catalog.adminMenuOptions().get(0);

            assertThat(describeShape(userOption)).startsWith(USER_SHAPE_TAG);
            assertThat(describeShape(adminOption)).startsWith(ADMIN_SHAPE_TAG);
            assertThat(describeShape(EXPECTED_STANDARD_USER_TYPE_CODE))
                    .as("a bare user-type code satisfies neither entry shape")
                    .isEqualTo(UNRECOGNISED_SHAPE_TAG);
            assertThat(userOption).as("a user entry never equals an administrator entry")
                    .isNotEqualTo(adminOption);
            assertThat(adminOption).as("an administrator entry never equals a user entry")
                    .isNotEqualTo(userOption);
            assertThat(catalog.adminMenuOptions().contains(userOption))
                    .as("the administrator catalog holds no user entry")
                    .isFalse();
            assertThat(catalog.userMenuOptions().contains(adminOption))
                    .as("the user catalog holds no administrator entry")
                    .isFalse();
        }
    }

    /**
     * The published lists, which are unmodifiable and which no caller can mutate into disagreement with
     * the copybooks they transcribe.
     */
    @Nested
    @DisplayName("Immutability: a caller can read the catalogs but can never change them")
    class Immutability {

        @Test
        @DisplayName("the user list rejects every structural mutation with an unsupported-operation failure")
        void theUserListRejectsEveryStructuralMutation() {
            final List<MenuOptionCatalog.UserMenuOption> options = catalog.userMenuOptions();
            final MenuOptionCatalog.UserMenuOption sample = options.get(0);
            final Comparator<MenuOptionCatalog.UserMenuOption> byLabel =
                    Comparator.comparing(MenuOptionCatalog.UserMenuOption::label);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("adding to the published user list")
                    .isThrownBy(() -> options.add(sample));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("removing from the published user list")
                    .isThrownBy(() -> options.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("replacing an entry of the published user list")
                    .isThrownBy(() -> options.set(0, sample));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("clearing the published user list")
                    .isThrownBy(options::clear);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("re-ordering the published user list")
                    .isThrownBy(() -> options.sort(byLabel));

            assertThat(catalog.userMenuOptions())
                    .as("the user catalog is unchanged after every rejected mutation")
                    .hasSize(EXPECTED_USER_OPTION_COUNT)
                    .isEqualTo(options);
        }

        @Test
        @DisplayName("the administrator list rejects every structural mutation with an "
                + "unsupported-operation failure")
        void theAdminListRejectsEveryStructuralMutation() {
            final List<MenuOptionCatalog.AdminMenuOption> options = catalog.adminMenuOptions();
            final MenuOptionCatalog.AdminMenuOption sample = options.get(0);
            final Comparator<MenuOptionCatalog.AdminMenuOption> byLabel =
                    Comparator.comparing(MenuOptionCatalog.AdminMenuOption::label);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("adding to the published administrator list")
                    .isThrownBy(() -> options.add(sample));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("removing from the published administrator list")
                    .isThrownBy(() -> options.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("replacing an entry of the published administrator list")
                    .isThrownBy(() -> options.set(0, sample));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("clearing the published administrator list")
                    .isThrownBy(options::clear);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("re-ordering the published administrator list")
                    .isThrownBy(() -> options.sort(byLabel));

            assertThat(catalog.adminMenuOptions())
                    .as("the administrator catalog is unchanged after every rejected mutation")
                    .hasSize(EXPECTED_ADMIN_OPTION_COUNT)
                    .isEqualTo(options);
        }

        @Test
        @DisplayName("two successive reads publish the same content, and so does a second catalog instance")
        void twoSuccessiveReadsPublishTheSameContent() {
            final List<MenuOptionCatalog.UserMenuOption> firstUserRead = catalog.userMenuOptions();
            final List<MenuOptionCatalog.UserMenuOption> secondUserRead = catalog.userMenuOptions();
            final List<MenuOptionCatalog.AdminMenuOption> firstAdminRead = catalog.adminMenuOptions();
            final List<MenuOptionCatalog.AdminMenuOption> secondAdminRead = catalog.adminMenuOptions();
            final MenuOptionCatalog secondCatalog = new MenuOptionCatalog();

            assertThat(secondUserRead)
                    .as("a second read of the user catalog")
                    .isEqualTo(firstUserRead)
                    .containsExactlyElementsOf(firstUserRead)
                    .hasSize(EXPECTED_USER_OPTION_COUNT);
            assertThat(secondAdminRead)
                    .as("a second read of the administrator catalog")
                    .isEqualTo(firstAdminRead)
                    .containsExactlyElementsOf(firstAdminRead)
                    .hasSize(EXPECTED_ADMIN_OPTION_COUNT);
            assertThat(secondCatalog.userMenuOptions())
                    .as("a second catalog instance publishes the same user options")
                    .isEqualTo(firstUserRead);
            assertThat(secondCatalog.adminMenuOptions())
                    .as("a second catalog instance publishes the same administrator options")
                    .isEqualTo(firstAdminRead);
        }

        @Test
        @DisplayName("a caller that copies a catalog and edits the copy leaves the catalog untouched")
        void editingACallerCopyLeavesTheCatalogUntouched() {
            final List<MenuOptionCatalog.UserMenuOption> publishedUsers = catalog.userMenuOptions();
            final List<MenuOptionCatalog.AdminMenuOption> publishedAdmins = catalog.adminMenuOptions();
            final List<MenuOptionCatalog.UserMenuOption> userCopy = new ArrayList<>(publishedUsers);
            final List<MenuOptionCatalog.AdminMenuOption> adminCopy = new ArrayList<>(publishedAdmins);

            userCopy.clear();
            adminCopy.clear();

            assertThat(userCopy).as("the caller's own copy").isEmpty();
            assertThat(adminCopy).as("the caller's own administrator copy").isEmpty();
            assertThat(catalog.userMenuOptions())
                    .as("the user catalog after the caller edited a copy")
                    .hasSize(EXPECTED_USER_OPTION_COUNT)
                    .isEqualTo(publishedUsers);
            assertThat(catalog.adminMenuOptions())
                    .as("the administrator catalog after the caller edited a copy")
                    .hasSize(EXPECTED_ADMIN_OPTION_COUNT)
                    .isEqualTo(publishedAdmins);
        }
    }

    /**
     * The declared field widths and entry lengths, which are what make the surplus-slot finding arithmetic
     * rather than opinion.
     */
    @Nested
    @DisplayName("Declared widths: the published field widths agree with the copybook picture clauses")
    class DeclaredWidths {

        @Test
        @DisplayName("every published component width matches the picture clause it comes from")
        void everyPublishedComponentWidthMatchesItsPictureClause() {
            assertThat(MenuOptionCatalog.OPTION_NUMBER_WIDTH)
                    .as("option-number width")
                    .isEqualTo(EXPECTED_OPTION_NUMBER_WIDTH);
            assertThat(MenuOptionCatalog.OPTION_LABEL_WIDTH)
                    .as("option-name width")
                    .isEqualTo(EXPECTED_LABEL_WIDTH);
            assertThat(MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH)
                    .as("target-program-name width")
                    .isEqualTo(EXPECTED_PROGRAM_NAME_WIDTH);
            assertThat(MenuOptionCatalog.USER_OPTION_USER_TYPE_WIDTH)
                    .as("user-type width")
                    .isEqualTo(EXPECTED_USER_TYPE_WIDTH);
            assertThat(MenuOptionCatalog.OPTION_NUMBER_MAXIMUM)
                    .as("highest value a two-digit option-number field can hold")
                    .isEqualTo(EXPECTED_OPTION_NUMBER_MAXIMUM);
        }

        @Test
        @DisplayName("the two entry lengths differ by exactly the one byte of the user-type code")
        void theTwoEntryLengthsDifferByExactlyTheUserTypeByte() {
            assertThat(MenuOptionCatalog.USER_MENU_ENTRY_LENGTH)
                    .as("user entry length")
                    .isEqualTo(EXPECTED_USER_ENTRY_LENGTH);
            assertThat(MenuOptionCatalog.ADMIN_MENU_ENTRY_LENGTH)
                    .as("administrator entry length")
                    .isEqualTo(EXPECTED_ADMIN_ENTRY_LENGTH);
            assertThat(MenuOptionCatalog.USER_MENU_ENTRY_LENGTH
                    - MenuOptionCatalog.ADMIN_MENU_ENTRY_LENGTH)
                    .as("the difference is the user-type byte and nothing else")
                    .isEqualTo(EXPECTED_USER_TYPE_WIDTH);
            assertThat(EXPECTED_OPTION_NUMBER_WIDTH + EXPECTED_LABEL_WIDTH + EXPECTED_PROGRAM_NAME_WIDTH
                    + EXPECTED_USER_TYPE_WIDTH)
                    .as("a user entry is the sum of its four declared component widths")
                    .isEqualTo(EXPECTED_USER_ENTRY_LENGTH);
            assertThat(EXPECTED_OPTION_NUMBER_WIDTH + EXPECTED_LABEL_WIDTH + EXPECTED_PROGRAM_NAME_WIDTH)
                    .as("an administrator entry is the sum of its three declared component widths")
                    .isEqualTo(EXPECTED_ADMIN_ENTRY_LENGTH);
        }

        @Test
        @DisplayName("each redefining table overruns the group it redefines, which is why the surplus slots "
                + "are undefined overlay storage rather than blank entries")
        void eachRedefiningTableOverrunsTheGroupItRedefines() {
            assertThat(MenuOptionCatalog.USER_MENU_ENTRY_LENGTH * catalog.userMenuOptionCount())
                    .as("bytes the populated user entries occupy")
                    .isEqualTo(EXPECTED_USER_DATA_GROUP_LENGTH);
            assertThat(EXPECTED_USER_ENTRY_LENGTH * USER_TABLE_DECLARED_CAPACITY)
                    .as("bytes the redefining user table spans")
                    .isEqualTo(EXPECTED_USER_TABLE_SPAN);
            assertThat(EXPECTED_USER_TABLE_SPAN - EXPECTED_USER_DATA_GROUP_LENGTH)
                    .as("bytes by which the user table overruns the group it redefines")
                    .isEqualTo(EXPECTED_USER_TABLE_SURPLUS_BYTES)
                    .isPositive();

            assertThat(MenuOptionCatalog.ADMIN_MENU_ENTRY_LENGTH * catalog.adminMenuOptionCount())
                    .as("bytes the populated administrator entries occupy")
                    .isEqualTo(EXPECTED_ADMIN_DATA_GROUP_LENGTH);
            assertThat(EXPECTED_ADMIN_ENTRY_LENGTH * ADMIN_TABLE_DECLARED_CAPACITY)
                    .as("bytes the redefining administrator table spans")
                    .isEqualTo(EXPECTED_ADMIN_TABLE_SPAN);
            assertThat(EXPECTED_ADMIN_TABLE_SPAN - EXPECTED_ADMIN_DATA_GROUP_LENGTH)
                    .as("bytes by which the administrator table overruns the group it redefines")
                    .isEqualTo(EXPECTED_ADMIN_TABLE_SURPLUS_BYTES)
                    .isPositive();
        }
    }

    /**
     * The component validation both entry types apply, which is what keeps a value that could not occupy
     * its legacy field out of a catalog in the first place.
     *
     * <p>Each failure names the legacy field it guards, so a rejection points at its own authority rather
     * than at a Java property name. The boundary cases matter as much as the failures: a value that fills
     * its field exactly is accepted, because COBOL space-fills a short alphanumeric value into its field
     * rather than rejecting it, and an option number is bounded by the digits of its field rather than by
     * the current population.</p>
     */
    @Nested
    @DisplayName("Component validation: a value that could not occupy its legacy field is refused")
    class ComponentValidation {

        @Test
        @DisplayName("an option number outside the range a two-digit field can hold is refused by both "
                + "entry types")
        void anOptionNumberOutsideTheDeclaredFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a zero user-option number")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            0, "Account View", "COACTVWC", EXPECTED_STANDARD_USER_TYPE_CODE))
                    .withMessageContaining("CDEMO-MENU-OPT-NUM");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a user-option number beyond a two-digit field")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            EXPECTED_OPTION_NUMBER_MAXIMUM + 1, "Account View", "COACTVWC",
                            EXPECTED_STANDARD_USER_TYPE_CODE))
                    .withMessageContaining("CDEMO-MENU-OPT-NUM");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a zero administrator-option number")
                    .isThrownBy(() -> new MenuOptionCatalog.AdminMenuOption(
                            0, "User List (Security)", "COUSR00C"))
                    .withMessageContaining("CDEMO-ADMIN-OPT-NUM");
        }

        @Test
        @DisplayName("an absent, blank or over-wide option name is refused, and the failure names the "
                + "option-name field")
        void anUnusableOptionNameIsRefused() {
            final String overWideLabel = "X".repeat(EXPECTED_LABEL_WIDTH + 1);

            assertThatExceptionOfType(NullPointerException.class)
                    .as("an absent user-option name")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, null, "COACTVWC", EXPECTED_STANDARD_USER_TYPE_CODE))
                    .withMessageContaining("CDEMO-MENU-OPT-NAME");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a blank user-option name")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, " ".repeat(EXPECTED_OPTION_NUMBER_WIDTH), "COACTVWC",
                            EXPECTED_STANDARD_USER_TYPE_CODE))
                    .withMessageContaining("CDEMO-MENU-OPT-NAME");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a user-option name wider than its declared field")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, overWideLabel, "COACTVWC", EXPECTED_STANDARD_USER_TYPE_CODE))
                    .withMessageContaining("CDEMO-MENU-OPT-NAME");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an administrator-option name wider than its declared field")
                    .isThrownBy(() -> new MenuOptionCatalog.AdminMenuOption(1, overWideLabel, "COUSR00C"))
                    .withMessageContaining("CDEMO-ADMIN-OPT-NAME");
        }

        @Test
        @DisplayName("an absent or over-wide target program name is refused, and the failure names the "
                + "program-name field")
        void anUnusableTargetProgramNameIsRefused() {
            final String overWideProgramName = "X".repeat(EXPECTED_PROGRAM_NAME_WIDTH + 1);

            assertThatExceptionOfType(NullPointerException.class)
                    .as("an absent user target program name")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, "Account View", null, EXPECTED_STANDARD_USER_TYPE_CODE))
                    .withMessageContaining("CDEMO-MENU-OPT-PGMNAME");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a user target program name wider than its declared field")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, "Account View", overWideProgramName, EXPECTED_STANDARD_USER_TYPE_CODE))
                    .withMessageContaining("CDEMO-MENU-OPT-PGMNAME");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an administrator target program name wider than its declared field")
                    .isThrownBy(() -> new MenuOptionCatalog.AdminMenuOption(
                            1, "User List (Security)", overWideProgramName))
                    .withMessageContaining("CDEMO-ADMIN-OPT-PGMNAME");
        }

        @Test
        @DisplayName("a user-type code that is absent or not exactly one character is refused, while its "
                + "value is never interpreted")
        void anUnusableUserTypeCodeIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .as("an absent user-type code")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, "Account View", "COACTVWC", null))
                    .withMessageContaining("CDEMO-MENU-OPT-USRTYPE");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an empty user-type code")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, "Account View", "COACTVWC", ""))
                    .withMessageContaining("CDEMO-MENU-OPT-USRTYPE");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a user-type code wider than one character")
                    .isThrownBy(() -> new MenuOptionCatalog.UserMenuOption(
                            1, "Account View", "COACTVWC",
                            "X".repeat(EXPECTED_USER_TYPE_WIDTH + 1)))
                    .withMessageContaining("CDEMO-MENU-OPT-USRTYPE");
        }

        @Test
        @DisplayName("an unrecognised one-character user-type code is accepted untouched, because the "
                + "catalog publishes the code raw and never interprets it")
        void anUnrecognisedUserTypeCodeIsAcceptedUntouched() {
            final String unknownCode = "Z";
            final MenuOptionCatalog.UserMenuOption option =
                    new MenuOptionCatalog.UserMenuOption(1, "Account View", "COACTVWC", unknownCode);

            assertThat(option.userType())
                    .as("an unrecognised code passes through for the service layer to interpret")
                    .isEqualTo(unknownCode)
                    .hasSize(EXPECTED_USER_TYPE_WIDTH);
            assertThat(catalog.userMenuOptions())
                    .as("and no such entry is present in the published catalog")
                    .doesNotContain(option);
        }

        @Test
        @DisplayName("a value that fills its field exactly is accepted, and its field image is that value "
                + "with no fill at all")
        void aValueThatFillsItsFieldExactlyIsAccepted() {
            final String exactWidthLabel = "X".repeat(EXPECTED_LABEL_WIDTH);
            final MenuOptionCatalog.UserMenuOption userOption = new MenuOptionCatalog.UserMenuOption(
                    EXPECTED_OPTION_NUMBER_MAXIMUM, exactWidthLabel, "COACTVWC",
                    EXPECTED_STANDARD_USER_TYPE_CODE);
            final MenuOptionCatalog.AdminMenuOption adminOption = new MenuOptionCatalog.AdminMenuOption(
                    EXPECTED_OPTION_NUMBER_MAXIMUM, exactWidthLabel, "COUSR00C");

            assertThat(userOption.number()).isEqualTo(EXPECTED_OPTION_NUMBER_MAXIMUM);
            assertThat(userOption.label()).isEqualTo(exactWidthLabel).hasSize(EXPECTED_LABEL_WIDTH);
            assertThat(userOption.paddedLabel())
                    .as("a label that already fills its field is returned unchanged")
                    .isEqualTo(exactWidthLabel)
                    .hasSize(EXPECTED_LABEL_WIDTH)
                    .doesNotEndWith(" ");
            assertThat(adminOption.paddedLabel())
                    .as("the administrator entry pads by the same rule")
                    .isEqualTo(exactWidthLabel)
                    .hasSize(EXPECTED_LABEL_WIDTH);
        }
    }
}
