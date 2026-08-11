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

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link BillPaymentResponse}, the response body of legacy transaction {@code CB00}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the bill-payment response: the sixteen components, the eleven
 * declared widths and the one component that deliberately carries none, the thirteen contract texts,
 * the exact decimal wire form of the balance, and the diagnostic rendering - which withholds four
 * components and is the most consequential thing in the file.
 *
 * <h2>Neither balance is bounded, and that is a requirement rather than an omission</h2>
 *
 * <p>The map's balance field has a display width, and a display width is terminal geometry: it is not
 * a scale, not a precision and not a formatting hint. Putting a length bound on a decimal component
 * would express a screen measurement as a numeric rule, and the two are unrelated. Tests below prove
 * both decimal components are unannotated, prove an amount at the full width of the persisted
 * ten-and-two field passes validation, and prove the scale reaches the wire intact.
 *
 * <h2>The success text is two fragments, and the join produces two consecutive spaces</h2>
 *
 * <p>The legacy program declares the success text in two pieces: the first ends with a space and the
 * second begins with one, so the assembled text carries a two-space run after its first period. That
 * run is contract, not a defect to be tidied, and the same idiom appears on the transaction-add
 * screen. Tests below assert both fragments verbatim, assert the two-space run appears when they are
 * joined, and assert that neither fragment is trimmed or pre-joined.
 *
 * <h2>Two source oddities inside one program are preserved</h2>
 *
 * <p>One text spells the word for the posted record out in full while another abbreviates it in the
 * same program, and the duplicate-key text uses the singular-agreement verb form rather than the
 * grammatically expected one. Both are reproduced. Tests below pin them, because a well-meant
 * correction of either is precisely the kind of change that passes review and fails byte equivalence.
 *
 * <h2>Five components are withheld from the diagnostic rendering, and the navigation state is
 * withheld whole</h2>
 *
 * <p>The account identifier is withheld because it identifies an account; the confirmation answer
 * because it is operator input and a rejection must never echo the value it rejected; the balance
 * because, beside the retained transaction identifier, it reveals exactly what one account owed; and
 * the navigation state because it carries identifiers of its own - withheld here rather than
 * delegated, so the rendering does not depend on another type's discretion. Tests below prove all
 * four, prove the twelve retained components are retained, prove the placeholder is a fixed constant
 * so no length survives, and prove the delegation is genuinely absent by asserting the nested type's
 * own opening marker does not appear.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads come from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and makes no claim about the mapper a deployed instance holds;
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object. Decimal
 * assertions are made against the emitted characters rather than a re-parsed tree, because parsing a
 * JSON number back produces a double-valued node by default and would silently drop the trailing zero
 * the wire form actually carried.
 *
 * <p>No legacy source text is reproduced.
 */
@DisplayName("BillPaymentResponse :: bill-payment response contract of legacy transaction CB00")
class BillPaymentResponseCoverageTest {

    /** The sixteen components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "accountId", "currentBalance", "confirm", "newTransactionId",
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "errorMessage", "paymentAccepted", "generalError", "fieldErrors", "focusScreenFieldId", "nextRoute",
            "navigationContext");

    /** The four components the diagnostic rendering withholds. */
    private static final List<String> EXPECTED_WITHHELD = List.of(
            "accountId", "currentBalance", "confirm", "navigationContext");

    /**
     * The one component that deliberately carries no length bound of any kind.
     *
     * <p>The screen presents a single balance: the account balance as it stood before the payment.
     * The amount paid is not a component of this reply, because the legacy screen never redisplays
     * it - the operator supplies it and the reply reports the outcome - so there is exactly one
     * decimal here to leave unbounded.</p>
     */
    private static final List<String> EXPECTED_UNBOUNDED_DECIMALS = List.of("currentBalance");

    /** The exact placeholder the rendering emits in place of each withheld component. */
    private static final String EXPECTED_PLACEHOLDER = "***REDACTED***";

    /** An account identifier at exactly the declared width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A posted transaction identifier at exactly the declared width. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /** Shared validator factory, opened once and closed once. */
    private static ValidatorFactory validatorFactory;

