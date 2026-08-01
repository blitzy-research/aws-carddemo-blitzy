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
 * Signals that a record update could not be applied because the record changed after it was read,
 * because it could not be locked for update, or because the rewrite failed after the lock had been
 * taken.
 *
 * <p>The Java replacement for the concurrency-safety mechanism of the account-update transaction
 * {@code CAUP}, implemented by {@code app/cbl/COACTUPC.cbl}. It is a leaf type: no framework
 * annotation, no I/O, no logging, and no dependency but {@code java.lang}, so it declares zero
 * imports in keeping with the rule that {@code util} and {@code exception} depend on nothing above
 * them.
 *
 * <p><strong>The replacement is stronger than the legacy baseline, and that is intentional.</strong>
 * Every application file in {@code app/csd/CARDDEMO.CSD} is defined with
 * {@code READINTEG(UNCOMMITTED)}, {@code UPDATEMODEL(LOCKING)}, {@code JOURNAL(NO)} and
 * {@code RECOVERY(NONE)}, so correctness rested entirely on the locking update model plus each
 * program's own before-and-after image comparison; the data store offered no isolation guarantee
 * and no rollback log. PostgreSQL READ COMMITTED combined with a JPA {@code @Version} column on the
 * account and card entities is a strict isolation improvement over that, not a behavioural
 * regression: the legacy design detected interference only in the fields it happened to compare,
 * while the relational design detects any concurrent write to the row. Recorded as decision log
 * entry D-15. The {@code @Version} annotation is applied on the entities in the {@code domain}
 * package; it is named here in prose only, and this class applies no persistence annotation.
 *
 * <p><strong>The comparison this replaces.</strong> Paragraph {@code 9700-CHECK-CHANGE-IN-REC} of
 * {@code app/cbl/COACTUPC.cbl} (L4109-L4192, exit label L4193) re-reads the record under lock and
 * compares it against the snapshot taken when the screen was first presented. It is one long
 * conjunction - every field must match - split into an account block whose mismatch exit is at
 * L4143 and a customer block whose mismatch exit is at L4189; on either mismatch the program sets
 * the "data was changed before update" state and jumps to the write-processing exit at L4105. The
 * compared fields are the account active status, current balance, credit limit, cash credit limit,
 * current-cycle credit, current-cycle debit and account group id - the group id compared
 * case-insensitively (L4139-L4140) - plus the customer primary-card-holder indicator
 * (L4183-L4185) and the FICO credit score (L4186). The comparison itself belongs to the
 * account-update service, which is not delivered yet; this type only reports its outcome.
 *
 * <p><strong>Three arms, deliberately not flattened.</strong> The legacy write path renders three
 * different outcomes, dispatched by the {@code EVALUATE} at {@code app/cbl/COACTUPC.cbl}
 * L2606-L2615, each with its own operator-facing text, and {@link ConflictKind} preserves that
 * distinction: the before image no longer matches - the true optimistic-lock conflict, which the
 * legacy program answers by re-displaying the details for review (L2611-L2612); the rewrite failed
 * after the record had been locked (L2609-L2610); and the lock could not be acquired at all
 * (L2607-L2608).
 *
 * <p><strong>Rollback belongs to the caller.</strong> {@code EXEC CICS SYNCPOINT ROLLBACK} occurs
 * exactly once in the estate, at {@code app/cbl/COACTUPC.cbl} L4100, on the customer-rewrite
 * failure arm. The account-rewrite arm at L4076-L4081 sets the same state but does <em>not</em> roll
 * back, because the account write is the first of the two and there is as yet nothing to undo. That
 * asymmetry is a fidelity detail for the account-update service to reproduce and is not modelled
 * here. The Java equivalent of L4100 is a {@code @Transactional} rollback in the calling service,
 * which raises this exception; this class neither declares {@code @Transactional} nor triggers a
 * rollback, and holds no reference to any Spring or Jakarta Persistence type.
 *
 * <p><strong>Recoverable, never terminal.</strong> On a before-image mismatch the legacy program
 * sets a "show details" state and re-displays the screen so the user can review the current values.
 * It does not abend and does not call the abend routine, so the caller is expected to re-present the
 * current state of the record rather than terminate. Accordingly this type extends
 * {@link RuntimeException} directly, is deliberately unrelated to the module's terminal abend type,
 * and publishes no accessor that would suggest a terminal outcome.
 *
 * <p>Paragraph {@code 9700-CHECK-CHANGE-IN-REC} of {@code app/cbl/COACTUPC.cbl} (L4109-L4192,
 * exit label at L4193) re-reads the record under lock and compares it against the snapshot taken
 * when the screen was first presented. It is a single long conjunction - every field must match -
 * split into an account block whose mismatch exit is at <strong>L4143</strong> and a customer
 * block whose mismatch exit is at <strong>L4189</strong>. On either mismatch the program sets the
 * "data was changed before update" state and jumps to the write-processing exit at L4105.</p>
 *
 * <p>The compared fields include the account active status, current balance, credit limit, cash
 * credit limit, current-cycle credit, current-cycle debit and account group id - the group id
 * compared case-insensitively (L4139-L4140) - plus the customer primary-card-holder indicator
 * (L4183-L4185) and the FICO credit score (L4186). The comparison itself is not reproduced here:
 * it belongs to {@code AccountUpdateService}. This type only reports its outcome.</p>
 *
 * <h2>Three distinct arms, deliberately not flattened</h2>
 *
 * <p>The legacy write path renders three different outcomes, dispatched by the
 * {@code EVALUATE} at {@code app/cbl/COACTUPC.cbl} L2606-L2615, and each has its own
 * operator-facing text. {@code ConflictKind} preserves that distinction:</p>
 *
 * <ul>
 *   <li>the before-image no longer matches - the true optimistic-lock conflict - which the
 *       legacy program answers by re-displaying the details for review (L2611-L2612);</li>
 *   <li>the rewrite failed after the record had been locked (L2609-L2610);</li>
 *   <li>the lock could not be acquired at all (L2607-L2608).</li>
 * </ul>
 *
 * <h2>Transaction rollback belongs to the caller</h2>
 *
 * <p>An explicit transaction-manager rollback request occurs exactly once in the entire legacy
 * estate, at {@code app/cbl/COACTUPC.cbl} <strong>L4100</strong>, on the customer-rewrite failure arm: when
 * the rewrite response is not normal the program sets the "locked but update failed" state, rolls
 * back, and jumps to the write-processing exit. The account-rewrite arm at L4076-L4081 sets the
 * same state but does <em>not</em> roll back, because the account write is the first of the two
 * and there is as yet nothing to undo. That asymmetry is a fidelity detail for
 * {@code AccountUpdateService} to reproduce; it is not modelled here.</p>
 *
 * <p>The Java equivalent of L4100 is a {@code @Transactional} rollback in the calling service,
 * which raises this exception. This class neither declares {@code @Transactional} nor triggers a
 * rollback itself, and it holds no reference to any Spring or Jakarta Persistence type.</p>
 *
 * <h2>Recoverable and non-abending</h2>
 *
 * <p>On a before-image mismatch the legacy program sets a "show details" state and re-displays
 * the screen so the user can review the current values. It does not abend, does not escalate to a
 * terminal failure, and does not call the abend routine. <strong>This is therefore a recoverable
 * conflict:</strong> the caller is expected to re-present the current state of the record for
 * review, not to terminate. Accordingly this type extends {@code RuntimeException} directly, is
 * deliberately unrelated to the module's terminal abend type, and publishes no accessor that would
 * suggest a terminal outcome.</p>
 *
 * <h2>Message text is an external contract</h2>
 *
 * <p>The four literals published as constants below are the exact strings an operator sees. In
 * {@code app/cbl/COACTUPC.cbl} L517-L528 the level-88 condition names carry the message text as
 * their {@code VALUE}, so the condition name and the operator text are one and the same. They are
 * reproduced character for character, legacy spelling included, and the detail message of an
 * instance is exactly one of them - never decorated with the entity name or the key, which travel
 * separately through {@link #entityName()} and {@link #key()}.</p>
 *
 * <p>One related condition name in the same block, at L525-L526, carries the text
 * {@code Error reading Card Data File}. That text belongs to the card cross-reference read path
 * rather than to the write-conflict path, so it is deliberately <em>not</em> declared as a
 * constant here.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Every citation above was verified against the legacy checkout at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed
 * into this module; the references are citations only.</p>
 */
public class OptimisticLockConflictException extends RuntimeException {

    /**
     * Explicit, stable serialization identity. {@code Throwable} is {@code Serializable}, and the
     * module compiles with {@code -Xlint:all -Werror}, which promotes the missing
     * {@code serialVersionUID} warning to a build error.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Operator-facing text for a true optimistic-lock conflict: the record changed between the
     * read that populated the screen and the attempt to write it back.
     *
     * <p>Reproduced verbatim from the {@code DATA-WAS-CHANGED-BEFORE-UPDATE} condition name at
     * {@code app/cbl/COACTUPC.cbl} L521-L522. Two details are deliberate and must not be
     * "corrected": <strong>"some one" is two words in the legacy source</strong>, and the literal
     * ends at {@code review} with <strong>no trailing full stop</strong> - the period visible in
     * the COBOL listing is the statement terminator, outside the quoted value.</p>
     */
    public static final String MSG_DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /**
     * Operator-facing text for a rewrite that failed after the record had already been locked.
     *
     * <p>Reproduced verbatim from the {@code LOCKED-BUT-UPDATE-FAILED} condition name at
     * {@code app/cbl/COACTUPC.cbl} L523-L524. It is set on both rewrite arms: the account rewrite
     * at L4079 and the customer rewrite at L4098.</p>
     */
    public static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    /**
     * Operator-facing text for a failure to lock the <em>account</em> record for update.
     *
     * <p>Reproduced verbatim from the {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} condition name at
     * {@code app/cbl/COACTUPC.cbl} L517-L518. The legacy program sets it at L3912 when the
     * account read-for-update does not return a normal response.</p>
     */
    public static final String MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE =
            "Could not lock account record for update";

    /**
     * Operator-facing text for a failure to lock the <em>customer</em> record for update.
     *
     * <p>Reproduced verbatim from the {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} condition name at
     * {@code app/cbl/COACTUPC.cbl} L519-L520. The legacy program sets it at L3939 when the
     * customer read-for-update does not return a normal response. The account and customer
     * variants are two distinct operator texts for one conflict arm, which is why
     * {@link ConflictKind#defaultMessage(String)} selects between them.</p>
     */
    public static final String MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE =
            "Could not lock customer record for update";

    /**
     * The entity name that identifies the customer record, used only to choose between the two
     * legacy lock-failure texts. Compared case-insensitively and after trimming, so a caller that
     * supplies {@code "customer"} or {@code " Customer "} still gets the customer text.
     *
     * <p>The comparison is over this Java-side descriptive label only. It never touches COBOL record
     * data, so the estate's ASCII-only case-folding rule for record fields does not apply to it.</p>
     */
    private static final String CUSTOMER_ENTITY_NAME = "Customer";

    /**
     * The three outcomes the legacy write path can render, as dispatched by the {@code EVALUATE} at
     * {@code app/cbl/COACTUPC.cbl} L2606-L2615.
     *
     * <p>There are exactly three constants because the legacy program renders exactly three distinct
     * states, each with its own operator text. They are kept distinct rather than flattened into one
     * generic "conflict" so that the caller can reproduce the legacy response for each - re-display
     * for review, report a failed update, or report a lock error - and so that the message text
     * remains verifiable character for character.</p>
     *
     * <p>Declaration order carries no behavioural meaning: the legacy states are three level-88
     * condition names over one shared field, so exactly one can ever be set. This is a set of
     * states, not an ordered cascade, and the top-down clause order of the legacy {@code EVALUATE}
     * - lock error, then failed update, then changed data - is a dispatch concern for whichever
     * component decides what to show the operator, not something encoded here.</p>
     */
    public enum ConflictKind {

        /**
         * The before-image no longer matches the record as freshly read under lock: a true
         * optimistic-lock conflict, detected by {@code 9700-CHECK-CHANGE-IN-REC}
         * ({@code app/cbl/COACTUPC.cbl} L4109-L4192) at the account mismatch exit L4143 or the
         * customer mismatch exit L4189.
         *
         * <p>Legacy state {@code DATA-WAS-CHANGED-BEFORE-UPDATE}; dispatched at L2611-L2612 to the
         * "show details" state, which re-displays the screen for the user to review. This arm is
         * recoverable: the caller re-presents the current values rather than terminating. Default
         * message {@link OptimisticLockConflictException#MSG_DATA_WAS_CHANGED_BEFORE_UPDATE}.</p>
         */
        RECORD_CHANGED_BEFORE_UPDATE(MSG_DATA_WAS_CHANGED_BEFORE_UPDATE),

        /**
         * The record was locked successfully but the rewrite itself failed.
         *
         * <p>Legacy state {@code LOCKED-BUT-UPDATE-FAILED}, set on the account-rewrite failure arm
         * at {@code app/cbl/COACTUPC.cbl} L4076-L4081 and on the customer-rewrite failure arm at
         * L4095-L4103; dispatched at L2609-L2610. Only the customer arm rolls back, at L4100 - the
         * sole rollback in the estate - because the account write is the first of the two. Default
         * message {@link OptimisticLockConflictException#MSG_LOCKED_BUT_UPDATE_FAILED}.</p>
         */
        UPDATE_FAILED_AFTER_LOCK(MSG_LOCKED_BUT_UPDATE_FAILED),

        /**
         * The record could not be locked for update at all, so no comparison and no rewrite were
         * attempted.
         *
         * <p>Legacy states {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}, set at
         * {@code app/cbl/COACTUPC.cbl} L3912 when the account read-for-update fails, and
         * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}, set at L3939 when the customer read-for-update
         * fails; dispatched at L2607-L2608. This single arm therefore owns two operator texts. Its
         * default message is
         * {@link OptimisticLockConflictException#MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE}, matching the
         * legacy order in which the two locks are taken - the account read-for-update precedes the
         * customer read-for-update - and {@link #defaultMessage(String)} selects
         * {@link OptimisticLockConflictException#MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE} when the
         * customer record is the one that could not be locked.</p>
         */
        LOCK_NOT_ACQUIRED(MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);

        /** The verbatim legacy operator text for this arm. Never {@code null}. */
        private final String legacyMessage;

        /**
         * Binds an arm to its verbatim legacy operator text.
         *
         * @param legacyMessage the exact text reproduced from the level-88 condition name at
         *                      {@code app/cbl/COACTUPC.cbl} L517-L528
         */
        ConflictKind(String legacyMessage) {
            this.legacyMessage = legacyMessage;
        }

        /**
         * Returns the verbatim legacy operator text for this arm.
         *
         * <p>For {@link #LOCK_NOT_ACQUIRED} this is the account variant; use
         * {@link #defaultMessage(String)} when the failing record may be the customer record.</p>
         *
         * @return the exact operator text, never {@code null} and never decorated
         */
        public String defaultMessage() {
            return this.legacyMessage;
        }

        /**
         * Returns the verbatim legacy operator text for this arm, resolved for the record that
         * failed.
         *
         * <p>The legacy program emits two different texts for a lock failure depending on which
         * file was being read for update - the account text at {@code app/cbl/COACTUPC.cbl} L3912
         * and the customer text at L3939. This overload reproduces that distinction rather than
         * collapsing the two, which would silently lose one of the four external-contract strings.
         * For the other two arms the entity name is irrelevant and the single legacy text is
         * returned unchanged.</p>
         *
         * @param entityName the descriptive name of the record that failed, such as
         *                   {@code "Account"}, {@code "Card"} or {@code "Customer"}; may be
         *                   {@code null} or blank, in which case {@link #defaultMessage()} applies
         * @return the exact operator text, never {@code null} and never decorated
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

    /** Which of the three legacy write-path arms produced this conflict. Never {@code null}. */
    private final ConflictKind conflictKind;

    /**
     * Descriptive name of the record involved, such as {@code "Account"}, {@code "Card"} or
     * {@code "Customer"}. Held as a {@code String} rather than a {@code Class} so that no
     * reflective inspection is possible: the module's audited budget for reflection is zero.
     * Never {@code null}; an absent value is normalised to the empty string.
     */
    private final String entityName;

    /**
     * The business key of the record involved, rendered as a string. The module uses no surrogate
     * primary keys, so this is the same natural key that identifies the row. Never {@code null};
     * an absent value is normalised to the empty string.
     */
    private final String key;

    /**
     * Creates a conflict whose detail message is the verbatim legacy text for the given arm,
     * resolved for the named record.
     *
     * @param conflictKind which legacy write-path arm produced the conflict; required
     * @param entityName   descriptive name of the record involved, such as {@code "Account"},
     *                     {@code "Card"} or {@code "Customer"}; {@code null} becomes the empty
     *                     string
     * @param key          the business key of the record involved; {@code null} becomes the empty
     *                     string
     * @throws IllegalArgumentException if {@code conflictKind} is {@code null}
     */
    public OptimisticLockConflictException(ConflictKind conflictKind, String entityName, String key) {
        this(conflictKind, entityName, key,
                requireConflictKind(conflictKind).defaultMessage(entityName), null);
    }

    /**
     * Creates a conflict whose detail message is the verbatim legacy text for the given arm,
     * resolved for the named record, wrapping the underlying cause.
     *
     * <p>The cause is the natural place for the persistence provider's own optimistic-lock
     * failure. It is always passed through and never swallowed, so the original stack trace
     * remains available for diagnosis.</p>
     *
     * @param conflictKind which legacy write-path arm produced the conflict; required
     * @param entityName   descriptive name of the record involved; {@code null} becomes the empty
     *                     string
     * @param key          the business key of the record involved; {@code null} becomes the empty
     *                     string
     * @param cause        the underlying failure, typically the persistence provider's
     *                     optimistic-lock exception; may be {@code null}
     * @throws IllegalArgumentException if {@code conflictKind} is {@code null}
     */
    public OptimisticLockConflictException(ConflictKind conflictKind, String entityName, String key,
            Throwable cause) {
        this(conflictKind, entityName, key,
                requireConflictKind(conflictKind).defaultMessage(entityName), cause);
    }

    /**
     * Canonical constructor. Both other constructors funnel through this one after resolving the
     * arm's legacy default message.
     *
     * <p>The detail message is an external contract: callers must supply one of the four legacy
     * literals published by this class rather than composing new operator-facing text. The message
     * is stored undecorated - the entity name and key are exposed separately by
     * {@link #entityName()} and {@link #key()} so that message-text comparison stays exact.</p>
     *
     * @param conflictKind which legacy write-path arm produced the conflict; required
     * @param entityName   descriptive name of the record involved; {@code null} becomes the empty
     *                     string
     * @param key          the business key of the record involved; {@code null} becomes the empty
     *                     string
     * @param message      the detail message, expected to be one of this class's legacy literals
     * @param cause        the underlying failure; may be {@code null}
     * @throws IllegalArgumentException if {@code conflictKind} is {@code null}
     */
    public OptimisticLockConflictException(ConflictKind conflictKind, String entityName, String key,
            String message, Throwable cause) {
        super(message, cause);
        this.conflictKind = requireConflictKind(conflictKind);
        this.entityName = entityName == null ? "" : entityName;
        this.key = key == null ? "" : key;
    }

    /**
     * Validates that an arm was supplied. The arm is not optional: the legacy program always knows
     * which of the three states it is reporting, and a caller that cannot say which one has a
     * programming error rather than a runtime conflict.
     *
     * <p>The thrown message is a developer diagnostic, not operator-facing screen text.</p>
     *
     * @param conflictKind the candidate arm
     * @return the same arm, guaranteed non-{@code null}
     * @throws IllegalArgumentException if {@code conflictKind} is {@code null}
     */
    private static ConflictKind requireConflictKind(ConflictKind conflictKind) {
        if (conflictKind == null) {
            throw new IllegalArgumentException(
                    "conflictKind is required; supply one of the three legacy write-path arms");
        }
        return conflictKind;
    }

    /**
     * Returns which of the three legacy write-path arms produced this conflict, so the caller can
     * reproduce the legacy response for that arm.
     *
     * @return the conflict arm, never {@code null}
     */
    public ConflictKind conflictKind() {
        return this.conflictKind;
    }

    /**
     * Returns the descriptive name of the record involved, such as {@code "Account"},
     * {@code "Card"} or {@code "Customer"}.
     *
     * @return the entity name exactly as supplied, or the empty string if none was supplied; never
     *         {@code null} and never the text {@code "null"}
     */
    public String entityName() {
        return this.entityName;
    }

    /**
     * Returns the business key of the record involved.
     *
     * @return the key exactly as supplied, or the empty string if none was supplied; never
     *         {@code null} and never the text {@code "null"}
     */
    public String key() {
        return this.key;
    }
}
