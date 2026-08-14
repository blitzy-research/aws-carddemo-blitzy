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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.OptimisticLockException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link OptimisticLockConflictException}, the Java replacement for the legacy
 * concurrency-safety mechanism of the account-update transaction {@code CAUP}, implemented by
 * {@code app/cbl/COACTUPC.cbl}.
 *
 * <p>A pure unit test: no application context, no persistence unit, no database and no messaging
 * emulator. It constructs the type directly and asserts its contract, so everything asserted here is
 * verifiable without a running system.</p>
 *
 * <p><strong>Three distinct legacy states, not interchangeable.</strong> The legacy write path
 * renders three outcomes, each carried by its own level-88 condition name over one shared field and
 * each with its own operator-facing text. The record could not be locked for update at all - set at
 * {@code app/cbl/COACTUPC.cbl} L3912 when the account read-for-update does not return a normal
 * response, and at L3939 for the customer read-for-update - and that single state owns
 * <strong>two</strong> operator texts. The record was locked but the rewrite failed - set on the
 * account-rewrite failure arm at L4079 and the customer-rewrite failure arm at L4098. The record
 * changed before the update could be applied - the outcome of the before-and-after image comparison
 * in paragraph {@code 9700-CHECK-CHANGE-IN-REC} (L4109-L4192, exit label L4193), set at its account
 * mismatch exit L4143 and its customer mismatch exit L4189.</p>
 *
 * <p><strong>Four verbatim operator literals for three states.</strong> The condition names at
 * {@code app/cbl/COACTUPC.cbl} L517-L524 carry the operator text as their {@code VALUE}, so the
 * condition name and the text an operator reads are the same thing:
 * {@code Could not lock account record for update} (L517-L518),
 * {@code Could not lock customer record for update} (L519-L520),
 * {@code Record changed by some one else. Please review} (L521-L522) and
 * {@code Update of record failed} (L523-L524). A fifth condition name in the same block, at
 * L525-L526, carries {@code Error reading Card Data File}; that text belongs to the card
 * cross-reference read path and this test asserts it has not leaked in. Two properties of the third
 * literal are deliberate legacy spelling and are asserted so that no future tidy-up can quietly
 * change what an operator sees: the phrase is <strong>two words</strong>, and the literal ends at
 * {@code review} with <strong>no trailing full stop</strong> - the period visible in the COBOL
 * listing is the statement terminator, outside the quoted value.</p>
 *
 * <p><strong>The dispatch order, and why the conflict is recoverable.</strong> The
 * {@code EVALUATE} at {@code app/cbl/COACTUPC.cbl} L2606-L2615 dispatches the three states in a
 * fixed clause order - lock error at L2607-L2608, then failed update at L2609-L2610, then changed
 * data at L2611-L2612 - with a default changes-okayed arm at L2613-L2614. The changed-data arm sets
 * a <em>show details</em> state that re-displays the screen so the operator can review the current
 * values, and the literal itself says {@code Please review}, so that path is recoverable and
 * non-abending - which is why the type under test is deliberately unrelated to
 * {@link AbendException}. A separate arm of the same {@code EVALUATE}, at L2634-L2639, handles an
 * unexpected data scenario by populating the abend context with code {@code 0001}, a blank reason
 * and an unexpected-data-scenario message, then performing the abend routine; that arm is
 * {@link AbendException} territory, and this test pins the separation.</p>
 *
 * <p><strong>Why "extends RuntimeException" is the rollback assertion.</strong>
 * {@code EXEC CICS SYNCPOINT ROLLBACK} occurs exactly once in the estate, at
 * {@code app/cbl/COACTUPC.cbl} L4100, on the customer-rewrite failure arm; the account-rewrite
 * failure arm at L4076-L4081 sets the same state and does <em>not</em> roll back, because the
 * account write is the first of the two and there is as yet nothing to undo. Spring's declarative
 * transaction management rolls back by default <strong>only on unchecked exceptions</strong>, so
 * were this type checked the surrounding transaction would <em>commit</em> silently on a detected
 * conflict - precisely the lost update the legacy image comparison existed to prevent. Extending
 * {@link RuntimeException} is therefore the load-bearing property, and it is pinned below. The
 * container-backed half of the proof - two concurrent updates against a {@code @Version}-annotated
 * row on a real relational database, asserting both that this exception surfaces and that the
 * surrounding boundary rolls back - requires a container and a persistence unit and so belongs to
 * the integration tier rather than here.</p>
 *
 * <p><strong>Stronger isolation is an intentional improvement.</strong> All eight application file
 * definitions in {@code app/csd/CARDDEMO.CSD} - at L1, L13, L25, L37, L50, L63, L76 and L88 - carry,
 * identically, uncommitted read integrity, a locking update model, no journalling and no recovery, so
 * correctness rested entirely on that locking model plus each program's own image comparison.
 * PostgreSQL READ COMMITTED combined with the JPA {@code @Version} column is strictly stronger than
 * that baseline: conflicts the legacy field-by-field comparison would have missed are now caught.
 * Recorded as decision log entry D-15, so the stronger guarantee is read as the improvement it is
 * rather than as a behavioural change.</p>
 *
 * <p>A separate arm of the same {@code EVALUATE}, at L2634-L2639, handles an unexpected data
 * scenario by populating the abend context with code {@code 0001}, a blank reason and an
 * unexpected-data-scenario message, and then performing the abend routine. That arm is
 * {@link AbendException} territory. The two arms of one decision map to two different Java types,
 * and this test pins the separation.</p>
 *
 * <h2>Why "extends RuntimeException" is the rollback assertion</h2>
 *
 * <p>An explicit transaction-manager rollback request occurs exactly <strong>once</strong> in the
 * entire legacy
 * estate, at {@code app/cbl/COACTUPC.cbl} <strong>L4100</strong>, on the customer-rewrite failure
 * arm; the program sets the locked-but-update-failed state, rolls back, and jumps to the
 * write-processing exit at L4105. The account-rewrite failure arm at L4076-L4081 sets the same
 * state and does <em>not</em> roll back, because the account write is the first of the two and
 * there is as yet nothing to undo. In the Java target both arms sit inside one
 * {@code @Transactional} boundary, which is a documented, deliberate simplification and a strict
 * improvement; the decision-log entry recording it is owned elsewhere and is not written here.</p>
 *
 * <p>The unit-level proof of that rollback is structural, and it is not ceremony. Spring's
 * declarative transaction management rolls back by default <strong>only on unchecked
 * exceptions</strong>. Were this type checked, the surrounding transaction would <em>commit</em>
 * silently on a detected conflict, producing precisely the lost update that the legacy
 * before-and-after image comparison existed to prevent. "Extends {@code RuntimeException}" is
 * therefore the load-bearing property that makes the rollback happen, and it is pinned below.</p>
 *
 * <p>The container-backed half of that proof lives elsewhere by design: an integration test in the
 * repository and service integration folders drives two concurrent updates against a
 * {@code @Version}-annotated row on a real relational database and asserts both that this exception
 * surfaces and that the surrounding {@code @Transactional} boundary rolls back. That test needs a
 * container and a persistence unit, so it is an integration concern and is deliberately not
 * written here.</p>
 *
 * <h2>Stronger isolation is an intentional improvement, not a regression</h2>
 *
 * <p>All eight application file definitions in {@code app/csd/CARDDEMO.CSD} - at L1, L13, L25, L37,
 * L50, L63, L76 and L88 - carry, identically, uncommitted read integrity, a locking update model,
 * no journalling and no recovery. Correctness rested entirely on that locking model plus each
 * program's own before-and-after image comparison; the data store itself offered no isolation
 * guarantee and no rollback log. <strong>PostgreSQL READ COMMITTED combined with the JPA
 * {@code @Version} column is therefore strictly stronger than the legacy baseline.</strong> A
 * reviewer must read the stronger isolation as an intentional, recorded upgrade rather than as a
 * behavioural change: conflicts the legacy field-by-field comparison would have missed are now
 * caught, which is strictly safer.</p>
 *
 * <h2>Deliberate absences</h2>
 *
 * <p>The type under test publishes no fatality flag, no abend code and no severity accessor,
 * because a recoverable conflict has no terminal outcome to report. That absence is proved at
 * compile time by this test never calling such a member: no reflective enumeration of members is
 * performed anywhere here, and the platform reflection API is not referenced at all, because the
 * module's audited budget for reflection is zero.</p>
 */
