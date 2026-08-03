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
 * The two CardDemo menu transactions: the main menu a regular user reaches after signing on, and the
 * menu an administrator reaches instead. Legacy authorities are {@code app/cbl/COMEN01C.cbl}
 * (transaction {@code CM00}, ten options) and {@code app/cbl/COADM01C.cbl} (transaction {@code CA00},
 * four options); both option tables live in the injected {@code MenuOptionCatalog} and this class owns
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

    private final MenuOptionCatalog menuOptionCatalog;

    private final Clock clock;

    /**
     * @param navigationService the single authority for the routes this service returns
     * @param messageCatalogService the source of the common message texts the legacy screens emit
     * @param menuOptionCatalog the two legacy option tables
     * @param clock the clock the header date and time are read from, injected so tests can fix it
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
    public MenuResponse userMenu(final NavigationContext inboundContext,
                                 final KeyAction keyAction,
                                 final String submittedOption,
                                 final UserType signedOnUserType) {
        if (navigationService.isNavigationContextAbsent(inboundContext)) {
            final NavigationContext nominated =
                    withOriginatingProgram(NavigationContext.empty(), SIGN_ON_PROGRAM_NAME);
            LOG.debug("User menu entered with no prior navigation state: transaction={}",
                    USER_MENU_TRANSACTION_ID);
            return userMenuTransfer(returnToSignOnScreen(nominated), NavigationContext.empty());
        }
        if (inboundContext.firstEntry()) {
            return sendUserMenuScreen(null, null, null, false, inboundContext.withReEntry());
        }
        final String optionField = receiveUserMenuScreen(submittedOption);
        if (keyAction == KeyAction.ENTER) {
            return processUserMenuEnterKey(optionField, inboundContext, signedOnUserType);
        }
        if (navigationService.isBackNavigationKey(keyAction)) {
            final NavigationContext nominated =
                    withNominatedProgram(inboundContext, SIGN_ON_PROGRAM_NAME);
            return userMenuTransfer(returnToSignOnScreen(nominated), NavigationContext.empty());
        }
        LOG.debug("User menu received an unmapped attention key: keyAction={}", keyAction);
        return sendUserMenuScreen(null, messageCatalogService.invalidKeyMessage(),
                MenuResponse.MessageSeverity.ERROR, true, inboundContext);
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
    public MenuResponse adminMenu(final NavigationContext inboundContext,
                                  final KeyAction keyAction,
                                  final String submittedOption) {
        if (navigationService.isNavigationContextAbsent(inboundContext)) {
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

    private MenuResponse processUserMenuEnterKey(final String optionField,
                                                 final NavigationContext context,
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
                    MenuResponse.MessageSeverity.ERROR, true, context);
        }
        final int selectedNumber = optionNumber.getAsInt();
        final MenuOptionCatalog.UserMenuOption selected = menuOptionCatalog
                .findUserOption(selectedNumber)
                .orElseThrow(() -> optionTableAbend(USER_MENU_PROGRAM_NAME, selectedNumber));
        if (navigationService.isAdminOnlyOptionDenied(signedOnUserType, selected.userType())) {
            LOG.warn("User menu denied an administrator-only option: option={} userType={}",
                    selectedNumber, signedOnUserType);
            return sendUserMenuScreen(echoedOption, ADMIN_ONLY_OPTION_MESSAGE,
                    MenuResponse.MessageSeverity.ERROR, true, context);
        }
        if (!navigationService.isDispatchSuppressed(selected.programName())) {
            return dispatchFromUserMenu(selected, context, signedOnUserType);
        }
        LOG.debug("User menu option has no program behind it: option={}", selectedNumber);
        return sendUserMenuScreen(echoedOption, userMenuPlaceholderMessage(selected),
                MenuResponse.MessageSeverity.INFORMATIONAL, false, context);
    }

    private MenuResponse processAdminMenuEnterKey(final String optionField,
                                                  final NavigationContext context) {
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
                    MenuResponse.MessageSeverity.ERROR, true, context);
        }
        final int selectedNumber = optionNumber.getAsInt();
        final MenuOptionCatalog.AdminMenuOption selected = menuOptionCatalog
                .findAdminOption(selectedNumber)
                .orElseThrow(() -> optionTableAbend(ADMIN_MENU_PROGRAM_NAME, selectedNumber));
        if (!navigationService.isDispatchSuppressed(selected.programName())) {
            return dispatchFromAdminMenu(selected, context);
        }
        LOG.debug("Administrator menu option has no program behind it: option={}", selectedNumber);
        return sendAdminMenuScreen(echoedOption, adminMenuPlaceholderMessage(),
                MenuResponse.MessageSeverity.INFORMATIONAL, false, context);
    }

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

    private NavigationService.Route returnToSignOnScreen(final NavigationContext context) {
        final NavigationService.Route target = navigationService.resolveSignOffRoute(context);
        LOG.debug("Menu exit resolved: defaultProgram={} route={}", SIGN_ON_PROGRAM_NAME,
                target.getRouteValue());
        return target;
    }

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

    private MenuResponse userMenuTransfer(final NavigationService.Route target,
                                          final NavigationContext context) {
        return MenuResponse.forUserMenu(null, null, null, null, buildUserMenuOptions(), null, null,
                null, false, null, target.getRouteValue(), context);
    }

    private MenuResponse adminMenuTransfer(final NavigationService.Route target,
                                           final NavigationContext context) {
        return MenuResponse.forAdminMenu(null, null, null, null, buildAdminMenuOptions(), null, null,
                null, false, null, target.getRouteValue(), context);
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

    private List<MenuResponse.UserMenuOption> buildUserMenuOptions() {
        final List<MenuOptionCatalog.UserMenuOption> catalogued = menuOptionCatalog.userMenuOptions();
        final List<MenuResponse.UserMenuOption> rows = new ArrayList<>(catalogued.size());
        for (final MenuOptionCatalog.UserMenuOption option : catalogued) {
            rows.add(new MenuResponse.UserMenuOption(option.number(), option.label()));
        }
        return rows;
    }

    private List<MenuResponse.AdminMenuOption> buildAdminMenuOptions() {
        final List<MenuOptionCatalog.AdminMenuOption> catalogued = menuOptionCatalog.adminMenuOptions();
        final List<MenuResponse.AdminMenuOption> rows = new ArrayList<>(catalogued.size());
        for (final MenuOptionCatalog.AdminMenuOption option : catalogued) {
            rows.add(new MenuResponse.AdminMenuOption(option.number(), option.label()));
        }
        return rows;
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

    private static String userMenuPlaceholderMessage(final MenuOptionCatalog.UserMenuOption selected) {
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

    private static NavigationContext withOriginatingProgram(final NavigationContext context,
                                                            final String programName) {
        return withRouting(context, context.fromTransactionId(), programName,
                context.toTransactionId(), context.toProgram(), context.programContext());
    }

    private static NavigationContext withNominatedProgram(final NavigationContext context,
                                                          final String programName) {
        return withRouting(context, context.fromTransactionId(), context.fromProgram(),
                context.toTransactionId(), programName, context.programContext());
    }

    private static NavigationContext withOriginatingIdentity(final NavigationContext context,
                                                             final String transactionId,
                                                             final String programName) {
        return withRouting(context, transactionId, programName, context.toTransactionId(),
                context.toProgram(), NavigationContext.ProgramContext.ENTER);
    }

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

    private static AbendException optionTableAbend(final String culprit, final int optionNumber) {
        return new AbendException(AbendException.ONLINE_ABEND_CODE, culprit,
                "MENU OPTION TABLE HOLDS NO SELECTED ENTRY",
                "MENU OPTION " + optionNumber + " NOT FOUND IN TABLE");
    }

    private static AbendException unresolvableTargetAbend(final String culprit) {
        return new AbendException(AbendException.ONLINE_ABEND_CODE, culprit,
                "XCTL TO UNRESOLVABLE PROGRAM NAME",
                "MENU DISPATCH FAILED FOR PROGRAM " + culprit);
    }

    private record ScreenHeader(String currentDate, String currentTime) {
    }
}
