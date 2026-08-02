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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CardUpdateResponse}, the response body of legacy transaction {@code CCUP}.
 *
 * <p>This is a pure unit test. It starts no application context, opens no connection and launches no
 * container: it constructs the type directly and, where the wire shape is the thing under test,
 * serialises it with a local mapper configured by hand to match the four serialisation settings the
 * module declares in {@code application.yml}. Nothing here shares state with any other test.</p>
 *
 * <p>The field inventory and every width come from the generated symbolic map
 * {@code app/cpy-bms/COCRDUP.CPY}, whose output redefinition begins at line 121 and declares the
 * fifteen modelled items between lines 128 and 212. Two further items in that redefinition, at lines
 * 218 and 224, are function-key legend furniture and are deliberately absent from the response; a
 * test below asserts their absence rather than trusting it. The protection attributes come from the
 * mapset {@code app/bms/COCRDUP.bms}, where the account identifier is protected at line 84 and the
 * expiry day is hidden, protected and carried through at line 142. The behaviour and every message
 * literal come from {@code app/cbl/COCRDUPC.cbl}.</p>
 *
 * <p><strong>The message block is the contract, and it is deliberately inconsistent.</strong> The
 * program declares its operator texts between lines 135 and 214, with two further upper-case filter
 * texts at lines 745 and 789 and one more at line 1023. Several of them look like defects and are
 * not: one confirmation text has no space after its full stop while the neighbouring failure text
 * does, the file-error prefix ends in a space, the exit text carries trailing pad spaces, one text
 * has a four-dot ellipsis, and the concurrency text spells a word as two words. Normalising any of
 * them breaks the external contract, so each is asserted here individually and by its own name, in
 * addition to the exhaustive table check. The expected values below are restated independently of
 * the class under test, which is the whole point: a table that read its expectations out of the
 * class it is testing would assert nothing.</p>
 *
 * <p>Absences are asserted as deliberately as presences. The abend text belongs to
 * {@code AbendException} and must not be declared a second time here, and no <em>readable</em>
 * version number, entity tag, timestamp or fetched-image snapshot may exist, because each of those is
 * a value a client could assert for itself.</p>
 *
 * <p>The concurrency proof, by contrast, is asserted <strong>present</strong>. The program detected a
 * concurrent change by comparing the image it had fetched when the screen was built against the record
 * it re-read under lock, refreshing that image and leaving the write path at line 1518; that image
 * travelled with the conversation in the work area the program returns with the screen at line 550,
 * which is why the migrated screen has to hand out an equivalent. This screen is both the presenting
 * and the confirming screen, so the sealed proof is returned here and echoed back on
 * {@code CardUpdateRequest}. Its value is opaque, so what is asserted about it is that it round-trips
 * untouched, that it is serialised, and that it never appears in a diagnostic rendering - never that
 * it has any particular shape. The only thing this response says to an operator about the condition
 * remains the single text declared at line 208.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("CardUpdateResponse :: card-update response contract of legacy transaction CCUP")
class CardUpdateResponseTest {

