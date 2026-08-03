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
package com.carddemo.api.dto;

import com.carddemo.config.MenuOptionCatalog;
import com.carddemo.service.MessageCatalogService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MenuResponse}, the single outbound contract serving both legacy menu
 * transactions: {@code CM00}, the user main menu driven by {@code app/cbl/COMEN01C.cbl}, and
 * {@code CA00}, the administrative menu driven by {@code app/cbl/COADM01C.cbl}.
 *
 * <p><strong>A pure unit test.</strong> No application context is started, no container is launched,
 * no database is opened and no framework type is referenced. Every instance is constructed directly.
 * Where the wire shape is what is under test, serialisation goes through a mapper built by hand in
 * {@link #moduleEquivalentMapper()} to match the six serialisation settings the module declares in
 * {@code src/main/resources/application.yml}, so the shape asserted here is the shape the running
 * application produces.
 *
 * <p><strong>Every expectation is an independent oracle.</strong> The constants below are restated
 * from the legacy artefacts rather than read back from the class under test, so a change to the
 * contract fails a test instead of quietly redefining the expectation: {@code app/cpy/COTTL01Y.cpy}
 * for the three forty-character titles, {@code app/cpy/CSMSG01Y.cpy} for the two fifty-character
 * common messages, {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy} for the two option
 * catalogs and their counts, {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} for
 * the header items and their widths, and {@code app/cbl/COMEN01C.cbl} with
 * {@code app/cbl/COADM01C.cbl} for the message texts and the two screen identities.
 *
 * <h2>Contract, not defect</h2>
 *
 * <p>Five properties of this contract look like mistakes and are the observable legacy behaviour.
 * Each is asserted deliberately, and each is recorded as a translation decision rather than
 * corrected.
 *
 * <ol>
 *   <li><strong>Twelve screen slots against ten and four catalog entries.</strong> Both symbolic maps
 *       lay out twelve option fields, and both option table views are dimensioned larger than their
 *       counts, so surplus blank capacity exists on both menus. The counts are the contract and the
 *       capacities are not published at all, so no blank row can ever be emitted.</li>
 *   <li><strong>Two option shapes that are never unified.</strong> The user catalog entry is one byte
 *       wider than the administrative one because it declares a user-type code the administrative
 *       entry has no equivalent for. {@link MenuResponse.UserMenuOption} and
 *       {@link MenuResponse.AdminMenuOption} therefore remain two unrelated record types.</li>
 *   <li><strong>The missing space in the user menu's coming-soon text.</strong> The user program
 *       inserts the selected option's leading word between a prefix and a suffix and leaves no space
 *       before the suffix, so the rendered text runs the two together. The administrative program
 *       comments the name operand out and renders the two parts alone. Both texts are asserted byte
 *       for byte and neither is normalised into the other.</li>
 *   <li><strong>Two inactive commented-out alternatives that must stay inactive.</strong> One at line
 *       21 of {@code app/cpy/COTTL01Y.cpy} for the second title line, one at line 69 of
 *       {@code app/cpy/COMEN02Y.cpy} for option 8's label. Both are asserted absent: reviving either
 *       would be feature expansion.</li>
 *   <li><strong>The forty-character padded titles.</strong> Their leading and trailing spaces are
 *       content, because the legacy fields are fixed-width and space-significant. Nothing here trims,
 *       strips, collapses, re-centres, re-pads or case-folds a value.</li>
 * </ol>
 *
 * <h2>Two places where the contract differs from a first reading of the screens</h2>
 *
 * <p>Both were settled by reading the production type rather than by assuming, and both are asserted
 * as the production type actually declares them.
 *
 * <ol>
 *   <li><strong>An option row publishes two items, not four and three.</strong> The user catalog
 *       entry declares four elementary items and the administrative entry three, but the target
 *       program name is dispatch metadata and the user-type code is an authorization input, and
 *       neither is screen content: both maps render a row as its number and its label, and the
 *       operator selects a row by typing its number. Both are therefore withheld from the wire and
 *       remain with the configuration-layer catalog, which carries every item of every entry. The
 *       guarantee that the two shapes never merge accordingly rests on <em>type identity</em> - two
 *       unrelated records with no shared supertype and no conversion between them - which is a
 *       stronger property than a difference in component lists, because it does not decay if the
 *       lists ever coincide. The absence of a user-type code on <em>either</em> shape is asserted
 *       below, as is the absence of any dispatch target from the payload.</li>
 *   <li><strong>An absent option collection is absent, not empty.</strong> The two option components
 *       answer a different question than their contents do: a user-menu response has no
 *       administrative collection <em>at all</em>, which is a different statement from an
 *       administrative menu listing nothing. Construction therefore admits exactly one collection -
 *       neither both nor neither - and stores the other as absent so that non-null inclusion omits it
 *       from the payload entirely. A present collection is detached with copy-of semantics and is
 *       unmodifiable; an absent one is not silently promoted to an empty list.</li>
 * </ol>
 *
 * <h2>Deliberate exclusions</h2>
 *
 * <p>This test imports no configuration-layer type, so the option catalog bean is never reached for:
 * this package sits above the configuration layer in the dependency direction and importing it would
 * invert the layering. It imports no domain enumeration, so the option row's user-type byte is treated
 * as the raw character it is. It imports no string utility, because the legacy blank-to-zero fill over
 * the right-justified two-character entry field belongs to the menu service and is asserted absent
 * here instead. It imports no date or time type, because every rendered header item crosses as text at
 * its measured width. And it performs no runtime type introspection of any kind: the component
 * inventory and its order are demonstrated through the serialised shape, the static types through
 * assignment, and immutability through construction.
 *
 * <p><strong>Provenance.</strong> Every citation above resolves against commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} of the read-only legacy estate. The release stamps
 * of the two option copybooks differ - {@code app/cpy/COADM02Y.cpy} line 50 carries a later stamp than
 * the estate-wide one - which is why no stamp is a constant anywhere in this module and provenance
 * lives in documentation and in commentary such as this. No picture clause, table declaration or
 * procedural statement is transcribed; only external contract text is reproduced.
 *
 * @see MenuResponse
 */
@DisplayName("MenuResponse - the shared outbound contract of the user and administrative menus")
class MenuResponseTest {

    /*
     * THE CANONICAL OWNERS, READ HERE RATHER THAN RE-DECLARED.
     *
     * The response contract deliberately declares no title text and no option catalogue: the titles
     * are owned by com.carddemo.service.MessageCatalogService and the rows by
     * com.carddemo.config.MenuOptionCatalog, and a second declaration anywhere would be a second
     * authority that can drift from the first. This suite therefore takes its expectations from those
     * owners, which is what makes an assertion here evidence about the module rather than evidence
     * about a copy of it. A test may read any layer; the contract under test may not.
     */

    /** The first screen title line, as its owner publishes it at its full declared width. */
    private static final String SCREEN_TITLE_LINE_1 = MessageCatalogService.CCDA_TITLE01;

    /** The second screen title line, as its owner publishes it at its full declared width. */
    private static final String SCREEN_TITLE_LINE_2 = MessageCatalogService.CCDA_TITLE02;

    /** The forty-character acknowledgement of the title copybook, as its owner publishes it. */
    private static final String SCREEN_TITLE_THANK_YOU = MessageCatalogService.CCDA_THANK_YOU;

    /**
     * The ten user rows projected exactly as the producer projects them: number and label only.
     *
     * <p>The target program name and the one-character user-type code the catalogue also holds are
     * dispatch and authorization inputs, so they stay behind and are not published on the wire.</p>
     */
    private static final List<MenuResponse.UserMenuOption> CANONICAL_USER_MENU_OPTIONS =
            new MenuOptionCatalog().userMenuOptions().stream()
                    .map(row -> new MenuResponse.UserMenuOption(row.number(), row.label()))
                    .toList();

    /** The four administrative rows projected the same way, number and label only. */
    private static final List<MenuResponse.AdminMenuOption> CANONICAL_ADMIN_MENU_OPTIONS =
            new MenuOptionCatalog().adminMenuOptions().stream()
                    .map(row -> new MenuResponse.AdminMenuOption(row.number(), row.label()))
                    .toList();

    // ---------------------------------------------------------------------------------------------
    // Independent oracles: the forty-character title family, app/cpy/COTTL01Y.cpy
    // ---------------------------------------------------------------------------------------------

    /** Declared width of both title lines and of the courtesy title: forty characters. */
    private static final int ORACLE_SCREEN_TITLE_WIDTH = 40;

    /** The first title line, line 19, with its six leading and seven trailing spaces intact. */
    private static final String ORACLE_TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

    /** The active second title line, line 22, with fourteen leading and eighteen trailing spaces. */
    private static final String ORACLE_TITLE_LINE_2 = "              CardDemo                  ";

    /** The courtesy title, line 24, ending in one significant trailing space. */
    private static final String ORACLE_TITLE_THANK_YOU = "Thank you for using CCDA application... ";

    /**
     * The alternative second title line commented out at line 21, quoted for one purpose only: to
     * assert it never became the contract. Reviving it would change what both menu screens display.
     */
    private static final String ORACLE_INACTIVE_TITLE_LINE_2 =
            "  Credit Card Demo Application (CCDA)   ";

    // ---------------------------------------------------------------------------------------------
    // Independent oracles: the fifty-character common-message family, app/cpy/CSMSG01Y.cpy
    // ---------------------------------------------------------------------------------------------

    /** Declared field width of the two common messages: fifty characters. */
    private static final int ORACLE_COMMON_MESSAGE_WIDTH = 50;

    /**
     * The common courtesy message exactly as its copybook literal is written - forty-nine characters,
     * one short of the fifty-character field that holds it. Quoted at literal width so that
     * {@link #ORACLE_COMMON_MESSAGE_THANK_YOU} below can demonstrate the completing space, and so that
     * measuring the literal instead of the field is visibly the wrong thing to do.
     */
    private static final String ORACLE_COMMON_MESSAGE_THANK_YOU_LITERAL =
            "Thank you for using CardDemo application...      ";

    /** The same message at its full declared field width: the literal plus its completing space. */
    private static final String ORACLE_COMMON_MESSAGE_THANK_YOU =
            ORACLE_COMMON_MESSAGE_THANK_YOU_LITERAL + " ";

    /** The common unmapped-key message as its copybook literal is written: forty-nine characters. */
    private static final String ORACLE_COMMON_MESSAGE_INVALID_KEY_LITERAL =
            "Invalid key pressed. Please see below...         ";

    /** The same message at its full declared field width. */
    private static final String ORACLE_COMMON_MESSAGE_INVALID_KEY =
            ORACLE_COMMON_MESSAGE_INVALID_KEY_LITERAL + " ";

    // ---------------------------------------------------------------------------------------------
    // Independent oracles: the two option catalogs
    // ---------------------------------------------------------------------------------------------

    /** The user option count declared at line 21 of {@code app/cpy/COMEN02Y.cpy}. */
    private static final int ORACLE_USER_MENU_OPTION_COUNT = 10;

    /** The administrative option count declared at line 20 of {@code app/cpy/COADM02Y.cpy}. */
    private static final int ORACLE_ADMIN_MENU_OPTION_COUNT = 4;

    /** Declared width of an option label in both catalogs: thirty-five characters. */
    private static final int ORACLE_OPTION_LABEL_WIDTH = 35;

    /** Declared width of an option number in both catalogs: two digits. */
    private static final int ORACLE_OPTION_NUMBER_WIDTH = 2;

    /** The ten user labels in catalog declaration order, in the display form the catalog carries. */
    private static final List<String> ORACLE_USER_LABELS = List.of(
            "Account View", "Account Update", "Credit Card List", "Credit Card View",
            "Credit Card Update", "Transaction List", "Transaction View", "Transaction Add",
            "Transaction Reports", "Bill Payment");

    /** The four administrative labels in catalog declaration order, in display form. */
    private static final List<String> ORACLE_ADMIN_LABELS = List.of(
            "User List (Security)", "User Add (Security)", "User Update (Security)",
            "User Delete (Security)");

