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
import java.util.Objects;

/**
 * Persistent delivery-state boundary for ordered job-submission card streams.
 *
 * <p>A FIFO deduplication identifier makes one repeated card idempotent; it does not make a stream of
 * cards atomic. If one submission publishes only its first cards, another submission publishes in
 * full, and the first is then retried, the accepted remainder would otherwise land after the
 * intervening stream. An implementation of this boundary persists the logical submission and the next
 * card to publish before allowing another logical submission to pass it.
 *
 * <p>The production implementation participates in the transaction opened by
 * {@link JobSubmissionCoordinator}. The direct implementation returned by {@link #direct()} exists
 * only for focused tests that do not boot infrastructure; it preserves the same iteration contract
 * without claiming persistence.
 */
public interface JobSubmissionOutbox {

    /**
     * Persists one logical submission and drains pending submissions in their original order until
     * this submission completes or one card publish is refused.
     *
     * @param submissionId   identity of the logical submission
     * @param cards          complete validated card list supplied for the submission
     * @param terminalOrdinal one-based ordinal of the last card the legacy loop transmits
     * @param publisher      boundary that publishes one card and reports whether it was accepted
     * @return delivery state for the requested submission
     */
    DeliveryOutcome publish(
            String submissionId,
            List<String> cards,
            int terminalOrdinal,
            CardPublisher publisher);

    /**
     * Returns a non-persistent implementation for isolated unit tests.
     *
     * <p>Production construction never uses this implementation. It deliberately performs no retry
     * bookkeeping across calls; tests of persistence use the production implementation against a real
     * PostgreSQL server.
     *
     * @return a direct ordered publisher
     */
    static JobSubmissionOutbox direct() {
        return (submissionId, cards, terminalOrdinal, publisher) -> {
            Objects.requireNonNull(submissionId, "submissionId must not be null");
            final List<String> stream =
                    List.copyOf(Objects.requireNonNull(cards, "cards must not be null"));
            final CardPublisher cardPublisher =
                    Objects.requireNonNull(publisher, "publisher must not be null");
            requireTerminalOrdinal(stream, terminalOrdinal);

            int cardsPublished = 0;
            for (int cardOrdinal = 1; cardOrdinal <= terminalOrdinal; cardOrdinal++) {
                if (!cardPublisher.publish(
                        submissionId, stream.get(cardOrdinal - 1), cardOrdinal)) {
                    return new DeliveryOutcome(cardsPublished, true);
                }
                cardsPublished++;
            }
            return new DeliveryOutcome(cardsPublished, false);
        };
    }

    /**
     * Publishes one already-validated card.
     */
    @FunctionalInterface
    interface CardPublisher {

        /**
         * @param submissionId identity of the card's logical submission
         * @param cardImage    exact eighty-character card image
         * @param cardOrdinal  one-based position in the submission
         * @return {@code true} when the queue accepted the card
         */
        boolean publish(String submissionId, String cardImage, int cardOrdinal);
    }

    /**
     * Delivery state of the requested logical submission.
     *
     * @param cardsPublished cards of this submission known to have reached the queue
     * @param failed         whether draining stopped at a refused card
     */
    record DeliveryOutcome(int cardsPublished, boolean failed) {

        /**
         * Validates the non-negative count carried across the boundary.
         */
        public DeliveryOutcome {
            if (cardsPublished < 0) {
                throw new IllegalArgumentException(
                        "cardsPublished must not be negative but was " + cardsPublished);
            }
        }
    }

    private static void requireTerminalOrdinal(
            final List<String> cards, final int terminalOrdinal) {
        if (terminalOrdinal < 1 || terminalOrdinal > cards.size()) {
            throw new IllegalArgumentException("terminalOrdinal must be between 1 and the "
                    + cards.size() + " supplied cards, but was " + terminalOrdinal);
        }
    }
}
