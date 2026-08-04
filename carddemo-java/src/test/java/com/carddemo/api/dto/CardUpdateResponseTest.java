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
import java.util.Optional;
import java.util.Set;

import com.carddemo.domain.enums.CardStatus;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CardUpdateResponse}, the response body of legacy CICS transaction
 * {@code CCUP}.
 *
 * <p>This is a pure unit test. It starts no application context, opens no connection, launches no
 * container and reads no property file: it constructs the type directly and, where the wire shape is
 * itself the thing under test, serialises it with a mapper built locally in this file to carry the
 * settings the module declares in {@code carddemo-java/src/main/resources/application.yml}. The
 * mapper is built here rather than borrowed from a helper so that this file states, in one place,
 * exactly which settings its wire assertions depend on. Nothing here shares mutable state with any
 * other test.
 *
 * <p><strong>Everything asserted about the type is asserted without inspecting it.</strong> No
 * runtime type inspection of any kind is used: the component inventory is proved by calling the
 * canonical constructor with its full positional argument list, so the compiler itself rejects a
 * component that is added, removed, renamed, reordered or retyped, and the wire inventory is proved
 * by reading the serialised key set. Immutability is proved by construction - the type has no
 * mutator to call, and the two collection tests show that neither the caller's list nor the exposed
 * list can reach inside a built instance.
 *
 * <p><strong>Where the facts come from.</strong> The field inventory and every width come from the
 * generated symbolic map {@code app/cpy-bms/COCRDUP.CPY}, whose output redefinition begins at line
 * 121 and declares the fifteen modelled items at lines 128, 134, 140, 146, 152, 158, 164, 170, 176,
 * 182, 188, 194, 200, 206 and 212. Two further items in that redefinition, at lines 218 and 224, are
 * function-key legend furniture and are deliberately absent from the response; their absence is
 * asserted rather than trusted. The protection attributes come from the mapset
 * {@code app/bms/COCRDUP.bms}, where the account identifier is protected at line 84 and the expiry
 * day is dark, field-set and protected at line 142. The behaviour and every message literal come
 * from {@code app/cbl/COCRDUPC.cbl}, 1,560 lines across 45 paragraphs. The two-state field-error
 * contract comes from the parameterized decoration macro {@code app/cpy/CSSETATY.cpy}, whose
 * re-entry gate is at line 20. The persisted layout behind the card values is the 150-byte card
 * record {@code app/cpy/CVACT02Y.cpy}.
 *
 * <p><strong>The message block is the contract, and it is deliberately inconsistent.</strong> Every
 * expected text below is restated from the program independently of the class under test, which is
 * the whole point: a table that read its expectations out of the class it is testing would assert
 * nothing. Several of the texts look like source defects and must nevertheless survive byte for
 * byte - the file-error prefix ends in a space, one confirmation text has no space after its full
 * stop while the structurally identical failure text does, the exit text carries fourteen trailing
 * pad spaces, one text runs to four full stops, the concurrency text spells a pronoun as two words,
 * and the expiry-month text names its bounds without zero padding. Normalising any of them breaks
 * the external interface contract, so each is asserted individually and by name in addition to the
 * exhaustive round-trip table.
 *
 * <p>A further case difference is contract rather than defect and is therefore never harmonised:
 * these texts are <em>mixed case</em>, whereas the card <em>list</em> program declares its operator
 * texts in <em>upper case</em>. Two texts on this very screen - the two filter texts the program
 * declares at its lines 745 and 789 - are themselves upper case where every neighbouring text is
 * mixed case. Both spellings are reproduced exactly as declared.
 *
 * <p><strong>Absences are asserted as deliberately as presences.</strong> No function-key legend, no
 * 3270 rendering artefact of any kind - no control-byte family, no terminal-buffer prefix, no map
 * coordinate, no attribute or colour value, no edited screen mask - and no <em>readable</em> version
 * number, entity tag, row version, timestamp, before-image snapshot or cursor position appears
 * anywhere in the contract. The lock text the program declares at line 206 and the concurrency text
 * it declares at line 208 reach a client as <em>text</em> and never as a version token; the
 * provider's version marker lives on the persistent card entity, not here.
 *
 * <p>The opaque sealed proof the type does carry is a different thing from a readable version
 * marker, and it is asserted present because the production contract declares it under decision
 * {@code DL-109} of {@code docs/decision-log.md}: the legacy program carried the fetched image
 * itself, returned it with the screen and compared against it on the confirming turn, so the
 * migrated screen has to hand out an equivalent. What is asserted about it is that it round-trips
 * untouched, that it reaches the wire, and that a diagnostic rendering never discloses it - never
 * that it has any readable structure, because it has none and nothing may be parsed out of it.
 *
 * <p>Provenance of every citation in this file: checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited in this documentation only and never
 * declared as a value: the stamp is not universal across the estate, so a constant asserting it
 * would be wrong for the members that carry a different one. No statement of the legacy source is
 * transcribed here - widths, offsets, counts, line numbers, member names and contract literals are
 * metadata about it, not extracts from it.
 */
@DisplayName("CardUpdateResponse :: card-update response contract of legacy transaction CCUP")
class CardUpdateResponseTest {

    /**
     * The declared width of the error line on this map: 80.
     *
     * <p>Restated here so that the width assertions do not read their expectation out of the class
     * under test. Thirteen of the seventeen symbolic maps in the estate declare 78 for the same
     * screen role; this map and the card-detail map declare 80, and the divergence is reproduced
     * rather than reconciled.
     */
    private static final int MAP_ERROR_MESSAGE_WIDTH = 80;

    /**
     * The declared width of the information line on this map: 40.
     *
     * <p>The account-view and account-update maps declare 45 for the same screen role. As with the
     * error line, the divergence is honoured and never normalised.
     */
    private static final int MAP_INFORMATION_MESSAGE_WIDTH = 40;

    /** The width the other thirteen maps use for the error line, which this map must never adopt. */
    private static final int OTHER_MAPS_ERROR_MESSAGE_WIDTH = 78;

    /** The width the account maps use for the information line, which this map must never adopt. */
    private static final int OTHER_MAPS_INFORMATION_MESSAGE_WIDTH = 45;

    /**
     * An account identifier at the full declared width of eleven whose leading zeros are the whole
     * point: a value that survives as characters and would be destroyed by an integral type.
     */
    private static final String LEADING_ZERO_ACCOUNT_ID = "00000000001";

    /**
     * A fictional card number at the full declared width of sixteen, chosen so that fifteen of its
     * sixteen characters are leading zeros. It is not a number in any issuer range and cannot be a
     * real primary account number.
     */
    private static final String LEADING_ZERO_CARD_NUMBER = "0000000000000001";

    /**
     * A second fictional card number at the full declared width, visibly distinct from the
     * leading-zero one so that a disclosure assertion cannot pass by coincidence.
     */
    private static final String FICTIONAL_CARD_NUMBER = "9999000011112222";

    /**
     * A mixed-case embossed name at exactly the declared width of fifty.
     *
     * <p>Mixed case on purpose. The program upper-folds the embossed name in place through a strict
     * 26-character table at its lines 1356 and 1499, and that fold belongs to the service; this
     * response must hand back whatever it was given, letter case included. The embedded spaces are
     * also deliberate: the legacy alphabetic check blanks every letter and then measures what is
     * left, so an interior space passes, which is exactly what the program's own rule text says.
     */
    private static final String MIXED_CASE_EMBOSSED_NAME =
            "Mary Ann de la Cruz-O'Brien Smithson Jones      xy";

    /** The screen field identifier of the expiry month, used as the focus hint. */
    private static final String SCREEN_FIELD_EXPIRY_MONTH = "EXPMON";

    /** The screen field identifier of the embossed name. */
    private static final String SCREEN_FIELD_CARD_NAME = "CRDNAME";

    /** The screen field identifier of the active-status code. */
    private static final String SCREEN_FIELD_CARD_STATUS = "CRDSTCD";

    /**
     * A declarative next-route label. It carries no account identifier and no card number, because a
     * route that embedded a regulated value would carry it into every diagnostic rendering of this
     * response no matter what the response itself withholds.
     */
    private static final String NEXT_ROUTE = "/api/cards/update";

    /**
     * A stand-in for a minted concurrency proof, deliberately an arbitrary opaque string rather than
     * anything a real minting service would produce: this type neither reads, parses, validates nor
     * bounds the proof, so a realistic value would exercise the minting service instead of this
     * contract. Its characters are visibly not a card value.
     */
    private static final String SEALED_PROOF_STAND_IN = "ccup-sealed-proof-stand-in";

    /** The five properties RFC 7807 would introduce, none of which this response may expose. */
    private static final List<String> PROBLEM_DETAIL_PROPERTIES =
            List.of("type", "title", "status", "detail", "instance");

    /**
     * Every key the serialised contract may carry, in the order the record declares its components.
     *
     * <p>Restated independently of the class under test. Asserting the serialised key set against
     * this list proves the whole component inventory without inspecting the type at runtime: a
     * component that was added would appear as an extra key, and one that was removed would be a
     * missing key. It is therefore also the proof that the two function-key legend items, every 3270
     * rendering artefact and every readable version marker are absent.
     */
    private static final List<String> EXPECTED_WIRE_KEYS = List.of(
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "accountId",
            "cardNumber",
            "embossedName",
            "activeStatus",
            "expiryMonth",
            "expiryYear",
            "expiryDay",
            "informationMessage",
            "errorMessage",
            "generalError",
            "fieldErrors",
            "focusScreenFieldId",
            "nextRoute",
            "navigationContext",
            "concurrencyToken");

    /**
     * Keys that must never appear in the serialised contract, each with a reason.
     *
     * <p>{@code fkeys} and {@code fkeysc} are the two function-key legend items the map carries and
     * this contract deliberately drops. {@code crdstp} is the screen-furniture family this contract
     * has no member of. {@code version}, {@code rowVersion}, {@code etag}, {@code timestamp},
     * {@code beforeImage} and {@code cursor} are readable concurrency or rendering state a client
     * could assert for itself - the concurrent-change condition reaches a client as the text the
     * program declares at line 208 and in no other form. {@code cvv} and {@code securityCode} are
     * values the legacy design never carried on this screen, so introducing either would be feature
     * expansion.
     */
    private static final List<String> FORBIDDEN_WIRE_KEYS = List.of(
            "fkeys",
            "fkeysc",
            "crdstp",
            "version",
            "rowVersion",
            "etag",
            "timestamp",
            "beforeImage",
            "cursor",
            "cvv",
            "securityCode");

