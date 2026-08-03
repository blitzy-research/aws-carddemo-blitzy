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

import java.math.BigDecimal;
import java.util.Objects;

import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.TransactionCategoryBalanceId;

/**
 * Hand-written, reflection-free mapper between the 50-byte transaction-category-balance record
 * {@code TRAN-CAT-BAL-RECORD} [app/cpy/CVTRA01Y.cpy] and the {@link TransactionCategoryBalance}
 * entity, whose identity is the three-component composite {@link TransactionCategoryBalanceId}.
 *
 * <p>This class is the <em>only</em> place in the module where this layout's byte offsets are
 * declared. The entity carries column widths and no offsets; no {@code service}, {@code api},
 * {@code repository}, {@code batch} or {@code config} class may slice a record image, and no
 * {@code substring} call appears here either, because every slice is taken through
 * {@link FixedWidthFieldReader}. The mapping is written by hand with explicit offset arithmetic
 * rather than driven by annotations or a bean-mapping library, because the module's reflection
 * budget is zero and a reflective mapper would spend it (decision D-26).
 *
 * <h2>Record layout</h2>
 *
 * <p>Offsets are zero-based byte positions in the record image; lengths are encoded byte counts,
 * never character counts.
 *
 * <pre>{@code
 * #   COBOL field        PIC           offset  length  Java property
 * -   TRAN-CAT-KEY       (group)            0      17  the @IdClass composite
 * 1     TRANCAT-ACCT-ID  9(11)              0      11  trancatAcctId  (String, @Id)
 * 2     TRANCAT-TYPE-CD  X(02)             11       2  trancatTypeCd  (String, @Id)
 * 3     TRANCAT-CD       9(04)             13       4  trancatCd      (String, @Id)
 * 4   TRAN-CAT-BAL       S9(09)V99         17      11  tranCatBal     (BigDecimal, scale 2)
 * -   FILLER             X(22)             28      22  not mapped, not persisted
 * }</pre>
 *
 * <p><strong>Width arithmetic.</strong> The key is {@code 11 + 2 + 4 = 17}; adding the 11-byte
 * balance gives {@code 28} mapped bytes; adding the 22-byte filler gives the full
 * <strong>50</strong>. Every one of those numbers is asserted by a constant in this class rather
 * than written as a bare literal at a call site. The cluster definition corroborates both figures
 * independently: {@code KEYS(17 0)} declares a 17-byte key at offset zero and
 * {@code RECORDSIZE(50 50)} a fixed 50-byte record [app/jcl/TCATBALF.jcl]. Because the key sits at
 * offset zero it <em>is</em> the leading substring of the record image, so the JPA identity is the
 * natural business key and never a generated surrogate (decision D-29).
 *
 * <h2>The {@code TRAN-CAT-KEY} name collision - 17 bytes here, 6 bytes elsewhere</h2>
 *
 * <p><strong>Two different copybooks declare a group named {@code TRAN-CAT-KEY}, and they are
 * different sizes:</strong>
 *
 * <pre>{@code
 * copybook                       TRAN-CAT-KEY composition                    width
 * app/cpy/CVTRA01Y.cpy (here)    TRANCAT-ACCT-ID 9(11)                    17 bytes
 *                                + TRANCAT-TYPE-CD X(02)
 *                                + TRANCAT-CD      9(04)
 * app/cpy/CVTRA04Y.cpy           TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04)    6 bytes
 * }</pre>
 *
 * <p><strong>The 6-byte key is NOT a prefix of the 17-byte key</strong> - the 17-byte key leads with
 * an 11-byte account identifier that the 6-byte key does not have at all. Conflating them would
 * silently mis-slice both layouts. The two type-and-category pairs sit at completely different
 * offsets: here at 11 and 13, there at 0 and 2. So a 6-byte read against this layout returns the
 * first six digits of an account identifier, and a 17-byte read against that layout runs off the end
 * of its key into a description - and both mistakes produce plausible-looking output rather than an
 * exception. The provisioning jobs attest the two widths independently: {@code KEYS(17 0)}
 * [app/jcl/TCATBALF.jcl] against {@code KEYS(6 0)} [app/jcl/TRANCATG.jcl]. Consequently
 * <strong>no key constant, no key-building helper and no identifier class is ever shared</strong>
 * between this mapper and {@link TranCatRecordMapper}, and no common supertype is introduced to
 * "reuse" the overlapping components. This class's key-width constant is named
 * {@link #ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH} for the three components it actually contains, so it
 * cannot be mistaken for that mapper's {@code TYPE_AND_CATEGORY_KEY_WIDTH}. Decision D-37 records
 * the collision.
 *
 * <h2>The balance field</h2>
 *
 * <p>{@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} under {@code USAGE DISPLAY}: zoned decimal,
 * <strong>11 encoded bytes</strong>, nine integer digits and two decimals, with no separate sign
 * byte. The sign is overpunched into the final digit byte:
 *
 * <pre>{@code
 * digit      0  1  2  3  4  5  6  7  8  9
 * positive   {  A  B  C  D  E  F  G  H  I
 * negative   }  J  K  L  M  N  O  P  Q  R
 * }</pre>
 *
 * <p>Decoding and encoding are delegated in their entirety to {@link ZonedDecimalCodec}, the single
 * point of decimal truth. <strong>This class never calls {@code setScale}, never selects a rounding
 * mode and never performs arithmetic of any kind</strong>; it slices the field and hands the image
 * over. That division of labour is deliberate: this class owns the offset, the codec owns the
 * overpunch convention and the scale, and neither owns both.
 *
 * <p><strong>Truncation, not rounding.</strong> The codec applies {@code RoundingMode.DOWN}, which is
 * mandatory here; {@code RoundingMode.HALF_EVEN} and {@code RoundingMode.HALF_UP} are forbidden. The
 * evidence is that the token {@code ROUNDED} occurs <strong>zero times</strong> across every program
 * and copybook in the estate, and a COBOL arithmetic store without {@code ROUNDED} truncates toward
 * zero. The conventional Java choice would differ by one cent on roughly half of all interest
 * computations, and the difference is invisible to a test written under the same wrong assumption -
 * which is exactly why the policy lives in one place and is recorded as decision D-02. The value is
 * carried as a {@link BigDecimal} of scale 2 and precision 11; no {@code double}, {@code float},
 * {@code Double} or {@code Float} appears anywhere in this class or in the entity. No packed-decimal
 * decoder is needed either, because {@code COMP-3} occurs <strong>zero times</strong> in
 * {@code app/cpy} - every persisted amount in the estate is zoned (decision D-01).
 *
 * <h2>This balance is the interest computation's operand</h2>
 *
 * <p>The accrual multiplies this balance by the disclosure rate and only then divides by the monthly
 * divisor, storing into a {@code PIC S9(09)V99} work field [app/cbl/CBACT04C.cbl]. Because the store
 * truncates, that ordering is load-bearing: dividing the rate first is algebraically identical in
 * exact arithmetic but moves the truncation point and changes the resulting cent, so the expression
 * is never rearranged (decision D-03). <strong>That computation lives in
 * {@code service/InterestCalculationService}, not here.</strong> This mapper decodes the operand and
 * stops - it performs no multiplication, no division, no accumulation and no zero-balance skip, and
 * it never re-scales a decoded balance.
 *
 * <h2>Fixture evidence</h2>
 *
 * <p>The shipped sample file [app/data/ASCII/tcatbal.txt] measures <strong>2,550 bytes = 50 rows at
 * a 51-byte stride</strong>, the extra byte being a {@code 0x0A} terminator. The terminator is a
 * record separator and <strong>never</strong> record content, so a caller strips it before calling
 * (decision D-30); presenting 51 bytes here raises rather than silently truncating. Read at the
 * declared offsets, every row carries an 11-digit account identifier at 0, a 2-character type code
 * at 11, a 4-character category code at 13, the balance image {@code 0000000000} closed by the
 * positive-zero overpunch <code>&#123;</code> at 17 - which decodes to {@code 0.00} on all 50 rows -
 * and 22 filler bytes at 28.
 *
 * <p>Two consequences follow, and both are easy to get wrong. First, because every seeded balance
 * carries the positive-zero overpunch <code>&#123;</code>, <strong>this fixture exercises exactly one
 * of the twenty sign characters</strong>; full overpunch coverage comes from
 * {@code app/data/ASCII/dailytran.txt}, where all twenty appear. Nothing about this fixture implies
 * that negative balances are impossible - the {@code PIC} clause is signed, and both this mapper and
 * the codec handle every sign. Second, those 22 filler bytes are <strong>ASCII zero, not
 * space</strong>.
 *
 * <p><strong>One of the twenty sign characters does not round-trip, by design.</strong> The entity
 * carries the balance as a plain {@link BigDecimal}, and {@link BigDecimal} has no negative zero,
 * so this mapper uses the codec's unsigned-bit entry points: an image whose every digit is zero and
 * whose final byte is the negative-zero overpunch <code>&#125;</code> decodes to zero and re-encodes
 * as the positive-zero <code>&#123;</code>. The arithmetic value is identical either way, and there is
 * no column in which a negative-zero bit could be persisted, so nothing is lost (decision D-04). The
 * collapse is confined to the all-zeros case: an ordinary negative amount whose cent digit happens to
 * be zero, such as {@code -1.00}, ends in <code>&#125;</code> and re-encodes to <code>&#125;</code>
 * unchanged. A byte-parity assertion over this field must therefore expect positive zero for an
 * all-zero image, which is what every one of the 50 fixture rows already carries.
 *
 * <h2>Filler, and the exact comparison bound {@code [0, 28)}</h2>
 *
 * <p>{@link #toRecord(TransactionCategoryBalance)} emits the filler run as <strong>spaces</strong>.
 * COBOL {@code FILLER X(22)} with no {@code VALUE} clause is uninitialised, so no byte value is
 * canonical, and this layout is one of the four whose fixture disagrees with the module-wide choice:
 * the four master fixtures carry space filler while the four reference-table fixtures - this one
 * among them - carry ASCII zero. Space is the module default and is stated explicitly at the call
 * site rather than inherited silently, so the choice is visible where the filler is written
 * (decision D-10, anomaly 20).
 *
 * <p><strong>Therefore every fixture round-trip assertion for this layout compares only the mapped
 * data prefix {@code [0, 28)}</strong> - that is, from zero up to but excluding
 * {@link #MAPPED_PREFIX_LENGTH}, and nothing beyond it. <strong>A whole-record 50-byte round-trip
 * comparison against {@code tcatbal.txt} WILL FAIL</strong>, because the fixture's 22 filler bytes
 * are {@code '0'} while this mapper emits spaces. That failure is a filler artefact and not a
 * mapping defect, and the bound exists precisely to prevent it being reported as one.
 *
 * <h2>Column naming is deliberately inconsistent</h2>
 *
 * <p>The three key columns use the {@code trancat_} prefix while the balance column is
 * {@code tran_cat_bal}, with underscores between all three tokens. That is not a defect to correct:
 * the copybook itself mixes {@code TRANCAT-} and {@code TRAN-CAT-} prefixes inside this one record,
 * and both spellings are transcribed exactly rather than regularised, because a column name is part
 * of the mapping validated against the migrated schema at start-up (decision D-37). The Java
 * property names follow the same split - {@code trancatAcctId} against {@code tranCatBal} - and the
 * class name itself is the abbreviated {@code TranCatBalRecordMapper}.
 *
 * <h2>Leading zeros are significant</h2>
 *
 * <p>The account identifier {@code 9(11)}, the type code {@code X(02)} and the category code
 * {@code 9(04)} are right-justified, zero-filled character data rather than integers - the fixture's
 * first row carries {@code 00000000001}, {@code 01} and {@code 0001}. None of the three is ever
 * parsed to {@code int} or {@code long} and re-formatted; each is carried as a {@link String} end to
 * end, and the encode direction pads rather than converts. Character fields are copied raw: nothing
 * here trims, strips, folds case, normalises padding or validates content.
 *
 * <h2>Identity, versioning and referential integrity</h2>
 *
 * <p>The entity is annotated {@code @IdClass(TransactionCategoryBalanceId.class)} with all
 * <strong>three</strong> key components annotated {@code @Id} on the entity itself, so the
 * components live on the entity and there is no identifier field to assign. This mapper therefore
 * supplies the three components individually and <strong>never attempts to set an identifier object
 * onto the entity</strong>; {@link #keyFromRecord(String)} constructs a
 * {@link TransactionCategoryBalanceId} only as a standalone projection for a caller that wants to
 * address a row.
 *
 * <p>The entity declares <strong>no {@code @Version} field</strong>, so this mapper neither reads nor
 * writes an optimistic-locking token. Nothing in this class assumes a referential relationship
 * either: it performs no account existence check and no type or category lookup. The schema defines
 * no foreign key from a category-balance row to any reference table, because the source treats type
 * and category codes as classification lexemes carried on the record rather than as values gated by
 * a parent table (decision D-38). The one referential rule that does exist - the account identifier
 * at offset 0 resolving to an account row - is enforced by the schema alone, and deliberately not
 * duplicated here: a mapper that validated it would need a database, and a mapper that needs a
 * database cannot be a pure function.
 *
 * <h2>Failure contract</h2>
 *
 * <p>A record image whose encoded length is not exactly {@link #RECORD_LENGTH} raises
 * {@link IllegalArgumentException} naming the artefact, the expected width and the <em>actual</em>
 * encoded byte length. Widths are measured in encoded bytes throughout, never by
 * {@link String#length()}. Input is never silently padded, never truncated, never partially mapped
 * and never returned as {@code null} (decision D-08). No exception type from
 * {@code com.carddemo.exception} is used, and none is imported: none of the module's six exception
 * types models "the caller handed me the wrong number of bytes", and a short record has no legacy
 * antecedent at all, because VSAM records are fixed length by construction - so the check is a
 * Java-only defensive guard against a caller defect (decision D-11). An invalid overpunch character
 * in the balance field is the codec's contract rather than this class's, and its failure propagates
 * unwrapped. A {@code null} argument raises {@link NullPointerException} through
 * {@link Objects#requireNonNull(Object, String)}.
 *
 * <h2>Shape and thread safety</h2>
 *
 * <p>Stateless by construction: the class is final, its single constructor is private and raises, all
 * members are static and no mutable static state exists. Every operation is pure and side-effect
 * free - no I/O, no clock, no environment, no randomness - and every returned byte array is freshly
 * allocated, so callers cannot alias one another. Instances are impossible, so there is nothing to
 * share and nothing to synchronise. This class holds no logger: {@code com.carddemo.util} is not
 * among the module's configured logger names, so a logger here would be unconfigured, and a mapper
 * that logged would make a pure function observably impure.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Decode one row of the fixture, terminator already stripped by the caller.
 * TransactionCategoryBalance row = TranCatBalRecordMapper.fromRecord(image);
 * BigDecimal balance = row.getTranCatBal();          // 0.00, scale exactly 2
 *
 * // Round trip, compared only across the mapped prefix.
 * String reEmitted = TranCatBalRecordMapper.toRecord(row);
 * boolean prefixMatches = reEmitted
 *         .regionMatches(0, image, 0, TranCatBalRecordMapper.MAPPED_PREFIX_LENGTH);
 *
 * // Address a row without materialising one.
 * TransactionCategoryBalanceId id = TranCatBalRecordMapper.keyFromRecord(image);
 * }</pre>
 *
 * <h2>Decisions recorded</h2>
 *
 * <ol>
 *   <li>{@code TRAN-CAT-KEY} is declared in two copybooks at two different widths, 17 bytes here and
 *       6 bytes in the transaction-category layout, and the 6-byte key is not a prefix of the
 *       17-byte one; the type-and-category pair sits at different offsets in each. No key constant,
 *       helper or identifier class is shared. {@code KEYS(17 0)} and {@code KEYS(6 0)} attest both
 *       widths. Decision D-37.</li>
 *   <li>Scale 2 with {@code RoundingMode.DOWN}, never {@code HALF_EVEN} or {@code HALF_UP}, because
 *       {@code ROUNDED} occurs zero times estate-wide. Applied by the codec, never here.
 *       Decision D-02.</li>
 *   <li>Column prefixing is inconsistent within this one record - key columns {@code trancat_*}
 *       against the balance column {@code tran_cat_bal} - and is inherited from the copybook and
 *       deliberately preserved rather than regularised. Decision D-37.</li>
 *   <li>Filler bytes are not uniform in the estate: this layout's fixture carries 22 ASCII-zero
 *       filler bytes while this mapper emits spaces, so round-trip assertions compare only
 *       {@code [0, 28)} and a whole-record comparison would fail on the filler alone.
 *       Decision D-10, anomaly 20.</li>
 *   <li>All 50 seeded balances are the positive-zero image, so the fixture exercises one of the
 *       twenty sign characters; full sign coverage comes from the daily-transaction fixture.
 *       <em>Stated here rather than by decision identifier.</em></li>
 *   <li>Malformed fixed-width input raises {@link IllegalArgumentException} rather than a
 *       {@code com.carddemo.exception} type. Decisions D-08 and D-11.</li>
 * </ol>
 *
 * <p><strong>Provenance.</strong> Translated from the estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}. Legacy artefacts are cited by path,
 * never transcribed: no COBOL, copybook or JCL source text is reproduced here.
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @see ZonedDecimalCodec
 * @see FixedWidthFieldReader
 * @see TranCatRecordMapper
 */
