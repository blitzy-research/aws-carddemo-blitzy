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
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
 * <p><strong>Sharing a transaction is not by itself enough to make that rule safe, so allocation is
 * serialised explicitly.</strong> Under the {@code READ COMMITTED} isolation this module runs at, an
 * insert another transaction has not yet committed is invisible, so two allocators can read the same
 * maximum, compute the same successor and collide on the primary key. An allocator therefore takes
 * {@link #lockIdentifierAllocation(long)} first, which holds the read-modify-write open for one
 * allocator at a time and releases at commit or rollback. The precondition the maximum depends on -
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
 * addresses the base cluster directly. No online request could therefore reach this index, so its
 * Java realisation - {@link #findByProcessingDateRange(String, String, Pageable)} - serves the
 * reporting job alone.
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
 * sixteen characters wide - and repositions on one of them. The Java counterpart is therefore a keyset
 * read: {@link #findByTranIdGreaterThanOrderByTranIdAsc(String, Limit)} resumes a forward browse after
 * the last identifier displayed and {@link #findByTranIdLessThanOrderByTranIdDesc(String, Limit)}
 * resumes a backward browse before the first. Only the opening page of a browse, which has no cursor
 * to resume from, uses the inherited paged {@code findAll} - at page zero, where an offset costs
 * nothing. Paging deeper by offset would make the database re-count and discard every earlier row on
 * each turn, and would let a row inserted or removed between two turns shift the window so that a row
 * is shown twice or skipped; a cursor cannot do either.
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
 * {@link #findMaxId()}, an empty {@link Optional} from the inherited single-row lookup, and an empty
 * {@link Slice} from {@link #findByProcessingDateRange(String, String, Pageable)}.
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
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * The advisory-lock key that serialises identifier allocation on this table: {@code 350016}.
     *
     * <p>The value is arbitrary but fixed, and it is spelled so that it names its own subject - the
     * legacy record length followed by the legacy key width, 350 bytes and 16 characters, as
     * {@code app/jcl/TRANFILE.jcl} declares them through its record size and its key definition.
     * Nothing in the database interprets it. The only requirement an advisory lock places on its key is
     * that every participant uses the same one, which is why the constant is declared here, beside the
     * method that consumes it, rather than restated at each call site. It is the module's only
     * advisory-lock key, so it cannot collide with another purpose.
     */
    long IDENTIFIER_ALLOCATION_LOCK_KEY = 350_016L;

    /**
     * Acquires the transaction-scoped advisory lock that serialises identifier allocation, blocking
     * until it is held and releasing automatically when the surrounding transaction ends.
     *
     * <p><strong>Call this before {@link #findMaxId()} whenever the maximum is being read in order to
     * mint a new identifier.</strong> Reading the maximum and inserting its successor inside one
     * transaction is not sufficient on its own: under {@code READ COMMITTED} an uncommitted insert is
     * invisible, so two concurrent allocators can observe the same maximum, derive the same successor
     * and then collide on the primary key - the loser failing rather than receiving the next
     * identifier. This lock makes the read-modify-write sequence one allocator at a time, which is the
     * serialisation the legacy region obtained from holding a record-level browse position across its
     * own read, increment and write.
     *
     * <p><strong>It is a lock and not a sequence, and that distinction is the whole point.</strong> A
     * sequence never reissues a value it handed out, so the first rollback after an identifier was
     * consumed would step the numbering permanently past that value and diverge from the legacy rule
     * for the remaining life of the table; {@link #findMaxId()} develops that argument. An advisory
     * lock adds no object to the schema, hands out no value of its own and leaves the
     * maximum-plus-one rule exactly as the legacy program states it - it only decides who executes
     * that rule next.
     *
     * <p><strong>Transaction-scoped, so no failure path can leak it.</strong> The lock is released by
     * commit and by rollback alike, with no explicit unlock to forget and nothing to release in a
     * finally block. A caller outside a transaction would take a lock that is released immediately and
     * would gain nothing, which is why the transactional boundary belongs to the calling service and
     * not to this interface.
     *
     * <p><strong>Why this is the module's only native statement.</strong> An advisory lock is a
     * function of the database session and has no expression in the query language: there is no derived
     * form and no query-language form of it. The pessimistic row locks the query language does offer
     * cannot substitute, because the row to lock is the current maximum and on an empty table there is
     * no such row - the very first two allocators would still collide, which is precisely the case a
     * seeded-from-empty table starts in. The statement is a fixed literal carrying one bound parameter:
     * it concatenates nothing, interpolates nothing and so introduces no injection surface, and the
     * module still calls {@code createNativeQuery} nowhere at all.
     *
     * <p><strong>What the allocating service still owes, and why it is not owed here.</strong> Two
     * allocators that both take this lock cannot collide, so no retry is needed between them. A retry is
     * needed only against a writer that reached the table without taking it - a bulk load, a migration
     * script, or a future caller that forgot - and the response to a duplicate key from such a source is
     * to re-read the maximum under the lock and try once more. That belongs to the service that owns the
     * transactional boundary, because only it can decide how many attempts are reasonable and what to
     * report when they are exhausted; this interface can neither open a new transaction nor observe the
     * outcome of the insert. The obligation is recorded here so the service that mints identifiers
     * carries it deliberately rather than discovering it.
     *
     * @param lockKey the advisory-lock key, always {@link #IDENTIFIER_ALLOCATION_LOCK_KEY}
     * @return the constant {@code 1}, which exists only because a select must project something; the
     *         value carries no meaning and the useful effect is that the lock is now held
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:lockKey)", nativeQuery = true)
    int lockIdentifierAllocation(@Param("lockKey") long lockKey);

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
     * <p><strong>A shared transaction is necessary but not sufficient, and the caller must take
     * {@link #lockIdentifierAllocation(long)} first.</strong> Under the {@code READ COMMITTED}
     * isolation this module runs at, a row another transaction has inserted but not yet committed is
     * invisible to this query, so two concurrent allocators inside their own transactions can both
     * observe the same maximum. They then compute the same successor and collide on the primary key, so
     * one of them fails outright instead of receiving the next identifier - the duplicate never reaches
     * the table, because the identifier is the primary key, but the payment that lost the race is
     * rejected for a reason that has nothing to do with the payment. The advisory lock declared above
     * closes that window by admitting one allocator at a time, and it is the reason this method's
     * result can be relied on for the length of the transaction that read it.
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
     * Reads the transactions that follow a boundary identifier, in ascending identifier order, limited
     * to the number of rows the caller asks for.
     *
     * <p>The forward half of the transaction-list keyset browse. The cursor is the identifier of the
     * last row the previous page displayed - the screen's LAST commarea field - and the comparison is
     * strict, so that row is not shown twice. The identifier is the primary key and therefore unique,
     * which is what makes a single-column cursor total and the sequence free of ties.
     *
     * <p><strong>Ask for one row more than the screen holds.</strong> The legacy program discovers that
     * a further page exists by attempting one more read and observing the outcome, never by counting the
     * cluster. Requesting eleven rows for a ten-row screen reproduces that exactly: eleven returned
     * means a further page follows and the eleventh row is discarded, ten or fewer means this is the
     * last page. That is why the limit is the caller's and why this method neither counts nor reports
     * availability.
     *
     * @param tranId the exclusive lower bound - the last identifier already displayed - matched exactly
     *               as supplied and never trimmed or padded
     * @param limit  the maximum number of rows to read, which the caller sets to the screen's row count
     *               plus one
     * @return the matching rows in ascending identifier order, at most {@code limit} of them, possibly
     *         empty and never {@code null}
     */
    List<Transaction> findByTranIdGreaterThanOrderByTranIdAsc(String tranId, Limit limit);

    /**
     * Reads the transactions that precede a boundary identifier, in descending identifier order,
     * limited to the number of rows the caller asks for.
     *
     * <p>The backward half of the transaction-list keyset browse, and the direct counterpart of the
     * legacy backward read. The cursor is the identifier of the first row the previous page displayed -
     * the screen's FIRST commarea field - and the comparison is strict, so that row is not repeated.
     *
     * <p><strong>Descending is the read order, not the presentation order.</strong> The legacy backward
     * path seeds its screen index with 10 and decrements it, so the highest identifier below the cursor
     * fills the bottom screen slot and the page the operator sees ascends exactly like a forward page.
     * The calling service therefore reverses these rows before building the response. Handing them to a
     * response in the order they arrive would invert the screen.
     *
     * <p>The one-extra-row convention of the forward method applies here too, and answers the
     * preceding-page question rather than the following-page one.
     *
     * @param tranId the exclusive upper bound - the first identifier already displayed - matched
     *               exactly as supplied and never trimmed or padded
     * @param limit  the maximum number of rows to read, which the caller sets to the screen's row count
     *               plus one
     * @return the matching rows in descending identifier order, at most {@code limit} of them, possibly
     *         empty and never {@code null}
     */
    List<Transaction> findByTranIdLessThanOrderByTranIdDesc(String tranId, Limit limit);

    /**
     * Returns one caller-sized slice of the transactions whose processing date falls within an
     * inclusive date range, ordered by card number ascending.
     *
     * <p>This is the Java realisation of the batch transaction report's selection and ordering, and of
     * the batch-only processing-timestamp alternate index that made it affordable.
     * {@code V2__create_indexes.sql} backs it with the non-unique B-tree index
     * {@code idx_transaction_tran_proc_ts}, so the range resolves through that index rather than by
     * scanning the transaction master.
     *
     * <h2>The result is sliced, because the range is operator-chosen and the table is append-only</h2>
     *
     * <p>The two bounds arrive from a job parameter that an operator supplies, and rows are only ever
     * added to this table - by the posting run, by the interest run and by the online add path. The
     * number of rows a range selects is therefore unbounded in principle and grows for the life of the
     * deployment, so materialising a whole range into one collection would make the reporting job's
     * memory a function of how much history had accumulated and of how wide a range somebody typed.
     * The reader consumes the range a page at a time instead.
     *
     * <p>A {@link Slice} rather than a {@link org.springframework.data.domain.Page}: a page carries a
     * total count, which costs a second aggregate query over the same range on every fetch and which
     * the report has no use for - it breaks its pages and its totals from the rows themselves, line by
     * line. A slice reports only whether another page follows, which is exactly what a sequential
     * reader needs.
     *
     * <p><strong>The ordering carries a tie-break so that paging is deterministic.</strong> Card
     * number is not unique across transactions, so an ordering on it alone leaves rows that share a
     * card number in an order the engine may choose differently for each page - which would let a row
     * be returned twice, or skipped, as the reader advances. The identifier is appended as a second,
     * unique ordering term to make the sequence total. That is faithful rather than additive: the
     * legacy external sort declares one key and no {@code EQUALS} option, so it guarantees nothing at
     * all about the relative order of records sharing a card number, and any total order refining the
     * declared key is admissible. The primary ordering is unchanged.
     *
     * <p>The ordering above is declared in the query text, so it is applied before anything a caller's
     * {@link Pageable} adds. A sort supplied through the pageable is appended after it and can only
     * refine an already total order, so the contract cannot be overridden from a call site; passing an
     * unsorted pageable is the intended usage.
     *
     * <h2>The legacy comparison is a ten-character prefix compare</h2>
     *
     * <p>The cataloged procedure {@code app/proc/TRANREPT.prc} declares two symbolic field names to
     * the external sort and two date parameters. The card-number field is declared at one-based
     * position 263 with width 16, typed as zoned decimal. The processing-<em>date</em> field is
     * declared at one-based position 305 with width <strong>10</strong>, typed as
     * <strong>character</strong> - and the underlying column at that position is 26 characters wide.
     * The two parameters are ten-character date literals. The sort orders by the card-number field
     * ascending and keeps only those records whose processing-date field is greater than or equal to
     * the start parameter and less than or equal to the end parameter.
     *
     * <p>So the legacy filter compares <strong>only the first ten characters</strong> of the
     * timestamp - its date prefix - as character data, against ten-character literals. Position 305
     * also corroborates the layout independently: it is the one-based form of the zero-based offset
     * 304 that the copybook gives for the processing timestamp and that the alternate index uses as
     * its key offset.
     *
     * <h2>Both ends are inclusive, and the predicate is deliberately asymmetric</h2>
     *
     * <p>The two bounds are inclusive, matching the greater-than-or-equal and less-than-or-equal pair
     * of the legacy selection. Neither may be made strict, and the range may not be renormalised into
     * a half-open interval.
     *
     * <p><strong>The upper bound compares a ten-character prefix of the column; the lower bound
     * compares the bare column. That difference is required, not an oversight.</strong> Comparing the
     * full 26-character value against a ten-character end-date literal would exclude every
     * transaction processed <em>on</em> the end date, because a value that extends a literal sorts
     * above that literal - a stored value of the end date followed by a time component is
     * lexicographically greater than the end date alone. The report would look entirely plausible,
     * would satisfy any test written under the same misunderstanding, and would silently drop a day's
     * transactions. In the other direction the two forms are provably equivalent: for a stored value
     * whose ten-character prefix is P and a ten-character literal L, the value is greater than or
     * equal to L exactly when P is - if P exceeds L then so does the value; if P equals L then the
     * value extends L and is greater; and if P is below L then so is the value. Leaving the lower
     * bound bare therefore costs nothing semantically and keeps it index-searchable, so the range
     * scan on {@code idx_transaction_tran_proc_ts} survives. Wrapping it would defeat that index for
     * no gain.
     *
     * <h2>The upper side carries a redundant pre-bound so the index constrains both ends</h2>
     *
     * <p>The consequence of that asymmetry is that the authoritative upper predicate is wrapped in a
     * function of the column, and a predicate over a function of a column cannot serve as a bound for
     * an index built on the column itself. Left at that, the index would be entered at the start date
     * and then read to the end of the table, with every row beyond the end date fetched and discarded
     * and the whole remainder sorted. The query therefore carries a <em>third</em> predicate,
     * {@code t.tranProcTs <= CONCAT(:endDate, } sixteen nines{@code )}, whose right-hand side mentions
     * no column and so is evaluated once and used as the index's upper bound.
     *
     * <p><strong>It is a pre-bound and never the authority.</strong> The ten-character prefix
     * comparison stays in the same {@code WHERE} clause and is what decides membership; the pre-bound
     * only has to be wide enough never to exclude a row the authority keeps. That property is provable
     * from the record layout rather than assumed, and it holds under both a byte-ordered and a
     * language-aware collation:
     *
     * <ul>
     *   <li>A populated processing timestamp is a ten-character date, then a separating space, then a
     *       time of day - so its eleventh character is a space. An unprocessed transaction carries 26
     *       spaces. Under byte ordering the comparison against the pre-bound is decided at the first
     *       character where the two differ: if the ten-character prefixes differ, the prefix decides
     *       it and the row is below the pre-bound exactly when it is below the end date; if the
     *       prefixes are equal, the eleventh character decides it, and a space is below the digit
     *       nine. So every row the authority keeps satisfies the pre-bound.</li>
     *   <li>Under a language-aware collation, which weighs letters and digits ahead of spaces and
     *       punctuation, the pre-bound contributes the date's digits followed by sixteen nines while a
     *       stored value contributes the same date's digits followed by the time's digits, of which
     *       the first is the tens digit of an hour and so at most two. Nine outranks it, and a blank
     *       timestamp contributes no digits at all. The pre-bound is again the greater value.</li>
     * </ul>
     *
     * <p>The invariant those two arguments rest on is the record layout's own: <strong>a stored
     * processing timestamp is either blank throughout or carries a space in its eleventh
     * character.</strong> Every writer must preserve it. Widening the filler, or replacing it with a
     * character that a language-aware collation ignores, would break the second argument; shortening
     * it below the sixteen characters that follow the date prefix would break the first.
     *
     * <p><strong>This must remain a character comparison.</strong> It is never converted to
     * {@code LocalDate}, {@code LocalDateTime}, {@code Instant}, a database date or date-time type, or
     * a type-conversion expression inside the query, because the legacy comparison is a character
     * comparison and the module pins the JDBC time zone specifically so that no implicit temporal
     * conversion happens anywhere. The prefix window is fixed at the first ten characters; neither its
     * start nor its length may change, and it stays on the upper bound.
     *
     * <h2>An unprocessed transaction is excluded by the lower bound</h2>
     *
     * <p>A transaction that has not been processed carries 26 spaces in that column, whose
     * ten-character prefix is ten spaces. The space character sorts below every digit in the character
     * collating sequence the legacy sort used, so such a row falls below any date literal beginning
     * with a digit and is excluded by the lower bound alone. That is the legacy behaviour exactly, so
     * <strong>no null test and no blank test is added</strong> - the column is declared not-null and
     * the comparison already handles the case. A guard would be unrequested behaviour.
     *
     * <h2>One ascending ordering serves both legacy jobs</h2>
     *
     * <p>The report procedure types the card-number bytes as zoned decimal, while the statement job
     * {@code app/jcl/CREASTMT.JCL} sorts the very same bytes typed as character. Nothing reconciles
     * the two, and nothing needs to: for a zero-padded unsigned sixteen-digit value with no sign
     * overpunch, zoned-decimal ascending order and character ascending order are identical. A single
     * ascending ordering on the card-number attribute is therefore faithful to both, which is why no
     * separate zoned-decimal comparator exists.
     *
     * <p><strong>That equivalence is a precondition on the stored data, and it is stated here so it is
     * not mistaken for a property of the column.</strong> It holds only while every stored card number
     * is exactly sixteen digit characters, zero-padded, unsigned and without a sign overpunch - which
     * is what the 16-byte card-number field of the record layout carries and what every mapper in this
     * module writes. Storing a shorter, space-padded, signed or non-numeric card number would make
     * character order diverge from zoned-decimal order and would silently reorder the report, without
     * breaking compilation and without failing any test that did not look for it. It is the same
     * precondition {@link #findMaxId()} depends on for the identifier, and every writer must preserve
     * both.
     *
     * <p>The rows returned here are the report's input, nothing more. The report line itself is
     * assembled at the fixed 133-character width by the formatter in the utility layer, and the report
     * program {@code app/cbl/CBTRN03C.cbl} contains no arithmetic statement at all, so this method
     * introduces no arithmetic and no total of its own. The selection filters on the date range only:
     * no card-number, type, category or amount predicate belongs here, because the legacy selection
     * had none.
     *
     * <p>The explicitly declared query text takes precedence over derivation from the method name, so
     * the name is documentation rather than a derivation instruction. That is deliberate and
     * well-defined; it does not need "fixing" into a derivable form, which could not express a range
     * predicate over a prefix in any case.
     *
     * @param startDate the inclusive lower bound of the range, a ten-character date in the form the
     *                  legacy parameters use; matched exactly as supplied and never trimmed or
     *                  reformatted
     * @param endDate   the inclusive upper bound of the range, likewise a ten-character date; a
     *                  transaction processed on this date is returned
     * @param pageable  the reader's window over the range - page number and page size, supplied by the
     *                  caller and never defaulted here; the ordering is fixed by this method and a
     *                  sort carried on the pageable can only refine it
     * @return the requested slice of matching transactions in ascending card-number order, reporting
     *         whether a further slice follows; possibly empty, never {@code null}
     */
    @Query("""
            SELECT t
            FROM Transaction t
            WHERE t.tranProcTs >= :startDate
              AND t.tranProcTs <= CONCAT(:endDate, '9999999999999999')
              AND SUBSTRING(t.tranProcTs, 1, 10) <= :endDate
            ORDER BY t.tranCardNum ASC, t.tranId ASC
            """)
    Slice<Transaction> findByProcessingDateRange(@Param("startDate") String startDate,
                                                 @Param("endDate") String endDate,
                                                 Pageable pageable);
}
