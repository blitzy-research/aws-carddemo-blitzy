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

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ConversationState;
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
 * <p><strong>Where property one holds, and where it is licensed not to.</strong> Property one holds
 * without exception for the batch, repository, domain and utility tiers, and for every service that
 * needs nothing but routing. It does not hold for the ten screen services enrolled in
 * {@code NoProductionSourceAuthorizesFromAnEchoedValue#ENROLLED_SCREEN_SERVICE_FILE_NAMES}, and the
 * reason is in the estate rather than in the Java. Those ten reproduce CICS programs that read carried
 * communication-area members as their own input: {@code COACTVWC} moves {@code CDEMO-ACCT-ID} into the
 * read key at line 691 and {@code CDEMO-CUST-ID} at line 708, and {@code COCRDSLC} moves
 * {@code CDEMO-ACCT-ID} and {@code CDEMO-CARD-NUM} into its work area at lines 342 and 343, tests them
 * for zero at lines 462 and 468, and branches on {@code CDEMO-LAST-MAPSET} at lines 505 and 527. A
 * five-field state cannot carry those members, so a service handed only a five-field state could not
 * reproduce the programs at all. The enrolment is therefore a recorded deviation and not an oversight:
 * it is written down in {@code docs/decision-log.md}, it names each of the ten files individually, and
 * the guards below still fail for an eleventh file, for a stale enrolment, and - by the new third guard
 * - for any enrolled file that compares an echoed identity instead of merely carrying it. The property
 * that actually protects authorization is the narrower one, and it is unconditional: no production
 * source anywhere decides a role from an echoed value.
 *
 * <p><strong>On the mismatch report.</strong>
 * {@link ConversationStateAdapter#echoedIdentityDisagrees(NavigationContext, String, UserType)} is
 * asserted to be a report and not a refusal: nothing throws, and reconciliation happens whether the
 * echo agreed or not. A stale echo is not an attack - a client may legitimately hold pre-authentication
 * state - and the legacy screens have no message for the condition, so inventing a rejection would be a
 * behaviour the estate does not have.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. The adapter holds no field and
 * is constructed directly.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
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
        subject = new ConversationStateAdapter();
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
            // Relocated from MenuServiceTest, where it used to be asserted against a service that
            // rebuilt the whole sixteen-field record itself. That copy is what made the eleven readable
            // in the service tier at all; the pass-through now happens here, where the values are never
            // in reach of a decision.
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

        /**
         * The class that declares the echoed-claim accessor; its own declaration is not a call site.
         */
        private static final String WIRE_RECORD_FILE_NAME = WIRE_RECORD_TYPE_NAME + ".java";

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
         * The files entitled to read an echoed member: this adapter, which carries the members through,
         * the wire record that declares them, and the sign-on projection that derives identity instead.
         */
        private static final List<String> ENTITLED_FILE_NAMES =
                List.of("ConversationStateAdapter.java", WIRE_RECORD_FILE_NAME,
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
         * The ten screen services licensed to name the wire record and to carry its echoed members,
         * named one by one so that an eleventh file still fails and so that a failure names the file.
         *
         * <p>Each of these reproduces a CICS screen program whose own input includes carried
         * communication-area members, which a five-field conversation state cannot express. The
         * services receive the record, read the carried members the legacy program reads, and rebuild
         * the record through its canonical constructor to hand the client's own view back unchanged -
         * which is the same pass-through the entitled boundary performs, performed in the one place the
         * screen program requires it. Two properties are asserted rather than assumed: every name here
         * must really name the record, so a service later reduced to a five-field state cannot leave a
         * dead licence behind; and no enrolled file may compare an echoed identity, only carry it.
         *
         * <p>Enrolling a file here is a layering decision and is recorded in
         * {@code docs/decision-log.md} alongside the matching entry for
         * {@code PackageLayeringTest.LICENSED_UPWARD_EDGES}. Nothing else may be added without the same
         * record.
         */
        private static final List<String> ENROLLED_SCREEN_SERVICE_FILE_NAMES = List.of(
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
         * finding nothing. Thirty-four lines carry one at the time of writing.
         */
        private static final int MINIMUM_CARRIED_IDENTITY_READS = 30;

        /**
         * The two packages entitled to name the wire record at all: the API layer, which is where the
         * boundary and the transport records live, and the configuration layer, which publishes the
         * record's schema and is the composition root.
         */
        private static final List<String> ENTITLED_PACKAGE_PATHS =
                List.of(Path.of("com", "carddemo", "api").toString(),
                        Path.of("com", "carddemo", "config").toString());

        /**
         * Reports whether a source file sits in a package entitled to name the wire record.
         *
         * @param path the production source path
         * @return {@code true} when the file sits under the API or configuration package
         */
        private static boolean entitledToNameTheWireRecord(final Path path) {
            final String asText = path.toString();
            for (final String entitled : ENTITLED_PACKAGE_PATHS) {
                if (asText.contains(entitled)) {
                    return true;
                }
            }
            return false;
        }

        @Test
        @DisplayName("only the API and configuration packages and the ten enrolled screen services "
                + "name the wire record, so no batch, repository, domain or utility class and no "
                + "unenrolled service can read an echoed member even in principle")
        void onlyTheEntitledPackagesAndTheEnrolledScreenServicesNameTheWireRecord() {
            // This is the structural half of the guarantee and the stronger half. A class that never
            // names NavigationContext has no receiver to read an echoed member off, so the question of
            // whether it trusts one cannot arise. It is asserted on the file text rather than on
            // imports because a fully qualified reference in a signature would evade an import check.
            final List<String> offenders = new ArrayList<>();
            final List<String> enrolledFilesFound = new ArrayList<>();
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (entitledToNameTheWireRecord(source.getKey())) {
                    continue;
                }
                if (!source.getValue().contains("NavigationContext")) {
                    continue;
                }
                if (ENROLLED_SCREEN_SERVICE_FILE_NAMES.contains(fileName)) {
                    enrolledFilesFound.add(fileName);
                    continue;
                }
                offenders.add(source.getKey().toString());
            }

            assertThat(offenders)
                    .as("a service that needs nothing but carried routing takes ConversationState, "
                            + "which models five routing and mode fields and none of the eleven echoed "
                            + "members; a service that reproduces a screen program reading carried "
                            + "communication-area members is licensed only by being named in "
                            + "ENROLLED_SCREEN_SERVICE_FILE_NAMES, which is where that layering "
                            + "decision is recorded")
                    .isEmpty();
            assertThat(enrolledFilesFound)
                    .as("every enrolled file must exist and must really name the wire record, so a "
                            + "service later reduced to a five-field state cannot leave a dead licence "
                            + "behind for an unrelated file to inherit")
                    .containsExactlyInAnyOrderElementsOf(ENROLLED_SCREEN_SERVICE_FILE_NAMES);
        }

        @Test
        @DisplayName("among the sources that do name the wire record, only this boundary and the ten "
                + "enrolled screen services read an echoed member, so no other file performs the "
                + "pass-through")
        void onlyTheBoundaryAndTheEnrolledScreenServicesReadAnEchoedMember() {
            // Scoped to the files that name the wire record, because a member accessor name can be a
            // homonym elsewhere: MenuOptionCatalog.UserMenuOption declares its own userType(), which is
            // the option's required role read from app/cpy/COMEN02Y.cpy and not an echoed claim. Only a
            // file holding a NavigationContext can read one off it, so only those files are searched.
            final List<String> offenders = new ArrayList<>();
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (ENTITLED_FILE_NAMES.contains(fileName)
                        || ENROLLED_SCREEN_SERVICE_FILE_NAMES.contains(fileName)
                        || !source.getValue().contains("NavigationContext")) {
                    continue;
                }
                for (final String member : ECHOED_MEMBER_NAMES) {
                    if (source.getValue().contains("." + member + "()")) {
                        offenders.add(fileName + " reads ." + member + "()");
                    }
                }
            }

            assertThat(offenders)
                    .as("the transport records declare the eleven members; only the boundary and the "
                            + "ten enrolled screen services read them, and each reads them to carry "
                            + "them through rather than to decide anything from them, which the "
                            + "identity guard below asserts for the enrolled ten")
                    .isEmpty();
        }

        @Test
        @DisplayName("the ten enrolled screen services only carry the echoed identity and never "
                + "compare it, so the licence to name the wire record is not a licence to authorize "
                + "from one")
        void theEnrolledScreenServicesCarryTheEchoedIdentityAndNeverCompareIt() {
            // This is what keeps the enrolment above from widening the trust boundary. The ten are
            // licensed to read carried members because the screen programs they reproduce read them,
            // but the two identity members are the security-bearing pair, and a carried read of either
            // is only ever an argument inside a copy of the record. A comparison sharing the line would
            // be a decision taken from a value the client supplied. Scoped to the carried-state
            // receivers so that a request DTO's own userId(), which those services validate and must be
            // free to compare, is not mistaken for an echoed claim.
            final List<String> offenders = new ArrayList<>();
            int carriedIdentityReads = 0;
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (!ENROLLED_SCREEN_SERVICE_FILE_NAMES.contains(fileName)) {
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
                    .as("the enrolled services must really carry the echoed identity, or this guard "
                            + "would pass by finding nothing at all")
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
                if (WIRE_RECORD_FILE_NAME.equals(source.getKey().getFileName().toString())) {
                    continue;
                }
                if (source.getValue().contains("echoesAdministratorCode()")) {
                    offenders.add(source.getKey().getFileName().toString());
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
        @DisplayName("declares no field, so the singleton is safe for unsynchronised concurrent use")
        void declaresNoField() {
            assertThat(ConversationStateAdapter.class.getDeclaredFields())
                    .as("a field here would make the boundary conversion stateful")
                    .isEmpty();
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
