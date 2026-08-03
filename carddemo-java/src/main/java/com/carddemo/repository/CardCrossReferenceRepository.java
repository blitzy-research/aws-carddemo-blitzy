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

import com.carddemo.domain.CardCrossReference;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@code card_cross_reference} table, which replaces the
 * {@code CARDXREF} key-sequenced VSAM base cluster of the legacy estate. It declares exactly one
 * method of its own, and that method exists to reproduce one specific legacy access path rather than
 * to add a convenience: see {@link #findByXrefAcctId(String)}.
 *
 * <p><strong>Legacy provenance.</strong> The row shape derives from copybook
 * {@code app/cpy/CVACT03Y.cpy}, whose header names a record length of 50 while only 36 of those bytes
 * carry information: a 16-byte card number at offset 0, a 9-byte customer identifier at offset 16 and
 * an 11-byte account identifier at offset 25, followed by an unnamed 14-byte filler at offset 36 that
 * completes the image and is deliberately not persisted. The geometry is corroborated independently
 * twice over: the cluster definition in {@code app/jcl/XREFFILE.jcl} fixes a key width of 16 at offset
 * 0 against {@code RECORDSIZE(50 50)} on an {@code INDEXED} cluster, and the file section of
 * {@code app/cbl/CBACT03C.cbl} splits the very same record into a 16-byte key field followed by a
 * 34-byte data remainder, which is the 9 plus 11 mapped bytes plus the 14 filler bytes.
 *
 * <p><strong>The unpersisted filler is measurable, not theoretical.</strong> That 36-versus-50 split
 * is what reconciles two validation artefacts describing the identical 50 records at different
 * widths. The text fixture {@code app/data/ASCII/cardxref.txt} measures 1,850 bytes, being 50 rows of
 * the 36 mapped bytes plus one line terminator each, because the text form writes only the mapped
 * prefix; the fixed-length dataset {@code app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS} measures 2,500
 * bytes, being the same 50 rows at the full 50-byte cluster record length. The 700-byte difference in
 * data is exactly the 50 rows of 14 filler bytes, and both measurements are correct. Recording this
 * forestalls a later reader concluding that the narrower fixture is truncated, and it is why no column
 * and no attribute stands in for the filler.
 *
 * <p><strong>Identity is the legacy business key, and its type is {@code String}.</strong> Because the
 * key occupies offset 0 it is the leading substring of the record image rather than a separate
 * attribute, which is the structural reason no surrogate key exists here or anywhere else in this
 * module. The identifier is a sixteen-character zero-filled lexeme whose leading zeros are
 * contractual, so a numeric key type would discard them and break the record-image-to-row
 * correspondence that byte-level output parity depends on.
 *
 * <p><strong>Values are matched and returned exactly as supplied.</strong> Nothing here trims, pads,
 * folds or reformats a value. The schema declares all three columns as bounded variable-length
 * character columns rather than blank-padded fixed-length ones precisely so that whatever width a
 * value arrives with is the width it is stored and read back at. Fixed-width layout knowledge -
 * offsets, widths and the filler - is the exclusive concern of the record mapper in the utility layer,
 * which produces instances of {@link CardCrossReference}; this interface performs no slicing, parsing
 * or formatting of any kind and holds no dependency on that layer.
 *
 * <p><strong>Why the record layout has to be exactly right.</strong> Copybook {@code CVACT03Y} is
 * included by ten programs, the third-highest inclusion count of any data copybook in the estate, and
 * that reach is structural rather than incidental: a card number is what the online screens and the
 * posting job are handed, while an account or a customer is what they need, so this table is the
 * resolution point for every card-to-account and card-to-customer hop in the system. A layout error
 * here would not fail loudly in one place; it would misresolve quietly in ten.
 *
 * <p><strong>Three foreign keys originate in this table and no association does.</strong>
 * {@code V2__create_indexes.sql} constrains all three columns - {@code fk_card_xref_card} against the
 * card table's key, {@code fk_card_xref_account} against the account table's key and
 * {@code fk_card_xref_customer} against the customer table's key - which makes this the most heavily
 * constrained table in the schema and, to an eye trained on object models, the obvious place for three
 * many-to-one associations. {@link CardCrossReference} deliberately declares none. This table exists
 * so that a lookup can travel from one key to another without loading anything, so an association
 * would defeat the purpose of the table; referential integrity is consequently enforced where it
 * belongs, by the database. The practical consequence is that view-scoped lazy loading can stay
 * disabled by construction: there is no association to traverse, so no {@code @EntityGraph} and no
 * fetch join is needed or permitted anywhere in this module, and no lazy-initialisation failure is
 * reachable from here.
 *
 * <p><strong>The customer hop is two keyed reads, not a join.</strong> The legacy account-view flow
 * reads this cross reference to obtain the customer identifier and then reads the customer directly by
 * that key, and the translation keeps both steps: the first is this repository, the second is the
 * inherited keyed lookup on the customer repository. No join finder exists on either side of that hop,
 * and none should be added.
 *
 * <p><strong>Inherited operations cover everything else.</strong> Keyed retrieval, existence checks,
 * counting, saving and both sorted and paged sequential traversal all arrive from
 * {@link JpaRepository}, so the legacy indexed and sequential patterns are reproduced without further
 * declarations. In particular the sequential reader of {@code app/cbl/CBACT03C.cbl}, which walks this
 * base cluster in card-number order, translates to an inherited sorted or paged {@code findAll} rather
 * than to a method here. Record absence on a keyed lookup is expressed as an empty result rather than
 * as an exception, exactly as the finder below expresses it as an empty list. Normalising a raw
 * two-byte file status into an outcome, and deciding whether an absent record is an error at all,
 * belongs to the service and batch layers, exactly as the legacy batch programs normalised a status
 * into an application result before branching on it; this interface therefore declares no exception
 * type and no status enum.
 *
 * <p>The reference seed loads exactly 50 rows into this table, and loads them after the customer,
 * account and card tables so that all three of the constraints above resolve. Those seed migrations
 * are reachable only under the local and test profiles.
 *
 * <p>Migrated from the AWS CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @see CardCrossReference
 * @see JpaRepository
 */
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {

    /**
     * Returns every cross-reference row bound to one account.
     *
     * <p>This is the Java realisation of the {@code CXACAIX} alternate index over the
     * {@code CARDXREF} base cluster, declared in {@code app/jcl/XREFFILE.jcl} on the account
     * identifier with {@code KEYS(11 25)} - a key width of 11 at offset 25, which is exactly that
     * identifier's position within the 50-byte record - and qualified {@code NONUNIQUEKEY} with
     * {@code UPGRADE}, meaning duplicate keys are permitted and the index is maintained in step with
     * the base cluster. {@code V2__create_indexes.sql} backs this derived query with the non-unique
     * B-tree index {@code idx_card_cross_reference_xref_acct_id} over the account-identifier column,
     * so the query resolves through that index rather than by scanning the table.
     *
     * <p>The alternate index is registered to the online region as a file entry of its own in
     * {@code app/csd/CARDDEMO.CSD}, which makes this a genuine online access path - in deliberate
     * contrast to the alternate index over the transaction cluster's processing timestamp, which has
     * no such entry and is reached only by the reporting job.
     *
     * <p><strong>The legacy access is a single keyed read, not a browse.</strong> Two online programs
     * reach the account through this path - the bill-payment program and the transaction-add program -
     * and each issues one keyed read against the alternate-index path, in contrast to the browse
     * sequences those same programs use elsewhere against the transaction cluster. A keyed read over a
     * non-unique alternate index yields the first base record in ascending base-key order among all
     * records sharing the alternate key, which here is the row with the lowest card number of those
     * bound to the account. <strong>That first-match rule is applied by the service layer, and this
     * method returns every match.</strong> Selecting one row out of several is a business decision, so
     * it is not encoded here as an ordering clause or a limiting finder; a caller that wants the
     * ordering made explicit can supply a card-number sort through an inherited operation.
     *
     * <p><strong>The result is a list because the alternate key is non-unique.</strong> Both callers
     * read a single record, which makes a single-valued return type look like the natural shape, and
     * it is the wrong one: the moment two rows shared an account identifier, a single-valued derived
     * query would raise an incorrect-result-size failure, and that is a failure mode the legacy system
     * cannot produce, since its own read of a duplicate-bearing structure simply returns the first
     * record. The list shape is the faithful translation; a singular one would be a plausible-looking
     * defect.
     *
     * <p>An <strong>empty list is the analogue of the legacy not-found response</strong>. No exception
     * is raised here for an account that has no cross-reference row: the legacy programs turned that
     * response into a screen message, and the equivalent decision belongs to the service layer.
     *
     * <p><strong>What the reference seed can and cannot prove.</strong> The seeded data is one-to-one
     * across 50 accounts, 50 cards and 50 cross-reference rows, so no seeded account identifier owns
     * more than one row. The seed therefore exercises this method but does not stress it: it cannot
     * demonstrate that several rows come back, and it cannot demonstrate the first-match ordering the
     * service applies. A purpose-built fixture holding two rows that share an account identifier with
     * differing card numbers is required for that, and the integration test for this repository builds
     * one rather than relying on the seed.
     *
     * @param xrefAcctId the eleven-character account identifier, matched exactly as supplied; it is
     *                   never trimmed, and its leading zeros are significant
     * @return every cross-reference row whose account identifier equals the argument; possibly empty,
     *         never {@code null}
     */
    List<CardCrossReference> findByXrefAcctId(String xrefAcctId);
}
