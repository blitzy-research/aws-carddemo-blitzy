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

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link BillPaymentResponse}, the response body of legacy transaction {@code CB00}
 * implemented by {@code app/cbl/COBIL00C.cbl} over screen {@code app/cpy-bms/COBIL00.CPY}.
 *
 * <p><strong>Two balances, both monetary and both truncating.</strong> {@code ACCT-CURR-BAL} is
 * {@code PIC S9(10)V99} in {@code app/cpy/CVACT01Y.cpy:L1-L30}, and the estate contains no
 * {@code ROUNDED} clause anywhere, so every store into a two-decimal field truncates. The response
 * therefore carries {@link BigDecimal} rather than any floating-point type, and the payload
 * assertions read the raw wire characters rather than a parsed tree - a tree binds a JSON float to a
 * double by default and would report scientific notation for a payload that in fact carries a plain
 * decimal.
 *
 * <p><strong>The rendering withholds four values and the whole conversation state.</strong>
 * {@code toString} is overridden to substitute a placeholder for the account identifier, both
 * balances, the confirmation character and the carried navigation block, so a log record can name the
 * response without carrying an account balance into a log file. The remaining twelve components are
 * screen furniture and routing and are rendered in full. Both halves of that split are asserted, and
 * the exact placeholder count is taken with an absent navigation block so the figure measures this
 * record's own redactions and not the sum of two records'.
 *
 * <p><strong>Transaction identifiers are sixteen characters.</strong> {@code COBIL00C} browses
 * backward from {@code HIGH-VALUES} to the highest existing key and adds one at
 * {@code app/cbl/COBIL00C.cbl:212-219}, seeding to one on an empty file at {@code :488}, so the
 * identifier is a sixteen-digit business key rather than a database sequence value.
 */
@DisplayName("BillPaymentResponse - the CB00 bill-payment screen contract")
class BillPaymentResponseRuleComplianceTest {

    /** A representative eleven-digit account identifier drawn from the seeded fixture range. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A representative sixteen-digit transaction identifier. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** The redaction placeholder the record's own rendering substitutes. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Builds a mapper configured exactly as {@code application.yml} configures the module's mapper.
     *
     * @return a mapper carrying the module's four Jackson settings
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
    private static JsonNode payloadOf(final BillPaymentResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Serializes a response to the exact characters that go on the wire.
     *
     * <p>Read as text rather than as a tree whenever the assertion is about how a number is
     * <em>written</em>, because parsing the payload back hands the question to the reader.
     *
     * @param response the response to render
     * @return the rendered payload as written
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static String wirePayloadOf(final BillPaymentResponse response)
            throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    /**
     * Builds a response positionally, laid out in rows of five so a component cannot silently drift
     * one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param accountId the account whose balance was paid
     * @param currentBalance the balance the account carried before the payment
     * @param confirm the one-character confirmation value
     * @param newTransactionId the identifier assigned to the payment transaction
     * @param errorMessage the operator message written to the error line
     * @param paymentAccepted whether the payment was posted
     * @param generalError whether the screen is reporting a whole-screen failure
     * @param focusScreenFieldId the screen field the cursor is placed on
     * @param navigationContext the carried conversation state
     * @return a response carrying the supplied values and the fixed screen furniture
     */
    private static BillPaymentResponse aResponse(
            final String accountId, final BigDecimal currentBalance,
            final String confirm, final String newTransactionId,
            final String errorMessage, final boolean paymentAccepted, final boolean generalError,
            final String focusScreenFieldId, final NavigationContext navigationContext) {
        return new BillPaymentResponse(
                accountId, currentBalance, confirm, newTransactionId,
                "CB00", "CardDemo", "07/19/22", "COBIL00C", "Bill Payment",
                "10:30:00", errorMessage, paymentAccepted, generalError, List.of(), focusScreenFieldId,
                "/api/menu/user", navigationContext);
    }

    /**
     * Builds a response carrying only the components a single assertion needs.
     *
     * @param accountId the account whose balance was paid
     * @param currentBalance the balance the account carried before the payment
     * @param errorMessage the operator message written to the error line
     * @return a response carrying the supplied values and nothing else
     */
    private static BillPaymentResponse aSparseResponse(final String accountId,
            final BigDecimal currentBalance, final String errorMessage) {
        return new BillPaymentResponse(
                accountId, currentBalance, null, null,
                null, null, null, null, null,
                null, errorMessage, false, false, List.of(), null,
                null, null);
    }

