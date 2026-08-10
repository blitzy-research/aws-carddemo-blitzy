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

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;
import com.carddemo.util.CobolStringUtils;

/**
 * The two CardDemo menu transactions: the main menu a regular user reaches after signing on, and the
 * menu an administrator reaches instead. Legacy authorities are {@code app/cbl/COMEN01C.cbl}
 * (transaction {@code CM00}, ten options) and {@code app/cbl/COADM01C.cbl} (transaction {@code CA00},
 * four options); both option tables arrive through the injected {@link MenuOptionSource} and this class owns
 * only the rules applied to them.
 *
 * <p><strong>Two entry points, deliberately not one.</strong> The two programs are almost identical
 * line for line and are still translated separately, because they read different catalogs, nominate
 * their own exit defaults, and differ in a gate the administrator menu has no counterpart to. The
 * asymmetry is visible in the signatures: only {@link #userMenu} takes a signed-on user type, because
 * only the user menu has anything to test one against.
 *
 * <p><strong>Option normalisation order is the contract.</strong> A backward scan finds the last
 * non-blank position, that prefix moves into a right-justified two-character receiver, every space in
 * the receiver becomes a zero, and only then is the value read as a number. The right-justification is
 * exactly what turns a single typed digit into a zero-filled two-digit value, so no shortcut past it
 * is taken; the step itself is delegated to {@code CobolStringUtils}, which owns it.
 *
 * <p><strong>The range check rejects before it indexes.</strong> Non-numeric, above the catalog's
 * declared count, and zero are rejected in source order. This is the one place the translation
 * deliberately stops a legacy fall-through: in the source the check does not return, so control falls
 * into the administrator-only gate and indexes the table at the value just rejected, which is outside
 * the populated range and therefore undefined. The outcome is identical either way - an unpopulated
 * read cannot match the gate and the dispatch below it is guarded by the error switch - so
 * short-circuiting loses no behaviour and keeps every table access in range.
 *
 * <p><strong>Two branches are provably unreachable and are reproduced anyway</strong>, because
 * deleting either would break the one-to-one paragraph mapping the traceability matrix rests on.
 * First, the administrator-only gate fires only when a standard user selects an entry whose own
 * user-type code is the administrator code, and all ten user-menu entries carry the standard code.
 * Second, the placeholder message is composed only when the selected entry names a target program
 * beginning with the suppression literal, and no entry of either table does.
 *
 * <p><strong>The placeholder text is malformed in the user menu, and is reproduced malformed.</strong>
 * Its composition takes a literal by size, then the option name <em>delimited by space</em>, then a
 * second literal by size. Delimiting a space-filled name by space copies only its first word and the
 * trailing literal supplies no separator, so option 1 renders as
 * {@code This option Accountis coming soon ...}. The administrator menu's own composition differs
 * because the source comments its option name out, so that text reads
 * {@code This option is coming soon ...} - with a separating space and no name at all. Both forms are
 * reproduced exactly as their own member writes them and neither is corrected towards the other.
 *
 * <p>Inactive source lines stay inactive on the same principle: the commented-out alternative label on
 * user option 8 is not surfaced, and the two commented-out moves of the signed-on identifier and type
 * into the communication area are not performed, so the handed-off state carries whatever those
 * components already held.
 *
 * <p><strong>Authorization moved, and moving it was not optional.</strong> The legacy gate read the
 * user-type byte out of a communication area the server had authored from an authenticated credential.
 * A REST client echoes that state and can send any byte, so the signed-on type arrives here as an
 * explicit argument taken from the authenticated principal instead. An absent type satisfies neither
 * legacy condition name and therefore trips no gate, which is the faithful outcome rather than a
 * lenient one.
 *
 * <p>This class builds no route table - {@code NavigationService} is the single authority for every
 * route returned - performs no persistence, and returns {@code MenuResponse} rather than any transport
 * type: screen rendering belongs to the client. Stateless singleton, safe for concurrent use.
 */
