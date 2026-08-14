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

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.BrowseWindow;
import com.carddemo.service.FieldErrorMarks;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenInputState;
import com.carddemo.service.ScreenNavigationState;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Unit tests for {@link ScreenStateAdapter}, the one boundary at which the four screen-contract wire
 * carriers and their service-owned counterparts are converted.
 *
 * <p><strong>What this file is really guarding.</strong> Five properties, each of which a plausible
 * "helpful" edit would break silently. That every conversion is a positional copy of the fourteen members a
 * client may echo: nothing trimmed, padded, defaulted, re-cased or reordered, because three of the four
 * carriers hold fixed-width blank-significant values and the fourth holds a page indicator whose leading
 * zeros are what the screen displayed. That the round trip is lossless in both directions, which is the only
 * reason the two type families may exist side by side. That the two identity members <em>are</em> reconciled
 * against the authenticated principal in both directions, because over HTTP the client holds the record the
 * legacy region held, so a caller could otherwise name somebody else simply by editing two fields it echoes
 * back. That the two pairs of routing nominations are screened against the destination vocabulary, so an
 * invented program name is carried as "nominates nothing" - the legacy's own empty-field case - rather than
 * reaching the navigation authority's unresolvable arm and surfacing as a terminal failure. And that absence
 * is handled asymmetrically on purpose: inbound, an absent wire record becomes the empty service-owned
 * carrier so no service has to null-check; outbound, an absent carrier stays absent so a response omits a
 * member the turn never produced.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * @since 1.0.0
 */
@DisplayName("ScreenStateAdapter :: the one boundary between the wire carriers and their service twins")
final class ScreenStateAdapterTest {

    /** The adapter under test. */
    private ScreenStateAdapter adapter;

    /** Creates the stateless adapter. */
    @BeforeEach
    void setUp() {
        adapter = new ScreenStateAdapter(new NavigationService());
    }

    /** A fully populated wire navigation record, with padding on values that carry it. */
    private static NavigationContext wireContext() {
        return new NavigationContext("CT00", "COTRN00C", "CT01", "COTRN01C", "USER0001", "U",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY ", " ANN", "SMITH",
                "00000000011", "Y", "4111111111111111", "COTRN0A", "COTRN00");
    }

    /** A fully populated wire work area. */
    private static ScreenWorkArea wireWorkArea() {
        return new ScreenWorkArea(KeyAction.PFK03, "COCRDLIC", "COCRDLI", "CCRDLIA", " an error ",
                " a return ", "00000000011", "4111111111111111", "000000011");
    }

    /**
     * The identity the filter chain establishes for these turns, deliberately naming the same principal the
     * wire record names so the positional assertions compare the crossing rather than the reconciliation.
     * The reconciliation itself is proved separately, by an identity that disagrees.
     */
    private static final Authentication IDENTITY = identityOf("USER0001", UserType.USER);

    /**
     * Builds an established identity carrying the single authority the chain grants for a user type.
     *
     * @param userId the principal name
     * @param userType the type whose declared authority is granted
     * @return an authenticated token the adapter can read identity from
     */
    private static Authentication identityOf(final String userId, final UserType userType) {
        return new TestingAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    @Nested
    @DisplayName("The adapter itself")
    class TheAdapterItself {

        @Test
        @DisplayName("is final and holds nothing but its own final collaborators, so the singleton is still "
                + "safe to share and still carries nothing between turns")
        void isFinalAndHoldsOnlyFinalCollaborators() {
            assertThat(Modifier.isFinal(ScreenStateAdapter.class.getModifiers())).isTrue();
            for (final Field field : ScreenStateAdapter.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                // Screening a nomination needs the destination vocabulary, so the adapter now holds one
                // collaborator. What must not appear is per-turn state: an injected collaborator that is
                // private and final cannot be reassigned between turns, so sharing one instance across
                // every concurrent request stays safe for exactly the reason it was safe before.
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("field %s must be private", field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final, so no turn can reassign it", field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .as("field %s must be an injected collaborator, not screen state", field.getName())
                        .isEqualTo(NavigationService.class);
            }
        }

        @Test
        @DisplayName("refuses an absent navigation authority, because an adapter that cannot screen a "
                + "nomination would pass an invented one straight through")
        void refusesAnAbsentNavigationAuthority() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ScreenStateAdapter(null))
                    .withMessageContaining("navigationService");
        }
    }

    @Nested
    @DisplayName("The navigation state")
    class TheNavigationState {