    /** Validator drawn from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the validator factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the validator factory opened by {@link #openValidatorFactory()}. */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    /**
     * Builds a response carrying only the named text component, so a violation can be attributed.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a response carrying that one value
     */
    private static BillPaymentResponse carrying(String component, String value) {
        return new BillPaymentResponse(
                "accountId".equals(component) ? value : null, null,
                "confirm".equals(component) ? value : null,
                "newTransactionId".equals(component) ? value : null,
                "transactionName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "errorMessage".equals(component) ? value : null,
                false, false, List.of(),
                "focusScreenFieldId".equals(component) ? value : null,
                "nextRoute".equals(component) ? value : null,
                null);
    }

    /**
     * Builds a response carrying only the balance.
     *
     * <p>The canonical constructor enforces the record shape of this one decimal, so every value
     * handed to this helper has to carry scale two and fit ten integer digits. A value that does not
     * is the subject of its own refusal test rather than a fixture here.</p>
     *
     * @param currentBalance the balance before the payment, at scale two
     * @return a response carrying no other populated component
     */
    private static BillPaymentResponse carryingBalance(BigDecimal currentBalance) {
        return new BillPaymentResponse(null, currentBalance, null, null, null, null,
                null, null, null, null, null, false, false, List.of(), null, null, null);
    }

    /**
     * Builds the response an accepted payment produces, with every component populated.
     *
     * @return a fully populated success response
     */
    private static BillPaymentResponse accepted() {
        return new BillPaymentResponse(ACCOUNT_ID, new BigDecimal("1234567890.12"),
                "Y", TRANSACTION_ID, "CB00",
                "AWS Mainframe Modernization             ", "08/02/26", "COBIL00C",
                "CardDemo                                ", "14:35:07",
                BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX
                        + BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT + "42.",
                true, false, List.of(), null, "/api/menu", JsonContractSupport.populatedNavigation());
    }

