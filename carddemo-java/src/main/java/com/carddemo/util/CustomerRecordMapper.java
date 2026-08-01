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
package com.carddemo.util;

import java.util.Objects;
import java.util.function.UnaryOperator;

import com.carddemo.domain.Customer;

/**
 * Hand-written, reflection-free mapper between the legacy 500-byte customer record image and
 * {@link Customer}. This is the widest layout in the estate - 500 bytes, eighteen mapped fields and a
 * 168-byte trailing filler - and the only one the estate declares under two different COBOL field
 * spellings.
 *
 * <h2>The authoritative layout</h2>
 * Two copybooks are the source of truth, {@code [app/cpy/CVCUS01Y.cpy]} and
 * {@code [app/cpy/CUSTREC.cpy]}, and both declare the same {@code CUSTOMER-RECORD} group. The cluster
 * definition in {@code [app/jcl/CUSTFILE.jcl]} corroborates the geometry independently, declaring
 * {@code RECORDSIZE(500 500)} and {@code KEYS(9 0)} - a nine-byte key at offset zero. Offsets below
 * are zero-based byte offsets into the record image.
 *
 * <pre>
 *  #   COBOL field                  PIC       Offset  Length  Java property on Customer
 * --  ---------------------------  --------  ------  ------  -------------------------------
 *  1  CUST-ID                      9(09)          0       9  custId  (the JPA identifier)
 *  2  CUST-FIRST-NAME              X(25)          9      25  firstName
 *  3  CUST-MIDDLE-NAME             X(25)         34      25  middleName
 *  4  CUST-LAST-NAME               X(25)         59      25  lastName
 *  5  CUST-ADDR-LINE-1             X(50)         84      50  addrLine1
 *  6  CUST-ADDR-LINE-2             X(50)        134      50  addrLine2
 *  7  CUST-ADDR-LINE-3             X(50)        184      50  addrLine3
 *  8  CUST-ADDR-STATE-CD           X(02)        234       2  addrStateCd
 *  9  CUST-ADDR-COUNTRY-CD         X(03)        236       3  addrCountryCd
 * 10  CUST-ADDR-ZIP                X(10)        239      10  addrZip
 * 11  CUST-PHONE-NUM-1             X(15)        249      15  phoneNum1
 * 12  CUST-PHONE-NUM-2             X(15)        264      15  phoneNum2
 * 13  CUST-SSN                     9(09)        279       9  custSsn  (the only nullable column)
 * 14  CUST-GOVT-ISSUED-ID          X(20)        288      20  govtIssuedId
 * 15  CUST-DOB-YYYY-MM-DD  or                   308      10  custDob
 *     CUST-DOB-YYYYMMDD            X(10)
 * 16  CUST-EFT-ACCOUNT-ID          X(10)        318      10  eftAccountId
 * 17  CUST-PRI-CARD-HOLDER-IND     X(01)        328       1  priCardHolderInd
 * 18  CUST-FICO-CREDIT-SCORE       9(03)        329       3  ficoCreditScore
 *  -  FILLER                       X(168)       332     168  not mapped, not persisted
 * </pre>
 *
 * <p><strong>Width arithmetic: 332 + 168 = 500.</strong> The eighteen mapped field lengths sum to
 * 332 bytes - {@code 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1
 * + 3} - and the 168-byte filler that follows brings the record to 500. That relationship is not
 * asserted at run time and is not merely documented here: every offset constant below is
 * <em>derived</em> from the preceding offset plus the preceding length, {@link #MAPPED_DATA_WIDTH} is
 * derived from the last field's offset plus its length, and {@link #RECORD_WIDTH} is derived as
 * {@code MAPPED_DATA_WIDTH + FILLER_LENGTH}. All of them are compile-time constant expressions, so a
 * length edited without its offset cannot compile to a self-consistent but wrong layout - the
 * classic fixed-width mapper defect. Every {@code fromRecord} input is additionally validated as
 * exactly {@value #RECORD_WIDTH} <em>encoded bytes</em>.
 *
 * <p><strong>No zoned-decimal field exists in this layout.</strong> Every field is either
 * {@code PIC X(n)} character data or unsigned {@code PIC 9(n)} external decimal; none is signed, none
 * carries an implied decimal point and none is monetary. {@link ZonedDecimalCodec} is therefore
 * deliberately never invoked from this class. That is recorded explicitly rather than left as an
 * absence so a reviewer can confirm by inspection that no monetary field was overlooked: the codec
 * exists for the {@code PIC S9(n)V99} amounts and rates carried by the account, transaction,
 * category-balance and disclosure-group layouts, and the customer record carries none of them.
 *
 * <h2>Two COBOL spellings of the date of birth, one entity, one mapper, one code path</h2>
 * {@code [app/cpy/CVCUS01Y.cpy]} and {@code [app/cpy/CUSTREC.cpy]} declare the same record name, the
 * same eighteen fields with the same picture clauses in the same order, and the same 168-byte filler.
 * They differ in exactly one respect: the field at offset 308 is spelled with hyphens between the
 * date parts in the first and without them in the second. Both spellings denote the same ten bytes at
 * the same offset.
 *
 * <p>The unhyphenated spelling is <strong>live</strong>, not dead code: the statement-generation
 * program {@code [app/cbl/CBSTM03A.CBL]} includes {@code CUSTREC} and never {@code CVCUS01Y}, while
 * six other programs include {@code CVCUS01Y}. Both are therefore in force at once.
 *
 * <p>Because the byte geometry is identical, this is <strong>one alternate 500-byte view of one
 * entity</strong>, not a second entity and not a second layout. A single {@code fromRecord} and
 * {@code toRecord} pair serves both. There is deliberately no variant flag, no variant enum, no
 * overload pair and no second class distinguishing them: the bytes do not differ, so the code must
 * not branch. The distinction is documentation only, and both spellings are published as constants -
 * {@link #CUST_DOB_FIELD_CVCUS01Y} and {@link #CUST_DOB_FIELD_CUSTREC} - so that the dual naming is
 * auditable in code rather than only in prose.
 *
 * <p>The spelling of the field name says nothing about the format of the value. The seeded value at
 * offset 308 of the first record of {@code [app/data/ASCII/custdata.txt]} is {@code 1961-06-08} -
 * hyphenated ISO form - so the unhyphenated <em>name</em> does not imply an unhyphenated
 * <em>value</em>. The field is carried as a raw ten-byte string in both directions. This class
 * performs no date parsing, no reformatting and no calendar validation; that is the date-validation
 * service's responsibility.
 *
 * <h2>The two regulated identifiers cross a caller-supplied seam</h2>
 * The record image carries the national identifier and the government-issued identifier in clear
 * text. {@link Customer} refuses to store either in that form: both its eighteen-argument constructor
 * and its two mutators admit only a value already sealed into the module's protected-value envelope,
 * and the national identifier may alternatively be {@code null}. The entity itself neither encrypts
 * nor decrypts, because the domain layer may not depend on the utility or service layers.
 *
 * <p>This mapper does not encrypt either, and holds no key, no algorithm name and no salt. It
 * <strong>slices both fields exactly as they appear</strong> and hands the raw slice to a sealing
 * operation the caller supplies, and on the way out hands the stored envelope to the caller's
 * inverse operation to recover the ten or nine bytes the record image requires. The seam is a plain
 * {@code UnaryOperator<String>} from the platform library, so this class acquires no dependency on
 * the service that implements it and the layering rule is preserved in both directions. A caller
 * wires the field-encryption service's sealing method into {@code fromRecord} and its revealing
 * method into {@code toRecord}.
 *
 * <p>Consequently no cleartext regulated value is ever written to a column by this mapper, and no
 * ciphertext is ever written into a record image. Neither the seam nor this class changes a single
 * byte of record geometry: the record image stays nine bytes at offset 279 and twenty bytes at offset
 * 288 regardless of how wide the column behind them is.
 *
 * <h2>The national identifier is the schema's only nullable column</h2>
 * {@code custSsn} maps to the one nullable column in the whole schema, and every one of the fifty
 * seeded rows stores {@code null} there. Null tolerance is therefore mandatory rather than defensive,
 * and it is asymmetric by design:
 *
 * <ul>
 *   <li>{@code fromRecord} always reads the nine bytes at offset 279. A record's field is never
 *       absent - a fixed-width record has no notion of a missing field - so the slice is always
 *       present and is always passed to the caller's sealing operation, even when it is nine
 *       spaces.</li>
 *   <li>{@code toRecord} <strong>tolerates a null</strong> national identifier and emits
 *       <strong>nine spaces</strong> in its place, without consulting the caller's revealing
 *       operation at all. Raising here would break every round trip against the seeded database,
 *       because every seeded row holds {@code null}.</li>
 * </ul>
 *
 * <p>An absent value is rendered as an all-space field and never as {@code 000000000}, which is how
 * COBOL renders an unset field and which also avoids fabricating a nine-digit value that was never
 * held. Nine spaces round-trip exactly: read back, the field is nine spaces, and placing nine spaces
 * reproduces nine spaces.
 *
 * <p>No diagnostic raised by this class contains the national identifier, the government-issued
 * identifier or any other field value. Messages name the artefact, the field, the offset and the
 * widths, all of which are layout facts rather than data. Nothing here is logged: this class holds no
 * logger, writes to no stream and prints nothing.
 *
 * <h2>The credit score is not range-validated here, and must not be</h2>
 * {@code CUST-FICO-CREDIT-SCORE} is three bytes of unsigned external decimal at offset 329. The
 * 300-to-850 range is screen-level edit validation belonging to the account-update path, not a
 * property of the stored record, and the evidence is measured rather than assumed: the first record
 * of {@code [app/data/ASCII/custdata.txt]} carries {@code 274}, and <strong>21 of the 50 seeded rows
 * carry a score below 300</strong>, the lowest being {@code 001}. A range, digit or pattern check
 * here would make the reference data unloadable and would fail the end-to-end and named-artefact
 * acceptance gates outright. The value is carried as a raw string and never as an {@code int}, so its
 * leading zeros survive.
 *
 * <h2>Leading zeros are significant in five fields</h2>
 * The identifier, the national identifier, the government-issued identifier, the
 * electronic-funds-transfer account identifier and the credit score all look numeric and none of them
 * is a number. Verified values from the first seeded record are {@code 000000001},
 * {@code 020973888}, {@code 00000000000049368437}, {@code 0053581756} and {@code 274}. Parsing any of
 * them to {@code int} or {@code long} and re-formatting would destroy the leading zeros and with them
 * the byte parity of the output. Every field in this layout is carried as a {@link String} end to
 * end, and no numeric type appears anywhere in this class. No floating-point type appears either.
 *
 * <h2>Values are copied raw; nothing is trimmed, folded or normalised</h2>
 * Trailing and interior spaces are contractual in this estate, so a slice is returned exactly as it
 * appears in the record. Nothing here trims, strips, case-folds, normalises a postal code,
 * normalises a telephone number, translates an indicator to an enumeration or validates a state code.
 * The permitted state codes and the state-and-postal-prefix combinations belong to the
 * validation-lookup service; calendar validity belongs to the date-validation service; the credit
 * score range belongs to the account-update service. This class performs no arithmetic of any kind.
 *
 * <h2>The worked example this mapper is verified against</h2>
 * {@code [app/data/ASCII/custdata.txt]} is 25,050 bytes: fifty records at a 501-byte stride, being
 * the 500-byte record plus one {@code 0x0A} terminator. The terminator is a record separator and is
 * never part of the record, so a caller strips it - or addresses the record inside the whole-file
 * buffer through {@link #fromRecord(byte[], int, UnaryOperator)}, which leaves it behind. Read at
 * offset, the first record yields:
 *
 * <pre>
 *   0  000000001                     249  (908)119-8310  &lt;- two trailing spaces
 *   9  Immanuel                      264  (373)693-8684
 *  34  Madeline                      279  020973888      &lt;- leading zero, stays a String
 *  59  Kessler                       288  00000000000049368437
 *  84  618 Deshaun Route             308  1961-06-08     &lt;- hyphenated value
 * 134  Apt. 802                      318  0053581756
 * 184  Altenwerthshire               328  Y
 * 234  NC                            329  274            &lt;- below 300, accepted
 * 236  USA                           332  168 spaces
 * 239  12546          &lt;- five trailing spaces
 * </pre>
 *
 * <p>Each character field above is space-padded to its declared width in the record and is returned
 * with that padding intact.
 *
 * <h2>Filler, and the exact bound for a round-trip comparison</h2>
 * {@code toRecord} emits the 168-byte filler as spaces. COBOL {@code FILLER X(168)} carries no
 * {@code VALUE} clause and is therefore uninitialised, so no byte value is canonical, and the sample
 * data shows exactly that divergence: the four master fixtures carry space filler while the four
 * reference-table fixtures carry ASCII-zero filler. Space is the module-wide default.
 *
 * <p><strong>Every fixture round-trip assertion for this layout therefore compares only the mapped
 * data prefix, the byte range from 0 inclusive to {@value #MAPPED_DATA_WIDTH} exclusive.</strong>
 * For this particular fixture the filler happens to be spaces in all fifty records, so a whole-record
 * comparison would also pass - but the prefix bound is the rule of the family and is stated here so
 * that a test author does not encode the coincidence instead of the rule.
 *
 * <h2>Identity, versioning and column naming</h2>
 * The JPA identifier is {@code custId}, the legacy business key itself. {@code KEYS(9 0)} places that
 * key at offset zero as the leading substring of the record image, so no surrogate identifier is
 * introduced: a surrogate would sever the record-image-to-row correspondence that byte-level output
 * parity depends on.
 *
 * <p>The customer entity carries <strong>no version attribute</strong>. Only the account and card
 * tables have a version column in this schema, so there is no optimistic-locking field to map here
 * and none is written.
 *
 * <p>Column naming in the entity is <strong>not uniformly prefixed</strong>: only the identifier, the
 * national identifier and the date of birth carry a {@code cust_} prefix, and the remaining fifteen
 * columns do not. That asymmetry is transcribed from the schema migration that owns the table, and
 * regularising it would name columns the schema does not have. It is stated here so that a reviewer
 * reading this mapper alongside the entity does not "correct" it.
 *
 * <h2>Failure contract</h2>
 * A record image whose encoded byte length is anything other than {@value #RECORD_WIDTH} raises
 * {@link IllegalArgumentException} naming the artefact, the expected width and the actual encoded
 * byte length. Input is never silently padded, never truncated, never partially mapped and never
 * returned as {@code null}. Widths are always measured in encoded bytes and never as a character
 * count, so a multi-byte character cannot slip through a width check and shift the geometry.
 *
 * <p>No exception type from this module's own exception package is used for that condition, and the
 * omission is deliberate: none of the six models "the caller handed me the wrong number of bytes".
 * A short or long record has no legacy antecedent at all, because VSAM and QSAM records are fixed
 * length by construction, so the check is a Java-only defensive guard against a caller defect and the
 * platform's own precondition exception is the honest signal. {@code null} arguments raise
 * {@link NullPointerException} through {@link Objects#requireNonNull(Object, String)}, the single
 * exception being the national identifier inside {@code toRecord}, whose absence is a legitimate
 * stored state rather than a defect.
 *
 * <h2>Shape</h2>
 * This class is a static contract rather than a component: it is final, it exposes only static
 * members, it holds no state - mutable or otherwise - beyond compile-time constants, and its single
 * private constructor raises rather than returning. Keeping it uninstantiable keeps this layout
 * single-valued, which matters because two callers holding per-instance copies of an offset table
 * could disagree about a layout the legacy record defines exactly once. Every method is pure and
 * side-effect free: no input or output, no clock, no environment, no randomness and no persistence.
 * It is therefore safe to use from any thread.
 *
 * <h2>Decisions this file raises for the decision log</h2>
 * <ol>
 *   <li>The date-of-birth field is spelled two ways across the two copybooks, hyphenated in
 *       {@code CVCUS01Y} and unhyphenated in {@code CUSTREC}. Resolved as one alternate 500-byte view
 *       of one entity with one mapper and one code path; {@code CBSTM03A} includes the latter, so
 *       both spellings are live.</li>
 *   <li>The unhyphenated field name does not imply an unhyphenated value - the seeded value is
 *       {@code 1961-06-08} - and no parsing or reformatting is performed here.</li>
 *   <li>The two regulated identifiers are sliced exactly as they appear. Sealing and revealing are
 *       the caller's, delegated through a platform functional interface rather than performed here,
 *       because the entity fails closed on cleartext and this layer may hold no key. Already recorded
 *       for the entity and the encryption service; recorded again here because the seam is what makes
 *       the mapper compatible with a fail-closed entity.</li>
 *   <li>{@code toRecord} emits nine spaces for an absent national identifier rather than raising or
 *       zero-filling, because every seeded row is {@code null} and that column is the schema's only
 *       nullable one.</li>
 *   <li>The credit score is not range-validated in the mapper: 21 of the 50 seeded rows fall below
 *       the screen's 300-to-850 range, so validating here would make the reference data
 *       unloadable.</li>
 *   <li>The filler byte is not uniform across the fixtures, so round-trip assertions compare only
 *       the mapped data prefix. Already carried as anomaly 20 and its resolution.</li>
 *   <li>Malformed fixed-width input raises {@link IllegalArgumentException} rather than a module
 *       exception type, because a short record has no legacy antecedent. Already recorded for the
 *       fixed-width primitive; it governs this mapper's contract too.</li>
 *   <li>A further source observation, not among the rows the module's anomaly register currently
 *       carries: lines 6 through 22 of {@code [app/cpy/CUSTREC.cpy]} are indented with literal tab
 *       characters where its sibling copybook uses spaces. It affects that member's own source
 *       formatting only - no field, no offset, no width and no record byte - and the legacy tree is
 *       left byte-identical, so nothing is corrected. Recorded so that a reviewer diffing the two
 *       copybooks reads the whitespace difference as known rather than as a transcription error.</li>
 * </ol>
 *
 * <p><strong>Provenance.</strong> Translated from the read-only legacy estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which appears in the trailer comment of both
 * source copybooks. No legacy source text is reproduced in this module: members, field names, widths,
 * offsets and values are cited by reference only.
 */
