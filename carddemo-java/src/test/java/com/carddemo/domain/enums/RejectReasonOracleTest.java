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
package com.carddemo.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.carddemo.support.LegacyRejectReasons;
import com.carddemo.support.TestDataFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts the shipped reject-reason enumeration against an ORACLE transcribed by hand from the legacy source,
 * in that direction and not the other.
 *
 * <h2>What was wrong with the previous arrangement</h2>
 *
 * <p>The reject reason code and its description leave the system positionally, in the eighty-byte trailer of a
 * four-hundred-and-thirty byte record that a downstream consumer reads by offset. Every expectation about
 * those bytes must NOT be built by READING {@link RejectReason} - {@code reason.getReasonCode()} and
 * {@code reason.getDescription()} - because that makes the comparison the implementation against itself.
 * A code recorded as 104 would produce an expectation of 104 and a passing test over a wrong file. Two of the five
 * reasons carry the SAME description under DIFFERENT codes, which is precisely the pairing a self-referential
 * expectation cannot police: transposing those two codes changes the emitted bytes and changes no expectation.
 *
 * <p>{@link LegacyRejectReasons} is the independent side. It holds five literal pairs, each transcribed from
 * the statement that sets it and each citing its source line, and it imports nothing from
 * {@code com.carddemo.domain} or {@code com.carddemo.batch}. This class compares the two, so a drift in the
 * enumeration fails here rather than surfacing as a wrong byte in a delivered file.
 *
 * <p>Recorded as DL-279 in {@code docs/decision-log.md}, including the three mutations this
 * comparison was confirmed to fail under.
 *
 * <p>No COBOL statement is transcribed.
 */
@DisplayName("Reject reasons: the shipped enumeration against a hand-transcribed oracle")
final class RejectReasonOracleTest {

    /** Creates the specification. */
    RejectReasonOracleTest() {
    }

    /**
     * The transcribed reasons, supplied to the parameterised comparisons.
     *
     * @return the oracle's five reasons
     */
    static List<LegacyRejectReasons.Reason> transcribedReasons() {
        return LegacyRejectReasons.REASONS;
    }

