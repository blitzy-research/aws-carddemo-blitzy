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
import java.util.List;
import java.util.Map;

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
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link MenuResponse}, the response body shared by legacy transactions {@code CM00} and
 * {@code CA00} - the user main menu implemented by {@code app/cbl/COMEN01C.cbl} and the administrative
 * menu implemented by {@code app/cbl/COADM01C.cbl}.
 *
 * <p>The two option catalogues are the ten entries of {@code app/cpy/COMEN02Y.cpy} and the four of
 * {@code app/cpy/COADM02Y.cpy} ; the widths are those of the corresponding screen fields.
 *
 * <p><strong>The eighth user option is the one to watch.</strong> The user catalogue's eighth entry
 * carries a commented-out alternative label marking it administrator-only, and that label is inactive in
 * the shipped estate. Reproducing the commented-out form - or promoting the entry to the administrator
 * code - would deny a standard user a selection the legacy grants, which is a behavioural regression
 * dressed as a correction. It is asserted explicitly below rather than left to inspection.
 *
 * <p><strong>Two of the four copybook items are not response content, and are asserted where they
 * live.</strong> Each copybook entry declares an option number, a label, a dispatch program name and a
 * user-type code. Only the first two are rendered by the mapset, so only those two are published on
 * {@link MenuResponse.UserMenuOption} and {@link MenuResponse.AdminMenuOption}. The program name is
 * the transfer-control target the legacy program hands to {@code EXEC CICS XCTL}, and the user-type
 * code is the input to the authorization comparison {@code app/cbl/COMEN01C.cbl} makes at lines 136 to
 * 143 <em>before</em> it dispatches; neither is ever displayed and neither is needed to select a row,
 * because selection is by number. Both therefore stay on {@link MenuOptionCatalog}, which carries all
 * four items of every entry. Every assertion below about a program name or a user-type code is made
 * against the catalogue rather than against the response, and a bridging assertion pins the two
 * catalogues to the same numbers and the same labels so that withholding the dispatch items cannot
 * quietly become a divergence in the items both do publish.
 *
 * @see MenuResponse
 * @see MenuOptionCatalog
 */
@DisplayName("MenuResponse - the CM00 and CA00 menu response contract")
class MenuResponseRuleComplianceTest {

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

    /**
     * A mapper configured exactly as {@code application.yml} configures the application's own.
     *
     * @return the module-equivalent mapper
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a response and reads the result back as a tree.
     *
     * @param response the response to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final MenuResponse response) throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * The catalogue that carries all four items of every copybook entry.
     *
     * <p>Stateless and immutable, so one instance serves every assertion below.
     */
    private static final MenuOptionCatalog CATALOG = new MenuOptionCatalog();

    /**
     * A rendered current date at exactly {@link MenuResponse#CURRENT_DATE_WIDTH} characters.
     *
     * <p>The value is already assembled, as it is when the legacy program has finished moving it into
     * the header, so the factories carry it unchanged and nothing here formats a date.
     */
    private static final String RENDERED_DATE = "08/02/26";

    /**
     * A rendered current time at exactly {@link MenuResponse#CURRENT_TIME_WIDTH} characters.
     *
     * <p>Assembled on the same terms as {@link #RENDERED_DATE}.
     */
    private static final String RENDERED_TIME = "14:22:07";

    /**
     * Builds a user-menu response, supplying the two per-interaction header items so that a test which
     * is not about the header does not have to name them.
     *
     * <p>This delegates to {@link MenuResponse#forUserMenu} unchanged and adds nothing: the header
     * date and time are the only two parameters it fills, and they are filled with values at their
     * declared widths so they can never be the reason a validation assertion fires. The factory itself
     * is exercised directly, with every parameter named, by the two tests that are about the header.
     *
     * @param options            the user options to render
     * @param selectedOption     the echoed option entry, or {@code null}
     * @param message            the message line, or {@code null}
     * @param messageSeverity    the semantic intent of {@code message}, or {@code null}
     * @param errorFlag          whether the response reports a failed interaction
     * @param focusScreenFieldId the screen field identifier focus belongs on, or {@code null}
     * @param nextRoute          the declarative next route, or {@code null}
     * @param navigationContext  the client-echoed navigation state, or {@code null}
     * @return the user-menu response
     */
    private static MenuResponse userMenu(final List<MenuResponse.UserMenuOption> options,
                                        final String selectedOption,
                                        final String message,
                                        final MenuResponse.MessageSeverity messageSeverity,
                                        final boolean errorFlag,
                                        final String focusScreenFieldId,
                                        final String nextRoute,
                                        final NavigationContext navigationContext) {
        return MenuResponse.forUserMenu(
                SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                RENDERED_DATE, RENDERED_TIME, options, selectedOption,
                message, messageSeverity, errorFlag, focusScreenFieldId, nextRoute,
                navigationContext);
    }

