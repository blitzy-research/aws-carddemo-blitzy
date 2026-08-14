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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ErrorResponse}, the REST error body of the screen-derived endpoints.
 *
 * <p>The two-state contract is what these tests protect: the legacy macro coloured a field when its
 * validation flag was not-OK and additionally marked it when the flag was specifically blank, so
 * MISSING and INVALID must stay distinct named values rather than collapsing into a boolean. The
 * state crosses the wire as its own textual name, so a client cannot come to depend on an ordinal.
 *
 * <p>One summary line coexists with many field errors, and a response may legitimately report the
 * two fields the legacy source decorates but never validates.
 */
@DisplayName("ErrorResponse :: two-state per-field error body of the screen-derived endpoints")
class ErrorResponseTest {
    private static final List<String> THIRTY_NINE_DECORATED_SCREEN_FIELDS = List.of(
            "ACSTTUS", "OPNYEAR", "OPNMON", "OPNDAY", "ACRDLIM", "EXPYEAR", "EXPMON",
            "EXPDAY", "ACSHLIM", "RISYEAR", "RISMON", "RISDAY", "ACURBAL", "ACRCYCR",
            "ACRCYDB", "ACTSSN1", "ACTSSN2", "ACTSSN3", "DOBYEAR", "DOBMON", "DOBDAY",
            "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM", "ACSADL1", "ACSSTTE", "ACSADL2",
            "ACSZIPC", "ACSCITY", "ACSCTRY", "ACSPH1A", "ACSPH1B", "ACSPH1C", "ACSPH2A",
            "ACSPH2B", "ACSPH2C", "ACSPFLG", "ACSEFTC");

    private static final List<String> FOUR_EDITABLE_BUT_UNDECORATED_SCREEN_FIELDS =
            List.of("ACCTSID", "AADDGRP", "ACSTNUM", "ACSGOVT");

    private static final String SUMMARY = "Account update rejected - correct the marked fields";

    private static final String OTHER_SUMMARY = "Account update rejected - re-key the account";

    private static final String SCREEN_ACCT_STATUS = "ACSTTUS";

    private static final String PROP_ACCT_STATUS = "acctStatus";

    private static final String ACCT_STATUS_MESSAGE = "Account status must be supplied";

    private static final String SCREEN_CREDIT_LIMIT = "ACRDLIM";

    private static final String PROP_CREDIT_LIMIT = "creditLimit";

    private static final String CREDIT_LIMIT_MESSAGE = "Credit limit must be a signed amount";

    private static final String SCREEN_MIDDLE_NAME = "ACSMNAM";

    private static final String PROP_MIDDLE_NAME = "middleName";

    private static final String SCREEN_ADDRESS_LINE_2 = "ACSADL2";

    private static final String PROP_ADDRESS_LINE_2 = "addressLine2";

    private static final String SCREEN_PRIMARY_CARDHOLDER = "ACSPFLG";

    private static final String PROP_PRIMARY_CARDHOLDER = "primaryCardholder";

    private static final String SCREEN_EFT_ACCOUNT_ID = "ACSEFTC";

    private static final String PROP_EFT_ACCOUNT_ID = "eftAccountId";

    private static final String DECORATION_ONLY_MESSAGE = "Field marked for operator attention";

    private static final String LEGACY_BLANK_MARKER = "*";

    private static final String KEY_MESSAGE = "message";

    private static final String KEY_FIELD_ERRORS = "fieldErrors";

    private static final String KEY_FOCUS = "focusScreenFieldId";

    private static final String KEY_FIELD_NAME = "fieldName";

    private static final String KEY_SCREEN_FIELD_ID = "screenFieldId";

    private static final String KEY_STATE = "state";

    private static final List<String> PROBLEM_DETAIL_PROPERTIES =
            List.of("type", "title", "status", "detail", "instance");

    private static ObjectMapper moduleEquivalentMapper() {
        return JsonContractSupport.declaredSettingsMapper();
    }

    private static JsonNode payloadOf(ErrorResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> propertyNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode requiredProperty(JsonNode node, String propertyName) {
        assertThat(node.has(propertyName))
                .as("the payload must carry the property %s, but carried %s",
                        propertyName, propertyNamesOf(node))
                .isTrue();
        return node.get(propertyName);
    }

    private static JsonNode entryAt(JsonNode entries, int index) {
        assertThat(entries.isArray())
                .as("the field errors must be rendered as an array")
                .isTrue();
        assertThat(entries)
                .as("the payload must carry a field error at position %d", index)
                .hasSizeGreaterThan(index);
        return entries.get(index);
    }

    private static JsonNode firstEntryOf(JsonNode payload) {
        return entryAt(requiredProperty(payload, KEY_FIELD_ERRORS), 0);
    }

    @Test
    @DisplayName("FieldState declares exactly two constants, MISSING then INVALID, because the macro had "
            + "exactly two firing states - a field that passed its edits decorates nothing and so has no "
            + "representation in an error enum at all")
    void fieldStateDeclaresExactlyTheTwoLegacyFiringStatesInDeclarationOrder() {
        assertThat(ErrorResponse.FieldState.values())
                .containsExactly(ErrorResponse.FieldState.MISSING,
                        ErrorResponse.FieldState.INVALID);

        assertThat(ErrorResponse.FieldState.values()).hasSize(2);
        assertThat(ErrorResponse.FieldState.MISSING.name()).isEqualTo("MISSING");
        assertThat(ErrorResponse.FieldState.INVALID.name()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("No third state constant exists: OK, VALID, NONE, UNKNOWN, BLANK, NOT_OK, WARNING and "
            + "DEFAULT are all rejected by name, so a synthetic constant cannot be introduced without "
            + "breaking this test")
    void noThirdStateConstantExistsUnderAnyOfTheNamesAReaderMightReachFor() {
        List<String> forbiddenNames =
                List.of("OK", "VALID", "NONE", "UNKNOWN", "BLANK", "NOT_OK", "WARNING", "DEFAULT");

        for (String forbidden : forbiddenNames) {
            assertThatThrownBy(() -> ErrorResponse.FieldState.valueOf(forbidden))
                    .as("FieldState must not declare a constant named %s", forbidden)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(ErrorResponse.FieldState.valueOf("MISSING"))
                .isSameAs(ErrorResponse.FieldState.MISSING);
        assertThat(ErrorResponse.FieldState.valueOf("INVALID"))
                .isSameAs(ErrorResponse.FieldState.INVALID);
    }

    @Test
    @DisplayName("MISSING and INVALID are distinct values with distinct ordinals and neither is derived "
            + "from the other, so no code path can silently coerce one into the other")
    void theTwoStatesAreDistinctValuesWithDistinctOrdinals() {
        ErrorResponse.FieldState missing = ErrorResponse.FieldState.MISSING;
        ErrorResponse.FieldState invalid = ErrorResponse.FieldState.INVALID;

        assertThat(missing).isNotEqualTo(invalid);
        assertThat(missing).isNotSameAs(invalid);
        assertThat(missing.ordinal()).isNotEqualTo(invalid.ordinal());
        assertThat(missing.compareTo(invalid)).isNotZero();

        assertThat(missing.ordinal()).isZero();
        assertThat(invalid.ordinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("The same field reported blank and reported badly filled yields two different states and "
            + "two unequal entries, with no coercion in either direction")
    void theSameFieldInTheTwoStatesYieldsTwoUnequalEntries() {
        ErrorResponse.FieldError blank = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING,
                ACCT_STATUS_MESSAGE);
        ErrorResponse.FieldError badlyFilled = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.INVALID,
                ACCT_STATUS_MESSAGE);

        assertThat(blank.state()).isSameAs(ErrorResponse.FieldState.MISSING);
        assertThat(badlyFilled.state()).isSameAs(ErrorResponse.FieldState.INVALID);

        assertThat(blank.fieldName()).isEqualTo(badlyFilled.fieldName());
        assertThat(blank.screenFieldId()).isEqualTo(badlyFilled.screenFieldId());
        assertThat(blank.message()).isEqualTo(badlyFilled.message());
        assertThat(blank).isNotEqualTo(badlyFilled);
    }

    @Test
    @DisplayName("A response may carry both states at once and each entry keeps its own, so a screen with "
            + "one blank field and one badly filled field reports both distinctly")
    void aSingleResponseCarriesBothStatesWithoutEitherOverwritingTheOther() {
        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE),
                new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                        ErrorResponse.FieldState.INVALID, CREDIT_LIMIT_MESSAGE)));

        assertThat(response.fieldErrors()).hasSize(2);
        assertThat(response.fieldErrors().get(0).state())
                .isSameAs(ErrorResponse.FieldState.MISSING);
        assertThat(response.fieldErrors().get(1).state())
                .isSameAs(ErrorResponse.FieldState.INVALID);
    }

