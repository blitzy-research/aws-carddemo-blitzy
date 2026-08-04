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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.service.ConversationState.EntryMode;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link ConversationState}, the service-tier carry-over the service layer owns.
 *
 * <p><strong>What this file is really guarding.</strong> The type exists because the legacy
 * communication area {@code CARDDEMO-COMMAREA} at {@code app/cpy/COCOM01Y.cpy} declares sixteen fields,
 * of which eleven are identity and cardholder members that arrive having been echoed by the client.
 * The service tier is not entitled to act on an echoed claim, so it is not given one to act on: this
 * type carries the four routing fields and the entry-mode flag, and nothing else. Most of the
 * assertions below are therefore <em>absence</em> assertions, and the strongest of them is the
 * component-set closure test - a sixth component of any kind fails it, which is what stops the eleven
 * from being reintroduced one convenience at a time.
 *
 * <p><strong>Where the expectations come from.</strong> The five carried fields correspond to
 * {@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-TRANID},
 * {@code CDEMO-TO-PROGRAM} and {@code CDEMO-PGM-CONTEXT} in that copybook. The entry-mode
 * normalisation is read from {@code app/cpy/COCOM01Y.cpy:L29-L31}, where the flag is
 * {@code PIC 9(01)} with condition names {@code CDEMO-PGM-ENTER VALUE 0} and
 * {@code CDEMO-PGM-REENTER VALUE 1}: a single-digit numeric item cannot be absent, so an unset flag and
 * a first entry are the same state, and every program tests only the re-entry side of it, as at
 * {@code app/cbl/COBIL00C.cbl:L112}. That is why the compact constructor normalises {@code null} to
 * first entry rather than preserving a third state the estate cannot express.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("ConversationState :: the service-owned pseudo-conversational carry-over")
final class ConversationStateTest {

    /** The five components, in declaration order. A change here is a change to the service contract. */
    private static final List<String> COMPONENT_NAMES = List.of(
            "fromTransactionId", "fromProgram", "toTransactionId", "toProgram", "entryMode");

    /**
     * The eleven communication-area members deliberately not modelled here, named individually so the
     * closure assertion fails with the name of whichever one was reintroduced.
     *
     * <p>All eleven are client-echoed on the wire record, which is why none of them is here: the user
     * identifier and type, the customer identifier and the three name parts, the account identifier and
     * status, the primary account number, and the two last-map fields.
     */
    private static final List<String> WITHHELD_MEMBER_NAMES = List.of(
            "userId", "userType", "customerId", "customerFirstName", "customerMiddleName",
            "customerLastName", "accountId", "accountStatus", "cardNumber", "lastMap", "lastMapset");

    /** A representative originating transaction identifier, four characters as the estate declares. */
    private static final String FROM_TRANSACTION = "CB00";

    /** A representative originating program name, eight characters as the estate declares. */
    private static final String FROM_PROGRAM = "COBIL00C";

    /** A representative nominated transaction identifier. */
    private static final String TO_TRANSACTION = "CM00";

    /** A representative nominated program name. */
    private static final String TO_PROGRAM = "COMEN01C";

    /**
     * Builds a state with every routing field populated and standing at re-entry.
     *
     * @return a fully populated state
     */
    private static ConversationState fullyPopulated() {
        return new ConversationState(FROM_TRANSACTION, FROM_PROGRAM, TO_TRANSACTION, TO_PROGRAM,
                EntryMode.RE_ENTRY);
    }

    // ----------------------------------------------------------------------------------------
    // The declared shape, and what it withholds
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the declared shape carries five routing and mode components and nothing else")
    final class TheDeclaredShape {