public final class TranCatBalRecordMapper {

    /**
     * Layout name carried into every diagnostic; it names both the record group and the copybook, so
     * a failure identifies the layout without the reader having to infer it from a width.
     */
    public static final String ARTEFACT = "TRAN-CAT-BAL-RECORD (CVTRA01Y)";

    /**
     * Diagnostic name for the composite key considered on its own. The width is spelled out because
     * an identically named group in another copybook is 6 bytes wide, and a diagnostic that said only
     * {@code TRAN-CAT-KEY} would not distinguish the two.
     */
    public static final String KEY_ARTEFACT = "TRAN-CAT-KEY (CVTRA01Y, 17 bytes)";

    /** Legacy name of the first key component, used only in diagnostics. */
    private static final String FIELD_TRANCAT_ACCT_ID = "TRANCAT-ACCT-ID";

    /** Legacy name of the second key component, used only in diagnostics. */
    private static final String FIELD_TRANCAT_TYPE_CD = "TRANCAT-TYPE-CD";

    /** Legacy name of the third key component, used only in diagnostics. */
    private static final String FIELD_TRANCAT_CD = "TRANCAT-CD";

    /**
     * Legacy name of the balance field, used in diagnostics and handed to the codec so that a
     * malformed overpunch names the COBOL field rather than an offset.
     */
    private static final String FIELD_TRAN_CAT_BAL = "TRAN-CAT-BAL";