    /**
     * Serialises a response and parses the result back into a tree.
     *
     * @param response the response to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(BillPaymentResponse response) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the sixteen components are declared in the order the screen presents them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(BillPaymentResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @ParameterizedTest(name = "{0} declares a bound of {1}")
        @CsvSource({
            "accountId,11",
            "confirm,1",
            "newTransactionId,16",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component declares the legacy width of the field it derives from")
        void eachBoundedComponentDeclaresItsLegacyWidth(String component, int width)
                throws NoSuchFieldException {
            Size bound = BillPaymentResponse.class.getDeclaredField(component)
                    .getAnnotation(Size.class);

            assertThat(bound).as("%s declares a width bound", component).isNotNull();
            assertThat(bound.max()).isEqualTo(width);
        }

        @Test
        @DisplayName("the rendered time is eight characters here, not the nine the sign-on map uses, "
                + "because each width is read from its own map")
        void theRenderedTimeWidthIsThisMapsOwn() throws NoSuchFieldException {
            int declared = BillPaymentResponse.class.getDeclaredField("currentTime")
                    .getAnnotation(Size.class).max();

            assertThat(declared)
                    .as("sharing one constant across maps would make one name stand for two "
                            + "independent legacy measurements")
                    .isEqualTo(8)
                    .isNotEqualTo(SignOnResponse.CURRENT_TIME_LENGTH);
        }

        @Test
        @DisplayName("the balance declares no length bound and no numeric rule, because a map "
                + "display width is terminal geometry rather than a scale or a precision")
        void theBalanceDeclaresNoLengthBoundAndNoNumericRule() throws NoSuchFieldException {
            for (String component : EXPECTED_UNBOUNDED_DECIMALS) {
                Annotation[] declared = BillPaymentResponse.class.getDeclaredField(component)
                        .getAnnotations();

                assertThat(Arrays.stream(declared)
                                .map(annotation -> annotation.annotationType().getName())
                                .filter(name -> name.startsWith("jakarta.validation")))
                        .as("%s must carry no validation rule of any kind: a length bound, a digit "
                                + "rule and a decimal maximum would each express a screen "
                                + "measurement as a numeric constraint", component)
                        .isEmpty();
                assertThat(Arrays.stream(declared)
                                .map(annotation -> annotation.annotationType().getSimpleName()))
                        .as("%s carries a documentation description and nothing else: the record "
                                + "shape it publishes is a fact about the persisted field, not a "
                                + "constraint on this boundary", component)
                        .containsExactly("Schema");
            }
        }

        @Test
        @DisplayName("the one balance is an exact decimal rather than a binary floating-point type, "
                + "and it is the only decimal the reply carries")
        void theBalanceIsTheOnlyExactDecimal() {
            RecordComponent[] components = BillPaymentResponse.class.getRecordComponents();

            assertThat(components[1].getType()).isEqualTo(BigDecimal.class);
            assertThat(Arrays.stream(components)
                            .filter(component -> component.getType().equals(BigDecimal.class))
                            .map(RecordComponent::getName))
                    .as("the amount paid is operator input the screen never redisplays, so it is "
                            + "not a component of the reply")
                    .containsExactly("currentBalance");
            assertThat(Arrays.stream(components)
                            .map(RecordComponent::getType)
                            .filter(type -> type.equals(double.class) || type.equals(float.class)
                                    || type.equals(Double.class) || type.equals(Float.class)))
                    .isEmpty();
        }

        @Test
        @DisplayName("the route is unbounded, because a route is a module-internal identifier rather "
                + "than a screen item of a measured width")
        void theRouteIsUnbounded() throws NoSuchFieldException {
            assertThat(BillPaymentResponse.class.getDeclaredField("nextRoute").getAnnotations())
                    .isEmpty();
        }

        @Test
        @DisplayName("one constructor exists, and it verifies the decimal's record shape without "
                + "defaulting, normalising or rescaling anything")
        void oneConstructorExistsAndItOnlyVerifies() {
            assertThat(BillPaymentResponse.class.getDeclaredConstructors())
                    .as("every text value must cross this boundary byte for byte, which is what lets "
                            + "a blank echoed field and a space-padded title reach a client unchanged")
                    .hasSize(1);
            assertThat(BillPaymentResponse.class.getDeclaredConstructors()[0].getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
            assertThat(carrying("title01", "  padded  ").title01())
                    .as("verification never rewrites: a padded screen value survives construction")
                    .isEqualTo("  padded  ");
            assertThat(carryingBalance(new BigDecimal("1.20")).currentBalance())
                    .as("a balance already at the record scale is carried, trailing zero included, "
                            + "rather than being stripped or re-scaled")
                    .isEqualTo(new BigDecimal("1.20"));
        }

        @Test
        @DisplayName("the published record shape is the ten-and-two of the persisted balance field")
        void thePublishedRecordShapeIsTheOneThePersistedFieldDeclares() {
            assertThat(BillPaymentResponse.BALANCE_SCALE)
                    .as("two decimal places, from the V99 of the account balance field")
                    .isEqualTo(2);
            assertThat(BillPaymentResponse.BALANCE_INTEGER_DIGITS)
                    .as("ten integer digits, from the S9(10) of the account balance field")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the two field identifiers are the map's own symbolic names")
        void theTwoFieldIdentifiersAreTheMapsOwnNames() {
            assertThat(BillPaymentResponse.ACCOUNT_ID_FIELD_ID).isEqualTo("ACTIDIN");
            assertThat(BillPaymentResponse.CONFIRM_FIELD_ID).isEqualTo("CONFIRM");
        }

        @Test
        @DisplayName("no member is declared beyond the sixteen accessors and the rendering "
                + "override, so this type computes nothing")
        void noMemberIsDeclaredBeyondTheAccessors() {
            List<String> instanceMethods =
                    Arrays.stream(BillPaymentResponse.class.getDeclaredMethods())
                            .filter(method ->
                                    !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                            .map(java.lang.reflect.Method::getName)
                            .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                            .toList();

            assertThat(instanceMethods).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }
    }

    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        @Test
        @DisplayName("the eleven single-purpose texts are reproduced character for character")
        void theElevenSinglePurposeTextsAreReproducedVerbatim() {
            assertThat(BillPaymentResponse.MSG_ACCT_ID_EMPTY)
                    .isEqualTo("Acct ID can NOT be empty...");
            assertThat(BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE)
                    .isEqualTo("Invalid value. Valid values are (Y/N)...");
            assertThat(BillPaymentResponse.MSG_NOTHING_TO_PAY)
                    .isEqualTo("You have nothing to pay...");
            assertThat(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT)
                    .isEqualTo("Confirm to make a bill payment...");
            assertThat(BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND)
                    .isEqualTo("Account ID NOT found...");
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_ACCOUNT)
                    .isEqualTo("Unable to lookup Account...");
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_UPDATE_ACCOUNT)
                    .isEqualTo("Unable to Update Account...");
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX)
                    .isEqualTo("Unable to lookup XREF AIX file...");
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND)
                    .isEqualTo("Transaction ID NOT found...");
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION)
                    .isEqualTo("Unable to lookup Transaction...");
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION)
                    .isEqualTo("Unable to Add Bill pay Transaction...");
        }

        @Test
        @DisplayName("the two success fragments keep the spaces that are part of their literals")
        void theTwoSuccessFragmentsKeepTheirLiteralSpaces() {
            assertThat(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX)
                    .isEqualTo("Payment successful. ")
                    .endsWith(" ");
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .isEqualTo(" Your Transaction ID is ")
                    .startsWith(" ")
                    .endsWith(" ");
        }

        @Test
        @DisplayName("joining the two fragments produces the two-space run after the first period, "
                + "which is contract text rather than a defect")
        void joiningTheTwoFragmentsProducesTheTwoSpaceRun() {
            String joined = BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX
                    + BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT;

            assertThat(joined)
                    .as("collapsing the run, trimming either fragment or pre-joining them into one "
                            + "constant would each break byte equivalence")
                    .isEqualTo("Payment successful.  Your Transaction ID is ")
                    .contains(".  Your");
        }

        @Test
        @DisplayName("the fragments are declared separately, so no single constant already carries "
                + "the assembled text")
        void theFragmentsAreDeclaredSeparately() {
            assertThat(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX)
                    .as("assembly belongs to the bill-payment service, which appends the identifier "
                            + "and the closing period")
                    .doesNotContain("Transaction ID is");
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .doesNotContain("Payment successful");
        }

        @Test
        @DisplayName("one text spells the word for the posted record out and another abbreviates it "
                + "in the same program, and neither is unified with the other")
        void theSpelledOutAndAbbreviatedFormsBothSurvive() {
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .contains("Transaction ID");
            assertThat(BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST)
                    .as("expanding the abbreviation would change contract text a client may match on")
                    .startsWith("Tran ID")
                    .doesNotContain("Transaction");
        }

        @Test
        @DisplayName("the duplicate-key text keeps the singular-agreement verb form the source uses")
        void theDuplicateKeyTextKeepsItsVerbForm() {
            assertThat(BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST)
                    .as("correcting the grammar is exactly the kind of change that passes review and "
                            + "fails byte equivalence")
                    .isEqualTo("Tran ID already exist...")
                    .doesNotContain("exists");
        }

        @Test
        @DisplayName("every declared text fits inside the width the map rendered the message at")
        void everyDeclaredTextFitsTheRenderedWidth() throws NoSuchFieldException {
            int renderedWidth = BillPaymentResponse.class.getDeclaredField("errorMessage")
                    .getAnnotation(Size.class).max();

            for (String text : List.of(BillPaymentResponse.MSG_ACCT_ID_EMPTY,
                    BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE,
                    BillPaymentResponse.MSG_NOTHING_TO_PAY,
                    BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT,
                    BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_ACCOUNT,
                    BillPaymentResponse.MSG_UNABLE_TO_UPDATE_ACCOUNT,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX,
                    BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                    BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST,
                    BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION)) {
                assertThat(text.length()).as("text \"%s\"", text)
                        .isLessThanOrEqualTo(renderedWidth);
            }
        }

        @Test
        @DisplayName("the assembled success text also fits, identifier and closing period included")
        void theAssembledSuccessTextAlsoFits() throws NoSuchFieldException {
            int renderedWidth = BillPaymentResponse.class.getDeclaredField("errorMessage")
                    .getAnnotation(Size.class).max();
            String assembled = BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX
                    + BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT + TRANSACTION_ID + ".";

            assertThat(assembled.length()).isLessThanOrEqualTo(renderedWidth);
        }

        @Test
        @DisplayName("the thirteen texts are distinct, so a client can tell every outcome apart")
        void theThirteenTextsAreDistinct() {
            assertThat(List.of(BillPaymentResponse.MSG_ACCT_ID_EMPTY,
                            BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE,
                            BillPaymentResponse.MSG_NOTHING_TO_PAY,
                            BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT,
                            BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND,
                            BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_ACCOUNT,
                            BillPaymentResponse.MSG_UNABLE_TO_UPDATE_ACCOUNT,
                            BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX,
                            BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND,
                            BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                            BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST,
                            BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION,
                            BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX))
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("an accepted response whose every component sits at or inside its width reports "
                + "no violation")
        void anAcceptedResponseReportsNoViolation() {
            assertThat(validator.validate(accepted())).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "accountId,11",
            "confirm,1",
            "newTransactionId,16",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component reports a value one character over its width")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            Set<ConstraintViolation<BillPaymentResponse>> violations =
                    validator.validate(carrying(component, "X".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value exactly at {1}")
        @CsvSource({
            "accountId,11",
            "confirm,1",
            "newTransactionId,16",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component accepts a value exactly at its width, so the bound is "
                + "inclusive")
        void eachBoundedComponentAcceptsAValueAtItsWidth(String component, int width) {
            assertThat(validator.validate(carrying(component, "X".repeat(width)))).isEmpty();
        }

        @ParameterizedTest(name = "balance {0} passes validation unbounded")
        @ValueSource(strings = {
            "0.00", "9999999999.99", "-9999999999.99", "1234567890.12", "-0.01",
        })
        @DisplayName("a balance anywhere inside the persisted ten-and-two field passes, sign and full "
                + "width included, because no numeric rule is declared at this boundary")
        void anyBalanceInsideTheRecordShapePassesValidation(String literal) {
            BigDecimal amount = new BigDecimal(literal);

            assertThat(validator.validate(carryingBalance(amount)))
                    .as("scaling belongs to the zoned-decimal codec, not to this boundary; what the "
                            + "boundary does is refuse a value the record field could not hold")
                    .isEmpty();
        }

        @ParameterizedTest(name = "balance {0} is refused by the constructor")
        @ValueSource(strings = {"0", "1.2", "1234567890.1234", "0.000"})
        @DisplayName("a balance whose scale is not the record's two is refused on construction, "
                + "because a rescale here would hide a codec defect rather than surface it")
        void aBalanceOffTheRecordScaleIsRefused(String literal) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingBalance(new BigDecimal(literal)))
                    .withMessageContaining("scale");
        }

        @Test
        @DisplayName("a balance needing more integer digits than the record field holds is refused")
        void aBalanceWiderThanTheRecordFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingBalance(new BigDecimal("99999999999.99")))
                    .withMessageContaining("integer digits");
        }

        @Test
        @DisplayName("an entirely empty response reports no violation, because a first entry into "
                + "the screen has nothing to report")
        void anEntirelyEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(
                            new BillPaymentResponse(null, null, null, null, null, null, null, null,
                                    null, null, null, false, false, List.of(), null, null, null)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "confirmation \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"Y", "N", "y", "n", "X", " "})
        @DisplayName("the confirmation bound restricts width only, because the accepted-value check "
                + "carries one specific message and belongs to the service")
        void theConfirmationBoundNamesNoAcceptableCharacter(String confirm) {
            assertThat(validator.validate(carrying("confirm", confirm))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Decimal wire form")
    class DecimalWireForm {

        @ParameterizedTest(name = "{0} crosses the wire as {1}")
        @CsvSource({
            "0.00,0.00",
            "1.20,1.20",
            "1234567890.12,1234567890.12",
            "-9999999999.99,-9999999999.99",
            "0.01,0.01",
        })
        @DisplayName("a balance crosses the wire plainly at its own scale, trailing zeros included")
        void aBalanceCrossesTheWirePlainlyAtItsScale(String literal, String expected)
                throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(carryingBalance(new BigDecimal(literal)));

            assertThat(rendered)
                    .as("the assertion is deliberately made against the emitted characters rather "
                            + "than a re-parsed tree: parsing a JSON number back produces a "
                            + "double-valued node by default, which would silently drop the trailing "
                            + "zero the wire form actually carried")
                    .isEqualTo("{\"currentBalance\":" + expected + ",\"paymentAccepted\":false,"
                            + "\"generalError\":false,\"fieldErrors\":[]}");
        }

        @Test
        @DisplayName("a large balance is never rendered in scientific notation, which a client "
                + "parsing fixed-width output could not read")
        void aLargeBalanceIsNeverRenderedInScientificNotation() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(carryingBalance(new BigDecimal("9999999999.99")));

            assertThat(rendered).contains("\"currentBalance\":9999999999.99,");
            assertThat(JsonContractSupport.renderedValueToken(rendered, "currentBalance"))
                    .as("an exponent marker in the number token would make the value unreadable to a "
                            + "client that parses the fixed-width form; the assertion is scoped to "
                            + "the token because a member name may legitimately contain the letter E")
                    .isEqualTo("9999999999.99")
                    .doesNotContainIgnoringCase("e");
        }

        @Test
        @DisplayName("a balance round trips at its exact scale when read back as an exact decimal, "
                + "because scale participates in decimal equality")
        void aBalanceRoundTripsAtItsExactScale() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            BillPaymentResponse response =
                    carryingBalance(new BigDecimal("0.00"));

            BillPaymentResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(returned.currentBalance()).isEqualByComparingTo("0.00");
            assertThat(returned.currentBalance().scale())
                    .as("a lost scale is detectable precisely because decimal equality is value and "
                            + "scale together")
                    .isEqualTo(2);
            assertThat(returned).isEqualTo(response);
        }

        @Test
        @DisplayName("a balance supplied at an unexpected scale is refused rather than rescaled, "
                + "because a silent rescale would hide a codec defect rather than surface it")
        void aBalanceAtAnUnexpectedScaleIsRefused() {
            BigDecimal fourDecimals = new BigDecimal("1234567890.1234");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the alternative - carrying it unchanged - lets a wrongly scaled value reach "
                            + "a client and turns a codec defect into a silent data defect")
                    .isThrownBy(() -> carryingBalance(fourDecimals))
                    .withMessageContaining("must carry scale 2");
            assertThat(carryingBalance(new BigDecimal("1234567890.12")).currentBalance().scale())
                    .isEqualTo(2);
        }

        /**
         * An absent balance is omitted and a zero balance is written, so the two stay distinguishable.
         *
         * <p>The absence half is asserted through a re-parsed tree, because presence is a structural
         * property a tree reports faithfully. The zero half is asserted against the emitted characters
         * instead, for the reason the parameterised test above this one records: re-parsing a JSON
         * number produces a double-valued node by default, whose text form is {@code 0.0}, so a tree
         * assertion would be testing the reader's normalisation rather than the wire form and would
         * drop precisely the trailing zero that makes the value a two-decimal money amount.</p>
         */
        @Test
        @DisplayName("an absent balance is omitted rather than written as zero, so \"no balance\" and "
                + "\"a balance of zero\" stay distinguishable")
        void anAbsentBalanceIsOmittedRatherThanWrittenAsZero() throws JsonProcessingException {
            assertThat(payloadOf(carryingBalance(null)).has("currentBalance"))
                    .as("a first entry into the screen has looked nothing up yet, and reporting that "
                            + "as a zero balance would be a different statement")
                    .isFalse();

            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(carryingBalance(new BigDecimal("0.00")));

            assertThat(JsonContractSupport.renderedValueToken(rendered, "currentBalance"))
                    .as("a zero balance is a value and is written, at the declared scale, so the "
                            + "trailing zero survives onto the wire")
                    .isEqualTo("0.00");
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("an accepted response renders all seventeen members under their contract names")
        void anAcceptedResponseRendersAllSeventeenMembers() throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted());

