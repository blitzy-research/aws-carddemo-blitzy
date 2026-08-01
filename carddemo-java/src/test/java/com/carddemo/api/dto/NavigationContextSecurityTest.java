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
package com.carddemo.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.domain.enums.UserType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link NavigationContext}, the Java realisation of the {@code CARDDEMO-COMMAREA}
 * structure declared by copybook member {@code COCOM01Y} - the state that all seventeen online programs
 * carried between pseudo-conversational turns, and whose seventeen textual inclusions collapse into this
 * one type.
 *
 * <h2>Three predicates that look interchangeable and are not</h2>
 *
 * <p>The administrator predicate, the first-entry predicate and the re-entry predicate all answer a
 * boolean, and all three have a specific asymmetry that a plausible implementation would get wrong.</p>
 *
 * <p>The administrator predicate is {@code true} for the administrator code <em>only</em>. An absent code
 * and an undeclared code are both {@code false}, because the legacy program tested one condition and let
 * an unconditional alternative catch everything else. So "not administrator" is not "user"; it is
 * "anything other than administrator".</p>
 *
 * <p>The entry predicates are the sharper case: an <em>absent</em> program context is a first entry, not
 * an unknown state, because sign-on establishes the first-entry value before handing control onward. And
 * the re-entry predicate is the gate on field-level error decoration, so getting the absent case backwards
 * would make a first submission report per-field errors that the legacy screen suppressed.</p>
 *
 * <h2>Redaction across six of the sixteen components</h2>
 *
 * <p>Six components carry identity or cardholder data: the customer identifier, three name parts, the
 * account identifier and the card number. Each is replaced in the rendering by a fixed placeholder. The
 * account <em>status</em> is deliberately retained - it is a single-character state code, not identity -
 * and that retained/withheld split is asserted in both directions so a later edit cannot quietly move a
 * component from one side to the other.</p>
 */
@DisplayName("NavigationContext - the COCOM01Y communication area")
class NavigationContextSecurityTest {

    private static final String FROM_TRANSACTION_ID = "CC00";
    private static final String FROM_PROGRAM = "COSGN00C";
    private static final String TO_TRANSACTION_ID = "CM00";
    private static final String TO_PROGRAM = "COMEN01C";
    private static final String USER_ID = "ADMIN001";
    private static final String CUSTOMER_ID = "000000011";
    private static final String CUSTOMER_FIRST_NAME = "MARY";
    private static final String CUSTOMER_MIDDLE_NAME = "ANN";
    private static final String CUSTOMER_LAST_NAME = "SMITH";
    private static final String ACCOUNT_ID = "00000000011";
    private static final String ACCOUNT_STATUS = "Y";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String LAST_MAP = "CACTUPA";
    private static final String LAST_MAPSET = "CACTUPS";

    /** A fully populated context carrying an administrator on a re-entry turn. */
    private static NavigationContext context() {
        return context("A", NavigationContext.ProgramContext.REENTER);
    }

    /** A fully populated context with the supplied user-type code and program context. */
    private static NavigationContext context(final String userType,
            final NavigationContext.ProgramContext programContext) {
        return new NavigationContext(FROM_TRANSACTION_ID, FROM_PROGRAM, TO_TRANSACTION_ID,
                TO_PROGRAM, USER_ID, userType, programContext, CUSTOMER_ID, CUSTOMER_FIRST_NAME,
                CUSTOMER_MIDDLE_NAME, CUSTOMER_LAST_NAME, ACCOUNT_ID, ACCOUNT_STATUS,
                CARD_NUMBER, LAST_MAP, LAST_MAPSET);
    }

    @Nested
    @DisplayName("The declared widths, taken straight from the copybook")
    class DeclaredWidths {

