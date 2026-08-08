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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.BillPaymentRequest;
import com.carddemo.api.dto.CardListRequest;
import com.carddemo.api.dto.CardUpdateRequest;
import com.carddemo.api.dto.ReportRequest;
import com.carddemo.api.dto.TransactionAddRequest;
import com.carddemo.api.dto.TransactionListRequest;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.service.BillPaymentService;
import com.carddemo.service.CardConcurrencyTokenService;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.MenuService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import com.carddemo.service.UserManagementService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every custom controller timer to the same failure-path contract.
 *
 * <p>The framework request timer already sees failures, but these named migration timers are what the
 * dashboard and the gate evidence use to separate the legacy transactions. Omitting an unexpected
 * exception from them biases both latency and outcome series toward successful calls. Each assertion
 * below drives the service boundary to raise and proves the corresponding sample is still stopped under
 * a fixed, bounded failure label.
 */
@DisplayName("custom controller timers retain unexpected failures")
class ControllerTimerFailurePathTest {

    /**
     * The one non-production fixture key, declared identically by {@code application-local.yml} and both
     * {@code application-test.yml} files: Base64 of exactly thirty-two bytes.
     */
    private static final String FIELD_ENCRYPTION_KEY = "Y2FyZGRlbW8tbm9ucHJvZC1maXh0dXJlLWtleSEhISE=";

    @Test
    @DisplayName("account view and update failures are timed")
    void accountFailuresAreTimed() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final AccountViewService view = mock(AccountViewService.class);
        final AccountUpdateService update = mock(AccountUpdateService.class);
        final ScreenStateAdapter screenStateAdapter = new ScreenStateAdapter(new NavigationService());
        final AccountController controller = new AccountController(view, update,
                mock(AccountProtectedDataAdapter.class), screenStateAdapter,
                new AccountUpdateContractAdapter(screenStateAdapter), registry);
        final RuntimeException failure = failure();

        when(view.viewAccount(nullable(String.class), any(), any())).thenThrow(failure);
        when(update.handle(any(), nullable(String.class))).thenThrow(failure);

        assertThatThrownBy(() -> controller.viewAccount(null, null, null, null)).isSameAs(failure);
        assertThat(timer(registry, "carddemo.online.account.view.turn",
                "presentation", "UNRESOLVED", "outcome", "failed").count()).isEqualTo(1L);

        assertThatThrownBy(() -> controller.updateAccount(
                mock(AccountUpdateRequest.class), null, null))
                .isSameAs(failure);
        assertThat(timer(registry, "carddemo.online.account.update.turn",
                "outcome", "failed").count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("all four administrative user failures are timed")
    void administrativeUserFailuresAreTimed() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final UserManagementService service = mock(UserManagementService.class);
        final AdminUserController controller = new AdminUserController(service,
                new UserContractAdapter(new ScreenStateAdapter(new NavigationService())), registry);
        final UserRequest request = mock(UserRequest.class);
        final RuntimeException failure = failure();

        when(service.listUsers(any())).thenThrow(failure);
        when(service.addUser(any())).thenThrow(failure);
        when(service.updateUser(any())).thenThrow(failure);
        when(service.deleteUser(any())).thenThrow(failure);

        assertThatThrownBy(() -> controller.listUsers(request, null)).isSameAs(failure);
        assertThatThrownBy(() -> controller.addUser(request, null)).isSameAs(failure);
        assertThatThrownBy(() -> controller.updateUser(request, null)).isSameAs(failure);
        assertThatThrownBy(() -> controller.deleteUser(request, null)).isSameAs(failure);

        assertFailed(registry, "carddemo.online.userlist.turn");
        assertFailed(registry, "carddemo.online.useradd.turn");
        assertFailed(registry, "carddemo.online.userupdate.turn");
        assertFailed(registry, "carddemo.online.userdelete.turn");
    }

    @Test
    @DisplayName("a bill-payment failure is timed without inventing a confirmation outcome")
    void billPaymentFailureIsTimed() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final BillPaymentService service = mock(BillPaymentService.class);
        final BillPaymentController controller =
                new BillPaymentController(service, new ScreenStateAdapter(new NavigationService()), registry);
        final RuntimeException failure = failure();
        when(service.processBillPayment(any())).thenThrow(failure);

        assertThatThrownBy(() -> controller.payBill(mock(BillPaymentRequest.class), null))
                .isSameAs(failure);

