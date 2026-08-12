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
package com.carddemo.api;

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.AccountViewResponse;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.Account;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.AccountUpdateCommand;
import com.carddemo.service.AccountUpdateOutcome;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenInputState;
import com.carddemo.service.ScreenNavigationState;
import com.carddemo.service.SensitiveFieldEncryptionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The account screens' boundary: the view route carrying legacy transaction CAVW and the update route
 * carrying CAUP.
 *
 * <p>Unlike the other screen boundaries in this package this one does real work of its own, and that
 * work is what this class holds. The view route assembles the screen work area rather than accepting
 * one, because the original transaction reads exactly one input field off it; and it projects the
 * service's result onto the published response, which is where the three field groups are gated
 * independently, where the regulated components are resolved through the protected-data adapter, and
 * where two stored values are cut back to the widths the map declares. Every one of those is a
 * decision taken here and nowhere else, so every one is asserted here.
 *
 * <p>The regulated components deserve the closest reading. The authority the boundary presents to the
 * adapter is derived from the established identity, by one derivation both routes read: an administrator
 * reveals and every other caller receives masks. That is asserted in both directions here, because a
 * change in either direction would still compile and would still pass every service test. The boundary
 * A <em>constant</em> unprivileged authority on the view route would mask the values for an
 * administrator while the update route revealed them - the two screens disagreeing about the same four
 * values of the same record - so the administrative reveal on the view route is asserted directly and not
 * merely on the update route. The absent-customer path is asserted to yield absent rather than masked
 * values, because a mask would tell an operator a record exists.
 */
@DisplayName("AccountController - the account view and update routes")
class AccountControllerTest {

    /** A non-production key, present so the encryption collaborator can be built at all. */
    private static final String FIXTURE_KEY = "Y2FyZGRlbW8tbm9ucHJvZC1maXh0dXJlLWtleSEhISE=";

    /** The eleven-digit account identifier the screens carry. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A stored postcode longer than the five characters the map publishes. */
    private static final String STORED_ZIP = "482261234";

    /** A stored telephone number longer than the thirteen characters the map publishes. */
    private static final String STORED_PHONE_1 = "(313)555-0100EXT9";

    /** A second stored telephone number, already inside the published width. */
    private static final String STORED_PHONE_2 = "(248)555-019";

    /** A stored birth date, one of the four regulated components. */
    private static final String BIRTH_DATE = "1984-07-22";

    /** A stored electronic-funds identifier, another regulated component. */
    private static final String EFT_ACCOUNT_ID = "4471902856";

    /** The first national-identifier position as the transaction composes it. */
    private static final String CLEAR_SSN_PART_1 = "123";

    /** The middle national-identifier position. */
    private static final String CLEAR_SSN_PART_2 = "45";

    /** The final national-identifier position, which the gate retains by design. */
    private static final String CLEAR_SSN_PART_3 = "6789";

    /** The birth year as the update screen carries it. */
    private static final String CLEAR_DOB_YEAR = "1984";

    /** The birth month. */
    private static final String CLEAR_DOB_MONTH = "07";

    /** The birth day. */
    private static final String CLEAR_DOB_DAY = "22";

    /** The government-issued identifier, which the gate keeps no character of. */
    private static final String CLEAR_GOVT_ISSUED_ID = "NY-DL-88231947";

    private AccountViewService accountViewService;

    private AccountUpdateService accountUpdateService;

    private AccountProtectedDataAdapter accountProtectedDataAdapter;

    private ScreenStateAdapter screenStateAdapter;

    /**
     * The real converter between the account-update wire contract and the service-owned pair.
     *
     * <p>The real one rather than a mock: it holds no mutable state and copies forty-six components inbound
     * and fifty-seven outbound positionally, so stubbing it would leave the delegation test measuring a stub
     * instead of the crossing.
     */
    private AccountUpdateContractAdapter accountUpdateContractAdapter;

    private MeterRegistry meterRegistry;

    private AccountController controller;

    @BeforeEach
    void setUp() {
        accountViewService = mock(AccountViewService.class);
        accountUpdateService = mock(AccountUpdateService.class);
        accountProtectedDataAdapter =
                new AccountProtectedDataAdapter(new SensitiveFieldEncryptionService(FIXTURE_KEY));
        // The real converter rather than a mock: it holds no state, performs a positional copy and is the
        // seam under test here, so stubbing it would measure the stub instead of the crossing.
        screenStateAdapter = new ScreenStateAdapter(new NavigationService());
        accountUpdateContractAdapter = new AccountUpdateContractAdapter(screenStateAdapter);
        meterRegistry = new SimpleMeterRegistry();
        controller = new AccountController(accountViewService, accountUpdateService,
                accountProtectedDataAdapter, screenStateAdapter, accountUpdateContractAdapter,
                meterRegistry);
    }

    /**
     * Builds the header group the send paragraph computes.
     *
     * @return a populated header
     */
    private static AccountViewService.ScreenHeader header() {
        return new AccountViewService.ScreenHeader("CAVW", "COACTVWC", "TITLE ONE", "TITLE TWO",
                "07/19/22", "14:23:07");
    }

