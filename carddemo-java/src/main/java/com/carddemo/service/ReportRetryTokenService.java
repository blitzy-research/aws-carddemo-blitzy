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

import com.carddemo.exception.ValidationException;
import com.carddemo.util.ReportRetryTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints the token that identifies one logical report submission, and decides whether a presented one may
 * still be honoured as a retry of it.
 *
 * <h2>What this exists to stop</h2>
 *
 * <p>The report screen's request reaches the batch tier as a stream of fixed-width cards on a FIFO queue,
 * and the single mechanism that keeps a retry from doubling that stream is the queue service's
 * deduplication of repeated deduplication identifiers. Those identifiers are derived from the submission
 * identity, which is derived from the reporting dates, the authenticated operator and this token - so a
 * caller that repeats its token repeats the identifiers exactly, and the queue collapses the cards that had
 * already landed instead of appending them again.
 *
 * <p>The broker's memory is finite. It recognises an identifier for {@link ReportRetryTokens#VALIDITY} and
 * then forgets it, after which the very same identifiers are simply new to it. Without this boundary the
 * published contract said "repeat the token to complete an interrupted submission" with no horizon at all,
 * so a caller retrying an hour later was told its request had been submitted while the queue accepted the
 * whole stream a second time - or accepted again the prefix that had already been accepted. That is the
 * defect: not that duplication was possible, but that the interface promised it was not.
 *
 * <h2>What it does instead</h2>
 *
 * <p>A token is minted here, carries the instant it was minted, and is authenticated so the instant cannot
 * be moved. A presented token is honoured only when this deployment minted it and the mint instant lies
 * inside the window; otherwise the turn is refused before a single card is published, and the caller is told
 * to submit a new request rather than being handed a duplicate it believes was a retry. Inside the window
 * the promise is exactly as strong as it always claimed to be, and outside it there is no promise being
 * made.
 *
 * <h2>Why a refusal rather than a silent new submission</h2>
 *
 * <p>Treating an unrecognised or expired token as though none had been sent would publish a fresh
 * submission while the caller believed it had retried an earlier one - which is the original defect wearing
 * different clothes, and quieter. Refusing puts the decision back with the operator, who is the only party
 * that knows whether the earlier attempt's output is wanted twice. The legacy screen had no notion of
 * identity at all and would simply have run the report again; that behaviour remains available and is
 * reached the way it always was, by submitting without a token.
 *
 * <h2>Where the key comes from</h2>
 *
 * <p>Derived from the deployment's signing secret under a fixed purpose label, so a deployment gains no
 * further secret to distribute or rotate and the derived key can neither mint nor verify a credential. The
 * property is read as a property rather than through the configuration record that also binds it, because a
 * service that imported a configuration type would invert the layering the module enforces. A context that
 * configured no signing material at all gets a key that lives as long as the process, which is stated in
 * full on {@link ReportRetryTokens#deriveKey(String)}; production requires the secret with no fallback, so
 * that branch is not reachable there.
 *
 * <h2>Telemetry</h2>
 *
 * <p>A refusal is counted here, where the refusal happens, under
 * {@value #METRIC_RETRY_TOKEN_REFUSED} tagged by which of the two conditions was reached. A control whose
 * operation a deployment cannot observe is indistinguishable from an absent one, and the two conditions are
 * operationally different: expiry is a client retrying too late, while an unrecognised token is a client
 * sending something this deployment never issued. Neither the presented value nor any part of it is
 * recorded, in the metric or in the log - it is caller-supplied text, and a refused credential-shaped value
 * is the last thing that belongs in a log line.
 *
 * <p>Stateless apart from its key and its collaborators, all final, so the singleton is safe for
 * unsynchronised concurrent use.
 *
 * <p>See {@code docs/decision-log.md} entry DL-310, which records what was rejected - durable request
 * outcomes and consumer-side deduplication - and why limiting the promise to the mechanism was the honest
 * shape.
 *
 * @since 1.0.0
 */
@Service
public class ReportRetryTokenService {

    /** Counter published when a presented token is refused. */
    public static final String METRIC_RETRY_TOKEN_REFUSED =
            "carddemo.online.reportrequest.retrytoken.refused";

    /** Tag naming which condition refused the token. */
    public static final String TAG_REASON = "reason";

    /** Refusal reason: the token verified but its window has closed. */
    public static final String REASON_EXPIRED = "expired";

    /** Refusal reason: this deployment did not mint the presented value. */
    public static final String REASON_NOT_ISSUED_HERE = "not-issued-here";

    /**
     * The one message a refused caller sees.
     *
     * <p>One message for both conditions. Telling a caller which of the two it reached would say whether
     * the value it sent was ever a token of this deployment, which is of no use to a client following the
     * contract and of some use to one probing it; the distinction that matters operationally is recorded in
     * the metric and the log instead. The message names the header and the remedy, because a caller that
     * cannot act on a refusal will simply retry it.
     */
    public static final String REFUSAL_MESSAGE = "The Idempotency-Key presented was not issued by this"
            + " service or is older than the " + ReportRetryTokens.VALIDITY_MINUTES + " minute retry"
            + " window; omit the header to submit a new request";

    /** Diagnostics for this boundary. */
    private static final Logger LOG = LoggerFactory.getLogger(ReportRetryTokenService.class);

    /** Property carrying the deployment's signing material, from which the token key is derived. */
    private static final String SIGNING_SECRET_PROPERTY = "carddemo.security.jwt.secret";

    /** Derived key material. Private to this instance and never exposed. */
    private final byte[] tokenKey;

    /** The module's clock, so mint and expiry read one time source. */
    private final Clock clock;

    /** Where the refusal counter is published. */
    private final MeterRegistry meterRegistry;

    /**
     * Derives the token key and binds the collaborators.
     *
     * @param signingSecret the deployment's signing material, bound from
     *                      {@code carddemo.security.jwt.secret}; a blank or absent value yields a
     *                      process-scoped key, which is the documented degradation for a context that
     *                      configured no signing material
     * @param clock         the module's clock
     * @param meterRegistry where the refusal counter is published
     * @throws NullPointerException if the clock or the registry is {@code null}
     */
    public ReportRetryTokenService(
            @Value("${" + SIGNING_SECRET_PROPERTY + ":}") final String signingSecret,
            final Clock clock,
            final MeterRegistry meterRegistry) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.tokenKey = ReportRetryTokens.deriveKey(signingSecret);
        if (signingSecret == null || signingSecret.isBlank()) {
            LOG.warn("No {} is configured, so report retry tokens are authenticated with a key that lives"
                    + " only as long as this process; a token minted here will not be honoured by another"
                    + " instance or after a restart", SIGNING_SECRET_PROPERTY);
        }
    }

    /**
     * Mints a token for a submission beginning now.
     *
     * @return a token this deployment will recognise for {@link ReportRetryTokens#VALIDITY}
     */
    public String mint() {
        return ReportRetryTokens.mint(this.clock.instant(), this.tokenKey);
    }

    /**
     * Honours a presented token, or refuses the turn.
     *
     * <p>Called before anything is published, so a refusal costs nothing and leaves nothing half-done.
     *
     * @param  presented the token the caller presented; must be neither {@code null} nor blank, because an
     *                   absent token is a new submission and is minted rather than checked
     * @return the presented token, when it may still be honoured as a retry
     * @throws ValidationException when this deployment did not mint the value, or when the window has
     *                             closed. The message names the header and the remedy and never the value
     */
    public String accept(final String presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        final Optional<Instant> issuedAt = ReportRetryTokens.issuedAt(presented, this.tokenKey);
        if (issuedAt.isEmpty()) {
            return refuse(REASON_NOT_ISSUED_HERE);
        }
        if (!ReportRetryTokens.isWithinValidity(issuedAt.get(), this.clock.instant())) {
            return refuse(REASON_EXPIRED);
        }
        return presented;
    }

    /**
     * Counts the refusal, records it, and raises it.
     *
     * @param  reason which condition was reached
     * @return never returns
     * @throws ValidationException always
     */
    private String refuse(final String reason) {
        Counter.builder(METRIC_RETRY_TOKEN_REFUSED)
                .description("Report-request retry tokens refused, by the condition that refused them")
                .tag(TAG_REASON, reason)
                .register(this.meterRegistry)
                .increment();
        LOG.warn("Report request refused before publication: reason=retry-token-{} window={}m",
                reason, ReportRetryTokens.VALIDITY_MINUTES);
        throw new ValidationException(REFUSAL_MESSAGE);
    }
}