    @Test
    @DisplayName("The state crosses the wire as its own textual name and never as a boolean or an ordinal "
            + "number, so a client can tell the two remedies apart from the payload alone")
    void theStateCrossesTheWireAsATextualNameRatherThanABooleanOrAnOrdinal()
            throws JsonProcessingException {
        JsonNode rendered = payloadOf(new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING),
                new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                        ErrorResponse.FieldState.INVALID))));

        JsonNode entries = requiredProperty(rendered, KEY_FIELD_ERRORS);
        JsonNode blankState = requiredProperty(entryAt(entries, 0), KEY_STATE);
        JsonNode badlyFilledState = requiredProperty(entryAt(entries, 1), KEY_STATE);

        assertThat(blankState.isTextual()).isTrue();
        assertThat(blankState.isBoolean()).isFalse();
        assertThat(blankState.isNumber()).isFalse();
        assertThat(blankState.asText()).isEqualTo("MISSING");

        assertThat(badlyFilledState.isTextual()).isTrue();
        assertThat(badlyFilledState.isBoolean()).isFalse();
        assertThat(badlyFilledState.isNumber()).isFalse();
        assertThat(badlyFilledState.asText()).isEqualTo("INVALID");

        assertThat(blankState.asText()).isNotEqualTo(badlyFilledState.asText());
    }

    @Test
    @DisplayName("The state carrier is an enum rather than a boolean or a bare string, so an unnamed third "
            + "value cannot be smuggled through the type system")
    void theStateCarrierIsAnEnumAndNotABooleanOrABareString() {
        ErrorResponse.FieldState state = new ErrorResponse.FieldError(
                PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME, ErrorResponse.FieldState.MISSING).state();

        assertThat(state).isInstanceOf(Enum.class);
        assertThat(state).isInstanceOf(ErrorResponse.FieldState.class);
        assertThat(state).isNotInstanceOf(Boolean.class);
        assertThat(state).isNotInstanceOf(String.class);
        assertThat(state).isNotInstanceOf(Number.class);
    }

    @Test
    @DisplayName("The summary-only constructor is the first-submission shape: a summary line and an empty "
            + "field-error collection, reproducing the re-entry gate at line 20 of the macro")
    void theSummaryOnlyConstructorIsTheUndecoratedFirstSubmissionShape() {
        ErrorResponse firstSubmission = new ErrorResponse(SUMMARY);

        assertThat(firstSubmission.message()).isEqualTo(SUMMARY);
        assertThat(firstSubmission.fieldErrors()).isNotNull().isEmpty();
        assertThat(firstSubmission.hasFieldErrors()).isFalse();
        assertThat(firstSubmission.focusScreenFieldId()).isNull();
    }

    @Test
    @DisplayName("Every construction path can express no field errors: an explicitly empty collection, a "
            + "null collection through the two-argument form, and a null collection through the full form")
    void everyConstructionPathCanExpressTheAbsenceOfFieldErrors() {
        ErrorResponse explicitlyEmpty = new ErrorResponse(SUMMARY, List.of(), null);
        ErrorResponse nullThroughShortForm = new ErrorResponse(SUMMARY, null);
        ErrorResponse nullThroughFullForm = new ErrorResponse(SUMMARY, null, null);

        assertThat(explicitlyEmpty.fieldErrors()).isEmpty();
        assertThat(nullThroughShortForm.fieldErrors()).isNotNull().isEmpty();
        assertThat(nullThroughFullForm.fieldErrors()).isNotNull().isEmpty();

        assertThat(explicitlyEmpty.hasFieldErrors()).isFalse();
        assertThat(nullThroughShortForm.hasFieldErrors()).isFalse();
        assertThat(nullThroughFullForm.hasFieldErrors()).isFalse();

        assertThat(explicitlyEmpty).isEqualTo(nullThroughShortForm);
        assertThat(explicitlyEmpty).isEqualTo(nullThroughFullForm);
    }

    @Test
    @DisplayName("An undecorated first submission still renders the field-error property, as an empty "
            + "array, so a client never has to test it for null before iterating")
    void anUndecoratedFirstSubmissionRendersAnEmptyArrayRatherThanOmittingTheProperty()
            throws JsonProcessingException {
        JsonNode payload = payloadOf(new ErrorResponse(SUMMARY));

        assertThat(payload.has(KEY_FIELD_ERRORS)).isTrue();
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS).isArray()).isTrue();
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS).isEmpty()).isTrue();
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS)).isEmpty();
    }

    @Test
    @DisplayName("A response may carry field errors for the two fields the source decorates but never "
            + "validates, because decoration alone is a legitimate reason for an entry to exist")
    void aResponseCarriesADecorationThatNoValidationRuleProduced() {
        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                        ErrorResponse.FieldState.MISSING, DECORATION_ONLY_MESSAGE),
                new ErrorResponse.FieldError(PROP_ADDRESS_LINE_2, SCREEN_ADDRESS_LINE_2,
                        ErrorResponse.FieldState.INVALID, DECORATION_ONLY_MESSAGE)));

        assertThat(response.fieldErrors()).hasSize(2);
        assertThat(response.fieldErrors().get(0).fieldName()).isEqualTo(PROP_MIDDLE_NAME);
        assertThat(response.fieldErrors().get(0).screenFieldId()).isEqualTo(SCREEN_MIDDLE_NAME);
        assertThat(response.fieldErrors().get(1).fieldName()).isEqualTo(PROP_ADDRESS_LINE_2);
        assertThat(response.fieldErrors().get(1).screenFieldId())
                .isEqualTo(SCREEN_ADDRESS_LINE_2);

        assertThat(THIRTY_NINE_DECORATED_SCREEN_FIELDS)
                .contains(SCREEN_MIDDLE_NAME, SCREEN_ADDRESS_LINE_2);
        assertThat(FOUR_EDITABLE_BUT_UNDECORATED_SCREEN_FIELDS)
                .doesNotContain(SCREEN_MIDDLE_NAME, SCREEN_ADDRESS_LINE_2);
    }

    @Test
    @DisplayName("One summary line coexists with many independent field errors: the legacy cascades stopped "
            + "at the first match, so N decorated fields still produce exactly one summary text")
    void oneSummaryLineCoexistsWithManyIndependentFieldErrors() {
        List<ErrorResponse.FieldError> threeFields = List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE),
                new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                        ErrorResponse.FieldState.INVALID, CREDIT_LIMIT_MESSAGE),
                new ErrorResponse.FieldError(PROP_PRIMARY_CARDHOLDER, SCREEN_PRIMARY_CARDHOLDER,
                        ErrorResponse.FieldState.INVALID, DECORATION_ONLY_MESSAGE));

        ErrorResponse response = new ErrorResponse(SUMMARY, threeFields);

        assertThat(response.message()).isEqualTo(SUMMARY);
        assertThat(response.fieldErrors()).hasSize(3);
        assertThat(response.hasFieldErrors()).isTrue();
    }

    @Test
    @DisplayName("The summary is carried verbatim and is never synthesised from the entries: it matches "
            + "what the caller supplied and contains none of the per-field wordings")
    void theSummaryIsCarriedVerbatimAndNeverConcatenatedFromTheEntries() {
        ErrorResponse response = new ErrorResponse(OTHER_SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE),
                new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                        ErrorResponse.FieldState.INVALID, CREDIT_LIMIT_MESSAGE)));

        assertThat(response.message()).isEqualTo(OTHER_SUMMARY);
        assertThat(response.message()).doesNotContain(ACCT_STATUS_MESSAGE);
        assertThat(response.message()).doesNotContain(CREDIT_LIMIT_MESSAGE);

        assertThat(response.fieldErrors().get(0).message()).isEqualTo(ACCT_STATUS_MESSAGE);
        assertThat(response.fieldErrors().get(1).message()).isEqualTo(CREDIT_LIMIT_MESSAGE);
    }

    @Test
    @DisplayName("An absent summary stays absent even when entries carry wordings of their own, so nothing "
            + "is invented to fill the summary line")
    void anAbsentSummaryIsNeverBackfilledFromTheEntries() throws JsonProcessingException {
        ErrorResponse response = new ErrorResponse(null, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)));

        assertThat(response.message()).isNull();
        assertThat(response.fieldErrors()).hasSize(1);

        JsonNode payload = payloadOf(response);
        assertThat(payload.has(KEY_MESSAGE)).isFalse();
        assertThat(requiredProperty(firstEntryOf(payload), KEY_MESSAGE).asText())
                .isEqualTo(ACCT_STATUS_MESSAGE);
    }

    @Test
    @DisplayName("The summary is a single text and not a collection of texts, so a client renders one line "
            + "and never has to choose which of several to show")
    void theSummaryIsASingleTextAndNotACollectionOfTexts() throws JsonProcessingException {
        JsonNode payload = payloadOf(new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE),
                new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                        ErrorResponse.FieldState.INVALID, CREDIT_LIMIT_MESSAGE))));

        JsonNode summary = requiredProperty(payload, KEY_MESSAGE);
        assertThat(summary.isTextual()).isTrue();
        assertThat(summary.isArray()).isFalse();
        assertThat(summary.isObject()).isFalse();
        assertThat(summary.asText()).isEqualTo(SUMMARY);

        assertThat(propertyNamesOf(payload))
                .containsExactly(KEY_MESSAGE, KEY_FIELD_ERRORS)
                .containsOnlyOnce(KEY_MESSAGE);
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS)).hasSize(2);
    }

    @Test
    @DisplayName("A response with no summary, no entries and no focus hint renders only the field-error "
            + "property: the two absent optional members are omitted rather than sent as null")
    void absentOptionalMembersAreOmittedFromThePayload() throws JsonProcessingException {
        JsonNode payload = payloadOf(new ErrorResponse(null, null, null));

        assertThat(propertyNamesOf(payload)).containsExactly(KEY_FIELD_ERRORS);
        assertThat(payload.has(KEY_MESSAGE)).isFalse();
        assertThat(payload.has(KEY_FOCUS)).isFalse();
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS).isArray()).isTrue();
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS)).isEmpty();
    }

    @Test
    @DisplayName("A fully populated response renders all three top-level properties in component order, so "
            + "a member that is present is always visible on the wire")
    void aPopulatedMemberAlwaysAppearsInThePayload() throws JsonProcessingException {
        JsonNode payload = payloadOf(new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)),
                SCREEN_ACCT_STATUS));

        assertThat(propertyNamesOf(payload))
                .containsExactly(KEY_MESSAGE, KEY_FIELD_ERRORS, KEY_FOCUS);
        assertThat(requiredProperty(payload, KEY_MESSAGE).asText()).isEqualTo(SUMMARY);
        assertThat(requiredProperty(payload, KEY_FOCUS).asText()).isEqualTo(SCREEN_ACCT_STATUS);
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS)).hasSize(1);
    }

    @Test
    @DisplayName("An entry built without a per-field wording omits that property and keeps the other three, "
            + "because the legacy macro produced a decoration and no text of its own")
    void anEntryWithoutAWordingOmitsThatPropertyAndKeepsTheOtherThree()
            throws JsonProcessingException {
        JsonNode entry = firstEntryOf(payloadOf(new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_EFT_ACCOUNT_ID, SCREEN_EFT_ACCOUNT_ID,
                        ErrorResponse.FieldState.INVALID)))));

        assertThat(propertyNamesOf(entry))
                .containsExactly(KEY_FIELD_NAME, KEY_SCREEN_FIELD_ID, KEY_STATE);
        assertThat(entry.has(KEY_MESSAGE)).isFalse();
        assertThat(requiredProperty(entry, KEY_FIELD_NAME).asText()).isEqualTo(PROP_EFT_ACCOUNT_ID);
        assertThat(requiredProperty(entry, KEY_SCREEN_FIELD_ID).asText()).isEqualTo(SCREEN_EFT_ACCOUNT_ID);
        assertThat(requiredProperty(entry, KEY_STATE).asText()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("An entry that does carry a wording renders all four properties, so the optional member is "
            + "omitted only when it is genuinely absent")
    void anEntryWithAWordingRendersAllFourProperties() throws JsonProcessingException {
        JsonNode entry = firstEntryOf(payloadOf(new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)))));

        assertThat(propertyNamesOf(entry))
                .containsExactly(KEY_FIELD_NAME, KEY_SCREEN_FIELD_ID, KEY_STATE, KEY_MESSAGE);
        assertThat(requiredProperty(entry, KEY_MESSAGE).asText()).isEqualTo(ACCT_STATUS_MESSAGE);
    }

    @Test
    @DisplayName("An empty summary is not the same as an absent one: the empty text is rendered, because "
            + "only null is omitted and no value is coerced into another")
    void anEmptySummaryIsRenderedWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
        JsonNode empty = payloadOf(new ErrorResponse(""));
        JsonNode absent = payloadOf(new ErrorResponse(null));

        assertThat(empty.has(KEY_MESSAGE)).isTrue();
        assertThat(requiredProperty(empty, KEY_MESSAGE).isTextual()).isTrue();
        assertThat(requiredProperty(empty, KEY_MESSAGE).asText()).isEmpty();
        assertThat(absent.has(KEY_MESSAGE)).isFalse();
    }

    @Test
    @DisplayName("The payload carries none of the five standard problem-detail properties, at the top level "
            + "or on an entry: this type is the error body, not a wrapper for another representation")
    void thePayloadIsNotAProblemDocument() throws JsonProcessingException {
        JsonNode payload = payloadOf(new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)),
                SCREEN_ACCT_STATUS));
        JsonNode entry = firstEntryOf(payload);

        for (String problemProperty : PROBLEM_DETAIL_PROPERTIES) {
            assertThat(payload.has(problemProperty))
                    .as("the response body must not carry the problem-detail property %s",
                            problemProperty)
                    .isFalse();
            assertThat(entry.has(problemProperty))
                    .as("a field entry must not carry the problem-detail property %s",
                            problemProperty)
                    .isFalse();
        }

        assertThat(propertyNamesOf(payload))
                .containsExactly(KEY_MESSAGE, KEY_FIELD_ERRORS, KEY_FOCUS)
                .doesNotContainAnyElementsOf(PROBLEM_DETAIL_PROPERTIES);
        assertThat(propertyNamesOf(entry))
                .containsExactly(KEY_FIELD_NAME, KEY_SCREEN_FIELD_ID, KEY_STATE, KEY_MESSAGE)
                .doesNotContainAnyElementsOf(PROBLEM_DETAIL_PROPERTIES);
    }

    @Test
    @DisplayName("No status code, no request identifier and no document reference is carried anywhere in "
            + "the payload, so transport concerns stay in the transport layer")
    void thePayloadCarriesNoTransportOrDocumentMetadata() throws JsonProcessingException {
        String rendered = moduleEquivalentMapper().writeValueAsString(
                new ErrorResponse(SUMMARY, List.of(
                        new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                                ErrorResponse.FieldState.INVALID, CREDIT_LIMIT_MESSAGE)),
                        SCREEN_CREDIT_LIMIT));

        assertThat(rendered).doesNotContain("problem+json");
        assertThat(rendered).doesNotContain("about:blank");
        assertThat(rendered).doesNotContain("httpStatus");
        assertThat(rendered).doesNotContain("traceId");
        assertThat(rendered).doesNotContain("timestamp");
        assertThat(rendered).doesNotContain("path");

        assertThat(rendered).doesNotContainPattern("[0-9]");
        assertThat(rendered).containsOnlyOnce(SUMMARY);
    }

    @Test
    @DisplayName("The accessor hands back an unmodifiable collection: adding, removing or clearing through "
            + "it is rejected, so a caller cannot rewrite a response it has been handed")
    void theFieldErrorCollectionIsUnmodifiableThroughTheAccessor() {
        ErrorResponse.FieldError carried = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING);
        ErrorResponse.FieldError intruder = new ErrorResponse.FieldError(
                PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, ErrorResponse.FieldState.INVALID);
        List<ErrorResponse.FieldError> exposed =
                new ErrorResponse(SUMMARY, List.of(carried)).fieldErrors();

        assertThatThrownBy(() -> exposed.add(intruder))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> exposed.remove(carried))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> exposed.set(0, intruder))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(exposed::clear)
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(exposed).containsExactly(carried);
    }

    @Test
    @DisplayName("The collection is defensively copied at construction, so mutating the caller's own list "
            + "afterwards leaves the response exactly as it was built")
    void mutatingTheCallerSuppliedListAfterConstructionDoesNotChangeTheResponse() {
        ErrorResponse.FieldError first = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING);
        ErrorResponse.FieldError second = new ErrorResponse.FieldError(
                PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT, ErrorResponse.FieldState.INVALID);

        List<ErrorResponse.FieldError> callerOwned = new ArrayList<>();
        callerOwned.add(first);
        ErrorResponse response = new ErrorResponse(SUMMARY, callerOwned);

        callerOwned.add(second);
        callerOwned.clear();

        assertThat(response.fieldErrors()).containsExactly(first);
        assertThat(response.fieldErrors()).doesNotContain(second);
        assertThat(response.hasFieldErrors()).isTrue();
    }

    @Test
    @DisplayName("A null collection becomes the empty immutable collection rather than null or a failure, "
            + "so no accessor and no payload ever sees a missing collection")
    void aNullCollectionBecomesTheEmptyImmutableCollection() {
        ErrorResponse response = new ErrorResponse(SUMMARY, null, SCREEN_ACCT_STATUS);
        ErrorResponse.FieldError intruder = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING);

        assertThat(response.fieldErrors()).isNotNull().isEmpty();
        assertThatThrownBy(() -> response.fieldErrors().add(intruder))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("A null entry inside the collection is rejected at construction rather than silently "
            + "dropped, because an entry with no state would be an error the client cannot show")
    void aNullEntryInsideTheCollectionIsRejectedRatherThanSilentlyDropped() {
        List<ErrorResponse.FieldError> withNullEntry = new ArrayList<>();
        withNullEntry.add(new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                ErrorResponse.FieldState.MISSING));
        withNullEntry.add(null);

        assertThatThrownBy(() -> new ErrorResponse(SUMMARY, withNullEntry))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErrorResponse(SUMMARY, withNullEntry, SCREEN_ACCT_STATUS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Each of the three mandatory entry components is rejected when null, and the rejection "
            + "names the component, so a half-built entry can never reach a client")
    void eachMandatoryEntryComponentIsRejectedWhenNull() {
        assertThatThrownBy(() -> new ErrorResponse.FieldError(
                null, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fieldName");

        assertThatThrownBy(() -> new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, null, ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("screenFieldId");

        assertThatThrownBy(() -> new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, null, ACCT_STATUS_MESSAGE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state");

        assertThatThrownBy(() -> new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state");
    }

    @Test
    @DisplayName("Neither the response nor an entry accepts a wholesale replacement of a component: both "
            + "are records with accessors only, and the collection they expose stays immutable after a copy")
    void aResponseCannotBeMutatedAfterConstruction() {
        ErrorResponse.FieldError carried = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING);
        ErrorResponse original = new ErrorResponse(SUMMARY, List.of(carried), SCREEN_ACCT_STATUS);

        ErrorResponse rebuilt =
                new ErrorResponse(OTHER_SUMMARY, original.fieldErrors(), SCREEN_CREDIT_LIMIT);

        assertThat(original.message()).isEqualTo(SUMMARY);
        assertThat(original.focusScreenFieldId()).isEqualTo(SCREEN_ACCT_STATUS);
        assertThat(rebuilt.message()).isEqualTo(OTHER_SUMMARY);
        assertThat(rebuilt.focusScreenFieldId()).isEqualTo(SCREEN_CREDIT_LIMIT);
        assertThat(rebuilt.fieldErrors()).isEqualTo(original.fieldErrors());
        assertThat(rebuilt).isNotEqualTo(original);
    }

    @Test
    @DisplayName("Trailing spaces survive byte for byte on every text component, because the legacy screen "
            + "and record fields these values come from are fixed width and space significant")
    void trailingSpacesSurviveByteForByteOnEveryTextComponent() {
        String paddedProperty = PROP_MIDDLE_NAME + "  ";
        String paddedScreenField = SCREEN_MIDDLE_NAME + " ";
        String paddedSummary = SUMMARY + "   ";
        String paddedWording = DECORATION_ONLY_MESSAGE + " ";

        ErrorResponse response = new ErrorResponse(paddedSummary, List.of(
                new ErrorResponse.FieldError(paddedProperty, paddedScreenField,
                        ErrorResponse.FieldState.MISSING, paddedWording)),
                paddedScreenField);

        assertThat(response.message()).isEqualTo(paddedSummary).hasSize(SUMMARY.length() + 3);
        assertThat(response.focusScreenFieldId()).isEqualTo(paddedScreenField).hasSize(8);

        ErrorResponse.FieldError entry = response.fieldErrors().get(0);
        assertThat(entry.fieldName()).isEqualTo(paddedProperty)
                .hasSize(PROP_MIDDLE_NAME.length() + 2);
        assertThat(entry.screenFieldId()).isEqualTo(paddedScreenField).hasSize(8);
        assertThat(entry.message()).isEqualTo(paddedWording)
                .hasSize(DECORATION_ONLY_MESSAGE.length() + 1);
    }

    @Test
    @DisplayName("Trailing spaces also survive a full serialise-and-parse round trip, so padding is not "
            + "lost somewhere between the type and the wire")
    void trailingSpacesSurviveTheFullRoundTrip() throws JsonProcessingException {
        String paddedScreenField = SCREEN_ACCT_STATUS + "  ";
        ErrorResponse sent = new ErrorResponse(SUMMARY + " ", List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS + " ", paddedScreenField,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE + " ")),
                paddedScreenField);

        ObjectMapper mapper = moduleEquivalentMapper();
        ErrorResponse received =
                mapper.readValue(mapper.writeValueAsString(sent), ErrorResponse.class);

        assertThat(received).isEqualTo(sent);
        assertThat(received.message()).isEqualTo(SUMMARY + " ");
        assertThat(received.focusScreenFieldId()).isEqualTo(paddedScreenField);
        assertThat(received.fieldErrors().get(0).screenFieldId()).isEqualTo(paddedScreenField);
        assertThat(received.fieldErrors().get(0).fieldName()).isEqualTo(PROP_ACCT_STATUS + " ");
        assertThat(received.fieldErrors().get(0).message()).isEqualTo(ACCT_STATUS_MESSAGE + " ");
    }

    @Test
    @DisplayName("Case is never folded in either direction, so a screen field identifier and a property "
            + "name each keep the exact spelling the caller used")
    void caseIsNeverFoldedInEitherDirection() throws JsonProcessingException {
        String mixedCaseScreenField = "AcSmNaM";
        String upperCaseProperty = "MIDDLENAME";

        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(upperCaseProperty, mixedCaseScreenField,
                        ErrorResponse.FieldState.INVALID)));
        ErrorResponse.FieldError entry = response.fieldErrors().get(0);

        assertThat(entry.screenFieldId()).isEqualTo(mixedCaseScreenField);
        assertThat(entry.screenFieldId()).isNotEqualTo(SCREEN_MIDDLE_NAME);
        assertThat(entry.screenFieldId()).isNotEqualTo("acsmnam");
        assertThat(entry.fieldName()).isEqualTo(upperCaseProperty);
        assertThat(entry.fieldName()).isNotEqualTo(PROP_MIDDLE_NAME);

        JsonNode rendered = firstEntryOf(payloadOf(response));
        assertThat(requiredProperty(rendered, KEY_SCREEN_FIELD_ID).asText()).isEqualTo(mixedCaseScreenField);
        assertThat(requiredProperty(rendered, KEY_FIELD_NAME).asText()).isEqualTo(upperCaseProperty);
    }

    @Test
    @DisplayName("A leading-zero identifier survives unchanged as text: these values cross the API as "
            + "bounded strings, never as a number, because a numeric type would drop the zeros")
    void aLeadingZeroIdentifierSurvivesUnchangedAsText() throws JsonProcessingException {
        String leadingZeroIdentifier = "000000042";

        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(leadingZeroIdentifier, leadingZeroIdentifier,
                        ErrorResponse.FieldState.INVALID, leadingZeroIdentifier)),
                leadingZeroIdentifier);
        ErrorResponse.FieldError entry = response.fieldErrors().get(0);

        assertThat(entry.fieldName()).isEqualTo(leadingZeroIdentifier).hasSize(9);
        assertThat(entry.screenFieldId()).isEqualTo(leadingZeroIdentifier);
        assertThat(entry.message()).isEqualTo(leadingZeroIdentifier);
        assertThat(response.focusScreenFieldId()).isEqualTo(leadingZeroIdentifier);

        JsonNode payload = payloadOf(response);
        JsonNode rendered = firstEntryOf(payload);
        assertThat(requiredProperty(rendered, KEY_FIELD_NAME).isTextual()).isTrue();
        assertThat(requiredProperty(rendered, KEY_FIELD_NAME).isNumber()).isFalse();
        assertThat(requiredProperty(rendered, KEY_FIELD_NAME).asText()).isEqualTo(leadingZeroIdentifier);
        assertThat(requiredProperty(payload, KEY_FOCUS).isTextual()).isTrue();
        assertThat(requiredProperty(payload, KEY_FOCUS).asText()).isEqualTo(leadingZeroIdentifier);

        ObjectMapper mapper = moduleEquivalentMapper();
        ErrorResponse received =
                mapper.readValue(mapper.writeValueAsString(response), ErrorResponse.class);
        assertThat(received.fieldErrors().get(0).fieldName()).isEqualTo(leadingZeroIdentifier);
        assertThat(received.focusScreenFieldId()).isEqualTo(leadingZeroIdentifier);
    }

    @Test
    @DisplayName("A leading space, an embedded space and an interior run of spaces are all preserved, so no "
            + "value is normalised on its way through the response")
    void leadingAndEmbeddedSpacesArePreservedToo() {
        String leadingSpace = " " + PROP_ACCT_STATUS;
        String embeddedSpaces = "MARY  ANN";

        ErrorResponse.FieldError entry = new ErrorResponse.FieldError(
                leadingSpace, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.INVALID,
                embeddedSpaces);

        assertThat(entry.fieldName()).isEqualTo(leadingSpace)
                .hasSize(PROP_ACCT_STATUS.length() + 1);
        assertThat(entry.message()).isEqualTo(embeddedSpaces).hasSize(9);
    }

    @Test
    @DisplayName("The full constructor carries all three response components and all four entry components "
            + "intact, and the presence test agrees with the collection")
    void theFullConstructorCarriesEveryComponentIntact() {
        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_PRIMARY_CARDHOLDER, SCREEN_PRIMARY_CARDHOLDER,
                        ErrorResponse.FieldState.INVALID, DECORATION_ONLY_MESSAGE)),
                SCREEN_PRIMARY_CARDHOLDER);

        assertThat(response.message()).isEqualTo(SUMMARY);
        assertThat(response.focusScreenFieldId()).isEqualTo(SCREEN_PRIMARY_CARDHOLDER);
        assertThat(response.hasFieldErrors()).isTrue();
        assertThat(response.fieldErrors()).hasSize(1);

        ErrorResponse.FieldError entry = response.fieldErrors().get(0);
        assertThat(entry.fieldName()).isEqualTo(PROP_PRIMARY_CARDHOLDER);
        assertThat(entry.screenFieldId()).isEqualTo(SCREEN_PRIMARY_CARDHOLDER);
        assertThat(entry.state()).isSameAs(ErrorResponse.FieldState.INVALID);
        assertThat(entry.message()).isEqualTo(DECORATION_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("Equality is value based for the response and for an entry, and a difference in any single "
            + "component breaks it, so two responses are interchangeable only when they truly match")
    void equalityAndHashCodeAreValueBasedForBothRecords() {
        ErrorResponse.FieldError entry = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING,
                ACCT_STATUS_MESSAGE);
        ErrorResponse.FieldError sameEntry = new ErrorResponse.FieldError(
                PROP_ACCT_STATUS, SCREEN_ACCT_STATUS, ErrorResponse.FieldState.MISSING,
                ACCT_STATUS_MESSAGE);

        assertThat(sameEntry).isEqualTo(entry).hasSameHashCodeAs(entry);
        assertThat(new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_ACCT_STATUS,
                ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)).isNotEqualTo(entry);
        assertThat(new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_CREDIT_LIMIT,
                ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)).isNotEqualTo(entry);
        assertThat(new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                ErrorResponse.FieldState.INVALID, ACCT_STATUS_MESSAGE)).isNotEqualTo(entry);
        assertThat(new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                ErrorResponse.FieldState.MISSING, CREDIT_LIMIT_MESSAGE)).isNotEqualTo(entry);

        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(entry), SCREEN_ACCT_STATUS);
        ErrorResponse identical =
                new ErrorResponse(SUMMARY, List.of(sameEntry), SCREEN_ACCT_STATUS);

        assertThat(identical).isEqualTo(response).hasSameHashCodeAs(response);
        assertThat(new ErrorResponse(OTHER_SUMMARY, List.of(entry), SCREEN_ACCT_STATUS))
                .isNotEqualTo(response);
        assertThat(new ErrorResponse(SUMMARY, List.of(), SCREEN_ACCT_STATUS))
                .isNotEqualTo(response);
        assertThat(new ErrorResponse(SUMMARY, List.of(entry), SCREEN_CREDIT_LIMIT))
                .isNotEqualTo(response);
        assertThat(response).isEqualTo(response);
        assertThat(response).isNotEqualTo(entry);
    }

    @Test
    @DisplayName("The text form names every component of both records and leaks nothing else, so a log line "
            + "built from it is readable and carries no internal detail")
    void theTextFormNamesEveryComponentAndLeaksNothingElse() {
        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)),
                SCREEN_ACCT_STATUS);

        String responseText = response.toString();
        assertThat(responseText).startsWith("ErrorResponse[");
        assertThat(responseText).contains(KEY_MESSAGE + "=" + SUMMARY);
        assertThat(responseText).contains(KEY_FIELD_ERRORS + "=");
        assertThat(responseText).contains(KEY_FOCUS + "=" + SCREEN_ACCT_STATUS);

        String entryText = response.fieldErrors().get(0).toString();
        assertThat(entryText).startsWith("FieldError[");
        assertThat(entryText).contains(KEY_FIELD_NAME + "=" + PROP_ACCT_STATUS);
        assertThat(entryText).contains(KEY_SCREEN_FIELD_ID + "=" + SCREEN_ACCT_STATUS);
        assertThat(entryText).contains(KEY_STATE + "=MISSING");

        assertThat(responseText).doesNotContain("com.carddemo");
        assertThat(responseText).doesNotContain("Exception");
        assertThat(responseText).doesNotContain("@");
        assertThat(entryText).doesNotContain("com.carddemo");
    }

    @Test
    @DisplayName("A serialise-and-parse round trip reproduces an equal response with an equal hash, so the "
            + "published shape is enough to rebuild the value on the other side")
    void aRoundTripReproducesAnEqualResponse() throws JsonProcessingException {
        ErrorResponse sent = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE),
                new ErrorResponse.FieldError(PROP_EFT_ACCOUNT_ID, SCREEN_EFT_ACCOUNT_ID,
                        ErrorResponse.FieldState.INVALID)),
                SCREEN_ACCT_STATUS);

        ObjectMapper mapper = moduleEquivalentMapper();
        ErrorResponse received =
                mapper.readValue(mapper.writeValueAsString(sent), ErrorResponse.class);

        assertThat(received).isEqualTo(sent).hasSameHashCodeAs(sent);
        assertThat(received.fieldErrors()).hasSize(2);
        assertThat(received.fieldErrors().get(0).state())
                .isSameAs(ErrorResponse.FieldState.MISSING);
        assertThat(received.fieldErrors().get(1).state())
                .isSameAs(ErrorResponse.FieldState.INVALID);
        assertThat(received.fieldErrors().get(1).message()).isNull();
    }

    @Test
    @DisplayName("An unknown property echoed back by a client is ignored rather than rejected, matching the "
            + "lenient deserialisation the module configures")
    void anUnknownPropertyFromAClientIsIgnored() throws JsonProcessingException {
        String payloadWithAnExtraProperty = """
                {"message":"%s",\
                "fieldErrors":[{"fieldName":"%s","screenFieldId":"%s","state":"MISSING"}],\
                "aPropertyThisEndpointDoesNotConsume":"echoed back by a client"}""";
        payloadWithAnExtraProperty = String.format(Locale.ROOT, payloadWithAnExtraProperty,
                SUMMARY, PROP_ACCT_STATUS, SCREEN_ACCT_STATUS);

        ErrorResponse received = moduleEquivalentMapper()
                .readValue(payloadWithAnExtraProperty, ErrorResponse.class);

        assertThat(received.message()).isEqualTo(SUMMARY);
        assertThat(received.fieldErrors()).hasSize(1);
        assertThat(received.fieldErrors().get(0).fieldName()).isEqualTo(PROP_ACCT_STATUS);
        assertThat(received.fieldErrors().get(0).state())
                .isSameAs(ErrorResponse.FieldState.MISSING);
        assertThat(received.focusScreenFieldId()).isNull();
    }

    @Test
    @DisplayName("A payload that omits the field-error property still parses into an empty collection, so a "
            + "client that sends the minimal body cannot produce a null collection")
    void aPayloadWithoutTheFieldErrorPropertyParsesIntoAnEmptyCollection()
            throws JsonProcessingException {
        ErrorResponse received = moduleEquivalentMapper()
                .readValue("{\"message\":\"" + SUMMARY + "\"}", ErrorResponse.class);

        assertThat(received.message()).isEqualTo(SUMMARY);
        assertThat(received.fieldErrors()).isNotNull().isEmpty();
        assertThat(received.hasFieldErrors()).isFalse();
        assertThat(received.focusScreenFieldId()).isNull();
    }

    @Test
    @DisplayName("No declarative constraint fires on any value, however blank or odd, because the legacy "
            + "cascades are ordered and stop at the first match - constraints would fire out of order and "
            + "report several failures at once")
    void noDeclarativeConstraintFiresOnAnyValueHoweverBlankOrOdd() {
        ErrorResponse blankThroughout = new ErrorResponse("", List.of(
                new ErrorResponse.FieldError("", "", ErrorResponse.FieldState.INVALID, ""),
                new ErrorResponse.FieldError(PROP_MIDDLE_NAME, SCREEN_MIDDLE_NAME,
                        ErrorResponse.FieldState.MISSING, ""),
                new ErrorResponse.FieldError(PROP_ADDRESS_LINE_2, SCREEN_ADDRESS_LINE_2,
                        ErrorResponse.FieldState.MISSING, "   ")), "");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<ConstraintViolation<ErrorResponse>> onTheResponse =
                    validator.validate(blankThroughout);
            assertThat(onTheResponse).isEmpty();

            for (ErrorResponse.FieldError entry : blankThroughout.fieldErrors()) {
                Set<ConstraintViolation<ErrorResponse.FieldError>> onTheEntry =
                        validator.validate(entry);
                assertThat(onTheEntry)
                        .as("no constraint may be declared on entry %s", entry.fieldName())
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("The payload carries no terminal presentation artefact: no colour value, no attribute or "
            + "control byte, no marker character, no map coordinate and no screen mask")
    void thePayloadCarriesNoTerminalPresentationArtefact() throws JsonProcessingException {
        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING, ACCT_STATUS_MESSAGE)),
                SCREEN_ACCT_STATUS);

        JsonNode payload = payloadOf(response);
        assertThat(propertyNamesOf(payload))
                .containsExactly(KEY_MESSAGE, KEY_FIELD_ERRORS, KEY_FOCUS);
        assertThat(propertyNamesOf(firstEntryOf(payload)))
                .containsExactly(KEY_FIELD_NAME, KEY_SCREEN_FIELD_ID, KEY_STATE, KEY_MESSAGE);

        String rendered = moduleEquivalentMapper().writeValueAsString(response);
        assertThat(rendered).doesNotContain(LEGACY_BLANK_MARKER);
        assertThat(rendered).doesNotContainIgnoringCase("colour");
        assertThat(rendered).doesNotContainIgnoringCase("color");
        assertThat(rendered).doesNotContainIgnoringCase("attribute");
        assertThat(rendered).doesNotContainIgnoringCase("marker");
        assertThat(rendered).doesNotContainIgnoringCase("highlight");
        assertThat(rendered).doesNotContainIgnoringCase("cursor");
        assertThat(rendered).doesNotContainIgnoringCase("mapset");

        assertThat(response.fieldErrors().get(0).state())
                .isSameAs(ErrorResponse.FieldState.MISSING);
    }

    @Test
    @DisplayName("The payload carries no re-entry indicator: the gate that decides whether errors may be "
            + "populated lives in the service, and this value object neither stores nor exposes it")
    void thePayloadCarriesNoReEntryIndicator() throws JsonProcessingException {
        ErrorResponse firstSubmission = new ErrorResponse(SUMMARY);
        ErrorResponse reSubmission = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING)));

        assertThat(propertyNamesOf(payloadOf(firstSubmission)))
                .containsExactly(KEY_MESSAGE, KEY_FIELD_ERRORS);
        assertThat(propertyNamesOf(payloadOf(reSubmission)))
                .containsExactly(KEY_MESSAGE, KEY_FIELD_ERRORS);

        assertThat(firstSubmission.hasFieldErrors()).isFalse();
        assertThat(reSubmission.hasFieldErrors()).isTrue();
        String rendered = moduleEquivalentMapper().writeValueAsString(reSubmission);
        assertThat(rendered).doesNotContainIgnoringCase("reenter");
        assertThat(rendered).doesNotContainIgnoringCase("reentry");
        assertThat(rendered).doesNotContainIgnoringCase("resubmit");
    }

    @Test
    @DisplayName("The response holds no registry of the 39 decorated fields: it carries exactly the entries "
            + "the caller supplied, in the caller's order, with no de-duplication and nothing added")
    void theResponseHoldsNoRegistryOfTheThirtyNineDecoratedFields() {
        List<ErrorResponse.FieldError> everyDecoratedField = new ArrayList<>();
        for (String screenField : THIRTY_NINE_DECORATED_SCREEN_FIELDS) {
            everyDecoratedField.add(new ErrorResponse.FieldError(
                    "propertyFor" + screenField, screenField,
                    ErrorResponse.FieldState.INVALID));
        }

        ErrorResponse allThirtyNine = new ErrorResponse(SUMMARY, everyDecoratedField);

        assertThat(THIRTY_NINE_DECORATED_SCREEN_FIELDS).hasSize(39);
        assertThat(allThirtyNine.fieldErrors()).hasSize(39);
        assertThat(allThirtyNine.fieldErrors())
                .extracting(ErrorResponse.FieldError::screenFieldId)
                .containsExactlyElementsOf(THIRTY_NINE_DECORATED_SCREEN_FIELDS);

        ErrorResponse justOne = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING)));
        assertThat(justOne.fieldErrors()).hasSize(1);

        ErrorResponse repeated = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.MISSING),
                new ErrorResponse.FieldError(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                        ErrorResponse.FieldState.INVALID)));
        assertThat(repeated.fieldErrors()).hasSize(2);
    }

    @Test
    @DisplayName("The expansion order of the 39 fields survives the production decoration path and the "
            + "hand-off into this response, including the two irregular placements, and none of the four "
            + "undecorated fields appears")
    // This is an integration-path test over ordering, not an independent oracle. The 39 identifiers
            // are an INPUT: they are pushed through the production decorator one at a time in legacy
            // expansion order, and the assertions read back what the decorator and this response produced,
            // so what is proved is that neither reorders, adds nor drops an entry. The same 39 are pinned
            // by digest against a fixture extracted from the legacy program in FieldErrorDecoratorTest,
            // which is where the list's own provenance is established.
    void theIrregularExpansionOrderSurvivesTheProductionDecorationPath() {
        FieldErrorDecorator decorated = FieldErrorDecorator.none();
        for (String screenField : THIRTY_NINE_DECORATED_SCREEN_FIELDS) {
            decorated = decorated.mark("propertyFor" + screenField, screenField,
                    FieldErrorDecorator.FlagState.NOT_OK);
        }

        ErrorResponse response = new ErrorResponse(SUMMARY, decorated.fieldErrors());
        List<String> reported = response.fieldErrors().stream()
                .map(ErrorResponse.FieldError::screenFieldId)
                .toList();

        assertThat(decorated.fieldErrors()).hasSize(39);
        assertThat(reported)
                .as("the response echoes the decorator's own sequence, adding and removing nothing")
                .containsExactlyElementsOf(THIRTY_NINE_DECORATED_SCREEN_FIELDS);

        int addressLine1 = reported.indexOf("ACSADL1");
        int state = reported.indexOf("ACSSTTE");
        int addressLine2 = reported.indexOf(SCREEN_ADDRESS_LINE_2);
        int postalCode = reported.indexOf("ACSZIPC");
        int city = reported.indexOf("ACSCITY");
        int country = reported.indexOf("ACSCTRY");

        assertThat(state).isGreaterThan(addressLine1).isLessThan(addressLine2);
        assertThat(postalCode).isLessThan(city).isLessThan(country);

        assertThat(reported).startsWith(SCREEN_ACCT_STATUS);
        assertThat(reported).endsWith(SCREEN_PRIMARY_CARDHOLDER, SCREEN_EFT_ACCOUNT_ID);

        assertThat(response.fieldErrors())
                .extracting(ErrorResponse.FieldError::state)
                .containsOnly(ErrorResponse.FieldState.INVALID);
        assertThat(response.fieldErrors())
                .extracting(ErrorResponse.FieldError::message)
                .containsOnlyNulls();

        assertThat(FOUR_EDITABLE_BUT_UNDECORATED_SCREEN_FIELDS).hasSize(4);
        assertThat(reported)
                .as("no keyable-but-undecorated field is invented by either type")
                .doesNotContainAnyElementsOf(FOUR_EDITABLE_BUT_UNDECORATED_SCREEN_FIELDS);
    }

    @Test
    @DisplayName("A blank flag and a not-OK flag reach this response as MISSING and INVALID respectively "
            + "when they travel the production decoration path, so the two legacy remedies stay distinct "
            + "end to end")
    void theTwoLegacyFlagStatesReachTheResponseAsDistinctStates() {
        ErrorResponse response = new ErrorResponse(SUMMARY,
                FieldErrorDecorator.none()
                        .mark(PROP_ACCT_STATUS, SCREEN_ACCT_STATUS,
                                FieldErrorDecorator.FlagState.BLANK)
                        .mark(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                                FieldErrorDecorator.FlagState.NOT_OK)
                        .fieldErrors());

        assertThat(response.hasFieldErrors()).isTrue();
        assertThat(response.fieldErrors())
                .extracting(ErrorResponse.FieldError::screenFieldId)
                .containsExactly(SCREEN_ACCT_STATUS, SCREEN_CREDIT_LIMIT);
        assertThat(response.fieldErrors())
                .extracting(ErrorResponse.FieldError::state)
                .containsExactly(ErrorResponse.FieldState.MISSING,
                        ErrorResponse.FieldState.INVALID);
    }

    @Test
    @DisplayName("An accumulation on which the production decorator was never called yields the "
            + "first-submission shape: a summary line and no field errors at all")
    void anUnmarkedAccumulationYieldsTheFirstSubmissionShape() {
        FieldErrorDecorator untouched = FieldErrorDecorator.none();

        ErrorResponse response = new ErrorResponse(SUMMARY, untouched.fieldErrors());

        assertThat(untouched.isEmpty()).isTrue();
        assertThat(response.hasFieldErrors()).isFalse();
        assertThat(response.fieldErrors()).isEmpty();
        assertThat(response.message()).isEqualTo(SUMMARY);
    }

    @Test
    @DisplayName("The response carries no numeric or monetary member at all, so no rounding or scale "
            + "decision exists anywhere in it and none can be introduced unnoticed")
    void theResponseCarriesNoNumericOrMonetaryMember() throws JsonProcessingException {
        ErrorResponse response = new ErrorResponse(SUMMARY, List.of(
                new ErrorResponse.FieldError(PROP_CREDIT_LIMIT, SCREEN_CREDIT_LIMIT,
                        ErrorResponse.FieldState.INVALID, CREDIT_LIMIT_MESSAGE)),
                SCREEN_CREDIT_LIMIT);
        JsonNode payload = payloadOf(response);
        JsonNode entry = firstEntryOf(payload);

        assertThat(requiredProperty(payload, KEY_MESSAGE).isTextual()).isTrue();
        assertThat(requiredProperty(payload, KEY_FOCUS).isTextual()).isTrue();
        assertThat(requiredProperty(payload, KEY_FIELD_ERRORS).isArray()).isTrue();
        assertThat(requiredProperty(entry, KEY_FIELD_NAME).isTextual()).isTrue();
        assertThat(requiredProperty(entry, KEY_SCREEN_FIELD_ID).isTextual()).isTrue();
        assertThat(requiredProperty(entry, KEY_STATE).isTextual()).isTrue();
        assertThat(requiredProperty(entry, KEY_MESSAGE).isTextual()).isTrue();

        assertThat(requiredProperty(payload, KEY_MESSAGE).isNumber()).isFalse();
        assertThat(requiredProperty(entry, KEY_STATE).isNumber()).isFalse();

        String rendered = moduleEquivalentMapper().writeValueAsString(response);
        assertThat(rendered).doesNotContainPattern("[0-9]");
    }
}