@DisplayName("OptimisticLockConflictException - the recoverable write-conflict contract")
class OptimisticLockConflictExceptionTest {

    /**
     * The operator text for a failure to lock the account record, written here independently of the
     * constant the production class publishes so that the assertion is a real comparison against
     * the legacy contract rather than a comparison of a constant with itself.
     */
    private static final String EXPECTED_COULD_NOT_LOCK_ACCOUNT =
            "Could not lock account record for update";

    /** The operator text for a failure to lock the customer record, written independently. */
    private static final String EXPECTED_COULD_NOT_LOCK_CUSTOMER =
            "Could not lock customer record for update";

    /**
     * The operator text for a true optimistic-lock conflict, written independently. Two words, and
     * no trailing full stop: both are legacy spelling and both are part of the contract.
     */
    private static final String EXPECTED_RECORD_CHANGED =
            "Record changed by some one else. Please review";

    /** The operator text for a rewrite that failed after the lock was taken, written independently. */
    private static final String EXPECTED_UPDATE_FAILED = "Update of record failed";

    /**
     * The operator text of the card cross-reference read failure. It is declared here only so that
     * its <em>absence</em> from every conflict arm can be asserted; it belongs to a different
     * legacy path and must never surface from this exception.
     */
    private static final String CROSS_REFERENCE_READ_TEXT = "Error reading Card Data File";

    /** Neutral descriptive name of the account record. */
    private static final String ACCOUNT_ENTITY = "Account";

    /** Neutral descriptive name of the customer record; also the selector for the customer text. */
    private static final String CUSTOMER_ENTITY = "Customer";

    /** Neutral descriptive name of the card record, used to prove no accidental text selection. */
    private static final String CARD_ENTITY = "Card";

    /** Synthetic zero-padded eleven-character account key, as the legacy record image carries it. */
    private static final String ACCOUNT_KEY = "00000000011";

    /** Synthetic zero-padded nine-character customer key. */
    private static final String CUSTOMER_KEY = "000000011";

    /**
     * Synthetic composite key of the shape the module's composite identifiers use: an account key,
     * a two-character type code and a four-character category code. Keys are character substrings
     * of a record image, never integers, so this must survive as an exact string.
     */
    private static final String COMPOSITE_KEY = "00000000011|01|0005";