    /**
     * Full record width in encoded bytes: the 28-byte mapped prefix plus the 22-byte trailing filler
     * run. Attested independently by {@code RECORDSIZE(50 50)} [app/jcl/TCATBALF.jcl] and by the
     * copybook's own declared record length [app/cpy/CVTRA01Y.cpy].
     */
    public static final int RECORD_LENGTH = 50;

    /** Offset of the first key component, and of the key group, which both begin the record. */
    public static final int TRANCAT_ACCT_ID_OFFSET = 0;

    /** Width of the account identifier, {@code PIC 9(11)}, whose leading zeros are significant. */
    public static final int TRANCAT_ACCT_ID_LENGTH = 11;

    /** Offset of the type code, derived from the component before it rather than written as a literal. */
    public static final int TRANCAT_TYPE_CD_OFFSET =
            TRANCAT_ACCT_ID_OFFSET + TRANCAT_ACCT_ID_LENGTH;

    /** Width of the transaction type code, {@code PIC X(02)}. */
    public static final int TRANCAT_TYPE_CD_LENGTH = 2;

    /** Offset of the category code, derived from the component before it. */
    public static final int TRANCAT_CD_OFFSET = TRANCAT_TYPE_CD_OFFSET + TRANCAT_TYPE_CD_LENGTH;

