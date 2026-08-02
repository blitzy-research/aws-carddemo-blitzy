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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.NavigationContext.ProgramContext;
import com.carddemo.exception.AbendException;
import com.carddemo.service.NavigationService.Route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies the two navigation behaviours that must match the legacy rather than improve on it.
 *
 * <p><strong>The route vocabulary is derived, not invented.</strong> The resource definition file
 * registers eighteen transaction definitions and eighteen program definitions, and the vocabulary is
 * neither figure. {@code COCRDSEC} is defined as a program at
 * {@code [app/csd/CARDDEMO.CSD:L211]} with no source member anywhere in {@code app/cbl}, and the
 * developer transaction {@code CDV1} at {@code [app/csd/CARDDEMO.CSD:L388]} is bound to exactly that
 * dangling definition - so the transaction cannot have been dispatchable. Neither produces a
 * destination, which leaves seventeen. An earlier revision produced eighteen by substituting the
 * date-validation subprogram {@code CSUTLDTC} as the implementation of {@code CDV1}; that subprogram is
 * bound to no transaction, is named by no transfer-control statement and by no menu catalogue, and is
 * reached only by static {@code CALL} from four sites. {@link TheDerivedRouteVocabulary} holds the
 * vocabulary to seventeen and holds {@code CSUTLDTC} out of it.</p>
 *
 * <p><strong>An unresolvable transfer fails.</strong> A transfer-control statement naming a program the
 * region cannot resolve abends the task. {@link AnUnresolvableNomination} holds that behaviour, in place
 * of the earlier fallback that silently substituted the caller's own default. The untrusted-input
 * concern that motivated the fallback is addressed by bounding the echoed name to the legacy field
 * width, which is asserted here too - bounding what a diagnostic carries, without changing the
 * outcome.</p>
 */
@DisplayName("NavigationService - the derived route vocabulary and the unresolvable transfer")
class NavigationServiceParityTest {

    /** A destination that exists in the vocabulary, used as the caller's own default. */
    private static final Route CALLER_DEFAULT = Route.USER_MENU;

    /** The legacy program name of a destination that does exist. */
    private static final String KNOWN_PROGRAM = "COACTVWC";

    /** The date-validation subprogram, which is a call target and never a destination. */
    private static final String DATE_VALIDATION_SUBPROGRAM = "CSUTLDTC";

    /** The dangling program definition, which has no source member. */
    private static final String DANGLING_PROGRAM = "COCRDSEC";

    /** The developer transaction bound only to the dangling program definition. */
    private static final String DANGLING_TRANSACTION = "CDV1";

    /** The width of {@code CDEMO-FROM-PROGRAM} and {@code CDEMO-TO-PROGRAM}. */
    private static final int LEGACY_PROGRAM_NAME_WIDTH = 8;

    private NavigationService navigationService;

    @BeforeEach
    void setUp() {
        navigationService = new NavigationService();
    }

    /**
     * Builds a context nominating {@code fromProgram} for back navigation.
     *
     * <p>The record declares {@code @Size(max = 8)} on this component, but that is a declarative
     * constraint evaluated by a validator rather than a check performed by the canonical
     * constructor, and the record declares no compact constructor. An overlong name is therefore
     * constructible here, which is precisely the untrusted-input case the service must survive.</p>
     */
    private static NavigationContext contextFrom(String fromProgram) {
        return new NavigationContext("CAVW", fromProgram, null, null, "ADMIN001", "A",
                ProgramContext.ENTER, null, null, null, null, null, null, null, null, null);
    }