public final class CustomerRecordMapper {

    /**
     * Layout name reported by every diagnostic this mapper and its slicing primitive raise.
     *
     * <p>Both copybook names are carried in the label rather than only the record name, because the
     * two are alternate views of the same 500 bytes and a diagnostic that named just one would send
     * a reader to the wrong member half the time. The label is never parsed and never selects
     * behaviour; it exists so that a message identifies its subject.
     */
    public static final String ARTEFACT = "CUSTOMER-RECORD (CVCUS01Y/CUSTREC)";

    /**
     * The date-of-birth field name as {@code [app/cpy/CVCUS01Y.cpy]} spells it, with hyphens between
     * the date parts. Published so the dual naming is auditable in code; it denotes the same ten
     * bytes as {@link #CUST_DOB_FIELD_CUSTREC}.
     */
    public static final String CUST_DOB_FIELD_CVCUS01Y = "CUST-DOB-YYYY-MM-DD";

    /**
     * The date-of-birth field name as {@code [app/cpy/CUSTREC.cpy]} spells it, without hyphens. This
     * is the spelling the statement-generation program sees, because
     * {@code [app/cbl/CBSTM03A.CBL]} includes that copybook. It denotes the same ten bytes at the
     * same offset as {@link #CUST_DOB_FIELD_CVCUS01Y}, which is why one mapper serves both and no
     * branch exists on the spelling.
     */
    public static final String CUST_DOB_FIELD_CUSTREC = "CUST-DOB-YYYYMMDD";

