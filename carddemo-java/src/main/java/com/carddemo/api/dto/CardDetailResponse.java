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

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable card-detail response &mdash; the REST-era form of the single-card display screen that
 * legacy transaction {@code CCDL} presented from {@code app/cbl/COCRDSLC.cbl} (887 lines).
 *
 * <p>The field contract is the generated symbolic map {@code app/cpy-bms/COCRDSL.CPY}, whose input
 * group is declared at line 17 and whose output redefinition is declared at line 109; the
 * 24&nbsp;&times;&nbsp;80 layout authority is {@code app/bms/COCRDSL.bms}. No COBOL source is
 * copied into this module; every citation below is a reference to a line in the read-only legacy
 * tree.
 *
 * <p><strong>Fifteen value families, fourteen of them modelled.</strong> The symbolic map declares
 * fifteen named field families. Each family contributes a value item on the input side and a
 * same-width value item on the output side, and the two widths were verified equal for all fifteen
 * families, so a single component per family carries both directions without loss. The fourteen
 * modelled here are, in map declaration order: the transaction name, the two screen title lines, the
 * current date, the program name, the current time, the account identifier, the card number, the
 * embossed name, the card active status, the expiry month, the expiry year, the informational message
 * and the error message. Each component's declared width is carried by a named constant below and
 * cited against both the copybook and the mapset.
 *
 * <p><strong>The two message widths on this map are not the module-wide ones.</strong> This map
 * declares {@code INFOMSG X(40)} at {@code app/cpy-bms/COCRDSL.CPY} line 96 and
 * {@code ERRMSG X(80)} at line 102, mirrored at lines 188 and 194 of the output redefinition and
 * corroborated by {@code app/bms/COCRDSL.bms} lines 142 and 146. Nearly every other screen in the
 * estate uses 45 and 78 for the same two roles. The divergence is deliberate contract and is
 * <em>not</em> normalised: {@link #INFO_MESSAGE_LENGTH} is 40 and {@link #ERROR_MESSAGE_LENGTH} is 80,
 * both declared locally so that no neighbouring screen's constant can silently widen or narrow this
 * one. Sharing a constant across screens was rejected for exactly that reason.
 *
 * <p><strong>The fifteenth family is excluded on purpose.</strong> The function-key legend family,
 * declared 75 characters wide at {@code app/cpy-bms/COCRDSL.CPY} line 108 and at
 * {@code app/bms/COCRDSL.bms} line 150, is the key caption printed along the foot of the terminal. It
 * is screen furniture: it carries no card data, changes with no card, and has no meaning to a REST
 * client, which learns the available operations from the interface description rather than from a
 * caption. It is therefore not a component here.
 *
 * <p><strong>There is no expiry-day component, and that is measured rather than assumed.</strong> The
 * map exposes only a month family and a year family; no day family is declared anywhere in
 * {@code app/cpy-bms/COCRDSL.CPY} or {@code app/bms/COCRDSL.bms}. The program does hold a day slice in
 * its own work area, at {@code app/cbl/COCRDSLC.cbl} line 90 alongside the year slice at line 86 and
 * the month slice at line 88, but it emits only the month, at line 480, and the year, at line 482.
 * The day is never sent to this screen, so adding a day component here would surface a value the
 * legacy contract does not expose. The card-update screen is the one that carries a day, and it
 * carries it as a hidden protected value; that belongs to that screen's own types.
 *
 * <p><strong>No generated 3270 plumbing is modelled.</strong> Beside each value item the symbolic map
 * generates control items &mdash; a length item, a flag item and an attribute item on the input side,
 * and colour, highlight, protection and validation items on the output side &mdash; together with a
 * twelve-character leading filler at line 18 and a three-character filler before each output group.
 * All of it is terminal plumbing rather than card data and none of it appears here. Neither do the
 * attribute values the program writes into those items: the field-set attribute at
 * {@code app/cbl/COCRDSLC.cbl} lines 510 and 511, the default and red colours at lines 529, 530, 533,
 * 537, 545 and 551, the dark and neutral colours at lines 554 and 556, or the single marker character
 * the program writes into a blank identifier field at lines 543 and 549. A REST client renders
 * nothing, so it is told what the values are and not how a terminal would have coloured them.
 *
 * <p><strong>Every identifier is text, never a number.</strong> The account identifier is eleven
 * characters and the card number is sixteen; both are identifiers whose leading zeros and whose fixed
 * external widths are contract, so both are carried as {@code String}. A card number of
 * {@code "0000000000000001"} is those exact sixteen characters and is not the number one, and an
 * account identifier of {@code "00000000001"} must never arrive as {@code "1"}. Coercing either to an
 * integral type would drop leading zeros and shorten the external width, and that width is compared
 * directly by the byte-equivalence acceptance criterion. The card number is carried whole: it is
 * neither obscured, shortened nor partially hidden, because the legacy design applies no field-level
 * protection to a primary account number anywhere, and closing that gap here would be unrequested
 * work that also changed the response contract. The gap itself is recorded in
 * {@code docs/decision-log.md} rather than silently left unremarked. That statement is about the
 * component and the payload built from it, which is a contract, and not about the stringified form,
 * which is a contract to nobody: {@link #toString()} is overridden below to withhold the card number,
 * the account identifier and the embossed cardholder name, so carrying a value whole to the client
 * and refusing to print it into a log are complementary rather than contradictory.
 *
 * <p><strong>The expiry month and year are text, and no temporal type appears.</strong> The card
 * record stores the expiration date as a ten-character text field, and its name is misspelled
 * {@code CARD-EXPIRAION-DATE} in the record layout at {@code app/cpy/CVACT02Y.cpy} line 9. The layout
 * position is preserved so the 150-byte record image stays byte-compatible, while the Java property
 * name is spelled correctly; the defect is recorded in {@code docs/decision-log.md} and is not
 * reproduced in any identifier in this module. The program slices that text field into a year part, a
 * month part and a day part at {@code app/cbl/COCRDSLC.cbl} lines 86, 88 and 90, and every slice is a
 * character field. Both components here are therefore {@code String}: no year-month, no date, no
 * month enum and no formatter, and this file imports no type from the platform's date-and-time
 * package. A temporal type
 * would have to parse, and parsing would reject or rewrite values the legacy screen displays as they
 * are &mdash; a blank month, a partially filled year, or a month outside one to twelve all reach this
 * screen unchanged. The month and year range rules belong to the card-update request and its service,
 * where the legacy actually applies them, and deliberately not to this display response.
 *
 * <p><strong>Nothing is validated, defaulted or normalised here.</strong> Every component may
 * legitimately be {@code null}, and a wholly empty response is a real state: the program prompts for
 * input with no card loaded. Values cross this boundary byte for byte and are never trimmed, stripped,
 * padded, case-folded, re-cased or re-formatted. The only constraint used is a maximum length, which
 * measures and never alters, so leading and trailing spaces survive validation untouched &mdash; which
 * matters most for {@link #MSG_EXIT}, whose trailing pad characters are part of its value. No
 * presence, pattern, character-class or numeric-range constraint appears: each would reject input the
 * legacy screen accepts, and constraint violations are reported in an unspecified order, which would
 * displace the source-ordered message cascades that belong to the service layer.
 *
 * <p><strong>The message constants are external contract text, reproduced exactly.</strong> The
 * sixteen constants below are the messages this screen emits, declared in the message block of
 * {@code app/cbl/COCRDSLC.cbl}: the informational messages at lines 130 and 132, and the error-side
 * messages at lines 137, 139, 141, 143, 145, 147, 149, 152, 154, 156, 158, 377, 670 and 711. Their
 * inconsistencies are contract rather than defect and are preserved character for character &mdash;
 * four consecutive dots in one, no space after a period and fourteen trailing pad characters in
 * another, no space after a comma and the wording "A 11" and "A 16" in two more, and a casing mixture
 * across the set. The same account-number wording is declared twice, at lines 145 and 147, against two
 * different conditions; both declarations are carried as separate constants so the duplication remains
 * visible instead of being collapsed into one. This type only carries the text: it selects nothing,
 * composes nothing and formats nothing, because which message applies is a service decision driven by
 * the validation order the legacy program uses.
 *
 * <p>One message emitted by this program is deliberately absent. The abend text written at
 * {@code app/cbl/COCRDSLC.cbl} line 860 belongs to the abend surface rather than to this screen: it is
 * the default carried by the module's abend exception, and declaring it here would create a second
 * source of truth for one string and pull an exception type into a data-transfer contract.
 *
 * <p><strong>Layering.</strong> This type depends on the JDK, on one Bean Validation constraint and on
 * one type from its own package. It holds no reference to a persistent entity, a repository, a
 * service, a configuration class, a framework type or an exception, performs no input or output, and
 * logs nothing. The card active status is carried as its raw single character rather than as the
 * status enum in {@code com.carddemo.domain.enums.CardStatus}: the column replacing that byte carries
 * no check constraint and the batch programs that write card records never validate it, so a value
 * outside the two codes the estate defines must round-trip through this response untouched rather than
 * be resolved, defaulted or rejected here. Resolving the raw character to a typed status is the
 * service layer's decision, and this type performs no lookup of any kind.
 *
 * @param transactionName the transaction identifier shown in the screen header, from the
 *     {@code TRNNAME} family ({@code app/cpy-bms/COCRDSL.CPY} lines 24 and 116,
 *     {@code app/bms/COCRDSL.bms} line 36), width {@link #TRANSACTION_NAME_LENGTH}. May be
 *     {@code null}.
 * @param title01 the first header title line, from the {@code TITLE01} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 30 and 122, {@code app/bms/COCRDSL.bms} line 40), width
 *     {@link #SCREEN_TITLE_LENGTH}. Named for the map item it carries, which is the spelling every
 *     screen contract in this package uses. May be {@code null}.
 * @param currentDate the header date text, from the {@code CURDATE} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 36 and 128, {@code app/bms/COCRDSL.bms} line 49), width
 *     {@link #CURRENT_DATE_LENGTH}. Carried as the eight characters the screen displays, never as a
 *     temporal value. May be {@code null}.
 * @param programName the program name shown in the screen header, from the {@code PGMNAME} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 42 and 134, {@code app/bms/COCRDSL.bms} line 59), width
 *     {@link #PROGRAM_NAME_LENGTH}. May be {@code null}.
 * @param title02 the second header title line, from the {@code TITLE02} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 48 and 140, {@code app/bms/COCRDSL.bms} line 63), width
 *     {@link #SCREEN_TITLE_LENGTH}. Named for the map item it carries, which is the spelling every
 *     screen contract in this package uses. May be {@code null}.
 * @param currentTime the header time text, from the {@code CURTIME} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 54 and 146, {@code app/bms/COCRDSL.bms} line 72), width
 *     {@link #CURRENT_TIME_LENGTH}. Carried as the eight characters the screen displays, never as a
 *     temporal value. May be {@code null}.
 * @param accountId the account identifier the card belongs to, from the {@code ACCTSID} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 60 and 152, {@code app/bms/COCRDSL.bms} line 87), width
 *     {@link #ACCOUNT_ID_LENGTH}. Eleven characters with contractual leading zeros; never a numeric
 *     type. One of the two enterable fields on this screen and therefore one of the two focus targets.
 *     May be {@code null}.
 * @param cardNumber the card number, from the {@code CARDSID} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 66 and 158, {@code app/bms/COCRDSL.bms} line 99), width
 *     {@link #CARD_NUMBER_LENGTH}. Sixteen characters carried whole and unobscured, for the reason
 *     given above; never a numeric type. The other enterable field and focus target. May be
 *     {@code null}.
 * @param embossedName the name embossed on the card, from the {@code CRDNAME} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 72 and 164, {@code app/bms/COCRDSL.bms} line 108), width
 *     {@link #EMBOSSED_NAME_LENGTH}. Space-padded in the legacy record and carried with its padding
 *     intact; never case-folded here, because the upper-casing the estate applies is a character-table
 *     fold performed where a card is updated and not where one is displayed. May be {@code null}.
 * @param cardActiveStatus the raw one-character card active status, from the {@code CRDSTCD} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 78 and 170, {@code app/bms/COCRDSL.bms} line 118), width
 *     {@link #CARD_ACTIVE_STATUS_LENGTH}. Held raw rather than typed so an unrecognised character
 *     round-trips instead of being rejected or absorbed into a synthetic value; not constrained to the
 *     codes the estate defines. May be {@code null}.
 * @param expiryMonth the expiry month, from the {@code EXPMON} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 84 and 176, {@code app/bms/COCRDSL.bms} line 128), width
 *     {@link #EXPIRY_MONTH_LENGTH}. Text, never a temporal or numeric type, and not range-checked
 *     here. May be {@code null}.
 * @param expiryYear the expiry year, from the {@code EXPYEAR} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 90 and 182, {@code app/bms/COCRDSL.bms} line 135), width
 *     {@link #EXPIRY_YEAR_LENGTH}. Text, never a temporal or numeric type, and not range-checked here.
 *     May be {@code null}.
 * @param infoMessage the informational message line, from the {@code INFOMSG} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 96 and 188, {@code app/bms/COCRDSL.bms} line 142), width
 *     {@link #INFO_MESSAGE_LENGTH}, which is 40 on this map. Populated from one of the two
 *     informational constants below, or left absent. Never trimmed. May be {@code null}.
 * @param errorMessage the error message line, from the {@code ERRMSG} family
 *     ({@code app/cpy-bms/COCRDSL.CPY} lines 102 and 194, {@code app/bms/COCRDSL.bms} line 146), width
 *     {@link #ERROR_MESSAGE_LENGTH}, which is 80 on this map. Populated from one of the error-side
 *     constants below, or left absent. Never trimmed, so a value carrying trailing pad characters
 *     keeps them. May be {@code null}.
 * @param generalError whether the screen as a whole is reporting an error, the typed form of the
 *     input-flag error condition declared at {@code app/cbl/COCRDSLC.cbl} line 53 on the flag at line
 *     51, whose companion conditions are the accepted state at line 52 and the not-yet-evaluated state
 *     at line 54. Set by the program at lines 654, 666, 694, 707, 756, 763, 797 and 801 and tested at
 *     lines 360 and 386. An explicit fact of its own: it is deliberately <em>not</em> derived from
 *     whether {@link #errorMessage()} is present, because the legacy flag and the legacy message are
 *     separate values that the program sets independently, and a caller that inferred one from the
 *     other would report an error for a screen carrying only an informational line.
 * @param fieldErrors the field-level detail behind {@link #generalError()}, in the order the turn
 *     accumulated it, which is the order the two filter fields are evaluated in. Each entry names the
 *     field and its screen identifier and states which of the two error conditions it is in: a field the
 *     operator left empty when the screen required one is reported as missing, and a field supplied with
 *     a value the screen could not use is reported as invalid. The distinction is the legacy's own - the
 *     program writes the decoration marker into a field it found <em>blank</em> at
 *     {@code app/cbl/COCRDSLC.cbl} lines 543 and 549 while only recolouring one it found unusable - and a
 *     single error flag with one message line cannot express it, which is why both are published.
 *     Defensively copied; {@code null} becomes empty; never re-ordered, because the order is what the
 *     operator saw.
 * @param focusScreenFieldId the identity of the screen field the client should place the cursor in, or
 *     {@code null} to leave placement to the client. Width
 *     {@link #FOCUS_SCREEN_FIELD_ID_LENGTH}. Only the two enterable fields are ever nominated, as
 *     {@link #SCREEN_FIELD_ACCOUNT_ID} and {@link #SCREEN_FIELD_CARD_NUMBER}. This is an identity and
 *     nothing else: the legacy positioned the cursor by writing a sentinel into a generated length
 *     item at {@code app/cbl/COCRDSLC.cbl} lines 518, 521 and 523, and that sentinel, those generated
 *     items, and any row, column or attribute byte are all absent here.
 * @param nextRoute the route the client should call next, as an opaque token. Declarative only: the
 *     legacy transferred control between programs, whereas this response merely names where the client
 *     may go and the server forwards nothing. The token vocabulary belongs to the navigation service,
 *     so no route table, route enumeration or route resolution appears in this type or anywhere in this
 *     package. Deliberately unbounded, because no legacy field declares a width for a route. Named for
 *     what it is - the <em>next</em> call rather than the current one - which is the spelling every
 *     screen contract in this package uses. May be {@code null}.
 * @param navigationContext the echoed navigation state to send back on the next call, never a
 *     server-side session. May be {@code null}, which is the state in which nothing has been carried
 *     yet.
 */
public record CardDetailResponse(
        @Size(max = CardDetailResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = CardDetailResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = CardDetailResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = CardDetailResponse.PROGRAM_NAME_LENGTH) String programName,
        @Size(max = CardDetailResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = CardDetailResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = CardDetailResponse.ACCOUNT_ID_LENGTH) String accountId,
        @Size(max = CardDetailResponse.CARD_NUMBER_LENGTH) String cardNumber,
        @Size(max = CardDetailResponse.EMBOSSED_NAME_LENGTH) String embossedName,
        @Size(max = CardDetailResponse.CARD_ACTIVE_STATUS_LENGTH) String cardActiveStatus,
        @Size(max = CardDetailResponse.EXPIRY_MONTH_LENGTH) String expiryMonth,
        @Size(max = CardDetailResponse.EXPIRY_YEAR_LENGTH) String expiryYear,
        @Size(max = CardDetailResponse.INFO_MESSAGE_LENGTH) String infoMessage,
        @Size(max = CardDetailResponse.ERROR_MESSAGE_LENGTH) String errorMessage,
        boolean generalError,
        List<ErrorResponse.FieldError> fieldErrors,
        @Size(max = CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {

    /**
     * Canonical constructor. Replaces the field-error list with an immutable copy and leaves every other
     * component exactly as supplied.
     *
     * <p>A {@code null} list becomes an empty immutable list, so the accessor never answers {@code null}
     * and a caller need not distinguish "no findings" from "findings not reported". Nothing else happens
     * here: no component is defaulted, re-ordered, trimmed or case folded, because on a fixed-width
     * space-filled screen field the surrounding spaces are part of what was displayed.
     *
     * @throws NullPointerException if the field-error list contains a {@code null} element, which
     *     {@link List#copyOf(java.util.Collection)} does not admit and which no finding could be
     */
    public CardDetailResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each regulated component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld
     * component - not its length, not a prefix or suffix, not a digest - can be recovered from a
     * stringified instance. A partial stand-in was rejected deliberately: a shortened card number is
     * still cardholder data. The same literal is used by every redacting contract in this package so
     * that the absence of a regulated value is auditable by one search across the whole DTO surface.
     *
     * <p>Private because it is a rendering detail rather than part of the card-detail contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Width in characters of the transaction name shown in the screen header: 4.
     *
     * <p>The declared width of the {@code TRNNAME} family, at {@code app/cpy-bms/COCRDSL.CPY} line 24
     * on the input side and line 116 on the output redefinition, corroborated by
     * {@code app/bms/COCRDSL.bms} line 36. The bound only reports an over-long value; it never trims,
     * pads or otherwise alters one.</p>
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * Width in characters of each of the two header title lines: 40.
     *
     * <p>The declared width of the {@code TITLE01} family at {@code app/cpy-bms/COCRDSL.CPY} lines 30
     * and 122 and of the {@code TITLE02} family at lines 48 and 140, corroborated by
     * {@code app/bms/COCRDSL.bms} lines 40 and 63. One constant serves both because they are the same
     * kind of value at the same declared width, not because two widths happen to coincide.</p>
     *
     * <p>It is <em>not</em> shared with {@link #INFO_MESSAGE_LENGTH}, which is also 40: a title and an
     * informational message are unrelated fields whose widths coincide by accident, and tying them
     * together would let a change to one silently move the other.</p>
     */
    public static final int SCREEN_TITLE_LENGTH = 40;

    /**
     * Width in characters of the header date text: 8.
     *
     * <p>The declared width of the {@code CURDATE} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 36
     * and 128, corroborated by {@code app/bms/COCRDSL.bms} line 49. The value is display text rather
     * than a date, so this is a character width and not a format specification.</p>
     */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * Width in characters of the program name shown in the screen header: 8.
     *
     * <p>The declared width of the {@code PGMNAME} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 42
     * and 134, corroborated by {@code app/bms/COCRDSL.bms} line 59. Declared separately from
     * {@link #CURRENT_DATE_LENGTH} and {@link #CURRENT_TIME_LENGTH} even though all three are 8,
     * because a program name, a date text and a time text are unrelated fields.</p>
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the header time text: 8.
     *
     * <p>The declared width of the {@code CURTIME} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 54
     * and 146, corroborated by {@code app/bms/COCRDSL.bms} line 72. As with the date, the value is
     * display text rather than a time.</p>
     */
    public static final int CURRENT_TIME_LENGTH = 8;

    /**
     * Width in characters of the account identifier: 11.
     *
     * <p>The declared width of the {@code ACCTSID} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 60
     * and 152, corroborated by {@code app/bms/COCRDSL.bms} line 87. The identifier is carried as text
     * so its leading zeros and its eleven-character external width both survive; the bound measures
     * that width and never enforces a digit format, which is a service-level check carrying its own
     * message text.</p>
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Width in characters of the card number: 16.
     *
     * <p>The declared width of the {@code CARDSID} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 66
     * and 158, corroborated by {@code app/bms/COCRDSL.bms} line 99, and the same width as the card
     * number in the 150-byte card record at {@code app/cpy/CVACT02Y.cpy} line 5. Sixteen characters
     * are an identifier rather than a quantity, so the value is text.</p>
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Width in characters of the embossed name: 50.
     *
     * <p>The declared width of the {@code CRDNAME} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 72
     * and 164, corroborated by {@code app/bms/COCRDSL.bms} line 108, and the same width as the embossed
     * name in the card record at {@code app/cpy/CVACT02Y.cpy} line 8. The legacy field is space-padded
     * to this width and the padding is contract, so the bound measures and never strips it.</p>
     */
    public static final int EMBOSSED_NAME_LENGTH = 50;

    /**
     * Width in characters of the raw card active status: 1.
     *
     * <p>The declared width of the {@code CRDSTCD} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 78
     * and 170, corroborated by {@code app/bms/COCRDSL.bms} line 118, and the same width as the active
     * status in the card record at {@code app/cpy/CVACT02Y.cpy} line 10. The bound deliberately does
     * <em>not</em> restrict the value to the codes the estate defines, because the column replacing
     * that record byte carries no check constraint and an unrecognised character must round-trip.</p>
     */
    public static final int CARD_ACTIVE_STATUS_LENGTH = 1;

    /**
     * Width in characters of the expiry month: 2.
     *
     * <p>The declared width of the {@code EXPMON} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 84
     * and 176, corroborated by {@code app/bms/COCRDSL.bms} line 128, and the width of the month slice
     * the program takes from the card expiration text at {@code app/cbl/COCRDSLC.cbl} line 88. A width
     * only: the one-to-twelve range rule belongs to the card-update path, not to this display
     * response.</p>
     */
    public static final int EXPIRY_MONTH_LENGTH = 2;

    /**
     * Width in characters of the expiry year: 4.
     *
     * <p>The declared width of the {@code EXPYEAR} family, at {@code app/cpy-bms/COCRDSL.CPY} lines 90
     * and 182, corroborated by {@code app/bms/COCRDSL.bms} line 135, and the width of the year slice
     * the program takes from the card expiration text at {@code app/cbl/COCRDSLC.cbl} line 86. A width
     * only: the year range rule belongs to the card-update path.</p>
     */
    public static final int EXPIRY_YEAR_LENGTH = 4;

    /**
     * Width in characters of the informational message line: <strong>40</strong>.
     *
     * <p>The declared width of {@code INFOMSG X(40)} at {@code app/cpy-bms/COCRDSL.CPY} line 96,
     * mirrored at line 188 of the output redefinition and corroborated by
     * {@code app/bms/COCRDSL.bms} line 142. It is also the width of the working message field the
     * program moves into that item, declared at {@code app/cbl/COCRDSLC.cbl} line 126 and moved at line
     * 496, so the two agree exactly and nothing is lost in transit.</p>
     *
     * <p><strong>This is 40, not the 45 used by the card-list and account screens.</strong> The
     * constant is declared locally and shared with no other response type precisely so that the
     * narrower width cannot be silently widened by a neighbouring screen's constant. The difference is
     * contract and is not normalised.</p>
     */
    public static final int INFO_MESSAGE_LENGTH = 40;

    /**
     * Width in characters of the error message line: <strong>80</strong>.
     *
     * <p>The declared width of {@code ERRMSG X(80)} at {@code app/cpy-bms/COCRDSL.CPY} line 102,
     * mirrored at line 194 of the output redefinition and corroborated by
     * {@code app/bms/COCRDSL.bms} line 146. The program moves its 75-character working return message,
     * declared at {@code app/cbl/COCRDSLC.cbl} line 134, into this wider item at line 494; the screen
     * item is the response contract, so 80 is the width carried here.</p>
     *
     * <p><strong>This is 80, not the 78 used almost everywhere else in the estate.</strong> As with the
     * informational width, the constant is local so no other screen's value can displace it.</p>
     */
    public static final int ERROR_MESSAGE_LENGTH = 80;

    /**
     * Width in characters of a screen field identity on this map: 7.
     *
     * <p>The widest field name the mapset declares. Every named field in
     * {@code app/bms/COCRDSL.bms} is at most seven characters, and both fields that can ever be
     * nominated for focus &mdash; the account identifier at line 84 and the card number at line 96,
     * the only two declared unprotected &mdash; are exactly seven.</p>
     *
     * <p>The bound measures an identity; it is not a cursor position. No row, column, offset or
     * sentinel is carried anywhere in this type.</p>
     */
    public static final int FOCUS_SCREEN_FIELD_ID_LENGTH = 7;

    /**
     * Identity of the account-identifier field, for {@link #focusScreenFieldId()}: {@code ACCTSID}.
     *
     * <p>The field name declared at {@code app/bms/COCRDSL.bms} line 84, which is the only field on the
     * mapset flagged for the initial cursor and one of only two flagged unprotected. The legacy nominated
     * it for the cursor in three of the four branches of its positioning decision, at
     * {@code app/cbl/COCRDSLC.cbl} lines 518 and 523 &mdash; the latter being the catch-all branch, which
     * makes this the default focus.</p>
     *
     * <p>Provided so a service names a screen field through a constant rather than by repeating a
     * generated label. It is an identity and carries no attribute, colour or position.</p>
     */
    public static final String SCREEN_FIELD_ACCOUNT_ID = "ACCTSID";

    /**
     * Identity of the card-number field, for {@link #focusScreenFieldId()}: {@code CARDSID}.
     *
     * <p>The field name declared at {@code app/bms/COCRDSL.bms} line 96, the second and last of the two
     * unprotected fields on the mapset. The legacy nominated it for the cursor in one branch of its
     * positioning decision, at {@code app/cbl/COCRDSLC.cbl} line 521.</p>
     */
    public static final String SCREEN_FIELD_CARD_NUMBER = "CARDSID";

    /**
     * Informational message stating that the requested card details are being displayed, from
     * {@code app/cbl/COCRDSLC.cbl} line 130: {@code "   Displaying requested details"}.
     *
     * <p>Thirty-one characters carried into {@link #infoMessage()}. <strong>The three leading spaces are
     * part of the value</strong> and must not be trimmed: the legacy field is a fixed-width area in which
     * the indent is how the text was positioned on the line, so removing it changes the rendered contract.
     * </p>
     *
     * <p>Live rather than dead text: the program tests this state at line 474 before populating the card
     * fields, and sets it at lines 754 and 795 once a card has been located.</p>
     */
    public static final String MSG_FOUND_CARDS_FOR_ACCOUNT = "   Displaying requested details";

    /**
     * Informational message prompting for search input, from {@code app/cbl/COCRDSLC.cbl} line 132:
     * {@code "Please enter Account and Card Number"}.
     *
     * <p>Thirty-six characters carried into {@link #infoMessage()}. Set on a first entry with no
     * communication area at line 460, and again at line 491 whenever no informational message has been
     * chosen, which makes it this screen's resting message.</p>
     */
    public static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /**
     * Message emitted when the operator leaves the screen with the exit key, from
     * {@code app/cbl/COCRDSLC.cbl} line 137: {@code "PF03 pressed.Exiting              "}.
     *
     * <p>Thirty-four characters: twenty characters of text followed by <strong>fourteen trailing
     * spaces</strong>. Both peculiarities are contract and neither may be repaired. There is
     * <strong>no space after the period</strong>, and the fourteen trailing spaces are part of the value,
     * so this constant must survive serialization with its full length intact. Trimming it, collapsing
     * its padding or inserting the missing space would each change an externally observable string that
     * the interface-contract acceptance criterion compares character for character.</p>
     */
    public static final String MSG_EXIT = "PF03 pressed.Exiting              ";

    /**
     * Message emitted when no account number was supplied, from {@code app/cbl/COCRDSLC.cbl} line 139:
     * {@code "Account number not provided"}. Twenty-seven characters, carried into
     * {@link #errorMessage()}.
     */
    public static final String MSG_PROMPT_FOR_ACCOUNT = "Account number not provided";

    /**
     * Message emitted when no card number was supplied, from {@code app/cbl/COCRDSLC.cbl} line 141:
     * {@code "Card number not provided"}. Twenty-four characters, carried into {@link #errorMessage()}.
     */
    public static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

    /**
     * Message emitted when neither search field was supplied, from {@code app/cbl/COCRDSLC.cbl} line 143:
     * {@code "No input received"}. Seventeen characters, carried into {@link #errorMessage()}.
     */
    public static final String MSG_NO_SEARCH_CRITERIA_RECEIVED = "No input received";

    /**
     * Message emitted when the supplied account number is all zeros, from
     * {@code app/cbl/COCRDSLC.cbl} line 145:
     * {@code "Account number must be a non zero 11 digit number"}. Forty-nine characters, carried into
     * {@link #errorMessage()}.
     *
     * <p><strong>The identical text is declared twice in the source</strong>, here at line 145 against
     * the all-zeros condition and again at line 147 against the not-numeric condition. Both declarations
     * are carried, as this constant and as {@link #MSG_SEARCHED_ACCOUNT_NOT_NUMERIC}, so the duplication
     * stays visible instead of being collapsed into one name. The two are equal by value and that is
     * intentional; a service selects by condition and the operator sees the same wording either way,
     * exactly as on the legacy screen.</p>
     */
    public static final String MSG_SEARCHED_ACCOUNT_ZEROES =
            "Account number must be a non zero 11 digit number";

    /**
     * Message emitted when the supplied account number is not numeric, from
     * {@code app/cbl/COCRDSLC.cbl} line 147:
     * {@code "Account number must be a non zero 11 digit number"}. Forty-nine characters, carried into
     * {@link #errorMessage()}.
     *
     * <p>The second of the two identical declarations described on {@link #MSG_SEARCHED_ACCOUNT_ZEROES}.
     * It is deliberately <em>not</em> an alias of that constant: each name records one legacy declaration
     * site, and collapsing them would erase the fact that the estate declares the wording twice.</p>
     */
    public static final String MSG_SEARCHED_ACCOUNT_NOT_NUMERIC =
            "Account number must be a non zero 11 digit number";

    /**
     * Message emitted when a supplied card number is not sixteen digits, from
     * {@code app/cbl/COCRDSLC.cbl} line 149:
     * {@code "Card number if supplied must be a 16 digit number"}. Forty-nine characters, carried into
     * {@link #errorMessage()}.
     *
     * <p>Sentence case here, unlike the upper-case filter wording in
     * {@link #MSG_CARD_FILTER_NOT_NUMERIC} that the same program emits from a different point. The two
     * exist side by side in the source and neither is normalised towards the other.</p>
     */
    public static final String MSG_SEARCHED_CARD_NOT_NUMERIC =
            "Card number if supplied must be a 16 digit number";

    /**
     * Message emitted when the account has no cross-reference entry, from
     * {@code app/cbl/COCRDSLC.cbl} line 152: {@code "Did not find this account in cards database"}.
     * Forty-three characters, carried into {@link #errorMessage()}.
     */
    public static final String MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE =
            "Did not find this account in cards database";

    /**
     * Message emitted when the account and card combination matches nothing, from
     * {@code app/cbl/COCRDSLC.cbl} line 154: {@code "Did not find cards for this search condition"}.
     * Forty-four characters, carried into {@link #errorMessage()}.
     */
    public static final String MSG_NO_CARDS_FOR_SEARCH_CONDITION =
            "Did not find cards for this search condition";

    /**
     * Message emitted when reading card data fails, from {@code app/cbl/COCRDSLC.cbl} line 156:
     * {@code "Error reading Card Data File"}. Twenty-eight characters, carried into
     * {@link #errorMessage()}.
     *
     * <p>Deliberately opaque about the cause. The legacy also assembled a detailed diagnostic naming the
     * failing operation, file and response codes, but that text never reaches this screen item, and no
     * status code, exception name, table name or internal path is exposed through this response.</p>
     */
    public static final String MSG_CARD_DATA_READ_ERROR = "Error reading Card Data File";

    /**
     * Message emitted when the supplied search criteria pass every edit, from
     * {@code app/cbl/COCRDSLC.cbl} line 158: {@code "Looks Good.... so far"}.
     *
     * <p>Twenty-one characters, carried into {@link #errorMessage()}. <strong>The ellipsis is four
     * consecutive dots, not three</strong>, and the count is contract: the interface-contract acceptance
     * criterion compares this string character for character, so writing a conventional three-dot
     * ellipsis would fail it.</p>
     */
    public static final String MSG_CODING_TO_BE_DONE = "Looks Good.... so far";

    /**
     * Message emitted when the screen is re-entered in a state the program does not recognise, from
     * {@code app/cbl/COCRDSLC.cbl} line 377: {@code "UNEXPECTED DATA SCENARIO"}.
     *
     * <p>Twenty-four characters, carried into {@link #errorMessage()}. Upper case, unlike most of the
     * messages on this screen; the mixed casing across the set is contract and is preserved. It is the
     * catch-all outcome of the program's entry-state decision, and it is a message rather than an abend:
     * the abend text this program can also produce is not declared in this type.</p>
     */
    public static final String MSG_UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    /**
     * Message emitted when the account filter is present but not numeric, from
     * {@code app/cbl/COCRDSLC.cbl} line 670:
     * {@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"}.
     *
     * <p>Fifty-two characters, carried into {@link #errorMessage()}. Three peculiarities are all
     * contract: it is upper case, there is <strong>no space after the comma</strong>, and the article is
     * <strong>"A 11" rather than "AN 11"</strong>. None may be corrected. The program emits it only when
     * no message has already been chosen, which is why message selection stays in the service where that
     * ordering lives.</p>
     */
    public static final String MSG_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Message emitted when the card filter is present but not numeric, from
     * {@code app/cbl/COCRDSLC.cbl} line 711:
     * {@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"}.
     *
     * <p>Fifty-two characters, carried into {@link #errorMessage()}. As with the account wording it is
     * upper case, has <strong>no space after the comma</strong>, and reads <strong>"A 16"</strong>. It
     * coexists with the sentence-case {@link #MSG_SEARCHED_CARD_NOT_NUMERIC} in the same program, and the
     * two are kept distinct.</p>
     */
    public static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * Returns a diagnostic representation that mirrors the response layout and discloses no regulated
     * value.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component, and this screen is the one that presents a single
     * card in full. Six components are regulated or identifying and they appear together: the card
     * number is a primary account number carried at its full sixteen characters, the account id is the
     * key that joins straight to a cardholder, the embossed name is the cardholder's own name as it
     * appears on the card, and the expiry month and year reconstruct the card's expiry date, which is
     * an authentication factor whenever it travels beside the number. That combination - number, name
     * and expiry in one line - is the most sensitive grouping this package handles, and any structured
     * logger, framework diagnostic, failed assertion, exception message or string interpolation
     * touching an instance would have emitted it whole.</p>
     *
     * <p><strong>Why the remainder is retained.</strong> The six header items are screen furniture, the
     * active-status indicator is a single character that identifies nobody, the two message slots carry
     * operator text drawn from the fixed catalogue declared above, the error flag is a boolean, the
     * focus hint is a map field name and the route is an opaque token: none of them identifies anybody,
     * and all of them are what a diagnostic is read for. Withholding them would remove the only useful
     * content without protecting anything. The navigation context is printed by delegation because it
     * withholds its own identifying values.</p>
     *
     * <p><strong>The withheld set is the same one {@link CardUpdateResponse#toString()} withholds.</strong>
     * The two card screens describe the same record, so a value that is unsafe to print from the update
     * screen is not made safe by having been reached through the detail screen, and divergence between
     * the two would be the kind of inconsistency that survives review by looking local.</p>
     *
     * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its component
     * exactly as supplied. Nothing here masks, truncates, trims, case-folds or otherwise transforms a
     * value - the class documentation makes that a contract, because the legacy fields are fixed width
     * and space filled and their padding is part of what the screen displayed - and this method is not
     * on the serialization path, which is produced from the accessors.</p>
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates
     * them. They compare every component by value, which is what a response contract requires, and
     * neither emits anything: an in-memory comparison is not a disclosure surface.</p>
     *
     * @return the response layout with each regulated component replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "CardDetailResponse["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", accountId=" + REDACTION_PLACEHOLDER
                + ", cardNumber=" + REDACTION_PLACEHOLDER
                + ", embossedName=" + REDACTION_PLACEHOLDER
                + ", cardActiveStatus=" + cardActiveStatus
                + ", expiryMonth=" + REDACTION_PLACEHOLDER
                + ", expiryYear=" + REDACTION_PLACEHOLDER
                + ", infoMessage=" + infoMessage
                + ", errorMessage=" + errorMessage
                + ", generalError=" + generalError
                + ", fieldErrors=" + fieldErrors
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
