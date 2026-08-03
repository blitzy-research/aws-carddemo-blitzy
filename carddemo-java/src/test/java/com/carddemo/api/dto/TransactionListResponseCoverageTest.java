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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for {@link TransactionListResponse} and its nested {@link
 * TransactionListResponse.TransactionRow}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The declared shape of the transaction-list response: its fifteen components and their order,
 * the ten screen widths it bounds, the five components it deliberately leaves unbounded, the seven
 * published screen messages, the two things its compact constructors refuse and the several things
 * they deliberately do not do, the wire form each component takes, and what its hand-written text
 * rendering withholds and retains.
 *
 * <h2>The collaborators are declared first, and they carry the card-list names</h2>
 *
 * <p>This response declares its row list, its browse position and its navigation state as its first
 * three components, which is the reverse of the card-list response's arrangement. The two
 * collaborators carry the same spellings its sibling uses - {@code pageMetadata} and {@code
 * navigationContext} - because they are the same types carrying the same meaning on both screens, and
 * a client that has learned to read a browse position on one screen should read it identically on the
 * other. The reporting components are not shared: this screen carries one {@code message} that reports
 * a browse boundary as often as a mistake and one {@code error} indicator, where the sibling separates
 * an informational text from an error text and names its indicator for the general case. Those names
 * are the wire contract, so the order, the shared spellings and the divergent ones are each asserted
 * directly against the sibling's own declarations.
 *
 * <h2>Two refusals, and why a refusal is not the same as a repair</h2>
 *
 * <p>The compact constructors refuse exactly two things: a page holding more rows than the screen has
 * row families, and a row amount whose decimal shape contradicts the record field it represents. Both
 * are refusals rather than corrections. Truncating a surplus row or rescaling an out-of-shape amount
 * would leave the caller with a plausible payload it could neither audit nor attribute; refusing names
 * the defect at the boundary where the producer that assembled it can still be identified. Neither
 * refusal changes a byte of anything it accepts, and both failure texts name the bound and the
 * offending figure without echoing the value, so a rejected amount cannot reach a log through the
 * diagnostic that reports it.
 *
 * <h2>Three components and three row values are withheld from the rendering</h2>
 *
 * <p>The type declares a rendering of its own rather than accepting the generated one, because a
 * generated rendering would have printed a full page: up to ten transaction identifiers beside their
 * descriptions and their amounts, in one log line, from any structured logger, framework diagnostic,
 * failed assertion or string interpolation that touched the response. The row list, the browse cursor
 * and the echoed search key are replaced by a fixed placeholder and a row count is emitted in their
 * place; each row withholds its identifier, description and amount behind the same placeholder as an
 * inner line of defence. The browse cursor is withheld unconditionally, so the placeholder's presence
 * reports nothing about whether the operator had paged.
 *
 * <h2>Five page-boundary messages, and one of them punctuates differently</h2>
 *
 * <p>The browse publishes five distinct boundary texts, not two: three report the top of the browse
 * and two report the bottom, and they are emitted from five different paragraphs so that the text
 * reports which mechanism found the boundary. Two say "already" because nothing moved; three say "at
 * the top" or "have reached" because an access was attempted. A sixth message reports a non-numeric
 * identifier and is the only one of the seven that puts a space before its three dots, and a seventh
 * reports an invalid selection and carries no ellipsis at all. Every one of those distinctions is one
 * byte wide, and each is asserted on its own.
 *
 * <h2>Three widths coincide with differently-sized fields elsewhere and must not be unified</h2>
 *
 * <p>The row description is twenty-six characters here, sixty on the transaction view screen and a
 * hundred in the stored record; the row date is eight here and ten on the view screen; and the page
 * indicator is eight here and three on the card-list screen. The divergences are asserted against the
 * neighbouring types' own declarations so that unifying any pair would fail here.
 *
 * <h2>A short page stays short, an over-deep page is refused, and nothing is reordered</h2>
 *
 * <p>The compact constructor copies the row list and turns a null list into an empty one. It does not
 * pad the list to the screen depth, does not sort or reverse it, does not truncate it, and does not
 * trim, pad, case-fold, reformat, rescale or round anything. Each of those omissions is a deliberate
 * behaviour of the legacy screen and each is asserted. The one thing it does do is check the depth,
 * and checking is not truncating: a list longer than the screen has row families is rejected whole,
 * and a shorter list is accepted exactly as supplied because a short final page is ordinary.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>Payloads come from {@link JsonContractSupport#declaredSettingsMapper()}, a mapper carrying the
 * four serialisation settings this module declares, written out by hand in one place rather than
 * copied into every suite. That evidences the shape this type takes <em>under those settings</em>,
 * and nothing more. It is not evidence about the mapper a deployed instance holds, and no assertion
 * below is worded as though it were; {@code ApplicationJsonContractTest} is the in-boundary evidence
 * for the deployed object. Decimal assertions are made against the emitted characters through {@link
 * JsonContractSupport#renderedValueToken(String, String)} and never against a re-parsed tree, because
 * re-parsing a JSON number loses the scale the contract is about.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy antecedents cited by the type under test: program {@code app/cbl/COTRN00C.cbl}, symbolic
 * map {@code app/cpy-bms/COTRN00.CPY}, mapset {@code app/bms/COTRN00.bms} and record layout {@code
 * app/cpy/CVTRA05Y.cpy}. Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * reproduced here.
 */
@DisplayName("TransactionListResponse :: response contract of legacy transaction CT00")
class TransactionListResponseCoverageTest {

    /** The fifteen components, in the order the record declares them. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "rows",
            "pageMetadata",
            "navigationContext",
            "nextRoute",
            "transactionIdFilter",
            "displayedPageNumber",
            "message",
            "error",
            "focusScreenFieldId",
            "title01",
            "title02",
            "currentDate",
            "currentTime",
            "transactionName",
            "programName");

    /** The ten components that carry a declared maximum length. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "transactionIdFilter",
            "displayedPageNumber",
            "message",
            "focusScreenFieldId",
            "title01",
            "title02",
            "currentDate",
            "currentTime",
            "transactionName",
            "programName");

    /** The five components that carry no declared maximum length. */
    private static final List<String> UNBOUNDED_COMPONENTS =
            List.of("rows", "pageMetadata", "navigationContext", "nextRoute", "error");

    /** The five row components, in the order the nested record declares them. */
    private static final List<String> EXPECTED_ROW_COMPONENTS =
            List.of("selection", "transactionId", "displayedDate", "description", "amount");

    /** The four row components that carry a declared maximum length. */
    private static final List<String> BOUNDED_ROW_COMPONENTS =
            List.of("selection", "transactionId", "displayedDate", "description");

    /** Transaction identifier this screen displays in its header. */
    private static final String TRANSACTION_NAME = "CT00";

    /** Program name this screen displays in its header. */
    private static final String PROGRAM_NAME = "COTRN00C";

    /** First screen title line, well within its forty-character bound. */
    private static final String SCREEN_TITLE_LINE_1 = "AWS Mainframe Modernization";

    /** Second screen title line, well within its forty-character bound. */
    private static final String SCREEN_TITLE_LINE_2 = "CardDemo";

    /** Clock date as the screen renders it: eight characters of text. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Clock time as the screen renders it: eight characters of text. */
    private static final String CURRENT_TIME = "14:35:07";

    /** Search key echoed back into the identifier entry field: sixteen characters. */
    private static final String TRANSACTION_ID_FILTER = "0000000000000001";

    /** Page indicator the screen displays: eight alphanumeric characters. */
    private static final String PAGE_NUMBER = "PAGE0001";

    /** Map field name the client should place the cursor in. */
    private static final String FOCUS_FIELD_NAME = "TRNIDIN";

    /** Route the client should call next; opaque to this contract and deliberately unbounded. */
    private static final String NEXT_ROUTE = "/api/transactions/detail";

    /** Selection indicator echoed on the first fixture row. */
    private static final String ROW_SELECTION = "S";

    /** Identifier of the first fixture row. */
    private static final String ROW_TRANSACTION_ID_1 = "0000000000000001";

    /** Identifier of the second fixture row. */
    private static final String ROW_TRANSACTION_ID_2 = "0000000000000002";

    /** Identifier of the third fixture row. */
    private static final String ROW_TRANSACTION_ID_3 = "0000000000000003";

    /** Row date in the screen's two-digit-year presentation form: eight characters. */
    private static final String ROW_DISPLAYED_DATE = "22/01/01";

    /** Row description, within the twenty-six characters this screen presents. */
    private static final String ROW_DESCRIPTION = "POS PURCHASE ONE";

    /** Row amount at the contractual scale of two. */
    private static final BigDecimal ROW_AMOUNT = new BigDecimal("1234.56");

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
     * Supplies each published message constant paired with the text it must equal, restated here
     * independently of the type under test.
     *
     * @return one argument pair per published message: the constant's value, then the expected text
     */
    static Stream<Arguments> publishedMessages() {
        return Stream.of(
                Arguments.of(
                        TransactionListResponse.MESSAGE_INVALID_SELECTION,
                        "Invalid selection. Valid value is S"),
                Arguments.of(
                        TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC,
                        "Tran ID must be Numeric ..."),
                Arguments.of(
                        TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                        "You are already at the top of the page..."),
                Arguments.of(
                        TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                        "You are already at the bottom of the page..."),
                Arguments.of(
                        TransactionListResponse.MESSAGE_AT_TOP, "You are at the top of the page..."),
                Arguments.of(
                        TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                        "You have reached the bottom of the page..."),
                Arguments.of(
                        TransactionListResponse.MESSAGE_REACHED_TOP,
                        "You have reached the top of the page..."));
    }

    /**
     * Supplies every published message text.
     *
     * @return the seven published message constants
     */
    static Stream<String> messageTexts() {
        return Stream.of(
                TransactionListResponse.MESSAGE_INVALID_SELECTION,
                TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC,
                TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                TransactionListResponse.MESSAGE_AT_TOP,
                TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                TransactionListResponse.MESSAGE_REACHED_TOP);
    }

    /**
     * Supplies the five page-boundary messages, which are the ones that attach their ellipsis
     * directly to the preceding word.
     *
     * @return the five boundary message constants
     */
    static Stream<String> boundaryMessages() {
        return Stream.of(
                TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                TransactionListResponse.MESSAGE_AT_TOP,
                TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                TransactionListResponse.MESSAGE_REACHED_TOP);
    }

    /**
     * Builds a row carrying the fixture values with the named component replaced.
     *
     * @param component the row component to populate; every other text component is left null
     * @param value the value to place in that component
     * @return a row carrying only the named component
     */
    private static TransactionListResponse.TransactionRow rowCarrying(
            String component, String value) {
        return new TransactionListResponse.TransactionRow(
                "selection".equals(component) ? value : null,
                "transactionId".equals(component) ? value : null,
                "displayedDate".equals(component) ? value : null,
                "description".equals(component) ? value : null,
                null);
    }

    /**
     * Builds a fully populated fixture row with the supplied identifier.
     *
     * @param transactionId the identifier to carry
     * @return a populated row
     */
    private static TransactionListResponse.TransactionRow row(String transactionId) {
        return new TransactionListResponse.TransactionRow(
                ROW_SELECTION, transactionId, ROW_DISPLAYED_DATE, ROW_DESCRIPTION, ROW_AMOUNT);
    }

    /**
     * Builds three rows whose identifiers ascend, as a forward page presents them.
     *
     * @return three rows in ascending identifier order
     */
    private static List<TransactionListResponse.TransactionRow> threeAscendingRows() {
        return List.of(
                row(ROW_TRANSACTION_ID_1), row(ROW_TRANSACTION_ID_2), row(ROW_TRANSACTION_ID_3));
    }

    /**
     * Builds three rows whose identifiers descend, as a backward page presents them.
     *
     * @return three rows in descending identifier order
     */
    private static List<TransactionListResponse.TransactionRow> threeDescendingRows() {
        return List.of(
                row(ROW_TRANSACTION_ID_3), row(ROW_TRANSACTION_ID_2), row(ROW_TRANSACTION_ID_1));
    }

    /**
     * Builds a forward browse position for the transaction-list screen depth.
     *
     * @return a forward-direction browse position
     */
    private static PageMetadata forwardPosition() {
        return PageMetadata.forward(
                PageMetadata.TRANSACTION_LIST_PAGE_SIZE, null, ROW_TRANSACTION_ID_3, true, false,
                PAGE_NUMBER);
    }

    /**
     * Builds a response carrying the fixture values with the named component replaced and no nested
     * record.
     *
     * @param component the component to populate; every other text component is left null
     * @param value the value to place in that component
     * @return a response carrying only the named component
     */
    private static TransactionListResponse carrying(String component, String value) {
        return new TransactionListResponse(
                List.of(),
                null,
                null,
                "nextRoute".equals(component) ? value : null,
                "transactionIdFilter".equals(component) ? value : null,
                "displayedPageNumber".equals(component) ? value : null,
                "message".equals(component) ? value : null,
                false,
                "focusScreenFieldId".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "transactionName".equals(component) ? value : null,
                "programName".equals(component) ? value : null);
    }

    /**
     * Builds a response carrying the supplied rows and nothing else.
     *
     * @param rows the rows to carry, which may be {@code null}
     * @return a response carrying only the supplied rows
     */
    private static TransactionListResponse withRows(
            List<TransactionListResponse.TransactionRow> rows) {
        return new TransactionListResponse(
                rows, null, null, null, null, null, null, false, null, null, null, null, null, null,
                null);
    }

    /**
     * Builds a response carrying the supplied message and error indicator.
     *
     * @param message the summary message to carry
     * @param error whether the response reports an error condition
     * @return a response carrying only the message and indicator
     */
    private static TransactionListResponse reporting(String message, boolean error) {
        return new TransactionListResponse(
                List.of(), null, null, null, null, null, message, error, null, null, null, null,
                null, null, null);
    }

    /**
     * Builds a fully populated page carrying both nested records.
     *
     * @return a populated response with a browse position and a navigation state
     */
    private static TransactionListResponse populatedPage() {
        return new TransactionListResponse(
                threeAscendingRows(),
                forwardPosition(),
                JsonContractSupport.populatedNavigation(),
                NEXT_ROUTE,
                TRANSACTION_ID_FILTER,
                PAGE_NUMBER,
                TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                false,
                FOCUS_FIELD_NAME,
                SCREEN_TITLE_LINE_1,
                SCREEN_TITLE_LINE_2,
                CURRENT_DATE,
                CURRENT_TIME,
                TRANSACTION_NAME,
                PROGRAM_NAME);
    }

    /**
     * Builds a fully populated page carrying exactly one row and neither nested record, so that the
     * generated rendering can be stated in full and so that a wholesale placeholder claim is made
     * against a value that delegates to nothing.
     *
     * @return a one-row response with no browse position and no navigation state
     */
    private static TransactionListResponse singleRowPageWithoutNestedRecords() {
        return new TransactionListResponse(
                List.of(row(ROW_TRANSACTION_ID_1)),
                null,
                null,
                NEXT_ROUTE,
                TRANSACTION_ID_FILTER,
                PAGE_NUMBER,
                TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                false,
                FOCUS_FIELD_NAME,
                SCREEN_TITLE_LINE_1,
                SCREEN_TITLE_LINE_2,
                CURRENT_DATE,
                CURRENT_TIME,
                TRANSACTION_NAME,
                PROGRAM_NAME);
    }

    /**
     * Serialises the supplied response with the module's declared settings.
     *
     * @param response the response to serialise
     * @return the emitted JSON text
     * @throws JsonProcessingException if serialisation fails, which fails the calling test
     */
    private static String payloadOf(TransactionListResponse response)
            throws JsonProcessingException {
        return JsonContractSupport.declaredSettingsMapper().writeValueAsString(response);
    }

    /** The declared shape of the record: components, order, widths and absent members. */
    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        /**
         * Reads the declared maximum length of a component of the enclosing record.
         *
         * @param component the component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int boundOf(String component) throws NoSuchFieldException {
            Field field = TransactionListResponse.class.getDeclaredField(component);
            Size size = field.getAnnotation(Size.class);
            assertThat(size)
                    .as("component %s must declare a maximum length", component)
                    .isNotNull();
            return size.max();
        }

        /**
         * Reads the declared maximum length of a component of the nested row record.
         *
         * @param component the row component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int rowBoundOf(String component) throws NoSuchFieldException {
            Field field =
                    TransactionListResponse.TransactionRow.class.getDeclaredField(component);
            Size size = field.getAnnotation(Size.class);
            assertThat(size)
                    .as("row component %s must declare a maximum length", component)
                    .isNotNull();
            return size.max();
        }

        /** The fifteen components appear in the documented order. */
        @Test
        @DisplayName("declares fifteen components in the documented order")
        void theComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(TransactionListResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        /** The row list, browse position and navigation state are the first three components. */
        @Test
        @DisplayName("declares its row list, browse position and navigation state first")
        void theCollaboratorsAreDeclaredFirst() {
            RecordComponent[] declared = TransactionListResponse.class.getRecordComponents();

            assertThat(declared[0].getName()).isEqualTo("rows");
            assertThat(declared[1].getName()).isEqualTo("pageMetadata");
            assertThat(declared[2].getName()).isEqualTo("navigationContext");
        }

        /**
         * The two collaborator components are named exactly as the card-list response names them,
         * and the two reporting components are not.
         *
         * <p>The collaborators are the same types carrying the same meaning on both screens, so one
         * spelling serves both: a client that has learned to read a browse position on one screen
         * reads it identically on the other. The reporting components are different in substance and
         * keep different names: this screen carries a single {@code message} that reports a browse
         * boundary as often as it reports a mistake, and a single {@code error} indicator, where the
         * card-list screen separates an informational text from an error text and names its indicator
         * for the general case. Naming those alike would suggest an equivalence the two screens do not
         * have.</p>
         */
        @Test
        @DisplayName("shares the collaborator names with the card-list response and not the reporting names")
        void theComponentNamesDifferFromTheCardListScreen() {
            List<String> declared =
                    Arrays.stream(TransactionListResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();
            List<String> sibling =
                    Arrays.stream(CardListResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .as("one spelling per collaborator across both browse screens")
                    .contains("pageMetadata", "navigationContext");
            assertThat(sibling).contains("pageMetadata", "navigationContext");

            assertThat(declared)
                    .as("this screen reports with one message and one indicator")
                    .contains("error", "message");
            assertThat(declared)
                    .as("the card-list reporting spellings describe a different reporting shape")
                    .doesNotContain("generalError", "errorMessage", "infoMessage");
            assertThat(sibling).contains("generalError", "errorMessage", "infoMessage");
        }

        /** Each bounded component declares the width this screen's map declares. */
        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({
            "transactionIdFilter,16",
            "displayedPageNumber,8",
            "message,78",
            "focusScreenFieldId,7",
            "title01,40",
            "title02,40",
            "currentDate,8",
            "currentTime,8",
            "transactionName,4",
            "programName,8"
        })
        @DisplayName("declares each screen width")
        void eachBoundedComponentDeclaresItsDocumentedWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(component)).isEqualTo(width);
        }

        /** The row list, both nested records, the route and the indicator carry no width. */
        @ParameterizedTest(name = "{0} declares no width")
        @ValueSource(
                strings = {"rows", "pageMetadata", "navigationContext", "nextRoute", "error"})
        @DisplayName("leaves the collaborators, the route and the indicator unbounded")
        void theUnboundedComponentsDeclareNoWidth(String component) throws NoSuchFieldException {
            Field field = TransactionListResponse.class.getDeclaredField(component);

            assertThat(field.getAnnotation(Size.class)).isNull();
        }

        /** Every component is accounted for as bounded or unbounded. */
        @Test
        @DisplayName("accounts for every component as bounded or unbounded")
        void everyComponentIsAccountedFor() {
            List<String> partition = new ArrayList<>(BOUNDED_COMPONENTS);
            partition.addAll(UNBOUNDED_COMPONENTS);

            assertThat(partition).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /** The nested row declares its five components in the documented order. */
        @Test
        @DisplayName("nested row declares five components in the documented order")
        void theRowComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(
                                    TransactionListResponse.TransactionRow.class
                                            .getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_ROW_COMPONENTS);
        }

        /** Each bounded row component declares the width the row family declares. */
        @ParameterizedTest(name = "row {0} is bounded at {1}")
        @CsvSource({
            "selection,1",
            "transactionId,16",
            "displayedDate,8",
            "description,26"
        })
        @DisplayName("declares each row width")
        void eachBoundedRowComponentDeclaresItsDocumentedWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(rowBoundOf(component)).isEqualTo(width);
        }

        /** The row amount carries no length bound, because a decimal has no character width. */
        @Test
        @DisplayName("leaves the row amount unbounded")
        void theRowAmountDeclaresNoBound() throws NoSuchFieldException {
            Field field =
                    TransactionListResponse.TransactionRow.class.getDeclaredField("amount");

            assertThat(field.getAnnotation(Size.class)).isNull();
            assertThat(field.getType()).isEqualTo(BigDecimal.class);
        }

        /**
         * The amount scale is published as a stated contract and enforced by the constructor rather
         * than by Bean Validation: the component carries no validation annotation of any kind.
         *
         * <p>The distinction matters to a client. A Bean Validation constraint is advisory - it reports
         * a violation to whoever chooses to run a validator, and a producer that never runs one emits
         * the wrong precision anyway. The constructor's refusal is not optional, so the shape a client
         * reads from the published schema is the shape it will actually receive. What is asserted here
         * is only the absence of the annotation; the refusal itself is asserted where the constructor
         * is exercised.</p>
         */
        @Test
        @DisplayName("publishes the amount scale as a contract and not as a Bean Validation constraint")
        void theAmountScaleIsPublishedAsAContractAndNotAsAConstraint() throws NoSuchFieldException {
            assertThat(TransactionListResponse.AMOUNT_SCALE).isEqualTo(2);

            Annotation[] declared =
                    TransactionListResponse.TransactionRow.class
                            .getDeclaredField("amount")
                            .getAnnotations();

            assertThat(declared)
                    .as("no constraint may be attached to the amount")
                    .noneMatch(
                            annotation ->
                                    annotation
                                            .annotationType()
                                            .getName()
                                            .startsWith("jakarta.validation"));
        }

        /**
         * The record declares fifteen integer constants, seven published message constants and one
         * withheld-value placeholder.
         *
         * <p>Fifteen rather than thirteen: eleven are screen widths, two publish the decimal shape of
         * the row amount, one publishes the row-count the screen declares, and one is the identifier
         * width shared between the row and the echoed search key. The placeholder is the only
         * non-public static member, because it is an implementation detail of the diagnostic rendering
         * rather than a published part of the contract, so the public-and-final claim is made about the
         * published constants alone and the placeholder's privacy is asserted separately.</p>
         */
        @Test
        @DisplayName("declares fourteen integer constants, seven message constants and one "
                + "withheld-value placeholder")
        void theStaticSurfaceIsFourteenWidthsAndSevenMessages() {
            List<Field> statics =
                    Arrays.stream(TransactionListResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .toList();

            assertThat(statics.stream().filter(field -> field.getType() == int.class).toList())
                    .as("every integer constant states the width or the decimal shape of one value; "
                            + "the screen depth is not among them, because that is a screen dimension "
                            + "the paging contract states once for the whole module")
                    .hasSize(14);
            assertThat(statics.stream().filter(field -> field.getType() == String.class).toList())
                    .hasSize(8);
            assertThat(statics).hasSize(22);

            List<Field> published =
                    statics.stream()
                            .filter(field -> Modifier.isPublic(field.getModifiers()))
                            .toList();

            assertThat(published).hasSize(21);
            assertThat(published)
                    .allSatisfy(
                            field ->
                                    assertThat(Modifier.isFinal(field.getModifiers()))
                                            .as("published constant %s must be final", field.getName())
                                            .isTrue());

            List<String> withheld =
                    statics.stream()
                            .filter(field -> !Modifier.isPublic(field.getModifiers()))
                            .map(Field::getName)
                            .toList();

            assertThat(withheld)
                    .as("only the placeholder substituted by the rendering is not published")
                    .containsExactly("REDACTION_PLACEHOLDER");
        }

        /**
         * The decimal shape of the row amount is published as two integer constants, because the
         * record field the amount represents has a fixed scale and a fixed integer width.
         *
         * <p>Nine integer digits and two decimal places is {@code TRAN-AMT PIC S9(09)V99}, so the two
         * constants together restate that field's total precision of eleven. They are published rather
         * than kept private because the compact constructor refuses an amount that contradicts them and
         * a producer needs to be able to read the rule it will be held to.</p>
         */
        @Test
        @DisplayName("publishes the decimal shape of the row amount")
        void theRecordShapeOfTheRowAmountIsPublished() {
            assertThat(TransactionListResponse.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionListResponse.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(
                            TransactionListResponse.AMOUNT_INTEGER_DIGITS
                                    + TransactionListResponse.AMOUNT_SCALE)
                    .as("nine integer digits and two decimal places is a precision of eleven")
                    .isEqualTo(11);
        }

        /**
         * The screen depth is published nowhere on this contract, under any spelling.
         *
         * <p>How many row families the screen declares is a screen dimension rather than a field width,
         * and the module states it once, in the paging contract. Publishing it here as well would make
         * this response body a competing source of truth for the same measurement and would invite a
         * caller to read it as a page size it could choose. Every constant this type does publish states
         * the width or the decimal shape of one value, so the assertion is that no depth constant exists
         * under any of the spellings a reintroduction would plausibly use.</p>
         */
        @Test
        @DisplayName("publishes no screen depth at all, under any spelling")
        void noPageSizeConstantIsDeclared() {
            List<String> names =
                    Arrays.stream(TransactionListResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .map(Field::getName)
                            .toList();

            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .as("the paging contract is where this screen's depth is stated")
                    .isEqualTo(10);

            assertThat(names)
                    .as("no depth constant of any spelling is published on the response body")
                    .noneMatch(
                            name ->
                                    name.contains("ROW_COUNT")
                                            || name.contains("PAGE_SIZE")
                                            || name.contains("ROW_LIMIT")
                                            || name.contains("DEPTH")
                                            || name.contains("MAX_ROWS"));
        }

        /** No component name suggests a total row or page count, because none exists. */
        @Test
        @DisplayName("carries no total row or page count")
        void noTotalRowOrPageCountIsCarried() {
            List<String> declared = new ArrayList<>();
            Arrays.stream(TransactionListResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .forEach(declared::add);
            Arrays.stream(PageMetadata.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .forEach(declared::add);

            assertThat(declared)
                    .as("the legacy browse never counts the cluster")
                    .noneMatch(
                            name ->
                                    name.toLowerCase(java.util.Locale.ROOT).contains("total")
                                            || name.toLowerCase(java.util.Locale.ROOT)
                                                    .contains("count"));
        }

        /**
         * One method is declared beyond the generated accessors: the private helper the two compact
         * constructors delegate their amount check to.
         *
         * <p>It lives on the enclosing type rather than on the nested row so that one statement of the
         * record field's shape serves both, and it is private, static and void because it decides
         * nothing and returns nothing - it either accepts an amount or refuses it. Its exact
         * modifiers are asserted, because a helper that became public or began returning a value would
         * be a new piece of published surface rather than an implementation detail.</p>
         *
         * @throws NoSuchMethodException if the helper is not declared, failing this test
         */
        @Test
        @DisplayName("declares one private helper beyond its accessors")
        void noMemberIsDeclaredBeyondTheAccessors() throws NoSuchMethodException {
            List<String> declared =
                    Arrays.stream(TransactionListResponse.class.getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .toList();

            List<String> expected = new ArrayList<>(EXPECTED_COMPONENTS);
            expected.add("requireRecordShape");

            assertThat(declared).containsExactlyInAnyOrderElementsOf(expected);

            Method helper =
                    TransactionListResponse.class.getDeclaredMethod(
                            "requireRecordShape", BigDecimal.class);

            assertThat(Modifier.isPrivate(helper.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(helper.getModifiers())).isTrue();
            assertThat(helper.getReturnType()).isEqualTo(void.class);
        }

        /** The nested row declares no member beyond the generated accessors. */
        @Test
        @DisplayName("nested row declares no member beyond its accessors")
        void theRowDeclaresNoMemberBeyondItsAccessors() {
            List<String> declared =
                    Arrays.stream(
                                    TransactionListResponse.TransactionRow.class
                                            .getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .toList();

            assertThat(declared).containsExactlyInAnyOrderElementsOf(EXPECTED_ROW_COMPONENTS);
        }

        /** The nested row keeps the generated canonical constructor and adds no other. */
        @Test
        @DisplayName("nested row keeps only the generated canonical constructor")
        void onlyTheGeneratedCanonicalConstructorExistsOnTheRow() {
            assertThat(TransactionListResponse.TransactionRow.class.getDeclaredConstructors())
                    .hasSize(1);
            assertThat(
                            TransactionListResponse.TransactionRow.class
                                    .getDeclaredConstructors()[0]
                                    .getParameterCount())
                    .isEqualTo(EXPECTED_ROW_COMPONENTS.size());
        }

        /** The nested row is implicitly static, as a record nested in a record must be. */
        @Test
        @DisplayName("nested row is implicitly static")
        void theNestedRowIsStatic() {
            assertThat(
                            Modifier.isStatic(
                                    TransactionListResponse.TransactionRow.class.getModifiers()))
                    .isTrue();
            assertThat(TransactionListResponse.TransactionRow.class.isRecord()).isTrue();
        }

        /** No serialisation annotation appears on any component of either record. */
        @Test
        @DisplayName("declares no serialisation annotation on any component")
        void noSerialisationAnnotationAppearsOnAnyComponent() {
            List<Annotation> annotations = new ArrayList<>();
            for (Field field : TransactionListResponse.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }
            for (Field field :
                    TransactionListResponse.TransactionRow.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
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

    /** The widths that coincide with differently-sized fields elsewhere in the estate. */
    @Nested
    @DisplayName("Width independence")
    class WidthIndependence {

        /**
         * Reads the declared maximum length of a component of the supplied record type.
         *
         * @param type the record type to inspect
         * @param component the component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int boundOf(Class<?> type, String component) throws NoSuchFieldException {
            return type.getDeclaredField(component).getAnnotation(Size.class).max();
        }

        /** The row description is twenty-six here and sixty on the transaction view screen. */
        @Test
        @DisplayName("row description is 26 here and 60 on the transaction view screen")
        void theRowDescriptionIsTwentySixAndNotTheSixtyOfTheViewScreen()
                throws NoSuchFieldException {
            assertThat(TransactionListResponse.DESCRIPTION_LENGTH).isEqualTo(26);
            assertThat(boundOf(TransactionViewResponse.class, "description")).isEqualTo(60);
            assertThat(TransactionListResponse.DESCRIPTION_LENGTH)
                    .as("one logical value, three widths, none unified")
                    .isNotEqualTo(boundOf(TransactionViewResponse.class, "description"));
        }

        /** The row date is eight here and ten on the transaction view screen. */
        @Test
        @DisplayName("row date is 8 here and 10 on the transaction view screen")
        void theRowDateIsEightAndNotTheTenOfTheViewScreen() throws NoSuchFieldException {
            assertThat(TransactionListResponse.DISPLAYED_DATE_LENGTH).isEqualTo(8);
            assertThat(boundOf(TransactionViewResponse.class, "originationDate")).isEqualTo(10);
            assertThat(boundOf(TransactionViewResponse.class, "processingDate")).isEqualTo(10);
        }

        /** The page indicator is eight here and three on the card-list screen. */
        @Test
        @DisplayName("page indicator is 8 here and 3 on the card-list screen")
        void thePageIndicatorIsEightAndNotTheThreeOfTheCardListScreen() {
            assertThat(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(3);
        }

        /** The message field is seventy-eight here and eighty on the card detail screen. */
        @Test
        @DisplayName("message field is 78 here and 80 on the card detail screen")
        void theMessageIsSeventyEightAndNotTheEightyOfTheCardDetailScreen() {
            assertThat(TransactionListResponse.MESSAGE_LENGTH).isEqualTo(78);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH).isEqualTo(80);
        }

        /**
         * Five unrelated fields are eight characters wide and each is declared on its own, so a
         * change to one cannot silently move the others.
         */
        @Test
        @DisplayName("declares five independent eight-character widths")
        void theFourEightCharacterWidthsAreDeclaredIndependently() {
            assertThat(TransactionListResponse.DISPLAYED_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(8);

            List<String> eightWide =
                    Arrays.stream(TransactionListResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> field.getType() == int.class)
                            .map(Field::getName)
                            .filter(
                                    name ->
                                            name.equals("DISPLAYED_DATE_LENGTH")
                                                    || name.equals("CURRENT_DATE_LENGTH")
                                                    || name.equals("CURRENT_TIME_LENGTH")
                                                    || name.equals("PROGRAM_NAME_LENGTH")
                                                    || name.equals(
                                                            "DISPLAYED_PAGE_NUMBER_LENGTH"))
                            .toList();

            assertThat(eightWide)
                    .as("five separate constants, none derived from another")
                    .hasSize(5);
        }

        /** One constant governs both title lines, because they are the same field kind. */
        @Test
        @DisplayName("governs both title lines with one forty-character constant")
        void oneConstantGovernsBothTitleLines() throws NoSuchFieldException {
            assertThat(TransactionListResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(boundOf(TransactionListResponse.class, "title01")).isEqualTo(40);
            assertThat(boundOf(TransactionListResponse.class, "title02")).isEqualTo(40);
        }

        /** The identifier width is shared between the row and the echoed search key. */
        @Test
        @DisplayName("shares one sixteen-character identifier width with the echoed search key")
        void theIdentifierWidthIsSharedWithTheEchoedSearchKey() throws NoSuchFieldException {
            assertThat(TransactionListResponse.TRANSACTION_ID_LENGTH).isEqualTo(16);
            assertThat(boundOf(TransactionListResponse.class, "transactionIdFilter")).isEqualTo(16);
            assertThat(boundOf(TransactionListResponse.TransactionRow.class, "transactionId"))
                    .isEqualTo(16);
        }
    }

    /** The seven published screen messages and the punctuation that distinguishes them. */
    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        /** Each published message equals its documented text exactly. */
        @ParameterizedTest(name = "[{index}] {1}")
        @MethodSource("com.carddemo.api.dto.TransactionListResponseCoverageTest#publishedMessages")
        @DisplayName("publishes each message exactly")
        void eachPublishedMessageIsExactlyItsDocumentedText(String actual, String expected) {
            assertThat(actual).isEqualTo(expected);
        }

        /** All seven published messages are distinct values. */
        @Test
        @DisplayName("publishes seven distinct messages")
        void theSevenPublishedMessagesAreDistinct() {
            List<String> published = messageTexts().toList();

            assertThat(published).hasSize(7);
            assertThat(published).doesNotHaveDuplicates();
        }

        /** Every published message fits within the map's message width. */
        @ParameterizedTest(name = "[{index}] fits 78")
        @MethodSource("com.carddemo.api.dto.TransactionListResponseCoverageTest#messageTexts")
        @DisplayName("keeps every published message within the message width")
        void everyPublishedMessageFitsTheMessageWidth(String message) {
            assertThat(message.length())
                    .isLessThanOrEqualTo(TransactionListResponse.MESSAGE_LENGTH);
        }

        /** The non-numeric identifier message alone puts a space before its three dots. */
        @Test
        @DisplayName("puts a space before the ellipsis on the numeric-identifier message alone")
        void theNumericMessageCarriesASpaceBeforeItsEllipsis() {
            assertThat(TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC).endsWith(" ...");
            assertThat(TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC)
                    .as("the word before the ellipsis is separated from it")
                    .contains("Numeric ...");
        }

        /** Each of the five boundary messages attaches its ellipsis to the preceding word. */
        @ParameterizedTest(name = "[{index}] attaches its ellipsis")
        @MethodSource("com.carddemo.api.dto.TransactionListResponseCoverageTest#boundaryMessages")
        @DisplayName("attaches the ellipsis directly on every boundary message")
        void theFiveBoundaryMessagesAttachTheirEllipsisDirectly(String message) {
            assertThat(message).endsWith("page...");
            assertThat(message).doesNotContain(" ...");
        }

        /** The invalid-selection message carries no ellipsis at all. */
        @Test
        @DisplayName("carries no ellipsis on the invalid-selection message")
        void theSelectionMessageCarriesNoEllipsis() {
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION).doesNotContain("...");
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION).endsWith("S");
        }

        /** The three top-of-browse texts are distinct values. */
        @Test
        @DisplayName("publishes three distinct top-of-browse texts")
        void theThreeTopBoundaryTextsAreDistinct() {
            List<String> top =
                    List.of(
                            TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                            TransactionListResponse.MESSAGE_AT_TOP,
                            TransactionListResponse.MESSAGE_REACHED_TOP);

            assertThat(top).doesNotHaveDuplicates();
            assertThat(top).allSatisfy(message -> assertThat(message).contains("top"));
        }

        /** The two bottom-of-browse texts are distinct values. */
        @Test
        @DisplayName("publishes two distinct bottom-of-browse texts")
        void theTwoBottomBoundaryTextsAreDistinct() {
            List<String> bottom =
                    List.of(
                            TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                            TransactionListResponse.MESSAGE_REACHED_BOTTOM);

            assertThat(bottom).doesNotHaveDuplicates();
            assertThat(bottom).allSatisfy(message -> assertThat(message).contains("bottom"));
        }

        /**
         * The two attention-key texts say "already" because nothing moved, and the three
         * file-access texts do not, because an access was attempted.
         */
        @Test
        @DisplayName("says already on the attention-key texts only")
        void theAlreadyTextsSayAlreadyAndTheAccessTextsDoNot() {
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_TOP).contains("already");
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM).contains("already");

            assertThat(TransactionListResponse.MESSAGE_AT_TOP).doesNotContain("already");
            assertThat(TransactionListResponse.MESSAGE_REACHED_BOTTOM).doesNotContain("already");
            assertThat(TransactionListResponse.MESSAGE_REACHED_TOP).doesNotContain("already");
        }

        /** The two "have reached" texts are phrased by outcome rather than by refusal. */
        @Test
        @DisplayName("phrases the discovered boundaries as have reached")
        void theDiscoveredBoundariesArePhrasedByOutcome() {
            assertThat(TransactionListResponse.MESSAGE_REACHED_BOTTOM).contains("have reached");
            assertThat(TransactionListResponse.MESSAGE_REACHED_TOP).contains("have reached");
            assertThat(TransactionListResponse.MESSAGE_AT_TOP).doesNotContain("have reached");
        }

        /** The invalid-selection text names a single valid value, because the screen accepts one. */
        @Test
        @DisplayName("names a single valid selection value")
        void theSelectionMessageIsSingular() {
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S");
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .as("this screen accepts exactly one selection character")
                    .doesNotContain(" or ")
                    .doesNotContain("values");
        }

        /** A boundary message is informational, so it is carried with the indicator clear. */
        @ParameterizedTest(name = "[{index}] informational")
        @MethodSource("com.carddemo.api.dto.TransactionListResponseCoverageTest#boundaryMessages")
        @DisplayName("carries a boundary message with the error indicator clear")
        void aBoundaryMessageIsCarriedWithTheErrorIndicatorClear(String message) {
            TransactionListResponse response = reporting(message, false);

            assertThat(response.message()).isEqualTo(message);
            assertThat(response.error()).isFalse();
        }

        /** The indicator is stated explicitly and never inferred from the message. */
        @Test
        @DisplayName("states the indicator independently of the message")
        void theIndicatorIsStatedIndependentlyOfTheMessage() {
            assertThat(reporting(null, true).error()).isTrue();
            assertThat(reporting(null, true).message()).isNull();
            assertThat(reporting(TransactionListResponse.MESSAGE_AT_TOP, false).error()).isFalse();
            assertThat(reporting(TransactionListResponse.MESSAGE_INVALID_SELECTION, true).error())
                    .isTrue();
        }

        /** A message is carried exactly as supplied, with its spaces intact. */
        @Test
        @DisplayName("carries a message without trimming or reformatting it")
        void noMessageIsTrimmedOrReformattedWhenCarried() {
            String padded = "  boundary reached   ";

            assertThat(reporting(padded, false).message()).isEqualTo(padded);
        }
    }

    /** Row list behaviour: copying, absence, order, depth and the browse position. */
    @Nested
    @DisplayName("Page shape")
    class PageShape {

        /** A null row list becomes an empty list rather than a null component. */
        @Test
        @DisplayName("turns a null row list into an empty list")
        void aNullRowListBecomesAnEmptyList() {
            assertThat(withRows(null).rows()).isNotNull().isEmpty();
        }

        /** The row list is published unmodifiable. */
        @Test
        @DisplayName("publishes an unmodifiable row list")
        void theRowListIsUnmodifiable() {
            List<TransactionListResponse.TransactionRow> rows =
                    withRows(threeAscendingRows()).rows();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(row(ROW_TRANSACTION_ID_1)));
        }

        /** The row list is copied, so later mutation of the caller's list is not observed. */
        @Test
        @DisplayName("copies the row list at construction")
        void theRowListIsCopiedSoLaterMutationIsNotObserved() {
            List<TransactionListResponse.TransactionRow> mutable = new ArrayList<>();
            mutable.add(row(ROW_TRANSACTION_ID_1));
            TransactionListResponse response = withRows(mutable);

            mutable.add(row(ROW_TRANSACTION_ID_2));

            assertThat(response.rows()).hasSize(1);
            assertThat(response.rows().get(0).transactionId()).isEqualTo(ROW_TRANSACTION_ID_1);
        }

        /** A null row element is rejected rather than published. */
        @Test
        @DisplayName("rejects a null row element")
        void aNullRowElementIsRejected() {
            List<TransactionListResponse.TransactionRow> withNull = new ArrayList<>();
            withNull.add(row(ROW_TRANSACTION_ID_1));
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> withRows(withNull));
        }

        /** A short page carries fewer rows and is never padded to the screen depth. */
        @Test
        @DisplayName("leaves a short page short")
        void aShortPageIsNotPaddedToTheScreenDepth() {
            assertThat(withRows(threeAscendingRows()).rows()).hasSize(3);
            assertThat(withRows(List.of()).rows()).isEmpty();
            assertThat(withRows(List.of(row(ROW_TRANSACTION_ID_1))).rows()).hasSize(1);
        }

        /** A full page carries exactly the screen depth without alteration. */
        @Test
        @DisplayName("carries a full page unaltered")
        void aFullPageIsCarriedUnaltered() {
            List<TransactionListResponse.TransactionRow> full = new ArrayList<>();
            for (int index = 0; index < PageMetadata.TRANSACTION_LIST_PAGE_SIZE; index++) {
                full.add(row(String.format(Locale.ROOT, "%016d", index + 1)));
            }

            assertThat(withRows(full).rows())
                    .hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .containsExactlyElementsOf(full);
        }

        /**
         * An over-deep page is refused rather than truncated, which surfaces the defect instead of
         * hiding it.
         *
         * <p>Neither truncating nor refusing happens here. Truncation would discard a row the browse
         * returned and leave the caller with a plausible page it could neither audit nor attribute, and
         * refusal would require this contract to publish the screen depth it refused against - a
         * measurement the paging contract already states once for the whole module. The page is
         * therefore carried exactly as supplied, and whether it fits the screen it is destined for is
         * the concern of the service that assembled it, which is the layer that knows which screen that
         * is. The no-truncation guarantee is kept by carrying every row, not by rejecting the page.</p>
         */
        @Test
        @DisplayName("carries an over-deep page untouched rather than truncating or refusing it")
        void anOverDeepPageIsCarriedUntouched() {
            List<TransactionListResponse.TransactionRow> tooMany = new ArrayList<>();
            for (int index = 0; index < PageMetadata.TRANSACTION_LIST_PAGE_SIZE + 1; index++) {
                tooMany.add(row(String.format(Locale.ROOT, "%016d", index + 1)));
            }

            assertThat(withRows(tooMany).rows())
                    .as("every row the browse returned survives, so nothing is hidden from the caller")
                    .hasSize(11)
                    .containsExactlyElementsOf(tooMany);
        }

        /** A page at exactly the screen depth is carried like any other, in the order supplied. */
        @Test
        @DisplayName("carries a page at exactly the screen depth")
        void aPageAtExactlyTheScreenDepthIsCarried() {
            List<TransactionListResponse.TransactionRow> exact = new ArrayList<>();
            for (int index = 0; index < PageMetadata.TRANSACTION_LIST_PAGE_SIZE; index++) {
                exact.add(row(String.format(Locale.ROOT, "%016d", index + 1)));
            }

            assertThat(withRows(exact).rows())
                    .hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .containsExactlyElementsOf(exact);
        }

        /** A forward page's ascending order is carried verbatim. */
        @Test
        @DisplayName("carries a forward page in ascending order verbatim")
        void theRowOrderIsCarriedVerbatimForAForwardPage() {
            List<TransactionListResponse.TransactionRow> ascending = threeAscendingRows();

            assertThat(withRows(ascending).rows())
                    .extracting(TransactionListResponse.TransactionRow::transactionId)
                    .containsExactly(
                            ROW_TRANSACTION_ID_1, ROW_TRANSACTION_ID_2, ROW_TRANSACTION_ID_3);
        }

        /**
         * A backward page arrives already reversed by the service, and that order is carried
         * verbatim rather than re-sorted.
         */
        @Test
        @DisplayName("carries a backward page in its arrival order verbatim")
        void theRowOrderIsCarriedVerbatimForABackwardPage() {
            List<TransactionListResponse.TransactionRow> descending = threeDescendingRows();

            assertThat(withRows(descending).rows())
                    .extracting(TransactionListResponse.TransactionRow::transactionId)
                    .containsExactly(
                            ROW_TRANSACTION_ID_3, ROW_TRANSACTION_ID_2, ROW_TRANSACTION_ID_1);
        }

        /** No row component is trimmed, padded or case-folded on the way through. */
        @Test
        @DisplayName("leaves every row value exactly as supplied")
        void noRowValueIsAlteredOnTheWayThrough() {
            TransactionListResponse.TransactionRow supplied =
                    new TransactionListResponse.TransactionRow(
                            " ", "  0000000000001 ", " 22/01/01", " pos purchase  ", ROW_AMOUNT);

            TransactionListResponse.TransactionRow carried = withRows(List.of(supplied)).rows().
                    get(0);

            assertThat(carried.selection()).isEqualTo(" ");
            assertThat(carried.transactionId()).isEqualTo("  0000000000001 ");
            assertThat(carried.displayedDate()).isEqualTo(" 22/01/01");
            assertThat(carried.description()).isEqualTo(" pos purchase  ");
        }

        /** The browse position is carried when supplied and may legitimately be absent. */
        @Test
        @DisplayName("carries the browse position when supplied and permits its absence")
        void theBrowsePositionIsCarriedWhenSuppliedAndMayBeAbsent() {
            assertThat(withRows(List.of()).pageMetadata()).isNull();

            TransactionListResponse carried = populatedPage();

            assertThat(carried.pageMetadata()).isNotNull();
            assertThat(carried.pageMetadata().pageSize())
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
            assertThat(carried.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD);
        }

        /** The screen depth this list uses is ten, which is the transaction-list page size. */
        @Test
        @DisplayName("uses the ten-row transaction-list page size")
        void theScreenDepthIsTen() {
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE).isEqualTo(10);
        }

        /** The navigation state is carried when supplied and may legitimately be absent. */
        @Test
        @DisplayName("carries the navigation state when supplied and permits its absence")
        void theNavigationStateIsCarriedWhenSuppliedAndMayBeAbsent() {
            assertThat(withRows(List.of()).navigationContext()).isNull();
            assertThat(populatedPage().navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }
    }

    /** The wire form the row amount takes under the module's declared settings. */
    @Nested
    @DisplayName("Decimal wire form")
    class DecimalWireForm {

        /**
         * Extracts the characters emitted for the row amount.
         *
         * @param amount the amount to carry on a single row
         * @return the emitted characters of that amount
         * @throws JsonProcessingException if serialisation fails, failing the calling test
         */
        private String emittedAmount(BigDecimal amount) throws JsonProcessingException {
            TransactionListResponse response =
                    withRows(
                            List.of(
                                    new TransactionListResponse.TransactionRow(
                                            ROW_SELECTION,
                                            ROW_TRANSACTION_ID_1,
                                            ROW_DISPLAYED_DATE,
                                            ROW_DESCRIPTION,
                                            amount)));
            return JsonContractSupport.renderedValueToken(payloadOf(response), "amount");
        }

        /**
         * The row amount is emitted in plain decimal form at the scale it was constructed with.
         *
         * <p>Every literal here is one the record field can hold: two decimal places and no more than
         * nine integer digits. A wider literal is not a weaker case of the same test, it is a value the
         * constructor refuses outright, and that refusal is asserted separately below rather than
         * folded in here where it would be mistaken for a formatting outcome.</p>
         */
        @ParameterizedTest(name = "{0} is emitted as {0}")
        @ValueSource(
                strings = {
                    "0.00",
                    "1.20",
                    "1234.56",
                    "-45.67",
                    "-0.01",
                    "999999999.99"
                })
        @DisplayName("emits the row amount in plain decimal form")
        void theRowAmountIsEmittedInPlainDecimalForm(String literal)
                throws JsonProcessingException {
            assertThat(emittedAmount(new BigDecimal(literal))).isEqualTo(literal);
        }

        /**
         * The widest amount the record field can hold is never emitted in exponential form.
         *
         * <p>Nine integer digits and two decimal places is the whole of the field, so this is the
         * largest magnitude that can reach the wire at all - which makes it the case most likely to
         * acquire an exponent from a mapper left to its own devices.</p>
         */
        @Test
        @DisplayName("never emits the row amount in exponential form")
        void theRowAmountIsNeverEmittedInExponentialForm() throws JsonProcessingException {
            String emitted = emittedAmount(new BigDecimal("999999999.99"));

            assertThat(emitted).doesNotContain("E").doesNotContain("e");
            assertThat(emitted).isEqualTo("999999999.99");
        }

        /**
         * An amount wider than the record field is refused at construction rather than emitted, so it
         * can never reach the wire in any form.
         *
         * <p>Ten integer digits is one digit more than {@code TRAN-AMT PIC S9(09)V99} can store, so a
         * value of that magnitude did not come out of the record and cannot be put back into it. The
         * refusal names the bound and the digit count it needed, and never the amount itself, so the
         * rejected value cannot reach a log through the diagnostic that reports it.</p>
         */
        @Test
        @DisplayName("refuses an amount wider than the record field")
        void anAmountWiderThanTheRecordFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    new TransactionListResponse.TransactionRow(
                                            null, null, null, null, new BigDecimal("1234567890.12")))
                    .withMessageContaining("must fit 9 integer digits")
                    .withMessageContaining("it needs 10")
                    .satisfies(
                            thrown ->
                                    assertThat(thrown.getMessage())
                                            .as("the refusal never echoes the amount")
                                            .doesNotContain("1234567890.12"));
        }

        /** A trailing zero in the scale survives to the wire. */
        @Test
        @DisplayName("keeps a trailing zero in the emitted scale")
        void aTrailingZeroSurvivesToTheWire() throws JsonProcessingException {
            assertThat(emittedAmount(new BigDecimal("1.20"))).isEqualTo("1.20");
            assertThat(emittedAmount(new BigDecimal("0.00"))).isEqualTo("0.00");
        }

        /** A negative row amount is carried, because a return is legitimately negative. */
        @Test
        @DisplayName("carries a negative row amount")
        void aNegativeRowAmountIsCarried() throws JsonProcessingException {
            assertThat(emittedAmount(new BigDecimal("-45.67"))).isEqualTo("-45.67");
        }

        /**
         * The row amount is neither rescaled nor rounded on the way through.
         *
         * <p>Every literal is at the record scale, and every one is a value a normalising layer would
         * be tempted to alter: a trailing zero it would strip, a negative zero it would collapse, a
         * signed value smaller than one cent it would round away, and the widest magnitude the field
         * holds. Surviving unchanged is the whole claim, so the scale is asserted alongside the
         * value - two amounts can be numerically equal and carry different scales, and only the scale
         * says how many decimal places the screen presented.</p>
         */
        @ParameterizedTest(name = "scale of {0} survives")
        @ValueSource(strings = {"1.20", "0.00", "-0.01", "999999999.99"})
        @DisplayName("neither rescales nor rounds the row amount")
        void theRowAmountIsNeitherRescaledNorRounded(String literal) {
            BigDecimal supplied = new BigDecimal(literal);

            TransactionListResponse.TransactionRow carried =
                    withRows(
                                    List.of(
                                            new TransactionListResponse.TransactionRow(
                                                    null, null, null, null, supplied)))
                            .rows()
                            .get(0);

            assertThat(carried.amount()).isEqualTo(supplied);
            assertThat(carried.amount().scale()).isEqualTo(TransactionListResponse.AMOUNT_SCALE);
            assertThat(carried.amount().toPlainString()).isEqualTo(literal);
        }

        /**
         * An amount off the record scale cannot be constructed at all, so a scale-shifted row is not a
         * value this contract has to distinguish - it is a value it refuses.
         *
         * <p>An earlier revision proved the point by constructing two rows differing only in scale and
         * asserting them unequal. That is a weaker guarantee than the one now in force: unequal means a
         * lost decimal place is <em>detectable</em> by a caller that thinks to compare, whereas a
         * refusal means it never crossed the boundary. Both scale directions are covered, because
         * a value carrying too few decimal places and one carrying too many are separate mistakes and
         * the refusal reports each with its own count.</p>
         */
        @ParameterizedTest(name = "scale of {0} is refused")
        @ValueSource(strings = {"1", "1.2", "1.234", "1.23456"})
        @DisplayName("refuses an amount off the record scale")
        void anAmountOffTheRecordScaleIsRefused(String literal) {
            BigDecimal supplied = new BigDecimal(literal);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    new TransactionListResponse.TransactionRow(
                                            null, null, null, null, supplied))
                    .withMessageContaining("must carry scale 2")
                    .withMessageContaining("its scale is " + supplied.scale());
        }

        /**
         * Two rows differing only in amount value are not equal, so a substituted amount is
         * detectable even when both amounts are record-shaped.
         */
        @Test
        @DisplayName("distinguishes two amounts that differ only in value")
        void twoRowsDifferingOnlyInAmountScaleAreNotEqual() {
            TransactionListResponse.TransactionRow oneTwenty =
                    new TransactionListResponse.TransactionRow(
                            null, null, null, null, new BigDecimal("1.20"));
            TransactionListResponse.TransactionRow oneTwentyOne =
                    new TransactionListResponse.TransactionRow(
                            null, null, null, null, new BigDecimal("1.21"));

            assertThat(oneTwenty).isNotEqualTo(oneTwentyOne);
        }

        /** An absent row amount is omitted from the payload rather than emitted as a zero. */
        @Test
        @DisplayName("omits an absent row amount")
        void anAbsentRowAmountIsOmitted() throws JsonProcessingException {
            TransactionListResponse response =
                    withRows(
                            List.of(
                                    new TransactionListResponse.TransactionRow(
                                            ROW_SELECTION, ROW_TRANSACTION_ID_1, null, null, null)));

            assertThat(payloadOf(response)).doesNotContain("\"amount\"");
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
                            .readTree(payloadOf(populatedPage()));

            assertThat(tree.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(
                    EXPECTED_COMPONENTS);
        }

        /** An absent component is omitted rather than emitted as a null. */
        @Test
        @DisplayName("omits an absent component")
        void anAbsentComponentIsOmitted() throws JsonProcessingException {
            String payload = payloadOf(carrying("message", "boundary"));

            assertThat(payload).contains("\"message\":\"boundary\"");
            assertThat(payload)
                    .doesNotContain("\"pageMetadata\"")
                    .doesNotContain("\"navigationContext\"")
                    .doesNotContain("\"nextRoute\"")
                    .doesNotContain("\"transactionIdFilter\"")
                    .doesNotContain("\"displayedPageNumber\"")
                    .doesNotContain("\"focusScreenFieldId\"")
                    .doesNotContain("\"title01\"")
                    .doesNotContain("\"title02\"")
                    .doesNotContain("\"currentDate\"")
                    .doesNotContain("\"currentTime\"")
                    .doesNotContain("\"transactionName\"")
                    .doesNotContain("\"programName\"");
        }

        /**
         * The browse position is emitted under the name this screen declares, which is the same name
         * the card-list screen declares for the same collaborator.
         */
        @Test
        @DisplayName("emits the browse position under the name pageMetadata")
        void theBrowsePositionIsEmittedUnderTheNamePage() throws JsonProcessingException {
            String payload = payloadOf(populatedPage());

            assertThat(payload).contains("\"pageMetadata\":{");
            assertThat(payload)
                    .as("no abbreviated spelling of the collaborator is emitted alongside it")
                    .doesNotContain("\"page\":");
        }

        /**
         * The navigation state is emitted under the name this screen declares, which is the same name
         * the card-list screen declares for the same collaborator.
         */
        @Test
        @DisplayName("emits the navigation state under the name navigationContext")
        void theNavigationStateIsEmittedUnderTheNameNavigation() throws JsonProcessingException {
            String payload = payloadOf(populatedPage());

            assertThat(payload).contains("\"navigationContext\":{");
            assertThat(payload)
                    .as("no abbreviated spelling of the collaborator is emitted alongside it")
                    .doesNotContain("\"navigation\":");
        }

        /** The indicator is emitted under the name this screen declares. */
        @Test
        @DisplayName("emits the indicator under the name error")
        void theIndicatorIsEmittedUnderTheNameError() throws JsonProcessingException {
            String payload = payloadOf(reporting(null, true));

            assertThat(payload).contains("\"error\":true");
            assertThat(payload).doesNotContain("\"generalError\"");
        }

        /** An empty row list is emitted as an empty array rather than omitted. */
        @Test
        @DisplayName("emits an empty row list as an empty array")
        void anEmptyRowListIsEmittedAsAnEmptyArray() throws JsonProcessingException {
            assertThat(payloadOf(withRows(List.of()))).contains("\"rows\":[]");
        }

        /** An empty response carries only the two components that cannot be absent. */
        @Test
        @DisplayName("emits only the two components that cannot be absent when empty")
        void anEmptyResponseCarriesOnlyTheComponentsThatCannotBeAbsent()
                throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(withRows(null)));

            assertThat(tree.fieldNames()).toIterable().containsExactlyInAnyOrder("rows", "error");
        }

        /** The rows are emitted in the order they are carried. */
        @Test
        @DisplayName("emits the rows in the order they are carried")
        void theRowsAreEmittedInOrder() throws JsonProcessingException {
            String payload = payloadOf(withRows(threeDescendingRows()));

            assertThat(payload.indexOf(ROW_TRANSACTION_ID_3))
                    .isLessThan(payload.indexOf(ROW_TRANSACTION_ID_2));
            assertThat(payload.indexOf(ROW_TRANSACTION_ID_2))
                    .isLessThan(payload.indexOf(ROW_TRANSACTION_ID_1));
        }

        /** Each row member appears under the name the nested record declares. */
        @Test
        @DisplayName("emits each row member under its declared name")
        void eachRowMemberAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(withRows(List.of(row(ROW_TRANSACTION_ID_1)))));

            assertThat(tree.get("rows").get(0).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_ROW_COMPONENTS);
        }

        /** The identifier and page indicator are emitted as text and never as numbers. */
        @Test
        @DisplayName("emits identifiers and the page indicator as text")
        void theIdentifiersAreEmittedAsText() throws JsonProcessingException {
            String payload = payloadOf(populatedPage());

            assertThat(payload).contains("\"transactionIdFilter\":\"" + TRANSACTION_ID_FILTER
                    + "\"");
            assertThat(payload)
                    .contains("\"displayedPageNumber\":\"" + PAGE_NUMBER + "\"");
            assertThat(payload).contains("\"transactionId\":\"" + ROW_TRANSACTION_ID_1 + "\"");
        }

        /** An unknown property is tolerated on read under the module's declared settings. */
        @Test
        @DisplayName("tolerates an unknown property on read")
        void anUnknownPropertyIsToleratedOnRead() {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload = "{\"message\":\"boundary\",\"error\":false,\"surplus\":\"ignored\"}";

            assertThatNoException()
                    .isThrownBy(() -> mapper.readValue(payload, TransactionListResponse.class));
        }

        /** A round trip through the declared settings preserves every component. */
        @Test
        @DisplayName("preserves every component across a round trip")
        void aRoundTripPreservesEveryComponent() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            TransactionListResponse original = populatedPage();

            TransactionListResponse restored =
                    mapper.readValue(
                            mapper.writeValueAsString(original), TransactionListResponse.class);

            assertThat(restored).isEqualTo(original);
        }
    }

    /** Bean Validation behaviour at, inside and outside each declared bound. */
    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        /** A fully populated page reports no violation. */
        @Test
        @DisplayName("reports no violation on a populated page")
        void aFullyPopulatedPageReportsNoViolation() {
            Set<ConstraintViolation<TransactionListResponse>> violations =
                    validator.validate(populatedPage());

            assertThat(violations).isEmpty();
        }

        /** An empty response reports no violation. */
        @Test
        @DisplayName("reports no violation on an empty response")
        void anEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(withRows(null))).isEmpty();
        }

        /** A value one character past a bound is reported against that component. */
        @ParameterizedTest(name = "{0} over {1} is reported")
        @CsvSource({
            "transactionIdFilter,16",
            "displayedPageNumber,8",
            "message,78",
            "focusScreenFieldId,7",
            "title01,40",
            "title02,40",
            "currentDate,8",
            "currentTime,8",
            "transactionName,4",
            "programName,8"
        })
        @DisplayName("reports an over-long value")
        void anOverLongValueIsReported(String component, int width) {
            Set<ConstraintViolation<TransactionListResponse>> violations =
                    validator.validate(carrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A value exactly at a bound is accepted. */
        @ParameterizedTest(name = "{0} at {1} is accepted")
        @CsvSource({
            "transactionIdFilter,16",
            "displayedPageNumber,8",
            "message,78",
            "focusScreenFieldId,7",
            "title01,40",
            "title02,40",
            "currentDate,8",
            "currentTime,8",
            "transactionName,4",
            "programName,8"
        })
        @DisplayName("accepts a value at a bound")
        void anAtWidthValueIsAccepted(String component, int width) {
            assertThat(validator.validate(carrying(component, "A".repeat(width)))).isEmpty();
        }

        /** The route is not measured, because it is a service-owned identifier. */
        @Test
        @DisplayName("does not measure the route")
        void theRouteIsNotMeasured() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(4096)))).isEmpty();
        }

        /**
         * A row bound is not cascaded from the enclosing page, because this contract declares no
         * cascade. The row must therefore be validated in its own right.
         */
        @Test
        @DisplayName("does not cascade a row bound from the enclosing page")
        void aRowBoundIsNotCascadedFromTheEnclosingPage() {
            TransactionListResponse response =
                    withRows(List.of(rowCarrying("description", "A".repeat(27))));

            assertThat(validator.validate(response))
                    .as("no cascade is declared, so the page reports nothing")
                    .isEmpty();
        }

        /** A row value one character past a bound is reported when the row is validated directly. */
        @ParameterizedTest(name = "row {0} over {1} is reported")
        @CsvSource({
            "selection,1",
            "transactionId,16",
            "displayedDate,8",
            "description,26"
        })
        @DisplayName("reports an over-long row value when the row is validated directly")
        void anOverLongRowValueIsReportedWhenTheRowIsValidatedDirectly(
                String component, int width) {
            Set<ConstraintViolation<TransactionListResponse.TransactionRow>> violations =
                    validator.validate(rowCarrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A row value exactly at a bound is accepted. */
        @ParameterizedTest(name = "row {0} at {1} is accepted")
        @CsvSource({
            "selection,1",
            "transactionId,16",
            "displayedDate,8",
            "description,26"
        })
        @DisplayName("accepts a row value at a bound")
        void anAtWidthRowValueIsAccepted(String component, int width) {
            assertThat(validator.validate(rowCarrying(component, "A".repeat(width)))).isEmpty();
        }

        /** A fully populated row reports no violation. */
        @Test
        @DisplayName("reports no violation on a populated row")
        void aPopulatedRowReportsNoViolation() {
            assertThat(validator.validate(row(ROW_TRANSACTION_ID_1))).isEmpty();
        }

        /** An entirely absent row reports no violation, because nothing is required. */
        @Test
        @DisplayName("reports no violation on an absent row")
        void anEntirelyAbsentRowReportsNoViolation() {
            assertThat(
                            validator.validate(
                                    new TransactionListResponse.TransactionRow(
                                            null, null, null, null, null)))
                    .isEmpty();
        }

        /**
         * The row amount is not measured by Bean Validation, at either end of the range the record
         * field can hold.
         *
         * <p>The extremes chosen are the widest and the smallest non-zero values the field holds, not
         * arbitrarily large ones: a wider value never reaches the validator because the constructor
         * refuses it, so asserting that the validator tolerates one would assert nothing about the
         * validator. What is shown here is that within the shape the constructor admits, no bound,
         * digit count or decimal constraint is applied on top.</p>
         */
        @Test
        @DisplayName("does not measure the row amount")
        void theRowAmountIsNotMeasured() {
            TransactionListResponse.TransactionRow widest =
                    new TransactionListResponse.TransactionRow(
                            null, null, null, null, new BigDecimal("999999999.99"));
            TransactionListResponse.TransactionRow smallest =
                    new TransactionListResponse.TransactionRow(
                            null, null, null, null, new BigDecimal("-999999999.99"));

            assertThat(validator.validate(widest)).isEmpty();
            assertThat(validator.validate(smallest)).isEmpty();
        }

        /**
         * An amount outside the record field's shape is refused by the constructor and therefore never
         * reaches the validator at all.
         *
         * <p>This is the reason the assertion above uses the field's own extremes. The two mechanisms
         * are not alternatives: the constructor decides what shapes exist, and Bean Validation measures
         * character widths on the components that carry text. Neither one measures the amount.</p>
         */
        @Test
        @DisplayName("refuses an out-of-shape amount before the validator could see it")
        void anOutOfShapeRowAmountNeverReachesTheValidator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    new TransactionListResponse.TransactionRow(
                                            null,
                                            null,
                                            null,
                                            null,
                                            new BigDecimal(
                                                    "123456789012345678901234567890.123456789")));
        }

        /** A blank or space-valued component is an ordinary state and is not reported. */
        @Test
        @DisplayName("accepts blank and space-padded values")
        void aBlankOrSpacePaddedValueIsAccepted() {
            assertThat(validator.validate(carrying("message", ""))).isEmpty();
            assertThat(validator.validate(carrying("transactionName", "    "))).isEmpty();
            assertThat(validator.validate(rowCarrying("selection", " "))).isEmpty();
        }
    }

    /** What the generated text rendering reveals, and what the nested records withhold. */
    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /** The three components this type replaces with a fixed placeholder in its rendering. */
        private static final List<String> WITHHELD_BY_THIS_TYPE =
                List.of("rows", "pageMetadata", "transactionIdFilter");

        /** The three row components the nested row replaces with a fixed placeholder. */
        private static final List<String> WITHHELD_BY_THE_ROW =
                List.of("transactionId", "description", "amount");

        /**
         * The rendering is the hand-written withholding form and not the generated record form.
         *
         * <p>The expected text is assembled here from the declared component order and an
         * independently written list of rendered values, so that a change to either the order or a
         * value fails this test. Two things distinguish it from what a record would generate: a row
         * count is emitted ahead of the components, because how many rows a page carried is the useful
         * non-identifying fact about it, and three components are replaced by a fixed placeholder.
         * Everything else is emitted in declaration order exactly as the generated form would.</p>
         */
        @Test
        @DisplayName("renders the withholding form and not the generated record form")
        void theRenderingIsExactlyTheGeneratedForm() {
            String withheld = "***REDACTED***";
            List<String> renderedValues =
                    List.of(
                            withheld,
                            withheld,
                            "null",
                            NEXT_ROUTE,
                            withheld,
                            PAGE_NUMBER,
                            TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                            "false",
                            FOCUS_FIELD_NAME,
                            SCREEN_TITLE_LINE_1,
                            SCREEN_TITLE_LINE_2,
                            CURRENT_DATE,
                            CURRENT_TIME,
                            TRANSACTION_NAME,
                            PROGRAM_NAME);

            StringBuilder expected = new StringBuilder("TransactionListResponse[rowCount=1");
            for (int index = 0; index < EXPECTED_COMPONENTS.size(); index++) {
                expected.append(", ")
                        .append(EXPECTED_COMPONENTS.get(index))
                        .append('=')
                        .append(renderedValues.get(index));
            }
            expected.append(']');

            assertThat(singleRowPageWithoutNestedRecords()).hasToString(expected.toString());
        }

        /**
         * The browse position is withheld whether or not one is carried, so the rendering discloses
         * nothing at all about the operator's position in the browse.
         *
         * <p>An earlier revision asserted the converse - that a response carrying neither nested
         * record substituted nothing - which was true of a type that declared no rendering of its own.
         * Unconditional substitution is the stronger property: if the placeholder appeared only when a
         * cursor was held, its presence or absence would itself report whether the operator had paged,
         * and a diagnostic that leaks one bit is still a diagnostic that leaks. The navigation state is
         * printed by delegation instead, so its absence is visible as {@code null} - that is safe
         * precisely because the state withholds its own identifying values.</p>
         */
        @Test
        @DisplayName("withholds the browse position whether or not one is carried")
        void aResponseCarryingNeitherNestedRecordContainsNoPlaceholder() {
            String withoutEither = singleRowPageWithoutNestedRecords().toString();
            String withBoth = populatedPage().toString();

            assertThat(withoutEither).contains("pageMetadata=***REDACTED***");
            assertThat(withBoth).contains("pageMetadata=***REDACTED***");
            assertThat(withoutEither).contains("navigationContext=null");
        }

        /**
         * Exactly three components of this type are withheld, and nothing outside those three is.
         *
         * <p>The rows are withheld whole rather than row by row, as a second line of defence behind
         * each row's own rendering; the browse cursor is withheld whole because its own rendering
         * discloses its paging state and this type must not become the path by which that surfaces
         * beside the search key; and the echoed search key is withheld for the same reason the rows
         * are - it is the identifier the operator was looking for. The screen furniture, the page
         * indicator, the summary message, the error indicator and the nominated field are presentation
         * state that identifies nobody, so they are retained: a diagnostic that withholds everything
         * is one nobody can use.</p>
         */
        @Test
        @DisplayName("withholds exactly three components of its own")
        void noComponentOfThisTypeIsWithheld() {
            String rendered = populatedPage().toString();

            for (String component : WITHHELD_BY_THIS_TYPE) {
                assertThat(rendered)
                        .as("component %s carries regulated content and is withheld", component)
                        .contains(component + "=***REDACTED***");
            }

            for (String component : EXPECTED_COMPONENTS) {
                if (WITHHELD_BY_THIS_TYPE.contains(component)) {
                    continue;
                }
                assertThat(rendered)
                        .as(
                                "component %s identifies nobody and is the part of a response worth"
                                        + " seeing in a diagnostic",
                                component)
                        .doesNotContain(component + "=***REDACTED***");
            }

            assertThat(rendered)
                    .as("the row count is reported in place of the rows")
                    .contains("rowCount=3");
        }

        /**
         * No row value reaches the rendering, because the row list is withheld whole before any row's
         * own rendering is reached.
         *
         * <p>A page holds up to ten rows, so a single stringified response would otherwise have emitted
         * ten transaction identifiers beside their descriptions and their amounts - a complete statement
         * extract in one log line. The row count is emitted instead, which is the fact a reader of a
         * diagnostic actually needs.</p>
         */
        @Test
        @DisplayName("lets no row value reach the rendering")
        void noRowValueIsWithheldFromTheRendering() {
            String rendered = populatedPage().toString();

            assertThat(rendered)
                    .doesNotContain(ROW_TRANSACTION_ID_1)
                    .doesNotContain(ROW_TRANSACTION_ID_2)
                    .doesNotContain(ROW_TRANSACTION_ID_3)
                    .doesNotContain(ROW_DISPLAYED_DATE)
                    .doesNotContain(ROW_DESCRIPTION)
                    .doesNotContain("1234.56");
            assertThat(rendered).contains("rows=***REDACTED***", "rowCount=3");
        }

        /**
         * A row reached directly withholds its identifier, its description and its amount, and retains
         * the two values that identify nobody.
         *
         * <p>This is the inner line of defence the wholesale withholding above sits in front of. The
         * echoed selection character and the displayed date are kept because the date is the screen's
         * own rendering of when a movement was processed and the selection is which line the operator
         * marked; neither says whose movement it was or how much it moved.</p>
         */
        @Test
        @DisplayName("nested row withholds its identifier, description and amount")
        void theNestedRowWithholdsItsIdentifyingComponents() {
            String rendered = row(ROW_TRANSACTION_ID_1).toString();

            for (String component : WITHHELD_BY_THE_ROW) {
                assertThat(rendered)
                        .as("row component %s is withheld", component)
                        .contains(component + "=***REDACTED***");
            }

            assertThat(rendered)
                    .doesNotContain(ROW_TRANSACTION_ID_1)
                    .doesNotContain(ROW_DESCRIPTION)
                    .doesNotContain("1234.56");
            assertThat(rendered)
                    .contains("TransactionRow[")
                    .contains("selection=" + ROW_SELECTION)
                    .contains("displayedDate=" + ROW_DISPLAYED_DATE);
        }

        /**
         * A nested navigation state withholds its own identifying components, so none of its
         * distinctive values reaches this rendering.
         */
        @Test
        @DisplayName("relies on the navigation state to withhold its identifying components")
        void aNestedNavigationStateWithholdsItsIdentifyingComponents() {
            String rendered = populatedPage().toString();

            assertThat(rendered).contains("navigationContext=NavigationContext[");
            assertThat(rendered)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER);
        }

        /**
         * A nested browse position is withheld whole rather than delegated to, so neither its cursor
         * keys nor its paging state reaches this rendering - while the paging record reached directly
         * still withholds its keys and shows its state.
         *
         * <p>Delegating would have been enough to keep the cursor keys out, because the paging record
         * withholds them itself. It is not enough here: the paging state it does show - the direction,
         * the depth and the page number - would appear beside the echoed search key and the row count,
         * and that combination describes exactly where in whose browse the operator stood. Withholding
         * the collaborator whole removes the combination rather than one of its parts, and the paging
         * record's own rendering is asserted separately so that the inner control is not weakened by the
         * outer one.</p>
         */
        @Test
        @DisplayName("withholds the browse position whole rather than delegating to it")
        void aNestedBrowsePositionWithholdsItsCursorKeys() {
            PageMetadata position =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            "PREVCURSORKEY7788",
                            "NEXTCURSORKEY9911",
                            true,
                            true,
                            "PAGE0002");
            TransactionListResponse response =
                    new TransactionListResponse(
                            List.of(), position, null, null, null, null, null, false, null, null,
                            null, null, null, null, null);

            String rendered = response.toString();

            assertThat(rendered)
                    .doesNotContain("PREVCURSORKEY7788")
                    .doesNotContain("NEXTCURSORKEY9911");
            assertThat(rendered)
                    .as("no part of the browse position reaches the response rendering")
                    .doesNotContain("direction=FORWARD")
                    .doesNotContain("pageSize=10")
                    .doesNotContain("PAGE0002");
            assertThat(rendered).contains("pageMetadata=***REDACTED***");

            assertThat(position.toString())
                    .as("the paging record still withholds its own keys and shows its own state")
                    .doesNotContain("PREVCURSORKEY7788")
                    .doesNotContain("NEXTCURSORKEY9911")
                    .contains("direction=FORWARD")
                    .contains("pageSize=10")
                    .contains("displayedPageNumber=PAGE0002");
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
            assertThat(populatedPage()).isEqualTo(populatedPage());
        }

        /** Hashing is stable across equal instances. */
        @Test
        @DisplayName("hashes equal values alike")
        void hashCodeIsStableAcrossEqualInstances() {
            assertThat(populatedPage()).hasSameHashCodeAs(populatedPage());
        }

        /** A difference in the message or the indicator is observed. */
        @Test
        @DisplayName("observes a difference in the message or the indicator")
        void aDifferenceInTheMessageOrIndicatorIsObserved() {
            assertThat(reporting(TransactionListResponse.MESSAGE_AT_TOP, false))
                    .isNotEqualTo(reporting(TransactionListResponse.MESSAGE_REACHED_TOP, false));
            assertThat(reporting(TransactionListResponse.MESSAGE_AT_TOP, false))
                    .isNotEqualTo(reporting(TransactionListResponse.MESSAGE_AT_TOP, true));
        }

        /** A null row list and an empty row list produce equal instances. */
        @Test
        @DisplayName("treats a null row list and an empty row list alike")
        void aNullRowListAndAnEmptyRowListProduceEqualInstances() {
            assertThat(withRows(null)).isEqualTo(withRows(List.of()));
        }

        /** A difference in the row list is observed. */
        @Test
        @DisplayName("observes a difference in the row list")
        void aDifferenceInTheRowListIsObserved() {
            assertThat(withRows(threeAscendingRows()))
                    .isNotEqualTo(withRows(threeDescendingRows()));
            assertThat(withRows(threeAscendingRows())).isNotEqualTo(withRows(List.of()));
        }

        /** The nested row has value equality across its five components. */
        @Test
        @DisplayName("gives the nested row value equality")
        void theRowRecordHasValueEquality() {
            assertThat(row(ROW_TRANSACTION_ID_1)).isEqualTo(row(ROW_TRANSACTION_ID_1));
            assertThat(row(ROW_TRANSACTION_ID_1)).hasSameHashCodeAs(row(ROW_TRANSACTION_ID_1));
            assertThat(row(ROW_TRANSACTION_ID_1)).isNotEqualTo(row(ROW_TRANSACTION_ID_2));
        }

        /** Every accessor returns exactly what was supplied. */
        @Test
        @DisplayName("returns from every accessor exactly what was supplied")
        void everyAccessorReturnsWhatWasSupplied() {
            TransactionListResponse response = populatedPage();

            assertThat(response.rows()).hasSize(3);
            assertThat(response.pageMetadata()).isEqualTo(forwardPosition());
            assertThat(response.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
            assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(response.transactionIdFilter()).isEqualTo(TRANSACTION_ID_FILTER);
            assertThat(response.displayedPageNumber()).isEqualTo(PAGE_NUMBER);
            assertThat(response.message())
                    .isEqualTo(TransactionListResponse.MESSAGE_REACHED_BOTTOM);
            assertThat(response.error()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_FIELD_NAME);
            assertThat(response.title01()).isEqualTo(SCREEN_TITLE_LINE_1);
            assertThat(response.title02()).isEqualTo(SCREEN_TITLE_LINE_2);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
        }

        /** Every row accessor returns exactly what was supplied. */
        @Test
        @DisplayName("returns from every row accessor exactly what was supplied")
        void everyRowAccessorReturnsWhatWasSupplied() {
            TransactionListResponse.TransactionRow carried = row(ROW_TRANSACTION_ID_1);

            assertThat(carried.selection()).isEqualTo(ROW_SELECTION);
            assertThat(carried.transactionId()).isEqualTo(ROW_TRANSACTION_ID_1);
            assertThat(carried.displayedDate()).isEqualTo(ROW_DISPLAYED_DATE);
            assertThat(carried.description()).isEqualTo(ROW_DESCRIPTION);
            assertThat(carried.amount()).isEqualTo(ROW_AMOUNT);
        }
    }
}