    /** Width of the transaction category code, {@code PIC 9(04)}, whose leading zeros are significant. */
    public static final int TRANCAT_CD_LENGTH = 4;

    /**
     * Encoded width of the three-part composite key: <strong>17 bytes</strong>, derived by addition
     * from its components rather than written as a literal, and corroborated by {@code KEYS(17 0)}
     * [app/jcl/TCATBALF.jcl].
     *
     * <p>Named for all three components it contains - an account identifier, a type code and a
     * category code - so that it can never be confused with the identically named but structurally
     * unrelated <strong>6-byte</strong> key of the transaction-category layout, which contains only
     * the last two and places them at different offsets. The 6-byte key is not a prefix of this one.
     * See {@link TranCatRecordMapper} and decision D-37.
     */
    public static final int ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH =
            TRANCAT_ACCT_ID_LENGTH + TRANCAT_TYPE_CD_LENGTH + TRANCAT_CD_LENGTH;

    /** Offset of the balance field, immediately after the composite key. */
    public static final int TRAN_CAT_BAL_OFFSET =
            ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH;

    /**
     * Encoded width of the balance, {@code PIC S9(09)V99}: <strong>11 bytes</strong>, nine integer
     * digits plus two decimals, with the sign overpunched into the eleventh byte rather than carried
     * separately. Taken from {@link ZonedDecimalCodec#CATEGORY_BALANCE_WIDTH} rather than restated, so
     * this layout and the codec cannot drift apart. Deliberately neither the twelve bytes of the
     * account monetary fields nor the six of the disclosure rate.
     */
    public static final int TRAN_CAT_BAL_LENGTH = ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH;

