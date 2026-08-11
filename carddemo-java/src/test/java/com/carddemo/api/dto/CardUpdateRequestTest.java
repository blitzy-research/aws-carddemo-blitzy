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

import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.support.SensitiveValues;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link CardUpdateRequest}, the request contract of legacy CICS transaction
 * {@code CCUP}, whose screen is {@code app/cpy-bms/COCRDUP.CPY} over mapset
 * {@code app/bms/COCRDUP.bms} and whose program is {@code app/cbl/COCRDUPC.cbl} - 1,560 lines and 45
 * procedure paragraphs. The persisted layout consulted for the field kinds is
 * {@code app/cpy/CVACT02Y.cpy}, the 150-byte card record.
 *
 * <p>A pure unit test. No application context is refreshed, no container is started and no database
 * is reached: every instance is built with the canonical constructor, every constraint outcome comes
 * from a locally built {@link Validator}, and every wire assertion comes from a locally built
 * {@link ObjectMapper} configured to match the four serialization settings the module declares in
 * {@code src/main/resources/application.yml}.
 *
 * <p><strong>Nothing here inspects the type through the platform's run-time introspection
 * API.</strong> That is a deliberate constraint rather than an omission, and it is what shapes the
 * whole file. The component inventory is pinned by serializing a fully populated instance and reading
 * the keys the payload actually carries, which is the inventory a client observes rather than the one
 * an annotation happens to declare.
 * Component order is pinned by the canonical constructor, positionally, so the assertion fails at
 * compile time if the order ever changes. Constraint presence and absence are pinned
 * <em>behaviourally</em>: a value one character past a component's map width must produce exactly one
 * violation on that component, and a wildly over-width value on the one unconstrained component must
 * produce none at all. Immutability is pinned by construction, because a record offers no other way to
 * produce a variant. Every one of those proofs tests the contract a caller meets; reading annotations
 * would only have tested that the source says what the source says.
 *
 * <h2>What this request must not do, and why each is a parity requirement</h2>
 *
 * <p><strong>It must not fold the embossed name.</strong> {@code app/cbl/COCRDUPC.cbl} folds that
 * field in place against a strict 26-character upper-case table at line 1356, immediately before it
 * captures the fetched image at line 1360, and again at line 1499, immediately before the six-way
 * comparison at lines 1503 to 1508. Because both sides of that comparison are folded by the time it
 * runs, an edit differing from the fetched value only in letter case is <em>not</em> detected as a
 * change, and the operator is told nothing changed rather than that the update succeeded. That outcome
 * survives only if the operator's exact bytes reach the service, so this contract carries the name
 * untouched and the service performs the fold. The platform's own case-conversion method on
 * {@code String} is unusable for that fold module-wide, being locale-sensitive and Unicode-aware, and
 * it appears nowhere in this file either: the two case variants below are hand-written literals, so
 * the assertion that no folding occurred is itself independent of any folding implementation.
 *
 * <p><strong>It must not reject a name that holds spaces.</strong> The program's letters-or-spaces
 * check is the blank-and-trim idiom at {@code app/cbl/COCRDUPC.cbl} line 824: every character that
 * appears in a table of the upper- and lower-case ASCII letters is blanked, and what remains is
 * trimmed and tested for emptiness. A space is not in the table, but a space is also exactly what
 * blanking leaves behind, so a value made only of letters and spaces leaves nothing and passes. Two
 * given names separated by a space are therefore valid input that the legacy system accepts, and a
 * predicate asserting that every character is a letter would reject data the estate already holds. The
 * faithful predicate is "every character is a letter or a space", it lives in the utility layer, and
 * this contract applies neither it nor anything like it.
 *
 * <p><strong>It must not pre-empt any of the delegated rules.</strong>
 * {@code app/cbl/COCRDUPC.cbl} runs an ordered, first-error-wins cascade in which each stage is gated
 * on the summary-message slot still being empty, so a submission with four bad fields produces exactly
 * one summary message - the one belonging to the earliest failing stage in source order. Bean
 * Validation evaluates constraints in an unspecified order and reports every violation at once, so
 * hoisting any of those rules into an annotation here would change which single message a bad
 * submission produces. The tests below therefore assert that a status character outside the declared
 * pair, an expiry month outside the inclusive one-through-twelve range whose message names its bounds
 * unzeroed, an expiry year outside the inclusive 1950-through-2099 range whose message names no range
 * at all, a punctuated name, an absent value and a blank value each produce <strong>zero</strong>
 * violations at this boundary.
 *
 * <p><strong>It must not carry what the screen produced rather than received.</strong> Of the
 * seventeen input families the symbolic map declares, seven are carried. Six are screen metadata the
 * server produces, two are server-produced message lines - the information line of width 40 at
 * {@code app/cpy-bms/COCRDUP.CPY} line 102 and the error line of width 80 at line 108, both widths
 * specific to this map and deliberately not harmonised with the widths the same two families take
 * elsewhere - and the last two are the function-key legends of widths 21 and 18 at lines 114 and 120,
 * whose fixed captions are declared in the mapset at lines 162 and 167. All ten are asserted absent
 * from the payload.
 *
 * <p><strong>The hidden expiry day is the one family this screen has and the card-detail screen does
 * not.</strong> It is declared at {@code app/cpy-bms/COCRDUP.CPY} line 96 with width 2 and the mapset
 * makes it dark, field-set and protected at line 142 - invisible to the operator, impossible to type
 * into, returned by the terminal regardless. The program reads the stored day out of the ninth position
 * of the persisted ten-character expiry value at line 1365 and compares that same position again during
 * change detection at line 1507, so the value must survive the round trip intact. It is therefore
 * carried, serialized outbound so the echo survives, ignored inbound because a request body is not a
 * terminal buffer, and left entirely unconstrained. The update surface is genuinely wider than the
 * detail surface here, and the tests below pin that asymmetry from both directions.
 *
 * <p>This mapset carries a later stamp of its own, which is why the pair is cited here as documentation and
 * is never embedded as a constant. No COBOL, screen-map or job-control source line is reproduced anywhere
 * in this file.
 */
@DisplayName("CardUpdateRequest :: card-update request contract of legacy transaction CCUP")
class CardUpdateRequestTest {

    /**
     * The ten keys the payload carries, in the order the canonical constructor declares them: the
     * seven map fields, the attention key, the echoed navigation state and the sealed concurrency
     * proof. The proof is not a map field - it is the echoed counterpart of the program work area the
     * legacy transaction carries across its pseudo-conversational turns.
     */
    private static final List<String> CONTRACT_KEYS = List.of(
            "accountId", "cardNumber", "embossedName", "activeStatus", "expiryMonth", "expiryYear",
            "expiryDay", "keyAction", "navigationContext", "concurrencyToken");

    /** Account id at its full map width of 11, with the leading zeros that must never be lost. */
    private static final String ACCOUNT_ID = "00000000011";

    private static final String OVER_WIDE_ACCOUNT_ID = "000000000011";

    /** Fictional card number at its full map width of 16, again with contractual leading zeros. */
    private static final String CARD_NUMBER = "0000000000000011";

    private static final String OVER_WIDE_CARD_NUMBER = "00000000000000011";

    /**
     * Embossed name at its full map width of 50, deliberately mixed case and deliberately holding
     * embedded spaces, an apostrophe and a hyphen. Nothing in this file derives its folded form from
     * it; the folded form below is a separate hand-written literal.
     */
    private static final String NAME_MIXED_CASE =
            "Mary Ann Elizabeth o'Hara-Smith de la Cruz Juniors";