        @Test
        @DisplayName("copies all sixteen fields inbound, byte for byte and padding included")
        void copiesAllSixteenFieldsInbound() {
            final ScreenNavigationState state = adapter.toNavigationState(wireContext(), IDENTITY);

            assertThat(state.fromTransactionId()).isEqualTo("CT00");
            assertThat(state.fromProgram()).isEqualTo("COTRN00C");
            assertThat(state.toTransactionId()).isEqualTo("CT01");
            assertThat(state.toProgram()).isEqualTo("COTRN01C");
            assertThat(state.userId()).isEqualTo("USER0001");
            assertThat(state.userType()).isEqualTo("U");
            assertThat(state.programContext())
                    .isEqualTo(ScreenNavigationState.ProgramContext.REENTER);
            assertThat(state.customerId()).isEqualTo("000000011");
            assertThat(state.customerFirstName()).isEqualTo("MARY ");
            assertThat(state.customerMiddleName()).isEqualTo(" ANN");
            assertThat(state.customerLastName()).isEqualTo("SMITH");
            assertThat(state.accountId()).isEqualTo("00000000011");
            assertThat(state.accountStatus()).isEqualTo("Y");
            assertThat(state.cardNumber()).isEqualTo("4111111111111111");
            assertThat(state.lastMap()).isEqualTo("COTRN0A");
            assertThat(state.lastMapset()).isEqualTo("COTRN00");
        }

        @Test
        @DisplayName("round trips a populated record without losing or altering one field")
        void roundTripsAPopulatedRecord() {
            // The identity agrees with the one the record echoes, so reconciliation is a restatement here
            // and the round trip is observably lossless across all sixteen members.
            assertThat(adapter.toNavigationContext(
                    adapter.toNavigationState(wireContext(), IDENTITY), IDENTITY))
                    .isEqualTo(wireContext());
        }