    /**
     * Builds a mapper carrying the settings the module declares for its own mapper.
     *
     * <p>Built here rather than shared, so this file states exactly which settings its wire
     * assertions rest on: absent properties are omitted rather than emitted as null, dates are not
     * written as timestamps, an unknown incoming property is tolerated rather than rejected, and a
     * decimal is written in plain notation. A payload asserted through this mapper is the payload
     * this type produces <em>under those settings</em>.
     *
     * @return a mapper carrying the module's declared serialisation settings
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
     * Serialises a response and parses the result back into a tree, so the wire shape can be
     * inspected key by key.
     *
     * @param response the response to serialise
     * @return the parsed payload
     * @throws JsonProcessingException if the payload cannot be produced or parsed, which is itself a
     *                                 contract failure and is therefore never caught here
     */
    private static JsonNode payloadOf(CardUpdateResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Serialises a response and reads it back, so that a value can be shown to survive the wire
     * unchanged.
     *
     * @param response the response to round-trip
     * @return the revived response
     * @throws JsonProcessingException if the payload cannot be produced or parsed
     */
    private static CardUpdateResponse wireRoundTrip(CardUpdateResponse response)
            throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readValue(mapper.writeValueAsString(response), CardUpdateResponse.class);
    }

    /**
     * Collects the key names of a JSON object in the order the payload declares them.
     *
     * @param payload the parsed payload
     * @return the key names, in payload order
     */
    private static List<String> keysOf(JsonNode payload) {
        List<String> keys = new ArrayList<>();
        payload.fieldNames().forEachRemaining(keys::add);
        return List.copyOf(keys);
    }

    /**
     * Builds the fully populated response through the canonical constructor with every one of its
     * twenty-one positional arguments supplied.
     *
     * <p>This call is itself the inventory assertion: the compiler rejects it if a component is
     * added, removed, renamed, reordered or retyped, which is how the component set is pinned
     * without inspecting the type at runtime.
     *
     * @param informationMessage the information line, or {@code null}
     * @param errorMessage       the error line, or {@code null}
     * @param generalError       whether the submission failed as a whole
     * @param fieldErrors        the per-field errors, or {@code null} for none
     * @param focusScreenFieldId the focus hint, or {@code null}
     * @return the fully populated response
     */
    private static CardUpdateResponse fullyPopulated(String informationMessage,
                                                     String errorMessage,
                                                     boolean generalError,
                                                     List<ErrorResponse.FieldError> fieldErrors,
                                                     String focusScreenFieldId) {
        return new CardUpdateResponse(
                "CCUP",
                "Tracking Card Demo",
                "08/01/26",
                "COCRDUPC",
                "Update Card Details",
                "16:00:00",
                LEADING_ZERO_ACCOUNT_ID,
                LEADING_ZERO_CARD_NUMBER,
                MIXED_CASE_EMBOSSED_NAME,
                "Y",
                "01",
                "2026",
                "31",
                informationMessage,
                errorMessage,
                generalError,
                fieldErrors,
                focusScreenFieldId,
                NEXT_ROUTE,
                populatedNavigationContext(),
                SEALED_PROOF_STAND_IN);
    }

    /**
     * Builds the first-submission shape through the seventeen-argument convenience constructor: the
     * fetched card values, one information line, and no error of either kind.
     *
     * @param informationMessage the information line, or {@code null}
     * @return the informational response
     */
    private static CardUpdateResponse firstSubmission(String informationMessage) {
        return new CardUpdateResponse(
                "CCUP",
                "Tracking Card Demo",
                "08/01/26",
                "COCRDUPC",
                "Update Card Details",
                "16:00:00",
                LEADING_ZERO_ACCOUNT_ID,
                LEADING_ZERO_CARD_NUMBER,
                MIXED_CASE_EMBOSSED_NAME,
                "Y",
                "01",
                "2026",
                "31",
                informationMessage,
                NEXT_ROUTE,
                populatedNavigationContext().withFirstEntry(),
                SEALED_PROOF_STAND_IN);
    }

    /**
     * Builds the re-submission shape: one summary error text, the explicit failure flag, and the
     * supplied per-field errors.
     *
     * @param fieldErrors the per-field errors, or {@code null} for none
     * @return the re-entry response
     */
    private static CardUpdateResponse reSubmission(List<ErrorResponse.FieldError> fieldErrors) {
        return new CardUpdateResponse(
                "CCUP",
                "Tracking Card Demo",
                "08/01/26",
                "COCRDUPC",
                "Update Card Details",
                "16:00:00",
                LEADING_ZERO_ACCOUNT_ID,
                LEADING_ZERO_CARD_NUMBER,
                MIXED_CASE_EMBOSSED_NAME,
                "Y",
                "13",
                "2026",
                "31",
                null,
                CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID,
                true,
                fieldErrors,
                SCREEN_FIELD_EXPIRY_MONTH,
                NEXT_ROUTE,
                populatedNavigationContext().withReEntry(),
                SEALED_PROOF_STAND_IN);
    }

    /**
     * Builds an echoed navigation state whose identifiers all carry leading zeros, so that the
     * carried-not-reimplemented assertion has something significant to preserve.
     *
     * @return the populated navigation state
     */
    private static NavigationContext populatedNavigationContext() {
        return new NavigationContext(
                "CCUP",
                "COCRDUPC",
                "CCUP",
                "COCRDUPC",
                "USER0001",
                "U",
                NavigationContext.ProgramContext.ENTER,
                "000000001",
                "Mary",
                "Ann",
                "Smithson",
                LEADING_ZERO_ACCOUNT_ID,
                "Y",
                LEADING_ZERO_CARD_NUMBER,
                "CCRDUPA",
                "COCRDUP");
    }

    /** The invalid-value state of the expiry month: filled in, but the value failed its edit. */
    private static ErrorResponse.FieldError expiryMonthInvalid() {
        return new ErrorResponse.FieldError(
                "expiryMonth",
                SCREEN_FIELD_EXPIRY_MONTH,
                ErrorResponse.FieldState.INVALID,
                CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID);
    }

    /** The blank state of the embossed name: left empty when a value was needed. */
    private static ErrorResponse.FieldError embossedNameMissing() {
        return new ErrorResponse.FieldError(
                "embossedName",
                SCREEN_FIELD_CARD_NAME,
                ErrorResponse.FieldState.MISSING,
                CardUpdateResponse.Messages.PROMPT_FOR_NAME);
    }

    /** The invalid-value state of the active-status code. */
    private static ErrorResponse.FieldError activeStatusInvalid() {
        return new ErrorResponse.FieldError(
                "activeStatus",
                SCREEN_FIELD_CARD_STATUS,
                ErrorResponse.FieldState.INVALID,
                CardUpdateResponse.Messages.CARD_STATUS_MUST_BE_YES_NO);
    }

    /**
     * Which of the two message rows a text belongs on.
     *
     * <p>The program keeps two separate work fields and each text belongs to exactly one of them:
     * six texts belong to the 40-character information field and the rest to the 75-character return
     * field that feeds the 80-character error row. The distinction is behavioural - it decides which
     * screen row an operator reads the text on - so it is reproduced rather than flattened.
     */
    private enum MessageRow {

        /** The information row, fed by the program's 40-character information work field. */
        INFORMATION,

        /** The error row, fed by the program's 75-character return work field. */
        ERROR
    }

    /**
     * One declared operator text, with the program line that declares it, its exact character count
     * and the screen row it belongs on.
     *
     * @param programLine the line of {@code app/cbl/COCRDUPC.cbl} that declares the text
     * @param text        the exact characters, restated independently of the class under test
     * @param length      the exact character count, restated so a silent trim cannot pass
     * @param row         the screen row the program routes the text to
     */
    private record DeclaredMessage(int programLine, String text, int length, MessageRow row) {
    }

    /**
     * The fifteen texts this contract must carry, each with the program line that declares it, its
     * measured length and its screen row.
     *
     * <p>Every value is restated from {@code app/cbl/COCRDUPC.cbl} rather than read from
     * {@link CardUpdateResponse.Messages}, so that the table is an independent oracle. The lengths
     * are stated as well as the characters, because a length assertion is what catches a trim that a
     * character assertion on a trimmed value would miss.
     */
    private static List<DeclaredMessage> declaredMessages() {
        return List.of(
                new DeclaredMessage(135, "File Error: ", 12, MessageRow.ERROR),
                new DeclaredMessage(161, "Details of selected card shown above", 36,
                        MessageRow.INFORMATION),
                new DeclaredMessage(163, "Please enter Account and Card Number", 36,
                        MessageRow.INFORMATION),
                new DeclaredMessage(165, "Update card details presented above.", 36,
                        MessageRow.INFORMATION),
                new DeclaredMessage(167, "Changes validated.Press F5 to save", 34,
                        MessageRow.INFORMATION),
                new DeclaredMessage(169, "Changes committed to database", 29,
                        MessageRow.INFORMATION),
                new DeclaredMessage(171, "Changes unsuccessful. Please try again", 38,
                        MessageRow.INFORMATION),
                new DeclaredMessage(176, "PF03 pressed.Exiting              ", 34, MessageRow.ERROR),
                new DeclaredMessage(184, "Card name can only contain alphabets and spaces", 47,
                        MessageRow.ERROR),
                new DeclaredMessage(188, "No change detected with respect to values fetched.", 50,
                        MessageRow.ERROR),
                new DeclaredMessage(196, "Card Active Status must be Y or N", 33, MessageRow.ERROR),
                new DeclaredMessage(198, "Card expiry month must be between 1 and 12", 42,
                        MessageRow.ERROR),
                new DeclaredMessage(206, "Could not lock record for update", 32, MessageRow.ERROR),
                new DeclaredMessage(208, "Record changed by some one else. Please review", 46,
                        MessageRow.ERROR),
                new DeclaredMessage(214, "Looks Good.... so far", 21, MessageRow.ERROR));
    }

    /**
     * Puts a text on the row the program routes it to and returns the response that carries it.
     *
     * @param declared the text and its row
     * @return a response carrying the text on its own row and nothing on the other
     */
    private static CardUpdateResponse carrying(DeclaredMessage declared) {
        return switch (declared.row()) {
            case INFORMATION -> fullyPopulated(declared.text(), null, false, List.of(), null);
            case ERROR -> fullyPopulated(null, declared.text(), true, List.of(), null);
        };
    }

    /**
     * Reads back whichever row a text was placed on.
     *
     * @param response the response carrying the text
     * @param declared the text and its row
     * @return the value read back from that row
     */
    private static String carriedText(CardUpdateResponse response, DeclaredMessage declared) {
        return switch (declared.row()) {
            case INFORMATION -> response.informationMessage();
            case ERROR -> response.errorMessage();
        };
    }

    @Nested
    @DisplayName("The fifteen declared operator texts")
    class MessageLiteralContract {

        @Test
        @DisplayName("each text the program declares is carried on its own screen row, survives a "
                + "wire round trip and reads back byte for byte at its exact length")
        void everyDeclaredTextRoundTripsByteForByte() throws JsonProcessingException {
            for (DeclaredMessage declared : declaredMessages()) {
                CardUpdateResponse carrier = carrying(declared);

                assertThat(carriedText(carrier, declared))
                        .as("program line %d, on construction", declared.programLine())
                        .isEqualTo(declared.text())
                        .hasSize(declared.length());

                CardUpdateResponse revived = wireRoundTrip(carrier);

                assertThat(carriedText(revived, declared))
                        .as("program line %d, after a wire round trip", declared.programLine())
                        .isEqualTo(declared.text())
                        .hasSize(declared.length());
                assertThat(revived).isEqualTo(carrier);
            }
        }