    /**
     * Builds the account row the screen presents.
     *
     * @return a populated account
     */
    private static Account account() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal("1234.56"), new BigDecimal("5000.00"),
                new BigDecimal("2000.00"), "2020-01-15", "2029-01-15", "2024-01-15",
                new BigDecimal("100.00"), new BigDecimal("50.00"), "48226", "ZEROAPR");
    }

    /**
     * Builds the customer row the screen presents, with both sealed columns absent so no envelope is
     * needed and the mask can still be observed on the other two regulated components.
     *
     * @return a populated customer
     */
    private static Customer customer() {
        return new Customer("000000011", "MARY ANN", "Q", "Aniya Von", "1500 Woodward Avenue",
                "Apt. 4B", "Detroit", "MI", "USA", STORED_ZIP, STORED_PHONE_1, STORED_PHONE_2,
                null, null, BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");
    }

    /**
     * Builds a view result with both field groups presented.
     *
     * @return the result
     */
    private static AccountViewService.AccountViewResult presentedResult() {
        return result(header(), account(), customer(), true, true,
                AccountViewService.Presentation.MAP);
    }

    /**
     * Builds a view result over the supplied parts.
     *
     * @param header the header group, or {@code null} when control left the screen
     * @param account the account row, or {@code null}
     * @param customer the customer row, or {@code null}
     * @param accountPresented whether the account group was placed on the map
     * @param customerPresented whether the customer group was placed on the map
     * @param presentation the presentation arm the turn took
     * @return the result
     */
    private static AccountViewService.AccountViewResult result(
            final AccountViewService.ScreenHeader header,
            final Account account,
            final Customer customer,
            final boolean accountPresented,
            final boolean customerPresented,
            final AccountViewService.Presentation presentation) {
        return new AccountViewService.AccountViewResult(
                "account-view", ScreenNavigationState.empty(), header, presentation, account, customer,
                ACCOUNT_ID, "ERROR TEXT", "INFO TEXT", "ACCTSID", true, false,
                AccountViewService.FilterFlag.VALID, AccountViewService.FilterFlag.VALID,
                account != null, customer != null, accountPresented, customerPresented,
                false, false, false, "LONG TEXT");
    }

    /**
     * Builds a view result carrying the two filter states and the two decoration conditions the turn
     * establishes for them.
     *
     * <p>The decoration conditions are supplied rather than derived, because the turn is what establishes
     * them - the unusable condition unconditionally from the flag, the unsupplied one only on a re-entry -
     * and a fixture that recomputed them would be asserting its own arithmetic instead of the projection.
     *
     * @param accountFilter the account-filter state the turn settled on
     * @param customerFilter the customer-filter state the turn settled on
     * @param filterInError whether the turn marked the filter unusable
     * @param filterMissingOnReEntry whether the turn marked the filter unsupplied on a re-entry
     * @return the result
     */
    private static AccountViewService.AccountViewResult filterResult(
            final AccountViewService.FilterFlag accountFilter,
            final AccountViewService.FilterFlag customerFilter,
            final boolean filterInError,
            final boolean filterMissingOnReEntry) {
        return new AccountViewService.AccountViewResult(
                "account-view", ScreenNavigationState.empty(), header(),
                AccountViewService.Presentation.MAP, null, null,
                ACCOUNT_ID, "ERROR TEXT", "INFO TEXT", "ACCTSID", true, false,
                accountFilter, customerFilter,
                false, false, false, false,
                filterInError, filterMissingOnReEntry, false, "LONG TEXT");
    }

    /**
     * Builds an update response carrying only the three components this boundary reads.
     *
     * @param error whether the turn raised the screen's input-error switch
     * @param nextRoute the route the turn resolved
     * @param fieldErrors the field-level errors the turn composed
     * @return the response
     */
    private static AccountUpdateOutcome updateResponse(final boolean error, final String nextRoute,
            final List<ValidationException.FieldError> fieldErrors) {
        return new AccountUpdateOutcome(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                error, "ACSTTUS", nextRoute, ScreenNavigationState.empty(), fieldErrors, null);
    }

    /**
     * An update screen carrying the eight regulated components in the clear, exactly as the transaction
     * composes it before the boundary gates it.
     *
     * @return the composed screen
     */
    private static AccountUpdateOutcome updateResponseWithRegulatedValues() {
        return new AccountUpdateOutcome(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                CLEAR_SSN_PART_1, CLEAR_SSN_PART_2, CLEAR_SSN_PART_3,
                CLEAR_DOB_YEAR, CLEAR_DOB_MONTH, CLEAR_DOB_DAY,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                CLEAR_GOVT_ISSUED_ID,
                null, null, null,
                EFT_ACCOUNT_ID,
                null, null, null,
                false, "ACSTTUS", "account-update", ScreenNavigationState.empty(), List.of(), null);
    }

    /**
     * An established identity carrying the authority the chain grants for one user type.
     *
     * @param userType the signed-on type
     * @return the identity
     */
    private static Authentication identityOf(final UserType userType) {
        return new TestingAuthenticationToken("TESTUSR1", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    /**
     * Reads the count of the view timer carrying a presentation tag.
     *
     * @param presentation the expected tag value
     * @return the number of turns recorded
     */
    private long viewTimed(final String presentation) {
        return meterRegistry.get("carddemo.online.account.view.turn")
                .tag("presentation", presentation).timer().count();
    }

    /**
     * Reads the count of the update timer carrying an outcome tag.
     *
     * @param outcome the expected tag value
     * @return the number of turns recorded
     */
    private long updateTimed(final String outcome) {
        return meterRegistry.get("carddemo.online.account.update.turn")
                .tag("outcome", outcome).timer().count();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("no collaborator may be absent, so a misassembled context fails at construction")
        void noCollaboratorMayBeAbsent() {
            assertThatNullPointerException().isThrownBy(() -> new AccountController(null,
                    accountUpdateService, accountProtectedDataAdapter, screenStateAdapter,
                    accountUpdateContractAdapter, meterRegistry))
                    .withMessageContaining("accountViewService");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    null, accountProtectedDataAdapter, screenStateAdapter,
                    accountUpdateContractAdapter, meterRegistry))
                    .withMessageContaining("accountUpdateService");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    accountUpdateService, null, screenStateAdapter, accountUpdateContractAdapter,
                    meterRegistry))
                    .withMessageContaining("accountProtectedDataAdapter");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    accountUpdateService, accountProtectedDataAdapter, null,
                    accountUpdateContractAdapter, meterRegistry))
                    .withMessageContaining("screenStateAdapter");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    accountUpdateService, accountProtectedDataAdapter, screenStateAdapter, null,
                    meterRegistry))
                    .withMessageContaining("accountUpdateContractAdapter");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    accountUpdateService, accountProtectedDataAdapter, screenStateAdapter,
                    accountUpdateContractAdapter, null))
                    .withMessageContaining("meterRegistry");
        }
    }

    @Nested
    @DisplayName("The view route assembles the work area rather than accepting one")
    class ViewRouteInput {

        @Test
        @DisplayName("the submitted identifier is the only member of the work area that is populated")
        void theSubmittedIdentifierIsTheOnlyPopulatedMember() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            controller.viewAccount(ACCOUNT_ID, "DFHENTER", NavigationContext.empty(), identityOf(UserType.USER));

            ArgumentCaptor<ScreenInputState> captor = ArgumentCaptor.forClass(ScreenInputState.class);
            verify(accountViewService).viewAccount(eq("DFHENTER"), captor.capture(), any());
            ScreenInputState assembled = captor.getValue();
            assertThat(assembled.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(assembled.keyAction()).isNull();
            assertThat(assembled.nextProgram()).isNull();
            assertThat(assembled.nextMapset()).isNull();
            assertThat(assembled.nextMap()).isNull();
            assertThat(assembled.errorMessage()).isNull();
            assertThat(assembled.returnMessage()).isNull();
            assertThat(assembled.cardNumber()).isNull();
            assertThat(assembled.customerId()).isNull();
        }

        @Test
        @DisplayName("an absent identifier and an absent key reach the transaction as absent, which is "
                + "the outcome that asks for one")
        void anAbsentIdentifierReachesTheTransactionAsAbsent() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            controller.viewAccount(null, null, null, identityOf(UserType.USER));

            ArgumentCaptor<ScreenInputState> captor = ArgumentCaptor.forClass(ScreenInputState.class);
            // An absent body reaches the transaction as the empty carried state rather than as a null
            // reference, which is what this screen already treats as no carry-over: its own absence test
            // answers identically for a null reference and for an all-blank state.
            verify(accountViewService).viewAccount(isNull(), captor.capture(),
                    eq(ScreenNavigationState.empty()
                            .reconciledWith("TESTUSR1", UserType.USER)));
            assertThat(captor.getValue().accountId()).isNull();
        }

        @Test
        @DisplayName("the echoed navigation record is handed over whole")
        void theEchoedNavigationRecordIsHandedOverWhole() {
            NavigationContext echoed = new NavigationContext("CAVW", "COMEN01C", "CAVW", "COACTVWC",
                    "USER0001", "U", NavigationContext.ProgramContext.REENTER, "000000123", "ANN",
                    "B", "SMITH", "00000000456", "Y", "4111111111111111", "CACTVWA", "COACTVW");
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            controller.viewAccount(ACCOUNT_ID, "DFHPF03", echoed, identityOf(UserType.USER));

            // Every one of the sixteen members crosses positionally, so the state the transaction receives
            // is the echoed record component for component and nothing was trimmed, dropped or defaulted.
            verify(accountViewService).viewAccount(eq("DFHPF03"), any(),
                    eq(new ScreenStateAdapter(new NavigationService())
                            .toNavigationState(echoed, identityOf(UserType.USER))));
        }

        @Test
        @DisplayName("the identity the transaction reads is the authenticated one, not the one the client "
                + "echoed, so a standard user cannot present itself as an administrator")
        void theTransactionReadsTheAuthenticatedIdentity() {
            NavigationContext claimingAdmin = new NavigationContext("CAVW", "COMEN01C", "CAVW",
                    "COACTVWC", "SOMEBODY", "A", NavigationContext.ProgramContext.REENTER, null,
                    null, null, null, null, null, null, null, null);
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            controller.viewAccount(ACCOUNT_ID, null, claimingAdmin, identityOf(UserType.USER));

            ArgumentCaptor<ScreenNavigationState> captor =
                    ArgumentCaptor.forClass(ScreenNavigationState.class);
            verify(accountViewService).viewAccount(any(), any(), captor.capture());
            assertThat(captor.getValue().userId()).isEqualTo("TESTUSR1");
            assertThat(captor.getValue().userType()).isEqualTo(UserType.USER.getCode());
        }

        @Test
        @DisplayName("an echoed program name that names no destination reaches the transaction as blank "
                + "rather than as a terminal failure, because a blank nomination is what makes the "
                + "calling screen's default apply")
        void anUnresolvableProgramNominationReachesTheTransactionAsBlank() {
            NavigationContext invented = new NavigationContext("CAVW", "NOTAPGM1", "CAVW", "ALSONOPE",
                    "USER0001", "U", NavigationContext.ProgramContext.REENTER, null, null, null,
                    null, null, null, null, null, null);
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            ResponseEntity<AccountViewResponse> answer =
                    controller.viewAccount(ACCOUNT_ID, null, invented, identityOf(UserType.USER));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<ScreenNavigationState> captor =
                    ArgumentCaptor.forClass(ScreenNavigationState.class);
            verify(accountViewService).viewAccount(any(), any(), captor.capture());
            assertThat(captor.getValue().fromProgram()).isNull();
            assertThat(captor.getValue().toProgram()).isNull();
            // The rest of the turn is untouched: the transaction identifiers the estate declares and the
            // re-entry gate both survive the screening.
            assertThat(captor.getValue().fromTransactionId()).isEqualTo("CAVW");
            assertThat(captor.getValue().reEntry()).isTrue();
        }
    }

    @Nested
    @DisplayName("The view route's two filter states become ordered field-level findings")
    class ViewFilterFindings {

        @Test
        @DisplayName("a filter that was never supplied reports as unsupplied, which the legacy shows with "
                + "the marker beside the field rather than only with a colour change")
        void anUnsuppliedFilterReportsAsUnsupplied() {
            when(accountViewService.viewAccount(any(), any(), any()))
                    .thenReturn(filterResult(AccountViewService.FilterFlag.BLANK,
                            AccountViewService.FilterFlag.VALID, false, true));

            AccountViewResponse body =
                    controller.viewAccount(null, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo("accountId");
            assertThat(body.fieldErrors().get(0).screenFieldId()).isEqualTo("ACCTSID");
            assertThat(body.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(body.fieldErrors().get(0).message()).isEqualTo("ERROR TEXT");
        }

        @Test
        @DisplayName("a filter that was supplied and cannot be used reports as unusable, so the two states "
                + "stay distinguishable rather than collapsing onto one flag")
        void anUnusableFilterReportsAsUnusable() {
            when(accountViewService.viewAccount(any(), any(), any()))
                    .thenReturn(filterResult(AccountViewService.FilterFlag.NOT_OK,
                            AccountViewService.FilterFlag.VALID, true, false));

            AccountViewResponse body =
                    controller.viewAccount("0000000000A", null, null, identityOf(UserType.USER))
                            .getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("an unsupplied filter reports nothing on a first entry, because the operator has not "
                + "been asked yet - the same gate the field-decoration macro applies")
        void anUnsuppliedFilterReportsNothingOnAFirstEntry() {
            when(accountViewService.viewAccount(any(), any(), any()))
                    .thenReturn(filterResult(AccountViewService.FilterFlag.BLANK,
                            AccountViewService.FilterFlag.VALID, false, false));

            AccountViewResponse body =
                    controller.viewAccount(null, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("the customer-master miss reports as its own finding after the account one, which is "
                + "how it is told apart on a screen with a single input item")
        void theCustomerMissReportsAsItsOwnFindingAfterTheAccountOne() {
            when(accountViewService.viewAccount(any(), any(), any()))
                    .thenReturn(filterResult(AccountViewService.FilterFlag.NOT_OK,
                            AccountViewService.FilterFlag.NOT_OK, true, false));

            AccountViewResponse body =
                    controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER))
                            .getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .as("the account filter is edited before the customer master is ever read, so its "
                            + "finding precedes the customer one")
                    .containsExactly("accountId", "customerId");
            assertThat(body.fieldErrors().get(1).screenFieldId())
                    .as("this screen has one input item, so the customer state names no map field")
                    .isEmpty();
        }

        @Test
        @DisplayName("a turn with both filters valid publishes an empty finding list rather than none, so "
                + "a clean screen is distinguishable from one whose findings were never established")
        void aCleanTurnPublishesAnEmptyFindingList() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body =
                    controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("The view route's projection onto the published response")
    class ViewProjection {

        @Test
        @DisplayName("the header group is carried across item for item")
        void theHeaderGroupIsCarriedAcrossItemForItem() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body =
                    controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.transactionName()).isEqualTo("CAVW");
            assertThat(body.programName()).isEqualTo("COACTVWC");
            assertThat(body.title01()).isEqualTo("TITLE ONE");
            assertThat(body.title02()).isEqualTo("TITLE TWO");
            assertThat(body.currentDate()).isEqualTo("07/19/22");
            assertThat(body.currentTime()).isEqualTo("14:23:07");
        }

        @Test
        @DisplayName("a turn that handed control to another screen assembled no header, and every "
                + "header item is then absent rather than defaulted")
        void aTurnThatHandedControlOnAssemblesNoHeader() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(null, null, null, false, false,
                            AccountViewService.Presentation.TRANSFER));

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, "DFHPF03", null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.transactionName()).isNull();
            assertThat(body.programName()).isNull();
            assertThat(body.title01()).isNull();
            assertThat(body.title02()).isNull();
            assertThat(body.currentDate()).isNull();
            assertThat(body.currentTime()).isNull();
        }

        @Test
        @DisplayName("the account group is carried across when it was placed on the map")
        void theAccountGroupIsCarriedAcrossWhenPresented() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(body.accountStatus()).isEqualTo("Y");
            assertThat(body.openDate()).isEqualTo("2020-01-15");
            assertThat(body.creditLimit()).isEqualByComparingTo("5000.00");
            assertThat(body.expirationDate()).isEqualTo("2029-01-15");
            assertThat(body.cashCreditLimit()).isEqualByComparingTo("2000.00");
            assertThat(body.reissueDate()).isEqualTo("2024-01-15");
            assertThat(body.currentBalance()).isEqualByComparingTo("1234.56");
            assertThat(body.currentCycleCredit()).isEqualByComparingTo("100.00");
            assertThat(body.currentCycleDebit()).isEqualByComparingTo("50.00");
            assertThat(body.accountGroupId()).isEqualTo("ZEROAPR");
        }

        @Test
        @DisplayName("the account group is absent when the flag says it was not placed, even though a "
                + "row was resolved")
        void theAccountGroupIsAbsentWhenTheFlagSaysSo() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(header(), account(), customer(), false, true,
                            AccountViewService.Presentation.MAP));

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.accountStatus()).isNull();
            assertThat(body.creditLimit()).isNull();
            assertThat(body.currentBalance()).isNull();
            assertThat(body.accountGroupId()).isNull();
            // The search field is echoed regardless: it is the operator's own entry, not stored content.
            assertThat(body.accountId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the account group is absent when no row was resolved, even though the flag is set")
        void theAccountGroupIsAbsentWhenNoRowWasResolved() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(header(), null, customer(), true, true,
                            AccountViewService.Presentation.MAP));

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.accountStatus()).isNull();
            assertThat(body.creditLimit()).isNull();
        }

        @Test
        @DisplayName("the unregulated customer items are carried across when the group was placed")
        void theUnregulatedCustomerItemsAreCarriedAcross() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.customerId()).isEqualTo("000000011");
            assertThat(body.ficoScore()).isEqualTo("742");
            assertThat(body.firstName()).isEqualTo("MARY ANN");
            assertThat(body.middleName()).isEqualTo("Q");
            assertThat(body.lastName()).isEqualTo("Aniya Von");
            assertThat(body.addressLine1()).isEqualTo("1500 Woodward Avenue");
            assertThat(body.addressLine2()).isEqualTo("Apt. 4B");
            assertThat(body.stateCode()).isEqualTo("MI");
            assertThat(body.countryCode()).isEqualTo("USA");
            assertThat(body.primaryCardHolderIndicator()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the city item is populated from the third address line, which is the pairing the "
                + "transaction is the authority for")
        void theCityItemIsPopulatedFromTheThirdAddressLine() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.city()).isEqualTo("Detroit");
        }

        @Test
        @DisplayName("the whole customer group is absent when it was not placed on the map")
        void theWholeCustomerGroupIsAbsentWhenNotPresented() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(header(), account(), customer(), true, false,
                            AccountViewService.Presentation.MAP));

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.customerId()).isNull();
            assertThat(body.firstName()).isNull();
            assertThat(body.city()).isNull();
            assertThat(body.zipCode()).isNull();
            assertThat(body.phoneNumber1()).isNull();
        }
    }

    @Nested
    @DisplayName("The regulated components, which this boundary reveals only to the authority the "
            + "established identity carries")
    class RegulatedComponents {

        @Test
        @DisplayName("the birth date, the government-issued identifier and the funds identifier arrive "
                + "masked for an ordinary signed-on caller")
        void theRegulatedComponentsArriveMasked() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.dateOfBirth()).isEqualTo("*".repeat(BIRTH_DATE.length()));
            assertThat(body.eftAccountId()).isEqualTo("*".repeat(EFT_ACCOUNT_ID.length()));
            assertThat(body.dateOfBirth()).doesNotContain("1984");
            assertThat(body.eftAccountId()).isNotEqualTo(EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("and arrive revealed for an administrative identity, because the view screen and the "
                + "update screen are the same regulated data behind the same policy")
        void theRegulatedComponentsArriveRevealedForAnAdministrator() {
            // The view route used to present a CONSTANT unprivileged authority regardless of who asked,
            // so an administrator was masked here and revealed on the update route. Both routes now read
            // one derivation of the authority; this is the assertion that fails if the constant returns.
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.ADMIN)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.dateOfBirth())
                    .as("the stored value, not a stand-in of the same width")
                    .isEqualTo(BIRTH_DATE)
                    .isNotEqualTo("*".repeat(BIRTH_DATE.length()));
            assertThat(body.eftAccountId())
                    .isEqualTo(EFT_ACCOUNT_ID)
                    .isNotEqualTo("*".repeat(EFT_ACCOUNT_ID.length()));
        }

        @Test
        @DisplayName("an absent identity reveals nothing, so a route the chain did not authenticate "
                + "cannot become the way the values are read")
        void anAbsentIdentityRevealsNothing() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.dateOfBirth()).isEqualTo("*".repeat(BIRTH_DATE.length()));
            assertThat(body.eftAccountId()).isEqualTo("*".repeat(EFT_ACCOUNT_ID.length()));
        }

        @Test
        @DisplayName("a regulated column that stores nothing arrives absent rather than masked, so an "
                + "absence is not dressed up as a withheld value")
        void anAbsentRegulatedColumnArrivesAbsent() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.ssn()).isNull();
            assertThat(body.governmentIssuedId()).isNull();
        }

        @Test
        @DisplayName("a turn that resolved no customer answers all four regulated components absent, "
                + "so a mask never implies a record exists")
        void aTurnWithNoCustomerAnswersAllFourAbsent() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(header(), account(), null, true, true,
                            AccountViewService.Presentation.MAP));

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.ssn()).isNull();
            assertThat(body.dateOfBirth()).isNull();
            assertThat(body.governmentIssuedId()).isNull();
            assertThat(body.eftAccountId()).isNull();
        }
    }

    @Nested
    @DisplayName("Published widths - two stored values are cut back to the map's own width")
    class PublishedWidths {

        @Test
        @DisplayName("a stored postcode longer than the published five characters is cut back")
        void aLongPostcodeIsCutBack() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.zipCode()).isEqualTo("48226").hasSize(5);
        }

        @Test
        @DisplayName("a stored telephone number longer than the published thirteen characters is cut "
                + "back, and one already inside the width is carried whole")
        void telephoneNumbersAreCutBackOnlyWhenTooLong() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.phoneNumber1()).isEqualTo("(313)555-0100").hasSize(13);
            assertThat(body.phoneNumber2()).isEqualTo(STORED_PHONE_2);
        }

        @Test
        @DisplayName("an absent value is left absent rather than becoming an empty field")
        void anAbsentValueIsLeftAbsent() {
            Customer withoutContact = new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                    "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                    null, null, null, null, null, BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(header(), account(), withoutContact, true, true,
                            AccountViewService.Presentation.MAP));

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.zipCode()).isNull();
            assertThat(body.phoneNumber1()).isNull();
            assertThat(body.phoneNumber2()).isNull();
        }
    }

    @Nested
    @DisplayName("The message and control group, and the view turn's timing")
    class MessagesAndTiming {

        @Test
        @DisplayName("both messages, the error switch, the focus field and the route are carried across")
        void theControlGroupIsCarriedAcross() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            ResponseEntity<AccountViewResponse> answer =
                    controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            AccountViewResponse body = answer.getBody();
            assertThat(body).isNotNull();
            assertThat(body.infoMessage()).isEqualTo("INFO TEXT");
            assertThat(body.errorMessage()).isEqualTo("ERROR TEXT");
            assertThat(body.inputError()).isTrue();
            assertThat(body.focusScreenFieldId()).isEqualTo("ACCTSID");
            assertThat(body.nextRoute()).isEqualTo("account-view");
            // Empty in every member the turn produced, and carrying the authenticated identity in the two
            // it does not produce: the record the response echoes names whoever the credential named.
            assertThat(body.navigationContext())
                    .isEqualTo(new NavigationContext(null, null, null, null, "TESTUSR1",
                            UserType.USER.getCode(), null, null, null, null, null, null, null, null,
                            null, null));
        }

        @Test
        @DisplayName("the view turn is timed under the arm the transaction took, so the presentation "
                + "mix is readable without reading bodies")
        void theViewTurnIsTimedUnderItsPresentationArm() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            controller.viewAccount(ACCOUNT_ID, null, null, identityOf(UserType.USER));

            assertThat(viewTimed(AccountViewService.Presentation.MAP.name())).isEqualTo(1L);
        }

        @Test
        @DisplayName("a different presentation arm accumulates in its own series")
        void aDifferentPresentationArmIsItsOwnSeries() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(null, null, null, false, false,
                            AccountViewService.Presentation.TRANSFER));

            controller.viewAccount(ACCOUNT_ID, "DFHPF03", null, identityOf(UserType.USER));

            assertThat(viewTimed(AccountViewService.Presentation.TRANSFER.name()))
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The update route")
    class UpdateRoute {

        @Test
        @DisplayName("the submitted record and the transmitted key are handed to the update screen and "
                + "its body is answered unedited")
        void theUpdateRouteDelegatesAndAnswersTheComposedBody() {
            AccountUpdateRequest submitted = new AccountUpdateRequest(ACCOUNT_ID, "Y", "2020", "01",
                    "15", "5000.00", "2029", "12", "31", "1000.00", "2021", "06", "30", "250.00",
                    "10.00", "GRP000001A", "5.00", "000000456", "123", "45", "6789", "1984", "07",
                    "22", "750", "ANN", "B", "SMITH", "1 MAIN ST", "MI", "SUITE 2", "48226",
                    "DETROIT", "USA", "248", "555", "0188", "GOVT-ID-000000000001", "313", "555",
                    "0199", "4471902856", "Y", KeyAction.ENTER, NavigationContext.empty(),
                    "sealed-proof-as-presented");
            AccountUpdateOutcome composed = updateResponse(false, "account-update", List.of());
            when(accountUpdateService.handle(any(), eq("DFHENTER"))).thenReturn(composed);

            ResponseEntity<AccountUpdateResponse> answer =
                    controller.updateAccount(submitted, "DFHENTER", identityOf(UserType.ADMIN));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            // Equality across the whole conversion rather than instance identity, which is the stronger
            // claim now that a crossing exists: identity would survive a conversion that dropped a
            // component, whereas this compares all fifty-seven.
            assertThat(answer.getBody())
                    .isEqualTo(accountUpdateContractAdapter.toResponse(composed,
                            identityOf(UserType.ADMIN)));
            // And the command the screen received is the submitted record component for component.
            ArgumentCaptor<AccountUpdateCommand> captor =
                    ArgumentCaptor.forClass(AccountUpdateCommand.class);
            verify(accountUpdateService).handle(captor.capture(), eq("DFHENTER"));
            assertThat(captor.getValue())
                    .isEqualTo(accountUpdateContractAdapter.toCommand(submitted,
                            identityOf(UserType.ADMIN)));
        }

        @Test
        @DisplayName("an accepted turn is timed as accepted")
        void anAcceptedTurnIsTimedAsAccepted() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponse(false, "account-update", List.of()));

            controller.updateAccount(mock(AccountUpdateRequest.class), null,
                    identityOf(UserType.USER));

            assertThat(updateTimed("accepted")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a turn that raised the input-error switch is timed as rejected")
        void aRejectedTurnIsTimedAsRejected() {
            when(accountUpdateService.handle(any(), any())).thenReturn(updateResponse(true,
                    "account-update", List.of(new ValidationException.FieldError("stateCode",
                            "ACSSTTE", ValidationException.FieldState.INVALID, "not valid"))));

            controller.updateAccount(mock(AccountUpdateRequest.class), "DFHENTER",
                    identityOf(UserType.USER));

            assertThat(updateTimed("rejected")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The update screen's regulated components, gated by the caller's authority")
    class UpdateRegulatedComponents {

        @Test
        @DisplayName("an ordinary signed-on caller receives masks, because the account identifier is a "
                + "request field and any signed-on caller may name any account")
        void anOrdinaryCallerReceivesMasks() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponseWithRegulatedValues());

            AccountUpdateResponse body = controller.updateAccount(mock(AccountUpdateRequest.class),
                    "DFHENTER", identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.ssnPart1()).isEqualTo("*".repeat(CLEAR_SSN_PART_1.length()));
            assertThat(body.ssnPart2()).isEqualTo("*".repeat(CLEAR_SSN_PART_2.length()));
            assertThat(body.dateOfBirthYear()).isEqualTo("*".repeat(CLEAR_DOB_YEAR.length()));
            assertThat(body.dateOfBirthMonth()).isEqualTo("*".repeat(CLEAR_DOB_MONTH.length()));
            assertThat(body.dateOfBirthDay()).isEqualTo("*".repeat(CLEAR_DOB_DAY.length()));
            assertThat(body.governmentIssuedId())
                    .isEqualTo("*".repeat(CLEAR_GOVT_ISSUED_ID.length()));
            assertThat(body.eftAccountId()).isEqualTo("*".repeat(EFT_ACCOUNT_ID.length()));
        }

        @Test
        @DisplayName("no masked screen carries any character of the withheld values, which is what a "
                + "reviewer has to be able to check by reading one assertion")
        void noMaskedScreenCarriesTheWithheldValues() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponseWithRegulatedValues());

            AccountUpdateResponse body = controller.updateAccount(mock(AccountUpdateRequest.class),
                    "DFHENTER", identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.governmentIssuedId()).doesNotContain("88231947");
            assertThat(body.dateOfBirthYear()).doesNotContain(CLEAR_DOB_YEAR);
            assertThat(body.eftAccountId()).isNotEqualTo(EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the final four national-identifier digits are retained, so an operator can confirm "
                + "an identity without seeing it - the same asymmetry the view screen takes")
        void theFinalFourNationalIdentifierDigitsAreRetained() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponseWithRegulatedValues());

            AccountUpdateResponse body = controller.updateAccount(mock(AccountUpdateRequest.class),
                    "DFHENTER", identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.ssnPart3()).isEqualTo(CLEAR_SSN_PART_3);
        }

        @Test
        @DisplayName("an administrator receives the values revealed, because the estate's own split is "
                + "what decides who may see them")
        void anAdministratorReceivesTheValuesRevealed() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponseWithRegulatedValues());

            AccountUpdateResponse body = controller.updateAccount(mock(AccountUpdateRequest.class),
                    "DFHENTER", identityOf(UserType.ADMIN)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.ssnPart1()).isEqualTo(CLEAR_SSN_PART_1);
            assertThat(body.ssnPart2()).isEqualTo(CLEAR_SSN_PART_2);
            assertThat(body.ssnPart3()).isEqualTo(CLEAR_SSN_PART_3);
            assertThat(body.dateOfBirthYear()).isEqualTo(CLEAR_DOB_YEAR);
            assertThat(body.dateOfBirthMonth()).isEqualTo(CLEAR_DOB_MONTH);
            assertThat(body.dateOfBirthDay()).isEqualTo(CLEAR_DOB_DAY);
            assertThat(body.governmentIssuedId()).isEqualTo(CLEAR_GOVT_ISSUED_ID);
            assertThat(body.eftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("a caller carrying no recognised authority receives masks, so an unresolved type is "
                + "not treated as a type that may reveal")
        void aCallerCarryingNoRecognisedAuthorityReceivesMasks() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponseWithRegulatedValues());

            AccountUpdateResponse withoutIdentity = controller.updateAccount(
                    mock(AccountUpdateRequest.class), "DFHENTER", null).getBody();
            AccountUpdateResponse withForeignAuthority = controller.updateAccount(
                    mock(AccountUpdateRequest.class), "DFHENTER",
                    new TestingAuthenticationToken("TESTUSR1", null,
                            List.of(new SimpleGrantedAuthority("ROLE_SOMETHING_ELSE")))).getBody();

            assertThat(withoutIdentity).isNotNull();
            assertThat(withForeignAuthority).isNotNull();
            assertThat(withoutIdentity.governmentIssuedId())
                    .isEqualTo("*".repeat(CLEAR_GOVT_ISSUED_ID.length()));
            assertThat(withForeignAuthority.governmentIssuedId())
                    .isEqualTo("*".repeat(CLEAR_GOVT_ISSUED_ID.length()));
        }

        @Test
        @DisplayName("every component that is not regulated crosses unchanged, so the gate replaces "
                + "eight values and edits nothing else")
        void everyOtherComponentCrossesUnchanged() {
            AccountUpdateOutcome composed = updateResponseWithRegulatedValues();
            when(accountUpdateService.handle(any(), any())).thenReturn(composed);

            AccountUpdateResponse body = controller.updateAccount(mock(AccountUpdateRequest.class),
                    "DFHENTER", identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            final AccountUpdateResponse expected =
                    accountUpdateContractAdapter.toResponse(composed, identityOf(UserType.USER));
            assertThat(body.error()).isEqualTo(expected.error());
            assertThat(body.errorMessage()).isEqualTo(expected.errorMessage());
            assertThat(body.focusScreenFieldId()).isEqualTo(expected.focusScreenFieldId());
            assertThat(body.nextRoute()).isEqualTo(expected.nextRoute());
            assertThat(body.navigationContext()).isEqualTo(expected.navigationContext());
            assertThat(body.fieldErrors()).isEqualTo(expected.fieldErrors());
            assertThat(body.concurrencyToken()).isEqualTo(expected.concurrencyToken());
        }

        @Test
        @DisplayName("an absent regulated component stays absent rather than becoming a mask, so an "
                + "absence is not dressed up as a withheld value")
        void anAbsentRegulatedComponentStaysAbsent() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponse(false, "account-update", List.of()));

            AccountUpdateResponse body = controller.updateAccount(mock(AccountUpdateRequest.class),
                    "DFHENTER", identityOf(UserType.USER)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.ssnPart1()).isNull();
            assertThat(body.governmentIssuedId()).isNull();
            assertThat(body.eftAccountId()).isNull();
        }
    }

    @Nested
    @DisplayName("The published paths")
    class PublishedPaths {

        @Test
        @DisplayName("the two subpaths hang off one account prefix, and the composed constants agree "
                + "with the parts they are built from")
        void theTwoSubpathsHangOffOneAccountPrefix() {
            assertThat(AccountController.ACCOUNTS_PATH).isEqualTo("/api/accounts");
            assertThat(AccountController.VIEW_SUBPATH).isEqualTo("/view");
            assertThat(AccountController.UPDATE_SUBPATH).isEqualTo("/update");
            assertThat(AccountController.ACCOUNT_VIEW_PATH)
                    .isEqualTo(AccountController.ACCOUNTS_PATH + AccountController.VIEW_SUBPATH);
            assertThat(AccountController.ACCOUNT_UPDATE_PATH)
                    .isEqualTo(AccountController.ACCOUNTS_PATH + AccountController.UPDATE_SUBPATH);
            assertThat(AccountController.ACCOUNT_ID_PARAM).isEqualTo("accountId");
            assertThat(AccountController.ATTENTION_KEY_PARAM).isEqualTo("attentionKey");
        }
    }

    /**
     * The width of the one field the view screen lets an operator type into.
     *
     * <p>A 3270 field transmits at most the characters it declares, so a longer value has no counterpart
     * on the original screen. Driven through the servlet contract rather than by calling the method,
     * because the refusal is the framework's parameter validation and the translated body comes from the
     * shared advice - neither of which is exercised by a direct call.
     */
    @Nested
    @DisplayName("the account-identifier parameter is bounded by the screen field's own width")
    class AccountIdentifierParameterWidth {

        private MockMvc mockMvc;

        @BeforeEach
        void standUpTheBoundary() {
            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("a value wider than the field is refused, and no turn is run for a narrowed key")
        void anOverLongIdentifierIsRefused() throws Exception {
            mockMvc.perform(post(AccountController.ACCOUNT_VIEW_PATH)
                            .principal(identityOf(UserType.ADMIN))
                            .param(AccountController.ACCOUNT_ID_PARAM, "000000000010000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName")
                            .value(AccountController.ACCOUNT_ID_PARAM))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("INVALID"));

            verify(accountViewService, never()).viewAccount(any(), any(), any());
        }

        @Test
        @DisplayName("a value at the field's own width is served, so the bound refuses nothing the "
                + "screen could carry")
        void anIdentifierAtTheFieldWidthIsServed() throws Exception {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            mockMvc.perform(post(AccountController.ACCOUNT_VIEW_PATH)
                            .principal(identityOf(UserType.ADMIN))
                            .param(AccountController.ACCOUNT_ID_PARAM, ACCOUNT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID));

            verify(accountViewService).viewAccount(any(), any(), any());
        }

        @Test
        @DisplayName("the declared bound is the account key's own width")
        void theDeclaredBoundIsTheKeyWidth() {
            assertThat(AccountController.ACCOUNT_ID_SCREEN_WIDTH).isEqualTo(ACCOUNT_ID.length());
        }
    }
}