    /**
     * Width of the mapped data prefix, {@code 17 + 11 = 28}, and <strong>the exact bound for a fixture
     * round-trip comparison</strong>: compare from zero up to but excluding this value, and nothing
     * beyond it. A whole-record comparison against the shipped fixture fails on the filler alone,
     * because that fixture carries ASCII-zero filler where this mapper emits spaces.
     */
    public static final int MAPPED_PREFIX_LENGTH = TRAN_CAT_BAL_OFFSET + TRAN_CAT_BAL_LENGTH;

    /** Offset at which the unmapped trailing filler run begins, which is the end of the mapped prefix. */
    public static final int FILLER_OFFSET = MAPPED_PREFIX_LENGTH;

    /**
     * Width of the unmapped trailing filler run, {@code 50 - 28 = 22} bytes. Derived by subtraction so
     * that the mapped fields and the filler always account for exactly the whole record.
     */
    public static final int FILLER_LENGTH = RECORD_LENGTH - MAPPED_PREFIX_LENGTH;

    /**
     * The byte this mapper writes across the filler run. Filler with no initialising clause is
     * uninitialised, so no value is canonical; space is the module-wide default and is stated
     * explicitly at the call site rather than inherited silently. The shipped fixture uses ASCII zero
     * instead, which is why a whole-record comparison against it fails (decision D-10, anomaly 20).
     */
    public static final char FILLER_CHARACTER = ' ';