    /**
     * Resolves the shipped constant of the given name, failing with a readable message when the enumeration
     * carries no such constant.
     *
     * <p>Resolving by NAME rather than by code is deliberate and is what gives the comparison its teeth. The
     * oracle transcribes a code and a description for each ROLE the source plays; looking the constant up by
     * its code and then asserting its code would be circular, and looking it up by its description cannot
     * separate the two reasons that share one. {@link RejectReason#valueOf(String)} is avoided in favour of a
     * scan so that a missing constant reports the names that ARE declared.
     *
     * @param name the constant name the oracle records for a role
     * @return the shipped constant
     */
    private static RejectReason shippedConstantNamed(final String name) {
        return Arrays.stream(RejectReason.values())
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    throw new AssertionError("the oracle records that a constant named " + name
                            + " must carry a reject reason, but the shipped enumeration declares only "
                            + Arrays.stream(RejectReason.values()).map(Enum::name).toList()
                            + ". A contract-bearing constant was renamed or removed without the oracle "
                            + "being updated alongside it");
                });
    }

    @Nested
    @DisplayName("The set matches, exactly and in both directions")
    class TheSetMatches {

        /** Creates the nest. */
        TheSetMatches() {
        }

        @Test
        @DisplayName("the enumeration declares exactly the FIVE transcribed codes, in ascending order and "
                + "with no sixth")
        void theEnumerationDeclaresExactlyTheTranscribedCodes() {
            final List<Integer> declared = Arrays.stream(RejectReason.values())
                    .map(RejectReason::getReasonCode)
                    .toList();

            assertThat(declared)
                    .as("a sixth reason would emit a trailer no consumer of this contract knows how to "
                            + "read, and a missing one would silently post a record the legacy rejected. "
                            + "The order is asserted too: the source's codes ascend, and an out-of-order "
                            + "declaration is the visible signature of a transposed pair")
                    .containsExactlyElementsOf(LegacyRejectReasons.CODES);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.domain.enums.RejectReasonOracleTest#transcribedReasons")
        @DisplayName("the constant that plays each transcribed ROLE carries the transcribed code and the "
                + "transcribed description")
        void theConstantPlayingEachRoleCarriesTheTranscribedPair(final LegacyRejectReasons.Reason reason) {
            final RejectReason shipped = shippedConstantNamed(reason.shippedConstantName());

            assertAll(
                    () -> assertThat(shipped.getReasonCode())
                            .as("the source sets %d where %s, at %s. Resolving BY ROLE rather than by "
                                    + "code is what makes a transposition of the two identically-described "
                                    + "reasons visible - by code alone the transposition is invisible",
                                    reason.code(), reason.role(), reason.sourceLocation())
                            .isEqualTo(reason.code()),
                    () -> assertThat(shipped.getDescription())
                            .as("the description the source moves at %s, character for character and "
                                    + "unpadded", reason.sourceLocation())
                            .isEqualTo(reason.description()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.domain.enums.RejectReasonOracleTest#transcribedReasons")
        @DisplayName("resolving a transcribed code returns the constant that plays that code's role, so the "
                + "mapping holds in the reverse direction as well")
        void resolvingATranscribedCodeReturnsTheConstantPlayingItsRole(
                final LegacyRejectReasons.Reason reason) {
            final Optional<RejectReason> resolved = RejectReason.byReasonCode(reason.code());

            assertThat(resolved)
                    .as("code %d is transcribed from %s and must be recognised", reason.code(),
                            reason.sourceLocation())
                    .isPresent();
            assertThat(resolved.orElseThrow().name())
                    .as("a reject record carrying %s must be recovered as the reason that arises when %s",
                            reason.fourDigitCode(), reason.role())
                    .isEqualTo(reason.shippedConstantName());
        }

        @Test
        @DisplayName("the two ACCOUNT RECORD NOT FOUND reasons are DISTINCT constants whose codes are NOT "
                + "interchangeable, which is the pairing neither a self-referential nor a set-only "
                + "expectation could have policed")
        void theTwoIdenticallyDescribedReasonsAreNotInterchangeable() {
            final LegacyRejectReasons.Reason transcribedOnRead =
                    LegacyRejectReasons.requireByCode(LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_READ_CODE);
            final LegacyRejectReasons.Reason transcribedOnRewrite =
                    LegacyRejectReasons.requireByCode(LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_REWRITE_CODE);

            final RejectReason onRead = shippedConstantNamed(transcribedOnRead.shippedConstantName());
            final RejectReason onRewrite = shippedConstantNamed(transcribedOnRewrite.shippedConstantName());

            assertAll(
                    () -> assertThat(onRead).isNotSameAs(onRewrite),
                    () -> assertThat(onRead.getReasonCode())
                            .as("the read failure is 101 and cannot be 109: the two are set at different "
                                    + "sites and are routed differently, one to a reject record and one to "
                                    + "a posted record")
                            .isEqualTo(101),
                    () -> assertThat(onRewrite.getReasonCode()).isEqualTo(109),
                    () -> assertThat(onRead.getDescription())
                            .as("the source moves the identical literal at both sites, so the "
                                    + "descriptions are equal and ONLY the four digits distinguish them - "
                                    + "which is precisely why the code cannot be checked by description")
                            .isEqualTo(onRewrite.getDescription()),
                    () -> assertThat(onRead.getDescription()).isEqualTo("ACCOUNT RECORD NOT FOUND"));
        }

        @Test
        @DisplayName("no code the oracle does not carry is recognised, so an invented code cannot acquire "
                + "a description by accident")
        void anUntranscribedCodeIsNotRecognised() {
            for (final int candidate : new int[] {1, 99, 104, 105, 108, 110, 200, 9999}) {
                assertThat(RejectReason.byReasonCode(candidate))
                        .as("code %d is set nowhere in the source and must not resolve", candidate)
                        .isEmpty();
            }
            assertThat(RejectReason.byReasonCode(LegacyRejectReasons.NO_REJECTION_CODE))
                    .as("zero is the value that means NO rejection, tested at line 209, so it is not a "
                            + "reason and must not resolve to one")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The trailer bytes each reason contributes")
    class TheTrailerBytes {

        /** Creates the nest. */
        TheTrailerBytes() {
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.domain.enums.RejectReasonOracleTest#transcribedReasons")
        @DisplayName("contributes four ZERO-FILLED digits then a seventy-six character blank-padded "
                + "description, which is eighty characters exactly")
        void contributesEightyCharacters(final LegacyRejectReasons.Reason reason) {
            assertAll(
                    () -> assertThat(reason.fourDigitCode())
                            .as("four digits, zero-filled on the LEFT because the field is numeric")
                            .hasSize(LegacyRejectReasons.CODE_WIDTH)
                            .containsOnlyDigits(),
                    () -> assertThat(reason.paddedDescription())
                            .as("seventy-six characters, blank-padded on the RIGHT because the field is "
                                    + "alphanumeric and a shorter move leaves spaces behind")
                            .hasSize(LegacyRejectReasons.DESCRIPTION_WIDTH)
                            .startsWith(reason.description()),
                    () -> assertThat(reason.trailer()).hasSize(80),
                    () -> assertThat(reason.trailer())
                            .as("350 plus 80 is the 430 bytes of a reject record, and the 80 begins with "
                                    + "the code")
                            .startsWith(reason.fourDigitCode()));
        }

        @Test
        @DisplayName("the four-digit rendering is zero-filled and never blank-filled, for every one of the "
                + "five codes")
        void theRenderingIsZeroFilledForEveryCode() {
            assertThat(LegacyRejectReasons.CODES.stream()
                            .map(LegacyRejectReasons::fourDigitCode)
                            .toList())
                    .as("the exact four characters a consumer reads at the head of the trailer")
                    .containsExactly("0100", "0101", "0102", "0103", "0109");
        }
    }

    @Nested
    @DisplayName("The one reason that is INERT by legacy design")
    class TheInertReason {

        /** Creates the nest. */
        TheInertReason() {
        }

        @Test
        @DisplayName("the oracle records that FOUR of the five produce a reject record and that the "
                + "rewrite reason does not")
        void theOracleRecordsWhichReasonsProduceARecord() {
            assertThat(LegacyRejectReasons.REASONS.stream()
                            .filter(LegacyRejectReasons.Reason::producesRecord)
                            .map(LegacyRejectReasons.Reason::code)
                            .toList())
                    .as("the main loop writes a reject record only when the reason is non-zero after the "
                            + "validation paragraph, so only the reasons that paragraph can set produce one")
                    .containsExactly(100, 101, 102, 103);

            assertThat(LegacyRejectReasons.requireByCode(
                            LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_REWRITE_CODE).producesRecord())
                    .as("109 is set inside the account-rewrite paragraph, which runs during POSTING - "
                            + "after the reject decision has already sent the record down the posting "
                            + "path. Nothing re-tests the reason afterwards, so the value is set and never "
                            + "read, and the transaction is posted regardless. Turning it into a sixth "
                            + "reject case would be a behaviour the legacy does not have")
                    .isFalse();
        }

        @Test
        @DisplayName("the fixture factory REFUSES to shape a landing record for either reason no landing "
                + "record can reach, and names where they are reached instead")
        void theFixtureFactoryRefusesTheTwoSeamOnlyReasons() {
            for (final RejectReason unreachable : List.of(RejectReason.ACCOUNT_NOT_FOUND_ON_READ,
                    RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)) {
                assertThatThrownBy(() -> TestDataFactory.dailyTransactionRejectedBy(unreachable))
                        .as("the factory used to hand back a record naming an orphaned card and tell"
                                + " the caller to register a cross-reference to an absent account - an"
                                + " arrangement the cross-reference table's foreign key refuses. A"
                                + " fixture that cannot do what its name says is worse than a refusal,"
                                + " because the refusal is discovered at the call site rather than"
                                + " inferred from a test that never went red")
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(unreachable.name())
                        .hasMessageContaining("foreign key")
                        .hasMessageContaining("RejectReasonArmsIT");
            }
        }

        @Test
        @DisplayName("the three reasons a landing record CAN reach are still shaped, so the refusal above "
                + "is a narrow statement rather than the factory giving up")
        void theThreeReachableReasonsAreStillShaped() {
            for (final RejectReason reachable : List.of(RejectReason.INVALID_CARD_NUMBER,
                    RejectReason.OVERLIMIT_TRANSACTION,
                    RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION)) {
                assertThat(TestDataFactory.dailyTransactionRejectedBy(reachable).image())
                        .as("%s is a property of the record and the seeded state together, so the"
                                + " record half of it is shapeable", reachable.name())
                        .hasSize(350);
            }
        }

        @Test
        @DisplayName("the inert reason is nonetheless a DECLARED constant, because the source sets it and "
                + "the translation reproduces the assignment as well as its inertness")
        void theInertReasonIsStillDeclared() {
            final LegacyRejectReasons.Reason transcribed = LegacyRejectReasons.requireByCode(
                    LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_REWRITE_CODE);

            assertThat(shippedConstantNamed(transcribed.shippedConstantName()).getReasonCode())
                    .as("omitting it would erase a statement the source performs; declaring it and "
                            + "routing it to the posted outcome is what reproduces both halves")
                    .isEqualTo(LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_REWRITE_CODE);
        }
    }
}
