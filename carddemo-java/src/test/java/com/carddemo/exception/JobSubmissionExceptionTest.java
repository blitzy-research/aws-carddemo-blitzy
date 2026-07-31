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
package com.carddemo.exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JobSubmissionException}, the failure type of the single online-to-batch
 * bridge in the CardDemo estate.
 *
 * <h2>What this test class is for</h2>
 *
 * <p>The legacy bridge is the transient-data-queue write in {@code app/cbl/CORPT00C.cbl},
 * paragraph {@code WIRTE-JOBSUB-TDQ} at line 515, reached from the transaction-report request
 * screen. The queue it writes to is declared in {@code app/csd/CARDDEMO.CSD} at lines 499 to 505.
 * Between them those two definitions fix a contract that the migrated code must not drift from,
 * and every test below exists to pin one clause of it.
 *
 * <h2>The decisive clause: the failure is NON-FATAL</h2>
 *
 * <p>The queue is defined with {@code ERROROPTION(IGNORE)}. A write error against that queue is
 * <em>ignored</em>: it raises no condition and terminates no transaction. The calling paragraph
 * behaves exactly as that attribute implies. On a non-normal response it writes the response and
 * reason codes to its diagnostic channel, raises its own error flag, moves the failure text into
 * the screen message field, repositions the cursor and re-sends the screen - and then simply falls
 * out of its evaluation and ends. There is no abend, no rollback and no re-raise anywhere on that
 * path; control returns to the operator normally and the request completes.
 *
 * <p>Three consequences follow, and the tests in the non-fatal group assert all three: the failure
 * is <strong>logged</strong>, the <strong>cards after the failing one are not sent</strong> because
 * the emitter's loop also tests that error flag, and <strong>control returns normally</strong>.
 *
 * <p>The class under test is therefore an unchecked value carrier that a catch-and-continue
 * handler can build and inspect on the failure path. It is deliberately <em>not</em> related to
 * the abend type in this package, and it exposes no fatality flag, no abend code, no graded
 * seriousness value and no instruction to abort. Those absences are asserted structurally, by
 * proving the type is not assignable to the abend type, and by the fact that this test class
 * compiles without ever naming such a member - a compile-time proof that needs no reflection.
 * If a future change made the failure fatal, the report request would begin failing where the
 * legacy system succeeds, which is a silent, high-impact behavioural regression.
 *
 * <h2>The frozen message literal</h2>
 *
 * <p>{@link JobSubmissionException#DEFAULT_MESSAGE} reproduces the screen literal character for
 * character. Its final three characters are three separate ASCII full stops, not the single
 * ellipsis character at code point U+2026. An editor or formatter can substitute that code point
 * silently and no compiler will notice, so the constants group guards the literal three
 * independent ways: an exact equality, an explicit check that the code point is absent, and a
 * count of the full stops in the whole string.
 *
 * <h2>Fixed-width payload and the meaning of a card ordinal</h2>
 *
 * <p>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)} makes every queue record an
 * 80-character fixed-width image, and {@code BLOCKFORMAT(UNBLOCKED)} means each card is published
 * individually rather than concatenated. {@code DISPOSITION(MOD)} makes writes append, so card
 * order is significant and becomes message-group ordering on the replacement queue. The submitted
 * job image is 17 cards wide, carries four ten-character date substitution slots, and ends with an
 * end-of-file sentinel card that is itself transmitted before the emitting loop stops. The legacy
 * card index is one-based and the card array holds up to 1000 entries. That is why a failing card
 * ordinal is meaningful failure context at all, and why
 * {@link JobSubmissionException#ORDINAL_NOT_APPLICABLE} is a negative sentinel rather than zero:
 * zero would be indistinguishable from a genuine one-based ordinal, so it could not tell "the
 * publish failed before any card went out" apart from "card N failed".
 *
 * <h2>Scope of this test class</h2>
 *
 * <p>This is a plain unit test. It starts no application context, no container and no messaging
 * client, and it adds no dependency: the only collaborators are the JDK, the test framework and
 * the assertion library. Proving the queue contract end to end - publishing the ordered card
 * sequence with its four substituted date slots and its terminal sentinel to a real ordered queue
 * and draining it back - belongs to the integration and end-to-end tiers, not here.
 *
 * <h2>Documented source anomaly</h2>
 *
 * <p>The legacy paragraph name is misspelled in the source: it reads {@code WIRTE-JOBSUB-TDQ}.
 * It is cited here exactly as written so the traceability row stays findable under the original
 * spelling. The misspelling is not propagated into any Java identifier.
 *
 * <h2>Provenance</h2>
 *
 * <p>Behaviour verified against the CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced
 * here; the citations are references, not transcriptions. The one exception is the failure message
 * text, which is an external interface contract literal and is therefore required verbatim.
 */
@DisplayName("JobSubmissionException")
class JobSubmissionExceptionTest {

    /**
     * The number of cards in the submitted job image. Factual layout evidence taken from the card
     * table the legacy program declares, not a capacity or performance figure.
     */
    private static final int JOB_IMAGE_CARD_COUNT = 17;

    /** The one-based ordinal of the terminating end-of-file sentinel card in that image. */
    private static final int SENTINEL_CARD_ORDINAL = 17;

    /** A one-based ordinal in the middle of the image, used as the simulated point of failure. */
    private static final int FAILING_CARD_ORDINAL = 9;

    /** The first one-based ordinal in the image. */
    private static final int FIRST_CARD_ORDINAL = 1;

    /**
     * A response code carrying a leading zero, so that the tests can prove the code is carried as
     * a string and is never parsed, trimmed or zero-stripped.
     */
    private static final String RESPONSE_CODE = "0012";

    /** A reason code carrying a leading zero, for the same purpose. */
    private static final String REASON_CODE = "0080";

    /**
     * A queue name that is deliberately not the default and deliberately shares no substring with
     * it, so that "the caller-supplied queue name is retained but not appended to the frozen text"
     * can be asserted without a false positive.
     */
    private static final String ALTERNATE_QUEUE_NAME = "ALTQUEUE";

    /**
     * A deliberately synthetic message probe supplied where a test needs to prove that a
     * caller-supplied message overrides the default. It is intentionally unlike the frozen literal
     * so that the override assertion cannot pass vacuously, and it carries no operator-facing
     * wording of its own, because inventing operator text is exactly what the frozen literal
     * exists to prevent.
     */
    private static final String SUPPLIED_MESSAGE_PROBE = "PROBE-SUPPLIED-MESSAGE";

    /** The message of the stand-in cause used throughout, so assertions can identify it. */
    private static final String CAUSE_MESSAGE = "publish failed";

    // ------------------------------------------------------------------------------------------
    // Frozen contract constants
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("frozen contract constants")
    class FrozenContractConstants {

        @Test
        @DisplayName("the default message reproduces the legacy screen literal character for character")
        void defaultMessageReproducesTheLegacyScreenLiteralExactly() {
            assertThat(JobSubmissionException.DEFAULT_MESSAGE)
                    .as("the screen message literal is an external interface contract; operators "
                            + "and downstream tooling match on it, so it is frozen")
                    .isEqualTo("Unable to Write TDQ (JOBS)...");
        }

        @Test
        @DisplayName("the default message ends with three separate ASCII full stops")
        void defaultMessageEndsWithThreeSeparateAsciiFullStops() {
            assertThat(JobSubmissionException.DEFAULT_MESSAGE)
                    .as("three ASCII full stops, not a single ellipsis code point")
                    .endsWith("...");
        }

        @Test
        @DisplayName("the default message contains no U+2026 ellipsis code point")
        void defaultMessageContainsNoUnicodeEllipsisCodePoint() {
            assertThat(JobSubmissionException.DEFAULT_MESSAGE)
                    .as("an editor or formatter can silently fold three full stops into one "
                            + "ellipsis code point and no compiler would notice")
                    .doesNotContain("\u2026");
        }

        @Test
        @DisplayName("the default message contains exactly three full stops in total")
        void defaultMessageContainsExactlyThreeFullStopsInTotal() {
            assertThat(JobSubmissionException.DEFAULT_MESSAGE.chars()
                    .filter(character -> character == '.')
                    .count())
                    .as("exactly three full stops in the whole string, none of them merged and "
                            + "none of them additional")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("the default message names the queue in parentheses with the legacy capitalisation")
        void defaultMessageNamesTheQueueInParentheses() {
            assertThat(JobSubmissionException.DEFAULT_MESSAGE)
                    .as("the queue name reaches the operator through the frozen literal itself")
                    .contains("(" + JobSubmissionException.DEFAULT_QUEUE_NAME + ")")
                    .contains("(JOBS)")
                    .startsWith("Unable to Write TDQ");
        }

        @Test
        @DisplayName("the default queue name is the legacy queue name, four upper-case characters with no whitespace")
        void defaultQueueNameIsTheLegacyQueueName() {
            assertThat(JobSubmissionException.DEFAULT_QUEUE_NAME)
                    .isEqualTo("JOBS")
                    .hasSize(4)
                    .isUpperCase()
                    .doesNotContainAnyWhitespaces();
        }

        @Test
        @DisplayName("the record size is the legacy fixed record size of 80: one card is one 80-character payload")
        void recordSizeIsTheLegacyFixedRecordSize() {
            assertThat(JobSubmissionException.RECORD_SIZE)
                    .as("a factual layout figure from the queue definition's fixed record size, "
                            + "not a capacity or throughput target: each submitted card is one "
                            + "80-character fixed-width payload")
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("the not-applicable ordinal sentinel is -1, which no one-based card ordinal can collide with")
        void ordinalNotApplicableSentinelIsNegativeOne() {
            assertThat(JobSubmissionException.ORDINAL_NOT_APPLICABLE)
                    .as("the legacy card index is one-based, so zero would be ambiguous and a "
                            + "negative sentinel is the only unambiguous choice")
                    .isEqualTo(-1)
                    .isNegative();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The non-fatal contract - the reason this test class exists
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("non-fatal contract (ERROROPTION(IGNORE))")
    class NonFatalContract {

        @Test
        @DisplayName("every constructor overload builds without throwing, so a catch-and-continue handler can always report the failure")
        void everyConstructorOverloadBuildsWithoutThrowing() {
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            assertThatCode(() -> new JobSubmissionException(cause))
                    .as("the cause-only form must be constructible on the failure path")
                    .doesNotThrowAnyException();

            assertThatCode(() -> new JobSubmissionException(SUPPLIED_MESSAGE_PROBE, cause))
                    .as("the message-and-cause form must be constructible on the failure path")
                    .doesNotThrowAnyException();

            assertThatCode(() -> new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE, REASON_CODE, cause))
                    .as("the diagnostic-context form must be constructible on the failure path")
                    .doesNotThrowAnyException();

            assertThatCode(() -> new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE, REASON_CODE,
                    FAILING_CARD_ORDINAL, cause))
                    .as("the fullest form, carrying the failing card ordinal, must be "
                            + "constructible on the failure path")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the type is unchecked, so no caller is forced to handle it and no signature changes on the failure path")
        void theTypeIsUncheckedSoNoCallerIsForcedToHandleIt() {
            final JobSubmissionException failure =
                    new JobSubmissionException(new IOException(CAUSE_MESSAGE));

            assertThat(failure)
                    .as("an unchecked failure lets a service catch it and continue exactly as the "
                            + "legacy paragraph continued, without a throws clause anywhere")
                    .isInstanceOf(RuntimeException.class);
            assertThat(JobSubmissionException.class.getSuperclass())
                    .as("it extends the unchecked base directly, with no intermediate type that "
                            + "could later acquire terminal semantics")
                    .isEqualTo(RuntimeException.class);
        }

        @Test
        @DisplayName("a publish failure is caught and the caller continues - ERROROPTION(IGNORE) semantics")
        void aPublishFailureIsCaughtAndTheCallerContinues() {
            final EmitterOutcome[] captured = new EmitterOutcome[1];

            // The entire simulation is wrapped. If the exception escaped the handler, or if the
            // fixed-width guard inside the simulated publish tripped, this assertion fails.
            assertThatCode(() -> captured[0] =
                    emitEveryCardIgnoringFailure(JOB_IMAGE_CARD_COUNT, FAILING_CARD_ORDINAL))
                    .as("nothing may escape a catch-and-continue handler: the queue definition "
                            + "ignores the error and the legacy paragraph returned normally")
                    .doesNotThrowAnyException();

            final EmitterOutcome outcome = captured[0];
            assertThat(outcome)
                    .as("the simulation must have produced an outcome, which it can only do by "
                            + "reaching its own final statement")
                    .isNotNull();
            assertThat(outcome.cardsAttempted())
                    .as("the loop ran to completion: every one of the %d cards was attempted even "
                            + "though card %d failed", JOB_IMAGE_CARD_COUNT, FAILING_CARD_ORDINAL)
                    .isEqualTo(JOB_IMAGE_CARD_COUNT);
            assertThat(outcome.cardsPublished())
                    .as("exactly one card failed, so exactly one fewer than the whole image was "
                            + "published")
                    .isEqualTo(JOB_IMAGE_CARD_COUNT - 1);
            assertThat(outcome.failuresCaught())
                    .as("the failure was observed rather than swallowed silently")
                    .isEqualTo(1);
            assertThat(outcome.lastCaught())
                    .as("the caught value is the failure type under test")
                    .isNotNull()
                    .isInstanceOf(JobSubmissionException.class);
            assertThat(outcome.lastCaught().failedCardOrdinal())
                    .as("the caught failure names the card the publish stopped on")
                    .isEqualTo(FAILING_CARD_ORDINAL);
            assertThat(outcome.lastCaught().queueName())
                    .isEqualTo(JobSubmissionException.DEFAULT_QUEUE_NAME);
            assertThat(outcome.lastCaught().responseCode()).isEqualTo(RESPONSE_CODE);
            assertThat(outcome.lastCaught().reasonCode()).isEqualTo(REASON_CODE);
            assertThat(outcome.lastCaught().getCause())
                    .as("the underlying publish failure is chained, not swallowed, so the caller "
                            + "still has the original detail to log")
                    .isInstanceOf(IOException.class)
                    .hasMessage(CAUSE_MESSAGE);
        }

        @Test
        @DisplayName("a publish failure stops the remaining cards yet the emitter still returns normally")
        void aPublishFailureStopsTheRemainingCardsYetStillReturnsNormally() {
            final EmitterOutcome[] captured = new EmitterOutcome[1];

            assertThatCode(() -> captured[0] =
                    emitCardsStoppingOnFailure(JOB_IMAGE_CARD_COUNT, FAILING_CARD_ORDINAL))
                    .as("the legacy emitter tested its own error flag, so it stopped emitting - "
                            + "but it still fell out of its loop and returned to the operator")
                    .doesNotThrowAnyException();

            final EmitterOutcome outcome = captured[0];
            assertThat(outcome).isNotNull();
            assertThat(outcome.cardsAttempted())
                    .as("emission stopped at the failing card rather than continuing through the "
                            + "rest of the image")
                    .isEqualTo(FAILING_CARD_ORDINAL);
            assertThat(outcome.cardsPublished())
                    .as("the cards after the failing one were not sent")
                    .isEqualTo(FAILING_CARD_ORDINAL - 1);
            assertThat(outcome.cardsPublished())
                    .as("and the sentinel card at ordinal %d was therefore never reached",
                            SENTINEL_CARD_ORDINAL)
                    .isLessThan(SENTINEL_CARD_ORDINAL);
            assertThat(outcome.failuresCaught()).isEqualTo(1);
            assertThat(outcome.lastCaught()).isNotNull();
            assertThat(outcome.lastCaught().failedCardOrdinal())
                    .as("the ordinal identifies the point from which the remaining cards were "
                            + "not sent")
                    .isEqualTo(FAILING_CARD_ORDINAL);
        }

        @Test
        @DisplayName("thrown and caught, the failure carries the diagnostic context a caller must log")
        void thrownAndCaughtTheFailureCarriesTheDiagnosticContext() {
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            assertThatThrownBy(() -> {
                throw new JobSubmissionException(JobSubmissionException.DEFAULT_QUEUE_NAME,
                        RESPONSE_CODE, REASON_CODE, FAILING_CARD_ORDINAL, cause);
            })
                    .as("the type a handler catches is exactly the type under test, never a "
                            + "wrapper and never an abend")
                    .isExactlyInstanceOf(JobSubmissionException.class)
                    .isNotInstanceOf(AbendException.class)
                    .hasMessageStartingWith(JobSubmissionException.DEFAULT_MESSAGE)
                    .hasMessageContaining(RESPONSE_CODE)
                    .hasMessageContaining(REASON_CODE)
                    .hasCause(cause);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Failure context round trips
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("failure context")
    class FailureContext {

        @Test
        @DisplayName("queue name, response code and reason code round-trip, and the ordinal is the not-applicable sentinel")
        void queueNameResponseAndReasonRoundTripWithoutAnOrdinal() {
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            final JobSubmissionException failure = new JobSubmissionException(
                    ALTERNATE_QUEUE_NAME, RESPONSE_CODE, REASON_CODE, cause);

            assertThat(failure.queueName()).isEqualTo(ALTERNATE_QUEUE_NAME);
            assertThat(failure.responseCode()).isEqualTo(RESPONSE_CODE);
            assertThat(failure.reasonCode()).isEqualTo(REASON_CODE);
            assertThat(failure.getCause())
                    .as("the underlying publish failure is chained by identity, not copied")
                    .isSameAs(cause);
            assertThat(failure.failedCardOrdinal())
                    .as("this form attributes the failure to no particular card, so the ordinal "
                            + "is the not-applicable sentinel")
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("queue name, both codes and the failing card ordinal all round-trip together")
        void queueNameBothCodesAndTheOrdinalAllRoundTrip() {
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            final JobSubmissionException failure = new JobSubmissionException(
                    ALTERNATE_QUEUE_NAME, RESPONSE_CODE, REASON_CODE, FAILING_CARD_ORDINAL, cause);

            assertThat(failure.queueName()).isEqualTo(ALTERNATE_QUEUE_NAME);
            assertThat(failure.responseCode()).isEqualTo(RESPONSE_CODE);
            assertThat(failure.reasonCode()).isEqualTo(REASON_CODE);
            assertThat(failure.failedCardOrdinal()).isEqualTo(FAILING_CARD_ORDINAL);
            assertThat(failure.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the codes are carried as strings, so leading zeros survive intact")
        void theCodesAreCarriedAsStringsSoLeadingZerosSurvive() {
            final JobSubmissionException failure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, "0000", "0007",
                    new IOException(CAUSE_MESSAGE));

            assertThat(failure.responseCode())
                    .as("the legacy diagnostic printed the code as it stood; parsing it to a "
                            + "number would drop the leading zeros and lose that fidelity")
                    .isEqualTo("0000")
                    .hasSize(4);
            assertThat(failure.reasonCode())
                    .isEqualTo("0007")
                    .hasSize(4);
            assertThat(failure.getMessage())
                    .as("the composed message shows the codes as supplied, zeros included")
                    .contains("0000")
                    .contains("0007");
        }

        @Test
        @DisplayName("the codes are neither trimmed nor rewritten - a non-empty value is carried byte for byte")
        void theCodesAreNeitherTrimmedNorRewritten() {
            final String paddedResponse = " 12 ";
            final String mixedCaseReason = "ReAs-Xy";

            final JobSubmissionException failure = new JobSubmissionException(
                    " " + ALTERNATE_QUEUE_NAME + " ", paddedResponse, mixedCaseReason,
                    new IOException(CAUSE_MESSAGE));

            assertThat(failure.responseCode())
                    .as("no trimming: the surrounding spaces are part of what the caller supplied")
                    .isEqualTo(paddedResponse);
            assertThat(failure.reasonCode())
                    .as("no case folding and no rewriting of any kind")
                    .isEqualTo(mixedCaseReason);
            assertThat(failure.queueName())
                    .as("the queue name is normalised only for absence, never trimmed")
                    .isEqualTo(" " + ALTERNATE_QUEUE_NAME + " ");
        }

        @Test
        @DisplayName("absent queue name and codes normalise to a value that is never null and never the word null")
        void absentQueueNameAndCodesNormaliseWithoutEverProducingNull() {
            final String absent = null;
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            final JobSubmissionException failure =
                    new JobSubmissionException(absent, absent, absent, cause);

            assertThat(failure.queueName())
                    .as("an absent queue name falls back to the legacy queue name")
                    .isNotNull()
                    .isEqualTo(JobSubmissionException.DEFAULT_QUEUE_NAME)
                    .isNotEqualTo("null");
            assertThat(failure.responseCode())
                    .as("an absent code becomes the empty string, never null and never the text "
                            + "\"null\"")
                    .isNotNull()
                    .isNotEqualTo("null")
                    .isEmpty();
            assertThat(failure.reasonCode())
                    .isNotNull()
                    .isNotEqualTo("null")
                    .isEmpty();
            assertThat(failure.getMessage())
                    .as("with no context available the message is the frozen literal unchanged, "
                            + "and no part of it can read as the word \"null\"")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE)
                    .doesNotContain("null")
                    .doesNotContainIgnoringCase("null");
            assertThat(failure.failedCardOrdinal())
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("an empty queue name and empty codes normalise exactly as absent ones do")
        void anEmptyQueueNameAndEmptyCodesNormaliseExactlyAsAbsentOnesDo() {
            final JobSubmissionException failure = new JobSubmissionException(
                    "", "", "", JobSubmissionException.ORDINAL_NOT_APPLICABLE,
                    new IOException(CAUSE_MESSAGE));

            assertThat(failure.queueName()).isEqualTo(JobSubmissionException.DEFAULT_QUEUE_NAME);
            assertThat(failure.responseCode()).isEmpty();
            assertThat(failure.reasonCode()).isEmpty();
            assertThat(failure.getMessage())
                    .as("an empty code contributes no diagnostic suffix, so the frozen literal "
                            + "stands alone")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The failing card ordinal and its sentinel
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("failing card ordinal")
    class FailingCardOrdinal {

        @Test
        @DisplayName("-1 means not applicable: every constructor that omits an ordinal reports it")
        void everyConstructorThatOmitsAnOrdinalReportsTheNotApplicableSentinel() {
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            assertThat(new JobSubmissionException(cause).failedCardOrdinal())
                    .as("the cause-only form attributes the failure to no card, which is a "
                            + "different statement from \"card zero failed\"")
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
            assertThat(new JobSubmissionException(SUPPLIED_MESSAGE_PROBE, cause)
                    .failedCardOrdinal())
                    .as("the message-and-cause form likewise attributes the failure to no card")
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
            assertThat(new JobSubmissionException(
                    ALTERNATE_QUEUE_NAME, RESPONSE_CODE, REASON_CODE, cause).failedCardOrdinal())
                    .as("the diagnostic-context form carries codes but still no card ordinal")
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("ordinals inside the 17-card image round-trip unchanged, first, middle and sentinel")
        void ordinalsInsideTheSeventeenCardImageRoundTripUnchanged() {
            assertThat(ordinalOf(FIRST_CARD_ORDINAL))
                    .as("the legacy card index is one-based, so the first card is 1 and not 0")
                    .isEqualTo(FIRST_CARD_ORDINAL);
            assertThat(ordinalOf(FAILING_CARD_ORDINAL))
                    .isEqualTo(FAILING_CARD_ORDINAL);
            assertThat(ordinalOf(SENTINEL_CARD_ORDINAL))
                    .as("the terminating sentinel card is itself transmitted, so it too can be "
                            + "the card a publish fails on")
                    .isEqualTo(SENTINEL_CARD_ORDINAL);
        }

        @Test
        @DisplayName("an ordinal of zero is carried unchanged and is treated as not applicable, never as card zero")
        void anOrdinalOfZeroIsCarriedUnchangedAndTreatedAsNotApplicable() {
            final JobSubmissionException failure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE, REASON_CODE, 0,
                    new IOException(CAUSE_MESSAGE));

            assertThat(failure.failedCardOrdinal())
                    .as("the value is carried exactly as supplied: it is neither rejected nor "
                            + "clamped nor renumbered")
                    .isZero();
            assertThat(failure.getMessage())
                    .as("but because the index is one-based, zero contributes no card suffix - "
                            + "which is precisely the ambiguity the negative sentinel avoids")
                    .doesNotContain("CARD:");
        }

        @Test
        @DisplayName("a negative ordinal other than the sentinel is carried unchanged and contributes no card suffix")
        void aNegativeOrdinalOtherThanTheSentinelIsCarriedUnchanged() {
            final JobSubmissionException failure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE, REASON_CODE, -7,
                    new IOException(CAUSE_MESSAGE));

            assertThat(failure.failedCardOrdinal())
                    .as("no validation is performed and none is expected: the value is carried")
                    .isEqualTo(-7);
            assertThat(failure.getMessage()).doesNotContain("CARD:");
        }

        private int ordinalOf(final int ordinal) {
            return new JobSubmissionException(JobSubmissionException.DEFAULT_QUEUE_NAME,
                    RESPONSE_CODE, REASON_CODE, ordinal, new IOException(CAUSE_MESSAGE))
                    .failedCardOrdinal();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The cause-only and message-and-cause constructors
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("cause-only and message-and-cause constructors")
    class CauseAndMessageConstructors {

        @Test
        @DisplayName("the cause-only constructor defaults the message and the queue name and records no codes")
        void theCauseOnlyConstructorDefaultsTheMessageAndTheQueueName() {
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            final JobSubmissionException failure = new JobSubmissionException(cause);

            assertThat(failure.getCause())
                    .as("the messaging client's own failure is chained by identity")
                    .isSameAs(cause);
            assertThat(failure.getMessage())
                    .as("with no distinguishable codes to report, the message is exactly the "
                            + "frozen literal")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(failure.queueName()).isEqualTo(JobSubmissionException.DEFAULT_QUEUE_NAME);
            assertThat(failure.responseCode()).isEmpty();
            assertThat(failure.reasonCode()).isEmpty();
            assertThat(failure.failedCardOrdinal())
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("the message-and-cause constructor uses the supplied message in place of the default")
        void theMessageAndCauseConstructorUsesTheSuppliedMessage() {
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            final JobSubmissionException failure =
                    new JobSubmissionException(SUPPLIED_MESSAGE_PROBE, cause);

            assertThat(SUPPLIED_MESSAGE_PROBE)
                    .as("the probe must differ from the default, otherwise the override assertion "
                            + "below would pass even if the supplied message were discarded")
                    .isNotEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(failure.getMessage())
                    .as("the supplied text is used exactly as given, with no prefixing and no "
                            + "suffixing")
                    .isEqualTo(SUPPLIED_MESSAGE_PROBE)
                    .isNotEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(failure.queueName()).isEqualTo(JobSubmissionException.DEFAULT_QUEUE_NAME);
            assertThat(failure.responseCode()).isEmpty();
            assertThat(failure.reasonCode()).isEmpty();
            assertThat(failure.failedCardOrdinal())
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("an absent or empty message falls back to the frozen literal, so operator-facing text is never missing")
        void anAbsentOrEmptyMessageFallsBackToTheFrozenLiteral() {
            final String absent = null;
            final Throwable cause = new IOException(CAUSE_MESSAGE);

            assertThat(new JobSubmissionException(absent, cause).getMessage())
                    .as("an absent message must not surface as the word \"null\" on an operator's "
                            + "screen or in a log line")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE)
                    .doesNotContainIgnoringCase("null");
            assertThat(new JobSubmissionException("", cause).getMessage())
                    .as("an empty message is treated as absent, exactly as an empty code is")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("an absent cause is tolerated on every overload, because a failure may surface with no chainable detail")
        void anAbsentCauseIsToleratedOnEveryOverload() {
            final Throwable noCause = null;

            assertThatCode(() -> new JobSubmissionException(noCause)).doesNotThrowAnyException();

            assertThat(new JobSubmissionException(noCause).getCause()).isNull();
            assertThat(new JobSubmissionException(SUPPLIED_MESSAGE_PROBE, noCause).getCause())
                    .isNull();
            assertThat(new JobSubmissionException(ALTERNATE_QUEUE_NAME, RESPONSE_CODE, REASON_CODE,
                    noCause).getCause()).isNull();
            assertThat(new JobSubmissionException(ALTERNATE_QUEUE_NAME, RESPONSE_CODE, REASON_CODE,
                    FAILING_CARD_ORDINAL, noCause).getCause()).isNull();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The composed message
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("composed message")
    class ComposedMessage {

        @Test
        @DisplayName("the composed message begins with the frozen literal, names the queue, and shows both codes")
        void theComposedMessageBeginsWithTheFrozenLiteralAndShowsBothCodes() {
            final JobSubmissionException failure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE, REASON_CODE,
                    new IOException(CAUSE_MESSAGE));

            assertThat(failure.getMessage())
                    .as("an operator reading the log must see what the legacy screen diagnostic "
                            + "showed: the queue, the response and the reason")
                    .startsWith(JobSubmissionException.DEFAULT_MESSAGE)
                    .contains(JobSubmissionException.DEFAULT_QUEUE_NAME)
                    .contains("(JOBS)")
                    .contains(RESPONSE_CODE)
                    .contains(REASON_CODE)
                    .contains("RESP:")
                    .contains("REAS:");
        }

        @Test
        @DisplayName("the composed message appends the failing card ordinal when one is supplied")
        void theComposedMessageAppendsTheFailingCardOrdinal() {
            final JobSubmissionException failure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE, REASON_CODE,
                    FAILING_CARD_ORDINAL, new IOException(CAUSE_MESSAGE));

            assertThat(failure.getMessage())
                    .as("the ordinal is what makes \"the remaining cards were not sent\" "
                            + "actionable rather than merely true")
                    .startsWith(JobSubmissionException.DEFAULT_MESSAGE)
                    .contains("CARD:" + FAILING_CARD_ORDINAL);
        }

        @Test
        @DisplayName("a caller-supplied queue name is retained on the accessor but never appended to the frozen text")
        void aCallerSuppliedQueueNameIsRetainedButNeverAppendedToTheFrozenText() {
            final JobSubmissionException failure = new JobSubmissionException(
                    ALTERNATE_QUEUE_NAME, RESPONSE_CODE, REASON_CODE,
                    new IOException(CAUSE_MESSAGE));

            assertThat(failure.queueName())
                    .as("the physical queue a deployment addressed is still available to the "
                            + "caller for logging")
                    .isEqualTo(ALTERNATE_QUEUE_NAME);
            assertThat(failure.getMessage())
                    .as("but the operator-facing text is frozen: the literal already names the "
                            + "queue, so a differently named physical queue cannot make it drift")
                    .doesNotContain(ALTERNATE_QUEUE_NAME)
                    .startsWith(JobSubmissionException.DEFAULT_MESSAGE)
                    .contains("(JOBS)");
        }

        @Test
        @DisplayName("the message promises no retry and offers no advisory prose, because the legacy path did not retry")
        void theMessagePromisesNoRetryAndOffersNoAdvisoryProse() {
            final JobSubmissionException withContext = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE, REASON_CODE,
                    FAILING_CARD_ORDINAL, new IOException(CAUSE_MESSAGE));
            final JobSubmissionException withoutContext =
                    new JobSubmissionException(new IOException(CAUSE_MESSAGE));

            assertThat(withContext.getMessage())
                    .as("the legacy path did not retry, it continued; inventing retry language "
                            + "would misdescribe the behaviour")
                    .doesNotContainIgnoringCase("retry")
                    .doesNotContainIgnoringCase("retried")
                    .doesNotContainIgnoringCase("please")
                    .doesNotContainIgnoringCase("contact");
            assertThat(withoutContext.getMessage())
                    .doesNotContainIgnoringCase("retry")
                    .doesNotContainIgnoringCase("retried")
                    .doesNotContainIgnoringCase("please")
                    .doesNotContainIgnoringCase("contact");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Type identity and non-assignability
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("type identity")
    class TypeIdentity {

        @Test
        @DisplayName("it is an unchecked exception extending RuntimeException directly")
        void itIsAnUncheckedExceptionExtendingRuntimeExceptionDirectly() {
            final JobSubmissionException failure =
                    new JobSubmissionException(new IOException(CAUSE_MESSAGE));

            assertThat(failure).isInstanceOf(RuntimeException.class);
            assertThat(JobSubmissionException.class.getSuperclass())
                    .isEqualTo(RuntimeException.class);
        }

        @Test
        @DisplayName("it is not an abend: ERROROPTION(IGNORE) ignores the write error, so it can never terminate a program")
        void itIsNotAnAbend() {
            assertThat(AbendException.class.isAssignableFrom(JobSubmissionException.class))
                    .as("making this an abend subtype would turn an ignored condition into a "
                            + "program termination - a direct behavioural regression against a "
                            + "queue definition that ignores the error outright")
                    .isFalse();
            assertThat(JobSubmissionException.class.isAssignableFrom(AbendException.class))
                    .as("the two types are unrelated in both directions, so no handler for one "
                            + "can silently capture the other")
                    .isFalse();
            assertThat(new JobSubmissionException(new IOException(CAUSE_MESSAGE)))
                    .isNotInstanceOf(AbendException.class);
        }

        @Test
        @DisplayName("it is not a file status failure: a queue write is not a record-oriented file operation")
        void itIsNotAFileStatusFailure() {
            assertThat(FileStatusException.class.isAssignableFrom(JobSubmissionException.class))
                    .as("a queue-write failure reports a response and reason code pair, not a "
                            + "two-byte file status, so the two failure surfaces stay disjoint")
                    .isFalse();
            assertThat(JobSubmissionException.class.isAssignableFrom(FileStatusException.class))
                    .isFalse();
            assertThat(new JobSubmissionException(new IOException(CAUSE_MESSAGE)))
                    .isNotInstanceOf(FileStatusException.class);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Serialization identity
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("serialization identity")
    class SerializationIdentity {

        @Test
        @DisplayName("serialVersionUID is explicitly declared as 1L rather than left to a compiler-generated hash")
        void serialVersionUidIsExplicitlyDeclaredAsOne() {
            final ObjectStreamClass descriptor =
                    ObjectStreamClass.lookup(JobSubmissionException.class);

            assertThat(descriptor)
                    .as("the type is serializable because every throwable is, so a stream "
                            + "descriptor must exist for it")
                    .isNotNull();
            assertThat(descriptor.getSerialVersionUID())
                    .as("an explicit identity keeps the wire form stable across recompilation; a "
                            + "generated hash would change with any field or signature edit")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the whole failure context survives a serialization round trip")
        void theWholeFailureContextSurvivesASerializationRoundTrip()
                throws IOException, ClassNotFoundException {
            final JobSubmissionException original = new JobSubmissionException(
                    ALTERNATE_QUEUE_NAME, RESPONSE_CODE, REASON_CODE, FAILING_CARD_ORDINAL,
                    new IOException(CAUSE_MESSAGE));

            final JobSubmissionException restored = deserialize(serialize(original));

            assertThat(restored.queueName()).isEqualTo(ALTERNATE_QUEUE_NAME);
            assertThat(restored.responseCode()).isEqualTo(RESPONSE_CODE);
            assertThat(restored.reasonCode()).isEqualTo(REASON_CODE);
            assertThat(restored.failedCardOrdinal()).isEqualTo(FAILING_CARD_ORDINAL);
            assertThat(restored.getMessage())
                    .as("the operator-facing text and its diagnostic suffix cross the wire intact")
                    .isEqualTo(original.getMessage())
                    .startsWith(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(restored.getCause())
                    .as("the chained cause is reconstructed as an equivalent instance rather than "
                            + "being dropped")
                    .isInstanceOf(IOException.class)
                    .hasMessage(CAUSE_MESSAGE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Test support: the emitter-loop simulation and the serialization plumbing
    // ------------------------------------------------------------------------------------------

    /**
     * The observable result of one simulated pass over the job image.
     *
     * @param cardsAttempted  how many cards the loop reached, which is what proves whether it ran
     *                        to completion
     * @param cardsPublished  how many cards were accepted by the simulated queue
     * @param failuresCaught  how many failures the handler caught and absorbed
     * @param lastCaught      the last failure the handler caught, or {@code null} if none occurred
     */
    private record EmitterOutcome(int cardsAttempted,
                                  int cardsPublished,
                                  int failuresCaught,
                                  JobSubmissionException lastCaught) {
    }

    /**
     * Simulates a publish of one card. Guards the fixed-width contract, then fails for exactly one
     * designated ordinal.
     *
     * <p>The width guard throws a different, deliberately uncaught type, so that a card of the
     * wrong width would escape the emitter simulation and fail the wrapping assertion instead of
     * being silently absorbed by the handler under test.
     *
     * @param cardImage      the fixed-width card payload
     * @param ordinal        the one-based ordinal of this card
     * @param failingOrdinal the one-based ordinal that must fail
     */
    private static void publishCard(final String cardImage,
                                    final int ordinal,
                                    final int failingOrdinal) {
        if (cardImage.length() != JobSubmissionException.RECORD_SIZE) {
            throw new IllegalStateException("a card payload must be exactly "
                    + JobSubmissionException.RECORD_SIZE + " characters wide, but ordinal "
                    + ordinal + " was " + cardImage.length());
        }
        if (ordinal == failingOrdinal) {
            throw new JobSubmissionException(JobSubmissionException.DEFAULT_QUEUE_NAME,
                    RESPONSE_CODE, REASON_CODE, ordinal, new IOException(CAUSE_MESSAGE));
        }
    }

    /**
     * Emits every card, catching a failure and continuing to the end of the image.
     *
     * <p>This is the executable statement of the non-aborting contract: the handler catches,
     * records and carries on, and the method returns a value that can only exist if the loop
     * reached its final iteration.
     *
     * @param cardCount      how many cards the image holds
     * @param failingOrdinal the one-based ordinal that fails
     * @return what the pass observed
     */
    private static EmitterOutcome emitEveryCardIgnoringFailure(final int cardCount,
                                                               final int failingOrdinal) {
        int attempted = 0;
        int published = 0;
        int failures = 0;
        JobSubmissionException lastCaught = null;
        for (int ordinal = FIRST_CARD_ORDINAL; ordinal <= cardCount; ordinal++) {
            attempted++;
            try {
                publishCard(cardImage(ordinal), ordinal, failingOrdinal);
                published++;
            } catch (JobSubmissionException failure) {
                // The queue definition ignores the error, so the handler absorbs it. A real caller
                // logs the response and reason codes here; nothing is re-thrown either way.
                failures++;
                lastCaught = failure;
            }
        }
        return new EmitterOutcome(attempted, published, failures, lastCaught);
    }

    /**
     * Emits cards until one fails, mirroring the legacy loop's own error flag, and still returns
     * normally afterwards.
     *
     * <p>The legacy emitting loop tested that flag as one of its termination conditions, so a
     * failed write stopped further emission - and yet the transaction still completed. Both halves
     * of that statement matter, and this pass is what proves the second half is not lost when the
     * first half is honoured.
     *
     * @param cardCount      how many cards the image holds
     * @param failingOrdinal the one-based ordinal that fails
     * @return what the pass observed
     */
    private static EmitterOutcome emitCardsStoppingOnFailure(final int cardCount,
                                                             final int failingOrdinal) {
        int attempted = 0;
        int published = 0;
        int failures = 0;
        JobSubmissionException lastCaught = null;
        boolean errorFlag = false;
        for (int ordinal = FIRST_CARD_ORDINAL; ordinal <= cardCount && !errorFlag; ordinal++) {
            attempted++;
            try {
                publishCard(cardImage(ordinal), ordinal, failingOrdinal);
                published++;
            } catch (JobSubmissionException failure) {
                failures++;
                lastCaught = failure;
                errorFlag = true;
            }
        }
        return new EmitterOutcome(attempted, published, failures, lastCaught);
    }

    /**
     * Builds a synthetic fixed-width card payload of exactly the contractual record size.
     *
     * <p>The content is synthetic on purpose: no legacy card text is reproduced anywhere in this
     * module. Only the width is contractual, and the space padding is what reproduces the fixed
     * record format rather than a trimmed string.
     *
     * @param ordinal the one-based ordinal of the card
     * @return a payload of exactly {@link JobSubmissionException#RECORD_SIZE} characters
     */
    private static String cardImage(final int ordinal) {
        final String body = "SUBMISSION-CARD-" + ordinal;
        return body + " ".repeat(JobSubmissionException.RECORD_SIZE - body.length());
    }

    /**
     * Writes the failure to a byte array using the platform serialization mechanism.
     *
     * @param failure the failure to write
     * @return its serialized form
     * @throws IOException if the stream rejects the write
     */
    private static byte[] serialize(final JobSubmissionException failure) throws IOException {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(sink)) {
            stream.writeObject(failure);
        }
        return sink.toByteArray();
    }

    /**
     * Reads a failure back from its serialized form.
     *
     * @param payload the serialized form
     * @return the reconstructed failure
     * @throws IOException            if the stream rejects the read
     * @throws ClassNotFoundException if the type is not resolvable, which would itself be a defect
     */
    private static JobSubmissionException deserialize(final byte[] payload)
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            return (JobSubmissionException) stream.readObject();
        }
    }
}
