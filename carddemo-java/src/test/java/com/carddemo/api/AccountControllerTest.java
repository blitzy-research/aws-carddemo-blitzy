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
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.Account;
import com.carddemo.domain.Customer;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * <p>The regulated components deserve the closest reading. This boundary holds no privileged purpose:
 * it presents the view screen to whichever operator is signed on, so it asks the adapter for an
 * unprivileged reveal and the adapter answers masked values. A change that quietly promoted that
 * authorization would still compile and would still pass every service test, so the masking is
 * asserted directly, and the absent-customer path is asserted to yield absent rather than masked
 * values because a mask would tell an operator a record exists.
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

    private AccountViewService accountViewService;

    private AccountUpdateService accountUpdateService;

    private AccountProtectedDataAdapter accountProtectedDataAdapter;

    private MeterRegistry meterRegistry;

    private AccountController controller;

    @BeforeEach
    void setUp() {
        accountViewService = mock(AccountViewService.class);
        accountUpdateService = mock(AccountUpdateService.class);
        accountProtectedDataAdapter =
                new AccountProtectedDataAdapter(new SensitiveFieldEncryptionService(FIXTURE_KEY));
        meterRegistry = new SimpleMeterRegistry();
        controller = new AccountController(accountViewService, accountUpdateService,
                accountProtectedDataAdapter, meterRegistry);
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
                "account-view", NavigationContext.empty(), header, presentation, account, customer,
                ACCOUNT_ID, "ERROR TEXT", "INFO TEXT", "ACCTSID", true, false,
                AccountViewService.FilterFlag.VALID, AccountViewService.FilterFlag.VALID,
                account != null, customer != null, accountPresented, customerPresented,
                false, false, false, "LONG TEXT");
    }

    /**
     * Builds an update response carrying only the three components this boundary reads.
     *
     * @param error whether the turn raised the screen's input-error switch
     * @param nextRoute the route the turn resolved
     * @param fieldErrors the field-level errors the turn composed
     * @return the response
     */
    private static AccountUpdateResponse updateResponse(final boolean error, final String nextRoute,
            final List<ErrorResponse.FieldError> fieldErrors) {
        return new AccountUpdateResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                error, "ACSTTUS", nextRoute, NavigationContext.empty(), fieldErrors, null);
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
                    accountUpdateService, accountProtectedDataAdapter, meterRegistry))
                    .withMessageContaining("accountViewService");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    null, accountProtectedDataAdapter, meterRegistry))
                    .withMessageContaining("accountUpdateService");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    accountUpdateService, null, meterRegistry))
                    .withMessageContaining("accountProtectedDataAdapter");
            assertThatNullPointerException().isThrownBy(() -> new AccountController(accountViewService,
                    accountUpdateService, accountProtectedDataAdapter, null))
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

            controller.viewAccount(ACCOUNT_ID, "DFHENTER", NavigationContext.empty());

            ArgumentCaptor<ScreenWorkArea> captor = ArgumentCaptor.forClass(ScreenWorkArea.class);
            verify(accountViewService).viewAccount(eq("DFHENTER"), captor.capture(), any());
            ScreenWorkArea assembled = captor.getValue();
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

            controller.viewAccount(null, null, null);

            ArgumentCaptor<ScreenWorkArea> captor = ArgumentCaptor.forClass(ScreenWorkArea.class);
            verify(accountViewService).viewAccount(isNull(), captor.capture(), isNull());
            assertThat(captor.getValue().accountId()).isNull();
        }

        @Test
        @DisplayName("the echoed navigation record is handed over whole")
        void theEchoedNavigationRecordIsHandedOverWhole() {
            NavigationContext echoed = NavigationContext.empty();
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            controller.viewAccount(ACCOUNT_ID, "DFHPF03", echoed);

            verify(accountViewService).viewAccount(eq("DFHPF03"), any(), eq(echoed));
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
                    controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, "DFHPF03", null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.accountStatus()).isNull();
            assertThat(body.creditLimit()).isNull();
        }

        @Test
        @DisplayName("the unregulated customer items are carried across when the group was placed")
        void theUnregulatedCustomerItemsAreCarriedAcross() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.city()).isEqualTo("Detroit");
        }

        @Test
        @DisplayName("the whole customer group is absent when it was not placed on the map")
        void theWholeCustomerGroupIsAbsentWhenNotPresented() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(header(), account(), customer(), true, false,
                            AccountViewService.Presentation.MAP));

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.customerId()).isNull();
            assertThat(body.firstName()).isNull();
            assertThat(body.city()).isNull();
            assertThat(body.zipCode()).isNull();
            assertThat(body.phoneNumber1()).isNull();
        }
    }

    @Nested
    @DisplayName("The regulated components, which this boundary is not licensed to reveal")
    class RegulatedComponents {

        @Test
        @DisplayName("the birth date, the government-issued identifier and the funds identifier arrive "
                + "masked, because the view screen holds no privileged purpose")
        void theRegulatedComponentsArriveMasked() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.dateOfBirth()).isEqualTo("*".repeat(BIRTH_DATE.length()));
            assertThat(body.eftAccountId()).isEqualTo("*".repeat(EFT_ACCOUNT_ID.length()));
            assertThat(body.dateOfBirth()).doesNotContain("1984");
            assertThat(body.eftAccountId()).isNotEqualTo(EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("a regulated column that stores nothing arrives absent rather than masked, so an "
                + "absence is not dressed up as a withheld value")
        void anAbsentRegulatedColumnArrivesAbsent() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.zipCode()).isEqualTo("48226").hasSize(5);
        }

        @Test
        @DisplayName("a stored telephone number longer than the published thirteen characters is cut "
                + "back, and one already inside the width is carried whole")
        void telephoneNumbersAreCutBackOnlyWhenTooLong() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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

            AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, null, null).getBody();

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
                    controller.viewAccount(ACCOUNT_ID, null, null);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            AccountViewResponse body = answer.getBody();
            assertThat(body).isNotNull();
            assertThat(body.infoMessage()).isEqualTo("INFO TEXT");
            assertThat(body.errorMessage()).isEqualTo("ERROR TEXT");
            assertThat(body.inputError()).isTrue();
            assertThat(body.focusScreenFieldId()).isEqualTo("ACCTSID");
            assertThat(body.nextRoute()).isEqualTo("account-view");
            assertThat(body.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("the view turn is timed under the arm the transaction took, so the presentation "
                + "mix is readable without reading bodies")
        void theViewTurnIsTimedUnderItsPresentationArm() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(presentedResult());

            controller.viewAccount(ACCOUNT_ID, null, null);

            assertThat(viewTimed(AccountViewService.Presentation.MAP.name())).isEqualTo(1L);
        }

        @Test
        @DisplayName("a different presentation arm accumulates in its own series")
        void aDifferentPresentationArmIsItsOwnSeries() {
            when(accountViewService.viewAccount(any(), any(), any())).thenReturn(
                    result(null, null, null, false, false,
                            AccountViewService.Presentation.TRANSFER));

            controller.viewAccount(ACCOUNT_ID, "DFHPF03", null);

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
            AccountUpdateRequest submitted = mock(AccountUpdateRequest.class);
            AccountUpdateResponse composed = updateResponse(false, "account-update", List.of());
            when(accountUpdateService.handle(submitted, "DFHENTER")).thenReturn(composed);

            ResponseEntity<AccountUpdateResponse> answer =
                    controller.updateAccount(submitted, "DFHENTER");

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).isSameAs(composed);
            verify(accountUpdateService).handle(submitted, "DFHENTER");
        }

        @Test
        @DisplayName("an accepted turn is timed as accepted")
        void anAcceptedTurnIsTimedAsAccepted() {
            when(accountUpdateService.handle(any(), any()))
                    .thenReturn(updateResponse(false, "account-update", List.of()));

            controller.updateAccount(mock(AccountUpdateRequest.class), null);

            assertThat(updateTimed("accepted")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a turn that raised the input-error switch is timed as rejected")
        void aRejectedTurnIsTimedAsRejected() {
            when(accountUpdateService.handle(any(), any())).thenReturn(updateResponse(true,
                    "account-update", List.of(new ErrorResponse.FieldError("stateCode", "ACSSTTE",
                            ErrorResponse.FieldState.INVALID, "not valid"))));

            controller.updateAccount(mock(AccountUpdateRequest.class), "DFHENTER");

            assertThat(updateTimed("rejected")).isEqualTo(1L);
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
}