@Service
public final class MenuService {
    private static final Logger LOG = LoggerFactory.getLogger(MenuService.class);

    public static final String USER_MENU_TRANSACTION_ID = "CM00";

    public static final String USER_MENU_PROGRAM_NAME = "COMEN01C";

    public static final String ADMIN_MENU_TRANSACTION_ID = "CA00";

    public static final String ADMIN_MENU_PROGRAM_NAME = "COADM01C";

    public static final String SIGN_ON_PROGRAM_NAME = "COSGN00C";

    public static final int OPTION_FIELD_WIDTH = 2;

    public static final String OPTION_SCREEN_FIELD_ID = "OPTION";

    /** Operator-facing text of the range check, reproduced character for character. */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * Operator-facing text of the administrator-only gate, reproduced character for character
     * including its trailing space; the gate itself is unreachable with the shipped option table.
     */
    public static final String ADMIN_ONLY_OPTION_MESSAGE = "No access - Admin Only option... ";

    private static final String PLACEHOLDER_MESSAGE_PREFIX = "This option ";

    private static final String PLACEHOLDER_MESSAGE_SUFFIX = "is coming soon ...";

    private static final char SPACE = ' ';

    private static final char ASCII_ZERO = '0';

    private static final char ASCII_NINE = '9';

    private static final int DECIMAL_RADIX = 10;

    private static final int NO_OPTION_SELECTED = 0;