        @Test
        @DisplayName("every text is offered by the contract exactly as the program declares it, so "
                + "the constant and the independently restated characters agree")
        void everyDeclaredTextMatchesTheOfferedConstant() {
            List<String> offered = List.of(
                    CardUpdateResponse.Messages.FILE_ERROR_PREFIX,
                    CardUpdateResponse.Messages.FOUND_CARDS_FOR_ACCOUNT,
                    CardUpdateResponse.Messages.PROMPT_FOR_SEARCH_KEYS,
                    CardUpdateResponse.Messages.PROMPT_FOR_CHANGES,
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.CONFIRM_UPDATE_SUCCESS,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    CardUpdateResponse.Messages.EXIT_MESSAGE,
                    CardUpdateResponse.Messages.NAME_MUST_BE_ALPHA,
                    CardUpdateResponse.Messages.NO_CHANGES_DETECTED,
                    CardUpdateResponse.Messages.CARD_STATUS_MUST_BE_YES_NO,
                    CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID,
                    CardUpdateResponse.Messages.COULD_NOT_LOCK_FOR_UPDATE,
                    CardUpdateResponse.Messages.DATA_WAS_CHANGED,
                    CardUpdateResponse.Messages.CODING_TO_BE_DONE);

            List<DeclaredMessage> expected = declaredMessages();
            assertThat(offered).hasSameSizeAs(expected);

            for (int index = 0; index < expected.size(); index++) {
                DeclaredMessage declared = expected.get(index);
                assertThat(offered.get(index))
                        .as("program line %d", declared.programLine())
                        .isEqualTo(declared.text())
                        .hasSize(declared.length());
            }
        }

        @Test
        @DisplayName("the file-error prefix keeps the trailing space that is part of its value, and "
                + "carries no status code, store name or query fragment")
        void theFileErrorPrefixKeepsItsTrailingSpace() throws JsonProcessingException {
            String prefix = CardUpdateResponse.Messages.FILE_ERROR_PREFIX;

            assertThat(prefix).isEqualTo("File Error: ").hasSize(12).endsWith(" ");
            assertThat(prefix.stripTrailing()).hasSize(11);
            assertThat(prefix).doesNotContainPattern("[0-9]");
            assertThat(prefix).doesNotContain("select", "insert", "table", "schema", "carddemo.");

            CardUpdateResponse revived =
                    wireRoundTrip(fullyPopulated(null, prefix, true, List.of(), null));

            assertThat(revived.errorMessage()).isEqualTo("File Error: ").hasSize(12).endsWith(" ");
        }

        @Test
        @DisplayName("the confirmation text has no space after its full stop while the failure text "
                + "does, and that inconsistency is the contract rather than a defect")
        void theTwoAdjacentTextsDifferInSpacingAfterTheFullStop() throws JsonProcessingException {
            String confirmation = CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION;
            String failure = CardUpdateResponse.Messages.INFORM_FAILURE;

            assertThat(confirmation).isEqualTo("Changes validated.Press F5 to save").hasSize(34);
            assertThat(failure).isEqualTo("Changes unsuccessful. Please try again").hasSize(38);

            assertThat(confirmation.charAt(confirmation.indexOf('.') + 1))
                    .as("program line 167 runs straight on from its full stop")
                    .isEqualTo('P');
            assertThat(confirmation).doesNotContain(". ");

            assertThat(failure.charAt(failure.indexOf('.') + 1))
                    .as("program line 171 does put a space after its full stop")
                    .isEqualTo(' ');
            assertThat(failure).contains(". ");

            assertThat(confirmation).isNotEqualTo(failure);
            assertThat(confirmation.contains(". "))
                    .as("the two structurally identical texts must not be harmonised: one is "
                            + "spaced after its full stop and the other is not")
                    .isNotEqualTo(failure.contains(". "));

            CardUpdateResponse both = fullyPopulated(confirmation, failure, true, List.of(), null);
            CardUpdateResponse revived = wireRoundTrip(both);

            assertThat(revived.informationMessage()).isEqualTo(confirmation).doesNotContain(". ");
            assertThat(revived.errorMessage()).isEqualTo(failure).contains(". ");
        }

        @Test
        @DisplayName("the exit text keeps its fourteen trailing pad spaces and runs straight on from "
                + "its full stop, because the padding is part of the declared value")
        void theExitTextKeepsItsTrailingPadSpaces() throws JsonProcessingException {
            String exit = CardUpdateResponse.Messages.EXIT_MESSAGE;

            assertThat(exit).isEqualTo("PF03 pressed.Exiting              ").hasSize(34);
            assertThat(exit.stripTrailing()).isEqualTo("PF03 pressed.Exiting").hasSize(20);
            assertThat(exit.length() - exit.stripTrailing().length()).isEqualTo(14);
            assertThat(exit.charAt(exit.indexOf('.') + 1)).isEqualTo('E');

            assertThat(wireRoundTrip(fullyPopulated(null, exit, true, List.of(), null))
                    .errorMessage()).isEqualTo(exit).hasSize(34);
        }

        @Test
        @DisplayName("the still-unfinished-path text runs to four full stops, not three and not five")
        void theUnfinishedPathTextRunsToFourFullStops() {
            String text = CardUpdateResponse.Messages.CODING_TO_BE_DONE;

            assertThat(text).isEqualTo("Looks Good.... so far").hasSize(21);
            assertThat(text).contains("....").doesNotContain(".....");
            assertThat(text.chars().filter(character -> character == '.').count()).isEqualTo(4L);
        }

        @Test
        @DisplayName("the expiry-month text names its bounds as 1 and 12 rather than zero-padding "
                + "them, even though the field it guards is two characters wide")
        void theExpiryMonthTextNamesItsBoundsWithoutZeroPadding() {
            assertThat(CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID)
                    .isEqualTo("Card expiry month must be between 1 and 12")
                    .hasSize(42)
                    .contains("1 and 12")
                    .doesNotContain("01 and 12");
        }

        @Test
        @DisplayName("the lock text is the single generic wording this program declares, and neither "
                + "of the account-update program's two record-specific variants appears")
        void theLockTextIsTheSingleGenericWording() {
            String lock = CardUpdateResponse.Messages.COULD_NOT_LOCK_FOR_UPDATE;

            assertThat(lock).isEqualTo("Could not lock record for update").hasSize(32);
            assertThat(lock)
                    .as("the two 40- and 41-character record-specific variants belong to the "
                            + "account-update screen and are neither imported nor unified here")
                    .doesNotContain("account record")
                    .doesNotContain("customer record");

            assertThat(declaredMessages())
                    .as("no declared text on this screen names a record kind in a lock message")
                    .noneMatch(declared -> declared.text().contains("lock account record"))
                    .noneMatch(declared -> declared.text().contains("lock customer record"));
        }

