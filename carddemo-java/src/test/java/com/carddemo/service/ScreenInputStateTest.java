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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.domain.enums.KeyAction;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScreenInputState}, the screen work area as a type the service layer owns.
 *
 * <p><strong>What this file is really guarding.</strong> That the nine live members of
 * {@code CC-WORK-AREAS} stay nine and stay in order; that the three numeric views are derived rather than
 * stored, so the textual and numeric readings of one storage area can never disagree; that a partially
 * filled, blank or non-digit identifier reads as absent rather than as a parse failure, because all three
 * are initialised to spaces in the legacy structure; that the attention key has no catch-all, matching a
 * legacy mapping with twenty-eight branches and no otherwise arm; and that the diagnostic rendering
 * withholds the three business keys and both operator message slots.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * <p>Provenance: {@code app/cpy/CVCRD01Y.cpy} and {@code app/cpy/CSSTRPFY.cpy}, read as read-only
 * reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("ScreenInputState :: the screen work area, owned by the service layer")
final class ScreenInputStateTest {

    /** The nine components, in copybook declaration order. */
    private static final List<String> COMPONENT_NAMES = List.of(
            "keyAction", "nextProgram", "nextMapset", "nextMap", "errorMessage", "returnMessage",
            "accountId", "cardNumber", "customerId");

    /** A fully populated instance, so every component is observably carried. */
    private static ScreenInputState populated() {
        return new ScreenInputState(KeyAction.PFK03, "COCRDLIC", "COCRDLI", "CCRDLIA",
                "Account 00000000011 not found", "Thank you for using CardDemo", "00000000011",
                "4111111111111111", "000000011");
    }

    @Nested
    @DisplayName("The carried shape")
    class TheCarriedShape {

        @Test
        @DisplayName("is exactly the nine live members of the work area, in declaration order")
        void isExactlyTheNineMembersInOrder() {
            final List<String> declared = Arrays.stream(ScreenInputState.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENT_NAMES);
        }

