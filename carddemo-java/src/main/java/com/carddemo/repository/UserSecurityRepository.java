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

import java.util.Optional;

import com.carddemo.domain.UserSecurity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Persistence gateway for the {@code user_security} table - the relational form of the 80-byte
 * {@code SEC-USER-DATA} record of copybook {@code CSUSR01Y}, keyed on the 8-character user
 * identifier the record carries at offset 0.
 *
 * <p><strong>This interface declares exactly the four operations the module performs, and inherits
 * nothing else.</strong> It extends the bare {@link Repository} marker rather than
 * {@code JpaRepository}, so the only methods that exist on it are the four written below. That is a
 * least-privilege decision about a table holding credentials, and it is worth stating what extending
 * {@code JpaRepository} would have handed to every injector instead:
 *
 * <ul>
 *   <li>{@code findAll()} and {@code findAll(Sort)} - an <strong>unbounded</strong> read of every
 *       sign-on identity, each one a fully hydrated entity carrying its BCrypt digest. One call, every
 *       credential in the system in memory, and nothing in the signature to suggest it.</li>
 *   <li>{@code saveAll}, {@code saveAllAndFlush}, {@code saveAndFlush}, {@code flush} - bulk and
 *       flush-forcing writes over a table the legacy tier only ever rewrote one record at a time.</li>
 *   <li>{@code deleteAll}, {@code deleteAllInBatch}, {@code deleteAllById}, {@code deleteAllByIdInBatch},
 *       {@code delete}, {@code deleteInBatch} - a set of ways to remove <strong>every sign-on identity
 *       in one statement</strong>, including two that bypass the persistence context entirely. The
 *       legacy delete transaction removes one row that it has just read.</li>
 *   <li>{@code getReferenceById} - a lazy proxy whose dereference outside a transaction is a failure
 *       mode this entity cannot otherwise reach.</li>
 *   <li>{@code findAll(Example)} and the whole query-by-example family - a query surface over an entity
 *       whose attributes include the digest.</li>
 * </ul>
 *
 * <p>None of those is used anywhere in the module, and every one of them is reachable from any bean
 * that declares this type as a constructor parameter. An interface is a capability grant, so the grant
 * is written out rather than inherited: what is not declared below cannot be called, cannot be reached
 * by a future edit that "just uses what is there", and cannot appear in a stack trace.
 *
 * <p><strong>The administrative browse returns a projection, not the entity.</strong>
 * {@link #findAllProjectedBy(Pageable)} yields {@link AdminEntry}, a closed projection over the four
 * non-credential columns, so the digest column is <strong>not named in the generated select</strong>
 * and no digest is hydrated to serve a list of users. The keyed read still returns the entity, because
 * sign-on has to verify a credential and the administrative update has to rewrite a row it read; those
 * are the two places a digest is legitimately in memory, and they are now the only two.
 *
 * <p>The sections below record why each additional finder a reader might reach for is either
 * unnecessary or actively forbidden.
 *
 * <p>{@code sec_usr_pwd} is {@code VARCHAR(60)} because it stores a BCrypt digest rather than the
 * legacy 8-byte cleartext field. It is one of three columns in the schema whose width deliberately
 * exceeds its legacy field - {@code customer.cust_ssn} and {@code customer.govt_issued_id} are the
 * other two, widened for protection at rest - and the only one taken to satisfy the
 * no-hardcoded-credential constraint in preference to behavioural parity; see
 * {@code docs/decision-log.md}. It must not be narrowed, which would truncate stored digests.
 *
 * <p>No finder may take a credential, hashed or otherwise: a salted digest differs on every
 * encoding of the same input, so an equality predicate over it could never match, and legacy
 * sign-on reads by key first and only then compares. Hashing, verification and redaction live in
 * the service and security-configuration layers, and nothing about this table is logged here.
 *
 * <p>{@code sec_usr_type} is an unconstrained {@link String} on purpose. Legacy sign-on tests only
 * the administrative code and routes every other value to the main menu through an unconditional
 * alternative, so a check constraint, converter or validation annotation here would reject data the
 * legacy system accepted and routed.
 *
 * <p><strong>How the dataset is provisioned, and why no character-set decode was ever needed.</strong>
 * The provisioning job {@code app/jcl/DUSRSECJ.jcl} is unlike the jobs behind the module's other
 * tables. It first discards any prior copy of the dataset, then runs a generic copy utility over ten
 * user records supplied <strong>in stream as ASCII card images</strong> - five of the administrative
 * type and five of the standard type - writing them to a <strong>physical sequential dataset</strong>
 * at {@code LRECL=80 RECFM=FB DSORG=PS}; only afterwards does it define the indexed cluster with
 * {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)} and reproduce the sequential dataset into it. Two
 * corroborations of this mapping fall out of that: the key definition independently confirms a
 * single-part business key of width 8 at offset 0, and the record size independently confirms the
 * 80-byte width the copybook sums to. Because the seed content originates in stream in ASCII rather
 * than in the mainframe encoding, <strong>no EBCDIC decode is required</strong> to recover it - which
 * is precisely why it costs nothing that the corresponding EBCDIC sequential dataset is the one such
 * dataset in the estate with no ASCII twin. The CICS resource definition {@code app/csd/CARDDEMO.CSD}
 * <strong>does</strong> register this file, so the legacy system reads it online during sign-on; it is
 * not a batch-only dataset.
 *
 * <p>A note for the decision log, because the distinction is easy to get wrong: this dataset is
 * provisioned by <em>both</em> a sequential-staging step and a cluster definition, and it is the
 * in-stream ASCII staging step - not the absence of a cluster definition - that makes it unique among
 * the module's tables and that removes the decoding problem.
 *
 * <h2>The business key is the identifier itself</h2>
 *
 * <p>The key type is {@link String} because the legacy key is the 8-character alphanumeric user
 * identifier stored at offset 0, mapped to {@code VARCHAR(8)}, and the seeded identifiers mix letters
 * and digits, so no numeric type is even conceivable. As everywhere else in this module, the JPA
 * identifier is the business key and <strong>no surrogate key exists</strong> - nothing is generated,
 * sequenced or synthesised, so a row's identity in the table is the same identity the legacy record
 * had.
 *
 * <h2>The credential column is one of the schema's three deliberate width divergences</h2>
 *
 * <p>The legacy field is eight bytes wide. The column {@code sec_usr_pwd} is {@code VARCHAR(60)},
 * sized for a BCrypt digest. Three columns across the eleven tables deliberately exceed their legacy
 * field width - this one, {@code customer.cust_ssn} and {@code customer.govt_issued_id}, the latter two
 * widened to hold a sealed envelope rather than cleartext - and this is the <strong>only one widened
 * for hashing rather than for protection at rest</strong>; every other column preserves its legacy
 * width exactly. It must not be narrowed back to the legacy width, which would truncate and destroy
 * every stored digest, and it must not be widened further. The divergence exists only because a binding requirement demands it,
 * and it is recorded in {@code docs/decision-log.md} rather than silently applied.
 *
 * <h2>A documented parity exception, and an absolute prohibition</h2>
 *
 * <p>Legacy sign-on reads the record by key and then compares the stored credential to the entered
 * value <strong>as cleartext, for direct equality</strong>. Reproducing that comparison would satisfy
 * behavioral parity and would violate the binding constraint that no credential may be hardcoded, so
 * this is the one place in the migration where the credential requirement <em>overrides</em> parity.
 * The resolution is BCrypt hashing, and it is the flagship entry in {@code docs/decision-log.md}: a
 * deliberate, labelled parity exception in which the security posture is intentionally improved rather
 * than mirrored. Everywhere else in this migration faithful beats idiomatic; a tie-break rule with no
 * named exception would be either dishonest or unusable, and this is the named exception.
 *
 * <p><strong>The prohibition that follows is absolute.</strong> The shared cleartext credential value
 * carried by the legacy provisioning job must never appear anywhere in this module - not in Java, not
 * in configuration or a profile overlay or an environment default, not in a log line or an exception
 * message, not in documentation or a comment, and not in a test fixture, test constant or assertion
 * message. It is not stated here, and it must not be introduced anywhere by a later change. Any test
 * that needs to demonstrate digest verification constructs its own throwaway value at run time.
 *
 * <h2>No finder may take a credential</h2>
 *
 * <p>The attribute this table exposes is the <strong>60-character BCrypt digest</strong> and never a
 * cleartext value, and the attribute must never be renamed or re-typed to suggest otherwise.
 * Consequently <strong>no finder on this interface takes a credential</strong>, hashed or not. That is
 * a mechanical impossibility rather than a stylistic preference: a salted digest differs on every
 * encoding of the same input, so an equality predicate over it can never match, and a query that
 * appeared to authenticate would silently reject every valid request. It would not reproduce the legacy
 * flow either, which reads by key first and only then compares. Read-then-verify is therefore both the
 * faithful shape and the only workable one.
 *
 * <p>Hashing, verifying, comparing, masking and redacting all live outside this package, in the
 * authentication service and the security configuration. This interface performs none of them, declares
 * no encoder, imports nothing from the security framework, and neither extends nor implements any of its
 * contracts - the adapter that presents these rows as an authenticated principal belongs to the
 * configuration and service layers, not here.
 *
 * <p>Nothing about this table is logged from this package, at any level. A user identifier, a stored
 * digest and a submitted credential are all material that must not reach an appender, so no logger is
 * declared here and none should ever be added.
 *
 * <h2>Sign-on outcomes are messages, and the service produces them</h2>
 *
 * <p>The legacy sign-on program emits seven distinct externally observable message texts - two entry
 * prompts, a wrong-credential error, a user-not-found error, an unable-to-verify error, and two common
 * messages on the exit key and on an unmapped key - and those texts are verified character for
 * character as an interface contract. They are produced by the service, not here. This interface
 * declares <strong>no message, no exception type and no status enum</strong>; the repository-level
 * expression of "user not found" is simply the <strong>empty {@code Optional}</strong> returned by the
 * inherited {@code findById}, which the service maps to the user-not-found text. The two-level file
 * status model of the legacy tier - a raw status normalised into an OK, end-of-file or error outcome
 * before anything branches on it - likewise belongs to the service and batch layers.
 *
 * <h2>The role split is an unconditional alternative, so persistence must not judge the value</h2>
 *
 * <p>On a successful read the legacy program tests <em>only</em> the administrative type and reaches
 * the main menu through an <strong>unconditional alternative</strong>. There is no third branch and no
 * validation of the value at all, so every non-administrative code - including one the estate never
 * declared - routes to the main menu without raising anything.
 *
 * <p>That is why {@code sec_usr_type} is a raw {@link String} with <strong>no check constraint, no
 * enumerated mapping, no attribute converter and no validation annotation</strong>. A value outside the
 * administrative and standard pair <strong>must load rather than fail at persistence</strong>, so that
 * the service's unconditional alternative can handle it exactly as the legacy program does. Adding a
 * constraint here would reject data the legacy system silently accepted and routed, which is a
 * behavioral regression dressed as rigour. The module does model the two codes as a domain enumeration,
 * but that type belongs to the service layer's use and is deliberately not referenced from this
 * package, which depends on the domain package alone.
 *
 * <h2>The administrative browse is a paged read over a projection</h2>
 *
 * <p>The administrative user list presents a page of 10 rows, proven from the legacy screen table
 * declared as occurring 10 times rather than inferred. The Java counterpart is
 * {@link #findAllProjectedBy(Pageable)}, which returns {@link AdminEntry} rather than the entity. The
 * page size is supplied by the caller through the {@code Pageable} and does not appear here as a
 * constant or a default; and this interface imposes <strong>no ordering of its own</strong>, so the
 * service can request either direction and reproduce the legacy browse fill order, which fills
 * backwards as well as forwards.
 *
 * <p>The projection is what makes the list safe rather than merely tidy. The legacy screen shows an
 * identifier, a first name, a last name and a type; it has never shown a credential. Returning the
 * entity would nonetheless load one digest per row into memory, ten at a time, to render four columns -
 * and every one of those instances is then a candidate for an accidental rendering. A closed projection
 * makes the digest absent rather than merely unused, which is a stronger statement than any convention
 * about not calling an accessor.
 *
 * <h2>Writes go through one save, one record at a time</h2>
 *
 * <p>The administrative add and update transactions both store a credential, and both <strong>hash on
 * write in the user-management service</strong> - never here. {@link #save(UserSecurity)} is the only
 * write path and covers add and update alike through the merge semantics of the persistence provider,
 * which is exactly the record-at-a-time, rewrite-in-place behavior of the legacy tier. There is
 * therefore <strong>no bulk update, no upsert method and no modifying statement</strong> on this
 * interface, and the delete transaction is served by {@link #deleteById(String)}, which removes the one
 * row it is given and has no counterpart that removes more.
 *
 * <h2>No version attribute, no association, and no index for a finder to serve</h2>
 *
 * <p>The entity declares <strong>no version attribute</strong> - the account and card entities are the
 * only two versioned entities in the module, because they are the only two whose legacy programs
 * compared a before image against an after image - and it declares <strong>no association</strong> to
 * any other entity. Deferred loading outside a transaction is therefore impossible by construction,
 * which satisfies the closed view-layer session setting trivially and means no entity graph and no
 * fetch join is needed or permitted anywhere in the module.
 *
 * <p>The index migration creates <strong>no foreign key and no secondary index</strong> touching this
 * table, which is the structural reason no derived finder is declared: the only access paths that
 * exist are the full-key read and the sequential or paged scan, and both are inherited. A derived
 * finder over any other attribute would describe an access path the schema does not support.
 *
 * <h2>Values are neither trimmed nor padded here</h2>
 *
 * <p>This interface performs no trimming, no padding and no case folding of any value. The columns are
 * bounded {@code VARCHAR(n)} rather than {@code CHAR(n)} precisely so the store neither blank-pads on
 * read nor trims on write, so whatever a caller supplies round-trips unchanged. Restoring a value to
 * its fixed record width, and every other piece of fixed-width layout knowledge, belongs exclusively to
 * the utility layer's mappers; no offset arithmetic, parsing or formatting occurs in this package.
 *
 * <h2>Seeded volume</h2>
 *
 * <p>The table <strong>starts empty</strong>: the reference-data migration seeds zero rows here and
 * asserts that fact, exactly as it does for the transaction table. The <strong>ten</strong> identities -
 * five administrative and five standard - arrive in {@code V4__seed_user_security.sql}, whose credentials
 * are stored only as independently salted BCrypt digests of exactly 60 characters, all ten distinct, a
 * property the migration itself verifies. That migration reaches <strong>local and test execution
 * only</strong>: the shared and production configurations pin the migration target below its version, so
 * a production deployment migrates schema and indexes and can never inherit a seeded login. Anything
 * asserting against these rows must therefore run with the seed applied.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source statement is transcribed
 * here: member names, dataset attributes, field names, byte offsets and widths, key definitions and
 * record lengths are metadata describing where a mapping came from, and the legacy tree remains
 * read-only reference that no production code reads at run time.
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
     * @param pageable the page, size and sort the caller requires; never {@code null}
     * @return one page of projections, empty when the page lies beyond the last row
     */
    Page<AdminEntry> findAllProjectedBy(Pageable pageable);

    /**
     * Stores one sign-on identity, inserting it or updating it in place.
     *
     * <p>The only write path. The credential this receives is already a BCrypt digest: hashing happens
     * in the user-management service, never here, and the entity itself refuses a value that is not a
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
