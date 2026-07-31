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

/**
 * Signals that a job-submission card could not be handed to the batch-submission queue.
 *
 * <h2>Legacy origin</h2>
 *
 * <p>The CardDemo estate contains exactly one online-to-batch bridge: the transient-data-queue
 * write in {@code app/cbl/CORPT00C.cbl}, paragraph {@code WIRTE-JOBSUB-TDQ} at lines 515 to 523.
 * Transaction {@code CR00} (the transaction-report request screen) assembles a job stream as a
 * fixed array of card images, then hands them to the queue one card per write, capturing a
 * response code and a reason code on every write. That single paragraph is the entirety of the
 * legacy submission mechanism, so this exception is the entirety of its failure surface. In the
 * migrated system the queue write becomes an Amazon SQS FIFO publish and this type is what a
 * failed publish is reported as.
 *
 * <h2>Queue contract</h2>
 *
 * <p>The queue is declared in {@code app/csd/CARDDEMO.CSD} at lines 499 to 505. Its attributes
 * are contractual rather than incidental, and each one constrains the replacement:
 *
 * <ul>
 *   <li>queue name {@code JOBS} - carried by {@link #DEFAULT_QUEUE_NAME}.</li>
 *   <li>record size 80 with record format fixed - carried by {@link #RECORD_SIZE}. One card is
 *       one message; the payload is a fixed-width 80-character image, never a trimmed string.</li>
 *   <li>block format unblocked - each card is an individually addressable record, so cards are
 *       published one message at a time rather than batched into a single payload.</li>
 *   <li>disposition MOD - writes append, so message order is significant and the FIFO
 *       replacement must preserve the order in which cards were emitted.</li>
 *   <li>type file output, type EXTRA, data buffers 1 - the queue is write-only from the online
 *       tier; nothing in the online tier ever reads it back.</li>
 *   <li>open time initial - the destination is available for the whole run, so a failure is a
 *       failure of the individual write and not of a deferred connection.</li>
 *   <li>DD name {@code INREADER} - the mainframe internal reader, whose replacement is the
 *       queue consumed by the batch tier.</li>
 *   <li><strong>{@code ERROROPTION(IGNORE)}</strong> - the decisive attribute. See below.</li>
 * </ul>
 *
 * <h2>Non-fatal by contract</h2>
 *
 * <p>{@code ERROROPTION(IGNORE)} is the authority, and the program's own behaviour matches it
 * exactly. At {@code app/cbl/CORPT00C.cbl} lines 525 to 535 a non-normal response causes the
 * program to write the response and reason codes to its diagnostic channel, raise its error
 * flag, place the failure message in the screen message field, reposition the cursor and re-send
 * the screen. It does not abend and it does not abort the request; control returns to the user
 * normally. Because the emitter's loop at lines 498 to 508 also tests that same error flag, a
 * failed write additionally stops the loop, so the cards after the failing one are never sent.
 *
 * <p>Three consequences follow, and they are the defining rules of this class:
 *
 * <ol>
 *   <li>the publish failure is <strong>logged</strong>;</li>
 *   <li>the <strong>remaining cards are not sent</strong>;</li>
 *   <li><strong>control returns normally</strong> - the caller's request completes.</li>
 * </ol>
 *
 * <p>{@code JobSubmissionService} is therefore expected to <strong>catch</strong> this exception
 * at the publish boundary and convert it into a non-fatal response condition. It must never be
 * allowed to escape to a request-aborting handler, because doing so would abort a request that
 * the legacy system completes. The type is an unchecked {@link RuntimeException} so that service
 * signatures stay clean, and it exposes everything a caller needs in order to log the failure
 * and continue without re-throwing.
 *
 * <p>Consistent with that, this class deliberately extends {@link RuntimeException} directly and
 * has <em>no</em> relationship of any kind with the abend type in this package. It also exposes
 * no API that would suggest terminality: nothing in its surface reports a fatality flag, an abend
 * code, a graded seriousness value or an instruction to abort. A non-normal queue write is a
 * reportable condition, not a terminating one.
 *
 * <h2>Caller obligation: log before reporting</h2>
 *
 * <p>The legacy program writes the response and reason codes to its diagnostic channel
 * <em>before</em> it moves the failure message into the screen field and re-sends the screen. The
 * migrated caller must preserve that ordering: {@code JobSubmissionService} is required to log
 * {@link #responseCode()} and {@link #reasonCode()} through SLF4J at the publish boundary,
 * <strong>before</strong> returning the non-fatal condition to the user. The codes must never
 * live only inside the exception message, because the message is an operator-facing contract
 * whose text is frozen and is not a substitute for a diagnostic record.
 *
 * <h2>Message text</h2>
 *
 * <p>{@link #DEFAULT_MESSAGE} reproduces, character for character, the literal the legacy program
 * places in the screen message field. It is an external interface contract: operators and
 * downstream tooling match on it, so it is frozen and no alternative wording is introduced
 * anywhere in this class. Every message this class produces begins with that literal exactly;
 * where diagnostic context is available it is appended as a suffix using the same
 * {@code RESP:} and {@code REAS:} labels the legacy diagnostic channel already uses, so no new
 * operator-facing vocabulary is invented. The queue name is deliberately <em>not</em> appended,
 * both because the frozen literal already names the queue and so that the operator-facing text
 * cannot drift when a deployment addresses a differently named physical queue.
 *
 * <h2>Card sequence</h2>
 *
 * <p>This class does not model the card sequence; {@code JclCardImageBuilder} owns it. Two facts
 * about that sequence matter to a consumer, however. The emitter's loop terminates on a
 * {@code /*EOF} sentinel card (or on a blank or low-values card), and the sentinel card is
 * <strong>itself transmitted</strong> before the loop ends, because the loop sets its
 * termination flag and only then performs the write ({@code app/cbl/CORPT00C.cbl} lines 501 to
 * 508). A consumer counting messages must therefore expect the sentinel. The legacy card array
 * holds up to 1000 cards and its index is one-based, which is why
 * {@link #failedCardOrdinal()} is one-based as well.
 *
 * <h2>Documented source anomaly</h2>
 *
 * <p>The paragraph name at {@code app/cbl/CORPT00C.cbl} line 515 is misspelled in the source: it
 * reads {@code WIRTE-JOBSUB-TDQ} rather than the intended spelling. The misspelling is recorded
 * here so that the traceability row remains findable under the original spelling, and it is
 * carried as a decision-log entry rather than corrected. It is deliberately not propagated into
 * any Java identifier; the method that replaces the paragraph in {@code JobSubmissionService} is
 * spelled correctly.
 *
 * <h2>Layer position</h2>
 *
 * <p>This type sits in the leaf exception layer and depends on nothing above it. It declares no
 * imports at all: the AWS or messaging failure is carried as a {@link Throwable} cause rather
 * than as a typed field, precisely so that no messaging, persistence or framework type leaks
 * into this layer. It holds no logger and writes to no stream; emitting the diagnostic is the
 * caller's obligation, as described above.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced in
 * this module; the citations above are references, not transcriptions.
 */
