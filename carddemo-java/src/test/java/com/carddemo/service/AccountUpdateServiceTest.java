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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.support.TestDataFactory;
import com.carddemo.support.TraceabilityMatrixCensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Surefire unit tests for {@link AccountUpdateService}, the account-update transaction {@code CAUP}.
 *
 * <h2>Provenance and why this suite is load-bearing</h2>
 *
 * <p>Legacy authority {@code app/cbl/COACTUPC.cbl}, read at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The member is 4,236 lines once its carriage
 * returns are stripped - it is CRLF-encoded, so any count taken from it without stripping measures
 * nothing - and it is the single largest translation in the migration at 16.1% of the estate's
 * paragraph total.
 *
 * <p>The paragraph figure is <strong>85</strong>, and it is the member's {@code PROCEDURE DIVISION}
 * paragraph count. That is what the action plan records, what the traceability matrix carries 85 rows
 * for, and what this class covers through the flow the two entry points drive. This suite previously
 * published 88 by adding the member's three {@code IDENTIFICATION DIVISION} entries to it; those three
 * are program metadata rather than procedure units, the matrix carries no row for any of them, and a
 * suite that counts them has silently redefined the frozen 544-row model. They are still surfaced by
 * name through {@code programIdParagraph()}, {@code dateWrittenParagraph()} and
 * {@code dateCompiledParagraph()} and are still asserted below - as metadata, and not as matrix rows.
 *
 * <p>{@code COACTUPC} is the sole includer of four copybooks - the lookup tables, the three-token
 * field-decoration macro, the date cascade and the date work fields - so the estate's entire
 * validation-lookup surface and its entire field-error-decoration surface are reached from this one
 * feature. A thin suite here leaves both surfaces untested everywhere.
 *
 * <h2>Harness</h2>
 *
 * <p>A pure surefire unit test. No Spring context, no container, no database, no port and no network:
 * every one of the twelve collaborators is a Mockito mock, and the clock is fixed. The two behaviours
 * that would normally want a real transaction - optimistic locking and the asymmetric rollback - are
 * proved here through mock behaviour and interaction ordering, which is what isolates them from
 * infrastructure; the real-transaction exercise belongs to the sibling integration tree.
 *
 * <h2>Independent oracle</h2>
 *
 * <p>No expected value in this class is produced by the code it judges. The 39 ordered
 * (component name, screen field identifier) pairs were extracted from the {@code COPY CSSETATY}
 * token substitutions in the legacy member itself and are declared below as literals; every message,
 * every conflict text, every padded catalogue value and every amount is likewise a literal. Nothing is
 * obtained by calling the service, the lookup tables, the message catalogue, the navigation service,
 * the key translator, the string utilities or the decimal codec.
 *
 * <h2>Source defects this suite pins rather than repairs</h2>
 *
 * <ul>
 * <li>Four mislabelled comments. The macro's own leading comment is corrupted and trails a stray
 * {@code ACSHLIM} token; the comments above the last two expansions are transposed, one reading
 * {@code EFT Account Id} above the primary-cardholder expansion and the other {@code Primary Card
 * Holder} above the transfer-account expansion; and a fourth reads {@code State} above the postcode
 * expansion. Every expectation below is bound from the substitution tokens, never from a comment.</li>
 * <li>A duplicated condition name for the cross-reference miss, declared twice, so the second text is
 * unreachable. The first-declared text is what this suite asserts.</li>
 * <li>A misspelled alphanumeric validation flag, rendered without its second {@code A} at four sites.
 * The behaviour is preserved; the Java member is spelled correctly.</li>
 * <li>The all-blank telephone shortcut, whose third condition group tests the area code where the line
 * number belongs.</li>
 * <li>The asymmetric rollback, which is genuine legacy behaviour and not a defect to normalise.</li>
 * <li>21 of the 50 seeded customer rows carry a credit score outside the range this transaction
 * enforces on input. The seed data is never altered and no persistence constraint is added.</li>
 * </ul>
 *
 * @see AccountUpdateService
 * @see AccountUpdateCommand
 * @see AccountUpdateOutcome
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService: transaction CAUP, the translation of COACTUPC")
class AccountUpdateServiceTest {

    /* ==========================================================================================
     * Independent-oracle constants. Every literal below is declared here rather than read from the
     * production classes this suite judges.
     * ========================================================================================== */

    /** The eleven-digit account key the fixtures use. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The nine-digit customer key the cross-reference resolves to. */
    private static final String CUSTOMER_ID = "000000001";

    /** The sixteen-digit card number the cross-reference carries. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A second, higher card number, used to show which row an ordered-first read returns. */
    private static final String HIGHER_CARD_NUMBER = "4111111111119999";

    /**
     * The account group identifier every one of the fifty seeded accounts holds: ten spaces.
     *
     * <p>Written as a repeat rather than as a run of literal spaces so its width is unmistakable. It is
     * never trimmed and never treated as absent.
     */
    private static final String ACCOUNT_GROUP_ID_TEN_SPACES = " ".repeat(10);

    /**
     * The 39 response component names, in the order the 39 macro expansions declare them.
     *
     * <p>Derived from the {@code (TESTVAR1)} substitution tokens between source lines 3208 and 3432,
     * never from the comments above them. Two orderings look wrong and are right: the state code sits
     * between the two address lines, and the postcode precedes the city and the country.
     */
    private static final List<String> EXPECTED_FIELD_NAMES = List.of(
            "accountStatus", "openYear", "openMonth", "openDay", "creditLimit",
            "expiryYear", "expiryMonth", "expiryDay", "cashCreditLimit",
            "reissueYear", "reissueMonth", "reissueDay",
            "currentBalance", "currentCycleCredit", "currentCycleDebit",
            "ssnPart1", "ssnPart2", "ssnPart3",
            "dateOfBirthYear", "dateOfBirthMonth", "dateOfBirthDay",
            "ficoScore", "firstName", "middleName", "lastName",
            "addressLine1", "stateCode", "addressLine2", "zipCode", "city", "countryCode",
            "phone1AreaCode", "phone1Prefix", "phone1LineNumber",
            "phone2AreaCode", "phone2Prefix", "phone2LineNumber",
            "primaryCardHolderIndicator", "eftAccountId");

    /**
     * The 39 seven-character screen field identifiers, in the same order.
     *
     * <p>Derived from the {@code (SCRNVAR2)} substitution tokens. The last two are the transposed pair:
     * {@code ACSPFLG} is the primary-cardholder field and {@code ACSEFTC} the transfer-account field,
     * whatever the comments above them say.
     */
    private static final List<String> EXPECTED_SCREEN_FIELD_IDS = List.of(
            "ACSTTUS", "OPNYEAR", "OPNMON", "OPNDAY", "ACRDLIM",
            "EXPYEAR", "EXPMON", "EXPDAY", "ACSHLIM",
            "RISYEAR", "RISMON", "RISDAY",
            "ACURBAL", "ACRCYCR", "ACRCYDB",
            "ACTSSN1", "ACTSSN2", "ACTSSN3",
            "DOBYEAR", "DOBMON", "DOBDAY",
            "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM",
            "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY",
            "ACSPH1A", "ACSPH1B", "ACSPH1C",
            "ACSPH2A", "ACSPH2B", "ACSPH2C",
            "ACSPFLG", "ACSEFTC");

    /**
     * The 39 macro validation-flag tokens, in the same order.
     *
     * <p>These are the {@code (TESTVAR1)} tokens verbatim, kept so a reader can trace each Java member
     * back to the flag the macro tested.
     */
    private static final List<String> EXPECTED_LEGACY_FLAG_TOKENS = List.of(
            "ACCT-STATUS", "OPEN-YEAR", "OPEN-MONTH", "OPEN-DAY", "CRED-LIMIT",
            "EXPIRY-YEAR", "EXPIRY-MONTH", "EXPIRY-DAY", "CASH-CREDIT-LIMIT",
            "REISSUE-YEAR", "REISSUE-MONTH", "REISSUE-DAY",
            "CURR-BAL", "CURR-CYC-CREDIT", "CURR-CYC-DEBIT",
            "EDIT-US-SSN-PART1", "EDIT-US-SSN-PART2", "EDIT-US-SSN-PART3",
            "DT-OF-BIRTH-YEAR", "DT-OF-BIRTH-MONTH", "DT-OF-BIRTH-DAY",
            "FICO-SCORE", "FIRST-NAME", "MIDDLE-NAME", "LAST-NAME",
            "ADDRESS-LINE-1", "STATE", "ADDRESS-LINE-2", "ZIPCODE", "CITY", "COUNTRY",
            "PHONE-NUM-1A", "PHONE-NUM-1B", "PHONE-NUM-1C",
            "PHONE-NUM-2A", "PHONE-NUM-2B", "PHONE-NUM-2C",
            "PRI-CARDHOLDER", "EFT-ACCOUNT-ID");

    /** The number of macro expansions, counted directly in the legacy member. */
    private static final int DECORATION_SITE_COUNT = 39;

    /** The middle name: decorated at expansion 24, never edited. */
    private static final String FIELD_MIDDLE_NAME = "middleName";

    /** The second address line: decorated at expansion 28, never edited. */
    private static final String FIELD_ADDRESS_LINE_2 = "addressLine2";

    /**
     * The two components the legacy decorates and never validates.
     *
     * <p>Because their flags can never leave the valid state, their two expansion sites fire and mark
     * nothing, which caps the observable mark count at 37 even when every other field is in error.
     */
    private static final List<String> NEVER_VALIDATED_FIELDS =
            List.of(FIELD_MIDDLE_NAME, FIELD_ADDRESS_LINE_2);

    /** Marks obtainable in one pass: the 39 sites less the two that can never fail. */
    private static final int MAX_OBSERVABLE_MARK_COUNT =
            DECORATION_SITE_COUNT - NEVER_VALIDATED_FIELDS.size();

    /* ---- Summary and field message texts, transcribed from the legacy literals ---- */

    private static final String MSG_ACCOUNT_NUMBER_NOT_PROVIDED = "Account number not provided";

    private static final String MSG_ACCOUNT_NUMBER_MALFORMED =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    private static final String MSG_NO_INPUT_RECEIVED = "No input received";

    private static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /** The first of the two identically named cross-reference-miss conditions. The one that binds. */
    private static final String MSG_ACCOUNT_NOT_IN_XREF_FIRST_DECLARED =
            "Did not find this account in account card xref file";

    /** The second declaration of the same condition name, which is therefore unreachable. */
    private static final String MSG_ACCOUNT_NOT_IN_XREF_SHADOWED =
            "Did not find this account in cards database";

    private static final String MSG_ACCOUNT_NOT_IN_MASTER =
            "Did not find this account in account master file";

    private static final String MSG_CUSTOMER_NOT_IN_MASTER =
            "Did not find associated customer in master file";

    private static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    /* ---- The four write-path conflict texts, byte for byte ---- */

    /**
     * The changed-before-update text. {@code some one} is <strong>two words</strong> in the legacy
     * literal; a single-word rendering is a contract break.
     */
    private static final String CONFLICT_MSG_DATA_WAS_CHANGED =
            "Record changed by some one else. Please review";

    private static final String CONFLICT_MSG_UPDATE_FAILED = "Update of record failed";

    private static final String CONFLICT_MSG_COULD_NOT_LOCK_ACCOUNT =
            "Could not lock account record for update";

    private static final String CONFLICT_MSG_COULD_NOT_LOCK_CUSTOMER =
            "Could not lock customer record for update";

    /* ---- Composed message suffixes ---- */

    private static final String SUFFIX_MUST_BE_SUPPLIED = " must be supplied.";
    private static final String SUFFIX_MUST_BE_Y_OR_N = " must be Y or N.";
    private static final String SUFFIX_ALPHABETS_ONLY = " can have alphabets only.";

    /** Source lines 1999 and 2095, claimed only by the two alphanumeric edits the driver never reaches. */
    private static final String SUFFIX_NUMBERS_OR_ALPHABETS_ONLY = " can have numbers or alphabets only.";

    private static final String SUFFIX_MUST_BE_ALL_NUMERIC = " must be all numeric.";
    private static final String SUFFIX_IS_NOT_VALID = " is not valid";
    private static final String SUFFIX_FICO_OUT_OF_RANGE = ": should be between 300 and 850";
    private static final String SUFFIX_STATE_NOT_VALID = ": is not a valid state code";
    private static final String SUFFIX_AREA_CODE_REQUIRED = ": Area code must be supplied.";
    private static final String SUFFIX_AREA_CODE_NOT_3_DIGITS = ": Area code must be A 3 digit number.";
    private static final String SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE =
            ": Not valid North America general purpose area code";
    private static final String SUFFIX_PREFIX_NOT_3_DIGITS = ": Prefix code must be A 3 digit number.";

    /** The legacy literal says {@code Line number code}, with the redundant third word. Preserved. */
    private static final String SUFFIX_LINE_NUMBER_NOT_4_DIGITS =
            ": Line number code must be A 4 digit number.";

    /* ---- Field labels the composed messages are prefixed with ---- */

    private static final String LABEL_ACCOUNT_STATUS = "Account Status";
    private static final String LABEL_FICO_SCORE = "FICO Score";
    private static final String LABEL_FIRST_NAME = "First Name";

    /** Source line 1568, the label the one live call site of the optional alphabetic edit moves. */
    private static final String LABEL_MIDDLE_NAME = "Middle Name";

    /** Source line 1614, where the label move is commented out and no edit follows it. */
    private static final String LABEL_ADDRESS_LINE_2 = "Address Line 2";

    private static final String LABEL_STATE = "State";
    private static final String LABEL_ZIP = "Zip";
    private static final String LABEL_PHONE_NUMBER_1 = "Phone Number 1";
    private static final String LABEL_ADDRESS_LINE_1 = "Address Line 1";

    /* ---- Information messages ---- */

    private static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";
    private static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";
    private static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";
    private static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";
    private static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    /* ---- The catalogue's invalid-key text at its contractual width ---- */

    /** The visible portion of the common invalid-key message: forty characters. */
    private static final String INVALID_KEY_TEXT = "Invalid key pressed. Please see below...";

    /** The declared width of a common message. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** The trailing spaces the padding contributes, which are part of the contract. */
    private static final int INVALID_KEY_TRAILING_SPACES = 10;

    /** The catalogue value exactly as it must cross the boundary: never trimmed. */
    private static final String INVALID_KEY_MESSAGE_PADDED =
            INVALID_KEY_TEXT + " ".repeat(INVALID_KEY_TRAILING_SPACES);

    /* ---- Routes, keys and numeric bounds ---- */

    private static final String ROUTE_ACCOUNT_UPDATE = "account-update";
    private static final String ROUTE_USER_MENU = "user-menu";
    private static final String LEGACY_TRANSACTION_ID = "CAUP";
    private static final String LEGACY_PROGRAM_ID = "COACTUPC";
    private static final String LEGACY_MEMBER = "COACTUPC.cbl";
    private static final String LEGACY_MAP = "CACTUPA";
    private static final String LEGACY_MAPSET = "COACTUP";
    private static final String RESOURCE_ACCOUNT_MASTER = "ACCTDAT";
    private static final String RESOURCE_CUSTOMER_MASTER = "CUSTDAT";

    /** The raw attention identifier for the exit key. */
    private static final String RAW_KEY_EXIT = "DFHPF3";

    /** The high key that folds onto the exit key, so the two are not distinct actions. */
    private static final String RAW_KEY_EXIT_FOLDED = "DFHPF15";

    /** The raw attention identifier for the save key. */
    private static final String RAW_KEY_SAVE = "DFHPF5";

    /** The high key that folds onto the save key. */
    private static final String RAW_KEY_SAVE_FOLDED = "DFHPF17";

    /** An identifier the legacy mapping declares no clause for, so it resolves to nothing at all. */
    private static final String RAW_KEY_UNMAPPED = "DFHPF25";

    /** Legacy attention inputs the mapping recognises. */
    private static final int RECOGNISED_ATTENTION_INPUT_COUNT = 28;

    /** Distinct outcomes those inputs collapse onto, because the high keys fold. */
    private static final int DISTINCT_ATTENTION_OUTCOME_COUNT = 16;

    private static final int FICO_LOWEST_ACCEPTED = 300;
    private static final int FICO_HIGHEST_ACCEPTED = 850;

    /** The message number the date subprogram flags that both callers tolerate in silence. */
    private static final int TOLERATED_DATE_MESSAGE_NUMBER = 2513;

    /** The severity every failing date condition carries. */
    private static final int FAILING_DATE_SEVERITY = 3;

    /* ---- The seeded record image, as literals ---- */

    private static final String SEEDED_STATUS = "Y";
    private static final BigDecimal SEEDED_CURRENT_BALANCE = new BigDecimal("1000.00");
    private static final BigDecimal SEEDED_CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal SEEDED_CASH_CREDIT_LIMIT = new BigDecimal("2000.00");
    private static final BigDecimal SEEDED_CYCLE_CREDIT = new BigDecimal("100.00");
    private static final BigDecimal SEEDED_CYCLE_DEBIT = new BigDecimal("50.00");
    private static final String SEEDED_OPEN_DATE = "2020-01-15";
    private static final String SEEDED_EXPIRY_DATE = "2029-01-15";
    private static final String SEEDED_REISSUE_DATE = "2024-01-15";
    private static final String SEEDED_ZIP = "10001";

    /**
     * The first name one of the fifty seeded customer rows actually holds.
     *
     * <p>It contains an embedded space, which is exactly why the alphabetic predicate must accept one.
     */
    private static final String SEEDED_FIRST_NAME = "Aniya Von";

    private static final String SEEDED_MIDDLE_NAME = "Q";
    private static final String SEEDED_LAST_NAME = "Smith";
    private static final String SEEDED_ADDRESS_LINE_1 = "1 High Street";
    private static final String SEEDED_ADDRESS_LINE_2 = "Flat 2";
    private static final String SEEDED_CITY = "Springfield";
    private static final String SEEDED_STATE = "NY";
    private static final String SEEDED_COUNTRY = "USA";
    private static final String SEEDED_PHONE_1 = "(201)555-0100";
    private static final String SEEDED_PHONE_2 = "(202)555-0101";
    private static final String SEEDED_DATE_OF_BIRTH = "1980-02-03";
    private static final String SEEDED_EFT_ACCOUNT = "1234567890";
    private static final String SEEDED_PRIMARY_CARD_HOLDER = "Y";
    private static final String SEEDED_FICO = "700";

    /** A credit score one of the twenty-one out-of-range seeded rows holds. */
    private static final String SEEDED_OUT_OF_RANGE_FICO = "001";