    /**
     * Not instantiable: this class is a static contract holding one record layout, and a per-instance
     * copy of that layout would let two callers disagree about geometry the legacy record defines
     * exactly once.
     */
    private TranCatBalRecordMapper() {
        throw new AssertionError("TranCatBalRecordMapper is a static utility and is not instantiable");
    }

    /**
     * Decodes a complete transaction-category-balance record image supplied as a string.
     *
     * <p>The image must be exactly {@value #RECORD_LENGTH} encoded bytes with any {@code 0x0A}
     * terminator already removed by the caller. The three key components are copied verbatim, so their
     * significant leading zeros survive and nothing is trimmed; the balance is decoded by
     * {@link ZonedDecimalCodec} from its 11-byte overpunched image to a {@link BigDecimal} of scale 2.
     * The 22-byte trailing filler is not read at all.
     *
     * @param  recordImage the complete record image, excluding any line terminator; must not be
     *                     {@code null}
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} encoded
     *                                  bytes, if it contains a character US-ASCII cannot represent, or
     *                                  if the balance field carries an invalid overpunched image
     */
    public static TransactionCategoryBalance fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null for " + ARTEFACT);
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Decodes a complete transaction-category-balance record image supplied as bytes.
     *
     * <p>Preferred over {@link #fromRecord(String)} when the caller already holds raw bytes, because
     * it removes any need for the caller to choose a charset. Behaviour is otherwise identical, and the
     * supplied array is copied rather than retained.
     *
     * @param  recordImage the complete record image as bytes, excluding any line terminator; must not
     *                     be {@code null}
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} bytes, if
     *                                  any byte is not 7-bit ASCII, or if the balance field carries an
     *                                  invalid overpunched image
     */
    public static TransactionCategoryBalance fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null for " + ARTEFACT);
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Decodes one transaction-category-balance record held inside a larger byte buffer.
     *
     * <p>This is the seam for a batch reader that holds a whole newline-terminated file in one buffer.
     * The shipped fixture's stride is {@value #RECORD_LENGTH} plus one for the terminator, so record
     * <em>i</em> starts at {@code i * (RECORD_LENGTH + 1)} and the terminator is simply left behind
     * rather than stripped. Stride arithmetic and file access stay with the caller; this class only
     * ever sees one record's worth of bytes.
     *
     * <p>Because this overload selects a range rather than validating a whole image, an out-of-range
     * request is reported as a range failure against the buffer instead of as a width mismatch.
     *
     * @param  buffer buffer containing the record, and possibly many others; must not be {@code null}
     * @param  from   zero-based index in {@code buffer} at which the record starts
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the range
     *                                  {@code [from, from + }{@value #RECORD_LENGTH}{@code )} is not
     *                                  wholly inside {@code buffer}, if any byte in that range is not
     *                                  7-bit ASCII, or if the balance field carries an invalid
     *                                  overpunched image
     */
    public static TransactionCategoryBalance fromRecord(byte[] buffer, int from) {
        Objects.requireNonNull(buffer, "buffer must not be null for " + ARTEFACT);
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_LENGTH));
    }