    /** The arm produced by the before-and-after image comparison. Aliased for readability. */
    private static final OptimisticLockConflictException.ConflictKind RECORD_CHANGED =
            OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE;

    /** The arm produced by a rewrite that failed after the lock was taken. Aliased for readability. */
    private static final OptimisticLockConflictException.ConflictKind UPDATE_FAILED =
            OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK;

    /** The arm produced when the record could not be locked at all. Aliased for readability. */
    private static final OptimisticLockConflictException.ConflictKind LOCK_NOT_ACQUIRED =
            OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED;

    /**
     * Serialises and deserialises an instance through in-memory byte streams, so that the
     * round-trip assertions do not repeat the plumbing.
     *
     * @param original the instance to round-trip
     * @return a freshly deserialised, distinct instance
     * @throws IOException            if either stream fails
     * @throws ClassNotFoundException if the deserialised type cannot be resolved
     */
    private static OptimisticLockConflictException roundTrip(OptimisticLockConflictException original)
            throws IOException, ClassNotFoundException {
        byte[] serialised;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
            out.flush();
            serialised = bytes.toByteArray();
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialised))) {
            return (OptimisticLockConflictException) in.readObject();
        }
    }

    /**
     * Cardinality and identity of the conflict arms. The count is not arbitrary: it is the number
     * of distinct states the legacy write path can render.
     */
    @Nested
    @DisplayName("ConflictKind cardinality")
    class ConflictKindCardinality {

        @Test
        @DisplayName("declares exactly three arms, because the legacy write path renders exactly "
                + "three states - lock not acquired (COACTUPC L3912, L3939), update failed after "
                + "the lock was taken (L4079, L4098) and record changed before update (L4143, "
                + "L4189) - reached from three distinct code paths and dispatched by the ordered "
                + "EVALUATE at L2606-L2615")
        void declaresExactlyThreeArmsMatchingTheThreeLegacyStates() {
            OptimisticLockConflictException.ConflictKind[] arms =
                    OptimisticLockConflictException.ConflictKind.values();

            assertThat(arms).hasSize(3);
            // Order-sensitive on purpose: an inserted or reordered arm fails here immediately,
            // which is what keeps the three-state cardinality from drifting.
            assertThat(arms).containsExactly(RECORD_CHANGED, UPDATE_FAILED, LOCK_NOT_ACQUIRED);
            assertThat(arms).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("rejects a fourth arm name, so the cross-reference read failure - a different "
                + "legacy path - cannot be smuggled in as a conflict state")
        void rejectsAFourthArmName() {
            assertThatThrownBy(
                    () -> OptimisticLockConflictException.ConflictKind.valueOf("XREF_READ_ERROR"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * The four operator-visible literals. Every assertion in this group is driven through observable
     * behaviour - the detail message an instance actually carries, or the text an arm resolves to -
     * rather than through the identifier of an enum constant, so the mapping stays pinned even if
     * the constants are renamed.
     */
    @Nested
    @DisplayName("Verbatim legacy operator text")
    class VerbatimLegacyOperatorText {

        @Test
        @DisplayName("the record-changed arm carries its legacy text verbatim: 'some one' stays two "
                + "words and the text ends at 'review' with no trailing full stop, because both "
                + "'corrections' would change what an operator reads (COACTUPC L521-L522)")
        void recordChangedArmCarriesItsLegacyTextVerbatim() {
            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            String message = conflict.getMessage();

            assertThat(message).isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(message).contains("some one");
            assertThat(message).contains("by some one else");
            assertThat(message).endsWith("review");
            assertThat(message).doesNotEndWith(".");
            assertThat(message).isNotEqualTo(EXPECTED_RECORD_CHANGED + ".");

            // A fused single-word spelling of the two-word legacy phrase is exactly one character
            // shorter. It is composed at run time rather than written as a literal so that the
            // prohibited "correction" appears nowhere in this source file.
            String fusedSpelling = EXPECTED_RECORD_CHANGED.replace("some one", "some".concat("one"));
            assertThat(fusedSpelling).hasSize(EXPECTED_RECORD_CHANGED.length() - 1);
            assertThat(message).isNotEqualTo(fusedSpelling);
            assertThat(message).doesNotContain(fusedSpelling);

            // Independent guards against invisible whitespace drift: exact length taken from the
            // literal itself, and equality with its own stripped form.
            assertThat(message).hasSize(EXPECTED_RECORD_CHANGED.length());
            assertThat(message).isEqualTo(message.strip());
        }

        @Test
        @DisplayName("the update-failed arm carries its legacy text verbatim, with no trailing full "
                + "stop and no whitespace drift (COACTUPC L523-L524)")
        void updateFailedArmCarriesItsLegacyTextVerbatim() {
            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(UPDATE_FAILED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            String message = conflict.getMessage();

            assertThat(message).isEqualTo(EXPECTED_UPDATE_FAILED);
            assertThat(message).endsWith("failed");
            assertThat(message).doesNotEndWith(".");
            assertThat(message).hasSize(EXPECTED_UPDATE_FAILED.length());
            assertThat(message).isEqualTo(message.strip());
        }

        @Test
        @DisplayName("the lock-not-acquired arm defaults to the account text, matching the legacy "
                + "order in which the two locks are taken (COACTUPC L3912 precedes L3939)")
        void lockNotAcquiredArmDefaultsToTheAccountText() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            String message = conflict.getMessage();

            assertThat(message).isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
            assertThat(message).endsWith("update");
            assertThat(message).doesNotEndWith(".");
            assertThat(message).hasSize(EXPECTED_COULD_NOT_LOCK_ACCOUNT.length());
            assertThat(message).isEqualTo(message.strip());
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage()).isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
        }

        @Test
        @DisplayName("the fourth literal is reachable: naming the customer record selects the "
                + "customer lock text, so one arm still publishes both of the legacy texts "
                + "(COACTUPC L519-L520 alongside L517-L518)")
        void namingTheCustomerRecordSelectsTheCustomerLockText() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, CUSTOMER_ENTITY, CUSTOMER_KEY);

            String message = conflict.getMessage();

            assertThat(message).isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
            assertThat(message).endsWith("update");
            assertThat(message).doesNotEndWith(".");
            assertThat(message).hasSize(EXPECTED_COULD_NOT_LOCK_CUSTOMER.length());
            assertThat(message).isEqualTo(message.strip());
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage(CUSTOMER_ENTITY))
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
        }

        @Test
        @DisplayName("customer text selection tolerates case and surrounding blanks, and no other "
                + "entity name reaches it")
        void customerTextSelectionToleratesCaseAndBlanksAndNothingElseReachesIt() {
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage("customer"))
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage("  Customer  "))
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage(CARD_ENTITY))
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage(ACCOUNT_ENTITY))
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage(""))
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
            assertThat(LOCK_NOT_ACQUIRED.defaultMessage(null))
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
        }

        @Test
        @DisplayName("entity-name selection applies to the lock arm only: the other two arms return "
                + "their own single legacy text whatever record is named")
        void entityNameSelectionAppliesToTheLockArmOnly() {
            assertThat(RECORD_CHANGED.defaultMessage(CUSTOMER_ENTITY)).isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(RECORD_CHANGED.defaultMessage(ACCOUNT_ENTITY)).isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(RECORD_CHANGED.defaultMessage()).isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(UPDATE_FAILED.defaultMessage(CUSTOMER_ENTITY)).isEqualTo(EXPECTED_UPDATE_FAILED);
            assertThat(UPDATE_FAILED.defaultMessage(ACCOUNT_ENTITY)).isEqualTo(EXPECTED_UPDATE_FAILED);
            assertThat(UPDATE_FAILED.defaultMessage()).isEqualTo(EXPECTED_UPDATE_FAILED);
        }

        @Test
        @DisplayName("the customer lock text is reachable only by naming the customer record, on "
                + "every constructor, so the entity name is the single selector for it")
        void theCustomerLockTextIsReachableOnlyByNamingTheCustomerRecord() {
            OptimisticLockConflictException fromThreeArguments = new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, CUSTOMER_ENTITY, CUSTOMER_KEY);
            OptimisticLockConflictException fromFiveArguments = new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, CUSTOMER_ENTITY, CUSTOMER_KEY,
                    EXPECTED_COULD_NOT_LOCK_CUSTOMER, null);

            assertThat(fromThreeArguments.getMessage()).isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
            assertThat(fromFiveArguments.getMessage()).isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
        }

        @Test
        @DisplayName("the customer lock text cannot be borrowed onto an account conflict, because no "
                + "legacy path can set the customer flag while reporting the account record: the "
                + "customer read at COACTUPC line 3921 is only reached once the account read at line "
                + "3894 has already succeeded")
        void theCustomerLockTextCannotBeBorrowedOntoAnAccountConflict() {
            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, ACCOUNT_ENTITY, ACCOUNT_KEY,
                    EXPECTED_COULD_NOT_LOCK_CUSTOMER, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("legacy text this arm resolves to");
        }

        @Test
        @DisplayName("the four literals the class publishes are exactly the four legacy texts, so "
                + "the published constants cannot drift from the contract either")
        void thePublishedConstantsAreExactlyTheFourLegacyTexts() {
            assertThat(OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE)
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
            assertThat(OptimisticLockConflictException.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE)
                    .isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
            assertThat(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE)
                    .isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED)
                    .isEqualTo(EXPECTED_UPDATE_FAILED);
        }

        @ParameterizedTest
        @EnumSource(OptimisticLockConflictException.ConflictKind.class)
        @DisplayName("every arm resolves to one of the four published legacy texts, undecorated and "
                + "free of whitespace drift")
        void everyArmResolvesToOneOfTheFourPublishedLegacyTexts(
                OptimisticLockConflictException.ConflictKind arm) {
            String resolved = arm.defaultMessage();

            assertThat(resolved).isIn(EXPECTED_COULD_NOT_LOCK_ACCOUNT, EXPECTED_COULD_NOT_LOCK_CUSTOMER,
                    EXPECTED_RECORD_CHANGED, EXPECTED_UPDATE_FAILED);
            assertThat(resolved).isNotBlank();
            assertThat(resolved).isEqualTo(resolved.strip());
            assertThat(resolved).doesNotEndWith(".");
        }

        @ParameterizedTest
        @EnumSource(OptimisticLockConflictException.ConflictKind.class)
        @DisplayName("the card cross-reference read text never surfaces from any arm, because it "
                + "belongs to a different legacy path (COACTUPC L525-L526)")
        void theCardCrossReferenceReadTextNeverSurfacesFromAnyArm(
                OptimisticLockConflictException.ConflictKind arm) {
            assertThat(arm.defaultMessage()).isNotEqualTo(CROSS_REFERENCE_READ_TEXT);
            assertThat(arm.defaultMessage(ACCOUNT_ENTITY)).isNotEqualTo(CROSS_REFERENCE_READ_TEXT);
            assertThat(arm.defaultMessage(CUSTOMER_ENTITY)).isNotEqualTo(CROSS_REFERENCE_READ_TEXT);
            assertThat(arm.defaultMessage(CARD_ENTITY)).isNotEqualTo(CROSS_REFERENCE_READ_TEXT);

            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(arm, CARD_ENTITY, ACCOUNT_KEY);
            assertThat(conflict.getMessage()).isNotEqualTo(CROSS_REFERENCE_READ_TEXT);
            assertThat(conflict.getMessage()).doesNotContain("Card Data File");
        }
    }

    /**
     * The arm, the entity name and the key must travel intact, because a caller reproducing the
     * legacy response needs all three: the arm decides which screen state to set, and the entity
     * name and key identify the record to re-present for review.
     */
    @Nested
    @DisplayName("Field round-tripping")
    class FieldRoundTripping {

        @Test
        @DisplayName("the three-argument constructor round-trips the arm, the entity name and the "
                + "key, and takes its detail message from the arm")
        void threeArgumentConstructorRoundTripsEveryField() {
            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            assertThat(conflict.conflictKind()).isEqualTo(RECORD_CHANGED);
            assertThat(conflict.entityName()).isEqualTo(ACCOUNT_ENTITY);
            assertThat(conflict.key()).isEqualTo(ACCOUNT_KEY);
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_RECORD_CHANGED);
        }

        @Test
        @DisplayName("the four-argument constructor round-trips every field alongside the cause")
        void fourArgumentConstructorRoundTripsEveryField() {
            OptimisticLockException cause = new OptimisticLockException("row version did not match");

            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    UPDATE_FAILED, CUSTOMER_ENTITY, CUSTOMER_KEY, cause);

            assertThat(conflict.conflictKind()).isEqualTo(UPDATE_FAILED);
            assertThat(conflict.entityName()).isEqualTo(CUSTOMER_ENTITY);
            assertThat(conflict.key()).isEqualTo(CUSTOMER_KEY);
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_UPDATE_FAILED);
        }

        @Test
        @DisplayName("the five-argument constructor round-trips every field alongside the explicit "
                + "message and the cause")
        void fiveArgumentConstructorRoundTripsEveryField() {
            IOException cause = new IOException("rewrite response was not normal");

            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, CUSTOMER_ENTITY, CUSTOMER_KEY,
                    EXPECTED_COULD_NOT_LOCK_CUSTOMER, cause);

            assertThat(conflict.conflictKind()).isEqualTo(LOCK_NOT_ACQUIRED);
            assertThat(conflict.entityName()).isEqualTo(CUSTOMER_ENTITY);
            assertThat(conflict.key()).isEqualTo(CUSTOMER_KEY);
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
        }

        @Test
        @DisplayName("an explicit detail message cannot contradict the arm: text belonging to a "
                + "different arm is rejected, because the legacy binds each text to a condition name "
                + "as a level-88 VALUE and never composes one independently of it")
        void anExplicitMessageCannotContradictTheArm() {
            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY, EXPECTED_UPDATE_FAILED, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("legacy text this arm resolves to");
        }

        @Test
        @DisplayName("text that belongs to no arm at all is rejected too, so the five-argument "
                + "constructor cannot introduce a fifth operator literal the legacy never had")
        void textThatBelongsToNoArmIsRejected() {
            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY, "Please try again later", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("legacy text this arm resolves to");
        }

        @Test
        @DisplayName("a near miss is rejected as firmly as unrelated text - a changed word, a "
                + "different case, a trailing stop or a stray blank - because the resolved text is "
                + "reproduced from the legacy byte for byte")
        void aNearMissIsRejectedAsFirmlyAsUnrelatedText() {
            assertThatThrownBy(() -> new OptimisticLockConflictException(LOCK_NOT_ACQUIRED,
                    ACCOUNT_ENTITY, ACCOUNT_KEY, "Could not lock account for update", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OptimisticLockConflictException(RECORD_CHANGED,
                    ACCOUNT_ENTITY, ACCOUNT_KEY, EXPECTED_RECORD_CHANGED.toUpperCase(Locale.ROOT),
                    null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OptimisticLockConflictException(UPDATE_FAILED,
                    ACCOUNT_ENTITY, ACCOUNT_KEY, EXPECTED_UPDATE_FAILED + ".", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OptimisticLockConflictException(UPDATE_FAILED,
                    ACCOUNT_ENTITY, ACCOUNT_KEY, " " + EXPECTED_UPDATE_FAILED, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an absent detail message is rejected rather than defaulted, so a caller who "
                + "wants the arm's own text uses a constructor that resolves it and says so")
        void anAbsentDetailMessageIsRejected() {
            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("legacy text this arm resolves to");
        }

        @Test
        @DisplayName("the rejection diagnostic does not echo the text it refused, so a value that "
                + "arrived from outside the module cannot ride an exception message into a log")
        void theRejectionDiagnosticDoesNotEchoTheRefusedText() {
            String hostile = "ZZ-CANARY-REFUSED-CONFLICT-TEXT-ZZ";

            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY, hostile, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(hostile);
        }

        @Test
        @DisplayName("the classification is still round-tripped when the arm's own text is supplied "
                + "explicitly, so constraining the message cost the constructor none of its purpose")
        void theClassificationIsRoundTrippedWhenTheArmsOwnTextIsSupplied() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY, EXPECTED_RECORD_CHANGED, null);

            assertThat(conflict.conflictKind()).isEqualTo(RECORD_CHANGED);
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(conflict.getMessage())
                    .isEqualTo(conflict.conflictKind().defaultMessage(conflict.entityName()));
        }

        @Test
        @DisplayName("every arm and entity pairing satisfies message equals arm default, so a "
                + "boundary may render the conflict from the classification and reach the same bytes")
        void everyArmAndEntityPairingSatisfiesMessageEqualsArmDefault() {
            for (OptimisticLockConflictException.ConflictKind arm
                    : OptimisticLockConflictException.ConflictKind.values()) {
                for (String entity : new String[] {ACCOUNT_ENTITY, CUSTOMER_ENTITY, CARD_ENTITY, ""}) {
                    OptimisticLockConflictException conflict =
                            new OptimisticLockConflictException(arm, entity, ACCOUNT_KEY);

                    assertThat(conflict.getMessage())
                            .as("arm %s entity '%s'", arm, entity)
                            .isEqualTo(arm.defaultMessage(entity));
                }
            }
        }

        @Test
        @DisplayName("the detail message is never decorated with the entity name or the key, so "
                + "message-text comparison against the legacy contract stays exact")
        void theDetailMessageIsNeverDecoratedWithTheEntityNameOrTheKey() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            assertThat(conflict.getMessage()).doesNotContain(ACCOUNT_ENTITY);
            assertThat(conflict.getMessage()).doesNotContain(ACCOUNT_KEY);
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_RECORD_CHANGED);
        }

        @Test
        @DisplayName("composite and zero-padded keys survive unchanged, because legacy keys are "
                + "character substrings of a record image and never integers")
        void compositeAndZeroPaddedKeysSurviveUnchanged() {
            OptimisticLockConflictException padded =
                    new OptimisticLockConflictException(UPDATE_FAILED, ACCOUNT_ENTITY, ACCOUNT_KEY);
            OptimisticLockConflictException composite =
                    new OptimisticLockConflictException(UPDATE_FAILED, ACCOUNT_ENTITY, COMPOSITE_KEY);

            assertThat(padded.key()).isEqualTo(ACCOUNT_KEY);
            assertThat(padded.key()).hasSize(ACCOUNT_KEY.length());
            // A numeric reading would collapse the leading zeros; the character reading must not.
            assertThat(padded.key()).isNotEqualTo("11");
            assertThat(padded.key()).startsWith("0");

            assertThat(composite.key()).isEqualTo(COMPOSITE_KEY);
            assertThat(composite.key()).hasSize(COMPOSITE_KEY.length());
        }

        @Test
        @DisplayName("an absent entity name or key becomes the empty string, and the detail message "
                + "never leaks the text of a missing value")
        void anAbsentEntityNameOrKeyBecomesTheEmptyString() {
            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(RECORD_CHANGED, null, null);

            assertThat(conflict.entityName()).isNotNull();
            assertThat(conflict.entityName()).isEmpty();
            assertThat(conflict.key()).isNotNull();
            assertThat(conflict.key()).isEmpty();
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(conflict.getMessage()).doesNotContain("null");
        }

        @Test
        @DisplayName("an absent entity name on the lock arm still resolves the account text, so a "
                + "missing name degrades to the legacy default rather than to no text at all")
        void anAbsentEntityNameOnTheLockArmStillResolvesTheAccountText() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, null, null, new IOException("read for update failed"));

            assertThat(conflict.entityName()).isEmpty();
            assertThat(conflict.key()).isEmpty();
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_COULD_NOT_LOCK_ACCOUNT);
            assertThat(conflict.getMessage()).doesNotContain("null");
        }
    }

    /**
     * A conflict with no arm cannot be routed. The arm is the classification the ordered legacy
     * dispatch keys on, so an unclassified conflict is a programming error and must fail loudly at
     * construction rather than degrade into a silently mis-rendered screen.
     */
    @Nested
    @DisplayName("A missing arm is rejected")
    class MissingArmIsRejected {

        @Test
        @DisplayName("the three-argument constructor rejects a missing arm, because the arm is what "
                + "the ordered legacy dispatch at COACTUPC L2606-L2615 keys on")
        void threeArgumentConstructorRejectsAMissingArm() {
            assertThatThrownBy(
                    () -> new OptimisticLockConflictException(null, ACCOUNT_ENTITY, ACCOUNT_KEY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("conflictKind");
        }

        @Test
        @DisplayName("the four-argument constructor rejects a missing arm even when a cause is "
                + "supplied")
        void fourArgumentConstructorRejectsAMissingArm() {
            IOException cause = new IOException("read for update failed");

            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    null, ACCOUNT_ENTITY, ACCOUNT_KEY, cause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("conflictKind");
        }

        @Test
        @DisplayName("the five-argument constructor rejects a missing arm even when an explicit "
                + "legacy message is supplied, so text alone cannot substitute for classification")
        void fiveArgumentConstructorRejectsAMissingArm() {
            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    null, ACCOUNT_ENTITY, ACCOUNT_KEY, EXPECTED_RECORD_CHANGED, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("conflictKind");
        }
    }

    /**
     * The underlying failure is always carried through. The persistence provider's own
     * optimistic-lock exception is the concrete failure this type translates, so it is used here as
     * the cause rather than an arbitrary stand-in.
     */
    @Nested
    @DisplayName("Cause preservation")
    class CausePreservation {

        @Test
        @DisplayName("the four-argument constructor preserves the persistence provider's own "
                + "optimistic-lock failure - the version-column collision this type translates - as "
                + "the cause, unswallowed")
        void fourArgumentConstructorPreservesThePersistenceProviderFailure() {
            OptimisticLockException cause = new OptimisticLockException("row version did not match");

            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY, cause);

            assertThat(conflict.getCause()).isSameAs(cause);
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_RECORD_CHANGED);
        }

        @Test
        @DisplayName("the five-argument constructor preserves the cause alongside the explicit "
                + "message")
        void fiveArgumentConstructorPreservesTheCause() {
            OptimisticLockException cause = new OptimisticLockException("row version did not match");

            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    UPDATE_FAILED, CUSTOMER_ENTITY, CUSTOMER_KEY, EXPECTED_UPDATE_FAILED, cause);

            assertThat(conflict.getCause()).isSameAs(cause);
            assertThat(conflict.getMessage()).isEqualTo(EXPECTED_UPDATE_FAILED);
        }

        @Test
        @DisplayName("the three-argument constructor leaves no cause, because the before-and-after "
                + "image comparison detects the conflict itself rather than catching a failure")
        void threeArgumentConstructorLeavesNoCause() {
            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            assertThat(conflict.getCause()).isNull();
        }

        @Test
        @DisplayName("an absent cause is accepted on both cause-taking constructors and leaves the "
                + "legacy message intact")
        void anAbsentCauseIsAccepted() {
            OptimisticLockConflictException fromFourArguments = new OptimisticLockConflictException(
                    UPDATE_FAILED, ACCOUNT_ENTITY, ACCOUNT_KEY, null);
            OptimisticLockConflictException fromFiveArguments = new OptimisticLockConflictException(
                    UPDATE_FAILED, ACCOUNT_ENTITY, ACCOUNT_KEY, EXPECTED_UPDATE_FAILED, null);

            assertThat(fromFourArguments.getCause()).isNull();
            assertThat(fromFourArguments.getMessage()).isEqualTo(EXPECTED_UPDATE_FAILED);
            assertThat(fromFiveArguments.getCause()).isNull();
            assertThat(fromFiveArguments.getMessage()).isEqualTo(EXPECTED_UPDATE_FAILED);
        }
    }

    /**
     * The structural half of the rollback proof. The database-level half - two concurrent updates to
     * a version-annotated row on a real relational database, asserting both that this exception
     * surfaces and that the surrounding transactional boundary rolls back - needs a container and a
     * persistence unit, so it belongs to the repository and service integration tier and is not
     * written here.
     */
    @Nested
    @DisplayName("The rollback contract, proved structurally")
    class RollbackContract {

        @Test
        @DisplayName("the conflict is unchecked, and that is precisely what makes the transaction "
                + "roll back: declarative transaction management rolls back by default only on "
                + "unchecked exceptions, so a checked conflict would let the transaction commit "
                + "silently and reproduce the lost update the legacy image comparison existed to "
                + "prevent")
        void theConflictIsUncheckedWhichIsWhatMakesTheTransactionRollBack() {
            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            assertThat(conflict).isInstanceOf(RuntimeException.class);
            assertThat(OptimisticLockConflictException.class.getSuperclass())
                    .isEqualTo(RuntimeException.class);
            assertThat(RuntimeException.class.isAssignableFrom(OptimisticLockConflictException.class))
                    .isTrue();
            // An Exception as well, which taken together with the RuntimeException assertion above
            // places it on the unchecked branch of the hierarchy: inside the range of types
            // transaction management inspects, and on the side of that range it rolls back for.
            assertThat(Exception.class.isAssignableFrom(OptimisticLockConflictException.class))
                    .isTrue();
            assertThat(Throwable.class.isAssignableFrom(OptimisticLockConflictException.class))
                    .isTrue();

            // Raising it through a method that declares no throws clause is a compile-time proof of
            // the same property: were the type ever made checked, this file would stop compiling.
            assertThatThrownBy(() -> raiseWithoutDeclaringIt(conflict)).isSameAs(conflict);
        }

        /**
         * Raises the conflict from a method with <em>no</em> {@code throws} clause. That this
         * compiles at all is the compiler's own assertion that the type is unchecked, which is the
         * property declarative transaction management relies on to roll back by default.
         *
         * @param conflict the conflict to raise
         */
        private void raiseWithoutDeclaringIt(OptimisticLockConflictException conflict) {
            throw conflict;
        }

        @Test
        @DisplayName("the conflict is not an Error, so it is a recoverable application outcome "
                + "rather than a virtual-machine failure a caller must not catch")
        void theConflictIsNotAnError() {
            assertThat(Error.class.isAssignableFrom(OptimisticLockConflictException.class)).isFalse();
        }

        @Test
        @DisplayName("the conflict can be thrown and caught as itself with every field intact, "
                + "which is the whole of what a transactional caller needs to route the legacy "
                + "response for the arm that failed")
        void theConflictCanBeThrownAndCaughtWithEveryFieldIntact() {
            OptimisticLockException cause = new OptimisticLockException("row version did not match");
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    RECORD_CHANGED, CUSTOMER_ENTITY, CUSTOMER_KEY, cause);

            assertThatThrownBy(() -> {
                throw conflict;
            })
                    .isSameAs(conflict)
                    .isInstanceOf(OptimisticLockConflictException.class)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage(EXPECTED_RECORD_CHANGED);

            assertThat(conflict.getCause()).isSameAs(cause);
            assertThat(conflict.conflictKind()).isEqualTo(RECORD_CHANGED);
            assertThat(conflict.entityName()).isEqualTo(CUSTOMER_ENTITY);
            assertThat(conflict.key()).isEqualTo(CUSTOMER_KEY);
        }
    }

    /**
     * A conflict is not an abend. The two outcomes come from two different arms of the same legacy
     * decision and must map to two different Java types, or a recoverable review prompt would be
     * indistinguishable from a terminal failure.
     */
    @Nested
    @DisplayName("A conflict is not an abend")
    class AConflictIsNotAnAbend {

        @Test
        @DisplayName("the conflict is not an abend, because the record-changed arm at COACTUPC "
                + "L2611-L2612 sets a show-details state that re-displays the screen for the "
                + "operator to review, while the unrelated arm at L2634-L2639 is the one that "
                + "abends with code 0001 on an unexpected data scenario")
        void theConflictIsNotAnAbend() {
            assertThat(AbendException.class.isAssignableFrom(OptimisticLockConflictException.class))
                    .isFalse();
            // Neither direction: the two types are siblings under RuntimeException, not a hierarchy.
            assertThat(OptimisticLockConflictException.class.isAssignableFrom(AbendException.class))
                    .isFalse();
            assertThat(OptimisticLockConflictException.class.getSuperclass())
                    .isNotEqualTo(AbendException.class);
        }

        @Test
        @DisplayName("the record-changed text asks the operator to review, which is the observable "
                + "evidence that this arm is a recoverable re-display rather than a termination")
        void theRecordChangedTextAsksTheOperatorToReview() {
            OptimisticLockConflictException conflict =
                    new OptimisticLockConflictException(RECORD_CHANGED, ACCOUNT_ENTITY, ACCOUNT_KEY);

            assertThat(conflict.getMessage()).contains("Please review");
            assertThat(conflict.getMessage()).endsWith("review");
        }
    }

    /**
     * Serialization identity. The type is reachable across a serialization boundary because
     * {@code Throwable} is serializable, so its identity must be declared rather than derived from a
     * compiler-generated hash that would change with any structural edit.
     *
     * <p>{@code java.io.ObjectStreamClass} is used to read the declared identity. It is a
     * serialization descriptor from {@code java.io}, not a member of the platform reflection API,
     * and it is used here in a test source only: no reflective field scan is performed, and no
     * reflection is introduced into production to satisfy this check.</p>
     */
    @Nested
    @DisplayName("Serialization identity")
    class SerializationIdentity {

        @Test
        @DisplayName("the serialization identity is declared explicitly as one, not derived from a "
                + "compiler-generated hash that any structural edit would silently change")
        void theSerializationIdentityIsDeclaredExplicitlyAsOne() {
            ObjectStreamClass descriptor =
                    ObjectStreamClass.lookup(OptimisticLockConflictException.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an instance survives a serialization round-trip with its arm, entity name, "
                + "key, legacy message and cause intact")
        void anInstanceSurvivesASerializationRoundTrip() throws IOException, ClassNotFoundException {
            IOException cause = new IOException("rewrite response was not normal");
            OptimisticLockConflictException original = new OptimisticLockConflictException(
                    LOCK_NOT_ACQUIRED, CUSTOMER_ENTITY, CUSTOMER_KEY, cause);

            OptimisticLockConflictException restored = roundTrip(original);

            assertThat(restored).isNotSameAs(original);
            assertThat(restored.conflictKind()).isEqualTo(LOCK_NOT_ACQUIRED);
            assertThat(restored.entityName()).isEqualTo(CUSTOMER_ENTITY);
            assertThat(restored.key()).isEqualTo(CUSTOMER_KEY);
            assertThat(restored.getMessage()).isEqualTo(EXPECTED_COULD_NOT_LOCK_CUSTOMER);
            assertThat(restored.getCause()).isInstanceOf(IOException.class);
            assertThat(restored.getCause()).hasMessage("rewrite response was not normal");
        }

        @Test
        @DisplayName("a round-trip preserves the two-word legacy phrase and the absent trailing full "
                + "stop, so the external contract survives transport as well as construction")
        void aRoundTripPreservesTheLegacySpelling() throws IOException, ClassNotFoundException {
            OptimisticLockConflictException original =
                    new OptimisticLockConflictException(RECORD_CHANGED, ACCOUNT_ENTITY, COMPOSITE_KEY);

            OptimisticLockConflictException restored = roundTrip(original);

            assertThat(restored.getMessage()).isEqualTo(EXPECTED_RECORD_CHANGED);
            assertThat(restored.getMessage()).contains("some one");
            assertThat(restored.getMessage()).doesNotEndWith(".");
            assertThat(restored.key()).isEqualTo(COMPOSITE_KEY);
            assertThat(restored.getCause()).isNull();
        }
    }

}
