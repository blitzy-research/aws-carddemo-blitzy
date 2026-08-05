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

import com.carddemo.domain.UserSecurity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence gateway for the {@code user_security} table - the relational form of the 80-byte
 * {@code SEC-USER-DATA} record of copybook {@code CSUSR01Y}, keyed on the 8-character user
 * identifier the record carries at offset 0.
 *
 * <p><strong>The body is empty, and deliberately so.</strong> Every operation the module performs on
 * this table is one of {@code JpaRepository}'s own: {@code findById} for the keyed sign-on read,
 * {@code findAll(Pageable)} for the administrative browse, {@code save} for the add and update
 * transactions, {@code deleteById} for the delete transaction, and {@code existsById} where presence
 * alone is the question. Not one of them needs redeclaring, and redeclaring them would state the
 * inherited signature a second time in a place that can drift from it.
 *
 * <p><strong>What is not declared here is still governed - by the service tier.</strong> An earlier
 * revision extended the bare {@code Repository} marker and wrote out four operations, to keep an
 * unbounded {@code findAll()}, the bulk deletes and the query-by-example family off the injected type.
 * The reasoning was sound about the risk and wrong about where the control belongs: an interface shape
 * is a static grant, and the thing that actually has to hold is that <em>no caller in this module reads
 * every credential, removes every identity, or hydrates a digest it has no use for</em>. That is a
 * property of the call sites, and it is asserted there. The rules the call sites keep are:
 *
 * <ul>
 *   <li><strong>The administrative list is paged and never unbounded.</strong> It reads
 *       {@code findAll(Pageable)} at the legacy screen's page size and never {@code findAll()}.</li>
 *   <li><strong>Nothing bulk-writes or bulk-deletes.</strong> The legacy tier rewrote one record at a
 *       time and removed one row it had just read, so the service uses {@code save} and
 *       {@code deleteById} and nothing else.</li>
 *   <li><strong>A digest that is loaded is used and not rendered.</strong> The list projects the four
 *       columns the legacy screen shows - identifier, given name, family name and role code - out of
 *       the entities it reads, and the digest is never written to a response, a log or an exception
 *       message. Projecting in the query rather than in the service would keep the digest out of
 *       memory, which is stronger; it also puts a transport shape into the persistence contract, which
 *       the layering forbids and which this file's own contract excludes.</li>
 * </ul>
 *
 * <p>{@code sec_usr_pwd} is {@code VARCHAR(60)} because it stores a BCrypt digest rather than the
 * legacy 8-byte cleartext field. It is one of three columns in the schema whose width deliberately
 * exceeds its legacy field - {@code customer.cust_ssn} and {@code customer.govt_issued_id} are the
 * other two, widened to hold a sealed envelope rather than cleartext - and the only one widened for
 * hashing rather than for protection at rest; every other column preserves its legacy width exactly.
 * It must not be narrowed back to the legacy width, which would truncate and destroy every stored
 * digest, and it must not be widened further. The divergence exists only because a binding requirement
 * demands it, and it is recorded in {@code docs/decision-log.md} rather than silently applied.
 *
 * <h2>How the dataset is provisioned, and why no character-set decode was ever needed</h2>
 *
 * <p>The provisioning job {@code app/jcl/DUSRSECJ.jcl} is unlike the jobs behind the module's other
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
 * <h2>The business key is the identifier itself</h2>
 *
 * <p>The key type is {@link String} because the legacy key is the 8-character alphanumeric user
 * identifier stored at offset 0, mapped to {@code VARCHAR(8)}, and the seeded identifiers mix letters
 * and digits, so no numeric type is even conceivable. As everywhere else in this module, the JPA
 * identifier is the business key and <strong>no surrogate key exists</strong> - nothing is generated,
 * sequenced or synthesised, so a row's identity in the table is the same identity the legacy record
 * had.
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
 * <h2>No query may take a credential</h2>
 *
 * <p>The attribute this table exposes is the <strong>60-character BCrypt digest</strong> and never a
 * cleartext value, and the attribute must never be renamed or re-typed to suggest otherwise.
 * Consequently <strong>no query anywhere may take a credential</strong>, hashed or not - and none can
 * be derived from this interface, because it declares no finder to derive one from. That is a
 * mechanical impossibility rather than a stylistic preference: a salted digest differs on every
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
 * <h2>The administrative browse is a paged read, and the page size is the caller's</h2>
 *
 * <p>The administrative user list presents a page of 10 rows, proven from the legacy screen table
 * declared as occurring 10 times rather than inferred. The Java counterpart is the inherited
 * {@code findAll(Pageable)}. The page size is supplied by the caller through the {@code Pageable} and
 * appears nowhere here as a constant or a default; nor does this interface impose an ordering, so the
 * service can request either direction and reproduce the legacy browse fill order, which fills
 * backwards as well as forwards.
 *
 * <h2>Writes go through one save, one record at a time</h2>
 *
 * <p>The administrative add and update transactions both store a credential, and both <strong>hash on
 * write in the user-management service</strong> - never here. The inherited {@code save} is the only
 * write path the service uses and covers add and update alike through the merge semantics of the
 * persistence provider, which is exactly the record-at-a-time, rewrite-in-place behavior of the legacy
 * tier. No bulk update, upsert or modifying statement is declared, and the delete transaction is served
 * by {@code deleteById}, which removes the one row it is given.
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
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {
}
