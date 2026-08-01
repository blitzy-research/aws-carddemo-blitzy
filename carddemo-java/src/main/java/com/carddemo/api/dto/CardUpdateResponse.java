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

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * Immutable card-update response contract for legacy CICS transaction {@code CCUP}, derived from
 * symbolic map {@code app/cpy-bms/COCRDUP.CPY}, mapset {@code app/bms/COCRDUP.bms} and program
 * {@code app/cbl/COCRDUPC.cbl} - 1,560 lines. The persisted layout behind the card values is the
 * 150-byte card record {@code app/cpy/CVACT02Y.cpy}. Field-level error decoration follows the
 * parameterized macro {@code app/cpy/CSSETATY.cpy}, and the echoed request state derives from the
 * communication area {@code app/cpy/COCOM01Y.cpy}.
 *
 * <p>Provenance of every citation in this file: checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Line numbers refer to that checkout, and
 * the legacy tree is read-only reference: no statement of it is transcribed here.
 *
 * <p><strong>What the map contributes.</strong> The symbolic map declares an input group and an
 * output group that redefines it, and the two have full width parity, so a single set of widths
 * governs both directions. Fifteen of the output group's seventeen displayable items are modelled:
 * six screen-header items, seven resulting card values and the two message lines. The remaining
 * two are the function-key legend items, which carry the fixed operator hints
 * {@code ENTER=Process F3=Exit} and {@code F5=Save F12=Cancel} as unconditional screen furniture
 * (mapset lines 158 and 163). They convey no state, so they are deliberately absent: a REST client
 * that needs to know the save gate reads it from the contract, not from a caption.
 *
 * <p><strong>What the map deliberately does not contribute.</strong> Each map item is generated
 * with a family of one-character control sub-fields - length, flag, attribute, colour, highlight,
 * programmed-symbol and validation - and the group opens with a twelve-byte terminal-buffer prefix.
 * All of it is 3270 rendering plumbing. None of it appears here: this type holds no attribute byte,
 * no colour value, no highlight value, no marker character, no map coordinate, no cursor position
 * and no edited screen overlay. The legacy program signalled a field error by writing a colour into
 * a control sub-field and, for a blank field only, a marker over the displayed value (program lines
 * 1243 to 1307); it positioned the cursor by writing a sentinel into a length sub-field (lines 1211
 * to 1235). Both mechanisms are discarded and only their meaning survives, as
 * {@link ErrorResponse.FieldError} entries and as {@link #focusScreenFieldId()}.
 *
 * <p><strong>Two widths that look like defects and are not.</strong> On this map the information
 * line is 40 characters and the error line is 80 (map lines 206 and 212, corroborated by mapset
 * lines 149 and 154). The card-list and account maps declare 45 and 78 for the same two roles.
 * The divergence is reproduced rather than reconciled, and {@link #INFORMATION_MESSAGE_LENGTH} and
 * {@link #ERROR_MESSAGE_LENGTH} are local to this type precisely so that no later edit can
 * accidentally unify them with another screen's widths.
 *
 * <p><strong>Nothing is normalized.</strong> Every value is carried exactly as the service supplied
 * it. No component is shortened, space-filled, re-cased, case-folded, obscured, cut short or
 * re-rendered, and the bounds below only report an over-long value - they never alter one. This
 * matters because the legacy fields are fixed-width and space-significant: one of the messages this
 * response carries ends in trailing spaces that are part of its value, and blank or space-filled
 * values are ordinary rather than exceptional.
 *
 * <p><strong>The case fold, and why it is the service's business and not this type's.</strong> The
 * program upper-folds the embossed name in place twice through a strict 26-character ASCII table
 * (declared at program lines 261 and 263): once at line 1357, before capturing the before-image at
 * line 1360, and once at line 1499, before comparing the submitted values field by field at lines
 * 1503 to 1508. Both folds are in place, which is why the comparison never sees the operator's
 * original letter case. The comparison's unequal branch jumps back to the write-processing exit at
 * line 1494 from line 1518. The consequence is observable and must be preserved: an edit that
 * differs from the fetched value only in letter case is reported as
 * {@link Messages#NO_CHANGES_DETECTED} rather than as an update. This type passes the service's
 * bytes through untouched and performs no fold of its own; the platform's locale-sensitive
 * upper-casing is not an equivalent of that table and is not used anywhere in this migration.
 *
 * <p><strong>The error surface.</strong> Exactly one summary message accompanies any number of
 * independent per-field errors, which is the legacy shape: every editor sets the summary text only
 * while it is still unset - a first-error-wins gate visible at program lines 730 and 743 - whereas
 * the per-field flags are set independently of each other. The two are therefore separate
 * components here, and {@link #generalError()} is a third, explicitly supplied, because the program
 * kept its own error flag (declared at line 55 and set at more than a dozen sites) independently of
 * whether any text had been chosen. Deriving error-ness from message presence would collapse two
 * independent legacy variables into one and would misreport the case where the flag is set while
 * the text slot is already occupied by an earlier failure.
 *
 * <p><strong>Field errors are absent on a first submission.</strong> The decoration macro fires only
 * when the program-context re-enter condition holds (macro line 20), and the program applies the
 * same gate to the two search fields at lines 1248 and 1258, using its own change-state gate for the
 * four card-detail fields. A first submission therefore returns the summary line with no field
 * errors at all, and re-submission is what populates them. This type only has to be constructible
 * in both shapes; the gate itself belongs to the service.
 *
 * <p>The per-field states come from {@link ErrorResponse.FieldState}, which has exactly two
 * constants because the legacy screen told exactly two operator mistakes apart: a field left blank
 * and a field filled in badly. The structurally identical state type declared alongside the
 * validation-failure carrier is deliberately not reused: depending on that package from here would
 * invert the module's layer direction, and the global failure handler one level up owns the
 * translation between the two.
 *
 * <p><strong>Navigation is declarative.</strong> {@link #route()} is an opaque label that names
 * where the client should go next, and {@link #navigationContext()} is echoed request state rather
 * than a server-held session. Neither is executed here: the legacy transferred control to another
 * program (line 473) and re-armed itself for the next turn (line 555), and the migrated equivalent
 * is the client making the next call. This type owns no route vocabulary, resolves no route and
 * forwards nothing.
 *
 * <p><strong>Every value is characters, and that is a decision rather than an omission.</strong>
 * The account identifier and the card number are fixed-width identifiers whose leading zeros carry
 * meaning, so an integral type would both lose them and invite arithmetic on a value that is never
 * arithmetic. The expiry month, year and day are three separate two-, four- and two-character screen
 * items that the program slices out of a single ten-character record field at fixed offsets, and it
 * accepts, redisplays and re-validates each of them independently, including while one of them is
 * blank or partly filled; a date or year-month type could not hold that intermediate state and would
 * silently normalize or reject values the legacy screen round-trips, so no date or time type is used
 * here at all. The active-status code stays a raw single character for the same reason: the column
 * that replaced it carries no check constraint, only one online program ever validates it, and every
 * batch reader takes it from the file unchecked, so a value outside the two known codes has to
 * round-trip untouched. The two-constant status enumeration in the domain layer is therefore
 * deliberately not used as this component's type - mapping through it would have to either invent a
 * third constant for the unmatched case or fail on data the legacy system accepts - and a service
 * that wants the typed view can still obtain it from the raw code.
 *
 * <p><strong>The expiry day is a hidden carry-through and is never editable.</strong> The mapset
 * declares its attributes as {@code (DRK,FSET,PROT)} - dark, field-set and protected - at mapset
 * line 142, so the operator never sees it and can never type it, and the program reinforces that
 * by forcing it dark on every send (program line 1285) and by never decorating it as an error. It
 * exists because the day component of the stored expiry date has to survive a screen round-trip so
 * that the change comparison at program lines 1503 to 1508 can put the whole date back together.
 * It is modelled for exactly that reason and must be echoed back unchanged; it is not an editable
 * field and no client should present it as one.
 *
 * <p><strong>Deliberately absent.</strong> No version, entity-tag or concurrency component exists
 * here even though one of the messages reports a concurrent change: the legacy detected it by
 * comparing before and after images, the migrated card entity uses a version column, and both are
 * persistence concerns. The conflict reaches the client purely as {@link Messages#DATA_WAS_CHANGED}.
 * Equally absent are the abend path, which belongs to the abend service and whose default operator
 * text is owned there rather than restated here; the screen work area, whose key-action and
 * identity state is request-side and whose identifiers this response already carries; the card
 * status enumeration and every date and time type, for the reasons given above; and the standard
 * problem-detail representation, which this module switches off in favour of {@link ErrorResponse}.
 *
 * <p><strong>Wire and thread contract.</strong> The module omits {@code null} values globally and
 * tolerates unknown properties globally, so no serialization annotation is needed and none is
 * declared. {@link #fieldErrors()} is normalized in the canonical constructor and is therefore
 * always present, emitted as an empty array when there is nothing to report, so a client never has
 * to test it for {@code null}. Instances are deeply immutable and safe to share across threads.
 *
 * @param transactionName    the transaction identifier echoed into the screen header, four
 *                           characters, map line 128. The program supplies its own identifier
 *                           {@code CCUP} at line 1059.
 * @param screenTitleLine1   the first title line, 40 characters, map line 134, supplied from the
 *                           shared title constants at program line 1057.
 * @param currentDate        the header date as the screen rendered it, eight characters, map line
 *                           140, supplied at program line 1068. An already-rendered display string,
 *                           not a date value.
 * @param programName        the program name echoed into the screen header, eight characters, map
 *                           line 146, supplied at program line 1060.
 * @param screenTitleLine2   the second title line, 40 characters, map line 152, supplied at program
 *                           line 1058.
 * @param currentTime        the header time as the screen rendered it, eight characters, map line
 *                           158, supplied at program line 1074. An already-rendered display string,
 *                           not a time value.
 * @param accountId          the eleven-character account identifier the update was keyed by, map
 *                           line 164. Protected on the mapset once details have been fetched
 *                           (mapset line 84), so it is a resulting value the client redisplays
 *                           rather than one it may edit. Carried as characters, never as a number:
 *                           it is a fixed-width identifier whose leading zeros are significant.
 * @param cardNumber         the sixteen-character card number, map line 170. Carried in full and in
 *                           the clear, exactly as the legacy screen and record did, because the
 *                           migration reproduces the existing contract and introduces no field-level
 *                           protection the estate does not have. It is never shortened, obscured or
 *                           re-rendered, and never carried as a number.
 * @param embossedName       the embossed cardholder name, 50 characters, map line 176. Whatever the
 *                           service produced, including its letter case; see the case-fold note
 *                           above.
 * @param activeStatus       the one-character active-status code, map line 182, carried raw for the
 *                           reasons set out above.
 * @param expiryMonth        the two-character expiry month, map line 188. Right-justified on the
 *                           screen (mapset line 129) and carried unaltered here.
 * @param expiryYear         the four-character expiry year, map line 194. Right-justified on the
 *                           screen (mapset line 137) and carried unaltered here.
 * @param expiryDay          the two-character expiry day, map line 200. Hidden, protected and never
 *                           editable; a carry-through component, as set out above.
 * @param informationMessage the information line, at most {@value #INFORMATION_MESSAGE_LENGTH}
 *                           characters, map line 206, supplied at program line 1161. One of the
 *                           {@link Messages} texts, or absent.
 * @param errorMessage       the error line, at most {@value #ERROR_MESSAGE_LENGTH} characters, map
 *                           line 212, supplied at program line 1163 from a 75-character work field,
 *                           which is why the line is wider than any text it can carry. One of the
 *                           {@link Messages} texts, or absent. Never a file status code, an internal
 *                           path, a statement fragment, a schema or table name, or any other
 *                           internal detail.
 * @param generalError       whether the submission failed as a whole, supplied explicitly and never
 *                           inferred from the presence of a message.
 * @param fieldErrors        the independent per-field errors, never {@code null} and never mutable.
 *                           Empty means no field-level error, which is also the first-submission
 *                           shape.
 * @param focusScreenFieldId the legacy screen field identifier that should receive input focus, or
 *                           {@code null} when the response offers no hint. An opaque label only.
 * @param route              the declarative next route, or {@code null}. An opaque label that this
 *                           type neither interprets nor acts on.
 * @param navigationContext  the echoed navigation state, or {@code null} when the caller carries
 *                           none. Immutable request state, not a server session.
 * @since 1.0.0
 */
public record CardUpdateResponse(

        /* TRNNAMEO, width 4, COCRDUP.CPY line 128 - screen header. */
        @Size(max = CardUpdateResponse.TRANSACTION_NAME_LENGTH) String transactionName,

        /* TITLE01O, width 40, COCRDUP.CPY line 134 - screen header. */
        @Size(max = CardUpdateResponse.SCREEN_TITLE_LENGTH) String screenTitleLine1,

        /* CURDATEO, width 8, COCRDUP.CPY line 140 - screen header, already rendered. */
        @Size(max = CardUpdateResponse.CURRENT_DATE_LENGTH) String currentDate,

        /* PGMNAMEO, width 8, COCRDUP.CPY line 146 - screen header. */
        @Size(max = CardUpdateResponse.PROGRAM_NAME_LENGTH) String programName,

        /* TITLE02O, width 40, COCRDUP.CPY line 152 - screen header. */
        @Size(max = CardUpdateResponse.SCREEN_TITLE_LENGTH) String screenTitleLine2,

        /* CURTIMEO, width 8, COCRDUP.CPY line 158 - screen header, already rendered. */
        @Size(max = CardUpdateResponse.CURRENT_TIME_LENGTH) String currentTime,

        /* ACCTSIDO, width 11, COCRDUP.CPY line 164 - protected on the mapset, line 84. */
        @Size(max = CardUpdateResponse.ACCOUNT_ID_LENGTH) String accountId,

        /* CARDSIDO, width 16, COCRDUP.CPY line 170 - carried in full, never obscured. */
        @Size(max = CardUpdateResponse.CARD_NUMBER_LENGTH) String cardNumber,

        /* CRDNAMEO, width 50, COCRDUP.CPY line 176 - decorated at program lines 1263 to 1272. */
        @Size(max = CardUpdateResponse.EMBOSSED_NAME_LENGTH) String embossedName,

        /* CRDSTCDO, width 1, COCRDUP.CPY line 182 - decorated at program lines 1274 to 1283. */
        @Size(max = CardUpdateResponse.ACTIVE_STATUS_LENGTH) String activeStatus,

        /* EXPMONO, width 2, COCRDUP.CPY line 188 - decorated at program lines 1287 to 1296. */
        @Size(max = CardUpdateResponse.EXPIRY_MONTH_LENGTH) String expiryMonth,

        /* EXPYEARO, width 4, COCRDUP.CPY line 194 - decorated at program lines 1298 to 1307. */
        @Size(max = CardUpdateResponse.EXPIRY_YEAR_LENGTH) String expiryYear,

        /* EXPDAYO, width 2, COCRDUP.CPY line 200 - hidden and protected, mapset line 142. */
        @Size(max = CardUpdateResponse.EXPIRY_DAY_LENGTH) String expiryDay,

        /* INFOMSGO, width 40 on this map, COCRDUP.CPY line 206 - set at program line 1161. */
        @Size(max = CardUpdateResponse.INFORMATION_MESSAGE_LENGTH) String informationMessage,

        /* ERRMSGO, width 80 on this map, COCRDUP.CPY line 212 - set at program line 1163. */
        @Size(max = CardUpdateResponse.ERROR_MESSAGE_LENGTH) String errorMessage,

        /* Explicit whole-submission failure flag, from the program's own flag at line 55. */
        boolean generalError,

        /* Per-field errors, from the decoration macro; empty on a first submission. */
        List<ErrorResponse.FieldError> fieldErrors,

        /* Focus hint, from the cursor-positioning cascade at program lines 1211 to 1235. */
        @Size(max = CardUpdateResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,

        /* Declarative next route; opaque, never resolved or dispatched here. */
        String route,

        /* Echoed navigation state, from COCOM01Y; immutable, not a server session. */
        NavigationContext navigationContext) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each regulated component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld
     * component - not its length, not a prefix or suffix, not a digest - can be recovered from a
     * stringified instance. A partial stand-in was rejected deliberately: a shortened card number
     * is still cardholder data.
     *
     * <p>This affects the stringified form only. The components themselves, and therefore the
     * serialized payload a client receives, always carry the full untouched value, which is the
     * contract the legacy screen and record established. Private because it is a rendering detail
     * and not part of the card-update contract.
     */
    private static final String WITHHELD_VALUE = "***WITHHELD***";

    /**
     * Width in characters of the transaction identifier echoed into the screen header: 4.
     *
     * <p>The declared width of {@code TRNNAMEO} at {@code app/cpy-bms/COCRDUP.CPY} line 128, and of
     * the transaction identifier the program supplies for it at line 1059 of
     * {@code app/cbl/COCRDUPC.cbl}. The bound only reports an over-long value; it never shortens,
     * space-fills or otherwise alters one.
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * Width in characters of a screen title line: 40.
     *
     * <p>The declared width of {@code TITLE01O} at {@code app/cpy-bms/COCRDUP.CPY} line 134 and of
     * {@code TITLE02O} at line 152. The two share this constant because they are the same kind of
     * value at the same declared width, not because their widths happen to coincide. Deliberately
     * distinct from {@link #INFORMATION_MESSAGE_LENGTH}, which is also 40 but is a different kind of
     * value on a different screen row.
     */
    public static final int SCREEN_TITLE_LENGTH = 40;

    /**
     * Width in characters of the header date: 8.
     *
     * <p>The declared width of {@code CURDATEO} at {@code app/cpy-bms/COCRDUP.CPY} line 140. The
     * mapset initialises the field with an eight-character display pattern (mapset line 51), which
     * is what the width is for: this is a rendered string, not a date value.
     */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * Width in characters of the program name echoed into the screen header: 8.
     *
     * <p>The declared width of {@code PGMNAMEO} at {@code app/cpy-bms/COCRDUP.CPY} line 146, and of
     * the program name the program supplies for it at line 1060 of {@code app/cbl/COCRDUPC.cbl}.
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the header time: 8.
     *
     * <p>The declared width of {@code CURTIMEO} at {@code app/cpy-bms/COCRDUP.CPY} line 158. As with
     * the date, the mapset initialises it with an eight-character display pattern (mapset line 74),
     * so this is a rendered string rather than a time value. Kept distinct from
     * {@link #CURRENT_DATE_LENGTH} and {@link #PROGRAM_NAME_LENGTH} because three different kinds of
     * value merely coincide at 8.
     */
    public static final int CURRENT_TIME_LENGTH = 8;

    /**
     * Width in characters of the account identifier: 11.
     *
     * <p>The declared width of {@code ACCTSIDO} at {@code app/cpy-bms/COCRDUP.CPY} line 164, which
     * matches the account identifier held in the 150-byte card record {@code app/cpy/CVACT02Y.cpy}
     * at line 6. Leading zeros are significant at this width, which is why the component is
     * characters and not a number.
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Width in characters of the card number: 16.
     *
     * <p>The declared width of {@code CARDSIDO} at {@code app/cpy-bms/COCRDUP.CPY} line 170, which
     * matches the card number that opens the 150-byte card record {@code app/cpy/CVACT02Y.cpy} at
     * line 5 and serves as its key. The full 16 characters are always carried.
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Width in characters of the embossed cardholder name: 50.
     *
     * <p>The declared width of {@code CRDNAMEO} at {@code app/cpy-bms/COCRDUP.CPY} line 176, which
     * matches the embossed name in the card record {@code app/cpy/CVACT02Y.cpy} at line 8.
     */
    public static final int EMBOSSED_NAME_LENGTH = 50;

    /**
     * Width in characters of the active-status code: 1.
     *
     * <p>The declared width of {@code CRDSTCDO} at {@code app/cpy-bms/COCRDUP.CPY} line 182, which
     * matches the active-status field of the card record {@code app/cpy/CVACT02Y.cpy} at line 10.
     */
    public static final int ACTIVE_STATUS_LENGTH = 1;

    /**
     * Width in characters of the expiry month: 2.
     *
     * <p>The declared width of {@code EXPMONO} at {@code app/cpy-bms/COCRDUP.CPY} line 188. The
     * program takes the month from a two-character slice of the record's ten-character expiry date
     * (line 1364 of {@code app/cbl/COCRDUPC.cbl}).
     */
    public static final int EXPIRY_MONTH_LENGTH = 2;

    /**
     * Width in characters of the expiry year: 4.
     *
     * <p>The declared width of {@code EXPYEARO} at {@code app/cpy-bms/COCRDUP.CPY} line 194. The
     * program takes the year from the leading four-character slice of the record's ten-character
     * expiry date (line 1362 of {@code app/cbl/COCRDUPC.cbl}). Distinct from
     * {@link #TRANSACTION_NAME_LENGTH} despite both being 4.
     */
    public static final int EXPIRY_YEAR_LENGTH = 4;

    /**
     * Width in characters of the hidden expiry day: 2.
     *
     * <p>The declared width of {@code EXPDAYO} at {@code app/cpy-bms/COCRDUP.CPY} line 200. The
     * program takes the day from a two-character slice of the record's ten-character expiry date
     * (line 1366 of {@code app/cbl/COCRDUPC.cbl}). Kept distinct from
     * {@link #EXPIRY_MONTH_LENGTH} because the two are different components of the date that happen
     * to share a width, and because only this one is hidden and protected.
     */
    public static final int EXPIRY_DAY_LENGTH = 2;

    /**
     * Width in characters of the information line on <em>this</em> map: 40.
     *
     * <p>The declared width of {@code INFOMSGO} at {@code app/cpy-bms/COCRDUP.CPY} line 206,
     * corroborated by mapset line 152, and matching the 40-character work field the program moves
     * into it at line 1161 of {@code app/cbl/COCRDUPC.cbl}.
     *
     * <p>The card-list and account maps declare 45 for the same screen role. That divergence is
     * reproduced rather than reconciled, so this constant is local to this type and must never be
     * shared with, or replaced by, another screen's width.
     */
    public static final int INFORMATION_MESSAGE_LENGTH = 40;

    /**
     * Width in characters of the error line on <em>this</em> map: 80.
     *
     * <p>The declared width of {@code ERRMSGO} at {@code app/cpy-bms/COCRDUP.CPY} line 212,
     * corroborated by mapset line 156. The program fills it from a 75-character work field at line
     * 1163 of {@code app/cbl/COCRDUPC.cbl}, so the line is deliberately wider than any text it can
     * carry and the surplus is not an error.
     *
     * <p>The card-list and account maps declare 78 for the same screen role. As with the information
     * line, the divergence is reproduced rather than reconciled and this constant stays local.
     */
    public static final int ERROR_MESSAGE_LENGTH = 80;

    /**
     * Width in characters of a legacy screen field identifier: 7.
     *
     * <p>The longest identifier the mapset {@code app/bms/COCRDUP.bms} defines is seven characters,
     * and every field the cursor-positioning cascade at lines 1211 to 1235 of
     * {@code app/cbl/COCRDUPC.cbl} can target is within that bound. The identifier is an opaque
     * label used only for correlation with the map it derives from.
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /**
     * Normalizes the per-field error collection so that the component is never {@code null}, never
     * aliased to caller-owned state and never mutable.
     *
     * <p>A {@code null} collection becomes the empty immutable list rather than being stored, so
     * every accessor and every serialized payload sees a usable collection - which is also the
     * first-submission shape, because the legacy decoration was gated on re-entry. A
     * non-{@code null} collection is defensively copied with
     * {@link List#copyOf(java.util.Collection)}, which both detaches it from the caller and rejects
     * a {@code null} element: an entry with no state would be meaningless, and silently dropping it
     * would hide an error the client has to show.
     *
     * <p>Every other component is stored exactly as supplied, including {@code null} and including
     * any leading or trailing space. Nothing is shortened, space-filled, re-cased, folded or
     * re-rendered here, because the legacy screen and record fields are fixed-width and
     * space-significant, and one message this response carries ends in trailing spaces that belong
     * to its value.
     */
    public CardUpdateResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Builds the successful or purely informational shape: the fetched or updated card values, one
     * information line, and no error of any kind.
     *
     * <p>This is the shape behind {@link Messages#FOUND_CARDS_FOR_ACCOUNT},
     * {@link Messages#PROMPT_FOR_CHANGES}, {@link Messages#PROMPT_FOR_CONFIRMATION} and
     * {@link Messages#CONFIRM_UPDATE_SUCCESS}: the legacy screen showed those on the information row
     * with the error row blank, no field decorated and its own error flag clear.
     *
     * @param transactionName    the header transaction identifier
     * @param screenTitleLine1   the first header title line
     * @param currentDate        the header date as rendered
     * @param programName        the header program name
     * @param screenTitleLine2   the second header title line
     * @param currentTime        the header time as rendered
     * @param accountId          the account identifier the update was keyed by
     * @param cardNumber         the card number, in full
     * @param embossedName       the embossed cardholder name, exactly as produced
     * @param activeStatus       the raw one-character active-status code
     * @param expiryMonth        the expiry month characters
     * @param expiryYear         the expiry year characters
     * @param expiryDay          the hidden expiry day characters, echoed for carry-through
     * @param informationMessage the information line, or {@code null}
     * @param route              the declarative next route, or {@code null}
     * @param navigationContext  the echoed navigation state, or {@code null}
     */
    public CardUpdateResponse(String transactionName,
                              String screenTitleLine1,
                              String currentDate,
                              String programName,
                              String screenTitleLine2,
                              String currentTime,
                              String accountId,
                              String cardNumber,
                              String embossedName,
                              String activeStatus,
                              String expiryMonth,
                              String expiryYear,
                              String expiryDay,
                              String informationMessage,
                              String route,
                              NavigationContext navigationContext) {
        this(transactionName,
                screenTitleLine1,
                currentDate,
                programName,
                screenTitleLine2,
                currentTime,
                accountId,
                cardNumber,
                embossedName,
                activeStatus,
                expiryMonth,
                expiryYear,
                expiryDay,
                informationMessage,
                null,
                false,
                List.of(),
                null,
                route,
                navigationContext);
    }

    /**
     * Tests whether this response carries any per-field error.
     *
     * <p>A convenience test over {@link #fieldErrors()} for callers that only need to branch on
     * presence. It is <em>not</em> a substitute for reading the individual states: a caller that has
     * to tell an operator what to do must inspect {@link ErrorResponse.FieldError#state()} on each
     * entry, because a field left blank and a field filled in badly need different remedies. It is
     * also not a substitute for {@link #generalError()}, which the service supplies independently -
     * the legacy program set its own error flag without necessarily decorating a field, for instance
     * when a record could not be locked or an update failed.
     *
     * @return {@code true} when at least one per-field error is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * Renders this response for diagnostics with every regulated component withheld.
     *
     * <p>The account identifier, the card number and the embossed cardholder name are replaced by a
     * fixed stand-in, so that a stringified instance reaching a log, a diagnostic message or a
     * failure report discloses none of them. Every other component is shown as-is: the header items,
     * the expiry components, the status code, the two message lines, the error surface and the
     * navigation state are all needed to diagnose a response and none of them identifies a
     * cardholder. The navigation state renders itself under the same discipline.
     *
     * <p>This override changes only the stringified form. The component accessors and the serialized
     * payload are unaffected and continue to carry the full untouched values, which is the contract
     * the legacy screen and record established and which this migration reproduces without
     * introducing protection the estate does not have.
     *
     * @return a diagnostic rendering that never discloses a regulated value
     */
    @Override
    public String toString() {
        return "CardUpdateResponse["
                + "transactionName=" + transactionName
                + ", screenTitleLine1=" + screenTitleLine1
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", screenTitleLine2=" + screenTitleLine2
                + ", currentTime=" + currentTime
                + ", accountId=" + WITHHELD_VALUE
                + ", cardNumber=" + WITHHELD_VALUE
                + ", embossedName=" + WITHHELD_VALUE
                + ", activeStatus=" + activeStatus
                + ", expiryMonth=" + expiryMonth
                + ", expiryYear=" + expiryYear
                + ", expiryDay=" + expiryDay
                + ", informationMessage=" + informationMessage
                + ", errorMessage=" + errorMessage
                + ", generalError=" + generalError
                + ", fieldErrors=" + fieldErrors
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", route=" + route
                + ", navigationContext=" + navigationContext
                + "]";
    }

    /**
     * The exact operator texts this response can carry, reproduced character for character from
     * {@code app/cbl/COCRDUPC.cbl}.
     *
     * <p>These are external interface contract text, not internal wording: operators read them and
     * downstream tooling matches on them, so each one is a fixed part of the migrated contract and is
     * verified character for character. Every constant below is declared exactly as the program
     * declares it, at the cited line, with its spacing, punctuation and letter case untouched.
     *
     * <p><strong>The set is deliberately inconsistent, and the inconsistencies are the
     * contract.</strong> Several of them look like source defects and must nevertheless survive:
     *
     * <ul>
     *   <li>{@link #PROMPT_FOR_CONFIRMATION} has no space after its full stop, while
     *       {@link #INFORM_FAILURE}, which is structured identically, does have one.</li>
     *   <li>{@link #FILE_ERROR_PREFIX} ends in a space.</li>
     *   <li>{@link #EXIT_MESSAGE} carries fourteen trailing spaces and no space after its full
     *       stop.</li>
     *   <li>{@link #CODING_TO_BE_DONE} has a run of four full stops, not three and not an ellipsis
     *       character.</li>
     *   <li>{@link #DATA_WAS_CHANGED} spells "some one" as two words.</li>
     *   <li>{@link #CARD_EXPIRY_MONTH_NOT_VALID} names the bounds as "1 and 12" rather than as two
     *       two-character values, even though the field it validates is two characters wide.</li>
     *   <li>{@link #CARD_EXPIRY_YEAR_NOT_VALID} names no bounds at all, even though the editor that
     *       emits it enforces a range.</li>
     *   <li>{@link #ACCOUNT_FILTER_MUST_BE_11_DIGITS} and {@link #CARD_FILTER_MUST_BE_16_DIGITS} are
     *       upper case where every neighbouring text is mixed case and have no space after their
     *       comma, and the account-side one says "A 11" where English takes "AN".</li>
     * </ul>
     *
     * <p>Altering any of them - evening out the spacing, correcting the grammar, collapsing the run
     * of full stops, dropping the trailing spaces or unifying the letter case - would break the
     * interface contract verification, so none of it is done.
     *
     * <p><strong>Selection belongs to the service.</strong> This holder only declares the vocabulary.
     * Choosing between these texts, deciding which row of the screen a chosen text belongs on and
     * ordering the validations that select them are all the card-update service's work, faithful to
     * the program's own gate, which sets the error text only while that slot is still unset - a
     * first-error-wins rule visible at lines 730 and 743. Nothing here composes, joins, re-renders or
     * chooses a message.
     *
     * <p><strong>Two texts that belong elsewhere.</strong> The program's abend routine emits a
     * default operator text at line 1534; that text is owned by the abend failure type in the
     * failure-carrier package and is deliberately not restated here, because a second declaration
     * would be a second source of truth and importing the first would invert the module's layer
     * direction. The lock-failure text below is likewise this program's own single generic wording:
     * the account-update program declares two more specific variants naming the record kind, and
     * they are neither reused nor unified with this one.
     *
     * @since 1.0.0
     */
    public static final class Messages {

        /**
         * {@code "File Error: "} - program line 135. <strong>The trailing space is part of the
         * value.</strong>
         *
         * <p>In the legacy program this is the leading fragment of a diagnostic that continues with
         * the failing operation name, the file name and two raw response codes. None of that
         * continuation is reproduced: a REST body must not disclose an internal file status code, a
         * data store name, a statement fragment or any other internal detail, so only this sanitized
         * fragment is available to carry. It is offered as a constant rather than a template
         * precisely so that nothing can be appended to it here.
         */
        public static final String FILE_ERROR_PREFIX = "File Error: ";

        /**
         * {@code "Details of selected card shown above"} - program line 161. Informational: the card
         * was fetched and its values are the ones this response carries.
         */
        public static final String FOUND_CARDS_FOR_ACCOUNT = "Details of selected card shown above";

        /**
         * {@code "Please enter Account and Card Number"} - program line 163. Informational: no search
         * key has been supplied yet.
         */
        public static final String PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

        /**
         * {@code "Update card details presented above."} - program line 165. Informational, and it
         * ends in a full stop where the two neighbouring prompts do not.
         */
        public static final String PROMPT_FOR_CHANGES = "Update card details presented above.";

        /**
         * {@code "Changes validated.Press F5 to save"} - program line 167. <strong>No space follows
         * the full stop.</strong>
         *
         * <p>The save gate it names is the fifth function key, which the mapset corroborates: the
         * legend field initialised at mapset line 167 offers save on that key, and the program
         * brightens that legend at line 1316 exactly when this text is showing. The absent space is
         * reproduced; compare {@link #INFORM_FAILURE}, which has one.
         */
        public static final String PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

        /**
         * {@code "Changes committed to database"} - program line 169. The success text.
         */
        public static final String CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

        /**
         * {@code "Changes unsuccessful. Please try again"} - program line 171. <strong>A space does
         * follow the full stop here.</strong>
         *
         * <p>The difference from {@link #PROMPT_FOR_CONFIRMATION}, whose structure is identical, is
         * the contract rather than a defect to be evened out. The program selects this text for both
         * the lock-failure and the update-failure outcomes (lines 1154 and 1156).
         */
        public static final String INFORM_FAILURE = "Changes unsuccessful. Please try again";

        /**
         * {@code "PF03 pressed.Exiting              "} - program line 176. <strong>No space follows
         * the full stop, and the fourteen trailing spaces are part of the value.</strong>
         *
         * <p>Emitted when the operator leaves the screen with the third function key. The trailing
         * spaces come from the way the text is declared against its work field and are carried
         * exactly as declared; a caller that shortens them has changed the contract.
         */
        public static final String EXIT_MESSAGE = "PF03 pressed.Exiting              ";

        /**
         * {@code "Account number not provided"} - program line 178. The blank-field state of the
         * account search field.
         */
        public static final String PROMPT_FOR_ACCOUNT = "Account number not provided";

        /**
         * {@code "Card number not provided"} - program line 180. The blank-field state of the card
         * search field.
         */
        public static final String PROMPT_FOR_CARD = "Card number not provided";

        /**
         * {@code "Card name not provided"} - program line 182. The blank-field state of the embossed
         * name.
         */
        public static final String PROMPT_FOR_NAME = "Card name not provided";

        /**
         * {@code "Card name can only contain alphabets and spaces"} - program line 184.
         *
         * <p>The rule the text states admits spaces, and so does the editor that emits it: the legacy
         * check blanks out every letter and then tests whether anything is left, so an embossed name
         * containing an interior space passes. A stricter letters-only test would reject values the
         * legacy system accepts and would contradict this very text.
         */
        public static final String NAME_MUST_BE_ALPHA =
                "Card name can only contain alphabets and spaces";

        /**
         * {@code "No input received"} - program line 186. Nothing at all was supplied.
         */
        public static final String NO_SEARCH_CRITERIA_RECEIVED = "No input received";

        /**
         * {@code "No change detected with respect to values fetched."} - program line 188. Ends in a
         * full stop.
         *
         * <p>This is the text behind the case-fold consequence described on the enclosing type: the
         * embossed name is upper-folded in place before the before-image is captured at line 1360 and
         * again before the comparison at lines 1503 to 1508, so an edit that differs from the fetched
         * value only in letter case legitimately produces this text rather than an update. That is
         * faithful behaviour, not a defect to be worked around.
         */
        public static final String NO_CHANGES_DETECTED =
                "No change detected with respect to values fetched.";

        /**
         * {@code "Account number must be a non zero 11 digit number"} - program lines 190 and 192.
         *
         * <p><strong>The program declares this identical text twice</strong>, once for the all-zero
         * account number and once for the non-numeric one, so the operator cannot tell the two apart.
         * One constant is declared here because there is one text; the two conditions remain distinct
         * in the service that selects it, and the duplication is recorded as a source observation
         * rather than resolved by inventing a second wording.
         */
        public static final String ACCOUNT_MUST_BE_NON_ZERO_11_DIGITS =
                "Account number must be a non zero 11 digit number";

        /**
         * {@code "Card number if supplied must be a 16 digit number"} - program line 194. The card
         * number is optional as a search key, which is what "if supplied" records.
         */
        public static final String CARD_MUST_BE_16_DIGITS =
                "Card number if supplied must be a 16 digit number";

        /**
         * {@code "Card Active Status must be Y or N"} - program line 196. Note the capitals in
         * "Active" and "Status", which no neighbouring text uses.
         *
         * <p>The two codes it names are the whole vocabulary of the status component, and they are the
         * reason that component is carried as a raw character: a value outside the pair has to round
         * trip rather than be rejected by the response type.
         */
        public static final String CARD_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";

        /**
         * {@code "Card expiry month must be between 1 and 12"} - program line 198.
         *
         * <p>The bounds are named as "1 and 12", not as two-character values, even though the field is
         * two characters wide and an operator typically types a leading zero. Reproduced as declared.
         */
        public static final String CARD_EXPIRY_MONTH_NOT_VALID =
                "Card expiry month must be between 1 and 12";

        /**
         * {@code "Invalid card expiry year"} - program line 200.
         *
         * <p>The text names no range at all, although the editor that emits it enforces one. The
         * silence is reproduced: the range belongs to the service's validation, not to this text.
         */
        public static final String CARD_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

        /**
         * {@code "Did not find this account in cards database"} - program line 202. The account has no
         * cross-reference entry.
         */
        public static final String DID_NOT_FIND_ACCOUNT_IN_CARD_DATA =
                "Did not find this account in cards database";

        /**
         * {@code "Did not find cards for this search condition"} - program line 204. The account and
         * card number combination matched nothing.
         */
        public static final String DID_NOT_FIND_ACCOUNT_CARD_COMBINATION =
                "Did not find cards for this search condition";

        /**
         * {@code "Could not lock record for update"} - program line 206.
         *
         * <p>This program has one generic wording that names no record kind. The account-update
         * program declares two more specific variants that do name one; they belong to that screen's
         * contract and are neither imported nor unified with this text.
         */
        public static final String COULD_NOT_LOCK_FOR_UPDATE = "Could not lock record for update";

        /**
         * {@code "Record changed by some one else. Please review"} - program line 208.
         * <strong>"some one" is two words.</strong>
         *
         * <p>This is the concurrency text, and it is the <em>only</em> way a concurrent change is
         * reported to a client. The legacy program detected the conflict by comparing the values it
         * had fetched against the ones it re-read (lines 1503 to 1508) and then refreshed its
         * before-image and left the write path at line 1518; the migrated equivalent is a version
         * column on the card entity. Both are persistence concerns, which is why the enclosing type
         * carries no version, entity-tag or concurrency component - only this text.
         */
        public static final String DATA_WAS_CHANGED = "Record changed by some one else. Please review";

        /**
         * {@code "Update of record failed"} - program line 210. The record was locked successfully but
         * the write did not succeed.
         */
        public static final String LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

        /**
         * {@code "Error reading Card Data File"} - program line 212. The sanitized read-failure text:
         * it names the business file in operator terms and discloses no status code, data store name
         * or internal detail.
         */
        public static final String CARD_DATA_READ_ERROR = "Error reading Card Data File";

        /**
         * {@code "Looks Good.... so far"} - program line 214. <strong>Four full stops, not three and
         * not an ellipsis character.</strong>
         *
         * <p>The program's own name for this text records that the path emitting it was still
         * unfinished. The text is nevertheless part of the observable contract, so it is reproduced
         * exactly and the run of four is verified rather than tidied.
         */
        public static final String CODING_TO_BE_DONE = "Looks Good.... so far";

        /**
         * {@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"} - program line 745.
         *
         * <p>Upper case where every neighbouring text is mixed case, <strong>no space after the
         * comma</strong>, and "A" where English takes "AN". All three are reproduced. It is emitted
         * from the account editor when the supplied value is not numeric, and it is a distinct text
         * from {@link #ACCOUNT_MUST_BE_NON_ZERO_11_DIGITS} even though both concern the same field.
         */
        public static final String ACCOUNT_FILTER_MUST_BE_11_DIGITS =
                "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

        /**
         * {@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"} - program line 789.
         *
         * <p>The card-side counterpart of the text above, sharing two of its three oddities:
         * <strong>no space after the comma</strong> and upper case throughout. Unlike the
         * account-side text it reads correctly as "A 16". Emitted from the card editor when the
         * supplied value is not numeric.
         */
        public static final String CARD_FILTER_MUST_BE_16_DIGITS =
                "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

        /**
         * {@code "UNEXPECTED DATA SCENARIO"} - program line 1023. Upper case.
         *
         * <p>Emitted when the program reaches a combination of states its own logic does not account
         * for. It is deliberately unspecific and must stay so: making it more informative would
         * disclose internal state that a REST body may not carry.
         */
        public static final String UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

        /**
         * Not instantiable: this type exists only to group the contract texts above.
         */
        private Messages() {
        }
    }
}
