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

import com.carddemo.domain.enums.KeyAction;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CardListRequest}, the inbound contract of legacy CICS transaction
 * {@code CCLI} - the screen that lists the cards belonging to an account and lets the operator mark
 * one row for viewing or for updating.
 *
 * <h2>What kind of test this is</h2>
 *
 * <p>A pure unit test. No application context is started, no container is launched, no connection is
 * opened and nothing is mocked: every instance is built with the canonical constructor and read back
 * through its own accessors. Where the wire form is the subject, a mapper is built locally in this
 * class and configured to the six settings the module declares under {@code spring.jackson} in
 * {@code src/main/resources/application.yml}, so that what is asserted here is the shape those
 * settings produce. Whether a deployed instance actually holds a mapper configured that way is a
 * different claim, proved elsewhere, and no assertion here pretends otherwise.
 *
 * <h2>The nine values an operator can really submit</h2>
 *
 * <p>The symbolic map {@code app/cpy-bms/COCRDLI.CPY} declares far more field families than the
 * program reads. Of all of them, {@code app/cbl/COCRDLIC.cbl} stages exactly nine inbound: the two
 * optional filters at lines 969 and 970, and the seven per-row action marks at lines 972 to 978, one
 * statement per row. A tenth item, the page indicator written outbound at line 667, is carried as a
 * display echo. Everything else the map declares is either written by the screen or is device-level
 * plumbing, and none of it is modelled on a request.
 *
 * <h2>Seven rows, and seven is the shape of the screen</h2>
 *
 * <p>The figure is arithmetic rather than a setting. Lines 249 to 255 of the program declare a
 * 196-character all-rows area and redefine it as a table of seven occurrences whose element is an
 * 11-character account identifier, a 16-character card number and a 1-character status indicator:
 * 28 characters, and 28 multiplied by seven accounts for all 196. It is a screen row count inherited
 * from a 24-by-80 terminal, published once as {@link PageMetadata#CARD_LIST_PAGE_SIZE}, and this
 * suite reads it from there rather than restating it, so the two can never drift apart.
 *
 * <h2>The marks are positional and an unmarked row carries information</h2>
 *
 * <p>The slot index is the row index. The program's error path copies the seven-character mark area
 * and rewrites it in place at lines 1088 to 1093 into a per-row indicator, one character per row, so
 * an unmarked row is not absence of data - it is the statement that that particular row was not
 * marked. Any representation that dropped the empty positions, deduplicated the marks or keyed them
 * by value rather than by position would destroy the alignment the error contract depends on. That
 * is why the request declares seven separately named components rather than a collection, and why
 * {@link CardListRequest#selectionsInRowOrder()} always yields one element per row, empty positions
 * included.
 *
 * <h2>What deliberately is not enforced at this boundary</h2>
 *
 * <p>Two rules that a reader might expect here live in {@code com.carddemo.service.CardListService}
 * instead, and this suite proves their absence rather than their presence.
 *
 * <ul>
 *   <li><strong>At most one row may be marked per page.</strong> The program counts the marks across
 *       the seven slots at lines 1079 to 1082 and rejects the submission at lines 1084 to 1086 when
 *       more than one is present, naming the offending rows. That is a single ordered check spanning
 *       seven components and reporting row indices; a declarative constraint could neither span them
 *       in order nor produce that message. A request carrying two marks therefore reports no
 *       violation here, and the assertion that it does not is deliberate.</li>
 *   <li><strong>The digit-format rules on the two filters.</strong> The program runs an ordered
 *       cascade - the account filter, then the card filter, then the marks, invoked in that order at
 *       lines 985 to 995 - and each stage is gated on no earlier stage having failed. The texts it
 *       emits are {@code ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER} at line 1022 and
 *       {@code CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER} at line 1058, reproduced here
 *       exactly as the program carries them - no space after the comma, and the article reading
 *       {@code A 11} and {@code A 16}. Bean Validation evaluates constraints in an unspecified order
 *       and would report several at once, so the format checks are service checks and this request
 *       accepts absent, empty and malformed filters without objection.</li>
 * </ul>
 *
 * <p>The only declarative constraint on a text component is therefore a maximum width, which
 * restates the physical width of the 3270 field and nothing else. It measures and never alters: no
 * value is trimmed, padded, folded or otherwise rewritten anywhere on this contract, which is why
 * every round-trip assertion below compares character for character.
 *
 * <h2>The six dead protected row items are excluded, and the exclusion is evidence-based</h2>
 *
 * <p>The generated map declares one further one-character item on each of rows two to seven - and
 * conspicuously no counterpart on row one, an asymmetry in the generated map itself. The mapset marks
 * every one of them auto-skip and dark, so no operator can place the cursor in one or see its
 * content, and an exhaustive search of all 1,459 lines of the program returns zero references to any
 * of them. They are dead generated fields. This suite pins their absence structurally, by asserting
 * the exact set of members a fully populated request puts on the wire: any member beyond the thirteen
 * the contract declares fails that assertion, so a future reader cannot "restore a missing field"
 * without the failure naming it.
 *
 * <h2>Three verified source oddities, recorded and deliberately not repaired here</h2>
 *
 * <ul>
 *   <li>The browse walks the base card cluster in card-number order and applies the account filter
 *       <em>after</em> each record is retrieved, in {@code 9500-FILTER-RECORDS} at line 1382, rather
 *       than positioning on an account-keyed path.</li>
 *   <li>Consistently with that, the account path name declared at lines 213 to 217 is referenced
 *       exactly once - by its own declaration - and is otherwise never used.</li>
 *   <li>Line 70 carries the estate's single packed-decimal declaration, on a screen work counter that
 *       is never persisted, which is why this migration path needs a zoned-decimal codec and no
 *       packed-decimal decoder at all.</li>
 * </ul>
 *
 * <p>All three are contract and recorded anomalies rather than defects to correct at this boundary,
 * and none of them becomes behaviour on this request.
 *
 * <h2>Provenance</h2>
 *
 * <p>Every width, line number and count cited above was measured at repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose card-list members carry the upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy estate under
 * {@code app/} is read-only reference: it is cited by member name, width, line number and count only,
 * and no line of legacy source is reproduced here.
 */
@DisplayName("CardListRequest :: the CCLI card-list submission contract")
class CardListRequestTest {

    /**
     * The screen's row count, read from the shared paging contract instead of being restated.
     *
     * <p>Reading it rather than writing {@code 7} is the point: the figure is published in exactly one
     * place, so a suite that hard-coded it could keep passing after the published figure changed.
     */
    private static final int SCREEN_ROWS = PageMetadata.CARD_LIST_PAGE_SIZE;

    /**
     * The thirteen members the contract puts on the wire, in the order the record declares them.
     *
     * <p>Asserting against this exact set is what pins the contract shape without asking the runtime
     * to describe itself: a member the contract does not declare cannot appear, and a member it does
     * declare cannot vanish.
     */
    private static final List<String> CONTRACT_MEMBERS = List.of(
            "accountIdFilter",
            "cardNumberFilter",
            "displayedPageNumber",
            "selection1",
            "selection2",
            "selection3",
            "selection4",
            "selection5",
            "selection6",
            "selection7",
            "pageMetadata",
            "keyAction",
            "navigationContext");

    /** Fictional account identifier at the map's full eleven characters, with leading zeros. */
    private static final String ACCOUNT_FILTER = "00000000011";

    /** Fictional card identifier at the map's full sixteen characters, with leading zeros. */
    private static final String CARD_FILTER = "0000000000000011";

    /** A second fictional card identifier, so a forward and a backward cursor differ. */
    private static final String OTHER_CARD_FILTER = "0000000000000099";

    /**
     * Backward-resume cursor: the card identifier alone, sixteen characters.
     *
     * <p>The card-list program declares a composite work field of a card number followed by an account
     * identifier, but at all four of its repositioning sites only the card-number half is moved into the
     * browse key - the companion move of the account half is commented out - so the key that resumes a
     * card browse is the card number by itself.
     */
    private static final String PREVIOUS_CURSOR = CARD_FILTER;

    /** Forward-resume cursor built on the same shape. */
    private static final String NEXT_CURSOR = OTHER_CARD_FILTER;

    /** The display echo at the map's three characters. */
    private static final String PAGE_ECHO = "002";

    /** The mark that selects a row for viewing. */
    private static final String VIEW_MARK = "S";

    /** The mark that selects a row for updating. */
    private static final String UPDATE_MARK = "U";

    /** The fixed stand-in the contract renders in place of each regulated component. */
    private static final String REDACTED = "***REDACTED***";

    /**
     * The width constraint's message key rather than its rendered text.
     *
     * <p>A rendered constraint message is resolved against the default locale, so asserting one would
     * make this suite fail under any locale whose bundle differs. The key is locale independent.
     */
    private static final String WIDTH_MESSAGE_KEY = "{jakarta.validation.constraints.Size.message}";

    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    /** Opens the single validator used by every bound assertion in this suite. */
    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the validator opened by {@link #openValidator()}. */
    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    /**
     * Builds echoed navigation state whose every component sits inside its declared width.
     *
     * @return navigation state suitable for a re-entry submission
     */
    private static NavigationContext navigation() {
        return new NavigationContext(
                "CCLI",
                "COCRDLIC",
                "CCLI",
                "COCRDLIC",
                "ADMINUSR",
                "A",
                NavigationContext.ProgramContext.REENTER,
                "000000011",
                "MARY",
                "ANN",
                "SMITH",
                ACCOUNT_FILTER,
                "Y",
                CARD_FILTER,
                "CCRDLIA",
                "COCRDLI");
    }

    /**
     * Builds the inbound half of the paging contract for one browse direction.
     *
     * @param direction the way the browse should walk
     * @return the two resume cursors and that direction
     */
    private static PageMetadata.PageCursorRequest cursor(PageMetadata.PagingDirection direction) {
        return new PageMetadata.PageCursorRequest(PREVIOUS_CURSOR, NEXT_CURSOR, direction);
    }

    /**
     * Builds the submission most assertions here work from: both filters, the display echo, the paging
     * state, the attention key and the echoed navigation state, with the third screen row marked.
     *
     * <p>The other six rows are deliberately left in the three different unmarked states - absent,
     * empty and blank - because those three states must survive separately. That is also why this is
     * not the fixture used to evidence the full member set: an absent component is omitted from the
     * wire form, so {@link #everyMemberPresent()} exists for that purpose alone.
     *
     * @return a submission carrying every kind of content this contract accepts
     */
    private static CardListRequest populated() {
        return new CardListRequest(
                ACCOUNT_FILTER,
                CARD_FILTER,
                PAGE_ECHO,
                null,
                "",
                VIEW_MARK,
                " ",
                null,
                null,
                null,
                cursor(PageMetadata.PagingDirection.FORWARD),
                KeyAction.PFK08,
                navigation());
    }

    /**
     * Builds a submission in which no component is absent, so that every declared member reaches the
     * wire.
     *
     * <p>Distinct from {@link #populated()}, which deliberately leaves four rows absent: because
     * absent members are omitted rather than written as null, only a submission with nothing absent
     * can show the full member set. The six unmarked rows carry an empty mark, which is a legal
     * unmarked state and not the same state as an absent one.
     *
     * @return a submission with all thirteen components supplied
     */
    private static CardListRequest everyMemberPresent() {
        return new CardListRequest(
                ACCOUNT_FILTER,
                CARD_FILTER,
                PAGE_ECHO,
                "",
                "",
                VIEW_MARK,
                "",
                "",
                "",
                "",
                cursor(PageMetadata.PagingDirection.FORWARD),
                KeyAction.PFK08,
                navigation());
    }

    /**
     * Builds the submission a first entry into the screen produces: nothing supplied at all.
     *
     * @return a submission whose every component is absent
     */
    private static CardListRequest firstEntry() {
        return new CardListRequest(null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * Builds a submission carrying the seven row marks and nothing else.
     *
     * @param row1 the mark on screen row one
     * @param row2 the mark on screen row two
     * @param row3 the mark on screen row three
     * @param row4 the mark on screen row four
     * @param row5 the mark on screen row five
     * @param row6 the mark on screen row six
     * @param row7 the mark on screen row seven
     * @return a submission whose only content is the seven marks
     */
    private static CardListRequest marked(String row1, String row2, String row3, String row4,
            String row5, String row6, String row7) {
        return new CardListRequest(null, null, null, row1, row2, row3, row4, row5, row6, row7, null,
                null, null);
    }

    /**
     * Builds a submission whose only marked row is the one named, every other row being absent.
     *
     * <p>The row is placed by naming it rather than by indexing into an array, so nothing here
     * performs position arithmetic of any kind: each component is either the mark or absent.
     *
     * @param row  the one-based screen row to mark
     * @param mark the mark to place on it
     * @return a submission marked at that row alone
     */
    private static CardListRequest markedAt(int row, String mark) {
        return marked(
                (row == 1) ? mark : null,
                (row == 2) ? mark : null,
                (row == 3) ? mark : null,
                (row == 4) ? mark : null,
                (row == 5) ? mark : null,
                (row == 6) ? mark : null,
                (row == 7) ? mark : null);
    }

    /**
     * Builds a submission carrying the two filters and the display echo and nothing else.
     *
     * @param accountFilter the account filter, which may be absent
     * @param cardFilter    the card filter, which may be absent
     * @param pageEcho      the display echo, which may be absent
     * @return a submission whose only content is those three values
     */
    private static CardListRequest filtered(String accountFilter, String cardFilter,
            String pageEcho) {
        return new CardListRequest(accountFilter, cardFilter, pageEcho, null, null, null, null, null,
                null, null, null, null, null);
    }

    /**
     * Builds a mapper configured to the settings the module declares for the running application.
     *
     * <p>Absent members are omitted rather than written as null, temporal values cross as text rather
     * than as epoch numbers, an unknown incoming member is tolerated so that a client can echo a whole
     * response back as its next request, and decimals are written plainly. The value-and-content form
     * of the inclusion setter is used because the single-argument form is deprecated in the pinned
     * databind release and this module compiles with warnings promoted to errors.
     *
     * @return a mapper matching the module's declared settings
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

    /**
     * Serialises a submission and reads the result back as a tree.
     *
     * @param request the submission to serialise
     * @return the tree the module's declared settings produce
     * @throws JsonProcessingException if the submission cannot be written or read back
     */
    private static JsonNode payloadOf(CardListRequest request) throws JsonProcessingException {
        ObjectMapper mapper = declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Lists the member names a tree carries, in the order they were written.
     *
     * @param payload the tree to read
     * @return the member names present
     */
    private static List<String> memberNames(JsonNode payload) {
        List<String> names = new ArrayList<>();
        payload.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Validates a submission with the shared validator.
     *
     * @param request the submission to validate
     * @return the violations reported, which is usually none
     */
    private static Set<ConstraintViolation<CardListRequest>> violations(CardListRequest request) {
        return validator.validate(request);
    }

    /**
     * Returns a text one character wider than a declared width, so a width bound is provoked.
     *
     * @param width the declared width
     * @return a text of {@code width + 1} identical characters
     */
    private static String oneCharacterTooWide(int width) {
        return "9".repeat(width + 1);
    }

    /**
     * Returns a text exactly as wide as a declared width, so a width bound is not provoked.
     *
     * @param width the declared width
     * @return a text of exactly {@code width} identical characters
     */
    private static String exactlyAsWide(int width) {
        return "9".repeat(width);
    }

    @Nested
    @DisplayName("the values an operator submits cross unchanged")
    class SubmittedValuesCrossUnchanged {

        @Test
        @DisplayName("every one of the thirteen accessors returns exactly what it was constructed "
                + "with")
        void everyAccessorReturnsWhatItWasConstructedWith() {
            CardListRequest request = populated();

            assertThat(request.accountIdFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_FILTER);
            assertThat(request.displayedPageNumber()).isEqualTo(PAGE_ECHO);
            assertThat(request.selection1()).isNull();
            assertThat(request.selection2()).isEmpty();
            assertThat(request.selection3()).isEqualTo(VIEW_MARK);
            assertThat(request.selection4()).isEqualTo(" ");
            assertThat(request.selection5()).isNull();
            assertThat(request.selection6()).isNull();
            assertThat(request.selection7()).isNull();
            assertThat(request.pageMetadata())
                    .isEqualTo(cursor(PageMetadata.PagingDirection.FORWARD));
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(request.navigationContext()).isEqualTo(navigation());
        }

        @Test
        @DisplayName("a leading zero survives on both filters, because an identifier that starts "
                + "with one is a different identifier without it")
        void leadingZerosSurviveOnBothFilters() {
            CardListRequest request = filtered("00000000001", "0000000000000001", "001");

            assertThat(request.accountIdFilter()).isEqualTo("00000000001").hasSize(11);
            assertThat(request.cardNumberFilter()).isEqualTo("0000000000000001").hasSize(16);
            assertThat(request.displayedPageNumber()).isEqualTo("001");
        }

        @Test
        @DisplayName("a filter narrower than its field is never widened to fit it")
        void aNarrowFilterIsNeverWidened() {
            CardListRequest request = filtered("1", "1", "1");

            assertThat(request.accountIdFilter()).isEqualTo("1").hasSize(1);
            assertThat(request.cardNumberFilter()).isEqualTo("1").hasSize(1);
            assertThat(request.displayedPageNumber()).isEqualTo("1").hasSize(1);
        }

        @Test
        @DisplayName("trailing spaces are carried as data, because the legacy field is fixed width "
                + "and the browse compares character for character")
        void trailingSpacesAreCarriedAsData() {
            String paddedAccount = "11         ";
            String paddedCard = "11              ";

            CardListRequest request = filtered(paddedAccount, paddedCard, "1  ");

            assertThat(request.accountIdFilter())
                    .isEqualTo(paddedAccount)
                    .hasSize(CardListRequest.ACCOUNT_ID_FILTER_LENGTH);
            assertThat(request.cardNumberFilter())
                    .isEqualTo(paddedCard)
                    .hasSize(CardListRequest.CARD_NUMBER_FILTER_LENGTH);
            assertThat(request.displayedPageNumber())
                    .isEqualTo("1  ")
                    .hasSize(CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("both filters cross the wire as text, so no leading zero is lost to a numeric "
                + "form")
        void bothFiltersCrossTheWireAsText() throws JsonProcessingException {
            JsonNode payload = payloadOf(filtered("00000000001", "0000000000000001", "001"));

            assertThat(payload.get("accountIdFilter").isTextual()).isTrue();
            assertThat(payload.get("cardNumberFilter").isTextual()).isTrue();
            assertThat(payload.get("displayedPageNumber").isTextual()).isTrue();
            assertThat(payload.get("accountIdFilter").asText()).isEqualTo("00000000001");
            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("the four published widths are the widths the symbolic map declares")
        void publishedWidthsAreTheMapWidths() {
            assertThat(CardListRequest.ACCOUNT_ID_FILTER_LENGTH).isEqualTo(11);
            assertThat(CardListRequest.CARD_NUMBER_FILTER_LENGTH).isEqualTo(16);
            assertThat(CardListRequest.SELECTION_LENGTH).isEqualTo(1);
            assertThat(CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("this screen's indicator is three characters wide; the transaction-list and "
                            + "user-list screens carry a differently named indicator of eight, and "
                            + "neither width may be normalised onto the other")
                    .isEqualTo(3)
                    .isNotEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("the seven row marks are positional and empty positions survive")
    class RowMarksArePositional {

        @Test
        @DisplayName("a mark on the third row reads back at the third position, with every other "
                + "position left as it was")
        void aMarkOnTheThirdRowReadsBackAtTheThirdPosition() {
            CardListRequest request = markedAt(3, VIEW_MARK);

            assertThat(request.selection3()).isEqualTo(VIEW_MARK);
            assertThat(request.selection1()).isNull();
            assertThat(request.selection2()).isNull();
            assertThat(request.selection4()).isNull();
            assertThat(request.selection5()).isNull();
            assertThat(request.selection6()).isNull();
            assertThat(request.selection7()).isNull();
            assertThat(request.selectionsInRowOrder())
                    .as("the list index is the row index, so the empty positions cannot be dropped")
                    .containsExactly(null, null, VIEW_MARK, null, null, null, null);
        }

        @Test
        @DisplayName("each of the seven rows in turn carries its mark at its own position and "
                + "nowhere else")
        void eachRowInTurnCarriesItsMarkAtItsOwnPosition() {
            for (int row = 1; row <= SCREEN_ROWS; row++) {
                List<String> inRowOrder = markedAt(row, UPDATE_MARK).selectionsInRowOrder();

                assertThat(inRowOrder)
                        .as("row %d", row)
                        .hasSize(SCREEN_ROWS)
                        .containsOnlyOnce(UPDATE_MARK);
                // The subtraction converts a one-based screen row to a zero-based list position. It
                // is index bookkeeping only: nothing here slices a fixed-width image or computes a
                // record offset.
                assertThat(inRowOrder.indexOf(UPDATE_MARK))
                        .as("row %d keeps its own position", row)
                        .isEqualTo(row - 1);
            }
        }

        @Test
        @DisplayName("three different unmarked states - absent, empty and blank - are each preserved "
                + "as themselves rather than folded together")
        void theThreeUnmarkedStatesArePreservedSeparately() {
            CardListRequest request = marked(null, "", " ", null, "", " ", VIEW_MARK);

            assertThat(request.selectionsInRowOrder())
                    .containsExactly(null, "", " ", null, "", " ", VIEW_MARK);
        }

        @Test
        @DisplayName("a submission with no row marked at all reports nothing, which is the state of "
                + "a plain paging keystroke")
        void aSubmissionWithNoRowMarkedReportsNothing() {
            CardListRequest request = marked("", "", "", "", "", "", "");

            assertThat(violations(request)).isEmpty();
            assertThat(request.selectionsInRowOrder())
                    .hasSize(SCREEN_ROWS)
                    .containsOnly("");
        }

        @Test
        @DisplayName("the view mark and the update mark are both carried unchanged, and neither is "
                + "interpreted here")
        void bothMarksAreCarriedUnchanged() {
            assertThat(markedAt(1, VIEW_MARK).selection1()).isEqualTo("S");
            assertThat(markedAt(1, UPDATE_MARK).selection1()).isEqualTo("U");
            assertThat(violations(markedAt(1, VIEW_MARK))).isEmpty();
            assertThat(violations(markedAt(1, UPDATE_MARK))).isEmpty();
        }

        @Test
        @DisplayName("a lower-case mark is carried exactly as typed, because folding it here would "
                + "accept a submission the legacy screen reports back")
        void aLowerCaseMarkIsCarriedExactlyAsTyped() {
            CardListRequest request = markedAt(5, "s");

            assertThat(request.selection5()).isEqualTo("s").isNotEqualTo(VIEW_MARK);
            assertThat(violations(request))
                    .as("the boundary does not judge the character; the service does")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unrecognised mark is carried unchanged, because the legacy screen reports "
                + "it back rather than refusing the submission")
        void anUnrecognisedMarkIsCarriedUnchanged() {
            CardListRequest request = markedAt(7, "Z");

            assertThat(request.selection7()).isEqualTo("Z");
            assertThat(violations(request)).isEmpty();
        }

        @Test
        @DisplayName("two marked rows report nothing here, because counting them and naming the "
                + "offending rows is the card-list service's ordered check")
        void twoMarkedRowsReportNothingHere() {
            // The program counts the marks at lines 1079 to 1082 and rejects more than one at lines
            // 1084 to 1086, naming the rows. That check spans all seven components in one ordered
            // pass and reports row indices, so it is owned by com.carddemo.service.CardListService
            // and deliberately absent here: a declarative constraint could neither order the cascade
            // nor produce that message.
            CardListRequest twoMarks = marked(VIEW_MARK, null, UPDATE_MARK, null, null, null, null);

            assertThat(violations(twoMarks)).isEmpty();
            assertThat(twoMarks.selectionsInRowOrder())
                    .containsExactly(VIEW_MARK, null, UPDATE_MARK, null, null, null, null);
        }

        @Test
        @DisplayName("all seven rows marked at once still reports nothing here, for the same reason")
        void allSevenRowsMarkedStillReportsNothingHere() {
            CardListRequest everyRow = marked(VIEW_MARK, VIEW_MARK, VIEW_MARK, VIEW_MARK, VIEW_MARK,
                    VIEW_MARK, VIEW_MARK);

            assertThat(violations(everyRow)).isEmpty();
            assertThat(everyRow.selectionsInRowOrder()).hasSize(SCREEN_ROWS).containsOnly(VIEW_MARK);
        }

        @Test
        @DisplayName("the row-order view cannot be written through, so no caller can rewrite a mark "
                + "or reorder the rows")
        void theRowOrderViewCannotBeWrittenThrough() {
            List<String> inRowOrder = populated().selectionsInRowOrder();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> inRowOrder.add(VIEW_MARK));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> inRowOrder.set(0, VIEW_MARK));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> inRowOrder.remove(0));
        }

        @Test
        @DisplayName("the row-order view admits an absent position, which an immutable-copy factory "
                + "would have rejected")
        void theRowOrderViewAdmitsAnAbsentPosition() {
            List<String> withAnUnmarkedRow = markedAt(1, VIEW_MARK).selectionsInRowOrder();

            assertThat(withAnUnmarkedRow).hasSize(SCREEN_ROWS).containsNull();
            assertThatNullPointerException()
                    .as("the platform's immutable-copy factory refuses an absent element, which is "
                            + "why the projection is not built with one: an unmarked row has to "
                            + "survive")
                    .isThrownBy(() -> List.copyOf(withAnUnmarkedRow));
        }

        @Test
        @DisplayName("with every row marked the same view copies cleanly, so the tolerance of an "
                + "absent position is the only reason a copy factory was avoided")
        void withEveryRowMarkedTheViewCopiesCleanly() {
            List<String> everyRowMarked = marked(VIEW_MARK, UPDATE_MARK, VIEW_MARK, UPDATE_MARK,
                    VIEW_MARK, UPDATE_MARK, VIEW_MARK).selectionsInRowOrder();

            assertThat(List.copyOf(everyRowMarked)).isEqualTo(everyRowMarked);
        }

        @Test
        @DisplayName("two views of one submission are equal yet independent, so the submission "
                + "retains no collection a caller could reach")
        void twoViewsAreEqualYetIndependent() {
            CardListRequest request = populated();

            List<String> first = request.selectionsInRowOrder();
            List<String> second = request.selectionsInRowOrder();

            assertThat(first).isEqualTo(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("the view agrees position by position with the seven accessors read on their "
                + "own, so it introduces no ordering of its own")
        void theViewAgreesWithTheSevenAccessors() {
            CardListRequest request = populated();

            assertThat(request.selectionsInRowOrder()).containsExactly(
                    request.selection1(),
                    request.selection2(),
                    request.selection3(),
                    request.selection4(),
                    request.selection5(),
                    request.selection6(),
                    request.selection7());
        }
    }


    @Nested
    @DisplayName("both filters are optional and neither is judged at this boundary")
    class FiltersAreOptionalAndUnjudged {

        @Test
        @DisplayName("absent filters report nothing, because a blank filter lists every card")
        void absentFiltersReportNothing() {
            assertThat(violations(filtered(null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("empty filters report nothing, and are not turned into absent ones")
        void emptyFiltersReportNothing() {
            CardListRequest request = filtered("", "", "");

            assertThat(violations(request)).isEmpty();
            assertThat(request.accountIdFilter()).isEmpty();
            assertThat(request.cardNumberFilter()).isEmpty();
            assertThat(request.displayedPageNumber()).isEmpty();
        }

        @Test
        @DisplayName("an account filter that is not eleven digits reports nothing here, because the "
                + "digit rule is a message-bearing stage of the service cascade")
        void aNonNumericAccountFilterReportsNothingHere() {
            assertThat(violations(filtered("ABCDEFGHIJK", null, null))).isEmpty();
            assertThat(violations(filtered("1", null, null))).isEmpty();
            assertThat(violations(filtered("   ", null, null))).isEmpty();
        }

        @Test
        @DisplayName("a card filter that is not sixteen digits reports nothing here either")
        void aNonNumericCardFilterReportsNothingHere() {
            assertThat(violations(filtered(null, "ABCDEFGHIJKLMNOP", null))).isEmpty();
            assertThat(violations(filtered(null, "1", null))).isEmpty();
            assertThat(violations(filtered(null, "    ", null))).isEmpty();
        }

        @Test
        @DisplayName("a submission with nothing supplied at all reports nothing, which is exactly a "
                + "first entry into the screen")
        void aSubmissionWithNothingSuppliedReportsNothing() {
            CardListRequest request = firstEntry();

            assertThat(violations(request)).isEmpty();
            assertThat(request.accountIdFilter()).isNull();
            assertThat(request.cardNumberFilter()).isNull();
            assertThat(request.displayedPageNumber()).isNull();
            assertThat(request.pageMetadata()).isNull();
            assertThat(request.keyAction()).isNull();
            assertThat(request.navigationContext()).isNull();
            assertThat(request.selectionsInRowOrder()).hasSize(SCREEN_ROWS).containsOnlyNulls();
        }

        @Test
        @DisplayName("a submission whose every text component is empty reports nothing, so no "
                + "presence rule fires anywhere")
        void aSubmissionWhoseEveryTextComponentIsEmptyReportsNothing() {
            CardListRequest allEmpty = new CardListRequest("", "", "", "", "", "", "", "", "", "",
                    null, null, null);

            assertThat(violations(allEmpty))
                    .as("no presence, pattern, digit or range rule is declared on any component")
                    .isEmpty();
        }

        @Test
        @DisplayName("a submission sitting exactly on every declared width reports nothing")
        void aSubmissionExactlyOnEveryWidthReportsNothing() {
            CardListRequest atWidth = new CardListRequest(
                    exactlyAsWide(CardListRequest.ACCOUNT_ID_FILTER_LENGTH),
                    exactlyAsWide(CardListRequest.CARD_NUMBER_FILTER_LENGTH),
                    exactlyAsWide(CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH),
                    exactlyAsWide(CardListRequest.SELECTION_LENGTH),
                    exactlyAsWide(CardListRequest.SELECTION_LENGTH),
                    exactlyAsWide(CardListRequest.SELECTION_LENGTH),
                    exactlyAsWide(CardListRequest.SELECTION_LENGTH),
                    exactlyAsWide(CardListRequest.SELECTION_LENGTH),
                    exactlyAsWide(CardListRequest.SELECTION_LENGTH),
                    exactlyAsWide(CardListRequest.SELECTION_LENGTH),
                    cursor(PageMetadata.PagingDirection.BACKWARD),
                    KeyAction.PFK07,
                    navigation());

            assertThat(violations(atWidth)).isEmpty();
        }

        @Test
        @DisplayName("the account filter reports its own width and nothing else when one character "
                + "too wide")
        void theAccountFilterReportsItsOwnWidth() {
            String tooWide = oneCharacterTooWide(CardListRequest.ACCOUNT_ID_FILTER_LENGTH);

            Set<ConstraintViolation<CardListRequest>> reported =
                    violations(filtered(tooWide, null, null));

            assertThat(reported).hasSize(1);
            ConstraintViolation<CardListRequest> only = reported.iterator().next();
            assertThat(only.getPropertyPath()).hasToString("accountIdFilter");
            assertThat(only.getMessageTemplate()).isEqualTo(WIDTH_MESSAGE_KEY);
            assertThat(only.getInvalidValue())
                    .as("the bound measures the value and hands it back untouched")
                    .isEqualTo(tooWide);
        }

        @Test
        @DisplayName("the card filter reports its own width and nothing else when one character too "
                + "wide")
        void theCardFilterReportsItsOwnWidth() {
            String tooWide = oneCharacterTooWide(CardListRequest.CARD_NUMBER_FILTER_LENGTH);

            Set<ConstraintViolation<CardListRequest>> reported =
                    violations(filtered(null, tooWide, null));

            assertThat(reported).hasSize(1);
            ConstraintViolation<CardListRequest> only = reported.iterator().next();
            assertThat(only.getPropertyPath()).hasToString("cardNumberFilter");
            assertThat(only.getMessageTemplate()).isEqualTo(WIDTH_MESSAGE_KEY);
        }

        @Test
        @DisplayName("the display echo reports its own three-character width when one character too "
                + "wide")
        void theDisplayEchoReportsItsOwnWidth() {
            String tooWide = oneCharacterTooWide(CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH);

            Set<ConstraintViolation<CardListRequest>> reported =
                    violations(filtered(null, null, tooWide));

            assertThat(reported).hasSize(1);
            ConstraintViolation<CardListRequest> only = reported.iterator().next();
            assertThat(only.getPropertyPath()).hasToString("displayedPageNumber");
            assertThat(only.getMessageTemplate()).isEqualTo(WIDTH_MESSAGE_KEY);
        }

        @Test
        @DisplayName("each row mark reports its own single-character width, one row at a time")
        void eachRowMarkReportsItsOwnWidth() {
            String twoCharacters = oneCharacterTooWide(CardListRequest.SELECTION_LENGTH);

            for (int row = 1; row <= SCREEN_ROWS; row++) {
                Set<ConstraintViolation<CardListRequest>> reported =
                        violations(markedAt(row, twoCharacters));

                assertThat(reported).as("row %d", row).hasSize(1);
                ConstraintViolation<CardListRequest> only = reported.iterator().next();
                assertThat(only.getPropertyPath()).hasToString("selection" + row);
                assertThat(only.getMessageTemplate()).isEqualTo(WIDTH_MESSAGE_KEY);
            }
        }
    }

    @Nested
    @DisplayName("the paging state is carried, never re-implemented")
    class PagingStateIsCarried {

        @Test
        @DisplayName("the submission carries the paging contract's own inbound shape, which is "
                + "narrower than the outbound one")
        void theSubmissionCarriesThePagingContractsInboundShape() {
            // The inbound shape offers a direction and the two cursors the previous turn handed back.
            // Everything else the outbound contract reports - the screen row count and the two
            // availability flags among them - is a conclusion the browse reaches, so a caller cannot
            // assert it here.
            CardListRequest request = populated();

            assertThat(request.pageMetadata())
                    .isEqualTo(new PageMetadata.PageCursorRequest(PREVIOUS_CURSOR, NEXT_CURSOR,
                            PageMetadata.PagingDirection.FORWARD));
        }

        @Test
        @DisplayName("both resume cursors round-trip unchanged, leading zeros included")
        void bothResumeCursorsRoundTripUnchanged() {
            PageMetadata.PageCursorRequest carried = populated().pageMetadata();

            assertThat(carried.previousCursorKey())
                    .isEqualTo(PREVIOUS_CURSOR)
                    .startsWith("0")
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            assertThat(carried.nextCursorKey())
                    .isEqualTo(NEXT_CURSOR)
                    .startsWith("0")
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH);
        }

        @Test
        @DisplayName("a cursor keeps its trailing spaces, because a legacy key is space padded and "
                + "the padding is part of the key")
        void aCursorKeepsItsTrailingSpaces() {
            String paddedKey = "11              ";
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null,
                    new PageMetadata.PageCursorRequest(paddedKey, null,
                            PageMetadata.PagingDirection.FORWARD),
                    null, null);

            assertThat(request.pageMetadata().previousCursorKey())
                    .isEqualTo(paddedKey)
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            assertThat(violations(request)).isEmpty();
        }

        @Test
        @DisplayName("forward and backward are distinguished, so the direction is behavioural rather "
                + "than decorative")
        void forwardAndBackwardAreDistinguished() {
            // A backward walk fills the screen rows from the bottom upward before the service
            // reverses them for display, so the two directions are not interchangeable.
            CardListRequest walkingForward = new CardListRequest(null, null, null, null, null, null,
                    null, null, null, null, cursor(PageMetadata.PagingDirection.FORWARD), null,
                    null);
            CardListRequest walkingBackward = new CardListRequest(null, null, null, null, null, null,
                    null, null, null, null, cursor(PageMetadata.PagingDirection.BACKWARD), null,
                    null);

            assertThat(walkingForward.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(walkingBackward.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(walkingForward).isNotEqualTo(walkingBackward);
        }

        @Test
        @DisplayName("the direction vocabulary is exactly the two legacy browse verbs, with no "
                + "catch-all and no default")
        void theDirectionVocabularyIsExactlyTheTwoBrowseVerbs() {
            assertThat(PageMetadata.PagingDirection.values())
                    .containsExactly(PageMetadata.PagingDirection.FORWARD,
                            PageMetadata.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("an absent paging state is legal, because a first entry has no cursor to hand "
                + "back and none is invented")
        void anAbsentPagingStateIsLegal() {
            CardListRequest request = filtered(ACCOUNT_FILTER, null, null);

            assertThat(request.pageMetadata()).isNull();
            assertThat(violations(request)).isEmpty();
        }

        @Test
        @DisplayName("an absent direction inside a supplied paging state is legal, because the first "
                + "entry arrives on a key that is not a paging key")
        void anAbsentDirectionInsideASuppliedPagingStateIsLegal() {
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null,
                    new PageMetadata.PageCursorRequest(PREVIOUS_CURSOR, NEXT_CURSOR, null), null,
                    null);

            assertThat(request.pageMetadata().direction()).isNull();
            assertThat(violations(request)).isEmpty();
        }

        @Test
        @DisplayName("an over-wide cursor inside the paging state is reported through the cascade, "
                + "naming the nested component")
        void anOverWideCursorIsReportedThroughTheCascade() {
            PageMetadata.PageCursorRequest tooWide = new PageMetadata.PageCursorRequest(
                    oneCharacterTooWide(PageMetadata.CURSOR_KEY_MAX_LENGTH), null,
                    PageMetadata.PagingDirection.BACKWARD);
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, tooWide, KeyAction.PFK07, null);

            Set<ConstraintViolation<CardListRequest>> reported = violations(request);

            assertThat(reported).hasSize(1);
            assertThat(reported.iterator().next().getPropertyPath())
                    .as("without the cascade the nested width would be stated on paper and enforced "
                            + "nowhere")
                    .hasToString("pageMetadata.previousCursorKey");
        }

        @Test
        @DisplayName("the screen's row count is read from the shared paging contract, and the "
                + "row-order view is exactly that long")
        void theRowCountIsReadFromTheSharedContract() {
            assertThat(SCREEN_ROWS)
                    .as("the shape of the card-list screen, published once and read here")
                    .isEqualTo(7);
            assertThat(populated().selectionsInRowOrder()).hasSize(SCREEN_ROWS);
        }
    }


    @Nested
    @DisplayName("the attention key is carried, and it has no catch-all")
    class AttentionKeyHasNoCatchAll {

        @Test
        @DisplayName("the vocabulary is exactly the sixteen condition names the shared work area "
                + "declares")
        void theVocabularyIsExactlySixteenNames() {
            assertThat(KeyAction.values()).hasSize(16);
        }

        @Test
        @DisplayName("every identifier is exactly five characters wide, because it originates in a "
                + "fixed-width work area")
        void everyIdentifierIsFiveCharactersWide() {
            for (KeyAction action : KeyAction.values()) {
                assertThat(action.getAid()).as("%s", action).hasSize(5);
            }
        }

        @Test
        @DisplayName("the two program-attention identifiers keep the padding that is part of their "
                + "value, and the lookup refuses the unpadded form")
        void theTwoProgramAttentionIdentifiersKeepTheirPadding() {
            assertThat(KeyAction.PA1.getAid()).isEqualTo("PA1  ").hasSize(5);
            assertThat(KeyAction.PA2.getAid()).isEqualTo("PA2  ").hasSize(5);
            assertThat(KeyAction.fromAid("PA1"))
                    .as("a lookup keyed on a shortened form would silently fail to resolve these two")
                    .isEmpty();
            assertThat(KeyAction.fromAid("PA2")).isEmpty();
        }

        @Test
        @DisplayName("every identifier resolves back to its own constant, padding included")
        void everyIdentifierResolvesBackToItsOwnConstant() {
            for (KeyAction action : KeyAction.values()) {
                Optional<KeyAction> resolved = KeyAction.fromAid(action.getAid());

                assertThat(resolved).as("%s", action).contains(action);
            }
        }

        @Test
        @DisplayName("an unrecognised identifier yields an empty result rather than an exception, "
                + "reproducing a mapping of 28 ordered clauses with no catch-all")
        void anUnrecognisedIdentifierYieldsAnEmptyResult() {
            assertThatNoException().isThrownBy(() -> KeyAction.fromAid("ZZZZZ"));
            assertThatNoException().isThrownBy(() -> KeyAction.fromAid(""));
            assertThat(KeyAction.fromAid("ZZZZZ")).isEmpty();
            assertThat(KeyAction.fromAid("")).isEmpty();
        }

        @Test
        @DisplayName("an absent identifier yields an empty result rather than an exception")
        void anAbsentIdentifierYieldsAnEmptyResult() {
            assertThatNoException().isThrownBy(() -> KeyAction.fromAid(null));
            assertThat(KeyAction.fromAid(null)).isEmpty();
        }

        @Test
        @DisplayName("the lookup does not fold case, because the work-area value is a byte pattern "
                + "rather than a word")
        void theLookupDoesNotFoldCase() {
            assertThat(KeyAction.fromAid("pfk01")).isEmpty();
            assertThat(KeyAction.fromAid("enter")).isEmpty();
            assertThat(KeyAction.fromAid("Enter")).isEmpty();
        }

        @Test
        @DisplayName("there is no substitute constant of any kind, because the legacy mapping leaves "
                + "an unrecognised key's stored value intact")
        void thereIsNoSubstituteConstant() {
            List<String> declared = new ArrayList<>();
            for (KeyAction action : KeyAction.values()) {
                declared.add(action.name());
            }

            assertThat(declared).containsExactly("ENTER", "CLEAR", "PA1", "PA2", "PFK01", "PFK02",
                    "PFK03", "PFK04", "PFK05", "PFK06", "PFK07", "PFK08", "PFK09", "PFK10", "PFK11",
                    "PFK12");
            assertThat(declared)
                    .as("absence is modelled by an empty result, never by a manufactured state")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("the higher function keys have no constants, because folding them onto the "
                + "lower twelve belongs to the utility-layer translator")
        void theHigherFunctionKeysHaveNoConstants() {
            List<String> declared = new ArrayList<>();
            for (KeyAction action : KeyAction.values()) {
                declared.add(action.name());
            }
            List<String> higherKeys = new ArrayList<>();
            for (int key = 13; key <= 24; key++) {
                higherKeys.add("PFK" + key);
            }

            assertThat(declared).doesNotContainAnyElementsOf(higherKeys);
            for (String higherKey : higherKeys) {
                assertThat(KeyAction.fromAid(higherKey)).as("%s", higherKey).isEmpty();
            }
        }

        @Test
        @DisplayName("the twelve function keys are told apart from the enter, clear and "
                + "program-attention keys")
        void theFunctionKeysAreToldApart() {
            assertThat(KeyAction.PFK08.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK01.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK12.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.ENTER.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.CLEAR.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA1.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA2.isProgramFunctionKey()).isFalse();
        }

        @Test
        @DisplayName("the submission carries the key it was given and tolerates its absence")
        void theSubmissionCarriesTheKeyAndToleratesItsAbsence() {
            CardListRequest withKey = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, KeyAction.PFK07, null);

            assertThat(withKey.keyAction()).isEqualTo(KeyAction.PFK07);
            assertThat(violations(withKey)).isEmpty();
            assertThat(firstEntry().keyAction()).isNull();
            assertThat(violations(firstEntry())).isEmpty();
        }
    }

    @Nested
    @DisplayName("the echoed navigation state is carried, never re-implemented")
    class NavigationStateIsCarried {

        @Test
        @DisplayName("every identifier it carries round-trips unchanged, leading zeros included")
        void everyIdentifierRoundTripsUnchanged() {
            NavigationContext echoed = new NavigationContext("CCLI", "COCRDLIC", "CCLI", "COCRDLIC",
                    "TESTUSR1", "U", NavigationContext.ProgramContext.ENTER, "000000011", "MARY",
                    "ANN", "SMITH", "00000000001", "Y", "0000000000000001", "CCRDLIA", "COCRDLI");

            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, KeyAction.ENTER, echoed);

            assertThat(request.navigationContext()).isEqualTo(echoed);
            assertThat(request.navigationContext().accountId())
                    .isEqualTo("00000000001")
                    .hasSize(NavigationContext.ACCOUNT_ID_LENGTH);
            assertThat(request.navigationContext().customerId()).isEqualTo("000000011");
            assertThat(request.navigationContext().cardNumber()).isEqualTo("0000000000000001");
            assertThat(request.navigationContext().userId()).isEqualTo("TESTUSR1");
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(violations(request)).isEmpty();
        }

        @Test
        @DisplayName("an absent navigation state is legal, because a first entry has none to echo")
        void anAbsentNavigationStateIsLegal() {
            assertThat(firstEntry().navigationContext()).isNull();
            assertThat(violations(firstEntry())).isEmpty();
        }

        @Test
        @DisplayName("an over-wide value inside the navigation state is reported through the cascade, "
                + "naming the nested component")
        void anOverWideNavigationValueIsReportedThroughTheCascade() {
            NavigationContext tooWide = new NavigationContext("CCLIX", "COCRDLIC", "CCLI",
                    "COCRDLIC", "ADMINUSR", "A", NavigationContext.ProgramContext.REENTER,
                    "000000011", "MARY", "ANN", "SMITH", ACCOUNT_FILTER, "Y", CARD_FILTER, "CCRDLIA",
                    "COCRDLI");
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, KeyAction.ENTER, tooWide);

            Set<ConstraintViolation<CardListRequest>> reported = violations(request);

            assertThat(reported).hasSize(1);
            assertThat(reported.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("it crosses the wire as one nested member rather than being flattened into this "
                + "contract")
        void itCrossesTheWireAsOneNestedMember() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("navigationContext").isObject()).isTrue();
            assertThat(payload.get("navigationContext").get("userId").asText())
                    .isEqualTo("ADMINUSR");
            assertThat(memberNames(payload))
                    .as("no navigation identifier is restated as a member of this contract")
                    .doesNotContain("userId", "accountId", "cardNumber", "customerId");
        }
    }

    @Nested
    @DisplayName("the wire form carries the thirteen contract members and nothing else")
    class WireFormCarriesTheContractMembers {

        @Test
        @DisplayName("a fully populated submission writes exactly the thirteen declared members")
        void aFullyPopulatedSubmissionWritesExactlyThirteenMembers() throws JsonProcessingException {
            // This is also what pins the exclusion of the six dead protected one-character row items
            // the generated map declares on rows two to seven - there is deliberately no counterpart
            // on row one - which an exhaustive search of the program's 1,459 lines shows it never
            // reads. Any member beyond these thirteen fails here and is named in the failure, so the
            // dead fields cannot be "restored" unnoticed.
            List<String> written = memberNames(payloadOf(everyMemberPresent()));

            assertThat(written)
                    .hasSize(13)
                    .containsExactlyInAnyOrderElementsOf(CONTRACT_MEMBERS);
        }

        @Test
        @DisplayName("no screen furniture and no device-level artefact is a member of this contract")
        void noScreenFurnitureIsAMemberOfThisContract() throws JsonProcessingException {
            List<String> written = memberNames(payloadOf(everyMemberPresent()));

            assertThat(written)
                    .as("titles, the transaction name, the program name, the clock values and the "
                            + "two message lines belong to the response, and the generated length, "
                            + "attribute and cursor items are device-level artefacts")
                    .doesNotContain("transactionName", "title01", "title02", "programName",
                            "currentDate", "currentTime", "infoMessage", "errorMessage",
                            "cursorPosition");
        }

        @Test
        @DisplayName("an absent member is omitted rather than written as null, so absence stays "
                + "distinguishable from emptiness")
        void anAbsentMemberIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(firstEntry());

            assertThat(payload.isObject()).isTrue();
            assertThat(memberNames(payload)).isEmpty();
        }

        @Test
        @DisplayName("an empty row mark is written while an absent one is omitted, which is the "
                + "difference the positional contract depends on")
        void anEmptyRowMarkIsWrittenWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(marked(null, "", VIEW_MARK, null, null, null, null));

            assertThat(memberNames(payload)).containsExactlyInAnyOrder("selection2", "selection3");
            assertThat(payload.get("selection2").asText()).isEmpty();
            assertThat(payload.get("selection3").asText()).isEqualTo(VIEW_MARK);
        }

        @Test
        @DisplayName("an unknown incoming member is tolerated, which is what lets a client echo a "
                + "whole response back as its next submission")
        void anUnknownIncomingMemberIsTolerated() throws JsonProcessingException {
            String body = """
                    {"accountIdFilter":"00000000011","selection3":"S",\
                    "cardStatus1":"Y","rows":[{"cardNumber":"0000000000000011"}]}""";

            CardListRequest bound = declaredSettingsMapper().readValue(body, CardListRequest.class);

            assertThat(bound.accountIdFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(bound.selection3()).isEqualTo(VIEW_MARK);
            assertThat(bound.selection1()).isNull();
        }

        @Test
        @DisplayName("the display echo is written outbound but discarded inbound, because the "
                + "program only ever writes it")
        void theDisplayEchoIsWrittenOutboundButDiscardedInbound() throws JsonProcessingException {
            assertThat(payloadOf(populated()).get("displayedPageNumber").asText())
                    .isEqualTo(PAGE_ECHO);

            CardListRequest bound = declaredSettingsMapper().readValue(
                    "{\"displayedPageNumber\":\"999\",\"accountIdFilter\":\"00000000011\"}",
                    CardListRequest.class);

            assertThat(bound.displayedPageNumber())
                    .as("the retained resume cursors are the only navigation authority")
                    .isNull();
            assertThat(bound.accountIdFilter())
                    .as("positive control: an ordinary filter still binds")
                    .isEqualTo(ACCOUNT_FILTER);
        }

        @Test
        @DisplayName("the attention key crosses as its own name and the paging direction as its own, "
                + "so neither is a bare number")
        void theKeyAndDirectionCrossAsNames() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("keyAction").asText()).isEqualTo("PFK08");
            assertThat(payload.get("pageMetadata").get("direction").asText()).isEqualTo("FORWARD");
        }

        @Test
        @DisplayName("a submission survives a full round trip unchanged apart from the echo the "
                + "contract refuses inbound")
        void aSubmissionSurvivesAFullRoundTrip() throws JsonProcessingException {
            ObjectMapper mapper = declaredSettingsMapper();

            CardListRequest returned = mapper.readValue(mapper.writeValueAsString(populated()),
                    CardListRequest.class);

            CardListRequest expected = new CardListRequest(ACCOUNT_FILTER, CARD_FILTER, null, null,
                    "", VIEW_MARK, " ", null, null, null,
                    cursor(PageMetadata.PagingDirection.FORWARD), KeyAction.PFK08, navigation());
            assertThat(returned).isEqualTo(expected);
            assertThat(returned.selectionsInRowOrder())
                    .containsExactly(null, "", VIEW_MARK, " ", null, null, null);
        }
    }

    @Nested
    @DisplayName("the rendering withholds regulated values while the wire keeps them")
    class RenderingWithholdsRegulatedValues {

        /**
         * Builds a submission with no echoed navigation state, so that a rendering assertion is about
         * this contract's own output rather than about the nested contract's.
         *
         * @param accountFilter the account filter to carry
         * @param cardFilter    the card filter to carry
         * @param resumeCursor  the paging state to carry
         * @return a submission whose rendering is entirely its own
         */
        private CardListRequest withoutNavigation(String accountFilter, String cardFilter,
                PageMetadata.PageCursorRequest resumeCursor) {
            return new CardListRequest(accountFilter, cardFilter, PAGE_ECHO, null, "", VIEW_MARK,
                    " ", null, null, null, resumeCursor, KeyAction.PFK08, null);
        }

        @Test
        @DisplayName("neither filter and neither resume cursor appears, and each withheld component "
                + "is named with the placeholder")
        void neitherFilterNorCursorAppears() {
            String rendered = withoutNavigation(ACCOUNT_FILTER, CARD_FILTER,
                    cursor(PageMetadata.PagingDirection.FORWARD)).toString();

            assertThat(rendered).doesNotContain(ACCOUNT_FILTER, CARD_FILTER, PREVIOUS_CURSOR,
                    NEXT_CURSOR);
            assertThat(rendered).contains(
                    "accountIdFilter=" + REDACTED,
                    "cardNumberFilter=" + REDACTED,
                    "pageMetadata=" + REDACTED);
        }

        @Test
        @DisplayName("the screen-interaction state is retained, because a keystroke against a row "
                + "identifies nobody")
        void theScreenInteractionStateIsRetained() {
            String rendered = withoutNavigation(ACCOUNT_FILTER, CARD_FILTER,
                    cursor(PageMetadata.PagingDirection.FORWARD)).toString();

            assertThat(rendered).contains(
                    "displayedPageNumber=" + PAGE_ECHO,
                    "selection3=" + VIEW_MARK,
                    "keyAction=PFK08");
            assertThat(rendered).startsWith("CardListRequest[").endsWith("]");
        }

        @Test
        @DisplayName("two submissions differing only in the withheld values render identically, so "
                + "no fragment of either survives")
        void twoSubmissionsDifferingOnlyInWithheldValuesRenderIdentically() {
            String first = withoutNavigation(ACCOUNT_FILTER, CARD_FILTER,
                    cursor(PageMetadata.PagingDirection.FORWARD)).toString();
            String second = withoutNavigation("99999999999", "9999999999999999",
                    new PageMetadata.PageCursorRequest("9".repeat(27), null,
                            PageMetadata.PagingDirection.FORWARD)).toString();

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("an absent regulated value still renders as the placeholder, so absence is not "
                + "disclosed either")
        void anAbsentRegulatedValueStillRendersAsThePlaceholder() {
            String rendered = withoutNavigation(null, null, null).toString();

            assertThat(rendered).contains(
                    "accountIdFilter=" + REDACTED,
                    "cardNumberFilter=" + REDACTED,
                    "pageMetadata=" + REDACTED);
        }

        @Test
        @DisplayName("rendering a submission changes nothing an accessor returns and nothing on the "
                + "wire")
        void renderingChangesNothingCarried() throws JsonProcessingException {
            CardListRequest request = populated();

            assertThat(request.toString()).contains(REDACTED);

            assertThat(request.accountIdFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_FILTER);
            assertThat(request.pageMetadata())
                    .isEqualTo(cursor(PageMetadata.PagingDirection.FORWARD));

            JsonNode payload = payloadOf(request);
            assertThat(payload.get("accountIdFilter").asText()).isEqualTo(ACCOUNT_FILTER);
            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_FILTER);
            assertThat(payload.get("pageMetadata").get("previousCursorKey").asText())
                    .isEqualTo(PREVIOUS_CURSOR);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("a submission with nothing supplied renders without failing")
        void aSubmissionWithNothingSuppliedRendersWithoutFailing() {
            assertThatNoException().isThrownBy(() -> firstEntry().toString());
            assertThat(firstEntry().toString()).startsWith("CardListRequest[").endsWith("]");
        }

        @Test
        @DisplayName("two submissions built from the same values are equal and agree on their hash, "
                + "and one differing row mark makes them unequal")
        void equalValuesCompareEqualAndOneDifferingMarkDoesNot() {
            CardListRequest first = populated();
            CardListRequest second = populated();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(markedAt(3, VIEW_MARK));
            assertThat(markedAt(3, VIEW_MARK)).isNotEqualTo(markedAt(4, VIEW_MARK));
            assertThat(markedAt(3, VIEW_MARK)).isNotEqualTo(markedAt(3, UPDATE_MARK));
        }
    }

}