    /** Offset of the customer identifier, {@code PIC 9(09)}: 0, and the record key at {@code KEYS(9 0)}. */
    public static final int CUST_ID_OFFSET = 0;

    /** Length of the customer identifier: 9. */
    public static final int CUST_ID_LENGTH = 9;

    /** Offset of the given name, {@code PIC X(25)}: 9. */
    public static final int FIRST_NAME_OFFSET = CUST_ID_OFFSET + CUST_ID_LENGTH;

    /** Length of the given name: 25. */
    public static final int FIRST_NAME_LENGTH = 25;

    /** Offset of the middle name, {@code PIC X(25)}: 34. */
    public static final int MIDDLE_NAME_OFFSET = FIRST_NAME_OFFSET + FIRST_NAME_LENGTH;

    /** Length of the middle name: 25. */
    public static final int MIDDLE_NAME_LENGTH = 25;

    /** Offset of the family name, {@code PIC X(25)}: 59. */
    public static final int LAST_NAME_OFFSET = MIDDLE_NAME_OFFSET + MIDDLE_NAME_LENGTH;

    /** Length of the family name: 25. */
    public static final int LAST_NAME_LENGTH = 25;

    /** Offset of the first address line, {@code PIC X(50)}: 84. */
    public static final int ADDR_LINE_1_OFFSET = LAST_NAME_OFFSET + LAST_NAME_LENGTH;