    /** A before-image proof standing in for the sealed token the response carries. */
    private static final String TOKEN = "before-image-proof";

    /** The proof re-minted over the rewritten records once both writes commit. */
    private static final String REMINTED_TOKEN = "before-image-proof-after-commit";

    /** A well-formed lexeme whose third decimal digit diverges under half-even rounding. */
    private static final String TRUNCATING_AMOUNT_LEXEME = "1234.567";

    /** What truncation toward zero at two places yields; half-even would give 1234.57. */
    private static final BigDecimal TRUNCATED_AMOUNT = new BigDecimal("1234.56");

    /** The scale every monetary component carries, matching the record field it stands for. */
    private static final int MONEY_SCALE = 2;

    /* ==========================================================================================
     * Collaborators. Every repository and every service is a mock; the clock is a fixed value.
     * ========================================================================================== */

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private ValidationLookupService validationLookupService;

    @Mock
    private MessageCatalogService messageCatalogService;

    @Mock
    private NavigationService navigationService;

    @Mock
    private AbendService abendService;

    @Mock
    private AccountConcurrencyTokenService concurrencyTokenService;

    @Mock
    private SensitiveFieldEncryptionService fieldEncryption;

    @Mock
    private OnlineTransactionBoundary transactionBoundary;

    /** A fixed instant, so the date-of-birth comparison and the header are deterministic. */
    private final Clock clock = Clock.fixed(Instant.parse("2024-05-06T07:08:09Z"), ZoneOffset.UTC);

    private AccountUpdateService service;

    /** Captures the service's own diagnostic channel, so log ordering is observable. */
    private ListAppender<ILoggingEvent> logAppender;

    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        this.service = new AccountUpdateService(this.accountRepository, this.customerRepository,
                this.cardCrossReferenceRepository, this.dateValidationService,
                this.validationLookupService, this.messageCatalogService, this.navigationService,
                this.abendService, this.concurrencyTokenService, this.fieldEncryption,
                this.transactionBoundary, this.clock);