        assertThat(timer(registry, "carddemo.online.billpayment.turn",
                "outcome", "failed",
                "confirmation", "UNRESOLVED",
                "paymentAccepted", "false").count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("card list, detail and update failures are timed")
    void cardFailuresAreTimed() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final CardListService list = mock(CardListService.class);
        final CardDetailService detail = mock(CardDetailService.class);
        final CardUpdateService update = mock(CardUpdateService.class);
        final RuntimeException failure = failure();

        when(list.processCardList(any())).thenThrow(failure);
        when(detail.processCardDetail(any())).thenThrow(failure);
        when(update.processCardUpdate(any())).thenThrow(failure);

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            final CardController controller = new CardController(list, detail, update,
                    new ScreenStateAdapter(new NavigationService()),
                    new CardConcurrencyTokenService(
                            new SensitiveFieldEncryptionService(FIELD_ENCRYPTION_KEY)),
                    validatorFactory.getValidator(), registry);

            assertThatThrownBy(() -> controller.listCards(mock(CardListRequest.class), null))
                    .isSameAs(failure);
            assertThatThrownBy(() -> controller.viewCardDetail(null, null, null, null, null))
                    .isSameAs(failure);
            // The update turn opens its conversation state first; an absent token is the genuine first
            // turn, so the stubbed screen is still reached and its failure still propagates.
            assertThatThrownBy(() -> controller.updateCard(mock(CardUpdateRequest.class), null))
                    .isSameAs(failure);
        }

        assertFailed(registry, "carddemo.online.cardlist.turn");
        assertFailed(registry, "carddemo.online.carddetail.turn");
        assertFailed(registry, "carddemo.online.cardupdate.turn");
    }

    @Test
    @DisplayName("both menu failures are timed under their fixed transaction identifiers")
    void menuFailuresAreTimed() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final MenuService service = mock(MenuService.class);
        final ConversationStateAdapter stateAdapter = new ConversationStateAdapter(new NavigationService());
        final MenuController controller = new MenuController(service, stateAdapter,
                mock(MenuResponseAdapter.class), registry);
        final RuntimeException failure = failure();

        when(service.userMenu(any(), isNull(), isNull(), isNull())).thenThrow(failure);
        when(service.adminMenu(any(), isNull(), isNull())).thenThrow(failure);

        assertThatThrownBy(() -> controller.userMenu(null, null, null, null)).isSameAs(failure);
        assertThatThrownBy(() -> controller.adminMenu(null, null, null, null)).isSameAs(failure);

        assertThat(timer(registry, "carddemo.online.menu.turn",
                "transaction", MenuService.USER_MENU_TRANSACTION_ID,
                "outcome", "FAILED").count()).isEqualTo(1L);
        assertThat(timer(registry, "carddemo.online.menu.turn",
                "transaction", MenuService.ADMIN_MENU_TRANSACTION_ID,
                "outcome", "FAILED").count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a report-request failure is timed with no invented period")
    void reportFailureIsTimed() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final ReportRequestService service = mock(ReportRequestService.class);
        final ReportController controller = new ReportController(service,
                new ReportContractAdapter(new ConversationStateAdapter(new NavigationService())), registry);
        final RuntimeException failure = failure();
        when(service.processReportRequest(any(), nullable(String.class), nullable(String.class))).thenThrow(failure);

        assertThatThrownBy(() -> controller.requestReport(mock(ReportRequest.class), null, null))
                .isSameAs(failure);

        assertThat(timer(registry, "carddemo.online.reportrequest.turn",
                "outcome", "failed", "period", "none").count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("transaction list, view and add failures are timed")
    void transactionFailuresAreTimed() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final TransactionListService list = mock(TransactionListService.class);
        final TransactionViewService view = mock(TransactionViewService.class);
        final TransactionAddService add = mock(TransactionAddService.class);
        final TransactionController controller =
                new TransactionController(list, view, add, new ScreenStateAdapter(new NavigationService()), registry);
        final RuntimeException failure = failure();

        when(list.listTransactions(any())).thenThrow(failure);
        when(view.viewTransaction(any())).thenThrow(failure);
        when(add.processTransactionAdd(any())).thenThrow(failure);

        assertThatThrownBy(() -> controller.listTransactions(
                mock(TransactionListRequest.class), null, 0, false, null)).isSameAs(failure);
        assertThatThrownBy(() -> controller.viewTransaction(null, null, null, null, null))
                .isSameAs(failure);
        assertThatThrownBy(() -> controller.addTransaction(
                mock(TransactionAddRequest.class), null, null)).isSameAs(failure);

        assertFailedWithNoRoute(registry, "carddemo.online.transaction.list.turn");
        assertFailedWithNoRoute(registry, "carddemo.online.transaction.view.turn");
        assertFailedWithNoRoute(registry, "carddemo.online.transaction.add.turn");
    }

    private static RuntimeException failure() {
        return new IllegalStateException("unexpected boundary failure");
    }

    private static void assertFailed(final MeterRegistry registry, final String metricName) {
        assertThat(timer(registry, metricName, "outcome", "failed").count()).isEqualTo(1L);
    }

    private static void assertFailedWithNoRoute(
            final MeterRegistry registry, final String metricName) {
        assertThat(timer(registry, metricName, "outcome", "failed", "route", "none").count())
                .isEqualTo(1L);
    }

    private static Timer timer(final MeterRegistry registry, final String metricName,
            final String... meterTags) {
        return registry.get(metricName).tags(meterTags).timer();
    }
}