        @Test
        @DisplayName("exactly the five components are declared, in order")
        void exactlyTheFiveComponentsAreDeclared() {
            final List<String> declared = Arrays.stream(ConversationState.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .containsExactlyElementsOf(COMPONENT_NAMES)
                    .hasSize(5);
        }

        @Test
        @DisplayName("not one of the eleven echoed communication-area members is declared, because a "
                + "service that read one would make a decision out of a claim")
        void notOneEchoedMemberIsDeclared() {
            final List<String> declared = Arrays.stream(ConversationState.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).doesNotContainAnyElementsOf(WITHHELD_MEMBER_NAMES);
        }

        @Test
        @DisplayName("no component name suggests an identity, a cardholder value or a screen name, so "
                + "the withheld set cannot be reintroduced under a different spelling")
        void noComponentNameSuggestsAnIdentityOrCardholderValue() {
            final List<String> lowerCased = Arrays.stream(
                            ConversationState.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("user"))
                    .noneMatch(name -> name.contains("customer"))
                    .noneMatch(name -> name.contains("account"))
                    .noneMatch(name -> name.contains("card"))
                    .noneMatch(name -> name.contains("ssn"))
                    .noneMatch(name -> name.contains("name") && !name.contains("program"))
                    .noneMatch(name -> name.contains("map"))
                    .noneMatch(name -> name.contains("role") || name.contains("authorit"));
        }

        @Test
        @DisplayName("the four routing components are text and the entry mode is the two-state "
                + "enumeration, so a fixed-width identifier keeps its blanks and the flag cannot "
                + "hold a third value")
        void routingComponentsAreTextAndTheModeIsEnumerated() {
            for (final RecordComponent component : ConversationState.class.getRecordComponents()) {
                if ("entryMode".equals(component.getName())) {
                    assertThat(component.getType()).isEqualTo(EntryMode.class);
                } else {
                    assertThat(component.getType())
                            .as("routing component %s is the fixed-width identifier itself",
                                    component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("the type declares no member beyond its five accessors and its named derivations, "
                + "so no fragment of the navigation rules sits on the carry-over itself")
        void noMemberIsDeclaredBeyondTheAccessorsAndNamedDerivations() {
            final List<String> declaredMethods = Arrays.stream(
                            ConversationState.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> !COMPONENT_NAMES.contains(name))
                    .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                    .toList();

            assertThat(declaredMethods)
                    .as("route resolution belongs to the navigation authority, not to the state it "
                            + "reads, so nothing here may resolve a destination")
                    .containsExactlyInAnyOrder("empty", "absent", "firstEntry", "reEntry",
                            "withReEntry", "withFirstEntry", "withOriginatingProgram",
                            "withNominatedProgram", "withOrigin");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Entry-mode normalisation: the estate's flag cannot be absent
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the entry mode is normalised, because the legacy flag cannot be absent")
    final class TheEntryModeIsNormalised {

        @Test
        @DisplayName("an absent entry mode becomes first entry, matching the zero a freshly "
                + "initialised communication area holds")
        void anAbsentEntryModeBecomesFirstEntry() {
            final ConversationState normalised = new ConversationState(
                    FROM_TRANSACTION, FROM_PROGRAM, null, null, null);

            assertThat(normalised.entryMode()).isSameAs(EntryMode.FIRST_ENTRY);
            assertThat(normalised.firstEntry()).isTrue();
            assertThat(normalised.reEntry()).isFalse();
        }

        @ParameterizedTest(name = "{0} is stored exactly as supplied")
        @EnumSource(EntryMode.class)
        @DisplayName("a supplied entry mode is stored exactly as supplied, so normalisation touches "
                + "only the absent case")
        void aSuppliedEntryModeIsStoredAsSupplied(final EntryMode supplied) {
            assertThat(new ConversationState(null, null, null, null, supplied).entryMode())
                    .isSameAs(supplied);
        }

        @Test
        @DisplayName("first entry and re-entry are the only two modes, so no third state can be "
                + "carried where the estate has only a single digit")
        void firstEntryAndReEntryAreTheOnlyTwoModes() {
            assertThat(EntryMode.values()).hasSize(2);
            assertThat(Arrays.stream(EntryMode.values()).map(Enum::name).toList())
                    .containsExactly("FIRST_ENTRY", "RE_ENTRY")
                    .doesNotContain("UNSET", "UNKNOWN", "NONE", "ABSENT");
        }

        @ParameterizedTest(name = "{0} describes itself as its own constant name")
        @EnumSource(EntryMode.class)
        @DisplayName("each mode describes itself by its own name, and the description is diagnostic "
                + "only rather than a wire value")
        void eachModeDescribesItselfByName(final EntryMode mode) {
            assertThat(mode.describe()).isEqualTo(mode.name()).isNotEmpty();
        }

        @Test
        @DisplayName("the two modes are complementary readings of one flag, never both and never "
                + "neither")
        void theTwoModesAreComplementary() {
            for (final EntryMode mode : EntryMode.values()) {
                final ConversationState state = new ConversationState(null, null, null, null, mode);

                assertThat(state.firstEntry())
                        .as("for %s, first entry and re-entry are exact complements", mode)
                        .isNotEqualTo(state.reEntry());
            }
        }

        @Test
        @DisplayName("no routing field is normalised: a blank identifier keeps its blanks, because "
                + "the estate's identifiers are fixed-width and blank-significant")
        void noRoutingFieldIsNormalised() {
            final ConversationState padded = new ConversationState(
                    "    ", "        ", "", null, EntryMode.RE_ENTRY);

            assertThat(padded.fromTransactionId()).isEqualTo("    ");
            assertThat(padded.fromProgram()).isEqualTo("        ");
            assertThat(padded.toTransactionId()).isEmpty();
            assertThat(padded.toProgram()).isNull();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Absence: the zero-length communication-area equivalent
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("absence is the zero-length communication area, and first entry does not break it")
    final class AbsenceIsTheZeroLengthCommunicationArea {

        @Test
        @DisplayName("the empty state carries no routing field and stands at first entry")
        void theEmptyStateCarriesNoRoutingFieldAndStandsAtFirstEntry() {
            final ConversationState empty = ConversationState.empty();

            assertThat(empty.fromTransactionId()).isNull();
            assertThat(empty.fromProgram()).isNull();
            assertThat(empty.toTransactionId()).isNull();
            assertThat(empty.toProgram()).isNull();
            assertThat(empty.entryMode()).isSameAs(EntryMode.FIRST_ENTRY);
            assertThat(empty.absent()).isTrue();
        }

        @Test
        @DisplayName("the empty state is shared rather than rebuilt, so an absent carry-over costs no "
                + "allocation on a path every online turn takes")
        void theEmptyStateIsShared() {
            assertThat(ConversationState.empty()).isSameAs(ConversationState.empty());
        }

        @Test
        @DisplayName("an all-null construction is equal to the empty state, because the constructor "
                + "normalises the one component that cannot be absent")
        void anAllNullConstructionEqualsTheEmptyState() {
            assertThat(new ConversationState(null, null, null, null, null))
                    .isEqualTo(ConversationState.empty());
        }

        @Test
        @DisplayName("marking the empty state as a first entry is the identity, because that is "
                + "already what it carries")
        void markingTheEmptyStateAsAFirstEntryIsTheIdentity() {
            assertThat(ConversationState.empty().withFirstEntry())
                    .isEqualTo(ConversationState.empty());
            assertThat(ConversationState.empty().withFirstEntry().absent()).isTrue();
        }

        @Test
        @DisplayName("marking the empty state as a re-entry is not absence, because re-entry is a "
                + "value a freshly initialised communication area cannot hold")
        void markingTheEmptyStateAsAReEntryIsNotAbsence() {
            assertThat(ConversationState.empty().withReEntry().absent()).isFalse();
        }

        @Test
        @DisplayName("any single populated routing field defeats absence, each one on its own")
        void anySinglePopulatedRoutingFieldDefeatsAbsence() {
            assertThat(new ConversationState(FROM_TRANSACTION, null, null, null, null).absent())
                    .isFalse();
            assertThat(new ConversationState(null, FROM_PROGRAM, null, null, null).absent())
                    .isFalse();
            assertThat(new ConversationState(null, null, TO_TRANSACTION, null, null).absent())
                    .isFalse();
            assertThat(new ConversationState(null, null, null, TO_PROGRAM, null).absent())
                    .isFalse();
        }

        @Test
        @DisplayName("a blank routing field defeats absence, because a blank fixed-width field is a "
                + "value the client sent and not the same as having sent nothing")
        void aBlankRoutingFieldDefeatsAbsence() {
            assertThat(new ConversationState("", null, null, null, null).absent()).isFalse();
            assertThat(new ConversationState("    ", null, null, null, null).absent()).isFalse();
        }

        @Test
        @DisplayName("a fully populated state is not absent")
        void aFullyPopulatedStateIsNotAbsent() {
            assertThat(fullyPopulated().absent()).isFalse();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Derivations: each returns a copy and mutates nothing
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("every derivation returns a copy and leaves the original untouched")
    final class EveryDerivationReturnsACopy {

        @Test
        @DisplayName("marking a re-entry changes only the mode")
        void markingAReEntryChangesOnlyTheMode() {
            final ConversationState original = new ConversationState(
                    FROM_TRANSACTION, FROM_PROGRAM, TO_TRANSACTION, TO_PROGRAM,
                    EntryMode.FIRST_ENTRY);

            final ConversationState derived = original.withReEntry();

            assertThat(derived.entryMode()).isSameAs(EntryMode.RE_ENTRY);
            assertThat(derived.fromTransactionId()).isEqualTo(FROM_TRANSACTION);
            assertThat(derived.fromProgram()).isEqualTo(FROM_PROGRAM);
            assertThat(derived.toTransactionId()).isEqualTo(TO_TRANSACTION);
            assertThat(derived.toProgram()).isEqualTo(TO_PROGRAM);
            assertThat(original.entryMode())
                    .as("the original is unaffected by the derivation")
                    .isSameAs(EntryMode.FIRST_ENTRY);
        }

        @Test
        @DisplayName("marking a first entry changes only the mode")
        void markingAFirstEntryChangesOnlyTheMode() {
            final ConversationState derived = fullyPopulated().withFirstEntry();

            assertThat(derived.entryMode()).isSameAs(EntryMode.FIRST_ENTRY);
            assertThat(derived.fromProgram()).isEqualTo(FROM_PROGRAM);
            assertThat(derived.toProgram()).isEqualTo(TO_PROGRAM);
            assertThat(fullyPopulated().entryMode()).isSameAs(EntryMode.RE_ENTRY);
        }

        @Test
        @DisplayName("nominating an originating program changes only that program, leaving the "
                + "originating transaction, the nomination and the mode where they were")
        void nominatingAnOriginatingProgramChangesOnlyThatProgram() {
            final ConversationState derived = fullyPopulated().withOriginatingProgram("COMEN01C");

            assertThat(derived.fromProgram()).isEqualTo("COMEN01C");
            assertThat(derived.fromTransactionId()).isEqualTo(FROM_TRANSACTION);
            assertThat(derived.toTransactionId()).isEqualTo(TO_TRANSACTION);
            assertThat(derived.toProgram()).isEqualTo(TO_PROGRAM);
            assertThat(derived.entryMode()).isSameAs(EntryMode.RE_ENTRY);
        }

        @Test
        @DisplayName("nominating a destination program changes only that program")
        void nominatingADestinationProgramChangesOnlyThatProgram() {
            final ConversationState derived = fullyPopulated().withNominatedProgram("COSGN00C");

            assertThat(derived.toProgram()).isEqualTo("COSGN00C");
            assertThat(derived.fromTransactionId()).isEqualTo(FROM_TRANSACTION);
            assertThat(derived.fromProgram()).isEqualTo(FROM_PROGRAM);
            assertThat(derived.toTransactionId()).isEqualTo(TO_TRANSACTION);
            assertThat(derived.entryMode()).isSameAs(EntryMode.RE_ENTRY);
        }

        @Test
        @DisplayName("either nomination accepts absence, because clearing a nomination is a state the "
                + "legacy hand-off produces and not an error")
        void eitherNominationAcceptsAbsence() {
            assertThat(fullyPopulated().withOriginatingProgram(null).fromProgram()).isNull();
            assertThat(fullyPopulated().withNominatedProgram(null).toProgram()).isNull();
        }

        @Test
        @DisplayName("recording this screen as the origin replaces the originating pair and resets the "
                + "mode to first entry, because the destination has not presented itself yet")
        void recordingTheOriginReplacesThePairAndResetsTheMode() {
            final ConversationState handOff = fullyPopulated().withOrigin("CR00", "CORPT00C");

            assertThat(handOff.fromTransactionId()).isEqualTo("CR00");
            assertThat(handOff.fromProgram()).isEqualTo("CORPT00C");
            assertThat(handOff.toTransactionId())
                    .as("the nomination is the destination's to change, not the origin's")
                    .isEqualTo(TO_TRANSACTION);
            assertThat(handOff.toProgram()).isEqualTo(TO_PROGRAM);
            assertThat(handOff.entryMode()).isSameAs(EntryMode.FIRST_ENTRY);
        }

        @Test
        @DisplayName("recording the origin resets a re-entry even when the pair it writes is the pair "
                + "already there, so the reset is unconditional")
        void recordingTheOriginResetsTheModeUnconditionally() {
            final ConversationState handOff =
                    fullyPopulated().withOrigin(FROM_TRANSACTION, FROM_PROGRAM);

            assertThat(handOff.entryMode()).isSameAs(EntryMode.FIRST_ENTRY);
            assertThat(handOff).isNotEqualTo(fullyPopulated());
        }

        @Test
        @DisplayName("derivations compose without interfering, so a hand-off followed by a re-entry "
                + "mark carries both effects")
        void derivationsComposeWithoutInterfering() {
            final ConversationState composed = ConversationState.empty()
                    .withOrigin("CB00", "COBIL00C")
                    .withNominatedProgram("COSGN00C")
                    .withReEntry();

            assertThat(composed).isEqualTo(new ConversationState(
                    "CB00", "COBIL00C", null, "COSGN00C", EntryMode.RE_ENTRY));
        }
    }

    // ----------------------------------------------------------------------------------------
    // Value semantics
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("value semantics")
    final class ValueSemantics {

        @Test
        @DisplayName("two states built from the same values are equal and hash alike")
        void equalInputsAreEqualAndHashAlike() {
            assertThat(fullyPopulated())
                    .isEqualTo(fullyPopulated())
                    .hasSameHashCodeAs(fullyPopulated());
        }

        @Test
        @DisplayName("every component participates in equality, so no difference is silently ignored")
        void everyComponentParticipatesInEquality() {
            final ConversationState reference = fullyPopulated();

            assertThat(reference).isNotEqualTo(new ConversationState(
                    "CA00", FROM_PROGRAM, TO_TRANSACTION, TO_PROGRAM, EntryMode.RE_ENTRY));
            assertThat(reference).isNotEqualTo(new ConversationState(
                    FROM_TRANSACTION, "COADM01C", TO_TRANSACTION, TO_PROGRAM, EntryMode.RE_ENTRY));
            assertThat(reference).isNotEqualTo(new ConversationState(
                    FROM_TRANSACTION, FROM_PROGRAM, "CA00", TO_PROGRAM, EntryMode.RE_ENTRY));
            assertThat(reference).isNotEqualTo(new ConversationState(
                    FROM_TRANSACTION, FROM_PROGRAM, TO_TRANSACTION, "COADM01C", EntryMode.RE_ENTRY));
            assertThat(reference).isNotEqualTo(new ConversationState(
                    FROM_TRANSACTION, FROM_PROGRAM, TO_TRANSACTION, TO_PROGRAM,
                    EntryMode.FIRST_ENTRY));
        }

        @Test
        @DisplayName("is immutable by construction: repeated reads never differ and there is no mutator")
        void isImmutableByConstruction() {
            final ConversationState state = fullyPopulated();

            assertThat(state.fromProgram()).isSameAs(state.fromProgram());
            assertThat(state.entryMode()).isSameAs(state.entryMode());
            assertThat(Arrays.stream(ConversationState.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("the generated rendering names every carried component and is left unredacted, "
                + "because the type carries no identifier and no cardholder value to redact")
        void theGeneratedRenderingIsRetained() {
            final String rendered = fullyPopulated().toString();

            assertThat(rendered)
                    .startsWith("ConversationState[")
                    .endsWith("]")
                    .contains("fromTransactionId=" + FROM_TRANSACTION)
                    .contains("fromProgram=" + FROM_PROGRAM)
                    .contains("toTransactionId=" + TO_TRANSACTION)
                    .contains("toProgram=" + TO_PROGRAM)
                    .contains("entryMode=RE_ENTRY");
            assertThatCode(() -> ConversationState.empty().toString())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the rendering names no identifier and no cardholder value, because none is "
                + "carried for it to name")
        void theRenderingNamesNoIdentifierOrCardholderValue() {
            final String rendered = fullyPopulated().toString().toLowerCase(Locale.ROOT);

            assertThat(rendered)
                    .doesNotContain("userid")
                    .doesNotContain("usertype")
                    .doesNotContain("customer")
                    .doesNotContain("accountid")
                    .doesNotContain("cardnumber")
                    .doesNotContain("ssn");
        }
    }
}
