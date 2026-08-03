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
package com.carddemo.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.config.MenuOptionCatalog;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;
import com.carddemo.util.CobolStringUtils;

/**
 * The two CardDemo menu transactions: the main menu the regular user reaches after signing on, and the
 * menu the administrator reaches instead.
 *
 * <p><strong>Legacy authorities.</strong> {@code app/cbl/COMEN01C.cbl} is transaction {@code CM00}, 282
 * lines and 7 procedure paragraphs; {@code app/cbl/COADM01C.cbl} is transaction {@code CA00}, 268 lines
 * and 7 procedure paragraphs. Their option tables are {@code app/cpy/COMEN02Y.cpy}, which declares a
 * count of ten at line 21, and {@code app/cpy/COADM02Y.cpy}, which declares a count of four at line 20.
 * Both tables live in the injected {@code MenuOptionCatalog}; this class holds no copy of either and
 * owns the rules applied to them. Migrated from checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><em>An observation for the record, not a behavioural fact:</em> {@code app/cpy/COADM02Y.cpy}
 * carries a trailer stamp that differs from every other member of the estate,
 * {@code CardDemo_v1.0-26-g42273c1-79} dated 2022-07-20. Nothing here depends on it and the
 * module-wide provenance stamp remains the one cited above.
 *
 * <p><strong>Two entry points, deliberately not one.</strong> {@code CM00} and {@code CA00} are all but
 * identical line for line, and they are still translated as two public operations, because they differ
 * in three ways that a shared parameterised operation would hide. They read different catalogs; the
 * user menu applies an administrator-only gate at {@code app/cbl/COMEN01C.cbl} lines 136 to 143 that
 * the administrator menu has <em>no</em> counterpart to; and their exit paths nominate their own
 * destination defaults. The gate asymmetry is visible in the signatures themselves: only
 * {@link #userMenu(NavigationContext, KeyAction, String, UserType)} takes a signed-on user type,
 * because only the user menu has anything to test one against. The normalisation, the range check and
 * the shape of dispatch <em>are</em> shared, through private methods.
 *
 * <p><strong>The fourteen paragraphs, and where each one went.</strong> Twelve methods carry all
 * fourteen, two of them shared by a semantically identical pair; both source paragraphs are recorded
 * against each shared method.
 *
 * <table border="1">
 * <caption>Paragraph to method mapping</caption>
 * <tr><th>Paragraph</th><th>{@code COMEN01C}</th><th>{@code COADM01C}</th><th>Method</th></tr>
 * <tr><td>{@code MAIN-PARA}</td><td>line 75</td><td>line 75</td>
 *     <td>{@code userMenu} / {@code adminMenu}</td></tr>
 * <tr><td>{@code PROCESS-ENTER-KEY}</td><td>line 115</td><td>line 115</td>
 *     <td>{@code processUserMenuEnterKey} / {@code processAdminMenuEnterKey}</td></tr>
 * <tr><td>{@code RETURN-TO-SIGNON-SCREEN}</td><td>line 170</td><td>line 160</td>
 *     <td>{@code returnToSignOnScreen} (shared)</td></tr>
 * <tr><td>{@code SEND-MENU-SCREEN}</td><td>line 182</td><td>line 172</td>
 *     <td>{@code sendUserMenuScreen} / {@code sendAdminMenuScreen}</td></tr>
 * <tr><td>{@code RECEIVE-MENU-SCREEN}</td><td>line 199</td><td>line 189</td>
 *     <td>{@code receiveUserMenuScreen} / {@code receiveAdminMenuScreen}</td></tr>
 * <tr><td>{@code POPULATE-HEADER-INFO}</td><td>line 212</td><td>line 202</td>
 *     <td>{@code populateHeaderInfo} (shared)</td></tr>
 * <tr><td>{@code BUILD-MENU-OPTIONS}</td><td>line 236</td><td>line 226</td>
 *     <td>{@code buildUserMenuOptions} / {@code buildAdminMenuOptions}</td></tr>
 * </table>
 *
 * <p><strong>The main paragraph.</strong> Both members open by clearing the error switch and blanking
 * the message work field and the screen message field, lines 77 to 80. A zero-length communication area
 * nominates the sign-on program and returns to it, lines 82 to 84. Otherwise the inbound area is adopted
 * at line 86, and a first entry marks the area as a re-entry, blanks the output map and sends, lines 87
 * to 90. A re-entry receives the map and then evaluates the attention key at line 93, in clause order:
 * the enter key is processed, the third program-function key nominates the sign-on program and returns
 * to it at lines 96 to 98, and every other key raises the error switch, takes the common invalid-key
 * message and re-sends, lines 99 to 102. The transaction is then re-armed at lines 107 to 110, which is
 * why a response that sends a screen nominates this same screen's own route as the next one.
 *
 * <p><strong>Option normalisation is four steps and the order is the contract.</strong> The work fields
 * are identical in both members at lines 45 to 47: a two-character alphanumeric field declared
 * {@code JUST RIGHT} at line 45, a two-digit numeric field at line 46 and a binary index at line 47.
 * Inside the enter-key processor, which begins at line 115 in both members, a backward scan finds the
 * last non-blank position at lines 117 to 121; that prefix moves into the right-justified receiver at
 * line 122; every space in the receiver becomes a zero at line 123; the receiver moves into the numeric
 * field at line 124; and the numeric field is echoed back to the output map at line 125. Step three is
 * delegated to {@code CobolStringUtils}, which owns it and whose documentation names these same lines;
 * it is never re-implemented here and no shortcut past the right-justification is taken, because the
 * right-justification is exactly what turns a single typed digit into a zero-filled two-digit value.
 *
 * <p><strong>The range check rejects before it indexes.</strong> Lines 127 to 134 reject a value that
 * is not numeric, or exceeds the catalog's declared count, or is zero, and emit
 * {@link #INVALID_OPTION_MESSAGE} from line 131. The three conditions are evaluated in source order.
 * Both legacy tables are declared larger than they are filled - capacity twelve over ten populated
 * entries in the user table's redefinition at {@code app/cpy/COMEN02Y.cpy} lines 87 to 92, and capacity
 * nine over four in the administrator table's - so an accepted number is always within the population
 * and the unpopulated tail positions are never reached.
 *
 * <p>That guard is also the one place where this translation deliberately stops a legacy fall-through.
 * In the source the range check does <em>not</em> return: control falls into the administrator-only gate,
 * which indexes the table at the very value just rejected. For a rejected value that index is outside
 * the population, so the legacy read is undefined. The outcome is nevertheless identical either way -
 * the gate cannot match an unpopulated read, and the dispatch block below it is guarded by the error
 * switch at line 145 - so short-circuiting loses no behaviour and gains a table access that is always
 * in range.
 *
 * <p><strong>Three inactive source lines stay inactive.</strong> Option 8 of the user table carries a
 * commented-out alternative label at {@code app/cpy/COMEN02Y.cpy} line 69, with the active label on
 * line 70; it is not surfaced, not enabled, and option 8 carries no administrator gate of its own.
 * {@code app/cbl/COMEN01C.cbl} lines 149 and 150 are commented-out moves of the signed-on user
 * identifier and user type into the communication area, so the handed-off state carries whatever those
 * two components already held; {@code app/cbl/COADM01C.cbl} has no such pair. And the administrator
 * menu's placeholder message omits its option name for the same reason, described below.
 *
 * <p><strong>Two branches are provably unreachable and are reproduced anyway.</strong> Deleting either
 * would break the one-to-one paragraph mapping the traceability matrix rests on, and correcting the
 * text either produces would be an unrequested behaviour change.
 *
 * <ol>
 *   <li><em>The administrator-only gate</em>, {@code app/cbl/COMEN01C.cbl} lines 136 to 143, fires when
 *       the signed-on user is a standard user <em>and</em> the selected entry's own user-type code is
 *       the administrator code, emitting the literal at line 140. All ten entries of
 *       {@code app/cpy/COMEN02Y.cpy} carry the standard-user code, so the second condition can never
 *       hold. {@code app/cbl/COADM01C.cbl} has no such gate and none is added.</li>
 *   <li><em>The placeholder message</em>, {@code app/cbl/COMEN01C.cbl} lines 157 to 164 and
 *       {@code app/cbl/COADM01C.cbl} lines 147 to 153, is composed only when the dispatch guard at
 *       {@code app/cbl/COMEN01C.cbl} line 146 and {@code app/cbl/COADM01C.cbl} line 138 finds a target
 *       program name beginning with the suppression literal. No entry of either table names such a
 *       program, so control always transfers and the text is never presented.</li>
 * </ol>
 *
 * <p><strong>The placeholder text is malformed in the user menu, and reproduced malformed.</strong> The
 * composition at {@code app/cbl/COMEN01C.cbl} lines 159 to 163 takes a literal by size, then the option
 * name <em>delimited by space</em>, then a second literal by size. Delimiting a space-filled name by
 * space copies only its first word, and the trailing literal supplies no separating space, so option 1
 * renders as {@code This option Accountis coming soon ...}. No space is inserted and the full name is
 * not substituted.
 *
 * <p>The administrator menu's composition at {@code app/cbl/COADM01C.cbl} lines 149 to 153 differs, and
 * the difference is in the source rather than in this translation: the option name and its delimiter
 * are <em>commented out</em> at lines 150 and 151. Only the two literals are live, so that text reads
 * {@code This option is coming soon ...} - with a separating space, because the leading literal ends
 * with one, and with no option name at all. Both forms are reproduced exactly as their own member
 * writes them; neither is corrected towards the other, and the commented-out name stays inactive on
 * exactly the principle that keeps option 8's alternative label inactive.
 *
 * <p><strong>Dispatch and the exit path.</strong> Dispatch spans {@code app/cbl/COMEN01C.cbl} lines 145
 * to 165 and {@code app/cbl/COADM01C.cbl} lines 137 to 155. When the error switch is clear and the
 * guard permits, the originating transaction identifier and program name are saved into the navigation
 * state, the program-context component is zeroed so the destination screen opens on a first entry, and
 * control transfers to the selected program. The shared exit paragraph at
 * {@code app/cbl/COMEN01C.cbl} line 170 and {@code app/cbl/COADM01C.cbl} line 160 defaults its
 * destination to the sign-on program when nothing is nominated; that default is applied through
 * {@code NavigationService}, which is the single authority for every route this class returns and
 * documents these exact paragraphs. This class declares no route table.
 *
 * <p>Both transfers in that exit paragraph pass <strong>no</strong> communication area, at
 * {@code app/cbl/COMEN01C.cbl} lines 175 to 177 and {@code app/cbl/COADM01C.cbl} lines 165 to 167, so
 * the sign-on program is entered with no prior state. The move that nominates the originating program
 * on the zero-length path, {@code app/cbl/COMEN01C.cbl} line 83, is therefore written and then
 * discarded. It is reproduced because it is what the source does, and the discarding is reproduced too:
 * a sign-on return carries the wholly empty navigation state forward.
 *
 * <p><strong>Authorization moved, and moving it was not optional.</strong> The legacy gate reads the
 * user-type byte out of the communication area, which was server-authored from an authenticated
 * credential read before any branch tested it. A REST client echoes that state and can send any byte,
 * so the signed-on type arrives here as an explicit argument taken from the authenticated principal
 * rather than from the echoed state, in keeping with the reconciliation contract
 * {@code NavigationContext} publishes. An absent type satisfies neither of the legacy condition names
 * and therefore trips no gate, which is the faithful outcome rather than a lenient one.
 *
 * <p><strong>What this class does not do.</strong> It performs no persistence, holds no repository and
 * declares no transaction; it computes no monetary value and slices no fixed-width record; it builds no
 * route table and decodes no attention key; and it adds no option, reorders none and renames none.
 * Screen rendering belongs to the client: this class returns {@code MenuResponse}, which carries the
 * route, the navigation state, the message and its intent, the focus hint, the error switch and the
 * rows to render, and it returns no transport type of any kind.
 *
 * <p>Stateless singleton, safe for unsynchronised concurrent use: every field is {@code final}, three
 * of them references to immutable collaborators, and no catalog contents are copied or cached here.
 */