    /**
     * The same 50 characters as {@link #NAME_MIXED_CASE} would read after the legacy 26-character
     * upper-case fold: the letters change and the apostrophe, the hyphen and the spaces do not.
     * Written out by hand so that the no-folding assertions do not depend on any folding code.
     */
    private static final String NAME_UPPER_FOLDED =
            "MARY ANN ELIZABETH O'HARA-SMITH DE LA CRUZ JUNIORS";

    private static final String OVER_WIDE_NAME =
            "Mary Ann Elizabeth o'Hara-Smith de la Cruz JuniorII";

    /**
     * Fifty characters carrying two leading spaces, several embedded spaces and sixteen trailing
     * spaces. The legacy screen delivers every field space-filled to its declared width, so those
     * spaces are contract and not incidental whitespace.
     */
    private static final String NAME_WITH_SURROUNDING_SPACES =
            "  MARY ANN VAN DER BERG DE LA CRUZ                ";

    /** Two given names separated by a space - valid input that the legacy system accepts. */
    private static final String TWO_WORD_NAME = "MARY ANN";

    private static final String STATUS_ACTIVE = "Y";

    private static final String OVER_WIDE_STATUS = "YN";

    /** Expiry month at its map width of 2, zero-padded, which must never be reduced to one digit. */
    private static final String EXPIRY_MONTH = "01";

    private static final String OVER_WIDE_MONTH = "013";

    private static final String EXPIRY_YEAR = "2026";

    private static final String OVER_WIDE_YEAR = "20261";

    /** Expiry day at its map width of 2 - hidden, protected and carry-through only. */
    private static final String EXPIRY_DAY = "28";

    /**
     * Forty characters in the one component the contract leaves entirely unconstrained. Twenty times
     * its map width, so a width bound of any size would report it and the absence of a report is the
     * proof that no constraint of any kind is attached.
     */
    private static final String FORTY_CHARACTER_DAY =
            "9999999999999999999999999999999999999999";

    /** The fixed stand-in the diagnostic rendering emits in place of every component. */
    private static final String REDACTED = "***REDACTED***";

    /** A stand-in for the sealed proof the response mints and a confirming request echoes back. */
    private static final String CONCURRENCY_TOKEN = "CCUP-sealed-proof-stand-in";

    /**
     * Builds echoed navigation state whose every component sits inside its own declared width, so it
     * contributes no violation of its own to any assertion below.
     *
     * @return a valid navigation context for this screen
     */
    private static NavigationContext navigation() {
        return new NavigationContext("CCUP", "COCRDUPC", "CCUP", "COCRDUPC", "OPERATR1", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");
    }

    private static CardUpdateRequest populated() {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE, STATUS_ACTIVE,
                EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(),
                CONCURRENCY_TOKEN);
    }

    private static CardUpdateRequest withAccountId(String accountId) {
        return new CardUpdateRequest(accountId, CARD_NUMBER, NAME_MIXED_CASE, STATUS_ACTIVE,
                EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(),
                CONCURRENCY_TOKEN);
    }

    private static CardUpdateRequest withCardNumber(String cardNumber) {
        return new CardUpdateRequest(ACCOUNT_ID, cardNumber, NAME_MIXED_CASE, STATUS_ACTIVE,
                EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(),
                CONCURRENCY_TOKEN);
    }

