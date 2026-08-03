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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.carddemo.domain.Card;

/**
 * Spring Data JPA repository for the {@code card} table - the Java replacement for the
 * {@code CARDDATA} VSAM KSDS base cluster of the legacy CardDemo estate, and for the {@code CARDAIX}
 * alternate index defined over it.
 *
 * <p>The interface declares two methods, and they are one access path expressed at the two
 * cardinalities the legacy tier actually consumes. Everything else a caller needs - keyed retrieval,
 * existence, save, delete, unfiltered ordered paging - arrives inherited from
 * {@link JpaRepository} and is deliberately not restated here.
 *
 * <p>No stereotype annotation appears on the type and none is needed: Spring Data discovers
 * repository interfaces from the component scan rooted at the application package, and the
 * implementation is a proxy it supplies at run time. There is deliberately no companion
 * implementation class, no fragment interface and no shared base interface behind this one - the
 * whole contract is the two derived queries below plus what is inherited.
 *
 * <h2>Record provenance</h2>
 *
 * <p>The persistent shape comes from the copybook {@code CVACT02Y}, whose own header states a record
 * length of 150 bytes, and is corroborated independently by the cluster definition in
 * {@code app/jcl/CARDFILE.jcl}, which fixes the record at 150 bytes for both its minimum and its
 * maximum and places a 16-byte key at offset 0. Offsets and widths, in declaration order:
 *
 * <ul>
 *   <li>0/16 - card number, the business key</li>
 *   <li>16/11 - account identifier, the alternate key</li>
 *   <li>27/3 - card verification code</li>
 *   <li>30/50 - embossed name</li>
 *   <li>80/10 - expiration date</li>
 *   <li>90/1 - active status code</li>
 *   <li>91/59 - trailing filler, deliberately neither an attribute of {@link Card} nor a column of
 *       the {@code card} table</li>
 * </ul>
 *
 * <p>The declared widths sum to exactly 150, and the batch reader {@code app/cbl/CBACT02C.cbl}
 * splits the same record in its file section as a 16-byte key field followed by a 134-byte
 * remainder, which is the estate-wide declaration pattern for a keyed file and re-derives the same
 * arithmetic from a second direction.
 *
 * <h2>Identity is the legacy business key, and it is a string</h2>
 *
 * <p>A key width of 16 at offset 0 means the key <em>is</em> the leading substring of the record
 * image, so the persistent identity of a card row is that card number and nothing else. No
 * surrogate identifier, generated value, sequence or synthetic key exists anywhere in this module:
 * introducing one would sever the record-image-to-row correspondence that byte-level output parity
 * depends on.
 *
 * <p>The identifier type is therefore {@code String} rather than a numeric type. The column is a
 * bounded 16-character value holding a digit lexeme whose <strong>leading zeros are
 * contractual</strong>, and the same reasoning governs the three-character verification code, where
 * a value written as {@code 007} must survive a round trip as the three characters {@code 007}
 * rather than as the number seven. A numeric type would silently discard that padding, and the
 * discarded padding is the contract.
 *
 * <h2>The mandated access path: the {@code CARDAIX} alternate index</h2>
 *
 * <p>{@code app/jcl/CARDFILE.jcl} defines an alternate index over the same base cluster, related to
 * it through a path, keyed on the account identifier at length 11 and offset 16 - exactly the
 * account identifier's position in the record image - and declared with a non-unique key that is
 * upgraded in step with the base cluster. The resource definition {@code app/csd/CARDDEMO.CSD}
 * registers {@code CARDAIX} as one of its eight file entries, so it is a genuine <strong>online</strong>
 * access path in its own right rather than a batch-only convenience. That is a deliberate contrast
 * with the alternate index over the transaction cluster's processing timestamp, which carries no
 * resource-definition file entry at all and is consumed only by the reporting job.
 *
 * <p>Both declared methods below are that alternate index, and {@code V2__create_indexes.sql} backs
 * them with the non-unique B-tree index {@code idx_card_card_acct_id} so the derived query resolves
 * through an index rather than by scanning the table. The same migration adds
 * {@code fk_card_account} from this table to {@code account} and {@code fk_card_xref_card} from the
 * cross-reference table back to this one.
 *
 * <h2>How the legacy tier really reaches a card, program by program</h2>
 *
 * <p>This was established by reading every reference to the alternate index by name, and it is
 * recorded here rather than smoothed over, because a future reader who guesses wrongly in either
 * direction will do damage. The finder is genuinely required and serves the account-view,
 * account-update, card-detail and card-update paths; within the legacy programs those four reach a
 * card through three different mechanisms:
 *
 * <ul>
 *   <li><strong>Card detail</strong> ({@code app/cbl/COCRDSLC.cbl}) issues its read against the
 *       {@code CARDAIX} path itself, keyed on the account identifier, in the paragraph that its own
 *       comment describes as access via the account-identifier alternate index. This is the
 *       canonical read-by-account that the two methods below reproduce directly.</li>
 *   <li><strong>Account view</strong> ({@code app/cbl/COACTVWC.cbl}) and <strong>account
 *       update</strong> ({@code app/cbl/COACTUPC.cbl}) both declare the {@code CARDAIX} name in
 *       working storage but resolve an account's card through the {@code CXACAIX} cross-reference
 *       alternate index instead. Their Java counterparts still need "the cards of this account",
 *       and this is where that query lives.</li>
 *   <li><strong>Card update</strong> ({@code app/cbl/COCRDUPC.cbl}) likewise declares the name and
 *       then reads and rewrites the <em>base</em> cluster by card number, which is inherited keyed
 *       access rather than either alternate index.</li>
 * </ul>
 *
 * <p><strong>The card-list screen is the case that matters most, and it does not use the alternate
 * index at all.</strong> {@code app/cbl/COCRDLIC.cbl} declares two file-name literals in working
 * storage, the base cluster and the {@code CARDAIX} path, and the second one is declared and then
 * never referenced. Every browse the program issues - start-browse, read-next, read-previous and
 * end-browse alike - names the <em>base cluster</em> and supplies the <em>card number</em> as the
 * record identifier field, and its browse response evaluation accepts the normal and the
 * duplicate-record responses together in a single clause, which is the ordinary shape for a browse
 * whose underlying structure permits duplicates. The account filter is then applied
 * <em>after</em> the read, by the program's own filter paragraph, which compares the account
 * identifier and the card number of the record just read and excludes it if either fails.
 *
 * <p>Two consequences follow, and both are load-bearing:
 *
 * <ul>
 *   <li>The card-list screen's unfiltered, card-number-ordered browse is the <strong>inherited</strong>
 *       paged {@code findAll} with a sort on the card number, so <strong>no method is declared here
 *       for it</strong>. Declaring one would be redundant, and routing the card list through the
 *       account-identifier finder instead would silently change the on-screen ordering from card
 *       number to account identifier.</li>
 *   <li>The account-identifier finder is nevertheless not speculative. Deleting it as unused would
 *       break the four paths named above, one of which reads the alternate index in the legacy
 *       program itself.</li>
 * </ul>
 *
 * <h2>Page size and sort direction belong to the caller, never to this interface</h2>
 *
 * <p>The card-list screen shows seven rows. That is proven twice over in the legacy program - an
 * explanatory comment giving the screen array as twenty-eight characters by seven rows, and a
 * working-storage table declared to occur seven times, each occurrence being an eleven-character
 * account identifier, a sixteen-character card number and a one-character selection flag. The
 * number is stated here as documentation of the legacy screen and appears nowhere in this file as a
 * constant, a default or a value: it is supplied by the card-list service as part of the
 * {@link Pageable} it passes in. A repository that fixed its own page size would be both a layering
 * breach and a hardcoded operational parameter. For the same reason the two other page sizes in the
 * estate - ten for the user list and ten for the transaction list - belong to the callers of other
 * repositories and are named here only so the three are never confused.
 *
 * <p>Sort <em>direction</em> is the caller's too, and accepting it is a behavioural requirement
 * rather than a courtesy. Backward paging in the legacy tier reads in reverse and fills the screen
 * rows from the last position towards the first, so a caller must be able to ask for a descending
 * order and receive rows in descending order. This interface imposes no ordering of any kind on
 * either method, which is precisely what lets the service reproduce the legacy fill sequence.
 *
 * <h2>Optimistic locking, and why none of it is declared here</h2>
 *
 * <p>{@code card} and {@code account} are the only two tables in {@code V1__create_schema.sql} that
 * carry a version counter, because they are the only two records the legacy system rewrites in
 * place under a comparison of the record image before and after the change. The version attribute on
 * {@link Card} is the faithful replacement for that comparison, and Hibernate enforces it on flush.
 *
 * <p>Consequently this interface declares <strong>no lock mode and no transaction boundary</strong>.
 * Optimistic locking is declarative on the entity, and translating a lost update into the module's
 * conflict exception is the service layer's job - the card-update path owns both that translation
 * and the retry structure that the legacy program expressed as a backward jump around its
 * write-processing exit. Layering a pessimistic lock on top of an optimistic model here would change
 * concurrency behaviour the legacy system never had.
 *
 * <p>One related point is worth stating so that a reviewer does not mistake it for a regression: the
 * legacy resource definition gives every application file uncommitted read integrity, no recovery
 * and no journaling, resting entirely on record-level locking plus the programs' own image
 * comparison. PostgreSQL's read-committed isolation combined with the entity's version counter is a
 * <strong>strict improvement</strong> on that baseline, and it is recorded as such in the decision
 * log.
 *
 * <h2>Sequential access, and what else is inherited</h2>
 *
 * <p>The batch file-maintenance path derived from {@code app/cbl/CBACT02C.cbl} reads the base
 * cluster sequentially in key order. That is an inherited sorted or paged {@code findAll} and not a
 * declared method, exactly as the card-list browse is. Keyed retrieval of a single card, which the
 * card-update path performs, is the inherited find-by-identifier.
 *
 * <h2>No object graph, and no fetch tuning</h2>
 *
 * <p>{@link Card} exposes the account identifier as a scalar attribute rather than as an
 * association, even though the migration does create the foreign keys named above. Referential
 * integrity is a database constraint here, not a Java relationship. Because no association exists,
 * nothing can be lazily initialised outside a transaction, the module's decision to disable
 * view-scoped persistence contexts is trivially satisfied, and no entity-graph hint or join-fetch
 * clause is needed or permitted anywhere in the module.
 *
 * <p>For the same reason no query hint, fetch size, timeout or other tuning parameter appears on
 * either method. Stating that a finder is index-backed is a claim about the <em>access path</em> and
 * belongs in the documentation; asserting a figure for how fast it runs does not, because the
 * performance gate of this migration establishes a first baseline rather than testing against an
 * inherited one.
 *
 * <h2>Values pass through unchanged</h2>
 *
 * <p>Nothing in this interface trims, pads, folds or normalises an argument or a result. Fixed-width
 * padding is significant in the estate, and the schema uses bounded variable-length columns rather
 * than blank-padded fixed-length ones precisely so that a stored value is returned as it was
 * written rather than silently re-padded on read. An account identifier handed to either method is
 * matched exactly as given.
 *
 * <p>In particular the legacy upper-casing of the embossed name is a strict twenty-six-character
 * table fold, performed by the card-update path through {@code com.carddemo.util.CobolStringUtils},
 * and it is never performed here and never by way of a locale-sensitive library case conversion.
 * Fixed-width offset arithmetic is likewise confined to the record mappers of the utility layer;
 * this interface holds no offsets and parses nothing.
 *
 * <h2>Recorded anomaly</h2>
 *
 * <p>The copybook's own name for the expiration-date field at offset 80 is misspelled. The Java
 * attribute and the migration column are both spelled correctly, and the record mapper's offset for
 * that field is unchanged, so the 150-byte layout stays byte-compatible while the misspelling
 * survives only in the decision log and the traceability matrix. It is neither reintroduced into
 * Java nor quietly corrected in the read-only legacy tree.
 *
 * <h2>Recorded security gap</h2>
 *
 * <p>The card primary account number and the verification code have no field-level encryption and no
 * masking anywhere in the legacy design, and this migration deliberately adds none: closing that gap
 * would be unrequested feature work, so it is documented as an open gap instead. Nothing in this
 * package logs, prints or otherwise emits a card number or a verification code - the repository
 * package is intentionally not one of the loggers configured for this module, and neither method
 * below produces any diagnostic output.
 *
 * <h2>Schema authority and seeded volume</h2>
 *
 * <p>This mapping is validated, never generated. Flyway owns the {@code card} table and the runtime
 * configuration fixes Hibernate at schema validation only, so the attribute name that both methods
 * derive their query from must resolve to the {@code card_acct_id} column or the application context
 * fails at start-up. That is the earliest possible detection of a misnamed derived finder, and it is
 * deliberate.
 *
 * <p>{@code V3__seed_reference_data.sql} seeds exactly fifty rows, after the customer and account
 * tables because the foreign key points at {@code account}, and before the cross-reference table
 * because its own foreign key points back here. That migration belongs to the local and test
 * profiles only; a production deployment migrates schema and indexes without inheriting sample data.
 * The fifty seeded cards map one-to-one onto fifty distinct accounts, so the seed alone cannot
 * demonstrate the multiple-card case that the non-unique key permits - a test that needs to prove it
 * has to add cards of its own.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the estate at commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. The
 * copybook's trailer records the upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68}, dated
 * 2022-07-19. The legacy tree is read-only reference and stays byte-identical: no source text from
 * it is copied into this module, and no production class reads from it at runtime, so the
 * traceability record cites members, cluster and index names, field names, offsets and widths rather
 * than reproducing any statement.
 *
 * @see Card
 * @see JpaRepository
 */
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Returns every card issued against one account, in one call.
     *
     * <p>This is the Java realisation of the {@code CARDAIX} alternate index over the
     * {@code CARDDATA} base cluster: an alternate key on the account identifier of length 11 at
     * offset 16 within the 150-byte record, declared with a non-unique key and upgraded in step with
     * the base cluster. {@code V2__create_indexes.sql} backs the derived query with the non-unique
     * B-tree index {@code idx_card_card_acct_id}, so it resolves through that index rather than by
     * scanning the table.
     *
     * <p>The alternate index is registered to the online region as a file entry of its own in
     * {@code app/csd/CARDDEMO.CSD}, which makes this a genuine online access path - in deliberate
     * contrast to the alternate index over the transaction cluster's processing timestamp, which has
     * no such entry and is reached only by the reporting job.
     *
     * <p><strong>The result is a list because the alternate key is non-unique.</strong> An account
     * legitimately carries more than one card, so a single-valued return type would raise an
     * incorrect-result-size failure the moment a second card existed - a failure mode the legacy
     * system cannot produce, since its own browse of a duplicate-bearing structure treats the
     * duplicate-record response as success. The list shape is the faithful translation; a singular
     * one would be a plausible-looking defect.
     *
     * <p>An <strong>empty list is the analogue of the legacy not-found response</strong>. No
     * exception is raised here for an account that has no cards, and none is raised for an account
     * that does not exist: normalising a file status into an outcome, and deciding whether an absent
     * record is an error, is the business of the service and batch layers, exactly as the legacy
     * programs normalised a two-byte status into an application result before branching on it.
     *
     * <p>Use this overload where the caller needs the whole set at once - the account-view,
     * account-update, card-detail and card-update paths all resolve a card from an account
     * identifier this way. Where a bounded, caller-ordered window is wanted instead, use
     * {@link #findByCardAcctId(String, Pageable)}.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied; it is
     *                   never trimmed, and its leading zeros are significant
     * @return every card whose account identifier equals the argument; possibly empty, never
     *         {@code null}
     */
    List<Card> findByCardAcctId(String cardAcctId);

    /**
     * Returns one caller-sized, caller-ordered page of the cards issued against one account.
     *
     * <p>Same access path as {@link #findByCardAcctId(String)} and the same index: the
     * {@code CARDAIX} alternate index over the {@code CARDDATA} base cluster, keyed on the account
     * identifier at length 11 and offset 16 within the 150-byte record, non-unique and upgraded with
     * the base cluster, backed in the relational schema by the non-unique B-tree index
     * {@code idx_card_card_acct_id} created by {@code V2__create_indexes.sql} so that the derived
     * query resolves through an index rather than a table scan. It is registered to the online
     * region as a file entry in its own right, which makes it an online path rather than the
     * batch-only kind the transaction processing-timestamp index is.
     *
     * <p><strong>The page carries entities rather than a single value for the same reason the other
     * overload returns a list:</strong> the alternate key is non-unique, an account may hold several
     * cards, and a single-valued return type would raise an incorrect-result-size failure that the
     * legacy system has no equivalent of. An <strong>empty page is the analogue of the legacy
     * not-found response</strong>, and no exception is raised here.
     *
     * <p><strong>The {@link Pageable} supplies both the page size and the sort direction, and this
     * method imposes neither.</strong> The card-list screen of the legacy estate shows seven rows,
     * and that size arrives from the service as part of this argument rather than being fixed here.
     * Direction matters just as much: legacy backward paging reads in reverse and fills the screen
     * rows from the last position towards the first, so a caller that asks for a descending order on
     * the card number must receive the rows in descending order. Passing an unsorted
     * {@link Pageable} leaves the order unspecified, which is a decision for the caller to make
     * knowingly.
     *
     * <p>Note that this is the <em>account-filtered</em> paged access path. The legacy card-list
     * screen itself pages the base cluster in card-number order and applies its account filter after
     * the read, so its Java counterpart uses the inherited paged {@code findAll} with a card-number
     * sort and not this method; routing it here would change the ordering the screen presents.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied; it is
     *                   never trimmed, and its leading zeros are significant
     * @param pageable   the caller's page number, page size and sort order, including sort
     *                   direction; supplied by the service and never defaulted here
     * @return the requested page of cards whose account identifier equals the argument, carrying the
     *         total count of matches; possibly empty, never {@code null}
     */
    Page<Card> findByCardAcctId(String cardAcctId, Pageable pageable);
}