    /** Length of the first address line: 50. */
    public static final int ADDR_LINE_1_LENGTH = 50;

    /** Offset of the second address line, {@code PIC X(50)}: 134. */
    public static final int ADDR_LINE_2_OFFSET = ADDR_LINE_1_OFFSET + ADDR_LINE_1_LENGTH;

    /** Length of the second address line: 50. */
    public static final int ADDR_LINE_2_LENGTH = 50;

    /** Offset of the third address line, {@code PIC X(50)}: 184. */
    public static final int ADDR_LINE_3_OFFSET = ADDR_LINE_2_OFFSET + ADDR_LINE_2_LENGTH;

    /** Length of the third address line: 50. */
    public static final int ADDR_LINE_3_LENGTH = 50;

    /** Offset of the state code, {@code PIC X(02)}: 234. */
    public static final int ADDR_STATE_CD_OFFSET = ADDR_LINE_3_OFFSET + ADDR_LINE_3_LENGTH;

    /** Length of the state code: 2. */
    public static final int ADDR_STATE_CD_LENGTH = 2;

    /** Offset of the country code, {@code PIC X(03)}: 236. */
    public static final int ADDR_COUNTRY_CD_OFFSET = ADDR_STATE_CD_OFFSET + ADDR_STATE_CD_LENGTH;

    /** Length of the country code: 3. */
    public static final int ADDR_COUNTRY_CD_LENGTH = 3;

    /** Offset of the postal code, {@code PIC X(10)}: 239. */
    public static final int ADDR_ZIP_OFFSET = ADDR_COUNTRY_CD_OFFSET + ADDR_COUNTRY_CD_LENGTH;

    /** Length of the postal code: 10. */
    public static final int ADDR_ZIP_LENGTH = 10;

    /** Offset of the primary telephone number, {@code PIC X(15)}: 249. */
    public static final int PHONE_NUM_1_OFFSET = ADDR_ZIP_OFFSET + ADDR_ZIP_LENGTH;

    /** Length of the primary telephone number: 15. */
    public static final int PHONE_NUM_1_LENGTH = 15;

    /** Offset of the secondary telephone number, {@code PIC X(15)}: 264. */
    public static final int PHONE_NUM_2_OFFSET = PHONE_NUM_1_OFFSET + PHONE_NUM_1_LENGTH;

    /** Length of the secondary telephone number: 15. */
    public static final int PHONE_NUM_2_LENGTH = 15;

    /**
     * Offset of the national identifier, {@code PIC 9(09)}: 279. Nine bytes in the record image
     * regardless of how wide the column behind them is.
     */
    public static final int CUST_SSN_OFFSET = PHONE_NUM_2_OFFSET + PHONE_NUM_2_LENGTH;

    /** Length of the national identifier: 9. */
    public static final int CUST_SSN_LENGTH = 9;

    /** Offset of the government-issued identifier, {@code PIC X(20)}: 288. */
    public static final int GOVT_ISSUED_ID_OFFSET = CUST_SSN_OFFSET + CUST_SSN_LENGTH;

    /** Length of the government-issued identifier: 20. */
    public static final int GOVT_ISSUED_ID_LENGTH = 20;

    /**
     * Offset of the date of birth, {@code PIC X(10)}: 308. One offset for both copybook spellings,
     * {@link #CUST_DOB_FIELD_CVCUS01Y} and {@link #CUST_DOB_FIELD_CUSTREC}.
     */
    public static final int CUST_DOB_OFFSET = GOVT_ISSUED_ID_OFFSET + GOVT_ISSUED_ID_LENGTH;

    /** Length of the date of birth: 10. */
    public static final int CUST_DOB_LENGTH = 10;

    /** Offset of the electronic-funds-transfer account identifier, {@code PIC X(10)}: 318. */
    public static final int EFT_ACCOUNT_ID_OFFSET = CUST_DOB_OFFSET + CUST_DOB_LENGTH;

    /** Length of the electronic-funds-transfer account identifier: 10. */
    public static final int EFT_ACCOUNT_ID_LENGTH = 10;

    /** Offset of the primary-cardholder indicator, {@code PIC X(01)}: 328. */
    public static final int PRI_CARD_HOLDER_IND_OFFSET = EFT_ACCOUNT_ID_OFFSET + EFT_ACCOUNT_ID_LENGTH;

    /** Length of the primary-cardholder indicator: 1. */
    public static final int PRI_CARD_HOLDER_IND_LENGTH = 1;

    /** Offset of the credit score, {@code PIC 9(03)}: 329. Never range-validated by this mapper. */
    public static final int FICO_CREDIT_SCORE_OFFSET =
            PRI_CARD_HOLDER_IND_OFFSET + PRI_CARD_HOLDER_IND_LENGTH;

    /** Length of the credit score: 3. */
    public static final int FICO_CREDIT_SCORE_LENGTH = 3;

    /**
     * Total width of the mapped data prefix: 332 bytes, being the sum of the eighteen field lengths.
     *
     * <p>Derived from the last field's offset plus its length rather than written as a literal, so it
     * cannot disagree with the offsets above. This is also the exclusive upper bound for a fixture
     * round-trip comparison, because the filler that follows it is not uniform across the sample
     * data.
     */
    public static final int MAPPED_DATA_WIDTH = FICO_CREDIT_SCORE_OFFSET + FICO_CREDIT_SCORE_LENGTH;