@Service
public final class MenuService {

    private static final Logger LOG = LoggerFactory.getLogger(MenuService.class);

    /**
     * Transaction identifier of the user main menu, from {@code WS-TRANID} at
     * {@code app/cbl/COMEN01C.cbl} line 37. Saved as the originating identifier when a selection
     * dispatches, at line 147 of the same member.
     */
    public static final String USER_MENU_TRANSACTION_ID = "CM00";

    /**
     * Program name of the user main menu, from {@code WS-PGMNAME} at {@code app/cbl/COMEN01C.cbl}
     * line 36. Saved as the originating program when a selection dispatches, at line 148.
     */
    public static final String USER_MENU_PROGRAM_NAME = "COMEN01C";

    /**
     * Transaction identifier of the administrator menu, from {@code WS-TRANID} at
     * {@code app/cbl/COADM01C.cbl} line 37, saved as the originating identifier at line 139.
     */
    public static final String ADMIN_MENU_TRANSACTION_ID = "CA00";

    /**
     * Program name of the administrator menu, from {@code WS-PGMNAME} at
     * {@code app/cbl/COADM01C.cbl} line 36, saved as the originating program at line 140.
     */
    public static final String ADMIN_MENU_PROGRAM_NAME = "COADM01C";

    /**
     * Program name both members nominate on their way out: on the zero-length path at
     * {@code app/cbl/COMEN01C.cbl} line 83 and {@code app/cbl/COADM01C.cbl} line 83, on the exit key at
     * line 97 of either member, and as the default inside the shared exit paragraph at
     * {@code app/cbl/COMEN01C.cbl} line 173 and {@code app/cbl/COADM01C.cbl} line 163.
     */
    public static final String SIGN_ON_PROGRAM_NAME = "COSGN00C";

    /**
     * Declared width of the option field, in character positions. It is the width of the
     * {@code JUST RIGHT} receiver at line 45 of either member and of the numeric field at line 46, and
     * equally the width of the symbolic map's input and output items {@code OPTIONI} and
     * {@code OPTIONO} at {@code app/cpy-bms/COMEN01.CPY} lines 132 and 254 and at the same lines of
     * {@code app/cpy-bms/COADM01.CPY}. Those widths coincide in the source, and the backward scan at
     * lines 117 to 121 starts from this one, because it is the length of the map item it scans.
     */
    public static final int OPTION_FIELD_WIDTH = 2;

    /**
     * Symbolic name of the screen field input focus belongs on. Both mapsets place the insertion
     * cursor on their option field by attribute, at {@code app/bms/COMEN01.bms} line 145 and at the
     * corresponding definition of {@code app/bms/COADM01.bms}, and neither member ever moves the cursor
     * explicitly, so this is the focus on every path that sends a screen.
     */
    public static final String OPTION_SCREEN_FIELD_ID = "OPTION";

    /**
     * The operator message a rejected option entry produces, from {@code app/cbl/COMEN01C.cbl} line 131
     * and identically {@code app/cbl/COADM01C.cbl} line 131. Reproduced byte for byte: the verb is
     * lower-case and the three full stops carry <strong>no</strong> preceding space, which is what
     * distinguishes this text from the sign-on messages, where the space is present.
     */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The operator message the user menu's administrator-only gate produces, from
     * {@code app/cbl/COMEN01C.cbl} line 140.
     *
     * <p><strong>The trailing space is inside the literal and is part of it.</strong> The source writes
     * three full stops followed by a space, so this value must never be trimmed. It belongs to the user
     * menu alone; {@code app/cbl/COADM01C.cbl} declares no such message because it has no such gate.
     * The gate is documented unreachable - every entry of {@code app/cpy/COMEN02Y.cpy} carries the
     * standard-user code - and the message is published all the same, so a test can assert the exact
     * text of a branch the legacy can never present.
     */
    public static final String ADMIN_ONLY_OPTION_MESSAGE = "No access - Admin Only option... ";

