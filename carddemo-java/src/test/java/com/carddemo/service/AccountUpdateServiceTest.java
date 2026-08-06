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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

import com.carddemo.api.AccountProtectedDataAdapter;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the account-update transaction {@code CAUP}, migrated from
 * {@code app/cbl/COACTUPC.cbl} - 4,236 lines, 85 procedure-division paragraphs and three
 * identification-division paragraphs, and the sole includer of the four copybooks that carry the
 * estate's whole validation-lookup surface.
 *
 * <p>Every expected message, field identifier and ordering below is written as a literal in this class,
 * so the oracle is independent of the code it judges. No expected value is obtained by calling the
 * service, the lookup tables, the message catalogue or the navigation service.
 *
 * <p>The tests that matter most are the ones guarding the six counter-intuitive behaviours: the two
 * fields that are decorated and never validated, the telephone cascade that forwards rather than
 * short-circuits, the gated credit-score range, the two postal lists that must never be intersected, the
 * asymmetric rollback, and the ordered write-outcome selection.
 */
@DisplayName("AccountUpdateService: the account-update transaction CAUP")
class AccountUpdateServiceTest {

    /** A test key of the exact length the field-encryption service requires. */
    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-acctupd-test-key-012345".getBytes(StandardCharsets.UTF_8));

    private static final String ACCOUNT_ID = "00000000011";
    private static final String CUSTOMER_ID = "000000001";
    private static final String CARD_NUMBER = "4111111111111111";

    /* The verbatim texts this transaction owns. Declared here, never imported from production code. */
    private static final String MSG_ACCOUNT_NUMBER_NOT_PROVIDED = "Account number not provided";
    private static final String MSG_ACCOUNT_NUMBER_MALFORMED =
            "Account Number if supplied must be a 11 digit Non-Zero Number";
    private static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";
    private static final String MSG_RECORD_CHANGED = "Record changed by some one else. Please review";
    private static final String MSG_UPDATE_FAILED = "Update of record failed";
    private static final String SUFFIX_AREA_CODE_REQUIRED = ": Area code must be supplied.";
    private static final String SUFFIX_AREA_CODE_NOT_3_DIGITS =
            ": Area code must be A 3 digit number.";
    private static final String SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE =
            ": Not valid North America general purpose area code";
    private static final String SUFFIX_PREFIX_REQUIRED = ": Prefix code must be supplied.";
    private static final String SUFFIX_LINE_NUMBER_REQUIRED =
            ": Line number code must be supplied.";
    private static final String SUFFIX_FICO_OUT_OF_RANGE = ": should be between 300 and 850";
    private static final String SUFFIX_STATE_NOT_VALID = ": is not a valid state code";
    private static final String INFO_PROMPT_FOR_SEARCH_KEYS =
            "Enter or update id of account to update";
    private static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";
    private static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";
    private static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

    /** The 39 BMS field identifiers, in the order the 39 macro expansions declare them. */
    private static final List<String> DECORATION_ORDER = List.of(
            "ACSTTUS", "OPNYEAR", "OPNMON", "OPNDAY", "ACRDLIM", "EXPYEAR", "EXPMON", "EXPDAY",
            "ACSHLIM", "RISYEAR", "RISMON", "RISDAY", "ACURBAL", "ACRCYCR", "ACRCYDB", "ACTSSN1",
            "ACTSSN2", "ACTSSN3", "DOBYEAR", "DOBMON", "DOBDAY", "ACSTFCO", "ACSFNAM", "ACSMNAM",
            "ACSLNAM", "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY", "ACSPH1A",
            "ACSPH1B", "ACSPH1C", "ACSPH2A", "ACSPH2B", "ACSPH2C", "ACSPFLG", "ACSEFTC");

    private AccountRepository accountRepository;
    private CustomerRepository customerRepository;
    private CardCrossReferenceRepository crossReferenceRepository;
    private ValidationLookupService lookupService;
    private AccountConcurrencyTokenService tokenService;
    private AccountUpdateService service;

    @BeforeEach
    void setUp() {
        this.accountRepository = Mockito.mock(AccountRepository.class);
        this.customerRepository = Mockito.mock(CustomerRepository.class);
        this.crossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        this.lookupService =
                new ValidationLookupService(new ObjectMapper(), new DefaultResourceLoader());
        final SensitiveFieldEncryptionService encryption =
                new SensitiveFieldEncryptionService(BASE64_KEY);
        this.tokenService = new AccountConcurrencyTokenService(encryption);
        // A real abend service rather than a mock, and deliberately so: its online arm throws, so any
        // turn that wrongly routed to it would surface as an AbendException instead of the outcome
        // asserted. That is a stronger guarantee than verifying no interaction with a stub.
        this.service = new AccountUpdateService(this.accountRepository, this.customerRepository,
                this.crossReferenceRepository, new DateValidationService(), this.lookupService,
                new MessageCatalogService(), new NavigationService(), new AbendService(),
                this.tokenService, encryption, new OnlineTransactionBoundary(),
                Clock.fixed(Instant.parse("2024-05-06T07:08:09Z"), ZoneOffset.UTC));
    }

    /* ---------------------------------------------------------------------------------------- */