        this.serviceLogger = (Logger) LoggerFactory.getLogger(AccountUpdateService.class);
        this.logAppender = new ListAppender<>();
        this.logAppender.start();
        this.serviceLogger.addAppender(this.logAppender);
        this.serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        this.serviceLogger.detachAppender(this.logAppender);
        this.logAppender.stop();
        this.serviceLogger.setLevel(null);
    }

    /* ==========================================================================================
     * Fixtures. The records are built through their own constructors at the widths the layouts
     * declare; the submission is built through a local accumulator so a test can name the one field
     * it is about instead of restating forty-seven components.
     * ========================================================================================== */

    /** The account row as the seed data holds it, group identifier included at its full width. */
    private static Account seededAccount() {
        return new Account(ACCOUNT_ID, SEEDED_STATUS, SEEDED_CURRENT_BALANCE, SEEDED_CREDIT_LIMIT,
                SEEDED_CASH_CREDIT_LIMIT, SEEDED_OPEN_DATE, SEEDED_EXPIRY_DATE, SEEDED_REISSUE_DATE,
                SEEDED_CYCLE_CREDIT, SEEDED_CYCLE_DEBIT, SEEDED_ZIP, ACCOUNT_GROUP_ID_TEN_SPACES);
    }

    /**
     * The customer row as the seed data holds it.
     *
     * <p>Both regulated columns are {@code null}, exactly as they are in all fifty seeded rows: the
     * national identifier is the schema's only nullable column and absence must be tolerated end to end.
     */
    private static Customer seededCustomer() {
        return seededCustomer(SEEDED_FICO);
    }

    /**
     * The same row with a nominated credit score, so a stored value outside the input range can be
     * loaded without the loader objecting.
     *
     * @param  ficoScore the stored score, which may be outside the range the input edit enforces
     * @return the customer row
     */
    private static Customer seededCustomer(final String ficoScore) {
        return new Customer(CUSTOMER_ID, SEEDED_FIRST_NAME, SEEDED_MIDDLE_NAME, SEEDED_LAST_NAME,
                SEEDED_ADDRESS_LINE_1, SEEDED_ADDRESS_LINE_2, SEEDED_CITY, SEEDED_STATE,
                SEEDED_COUNTRY, SEEDED_ZIP, SEEDED_PHONE_1, SEEDED_PHONE_2, null, null,
                SEEDED_DATE_OF_BIRTH, SEEDED_EFT_ACCOUNT, SEEDED_PRIMARY_CARD_HOLDER, ficoScore);
    }

    private static CardCrossReference seededCrossReference() {
        return new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
    }

    /** A re-entered navigation record, which is the state the decoration macro fires in. */
    private static ScreenNavigationState reEntered() {
        return ScreenNavigationState.empty().withReEntry();
    }

    /**
     * One submission, accumulated field by field.
     *
     * <p>Every component defaults to the value the seeded records hold, so a turn built with no change
     * at all reads as unchanged and the edits are skipped - which is what the legacy does. A test then
     * names only the components it is about. Records are positional, so an accumulator is the only way
     * to keep forty-seven components legible.
     */
    private static final class Turn {

        private String accountId = ACCOUNT_ID;
        private String accountStatus = SEEDED_STATUS;
        private String openYear = "2020";
        private String openMonth = "01";
        private String openDay = "15";
        private String creditLimit = "5000.00";
        private String expiryYear = "2029";
        private String expiryMonth = "01";
        private String expiryDay = "15";
        private String cashCreditLimit = "2000.00";
        private String reissueYear = "2024";
        private String reissueMonth = "01";
        private String reissueDay = "15";
        private String currentBalance = "1000.00";
        private String currentCycleCredit = "100.00";
        private String accountGroupId = ACCOUNT_GROUP_ID_TEN_SPACES;
        private String currentCycleDebit = "50.00";
        private String customerId = CUSTOMER_ID;
        private String ssnPart1;
        private String ssnPart2;
        private String ssnPart3;
        private String dateOfBirthYear = "1980";
        private String dateOfBirthMonth = "02";
        private String dateOfBirthDay = "03";
        private String ficoScore = SEEDED_FICO;
        private String firstName = SEEDED_FIRST_NAME;
        private String middleName = SEEDED_MIDDLE_NAME;
        private String lastName = SEEDED_LAST_NAME;
        private String addressLine1 = SEEDED_ADDRESS_LINE_1;
        private String stateCode = SEEDED_STATE;
        private String addressLine2 = SEEDED_ADDRESS_LINE_2;
        private String zipCode = SEEDED_ZIP;
        private String city = SEEDED_CITY;
        private String countryCode = SEEDED_COUNTRY;
        private String phone1AreaCode = "201";
        private String phone1Prefix = "555";
        private String phone1LineNumber = "0100";
        private String governmentIssuedId;
        private String phone2AreaCode = "202";
        private String phone2Prefix = "555";
        private String phone2LineNumber = "0101";
        private String eftAccountId = SEEDED_EFT_ACCOUNT;
        private String primaryCardHolderIndicator = SEEDED_PRIMARY_CARD_HOLDER;
        private KeyAction keyAction = KeyAction.ENTER;
        private ScreenNavigationState navigationContext = reEntered();
        private String concurrencyToken = TOKEN;
        private boolean protectedValuesWithheld;

        private Turn accountId(final String value) {
            this.accountId = value;
            return this;
        }

        private Turn accountStatus(final String value) {
            this.accountStatus = value;
            return this;
        }

        private Turn openDate(final String year, final String month, final String day) {
            this.openYear = year;
            this.openMonth = month;
            this.openDay = day;
            return this;
        }

        private Turn creditLimit(final String value) {
            this.creditLimit = value;
            return this;
        }

        private Turn cashCreditLimit(final String value) {
            this.cashCreditLimit = value;
            return this;
        }

        private Turn currentBalance(final String value) {
            this.currentBalance = value;
            return this;
        }

        private Turn currentCycleCredit(final String value) {
            this.currentCycleCredit = value;
            return this;
        }

        private Turn currentCycleDebit(final String value) {
            this.currentCycleDebit = value;
            return this;
        }

        private Turn expiryDate(final String year, final String month, final String day) {
            this.expiryYear = year;
            this.expiryMonth = month;
            this.expiryDay = day;
            return this;
        }

        private Turn reissueDate(final String year, final String month, final String day) {
            this.reissueYear = year;
            this.reissueMonth = month;
            this.reissueDay = day;
            return this;
        }

        private Turn accountGroupId(final String value) {
            this.accountGroupId = value;
            return this;
        }

        private Turn customerId(final String value) {
            this.customerId = value;
            return this;
        }

        private Turn nationalIdentifier(final String part1, final String part2, final String part3) {
            this.ssnPart1 = part1;
            this.ssnPart2 = part2;
            this.ssnPart3 = part3;
            return this;
        }

        private Turn dateOfBirth(final String year, final String month, final String day) {
            this.dateOfBirthYear = year;
            this.dateOfBirthMonth = month;
            this.dateOfBirthDay = day;
            return this;
        }

        private Turn ficoScore(final String value) {
            this.ficoScore = value;
            return this;
        }

        private Turn firstName(final String value) {
            this.firstName = value;
            return this;
        }

        private Turn middleName(final String value) {
            this.middleName = value;
            return this;
        }

        private Turn lastName(final String value) {
            this.lastName = value;
            return this;
        }

        private Turn addressLine1(final String value) {
            this.addressLine1 = value;
            return this;
        }

        private Turn stateCode(final String value) {
            this.stateCode = value;
            return this;
        }

        private Turn addressLine2(final String value) {
            this.addressLine2 = value;
            return this;
        }

        private Turn zipCode(final String value) {
            this.zipCode = value;
            return this;
        }

        private Turn city(final String value) {
            this.city = value;
            return this;
        }

        private Turn countryCode(final String value) {
            this.countryCode = value;
            return this;
        }

        private Turn phone1(final String areaCode, final String prefix, final String lineNumber) {
            this.phone1AreaCode = areaCode;
            this.phone1Prefix = prefix;
            this.phone1LineNumber = lineNumber;
            return this;
        }

        private Turn phone2(final String areaCode, final String prefix, final String lineNumber) {
            this.phone2AreaCode = areaCode;
            this.phone2Prefix = prefix;
            this.phone2LineNumber = lineNumber;
            return this;
        }

        private Turn governmentIssuedId(final String value) {
            this.governmentIssuedId = value;
            return this;
        }

        private Turn eftAccountId(final String value) {
            this.eftAccountId = value;
            return this;
        }

        private Turn primaryCardHolderIndicator(final String value) {
            this.primaryCardHolderIndicator = value;
            return this;
        }

        private Turn keyAction(final KeyAction value) {
            this.keyAction = value;
            return this;
        }

        private Turn navigationContext(final ScreenNavigationState value) {
            this.navigationContext = value;
            return this;
        }

        private Turn concurrencyToken(final String value) {
            this.concurrencyToken = value;
            return this;
        }

        private AccountUpdateCommand build() {
            return new AccountUpdateCommand(this.accountId, this.accountStatus, this.openYear,
                    this.openMonth, this.openDay, this.creditLimit, this.expiryYear,
                    this.expiryMonth, this.expiryDay, this.cashCreditLimit, this.reissueYear,
                    this.reissueMonth, this.reissueDay, this.currentBalance,
                    this.currentCycleCredit, this.accountGroupId, this.currentCycleDebit,
                    this.customerId, this.ssnPart1, this.ssnPart2, this.ssnPart3,
                    this.dateOfBirthYear, this.dateOfBirthMonth, this.dateOfBirthDay,
                    this.ficoScore, this.firstName, this.middleName, this.lastName,
                    this.addressLine1, this.stateCode, this.addressLine2, this.zipCode, this.city,
                    this.countryCode, this.phone1AreaCode, this.phone1Prefix,
                    this.phone1LineNumber, this.governmentIssuedId, this.phone2AreaCode,
                    this.phone2Prefix, this.phone2LineNumber, this.eftAccountId,
                    this.primaryCardHolderIndicator, this.keyAction, this.navigationContext,
                    this.concurrencyToken, this.protectedValuesWithheld);
        }
    }

    /** A turn that mirrors the seeded records exactly, so nothing reads as changed. */
    private static Turn unchangedTurn() {
        return new Turn();
    }

    /**
     * A turn that differs from the seeded records and passes every edit.
     *
     * <p>The three national-identifier parts carry the difference. They have to be supplied whatever
     * the difference is - all three are mandatory numeric edits - and the seeded rows hold no national
     * identifier, so supplying them is itself the change. Nothing else has to move.
     */
    private static Turn cleanChangedTurn() {
        return new Turn().nationalIdentifier("123", "45", "6789");
    }

    /* ==========================================================================================
     * Stub helpers. Each stubs exactly one seam, so a test declares only the seams it exercises and
     * strict stubbing keeps the declarations honest.
     * ========================================================================================== */

    /** The three reads the detail path performs, in the order it performs them. */
    private void stubSeededReads() {
        stubSeededReads(seededCustomer());
    }

    private void stubSeededReads(final Customer customer) {
        when(this.cardCrossReferenceRepository
                .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(seededCrossReference()));
        when(this.accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(seededAccount()));
        when(this.customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
    }

    /** The date cascade, answering that every component of every group is good. */
    private void stubAcceptingDateCascade() {
        when(this.dateValidationService.validateCcyymmddDate(any(), any()))
                .thenReturn(acceptingDateResult());
    }

    /** The separate date-of-birth entry point, reached only when the group came back valid. */
    private void stubAcceptingDateOfBirth() {
        when(this.dateValidationService.validateDateOfBirth(any(), any(), any()))
                .thenReturn(acceptingDateResult());
    }

    /** Both telephone area codes are general-purpose. */
    private void stubAcceptingAreaCodes() {
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(true);
    }

    /** The flat fifty-six-code membership test answers yes for the seeded state. */
    private void stubAcceptingStateCode() {
        when(this.validationLookupService.isValidUsStateCode(SEEDED_STATE)).thenReturn(true);
    }

    /** The two-hundred-and-forty-entry composite test answers yes for the seeded pairing. */
    private void stubAcceptingStateZipComposite() {
        when(this.validationLookupService.isValidUsStateZipCodeCombination(any())).thenReturn(true);
    }

    /**
     * Every seam except the two postal ones, which the postal tests stub for themselves because they
     * are the seams under examination there.
     */
    private void stubCommonAcceptingEdits() {
        stubAcceptingDateCascade();
        stubAcceptingDateOfBirth();
        stubAcceptingAreaCodes();
    }

    /** Every edit of a clean changed turn passes. */
    private void stubAllEditsAccepting() {
        stubCommonAcceptingEdits();
        stubAcceptingStateCode();
        stubAcceptingStateZipComposite();
    }

    /**
     * The independent transaction, running its callback inline.
     *
     * <p>The boundary is a mock like every other collaborator, so the rewrite pair executes on the
     * calling thread and a failure raised inside it propagates to the arm under test. That is what makes
     * the ordered-rewrite behaviour observable without a database.
     */
    private void stubBoundaryRunsInline() {
        when(this.transactionBoundary.execute(any()))
                .thenAnswer(invocation -> invocation.<Supplier<Boolean>>getArgument(0).get());
    }

    /** A cascade outcome in which all three components passed and nothing was claimed. */
    private static DateValidationService.DateEditResult acceptingDateResult() {
        return new DateValidationService.DateEditResult(false,
                DateValidationService.DateEditFlag.VALID,
                DateValidationService.DateEditFlag.VALID,
                DateValidationService.DateEditFlag.VALID, "");
    }

    /**
     * A cascade outcome in which every component was supplied and rejected.
     *
     * @param  returnMessage the text the cascade accumulated
     * @return the rejecting outcome
     */
    private static DateValidationService.DateEditResult rejectingDateResult(
            final String returnMessage) {
        return new DateValidationService.DateEditResult(true,
                DateValidationService.DateEditFlag.NOT_OK,
                DateValidationService.DateEditFlag.NOT_OK,
                DateValidationService.DateEditFlag.NOT_OK, returnMessage);
    }

    /* ==========================================================================================
     * Assertion helpers.
     * ========================================================================================== */

    private static List<String> screenFieldIdsOf(final AccountUpdateOutcome outcome) {
        return outcome.fieldErrors().stream().map(ValidationException.FieldError::bmsFieldId).toList();
    }

    private static List<String> fieldNamesOf(final AccountUpdateOutcome outcome) {
        return outcome.fieldErrors().stream().map(ValidationException.FieldError::field).toList();
    }

    private static ValidationException.FieldError errorFor(final AccountUpdateOutcome outcome,
            final String screenFieldId) {
        return outcome.fieldErrors().stream()
                .filter(error -> screenFieldId.equals(error.bmsFieldId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no field error was reported against screen field " + screenFieldId));
    }

    /* ==========================================================================================
     * Reaching the three edits no production call site routes to.
     * ========================================================================================== */

    /**
     * The observable result of running one of the three never-reached character-class edits.
     *
     * @param flag       the three-state field flag the edit left behind
     * @param message    the composed per-field text, or {@code null} when the edit reported no failure
     * @param inputError whether the edit set the member's own input-error condition
     */
    private record UnreachedEdit(AccountUpdateService.FieldFlag flag, String message,
            boolean inputError) {
    }

    /**
     * Runs one of the three edits the member translates but never reaches, and answers what it left in
     * the per-turn edit state.
     *
     * <p>The edit state is the service's own private nested carrier, and the three edits are private
     * methods with no caller, so both are reached reflectively. That is deliberate and it is confined
     * here: the alternative is to widen the visibility of the nested class, its constructor, its flag
     * reader and six methods so that a test can call them, which would add production surface whose only
     * consumer is a test. The module's unsafe-code audit is scoped to {@code src/main/java}, so nothing
     * here moves a reflection count, and no production behaviour changes - the edit driver still routes
     * neither of the two never-edited fields to any validation at all.
     *
     * @param  editMethodName the declared name of the edit to run
     * @param  field          the screen field to run it against, which supplies the composed label
     * @param  value          the keyed value, which may be {@code null} for an untransmitted field
     * @return what the edit left in the state
     * @throws ReflectiveOperationException if the service's shape has changed, which is a real failure
     *                                      rather than something to swallow
     */
    private UnreachedEdit unreachedEdit(final String editMethodName,
            final AccountUpdateService.ScreenField field, final String value)
            throws ReflectiveOperationException {
        final Class<?> editStateClass =
                Class.forName(AccountUpdateService.class.getName() + "$EditState");
        final Constructor<?> stateConstructor = editStateClass.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        final Object state = stateConstructor.newInstance();

        final Method edit = AccountUpdateService.class.getDeclaredMethod(editMethodName,
                editStateClass, AccountUpdateService.ScreenField.class, String.class);
        edit.setAccessible(true);
        edit.invoke(this.service, state, field, value);

        return new UnreachedEdit((AccountUpdateService.FieldFlag) mapMember(editStateClass, state,
                        "flags").get(field),
                (String) mapMember(editStateClass, state, "fieldMessages").get(field),
                booleanMember(editStateClass, state, "inputError"));
    }

    /**
     * One map-valued member of the edit state, read without a generic cast so no warning is suppressed.
     *
     * @param  declaring the class declaring the member
     * @param  instance  the state instance to read from
     * @param  name      the member's declared name
     * @return the member's value
     * @throws ReflectiveOperationException if the member is absent or unreadable
     */
    private static Map<?, ?> mapMember(final Class<?> declaring, final Object instance,
            final String name) throws ReflectiveOperationException {
        final Field member = declaring.getDeclaredField(name);
        member.setAccessible(true);
        return (Map<?, ?>) member.get(instance);
    }

    /**
     * One boolean member of the edit state.
     *
     * @param  declaring the class declaring the member
     * @param  instance  the state instance to read from
     * @param  name      the member's declared name
     * @return the member's value
     * @throws ReflectiveOperationException if the member is absent or unreadable
     */
    private static boolean booleanMember(final Class<?> declaring, final Object instance,
            final String name) throws ReflectiveOperationException {
        final Field member = declaring.getDeclaredField(name);
        member.setAccessible(true);
        return (boolean) member.get(instance);
    }

    /** The service's own source text, read so that a production call site cannot appear unnoticed. */
    private static String serviceSource() {
        final Path source = Path.of("src", "main", "java", "com", "carddemo", "service",
                "AccountUpdateService.java");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new AssertionError("the service's own source must be readable at " + source,
                    unreadable);
        }
    }

    /**
     * How many times the named method is invoked in the given source, excluding its own declaration.
     *
     * <p>Counted against the source rather than against the bytecode because the question is whether a
     * <em>call site</em> exists, and a method that exists but is never called is exactly what these three
     * are. The method is also asserted to exist, so a rename cannot make the count zero by accident.
     *
     * @param  source     the service's source text
     * @param  methodName the declared name of the method
     * @return the number of call sites
     */
    private static int callSitesOf(final String source, final String methodName) {
        assertThat(Arrays.stream(AccountUpdateService.class.getDeclaredMethods())
                .map(Method::getName)
                .toList())
                .as("%s must still be declared, so a zero call count means unreached and not renamed",
                        methodName)
                .contains(methodName);

        int found = 0;
        int at = source.indexOf(methodName + "(");
        while (at >= 0) {
            found++;
            at = source.indexOf(methodName + "(", at + 1);
        }
        return found - 1;
    }

    /** Every logged line the service emitted during the turn, in emission order. */
    private List<String> loggedMessages() {
        final List<String> rendered = new ArrayList<>();
        for (final ILoggingEvent event : this.logAppender.list) {
            rendered.add(event.getFormattedMessage());
        }
        return rendered;
    }

    /**
     * The submission that puts every editable component into a failing state.
     *
     * <p>Reaches all thirty-seven fields that can carry a mark. The telephone components are keyed
     * rather than left blank so the all-blank shortcut does not fire and forgive them, and each is one
     * digit wide so the width arm rejects it without the area-code table ever being consulted.
     */
    private static Turn everyEditableFieldFailingTurn() {
        return new Turn()
                .accountStatus("X")
                .creditLimit("not-an-amount")
                .cashCreditLimit("not-an-amount")
                .currentBalance("not-an-amount")
                .currentCycleCredit("not-an-amount")
                .currentCycleDebit("not-an-amount")
                .nationalIdentifier("abc", "de", "fghi")
                .ficoScore("abc")
                .firstName("A1")
                .lastName("B2")
                .addressLine1(" ")
                .stateCode("1X")
                .zipCode("ABCDE")
                .city("C3")
                .countryCode("D4")
                .phone1("9", "8", "7")
                .phone2("6", "5", "4")
                .eftAccountId("XYZ")
                .primaryCardHolderIndicator("Q");
    }

    /** The screen field identifiers that can carry a mark, in expansion order. */
    private static List<String> markableScreenFieldIds() {
        final List<String> markable = new ArrayList<>();
        for (int index = 0; index < EXPECTED_FIELD_NAMES.size(); index++) {
            if (!NEVER_VALIDATED_FIELDS.contains(EXPECTED_FIELD_NAMES.get(index))) {
                markable.add(EXPECTED_SCREEN_FIELD_IDS.get(index));
            }
        }
        return markable;
    }

    /** The component names that can carry a mark, in expansion order. */
    private static List<String> markableFieldNames() {
        final List<String> markable = new ArrayList<>(EXPECTED_FIELD_NAMES);
        markable.removeAll(NEVER_VALIDATED_FIELDS);
        return markable;
    }

    /* ==========================================================================================
     * The 39 decoration sites.
     * ========================================================================================== */

    @Nested
    @DisplayName("the 39 field-decoration sites the three-token macro is expanded at")
    class DecorationSites {

        @Test
        @DisplayName("declare exactly 39 sites, one per COPY CSSETATY expansion between source lines "
                + "3208 and 3432")
        void thirtyNineSitesAreDeclared() {
            assertAll(
                    () -> assertThat(AccountUpdateService.ScreenField.values())
                            .hasSize(DECORATION_SITE_COUNT),
                    () -> assertThat(EXPECTED_FIELD_NAMES).hasSize(DECORATION_SITE_COUNT),
                    () -> assertThat(EXPECTED_SCREEN_FIELD_IDS).hasSize(DECORATION_SITE_COUNT),
                    () -> assertThat(EXPECTED_LEGACY_FLAG_TOKENS).hasSize(DECORATION_SITE_COUNT));
        }

        @Test
        @DisplayName("bind every site from its substitution tokens, so the transposed comments above "
                + "the last two expansions and the State comment above the postcode cannot mislead")
        void everySiteIsBoundFromItsTokens() {
            final List<String> fieldNames = new ArrayList<>();
            final List<String> screenFieldIds = new ArrayList<>();
            final List<String> flagTokens = new ArrayList<>();
            for (final AccountUpdateService.ScreenField field
                    : AccountUpdateService.ScreenField.values()) {
                fieldNames.add(field.getFieldName());
                screenFieldIds.add(field.getBmsFieldId());
                flagTokens.add(field.getLegacyFlagToken());
            }

            assertAll(
                    () -> assertThat(fieldNames).containsExactlyElementsOf(EXPECTED_FIELD_NAMES),
                    () -> assertThat(screenFieldIds)
                            .containsExactlyElementsOf(EXPECTED_SCREEN_FIELD_IDS),
                    () -> assertThat(flagTokens)
                            .containsExactlyElementsOf(EXPECTED_LEGACY_FLAG_TOKENS));
        }

        @Test
        @DisplayName("carry 39 distinct component names and 39 distinct screen identifiers, so a "
                + "copy-and-paste duplicate cannot pass")
        void everyPairIsDistinct() {
            assertAll(
                    () -> assertThat(EXPECTED_FIELD_NAMES).doesNotHaveDuplicates(),
                    () -> assertThat(EXPECTED_SCREEN_FIELD_IDS).doesNotHaveDuplicates(),
                    () -> assertThat(EXPECTED_LEGACY_FLAG_TOKENS).doesNotHaveDuplicates());
        }

        @Test
        @DisplayName("report in expansion order, with the state code between the two address lines "
                + "and the postcode ahead of the city and the country")
        void marksAreReportedInExpansionOrder() {
            stubSeededReads();
            when(dateValidationService
                    .validateCcyymmddDate(any(), any()))
                    .thenReturn(rejectingDateResult("Open Date is not valid"));

            final AccountUpdateOutcome outcome =
                    service.handle(
                            everyEditableFieldFailingTurn().build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactlyElementsOf(markableScreenFieldIds()),
                    () -> assertThat(fieldNamesOf(outcome))
                            .containsExactlyElementsOf(markableFieldNames()),
                    () -> assertThat(outcome.fieldErrors()).hasSize(MAX_OBSERVABLE_MARK_COUNT),
                    () -> assertThat(outcome.error()).isTrue());
        }

        @Test
        @DisplayName("cap the observable marks at 37, because the two sites whose fields are never "
                + "edited fire and mark nothing")
        void twoSitesFireAndMarkNothing() {
            stubSeededReads();
            when(dateValidationService
                    .validateCcyymmddDate(any(), any()))
                    .thenReturn(rejectingDateResult("Open Date is not valid"));

            final AccountUpdateOutcome outcome =
                    service.handle(
                            everyEditableFieldFailingTurn().build());

            assertAll(
                    () -> assertThat(fieldNamesOf(outcome))
                            .doesNotContain(FIELD_MIDDLE_NAME, FIELD_ADDRESS_LINE_2),
                    () -> assertThat(EXPECTED_FIELD_NAMES.indexOf(FIELD_MIDDLE_NAME)).isEqualTo(23),
                    () -> assertThat(EXPECTED_FIELD_NAMES.indexOf(FIELD_ADDRESS_LINE_2))
                            .isEqualTo(27),
                    () -> assertThat(MAX_OBSERVABLE_MARK_COUNT).isEqualTo(37));
        }

        @Test
        @DisplayName("focus the first field in expansion order, because the cursor follows the "
                + "reported order rather than the order the edits ran in")
        void theFirstMarkInExpansionOrderTakesTheFocus() {
            stubSeededReads();
            when(dateValidationService
                    .validateCcyymmddDate(any(), any()))
                    .thenReturn(rejectingDateResult("Open Date is not valid"));

            final AccountUpdateOutcome outcome =
                    service.handle(
                            everyEditableFieldFailingTurn().build());

            assertThat(outcome.focusScreenFieldId())
                    .isEqualTo(EXPECTED_SCREEN_FIELD_IDS.get(0));
        }
    }

    /* ==========================================================================================
     * The two error states the macro distinguishes, and when they appear at all.
     * ========================================================================================== */

    @Nested
    @DisplayName("the two field-error states, populated only on a re-submission")
    class TwoStateFieldErrors {

        @Test
        @DisplayName("a first submission is undecorated, because the macro's outer condition also "
                + "requires the re-enter flag")
        void aFirstSubmissionIsUndecorated() {
            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn()
                            .navigationContext(null)
                            .concurrencyToken(null)
                            .build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.hasFieldErrors()).isFalse(),
                    () -> assertThat(outcome.error()).isFalse(),
                    () -> assertThat(outcome.infoMessage()).isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS));
            verifyNoInteractions(accountRepository,
                    customerRepository,
                    cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("a re-entry carrying a value that failed its edit reports INVALID, which is the "
                + "coloured-without-marker state")
        void aRejectedValueReportsInvalid() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service
                    .handle(cleanChangedTurn().accountStatus("X").build());

            final ValidationException.FieldError error = errorFor(outcome, "ACSTTUS");
            assertAll(
                    () -> assertThat(outcome.fieldErrors()).hasSize(1),
                    () -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(error.field()).isEqualTo("accountStatus"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_ACCOUNT_STATUS + SUFFIX_MUST_BE_Y_OR_N));
        }

        @Test
        @DisplayName("a re-entry carrying no value at all reports MISSING, which is the state the "
                + "macro additionally marks with a character")
        void anUnsuppliedValueReportsMissing() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service
                    .handle(cleanChangedTurn().accountStatus(" ").build());

            final ValidationException.FieldError error = errorFor(outcome, "ACSTTUS");
            assertAll(
                    () -> assertThat(outcome.fieldErrors()).hasSize(1),
                    () -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_ACCOUNT_STATUS + SUFFIX_MUST_BE_SUPPLIED));
        }

        @Test
        @DisplayName("the two states are the whole enumeration: a field that passed produces no entry, "
                + "so there is no third constant to represent")
        void theStateEnumerationHasExactlyTwoConstants() {
            assertAll(
                    () -> assertThat(ValidationException.FieldState.values()).hasSize(2),
                    () -> assertThat(ValidationException.FieldState.values())
                            .containsExactly(ValidationException.FieldState.MISSING,
                                    ValidationException.FieldState.INVALID));
        }

        @Test
        @DisplayName("the reported list is never null and never modifiable, so a consumer cannot "
                + "rewrite what the screen said")
        void theReportedListIsUnmodifiableAndNeverNull() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service
                    .handle(cleanChangedTurn().accountStatus("X").build());
            final List<ValidationException.FieldError> reported = outcome.fieldErrors();
            final ValidationException.FieldError intruder = new ValidationException.FieldError(
                    "intruder", "ACSTTUS", ValidationException.FieldState.INVALID, null);

            assertAll(
                    () -> assertThat(reported).isNotNull(),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> reported.add(intruder)),
                    () -> assertThat(new ValidationException("summary").fieldErrors())
                            .isNotNull()
                            .isEmpty());
        }

        @Test
        @DisplayName("a turn with no error at all reports an empty list rather than a null one")
        void aCleanTurnReportsAnEmptyList() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).isNotNull().isEmpty(),
                    () -> assertThat(outcome.error()).isFalse(),
                    () -> assertThat(outcome.infoMessage())
                            .isEqualTo(INFO_PROMPT_FOR_CONFIRMATION));
        }
    }

    /* ==========================================================================================
     * The two fields the legacy decorates and never edits.
     * ========================================================================================== */

    @Nested
    @DisplayName("the middle name and the second address line, which the legacy decorates but codes "
            + "no edits for: attaching a constraint would reject input the legacy accepts")
    class FieldsTheLegacyNeverEdits {

        @ParameterizedTest(name = "middle name [{0}] is accepted")
        @ValueSource(strings = {"", "     ", "12345", "O'Brien-Smith!", "A1B2C3", "@@@"})
        @DisplayName("accept any middle name at all, however unlike a name it looks")
        void anyMiddleNameIsAccepted(final String keyed) {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service
                    .handle(cleanChangedTurn().middleName(keyed).build());

            assertAll(
                    () -> assertThat(fieldNamesOf(outcome)).doesNotContain(FIELD_MIDDLE_NAME),
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.error()).isFalse());
        }

        @ParameterizedTest(name = "second address line [{0}] is accepted")
        @ValueSource(strings = {"", "     ", "12345", "Apt. 4/B #7", "!!!", "0"})
        @DisplayName("accept any second address line at all, its edit call being commented out in "
                + "the source")
        void anySecondAddressLineIsAccepted(final String keyed) {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service
                    .handle(cleanChangedTurn().addressLine2(keyed).build());

            assertAll(
                    () -> assertThat(fieldNamesOf(outcome)).doesNotContain(FIELD_ADDRESS_LINE_2),
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.error()).isFalse());
        }

        @Test
        @DisplayName("keep both decoration sites in the enumeration, so each fires on re-entry and "
                + "marks nothing rather than being absent")
        void bothSitesRemainInTheEnumeration() {
            assertAll(
                    () -> assertThat(AccountUpdateService.ScreenField.MIDDLE_NAME.neverValidated())
                            .isTrue(),
                    () -> assertThat(
                            AccountUpdateService.ScreenField.ADDRESS_LINE_2.neverValidated())
                            .isTrue(),
                    () -> assertThat(AccountUpdateService.ScreenField.MIDDLE_NAME.getBmsFieldId())
                            .isEqualTo("ACSMNAM"),
                    () -> assertThat(
                            AccountUpdateService.ScreenField.ADDRESS_LINE_2.getBmsFieldId())
                            .isEqualTo("ACSADL2"));
        }

        @Test
        @DisplayName("no other field is exempt: exactly two of the 39 carry the never-edited mark")
        void exactlyTwoFieldsAreExempt() {
            final List<String> exempt = new ArrayList<>();
            for (final AccountUpdateService.ScreenField field
                    : AccountUpdateService.ScreenField.values()) {
                if (field.neverValidated()) {
                    exempt.add(field.getFieldName());
                }
            }

            assertThat(exempt).containsExactlyElementsOf(NEVER_VALIDATED_FIELDS);
        }

        @Test
        @DisplayName("both fields are still written, so a keyed value reaches the record even though "
                + "nothing validated it")
        void bothFieldsAreStillWritten() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any()))
                    .thenReturn(1);
            when(concurrencyTokenService.mint(any(), any()))
                    .thenReturn(REMINTED_TOKEN);
            final ArgumentCaptor<Customer> written = ArgumentCaptor.forClass(Customer.class);

            service.handle(cleanChangedTurn()
                    .middleName("9")
                    .addressLine2("#7")
                    .keyAction(KeyAction.PFK05)
                    .build());

            verify(customerRepository)
                    .compareAndSet(any(), written.capture());
            assertAll(
                    () -> assertThat(written.getValue().getMiddleName()).isEqualTo("9"),
                    () -> assertThat(written.getValue().getAddrLine2()).isEqualTo("#7"));
        }
    }

    /* ==========================================================================================
     * The three edits the member translates and never reaches.
     * ========================================================================================== */

    /**
     * The three character-class edits no call site in {@code COACTUPC} routes to, exercised directly.
     *
     * <p><strong>Why this suite exists at all.</strong> Six of the member's 85 paragraph units -
     * {@code 1230-EDIT-ALPHANUM-REQD} and its exit, {@code 1235-EDIT-ALPHA-OPT} and its exit, and
     * {@code 1240-EDIT-ALPHANUM-OPT} and its exit - are translated into methods the edit driver never
     * calls. Two different reasons put them there and both are recorded rather than tidied away. The two
     * alphanumeric edits have <em>no call site in the source</em>: the driver reaches the required
     * alphabetic, the optional alphabetic, the required numeric, the mandatory and the signed edits and
     * never those. The optional alphabetic edit does have a live source call site, on the middle name, but
     * the migration directive forbids attaching any constraint to that field - the screen-attribute block
     * describes it as carrying no edits - so wiring it would reject input the legacy is documented to
     * accept.
     *
     * <p><strong>Why the coverage is taken here rather than through the driver.</strong> The traceability
     * matrix names this class as the covering test for all six units, and a named test that never executes
     * the method it cites is not coverage. The only two ways to make the citation true are to wire the
     * driver - which the directive forbids - or to reach the methods directly. So they are reached
     * directly, and the choice is deliberately made in the test rather than in the service: relaxing the
     * visibility of the nested edit state, its constructor, its flag reader and the six methods would add
     * nine pieces of production surface to make a test convenient, and surface that exists for a test is
     * the same defect as surface that exists for an operation nobody calls.
     *
     * <p><strong>Reflection is confined to this class and moves no audit count.</strong> The module's
     * unsafe-code audit is scoped to {@code src/main/java}, and the sibling suites already reach an
     * unreachable private member the same way. Nothing here changes what production code does: the driver
     * still bypasses both never-edited fields, which the suite immediately above proves.
     *
     * <p>Legacy authority {@code app/cbl/COACTUPC.cbl} paragraphs {@code 1230-EDIT-ALPHANUM-REQD}
     * (L1955-L2007), {@code 1235-EDIT-ALPHA-OPT} (L2012-L2055) and {@code 1240-EDIT-ALPHANUM-OPT}
     * (L2061-L2103), read as read-only reference. No source text is transcribed.
     */
    @Nested
    @DisplayName("the three character-class edits the member translates but never reaches: exercised "
            + "directly, because a named covering test that never runs the method is not coverage")
    class EditsTranslatedButNeverReached {

        /** The required alphanumeric edit, {@code 1230-EDIT-ALPHANUM-REQD}. */
        private static final String EDIT_ALPHANUMERIC_REQUIRED = "editAlphanumericRequired";

        /** The optional alphabetic edit, {@code 1235-EDIT-ALPHA-OPT}. */
        private static final String EDIT_ALPHA_OPTIONAL = "editAlphaOptional";

        /** The optional alphanumeric edit, {@code 1240-EDIT-ALPHANUM-OPT}. */
        private static final String EDIT_ALPHANUMERIC_OPTIONAL = "editAlphanumericOptional";

        @Test
        @DisplayName("the required alphanumeric edit fails a blank as missing and composes the label "
                + "against the supplied suffix, exactly as the required alphabetic edit does")
        void theRequiredAlphanumericEditFailsABlankAsMissing() throws Exception {
            final UnreachedEdit edit = unreachedEdit(EDIT_ALPHANUMERIC_REQUIRED,
                    AccountUpdateService.ScreenField.FIRST_NAME, "   ");

            assertAll(
                    () -> assertThat(edit.flag()).isEqualTo(AccountUpdateService.FieldFlag.BLANK),
                    () -> assertThat(edit.message())
                            .isEqualTo(LABEL_FIRST_NAME + SUFFIX_MUST_BE_SUPPLIED),
                    () -> assertThat(edit.inputError()).isTrue());
        }

        @Test
        @DisplayName("the required alphanumeric edit admits a digit, which is the whole difference from "
                + "the alphabetic edit: it converts the 62-character table and not the 52-character one")
        void theRequiredAlphanumericEditAdmitsADigit() throws Exception {
            final UnreachedEdit admitted = unreachedEdit(EDIT_ALPHANUMERIC_REQUIRED,
                    AccountUpdateService.ScreenField.FIRST_NAME, "A1B2C3");
            final UnreachedEdit refusedByTheAlphabeticEdit = unreachedEdit(EDIT_ALPHA_OPTIONAL,
                    AccountUpdateService.ScreenField.MIDDLE_NAME, "A1B2C3");

            assertAll(
                    () -> assertThat(admitted.flag())
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(admitted.inputError()).isFalse(),
                    () -> assertThat(refusedByTheAlphabeticEdit.flag())
                            .as("the same value under the 52-character table is refused")
                            .isEqualTo(AccountUpdateService.FieldFlag.NOT_OK));
        }

        @Test
        @DisplayName("the required alphanumeric edit refuses punctuation under its own suffix, so the "
                + "two character-class refusals stay distinguishable in the summary")
        void theRequiredAlphanumericEditRefusesPunctuation() throws Exception {
            final UnreachedEdit edit = unreachedEdit(EDIT_ALPHANUMERIC_REQUIRED,
                    AccountUpdateService.ScreenField.FIRST_NAME, "O'Brien");

            assertAll(
                    () -> assertThat(edit.flag()).isEqualTo(AccountUpdateService.FieldFlag.NOT_OK),
                    () -> assertThat(edit.message())
                            .isEqualTo(LABEL_FIRST_NAME + SUFFIX_NUMBERS_OR_ALPHABETS_ONLY),
                    () -> assertThat(edit.inputError()).isTrue());
        }

        @Test
        @DisplayName("both optional edits declare a blank valid and leave early, which is the whole "
                + "difference between the optional and the required shape")
        void bothOptionalEditsDeclareABlankValid() throws Exception {
            final UnreachedEdit alphabetic = unreachedEdit(EDIT_ALPHA_OPTIONAL,
                    AccountUpdateService.ScreenField.MIDDLE_NAME, "   ");
            final UnreachedEdit alphanumeric = unreachedEdit(EDIT_ALPHANUMERIC_OPTIONAL,
                    AccountUpdateService.ScreenField.ADDRESS_LINE_2, null);

            assertAll(
                    () -> assertThat(alphabetic.flag())
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(alphabetic.message()).isNull(),
                    () -> assertThat(alphabetic.inputError()).isFalse(),
                    () -> assertThat(alphanumeric.flag())
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(alphanumeric.message()).isNull(),
                    () -> assertThat(alphanumeric.inputError()).isFalse());
        }

        @Test
        @DisplayName("the optional alphabetic edit refuses a digit under the alphabets-only suffix, so "
                + "the live source call site on the middle name really would have rejected one")
        void theOptionalAlphabeticEditRefusesADigit() throws Exception {
            final UnreachedEdit edit = unreachedEdit(EDIT_ALPHA_OPTIONAL,
                    AccountUpdateService.ScreenField.MIDDLE_NAME, "9");

            assertAll(
                    () -> assertThat(edit.flag()).isEqualTo(AccountUpdateService.FieldFlag.NOT_OK),
                    () -> assertThat(edit.message())
                            .isEqualTo(LABEL_MIDDLE_NAME + SUFFIX_ALPHABETS_ONLY),
                    () -> assertThat(edit.inputError()).isTrue());
        }

        @Test
        @DisplayName("the optional alphanumeric edit admits a digit despite the source comment claiming "
                + "letters and spaces only, because the converted table governs and the comment does not")
        void theOptionalAlphanumericEditAdmitsADigit() throws Exception {
            final UnreachedEdit admitted = unreachedEdit(EDIT_ALPHANUMERIC_OPTIONAL,
                    AccountUpdateService.ScreenField.ADDRESS_LINE_2, "Apt 4B");
            final UnreachedEdit refused = unreachedEdit(EDIT_ALPHANUMERIC_OPTIONAL,
                    AccountUpdateService.ScreenField.ADDRESS_LINE_2, "Apt. 4/B");

            assertAll(
                    () -> assertThat(admitted.flag())
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(refused.flag())
                            .isEqualTo(AccountUpdateService.FieldFlag.NOT_OK),
                    () -> assertThat(refused.message())
                            .isEqualTo("Address Line 2" + SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
        }

        @ParameterizedTest(name = "{0} accepts an embedded space")
        @ValueSource(strings = {EDIT_ALPHANUMERIC_REQUIRED, EDIT_ALPHA_OPTIONAL,
                                EDIT_ALPHANUMERIC_OPTIONAL})
        @DisplayName("all three edits accept an embedded space, because the estate's idiom blanks every "
                + "table character and then trims, and a space was already a space")
        void allThreeEditsAcceptAnEmbeddedSpace(final String edit) throws Exception {
            final UnreachedEdit outcome = unreachedEdit(edit,
                    AccountUpdateService.ScreenField.FIRST_NAME, "MARY ANN");

            assertAll(
                    () -> assertThat(outcome.flag())
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(outcome.inputError()).isFalse());
        }

        @Test
        @DisplayName("all three remain unreached from production, so this suite is the only caller and "
                + "the never-edited fields keep bypassing validation")
        void allThreeRemainUnreachedFromProduction() {
            final String source = serviceSource();

            assertAll(
                    () -> assertThat(callSitesOf(source, EDIT_ALPHANUMERIC_REQUIRED))
                            .as("%s must keep its single declaration and no call",
                                    EDIT_ALPHANUMERIC_REQUIRED)
                            .isZero(),
                    () -> assertThat(callSitesOf(source, EDIT_ALPHA_OPTIONAL))
                            .as("wiring %s would reject a middle name the legacy accepts",
                                    EDIT_ALPHA_OPTIONAL)
                            .isZero(),
                    () -> assertThat(callSitesOf(source, EDIT_ALPHANUMERIC_OPTIONAL))
                            .as("%s has no call site in the source either", EDIT_ALPHANUMERIC_OPTIONAL)
                            .isZero());
        }
    }

    /* ==========================================================================================
     * The telephone cascade: five paragraphs that forward rather than short-circuit.
     * ========================================================================================== */

    @Nested
    @DisplayName("the telephone cascade, whose failing stages forward to the next stage instead of "
            + "leaving the range")
    class TelephoneCascade {

        @Test
        @DisplayName("three bad parts yield three field errors and exactly one summary, and the "
                + "summary is the first failure's text")
        void threeBadPartsYieldThreeErrorsAndOneSummary() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().phone1("9", "8", "7").build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).hasSize(3),
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactly("ACSPH1A", "ACSPH1B", "ACSPH1C"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_AREA_CODE_NOT_3_DIGITS),
                    () -> assertThat(outcome.errorMessage())
                            .isNotEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_PREFIX_NOT_3_DIGITS)
                            .isNotEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_LINE_NUMBER_NOT_4_DIGITS));
        }

        @Test
        @DisplayName("the line-number stage still runs after the two stages before it failed, which "
                + "a short-circuiting cascade would have skipped")
        void theLineNumberStageRunsAfterTwoFailures() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().phone1("9", "8", "7").build());

            assertThat(errorFor(outcome, "ACSPH1C").state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("both telephones run their own cascade, so six bad parts report in expansion "
                + "order across the two groups")
        void bothTelephonesRunTheirOwnCascade() {
            stubSeededReads();
            stubAcceptingDateCascade();
            stubAcceptingDateOfBirth();
            stubAcceptingStateCode();
            stubAcceptingStateZipComposite();

            final AccountUpdateOutcome outcome = service.handle(cleanChangedTurn()
                    .phone1("9", "8", "7")
                    .phone2("6", "5", "4")
                    .build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).hasSize(6),
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly(
                            "ACSPH1A", "ACSPH1B", "ACSPH1C", "ACSPH2A", "ACSPH2B", "ACSPH2C"));
            // Neither cascade reaches its fourth arm, so the area-code table is never consulted at
            // all: both area codes fail the width arm that precedes it.
            verify(validationLookupService, never()).isValidGeneralPurposeAreaCode(any());
        }

        @Test
        @DisplayName("an easily-recognisable code is rejected, because the fourth arm consults the "
                + "410-entry general-purpose set and never the 490-entry union")
        void anEasilyRecognisableCodeIsRejected() {
            stubSeededReads();
            stubAcceptingDateCascade();
            stubAcceptingDateOfBirth();
            stubAcceptingStateCode();
            stubAcceptingStateZipComposite();
            when(validationLookupService.isValidGeneralPurposeAreaCode("800")).thenReturn(false);
            when(validationLookupService.isValidGeneralPurposeAreaCode("202")).thenReturn(true);

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().phone1("800", "555", "0100").build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).hasSize(1),
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSPH1A"),
                    () -> assertThat(outcome.errorMessage()).isEqualTo(
                            LABEL_PHONE_NUMBER_1 + SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE));
        }

        @Test
        @DisplayName("a general-purpose code is accepted, and the candidate reaches the table already "
                + "trimmed, which is the one place the cascade trims anything")
        void aGeneralPurposeCodeIsAcceptedAndTrimmedAtTheTable() {
            stubSeededReads();
            stubAllEditsAccepting();
            final ArgumentCaptor<String> consulted = ArgumentCaptor.forClass(String.class);

            final AccountUpdateOutcome outcome = service.handle(cleanChangedTurn().build());

            verify(validationLookupService, times(2))
                    .isValidGeneralPurposeAreaCode(consulted.capture());
            assertAll(
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(consulted.getAllValues()).containsExactly("201", "202"),
                    () -> assertThat(consulted.getAllValues())
                            .allSatisfy(key -> assertThat(key).isEqualTo(key.trim())));
        }

        @Test
        @DisplayName("a space-bearing area code is rejected by the width arm before the table is "
                + "reached, so the trim at the table can never rescue it")
        void aSpaceBearingAreaCodeNeverReachesTheTable() {
            stubSeededReads();
            stubAcceptingDateCascade();
            stubAcceptingDateOfBirth();
            stubAcceptingStateCode();
            stubAcceptingStateZipComposite();
            when(validationLookupService.isValidGeneralPurposeAreaCode("202")).thenReturn(true);

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().phone1("20 ", "555", "0100").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSPH1A"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_AREA_CODE_NOT_3_DIGITS));
            verify(validationLookupService, never()).isValidGeneralPurposeAreaCode("20");
            verify(validationLookupService, never()).isValidGeneralPurposeAreaCode("20 ");
        }

        @Test
        @DisplayName("a wholly blank telephone is accepted, because the group is not mandatory")
        void aWhollyBlankTelephoneIsAccepted() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().phone1(" ", " ", " ").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .doesNotContain("ACSPH1A", "ACSPH1B", "ACSPH1C"),
                    () -> assertThat(outcome.fieldErrors()).isEmpty());
        }

        @Test
        @DisplayName("the reproduced defect: the shortcut's third condition tests the area code where "
                + "the line number belongs, so a keyed line number is forgiven when the area code is "
                + "spaces and edited when it was never transmitted")
        void theShortcutTestsTheAreaCodeWhereTheLineNumberBelongs() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome forgiven =
                    service.handle(cleanChangedTurn().phone1(" ", " ", "0100").build());

            assertAll(
                    () -> assertThat(forgiven.fieldErrors()).isEmpty(),
                    () -> assertThat(screenFieldIdsOf(forgiven))
                            .doesNotContain("ACSPH1A", "ACSPH1B", "ACSPH1C"));
        }

        @Test
        @DisplayName("the same keyed line number is edited when the area code was never transmitted, "
                + "so the two spellings of blank take different paths")
        void anUntransmittedAreaCodeTakesTheCascade() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().phone1(null, null, "0100").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactly("ACSPH1A", "ACSPH1B"),
                    () -> assertThat(errorFor(outcome, "ACSPH1A").state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(errorFor(outcome, "ACSPH1B").state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_PHONE_NUMBER_1 + SUFFIX_AREA_CODE_REQUIRED));
        }
    }

    /* ==========================================================================================
     * The credit-score range: input validation only, and gated on the field's own flag.
     * ========================================================================================== */

    @Nested
    @DisplayName("the credit-score range, enforced on input and nowhere else")
    class CreditScoreRange {

        @ParameterizedTest(name = "a score of {0} is accepted")
        @ValueSource(strings = {"300", "850", "301", "849", "500"})
        @DisplayName("accept both inclusive bounds and everything between them")
        void inclusiveBoundsAreAccepted(final String score) {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().ficoScore(score).build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.error()).isFalse());
        }

        @ParameterizedTest(name = "a score of {0} is rejected")
        @ValueSource(strings = {"299", "851", "001", "999"})
        @DisplayName("reject a score outside the bounds, with the exact text and no closing full stop")
        void scoresOutsideTheBoundsAreRejected(final String score) {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().ficoScore(score).build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSTFCO"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_FICO_SCORE + SUFFIX_FICO_OUT_OF_RANGE)
                            .doesNotEndWith("."),
                    () -> assertThat(outcome.error()).isTrue());
        }

        @Test
        @DisplayName("both bounds are the ones the condition name declares")
        void theBoundsAreTheDeclaredOnes() {
            assertAll(
                    () -> assertThat(AccountUpdateService.FICO_SCORE_MINIMUM)
                            .isEqualTo(FICO_LOWEST_ACCEPTED),
                    () -> assertThat(AccountUpdateService.FICO_SCORE_MAXIMUM)
                            .isEqualTo(FICO_HIGHEST_ACCEPTED));
        }

        @Test
        @DisplayName("a score that already failed the numeric edit is not edited again, so it earns "
                + "the numeric message and not the range message")
        void anAlreadyInvalidScoreIsNotReEdited() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().ficoScore("abc").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSTFCO"),
                    () -> assertThat(outcome.fieldErrors()).hasSize(1),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_FICO_SCORE + SUFFIX_MUST_BE_ALL_NUMERIC),
                    () -> assertThat(outcome.errorMessage())
                            .isNotEqualTo(LABEL_FICO_SCORE + SUFFIX_FICO_OUT_OF_RANGE));
        }

        @Test
        @DisplayName("a stored score below the range loads and is shown, because 21 of the 50 seeded "
                + "customer rows hold one and no persistence constraint may reject them")
        void aStoredOutOfRangeScoreLoadsAndIsShown() {
            stubSeededReads(seededCustomer(SEEDED_OUT_OF_RANGE_FICO));

            final AccountUpdateOutcome outcome = service.handle(
                    unchangedTurn().ficoScore(SEEDED_OUT_OF_RANGE_FICO).build());

            assertAll(
                    () -> assertThat(outcome.ficoScore()).isEqualTo(SEEDED_OUT_OF_RANGE_FICO),
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.errorMessage()).isEqualTo(MSG_NO_CHANGES_DETECTED));
        }
    }

    /* ==========================================================================================
     * The two postal lists, which must never be intersected.
     * ========================================================================================== */

    @Nested
    @DisplayName("the state code and the state-plus-postcode composite, two different questions put "
            + "to two lists that are never intersected")
    class PostalValidation {

        @ParameterizedTest(name = "the prefix {0} fails the flat 56-code test")
        @ValueSource(strings = {"AA", "AE", "AP", "FM", "MH", "PW"})
        @DisplayName("reject each of the six prefixes that exist only in the composite list, and never "
                + "put the two-character question to the composite list")
        void thePrefixesThatExistOnlyInTheCompositeListFailTheStateTest(final String prefix) {
            stubSeededReads();
            stubCommonAcceptingEdits();
            when(validationLookupService.isValidUsStateCode(prefix)).thenReturn(false);

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().stateCode(prefix).build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSSTTE"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_STATE + SUFFIX_STATE_NOT_VALID)
                            .doesNotEndWith("."));
            verify(validationLookupService, never()).isValidUsStateZipCodeCombination(prefix);
            verify(validationLookupService, never()).isValidUsStateZipCodeCombination(any());
        }

        @ParameterizedTest(name = "{0} plus postcode {1} composes the key {2}")
        @CsvSource({"AA, 34567, AA34", "AE, 09123, AE09", "AP, 96201, AP96", "FM, 96941, FM96",
                    "MH, 96960, MH96", "PW, 96940, PW96"})
        @DisplayName("compose the composite key positionally from the state code and the first two "
                + "postcode characters, which is a four-character question the 56-code list never sees")
        void theCompositeKeyIsComposedPositionally(final String state, final String postcode,
                final String expectedKey) {
            stubSeededReads();
            stubCommonAcceptingEdits();
            when(validationLookupService.isValidUsStateCode(state)).thenReturn(true);
            when(validationLookupService.isValidUsStateZipCodeCombination(expectedKey))
                    .thenReturn(true);
            final ArgumentCaptor<String> stateAsked = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> compositeAsked = ArgumentCaptor.forClass(String.class);

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().stateCode(state).zipCode(postcode).build());

            verify(validationLookupService).isValidUsStateCode(stateAsked.capture());
            verify(validationLookupService)
                    .isValidUsStateZipCodeCombination(compositeAsked.capture());
            assertAll(
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(stateAsked.getValue()).isEqualTo(state).hasSize(2),
                    () -> assertThat(compositeAsked.getValue()).isEqualTo(expectedKey).hasSize(4));
        }

        @Test
        @DisplayName("a composite failure sets both the state flag and the postcode flag, and emits "
                + "the bare literal with no field-name prefix - the only message composed that way")
        void aCompositeFailureSetsBothFlagsAndEmitsTheUnprefixedMessage() {
            stubSeededReads();
            stubCommonAcceptingEdits();
            stubAcceptingStateCode();
            when(validationLookupService.isValidUsStateZipCodeCombination(any())).thenReturn(false);

            final AccountUpdateOutcome outcome = service.handle(cleanChangedTurn().build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactly("ACSSTTE", "ACSZIPC"),
                    () -> assertThat(errorFor(outcome, "ACSSTTE").state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(errorFor(outcome, "ACSZIPC").state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(outcome.errorMessage()).isEqualTo(MSG_INVALID_ZIP_FOR_STATE),
                    () -> assertThat(outcome.errorMessage())
                            .doesNotStartWith(LABEL_STATE)
                            .doesNotStartWith(LABEL_ZIP));
        }

        @Test
        @DisplayName("neither key is trimmed: a short postcode contributes what it has and the "
                + "composite is space-filled to its four positions")
        void neitherKeyIsTrimmed() {
            stubSeededReads();
            stubCommonAcceptingEdits();
            stubAcceptingStateCode();
            when(validationLookupService.isValidUsStateZipCodeCombination(any())).thenReturn(true);
            final ArgumentCaptor<String> compositeAsked = ArgumentCaptor.forClass(String.class);

            service.handle(cleanChangedTurn().zipCode("1").build());

            verify(validationLookupService)
                    .isValidUsStateZipCodeCombination(compositeAsked.capture());
            assertAll(
                    () -> assertThat(compositeAsked.getValue()).isEqualTo(SEEDED_STATE + "1 "),
                    () -> assertThat(compositeAsked.getValue()).hasSize(4),
                    () -> assertThat(compositeAsked.getValue()).isNotEqualTo(SEEDED_STATE + "1"));
        }

        @Test
        @DisplayName("the composite edit is gated on both of its inputs, so a rejected postcode stops "
                + "it being asked at all")
        void theCompositeEditIsGatedOnBothInputs() {
            stubSeededReads();
            stubCommonAcceptingEdits();
            stubAcceptingStateCode();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().zipCode("ABCDE").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSZIPC"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_ZIP + SUFFIX_MUST_BE_ALL_NUMERIC));
            verify(validationLookupService, never()).isValidUsStateZipCodeCombination(any());
        }

        @Test
        @DisplayName("the five declared cardinalities are the ones this feature depends on, and the "
                + "490 figure is the derived union rather than a stored list")
        void theDeclaredCardinalitiesHold() {
            assertAll(
                    () -> assertThat(ValidationLookupService.GENERAL_PURPOSE_AREA_CODE_COUNT)
                            .isEqualTo(410),
                    () -> assertThat(ValidationLookupService.EASY_RECOGNITION_AREA_CODE_COUNT)
                            .isEqualTo(80),
                    () -> assertThat(ValidationLookupService.PHONE_AREA_CODE_COUNT).isEqualTo(490),
                    () -> assertThat(ValidationLookupService.GENERAL_PURPOSE_AREA_CODE_COUNT
                            + ValidationLookupService.EASY_RECOGNITION_AREA_CODE_COUNT)
                            .isEqualTo(ValidationLookupService.PHONE_AREA_CODE_COUNT),
                    () -> assertThat(ValidationLookupService.US_STATE_CODE_COUNT).isEqualTo(56),
                    () -> assertThat(ValidationLookupService.US_STATE_ZIP_COMBINATION_COUNT)
                            .isEqualTo(240));
        }
    }

    /* ==========================================================================================
     * The alphabetic idiom: blank the letters, then trim, so embedded spaces survive.
     * ========================================================================================== */

    @Nested
    @DisplayName("the alphabetic edits, which the legacy performs by blanking every letter and then "
            + "trimming, so a per-character letter test would break the seeded data")
    class AlphabeticEdits {

        @ParameterizedTest(name = "[{0}] passes the alphabetic edit")
        @ValueSource(strings = {"MARY ANN", "Aniya Von", "Mary Jane Watson", " Leading", "Trailing ",
                                "Smith", "A B C"})
        @DisplayName("accept an embedded, leading or trailing space, the seeded first name included")
        void embeddedSpacesPass(final String keyed) {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().firstName(keyed).build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.error()).isFalse());
        }

        @Test
        @DisplayName("the seeded first name carries a character that is not a letter, so a "
                + "per-character letter test would reject a value already on file - which is exactly "
                + "why the faithful predicate admits a space")
        void theSeededFirstNameCarriesANonLetter() {
            final long nonLetterPositions = SEEDED_FIRST_NAME.chars()
                    .filter(codePoint -> !Character.isLetter(codePoint))
                    .count();

            assertAll(
                    () -> assertThat(SEEDED_FIRST_NAME).contains(" "),
                    () -> assertThat(nonLetterPositions).isPositive(),
                    () -> assertThat(SEEDED_FIRST_NAME).isEqualTo("Aniya Von"));
        }

        @ParameterizedTest(name = "[{0}] fails the alphabetic edit")
        @ValueSource(strings = {"Mary1", "M4RY", "Mary-Ann", "Mary.Ann", "0"})
        @DisplayName("reject anything that is neither a letter nor a space")
        void nonAlphabeticValuesAreRejected(final String keyed) {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().firstName(keyed).build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSFNAM"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_FIRST_NAME + SUFFIX_ALPHABETS_ONLY));
        }

        @Test
        @DisplayName("an unsupplied mandatory alphabetic field reports MISSING rather than the "
                + "character-class failure")
        void anUnsuppliedAlphabeticFieldReportsMissing() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().firstName("   ").build());

            assertAll(
                    () -> assertThat(errorFor(outcome, "ACSFNAM").state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_FIRST_NAME + SUFFIX_MUST_BE_SUPPLIED));
        }

        @Test
        @DisplayName("a blank mandatory first address line reports MISSING through the plain "
                + "mandatory edit, which performs no character-class test at all")
        void aBlankAddressLineOneReportsMissing() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().addressLine1(" ").build());

            assertAll(
                    () -> assertThat(errorFor(outcome, "ACSADL1").state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_ADDRESS_LINE_1 + SUFFIX_MUST_BE_SUPPLIED));
        }

        @Test
        @DisplayName("the first address line accepts a digit, because its edit is the mandatory one "
                + "rather than the alphabetic one")
        void theFirstAddressLineAcceptsADigit() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome =
                    service.handle(cleanChangedTurn().addressLine1("221B Baker Street").build());

            assertThat(outcome.fieldErrors()).isEmpty();
        }
    }

    /* ==========================================================================================
     * The three edits the driver deliberately never reaches, called directly through the seam.
     * ========================================================================================== */

    /**
     * The three translated-but-unwired edits: {@code 1230-EDIT-ALPHANUM-REQD} at source line 1955,
     * {@code 1235-EDIT-ALPHA-OPT} at 2012 and {@code 1240-EDIT-ALPHANUM-OPT} at 2061.
     *
     * <p>Each is package-private on the service for exactly this suite, and each is called here by name:
     * {@code editAlphanumericRequired}, {@code editAlphaOptional} and {@code editAlphanumericOptional}.
     * Nothing reflective is used - the seam is ordinary package access, because the production tree is
     * held to a reflection count of zero and a reflective call would prove nothing about a call the
     * driver could make.
     *
     * <p><strong>Why calling them directly is the only honest way to cover them.</strong> No production
     * caller exists, by decision rather than by oversight: the required alphanumeric edit and its optional
     * sibling have no call site in the legacy member either, and the optional alphabetic edit does have one
     * - the middle name at source lines 1568 to 1574 - but the migration directive forbids attaching any
     * constraint to that field, so the driver must not route to it. Driving the entry point can therefore
     * never execute these paragraphs, and a suite that only drove the entry point would leave six of the
     * member's 85 procedure paragraphs - these three and their three paired exits - claimed as covered
     * while nothing executed them. The three exits need no call of their own: each head calls its own exit
     * on every arm, so proving the arms proves {@code editAlphanumericRequiredExit},
     * {@code editAlphaOptionalExit} and {@code editAlphanumericOptionalExit} with it.
     *
     * <p>Every expected value below is a literal taken from the legacy member, never from the service:
     * the label the edit moves into the message-composition slot, and the suffix its failing arm claims.
     *
     * <p>That the driver still reaches none of them is asserted separately, and stays asserted, by
     * {@link FieldsTheLegacyNeverEdits}: a turn keying a middle name or a second address line that all
     * three of these edits would refuse produces no field error at all, which is the assertion that fails
     * the moment one of them is wired in.
     */
    @Nested
    @DisplayName("the three edits the driver deliberately never reaches, exercised through the "
            + "package-private seam so that no paragraph is claimed as covered without being executed")
    class DeliberatelyUnwiredEdits {

        /** {@code 1230-EDIT-ALPHANUM-REQD}: an unsupplied value is the blank arm, not the class arm. */
        @Test
        @DisplayName("1230-EDIT-ALPHANUM-REQD: an unsupplied value flags BLANK and claims the supplied "
                + "text")
        void theRequiredAlphanumericEditFlagsAnUnsuppliedValueBlank() {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphanumericRequired(state, AccountUpdateService.ScreenField.FIRST_NAME, "   ");

            assertAll(
                    () -> assertThat(state.flag(AccountUpdateService.ScreenField.FIRST_NAME))
                            .isEqualTo(AccountUpdateService.FieldFlag.BLANK),
                    () -> assertThat(state.messageFor(AccountUpdateService.ScreenField.FIRST_NAME))
                            .isEqualTo(LABEL_FIRST_NAME + SUFFIX_MUST_BE_SUPPLIED));
        }

        /**
         * {@code 1230-EDIT-ALPHANUM-REQD}: the 62-character table, so a digit passes where the alphabetic
         * edit would have refused it. That difference is the whole reason both paragraphs exist.
         *
         * @param keyed the value the screen transmitted
         */
        @ParameterizedTest(name = "[{0}] passes the required alphanumeric edit")
        @ValueSource(strings = {"M4RY", "MARY ANN", "Mary Jane 2nd", "0", "A1 B2"})
        @DisplayName("1230-EDIT-ALPHANUM-REQD: digits and embedded spaces both pass, unlike the "
                + "alphabetic edit")
        void theRequiredAlphanumericEditAcceptsDigitsAndSpaces(final String keyed) {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphanumericRequired(state, AccountUpdateService.ScreenField.FIRST_NAME, keyed);

            assertAll(
                    () -> assertThat(state.flag(AccountUpdateService.ScreenField.FIRST_NAME))
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(state.messageFor(AccountUpdateService.ScreenField.FIRST_NAME))
                            .isNull());
        }

        /**
         * {@code 1230-EDIT-ALPHANUM-REQD}: anything outside the table fails with its own suffix, which is
         * the text no other edit in the member claims.
         *
         * @param keyed the value the screen transmitted
         */
        @ParameterizedTest(name = "[{0}] fails the required alphanumeric edit")
        @ValueSource(strings = {"Mary-Ann", "Mary.Ann", "M@RY", "221B_Baker"})
        @DisplayName("1230-EDIT-ALPHANUM-REQD: a value outside the table flags NOT_OK and claims the "
                + "numbers-or-alphabets text")
        void theRequiredAlphanumericEditRefusesEverythingOutsideItsTable(final String keyed) {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphanumericRequired(state, AccountUpdateService.ScreenField.FIRST_NAME, keyed);

            assertAll(
                    () -> assertThat(state.flag(AccountUpdateService.ScreenField.FIRST_NAME))
                            .isEqualTo(AccountUpdateService.FieldFlag.NOT_OK),
                    () -> assertThat(state.messageFor(AccountUpdateService.ScreenField.FIRST_NAME))
                            .isEqualTo(LABEL_FIRST_NAME + SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
        }

        /**
         * {@code 1235-EDIT-ALPHA-OPT}: the arm that distinguishes it from the required alphabetic edit is
         * the blank one, which leaves early at source lines 2024 to 2025 declaring the field valid.
         */
        @Test
        @DisplayName("1235-EDIT-ALPHA-OPT: an unsupplied value is valid, which is the one arm that "
                + "differs from the required alphabetic edit")
        void theOptionalAlphabeticEditAcceptsAnUnsuppliedValue() {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphaOptional(state, AccountUpdateService.ScreenField.MIDDLE_NAME, "   ");

            assertAll(
                    () -> assertThat(state.flag(AccountUpdateService.ScreenField.MIDDLE_NAME))
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(state.messageFor(AccountUpdateService.ScreenField.MIDDLE_NAME))
                            .isNull());
        }

        /**
         * {@code 1235-EDIT-ALPHA-OPT}: the same blank-the-letters-then-trim idiom as the required edit, so
         * an embedded space survives.
         *
         * @param keyed the value the screen transmitted
         */
        @ParameterizedTest(name = "[{0}] passes the optional alphabetic edit")
        @ValueSource(strings = {"MARY ANN", "Aniya Von", "Smith", " Leading", "Trailing "})
        @DisplayName("1235-EDIT-ALPHA-OPT: letters and spaces pass, embedded spaces included")
        void theOptionalAlphabeticEditAcceptsLettersAndSpaces(final String keyed) {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphaOptional(state, AccountUpdateService.ScreenField.MIDDLE_NAME, keyed);

            assertThat(state.flag(AccountUpdateService.ScreenField.MIDDLE_NAME))
                    .isEqualTo(AccountUpdateService.FieldFlag.ISVALID);
        }

        /**
         * {@code 1235-EDIT-ALPHA-OPT}: a supplied value still faces the character-class test, which is
         * why wiring this paragraph would reject a middle name carrying a digit - the behaviour the
         * migration directive forbids.
         *
         * @param keyed the value the screen transmitted
         */
        @ParameterizedTest(name = "[{0}] fails the optional alphabetic edit")
        @ValueSource(strings = {"Mary1", "M4RY", "Mary-Ann", "0"})
        @DisplayName("1235-EDIT-ALPHA-OPT: a supplied non-alphabetic value flags NOT_OK and claims the "
                + "alphabets-only text, which is what wiring it would inflict on the middle name")
        void theOptionalAlphabeticEditRefusesASuppliedNonAlphabeticValue(final String keyed) {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphaOptional(state, AccountUpdateService.ScreenField.MIDDLE_NAME, keyed);

            assertAll(
                    () -> assertThat(state.flag(AccountUpdateService.ScreenField.MIDDLE_NAME))
                            .isEqualTo(AccountUpdateService.FieldFlag.NOT_OK),
                    () -> assertThat(state.messageFor(AccountUpdateService.ScreenField.MIDDLE_NAME))
                            .isEqualTo(LABEL_MIDDLE_NAME + SUFFIX_ALPHABETS_ONLY));
        }

        /** {@code 1240-EDIT-ALPHANUM-OPT}: blank leaves early at source lines 2072 to 2073. */
        @Test
        @DisplayName("1240-EDIT-ALPHANUM-OPT: an unsupplied value is valid")
        void theOptionalAlphanumericEditAcceptsAnUnsuppliedValue() {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphanumericOptional(state,
                    AccountUpdateService.ScreenField.ADDRESS_LINE_2, "   ");

            assertAll(
                    () -> assertThat(state.flag(AccountUpdateService.ScreenField.ADDRESS_LINE_2))
                            .isEqualTo(AccountUpdateService.FieldFlag.ISVALID),
                    () -> assertThat(state.messageFor(AccountUpdateService.ScreenField.ADDRESS_LINE_2))
                            .isNull());
        }

        /**
         * {@code 1240-EDIT-ALPHANUM-OPT}: the source comment above it claims letters and spaces only, and
         * the statement converts the 62-character table, so a digit passes. The code governs.
         *
         * @param keyed the value the screen transmitted
         */
        @ParameterizedTest(name = "[{0}] passes the optional alphanumeric edit")
        @ValueSource(strings = {"221B Baker Street", "APT 4", "4", "Flat 2B"})
        @DisplayName("1240-EDIT-ALPHANUM-OPT: a digit passes although the comment above the paragraph "
                + "says otherwise, because the statement converts the alphanumeric table")
        void theOptionalAlphanumericEditAcceptsDigitsDespiteItsComment(final String keyed) {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphanumericOptional(state,
                    AccountUpdateService.ScreenField.ADDRESS_LINE_2, keyed);

            assertThat(state.flag(AccountUpdateService.ScreenField.ADDRESS_LINE_2))
                    .isEqualTo(AccountUpdateService.FieldFlag.ISVALID);
        }

        /**
         * {@code 1240-EDIT-ALPHANUM-OPT}: the failing arm, which claims the same suffix as its required
         * sibling because both convert the same table.
         *
         * @param keyed the value the screen transmitted
         */
        @ParameterizedTest(name = "[{0}] fails the optional alphanumeric edit")
        @ValueSource(strings = {"221B-Baker", "Apt #4", "P.O. Box"})
        @DisplayName("1240-EDIT-ALPHANUM-OPT: a value outside the table flags NOT_OK and claims the "
                + "numbers-or-alphabets text")
        void theOptionalAlphanumericEditRefusesEverythingOutsideItsTable(final String keyed) {
            final AccountUpdateService.EditState state = new AccountUpdateService.EditState();

            service.editAlphanumericOptional(state,
                    AccountUpdateService.ScreenField.ADDRESS_LINE_2, keyed);

            assertAll(
                    () -> assertThat(state.flag(AccountUpdateService.ScreenField.ADDRESS_LINE_2))
                            .isEqualTo(AccountUpdateService.FieldFlag.NOT_OK),
                    () -> assertThat(state.messageFor(AccountUpdateService.ScreenField.ADDRESS_LINE_2))
                            .isEqualTo(LABEL_ADDRESS_LINE_2 + SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
        }

    }

    /* ==========================================================================================
     * The date cascade, delegated in full rather than re-implemented.
     * ========================================================================================== */

    @Nested
    @DisplayName("the date cascade, delegated whole to the collaborator that owns its fourteen "
            + "paragraphs, because performing only its head paragraph would silently skip every stage")
    class DateValidationDelegation {

        @Test
        @DisplayName("delegate all four date groups, composing each candidate positionally, so no "
                + "date is parsed here")
        void allFourGroupsAreDelegated() {
            stubSeededReads();
            stubAllEditsAccepting();
            final ArgumentCaptor<String> candidates = ArgumentCaptor.forClass(String.class);

            service.handle(cleanChangedTurn().build());

            verify(dateValidationService, times(4))
                    .validateCcyymmddDate(candidates.capture(), any());
            assertThat(candidates.getAllValues())
                    .containsExactly("20200115", "20290115", "20240115", "19800203");
        }

        @Test
        @DisplayName("reach the date-of-birth check through its own entry point and compare against "
                + "the injected clock, so the outcome is deterministic")
        void theDateOfBirthCheckUsesItsOwnEntryPointAndTheInjectedClock() {
            stubSeededReads();
            stubAllEditsAccepting();
            final ArgumentCaptor<LocalDate> asOf = ArgumentCaptor.forClass(LocalDate.class);

            service.handle(cleanChangedTurn().build());

            verify(dateValidationService)
                    .validateDateOfBirth(eq("19800203"), asOf.capture(), any());
            assertThat(asOf.getValue()).isEqualTo(LocalDate.of(2024, 5, 6));
        }

        @Test
        @DisplayName("skip the date-of-birth check when the group itself came back invalid, which is "
                + "the gate the source applies")
        void theDateOfBirthCheckIsGatedOnTheGroup() {
            stubSeededReads();
            when(dateValidationService.validateCcyymmddDate(any(), any()))
                    .thenReturn(acceptingDateResult());
            when(dateValidationService.validateCcyymmddDate(eq("19801399"), any()))
                    .thenReturn(rejectingDateResult("Date of Birth is not valid"));
            stubAcceptingAreaCodes();
            stubAcceptingStateCode();
            stubAcceptingStateZipComposite();

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().dateOfBirth("1980", "13", "99").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactly("DOBYEAR", "DOBMON", "DOBDAY"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo("Date of Birth is not valid"));
            verify(dateValidationService, never()).validateDateOfBirth(any(), any(), any());
        }

        @Test
        @DisplayName("carry the collaborator's three flags onto the three component fields, so one "
                + "rejected group reports against all three of its components")
        void aRejectedGroupReportsAgainstAllThreeComponents() {
            stubSeededReads();
            when(dateValidationService.validateCcyymmddDate(any(), any()))
                    .thenReturn(acceptingDateResult());
            when(dateValidationService.validateCcyymmddDate(eq("20291399"), any()))
                    .thenReturn(rejectingDateResult("Expiry Date is not valid"));
            stubAcceptingDateOfBirth();
            stubAcceptingAreaCodes();
            stubAcceptingStateCode();
            stubAcceptingStateZipComposite();

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().expiryDate("2029", "13", "99").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactly("EXPYEAR", "EXPMON", "EXPDAY"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo("Expiry Date is not valid"));
        }

        @Test
        @DisplayName("accept a calendar-impossible date when the collaborator accepts it, which is the "
                + "tolerated message number 2513 arriving through the seam: nothing here re-parses it")
        void aDateTheCollaboratorAcceptsIsAcceptedHere() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().openDate("2024", "02", "30").build());

            assertAll(
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.error()).isFalse(),
                    () -> assertThat(outcome.infoMessage())
                            .isEqualTo(INFO_PROMPT_FOR_CONFIRMATION));
            verify(dateValidationService).validateCcyymmddDate(eq("20240230"), any());
        }

        @Test
        @DisplayName("reject the very same date when the collaborator rejects it, so the verdict comes "
                + "from the seam in both directions and a collapsed boolean cannot hide here")
        void theSameDateIsRejectedWhenTheCollaboratorRejectsIt() {
            stubSeededReads();
            when(dateValidationService.validateCcyymmddDate(any(), any()))
                    .thenReturn(acceptingDateResult());
            when(dateValidationService.validateCcyymmddDate(eq("20240230"), any()))
                    .thenReturn(rejectingDateResult("Open Date is not valid"));
            stubAcceptingDateOfBirth();
            stubAcceptingAreaCodes();
            stubAcceptingStateCode();
            stubAcceptingStateZipComposite();

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().openDate("2024", "02", "30").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactly("OPNYEAR", "OPNMON", "OPNDAY"),
                    () -> assertThat(outcome.error()).isTrue());
        }

        @Test
        @DisplayName("the tolerated condition really is the one at message number 2513, and its "
                + "siblings at the same severity carry other numbers, so the two acceptance levels "
                + "cannot be collapsed into one")
        void theToleratedConditionIsTheOneAtMessageNumber2513() {
            assertAll(
                    () -> assertThat(DateValidationService.DateFeedback.UNSUPPORTED_RANGE
                            .getMessageNumber()).isEqualTo(TOLERATED_DATE_MESSAGE_NUMBER),
                    () -> assertThat(DateValidationService.DateFeedback.UNSUPPORTED_RANGE
                            .getSeverity()).isEqualTo(FAILING_DATE_SEVERITY),
                    () -> assertThat(DateValidationService.DateFeedback.INVALID_MONTH.getSeverity())
                            .isEqualTo(FAILING_DATE_SEVERITY),
                    () -> assertThat(DateValidationService.DateFeedback.INVALID_MONTH
                            .getMessageNumber()).isNotEqualTo(TOLERATED_DATE_MESSAGE_NUMBER),
                    () -> assertThat(DateValidationService.DateFeedback.DATE_IS_VALID.getSeverity())
                            .isZero());
        }

        @Test
        @DisplayName("the cascade's own message goes through the same first-error-wins gate, so a "
                + "date failure cannot displace an earlier one")
        void theCascadeMessagePassesThroughTheFirstErrorWinsGate() {
            stubSeededReads();
            when(dateValidationService.validateCcyymmddDate(any(), any()))
                    .thenReturn(acceptingDateResult());
            when(dateValidationService.validateCcyymmddDate(eq("20291399"), any()))
                    .thenReturn(rejectingDateResult("Expiry Date is not valid"));
            stubAcceptingDateOfBirth();
            stubAcceptingAreaCodes();
            stubAcceptingStateCode();
            stubAcceptingStateZipComposite();

            final AccountUpdateOutcome outcome = service.handle(cleanChangedTurn()
                    .accountStatus("X")
                    .expiryDate("2029", "13", "99")
                    .build());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_ACCOUNT_STATUS + SUFFIX_MUST_BE_Y_OR_N),
                    () -> assertThat(screenFieldIdsOf(outcome))
                            .containsExactly("ACSTTUS", "EXPYEAR", "EXPMON", "EXPDAY"));
        }
    }

    /* ==========================================================================================
     * The write range: one ordered rewrite pair, and the asymmetric rollback.
     * ========================================================================================== */

    @Nested
    @DisplayName("the write range, whose two rewrites share one unit of work and whose two failure "
            + "arms are deliberately asymmetric")
    class WriteRangeAndRollback {

        /** A confirmation turn: a change, every edit passing, and the save key pressed. */
        private AccountUpdateCommand confirmationTurn() {
            return cleanChangedTurn().keyAction(KeyAction.PFK05).build();
        }

        @Test
        @DisplayName("commit both rewrites in order, confirm the turn, and re-mint the before-image "
                + "so the next change is not refused as somebody else's")
        void bothRewritesCommitInOrder() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any())).thenReturn(1);
            when(concurrencyTokenService.mint(any(), any())).thenReturn(REMINTED_TOKEN);

            final AccountUpdateOutcome outcome = service.handle(confirmationTurn());

            final InOrder writes = inOrder(accountRepository, customerRepository);
            writes.verify(accountRepository).saveAndFlush(any());
            writes.verify(customerRepository).compareAndSet(any(), any());
            assertAll(
                    () -> assertThat(outcome.infoMessage()).isEqualTo(INFO_CONFIRM_UPDATE_SUCCESS),
                    () -> assertThat(outcome.errorMessage()).isNull(),
                    () -> assertThat(outcome.error()).isFalse(),
                    () -> assertThat(outcome.concurrencyToken()).isEqualTo(REMINTED_TOKEN));
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the account arm leaves an EMPTY unit of work to roll back, so the customer "
                + "rewrite is never reached - this is the first half of the asymmetry")
        void theAccountArmLeavesAnEmptyUnitOfWork() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(accountRepository.saveAndFlush(any()))
                    .thenThrow(new OptimisticLockingFailureException("the account row moved"));

            final AccountUpdateOutcome outcome = service.handle(confirmationTurn());

            assertAll(
                    () -> assertThat(outcome.errorMessage()).isEqualTo(CONFLICT_MSG_UPDATE_FAILED),
                    () -> assertThat(outcome.infoMessage()).isEqualTo(INFO_INFORM_FAILURE),
                    () -> assertThat(outcome.error()).isTrue(),
                    () -> assertThat(loggedMessages())
                            .anySatisfy(line -> assertThat(line)
                                    .contains("the empty unit of work was rolled back")
                                    .contains(RESOURCE_ACCOUNT_MASTER)));
            verify(customerRepository, never()).compareAndSet(any(), any());
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the customer arm rolls back a COMPLETED account rewrite, which the account arm "
                + "never has to do - this is the second half of the asymmetry")
        void theCustomerArmRollsBackACompletedAccountRewrite() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any()))
                    .thenThrow(new OptimisticLockingFailureException("the customer row moved"));

            final AccountUpdateOutcome outcome = service.handle(confirmationTurn());

            assertAll(
                    () -> assertThat(outcome.errorMessage()).isEqualTo(CONFLICT_MSG_UPDATE_FAILED),
                    () -> assertThat(outcome.infoMessage()).isEqualTo(INFO_INFORM_FAILURE),
                    () -> assertThat(loggedMessages())
                            .anySatisfy(line -> assertThat(line)
                                    .contains("the account rewrite completed earlier in the unit "
                                            + "and was rolled back")
                                    .contains(RESOURCE_CUSTOMER_MASTER)));
            verify(accountRepository).saveAndFlush(any());
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("a customer image that moved between the hold and the rewrite rolls the account "
                + "rewrite back and shows the detail again rather than reporting a failure")
        void aCustomerImageThatMovedRollsBackTheAccountRewrite() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any())).thenReturn(0);

            final AccountUpdateOutcome outcome = service.handle(confirmationTurn());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(CONFLICT_MSG_DATA_WAS_CHANGED),
                    () -> assertThat(outcome.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CHANGES),
                    () -> assertThat(loggedMessages())
                            .anySatisfy(line -> assertThat(line)
                                    .contains("the held customer image no longer matched")
                                    .contains("the account rewrite was rolled back")));
            verify(accountRepository).saveAndFlush(any());
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("a record that moved before the update is reported as a recoverable conflict and "
                + "never abends, so the abend service is untouched")
        void aRecordThatMovedBeforeTheUpdateNeverAbends() {
            stubSeededReads();
            stubAllEditsAccepting();
            doThrow(new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                    "Account", ACCOUNT_ID))
                    .when(concurrencyTokenService).verify(any(), any(), any());

            final AccountUpdateOutcome outcome = service.handle(confirmationTurn());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(CONFLICT_MSG_DATA_WAS_CHANGED),
                    () -> assertThat(outcome.error()).isTrue());
            verifyNoInteractions(transactionBoundary);
            verify(accountRepository, never()).saveAndFlush(any());
            verify(customerRepository, never()).compareAndSet(any(), any());
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("an account that cannot be held reports the lock text and never opens the unit "
                + "of work")
        void anAccountThatCannotBeHeldReportsTheLockText() {
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededCrossReference()));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededAccount()))
                    .thenReturn(Optional.empty());
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(seededCustomer()));
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service.handle(confirmationTurn());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(CONFLICT_MSG_COULD_NOT_LOCK_ACCOUNT),
                    () -> assertThat(outcome.infoMessage()).isEqualTo(INFO_INFORM_FAILURE));
            verifyNoInteractions(transactionBoundary);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the reproduced defect: a customer that cannot be held claims its own lock text, "
                + "which the outcome selection never tests, so the turn is reported as a completed "
                + "update")
        void aCustomerThatCannotBeHeldFallsToTheOtherwiseArm() {
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededCrossReference()));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededAccount()));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(seededCustomer()))
                    .thenReturn(Optional.empty());
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service.handle(confirmationTurn());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(CONFLICT_MSG_COULD_NOT_LOCK_CUSTOMER),
                    () -> assertThat(outcome.infoMessage()).isEqualTo(INFO_CONFIRM_UPDATE_SUCCESS));
            verifyNoInteractions(transactionBoundary);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the write range is a single pass that terminates on the legacy's own condition, "
                + "with no repeat attempt, no delay, no backoff and no timeout anywhere")
        void theWriteRangeIsASinglePassWithNoDelay() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any())).thenReturn(1);
            when(concurrencyTokenService.mint(any(), any())).thenReturn(REMINTED_TOKEN);

            service.handle(confirmationTurn());

            assertAll(
                    () -> verify(accountRepository, times(2)).findById(ACCOUNT_ID),
                    () -> verify(customerRepository, times(2)).findById(CUSTOMER_ID),
                    () -> verify(transactionBoundary, times(1)).execute(any()),
                    () -> verify(accountRepository, times(1)).saveAndFlush(any()),
                    () -> verify(customerRepository, times(1)).compareAndSet(any(), any()),
                    () -> verify(concurrencyTokenService, times(1)).verify(any(), any(), any()));
            verifyNoMoreInteractions(accountRepository, customerRepository,
                    cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("the conflict enumeration has exactly the three write-path outcomes the estate "
                + "declares, and no fourth")
        void theConflictEnumerationHasExactlyThreeConstants() {
            assertAll(
                    () -> assertThat(OptimisticLockConflictException.ConflictKind.values())
                            .hasSize(3),
                    () -> assertThat(OptimisticLockConflictException.ConflictKind.values())
                            .containsExactly(
                                    OptimisticLockConflictException.ConflictKind
                                            .RECORD_CHANGED_BEFORE_UPDATE,
                                    OptimisticLockConflictException.ConflictKind
                                            .UPDATE_FAILED_AFTER_LOCK,
                                    OptimisticLockConflictException.ConflictKind
                                            .LOCK_NOT_ACQUIRED));
        }

        @Test
        @DisplayName("all four conflict texts match the legacy literals byte for byte, the "
                + "changed-before text carrying \"some one\" as two words")
        void allFourConflictTextsMatchByteForByte() {
            assertAll(
                    () -> assertThat(
                            OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE)
                            .isEqualTo(CONFLICT_MSG_DATA_WAS_CHANGED)
                            .contains("some one")
                            .doesNotContain("someone"),
                    () -> assertThat(OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED)
                            .isEqualTo(CONFLICT_MSG_UPDATE_FAILED),
                    () -> assertThat(
                            OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE)
                            .isEqualTo(CONFLICT_MSG_COULD_NOT_LOCK_ACCOUNT),
                    () -> assertThat(
                            OptimisticLockConflictException.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE)
                            .isEqualTo(CONFLICT_MSG_COULD_NOT_LOCK_CUSTOMER),
                    () -> assertThat(OptimisticLockConflictException.ConflictKind
                            .LOCK_NOT_ACQUIRED.defaultMessage("Customer"))
                            .isEqualTo(CONFLICT_MSG_COULD_NOT_LOCK_CUSTOMER),
                    () -> assertThat(OptimisticLockConflictException.ConflictKind
                            .LOCK_NOT_ACQUIRED.defaultMessage("Account"))
                            .isEqualTo(CONFLICT_MSG_COULD_NOT_LOCK_ACCOUNT));
        }

        @Test
        @DisplayName("an unchanged confirmation turn writes nothing at all and prompts again, because "
                + "the no-change arm demotes the state before the outcome selection is reached")
        void anUnchangedConfirmationTurnWritesNothing() {
            stubSeededReads();

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().keyAction(KeyAction.PFK05).build());

            assertAll(
                    () -> assertThat(outcome.errorMessage()).isEqualTo(MSG_NO_CHANGES_DETECTED),
                    () -> assertThat(outcome.fieldErrors()).isEmpty());
            verifyNoInteractions(transactionBoundary);
            verify(accountRepository, never()).saveAndFlush(any());
            verify(customerRepository, never()).compareAndSet(any(), any());
        }

        @Test
        @DisplayName("the confirmation turn re-edits the image it is given, so a value altered "
                + "between validation and saving is refused and nothing is written")
        void theConfirmationTurnReEditsTheSubmittedImage() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service.handle(cleanChangedTurn()
                    .keyAction(KeyAction.PFK05)
                    .ficoScore("851")
                    .build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACSTFCO"),
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(LABEL_FICO_SCORE + SUFFIX_FICO_OUT_OF_RANGE));
            verifyNoInteractions(transactionBoundary);
        }
    }

    /* ==========================================================================================
     * The cross-reference read, which is one keyed read of a duplicate-bearing access path.
     * ========================================================================================== */

    @Nested
    @DisplayName("the cross-reference read, bounded to the one row a keyed read of the account path "
            + "would return")
    class CrossReferenceResolution {

        @Test
        @DisplayName("an empty result is the not-found condition, and it claims the FIRST of the two "
                + "identically named condition texts - the second declaration is unreachable")
        void anEmptyResultClaimsTheFirstDeclaredText() {
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().concurrencyToken(null).build());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(MSG_ACCOUNT_NOT_IN_XREF_FIRST_DECLARED),
                    () -> assertThat(outcome.errorMessage())
                            .isNotEqualTo(MSG_ACCOUNT_NOT_IN_XREF_SHADOWED),
                    () -> assertThat(outcome.error()).isTrue());
            verifyNoInteractions(accountRepository, customerRepository);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the ordered-first finder is the keyed read, so the unbounded finder that would "
                + "return every row of the account is never used")
        void theOrderedFirstFinderIsTheKeyedRead() {
            stubSeededReads();

            service.handle(unchangedTurn().build());

            verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(cardCrossReferenceRepository, never()).findByXrefAcctId(any());
        }

        @Test
        @DisplayName("the row the ordered-first finder answers with is the lowest card number of the "
                + "account, which is the row a keyed read of the base key returns, and it is the row "
                + "whose customer identifier drives the customer read")
        void theLowestCardNumberIsTheRowTheReadReturns() {
            stubSeededReads();
            final ArgumentCaptor<String> customerKey = ArgumentCaptor.forClass(String.class);

            final AccountUpdateOutcome outcome = service.handle(unchangedTurn().build());

            verify(customerRepository).findById(customerKey.capture());
            assertAll(
                    () -> assertThat(CARD_NUMBER).isLessThan(HIGHER_CARD_NUMBER),
                    () -> assertThat(customerKey.getValue()).isEqualTo(CUSTOMER_ID),
                    () -> assertThat(outcome.navigationContext().cardNumber())
                            .isEqualTo(CARD_NUMBER)
                            .isNotEqualTo(HIGHER_CARD_NUMBER));
        }

        @Test
        @DisplayName("the account key drives the read, and the resolved customer key drives the "
                + "customer read, so neither is invented here")
        void bothKeysComeFromTheRecordsThemselves() {
            stubSeededReads();
            final ArgumentCaptor<String> accountKey = ArgumentCaptor.forClass(String.class);

            service.handle(unchangedTurn().build());

            verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(accountKey.capture());
            assertThat(accountKey.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("an account the master does not hold is reported on the screen with the declared "
                + "text: an outcome is returned rather than a record-not-found exception raised, and "
                + "the customer master is never reached")
        void anAbsentAccountIsReportedOnTheScreen() {
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededCrossReference()));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().concurrencyToken(null).build());

            assertAll(
                    () -> assertThat(outcome).isNotNull(),
                    () -> assertThat(outcome.errorMessage()).isEqualTo(MSG_ACCOUNT_NOT_IN_MASTER),
                    () -> assertThat(outcome.error()).isTrue());
            verifyNoInteractions(customerRepository);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("a customer the master does not hold is likewise reported on the screen")
        void anAbsentCustomerIsReportedOnTheScreen() {
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededCrossReference()));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(seededAccount()));
            when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().concurrencyToken(null).build());

            assertAll(
                    () -> assertThat(outcome.errorMessage()).isEqualTo(MSG_CUSTOMER_NOT_IN_MASTER),
                    () -> assertThat(outcome.error()).isTrue());
            verifyNoInteractions(abendService);
        }
    }

    /* ==========================================================================================
     * Attention keys: the high keys fold, and the mapping has no catch-all.
     * ========================================================================================== */

    @Nested
    @DisplayName("the attention keys, whose mapping folds the high keys onto the low ones and declares "
            + "no catch-all clause")
    class AttentionKeys {

        @Test
        @DisplayName("the exit key resolves the caller's destination through the navigation service")
        void theExitKeyResolvesThroughTheNavigationService() {
            when(navigationService.resolveBackNavigation(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().build(), RAW_KEY_EXIT);

            assertAll(
                    () -> assertThat(outcome.nextRoute()).isEqualTo(ROUTE_USER_MENU),
                    () -> assertThat(outcome.navigationContext().fromTransactionId())
                            .isEqualTo(LEGACY_TRANSACTION_ID),
                    () -> assertThat(outcome.navigationContext().fromProgram())
                            .isEqualTo(LEGACY_PROGRAM_ID));
            verifyNoInteractions(accountRepository, customerRepository,
                    cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("the high exit key behaves identically to the low one, so keys 13 to 24 are not "
                + "distinct actions")
        void theHighExitKeyFoldsOntoTheLowOne() {
            when(navigationService.resolveBackNavigation(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final AccountUpdateOutcome low =
                    service.handle(unchangedTurn().build(), RAW_KEY_EXIT);
            final AccountUpdateOutcome folded =
                    service.handle(unchangedTurn().build(), RAW_KEY_EXIT_FOLDED);

            assertAll(
                    () -> assertThat(folded.nextRoute()).isEqualTo(low.nextRoute()),
                    () -> assertThat(folded.errorMessage()).isEqualTo(low.errorMessage()),
                    () -> assertThat(folded.infoMessage()).isEqualTo(low.infoMessage()),
                    () -> assertThat(folded.error()).isEqualTo(low.error()));
        }

        @Test
        @DisplayName("the high save key folds too, so a confirmation arrives either way and the "
                + "rewrite pair runs")
        void theHighSaveKeyFoldsOntoTheLowOne() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any())).thenReturn(1);
            when(concurrencyTokenService.mint(any(), any())).thenReturn(REMINTED_TOKEN);

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().keyAction(KeyAction.PFK05).build(), RAW_KEY_SAVE_FOLDED);

            assertThat(outcome.infoMessage()).isEqualTo(INFO_CONFIRM_UPDATE_SUCCESS);
            verify(accountRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("the low save key reaches the same place, which is what makes the pair a fold "
                + "rather than two behaviours")
        void theLowSaveKeyReachesTheSamePlace() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any())).thenReturn(1);
            when(concurrencyTokenService.mint(any(), any())).thenReturn(REMINTED_TOKEN);

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().keyAction(KeyAction.PFK05).build(), RAW_KEY_SAVE);

            assertThat(outcome.infoMessage()).isEqualTo(INFO_CONFIRM_UPDATE_SUCCESS);
            verify(accountRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("an identifier the mapping declares no clause for produces no action at all, so "
                + "the turn claims the catalogue's invalid-key text and stays on this route")
        void anUnmappedIdentifierClaimsTheCatalogueText() {
            when(messageCatalogService.invalidKeyMessage())
                    .thenReturn(INVALID_KEY_MESSAGE_PADDED);

            final AccountUpdateOutcome outcome = service.handle(
                    unchangedTurn().concurrencyToken(null).accountId("0").build(),
                    RAW_KEY_UNMAPPED);

            assertAll(
                    () -> assertThat(outcome.errorMessage()).isEqualTo(INVALID_KEY_MESSAGE_PADDED),
                    () -> assertThat(outcome.nextRoute()).isEqualTo(ROUTE_ACCOUNT_UPDATE),
                    () -> assertThat(outcome.error()).isTrue());
            verifyNoInteractions(accountRepository, customerRepository,
                    cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("the catalogue text crosses the boundary at exactly 50 encoded bytes with its "
                + "ten trailing spaces intact, measured on bytes and never trimmed")
        void theCatalogueTextIsFiftyEncodedBytesWithItsTrailingSpaces() {
            when(messageCatalogService.invalidKeyMessage())
                    .thenReturn(INVALID_KEY_MESSAGE_PADDED);

            final AccountUpdateOutcome outcome = service.handle(
                    unchangedTurn().concurrencyToken(null).accountId("0").build(),
                    RAW_KEY_UNMAPPED);
            final String emitted = outcome.errorMessage();

            assertAll(
                    () -> assertThat(emitted.getBytes(StandardCharsets.US_ASCII).length)
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(emitted)
                            .endsWith(" ".repeat(INVALID_KEY_TRAILING_SPACES)),
                    () -> assertThat(emitted).isNotEqualTo(emitted.trim()),
                    () -> assertThat(emitted).startsWith(INVALID_KEY_TEXT),
                    () -> assertThat(INVALID_KEY_TEXT.length())
                            .isEqualTo(COMMON_MESSAGE_WIDTH - INVALID_KEY_TRAILING_SPACES));
        }

        @Test
        @DisplayName("an omitted raw identifier takes the typed action the submission already carries, "
                + "which is the path a caller that resolved the key itself uses")
        void anOmittedRawIdentifierTakesTheTypedAction() {
            when(navigationService.resolveBackNavigation(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().keyAction(KeyAction.PFK03).build());

            assertThat(outcome.nextRoute()).isEqualTo(ROUTE_USER_MENU);
        }

        @Test
        @DisplayName("a save key pressed outside the confirmation state is forced to enter, so an "
                + "out-of-context key redisplays the screen instead of writing")
        void anOutOfContextSaveKeyIsForcedToEnter() {
            final AccountUpdateOutcome outcome = service.handle(unchangedTurn()
                    .concurrencyToken(null)
                    .accountId("0")
                    .keyAction(KeyAction.PFK05)
                    .build());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(MSG_ACCOUNT_NUMBER_MALFORMED),
                    () -> assertThat(outcome.nextRoute()).isEqualTo(ROUTE_ACCOUNT_UPDATE));
            verifyNoInteractions(transactionBoundary);
            verify(accountRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("the 28 identifiers the mapping recognises collapse onto 16 outcomes, which is "
                + "the whole action enumeration and has no unknown member")
        void twentyEightIdentifiersCollapseOntoSixteenOutcomes() {
            assertAll(
                    () -> assertThat(KeyAction.values())
                            .hasSize(DISTINCT_ATTENTION_OUTCOME_COUNT),
                    () -> assertThat(RECOGNISED_ATTENTION_INPUT_COUNT)
                            .isGreaterThan(DISTINCT_ATTENTION_OUTCOME_COUNT),
                    () -> assertThat(KeyAction.values())
                            .noneMatch(action -> "UNKNOWN".equals(action.name())));
        }
    }

    /* ==========================================================================================
     * The diagnostic channel, and the one abend site.
     * ========================================================================================== */

    @Nested
    @DisplayName("the diagnostic channel and the single abend site, which the dispatch selection's "
            + "otherwise branch guards and which no recoverable path reaches")
    class DiagnosticChannelAndAbendSeam {

        @Test
        @DisplayName("the write-failure diagnostic is already emitted by the time the outcome is "
                + "returned, so the record precedes the screen it explains")
        void theDiagnosticIsEmittedBeforeTheOutcomeIsReturned() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any()))
                    .thenThrow(new OptimisticLockingFailureException("the customer row moved"));

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().keyAction(KeyAction.PFK05).build());

            assertAll(
                    () -> assertThat(loggedMessages()).isNotEmpty(),
                    () -> assertThat(loggedMessages())
                            .anySatisfy(line -> assertThat(line)
                                    .contains(RESOURCE_CUSTOMER_MASTER)
                                    .contains("rolled back")),
                    () -> assertThat(outcome.errorMessage()).isEqualTo(CONFLICT_MSG_UPDATE_FAILED));
        }

        @Test
        @DisplayName("the diagnostic names the resource and the transaction and carries no regulated "
                + "value, no national identifier and no amount")
        void theDiagnosticCarriesNoRegulatedValue() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any())).thenReturn(0);

            service.handle(cleanChangedTurn().keyAction(KeyAction.PFK05).build());

            assertThat(loggedMessages()).allSatisfy(line -> assertThat(line)
                    .doesNotContain("123456789")
                    .doesNotContain(SEEDED_CREDIT_LIMIT.toPlainString())
                    .doesNotContain(SEEDED_CURRENT_BALANCE.toPlainString()));
        }

        @Test
        @DisplayName("the conflict path records the conflict kind, which is what makes the record "
                + "actionable without exposing the records themselves")
        void theConflictPathRecordsTheConflictKind() {
            stubSeededReads();
            stubAllEditsAccepting();
            doThrow(new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                    "Account", ACCOUNT_ID))
                    .when(concurrencyTokenService).verify(any(), any(), any());

            service.handle(cleanChangedTurn().keyAction(KeyAction.PFK05).build());

            assertThat(loggedMessages()).anySatisfy(line -> assertThat(line)
                    .contains(OptimisticLockConflictException.ConflictKind
                            .RECORD_CHANGED_BEFORE_UPDATE.name())
                    .contains("the records moved before the update"));
        }

        @Test
        @DisplayName("no recoverable path reaches the abend service: the search-key turn, the decorated "
                + "redisplay, the confirmation, the conflict and the failed write all leave it "
                + "untouched")
        void noRecoverablePathReachesTheAbendService() {
            when(messageCatalogService.invalidKeyMessage())
                    .thenReturn(INVALID_KEY_MESSAGE_PADDED);

            service.handle(unchangedTurn().navigationContext(null).concurrencyToken(null).build());
            service.handle(unchangedTurn().concurrencyToken(null).accountId("0").build(),
                    RAW_KEY_UNMAPPED);

            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the decorated redisplay and the unchanged turn leave the abend service untouched "
                + "too, so the whole ordinary conversation is abend-free")
        void theOrdinaryConversationIsAbendFree() {
            stubSeededReads();
            when(dateValidationService.validateCcyymmddDate(any(), any()))
                    .thenReturn(rejectingDateResult("Open Date is not valid"));

            service.handle(everyEditableFieldFailingTurn().build());

            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the abend routine emits its own diagnostic before it raises, which is why the "
                + "exception types carry no logger; the site is the dispatch otherwise branch and is "
                + "defensively unreachable from either entry point")
        void theAbendRoutineDiagnosesBeforeItRaises() {
            stubSeededReads();
            stubAllEditsAccepting();
            stubBoundaryRunsInline();
            when(customerRepository.compareAndSet(any(), any())).thenReturn(1);
            when(concurrencyTokenService.mint(any(), any())).thenReturn(REMINTED_TOKEN);

            final AccountUpdateOutcome committed = service.handle(
                    cleanChangedTurn().keyAction(KeyAction.PFK05).build());
            final AccountUpdateOutcome acknowledged = service.handle(cleanChangedTurn()
                    .keyAction(KeyAction.PFK05)
                    .concurrencyToken(REMINTED_TOKEN)
                    .build());

            assertAll(
                    () -> assertThat(committed.infoMessage())
                            .isEqualTo(INFO_CONFIRM_UPDATE_SUCCESS),
                    () -> assertThat(acknowledged).isNotNull(),
                    () -> assertThat(loggedMessages()).isNotEmpty());
            verifyNoInteractions(abendService);
        }
    }

    /* ==========================================================================================
     * Field-level rendering fidelity.
     * ========================================================================================== */

    @Nested
    @DisplayName("field-level rendering fidelity: fixed widths measured on bytes, absence tolerated, "
            + "and every amount truncated toward zero")
    class RenderingFidelity {

        @Test
        @DisplayName("the account group identifier is carried at its full ten-character width, "
                + "untrimmed, and is never mistaken for an absent value")
        void theGroupIdentifierIsCarriedUntrimmed() {
            stubSeededReads();

            final AccountUpdateOutcome outcome = service.handle(unchangedTurn().build());

            assertAll(
                    () -> assertThat(outcome.accountGroupId())
                            .isEqualTo(ACCOUNT_GROUP_ID_TEN_SPACES),
                    () -> assertThat(outcome.accountGroupId()).isNotNull(),
                    () -> assertThat(outcome.accountGroupId()
                            .getBytes(StandardCharsets.US_ASCII).length).isEqualTo(10),
                    () -> assertThat(outcome.accountGroupId()).isNotEqualTo(""),
                    () -> assertThat(outcome.accountGroupId().isBlank()).isTrue());
        }

        @Test
        @DisplayName("an absent national identifier renders without throwing, the column being the "
                + "schema's only nullable one and null in all fifty seeded rows")
        void anAbsentNationalIdentifierRenders() {
            stubSeededReads();

            assertThatNoException().isThrownBy(() -> service.handle(unchangedTurn().build()));

            final AccountUpdateOutcome outcome = service.handle(unchangedTurn().build());
            assertAll(
                    () -> assertThat(outcome.ssnPart1()).isNull(),
                    () -> assertThat(outcome.ssnPart2()).isNull(),
                    () -> assertThat(outcome.ssnPart3()).isNull(),
                    () -> assertThat(outcome.governmentIssuedId()).isNull());
        }

        @Test
        @DisplayName("a submitted amount is truncated toward zero, not rounded: a third decimal digit "
                + "of six is dropped where half-even would have carried it")
        void aSubmittedAmountIsTruncatedTowardZero() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().creditLimit(TRUNCATING_AMOUNT_LEXEME).build());

            assertAll(
                    () -> assertThat(outcome.creditLimit()).isEqualTo(TRUNCATED_AMOUNT),
                    () -> assertThat(outcome.creditLimit().scale()).isEqualTo(MONEY_SCALE),
                    () -> assertThat(outcome.creditLimit())
                            .isNotEqualTo(new BigDecimal("1234.57")),
                    () -> assertThat(outcome.fieldErrors()).isEmpty());
        }

        @Test
        @DisplayName("a negative amount truncates toward zero as well, so the magnitude falls rather "
                + "than the value")
        void aNegativeAmountTruncatesTowardZero() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().currentBalance("-" + TRUNCATING_AMOUNT_LEXEME).build());

            assertAll(
                    () -> assertThat(outcome.currentBalance())
                            .isEqualTo(TRUNCATED_AMOUNT.negate()),
                    () -> assertThat(outcome.currentBalance().scale()).isEqualTo(MONEY_SCALE),
                    () -> assertThat(outcome.currentBalance())
                            .isNotEqualTo(new BigDecimal("-1234.57")));
        }

        @Test
        @DisplayName("every monetary component carries the record field's own scale, so no component "
                + "reaches a client at a scale the record cannot hold")
        void everyMonetaryComponentCarriesTheRecordScale() {
            stubSeededReads();

            final AccountUpdateOutcome outcome = service.handle(unchangedTurn().build());

            assertAll(
                    () -> assertThat(outcome.creditLimit().scale()).isEqualTo(MONEY_SCALE),
                    () -> assertThat(outcome.cashCreditLimit().scale()).isEqualTo(MONEY_SCALE),
                    () -> assertThat(outcome.currentBalance().scale()).isEqualTo(MONEY_SCALE),
                    () -> assertThat(outcome.currentCycleCredit().scale()).isEqualTo(MONEY_SCALE),
                    () -> assertThat(outcome.currentCycleDebit().scale()).isEqualTo(MONEY_SCALE),
                    () -> assertThat(AccountUpdateOutcome.MONEY_SCALE).isEqualTo(MONEY_SCALE));
        }

        @Test
        @DisplayName("the stored amounts reach the screen unchanged in value, so no rescaling is "
                + "performed on the display path either")
        void storedAmountsReachTheScreenUnchanged() {
            stubSeededReads();

            final AccountUpdateOutcome outcome = service.handle(unchangedTurn().build());

            assertAll(
                    () -> assertThat(outcome.creditLimit())
                            .isEqualByComparingTo(SEEDED_CREDIT_LIMIT),
                    () -> assertThat(outcome.currentBalance())
                            .isEqualByComparingTo(SEEDED_CURRENT_BALANCE),
                    () -> assertThat(outcome.cashCreditLimit())
                            .isEqualByComparingTo(SEEDED_CASH_CREDIT_LIMIT),
                    () -> assertThat(outcome.currentCycleCredit())
                            .isEqualByComparingTo(SEEDED_CYCLE_CREDIT),
                    () -> assertThat(outcome.currentCycleDebit())
                            .isEqualByComparingTo(SEEDED_CYCLE_DEBIT));
        }

        @Test
        @DisplayName("an amount whose magnitude exceeds the record's ten integer digits is refused "
                + "rather than silently truncated at the high order, which is the fail-safe direction")
        void anOversizedAmountIsRefused() {
            stubSeededReads();
            stubAllEditsAccepting();

            final AccountUpdateOutcome outcome = service.handle(
                    cleanChangedTurn().creditLimit("99999999999.99").build());

            assertAll(
                    () -> assertThat(screenFieldIdsOf(outcome)).containsExactly("ACRDLIM"),
                    () -> assertThat(outcome.errorMessage()).endsWith(SUFFIX_IS_NOT_VALID),
                    () -> assertThat(AccountUpdateOutcome.MONEY_INTEGER_DIGITS).isEqualTo(10));
        }

        @Test
        @DisplayName("this transaction's own identity reaches the screen as the legacy names it, map "
                + "and mapset included")
        void theTransactionIdentityReachesTheScreen() {
            stubSeededReads();

            final AccountUpdateOutcome outcome = service.handle(unchangedTurn().build());

            assertAll(
                    () -> assertThat(outcome.navigationContext().fromTransactionId())
                            .isEqualTo(LEGACY_TRANSACTION_ID),
                    () -> assertThat(outcome.navigationContext().fromProgram())
                            .isEqualTo(LEGACY_PROGRAM_ID),
                    () -> assertThat(outcome.navigationContext().lastMap()).isEqualTo(LEGACY_MAP),
                    () -> assertThat(outcome.navigationContext().lastMapset())
                            .isEqualTo(LEGACY_MAPSET),
                    () -> assertThat(outcome.nextRoute()).isEqualTo(ROUTE_ACCOUNT_UPDATE));
        }
    }

    /* ==========================================================================================
     * Repository interaction discipline.
     * ========================================================================================== */

    @Nested
    @DisplayName("repository interaction discipline: three reads, in order, once each, and no ordering "
            + "argument anywhere because the ordering the read needs is declared in the finder name")
    class RepositoryInteractionDiscipline {

        @Test
        @DisplayName("the three reads run in the declared order: the cross-reference path, then the "
                + "account master, then the customer master keyed by what the cross-reference gave")
        void theThreeReadsRunInTheDeclaredOrder() {
            stubSeededReads();

            service.handle(unchangedTurn().build());

            final InOrder reads = inOrder(cardCrossReferenceRepository, accountRepository,
                    customerRepository);
            reads.verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            reads.verify(accountRepository).findById(ACCOUNT_ID);
            reads.verify(customerRepository).findById(CUSTOMER_ID);
            reads.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("each read happens exactly once on a turn that does not write, and nothing else "
                + "touches a repository")
        void eachReadHappensExactlyOnce() {
            stubSeededReads();

            service.handle(unchangedTurn().build());

            verify(cardCrossReferenceRepository, times(1))
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(accountRepository, times(1)).findById(ACCOUNT_ID);
            verify(customerRepository, times(1)).findById(CUSTOMER_ID);
            verifyNoMoreInteractions(cardCrossReferenceRepository, accountRepository,
                    customerRepository);
        }

        @Test
        @DisplayName("no repository call carries a sort or a page request, because the only ordering "
                + "this feature depends on is declared in the ordered-first finder's own name")
        void noRepositoryCallCarriesAnOrdering() {
            stubSeededReads();

            service.handle(unchangedTurn().build());

            verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(accountRepository).findById(ACCOUNT_ID);
            verify(customerRepository).findById(CUSTOMER_ID);
            verify(cardCrossReferenceRepository, never()).findByXrefAcctId(any());
            verifyNoMoreInteractions(cardCrossReferenceRepository, accountRepository,
                    customerRepository);
        }

        @Test
        @DisplayName("the before-image is minted only when none arrived, so a turn that echoed one "
                + "never has its own records compared against themselves")
        void theBeforeImageIsMintedOnlyWhenNoneArrived() {
            stubSeededReads();

            service.handle(unchangedTurn().build());

            verify(concurrencyTokenService, never()).mint(any(), any());
        }

        @Test
        @DisplayName("a turn that carried no before-image mints one over the records it just fetched")
        void aTurnWithNoBeforeImageMintsOne() {
            stubSeededReads();
            when(concurrencyTokenService.mint(any(), any())).thenReturn(TOKEN);

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().concurrencyToken(null).build());

            assertThat(outcome.concurrencyToken()).isEqualTo(TOKEN);
            verify(concurrencyTokenService).mint(any(), any());
        }

        @Test
        @DisplayName("a turn that never gets past the key edit touches no repository at all")
        void aRejectedKeyTouchesNoRepository() {
            final AccountUpdateOutcome outcome = service.handle(
                    unchangedTurn().concurrencyToken(null).accountId("abc").build());

            assertThat(outcome.errorMessage()).isEqualTo(MSG_ACCOUNT_NUMBER_MALFORMED);
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    customerRepository, transactionBoundary, concurrencyTokenService);
        }
    }

    /* ==========================================================================================
     * Absent and boundary input at both entry points.
     * ========================================================================================== */

    @Nested
    @DisplayName("absent and boundary input at both entry points, none of which may escape as a "
            + "runtime failure")
    class AbsentAndBoundaryInput {

        @Test
        @DisplayName("an absent submission is a programming error at both entry points, and says so")
        void anAbsentSubmissionIsAProgrammingError() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> service.handle(null))
                            .withMessageContaining("request must not be null"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> service.handle(null, RAW_KEY_EXIT))
                            .withMessageContaining("request must not be null"));
        }

        @Test
        @DisplayName("an absent raw identifier is not an error: it means the caller resolved the key "
                + "itself, so the typed action is taken")
        void anAbsentRawIdentifierIsNotAnError() {
            when(navigationService.resolveBackNavigation(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().keyAction(KeyAction.PFK03).build(), null);

            assertThat(outcome.nextRoute()).isEqualTo(ROUTE_USER_MENU);
        }

        @Test
        @DisplayName("a submission carrying no typed action at all falls back to enter rather than "
                + "failing, which is reachable because the key mapping declares no catch-all")
        void anAbsentTypedActionFallsBackToEnter() {
            final AccountUpdateOutcome outcome = service.handle(
                    unchangedTurn().concurrencyToken(null).accountId("0").keyAction(null).build());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(MSG_ACCOUNT_NUMBER_MALFORMED),
                    () -> assertThat(outcome.nextRoute()).isEqualTo(ROUTE_ACCOUNT_UPDATE));
        }

        @Test
        @DisplayName("a blank account key claims the not-provided text, which wins the summary slot "
                + "over the no-input text raised immediately after it")
        void aBlankAccountKeyClaimsTheNotProvidedText() {
            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().concurrencyToken(null).accountId("  ").build());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(MSG_ACCOUNT_NUMBER_NOT_PROVIDED),
                    () -> assertThat(outcome.errorMessage())
                            .isNotEqualTo(MSG_NO_INPUT_RECEIVED),
                    () -> assertThat(outcome.error()).isTrue());
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    customerRepository);
        }

        @ParameterizedTest(name = "the key [{0}] is malformed")
        @ValueSource(strings = {"0", "00000000000", "abc", "1234567890", "123456789012", "1 3"})
        @DisplayName("a key that is zero, short, long or non-numeric claims the composed malformed "
                + "text and reads no record")
        void aMalformedKeyClaimsTheComposedText(final String keyed) {
            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().concurrencyToken(null).accountId(keyed).build());

            assertAll(
                    () -> assertThat(outcome.errorMessage())
                            .isEqualTo(MSG_ACCOUNT_NUMBER_MALFORMED),
                    () -> assertThat(outcome.error()).isTrue());
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    customerRepository);
        }

        @Test
        @DisplayName("a submission in which every component is absent produces the search-key prompt "
                + "and neither a null nor a number-format failure escapes")
        void aWhollyAbsentSubmissionProducesThePrompt() {
            final AccountUpdateCommand absent = new AccountUpdateCommand(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, false);

            assertThatNoException().isThrownBy(() -> service.handle(absent));

            final AccountUpdateOutcome outcome = service.handle(absent);
            assertAll(
                    () -> assertThat(outcome).isNotNull(),
                    () -> assertThat(outcome.infoMessage())
                            .isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS),
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.nextRoute()).isEqualTo(ROUTE_ACCOUNT_UPDATE));
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    customerRepository, transactionBoundary, abendService);
        }

        @Test
        @DisplayName("an absent navigation record is the zero-length communication area the legacy "
                + "tests for, and it starts a fresh conversation rather than failing")
        void anAbsentNavigationRecordStartsAFreshConversation() {
            final AccountUpdateOutcome outcome =
                    service.handle(unchangedTurn().navigationContext(null).build());

            assertAll(
                    () -> assertThat(outcome.infoMessage())
                            .isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS),
                    () -> assertThat(outcome.fieldErrors()).isEmpty(),
                    () -> assertThat(outcome.error()).isFalse());
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    customerRepository);
        }
    }

    /* ==========================================================================================
     * Member coverage and paragraph traceability.
     * ========================================================================================== */

    @Nested
    @DisplayName("member coverage and paragraph traceability: every public member exercised and all "
            + "85 paragraph units accounted for")
    class MemberCoverageAndTraceability {

        @Test
        @DisplayName("the three identification-division entries are surfaced by name as program "
                + "metadata, which is not a paragraph unit and owes no matrix row")
        void theThreeIdentificationEntriesAreSurfacedByName() {
            assertAll(
                    () -> assertThat(service.programIdParagraph()).isEqualTo(LEGACY_PROGRAM_ID),
                    () -> assertThat(service.dateWrittenParagraph()).isEqualTo("July 2022."),
                    () -> assertThat(service.dateCompiledParagraph()).isEqualTo("Today."));
        }

        @Test
        @DisplayName("the unit count this member contributes is the 85 the published matrix carries, "
                + "read from the matrix rather than restated here")
        void theUnitCountIsTheOneTheMatrixCarries() {
            // Read, not written down. The figure is taken from the matrix three independent ways - the
            // census subtotal, the member section's own declaration and the rows citing the member - and
            // the reader fails if those three disagree. An earlier revision asserted 3 + 85 == 88 over
            // constants this file authored itself, which could not fail and did not measure anything.
            assertThat(TraceabilityMatrixCensus.unitsOf(LEGACY_MEMBER))
                    .as("the frozen model is 528 program paragraphs plus 16 procedural-copybook "
                            + "paragraphs; a member that reports more than the matrix carries has "
                            + "redefined it")
                    .isEqualTo(85);
        }

        @Test
        @DisplayName("the single-argument entry point delegates to the two-argument one with no raw "
                + "identifier, so both produce the same screen for the same submission")
        void bothEntryPointsAgree() {
            when(navigationService.resolveBackNavigation(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final AccountUpdateOutcome viaOneArgument =
                    service.handle(unchangedTurn().keyAction(KeyAction.PFK03).build());
            final AccountUpdateOutcome viaTwoArguments =
                    service.handle(unchangedTurn().keyAction(KeyAction.PFK03).build(), null);

            assertAll(
                    () -> assertThat(viaTwoArguments.nextRoute())
                            .isEqualTo(viaOneArgument.nextRoute()),
                    () -> assertThat(viaTwoArguments.infoMessage())
                            .isEqualTo(viaOneArgument.infoMessage()),
                    () -> assertThat(viaTwoArguments.navigationContext())
                            .isEqualTo(viaOneArgument.navigationContext()));
        }

        @Test
        @DisplayName("every collaborator is required, so a partially wired service cannot be built")
        void everyCollaboratorIsRequired() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(null, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService,
                                    navigationService, abendService, concurrencyTokenService,
                                    fieldEncryption, transactionBoundary, clock))
                            .withMessageContaining("accountRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, null,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService,
                                    navigationService, abendService, concurrencyTokenService,
                                    fieldEncryption, transactionBoundary, clock))
                            .withMessageContaining("customerRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    null, dateValidationService, validationLookupService,
                                    messageCatalogService, navigationService, abendService,
                                    concurrencyTokenService, fieldEncryption, transactionBoundary,
                                    clock))
                            .withMessageContaining("cardCrossReferenceRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, null, validationLookupService,
                                    messageCatalogService, navigationService, abendService,
                                    concurrencyTokenService, fieldEncryption, transactionBoundary,
                                    clock))
                            .withMessageContaining("dateValidationService"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService, null,
                                    messageCatalogService, navigationService, abendService,
                                    concurrencyTokenService, fieldEncryption, transactionBoundary,
                                    clock))
                            .withMessageContaining("validationLookupService"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, null, navigationService, abendService,
                                    concurrencyTokenService, fieldEncryption, transactionBoundary,
                                    clock))
                            .withMessageContaining("messageCatalogService"));
        }

        @Test
        @DisplayName("the remaining six collaborators are required too, which completes the twelve")
        void theRemainingCollaboratorsAreRequired() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService, null,
                                    abendService, concurrencyTokenService, fieldEncryption,
                                    transactionBoundary, clock))
                            .withMessageContaining("navigationService"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService,
                                    navigationService, null, concurrencyTokenService,
                                    fieldEncryption, transactionBoundary, clock))
                            .withMessageContaining("abendService"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService,
                                    navigationService, abendService, null, fieldEncryption,
                                    transactionBoundary, clock))
                            .withMessageContaining("concurrencyTokenService"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService,
                                    navigationService, abendService, concurrencyTokenService, null,
                                    transactionBoundary, clock))
                            .withMessageContaining("fieldEncryption"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService,
                                    navigationService, abendService, concurrencyTokenService,
                                    fieldEncryption, null, clock))
                            .withMessageContaining("transactionBoundary"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new AccountUpdateService(accountRepository, customerRepository,
                                    cardCrossReferenceRepository, dateValidationService,
                                    validationLookupService, messageCatalogService,
                                    navigationService, abendService, concurrencyTokenService,
                                    fieldEncryption, transactionBoundary, null))
                            .withMessageContaining("clock"));
        }

        @Test
        @DisplayName("the field-flag triad carries the macro's outer and inner conditions, so the "
                + "colour and the marker are decided by the flag and nothing else")
        void theFieldFlagTriadCarriesTheMacrosConditions() {
            assertAll(
                    () -> assertThat(AccountUpdateService.FieldFlag.values()).hasSize(3),
                    () -> assertThat(AccountUpdateService.FieldFlag.ISVALID.isValid()).isTrue(),
                    () -> assertThat(AccountUpdateService.FieldFlag.ISVALID.requiresDecoration())
                            .isFalse(),
                    () -> assertThat(AccountUpdateService.FieldFlag.ISVALID.writesMissingMarker())
                            .isFalse(),
                    () -> assertThat(AccountUpdateService.FieldFlag.NOT_OK.isNotOk()).isTrue(),
                    () -> assertThat(AccountUpdateService.FieldFlag.NOT_OK.requiresDecoration())
                            .isTrue(),
                    () -> assertThat(AccountUpdateService.FieldFlag.NOT_OK.writesMissingMarker())
                            .isFalse(),
                    () -> assertThat(AccountUpdateService.FieldFlag.BLANK.isBlank()).isTrue(),
                    () -> assertThat(AccountUpdateService.FieldFlag.BLANK.requiresDecoration())
                            .isTrue(),
                    () -> assertThat(AccountUpdateService.FieldFlag.BLANK.writesMissingMarker())
                            .isTrue());
        }

        @ParameterizedTest(name = "{0} holds the byte {1}, changesMade={2}, changesFailed={3}")
        @CsvSource({"DETAILS_NOT_FETCHED, ' ', false, false", "SHOW_DETAILS, S, false, false",
                    "CHANGES_NOT_OK, E, true, false", "CHANGES_OK_NOT_CONFIRMED, N, true, false",
                    "CHANGES_OKAYED_AND_DONE, C, true, false",
                    "CHANGES_OKAYED_LOCK_ERROR, L, true, true",
                    "CHANGES_OKAYED_BUT_FAILED, F, true, true"})
        @DisplayName("the change-action group carries its own byte and its two grouping conditions, in "
                + "the declaration order the dispatch selection depends on")
        void theChangeActionGroupCarriesItsByteAndGroupings(final String name, final char code,
                final boolean changesMade, final boolean changesFailed) {
            final AccountUpdateService.ChangeAction action =
                    AccountUpdateService.ChangeAction.valueOf(name);

            assertAll(
                    () -> assertThat(action.getCode()).isEqualTo(code),
                    () -> assertThat(action.changesMade()).isEqualTo(changesMade),
                    () -> assertThat(action.changesFailed()).isEqualTo(changesFailed));
        }

        @Test
        @DisplayName("the change-action group declares exactly the seven states the source does, in "
                + "source order, because the dispatch selection stops at its first match")
        void theChangeActionGroupDeclaresSevenStatesInSourceOrder() {
            assertAll(
                    () -> assertThat(AccountUpdateService.ChangeAction.values()).hasSize(7),
                    () -> assertThat(AccountUpdateService.ChangeAction.values()).containsExactly(
                            AccountUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                            AccountUpdateService.ChangeAction.SHOW_DETAILS,
                            AccountUpdateService.ChangeAction.CHANGES_NOT_OK,
                            AccountUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE,
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED));
        }

        @Test
        @DisplayName("this suite's provenance matches the checkout and release stamp the traceability "
                + "matrix cites, so a reader can find the source these expectations came from")
        void theProvenanceMatchesTheTraceabilityMatrix() {
            assertAll(
                    () -> assertThat(TestDataFactory.VERIFIED_CHECKOUT_COMMIT)
                            .isEqualTo("7756d895ffeb65f7ea72aaa609e356d9899afcec"),
                    () -> assertThat(TestDataFactory.UPSTREAM_RELEASE_STAMP)
                            .isEqualTo("CardDemo_v1.0-15-g27d6c6f-68"));
        }

        @Test
        @DisplayName("the seeded account group identifier this suite asserts against is the one the "
                + "shared fixture declares, so the ten-space width is not a local invention")
        void theSeededGroupIdentifierAgreesWithTheSharedFixture() {
            assertThat(ACCOUNT_GROUP_ID_TEN_SPACES)
                    .isEqualTo(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID);
        }
    }
}