    /** Offset at which the trailing filler begins: 332, immediately after the mapped data prefix. */
    public static final int FILLER_OFFSET = MAPPED_DATA_WIDTH;

    /** Length of the trailing {@code FILLER X(168)}: 168. Not mapped and not persisted. */
    public static final int FILLER_LENGTH = 168;

    /**
     * Record width in encoded bytes: 500, derived as {@code 332 + 168}. Corroborated independently by
     * {@code RECORDSIZE(500 500)} in {@code [app/jcl/CUSTFILE.jcl]} and by the record-length note in
     * both copybook headers.
     */
    public static final int RECORD_WIDTH = MAPPED_DATA_WIDTH + FILLER_LENGTH;

    /** Legacy name of the customer identifier field, used only in diagnostics. */
    private static final String CUST_ID_FIELD = "CUST-ID";

    /** Legacy name of the given-name field, used only in diagnostics. */
    private static final String FIRST_NAME_FIELD = "CUST-FIRST-NAME";

    /** Legacy name of the middle-name field, used only in diagnostics. */
    private static final String MIDDLE_NAME_FIELD = "CUST-MIDDLE-NAME";

    /** Legacy name of the family-name field, used only in diagnostics. */
    private static final String LAST_NAME_FIELD = "CUST-LAST-NAME";

    /** Legacy name of the first address line, used only in diagnostics. */
    private static final String ADDR_LINE_1_FIELD = "CUST-ADDR-LINE-1";

    /** Legacy name of the second address line, used only in diagnostics. */
    private static final String ADDR_LINE_2_FIELD = "CUST-ADDR-LINE-2";

    /** Legacy name of the third address line, used only in diagnostics. */
    private static final String ADDR_LINE_3_FIELD = "CUST-ADDR-LINE-3";

    /** Legacy name of the state-code field, used only in diagnostics. */
    private static final String ADDR_STATE_CD_FIELD = "CUST-ADDR-STATE-CD";

    /** Legacy name of the country-code field, used only in diagnostics. */
    private static final String ADDR_COUNTRY_CD_FIELD = "CUST-ADDR-COUNTRY-CD";

    /** Legacy name of the postal-code field, used only in diagnostics. */
    private static final String ADDR_ZIP_FIELD = "CUST-ADDR-ZIP";

    /** Legacy name of the primary telephone field, used only in diagnostics. */
    private static final String PHONE_NUM_1_FIELD = "CUST-PHONE-NUM-1";

    /** Legacy name of the secondary telephone field, used only in diagnostics. */
    private static final String PHONE_NUM_2_FIELD = "CUST-PHONE-NUM-2";

    /** Legacy name of the national-identifier field, used only in diagnostics; never its value. */
    private static final String CUST_SSN_FIELD = "CUST-SSN";

    /** Legacy name of the government-issued-identifier field, used only in diagnostics. */
    private static final String GOVT_ISSUED_ID_FIELD = "CUST-GOVT-ISSUED-ID";

    /** Legacy name of the electronic-funds-transfer account identifier, used only in diagnostics. */
    private static final String EFT_ACCOUNT_ID_FIELD = "CUST-EFT-ACCOUNT-ID";

    /** Legacy name of the primary-cardholder indicator, used only in diagnostics. */
    private static final String PRI_CARD_HOLDER_IND_FIELD = "CUST-PRI-CARD-HOLDER-IND";

    /** Legacy name of the credit-score field, used only in diagnostics. */
    private static final String FICO_CREDIT_SCORE_FIELD = "CUST-FICO-CREDIT-SCORE";

    /**
     * The empty value, placed into the national-identifier field when no identifier is held so that
     * the field emerges as {@value #CUST_SSN_LENGTH} spaces.
     *
     * <p>Named rather than written inline at the call site because an empty literal there reads like
     * an oversight, when it is in fact the deliberate rendering of an absent value: alphanumeric
     * placement pads to the field width with spaces, so an empty value produces a field of spaces -
     * exactly how COBOL renders an unset field - instead of the zero-filled digits that numeric
     * placement would produce.
     */
    private static final String ABSENT_VALUE = "";

    /** Not instantiable: this class exposes static members only and holds no state. */
    private CustomerRecordMapper() {
        throw new AssertionError("CustomerRecordMapper is a static utility and is not instantiable");
    }

    /**
     * Maps a complete 500-byte customer record image, supplied as a string, to a fully populated
     * {@link Customer}.
     *
     * <p>Every character field is carried across exactly as it appears in the record: untrimmed, not
     * case-folded and not normalised in any way. The two regulated identifiers are sliced exactly as
     * they appear and then handed to {@code regulatedFieldSealer}, because the entity stores those two
     * fields only in the module's protected-value envelope and this layer may hold no key. Nothing
     * else on the record passes through that operation.
     *
     * <p>The image must carry no line terminator. The sample fixture is newline-terminated with a
     * 501-byte stride, so a caller reading it line by line must exclude the terminating byte, or use
     * {@link #fromRecord(byte[], int, UnaryOperator)} to address a record inside a whole-file buffer
     * and leave the terminator behind.
     *
     * @param recordImage           the complete record image, exactly {@value #RECORD_WIDTH} encoded
     *                              bytes, with no line terminator
     * @param regulatedFieldSealer  seals a cleartext value into the module's protected-value
     *                              envelope; applied to the national identifier and the
     *                              government-issued identifier and to nothing else, and must not
     *                              return {@code null}
     * @return a fully populated customer, never {@code null} and never partially mapped
     * @throws NullPointerException     if {@code recordImage} or {@code regulatedFieldSealer} is
     *                                  {@code null}
     * @throws IllegalArgumentException if the image's encoded byte length is not exactly
     *                                  {@value #RECORD_WIDTH}, if it contains a character US-ASCII
     *                                  cannot represent, or if {@code regulatedFieldSealer} returns
     *                                  {@code null} or a value the entity refuses
     */
    public static Customer fromRecord(String recordImage, UnaryOperator<String> regulatedFieldSealer) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        Objects.requireNonNull(regulatedFieldSealer, "regulatedFieldSealer must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_WIDTH), regulatedFieldSealer);
    }

