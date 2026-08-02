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

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MenuResponse}, the shared outbound contract of legacy transactions
 * {@code CM00} and {@code CA00}.
 *
 * <p>A pure unit test. It starts no application context, opens no connection and launches no container:
 * it constructs the type directly and, where the wire shape is what is under test, serialises with a
 * local mapper configured by hand to match the serialisation settings the module declares in
 * {@code application.yml}.</p>
 *
 * <p>Every expectation is restated independently of the class under test, from the legacy artefacts:
 * {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY} for the header items and their
 * widths, {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy} for the option tables and their
 * counts, and {@code app/cbl/COMEN01C.cbl} with {@code app/cbl/COADM01C.cbl} for the two transaction and
 * program identities.</p>
 *
 * <p>Four properties are asserted with particular care because each one reverses silently if edited
 * carelessly.</p>
 *
 * <ol>
 *   <li><em>The rendered time is eight characters on these two screens.</em> The sign-on mapset is the
 *       one place in the estate where the same item is nine, so this test compares the two figures and
 *       requires them to differ.</li>
 *   <li><em>An option row publishes the number and the label and nothing else.</em> The target program
 *       name and the user-type code are dispatch and authorization inputs; a test below asserts their
 *       absence rather than trusting it.</li>
 *   <li><em>Exactly one menu, at exactly its declared size.</em> Ten user options or four
 *       administrative ones, never both collections and never neither, enforced on construction.</li>
 *   <li><em>The forty-character courtesy title is not the fifty-character common acknowledgement.</em>
 *       Different text, different width, different copybook.</li>
 * </ol>
 *
 * <p>Source checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}.</p>
 */
@DisplayName("MenuResponse - the shared outbound menu contract")
class MenuResponseTest {

