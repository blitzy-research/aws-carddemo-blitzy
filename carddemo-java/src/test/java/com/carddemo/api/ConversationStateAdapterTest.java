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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ConversationState;
import com.carddemo.service.NavigationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link ConversationStateAdapter}, the one place the client-echoed navigation record
 * and the service-tier conversation state are converted into one another.
 *
 * <p><strong>This file carries the trust-boundary proof.</strong> The wire record
 * {@link NavigationContext} models all sixteen fields of {@code CARDDEMO-COMMAREA}
 * ({@code app/cpy/COCOM01Y.cpy}) because the communication area is an external contract. Eleven of
 * those sixteen are identity and cardholder members, and every one of them arrives having been echoed
 * by the client, so none is evidence of anything. Three separate properties therefore have to hold
 * simultaneously, and they pull against one another:
 *
 * <ol>
 *   <li><strong>Inbound, the eleven are dropped.</strong> A service is handed a five-field state, so it
 *       cannot read an echoed identity or cardholder value even by accident. Asserted by comparing the
 *       component sets of the two types and by driving a fully populated record through the conversion.
 *   <li><strong>Outbound, the eleven survive.</strong> The client's own view of its state must not be
 *       silently emptied, so the members the service never saw are carried through from the inbound
 *       record unchanged. This is the assertion relocated out of {@code MenuServiceTest}, where it used
 *       to be made against a service that copied the members itself.
 *   <li><strong>Outbound, identity is the server's and not the client's.</strong> The two identity
 *       members are overwritten with the authenticated principal, so a client-supplied identity cannot
 *       survive a turn and return looking as though the server had asserted it. This is what finally
 *       gives {@link NavigationContext#reconciledWith(String, UserType)} a production caller.
 * </ol>
 *
 * <p><strong>Where property one holds, and where the full service-owned carriage is required.</strong>
 * Property one holds without exception for the batch, repository, domain and utility tiers, and for
 * every service that needs nothing but routing. Ten screen services instead take
 * {@code ScreenNavigationState}, a service-owned carrier of all sixteen fields, because the CICS
 * programs they reproduce read carried communication-area members as their own input:
 * {@code COACTVWC} moves {@code CDEMO-ACCT-ID} into the read key at line 691 and
 * {@code CDEMO-CUST-ID} at line 708, and {@code COCRDSLC} moves {@code CDEMO-ACCT-ID} and
 * {@code CDEMO-CARD-NUM} into its work area at lines 342 and 343, tests them for zero at lines 462
 * and 468, and branches on {@code CDEMO-LAST-MAPSET} at lines 505 and 527. A five-field state cannot
 * reproduce those programs.
 *
 * <p>That is no longer a package-direction exception. No production source outside {@code api} and
 * {@code config} may name the wire {@code NavigationContext} at all; the ten services name only the
 * service-owned carrier, and
 * {@code NoProductionSourceAuthorizesFromAnEchoedValue#SCREEN_STATE_CONSUMER_FILE_NAMES} is a
 * trust-boundary census rather than a wire-record licence. The guards below fail for an eleventh
 * service-carrier consumer, for a stale census entry, for any wire-record name outside the two boundary
 * packages, and for any screen-state consumer that compares an echoed identity instead of merely
 * carrying it. The property that protects authorization is unconditional: no production source
 * anywhere decides a role from an echoed value.
 *
 * <p><strong>On the mismatch report.</strong>
 * {@link ConversationStateAdapter#echoedIdentityDisagrees(NavigationContext, String, UserType)} is
 * asserted to be a report and not a refusal: nothing throws, and reconciliation happens whether the
 * echo agreed or not. A stale echo is not an attack - a client may legitimately hold pre-authentication
 * state - and the legacy screens have no message for the condition, so inventing a rejection would be a
 * behaviour the estate does not have.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. The adapter holds one injected
 * collaborator - the navigation authority it screens an echoed program nomination against - and no per-turn
 * state, so it is constructed directly over the real authority.
 *
 * @since 1.0.0
 */
@DisplayName("ConversationStateAdapter :: the navigation-state trust boundary")
final class ConversationStateAdapterTest {

    /**
     * The eleven communication-area members the service tier never receives, named individually so a
     * failure names whichever one leaked or was dropped.
     */
    private static final List<String> ECHOED_MEMBER_NAMES = List.of(
            "userId", "userType", "customerId", "customerFirstName", "customerMiddleName",
            "customerLastName", "accountId", "accountStatus", "cardNumber", "lastMap", "lastMapset");

    /** The five members the service tier does receive, which the carried state models. */
    private static final List<String> CARRIED_MEMBER_NAMES = List.of(
            "fromTransactionId", "fromProgram", "toTransactionId", "toProgram", "entryMode");

    /** The identifier the client echoes, which is deliberately not the authenticated one. */
    private static final String ECHOED_USER_ID = "STALEUSR";

    /** The identifier of the authenticated principal. */
    private static final String AUTHENTICATED_USER_ID = "ADMIN001";

    /** Subject under test. */
    private ConversationStateAdapter subject;

    /** Constructs a fresh adapter for each test, since it holds no state to reset. */
    @BeforeEach
    void setUp() {
        subject = new ConversationStateAdapter(new NavigationService());
    }

    /**
     * Builds a wire record with every one of the sixteen members populated and standing at re-entry.
     *
     * <p>The identity members carry values that disagree with the authenticated principal on purpose:
     * that is the condition the reconciliation exists for, and a fixture that agreed would let a broken
     * reconciliation pass.
     *
     * @return a fully populated record
     */
    private static NavigationContext fullyEchoed() {
        return new NavigationContext("CB00", "COBIL00C", "CM00", "COMEN01C",
                ECHOED_USER_ID, UserType.USER.getCode(), NavigationContext.ProgramContext.REENTER,
                "000000123", "MARY", "ANN", "SMITH",
                "00000000011", "Y", "4111111111111111",
                "COBIL0A", "COBIL00");
    }

    // ----------------------------------------------------------------------------------------
    // Inbound: the eleven echoed members are dropped, not passed along
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("inbound, an echoed routing nomination is screened against the destination vocabulary")
    final class InboundTheNominationsAreScreened {

        @Test
        @DisplayName("a nomination the estate declares crosses unchanged, so every reachable legacy value "
                + "still reaches the navigation authority as the region would have supplied it")
        void aDeclaredNominationCrossesUnchanged() {
            final ConversationState carried = subject.toConversationState(fullyEchoed());

            assertThat(carried.fromProgram()).isEqualTo("COBIL00C");
            assertThat(carried.toProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("a nomination that names no destination is carried as nothing, so an invented program "
                + "name becomes the legacy's own empty-field case instead of a terminal failure on an "
                + "authenticated route")
        void anUnresolvableNominationIsCarriedAsNothing() {
            final NavigationContext invented = new NavigationContext("CB00", "NOTAPGM1", "CM00",
                    "ALSONOPE", ECHOED_USER_ID, UserType.USER.getCode(),
                    NavigationContext.ProgramContext.REENTER, null, null, null, null, null, null,
                    null, null, null);

            final ConversationState carried = subject.toConversationState(invented);

            assertThat(carried.fromProgram()).isNull();
            assertThat(carried.toProgram()).isNull();
            // The turn is still a turn: the routing members the authority does not resolve by name, and the
            // entry gate, both survive.
            assertThat(carried.fromTransactionId()).isEqualTo("CB00");
            assertThat(carried.toTransactionId()).isEqualTo("CM00");
            assertThat(carried.reEntry()).isTrue();
        }

        @Test
        @DisplayName("a screened nomination resolves to the calling screen's own default rather than "
                + "abending, which is what the blank field already means")
        void aScreenedNominationFallsBackToTheCallerDefault() {
            final NavigationContext invented = new NavigationContext(null, "NOTAPGM1", null,
                    "ALSONOPE", ECHOED_USER_ID, UserType.USER.getCode(),
                    NavigationContext.ProgramContext.REENTER, null, null, null, null, null, null,
                    null, null, null);
            final ConversationState carried = subject.toConversationState(invented);

            final NavigationService authority = new NavigationService();
            assertThat(authority.resolveBackNavigation(carried, NavigationService.Route.USER_MENU))
                    .isEqualTo(NavigationService.Route.USER_MENU);
            assertThat(authority.resolveNominatedDestination(carried,
                    NavigationService.Route.USER_MENU))
                    .isEqualTo(NavigationService.Route.USER_MENU);
        }

        @Test
        @DisplayName("a blank nomination is left exactly as received, because a blank field is significant: "
                + "it is what makes the calling screen's default apply")
        void aBlankNominationIsLeftAsReceived() {
            final NavigationContext blanked = new NavigationContext(null, "        ", null, "",
                    ECHOED_USER_ID, UserType.USER.getCode(),
                    NavigationContext.ProgramContext.ENTER, null, null, null, null, null, null, null,
                    null, null);

            final ConversationState carried = subject.toConversationState(blanked);

            assertThat(carried.fromProgram()).isEqualTo("        ");
            assertThat(carried.toProgram()).isEmpty();
        }
    }

    @Nested
    @DisplayName("inbound, the eleven echoed members are dropped rather than handed to a service")
    final class InboundTheEchoedMembersAreDropped {

        @Test
        @DisplayName("the state the service receives declares only the five routing and entry members, "
                + "so an echoed identity or cardholder value is not merely unread but unreachable")
        void theStateDeclaresOnlyTheFiveCarriedMembers() {
            final List<String> carried = Arrays.stream(
                            ConversationState.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            final List<String> onTheWire = Arrays.stream(
                            NavigationContext.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(onTheWire)
                    .as("the wire record reproduces the whole sixteen-field communication area")
                    .hasSize(16)
                    .containsAll(ECHOED_MEMBER_NAMES);
            assertThat(carried)
                    .containsExactlyElementsOf(CARRIED_MEMBER_NAMES)
                    .doesNotContainAnyElementsOf(ECHOED_MEMBER_NAMES);
        }

        @Test
        @DisplayName("a fully echoed record converts to exactly its four routing fields and its entry "
                + "mode, and the conversion reads nothing else")
        void aFullyEchoedRecordConvertsToItsRoutingFieldsAndMode() {
            final ConversationState state = subject.toConversationState(fullyEchoed());

            assertThat(state.fromTransactionId()).isEqualTo("CB00");
            assertThat(state.fromProgram()).isEqualTo("COBIL00C");
            assertThat(state.toTransactionId()).isEqualTo("CM00");
            assertThat(state.toProgram()).isEqualTo("COMEN01C");
            assertThat(state.entryMode()).isSameAs(ConversationState.EntryMode.RE_ENTRY);
        }

        @Test
        @DisplayName("the rendering of the converted state names no echoed identity or cardholder "
                + "value, because none was carried across for it to name")
        void theConvertedStateRendersNoEchoedValue() {
            final String rendered = subject.toConversationState(fullyEchoed()).toString();

            assertThat(rendered)
                    .doesNotContain(ECHOED_USER_ID)
                    .doesNotContain("000000123")
                    .doesNotContain("MARY")
                    .doesNotContain("SMITH")
                    .doesNotContain("00000000011")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("COBIL0A");
        }

        @Test
        @DisplayName("an absent record converts to the empty state rather than to nothing, so no "
                + "service has to null-check the state it is handed")
        void anAbsentRecordConvertsToTheEmptyState() {
            assertThat(subject.toConversationState(null))
                    .isSameAs(ConversationState.empty());
        }

        @Test
        @DisplayName("an empty record converts to the empty state, which the navigation authority "
                + "treats as no carry-over at all")
        void anEmptyRecordConvertsToTheEmptyState() {
            assertThat(subject.toConversationState(NavigationContext.empty()).absent()).isTrue();
        }

        @Test
        @DisplayName("a record carrying only echoed identity converts to the empty state, because none "
                + "of those members is carried and none of them makes a turn stateful for a service")
        void aRecordCarryingOnlyEchoedIdentityConvertsToTheEmptyState() {
            final NavigationContext identityOnly = new NavigationContext(
                    null, null, null, null, ECHOED_USER_ID, UserType.ADMIN.getCode(), null,
                    "000000123", "MARY", "ANN", "SMITH", "00000000011", "Y", "4111111111111111",
                    "COBIL0A", "COBIL00");

            assertThat(subject.toConversationState(identityOnly).absent())
                    .as("a client that echoes an identity and nothing else has established no "
                            + "conversation the service can act on")
                    .isTrue();
        }

        @Test
        @DisplayName("an unset program-context flag converts to first entry, matching the zero a "
                + "freshly initialised communication area holds")
        void anUnsetProgramContextConvertsToFirstEntry() {
            final NavigationContext unset = new NavigationContext(
                    "CB00", "COBIL00C", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(subject.toConversationState(unset).entryMode())
                    .isSameAs(ConversationState.EntryMode.FIRST_ENTRY);
        }

        @ParameterizedTest(name = "{0} round-trips through both conversions")
        @EnumSource(NavigationContext.ProgramContext.class)
        @DisplayName("each program-context flag converts to its entry mode and back to itself, so the "
                + "one member the service is entitled to read survives both directions")
        void eachProgramContextRoundTrips(final NavigationContext.ProgramContext flag) {
            final NavigationContext inbound = new NavigationContext(
                    "CB00", "COBIL00C", "CM00", "COMEN01C", null, null, flag,
                    null, null, null, null, null, null, null, null, null);

            final ConversationState state = subject.toConversationState(inbound);
            final NavigationContext outbound =
                    subject.toNavigationContext(inbound, state, null, null);

            assertThat(outbound.programContext()).isSameAs(flag);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Outbound: the eleven survive, and identity is the server's
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("outbound, the echoed members survive while the identity becomes the server's")
    final class OutboundTheEchoedMembersSurvive {

        @Test
        @DisplayName("every cardholder member the service never saw is carried through unchanged, so "
                + "the client's own view of its state is not silently emptied")
        void everyCardholderMemberIsCarriedThroughUnchanged() {
            // // Asserted here rather than in MenuServiceTest, because a service that rebuilt the whole
            // // sixteen-field record itself is what would make the eleven readable in the service tier at all.
            // // The pass-through happens here, where the values are never in reach of a decision.
            final NavigationContext inbound = fullyEchoed();

            final NavigationContext outbound = subject.toNavigationContext(inbound,
                    subject.toConversationState(inbound), AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(outbound.customerId()).isEqualTo("000000123");
            assertThat(outbound.customerFirstName()).isEqualTo("MARY");
            assertThat(outbound.customerMiddleName()).isEqualTo("ANN");
            assertThat(outbound.customerLastName()).isEqualTo("SMITH");
            assertThat(outbound.accountId()).isEqualTo("00000000011");
            assertThat(outbound.accountStatus()).isEqualTo("Y");
            assertThat(outbound.cardNumber()).isEqualTo("4111111111111111");
            assertThat(outbound.lastMap()).isEqualTo("COBIL0A");
            assertThat(outbound.lastMapset()).isEqualTo("COBIL00");
        }

        @Test
        @DisplayName("the identity the response carries is the authenticated principal's and not the "
                + "identity the client echoed, so a client-supplied identity cannot survive a turn")
        void theIdentityCarriedIsTheAuthenticatedOne() {
            final NavigationContext inbound = fullyEchoed();

            final NavigationContext outbound = subject.toNavigationContext(inbound,
                    subject.toConversationState(inbound), AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(outbound.userId())
                    .isEqualTo(AUTHENTICATED_USER_ID)
                    .isNotEqualTo(ECHOED_USER_ID);
            assertThat(outbound.userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(outbound.echoesAdministratorCode())
                    .as("the response carries the administrator code because the principal is one, "
                            + "not because the client said so")
                    .isTrue();
        }

        @Test
        @DisplayName("an absent principal clears the identity rather than letting the echoed identity "
                + "stand in for one")
        void anAbsentPrincipalClearsTheIdentity() {
            final NavigationContext inbound = fullyEchoed();

            final NavigationContext outbound = subject.toNavigationContext(inbound,
                    subject.toConversationState(inbound), null, null);

            assertThat(outbound.userId()).isNull();
            assertThat(outbound.userType()).isNull();
            assertThat(outbound.customerLastName())
                    .as("clearing the identity does not disturb the members the service never saw")
                    .isEqualTo("SMITH");
        }

        @Test
        @DisplayName("the routing fields come from the state the service returned, so the service's "
                + "own decision is what the response publishes")
        void theRoutingFieldsComeFromTheReturnedState() {
            final NavigationContext inbound = fullyEchoed();
            final ConversationState decided = subject.toConversationState(inbound)
                    .withOrigin("CR00", "CORPT00C")
                    .withNominatedProgram("COSGN00C");

            final NavigationContext outbound = subject.toNavigationContext(inbound, decided,
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(outbound.fromTransactionId()).isEqualTo("CR00");
            assertThat(outbound.fromProgram()).isEqualTo("CORPT00C");
            assertThat(outbound.toProgram()).isEqualTo("COSGN00C");
            assertThat(outbound.programContext())
                    .as("recording an origin resets the flag to first entry, and the reset is "
                            + "published rather than discarded")
                    .isSameAs(NavigationContext.ProgramContext.ENTER);
        }

        @Test
        @DisplayName("an absent inbound record still produces a publishable record, with no echoed "
                + "member invented to fill the gap")
        void anAbsentInboundRecordStillProducesAPublishableRecord() {
            final NavigationContext outbound = subject.toNavigationContext(null,
                    ConversationState.empty().withOriginatingProgram("COBIL00C"),
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(outbound.fromProgram()).isEqualTo("COBIL00C");
            assertThat(outbound.userId()).isEqualTo(AUTHENTICATED_USER_ID);
            assertThat(outbound.customerId()).isNull();
            assertThat(outbound.accountId()).isNull();
            assertThat(outbound.cardNumber()).isNull();
            assertThat(outbound.lastMap()).isNull();
        }

        @Test
        @DisplayName("an absent returned state clears the routing fields rather than falling back to "
                + "the routing the client echoed")
        void anAbsentReturnedStateClearsTheRoutingFields() {
            final NavigationContext outbound = subject.toNavigationContext(fullyEchoed(), null,
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(outbound.fromTransactionId()).isNull();
            assertThat(outbound.fromProgram()).isNull();
            assertThat(outbound.toTransactionId()).isNull();
            assertThat(outbound.toProgram()).isNull();
            assertThat(outbound.programContext())
                    .isSameAs(NavigationContext.ProgramContext.ENTER);
            assertThat(outbound.customerLastName())
                    .as("the members the service never saw are still carried through")
                    .isEqualTo("SMITH");
        }

        @Test
        @DisplayName("both records absent still produces a record rather than nothing, and its "
                + "program-context flag is written explicitly rather than left absent")
        void bothRecordsAbsentStillProducesARecord() {
            // The flag is written rather than omitted on purpose. Its legacy antecedent is
            // CDEMO-PGM-CONTEXT PIC 9(01) at [app/cpy/COCOM01Y.cpy:L29-L31], a single-digit numeric item
            // that cannot be absent: it holds 0 on a first entry and 1 thereafter. The wire record's own
            // all-null empty() instance leaves it null, which is a shape the estate cannot produce, so
            // the outbound record is the empty one with the flag stated - not the all-null one. Stating
            // it matters because the flag is what tells the next turn whether field-level error
            // decoration applies at all, and an omitted flag would leave that to be inferred.
            assertThat(subject.toNavigationContext(null, null, null, null))
                    .isEqualTo(NavigationContext.empty().withFirstEntry());
            assertThat(subject.toNavigationContext(null, null, null, null).programContext())
                    .isSameAs(NavigationContext.ProgramContext.ENTER);
        }

        @Test
        @DisplayName("the outbound record still declares all sixteen members, because the wire contract "
                + "is the communication area and dropping a member would change it")
        void theOutboundRecordStillDeclaresAllSixteenMembers() {
            final NavigationContext outbound = subject.toNavigationContext(fullyEchoed(),
                    subject.toConversationState(fullyEchoed()), AUTHENTICATED_USER_ID,
                    UserType.ADMIN);

            assertThat(outbound.getClass().getRecordComponents()).hasSize(16);
            assertThat(Arrays.stream(outbound.getClass().getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .containsAll(ECHOED_MEMBER_NAMES);
        }
    }

    // ----------------------------------------------------------------------------------------
    // The mismatch report is a report, not a refusal
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the identity-mismatch check reports rather than refuses")
    final class TheMismatchCheckReportsRatherThanRefuses {

        @Test
        @DisplayName("an echoed identity matching the principal in both members agrees")
        void aMatchingEchoAgrees() {
            final NavigationContext matching = new NavigationContext(
                    null, null, null, null, AUTHENTICATED_USER_ID, UserType.ADMIN.getCode(), null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(subject.echoedIdentityDisagrees(matching, AUTHENTICATED_USER_ID,
                    UserType.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("a differing identifier disagrees, and so does a differing type on its own, "
                + "because the two members are compared together")
        void aDifferenceInEitherMemberDisagrees() {
            final NavigationContext wrongId = new NavigationContext(
                    null, null, null, null, ECHOED_USER_ID, UserType.ADMIN.getCode(), null,
                    null, null, null, null, null, null, null, null, null);
            final NavigationContext wrongType = new NavigationContext(
                    null, null, null, null, AUTHENTICATED_USER_ID, UserType.USER.getCode(), null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(subject.echoedIdentityDisagrees(wrongId, AUTHENTICATED_USER_ID,
                    UserType.ADMIN)).isTrue();
            assertThat(subject.echoedIdentityDisagrees(wrongType, AUTHENTICATED_USER_ID,
                    UserType.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("an absent record disagrees with an established principal, because an absent "
                + "record asserts no identity while a principal is one")
        void anAbsentRecordDisagreesWithAnEstablishedPrincipal() {
            assertThat(subject.echoedIdentityDisagrees(null, AUTHENTICATED_USER_ID, UserType.ADMIN))
                    .isTrue();
            assertThat(subject.echoedIdentityDisagrees(null, AUTHENTICATED_USER_ID, null)).isTrue();
            assertThat(subject.echoedIdentityDisagrees(null, null, UserType.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("an absent record and no principal agree, which is the unauthenticated first "
                + "turn and not a mismatch")
        void anAbsentRecordAndNoPrincipalAgree() {
            assertThat(subject.echoedIdentityDisagrees(null, null, null)).isFalse();
        }

        @Test
        @DisplayName("an unrecognised echoed type code disagrees without raising, so a hostile value "
                + "cannot turn the check into a thrown failure")
        void anUnrecognisedEchoedTypeCodeDisagreesWithoutRaising() {
            final NavigationContext hostile = new NavigationContext(
                    null, null, null, null, AUTHENTICATED_USER_ID, "Z", null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(subject.echoedIdentityDisagrees(hostile, AUTHENTICATED_USER_ID,
                    UserType.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("a disagreement changes nothing about the conversion: reconciliation happens "
                + "either way, so the check is diagnostic and never a gate")
        void aDisagreementDoesNotChangeTheConversion() {
            final NavigationContext disagreeing = fullyEchoed();
            final NavigationContext agreeing = new NavigationContext(
                    "CB00", "COBIL00C", "CM00", "COMEN01C",
                    AUTHENTICATED_USER_ID, UserType.ADMIN.getCode(),
                    NavigationContext.ProgramContext.REENTER,
                    "000000123", "MARY", "ANN", "SMITH",
                    "00000000011", "Y", "4111111111111111", "COBIL0A", "COBIL00");

            assertThat(subject.echoedIdentityDisagrees(disagreeing, AUTHENTICATED_USER_ID,
                    UserType.ADMIN)).isTrue();
            assertThat(subject.echoedIdentityDisagrees(agreeing, AUTHENTICATED_USER_ID,
                    UserType.ADMIN)).isFalse();
            assertThat(subject.toNavigationContext(disagreeing,
                    subject.toConversationState(disagreeing), AUTHENTICATED_USER_ID, UserType.ADMIN))
                    .as("the two conversions produce the same record, so whether the echo agreed "
                            + "makes no difference to what the response carries")
                    .isEqualTo(subject.toNavigationContext(agreeing,
                            subject.toConversationState(agreeing), AUTHENTICATED_USER_ID,
                            UserType.ADMIN));
        }
    }

    // ----------------------------------------------------------------------------------------
    // The adapter itself
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("no production source authorizes from an echoed value")
    final class NoProductionSourceAuthorizesFromAnEchoedValue {

        /** The production source tree, relative to the module directory the build runs tests from. */
        private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

        /**
         * A conservative floor on the number of production sources, so a walk that found almost
         * nothing cannot satisfy the absence assertions below.
         */
        private static final int MINIMUM_PRODUCTION_SOURCES = 25;

        /** Simple name of the wire record, the receiver an echoed member would be read from. */
        private static final String WIRE_RECORD_TYPE_NAME = "NavigationContext";

        /** Fully qualified wire type, used to distinguish it from the service-owned carrier. */
        private static final String WIRE_RECORD_QUALIFIED_NAME =
                "com.carddemo.api.dto.NavigationContext";

        /**
         * The class that declares the echoed-claim accessor; its own declaration is not a call site.
         */
        private static final String WIRE_RECORD_FILE_NAME = WIRE_RECORD_TYPE_NAME + ".java";

        /**
         * Simple name of the service-owned carrier of the same sixteen fields.
         *
         * <p>The layering forbids a service from naming the wire record, so the ten screen services that
         * reproduce programs reading carried communication-area members take a service-owned carrier of
         * the same shape instead, and one adapter in the API layer converts between the two. That moves
         * where an echoed member is read; it does not make an echoed member trustworthy. Every scan below
         * therefore covers both names, so relocating the carriage could not quietly relocate it out of
         * this guard's reach.
         */
        private static final String SERVICE_CARRIER_TYPE_NAME = "ScreenNavigationState";

        /** The file that declares the service-owned carrier; its own declaration is not a call site. */
        private static final String SERVICE_CARRIER_FILE_NAME = SERVICE_CARRIER_TYPE_NAME + ".java";

        /** Both carriers of the sixteen fields: the wire form and the service-owned form. */
        private static final List<String> CARRIER_TYPE_NAMES =
                List.of(WIRE_RECORD_TYPE_NAME, SERVICE_CARRIER_TYPE_NAME);

        /**
         * The service-owned screen contract records that declare the carrier as one opaque component.
         *
         * <p>Closing the upward package edges gave the two heaviest screen services their own
         * command-and-outcome types in place of the transport records they used to take, and each of
         * those four declares the sixteen-field carrier as a single whole-record component. Naming the
         * carrier there is a declaration rather than a call site, which is exactly the footing
         * {@link #SERVICE_CARRIER_FILE_NAME} itself stands on: a record that holds the carriage whole
         * has no read of an individual member to trust or distrust.
         *
         * <p>The skip is earned rather than granted.
         * {@link #theServiceOwnedContractRecordsReadNoEchoedMember()} proves that each of the four
         * really names the carrier and reads none of the eleven members off it, so a member accessor
         * added to any of them fails there instead of passing unnoticed here. The structural guard
         * below asserts that every name listed was really reached, so a rename leaves no stale topology
         * entry behind for an unrelated file to inherit.
         */
        private static final List<String> SERVICE_CONTRACT_HOLDER_FILE_NAMES = List.of(
                "AccountUpdateCommand.java", "AccountUpdateOutcome.java",
                "UserCommand.java", "UserOutcome.java");

        /**
         * The sign-on projection, entitled for a reason the accessor-name search cannot see.
         *
         * <p>It reads {@code userId()} and {@code userType()} - two of the eleven names - but reads them
         * off the <em>service's</em> sign-on result, not off a wire record. Those two values are the
         * identity the credential master yielded, which is the opposite of an echoed claim: sign-on is the
         * one turn that establishes identity, so identity there is derived and there is nothing to echo it
         * from. The search matches on the accessor name alone and so cannot distinguish the receiver, which
         * is the same homonym problem the scoping comment below records for the menu catalog's own
         * {@code userType()}. The entitlement is earned rather than asserted: a test below proves this file
         * constructs a wire record and never accepts one, so it has no receiver an echoed member could be
         * read from.
         */
        private static final String SIGN_ON_ADAPTER_FILE_NAME = "SignOnContractAdapter.java";

        /**
         * The screen-state boundary, entitled on exactly the same footing as this adapter.
         *
         * <p>It is the one place the sixteen-field wire record and the service-owned carrier of the same
         * shape are converted into one another, so it reads all eleven echoed members and writes all
         * eleven back. It decides nothing from any of them: it carries them through unread, and the two it
         * does not carry through - the identity pair - it replaces with the authenticated principal's own
         * rather than acting on what the client claimed.
         */
        private static final String SCREEN_STATE_ADAPTER_FILE_NAME = "ScreenStateAdapter.java";

        /**
         * The files entitled to read an echoed member: this adapter and the screen-state boundary, which
         * carry the members through, the two carriers that declare them, and the sign-on projection that
         * derives identity instead.
         */
        private static final List<String> ENTITLED_FILE_NAMES =
                List.of("ConversationStateAdapter.java", WIRE_RECORD_FILE_NAME,
                        SERVICE_CARRIER_FILE_NAME, SCREEN_STATE_ADAPTER_FILE_NAME,
                        SIGN_ON_ADAPTER_FILE_NAME);


        /**
         * Reads every production source as a path-and-text pair.
         *
         * @return one entry per production {@code .java} file
         */
        private static List<Map.Entry<Path, String>> productionSources() {
            assertThat(PRODUCTION_SOURCE_ROOT)
                    .as("the production source root must be readable from the test working "
                            + "directory, or every absence assertion here would be vacuous")
                    .isDirectory();

            final List<Map.Entry<Path, String>> sources = new ArrayList<>();
            try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
                for (final Path path : tree.filter(Files::isRegularFile)
                        .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    sources.add(Map.entry(path, readText(path)));
                }
            } catch (final IOException problem) {
                throw new UncheckedIOException("unable to walk " + PRODUCTION_SOURCE_ROOT, problem);
            }

            assertThat(sources.size())
                    .as("far too few production sources were read for the absence assertions to "
                            + "mean anything; the walk must have started in the wrong directory")
                    .isGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);
            return sources;
        }

        /**
         * Reads one source file as text.
         *
         * @param path the file to read
         * @return the file's content
         */
        private static String readText(final Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (final IOException problem) {
                throw new UncheckedIOException("unable to read " + path, problem);
            }
        }

        /**
         * The ten screen services that consume the service-owned sixteen-field carrier.
         *
         * <p>This is not a licence to name the wire record: the unconditional wire-record census below
         * allows that type only in {@code api} and {@code config}. These services reproduce CICS screen
         * programs whose input includes carried communication-area members, so a five-field
         * {@link ConversationState} cannot express their input. They therefore read
         * {@link com.carddemo.service.ScreenNavigationState}, which belongs to their own layer, and
         * rebuild it through its canonical constructor to carry the client's view unchanged.
         *
         * <p>Two properties are asserted rather than assumed: every name here must really name the
         * service carrier, so a service later reduced to five-field state cannot leave a dead census
         * entry behind, and no listed file may compare an echoed identity, only carry it.
         */
        private static final List<String> SCREEN_STATE_CONSUMER_FILE_NAMES = List.of(
                "AccountUpdateService.java", "AccountViewService.java", "BillPaymentService.java",
                "CardDetailService.java", "CardListService.java", "CardUpdateService.java",
                "TransactionAddService.java", "TransactionListService.java",
                "TransactionViewService.java", "UserManagementService.java");

        /**
         * The receiver identifiers the enrolled services hold a wire record in, so the identity guard
         * below reads an echoed member and not a homonym off a request or a screen work area. The names
         * are matched as substrings, so a qualified form such as {@code state.context} is covered by
         * {@code context} and needs no separate entry.
         */
        private static final List<String> CARRIED_STATE_RECEIVER_NAMES = List.of(
                "context", "inbound", "received", "base", "echoed", "carried", "current", "commarea",
                "navigationContext", "carriedImage");

        /**
         * Tokens that would turn a carried read into a decision. A carried read is an argument in a
         * copy of the record and nothing else, so none of these may share its line.
         */
        private static final List<String> COMPARISON_TOKENS = List.of(
                "==", "!=", ".equals(", "if (", "switch", "?", ".compareTo(", ".startsWith(",
                ".contains(", "&&", "||");

        /**
         * A floor on the number of carried identity reads found, so the identity guard cannot pass by
         * finding nothing. The floor is set far below the number of lines that carry one.
         */
        private static final int MINIMUM_CARRIED_IDENTITY_READS = 30;

        /**
         * The two packages entitled to name the wire record at all: the API layer, which is where the
         * boundary and the transport records live, and the configuration layer, which publishes the
         * record's schema and is the composition root.
         */
        private static final List<String> WIRE_RECORD_PACKAGE_PATHS =
                List.of(Path.of("com", "carddemo", "api").toString(),
                        Path.of("com", "carddemo", "config").toString());

        /**
         * Reports whether a source file sits in a package entitled to name the wire record.
         *
         * @param path the production source path
         * @return {@code true} when the file sits under the API or configuration package
         */
        private static boolean isWireRecordPackage(final Path path) {
            final String asText = path.toString();
            for (final String permitted : WIRE_RECORD_PACKAGE_PATHS) {
                if (asText.contains(permitted)) {
                    return true;
                }
            }
            return false;
        }

        @Test
        @DisplayName("only API and configuration sources name the wire NavigationContext, with no "
                + "service exemption of any kind")
        void onlyApiAndConfigurationNameTheWireRecord() {
            // Asserted on full file text rather than imports so a fully-qualified signature, a field
            // declaration or even documentation proposing the wrong dependency cannot evade the
            // boundary. The positive controls make the absence non-vacuous: the walk must find both
            // the declaring transport file and the configuration that publishes its schema.
            final List<String> offenders = new ArrayList<>();
            final List<String> permittedFilesFound = new ArrayList<>();
            for (final Map.Entry<Path, String> source : productionSources()) {
                if (!source.getValue().contains(WIRE_RECORD_TYPE_NAME)) {
                    continue;
                }
                if (isWireRecordPackage(source.getKey())) {
                    permittedFilesFound.add(source.getKey().getFileName().toString());
                } else {
                    offenders.add(source.getKey().toString());
                }
            }

            assertThat(permittedFilesFound)
                    .as("the census must find the wire record and its published schema, or an empty "
                            + "search could masquerade as package isolation")
                    .contains(WIRE_RECORD_FILE_NAME, "PublishedContractTypeRoster.java");
            assertThat(offenders)
                    .as("NavigationContext is a transport record: every service takes a service-owned "
                            + "carrier, so no source outside api/config may name it for any reason")
                    .isEmpty();
        }

        @Test
        @DisplayName("outside the API boundary, only the service carrier declaration, four opaque "
                + "contract holders and ten measured screen services name ScreenNavigationState")
        void onlyTheMeasuredServiceFilesNameTheServiceCarrier() {
            // This is the service-carrier half of the topology. It deliberately does not mention the
            // wire type: the preceding test has already made that rule unconditional.
            final List<String> offenders = new ArrayList<>();
            final List<String> consumerFilesFound = new ArrayList<>();
            final List<String> holderFilesFound = new ArrayList<>();
            boolean carrierDeclarationFound = false;
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (isWireRecordPackage(source.getKey())) {
                    continue;
                }
                if (!source.getValue().contains(SERVICE_CARRIER_TYPE_NAME)) {
                    continue;
                }
                if (SERVICE_CARRIER_FILE_NAME.equals(fileName)) {
                    carrierDeclarationFound = true;
                    continue;
                }
                if (SERVICE_CONTRACT_HOLDER_FILE_NAMES.contains(fileName)) {
                    holderFilesFound.add(fileName);
                    continue;
                }
                if (SCREEN_STATE_CONSUMER_FILE_NAMES.contains(fileName)) {
                    consumerFilesFound.add(fileName);
                    continue;
                }
                offenders.add(source.getKey().toString());
            }

            assertThat(carrierDeclarationFound)
                    .as("the carrier declaration itself must be reached, or every consumer assertion "
                            + "below is detached from the type it claims to govern")
                    .isTrue();
            assertThat(offenders)
                    .as("a service that needs only routing takes ConversationState; a service that "
                            + "reproduces a full screen carriage must be one of the ten measured "
                            + "ScreenNavigationState consumers, and no other layer may grow a second "
                            + "carrier path")
                    .isEmpty();
            assertThat(consumerFilesFound)
                    .as("every measured screen service must exist and really name the service carrier, "
                            + "so a stale census entry cannot hide an unreviewed consumer")
                    .containsExactlyInAnyOrderElementsOf(SCREEN_STATE_CONSUMER_FILE_NAMES);
            assertThat(holderFilesFound)
                    .as("every service-owned contract record listed must exist and must really name "
                            + "the carrier; an opaque holder entry that matches no file would make this "
                            + "topology assertion incomplete")
                    .containsExactlyInAnyOrderElementsOf(SERVICE_CONTRACT_HOLDER_FILE_NAMES);
        }

        @Test
        @DisplayName("the service-owned contract records hold the carriage whole and never dereference "
                + "it, which is what earns the skip granted above rather than merely widening it")
        void theServiceOwnedContractRecordsReadNoEchoedMember() {
            // The topology above says these four may hold the carrier whole. This proves what that
            // classification means:
            // each of them declares the carriage as one component, copies it whole, and never reaches
            // inside it, so there is no read of any of the sixteen fields to trust or distrust and the
            // question the enrolment governs cannot arise in them.
            //
            // Stated as an absence of dereference rather than as an absence of the eleven accessor
            // names, for two reasons. It is stronger: it forbids reading any of the sixteen, not only
            // the eleven the trust boundary turns on. And it is exact: these records declare their own
            // accountId, cardNumber, userId and userType map items - the operator's own entry - so a
            // bare accessor-name search would report a record reading its own component as though it had
            // read a carried claim, which is precisely the homonym the sibling guard's receiver scoping
            // exists to tell apart.
            final List<String> holdersFound = new ArrayList<>();
            final List<String> offenders = new ArrayList<>();
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (!SERVICE_CONTRACT_HOLDER_FILE_NAMES.contains(fileName)) {
                    continue;
                }
                holdersFound.add(fileName);
                assertThat(source.getValue())
                        .as("%s must really declare the service-owned carrier, or the skip it was "
                                + "granted covers nothing at all", fileName)
                        .contains(SERVICE_CARRIER_TYPE_NAME);
                final String[] lines = source.getValue().split("\n", -1);
                for (int index = 0; index < lines.length; index++) {
                    if (dereferencesCarriedState(lines[index])) {
                        offenders.add(fileName + " line " + (index + 1) + ": " + lines[index].strip());
                    }
                }
            }

            assertThat(holdersFound)
                    .as("each listed contract record must exist, so this earning test cannot pass by "
                            + "finding nothing to examine")
                    .containsExactlyInAnyOrderElementsOf(SERVICE_CONTRACT_HOLDER_FILE_NAMES);
            assertThat(offenders)
                    .as("a service-owned contract record carries the communication area as one opaque "
                            + "component; the moment it reaches inside one it is performing the "
                            + "pass-through the screen-state census governs, and it belongs in "
                            + "SCREEN_STATE_CONSUMER_FILE_NAMES rather than here")
                    .isEmpty();
        }

        /**
         * Reports whether a line reaches inside carried state rather than copying it whole.
         *
         * <p>Matched on the documented receiver names so that this shares one vocabulary with
         * {@link #readsEchoedMemberOffCarriedState(String, String)}, and on a word boundary so that a
         * component whose name merely begins with a receiver name - {@code currentDate} against
         * {@code current} - is not mistaken for a dereference of the carriage.
         *
         * @param  line one line of production source
         * @return {@code true} when the line dereferences a carried-state receiver
         */
        private static boolean dereferencesCarriedState(final String line) {
            for (final String receiver : CARRIED_STATE_RECEIVER_NAMES) {
                for (final String form : List.of(receiver + ".", receiver + "().")) {
                    int at = line.indexOf(form);
                    while (at >= 0) {
                        if (at == 0 || !isIdentifierPart(line.charAt(at - 1))) {
                            return true;
                        }
                        at = line.indexOf(form, at + 1);
                    }
                }
            }
            return false;
        }

        /**
         * Reports whether a character may appear inside a Java identifier.
         *
         * @param  character one character of production source
         * @return {@code true} when the character is a letter, a digit or an underscore
         */
        private static boolean isIdentifierPart(final char character) {
            return Character.isLetterOrDigit(character) || character == '_';
        }

        @Test
        @DisplayName("only the API boundary files and the ten service-carrier consumers read an "
                + "echoed member, so no other file performs the pass-through")
        void onlyTheApiBoundariesAndScreenStateConsumersReadAnEchoedMember() {
            // Scoped twice over, because a member accessor name is not by itself evidence of an echoed
            // read. It is scoped to the files that name the wire record, since only a file holding a
            // NavigationContext has a receiver to read one off. And within those files it is scoped to
            // the receiver, because the accessor names are homonyms of components other records
            // genuinely declare: MenuOptionCatalog.UserMenuOption declares its own userType(), which is
            // the option's required role read from app/cpy/COMEN02Y.cpy, and the account, card,
            // transaction and user screen contracts declare their own accountId(), cardNumber() and
            // userId() map items, which are the operator's own entry. A boundary controller that
            // publishes such a screen therefore contains those names while passing the wire record
            // through whole and reading nothing off it - and a bare accessor-name search cannot tell the
            // two apart, so it would report every one of those controllers as an offender.
            //
            // The receiver test is the same one the identity guard below applies, over the same
            // documented list of names a wire record is held in, so the two halves of this guarantee
            // cannot come to disagree about what an echoed read looks like. The structural test above
            // is what keeps this scoping honest: no file outside API/config may name the wire record,
            // and only the measured service files may name the service carrier, so there is no receiver
            // this scan could miss.
            final List<String> offenders = new ArrayList<>();
            final List<String> positiveControl = new ArrayList<>();
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (CARRIER_TYPE_NAMES.stream().noneMatch(source.getValue()::contains)) {
                    continue;
                }
                final boolean expectedReader = ENTITLED_FILE_NAMES.contains(fileName)
                        || SCREEN_STATE_CONSUMER_FILE_NAMES.contains(fileName);
                for (final String line : source.getValue().split("\n", -1)) {
                    for (final String member : ECHOED_MEMBER_NAMES) {
                        if (!readsEchoedMemberOffCarriedState(line, member)) {
                            continue;
                        }
                        if (expectedReader) {
                            positiveControl.add(fileName + " reads ." + member + "()");
                        } else {
                            offenders.add(fileName + " reads ." + member + "() at: " + line.strip());
                        }
                    }
                }
            }

            assertThat(positiveControl)
                    .as("the receiver-scoped search must still find the pass-through reads the "
                            + "expected files genuinely perform, or the scoping has made this "
                            + "assertion vacuous and an unexpected read would pass unnoticed")
                    .isNotEmpty();
            assertThat(offenders)
                    .as("the two carriers declare the eleven members; only the API boundary and the "
                            + "ten measured service-carrier consumers read them, and each reads them "
                            + "to carry them through rather than to decide anything from them")
                    .isEmpty();
        }

        @Test
        @DisplayName("the ten screen-state consumers only carry the echoed identity and never compare "
                + "it, so owning the carrier cannot become authority over its client-supplied values")
        void theScreenStateConsumersCarryTheEchoedIdentityAndNeverCompareIt() {
            // The two identity members are the security-bearing pair, and a carried read of either is
            // only ever an argument inside a copy of the service-owned record. A comparison sharing the
            // line would be a decision taken from a value the client supplied. Scoped to the
            // carried-state receivers so that a request DTO's own userId(), which those services
            // validate and must be free to compare, is not mistaken for an echoed claim.
            final List<String> offenders = new ArrayList<>();
            int carriedIdentityReads = 0;
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (!SCREEN_STATE_CONSUMER_FILE_NAMES.contains(fileName)) {
                    continue;
                }
                for (final String line : source.getValue().split("\n", -1)) {
                    if (!readsCarriedIdentity(line)) {
                        continue;
                    }
                    carriedIdentityReads++;
                    for (final String comparison : COMPARISON_TOKENS) {
                        if (line.contains(comparison)) {
                            offenders.add(fileName + ": " + line.strip());
                            break;
                        }
                    }
                }
            }

            assertThat(carriedIdentityReads)
                    .as("the measured screen services must really carry the echoed identity, or this "
                            + "guard would pass by finding nothing at all")
                    .isGreaterThanOrEqualTo(MINIMUM_CARRIED_IDENTITY_READS);
            assertThat(offenders)
                    .as("a carried identity is an argument in a copy of the record and nothing else; "
                            + "the role split reads the authenticated principal, which is why "
                            + "NavigationService takes a user type as its own parameter")
                    .isEmpty();
        }

        /**
         * Reports whether a line reads the echoed user identifier or user type off carried state.
         *
         * @param line one line of production source
         * @return {@code true} when the line reads either identity member off a carried-state receiver
         */
        private static boolean readsCarriedIdentity(final String line) {
            for (final String receiver : CARRIED_STATE_RECEIVER_NAMES) {
                if (line.contains(receiver + ".userId()") || line.contains(receiver + ".userType()")) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reports whether a line reads one named echoed member off a wire-record receiver.
         *
         * <p>The generalisation of {@link #readsCarriedIdentity(String)} from the two identity members
         * to all eleven, over the same documented receiver names, so that the two guards share one
         * definition of what an echoed read is. Requiring the receiver is what distinguishes a read off
         * carried state from a read of an identically named component that another record genuinely
         * declares - a screen contract's own account identifier, card number or user identifier, which
         * is the operator's own entry rather than a claim the client echoed.
         *
         * @param line one line of production source
         * @param member the echoed member's accessor name
         * @return {@code true} when the line reads that member off a carried-state receiver
         */
        private static boolean readsEchoedMemberOffCarriedState(final String line,
                final String member) {
            for (final String receiver : CARRIED_STATE_RECEIVER_NAMES) {
                if (line.contains(receiver + "." + member + "()")) {
                    return true;
                }
            }
            return false;
        }

        @Test
        @DisplayName("the sign-on projection only ever constructs a wire record and never accepts one, "
                + "which is what earns its entitlement above rather than merely widening it")
        void theSignOnProjectionProducesTheWireRecordAndNeverConsumesOne() {
            // The entitlement added for this file would be a hole if the file could also receive a wire
            // record, because then its reads of userId() and userType() would be ambiguous between the
            // service result and an echoed claim. It cannot: a value can only be read off a receiver, and
            // this file declares none. Asserted on the text because the property is an absence - there is
            // no parameter, field or local of that type to observe by invoking anything.
            final String text = productionSources().stream()
                    .filter(source -> SIGN_ON_ADAPTER_FILE_NAME
                            .equals(source.getKey().getFileName().toString()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            SIGN_ON_ADAPTER_FILE_NAME + " must exist, or its entitlement is dead weight"));

            assertThat(text)
                    .as("it is the producer of derived identity, so it must construct the record")
                    .contains("new " + WIRE_RECORD_TYPE_NAME + "(");
            assertThat(text)
                    .as("and it must accept no wire record in any position, so no echoed member has a "
                            + "receiver here to be read from")
                    .doesNotContain("(" + WIRE_RECORD_TYPE_NAME + " ")
                    .doesNotContain("(final " + WIRE_RECORD_TYPE_NAME + " ")
                    .doesNotContain(", " + WIRE_RECORD_TYPE_NAME + " ")
                    .doesNotContain(", final " + WIRE_RECORD_TYPE_NAME + " ")
                    .doesNotContain("private final " + WIRE_RECORD_TYPE_NAME + " ");
        }

        @Test
        @DisplayName("the echoed-claim accessor has no production call site at all, so no code path "
                + "anywhere decides anything from what the client said its role was")
        void theEchoedClaimAccessorHasNoProductionCallSite() {
            // NavigationContext.echoesAdministratorCode() reports what the client claimed and its own
            // javadoc says so. Having it uncalled in production is the intended state, not an
            // oversight: it exists so a diagnostic can report the claim, and the sign-on role split
            // reads the authenticated principal instead - which is why NavigationService takes a user
            // type as its own parameter. A call site appearing here would be an authorization decision
            // taken from an echoed value. The declaring file is excluded because its own declaration
            // and the recursive call inside it are not decisions.
            final List<String> offenders = new ArrayList<>();
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (WIRE_RECORD_FILE_NAME.equals(fileName)
                        || SERVICE_CARRIER_FILE_NAME.equals(fileName)) {
                    continue;
                }
                if (source.getValue().contains("echoesAdministratorCode()")) {
                    offenders.add(fileName);
                }
            }

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("the audit is not vacuous: the entitled boundary really does contain every read "
                + "the other sources are asserted not to contain")
        void theAuditIsNotVacuous() {
            final String adapterText = productionSources().stream()
                    .filter(source -> "ConversationStateAdapter.java"
                            .equals(source.getKey().getFileName().toString()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();

            assertThat(adapterText)
                    .as("the search string for the structural assertion must match real source")
                    .contains("NavigationContext");
            assertThat(ECHOED_MEMBER_NAMES)
                    .allSatisfy(member -> assertThat(adapterText)
                            .as("the boundary carries %s through, so the search string matches real "
                                    + "source and the absence assertions are meaningful", member)
                            .contains("." + member + "()"));
        }
    }

    @Nested
    @DisplayName("the adapter is a stateless boundary and holds no decision of its own")
    final class TheAdapterIsAStatelessBoundary {

        @Test
        @DisplayName("declares nothing but its own final collaborator, so the singleton is still safe for "
                + "unsynchronised concurrent use")
        void declaresOnlyItsFinalCollaborator() {
            for (final java.lang.reflect.Field field
                    : ConversationStateAdapter.class.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                // Screening a nomination needs the destination vocabulary, so the boundary now holds one
                // collaborator. What would make the conversion stateful is per-turn state, and a private
                // final collaborator cannot be reassigned by a turn.
                assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers()))
                        .as("field %s must be private", field.getName())
                        .isTrue();
                assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final, so no turn can reassign it", field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .as("field %s must be an injected collaborator, not carried state",
                                field.getName())
                        .isEqualTo(NavigationService.class);
            }
        }

        @Test
        @DisplayName("refuses an absent navigation authority, because a boundary that cannot screen a "
                + "nomination would pass an invented one straight through to an abend")
        void refusesAnAbsentNavigationAuthority() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ConversationStateAdapter(null))
                    .withMessageContaining("navigationService");
        }

        @Test
        @DisplayName("declares only the two conversions and the mismatch report, so no route, no "
                + "message and no authorization decision is made at the boundary")
        void declaresOnlyTheConversionsAndTheReport() {
            assertThat(Arrays.stream(ConversationStateAdapter.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .map(java.lang.reflect.Method::getName)
                    .toList())
                    .containsExactlyInAnyOrder("toConversationState", "toNavigationContext",
                            "echoedIdentityDisagrees");
        }

        @Test
        @DisplayName("repeated conversions of the same record are equal, so the boundary carries "
                + "nothing over between turns")
        void repeatedConversionsAreEqual() {
            assertThat(subject.toConversationState(fullyEchoed()))
                    .isEqualTo(subject.toConversationState(fullyEchoed()));
            assertThat(subject.toNavigationContext(fullyEchoed(),
                    subject.toConversationState(fullyEchoed()), AUTHENTICATED_USER_ID,
                    UserType.ADMIN))
                    .isEqualTo(subject.toNavigationContext(fullyEchoed(),
                            subject.toConversationState(fullyEchoed()), AUTHENTICATED_USER_ID,
                            UserType.ADMIN));
        }
    }
}