    private Account seededAccount() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal("1000.00"), new BigDecimal("5000.00"),
                new BigDecimal("2000.00"), "2020-01-15", "2029-01-15", "2024-01-15",
                new BigDecimal("100.00"), new BigDecimal("50.00"), "12345", "          ");
    }

    private Customer seededCustomer() {
        // The eighteen persisted attributes in record order. The national and government identifiers are
        // null, exactly as they are in all fifty seeded rows, and absence must be tolerated.
        return new Customer(CUSTOMER_ID, "Aniya Von", "Q", "Smith", "1 High Street", "Flat 2",
                "Springfield", "NY", "USA", "10001", "(201)555-0100", "(202)555-0101", null, null,
                "1980-02-03", "1234567890", "Y", "700");
    }

    private void seedRecords() {
        Mockito.when(this.crossReferenceRepository
                        .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID,
                        ACCOUNT_ID)));
        Mockito.when(this.accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(seededAccount()));
        Mockito.when(this.customerRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(seededCustomer()));
    }

    private static ScreenNavigationState reEntered() {
        return ScreenNavigationState.empty().withReEntry();
    }

    /** A detail turn that mirrors the seeded records exactly, so nothing reads as changed. */
    private AccountUpdateCommand unchangedDetailTurn(final String token, final KeyAction key) {
        return new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020", "01", "15", "5000.00", "2029",
                "01", "15", "2000.00", "2024", "01", "15", "1000.00", "100.00", "          ",
                "50.00", CUSTOMER_ID, null, null, null, "1980", "02", "03", "700", "Aniya Von",
                "Q", "Smith", "1 High Street", "NY", "Flat 2", "10001", "Springfield", "USA",
                "201", "555", "0100", null, "202", "555", "0101", "1234567890", "Y", key,
                reEntered(), token, false);
    }

    /** The same turn with one detail changed, so the edits run. */
    private AccountUpdateCommand changedDetailTurn(final String token, final KeyAction key) {
        return new AccountUpdateCommand(ACCOUNT_ID, "N", "2020", "01", "15", "5000.00", "2029",
                "01", "15", "2000.00", "2024", "01", "15", "1000.00", "100.00", "          ",
                "50.00", CUSTOMER_ID, "123", "45", "6789", "1980", "02", "03", "700",
                "Aniya Von", "Q", "Smith", "1 High Street", "NY", "Flat 2", "10001",
                "Springfield", "USA", "201", "555", "0100", null, "202", "555", "0101",
                "1234567890", "Y", key, reEntered(), token, false);
    }

    private String mintedToken() {
        return this.tokenService.mint(seededAccount(), seededCustomer());
    }

    private static List<String> screenFieldIdsOf(final AccountUpdateOutcome response) {
        return response.fieldErrors().stream().map(ValidationException.FieldError::bmsFieldId).toList();
    }

    /** The national identifier the withholding fixtures store, sealed on the way into the row. */
    private static final String STORED_SSN = "123456789";

    /** The government-issued identifier the withholding fixtures store, sealed likewise. */
    private static final String STORED_GOVERNMENT_ID = "G1234567";

    /**
     * A customer row carrying both regulated identifiers, so a withheld stand-in has something to restore.
     *
     * <p>The seeded fifty rows hold neither, which is why the ordinary fixture leaves both null; this one
     * exists specifically to exercise the restoration, and both columns accept only envelopes.
     */
    private Customer customerWithRegulatedIdentifiers() {
        final SensitiveFieldEncryptionService encryption =
                new SensitiveFieldEncryptionService(BASE64_KEY);
        return new Customer(CUSTOMER_ID, "Aniya Von", "Q", "Smith", "1 High Street", "Flat 2",
                "Springfield", "NY", "USA", "10001", "(201)555-0100", "(202)555-0101",
                encryption.protect(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, STORED_SSN),
                encryption.protect(SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                        STORED_GOVERNMENT_ID),
                "1980-02-03", "1234567890", "Y", "700");
    }

    /** Seeds the three reads with a customer that carries both regulated identifiers. */
    private void seedRecordsWithRegulatedIdentifiers() {
        final Customer customer = customerWithRegulatedIdentifiers();
        Mockito.when(this.crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
        Mockito.when(this.accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(seededAccount()));
        Mockito.when(this.customerRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(customer));
    }

    /**
     * A turn as a caller without the authority to see the regulated values echoes it back.
     *
     * <p>Every regulated component carries the stand-in the outbound gate composes - the withheld character
     * repeated to the stored value's own width - except the final part of the national identifier, which
     * the gate does not withhold. One unrelated value differs from the record, so the edits actually run.
     *
     * @param token the minted before-image proof
     * @param withheld whether the caller is to be treated as one the values were withheld from
     * @return the echoed turn
     */
    private AccountUpdateCommand withheldDetailTurn(final String token, final boolean withheld) {
        return new AccountUpdateCommand(ACCOUNT_ID, "N", "2020", "01", "15", "5000.00", "2029",
                "01", "15", "2000.00", "2024", "01", "15", "1000.00", "100.00", "          ",
                "50.00", CUSTOMER_ID, "***", "**", "6789", "****", "**", "**", "700",
                "Aniya Von", "Q", "Smith", "1 High Street", "NY", "Flat 2", "10001",
                "Springfield", "USA", "201", "555", "0100", "********", "202", "555", "0101",
                "**********", "Y", KeyAction.ENTER, reEntered(), token, withheld);
    }

    /** The five screen fields whose edits a withheld stand-in would otherwise fail. */
    private static final List<String> REGULATED_SCREEN_FIELDS =
            List.of("ACTSSN1", "ACTSSN2", "DOBYEAR", "DOBMON", "DOBDAY", "ACSEFTC");

    /* ---------------------------------------------------------------------------------------- */

    @Nested
    @DisplayName("the regulated values a caller was not permitted to see")
    class WithheldRegulatedValues {

        @Test
        @DisplayName("an ordinary operator can save an unrelated change, because the withheld stand-ins "
                + "restore to the stored values instead of being edited as input")
        void anOrdinaryOperatorCanSaveAnUnrelatedChange() {
            // Without the restoration the mandatory edits at COACTUPC lines 1520 to 1556 reject the
            // stand-ins and the turn never validates, which costs every ordinary operator the whole
            // transaction - not just the fields they could not see.
            seedRecordsWithRegulatedIdentifiers();
            final Customer stored = customerWithRegulatedIdentifiers();
            final String token = tokenService.mint(seededAccount(), stored);

            final AccountUpdateOutcome response = service.handle(withheldDetailTurn(token, true));

            assertThat(screenFieldIdsOf(response))
                    .as("no regulated field may report, because none of them was supplied as input")
                    .doesNotContainAnyElementsOf(REGULATED_SCREEN_FIELDS);
            assertThat(response.error()).isFalse();
            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CONFIRMATION);
        }

        @Test
        @DisplayName("the same stand-ins are edited as ordinary input when nothing was withheld, so an "
                + "authorized operator's literal entry is never silently reinterpreted")
        void theSameStandInsAreEditedWhenNothingWasWithheld() {
            seedRecordsWithRegulatedIdentifiers();
            final String token = tokenService.mint(seededAccount(), customerWithRegulatedIdentifiers());

            final AccountUpdateOutcome response = service.handle(withheldDetailTurn(token, false));

            assertThat(screenFieldIdsOf(response))
                    .as("the stand-in is not a digit, so every regulated edit that requires digits fails")
                    .containsAnyElementsOf(REGULATED_SCREEN_FIELDS);
            assertThat(response.error()).isTrue();
        }

        @Test
        @DisplayName("a stand-in of the wrong width is edited as input, because the gate composes it at "
                + "the stored value's own width and nothing else is its stand-in")
        void aStandInOfTheWrongWidthIsEditedAsInput() {
            seedRecordsWithRegulatedIdentifiers();
            final String token = tokenService.mint(seededAccount(), customerWithRegulatedIdentifiers());
            final AccountUpdateCommand tooShort = new AccountUpdateCommand(ACCOUNT_ID, "N", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "***", "**", "6789",
                    "**", "**", "**", "700", "Aniya Von", "Q", "Smith", "1 High Street", "NY",
                    "Flat 2", "10001", "Springfield", "USA", "201", "555", "0100", "********",
                    "202", "555", "0101", "**********", "Y", KeyAction.ENTER, reEntered(), token,
                    true);

            final AccountUpdateOutcome response = service.handle(tooShort);

            assertThat(screenFieldIdsOf(response))
                    .as("a two-character stand-in cannot have come from a four-character stored year")
                    .contains("DOBYEAR");
        }

        @Test
        @DisplayName("the character this transaction recognises coming back is the character the boundary "
                + "gate composes going out, which is the agreement neither layer can import")
        void theWithheldCharacterAgreesWithTheBoundaryGate() {
            // The gate composes the stand-in and this transaction recognises it, and the constant cannot be
            // shared: nothing in the service layer may depend upward on the boundary. A suite is the only
            // place both are visible, so this is where the agreement is pinned - a change to either side
            // alone fails here rather than silently disabling the restoration.
            assertThat(String.valueOf(AccountUpdateService.WITHHELD_VALUE_CHARACTER))
                    .isEqualTo(AccountProtectedDataAdapter.MASK_CHARACTER);
        }

        @Test
        @DisplayName("a regulated value the record does not hold leaves the submitted stand-in alone, "
                + "because there was nothing for a stand-in to be composed from")
        void anAbsentStoredValueLeavesTheStandInAlone() {
            // The fifty seeded rows hold neither identifier, and this is the shape they take.
            seedRecords();
            final AccountUpdateOutcome response =
                    service.handle(withheldDetailTurn(mintedToken(), true));

            assertThat(screenFieldIdsOf(response))
                    .as("nothing is stored for the national identifier, so the stand-in is edited")
                    .contains("ACTSSN1");
        }
    }

    /* ---------------------------------------------------------------------------------------- */

    @Nested
    @DisplayName("the search-key turn")
    class SearchKeyTurn {

        @Test
        @DisplayName("a fresh entry prompts for the key and decorates nothing")
        void freshEntryPrompts() {
            final AccountUpdateCommand request = new AccountUpdateCommand(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    KeyAction.ENTER, null, null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_SEARCH_KEYS);
            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.error()).isFalse();
            assertThat(response.nextRoute()).isEqualTo("account-update");
            assertThat(response.transactionName()).isEqualTo("CAUP");
            assertThat(response.programName()).isEqualTo("COACTUPC");
            assertThat(response.currentDate()).isEqualTo("05/06/24");
            assertThat(response.currentTime()).isEqualTo("07:08:09");
            assertThat(response.navigationContext().lastMap()).isEqualTo("CACTUPA");
            assertThat(response.navigationContext().lastMapset()).isEqualTo("COACTUP");
        }

        @Test
        @DisplayName("a blank key claims the not-provided text, which wins over the no-input text")
        void blankKeyClaimsNotProvided() {
            final AccountUpdateCommand request = new AccountUpdateCommand("   ", null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.errorMessage()).isEqualTo(MSG_ACCOUNT_NUMBER_NOT_PROVIDED);
            assertThat(response.error()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"1234", "0000000000A", "00000000000"})
        @DisplayName("a key that is short, non-numeric or zero claims the composed malformed text")
        void malformedKey(final String keyed) {
            final AccountUpdateCommand request = new AccountUpdateCommand(keyed, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.errorMessage()).isEqualTo(MSG_ACCOUNT_NUMBER_MALFORMED);
            assertThat(response.error()).isTrue();
        }

        @Test
        @DisplayName("a valid key resolves the cross-reference, both records, and shows the detail")
        void validKeyShowsDetails() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.accountStatus()).isEqualTo("Y");
            assertThat(response.openYear()).isEqualTo("2020");
            assertThat(response.openMonth()).isEqualTo("01");
            assertThat(response.openDay()).isEqualTo("15");
            assertThat(response.creditLimit()).isEqualByComparingTo("5000.00");
            assertThat(response.phone1AreaCode()).isEqualTo("201");
            assertThat(response.phone1Prefix()).isEqualTo("555");
            assertThat(response.phone1LineNumber()).isEqualTo("0100");
            assertThat(response.dateOfBirthYear()).isEqualTo("1980");
            // The group identifier is ten spaces in every seeded row and is never trimmed.
            assertThat(response.accountGroupId()).isEqualTo("          ");
            // The national identifier is null in every seeded row and absence is tolerated.
            assertThat(response.ssnPart1()).isNull();
            assertThat(response.navigationContext().accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.navigationContext().customerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("an unresolvable cross-reference claims the declared not-found text")
        void crossReferenceMissing() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.errorMessage())
                    .isEqualTo("Did not find this account in account card xref file");
            assertThat(response.error()).isTrue();
            Mockito.verify(accountRepository, Mockito.never())
                    .findById(ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("the 39 decoration sites")
    class Decoration {

        @Test
        @DisplayName("report in the declared order, with the state code between the two address lines")
        void orderIsPreserved() {
            seedRecords();
            // Every editable field left blank, so every edited field reports.
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, CUSTOMER_ID, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, KeyAction.ENTER, reEntered(), mintedToken(), false);

            final List<String> reported = screenFieldIdsOf(service.handle(request));

            assertThat(reported).isSubsetOf(DECORATION_ORDER);
            assertThat(reported).isSortedAccordingTo(
                    (left, right) -> Integer.compare(DECORATION_ORDER.indexOf(left),
                            DECORATION_ORDER.indexOf(right)));
            assertThat(DECORATION_ORDER.indexOf("ACSSTTE"))
                    .isGreaterThan(DECORATION_ORDER.indexOf("ACSADL1"))
                    .isLessThan(DECORATION_ORDER.indexOf("ACSADL2"));
            assertThat(DECORATION_ORDER.indexOf("ACSZIPC"))
                    .isLessThan(DECORATION_ORDER.indexOf("ACSCITY"))
                    .isLessThan(DECORATION_ORDER.indexOf("ACSCTRY"));
        }

        @Test
        @DisplayName("distinguish a missing field from an invalid one")
        void missingAndInvalidAreDistinct() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, null, "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "999", "Aniya Von", "Q", "Smith", "1 High Street", "NY",
                    "Flat 2", "10001", "Springfield", "USA", "201", "555", "0100", null, "202",
                    "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.fieldErrors())
                    .anySatisfy(error -> {
                        assertThat(error.bmsFieldId()).isEqualTo("ACSTTUS");
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.MISSING);
                    })
                    .anySatisfy(error -> {
                        assertThat(error.bmsFieldId()).isEqualTo("ACSTFCO");
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.INVALID);
                    });
        }

        @Test
        @DisplayName("are suppressed on a first entry, because the macro fires only on re-entry")
        void firstEntryIsNotDecorated() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, CUSTOMER_ID, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, KeyAction.ENTER,
                    ScreenNavigationState.empty().withFirstEntry().reconciledWith("USER0001", null),
                    mintedToken(), false);

            assertThat(service.handle(request).fieldErrors()).isEmpty();
        }

        /**
         * Pins the directed behaviour, and records that the two fields are not alike in the source.
         *
         * <p>Address line 2 genuinely has no edit anywhere. The middle name does: line 1571 performs the
         * optional alphabetic edit on it and the call is live, so the legacy would reject the value keyed
         * below. The screen-attribute comment at line 3345 says otherwise, the migration directive follows
         * the comment, and this assertion holds the directive. Should the directive ever be revisited, this
         * is the test that must change with it.
         */
        @Test
        @DisplayName("never fire for the middle name or address line 2, whatever is keyed")
        void twoFieldsAreNeverValidated() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", "Aniya Von", "%%% 12 !!", "Smith", "1 High Street",
                    "NY", "$$$ 99 ???", "10001", "Springfield", "USA", "201", "555", "0100", null,
                    "202", "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(),
                    mintedToken(), false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(screenFieldIdsOf(response)).doesNotContain("ACSMNAM", "ACSADL2");
            assertThat(response.middleName()).isEqualTo("%%% 12 !!");
            assertThat(response.addressLine2()).isEqualTo("$$$ 99 ???");
        }
    }

    @Nested
    @DisplayName("the telephone cascade")
    class TelephoneCascade {

        private AccountUpdateOutcome withPhoneOne(final String area, final String prefix,
                final String line) {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", "Aniya Von", "Q", "Smith", "1 High Street", "NY",
                    "Flat 2", "10001", "Springfield", "USA", area, prefix, line, null, "202",
                    "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);
            return service.handle(request);
        }

        @Test
        @DisplayName("runs all three stages, so three bad parts yield three errors and one message")
        void forwardsRatherThanShortCircuits() {
            // Every part supplied and every part malformed. All three must be keyed, because a wholly
            // blank telephone is not an error at all - the source says so at line 2233, "Not mandatory
            // to enter a phone number".
            final AccountUpdateOutcome response = withPhoneOne("12", "1", "1");

            assertThat(screenFieldIdsOf(response)).contains("ACSPH1A", "ACSPH1B", "ACSPH1C");
            assertThat(response.fieldErrors())
                    .filteredOn(error -> error.bmsFieldId().startsWith("ACSPH1"))
                    .allSatisfy(error -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
            // One summary message, and it is the first failure's - the area code's.
            assertThat(response.errorMessage())
                    .isEqualTo("Phone Number 1" + SUFFIX_AREA_CODE_NOT_3_DIGITS);
        }

        @Test
        @DisplayName("treats a wholly unkeyed telephone as acceptable, because it is not mandatory")
        void anUnkeyedTelephoneIsAccepted() {
            final AccountUpdateOutcome response = withPhoneOne("   ", "   ", "    ");

            assertThat(screenFieldIdsOf(response))
                    .doesNotContain("ACSPH1A", "ACSPH1B", "ACSPH1C");
        }

        @Test
        @DisplayName("keeps the first failure's message when a later stage also fails")
        void firstErrorWinsTheSummarySlot() {
            final AccountUpdateOutcome response = withPhoneOne("201", "  ", "  ");

            assertThat(screenFieldIdsOf(response)).contains("ACSPH1B", "ACSPH1C")
                    .doesNotContain("ACSPH1A");
            assertThat(response.errorMessage())
                    .isEqualTo("Phone Number 1" + SUFFIX_PREFIX_REQUIRED);
        }

        @Test
        @DisplayName("reaches the line-number stage even when the two before it failed")
        void lineNumberStageAlwaysRuns() {
            final AccountUpdateOutcome response = withPhoneOne("00", "00", "  ");

            assertThat(screenFieldIdsOf(response)).contains("ACSPH1A", "ACSPH1B", "ACSPH1C");
            assertThat(response.errorMessage())
                    .isEqualTo("Phone Number 1" + SUFFIX_AREA_CODE_NOT_3_DIGITS);
            assertThat(response.fieldErrors())
                    .anySatisfy(error -> {
                        assertThat(error.bmsFieldId()).isEqualTo("ACSPH1C");
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.MISSING);
                    });
        }

        @Test
        @DisplayName("rejects an easily-recognisable code and accepts a general-purpose one")
        void membershipUsesTheGeneralPurposeSetAlone() {
            assertThat(screenFieldIdsOf(withPhoneOne("800", "555", "0100")))
                    .contains("ACSPH1A");
            assertThat(withPhoneOne("800", "555", "0100").errorMessage())
                    .isEqualTo("Phone Number 1" + SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE);
            assertThat(screenFieldIdsOf(withPhoneOne("201", "555", "0100")))
                    .doesNotContain("ACSPH1A", "ACSPH1B", "ACSPH1C");
        }

        @Test
        @DisplayName("the 490 figure is the derived union, so it is never the membership set")
        void theUnionIsDerivedNotStored() {
            assertThat(lookupService.generalPurposeAreaCodes()).hasSize(410)
                    .doesNotContain("800");
            assertThat(lookupService.easilyRecognisableAreaCodes()).hasSize(80).contains("800");
            assertThat(lookupService.phoneAreaCodes()).hasSize(490);
        }

        @Test
        @DisplayName("the reproduced defect accepts a keyed line number when both parts before are blank")
        void allBlankShortcutTestsTheWrongSubField() {
            final AccountUpdateOutcome response = withPhoneOne("   ", "   ", "0100");

            assertThat(screenFieldIdsOf(response))
                    .doesNotContain("ACSPH1A", "ACSPH1B", "ACSPH1C");
            assertThat(response.errorMessage()).isNotEqualTo(
                    "Phone Number 1" + SUFFIX_LINE_NUMBER_REQUIRED);
        }
    }

    @Nested
    @DisplayName("the credit-score range")
    class CreditScoreRange {

        private AccountUpdateOutcome withScore(final String score) {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", score, "Aniya Von", "Q", "Smith", "1 High Street", "NY",
                    "Flat 2", "10001", "Springfield", "USA", "201", "555", "0100", null, "202",
                    "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);
            return service.handle(request);
        }

        @ParameterizedTest
        @ValueSource(strings = {"300", "850", "301", "849"})
        @DisplayName("accepts both inclusive bounds")
        void inclusiveBoundsAccepted(final String score) {
            assertThat(screenFieldIdsOf(withScore(score))).doesNotContain("ACSTFCO");
        }

        @ParameterizedTest
        @ValueSource(strings = {"299", "851", "001"})
        @DisplayName("rejects a score outside the bounds with the exact text and no full stop")
        void outsideBoundsRejected(final String score) {
            final AccountUpdateOutcome response = withScore(score);
            assertThat(screenFieldIdsOf(response)).contains("ACSTFCO");
            assertThat(response.errorMessage())
                    .isEqualTo("FICO Score" + SUFFIX_FICO_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("does not re-edit a score that already failed the numeric edit")
        void alreadyInvalidScoreIsNotReEdited() {
            final AccountUpdateOutcome response = withScore("abc");

            assertThat(screenFieldIdsOf(response)).contains("ACSTFCO");
            assertThat(response.errorMessage()).isEqualTo("FICO Score must be all numeric.");
        }
    }

    @Nested
    @DisplayName("the state code and the state-plus-postcode composite")
    class PostalValidation {

        @Test
        @DisplayName("the two lists are never intersected, so six prefixes exist only in the composite")
        void listsAreNeverIntersected() {
            assertThat(lookupService.usStateCodes()).hasSize(56)
                    .doesNotContain("AA", "AE", "AP", "FM", "MH", "PW");
            assertThat(lookupService.usStateZipCodeCombinations()).hasSize(240).contains("AA34");
        }

        @Test
        @DisplayName("an unknown state code is rejected with the exact text and no full stop")
        void unknownStateRejected() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", "Aniya Von", "Q", "Smith", "1 High Street", "AA",
                    "Flat 2", "34001", "Springfield", "USA", "201", "555", "0100", null, "202",
                    "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(screenFieldIdsOf(response)).contains("ACSSTTE");
            assertThat(response.errorMessage()).isEqualTo("State" + SUFFIX_STATE_NOT_VALID);
        }

        @Test
        @DisplayName("a bad composite sets both flags and emits the unprefixed message")
        void badCompositeSetsBothFlags() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", "Aniya Von", "Q", "Smith", "1 High Street", "NY",
                    "Flat 2", "99999", "Springfield", "USA", "201", "555", "0100", null, "202",
                    "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(screenFieldIdsOf(response)).contains("ACSSTTE", "ACSZIPC");
            assertThat(response.errorMessage()).isEqualTo(MSG_INVALID_ZIP_FOR_STATE);
        }
    }

    @Nested
    @DisplayName("the character-class edits")
    class CharacterClassEdits {

        @ParameterizedTest
        @ValueSource(strings = {"MARY ANN", "Aniya Von", "Smith"})
        @DisplayName("accept an embedded space, because the legacy idiom blanks and trims")
        void embeddedSpacesPass(final String name) {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", name, "Q", "Smith", "1 High Street", "NY", "Flat 2",
                    "10001", "Springfield", "USA", "201", "555", "0100", null, "202", "555",
                    "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);

            assertThat(screenFieldIdsOf(service.handle(request))).doesNotContain("ACSFNAM");
        }

        @Test
        @DisplayName("reject a digit in a required alphabetic field")
        void digitsRejected() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", "Mary2", "Q", "Smith", "1 High Street", "NY",
                    "Flat 2", "10001", "Springfield", "USA", "201", "555", "0100", null, "202",
                    "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(screenFieldIdsOf(response)).contains("ACSFNAM");
            assertThat(response.errorMessage())
                    .isEqualTo("First Name can have alphabets only.");
        }
    }

    @Nested
    @DisplayName("the write range")
    class WriteRange {

        @Test
        @DisplayName("commits both records and confirms when nothing conflicts")
        void happyPath() {
            seedRecords();
            Mockito.when(accountRepository.saveAndFlush(ArgumentMatchers.any(Account.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            Mockito.when(customerRepository.compareAndSet(
                    ArgumentMatchers.any(Customer.class), ArgumentMatchers.any(Customer.class)))
                    .thenReturn(1);

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn(mintedToken(), KeyAction.PFK05));

            assertThat(response.infoMessage()).isEqualTo(INFO_CONFIRM_UPDATE_SUCCESS);
            Mockito.verify(accountRepository).saveAndFlush(ArgumentMatchers.any(Account.class));
            final ArgumentCaptor<Customer> before = ArgumentCaptor.forClass(Customer.class);
            final ArgumentCaptor<Customer> after = ArgumentCaptor.forClass(Customer.class);
            Mockito.verify(customerRepository).compareAndSet(before.capture(), after.capture());
            assertThat(before.getValue().getCustSsn())
                    .as("the held row remains the unchanged compare image")
                    .isNull();
            assertThat(after.getValue().getCustSsn())
                    .as("the replacement carries the operator's newly protected value")
                    .isNotNull();
            assertThat(after.getValue().getCustId()).isEqualTo(before.getValue().getCustId());
        }

        @Test
        @DisplayName("the account arm reports the failure after the inner unit has rolled back")
        void accountArmReturnsTheLegacyFailure() {
            seedRecords();
            Mockito.when(accountRepository.saveAndFlush(ArgumentMatchers.any(Account.class)))
                    .thenThrow(new OptimisticLockingFailureException("account moved"));

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn(mintedToken(), KeyAction.PFK05));

            assertThat(response.errorMessage()).isEqualTo(MSG_UPDATE_FAILED);
            assertThat(response.error()).isTrue();
            Mockito.verify(customerRepository, Mockito.never())
                    .compareAndSet(ArgumentMatchers.any(Customer.class),
                            ArgumentMatchers.any(Customer.class));
        }

        @Test
        @DisplayName("the customer arm returns the same legacy failure after rolling back the account")
        void customerArmReturnsTheLegacyFailure() {
            seedRecords();
            Mockito.when(accountRepository.saveAndFlush(ArgumentMatchers.any(Account.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            Mockito.when(customerRepository.compareAndSet(
                            ArgumentMatchers.any(Customer.class),
                            ArgumentMatchers.any(Customer.class)))
                    .thenThrow(new OptimisticLockingFailureException("customer moved"));

            final AccountUpdateCommand confirming = changedDetailTurn(mintedToken(),
                    KeyAction.PFK05);

            final AccountUpdateOutcome response = service.handle(confirming);

            assertThat(response.errorMessage()).isEqualTo(MSG_UPDATE_FAILED);
            assertThat(response.error()).isTrue();
            Mockito.verify(accountRepository).saveAndFlush(ArgumentMatchers.any(Account.class));
            Mockito.verify(customerRepository).compareAndSet(
                    ArgumentMatchers.any(Customer.class), ArgumentMatchers.any(Customer.class));
        }

        @Test
        @DisplayName("a customer-only change after token verification rolls back the account rewrite")
        void customerCompareAndSetMissUsesTheChangedRecordArm() {
            seedRecords();
            Mockito.when(accountRepository.saveAndFlush(ArgumentMatchers.any(Account.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            Mockito.when(customerRepository.compareAndSet(
                    ArgumentMatchers.any(Customer.class), ArgumentMatchers.any(Customer.class)))
                    .thenReturn(0);

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn(mintedToken(), KeyAction.PFK05));

            assertThat(response.errorMessage()).isEqualTo(MSG_RECORD_CHANGED);
            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
            assertThat(response.error()).isTrue();
            Mockito.verify(accountRepository).saveAndFlush(ArgumentMatchers.any(Account.class));
            Mockito.verify(customerRepository).compareAndSet(
                    ArgumentMatchers.any(Customer.class), ArgumentMatchers.any(Customer.class));
            Mockito.verify(customerRepository, Mockito.never())
                    .saveAndFlush(ArgumentMatchers.any(Customer.class));
        }

        @Test
        @DisplayName("a record that moved before the update shows the detail again")
        void changeBeforeUpdateShowsDetails() {
            seedRecords();

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn("not-a-valid-token", KeyAction.PFK05));

            assertThat(response.errorMessage()).isEqualTo(MSG_RECORD_CHANGED);
            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
            Mockito.verify(accountRepository, Mockito.never())
                    .saveAndFlush(ArgumentMatchers.any(Account.class));
        }

        @Test
        @DisplayName("an account that cannot be held reports the lock text")
        void accountCannotBeHeld() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            Mockito.when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(seededCustomer()));
            // Chained rather than a varargs sequence, because a varargs call on a generic return type
            // creates an unchecked array and the build treats every warning as an error.
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededAccount()))
                    .thenReturn(Optional.empty());

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn(mintedToken(), KeyAction.PFK05));

            assertThat(response.errorMessage())
                    .isEqualTo("Could not lock account record for update");
        }

        @Test
        @DisplayName("an unchanged turn skips the edits and does not advance to the confirmation state")
        void unchangedTurnSkipsTheEdits() {
            seedRecords();

            final AccountUpdateOutcome response =
                    service.handle(unchangedDetailTurn(mintedToken(), KeyAction.ENTER));

            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.errorMessage())
                    .isEqualTo("No change detected with respect to values fetched.");
            // Lines 2585 to 2591 advance only when the turn has no input error AND a change was seen,
            // so a turn that changed nothing stays on the detail screen rather than offering the save.
            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
        }

        @Test
        @DisplayName("a changed and clean turn advances to the confirmation state")
        void changedCleanTurnAdvancesToConfirmation() {
            seedRecords();

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn(mintedToken(), KeyAction.ENTER));

            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.error()).isFalse();
            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CONFIRMATION);
        }
    }

    @Nested
    @DisplayName("the catch-all arms of the three read paragraphs and of the two update holds")
    class ReadFailureArms {

        /** The eight segments of {@code WS-FILE-ERROR-MESSAGE} sum to the width of {@code WS-RETURN-MSG}. */
        private static final int RETURN_MESSAGE_WIDTH = 75;

        private AccountUpdateCommand searchKeyTurn() {
            return new AccountUpdateCommand(ACCOUNT_ID, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, KeyAction.ENTER,
                    reEntered(), null, false);
        }

        private static String expectedFileError(final String resource) {
            return "File Error: " + "READ    " + " on " + padded(resource) + " returned RESP "
                    + " ".repeat(10) + ",RESP2 " + " ".repeat(10);
        }

        private static String padded(final String resource) {
            return resource + " ".repeat(9 - resource.length());
        }

        @Test
        @DisplayName("a cross-reference read that FAILS is the catch-all arm, not the not-found arm: it "
                + "names the path and the operation, and it stops the read range")
        void aFailingCrossReferenceReadTakesTheCatchAllArm() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));

            final AccountUpdateOutcome response = service.handle(searchKeyTurn());

            assertThat(response.error()).isTrue();
            assertThat(response.errorMessage()).isEqualTo(expectedFileError("CXACAIX"));
            assertThat(response.errorMessage()).hasSize(RETURN_MESSAGE_WIDTH);
            Mockito.verify(accountRepository, Mockito.never())
                    .findById(ArgumentMatchers.anyString());
            Mockito.verify(customerRepository, Mockito.never())
                    .findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("an account-master read that FAILS names the account master and never builds the "
                + "detail screen from records it did not fetch")
        void aFailingAccountReadTakesTheCatchAllArm() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));
            Mockito.when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(seededCustomer()));

            final AccountUpdateOutcome response = service.handle(searchKeyTurn());

            assertThat(response.error()).isTrue();
            assertThat(response.errorMessage()).isEqualTo(expectedFileError("ACCTDAT"));
            assertThat(response.accountStatus())
                    .as("no account was fetched, so no account field may be presented")
                    .isNull();
        }

        @Test
        @DisplayName("a customer-master read that FAILS names the customer master, and its arm raises "
                + "the CUSTOMER filter flag rather than the account one")
        void aFailingCustomerReadTakesTheCatchAllArm() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededAccount()));
            Mockito.when(customerRepository.findById(CUSTOMER_ID))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));

            final AccountUpdateOutcome response = service.handle(searchKeyTurn());

            assertThat(response.error()).isTrue();
            assertThat(response.errorMessage()).isEqualTo(expectedFileError("CUSTDAT"));
            assertThat(response.firstName())
                    .as("no customer was fetched, so no customer field may be presented")
                    .isNull();
        }

        @Test
        @DisplayName("the composed text is the declared width exactly, so the five-character trailing "
                + "filler of the legacy group falls outside the field and nothing is truncated")
        void theComposedTextFillsTheDeclaredWidthExactly() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));

            final String composed = service.handle(searchKeyTurn()).errorMessage();

            assertThat(composed).hasSize(RETURN_MESSAGE_WIDTH);
            assertThat(composed.substring(0, 12)).isEqualTo("File Error: ");
            assertThat(composed.substring(12, 20)).isEqualTo("READ    ");
            assertThat(composed.substring(20, 24)).isEqualTo(" on ");
            assertThat(composed.substring(24, 33)).isEqualTo("CXACAIX  ");
            assertThat(composed.substring(33, 48)).isEqualTo(" returned RESP ");
            assertThat(composed.substring(48, 58))
                    .as("a relational store reports no CICS response pair, so nothing is fabricated")
                    .isEqualTo(" ".repeat(10));
            assertThat(composed.substring(58, 65)).isEqualTo(",RESP2 ");
            assertThat(composed.substring(65, 75)).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("the catch-all text OVERWRITES a message an earlier edit had claimed, because the "
                + "source moves it ungated while every not-found arm moves it through the gate")
        void theCatchAllTextOverwritesAClaimedMessage() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));

            final AccountUpdateOutcome response = service.handle(searchKeyTurn());

            assertThat(response.errorMessage())
                    .isNotEqualTo("Did not find this account in account card xref file")
                    .isEqualTo(expectedFileError("CXACAIX"));
        }

        @Test
        @DisplayName("an account HOLD that fails reaches the could-not-lock arm, because the source "
                + "tests for the normal response and treats every other response alike")
        void aFailingAccountHoldReachesTheLockArm() {
            seedRecords();
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededAccount()))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn(mintedToken(), KeyAction.PFK05));

            assertThat(response.error()).isTrue();
            assertThat(response.errorMessage())
                    .isEqualTo(OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            Mockito.verify(accountRepository, Mockito.never())
                    .saveAndFlush(ArgumentMatchers.any(Account.class));
            Mockito.verify(customerRepository, Mockito.never())
                    .compareAndSet(ArgumentMatchers.any(Customer.class),
                            ArgumentMatchers.any(Customer.class));
        }

        @Test
        @DisplayName("a customer HOLD that fails reaches its own could-not-lock arm and leaves the "
                + "write range before either rewrite is attempted")
        void aFailingCustomerHoldReachesTheLockArm() {
            seedRecords();
            Mockito.when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(seededCustomer()))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));

            final AccountUpdateOutcome response =
                    service.handle(changedDetailTurn(mintedToken(), KeyAction.PFK05));

            assertThat(response.error()).isTrue();
            assertThat(response.errorMessage())
                    .isEqualTo(OptimisticLockConflictException.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE);
            Mockito.verify(accountRepository, Mockito.never())
                    .saveAndFlush(ArgumentMatchers.any(Account.class));
        }
    }

    @Nested
    @DisplayName("keys and navigation")
    class KeysAndNavigation {

        @Test
        @DisplayName("the exit key resolves the caller's destination through the navigation service")
        void exitKeyRoutesBack() {
            final AccountUpdateCommand request = new AccountUpdateCommand(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    KeyAction.PFK03, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.nextRoute()).isEqualTo("user-menu");
            assertThat(response.navigationContext().fromTransactionId()).isEqualTo("CAUP");
            assertThat(response.navigationContext().fromProgram()).isEqualTo("COACTUPC");
            assertThat(response.navigationContext().programContext())
                    .isEqualTo(ScreenNavigationState.ProgramContext.ENTER);
        }

        @Test
        @DisplayName("keys 13 to 24 fold onto keys 1 to 12, so the exit key is reached either way")
        void highFunctionKeysFold() {
            final AccountUpdateCommand request = new AccountUpdateCommand(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome folded = service.handle(request, "DFHPF15");

            assertThat(folded.nextRoute()).isEqualTo("user-menu");
        }

        @Test
        @DisplayName("an unmapped identifier claims the fifty-character invalid-key text, untrimmed")
        void unmappedIdentifierClaimsTheCatalogueText() {
            final AccountUpdateCommand request = new AccountUpdateCommand(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request, "DFHNOSUCH");

            assertThat(response.errorMessage()).hasSize(50);
            assertThat(response.errorMessage()).isEqualTo(
                    "Invalid key pressed. Please see below...               ".substring(0, 50));
            assertThat(response.error()).isTrue();
        }

        @Test
        @DisplayName("a save key outside the confirmation state is forced to enter")
        void outOfContextSaveKeyBecomesEnter() {
            seedRecords();

            final AccountUpdateOutcome response =
                    service.handle(unchangedDetailTurn(mintedToken(), KeyAction.PFK12));

            assertThat(response.infoMessage()).isEqualTo(INFO_PROMPT_FOR_CHANGES);
        }

        @Test
        @DisplayName("an omitted attention key falls back to enter rather than failing")
        void omittedAttentionKeyDefaultsToEnter() {
            // No raw identifier and no key on the request: the work area carries nothing, so the
            // fallback supplies the enter key exactly as an unmodified 3270 read would.
            final AccountUpdateCommand request = new AccountUpdateCommand(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.nextRoute()).isEqualTo("account-update");
            assertThat(response.errorMessage()).isEqualTo(MSG_ACCOUNT_NUMBER_NOT_PROVIDED);
        }

        @Test
        @DisplayName("a null request is a programming error")
        void nullRequestRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.handle(null));
        }
    }

    @Nested
    @DisplayName("the remaining edit paragraphs")
    class RemainingEdits {

        /** A detail turn with one substituted value, so a change is always seen and the edits run. */
        private AccountUpdateOutcome turnWith(final String status, final String ssn1,
                final String ssn2, final String ssn3, final String zip, final String eft,
                final String priCardHolder, final String creditLimit, final String dobYear,
                final String dobMonth, final String dobDay) {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, status, "2020",
                    "01", "15", creditLimit, "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, ssn1, ssn2, ssn3,
                    dobYear, dobMonth, dobDay, "700", "Aniya Von", "Q", "Smith", "1 High Street",
                    "NY", "Flat 2", zip, "Springfield", "USA", "201", "555", "0100", null, "202",
                    "555", "0101", eft, priCardHolder, KeyAction.ENTER, reEntered(), mintedToken(), false);
            return service.handle(request);
        }

        @ParameterizedTest
        @ValueSource(strings = {"666", "900", "999"})
        @DisplayName("reject an excluded national-identifier first part")
        void excludedSsnFirstPart(final String part1) {
            final AccountUpdateOutcome response = turnWith("Y", part1, "45", "6789", "10001",
                    "1234567890", "Y", "5000.00", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACTSSN1");
            assertThat(response.errorMessage())
                    .isEqualTo("SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        }

        @Test
        @DisplayName("reject an all-zero first part on the numeric edit, before the range check gates")
        void zeroSsnFirstPartFailsTheNumericEditFirst() {
            // 000 is in the excluded range too, but the required-numeric edit runs first and its
            // non-zero test claims the summary slot. The range check is gated on the flag still being
            // valid, so it never re-edits the field. Both the ordering and the gate are the contract.
            final AccountUpdateOutcome response = turnWith("Y", "000", "45", "6789", "10001",
                    "1234567890", "Y", "5000.00", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACTSSN1");
            assertThat(response.errorMessage()).isEqualTo("SSN: First 3 chars must not be zero.");
        }

        @Test
        @DisplayName("accept a national-identifier first part just outside the excluded range")
        void permittedSsnFirstPart() {
            assertThat(screenFieldIdsOf(turnWith("Y", "899", "45", "6789", "10001", "1234567890",
                    "Y", "5000.00", "1980", "02", "03")))
                    .doesNotContain("ACTSSN1", "ACTSSN2", "ACTSSN3");
        }

        @Test
        @DisplayName("reject a non-numeric postcode through the required-numeric edit")
        void nonNumericZip() {
            final AccountUpdateOutcome response = turnWith("Y", "123", "45", "6789", "1000X",
                    "1234567890", "Y", "5000.00", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACSZIPC");
            assertThat(response.errorMessage()).isEqualTo("Zip must be all numeric.");
        }

        @Test
        @DisplayName("reject a non-numeric transfer-account identifier")
        void nonNumericEftAccount() {
            final AccountUpdateOutcome response = turnWith("Y", "123", "45", "6789", "10001",
                    "123456789X", "Y", "5000.00", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACSEFTC");
            assertThat(response.errorMessage()).isEqualTo("EFT Account Id must be all numeric.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"X", "1", "y"})
        @DisplayName("reject a primary-cardholder flag that is neither Y nor N")
        void badPrimaryCardHolderFlag(final String flag) {
            final AccountUpdateOutcome response = turnWith("Y", "123", "45", "6789", "10001",
                    "1234567890", flag, "5000.00", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACSPFLG");
            assertThat(response.errorMessage()).isEqualTo("Primary Card Holder must be Y or N.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Y", "N"})
        @DisplayName("accept the two flag values the condition name enumerates")
        void goodPrimaryCardHolderFlag(final String flag) {
            assertThat(screenFieldIdsOf(turnWith("Y", "123", "45", "6789", "10001", "1234567890",
                    flag, "5000.00", "1980", "02", "03"))).doesNotContain("ACSPFLG");
        }

        @Test
        @DisplayName("reject an account status that is neither Y nor N")
        void badAccountStatus() {
            final AccountUpdateOutcome response = turnWith("X", "123", "45", "6789", "10001",
                    "1234567890", "Y", "5000.00", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACSTTUS");
            assertThat(response.errorMessage()).isEqualTo("Account Status must be Y or N.");
        }

        @Test
        @DisplayName("reject a malformed monetary amount")
        void malformedAmount() {
            final AccountUpdateOutcome response = turnWith("Y", "123", "45", "6789", "10001",
                    "1234567890", "Y", "12.3.4", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACRDLIM");
            assertThat(response.errorMessage()).isEqualTo("Credit Limit is not valid");
        }

        @Test
        @DisplayName("reject an amount whose magnitude exceeds the record's ten integer digits")
        void oversizedAmount() {
            final AccountUpdateOutcome response = turnWith("Y", "123", "45", "6789", "10001",
                    "1234567890", "Y", "99999999999.99", "1980", "02", "03");

            assertThat(screenFieldIdsOf(response)).contains("ACRDLIM");
            assertThat(response.errorMessage()).isEqualTo("Credit Limit is not valid");
        }

        @Test
        @DisplayName("delegate the date cascade, so a bad month reports against the month field")
        void badMonthReportsThroughTheCascade() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "13", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", "Aniya Von", "Q", "Smith", "1 High Street", "NY",
                    "Flat 2", "10001", "Springfield", "USA", "201", "555", "0100", null, "202",
                    "555", "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(screenFieldIdsOf(response)).contains("OPNMON");
            assertThat(response.error()).isTrue();
        }

        @Test
        @DisplayName("delegate the date-of-birth check through its own separate entry point")
        void badDateOfBirthReportsThroughItsOwnEntryPoint() {
            final AccountUpdateOutcome response = turnWith("Y", "123", "45", "6789", "10001",
                    "1234567890", "Y", "5000.00", "1980", "02", "31");

            assertThat(screenFieldIdsOf(response))
                    .containsAnyOf("DOBYEAR", "DOBMON", "DOBDAY");
            assertThat(response.error()).isTrue();
        }

        @Test
        @DisplayName("reject a blank mandatory address line 1")
        void blankAddressLine1() {
            seedRecords();
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, "Y", "2020",
                    "01", "15", "5000.00", "2029", "01", "15", "2000.00", "2024", "01", "15",
                    "1000.00", "100.00", "          ", "50.00", CUSTOMER_ID, "123", "45", "6789",
                    "1980", "02", "03", "700", "Aniya Von", "Q", "Smith", "   ", "NY", "Flat 2",
                    "10001", "Springfield", "USA", "201", "555", "0100", null, "202", "555",
                    "0101", "1234567890", "Y", KeyAction.ENTER, reEntered(), mintedToken(), false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(screenFieldIdsOf(response)).contains("ACSADL1");
            assertThat(response.errorMessage()).isEqualTo("Address Line 1 must be supplied.");
        }

        @Test
        @DisplayName("report an account the master does not hold")
        void accountAbsentFromMaster() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.errorMessage())
                    .isEqualTo("Did not find this account in account master file");
            Mockito.verify(customerRepository, Mockito.never())
                    .findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("report a customer the master does not hold")
        void customerAbsentFromMaster() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededAccount()));
            Mockito.when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
            final AccountUpdateCommand request = new AccountUpdateCommand(ACCOUNT_ID, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, KeyAction.ENTER, reEntered(), null, false);

            final AccountUpdateOutcome response = service.handle(request);

            assertThat(response.errorMessage())
                    .isEqualTo("Did not find associated customer in master file");
        }
    }

    @Nested
    @DisplayName("the translated level-88 groups")
    class ConditionNameGroups {

        @Test
        @DisplayName("the field flag triad carries the macro's two conditions")
        void fieldFlagPredicates() {
            assertThat(AccountUpdateService.FieldFlag.values()).hasSize(3);
            assertThat(AccountUpdateService.FieldFlag.ISVALID.isValid()).isTrue();
            assertThat(AccountUpdateService.FieldFlag.ISVALID.requiresDecoration()).isFalse();
            assertThat(AccountUpdateService.FieldFlag.ISVALID.writesMissingMarker()).isFalse();
            assertThat(AccountUpdateService.FieldFlag.NOT_OK.isNotOk()).isTrue();
            assertThat(AccountUpdateService.FieldFlag.NOT_OK.requiresDecoration()).isTrue();
            assertThat(AccountUpdateService.FieldFlag.NOT_OK.writesMissingMarker()).isFalse();
            assertThat(AccountUpdateService.FieldFlag.BLANK.isBlank()).isTrue();
            assertThat(AccountUpdateService.FieldFlag.BLANK.requiresDecoration()).isTrue();
            assertThat(AccountUpdateService.FieldFlag.BLANK.writesMissingMarker()).isTrue();
        }

        @ParameterizedTest
        @CsvSource({"DETAILS_NOT_FETCHED,' ',false,false", "SHOW_DETAILS,S,false,false",
            "CHANGES_NOT_OK,E,true,false", "CHANGES_OK_NOT_CONFIRMED,N,true,false",
            "CHANGES_OKAYED_AND_DONE,C,true,false", "CHANGES_OKAYED_LOCK_ERROR,L,true,true",
            "CHANGES_OKAYED_BUT_FAILED,F,true,true"})
        @DisplayName("the change-action group carries its byte and its two grouping conditions")
        void changeActionPredicates(final String name, final char code, final boolean made,
                final boolean failed) {
            final AccountUpdateService.ChangeAction action =
                    AccountUpdateService.ChangeAction.valueOf(name);
            assertThat(action.getCode()).isEqualTo(code);
            assertThat(action.changesMade()).isEqualTo(made);
            assertThat(action.changesFailed()).isEqualTo(failed);
        }

        @Test
        @DisplayName("the screen-field group declares exactly 39 constants in the macro's order")
        void screenFieldOrderAndIdentity() {
            final AccountUpdateService.ScreenField[] fields =
                    AccountUpdateService.ScreenField.values();
            assertThat(fields).hasSize(39);
            assertThat(java.util.Arrays.stream(fields)
                    .map(AccountUpdateService.ScreenField::getBmsFieldId).toList())
                    .containsExactlyElementsOf(DECORATION_ORDER);
            assertThat(AccountUpdateService.ScreenField.ACCT_STATUS.getLegacyFlagToken())
                    .isEqualTo("ACCT-STATUS");
            assertThat(AccountUpdateService.ScreenField.PRI_CARDHOLDER.getBmsFieldId())
                    .isEqualTo("ACSPFLG");
            assertThat(AccountUpdateService.ScreenField.EFT_ACCOUNT_ID.getBmsFieldId())
                    .isEqualTo("ACSEFTC");
            assertThat(AccountUpdateService.ScreenField.ZIPCODE.getLegacyLabel()).isEqualTo("Zip");
            assertThat(AccountUpdateService.ScreenField.MIDDLE_NAME.neverValidated()).isTrue();
            assertThat(AccountUpdateService.ScreenField.ADDRESS_LINE_2.neverValidated()).isTrue();
            assertThat(AccountUpdateService.ScreenField.FIRST_NAME.neverValidated()).isFalse();
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("the three identification-division paragraphs are surfaced by name")
        void identificationParagraphs() {
            assertThat(service.programIdParagraph()).isEqualTo("COACTUPC");
            assertThat(service.dateWrittenParagraph()).isEqualTo("July 2022.");
            assertThat(service.dateCompiledParagraph()).isEqualTo("Today.");
        }

        @Test
        @DisplayName("every collaborator is required")
        void collaboratorsAreRequired() {
            final SensitiveFieldEncryptionService encryption =
                    new SensitiveFieldEncryptionService(BASE64_KEY);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AccountUpdateService(null, customerRepository,
                            crossReferenceRepository, new DateValidationService(), lookupService,
                            new MessageCatalogService(), new NavigationService(), new AbendService(),
                            tokenService, encryption, new OnlineTransactionBoundary(),
                            Clock.systemUTC()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AccountUpdateService(accountRepository,
                            customerRepository, crossReferenceRepository,
                            new DateValidationService(), lookupService, new MessageCatalogService(),
                            new NavigationService(), new AbendService(), tokenService, encryption,
                            null, Clock.systemUTC()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AccountUpdateService(accountRepository,
                            customerRepository, crossReferenceRepository,
                            new DateValidationService(), lookupService, new MessageCatalogService(),
                            new NavigationService(), new AbendService(), tokenService, encryption,
                            new OnlineTransactionBoundary(), null));
        }
    }
}