        @Test
        @DisplayName("replaces the echoed identity with the authenticated one inbound, so a caller cannot "
                + "act as somebody else by editing the two identity fields of the record it echoes")
        void reconcilesTheEchoedIdentityInbound() {
            final ScreenNavigationState state = adapter.toNavigationState(wireContext(),
                    identityOf("ADMIN001", UserType.ADMIN));

            // The record names USER0001 as a standard user; the credential names ADMIN001 as an
            // administrator, and it is the credential that reaches the transaction.
            assertThat(state.userId()).isEqualTo("ADMIN001");
            assertThat(state.userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(state.agreesWith("ADMIN001", UserType.ADMIN)).isTrue();
            // Nothing else moved: the other fourteen members are still the echoed ones, byte for byte.
            assertThat(state.customerFirstName()).isEqualTo("MARY ");
            assertThat(state.customerMiddleName()).isEqualTo(" ANN");
            assertThat(state.accountId()).isEqualTo("00000000011");
            assertThat(state.lastMapset()).isEqualTo("COTRN00");
        }

        @Test
        @DisplayName("restates the authenticated identity outbound too, so a client-supplied identity "
                + "cannot come back looking as though the server had asserted it")
        void reconcilesTheIdentityOutbound() {
            final NavigationContext echoedBack = adapter.toNavigationContext(
                    new ScreenNavigationState("CT00", "COTRN00C", "CT01", "COTRN01C", "IMPOSTOR",
                            "A", ScreenNavigationState.ProgramContext.REENTER, "000000011", "MARY ",
                            " ANN", "SMITH", "00000000011", "Y", "4111111111111111", "COTRN0A",
                            "COTRN00"),
                    IDENTITY);

            assertThat(echoedBack.userId()).isEqualTo("USER0001");
            assertThat(echoedBack.userType()).isEqualTo(UserType.USER.getCode());
        }

        @Test
        @DisplayName("clears the identity members when no principal is established, rather than inventing "
                + "one, because an unauthenticated route has no identity to assert")
        void clearsTheIdentityWhenNoPrincipalIsEstablished() {
            final ScreenNavigationState state = adapter.toNavigationState(wireContext(), null);

            assertThat(state.userId()).isNull();
            assertThat(state.userType()).isNull();
            assertThat(state.accountId()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("clears the identity members when the established principal carries neither declared "
                + "authority, so an unrecognised authority cannot be read as a user type")
        void clearsTheTypeWhenNoDeclaredAuthorityIsGranted() {
            final ScreenNavigationState state = adapter.toNavigationState(wireContext(),
                    new TestingAuthenticationToken("USER0001", null,
                            List.of(new SimpleGrantedAuthority("ROLE_SOMETHING_ELSE"))));

            assertThat(state.userId()).isEqualTo("USER0001");
            assertThat(state.userType()).isNull();
        }

        @Test
        @DisplayName("turns an absent record into the reconciled empty state inbound and an absent state "
                + "into nothing outbound, so a service never null-checks and a response omits what was "
                + "never produced")
        void handlesAbsenceAsymmetrically() {
            assertThat(adapter.toNavigationState(null, IDENTITY))
                    .isEqualTo(ScreenNavigationState.empty()
                            .reconciledWith("USER0001", UserType.USER));
            assertThat(adapter.toNavigationContext(null, IDENTITY)).isNull();
        }

        @Test
        @DisplayName("carries an echoed nomination the estate declares straight through, both the program "
                + "name and the transaction identifier, because every reachable value still crosses")
        void carriesADeclaredNominationThrough() {
            final ScreenNavigationState state = adapter.toNavigationState(wireContext(), IDENTITY);

            assertThat(state.fromTransactionId()).isEqualTo("CT00");
            assertThat(state.fromProgram()).isEqualTo("COTRN00C");
            assertThat(state.toTransactionId()).isEqualTo("CT01");
            assertThat(state.toProgram()).isEqualTo("COTRN01C");
        }

        @Test
        @DisplayName("carries a nomination that names no destination as nothing, so an invented program "
                + "name becomes the legacy's own empty-field case instead of a terminal failure")
        void carriesAnUnresolvableNominationAsNothing() {
            final NavigationContext invented = new NavigationContext("ZZZZ", "NOTAPGM1", "QQQQ",
                    "ALSONOPE", "USER0001", "U", NavigationContext.ProgramContext.REENTER, null, null,
                    null, null, null, null, null, null, null);

            final ScreenNavigationState state = adapter.toNavigationState(invented, IDENTITY);

            assertThat(state.fromTransactionId()).isNull();
            assertThat(state.fromProgram()).isNull();
            assertThat(state.toTransactionId()).isNull();
            assertThat(state.toProgram()).isNull();
            // And the turn is still a turn: the reconciled identity and the re-entry gate both survive.
            assertThat(state.userId()).isEqualTo("USER0001");
            assertThat(state.reEntry()).isTrue();
        }

        @Test
        @DisplayName("leaves a blank nomination exactly as received, because a blank field is significant: "
                + "it is what makes the calling screen's default apply")
        void leavesABlankNominationAsReceived() {
            final NavigationContext blanked = new NavigationContext("    ", "        ", "", "\0\0\0\0",
                    "USER0001", "U", NavigationContext.ProgramContext.ENTER, null, null, null, null,
                    null, null, null, null, null);

            final ScreenNavigationState state = adapter.toNavigationState(blanked, IDENTITY);

            assertThat(state.fromTransactionId()).isEqualTo("    ");
            assertThat(state.fromProgram()).isEqualTo("        ");
            assertThat(state.toTransactionId()).isEmpty();
            assertThat(state.toProgram()).isEqualTo("\0\0\0\0");
        }

        @Test
        @DisplayName("preserves an absent program-context flag in both directions rather than inventing "
                + "the first-entry constant, so absence stays distinguishable")
        void preservesAnAbsentProgramContextFlag() {
            final NavigationContext withoutFlag = new NavigationContext("CT00", "COTRN00C", null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(adapter.toNavigationState(withoutFlag, null).programContext()).isNull();
            assertThat(adapter.toNavigationContext(
                    adapter.toNavigationState(withoutFlag, null), null))
                    .isEqualTo(withoutFlag);
        }

        @Test
        @DisplayName("maps the first-entry flag in both directions as well as the re-entry one")
        void mapsBothProgramContextConstants() {
            final NavigationContext entering = new NavigationContext(null, null, null, null, null, null,
                    NavigationContext.ProgramContext.ENTER, null, null, null, null, null, null, null,
                    null, null);

            assertThat(adapter.toNavigationState(entering, null).programContext())
                    .isEqualTo(ScreenNavigationState.ProgramContext.ENTER);
            assertThat(adapter.toNavigationContext(
                    adapter.toNavigationState(entering, null), null)
                    .programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
        }
    }

    @Nested
    @DisplayName("The authenticated identity readers")
    class TheAuthenticatedIdentityReaders {

        @Test
        @DisplayName("read the principal name and the granted user type, and answer nothing for an absent "
                + "principal, because reading an identity is not the same as requiring one")
        void readThePrincipalAndItsType() {
            assertThat(ScreenStateAdapter.authenticatedUserId(IDENTITY)).isEqualTo("USER0001");
            assertThat(ScreenStateAdapter.authenticatedUserType(IDENTITY)).isEqualTo(UserType.USER);
            assertThat(ScreenStateAdapter.authenticatedUserId(null)).isNull();
            assertThat(ScreenStateAdapter.authenticatedUserType(null)).isNull();
        }

        @Test
        @DisplayName("resolve the administrative authority as well as the standard one, since the chain "
                + "grants exactly one of the two")
        void resolveBothDeclaredAuthorities() {
            assertThat(ScreenStateAdapter.authenticatedUserType(identityOf("ADMIN001", UserType.ADMIN)))
                    .isEqualTo(UserType.ADMIN);
            assertThat(ScreenStateAdapter.authenticatedUserType(identityOf("USER0001", UserType.USER)))
                    .isEqualTo(UserType.USER);
        }

        @Test
        @DisplayName("answer nothing for an identity granted no authority at all, rather than guessing a "
                + "type from the principal name")
        void answerNothingForAnUngrantedIdentity() {
            assertThat(ScreenStateAdapter.authenticatedUserType(
                    new TestingAuthenticationToken("USER0001", null, List.of())))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("The screen input state")
    class TheScreenInputState {

        @Test
        @DisplayName("copies all nine members in both directions, message padding included, because the "
                + "message slots are part of the screen contract")
        void copiesAllNineMembersBothWays() {
            final ScreenInputState state = adapter.toInputState(wireWorkArea());

            assertThat(state.keyAction()).isEqualTo(KeyAction.PFK03);
            assertThat(state.nextProgram()).isEqualTo("COCRDLIC");
            assertThat(state.nextMapset()).isEqualTo("COCRDLI");
            assertThat(state.nextMap()).isEqualTo("CCRDLIA");
            assertThat(state.errorMessage()).isEqualTo(" an error ");
            assertThat(state.returnMessage()).isEqualTo(" a return ");
            assertThat(state.accountId()).isEqualTo("00000000011");
            assertThat(state.cardNumber()).isEqualTo("4111111111111111");
            assertThat(state.customerId()).isEqualTo("000000011");
            assertThat(adapter.toScreenWorkArea(state)).isEqualTo(wireWorkArea());
        }

        @Test
        @DisplayName("turns an absent work area into the empty state inbound and an absent state into "
                + "nothing outbound")
        void handlesAbsenceAsymmetrically() {
            assertThat(adapter.toInputState(null)).isEqualTo(ScreenInputState.empty());
            assertThat(adapter.toScreenWorkArea(null)).isNull();
        }

        @Test
        @DisplayName("keeps an unresolved attention key unresolved, because the legacy mapping has no "
                + "otherwise branch and no constant stands for one")
        void keepsAnUnresolvedAttentionKeyUnresolved() {
            final ScreenWorkArea noKey = new ScreenWorkArea(null, null, null, null, null, null, null,
                    null, null);

            assertThat(adapter.toInputState(noKey).attentionKey()).isEmpty();
            assertThat(adapter.toScreenWorkArea(adapter.toInputState(noKey)).keyAction()).isNull();
        }
    }

    @Nested
    @DisplayName("The browse window")
    class TheBrowseWindow {

        @Test
        @DisplayName("copies the seven paging components outbound and keeps the direction the service "
                + "assembled the page in")
        void copiesTheSevenComponentsOutbound() {
            final BrowseWindow backward = BrowseWindow.backward(BrowseWindow.CARD_LIST_PAGE_SIZE,
                    "4111111111111111", "4111111111111199", true, true, "00000002");

            final PageMetadata wire = adapter.toPageMetadata(backward);

            assertThat(wire).isNotNull();
            assertThat(wire.pageSize()).isEqualTo(BrowseWindow.CARD_LIST_PAGE_SIZE);
            assertThat(wire.previousCursorKey()).isEqualTo("4111111111111111");
            assertThat(wire.nextCursorKey()).isEqualTo("4111111111111199");
            assertThat(wire.direction()).isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(wire.hasMorePages()).isTrue();
            assertThat(wire.hasPreviousPages()).isTrue();
            assertThat(wire.displayedPageNumber())
                    .as("the indicator's leading zeros are what the screen displayed")
                    .isEqualTo("00000002");
        }

        @Test
        @DisplayName("maps the forward direction outbound as well as the backward one")
        void mapsTheForwardDirectionOutbound() {
            final PageMetadata wire = adapter.toPageMetadata(
                    BrowseWindow.forward(10, null, "0000000000000009", true, false, "1"));

            assertThat(wire).isNotNull();
            assertThat(wire.direction()).isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(wire.previousCursorKey()).isNull();
        }

        @Test
        @DisplayName("copies the inbound cursor request in both directions and preserves an absent "
                + "direction, because the first turn of a browse asks for neither")
        void copiesTheInboundCursorRequest() {
            final BrowseWindow.CursorRequest forward = adapter.toCursorRequest(
                    new PageMetadata.PageCursorRequest("first", "last",
                            PageMetadata.PagingDirection.FORWARD, null, false));
            final BrowseWindow.CursorRequest backward = adapter.toCursorRequest(
                    new PageMetadata.PageCursorRequest("first", "last",
                            PageMetadata.PagingDirection.BACKWARD, null, false));
            final BrowseWindow.CursorRequest neither =
                    adapter.toCursorRequest(new PageMetadata.PageCursorRequest(null, null, null, null, false));

            assertThat(forward).isNotNull();
            assertThat(forward.previousCursorKey()).isEqualTo("first");
            assertThat(forward.nextCursorKey()).isEqualTo("last");
            assertThat(forward.direction()).isEqualTo(BrowseWindow.PagingDirection.FORWARD);
            assertThat(backward).isNotNull();
            assertThat(backward.direction()).isEqualTo(BrowseWindow.PagingDirection.BACKWARD);
            assertThat(neither).isNotNull();
            assertThat(neither.direction()).isNull();
        }

        @Test
        @DisplayName("turns an absent window and an absent cursor request into nothing, so a turn that "
                + "assembled no page carries no paging member")
        void turnsAbsenceIntoNothing() {
            assertThat(adapter.toPageMetadata(null)).isNull();
            assertThat(adapter.toCursorRequest(null)).isNull();
        }
    }

    @Nested
    @DisplayName("The field marks")
    class TheFieldMarks {

        /** An accumulation carrying one blank mark and one rejected mark, in that order. */
        private static FieldErrorMarks marks() {
            return FieldErrorMarks.none()
                    .mark("accountStatus", "ACSTTUS", FieldErrorMarks.FlagState.BLANK)
                    .mark("creditLimit", "ACRDLIM", FieldErrorMarks.FlagState.NOT_OK);
        }

        @Test
        @DisplayName("converts to the wire decoration entry for entry, in marking sequence, keeping the "
                + "blank and not-ok states distinct")
        void convertsToTheWireDecorationInSequence() {
            final FieldErrorDecorator decoration = adapter.toFieldErrorDecorator(marks());

            assertThat(decoration.markedFields()).hasSize(2);
            assertThat(decoration.markedFields().get(0).field()).isEqualTo("accountStatus");
            assertThat(decoration.markedFields().get(0).bmsFieldId()).isEqualTo("ACSTTUS");
            assertThat(decoration.markedFields().get(0).flagState())
                    .isEqualTo(FieldErrorDecorator.FlagState.BLANK);
            assertThat(decoration.markedFields().get(1).field()).isEqualTo("creditLimit");
            assertThat(decoration.markedFields().get(1).flagState())
                    .isEqualTo(FieldErrorDecorator.FlagState.NOT_OK);
        }

        @Test
        @DisplayName("converts straight to the error contract's entries, mapping blank to missing and "
                + "not-ok to invalid, which is the distinction the legacy marker drew")
        void convertsStraightToTheErrorContract() {
            final List<ErrorResponse.FieldError> errors = adapter.toFieldErrors(marks());

            assertThat(errors).hasSize(2).isUnmodifiable();
            assertThat(errors.get(0).fieldName()).isEqualTo("accountStatus");
            assertThat(errors.get(0).screenFieldId()).isEqualTo("ACSTTUS");
            assertThat(errors.get(0).state()).isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(errors.get(0).message())
                    .as("the macro emitted no per-field text and none is invented")
                    .isNull();
            assertThat(errors.get(1).state()).isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("turns an absent accumulation into an empty decoration and an empty entry list, so a "
                + "first submission carries no field errors")
        void turnsAbsenceIntoAnEmptyDecoration() {
            assertThat(adapter.toFieldErrorDecorator(null).markedFields()).isEmpty();
            assertThat(adapter.toFieldErrorDecorator(FieldErrorMarks.none()).markedFields()).isEmpty();
            assertThat(adapter.toFieldErrors(null)).isEmpty();
            assertThat(adapter.toFieldErrors(FieldErrorMarks.none())).isEmpty();
        }
    }
}