    /** Builds a context nominating {@code toProgram} as its destination. */
    private static NavigationContext contextTo(String toProgram) {
        return new NavigationContext("CAVW", "COACTVWC", "CAUP", toProgram, "ADMIN001", "A",
                ProgramContext.ENTER, null, null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("the derived route vocabulary")
    class TheDerivedRouteVocabulary {

        @Test
        @DisplayName("there are seventeen destinations, one per online program that both exists and "
                + "is bound to a transaction")
        void thereAreSeventeenDestinations() {
            assertThat(NavigationService.ROUTE_COUNT).isEqualTo(17);
            assertThat(Route.values()).hasSize(NavigationService.ROUTE_COUNT);
            assertThat(navigationService.routes()).hasSize(NavigationService.ROUTE_COUNT);
        }

        @Test
        @DisplayName("the date-validation subprogram is not a destination, because it is bound to no "
                + "transaction and reached only by CALL")
        void theDateValidationSubprogramIsNotADestination() {
            assertThat(navigationService.routes())
                    .extracting(Route::getLegacyProgramName)
                    .doesNotContain(DATE_VALIDATION_SUBPROGRAM);
            assertThat(navigationService.routeForLegacyProgram(DATE_VALIDATION_SUBPROGRAM))
                    .isEmpty();
        }

        @Test
        @DisplayName("the developer transaction bound to the dangling program is not a destination")
        void theDanglingTransactionIsNotADestination() {
            assertThat(navigationService.routes())
                    .extracting(Route::getLegacyTransactionId)
                    .doesNotContain(DANGLING_TRANSACTION);
            assertThat(navigationService.routeForLegacyTransactionId(DANGLING_TRANSACTION))
                    .isEmpty();
        }

        @Test
        @DisplayName("the dangling program definition is not a destination either")
        void theDanglingProgramIsNotADestination() {
            assertThat(navigationService.routes())
                    .extracting(Route::getLegacyProgramName)
                    .doesNotContain(DANGLING_PROGRAM);
            assertThat(navigationService.routeForLegacyProgram(DANGLING_PROGRAM)).isEmpty();
        }

        @Test
        @DisplayName("no destination carries a date-validation route value, so the invented "
                + "destination is gone from the wire vocabulary as well as from the enum")
        void noDateValidationRouteValueRemains() {
            assertThat(navigationService.routes())
                    .extracting(Route::getRouteValue)
                    .doesNotContain("date-validation");
            assertThat(navigationService.routeForValue("date-validation")).isEmpty();
        }

        @Test
        @DisplayName("the seventeen destinations name exactly the seventeen online programs of the "
                + "estate, each once")
        void theSeventeenDestinationsNameTheSeventeenOnlinePrograms() {
            List<String> programNames = navigationService.routes().stream()
                    .map(Route::getLegacyProgramName)
                    .toList();

            assertThat(programNames).doesNotHaveDuplicates().containsExactlyInAnyOrder(
                    "COSGN00C", "COMEN01C", "COADM01C", "COACTVWC", "COACTUPC",
                    "COCRDLIC", "COCRDSLC", "COCRDUPC", "COTRN00C", "COTRN01C",
                    "COTRN02C", "CORPT00C", "COBIL00C", "COUSR00C", "COUSR01C",
                    "COUSR02C", "COUSR03C");
        }

        @Test
        @DisplayName("every legacy program name fits the eight-byte communication-area field, and "
                + "every transaction identifier its four")
        void everyNameFitsItsLegacyField() {
            assertThat(navigationService.routes())
                    .allSatisfy(route -> {
                        assertThat(route.getLegacyProgramName())
                                .hasSizeLessThanOrEqualTo(LEGACY_PROGRAM_NAME_WIDTH);
                        assertThat(route.getLegacyTransactionId()).hasSize(4);
                    });
        }
    }

    @Nested
    @DisplayName("an unresolvable nomination")
    class AnUnresolvableNomination {

        @Test
        @DisplayName("back navigation to an unknown program abends rather than applying the "
                + "caller's default")
        void backNavigationToAnUnknownProgramAbends() {
            NavigationContext context = contextFrom("CONOSUCH");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> navigationService.resolveBackNavigation(context,
                            CALLER_DEFAULT));
        }

        @Test
        @DisplayName("a nominated destination naming an unknown program abends rather than applying "
                + "the caller's default")
        void aNominatedDestinationNamingAnUnknownProgramAbends() {
            NavigationContext context = contextTo("CONOSUCH");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> navigationService.resolveNominatedDestination(context,
                            CALLER_DEFAULT));
        }

        @Test
        @DisplayName("the abend carries the online abend code, the offending program as its culprit "
                + "and an unresolvable-program reason")
        void theAbendCarriesTheOffendingProgram() {
            NavigationContext context = contextFrom("CONOSUCH");

            AbendException abend = catchAbend(
                    () -> navigationService.resolveBackNavigation(context, CALLER_DEFAULT));

            assertThat(abend.code()).isEqualTo(AbendException.ONLINE_ABEND_CODE);
            assertThat(abend.culprit()).isEqualTo("CONOSUCH");
            assertThat(abend.reason()).contains("UNRESOLVABLE");
            assertThat(abend.getMessage()).contains("back-navigation");
        }

        @Test
        @DisplayName("the rule that failed is identified, so back navigation and nominated "
                + "destination are distinguishable in a diagnostic")
        void theFailingRuleIsIdentified() {
            AbendException fromBack = catchAbend(() -> navigationService
                    .resolveBackNavigation(contextFrom("CONOSUCH"), CALLER_DEFAULT));
            AbendException fromNominated = catchAbend(() -> navigationService
                    .resolveNominatedDestination(contextTo("CONOSUCH"), CALLER_DEFAULT));

            assertThat(fromBack.getMessage()).contains("back-navigation");
            assertThat(fromNominated.getMessage()).contains("nominated-destination");
        }

        @Test
        @DisplayName("the date-validation subprogram is not navigable, so nominating it abends - the "
                + "invented destination is unreachable by name as well as absent from the vocabulary")
        void nominatingTheDateValidationSubprogramAbends() {
            NavigationContext context = contextFrom(DATE_VALIDATION_SUBPROGRAM);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> navigationService.resolveBackNavigation(context,
                            CALLER_DEFAULT));
        }