    /**
     * Leading literal of the placeholder message, taken by size at {@code app/cbl/COMEN01C.cbl} line 159
     * and {@code app/cbl/COADM01C.cbl} line 149. It ends with a space, which is the only separating
     * space either composition contains.
     */
    private static final String PLACEHOLDER_MESSAGE_PREFIX = "This option ";

    /**
     * Trailing literal of the placeholder message, taken by size at {@code app/cbl/COMEN01C.cbl} line
     * 162 and {@code app/cbl/COADM01C.cbl} line 152. It begins with a letter and not a space, which is
     * precisely why the user menu's text runs the option's first word into it.
     */
    private static final String PLACEHOLDER_MESSAGE_SUFFIX = "is coming soon ...";

    /** The space character: the blank the backward scan skips and the delimiter that truncates a name. */
    private static final char SPACE = ' ';

    /** Lowest character the class test for a numeric field accepts. */
    private static final char ASCII_ZERO = '0';

    /** Highest character the class test for a numeric field accepts. */
    private static final char ASCII_NINE = '9';

    /** Radix of the digit accumulation that reproduces a move into a two-digit numeric field. */
    private static final int DECIMAL_RADIX = 10;

    /** The value the option field holds when nothing usable was typed, and which the range check rejects. */
    private static final int NO_OPTION_SELECTED = 0;

    /**
     * The first character position of the option field, counting from one as the legacy reference
     * modification does. It is the floor the backward scan at lines 117 to 121 stops on, which is why the
     * scan leaves a wholly blank field reporting position one rather than position zero.
     */
    private static final int FIRST_FIELD_POSITION = 1;

    /**
     * Rendered form of the header date, assembled from three separate moves at
     * {@code app/cbl/COMEN01C.cbl} lines 221 to 226 and {@code app/cbl/COADM01C.cbl} lines 211 to 216
     * into the group item declared at {@code app/cpy/CSDAT01Y.cpy}, which interleaves two literal
     * solidus characters between a two-digit month, day and year. The year is the low-order two digits
     * of a four-digit value, which is what a reduced two-letter year field emits.
     *
     * <p>The locale is pinned rather than defaulted so that the decimal style is fixed: a formatter
     * built on the ambient locale can emit digits outside the ASCII range, and the legacy field is
     * eight single-byte characters.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT);

    /**
     * Rendered form of the header time, assembled from three moves at
     * {@code app/cbl/COMEN01C.cbl} lines 227 to 231 and {@code app/cbl/COADM01C.cbl} lines 217 to 221
     * into the group item at {@code app/cpy/CSDAT01Y.cpy} that interleaves two literal colons. The
     * hundredths component the source also captures is not part of the rendered field. The locale is
     * pinned for the same reason as on the date.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final NavigationService navigationService;

    private final MessageCatalogService messageCatalogService;

    private final MenuOptionCatalog menuOptionCatalog;

    private final Clock clock;

    /**
     * Creates the menu service.
     *
     * <p>Constructor injection throughout, with no field injection and no setter, so the collaborator
     * set is visible at the single point that establishes it and every field can be {@code final}.
     *
     * @param navigationService     resolves every route this class returns, applies the destination
     *                              default of the shared exit paragraph, and owns both dispatch guards
     * @param messageCatalogService supplies the shared invalid-key text at its contractual width
     * @param menuOptionCatalog     publishes the two option tables; the only reference this class makes
     *                              to the configuration layer, and it is by injection rather than by
     *                              static access
     * @param clock                 the module's single clock, from which the header date and time are
     *                              rendered; supplied rather than defaulted so a test can fix the
     *                              rendered header
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public MenuService(final NavigationService navigationService,
                       final MessageCatalogService messageCatalogService,
                       final MenuOptionCatalog menuOptionCatalog,
                       final Clock clock) {
        this.navigationService = Objects.requireNonNull(navigationService,
                "navigationService must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.menuOptionCatalog = Objects.requireNonNull(menuOptionCatalog,
                "menuOptionCatalog must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ------------------------------------------------------------------------------------------
    // MAIN-PARA - app/cbl/COMEN01C.cbl line 75 (CM00) and app/cbl/COADM01C.cbl line 75 (CA00)
    // ------------------------------------------------------------------------------------------

    /**
     * Serves one turn of the user main menu, legacy transaction {@code CM00}. Translates
     * {@code MAIN-PARA} at {@code app/cbl/COMEN01C.cbl} lines 75 to 110.
     *
     * <p>The error switch starts clear and the message work field and screen message field start blank,
     * lines 77 to 80; nothing is retained between turns, so every path below states its own message and
     * its own switch value rather than clearing an inherited one.
     *
     * <p>The branches, in source order. A turn carrying no prior state nominates the sign-on program and
     * returns to it, lines 82 to 84. Otherwise the inbound state is adopted at line 86. A first entry
     * marks the state as a re-entry, blanks the output map and sends, lines 87 to 90 - so no option is
     * echoed and no message is shown. A re-entry receives the map at line 92 and then evaluates the
     * attention key at line 93, in clause order: the enter key is processed at line 95, the third
     * program-function key nominates the sign-on program and returns to it at lines 96 to 98, and every
     * other key raises the error switch, takes the common invalid-key message and re-sends, lines 99 to
     * 102. The invalid-key text arrives at its contractual width and is never trimmed.
     *
     * <p>An absent attention key takes the same arm as any unmapped one. That is faithful: the legacy
     * terminal always supplies an identifier, the attention-key vocabulary declares no default constant,
     * and a value that is neither the enter key nor the third program-function key is exactly what the
     * final clause of the evaluation exists for.
     *
     * @param inboundContext    the navigation state the client echoed. A {@code null} or wholly empty
     *                          state is the zero-length communication area of line 82 and is a real
     *                          state rather than an error
     * @param keyAction         the decoded attention key. Consulted only on a re-entry, because the
     *                          legacy evaluates it only there; may be {@code null}
     * @param submittedOption   the option field exactly as submitted, bounded to the declared field
     *                          width on receipt; may be {@code null}, which is a blank field
     * @param signedOnUserType  the <strong>authenticated</strong> principal's user type, which the
     *                          administrator-only gate tests. Never read from {@code inboundContext},
     *                          because that state is client-echoed and therefore untrusted; may be
     *                          {@code null}, which trips no gate
     * @return the reply, always carrying the ten user-menu rows and either a nominated route or a sent
     *         screen; never {@code null}
     * @throws AbendException if a route this turn must transfer to cannot be resolved, reproducing the
     *                        abend the legacy transfer would have raised
     */
    public MenuResponse userMenu(final NavigationContext inboundContext,
                                 final KeyAction keyAction,
                                 final String submittedOption,
                                 final UserType signedOnUserType) {
        if (navigationService.isNavigationContextAbsent(inboundContext)) {
            // Line 83: the originating program is nominated on a state that the transfer at lines 175
            // to 177 then discards, because that transfer passes no communication area. The write is
            // reproduced, and so is its being discarded.
            final NavigationContext nominated =
                    withOriginatingProgram(NavigationContext.empty(), SIGN_ON_PROGRAM_NAME);
            LOG.debug("User menu entered with no prior navigation state: transaction={}",
                    USER_MENU_TRANSACTION_ID);
            return userMenuTransfer(returnToSignOnScreen(nominated), NavigationContext.empty());
        }
        // Line 86: adopt the inbound communication area.
        if (inboundContext.firstEntry()) {
            // Lines 88 to 90: mark the area as a re-entry, blank the output map, send.
            return sendUserMenuScreen(null, null, null, false, inboundContext.withReEntry());
        }
        final String optionField = receiveUserMenuScreen(submittedOption);
        if (keyAction == KeyAction.ENTER) {
            return processUserMenuEnterKey(optionField, inboundContext, signedOnUserType);
        }
        if (navigationService.isBackNavigationKey(keyAction)) {
            // Line 97: nominate the sign-on program as the destination, then take the exit paragraph.
            final NavigationContext nominated =
                    withNominatedProgram(inboundContext, SIGN_ON_PROGRAM_NAME);
            return userMenuTransfer(returnToSignOnScreen(nominated), NavigationContext.empty());
        }
        // Lines 100 to 102: the final clause of the attention-key evaluation.
        LOG.debug("User menu received an unmapped attention key: keyAction={}", keyAction);
        return sendUserMenuScreen(null, messageCatalogService.invalidKeyMessage(),
                MenuResponse.MessageSeverity.ERROR, true, inboundContext);
    }

