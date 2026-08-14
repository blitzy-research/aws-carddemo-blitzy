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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies that online error mapping sits outside the transaction proxy that owns repository writes.
 */
@DisplayName("OnlineTransactionBoundary - repository failure finishes before screen mapping")
class OnlineTransactionBoundaryTest {

    private static final Set<String> ACCOUNT_UPDATE_ENTRIES = Set.of("handle");
    private static final Set<String> CARD_UPDATE_ENTRIES = Set.of("processCardUpdate");
    private static final Set<String> USER_ENTRIES =
            Set.of("listUsers", "addUser", "updateUser", "deleteUser");
    private static final Set<String> TRANSACTION_ADD_ENTRIES = Set.of("processTransactionAdd");
    private static final Set<String> ACCOUNT_VIEW_ENTRIES = Set.of("viewAccount");
    private static final Set<String> CARD_LIST_ENTRIES = Set.of("processCardList");
    private static final Set<String> TRANSACTION_LIST_ENTRIES = Set.of("listTransactions");

    @Test
    @DisplayName("execute is a REQUIRES_NEW transaction on a separate non-final bean")
    void executeOwnsAnIndependentTransaction() throws NoSuchMethodException {
        final Method execute =
                OnlineTransactionBoundary.class.getMethod("execute", java.util.function.Supplier.class);
        final Transactional transactional = execute.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(Modifier.isFinal(OnlineTransactionBoundary.class.getModifiers())).isFalse();
    }

    @Test
    @DisplayName("the callback result and failure cross the boundary unchanged")
    void delegatesToTheCompleteRepositoryOperation() {
        final OnlineTransactionBoundary boundary = new OnlineTransactionBoundary();
        final IllegalStateException failure = new IllegalStateException("write failed");

        assertThat(boundary.execute(() -> "committed")).isEqualTo("committed");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.execute(() -> {
            throw failure;
        })).isSameAs(failure);
        assertThatNullPointerException()
                .isThrownBy(() -> boundary.execute(null))
                .withMessage("operation must not be null");
    }

    @Test
    @DisplayName("all affected screen entry points are outside transactional advice")
    void screenEntryPointsRemainOutsideTheDoomedTransaction() {
        final List<EntrySurface> surfaces = List.of(
                new EntrySurface(AccountUpdateService.class, ACCOUNT_UPDATE_ENTRIES),
                new EntrySurface(CardUpdateService.class, CARD_UPDATE_ENTRIES),
                new EntrySurface(UserManagementService.class, USER_ENTRIES),
                new EntrySurface(TransactionAddService.class, TRANSACTION_ADD_ENTRIES),
                new EntrySurface(AccountViewService.class, ACCOUNT_VIEW_ENTRIES),
                new EntrySurface(CardListService.class, CARD_LIST_ENTRIES),
                new EntrySurface(TransactionListService.class, TRANSACTION_LIST_ENTRIES));

        for (final EntrySurface surface : surfaces) {
            final List<Method> entries = Arrays.stream(surface.type().getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> surface.entryNames().contains(method.getName()))
                    .toList();
            assertThat(entries)
                    .as("%s entry-point inventory", surface.type().getSimpleName())
                    .isNotEmpty()
                    .allSatisfy(method -> assertThat(method.getAnnotation(Transactional.class))
                            .as("%s.%s must map failures after the inner transaction",
                                    surface.type().getSimpleName(), method.getName())
                            .isNull());
            assertThat(Modifier.isFinal(surface.type().getModifiers()))
                    .as("%s no longer needs a CGLIB transaction proxy",
                            surface.type().getSimpleName())
                    .isTrue();
        }
    }

    private record EntrySurface(Class<?> type, Set<String> entryNames) {
    }
}
