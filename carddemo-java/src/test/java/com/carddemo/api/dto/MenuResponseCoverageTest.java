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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.carddemo.service.MenuOptionCatalog;
import com.carddemo.service.MessageCatalogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for {@link MenuResponse}, its two option records and its severity enumeration.
 *
 * <h2>What is under test</h2>
 *
 * <p>The declared shape of the response that serves both menu screens: its fifteen components, the
 * nine screen widths it bounds and the six components it deliberately leaves unbounded, the two
 * canonical option catalogues and their exact contents, the two factories and what each of them
 * fills and leaves absent, the three invariants its compact constructor enforces, the wire form each
 * component takes, and what its generated text rendering reveals.
 *
 * <h2>Both option shapes publish screen content only, and the copybook asymmetry lives elsewhere</h2>
 *
 * <p>A published option row carries the number the operator types and the text printed beside it, and
 * nothing else. The dispatch target and the user-type code that the two copybook tables also declare
 * are not published in a reply: they are the menu service's own inputs and they stay on
 * {@link MenuOptionCatalog}, where the four-versus-three asymmetry between the user and
 * administrative entries is still asserted. The two response records share no supertype and neither
 * specialises the other, so a caller holding one always knows which menu it belongs to. Both the
 * two-component shape, the absence of the two withheld items and the absence of a shared supertype
 * are asserted directly.
 *
 * <h2>Ten and four, never twelve and nine</h2>
 *
 * <p>The copybook tables are dimensioned larger than their contents: the user table holds twelve
 * slots of which ten are populated, and the administrative table holds nine of which four are
 * populated. Iterating capacity instead of count is the easiest way to render blank rows onto a
 * menu, so this type publishes the two counts and never the two capacities. That the capacities are
 * absent is asserted mechanically, not merely by reading the two count constants.
 *
 * <h2>Exactly one collection, and exactly the count its copybook declares</h2>
 *
 * <p>The card-list and transaction-list responses turn a null row list into an empty list. This one
 * does neither: the two option components answer "which menu is this?", so construction requires
 * exactly one of them to be present and requires a present collection to hold exactly the number of
 * rows its copybook table declares — ten for the user menu, four for the administrative one. Both
 * legacy programs rebuild every row on every send, including each redisplay after a rejected entry,
 * so a shorter, longer, empty or absent collection describes no screen the estate can produce. Each
 * of the three invariants is asserted, including the message the refusal carries.
 *
 * <h2>Three fixed-width acknowledgements exist and all three must stay distinct</h2>
 *
 * <p>The forty-character screen-title acknowledgement published here names the product one way; the
 * fifty-character common-message acknowledgement owned by the service-layer catalogue names it
 * another and sits in a wider field. Substituting one for the other is a byte-equivalence failure,
 * so the divergence is asserted against the catalogue's own constants rather than restated.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>Payloads come from {@link JsonContractSupport#declaredSettingsMapper()}, a mapper carrying the
 * four serialisation settings this module declares, written out by hand in one place rather than
 * copied into every suite. That evidences the shape this type takes <em>under those settings</em>,
 * and nothing more. It is not evidence about the mapper a deployed instance holds, and no assertion
 * below is worded as though it were; {@code ApplicationJsonContractTest} is the in-boundary evidence
 * for the deployed object.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy antecedents cited by the type under test: programs {@code app/cbl/COMEN01C.cbl} and
 * {@code app/cbl/COADM01C.cbl}, option catalogues {@code app/cpy/COMEN02Y.cpy} and {@code
 * app/cpy/COADM02Y.cpy}, title copybook {@code app/cpy/COTTL01Y.cpy}, common messages {@code
 * app/cpy/CSMSG01Y.cpy}, symbolic maps {@code app/cpy-bms/COMEN01.CPY} and {@code
 * app/cpy-bms/COADM01.CPY}. Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * reproduced here.
 */
@DisplayName("MenuResponse :: response contract of legacy transactions CM00 and CA00")
class MenuResponseCoverageTest {

    /*
     * THE CANONICAL OWNERS, READ HERE RATHER THAN RE-DECLARED.
     *
     * The response contract deliberately declares no title text and no option catalogue: the titles
     * are owned by com.carddemo.service.MessageCatalogService and the rows by
     * com.carddemo.service.MenuOptionCatalog, and a second declaration anywhere would be a second
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

    /** The fifteen components, in the order the record declares them. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "userMenuOptions",
            "adminMenuOptions",
            "selectedOption",
            "message",
            "messageSeverity",
            "errorFlag",
            "focusScreenFieldId",
            "nextRoute",
            "navigationContext");

    /** The nine components that carry a declared maximum length. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "selectedOption",
            "message",
            "focusScreenFieldId");

    /** The six components that carry no declared maximum length. */
    private static final List<String> UNBOUNDED_COMPONENTS = List.of(
            "userMenuOptions",
            "adminMenuOptions",
            "messageSeverity",
            "errorFlag",
            "nextRoute",
            "navigationContext");

    /**
     * The two components of a user option, in declaration order.
     *
     * <p>Screen content only. The dispatch target and the user-type code that the copybook table also
     * declares are deliberately absent from the published row: they are inputs the menu service reads
     * and they stay on {@link MenuOptionCatalog}. A reply that carried the program name would hand a
     * client an internal dispatch target it must never need, so the absence is asserted here rather
     * than assumed.
     */
    private static final List<String> EXPECTED_USER_OPTION_COMPONENTS = List.of("number", "label");

    /** The two components of an administrative option, in declaration order. */
    private static final List<String> EXPECTED_ADMIN_OPTION_COMPONENTS = List.of("number", "label");

    /** The two items no published option row carries, on either shape. */
    private static final List<String> WITHHELD_OPTION_COMPONENTS =
            List.of("programName", "userType");

    /** The ten user option labels, in screen order, restated independently. */
    private static final List<String> EXPECTED_USER_LABELS = List.of(
            "Account View",
            "Account Update",
            "Credit Card List",
            "Credit Card View",
            "Credit Card Update",
            "Transaction List",
            "Transaction View",
            "Transaction Add",
            "Transaction Reports",
            "Bill Payment");

    /** The ten user option target programs, in screen order, restated independently. */
    private static final List<String> EXPECTED_USER_PROGRAMS = List.of(
            "COACTVWC",
            "COACTUPC",
            "COCRDLIC",
            "COCRDSLC",
            "COCRDUPC",
            "COTRN00C",
            "COTRN01C",
            "COTRN02C",
            "CORPT00C",
            "COBIL00C");

    /** The four administrative option labels, in screen order, restated independently. */
    private static final List<String> EXPECTED_ADMIN_LABELS = List.of(
            "User List (Security)",
            "User Add (Security)",
            "User Update (Security)",
            "User Delete (Security)");

    /** The four administrative option target programs, in screen order, restated independently. */
    private static final List<String> EXPECTED_ADMIN_PROGRAMS =
            List.of("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    /** Echoed option entry, within the two-character bound. */
    private static final String SELECTED_OPTION = "07";

    /** Message line text, within the eighty-character bound. */
    private static final String MESSAGE = "Please enter a valid option number...";

    /** Opaque screen field label the client should place focus on. */
    private static final String FOCUS_SCREEN_FIELD_ID = "OPTION";

    /** Declarative next route; deliberately unbounded because it is service-owned. */
    private static final String ROUTE = "/api/transactions";

    /** Rendered current date, at the declared eight-character width. */
    private static final String CURRENT_DATE = "01/31/24";

    /** Rendered current time, at the declared eight-character width. */
    private static final String CURRENT_TIME = "10:15:30";

    /** Bean Validation factory, opened once for the class and closed after it. */
    private static ValidatorFactory validatorFactory;

    /** Validator obtained from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the Bean Validation factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the Bean Validation factory opened for this class. */
    @AfterAll
    static void closeValidatorFactory() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Supplies the ten user options paired with their expected number, label and program.
     *
     * @return one argument triple per user option: index, expected label, expected program name
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> userOptionExpectations() {
        return Stream.iterate(0, index -> index + 1)
                .limit(EXPECTED_USER_LABELS.size())
                .map(
                        index ->
                                org.junit.jupiter.params.provider.Arguments.of(
                                        index,
                                        EXPECTED_USER_LABELS.get(index),
                                        EXPECTED_USER_PROGRAMS.get(index)));
    }

    /**
     * Supplies the four administrative options paired with their expected label and program.
     *
     * @return one argument triple per administrative option: index, expected label, expected program
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> adminOptionExpectations() {
        return Stream.iterate(0, index -> index + 1)
                .limit(EXPECTED_ADMIN_LABELS.size())
                .map(
                        index ->
                                org.junit.jupiter.params.provider.Arguments.of(
                                        index,
                                        EXPECTED_ADMIN_LABELS.get(index),
                                        EXPECTED_ADMIN_PROGRAMS.get(index)));
    }

    /**
     * Builds a response carrying the named text component and nothing else beyond the one option
     * collection every response must carry.
     *
     * <p>The canonical user collection is always supplied, because construction rejects a response
     * that carries neither collection: a menu response describes one menu screen, and a screen with
     * no rows on it is not a state either legacy program can reach. Every other text component is
     * left null, so an assertion made through this helper is made against one populated value.
     *
     * @param component the component to populate; every other text component is left null
     * @param value the value to place in that component
     * @return a response carrying only the named component and the canonical user collection
     */
    private static MenuResponse carrying(String component, String value) {
        return new MenuResponse(
                "transactionName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                CANONICAL_USER_MENU_OPTIONS,
                null,
                "selectedOption".equals(component) ? value : null,
                "message".equals(component) ? value : null,
                null,
                false,
                "focusScreenFieldId".equals(component) ? value : null,
                "nextRoute".equals(component) ? value : null,
                null);
    }

    /**
     * Builds a user option carrying the named component and nothing else.
     *
     * @param component the row component to populate
     * @param value the value to place in that component
     * @return a user option carrying only the named component
     */
    private static MenuResponse.UserMenuOption userOptionCarrying(String component, String value) {
        return new MenuResponse.UserMenuOption(1, "label".equals(component) ? value : null);
    }

    /**
     * Builds an administrative option carrying the named component and nothing else.
     *
     * @param component the row component to populate
     * @param value the value to place in that component
     * @return an administrative option carrying only the named component
     */
    private static MenuResponse.AdminMenuOption adminOptionCarrying(
            String component, String value) {
        return new MenuResponse.AdminMenuOption(1, "label".equals(component) ? value : null);
    }

    /**
     * Builds a fully populated user-menu response through the canonical factory.
     *
     * @return a user-menu response carrying every optional component
     */
    private static MenuResponse populatedUserMenu() {
        return MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                CURRENT_TIME,
                CANONICAL_USER_MENU_OPTIONS,
                SELECTED_OPTION,
                MESSAGE,
                MenuResponse.MessageSeverity.ERROR,
                true,
                FOCUS_SCREEN_FIELD_ID,
                ROUTE,
                JsonContractSupport.populatedNavigation());
    }