    /**
     * Serves one turn of the administrator menu, legacy transaction {@code CA00}. Translates
     * {@code MAIN-PARA} at {@code app/cbl/COADM01C.cbl} lines 75 to 110, whose structure is that of
     * {@link #userMenu(NavigationContext, KeyAction, String, UserType)} line for line.
     *
     * <p><strong>There is no signed-on user type parameter, and that absence is the contract.</strong>
     * {@code app/cbl/COADM01C.cbl} has no administrator-only gate: its option table at
     * {@code app/cpy/COADM02Y.cpy} declares no user-type item for a gate to test, so there is nothing
     * here for such an argument to be tested against. Which callers may reach this menu at all is
     * settled where routes are secured, never by anything this method does.
     *
     * @param inboundContext  the navigation state the client echoed; {@code null} or wholly empty is the
     *                        zero-length communication area of line 82
     * @param keyAction       the decoded attention key, consulted only on a re-entry; may be
     *                        {@code null}, which takes the unmapped-key arm
     * @param submittedOption the option field exactly as submitted; may be {@code null}
     * @return the reply, always carrying the four administrator-menu rows; never {@code null}
     * @throws AbendException if a route this turn must transfer to cannot be resolved
     */
    public MenuResponse adminMenu(final NavigationContext inboundContext,
                                  final KeyAction keyAction,
                                  final String submittedOption) {
        if (navigationService.isNavigationContextAbsent(inboundContext)) {
            // Line 83, then the exit paragraph at line 160 whose transfer passes no communication area.
            final NavigationContext nominated =
                    withOriginatingProgram(NavigationContext.empty(), SIGN_ON_PROGRAM_NAME);
            LOG.debug("Administrator menu entered with no prior navigation state: transaction={}",
                    ADMIN_MENU_TRANSACTION_ID);
            return adminMenuTransfer(returnToSignOnScreen(nominated), NavigationContext.empty());
        }
        if (inboundContext.firstEntry()) {
            return sendAdminMenuScreen(null, null, null, false, inboundContext.withReEntry());
        }
        final String optionField = receiveAdminMenuScreen(submittedOption);
        if (keyAction == KeyAction.ENTER) {
            return processAdminMenuEnterKey(optionField, inboundContext);
        }
        if (navigationService.isBackNavigationKey(keyAction)) {
            final NavigationContext nominated =
                    withNominatedProgram(inboundContext, SIGN_ON_PROGRAM_NAME);
            return adminMenuTransfer(returnToSignOnScreen(nominated), NavigationContext.empty());
        }
        LOG.debug("Administrator menu received an unmapped attention key: keyAction={}", keyAction);
        return sendAdminMenuScreen(null, messageCatalogService.invalidKeyMessage(),
                MenuResponse.MessageSeverity.ERROR, true, inboundContext);
    }

    // ------------------------------------------------------------------------------------------
    // PROCESS-ENTER-KEY - app/cbl/COMEN01C.cbl line 115 and app/cbl/COADM01C.cbl line 115
    // ------------------------------------------------------------------------------------------

    /**
     * Processes an enter key on the user main menu. Translates {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COMEN01C.cbl} lines 115 to 165.
     *
     * <p>Normalisation first, in the five steps the source performs and in that order: the backward scan
     * at lines 117 to 121, the prefix move into the right-justified receiver at line 122, the blank-to-
     * zero fill at line 123 delegated to {@code CobolStringUtils}, the move into the two-digit numeric
     * field at line 124, and the echo back to the output map at line 125.
     *
     * <p>Then the range check at lines 127 to 134, whose three conditions are evaluated in source order:
     * not numeric, above the catalog's declared count, or zero. Any of them raises the error switch,
     * emits {@link #INVALID_OPTION_MESSAGE} and re-sends. The check completes <em>before</em> anything
     * indexes the catalog, so the value that reaches the lookup below is always within the population.
     *
     * <p>Then the administrator-only gate at lines 136 to 143, which is documented unreachable, then the
     * dispatch guard at line 146, and then - unconditionally, outside that guard - the placeholder
     * composition at lines 157 to 164, which is documented unreachable for the same reason the guard is.
     *
     * @param optionField      the option field as received, at exactly {@link #OPTION_FIELD_WIDTH}
     *                         character positions
     * @param context          the adopted navigation state
     * @param signedOnUserType the authenticated principal's user type; may be {@code null}
     * @return the reply this selection produces
     */
    private MenuResponse processUserMenuEnterKey(final String optionField,
                                                 final NavigationContext context,
                                                 final UserType signedOnUserType) {
        // Lines 117 to 121: scan the field backwards for the last non-blank position, floored at one.
        final int lastNonBlankPosition = scanLastNonBlankPosition(optionField);
        // Line 122: move that prefix into the two-character JUST RIGHT receiver declared at line 45.
        final String receivedPrefix = receivedOptionPrefix(optionField, lastNonBlankPosition);
        // Line 123: replace every space in the receiver with a zero. Delegated to the utility layer that
        // owns this primitive; never re-implemented here, and never bypassed by parsing the raw field,
        // because the right-justification is what makes a single typed digit a two-digit value.
        final String optionLexeme =
                CobolStringUtils.rightJustifyZeroFill(receivedPrefix, OPTION_FIELD_WIDTH);
        // Line 124: move the receiver into the two-digit numeric field declared at line 46, carrying
        // with it the class test that line 127 applies to the result.
        final OptionalInt optionNumber = optionNumberOfLexeme(optionLexeme);
        // Line 125: echo the numeric field back to the output map item, so every reply below carries it.
        final String echoedOption = optionLexeme;

        final int declaredCount = menuOptionCatalog.userMenuOptionCount();
        if (isOutsideOptionRange(optionNumber, declaredCount)) {
            LOG.debug("User menu rejected an option entry: declaredCount={} numeric={}",
                    declaredCount, optionNumber.isPresent());
            return sendUserMenuScreen(echoedOption, INVALID_OPTION_MESSAGE,
                    MenuResponse.MessageSeverity.ERROR, true, context);
        }
        final int selectedNumber = optionNumber.getAsInt();
        final MenuOptionCatalog.UserMenuOption selected = menuOptionCatalog
                .findUserOption(selectedNumber)
                .orElseThrow(() -> optionTableAbend(USER_MENU_PROGRAM_NAME, selectedNumber));
        if (navigationService.isAdminOnlyOptionDenied(signedOnUserType, selected.userType())) {
            // Lines 138 to 142: raise the error switch, blank the message field, take the literal at
            // line 140, re-send. Documented unreachable: every entry of app/cpy/COMEN02Y.cpy carries
            // the standard-user code, so the second condition of the gate can never hold.
            LOG.warn("User menu denied an administrator-only option: option={} userType={}",
                    selectedNumber, signedOnUserType);
            return sendUserMenuScreen(echoedOption, ADMIN_ONLY_OPTION_MESSAGE,
                    MenuResponse.MessageSeverity.ERROR, true, context);
        }
        // Line 145: the error switch is clear on every path that reaches here. Line 146: the guard.
        if (!navigationService.isDispatchSuppressed(selected.programName())) {
            return dispatchFromUserMenu(selected, context, signedOnUserType);
        }
        // Lines 157 to 164: blank the message, set the message colour to green, compose the placeholder
        // text, re-send. Reached only when the guard above suppresses dispatch, which no entry of
        // app/cpy/COMEN02Y.cpy can cause - hence documented unreachable, and preserved regardless.
        LOG.debug("User menu option has no program behind it: option={}", selectedNumber);
        return sendUserMenuScreen(echoedOption, userMenuPlaceholderMessage(selected),
                MenuResponse.MessageSeverity.INFORMATIONAL, false, context);
    }

