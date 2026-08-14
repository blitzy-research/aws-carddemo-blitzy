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
package com.carddemo.repository;

import com.carddemo.domain.Account;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for the {@code account} table, which replaces the {@code ACCTDAT}
 * key-sequenced VSAM base cluster of the legacy estate. It is the only persistence entry point for
 * {@link Account}. Most access is inherited; the posting program additionally needs two explicit
 * operations to reproduce the legacy {@code REWRITE ... INVALID KEY} arm without a timing window - a
 * keyed read that holds the row and an update-count rewrite predicated on the version that read
 * observed.
 *
 * <p><strong>Legacy provenance.</strong> The row shape derives from copybook
 * {@code app/cpy/CVACT01Y.cpy}, a 300-byte record whose 11-byte account identifier sits at offset 0,
 * followed by a one-character status code at offset 11, five two-decimal monetary fields at offsets 12,
 * 24, 36, 78 and 90, three ten-character date fields at offsets 48, 58 and 68, a ten-character postal
 * field at offset 102 and a ten-character group identifier at offset 112. A 178-byte trailing filler
 * begins at offset 122 and is deliberately not persisted, so offset 122 plus 178 bytes accounts for the
 * full 300. Two further sources corroborate the layout independently: the cluster definition in
 * {@code app/jcl/ACCTFILE.jcl}, which fixes a key width of 11 at offset 0 against a record size of 300,
 * and the file section of {@code app/cbl/CBACT01C.cbl}, which splits the very same record into an
 * eleven-digit key field followed by a 289-byte data field.
 *
 * <p><strong>Identity is the legacy business key, and its type is {@code String}.</strong> Because the
 * key occupies offset 0, it is the leading substring of the record image rather than a separate
 * attribute, which is why no surrogate key exists here or anywhere else in this module. The identifier
 * is an eleven-character zero-filled lexeme, so its leading zeros are contractual; a numeric key type
 * would discard them and break the record-image-to-row correspondence that byte-level output parity
 * depends on.
 *
 * <p><strong>Optimistic locking, and why none of it appears here.</strong> This entity and the card
 * entity are the only two in the module that carry a version attribute, because they are the only two
 * records the legacy tier rewrote in place after comparing a before image against an after image. The
 * provider-managed {@code @Version} property on {@link Account} is the faithful replacement for that
 * comparison, and the provider enforces it on flush. A conflict therefore surfaces as a transactional
 * rollback and is translated into a domain-specific conflict exception in the service layer, which is
 * where the single rollback of the legacy online update path also lives. Every online path against this
 * table therefore uses the inherited unheld read and relies on that version check alone, and none of them
 * should acquire a lock: replacing an optimistic model with mutual exclusion would introduce waiting the
 * legacy system never had.
 *
 * <p>The one keyed read that does hold its row, {@link #findByIdForUpdate(String)}, is not an exception to
 * that rule but a different use of a lock, and the distinction is worth stating because it is easy to
 * misread. It does not replace the version check - the rewrite it precedes still carries the version in
 * its predicate, and a concurrent change is still refused rather than waited for. The hold exists only so
 * that the batch tier can answer <em>whether the row exists</em> at the moment it rewrites, because the
 * legacy {@code INVALID KEY} arm is exactly that answer and asking for it in a second unprotected query
 * would let a concurrent commit invert it. Its scope is one record of one batch step, and no online path
 * uses it.
 *
 * <p>Read-committed isolation combined with that version check is strictly stronger than the legacy
 * baseline, whose file definitions specified uncommitted read integrity with no recovery and no
 * journaling and rested solely on a locking update model plus the programs' own image comparison. The
 * stronger isolation is a deliberate, documented posture change recorded in the decision log, not a
 * behavioural regression.
 *
 * <p><strong>The group identifier is deliberately not a foreign key.</strong> It is only a nonunique
 * leading portion of the composite disclosure-group key, recurring seventeen times per group across the
 * fifty-one seeded disclosure rows, so it cannot serve as a foreign-key target. The point is settled
 * twice over: every one of the fifty seeded accounts carries a ten-space group identifier, which
 * matches none of the three seeded group identifiers, so a constraint would reject the entire seed. The
 * interest calculation instead composes the full key at runtime and falls back to a documented default
 * group when no row matches, which referential integrity could not express. No foreign key originates
 * from this table; those that exist point at it from the card, cross-reference and category-balance
 * tables.
 *
 * <p><strong>Monetary values.</strong> The five amounts are carried as {@code BigDecimal} at scale 2 and
 * never as an approximate binary numeric type, so that decimal precision is identical to the
 * zoned-decimal source fields rather than merely close to them. The estate contains no rounding clause
 * on any arithmetic statement, which means every store into a two-decimal field truncates toward zero;
 * all scaling is therefore performed by {@code com.carddemo.util.ZonedDecimalCodec} under
 * {@code RoundingMode.DOWN}. No arithmetic, scaling, rounding, aggregation or comparison of amounts
 * happens in this interface, which is precisely why it cannot introduce a divergent rounding policy.
 *
 * <p><strong>Values are never trimmed.</strong> Fixed-width padding is significant throughout this
 * schema, and the migration uses bounded variable-length columns rather than blank-padded fixed-length
 * ones so that padding survives a round trip verbatim. The ten-space group identifier and the uniform
 * postal value in the sample dataset are meaningful data and a fixture characteristic respectively, and
 * neither is normalised. Offset arithmetic, parsing and formatting belong exclusively to the
 * fixed-width mappers in the utility layer.
 *
 * <p><strong>Source anomaly.</strong> The originating copybook drops a letter from the expiration-date
 * field name. The Java property and the migration column are both spelled correctly while the mapper's
 * offset and width are unchanged, so the record image stays byte-compatible; the misspelling is
 * recorded in the decision log and the traceability matrix and is not reintroduced here.
 *
 * <p><strong>No JPA association is declared anywhere in this module.</strong> The card, cross-reference
 * and category-balance foreign keys exist in the schema, but no entity declares a to-one or to-many
 * mapping, so lazy loading outside a transaction is impossible, disabling the view-scoped persistence
 * context is trivially satisfied, and no entity graph or fetch join is needed or permitted. The
 * account-to-card relationship is navigated from the card side, by a derived query over the card's
 * account-identifier column that reproduces the legacy alternate index; adding a counterpart here
 * would duplicate an existing access path.
 *
 * <p><strong>The posting rewrite is deliberately explicit.</strong> {@code CBTRN02C} distinguishes a
 * successful account rewrite from an invalid-key outcome by the operation's status. A preflight
 * existence query followed by {@code save} cannot preserve that boundary: the row may disappear between
 * the two calls, and a managed entity can defer its SQL until a later flush. The update below therefore
 * writes exactly the three balances changed by paragraph {@code 2800-UPDATE-ACCOUNT-REC}, increments the
 * provider-managed version explicitly, and returns the affected-row count. Clearing the persistence
 * context after execution prevents the validation-time managed account from being flushed a second time.
 * This is the one AAP-authorized exception to the module's general prohibition on modifying queries.
 *
 * <p>All other keyed retrieval, counting, saving and sorted or paged traversal remains inherited from
 * {@code JpaRepository}. Record absence is expressed as an empty {@code Optional} from the inherited
 * keyed lookup. The reference seed loads exactly fifty rows into this table under the local and test
 * profiles only, after the customer table and before the card table.
 *
 * @see Account
 */
public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Reads one account by its business key and holds the row for the rewrite that follows it in the
     * same unit of work.
     *
     * <p><strong>What this exists to remove.</strong> A compare-and-set rewrite that affects no row has
     * two possible causes - the row is gone, or the row is present carrying a different version - and the
     * legacy reports only the first, through {@code REWRITE ... INVALID KEY}. Asking afterwards whether
     * the row exists distinguishes them but is a second, unprotected query: another writer committing
     * between the two turns a version race into a reported absence, or an absence into a reported race,
     * and the caller cannot tell. Establishing presence <em>under a write lock before</em> the rewrite
     * removes the window entirely, because nothing can change the row between the two statements.
     *
     * <p>An empty result is therefore the legacy invalid-key condition, and a row whose version differs
     * from the one the caller read is a change the caller must refuse. Neither answer can be wrong for a
     * timing reason.
     *
     * <p><strong>This is stronger than the legacy baseline, deliberately.</strong> The legacy cluster is
     * defined {@code READINTEG(UNCOMMITTED)}, {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, and the
     * batch program rewrites by key with no hold at all, so the legacy could not observe a concurrent
     * change and had no arm for one. The strengthening is recorded in {@code docs/decision-log.md} entry
     * DL-170 rather than presented as parity.
     *
     * <p>The lock is released when the unit of work that took it ends, so the caller must already be
     * inside one; the persistence provider reports a call made outside a transaction rather than silently
     * reading without the lock. The query is written out because the lock mode, not a predicate, is what
     * distinguishes it from the inherited keyed read.
     *
     * @param accountId the eleven-character account business key
     * @return the account, or {@link Optional#empty()} when no row carries that key
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.acctId = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") String accountId);

    /**
     * Rewrites the three account balances changed by the posting program and advances the optimistic
     * version in the same database statement, but only while the row still carries the version the
     * caller read.
     *
     * <p><strong>Why the version is part of the predicate and not only of the assignment.</strong>
     * Incrementing {@code version} while matching on the business key alone advances the counter without
     * ever consulting it, which is the shape of a lost update: an online screen that read the same
     * account, changed a balance and committed between this caller's read and this statement would have
     * its write silently overwritten, and because the statement always matched exactly one row the
     * caller could never learn that it had happened. Naming the read version in the predicate turns that
     * case into a zero-row outcome, which the caller can then tell apart from a genuinely absent
     * account - the legacy invalid-key condition - by asking whether the row exists at all. The
     * statement is therefore a compare-and-set, and the version attribute now does the job the
     * {@code @Version} declaration promises rather than being carried along as a counter.
     *
     * <p>Two callers of the same account inside one run are unaffected: the statement clears the
     * persistence context, and the posting mainline re-reads the account for every record, so each
     * record compares against the version its own read observed.
     *
     * <p>The version predicate remains even though the caller now holds the row when it runs. It is
     * defence in depth rather than duplication: it guarantees that a caller which had <em>not</em> taken
     * the hold still cannot overwrite a concurrent change unnoticed, so the safety of this statement does
     * not depend on every future call site remembering to lock first.
     *
     * @param accountId          the eleven-character account business key
     * @param version            the optimistic version observed when the account was read
     * @param currentBalance     the new current balance
     * @param currentCycleCredit the new current-cycle credit total
     * @param currentCycleDebit  the new current-cycle debit total, retaining a negative sign
     * @return one when the row was rewritten, or zero when no row carries that key at that version -
     *     which the caller distinguishes by having established presence under a write lock first, through
     *     {@link #findByIdForUpdate(String)}, rather than by asking afterwards
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Account a
            SET a.acctCurrBal = :currentBalance,
                a.acctCurrCycCredit = :currentCycleCredit,
                a.acctCurrCycDebit = :currentCycleDebit,
                a.version = a.version + 1
            WHERE a.acctId = :accountId
              AND a.version = :version
            """)
    int rewritePostingBalances(@Param("accountId") String accountId,
                               @Param("version") long version,
                               @Param("currentBalance") BigDecimal currentBalance,
                               @Param("currentCycleCredit") BigDecimal currentCycleCredit,
                               @Param("currentCycleDebit") BigDecimal currentCycleDebit);
}