    /**
     * The fifteen modelled item widths, in the order the output redefinition declares them in
     * {@code app/cpy-bms/COCRDUP.CPY}: lines 128, 134, 140, 146, 152, 158, 164, 170, 176, 182, 188,
     * 194, 200, 206 and 212. Restated here so the assertion does not read the class under test.
     */
    private static final List<Integer> MAP_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 11, 16, 50, 1, 2, 4, 2, 40, 80);

    /**
     * The fifteen mapped components in map order, followed by the five response-shaping components and
     * the concurrency proof. A change to this list is a change to the REST contract and must be a
     * deliberate one.
     */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02",
            "currentTime", "accountId", "cardNumber", "embossedName", "activeStatus", "expiryMonth",
            "expiryYear", "expiryDay", "informationMessage", "errorMessage", "generalError",
            "fieldErrors", "focusScreenFieldId", "nextRoute", "navigationContext",
            "concurrencyToken");

    /**
     * Every operator text the program declares, keyed by the constant that must carry it and valued
     * by the exact characters, restated independently of {@link CardUpdateResponse.Messages}.
     */
    private static final Map<String, String> EXPECTED_MESSAGES = expectedMessages();

    /** The bounded screen-field identifier the response uses as its focus hint. */
    private static final String SCREEN_EXPIRY_MONTH = "EXPMON";

    /** A representative account identifier at the full declared width of eleven. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A representative card number at the full declared width of sixteen. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** An embossed name carrying an embedded space, which the alphabetic rule admits. */
    private static final String EMBOSSED_NAME = "MARY ANN";

    /**
     * A stand-in for a minted concurrency proof.
     *
     * <p>Deliberately an arbitrary opaque string rather than anything a real minting service would
     * produce. This type neither reads, parses, validates nor bounds the proof - it carries it - so a
     * realistic value would test the minting service instead of this contract. What is asserted is
     * that whatever is handed in comes back out byte for byte, reaches the wire, and never reaches a
     * diagnostic rendering. Its characters are chosen to be visibly not a card value, so that a
     * disclosure assertion below cannot pass by coincidence.
     */
    private static final String CONCURRENCY_TOKEN = "CCUP1-sealed-proof-stand-in";

    /** The properties RFC 7807 would introduce, none of which this response may expose. */
    private static final List<String> PROBLEM_DETAIL_PROPERTIES =
            List.of("type", "title", "status", "detail", "instance");

    private static Map<String, String> expectedMessages() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FILE_ERROR_PREFIX", "File Error: ");
        expected.put("FOUND_CARDS_FOR_ACCOUNT", "Details of selected card shown above");
        expected.put("PROMPT_FOR_SEARCH_KEYS", "Please enter Account and Card Number");
        expected.put("PROMPT_FOR_CHANGES", "Update card details presented above.");
        expected.put("PROMPT_FOR_CONFIRMATION", "Changes validated.Press F5 to save");
        expected.put("CONFIRM_UPDATE_SUCCESS", "Changes committed to database");
        expected.put("INFORM_FAILURE", "Changes unsuccessful. Please try again");
        expected.put("EXIT_MESSAGE", "PF03 pressed.Exiting              ");
        expected.put("PROMPT_FOR_ACCOUNT", "Account number not provided");
        expected.put("PROMPT_FOR_CARD", "Card number not provided");
        expected.put("PROMPT_FOR_NAME", "Card name not provided");
        expected.put("NAME_MUST_BE_ALPHA", "Card name can only contain alphabets and spaces");
        expected.put("NO_SEARCH_CRITERIA_RECEIVED", "No input received");
        expected.put("NO_CHANGES_DETECTED", "No change detected with respect to values fetched.");
        expected.put("ACCOUNT_MUST_BE_NON_ZERO_11_DIGITS",
                "Account number must be a non zero 11 digit number");
        expected.put("CARD_MUST_BE_16_DIGITS", "Card number if supplied must be a 16 digit number");
        expected.put("CARD_STATUS_MUST_BE_YES_NO", "Card Active Status must be Y or N");
        expected.put("CARD_EXPIRY_MONTH_NOT_VALID", "Card expiry month must be between 1 and 12");
        expected.put("CARD_EXPIRY_YEAR_NOT_VALID", "Invalid card expiry year");
        expected.put("DID_NOT_FIND_ACCOUNT_IN_CARD_DATA",
                "Did not find this account in cards database");
        expected.put("DID_NOT_FIND_ACCOUNT_CARD_COMBINATION",
                "Did not find cards for this search condition");
        expected.put("COULD_NOT_LOCK_FOR_UPDATE", "Could not lock record for update");
        expected.put("DATA_WAS_CHANGED", "Record changed by some one else. Please review");
        expected.put("LOCKED_BUT_UPDATE_FAILED", "Update of record failed");
        expected.put("CARD_DATA_READ_ERROR", "Error reading Card Data File");
        expected.put("CODING_TO_BE_DONE", "Looks Good.... so far");
        expected.put("ACCOUNT_FILTER_MUST_BE_11_DIGITS",
                "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        expected.put("CARD_FILTER_MUST_BE_16_DIGITS",
                "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        expected.put("UNEXPECTED_DATA_SCENARIO", "UNEXPECTED DATA SCENARIO");
        return Map.copyOf(expected);
    }

    /**
     * Supplies a mapper carrying the four serialisation settings the module declares in
     * {@code application.yml}, obtained from {@link JsonContractSupport#declaredSettingsMapper()} so
     * that those settings exist in exactly one place in the test tree.
     *
     * <p>A payload asserted through it is the payload this type takes <em>under those settings</em>,
     * which is not the same claim as the payload a client receives from a deployed instance.
     * {@link ApplicationJsonContractTest} establishes the correspondence by comparing a mapper taken
     * from a real context against this very factory.</p>
     *
     * @return a mapper carrying the module's four declared serialisation settings
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonContractSupport.declaredSettingsMapper();
    }

    private static JsonNode payloadOf(CardUpdateResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /** Reads a declared message constant by name, so the table check needs no hand-written switch. */
    private static String declaredMessage(String constantName) throws ReflectiveOperationException {
        Field field = CardUpdateResponse.Messages.class.getDeclaredField(constantName);
        return (String) field.get(null);
    }

    /**
     * A declarative route label. It deliberately carries no account identifier and no card number:
     * the navigation layer owns the route vocabulary, and a route that embedded a regulated value
     * would carry it into every diagnostic rendering of this response no matter what the response
     * itself withholds. That boundary is asserted rather than assumed further below.
     */
    private static final String ROUTE = "/api/cards/update";

    /** The informational shape: every fetched value present, no error of either kind. */
    private static CardUpdateResponse informationalResponse(String informationMessage) {
        return new CardUpdateResponse("CCUP", "Tracking Card Demo", "08/01/26", "COCRDUPC",
                "Update Card Details", "16:00:00", ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y",
                "01", "2026", "31", informationMessage, ROUTE,
                NavigationContext.empty().withFirstEntry(), CONCURRENCY_TOKEN);
    }

    /** The re-entry shape: a summary text, the explicit failure flag and one decorated field. */
    private static CardUpdateResponse reEntryResponse(List<ErrorResponse.FieldError> fieldErrors) {
        return new CardUpdateResponse("CCUP", "Tracking Card Demo", "08/01/26", "COCRDUPC",
                "Update Card Details", "16:00:00", ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y",
                "13", "2026", "31", null,
                CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID, true, fieldErrors,
                SCREEN_EXPIRY_MONTH, ROUTE, NavigationContext.empty().withReEntry(),
                CONCURRENCY_TOKEN);
    }

    private static ErrorResponse.FieldError expiryMonthInvalid() {
        return new ErrorResponse.FieldError("expiryMonth", SCREEN_EXPIRY_MONTH,
                ErrorResponse.FieldState.INVALID,
                CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID);
    }

    @Test
    @DisplayName("Every operator text the program declares is carried byte for byte, and the holder "
            + "declares no text beyond them")
    void messagesAreCarriedByteForByte() throws ReflectiveOperationException {
        for (Map.Entry<String, String> entry : EXPECTED_MESSAGES.entrySet()) {
            String actual = declaredMessage(entry.getKey());
            assertThat(actual)
                    .as("message constant %s", entry.getKey())
                    .isEqualTo(entry.getValue())
                    .hasSameSizeAs(entry.getValue());
        }
        List<String> declared = Arrays.stream(CardUpdateResponse.Messages.class.getDeclaredFields())
                .filter(field -> field.getType() == String.class)
                .map(Field::getName)
                .toList();
        assertThat(declared).containsExactlyInAnyOrderElementsOf(EXPECTED_MESSAGES.keySet());
        assertThat(declared).hasSize(29);
    }

    @Test
    @DisplayName("The file-error prefix keeps the trailing space that is part of its value and leaks "
            + "no status code, table name, schema name or query text")
    void fileErrorPrefixKeepsItsTrailingSpaceAndLeaksNothing() {
        String prefix = CardUpdateResponse.Messages.FILE_ERROR_PREFIX;

        assertThat(prefix).isEqualTo("File Error: ").hasSize(12).endsWith(" ");
        assertThat(prefix.stripTrailing()).hasSize(11);
        assertThat(prefix).doesNotContainPattern("[0-9]");
        assertThat(prefix.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("select", "insert", "update ", "table", "schema", "carddemo.");
    }

    @Test
    @DisplayName("The confirmation text has no space after its full stop while the failure text does, "
            + "and that inconsistency is the contract rather than a defect")
    void spacingAfterTheFullStopDiffersBetweenTheConfirmationAndFailureTexts() {
        String confirmation = CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION;
        String failure = CardUpdateResponse.Messages.INFORM_FAILURE;

        assertThat(confirmation).isEqualTo("Changes validated.Press F5 to save");
        assertThat(confirmation.charAt(confirmation.indexOf('.') + 1)).isEqualTo('P');
        assertThat(confirmation).doesNotContain(". ");

        assertThat(failure).isEqualTo("Changes unsuccessful. Please try again");
        assertThat(failure.charAt(failure.indexOf('.') + 1)).isEqualTo(' ');
        assertThat(failure).contains(". ");
    }

    @Test
    @DisplayName("The confirmation text names PF5 as the save gate, matching the legend the mapset "
            + "displays")
    void confirmationTextNamesTheSaveKey() {
        assertThat(CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION).contains("F5");
    }

    @Test
    @DisplayName("The exit text keeps its fourteen trailing pad spaces, because the padding is part "
            + "of the declared value and not decoration")
    void exitTextKeepsItsTrailingPadSpaces() {
        String exit = CardUpdateResponse.Messages.EXIT_MESSAGE;

        assertThat(exit).isEqualTo("PF03 pressed.Exiting              ").hasSize(34);
        assertThat(exit.stripTrailing()).isEqualTo("PF03 pressed.Exiting").hasSize(20);
        assertThat(exit.length() - exit.stripTrailing().length()).isEqualTo(14);
        assertThat(exit.charAt(exit.indexOf('.') + 1)).isEqualTo('E');
    }

    @Test
    @DisplayName("The validation-passed text carries a four-dot ellipsis, not three and not two")
    void validationPassedTextCarriesFourDots() {
        String text = CardUpdateResponse.Messages.CODING_TO_BE_DONE;

        assertThat(text).isEqualTo("Looks Good.... so far").contains("....");
        assertThat(text).doesNotContain(".....");
        assertThat(text.chars().filter(character -> character == '.').count()).isEqualTo(4L);
    }

    @Test
    @DisplayName("The concurrency text spells the pronoun as two words, and it is the only thing this "
            + "response says about a record changed elsewhere")
    void concurrencyTextSpellsSomeOneAsTwoWords() {
        String text = CardUpdateResponse.Messages.DATA_WAS_CHANGED;

        assertThat(text).isEqualTo("Record changed by some one else. Please review");
        assertThat(text).contains("some one").doesNotContain("someone");
    }

    @Test
    @DisplayName("The expiry-month text names the range as 1 and 12 rather than zero-padding it, and "
            + "the expiry-year text names no range at all")
    void expiryTextsNameTheirRangesExactlyAsDeclared() {
        assertThat(CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID)
                .isEqualTo("Card expiry month must be between 1 and 12")
                .contains("1 and 12")
                .doesNotContain("01 and 12");

        assertThat(CardUpdateResponse.Messages.CARD_EXPIRY_YEAR_NOT_VALID)
                .isEqualTo("Invalid card expiry year")
                .doesNotContain("1950", "2099");
    }

    @Test
    @DisplayName("The two upper-case filter texts have no space after the comma and both read A "
            + "rather than AN before the digit count")
    void upperCaseFilterTextsKeepTheirIrregularPunctuationAndArticle() {
        String accountFilter = CardUpdateResponse.Messages.ACCOUNT_FILTER_MUST_BE_11_DIGITS;
        String cardFilter = CardUpdateResponse.Messages.CARD_FILTER_MUST_BE_16_DIGITS;

        assertThat(accountFilter).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertThat(cardFilter).isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        assertThat(accountFilter).contains(",IF").doesNotContain(", IF");
        assertThat(cardFilter).contains(",IF").doesNotContain(", IF");
        assertThat(accountFilter).contains("A 11").doesNotContain("AN 11");
        assertThat(cardFilter).contains("A 16");
        assertThat(accountFilter).isEqualTo(accountFilter.toUpperCase(java.util.Locale.ROOT));
        assertThat(cardFilter).isEqualTo(cardFilter.toUpperCase(java.util.Locale.ROOT));
    }

    @Test
    @DisplayName("The account-width text is declared once even though the program declares the same "
            + "characters at two separate lines")
    void twiceDeclaredAccountWidthTextIsCarriedOnce() throws ReflectiveOperationException {
        assertThat(CardUpdateResponse.Messages.ACCOUNT_MUST_BE_NON_ZERO_11_DIGITS)
                .isEqualTo("Account number must be a non zero 11 digit number");

        List<String> carryingTheSameText =
                Arrays.stream(CardUpdateResponse.Messages.class.getDeclaredFields())
                        .filter(field -> field.getType() == String.class)
                        .map(Field::getName)
                        .filter(name -> {
                            try {
                                return declaredMessage(name)
                                        .equals("Account number must be a non zero 11 digit number");
                            } catch (ReflectiveOperationException cause) {
                                throw new AssertionError(cause);
                            }
                        })
                        .toList();

        assertThat(carryingTheSameText).containsExactly("ACCOUNT_MUST_BE_NON_ZERO_11_DIGITS");
    }

    @Test
    @DisplayName("The lock text is the single generic one this program declares, and neither of the "
            + "account-update program's two record-specific variants appears here")
    void lockTextIsTheSingleGenericOne() {
        assertThat(CardUpdateResponse.Messages.COULD_NOT_LOCK_FOR_UPDATE)
                .isEqualTo("Could not lock record for update");

        assertThat(EXPECTED_MESSAGES.values())
                .noneMatch(text -> text.contains("lock account record"))
                .noneMatch(text -> text.contains("lock customer record"));
    }

    @Test
    @DisplayName("The alphabetic rule for the embossed name admits spaces, which is what the "
            + "blank-and-measure idiom the program uses actually permits")
    void alphabeticRuleTextAdmitsSpaces() {
        assertThat(CardUpdateResponse.Messages.NAME_MUST_BE_ALPHA)
                .isEqualTo("Card name can only contain alphabets and spaces")
                .contains("and spaces");
    }

    @Test
    @DisplayName("The abend text is not declared here, because it belongs to the abend exception and "
            + "a second declaration would create a second source of truth")
    void abendTextIsNotDeclaredHere() {
        assertThat(EXPECTED_MESSAGES.values()).doesNotContain("UNEXPECTED ABEND OCCURRED.");
        assertThat(EXPECTED_MESSAGES.values())
                .noneMatch(text -> text.contains("UNEXPECTED ABEND"));
    }

    @Test
    @DisplayName("Every declared text fits the eighty-character error field it may be routed to, so "
            + "no text can overflow the widest carrier on this map")
    void everyDeclaredTextFitsTheWidestCarrier() {
        assertThat(EXPECTED_MESSAGES.values())
                .allSatisfy(text -> assertThat(text.length())
                        .isLessThanOrEqualTo(CardUpdateResponse.ERROR_MESSAGE_LENGTH));
    }

    @Test
    @DisplayName("The message holder is a constants holder and cannot be instantiated")
    void messageHolderCannotBeInstantiated() throws ReflectiveOperationException {
        Constructor<CardUpdateResponse.Messages> constructor =
                CardUpdateResponse.Messages.class.getDeclaredConstructor();

        assertThat(constructor.canAccess(null)).isFalse();
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isNotNull();
        assertThat(Modifier.isFinal(CardUpdateResponse.Messages.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("The fifteen published widths equal the widths the symbolic map declares, in the "
            + "order the map declares them")
    void publishedWidthsEqualTheMapWidths() {
        List<Integer> published = List.of(
                CardUpdateResponse.TRANSACTION_NAME_LENGTH,
                CardUpdateResponse.SCREEN_TITLE_LENGTH,
                CardUpdateResponse.CURRENT_DATE_LENGTH,
                CardUpdateResponse.PROGRAM_NAME_LENGTH,
                CardUpdateResponse.SCREEN_TITLE_LENGTH,
                CardUpdateResponse.CURRENT_TIME_LENGTH,
                CardUpdateResponse.ACCOUNT_ID_LENGTH,
                CardUpdateResponse.CARD_NUMBER_LENGTH,
                CardUpdateResponse.EMBOSSED_NAME_LENGTH,
                CardUpdateResponse.ACTIVE_STATUS_LENGTH,
                CardUpdateResponse.EXPIRY_MONTH_LENGTH,
                CardUpdateResponse.EXPIRY_YEAR_LENGTH,
                CardUpdateResponse.EXPIRY_DAY_LENGTH,
                CardUpdateResponse.INFORMATION_MESSAGE_LENGTH,
                CardUpdateResponse.ERROR_MESSAGE_LENGTH);

        assertThat(published).containsExactlyElementsOf(MAP_WIDTHS);
    }

    @Test
    @DisplayName("This map's message widths are forty and eighty, not the forty-five and seventy-eight "
            + "the card-list and account maps use, and the difference is honoured not normalised")
    void messageWidthsAreThisMapsWidthsAndNotTheOtherMaps() {
        assertThat(CardUpdateResponse.INFORMATION_MESSAGE_LENGTH).isEqualTo(40).isNotEqualTo(45);
        assertThat(CardUpdateResponse.ERROR_MESSAGE_LENGTH).isEqualTo(80).isNotEqualTo(78);
    }

    @Test
    @DisplayName("The response declares twenty-one components - fifteen mapped, five response-shaping "
            + "and the concurrency proof last - and the two function-key legend items the map also "
            + "carries are deliberately absent")
    void componentsAreDeclaredInMapOrderWithoutTheFunctionKeyLegend() {
        List<String> declared = Arrays.stream(CardUpdateResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER).hasSize(21);
        assertThat(declared).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("fkey"));
        assertThat(declared).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("legend"));
        assertThat(declared).endsWith("concurrencyToken");
        assertThat(declared.subList(0, 15))
                .as("the fifteen mapped components precede every response-shaping one")
                .doesNotContain("concurrencyToken");
    }

    @Test
    @DisplayName("Every carried value is characters, the failure flag is the only primitive, and no "
            + "component is numeric, temporal, an enum or a version marker")
    void everyCarriedValueIsCharactersAndNothingIsNumericOrTemporal() {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        for (RecordComponent component : CardUpdateResponse.class.getRecordComponents()) {
            types.put(component.getName(), component.getType());
        }

        assertThat(types.get("generalError")).isEqualTo(boolean.class);
        assertThat(types.get("fieldErrors")).isEqualTo(List.class);
        assertThat(types.get("navigationContext")).isEqualTo(NavigationContext.class);
        assertThat(types.get("concurrencyToken"))
                .as("the concurrency proof is opaque characters, never a number, a timestamp or a "
                        + "structured type a client could take apart")
                .isEqualTo(String.class);
        types.entrySet().stream()
                .filter(entry -> !List.of("generalError", "fieldErrors", "navigationContext")
                        .contains(entry.getKey()))
                .forEach(entry -> assertThat(entry.getValue())
                        .as("component %s", entry.getKey())
                        .isEqualTo(String.class));

        assertThat(types.values()).doesNotContain(int.class, long.class, Integer.class, Long.class,
                Double.class, java.math.BigDecimal.class, java.time.LocalDate.class);
        assertThat(types.keySet()).noneMatch(name -> name.contains("version"))
                .noneMatch(name -> name.contains("etag"))
                .noneMatch(name -> name.contains("timestamp"))
                .noneMatch(name -> name.contains("beforeImage"))
                .noneMatch(name -> name.contains("cursor"));
    }

    @Test
    @DisplayName("Bounds are declared as maximum lengths only and match the map widths on both the "
            + "field and its accessor, and no other constraint annotation is present")
    void boundsAreMaximumLengthsOnlyAndAgreeAcrossFieldAndAccessor()
            throws ReflectiveOperationException {
        Map<String, Integer> expectedBounds = new LinkedHashMap<>();
        expectedBounds.put("transactionName", 4);
        expectedBounds.put("title01", 40);
        expectedBounds.put("currentDate", 8);
        expectedBounds.put("programName", 8);
        expectedBounds.put("title02", 40);
        expectedBounds.put("currentTime", 8);
        expectedBounds.put("accountId", 11);
        expectedBounds.put("cardNumber", 16);
        expectedBounds.put("embossedName", 50);
        expectedBounds.put("activeStatus", 1);
        expectedBounds.put("expiryMonth", 2);
        expectedBounds.put("expiryYear", 4);
        expectedBounds.put("expiryDay", 2);
        expectedBounds.put("informationMessage", 40);
        expectedBounds.put("errorMessage", 80);
        expectedBounds.put("focusScreenFieldId", 7);

        for (Map.Entry<String, Integer> entry : expectedBounds.entrySet()) {
            Field field = CardUpdateResponse.class.getDeclaredField(entry.getKey());
            Method accessor = CardUpdateResponse.class.getDeclaredMethod(entry.getKey());
            assertThat(field.getAnnotation(Size.class)).as("field %s", entry.getKey()).isNotNull();
            assertThat(field.getAnnotation(Size.class).max()).isEqualTo(entry.getValue());
            assertThat(accessor.getAnnotation(Size.class)).isNotNull();
            assertThat(accessor.getAnnotation(Size.class).max()).isEqualTo(entry.getValue());
        }

        List<String> annotationNames =
                Arrays.stream(CardUpdateResponse.class.getDeclaredFields())
                        .flatMap(field -> Arrays.stream(field.getAnnotations()))
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .distinct()
                        .toList();
        assertThat(annotationNames).containsExactly("Size");
    }

    @Test
    @DisplayName("The route is deliberately unbounded, because it is a REST label rather than a "
            + "fixed-width screen field")
    void routeCarriesNoWidthBound() throws NoSuchFieldException {
        assertThat(CardUpdateResponse.class.getDeclaredField("nextRoute").getAnnotation(Size.class))
                .isNull();
    }

    @Test
    @DisplayName("An over-long value is reported by the bound but the value itself is left exactly as "
            + "it was supplied, because a bound reports and never alters")
    void anOverLongValueIsReportedWithoutBeingAltered() {
        String tooLongForOneCharacter = "YN";
        CardUpdateResponse response = new CardUpdateResponse("CCUP", null, null, null, null, null,
                ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, tooLongForOneCharacter, "01", "2026", "31",
                null, "/api/cards/1", NavigationContext.empty(), CONCURRENCY_TOKEN);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<CardUpdateResponse>> violations = validator.validate(response);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("activeStatus");
        }

        assertThat(response.activeStatus()).isEqualTo(tooLongForOneCharacter).hasSize(2);
    }

    @Test
    @DisplayName("A response carrying only values within their bounds reports no violation, including "
            + "an out-of-vocabulary status character which must round-trip rather than be rejected")
    void anOutOfVocabularyStatusCharacterRoundTripsWithoutViolation() {
        CardUpdateResponse response = new CardUpdateResponse("CCUP", null, null, null, null, null,
                ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "X", "01", "2026", "31", null,
                "/api/cards/1", NavigationContext.empty(), CONCURRENCY_TOKEN);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(response)).isEmpty();
        }

        assertThat(response.activeStatus()).isEqualTo("X");
    }

    @Test
    @DisplayName("Every supplied value is returned exactly as it was supplied, with no shortening, "
            + "space-filling, re-casing or re-rendering of any component")
    void suppliedValuesAreReturnedUnaltered() {
        NavigationContext context = NavigationContext.empty().withReEntry();
        List<ErrorResponse.FieldError> errors = List.of(expiryMonthInvalid());
        CardUpdateResponse response = new CardUpdateResponse("CCUP", " leading and trailing ",
                "08/01/26", "COCRDUPC", "Update Card Details", "16:00:00", ACCOUNT_ID, CARD_NUMBER,
                EMBOSSED_NAME, "Y", "01", "2026", "31",
                CardUpdateResponse.Messages.PROMPT_FOR_CHANGES,
                CardUpdateResponse.Messages.EXIT_MESSAGE, true, errors, SCREEN_EXPIRY_MONTH,
                "/api/cards/4111111111111111", context, CONCURRENCY_TOKEN);

        assertThat(response.transactionName()).isEqualTo("CCUP");
        assertThat(response.title01()).isEqualTo(" leading and trailing ");
        assertThat(response.currentDate()).isEqualTo("08/01/26");
        assertThat(response.programName()).isEqualTo("COCRDUPC");
        assertThat(response.title02()).isEqualTo("Update Card Details");
        assertThat(response.currentTime()).isEqualTo("16:00:00");
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(response.embossedName()).isEqualTo(EMBOSSED_NAME);
        assertThat(response.activeStatus()).isEqualTo("Y");
        assertThat(response.expiryMonth()).isEqualTo("01");
        assertThat(response.expiryYear()).isEqualTo("2026");
        assertThat(response.expiryDay()).isEqualTo("31");
        assertThat(response.informationMessage())
                .isEqualTo(CardUpdateResponse.Messages.PROMPT_FOR_CHANGES);
        assertThat(response.errorMessage()).isEqualTo(CardUpdateResponse.Messages.EXIT_MESSAGE);
        assertThat(response.generalError()).isTrue();
        assertThat(response.fieldErrors()).isEqualTo(errors);
        assertThat(response.focusScreenFieldId()).isEqualTo(SCREEN_EXPIRY_MONTH);
        assertThat(response.nextRoute()).isEqualTo("/api/cards/4111111111111111");
        assertThat(response.navigationContext()).isSameAs(context);
        assertThat(response.concurrencyToken()).isEqualTo(CONCURRENCY_TOKEN);
    }

    @Test
    @DisplayName("A message carrying trailing pad spaces keeps them through construction and through "
            + "a serialise-and-parse round trip")
    void padSpacesSurviveConstructionAndTheWireRoundTrip() throws JsonProcessingException {
        CardUpdateResponse response = reEntryResponse(List.of(expiryMonthInvalid()));
        CardUpdateResponse padded = new CardUpdateResponse(response.transactionName(),
                response.title01(), response.currentDate(), response.programName(),
                response.title02(), response.currentTime(), response.accountId(),
                response.cardNumber(), response.embossedName(), response.activeStatus(),
                response.expiryMonth(), response.expiryYear(), response.expiryDay(), null,
                CardUpdateResponse.Messages.EXIT_MESSAGE, true, response.fieldErrors(),
                response.focusScreenFieldId(), response.nextRoute(), response.navigationContext(),
                response.concurrencyToken());

        assertThat(padded.errorMessage()).hasSize(34).endsWith("              ");

        ObjectMapper mapper = moduleEquivalentMapper();
        CardUpdateResponse revived = mapper.readValue(mapper.writeValueAsString(padded),
                CardUpdateResponse.class);

        assertThat(revived.errorMessage()).isEqualTo(CardUpdateResponse.Messages.EXIT_MESSAGE);
        assertThat(revived.errorMessage()).hasSize(34);
        assertThat(revived).isEqualTo(padded);
    }

    @Test
    @DisplayName("A null field-error collection becomes an empty one, so a first submission is "
            + "constructible with no errors at all and never yields a null list")
    void nullFieldErrorsBecomeAnEmptyList() {
        CardUpdateResponse informational =
                informationalResponse(CardUpdateResponse.Messages.PROMPT_FOR_CHANGES);

        assertThat(informational.fieldErrors()).isNotNull().isEmpty();
        assertThat(informational.hasFieldErrors()).isFalse();
        assertThat(informational.generalError()).isFalse();
        assertThat(informational.errorMessage()).isNull();
        assertThat(informational.focusScreenFieldId()).isNull();
    }

    @Test
    @DisplayName("The field-error collection is copied on the way in, so a later mutation of the "
            + "caller's list cannot reach inside the response")
    void fieldErrorsAreCopiedOnTheWayIn() {
        List<ErrorResponse.FieldError> mutable = new ArrayList<>();
        mutable.add(expiryMonthInvalid());

        CardUpdateResponse response = reEntryResponse(mutable);
        assertThat(response.fieldErrors()).hasSize(1);

        mutable.add(new ErrorResponse.FieldError("expiryYear", "EXPYEAR",
                ErrorResponse.FieldState.MISSING,
                CardUpdateResponse.Messages.CARD_EXPIRY_YEAR_NOT_VALID));
        mutable.clear();

        assertThat(response.fieldErrors()).hasSize(1);
        assertThat(response.fieldErrors().get(0).fieldName()).isEqualTo("expiryMonth");
    }

    @Test
    @DisplayName("The exposed field-error collection rejects mutation, so a caller cannot alter the "
            + "response after it has been built")
    void exposedFieldErrorsRejectMutation() {
        CardUpdateResponse response = reEntryResponse(List.of(expiryMonthInvalid()));

        assertThatThrownBy(() -> response.fieldErrors().add(expiryMonthInvalid()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.fieldErrors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("The two error mechanisms are independent: the failure flag is carried explicitly "
            + "and is never derived from a message being present")
    void theFailureFlagIsIndependentOfAnyMessage() {
        CardUpdateResponse flaggedWithoutText = new CardUpdateResponse("CCUP", null, null, null,
                null, null, ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", "01", "2026", "31", null,
                null, true, List.of(), null, "/api/cards/1", NavigationContext.empty(),
                CONCURRENCY_TOKEN);
        CardUpdateResponse textWithoutFlag =
                informationalResponse(CardUpdateResponse.Messages.CODING_TO_BE_DONE);

        assertThat(flaggedWithoutText.generalError()).isTrue();
        assertThat(flaggedWithoutText.errorMessage()).isNull();
        assertThat(flaggedWithoutText.informationMessage()).isNull();

        assertThat(textWithoutFlag.generalError()).isFalse();
        assertThat(textWithoutFlag.informationMessage()).isNotNull();
    }

    @Test
    @DisplayName("Exactly one summary text is carried alongside as many independent field errors as "
            + "the screen decorated, and the per-field states are not collapsed into a flag")
    void oneSummaryTextAccompaniesIndependentPerFieldStates() {
        List<ErrorResponse.FieldError> errors = List.of(
                expiryMonthInvalid(),
                new ErrorResponse.FieldError("embossedName", "CRDNAME",
                        ErrorResponse.FieldState.MISSING,
                        CardUpdateResponse.Messages.PROMPT_FOR_NAME));

        CardUpdateResponse response = reEntryResponse(errors);

        assertThat(response.errorMessage())
                .isEqualTo(CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID);
        assertThat(response.informationMessage()).isNull();
        assertThat(response.fieldErrors()).hasSize(2);
        assertThat(response.fieldErrors()).extracting(ErrorResponse.FieldError::state)
                .containsExactly(ErrorResponse.FieldState.INVALID,
                        ErrorResponse.FieldState.MISSING);
        assertThat(response.hasFieldErrors()).isTrue();
    }

    @Test
    @DisplayName("Field errors are absent on a first submission and present only on a re-submission, "
            + "because the decoration macro fires only in the re-entry state")
    void fieldErrorsAppearOnlyOnReEntry() {
        CardUpdateResponse firstSubmission =
                informationalResponse(CardUpdateResponse.Messages.PROMPT_FOR_CHANGES);
        CardUpdateResponse reSubmission = reEntryResponse(List.of(expiryMonthInvalid()));

        assertThat(firstSubmission.navigationContext().programContext())
                .isEqualTo(NavigationContext.ProgramContext.ENTER);
        assertThat(firstSubmission.fieldErrors()).isEmpty();

        assertThat(reSubmission.navigationContext().programContext())
                .isEqualTo(NavigationContext.ProgramContext.REENTER);
        assertThat(reSubmission.fieldErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("An embossed name differing only in letter case yields a different response, which "
            + "is why a case-only edit is legitimately reported as no change detected")
    void aCaseOnlyDifferenceInTheEmbossedNameIsNotFoldedAway() {
        CardUpdateResponse upper = informationalResponse(
                CardUpdateResponse.Messages.NO_CHANGES_DETECTED);
        CardUpdateResponse lower = new CardUpdateResponse(upper.transactionName(),
                upper.title01(), upper.currentDate(), upper.programName(),
                upper.title02(), upper.currentTime(), upper.accountId(),
                upper.cardNumber(), "mary ann", upper.activeStatus(), upper.expiryMonth(),
                upper.expiryYear(), upper.expiryDay(), upper.informationMessage(), upper.nextRoute(),
                upper.navigationContext(), upper.concurrencyToken());

        assertThat(lower.embossedName()).isEqualTo("mary ann");
        assertThat(upper.embossedName()).isEqualTo("MARY ANN");
        assertThat(lower).isNotEqualTo(upper);
        assertThat(upper.informationMessage())
                .isEqualTo("No change detected with respect to values fetched.");
    }

    @Test
    @DisplayName("Two responses built from the same values are equal and share a hash code, because "
            + "the contract is a value and not an identity")
    void equalValuesProduceEqualResponses() {
        CardUpdateResponse first = reEntryResponse(List.of(expiryMonthInvalid()));
        CardUpdateResponse second = reEntryResponse(List.of(expiryMonthInvalid()));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(informationalResponse(null));
    }

    @Test
    @DisplayName("The diagnostic rendering withholds the three regulated values and the concurrency "
            + "proof, while the wire payload still carries every one of them in full")
    void diagnosticRenderingWithholdsWhileTheWirePayloadDoesNot()
            throws JsonProcessingException {
        CardUpdateResponse response =
                informationalResponse(CardUpdateResponse.Messages.FOUND_CARDS_FOR_ACCOUNT);

        String rendered = response.toString();
        assertThat(rendered).doesNotContain(CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME);
        assertThat(rendered).contains("CardUpdateResponse", "***REDACTED***");

        // The proof is withheld for a different reason from the regulated values: it is not cardholder
        // data but a live integrity credential, and one recovered from a log line would let a stale
        // confirmation be replayed against the record it describes.
        assertThat(rendered).doesNotContain(CONCURRENCY_TOKEN);
        assertThat(rendered).contains("concurrencyToken=***REDACTED***");

        // The guarantee covers the regulated value components and the proof, and nothing else: the route
        // is a navigation label rendered exactly as supplied, so keeping regulated values out of the
        // route vocabulary remains the navigation layer's obligation rather than this response's.
        assertThat(rendered).contains("nextRoute=" + ROUTE);
        // The status code stays visible; the three expiry parts do not, because an expiry date beside a
        // card number is an authentication factor, which is why CardDetailResponse withholds the same
        // parts and the two card screens must not disagree.
        assertThat(rendered).contains("activeStatus=Y", "expiryDay=***REDACTED***",
                "expiryMonth=***REDACTED***", "expiryYear=***REDACTED***");

        JsonNode payload = payloadOf(response);
        assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
        assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
        assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
        assertThat(payload.get("concurrencyToken").asText()).isEqualTo(CONCURRENCY_TOKEN);
        assertThat(payload.toString()).doesNotContain("REDACTED");
    }

    @Test
    @DisplayName("The concurrency proof is serialised, survives a wire round trip byte for byte, and "
            + "is omitted entirely rather than emitted as null when a shape presents no card")
    void theConcurrencyProofRoundTripsAndIsOmittedWhenAbsent() throws JsonProcessingException {
        CardUpdateResponse presenting =
                informationalResponse(CardUpdateResponse.Messages.FOUND_CARDS_FOR_ACCOUNT);

        ObjectMapper mapper = moduleEquivalentMapper();
        CardUpdateResponse revived = mapper.readValue(mapper.writeValueAsString(presenting),
                CardUpdateResponse.class);

        assertThat(revived.concurrencyToken()).isEqualTo(CONCURRENCY_TOKEN);
        assertThat(revived).isEqualTo(presenting);

        // A shape that presents no card to confirm states the absence deliberately, and the module's
        // global null omission keeps the property off the wire rather than sending an explicit null.
        CardUpdateResponse noCardPresented = new CardUpdateResponse("CCUP", null, null, null, null,
                null, null, null, null, null, null, null, null,
                CardUpdateResponse.Messages.PROMPT_FOR_SEARCH_KEYS, ROUTE,
                NavigationContext.empty().withFirstEntry(), null);

        assertThat(noCardPresented.concurrencyToken()).isNull();
        assertThat(payloadOf(noCardPresented).has("concurrencyToken")).isFalse();
    }

    @Test
    @DisplayName("The proof is opaque to this contract: it carries no width bound, no other "
            + "constraint, and an arbitrary value passes validation untouched")
    void theConcurrencyProofIsUnboundedAndUnconstrained() throws NoSuchFieldException {
        Field field = CardUpdateResponse.class.getDeclaredField("concurrencyToken");

        assertThat(field.getAnnotation(Size.class))
                .as("a width rule would couple this contract to the sealing envelope's encoding")
                .isNull();
        assertThat(field.getAnnotations()).isEmpty();

        String farLongerThanAnyScreenField = "x".repeat(4096);
        CardUpdateResponse response = new CardUpdateResponse("CCUP", null, null, null, null, null,
                ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", "01", "2026", "31", null, ROUTE,
                NavigationContext.empty(), farLongerThanAnyScreenField);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(response)).isEmpty();
        }

        assertThat(response.concurrencyToken()).isEqualTo(farLongerThanAnyScreenField).hasSize(4096);
    }

    @Test
    @DisplayName("Two responses differing only in their concurrency proof are not equal, so a proof "
            + "cannot be swapped between responses without changing the value")
    void aDifferentProofYieldsADifferentResponse() {
        CardUpdateResponse first =
                informationalResponse(CardUpdateResponse.Messages.FOUND_CARDS_FOR_ACCOUNT);
        CardUpdateResponse second = new CardUpdateResponse(first.transactionName(),
                first.title01(), first.currentDate(), first.programName(),
                first.title02(), first.currentTime(), first.accountId(),
                first.cardNumber(), first.embossedName(), first.activeStatus(), first.expiryMonth(),
                first.expiryYear(), first.expiryDay(), first.informationMessage(), first.nextRoute(),
                first.navigationContext(), CONCURRENCY_TOKEN + "-other");

        assertThat(second).isNotEqualTo(first);
        assertThat(second.concurrencyToken()).isNotEqualTo(first.concurrencyToken());
    }

    @Test
    @DisplayName("The card number crosses the wire whole, never shortened and never obscured, "
            + "because the response is the card-maintenance contract")
    void theCardNumberCrossesTheWireWhole() throws JsonProcessingException {
        JsonNode payload = payloadOf(informationalResponse(null));

        assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER).hasSize(16);
        assertThat(payload.get("cardNumber").asText()).doesNotContain("*", "X", "#");
    }

    @Test
    @DisplayName("The payload omits absent components, always states the failure flag, renders the "
            + "per-field state as its own name and exposes no RFC 7807 property")
    void theWireShapeIsTheModulesOwnErrorBodyAndNotProblemDetail()
            throws JsonProcessingException {
        JsonNode informational = payloadOf(informationalResponse(
                CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION));

        assertThat(informational.has("errorMessage")).isFalse();
        assertThat(informational.has("focusScreenFieldId")).isFalse();
        assertThat(informational.get("generalError").asBoolean()).isFalse();
        assertThat(informational.get("fieldErrors").isArray()).isTrue();
        assertThat(informational.get("fieldErrors")).isEmpty();
        assertThat(informational.get("informationMessage").asText())
                .isEqualTo("Changes validated.Press F5 to save");

        JsonNode reEntry = payloadOf(reEntryResponse(List.of(expiryMonthInvalid())));
        assertThat(reEntry.get("generalError").asBoolean()).isTrue();
        assertThat(reEntry.get("fieldErrors").get(0).get("state").asText()).isEqualTo("INVALID");
        assertThat(reEntry.get("focusScreenFieldId").asText()).isEqualTo(SCREEN_EXPIRY_MONTH);

        for (String property : PROBLEM_DETAIL_PROPERTIES) {
            assertThat(informational.has(property)).as("RFC 7807 property %s", property).isFalse();
            assertThat(reEntry.has(property)).as("RFC 7807 property %s", property).isFalse();
        }
    }

    @Test
    @DisplayName("The focus hint is a screen-field identifier and never a coordinate, a sentinel or "
            + "an attribute value")
    void theFocusHintIsAnIdentifierOnly() throws JsonProcessingException {
        JsonNode payload = payloadOf(reEntryResponse(List.of(expiryMonthInvalid())));
        JsonNode focus = payload.get("focusScreenFieldId");

        assertThat(focus.isTextual()).isTrue();
        assertThat(focus.asText()).isEqualTo(SCREEN_EXPIRY_MONTH).doesNotContain("-1");
        assertThat(focus.asText().length())
                .isLessThanOrEqualTo(CardUpdateResponse.SCREEN_FIELD_ID_LENGTH);
    }

    @Test
    @DisplayName("The hidden expiry day is carried through the response even though the operator can "
            + "never edit it on the screen")
    void theHiddenExpiryDayIsCarriedThrough() throws JsonProcessingException {
        CardUpdateResponse response = informationalResponse(null);

        assertThat(response.expiryDay()).isEqualTo("31");
        assertThat(payloadOf(response).get("expiryDay").asText()).isEqualTo("31");
    }

    @Test
    @DisplayName("The route is carried as an opaque label the client follows, and the response "
            + "resolves nothing and dispatches nothing")
    void theRouteIsCarriedAsAnOpaqueLabel() throws JsonProcessingException {
        CardUpdateResponse response = informationalResponse(null);

        assertThat(response.nextRoute()).isEqualTo(ROUTE);
        assertThat(payloadOf(response).get("nextRoute").asText()).isEqualTo(ROUTE);
        assertThat(Arrays.stream(CardUpdateResponse.class.getDeclaredMethods())
                .map(Method::getName))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("resolve"))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("navigate"));
    }

    @Test
    @DisplayName("An unknown property on an inbound payload is tolerated and a missing field-error "
            + "collection is normalised to empty, matching the module's deserialisation settings")
    void anUnknownPropertyIsToleratedAndAMissingCollectionBecomesEmpty()
            throws JsonProcessingException {
        String payload = "{\"transactionName\":\"CCUP\",\"cardNumber\":\"" + CARD_NUMBER + "\","
                + "\"generalError\":false,\"unknownProperty\":\"ignored\"}";

        CardUpdateResponse revived =
                moduleEquivalentMapper().readValue(payload, CardUpdateResponse.class);

        assertThat(revived.transactionName()).isEqualTo("CCUP");
        assertThat(revived.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(revived.fieldErrors()).isNotNull().isEmpty();
        assertThat(revived.generalError()).isFalse();
    }
}