    /**
     * Reports whether a named component's accessor carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself, so
     * {@code RecordComponent.getAnnotation} would report nothing for every component.
     *
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final String componentName) {
        try {
            return BillPaymentResponse.class.getDeclaredMethod(componentName)
                    .getAnnotation(Size.class) != null;
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("no accessor declared for " + componentName, cause);
        }
    }

    /**
     * Reads the declared upper bound of a named component's accessor.
     *
     * @param componentName the record component whose accessor carries the annotation
     * @return the declared maximum length
     * @throws NoSuchMethodException when no such accessor is declared
     */
    private static int declaredMaximumLength(final String componentName)
            throws NoSuchMethodException {
        final Size size = BillPaymentResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    /**
     * Counts the occurrences of the redaction placeholder in a rendering.
     *
     * @param rendered the rendering to scan
     * @return the number of placeholders present
     */
    private static int placeholderCount(final String rendered) {
        return rendered.split(Pattern.quote(REDACTION_PLACEHOLDER), -1).length - 1;
    }

    // =============================================================================================

    @Nested
    @DisplayName("the two screen field identifiers")
    class TheTwoScreenFieldIdentifiers {

        @Test
        @DisplayName("the two identifiers are the BMS field names the legacy screen declares, so the "
                + "cursor lands on the field the operator has to correct")
        void theTwoIdentifiersAreTheBmsFieldNames() {
            assertThat(BillPaymentResponse.ACCOUNT_ID_FIELD_ID).isEqualTo("ACTIDIN");
            assertThat(BillPaymentResponse.CONFIRM_FIELD_ID).isEqualTo("CONFIRM");
        }

        @Test
        @DisplayName("both identifiers are seven characters of upper case, which is the BMS field-name "
                + "shape and the width the focus component declares")
        void bothIdentifiersAreSevenCharactersOfUpperCase() throws NoSuchMethodException {
            assertThat(BillPaymentResponse.ACCOUNT_ID_FIELD_ID).hasSize(7)
                    .isEqualTo(BillPaymentResponse.ACCOUNT_ID_FIELD_ID.toUpperCase(Locale.ROOT));
            assertThat(BillPaymentResponse.CONFIRM_FIELD_ID).hasSize(7)
                    .isEqualTo(BillPaymentResponse.CONFIRM_FIELD_ID.toUpperCase(Locale.ROOT));
            assertThat(declaredMaximumLength("focusScreenFieldId")).isEqualTo(7);
        }

        @Test
        @DisplayName("the two identifiers are distinct, so a focus instruction is never ambiguous")
        void theTwoIdentifiersAreDistinct() {
            assertThat(BillPaymentResponse.ACCOUNT_ID_FIELD_ID)
                    .isNotEqualTo(BillPaymentResponse.CONFIRM_FIELD_ID);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the fourteen operator messages")
    class TheFourteenOperatorMessages {

        @Test
        @DisplayName("the two field-level rejections are the empty-account and invalid-confirmation "
                + "sentences")
        void theTwoFieldLevelRejectionsArePublished() {
            assertThat(BillPaymentResponse.MSG_ACCT_ID_EMPTY)
                    .isEqualTo("Acct ID can NOT be empty...");
            assertThat(BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE)
                    .isEqualTo("Invalid value. Valid values are (Y/N)...");
        }

        @Test
        @DisplayName("the nothing-to-pay message is the refusal COBIL00C raises when the balance is "
                + "not positive, which is the one business rule the screen enforces")
        void theNothingToPayMessageIsPublished() {
            assertThat(BillPaymentResponse.MSG_NOTHING_TO_PAY)
                    .isEqualTo("You have nothing to pay...");
        }

        @Test
        @DisplayName("the confirmation prompt is the message shown before the payment is posted")
        void theConfirmationPromptIsPublished() {
            assertThat(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT)
                    .isEqualTo("Confirm to make a bill payment...");
        }

        @Test
        @DisplayName("the six lookup and update failures name the resource that failed, so an "
                + "operator can tell an absent account from an unreadable one")
        void theSixLookupAndUpdateFailuresNameTheirResource() {
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
        }

        @Test
        @DisplayName("the cross-reference failure still names the alternate index by its mainframe "
                + "term, because the operator-facing text is part of the preserved contract")
        void theCrossReferenceFailureNamesTheAlternateIndex() {
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX).contains("XREF AIX");
        }

        @Test
        @DisplayName("the two posting failures are the duplicate-key refusal and the general add "
                + "failure")
        void theTwoPostingFailuresArePublished() {
            assertThat(BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST)
                    .isEqualTo("Tran ID already exist...");
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION)
                    .isEqualTo("Unable to Add Bill pay Transaction...");
        }

        @Test
        @DisplayName("the success message is a prefix and a fragment, so the assigned transaction "
                + "identifier can be spliced in without a format string")
        void theSuccessMessageIsAPrefixAndAFragment() {
            assertThat(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX)
                    .isEqualTo("Payment successful. ");
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .isEqualTo(" Your Transaction ID is ");
        }

        @Test
        @DisplayName("the assembled success sentence still fits the seventy-eight character error "
                + "line once a sixteen-digit identifier has been spliced into it")
        void theAssembledSuccessSentenceStillFitsTheErrorLine() {
            final String assembled = BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX
                    + BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT + TRANSACTION_ID;

            assertThat(assembled)
                    .isEqualTo("Payment successful.  Your Transaction ID is " + TRANSACTION_ID);
            assertThat(assembled.length()).isLessThanOrEqualTo(78);
        }

        @Test
        @DisplayName("all fourteen messages are distinct and every one of them fits the error line")
        void allFourteenMessagesAreDistinctAndFit() {
            final List<String> messages = List.of(
                    BillPaymentResponse.MSG_ACCT_ID_EMPTY,
                    BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE,
                    BillPaymentResponse.MSG_NOTHING_TO_PAY,
                    BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT,
                    BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_ACCOUNT,
                    BillPaymentResponse.MSG_UNABLE_TO_UPDATE_ACCOUNT,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX,
                    BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                    BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX,
                    BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT,
                    BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST,
                    BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION);

            assertThat(messages).hasSize(14).doesNotHaveDuplicates();
            assertThat(messages).allSatisfy(message ->
                    assertThat(message.length()).isLessThanOrEqualTo(78));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the withholding rendering")
    class TheWithholdingRendering {

        @Test
        @DisplayName("the account identifier, the balance and the confirmation are withheld, so a "
                + "log record can never carry an account balance")
        void theSensitiveComponentsAreWithheld() {
            final String rendered = aResponse(ACCOUNT_ID, new BigDecimal("1234.56"),
                    "Y", TRANSACTION_ID, null, true, false, null,
                    NavigationContext.empty()).toString();

            assertThat(rendered).doesNotContain(ACCOUNT_ID);
            assertThat(rendered).doesNotContain("1234.56");
            assertThat(rendered).doesNotContain("confirm=Y");
        }

        @Test
        @DisplayName("the screen furniture, the routing and the assigned identifier are rendered in "
                + "full, because none of them is operator data")
        void theNonSensitiveComponentsAreRenderedInFull() {
            final String rendered = aResponse(ACCOUNT_ID, new BigDecimal("1.00"), "Y",
                    TRANSACTION_ID, BillPaymentResponse.MSG_NOTHING_TO_PAY, false, true,
                    BillPaymentResponse.CONFIRM_FIELD_ID, null).toString();

            assertThat(rendered).startsWith("BillPaymentResponse[");
            assertThat(rendered).contains("newTransactionId=" + TRANSACTION_ID,
                    "transactionName=CB00", "title01=CardDemo", "currentDate=07/19/22",
                    "programName=COBIL00C", "title02=Bill Payment", "currentTime=10:30:00",
                    "errorMessage=" + BillPaymentResponse.MSG_NOTHING_TO_PAY,
                    "paymentAccepted=false", "generalError=true",
                    "focusScreenFieldId=" + BillPaymentResponse.CONFIRM_FIELD_ID,
                    "nextRoute=/api/menu/user");
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("exactly four placeholders are present, counted with an absent navigation block "
                + "so the figure measures this record's redactions rather than two records' summed")
        void exactlyFourPlaceholdersArePresent() {
            final String rendered = aResponse(ACCOUNT_ID, new BigDecimal("10.00"), "Y",
                    TRANSACTION_ID, null, true, false, null, null).toString();

            assertThat(placeholderCount(rendered)).isEqualTo(4);
        }

        @Test
        @DisplayName("the navigation block is withheld too, so a carried conversation state cannot "
                + "leak an account identifier through a nested rendering")
        void theNavigationBlockIsWithheld() {
            final String rendered = aResponse(ACCOUNT_ID, new BigDecimal("1.00"), "Y",
                    TRANSACTION_ID, null, true, false, null, NavigationContext.empty()).toString();

            assertThat(rendered).contains("navigationContext=" + REDACTION_PLACEHOLDER);
            assertThat(rendered).doesNotContain("NavigationContext[");
        }

        @Test
        @DisplayName("an absent sensitive value is still rendered as a placeholder rather than as "
                + "null, so the rendering never discloses which values were present")
        void anAbsentSensitiveValueIsStillAPlaceholder() {
            final String rendered = aSparseResponse(null, null, null).toString();

            assertThat(placeholderCount(rendered)).isEqualTo(4);
            assertThat(rendered).contains("accountId=" + REDACTION_PLACEHOLDER,
                    "currentBalance=" + REDACTION_PLACEHOLDER,
                    "confirm=" + REDACTION_PLACEHOLDER,
                    "navigationContext=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the placeholder is a private constant, so no caller can build a rendering that "
                + "looks redacted without being redacted")
        void thePlaceholderIsAPrivateConstant() throws NoSuchFieldException {
            final int modifiers = BillPaymentResponse.class
                    .getDeclaredField("REDACTION_PLACEHOLDER").getModifiers();

            assertThat(Modifier.isPrivate(modifiers)).isTrue();
            assertThat(Modifier.isStatic(modifiers)).isTrue();
            assertThat(Modifier.isFinal(modifiers)).isTrue();
        }

        @Test
        @DisplayName("the rendering is not the payload, because the payload must carry the balance "
                + "the screen has to display")
        void theRenderingIsNotThePayload() throws JsonProcessingException {
            final BillPaymentResponse response = aSparseResponse(ACCOUNT_ID,
                    new BigDecimal("1234.56"), null);

            assertThat(response.toString()).doesNotContain("1234.56");
            assertThat(wirePayloadOf(response)).contains("\"currentBalance\":1234.56");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        /**
         * The response declares seventeen components, and the payment amount is not one of them.
         *
         * <p>{@code app/cbl/COBIL00C.cbl} pays the whole outstanding balance: the amount taken is the
         * balance the account carried, at line 198 and following, and the screen has no separate amount
         * field to render. A second monetary component would therefore have had to be either a copy of
         * the balance or a value the legacy screen never shows, and both are worse than its absence.
         * The absence is asserted here rather than left implicit, because a component that once existed
         * is exactly the kind of thing a client keeps reading for.
         */
        @Test
        @DisplayName("the response declares seventeen components in screen order - the account block, the "
                + "screen furniture and the routing block - and no separate payment amount")
        void theResponseDeclaresSeventeenComponentsInScreenOrder() {
            final List<String> declared = Arrays.stream(
                    BillPaymentResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("accountId", "currentBalance",
                    "confirm", "newTransactionId", "transactionName", "title01", "currentDate",
                    "programName", "title02", "currentTime", "errorMessage", "paymentAccepted",
                    "generalError", "fieldErrors", "focusScreenFieldId", "nextRoute", "navigationContext");
            assertThat(declared).hasSize(17);
            assertThat(declared).doesNotContain("paymentBalance");
        }

        @Test
        @DisplayName("exactly one component is monetary, and it is BigDecimal rather than any "
                + "floating-point type")
        void exactlyOneComponentIsMonetary() {
            final List<String> monetary = Arrays.stream(
                    BillPaymentResponse.class.getRecordComponents())
                    .filter(component -> component.getType().equals(BigDecimal.class))
                    .map(RecordComponent::getName).toList();

            assertThat(monetary).containsExactly("currentBalance");
            assertThat(Arrays.stream(BillPaymentResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName()).toList())
                    .doesNotContain("double", "float", "java.lang.Double", "java.lang.Float");
        }

        @Test
        @DisplayName("exactly eleven components carry a declared upper bound, and the five that do not "
                + "are the balance, the two flags, the route and the conversation state")
        void exactlyElevenComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(
                    BillPaymentResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(BillPaymentResponseRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).hasSize(11);
            assertThat(bounded).doesNotContain("currentBalance",
                    "paymentAccepted", "generalError", "nextRoute", "navigationContext");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final BillPaymentResponse response = aResponse(ACCOUNT_ID, new BigDecimal("1234.56"),
                    "Y", TRANSACTION_ID,
                    BillPaymentResponse.MSG_NOTHING_TO_PAY, true, false,
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID, NavigationContext.empty());

            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.currentBalance()).isEqualByComparingTo("1234.56");
            assertThat(response.confirm()).isEqualTo("Y");
            assertThat(response.newTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.transactionName()).isEqualTo("CB00");
            assertThat(response.title01()).isEqualTo("CardDemo");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.programName()).isEqualTo("COBIL00C");
            assertThat(response.title02()).isEqualTo("Bill Payment");
            assertThat(response.currentTime()).isEqualTo("10:30:00");
            assertThat(response.errorMessage())
                    .isEqualTo(BillPaymentResponse.MSG_NOTHING_TO_PAY);
            assertThat(response.paymentAccepted()).isTrue();
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId())
                    .isEqualTo(BillPaymentResponse.ACCOUNT_ID_FIELD_ID);
            assertThat(response.nextRoute()).isEqualTo("/api/menu/user");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a wholly absent response reports no violation, because every bound is an upper "
                + "bound")
        void aWhollyAbsentResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aSparseResponse(null, null, null)))
                        .isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "accountId,11", "confirm,1", "newTransactionId,16",
            "transactionName,4", "title01,40", "currentDate,8",
            "programName,8", "title02,40", "currentTime,8",
            "errorMessage,78", "focusScreenFieldId,7",
        })
        @DisplayName("every bounded component declares the legacy width of the field it renders")
        void everyBoundedComponentDeclaresItsLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @ParameterizedTest
        @CsvSource({
            "accountId,11", "confirm,1", "newTransactionId,16",
            "transactionName,4", "title01,40", "currentDate,8",
            "programName,8", "title02,40", "currentTime,8",
            "errorMessage,78", "focusScreenFieldId,7",
        })
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum))))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum + 1))))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString()).isEqualTo(componentName));
            }
        }

        /**
         * Builds a response carrying a single named component and nothing else.
         *
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a response carrying only that component
         */
        private BillPaymentResponse responseWith(final String componentName, final String value) {
            return switch (componentName) {
                case "accountId" -> new BillPaymentResponse(value, null, null, null, null,
                        null, null, null, null, null, null, false, false, List.of(), null, null, null);
                case "confirm" -> new BillPaymentResponse(null, null, value, null, null,
                        null, null, null, null, null, null, false, false, List.of(), null, null, null);
                case "newTransactionId" -> new BillPaymentResponse(null, null, null, value, null,
                        null, null, null, null, null, null, false, false, List.of(), null, null, null);
                case "transactionName" -> new BillPaymentResponse(null, null, null, null, value,
                        null, null, null, null, null, null, false, false, List.of(), null, null, null);
                case "title01" -> new BillPaymentResponse(null, null, null, null, null,
                        value, null, null, null, null, null, false, false, List.of(), null, null, null);
                case "currentDate" -> new BillPaymentResponse(null, null, null, null, null,
                        null, value, null, null, null, null, false, false, List.of(), null, null, null);
                case "programName" -> new BillPaymentResponse(null, null, null, null, null,
                        null, null, value, null, null, null, false, false, List.of(), null, null, null);
                case "title02" -> new BillPaymentResponse(null, null, null, null, null,
                        null, null, null, value, null, null, false, false, List.of(), null, null, null);
                case "currentTime" -> new BillPaymentResponse(null, null, null, null, null,
                        null, null, null, null, value, null, false, false, List.of(), null, null, null);
                case "errorMessage" -> new BillPaymentResponse(null, null, null, null, null,
                        null, null, null, null, null, value, false, false, List.of(), null, null, null);
                case "focusScreenFieldId" -> new BillPaymentResponse(null, null, null, null, null,
                        null, null, null, null, null, null, false, false, List.of(), value, null, null);
                default -> throw new IllegalArgumentException(
                        "no bounded component named " + componentName);
            };
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two responses built from identical values are equal and share a hash code")
        void twoResponsesBuiltFromIdenticalValuesAreEqual() {
            final BillPaymentResponse first = aResponse(ACCOUNT_ID, new BigDecimal("10.00"),
                    "Y", TRANSACTION_ID, null, true, false, null, null);
            final BillPaymentResponse second = aResponse(ACCOUNT_ID, new BigDecimal("10.00"),
                    "Y", TRANSACTION_ID, null, true, false, null, null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        /**
         * Scale is part of a monetary value, and the response refuses the wrong one rather than carrying it.
         *
         * <p>Two decimals is not a display preference here: the record field this component echoes stores
         * exactly two, so a balance of a different scale did not come from that field. The response
         * therefore refuses it at construction rather than accepting it and rendering it, which is the
         * choice that matters, because {@code BigDecimal} equality is scale-sensitive while its comparison
         * is not - a value of the wrong scale would compare equal to the right one everywhere a total was
         * checked and unequal everywhere an instance was, and no test of either alone would find it.</p>
         *
         * <p>Normalising the scale silently would have been the other way to make equality behave, and it
         * is worse: it would accept a value that did not come from the record field and make it
         * indistinguishable from one that did.</p>
         */
        @Test
        @DisplayName("a balance whose scale is not the record field's two decimal places is refused at "
                + "construction rather than normalised, because scale is part of a monetary value")
        void aBalanceOfTheWrongScaleIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> aSparseResponse(ACCOUNT_ID, new BigDecimal("10.0"), null))
                    .withMessageContaining("must carry scale 2")
                    .withMessageContaining("its scale is 1");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a scale-zero integral value is refused for the same reason")
                    .isThrownBy(() -> aSparseResponse(ACCOUNT_ID, BigDecimal.TEN, null));

            assertThat(new BigDecimal("10.00"))
                    .as("this is why the refusal matters: the two compare equal, so a wrong-scale value "
                            + "would pass every comparison and fail every equality")
                    .isEqualByComparingTo("10.0")
                    .isNotEqualTo(new BigDecimal("10.0"));
        }

        @Test
        @DisplayName("a well-scaled balance is accepted and keeps its scale, so the refusal above costs "
                + "nothing legitimate")
        void aWellScaledBalanceKeepsItsScale() {
            assertThat(aSparseResponse(ACCOUNT_ID, new BigDecimal("10.00"), null).currentBalance())
                    .isEqualTo(new BigDecimal("10.00"))
                    .satisfies(balance -> assertThat(balance.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("a monetary amount renders in plain notation with its scale intact, read from "
                + "the wire characters rather than from a parsed tree")
        void aMonetaryAmountRendersInPlainNotationWithItsScale() throws JsonProcessingException {
            final String wire = wirePayloadOf(aSparseResponse(ACCOUNT_ID,
                    new BigDecimal("1000000000.05"), null));

            assertThat(wire).contains("\"currentBalance\":1000000000.05");
            assertThat(wire).doesNotContain("E9").doesNotContain("E+");
        }

        @Test
        @DisplayName("a trailing-zero cent survives the wire as two digits, so a whole-dollar balance "
                + "is not rendered as though it had no cents")
        void aTrailingZeroCentSurvivesTheWire() throws JsonProcessingException {
            assertThat(wirePayloadOf(aSparseResponse(ACCOUNT_ID, new BigDecimal("42.10"), null)))
                    .contains("\"currentBalance\":42.10");
        }

        @Test
        @DisplayName("the payload omits every absent component and keeps the two primitive flags")
        void thePayloadOmitsAbsentComponentsAndKeepsTheFlags() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(ACCOUNT_ID, null, null));

            assertThat(payload.has("currentBalance")).isFalse();
            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.has("paymentAccepted")).isTrue();
            assertThat(payload.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(payload.has("generalError")).isTrue();
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper "
                + "unchanged, monetary scale included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final BillPaymentResponse original = aResponse(ACCOUNT_ID, new BigDecimal("1234.56"),
                    "Y", TRANSACTION_ID,
                    BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT, false, false,
                    BillPaymentResponse.CONFIRM_FIELD_ID, NavigationContext.empty());
            final BillPaymentResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), BillPaymentResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.currentBalance().scale()).isEqualTo(2);
        }
    }
}