        @Test
        @DisplayName("an echoed name longer than the legacy field is truncated to that field's "
                + "width before it reaches the abend, and still abends")
        void anOverlongEchoedNameIsBoundedAndStillAbends() {
            String overlong = "COACTVWCXXXXXXXXXXXXXXXX";
            assertThat(overlong).hasSizeGreaterThan(LEGACY_PROGRAM_NAME_WIDTH);

            AbendException abend = catchAbend(() -> navigationService
                    .resolveBackNavigation(contextFrom(overlong), CALLER_DEFAULT));

            assertThat(abend.culprit())
                    .hasSize(LEGACY_PROGRAM_NAME_WIDTH)
                    .isEqualTo(overlong.substring(0, LEGACY_PROGRAM_NAME_WIDTH));
        }

        @Test
        @DisplayName("truncation bounds the diagnostic and never the outcome: a name whose leading "
                + "eight bytes do name a destination still abends, because the whole name did not")
        void truncationDoesNotRescueAnUnresolvableName() {
            NavigationContext context = contextFrom(KNOWN_PROGRAM + "TRAILING");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> navigationService.resolveBackNavigation(context,
                            CALLER_DEFAULT));
        }

        @Test
        @DisplayName("the fixed-width abend context is still assembled, so an overlong echoed name "
                + "cannot break the diagnostic it appears in")
        void theFixedWidthAbendContextIsStillAssembled() {
            AbendException abend = catchAbend(() -> navigationService
                    .resolveBackNavigation(contextFrom("Z".repeat(64)), CALLER_DEFAULT));

            assertThat(abend.toFixedWidthContext())
                    .hasSize(AbendException.CONTEXT_LENGTH);
        }

        private AbendException catchAbend(Runnable action) {
            try {
                action.run();
            } catch (AbendException abend) {
                return abend;
            }
            throw new AssertionError("expected an AbendException, but none was raised");
        }
    }

    @Nested
    @DisplayName("the nomination rules that are unchanged")
    class TheNominationRulesThatAreUnchanged {

        @Test
        @DisplayName("a blank originating-program field nominates nothing, so the caller's own "
                + "default applies - this is the legacy's own behaviour and is untouched")
        void aBlankFieldStillYieldsTheCallerDefault() {
            assertThat(navigationService.resolveBackNavigation(contextFrom("        "),
                    CALLER_DEFAULT)).isEqualTo(CALLER_DEFAULT);
            assertThat(navigationService.resolveBackNavigation(contextFrom(null),
                    CALLER_DEFAULT)).isEqualTo(CALLER_DEFAULT);
        }

        @Test
        @DisplayName("a field naming a reachable destination still yields that destination rather "
                + "than the caller's default")
        void aKnownFieldStillYieldsItsDestination() {
            Route resolved = navigationService.resolveBackNavigation(contextFrom(KNOWN_PROGRAM),
                    CALLER_DEFAULT);

            assertThat(resolved.getLegacyProgramName()).isEqualTo(KNOWN_PROGRAM);
            assertThat(resolved).isNotEqualTo(CALLER_DEFAULT);
        }

        @Test
        @DisplayName("the destination-program field follows the identical rule, reading its own "
                + "communication-area field")
        void theDestinationFieldFollowsTheIdenticalRule() {
            assertThat(navigationService.resolveNominatedDestination(contextTo(null),
                    CALLER_DEFAULT)).isEqualTo(CALLER_DEFAULT);
            assertThat(navigationService.resolveNominatedDestination(contextTo(KNOWN_PROGRAM),
                    CALLER_DEFAULT).getLegacyProgramName()).isEqualTo(KNOWN_PROGRAM);
        }
    }
}