    /**
     * Maps a complete 500-byte customer record image, supplied as bytes, to a fully populated
     * {@link Customer}.
     *
     * <p>Preferred over {@link #fromRecord(String, UnaryOperator)} when the caller already holds raw
     * bytes, because it removes any need for the caller to choose a charset: the bytes are verified
     * to be 7-bit ASCII and decoded as US-ASCII by the slicing primitive, so no platform-default
     * conversion exists anywhere on the path.
     *
     * @param recordImage           the complete record image as bytes, exactly
     *                              {@value #RECORD_WIDTH} bytes, with no line terminator
     * @param regulatedFieldSealer  seals a cleartext value into the module's protected-value
     *                              envelope; applied to the two regulated identifiers only, and must
     *                              not return {@code null}
     * @return a fully populated customer, never {@code null} and never partially mapped
     * @throws NullPointerException     if {@code recordImage} or {@code regulatedFieldSealer} is
     *                                  {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_WIDTH} bytes, if
     *                                  any byte is not 7-bit ASCII, or if
     *                                  {@code regulatedFieldSealer} returns {@code null} or a value
     *                                  the entity refuses
     */
    public static Customer fromRecord(byte[] recordImage, UnaryOperator<String> regulatedFieldSealer) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        Objects.requireNonNull(regulatedFieldSealer, "regulatedFieldSealer must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_WIDTH), regulatedFieldSealer);
    }

    /**
     * Maps one 500-byte customer record held inside a larger byte buffer to a fully populated
     * {@link Customer}.
     *
     * <p>This is the seam for a caller that has read a whole newline-terminated fixed-width file into
     * a single buffer. The stride of such a file is {@code RECORD_WIDTH + 1}, so record <em>i</em> of
     * {@code [app/data/ASCII/custdata.txt]} is addressed as
     * {@code fromRecord(buffer, i * (RECORD_WIDTH + 1), sealer)}, which selects the record and leaves
     * the terminating byte behind. Stride arithmetic and file access stay with the caller; this class
     * never performs input or output.
     *
     * @param buffer                buffer containing the record, and possibly many others
     * @param from                  zero-based index in {@code buffer} at which the record starts
     * @param regulatedFieldSealer  seals a cleartext value into the module's protected-value
     *                              envelope; applied to the two regulated identifiers only, and must
     *                              not return {@code null}
     * @return a fully populated customer, never {@code null} and never partially mapped
     * @throws NullPointerException     if {@code buffer} or {@code regulatedFieldSealer} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the range
     *                                  {@code [from, from + RECORD_WIDTH)} is not wholly inside
     *                                  {@code buffer}, if any byte in that range is not 7-bit ASCII,
     *                                  or if {@code regulatedFieldSealer} returns {@code null} or a
     *                                  value the entity refuses
     */
    public static Customer fromRecord(byte[] buffer, int from,
            UnaryOperator<String> regulatedFieldSealer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        Objects.requireNonNull(regulatedFieldSealer, "regulatedFieldSealer must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_WIDTH),
                regulatedFieldSealer);
    }

    /**
     * Composes a complete 500-byte customer record image, as a string, from a {@link Customer}.
     *
     * <p>The exact inverse of {@code fromRecord}. Each field is placed at its own offset by the
     * justification its picture clause declares: unsigned external decimal right-justified and
     * zero-filled, character data left-justified and space-padded. The 168-byte trailing filler is
     * emitted as spaces.
     *
     * <p>The national identifier is the one field that may legitimately be absent, and an absent
     * value is rendered as {@value #CUST_SSN_LENGTH} spaces without the revealing operation being
     * consulted. Every other attribute is required, because every other column is not nullable, and a
     * {@code null} there is reported as a customer the record image cannot represent.
     *
     * <p>The returned image carries no line terminator. A caller writing a newline-terminated file
     * appends the separator itself.
     *
     * @param customer                the customer to render; every attribute other than the national
     *                                identifier must be present
     * @param regulatedFieldRevealer  recovers the cleartext a record image requires from the stored
     *                                protected-value envelope; applied to the two regulated
     *                                identifiers only, never to an absent national identifier, and
     *                                must not return {@code null}
     * @return the complete record image, exactly {@value #RECORD_WIDTH} characters, never
     *         {@code null}
     * @throws NullPointerException     if {@code customer} or {@code regulatedFieldRevealer} is
     *                                  {@code null}
     * @throws IllegalArgumentException if any required attribute is {@code null}, if
     *                                  {@code regulatedFieldRevealer} returns {@code null}, or if any
     *                                  value is wider than its field or is not representable in
     *                                  US-ASCII
     */
    public static String toRecord(Customer customer, UnaryOperator<String> regulatedFieldRevealer) {
        return compose(customer, regulatedFieldRevealer).image();
    }

    /**
     * Composes a complete 500-byte customer record image, as bytes, from a {@link Customer}.
     *
     * <p>Identical in every respect to {@link #toRecord(Customer, UnaryOperator)} except that the
     * image is returned as a fresh, caller-owned byte array, which is what a fixed-width writer
     * wants. The encoding is US-ASCII, named explicitly by the slicing primitive rather than inherited
     * from the platform default.
     *
     * @param customer                the customer to render; every attribute other than the national
     *                                identifier must be present
     * @param regulatedFieldRevealer  recovers the cleartext a record image requires from the stored
     *                                protected-value envelope; applied to the two regulated
     *                                identifiers only, never to an absent national identifier, and
     *                                must not return {@code null}
     * @return a new array of exactly {@value #RECORD_WIDTH} bytes, never {@code null}
     * @throws NullPointerException     if {@code customer} or {@code regulatedFieldRevealer} is
     *                                  {@code null}
     * @throws IllegalArgumentException if any required attribute is {@code null}, if
     *                                  {@code regulatedFieldRevealer} returns {@code null}, or if any
     *                                  value is wider than its field or is not representable in
     *                                  US-ASCII
     */
    public static byte[] toRecordBytes(Customer customer,
            UnaryOperator<String> regulatedFieldRevealer) {
        return compose(customer, regulatedFieldRevealer).toByteArray();
    }