    private static final int FIRST_FIELD_POSITION = 1;

    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT);

    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final NavigationService navigationService;

    private final MessageCatalogService messageCatalogService;

    private final MenuOptionSource menuOptionCatalog;

    private final Clock clock;

    /**
     * @param navigationService the single authority for the routes this service returns
     * @param messageCatalogService the source of the common message texts the legacy screens emit
     * @param menuOptionCatalog the two legacy option tables
     * @param clock the clock the header date and time are read from, injected so tests can fix it
     */
    public MenuService(final NavigationService navigationService,
                       final MessageCatalogService messageCatalogService,
                       final MenuOptionSource menuOptionCatalog,
                       final Clock clock) {
        this.navigationService = Objects.requireNonNull(navigationService,
                "navigationService must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.menuOptionCatalog = Objects.requireNonNull(menuOptionCatalog,
                "menuOptionCatalog must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Handles one turn of transaction {@code CM00}, the main menu.
     *
     * @param inboundContext the navigation state the client echoed back; absent state returns to sign-on
     * @param keyAction the attention key the client reports, evaluated in the legacy clause order
     * @param submittedOption the raw two-character option field, normalised before it is read
     * @param signedOnUserType the type of the authenticated principal - never the echoed value - which
     *                         may be {@code null}, in which case no gate trips
     * @return the route, navigation state, message and rows for the client to render
     */
    public MenuScreen userMenu(final ConversationState inboundContext,
                                 final KeyAction keyAction,
                                 final String submittedOption,
                                 final UserType signedOnUserType) {
        if (navigationService.isConversationStateAbsent(inboundContext)) {
            final ConversationState nominated =
                    withOriginatingProgram(ConversationState.empty(), SIGN_ON_PROGRAM_NAME);
            LOG.debug("User menu entered with no prior navigation state: transaction={}",
                    USER_MENU_TRANSACTION_ID);
            return userMenuTransfer(returnToSignOnScreen(nominated), ConversationState.empty());
        }
        if (inboundContext.firstEntry()) {
            return sendUserMenuScreen(null, null, null, false, inboundContext.withReEntry());
        }
        final String optionField = receiveUserMenuScreen(submittedOption);
        if (keyAction == KeyAction.ENTER) {
            return processUserMenuEnterKey(optionField, inboundContext, signedOnUserType);
        }
        if (navigationService.isBackNavigationKey(keyAction)) {
            final ConversationState nominated =
                    withNominatedProgram(inboundContext, SIGN_ON_PROGRAM_NAME);
            return userMenuTransfer(returnToSignOnScreen(nominated), ConversationState.empty());
        }
        LOG.debug("User menu received an unmapped attention key: keyAction={}", keyAction);
        return sendUserMenuScreen(null, messageCatalogService.invalidKeyMessage(),
                MessageSeverity.ERROR, true, inboundContext);
    }

    /**
     * Handles one turn of transaction {@code CA00}, the administrator menu, which has no administrator
     * gate of its own and therefore takes no signed-on type.
     *
     * @param inboundContext the navigation state the client echoed back; absent state returns to sign-on
     * @param keyAction the attention key the client reports, evaluated in the legacy clause order
     * @param submittedOption the raw two-character option field, normalised before it is read
     * @return the route, navigation state, message and rows for the client to render
     */
    public MenuScreen adminMenu(final ConversationState inboundContext,
                                  final KeyAction keyAction,
                                  final String submittedOption) {
        if (navigationService.isConversationStateAbsent(inboundContext)) {
            final ConversationState nominated =
                    withOriginatingProgram(ConversationState.empty(), SIGN_ON_PROGRAM_NAME);
            LOG.debug("Administrator menu entered with no prior navigation state: transaction={}",
                    ADMIN_MENU_TRANSACTION_ID);
            return adminMenuTransfer(returnToSignOnScreen(nominated), ConversationState.empty());
        }
        if (inboundContext.firstEntry()) {
            return sendAdminMenuScreen(null, null, null, false, inboundContext.withReEntry());
        }
        final String optionField = receiveAdminMenuScreen(submittedOption);
        if (keyAction == KeyAction.ENTER) {
            return processAdminMenuEnterKey(optionField, inboundContext);
        }
        if (navigationService.isBackNavigationKey(keyAction)) {
            final ConversationState nominated =
                    withNominatedProgram(inboundContext, SIGN_ON_PROGRAM_NAME);
            return adminMenuTransfer(returnToSignOnScreen(nominated), ConversationState.empty());
        }
        LOG.debug("Administrator menu received an unmapped attention key: keyAction={}", keyAction);
        return sendAdminMenuScreen(null, messageCatalogService.invalidKeyMessage(),
                MessageSeverity.ERROR, true, inboundContext);
    }

    private MenuScreen processUserMenuEnterKey(final String optionField,
                                                 final ConversationState context,
                                                 final UserType signedOnUserType) {
        final int lastNonBlankPosition = scanLastNonBlankPosition(optionField);
        final String receivedPrefix = receivedOptionPrefix(optionField, lastNonBlankPosition);
        final String optionLexeme =
                CobolStringUtils.rightJustifyZeroFill(receivedPrefix, OPTION_FIELD_WIDTH);
        final OptionalInt optionNumber = optionNumberOfLexeme(optionLexeme);
        final String echoedOption = optionLexeme;

        final int declaredCount = menuOptionCatalog.userMenuOptionCount();
        if (isOutsideOptionRange(optionNumber, declaredCount)) {
            LOG.debug("User menu rejected an option entry: declaredCount={} numeric={}",
                    declaredCount, optionNumber.isPresent());
            return sendUserMenuScreen(echoedOption, INVALID_OPTION_MESSAGE,
                    MessageSeverity.ERROR, true, context);
        }
        final int selectedNumber = optionNumber.getAsInt();
        final MenuOptionSource.UserMenuOption selected = menuOptionCatalog
                .findUserOption(selectedNumber)
                .orElseThrow(() -> optionTableAbend(USER_MENU_PROGRAM_NAME, selectedNumber));
        if (navigationService.isAdminOnlyOptionDenied(signedOnUserType, selected.userType())) {
            LOG.warn("User menu denied an administrator-only option: option={} userType={}",
                    selectedNumber, signedOnUserType);
            return sendUserMenuScreen(echoedOption, ADMIN_ONLY_OPTION_MESSAGE,
                    MessageSeverity.ERROR, true, context);
        }
        if (!navigationService.isDispatchSuppressed(selected.programName())) {
            return dispatchFromUserMenu(selected, context, signedOnUserType);
        }
        LOG.debug("User menu option has no program behind it: option={}", selectedNumber);
        return sendUserMenuScreen(echoedOption, userMenuPlaceholderMessage(selected),
                MessageSeverity.INFORMATIONAL, false, context);
    }

    private MenuScreen processAdminMenuEnterKey(final String optionField,
                                                  final ConversationState context) {
        final int lastNonBlankPosition = scanLastNonBlankPosition(optionField);
        final String receivedPrefix = receivedOptionPrefix(optionField, lastNonBlankPosition);
        final String optionLexeme =
                CobolStringUtils.rightJustifyZeroFill(receivedPrefix, OPTION_FIELD_WIDTH);
        final OptionalInt optionNumber = optionNumberOfLexeme(optionLexeme);
        final String echoedOption = optionLexeme;

        final int declaredCount = menuOptionCatalog.adminMenuOptionCount();
        if (isOutsideOptionRange(optionNumber, declaredCount)) {
            LOG.debug("Administrator menu rejected an option entry: declaredCount={} numeric={}",
                    declaredCount, optionNumber.isPresent());
            return sendAdminMenuScreen(echoedOption, INVALID_OPTION_MESSAGE,
                    MessageSeverity.ERROR, true, context);
        }
        final int selectedNumber = optionNumber.getAsInt();
        final MenuOptionSource.AdminMenuOption selected = menuOptionCatalog
                .findAdminOption(selectedNumber)
                .orElseThrow(() -> optionTableAbend(ADMIN_MENU_PROGRAM_NAME, selectedNumber));
        if (!navigationService.isDispatchSuppressed(selected.programName())) {
            return dispatchFromAdminMenu(selected, context);
        }
        LOG.debug("Administrator menu option has no program behind it: option={}", selectedNumber);
        return sendAdminMenuScreen(echoedOption, adminMenuPlaceholderMessage(),
                MessageSeverity.INFORMATIONAL, false, context);
    }

    private MenuScreen dispatchFromUserMenu(final MenuOptionSource.UserMenuOption selected,
                                              final ConversationState context,
                                              final UserType signedOnUserType) {
        final ConversationState handOff = withOriginatingIdentity(context,
                USER_MENU_TRANSACTION_ID, USER_MENU_PROGRAM_NAME);
        final NavigationService.Route target = navigationService
                .resolveMenuDispatch(signedOnUserType, selected.userType(), selected.programName())
                .orElseThrow(() -> unresolvableTargetAbend(USER_MENU_PROGRAM_NAME));
        LOG.debug("User menu dispatching: option={} route={}", selected.number(),
                target.getRouteValue());
        return userMenuTransfer(target, handOff);
    }

    private MenuScreen dispatchFromAdminMenu(final MenuOptionSource.AdminMenuOption selected,
                                               final ConversationState context) {
        final ConversationState handOff = withOriginatingIdentity(context,
                ADMIN_MENU_TRANSACTION_ID, ADMIN_MENU_PROGRAM_NAME);
        final NavigationService.Route target = navigationService
                .resolveAdminMenuDispatch(selected.programName())
                .orElseThrow(() -> unresolvableTargetAbend(ADMIN_MENU_PROGRAM_NAME));
        LOG.debug("Administrator menu dispatching: option={} route={}", selected.number(),
                target.getRouteValue());
        return adminMenuTransfer(target, handOff);
    }

    private NavigationService.Route returnToSignOnScreen(final ConversationState context) {
        final NavigationService.Route target = navigationService.resolveSignOffRoute(context);
        LOG.debug("Menu exit resolved: defaultProgram={} route={}", SIGN_ON_PROGRAM_NAME,
                target.getRouteValue());
        return target;
    }

    private MenuScreen sendUserMenuScreen(final String echoedOption,
                                            final String message,
                                            final MessageSeverity severity,
                                            final boolean errorFlag,
                                            final ConversationState context) {
        final ScreenHeader header = populateHeaderInfo();
        return new MenuScreen(MenuKind.USER_MENU, messageCatalogService.screenTitle01(),
                messageCatalogService.screenTitle02(),
                header.currentDate(), header.currentTime(),
                buildUserMenuOptions(), echoedOption, message, severity, errorFlag,
                OPTION_SCREEN_FIELD_ID, NavigationService.Route.USER_MENU.getRouteValue(), context);
    }

    private MenuScreen sendAdminMenuScreen(final String echoedOption,
                                             final String message,
                                             final MessageSeverity severity,
                                             final boolean errorFlag,
                                             final ConversationState context) {
        final ScreenHeader header = populateHeaderInfo();
        return new MenuScreen(MenuKind.ADMIN_MENU, messageCatalogService.screenTitle01(),
                messageCatalogService.screenTitle02(),
                header.currentDate(), header.currentTime(),
                buildAdminMenuOptions(), echoedOption, message, severity, errorFlag,
                OPTION_SCREEN_FIELD_ID, NavigationService.Route.ADMIN_MENU.getRouteValue(), context);
    }

    private MenuScreen userMenuTransfer(final NavigationService.Route target,
                                          final ConversationState context) {
        return new MenuScreen(MenuKind.USER_MENU, null, null, null, null, buildUserMenuOptions(),
                null, null, null, false, null, target.getRouteValue(), context);
    }

    private MenuScreen adminMenuTransfer(final NavigationService.Route target,
                                           final ConversationState context) {
        return new MenuScreen(MenuKind.ADMIN_MENU, null, null, null, null, buildAdminMenuOptions(),
                null, null, null, false, null, target.getRouteValue(), context);
    }

    private String receiveUserMenuScreen(final String submittedOption) {
        return optionFieldImage(submittedOption);
    }

    private String receiveAdminMenuScreen(final String submittedOption) {
        return optionFieldImage(submittedOption);
    }

    private ScreenHeader populateHeaderInfo() {
        final LocalDateTime taken = LocalDateTime.now(clock);
        return new ScreenHeader(HEADER_DATE_FORMAT.format(taken), HEADER_TIME_FORMAT.format(taken));
    }

    private List<MenuRow> buildUserMenuOptions() {
        final List<MenuOptionSource.UserMenuOption> catalogued = menuOptionCatalog.userMenuOptions();
        final List<MenuRow> rows = new ArrayList<>(catalogued.size());
        for (final MenuOptionSource.UserMenuOption option : catalogued) {
            rows.add(new MenuRow(option.number(), option.label()));
        }
        return List.copyOf(rows);
    }

    private List<MenuRow> buildAdminMenuOptions() {
        final List<MenuOptionSource.AdminMenuOption> catalogued = menuOptionCatalog.adminMenuOptions();
        final List<MenuRow> rows = new ArrayList<>(catalogued.size());
        for (final MenuOptionSource.AdminMenuOption option : catalogued) {
            rows.add(new MenuRow(option.number(), option.label()));
        }
        return List.copyOf(rows);
    }

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

    private static int scanLastNonBlankPosition(final String optionField) {
        int position = optionField.length();
        while (position > FIRST_FIELD_POSITION && optionField.charAt(position - 1) == SPACE) {
            position--;
        }
        return position;
    }

    private static String receivedOptionPrefix(final String optionField,
                                               final int lastNonBlankPosition) {
        final char[] prefix = new char[lastNonBlankPosition];
        optionField.getChars(0, lastNonBlankPosition, prefix, 0);
        return new String(prefix);
    }

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

    private static boolean isOutsideOptionRange(final OptionalInt optionNumber,
                                                final int declaredCount) {
        return optionNumber.isEmpty()
                || optionNumber.getAsInt() > declaredCount
                || optionNumber.getAsInt() == NO_OPTION_SELECTED;
    }

    private static String userMenuPlaceholderMessage(final MenuOptionSource.UserMenuOption selected) {
        return PLACEHOLDER_MESSAGE_PREFIX + firstSpaceDelimitedWord(selected.paddedLabel())
                + PLACEHOLDER_MESSAGE_SUFFIX;
    }

    private static String adminMenuPlaceholderMessage() {
        return PLACEHOLDER_MESSAGE_PREFIX + PLACEHOLDER_MESSAGE_SUFFIX;
    }

    private static String firstSpaceDelimitedWord(final String paddedName) {
        int transferred = 0;
        while (transferred < paddedName.length() && paddedName.charAt(transferred) != SPACE) {
            transferred++;
        }
        final char[] word = new char[transferred];
        paddedName.getChars(0, transferred, word, 0);
        return new String(word);
    }

    /*
     * Each of the three routing derivations below produces only the routing change it intends. The
     * carried state this service works in is the five-field service-tier form, which holds none of the
     * communication area's identity members - not the echoed user identifier or type, not the customer
     * identifier or name parts, and not the account identifier, status or primary account number - so a
     * derivation cannot carry a client-supplied identity forward as though the server had asserted it.
     * Those members are reconciled against the authenticated principal by the adapter at the API
     * boundary, which is the only place that may assert them.
     */

    private static ConversationState withOriginatingProgram(final ConversationState context,
                                                            final String programName) {
        return context.withOriginatingProgram(programName);
    }

    private static ConversationState withNominatedProgram(final ConversationState context,
                                                          final String programName) {
        return context.withNominatedProgram(programName);
    }

    private static ConversationState withOriginatingIdentity(final ConversationState context,
                                                             final String transactionId,
                                                             final String programName) {
        return context.withOrigin(transactionId, programName);
    }

    /**
     * Builds the abend a selected option with no catalog entry raises, recording it first.
     *
     * <p>Routed through {@link AbendService#onlineAbend} rather than constructed here, and that is the
     * whole change: both of this class's abend paths previously built the exception directly, so a menu
     * dispatch that abended produced <strong>no log record at all</strong> - not the culprit, not the
     * reason, nothing. The exception reached the boundary and was rendered to the caller, and the operator
     * had only the response to work from. The shared method emits the one online abend record, naming the
     * culprit and the reason from the same vocabulary every other abend site uses.
     *
     * <p>The option number travels as the operation rather than inside the terminal message, so it is a
     * named field of the record instead of text embedded in a sentence. It is a small integer this class
     * derived from a validated selection, never a caller's raw input.
     *
     * <p>Called statically because this method must hand the exception back to an
     * {@code Optional.orElseThrow} supplier; see the note on {@link AbendService#onlineAbend}. See
     * {@code docs/decision-log.md} entry DL-312.
     *
     * @param  culprit the legacy member name of the menu program
     * @param  optionNumber the option that had no entry
     * @return the failure to raise, already recorded
     */
    private static AbendException optionTableAbend(final String culprit, final int optionNumber) {
        return AbendService.onlineAbend(culprit,
                "MENU OPTION TABLE HOLDS NO SELECTED ENTRY",
                "MENU OPTION " + optionNumber + " NOT FOUND IN TABLE",
                "SELECT OPTION " + optionNumber,
                null);
    }

    /**
     * Builds the abend an unresolvable dispatch target raises, recording it first.
     *
     * <p>Routed through the shared diagnostic for the reason given on {@link #optionTableAbend}. The
     * culprit is this class's own program name, which is also the only name available: the target that
     * could not be resolved came from the catalog this class reads, so naming it as the resource would
     * repeat the culprit rather than add to it.
     *
     * @param  culprit the legacy member name of the menu program
     * @return the failure to raise, already recorded
     */
    private static AbendException unresolvableTargetAbend(final String culprit) {
        return AbendService.onlineAbend(culprit,
                "XCTL TO UNRESOLVABLE PROGRAM NAME",
                "MENU DISPATCH FAILED FOR PROGRAM " + culprit,
                "XCTL",
                null);
    }

    private record ScreenHeader(String currentDate, String currentTime) {
    }

    /**
     * Which of the two menus a result describes.
     *
     * <p>The two menus are separate transactions reading separate catalogs, and this is what lets one
     * result type serve both without either becoming a special case of the other. The adapter at the
     * API boundary reads it to choose which response shape to build.
     */
    public enum MenuKind {

        /** The main menu a regular user reaches, legacy transaction {@code CM00}. */
        USER_MENU,

        /** The menu an administrator reaches instead, legacy transaction {@code CA00}. */
        ADMIN_MENU
    }

    /**
     * How a message is to be presented, mirroring the two ways the legacy screens carry one.
     *
     * <p>Deliberately separate from the transport contract's own severity enumeration: the API layer
     * maps between them so that this service names no transport type. The two constants exist because
     * the legacy screens distinguish an informational line from an error line, and the error indicator
     * is its own fact carried beside the message rather than inferred from it.
     */
    public enum MessageSeverity {

        /** A message that reports a state rather than a failure. */
        INFORMATIONAL,

        /** A message that reports a failure. */
        ERROR
    }

    /**
     * One presentable menu row: the option number the operator types and the label beside it.
     *
     * <p>Both legacy catalogs reduce to this pair at the point of presentation, which is why one row
     * type serves both menus even though {@link MenuOptionSource} deliberately keeps two entry shapes
     * apart. The distinction that justifies two catalog types - a user entry carries a one-character
     * user-type code and an administrator entry has no such component - is a dispatch concern that this
     * service has already applied by the time a row is built, and it is not presented to the operator.
     *
     * @param number the option number as the catalog declares it
     * @param label the option label, carried verbatim because it is the external screen contract
     */
    public record MenuRow(int number, String label) {
    }

    /**
     * The outcome of one menu turn, expressed entirely in types this service owns.
     *
     * <p>The type is owned by this package rather than by the transport, because a service that returned
     * the REST response record would depend upward on the API package and invert the specification's
     * layering rule. The adapter in the API layer maps this record onto that response, so the mapping
     * happens once, at the boundary, and is testable on its own.
     *
     * <p>The carried state is the five-field service-tier form. The eleven identity and cardholder
     * members of the communication-area contract are not present and are not this service's to supply:
     * the adapter merges the routing change recorded here back onto the record the client echoed and
     * reconciles the identity members against the authenticated principal.
     *
     * @param kind which menu this result describes, never {@code null}
     * @param title01 the first screen title, or {@code null} on a transfer that renders nothing
     * @param title02 the second screen title, or {@code null} on a transfer
     * @param currentDate the header date, or {@code null} on a transfer
     * @param currentTime the header time, or {@code null} on a transfer
     * @param rows the presentable option rows, never {@code null} and never mutable
     * @param echoedOption the normalised option field echoed back, or {@code null}
     * @param message the summary message, or {@code null} when the turn reports nothing
     * @param severity how that message is to be presented, or {@code null} when there is none
     * @param errorFlag the error indicator, carried as its own fact beside the message
     * @param focusScreenFieldId the field input focus belongs on, or {@code null}
     * @param nextRoute the route the client is to call next, never {@code null}
     * @param conversationState the carry-over for the next turn, never {@code null}
     */
    public record MenuScreen(
            MenuKind kind,
            String title01,
            String title02,
            String currentDate,
            String currentTime,
            List<MenuRow> rows,
            String echoedOption,
            String message,
            MessageSeverity severity,
            boolean errorFlag,
            String focusScreenFieldId,
            String nextRoute,
            ConversationState conversationState) {

        /**
         * Normalizes the row collection so the component is never {@code null} and never mutable.
         *
         * <p>A {@code null} collection becomes the empty immutable list and a supplied one is
         * defensively copied, which detaches it from the producer and rejects a {@code null} element.
         * Order and length are preserved exactly: the rows are in catalog order and that order is the
         * order the screen presents them in.
         */
        public MenuScreen {
            rows = (rows == null) ? List.of() : List.copyOf(rows);
        }

        /**
         * Reports whether this result describes the administrator menu.
         *
         * @return {@code true} for the administrator menu
         */
        public boolean adminMenu() {
            return kind == MenuKind.ADMIN_MENU;
        }
    }
}