    /** The active label of option 8, line 70 of {@code app/cpy/COMEN02Y.cpy}, in display form. */
    private static final String ORACLE_OPTION_EIGHT_LABEL = "Transaction Add";

    /**
     * The alternative label commented out at line 69 of {@code app/cpy/COMEN02Y.cpy}, quoted only to
     * assert its absence. It reads as a role restriction, so reviving it would change who may add a
     * transaction - feature expansion rather than migration.
     */
    private static final String ORACLE_INACTIVE_OPTION_EIGHT_LABEL = "Transaction Add (Admin Only)";

    /** The label of option 8 at its declared width, to prove a padded value is not re-trimmed. */
    private static final String ORACLE_OPTION_EIGHT_LABEL_PADDED = "Transaction Add                    ";

    // ---------------------------------------------------------------------------------------------
    // Independent oracles: the two screen identities and the header widths
    // ---------------------------------------------------------------------------------------------

    /** Width of the transaction identifier on both maps, line 24: four. */
    private static final int ORACLE_TRANSACTION_NAME_WIDTH = 4;

    /** Width of the displayed program name on both maps, line 42: eight. */
    private static final int ORACLE_PROGRAM_NAME_WIDTH = 8;

    /** Width of the rendered date on both maps, line 36: eight. */
    private static final int ORACLE_CURRENT_DATE_WIDTH = 8;

    /**
     * Width of the rendered time on both menu maps, line 54: eight.
     *
     * <p>The sign-on map is the one place in the estate where the same item is nine characters wide.
     * The two figures are deliberately separate and are never normalised into one: widening this one
     * would put a character on the menu screens that neither menu map has room for, and narrowing the
     * other would truncate what sign-on renders. The sign-on contract declares its own figure.
     */
    private static final int ORACLE_CURRENT_TIME_WIDTH = 8;

    /** Width of the operator's option entry field on both maps, line 132: two. */
    private static final int ORACLE_SELECTED_OPTION_WIDTH = 2;

    /** The user menu's transaction identifier, {@code app/cbl/COMEN01C.cbl} line 37. */
    private static final String ORACLE_USER_MENU_TRANSACTION_NAME = "CM00";

    /** The user menu's own program name, {@code app/cbl/COMEN01C.cbl} line 36. */
    private static final String ORACLE_USER_MENU_PROGRAM_NAME = "COMEN01C";

    /** The administrative menu's transaction identifier, {@code app/cbl/COADM01C.cbl} line 37. */
    private static final String ORACLE_ADMIN_MENU_TRANSACTION_NAME = "CA00";

    /** The administrative menu's own program name, {@code app/cbl/COADM01C.cbl} line 36. */
    private static final String ORACLE_ADMIN_MENU_PROGRAM_NAME = "COADM01C";

    // ---------------------------------------------------------------------------------------------
    // Independent oracles: the message line and the four texts that reach it
    // ---------------------------------------------------------------------------------------------

    /**
     * Width of the working-storage field both programs hold the outgoing message in: eighty
     * characters. This is the bound a caller is measured against.
     */
    private static final int ORACLE_MESSAGE_WIDTH = 80;

    /**
     * Width of the screen field the message is rendered into on both menu maps, line 138:
     * seventy-eight characters.
     *
     * <p>Recorded because a byte comparison against what the legacy screen displayed is bounded by
     * this narrower figure, never by {@link #ORACLE_MESSAGE_WIDTH}. Thirteen maps in the estate declare
     * this field at seventy-eight; two card maps declare theirs at eighty instead, and the separate
     * informational line is forty characters wide on the card maps against forty-five on the two
     * account maps. None of those widths is normalised against any other - each screen's field is its
     * own - and this contract applies none of them as a transformation: no message is ever shortened
     * to fit.
     */
    private static final int ORACLE_SCREEN_MESSAGE_FIELD_WIDTH = 78;

    /** The twelve-character prefix both programs assemble the coming-soon text from. */
    private static final String ORACLE_COMING_SOON_PREFIX = "This option ";

    /** The eighteen-character suffix both programs assemble the coming-soon text from. */
    private static final String ORACLE_COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * The user menu's coming-soon text, as {@code app/cbl/COMEN01C.cbl} lines 159 to 162 render it.
     *
     * <p>The option name is inserted between prefix and suffix delimited by a space, which takes the
     * name's leading word only and leaves nothing between it and the suffix. The rendered text
     * therefore runs the word straight into the suffix. <strong>The absent space is a source defect and
     * is nonetheless the observable contract</strong>, so it is reproduced here rather than repaired,
     * and the value below is deliberately assembled the way the program assembles it so that the
     * absence is visible instead of buried inside a hand-typed literal.
     */
    private static final String ORACLE_USER_COMING_SOON =
            ORACLE_COMING_SOON_PREFIX + "Account" + ORACLE_COMING_SOON_SUFFIX;

    /**
     * The administrative menu's coming-soon text, as {@code app/cbl/COADM01C.cbl} lines 149 to 152
     * render it: the identical construction with the name operand commented out, so no name appears
     * and the prefix's own trailing space separates the two parts correctly.
     *
     * <p>The two texts are two distinct behaviours and are never reconciled into one.
     */
    private static final String ORACLE_ADMIN_COMING_SOON =
            ORACLE_COMING_SOON_PREFIX + ORACLE_COMING_SOON_SUFFIX;

    /** The rejection both programs emit for an out-of-range entry: thirty-seven characters. */
    private static final String ORACLE_INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The access-denied text only the user program emits, as part of its user-type gate: thirty-three
     * characters ending in a significant trailing space, with no administrative counterpart.
     */
    private static final String ORACLE_ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    // ---------------------------------------------------------------------------------------------
    // Fixture values
    // ---------------------------------------------------------------------------------------------

    /** A rendered date at the map's own width, carried as text so a leading zero survives. */
    private static final String RENDERED_DATE = "01/31/24";

    /** A rendered time at the map's own width, likewise carried as text. */
    private static final String RENDERED_TIME = "09:27:53";

    /** An opaque declarative route label. Route vocabulary belongs to the navigation service. */
    private static final String OPAQUE_ROUTE = "account-view";

    /** A second, unrelated opaque route label, to show the component polices no vocabulary. */
    private static final String OTHER_OPAQUE_ROUTE = "no-such-route-exists-here";

    /** An opaque screen field label for the focus hint: a name, never a coordinate. */
    private static final String FOCUS_FIELD = "OPTION";

    /**
     * An account identifier whose leading zeros are contractual, at the width the navigation state
     * declares. Every legacy numeric <em>identifier</em> crosses as text for exactly this reason.
     */
    private static final String LEADING_ZERO_ACCOUNT_ID = "00000000001";

    /** A synthetic user identifier. No credential value appears anywhere in this test. */
    private static final String SYNTHETIC_USER_ID = "TESTUSR1";

    /** The one-character user-type code, carried raw. A character and never an enumeration here. */
    private static final String RAW_USER_TYPE_CODE = "U";