public class JobSubmissionException extends RuntimeException {

    /**
     * Serialization identity. Declared explicitly and pinned: {@link Throwable} is
     * {@link java.io.Serializable}, and the module compiles under {@code -Xlint:all -Werror},
     * which promotes the missing-serial-version-uid warning to a build error.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The operator-facing failure text, reproduced character for character from the literal the
     * legacy program places in its screen message field at {@code app/cbl/CORPT00C.cbl} line 531.
     *
     * <p>The value is {@code Unable to Write TDQ (JOBS)...} - capital {@code U} on {@code Unable},
     * capital {@code W} on {@code Write}, {@code TDQ} in capitals, a single space, then
     * {@code (JOBS)} parenthesised and capitalised, then exactly three full stops. The three
     * characters at the end are three separate ASCII full stops and not a single ellipsis
     * character, and there is no trailing space and no further punctuation.
     *
     * <p>This is an external interface contract and is therefore frozen. It is used as the
     * default message and as the leading text of every message this class composes, and no
     * alternative wording is defined anywhere in this class.
     */
    public static final String DEFAULT_MESSAGE = "Unable to Write TDQ (JOBS)...";

    /**
     * The legacy queue name, {@code JOBS}, as declared at {@code app/csd/CARDDEMO.CSD} line 499
     * and named in the frozen message text. Used whenever a caller supplies no queue name.
     */
    public static final String DEFAULT_QUEUE_NAME = "JOBS";