        @Test
        @DisplayName("the concurrent-change condition reaches a client only as text, and that text "
                + "spells the pronoun as two words")
        void theConcurrentChangeConditionReachesAClientOnlyAsText()
                throws JsonProcessingException {
            String text = CardUpdateResponse.Messages.DATA_WAS_CHANGED;

            assertThat(text).isEqualTo("Record changed by some one else. Please review").hasSize(46);
            assertThat(text).contains("some one").doesNotContain("someone");

            JsonNode payload = payloadOf(fullyPopulated(null, text, true, List.of(), null));

            assertThat(payload.get("errorMessage").asText()).isEqualTo(text);
            assertThat(payload.get("errorMessage").isTextual()).isTrue();
            for (String forbidden : FORBIDDEN_WIRE_KEYS) {
                assertThat(payload.has(forbidden))
                        .as("the condition is reported as text, never as the %s token", forbidden)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the alphabetic rule for the embossed name admits spaces, which is what the "
                + "blank-and-measure idiom behind it actually permits")
        void theAlphabeticRuleTextAdmitsSpaces() {
            assertThat(CardUpdateResponse.Messages.NAME_MUST_BE_ALPHA)
                    .isEqualTo("Card name can only contain alphabets and spaces")
                    .hasSize(47)
                    .contains("and spaces");
        }

        @Test
        @DisplayName("the status text names the two codes it accepts, and the no-change text keeps "
                + "the full stop that ends it")
        void theStatusAndNoChangeTextsAreCarriedAsDeclared() {
            assertThat(CardUpdateResponse.Messages.CARD_STATUS_MUST_BE_YES_NO)
                    .isEqualTo("Card Active Status must be Y or N")
                    .hasSize(33);
            assertThat(CardUpdateResponse.Messages.NO_CHANGES_DETECTED)
                    .isEqualTo("No change detected with respect to values fetched.")
                    .hasSize(50)
                    .endsWith(".");
        }

        @Test
        @DisplayName("every declared text fits the eighty-character error row, so no text can "
                + "overflow the widest carrier this map declares")
        void everyDeclaredTextFitsTheWidestCarrier() {
            assertThat(declaredMessages())
                    .allSatisfy(declared -> assertThat(declared.length())
                            .as("program line %d", declared.programLine())
                            .isLessThanOrEqualTo(MAP_ERROR_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("the six information-row texts all fit the forty-character information row, "
                + "which is why the row split is behavioural and not cosmetic")
        void theInformationRowTextsFitTheInformationRow() {
            List<DeclaredMessage> informationRow = declaredMessages().stream()
                    .filter(declared -> declared.row() == MessageRow.INFORMATION)
                    .toList();

            assertThat(informationRow).hasSize(6);
            assertThat(informationRow)
                    .allSatisfy(declared -> assertThat(declared.length())
                            .as("program line %d", declared.programLine())
                            .isLessThanOrEqualTo(MAP_INFORMATION_MESSAGE_WIDTH));
        }
    }

    @Nested
    @DisplayName("The two message widths unique to the card maps")
    class MessageWidthContract {

        @Test
        @DisplayName("this map's message widths are eighty and forty, deliberately not the "
                + "seventy-eight and forty-five the other maps declare")
        void theMessageWidthsAreThisMapsOwn() {
            assertThat(CardUpdateResponse.ERROR_MESSAGE_LENGTH)
                    .isEqualTo(MAP_ERROR_MESSAGE_WIDTH)
                    .isNotEqualTo(OTHER_MAPS_ERROR_MESSAGE_WIDTH);
            assertThat(CardUpdateResponse.INFORMATION_MESSAGE_LENGTH)
                    .isEqualTo(MAP_INFORMATION_MESSAGE_WIDTH)
                    .isNotEqualTo(OTHER_MAPS_INFORMATION_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("an error message at the full eighty characters round-trips untrimmed and is "
                + "never shortened to the seventy-eight the other thirteen maps use")
        void anEightyCharacterErrorMessageRoundTripsUntrimmed() throws JsonProcessingException {
            String declared = CardUpdateResponse.Messages.NO_CHANGES_DETECTED;
            String atFullWidth = declared + " ".repeat(MAP_ERROR_MESSAGE_WIDTH - declared.length());

            assertThat(atFullWidth).hasSize(80);

            CardUpdateResponse response = fullyPopulated(null, atFullWidth, true, List.of(), null);

            assertThat(response.errorMessage()).isEqualTo(atFullWidth).hasSize(80).endsWith("  ");

            CardUpdateResponse revived = wireRoundTrip(response);

            assertThat(revived.errorMessage())
                    .as("the value crosses the wire at eighty characters, not seventy-eight")
                    .isEqualTo(atFullWidth)
                    .hasSize(MAP_ERROR_MESSAGE_WIDTH);
            assertThat(revived.errorMessage().length())
                    .as("neither the other maps' width nor a trimmed length is ever substituted")
                    .isNotEqualTo(OTHER_MAPS_ERROR_MESSAGE_WIDTH)
                    .isNotEqualTo(declared.length());

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(revived))
                        .as("eighty characters is within this map's own bound")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("an information message at the full forty characters round-trips untrimmed and "
                + "is never widened to the forty-five the account maps use")
        void aFortyCharacterInformationMessageRoundTripsUntrimmed()
                throws JsonProcessingException {
            String declared = CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION;
            String atFullWidth =
                    declared + " ".repeat(MAP_INFORMATION_MESSAGE_WIDTH - declared.length());

            assertThat(atFullWidth).hasSize(40);

            CardUpdateResponse response = fullyPopulated(atFullWidth, null, false, List.of(), null);

            assertThat(response.informationMessage())
                    .isEqualTo(atFullWidth)
                    .hasSize(40)
                    .endsWith("      ");

            CardUpdateResponse revived = wireRoundTrip(response);

            assertThat(revived.informationMessage())
                    .as("the value crosses the wire at forty characters, not forty-five")
                    .isEqualTo(atFullWidth)
                    .hasSize(MAP_INFORMATION_MESSAGE_WIDTH);
            assertThat(revived.informationMessage().length())
                    .isNotEqualTo(OTHER_MAPS_INFORMATION_MESSAGE_WIDTH)
                    .isNotEqualTo(declared.length());

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(revived)).isEmpty();
            }
        }

        @Test
        @DisplayName("an information message at forty-five characters is reported by this map's own "
                + "bound, proving the account maps' width was not adopted")
        void theAccountMapsInformationWidthIsNotAdopted() {
            String atAnotherMapsWidth = "x".repeat(OTHER_MAPS_INFORMATION_MESSAGE_WIDTH);

            CardUpdateResponse response =
                    fullyPopulated(atAnotherMapsWidth, null, false, List.of(), null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                Set<ConstraintViolation<CardUpdateResponse>> violations = validator.validate(response);

                assertThat(violations).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath())
                        .hasToString("informationMessage");
            }

            assertThat(response.informationMessage())
                    .as("a bound reports an over-long value and never alters one")
                    .isEqualTo(atAnotherMapsWidth)
                    .hasSize(45);
        }

        @Test
        @DisplayName("the fifteen published widths are the fifteen the symbolic map declares, in the "
                + "order the map declares them")
        void thePublishedWidthsAreTheMapWidths() {
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

            assertThat(published)
                    .containsExactly(4, 40, 8, 8, 40, 8, 11, 16, 50, 1, 2, 4, 2, 40, 80);
        }

        @Test
        @DisplayName("the expiry day is modelled at two characters even though it is dark, protected "
                + "and never editable, because the stored date has to survive the round trip")
        void theHiddenExpiryDayIsModelledAndCarriedThrough() throws JsonProcessingException {
            assertThat(CardUpdateResponse.EXPIRY_DAY_LENGTH).isEqualTo(2);

            CardUpdateResponse response = firstSubmission(null);

            assertThat(response.expiryDay()).isEqualTo("31").hasSize(2);
            assertThat(payloadOf(response).get("expiryDay").asText()).isEqualTo("31");
            assertThat(wireRoundTrip(response).expiryDay()).isEqualTo("31");
        }
    }

    @Nested
    @DisplayName("The two-state field-error surface and the single summary text")
    class FieldErrorContract {

        @Test
        @DisplayName("per-field errors carry exactly two states, the two are never conflated, and "
                + "several independent field errors coexist")
        void theTwoFieldStatesAreDistinctAndCoexist() {
            List<ErrorResponse.FieldError> errors =
                    List.of(expiryMonthInvalid(), embossedNameMissing(), activeStatusInvalid());

            CardUpdateResponse response = reSubmission(errors);

            assertThat(ErrorResponse.FieldState.values())
                    .as("the legacy screen told exactly two operator mistakes apart")
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID)
                    .hasSize(2);

            assertThat(response.fieldErrors()).hasSize(3);
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.INVALID,
                            ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("expiryMonth", "embossedName", "activeStatus");
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactly(SCREEN_FIELD_EXPIRY_MONTH, SCREEN_FIELD_CARD_NAME,
                            SCREEN_FIELD_CARD_STATUS);

            assertThat(ErrorResponse.FieldState.MISSING)
                    .as("a blank field and a badly filled field need different remedies")
                    .isNotEqualTo(ErrorResponse.FieldState.INVALID);
            assertThat(response.hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("the state is a named value rather than a flag, so it crosses the wire as its "
                + "own name and never as a boolean or an ordinal")
        void theStateCrossesTheWireAsItsOwnName() throws JsonProcessingException {
            JsonNode payload =
                    payloadOf(reSubmission(List.of(expiryMonthInvalid(), embossedNameMissing())));
            JsonNode fieldErrors = payload.get("fieldErrors");

            assertThat(fieldErrors.isArray()).isTrue();
            assertThat(fieldErrors).hasSize(2);

            JsonNode invalid = fieldErrors.get(0).get("state");
            JsonNode missing = fieldErrors.get(1).get("state");

            assertThat(invalid.isTextual()).isTrue();
            assertThat(invalid.isBoolean()).isFalse();
            assertThat(invalid.isNumber()).isFalse();
            assertThat(invalid.asText()).isEqualTo("INVALID");

            assertThat(missing.isTextual()).isTrue();
            assertThat(missing.isBoolean()).isFalse();
            assertThat(missing.isNumber()).isFalse();
            assertThat(missing.asText()).isEqualTo("MISSING");
        }

        @Test
        @DisplayName("a first submission carries no field error at all, because the decoration macro "
                + "fires only once the screen has been re-entered")
        void aFirstSubmissionCarriesNoFieldError() throws JsonProcessingException {
            CardUpdateResponse first =
                    firstSubmission(CardUpdateResponse.Messages.PROMPT_FOR_CHANGES);

            assertThat(first.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(first.navigationContext().firstEntry()).isTrue();
            assertThat(first.fieldErrors()).isNotNull().isEmpty();
            assertThat(first.hasFieldErrors()).isFalse();
            assertThat(first.generalError()).isFalse();
            assertThat(first.errorMessage()).isNull();
            assertThat(first.focusScreenFieldId()).isNull();
            assertThat(payloadOf(first).get("fieldErrors")).isEmpty();

            CardUpdateResponse resubmitted = reSubmission(List.of(expiryMonthInvalid()));

            assertThat(resubmitted.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(resubmitted.navigationContext().reEntry()).isTrue();
            assertThat(resubmitted.fieldErrors()).isNotEmpty();
        }

        @Test
        @DisplayName("exactly one summary text accompanies any number of field errors, and it is the "
                + "first failure's own text rather than a joining of theirs")
        void oneSummaryTextAccompaniesAnyNumberOfFieldErrors() throws JsonProcessingException {
            List<ErrorResponse.FieldError> errors =
                    List.of(expiryMonthInvalid(), embossedNameMissing(), activeStatusInvalid());

            CardUpdateResponse response = reSubmission(errors);
            String summary = response.errorMessage();

            assertThat(summary)
                    .as("the first failure in the legacy cascade wins the single summary row")
                    .isEqualTo(CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID);
            assertThat(summary)
                    .as("the summary is one text, never a concatenation of the field texts")
                    .doesNotContain(CardUpdateResponse.Messages.PROMPT_FOR_NAME)
                    .doesNotContain(CardUpdateResponse.Messages.CARD_STATUS_MUST_BE_YES_NO)
                    .hasSize(42);
            assertThat(response.informationMessage()).isNull();

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("errorMessage").isTextual())
                    .as("one summary row, never an array of rows")
                    .isTrue();
            assertThat(payload.get("errorMessage").isArray()).isFalse();
            assertThat(payload.get("fieldErrors")).hasSize(3);
        }

        @Test
        @DisplayName("the whole-submission failure flag is supplied explicitly and is never inferred "
                + "from a message being present or absent")
        void theFailureFlagIsSuppliedExplicitly() {
            CardUpdateResponse flaggedWithoutText =
                    fullyPopulated(null, null, true, List.of(), null);
            CardUpdateResponse textWithoutFlag = fullyPopulated(
                    CardUpdateResponse.Messages.CODING_TO_BE_DONE, null, false, List.of(), null);

            assertThat(flaggedWithoutText.generalError()).isTrue();
            assertThat(flaggedWithoutText.errorMessage()).isNull();
            assertThat(flaggedWithoutText.informationMessage()).isNull();
            assertThat(flaggedWithoutText.fieldErrors()).isEmpty();

            assertThat(textWithoutFlag.generalError()).isFalse();
            assertThat(textWithoutFlag.informationMessage()).isNotNull();
        }

        @Test
        @DisplayName("a null field-error collection becomes the empty immutable one, so the accessor "
                + "never returns null and never has to be tested for it")
        void aNullCollectionBecomesTheEmptyImmutableOne() {
            CardUpdateResponse response = fullyPopulated(null, null, false, null, null);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
            assertThatThrownBy(() -> response.fieldErrors().add(expiryMonthInvalid()))
                    .as("the substituted empty collection is immutable too")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("the collection is copied on the way in, so a later change to the caller's list "
                + "cannot reach inside a built response")
        void theCollectionIsCopiedOnTheWayIn() {
            List<ErrorResponse.FieldError> callerOwned = new ArrayList<>();
            callerOwned.add(expiryMonthInvalid());

            CardUpdateResponse response = reSubmission(callerOwned);
            assertThat(response.fieldErrors()).hasSize(1);

            callerOwned.add(embossedNameMissing());
            callerOwned.clear();

            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.fieldErrors().get(0).fieldName()).isEqualTo("expiryMonth");
        }

        @Test
        @DisplayName("the exposed collection rejects every mutation, so a caller cannot alter a "
                + "response after it has been built")
        void theExposedCollectionRejectsMutation() {
            CardUpdateResponse response = reSubmission(List.of(expiryMonthInvalid()));
            List<ErrorResponse.FieldError> exposed = response.fieldErrors();

            assertThatThrownBy(() -> exposed.add(embossedNameMissing()))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> exposed.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> exposed.set(0, embossedNameMissing()))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(exposed::clear).isInstanceOf(UnsupportedOperationException.class);

            assertThat(response.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("a null entry in the collection is rejected outright, because an entry with no "
                + "state is an error no client could act on")
        void aNullEntryIsRejectedOutright() {
            List<ErrorResponse.FieldError> withANullEntry = new ArrayList<>();
            withANullEntry.add(expiryMonthInvalid());
            withANullEntry.add(null);

            assertThatThrownBy(() -> reSubmission(withANullEntry))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the focus hint is a screen-field identifier only, never a coordinate, a "
                + "sentinel or an attribute value")
        void theFocusHintIsAnIdentifierOnly() throws JsonProcessingException {
            JsonNode focus = payloadOf(reSubmission(List.of(expiryMonthInvalid())))
                    .get("focusScreenFieldId");

            assertThat(focus.isTextual()).isTrue();
            assertThat(focus.asText())
                    .isEqualTo(SCREEN_FIELD_EXPIRY_MONTH)
                    .doesNotContain("-1")
                    .doesNotContainPattern("[0-9]");
            assertThat(focus.asText().length())
                    .isLessThanOrEqualTo(CardUpdateResponse.SCREEN_FIELD_ID_LENGTH);
        }
    }

    @Nested
    @DisplayName("The echoed card values")
    class EchoedValueContract {

        @Test
        @DisplayName("every echoed value reads back byte for byte at its full declared width, with "
                + "nothing shortened, space-filled, re-cased or re-rendered")
        void everyEchoedValueReadsBackAtItsFullDeclaredWidth() throws JsonProcessingException {
            CardUpdateResponse response = fullyPopulated(null, null, false, List.of(), null);

            assertThat(response.accountId())
                    .isEqualTo(LEADING_ZERO_ACCOUNT_ID)
                    .hasSize(CardUpdateResponse.ACCOUNT_ID_LENGTH);
            assertThat(response.cardNumber())
                    .isEqualTo(LEADING_ZERO_CARD_NUMBER)
                    .hasSize(CardUpdateResponse.CARD_NUMBER_LENGTH);
            assertThat(response.embossedName())
                    .isEqualTo(MIXED_CASE_EMBOSSED_NAME)
                    .hasSize(CardUpdateResponse.EMBOSSED_NAME_LENGTH);
            assertThat(response.activeStatus())
                    .isEqualTo("Y")
                    .hasSize(CardUpdateResponse.ACTIVE_STATUS_LENGTH);
            assertThat(response.expiryMonth())
                    .isEqualTo("01")
                    .hasSize(CardUpdateResponse.EXPIRY_MONTH_LENGTH);
            assertThat(response.expiryYear())
                    .isEqualTo("2026")
                    .hasSize(CardUpdateResponse.EXPIRY_YEAR_LENGTH);
            assertThat(response.expiryDay())
                    .isEqualTo("31")
                    .hasSize(CardUpdateResponse.EXPIRY_DAY_LENGTH);

            CardUpdateResponse revived = wireRoundTrip(response);

            assertThat(revived.accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(revived.cardNumber()).isEqualTo(LEADING_ZERO_CARD_NUMBER);
            assertThat(revived.embossedName()).isEqualTo(MIXED_CASE_EMBOSSED_NAME).hasSize(50);
            assertThat(revived.activeStatus()).isEqualTo("Y");
            assertThat(revived.expiryMonth()).isEqualTo("01");
            assertThat(revived.expiryYear()).isEqualTo("2026");
            assertThat(revived.expiryDay()).isEqualTo("31");
        }

        @Test
        @DisplayName("a value shorter than its declared width is never padded up, and one carrying "
                + "trailing spaces never has them trimmed")
        void shortValuesAreNeverPaddedAndPaddedValuesAreNeverTrimmed()
                throws JsonProcessingException {
            String shortAccountId = "42";
            String nameWithSignificantTrailingSpaces = "Mary Ann" + " ".repeat(6);

            CardUpdateResponse response = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    shortAccountId,
                    FICTIONAL_CARD_NUMBER,
                    nameWithSignificantTrailingSpaces,
                    "Y",
                    "1",
                    "26",
                    "3",
                    null,
                    NEXT_ROUTE,
                    NavigationContext.empty(),
                    SEALED_PROOF_STAND_IN);

            assertThat(response.accountId())
                    .as("a bound reports an over-long value and never pads a short one")
                    .isEqualTo(shortAccountId)
                    .hasSize(2);
            assertThat(response.embossedName())
                    .isEqualTo(nameWithSignificantTrailingSpaces)
                    .hasSize(14)
                    .endsWith("      ");
            assertThat(response.expiryMonth()).isEqualTo("1").hasSize(1);
            assertThat(response.expiryYear()).isEqualTo("26").hasSize(2);
            assertThat(response.expiryDay()).isEqualTo("3").hasSize(1);

            CardUpdateResponse revived = wireRoundTrip(response);

            assertThat(revived.accountId()).isEqualTo(shortAccountId).hasSize(2);
            assertThat(revived.embossedName())
                    .isEqualTo(nameWithSignificantTrailingSpaces)
                    .hasSize(14);
            assertThat(revived.expiryMonth()).isEqualTo("1");
            assertThat(revived.expiryDay()).isEqualTo("3");

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(revived))
                        .as("a short value is within every maximum-length bound")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the embossed name is handed back exactly as it was produced, because the fold "
                + "belongs to the service and this contract transforms nothing")
        void theEmbossedNameIsHandedBackExactlyAsProduced() throws JsonProcessingException {
            CardUpdateResponse mixedCase = fullyPopulated(null, null, false, List.of(), null);

            assertThat(mixedCase.embossedName())
                    .as("a mixed-case value at the full declared width survives untouched")
                    .isEqualTo(MIXED_CASE_EMBOSSED_NAME)
                    .hasSize(50)
                    .isNotEqualTo("MARY ANN DE LA CRUZ-O'BRIEN SMITHSON JONES      XY")
                    .contains("Mary Ann")
                    .contains("de la");

            assertThat(payloadOf(mixedCase).get("embossedName").asText())
                    .isEqualTo(MIXED_CASE_EMBOSSED_NAME);
            assertThat(wireRoundTrip(mixedCase).embossedName())
                    .isEqualTo(MIXED_CASE_EMBOSSED_NAME);
        }

        @Test
        @DisplayName("a name differing only in letter case yields a different value, which is why a "
                + "case-only edit is legitimately reported as no change detected")
        void aCaseOnlyDifferenceIsNotFoldedAway() {
            CardUpdateResponse asProduced = firstSubmission(
                    CardUpdateResponse.Messages.NO_CHANGES_DETECTED);
            CardUpdateResponse recased = new CardUpdateResponse(
                    asProduced.transactionName(),
                    asProduced.title01(),
                    asProduced.currentDate(),
                    asProduced.programName(),
                    asProduced.title02(),
                    asProduced.currentTime(),
                    asProduced.accountId(),
                    asProduced.cardNumber(),
                    "MARY ANN DE LA CRUZ-O'BRIEN SMITHSON JONES      XY",
                    asProduced.activeStatus(),
                    asProduced.expiryMonth(),
                    asProduced.expiryYear(),
                    asProduced.expiryDay(),
                    asProduced.informationMessage(),
                    asProduced.nextRoute(),
                    asProduced.navigationContext(),
                    asProduced.concurrencyToken());

            assertThat(recased.embossedName()).isNotEqualTo(asProduced.embossedName());
            assertThat(recased).isNotEqualTo(asProduced);
            assertThat(asProduced.informationMessage())
                    .isEqualTo("No change detected with respect to values fetched.");
        }

        @Test
        @DisplayName("the embossed name admits an embedded space, because the legacy alphabetic check "
                + "blanks the letters and then measures what is left")
        void theEmbossedNameAdmitsAnEmbeddedSpace() {
            assertThat(MIXED_CASE_EMBOSSED_NAME).contains(" ");

            CardUpdateResponse response = fullyPopulated(null, null, false, List.of(), null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response))
                        .as("a name with interior spaces is a value the legacy screen accepts")
                        .isEmpty();
            }

            assertThat(response.embossedName()).isEqualTo(MIXED_CASE_EMBOSSED_NAME);
        }

        @Test
        @DisplayName("the expiry month, year and day stay three separate bounded values and are "
                + "never merged into one")
        void theThreeExpiryPartsStaySeparate() throws JsonProcessingException {
            CardUpdateResponse response = fullyPopulated(null, null, false, List.of(), null);
            JsonNode payload = payloadOf(response);

            assertThat(payload.get("expiryMonth").isTextual()).isTrue();
            assertThat(payload.get("expiryYear").isTextual()).isTrue();
            assertThat(payload.get("expiryDay").isTextual()).isTrue();

            assertThat(payload.get("expiryMonth").isNumber()).isFalse();
            assertThat(payload.get("expiryYear").isNumber()).isFalse();
            assertThat(payload.get("expiryDay").isNumber()).isFalse();

            assertThat(payload.has("expiryDate"))
                    .as("the three parts are never merged into a single date value")
                    .isFalse();
            assertThat(payload.has("expiry")).isFalse();
            assertThat(payload.has("expirationDate")).isFalse();
            assertThat(payload.has("expiryYearMonth")).isFalse();

            assertThat(response.expiryMonth())
                    .as("a zero-padded month is never reduced to a bare digit")
                    .isEqualTo("01")
                    .isNotEqualTo("1")
                    .hasSize(2);
            assertThat(payload.get("expiryMonth").asText()).isEqualTo("01").isNotEqualTo("1");
            assertThat(wireRoundTrip(response).expiryMonth()).isEqualTo("01").hasSize(2);
        }

        @Test
        @DisplayName("a month outside the range the operator text names still round-trips, because "
                + "range checking is the service's work and not this contract's")
        void anOutOfRangeMonthStillRoundTrips() throws JsonProcessingException {
            CardUpdateResponse response = reSubmission(List.of(expiryMonthInvalid()));

            assertThat(response.expiryMonth()).isEqualTo("13").hasSize(2);
            assertThat(response.errorMessage())
                    .isEqualTo(CardUpdateResponse.Messages.CARD_EXPIRY_MONTH_NOT_VALID);
            assertThat(wireRoundTrip(response).expiryMonth()).isEqualTo("13");

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response))
                        .as("no range constraint is declared, so the value is reported by text only")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the account identifier and the card number keep every leading zero, because "
                + "they are fixed-width identifiers and never numbers")
        void theIdentifiersKeepEveryLeadingZero() throws JsonProcessingException {
            CardUpdateResponse response = fullyPopulated(null, null, false, List.of(), null);
            JsonNode payload = payloadOf(response);

            assertThat(response.accountId())
                    .isEqualTo("00000000001")
                    .isNotEqualTo("1")
                    .hasSize(11)
                    .startsWith("0");
            assertThat(response.cardNumber())
                    .isEqualTo("0000000000000001")
                    .isNotEqualTo("1")
                    .hasSize(16)
                    .startsWith("0");

            assertThat(payload.get("accountId").isTextual()).isTrue();
            assertThat(payload.get("accountId").isNumber()).isFalse();
            assertThat(payload.get("accountId").asText()).isEqualTo("00000000001");
            assertThat(payload.get("cardNumber").isTextual()).isTrue();
            assertThat(payload.get("cardNumber").isNumber()).isFalse();
            assertThat(payload.get("cardNumber").asText()).isEqualTo("0000000000000001");

            CardUpdateResponse revived = wireRoundTrip(response);

            assertThat(revived.accountId()).isEqualTo("00000000001").hasSize(11);
            assertThat(revived.cardNumber()).isEqualTo("0000000000000001").hasSize(16);
        }

        @Test
        @DisplayName("the card number crosses the wire whole, never shortened and never obscured, and "
                + "no security code accompanies it")
        void theCardNumberCrossesTheWireWholeAndUnaccompanied() throws JsonProcessingException {
            CardUpdateResponse response = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    LEADING_ZERO_ACCOUNT_ID,
                    FICTIONAL_CARD_NUMBER,
                    MIXED_CASE_EMBOSSED_NAME,
                    "Y",
                    "01",
                    "2026",
                    "31",
                    null,
                    NEXT_ROUTE,
                    NavigationContext.empty(),
                    SEALED_PROOF_STAND_IN);

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("cardNumber").asText())
                    .isEqualTo(FICTIONAL_CARD_NUMBER)
                    .hasSize(16)
                    .doesNotContain("*")
                    .doesNotContain("X")
                    .doesNotContain("#")
                    .doesNotContain("•");

