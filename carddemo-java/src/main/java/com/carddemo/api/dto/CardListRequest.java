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
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable card-list request contract for legacy CICS transaction {@code CCLI}, derived from
 * symbolic map {@code app/cpy-bms/COCRDLI.CPY}, mapset {@code app/bms/COCRDLI.bms} and program
 * {@code app/cbl/COCRDLIC.cbl} - 1,459 lines carrying 42 measured procedure paragraphs, three more
 * than the 39 recorded in the migration plan. The screen lists the cards belonging to an account,
 * optionally narrowed by an account filter and a card filter, and lets the operator mark one row
 * for viewing or for updating.
 *
 * <p><strong>What the operator can actually submit.</strong> The map declares 45 measured input
 * field families. The migration plan records 44; the measured figure is reported here alongside it
 * rather than silently adopted, and the discrepancy is one field family, accounted for below. The
 * families are six screen-metadata items (transaction name, both title lines, current date,
 * program name, current time), two message items, the page indicator, the two filters, four items
 * per screen row across seven rows, and six protected row items that no code reads. Of all of
 * them exactly <strong>nine are genuine operator input</strong>, and that is not inferred from
 * attribute bytes but read off the program: {@code COCRDLIC} lines 969 and 970 stage the two
 * filters, and lines 972 to 978 stage the seven per-row action codes, one statement per row. No
 * other map item is read inbound anywhere in the program. A tenth item, the page indicator, is
 * carried here as a display echo and is discussed under paging below.
 *
 * <p><strong>Response-only items are absent by design.</strong> The six screen-metadata items and
 * the two message items - a 45-character informational line and a 78-character error line - belong
 * on the corresponding response contract, as do the three display items each row carries: the
 * account identifier, the card number and the card status. Those three are values the screen
 * <em>writes</em> into each row, so modelling them here would invite a client to send back data the
 * program never reads.
 *
 * <p><strong>Six protected row items are excluded outright, and the exclusion is evidence-based.</strong>
 * The generated map declares a one-character item named {@code CRDSTP2} through {@code CRDSTP7} on
 * rows two to seven - and conspicuously <em>no</em> {@code CRDSTP1}, an asymmetry in the generated
 * map itself, since row one moves straight from its action code to its account-identifier item. The
 * mapset marks every one of them auto-skip and dark, so no operator can ever place the cursor in one
 * or see its content, and an exhaustive search of all 1,459 lines of {@code COCRDLIC} returns
 * <strong>zero</strong> references to any of them. They are dead generated fields. Modelling them,
 * whether individually or as a symmetrical seventh-and-first pair, would fabricate a contract the
 * legacy system does not have, so no component here corresponds to any of them.
 *
 * <p><strong>No 3270 plumbing crosses this boundary.</strong> Every map field family expands into a
 * length item, a flag item with its attribute redefinition and a fill item alongside the data item,
 * and the input group opens with a twelve-byte terminal-buffer filler. Those are generated
 * device-level artefacts, not application data: none of them appears here, no map coordinate or
 * terminal attribute appears here, and no cursor position, colour, highlight or marker byte appears
 * here.
 *
 * <p><strong>The seven action codes are positional, and empty positions must survive.</strong> Each
 * screen row carries one character in which the operator marks that row. The program stages them
 * into seven fixed slots at lines 972 to 978, and the slot index <em>is</em> the row index: the
 * error path at lines 1088 to 1093 - copying the flag area at line 1088, then rewriting it in place
 * at lines 1090 to 1093 - turns the seven-character flag area into a per-row indicator, one
 * character per row, which is a positional map from row to error state. A blank row therefore
 * carries information - it says "row four was not marked" - and any representation that dropped
 * blanks, deduplicated the codes or keyed them by value rather than by position would destroy the
 * alignment the error contract depends on. They are consequently modelled as seven separately named
 * components rather than as a collection, which makes compaction structurally impossible, and
 * {@link #selectionsInRowOrder()} exposes them in row order for a consumer that needs to walk the
 * rows.
 *
 * <p><strong>At most one row may be marked per page.</strong> Lines 1079 to 1082 tally the marks
 * across the seven slots and lines 1084 to 1086 reject the submission when more than one is
 * present, and the rejection is expected to name the offending rows - which is exactly why the
 * positional indicator is built. That rule is a service-layer check, not a declarative one: it spans
 * seven components at once, it must report row indices, and it runs inside an ordered cascade.
 *
 * <p><strong>Nothing about an action code is interpreted here.</strong> The two meaningful values
 * select a row for viewing and for updating respectively, and any other non-blank value is a
 * reportable error rather than a parse failure. This contract therefore declares no action
 * enumeration, no set of accepted values, no pattern, no case folding, no trimming, no tally and no
 * indicator construction. All of it belongs to the card-list service, which reproduces the legacy
 * evaluation order and emits the legacy message text. A pattern restricting the value here would
 * silently swallow an unrecognised code that the legacy system reports back to the operator.
 *
 * <p><strong>The screen presents seven rows, and seven is the shape of the screen.</strong> The
 * proof is arithmetic and is recorded here because the number is easy to mistake for something
 * adjustable. Lines 250 to 260 of {@code COCRDLIC} declare a 196-character all-rows area and
 * redefine it as a table of seven occurrences at line 255, whose element is an 11-character account
 * identifier, a 16-character card number and a 1-character status indicator: 28 characters, and 28
 * multiplied by seven accounts for all 196. A screen-line counter of the same value corroborates
 * it, as do the seven row families in the map. It is exposed as
 * {@link PageMetadata#CARD_LIST_PAGE_SIZE} and is deliberately <em>not</em> re-declared here, so
 * this contract adds no second statement of the same fact. Changing it would put a different number
 * of rows on a screen, which is a visible behavioural change rather than a setting.
 *
 * <p><strong>Paging is cursor-based and is echoed state, never a computed offset.</strong> The
 * legacy screen is pseudo-conversational: on re-entry the program slices the passed area into two
 * parts at lines 327 to 331 - the shared navigation area it holds in common with every other online
 * program, and its own paging area declared at lines 229 to 248 - and it writes both back out at
 * lines 609 to 612 for the next turn. The client returns what it was given. This request therefore
 * carries those two areas as two components: {@link PageMetadata.PageCursorRequest} for the private
 * paging area, whose retained first and last browse keys are the composite of a 16-character card
 * number followed by an 11-character account identifier, and {@link NavigationContext} for the shared
 * area. Both are absent on a first entry into the screen, exactly as the legacy initialises them, and
 * neither is synthesised here.
 *
 * <p><strong>The inbound half of the paging area is a narrower shape than the outbound half.</strong>
 * What the client legitimately hands back is the browse key to restart from and the direction it is
 * asking for. It does not hand back how many rows fit on the screen, whether a further page exists in
 * either direction, or which page number to print: every one of those is the server's own conclusion
 * about the browse it is about to perform, restated on the way out. Accepting them inbound would let a
 * caller name a page size the screen does not have - the row count is fixed at
 * {@link PageMetadata#CARD_LIST_PAGE_SIZE} by the shape of the map itself - or assert the existence of
 * a page the browse has not found. {@link PageMetadata.PageCursorRequest} therefore carries the two
 * keys and the direction and nothing else, while the full {@link PageMetadata} remains the outbound
 * form on the response contract. The two are separate types rather than one type used in two
 * directions, so neither can drift into the other's role.
 *
 * <p><strong>The inbound page indicator is never read by the program.</strong> The screen-number is
 * written outbound at line 667, and an exhaustive search finds no inbound read of the corresponding
 * input item anywhere in the program. It is carried here because the map declares it and the client
 * echoes it, and it is <strong>non-bindable</strong> for exactly that reason: a value the legacy
 * program never consults is a value this contract must not let a caller use to influence anything.
 * Serialized so that the echo remains visible in the contract, ignored inbound so that the retained
 * browse keys stay the sole authoritative navigation state. It is three characters wide and travels
 * as text, never as a number - the transaction-list and user-list screens use a differently named
 * eight-character indicator, so no width is shared between them.
 *
 * <p><strong>The attention key has no default.</strong> Direction and exit are decided by the key
 * the operator pressed: the program branches on the backward and forward program-function keys and
 * on the exit key at lines 372 to 374, 410, 439 to 501 and 901 to 910. The key travels as
 * {@link KeyAction}, whose 16 constants come from the 16 condition names of the shared work-area
 * copybook. It may be absent: the legacy key-translation copybook contains 28 ordered clauses and
 * zero catch-all clause, so an unrecognised key produces no assignment at all and leaves the
 * previously held value untouched. This contract reproduces that by permitting an absent key and
 * inventing no substitute, and the fold of the higher program-function keys onto the lower twelve
 * belongs to the utility-layer key translator rather than here.
 *
 * <p><strong>Why this request tolerates bad input.</strong> The program runs an ordered validation
 * cascade - the account filter at {@code 2210-EDIT-ACCOUNT}, then the card filter at
 * {@code 2220-EDIT-CARD}, then the action codes at {@code 2250-EDIT-ARRAY}, invoked in that order
 * from {@code 2200-EDIT-INPUTS} at lines 985 to 995 - and each stage is gated on no earlier stage
 * having failed. The exact texts the service emits are
 * {@code ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER} and
 * {@code CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER}, at lines 1022 and 1058
 * respectively; both are reproduced verbatim wherever they are emitted, including the absent space
 * after the comma and the article that reads {@code A 11} and {@code A 16}. Bean Validation
 * evaluates constraints in an unspecified order and could not reproduce that message set, so the
 * digit-format checks are service checks and this request <strong>accepts null, blank and
 * malformed values without rejecting them</strong>. The only declarative constraint used on a text
 * component is a maximum length at each component's measured map width, which restates the
 * physical width of the 3270 field rather than any business rule; it neither trims a value nor
 * disturbs a leading or trailing space, and no format, presence, vocabulary or cross-field
 * constraint appears on any of them.
 *
 * <p><strong>The two nested components carry a cascade, which is not a field edit.</strong> The
 * paging component and the navigation component each declare widths of their own, and a nested
 * constraint is evaluated only when the enclosing component asks for it: without the cascade those
 * widths are stated on paper and enforced nowhere, so an arbitrarily wide browse cursor or echoed
 * identifier crosses this boundary unmeasured on its way to a query. The cascade adds no rule that
 * the nested types do not already declare, expresses no opinion about any value here, and cannot
 * pre-empt a service cascade, because an over-wide nested value is a state no 3270 submission could
 * produce and the estate therefore has no ordered check and no message for it.
 *
 * <p><strong>Both filters are optional and neither has a default.</strong> The screen distinguishes
 * three states per filter - blank, supplied but unusable, and supplied and usable - so a blank
 * filter is a normal submission that lists every card. An absent value here means the operator left
 * the field alone, and the service treats absent and blank alike. No empty string, zero-filled
 * value or sentinel is substituted for a missing filter: fabricating one would turn "no filter" into
 * "filter for this value" and change which rows the screen lists.
 *
 * <p><strong>Regulated values are carried verbatim and disclosed nowhere.</strong> The card filter
 * is a primary account number and the account filter joins directly to a cardholder. Both are
 * transported exactly as received - never masked, never truncated, never partially obscured, never
 * transformed - because the browse compares the filter to the retrieved record character for
 * character and any alteration would change which rows match. Disclosure is prevented on the
 * rendering path instead: {@link #toString()} substitutes a fixed placeholder for both filters and
 * for the paging component, whose browse keys embed the same card number, while
 * {@link NavigationContext} redacts its own identifying values. This file holds no logger and emits
 * nothing.
 *
 * <p><strong>Three verified source oddities are recorded for traceability and change nothing
 * here.</strong> First, line 70 of {@code COCRDLIC} carries the estate's single packed-decimal
 * declaration, on a screen work counter that is never persisted, so no packed-decimal handling
 * arises anywhere in this migration path. Second, the browse walks the base card cluster in card
 * number order and applies the account filter <em>after</em> each record is retrieved, in
 * {@code 9500-FILTER-RECORDS} at line 1382, rather than positioning on an account-keyed path.
 * Third, and consistently with that, the account path name declared at lines 213 to 217 is
 * referenced exactly once - by its own declaration - and is otherwise never used.
 *
 * <p>This request is a {@code record}: immutable, constructed in one step, with no code generator
 * and no annotation processor involved. It depends only on the platform library, the validation
 * API, one shared enumeration and two contracts in its own package; it holds no persistence, web or
 * data-access type, performs no input or output, and contains no business logic, no ordering, no
 * comparison and no arithmetic.
 *
 * <p>Provenance: repository checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy estate under
 * {@code app/} is read-only reference and is cited by name, width and line number only; no legacy
 * source text is reproduced here.
 *
 * @param accountIdFilter optional account filter - map field {@code ACCTSID}, width 11, staged by
 *        {@code COCRDLIC} line 969. Carried as text so that a leading zero survives; an 11-digit
 *        identifier whose leading zeros are part of the value can never be a numeric type here.
 *        May be {@code null}, empty or blank, all of which mean no account narrowing, and no
 *        default is supplied. Transported verbatim and never masked; excluded from
 *        {@link #toString()}.
 * @param cardNumberFilter optional card filter - map field {@code CARDSID}, width 16, staged by
 *        {@code COCRDLIC} line 970. Carried as text for the same reason and compared character for
 *        character by the browse. May be {@code null}, empty or blank, all of which mean no card
 *        narrowing, and no default is supplied. This is a primary account number: transported
 *        verbatim, never masked or truncated, and excluded from {@link #toString()}.
 * @param displayedPageNumber the page indicator the screen shows - map field {@code PAGENO}, width
 *        3. Written outbound at line 667 and <em>never read inbound</em>, so it is a display echo
 *        rather than navigation state; the browse keys on {@code pageMetadata} are authoritative.
 *        <strong>Non-bindable</strong>: present in the contract so the echo is documented, ignored
 *        when a body supplies it, because the program consults no such value and neither may this
 *        boundary. Text, not a number, and may be {@code null} when the client has nothing to echo.
 * @param selection1 action code marked on screen row one - map field {@code CRDSEL1}, width 1,
 *        staged into the first slot at line 972. Position is the row index. {@code null}, empty or
 *        blank all mean the row was not marked, and the value is neither interpreted nor
 *        normalised here.
 * @param selection2 action code marked on screen row two - map field {@code CRDSEL2}, width 1,
 *        staged into the second slot at line 973. Same semantics as row one.
 * @param selection3 action code marked on screen row three - map field {@code CRDSEL3}, width 1,
 *        staged into the third slot at line 974. Same semantics as row one.
 * @param selection4 action code marked on screen row four - map field {@code CRDSEL4}, width 1,
 *        staged into the fourth slot at line 975. Same semantics as row one.
 * @param selection5 action code marked on screen row five - map field {@code CRDSEL5}, width 1,
 *        staged into the fifth slot at line 976. Same semantics as row one.
 * @param selection6 action code marked on screen row six - map field {@code CRDSEL6}, width 1,
 *        staged into the sixth slot at line 977. Same semantics as row one.
 * @param selection7 action code marked on screen row seven - map field {@code CRDSEL7}, width 1,
 *        staged into the seventh and last slot at line 978. Same semantics as row one.
 * @param pageMetadata the paging state the previous turn handed back, reproducing the program's own
 *        paging area declared at lines 229 to 248 and returned at lines 609 to 612. Supplies the two
 *        browse keys to restart from, the direction asked for, and the two retained values the program
 *        reads back rather than recomputes - the page number of {@code WS-CA-SCREEN-NUM} at line 237 and
 *        the next-page flag of {@code WS-CA-NEXT-PAGE-IND} at line 242. It does <em>not</em> supply the
 *        page size or whether a page precedes this one, both of which are server conclusions, which is
 *        why the inbound shape is {@link PageMetadata.PageCursorRequest} rather than the full
 *        {@link PageMetadata}. Validated transitively, so the widths and the page-number form it declares
 *        are actually evaluated. {@code null} on a first entry into the screen, where the legacy
 *        initialises the area instead. Its browse keys embed a card number, so it is excluded from
 *        {@link #toString()}.
 * @param lastPageAlreadyShown the third retained paging value, {@code WS-CA-LAST-PAGE-DISPLAYED} at
 *        lines 239 to 241, echoed from the previous response. It has no home on the shared paging
 *        contract because it is this screen's alone - neither list program that shares that contract
 *        declares an equivalent - and it cannot be derived from this request, because what it records is
 *        whether the operator has <em>already been told</em> they are at the end of the data. The message
 *        rule at lines 905 to 916 reads it to tell a first arrival at the end from a repeated request
 *        past it, so without it that rule collapses to one of its two arms. Absent reads as
 *        {@code false}, which is what a first entry carries.
 * @param keyAction the attention key the operator pressed, which decides direction and exit.
 *        {@code null} when the key mapped to nothing, mirroring a translation that has 28 ordered
 *        clauses and no catch-all and therefore leaves the previously held value untouched. No
 *        substitute is invented.
 * @param navigationContext the shared navigation state echoed from the previous turn, the other
 *        half of the passed area sliced at lines 327 to 331. Validated transitively, so the widths it
 *        declares are actually evaluated. {@code null} on a first entry. It redacts its own
 *        identifying values when stringified.
 * @param rowSnapshotToken the authenticated, server-minted snapshot of the identities displayed in the
 *        seven screen rows, echoed exactly as the previous card-list response returned it. It stands in
 *        for the private row table the legacy program carries across the pseudo-conversation - the
 *        196-character seven-row table of {@code app/cbl/COCRDLIC.cbl} lines 250 to 260, returned behind
 *        the shared area at lines 604 to 619 - which is what lets the two selection transfers at lines
 *        531 to 534 and 559 to 562 hand the next screen the marked row's account number and card number
 *        without reading the file again. <strong>Echo it whenever a selection is marked.</strong> A
 *        marked row cannot be resolved without it and the turn then reports 'INVALID ACTION CODE' and
 *        re-presents the page. It is read for nothing else: paging, filtering and the exit key never
 *        consult it. The value is opaque - it discloses no account number and no card number - and it is
 *        write-only on this contract, so it is never echoed back inside a request rendering.
 */
@Schema(description = "Card-list request for legacy transaction CCLI. THE TWO FILTERS ARE READ ONLY ON "
        + "A RETURNING TURN. The legacy screen receives no map on a fresh entry and therefore reads none "
        + "of its own input fields on one, and this contract reproduces that exactly: accountIdFilter and "
        + "cardNumberFilter are applied only when navigationContext identifies this screen as the one the "
        + "turn is returning from. On a fresh entry both are carried back in the response but are neither "
        + "applied nor validated - not even for digit format - and no error is raised, because the legacy "
        + "program raises none; the first unfiltered page of seven rows is answered. To have a filter "
        + "applied, echo the navigationContext object exactly as the previous card-list response returned "
        + "it. Once a turn is returning, a filter that is not the required number of digits is refused "
        + "with 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER' or 'CARD ID FILTER,IF SUPPLIED "
        + "MUST BE A 16 DIGIT NUMBER'. The per-property descriptions and the NavigationContext schema "
        + "state the same precondition.")
public record CardListRequest(
        @Schema(description = "Optional account filter, map field ACCTSID of app/cpy-bms/COCRDLI.CPY, "
                + "eleven digits. READ ONLY ON A RETURNING TURN: the legacy screen receives no map on "
                + "a fresh entry, so a filter is read only when navigationContext identifies this "
                + "screen as the one the turn is returning from. On any other turn the value is "
                + "carried back in the response but is neither applied nor validated, and no error is "
                + "raised - a full unfiltered page is answered, which is what the legacy program does. "
                + "To have a filter applied, echo the navigationContext from the previous card-list "
                + "response. When it is applied, a value that is not eleven digits is refused with "
                + "'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'. Optional, with no default: "
                + "an absent filter is not the same as a blank one.")
        @Size(max = CardListRequest.ACCOUNT_ID_FILTER_LENGTH) String accountIdFilter,
        @Schema(description = "Optional card filter, map field CARDSID of app/cpy-bms/COCRDLI.CPY, "
                + "sixteen digits. READ ONLY ON A RETURNING TURN, on exactly the condition described "
                + "for the account filter: on any other turn it is carried back unapplied and "
                + "unvalidated with no error raised. When it is applied, a value that is not sixteen "
                + "digits is refused with 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'. "
                + "Optional, with no default.")
        @Size(max = CardListRequest.CARD_NUMBER_FILTER_LENGTH) String cardNumberFilter,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @Size(max = CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH) String displayedPageNumber,
        @Size(max = CardListRequest.SELECTION_LENGTH) String selection1,
        @Size(max = CardListRequest.SELECTION_LENGTH) String selection2,
        @Size(max = CardListRequest.SELECTION_LENGTH) String selection3,
        @Size(max = CardListRequest.SELECTION_LENGTH) String selection4,
        @Size(max = CardListRequest.SELECTION_LENGTH) String selection5,
        @Size(max = CardListRequest.SELECTION_LENGTH) String selection6,
        @Size(max = CardListRequest.SELECTION_LENGTH) String selection7,
        @Valid PageMetadata.PageCursorRequest pageMetadata,
        boolean lastPageAlreadyShown,
        KeyAction keyAction,
        @Schema(description = "Navigation state echoed back from the previous response. It is what "
                + "makes a turn a returning turn, and so it is the precondition for the two filters "
                + "above being read at all: echo the navigationContext from the previous card-list "
                + "response to have a filter applied. Absent or naming another screen, this turn is a "
                + "fresh entry, any filter supplied is carried back without being applied or "
                + "validated, and the first page is answered unfiltered.")
        @Valid NavigationContext navigationContext,
        @Schema(description = "Opaque server-minted snapshot of the identities displayed in the seven "
                + "screen rows, echoed from the previous card-list response. REQUIRED WHENEVER A "
                + "SELECTION IS MARKED: the legacy program keeps its seven displayed rows in its own "
                + "communication area and reads the marked row out of it, so a marked row that arrives "
                + "without this snapshot cannot be resolved and the turn answers 'INVALID ACTION CODE' "
                + "and re-presents the page. Read for nothing else - paging, filtering and the exit key "
                + "never consult it. Carries no account number and no card number in legible form.",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String rowSnapshotToken) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each regulated component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld
     * component - not its length, not a prefix or suffix, not a digest, not a partial mask - can be
     * recovered from a stringified instance. A partial mask was rejected deliberately: a truncated
     * primary account number is still cardholder data.
     *
     * <p>Private because it is a rendering detail and not part of the request contract. It stands in
     * only on the rendering path: every accessor returns its component untouched.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Width in characters of the account filter: 11.
     *
     * <p>A legacy field width, not a restriction invented here. Map field {@code ACCTSID} of
     * {@code app/cpy-bms/COCRDLI.CPY} is 11 characters wide at line 66 and the mapset declares the
     * same width at line 89 of {@code app/bms/COCRDLI.bms}; the shared work-area field the program
     * stages it into is 11 characters wide as well. The bound only reports an over-long value - it
     * never trims, pads or otherwise alters one, and it imposes no digit-format rule, because the
     * format check is a message-bearing stage of the service cascade.
     *
     * <p>The same width appears on the navigation context's account identifier because both denote
     * an account identifier, but the constant is declared here against the map field this screen
     * presents, so a change to one screen cannot silently move the other.
     */
    public static final int ACCOUNT_ID_FILTER_LENGTH = 11;

    /**
     * Width in characters of the card filter: 16.
     *
     * <p>A legacy field width, not a restriction invented here. Map field {@code CARDSID} of
     * {@code app/cpy-bms/COCRDLI.CPY} is 16 characters wide at line 72 and the mapset declares the
     * same width at line 101 of {@code app/bms/COCRDLI.bms}; the shared work-area field the program
     * stages it into is 16 characters wide as well. The bound only reports an over-long value and
     * imposes no digit-format rule, for the reason given on the account filter.
     */
    public static final int CARD_NUMBER_FILTER_LENGTH = 16;

    /**
     * Width in characters of the displayed page indicator: 3.
     *
     * <p>A legacy field width, not a restriction invented here. Map field {@code PAGENO} of
     * {@code app/cpy-bms/COCRDLI.CPY} is 3 characters wide at line 60, and the mapset declares the
     * same width at line 82 of {@code app/bms/COCRDLI.bms}. The field is alphanumeric rather than
     * numeric, which is why the indicator crosses this API as text.
     *
     * <p>Declared here rather than shared with the transaction-list and user-list screens: those
     * screens carry a differently named indicator of a different width, so a shared constant would
     * be one name for two unrelated numbers.
     */
    public static final int DISPLAYED_PAGE_NUMBER_LENGTH = 3;

    /**
     * Width in characters of one row action code: 1.
     *
     * <p>A legacy field width, not a restriction invented here. Each of the map fields
     * {@code CRDSEL1} through {@code CRDSEL7} is a single character - lines 78, 102, 132, 162, 192,
     * 222 and 252 of {@code app/cpy-bms/COCRDLI.CPY}, with the mapset declaring the same width at
     * line 140 for the first of them - and the program stages the seven of them into a
     * seven-character area, one character per row. The bound only reports an over-long value; it
     * restricts the value to no particular character, because an unrecognised code is something the
     * legacy screen reports back to the operator rather than something it rejects at the boundary.
     */
    public static final int SELECTION_LENGTH = 1;

    /**
     * Returns the seven row action codes in screen-row order, first row first.
     *
     * <p>An order-preserving projection of the seven components and nothing more. It interprets no
     * code, recognises no value, counts nothing, tallies nothing, folds no case, trims nothing and
     * builds no indicator: those belong to the card-list service, which reproduces the legacy
     * evaluation order. Its only purpose is to state the row ordering once, in the contract that
     * owns it, so that a consumer walking the rows - to build the per-row error indicator the legacy
     * error path produces at lines 1088 to 1093, rewritten in place at lines 1090 to 1093, or to
     * name the offending rows when more than one is marked at lines 1079 to 1086 - cannot transpose
     * two of them by hand.
     *
     * <p><strong>Every position is present, including the empty ones.</strong> The returned list
     * always has one element per screen row, in row order, and an unmarked row appears as whatever
     * that component holds - {@code null}, an empty string or a blank - rather than being omitted.
     * The list index is therefore the row index. This is why the projection is built over a
     * null-tolerant list: the immutable-copy factories of the platform library reject a null
     * element, so using one would throw on precisely the unmarked row this contract has to preserve.
     *
     * <p>The returned list is unmodifiable and is backed by an array created inside this call and
     * referenced by nothing else, so neither the caller nor this instance can alter it, and this
     * record retains no collection of any kind.
     *
     * @return an unmodifiable list of the seven row action codes in row order, one element per
     *         screen row, permitting {@code null} elements for unmarked rows
     */
    public List<String> selectionsInRowOrder() {
        return Collections.unmodifiableList(Arrays.asList(
                selection1, selection2, selection3, selection4, selection5, selection6, selection7));
    }

    /**
     * Returns a diagnostic representation that mirrors the request layout and discloses no regulated
     * value.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component. Three of these are regulated: the card filter is a
     * primary account number, the account filter is the key that joins straight to a cardholder, and
     * the paging component's retained browse keys embed the same card number followed by the same
     * account identifier. Any structured logger, framework diagnostic, failed assertion, exception
     * message or string interpolation touching an instance would otherwise have emitted all three.
     * The paging component is withheld whole rather than partly, because its own rendering does not
     * withhold its browse keys and this type must not become the path by which they surface.
     *
     * <p><strong>Why the remainder is retained.</strong> The page indicator, the seven row action
     * codes and the attention key are screen-interaction state that identifies nobody: they are the
     * part of a card-list submission worth seeing in a diagnostic, and withholding them would remove
     * the only useful content without protecting anything. The navigation context is printed by
     * delegation because it withholds its own identifying values. The row snapshot is withheld even
     * though it is already opaque: it seals up to seven account-and-card pairs, a diagnostic has no use
     * for the ciphertext, and printing it would put a replayable credential-shaped value in a log.
     *
     * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its
     * component exactly as supplied; no value is masked, truncated or transformed anywhere in this
     * type, because the browse compares a filter to a retrieved record character for character.
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates
     * them. They compare every component by value, which is what a request contract requires, and
     * neither emits anything: an in-memory comparison is not a disclosure surface.
     *
     * @return the request layout with each regulated component replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "CardListRequest["
                + "accountIdFilter=" + REDACTION_PLACEHOLDER
                + ", cardNumberFilter=" + REDACTION_PLACEHOLDER
                + ", displayedPageNumber=" + displayedPageNumber
                + ", selection1=" + selection1
                + ", selection2=" + selection2
                + ", selection3=" + selection3
                + ", selection4=" + selection4
                + ", selection5=" + selection5
                + ", selection6=" + selection6
                + ", selection7=" + selection7
                + ", pageMetadata=" + REDACTION_PLACEHOLDER
                + ", lastPageAlreadyShown=" + lastPageAlreadyShown
                + ", keyAction=" + keyAction
                + ", navigationContext=" + navigationContext
                + ", rowSnapshotToken=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
