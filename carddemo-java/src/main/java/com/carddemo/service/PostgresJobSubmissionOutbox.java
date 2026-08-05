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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL-backed delivery state for the ordered SQS job-submission bridge.
 *
 * <p>The coordinator opens a deployment-wide transaction and advisory lock before invoking this
 * component. Inside that boundary a new logical submission is inserted before its first card is sent,
 * and the next unsent ordinal is advanced after every accepted send. An incomplete older submission is
 * always drained before a newer one. A later request therefore cannot land between the first and last
 * cards of a retried stream.
 *
 * <p>Completed rows are retained. Reusing an identity with the same card stream is a true retry and
 * returns the persisted completed state without sending another card; reusing it for different work is
 * rejected. The full caller-supplied stream is represented by a SHA-256 fingerprint, while only the
 * cards the legacy loop can actually transmit are stored as their exact eighty-byte images.
 */
@Component
public final class PostgresJobSubmissionOutbox implements JobSubmissionOutbox {

    private static final int CARD_WIDTH = 80;

    private static final int FINGERPRINT_WIDTH = 32;

    private static final int FIRST_CARD_ORDINAL = 1;

    private static final String FIND_BY_ID = """
            SELECT submission_sequence, submission_id, card_count, terminal_ordinal,
                   next_card_ordinal, card_image, stream_fingerprint
              FROM job_submission_outbox
             WHERE submission_id = ?
             FOR UPDATE
            """;

    private static final String FIND_OLDEST_PENDING = """
            SELECT submission_sequence, submission_id, card_count, terminal_ordinal,
                   next_card_ordinal, card_image, stream_fingerprint
              FROM job_submission_outbox
             WHERE next_card_ordinal <= terminal_ordinal
             ORDER BY submission_sequence
             LIMIT 1
             FOR UPDATE
            """;

    private static final String INSERT_SUBMISSION = """
            INSERT INTO job_submission_outbox
                   (submission_id, card_count, terminal_ordinal, next_card_ordinal,
                    card_image, stream_fingerprint)
            VALUES (?, ?, ?, 1, ?, ?)
            """;

    private static final String ADVANCE_SUBMISSION = """
            UPDATE job_submission_outbox
               SET next_card_ordinal = ?,
                   completed = (? > terminal_ordinal)
             WHERE submission_sequence = ?
               AND next_card_ordinal = ?
            """;