        @Test
        @DisplayName("the ten published widths match the copybook: 4, 8, 8, 1, 9, 25, 11, 1, 16 and 7")
        void theTenPublishedWidthsMatchTheCopybook() {
            assertThat(NavigationContext.TRANSACTION_ID_LENGTH).isEqualTo(4);
            assertThat(NavigationContext.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(NavigationContext.USER_ID_LENGTH).isEqualTo(8);
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(1);
            assertThat(NavigationContext.CUSTOMER_ID_LENGTH).isEqualTo(9);
            assertThat(NavigationContext.CUSTOMER_NAME_LENGTH).isEqualTo(25);
            assertThat(NavigationContext.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH).isEqualTo(1);
            assertThat(NavigationContext.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MAP_NAME_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the fixtures sit at the declared widths for the key identifiers")
        void theFixturesSitAtTheDeclaredWidths() {
            assertThat(FROM_TRANSACTION_ID).hasSize(NavigationContext.TRANSACTION_ID_LENGTH);
            assertThat(FROM_PROGRAM).hasSize(NavigationContext.PROGRAM_NAME_LENGTH);
            assertThat(USER_ID).hasSize(NavigationContext.USER_ID_LENGTH);
            assertThat(CUSTOMER_ID).hasSize(NavigationContext.CUSTOMER_ID_LENGTH);
            assertThat(ACCOUNT_ID).hasSize(NavigationContext.ACCOUNT_ID_LENGTH);
            assertThat(ACCOUNT_STATUS).hasSize(NavigationContext.ACCOUNT_STATUS_LENGTH);
            assertThat(CARD_NUMBER).hasSize(NavigationContext.CARD_NUMBER_LENGTH);
            assertThat(LAST_MAP).hasSize(NavigationContext.MAP_NAME_LENGTH);
            assertThat(LAST_MAPSET).hasSize(NavigationContext.MAP_NAME_LENGTH);
        }
    }

    @Nested
    @DisplayName("Construction and the wholly empty state")
    class Construction {

        @Test
        @DisplayName("all sixteen components land on their own accessors, so none of the fourteen same-typed string "
                + "arguments is transposed")
        void allSixteenComponentsLandOnTheirOwnAccessors() {
            final NavigationContext subject = context();
            assertThat(subject.fromTransactionId()).isEqualTo(FROM_TRANSACTION_ID);
            assertThat(subject.fromProgram()).isEqualTo(FROM_PROGRAM);
            assertThat(subject.toTransactionId()).isEqualTo(TO_TRANSACTION_ID);
            assertThat(subject.toProgram()).isEqualTo(TO_PROGRAM);
            assertThat(subject.userId()).isEqualTo(USER_ID);
            assertThat(subject.userType()).isEqualTo("A");
            assertThat(subject.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(subject.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(subject.customerFirstName()).isEqualTo(CUSTOMER_FIRST_NAME);
            assertThat(subject.customerMiddleName()).isEqualTo(CUSTOMER_MIDDLE_NAME);
            assertThat(subject.customerLastName()).isEqualTo(CUSTOMER_LAST_NAME);
            assertThat(subject.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(subject.accountStatus()).isEqualTo(ACCOUNT_STATUS);
            assertThat(subject.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(subject.lastMap()).isEqualTo(LAST_MAP);
            assertThat(subject.lastMapset()).isEqualTo(LAST_MAPSET);
        }

        @Test
        @DisplayName("the empty state has all sixteen components absent, which is a real legacy state rather than a "
                + "placeholder: a program entered with an empty area routes to sign-on unconditionally")
        void theEmptyStateHasAllSixteenComponentsAbsent() {
            final NavigationContext subject = NavigationContext.empty();
            assertThat(subject.fromTransactionId()).isNull();
            assertThat(subject.fromProgram()).isNull();
            assertThat(subject.toTransactionId()).isNull();
            assertThat(subject.toProgram()).isNull();
            assertThat(subject.userId()).isNull();
            assertThat(subject.userType()).isNull();
            assertThat(subject.programContext()).isNull();
            assertThat(subject.customerId()).isNull();
            assertThat(subject.customerFirstName()).isNull();
            assertThat(subject.customerMiddleName()).isNull();
            assertThat(subject.customerLastName()).isNull();
            assertThat(subject.accountId()).isNull();
            assertThat(subject.accountStatus()).isNull();
            assertThat(subject.cardNumber()).isNull();
            assertThat(subject.lastMap()).isNull();
            assertThat(subject.lastMapset()).isNull();
        }

        @Test
        @DisplayName("the empty state allocates nothing per call: the same instance is handed back, which is safe "
                + "because the type is deeply immutable")
        void theEmptyStateAllocatesNothingPerCall() {
            assertThat(NavigationContext.empty()).isSameAs(NavigationContext.empty());
        }

        @Test
        @DisplayName("the empty state answers first entry, matching a program context that has not been set to the "
                + "re-entry value")
        void theEmptyStateAnswersFirstEntry() {
            assertThat(NavigationContext.empty().firstEntry()).isTrue();
            assertThat(NavigationContext.empty().reEntry()).isFalse();
        }

        @Test
        @DisplayName("the empty state carries no resolved user type and is not an administrator")
        void theEmptyStateCarriesNoResolvedUserType() {
            assertThat(NavigationContext.empty().resolvedUserType()).isEmpty();
            assertThat(NavigationContext.empty().echoesAdministratorCode()).isFalse();
        }
    }

    @Nested
    @DisplayName("Resolving the raw user-type code, which never throws")
    class UserTypeResolution {

        @ParameterizedTest
        @EnumSource(UserType.class)
        @DisplayName("every declared user-type code resolves to its own type")
        void everyDeclaredCodeResolves(final UserType expected) {
            final NavigationContext subject =
                    context(expected.getCode(), NavigationContext.ProgramContext.ENTER);
            assertThat(subject.resolvedUserType()).contains(expected);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " ", "a", "u", "X", "AA", "0", "  A"})
        @DisplayName("an absent, blank, re-cased or undeclared code resolves to nothing rather than throwing, so a "
                + "corrupt echoed area cannot fail a request with a parse error")
        void anUndeclaredCodeResolvesToNothing(final String code) {
            final NavigationContext subject =
                    context(code, NavigationContext.ProgramContext.ENTER);
            assertThat(subject.resolvedUserType()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The echoed-administrator-code predicate, true for exactly one code and authorizing nothing")
    class AdministratorPredicate {

        @Test
        @DisplayName("the administrator code answers true")
        void theAdministratorCodeAnswersTrue() {
            assertThat(context("A", NavigationContext.ProgramContext.ENTER).echoesAdministratorCode())
                    .isTrue();
        }

        @Test
        @DisplayName("the standard-user code answers false")
        void theStandardUserCodeAnswersFalse() {
            assertThat(context("U", NavigationContext.ProgramContext.ENTER).echoesAdministratorCode())
                    .isFalse();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " ", "a", "u", "X", "AA", "0"})
        @DisplayName("every other outcome, including an absent code and an undeclared one, answers false - which is "
                + "exactly what the legacy program's unconditional alternative encodes, so NOT administrator is not "
                + "the same as user")
        void everyOtherOutcomeAnswersFalse(final String code) {
            assertThat(context(code, NavigationContext.ProgramContext.ENTER).echoesAdministratorCode())
                    .isFalse();
        }

        @Test
        @DisplayName("a lower-case administrator code answers false, because the legacy classification was a "
                + "byte comparison and no case folding may creep into it")
        void aLowerCaseAdministratorCodeAnswersFalse() {
            assertThat(context("a", NavigationContext.ProgramContext.ENTER).echoesAdministratorCode())
                    .isFalse();
        }

        @Test
        @DisplayName("the predicate agrees with the vocabulary's own administrator test, so the two cannot drift")
        void thePredicateAgreesWithTheVocabulary() {
            for (final UserType type : UserType.values()) {
                assertThat(context(type.getCode(), NavigationContext.ProgramContext.ENTER)
                        .echoesAdministratorCode()).isEqualTo(type.isAdmin());
            }
        }
    }

    @Nested
    @DisplayName("The two entry predicates, whose absent case is the sharp one")
    class EntryPredicates {

        @Test
        @DisplayName("the re-entry state answers re-entry and not first entry")
        void theReEntryStateAnswersReEntry() {
            final NavigationContext subject = context();
            assertThat(subject.reEntry()).isTrue();
            assertThat(subject.firstEntry()).isFalse();
        }

        @Test
        @DisplayName("the first-entry state answers first entry and not re-entry")
        void theFirstEntryStateAnswersFirstEntry() {
            final NavigationContext subject =
                    context("A", NavigationContext.ProgramContext.ENTER);
            assertThat(subject.firstEntry()).isTrue();
            assertThat(subject.reEntry()).isFalse();
        }

        @Test
        @DisplayName("an ABSENT program context answers first entry, because sign-on establishes the first-entry "
                + "value before handing control onward - getting this backwards would make a first submission report "
                + "per-field errors the legacy screen suppressed")
        void anAbsentProgramContextAnswersFirstEntry() {
            final NavigationContext subject = context("A", null);
            assertThat(subject.programContext()).isNull();
            assertThat(subject.firstEntry()).isTrue();
            assertThat(subject.reEntry()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(NavigationContext.ProgramContext.class)
        @DisplayName("the two predicates are exact complements for every declared context")
        void theTwoPredicatesAreExactComplements(
                final NavigationContext.ProgramContext programContext) {
            final NavigationContext subject = context("A", programContext);
            assertThat(subject.firstEntry()).isNotEqualTo(subject.reEntry());
        }

        @Test
        @DisplayName("the two predicates are exact complements when the context is absent too")
        void theTwoPredicatesAreComplementsWhenTheContextIsAbsent() {
            final NavigationContext subject = context("A", null);
            assertThat(subject.firstEntry()).isNotEqualTo(subject.reEntry());
        }

        @Test
        @DisplayName("the context vocabulary has exactly two constants, matching the two condition names the "
                + "copybook declares, and exposes no raw digit")
        void theContextVocabularyHasExactlyTwoConstants() {
            assertThat(NavigationContext.ProgramContext.values()).hasSize(2);
            assertThat(NavigationContext.ProgramContext.values())
                    .containsExactly(NavigationContext.ProgramContext.ENTER,
                            NavigationContext.ProgramContext.REENTER);
            assertThat(NavigationContext.ProgramContext.ENTER.name()).isEqualTo("ENTER");
            assertThat(NavigationContext.ProgramContext.REENTER.name()).isEqualTo("REENTER");
        }
    }

    @Nested
    @DisplayName("The two named derivations, which carry fifteen components across byte for byte")
    class ContextDerivations {

        @Test
        @DisplayName("marking a first entry sets the context and changes nothing else")
        void markingAFirstEntrySetsTheContextAndNothingElse() {
            final NavigationContext before = context();
            final NavigationContext after = before.withFirstEntry();
            assertThat(after.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(after.firstEntry()).isTrue();
            assertThat(after.fromTransactionId()).isEqualTo(before.fromTransactionId());
            assertThat(after.fromProgram()).isEqualTo(before.fromProgram());
            assertThat(after.toTransactionId()).isEqualTo(before.toTransactionId());
            assertThat(after.toProgram()).isEqualTo(before.toProgram());
            assertThat(after.userId()).isEqualTo(before.userId());
            assertThat(after.userType()).isEqualTo(before.userType());
            assertThat(after.customerId()).isEqualTo(before.customerId());
            assertThat(after.customerFirstName()).isEqualTo(before.customerFirstName());
            assertThat(after.customerMiddleName()).isEqualTo(before.customerMiddleName());
            assertThat(after.customerLastName()).isEqualTo(before.customerLastName());
            assertThat(after.accountId()).isEqualTo(before.accountId());
            assertThat(after.accountStatus()).isEqualTo(before.accountStatus());
            assertThat(after.cardNumber()).isEqualTo(before.cardNumber());
            assertThat(after.lastMap()).isEqualTo(before.lastMap());
            assertThat(after.lastMapset()).isEqualTo(before.lastMapset());
        }

        @Test
        @DisplayName("marking a re-entry sets the context and changes nothing else")
        void markingAReEntrySetsTheContextAndNothingElse() {
            final NavigationContext before =
                    context("U", NavigationContext.ProgramContext.ENTER);
            final NavigationContext after = before.withReEntry();
            assertThat(after.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(after.reEntry()).isTrue();
            assertThat(after.userId()).isEqualTo(USER_ID);
            assertThat(after.userType()).isEqualTo("U");
            assertThat(after.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(after.lastMapset()).isEqualTo(LAST_MAPSET);
        }

        @Test
        @DisplayName("the receiver is untouched by either derivation, so a state already handed to a caller cannot "
                + "change under it")
        void theReceiverIsUntouchedByEitherDerivation() {
            final NavigationContext before =
                    context("A", NavigationContext.ProgramContext.ENTER);
            before.withReEntry();
            assertThat(before.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(before.firstEntry()).isTrue();
        }

        @Test
        @DisplayName("the two derivations are inverses of each other over the context component alone")
        void theTwoDerivationsAreInversesOverTheContext() {
            final NavigationContext original =
                    context("A", NavigationContext.ProgramContext.ENTER);
            assertThat(original.withReEntry().withFirstEntry()).isEqualTo(original);
            assertThat(original.withFirstEntry()).isEqualTo(original);
        }

        @Test
        @DisplayName("a derivation applied to an already-marked state is idempotent")
        void aDerivationAppliedTwiceIsIdempotent() {
            final NavigationContext subject = context();
            assertThat(subject.withReEntry()).isEqualTo(subject.withReEntry().withReEntry());
            assertThat(subject.withFirstEntry())
                    .isEqualTo(subject.withFirstEntry().withFirstEntry());
        }

        @Test
        @DisplayName("a derivation on the empty state promotes only the context, leaving the other fifteen absent")
        void aDerivationOnTheEmptyStatePromotesOnlyTheContext() {
            final NavigationContext derived = NavigationContext.empty().withReEntry();
            assertThat(derived.reEntry()).isTrue();
            assertThat(derived.userId()).isNull();
            assertThat(derived.accountId()).isNull();
            assertThat(derived.lastMapset()).isNull();
            assertThat(derived).isNotEqualTo(NavigationContext.empty());
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering: navigation state retained, identity withheld")
    class DiagnosticRendering {

        @Test
        @DisplayName("the navigation components are retained, because they are what makes this state worth rendering")
        void theNavigationComponentsAreRetained() {
            final String rendering = context().toString();
            assertThat(rendering)
                    .startsWith("NavigationContext[")
                    .contains("fromTransactionId=" + FROM_TRANSACTION_ID)
                    .contains("fromProgram=" + FROM_PROGRAM)
                    .contains("toTransactionId=" + TO_TRANSACTION_ID)
                    .contains("toProgram=" + TO_PROGRAM)
                    .contains("userId=" + USER_ID)
                    .contains("userType=A")
                    .contains("programContext=REENTER")
                    .contains("lastMap=" + LAST_MAP)
                    .contains("lastMapset=" + LAST_MAPSET)
                    .endsWith("]");
        }

        @Test
        @DisplayName("the account STATUS is retained, because a single-character state code is not identity - the "
                + "retained side of the split is asserted so a later edit cannot quietly move a component")
        void theAccountStatusIsRetained() {
            assertThat(context().toString()).contains("accountStatus=" + ACCOUNT_STATUS);
        }

        @Test
        @DisplayName("none of the six identity or cardholder components appears in the rendering")
        void noneOfTheSixIdentityComponentsAppears() {
            final String rendering = context().toString();
            assertThat(rendering)
                    .doesNotContain(CUSTOMER_ID)
                    .doesNotContain(CUSTOMER_FIRST_NAME)
                    .doesNotContain(CUSTOMER_MIDDLE_NAME)
                    .doesNotContain(CUSTOMER_LAST_NAME)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("4111");
        }

        @Test
        @DisplayName("each withheld component is replaced by a fixed placeholder")
        void eachWithheldComponentIsReplacedByAFixedPlaceholder() {
            final String rendering = context().toString();
            assertThat(rendering)
                    .contains("customerId=***REDACTED***")
                    .contains("customerFirstName=***REDACTED***")
                    .contains("customerMiddleName=***REDACTED***")
                    .contains("customerLastName=***REDACTED***")
                    .contains("accountId=***REDACTED***")
                    .contains("cardNumber=***REDACTED***");
        }

        @Test
        @DisplayName("the placeholder is constant across differing values, which rules out a partial mask or a "
                + "digest - nothing about a withheld value, not even its length, survives")
        void thePlaceholderIsConstantAcrossDifferingValues() {
            final NavigationContext shortValues = new NavigationContext(FROM_TRANSACTION_ID,
                    FROM_PROGRAM, TO_TRANSACTION_ID, TO_PROGRAM, USER_ID, "A",
                    NavigationContext.ProgramContext.ENTER, "1", "A", "B", "C", "2",
                    ACCOUNT_STATUS, "3", LAST_MAP, LAST_MAPSET);
            final NavigationContext longValues = new NavigationContext(FROM_TRANSACTION_ID,
                    FROM_PROGRAM, TO_TRANSACTION_ID, TO_PROGRAM, USER_ID, "A",
                    NavigationContext.ProgramContext.ENTER, "999999999", "AAAAAAAAAA",
                    "BBBBBBBBBB", "CCCCCCCCCC", "99999999999", ACCOUNT_STATUS,
                    "9999999999999999", LAST_MAP, LAST_MAPSET);
            assertThat(shortValues.toString()).isEqualTo(longValues.toString());
        }

        @Test
        @DisplayName("a hostile value planted in a withheld component cannot reach the rendering")
        void aHostileValuePlantedInAWithheldComponentCannotReachTheRendering() {
            final NavigationContext subject = new NavigationContext(FROM_TRANSACTION_ID,
                    FROM_PROGRAM, TO_TRANSACTION_ID, TO_PROGRAM, USER_ID, "A",
                    NavigationContext.ProgramContext.ENTER, "CANARY-CU", "CANARY-FIRST",
                    "CANARY-MID", "CANARY-LAST", "CANARY-ACCT", ACCOUNT_STATUS,
                    "CANARY-CARD-NUM-", LAST_MAP, LAST_MAPSET);
            assertThat(subject.toString()).doesNotContain("CANARY");
        }

        @Test
        @DisplayName("the empty state renders without throwing")
        void theEmptyStateRendersWithoutThrowing() {
            assertThat(NavigationContext.empty().toString())
                    .startsWith("NavigationContext[")
                    .contains("programContext=null")
                    .contains("customerId=***REDACTED***")
                    .endsWith("]");
        }
    }

    @Nested
    @DisplayName("Value semantics, which the record contract generates and the redaction does not disturb")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component, including the six the rendering withholds")
        void equalityComparesEveryComponentIncludingTheWithheldOnes() {
            assertThat(context()).isEqualTo(context()).hasSameHashCodeAs(context());
            final NavigationContext differentCard = new NavigationContext(FROM_TRANSACTION_ID,
                    FROM_PROGRAM, TO_TRANSACTION_ID, TO_PROGRAM, USER_ID, "A",
                    NavigationContext.ProgramContext.REENTER, CUSTOMER_ID, CUSTOMER_FIRST_NAME,
                    CUSTOMER_MIDDLE_NAME, CUSTOMER_LAST_NAME, ACCOUNT_ID, ACCOUNT_STATUS,
                    "5555555555554444", LAST_MAP, LAST_MAPSET);
            assertThat(context()).isNotEqualTo(differentCard);
        }

        @Test
        @DisplayName("two states that render identically can still be unequal, which is why the rendering must never "
                + "be used as an equality proxy")
        void twoStatesThatRenderIdenticallyCanStillBeUnequal() {
            final NavigationContext left = new NavigationContext(null, null, null, null, null,
                    null, null, "1", null, null, null, null, null, null, null, null);
            final NavigationContext right = new NavigationContext(null, null, null, null, null,
                    null, null, "2", null, null, null, null, null, null, null, null);
            assertThat(left.toString()).isEqualTo(right.toString());
            assertThat(left).isNotEqualTo(right);
        }

        @Test
        @DisplayName("the program context participates in equality individually")
        void theProgramContextParticipatesIndividually() {
            assertThat(context("A", NavigationContext.ProgramContext.ENTER))
                    .isNotEqualTo(context("A", NavigationContext.ProgramContext.REENTER));
        }

        @Test
        @DisplayName("the user-type code participates in equality individually")
        void theUserTypeCodeParticipatesIndividually() {
            assertThat(context("A", NavigationContext.ProgramContext.ENTER))
                    .isNotEqualTo(context("U", NavigationContext.ProgramContext.ENTER));
        }

        @Test
        @DisplayName("a state is not equal to null and not equal to a foreign type")
        void aStateIsNotEqualToNullOrAForeignType() {
            assertThat(context()).isNotEqualTo(null);
            assertThat(context().equals(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("a hand-built all-null state equals the named empty state, so the two ways of expressing "
                + "nothing-carried-yet agree")
        void aHandBuiltAllNullStateEqualsTheNamedEmptyState() {
            final NavigationContext handBuilt = new NavigationContext(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(handBuilt).isEqualTo(NavigationContext.empty())
                    .hasSameHashCodeAs(NavigationContext.empty());
        }
    }
    /**
     * The trust boundary the migration introduced. The legacy area lived in CICS-managed storage and
     * its identity bytes were server-authored from an authenticated read - {@code app/cbl/COSGN00C.cbl}
     * line 226 writes the identifier and line 227 moves {@code SEC-USR-TYPE} out of the {@code USRSEC}
     * record, and only then does line 230 branch on {@code CDEMO-USRTYP-ADMIN}. Echoing the same area
     * through a REST client removes that proof, so these tests fix two properties: the predicate over
     * the echoed byte cannot be read as authorization, and reconciliation against the authenticated
     * principal is available in both the reject and the overwrite form. See DL-087.
     */
    @Nested
    @DisplayName("Reconciliation against the authenticated principal, because the echoed identity is input")
    class PrincipalReconciliation {

        @Test
        @DisplayName("a tampered user-type byte makes the echo predicate answer true, which is exactly "
                + "why it is not authorization: a standard user who sends the administrator character "
                + "reaches the same answer as an administrator")
        void aTamperedUserTypeByteMakesTheEchoPredicateAnswerTrue() {
            final NavigationContext tampered =
                    context("A", NavigationContext.ProgramContext.REENTER);

            assertThat(tampered.echoesAdministratorCode()).isTrue();

            // The same byte, reconciled against a principal who is a standard user, stops claiming it.
            final NavigationContext reconciled = tampered.reconciledWith(USER_ID, UserType.USER);

            assertThat(reconciled.echoesAdministratorCode()).isFalse();
            assertThat(reconciled.userType()).isEqualTo(UserType.USER.getCode());
        }

        @Test
        @DisplayName("a tampered echo disagrees with the principal, so a caller that prefers to refuse "
                + "the turn can detect it rather than having to trust the byte")
        void aTamperedEchoDisagreesWithThePrincipal() {
            final NavigationContext tampered =
                    context("A", NavigationContext.ProgramContext.REENTER);

            assertThat(tampered.agreesWith(USER_ID, UserType.USER)).isFalse();
            assertThat(tampered.agreesWith(USER_ID, UserType.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("a tampered user identifier disagrees too, so impersonation is caught on the same "
                + "check as elevation")
        void aTamperedUserIdentifierDisagreesToo() {
            final NavigationContext tampered =
                    context("A", NavigationContext.ProgramContext.REENTER);

            assertThat(tampered.agreesWith("USER0001", UserType.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("the identifier comparison neither trims nor case-folds, because the echoed value "
                + "travels exactly as received and the user-security key is fixed-width")
        void theIdentifierComparisonNeitherTrimsNorCaseFolds() {
            final NavigationContext subject =
                    context("A", NavigationContext.ProgramContext.REENTER);

            assertThat(subject.agreesWith(USER_ID, UserType.ADMIN)).isTrue();
            assertThat(subject.agreesWith(" " + USER_ID, UserType.ADMIN)).isFalse();
            assertThat(subject.agreesWith(USER_ID + " ", UserType.ADMIN)).isFalse();
            assertThat(subject.agreesWith(USER_ID.toLowerCase(java.util.Locale.ROOT),
                    UserType.ADMIN)).isFalse();
        }

        @ParameterizedTest(name = "an echoed code of [{0}] never agrees with a declared principal role")
        @ValueSource(strings = {"a", "u", "X", " ", "AA", ""})
        @DisplayName("an undeclared or unresolvable echoed code disagrees with every declared principal "
                + "role rather than matching one, which is the safe direction for the failure")
        void anUnresolvableEchoedCodeDisagreesWithEveryDeclaredRole(final String echoed) {
            final NavigationContext subject =
                    context(echoed, NavigationContext.ProgramContext.REENTER);

            assertThat(subject.agreesWith(USER_ID, UserType.ADMIN)).isFalse();
            assertThat(subject.agreesWith(USER_ID, UserType.USER)).isFalse();
        }

        @Test
        @DisplayName("an absent echoed code agrees only with an absent principal role, so a turn that "
                + "carried no identity is not silently promoted to one")
        void anAbsentEchoedCodeAgreesOnlyWithAnAbsentPrincipalRole() {
            final NavigationContext subject =
                    context(null, NavigationContext.ProgramContext.REENTER);

            assertThat(subject.agreesWith(USER_ID, null)).isTrue();
            assertThat(subject.agreesWith(USER_ID, UserType.ADMIN)).isFalse();
            assertThat(subject.agreesWith(USER_ID, UserType.USER)).isFalse();
        }

        @Test
        @DisplayName("an absent principal identifier agrees only with an absent echoed identifier, so "
                + "an unauthenticated turn cannot be reconciled against a named operator by omission")
        void anAbsentPrincipalIdentifierAgreesOnlyWithAnAbsentEchoedIdentifier() {
            final NavigationContext named =
                    context("A", NavigationContext.ProgramContext.REENTER);
            final NavigationContext anonymous = NavigationContext.empty();

            assertThat(named.agreesWith(null, UserType.ADMIN)).isFalse();
            assertThat(anonymous.agreesWith(null, null)).isTrue();
            assertThat(anonymous.agreesWith(null, UserType.ADMIN)).isFalse();
            assertThat(anonymous.agreesWith(USER_ID, null)).isFalse();
        }

        @Test
        @DisplayName("reconciliation writes the principal's identity and leaves the other fourteen "
                + "components byte for byte, because correcting identity is not licence to rewrite "
                + "echoed navigation state")
        void reconciliationLeavesTheOtherFourteenComponentsUntouched() {
            final NavigationContext before =
                    context("A", NavigationContext.ProgramContext.REENTER);

            final NavigationContext after = before.reconciledWith("USER0001", UserType.USER);

            assertThat(after.userId()).isEqualTo("USER0001");
            assertThat(after.userType()).isEqualTo("U");
            assertThat(after.fromTransactionId()).isEqualTo(before.fromTransactionId());
            assertThat(after.fromProgram()).isEqualTo(before.fromProgram());
            assertThat(after.toTransactionId()).isEqualTo(before.toTransactionId());
            assertThat(after.toProgram()).isEqualTo(before.toProgram());
            assertThat(after.programContext()).isEqualTo(before.programContext());
            assertThat(after.customerId()).isEqualTo(before.customerId());
            assertThat(after.customerFirstName()).isEqualTo(before.customerFirstName());
            assertThat(after.customerMiddleName()).isEqualTo(before.customerMiddleName());
            assertThat(after.customerLastName()).isEqualTo(before.customerLastName());
            assertThat(after.accountId()).isEqualTo(before.accountId());
            assertThat(after.accountStatus()).isEqualTo(before.accountStatus());
            assertThat(after.cardNumber()).isEqualTo(before.cardNumber());
            assertThat(after.lastMap()).isEqualTo(before.lastMap());
            assertThat(after.lastMapset()).isEqualTo(before.lastMapset());
        }

        @Test
        @DisplayName("reconciliation is pure, so the tampered instance a caller still holds is "
                + "unchanged and cannot be mistaken for the corrected one")
        void reconciliationIsPure() {
            final NavigationContext before =
                    context("A", NavigationContext.ProgramContext.REENTER);

            final NavigationContext after = before.reconciledWith("USER0001", UserType.USER);

            assertThat(before.userId()).isEqualTo(USER_ID);
            assertThat(before.userType()).isEqualTo("A");
            assertThat(before.echoesAdministratorCode()).isTrue();
            assertThat(after).isNotSameAs(before).isNotEqualTo(before);
        }

        @Test
        @DisplayName("a reconciled instance agrees with the principal it was reconciled against, so "
                + "the overwrite remedy and the reject remedy cannot disagree with each other")
        void aReconciledInstanceAgreesWithItsPrincipal() {
            for (final UserType role : UserType.values()) {
                final NavigationContext reconciled =
                        context("A", NavigationContext.ProgramContext.REENTER)
                                .reconciledWith("USER0001", role);

                assertThat(reconciled.agreesWith("USER0001", role))
                        .as("role %s", role)
                        .isTrue();
                assertThat(reconciled.echoesAdministratorCode())
                        .as("role %s", role)
                        .isEqualTo(role.isAdmin());
            }
        }

        @Test
        @DisplayName("an absent principal role clears the byte rather than inventing one, so an "
                + "unauthenticated turn cannot be reconciled into carrying a role")
        void anAbsentPrincipalRoleClearsTheByte() {
            final NavigationContext reconciled =
                    context("A", NavigationContext.ProgramContext.REENTER)
                            .reconciledWith(null, null);

            assertThat(reconciled.userId()).isNull();
            assertThat(reconciled.userType()).isNull();
            assertThat(reconciled.echoesAdministratorCode()).isFalse();
            assertThat(reconciled.resolvedUserType()).isEmpty();
        }

        @Test
        @DisplayName("reconciliation writes the declared one-character code, so the reconciled instance "
                + "still carries a raw byte and round-trips like any other")
        void reconciliationWritesTheDeclaredOneCharacterCode() {
            final NavigationContext reconciled =
                    context(null, NavigationContext.ProgramContext.ENTER)
                            .reconciledWith(USER_ID, UserType.ADMIN);

            assertThat(reconciled.userType()).isEqualTo("A").hasSize(1);
            assertThat(reconciled.resolvedUserType()).contains(UserType.ADMIN);
        }
    }
}