    /**
     * Builds a fully populated administrative-menu response through the canonical factory.
     *
     * @return an administrative-menu response carrying every optional component
     */
    private static MenuResponse populatedAdminMenu() {
        return MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                CURRENT_TIME,
                CANONICAL_ADMIN_MENU_OPTIONS,
                SELECTED_OPTION,
                MESSAGE,
                MenuResponse.MessageSeverity.INFORMATIONAL,
                false,
                FOCUS_SCREEN_FIELD_ID,
                ROUTE,
                JsonContractSupport.populatedNavigation());
    }

    /**
     * Builds a user-menu response carrying no navigation state, so that a wholesale placeholder
     * claim is made against a value that delegates to nothing.
     *
     * @return a user-menu response with no navigation state
     */
    private static MenuResponse userMenuWithoutNavigation() {
        return MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                CURRENT_TIME,
                CANONICAL_USER_MENU_OPTIONS,
                SELECTED_OPTION,
                MESSAGE,
                MenuResponse.MessageSeverity.ERROR,
                true,
                FOCUS_SCREEN_FIELD_ID,
                ROUTE,
                null);
    }

    /**
     * Serialises the supplied response with the module's declared settings.
     *
     * @param response the response to serialise
     * @return the emitted JSON text
     * @throws JsonProcessingException if serialisation fails, which fails the calling test
     */
    private static String payloadOf(MenuResponse response) throws JsonProcessingException {
        return JsonContractSupport.declaredSettingsMapper().writeValueAsString(response);
    }

    /** The declared shape of the record and of its two option records. */
    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        /**
         * Reads the declared maximum length of a component of the supplied record type.
         *
         * @param type the record type to inspect
         * @param component the component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int boundOf(Class<?> type, String component) throws NoSuchFieldException {
            Size size = type.getDeclaredField(component).getAnnotation(Size.class);
            assertThat(size)
                    .as("component %s must declare a maximum length", component)
                    .isNotNull();
            return size.max();
        }

        /** The fifteen components appear in the documented order. */
        @Test
        @DisplayName("declares fifteen components in the documented order")
        void theComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(MenuResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        /** Each bounded component declares the width the maps declare. */
        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "selectedOption,2",
            "message,80",
            "focusScreenFieldId,7"
        })
        @DisplayName("declares each screen width")
        void eachBoundedComponentDeclaresItsDocumentedWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(MenuResponse.class, component)).isEqualTo(width);
        }

        /** The collections, the severity, the flag and the route carry no width. */
        @ParameterizedTest(name = "{0} declares no width")
        @ValueSource(
                strings = {
                    "userMenuOptions",
                    "adminMenuOptions",
                    "messageSeverity",
                    "errorFlag",
                    "nextRoute",
                    "navigationContext"
                })
        @DisplayName("leaves the collections, severity, flag and route unbounded")
        void theUnboundedComponentsDeclareNoWidth(String component) throws NoSuchFieldException {
            assertThat(MenuResponse.class.getDeclaredField(component).getAnnotation(Size.class))
                    .isNull();
        }

        /** Every component is accounted for as bounded or unbounded. */
        @Test
        @DisplayName("accounts for every component as bounded or unbounded")
        void everyComponentIsAccountedFor() {
            List<String> partition = new ArrayList<>(BOUNDED_COMPONENTS);
            partition.addAll(UNBOUNDED_COMPONENTS);

            assertThat(partition).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /** Both option shapes declare exactly the two items the screen prints. */
        @Test
        @DisplayName("declares two components on each option shape and no dispatch target")
        void bothOptionShapesPublishScreenContentOnly() {
            List<String> user =
                    Arrays.stream(MenuResponse.UserMenuOption.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();
            List<String> admin =
                    Arrays.stream(MenuResponse.AdminMenuOption.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(user).containsExactlyElementsOf(EXPECTED_USER_OPTION_COMPONENTS);
            assertThat(admin).containsExactlyElementsOf(EXPECTED_ADMIN_OPTION_COMPONENTS);
            assertThat(user)
                    .as("a reply publishes no dispatch target and no role code")
                    .doesNotContainAnyElementsOf(WITHHELD_OPTION_COMPONENTS);
            assertThat(admin)
                    .as("a reply publishes no dispatch target")
                    .doesNotContainAnyElementsOf(WITHHELD_OPTION_COMPONENTS);
        }

        /**
         * The copybook asymmetry is real and is asserted where the withheld items live: the
         * configuration-layer catalogue entry for a user option carries four items and the
         * administrative one carries three, because the administrative table view declares no
         * user-type item. Adding a fourth component there — even a permanently absent one — would
         * fabricate a field the copybook does not have. Neither shape published in a reply carries
         * either item, which is why the asymmetry cannot be observed on this type.
         */
        @Test
        @DisplayName("leaves the copybook asymmetry on the configuration-layer catalogue")
        void theCopybookAsymmetryLivesOnTheConfigurationCatalogue() {
            List<String> catalogUser =
                    Arrays.stream(MenuOptionCatalog.UserMenuOption.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();
            List<String> catalogAdmin =
                    Arrays.stream(MenuOptionCatalog.AdminMenuOption.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(catalogUser).containsExactly("number", "label", "programName", "userType");
            assertThat(catalogAdmin).containsExactly("number", "label", "programName");
            assertThat(catalogAdmin)
                    .as("the administrative copybook declares no user-type item")
                    .doesNotContain("userType");
        }

        /** Neither withheld item exists as a field on either published option shape. */
        @ParameterizedTest(name = "no option row declares {0}")
        @ValueSource(strings = {"programName", "userType"})
        @DisplayName("declares neither withheld item as a field on either option shape")
        void neitherWithheldItemIsDeclaredOnEitherOptionShape(String withheld) {
            assertThatExceptionOfType(NoSuchFieldException.class)
                    .isThrownBy(
                            () -> MenuResponse.UserMenuOption.class.getDeclaredField(withheld));
            assertThatExceptionOfType(NoSuchFieldException.class)
                    .isThrownBy(
                            () -> MenuResponse.AdminMenuOption.class.getDeclaredField(withheld));
        }

        /** Neither option record specialises the other, and they share no declared supertype. */
        @Test
        @DisplayName("gives the two option shapes no shared supertype")
        void theTwoOptionShapesShareNoSupertype() {
            assertThat(MenuResponse.UserMenuOption.class.getInterfaces()).isEmpty();
            assertThat(MenuResponse.AdminMenuOption.class.getInterfaces()).isEmpty();
            assertThat(MenuResponse.UserMenuOption.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MenuResponse.AdminMenuOption.class.getSuperclass()).isEqualTo(Record.class);
        }

        /** The option number is an int on both shapes, because it is a cardinal and not a key. */
        @Test
        @DisplayName("carries the option number as an int on both shapes")
        void theOptionNumberIsAnInt() throws NoSuchFieldException {
            assertThat(MenuResponse.UserMenuOption.class.getDeclaredField("number").getType())
                    .isEqualTo(int.class);
            assertThat(MenuResponse.AdminMenuOption.class.getDeclaredField("number").getType())
                    .isEqualTo(int.class);
            assertThat(MenuResponse.OPTION_NUMBER_WIDTH).isEqualTo(2);
        }

        /** Each bounded option component declares the width the copybook declares. */
        @ParameterizedTest(name = "option {0} is bounded at {1}")
        @CsvSource({"label,35"})
        @DisplayName("declares each user option width")
        void eachBoundedUserOptionComponentDeclaresItsWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(MenuResponse.UserMenuOption.class, component)).isEqualTo(width);
        }

        /** Each bounded administrative option component declares the copybook width. */
        @ParameterizedTest(name = "admin option {0} is bounded at {1}")
        @CsvSource({"label,35"})
        @DisplayName("declares each administrative option width")
        void eachBoundedAdminOptionComponentDeclaresItsWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(MenuResponse.AdminMenuOption.class, component)).isEqualTo(width);
        }

        /**
         * The static surface is fourteen widths, four identity texts and no catalogue at all.
         *
         * <p>The four texts are the two menus' own transaction identifiers and program names, which are
         * this response's identity and are declared nowhere else in the module. What is deliberately
         * <em>absent</em> is every piece of legacy text that has an owner elsewhere: the two screen
         * title lines and the forty-character acknowledgement, owned by
         * {@code com.carddemo.service.MessageCatalogService}, and the ten user rows and four
         * administrative rows, owned by {@code com.carddemo.service.MenuOptionCatalog}. A second
         * declaration of any of them here would make this contract a second authority for the same
         * text, so the count below is the guard that keeps one authority per value.</p>
         */
        @Test
        @DisplayName("declares fourteen widths, four identity texts and no catalogue, so no legacy text "
                + "with an owner elsewhere is re-declared here")
        void theStaticSurfaceIsFourteenWidthsFourIdentityTextsAndNoCatalogue() {
            List<Field> statics =
                    Arrays.stream(MenuResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .toList();

            assertThat(statics.stream().filter(field -> field.getType() == int.class).toList())
                    .hasSize(14);
            assertThat(statics.stream().filter(field -> field.getType() == String.class).toList())
                    .as("only the two menus' own transaction identifiers and program names")
                    .hasSize(4)
                    .extracting(Field::getName)
                    .containsExactlyInAnyOrder("USER_MENU_TRANSACTION_NAME", "USER_MENU_PROGRAM_NAME",
                            "ADMIN_MENU_TRANSACTION_NAME", "ADMIN_MENU_PROGRAM_NAME");
            assertThat(statics.stream().filter(field -> field.getType() == List.class).toList())
                    .as("no option catalogue is declared here; the configuration layer owns the rows")
                    .isEmpty();
            assertThat(statics).hasSize(18);
            assertThat(statics)
                    .allSatisfy(
                            field -> {
                                assertThat(Modifier.isPublic(field.getModifiers())).isTrue();
                                assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
                            });
        }

        @Test
        @DisplayName("declares no title text and no acknowledgement text, so the message catalogue stays "
                + "the single owner of every fixed-width message value")
        void declaresNoTitleTextAndNoAcknowledgementText() throws Exception {
            List<String> declaredText = new ArrayList<>();
            for (Field field : MenuResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    declaredText.add((String) field.get(null));
                }
            }

            assertThat(declaredText)
                    .as("no value this file declares is any of the title copybook's three values")
                    .doesNotContain(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, SCREEN_TITLE_THANK_YOU);
            assertThat(declaredText)
                    .allSatisfy(value -> assertThat(value.length())
                            .as("an identity text is four or eight characters, never forty or fifty")
                            .isIn(MenuResponse.TRANSACTION_NAME_WIDTH, MenuResponse.PROGRAM_NAME_WIDTH));
        }

        @Test
        @DisplayName("declares no option label, so the configuration catalogue stays the single owner of "
                + "the fourteen rows and of the program and role metadata they carry")
        void declaresNoOptionLabel() throws Exception {
            List<String> declaredText = new ArrayList<>();
            for (Field field : MenuResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    declaredText.add((String) field.get(null));
                }
            }

            List<String> everyCanonicalLabel = new ArrayList<>();
            CANONICAL_USER_MENU_OPTIONS.forEach(option -> everyCanonicalLabel.add(option.label()));
            CANONICAL_ADMIN_MENU_OPTIONS.forEach(option -> everyCanonicalLabel.add(option.label()));

            assertThat(everyCanonicalLabel).hasSize(14);
            assertThat(declaredText).doesNotContainAnyElementsOf(everyCanonicalLabel);
        }

        /**
         * Neither table capacity is published. The user table holds twelve slots and the
         * administrative table nine, and iterating capacity instead of count is what renders blank
         * rows onto a menu, so neither number may appear as a constant here.
         */
        @Test
        @DisplayName("publishes neither table capacity")
        void neitherTableCapacityIsPublished() {
            List<Field> intConstants =
                    Arrays.stream(MenuResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .filter(field -> field.getType() == int.class)
                            .toList();

            assertThat(intConstants)
                    .as("neither twelve nor nine may be published as a constant")
                    .allSatisfy(
                            field -> {
                                field.setAccessible(true);
                                assertThat(field.getInt(null)).isNotIn(12, 9);
                            });
            assertThat(
                            intConstants.stream()
                                    .map(Field::getName)
                                    .filter(name -> name.contains("CAPACITY"))
                                    .toList())
                    .isEmpty();
        }

        /** The two counts are ten and four. */
        @Test
        @DisplayName("publishes counts of ten and four")
        void theTwoCountsAreTenAndFour() {
            assertThat(MenuResponse.USER_MENU_OPTION_COUNT).isEqualTo(10);
            assertThat(MenuResponse.ADMIN_MENU_OPTION_COUNT).isEqualTo(4);
        }

        /**
         * The declared surface beyond the accessors is exactly the two factories and the two
         * presence predicates.
         */
        @Test
        @DisplayName("declares two factories and two presence predicates and nothing else")
        void theDeclaredSurfaceBeyondTheAccessorsIsFourMethods() {
            List<String> declared =
                    Arrays.stream(MenuResponse.class.getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .filter(name -> !EXPECTED_COMPONENTS.contains(name))
                            .toList();

            assertThat(declared)
                    .containsExactlyInAnyOrder(
                            "forUserMenu", "forAdminMenu", "carriesUserMenu", "carriesAdminMenu");
        }

        /** Neither option record declares a member beyond the generated accessors. */
        @Test
        @DisplayName("declares no member on either option record")
        void neitherOptionRecordDeclaresAMember() {
            assertThat(
                            Arrays.stream(MenuResponse.UserMenuOption.class.getDeclaredMethods())
                                    .filter(method -> !method.isSynthetic())
                                    .map(Method::getName)
                                    .filter(
                                            name ->
                                                    !"equals".equals(name)
                                                            && !"hashCode".equals(name)
                                                            && !"toString".equals(name))
                                    .toList())
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_USER_OPTION_COMPONENTS);
            assertThat(
                            Arrays.stream(MenuResponse.AdminMenuOption.class.getDeclaredMethods())
                                    .filter(method -> !method.isSynthetic())
                                    .map(Method::getName)
                                    .filter(
                                            name ->
                                                    !"equals".equals(name)
                                                            && !"hashCode".equals(name)
                                                            && !"toString".equals(name))
                                    .toList())
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_ADMIN_OPTION_COMPONENTS);
        }

        /** Both option records keep only the generated canonical constructor. */
        @Test
        @DisplayName("keeps only the generated canonical constructor on both option records")
        void bothOptionRecordsKeepOnlyTheGeneratedConstructor() {
            assertThat(MenuResponse.UserMenuOption.class.getDeclaredConstructors()).hasSize(1);
            assertThat(MenuResponse.AdminMenuOption.class.getDeclaredConstructors()).hasSize(1);
            assertThat(
                            MenuResponse.UserMenuOption.class
                                    .getDeclaredConstructors()[0]
                                    .getParameterCount())
                    .isEqualTo(2);
            assertThat(
                            MenuResponse.AdminMenuOption.class
                                    .getDeclaredConstructors()[0]
                                    .getParameterCount())
                    .isEqualTo(2);
        }

        /** The severity enumeration declares exactly the two constants the highlighting encoded. */
        @Test
        @DisplayName("declares exactly two severity constants")
        void theSeverityEnumerationDeclaresTwoConstants() {
            assertThat(MenuResponse.MessageSeverity.values())
                    .containsExactly(
                            MenuResponse.MessageSeverity.INFORMATIONAL,
                            MenuResponse.MessageSeverity.ERROR);
        }

        /** No serialisation annotation appears on any component of any of the three records. */
        @Test
        @DisplayName("declares no serialisation annotation on any component")
        void noSerialisationAnnotationAppearsOnAnyComponent() {
            List<Annotation> annotations = new ArrayList<>();
            for (Class<?> type :
                    List.of(
                            MenuResponse.class,
                            MenuResponse.UserMenuOption.class,
                            MenuResponse.AdminMenuOption.class)) {
                for (Field field : type.getDeclaredFields()) {
                    annotations.addAll(Arrays.asList(field.getAnnotations()));
                }
            }

            assertThat(annotations)
                    .as("this type declares no serialisation behaviour of its own")
                    .noneMatch(
                            annotation ->
                                    annotation
                                            .annotationType()
                                            .getName()
                                            .startsWith("com.fasterxml.jackson"));
        }
    }

    /** The two published catalogues and their exact contents. */
    @Nested
    @DisplayName("Canonical catalogues")
    class CanonicalCatalogues {

        /** The user catalogue holds exactly the published count of entries. */
        @Test
        @DisplayName("holds exactly ten user options")
        void theUserCatalogueHoldsTenEntries() {
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .hasSize(MenuResponse.USER_MENU_OPTION_COUNT)
                    .hasSize(10);
        }

        /** The administrative catalogue holds exactly the published count of entries. */
        @Test
        @DisplayName("holds exactly four administrative options")
        void theAdminCatalogueHoldsFourEntries() {
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .hasSize(MenuResponse.ADMIN_MENU_OPTION_COUNT)
                    .hasSize(4);
        }

        /**
         * Each user option publishes its documented number and label, the dispatch target for that
         * row is what the configuration-layer catalogue holds for the same index, and the target
         * appears nowhere in the published row.
         */
        @ParameterizedTest(name = "user option {0} is {1}, dispatching to {2} out of sight")
        @MethodSource("com.carddemo.api.dto.MenuResponseCoverageTest#userOptionExpectations")
        @DisplayName("publishes each user option exactly")
        void eachUserOptionIsExactlyAsDocumented(int index, String label, String programName) {
            MenuResponse.UserMenuOption option =
                    CANONICAL_USER_MENU_OPTIONS.get(index);

            assertThat(option.number()).isEqualTo(index + 1);
            assertThat(option.label()).isEqualTo(label);
            assertThat(option)
                    .as("the row is exactly the two screen items")
                    .isEqualTo(new MenuResponse.UserMenuOption(index + 1, label));
            assertThat(new MenuOptionCatalog().userMenuOptions().get(index).programName())
                    .as("the dispatch target for this row is held by the configuration catalogue")
                    .isEqualTo(programName);
            assertThat(option.toString())
                    .as("and never reaches the published row")
                    .doesNotContain(programName);
        }

        /**
         * Each administrative option publishes its documented number and label, and the dispatch
         * target for that row stays on the configuration-layer catalogue.
         */
        @ParameterizedTest(name = "admin option {0} is {1}, dispatching to {2} out of sight")
        @MethodSource("com.carddemo.api.dto.MenuResponseCoverageTest#adminOptionExpectations")
        @DisplayName("publishes each administrative option exactly")
        void eachAdminOptionIsExactlyAsDocumented(int index, String label, String programName) {
            MenuResponse.AdminMenuOption option =
                    CANONICAL_ADMIN_MENU_OPTIONS.get(index);

            assertThat(option.number()).isEqualTo(index + 1);
            assertThat(option.label()).isEqualTo(label);
            assertThat(option)
                    .as("the row is exactly the two screen items")
                    .isEqualTo(new MenuResponse.AdminMenuOption(index + 1, label));
            assertThat(new MenuOptionCatalog().adminMenuOptions().get(index).programName())
                    .as("the dispatch target for this row is held by the configuration catalogue")
                    .isEqualTo(programName);
            assertThat(option.toString())
                    .as("and never reaches the published row")
                    .doesNotContain(programName);
        }

        /** Option numbers ascend from one with no gap, so screen order is declaration order. */
        @Test
        @DisplayName("numbers both catalogues from one with no gap")
        void bothCataloguesAreNumberedFromOneWithNoGap() {
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .extracting(MenuResponse.UserMenuOption::number)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .extracting(MenuResponse.AdminMenuOption::number)
                    .containsExactly(1, 2, 3, 4);
        }

        /** No entry of either catalogue is blank, because surplus capacity is excluded. */
        @Test
        @DisplayName("carries no blank entry in either catalogue")
        void neitherCatalogueCarriesABlankEntry() {
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .allSatisfy(option -> assertThat(option.label()).isNotBlank());
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .allSatisfy(option -> assertThat(option.label()).isNotBlank());
        }

        /**
         * Option eight carries the one active label. The commented-out alternative in the copybook is
         * neither published nor implied, and no role gate is attached to it — the published row has
         * no component a role gate could be expressed in, and the configuration catalogue records the
         * same standard user-type code for this row as for its nine peers.
         */
        @Test
        @DisplayName("gives option eight one active label and no role gate")
        void optionEightCarriesTheActiveLabelAndNoRoleGate() {
            MenuResponse.UserMenuOption eighth =
                    CANONICAL_USER_MENU_OPTIONS.get(7);

            assertThat(eighth.number()).isEqualTo(8);
            assertThat(eighth.label()).isEqualTo("Transaction Add");
            assertThat(eighth.label()).doesNotContain("Admin");
            assertThat(new MenuOptionCatalog().userMenuOptions().get(7).programName())
                    .isEqualTo("COTRN02C");
            assertThat(new MenuOptionCatalog().userMenuOptions().get(7).userType())
                    .isEqualTo(MenuOptionCatalog.STANDARD_USER_TYPE_CODE);
        }

        /**
         * Every catalogued user option carries the standard user-type code and no other code
         * appears. The assertion is made against the configuration-layer catalogue because that is
         * where the code lives; no published response row carries one.
         */
        @Test
        @DisplayName("carries the standard user-type code on every catalogued user option")
        void everyCataloguedUserOptionCarriesTheStandardCode() {
            assertThat(MenuOptionCatalog.STANDARD_USER_TYPE_CODE).isEqualTo("U");
            assertThat(new MenuOptionCatalog().userMenuOptions())
                    .extracting(MenuOptionCatalog.UserMenuOption::userType)
                    .containsOnly(MenuOptionCatalog.STANDARD_USER_TYPE_CODE);
        }

        /** Every published label fits the declared label width. */
        @Test
        @DisplayName("keeps every label within the declared label width")
        void everyLabelFitsTheDeclaredWidth() {
            assertThat(MenuResponse.OPTION_LABEL_WIDTH).isEqualTo(35);
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .allSatisfy(
                            option ->
                                    assertThat(option.label().length())
                                            .isLessThanOrEqualTo(
                                                    MenuResponse.OPTION_LABEL_WIDTH));
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .allSatisfy(
                            option ->
                                    assertThat(option.label().length())
                                            .isLessThanOrEqualTo(
                                                    MenuResponse.OPTION_LABEL_WIDTH));
        }

        /**
         * Every catalogued program name occupies exactly the declared program-name width, asserted
         * against the catalogue that holds them. No published response row carries a program name, so
         * the width constant is the catalogue's and not this type's.
         */
        @Test
        @DisplayName("gives every catalogued program name exactly the declared width")
        void everyCataloguedProgramNameOccupiesTheDeclaredWidth() {
            assertThat(MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH).isEqualTo(8);
            assertThat(new MenuOptionCatalog().userMenuOptions())
                    .allSatisfy(
                            option ->
                                    assertThat(option.programName())
                                            .hasSize(
                                                    MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH));
            assertThat(new MenuOptionCatalog().adminMenuOptions())
                    .allSatisfy(
                            option ->
                                    assertThat(option.programName())
                                            .hasSize(
                                                    MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH));
        }

        /** Labels are carried in display form, so no label is blank-filled to its declared width. */
        @Test
        @DisplayName("carries labels in display form rather than blank-filled")
        void labelsAreCarriedInDisplayForm() {
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .allSatisfy(
                            option -> {
                                assertThat(option.label()).isEqualTo(option.label().strip());
                                assertThat(option.label().length())
                                        .isLessThan(MenuResponse.OPTION_LABEL_WIDTH);
                            });
        }

        /** Both catalogues are unmodifiable. */
        @Test
        @DisplayName("publishes both catalogues unmodifiable")
        void bothCataloguesAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(
                            () ->
                                    CANONICAL_USER_MENU_OPTIONS.add(
                                            new MenuResponse.UserMenuOption(11, "Surplus")));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(
                            () ->
                                    CANONICAL_ADMIN_MENU_OPTIONS.add(
                                            new MenuResponse.AdminMenuOption(5, "Surplus")));
        }

        /**
         * The duplication with the configuration-layer catalogue is deliberate, but the contents
         * must not drift: every published entry agrees with the catalogue bean's own entry on the two
         * items a reply publishes, and carries neither of the two the catalogue keeps to itself.
         */
        @Test
        @DisplayName("agrees with the configuration-layer catalogue entry for entry")
        void theCataloguesAgreeWithTheConfigurationLayerCatalogue() {
            MenuOptionCatalog catalog = new MenuOptionCatalog();

            assertThat(catalog.userMenuOptions()).hasSameSizeAs(
                    CANONICAL_USER_MENU_OPTIONS);
            for (int index = 0; index < CANONICAL_USER_MENU_OPTIONS.size(); index++) {
                MenuResponse.UserMenuOption published =
                        CANONICAL_USER_MENU_OPTIONS.get(index);
                MenuOptionCatalog.UserMenuOption catalogued = catalog.userMenuOptions().get(index);

                assertThat(published.number()).isEqualTo(catalogued.number());
                assertThat(published.label()).isEqualTo(catalogued.label());
                assertThat(published.toString())
                        .as("the catalogued dispatch target and role code stay out of the reply")
                        .doesNotContain(catalogued.programName())
                        .doesNotContain("userType");
            }

            assertThat(catalog.adminMenuOptions()).hasSameSizeAs(
                    CANONICAL_ADMIN_MENU_OPTIONS);
            for (int index = 0; index < CANONICAL_ADMIN_MENU_OPTIONS.size(); index++) {
                MenuResponse.AdminMenuOption published =
                        CANONICAL_ADMIN_MENU_OPTIONS.get(index);
                MenuOptionCatalog.AdminMenuOption catalogued = catalog.adminMenuOptions().get(index);

                assertThat(published.number()).isEqualTo(catalogued.number());
                assertThat(published.label()).isEqualTo(catalogued.label());
                assertThat(published.toString())
                        .as("the catalogued dispatch target stays out of the reply")
                        .doesNotContain(catalogued.programName());
            }
        }
    }

    /** The title and message widths, and the three acknowledgements that must stay distinct. */
    @Nested
    @DisplayName("Titles and message widths")
    class TitlesAndMessageWidths {

        /** Both published title lines are exactly the declared title width. */
        @Test
        @DisplayName("gives both title lines exactly forty characters")
        void bothTitleLinesAreExactlyFortyCharacters() {
            assertThat(MenuResponse.SCREEN_TITLE_WIDTH).isEqualTo(40);
            assertThat(SCREEN_TITLE_LINE_1)
                    .hasSize(MenuResponse.SCREEN_TITLE_WIDTH);
            assertThat(SCREEN_TITLE_LINE_2)
                    .hasSize(MenuResponse.SCREEN_TITLE_WIDTH);
        }

        /** The title lines carry their significant leading and trailing spaces. */
        @Test
        @DisplayName("keeps the significant spaces on both title lines")
        void theTitleLinesKeepTheirSignificantSpaces() {
            assertThat(SCREEN_TITLE_LINE_1).startsWith(" ").endsWith(" ");
            assertThat(SCREEN_TITLE_LINE_1.strip())
                    .isEqualTo("AWS Mainframe Modernization");
            assertThat(SCREEN_TITLE_LINE_2).startsWith(" ").endsWith(" ");
            assertThat(SCREEN_TITLE_LINE_2.strip()).isEqualTo("CardDemo");
        }

        /** The two title lines agree with the service-layer message catalogue. */
        @Test
        @DisplayName("agrees with the service-layer catalogue on both title lines")
        void theTitleLinesAgreeWithTheServiceCatalogue() {
            assertThat(SCREEN_TITLE_LINE_1)
                    .isEqualTo(MessageCatalogService.CCDA_TITLE01);
            assertThat(SCREEN_TITLE_LINE_2)
                    .isEqualTo(MessageCatalogService.CCDA_TITLE02);
        }

        /** The screen-title acknowledgement is forty characters and ends in a significant space. */
        @Test
        @DisplayName("publishes a forty-character acknowledgement ending in a space")
        void theScreenTitleAcknowledgementIsFortyCharacters() {
            assertThat(SCREEN_TITLE_THANK_YOU)
                    .hasSize(MenuResponse.SCREEN_TITLE_WIDTH);
            assertThat(SCREEN_TITLE_THANK_YOU).endsWith(" ");
            assertThat(SCREEN_TITLE_THANK_YOU.strip())
                    .isEqualTo("Thank you for using CCDA application...");
        }

        /**
         * The screen-title acknowledgement is not the common-message acknowledgement: the two differ
         * both in the product token they name and in the width of the field they occupy.
         */
        @Test
        @DisplayName("keeps the screen-title and common-message acknowledgements distinct")
        void theTwoAcknowledgementsAreDistinct() {
            assertThat(SCREEN_TITLE_THANK_YOU)
                    .as("substituting one for the other is a byte-equivalence failure")
                    .isNotEqualTo(MessageCatalogService.CCDA_MSG_THANK_YOU);
            assertThat(SCREEN_TITLE_THANK_YOU.length())
                    .isNotEqualTo(MessageCatalogService.CCDA_MSG_THANK_YOU.length());
            assertThat(SCREEN_TITLE_THANK_YOU).contains("CCDA");
            assertThat(MessageCatalogService.CCDA_MSG_THANK_YOU).contains("CardDemo");
        }

        /** The screen-title acknowledgement is the catalogue's own title-width variant. */
        @Test
        @DisplayName("matches the catalogue's title-width acknowledgement")
        void theScreenTitleAcknowledgementMatchesTheCatalogueVariant() {
            assertThat(SCREEN_TITLE_THANK_YOU)
                    .isEqualTo(MessageCatalogService.CCDA_THANK_YOU);
        }

        /** The common-message width is fifty and is not the title width. */
        @Test
        @DisplayName("publishes a fifty-character common-message width distinct from the title")
        void theCommonMessageWidthIsFifty() {
            assertThat(MenuResponse.COMMON_MESSAGE_WIDTH).isEqualTo(50);
            assertThat(MenuResponse.COMMON_MESSAGE_WIDTH)
                    .isNotEqualTo(MenuResponse.SCREEN_TITLE_WIDTH);
            assertThat(MenuResponse.COMMON_MESSAGE_WIDTH)
                    .isEqualTo(MessageCatalogService.COMMON_MESSAGE_WIDTH);
        }

        /** Both common messages occupy exactly the published common-message width. */
        @Test
        @DisplayName("sizes both common messages at the published width")
        void bothCommonMessagesOccupyThePublishedWidth() {
            assertThat(MessageCatalogService.CCDA_MSG_THANK_YOU)
                    .hasSize(MenuResponse.COMMON_MESSAGE_WIDTH);
            assertThat(MessageCatalogService.CCDA_MSG_INVALID_KEY)
                    .hasSize(MenuResponse.COMMON_MESSAGE_WIDTH);
        }

        /**
         * The bound on the message component is the working-storage width, while the screen field is
         * narrower. Both are published because they answer different questions, and neither is
         * applied as a transformation.
         */
        @Test
        @DisplayName("publishes the eighty-character bound and the narrower screen width")
        void bothMessageWidthsArePublishedAndNeitherTransforms() throws NoSuchFieldException {
            assertThat(MenuResponse.MESSAGE_WIDTH).isEqualTo(80);
            assertThat(MenuResponse.SCREEN_MESSAGE_FIELD_WIDTH).isEqualTo(78);
            assertThat(MenuResponse.MESSAGE_WIDTH)
                    .isGreaterThan(MenuResponse.SCREEN_MESSAGE_FIELD_WIDTH);
            assertThat(
                            MenuResponse.class
                                    .getDeclaredField("message")
                                    .getAnnotation(Size.class)
                                    .max())
                    .as("the caller is bounded by the working-storage width")
                    .isEqualTo(MenuResponse.MESSAGE_WIDTH);

            String seventyNine = "A".repeat(79);

            assertThat(carrying("message", seventyNine).message())
                    .as("a message wider than the screen field is never shortened")
                    .isEqualTo(seventyNine);
        }

        /**
         * The two-character option-number width and the two-character echoed-entry width are equal
         * and are declared independently, because they are different fields with different owners.
         */
        @Test
        @DisplayName("declares the two equal two-character widths independently")
        void theTwoEqualTwoCharacterWidthsAreDeclaredIndependently() {
            assertThat(MenuResponse.OPTION_NUMBER_WIDTH).isEqualTo(2);
            assertThat(MenuResponse.SELECTED_OPTION_WIDTH).isEqualTo(2);

            List<String> twoWide =
                    Arrays.stream(MenuResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> field.getType() == int.class)
                            .map(Field::getName)
                            .filter(
                                    name ->
                                            "OPTION_NUMBER_WIDTH".equals(name)
                                                    || "SELECTED_OPTION_WIDTH".equals(name))
                            .toList();

            assertThat(twoWide).as("two separate constants, neither derived from the other")
                    .hasSize(2);
        }

        /** The message is carried exactly as supplied, with its spaces intact. */
        @Test
        @DisplayName("carries the message without trimming or reformatting it")
        void theMessageIsCarriedWithoutAlteration() {
            String padded = "  option unavailable   ";

            assertThat(carrying("message", padded).message()).isEqualTo(padded);
        }
    }

    /** The two factories and the two presence predicates. */
    @Nested
    @DisplayName("Factories and presence")
    class FactoriesAndPresence {

        /**
         * The user factory fills every fixed header item the user menu displays — both title lines
         * and the transaction and program identifiers — and carries the two computed items through.
         */
        @Test
        @DisplayName("fills every fixed header item on the user factory")
        void theUserFactoryFillsEveryFixedHeaderItem() {
            MenuResponse response = populatedUserMenu();

            assertThat(response.title01()).isEqualTo(SCREEN_TITLE_LINE_1);
            assertThat(response.title02()).isEqualTo(SCREEN_TITLE_LINE_2);
            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME)
                    .isEqualTo("CM00");
            assertThat(response.programName())
                    .isEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME)
                    .isEqualTo("COMEN01C");
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
        }

        /**
         * The administrative factory fills the same two title lines but its own transaction and
         * program identifiers, because the administrative program is a different program.
         */
        @Test
        @DisplayName("fills every fixed header item on the administrative factory")
        void theAdminFactoryFillsEveryFixedHeaderItem() {
            MenuResponse response = populatedAdminMenu();

            assertThat(response.title01()).isEqualTo(SCREEN_TITLE_LINE_1);
            assertThat(response.title02()).isEqualTo(SCREEN_TITLE_LINE_2);
            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.ADMIN_MENU_TRANSACTION_NAME)
                    .isEqualTo("CA00")
                    .isNotEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME);
            assertThat(response.programName())
                    .isEqualTo(MenuResponse.ADMIN_MENU_PROGRAM_NAME)
                    .isEqualTo("COADM01C")
                    .isNotEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
        }

        /** Neither factory fills a title line with the acknowledgement text. */
        @Test
        @DisplayName("fills no title line with the acknowledgement text")
        void neitherFactoryFillsATitleWithTheAcknowledgement() {
            assertThat(populatedUserMenu().title01())
                    .isNotEqualTo(SCREEN_TITLE_THANK_YOU);
            assertThat(populatedUserMenu().title02())
                    .isNotEqualTo(SCREEN_TITLE_THANK_YOU);
            assertThat(populatedAdminMenu().title01())
                    .isNotEqualTo(SCREEN_TITLE_THANK_YOU);
            assertThat(populatedAdminMenu().title02())
                    .isNotEqualTo(SCREEN_TITLE_THANK_YOU);
        }

        /** The user factory populates the user collection and leaves the administrative absent. */
        @Test
        @DisplayName("populates only the user collection on the user factory")
        void theUserFactoryPopulatesOnlyTheUserCollection() {
            MenuResponse response = populatedUserMenu();

            assertThat(response.userMenuOptions())
                    .containsExactlyElementsOf(CANONICAL_USER_MENU_OPTIONS);
            assertThat(response.adminMenuOptions()).isNull();
        }

        /** The administrative factory populates only the administrative collection. */
        @Test
        @DisplayName("populates only the administrative collection on the administrative factory")
        void theAdminFactoryPopulatesOnlyTheAdminCollection() {
            MenuResponse response = populatedAdminMenu();

            assertThat(response.adminMenuOptions())
                    .containsExactlyElementsOf(CANONICAL_ADMIN_MENU_OPTIONS);
            assertThat(response.userMenuOptions()).isNull();
        }

        /** Both factories carry every remaining component through unaltered. */
        @Test
        @DisplayName("carries every remaining component through both factories unaltered")
        void bothFactoriesCarryEveryRemainingComponentThrough() {
            MenuResponse user = populatedUserMenu();

            assertThat(user.selectedOption()).isEqualTo(SELECTED_OPTION);
            assertThat(user.message()).isEqualTo(MESSAGE);
            assertThat(user.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(user.errorFlag()).isTrue();
            assertThat(user.focusScreenFieldId()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(user.nextRoute()).isEqualTo(ROUTE);
            assertThat(user.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());

            MenuResponse admin = populatedAdminMenu();

            assertThat(admin.selectedOption()).isEqualTo(SELECTED_OPTION);
            assertThat(admin.message()).isEqualTo(MESSAGE);
            assertThat(admin.messageSeverity())
                    .isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
            assertThat(admin.errorFlag()).isFalse();
            assertThat(admin.focusScreenFieldId()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(admin.nextRoute()).isEqualTo(ROUTE);
            assertThat(admin.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        /** The presence predicates answer for the collection each factory populated. */
        @Test
        @DisplayName("answers the presence predicates for the populated collection")
        void thePresencePredicatesAnswerForThePopulatedCollection() {
            assertThat(populatedUserMenu().carriesUserMenu()).isTrue();
            assertThat(populatedUserMenu().carriesAdminMenu()).isFalse();
            assertThat(populatedAdminMenu().carriesAdminMenu()).isTrue();
            assertThat(populatedAdminMenu().carriesUserMenu()).isFalse();
        }

        /**
         * Neither an empty collection nor an absent one describes a menu screen, so the factory
         * refuses both rather than publishing a screen with no rows on it. The refusal names the
         * count the copybook table declares and what was supplied.
         */
        @Test
        @DisplayName("refuses both an empty collection and an absent one")
        void anEmptyCollectionAndAnAbsentOneAreBothRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    MenuResponse.forUserMenu(
                                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                                            CURRENT_DATE, CURRENT_TIME, List.of(), null, null, null,
                                            false, null, null, null))
                    .withMessageContaining(String.valueOf(MenuResponse.USER_MENU_OPTION_COUNT))
                    .withMessageContaining("0 were supplied");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    MenuResponse.forUserMenu(
                                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                                            CURRENT_DATE, CURRENT_TIME, null, null, null, null,
                                            false, null, null, null))
                    .withMessageContaining("neither was supplied");
        }

        /**
         * The two predicates always disagree, because construction admits exactly one collection.
         * A response answering both negatively is unconstructible, and the refusal is what makes that
         * so.
         */
        @Test
        @DisplayName("keeps the two presence predicates in permanent disagreement")
        void theTwoPresencePredicatesAlwaysDisagree() {
            for (MenuResponse response :
                    List.of(populatedUserMenu(), populatedAdminMenu(), carrying("message", MESSAGE))) {
                assertThat(response.carriesUserMenu())
                        .as("exactly one collection is present, so exactly one predicate answers yes")
                        .isNotEqualTo(response.carriesAdminMenu());
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    new MenuResponse(
                                            null, null, null, null, null, null, null, null, null,
                                            MESSAGE, null, false, null, null, null))
                    .withMessageContaining("neither was supplied");
        }
    }

    /** How the compact constructor treats the two option collections. */
    @Nested
    @DisplayName("Collection discipline")
    class CollectionDiscipline {

        /**
         * A response carrying no option collection describes no screen, so it is refused rather than
         * stored. This is the opposite of the two paginated list responses, which turn a null row
         * list into an empty one: there, no rows is an ordinary result; here, the collection is what
         * answers which menu this is.
         */
        @Test
        @DisplayName("refuses a response that carries no option collection")
        void aResponseWithNoOptionCollectionIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    new MenuResponse(
                                            null, null, null, null, null, null, null, null, null,
                                            null, null, false, null, null, null))
                    .withMessageContaining("exactly one option collection")
                    .withMessageContaining("neither was supplied");
        }

        /** A supplied collection is copied, so later mutation of the caller's list is not seen. */
        @Test
        @DisplayName("copies a supplied collection at construction")
        void aSuppliedCollectionIsCopied() {
            List<MenuResponse.UserMenuOption> mutable =
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS);
            MenuResponse response =
                    MenuResponse.forUserMenu(
                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                            CURRENT_DATE, CURRENT_TIME, mutable, null, null, null, false, null,
                            null, null);

            mutable.add(new MenuResponse.UserMenuOption(11, "Surplus"));

            assertThat(response.userMenuOptions())
                    .hasSize(MenuResponse.USER_MENU_OPTION_COUNT)
                    .containsExactlyElementsOf(CANONICAL_USER_MENU_OPTIONS);
        }

        /** A published collection is unmodifiable. */
        @Test
        @DisplayName("publishes a supplied collection unmodifiable")
        void aPublishedCollectionIsUnmodifiable() {
            List<MenuResponse.UserMenuOption> published = populatedUserMenu().userMenuOptions();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(
                            () ->
                                    published.add(
                                            new MenuResponse.UserMenuOption(11, "Surplus")));
        }

        /** A null element is rejected outright rather than published as a blank row. */
        @Test
        @DisplayName("rejects a null option element")
        void aNullOptionElementIsRejected() {
            List<MenuResponse.UserMenuOption> withNull =
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS);
            withNull.set(4, null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(
                            () ->
                                    MenuResponse.forUserMenu(
                                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                                            CURRENT_DATE, CURRENT_TIME, withNull, null, null, null,
                                            false, null, null, null));

            List<MenuResponse.AdminMenuOption> adminWithNull =
                    new ArrayList<>(CANONICAL_ADMIN_MENU_OPTIONS);
            adminWithNull.set(0, null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(
                            () ->
                                    MenuResponse.forAdminMenu(
                                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                                            CURRENT_DATE, CURRENT_TIME, adminWithNull, null, null,
                                            null, false, null, null, null));
        }

        /**
         * The count is enforced. A shorter collection is refused rather than carried, because both
         * legacy programs rebuild every row of their table on every send — including each redisplay
         * after a rejected entry — so there is no screen state in which a menu shows fewer rows. The
         * refusal names the declared count and the count supplied.
         */
        @Test
        @DisplayName("refuses a shorter collection")
        void aShorterCollectionIsRefused() {
            List<MenuResponse.UserMenuOption> shorter =
                    CANONICAL_USER_MENU_OPTIONS.subList(0, 3);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    MenuResponse.forUserMenu(
                                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                                            CURRENT_DATE, CURRENT_TIME, shorter, null, null, null,
                                            false, null, null, null))
                    .withMessageContaining(String.valueOf(MenuResponse.USER_MENU_OPTION_COUNT))
                    .withMessageContaining("3 were supplied");
        }

        /** A longer collection is likewise refused rather than truncated to the count. */
        @Test
        @DisplayName("refuses a longer collection")
        void aLongerCollectionIsRefused() {
            List<MenuResponse.UserMenuOption> longer =
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS);
            longer.add(new MenuResponse.UserMenuOption(11, "Surplus"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    MenuResponse.forUserMenu(
                                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                                            CURRENT_DATE, CURRENT_TIME, longer, null, null, null,
                                            false, null, null, null))
                    .withMessageContaining(
                            String.valueOf(MenuResponse.USER_MENU_OPTION_COUNT + 1)
                                    + " were supplied");
        }

        /** The administrative count is enforced on its own terms, at four rather than ten. */
        @Test
        @DisplayName("refuses an administrative collection of any size but four")
        void anAdministrativeCollectionOfTheWrongSizeIsRefused() {
            List<MenuResponse.AdminMenuOption> shorter =
                    CANONICAL_ADMIN_MENU_OPTIONS.subList(0, 2);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    MenuResponse.forAdminMenu(
                                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                                            CURRENT_DATE, CURRENT_TIME, shorter, null, null, null,
                                            false, null, null, null))
                    .withMessageContaining(String.valueOf(MenuResponse.ADMIN_MENU_OPTION_COUNT))
                    .withMessageContaining("2 were supplied");
        }

        /** Option order is carried verbatim and is never sorted or re-indexed. */
        @Test
        @DisplayName("carries option order verbatim")
        void optionOrderIsCarriedVerbatim() {
            List<MenuResponse.UserMenuOption> reversed =
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS);
            java.util.Collections.reverse(reversed);

            MenuResponse response =
                    MenuResponse.forUserMenu(
                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                            CURRENT_DATE, CURRENT_TIME, reversed, null, null, null, false, null,
                            null, null);

            assertThat(response.userMenuOptions())
                    .extracting(MenuResponse.UserMenuOption::number)
                    .containsExactly(10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        }

        /**
         * Both collections together are refused on the canonical constructor as well as through the
         * factories, because a menu screen is one menu and neither legacy program can render both
         * tables at once. The invariant lives in the compact constructor, so no construction path
         * evades it.
         */
        @Test
        @DisplayName("refuses both collections on the canonical constructor")
        void bothCollectionsAreRefusedOnTheCanonicalConstructor() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    new MenuResponse(
                                            MenuResponse.USER_MENU_TRANSACTION_NAME,
                                            SCREEN_TITLE_LINE_1,
                                            CURRENT_DATE,
                                            MenuResponse.USER_MENU_PROGRAM_NAME,
                                            SCREEN_TITLE_LINE_2,
                                            CURRENT_TIME,
                                            CANONICAL_USER_MENU_OPTIONS,
                                            CANONICAL_ADMIN_MENU_OPTIONS,
                                            null,
                                            null,
                                            null,
                                            false,
                                            null,
                                            null,
                                            null))
                    .withMessageContaining("exactly one option collection")
                    .withMessageContaining("both were supplied");
        }
    }

    /** The JSON form each component takes under the module's declared settings. */
    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        /** Every populated component appears under the name the record declares. */
        @Test
        @DisplayName("emits every populated component under its declared name")
        void everyPopulatedComponentAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(populatedUserMenu()));

            List<String> expected = new ArrayList<>(EXPECTED_COMPONENTS);
            expected.remove("adminMenuOptions");

            assertThat(tree.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(expected);
        }

        /** An absent collection is omitted rather than emitted as a null or an empty array. */
        @Test
        @DisplayName("omits an absent collection")
        void anAbsentCollectionIsOmitted() throws JsonProcessingException {
            String payload = payloadOf(populatedUserMenu());

            assertThat(payload).contains("\"userMenuOptions\":[");
            assertThat(payload).doesNotContain("\"adminMenuOptions\"");
        }

        /**
         * The present collection is emitted as an array of exactly the declared number of objects.
         * An empty array is not a state this contract can reach, because construction refuses a
         * collection of any other size.
         */
        @Test
        @DisplayName("emits the present collection as an array of the declared size")
        void thePresentCollectionIsEmittedAtItsDeclaredSize() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(populatedUserMenu()));

            assertThat(tree.get("userMenuOptions").isArray()).isTrue();
            assertThat(tree.get("userMenuOptions")).hasSize(MenuResponse.USER_MENU_OPTION_COUNT);
            assertThat(payloadOf(populatedUserMenu())).doesNotContain("\"userMenuOptions\":[]");
        }

        /** Each user option member appears under the name the nested record declares. */
        @Test
        @DisplayName("emits each user option member under its declared name")
        void eachUserOptionMemberAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(populatedUserMenu()));

            assertThat(tree.get("userMenuOptions").get(0).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_USER_OPTION_COMPONENTS);
        }

        /** Each administrative option member appears under its declared name, and no user type. */
        @Test
        @DisplayName("emits each administrative option member under its declared name")
        void eachAdminOptionMemberAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(populatedAdminMenu()));

            assertThat(tree.get("adminMenuOptions").get(0).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_ADMIN_OPTION_COMPONENTS);
        }

        /**
         * The option number is emitted as a number and the label as text. Neither withheld item
         * reaches the payload: the row-level {@code programName} and {@code userType} keys are absent,
         * and so is every dispatch target the catalogue holds.
         *
         * <p>Both absences are asserted inside the option array rather than over the whole document,
         * and for two separate reasons. The response's own header {@code programName} — the CICS
         * program the screen displays — is a different item and is legitimately present. So is the
         * navigation state's {@code fromProgram}, which names the program the operator arrived from:
         * that is screen-flow state the next request has to echo back, not a catalogued dispatch
         * target, and it can legitimately name any program in the estate including one that also
         * happens to be an option's target. A document-wide prohibition on a program name would
         * therefore be asserting something untrue of the contract, so the final assertion pins that
         * legitimate presence rather than forbidding it.</p>
         */
        @Test
        @DisplayName("emits the option number as a number and withholds both dispatch items")
        void theOptionNumberIsEmittedAsANumberAndTheDispatchItemsAreWithheld()
                throws JsonProcessingException {
            String payload = payloadOf(populatedUserMenu());
            JsonNode rows =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payload)
                            .get("userMenuOptions");

            assertThat(payload).contains("\"number\":1");
            assertThat(payload).contains("\"label\":\"Account View\"");
            assertThat(rows.toString())
                    .doesNotContain("programName")
                    .doesNotContain("userType");
            assertThat(rows.toString())
                    .as("no catalogued dispatch target reaches the wire, because no row carries one")
                    .doesNotContain("COACTVWC")
                    .doesNotContain("COBIL00C");
            assertThat(payload)
                    .as("the header program name the screen displays is a different item")
                    .contains("\"programName\":\"" + MenuResponse.USER_MENU_PROGRAM_NAME + "\"");
            assertThat(payload)
                    .as("and so is the program the operator arrived from, which is screen-flow state "
                            + "the next request echoes back rather than a catalogued dispatch target")
                    .contains("\"fromProgram\":\"COBIL00C\"");
        }

        /** The severity is emitted as its constant name, because the enum declares no wire value. */
        @ParameterizedTest(name = "{0} is emitted as its name")
        @EnumSource(MenuResponse.MessageSeverity.class)
        @DisplayName("emits the severity as its constant name")
        void theSeverityIsEmittedAsItsConstantName(MenuResponse.MessageSeverity severity)
                throws JsonProcessingException {
            MenuResponse response =
                    MenuResponse.forUserMenu(
                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                            CURRENT_DATE, CURRENT_TIME, CANONICAL_USER_MENU_OPTIONS,
                            null, MESSAGE, severity, false, null, null, null);

            assertThat(payloadOf(response))
                    .contains("\"messageSeverity\":\"" + severity.name() + "\"");
        }

        /** An absent severity is omitted rather than defaulted. */
        @Test
        @DisplayName("omits an absent severity")
        void anAbsentSeverityIsOmitted() throws JsonProcessingException {
            assertThat(payloadOf(carrying("message", MESSAGE)))
                    .doesNotContain("\"messageSeverity\"");
        }

        /** The indicator is emitted under the name this screen declares. */
        @Test
        @DisplayName("emits the indicator under the name errorFlag")
        void theIndicatorIsEmittedUnderTheNameErrorFlag() throws JsonProcessingException {
            String payload = payloadOf(populatedUserMenu());

            assertThat(payload).contains("\"errorFlag\":true");
            assertThat(payload).doesNotContain("\"generalError\"").doesNotContain("\"error\":");
        }

        /**
         * A minimal response carries its one option collection and the indicator, and nothing else.
         * The collection cannot be omitted because construction requires it, and the indicator cannot
         * be omitted because a primitive boolean is never absent.
         */
        @Test
        @DisplayName("emits only the collection and the indicator when nothing else is populated")
        void aMinimalResponseCarriesOnlyItsCollectionAndTheIndicator()
                throws JsonProcessingException {
            MenuResponse minimal =
                    new MenuResponse(
                            null, null, null, null, null, null,
                            CANONICAL_USER_MENU_OPTIONS, null, null, null, null, false,
                            null, null, null);
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper().readTree(payloadOf(minimal));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("userMenuOptions", "errorFlag");
        }

        /**
         * An unknown property is tolerated on read under the module's declared settings. The document
         * is a well-formed response with one surplus key rather than a minimal fragment, because a
         * fragment carrying no option collection would be refused by construction and the refusal
         * would mask the tolerance being asserted.
         */
        @Test
        @DisplayName("tolerates an unknown property on read")
        void anUnknownPropertyIsToleratedOnRead() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload =
                    payloadOf(populatedUserMenu()).replaceFirst("\\{", "{\"extra\":\"x\",");

            assertThatNoException()
                    .isThrownBy(() -> mapper.readValue(payload, MenuResponse.class));
        }

        /** A round trip preserves every component of a user-menu response. */
        @Test
        @DisplayName("preserves every component of a user menu across a round trip")
        void aUserMenuRoundTripPreservesEveryComponent() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            MenuResponse original = populatedUserMenu();

            MenuResponse restored =
                    mapper.readValue(mapper.writeValueAsString(original), MenuResponse.class);

            assertThat(restored).isEqualTo(original);
        }

        /** A round trip preserves every component of an administrative-menu response. */
        @Test
        @DisplayName("preserves every component of an administrative menu across a round trip")
        void anAdminMenuRoundTripPreservesEveryComponent() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            MenuResponse original = populatedAdminMenu();

            MenuResponse restored =
                    mapper.readValue(mapper.writeValueAsString(original), MenuResponse.class);

            assertThat(restored).isEqualTo(original);
        }
    }

    /** Bean Validation behaviour at, inside and outside each declared bound. */
    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        /** A fully populated user menu reports no violation. */
        @Test
        @DisplayName("reports no violation on a populated user menu")
        void aPopulatedUserMenuReportsNoViolation() {
            assertThat(validator.validate(populatedUserMenu())).isEmpty();
        }

        /** A fully populated administrative menu reports no violation. */
        @Test
        @DisplayName("reports no violation on a populated administrative menu")
        void aPopulatedAdminMenuReportsNoViolation() {
            assertThat(validator.validate(populatedAdminMenu())).isEmpty();
        }

        /** A minimal response reports no violation, because no component is required. */
        @Test
        @DisplayName("reports no violation on a minimal response")
        void aMinimalResponseReportsNoViolation() {
            assertThat(
                            validator.validate(
                                    new MenuResponse(
                                            null, null, null, null, null, null,
                                            CANONICAL_USER_MENU_OPTIONS, null, null,
                                            null, null, false, null, null, null)))
                    .isEmpty();
        }

        /** A value one character past a bound is reported against that component. */
        @ParameterizedTest(name = "{0} over {1} is reported")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "selectedOption,2",
            "message,80",
            "focusScreenFieldId,7"
        })
        @DisplayName("reports an over-long value")
        void anOverLongValueIsReported(String component, int width) {
            Set<ConstraintViolation<MenuResponse>> violations =
                    validator.validate(carrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A value exactly at a bound is accepted. */
        @ParameterizedTest(name = "{0} at {1} is accepted")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "selectedOption,2",
            "message,80",
            "focusScreenFieldId,7"
        })
        @DisplayName("accepts a value at a bound")
        void anAtWidthValueIsAccepted(String component, int width) {
            assertThat(validator.validate(carrying(component, "A".repeat(width)))).isEmpty();
        }

        /**
         * The route is not measured and the focus hint is. The route is a service-owned path with no
         * screen field behind it; the focus hint names a screen field label, and the label is seven
         * characters wide on the maps, so it is bounded at that width.
         */
        @Test
        @DisplayName("measures the focus hint and leaves the route unmeasured")
        void theRouteIsUnmeasuredAndTheFocusHintIsMeasured() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(4096)))).isEmpty();
            assertThat(
                            validator.validate(
                                    carrying(
                                            "focusScreenFieldId",
                                            "A".repeat(MenuResponse.SCREEN_FIELD_ID_WIDTH))))
                    .isEmpty();

            Set<ConstraintViolation<MenuResponse>> violations =
                    validator.validate(
                            carrying(
                                    "focusScreenFieldId",
                                    "A".repeat(MenuResponse.SCREEN_FIELD_ID_WIDTH + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("focusScreenFieldId");
        }

        /**
         * An option bound is not cascaded from the enclosing response, because this contract
         * declares no cascade. The option must therefore be validated in its own right.
         */
        @Test
        @DisplayName("does not cascade an option bound from the enclosing response")
        void anOptionBoundIsNotCascadedFromTheEnclosingResponse() {
            List<MenuResponse.UserMenuOption> withOverLongRow =
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS);
            withOverLongRow.set(0, userOptionCarrying("label", "A".repeat(36)));
            MenuResponse response =
                    MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                            CURRENT_TIME,
                            withOverLongRow,
                            null,
                            null,
                            null,
                            false,
                            null,
                            null,
                            null);

            assertThat(validator.validate(response))
                    .as("no cascade is declared, so the response reports nothing")
                    .isEmpty();
        }

        /** An over-long user option value is reported when the option is validated directly. */
        @ParameterizedTest(name = "user option {0} over {1} is reported")
        @CsvSource({"label,35"})
        @DisplayName("reports an over-long user option value when validated directly")
        void anOverLongUserOptionValueIsReported(String component, int width) {
            Set<ConstraintViolation<MenuResponse.UserMenuOption>> violations =
                    validator.validate(userOptionCarrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A user option value exactly at a bound is accepted. */
        @ParameterizedTest(name = "user option {0} at {1} is accepted")
        @CsvSource({"label,35"})
        @DisplayName("accepts a user option value at a bound")
        void anAtWidthUserOptionValueIsAccepted(String component, int width) {
            assertThat(validator.validate(userOptionCarrying(component, "A".repeat(width))))
                    .isEmpty();
        }

        /** An over-long administrative option value is reported when validated directly. */
        @ParameterizedTest(name = "admin option {0} over {1} is reported")
        @CsvSource({"label,35"})
        @DisplayName("reports an over-long administrative option value when validated directly")
        void anOverLongAdminOptionValueIsReported(String component, int width) {
            Set<ConstraintViolation<MenuResponse.AdminMenuOption>> violations =
                    validator.validate(adminOptionCarrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** An administrative option value exactly at a bound is accepted. */
        @ParameterizedTest(name = "admin option {0} at {1} is accepted")
        @CsvSource({"label,35"})
        @DisplayName("accepts an administrative option value at a bound")
        void anAtWidthAdminOptionValueIsAccepted(String component, int width) {
            assertThat(validator.validate(adminOptionCarrying(component, "A".repeat(width))))
                    .isEmpty();
        }

        /**
         * The option number's admissible range is not constrained here, because the count comparison
         * both legacy programs perform belongs to the menu service.
         */
        @ParameterizedTest(name = "option number {0} is not range-checked")
        @ValueSource(ints = {-1, 0, 11, 99, 100})
        @DisplayName("does not range-check the option number")
        void theOptionNumberIsNotRangeChecked(int number) {
            assertThat(validator.validate(new MenuResponse.UserMenuOption(number, "L"))).isEmpty();
            assertThat(validator.validate(new MenuResponse.AdminMenuOption(number, "L"))).isEmpty();
        }

        /** Every canonical option passes validation as published. */
        @Test
        @DisplayName("accepts every canonical option as published")
        void everyCanonicalOptionPassesValidation() {
            assertThat(CANONICAL_USER_MENU_OPTIONS)
                    .allSatisfy(option -> assertThat(validator.validate(option)).isEmpty());
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS)
                    .allSatisfy(option -> assertThat(validator.validate(option)).isEmpty());
        }

        /** A blank or space-valued component is an ordinary state and is not reported. */
        @Test
        @DisplayName("accepts blank and space-padded values")
        void aBlankOrSpacePaddedValueIsAccepted() {
            assertThat(validator.validate(carrying("message", ""))).isEmpty();
            assertThat(validator.validate(carrying("selectedOption", "  "))).isEmpty();
        }
    }

    /** What the generated text rendering reveals, and what the nested record withholds. */
    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /**
         * The rendering is exactly the generated record form. The expected text is assembled here
         * from the declared component order and an independently written list of rendered values.
         */
        @Test
        @DisplayName("renders exactly the generated record form")
        void theRenderingIsExactlyTheGeneratedForm() {
            MenuResponse response =
                    MenuResponse.forAdminMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                            CURRENT_TIME,
                            List.of(
                                    new MenuResponse.AdminMenuOption(1, "User List (Security)"),
                                    new MenuResponse.AdminMenuOption(2, "User Add (Security)"),
                                    new MenuResponse.AdminMenuOption(3, "User Update (Security)"),
                                    new MenuResponse.AdminMenuOption(4, "User Delete (Security)")),
                            SELECTED_OPTION,
                            MESSAGE,
                            MenuResponse.MessageSeverity.INFORMATIONAL,
                            false,
                            FOCUS_SCREEN_FIELD_ID,
                            ROUTE,
                            null);

            List<String> renderedValues =
                    List.of(
                            MenuResponse.ADMIN_MENU_TRANSACTION_NAME,
                            SCREEN_TITLE_LINE_1,
                            CURRENT_DATE,
                            MenuResponse.ADMIN_MENU_PROGRAM_NAME,
                            SCREEN_TITLE_LINE_2,
                            CURRENT_TIME,
                            "null",
                            "[AdminMenuOption[number=1, label=User List (Security)],"
                                    + " AdminMenuOption[number=2, label=User Add (Security)],"
                                    + " AdminMenuOption[number=3, label=User Update (Security)],"
                                    + " AdminMenuOption[number=4, label=User Delete (Security)]]",
                            SELECTED_OPTION,
                            MESSAGE,
                            "INFORMATIONAL",
                            "false",
                            FOCUS_SCREEN_FIELD_ID,
                            ROUTE,
                            "null");

            StringBuilder expected = new StringBuilder("MenuResponse[");
            for (int index = 0; index < EXPECTED_COMPONENTS.size(); index++) {
                if (index > 0) {
                    expected.append(", ");
                }
                expected.append(EXPECTED_COMPONENTS.get(index))
                        .append('=')
                        .append(renderedValues.get(index));
            }
            expected.append(']');

            assertThat(response).hasToString(expected.toString());
        }

        /**
         * A response carrying no navigation state substitutes nothing, because this type declares no
         * rendering of its own. The wholesale claim is made only here.
         */
        @Test
        @DisplayName("substitutes nothing when no navigation state is carried")
        void aResponseCarryingNoNavigationStateContainsNoPlaceholder() {
            String rendered = userMenuWithoutNavigation().toString();

            assertThat(rendered).doesNotContain("***REDACTED***");
            assertThat(rendered).contains("navigationContext=null");
        }

        /** No component of this type is withheld from the rendering. */
        @Test
        @DisplayName("withholds no component of its own")
        void noComponentOfThisTypeIsWithheld() {
            String rendered = populatedUserMenu().toString();

            for (String component : EXPECTED_COMPONENTS) {
                assertThat(rendered)
                        .as(
                                "component %s belongs to a response returned to an already"
                                        + " authorised operator and is not withheld here",
                                component)
                        .doesNotContain(component + "=***REDACTED***");
            }
        }

        /**
         * Every published option value appears in the rendering, because an option row declares no
         * rendering of its own — and neither withheld item appears, because no row carries one.
         *
         * <p>The absence is asserted over the option collection's own rendering rather than over the
         * whole response, for the same reason the wire-shape test scopes its assertion: the nested
         * navigation state names the program the operator arrived from, and that program may be any
         * member of the estate including one that is also an option's dispatch target. Forbidding a
         * program name across the whole rendering would forbid legitimate screen-flow state, so the
         * final assertion pins that presence instead and thereby records why the narrower scope is the
         * correct one.</p>
         */
        @Test
        @DisplayName("shows every published option value and no dispatch target")
        void everyPublishedOptionValueAppearsInTheRendering() {
            MenuResponse response = populatedUserMenu();
            String rendered = response.toString();

            assertThat(rendered).contains("Account View").contains("Bill Payment");
            assertThat(response.userMenuOptions().toString())
                    .as("a dispatch target is not a screen value and no row carries one, so none can "
                            + "be rendered")
                    .doesNotContain("COACTVWC")
                    .doesNotContain("COBIL00C");
            assertThat(rendered)
                    .as("the program the operator arrived from is screen-flow state and is rendered, "
                            + "which is why the prohibition is scoped to the option rows")
                    .contains("fromProgram=COBIL00C");
        }

        /**
         * A nested navigation state withholds its own identifying components, so none of its
         * distinctive values reaches this rendering.
         */
        @Test
        @DisplayName("relies on the navigation state to withhold its identifying components")
        void aNestedNavigationStateWithholdsItsIdentifyingComponents() {
            String rendered = populatedUserMenu().toString();

            assertThat(rendered).contains("navigationContext=NavigationContext[");
            assertThat(rendered)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER);
        }
    }

    /** Equality, hashing and the immutability the record contract provides. */
    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        /** Two responses carrying equal components are equal. */
        @Test
        @DisplayName("treats equal components as equal values")
        void twoResponsesWithEqualComponentsAreEqual() {
            assertThat(populatedUserMenu()).isEqualTo(populatedUserMenu());
            assertThat(populatedAdminMenu()).isEqualTo(populatedAdminMenu());
        }

        /** Hashing is stable across equal instances. */
        @Test
        @DisplayName("hashes equal values alike")
        void hashCodeIsStableAcrossEqualInstances() {
            assertThat(populatedUserMenu()).hasSameHashCodeAs(populatedUserMenu());
        }

        /** A user menu and an administrative menu are never equal. */
        @Test
        @DisplayName("never equates a user menu with an administrative menu")
        void aUserMenuIsNeverEqualToAnAdminMenu() {
            assertThat(populatedUserMenu()).isNotEqualTo(populatedAdminMenu());
        }

        /**
         * The two computed header items are part of identity: two responses differing only in the
         * rendered time are not equal, so a client cannot mistake a stale header for the same value.
         * The absent-versus-empty distinction the two list screens draw does not arise here, because
         * neither state is constructible.
         */
        @Test
        @DisplayName("distinguishes two responses that differ only in a header item")
        void twoResponsesDifferingOnlyInAHeaderItemAreNotEqual() {
            MenuResponse earlier =
                    MenuResponse.forUserMenu(
                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                            CURRENT_DATE, CURRENT_TIME, CANONICAL_USER_MENU_OPTIONS,
                            null, null, null, false, null, null, null);
            MenuResponse later =
                    MenuResponse.forUserMenu(
                            SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                            CURRENT_DATE, "10:15:31", CANONICAL_USER_MENU_OPTIONS,
                            null, null, null, false, null, null, null);

            assertThat(earlier).isNotEqualTo(later);
            assertThat(earlier)
                    .isNotEqualTo(
                            MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, "02/01/24", CURRENT_TIME,
                                    CANONICAL_USER_MENU_OPTIONS, null, null, null,
                                    false, null, null, null));
        }

        /** A difference in the severity or the indicator is observed. */
        @Test
        @DisplayName("observes a difference in the severity or the indicator")
        void aDifferenceInTheSeverityOrIndicatorIsObserved() {
            MenuResponse informational =
                    MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                            CURRENT_TIME,
                            CANONICAL_USER_MENU_OPTIONS,
                            null,
                            MESSAGE,
                            MenuResponse.MessageSeverity.INFORMATIONAL,
                            false,
                            null,
                            null,
                            null);
            MenuResponse error =
                    MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                            CURRENT_TIME,
                            CANONICAL_USER_MENU_OPTIONS,
                            null,
                            MESSAGE,
                            MenuResponse.MessageSeverity.ERROR,
                            false,
                            null,
                            null,
                            null);
            MenuResponse flagged =
                    MenuResponse.forUserMenu(SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2, CURRENT_DATE,
                            CURRENT_TIME,
                            CANONICAL_USER_MENU_OPTIONS,
                            null,
                            MESSAGE,
                            MenuResponse.MessageSeverity.INFORMATIONAL,
                            true,
                            null,
                            null,
                            null);

            assertThat(informational).isNotEqualTo(error);
            assertThat(informational).isNotEqualTo(flagged);
        }

        /** Both option records have value equality across their components. */
        @Test
        @DisplayName("gives both option records value equality")
        void bothOptionRecordsHaveValueEquality() {
            assertThat(new MenuResponse.UserMenuOption(1, "L"))
                    .isEqualTo(new MenuResponse.UserMenuOption(1, "L"))
                    .hasSameHashCodeAs(new MenuResponse.UserMenuOption(1, "L"))
                    .isNotEqualTo(new MenuResponse.UserMenuOption(1, "M"))
                    .isNotEqualTo(new MenuResponse.UserMenuOption(2, "L"));
            assertThat(new MenuResponse.AdminMenuOption(1, "L"))
                    .isEqualTo(new MenuResponse.AdminMenuOption(1, "L"))
                    .hasSameHashCodeAs(new MenuResponse.AdminMenuOption(1, "L"))
                    .isNotEqualTo(new MenuResponse.AdminMenuOption(2, "L"));
        }

        /** Every accessor returns exactly what was supplied. */
        @Test
        @DisplayName("returns from every accessor exactly what was supplied")
        void everyAccessorReturnsWhatWasSupplied() {
            MenuResponse response =
                    new MenuResponse(
                            MenuResponse.USER_MENU_TRANSACTION_NAME,
                            SCREEN_TITLE_LINE_1,
                            CURRENT_DATE,
                            MenuResponse.USER_MENU_PROGRAM_NAME,
                            SCREEN_TITLE_LINE_2,
                            CURRENT_TIME,
                            CANONICAL_USER_MENU_OPTIONS,
                            null,
                            SELECTED_OPTION,
                            MESSAGE,
                            MenuResponse.MessageSeverity.ERROR,
                            true,
                            FOCUS_SCREEN_FIELD_ID,
                            ROUTE,
                            JsonContractSupport.populatedNavigation());

            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo(SCREEN_TITLE_LINE_1);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.programName()).isEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo(SCREEN_TITLE_LINE_2);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(response.userMenuOptions())
                    .containsExactlyElementsOf(CANONICAL_USER_MENU_OPTIONS);
            assertThat(response.adminMenuOptions()).isNull();
            assertThat(response.selectedOption()).isEqualTo(SELECTED_OPTION);
            assertThat(response.message()).isEqualTo(MESSAGE);
            assertThat(response.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(response.errorFlag()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(response.nextRoute()).isEqualTo(ROUTE);
            assertThat(response.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }
    }
}