    private static final RowMapper<PersistedSubmission> SUBMISSION_MAPPER = (result, rowNumber) ->
            persisted(
                    result.getLong("submission_sequence"),
                    result.getString("submission_id"),
                    result.getInt("card_count"),
                    result.getInt("terminal_ordinal"),
                    result.getInt("next_card_ordinal"),
                    result.getBytes("card_image"),
                    result.getBytes("stream_fingerprint"));

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the outbox over the same data source the coordinator's transaction manager controls.
     *
     * @param jdbcTemplate parameterized access to the shared PostgreSQL database
     */
    public PostgresJobSubmissionOutbox(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public DeliveryOutcome publish(final String submissionId, final List<String> cards,
            final int terminalOrdinal, final CardPublisher publisher) {
        requireTransaction();
        final String identity = Objects.requireNonNull(
                submissionId, "submissionId must not be null");
        final List<String> stream =
                List.copyOf(Objects.requireNonNull(cards, "cards must not be null"));
        final CardPublisher cardPublisher =
                Objects.requireNonNull(publisher, "publisher must not be null");
        requireTerminalOrdinal(stream, terminalOrdinal);

        final byte[] publishableImage = publishableImage(stream, terminalOrdinal);
        final byte[] fingerprint = fingerprint(stream);
        PersistedSubmission requested = findById(identity)
                .map(existing -> existing.verifiedAgainst(
                        stream.size(), terminalOrdinal, publishableImage, fingerprint))
                .orElseGet(() -> insert(identity, stream.size(), terminalOrdinal,
                        publishableImage, fingerprint));

        if (requested.complete()) {
            return new DeliveryOutcome(requested.cardsPublished(), false);
        }

        while (true) {
            final PersistedSubmission pending = findOldestPending()
                    .orElseThrow(() -> new DataIntegrityViolationException(
                            "the requested job submission is incomplete but the outbox has no "
                                    + "pending row"));
            final int cardOrdinal = pending.nextCardOrdinal();
            if (!cardPublisher.publish(
                    pending.submissionId(), pending.cardAt(cardOrdinal), cardOrdinal)) {
                requested = requireById(identity);
                return new DeliveryOutcome(requested.cardsPublished(), true);
            }
            advance(pending);

            requested = requireById(identity);
            if (requested.complete()) {
                return new DeliveryOutcome(requested.cardsPublished(), false);
            }
        }
    }

    private PersistedSubmission insert(final String submissionId, final int cardCount,
            final int terminalOrdinal, final byte[] cardImage, final byte[] fingerprint) {
        final int inserted = this.jdbcTemplate.update(INSERT_SUBMISSION,
                submissionId, Integer.valueOf(cardCount), Integer.valueOf(terminalOrdinal),
                cardImage, fingerprint);
        if (inserted != 1) {
            throw new ConcurrencyFailureException(
                    "the job-submission outbox did not insert exactly one logical submission");
        }
        return requireById(submissionId);
    }

    private void advance(final PersistedSubmission submission) {
        final int nextOrdinal = submission.nextCardOrdinal() + 1;
        final int updated = this.jdbcTemplate.update(ADVANCE_SUBMISSION,
                Integer.valueOf(nextOrdinal), Integer.valueOf(nextOrdinal),
                Long.valueOf(submission.sequence()),
                Integer.valueOf(submission.nextCardOrdinal()));
        if (updated != 1) {
            throw new ConcurrencyFailureException(
                    "the job-submission outbox delivery position changed unexpectedly");
        }
    }

    private Optional<PersistedSubmission> findById(final String submissionId) {
        return this.jdbcTemplate.query(FIND_BY_ID, SUBMISSION_MAPPER, submissionId)
                .stream()
                .findFirst();
    }

    private PersistedSubmission requireById(final String submissionId) {
        return findById(submissionId)
                .orElseThrow(() -> new DataIntegrityViolationException(
                        "the job-submission outbox lost a staged logical submission"));
    }

    private Optional<PersistedSubmission> findOldestPending() {
        return this.jdbcTemplate.query(FIND_OLDEST_PENDING, SUBMISSION_MAPPER)
                .stream()
                .findFirst();
    }

    private static PersistedSubmission persisted(final long sequence, final String submissionId,
            final int cardCount, final int terminalOrdinal, final int nextCardOrdinal,
            final byte[] cardImage, final byte[] fingerprint) {
        if (submissionId == null || submissionId.isEmpty()) {
            throw new DataIntegrityViolationException(
                    "the job-submission outbox contains an empty identity");
        }
        if (cardCount < 1 || terminalOrdinal < FIRST_CARD_ORDINAL
                || terminalOrdinal > cardCount) {
            throw new DataIntegrityViolationException(
                    "the job-submission outbox contains invalid card counts");
        }
        if (nextCardOrdinal < FIRST_CARD_ORDINAL
                || nextCardOrdinal > terminalOrdinal + 1) {
            throw new DataIntegrityViolationException(
                    "the job-submission outbox contains an invalid delivery position");
        }
        if (cardImage == null || cardImage.length != terminalOrdinal * CARD_WIDTH) {
            throw new DataIntegrityViolationException(
                    "the job-submission outbox contains a wrongly framed card image");
        }
        if (fingerprint == null || fingerprint.length != FINGERPRINT_WIDTH) {
            throw new DataIntegrityViolationException(
                    "the job-submission outbox contains an invalid stream fingerprint");
        }
        return new PersistedSubmission(sequence, submissionId, cardCount, terminalOrdinal,
                nextCardOrdinal, cardImage, fingerprint);
    }

    private static byte[] publishableImage(final List<String> cards, final int terminalOrdinal) {
        final byte[] image = new byte[terminalOrdinal * CARD_WIDTH];
        for (int cardOrdinal = FIRST_CARD_ORDINAL;
                cardOrdinal <= terminalOrdinal;
                cardOrdinal++) {
            final byte[] card = cards.get(cardOrdinal - FIRST_CARD_ORDINAL)
                    .getBytes(StandardCharsets.US_ASCII);
            if (card.length != CARD_WIDTH) {
                throw new IllegalArgumentException("job-submission card " + cardOrdinal
                        + " must be exactly " + CARD_WIDTH + " bytes");
            }
            System.arraycopy(card, 0, image,
                    (cardOrdinal - FIRST_CARD_ORDINAL) * CARD_WIDTH, CARD_WIDTH);
        }
        return image;
    }

    private static byte[] fingerprint(final List<String> cards) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("the required SHA-256 digest is unavailable", unavailable);
        }
        for (final String card : cards) {
            digest.update(card.getBytes(StandardCharsets.US_ASCII));
        }
        return digest.digest();
    }

    private static void requireTerminalOrdinal(
            final List<String> cards, final int terminalOrdinal) {
        if (terminalOrdinal < FIRST_CARD_ORDINAL || terminalOrdinal > cards.size()) {
            throw new IllegalArgumentException("terminalOrdinal must be between 1 and the "
                    + cards.size() + " supplied card(s), but was " + terminalOrdinal);
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the PostgreSQL job-submission outbox must run inside "
                    + "the coordinator's transaction-scoped advisory lock");
        }
    }

    private record PersistedSubmission(
            long sequence,
            String submissionId,
            int cardCount,
            int terminalOrdinal,
            int nextCardOrdinal,
            byte[] cardImage,
            byte[] fingerprint) {

        private PersistedSubmission {
            cardImage = cardImage.clone();
            fingerprint = fingerprint.clone();
        }

        private PersistedSubmission verifiedAgainst(final int suppliedCardCount,
                final int suppliedTerminalOrdinal, final byte[] suppliedCardImage,
                final byte[] suppliedFingerprint) {
            if (this.cardCount != suppliedCardCount
                    || this.terminalOrdinal != suppliedTerminalOrdinal
                    || !Arrays.equals(this.cardImage, suppliedCardImage)
                    || !MessageDigest.isEqual(this.fingerprint, suppliedFingerprint)) {
                throw new IllegalArgumentException("submissionId " + this.submissionId
                        + " already names a different job-submission card stream; an identity may"
                        + " be reused only for a true retry of the same logical submission");
            }
            return this;
        }

        private String cardAt(final int cardOrdinal) {
            final int offset = (cardOrdinal - FIRST_CARD_ORDINAL) * CARD_WIDTH;
            return new String(this.cardImage, offset, CARD_WIDTH, StandardCharsets.US_ASCII);
        }

        private int cardsPublished() {
            return Math.min(this.nextCardOrdinal - FIRST_CARD_ORDINAL, this.terminalOrdinal);
        }

        private boolean complete() {
            return this.nextCardOrdinal > this.terminalOrdinal;
        }

        @Override
        public byte[] cardImage() {
            return this.cardImage.clone();
        }

        @Override
        public byte[] fingerprint() {
            return fingerprint.clone();
        }
    }
}
