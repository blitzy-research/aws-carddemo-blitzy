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
 * <p><strong>Legacy origin.</strong> The estate contains exactly one online-to-batch bridge: the
 * transient-data-queue write in {@code app/cbl/CORPT00C.cbl}, paragraph {@code WIRTE-JOBSUB-TDQ}
 * at lines 515 to 523. Transaction {@code CR00}, the transaction-report request screen, assembles
 * a job stream as a fixed array of card images and hands them to the queue one card per write,
 * capturing a response code and a reason code on every write. That paragraph is the entirety of
 * the legacy submission mechanism, so this type is the entirety of its failure surface. The queue
 * write becomes an Amazon SQS FIFO publish, and this type is what a failed publish is reported as.
 *
 * <p><strong>Queue contract.</strong> The queue is declared at {@code app/csd/CARDDEMO.CSD} lines
 * 499 to 505 and every attribute constrains the replacement. {@code RECORDSIZE(80)} with
 * {@code RECORDFORMAT(FIXED)} makes one card one message with a fixed-width 80-character payload
 * ({@link #RECORD_SIZE}), never a trimmed string; {@code BLOCKFORMAT(UNBLOCKED)} means cards are
 * published individually rather than batched into one payload; {@code DISPOSITION(MOD)} makes
 * writes append, so message order is significant and the FIFO replacement must preserve the order
 * in which cards were emitted; {@code TYPEFILE(OUTPUT)} makes the queue write-only from the online
 * tier; {@code OPENTIME(INITIAL)} means a failure is a failure of the individual write rather than
 * of a deferred connection; and {@code ERROROPTION(IGNORE)} is the decisive attribute. The
 * mapping of those attributes onto FIFO publishing is recorded as decision log entry D-36.
 *
 * <p><strong>Non-fatal by contract.</strong> {@code ERROROPTION(IGNORE)} is the authority and the
 * program's own behaviour matches it exactly: at {@code app/cbl/CORPT00C.cbl} lines 525 to 535 a
 * non-normal response makes the program write the response and reason codes to its diagnostic
 * channel, raise its error flag, place the failure message in the screen message field, reposition
 * the cursor and re-send the screen. It does not abend and it does not abort the request. Because
 * the emitting loop at lines 498 to 508 tests that same error flag, a failed write additionally
 * stops the loop, so the cards after the failing one are never sent. Three rules follow, and they
 * define this class: the failure is <strong>logged</strong>, the <strong>remaining cards are not
 * sent</strong>, and <strong>control returns normally</strong>. {@code JobSubmissionService} is
 * therefore expected to catch this exception at the publish boundary and convert it into a
 * non-fatal response condition; letting it reach a request-aborting handler would abort a request
 * that the legacy system completes.
 *
 * <p><strong>No terminality in the surface.</strong> The type extends {@link RuntimeException}
 * directly so service signatures stay clean, has no relationship of any kind with the abend type
 * in this package, and exposes no fatality flag, abend code, graded seriousness value or
 * instruction to abort. A non-normal queue write is a reportable condition, not a terminating one.
 *
 * <p><strong>Caller obligation: log before reporting.</strong> The legacy program writes the
 * response and reason codes to its diagnostic channel <em>before</em> it moves the failure message
 * into the screen field and re-sends the screen, and the migrated caller preserves that ordering:
 * {@link #responseCode()} and {@link #reasonCode()} are logged through SLF4J at the publish
 * boundary before the non-fatal condition is returned to the user. The codes must never live only
 * inside the exception message, because that message is a frozen operator-facing contract and not
 * a substitute for a diagnostic record.
 *
 * <p><strong>Message text.</strong> {@link #DEFAULT_MESSAGE} reproduces, character for character,
 * the literal the legacy program places in the screen message field, so it is an external
 * interface contract and is frozen; no alternative wording exists anywhere in this class. Every
 * message begins with that literal exactly, and available diagnostic context is appended as a
 * suffix using the {@code RESP:} and {@code REAS:} labels the legacy diagnostic channel already
 * uses, so no new operator-facing vocabulary is invented. The queue name is deliberately not
 * appended: the frozen literal already names the queue, and appending it would let the
 * operator-facing text drift when a deployment addresses a differently named physical queue.
 *
 * <p><strong>Card sequence.</strong> {@code JclCardImageBuilder} owns the sequence; two of its
 * facts reach a consumer of this type. The emitting loop terminates on a {@code /*EOF} sentinel
 * card, or on a blank or low-values card, and transmits that sentinel before ending, because it
 * sets its termination flag and only then performs the write ({@code app/cbl/CORPT00C.cbl} lines
 * 501 to 508) - so a consumer counting messages must expect it. The legacy card array holds up to
 * 1000 cards and its index is one-based, which is why {@link #failedCardOrdinal()} is one-based.
 *
 * <p><strong>Source anomaly.</strong> The paragraph name at {@code app/cbl/CORPT00C.cbl} line 515
 * is misspelled {@code WIRTE-JOBSUB-TDQ} in the source. It is carried as row 6 of the source
 * anomaly register rather than corrected, and is deliberately not propagated into any Java
 * identifier.
 *
 * <p><strong>Layer position.</strong> A leaf exception type that depends on nothing above it and
 * declares no imports at all: the messaging failure is carried as a {@link Throwable} cause rather
 * than as a typed field, so no messaging, persistence or framework type leaks into this layer. It
 * holds no logger and writes to no stream; emitting the diagnostic is the caller's obligation.
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
     * <p>The value is {@code Unable to Write TDQ (JOBS)...} - capital {@code U} and {@code W}, then
     * {@code TDQ} in capitals, a single space, {@code (JOBS)} parenthesised, then exactly three
     * separate ASCII full stops rather than one ellipsis character, with no trailing space and no
     * further punctuation. It is an external interface contract and therefore frozen: it is both
     * the default message and the leading text of every message this class composes, and no
     * alternative wording is defined anywhere here.
     */
    public static final String DEFAULT_MESSAGE = "Unable to Write TDQ (JOBS)...";

    /**
     * The legacy queue name, {@code JOBS}, as declared at {@code app/csd/CARDDEMO.CSD} line 499
     * and named in the frozen message text. Used whenever a caller supplies no queue name.
     */
    public static final String DEFAULT_QUEUE_NAME = "JOBS";

    /**
     * The fixed record size of the submission queue, 80 characters, from {@code RECORDSIZE(80)}
     * with {@code RECORDFORMAT(FIXED)} at {@code app/csd/CARDDEMO.CSD} lines 502 to 503, matching
     * the 80-character card field the legacy program writes from.
     *
     * <p>The width is contractual: one card is one message, each payload is a fixed-width
     * 80-character image, and {@code BLOCKFORMAT(UNBLOCKED)} means cards are published
     * individually rather than concatenated. Published here so that a caller reporting a rejected
     * publish can state the required width without reaching into the card-building layer.
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
     * {@link #DEFAULT_QUEUE_NAME}. No codes and no ordinal are recorded, so
     * {@link #responseCode()} and {@link #reasonCode()} return the empty string and
     * {@link #failedCardOrdinal()} returns {@link #ORDINAL_NOT_APPLICABLE}. Use this form when the
     * messaging client surfaces no distinguishable codes; the cause is chained rather than
     * swallowed, so the original diagnostic detail survives for the caller to log.
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
     * <p>The message is used exactly as supplied; this class invents no operator-facing text of its
     * own, so a caller passing a message is expected to pass the frozen contract literal. A
     * {@code null} or empty message falls back to {@link #DEFAULT_MESSAGE}, which is that same
     * literal, so the text is never absent and never the word "null". The queue name defaults to
     * {@link #DEFAULT_QUEUE_NAME}; no codes and no card ordinal are recorded.
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
     * <p>Equivalent to
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
     * Creates a failure carrying the queue name, the response and reason codes, and the ordinal of
     * the card whose publish failed.
     *
     * <p>This is the fullest form and the one the publish loop should use: the ordinal states where
     * in the card sequence the submission stopped, which is what makes "the remaining cards are not
     * sent" actionable rather than merely true. The composed message begins with
     * {@link #DEFAULT_MESSAGE} exactly and appends any supplied codes and ordinal as a diagnostic
     * suffix. Codes are recorded verbatim and never interpreted, mapped, ranked or classified as
     * retryable; interpreting a messaging client's codes is not this layer's concern.
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
     * <p>The value is a string rather than a number so that it accommodates both the numeric codes
     * the legacy queue write reported and the textual codes a messaging client reports, with no
     * conversion and no loss on either side. The caller logs it at the publish boundary before
     * returning the non-fatal condition.
     *
     * @return the response code, never {@code null}; the empty string when none was supplied,
     *         never the text "null"
     */
    public String responseCode() {
        return this.responseCode;
    }

    /**
     * Returns the reason code reported by the publish attempt, exactly as supplied and without
     * interpretation. The caller logs it alongside {@link #responseCode()} at the publish boundary
     * before returning the non-fatal condition.
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
     * <p>The ordinal is one-based because the legacy card index is one-based, and a value not
     * greater than zero - canonically {@link #ORDINAL_NOT_APPLICABLE} - means the failure is not
     * attributable to one identified card. The value is returned exactly as supplied, never
     * clamped or renumbered. Because a failed publish stops the emitting loop, the ordinal also
     * identifies the point from which the remaining cards were not sent.
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
     * <p>Absence means {@code null} or the empty string and nothing else; the value is never
     * trimmed or folded, so any non-empty input is returned byte for byte. Normalising here rather
     * than at each call site is what lets every field promise it is never {@code null} and every
     * message promise it never contains the text "null".
     *
     * @param value    the caller-supplied value, possibly {@code null} or empty
     * @param fallback the value to substitute when {@code value} is absent
     * @return {@code value} when it is neither {@code null} nor empty, otherwise {@code fallback}
     */
    private static String orDefault(final String value, final String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * Builds the exception message from the frozen contract literal plus whatever diagnostic
     * context is available.
     *
     * <p>The result always begins with {@link #DEFAULT_MESSAGE} exactly. A supplied response code
     * and reason code are appended under the {@code RESP:} and {@code REAS:} labels the legacy
     * diagnostic channel already uses, and a card ordinal greater than zero under {@code CARD:};
     * each part is omitted when absent, so with nothing available the result is
     * {@link #DEFAULT_MESSAGE} unchanged. Only caller-supplied codes and an ordinal ever reach the
     * message, so composition cannot introduce a credential, key, token, account identifier or
     * endpoint.
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