    /**
     * The fixed record size of the submission queue, 80 characters, from
     * {@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)} at
     * {@code app/csd/CARDDEMO.CSD} lines 502 to 503, and matching the 80-character card field
     * the legacy program writes from.
     *
     * <p>The width is contractual: one card is one message, each message payload is a fixed-width
     * 80-character image, and {@code BLOCKFORMAT(UNBLOCKED)} means cards are published
     * individually rather than concatenated. The constant is published here so that a caller
     * reporting a rejected publish can state the width the contract requires without reaching
     * into the card-building layer.
     */
    public static final int RECORD_SIZE = 80;

    /**
     * Sentinel for {@link #failedCardOrdinal()} meaning "not applicable", used when the failure
     * is not attributable to one identified card in the sequence.
     *
     * <p>The legacy card index is one-based, so every genuine ordinal is greater than zero and
     * this negative value cannot collide with one. Any value that is not greater than zero is
     * treated as not applicable, and this constant is the canonical way to express it.
     */
    public static final int ORDINAL_NOT_APPLICABLE = -1;

    /**
     * The queue the failed publish was addressed to. Never {@code null}; defaults to
     * {@link #DEFAULT_QUEUE_NAME}.
     */
    private final String queueName;

    /**
     * The response code reported by the publish attempt. Never {@code null}; the empty string
     * when none was supplied. Carried uninterpreted.
     */
    private final String responseCode;

    /**
     * The reason code reported by the publish attempt. Never {@code null}; the empty string when
     * none was supplied. Carried uninterpreted.
     */
    private final String reasonCode;

    /**
     * The one-based ordinal of the card whose publish failed, or
     * {@link #ORDINAL_NOT_APPLICABLE} when the failure is not attributable to one card.
     */
    private final int failedCardOrdinal;

    /**
     * Creates a failure carrying only the underlying cause.
     *
     * <p>The message is exactly {@link #DEFAULT_MESSAGE} and the queue name defaults to
     * {@link #DEFAULT_QUEUE_NAME}. No response code, no reason code and no card ordinal are
     * recorded, so {@link #responseCode()} and {@link #reasonCode()} return the empty string and
     * {@link #failedCardOrdinal()} returns {@link #ORDINAL_NOT_APPLICABLE}.
     *
     * <p>Use this form when the messaging client surfaces no distinguishable codes. The cause is
     * the underlying publish failure and is chained rather than swallowed, so the original
     * diagnostic detail survives for the caller to log.
     *
     * @param cause the underlying publish failure, typically the messaging client's own
     *              exception; may be {@code null} when no cause is available
     */
    public JobSubmissionException(final Throwable cause) {
        this(DEFAULT_QUEUE_NAME, null, null, ORDINAL_NOT_APPLICABLE, cause);
    }

    /**
     * Creates a failure carrying an explicit message and the underlying cause.
     *
     * <p>The message is used exactly as supplied; this class invents no operator-facing text of
     * its own, so a caller passing a message is expected to pass the frozen contract literal.
     * A {@code null} or empty message falls back to {@link #DEFAULT_MESSAGE}, which is that same
     * literal, so the operator-facing text is never absent and is never the word "null".
     *
     * <p>The queue name defaults to {@link #DEFAULT_QUEUE_NAME}; no codes and no card ordinal are
     * recorded.
     *
     * @param message the failure text; when {@code null} or empty, {@link #DEFAULT_MESSAGE} is
     *                used instead
     * @param cause   the underlying publish failure; may be {@code null}
     */
    public JobSubmissionException(final String message, final Throwable cause) {
        super(orDefault(message, DEFAULT_MESSAGE), cause);
        this.queueName = DEFAULT_QUEUE_NAME;
        this.responseCode = "";
        this.reasonCode = "";
        this.failedCardOrdinal = ORDINAL_NOT_APPLICABLE;
    }