    /**
     * Processes an enter key on the administrator menu. Translates {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COADM01C.cbl} lines 115 to 155.
     *
     * <p>Normalisation and the range check are the same four steps at the same lines 117 to 125 and the
     * same three conditions at lines 127 to 134, differing only in reading the administrator count. What
     * follows differs in two ways, both of them properties of this member rather than of this
     * translation. There is <strong>no administrator-only gate</strong>: nothing stands between the
     * range check at line 134 and the dispatch block that opens at line 137, and none is added. And the
     * placeholder composition at lines 149 to 153 has its option name commented out at lines 150 and
     * 151, so the text it produces carries no name; see {@link #adminMenuPlaceholderMessage()}.
     *
     * @param optionField the option field as received, at exactly {@link #OPTION_FIELD_WIDTH} character
     *                    positions
     * @param context     the adopted navigation state
     * @return the reply this selection produces
     */
    private MenuResponse processAdminMenuEnterKey(final String optionField,
                                                  final NavigationContext context) {
        // Lines 117 to 121: the backward scan, identical to the user menu's.
        final int lastNonBlankPosition = scanLastNonBlankPosition(optionField);
        // Line 122: the prefix move into the JUST RIGHT receiver declared at line 45.
        final String receivedPrefix = receivedOptionPrefix(optionField, lastNonBlankPosition);
        // Line 123: the blank-to-zero fill, delegated to the utility layer exactly as above.
        final String optionLexeme =
                CobolStringUtils.rightJustifyZeroFill(receivedPrefix, OPTION_FIELD_WIDTH);
        // Line 124: the move into the two-digit numeric field declared at line 46.
        final OptionalInt optionNumber = optionNumberOfLexeme(optionLexeme);
        // Line 125: the echo back to the output map item.
        final String echoedOption = optionLexeme;

        final int declaredCount = menuOptionCatalog.adminMenuOptionCount();
        if (isOutsideOptionRange(optionNumber, declaredCount)) {
            LOG.debug("Administrator menu rejected an option entry: declaredCount={} numeric={}",
                    declaredCount, optionNumber.isPresent());
            return sendAdminMenuScreen(echoedOption, INVALID_OPTION_MESSAGE,
                    MenuResponse.MessageSeverity.ERROR, true, context);
        }
        final int selectedNumber = optionNumber.getAsInt();
        final MenuOptionCatalog.AdminMenuOption selected = menuOptionCatalog
                .findAdminOption(selectedNumber)
                .orElseThrow(() -> optionTableAbend(ADMIN_MENU_PROGRAM_NAME, selectedNumber));
        // Line 137: the error switch is clear on every path that reaches here. Line 138: the guard.
        if (!navigationService.isDispatchSuppressed(selected.programName())) {
            return dispatchFromAdminMenu(selected, context);
        }
        // Lines 147 to 153, unconditional outside the guard, and documented unreachable.
        LOG.debug("Administrator menu option has no program behind it: option={}", selectedNumber);
        return sendAdminMenuScreen(echoedOption, adminMenuPlaceholderMessage(),
                MenuResponse.MessageSeverity.INFORMATIONAL, false, context);
    }

    /**
     * Hands control to the program a user-menu selection names. Translates
     * {@code app/cbl/COMEN01C.cbl} lines 147 to 155.
     *
     * <p>The originating transaction identifier and program name are saved at lines 147 and 148, and the
     * program-context component is zeroed at line 151 so the destination screen opens on a first entry.
     * Lines 149 and 150 are commented-out moves of the signed-on user identifier and user type into the
     * communication area: they stay inactive, so both components are carried across exactly as they
     * arrived. The transfer at lines 152 to 155 passes the communication area, which is why this state
     * travels with the reply.
     *
     * @throws AbendException if the selected program name resolves to no reachable destination
     */
    private MenuResponse dispatchFromUserMenu(final MenuOptionCatalog.UserMenuOption selected,
                                              final NavigationContext context,
                                              final UserType signedOnUserType) {
        final NavigationContext handOff = withOriginatingIdentity(context,
                USER_MENU_TRANSACTION_ID, USER_MENU_PROGRAM_NAME);
        final NavigationService.Route target = navigationService
                .resolveMenuDispatch(signedOnUserType, selected.userType(), selected.programName())
                .orElseThrow(() -> unresolvableTargetAbend(USER_MENU_PROGRAM_NAME));
        LOG.debug("User menu dispatching: option={} route={}", selected.number(),
                target.getRouteValue());
        return userMenuTransfer(target, handOff);
    }

    /**
     * Hands control to the program an administrator-menu selection names. Translates
     * {@code app/cbl/COADM01C.cbl} lines 139 to 145: the originating identifiers are saved at lines 139
     * and 140, the program context is zeroed at line 141, and the transfer at lines 142 to 145 passes
     * the communication area. This member has no commented-out identity moves, so there is nothing here
     * corresponding to {@code app/cbl/COMEN01C.cbl} lines 149 and 150.
     *
     * @throws AbendException if the selected program name resolves to no reachable destination
     */
    private MenuResponse dispatchFromAdminMenu(final MenuOptionCatalog.AdminMenuOption selected,
                                               final NavigationContext context) {
        final NavigationContext handOff = withOriginatingIdentity(context,
                ADMIN_MENU_TRANSACTION_ID, ADMIN_MENU_PROGRAM_NAME);
        final NavigationService.Route target = navigationService
                .resolveAdminMenuDispatch(selected.programName())
                .orElseThrow(() -> unresolvableTargetAbend(ADMIN_MENU_PROGRAM_NAME));
        LOG.debug("Administrator menu dispatching: option={} route={}", selected.number(),
                target.getRouteValue());
        return adminMenuTransfer(target, handOff);
    }

    // ------------------------------------------------------------------------------------------
    // RETURN-TO-SIGNON-SCREEN - app/cbl/COMEN01C.cbl line 170 and app/cbl/COADM01C.cbl line 160
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves the destination the shared exit paragraph transfers to. Translates
     * {@code RETURN-TO-SIGNON-SCREEN} at {@code app/cbl/COMEN01C.cbl} lines 170 to 177 and the
     * semantically identical paragraph at {@code app/cbl/COADM01C.cbl} lines 160 to 167; both source
     * paragraphs map here, and the traceability matrix records both against this method.
     *
     * <p>The paragraph tests the destination-program component and substitutes the sign-on program when
     * it nominates nothing, at line 173 and line 163 respectively, then transfers to whatever the
     * component now names. That nominate-then-default rule is applied through {@code NavigationService},
     * whose sign-off resolver documents these exact lines and holds the blank test in the legacy form -
     * spaces or low values, rather than the conventional Java notion of blank. This method therefore
     * declares no default of its own, and this class declares no route table.
     *
     * <p>The two callers differ in which component they wrote first. The zero-length path nominates the
     * <em>originating</em> program at line 83 and leaves the destination component untouched, so the
     * default applies; the exit key nominates the <em>destination</em> program at line 97, so the
     * nomination is honoured and resolves to the same place. Both therefore reach sign-on, which is why
     * no observable difference follows from the distinction - and the distinction is preserved anyway,
     * because it is what the source does.
     *
     * @param context the navigation state as the calling path left it
     * @return the destination to transfer to; never {@code null}
     * @throws AbendException if the destination component names a program that resolves to nothing,
     *                        reproducing the abend the legacy transfer would have raised
     */
    private NavigationService.Route returnToSignOnScreen(final NavigationContext context) {
        final NavigationService.Route target = navigationService.resolveSignOffRoute(context);
        LOG.debug("Menu exit resolved: defaultProgram={} route={}", SIGN_ON_PROGRAM_NAME,
                target.getRouteValue());
        return target;
    }

    // ------------------------------------------------------------------------------------------
    // SEND-MENU-SCREEN - app/cbl/COMEN01C.cbl line 182 and app/cbl/COADM01C.cbl line 172
    // ------------------------------------------------------------------------------------------