            assertThat(payload.has("cvv"))
                    .as("the legacy screen never carried a security code, so neither does this")
                    .isFalse();
            assertThat(payload.has("securityCode")).isFalse();
            assertThat(payload.has("maskedCardNumber")).isFalse();
            assertThat(payload.has("cardNumberLast4")).isFalse();
        }
    }

    @Nested
    @DisplayName("The status vocabulary the raw active-status character belongs to")
    class ActiveStatusVocabularyContract {

        @Test
        @DisplayName("the vocabulary has exactly two constants, and neither a synthetic fallback nor "
                + "any catch-all constant exists")
        void theVocabularyHasExactlyTwoConstantsAndNoFallback() {
            assertThat(CardStatus.values())
                    .containsExactly(CardStatus.Y, CardStatus.N)
                    .hasSize(2);

            assertThat(CardStatus.Y.getCode()).isEqualTo('Y');
            assertThat(CardStatus.N.getCode()).isEqualTo('N');

            List<String> names = List.of(CardStatus.Y.name(), CardStatus.N.name());
            assertThat(names)
                    .as("a synthetic constant would be a value the estate never produces")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("the active predicate answers true only for the active constant")
        void theActivePredicateAnswersTrueOnlyForTheActiveConstant() {
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("the lookup never throws: an unrecognised character yields an empty result and "
                + "no case folding is applied on the way in")
        void theLookupNeverThrowsAndNeverFolds() {
            assertThat(CardStatus.fromCode('Y')).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode('N')).contains(CardStatus.N);

            assertThatCode(() -> CardStatus.fromCode('X')).doesNotThrowAnyException();
            assertThat(CardStatus.fromCode('X')).isEmpty();
            assertThat(CardStatus.fromCode(' ')).isEmpty();

            assertThat(CardStatus.fromCode('y'))
                    .as("the legacy comparison tested the byte as supplied, so a lowercase code "
                            + "does not resolve")
                    .isEmpty();
            assertThat(CardStatus.fromCode('n')).isEmpty();
        }

        @Test
        @DisplayName("the validation-flag characters are not status values, because they belong to "
                + "the field-error surface rather than to this vocabulary")
        void theValidationFlagCharactersAreNotStatusValues() {
            assertThat(CardStatus.fromCode('0'))
                    .as("the flag characters belong to the decoration surface, not to the status")
                    .isEmpty();
            assertThat(CardStatus.fromCode('B')).isEmpty();
        }

        @Test
        @DisplayName("the status vocabulary and the field-error vocabulary are two separate things, "
                + "and the field-error one is a member of the response contract rather than a "
                + "tenth member of the domain vocabulary set")
        void theStatusAndFieldErrorVocabulariesAreSeparate() {
            // ErrorResponse.FieldState is reached here as a nested member of the response contract.
            // That reference compiling at all is the proof that it is not a member of the domain
            // vocabulary package: were it declared there, this qualification would not resolve.
            assertThat(ErrorResponse.FieldState.values()).hasSize(2);
            assertThat(CardStatus.values()).hasSize(2);

            List<String> statusNames = List.of(CardStatus.Y.name(), CardStatus.N.name());
            List<String> fieldStateNames = List.of(ErrorResponse.FieldState.MISSING.name(),
                    ErrorResponse.FieldState.INVALID.name());

            assertThat(statusNames)
                    .as("the status vocabulary borrows no constant from the field-error one")
                    .doesNotContainAnyElementsOf(fieldStateNames);
            assertThat(fieldStateNames)
                    .as("and the field-error vocabulary borrows none from the status one")
                    .doesNotContainAnyElementsOf(statusNames);

            assertThat(CardStatus.fromCode(ErrorResponse.FieldState.MISSING.name()))
                    .as("a field-error state name is not a status code at any length")
                    .isEmpty();
        }

        @Test
        @DisplayName("the string lookup tolerates absence, a wrong length and padding without "
                + "throwing, and never trims before matching")
        void theStringLookupToleratesAbsenceLengthAndPadding() {
            assertThat(CardStatus.fromCode("Y")).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode("N")).contains(CardStatus.N);

            assertThatCode(() -> CardStatus.fromCode((String) null)).doesNotThrowAnyException();
            assertThat(CardStatus.fromCode((String) null)).isEmpty();
            assertThat(CardStatus.fromCode("")).isEmpty();
            assertThat(CardStatus.fromCode("Y ")).as("no trimming before matching").isEmpty();
            assertThat(CardStatus.fromCode("YN")).isEmpty();
            assertThat(CardStatus.fromCode("y")).isEmpty();
        }

        @Test
        @DisplayName("the response keeps the raw character rather than the typed constant, so a code "
                + "outside the vocabulary round-trips instead of being rejected")
        void theResponseKeepsTheRawCharacter() throws JsonProcessingException {
            CardUpdateResponse outOfVocabulary = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    LEADING_ZERO_ACCOUNT_ID,
                    LEADING_ZERO_CARD_NUMBER,
                    MIXED_CASE_EMBOSSED_NAME,
                    "X",
                    "01",
                    "2026",
                    "31",
                    null,
                    NEXT_ROUTE,
                    NavigationContext.empty(),
                    SEALED_PROOF_STAND_IN);

            assertThat(outOfVocabulary.activeStatus()).isEqualTo("X").hasSize(1);
            assertThat(CardStatus.fromCode(outOfVocabulary.activeStatus())).isEmpty();

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(outOfVocabulary))
                        .as("a code the batch readers would accept must not be rejected here")
                        .isEmpty();
            }

            JsonNode payload = payloadOf(outOfVocabulary);

            assertThat(payload.get("activeStatus").isTextual()).isTrue();
            assertThat(payload.get("activeStatus").asText()).isEqualTo("X");
            assertThat(wireRoundTrip(outOfVocabulary).activeStatus()).isEqualTo("X");

            CardUpdateResponse active = fullyPopulated(null, null, false, List.of(), null);
            Optional<CardStatus> resolved = CardStatus.fromCode(active.activeStatus());

            assertThat(resolved).contains(CardStatus.Y);
            assertThat(resolved.map(CardStatus::isActive)).contains(Boolean.TRUE);
        }
    }

    @Nested
    @DisplayName("The echoed navigation state")
    class NavigationStateContract {

        @Test
        @DisplayName("the navigation state is carried whole, and every identifier in it round-trips "
                + "unchanged including the leading zeros")
        void theNavigationStateIsCarriedWhole() throws JsonProcessingException {
            NavigationContext carried = populatedNavigationContext();
            CardUpdateResponse response = fullyPopulated(null, null, false, List.of(), null);

            assertThat(response.navigationContext())
                    .as("the state is carried, not re-implemented")
                    .isEqualTo(carried);
            assertThat(response.navigationContext().accountId())
                    .isEqualTo("00000000001")
                    .isNotEqualTo("1")
                    .hasSize(11);
            assertThat(response.navigationContext().customerId())
                    .isEqualTo("000000001")
                    .isNotEqualTo("1")
                    .hasSize(9);
            assertThat(response.navigationContext().cardNumber())
                    .isEqualTo("0000000000000001")
                    .hasSize(16);
            assertThat(response.navigationContext().userId()).isEqualTo("USER0001");
            assertThat(response.navigationContext().fromTransactionId()).isEqualTo("CCUP");
            assertThat(response.navigationContext().toProgram()).isEqualTo("COCRDUPC");
            assertThat(response.navigationContext().lastMap()).isEqualTo("CCRDUPA");
            assertThat(response.navigationContext().lastMapset()).isEqualTo("COCRDUP");

            CardUpdateResponse revived = wireRoundTrip(response);

            assertThat(revived.navigationContext()).isEqualTo(carried);
            assertThat(revived.navigationContext().accountId()).isEqualTo("00000000001");
            assertThat(revived.navigationContext().customerId()).isEqualTo("000000001");
            assertThat(revived.navigationContext().cardNumber()).isEqualTo("0000000000000001");

            JsonNode nested = payloadOf(response).get("navigationContext");

            assertThat(nested.isObject()).isTrue();
            assertThat(nested.get("accountId").isTextual()).isTrue();
            assertThat(nested.get("accountId").asText()).isEqualTo("00000000001");
        }

        @Test
        @DisplayName("a response carries no navigation state at all when the caller has none, and the "
                + "absent state is omitted from the payload rather than emitted as null")
        void anAbsentNavigationStateIsOmitted() throws JsonProcessingException {
            CardUpdateResponse response = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    LEADING_ZERO_ACCOUNT_ID,
                    LEADING_ZERO_CARD_NUMBER,
                    MIXED_CASE_EMBOSSED_NAME,
                    "Y",
                    "01",
                    "2026",
                    "31",
                    null,
                    NEXT_ROUTE,
                    null,
                    SEALED_PROOF_STAND_IN);

            assertThat(response.navigationContext()).isNull();
            assertThat(payloadOf(response).has("navigationContext")).isFalse();
            assertThat(wireRoundTrip(response).navigationContext()).isNull();
        }

        @Test
        @DisplayName("the entry state travels with the response, so the first-entry and re-entry "
                + "shapes are distinguishable by a client")
        void theEntryStateTravelsWithTheResponse() {
            CardUpdateResponse first = firstSubmission(null);
            CardUpdateResponse resubmitted = reSubmission(List.of(embossedNameMissing()));

            assertThat(first.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(first.navigationContext().firstEntry()).isTrue();
            assertThat(first.navigationContext().reEntry()).isFalse();

            assertThat(resubmitted.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(resubmitted.navigationContext().reEntry()).isTrue();
            assertThat(resubmitted.navigationContext().firstEntry()).isFalse();

            assertThat(NavigationContext.ProgramContext.values())
                    .containsExactly(NavigationContext.ProgramContext.ENTER,
                            NavigationContext.ProgramContext.REENTER);
        }
    }

    @Nested
    @DisplayName("The declared bounds and what they may and may not do")
    class DeclaredBoundContract {

        @Test
        @DisplayName("a response whose every component is absent reports no violation at all, "
                + "because the only rule the contract declares is a maximum length")
        void aWhollyAbsentResponseReportsNoViolation() {
            CardUpdateResponse empty = new CardUpdateResponse(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Set<ConstraintViolation<CardUpdateResponse>> violations =
                        factory.getValidator().validate(empty);

                assertThat(violations)
                        .as("no presence, pattern, digit or range rule may fire on this contract, "
                                + "because the legacy cascade is ordered and first-error-wins")
                        .isEmpty();
            }

            assertThat(empty.transactionName()).isNull();
            assertThat(empty.title01()).isNull();
            assertThat(empty.currentDate()).isNull();
            assertThat(empty.programName()).isNull();
            assertThat(empty.title02()).isNull();
            assertThat(empty.currentTime()).isNull();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.embossedName()).isNull();
            assertThat(empty.activeStatus()).isNull();
            assertThat(empty.expiryMonth()).isNull();
            assertThat(empty.expiryYear()).isNull();
            assertThat(empty.expiryDay()).isNull();
            assertThat(empty.informationMessage()).isNull();
            assertThat(empty.errorMessage()).isNull();
            assertThat(empty.generalError()).isFalse();
            assertThat(empty.fieldErrors()).isNotNull().isEmpty();
            assertThat(empty.focusScreenFieldId()).isNull();
            assertThat(empty.nextRoute()).isNull();
            assertThat(empty.navigationContext()).isNull();
            assertThat(empty.concurrencyToken()).isNull();
        }

        @Test
        @DisplayName("a blank value passes every declared bound, so a space-filled screen field is "
                + "ordinary rather than exceptional")
        void aBlankValuePassesEveryDeclaredBound() {
            CardUpdateResponse blanks = new CardUpdateResponse(
                    "    ",
                    " ".repeat(40),
                    "        ",
                    "        ",
                    " ".repeat(40),
                    "        ",
                    " ".repeat(11),
                    " ".repeat(16),
                    " ".repeat(50),
                    " ",
                    "  ",
                    "    ",
                    "  ",
                    " ".repeat(MAP_INFORMATION_MESSAGE_WIDTH),
                    NEXT_ROUTE,
                    NavigationContext.empty(),
                    SEALED_PROOF_STAND_IN);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(blanks))
                        .as("no blank-rejecting rule may be declared on this contract")
                        .isEmpty();
            }

            assertThat(blanks.accountId()).hasSize(11).isBlank();
            assertThat(blanks.embossedName()).hasSize(50).isBlank();
            assertThat(blanks.informationMessage()).hasSize(40).isBlank();
        }

        @Test
        @DisplayName("an over-long value is reported once, on its own path, and the value itself is "
                + "left exactly as it was supplied")
        void anOverLongValueIsReportedWithoutBeingAltered() {
            String tooLongForOneCharacter = "YN";
            CardUpdateResponse response = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    LEADING_ZERO_ACCOUNT_ID,
                    LEADING_ZERO_CARD_NUMBER,
                    MIXED_CASE_EMBOSSED_NAME,
                    tooLongForOneCharacter,
                    "01",
                    "2026",
                    "31",
                    null,
                    NEXT_ROUTE,
                    NavigationContext.empty(),
                    SEALED_PROOF_STAND_IN);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Set<ConstraintViolation<CardUpdateResponse>> violations =
                        factory.getValidator().validate(response);

                assertThat(violations).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath())
                        .hasToString("activeStatus");
            }

            assertThat(response.activeStatus()).isEqualTo(tooLongForOneCharacter).hasSize(2);
        }

        @Test
        @DisplayName("the route and the sealed proof carry no width rule, because neither is a "
                + "fixed-width screen field")
        void theRouteAndTheSealedProofCarryNoWidthRule() {
            String farLongerThanAnyScreenField = "x".repeat(4096);
            CardUpdateResponse response = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    LEADING_ZERO_ACCOUNT_ID,
                    LEADING_ZERO_CARD_NUMBER,
                    MIXED_CASE_EMBOSSED_NAME,
                    "Y",
                    "01",
                    "2026",
                    "31",
                    null,
                    "/api/cards/" + farLongerThanAnyScreenField,
                    NavigationContext.empty(),
                    farLongerThanAnyScreenField);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response))
                        .as("a width rule on either would couple this contract to something that is "
                                + "not a screen field")
                        .isEmpty();
            }

            assertThat(response.concurrencyToken())
                    .isEqualTo(farLongerThanAnyScreenField)
                    .hasSize(4096);
            assertThat(response.nextRoute()).hasSize(4107);
        }
    }

    @Nested
    @DisplayName("The wire contract, and everything deliberately absent from it")
    class WireContract {

        /** A response with every one of the twenty-one components supplied. */
        private CardUpdateResponse whollyPopulated() {
            return fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    true,
                    List.of(expiryMonthInvalid(), embossedNameMissing()),
                    SCREEN_FIELD_EXPIRY_MONTH);
        }

        @Test
        @DisplayName("the serialised contract carries exactly the twenty-one keys the type declares, "
                + "which is what proves nothing has been added and nothing dropped")
        void theSerialisedContractCarriesExactlyTheDeclaredKeys() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(whollyPopulated()));

            assertThat(keys)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_WIRE_KEYS)
                    .hasSize(21);
            assertThat(keys).startsWith("transactionName");
            assertThat(keys).endsWith("concurrencyToken");
        }

        @Test
        @DisplayName("neither function-key legend the symbolic map carries appears in the contract, "
                + "because a caption conveys no state a client needs")
        void neitherFunctionKeyLegendAppears() throws JsonProcessingException {
            JsonNode payload = payloadOf(whollyPopulated());

            assertThat(payload.has("fkeys")).isFalse();
            assertThat(payload.has("fkeysc")).isFalse();
            assertThat(payload.has("functionKeys")).isFalse();
            assertThat(payload.has("functionKeyLegend")).isFalse();

            assertThat(EXPECTED_WIRE_KEYS)
                    .as("no declared key names a legend or a caption")
                    .noneMatch(key -> key.contains("fkey"))
                    .noneMatch(key -> key.contains("Fkey"))
                    .noneMatch(key -> key.contains("legend"))
                    .noneMatch(key -> key.contains("Legend"))
                    .noneMatch(key -> key.contains("caption"));
        }

        @Test
        @DisplayName("no readable version marker, entity tag, row version, timestamp, before-image or "
                + "cursor reaches a client, and no 3270 rendering artefact does either")
        void noReadableVersionMarkerAndNoRenderingArtefactReachesAClient()
                throws JsonProcessingException {
            JsonNode payload = payloadOf(whollyPopulated());

            for (String forbidden : FORBIDDEN_WIRE_KEYS) {
                assertThat(payload.has(forbidden))
                        .as("the contract must carry no %s key", forbidden)
                        .isFalse();
            }

            List<String> renderingArtefacts = List.of(
                    "attribute", "colour", "color", "highlight", "programmedSymbol", "validation",
                    "tioa", "filler", "row", "column", "coordinate", "cursorPosition", "mask",
                    "dfhred", "dfhgreen", "screenMask", "editedValue");

            for (String artefact : renderingArtefacts) {
                assertThat(payload.has(artefact))
                        .as("the contract must carry no %s key", artefact)
                        .isFalse();
            }

            assertThat(payload.get("errorMessage").asText())
                    .as("the concurrency and lock conditions are text and nothing else")
                    .isEqualTo(CardUpdateResponse.Messages.INFORM_FAILURE);
        }

        @Test
        @DisplayName("the body is the module's own error shape and exposes no RFC 7807 property, "
                + "because the standard problem representation is deliberately switched off")
        void theBodyIsTheModulesOwnErrorShapeAndNotAProblemDocument()
                throws JsonProcessingException {
            JsonNode informational = payloadOf(
                    firstSubmission(CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION));
            JsonNode reEntry = payloadOf(reSubmission(List.of(expiryMonthInvalid())));

            for (String property : PROBLEM_DETAIL_PROPERTIES) {
                assertThat(informational.has(property))
                        .as("problem-document property %s", property)
                        .isFalse();
                assertThat(reEntry.has(property))
                        .as("problem-document property %s", property)
                        .isFalse();
            }

            assertThat(informational.get("generalError").isBoolean()).isTrue();
            assertThat(informational.get("fieldErrors").isArray()).isTrue();
        }

        @Test
        @DisplayName("an absent component is omitted from the payload rather than emitted as null, "
                + "while the failure flag and the collection are always present")
        void absentComponentsAreOmittedAndTheAlwaysPresentOnesAreNot()
                throws JsonProcessingException {
            JsonNode payload =
                    payloadOf(firstSubmission(CardUpdateResponse.Messages.PROMPT_FOR_CHANGES));

            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.has("focusScreenFieldId")).isFalse();

            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
            assertThat(payload.get("informationMessage").asText())
                    .isEqualTo("Update card details presented above.");

            assertThat(payload.toString())
                    .as("nothing is emitted as an explicit null")
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("an unknown incoming property is tolerated, and a payload that omits the "
                + "collection revives with the empty immutable one")
        void anUnknownIncomingPropertyIsTolerated() throws JsonProcessingException {
            String payload = "{\"transactionName\":\"CCUP\","
                    + "\"accountId\":\"" + LEADING_ZERO_ACCOUNT_ID + "\","
                    + "\"cardNumber\":\"" + LEADING_ZERO_CARD_NUMBER + "\","
                    + "\"expiryMonth\":\"01\","
                    + "\"generalError\":false,"
                    + "\"aPropertyThisContractDoesNotDeclare\":\"tolerated\"}";

            CardUpdateResponse revived =
                    moduleEquivalentMapper().readValue(payload, CardUpdateResponse.class);

            assertThat(revived.transactionName()).isEqualTo("CCUP");
            assertThat(revived.accountId()).isEqualTo("00000000001").hasSize(11);
            assertThat(revived.cardNumber()).isEqualTo("0000000000000001").hasSize(16);
            assertThat(revived.expiryMonth()).isEqualTo("01").isNotEqualTo("1");
            assertThat(revived.generalError()).isFalse();
            assertThat(revived.fieldErrors()).isNotNull().isEmpty();
            assertThat(revived.errorMessage()).isNull();
            assertThat(revived.navigationContext()).isNull();
            assertThat(revived.concurrencyToken()).isNull();
        }

        @Test
        @DisplayName("the next route is declarative data the client follows, carried as an opaque "
                + "label with no route vocabulary and no dispatch of any kind")
        void theNextRouteIsDeclarativeData() throws JsonProcessingException {
            CardUpdateResponse response = firstSubmission(null);

            String route = response.nextRoute();

            assertThat(route)
                    .as("the route is plain characters, never an enumerated constant and never a "
                            + "resolved target")
                    .isEqualTo(NEXT_ROUTE);

            JsonNode node = payloadOf(response).get("nextRoute");

            assertThat(node.isTextual()).isTrue();
            assertThat(node.isObject()).isFalse();
            assertThat(node.isArray()).isFalse();
            assertThat(node.asText()).isEqualTo(NEXT_ROUTE);

            assertThat(wireRoundTrip(response).nextRoute()).isEqualTo(NEXT_ROUTE);
        }

        @Test
        @DisplayName("a response can carry no route at all, so routing remains the client's business "
                + "and the contract never substitutes a default target")
        void aResponseCanCarryNoRouteAtAll() throws JsonProcessingException {
            CardUpdateResponse response = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    LEADING_ZERO_ACCOUNT_ID,
                    LEADING_ZERO_CARD_NUMBER,
                    MIXED_CASE_EMBOSSED_NAME,
                    "Y",
                    "01",
                    "2026",
                    "31",
                    null,
                    null,
                    NavigationContext.empty(),
                    SEALED_PROOF_STAND_IN);

            assertThat(response.nextRoute()).isNull();
            assertThat(payloadOf(response).has("nextRoute")).isFalse();
        }

        @Test
        @DisplayName("the sealed proof reaches the wire, survives a round trip byte for byte, and is "
                + "omitted entirely when a shape presents no card to confirm")
        void theSealedProofReachesTheWireAndIsOmittedWhenAbsent() throws JsonProcessingException {
            CardUpdateResponse presenting =
                    firstSubmission(CardUpdateResponse.Messages.FOUND_CARDS_FOR_ACCOUNT);

            assertThat(payloadOf(presenting).get("concurrencyToken").asText())
                    .isEqualTo(SEALED_PROOF_STAND_IN);

            CardUpdateResponse revived = wireRoundTrip(presenting);

            assertThat(revived.concurrencyToken()).isEqualTo(SEALED_PROOF_STAND_IN);
            assertThat(revived).isEqualTo(presenting);

            CardUpdateResponse noCardPresented = new CardUpdateResponse(
                    "CCUP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    CardUpdateResponse.Messages.PROMPT_FOR_SEARCH_KEYS,
                    NEXT_ROUTE,
                    NavigationContext.empty().withFirstEntry(),
                    null);

            assertThat(noCardPresented.concurrencyToken()).isNull();
            assertThat(payloadOf(noCardPresented).has("concurrencyToken")).isFalse();
            assertThat(noCardPresented.informationMessage())
                    .isEqualTo("Please enter Account and Card Number");
        }
    }

    @Nested
    @DisplayName("Value semantics, immutability and the diagnostic rendering")
    class ValueSemanticsContract {

        @Test
        @DisplayName("two responses built from the same values are equal and share a hash code, "
                + "because the contract is a value and not an identity")
        void equalValuesProduceEqualResponses() {
            CardUpdateResponse first = reSubmission(List.of(expiryMonthInvalid()));
            CardUpdateResponse second = reSubmission(List.of(expiryMonthInvalid()));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isEqualTo(first);
            assertThat(first).isNotEqualTo(firstSubmission(null));
            assertThat(first).isNotEqualTo(null);
            assertThat(first).isNotEqualTo("not a response");
        }

        @Test
        @DisplayName("a response differing in a single component is not equal, one component at a "
                + "time, so no component is left out of the value comparison")
        void aSingleDifferingComponentBreaksEquality() {
            CardUpdateResponse baseline = fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    true,
                    List.of(expiryMonthInvalid()),
                    SCREEN_FIELD_EXPIRY_MONTH);

            assertThat(fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CHANGES,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    true,
                    List.of(expiryMonthInvalid()),
                    SCREEN_FIELD_EXPIRY_MONTH)).isNotEqualTo(baseline);

            assertThat(fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.COULD_NOT_LOCK_FOR_UPDATE,
                    true,
                    List.of(expiryMonthInvalid()),
                    SCREEN_FIELD_EXPIRY_MONTH)).isNotEqualTo(baseline);

            assertThat(fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    false,
                    List.of(expiryMonthInvalid()),
                    SCREEN_FIELD_EXPIRY_MONTH)).isNotEqualTo(baseline);

            assertThat(fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    true,
                    List.of(embossedNameMissing()),
                    SCREEN_FIELD_EXPIRY_MONTH)).isNotEqualTo(baseline);

            assertThat(fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    true,
                    List.of(expiryMonthInvalid()),
                    SCREEN_FIELD_CARD_NAME)).isNotEqualTo(baseline);
        }

        @Test
        @DisplayName("two responses differing only in their sealed proof are not equal, so a proof "
                + "cannot be swapped between responses without changing the value")
        void aDifferentSealedProofYieldsADifferentResponse() {
            CardUpdateResponse first = firstSubmission(null);
            CardUpdateResponse second = new CardUpdateResponse(
                    first.transactionName(),
                    first.title01(),
                    first.currentDate(),
                    first.programName(),
                    first.title02(),
                    first.currentTime(),
                    first.accountId(),
                    first.cardNumber(),
                    first.embossedName(),
                    first.activeStatus(),
                    first.expiryMonth(),
                    first.expiryYear(),
                    first.expiryDay(),
                    first.informationMessage(),
                    first.nextRoute(),
                    first.navigationContext(),
                    SEALED_PROOF_STAND_IN + "-other");

            assertThat(second).isNotEqualTo(first);
            assertThat(second.concurrencyToken()).isNotEqualTo(first.concurrencyToken());
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the card values and the sealed proof while "
                + "the payload still carries every one of them in full")
        void theDiagnosticRenderingWithholdsWhileThePayloadDoesNot()
                throws JsonProcessingException {
            CardUpdateResponse response =
                    firstSubmission(CardUpdateResponse.Messages.FOUND_CARDS_FOR_ACCOUNT);

            String rendered = response.toString();

            assertThat(rendered)
                    .doesNotContain(LEADING_ZERO_CARD_NUMBER)
                    .doesNotContain(MIXED_CASE_EMBOSSED_NAME)
                    .doesNotContain(SEALED_PROOF_STAND_IN);
            assertThat(rendered).contains("CardUpdateResponse");
            assertThat(rendered).contains("concurrencyToken=***REDACTED***");
            assertThat(rendered).contains("expiryMonth=***REDACTED***",
                    "expiryYear=***REDACTED***", "expiryDay=***REDACTED***");
            assertThat(rendered)
                    .as("the header items, the status code and the navigation state stay visible, "
                            + "because none of them identifies a cardholder")
                    .contains("transactionName=CCUP", "programName=COCRDUPC", "activeStatus=Y",
                            "nextRoute=" + NEXT_ROUTE, "generalError=false");

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("accountId").asText()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(LEADING_ZERO_CARD_NUMBER);
            assertThat(payload.get("embossedName").asText()).isEqualTo(MIXED_CASE_EMBOSSED_NAME);
            assertThat(payload.get("concurrencyToken").asText()).isEqualTo(SEALED_PROOF_STAND_IN);
            assertThat(payload.toString()).doesNotContain("REDACTED");
        }

        @Test
        @DisplayName("the diagnostic rendering names every component, so a future component cannot be "
                + "added and silently left out of the withholding decision")
        void theDiagnosticRenderingNamesEveryComponent() {
            String rendered = fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    true,
                    List.of(expiryMonthInvalid()),
                    SCREEN_FIELD_EXPIRY_MONTH).toString();

            for (String key : EXPECTED_WIRE_KEYS) {
                assertThat(rendered).as("component %s", key).contains(key + "=");
            }
        }

        @Test
        @DisplayName("every accessor on the fully populated shape answers with the value it was "
                + "given, so no component is write-only or silently dropped")
        void everyAccessorAnswersWithTheValueItWasGiven() {
            List<ErrorResponse.FieldError> errors = List.of(expiryMonthInvalid());
            CardUpdateResponse response = fullyPopulated(
                    CardUpdateResponse.Messages.PROMPT_FOR_CONFIRMATION,
                    CardUpdateResponse.Messages.INFORM_FAILURE,
                    true,
                    errors,
                    SCREEN_FIELD_EXPIRY_MONTH);

            assertThat(response.transactionName()).isEqualTo("CCUP");
            assertThat(response.title01()).isEqualTo("Tracking Card Demo");
            assertThat(response.currentDate()).isEqualTo("08/01/26");
            assertThat(response.programName()).isEqualTo("COCRDUPC");
            assertThat(response.title02()).isEqualTo("Update Card Details");
            assertThat(response.currentTime()).isEqualTo("16:00:00");
            assertThat(response.accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(response.cardNumber()).isEqualTo(LEADING_ZERO_CARD_NUMBER);
            assertThat(response.embossedName()).isEqualTo(MIXED_CASE_EMBOSSED_NAME);
            assertThat(response.activeStatus()).isEqualTo("Y");
            assertThat(response.expiryMonth()).isEqualTo("01");
            assertThat(response.expiryYear()).isEqualTo("2026");
            assertThat(response.expiryDay()).isEqualTo("31");
            assertThat(response.informationMessage())
                    .isEqualTo("Changes validated.Press F5 to save");
            assertThat(response.errorMessage()).isEqualTo("Changes unsuccessful. Please try again");
            assertThat(response.generalError()).isTrue();
            assertThat(response.fieldErrors()).isEqualTo(errors);
            assertThat(response.focusScreenFieldId()).isEqualTo(SCREEN_FIELD_EXPIRY_MONTH);
            assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(response.navigationContext()).isEqualTo(populatedNavigationContext());
            assertThat(response.concurrencyToken()).isEqualTo(SEALED_PROOF_STAND_IN);
            assertThat(response.hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("the type offers no way to change a built instance, so a response handed to two "
                + "callers cannot be altered by either of them")
        void theTypeOffersNoWayToChangeABuiltInstance() {
            CardUpdateResponse shared = reSubmission(List.of(expiryMonthInvalid()));

            String observedName = shared.embossedName();
            String observedNumber = shared.cardNumber();
            List<ErrorResponse.FieldError> observedErrors = shared.fieldErrors();

            assertThatThrownBy(() -> observedErrors.add(embossedNameMissing()))
                    .isInstanceOf(UnsupportedOperationException.class);

            assertThat(shared.embossedName()).isSameAs(observedName);
            assertThat(shared.cardNumber()).isSameAs(observedNumber);
            assertThat(shared.fieldErrors()).isEqualTo(observedErrors).hasSize(1);
            assertThat(shared).isEqualTo(reSubmission(List.of(expiryMonthInvalid())));
        }
    }
}
