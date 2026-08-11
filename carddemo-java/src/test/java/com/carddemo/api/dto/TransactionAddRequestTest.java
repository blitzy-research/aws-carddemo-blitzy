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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.TransactionSourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionAddRequest}, the inbound contract of legacy CICS transaction
 * {@code CT02}.
 *
 * <p>A pure unit test. No application context is started, no container is launched, no connection is
 * opened and nothing is mocked: the type under test is a value carrier, so every property it has can
 * be established by constructing it, reading it back, serializing it and validating it.
 *
 * <h2>What this file pins, and why each one could be got wrong silently</h2>
 *
 * <p><strong>The map has no transaction identifier, and this contract must not acquire one.</strong>
 * A mechanical search of the symbolic map {@code app/cpy-bms/COTRN02.CPY} and of the mapset
 * {@code app/bms/COTRN02.bms} finds no transaction-identifier field of any kind; the map's first two
 * inputs are the account lookup key at line 60 and the card lookup key at line 66. The view map for
 * the neighbouring transaction is the contrast that makes the omission deliberate rather than
 * accidental: {@code app/cpy-bms/COTRN01.CPY} carries an identifier twice, once as an input at its
 * line 60 and once as an echoed output at its line 66. The add path derives the identifier instead,
 * server side, as the highest existing key plus one inside the same unit of work as the write. So the
 * assertions below prove the absence of any inbound identifier rather than asserting a shape for one,
 * and nothing here names a sequence, an identity column or a surrogate key.
 *
 * <p><strong>The amount is a twelve-character lexeme, not a number.</strong> The map declares the
 * amount item twelve characters wide at line 96 and the mapset declares its screen field unprotected
 * at that same width across lines 174 to 177. The program then applies two ordered lexical tests to
 * it: an emptiness test at {@code app/cbl/COTRN02C.cbl} line 278, and a four-position shape test that
 * ends at line 345 with a 39-character message spelling the required external form
 * {@code -99999999.99} - a sign position, eight integer digits, a decimal point and two fraction
 * digits. That template is a 3270 display convention and is never reproduced here as a Java format
 * string. Three states the program must report separately - an empty submission, an absent decimal
 * point and a sign character that is neither plus nor minus - are simply not expressible as a number,
 * and a decimal component would additionally accept exponent notation that no twelve-column screen
 * field could have produced. The tests below therefore hold the lexeme and prove that nothing in this
 * type parses, scales, rounds or reformats it.
 *
 * <p><strong>Every field rule is delegated, so this type must report nothing.</strong> The program
 * runs its checks as one ordered cascade in which the first failure ends the evaluation and the
 * operator sees exactly one message; the fourteen message-bearing stages relevant to this contract
 * are measurable in the source, at lines 184, 199, 213, 226, 254, 278, 314, 325, 345, 360, 401, 421,
 * 593 and 626. Bean Validation evaluates constraints in an unspecified order and reports every
 * violation at once, so a single presence, pattern or range constraint anywhere on this record would
 * surface a message from the wrong stage. The delegation tests below prove the absence of such
 * constraints behaviourally - by submitting values that each of them would have rejected and
 * requiring zero violations - and the width tests prove that the one constraint that does exist
 * measures a value without altering it.
 *
 * <p><strong>Every proof here is behavioural or by construction, and none inspects a
 * declaration.</strong> Absence of a component is established from the serialized property set,
 * immutability from repeated reads of the same instance, static typing from assignment to a declared
 * local, and constraint identity from the violation metadata the validation API itself publishes. No
 * declaration, member or annotation of the type under test is introspected anywhere in this file.
 *
 * <p>No legacy source text is reproduced; the citations point into a read-only reference tree that this
 * migration never copies from.
 */
@DisplayName("TransactionAddRequest :: inbound contract of legacy transaction CT02")
class TransactionAddRequestTest {

    /**
     * The fourteen operator-editable values of the symbolic map, each paired with the width the map
     * declares for it, in map declaration order.
     *
     * <p>The widths are read from {@code app/cpy-bms/COTRN02.CPY} and restated here so that this test
     * measures the contract against an independently transcribed expectation rather than against the
     * production declaration it is checking. Three of them deliberately disagree with the persisted
     * record of {@code app/cpy/CVTRA05Y.cpy}: the description is 60 here against 100 there, the
     * merchant name 30 against 50, the merchant city 25 against 50. The screen width is what an
     * operator may type, and it is the width this contract enforces.
     */
    enum Component {

        /** Account lookup key, map field {@code ACTIDIN} at line 60. */
        ACCOUNT_ID("accountId", 11),

        /** Card lookup key, map field {@code CARDNIN} at line 66. */
        CARD_NUMBER("cardNumber", 16),

        /** Transaction type code, map field {@code TTYPCD}. */
        TYPE_CODE("typeCode", 2),

        /** Transaction category code, map field {@code TCATCD}; four digits carried as text. */
        CATEGORY_CODE("categoryCode", 4),

        /** Transaction source, map field {@code TRNSRC}; carried raw and space padded. */
        TRANSACTION_SOURCE("transactionSource", 10),

        /** Transaction description, map field {@code TDESC}; the screen width, not the record width. */
        DESCRIPTION("description", 60),

        /** Transaction amount, map field {@code TRNAMT} at line 96; the typed external form. */
        AMOUNT("amount", 12),

        /** Origination date, map field {@code TORIGDT}; ten characters of opaque text. */
        ORIGINATION_DATE("originationDate", 10),

        /** Processing date, map field {@code TPROCDT}; ten characters of opaque text. */
        PROCESSING_DATE("processingDate", 10),

        /** Merchant identifier, map field {@code MID}; nine digits carried as text. */
        MERCHANT_ID("merchantId", 9),

        /** Merchant name, map field {@code MNAME}; the screen width, not the record width. */
        MERCHANT_NAME("merchantName", 30),

        /** Merchant city, map field {@code MCITY}; the screen width, not the record width. */
        MERCHANT_CITY("merchantCity", 25),

        /** Merchant postal code, map field {@code MZIP}. */
        MERCHANT_ZIP("merchantZip", 10),

        /** Confirmation value, map field {@code CONFIRM} at line 138; one character, not a flag. */
        CONFIRM("confirm", 1);

        private final String property;

        private final int width;

        Component(String property, int width) {
            this.property = property;
            this.width = width;
        }

        /** @return the record component and JSON property name this map field binds to */
        String property() {
            return property;
        }

