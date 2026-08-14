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

import com.carddemo.domain.enums.UserType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScreenNavigationState}, the sixteen-field communication area as a type the service
 * layer owns.
 *
 * <p><strong>What this file is really guarding.</strong> Three things. That the sixteen components stay
 * sixteen and stay in the copybook's declaration order, because a screen echoes the whole area and an
 * inserted or reordered component would silently move a value onto the wrong field. That an echoed identity
 * is treated as a claim and never as evidence - the agreement predicate and the reconciliation both exist
 * for that, and reconciliation must leave the other fourteen components untouched. And that the diagnostic
 * rendering withholds the customer identifier, the three name parts, the account identifier and the card
 * number, because this state is carried on every turn of every screen and a default record rendering would
 * put cardholder data one interpolation away from every log line.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * @since 1.0.0
 */
@DisplayName("ScreenNavigationState :: the whole communication area, owned by the service layer")
final class ScreenNavigationStateTest {

    /** The sixteen components, in copybook declaration order. */
    private static final List<String> COMPONENT_NAMES = List.of(
            "fromTransactionId", "fromProgram", "toTransactionId", "toProgram", "userId", "userType",
            "programContext", "customerId", "customerFirstName", "customerMiddleName",
            "customerLastName", "accountId", "accountStatus", "cardNumber", "lastMap", "lastMapset");

    /** A fully populated instance, so every component is observably carried. */
    private static ScreenNavigationState populated() {
        return new ScreenNavigationState("CT00", "COTRN00C", "CT01", "COTRN01C", "USER0001", "U",
                ScreenNavigationState.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                "00000000011", "Y", "4111111111111111", "COTRN0A", "COTRN00");
    }

    @Nested
    @DisplayName("The carried shape")
    class TheCarriedShape {

        @Test
        @DisplayName("is exactly the sixteen communication-area fields in declaration order, so no value "
                + "can land on the wrong field and no seventeenth can appear unnoticed")
        void isExactlyTheSixteenFieldsInOrder() {
            final List<String> declared = Arrays.stream(ScreenNavigationState.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENT_NAMES);
        }

        @Test
        @DisplayName("carries every value exactly as supplied, trimming, padding and defaulting nothing")
        void carriesEveryValueExactlyAsSupplied() {
            final ScreenNavigationState state = new ScreenNavigationState(" CT ", "COTRN00C ", null, "",
                    " USER1  ", " ", null, " 000000011", "  MARY", "", null, "00000000011 ", " ",
                    " 4111 ", "  ", "COTRN00");

            assertThat(state.fromTransactionId()).isEqualTo(" CT ");
            assertThat(state.fromProgram()).isEqualTo("COTRN00C ");
            assertThat(state.toTransactionId()).isNull();
            assertThat(state.toProgram()).isEmpty();
            assertThat(state.userId()).isEqualTo(" USER1  ");
            assertThat(state.userType()).isEqualTo(" ");
            assertThat(state.programContext()).isNull();
            assertThat(state.customerId()).isEqualTo(" 000000011");
            assertThat(state.customerFirstName()).isEqualTo("  MARY");
            assertThat(state.customerMiddleName()).isEmpty();
            assertThat(state.customerLastName()).isNull();
            assertThat(state.accountId()).isEqualTo("00000000011 ");
            assertThat(state.accountStatus()).isEqualTo(" ");
            assertThat(state.cardNumber()).isEqualTo(" 4111 ");
            assertThat(state.lastMap()).isEqualTo("  ");
            assertThat(state.lastMapset()).isEqualTo("COTRN00");
        }