    /**
     * Slices an already validated record image into the eighteen attributes and constructs the
     * customer from them.
     *
     * <p>The entity's eighteen-argument constructor is used rather than the mutators, and that is not
     * a stylistic preference: the entity's no-argument constructor is {@code protected} and therefore
     * unreachable from this package, and the constructor's parameter order is exactly the record's
     * field order, so the argument list below reads as the layout itself and a transposed pair would
     * be visible at a glance. Values are stored verbatim by that constructor; nothing is trimmed,
     * padded, folded or reformatted on either side of the boundary.
     *
     * <p>Only the two regulated identifiers pass through the sealing operation. The date of birth is
     * sliced under the hyphenated field name for diagnostics, which is a naming choice and nothing
     * more: the unhyphenated copybook spells the same ten bytes at the same offset, so there is one
     * slice, one attribute and one code path.
     *
     * @param record                the validated record image
     * @param regulatedFieldSealer  seals a cleartext regulated value into the protected-value envelope
     * @return the fully populated customer
     */
    private static Customer map(FixedWidthFieldReader record,
            UnaryOperator<String> regulatedFieldSealer) {
        return new Customer(
                record.field(CUST_ID_FIELD, CUST_ID_OFFSET, CUST_ID_LENGTH),
                record.field(FIRST_NAME_FIELD, FIRST_NAME_OFFSET, FIRST_NAME_LENGTH),
                record.field(MIDDLE_NAME_FIELD, MIDDLE_NAME_OFFSET, MIDDLE_NAME_LENGTH),
                record.field(LAST_NAME_FIELD, LAST_NAME_OFFSET, LAST_NAME_LENGTH),
                record.field(ADDR_LINE_1_FIELD, ADDR_LINE_1_OFFSET, ADDR_LINE_1_LENGTH),
                record.field(ADDR_LINE_2_FIELD, ADDR_LINE_2_OFFSET, ADDR_LINE_2_LENGTH),
                record.field(ADDR_LINE_3_FIELD, ADDR_LINE_3_OFFSET, ADDR_LINE_3_LENGTH),
                record.field(ADDR_STATE_CD_FIELD, ADDR_STATE_CD_OFFSET, ADDR_STATE_CD_LENGTH),
                record.field(ADDR_COUNTRY_CD_FIELD, ADDR_COUNTRY_CD_OFFSET, ADDR_COUNTRY_CD_LENGTH),
                record.field(ADDR_ZIP_FIELD, ADDR_ZIP_OFFSET, ADDR_ZIP_LENGTH),
                record.field(PHONE_NUM_1_FIELD, PHONE_NUM_1_OFFSET, PHONE_NUM_1_LENGTH),
                record.field(PHONE_NUM_2_FIELD, PHONE_NUM_2_OFFSET, PHONE_NUM_2_LENGTH),
                seal(regulatedFieldSealer, CUST_SSN_FIELD,
                        record.field(CUST_SSN_FIELD, CUST_SSN_OFFSET, CUST_SSN_LENGTH)),
                seal(regulatedFieldSealer, GOVT_ISSUED_ID_FIELD,
                        record.field(GOVT_ISSUED_ID_FIELD, GOVT_ISSUED_ID_OFFSET,
                                GOVT_ISSUED_ID_LENGTH)),
                record.field(CUST_DOB_FIELD_CVCUS01Y, CUST_DOB_OFFSET, CUST_DOB_LENGTH),
                record.field(EFT_ACCOUNT_ID_FIELD, EFT_ACCOUNT_ID_OFFSET, EFT_ACCOUNT_ID_LENGTH),
                record.field(PRI_CARD_HOLDER_IND_FIELD, PRI_CARD_HOLDER_IND_OFFSET,
                        PRI_CARD_HOLDER_IND_LENGTH),
                record.field(FICO_CREDIT_SCORE_FIELD, FICO_CREDIT_SCORE_OFFSET,
                        FICO_CREDIT_SCORE_LENGTH));
    }

    /**
     * Places all eighteen attributes and the trailing filler into a record image.
     *
     * <p>Placements are made in record order so that this method reads as the layout, even though
     * placement is positional and absolute and the order is therefore free. Each field's justification
     * follows its picture clause: the identifier, the national identifier and the credit score are
     * unsigned external decimal and are placed right-justified and zero-filled, and the remaining
     * fifteen are character data and are placed left-justified and space-padded. Two of those fifteen
     * hold values that merely look numeric - the postal code and the electronic-funds-transfer account
     * identifier - and both keep character justification, because the copybook declares them as
     * character data and the record image is the contract rather than the value's appearance.
     *
     * @param customer                the customer to render
     * @param regulatedFieldRevealer  recovers a regulated field's cleartext from its stored envelope
     * @return the completed record image
     */
    private static FixedWidthFieldReader compose(Customer customer,
            UnaryOperator<String> regulatedFieldRevealer) {
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(regulatedFieldRevealer, "regulatedFieldRevealer must not be null");

        FixedWidthFieldReader.Builder image = FixedWidthFieldReader.builder(ARTEFACT, RECORD_WIDTH);

        image.putNumeric(CUST_ID_FIELD, CUST_ID_OFFSET, CUST_ID_LENGTH,
                requirePresent(customer.getCustId(), "custId", CUST_ID_FIELD));
        image.putAlphanumeric(FIRST_NAME_FIELD, FIRST_NAME_OFFSET, FIRST_NAME_LENGTH,
                requirePresent(customer.getFirstName(), "firstName", FIRST_NAME_FIELD));
        image.putAlphanumeric(MIDDLE_NAME_FIELD, MIDDLE_NAME_OFFSET, MIDDLE_NAME_LENGTH,
                requirePresent(customer.getMiddleName(), "middleName", MIDDLE_NAME_FIELD));
        image.putAlphanumeric(LAST_NAME_FIELD, LAST_NAME_OFFSET, LAST_NAME_LENGTH,
                requirePresent(customer.getLastName(), "lastName", LAST_NAME_FIELD));
        image.putAlphanumeric(ADDR_LINE_1_FIELD, ADDR_LINE_1_OFFSET, ADDR_LINE_1_LENGTH,
                requirePresent(customer.getAddrLine1(), "addrLine1", ADDR_LINE_1_FIELD));
        image.putAlphanumeric(ADDR_LINE_2_FIELD, ADDR_LINE_2_OFFSET, ADDR_LINE_2_LENGTH,
                requirePresent(customer.getAddrLine2(), "addrLine2", ADDR_LINE_2_FIELD));
        image.putAlphanumeric(ADDR_LINE_3_FIELD, ADDR_LINE_3_OFFSET, ADDR_LINE_3_LENGTH,
                requirePresent(customer.getAddrLine3(), "addrLine3", ADDR_LINE_3_FIELD));
        image.putAlphanumeric(ADDR_STATE_CD_FIELD, ADDR_STATE_CD_OFFSET, ADDR_STATE_CD_LENGTH,
                requirePresent(customer.getAddrStateCd(), "addrStateCd", ADDR_STATE_CD_FIELD));
        image.putAlphanumeric(ADDR_COUNTRY_CD_FIELD, ADDR_COUNTRY_CD_OFFSET, ADDR_COUNTRY_CD_LENGTH,
                requirePresent(customer.getAddrCountryCd(), "addrCountryCd", ADDR_COUNTRY_CD_FIELD));
        image.putAlphanumeric(ADDR_ZIP_FIELD, ADDR_ZIP_OFFSET, ADDR_ZIP_LENGTH,
                requirePresent(customer.getAddrZip(), "addrZip", ADDR_ZIP_FIELD));
        image.putAlphanumeric(PHONE_NUM_1_FIELD, PHONE_NUM_1_OFFSET, PHONE_NUM_1_LENGTH,
                requirePresent(customer.getPhoneNum1(), "phoneNum1", PHONE_NUM_1_FIELD));
        image.putAlphanumeric(PHONE_NUM_2_FIELD, PHONE_NUM_2_OFFSET, PHONE_NUM_2_LENGTH,
                requirePresent(customer.getPhoneNum2(), "phoneNum2", PHONE_NUM_2_FIELD));

        // The only attribute that may legitimately be absent. An absent national identifier is
        // rendered as a field of spaces - COBOL's rendering of an unset field - and the revealing
        // operation is not consulted at all, because there is no envelope to open. Numeric placement
        // is deliberately not used here: it would zero-fill and so fabricate a nine-digit value that
        // was never held. Every seeded row stores no identifier, so this is the common path and not an
        // edge case.
        String heldNationalIdentifier = customer.getCustSsn();
        if (heldNationalIdentifier == null) {
            image.putAlphanumeric(CUST_SSN_FIELD, CUST_SSN_OFFSET, CUST_SSN_LENGTH, ABSENT_VALUE);
        } else {
            image.putNumeric(CUST_SSN_FIELD, CUST_SSN_OFFSET, CUST_SSN_LENGTH,
                    reveal(regulatedFieldRevealer, CUST_SSN_FIELD, heldNationalIdentifier));
        }

        image.putAlphanumeric(GOVT_ISSUED_ID_FIELD, GOVT_ISSUED_ID_OFFSET, GOVT_ISSUED_ID_LENGTH,
                reveal(regulatedFieldRevealer, GOVT_ISSUED_ID_FIELD,
                        requirePresent(customer.getGovtIssuedId(), "govtIssuedId",
                                GOVT_ISSUED_ID_FIELD)));
        image.putAlphanumeric(CUST_DOB_FIELD_CVCUS01Y, CUST_DOB_OFFSET, CUST_DOB_LENGTH,
                requirePresent(customer.getCustDob(), "custDob", CUST_DOB_FIELD_CVCUS01Y));
        image.putAlphanumeric(EFT_ACCOUNT_ID_FIELD, EFT_ACCOUNT_ID_OFFSET, EFT_ACCOUNT_ID_LENGTH,
                requirePresent(customer.getEftAccountId(), "eftAccountId", EFT_ACCOUNT_ID_FIELD));
        image.putAlphanumeric(PRI_CARD_HOLDER_IND_FIELD, PRI_CARD_HOLDER_IND_OFFSET,
                PRI_CARD_HOLDER_IND_LENGTH, requirePresent(customer.getPriCardHolderInd(),
                        "priCardHolderInd", PRI_CARD_HOLDER_IND_FIELD));
        image.putNumeric(FICO_CREDIT_SCORE_FIELD, FICO_CREDIT_SCORE_OFFSET, FICO_CREDIT_SCORE_LENGTH,
                requirePresent(customer.getFicoCreditScore(), "ficoCreditScore",
                        FICO_CREDIT_SCORE_FIELD));

        // Named rather than left to the builder's space-initialised buffer, so that the placements
        // above plus this run account for all 500 bytes and a forgotten field would be visible as a
        // gap in the arithmetic.
        image.putSpaceFiller(FILLER_OFFSET, FILLER_LENGTH);

        return image.build();
    }