            assertThat(payload.size()).isEqualTo(EXPECTED_COMPONENTS.size() - 1);
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("newTransactionId").asText()).isEqualTo(TRANSACTION_ID);
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
            assertThat(payload.get("paymentAccepted").asBoolean()).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("nextRoute").asText()).isEqualTo("/api/menu");
            assertThat(payload.has("focusScreenFieldId"))
                    .as("an accepted payment focuses nothing, and an absent value is omitted")
                    .isFalse();
        }

        @Test
        @DisplayName("the account identifier and the balances are not redacted on the wire, because "
                + "the response goes to the operator who is already looking at the screen")
        void theWireFormIsNotRedacted() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(accepted());

            assertThat(payloadOf(accepted()).get("accountId").asText())
                    .as("the protection is against diagnostic channels, not against the client")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(rendered)
                    .as("the balance is checked against the emitted characters rather than a "
                            + "re-parsed tree, which would report this value back as an "
                            + "exponent-form double")
                    .contains("\"currentBalance\":1234567890.12,");
        }

        @Test
        @DisplayName("the title lines keep their trailing spaces, because they are fixed-width screen "
                + "values rather than trimmed labels")
        void theTitleLinesKeepTheirTrailingSpaces() throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted());

            assertThat(payload.get("title01").asText()).hasSize(40).endsWith(" ");
            assertThat(payload.get("title02").asText()).hasSize(40).endsWith(" ");
        }

        @Test
        @DisplayName("both boolean flags are always written, because a primitive has no absent state "
                + "to omit")
        void bothBooleanFlagsAreAlwaysWritten() throws JsonProcessingException {
            JsonNode payload = payloadOf(
                    new BillPaymentResponse(null, null, null, null, null, null, null, null, null,
                            null, null, false, false, List.of(), null, null, null));

            // Three, not two: the per-field decoration list is normalised to an empty list by the
            // canonical constructor, and the module omits nulls rather than empties, so it crosses as
            // [] on every reply. That is what lets a reader tell "this turn faulted no field" from
            // "this reply does not speak about fields" at all.
            assertThat(payload.size()).isEqualTo(3);
            assertThat(payload.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
        }

        @Test
        @DisplayName("an explicitly blanked component is written while an absent one is omitted, so "
                + "the two states stay distinguishable on the wire")
        void aBlankedComponentIsWrittenWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("confirm", " "));

            assertThat(payload.get("confirm").asText()).isEqualTo(" ");
            assertThat(payload.has("accountId")).isFalse();
        }

        @Test
        @DisplayName("a fully populated response round trips unchanged, nested navigation state "
                + "included")
        void aFullyPopulatedResponseRoundTripsUnchanged() throws JsonProcessingException {
            BillPaymentResponse response = accepted();
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            BillPaymentResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(returned).isEqualTo(response);
            assertThat(returned.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("an unknown member is ignored rather than rejected, so a client may echo the "
                + "response back without being refused")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"rows\":[],\"page\":null}";

            BillPaymentResponse returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, BillPaymentResponse.class);

            assertThat(returned.accountId()).isEqualTo(ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("Diagnostic redaction")
    class DiagnosticRedaction {

        @Test
        @DisplayName("the account identifier, the balance, the confirmation answer and the "
                + "navigation state are all withheld")
        void allFourRegulatedComponentsAreWithheld() {
            String rendered = accepted().toString();

            assertThat(rendered).startsWith("BillPaymentResponse[");
            for (String component : EXPECTED_WITHHELD) {
                assertThat(rendered).as("%s is withheld", component)
                        .contains(component + "=" + EXPECTED_PLACEHOLDER);
            }
            assertThat(rendered)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain("1234567890.12");
        }

        @Test
        @DisplayName("the navigation state is withheld whole rather than delegated, so the rendering "
                + "does not depend on another type's discretion")
        void theNavigationStateIsWithheldWholeRatherThanDelegated() {
            String rendered = accepted().toString();

            assertThat(rendered)
                    .as("omitting it here avoids depending on the nested type continuing to redact "
                            + "its own identifiers")
                    .doesNotContain("NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_USER_ID);
        }

        @Test
        @DisplayName("the confirmation answer is withheld because a rejection must never echo the "
                + "value it rejected")
        void theConfirmationAnswerIsWithheld() {
            assertThat(carrying("confirm", "Q").toString())
                    .contains("confirm=" + EXPECTED_PLACEHOLDER)
                    .doesNotContain("confirm=Q");
        }

        @Test
        @DisplayName("the twelve retained components are retained, because that set is what names "
                + "which payment attempt this was and what the operator was told")
        void theTwelveRetainedComponentsAreRetained() {
            String rendered = accepted().toString();

            assertThat(rendered)
                    .contains("newTransactionId=" + TRANSACTION_ID)
                    .contains("transactionName=CB00")
                    .contains("programName=COBIL00C")
                    .contains("currentDate=08/02/26")
                    .contains("currentTime=14:35:07")
                    .contains("paymentAccepted=true")
                    .contains("generalError=false")
                    .contains("nextRoute=/api/menu")
                    .contains("Payment successful.");
        }

        @Test
        @DisplayName("the summary message is retained because it is contract text carrying no "
                + "operator input, not even on the success arm")
        void theSummaryMessageIsRetained() {
            assertThat(carrying("errorMessage", BillPaymentResponse.MSG_NOTHING_TO_PAY).toString())
                    .contains("errorMessage=" + BillPaymentResponse.MSG_NOTHING_TO_PAY);
        }

        @Test
        @DisplayName("the placeholder does not vary with the value, so neither the length nor a "
                + "prefix of a withheld component is recoverable")
        void thePlaceholderDoesNotVaryWithTheValue() {
            assertThat(carrying("accountId", "1").toString())
                    .as("a truncated account identifier is still account data, and a digest of an "
                            + "eleven-character identifier is trivially reversible by enumeration")
                    .isEqualTo(carrying("accountId", ACCOUNT_ID).toString());
        }

        @Test
        @DisplayName("an absent withheld component renders as the same placeholder, so absence and "
                + "presence are indistinguishable in a diagnostic")
        void absenceAndPresenceAreIndistinguishable() {
            String rendered = new BillPaymentResponse(null, null, null, null, null, null, null,
                    null, null, null, null, false, false, List.of(), null, null, null).toString();

            for (String component : EXPECTED_WITHHELD) {
                assertThat(rendered).as("%s is withheld even when absent", component)
                        .contains(component + "=" + EXPECTED_PLACEHOLDER);
            }
        }

        @Test
        @DisplayName("withholding is confined to the rendering path: every accessor returns its "
                + "component exactly as supplied")
        void withholdingIsConfinedToTheRenderingPath() {
            BillPaymentResponse response = accepted();

            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.currentBalance()).isEqualByComparingTo("1234567890.12");
            assertThat(response.confirm()).isEqualTo("Y");
            assertThat(response.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("no balance digit sequence survives the rendering under any scale")
        void noBalanceDigitSequenceSurvivesTheRendering() {
            for (String literal : List.of("0.00", "1.20", "9999999999.99", "-4242.42")) {
                assertThat(carryingBalance(new BigDecimal(literal)).toString())
                        .as("balance literal %s", literal)
                        .doesNotContain(literal);
            }
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component by value, the balance included, so "
                + "byte-for-byte fixture comparison is unaffected by the rendering override")
        void equalityComparesEveryComponentByValue() {
            BillPaymentResponse left = accepted();
            BillPaymentResponse right = accepted();

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("a balance and its absence do not compare equal, and two balances differing in "
                + "value do not either, so a lost or altered balance is detectable")
        void balancesAreComparedByValue() {
            assertThat(carryingBalance(new BigDecimal("1.20")))
                    .isNotEqualTo(carryingBalance(new BigDecimal("1.21")))
                    .isNotEqualTo(carryingBalance(null))
                    .isEqualTo(carryingBalance(new BigDecimal("1.20")));
        }

        @Test
        @DisplayName("a scale-shifted balance cannot be constructed at all, which is the stronger "
                + "form of the same guarantee equality used to provide")
        void aScaleShiftedBalanceCannotBeConstructed() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingBalance(new BigDecimal("1.2")));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingBalance(new BigDecimal("1.200")));
        }

        @Test
        @DisplayName("the acceptance flag participates in equality, so an accepted and a rejected "
                + "response carrying the same text are distinguishable")
        void theAcceptanceFlagParticipatesInEquality() {
            BillPaymentResponse acceptedFlag = new BillPaymentResponse(null, null, null, null,
                    null, null, null, null, null, null, "same", true, false, List.of(), null, null, null);
            BillPaymentResponse rejectedFlag = new BillPaymentResponse(null, null, null, null,
                    null, null, null, null, null, null, "same", false, true, List.of(), null, null, null);

            assertThat(acceptedFlag).isNotEqualTo(rejectedFlag);
        }
    }
}
