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
 * Signals that a record update could not be applied: the record changed after it was read, the
 * record could not be locked for update, or the rewrite failed after the lock had been taken.
 *
 * <p>The three arms are deliberately not flattened. The legacy account-update program dispatches
 * them from one {@code EVALUATE} at {@code app/cbl/COACTUPC.cbl} L2606-L2615 and gives each its own
 * operator-facing text, so {@link ConflictKind} preserves the distinction: before-image mismatch -
 * the true optimistic-lock conflict, answered by re-displaying the record for review
 * (L2611-L2612); rewrite failed after locking (L2609-L2610); lock not acquired (L2607-L2608).
 *
 * <p>Rollback belongs to the caller. The estate's only rollback command sits on the customer-rewrite
 * failure arm at {@code app/cbl/COACTUPC.cbl} L4100, while the account-rewrite arm at L4076-L4081
 * sets the same state and does <em>not</em> roll back, because the account write is the first of the
 * two and there is as yet nothing to undo. That asymmetry is the account-update service's to
 * reproduce with a transactional rollback; this class declares no transaction, triggers no rollback
 * and references no Spring or Jakarta Persistence type.
 *
 * <p>Recoverable, never terminal: the legacy program re-displays the screen so the user can review
 * the current values and never abends, so this type extends {@link RuntimeException} directly and is
 * unrelated to the module's terminal abend type.
 *
 * <p>The four message texts are an external contract reproduced verbatim, including the lock text
 * that differs for the customer record. A constructor therefore refuses any message other than the
 * text its arm resolves to for its entity, so operator-facing wording cannot be composed at a call
 * site.
 */
public class OptimisticLockConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String MSG_DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    public static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    public static final String MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE =
            "Could not lock account record for update";

    public static final String MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE =
            "Could not lock customer record for update";

    private static final String CUSTOMER_ENTITY_NAME = "Customer";

    /** The three legacy write-path outcomes, each carrying the operator text the estate emits for it. */
    public enum ConflictKind {
        RECORD_CHANGED_BEFORE_UPDATE(MSG_DATA_WAS_CHANGED_BEFORE_UPDATE),

        UPDATE_FAILED_AFTER_LOCK(MSG_LOCKED_BUT_UPDATE_FAILED),

        LOCK_NOT_ACQUIRED(MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);

        private final String legacyMessage;

        ConflictKind(String legacyMessage) {
            this.legacyMessage = legacyMessage;
        }

        public String defaultMessage() {
            return this.legacyMessage;
        }

        /**
         * Resolves this arm's text for one entity: the lock-not-acquired arm names the customer record
         * when the customer is being locked, which is the only text that varies by entity.
         *
         * @param entityName the entity being written, matched case-insensitively; may be {@code null}
         * @return the legacy text for this arm and entity
         */
        public String defaultMessage(String entityName) {
            if (this == LOCK_NOT_ACQUIRED
                    && entityName != null
                    && CUSTOMER_ENTITY_NAME.equalsIgnoreCase(entityName.trim())) {
                return MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE;
            }
            return this.legacyMessage;
        }
    }

    private final ConflictKind conflictKind;

    private final String entityName;

    private final String key;

    public OptimisticLockConflictException(ConflictKind conflictKind, String entityName, String key) {
        this(conflictKind, entityName, key,
                requireConflictKind(conflictKind).defaultMessage(entityName), null);
    }

    public OptimisticLockConflictException(ConflictKind conflictKind, String entityName, String key,
            Throwable cause) {
        this(conflictKind, entityName, key,
                requireConflictKind(conflictKind).defaultMessage(entityName), cause);
    }

    public OptimisticLockConflictException(ConflictKind conflictKind, String entityName, String key,
            String message, Throwable cause) {
        super(requireLegacyMessage(requireConflictKind(conflictKind), entityName, message), cause);
        this.conflictKind = requireConflictKind(conflictKind);
        this.entityName = entityName == null ? "" : entityName;
        this.key = key == null ? "" : key;
    }

    private static ConflictKind requireConflictKind(ConflictKind conflictKind) {
        if (conflictKind == null) {
            throw new IllegalArgumentException(
                    "conflictKind is required; supply one of the three legacy write-path arms");
        }
        return conflictKind;
    }

    /**
     * Guards the message contract: an arm may only carry the text the legacy program emits for it,
     * so a caller cannot substitute wording an operator has never seen.
     */
    private static String requireLegacyMessage(ConflictKind conflictKind, String entityName,
            String message) {
        String resolved = conflictKind.defaultMessage(entityName);
        if (!resolved.equals(message)) {
            throw new IllegalArgumentException(
                    "message must be the legacy text this arm resolves to for this entity; pass "
                            + "conflictKind.defaultMessage(entityName), or use a constructor that "
                            + "resolves it, rather than composing operator-facing text");
        }
        return resolved;
    }

    public ConflictKind conflictKind() {
        return this.conflictKind;
    }

    public String entityName() {
        return this.entityName;
    }

    public String key() {
        return this.key;
    }
}