    /**
     * Assembles and sends the user main menu. Translates {@code SEND-MENU-SCREEN} at
     * {@code app/cbl/COMEN01C.cbl} lines 182 to 194: the header is populated at line 184, the option rows
     * are built at line 185, the message work field is moved into the screen message field at line 187,
     * and the map is transmitted with an erase at lines 189 to 194.
     *
     * <p>Four header items the paragraph also fills - both title lines and the transaction and program
     * identifiers, at lines 216 to 219 - are constants of the response contract and are filled by its own
     * user-menu factory, so this method supplies only the two items the program computes per interaction.
     *
     * <p>A sent screen re-arms its own transaction at lines 107 to 110, so the nominated next route is
     * this menu's own and the focus hint is the option field.
     *
     * @param echoedOption the option entry echoed back from line 125, or {@code null} when this send
     *                     follows the output-map blanking at line 89 or an unmapped attention key
     * @param message      the message line, or {@code null} when the screen shows none
     * @param severity     what that message means, or {@code null} when there is none
     * @param errorFlag    the state of the error switch declared at line 40
     * @param context      the navigation state to echo back for the next turn
     */
    private MenuResponse sendUserMenuScreen(final String echoedOption,
                                            final String message,
                                            final MenuResponse.MessageSeverity severity,
                                            final boolean errorFlag,
                                            final NavigationContext context) {
        final ScreenHeader header = populateHeaderInfo();
        return MenuResponse.forUserMenu(messageCatalogService.screenTitle01(),
                messageCatalogService.screenTitle02(),
                header.currentDate(), header.currentTime(),
                buildUserMenuOptions(), echoedOption, message, severity, errorFlag,
                OPTION_SCREEN_FIELD_ID, NavigationService.Route.USER_MENU.getRouteValue(), context);
    }

    /**
     * Assembles and sends the administrator menu. Translates {@code SEND-MENU-SCREEN} at
     * {@code app/cbl/COADM01C.cbl} lines 172 to 184, which differs from its user-menu counterpart only
     * in the map and mapset it transmits and in the option table it renders.
     *
     * @param echoedOption the option entry echoed back from line 125, or {@code null}
     * @param message      the message line, or {@code null}
     * @param severity     what that message means, or {@code null}
     * @param errorFlag    the state of the error switch declared at line 40
     * @param context      the navigation state to echo back for the next turn
     */
    private MenuResponse sendAdminMenuScreen(final String echoedOption,
                                             final String message,
                                             final MenuResponse.MessageSeverity severity,
                                             final boolean errorFlag,
                                             final NavigationContext context) {
        final ScreenHeader header = populateHeaderInfo();
        return MenuResponse.forAdminMenu(messageCatalogService.screenTitle01(),
                messageCatalogService.screenTitle02(),
                header.currentDate(), header.currentTime(),
                buildAdminMenuOptions(), echoedOption, message, severity, errorFlag,
                OPTION_SCREEN_FIELD_ID, NavigationService.Route.ADMIN_MENU.getRouteValue(), context);
    }

    /**
     * Builds the reply for a user-menu turn that transfers control instead of sending a screen.
     *
     * <p>No screen is transmitted on a transfer, so no header is rendered and no focus is hinted, and the
     * absence of those items is what distinguishes a transfer from a send. The option rows travel
     * regardless, because the response contract admits exactly one populated option collection on every
     * reply and rejects a response carrying neither.
     */
    private MenuResponse userMenuTransfer(final NavigationService.Route target,
                                          final NavigationContext context) {
        return MenuResponse.forUserMenu(null, null, null, null, buildUserMenuOptions(), null, null,
                null, false, null, target.getRouteValue(), context);
    }

    /**
     * Builds the reply for an administrator-menu turn that transfers control, on the same terms as its
     * user-menu counterpart.
     */
    private MenuResponse adminMenuTransfer(final NavigationService.Route target,
                                           final NavigationContext context) {
        return MenuResponse.forAdminMenu(null, null, null, null, buildAdminMenuOptions(), null, null,
                null, false, null, target.getRouteValue(), context);
    }

    // ------------------------------------------------------------------------------------------
    // RECEIVE-MENU-SCREEN - app/cbl/COMEN01C.cbl line 199 and app/cbl/COADM01C.cbl line 189
    // ------------------------------------------------------------------------------------------

    /**
     * Receives the user main menu's input. Translates {@code RECEIVE-MENU-SCREEN} at
     * {@code app/cbl/COMEN01C.cbl} lines 199 to 207, which reads the map into the symbolic input area.
     *
     * <p>The submitted text is bounded to the declared width of {@code OPTIONI} at
     * {@code app/cpy-bms/COMEN01.CPY} line 132, which is what receiving into a fixed-width item does. A
     * terminal could not have submitted more, and a REST client can, so the bound is applied here.
     *
     * <p>The paragraph also captures a response and reason code at lines 205 and 206. Neither is tested
     * anywhere in the member, so nothing branches on them and nothing is modelled for them.
     *
     * @param submittedOption the option field exactly as submitted; may be {@code null}
     * @return the field image, at exactly {@link #OPTION_FIELD_WIDTH} character positions
     */
    private String receiveUserMenuScreen(final String submittedOption) {
        return optionFieldImage(submittedOption);
    }

    /**
     * Receives the administrator menu's input. Translates {@code RECEIVE-MENU-SCREEN} at
     * {@code app/cbl/COADM01C.cbl} lines 189 to 197, which differs from its user-menu counterpart only
     * in the map and mapset it reads; the input item {@code OPTIONI} at
     * {@code app/cpy-bms/COADM01.CPY} line 132 is declared at the same width.
     *
     * @param submittedOption the option field exactly as submitted; may be {@code null}
     * @return the field image, at exactly {@link #OPTION_FIELD_WIDTH} character positions
     */
    private String receiveAdminMenuScreen(final String submittedOption) {
        return optionFieldImage(submittedOption);
    }

    // ------------------------------------------------------------------------------------------
    // POPULATE-HEADER-INFO - app/cbl/COMEN01C.cbl line 212 and app/cbl/COADM01C.cbl line 202
    // ------------------------------------------------------------------------------------------

    /**
     * Renders the two header items each menu computes per interaction. Translates
     * {@code POPULATE-HEADER-INFO} at {@code app/cbl/COMEN01C.cbl} lines 212 to 231 and the
     * semantically identical paragraph at {@code app/cbl/COADM01C.cbl} lines 202 to 221; both source
     * paragraphs map here, and the traceability matrix records both against this method.
     *
     * <p>The current date and time are taken once, at line 214 and line 204 respectively, and both
     * rendered items derive from that single reading, so a screen cannot show a date and a time taken a
     * moment apart. The date is assembled at lines 221 to 226 and 211 to 216, the time at lines 227 to
     * 231 and 217 to 221.
     *
     * <p>The remaining four items the paragraph fills - both title lines and the transaction and program
     * identifiers, at lines 216 to 219 and 206 to 209 - are fixed for each menu and are supplied by the
     * response contract's own per-menu factory, so they are not carried here.
     *
     * @return the two rendered header items
     */
    private ScreenHeader populateHeaderInfo() {
        final LocalDateTime taken = LocalDateTime.now(clock);
        return new ScreenHeader(HEADER_DATE_FORMAT.format(taken), HEADER_TIME_FORMAT.format(taken));
    }