    /**
     * Seals a regulated field's cleartext slice by delegating to the caller's operation.
     *
     * <p>This method performs no cryptography of any kind: it invokes the operation the caller
     * supplied and checks only that a value came back. The check exists because the entity would
     * otherwise refuse the {@code null} with a message about its own attribute, several frames away
     * from the operation that actually failed.
     *
     * @param regulatedFieldSealer the caller's sealing operation
     * @param fieldName            legacy field name, named in any diagnostic
     * @param cleartext            the raw slice, exactly as it appears in the record image
     * @return the sealed value
     * @throws IllegalArgumentException if the sealing operation returns {@code null}
     */
    private static String seal(UnaryOperator<String> regulatedFieldSealer, String fieldName,
            String cleartext) {
        String sealed = regulatedFieldSealer.apply(cleartext);
        if (sealed == null) {
            throw new IllegalArgumentException("the sealing operation returned no value for "
                    + ARTEFACT + " field '" + fieldName + "'; a regulated value must be sealed into"
                    + " the module's protected-value envelope, never dropped");
        }
        return sealed;
    }

    /**
     * Recovers a regulated field's cleartext by delegating to the caller's operation.
     *
     * <p>The inverse of {@link #seal(UnaryOperator, String, String)}, and equally free of
     * cryptography. It is never called for an absent national identifier, so the operation never sees
     * a {@code null} argument from here.
     *
     * @param regulatedFieldRevealer the caller's revealing operation
     * @param fieldName              legacy field name, named in any diagnostic
     * @param envelope               the stored protected value
     * @return the recovered cleartext, ready to be placed into the record image
     * @throws IllegalArgumentException if the revealing operation returns {@code null}
     */
    private static String reveal(UnaryOperator<String> regulatedFieldRevealer, String fieldName,
            String envelope) {
        String cleartext = regulatedFieldRevealer.apply(envelope);
        if (cleartext == null) {
            throw new IllegalArgumentException("the revealing operation returned no value for "
                    + ARTEFACT + " field '" + fieldName + "'; a fixed-width field cannot be left"
                    + " unwritten, and a record is never partially composed");
        }
        return cleartext;
    }

    /**
     * Verifies that a required attribute is present before it is placed into the record image.
     *
     * <p>Every column behind this layout except the national identifier is declared not nullable, so
     * an absent value here is a customer the record image cannot represent rather than a value to
     * substitute. Nothing is defaulted, padded or replaced by an empty field, because doing so would
     * emit a syntactically valid record with content that was never held.
     *
     * <p>The message names the Java property and the legacy field and never the value, for the same
     * reason the rest of the module withholds values from diagnostics: an exception message is one of
     * the surfaces most likely to be logged or returned.
     *
     * @param value        the attribute value
     * @param propertyName the Java property name, named in any diagnostic
     * @param fieldName    the legacy field name, named in any diagnostic
     * @return the value, unchanged, when it is present
     * @throws IllegalArgumentException if the value is {@code null}
     */
    private static String requirePresent(String value, String propertyName, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("customer attribute '" + propertyName + "' is absent,"
                    + " so " + ARTEFACT + " field '" + fieldName + "' cannot be composed; every"
                    + " column behind this layout except the national identifier is not nullable");
        }
        return value;
    }
}