    private static CardUpdateRequest withName(String name) {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, name, STATUS_ACTIVE, EXPIRY_MONTH,
                EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(), CONCURRENCY_TOKEN);
    }

    private static CardUpdateRequest withStatus(String status) {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE, status, EXPIRY_MONTH,
                EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(), CONCURRENCY_TOKEN);
    }

    /**
     * Builds a submission that differs from {@link #populated()} only in its three expiry parts,
     * which the map declares in this order: month, then year, then the hidden day.
     *
     * @param month the month part
     * @param year the year part
     * @param day the hidden day part
     * @return a request carrying those three parts
     */
    private static CardUpdateRequest withExpiry(String month, String year, String day) {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE, STATUS_ACTIVE, month,
                year, day, KeyAction.PFK05, navigation(), CONCURRENCY_TOKEN);
    }

    /**
     * Builds a mapper configured with the four serialization settings the module declares: absent
     * properties are omitted rather than sent as null, dates are rendered in their textual form rather
     * than as epoch numbers, an unknown incoming property is tolerated, and a decimal is written
     * plainly. Built locally on every call rather than shared, so no test can observe state another
     * test left behind.
     *
     * @return a mapper equivalent to the one the module configures
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a request and reads the result back as a tree, which is how every wire assertion
     * below observes the payload a client would receive.
     *
     * @param request the request to serialize
     * @return the payload as a tree
     * @throws JsonProcessingException if the request cannot be written or the result cannot be read
     */
    private static JsonNode payloadOf(CardUpdateRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Collects the keys a payload carries, in the order the payload carries them.
     *
     * @param payload a serialized request
     * @return the payload's keys
     */
    private static List<String> keysOf(JsonNode payload) {
        List<String> keys = new ArrayList<>();
        payload.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /**
     * Validates a request with a locally built validator, which is what the boundary applies. Built
     * through the specification's own bootstrap rather than obtained from a container, so this test
     * needs no application context.
     *
     * @param request the request to validate
     * @param groups the validation groups to apply, or none for the default group
     * @return every violation the boundary would report
     */
    private static Set<ConstraintViolation<CardUpdateRequest>> violationsOf(CardUpdateRequest request,
            Class<?>... groups) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request, groups);
        }
    }

    /**
     * Returns the single path a validation run reported, failing with a readable diagnostic when the
     * run reported anything other than exactly one violation.
     *
     * @param violations the reported violations
     * @return the property path of the only violation
     */
    private static String onlyPathIn(Set<ConstraintViolation<CardUpdateRequest>> violations) {
        assertThat(violations)
                .as("exactly one violation was expected, and the paths reported were %s",
                        violations.stream().map(v -> v.getPropertyPath().toString()).toList())
                .hasSize(1);
        return violations.iterator().next().getPropertyPath().toString();
    }

    @Nested
    @DisplayName("the contract a client observes")
    class ObservedContract {

        @Test
        @DisplayName("carries exactly the ten components, in the order the map and the state declare")
        void carriesExactlyTenComponents() throws JsonProcessingException {
            assertThat(keysOf(payloadOf(populated())))
                    .as("seven map fields, the attention key, the echoed state and the sealed proof")
                    .containsExactlyElementsOf(CONTRACT_KEYS)
                    .hasSize(10);
        }

        @Test
        @DisplayName("carries the hidden expiry day, which the card-detail surface deliberately lacks")
        void carriesTheHiddenExpiryDay() throws JsonProcessingException {
            // The update surface is genuinely wider than the detail surface. This family is declared
            // at COCRDUP.CPY line 96 with width 2 and has no counterpart anywhere on COCRDSL.CPY, so
            // CardDetailResponse deliberately carries no expiry-day component at all. The value must
            // survive the round trip because the program compares that position again at COCRDUPC
            // line 1507 after capturing it at line 1365.
            assertThat(populated().expiryDay()).isEqualTo(EXPIRY_DAY);
            assertThat(keysOf(payloadOf(populated()))).contains("expiryDay");
            assertThat(payloadOf(populated()).get("expiryDay").asText()).isEqualTo(EXPIRY_DAY);
        }

        @Test
        @DisplayName("carries neither function-key legend, because both are terminal furniture")
        void carriesNeitherFunctionKeyLegend() throws JsonProcessingException {
            // Widths 21 and 18 at COCRDUP.CPY lines 114 and 120; their fixed captions are declared in
            // the mapset at lines 162 and 167 and are produced by the screen, never submitted to it.
            assertThat(keysOf(payloadOf(populated())))
                    .doesNotContain("fkeys", "fkeysc", "functionKeys", "functionKeyLegend",
                            "functionKeyContinuation", "keyLegend");
        }

        @Test
        @DisplayName("carries no information line and no error line, which the response owns")
        void carriesNeitherMessageLine() throws JsonProcessingException {
            // Width 40 at COCRDUP.CPY line 102 and width 80 at line 108. Both widths are specific to
            // this map and to the card-detail map and are deliberately not harmonised with the widths
            // the same two families take on the other screens; that asymmetry belongs to the response
            // contract, and neither family belongs on a request at all.
            assertThat(keysOf(payloadOf(populated())))
                    .doesNotContain("infoMessage", "informationMessage", "infomsg", "errorMessage",
                            "errmsg", "message", "summaryMessage", "returnMessage");
        }

        @Test
        @DisplayName("carries none of the six screen-metadata families the server produces")
        void carriesNoScreenMetadata() throws JsonProcessingException {
            // The transaction name at line 24, the two title lines at lines 30 and 48, the current
            // date at line 36, the program name at line 42 and the current time at line 54.
            assertThat(keysOf(payloadOf(populated())))
                    .doesNotContain("transactionName", "trnName", "title", "title01", "title02",
                            "currentDate", "curDate", "currentTime", "curTime", "programName",
                            "pgmName");
        }

        @Test
        @DisplayName("carries no readable concurrency value and no screen artefact of any kind")
        void carriesTheSealedProofAndNoReadableSubstituteForIt() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys)
                    .as("the sealed proof replaces the protected attribute byte the terminal had")
                    .contains("concurrencyToken");
            assertThat(keys)
                    .as("every one of these is state a caller could assert for itself")
                    .doesNotContain("version", "rowVersion", "etag", "eTag", "timestamp",
                            "fetchedImage", "beforeImage", "screenWorkArea");
            assertThat(keys)
                    .as("no field length, flag, attribute, colour, highlight or cursor value")
                    .doesNotContain("fieldLength", "fieldFlag", "attribute", "colour", "color",
                            "highlight", "cursor", "tioa");
        }

        @Test
        @DisplayName("carries no card verification value, because the legacy design carries none")
        void carriesNoCardVerificationValue() throws JsonProcessingException {
            // The 150-byte record does hold a verification code at CVACT02Y line 7, but this screen
            // never presents it and never accepts it, so it is not part of this contract. The absence
            // of field-level protection on that code in the legacy design is a recorded gap rather
            // than something this request closes.
            assertThat(keysOf(payloadOf(populated())))
                    .doesNotContain("cvv", "cardCvv", "cvvCode", "securityCode",
                            "verificationCode");
        }

        @Test
        @DisplayName("takes the expiry month before the expiry year, and the hidden day last")
        void canonicalConstructorTakesMonthThenYearThenDay() {
            // Proven positionally rather than by inspecting declarations: the three distinct values
            // below are placed in constructor positions five, six and seven and read back through the
            // three accessors. The map declares the parts in this order at lines 84, 90 and 96, which
            // inverts both the order the persisted record holds them in and the order the
            // account-update screen uses, so the order is pinned here rather than assumed.
            CardUpdateRequest request = withExpiry("07", "2031", "19");

            assertThat(request.expiryMonth()).isEqualTo("07");
            assertThat(request.expiryYear()).isEqualTo("2031");
            assertThat(request.expiryDay()).isEqualTo("19");
        }

        @Test
        @DisplayName("keeps the three expiry parts split, and offers no combined value")
        void keepsTheThreeExpiryPartsSplit() throws JsonProcessingException {
            CardUpdateRequest request = populated();

            assertThat(keysOf(payloadOf(request)))
                    .as("three separate keys, and nothing that merges them")
                    .contains("expiryMonth", "expiryYear", "expiryDay")
                    .doesNotContain("expiryDate", "expiry", "expiration", "expirationDate");
            assertThat(request.expiryMonth()).isNotEqualTo(request.expiryYear());
            assertThat(List.of(request.expiryMonth(), request.expiryYear(), request.expiryDay()))
                    .as("no accessor returns a value assembled out of the other two")
                    .doesNotContain(EXPIRY_YEAR + EXPIRY_MONTH + EXPIRY_DAY,
                            EXPIRY_MONTH + EXPIRY_YEAR + EXPIRY_DAY);
        }

        @Test
        @DisplayName("renders every carried value as text, never as a number")
        void rendersEveryCarriedValueAsText() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            for (String key : List.of("accountId", "cardNumber", "embossedName", "activeStatus",
                    "expiryMonth", "expiryYear", "expiryDay", "concurrencyToken")) {
                assertThat(payload.get(key).isTextual())
                        .as("%s must cross the boundary as text, so its width and its leading zeros"
                                + " survive", key)
                        .isTrue();
                assertThat(payload.get(key).isNumber()).as("%s must not be a number", key).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("the two fields a terminal protected")
    class ProtectedTerminalFields {

        @Test
        @DisplayName("discards a hidden expiry day that an inbound body supplies")
        void discardsAnInboundExpiryDay() throws JsonProcessingException {
            // The attribute rewrite that would have unprotected this field is commented out in all
            // four of the program's screen states, so the mapset's dark-and-protected declaration at
            // COCRDUP.bms line 142 governs throughout and no state exists in which an operator could
            // have supplied it. Over HTTP that guarantee has to be re-established at the boundary.
            String body = """
                    {"accountId":"00000000011","cardNumber":"0000000000000011",\
                    "embossedName":"MARY ANN","activeStatus":"Y","expiryMonth":"01",\
                    "expiryYear":"2026","expiryDay":"99"}""";

            CardUpdateRequest bound = moduleEquivalentMapper().readValue(body,
                    CardUpdateRequest.class);

            assertThat(bound.expiryDay())
                    .as("the wire cannot reach the stored expiry date through this field")
                    .isNull();
            assertThat(bound.expiryMonth())
                    .as("positive control: a sibling expiry part still binds")
                    .isEqualTo(EXPIRY_MONTH);
        }

        @Test
        @DisplayName("still serialises the hidden expiry day, so the echo survives the round trip")
        void stillSerialisesTheHiddenExpiryDay() throws JsonProcessingException {
            assertThat(payloadOf(populated()).get("expiryDay").asText())
                    .as("the comparison at COCRDUPC line 1507 needs the fetched day back intact")
                    .isEqualTo(EXPIRY_DAY);
        }

        @Test
        @DisplayName("attaches no constraint of any kind to the hidden expiry day, not even a width")
        void attachesNoConstraintToTheHiddenExpiryDay() {
            // Forty characters is twenty times the map width of 2. A width bound of any size would
            // report it, so a clean run is the proof that the component carries no constraint at all.
            assertThat(violationsOf(withExpiry(EXPIRY_MONTH, EXPIRY_YEAR, FORTY_CHARACTER_DAY)))
                    .as("the day is machinery rather than user input, so it is never validated")
                    .isEmpty();
            assertThat(violationsOf(withExpiry(EXPIRY_MONTH, EXPIRY_YEAR, null))).isEmpty();
            assertThat(violationsOf(withExpiry(EXPIRY_MONTH, EXPIRY_YEAR, ""))).isEmpty();
            assertThat(onlyPathIn(violationsOf(withExpiry(OVER_WIDE_MONTH, EXPIRY_YEAR, EXPIRY_DAY))))
                    .as("positive control: the sibling month part IS bounded at its map width")
                    .isEqualTo("expiryMonth");
        }

        @Test
        @DisplayName("keeps the account id bindable, because the screen unprotects it before the fetch")
        void keepsTheAccountIdBindable() throws JsonProcessingException {
            // COCRDUP.bms line 84 declares the first-send attribute protected, but the program
            // rewrites it to unprotected at COCRDUPC line 1174 while details are not yet fetched and
            // back to protected at line 1183 once they are. The operator types it there as the search
            // filter, so making it non-bindable outright would be a behavioural change rather than a
            // hardening.
            CardUpdateRequest bound = moduleEquivalentMapper()
                    .readValue("{\"accountId\":\"00000000011\"}", CardUpdateRequest.class);

            assertThat(bound.accountId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("requires the account id to be absent on the one submission that writes")
        void requiresTheAccountIdToBeAbsentOnTheWritingSubmission() {
            assertThat(onlyPathIn(violationsOf(populated(), CardUpdateRequest.ConfirmSave.class)))
                    .as("the rewrite at COCRDUPC lines 1461 to 1474 takes the owning account from the"
                            + " freshly loaded record, and the confirming state has the field"
                            + " protected")
                    .isEqualTo("accountId");
        }

        @Test
        @DisplayName("accepts a confirming submission that omits the account id")
        void acceptsAConfirmingSubmissionWithoutAnAccountId() {
            CardUpdateRequest confirming = new CardUpdateRequest(null, CARD_NUMBER, NAME_MIXED_CASE,
                    STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05,
                    navigation(), CONCURRENCY_TOKEN);

            assertThat(violationsOf(confirming, CardUpdateRequest.ConfirmSave.class)).isEmpty();
        }

        @Test
        @DisplayName("leaves the absence rule inert on the ordinary searching submission")
        void leavesTheAbsenceRuleInertOnTheSearchingSubmission() {
            assertThat(violationsOf(populated()))
                    .as("the turn on which the operator types the filter must behave exactly as it"
                            + " did before the group existed")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the embossed name is never folded here")
    class EmbossedNameIsNeverFolded {

        @Test
        @DisplayName("returns a mixed-case name at full width byte for byte through the accessor")
        void returnsAMixedCaseNameByteForByte() {
            CardUpdateRequest request = withName(NAME_MIXED_CASE);

            assertThat(NAME_MIXED_CASE)
                    .as("the fixture must sit at the map width of 50 for this to mean anything")
                    .hasSize(50);
            assertThat(request.embossedName())
                    .isEqualTo(NAME_MIXED_CASE)
                    .isNotEqualTo(NAME_UPPER_FOLDED)
                    .hasSize(50);
        }

        @Test
        @DisplayName("carries a mixed-case name across the wire without folding it either")
        void carriesAMixedCaseNameAcrossTheWireUnfolded() throws JsonProcessingException {
            JsonNode payload = payloadOf(withName(NAME_MIXED_CASE));

            assertThat(payload.get("embossedName").asText())
                    .as("the service folds against a 26-character table; the boundary must not")
                    .isEqualTo(NAME_MIXED_CASE)
                    .isNotEqualTo(NAME_UPPER_FOLDED);
        }

        @Test
        @DisplayName("binds a mixed-case name from an inbound body without folding it")
        void bindsAMixedCaseNameWithoutFoldingIt() throws JsonProcessingException {
            String body = "{\"embossedName\":\"" + NAME_MIXED_CASE + "\"}";

            CardUpdateRequest bound = moduleEquivalentMapper().readValue(body,
                    CardUpdateRequest.class);

            assertThat(bound.embossedName())
                    .isEqualTo(NAME_MIXED_CASE)
                    .isNotEqualTo(NAME_UPPER_FOLDED);
        }

        @Test
        @DisplayName("keeps a case-only edit visible, which is what the service needs to see")
        void keepsACaseOnlyEditVisible() {
            CardUpdateRequest asTyped = withName(NAME_MIXED_CASE);
            CardUpdateRequest asFolded = withName(NAME_UPPER_FOLDED);

            // The program folds both sides of its comparison, so a case-only edit reads to the
            // operator as no change at all rather than as a successful update. That outcome is the
            // contract, and it can only be reproduced if the operator's exact bytes arrive here
            // distinguishable - which is what these two instances being unequal demonstrates.
            assertThat(asTyped).isNotEqualTo(asFolded);
            assertThat(asTyped.embossedName()).hasSameSizeAs(asFolded.embossedName());
        }

        @Test
        @DisplayName("accepts a name at full width whether folded or not, with no violation either way")
        void acceptsEitherCaseWithoutViolation() {
            assertThat(violationsOf(withName(NAME_MIXED_CASE))).isEmpty();
            assertThat(violationsOf(withName(NAME_UPPER_FOLDED))).isEmpty();
        }
    }

    @Nested
    @DisplayName("the letters-or-spaces rule admits spaces")
    class LettersOrSpacesRuleAdmitsSpaces {

        @Test
        @DisplayName("accepts a full-width name holding leading, embedded and trailing spaces")
        void acceptsSurroundingAndEmbeddedSpaces() {
            CardUpdateRequest request = withName(NAME_WITH_SURROUNDING_SPACES);

            assertThat(violationsOf(request))
                    .as("the blank-and-trim idiom at COCRDUPC line 824 leaves nothing behind for a"
                            + " value of letters and spaces, so the legacy system accepts it")
                    .isEmpty();
            assertThat(request.embossedName())
                    .isEqualTo(NAME_WITH_SURROUNDING_SPACES)
                    .hasSize(50);
        }

        @Test
        @DisplayName("trims neither the leading nor the trailing spaces of such a name")
        void trimsNeitherEndOfSuchAName() throws JsonProcessingException {
            CardUpdateRequest request = withName(NAME_WITH_SURROUNDING_SPACES);

            assertThat(request.embossedName().charAt(0))
                    .as("the legacy screen space-fills every field, so the spaces are contract")
                    .isEqualTo(' ');
            assertThat(request.embossedName().charAt(49)).isEqualTo(' ');
            assertThat(payloadOf(request).get("embossedName").asText())
                    .isEqualTo(NAME_WITH_SURROUNDING_SPACES);
        }

        @Test
        @DisplayName("accepts two given names separated by a space")
        void acceptsTwoGivenNamesSeparatedByASpace() {
            // The service reports a 47-character diagnostic when the value holds anything other than
            // letters and spaces, and this value holds nothing else, so it must reach the service
            // unchallenged by the boundary.
            CardUpdateRequest request = withName(TWO_WORD_NAME);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.embossedName()).isEqualTo(TWO_WORD_NAME);
        }

        @Test
        @DisplayName("accepts a punctuated name too, which the service is the one to reject")
        void acceptsAPunctuatedNameTheServiceRejects() {
            CardUpdateRequest request = withName("O'BRIEN-SMITH 3RD!");

            assertThat(violationsOf(request))
                    .as("hoisting the letters-or-spaces rule here would report it out of cascade"
                            + " order and alongside other violations")
                    .isEmpty();
            assertThat(request.embossedName()).isEqualTo("O'BRIEN-SMITH 3RD!");
        }

        @Test
        @DisplayName("accepts an absent and a blank name, both of which are real screen states")
        void acceptsAnAbsentAndABlankName() {
            assertThat(violationsOf(withName(null))).isEmpty();
            assertThat(violationsOf(withName(""))).isEmpty();
            assertThat(withName("").embossedName()).isEmpty();
        }
    }

    @Nested
    @DisplayName("every business rule stays delegated to the service")
    class DelegatedRulesStayDelegated {

        @Test
        @DisplayName("accepts every status character, including ones outside the declared pair")
        void acceptsEveryStatusCharacter() {
            // The service reports a 33-character diagnostic naming the two permitted codes. The two
            // further one-character states the account-update program declares beside that pair are
            // states of the validation flag that drives error display, not card statuses, so they too
            // are simply raw values as far as this contract is concerned.
            for (String status : List.of(STATUS_ACTIVE, "N", "X", "0", "B", "y", "")) {
                assertThat(violationsOf(withStatus(status)))
                        .as("status %s must reach the service unchallenged", status)
                        .isEmpty();
                assertThat(withStatus(status).activeStatus())
                        .as("status %s must arrive byte for byte", status)
                        .isEqualTo(status);
            }
            assertThat(violationsOf(withStatus(null))).isEmpty();
            assertThat(withStatus(null).activeStatus()).isNull();
        }

        @Test
        @DisplayName("never folds the case of a status character")
        void neverFoldsTheCaseOfAStatusCharacter() throws JsonProcessingException {
            CardUpdateRequest lowerCase = withStatus("y");

            assertThat(lowerCase.activeStatus()).isEqualTo("y").isNotEqualTo(STATUS_ACTIVE);
            assertThat(payloadOf(lowerCase).get("activeStatus").asText()).isEqualTo("y");
        }

        @Test
        @DisplayName("accepts every expiry month token, in range or out of it")
        void acceptsEveryExpiryMonthToken() {
            // The diagnostic names the inclusive bounds unzeroed - one and twelve rather than zero-one
            // and twelve - and that wording is itself part of the contract the response reproduces.
            for (String month : List.of(EXPIRY_MONTH, "12", "1", "13", "00", "")) {
                assertThat(violationsOf(withExpiry(month, EXPIRY_YEAR, EXPIRY_DAY)))
                        .as("month %s must reach the service unchallenged", month)
                        .isEmpty();
                assertThat(withExpiry(month, EXPIRY_YEAR, EXPIRY_DAY).expiryMonth())
                        .as("month %s must arrive byte for byte", month)
                        .isEqualTo(month);
            }
            assertThat(violationsOf(withExpiry(null, EXPIRY_YEAR, EXPIRY_DAY))).isEmpty();
        }

        @Test
        @DisplayName("never reduces a zero-padded month to its digit and never makes it a number")
        void neverReducesAZeroPaddedMonth() throws JsonProcessingException {
            CardUpdateRequest request = withExpiry(EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY);

            assertThat(request.expiryMonth()).isEqualTo(EXPIRY_MONTH).isNotEqualTo("1").hasSize(2);
            assertThat(payloadOf(request).get("expiryMonth").isTextual()).isTrue();
            assertThat(payloadOf(request).get("expiryMonth").isNumber()).isFalse();
            assertThat(payloadOf(request).get("expiryMonth").asText()).isEqualTo(EXPIRY_MONTH);
        }

        @Test
        @DisplayName("accepts every expiry year token, in range or out of it")
        void acceptsEveryExpiryYearToken() {
            // The rule is an inclusive 1950-through-2099 range whose diagnostic deliberately names no
            // range at all, and it belongs entirely to the ordered cascade.
            for (String year : List.of("1950", "2099", EXPIRY_YEAR, "1066", "0000", "")) {
                assertThat(violationsOf(withExpiry(EXPIRY_MONTH, year, EXPIRY_DAY)))
                        .as("year %s must reach the service unchallenged", year)
                        .isEmpty();
                assertThat(withExpiry(EXPIRY_MONTH, year, EXPIRY_DAY).expiryYear())
                        .as("year %s must arrive byte for byte", year)
                        .isEqualTo(year);
            }
            assertThat(violationsOf(withExpiry(EXPIRY_MONTH, null, EXPIRY_DAY))).isEmpty();
        }

        @Test
        @DisplayName("accepts identifiers that hold no digits at all")
        void acceptsIdentifiersThatHoldNoDigits() {
            // The service reports one diagnostic when an identifier is absent and a different one when
            // it is not a number of the right length, and it reports whichever the cascade reaches
            // first. A pattern or digit constraint here would report both at once.
            assertThat(violationsOf(withAccountId("ABCDEFGHIJK"))).isEmpty();
            assertThat(violationsOf(withCardNumber("ABCDEFGHIJKLMNOP"))).isEmpty();
            assertThat(withAccountId("ABCDEFGHIJK").accountId()).isEqualTo("ABCDEFGHIJK");
            assertThat(SensitiveValues.fingerprint(withCardNumber("ABCDEFGHIJKLMNOP").cardNumber()))
                    .isEqualTo(SensitiveValues.fingerprint("ABCDEFGHIJKLMNOP"));
        }

        @Test
        @DisplayName("accepts a submission whose every value would fail the cascade")
        void acceptsASubmissionThatWouldFailEveryStage() {
            CardUpdateRequest hostile = new CardUpdateRequest("ABCDEFGHIJK", "ABCDEFGHIJKLMNOP",
                    "!!! 123 @@@", "?", "ZZ", "20XY", "??", KeyAction.ENTER, navigation(),
                    CONCURRENCY_TOKEN);

            assertThat(violationsOf(hostile))
                    .as("one summary message belongs to the earliest failing stage, and only the"
                            + " service knows which stage that is")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("fixed-width values cross the boundary untouched")
    class FixedWidthValuesCrossUntouched {

        @Test
        @DisplayName("returns each of the seven map values at its exact declared width")
        void returnsEachMapValueAtItsExactWidth() {
            CardUpdateRequest request = populated();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID).hasSize(11);
            assertThat(SensitiveValues.fingerprint(request.cardNumber())).isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
            assertThat(request.cardNumber().length()).isEqualTo(16);
            assertThat(request.embossedName()).isEqualTo(NAME_MIXED_CASE).hasSize(50);
            assertThat(request.activeStatus()).isEqualTo(STATUS_ACTIVE).hasSize(1);
            assertThat(request.expiryMonth()).isEqualTo(EXPIRY_MONTH).hasSize(2);
            assertThat(request.expiryYear()).isEqualTo(EXPIRY_YEAR).hasSize(4);
            assertThat(request.expiryDay()).isEqualTo(EXPIRY_DAY).hasSize(2);
        }

        @Test
        @DisplayName("carries each of the seven map values across the wire at the same width")
        void carriesEachMapValueAcrossTheWireAtTheSameWidth() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID).hasSize(11);
            assertThat(SensitiveValues.fingerprint(payload.get("cardNumber").asText())).isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
            assertThat(payload.get("cardNumber").asText().length()).isEqualTo(16);
            assertThat(payload.get("embossedName").asText()).isEqualTo(NAME_MIXED_CASE).hasSize(50);
            assertThat(payload.get("activeStatus").asText()).isEqualTo(STATUS_ACTIVE).hasSize(1);
            assertThat(payload.get("expiryMonth").asText()).isEqualTo(EXPIRY_MONTH).hasSize(2);
            assertThat(payload.get("expiryYear").asText()).isEqualTo(EXPIRY_YEAR).hasSize(4);
            assertThat(payload.get("expiryDay").asText()).isEqualTo(EXPIRY_DAY).hasSize(2);
        }

        @Test
        @DisplayName("never pads a short value up to its declared width")
        void neverPadsAShortValueUp() throws JsonProcessingException {
            CardUpdateRequest shortValues = new CardUpdateRequest("1", "2", "M", STATUS_ACTIVE, "1",
                    "9", "3", KeyAction.PFK05, navigation(), CONCURRENCY_TOKEN);

            assertThat(shortValues.accountId()).isEqualTo("1").hasSize(1);
            assertThat(SensitiveValues.fingerprint(shortValues.cardNumber())).isEqualTo(SensitiveValues.fingerprint("2"));
            assertThat(shortValues.cardNumber().length()).isEqualTo(1);
            assertThat(shortValues.embossedName()).isEqualTo("M").hasSize(1);
            assertThat(shortValues.expiryMonth()).isEqualTo("1").hasSize(1);
            assertThat(shortValues.expiryYear()).isEqualTo("9").hasSize(1);
            assertThat(shortValues.expiryDay()).isEqualTo("3").hasSize(1);
            assertThat(payloadOf(shortValues).get("accountId").asText()).isEqualTo("1");
        }

        @Test
        @DisplayName("never trims the trailing spaces a space-filled screen field arrives with")
        void neverTrimsTrailingSpaces() throws JsonProcessingException {
            CardUpdateRequest spaceFilled = new CardUpdateRequest("11         ", "11              ",
                    NAME_WITH_SURROUNDING_SPACES, " ", "1 ", "26  ", "8 ", KeyAction.PFK05,
                    navigation(), CONCURRENCY_TOKEN);

            assertThat(spaceFilled.accountId()).isEqualTo("11         ").hasSize(11);
            assertThat(SensitiveValues.fingerprint(spaceFilled.cardNumber())).isEqualTo(SensitiveValues.fingerprint("11              "));
            assertThat(spaceFilled.cardNumber().length()).isEqualTo(16);
            assertThat(spaceFilled.activeStatus()).isEqualTo(" ").hasSize(1);
            assertThat(spaceFilled.expiryMonth()).isEqualTo("1 ").hasSize(2);
            assertThat(spaceFilled.expiryYear()).isEqualTo("26  ").hasSize(4);
            assertThat(spaceFilled.expiryDay()).isEqualTo("8 ").hasSize(2);
            assertThat(payloadOf(spaceFilled).get("expiryYear").asText()).isEqualTo("26  ");
            assertThat(violationsOf(spaceFilled))
                    .as("a space-filled field sits exactly at its width, so nothing is reported")
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps the leading zeros of both identifiers on the accessor and on the wire")
        void keepsTheLeadingZerosOfBothIdentifiers() throws JsonProcessingException {
            CardUpdateRequest request = new CardUpdateRequest("00000000001", "0000000000000001",
                    NAME_MIXED_CASE, STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY,
                    KeyAction.PFK05, navigation(), CONCURRENCY_TOKEN);

            assertThat(request.accountId()).isEqualTo("00000000001").isNotEqualTo("1").hasSize(11);
            assertThat(SensitiveValues.fingerprint(request.cardNumber()))
                    .isEqualTo(SensitiveValues.fingerprint("0000000000000001"))
                    .isNotEqualTo(SensitiveValues.fingerprint("1"));
            assertThat(request.cardNumber().length()).isEqualTo(16);

            JsonNode payload = payloadOf(request);

            assertThat(payload.get("accountId").asText()).isEqualTo("00000000001");
            assertThat(payload.get("cardNumber").asText()).isEqualTo("0000000000000001");
            assertThat(payload.get("accountId").isTextual())
                    .as("a numeric type here would have discarded the leading zeros and shortened"
                            + " the external width")
                    .isTrue();
            assertThat(payload.get("cardNumber").isTextual()).isTrue();
        }

        @Test
        @DisplayName("binds both identifiers back from the wire with their leading zeros intact")
        void bindsBothIdentifiersBackWithLeadingZerosIntact() throws JsonProcessingException {
            String body = """
                    {"accountId":"00000000001","cardNumber":"0000000000000001",\
                    "expiryMonth":"01","expiryYear":"2026"}""";

            CardUpdateRequest bound = moduleEquivalentMapper().readValue(body,
                    CardUpdateRequest.class);

            assertThat(bound.accountId()).isEqualTo("00000000001");
            assertThat(SensitiveValues.fingerprint(bound.cardNumber())).isEqualTo(SensitiveValues.fingerprint("0000000000000001"));
            assertThat(bound.expiryMonth()).isEqualTo("01");
            assertThat(bound.expiryYear()).isEqualTo("2026");
        }

        @Test
        @DisplayName("carries the card number at full width, because a shortened key selects nothing")
        void carriesTheCardNumberAtFullWidth() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("cardNumber").asText())
                    .as("no masking, no truncation and no placeholder on a transported key")
                    .isEqualTo(CARD_NUMBER)
                    .hasSize(16)
                    .doesNotContain("*")
                    .doesNotContain(REDACTED);
        }
    }

    @Nested
    @DisplayName("the declarative constraint surface")
    class DeclarativeConstraintSurface {

        @Test
        @DisplayName("reports nothing when every component is absent")
        void reportsNothingWhenEveryComponentIsAbsent() {
            CardUpdateRequest absent = new CardUpdateRequest(null, null, null, null, null, null, null,
                    null, null, null);

            assertThat(violationsOf(absent))
                    .as("an empty submission is a real screen state that the legacy prompts against")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing when every text component is blank")
        void reportsNothingWhenEveryTextComponentIsBlank() {
            CardUpdateRequest blank = new CardUpdateRequest("", "", "", "", "", "", "", null,
                    NavigationContext.empty(), "");

            assertThat(violationsOf(blank))
                    .as("no presence rule of any kind may fire at this boundary")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports the width bound, and only the width bound, on each bounded component")
        void reportsOnlyTheWidthBoundOnEachBoundedComponent() {
            // The one thing measured at this boundary is whether a value could physically have
            // occupied its screen field: a value one character past the map width could not have been
            // typed into it and could not have been read out of the record either. Being over-long by
            // *content* is a different matter entirely and belongs to the cascade - a name of
            // punctuation at the full width of 50 is reported by the service, not here, which is what
            // the delegation tests above establish. A width bound also measures without altering, so
            // no leading or trailing space is lost to it.
            assertThat(onlyPathIn(violationsOf(withAccountId(OVER_WIDE_ACCOUNT_ID))))
                    .isEqualTo("accountId");
            assertThat(onlyPathIn(violationsOf(withCardNumber(OVER_WIDE_CARD_NUMBER))))
                    .isEqualTo("cardNumber");
            assertThat(onlyPathIn(violationsOf(withName(OVER_WIDE_NAME)))).isEqualTo("embossedName");
            assertThat(onlyPathIn(violationsOf(withStatus(OVER_WIDE_STATUS))))
                    .isEqualTo("activeStatus");
            assertThat(onlyPathIn(violationsOf(withExpiry(OVER_WIDE_MONTH, EXPIRY_YEAR, EXPIRY_DAY))))
                    .isEqualTo("expiryMonth");
            assertThat(onlyPathIn(violationsOf(withExpiry(EXPIRY_MONTH, OVER_WIDE_YEAR, EXPIRY_DAY))))
                    .isEqualTo("expiryYear");
        }

        @Test
        @DisplayName("reports nothing at all when every component sits exactly at its width")
        void reportsNothingAtTheExactWidth() {
            assertThat(violationsOf(withAccountId(ACCOUNT_ID))).isEmpty();
            assertThat(violationsOf(withCardNumber(CARD_NUMBER))).isEmpty();
            assertThat(violationsOf(withName(NAME_MIXED_CASE))).isEmpty();
            assertThat(violationsOf(withStatus(STATUS_ACTIVE))).isEmpty();
            assertThat(violationsOf(withExpiry(EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY))).isEmpty();
        }

        @Test
        @DisplayName("measures without altering, so validation leaves every value as it was")
        void measuresWithoutAltering() {
            CardUpdateRequest request = withName(NAME_WITH_SURROUNDING_SPACES);

            assertThat(violationsOf(request)).isEmpty();

            assertThat(request.embossedName())
                    .as("a width bound measures; nothing here trims, pads or re-cases")
                    .isEqualTo(NAME_WITH_SURROUNDING_SPACES);
            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(SensitiveValues.fingerprint(request.cardNumber())).isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
        }

        @Test
        @DisplayName("descends into the echoed navigation state, so its own widths are evaluated")
        void descendsIntoTheEchoedNavigationState() {
            NavigationContext overWide = new NavigationContext("CCUPX", "COCRDUPC", "CCUP",
                    "COCRDUPC", "OPERATR1", "A", NavigationContext.ProgramContext.REENTER,
                    "000000011", "MARY", "ANN", "SMITH", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA",
                    "COCRDUP");
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    NAME_MIXED_CASE, STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY,
                    KeyAction.PFK05, overWide, CONCURRENCY_TOKEN);

            assertThat(onlyPathIn(violationsOf(request)))
                    .as("without the cascade an over-long echoed identifier would cross unchecked")
                    .isEqualTo("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("treats an absent navigation state as no violation at all")
        void treatsAnAbsentNavigationStateAsNoViolation() {
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    NAME_MIXED_CASE, STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY,
                    KeyAction.PFK05, null, CONCURRENCY_TOKEN);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext()).isNull();
        }

        @Test
        @DisplayName("attaches no rule to the sealed proof, whose absence the service reports instead")
        void attachesNoRuleToTheSealedProof() {
            CardUpdateRequest withoutProof = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    NAME_MIXED_CASE, STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY,
                    KeyAction.PFK05, navigation(), null);

            assertThat(violationsOf(withoutProof))
                    .as("a stale, altered or absent proof is a conflict the service reports with the"
                            + " legacy concurrency notice, not a binding failure")
                    .isEmpty();
            assertThat(withoutProof.concurrencyToken()).isNull();
            assertThat(violationsOf(new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE,
                    STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05,
                    navigation(), FORTY_CHARACTER_DAY)))
                    .as("no width or pattern rule may couple this contract to the proof's encoding")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the status vocabulary the request deliberately does not enforce")
    class StatusVocabulary {

        @Test
        @DisplayName("declares exactly the two codes the screen caption names, and nothing else")
        void declaresExactlyTwoCodes() {
            List<String> names = new ArrayList<>();
            for (CardStatus status : CardStatus.values()) {
                names.add(status.name());
            }

            assertThat(names).containsExactly("Y", "N").hasSize(2);
            assertThat(names)
                    .as("a synthetic fallback would invent a status the estate never produces")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("answers active only for the active constant")
        void answersActiveOnlyForTheActiveConstant() {
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("carries the raw stored character on each constant")
        void carriesTheRawStoredCharacterOnEachConstant() {
            // Byte offset 91 of the 150-byte card record, declared one character wide at
            // CVACT02Y line 10.
            assertThat(CardStatus.Y.getCode()).isEqualTo('Y');
            assertThat(CardStatus.N.getCode()).isEqualTo('N');
        }

        @Test
        @DisplayName("resolves a known character and yields nothing for any other, without throwing")
        void resolvesAKnownCharacterAndNothingElse() {
            Optional<CardStatus> active = CardStatus.fromCode('Y');
            Optional<CardStatus> inactive = CardStatus.fromCode('N');

            assertThat(active).contains(CardStatus.Y);
            assertThat(inactive).contains(CardStatus.N);
            // The two further one-character states the account-update program declares beside the pair
            // are validation-flag states that drive field-level error display - one recording that a
            // submitted value failed validation and one recording that the field was left blank.
            // Neither is ever stored in the record, so neither resolves here.
            assertThat(CardStatus.fromCode('0')).isEmpty();
            assertThat(CardStatus.fromCode('B')).isEmpty();
            assertThat(CardStatus.fromCode('X')).isEmpty();
            assertThat(CardStatus.fromCode(' ')).isEmpty();
            assertThat(CardStatus.fromCode('y'))
                    .as("the legacy comparison tested the byte as supplied, so nothing is folded")
                    .isEmpty();
            assertThatCode(() -> CardStatus.fromCode('X')).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("resolves a stored string and yields nothing for absent, wider or unknown values")
        void resolvesAStoredStringAndNothingElse() {
            String absent = null;

            assertThat(CardStatus.fromCode(STATUS_ACTIVE)).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode("N")).contains(CardStatus.N);
            assertThat(CardStatus.fromCode(absent)).isEmpty();
            assertThat(CardStatus.fromCode("")).isEmpty();
            assertThat(CardStatus.fromCode(" ")).isEmpty();
            assertThat(CardStatus.fromCode("Y ")).as("nothing is trimmed before the lookup").isEmpty();
            assertThat(CardStatus.fromCode("YN")).isEmpty();
            assertThat(CardStatus.fromCode("y")).isEmpty();
            assertThatCode(() -> CardStatus.fromCode(absent)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is not what the request carries, which is the raw character instead")
        void isNotWhatTheRequestCarries() {
            // Holding the enumeration on the request would either reject an undeclared character at
            // construction or drop it silently, and the persistence column carries no membership
            // constraint, so a value outside the pair must round-trip untouched.
            String undeclared = withStatus("X").activeStatus();

            assertThat(undeclared).isEqualTo("X");
            assertThat(CardStatus.fromCode(undeclared))
                    .as("the request accepted a code the vocabulary does not know")
                    .isEmpty();

            String declared = populated().activeStatus();

            assertThat(declared).isEqualTo(STATUS_ACTIVE);
            assertThat(CardStatus.fromCode(declared)).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode(declared).map(CardStatus::isActive).orElse(false)).isTrue();
        }
    }

    @Nested
    @DisplayName("serialization shape, rendering and immutability")
    class SerializationShapeRenderingAndImmutability {

        @Test
        @DisplayName("omits every absent component from the payload rather than sending it as null")
        void omitsEveryAbsentComponent() throws JsonProcessingException {
            CardUpdateRequest onlyTheKey = new CardUpdateRequest(null, CARD_NUMBER, null, null, null,
                    null, null, null, null, null);

            assertThat(keysOf(payloadOf(onlyTheKey))).containsExactly("cardNumber");
        }

        @Test
        @DisplayName("omits nothing when every component is present")
        void omitsNothingWhenEveryComponentIsPresent() throws JsonProcessingException {
            assertThat(keysOf(payloadOf(populated()))).hasSameSizeAs(CONTRACT_KEYS);
        }

        @Test
        @DisplayName("tolerates an unknown incoming property, so a client may echo what it likes")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            String body = """
                    {"cardNumber":"0000000000000011","expiryMonth":"01",\
                    "somePropertyThisEndpointDoesNotConsume":"echoed"}""";

            CardUpdateRequest bound = moduleEquivalentMapper().readValue(body,
                    CardUpdateRequest.class);

            assertThat(SensitiveValues.fingerprint(bound.cardNumber())).isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
            assertThat(bound.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(bound.accountId()).isNull();
        }

        @Test
        @DisplayName("carries the attention key as the declared enumeration and never defaults it")
        void carriesTheAttentionKeyAndNeverDefaultsIt() throws JsonProcessingException {
            // The legacy key mapping has no catch-all branch, so an unrecognised key leaves the
            // previously held value in place; an absent key is therefore a real state and never a
            // default. The fold of keys 13 through 24 onto keys 1 through 12 lives in the utility
            // layer, which this contract does not depend on.
            assertThat(populated().keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(payloadOf(populated()).get("keyAction").asText()).isEqualTo("PFK05");

            CardUpdateRequest cancelling = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    NAME_MIXED_CASE, STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY,
                    KeyAction.PFK12, navigation(), CONCURRENCY_TOKEN);

            assertThat(cancelling.keyAction()).isEqualTo(KeyAction.PFK12);

            CardUpdateRequest noKey = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE,
                    STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, null, navigation(),
                    CONCURRENCY_TOKEN);

            assertThat(noKey.keyAction()).isNull();
            assertThat(violationsOf(noKey)).isEmpty();
            assertThat(keysOf(payloadOf(noKey))).doesNotContain("keyAction");
        }

        @Test
        @DisplayName("binds the attention key back from its declared name")
        void bindsTheAttentionKeyBackFromItsName() throws JsonProcessingException {
            CardUpdateRequest bound = moduleEquivalentMapper()
                    .readValue("{\"keyAction\":\"PFK05\"}", CardUpdateRequest.class);

            assertThat(bound.keyAction()).isEqualTo(KeyAction.PFK05);
        }

        @Test
        @DisplayName("withholds every component from the diagnostic rendering")
        void withholdsEveryComponentFromTheRendering() {
            String rendered = populated().toString();

            assertThat(rendered).isEqualTo("CardUpdateRequest[" + REDACTED + "]");
            assertThat(rendered)
                    .as("the generated rendering would have printed a full card number beside the"
                            + " name it is embossed with")
                    .doesNotContain(CARD_NUMBER, ACCOUNT_ID, NAME_MIXED_CASE, EXPIRY_MONTH,
                            EXPIRY_YEAR, EXPIRY_DAY, CONCURRENCY_TOKEN);
            for (String component : CONTRACT_KEYS) {
                assertThat(rendered)
                        .as("%s must not be named in the rendering, so a component added later cannot"
                                + " leak by omission", component)
                        .doesNotContain(component + "=");
            }
        }

        @Test
        @DisplayName("keeps the rendering separate from the payload, which carries the values in full")
        void keepsTheRenderingSeparateFromThePayload() throws JsonProcessingException {
            CardUpdateRequest request = populated();

            assertThat(request.toString()).contains(REDACTED);
            assertThat(payloadOf(request).toString())
                    .as("the service needs the key intact, because a shortened key selects nothing")
                    .doesNotContain(REDACTED)
                    .contains(CARD_NUMBER);
        }

        @Test
        @DisplayName("changes nothing an accessor returns, whatever the boundary did with it")
        void changesNothingAnAccessorReturns() throws JsonProcessingException {
            CardUpdateRequest request = populated();

            request.toString();
            violationsOf(request);
            payloadOf(request);

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(SensitiveValues.fingerprint(request.cardNumber())).isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
            assertThat(request.embossedName()).isEqualTo(NAME_MIXED_CASE);
            assertThat(request.activeStatus()).isEqualTo(STATUS_ACTIVE);
            assertThat(request.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(request.expiryYear()).isEqualTo(EXPIRY_YEAR);
            assertThat(request.expiryDay()).isEqualTo(EXPIRY_DAY);
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(request.navigationContext()).isEqualTo(navigation());
            assertThat(request.concurrencyToken()).isEqualTo(CONCURRENCY_TOKEN);
        }

        @Test
        @DisplayName("offers no way to alter an instance, so a variant is a second instance")
        void offersNoWayToAlterAnInstance() {
            CardUpdateRequest original = populated();
            CardUpdateRequest variant = withStatus("N");

            // There is no setter to call and no builder to mutate: the only route to a different
            // value is the canonical constructor, which is what makes this type safe to hand to a
            // service and to a validator at the same time.
            assertThat(variant).isNotSameAs(original).isNotEqualTo(original);
            assertThat(original.activeStatus())
                    .as("constructing the variant left the original exactly as it was")
                    .isEqualTo(STATUS_ACTIVE);
            assertThat(variant.activeStatus()).isEqualTo("N");
            assertThat(SensitiveValues.fingerprint(original.cardNumber())).isEqualTo(SensitiveValues.fingerprint(variant.cardNumber()));
        }

        @Test
        @DisplayName("compares equal by value, and unequal on a difference in any one component")
        void comparesEqualByValueAndUnequalOnAnyDifference() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(populated()).isNotEqualTo(null).isNotEqualTo(CONCURRENCY_TOKEN);

            assertThat(withAccountId("00000000012")).isNotEqualTo(populated());
            assertThat(withCardNumber("0000000000000012")).isNotEqualTo(populated());
            assertThat(withName(NAME_UPPER_FOLDED)).isNotEqualTo(populated());
            assertThat(withStatus("N")).isNotEqualTo(populated());
            assertThat(withExpiry("02", EXPIRY_YEAR, EXPIRY_DAY)).isNotEqualTo(populated());
            assertThat(withExpiry(EXPIRY_MONTH, "2027", EXPIRY_DAY)).isNotEqualTo(populated());
            assertThat(withExpiry(EXPIRY_MONTH, EXPIRY_YEAR, "29")).isNotEqualTo(populated());
            assertThat(new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE, STATUS_ACTIVE,
                    EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK03, navigation(),
                    CONCURRENCY_TOKEN)).isNotEqualTo(populated());
            assertThat(new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE, STATUS_ACTIVE,
                    EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05,
                    navigation().withFirstEntry(), CONCURRENCY_TOKEN)).isNotEqualTo(populated());
            assertThat(new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, NAME_MIXED_CASE, STATUS_ACTIVE,
                    EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(),
                    "a-different-sealed-proof")).isNotEqualTo(populated());
        }

        @Test
        @DisplayName("scopes the absence rule to the writing group and to nothing else")
        void scopesTheAbsenceRuleToTheWritingGroup() {
            // The group is a bare marker whose only purpose is to select the constraint that fires on
            // the turn that persists. Its emptiness is a source property; what matters at the boundary
            // is that naming it changes exactly one outcome and nothing else, which these four runs
            // together establish.
            assertThat(violationsOf(withAccountId(null), CardUpdateRequest.ConfirmSave.class))
                    .as("a confirming submission without the protected filter is clean")
                    .isEmpty();
            assertThat(onlyPathIn(violationsOf(withAccountId(ACCOUNT_ID),
                    CardUpdateRequest.ConfirmSave.class)))
                    .as("naming the group turns the absence rule on")
                    .isEqualTo("accountId");
            assertThat(violationsOf(withAccountId(ACCOUNT_ID)))
                    .as("not naming it leaves the rule off")
                    .isEmpty();

            CardUpdateRequest confirmingWithAnOverWideName = new CardUpdateRequest(null, CARD_NUMBER,
                    OVER_WIDE_NAME, STATUS_ACTIVE, EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY,
                    KeyAction.PFK05, navigation(), CONCURRENCY_TOKEN);

            assertThat(violationsOf(confirmingWithAnOverWideName,
                    CardUpdateRequest.ConfirmSave.class))
                    .as("the group carries no rule of its own beyond that single absence, so a width"
                            + " overrun is not reported under it")
                    .isEmpty();
            assertThat(onlyPathIn(violationsOf(confirmingWithAnOverWideName)))
                    .as("the very same instance under the default group reports the width bound and"
                            + " nothing else, which is what proves the two groups are disjoint")
                    .isEqualTo("embossedName");
        }
    }
}