    // ------------------------------------------------------------------------------------------
    // BUILD-MENU-OPTIONS - app/cbl/COMEN01C.cbl line 236 and app/cbl/COADM01C.cbl line 226
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the user main menu's rows. Translates {@code BUILD-MENU-OPTIONS} at
     * {@code app/cbl/COMEN01C.cbl} lines 236 to 277.
     *
     * <p>The loop at lines 238 and 239 is bounded by the table's declared count and not by its capacity,
     * so exactly the populated entries are rendered. Iterating the catalog reproduces that bound
     * directly, because the catalog publishes exactly the populated count. The evaluation at lines 248 to
     * 275 places each row into its own screen field and carries arms for positions 11 and 12 that the
     * loop bound can never reach; those two arms are dead in the source and have no counterpart here.
     *
     * <p>The row text composed at lines 243 to 246 concatenates the two-digit number, a full stop and a
     * space, and the option label at its declared width. That concatenation is presentation: the response
     * contract carries the number and the label as separate components and leaves their assembly to the
     * client, so no fixed-width row image is built here.
     *
     * @return exactly the populated user rows, in table order
     */
    private List<MenuResponse.UserMenuOption> buildUserMenuOptions() {
        final List<MenuOptionCatalog.UserMenuOption> catalogued = menuOptionCatalog.userMenuOptions();
        final List<MenuResponse.UserMenuOption> rows = new ArrayList<>(catalogued.size());
        for (final MenuOptionCatalog.UserMenuOption option : catalogued) {
            rows.add(new MenuResponse.UserMenuOption(option.number(), option.label()));
        }
        return rows;
    }

