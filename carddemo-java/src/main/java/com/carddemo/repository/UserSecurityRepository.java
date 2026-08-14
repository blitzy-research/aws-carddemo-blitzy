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

import java.util.List;
import java.util.Optional;

import com.carddemo.domain.UserSecurity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Persistence gateway for the {@code user_security} table - the relational form of the 80-byte
 * {@code SEC-USER-DATA} record of copybook {@code CSUSR01Y}, keyed on the 8-character user identifier
 * the record carries at offset 0. The key type is {@link String} because the seeded identifiers mix
 * letters and digits; as everywhere else in this module the JPA identifier is the business key and no
 * surrogate exists.
 *
 * <h2>The declared surface is the whole surface</h2>
 *
 * <p>This interface extends the bare {@link Repository} marker rather than {@code JpaRepository}, so the
 * only methods on it are the nine written below. That is a least-privilege decision about a table holding
 * credentials: {@code JpaRepository} would hand every injector an unbounded {@code findAll()} that
 * hydrates every stored digest, bulk and flush-forcing writes over a table the legacy tier rewrote one
 * record at a time, several ways to remove every sign-on identity in one statement, a lazy
 * {@code getReferenceById} proxy, and a query-by-example surface over the digest-bearing entity. None is
 * used anywhere in the module, and an interface is a capability grant, so the grant is written out rather
 * than inherited: what is not declared below cannot be called and cannot be reached by a future edit that
 * "just uses what is there".
 *
 * <h2>Two keyed reads, and the difference between them is the record hold</h2>
 *
 * <p>{@link #findById(String)} serves sign-on, which reads and writes nothing. The administrative update
 * and delete transactions instead issue a read-for-update, which holds the record exclusively until the
 * rewrite or delete that follows in the same unit of work - the legacy delete verb carries no record
 * identifier at all and can only mean "the record this task holds".
 * {@link #findByIdForUpdate(String)} is that read, and it is a separate method rather than a flag because
 * a lock mode is a property of the statement and because sign-on must never take a write lock on the row
 * it authenticates against.
 *
 * <h2>The credential column is one of the schema's three deliberate width divergences</h2>
 *
 * <p>The legacy field is eight bytes wide; {@code sec_usr_pwd} is {@code VARCHAR(60)}, sized for a BCrypt
 * digest. Three columns across the eleven tables deliberately exceed their legacy width - this one,
 * {@code customer.cust_ssn} and {@code customer.govt_issued_id}, the latter two widened to hold a sealed
 * envelope rather than cleartext - and this is the only one widened for hashing rather than for
 * protection at rest. It must not be narrowed back, which would truncate and destroy every stored digest,
 * and it must not be widened further. The divergence exists only because a binding requirement demands
 * it, and it is recorded in {@code docs/decision-log.md} rather than silently applied.
 *
 * <h2>A documented parity exception, and an absolute prohibition</h2>
 *
 * <p>Legacy sign-on reads the record by key and then compares the stored credential to the entered value
 * <strong>as cleartext, for direct equality</strong>. Reproducing that comparison would satisfy
 * behavioural parity and would violate the binding constraint that no credential may be hardcoded, so
 * this is the one place in the migration where the credential requirement <em>overrides</em> parity. The
 * resolution is BCrypt hashing, recorded as a deliberate, labelled parity exception in which the security
 * posture is intentionally improved rather than mirrored. Everywhere else faithful beats idiomatic; a
 * tie-break rule with no named exception would be either dishonest or unusable, and this is the named
 * exception.
 *
 * <p><strong>The prohibition that follows is absolute.</strong> The shared cleartext credential value
 * carried by the legacy provisioning job must never appear anywhere in this module - not in Java, not in
 * configuration or a profile overlay or an environment default, not in a log line or an exception
 * message, not in documentation or a comment, and not in a test fixture, constant or assertion message.
 * It is not stated here and must not be introduced anywhere by a later change; a test needing to
 * demonstrate digest verification constructs its own throwaway value at run time.
 *
 * <h2>No finder may take a credential</h2>
 *
 * <p>The attribute this table exposes is the 60-character BCrypt digest and never a cleartext value, and
 * it must never be renamed or re-typed to suggest otherwise. No finder here takes a credential, hashed or
 * not, and that is a mechanical impossibility rather than a preference: a salted digest differs on every
 * encoding of the same input, so an equality predicate over it can never match and a query that appeared
 * to authenticate would silently reject every valid request. Read-then-verify is both the faithful shape
 * and the only workable one.
 *
 * <p>Hashing, verifying, comparing, masking and redacting all live in the authentication service and the
 * security configuration. This interface declares no encoder, imports nothing from the security framework
 * and neither extends nor implements any of its contracts. Nothing about this table is logged from this
 * package at any level - a user identifier, a stored digest and a submitted credential are all material
 * that must not reach an appender - so no logger is declared here and none should be added.
 *
 * <h2>Sign-on outcomes are messages, and the service produces them</h2>
 *
 * <p>The legacy sign-on program emits seven distinct externally observable message texts, verified
 * character for character as an interface contract, and they are produced by the service. This interface
 * declares no message, no exception type and no status enum; the repository-level expression of "user not
 * found" is the empty {@code Optional} returned by {@link #findById(String)}, which the service maps to
 * the user-not-found text. The two-level file status model of the legacy tier likewise belongs above this
 * layer.
 *
 * <h2>The role split is an unconditional alternative, so persistence must not judge the value</h2>
 *
 * <p>On a successful read the legacy program tests <em>only</em> the administrative type and reaches the
 * main menu through an unconditional alternative. There is no third branch and no validation of the value
 * at all, so every non-administrative code - including one the estate never declared - routes to the main
 * menu without raising anything. That is why {@code sec_usr_type} is a raw {@link String} with no check
 * constraint, no enumerated mapping, no attribute converter and no validation annotation: a value outside
 * the administrative and standard pair must load rather than fail at persistence, and adding a constraint
 * here would reject data the legacy system silently accepted and routed. The module does model the two
 * codes as a domain enumeration, but that type serves the service layer and is deliberately not
 * referenced from this package.
 *
 * <h2>The administrative browse is a projected keyset read</h2>
 *
 * <p>The administrative user list presents a page of 10 rows, proven from the legacy screen table
 * declared as occurring 10 times. The opening page may use {@link #findAllProjectedBy(Pageable)} at
 * window zero; every continuation uses the strict greater-than or less-than projected method and asks for
 * the screen width plus the source's one-record probe.
 * {@link #findProjectedBySecUsrId(String)} supplies the inclusive boundary without hydrating a credential,
 * and {@link #countBySecUsrIdLessThan(String)} reconstructs the private page counter with one range
 * aggregate rather than an offset rescan.
 *
 * <p>The projection is what makes the list safe rather than merely tidy. The legacy screen shows an
 * identifier, a first name, a last name and a type and has never shown a credential; returning the entity
 * would load one digest per row to render four columns, and every such instance is a candidate for an
 * accidental rendering. A closed projection makes the digest absent rather than merely unused, which is a
 * stronger statement than any convention about not calling an accessor.
 *
 * <h2>Writes, locking and the access paths that exist</h2>
 *
 * <p>The administrative add and update transactions both store a credential and both hash on write in the
 * user-management service, never here. Add uses the module's explicit create-only writer so a duplicate
 * key cannot become a merge; {@link #save(UserSecurity)} serves the rewrite-in-place update only. There is
 * no bulk update or upsert, and {@link #deleteById(String)} removes the one row it is given.
 *
 * <p>The entity declares no version attribute - the account and card entities are the only two versioned
 * entities in the module, because they are the only two whose legacy programs compared a before image
 * against an after image - and no association to any other entity, so deferred loading outside a
 * transaction is impossible by construction and no entity graph or fetch join is needed or permitted. The
 * index migration creates no foreign key and no secondary index touching this table, so every declared
 * finder stays on the primary key: exact, strict ascending, strict descending or count-below. A finder
 * over any other attribute would describe an access path the schema does not support.
 *
 * <p>This interface performs no trimming, padding or case folding. The columns are bounded
 * {@code VARCHAR(n)} rather than {@code CHAR(n)} precisely so the store neither blank-pads on read nor
 * trims on write, and whatever a caller supplies round-trips unchanged. Restoring a value to its fixed
 * record width, and every other piece of fixed-width layout knowledge, belongs to the utility layer's
 * mappers.
 *
 * <h2>Provisioning and seeded volume</h2>
 *
 * <p>The provisioning job stages ten user records supplied <strong>in stream as ASCII card images</strong>
 * - five administrative and five standard - into a physical sequential dataset at
 * {@code LRECL=80 RECFM=FB DSORG=PS}, and only then defines the indexed cluster with {@code KEYS(8,0)}
 * and {@code RECORDSIZE(80,80)} and reproduces the sequential dataset into it. Two corroborations fall
 * out: a single-part business key of width 8 at offset 0, and the 80-byte record width the copybook sums
 * to. Because the seed content originates in stream in ASCII, <strong>no EBCDIC decode is required</strong>
 * to recover it - which is why it costs nothing that the corresponding EBCDIC sequential dataset is the
 * one such dataset in the estate with no ASCII twin. The CICS resource definition does register this
 * file, so the legacy system reads it online during sign-on; it is not a batch-only dataset.
 *
 * <p>The table starts empty: the reference-data migration seeds zero rows here and asserts that fact,
 * exactly as it does for the transaction table. The ten identities arrive in
 * {@code V4__seed_user_security.sql}, whose credentials are stored only as independently salted BCrypt
 * digests of exactly 60 characters, all ten distinct, a property the migration itself verifies. That
 * migration reaches local and test execution only, because the shared and production configurations pin
 * the migration target below its version, so a production deployment migrates schema and indexes and can
 * never inherit a seeded login. Anything asserting against these rows must run with the seed applied.
 *
 * @see UserSecurity
 */
public interface UserSecurityRepository extends Repository<UserSecurity, String> {

    /**
     * Reads one sign-on identity by its eight-character identifier.
     *
     * <p>The one method that returns the entity, and therefore the one that brings a BCrypt digest into
     * memory. Two callers need that and no others do: sign-on, which verifies a presented credential
     * against the stored digest, and the administrative update transaction, which reads the row it is
     * about to rewrite exactly as the legacy program does.
     *
     * @param secUsrId the eight-character sign-on identifier, which is the primary key
     * @return the identity, or {@link Optional#empty()} when no row carries that identifier
     */
    Optional<UserSecurity> findById(String secUsrId);

    /**
     * Reads one sign-on identity by its identifier and holds the row for the write that follows.
     *
     * <p>The relational form of the read for update that both administrative maintenance
     * transactions issue: {@code app/cbl/COUSR02C.cbl} L322-L331 before its rewrite at
     * L360, and {@code app/cbl/COUSR03C.cbl} L269-L278 before its delete at L307. In the region that
     * read takes an exclusive hold on the record and the rewrite or delete happens while the hold is
     * still in place, which is what makes the pair one indivisible maintenance step. The delete verb is
     * the proof: it names no record identifier, so the only record it can remove is the one the task is
     * holding.
     *
     * <p><strong>A plain read followed by a write in a later unit is not the same operation.</strong>
     * Between the two, another administrator can change or remove the same identity, and the second
     * write would then overwrite or delete a row that no longer holds what the operator was shown -
     * silently, because nothing in either statement would notice. The row carries no version attribute
     * and one is deliberately not added: the legacy prevented the interleaving with a lock rather than
     * detecting it after the fact, and reproducing the lock keeps the observable behaviour identical
     * instead of introducing a conflict outcome the legacy screens have no message for.
     *
     * <p>The lock is a write lock, so it also excludes a concurrent holder of the same row rather than
     * only concurrent writers, and it is released when the unit of work that took it ends. The caller
     * must therefore already be inside one; a call outside a transaction is a configuration error that
     * the persistence provider reports rather than silently downgrading the lock.
     *
     * <p>The query is written out rather than derived because the lock mode, not a predicate, is what
     * distinguishes it from the read above, and two derived methods differing only in an annotation
     * would be indistinguishable at a call site.
     *
     * @param secUsrId the eight-character sign-on identifier, which is the primary key
     * @return the identity, or {@link Optional#empty()} when no row carries that identifier
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserSecurity u WHERE u.secUsrId = :secUsrId")
    Optional<UserSecurity> findByIdForUpdate(@Param("secUsrId") String secUsrId);

    /**
     * Reads a page of sign-on identities as projections that carry no credential.
     *
     * <p>The criteria-less derived form is spelled {@code findAllProjectedBy} because the return type
     * rather than a predicate is what varies: there is no criterion, and the projection is the point.
     * The generated select names the four projected columns alone, so {@code sec_usr_pwd} is not read
     * and no digest exists in the returned objects to be rendered, logged or serialized by accident.
     *
     * <p>Ordering and page size come entirely from the argument. The administrative browse pages 10 rows
     * at a time and fills backwards as well as forwards, so imposing either here would break one of the
     * two directions.
     *
     * <p><strong>&#9733; A slice, and deliberately not a page.</strong> A page carries a total row count,
     * which the server produces with a second aggregate over the whole table. Nothing reads it: the legacy
     * screen computes no row number, displays no total and has no field to display one in, and the caller
     * takes the content and discards the rest. The window itself is what the caller needs and a slice is
     * exactly that window, so the count is not requested rather than requested and thrown away. Recorded
     * as {@code DL-296} in {@code docs/decision-log.md}.
     *
     * <p><strong>This serves the opening page of a browse only.</strong> The legacy screen computes no
     * row number: it retains the first and last identifier it displayed - two eight-character commarea
     * fields - and repositions on one of them, so every page after the first is a keyset read through
     * {@link #findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(String, Limit)} or
     * {@link #findBySecUsrIdLessThanOrderBySecUsrIdDesc(String, Limit)}. Paging deeper through this
     * method would ask the database to count and discard every earlier row on each turn, and would let a
     * row added or removed between two turns shift the window so that an identity is listed twice or
     * skipped. At page zero there is nothing to discard, which is why the opening page legitimately
     * arrives here.
     *
     * @param pageable the window, size and sort the caller requires; never {@code null}
     * @return one window of projections, empty when the window lies beyond the last row
     */
    Slice<AdminEntry> findAllProjectedBy(Pageable pageable);

    /**
     * Reads one sign-on identity as the credential-free list projection.
     *
     * <p>The list browse needs an inclusive opening position. Reading the entity through
     * {@link #findById(String)} would load the stored BCrypt digest merely to establish whether the
     * boundary row exists, so this projected form supplies the same primary-key seek without selecting
     * the credential column.
     *
     * @param secUsrId the exact eight-character identifier
     * @return the projected row, or empty when that key is absent
     */
    Optional<AdminEntry> findProjectedBySecUsrId(String secUsrId);

    /**
     * Reads the sign-on identities that follow a boundary identifier, in ascending identifier order,
     * limited to the number of rows the caller asks for, as projections that carry no credential.
     *
     * <p>The forward half of the administrative keyset browse. The cursor is the identifier of the last
     * row the previous page displayed, and the comparison is strict so that row is not listed twice. The
     * identifier is the primary key and therefore unique, which makes the single-column cursor total.
     *
     * <p><strong>Ask for one row more than the screen holds.</strong> The legacy program discovers that a
     * further page exists by attempting one more read rather than by counting, so requesting eleven rows
     * for a ten-row screen reproduces that exactly and the eleventh row is discarded once it has answered
     * the question.
     *
     * <p>The return type is the same closed projection the paged form returns, so the credential column is
     * not selected on this path either. That is the reason the keyset methods are declared here rather
     * than being left to a caller composing a specification: a specification would have returned entities.
     *
     * @param secUsrId the exclusive lower bound - the last identifier already displayed - matched exactly
     *                 as supplied and never trimmed or folded
     * @param limit    the maximum number of rows to read, which the caller sets to the screen's row count
     *                 plus one
     * @return the matching projections in ascending identifier order, at most {@code limit} of them,
     *         possibly empty and never {@code null}
     */
    List<AdminEntry> findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(String secUsrId, Limit limit);

    /**
     * Reads the sign-on identities that precede a boundary identifier, in descending identifier order,
     * limited to the number of rows the caller asks for, as projections that carry no credential.
     *
     * <p>The backward half of the administrative keyset browse. The cursor is the identifier of the first
     * row the previous page displayed, and the comparison is strict so that row is not repeated.
     *
     * <p><strong>Descending is the read order and not the presentation order.</strong> The legacy backward
     * path fills its bottom screen slot first and works upward, so the page the operator sees ascends
     * exactly like a forward page; the calling service reverses these rows before building the response.
     *
     * @param secUsrId the exclusive upper bound - the first identifier already displayed - matched exactly
     *                 as supplied and never trimmed or folded
     * @param limit    the maximum number of rows to read, which the caller sets to the screen's row count
     *                 plus one
     * @return the matching projections in descending identifier order, at most {@code limit} of them,
     *         possibly empty and never {@code null}
     */
    List<AdminEntry> findBySecUsrIdLessThanOrderBySecUsrIdDesc(String secUsrId, Limit limit);

    /**
     * Counts the identities preceding one key.
     *
     * <p>The legacy carries a page counter in its private communication area. The REST contract does
     * not trust a client-supplied page number, so the service reconstructs that counter from the
     * primary-key position with one range count instead of rescanning offset pages from page zero.
     * The query selects no entity and therefore no credential.
     *
     * @param secUsrId the positioned key, excluded from the count
     * @return how many identities precede the key
     */
    long countBySecUsrIdLessThan(String secUsrId);

    /**
     * Rewrites one existing sign-on identity in place.
     *
     * <p>Create uses the module's explicit insert primitive so a duplicate natural key is refused
     * rather than merged. The credential this receives is already a BCrypt digest: hashing happens in
     * the user-management service, never here, and the entity itself refuses a value that is not a
     * digest of the expected form.
     *
     * @param identity the identity to store; never {@code null}
     * @return the stored instance, which the persistence provider may substitute for the argument
     */
    UserSecurity save(UserSecurity identity);

    /**
     * Removes the one sign-on identity carrying an identifier.
     *
     * <p>Removes at most one row and has no bulk counterpart on this interface. The administrative
     * delete transaction reads the row and shows it to the operator before calling this, so an absent
     * identifier is already reported by then rather than being discovered here.
     *
     * @param secUsrId the eight-character sign-on identifier, which is the primary key
     */
    void deleteById(String secUsrId);

    /**
     * The four non-credential columns of a sign-on identity, as the administrative list needs them.
     *
     * <p>A <strong>closed</strong> projection: every accessor names a persistent attribute directly, so
     * the persistence provider selects exactly those four columns and nothing more. It is nested inside
     * the repository because it is part of this interface's contract rather than a domain type or a
     * transport type - it depends on nothing, and it keeps the layer rule that this package imports only
     * the domain package.
     *
     * <p><strong>There is no credential accessor and one cannot be added by convention.</strong> A
     * projection accessor resolves by bean-property name, and the entity deliberately exposes its digest
     * as {@code credentialDigest()} rather than as {@code getSecUsrPwd()}, so no accessor spelled the
     * conventional way resolves to it. Declaring {@code getSecUsrPwd()} here would fail to bind rather
     * than quietly widen the select, which is the failure direction to prefer.
     *
     * <p>The four accessors mirror the four fields the legacy screen displays, in the order the record
     * declares them: the identifier at offset 0, the given name at offset 8, the family name at offset
     * 28, and the role code at offset 56.
     */
    interface AdminEntry {

        /**
         * Returns the eight-character sign-on identifier; record offset 0, width 8.
         *
         * @return the identifier, never {@code null} for a persisted row
         */
        String getSecUsrId();

        /**
         * Returns the given name; record offset 8, width 20.
         *
         * @return the given name, never {@code null} for a persisted row
         */
        String getSecUsrFname();

        /**
         * Returns the family name; record offset 28, width 20.
         *
         * @return the family name, never {@code null} for a persisted row
         */
        String getSecUsrLname();

        /**
         * Returns the single-character role code; record offset 56, width 1.
         *
         * <p>Returned raw and unjudged, because the legacy program tests only the administrative code and
         * routes every other value - including one the estate never declared - through an unconditional
         * alternative. Interpreting it belongs to the service layer.
         *
         * @return the role code, never {@code null} for a persisted row
         */
        String getSecUsrType();
    }
}