        @Test
        @DisplayName("publishes the nine legacy widths, so a service can reason about them without "
                + "restating a figure")
        void publishesTheNineLegacyWidths() {
            assertThat(ScreenInputState.ATTENTION_ID_LENGTH).isEqualTo(5);
            assertThat(ScreenInputState.NEXT_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(ScreenInputState.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(ScreenInputState.NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(ScreenInputState.ERROR_MESSAGE_LENGTH).isEqualTo(75);
            assertThat(ScreenInputState.RETURN_MESSAGE_LENGTH).isEqualTo(75);
            assertThat(ScreenInputState.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(ScreenInputState.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(ScreenInputState.CUSTOMER_ID_LENGTH).isEqualTo(9);
        }

        @Test
        @DisplayName("stores every value exactly as supplied, including blank padding, because two of the "
                + "members are operator-facing message slots")
        void storesEveryValueExactlyAsSupplied() {
            final ScreenInputState state = new ScreenInputState(null, " COCRDLIC ", "", null,
                    "  padded error  ", " padded return ", " 11 ", null, "");

            assertThat(state.keyAction()).isNull();
            assertThat(state.nextProgram()).isEqualTo(" COCRDLIC ");
            assertThat(state.nextMapset()).isEmpty();
            assertThat(state.nextMap()).isNull();
            assertThat(state.errorMessage()).isEqualTo("  padded error  ");
            assertThat(state.returnMessage()).isEqualTo(" padded return ");
            assertThat(state.accountId()).isEqualTo(" 11 ");
            assertThat(state.cardNumber()).isNull();
            assertThat(state.customerId()).isEmpty();
        }

        @Test
        @DisplayName("has a shared empty instance whose every component is absent, for a turn that carries "
                + "no screen input at all")
        void hasAnAllAbsentEmptyInstance() {
            final ScreenInputState empty = ScreenInputState.empty();

            assertThat(ScreenInputState.empty()).isSameAs(empty);
            assertThat(empty.attentionKey()).isEmpty();
            assertThat(empty.attentionIdText()).isEmpty();
            assertThat(empty.nextProgram()).isNull();
            assertThat(empty.nextMapset()).isNull();
            assertThat(empty.nextMap()).isNull();
            assertThat(empty.errorMessage()).isNull();
            assertThat(empty.returnMessage()).isNull();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.customerId()).isNull();
        }
    }

    @Nested
    @DisplayName("The attention key")
    class TheAttentionKey {

        @Test
        @DisplayName("answers the resolved key and its padded five-character identifier verbatim, because "
                + "the padding is real data at real positions")
        void answersTheResolvedKeyAndItsPaddedIdentifier() {
            assertThat(populated().attentionKey()).contains(KeyAction.PFK03);
            assertThat(populated().attentionIdText()).contains(KeyAction.PFK03.getAid());
            assertThat(populated().attentionIdText().orElseThrow())
                    .hasSize(ScreenInputState.ATTENTION_ID_LENGTH);
        }

        @Test
        @DisplayName("reports an unresolved identifier as absent rather than substituting a placeholder, "
                + "because the legacy mapping has no otherwise branch")
        void reportsAnUnresolvedIdentifierAsAbsent() {
            final ScreenInputState none = new ScreenInputState(null, null, null, null, null, null, null,
                    null, null);

            assertThat(none.attentionKey()).isEmpty();
            assertThat(none.attentionIdText()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The three derived numeric views")
    class TheThreeDerivedNumericViews {

        @Test
        @DisplayName("read each whole identifier as an unsigned number, which is what the legacy numeric "
                + "redefinition over the same bytes reads")
        void readEachWholeIdentifier() {
            assertThat(populated().accountIdNumeric()).contains(new BigInteger("11"));
            assertThat(populated().cardNumberNumeric())
                    .contains(new BigInteger("4111111111111111"));
            assertThat(populated().customerIdNumeric()).contains(new BigInteger("11"));
        }

        @Test
        @DisplayName("report absent, empty, blank, space-padded and non-digit values as unreadable rather "
                + "than raising, because all three fields start as spaces in the legacy structure")
        void reportUnreadableValuesAsAbsent() {
            final ScreenInputState unreadable = new ScreenInputState(null, null, null, null, null, null,
                    "           ", "4111 1111", "");

            assertThat(unreadable.accountIdNumeric()).isEmpty();
            assertThat(unreadable.cardNumberNumeric()).isEmpty();
            assertThat(unreadable.customerIdNumeric()).isEmpty();
            assertThat(ScreenInputState.empty().accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("refuse a non-ASCII digit, because a single-byte fixed-width field cannot hold one "
                + "and the legacy alias would never have read it as a number")
        void refuseANonAsciiDigit() {
            final ScreenInputState arabicIndic = new ScreenInputState(null, null, null, null, null, null,
                    "\u0661\u0662\u0663", null, null);

            assertThat(arabicIndic.accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("keep leading zeros in the stored text while reading the value as a number, which is "
                + "the guarantee a redefinition gives")
        void keepLeadingZerosInTheStoredText() {
            final ScreenInputState zeroFilled = new ScreenInputState(null, null, null, null, null, null,
                    "00000000011", "0000000000000001", "000000011");

            assertThat(zeroFilled.accountId()).isEqualTo("00000000011");
            assertThat(zeroFilled.accountIdNumeric()).contains(BigInteger.valueOf(11L));
            assertThat(zeroFilled.cardNumberNumeric()).contains(BigInteger.ONE);
            assertThat(zeroFilled.customerIdNumeric()).contains(BigInteger.valueOf(11L));
        }
    }

    @Nested
    @DisplayName("The diagnostic rendering")
    class TheDiagnosticRendering {

        @Test
        @DisplayName("withholds the three business keys and both message slots, and keeps the routing "
                + "members, which are not identifiers")
        void withholdsTheKeysAndBothMessageSlots() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("00000000011")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("000000011")
                    .doesNotContain("not found")
                    .doesNotContain("Thank you");
            assertThat(rendered)
                    .contains("ScreenInputState[")
                    .contains("keyAction=PFK03")
                    .contains("nextProgram=COCRDLIC")
                    .contains("nextMapset=COCRDLI")
                    .contains("nextMap=CCRDLIA");
        }

        @Test
        @DisplayName("keeps equality and hashing over every component, so a withheld value is still "
                + "compared byte for byte")
        void keepsEqualityOverEveryComponent() {
            assertThat(populated()).isEqualTo(populated());
            assertThat(populated()).hasSameHashCodeAs(populated());
            assertThat(populated()).isNotEqualTo(ScreenInputState.empty());
        }
    }
}