    /**
     * Builds the administrator menu's rows. Translates {@code BUILD-MENU-OPTIONS} at
     * {@code app/cbl/COADM01C.cbl} lines 226 to 263, on the same terms as its user-menu counterpart. Its
     * evaluation at lines 238 to 261 carries arms for positions 5 through 10, all of them beyond the
     * declared count of four and therefore dead in the source.
     *
     * @return exactly the populated administrator rows, in table order
     */
    private List<MenuResponse.AdminMenuOption> buildAdminMenuOptions() {
        final List<MenuOptionCatalog.AdminMenuOption> catalogued = menuOptionCatalog.adminMenuOptions();
        final List<MenuResponse.AdminMenuOption> rows = new ArrayList<>(catalogued.size());
        for (final MenuOptionCatalog.AdminMenuOption option : catalogued) {
            rows.add(new MenuResponse.AdminMenuOption(option.number(), option.label()));
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------
    // Option field primitives - the four normalisation steps and the range test
    // ------------------------------------------------------------------------------------------

    /**
     * Produces the option field image the symbolic input item holds after a receive: left-justified,
     * space-filled to {@link #OPTION_FIELD_WIDTH} and truncated on the right beyond it, which is what a
     * move into a fixed-width alphanumeric item does.
     *
     * <p>An absent value is a wholly blank field rather than a failure, because a terminal that submits
     * an untouched field submits spaces. Characters are copied into a receiver rather than sliced out of
     * the sender, so no offset arithmetic over a sliced string is performed anywhere in this class.
     *
     * @param submittedOption the submitted text; may be {@code null}
     * @return exactly {@link #OPTION_FIELD_WIDTH} character positions
     */
    private static String optionFieldImage(final String submittedOption) {
        final char[] field = new char[OPTION_FIELD_WIDTH];
        for (int position = 0; position < OPTION_FIELD_WIDTH; position++) {
            field[position] = SPACE;
        }
        if (submittedOption != null) {
            final int received = Math.min(submittedOption.length(), OPTION_FIELD_WIDTH);
            submittedOption.getChars(0, received, field, 0);
        }
        return new String(field);
    }

    /**
     * Finds the last non-blank position of the option field, counting from one. Reproduces the loop at
     * {@code app/cbl/COMEN01C.cbl} lines 117 to 121 and {@code app/cbl/COADM01C.cbl} lines 117 to 121: it
     * starts from the declared length of the map item, steps backwards, and stops on the first non-blank
     * position <em>or</em> on position one, whichever comes first.
     *
     * <p><strong>The floor at one is behaviour, not defensiveness.</strong> A wholly blank field leaves
     * the index at one rather than at zero, so the prefix taken next is one blank character - which the
     * fill then turns into a zero-filled value that the range check rejects as zero. An index of zero
     * would instead take no characters at all, and the fill would produce the same value by a different
     * route; reproducing the floor keeps the intermediate states identical too.
     *
     * @param optionField the field image, at exactly {@link #OPTION_FIELD_WIDTH} character positions
     * @return the one-based position of the last non-blank character, or one when the field is blank
     */
    private static int scanLastNonBlankPosition(final String optionField) {
        int position = optionField.length();
        while (position > FIRST_FIELD_POSITION && optionField.charAt(position - 1) == SPACE) {
            position--;
        }
        return position;
    }

    /**
     * Takes the leading {@code lastNonBlankPosition} characters of the option field: the reference
     * modification at {@code app/cbl/COMEN01C.cbl} line 122 and {@code app/cbl/COADM01C.cbl} line 122.
     *
     * <p>Characters are copied into a receiver of the required size rather than sliced out of the sender,
     * which mirrors what a move into a fixed-width item does and keeps this class free of string-slicing
     * offset arithmetic.
     *
     * @param optionField          the field image
     * @param lastNonBlankPosition the one-based position the backward scan settled on
     * @return the prefix, which the caller then right-justifies and zero-fills
     */
    private static String receivedOptionPrefix(final String optionField,
                                               final int lastNonBlankPosition) {
        final char[] prefix = new char[lastNonBlankPosition];
        optionField.getChars(0, lastNonBlankPosition, prefix, 0);
        return new String(prefix);
    }

    /**
     * Moves the normalised lexeme into the two-digit numeric field declared at line 46 of either member,
     * and answers the class test that {@code app/cbl/COMEN01C.cbl} line 127 and
     * {@code app/cbl/COADM01C.cbl} line 127 then apply to it. An empty result <em>is</em> the "not
     * numeric" condition of that line.
     *
     * <p>Only the ten ASCII digits are accepted, because that is what the class test for a numeric
     * display field accepts. A general-purpose integer parse would be wrong twice over: it admits a
     * leading sign, which the field cannot hold, and it admits digits from outside the ASCII range, which
     * the field's own characters are not. Accumulating the digits directly avoids both.
     *
     * <p>By the time this runs, the fill at line 123 has already replaced every space, so a blank field
     * arrives here as a well-formed zero rather than as a non-numeric value - which is exactly why the
     * source needs a separate zero test at line 129.
     *
     * @param optionLexeme the normalised lexeme, at exactly {@link #OPTION_FIELD_WIDTH} characters
     * @return the value the numeric field holds, or an empty result when the lexeme is not all digits
     */
    private static OptionalInt optionNumberOfLexeme(final String optionLexeme) {
        int value = 0;
        for (int position = 0; position < optionLexeme.length(); position++) {
            final char character = optionLexeme.charAt(position);
            if (character < ASCII_ZERO || character > ASCII_NINE) {
                return OptionalInt.empty();
            }
            value = value * DECIMAL_RADIX + (character - ASCII_ZERO);
        }
        return OptionalInt.of(value);
    }

    /**
     * Applies the three conditions of the range check at {@code app/cbl/COMEN01C.cbl} lines 127 to 129
     * and {@code app/cbl/COADM01C.cbl} lines 127 to 129, <strong>in source order</strong>: not numeric,
     * then above the table's declared count, then zero. The order is preserved and the evaluation
     * short-circuits, so the numeric value is never read from a lexeme that has no numeric value.
     *
     * <p>The bound is the table's <em>declared count</em> and never its capacity. Both legacy tables are
     * declared larger than they are filled, so testing against capacity would admit a position no entry
     * occupies. Because this test completes before the catalog is consulted, those unpopulated positions
     * are never reached at all.
     *
     * @param optionNumber  the value the numeric field holds, or an empty result when it is not numeric
     * @param declaredCount the count the option table declares
     * @return {@code true} when the entry must be rejected
     */
    private static boolean isOutsideOptionRange(final OptionalInt optionNumber,
                                                final int declaredCount) {
        return optionNumber.isEmpty()
                || optionNumber.getAsInt() > declaredCount
                || optionNumber.getAsInt() == NO_OPTION_SELECTED;
    }

    // ------------------------------------------------------------------------------------------
    // The placeholder message - malformed in one member, name-suppressed in the other, unreachable
    // in both
    // ------------------------------------------------------------------------------------------

    /**
     * Composes the user menu's placeholder message exactly as {@code app/cbl/COMEN01C.cbl} lines 159 to
     * 163 compose it, <strong>malformation included</strong>.
     *
     * <p>The source takes the leading literal by size, then the option name <em>delimited by space</em>,
     * then the trailing literal by size. Delimiting a space-filled name by space transfers only its first
     * word, and the trailing literal begins with a letter, so nothing separates the two: option 1 renders
     * as {@code This option Accountis coming soon ...}. No separating space is inserted and the full name
     * is not substituted, because either change would alter text the source emits.
     *
     * <p>Documented unreachable. The composition is reached only when the guard at line 146 suppresses
     * dispatch, and no entry of {@code app/cpy/COMEN02Y.cpy} names a program that trips that guard.
     *
     * @param selected the selected option, whose name is read at its declared width so that the
     *                 space delimiter behaves as it does over the fixed-width source field
     * @return the composed message
     */
    private static String userMenuPlaceholderMessage(final MenuOptionCatalog.UserMenuOption selected) {
        return PLACEHOLDER_MESSAGE_PREFIX + firstSpaceDelimitedWord(selected.paddedLabel())
                + PLACEHOLDER_MESSAGE_SUFFIX;
    }

    /**
     * Composes the administrator menu's placeholder message exactly as {@code app/cbl/COADM01C.cbl} lines
     * 149 to 153 compose it, which is <strong>not</strong> what the user menu composes.
     *
     * <p>In that member the option name and its delimiter are commented out, at lines 150 and 151, so
     * only the two literals are live. The result therefore carries no option name at all, and it reads
     * correctly - {@code This option is coming soon ...} - because the leading literal already ends with
     * a space. The inactive lines stay inactive on exactly the principle that keeps the alternative label
     * of user option 8 inactive: a commented-out source line is not behaviour. Neither member's text is
     * corrected towards the other's.
     *
     * <p>Documented unreachable, for the same reason as its user-menu counterpart: no entry of
     * {@code app/cpy/COADM02Y.cpy} trips the guard at line 138.
     *
     * @return the composed message
     */
    private static String adminMenuPlaceholderMessage() {
        return PLACEHOLDER_MESSAGE_PREFIX + PLACEHOLDER_MESSAGE_SUFFIX;
    }

    /**
     * Transfers characters up to, but not including, the first space - what a sending item delimited by
     * space contributes to a concatenation.
     *
     * <p>Applied to a name held at its declared width, every name in either table stops at its first
     * internal space, and a hypothetical single-word name would stop at the trailing fill instead. The
     * transfer count is measured and the characters are then copied, so no slice is taken.
     *
     * @param paddedName the option name at its declared field width
     * @return the first space-delimited word
     */
    private static String firstSpaceDelimitedWord(final String paddedName) {
        int transferred = 0;
        while (transferred < paddedName.length() && paddedName.charAt(transferred) != SPACE) {
            transferred++;
        }
        final char[] word = new char[transferred];
        paddedName.getChars(0, transferred, word, 0);
        return new String(word);
    }

    // ------------------------------------------------------------------------------------------
    // Navigation state derivation - the communication-area moves, component for component
    // ------------------------------------------------------------------------------------------

    /**
     * Derives a state whose originating-program component names {@code programName}, reproducing the move
     * at {@code app/cbl/COMEN01C.cbl} line 83 and {@code app/cbl/COADM01C.cbl} line 83.
     */
    private static NavigationContext withOriginatingProgram(final NavigationContext context,
                                                            final String programName) {
        return withRouting(context, context.fromTransactionId(), programName,
                context.toTransactionId(), context.toProgram(), context.programContext());
    }

    /**
     * Derives a state whose destination-program component names {@code programName}, reproducing the move
     * at line 97 of either member. The shared exit paragraph then honours that nomination rather than
     * applying its default.
     */
    private static NavigationContext withNominatedProgram(final NavigationContext context,
                                                          final String programName) {
        return withRouting(context, context.fromTransactionId(), context.fromProgram(),
                context.toTransactionId(), programName, context.programContext());
    }

    /**
     * Derives the state a dispatching menu hands to its destination: the originating transaction
     * identifier and program name are set, from {@code app/cbl/COMEN01C.cbl} lines 147 and 148 and
     * {@code app/cbl/COADM01C.cbl} lines 139 and 140, and the program context is zeroed, from line 151
     * and line 141 respectively, so the destination screen opens on a first entry.
     *
     * <p>Nothing else is touched. In particular the signed-on user identifier and user-type components
     * are carried across unchanged, because the two moves that would have rewritten them are commented
     * out at {@code app/cbl/COMEN01C.cbl} lines 149 and 150 and have no counterpart at all in the other
     * member.
     */
    private static NavigationContext withOriginatingIdentity(final NavigationContext context,
                                                             final String transactionId,
                                                             final String programName) {
        return withRouting(context, transactionId, programName, context.toTransactionId(),
                context.toProgram(), NavigationContext.ProgramContext.ENTER);
    }

    /**
     * Rebuilds a navigation state with new routing components and every other component carried across
     * byte for byte.
     *
     * <p>The single place in this class where all sixteen components are enumerated. The state type
     * offers no per-component derivation for the four routing fields, and enumerating them once here is
     * what keeps a transposed argument from silently corrupting echoed state at three separate call
     * sites.
     */
    private static NavigationContext withRouting(final NavigationContext context,
                                                 final String fromTransactionId,
                                                 final String fromProgram,
                                                 final String toTransactionId,
                                                 final String toProgram,
                                                 final NavigationContext.ProgramContext programContext) {
        return new NavigationContext(
                fromTransactionId,
                fromProgram,
                toTransactionId,
                toProgram,
                context.userId(),
                context.userType(),
                programContext,
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                context.accountId(),
                context.accountStatus(),
                context.cardNumber(),
                context.lastMap(),
                context.lastMapset());
    }

    // ------------------------------------------------------------------------------------------
    // Failure paths - both unreachable while the catalog and the route vocabulary agree
    // ------------------------------------------------------------------------------------------

    /**
     * The abend raised when an option number inside the declared count matches no catalog entry.
     *
     * <p>Unreachable while both tables number their entries contiguously from one, which they do; it
     * exists because the legacy indexes its table by position and so cannot fail to find an entry, and
     * the faithful equivalent of a table that disagrees with its own count is a terminating failure
     * rather than a silently different screen.
     *
     * <p>The reason and message texts are held inside the widths the legacy abend record declares for
     * them, 50 and 72 characters, so that raising this failure cannot itself fail on a width check and
     * report the wrong problem.
     */
    private static AbendException optionTableAbend(final String culprit, final int optionNumber) {
        return new AbendException(AbendException.ONLINE_ABEND_CODE, culprit,
                "MENU OPTION TABLE HOLDS NO SELECTED ENTRY",
                "MENU OPTION " + optionNumber + " NOT FOUND IN TABLE");
    }

    /**
     * The abend raised when a selected option's program name resolves to no reachable destination.
     *
     * <p>Reproduces what the legacy transfer would have done with an unresolvable program name, and
     * matches how the navigation layer treats an unresolvable nomination. Unreachable while every one of
     * the fourteen catalogued program names appears in the route vocabulary, which all fourteen do.
     */
    private static AbendException unresolvableTargetAbend(final String culprit) {
        return new AbendException(AbendException.ONLINE_ABEND_CODE, culprit,
                "XCTL TO UNRESOLVABLE PROGRAM NAME",
                "MENU DISPATCH FAILED FOR PROGRAM " + culprit);
    }

    /**
     * The two header items a menu computes per interaction, rendered exactly as the screen shows them.
     *
     * <p>A value carried between two of this class's own methods rather than part of any contract: the
     * response type receives the two strings separately. Both are text and never temporal types, so the
     * rendered form survives unchanged.
     *
     * @param currentDate the rendered date, from the group item assembled at
     *                    {@code app/cbl/COMEN01C.cbl} lines 221 to 226
     * @param currentTime the rendered time, from the group item assembled at lines 227 to 231
     */
    private record ScreenHeader(String currentDate, String currentTime) {
    }
}
