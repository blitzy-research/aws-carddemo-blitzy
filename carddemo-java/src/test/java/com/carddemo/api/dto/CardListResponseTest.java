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

import com.carddemo.domain.enums.CardStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardListResponse}, the response contract of legacy transaction {@code CCLI}.
 *
 * <p>The subject is a carrier, so these tests are about what it conveys unchanged rather than about
 * behaviour it implements. Four things are pinned: the shape of one row, the shape of a page, the
 * exact text of the screen's nine operator messages, and the fact that nothing on the way through is
 * normalised. Every field width, message literal and structural claim asserted here was read from
 * {@code app/cpy-bms/COCRDLI.CPY}, {@code app/bms/COCRDLI.bms} and {@code app/cbl/COCRDLIC.cbl}, and
 * every component name, accessor and constant was read from the production type rather than assumed.
 *
 * <h2>Why the component inventory is proven through the serialized payload</h2>
 *
 * <p>The obvious way to assert "this type declares exactly these components" is to interrogate the
 * class object for them at run time. That facility is unavailable to this module by design: the
 * unsafe-code audit holds its use at zero, which is the same constraint that forces every fixed-width
 * record mapper to be written by hand. So the inventory is proven the way a client actually observes
 * it - by serializing a fully populated instance and asserting the exact set and order of keys in the
 * payload. For a response contract that is the stronger assertion of the two, because it pins the wire
 * form a consumer binds against rather than an internal shape a consumer never sees. Immutability is
 * likewise demonstrated by construction: a mutation attempted through an accessor is observed to fail,
 * and a later mutation of a caller's list is observed not to reach a constructed instance.
 *
 * <h2>Why the mapper here is built locally</h2>
 *
 * <p>These tests construct their own {@link ObjectMapper} configured to match the settings the module
 * declares in {@code src/main/resources/application.yml} - absent properties omitted, dates not
 * written as timestamps, unknown properties tolerated on the way in, and plain decimal output. No
 * application context is started and no shared fixture is used, so the payload asserted here is
 * produced by settings visible in this file, and a drift between these settings and the module's own
 * is a difference a reader can see rather than one hidden behind a helper.
 *
 * <h2>Seven rows is screen geometry</h2>
 *
 * <p>The card-list screen has seven row slots. The figure lives on the shared paging contract as
 * {@link PageMetadata#CARD_LIST_PAGE_SIZE} and is referenced from there throughout these tests rather
 * than restated, so there is one authority for it. It is a property of the screen's shape, proven from
 * the seven-occurrence row table in the program and corroborated by the seven row families of the
 * symbolic map: changing it would put a different number of rows in front of an operator, which is a
 * behavioural change and not a setting. Consequently a short page stays short - no blank filler row is
 * ever manufactured to reach the full complement.
 *
 * <h2>The excluded map item</h2>
 *
 * <p>The symbolic map declares one further one-character item on its second through seventh row
 * families - six occurrences, none on the first row. The mapset gives every one of the six the
 * auto-skip and dark attributes, so a terminal neither accepts input into them nor displays them, and
 * an exhaustive search of the 1,459-line program finds no reference to any of them. The production
 * type therefore models four items per row and not five. These tests assert that exclusion positively,
 * by pinning the row payload to exactly four keys, so a later "restoration" of the missing item would
 * fail here. Following the production type, the item is identified by its position in the map rather
 * than by its name, so that the name appears nowhere in this file either; it is named in
 * {@code docs/decision-log.md}.
 *
 * <h2>Recorded source oddities, asserted nowhere because they are not this type's concern</h2>
 *
 * <ul>
 *   <li>The browse walks the card cluster in card-number sequence and applies the account filter
 *       after each record is retrieved, in the filtering paragraph at {@code app/cbl/COCRDLIC.cbl}
 *       line 1382.</li>
 *   <li>An alternate-index name for the account-keyed path it does not take is declared at lines 213
 *       to 217 of the same program and then never referenced.</li>
 *   <li>The estate's only packed-decimal declaration is at line 70 of that program, on a screen work
 *       field that is never persisted, which is why the module carries a zoned-decimal codec and no
 *       packed-decimal decoder.</li>
 *   <li>The selection tally at lines 1079 to 1082 and the positional conversion at lines 1090 to 1093
 *       belong to the service that reproduces the program. What reaches this contract is their
 *       outcome as data.</li>
 * </ul>
 *
 * <p>Provenance for every citation above: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No source statement is reproduced anywhere in
 * this file.
 */
@DisplayName("CardListResponse :: card-list response contract of legacy transaction CCLI")
class CardListResponseTest {

    /**
     * The response payload keys, in the order the symbolic map declares the fields they carry.
     *
     * <p>This is the component inventory, stated as the wire form a client binds against. Asserting
     * the payload against it proves the inventory, the spelling of every name and the declaration
     * order in one comparison, and does so without interrogating the class object.</p>
     */
    private static final List<String> PAYLOAD_KEYS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "displayedPageNumber", "accountFilter", "cardNumberFilter", "rows",
            "selectionErrorFlags", "infoMessage", "errorMessage", "generalError", "pageMetadata",
            "focusScreenFieldId", "nextRoute", "navigationContext");

    /**
     * The four keys one row carries, in declaration order, and deliberately no fifth. The excluded
     * map item would have appeared in this payload had the production type modelled it.
     */
    private static final List<String> ROW_PAYLOAD_KEYS_IN_MAP_ORDER =
            List.of("selection", "accountNumber", "cardNumber", "cardStatus");

    /**
     * The nine operator messages the screen renders, exactly as the program builds them.
     *
     * <p>External interface text: operators and downstream tooling match on it, so it is part of the
     * contract rather than display sugar. Three properties are faithful to the source and must not be
     * tidied - every message is upper case, whereas the account, card-detail, transaction, report,
     * bill-payment and administrative-user screens use mixed case and the difference is contract
     * rather than defect; the two filter messages carry no space after their comma and say "A" where
     * English would say "AN", while the row-action prompt does carry a space after its comma, so the
     * punctuation genuinely differs between messages rather than being one rule applied
     * inconsistently; and exactly one message ends in a full stop.</p>
     */
    private static final List<String> ALL_NINE_MESSAGES = List.of(
            CardListResponse.MSG_ACCOUNT_FILTER_INVALID,
            CardListResponse.MSG_CARD_FILTER_INVALID,
            CardListResponse.MSG_INVALID_ACTION_CODE,
            CardListResponse.MSG_MORE_THAN_ONE_ACTION,
            CardListResponse.MSG_NO_PREVIOUS_PAGES,
            CardListResponse.MSG_NO_MORE_PAGES,
            CardListResponse.MSG_NO_MORE_RECORDS,
            CardListResponse.MSG_NO_RECORDS_FOUND,
            CardListResponse.MSG_ROW_ACTION_PROMPT);

    /** Fictional eleven-character account identifier, chosen to open with zeros. */
    private static final String ACCOUNT_FILTER = "00000000011";

    /** Fictional sixteen-character card number. Never a real issuer range. */
    private static final String CARD_FILTER = "4111111111111111";

    /** Fictional eleven-character account identifier carried on a row. */
    private static final String ROW_ACCOUNT = "00000000022";

    /** Fictional sixteen-character card number carried on a row. */
    private static final String ROW_CARD = "4222222222222222";

    /**
     * Fictional backward-restart browse key: the card number alone, sixteen characters.
     *
     * <p>The card-list program declares a twenty-seven-character composite of a card number followed by
     * an account identifier, but only the card-number half ever reaches the browse key - the companion
     * move of the account half is commented out at every one of its four repositioning sites.
     */
    private static final String PREVIOUS_KEY = "4222222222222299";

    /** Fictional forward-restart browse key, same composition. */
    private static final String NEXT_KEY = "4333333333333333";

    /** The stand-in the production renderings emit in place of each withheld value. */
    private static final String WITHHELD = "***REDACTED***";

    /** The page indicator this screen renders, at the three characters its map field declares. */
    private static final String PAGE_INDICATOR = "001";

    private static CardListResponse.CardListRow row(int ordinal) {
        return new CardListResponse.CardListRow(
                "S", "0000000002" + ordinal, "422222222222222" + ordinal, "Y");
    }

    private static List<CardListResponse.CardListRow> rows(int count) {
        List<CardListResponse.CardListRow> built = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            built.add(row(ordinal));
        }
        return built;
    }

    /** A positional indicator marking the first slot only, aligned to {@code count} rows. */
    private static List<Boolean> flags(int count) {
        List<Boolean> built = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            built.add(position == 0);
        }
        return built;
    }

    private static NavigationContext navigation() {
        return new NavigationContext("CCLI", "COCRDLIC", "CCDL", "COCRDSLC", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_FILTER, "Y", CARD_FILTER, "CCRDLIA", "COCRDLI");
    }

    private static PageMetadata paging() {
        return PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, PREVIOUS_KEY, NEXT_KEY,
                true, false, PAGE_INDICATOR);
    }

    /** A response carrying every component populated, used wherever the whole shape matters. */
    private static CardListResponse fullyPopulated(List<CardListResponse.CardListRow> rows,
            List<Boolean> selectionErrorFlags) {
        return new CardListResponse("CCLI", "AWS Mainframe Modernization", "08/02/26", "COCRDLIC",
                "CardDemo", "14:30:00", PAGE_INDICATOR, ACCOUNT_FILTER, CARD_FILTER, rows,
                selectionErrorFlags, CardListResponse.MSG_ROW_ACTION_PROMPT,
                CardListResponse.MSG_NO_MORE_RECORDS, true, paging(), "CRDSID", "/api/cards",
                navigation());
    }

    /** A full complement of rows with no positional indicator, which is the ordinary case. */
    private static CardListResponse fullPage() {
        return fullyPopulated(rows(PageMetadata.CARD_LIST_PAGE_SIZE), List.of());
    }

    /** A response with every nullable component absent and no rows. */
    private static CardListResponse allAbsent() {
        return new CardListResponse(null, null, null, null, null, null, null, null, null, null,
                null, null, null, false, null, null, null, null);
    }

    /**
     * A response carrying one message in the error slot and nothing else that matters, used to drive
     * each of the nine literals through the contract.
     *
     * @param message the message to carry
     * @return a response whose error slot holds exactly that message
     */
    private static CardListResponse carrying(String message) {
        return new CardListResponse(null, null, null, null, null, null, null, null, null, null,
                null, null, message, true, null, null, null, null);
    }

    /**
     * Builds a mapper configured exactly as the module declares its own: absent properties omitted,
     * dates not written as timestamps, unknown properties tolerated, plain decimal output.
     *
     * @return a mapper matching the module's declared serialization settings
     */
    private static ObjectMapper declaredSettingsMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static JsonNode payloadOf(Object value) throws JsonProcessingException {
        ObjectMapper mapper = declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /**
     * Every identifying value these tests put into a response, so that a rendering can be asserted
     * against the whole set at once.
     *
     * <p>Assertions over a rendering are made against the entire string rather than a sliced portion
     * of it. Slicing would need offset arithmetic, which this module does not use anywhere, and it is
     * unnecessary here: the delegated navigation state withholds its own identifying values, so no
     * identifier reaches the rendering by any route and the whole string is a legitimate subject.</p>
     */
    private static final List<String> EVERY_IDENTIFYING_VALUE = List.of(
            ACCOUNT_FILTER, CARD_FILTER, ROW_ACCOUNT, ROW_CARD, PREVIOUS_KEY, NEXT_KEY);

    @Nested
    @DisplayName("one row carries exactly four items and no fifth")
    class RowShape {

        @Test
        @DisplayName("publishes exactly the four map items, in the order the map declares them")
        void publishesExactlyFourItems() throws JsonProcessingException {
            JsonNode payload = payloadOf(new CardListResponse.CardListRow(
                    "S", ROW_ACCOUNT, ROW_CARD, "Y"));

            assertThat(keysOf(payload))
                    .as("a row is the echoed action code, the account, the card and the status")
                    .containsExactlyElementsOf(ROW_PAYLOAD_KEYS_IN_MAP_ORDER)
                    .hasSize(4);
        }

        @Test
        @DisplayName("publishes no item beyond those four, so the excluded map item stays excluded")
        void publishesNoFifthItem() throws JsonProcessingException {
            JsonNode payload = payloadOf(new CardListResponse.CardListRow(
                    "S", ROW_ACCOUNT, ROW_CARD, "Y"));

            assertThat(payload.size())
                    .as("the map item declared only on the second through seventh row families is"
                            + " referenced nowhere in the program and is modelled nowhere here")
                    .isEqualTo(ROW_PAYLOAD_KEYS_IN_MAP_ORDER.size());
            assertThat(keysOf(payload))
                    .as("no terminal plumbing either: no length, flag, attribute or filler item")
                    .allSatisfy(key -> assertThat(key).isIn(ROW_PAYLOAD_KEYS_IN_MAP_ORDER));
        }

        @Test
        @DisplayName("carries the status as the raw one-character code rather than as the enumeration")
        void carriesTheStatusAsRawText() throws JsonProcessingException {
            CardListResponse.CardListRow unmapped =
                    new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Q");

            assertThat(unmapped.cardStatus())
                    .as("a code outside the known pair flows through untouched, as it does in the"
                            + " legacy system, which a fixed enumeration could not do")
                    .isEqualTo("Q");
            assertThat(payloadOf(unmapped).get("cardStatus").asText()).isEqualTo("Q");
            assertThat(CardStatus.fromCode(unmapped.cardStatus()))
                    .as("the meaning is resolved through the domain lookup, not by this contract")
                    .isEmpty();
        }

        @Test
        @DisplayName("reads every item back exactly as supplied, at its full map width")
        void readsEveryItemBackUnaltered() {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("U", ROW_ACCOUNT, ROW_CARD, "N");

            assertThat(subject.selection()).isEqualTo("U");
            assertThat(subject.accountNumber())
                    .isEqualTo(ROW_ACCOUNT)
                    .hasSize(CardListResponse.ACCOUNT_NUMBER_LENGTH);
            assertThat(subject.cardNumber())
                    .isEqualTo(ROW_CARD)
                    .hasSize(CardListResponse.CARD_NUMBER_LENGTH);
            assertThat(subject.cardStatus())
                    .isEqualTo("N")
                    .hasSize(CardListResponse.CARD_STATUS_LENGTH);
        }

        @Test
        @DisplayName("compares by value and renders through the overridden representation")
        void comparesByValue() {
            CardListResponse.CardListRow one =
                    new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Y");
            CardListResponse.CardListRow same =
                    new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Y");
            CardListResponse.CardListRow other =
                    new CardListResponse.CardListRow("U", ROW_ACCOUNT, ROW_CARD, "Y");

            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
            assertThat(one.toString()).startsWith("CardListRow[").endsWith("]");
        }
    }

    @Nested
    @DisplayName("the response carries exactly the eighteen map components")
    class ResponseShape {

        @Test
        @DisplayName("publishes exactly those components, in the order the map declares their fields")
        void publishesExactlyTheMapComponents() throws JsonProcessingException {
            assertThat(keysOf(payloadOf(fullPage())))
                    .containsExactlyElementsOf(PAYLOAD_KEYS_IN_MAP_ORDER)
                    .hasSize(18);
        }

        @Test
        @DisplayName("publishes no count of records or of pages, which the legacy browse never knew")
        void publishesNoTotals() throws JsonProcessingException {
            JsonNode paging = payloadOf(fullPage()).get("pageMetadata");

            assertThat(keysOf(paging))
                    .as("the accompanying paging state publishes the row count of this page, the two"
                            + " boundary keys, the direction, the two end-of-browse indicators and"
                            + " the display indicator - and nothing that counts the cluster. A count"
                            + " of records or of pages would be fabricated information backed by a"
                            + " query the original never issued: exhaustion was discovered by"
                            + " attempting one more read, which is exactly what the end-of-data and"
                            + " end-of-browse messages express. Asserted as the complete key set"
                            + " rather than as a list of names to avoid, so that any counting"
                            + " component fails here whatever it were called")
                    .containsExactly("pageSize", "previousCursorKey", "nextCursorKey", "direction",
                            "hasMorePages", "hasPreviousPages", "displayedPageNumber")
                    .hasSize(7);
            assertThat(keysOf(payloadOf(fullPage())))
                    .as("and the response's own key set, pinned exactly above, admits no such"
                            + " component either")
                    .containsExactlyElementsOf(PAYLOAD_KEYS_IN_MAP_ORDER);
            assertThat(paging.get("hasMorePages").asBoolean()).isTrue();
            assertThat(paging.get("hasPreviousPages").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("publishes no terminal artefact and no problem-document member")
        void publishesNoTerminalArtefactOrProblemDocument() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(fullPage()));

            assertThat(keys)
                    .as("no generated 3270 length, flag, attribute or colour item and no map"
                            + " coordinate reaches this contract")
                    .doesNotContain("length", "flag", "attribute", "colour", "color", "highlight",
                            "row", "column", "cursorPosition");
            assertThat(keys)
                    .as("the error shape is this screen's own message slots, not RFC 7807")
                    .doesNotContain("type", "detail", "instance", "status")
                    .contains("errorMessage", "infoMessage", "generalError");
        }

        @Test
        @DisplayName("states each renamed width at the value its map field declares")
        void statesEachRenamedWidth() {
            assertThat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("this screen's page indicator is three characters wide, and the eight of the"
                            + " transaction-list and user-list maps is a different contract")
                    .isEqualTo(3)
                    .isNotEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);
            assertThat(CardListResponse.ERROR_MESSAGE_LENGTH)
                    .as("the error slot is seventy-eight here, and eighty on the card-detail and"
                            + " card-update maps, which is never normalised across them")
                    .isEqualTo(78);
            assertThat(CardListResponse.INFO_MESSAGE_LENGTH)
                    .as("the informational slot is forty-five here and on the account maps, and"
                            + " forty on the card-detail and card-update maps")
                    .isEqualTo(45);
            assertThat(CardListResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("states each row and header width at the value its map field declares")
        void statesEachRowAndHeaderWidth() {
            assertThat(CardListResponse.SELECTION_LENGTH).isEqualTo(1);
            assertThat(CardListResponse.ACCOUNT_NUMBER_LENGTH).isEqualTo(11);
            assertThat(CardListResponse.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(CardListResponse.CARD_STATUS_LENGTH).isEqualTo(1);
            assertThat(CardListResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(CardListResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(CardListResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.ACCOUNT_NUMBER_LENGTH
                    + CardListResponse.CARD_NUMBER_LENGTH + CardListResponse.CARD_STATUS_LENGTH)
                    .as("the three carried row values account for the row element the program"
                            + " declares over its all-rows area")
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("reads every component back exactly as supplied")
        void readsEveryComponentBack() {
            CardListResponse subject = fullPage();

            assertThat(subject.transactionName()).isEqualTo("CCLI");
            assertThat(subject.title01()).isEqualTo("AWS Mainframe Modernization");
            assertThat(subject.currentDate()).isEqualTo("08/02/26");
            assertThat(subject.programName()).isEqualTo("COCRDLIC");
            assertThat(subject.title02()).isEqualTo("CardDemo");
            assertThat(subject.currentTime()).isEqualTo("14:30:00");
            assertThat(subject.displayedPageNumber()).isEqualTo(PAGE_INDICATOR);
            assertThat(subject.accountFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(subject.cardNumberFilter()).isEqualTo(CARD_FILTER);
            assertThat(subject.rows()).hasSize(PageMetadata.CARD_LIST_PAGE_SIZE);
            assertThat(subject.selectionErrorFlags()).isEmpty();
            assertThat(subject.infoMessage()).isEqualTo(CardListResponse.MSG_ROW_ACTION_PROMPT);
            assertThat(subject.errorMessage()).isEqualTo(CardListResponse.MSG_NO_MORE_RECORDS);
            assertThat(subject.generalError()).isTrue();
            assertThat(subject.pageMetadata()).isEqualTo(paging());
            assertThat(subject.focusScreenFieldId()).isEqualTo("CRDSID");
            assertThat(subject.nextRoute()).isEqualTo("/api/cards");
            assertThat(subject.navigationContext()).isEqualTo(navigation());
        }

        @Test
        @DisplayName("compares by value across every component")
        void comparesByValue() {
            CardListResponse one = fullPage();
            CardListResponse same = fullPage();
            CardListResponse other =
                    fullyPopulated(rows(PageMetadata.CARD_LIST_PAGE_SIZE - 1), List.of());

            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
        }
    }

    @Nested
    @DisplayName("the screen's row slots bound the page above")
    class ScreenGeometry {

        @Test
        @DisplayName("accepts a page occupying every row slot the screen has")
        void acceptsAPageOccupyingEverySlot() {
            CardListResponse subject = fullPage();

            assertThat(subject.rows())
                    .hasSize(PageMetadata.CARD_LIST_PAGE_SIZE)
                    .hasSize(7);
        }

        @Test
        @DisplayName("rejects one row beyond the slots the screen has, naming the count")
        void rejectsOneRowBeyondTheSlots() {
            List<CardListResponse.CardListRow> overfull =
                    rows(PageMetadata.CARD_LIST_PAGE_SIZE + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fullyPopulated(overfull, List.of()))
                    .withMessageContaining(String.valueOf(PageMetadata.CARD_LIST_PAGE_SIZE))
                    .withMessageContaining(String.valueOf(PageMetadata.CARD_LIST_PAGE_SIZE + 1));
        }

        @Test
        @DisplayName("accepts a partly filled page unchanged and manufactures no blank filler row")
        void acceptsAPartlyFilledPageUnchanged() {
            CardListResponse subject = fullyPopulated(rows(3), List.of());

            assertThat(subject.rows())
                    .as("a short page stays short: nothing is padded up to the full complement")
                    .hasSize(3);
            assertThat(subject.rows()).noneSatisfy(row -> assertThat(row.cardNumber()).isNull());
        }

        @Test
        @DisplayName("treats an absent row collection as an empty page rather than as a missing one")
        void treatsAnAbsentRowCollectionAsEmpty() {
            CardListResponse subject = allAbsent();

            assertThat(subject.rows()).isNotNull().isEmpty();
            assertThat(subject.selectionErrorFlags()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("preserves the supplied row order exactly, sorting and de-duplicating nothing")
        void preservesTheSuppliedRowOrder() {
            CardListResponse.CardListRow third = row(3);
            CardListResponse.CardListRow first = row(1);
            CardListResponse.CardListRow duplicate = row(3);
            List<CardListResponse.CardListRow> asSupplied = List.of(third, first, duplicate);

            CardListResponse subject = fullyPopulated(asSupplied, List.of());

            assertThat(subject.rows())
                    .as("a backward page arrives already in presentation order, so any re-ordering"
                            + " here would invert a page that was already correct")
                    .containsExactly(third, first, duplicate)
                    .hasSize(3);
        }

        @Test
        @DisplayName("takes the row-slot count from the shared paging contract and restates it nowhere")
        void takesTheRowSlotCountFromTheSharedContract() {
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE)
                    .as("one authority for the figure, on the contract that owns paging")
                    .isEqualTo(7)
                    .isNotEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .isNotEqualTo(PageMetadata.USER_LIST_PAGE_SIZE);
            assertThat(paging().pageSize()).isEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("the positional indicator stays aligned with the rows")
    class PositionalIndicator {

        @Test
        @DisplayName("accepts an absent indicator, which is the ordinary non-rejection case")
        void acceptsAnAbsentIndicator() {
            assertThatNoException().isThrownBy(
                    () -> fullyPopulated(rows(PageMetadata.CARD_LIST_PAGE_SIZE), List.of()));
            assertThat(fullPage().selectionErrorFlags()).isEmpty();
        }

        @Test
        @DisplayName("accepts an indicator holding one entry per row, marked and unmarked alike")
        void acceptsAnAlignedIndicator() {
            CardListResponse subject = fullyPopulated(rows(4), flags(4));

            assertThat(subject.selectionErrorFlags())
                    .as("an unmarked slot is present and false rather than absent, so position i"
                            + " still describes row i")
                    .containsExactly(true, false, false, false)
                    .hasSameSizeAs(subject.rows());
        }

        @Test
        @DisplayName("rejects an indicator shorter than the rows, naming both counts")
        void rejectsAShortIndicator() {
            List<CardListResponse.CardListRow> four = rows(4);
            List<Boolean> two = flags(2);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fullyPopulated(four, two))
                    .withMessageContaining("2")
                    .withMessageContaining("4");
        }

        @Test
        @DisplayName("rejects an indicator longer than the rows")
        void rejectsALongIndicator() {
            List<CardListResponse.CardListRow> two = rows(2);
            List<Boolean> four = flags(4);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fullyPopulated(two, four));
        }

        @Test
        @DisplayName("rejects an indicator attributing an error to a row an empty page does not have")
        void rejectsAnIndicatorOnAnEmptyPage() {
            List<Boolean> one = flags(1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fullyPopulated(List.of(), one));
        }

        @Test
        @DisplayName("carries the indicator as data and tallies, rewrites and interprets nothing")
        void carriesTheIndicatorAsData() throws JsonProcessingException {
            List<CardListResponse.CardListRow> bothActedOn = List.of(
                    new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Y"),
                    new CardListResponse.CardListRow("U", ROW_ACCOUNT, ROW_CARD, "Y"));

            CardListResponse subject = fullyPopulated(bothActedOn, List.of(true, true));

            assertThat(subject.selectionErrorFlags()).containsExactly(true, true);
            assertThat(subject.rows())
                    .as("the echoed action codes survive untouched: no code is cleared, replaced"
                            + " or converted into an indicator value by this contract")
                    .extracting(CardListResponse.CardListRow::selection)
                    .containsExactly("S", "U");
            assertThat(payloadOf(subject).get("selectionErrorFlags").toString())
                    .isEqualTo("[true,true]");
        }
    }

    @Nested
    @DisplayName("both collections are copied on the way in and unmodifiable on the way out")
    class CollectionImmutability {

        @Test
        @DisplayName("a later change to the caller's row list does not reach a constructed response")
        void aLaterChangeToTheCallersRowListDoesNotReach() {
            List<CardListResponse.CardListRow> callersList = new ArrayList<>(rows(2));
            CardListResponse subject = fullyPopulated(callersList, List.of());

            callersList.clear();
            callersList.add(row(5));

            assertThat(subject.rows()).hasSize(2).containsExactly(row(1), row(2));
        }

        @Test
        @DisplayName("a later change to the caller's indicator does not reach a constructed response")
        void aLaterChangeToTheCallersIndicatorDoesNotReach() {
            List<Boolean> callersList = new ArrayList<>(flags(2));
            CardListResponse subject = fullyPopulated(rows(2), callersList);

            callersList.set(1, true);

            assertThat(subject.selectionErrorFlags()).containsExactly(true, false);
        }

        @Test
        @DisplayName("neither retained collection can be changed through its accessor")
        void neitherRetainedCollectionCanBeChanged() {
            CardListResponse subject = fullyPopulated(rows(2), flags(2));
            List<CardListResponse.CardListRow> retainedRows = subject.rows();
            List<Boolean> retainedFlags = subject.selectionErrorFlags();
            CardListResponse.CardListRow intruder = row(6);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> retainedRows.add(intruder));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> retainedFlags.add(true));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(retainedRows::clear);
        }

        @Test
        @DisplayName("an empty page hands back an unmodifiable collection rather than null")
        void anEmptyPageHandsBackAnUnmodifiableCollection() {
            List<CardListResponse.CardListRow> retained = allAbsent().rows();
            CardListResponse.CardListRow intruder = row(1);

            assertThat(retained).isNotNull().isEmpty();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> retained.add(intruder));
        }

        @Test
        @DisplayName("a null entry in either collection is refused rather than carried")
        void aNullEntryIsRefused() {
            List<CardListResponse.CardListRow> withNullRow = new ArrayList<>();
            withNullRow.add(null);
            List<Boolean> withNullFlag = new ArrayList<>();
            withNullFlag.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .as("a row is either present or the page is shorter, and every indicator"
                            + " position is explicitly marked or explicitly unmarked")
                    .isThrownBy(() -> fullyPopulated(withNullRow, List.of()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> fullyPopulated(rows(1), withNullFlag));
        }
    }

    @Nested
    @DisplayName("the nine operator messages cross unchanged, character for character")
    class OperatorMessages {

        @Test
        @DisplayName("every one of the nine round-trips through the contract byte for byte")
        void everyOneRoundTripsByteForByte() throws JsonProcessingException {
            assertThat(ALL_NINE_MESSAGES).hasSize(9).doesNotHaveDuplicates();

            for (String message : ALL_NINE_MESSAGES) {
                CardListResponse subject = carrying(message);

                assertThat(subject.errorMessage())
                        .as("carried untrimmed, un-case-folded and unedited")
                        .isEqualTo(message);
                assertThat(payloadOf(subject).get("errorMessage").asText())
                        .as("and unchanged on the wire")
                        .isEqualTo(message);
            }
        }

        @Test
        @DisplayName("all nine texts are pinned here independently of the constants that hold them")
        void allNineTextsArePinnedIndependently() {
            assertThat(ALL_NINE_MESSAGES)
                    .as("Spelling each text out here rather than only comparing a constant with"
                            + " itself is what makes this an independent check: were a constant"
                            + " edited, this assertion would fail rather than follow the edit. The"
                            + " texts are transcribed from the program that builds them and are"
                            + " what a consumer matches on, so they are pinned character for"
                            + " character - including the two unspaced commas, the two \"A\""
                            + " articles English would write as \"AN\", the single trailing full"
                            + " stop, and the one spaced comma on the informational prompt")
                    .containsExactly(
                            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER",
                            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER",
                            "INVALID ACTION CODE",
                            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE",
                            "NO PREVIOUS PAGES TO DISPLAY",
                            "NO MORE PAGES TO DISPLAY",
                            "NO MORE RECORDS TO SHOW",
                            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.",
                            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @Test
        @DisplayName("the two paging guards and the end-of-data text stay three distinct messages")
        void thePagingGuardsAndEndOfDataStayDistinct() {
            assertThat(CardListResponse.MSG_NO_PREVIOUS_PAGES)
                    .isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_PAGES).isEqualTo("NO MORE PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_RECORDS)
                    .as("reaching the end of the data while filling a page is a different condition"
                            + " from refusing a navigation already at the end of the browse, and the"
                            + " legacy renders a different text for each")
                    .isEqualTo("NO MORE RECORDS TO SHOW")
                    .isNotEqualTo(CardListResponse.MSG_NO_MORE_PAGES);
            assertThat(CardListResponse.MSG_INVALID_ACTION_CODE).isEqualTo("INVALID ACTION CODE");
            assertThat(CardListResponse.MSG_MORE_THAN_ONE_ACTION)
                    .isEqualTo("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE");
        }

        @Test
        @DisplayName("the account filter rejection keeps its comma unspaced and its article as written")
        void theAccountFilterRejectionKeepsItsPunctuation() {
            String message = CardListResponse.MSG_ACCOUNT_FILTER_INVALID;

            assertThat(message).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
            assertThat(message)
                    .as("no space is inserted after the comma")
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF");
            assertThat(message)
                    .as("the article stays as the program wrote it and is not corrected")
                    .contains("A 11 DIGIT")
                    .doesNotContain("AN 11 DIGIT");
            assertThat(carrying(message).errorMessage()).isEqualTo(message);
        }

        @Test
        @DisplayName("the card filter rejection keeps its comma unspaced and its article as written")
        void theCardFilterRejectionKeepsItsPunctuation() {
            String message = CardListResponse.MSG_CARD_FILTER_INVALID;

            assertThat(message).isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
            assertThat(message).contains("FILTER,IF").doesNotContain("FILTER, IF");
            assertThat(message).contains("A 16 DIGIT").doesNotContain("AN 16 DIGIT");
            assertThat(carrying(message).errorMessage()).isEqualTo(message);
        }

        @Test
        @DisplayName("exactly one of the nine ends in a full stop, and it keeps it")
        void exactlyOneEndsInAFullStop() {
            assertThat(ALL_NINE_MESSAGES)
                    .filteredOn(message -> message.endsWith("."))
                    .as("the trailing stop is part of that message's text and is neither removed"
                            + " from it nor added to the other eight")
                    .containsExactly("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
            assertThat(carrying(CardListResponse.MSG_NO_RECORDS_FOUND).errorMessage())
                    .endsWith("CONDITION.");
        }

        @Test
        @DisplayName("the row-action prompt does carry a space after its comma, unlike the filters")
        void theRowActionPromptCarriesASpaceAfterItsComma() {
            String prompt = CardListResponse.MSG_ROW_ACTION_PROMPT;

            assertThat(prompt).isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
            assertThat(prompt)
                    .as("the punctuation genuinely differs between messages rather than being one"
                            + " rule applied inconsistently, so neither form may be harmonised")
                    .contains("DETAIL, U");
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID).contains("FILTER,IF");
        }

        @Test
        @DisplayName("all nine are upper case, and the mixed case of other screens is not imposed")
        void allNineAreUpperCase() {
            for (String message : ALL_NINE_MESSAGES) {
                assertThat(message.codePoints().noneMatch(Character::isLowerCase))
                        .as("this screen's family is upper case throughout, whereas the account,"
                                + " card-detail, transaction, report, bill-payment and"
                                + " administrative-user screens use mixed case: the difference is"
                                + " contract and is never harmonised in either direction. Asserted"
                                + " as the absence of a lower-case character rather than by folding"
                                + " the text, because folding is itself the transformation this"
                                + " contract forbids - and because a fold would answer differently"
                                + " under a locale whose case rules differ from the root's: %s",
                                message)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the eight rejection texts fit the error slot and the prompt fits the info slot")
        void eachMessageFitsTheSlotItBelongsTo() {
            for (String message : ALL_NINE_MESSAGES) {
                if (message.equals(CardListResponse.MSG_ROW_ACTION_PROMPT)) {
                    assertThat(message.length())
                            .as("the informational prompt belongs in the forty-five character slot")
                            .isLessThanOrEqualTo(CardListResponse.INFO_MESSAGE_LENGTH);
                } else {
                    assertThat(message.length())
                            .as("a rejection text belongs in the seventy-eight character slot")
                            .isLessThanOrEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH);
                }
            }
        }

        @Test
        @DisplayName("both message slots are carried independently and neither is inferred")
        void bothSlotsAreCarriedIndependently() {
            CardListResponse subject = fullPage();

            assertThat(subject.infoMessage()).isEqualTo(CardListResponse.MSG_ROW_ACTION_PROMPT);
            assertThat(subject.errorMessage()).isEqualTo(CardListResponse.MSG_NO_MORE_RECORDS);
            assertThat(subject.generalError())
                    .as("the error condition is stated in its own right rather than inferred from"
                            + " the error slot being occupied, as the legacy flag is")
                    .isTrue();
            assertThat(carrying(null).generalError()).isTrue();
            assertThat(carrying(null).errorMessage()).isNull();
        }
    }

    @Nested
    @DisplayName("field widths are this screen's own and are never normalised")
    class FieldWidthFidelity {

        @Test
        @DisplayName("an error message occupying the whole slot survives untrimmed")
        void aFullWidthErrorMessageSurvivesUntrimmed() throws JsonProcessingException {
            String occupied = "E".repeat(CardListResponse.ERROR_MESSAGE_LENGTH - 4) + "    ";
            CardListResponse subject = carrying(occupied);

            assertThat(subject.errorMessage())
                    .as("trailing spaces on a space-filled screen field are part of what was"
                            + " displayed, so they are neither trimmed nor stripped")
                    .isEqualTo(occupied)
                    .hasSize(78)
                    .endsWith("    ");
            assertThat(payloadOf(subject).get("errorMessage").asText()).isEqualTo(occupied);
        }

        @Test
        @DisplayName("a page indicator at this screen's three characters survives untrimmed")
        void aThreeCharacterPageIndicatorSurvivesUntrimmed() throws JsonProcessingException {
            CardListResponse subject = new CardListResponse(null, null, null, null, null, null,
                    "1  ", null, null, null, null, null, null, false, null, null, null, null);

            assertThat(subject.displayedPageNumber())
                    .as("three characters here, never widened to the eight the transaction-list"
                            + " and user-list maps declare")
                    .isEqualTo("1  ")
                    .hasSize(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH);
            assertThat(payloadOf(subject).get("displayedPageNumber").asText()).isEqualTo("1  ");
        }

        @Test
        @DisplayName("an over-long value is reported by the declared bound and never shortened")
        void anOverLongValueIsReportedAndNeverShortened() {
            String tooWide = "X".repeat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH + 1);
            CardListResponse subject = new CardListResponse(null, null, null, null, null, null,
                    tooWide, null, null, null, null, null, null, false, null, null, null, null);

            assertThat(subject.displayedPageNumber())
                    .as("a bound measures and reports; it does not alter, clamp or truncate")
                    .isEqualTo(tooWide)
                    .hasSize(4);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(subject)).hasSize(1);
            }
        }

        @Test
        @DisplayName("every header item survives at its own map width, untrimmed and unpadded")
        void everyHeaderItemSurvivesAtItsOwnWidth() {
            String transaction = "CCLI";
            String title = "T".repeat(CardListResponse.SCREEN_TITLE_LENGTH);
            String date = "08/02/26";
            String program = "COCRDLIC";
            String time = "14:30:0 ";

            CardListResponse subject = new CardListResponse(transaction, title, date, program,
                    title, time, null, null, null, null, null, null, null, false, null, "CRDSID",
                    null, null);

            assertThat(subject.transactionName()).isEqualTo(transaction).hasSize(4);
            assertThat(subject.title01()).isEqualTo(title).hasSize(40);
            assertThat(subject.title02()).isEqualTo(title).hasSize(40);
            assertThat(subject.currentDate()).isEqualTo(date).hasSize(8);
            assertThat(subject.programName()).isEqualTo(program).hasSize(8);
            assertThat(subject.currentTime()).isEqualTo(time).hasSize(8).endsWith(" ");
            assertThat(subject.focusScreenFieldId())
                    .as("a map field name only: no coordinate, no attribute byte and none of the"
                            + " numeric control values the legacy used to achieve the effect")
                    .isEqualTo("CRDSID")
                    .hasSizeLessThanOrEqualTo(CardListResponse.SCREEN_FIELD_ID_LENGTH);
        }
    }

    @Nested
    @DisplayName("identifiers are text, so leading zeros survive")
    class IdentifiersAreText {

        @Test
        @DisplayName("an account identifier opening with zeros comes back with them intact")
        void anAccountIdentifierKeepsItsLeadingZeros() throws JsonProcessingException {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("S", "00000000001", ROW_CARD, "Y");

            assertThat(subject.accountNumber())
                    .as("a numeric type would discard them and eleven digits opening with a zero"
                            + " would come back a different value")
                    .isEqualTo("00000000001")
                    .isNotEqualTo("1")
                    .hasSize(CardListResponse.ACCOUNT_NUMBER_LENGTH);
            assertThat(payloadOf(subject).get("accountNumber").asText()).isEqualTo("00000000001");
            assertThat(payloadOf(subject).get("accountNumber").isTextual())
                    .as("text on the wire as well, never a JSON number")
                    .isTrue();
        }

        @Test
        @DisplayName("a card number opening with zeros comes back with them intact")
        void aCardNumberKeepsItsLeadingZeros() throws JsonProcessingException {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("S", ROW_ACCOUNT, "0000000000000001", "Y");

            assertThat(subject.cardNumber())
                    .isEqualTo("0000000000000001")
                    .isNotEqualTo("1")
                    .hasSize(CardListResponse.CARD_NUMBER_LENGTH);
            assertThat(payloadOf(subject).get("cardNumber").isTextual()).isTrue();
        }

        @Test
        @DisplayName("both echoed filters keep their leading zeros too")
        void bothEchoedFiltersKeepTheirLeadingZeros() throws JsonProcessingException {
            CardListResponse subject = new CardListResponse(null, null, null, null, null, null,
                    null, "00000000011", "0000000000000009", null, null, null, null, false, null,
                    null, null, null);
            JsonNode payload = payloadOf(subject);

            assertThat(subject.accountFilter()).isEqualTo("00000000011");
            assertThat(subject.cardNumberFilter()).isEqualTo("0000000000000009");
            assertThat(payload.get("accountFilter").isTextual()).isTrue();
            assertThat(payload.get("cardNumberFilter").isTextual()).isTrue();
        }

        @Test
        @DisplayName("a card number crosses in full, neither obscured nor shortened")
        void aCardNumberCrossesInFull() throws JsonProcessingException {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Y");

            assertThat(payloadOf(subject).get("cardNumber").asText())
                    .as("the legacy screen displays it in full and applies no field-level"
                            + " protection to it, so altering the value here would break the screen"
                            + " contract this type reproduces. The gap is recorded in the decision"
                            + " log rather than closed by unrequested change")
                    .isEqualTo(ROW_CARD)
                    .doesNotContain("*");
        }
    }

    @Nested
    @DisplayName("the echoed action code is positional and un-normalised")
    class EchoedActionCode {

        @Test
        @DisplayName("the detail and update codes are echoed exactly as received")
        void theDetailAndUpdateCodesAreEchoedExactly() {
            assertThat(new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Y").selection())
                    .isEqualTo("S");
            assertThat(new CardListResponse.CardListRow("U", ROW_ACCOUNT, ROW_CARD, "Y").selection())
                    .isEqualTo("U");
        }

        @Test
        @DisplayName("a lower-case code is echoed as sent and is neither folded nor substituted")
        void aLowerCaseCodeIsEchoedAsSent() throws JsonProcessingException {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("s", ROW_ACCOUNT, ROW_CARD, "Y");

            assertThat(subject.selection())
                    .as("interpreting the code belongs to the service that reproduces the program;"
                            + " this contract echoes what it was given")
                    .isEqualTo("s")
                    .isNotEqualTo("S");
            assertThat(payloadOf(subject).get("selection").asText()).isEqualTo("s");
        }

        @Test
        @DisplayName("a blank and an absent code are distinct and both survive")
        void aBlankAndAnAbsentCodeAreDistinct() throws JsonProcessingException {
            CardListResponse.CardListRow blank =
                    new CardListResponse.CardListRow(" ", ROW_ACCOUNT, ROW_CARD, "Y");
            CardListResponse.CardListRow untouched =
                    new CardListResponse.CardListRow(null, ROW_ACCOUNT, ROW_CARD, "Y");

            assertThat(blank.selection()).isEqualTo(" ");
            assertThat(payloadOf(blank).get("selection").asText()).isEqualTo(" ");
            assertThat(untouched.selection()).isNull();
            assertThat(keysOf(payloadOf(untouched)))
                    .as("an absent code is omitted rather than rendered as a blank")
                    .doesNotContain("selection")
                    .hasSize(3);
        }

        @Test
        @DisplayName("an empty code survives as an empty value rather than becoming absent")
        void anEmptyCodeSurvivesAsEmpty() {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("", ROW_ACCOUNT, ROW_CARD, "Y");

            assertThat(subject.selection()).isNotNull();
            assertThat(subject.selection()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the card status vocabulary is exactly the pair the estate defines")
    class CardStatusVocabulary {

        @Test
        @DisplayName("declares exactly two constants and no synthetic catch-all")
        void declaresExactlyTwoConstants() {
            assertThat(CardStatus.values())
                    .as("the yes/no active flag, and nothing else")
                    .hasSize(2)
                    .containsExactly(CardStatus.Y, CardStatus.N);
            assertThat(CardStatus.values())
                    .extracting(Enum::name)
                    .as("no UNKNOWN, NONE, OTHER, INVALID or DEFAULT constant absorbs a miss: such"
                            + " a constant would be a value the estate never produces")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("the active predicate answers true only for the active constant")
        void theActivePredicateAnswersTrueOnlyForActive() {
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
            assertThat(CardStatus.Y.getCode()).isEqualTo('Y');
            assertThat(CardStatus.N.getCode()).isEqualTo('N');
        }

        @Test
        @DisplayName("the validation-flag states are not statuses and appear as no constant")
        void theValidationFlagStatesAreNotStatuses() {
            assertThat(CardStatus.values())
                    .extracting(CardStatus::getCode)
                    .as("the not-OK and blank states belong to the field-error surface, which"
                            + " exposes them as per-field MISSING and INVALID; neither is ever"
                            + " stored in the status field or written to the card record")
                    .containsExactly('Y', 'N')
                    .doesNotContain('0', 'B');
            assertThat(CardStatus.fromCode('0')).isEmpty();
            assertThat(CardStatus.fromCode('B')).isEmpty();
        }

        @Test
        @DisplayName("an unrecognised code yields an empty result without throwing")
        void anUnrecognisedCodeYieldsAnEmptyResult() {
            assertThatNoException().isThrownBy(() -> CardStatus.fromCode('Q'));

            assertThat(CardStatus.fromCode('Q'))
                    .as("a file-sourced value outside the pair flowed through the legacy system"
                            + " untouched, and the replacing column carries no check constraint")
                    .isEmpty();
            assertThat(CardStatus.fromCode(' ')).isEmpty();
            assertThat(CardStatus.fromCode((String) null)).isEmpty();
            assertThat(CardStatus.fromCode("")).isEmpty();
            assertThat(CardStatus.fromCode("YY")).isEmpty();
            assertThat(CardStatus.fromCode("Y ")).isEmpty();
        }

        @Test
        @DisplayName("the lookup applies no case fold, so a lower-case code does not resolve")
        void theLookupAppliesNoCaseFold() {
            assertThat(CardStatus.fromCode('y')).isEmpty();
            assertThat(CardStatus.fromCode("y")).isEmpty();
            assertThat(CardStatus.fromCode("n")).isEmpty();
            assertThat(CardStatus.fromCode("Y")).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode("N")).contains(CardStatus.N);
        }

        @Test
        @DisplayName("a row's raw status resolves through the lookup and composes to a boolean")
        void aRowsRawStatusResolvesThroughTheLookup() {
            Optional<CardStatus> active =
                    CardStatus.fromCode(row(1).cardStatus());
            Optional<CardStatus> unmapped = CardStatus.fromCode(
                    new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Q").cardStatus());

            assertThat(active).contains(CardStatus.Y);
            assertThat(active.map(CardStatus::isActive).orElse(false)).isTrue();
            assertThat(unmapped).isEmpty();
            assertThat(unmapped.map(CardStatus::isActive).orElse(false))
                    .as("an absent or unrecognised code composes to not-active")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the paging contract is carried, not re-implemented")
    class PagingIsCarried {

        @Test
        @DisplayName("both opaque browse keys come back unchanged, leading zeros included")
        void bothBrowseKeysComeBackUnchanged() {
            String zeroLed = "0000000000000001";
            PageMetadata metadata = PageMetadata.forward(
                    PageMetadata.CARD_LIST_PAGE_SIZE, zeroLed, NEXT_KEY, true, false,
                    PAGE_INDICATOR);

            CardListResponse subject = fullyPopulated(rows(1), List.of());

            assertThat(metadata.previousCursorKey())
                    .as("a browse key is opaque: never parsed, ordered, padded, trimmed or re-cased")
                    .isEqualTo(zeroLed)
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            assertThat(metadata.nextCursorKey()).isEqualTo(NEXT_KEY);
            assertThat(subject.pageMetadata().previousCursorKey()).isEqualTo(PREVIOUS_KEY);
            assertThat(subject.pageMetadata().nextCursorKey()).isEqualTo(NEXT_KEY);
        }

        @Test
        @DisplayName("the direction distinguishes a forward page from a backward one")
        void theDirectionDistinguishesForwardFromBackward() {
            PageMetadata forward = PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE,
                    PREVIOUS_KEY, NEXT_KEY, true, false, PAGE_INDICATOR);
            PageMetadata backward = PageMetadata.backward(PageMetadata.CARD_LIST_PAGE_SIZE,
                    PREVIOUS_KEY, NEXT_KEY, false, true, "002");

            assertThat(forward.direction()).isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(backward.direction())
                    .as("a backward page is read descending and the service reverses it so the page"
                            + " still presents ascending, reproducing the bottom-row-upward fill:"
                            + " the direction is behavioural rather than decorative")
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(PageMetadata.PagingDirection.values()).hasSize(2);
        }

        @Test
        @DisplayName("a backward page carries its rows in the order it was handed them")
        void aBackwardPageCarriesItsRowsAsHandedOver() {
            List<CardListResponse.CardListRow> asPresented = List.of(row(5), row(6), row(7));
            PageMetadata backward = PageMetadata.backward(PageMetadata.CARD_LIST_PAGE_SIZE,
                    PREVIOUS_KEY, NEXT_KEY, false, true, "002");

            CardListResponse subject = new CardListResponse("CCLI", null, null, null, null, null,
                    "002", null, null, asPresented, null, null, null, false, backward, null, null,
                    null);

            assertThat(subject.rows()).containsExactly(row(5), row(6), row(7));
            assertThat(subject.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("the two end-of-browse indicators are independent of one another")
        void theTwoEndOfBrowseIndicatorsAreIndependent() {
            PageMetadata middle = PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE,
                    PREVIOUS_KEY, NEXT_KEY, true, true, PAGE_INDICATOR);

            assertThat(middle.hasMorePages()).isTrue();
            assertThat(middle.hasPreviousPages())
                    .as("the legacy renders a distinct message at each end of the browse, which is"
                            + " why one flag cannot stand for both")
                    .isTrue();
            assertThat(CardListResponse.MSG_NO_PREVIOUS_PAGES)
                    .isNotEqualTo(CardListResponse.MSG_NO_MORE_PAGES);
        }

        @Test
        @DisplayName("an absent paging component is legitimate on a path with no browse position")
        void anAbsentPagingComponentIsLegitimate() throws JsonProcessingException {
            CardListResponse subject = allAbsent();

            assertThat(subject.pageMetadata()).isNull();
            assertThat(keysOf(payloadOf(subject))).doesNotContain("pageMetadata");
        }
    }

    @Nested
    @DisplayName("the navigation state is carried, not re-implemented")
    class NavigationIsCarried {

        @Test
        @DisplayName("every identifier it carries comes back unchanged, leading zeros included")
        void everyIdentifierComesBackUnchanged() {
            NavigationContext carried = navigation();
            CardListResponse subject = fullPage();

            assertThat(subject.navigationContext().lastMap()).isEqualTo("CCRDLIA");
            assertThat(subject.navigationContext().lastMapset()).isEqualTo("COCRDLI");
            assertThat(subject.navigationContext().accountId())
                    .isEqualTo(ACCOUNT_FILTER)
                    .isEqualTo("00000000011");
            assertThat(subject.navigationContext().customerId()).isEqualTo("000000011");
            assertThat(subject.navigationContext().cardNumber()).isEqualTo(CARD_FILTER);
            assertThat(subject.navigationContext().userId()).isEqualTo("ADMINUSR");
            assertThat(subject.navigationContext()).isEqualTo(carried);
        }

        @Test
        @DisplayName("it records the screen transition and the re-entry state as supplied")
        void itRecordsTheTransitionAndReEntryState() {
            NavigationContext carried = fullPage().navigationContext();

            assertThat(carried.fromTransactionId()).isEqualTo("CCLI");
            assertThat(carried.fromProgram()).isEqualTo("COCRDLIC");
            assertThat(carried.toTransactionId()).isEqualTo("CCDL");
            assertThat(carried.toProgram()).isEqualTo("COCRDSLC");
            assertThat(carried.reEntry())
                    .as("the field-level error decoration the legacy applies fires only on"
                            + " re-submission, so the re-entry state is a fact the screen needs")
                    .isTrue();
            assertThat(carried.firstEntry()).isFalse();
        }

        @Test
        @DisplayName("the wholly empty state is carried as readily as a populated one")
        void theWhollyEmptyStateIsCarried() throws JsonProcessingException {
            CardListResponse subject = new CardListResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false, null, null, null,
                    NavigationContext.empty());

            assertThat(subject.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(subject.navigationContext().firstEntry()).isTrue();
            assertThat(payloadOf(subject).get("navigationContext").isObject()).isTrue();
            assertThat(payloadOf(subject).get("navigationContext").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("an absent navigation state is legitimate and is omitted from the payload")
        void anAbsentNavigationStateIsLegitimate() throws JsonProcessingException {
            assertThat(allAbsent().navigationContext()).isNull();
            assertThat(keysOf(payloadOf(allAbsent()))).doesNotContain("navigationContext");
        }
    }

    @Nested
    @DisplayName("the route is declarative data, never dispatch")
    class RouteIsData {

        @Test
        @DisplayName("carries the next route opaquely, as a value the client follows")
        void carriesTheNextRouteOpaquely() throws JsonProcessingException {
            CardListResponse subject = fullPage();

            assertThat(subject.nextRoute())
                    .as("the detail and update selections dispatched program to program on the"
                            + " mainframe; here the client drives the next call and no forwarding"
                            + " happens on the server")
                    .isEqualTo("/api/cards");
            assertThat(payloadOf(subject).get("nextRoute").isTextual()).isTrue();
        }

        @Test
        @DisplayName("accepts any route value without interpreting or validating it")
        void acceptsAnyRouteValueWithoutInterpretingIt() {
            assertThat(carryingRoute("/api/cards/detail").nextRoute())
                    .isEqualTo("/api/cards/detail");
            assertThat(carryingRoute("/api/cards/update").nextRoute())
                    .isEqualTo("/api/cards/update");
            assertThat(carryingRoute("anything the navigation service decides").nextRoute())
                    .as("the route vocabulary belongs to the navigation service, so no table,"
                            + " enumeration or registry of routes appears on this contract")
                    .isEqualTo("anything the navigation service decides");
            assertThat(carryingRoute(null).nextRoute()).isNull();
        }

        private CardListResponse carryingRoute(String route) {
            return new CardListResponse(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, false, null, null, route, null);
        }
    }

    @Nested
    @DisplayName("the declared bounds report and never reject a legitimate screen state")
    class DeclaredBoundsOnlyReport {

        @Test
        @DisplayName("a wholly absent response raises not one violation")
        void aWhollyAbsentResponseRaisesNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                assertThat(validator.validate(allAbsent()))
                        .as("the legacy program renders a partly filled map on almost every path,"
                                + " so no component may be required to be present. The ordered,"
                                + " first-error-wins cascade belongs to the service layer")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a wholly absent row raises not one violation either")
        void aWhollyAbsentRowRaisesNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(new CardListResponse.CardListRow(null, null, null, null)))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a fully populated response raises not one violation")
        void aFullyPopulatedResponseRaisesNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(fullPage())).isEmpty();
            }
        }

        @Test
        @DisplayName("the two fields the legacy leaves unedited attract no constraint of their own")
        void theUneditedFieldsAttractNoConstraint() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                assertThat(validator.validate(new CardListResponse.CardListRow(
                        "!", ROW_ACCOUNT, ROW_CARD, "?")))
                        .as("no pattern, digit, minimum or maximum constraint fires on a value the"
                                + " legacy accepted; only an over-long value is ever reported")
                        .isEmpty();
                assertThat(validator.validate(carrying("a mixed-case operator message")))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("only an over-long value is reported, and the value itself stays untouched")
        void onlyAnOverLongValueIsReported() {
            CardListResponse.CardListRow tooWide = new CardListResponse.CardListRow(
                    "SU", ROW_ACCOUNT + "9", ROW_CARD + "9", "YN");

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(tooWide)).hasSize(4);
            }
            assertThat(tooWide.selection()).isEqualTo("SU");
            assertThat(tooWide.accountNumber()).hasSize(12);
            assertThat(tooWide.cardNumber()).hasSize(17);
            assertThat(tooWide.cardStatus()).isEqualTo("YN");
        }
    }

    @Nested
    @DisplayName("the wire form behaves as the module's declared settings require")
    class WireForm {

        @Test
        @DisplayName("omits every absent component rather than writing it as null")
        void omitsEveryAbsentComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(allAbsent());

            assertThat(keysOf(payload))
                    .as("the two collections are present because an absent one became empty, and"
                            + " the error flag is present because it is never absent")
                    .containsExactly("rows", "selectionErrorFlags", "generalError");
            assertThat(payload.get("rows").isEmpty()).isTrue();
            assertThat(payload.get("selectionErrorFlags").isEmpty()).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("tolerates a property it does not declare rather than refusing the body")
        void toleratesAnUndeclaredProperty() throws JsonProcessingException {
            String body = "{\"transactionName\":\"CCLI\",\"displayedPageNumber\":\"001\","
                    + "\"aPropertyThisContractNeverDeclared\":\"ignored\"}";

            CardListResponse bound = declaredSettingsMapper().readValue(body,
                    CardListResponse.class);

            assertThat(bound.transactionName()).isEqualTo("CCLI");
            assertThat(bound.displayedPageNumber()).isEqualTo(PAGE_INDICATOR);
            assertThat(bound.rows()).isEmpty();
            assertThat(bound.selectionErrorFlags()).isEmpty();
        }

        @Test
        @DisplayName("survives a full round trip through the wire unchanged")
        void survivesAFullRoundTrip() throws JsonProcessingException {
            ObjectMapper mapper = declaredSettingsMapper();
            CardListResponse original = fullyPopulated(rows(2), flags(2));

            CardListResponse returned = mapper.readValue(
                    mapper.writeValueAsString(original), CardListResponse.class);

            assertThat(returned)
                    .as("every component compares equal after the round trip, which is what makes"
                            + " the payload a faithful statement of the contract")
                    .isEqualTo(original);
            assertThat(returned.rows()).containsExactlyElementsOf(original.rows());
            assertThat(returned.selectionErrorFlags()).containsExactly(true, false);
            assertThat(returned.pageMetadata()).isEqualTo(original.pageMetadata());
            assertThat(returned.navigationContext()).isEqualTo(original.navigationContext());
        }

        @Test
        @DisplayName("publishes the paging direction by name rather than by position")
        void publishesThePagingDirectionByName() throws JsonProcessingException {
            JsonNode paging = payloadOf(fullPage()).get("pageMetadata");

            assertThat(paging.get("direction").asText())
                    .as("a bare number would bind an enumerated component by ordinal, and no"
                            + " attention identifier the legacy branched on is an index")
                    .isEqualTo("FORWARD");
            assertThat(paging.get("direction").isTextual()).isTrue();
        }

        @Test
        @DisplayName("publishes each row inside the row collection, in order")
        void publishesEachRowInOrder() throws JsonProcessingException {
            JsonNode rowArray = payloadOf(fullyPopulated(rows(3), List.of())).get("rows");

            assertThat(rowArray.isArray()).isTrue();
            assertThat(rowArray.size()).isEqualTo(3);
            assertThat(rowArray.get(0).get("accountNumber").asText()).isEqualTo("00000000021");
            assertThat(rowArray.get(2).get("accountNumber").asText()).isEqualTo("00000000023");
            assertThat(keysOf(rowArray.get(0)))
                    .containsExactlyElementsOf(ROW_PAYLOAD_KEYS_IN_MAP_ORDER);
        }
    }

    @Nested
    @DisplayName("the diagnostic renderings withhold every identifying value")
    class DiagnosticRenderings {

        @Test
        @DisplayName("the response rendering discloses no identifier by any route")
        void theResponseRenderingDisclosesNoIdentifier() {
            String rendered = fullPage().toString();

            assertThat(rendered).startsWith("CardListResponse[").endsWith("]");
            assertThat(rendered)
                    .as("a full page would otherwise have emitted seven card numbers beside the"
                            + " accounts they belong to, both echoed filters and both browse keys")
                    .doesNotContain(EVERY_IDENTIFYING_VALUE.toArray(new String[0]));
            assertThat(EVERY_IDENTIFYING_VALUE).hasSize(6);
        }

        @Test
        @DisplayName("the response rendering withholds the collection and the paging state whole")
        void theResponseRenderingWithholdsGroupingsWhole() {
            String rendered = fullPage().toString();

            assertThat(rendered)
                    .contains("accountFilter=" + WITHHELD)
                    .contains("cardNumberFilter=" + WITHHELD)
                    .contains("rows=" + WITHHELD)
                    .contains("pageMetadata=" + WITHHELD);
        }

        @Test
        @DisplayName("the response rendering states the row count, which identifies nobody")
        void theResponseRenderingStatesTheRowCount() {
            assertThat(fullPage().toString())
                    .contains("rowCount=" + PageMetadata.CARD_LIST_PAGE_SIZE);
            assertThat(fullyPopulated(rows(2), List.of()).toString()).contains("rowCount=2");
            assertThat(allAbsent().toString()).contains("rowCount=0");
        }

        @Test
        @DisplayName("the response rendering retains the screen furniture and the message slots")
        void theResponseRenderingRetainsScreenFurniture() {
            String rendered = fullPage().toString();

            assertThat(rendered)
                    .contains("transactionName=CCLI")
                    .contains("programName=COCRDLIC")
                    .contains("displayedPageNumber=" + PAGE_INDICATOR)
                    .contains("infoMessage=" + CardListResponse.MSG_ROW_ACTION_PROMPT)
                    .contains("errorMessage=" + CardListResponse.MSG_NO_MORE_RECORDS)
                    .contains("generalError=true")
                    .contains("focusScreenFieldId=CRDSID")
                    .contains("nextRoute=/api/cards");
        }

        @Test
        @DisplayName("the response rendering shows the positional indicator, which names no record")
        void theResponseRenderingShowsThePositionalIndicator() {
            assertThat(fullyPopulated(rows(2), flags(2)).toString())
                    .contains("selectionErrorFlags=[true, false]");
        }

        @Test
        @DisplayName("the row rendering withholds both identifiers and keeps the two codes")
        void theRowRenderingWithholdsBothIdentifiers() {
            String rendered = new CardListResponse.CardListRow("S", ROW_ACCOUNT, ROW_CARD, "Y")
                    .toString();

            assertThat(rendered)
                    .doesNotContain(ROW_ACCOUNT, ROW_CARD)
                    .contains("accountNumber=" + WITHHELD)
                    .contains("cardNumber=" + WITHHELD)
                    .contains("selection=S")
                    .contains("cardStatus=Y");
        }

        @Test
        @DisplayName("withholding changes nothing an accessor returns")
        void withholdingChangesNothingAnAccessorReturns() {
            CardListResponse subject = fullPage();
            CardListResponse.CardListRow subjectRow = subject.rows().get(0);

            assertThat(subject.toString()).doesNotContain(ACCOUNT_FILTER);
            assertThat(subject.accountFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(subject.cardNumberFilter()).isEqualTo(CARD_FILTER);
            assertThat(subject.pageMetadata().nextCursorKey()).isEqualTo(NEXT_KEY);
            assertThat(subjectRow.accountNumber()).isEqualTo("00000000021");
            assertThat(subjectRow.cardNumber()).isEqualTo("4222222222222221");
        }

        @Test
        @DisplayName("withholding changes nothing the wire carries")
        void withholdingChangesNothingTheWireCarries() throws JsonProcessingException {
            CardListResponse subject = fullPage();
            JsonNode payload = payloadOf(subject);

            assertThat(subject.toString()).doesNotContain(CARD_FILTER);
            assertThat(payload.get("accountFilter").asText()).isEqualTo(ACCOUNT_FILTER);
            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_FILTER);
            assertThat(payload.get("rows").get(0).get("cardNumber").asText())
                    .as("the client cannot render the screen without the value, so only the"
                            + " diagnostic channel withholds it")
                    .isEqualTo("4222222222222221");
            assertThat(payload.get("pageMetadata").get("nextCursorKey").asText())
                    .isEqualTo(NEXT_KEY);
        }

        @Test
        @DisplayName("comparison still sees every withheld value, so equality stays correct")
        void comparisonStillSeesEveryWithheldValue() {
            CardListResponse one = fullPage();
            CardListResponse differsOnlyInAWithheldValue = new CardListResponse("CCLI",
                    "AWS Mainframe Modernization", "08/02/26", "COCRDLIC", "CardDemo", "14:30:00",
                    PAGE_INDICATOR, "00000000099", CARD_FILTER,
                    rows(PageMetadata.CARD_LIST_PAGE_SIZE), List.of(),
                    CardListResponse.MSG_ROW_ACTION_PROMPT, CardListResponse.MSG_NO_MORE_RECORDS,
                    true, paging(), "CRDSID", "/api/cards", navigation());

            assertThat(one.toString()).isEqualTo(differsOnlyInAWithheldValue.toString());
            assertThat(one)
                    .as("an in-memory comparison is not a disclosure surface, so equality compares"
                            + " every component even where the rendering withholds it")
                    .isNotEqualTo(differsOnlyInAWithheldValue);
        }
    }
}
