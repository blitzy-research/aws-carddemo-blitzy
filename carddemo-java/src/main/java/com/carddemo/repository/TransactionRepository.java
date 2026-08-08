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

import com.carddemo.domain.Transaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for table {@code transaction} - the Java replacement for the
 * {@code TRANSACT} VSAM KSDS base cluster of the legacy CardDemo estate, and for the batch-only
 * alternate index defined over that cluster's processing timestamp.
 *
 * <p>This is the <em>validated</em> transaction master, the settled side of the posting boundary. The
 * byte-for-byte parallel {@code daily_transaction} table is the raw landing surface for the
 * sequential daily input; a row reaches <em>this</em> table only once the posting job has accepted
 * it, or once the interest accrual run or the online add path has written one directly.
 *
 * <h2>Legacy provenance</h2>
 *
 * <p>The persisted shape is copybook {@code app/cpy/CVTRA05Y.cpy}, whose own header states a record
 * length of 350 bytes. Thirteen named fields are followed by a 20-byte trailing filler, and the
 * declared widths sum to exactly 350. Mapped positions, as zero-based offset and width:
 *
 * <ul>
 *   <li>0/16 - transaction identifier, the business key</li>
 *   <li>16/2 - transaction type code</li>
 *   <li>18/4 - transaction category code</li>
 *   <li>22/10 - source code</li>
 *   <li>32/100 - description</li>
 *   <li>132/11 - amount, nine digits before the decimal point and two after</li>
 *   <li>143/9 - merchant identifier</li>
 *   <li>152/50 - merchant name</li>
 *   <li>202/50 - merchant city</li>
 *   <li>252/10 - merchant postal code</li>
 *   <li>262/16 - card number, one-based position 263</li>
 *   <li>278/26 - origination timestamp</li>
 *   <li>304/26 - processing timestamp, one-based position 305</li>
 *   <li>330/20 - trailing filler, deliberately neither an entity attribute nor a column</li>
 * </ul>
 *
 * <p>Two job-control members corroborate that layout without reference to the copybook.
 * {@code app/jcl/TRANFILE.jcl} defines the base cluster with indexed organisation,
 * {@code KEYS(16 0)} and {@code RECORDSIZE(350 350)}, fixing the record at 350 bytes for both its
 * minimum and its maximum. The alternate index declared over the same cluster is keyed
 * {@code KEYS(26 304)}, which lands exactly on the processing timestamp. Accumulating the widths
 * listed above reaches 262 where the card number begins, 278 where the origination timestamp begins
 * and 304 where the processing timestamp begins, so three independent artifacts agree on the same
 * three offsets and they can be treated as contract rather than as inference.
 *
 * <h2>The identifier is the legacy business key, and nothing generates it</h2>
 *
 * <p>A key offset of 0 makes the key the leading substring of the record image, which is the identity
 * pattern of every keyed file in the estate; {@code app/cbl/CBACT01C.cbl} shows the same shape in its
 * file section, where a keyed record is declared as a leading key field followed by a remainder
 * field. The identifier is therefore the entity's own identity attribute and the key type of this
 * repository is {@link String}, because the copybook declares that field as sixteen
 * <em>alphanumeric</em> characters rather than as sixteen digits. No surrogate key exists anywhere in
 * this module.
 *
 * <p><strong>No database sequence, no identity column and no generated-value annotation is permitted
 * for this table.</strong> Identifiers are minted by reading the current maximum and adding one
 * within the same transactional boundary as the insert - see {@link #findMaxId()} for the derivation
 * and for the concrete reason a sequence is not an acceptable substitute. The shipped schema declares
 * no sequence and no auto-generated column anywhere, for exactly this reason.
 *
 * <p><strong>Sharing a transaction is not by itself enough to make that rule safe.</strong> Under
 * the {@code READ COMMITTED} isolation this module runs at, an insert another transaction has not yet
 * committed is invisible, so two allocators could read the same maximum, compute the same successor and
 * collide on the primary key. That window is closed by {@link #lockIdentifierAllocation(long)}, the
 * transaction-scoped application lock this interface publishes for exactly that purpose: it is the
 * relational form of the position the legacy region held across its own read-modify-write, it is
 * transaction-mandatory so it cannot be taken where it would be released immediately, and every caller
 * that mints an identifier takes it before reading the maximum. What remains the caller's is the response
 * to a writer that reached the table <em>without</em> the lock, and both allocating services carry that
 * obligation where they mint. The precondition the maximum depends on -
 * that every stored identifier is exactly sixteen digit characters - is enforced twice over rather
 * than assumed: the entity refuses any other shape before an insert or an update reaches the
 * database, and {@code V1__create_schema.sql} carries the matching check constraint
 * {@code ck_transaction_tran_id_digits} so that a writer bypassing the entity is refused as well.
 *
 * <h2>The four merchant columns on this table carry no prefix</h2>
 *
 * <p>Every other column here carries the regular {@code tran_} prefix, but on exactly four of them
 * the schema drops it and names the column for the merchant attribute alone: {@code merchant_id},
 * {@code merchant_name}, {@code merchant_city} and {@code merchant_zip}. The parallel
 * {@code daily_transaction} table, whose legacy record is byte-for-byte identical, <em>does</em>
 * prefix its own merchant columns after its own field-name prefix. That asymmetry is a property of
 * the shipped migration, and it is the single most likely mapping error in this package: regularising
 * any of the four names, or copying them across from the sibling table, produces an identifier the
 * migration never declares. Because the mapping is validated at start-up rather than generated, the
 * mistake fails the application context outright instead of quietly creating a second column - a
 * loud failure, but an easy mistake to make while tidying, which is why the rule is written down
 * here. The decision is recorded in {@code docs/decision-log.md}.
 *
 * <h2>The processing-timestamp alternate index: declared twice, emitted once, batch only</h2>
 *
 * <p>{@code app/jcl/TRANFILE.jcl} and {@code app/jcl/TRANIDX.jcl} both define an alternate index of
 * the same name, related to the same base cluster, with the same key width and offset
 * ({@code KEYS(26 304)}) and the same non-unique, upgraded attributes. That is <strong>one logical
 * index described in two members, not two indexes</strong>, and {@code V2__create_indexes.sql} emits
 * it exactly once as the non-unique B-tree index {@code idx_transaction_tran_proc_ts}. A translator
 * working member by member would have created it twice.
 *
 * <p>It is <strong>batch only</strong>, and the proof is an absence rather than an assertion. The
 * resource definition {@code app/csd/CARDDEMO.CSD} registers exactly eight files to the online
 * region: the account, card, card alternate index, card cross-reference, cross-reference alternate
 * index, customer, transaction base cluster and user-security files. Both card-side alternate-index
 * paths are among them; the transaction alternate-index path is not, and the transaction entry
 * addresses the base cluster directly. No online request can therefore reach this index, and exactly
 * one caller in the module does: the reporting job's record-selection step, through
 * {@link #findByProcessingDateWindowOrderedByCardNumber(String, String)}.
 *
 * <p><strong>That query is the index's reason for existing and its only consumer.</strong> The legacy
 * reporting stream unloads the whole cluster and then hands the unload to an external sort whose
 * inclusion condition addresses ten characters of the processing timestamp; performed as a file scan
 * the selection reads every stored record, which is precisely the cost the alternate index was defined
 * to avoid. The selection is therefore declared here, where the index can serve it, and the report
 * step calls it rather than filtering an unloaded generation for itself. A range query that no
 * production caller reached was previously removed from this interface for being a second contract for
 * the same selection; what is declared now is the <em>first</em> contract, and the report's selection
 * step is its caller.
 *
 * <p>{@code V2__create_indexes.sql} additionally declares {@code fk_transaction_card}, from this
 * table's card number to the card master. The entity nonetheless declares <strong>no association of
 * any kind</strong>: the constraint is enforced by the database on write, while reads return plain
 * column values. Deferred loading outside a transaction is consequently impossible, the
 * view-scoped session is disabled module-wide and trivially satisfied, and no eager-fetch graph
 * declaration and no fetch join is needed or permitted anywhere in this module.
 *
 * <h2>What this interface deliberately does not declare</h2>
 *
 * <h2>The transaction-list browse is keyset paged, never offset paged</h2>
 *
 * <p>The list screen implemented by {@code app/cbl/COTRN00C.cbl} browses the base cluster by
 * transaction identifier a page at a time, and its page size is 10. Uniquely among the estate's three
 * paginated screens, that size comes from no screen-row table at all: the program declares no such
 * table and the figure is established purely by loop bounds, a forward loop running while an index
 * does not exceed 10 and a fill loop running until the index reaches 11. The estate's other two page
 * sizes belong to other repositories' consumers and are recorded only so they are never confused with
 * this one: the card list is 7, from a seven-row screen table, and the user list is 10, from a ten-row
 * screen table.
 *
 * <p><strong>The legacy browse computes no row number and no offset.</strong> It remembers the first
 * and last identifier of the page it displayed - the screen's own FIRST and LAST commarea fields, each
 * sixteen characters wide - and repositions on one of them. <strong>No keyset finder is declared here
 * for it.</strong> The browse is served by the inherited {@code findAll(Pageable)} carrying an
 * identifier sort, and the cursor semantics - which identifier to resume from, in which direction, and
 * how a further page is discovered - live in the service that reproduces the screen, because they are
 * properties of the screen's retained state rather than of this table. Declaring a pair of
 * cursor-shaped finders here would publish four permutations of one screen's paging strategy as
 * persistence contract, and a second screen with a different strategy would have to add four more.
 *
 * <p><strong>A backward page is read descending and presented ascending.</strong> The legacy backward
 * path seeds its screen index with 10 and decrements it, so the first record it reads - the highest
 * identifier below the cursor - lands in the <em>bottom</em> screen slot and the tenth lands in the
 * top one. The page the operator sees is consequently in ascending identifier order, exactly like a
 * forward page. The descending order therefore belongs to the read and not to the response: the
 * calling service reverses the rows this interface returns before it builds the page, which is what
 * {@code com.carddemo.api.dto.PageMetadata} documents on its backward factory. Presenting a backward
 * page in the order it was read would invert the screen relative to the legacy fill.
 *
 * <p><strong>No finder on the card number.</strong> Statement generation does read transactions by
 * card number, but that path has no alternate index in the legacy estate - the three alternate
 * indexes are the two card-side ones and this table's processing-timestamp one - and
 * {@code V2__create_indexes.sql} creates no index on that column either. Declaring a finder for it
 * would add a fourth access path with no legacy antecedent and no supporting index.
 *
 * <p><strong>No aggregate beyond the single maximum, and no bulk operation.</strong> The report
 * program {@code app/cbl/CBTRN03C.cbl} contains no arithmetic statement at all; its page and account
 * total breaks are assembled line by line at the fixed 133-character report width by the formatter in
 * the utility layer. Introducing a set-based total here would relocate that logic and change its
 * truncation behaviour. The legacy tier is record-at-a-time and rewrite-in-place throughout, so no
 * bulk update or delete is declared either.
 *
 * <h2>Values are carried exactly as stored</h2>
 *
 * <p>Nothing in this interface trims, pads, folds or normalises an argument or a result. Fixed-width
 * padding is significant, and the schema uses bounded variable-length character columns rather than
 * blank-padded fixed-length ones precisely so that the stored padding survives a round trip
 * unchanged. The source code is one consequence: it is a raw, space-padded string whose values in the
 * estate include two ten-character terminal and operator codes and a shorter system-generated code,
 * and it is never an enumerated type and never trimmed. Sixteen-character identifiers and card
 * numbers keep their leading zeros, which are contractual rather than cosmetic.
 *
 * <p>The amount is a {@code BigDecimal} at scale 2, mapped to a fixed-precision numeric column, and
 * never an approximate binary numeric type. The estate contains no rounding clause on any arithmetic
 * statement, so every store into a two-decimal field truncates rather than rounds; all scaling is
 * performed by {@code com.carddemo.util.ZonedDecimalCodec} with the rounding mode set to truncate
 * toward zero. No scaling, no arithmetic and no aggregate over the amount happens here.
 *
 * <h2>Absence is a return value here, not an exception</h2>
 *
 * <p>The legacy batch programs never branched on a raw two-byte file status. They normalised it first
 * into a coarse application result - one value for success, one for end of file, one for anything
 * else - and branched on that, as {@code app/cbl/CBACT01C.cbl} shows in its read paragraph. That
 * normalisation, the decision whether an absent record is an error, and the terminal abend path all
 * belong to the service and batch tiers. This interface therefore declares no exception type and no
 * status enumeration. Its own expressions of "nothing there" are an empty {@link Optional} from
 * {@link #findMaxId()} and an empty {@link Optional} from the inherited single-row lookup.
 *
 * <p>The interface carries no stereotype annotation: the repository infrastructure discovers it
 * through the component scan rooted at the base package, so annotating it would add nothing and
 * imply that the others are somehow different.
 *
 * <p>The table <strong>starts empty</strong>. {@code V3__seed_reference_data.sql} seeds zero rows
 * into it, as it does for the user-security table; rows appear only from the posting, interest and
 * online add paths. Every assertion about the first minted identifier depends on that fact.
 *
 * <p>Provenance: legacy sources are read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @see Transaction
 * @see JpaRepository
 * @since 1.0.0
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, String>, TransactionInsertRepository {

    /**
     * Returns the highest transaction identifier currently stored, or empty when the table holds no
     * rows at all.
     *
     * <p>This is the read half of the legacy identifier-generation rule, reproduced exactly. The
     * online bill-payment program {@code app/cbl/COBIL00C.cbl} mints a new identifier without any
     * sequence facility: it seeds the record key with high values, starts a browse at that key, reads
     * <strong>backward</strong> by one record - which lands on the highest identifier present - ends
     * the browse, moves what it found into a sixteen-digit numeric work field initialised to zeros,
     * adds one to it, initialises a fresh record and moves the incremented value back into the key.
     * Its backward-read paragraph handles the end-of-file response by moving zeros into the key, so an
     * empty file seeds zero.
     *
     * <p><strong>The increment does not happen here.</strong> This method supplies the maximum and
     * nothing else. The bill-payment service adds one and performs the insert inside the same
     * transactional boundary as this read, which is what reproduces the legacy read-modify-write.
     * Splitting the read from the write, or caching the last value issued, would break that
     * correspondence, so no state of any kind is held here.
     *
     * <p><strong>A shared transaction is necessary but not sufficient, and closing the remaining
     * window is the caller's concurrency policy rather than this method's.</strong> Under the
     * {@code READ COMMITTED} isolation this module runs at, a row another transaction has inserted but
     * not yet committed is invisible to this query, so two concurrent allocators inside their own
     * transactions can both observe the same maximum. They then compute the same successor and collide
     * on the primary key, so one of them fails outright instead of receiving the next identifier - the
     * duplicate never reaches the table, because the identifier is the primary key, but the payment
     * that lost the race is rejected for a reason that has nothing to do with the payment.
     *
     * <p><strong>That window is closed by serialising allocators, and the serialisation is what the
     * legacy region already had.</strong> The legacy program held its browse position on the keyed file
     * across the backward read, the increment and the write, over a file defined with a locking update
     * model - so two tasks minting an identifier could not interleave between the read and the write. The
     * relational equivalent is {@link #lockIdentifierAllocation(long)}: one application-wide lock, taken
     * before the maximum is read and released when the allocating transaction ends. Because the lock is
     * scoped to a transaction, it is declared <em>transaction-mandatory</em> - taken outside one it would
     * be released the instant the statement completed and would serialise nothing - and the two callers
     * that mint an identifier take it as the first statement of the unit that also performs the insert.
     *
     * <p>What the lock does <em>not</em> do is remove the caller's obligation, and the reason is a writer
     * it cannot reach. A bulk load, a migration script or a future caller that never takes the lock can
     * still store an identifier between an allocator's read and its insert, so <strong>a caller minting
     * an identifier from this maximum still owns the response to a collision.</strong> The two callers
     * answer it the same way: the maximum is re-read under the lock they still hold and the identifier is
     * minted once more, up to a bounded number of attempts, and the last attempt lets the store's own
     * duplicate response reach the source's already-exists arm rather than looping. Two allocators that
     * both take the lock are serialised by it and never consume that bound.
     *
     * <p><strong>An empty result is the analogue of the legacy end-of-file response.</strong> The
     * maximum over zero rows is null, which the repository infrastructure materialises as an empty
     * {@link Optional}. That exact correspondence is the reason the return type is wrapped rather than
     * plain, and it is why the caller has a well-defined seed - zero - for the very first identifier
     * instead of a null to defend against.
     *
     * <p><strong>The first identifier on an empty table is the sixteen-character string
     * {@code 0000000000000001}, and not {@code 1}.</strong> The legacy increment writes a
     * sixteen-digit value back into a sixteen-character alphanumeric field, and such a move always
     * produces sixteen zero-padded digits. Any fixture or assertion that expects a bare {@code 1} is
     * wrong.
     *
     * <p><strong>Why a textual maximum is correct here, and collation-independent.</strong> The
     * identifier column is character data because the copybook declares the field as sixteen
     * alphanumeric characters; returning a numeric type would destroy the leading zeros that the
     * external contract depends on. Lexicographic and numeric maxima coincide only because every
     * identifier is exactly sixteen digit characters, zero-padded, with no sign overpunch - and the
     * legacy program guarantees precisely that by moving a sixteen-digit numeric value into the
     * alphanumeric field. Under that precondition the two orderings are the same and the result does
     * not depend on the database collation, since all sixteen characters lie in the digit range.
     * <strong>Every writer must preserve that precondition:</strong> storing a shorter, unpadded or
     * non-numeric identifier would silently break the ordering and therefore break identifier
     * generation, without breaking compilation.
     *
     * <p><strong>A database sequence is forbidden as a substitute</strong>, and the reason is concrete
     * rather than stylistic: a sequence never reuses a value it has handed out, whereas this rule
     * always reuses a gap. The first time a payment transaction rolls back after consuming an
     * identifier, a sequence would step permanently past that value and diverge from the legacy
     * numbering for the remaining life of the table. Gaps are not hypothetical - they are guaranteed
     * by the first rollback. The shipped schema accordingly declares no sequence and no auto-generated
     * column anywhere.
     *
     * @return the highest stored transaction identifier, or an empty {@link Optional} when the table
     *         holds no rows
     */
    @Query("""
            SELECT MAX(t.tranId)
            FROM Transaction t
            """)
    Optional<String> findMaxId();

    /**
     * Returns every transaction whose processing date falls inside an inclusive ten-character window,
     * ordered by card number ascending, which is the record selection the reporting stream performs.
     *
     * <h2>What this reproduces, statement for statement</h2>
     *
     * <p>The cataloged reporting procedure declares two sort symbols over the 350-byte record - the card
     * number as sixteen zoned-decimal bytes at one-based position 263, and the processing date as ten
     * character bytes at one-based position 305 - then applies an inclusion condition that admits a
     * record whose processing date is greater than or equal to the start bound <em>and</em> less than or
     * equal to the end bound, and orders what survives by the card number ascending. Both bounds are
     * inclusive and both comparisons are between characters. This method is that step: the predicate is
     * the {@code WHERE} clause, the ordering is the {@code ORDER BY} clause, and the width the predicate
     * addresses is the ten leading characters of the timestamp rather than the whole 26-character field.
     *
     * <p><strong>The ten-character width is the trap, and it is why the UPPER bound takes a prefix.</strong>
     * The stored field is 26 characters; the bound is 10. Comparing the <em>whole</em> column against a
     * ten-character upper bound drops every record processed on the end date, because a longer string
     * whose leading characters equal a shorter one sorts above it - so a record stamped
     * {@code 2022-07-06 12:00:00.000000} would fail a {@code <= '2022-07-06'} test even though the legacy
     * admits it. Taking the leading ten characters on the upper bound is what makes it inclusive in the
     * sense the legacy means.
     *
     * <p><strong>The lower bound is deliberately left bare, and that is the half that reaches the
     * index.</strong> A wrapped lower bound is not indexable, so wrapping both would have left this
     * selection reading every stored row - the very cost the alternate index exists to avoid. Bare is
     * also exactly equivalent: a 26-character value whose ten-character prefix is at or above a
     * ten-character bound is itself at or above that bound, because either the two differ within the
     * first ten characters, in which case the value is greater, or the prefix equals the bound, in which
     * case the longer value is greater. The prefix upper bound remains a filter, which is expected and
     * sufficient. One consequence is worth stating because it removes a guard rather than adding one: an
     * unprocessed record carries twenty-six blanks, which sort below the digits of any date, so the bare
     * lower bound already excludes it and this query needs no emptiness test.
     *
     * <p><strong>Card-number ordering, and what breaks ties.</strong> The sort key is the card number and
     * nothing else, so records sharing a card number are in no order the sort utility guarantees. The
     * identifier is added as a second key here for one reason: it makes the result <em>stable</em>, so the
     * emitted report is a function of the stored rows and not of the plan the server happened to choose.
     * The card number is sixteen digit characters in every writer's output - the schema's own key-shape
     * check on the transaction identifier states the same rule for that column, and the card master's
     * width check states it for card numbers - so ascending character order and ascending numeric order
     * coincide, which is what lets a character comparison reproduce the utility's zoned-decimal typing.
     * The report job nonetheless re-applies its own zoned-decimal comparator to the rows it receives, so
     * the emitted order is that comparator's even if a future collation were to disagree with this one.
     *
     * <p><strong>Collation independence.</strong> Both operands of each comparison are ten characters in
     * the same shape - four digits, a hyphen, two digits, a hyphen, two digits - so the hyphens occupy
     * identical positions and cannot change the outcome under any collation that orders digits normally.
     * The delivered schema is validated against a server initialised with the byte-ordering locale, and
     * this predicate does not depend on that.
     *
     * <p><strong>Why the result is a plain list and why that is bounded in practice.</strong> The legacy
     * step materialised its whole result as a dataset before the report program read a record of it, so a
     * materialised result is the faithful shape rather than a concession. The window is the bound: the
     * caller supplies a start and an end date and receives the records processed between them, which is
     * the same set the legacy sort wrote to its output dataset. The report step then walks that set once,
     * writing one record at a time.
     *
     * <p>This is the only method on this interface that reads through the processing-timestamp alternate
     * index, and the lower bound alone is the predicate able to drive it.
     *
     * <p>Recorded in {@code docs/decision-log.md} DL-276.
     *
     * @param  startDate the inclusive lower bound, exactly {@code YYYY-MM-DD}
     * @param  endDate   the inclusive upper bound, exactly {@code YYYY-MM-DD}
     * @return the selected records, ordered by card number ascending then by identifier ascending, empty
     *         when the window admits none
     */
    @Query("""
            SELECT t
            FROM Transaction t
            WHERE t.tranProcTs >= :startDate
              AND SUBSTRING(t.tranProcTs, 1, 10) <= :endDate
            ORDER BY t.tranCardNum ASC, t.tranId ASC
            """)
    List<Transaction> findByProcessingDateWindowOrderedByCardNumber(
            @Param("startDate") String startDate, @Param("endDate") String endDate);
}