    /** The fifteen components, in the order the record declares them. */
    private static final List<String> COMPONENTS_IN_DECLARED_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "userMenuOptions", "adminMenuOptions", "selectedOption", "message", "messageSeverity",
            "errorFlag", "focusScreenFieldId", "nextRoute", "navigationContext");

    /** The six header items in the order both symbolic maps declare them. */
    private static final List<String> HEADER_ITEMS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime");

    /** Width of {@code TRNNAME} on line 24 of both maps: four. */
    private static final int ORACLE_TRANSACTION_NAME_WIDTH = 4;

    /** Width of {@code PGMNAME} on line 42 of both maps: eight. */
    private static final int ORACLE_PROGRAM_NAME_WIDTH = 8;

    /** Width of {@code CURDATE} on line 36 of both maps: eight. */
    private static final int ORACLE_CURRENT_DATE_WIDTH = 8;

    /** Width of {@code CURTIME} on line 54 of both maps: eight, not nine. */
    private static final int ORACLE_CURRENT_TIME_WIDTH = 8;

    /** Widest symbolic field name either mapset declares: seven. */
    private static final int ORACLE_SCREEN_FIELD_ID_WIDTH = 7;

    /** Width of {@code TITLE01} and {@code TITLE02}: forty. */
    private static final int ORACLE_SCREEN_TITLE_WIDTH = 40;

    /** Width of the two shared acknowledgements in {@code app/cpy/CSMSG01Y.cpy}: fifty. */
    private static final int ORACLE_COMMON_MESSAGE_WIDTH = 50;

    /** Value of {@code CDEMO-MENU-OPT-COUNT} on line 21 of {@code app/cpy/COMEN02Y.cpy}: ten. */
    private static final int ORACLE_USER_MENU_OPTION_COUNT = 10;

    /** Value of {@code CDEMO-ADMIN-OPT-COUNT} on line 20 of {@code app/cpy/COADM02Y.cpy}: four. */
    private static final int ORACLE_ADMIN_MENU_OPTION_COUNT = 4;

    /** The ten user labels in table order, trimmed of the copybook's padding to display form. */
    private static final List<String> ORACLE_USER_LABELS = List.of(
            "Account View", "Account Update", "Credit Card List", "Credit Card View",
            "Credit Card Update", "Transaction List", "Transaction View", "Transaction Add",
            "Transaction Reports", "Bill Payment");

    /** The four administrative labels in table order, trimmed to display form. */
    private static final List<String> ORACLE_ADMIN_LABELS = List.of(
            "User List (Security)", "User Add (Security)", "User Update (Security)",
            "User Delete (Security)");

    /** The inactive alternative label commented out on line 69 of {@code app/cpy/COMEN02Y.cpy}. */
    private static final String INACTIVE_OPTION_EIGHT_LABEL = "Transaction Add (Admin Only)";

    /** A rendered date at the map's own width. */
    private static final String CURRENT_DATE = "01/31/24";

    /** A rendered time at the map's own width. */
    private static final String CURRENT_TIME = "10:15:30";

    /** A declarative route label, opaque to this contract. */
    private static final String ROUTE_ACCOUNT_VIEW = "account-view";

    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                                JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static JsonNode payloadOf(MenuResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static MenuResponse userMenu() {
        return MenuResponse.forUserMenu(CURRENT_DATE, CURRENT_TIME,
                MenuResponse.CANONICAL_USER_MENU_OPTIONS, "01", null, null, false, null,
                ROUTE_ACCOUNT_VIEW, NavigationContext.empty());
    }

    private static MenuResponse adminMenu() {
        return MenuResponse.forAdminMenu(CURRENT_DATE, CURRENT_TIME,
                MenuResponse.CANONICAL_ADMIN_MENU_OPTIONS, "  ", "Please enter a valid option ...",
                MenuResponse.MessageSeverity.ERROR, true, "OPTION", null,
                NavigationContext.empty());
    }

    private static Set<ConstraintViolation<MenuResponse>> violationsOf(MenuResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    /** Builds a user collection of the requested size, so a wrong size can be offered on purpose. */
    private static List<MenuResponse.UserMenuOption> userOptionsOfSize(int size) {
        List<MenuResponse.UserMenuOption> options = new ArrayList<>();
        for (int index = 1; index <= size; index++) {
            options.add(new MenuResponse.UserMenuOption(index, "Option " + index));
        }
        return List.copyOf(options);
    }

    /** Builds an administrative collection of the requested size, for the same reason. */
    private static List<MenuResponse.AdminMenuOption> adminOptionsOfSize(int size) {
        List<MenuResponse.AdminMenuOption> options = new ArrayList<>();
        for (int index = 1; index <= size; index++) {
            options.add(new MenuResponse.AdminMenuOption(index, "Option " + index));
        }
        return List.copyOf(options);
    }

    @Nested
    @DisplayName("The component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the fifteen components, in declaration order")
        void declaresExactlyFifteenComponentsInOrder() {
            List<String> declared = Arrays.stream(MenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_DECLARED_ORDER).hasSize(15);
        }

        @Test
        @DisplayName("opens with the whole common header, in the order both maps declare it")
        void opensWithTheWholeCommonHeaderInMapOrder() {
            List<String> firstSix = Arrays.stream(MenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .limit(HEADER_ITEMS_IN_MAP_ORDER.size())
                    .toList();

            assertThat(firstSix).containsExactlyElementsOf(HEADER_ITEMS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("names the focus hint, the destination, the titles and the successor state the way "
                + "every other contract does")
        void namesEverySharedConceptCanonically() {
            List<String> declared = Arrays.stream(MenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .contains("focusScreenFieldId", "nextRoute", "title01", "title02",
                            "navigationContext")
                    .doesNotContain("fieldToFocus", "focusField", "focusFieldName", "route",
                            "navigation", "screenTitleLine1", "screenTitleLine2", "screenTitle1",
                            "titleLine1");
        }

        @Test
        @DisplayName("carries characters throughout apart from the two collections, the severity and "
                + "the failure flag, and no domain enumeration anywhere")
        void carriesCharactersThroughoutAndNoDomainEnumeration() {
            Map<String, Class<?>> types = new LinkedHashMap<>();
            for (RecordComponent component : MenuResponse.class.getRecordComponents()) {
                types.put(component.getName(), component.getType());
            }

            assertThat(types.get("errorFlag")).isEqualTo(boolean.class);
            assertThat(types.get("userMenuOptions")).isEqualTo(List.class);
            assertThat(types.get("adminMenuOptions")).isEqualTo(List.class);
            assertThat(types.get("messageSeverity")).isEqualTo(MenuResponse.MessageSeverity.class);
            assertThat(types.get("navigationContext")).isEqualTo(NavigationContext.class);
            types.entrySet().stream()
                    .filter(entry -> !List.of("errorFlag", "userMenuOptions", "adminMenuOptions",
                            "messageSeverity", "navigationContext").contains(entry.getKey()))
                    .forEach(entry -> assertThat(entry.getValue())
                            .as("component %s", entry.getKey())
                            .isEqualTo(String.class));

            assertThat(types.values()).noneMatch(type -> type.getName()
                    .startsWith("com.carddemo.domain."));
        }
    }

    @Nested
    @DisplayName("The declared widths")
    class DeclaredWidths {

        @Test
        @DisplayName("match the two symbolic maps item for item")
        void matchTheTwoSymbolicMaps() {
            assertThat(MenuResponse.TRANSACTION_NAME_WIDTH)
                    .isEqualTo(ORACLE_TRANSACTION_NAME_WIDTH);
            assertThat(MenuResponse.PROGRAM_NAME_WIDTH).isEqualTo(ORACLE_PROGRAM_NAME_WIDTH);
            assertThat(MenuResponse.CURRENT_DATE_WIDTH).isEqualTo(ORACLE_CURRENT_DATE_WIDTH);
            assertThat(MenuResponse.CURRENT_TIME_WIDTH).isEqualTo(ORACLE_CURRENT_TIME_WIDTH);
            assertThat(MenuResponse.SCREEN_TITLE_WIDTH).isEqualTo(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(MenuResponse.SCREEN_FIELD_ID_WIDTH)
                    .isEqualTo(ORACLE_SCREEN_FIELD_ID_WIDTH);
        }

        @Test
        @DisplayName("keep the menu time at eight characters and never adopt the sign-on mapset's nine")
        void keepTheMenuTimeAtEightCharacters() {
            assertThat(MenuResponse.CURRENT_TIME_WIDTH).isEqualTo(ORACLE_CURRENT_TIME_WIDTH)
                    .isEqualTo(MenuResponse.CURRENT_DATE_WIDTH);
            assertThat(MenuResponse.CURRENT_TIME_WIDTH)
                    .isNotEqualTo(SignOnResponse.CURRENT_TIME_LENGTH);
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH).isEqualTo(9);
        }

        @Test
        @DisplayName("share the focus bound with every other screen contract")
        void shareTheFocusBoundWithEveryOtherScreenContract() {
            assertThat(MenuResponse.SCREEN_FIELD_ID_WIDTH)
                    .isEqualTo(SignOnResponse.SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("bound the focus hint at seven characters")
        void boundTheFocusHintAtSeven() {
            MenuResponse overTheBound = MenuResponse.forUserMenu(CURRENT_DATE, CURRENT_TIME,
                    MenuResponse.CANONICAL_USER_MENU_OPTIONS, null, null, null, false, "EIGHTLET",
                    null, null);

            assertThat(violationsOf(overTheBound)).hasSize(1);
            assertThat(violationsOf(overTheBound).iterator().next().getPropertyPath())
                    .hasToString("focusScreenFieldId");
        }

        @Test
        @DisplayName("bound each header item at the map's own width")
        void boundEachHeaderItemAtTheMapWidth() {
            MenuResponse tooWide = new MenuResponse("CM000", null, "010101010", null, null,
                    "101530999", MenuResponse.CANONICAL_USER_MENU_OPTIONS, null, null, null, null,
                    false, null, null, null);

            assertThat(violationsOf(tooWide)).extracting(violation -> violation.getPropertyPath()
                            .toString())
                    .containsExactlyInAnyOrder("transactionName", "currentDate", "currentTime");
        }

        @Test
        @DisplayName("record the narrower rendered message field without ever applying it")
        void recordTheNarrowerMessageFieldWithoutApplyingIt() {
            assertThat(MenuResponse.MESSAGE_WIDTH).isEqualTo(80);
            assertThat(MenuResponse.SCREEN_MESSAGE_FIELD_WIDTH).isEqualTo(78);
            assertThat(MenuResponse.COMMON_MESSAGE_WIDTH).isEqualTo(ORACLE_COMMON_MESSAGE_WIDTH);
        }
    }

    @Nested
    @DisplayName("An option row")
    class OptionRow {

        @Test
        @DisplayName("publishes the number and the label and nothing else, on either menu")
        void publishesTheNumberAndTheLabelOnly() {
            List<String> userComponents = Arrays.stream(
                            MenuResponse.UserMenuOption.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            List<String> adminComponents = Arrays.stream(
                            MenuResponse.AdminMenuOption.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(userComponents).containsExactly("number", "label");
            assertThat(adminComponents).containsExactly("number", "label");
        }

        @Test
        @DisplayName("withholds the dispatch target and the authorization input under any spelling")
        void withholdsDispatchAndAuthorizationMetadata() {
            List<String> lowered = new ArrayList<>();
            Arrays.stream(MenuResponse.UserMenuOption.class.getRecordComponents())
                    .forEach(component -> lowered.add(component.getName()
                            .toLowerCase(Locale.ROOT)));
            Arrays.stream(MenuResponse.AdminMenuOption.class.getRecordComponents())
                    .forEach(component -> lowered.add(component.getName()
                            .toLowerCase(Locale.ROOT)));

            assertThat(lowered).noneMatch(name -> name.contains("program"))
                    .noneMatch(name -> name.contains("pgm"))
                    .noneMatch(name -> name.contains("usertype"))
                    .noneMatch(name -> name.contains("usrtype"))
                    .noneMatch(name -> name.contains("role"));
        }

        @Test
        @DisplayName("declares no width constant for a value it does not publish")
        void declaresNoWidthConstantForAnUnpublishedValue() {
            List<String> constants = Arrays.stream(MenuResponse.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .toList();

            assertThat(constants).doesNotContain("OPTION_PROGRAM_NAME_WIDTH",
                    "USER_OPTION_USER_TYPE_WIDTH", "STANDARD_USER_TYPE_CODE");
            assertThat(constants).contains("OPTION_NUMBER_WIDTH", "OPTION_LABEL_WIDTH");
        }

        @Test
        @DisplayName("stays a distinct type per menu, with no shared supertype and no conversion")
        void staysADistinctTypePerMenu() {
            assertThat(MenuResponse.UserMenuOption.class
                    .isAssignableFrom(MenuResponse.AdminMenuOption.class)).isFalse();
            assertThat(MenuResponse.AdminMenuOption.class
                    .isAssignableFrom(MenuResponse.UserMenuOption.class)).isFalse();
            assertThat(MenuResponse.UserMenuOption.class.getInterfaces()).isEmpty();
            assertThat(MenuResponse.AdminMenuOption.class.getInterfaces()).isEmpty();
            assertThat(MenuResponse.UserMenuOption.class.getSuperclass())
                    .isEqualTo(java.lang.Record.class);
            assertThat(MenuResponse.AdminMenuOption.class.getSuperclass())
                    .isEqualTo(java.lang.Record.class);
        }

        @Test
        @DisplayName("bounds the label at the copybook's declared width without widening a value")
        void boundsTheLabelWithoutWideningIt() {
            MenuResponse.UserMenuOption option = new MenuResponse.UserMenuOption(1, "Account View");

            assertThat(MenuResponse.OPTION_LABEL_WIDTH).isEqualTo(35);
            assertThat(option.label()).isEqualTo("Account View").hasSize(12);
            assertThat(MenuResponse.OPTION_NUMBER_WIDTH).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("The canonical option lists")
    class CanonicalOptionLists {

        @Test
        @DisplayName("carry the copybook's counts, numbering and labels in table order")
        void carryTheCopybookCountsNumberingAndLabels() {
            assertThat(MenuResponse.USER_MENU_OPTION_COUNT)
                    .isEqualTo(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(MenuResponse.ADMIN_MENU_OPTION_COUNT)
                    .isEqualTo(ORACLE_ADMIN_MENU_OPTION_COUNT);

            assertThat(MenuResponse.CANONICAL_USER_MENU_OPTIONS)
                    .hasSize(ORACLE_USER_MENU_OPTION_COUNT)
                    .extracting(MenuResponse.UserMenuOption::label)
                    .containsExactlyElementsOf(ORACLE_USER_LABELS);
            assertThat(MenuResponse.CANONICAL_USER_MENU_OPTIONS)
                    .extracting(MenuResponse.UserMenuOption::number)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            assertThat(MenuResponse.CANONICAL_ADMIN_MENU_OPTIONS)
                    .hasSize(ORACLE_ADMIN_MENU_OPTION_COUNT)
                    .extracting(MenuResponse.AdminMenuOption::label)
                    .containsExactlyElementsOf(ORACLE_ADMIN_LABELS);
            assertThat(MenuResponse.CANONICAL_ADMIN_MENU_OPTIONS)
                    .extracting(MenuResponse.AdminMenuOption::number)
                    .containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("carry the active label for option eight and never the commented-out alternative")
        void carryTheActiveLabelForOptionEight() {
            assertThat(MenuResponse.CANONICAL_USER_MENU_OPTIONS.get(7).label())
                    .isEqualTo("Transaction Add")
                    .isNotEqualTo(INACTIVE_OPTION_EIGHT_LABEL);
            assertThat(MenuResponse.CANONICAL_USER_MENU_OPTIONS)
                    .extracting(MenuResponse.UserMenuOption::label)
                    .doesNotContain(INACTIVE_OPTION_EIGHT_LABEL);
        }

        @Test
        @DisplayName("exclude the copybook's surplus table capacity entirely")
        void excludeTheSurplusTableCapacity() {
            assertThat(MenuResponse.CANONICAL_USER_MENU_OPTIONS).hasSizeLessThan(12);
            assertThat(MenuResponse.CANONICAL_ADMIN_MENU_OPTIONS).hasSizeLessThan(9);
            assertThat(MenuResponse.CANONICAL_USER_MENU_OPTIONS)
                    .noneMatch(option -> option.label() == null || option.label().isBlank());
            assertThat(MenuResponse.CANONICAL_ADMIN_MENU_OPTIONS)
                    .noneMatch(option -> option.label() == null || option.label().isBlank());
        }

        @Test
        @DisplayName("are immutable and safe to share")
        void areImmutableAndSafeToShare() {
            assertThatThrownBy(() -> MenuResponse.CANONICAL_USER_MENU_OPTIONS
                    .add(new MenuResponse.UserMenuOption(11, "Injected")))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> MenuResponse.CANONICAL_ADMIN_MENU_OPTIONS
                    .add(new MenuResponse.AdminMenuOption(5, "Injected")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("The construction invariants")
    class ConstructionInvariants {

        @Test
        @DisplayName("reject a response that carries both option collections")
        void rejectBothCollections() {
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null,
                    MenuResponse.CANONICAL_USER_MENU_OPTIONS,
                    MenuResponse.CANONICAL_ADMIN_MENU_OPTIONS, null, null, null, false, null, null,
                    null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one option collection")
                    .hasMessageContaining("both were supplied");
        }

        @Test
        @DisplayName("reject a response that carries neither option collection")
        void rejectNeitherCollection() {
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, null, null,
                    null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one option collection")
                    .hasMessageContaining("neither was supplied");
        }

        @Test
        @DisplayName("reject a user menu that does not render all ten rows")
        void rejectAShortUserMenu() {
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null,
                    userOptionsOfSize(9), null, null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("the user menu renders exactly 10 options")
                    .hasMessageContaining("9 were supplied");
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null,
                    userOptionsOfSize(0), null, null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null,
                    userOptionsOfSize(12), null, null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("reject an administrative menu that does not render all four rows")
        void rejectAShortAdminMenu() {
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, null,
                    adminOptionsOfSize(3), null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("the administrative menu renders exactly 4 options")
                    .hasMessageContaining("3 were supplied");
            assertThatThrownBy(() -> new MenuResponse(null, null, null, null, null, null, null,
                    adminOptionsOfSize(9), null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("admit exactly the two shapes the two screens can render")
        void admitExactlyTheTwoRenderableShapes() {
            assertThat(userMenu().userMenuOptions()).hasSize(ORACLE_USER_MENU_OPTION_COUNT);
            assertThat(userMenu().adminMenuOptions()).isNull();
            assertThat(adminMenu().adminMenuOptions()).hasSize(ORACLE_ADMIN_MENU_OPTION_COUNT);
            assertThat(adminMenu().userMenuOptions()).isNull();
        }

        @Test
        @DisplayName("detach the supplied collection from the caller")
        void detachTheSuppliedCollection() {
            List<MenuResponse.UserMenuOption> mutable =
                    new ArrayList<>(MenuResponse.CANONICAL_USER_MENU_OPTIONS);

            MenuResponse response = new MenuResponse(null, null, null, null, null, null, mutable,
                    null, null, null, null, false, null, null, null);
            mutable.clear();

            assertThat(response.userMenuOptions()).hasSize(ORACLE_USER_MENU_OPTION_COUNT);
            assertThatThrownBy(() -> response.userMenuOptions()
                    .add(new MenuResponse.UserMenuOption(11, "Injected")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("make the two menu tests always disagree")
        void makeTheTwoMenuTestsAlwaysDisagree() {
            assertThat(userMenu().carriesUserMenu()).isTrue();
            assertThat(userMenu().carriesAdminMenu()).isFalse();
            assertThat(adminMenu().carriesAdminMenu()).isTrue();
            assertThat(adminMenu().carriesUserMenu()).isFalse();
        }
    }

    @Nested
    @DisplayName("The two factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("fill the user menu's own transaction and program identity")
        void fillTheUserMenuIdentity() {
            MenuResponse response = userMenu();

            assertThat(MenuResponse.USER_MENU_TRANSACTION_NAME).isEqualTo("CM00")
                    .hasSize(ORACLE_TRANSACTION_NAME_WIDTH);
            assertThat(MenuResponse.USER_MENU_PROGRAM_NAME).isEqualTo("COMEN01C")
                    .hasSize(ORACLE_PROGRAM_NAME_WIDTH);
            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME);
            assertThat(response.programName()).isEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME);
        }

        @Test
        @DisplayName("fill the administrative menu's own, different, identity")
        void fillTheAdminMenuIdentity() {
            MenuResponse response = adminMenu();

            assertThat(MenuResponse.ADMIN_MENU_TRANSACTION_NAME).isEqualTo("CA00")
                    .hasSize(ORACLE_TRANSACTION_NAME_WIDTH)
                    .isNotEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME);
            assertThat(MenuResponse.ADMIN_MENU_PROGRAM_NAME).isEqualTo("COADM01C")
                    .hasSize(ORACLE_PROGRAM_NAME_WIDTH)
                    .isNotEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME);
            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.ADMIN_MENU_TRANSACTION_NAME);
            assertThat(response.programName()).isEqualTo(MenuResponse.ADMIN_MENU_PROGRAM_NAME);
        }

        @Test
        @DisplayName("fill both title lines identically on both menus, at their full declared width")
        void fillBothTitleLinesIdenticallyOnBothMenus() {
            assertThat(MenuResponse.SCREEN_TITLE_LINE_1).hasSize(ORACLE_SCREEN_TITLE_WIDTH)
                    .startsWith("      ")
                    .endsWith("       ")
                    .contains("AWS Mainframe Modernization");
            assertThat(MenuResponse.SCREEN_TITLE_LINE_2).hasSize(ORACLE_SCREEN_TITLE_WIDTH)
                    .contains("CardDemo");

            assertThat(userMenu().title01()).isEqualTo(MenuResponse.SCREEN_TITLE_LINE_1);
            assertThat(userMenu().title02()).isEqualTo(MenuResponse.SCREEN_TITLE_LINE_2);
            assertThat(adminMenu().title01()).isEqualTo(MenuResponse.SCREEN_TITLE_LINE_1);
            assertThat(adminMenu().title02()).isEqualTo(MenuResponse.SCREEN_TITLE_LINE_2);
        }

        @Test
        @DisplayName("carry the rendered date and time through unaltered")
        void carryTheRenderedDateAndTimeUnaltered() {
            assertThat(userMenu().currentDate()).isEqualTo(CURRENT_DATE)
                    .hasSize(ORACLE_CURRENT_DATE_WIDTH);
            assertThat(userMenu().currentTime()).isEqualTo(CURRENT_TIME)
                    .hasSize(ORACLE_CURRENT_TIME_WIDTH);
        }

        @Test
        @DisplayName("carry the destination the caller nominates without resolving it")
        void carryTheDestinationWithoutResolvingIt() {
            assertThat(userMenu().nextRoute()).isEqualTo(ROUTE_ACCOUNT_VIEW);
            assertThat(adminMenu().nextRoute()).isNull();
        }

        @Test
        @DisplayName("reject a collection of the wrong size just as the canonical constructor does")
        void rejectAWrongSizedCollection() {
            assertThatThrownBy(() -> MenuResponse.forUserMenu(CURRENT_DATE, CURRENT_TIME, null, null,
                    null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MenuResponse.forAdminMenu(CURRENT_DATE, CURRENT_TIME,
                    adminOptionsOfSize(2), null, null, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("The wire shape")
    class WireShape {

        @Test
        @DisplayName("publishes the whole header and omits the collection that is absent")
        void publishesTheHeaderAndOmitsTheAbsentCollection() throws Exception {
            JsonNode payload = payloadOf(userMenu());

            assertThat(payload.get("transactionName").asText()).isEqualTo("CM00");
            assertThat(payload.get("title01").asText())
                    .isEqualTo(MenuResponse.SCREEN_TITLE_LINE_1);
            assertThat(payload.get("currentDate").asText()).isEqualTo(CURRENT_DATE);
            assertThat(payload.get("programName").asText()).isEqualTo("COMEN01C");
            assertThat(payload.get("title02").asText())
                    .isEqualTo(MenuResponse.SCREEN_TITLE_LINE_2);
            assertThat(payload.get("currentTime").asText()).isEqualTo(CURRENT_TIME);
            assertThat(payload.has("userMenuOptions")).isTrue();
            assertThat(payload.has("adminMenuOptions")).isFalse();
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.has("messageSeverity")).isFalse();
        }

        @Test
        @DisplayName("publishes each option row as a number and a label only")
        void publishesEachOptionRowAsNumberAndLabelOnly() throws Exception {
            JsonNode rows = payloadOf(userMenu()).get("userMenuOptions");

            assertThat(rows).hasSize(ORACLE_USER_MENU_OPTION_COUNT);
            rows.forEach(row -> {
                List<String> names = new ArrayList<>();
                row.fieldNames().forEachRemaining(names::add);
                assertThat(names).containsExactlyInAnyOrder("number", "label");
            });
            assertThat(rows.get(0).get("number").asInt()).isEqualTo(1);
            assertThat(rows.get(0).get("label").asText()).isEqualTo("Account View");
        }

        @Test
        @DisplayName("publishes no dispatch target and no authorization input anywhere in the payload")
        void publishesNoDispatchTargetAnywhere() throws Exception {
            String json = moduleEquivalentMapper().writeValueAsString(userMenu());

            assertThat(json).doesNotContain("COACTVWC")
                    .doesNotContain("COBIL00C")
                    .doesNotContain("\"userType\"")
                    .doesNotContain("\"programName\":\"COACTVWC\"");
            assertThat(json).contains("\"programName\":\"COMEN01C\"");
        }

        @Test
        @DisplayName("publishes no property named for a superseded spelling")
        void publishesNoSupersededSpelling() throws Exception {
            JsonNode payload = payloadOf(adminMenu());

            assertThat(payload.has("screenTitleLine1")).isFalse();
            assertThat(payload.has("screenTitleLine2")).isFalse();
            assertThat(payload.has("route")).isFalse();
            assertThat(payload.has("navigation")).isFalse();
            assertThat(payload.has("fieldToFocus")).isFalse();
            assertThat(payload.get("nextRoute")).isNull();
            assertThat(payload.get("focusScreenFieldId").asText()).isEqualTo("OPTION");
        }

        @Test
        @DisplayName("carries the message and its intent as separate facts")
        void carriesTheMessageAndItsIntentSeparately() throws Exception {
            JsonNode payload = payloadOf(adminMenu());

            assertThat(payload.get("message").asText())
                    .isEqualTo("Please enter a valid option ...");
            assertThat(payload.get("messageSeverity").asText()).isEqualTo("ERROR");
            assertThat(payload.get("errorFlag").asBoolean()).isTrue();
            assertThat(MenuResponse.MessageSeverity.values())
                    .containsExactly(MenuResponse.MessageSeverity.INFORMATIONAL,
                            MenuResponse.MessageSeverity.ERROR);
        }

        @Test
        @DisplayName("echoes the operator's entry exactly, without blank-to-zero fill")
        void echoesTheOperatorEntryExactly() throws Exception {
            assertThat(payloadOf(adminMenu()).get("selectedOption").asText()).isEqualTo("  ");
            assertThat(payloadOf(userMenu()).get("selectedOption").asText()).isEqualTo("01");
        }

        @Test
        @DisplayName("round-trips through the module's shape without losing a component")
        void roundTripsWithoutLosingAComponent() throws Exception {
            ObjectMapper mapper = moduleEquivalentMapper();
            MenuResponse original = userMenu();

            MenuResponse back = mapper.readValue(mapper.writeValueAsString(original),
                    MenuResponse.class);

            assertThat(back).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(back.userMenuOptions())
                    .containsExactlyElementsOf(MenuResponse.CANONICAL_USER_MENU_OPTIONS);
            assertThat(back.carriesUserMenu()).isTrue();
        }

        @Test
        @DisplayName("refuses a payload that describes neither menu, even on the way in")
        void refusesAPayloadDescribingNeitherMenu() {
            ObjectMapper mapper = moduleEquivalentMapper();

            assertThatThrownBy(() -> mapper.readValue("{\"transactionName\":\"CM00\"}",
                    MenuResponse.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessageContaining("exactly one option collection");
        }
    }

    @Nested
    @DisplayName("The two acknowledgements that must never be merged")
    class DistinctAcknowledgements {

        @Test
        @DisplayName("keep the forty-character courtesy title distinct from the fifty-character "
                + "common message width")
        void keepTheCourtesyTitleDistinctFromTheCommonMessage() {
            assertThat(MenuResponse.SCREEN_TITLE_THANK_YOU)
                    .hasSize(ORACLE_SCREEN_TITLE_WIDTH)
                    .endsWith(" ")
                    .contains("CCDA");
            assertThat(MenuResponse.SCREEN_TITLE_THANK_YOU.length())
                    .isNotEqualTo(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(MenuResponse.COMMON_MESSAGE_WIDTH)
                    .isNotEqualTo(MenuResponse.SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("declare neither shared acknowledgement's text in this contract")
        void declareNeitherSharedAcknowledgementText() {
            List<String> textConstants = Arrays.stream(MenuResponse.class.getDeclaredFields())
                    .filter(field -> field.getType() == String.class)
                    .map(java.lang.reflect.Field::getName)
                    .toList();

            assertThat(textConstants).noneMatch(name -> name.contains("COMMON_MESSAGE_TEXT"))
                    .noneMatch(name -> name.startsWith("MSG_"));
        }
    }
}