    /**
     * Creates a failure carrying the queue name and the response and reason codes reported by the
     * publish attempt, without attributing the failure to a particular card.
     *
     * <p>Equivalent to calling
     * {@link #JobSubmissionException(String, String, String, int, Throwable)} with
     * {@link #ORDINAL_NOT_APPLICABLE} as the ordinal.
     *
     * @param queueName    the queue the publish was addressed to; when {@code null} or empty,
     *                     {@link #DEFAULT_QUEUE_NAME} is used
     * @param responseCode the response code reported by the publish attempt, carried
     *                     uninterpreted; when {@code null} it becomes the empty string
     * @param reasonCode   the reason code reported by the publish attempt, carried
     *                     uninterpreted; when {@code null} it becomes the empty string
     * @param cause        the underlying publish failure; may be {@code null}
     */
    public JobSubmissionException(final String queueName,
                                  final String responseCode,
                                  final String reasonCode,
                                  final Throwable cause) {
        this(queueName, responseCode, reasonCode, ORDINAL_NOT_APPLICABLE, cause);
    }

    /**
     * Creates a failure carrying the queue name, the response and reason codes, and the ordinal
     * of the card whose publish failed.
     *
     * <p>This is the fullest form and the one the publish loop should use, because the ordinal
     * lets the caller log exactly where in the card sequence the submission stopped - which is
     * the information that makes "the remaining cards are not sent" actionable rather than
     * merely true.
     *
     * <p>The composed message begins with {@link #DEFAULT_MESSAGE} exactly and then appends any
     * supplied codes and ordinal as a diagnostic suffix. The codes are recorded verbatim and are
     * never interpreted, mapped, ranked or classified as retryable; interpreting a messaging
     * client's codes is not this layer's concern.
     *
     * @param queueName         the queue the publish was addressed to; when {@code null} or
     *                          empty, {@link #DEFAULT_QUEUE_NAME} is used
     * @param responseCode      the response code reported by the publish attempt, carried
     *                          uninterpreted; when {@code null} it becomes the empty string
     * @param reasonCode        the reason code reported by the publish attempt, carried
     *                          uninterpreted; when {@code null} it becomes the empty string
     * @param failedCardOrdinal the one-based ordinal of the card whose publish failed, or
     *                          {@link #ORDINAL_NOT_APPLICABLE} when the failure is not
     *                          attributable to one card
     * @param cause             the underlying publish failure; may be {@code null}
     */
    public JobSubmissionException(final String queueName,
                                  final String responseCode,
                                  final String reasonCode,
                                  final int failedCardOrdinal,
                                  final Throwable cause) {
        super(composeMessage(responseCode, reasonCode, failedCardOrdinal), cause);
        this.queueName = orDefault(queueName, DEFAULT_QUEUE_NAME);
        this.responseCode = orDefault(responseCode, "");
        this.reasonCode = orDefault(reasonCode, "");
        this.failedCardOrdinal = failedCardOrdinal;
    }

    /**
     * Returns the queue the failed publish was addressed to.
     *
     * @return the queue name, never {@code null}; {@link #DEFAULT_QUEUE_NAME} when the caller
     *         supplied none
     */
    public String queueName() {
        return this.queueName;
    }

    /**
     * Returns the response code reported by the publish attempt, exactly as supplied and without
     * interpretation.
     *
     * <p>The value is a string rather than a number so that it accommodates both the numeric
     * codes the legacy queue write reported and the textual error codes a messaging client
     * reports, with no conversion and no loss on either side.
     *
     * <p>The caller is obliged to log this value at the publish boundary before returning the
     * non-fatal condition to the user.
     *
     * @return the response code, never {@code null}; the empty string when none was supplied,
     *         never the text "null"
     */
    public String responseCode() {
        return this.responseCode;
    }