        /** @return the width the symbolic map declares for this field */
        int width() {
            return width;
        }
    }

    /**
     * The sixteen components in the order the map declares its fields, followed by the two
     * interaction components the pseudo-conversational turn contributes.
     */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "accountId", "cardNumber", "typeCode", "categoryCode", "transactionSource", "description",
            "amount", "originationDate", "processingDate", "merchantId", "merchantName",
            "merchantCity", "merchantZip", "confirm", "keyAction", "navigationContext");

    /**
     * Property names that would betray a terminal data stream, a response-side value or an invented
     * identifier having leaked into the request contract.
     *
     * <p>Every entry is compared exactly and case sensitively, so no locale-sensitive case operation
     * is involved. The exhaustive property-set assertion is the primary proof; this list exists so
     * that a failure names what leaked instead of only reporting a set mismatch.
     */
    private static final List<String> FORBIDDEN_PROPERTIES = List.of(
            "id", "transactionId", "tranId", "trnId", "transactionIdentifier", "sequenceNumber",
            "errorMessage", "infoMessage", "message", "title", "title01", "title02", "screenTitle",
            "currentDate", "currentTime", "programName", "transactionName", "confirmed",
            "confirmFlag", "cvv", "cardVerificationValue", "securityCode", "maskedCardNumber",
            "filler", "attribute", "cursor", "highlight", "colour", "color", "position");

    /** Name endings that only a 3270 attribute, length halfword or flag byte would produce. */
    private static final List<String> FORBIDDEN_PROPERTY_ENDINGS = List.of(
            "Length", "Attribute", "Attr", "Colour", "Color", "Cursor", "Highlight", "Filler",
            "Flag", "Position", "Row", "Column", "Mask");

    private static final String ACCOUNT_KEY = "00000000011";
    private static final String CARD_KEY = "1234567890123456";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0002";
    private static final String DESCRIPTION = "GROCERIES AT STORE 42";
    private static final String ORIGINATION_DATE = "2022-06-10";
    private static final String PROCESSING_DATE = "2022-06-11";
    private static final String MERCHANT_ID = "000000042";
    private static final String MERCHANT_NAME = "SMITH HARDWARE";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101";
    private static final String CONFIRM_YES = "Y";

    /** The three ten-character source images the estate actually writes, padding included. */
    private static final String SOURCE_POINT_OF_SALE = "POS TERM  ";
    private static final String SOURCE_OPERATOR = "OPERATOR  ";
    private static final String SOURCE_SYSTEM = "System    ";

    /** A ten-character source image the estate never writes, used to prove nothing rejects it. */
    private static final String SOURCE_UNDECLARED = "MOBILE APP";

    /** Well-formed twelve-character amount lexemes, one per sign the operator may type. */
    private static final String AMOUNT_NEGATIVE = "-00000123.45";
    private static final String AMOUNT_POSITIVE = "+00000123.45";
    private static final String AMOUNT_ZERO = "-00000000.00";

    /** The placeholder the production rendering substitutes for each regulated component. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    /**
     * Opens the reference Bean Validation provider directly.
     *
     * <p>Deliberately the provider bootstrap rather than a framework-supplied validator bean: the
     * question these tests answer is what constraints the type itself declares, and routing through a
     * container would let container configuration influence the answer.
     */
    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Releases the provider so no factory outlives the class that opened it. */
    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    /**
     * Builds a request from the screen values supplied, leaving both interaction components absent.
     *
     * @param values the screen values to place, keyed by map field; an absent key stays {@code null}
     * @return the request carrying exactly those values
     */
    private static TransactionAddRequest of(Map<Component, String> values) {
        return of(values, null, null);
    }

    /**
     * Builds a request from the screen values supplied plus the two interaction components.
     *
     * <p>The single positional construction site in this file. Every other factory routes through it,
     * so no test depends on remembering the order of sixteen constructor arguments.
     *
     * @param values the screen values to place, keyed by map field; an absent key stays {@code null}
     * @param keyAction the attention key, or {@code null} for an absent one
     * @param navigationContext the echoed navigation state, or {@code null} for an absent one
     * @return the request carrying exactly those values
     */
    private static TransactionAddRequest of(Map<Component, String> values, KeyAction keyAction,
            NavigationContext navigationContext) {
        return new TransactionAddRequest(
                values.get(Component.ACCOUNT_ID),
                values.get(Component.CARD_NUMBER),
                values.get(Component.TYPE_CODE),
                values.get(Component.CATEGORY_CODE),
                values.get(Component.TRANSACTION_SOURCE),
                values.get(Component.DESCRIPTION),
                values.get(Component.AMOUNT),
                values.get(Component.ORIGINATION_DATE),
                values.get(Component.PROCESSING_DATE),
                values.get(Component.MERCHANT_ID),
                values.get(Component.MERCHANT_NAME),
                values.get(Component.MERCHANT_CITY),
                values.get(Component.MERCHANT_ZIP),
                values.get(Component.CONFIRM),
                keyAction,
                navigationContext);
    }

    /**
     * Builds a request in which exactly one screen value is supplied.
     *
     * @param component the map field to populate
     * @param value the value to place, which may be {@code null}, blank or over-wide
     * @return the request carrying only that value
     */
    private static TransactionAddRequest withOnly(Component component, String value) {
        Map<Component, String> values = new EnumMap<>(Component.class);
        values.put(component, value);
        return of(values);
    }

    /**
     * Builds a request in which every screen value is the same.
     *
     * @param value the value to place in all fourteen fields; may be {@code null}
     * @return the request carrying that value fourteen times
     */
    private static TransactionAddRequest withEveryScreenValue(String value) {
        Map<Component, String> values = new EnumMap<>(Component.class);
        for (Component component : Component.values()) {
            values.put(component, value);
        }
        return of(values);
    }

    /** @return a request in which nothing at all was supplied, which is a real first-entry state */
    private static TransactionAddRequest nothingSupplied() {
        return of(new EnumMap<>(Component.class));
    }

    /**
     * Reads one component back through its own accessor.
     *
     * <p>An exhaustive arrow switch over the enumeration, so adding a map field without teaching this
     * method about it is a compile error rather than a silently unexercised component.
     *
     * @param request the request to read
     * @param component the map field to read
     * @return the value that component holds
     */
    private static String read(TransactionAddRequest request, Component component) {
        return switch (component) {
            case ACCOUNT_ID -> request.accountId();
            case CARD_NUMBER -> request.cardNumber();
            case TYPE_CODE -> request.typeCode();
            case CATEGORY_CODE -> request.categoryCode();
            case TRANSACTION_SOURCE -> request.transactionSource();
            case DESCRIPTION -> request.description();
            case AMOUNT -> request.amount();
            case ORIGINATION_DATE -> request.originationDate();
            case PROCESSING_DATE -> request.processingDate();
            case MERCHANT_ID -> request.merchantId();
            case MERCHANT_NAME -> request.merchantName();
            case MERCHANT_CITY -> request.merchantCity();
            case MERCHANT_ZIP -> request.merchantZip();
            case CONFIRM -> request.confirm();
        };
    }

    /** @return an echoed navigation state whose every value sits inside its declared width */
    private static NavigationContext navigationState() {
        return new NavigationContext("CT02", "COTRN02C", "CT02", "COTRN02C", "TESTUSR1", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_KEY, "Y", CARD_KEY, "COTRN2A", "COTRN02");
    }

    /** @return a request in which all sixteen components are populated within their widths */
    private static TransactionAddRequest populated() {
        return new TransactionAddRequest(ACCOUNT_KEY, CARD_KEY, TYPE_CODE, CATEGORY_CODE,
                SOURCE_POINT_OF_SALE, DESCRIPTION, AMOUNT_NEGATIVE, ORIGINATION_DATE, PROCESSING_DATE,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CONFIRM_YES, KeyAction.ENTER,
                navigationState());
    }

    /**
     * Builds a mapper carrying the four serialization settings the module declares.
     *
     * <p>Assembled locally rather than borrowed, so that this file states the settings it depends on:
     * absent values omitted, dates never written as timestamps, unknown incoming properties tolerated
     * and decimal values written in plain notation. The two enumeration-related deserialization
     * settings the module also declares are applied as well, because one component of this contract
     * is an enumeration and a bare number must never select a constant by ordinal.
     *
     * @return a mapper equivalent to the module's declared configuration
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

    /**
     * Serializes a request and reads its properties back as a plain map.
     *
     * @param request the request to serialize
     * @return the property names and values that crossed the wire
     */
    private static Map<String, Object> payloadOf(TransactionAddRequest request) {
        ObjectMapper mapper = moduleEquivalentMapper();
        try {
            return mapper.readValue(mapper.writeValueAsString(request),
                    new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException failure) {
            throw new AssertionError("the request contract failed to serialize: " + failure, failure);
        }
    }

    /**
     * Serializes a request and returns the parsed tree, so node types can be asserted.
     *
     * @param request the request to serialize
     * @return the serialized form as a tree
     */
    private static JsonNode treeOf(TransactionAddRequest request) {
        ObjectMapper mapper = moduleEquivalentMapper();
        try {
            return mapper.readTree(mapper.writeValueAsString(request));
        } catch (JsonProcessingException failure) {
            throw new AssertionError("the request contract failed to serialize: " + failure, failure);
        }
    }

    /**
     * Serializes a request, reads it back and returns the result.
     *
     * @param request the request to round-trip
     * @return the request as it survives one serialization and one deserialization
     */
    private static TransactionAddRequest roundTrip(TransactionAddRequest request) {
        ObjectMapper mapper = moduleEquivalentMapper();
        try {
            return mapper.readValue(mapper.writeValueAsString(request), TransactionAddRequest.class);
        } catch (JsonProcessingException failure) {
            throw new AssertionError("the request contract failed to round-trip: " + failure, failure);
        }
    }

    /**
     * Reads a request out of a literal body.
     *
     * @param body the JSON body to bind
     * @return the bound request
     */
    private static TransactionAddRequest bind(String body) {
        try {
            return moduleEquivalentMapper().readValue(body, TransactionAddRequest.class);
        } catch (JsonProcessingException failure) {
            throw new AssertionError("a body the contract must tolerate was rejected: " + failure,
                    failure);
        }
    }

    /**
     * @param request the request to validate
     * @return every violation the reference provider reports for it
     */
    private static Set<ConstraintViolation<TransactionAddRequest>> violationsOf(
            TransactionAddRequest request) {
        return validator.validate(request);
    }

    /**
     * Asserts that exactly one violation was reported and returns it.
     *
     * @param request the request to validate
     * @return the single violation
     */
    private static ConstraintViolation<TransactionAddRequest> onlyViolation(
            TransactionAddRequest request) {
        Set<ConstraintViolation<TransactionAddRequest>> violations = violationsOf(request);
        assertThat(violations)
                .as("exactly one constraint may fire, because only a maximum-length bound exists")
                .hasSize(1);
        return violations.iterator().next();
    }

    /**
     * Returns the maximum length of the bound that fired, failing if anything else fired.
     *
     * <p>Read from the descriptor the validation API publishes for the violation, so the identity of
     * the constraint is established without inspecting any declaration.
     *
     * @param violation the violation to interrogate
     * @return the maximum length the firing bound declares
     */
    private static int firedLengthBound(ConstraintViolation<TransactionAddRequest> violation) {
        if (violation.getConstraintDescriptor().getAnnotation() instanceof Size bound) {
            return bound.max();
        }
        throw new AssertionError("expected a maximum-length bound to fire on "
                + violation.getPropertyPath() + " but the constraint that fired was "
                + violation.getMessageTemplate()
                + "; a presence, pattern, digit or range constraint on this contract would report a"
                + " message out of the order the legacy cascade reports its own");
    }

    /**
     * @param length the length required
     * @return a value of exactly that length, made of one repeated character
     */
    private static String filled(int length) {
        return "X".repeat(length);
    }

    /**
     * @param length the length required
     * @return a value of exactly that length whose first character is significant and whose
     *     remainder is trailing space, so trimming anywhere would be visible
     */
    private static String spacePadded(int length) {
        return length <= 1 ? "A" : "A" + " ".repeat(length - 1);
    }

    /**
     * @param length the length required
     * @return a value of exactly that length holding both cases, so folding anywhere would be visible
     */
    private static String mixedCase(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int position = 0; position < length; position++) {
            value.append(position % 2 == 0 ? 'a' : 'B');
        }
        return value.toString();
    }

    @Nested
    @DisplayName("what the map contributes, and what it must never acquire")
    class MapContract {

        @Test
        @DisplayName("binds the fourteen editable map values plus the two interaction components")
        void bindsExactlyTheComponentsTheMapContributes() {
            Set<String> names = payloadOf(populated()).keySet();

            assertThat(names)
                    .as("the contract is the fourteen unprotected map fields and nothing else")
                    .containsExactlyInAnyOrderElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("carries no transaction identifier, because the operator never supplies one")
        void carriesNoTransactionIdentifierComponent() {
            Set<String> names = payloadOf(populated()).keySet();

            assertThat(names)
                    .as("the identifier is derived server side as the highest existing key plus one")
                    .doesNotContainAnyElementsOf(FORBIDDEN_PROPERTIES);
            assertThat(names).noneMatch(name -> name.endsWith("TransactionId"));
            assertThat(names).noneMatch(name -> name.endsWith("Identifier"));
        }

        @Test
        @DisplayName("ignores an identifier a client tries to propose")
        void ignoresAProposedTransactionIdentifier() {
            String proposedIdentifier = "0000000000000001";
            String body = """
                    {"transactionId":"0000000000000001","accountId":"00000000011"}
                    """;

            TransactionAddRequest bound = bind(body);

            assertThat(bound)
                    .as("a proposed identifier binds to nothing at all")
                    .isEqualTo(withOnly(Component.ACCOUNT_ID, ACCOUNT_KEY));
            assertThat(bound.accountId()).isEqualTo(ACCOUNT_KEY);
            assertThat(bound.toString()).doesNotContain(proposedIdentifier);
            assertThat(payloadOf(bound).keySet()).containsExactly("accountId");
        }

        @Test
        @DisplayName("omits the header families and the error message, which are response side")
        void omitsEveryResponseSideFamily() {
            Set<String> names = payloadOf(populated()).keySet();

            assertThat(names).doesNotContain("errorMessage", "infoMessage", "title01", "title02",
                    "currentDate", "currentTime", "programName", "transactionName");
        }

        @Test
        @DisplayName("models none of the generated terminal stream plumbing")
        void modelsNoTerminalStreamArtefact() {
            Set<String> names = payloadOf(populated()).keySet();

            for (String ending : FORBIDDEN_PROPERTY_ENDINGS) {
                assertThat(names)
                        .as("a length halfword, flag byte or attribute byte would end in %s", ending)
                        .noneMatch(name -> name.endsWith(ending));
            }
        }

        @Test
        @DisplayName("carries the card number whole and declares no verification code")
        void carriesTheCardNumberWholeAndDeclaresNoVerificationCode() {
            Map<String, Object> payload = payloadOf(populated());

            assertThat(payload)
                    .as("the legacy design masks nothing, and this migration adds no masking")
                    .containsEntry("cardNumber", CARD_KEY);
            assertThat(payload.keySet()).doesNotContain("cvv", "cardVerificationValue", "securityCode",
                    "maskedCardNumber");
        }
    }

    @Nested
    @DisplayName("the two lookup keys")
    class LookupKeys {

        @Test
        @DisplayName("carries both keys as text at the widths the map declares")
        void carriesBothKeysAtTheirMapWidths() {
            TransactionAddRequest request = populated();

            String accountId = request.accountId();
            String cardNumber = request.cardNumber();

            assertThat(accountId).isEqualTo(ACCOUNT_KEY).hasSize(Component.ACCOUNT_ID.width());
            assertThat(cardNumber).isEqualTo(CARD_KEY).hasSize(Component.CARD_NUMBER.width());
        }

        @Test
        @DisplayName("keeps every leading zero on the account key rather than shortening it")
        void keepsLeadingZerosOnTheAccountKey() {
            TransactionAddRequest request = withOnly(Component.ACCOUNT_ID, "00000000001");

            assertThat(request.accountId()).isEqualTo("00000000001").isNotEqualTo("1");
            assertThat(payloadOf(request)).containsEntry("accountId", "00000000001");
        }

        @Test
        @DisplayName("expresses an absent key as null and never as a substitute")
        void expressesAnAbsentKeyAsNull() {
            TransactionAddRequest request = nothingSupplied();

            assertThat(request.accountId()).isNull();
            assertThat(request.cardNumber()).isNull();
            assertThat(payloadOf(request).keySet()).doesNotContain("accountId", "cardNumber");
        }

        @Test
        @DisplayName("carries both keys unchanged when both arrive, applying no precedence here")
        void appliesNoPrecedenceWhenBothKeysArrive() {
            Map<Component, String> values = new EnumMap<>(Component.class);
            values.put(Component.ACCOUNT_ID, ACCOUNT_KEY);
            values.put(Component.CARD_NUMBER, CARD_KEY);

            TransactionAddRequest request = of(values);

            assertThat(request.accountId()).isSameAs(ACCOUNT_KEY);
            assertThat(request.cardNumber()).isSameAs(CARD_KEY);
        }

        @Test
        @DisplayName("keeps a key that is not numeric at all, and reports nothing about it")
        void keepsANonNumericKeyIntact() {
            Map<Component, String> values = new EnumMap<>(Component.class);
            values.put(Component.ACCOUNT_ID, "ABCDEFGHIJK");
            values.put(Component.CARD_NUMBER, "ABCDEFGHIJKLMNOP");

            TransactionAddRequest request = of(values);

            assertThat(request.accountId()).isEqualTo("ABCDEFGHIJK");
            assertThat(request.cardNumber()).isEqualTo("ABCDEFGHIJKLMNOP");
            assertThat(violationsOf(request))
                    .as("the numeric checks are ordered service stages, not declarative constraints")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("codes and identifiers stay text")
    class TextIdentifiers {

        @Test
        @DisplayName("keeps the category code four characters wide with its leading zeros")
        void keepsTheCategoryCodeFourCharactersWide() {
            TransactionAddRequest request = withOnly(Component.CATEGORY_CODE, CATEGORY_CODE);

            String categoryCode = request.categoryCode();

            assertThat(categoryCode).isEqualTo("0002").hasSize(Component.CATEGORY_CODE.width());
            assertThat(categoryCode).isNotEqualTo("2");
            assertThat(payloadOf(request)).containsEntry("categoryCode", "0002");
        }

        @Test
        @DisplayName("keeps the merchant identifier nine characters wide with its leading zeros")
        void keepsTheMerchantIdentifierNineCharactersWide() {
            TransactionAddRequest request = withOnly(Component.MERCHANT_ID, MERCHANT_ID);

            String merchantId = request.merchantId();

            assertThat(merchantId).isEqualTo("000000042").hasSize(Component.MERCHANT_ID.width());
            assertThat(merchantId).isNotEqualTo("42");
        }

        @Test
        @DisplayName("keeps the type code two characters wide")
        void keepsTheTypeCodeTwoCharactersWide() {
            String typeCode = withOnly(Component.TYPE_CODE, TYPE_CODE).typeCode();

            assertThat(typeCode).isEqualTo("01").hasSize(Component.TYPE_CODE.width());
            assertThat(typeCode).isNotEqualTo("1");
        }

        @Test
        @DisplayName("serializes every identifier as JSON text and never as a number")
        void serializesEveryIdentifierAsText() {
            JsonNode tree = treeOf(populated());

            for (String property : List.of("accountId", "cardNumber", "typeCode", "categoryCode",
                    "merchantId", "merchantZip")) {
                assertThat(tree.get(property).isTextual())
                        .as("%s is a fixed-width identifier whose leading zeros are contract",
                                property)
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("the transaction source stays raw")
    class RawTransactionSource {

        @Test
        @DisplayName("agrees with the domain vocabulary about the field width")
        void agreesWithTheDomainVocabularyAboutTheWidth() {
            assertThat(Component.TRANSACTION_SOURCE.width())
                    .isEqualTo(TransactionSourceType.VALUE_LENGTH);
        }

        @Test
        @DisplayName("keeps each image the estate writes padded to its full width")
        void keepsEachEstateImagePadded() {
            for (String image : List.of(SOURCE_POINT_OF_SALE, SOURCE_OPERATOR, SOURCE_SYSTEM)) {
                assertThat(TransactionSourceType.fromValue(image))
                        .as("%s must be an image the estate genuinely writes", image)
                        .isPresent();

                String carried = withOnly(Component.TRANSACTION_SOURCE, image).transactionSource();

                assertThat(carried).isEqualTo(image).hasSize(Component.TRANSACTION_SOURCE.width());
            }
        }

        @Test
        @DisplayName("keeps the mixed-case system image exactly as the estate writes it")
        void keepsTheMixedCaseSystemImage() {
            String carried = withOnly(Component.TRANSACTION_SOURCE, SOURCE_SYSTEM).transactionSource();

            assertThat(carried).isEqualTo(SOURCE_SYSTEM).isNotEqualTo("SYSTEM    ");
        }

        @Test
        @DisplayName("keeps an image the estate never writes, which nothing here may reject")
        void keepsAnUndeclaredImage() {
            assertThat(TransactionSourceType.fromValue(SOURCE_UNDECLARED))
                    .as("the chosen image must genuinely be outside the declared vocabulary")
                    .isEmpty();

            TransactionAddRequest request = withOnly(Component.TRANSACTION_SOURCE, SOURCE_UNDECLARED);

            assertThat(request.transactionSource()).isEqualTo(SOURCE_UNDECLARED);
            assertThat(violationsOf(request))
                    .as("the legacy program checked only that this field is not blank")
                    .isEmpty();
        }

        @Test
        @DisplayName("neither trims a short image nor pads it back up")
        void neitherTrimsNorPadsAnImage() {
            String trimmedImage = "POS TERM";
            assertThat(TransactionSourceType.fromValue(trimmedImage))
                    .as("a trimmed image resolves to nothing, which is why trimming would be a defect")
                    .isEmpty();

            String carried = withOnly(Component.TRANSACTION_SOURCE, trimmedImage).transactionSource();

            assertThat(carried).isEqualTo(trimmedImage).hasSize(trimmedImage.length());
        }

        @Test
        @DisplayName("folds no case on the way through")
        void foldsNoCase() {
            String lowerCaseImage = "pos term  ";

            String carried = withOnly(Component.TRANSACTION_SOURCE, lowerCaseImage)
                    .transactionSource();

            assertThat(carried).isEqualTo(lowerCaseImage);
        }

        @Test
        @DisplayName("crosses the wire as the raw image and never as a domain constant name")
        void crossesTheWireAsTheRawImage() {
            Map<String, Object> payload = payloadOf(populated());

            assertThat(payload).containsEntry("transactionSource", SOURCE_POINT_OF_SALE);
            assertThat(payload.get("transactionSource"))
                    .isNotEqualTo(TransactionSourceType.POS_TERM.name());
        }
    }

    @Nested
    @DisplayName("the amount is a twelve-character lexeme, not a number")
    class AmountLexeme {

        @Test
        @DisplayName("is carried as text, so binding it can introduce no approximate numeric type")
        void isCarriedAsText() {
            String amount = populated().amount();

            assertThat(amount).isEqualTo(AMOUNT_NEGATIVE).hasSize(Component.AMOUNT.width());
            JsonNode rendered = treeOf(populated()).get("amount");
            assertThat(rendered.isTextual()).isTrue();
            assertThat(rendered.isNumber()).isFalse();
        }

        @Test
        @DisplayName("keeps a negative, a positive and a zero lexeme byte for byte")
        void keepsEveryWellFormedLexemeByteForByte() {
            for (String lexeme : List.of(AMOUNT_NEGATIVE, AMOUNT_POSITIVE, AMOUNT_ZERO)) {
                TransactionAddRequest request = withOnly(Component.AMOUNT, lexeme);

                assertThat(request.amount()).isEqualTo(lexeme).hasSize(Component.AMOUNT.width());
                assertThat(roundTrip(request).amount()).isEqualTo(lexeme);
                assertThat(violationsOf(request)).isEmpty();
            }
        }

        @Test
        @DisplayName("keeps a blank and an empty submission distinct from an absent one")
        void keepsBlankEmptyAndAbsentDistinct() {
            String blank = " ".repeat(Component.AMOUNT.width());
            TransactionAddRequest blankSubmission = withOnly(Component.AMOUNT, blank);
            TransactionAddRequest emptySubmission = withOnly(Component.AMOUNT, "");

            assertThat(blankSubmission.amount()).isEqualTo(blank).hasSize(Component.AMOUNT.width());
            assertThat(emptySubmission.amount()).isEmpty();
            assertThat(nothingSupplied().amount()).isNull();
            assertThat(blankSubmission).isNotEqualTo(emptySubmission);
            assertThat(emptySubmission).isNotEqualTo(nothingSupplied());
            assertThat(roundTrip(blankSubmission).amount())
                    .as("the blank must survive the wire untrimmed to stay reportable")
                    .isEqualTo(blank);
            assertThat(violationsOf(blankSubmission)).isEmpty();
            assertThat(violationsOf(emptySubmission)).isEmpty();
        }

        @Test
        @DisplayName("keeps a malformed lexeme intact so the format stage can report it")
        void keepsAMalformedLexemeIntact() {
            for (String malformed : List.of("1234567890AB", "00000123,45", "12345678.9",
                    "*00000123.45", "ABCDEFGHIJKL")) {
                TransactionAddRequest request = withOnly(Component.AMOUNT, malformed);

                assertThat(request.amount()).isEqualTo(malformed);
                assertThat(roundTrip(request).amount()).isEqualTo(malformed);
                assertThat(violationsOf(request))
                        .as("%s must reach the ordered format stage rather than a binding failure",
                                malformed)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("carries exponent notation as inert text rather than as a magnitude")
        void carriesExponentNotationAsInertText() {
            TransactionAddRequest request = withOnly(Component.AMOUNT, "1e100000");

            assertThat(request.amount()).isEqualTo("1e100000");
            assertThat(treeOf(request).get("amount").isTextual()).isTrue();
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("introduces no grouping, currency symbol or scientific rendering on the wire")
        void introducesNoGroupingOrCurrencyRenderingOnTheWire() {
            String rendered = treeOf(populated()).get("amount").asText();

            assertThat(rendered).isEqualTo(AMOUNT_NEGATIVE).doesNotContain(",", "$", "e", "E");
        }

        @Test
        @DisplayName("rejects a lexeme wider than the screen field, and only for its length")
        void rejectsALexemeWiderThanTheScreenField() {
            String overWide = filled(Component.AMOUNT.width() + 1);

            ConstraintViolation<TransactionAddRequest> violation =
                    onlyViolation(withOnly(Component.AMOUNT, overWide));

            assertThat(violation.getPropertyPath()).hasToString("amount");
            assertThat(firedLengthBound(violation)).isEqualTo(Component.AMOUNT.width());
            assertThat(violation.getInvalidValue())
                    .as("the bound measures the value and never alters it")
                    .isEqualTo(overWide);
        }
    }

    @Nested
    @DisplayName("both dates stay opaque text")
    class OpaqueDates {

        @Test
        @DisplayName("carries each date as ten characters of text")
        void carriesEachDateAsText() {
            TransactionAddRequest request = populated();

            String originationDate = request.originationDate();
            String processingDate = request.processingDate();

            assertThat(originationDate).isEqualTo(ORIGINATION_DATE)
                    .hasSize(Component.ORIGINATION_DATE.width());
            assertThat(processingDate).isEqualTo(PROCESSING_DATE)
                    .hasSize(Component.PROCESSING_DATE.width());
            JsonNode tree = treeOf(request);
            assertThat(tree.get("originationDate").isTextual()).isTrue();
            assertThat(tree.get("processingDate").isTextual()).isTrue();
        }

        @Test
        @DisplayName("keeps an all-space date unchanged rather than absent, empty or trimmed")
        void keepsAnAllSpaceDateUnchanged() {
            String blankDate = " ".repeat(Component.ORIGINATION_DATE.width());
            Map<Component, String> values = new EnumMap<>(Component.class);
            values.put(Component.ORIGINATION_DATE, blankDate);
            values.put(Component.PROCESSING_DATE, blankDate);

            TransactionAddRequest request = of(values);

            assertThat(request.originationDate()).isEqualTo(blankDate)
                    .hasSize(Component.ORIGINATION_DATE.width());
            assertThat(request.processingDate()).isEqualTo(blankDate);
            assertThat(roundTrip(request).originationDate())
                    .as("blank date text is ordinary data in this estate")
                    .isEqualTo(blankDate);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("keeps a date the calendar would reject, because that stage is delegated")
        void keepsACalendarInvalidDateUnchanged() {
            for (String invalidDate : List.of("2022-02-31", "0000-00-00", "9999-99-99")) {
                TransactionAddRequest request = withOnly(Component.ORIGINATION_DATE, invalidDate);

                assertThat(request.originationDate()).isEqualTo(invalidDate);
                assertThat(violationsOf(request))
                        .as("%s is answered by the shared date utility, not by a constraint",
                                invalidDate)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("keeps a differently shaped date unchanged rather than reformatting it")
        void keepsADifferentlyShapedDateUnchanged() {
            String reorderedDate = "06/10/2022";

            TransactionAddRequest request = withOnly(Component.PROCESSING_DATE, reorderedDate);

            assertThat(request.processingDate()).isEqualTo(reorderedDate);
            assertThat(roundTrip(request).processingDate()).isEqualTo(reorderedDate);
            assertThat(violationsOf(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the confirmation value is one character, not a two-state flag")
    class ConfirmationSlot {

        @Test
        @DisplayName("carries a single character unchanged")
        void carriesASingleCharacterUnchanged() {
            for (String keystroke : List.of("Y", "N")) {
                String carried = withOnly(Component.CONFIRM, keystroke).confirm();

                assertThat(carried).isEqualTo(keystroke).hasSize(Component.CONFIRM.width());
            }
        }

        @Test
        @DisplayName("folds no case, so a lower-case keystroke stays lower case")
        void foldsNoCase() {
            for (String keystroke : List.of("y", "n")) {
                TransactionAddRequest request = withOnly(Component.CONFIRM, keystroke);

                assertThat(request.confirm()).isEqualTo(keystroke);
                assertThat(roundTrip(request).confirm()).isEqualTo(keystroke);
            }
        }

        @Test
        @DisplayName("accepts a space and an empty value, both unchanged")
        void acceptsASpaceAndAnEmptyValue() {
            TransactionAddRequest spaceSubmission = withOnly(Component.CONFIRM, " ");
            TransactionAddRequest emptySubmission = withOnly(Component.CONFIRM, "");

            assertThat(spaceSubmission.confirm()).isEqualTo(" ").hasSize(Component.CONFIRM.width());
            assertThat(emptySubmission.confirm()).isEmpty();
            assertThat(roundTrip(spaceSubmission).confirm()).isEqualTo(" ");
            assertThat(violationsOf(spaceSubmission)).isEmpty();
            assertThat(violationsOf(emptySubmission)).isEmpty();
        }

        @Test
        @DisplayName("keeps a value that is neither yes nor no, which a flag could not carry")
        void keepsAValueThatIsNeitherYesNorNo() {
            TransactionAddRequest request = withOnly(Component.CONFIRM, "Q");

            assertThat(request.confirm()).isEqualTo("Q");
            assertThat(violationsOf(request))
                    .as("the unexpected-value message is an ordered service stage")
                    .isEmpty();
        }

        @Test
        @DisplayName("exposes no derived two-state flag anywhere in the contract")
        void exposesNoDerivedTwoStateFlag() {
            Map<String, Object> payload = payloadOf(populated());

            assertThat(payload.keySet()).doesNotContain("confirmed", "confirmFlag", "confirmation");
            assertThat(payload.get("confirm")).isInstanceOf(String.class).isEqualTo(CONFIRM_YES);
            assertThat(treeOf(populated()).get("confirm").isBoolean()).isFalse();
        }

        @Test
        @DisplayName("rejects more than one character, and only for its length")
        void rejectsMoreThanOneCharacter() {
            ConstraintViolation<TransactionAddRequest> violation =
                    onlyViolation(withOnly(Component.CONFIRM, "YY"));

            assertThat(violation.getPropertyPath()).hasToString("confirm");
            assertThat(firedLengthBound(violation)).isEqualTo(Component.CONFIRM.width());
        }
    }

    @Nested
    @DisplayName("every field rule is delegated, so this contract reports nothing")
    class DelegatedValidation {

        @Test
        @DisplayName("an entirely absent submission violates nothing")
        void anEntirelyAbsentSubmissionViolatesNothing() {
            assertThat(violationsOf(nothingSupplied()))
                    .as("a presence constraint anywhere would fire here and pre-empt the cascade")
                    .isEmpty();
        }

        @Test
        @DisplayName("an entirely empty submission violates nothing")
        void anEntirelyEmptySubmissionViolatesNothing() {
            assertThat(violationsOf(withEveryScreenValue("")))
                    .as("an emptiness constraint anywhere would fire here")
                    .isEmpty();
        }

        @Test
        @DisplayName("an entirely blank submission violates nothing")
        void anEntirelyBlankSubmissionViolatesNothing() {
            assertThat(violationsOf(withEveryScreenValue(" ")))
                    .as("a not-blank constraint anywhere would fire here")
                    .isEmpty();
        }

        @Test
        @DisplayName("a malformed amount violates nothing, because its format stage is ordered")
        void aMalformedAmountViolatesNothing() {
            assertThat(violationsOf(withOnly(Component.AMOUNT, "NOT A SUM"))).isEmpty();
            assertThat(violationsOf(withOnly(Component.AMOUNT, "00000123,45"))).isEmpty();
        }

        @Test
        @DisplayName("a non-numeric identifier violates nothing")
        void aNonNumericIdentifierViolatesNothing() {
            assertThat(violationsOf(withOnly(Component.ACCOUNT_ID, "ABCDEFGHIJK"))).isEmpty();
            assertThat(violationsOf(withOnly(Component.MERCHANT_ID, "MERCHANT."))).isEmpty();
        }

        @Test
        @DisplayName("a category code outside the reference table violates nothing")
        void anOutOfRangeCategoryCodeViolatesNothing() {
            assertThat(violationsOf(withOnly(Component.CATEGORY_CODE, "9999"))).isEmpty();
            assertThat(violationsOf(withOnly(Component.CATEGORY_CODE, "ZZZZ"))).isEmpty();
            assertThat(violationsOf(withOnly(Component.TYPE_CODE, "ZZ"))).isEmpty();
        }

        @Test
        @DisplayName("a transaction source the estate never writes violates nothing")
        void anUndeclaredTransactionSourceViolatesNothing() {
            assertThat(violationsOf(withOnly(Component.TRANSACTION_SOURCE, SOURCE_UNDECLARED)))
                    .isEmpty();
        }

        @Test
        @DisplayName("a fully populated submission violates nothing")
        void aFullyPopulatedSubmissionViolatesNothing() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Component.class)
        @DisplayName("accepts a value at the map width and returns it byte for byte")
        void acceptsAValueAtTheMapWidth(Component component) {
            String atWidth = filled(component.width());

            TransactionAddRequest request = withOnly(component, atWidth);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(read(request, component)).isEqualTo(atWidth).hasSize(component.width());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Component.class)
        @DisplayName("reports exactly one length violation one character beyond the map width")
        void reportsOneLengthViolationBeyondTheMapWidth(Component component) {
            String overWide = filled(component.width() + 1);

            ConstraintViolation<TransactionAddRequest> violation =
                    onlyViolation(withOnly(component, overWide));

            assertThat(violation.getPropertyPath()).hasToString(component.property());
            assertThat(firedLengthBound(violation)).isEqualTo(component.width());
            assertThat(violation.getInvalidValue())
                    .as("the bound measures the value and never alters it")
                    .isEqualTo(overWide);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Component.class)
        @DisplayName("keeps trailing space padding rather than trimming it")
        void keepsTrailingSpacePadding(Component component) {
            String padded = spacePadded(component.width());

            TransactionAddRequest request = withOnly(component, padded);

            assertThat(read(request, component)).isEqualTo(padded).hasSize(component.width());
            assertThat(read(roundTrip(request), component)).isEqualTo(padded);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Component.class)
        @DisplayName("keeps both cases rather than folding either")
        void keepsBothCasesUnfolded(Component component) {
            String mixed = mixedCase(component.width());

            TransactionAddRequest request = withOnly(component, mixed);

            assertThat(read(request, component)).isEqualTo(mixed);
            assertThat(read(roundTrip(request), component)).isEqualTo(mixed);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Component.class)
        @DisplayName("keeps a value shorter than the field rather than padding it up")
        void keepsAShortValueUnpadded(Component component) {
            TransactionAddRequest request = withOnly(component, "A");

            assertThat(read(request, component)).isEqualTo("A").hasSize(1);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("validates the nested navigation state transitively, and nothing of its own")
        void validatesTheNestedNavigationStateTransitively() {
            NavigationContext overWideNavigation = new NavigationContext("CT02X", null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);

            ConstraintViolation<TransactionAddRequest> violation = onlyViolation(
                    of(new EnumMap<>(Component.class), KeyAction.ENTER, overWideNavigation));

            assertThat(violation.getPropertyPath()).hasToString("navigationContext.fromTransactionId");
            assertThat(firedLengthBound(violation))
                    .isEqualTo(NavigationContext.TRANSACTION_ID_LENGTH);
        }

        @Test
        @DisplayName("the nested cascade introduces no rule of its own")
        void theNestedCascadeIntroducesNoRuleOfItsOwn() {
            assertThat(violationsOf(
                    of(new EnumMap<>(Component.class), KeyAction.ENTER, NavigationContext.empty())))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("returns every component exactly as supplied, copying and normalising nothing")
        void returnsEveryComponentExactlyAsSupplied() {
            Map<Component, String> values = new EnumMap<>(Component.class);
            for (Component component : Component.values()) {
                values.put(component, spacePadded(component.width()));
            }
            NavigationContext navigation = navigationState();

            TransactionAddRequest request = of(values, KeyAction.PFK03, navigation);

            for (Component component : Component.values()) {
                assertThat(read(request, component))
                        .as("%s must be the very value supplied", component.property())
                        .isSameAs(values.get(component));
            }
            assertThat(request.keyAction()).isSameAs(KeyAction.PFK03);
            assertThat(request.navigationContext()).isSameAs(navigation);
        }

        @Test
        @DisplayName("reads the same on every read, because no component can be replaced")
        void readsTheSameOnEveryRead() {
            TransactionAddRequest request = populated();

            for (Component component : Component.values()) {
                assertThat(read(request, component)).isSameAs(read(request, component));
            }
            assertThat(request.keyAction()).isSameAs(request.keyAction());
            assertThat(request.navigationContext()).isSameAs(request.navigationContext());
        }

        @Test
        @DisplayName("compares by value across every component")
        void comparesByValue() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(nothingSupplied()).isEqualTo(nothingSupplied())
                    .hasSameHashCodeAs(nothingSupplied());
            assertThat(populated()).isNotEqualTo(nothingSupplied());
            assertThat(populated()).isNotEqualTo(new Object());

            for (Component component : Component.values()) {
                assertThat(withOnly(component, "A"))
                        .as("%s must participate in equality", component.property())
                        .isNotEqualTo(withOnly(component, "B"));
            }
        }

        @Test
        @DisplayName("tolerates null in every component, including both interaction components")
        void toleratesNullInEveryComponent() {
            TransactionAddRequest request = withEveryScreenValue(null);

            for (Component component : Component.values()) {
                assertThat(read(request, component)).isNull();
            }
            assertThat(request.keyAction()).isNull();
            assertThat(request.navigationContext()).isNull();
            assertThat(request.toString()).isNotBlank();
            assertThat(violationsOf(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the attention key")
    class AttentionKey {

        @Test
        @DisplayName("has no default, so an absent key stays absent")
        void hasNoDefault() {
            assertThat(nothingSupplied().keyAction())
                    .as("an absent key is absent, not an implied enter")
                    .isNull();
            assertThat(payloadOf(nothingSupplied()).keySet()).doesNotContain("keyAction");
        }

        @Test
        @DisplayName("round-trips by constant name for every key the translator can produce")
        void roundTripsByConstantName() {
            for (KeyAction key : KeyAction.values()) {
                TransactionAddRequest request = of(new EnumMap<>(Component.class), key, null);

                assertThat(payloadOf(request)).containsEntry("keyAction", key.name());
                assertThat(roundTrip(request).keyAction()).isSameAs(key);
            }
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the two keys, the description, the amount and all four merchant values")
        void withholdsEveryRegulatedValue() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(ACCOUNT_KEY, CARD_KEY, DESCRIPTION, AMOUNT_NEGATIVE,
                    MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP);
            assertThat(rendered).contains(REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("retains the codes, the dates and the interaction state")
        void retainsCodesDatesAndInteractionState() {
            String rendered = populated().toString();

            assertThat(rendered).contains(TYPE_CODE, CATEGORY_CODE, SOURCE_POINT_OF_SALE,
                    ORIGINATION_DATE, PROCESSING_DATE, KeyAction.ENTER.name());
        }

        @Test
        @DisplayName("names every component once, in the order the map declares its fields")
        void namesEveryComponentInMapOrder() {
            String rendered = populated().toString();

            assertThat(rendered).startsWith("TransactionAddRequest[").endsWith("]");
            int previousPosition = -1;
            for (String name : COMPONENTS_IN_MAP_ORDER) {
                int position = rendered.indexOf(name + "=");
                assertThat(position)
                        .as("%s must be rendered, and in map order", name)
                        .isGreaterThan(previousPosition);
                previousPosition = position;
            }
        }

        @Test
        @DisplayName("delegates the navigation state, which withholds its own identifiers")
        void delegatesTheNavigationState() {
            String rendered = populated().toString();

            assertThat(rendered).contains("NavigationContext[").contains("COTRN02C");
        }

        @Test
        @DisplayName("changes nothing an accessor returns and nothing on the wire")
        void changesNothingTransported() {
            TransactionAddRequest request = populated();

            assertThat(request.toString()).isNotBlank();

            assertThat(request.cardNumber()).isEqualTo(CARD_KEY);
            assertThat(request.amount()).isEqualTo(AMOUNT_NEGATIVE);
            assertThat(payloadOf(request))
                    .containsEntry("cardNumber", CARD_KEY)
                    .containsEntry("amount", AMOUNT_NEGATIVE)
                    .containsEntry("description", DESCRIPTION);
        }

        @Test
        @DisplayName("renders an instance with nothing populated without failing")
        void rendersAnEmptyInstance() {
            String rendered = nothingSupplied().toString();

            assertThat(rendered).startsWith("TransactionAddRequest[")
                    .contains("keyAction=null")
                    .contains("navigationContext=null")
                    .endsWith("]");
        }
    }

    @Nested
    @DisplayName("wire contract")
    class WireContract {

        @Test
        @DisplayName("omits every absent value rather than writing it as null")
        void omitsEveryAbsentValue() {
            assertThat(payloadOf(nothingSupplied()))
                    .as("absent-value omission is the module-wide inclusion setting")
                    .isEmpty();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property")
        void toleratesAnUnknownIncomingProperty() {
            String body = """
                    {"accountId":"00000000011","operatorTerminalId":"TRM00042","confirm":"Y"}
                    """;

            TransactionAddRequest bound = bind(body);

            assertThat(bound.accountId()).isEqualTo(ACCOUNT_KEY);
            assertThat(bound.confirm()).isEqualTo(CONFIRM_YES);
        }

        @Test
        @DisplayName("round-trips every component byte for byte")
        void roundTripsEveryComponentByteForByte() {
            TransactionAddRequest request = populated();

            TransactionAddRequest returned = roundTrip(request);

            assertThat(returned).isEqualTo(request);
            for (Component component : Component.values()) {
                assertThat(read(returned, component)).isEqualTo(read(request, component));
            }
            assertThat(returned.keyAction()).isSameAs(KeyAction.ENTER);
            assertThat(returned.navigationContext()).isEqualTo(navigationState());
        }
    }
}