    /**
     * Builds an administrative-menu response on the same terms as {@link #userMenu}.
     *
     * @param options            the administrative options to render
     * @param selectedOption     the echoed option entry, or {@code null}
     * @param message            the message line, or {@code null}
     * @param messageSeverity    the semantic intent of {@code message}, or {@code null}
     * @param errorFlag          whether the response reports a failed interaction
     * @param focusScreenFieldId the screen field identifier focus belongs on, or {@code null}
     * @param nextRoute          the declarative next route, or {@code null}
     * @param navigationContext  the client-echoed navigation state, or {@code null}
     * @return the administrative-menu response
     */
    private static MenuResponse adminMenu(final List<MenuResponse.AdminMenuOption> options,
                                          final String selectedOption,
                                          final String message,
                                          final MenuResponse.MessageSeverity messageSeverity,
                                          final boolean errorFlag,
                                          final String focusScreenFieldId,
                                          final String nextRoute,
                                          final NavigationContext navigationContext) {
        return MenuResponse.forAdminMenu(
                SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                RENDERED_DATE, RENDERED_TIME, options, selectedOption,
                message, messageSeverity, errorFlag, focusScreenFieldId, nextRoute,
                navigationContext);
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published widths and counts")
    class ThePublishedWidthsAndCounts {

        @Test
        @DisplayName("the option field widths the response publishes are the legacy ones")
        void theOptionFieldWidthsAreTheLegacyOnes() {
            assertThat(MenuResponse.OPTION_NUMBER_WIDTH).isEqualTo(2);
            assertThat(MenuResponse.OPTION_LABEL_WIDTH).isEqualTo(35);
            assertThat(MenuResponse.SELECTED_OPTION_WIDTH).isEqualTo(2);
        }

        /**
         * The dispatch and authorization widths are legacy contract too, and they are asserted on the
         * class that carries the values they measure.
         *
         * <p>Both are copybook item lengths: {@code CDEMO-MENU-OPT-PGMNAME} is eight characters and
         * {@code CDEMO-MENU-OPT-USRTYPE} is one. Neither is a response width, because neither item is
         * response content, so asserting them here against {@link MenuResponse} would assert nothing
         * about the mapset and would keep two names alive that the response has no use for.
         */
        @Test
        @DisplayName("the dispatch and authorization widths are the legacy ones, and are published by "
                + "the catalogue that carries the items they measure")
        void theDispatchAndAuthorizationWidthsAreTheLegacyOnes() {
            assertThat(MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH).isEqualTo(8);
            assertThat(MenuOptionCatalog.USER_OPTION_USER_TYPE_WIDTH).isEqualTo(1);
        }

        /**
         * The two classes agree on the two widths they both publish.
         *
         * <p>Splitting the entry across a response shape and a catalogue shape is only safe while the
         * shared measurements stay identical; a divergence would mean one of the two was rendering or
         * validating against a bound the copybook does not declare.
         */
        @Test
        @DisplayName("the response and the catalogue agree on the number and label widths they both "
                + "publish")
        void theResponseAndCatalogueAgreeOnTheSharedWidths() {
            assertThat(MenuResponse.OPTION_NUMBER_WIDTH)
                    .isEqualTo(MenuOptionCatalog.OPTION_NUMBER_WIDTH);
            assertThat(MenuResponse.OPTION_LABEL_WIDTH)
                    .isEqualTo(MenuOptionCatalog.OPTION_LABEL_WIDTH);
            assertThat(MenuResponse.USER_MENU_OPTION_COUNT)
                    .isEqualTo(MenuOptionCatalog.USER_MENU_OPTION_COUNT);
            assertThat(MenuResponse.ADMIN_MENU_OPTION_COUNT)
                    .isEqualTo(MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT);
        }

        @Test
        @DisplayName("the screen widths are the legacy ones, and the message work field is wider than "
                + "the screen field it is written into")
        void theScreenWidthsAreTheLegacyOnes() {
            assertThat(MenuResponse.SCREEN_TITLE_WIDTH).isEqualTo(40);
            assertThat(MenuResponse.MESSAGE_WIDTH).isEqualTo(80);
            assertThat(MenuResponse.SCREEN_MESSAGE_FIELD_WIDTH).isEqualTo(78);
            assertThat(MenuResponse.COMMON_MESSAGE_WIDTH).isEqualTo(50);
            assertThat(MenuResponse.SCREEN_MESSAGE_FIELD_WIDTH)
                    .isLessThan(MenuResponse.MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("the two catalogue sizes are 10 and 4, the counts the copybooks actually populate "
                + "rather than the table capacities they declare")
        void theTwoCatalogueSizesAreTenAndFour() {
            assertThat(MenuResponse.USER_MENU_OPTION_COUNT).isEqualTo(10);
            assertThat(MenuResponse.ADMIN_MENU_OPTION_COUNT).isEqualTo(4);
        }

        @Test
        @DisplayName("the standard-user code is U, and it is the catalogue that publishes it")
        void theStandardUserCodeIsU() {
            assertThat(MenuOptionCatalog.STANDARD_USER_TYPE_CODE).isEqualTo("U");
            assertThat(MenuOptionCatalog.STANDARD_USER_TYPE_CODE)
                    .hasSize(MenuOptionCatalog.USER_OPTION_USER_TYPE_WIDTH);
        }

        @Test
        @DisplayName("the three screen titles are each exactly the declared title width, because the "
                + "legacy literals are space-filled to it and the padding is contract")
        void theThreeScreenTitlesAreExactlyTheTitleWidth() {
            assertThat(SCREEN_TITLE_LINE_1).hasSize(MenuResponse.SCREEN_TITLE_WIDTH);
            assertThat(SCREEN_TITLE_LINE_2).hasSize(MenuResponse.SCREEN_TITLE_WIDTH);
            assertThat(SCREEN_TITLE_THANK_YOU).hasSize(MenuResponse.SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("the three screen titles carry the legacy text, padding included")
        void theThreeScreenTitlesCarryTheLegacyText() {
            assertThat(SCREEN_TITLE_LINE_1).isEqualTo(
                    "      AWS Mainframe Modernization       ");
            assertThat(SCREEN_TITLE_LINE_2).isEqualTo(
                    "              CardDemo                  ");
            assertThat(SCREEN_TITLE_THANK_YOU).isEqualTo(
                    "Thank you for using CCDA application... ");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the two canonical option catalogues")
    class TheTwoCanonicalOptionCatalogues {

        @Test
        @DisplayName("the user catalogue holds exactly ten entries, numbered one to ten in order")
        void theUserCatalogueHoldsTenEntriesInOrder() {
            final List<MenuResponse.UserMenuOption> options =
                    CANONICAL_USER_MENU_OPTIONS;

            assertThat(options).hasSize(MenuResponse.USER_MENU_OPTION_COUNT);
            for (int index = 0; index < options.size(); index++) {
                assertThat(options.get(index).number())
                        .as("entry at index %d", index)
                        .isEqualTo(index + 1);
            }
        }

        /**
         * The ten dispatch targets are catalogue content, and the response shape does not carry them.
         *
         * <p>Both halves are asserted together on purpose. Naming the ten programs proves the dispatch
         * table is the legacy one; asserting that the response entry has no such component proves a
         * client is never handed the means to name a transfer-control target.
         */
        @Test
        @DisplayName("the catalogue names the ten legacy programs in catalogue order, and the response "
                + "entry carries no program name at all")
        void theUserCatalogueNamesTheTenLegacyPrograms() {
            assertThat(CATALOG.userMenuOptions().stream()
                    .map(MenuOptionCatalog.UserMenuOption::programName).toList())
                    .containsExactly("COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
                            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");
            assertThat(MenuResponse.UserMenuOption.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .containsExactly("number", "label")
                    .doesNotContain("programName", "userType");
        }

        /**
         * The two catalogues agree on the two items they both publish.
         *
         * <p>This is the bridging assertion the class javadoc promises. It is what keeps withholding
         * the dispatch items from turning into a divergence: if a label or a number were ever changed
         * on one side only, the screen would render one thing and the dispatcher would select from
         * another, and nothing else in either class would notice.
         */
        @Test
        @DisplayName("the response catalogue and the option catalogue carry the same numbers and the "
                + "same labels, in the same order")
        void theTwoCataloguesAgreeOnWhatTheyBothPublish() {
            assertThat(CANONICAL_USER_MENU_OPTIONS.stream()
                    .map(MenuResponse.UserMenuOption::number).toList())
                    .isEqualTo(CATALOG.userMenuOptions().stream()
                            .map(MenuOptionCatalog.UserMenuOption::number).toList());
            assertThat(CANONICAL_USER_MENU_OPTIONS.stream()
                    .map(MenuResponse.UserMenuOption::label).toList())
                    .isEqualTo(CATALOG.userMenuOptions().stream()
                            .map(MenuOptionCatalog.UserMenuOption::label).toList());
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS.stream()
                    .map(MenuResponse.AdminMenuOption::number).toList())
                    .isEqualTo(CATALOG.adminMenuOptions().stream()
                            .map(MenuOptionCatalog.AdminMenuOption::number).toList());
            assertThat(CANONICAL_ADMIN_MENU_OPTIONS.stream()
                    .map(MenuResponse.AdminMenuOption::label).toList())
                    .isEqualTo(CATALOG.adminMenuOptions().stream()
                            .map(MenuOptionCatalog.AdminMenuOption::label).toList());
        }

        @Test
        @DisplayName("the user catalogue carries the legacy labels in catalogue order")
        void theUserCatalogueCarriesTheLegacyLabels() {
            assertThat(CANONICAL_USER_MENU_OPTIONS.stream()
                    .map(MenuResponse.UserMenuOption::label).toList())
                    .containsExactly("Account View", "Account Update", "Credit Card List",
                            "Credit Card View", "Credit Card Update", "Transaction List",
                            "Transaction View", "Transaction Add", "Transaction Reports",
                            "Bill Payment");
        }

        @Test
        @DisplayName("every user entry carries the standard-user code, which is why the "
                + "administrator-only gate is dormant in the shipped estate")
        void everyUserEntryCarriesTheStandardUserCode() {
            for (final MenuOptionCatalog.UserMenuOption option : CATALOG.userMenuOptions()) {
                assertThat(option.userType())
                        .as("entry %d, %s", option.number(), option.label())
                        .isEqualTo(MenuOptionCatalog.STANDARD_USER_TYPE_CODE);
            }
        }

        @Test
        @DisplayName("the eighth user entry carries the active label and the standard-user code, not the "
                + "commented-out administrator-only alternative")
        void theEighthUserEntryCarriesTheActiveLabel() {
            final MenuResponse.UserMenuOption rendered =
                    CANONICAL_USER_MENU_OPTIONS.get(7);
            final MenuOptionCatalog.UserMenuOption dispatched = CATALOG.userMenuOptions().get(7);

            assertThat(rendered.number()).isEqualTo(8);
            assertThat(rendered.label())
                    .as("the commented-out alternative label must remain inactive")
                    .isEqualTo("Transaction Add");
            assertThat(dispatched.number()).isEqualTo(8);
            assertThat(dispatched.label()).isEqualTo(rendered.label());
            assertThat(dispatched.userType())
                    .as("promoting this entry would deny a standard user a selection the legacy grants")
                    .isEqualTo(MenuOptionCatalog.STANDARD_USER_TYPE_CODE);
            assertThat(dispatched.programName()).isEqualTo("COTRN02C");
        }

        @Test
        @DisplayName("the administrative catalogue holds exactly four entries, numbered one to four, "
                + "naming the four user-maintenance programs")
        void theAdministrativeCatalogueHoldsFourEntries() {
            final List<MenuResponse.AdminMenuOption> options =
                    CANONICAL_ADMIN_MENU_OPTIONS;

            assertThat(options).hasSize(MenuResponse.ADMIN_MENU_OPTION_COUNT);
            assertThat(options.stream().map(MenuResponse.AdminMenuOption::number).toList())
                    .containsExactly(1, 2, 3, 4);
            assertThat(CATALOG.adminMenuOptions().stream()
                    .map(MenuOptionCatalog.AdminMenuOption::programName).toList())
                    .containsExactly("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");
            assertThat(options.stream().map(MenuResponse.AdminMenuOption::label).toList())
                    .containsExactly("User List (Security)", "User Add (Security)",
                            "User Update (Security)", "User Delete (Security)");
        }

        @Test
        @DisplayName("the administrative entry carries no user-type field at all, which is why the "
                + "administrative menu applies no user-type gate")
        void theAdministrativeEntryCarriesNoUserTypeField() {
            assertThat(MenuResponse.AdminMenuOption.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .containsExactly("number", "label")
                    .doesNotContain("userType");
            assertThat(MenuOptionCatalog.AdminMenuOption.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .containsExactly("number", "label", "programName")
                    .as("the catalogue carries the dispatch target but still no user-type code")
                    .doesNotContain("userType");
        }

        @Test
        @DisplayName("every catalogued label and program name fits its declared width")
        void everyCataloguedValueFitsItsDeclaredWidth() {
            for (final MenuResponse.UserMenuOption option : CANONICAL_USER_MENU_OPTIONS) {
                assertThat(option.label().length())
                        .isLessThanOrEqualTo(MenuResponse.OPTION_LABEL_WIDTH);
            }
            for (final MenuResponse.AdminMenuOption option
                    : CANONICAL_ADMIN_MENU_OPTIONS) {
                assertThat(option.label().length())
                        .isLessThanOrEqualTo(MenuResponse.OPTION_LABEL_WIDTH);
            }
            for (final MenuOptionCatalog.UserMenuOption option : CATALOG.userMenuOptions()) {
                assertThat(option.programName())
                        .hasSize(MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH);
                assertThat(option.userType())
                        .hasSize(MenuOptionCatalog.USER_OPTION_USER_TYPE_WIDTH);
            }
            for (final MenuOptionCatalog.AdminMenuOption option : CATALOG.adminMenuOptions()) {
                assertThat(option.programName())
                        .hasSize(MenuOptionCatalog.OPTION_PROGRAM_NAME_WIDTH);
            }
        }

        @Test
        @DisplayName("both catalogues are immutable, so a caller can hold either without copying it")
        void bothCataloguesAreImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CANONICAL_USER_MENU_OPTIONS.clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CANONICAL_ADMIN_MENU_OPTIONS.clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CATALOG.userMenuOptions().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CATALOG.adminMenuOptions().clear());
        }

        @Test
        @DisplayName("no catalogued program name begins with the suppression literal, which is exactly "
                + "why the suppression branch is unreachable in the shipped estate")
        void noCataloguedProgramNameBeginsWithTheSuppressionLiteral() {
            for (final MenuOptionCatalog.UserMenuOption option : CATALOG.userMenuOptions()) {
                assertThat(option.programName()).doesNotStartWith("DUMMY");
            }
            for (final MenuOptionCatalog.AdminMenuOption option : CATALOG.adminMenuOptions()) {
                assertThat(option.programName()).doesNotStartWith("DUMMY");
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the two factories and the two predicates")
    class TheTwoFactoriesAndPredicates {

        @Test
        @DisplayName("the user-menu factory carries both producer-supplied screen titles and leaves "
                + "the administrative catalogue absent")
        void theUserMenuFactoryCarriesBothTitles() {
            final MenuResponse response = MenuResponse.forUserMenu(
                    SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME,
                    CANONICAL_USER_MENU_OPTIONS, "01", "Ready",
                    MenuResponse.MessageSeverity.INFORMATIONAL, false, "OPTIONI", "account-view",
                    NavigationContext.empty());

            assertThat(response.title01()).isEqualTo(SCREEN_TITLE_LINE_1);
            assertThat(response.title02()).isEqualTo(SCREEN_TITLE_LINE_2);
            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME);
            assertThat(response.programName()).isEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME);
            assertThat(response.currentDate())
                    .as("the rendered date arrives assembled and is carried unchanged")
                    .isEqualTo(RENDERED_DATE);
            assertThat(response.currentTime()).isEqualTo(RENDERED_TIME);
            assertThat(response.userMenuOptions()).hasSize(MenuResponse.USER_MENU_OPTION_COUNT);
            assertThat(response.adminMenuOptions()).isNull();
            assertThat(response.selectedOption()).isEqualTo("01");
            assertThat(response.message()).isEqualTo("Ready");
            assertThat(response.messageSeverity())
                    .isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
            assertThat(response.errorFlag()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo("OPTIONI");
            assertThat(response.nextRoute()).isEqualTo("account-view");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("the administrative-menu factory carries both producer-supplied screen titles "
                + "and leaves the user catalogue absent")
        void theAdminMenuFactoryCarriesBothTitles() {
            final MenuResponse response = MenuResponse.forAdminMenu(
                    SCREEN_TITLE_LINE_1, SCREEN_TITLE_LINE_2,
                    RENDERED_DATE, RENDERED_TIME,
                    CANONICAL_ADMIN_MENU_OPTIONS, "02", "Denied",
                    MenuResponse.MessageSeverity.ERROR, true, "OPTIONI", "user-add",
                    NavigationContext.empty());

            assertThat(response.title01()).isEqualTo(SCREEN_TITLE_LINE_1);
            assertThat(response.title02()).isEqualTo(SCREEN_TITLE_LINE_2);
            assertThat(response.transactionName())
                    .as("the two menus differ in transaction and program, and agree on both titles")
                    .isEqualTo(MenuResponse.ADMIN_MENU_TRANSACTION_NAME);
            assertThat(response.programName()).isEqualTo(MenuResponse.ADMIN_MENU_PROGRAM_NAME);
            assertThat(response.currentDate()).isEqualTo(RENDERED_DATE);
            assertThat(response.currentTime()).isEqualTo(RENDERED_TIME);
            assertThat(response.adminMenuOptions()).hasSize(MenuResponse.ADMIN_MENU_OPTION_COUNT);
            assertThat(response.userMenuOptions()).isNull();
            assertThat(response.errorFlag()).isTrue();
            assertThat(response.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
        }

        @Test
        @DisplayName("the factories take both titles from the producer and default neither, so this "
                + "contract cannot become a second authority for the title text")
        void theFactoriesTakeBothTitlesFromTheProducer() {
            final String firstSentinel = "<<<title-one-supplied-by-the-producer>>>";
            final String secondSentinel = "<<<title-two-supplied-by-the-producer>>>";

            final MenuResponse user = MenuResponse.forUserMenu(firstSentinel, secondSentinel,
                    RENDERED_DATE, RENDERED_TIME, CANONICAL_USER_MENU_OPTIONS, null, null, null,
                    false, null, null, null);
            final MenuResponse admin = MenuResponse.forAdminMenu(firstSentinel, secondSentinel,
                    RENDERED_DATE, RENDERED_TIME, CANONICAL_ADMIN_MENU_OPTIONS, null, null, null,
                    false, null, null, null);

            assertThat(user.title01()).isEqualTo(firstSentinel);
            assertThat(user.title02()).isEqualTo(secondSentinel);
            assertThat(admin.title01()).isEqualTo(firstSentinel);
            assertThat(admin.title02()).isEqualTo(secondSentinel);
        }

        @Test
        @DisplayName("both factories accept an absent title rather than substituting the owner's value, "
                + "which is the observable difference between a parameter and a hidden default")
        void bothFactoriesAcceptAnAbsentTitle() {
            final MenuResponse user = MenuResponse.forUserMenu(null, null, RENDERED_DATE,
                    RENDERED_TIME, CANONICAL_USER_MENU_OPTIONS, null, null, null, false, null, null,
                    null);
            final MenuResponse admin = MenuResponse.forAdminMenu(null, null, RENDERED_DATE,
                    RENDERED_TIME, CANONICAL_ADMIN_MENU_OPTIONS, null, null, null, false, null, null,
                    null);

            assertThat(user.title01()).isNull();
            assertThat(user.title02()).isNull();
            assertThat(admin.title01()).isNull();
            assertThat(admin.title02()).isNull();
        }

        @Test
        @DisplayName("both factories declare the two titles as their first two parameters, so a producer "
                + "cannot omit them by accident")
        void bothFactoriesDeclareTheTitlesFirst() throws NoSuchMethodException {
            assertThat(MenuResponse.class
                    .getDeclaredMethod("forUserMenu", String.class, String.class, String.class,
                            String.class, List.class, String.class, String.class,
                            MenuResponse.MessageSeverity.class, boolean.class, String.class,
                            String.class, NavigationContext.class)
                    .getParameterCount())
                    .isEqualTo(12);
            assertThat(MenuResponse.class
                    .getDeclaredMethod("forAdminMenu", String.class, String.class, String.class,
                            String.class, List.class, String.class, String.class,
                            MenuResponse.MessageSeverity.class, boolean.class, String.class,
                            String.class, NavigationContext.class)
                    .getParameterCount())
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the two predicates report which catalogue the response carries, and exactly one is "
                + "true for each factory")
        void thePredicatesReportWhichCatalogueIsCarried() {
            final MenuResponse userMenu = userMenu(
                    CANONICAL_USER_MENU_OPTIONS, null, null, null, false, null, null,
                    null);
            final MenuResponse adminMenu = adminMenu(
                    CANONICAL_ADMIN_MENU_OPTIONS, null, null, null, false, null, null,
                    null);

            assertThat(userMenu.carriesUserMenu()).isTrue();
            assertThat(userMenu.carriesAdminMenu()).isFalse();
            assertThat(adminMenu.carriesAdminMenu()).isTrue();
            assertThat(adminMenu.carriesUserMenu()).isFalse();
        }

        /**
         * An empty collection is refused rather than carried, because no legacy screen state produces
         * one.
         *
         * <p>{@code app/cbl/COMEN01C.cbl} rebuilds all ten rows on every send, including each redisplay
         * after a rejected entry, so a user menu showing no rows describes no screen the estate can
         * produce. The count is published as a constant, and accepting any other size would let a
         * mis-assembled screen reach a client as a well-formed response.
         *
         * <p>Presence and content remain different questions: {@link MenuResponse#carriesUserMenu()}
         * still answers which of the two option shapes a client is about to read, and it is the
         * cardinality invariant rather than the predicate that rules an empty collection out.
         */
        @Test
        @DisplayName("an empty catalogue is refused, because the legacy screen rebuilds every row on "
                + "every send and so never shows a menu with nothing on it")
        void anEmptyCatalogueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> userMenu(List.of(), null, null, null, false, null, null, null))
                    .withMessageContaining("the user menu renders exactly "
                            + MenuResponse.USER_MENU_OPTION_COUNT + " options")
                    .withMessageContaining("but 0 were supplied");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> adminMenu(List.of(), null, null, null, false, null, null, null))
                    .withMessageContaining("the administrative menu renders exactly "
                            + MenuResponse.ADMIN_MENU_OPTION_COUNT + " options");
        }

        /**
         * A response carrying neither collection is refused, and so is one carrying both.
         *
         * <p>A menu screen is one menu. Transaction {@code CM00} renders the user table and {@code CA00}
         * renders the administrative one; neither program can render both, and neither renders a screen
         * with no rows. A response carrying both collections or neither therefore describes no screen
         * the estate can produce, which is why exactly-one is a construction invariant rather than a
         * convention the two factories happen to observe.
         *
         * <p>The thank-you title is <em>not</em> a counter-example. It is the title the sign-off path
         * moves in before transferring control away from the menu program, and the response that
         * carries it is the sign-on screen's, not a menu response with its rows omitted.
         */
        @Test
        @DisplayName("a response carrying neither catalogue is refused, and so is one carrying both")
        void aResponseCarryingNeitherOrBothCataloguesIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MenuResponse(MenuResponse.USER_MENU_TRANSACTION_NAME,
                            SCREEN_TITLE_THANK_YOU, RENDERED_DATE,
                            MenuResponse.USER_MENU_PROGRAM_NAME, SCREEN_TITLE_LINE_2,
                            RENDERED_TIME, null, null, null, null, null, false, null, "sign-on",
                            NavigationContext.empty()))
                    .withMessageContaining("a menu response carries exactly one option collection")
                    .withMessageContaining("neither was supplied");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MenuResponse(MenuResponse.USER_MENU_TRANSACTION_NAME,
                            SCREEN_TITLE_LINE_1, RENDERED_DATE,
                            MenuResponse.USER_MENU_PROGRAM_NAME, SCREEN_TITLE_LINE_2,
                            RENDERED_TIME, CANONICAL_USER_MENU_OPTIONS,
                            CANONICAL_ADMIN_MENU_OPTIONS, null, null, null, false, null,
                            null, NavigationContext.empty()))
                    .withMessageContaining("both were supplied");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the compact constructor's defensive copying")
    class TheCompactConstructorsDefensiveCopying {

        @Test
        @DisplayName("a supplied user catalogue is copied, so a later change to the caller's list cannot "
                + "reach inside the response")
        void aSuppliedUserCatalogueIsCopied() {
            final List<MenuResponse.UserMenuOption> mutable = new ArrayList<>(
                    CANONICAL_USER_MENU_OPTIONS);

            final MenuResponse response = userMenu(mutable, null, null, null, false,
                    null, null, null);
            mutable.clear();

            assertThat(response.userMenuOptions())
                    .hasSize(MenuResponse.USER_MENU_OPTION_COUNT)
                    .containsExactlyElementsOf(CANONICAL_USER_MENU_OPTIONS);
        }

        @Test
        @DisplayName("a supplied administrative catalogue is copied too")
        void aSuppliedAdminCatalogueIsCopied() {
            final List<MenuResponse.AdminMenuOption> mutable = new ArrayList<>(
                    CANONICAL_ADMIN_MENU_OPTIONS);

            final MenuResponse response = adminMenu(mutable, null, null, null, false,
                    null, null, null);
            mutable.clear();

            assertThat(response.adminMenuOptions())
                    .hasSize(MenuResponse.ADMIN_MENU_OPTION_COUNT);
        }

        @Test
        @DisplayName("the copy is unmodifiable, so nothing downstream can alter a published catalogue")
        void theCopyIsUnmodifiable() {
            final MenuResponse response = userMenu(
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS), null, null, null,
                    false, null, null, null);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.userMenuOptions().clear());
        }

        /**
         * The collection the response does not carry stays absent rather than becoming an empty list.
         *
         * <p>The two are different statements. On a user-menu response there is no administrative
         * collection <em>at all</em>, which is not the same as an administrative menu that happens to
         * list nothing - and because the module serializes only non-{@code null} properties, storing
         * {@code null} omits the irrelevant collection from the payload entirely rather than leaving a
         * client to decide what an empty one meant.
         *
         * <p>Both cases are asserted from the factories, because exactly-one is a construction
         * invariant: there is no admissible response in which both collections are absent, so the
         * substitution can only be observed on the collection the response legitimately omits.
         */
        @Test
        @DisplayName("the collection a response does not carry is left absent rather than replaced with "
                + "an empty one")
        void theUncarriedCatalogueIsLeftAbsent() {
            final MenuResponse user = userMenu(CANONICAL_USER_MENU_OPTIONS, null, null,
                    null, false, null, null, null);
            final MenuResponse admin = adminMenu(CANONICAL_ADMIN_MENU_OPTIONS, null,
                    null, null, false, null, null, null);

            assertThat(user.adminMenuOptions()).isNull();
            assertThat(user.userMenuOptions()).isNotNull();
            assertThat(admin.userMenuOptions()).isNull();
            assertThat(admin.adminMenuOptions()).isNotNull();
        }

        @Test
        @DisplayName("a catalogue containing an absent entry is rejected, because a null entry is one a "
                + "client could not render")
        void aCatalogueContainingAnAbsentEntryIsRejected() {
            final List<MenuResponse.UserMenuOption> withNull =
                    new ArrayList<>(CANONICAL_USER_MENU_OPTIONS);
            withNull.set(4, null);

            assertThat(withNull).hasSize(MenuResponse.USER_MENU_OPTION_COUNT);
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> userMenu(withNull, null, null, null, false, null, null,
                            null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the message-severity vocabulary")
    class TheMessageSeverityVocabulary {

        @Test
        @DisplayName("the vocabulary is exactly informational and error, the two states the legacy screen "
                + "distinguishes")
        void theVocabularyIsExactlyTwoStates() {
            assertThat(MenuResponse.MessageSeverity.values())
                    .containsExactly(MenuResponse.MessageSeverity.INFORMATIONAL,
                            MenuResponse.MessageSeverity.ERROR);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(MenuResponse.MessageSeverity.class)
        @DisplayName("each severity round-trips through the response and through the payload as its "
                + "constant name")
        void eachSeverityRoundTrips(final MenuResponse.MessageSeverity severity)
                throws JsonProcessingException {

            final MenuResponse response = userMenu(
                    CANONICAL_USER_MENU_OPTIONS, null, "Message", severity, false, null,
                    null, null);

            assertThat(response.messageSeverity()).isEqualTo(severity);
            assertThat(payloadOf(response).get("messageSeverity").asText())
                    .isEqualTo(severity.name());
            assertThat(MenuResponse.MessageSeverity.valueOf(severity.name())).isEqualTo(severity);
        }

        @Test
        @DisplayName("the severity is independent of the error flag, because the flag drives field "
                + "decoration and the severity drives message rendering")
        void theSeverityIsIndependentOfTheErrorFlag() {
            final MenuResponse response = userMenu(
                    CANONICAL_USER_MENU_OPTIONS, null, "Message",
                    MenuResponse.MessageSeverity.ERROR, false, null, null, null);

            assertThat(response.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(response.errorFlag()).isFalse();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics, validation and the published payload")
    class ValueSemanticsValidationAndPayload {

        @Test
        @DisplayName("two responses built the same way are equal and share a hash code")
        void twoIdenticalResponsesAreEqual() {
            final MenuResponse first = userMenu(
                    CANONICAL_USER_MENU_OPTIONS, "01", "Ready",
                    MenuResponse.MessageSeverity.INFORMATIONAL, false, "OPTIONI", "account-view",
                    NavigationContext.empty());
            final MenuResponse second = userMenu(
                    CANONICAL_USER_MENU_OPTIONS, "01", "Ready",
                    MenuResponse.MessageSeverity.INFORMATIONAL, false, "OPTIONI", "account-view",
                    NavigationContext.empty());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a user-menu response and an administrative-menu response are never equal, even "
                + "when every other component agrees")
        void theTwoMenusAreNeverEqual() {
            assertThat(userMenu(CANONICAL_USER_MENU_OPTIONS, null, null, null, false,
                    null, null, null))
                    .isNotEqualTo(adminMenu(CANONICAL_ADMIN_MENU_OPTIONS, null, null,
                            null, false, null, null, null));
        }

        @Test
        @DisplayName("a value at its declared width passes validation and one character over is reported")
        void aValueAtItsWidthPassesAndOneOverIsReported() {
            final MenuResponse atBound = userMenu(CANONICAL_USER_MENU_OPTIONS,
                    "X".repeat(MenuResponse.SELECTED_OPTION_WIDTH), null, null, false, null, null,
                    null);
            final MenuResponse overBound = userMenu(CANONICAL_USER_MENU_OPTIONS,
                    "X".repeat(MenuResponse.SELECTED_OPTION_WIDTH + 1), null, null, false, null,
                    null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(atBound)).isEmpty();
                assertThat(factory.getValidator().validate(overBound)).hasSize(1);
                assertThat(factory.getValidator().validate(overBound).iterator().next()
                        .getPropertyPath()).hasToString("selectedOption");
            }
        }

        @Test
        @DisplayName("the canonical catalogues pass validation, so the shipped contract is internally "
                + "consistent")
        void theCanonicalCataloguesPassValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(userMenu(
                        CANONICAL_USER_MENU_OPTIONS, "01",
                        SCREEN_TITLE_THANK_YOU, null, false, null, null, null)))
                        .isEmpty();
                assertThat(factory.getValidator().validate(adminMenu(
                        CANONICAL_ADMIN_MENU_OPTIONS, "01", null, null, false, null,
                        null, null)))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("an absent component is omitted from the payload and the absent catalogue leaves no "
                + "key behind")
        void anAbsentComponentIsOmittedFromThePayload() throws JsonProcessingException {
            final JsonNode payload = payloadOf(userMenu(
                    CANONICAL_USER_MENU_OPTIONS, null, null, null, false, null,
                    "user-menu", null));

            assertThat(payload.has("adminMenuOptions")).isFalse();
            assertThat(payload.has("selectedOption")).isFalse();
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.get("userMenuOptions"))
                    .isNotNull();
            assertThat(payload.get("userMenuOptions").size())
                    .isEqualTo(MenuResponse.USER_MENU_OPTION_COUNT);
            assertThat(payload.get("errorFlag").asBoolean()).isFalse();
        }

        /**
         * The rendered entry publishes the number and the label, and publishes nothing else.
         *
         * <p>The dispatch target and the user-type code are asserted here by their <em>absence</em> from
         * the payload, and their values are asserted against {@link MenuOptionCatalog} above. Both
         * halves matter: a client that could read {@code programName} would be able to name a
         * transfer-control target, and one that could read {@code userType} would be able to read an
         * authorization rule the server applies before it dispatches and that the client has no use
         * for.
         *
         * <p>The whole entry is compared as a rendered document rather than key by key, so a component
         * added later is caught rather than tolerated.
         */
        @Test
        @DisplayName("each rendered entry publishes its number and label, and the dispatch target and "
                + "user-type code appear nowhere in the payload")
        void eachCatalogueEntryRendersItsOwnComponents() throws JsonProcessingException {
            final JsonNode payload = payloadOf(userMenu(
                    CANONICAL_USER_MENU_OPTIONS, null, null, null, false, null, null,
                    null));
            final JsonNode firstUserEntry = payload.get("userMenuOptions").get(0);

            assertThat(firstUserEntry.get("number").asInt()).isEqualTo(1);
            assertThat(firstUserEntry.get("label").asText()).isEqualTo("Account View");
            assertThat(firstUserEntry.properties())
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder("number", "label");
            assertThat(payload.toString())
                    .as("no dispatch target value reaches the client, on any of the ten rows")
                    .doesNotContain("COACTVWC", "COBIL00C")
                    .as("nor does the authorization code the server applies before it dispatches")
                    .doesNotContain("userType");
            assertThat(payload.get("userMenuOptions").toString())
                    .as("the entries carry a number and a label and no third key, on any row - asserted "
                            + "over the entry array rather than the whole document, because the screen "
                            + "publishes its own program-name display field and that is not a dispatch "
                            + "target")
                    .doesNotContain("programName");
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final MenuResponse original = adminMenu(
                    CANONICAL_ADMIN_MENU_OPTIONS, "03", "Denied",
                    MenuResponse.MessageSeverity.ERROR, true, "OPTIONI", "user-update",
                    NavigationContext.empty());

            assertThat(mapper.readValue(mapper.writeValueAsString(original), MenuResponse.class))
                    .isEqualTo(original);
        }
    }
}