        @Test
        @DisplayName("has an empty instance whose every component is absent, shared so an absent carry-over "
                + "costs no allocation")
        void hasAnAllAbsentEmptyInstance() {
            final ScreenNavigationState empty = ScreenNavigationState.empty();

            assertThat(ScreenNavigationState.empty()).isSameAs(empty);
            assertThat(empty.fromTransactionId()).isNull();
            assertThat(empty.fromProgram()).isNull();
            assertThat(empty.toTransactionId()).isNull();
            assertThat(empty.toProgram()).isNull();
            assertThat(empty.userId()).isNull();
            assertThat(empty.userType()).isNull();
            assertThat(empty.programContext()).isNull();
            assertThat(empty.customerId()).isNull();
            assertThat(empty.customerFirstName()).isNull();
            assertThat(empty.customerMiddleName()).isNull();
            assertThat(empty.customerLastName()).isNull();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.accountStatus()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.lastMap()).isNull();
            assertThat(empty.lastMapset()).isNull();
        }
    }

    @Nested
    @DisplayName("The echoed identity")
    class TheEchoedIdentity {

        @Test
        @DisplayName("resolves the one-character code onto the module's user type, and reports an absent or "
                + "unrecognised code as unresolved rather than guessing")
        void resolvesTheOneCharacterCode() {
            assertThat(populated().resolvedUserType()).contains(UserType.USER);
            assertThat(ScreenNavigationState.empty().resolvedUserType()).isEmpty();

            final ScreenNavigationState unknown = new ScreenNavigationState(null, null, null, null,
                    "USER0001", "Z", null, null, null, null, null, null, null, null, null, null);
            assertThat(unknown.resolvedUserType()).isEmpty();
            assertThat(unknown.echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("reports only what the client claimed about its role, never what it is entitled to")
        void reportsOnlyWhatTheClientClaimed() {
            final ScreenNavigationState claimsAdmin = new ScreenNavigationState(null, null, null, null,
                    "ADMIN001", UserType.ADMIN.getCode(), null, null, null, null, null, null, null, null,
                    null, null);

            assertThat(claimsAdmin.echoesAdministratorCode()).isTrue();
            assertThat(populated().echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("agrees with the authenticated principal only when identifier and type both match, so "
                + "a mismatch in either is a disagreement")
        void agreesOnlyWhenBothMatch() {
            final ScreenNavigationState state = populated();

            assertThat(state.agreesWith("USER0001", UserType.USER)).isTrue();
            assertThat(state.agreesWith("USER0002", UserType.USER)).isFalse();
            assertThat(state.agreesWith("USER0001", UserType.ADMIN)).isFalse();
            assertThat(state.agreesWith(null, UserType.USER)).isFalse();
            assertThat(ScreenNavigationState.empty().agreesWith(null, null)).isTrue();
            assertThat(ScreenNavigationState.empty().agreesWith("USER0001", null)).isFalse();
        }

        @Test
        @DisplayName("reconciles the identity onto the authenticated one and leaves the other fourteen "
                + "components byte for byte, because correcting identity is not licence to rewrite state")
        void reconcilesIdentityAndNothingElse() {
            final ScreenNavigationState reconciled =
                    populated().reconciledWith("ADMIN001", UserType.ADMIN);

            assertThat(reconciled.userId()).isEqualTo("ADMIN001");
            assertThat(reconciled.userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(reconciled.fromTransactionId()).isEqualTo("CT00");
            assertThat(reconciled.fromProgram()).isEqualTo("COTRN00C");
            assertThat(reconciled.toTransactionId()).isEqualTo("CT01");
            assertThat(reconciled.toProgram()).isEqualTo("COTRN01C");
            assertThat(reconciled.programContext())
                    .isEqualTo(ScreenNavigationState.ProgramContext.REENTER);
            assertThat(reconciled.customerId()).isEqualTo("000000011");
            assertThat(reconciled.customerFirstName()).isEqualTo("MARY");
            assertThat(reconciled.customerMiddleName()).isEqualTo("ANN");
            assertThat(reconciled.customerLastName()).isEqualTo("SMITH");
            assertThat(reconciled.accountId()).isEqualTo("00000000011");
            assertThat(reconciled.accountStatus()).isEqualTo("Y");
            assertThat(reconciled.cardNumber()).isEqualTo("4111111111111111");
            assertThat(reconciled.lastMap()).isEqualTo("COTRN0A");
            assertThat(reconciled.lastMapset()).isEqualTo("COTRN00");
        }

        @Test
        @DisplayName("clears the role byte rather than inventing one when the principal carries no role")
        void clearsTheRoleByteForAnAbsentPrincipalRole() {
            assertThat(populated().reconciledWith("ADMIN001", null).userType()).isNull();
        }
    }

    @Nested
    @DisplayName("The entry mode")
    class TheEntryMode {

        @Test
        @DisplayName("treats only the re-enter flag as a re-entry, so an absent flag is a first entry "
                + "exactly as the legacy single-digit field is")
        void treatsOnlyReenterAsAReEntry() {
            assertThat(populated().reEntry()).isTrue();
            assertThat(populated().firstEntry()).isFalse();
            assertThat(ScreenNavigationState.empty().reEntry()).isFalse();
            assertThat(ScreenNavigationState.empty().firstEntry()).isTrue();
            assertThat(populated().withFirstEntry().reEntry()).isFalse();
            assertThat(ScreenNavigationState.empty().withReEntry().reEntry()).isTrue();
        }

        @Test
        @DisplayName("changes nothing but the flag when it is changed, so a mode switch cannot disturb the "
                + "carried selection")
        void changesNothingButTheFlag() {
            final ScreenNavigationState state = populated();
            final ScreenNavigationState first = state.withFirstEntry();

            assertThat(first.programContext()).isEqualTo(ScreenNavigationState.ProgramContext.ENTER);
            assertThat(first.withReEntry()).isEqualTo(state);
        }
    }

    @Nested
    @DisplayName("The narrowing onto the routing carrier")
    class TheNarrowingOntoTheRoutingCarrier {

        @Test
        @DisplayName("carries the four routing fields and the entry mode and drops the eleven echoed "
                + "members, which is what makes a routing decision unable to read a claim")
        void carriesRoutingAndDropsTheEchoedMembers() {
            final ConversationState narrowed = populated().toConversationState();

            assertThat(narrowed.fromTransactionId()).isEqualTo("CT00");
            assertThat(narrowed.fromProgram()).isEqualTo("COTRN00C");
            assertThat(narrowed.toTransactionId()).isEqualTo("CT01");
            assertThat(narrowed.toProgram()).isEqualTo("COTRN01C");
            assertThat(narrowed.entryMode()).isEqualTo(ConversationState.EntryMode.RE_ENTRY);
            assertThat(Arrays.stream(ConversationState.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .hasSize(5);
        }

        @Test
        @DisplayName("narrows an absent flag to a first entry, matching the legacy default")
        void narrowsAnAbsentFlagToAFirstEntry() {
            assertThat(ScreenNavigationState.empty().toConversationState())
                    .isEqualTo(ConversationState.empty());
        }
    }

    @Nested
    @DisplayName("The diagnostic rendering")
    class TheDiagnosticRendering {

        @Test
        @DisplayName("withholds the customer identifier, all three name parts, the account identifier and "
                + "the card number, and nothing else")
        void withholdsEveryCardholderBearingComponent() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("000000011")
                    .doesNotContain("MARY")
                    .doesNotContain("ANN")
                    .doesNotContain("SMITH")
                    .doesNotContain("00000000011")
                    .doesNotContain("4111111111111111");
            assertThat(rendered)
                    .contains("ScreenNavigationState[")
                    .contains("fromTransactionId=CT00")
                    .contains("fromProgram=COTRN00C")
                    .contains("toTransactionId=CT01")
                    .contains("toProgram=COTRN01C")
                    .contains("userId=USER0001")
                    .contains("userType=U")
                    .contains("programContext=REENTER")
                    .contains("accountStatus=Y")
                    .contains("lastMap=COTRN0A")
                    .contains("lastMapset=COTRN00");
        }

        @Test
        @DisplayName("keeps equality and hashing over every component, so withholding a value from a log "
                + "does not withhold it from comparison")
        void keepsEqualityOverEveryComponent() {
            assertThat(populated()).isEqualTo(populated());
            assertThat(populated()).hasSameHashCodeAs(populated());
            assertThat(populated().reconciledWith("OTHER001", UserType.ADMIN))
                    .isNotEqualTo(populated());
        }
    }
}