    /**
     * Extracts just the three-part composite key from a complete record image, without materialising a
     * row.
     *
     * <p>For a caller that only needs to address or probe a row. The components are sliced at the same
     * offsets and copied just as verbatim as {@link #fromRecord(String)} copies them, so a zero-filled
     * identifier yields a key that matches the stored row exactly. The whole image is still required
     * and still validated, because the key is only meaningful as part of a well-formed record, and the
     * balance is not decoded at all.
     *
     * @param  recordImage the complete record image, excluding any line terminator; must not be
     *                     {@code null}
     * @return the three-component composite key, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} encoded
     *                                  bytes, or contains a character US-ASCII cannot represent
     */
    public static TransactionCategoryBalanceId keyFromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null for " + KEY_ARTEFACT);
        FixedWidthFieldReader record = FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH);
        return new TransactionCategoryBalanceId(
                record.field(FIELD_TRANCAT_ACCT_ID, TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH),
                record.field(FIELD_TRANCAT_TYPE_CD, TRANCAT_TYPE_CD_OFFSET, TRANCAT_TYPE_CD_LENGTH),
                record.field(FIELD_TRANCAT_CD, TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH));
    }

    /**
     * Renders the {@value #ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH}-byte composite key image of an entity,
     * which is the leading substring of its record image.
     *
     * <p>The exact inverse of taking the leading {@link #ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH} bytes of
     * a record image, which is what the cluster definition makes the stored key by declaring
     * {@code KEYS(17 0)} [app/jcl/TCATBALF.jcl]. Useful wherever the key is needed as one value rather
     * than three - key ordering, a keyed extract, or asserting that a key round-trips - without any
     * caller reassembling it and risking the component order.
     *
     * <p>Named for all three components it contains so that it can never be mistaken for the
     * identically named but structurally unrelated 6-byte key of the transaction-category layout, which
     * omits the account identifier this key leads with and places its two remaining components at
     * different offsets.
     *
     * @param  balance the entity whose key image is wanted; all three key components must be present
     * @return the key image, exactly {@value #ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH} characters
     * @throws NullPointerException     if {@code balance} is {@code null} or any key component is
     *                                  {@code null}
     * @throws IllegalArgumentException if any component is wider than its field or contains a character
     *                                  US-ASCII cannot represent
     */
    public static String accountTypeAndCategoryKeyImage(TransactionCategoryBalance balance) {
        Objects.requireNonNull(balance, "balance must not be null for " + KEY_ARTEFACT);
        return FixedWidthFieldReader.builder(KEY_ARTEFACT, ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH)
                .putNumeric(FIELD_TRANCAT_ACCT_ID, TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH,
                        requireField(balance.getTrancatAcctId(), FIELD_TRANCAT_ACCT_ID))
                .putAlphanumeric(FIELD_TRANCAT_TYPE_CD, TRANCAT_TYPE_CD_OFFSET,
                        TRANCAT_TYPE_CD_LENGTH,
                        requireField(balance.getTrancatTypeCd(), FIELD_TRANCAT_TYPE_CD))
                .putNumeric(FIELD_TRANCAT_CD, TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH,
                        requireField(balance.getTrancatCd(), FIELD_TRANCAT_CD))
                .build()
                .image();
    }

    /**
     * Encodes an entity into a complete {@value #RECORD_LENGTH}-byte record image.
     *
     * <p>Padding is part of the contract. The unsigned numeric components are placed right-justified
     * and zero-filled, which is what preserves their significant leading zeros, and the type code is
     * placed left-justified so a value already at its full declared width passes through byte for byte
     * and nothing is ever trimmed. The balance is encoded by {@link ZonedDecimalCodec} and placed flush
     * against the field's trailing edge so the overpunched sign byte stays last. The trailing filler is
     * written as {@link #FILLER_CHARACTER}.
     *
     * <p><strong>Comparison bound.</strong> Because the filler is uninitialised in the source and the
     * shipped fixture uses ASCII zero where this method writes a space, a round trip against that
     * fixture must compare only the mapped prefix {@code [0, }{@value #MAPPED_PREFIX_LENGTH}{@code )};
     * a whole-record comparison will fail on the filler alone.
     *
     * @param  balance the entity to encode; must not be {@code null}, and every mapped property must be
     *                 populated
     * @return the record image, exactly {@value #RECORD_LENGTH} US-ASCII characters
     * @throws NullPointerException     if {@code balance} is {@code null} or any mapped property of it
     *                                  is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field, contains a character
     *                                  US-ASCII cannot represent, or does not fit the balance field
     */
    public static String toRecord(TransactionCategoryBalance balance) {
        return buildImage(balance).image();
    }

    /**
     * Encodes an entity into a complete {@value #RECORD_LENGTH}-byte record image, returning the bytes
     * themselves so that the emitted width is guaranteed by construction rather than asserted after the
     * fact.
     *
     * <p>Behaves exactly as {@link #toRecord(TransactionCategoryBalance)}, including the filler byte and
     * the comparison bound described there. The returned array is freshly allocated and never shared.
     *
     * @param  balance the entity to encode; must not be {@code null}, and every mapped property must be
     *                 populated
     * @return a fresh array of exactly {@value #RECORD_LENGTH} US-ASCII bytes, never shared
     * @throws NullPointerException     if {@code balance} is {@code null} or any mapped property of it
     *                                  is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field, contains a character
     *                                  US-ASCII cannot represent, or does not fit the balance field
     */
    public static byte[] toRecordBytes(TransactionCategoryBalance balance) {
        return buildImage(balance).toByteArray();
    }

    /**
     * Slices the four mapped fields out of a validated record and assembles the entity.
     *
     * <p>Every slice is taken through {@link FixedWidthFieldReader}, which returns raw, untrimmed
     * values, and the balance is converted by {@link ZonedDecimalCodec}, which is the only place a scale
     * is applied. The entity is built with its public four-argument constructor rather than a
     * no-argument constructor and setters, because the no-argument constructor is {@code protected} and
     * reserved for the persistence provider and so is not reachable from this package; the
     * four-argument constructor assigns every argument verbatim, which is the guarantee this mapper
     * needs. The three key components are supplied individually and never as an identifier object,
     * because the entity carries them as {@code @Id} properties under {@code @IdClass}.
     *
     * @param  record the validated record image
     * @return a fully populated entity, never {@code null}
     * @throws IllegalArgumentException if the balance field carries an invalid overpunched image
     */
    private static TransactionCategoryBalance mapFrom(FixedWidthFieldReader record) {
        String trancatAcctId =
                record.field(FIELD_TRANCAT_ACCT_ID, TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH);
        String trancatTypeCd =
                record.field(FIELD_TRANCAT_TYPE_CD, TRANCAT_TYPE_CD_OFFSET, TRANCAT_TYPE_CD_LENGTH);
        String trancatCd = record.field(FIELD_TRANCAT_CD, TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH);
        // The balance image is sliced here and converted there: this class owns the offset, the codec
        // owns the overpunch convention and the scale, and neither owns both. No scale is applied here
        // and no arithmetic is performed on the result.
        String balanceImage =
                record.field(FIELD_TRAN_CAT_BAL, TRAN_CAT_BAL_OFFSET, TRAN_CAT_BAL_LENGTH);
        BigDecimal tranCatBal = ZonedDecimalCodec.decodeMonetary(
                balanceImage, TRAN_CAT_BAL_LENGTH, FIELD_TRAN_CAT_BAL);
        return new TransactionCategoryBalance(trancatAcctId, trancatTypeCd, trancatCd, tranCatBal);
    }

    /**
     * Places the four mapped fields and the filler run into a fresh record buffer.
     *
     * <p>Shared by both encode entrypoints so that the placement rules exist exactly once. Each
     * property is null-checked before placement, so a partially populated entity names the offending
     * legacy field instead of failing obscurely inside the builder.
     *
     * @param  balance the entity to encode
     * @return a reader over the completed image, of exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code balance} or any mapped property of it is {@code null}
     * @throws IllegalArgumentException if any value does not fit its field
     */
    private static FixedWidthFieldReader buildImage(TransactionCategoryBalance balance) {
        Objects.requireNonNull(balance, "balance must not be null for " + ARTEFACT);
        String trancatAcctId = requireField(balance.getTrancatAcctId(), FIELD_TRANCAT_ACCT_ID);
        String trancatTypeCd = requireField(balance.getTrancatTypeCd(), FIELD_TRANCAT_TYPE_CD);
        String trancatCd = requireField(balance.getTrancatCd(), FIELD_TRANCAT_CD);
        BigDecimal tranCatBal = Objects.requireNonNull(balance.getTranCatBal(),
                () -> nullFieldMessage(FIELD_TRAN_CAT_BAL));
        return FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH)
                // Right-justified and zero-filled, which is what preserves the significant leading
                // zeros of the account identifier.
                .putNumeric(FIELD_TRANCAT_ACCT_ID, TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH,
                        trancatAcctId)
                // Left-justified and space-padded. A value already at full width is placed unchanged.
                .putAlphanumeric(FIELD_TRANCAT_TYPE_CD, TRANCAT_TYPE_CD_OFFSET,
                        TRANCAT_TYPE_CD_LENGTH, trancatTypeCd)
                .putNumeric(FIELD_TRANCAT_CD, TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH, trancatCd)
                // The codec returns exactly the declared width, so this placement is positional only;
                // it is right-justified so the overpunched sign byte stays in the final position.
                .putNumeric(FIELD_TRAN_CAT_BAL, TRAN_CAT_BAL_OFFSET, TRAN_CAT_BAL_LENGTH,
                        ZonedDecimalCodec.encodeMonetary(tranCatBal, TRAN_CAT_BAL_LENGTH,
                                FIELD_TRAN_CAT_BAL))
                // Stated explicitly rather than inherited from the buffer's default, so the deliberate
                // choice of space over the fixture's ASCII zero is visible right here.
                .putFiller(FILLER_OFFSET, FILLER_LENGTH, FILLER_CHARACTER)
                .build();
    }

    /**
     * Returns a mapped character field, having verified that it is present.
     *
     * <p>An entity may legitimately be unpopulated - the persistence provider constructs one empty
     * before filling it - so a missing value is a caller defect rather than a record defect, and it is
     * reported with the legacy field name so the caller can see which component is absent. An empty
     * value is <em>not</em> rejected: an empty code is a legitimate all-zeros field, exactly as COBOL
     * renders an unset field. The value is returned unchanged and in particular untrimmed.
     *
     * @param  value     the property value as the entity holds it
     * @param  fieldName legacy field name used in the diagnostic
     * @return {@code value}, unchanged
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String requireField(String value, String fieldName) {
        return Objects.requireNonNull(value, () -> nullFieldMessage(fieldName));
    }

    /**
     * Builds the diagnostic for an absent mapped field, naming the artefact and the legacy field.
     *
     * @param  fieldName legacy field name that is absent
     * @return the diagnostic message
     */
    private static String nullFieldMessage(String fieldName) {
        return ARTEFACT + " cannot be encoded because " + fieldName
                + " is null; every mapped field of a fixed-width record must be present";
    }
}