    /**
     * Returns the reason code reported by the publish attempt, exactly as supplied and without
     * interpretation.
     *
     * <p>The caller is obliged to log this value alongside {@link #responseCode()} at the publish
     * boundary before returning the non-fatal condition to the user.
     *
     * @return the reason code, never {@code null}; the empty string when none was supplied, never
     *         the text "null"
     */
    public String reasonCode() {
        return this.reasonCode;
    }

    /**
     * Returns the one-based ordinal of the card whose publish failed.
     *
     * <p>The ordinal is one-based because the legacy card index is one-based. A value that is not
     * greater than zero - canonically {@link #ORDINAL_NOT_APPLICABLE} - means the failure is not
     * attributable to one identified card. The value is returned exactly as supplied and is not
     * clamped or renumbered.
     *
     * <p>Because a failed publish stops the emitting loop, this ordinal also identifies the point
     * from which the remaining cards were not sent.
     *
     * @return the one-based failing card ordinal, or a value not greater than zero when not
     *         applicable
     */
    public int failedCardOrdinal() {
        return this.failedCardOrdinal;
    }

    /**
     * The single null-normalisation helper: returns {@code value} unless it is absent, in which
     * case it returns {@code fallback}.
     *
     * <p>Absence means {@code null} or the empty string, and nothing else. The value is never
     * trimmed, folded or otherwise rewritten, so any non-empty input is returned byte for byte -
     * which is what lets the accessors promise that they hand back exactly what the caller
     * supplied. Normalising here rather than at each call site is also what guarantees no field
     * can ever hold {@code null} and no message can ever contain the text "null".
     *
     * @param value    the caller-supplied value, possibly {@code null} or empty
     * @param fallback the value to substitute when {@code value} is absent
     * @return {@code value} when it is neither {@code null} nor empty, otherwise {@code fallback}
     */
    private static String orDefault(final String value, final String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * The message-composition helper: builds the exception message from the frozen contract
     * literal plus whatever diagnostic context is available.
     *
     * <p>The result always begins with {@link #DEFAULT_MESSAGE} exactly, so the operator-facing
     * contract text is preserved verbatim as a prefix in every case. A supplied response code and
     * reason code are appended using the {@code RESP:} and {@code REAS:} labels the legacy
     * diagnostic channel already uses, and a card ordinal greater than zero is appended as
     * {@code CARD:}; each part is omitted when it is absent. No other vocabulary is introduced,
     * and the queue name is deliberately not appended because the frozen literal already names
     * the queue.
     *
     * <p>When nothing is available the result is {@link #DEFAULT_MESSAGE} unchanged. Only
     * caller-supplied codes and an ordinal ever reach the message, so no credential, key, token,
     * account identifier or endpoint can be introduced by composition.
     *
     * @param responseCode      the response code, possibly {@code null} or empty
     * @param reasonCode        the reason code, possibly {@code null} or empty
     * @param failedCardOrdinal the one-based failing card ordinal, or a value not greater than
     *                          zero when not applicable
     * @return the composed message, always beginning with {@link #DEFAULT_MESSAGE}
     */
    private static String composeMessage(final String responseCode,
                                         final String reasonCode,
                                         final int failedCardOrdinal) {
        final String response = orDefault(responseCode, "");
        final String reason = orDefault(reasonCode, "");
        final StringBuilder composed = new StringBuilder(DEFAULT_MESSAGE);
        if (!response.isEmpty()) {
            composed.append(" RESP:").append(response);
        }
        if (!reason.isEmpty()) {
            composed.append(" REAS:").append(reason);
        }
        // The legacy card index is one-based, so any ordinal at or below zero is "not applicable".
        if (failedCardOrdinal > 0) {
            composed.append(" CARD:").append(failedCardOrdinal);
        }
        return composed.toString();
    }
}
