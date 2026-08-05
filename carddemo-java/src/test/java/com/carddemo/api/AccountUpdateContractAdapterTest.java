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
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.AccountUpdateCommand;
import com.carddemo.service.AccountUpdateOutcome;
import com.carddemo.service.ScreenNavigationState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The account-update boundary: forty-six components in, fifty-seven out, every one a positional copy.
 *
 * <p><strong>Why the comparison is name-matched rather than hand-listed.</strong> A hand-written assertion
 * per component would be a hundred and three assertions that a newly added component could slip past
 * unnoticed, which is the exact failure the twinning creates. Pairing the two records' components by name
 * and comparing each pair means a component added to one side and forgotten on the other is reported here,
 * and a component silently mapped from its neighbour is reported as a value mismatch.
 *
 * <p>Reflection is used for that pairing only. The module's unsafe-code audit commits to zero reflection in
 * {@code src/main/java/**} and scopes itself there deliberately; nothing under test uses any.
 *
 * <p>Provenance: {@code app/cbl/COACTUPC.cbl} and {@code app/cpy-bms/COACTUP.CPY}, read as read-only
 * reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}.
 */
@DisplayName("AccountUpdateContractAdapter :: the one crossing between the update wire contract and the "
        + "service-owned pair")
class AccountUpdateContractAdapterTest {

    /** A populated communication area, so the nested crossing is exercised rather than defaulted. */
    private static final NavigationContext ECHOED = new NavigationContext("CAUP", "COMEN01C", "CAUP",
            "COACTUPC", "USER0001", "U", NavigationContext.ProgramContext.REENTER, "000000456", "ANN",
            "B", "SMITH", "00000000011", "Y", "4111111111111111", "CACTUPA", "COACTUP");

    /** The adapter under test, over the real navigation seam it delegates to. */
    private AccountUpdateContractAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AccountUpdateContractAdapter(new ScreenStateAdapter());
    }

    /** A request whose every text component carries a distinct, padding-bearing marker. */
    private static AccountUpdateRequest request() {
        return new AccountUpdateRequest(" 0000000011", "Y", "2020", "01", "15", " 5000.00", "2029",
                "12", "31", "1000.00 ", "2021", "06", "30", "250.00", "10.00", "GRP000001A", "5.00",
                "000000456", "123", "45", "6789", "1984", "07", "22", "750", "ANN  ", " B ",
                "SMITH ", "1 MAIN ST", "MI", "SUITE 2", "48226", "DETROIT", "USA", "248", "555",
                "0188", "GOVT-ID-000000000001", "313", "555", "0199", "4471902856", "Y",
                KeyAction.PFK05, ECHOED, "sealed-proof-as-presented");
    }

    /** An outcome whose every text component carries a distinct, padding-bearing marker. */
    private static AccountUpdateOutcome outcome(final List<ValidationException.FieldError> findings) {
        final BigDecimal money = new BigDecimal("250.00");
        return new AccountUpdateOutcome("CAUP", "TITLE ONE ", "07/19/22", "COACTUPC", " TITLE TWO",
                "23:12:33", " 0000000011", "Y", "2020", "01", "15", money, "2029", "12", "31", money,
                "2021", "06", "30", money, money, "GRP000001A", money, "000000456", "123", "45",
                "6789", "1984", "07", "22", "750", "ANN  ", " B ", "SMITH ", "1 MAIN ST", "MI",
                "SUITE 2", "48226", "DETROIT", "USA", "248", "555", "0188",
                "GOVT-ID-000000000001", "313", "555", "0199", "4471902856", "Y",
                "Looks Good.... so far  ", "ERROR TEXT  ", true, "ACSTTUS", "account-update",
                ScreenNavigationState.empty().withReEntry(), findings, "sealed-proof-as-presented");
    }

    /** Reads a record component's value by name. */
    private static Object valueOf(final Object record, final String name) {
        try {
            final Method accessor = record.getClass().getMethod(name);
            return accessor.invoke(record);
        } catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException
                problem) {
            throw new AssertionError("component " + name + " is not readable on "
                    + record.getClass().getSimpleName(), problem);
        }
    }

    /**
     * Compares every component the two records share by name, and reports the two failure modes separately.
     *
     * @param left the record the value came from
     * @param right the record the value should have reached
     * @param excluded components whose types differ by design and are asserted individually elsewhere
     */
    private static void assertEveryNamedComponentAgrees(final Object left, final Object right,
            final List<String> excluded) {
        final List<String> rightNames = Arrays.stream(right.getClass().getRecordComponents())
                .map(RecordComponent::getName).toList();
        final List<String> unmatched = new ArrayList<>();
        final List<String> divergent = new ArrayList<>();
        int compared = 0;
        for (final RecordComponent component : left.getClass().getRecordComponents()) {
            final String name = component.getName();
            if (excluded.contains(name)) {
                continue;
            }
            if (!rightNames.contains(name)) {
                unmatched.add(name);
                continue;
            }
            compared++;
            final Object leftValue = valueOf(left, name);
            final Object rightValue = valueOf(right, name);
            if (leftValue == null ? rightValue != null : !leftValue.equals(rightValue)) {
                divergent.add(name + ": " + leftValue + " -> " + rightValue);
            }
        }

        assertThat(unmatched)
                .as("a component named on one side of the pair and not the other cannot be copied")
                .isEmpty();
        assertThat(divergent)
                .as("every shared component must cross unchanged")
                .isEmpty();
        assertThat(compared)
                .as("far too few components were compared for this assertion to mean anything")
                .isGreaterThanOrEqualTo(43);
    }

    @Nested
    @DisplayName("the adapter itself")
    final class TheAdapterItself {

        @Test
        @DisplayName("is final and refuses an absent navigation seam, so a half-built boundary cannot exist")
        void isFinalAndRefusesAnAbsentSeam() {
            assertThat(AccountUpdateContractAdapter.class).isFinal();
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountUpdateContractAdapter(null))
                    .withMessageContaining("screenStateAdapter");
        }

        @Test
        @DisplayName("refuses an absent request and an absent outcome, because neither is a reachable state "
                + "and converting nothing would answer a response the screen never composed")
        void refusesAnAbsentRequestOrOutcome() {
            assertThatNullPointerException().isThrownBy(() -> adapter.toCommand(null))
                    .withMessageContaining("request");
            assertThatNullPointerException().isThrownBy(() -> adapter.toResponse(null))
                    .withMessageContaining("outcome");
        }
    }

    @Nested
    @DisplayName("inbound - the transmitted screen becomes the command")
    final class Inbound {

        @Test
        @DisplayName("copies all forty-four shared components positionally, padding and blanks included")
        void copiesEverySharedComponent() {
            assertEveryNamedComponentAgrees(request(), adapter.toCommand(request()),
                    List.of("navigationContext"));
        }

        @Test
        @DisplayName("carries the echoed communication area across as the service-owned state, all sixteen "
                + "fields of it")
        void carriesTheCommunicationAreaAcross() {
            final ScreenNavigationState carried = adapter.toCommand(request()).navigationContext();

            assertThat(carried).isEqualTo(new ScreenNavigationState("CAUP", "COMEN01C", "CAUP",
                    "COACTUPC", "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER,
                    "000000456", "ANN", "B", "SMITH", "00000000011", "Y", "4111111111111111",
                    "CACTUPA", "COACTUP"));
            assertThat(carried.reEntry())
                    .as("the re-enter gate is what decides whether the screen decorates a field at all")
                    .isTrue();
        }

        @Test
        @DisplayName("an absent communication area becomes the empty carried state rather than nothing, "
                + "which is what this screen already treats as no carry-over")
        void anAbsentCommunicationAreaBecomesEmpty() {
            final AccountUpdateRequest withoutContext = new AccountUpdateRequest(null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(adapter.toCommand(withoutContext).navigationContext())
                    .isEqualTo(ScreenNavigationState.empty());
        }

        @Test
        @DisplayName("neither trims nor upper-folds nor defaults a value, because the ordered cascade "
                + "distinguishes blank from supplied-but-unusable")
        void altersNoValue() {
            final AccountUpdateCommand command = adapter.toCommand(request());

            assertThat(command.accountId()).isEqualTo(" 0000000011");
            assertThat(command.creditLimit()).isEqualTo(" 5000.00");
            assertThat(command.cashCreditLimit()).isEqualTo("1000.00 ");
            assertThat(command.firstName()).isEqualTo("ANN  ");
            assertThat(command.middleName()).isEqualTo(" B ");
            assertThat(command.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(command.concurrencyToken()).isEqualTo("sealed-proof-as-presented");
        }
    }

    @Nested
    @DisplayName("outbound - the settled turn becomes the response")
    final class Outbound {

        @Test
        @DisplayName("copies all fifty-four shared components positionally, padding included")
        void copiesEverySharedComponent() {
            assertEveryNamedComponentAgrees(outcome(List.of()), adapter.toResponse(outcome(List.of())),
                    List.of("navigationContext", "fieldErrors"));
        }

        @Test
        @DisplayName("carries the monetary components at the record field's own scale, unrounded")
        void carriesMoneyAtTheRecordScale() {
            final AccountUpdateResponse response = adapter.toResponse(outcome(List.of()));

            assertThat(response.creditLimit()).isEqualTo(new BigDecimal("250.00"));
            assertThat(response.creditLimit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("carries the communication area back as the wire record, and an absent one back as "
                + "nothing, so a response omits a member the turn never produced")
        void carriesTheCommunicationAreaBack() {
            assertThat(adapter.toResponse(outcome(List.of())).navigationContext())
                    .isEqualTo(new ScreenStateAdapter()
                            .toNavigationContext(ScreenNavigationState.empty().withReEntry()));
        }

        @Test
        @DisplayName("translates each finding entry for entry in cascade order, mapping the two states "
                + "onto the transport vocabulary and keeping the message verbatim")
        void translatesTheFindings() {
            final List<ValidationException.FieldError> findings = List.of(
                    new ValidationException.FieldError("accountStatus", "ACSTTUS",
                            ValidationException.FieldState.MISSING, "must be supplied"),
                    new ValidationException.FieldError("ficoScore", "ACSTFCO",
                            ValidationException.FieldState.INVALID,
                            AccountUpdateOutcome.SUFFIX_FICO_OUT_OF_RANGE));

            final List<ErrorResponse.FieldError> translated =
                    adapter.toResponse(outcome(findings)).fieldErrors();

            assertThat(translated).hasSize(2);
            assertThat(translated.get(0).fieldName()).isEqualTo("accountStatus");
            assertThat(translated.get(0).screenFieldId()).isEqualTo("ACSTTUS");
            assertThat(translated.get(0).state()).isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(translated.get(0).message()).isEqualTo("must be supplied");
            assertThat(translated.get(1).fieldName()).isEqualTo("ficoScore");
            assertThat(translated.get(1).state()).isEqualTo(ErrorResponse.FieldState.INVALID);
            assertThat(translated.get(1).message())
                    .isEqualTo(AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("substitutes the empty string for an absent field name or screen identifier, because "
                + "the transport entry refuses a null for either and an unlocatable entry is worse")
        void substitutesTheEmptyStringForAnAbsentIdentity() {
            final List<ErrorResponse.FieldError> translated = adapter.toResponse(outcome(List.of(
                    new ValidationException.FieldError(null, null,
                            ValidationException.FieldState.INVALID, null)))).fieldErrors();

            assertThat(translated).singleElement().satisfies(entry -> {
                assertThat(entry.fieldName()).isEmpty();
                assertThat(entry.screenFieldId()).isEmpty();
                assertThat(entry.message()).isNull();
            });
        }

        @Test
        @DisplayName("an absent finding list becomes an empty one, never a null on the wire")
        void anAbsentFindingListBecomesEmpty() {
            assertThat(adapter.toResponse(outcome(null)).fieldErrors()).isEmpty();
        }
    }
}