    /**
     * The complete property-name list of a fully populated user-menu payload, in the order the record
     * declares its components. Asserting the whole list at once is what excludes every artefact that
     * must not appear: no table capacity, no dispatch target, no user-type code, no problem-document
     * member, and none of the control-byte, filler, coordinate or terminal-attribute items a symbolic
     * map carries alongside each field.
     */
    private static final List<String> USER_MENU_PAYLOAD_PROPERTIES = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "userMenuOptions", "selectedOption", "message", "messageSeverity", "errorFlag",
            "focusScreenFieldId", "nextRoute", "navigationContext");

    /** The same list for an administrative-menu payload: the user collection is absent instead. */
    private static final List<String> ADMIN_MENU_PAYLOAD_PROPERTIES = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "adminMenuOptions", "selectedOption", "message", "messageSeverity", "errorFlag",
            "focusScreenFieldId", "nextRoute", "navigationContext");

    /** The two properties an option row publishes, in declaration order. */
    private static final List<String> OPTION_ROW_PROPERTIES = List.of("number", "label");

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a mapper configured by hand to the six settings the module declares under
     * {@code spring.jackson} in {@code application.yml}: non-null property inclusion, dates never as
     * timestamps, unknown properties tolerated on the way in, a bare number never binding an
     * enumerated component, a fractional number never binding an integral one, and big decimals
     * written plain.
     *
     * <p>Built locally on each call rather than shared, so no test can observe another test's mapper
     * state and no shared helper is required.
     *
     * @return a mapper equivalent to the one the running application configures
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static JsonNode payloadOf(Object value) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    private static List<String> propertyNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    /**
     * Validates a candidate with a plain Bean Validation validator obtained from the specification's
     * own factory, never a framework-managed one.
     *
     * @param <T>       the validated type
     * @param candidate the instance to validate
     * @return the violations raised, which for this contract is ordinarily none
     */
    private static <T> Set<ConstraintViolation<T>> violationsOf(T candidate) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(candidate);
        }
    }

    /** The ten user rows, built by hand from the oracle labels rather than taken from the contract. */
    private static List<MenuResponse.UserMenuOption> oracleUserOptions() {
        List<MenuResponse.UserMenuOption> rows = new ArrayList<>();
        for (int index = 0; index < ORACLE_USER_LABELS.size(); index++) {
            rows.add(new MenuResponse.UserMenuOption(index + 1, ORACLE_USER_LABELS.get(index)));
        }
        return List.copyOf(rows);
    }

    /** The four administrative rows, built by hand from the oracle labels for the same reason. */
    private static List<MenuResponse.AdminMenuOption> oracleAdminOptions() {
        List<MenuResponse.AdminMenuOption> rows = new ArrayList<>();
        for (int index = 0; index < ORACLE_ADMIN_LABELS.size(); index++) {
            rows.add(new MenuResponse.AdminMenuOption(index + 1, ORACLE_ADMIN_LABELS.get(index)));
        }
        return List.copyOf(rows);
    }

    /** A user collection of an arbitrary size, so a wrong size can be offered deliberately. */
    private static List<MenuResponse.UserMenuOption> userOptionsSized(int size) {
        List<MenuResponse.UserMenuOption> rows = new ArrayList<>();
        for (int index = 1; index <= size; index++) {
            rows.add(new MenuResponse.UserMenuOption(index, "Row " + index));
        }
        return List.copyOf(rows);
    }

    /** An administrative collection of an arbitrary size, for the same reason. */
    private static List<MenuResponse.AdminMenuOption> adminOptionsSized(int size) {
        List<MenuResponse.AdminMenuOption> rows = new ArrayList<>();
        for (int index = 1; index <= size; index++) {
            rows.add(new MenuResponse.AdminMenuOption(index, "Row " + index));
        }
        return List.copyOf(rows);
    }

    /** A navigation state carrying identifiers whose leading zeros must survive the round trip. */
    private static NavigationContext navigationState() {
        return new NavigationContext(ORACLE_USER_MENU_TRANSACTION_NAME, ORACLE_USER_MENU_PROGRAM_NAME,
                ORACLE_ADMIN_MENU_TRANSACTION_NAME, ORACLE_ADMIN_MENU_PROGRAM_NAME, SYNTHETIC_USER_ID,
                RAW_USER_TYPE_CODE, NavigationContext.ProgramContext.ENTER, "000000001", null, null,
                null, LEADING_ZERO_ACCOUNT_ID, null, null, null, null);
    }

    /** A fully populated user-menu response: every component present, nothing left to default. */
    private static MenuResponse populatedUserMenu() {
        return MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME, oracleUserOptions(), "01",
                ORACLE_USER_COMING_SOON, MenuResponse.MessageSeverity.INFORMATIONAL, false,
                FOCUS_FIELD, OPAQUE_ROUTE, navigationState());
    }

    /** A fully populated administrative-menu response, on the rejected-entry path. */
    private static MenuResponse populatedAdminMenu() {
        return MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME, oracleAdminOptions(), "  ",
                ORACLE_INVALID_OPTION_MESSAGE, MenuResponse.MessageSeverity.ERROR, true, FOCUS_FIELD,
                OPAQUE_ROUTE, navigationState());
    }

    @Nested
    @DisplayName("The forty-character screen-title family")
    class ScreenTitleFamily {

        @Test
        @DisplayName("the oracles themselves are the widths the two copybooks declare")
        void oraclesAreSelfConsistent() {
            assertThat(ORACLE_TITLE_LINE_1).as("first title line at its declared width")
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(ORACLE_TITLE_LINE_2).as("active second title line at its declared width")
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(ORACLE_TITLE_THANK_YOU).as("courtesy title at its declared width")
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(ORACLE_INACTIVE_TITLE_LINE_2)
                    .as("the inactive alternative is also forty characters, which is why only its "
                            + "text distinguishes it")
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(ORACLE_COMMON_MESSAGE_THANK_YOU_LITERAL)
                    .as("the common courtesy literal is one character short of its field")
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH - 1);
            assertThat(ORACLE_COMMON_MESSAGE_INVALID_KEY_LITERAL)
                    .as("the common unmapped-key literal is one character short of its field")
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH - 1);
            assertThat(ORACLE_COMMON_MESSAGE_THANK_YOU).hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(ORACLE_COMMON_MESSAGE_INVALID_KEY).hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("publishes the declared title width and all three title values unaltered")
        void publishesAllThreeTitlesAtTheDeclaredWidth() {
            assertThat(MenuResponse.SCREEN_TITLE_WIDTH).isEqualTo(ORACLE_SCREEN_TITLE_WIDTH);

            assertThat(SCREEN_TITLE_LINE_1)
                    .isEqualTo(ORACLE_TITLE_LINE_1)
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(SCREEN_TITLE_LINE_2)
                    .isEqualTo(ORACLE_TITLE_LINE_2)
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(SCREEN_TITLE_THANK_YOU)
                    .isEqualTo(ORACLE_TITLE_THANK_YOU)
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("keeps every leading and trailing space of the first title, because the spaces "
                + "are what centre it")
        void keepsTheFirstTitlePadding() {
            String title = SCREEN_TITLE_LINE_1;

            assertThat(title).hasSize(ORACLE_SCREEN_TITLE_WIDTH)
                    .startsWith("      ")
                    .endsWith("       ")
                    .contains("AWS Mainframe Modernization");
            assertThat(title.indexOf('A')).as("six leading spaces precede the text").isEqualTo(6);
            assertThat(title.length() - title.stripTrailing().length())
                    .as("seven trailing spaces follow it").isEqualTo(7);
            assertThat(title).isNotEqualTo(title.strip())
                    .isNotEqualTo(title.trim())
                    .isNotEqualTo(title.stripLeading())
                    .isNotEqualTo(title.stripTrailing());
        }

        @Test
        @DisplayName("keeps every leading and trailing space of the active second title, and never "
                + "adopts the alternative commented out beside it")
        void keepsTheSecondTitlePaddingAndRejectsTheInactiveAlternative() {
            String title = SCREEN_TITLE_LINE_2;

            assertThat(title).hasSize(ORACLE_SCREEN_TITLE_WIDTH).contains("CardDemo");
            assertThat(title.indexOf('C')).as("fourteen leading spaces precede the text")
                    .isEqualTo(14);
            assertThat(title.length() - title.stripTrailing().length())
                    .as("eighteen trailing spaces follow it").isEqualTo(18);
            assertThat(title).isNotEqualTo(title.strip()).isNotEqualTo(title.trim());

            assertThat(title).as("the inactive alternative at line 21 stays inactive")
                    .isNotEqualTo(ORACLE_INACTIVE_TITLE_LINE_2);
            assertThat(title).doesNotContain("Credit Card Demo Application");
            assertThat(SCREEN_TITLE_LINE_1)
                    .isNotEqualTo(ORACLE_INACTIVE_TITLE_LINE_2);
            assertThat(SCREEN_TITLE_THANK_YOU)
                    .isNotEqualTo(ORACLE_INACTIVE_TITLE_LINE_2);
        }

        @Test
        @DisplayName("keeps the courtesy title's single significant trailing space")
        void keepsTheCourtesyTitleTrailingSpace() {
            String title = SCREEN_TITLE_THANK_YOU;

            assertThat(title).hasSize(ORACLE_SCREEN_TITLE_WIDTH)
                    .startsWith("Thank you")
                    .endsWith("... ");
            assertThat(title.length() - title.stripTrailing().length())
                    .as("exactly one trailing space completes the field").isEqualTo(1);
            assertThat(title).isNotEqualTo(title.stripTrailing());
        }

        @Test
        @DisplayName("carries all three titles through the response and the module's mapper byte for "
                + "byte")
        void roundTripsAllThreeTitlesThroughTheWire() throws Exception {
            ObjectMapper mapper = moduleEquivalentMapper();

            for (String title : List.of(SCREEN_TITLE_LINE_1,
                    SCREEN_TITLE_LINE_2, SCREEN_TITLE_THANK_YOU)) {
                MenuResponse response = new MenuResponse(ORACLE_USER_MENU_TRANSACTION_NAME, title,
                        RENDERED_DATE, ORACLE_USER_MENU_PROGRAM_NAME, title, RENDERED_TIME,
                        oracleUserOptions(), null, "01", null, null, false, null, null, null);

                assertThat(response.title01()).as("carried unaltered by the record").isEqualTo(title);
                assertThat(response.title02()).isEqualTo(title);

                JsonNode payload = payloadOf(response);
                assertThat(payload.get("title01").asText())
                        .as("serialised byte for byte, padding included").isEqualTo(title);
                assertThat(payload.get("title02").asText()).isEqualTo(title);

                MenuResponse back =
                        mapper.readValue(mapper.writeValueAsString(response), MenuResponse.class);
                assertThat(back.title01()).as("survives deserialisation unaltered").isEqualTo(title);
                assertThat(back.title02()).isEqualTo(title);
                assertThat(back.title01()).hasSize(ORACLE_SCREEN_TITLE_WIDTH);
            }
        }

        @Test
        @DisplayName("both factory methods fill both title lines from the same copybook, on both menus")
        void bothFactoriesFillBothTitlesIdentically() {
            MenuResponse user = populatedUserMenu();
            MenuResponse admin = populatedAdminMenu();

            assertThat(user.title01()).isEqualTo(ORACLE_TITLE_LINE_1);
            assertThat(user.title02()).isEqualTo(ORACLE_TITLE_LINE_2);
            assertThat(admin.title01()).isEqualTo(ORACLE_TITLE_LINE_1);
            assertThat(admin.title02()).isEqualTo(ORACLE_TITLE_LINE_2);
            assertThat(admin.title01()).as("one copybook serves both screens")
                    .isEqualTo(user.title01());
            assertThat(admin.title02()).isEqualTo(user.title02());
        }

        @Test
        @DisplayName("never merges the forty-character title family with the fifty-character common "
                + "message family")
        void neverMergesTheTwoFixedWidthFamilies() {
            // Two distinct contracts at two distinct declared widths, in two distinct copybooks, with
            // two distinct owners: the titles belong to this response, the common messages to the
            // service-layer message catalog reached through the sign-on contract. Conflating them
            // corrupts the byte-level message verification the interface-contract gate performs,
            // because the courtesy texts differ in BOTH the product token they name AND their width.
            assertThat(MenuResponse.SCREEN_TITLE_WIDTH)
                    .as("the two families are declared at different widths")
                    .isNotEqualTo(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(MenuResponse.COMMON_MESSAGE_WIDTH).isEqualTo(ORACLE_COMMON_MESSAGE_WIDTH)
                    .isNotEqualTo(MenuResponse.SCREEN_TITLE_WIDTH);

            for (String title : List.of(SCREEN_TITLE_LINE_1,
                    SCREEN_TITLE_LINE_2, SCREEN_TITLE_THANK_YOU)) {
                assertThat(title).as("no title equals either common message at field width")
                        .isNotEqualTo(ORACLE_COMMON_MESSAGE_THANK_YOU)
                        .isNotEqualTo(ORACLE_COMMON_MESSAGE_INVALID_KEY)
                        .isNotEqualTo(ORACLE_COMMON_MESSAGE_THANK_YOU_LITERAL)
                        .isNotEqualTo(ORACLE_COMMON_MESSAGE_INVALID_KEY_LITERAL);
                assertThat(title.length()).isNotEqualTo(ORACLE_COMMON_MESSAGE_WIDTH);
            }
        }

        @Test
        @DisplayName("keeps the courtesy title distinct from the common courtesy message by product "
                + "token as well as by width")
        void keepsTheTwoCourtesyTextsDistinct() {
            String title = SCREEN_TITLE_THANK_YOU;

            assertThat(title).as("the title names the application by its four-letter token")
                    .contains("CCDA")
                    .doesNotContain("CardDemo");
            assertThat(ORACLE_COMMON_MESSAGE_THANK_YOU)
                    .as("the common message names it differently")
                    .contains("CardDemo")
                    .doesNotContain("CCDA");
            assertThat(title).isNotEqualTo(ORACLE_COMMON_MESSAGE_THANK_YOU);
            assertThat(title.strip())
                    .as("not even the two texts stripped of padding are the same value")
                    .isNotEqualTo(ORACLE_COMMON_MESSAGE_THANK_YOU.strip());
        }

        @Test
        @DisplayName("a title is neither re-padded nor re-trimmed when a caller supplies one directly")
        void neitherPadsNorTrimsACallerSuppliedTitle() throws Exception {
            String shortTitle = "CardDemo";
            String paddedTitle = ORACLE_TITLE_LINE_2;

            MenuResponse response = new MenuResponse(null, shortTitle, null, null, paddedTitle, null,
                    oracleUserOptions(), null, null, null, null, false, null, null, null);

            assertThat(response.title01()).as("a short title is never widened")
                    .isEqualTo(shortTitle).hasSize(shortTitle.length());
            assertThat(response.title02()).as("a padded title is never narrowed")
                    .isEqualTo(paddedTitle).hasSize(ORACLE_SCREEN_TITLE_WIDTH);

            JsonNode payload = payloadOf(response);
            assertThat(payload.get("title01").asText()).isEqualTo(shortTitle);
            assertThat(payload.get("title02").asText()).isEqualTo(paddedTitle);
        }
    }

    @Nested
    @DisplayName("A - the two option cardinalities")
    class OptionCardinality {

        @Test
        @DisplayName("publishes exactly the two catalog counts and nothing resembling a table "
                + "capacity")
        void publishesTheTwoCatalogCounts() {
            // The screens lay out more option fields than either catalog fills, and both table views
            // are dimensioned larger than their counts, so surplus blank capacity exists on both
            // menus. The counts are the contract; the capacities are screen-layout and table-view
            // facts that this response does not publish in any form. Asserting the counts by exact
            // equality is what excludes every larger figure.
            assertThat(MenuResponse.USER_MENU_OPTION_COUNT)
                    .isEqualTo(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(MenuResponse.ADMIN_MENU_OPTION_COUNT)
                    .isEqualTo(ORACLE_ADMIN_MENU_OPTION_COUNT);
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .hasSize(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .hasSize(ORACLE_ADMIN_MENU_OPTION_COUNT);
        }

        @Test
        @DisplayName("serialises exactly ten user rows, with no blank row beyond the last")
        void serialisesExactlyTenUserMenuRows() throws Exception {
            JsonNode rows = payloadOf(populatedUserMenu()).get("userMenuOptions");

            assertThat(rows.isArray()).isTrue();
            assertThat(rows.size()).isEqualTo(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(rows.get(ORACLE_USER_MENU_OPTION_COUNT))
                    .as("nothing follows the last populated row - no surplus slot is rendered")
                    .isNull();
            assertThat(rows.get(ORACLE_USER_MENU_OPTION_COUNT + 1)).isNull();
            for (int index = 0; index < rows.size(); index++) {
                JsonNode row = rows.get(index);
                assertThat(row.get("label").asText()).as("no blank label anywhere").isNotBlank();
                assertThat(row.get("number").asInt()).isPositive();
            }
        }

        @Test
        @DisplayName("serialises exactly four administrative rows, with no blank row beyond the last")
        void serialisesExactlyFourAdminRows() throws Exception {
            JsonNode rows = payloadOf(populatedAdminMenu()).get("adminMenuOptions");

            assertThat(rows.isArray()).isTrue();
            assertThat(rows.size()).isEqualTo(ORACLE_ADMIN_MENU_OPTION_COUNT);
            assertThat(rows.get(ORACLE_ADMIN_MENU_OPTION_COUNT))
                    .as("nothing follows the last populated row").isNull();
            assertThat(rows.get(ORACLE_ADMIN_MENU_OPTION_COUNT + 1)).isNull();
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index).get("label").asText()).isNotBlank();
            }
        }

        @Test
        @DisplayName("names no capacity, slot, occurrence or filler property on either menu")
        void namesNoCapacityProperty() throws Exception {
            List<String> userNames = propertyNamesOf(payloadOf(populatedUserMenu()));
            List<String> adminNames = propertyNamesOf(payloadOf(populatedAdminMenu()));

            assertThat(userNames).as("the whole property list, which admits nothing else")
                    .containsExactlyElementsOf(USER_MENU_PAYLOAD_PROPERTIES);
            assertThat(adminNames).containsExactlyElementsOf(ADMIN_MENU_PAYLOAD_PROPERTIES);

            for (String name : List.of("capacity", "occurs", "occurrences", "slots", "slotCount",
                    "maxOptions", "optionCapacity", "tableSize", "filler")) {
                assertThat(userNames).as("user payload must not name %s", name).doesNotContain(name);
                assertThat(adminNames).as("admin payload must not name %s", name)
                        .doesNotContain(name);
            }
        }

        @Test
        @DisplayName("rejects a user collection that is not exactly the catalog count")
        void rejectsAWrongSizedUserCollection() {
            List<MenuResponse.UserMenuOption> tooFew = userOptionsSized(3);
            List<MenuResponse.UserMenuOption> tooMany =
                    userOptionsSized(ORACLE_USER_MENU_OPTION_COUNT + 2);

            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, tooFew,
                    null, null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(ORACLE_USER_MENU_OPTION_COUNT));
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, tooMany,
                    null, null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects an administrative collection that is not exactly the catalog count")
        void rejectsAWrongSizedAdminCollection() {
            List<MenuResponse.AdminMenuOption> tooFew = adminOptionsSized(1);
            List<MenuResponse.AdminMenuOption> tooMany =
                    adminOptionsSized(ORACLE_ADMIN_MENU_OPTION_COUNT + 3);

            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, null,
                    tooFew, null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(ORACLE_ADMIN_MENU_OPTION_COUNT));
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, null,
                    tooMany, null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("B - option ordering")
    class OptionOrdering {

        @Test
        @DisplayName("preserves catalog declaration order and never sorts, re-indexes or "
                + "de-duplicates")
        void preservesCatalogDeclarationOrder() {
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .extracting(MenuResponse.UserMenuOption::label)
                    .containsExactlyElementsOf(ORACLE_USER_LABELS);
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .extracting(MenuResponse.AdminMenuOption::label)
                    .containsExactlyElementsOf(ORACLE_ADMIN_LABELS);

            MenuResponse response = populatedUserMenu();
            assertThat(response.userMenuOptions())
                    .extracting(MenuResponse.UserMenuOption::label)
                    .containsExactlyElementsOf(ORACLE_USER_LABELS);
        }

        @Test
        @DisplayName("keeps an order a caller supplies, even one no catalog would produce")
        void keepsACallerSuppliedOrder() throws Exception {
            List<MenuResponse.UserMenuOption> reversed =
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS.reversed());
            MenuResponse response = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME, reversed,
                    null, null, null, false, null, null, null);

            assertThat(response.userMenuOptions())
                    .as("nothing re-sorts the rows on the way in")
                    .containsExactlyElementsOf(reversed);

            JsonNode rows = payloadOf(response).get("userMenuOptions");
            assertThat(rows.get(0).get("number").asInt())
                    .as("nor on the way out").isEqualTo(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(rows.get(rows.size() - 1).get("number").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("carries the option number as data beside the label, so position is never "
                + "inferred from index")
        void carriesTheNumberAsDataBesideTheLabel() throws Exception {
            JsonNode rows = payloadOf(populatedUserMenu()).get("userMenuOptions");

            for (int index = 0; index < rows.size(); index++) {
                JsonNode row = rows.get(index);
                assertThat(row.has("number")).as("every row states its own number").isTrue();
                assertThat(row.get("number").asInt()).isEqualTo(index + 1);
                assertThat(row.get("label").asText()).isEqualTo(ORACLE_USER_LABELS.get(index));
            }
        }

        @Test
        @DisplayName("keeps duplicate rows rather than collapsing them")
        void keepsDuplicateRows() {
            MenuResponse.UserMenuOption repeated =
                    new MenuResponse.UserMenuOption(1, ORACLE_USER_LABELS.get(0));
            List<MenuResponse.UserMenuOption> withDuplicates = new ArrayList<>();
            for (int index = 0; index < ORACLE_USER_MENU_OPTION_COUNT; index++) {
                withDuplicates.add(repeated);
            }

            MenuResponse response = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, withDuplicates, null, null,
                    null, false, null, null, null);

            assertThat(response.userMenuOptions())
                    .as("no de-duplication: the collection is a list, not a set")
                    .hasSize(ORACLE_USER_MENU_OPTION_COUNT)
                    .containsOnly(repeated);
        }

        @Test
        @DisplayName("carries option 8's active label and never the alternative commented out beside "
                + "it")
        void carriesTheActiveLabelForOptionEight() {
            MenuResponse.UserMenuOption optionEight =
                    CANONICAL_USER_MENU_OPTIONS.get(7);

            assertThat(optionEight.number()).isEqualTo(8);
            assertThat(optionEight.label()).isEqualTo(ORACLE_OPTION_EIGHT_LABEL);
            assertThat(optionEight.label())
                    .as("the alternative at line 69 would restrict who may add a transaction, so it "
                            + "stays inactive")
                    .isNotEqualTo(ORACLE_INACTIVE_OPTION_EIGHT_LABEL)
                    .doesNotContain("Admin Only");
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .extracting(MenuResponse.UserMenuOption::label)
                    .doesNotContain(ORACLE_INACTIVE_OPTION_EIGHT_LABEL);
        }
    }

    @Nested
    @DisplayName("C - the static types of an option row")
    class OptionRowTypes {

        @Test
        @DisplayName("declares the option number an int on both shapes")
        void declaresTheOptionNumberAnInt() {
            // The assignments below are the assertion: each compiles only because the accessor's
            // static type is exactly int, so a widening to long or a change to a boxed or textual type
            // fails the build rather than a runtime check. The catalog declares this item as a
            // two-digit cardinal that both programs compare arithmetically against their option count,
            // which is why it is a number here. Every legacy numeric *identifier* - account, customer,
            // card - crosses this boundary as text instead, so its contractual leading zeros survive;
            // an option number is a count, not an identifier, and is the one place the distinction
            // falls the other way.
            int userNumber = CANONICAL_USER_MENU_OPTIONS.get(0).number();
            int adminNumber = CANONICAL_ADMIN_MENU_OPTIONS.get(0).number();

            assertThat(userNumber).isEqualTo(1);
            assertThat(adminNumber).isEqualTo(1);
            assertThat(MenuResponse.OPTION_NUMBER_WIDTH).isEqualTo(ORACLE_OPTION_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("declares the option label a String on both shapes, bounded at the catalog width")
        void declaresTheOptionLabelAString() {
            String userLabel = CANONICAL_USER_MENU_OPTIONS.get(0).label();
            String adminLabel = CANONICAL_ADMIN_MENU_OPTIONS.get(0).label();

            assertThat(userLabel).isEqualTo(ORACLE_USER_LABELS.get(0));
            assertThat(adminLabel).isEqualTo(ORACLE_ADMIN_LABELS.get(0));
            assertThat(MenuResponse.OPTION_LABEL_WIDTH).isEqualTo(ORACLE_OPTION_LABEL_WIDTH);
        }

        @Test
        @DisplayName("publishes a row as its number and its label only, on both shapes")
        void publishesNumberAndLabelOnly() throws Exception {
            JsonNode userRow = payloadOf(CANONICAL_USER_MENU_OPTIONS.get(0));
            JsonNode adminRow = payloadOf(CANONICAL_ADMIN_MENU_OPTIONS.get(0));

            assertThat(propertyNamesOf(userRow)).containsExactlyElementsOf(OPTION_ROW_PROPERTIES);
            assertThat(propertyNamesOf(adminRow)).containsExactlyElementsOf(OPTION_ROW_PROPERTIES);
            assertThat(userRow.size()).isEqualTo(OPTION_ROW_PROPERTIES.size());
            assertThat(adminRow.size()).isEqualTo(OPTION_ROW_PROPERTIES.size());
            assertThat(userRow.get("number").isInt()).as("a JSON number, not a quoted digit string")
                    .isTrue();
            assertThat(userRow.get("label").isTextual()).isTrue();
        }

        @Test
        @DisplayName("carries no user-type code and no dispatch target on either shape")
        void carriesNoRoleCodeAndNoDispatchTarget() throws Exception {
            // The administrative catalog entry declares no user-type item at all, which is what makes
            // it one byte narrower than the user entry. The user entry does declare one, but it is an
            // authorization input the program evaluates before it dispatches, never screen content, so
            // it is withheld from the wire along with the target program name. Both remain with the
            // configuration-layer catalog, which carries every item of every entry; that is where the
            // requirement to map every copybook field is discharged. Where the code does cross a
            // boundary - on the navigation state - it crosses as the raw one-character String it is in
            // the record, never as a domain enumeration, which is why no such enumeration is imported
            // anywhere in this test.
            for (JsonNode row : List.of(payloadOf(CANONICAL_USER_MENU_OPTIONS.get(0)),
                    payloadOf(CANONICAL_ADMIN_MENU_OPTIONS.get(0)))) {
                for (String withheld : List.of("userType", "usrType", "userTypeCode", "programName",
                        "pgmName", "targetProgram", "program", "role", "authorization")) {
                    assertThat(row.has(withheld)).as("an option row must not publish %s", withheld)
                            .isFalse();
                }
            }

            String userMenuJson = moduleEquivalentMapper().writeValueAsString(populatedUserMenu());
            for (String program : List.of("COACTVWC", "COACTUPC", "COCRDLIC", "COTRN02C",
                    "CORPT00C", "COBIL00C", "COUSR00C", "COUSR03C")) {
                assertThat(userMenuJson).as("no dispatch target reaches the payload")
                        .doesNotContain(program);
            }
        }

        @Test
        @DisplayName("keeps the two shapes distinct types with no shared supertype and no conversion")
        void keepsTheTwoShapesDistinctTypes() {
            // The two local declarations below are themselves the structural assertion, and the
            // compiler is what enforces it: neither type is assignable to the other, so no conversion
            // between them exists and the response's two option components can never be confused. The
            // same two types appear in the record's own signature, which fixes the property for every
            // caller. Nothing here inspects a class object to establish it.
            MenuResponse.UserMenuOption userRow = new MenuResponse.UserMenuOption(1, "Same Text");
            MenuResponse.AdminMenuOption adminRow = new MenuResponse.AdminMenuOption(1, "Same Text");

            // Type identity is the guarantee, and it is stronger than a difference in component lists
            // because it does not decay if the two lists ever coincide - as they presently do. Even
            // carrying identical values the two are never equal, so a caller holding one always knows
            // which menu produced it.
            assertThat(userRow).isNotEqualTo(adminRow);
            assertThat(adminRow).isNotEqualTo(userRow);
            assertThat(userRow.toString()).as("each row names its own type")
                    .startsWith("UserMenuOption[");
            assertThat(adminRow.toString()).startsWith("AdminMenuOption[");
            assertThat(userRow.number()).isEqualTo(adminRow.number());
            assertThat(userRow.label()).isEqualTo(adminRow.label());
        }

        @Test
        @DisplayName("bounds an over-long label without ever shortening it")
        void boundsAnOverLongLabelWithoutShorteningIt() {
            String overLong = "L".repeat(ORACLE_OPTION_LABEL_WIDTH + 1);

            MenuResponse.UserMenuOption row = new MenuResponse.UserMenuOption(1, overLong);

            assertThat(row.label()).as("measured, never altered")
                    .isEqualTo(overLong).hasSize(ORACLE_OPTION_LABEL_WIDTH + 1);
            assertThat(violationsOf(row)).as("the size bound is what reports it")
                    .hasSize(1)
                    .first()
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .isEqualTo("label");
            assertThat(violationsOf(new MenuResponse.UserMenuOption(1,
                    "L".repeat(ORACLE_OPTION_LABEL_WIDTH)))).isEmpty();
            assertThat(violationsOf(new MenuResponse.AdminMenuOption(1, overLong))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("D - the option collections are immutable and detached")
    class OptionCollectionSemantics {

        @Test
        @DisplayName("returns an unmodifiable collection on both menus")
        void returnsAnUnmodifiableCollection() {
            List<MenuResponse.UserMenuOption> userRows = populatedUserMenu().userMenuOptions();
            List<MenuResponse.AdminMenuOption> adminRows = populatedAdminMenu().adminMenuOptions();
            MenuResponse.UserMenuOption extraUserMenuRow = new MenuResponse.UserMenuOption(11, "Extra");
            MenuResponse.AdminMenuOption extraAdminRow = new MenuResponse.AdminMenuOption(5, "Extra");

            assertThatThrownBy(() -> userRows.add(extraUserMenuRow))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> userRows.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> userRows.set(0, extraUserMenuRow))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> userRows.clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> adminRows.add(extraAdminRow))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> adminRows.clear())
                    .isInstanceOf(UnsupportedOperationException.class);

            assertThat(userRows).hasSize(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(adminRows).hasSize(ORACLE_ADMIN_MENU_OPTION_COUNT);
        }

        @Test
        @DisplayName("detaches the caller's collection with copy-of semantics, so a later mutation "
                + "changes nothing")
        void detachesTheCallersCollection() {
            List<MenuResponse.UserMenuOption> caller = new ArrayList<>(oracleUserOptions());
            MenuResponse response = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME, caller,
                    null, null, null, false, null, null, null);

            caller.clear();
            caller.add(new MenuResponse.UserMenuOption(1, "Replaced"));

            assertThat(response.userMenuOptions())
                    .as("the response holds its own copy, not the caller's list")
                    .hasSize(ORACLE_USER_MENU_OPTION_COUNT)
                    .extracting(MenuResponse.UserMenuOption::label)
                    .containsExactlyElementsOf(ORACLE_USER_LABELS);
            assertThat(response.userMenuOptions()).isNotSameAs(caller);
        }

        @Test
        @DisplayName("detaches an administrative collection the same way")
        void detachesTheCallersAdminCollection() {
            List<MenuResponse.AdminMenuOption> caller = new ArrayList<>(oracleAdminOptions());
            MenuResponse response = MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME, caller,
                    null, null, null, false, null, null, null);

            caller.clear();

            assertThat(response.adminMenuOptions()).hasSize(ORACLE_ADMIN_MENU_OPTION_COUNT)
                    .extracting(MenuResponse.AdminMenuOption::label)
                    .containsExactlyElementsOf(ORACLE_ADMIN_LABELS);
            assertThat(response.adminMenuOptions()).isNotSameAs(caller);
        }

        @Test
        @DisplayName("keeps an absent collection absent rather than promoting it to an empty one")
        void keepsAnAbsentCollectionAbsent() throws Exception {
            // Absent and empty answer different questions. A user-menu response has no administrative
            // collection AT ALL, which is a different statement from an administrative menu that lists
            // nothing - and because the module omits absent properties, storing it absent keeps the
            // irrelevant collection out of the payload entirely rather than publishing an empty array a
            // client would then have to interpret. Promoting absent to empty would erase that
            // distinction and would also make a response describing neither menu representable.
            MenuResponse userMenu = populatedUserMenu();
            MenuResponse adminMenu = populatedAdminMenu();

            assertThat(userMenu.adminMenuOptions()).isNull();
            assertThat(adminMenu.userMenuOptions()).isNull();
            assertThat(payloadOf(userMenu).has("adminMenuOptions"))
                    .as("omitted from the payload, not published as an empty array").isFalse();
            assertThat(payloadOf(adminMenu).has("userMenuOptions")).isFalse();
        }

        @Test
        @DisplayName("admits exactly one menu: never both collections and never neither")
        void admitsExactlyOneMenu() {
            List<MenuResponse.UserMenuOption> userRows = oracleUserOptions();
            List<MenuResponse.AdminMenuOption> adminRows = oracleAdminOptions();

            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, userRows,
                    adminRows, null, null, null, false, null, null, null))
                    .as("both collections describes no screen the estate can produce")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one option collection")
                    .hasMessageContaining("both were supplied");
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, null, null,
                    null, null, null, false, null, null, null))
                    .as("neither collection likewise describes no screen")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one option collection")
                    .hasMessageContaining("neither was supplied");

            assertThat(populatedUserMenu().carriesUserMenu()).isTrue();
            assertThat(populatedUserMenu().carriesAdminMenu()).isFalse();
            assertThat(populatedAdminMenu().carriesAdminMenu()).isTrue();
            assertThat(populatedAdminMenu().carriesUserMenu()).isFalse();
        }

        @Test
        @DisplayName("rejects a blank row offered inside an otherwise correctly sized collection")
        void rejectsABlankRow() {
            List<MenuResponse.UserMenuOption> withNullRow = new ArrayList<>(oracleUserOptions());
            withNullRow.set(4, null);

            assertThatThrownBy(() -> MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, withNullRow, null, null,
                    null, false, null, null, null))
                    .as("copy-of semantics reject a null element, which is exactly the surplus blank "
                            + "slot this contract excludes")
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("E - the navigation state is carried, never re-implemented")
    class NavigationStateCarriage {

        @Test
        @DisplayName("carries a navigation state whose identifiers round-trip unchanged, leading "
                + "zeros included")
        void carriesIdentifiersWithLeadingZerosIntact() throws Exception {
            ObjectMapper mapper = moduleEquivalentMapper();
            MenuResponse response = populatedUserMenu();

            assertThat(response.navigationContext()).isNotNull();
            assertThat(response.navigationContext().accountId())
                    .as("a numeric identifier crosses as text so its leading zeros survive")
                    .isEqualTo(LEADING_ZERO_ACCOUNT_ID)
                    .hasSize(NavigationContext.ACCOUNT_ID_LENGTH)
                    .startsWith("0");

            JsonNode carried = payloadOf(response).get("navigationContext");
            assertThat(carried.get("accountId").isTextual())
                    .as("a JSON string, never a number, or the zeros would be lost").isTrue();
            assertThat(carried.get("accountId").asText()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(carried.get("customerId").asText()).isEqualTo("000000001");

            MenuResponse back =
                    mapper.readValue(mapper.writeValueAsString(response), MenuResponse.class);
            assertThat(back.navigationContext()).isEqualTo(response.navigationContext());
            assertThat(back.navigationContext().accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
        }

        @Test
        @DisplayName("carries every navigation identifier it was given, without re-deriving any of "
                + "them")
        void carriesEveryNavigationIdentifier() {
            NavigationContext carried = populatedUserMenu().navigationContext();

            assertThat(carried.fromTransactionId()).isEqualTo(ORACLE_USER_MENU_TRANSACTION_NAME);
            assertThat(carried.fromProgram()).isEqualTo(ORACLE_USER_MENU_PROGRAM_NAME);
            assertThat(carried.toTransactionId()).isEqualTo(ORACLE_ADMIN_MENU_TRANSACTION_NAME);
            assertThat(carried.toProgram()).isEqualTo(ORACLE_ADMIN_MENU_PROGRAM_NAME);
            assertThat(carried.userId()).isEqualTo(SYNTHETIC_USER_ID);
            assertThat(carried.userType())
                    .as("the one-character code travels raw, never as an enumeration")
                    .isEqualTo(RAW_USER_TYPE_CODE)
                    .hasSize(NavigationContext.USER_TYPE_LENGTH);
            assertThat(carried.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(carried.firstEntry()).isTrue();
            assertThat(carried.reEntry()).isFalse();
        }

        @Test
        @DisplayName("accepts an absent navigation state and an empty one alike")
        void acceptsAnAbsentAndAnEmptyNavigationState() throws Exception {
            MenuResponse without = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    null, null, false, null, null, null);
            MenuResponse withEmpty = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    null, null, false, null, null, NavigationContext.empty());

            assertThat(without.navigationContext()).isNull();
            assertThat(payloadOf(without).has("navigationContext")).isFalse();

            assertThat(withEmpty.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(payloadOf(withEmpty).get("navigationContext").size())
                    .as("an empty state serialises as an object with every component omitted")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("F - the next route is declarative data, never logic")
    class RouteIsData {

        @Test
        @DisplayName("carries an opaque route verbatim and resolves nothing")
        void carriesAnOpaqueRouteVerbatim() throws Exception {
            // Route constants belong to the navigation service. This response holds a value the
            // service chose and the client acts on: the estate's program-to-program transfers and
            // re-arming returns all become route values in a response body rather than server-side
            // forwarding, and no route table, route enumeration or dispatch method exists here to
            // resolve one.
            MenuResponse response = populatedUserMenu();

            assertThat(response.nextRoute()).isEqualTo(OPAQUE_ROUTE);
            assertThat(payloadOf(response).get("nextRoute").isTextual())
                    .as("a plain string, not an enumerated constant and not an object").isTrue();
            assertThat(payloadOf(response).get("nextRoute").asText()).isEqualTo(OPAQUE_ROUTE);
        }

        @Test
        @DisplayName("polices no route vocabulary, so an unknown token survives untouched")
        void policesNoRouteVocabulary() throws Exception {
            MenuResponse unknown = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    null, null, false, null, OTHER_OPAQUE_ROUTE, null);
            String veryLongRoute = "r".repeat(ORACLE_MESSAGE_WIDTH * 2);
            MenuResponse unbounded = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    null, null, false, null, veryLongRoute, null);

            assertThat(unknown.nextRoute()).isEqualTo(OTHER_OPAQUE_ROUTE);
            assertThat(violationsOf(unknown))
                    .as("no pattern, vocabulary or presence constraint applies to a route").isEmpty();
            assertThat(unbounded.nextRoute()).isEqualTo(veryLongRoute);
            assertThat(violationsOf(unbounded))
                    .as("the route carries no width bound either, because it is not a screen field")
                    .isEmpty();
            assertThat(payloadOf(unbounded).get("nextRoute").asText()).isEqualTo(veryLongRoute);
        }

        @Test
        @DisplayName("distinguishes an absent route from an empty one")
        void distinguishesAnAbsentRouteFromAnEmptyOne() throws Exception {
            MenuResponse absent = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    null, null, false, null, null, null);
            MenuResponse empty = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    null, null, false, null, "", null);

            assertThat(absent.nextRoute()).isNull();
            assertThat(payloadOf(absent).has("nextRoute"))
                    .as("nominating no route omits the property").isFalse();
            assertThat(empty.nextRoute()).as("an empty route is not converted to absent").isEmpty();
            assertThat(payloadOf(empty).get("nextRoute").asText()).isEmpty();
        }

        @Test
        @DisplayName("the focus hint is an opaque label, never a coordinate or an attribute")
        void theFocusHintIsAnOpaqueLabel() throws Exception {
            MenuResponse response = populatedUserMenu();

            assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_FIELD);
            assertThat(MenuResponse.SCREEN_FIELD_ID_WIDTH)
                    .as("bounded by the widest symbolic field name either mapset declares")
                    .isGreaterThanOrEqualTo(FOCUS_FIELD.length());
            assertThat(payloadOf(response).get("focusScreenFieldId").isTextual()).isTrue();

            String json = moduleEquivalentMapper().writeValueAsString(response);
            for (String presentation : List.of("DFHRED", "DFHGREEN", "DFHBMASB", "DFHUNIMD",
                    "cursor", "row", "column", "attribute", "highlight", "colour", "color")) {
                assertThat(json).as("no terminal presentation value reaches the payload")
                        .doesNotContain(presentation);
            }
        }
    }

    @Nested
    @DisplayName("G - the rendered header items stay plain text")
    class ScreenFurniture {

        @Test
        @DisplayName("publishes the six header widths the two symbolic maps declare")
        void publishesTheSixHeaderWidths() {
            assertThat(MenuResponse.TRANSACTION_NAME_WIDTH)
                    .isEqualTo(ORACLE_TRANSACTION_NAME_WIDTH);
            assertThat(MenuResponse.PROGRAM_NAME_WIDTH).isEqualTo(ORACLE_PROGRAM_NAME_WIDTH);
            assertThat(MenuResponse.CURRENT_DATE_WIDTH).isEqualTo(ORACLE_CURRENT_DATE_WIDTH);
            assertThat(MenuResponse.CURRENT_TIME_WIDTH).isEqualTo(ORACLE_CURRENT_TIME_WIDTH);
            assertThat(MenuResponse.SCREEN_TITLE_WIDTH).isEqualTo(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(MenuResponse.SELECTED_OPTION_WIDTH).isEqualTo(ORACLE_SELECTED_OPTION_WIDTH);
        }

        @Test
        @DisplayName("keeps the rendered time at eight characters, the width both menu maps declare")
        void keepsTheRenderedTimeAtEightCharacters() {
            // Eight here, and nine on the sign-on map - the one place in the estate where this item is
            // wider. The two figures are deliberately separate constants on two separate contracts and
            // are never normalised into one: widening this one would put a character on the menu
            // screens that neither menu map has room for, and narrowing the other would truncate what
            // sign-on renders.
            assertThat(MenuResponse.CURRENT_TIME_WIDTH).isEqualTo(ORACLE_CURRENT_TIME_WIDTH);
            assertThat(RENDERED_TIME).hasSize(ORACLE_CURRENT_TIME_WIDTH);
            assertThat(violationsOf(populatedUserMenu())).isEmpty();
            assertThat(violationsOf(MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE,
                    "1".repeat(ORACLE_CURRENT_TIME_WIDTH + 1), oracleUserOptions(), null, null, null,
                    false, null, null, null)))
                    .as("a ninth character does not fit the menu screens' own field")
                    .hasSize(1);
        }

        @Test
        @DisplayName("carries the rendered date and time as text, untrimmed, with no formatter "
                + "anywhere")
        void carriesTheRenderedDateAndTimeAsText() throws Exception {
            String paddedDate = " 1/31/24";
            String paddedTime = "09:27:53";
            MenuResponse response = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    paddedDate, paddedTime,
                    oracleUserOptions(), null, null, null, false, null, null, null);

            assertThat(response.currentDate())
                    .as("carried exactly as the program rendered it, leading space included")
                    .isEqualTo(paddedDate).hasSize(ORACLE_CURRENT_DATE_WIDTH);
            assertThat(response.currentTime()).isEqualTo(paddedTime);

            JsonNode payload = payloadOf(response);
            assertThat(payload.get("currentDate").isTextual())
                    .as("text, never a date type: no parse, no format, no zone").isTrue();
            assertThat(payload.get("currentDate").asText()).isEqualTo(paddedDate);
            assertThat(payload.get("currentTime").isTextual()).isTrue();
            assertThat(payload.get("currentTime").asText()).isEqualTo(paddedTime);
        }

        @Test
        @DisplayName("echoes each screen's own transaction identifier and program name")
        void echoesEachScreensOwnIdentity() {
            MenuResponse user = populatedUserMenu();
            MenuResponse admin = populatedAdminMenu();

            assertThat(user.transactionName()).isEqualTo(ORACLE_USER_MENU_TRANSACTION_NAME)
                    .hasSize(ORACLE_TRANSACTION_NAME_WIDTH);
            assertThat(user.programName()).isEqualTo(ORACLE_USER_MENU_PROGRAM_NAME)
                    .hasSize(ORACLE_PROGRAM_NAME_WIDTH);
            assertThat(admin.transactionName()).isEqualTo(ORACLE_ADMIN_MENU_TRANSACTION_NAME)
                    .hasSize(ORACLE_TRANSACTION_NAME_WIDTH);
            assertThat(admin.programName()).isEqualTo(ORACLE_ADMIN_MENU_PROGRAM_NAME)
                    .hasSize(ORACLE_PROGRAM_NAME_WIDTH);
            assertThat(user.transactionName()).as("two menus are two transactions")
                    .isNotEqualTo(admin.transactionName());
            assertThat(user.programName()).isNotEqualTo(admin.programName());

            assertThat(MenuResponse.USER_MENU_TRANSACTION_NAME)
                    .isEqualTo(ORACLE_USER_MENU_TRANSACTION_NAME);
            assertThat(MenuResponse.USER_MENU_PROGRAM_NAME).isEqualTo(ORACLE_USER_MENU_PROGRAM_NAME);
            assertThat(MenuResponse.ADMIN_MENU_TRANSACTION_NAME)
                    .isEqualTo(ORACLE_ADMIN_MENU_TRANSACTION_NAME);
            assertThat(MenuResponse.ADMIN_MENU_PROGRAM_NAME)
                    .isEqualTo(ORACLE_ADMIN_MENU_PROGRAM_NAME);
        }

        @Test
        @DisplayName("echoes the operator's entry exactly, applying no blank-to-zero fill")
        void echoesTheOperatorsEntryExactly() throws Exception {
            // The legacy blank-to-zero fill over the right-justified two-character entry field turns a
            // single digit into a zero-filled pair. That normalisation belongs to the menu service and
            // its string utilities, not to a response record, so a value arrives here already resolved
            // and leaves unchanged. Asserting the un-filled forms survive is how the absence is proved.
            MenuResponse blankEntry = MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleAdminOptions(),
                    "  ", null, null, false, null, null, null);
            MenuResponse singleDigit = MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleAdminOptions(),
                    " 1", null, null, false, null, null, null);
            MenuResponse filled = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), "01",
                    null, null, false, null, null, null);

            assertThat(blankEntry.selectedOption()).as("two spaces stay two spaces").isEqualTo("  ");
            assertThat(singleDigit.selectedOption())
                    .as("a space-then-digit entry is never zero-filled here").isEqualTo(" 1");
            assertThat(filled.selectedOption()).isEqualTo("01");

            assertThat(payloadOf(blankEntry).get("selectedOption").asText()).isEqualTo("  ");
            assertThat(payloadOf(singleDigit).get("selectedOption").asText()).isEqualTo(" 1");
            assertThat(payloadOf(filled).get("selectedOption").asText()).isEqualTo("01");
        }
    }

    @Nested
    @DisplayName("H - the single message line")
    class MessageLine {

        @Test
        @DisplayName("publishes both message widths, the working-storage bound and the narrower screen "
                + "field, and applies neither")
        void publishesBothMessageWidths() {
            assertThat(MenuResponse.MESSAGE_WIDTH).isEqualTo(ORACLE_MESSAGE_WIDTH);
            assertThat(MenuResponse.SCREEN_MESSAGE_FIELD_WIDTH)
                    .isEqualTo(ORACLE_SCREEN_MESSAGE_FIELD_WIDTH);
            assertThat(MenuResponse.SCREEN_MESSAGE_FIELD_WIDTH)
                    .as("the two genuinely differ and both matter")
                    .isLessThan(MenuResponse.MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("carries a message at the screen field's full width untrimmed")
        void carriesAMessageAtTheScreenFieldWidth() throws Exception {
            String atScreenWidth =
                    " " + "M".repeat(ORACLE_SCREEN_MESSAGE_FIELD_WIDTH - 2) + " ";
            MenuResponse response = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    atScreenWidth, MenuResponse.MessageSeverity.ERROR, true, null, null, null);

            assertThat(atScreenWidth).hasSize(ORACLE_SCREEN_MESSAGE_FIELD_WIDTH);
            assertThat(response.message())
                    .as("both the leading and the trailing space are content")
                    .isEqualTo(atScreenWidth)
                    .hasSize(ORACLE_SCREEN_MESSAGE_FIELD_WIDTH);
            assertThat(response.message()).isNotEqualTo(atScreenWidth.strip());
            assertThat(payloadOf(response).get("message").asText()).isEqualTo(atScreenWidth);
            assertThat(violationsOf(response))
                    .as("well within the working-storage bound").isEmpty();
        }

        @Test
        @DisplayName("bounds the message at the working-storage width and never shortens one to fit "
                + "the screen field")
        void boundsTheMessageAtTheWorkingStorageWidth() {
            String atWorkingStorageWidth = "M".repeat(ORACLE_MESSAGE_WIDTH);
            String overWorkingStorageWidth = "M".repeat(ORACLE_MESSAGE_WIDTH + 1);

            MenuResponse admissible = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    atWorkingStorageWidth, null, false, null, null, null);
            MenuResponse tooWide = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    overWorkingStorageWidth, null, false, null, null, null);

            assertThat(violationsOf(admissible))
                    .as("the working-storage width is the bound, not the narrower screen field")
                    .isEmpty();
            assertThat(admissible.message()).hasSize(ORACLE_MESSAGE_WIDTH)
                    .as("a message wider than the screen field is carried, never truncated")
                    .isEqualTo(atWorkingStorageWidth);
            assertThat(violationsOf(tooWide)).hasSize(1)
                    .first()
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .isEqualTo("message");
            assertThat(tooWide.message()).as("measured, still never altered")
                    .isEqualTo(overWorkingStorageWidth);
        }

        @Test
        @DisplayName("carries the user menu's coming-soon text byte for byte, missing space included")
        void carriesTheUserComingSoonTextByteForByte() throws Exception {
            // CONTRACT, NOT A DEFECT. The user program inserts the selected option's name between a
            // prefix and a suffix delimited by a space, which takes the name's leading word only and
            // leaves nothing between it and the suffix, so the rendered text runs the word straight
            // into the suffix. The absence is the observable behaviour operators and downstream tooling
            // match on, so it is reproduced rather than repaired. Assembling the text is the menu
            // service's work; this response carries the result unaltered. The placeholder-program test
            // that decides whether the text is emitted at all likewise belongs to that service.
            ObjectMapper mapper = moduleEquivalentMapper();

            assertThat(ORACLE_USER_COMING_SOON)
                    .as("the leading word runs into the suffix with no separator")
                    .contains("Accountis")
                    .doesNotContain("Account is")
                    .isEqualTo("This option Accountis coming soon ...")
                    .hasSize(37);

            MenuResponse response = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME,
                    oracleUserOptions(), "01", ORACLE_USER_COMING_SOON,
                    MenuResponse.MessageSeverity.INFORMATIONAL, false, null, null, null);

            assertThat(response.message()).isEqualTo(ORACLE_USER_COMING_SOON);
            assertThat(payloadOf(response).get("message").asText())
                    .as("no normalisation on the way out").isEqualTo(ORACLE_USER_COMING_SOON);

            MenuResponse back =
                    mapper.readValue(mapper.writeValueAsString(response), MenuResponse.class);
            assertThat(back.message()).as("nor on the way back in")
                    .isEqualTo(ORACLE_USER_COMING_SOON);
        }

        @Test
        @DisplayName("carries the administrative menu's coming-soon text byte for byte, which has the "
                + "space the user menu's lacks")
        void carriesTheAdminComingSoonTextByteForByte() throws Exception {
            // The identical construction with the name operand commented out, so no name appears and
            // the prefix's own trailing space separates the two parts correctly. Two divergent texts
            // from one construction: they are two distinct behaviours and are never reconciled.
            ObjectMapper mapper = moduleEquivalentMapper();

            assertThat(ORACLE_ADMIN_COMING_SOON)
                    .isEqualTo("This option is coming soon ...")
                    .contains("option is coming")
                    .hasSize(30);

            MenuResponse response = MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME,
                    oracleAdminOptions(), "01", ORACLE_ADMIN_COMING_SOON,
                    MenuResponse.MessageSeverity.INFORMATIONAL, false, null, null, null);

            assertThat(response.message()).isEqualTo(ORACLE_ADMIN_COMING_SOON);
            assertThat(payloadOf(response).get("message").asText())
                    .isEqualTo(ORACLE_ADMIN_COMING_SOON);

            MenuResponse back =
                    mapper.readValue(mapper.writeValueAsString(response), MenuResponse.class);
            assertThat(back.message()).isEqualTo(ORACLE_ADMIN_COMING_SOON);
        }

        @Test
        @DisplayName("never reconciles the two coming-soon texts into one")
        void neverReconcilesTheTwoComingSoonTexts() {
            assertThat(ORACLE_USER_COMING_SOON).isNotEqualTo(ORACLE_ADMIN_COMING_SOON);
            assertThat(ORACLE_USER_COMING_SOON.length())
                    .as("the user form is longer by the inserted word alone")
                    .isEqualTo(ORACLE_ADMIN_COMING_SOON.length() + "Account".length());
            assertThat(ORACLE_COMING_SOON_PREFIX).hasSize(12).endsWith(" ");
            assertThat(ORACLE_COMING_SOON_SUFFIX).hasSize(18).startsWith("is");

            MenuResponse userForm = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    ORACLE_USER_COMING_SOON, MenuResponse.MessageSeverity.INFORMATIONAL, false, null,
                    null, null);
            MenuResponse adminForm = MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleAdminOptions(), null,
                    ORACLE_ADMIN_COMING_SOON, MenuResponse.MessageSeverity.INFORMATIONAL, false, null,
                    null, null);

            assertThat(userForm.message()).isNotEqualTo(adminForm.message());
            assertThat(userForm.message())
                    .as("the user form is not the administrative form with a word inserted tidily")
                    .isNotEqualTo(ORACLE_COMING_SOON_PREFIX + "Account " + ORACLE_COMING_SOON_SUFFIX);
        }

        @Test
        @DisplayName("carries the other two menu texts, including a common message at its full "
                + "fifty-character width")
        void carriesTheOtherMenuTexts() {
            assertThat(ORACLE_INVALID_OPTION_MESSAGE).hasSize(37).endsWith("...");
            assertThat(ORACLE_ADMIN_ONLY_MESSAGE)
                    .as("the access-denied text ends in a significant trailing space")
                    .hasSize(33).endsWith(" ");

            for (String text : List.of(ORACLE_INVALID_OPTION_MESSAGE, ORACLE_ADMIN_ONLY_MESSAGE,
                    ORACLE_COMMON_MESSAGE_THANK_YOU, ORACLE_COMMON_MESSAGE_INVALID_KEY)) {
                MenuResponse response = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(),
                        null, text, MenuResponse.MessageSeverity.ERROR, true, null, null, null);

                assertThat(response.message()).as("carried unaltered at its own width")
                        .isEqualTo(text).hasSize(text.length());
                assertThat(violationsOf(response))
                        .as("every one of them fits the working-storage bound").isEmpty();
            }
        }

        @Test
        @DisplayName("carries the message's intent as a separate fact from the error switch")
        void carriesTheMessageIntentSeparately() throws Exception {
            MenuResponse informational = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(),
                    null, ORACLE_USER_COMING_SOON, MenuResponse.MessageSeverity.INFORMATIONAL, false,
                    null, null, null);
            MenuResponse rejection = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    ORACLE_INVALID_OPTION_MESSAGE, MenuResponse.MessageSeverity.ERROR, true, null,
                    null, null);

            assertThat(informational.messageSeverity())
                    .isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
            assertThat(informational.errorFlag())
                    .as("a message on the successful placeholder path leaves the switch clear")
                    .isFalse();
            assertThat(rejection.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(rejection.errorFlag()).isTrue();

            assertThat(payloadOf(informational).get("messageSeverity").asText())
                    .isEqualTo("INFORMATIONAL");
            assertThat(payloadOf(rejection).get("messageSeverity").asText()).isEqualTo("ERROR");
            assertThat(MenuResponse.MessageSeverity.values())
                    .containsExactly(MenuResponse.MessageSeverity.INFORMATIONAL,
                            MenuResponse.MessageSeverity.ERROR);
            assertThat(MenuResponse.MessageSeverity.valueOf("ERROR"))
                    .isEqualTo(MenuResponse.MessageSeverity.ERROR);
        }

        @Test
        @DisplayName("distinguishes a screen showing no message from one showing an empty one")
        void distinguishesNoMessageFromAnEmptyMessage() throws Exception {
            MenuResponse silent = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null,
                    null, null, false, null, null, null);
            MenuResponse blank = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    null, null, oracleUserOptions(), null, "",
                    null, false, null, null, null);

            assertThat(silent.message()).isNull();
            assertThat(silent.messageSeverity()).isNull();
            assertThat(payloadOf(silent).has("message")).isFalse();
            assertThat(payloadOf(silent).has("messageSeverity")).isFalse();
            assertThat(blank.message()).isEmpty();
            assertThat(payloadOf(blank).get("message").asText()).isEmpty();
        }
    }

    @Nested
    @DisplayName("I - the only constraint that fires is the size bound")
    class ConstraintInventory {

        @Test
        @DisplayName("raises no violation when every component but the one required collection is "
                + "absent")
        void raisesNoViolationWhenEveryComponentIsAbsent() {
            // No presence, blankness, pattern, digit, minimum or maximum constraint exists on this
            // contract, so an all-absent instance is valid. That is deliberate: the legacy validation
            // cascades are ordered and stop at the first failure, and a declarative constraint set
            // reports them all at once in an order of its own choosing, which is a different behaviour.
            // Ordered validation therefore belongs to the service layer, and the only annotation here
            // measures a width.
            //
            // Every component is absent below except the one option collection construction requires:
            // a response describing neither menu is unrepresentable by design, which the construction
            // invariants assert separately.
            MenuResponse allAbsentUserMenu = new MenuResponse(null, null, null, null, null, null,
                    oracleUserOptions(), null, null, null, null, false, null, null, null);
            MenuResponse allAbsentAdminMenu = new MenuResponse(null, null, null, null, null, null,
                    null, oracleAdminOptions(), null, null, null, false, null, null, null);

            assertThat(violationsOf(allAbsentUserMenu))
                    .as("no @NotNull, @NotBlank, @NotEmpty, @Pattern, @Digits, @Min or @Max fires")
                    .isEmpty();
            assertThat(violationsOf(allAbsentAdminMenu)).isEmpty();
        }

        @Test
        @DisplayName("raises no violation for an option row whose label is absent")
        void raisesNoViolationForAnAbsentLabel() {
            assertThat(violationsOf(new MenuResponse.UserMenuOption(0, null)))
                    .as("neither presence nor a numeric range is constrained on a row").isEmpty();
            assertThat(violationsOf(new MenuResponse.AdminMenuOption(0, null))).isEmpty();
            assertThat(violationsOf(new MenuResponse.UserMenuOption(-1, "")))
                    .as("an out-of-range number is the service's count comparison, not a constraint")
                    .isEmpty();
        }

        @Test
        @DisplayName("raises no violation for a fully populated response on either menu")
        void raisesNoViolationForAPopulatedResponse() {
            assertThat(violationsOf(populatedUserMenu())).isEmpty();
            assertThat(violationsOf(populatedAdminMenu())).isEmpty();
        }

        @Test
        @DisplayName("reports exactly one violation per over-wide component, naming that component")
        void reportsOneViolationPerOverWideComponent() {
            MenuResponse overWide = new MenuResponse(
                    "C".repeat(ORACLE_TRANSACTION_NAME_WIDTH + 1),
                    "T".repeat(ORACLE_SCREEN_TITLE_WIDTH + 1),
                    "D".repeat(ORACLE_CURRENT_DATE_WIDTH + 1),
                    "P".repeat(ORACLE_PROGRAM_NAME_WIDTH + 1),
                    ORACLE_TITLE_LINE_2,
                    RENDERED_TIME,
                    oracleUserOptions(), null,
                    "S".repeat(ORACLE_SELECTED_OPTION_WIDTH + 1),
                    RENDERED_TIME, null, false,
                    "F".repeat(MenuResponse.SCREEN_FIELD_ID_WIDTH + 1),
                    OPAQUE_ROUTE, null);

            assertThat(violationsOf(overWide))
                    .as("one per over-wide component and nothing else")
                    .hasSize(6)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("transactionName", "title01", "currentDate",
                            "programName", "selectedOption", "focusScreenFieldId");
        }
    }

    @Nested
    @DisplayName("J - the wire shape, immutability and tolerance")
    class WireShapeAndValueSemantics {

        @Test
        @DisplayName("publishes exactly the expected property list, in the record's declared order")
        void publishesExactlyTheExpectedPropertyList() throws Exception {
            assertThat(propertyNamesOf(payloadOf(populatedUserMenu())))
                    .as("declared order, which is the two maps' own header order first")
                    .containsExactlyElementsOf(USER_MENU_PAYLOAD_PROPERTIES);
            assertThat(propertyNamesOf(payloadOf(populatedAdminMenu())))
                    .containsExactlyElementsOf(ADMIN_MENU_PAYLOAD_PROPERTIES);
            assertThat(USER_MENU_PAYLOAD_PROPERTIES.subList(0, 6))
                    .as("the six header items lead, in map declaration order")
                    .containsExactly("transactionName", "title01", "currentDate", "programName",
                            "title02", "currentTime");
        }

        @Test
        @DisplayName("omits every absent property rather than emitting a null")
        void omitsEveryAbsentProperty() throws Exception {
            MenuResponse sparse = new MenuResponse(null, null, null, null, null, null,
                    oracleUserOptions(), null, null, null, null, false, null, null, null);

            JsonNode payload = payloadOf(sparse);

            assertThat(propertyNamesOf(payload))
                    .as("only the collection that is present, and the primitive switch")
                    .containsExactly("userMenuOptions", "errorFlag");
            assertThat(payload.toString()).doesNotContain("null");
            assertThat(payload.get("errorFlag").isBoolean())
                    .as("a primitive is always present, because absence is not one of its states")
                    .isTrue();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property")
        void toleratesAnUnknownIncomingProperty() throws Exception {
            ObjectMapper mapper = moduleEquivalentMapper();
            String withExtras = mapper.writeValueAsString(populatedUserMenu())
                    .replaceFirst("\\{", "{\"unknownProperty\":\"ignored\",\"anotherOne\":7,");

            MenuResponse back = mapper.readValue(withExtras, MenuResponse.class);

            assertThat(back).isEqualTo(populatedUserMenu());
            assertThat(back.userMenuOptions()).hasSize(ORACLE_USER_MENU_OPTION_COUNT);
        }

        @Test
        @DisplayName("round-trips every component through the module's mapper without loss")
        void roundTripsEveryComponentWithoutLoss() throws Exception {
            ObjectMapper mapper = moduleEquivalentMapper();

            for (MenuResponse original : List.of(populatedUserMenu(), populatedAdminMenu())) {
                MenuResponse back =
                        mapper.readValue(mapper.writeValueAsString(original), MenuResponse.class);

                assertThat(back).isEqualTo(original).hasSameHashCodeAs(original);
                assertThat(back.transactionName()).isEqualTo(original.transactionName());
                assertThat(back.title01()).isEqualTo(original.title01());
                assertThat(back.currentDate()).isEqualTo(original.currentDate());
                assertThat(back.programName()).isEqualTo(original.programName());
                assertThat(back.title02()).isEqualTo(original.title02());
                assertThat(back.currentTime()).isEqualTo(original.currentTime());
                assertThat(back.userMenuOptions()).isEqualTo(original.userMenuOptions());
                assertThat(back.adminMenuOptions()).isEqualTo(original.adminMenuOptions());
                assertThat(back.selectedOption()).isEqualTo(original.selectedOption());
                assertThat(back.message()).isEqualTo(original.message());
                assertThat(back.messageSeverity()).isEqualTo(original.messageSeverity());
                assertThat(back.errorFlag()).isEqualTo(original.errorFlag());
                assertThat(back.focusScreenFieldId()).isEqualTo(original.focusScreenFieldId());
                assertThat(back.nextRoute()).isEqualTo(original.nextRoute());
                assertThat(back.navigationContext()).isEqualTo(original.navigationContext());
                assertThat(back.carriesUserMenu()).isEqualTo(original.carriesUserMenu());
                assertThat(back.carriesAdminMenu()).isEqualTo(original.carriesAdminMenu());
            }
        }

        @Test
        @DisplayName("enforces the menu invariant on the way in as well as on the way out")
        void enforcesTheMenuInvariantOnTheWayIn() {
            ObjectMapper mapper = moduleEquivalentMapper();

            assertThatThrownBy(() -> mapper.readValue("{\"transactionName\":\"CM00\"}",
                    MenuResponse.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessageContaining("exactly one option collection");
        }

        @Test
        @DisplayName("is immutable by construction: no component can be replaced in place")
        void isImmutableByConstruction() {
            // Demonstrated by construction rather than by inspecting the type. A record has no mutator
            // to call, so the proof available at runtime is that repeated reads agree, that a
            // "modified" value requires a wholly new instance, and that the original is unaffected by
            // producing one. The collections were already shown detached and unmodifiable above.
            MenuResponse original = populatedUserMenu();

            assertThat(original.title01()).isSameAs(original.title01());
            assertThat(original.userMenuOptions()).isSameAs(original.userMenuOptions());
            assertThat(original.navigationContext()).isSameAs(original.navigationContext());

            MenuResponse rebuilt = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    original.currentDate(),
                    original.currentTime(), original.userMenuOptions(), "02", original.message(),
                    original.messageSeverity(), original.errorFlag(), original.focusScreenFieldId(),
                    original.nextRoute(), original.navigationContext());

            assertThat(rebuilt).as("a changed entry means a new instance")
                    .isNotEqualTo(original).isNotSameAs(original);
            assertThat(original.selectedOption()).as("the original is untouched").isEqualTo("01");
            assertThat(rebuilt.selectedOption()).isEqualTo("02");
        }

        @Test
        @DisplayName("is not a problem document and carries no problem-document member")
        void isNotAProblemDocument() throws Exception {
            // This contract reports a rejected interaction in its own message line and error switch,
            // never as a media-type-specific problem document, so none of that vocabulary appears
            // here - including on the rejection path, where the temptation would be greatest.
            for (JsonNode payload : List.of(payloadOf(populatedUserMenu()),
                    payloadOf(populatedAdminMenu()))) {
                for (String member : List.of("type", "title", "status", "detail", "instance",
                        "properties", "problem")) {
                    assertThat(payload.has(member))
                            .as("a problem-document member must not appear: %s", member).isFalse();
                }
                assertThat(payload.has("message")).as("the legacy message line is the channel")
                        .isTrue();
                assertThat(payload.has("errorFlag")).isTrue();
            }
        }

        @Test
        @DisplayName("carries no screen-layout artefact of any kind")
        void carriesNoScreenLayoutArtefact() throws Exception {
            JsonNode payload = payloadOf(populatedUserMenu());

            for (String name : propertyNamesOf(payload)) {
                assertThat(name)
                        .as("a symbolic map suffixes each field with a control byte; none appears here")
                        .doesNotEndWith("L").doesNotEndWith("F").doesNotEndWith("A")
                        .doesNotEndWith("C").doesNotEndWith("P").doesNotEndWith("H")
                        .doesNotEndWith("V").doesNotEndWith("I").doesNotEndWith("O");
                assertThat(name).doesNotContain("Filler").doesNotContain("filler")
                        .doesNotContain("Tioa").doesNotContain("Dfh").doesNotContain("Map");
            }
            assertThat(payload.has("optn001")).isFalse();
            assertThat(payload.has("OPTN001")).isFalse();
        }
    }

    @Nested
    @DisplayName("K - every published member is exercised")
    class PublishedSurface {

        @Test
        @DisplayName("exercises all fifteen accessors on both menus")
        void exercisesAllFifteenAccessors() {
            MenuResponse user = populatedUserMenu();
            MenuResponse admin = populatedAdminMenu();

            assertThat(user.transactionName()).isEqualTo(ORACLE_USER_MENU_TRANSACTION_NAME);
            assertThat(user.title01()).isEqualTo(ORACLE_TITLE_LINE_1);
            assertThat(user.currentDate()).isEqualTo(RENDERED_DATE);
            assertThat(user.programName()).isEqualTo(ORACLE_USER_MENU_PROGRAM_NAME);
            assertThat(user.title02()).isEqualTo(ORACLE_TITLE_LINE_2);
            assertThat(user.currentTime()).isEqualTo(RENDERED_TIME);
            assertThat(user.userMenuOptions()).hasSize(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(user.adminMenuOptions()).isNull();
            assertThat(user.selectedOption()).isEqualTo("01");
            assertThat(user.message()).isEqualTo(ORACLE_USER_COMING_SOON);
            assertThat(user.messageSeverity())
                    .isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
            assertThat(user.errorFlag()).isFalse();
            assertThat(user.focusScreenFieldId()).isEqualTo(FOCUS_FIELD);
            assertThat(user.nextRoute()).isEqualTo(OPAQUE_ROUTE);
            assertThat(user.navigationContext()).isEqualTo(navigationState());

            assertThat(admin.transactionName()).isEqualTo(ORACLE_ADMIN_MENU_TRANSACTION_NAME);
            assertThat(admin.userMenuOptions()).isNull();
            assertThat(admin.adminMenuOptions()).hasSize(ORACLE_ADMIN_MENU_OPTION_COUNT);
            assertThat(admin.selectedOption()).isEqualTo("  ");
            assertThat(admin.message()).isEqualTo(ORACLE_INVALID_OPTION_MESSAGE);
            assertThat(admin.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(admin.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("exercises both accessors on both option shapes, over every canonical row")
        void exercisesBothAccessorsOnBothOptionShapes() {
            for (int index = 0; index < CANONICAL_USER_MENU_OPTIONS.size(); index++) {
                MenuResponse.UserMenuOption row = CANONICAL_USER_MENU_OPTIONS.get(index);
                assertThat(row.number()).isEqualTo(index + 1);
                assertThat(row.label()).isEqualTo(ORACLE_USER_LABELS.get(index));
            }
            for (int index = 0; index < CANONICAL_ADMIN_MENU_OPTIONS.size(); index++) {
                MenuResponse.AdminMenuOption row =
                        CANONICAL_ADMIN_MENU_OPTIONS.get(index);
                assertThat(row.number()).isEqualTo(index + 1);
                assertThat(row.label()).isEqualTo(ORACLE_ADMIN_LABELS.get(index));
            }
        }

        @Test
        @DisplayName("gives both option shapes value equality, a stable hash and a readable rendering")
        void givesBothOptionShapesValueSemantics() {
            MenuResponse.UserMenuOption userRow = new MenuResponse.UserMenuOption(1, "Account View");
            MenuResponse.UserMenuOption sameUserMenuRow =
                    new MenuResponse.UserMenuOption(1, "Account View");
            MenuResponse.AdminMenuOption adminRow =
                    new MenuResponse.AdminMenuOption(1, "User List (Security)");
            MenuResponse.AdminMenuOption sameAdminRow =
                    new MenuResponse.AdminMenuOption(1, "User List (Security)");

            assertThat(userRow).isEqualTo(sameUserMenuRow).hasSameHashCodeAs(sameUserMenuRow);
            assertThat(userRow).isNotEqualTo(new MenuResponse.UserMenuOption(2, "Account View"));
            assertThat(userRow).isNotEqualTo(new MenuResponse.UserMenuOption(1, "Account Update"));
            assertThat(userRow.toString()).contains("number=1").contains("label=Account View");

            assertThat(adminRow).isEqualTo(sameAdminRow).hasSameHashCodeAs(sameAdminRow);
            assertThat(adminRow)
                    .isNotEqualTo(new MenuResponse.AdminMenuOption(2, "User List (Security)"));
            assertThat(adminRow.toString()).contains("number=1")
                    .contains("label=User List (Security)");
        }

        @Test
        @DisplayName("gives the response value equality, a stable hash and a readable rendering")
        void givesTheResponseValueSemantics() {
            MenuResponse first = populatedUserMenu();
            MenuResponse second = populatedUserMenu();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isEqualTo(first);
            assertThat(first).isNotEqualTo(populatedAdminMenu());
            assertThat(first).isNotEqualTo(null);
            assertThat(first).isNotEqualTo(ORACLE_TITLE_LINE_1);

            assertThat(first.toString()).startsWith("MenuResponse[")
                    .contains("transactionName=" + ORACLE_USER_MENU_TRANSACTION_NAME)
                    .contains("programName=" + ORACLE_USER_MENU_PROGRAM_NAME)
                    .contains("errorFlag=false")
                    .endsWith("]");
        }

        @Test
        @DisplayName("publishes both canonical lists unmodifiable and equal to the hand-built oracles")
        void publishesBothCanonicalListsUnmodifiable() {
            MenuResponse.UserMenuOption extraUserMenuRow = new MenuResponse.UserMenuOption(11, "Extra");
            MenuResponse.AdminMenuOption extraAdminRow = new MenuResponse.AdminMenuOption(5, "Extra");

            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .containsExactlyElementsOf(oracleUserOptions());
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .containsExactlyElementsOf(oracleAdminOptions());
            assertThatThrownBy(() -> CANONICAL_USER_MENU_OPTIONS.add(extraUserMenuRow))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> CANONICAL_ADMIN_MENU_OPTIONS.add(extraAdminRow))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("accepts the canonical lists through both factory methods")
        void acceptsTheCanonicalListsThroughBothFactories() {
            MenuResponse user = MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME,
                    CANONICAL_USER_MENU_OPTIONS, null, null, null, false, null, null,
                    null);
            MenuResponse admin = MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME,
                    CANONICAL_ADMIN_MENU_OPTIONS, null, null, null, false, null, null,
                    null);

            assertThat(user.carriesUserMenu()).isTrue();
            assertThat(user.userMenuOptions())
                    .containsExactlyElementsOf(CANONICAL_USER_MENU_OPTIONS);
            assertThat(admin.carriesAdminMenu()).isTrue();
            assertThat(admin.adminMenuOptions())
                    .containsExactlyElementsOf(CANONICAL_ADMIN_MENU_OPTIONS);
        }

        @Test
        @DisplayName("a label is neither re-padded to the catalog width nor re-trimmed of its own "
                + "padding")
        void neitherPadsNorTrimsALabel() throws Exception {
            MenuResponse.UserMenuOption trimmedRow =
                    new MenuResponse.UserMenuOption(8, ORACLE_OPTION_EIGHT_LABEL);
            MenuResponse.UserMenuOption paddedRow =
                    new MenuResponse.UserMenuOption(8, ORACLE_OPTION_EIGHT_LABEL_PADDED);

            assertThat(ORACLE_OPTION_EIGHT_LABEL_PADDED).hasSize(ORACLE_OPTION_LABEL_WIDTH);
            assertThat(trimmedRow.label())
                    .as("a trimmed label is never widened to the declared width")
                    .isEqualTo(ORACLE_OPTION_EIGHT_LABEL)
                    .hasSize(ORACLE_OPTION_EIGHT_LABEL.length());
            assertThat(trimmedRow.label().length()).isLessThan(ORACLE_OPTION_LABEL_WIDTH);
            assertThat(paddedRow.label())
                    .as("a label carrying its own padding is never trimmed")
                    .isEqualTo(ORACLE_OPTION_EIGHT_LABEL_PADDED)
                    .hasSize(ORACLE_OPTION_LABEL_WIDTH);
            assertThat(paddedRow.label()).isNotEqualTo(trimmedRow.label());

            assertThat(payloadOf(trimmedRow).get("label").asText())
                    .isEqualTo(ORACLE_OPTION_EIGHT_LABEL);
            assertThat(payloadOf(paddedRow).get("label").asText())
                    .isEqualTo(ORACLE_OPTION_EIGHT_LABEL_PADDED);
        }
    }
}
