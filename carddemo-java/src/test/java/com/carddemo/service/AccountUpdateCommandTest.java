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

import com.carddemo.domain.enums.KeyAction;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service-owned account-update command: forty-seven components, no bounds, no disclosure.
 *
 * <p>What is asserted here is what the type owes. Its shape is a contract in its own right, because
 * {@code api.AccountUpdateContractAdapter} copies into it positionally across forty-six transmitted
 * components and derives the forty-seventh from the authenticated principal, and a component added or
 * reordered on one side alone is a silent mis-mapping. Its rendering is a disclosure
 * surface, because forty-three of the components are operator-typed account and customer values, four of
 * them regulated. Its equality is a parity instrument, because the account-update parity suite compares
 * whole commands.
 */
@DisplayName("AccountUpdateCommand :: the transmitted account-update screen the transaction reads")
class AccountUpdateCommandTest {

    /** A fully populated command whose every text component is a distinct marker. */
    private static AccountUpdateCommand populated() {
        return new AccountUpdateCommand("00000000011", "Y", "2020", "01", "15", "5000.00", "2029",
                "12", "31", "1000.00", "2021", "06", "30", "250.00", "10.00", "GRP000001A", "5.00",
                "000000456", "123", "45", "6789", "1984", "07", "22", "750", "ANN", "B", "SMITH",
                "1 MAIN ST", "MI", "SUITE 2", "48226", "DETROIT", "USA", "248", "555", "0188",
                "GOVT-ID-000000000001", "313", "555", "0199", "4471902856", "Y", KeyAction.ENTER,
                ScreenNavigationState.empty().withReEntry(), "sealed-proof-as-presented", false);
    }

    @Nested
    @DisplayName("the declared shape")
    final class TheDeclaredShape {

        @Test
        @DisplayName("declares exactly forty-seven components, because the adapter copies forty-six of "
                + "them positionally and derives the last from the caller's own authority")
        void declaresExactlyFortySevenComponents() {
            assertThat(AccountUpdateCommand.class.getRecordComponents()).hasSize(47);
        }

        @Test
        @DisplayName("declares the four non-map components last and in that order, the first three "
                + "matching the position their wire counterparts occupy and the fourth having none")
        void declaresTheFourNonMapComponentsLast() {
            final List<String> names = Arrays.stream(AccountUpdateCommand.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(names.subList(43, 47))
                    .containsExactly("keyAction", "navigationContext", "concurrencyToken",
                            "protectedValuesWithheld");
        }

        @Test
        @DisplayName("carries the echoed communication area as the service-owned state and the key as the "
                + "module's own enumeration, so no wire type reaches the service tier")
        void carriesOnlyServiceOwnedCollaboratorTypes() {
            final List<Class<?>> nonTextTypes =
                    Arrays.stream(AccountUpdateCommand.class.getRecordComponents())
                            .map(RecordComponent::getType)
                            .filter(type -> type != String.class)
                            .toList();

            assertThat(nonTextTypes)
                    .as("the withholding statement is a primitive, so it too carries no wire type")
                    .containsExactly(KeyAction.class, ScreenNavigationState.class, boolean.class);
        }

        @Test
        @DisplayName("every value component is text, because the ordered validation cascade is what reports "
                + "a malformed one and parsing here would pre-empt it")
        void everyValueComponentIsText() {
            final long textComponents = Arrays.stream(AccountUpdateCommand.class.getRecordComponents())
                    .filter(component -> component.getType() == String.class)
                    .count();

            assertThat(textComponents).isEqualTo(44);
        }
    }

    @Nested
    @DisplayName("what it carries")
    final class WhatItCarries {

        @Test
        @DisplayName("carries every value exactly as given, blanks and padding included")
        void carriesEveryValueExactlyAsGiven() {
            final AccountUpdateCommand command = new AccountUpdateCommand("  1  ", " ", null, "", "  ",
                    " 5000.00 ", null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, "  ANN  ", null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, false);

            assertThat(command.accountId()).isEqualTo("  1  ");
            assertThat(command.accountStatus()).isEqualTo(" ");
            assertThat(command.openMonth()).isEmpty();
            assertThat(command.openDay()).isEqualTo("  ");
            assertThat(command.creditLimit()).isEqualTo(" 5000.00 ");
            assertThat(command.firstName()).isEqualTo("  ANN  ");
            assertThat(command.openYear()).isNull();
        }

        @Test
        @DisplayName("accepts an absent key, an absent communication area and an absent token, each of "
                + "which is a reachable state rather than a caller defect")
        void acceptsTheThreeReachableAbsences() {
            final AccountUpdateCommand command = new AccountUpdateCommand(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, false);

            assertThat(command.keyAction()).isNull();
            assertThat(command.navigationContext()).isNull();
            assertThat(command.concurrencyToken()).isNull();
        }
    }

    @Nested
    @DisplayName("equality and rendering")
    final class EqualityAndRendering {

        @Test
        @DisplayName("compares by value across every component, which is what the parity suite needs")
        void comparesByValue() {
            assertThat(populated()).isEqualTo(populated())
                    .hasSameHashCodeAs(populated());
        }

        @Test
        @DisplayName("a difference in any single component breaks equality, so a dropped component cannot "
                + "pass a whole-command comparison")
        void aSingleDifferenceBreaksEquality() {
            final AccountUpdateCommand other = new AccountUpdateCommand("00000000011", "Y", "2020",
                    "01", "15", "5000.00", "2029", "12", "31", "1000.00", "2021", "06", "30",
                    "250.00", "10.00", "GRP000001A", "5.00", "000000456", "123", "45", "6789",
                    "1984", "07", "22", "750", "ANN", "B", "SMITH", "1 MAIN ST", "MI", "SUITE 2",
                    "48226", "DETROIT", "USA", "248", "555", "0188", "GOVT-ID-000000000001", "313",
                    "555", "0199", "4471902856", "N", KeyAction.ENTER,
                    ScreenNavigationState.empty().withReEntry(), "sealed-proof-as-presented", false);

            assertThat(other).isNotEqualTo(populated());
        }

        @Test
        @DisplayName("renders no component value at all, because forty-three are operator-typed values, "
                + "four are regulated and the token is integrity-protected")
        void rendersNoComponentValue() {
            final String rendered = populated().toString();

            assertThat(rendered).isEqualTo("AccountUpdateCommand[***REDACTED***]");
            assertThat(rendered).doesNotContain("00000000011", "SMITH", "6789", "48226",
                    "GOVT-ID-000000000001", "4471902856", "sealed-proof-as-presented");
        }
    }
}
