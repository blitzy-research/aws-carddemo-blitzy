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

import com.carddemo.domain.Account;

/**
 * Sole holder of the 300-byte account record layout, and the exact two-way mapping between that
 * record image and the {@link Account} entity.
 *
 * <p>The mapping is written by hand with explicit byte offsets. That is a design constraint rather
 * than a stylistic preference: the module's reflection budget is zero, so no annotation-driven,
 * convention-driven or bean-mapping library may place a field, and every offset therefore appears
 * as a named constant on this class where a reviewer can audit it against the copybook without
 * reading a method body. Decision log entries DL-034 and D-26 record the commitment.
 *
 * <h2>The record</h2>
 *
 * <p>Layout authority is the account entity copybook, whose group item declares twelve data items
 * followed by a filler run {@code [app/cpy/CVACT01Y.cpy:L4-L17]}. Two independent sources
 * corroborate the 300-byte total: the cluster definition that provisions the data set declares an
 * eleven-byte key at offset zero and a fixed record size of 300 {@code [app/jcl/ACCTFILE.jcl]}, and
 * the sequential reader's file description splits the same record into an eleven-byte identifier
 * plus a 289-byte remainder, and 11 + 289 = 300 {@code [app/cbl/CBACT01C.cbl:L42-L43]}. That second
 * citation uses the locator form this module cites by, which differs from the physical line by a
 * small offset for the reason recorded as decision D-42.
 *
 * <pre>
 *  #   COBOL field             PIC          offset  length  Java property on Account
 * ---  ----------------------  -----------  ------  ------  --------------------------
 *   1  ACCT-ID                 9(11)             0      11  acctId - the entity identifier
 *   2  ACCT-ACTIVE-STATUS      X(01)            11       1  acctActiveStatus - raw code
 *   3  ACCT-CURR-BAL           S9(10)V99        12      12  acctCurrBal - scale 2
 *   4  ACCT-CREDIT-LIMIT       S9(10)V99        24      12  acctCreditLimit - scale 2
 *   5  ACCT-CASH-CREDIT-LIMIT  S9(10)V99        36      12  acctCashCreditLimit - scale 2
 *   6  ACCT-OPEN-DATE          X(10)            48      10  acctOpenDate
 *   7  ACCT-EXPIRAION-DATE     X(10)            58      10  acctExpirationDate - see below
 *   8  ACCT-REISSUE-DATE       X(10)            68      10  acctReissueDate
 *   9  ACCT-CURR-CYC-CREDIT    S9(10)V99        78      12  acctCurrCycCredit - scale 2
 *  10  ACCT-CURR-CYC-DEBIT     S9(10)V99        90      12  acctCurrCycDebit - scale 2
 *  11  ACCT-ADDR-ZIP           X(10)           102      10  acctAddrZip
 *  12  ACCT-GROUP-ID           X(10)           112      10  acctGroupId
 *   -  FILLER                  X(178)          122     178  not mapped
 *
 * 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300
 * </pre>
 *
 * <h2>The five monetary fields are not contiguous</h2>
 *
 * <p><strong>This is the single easiest mistake to make in this layout.</strong> The five
 * {@code S9(10)V99} fields are split three then two by the three ten-byte date fields: three
 * amounts at offsets 12, 24 and 36, then a date block at 48, 58 and 68, then two more amounts at 78
 * and 90. An implementation that walks five consecutive twelve-byte amount fields misreads every
 * byte from offset 48 onward while still producing a plausible object. Every offset is therefore
 * declared as its own named constant and no offset is computed by advancing a running cursor, so
 * the discontinuity is visible rather than hidden inside an accumulation.
 *
 * <h2>Zoned decimal, not packed decimal</h2>
 *
 * <p>Each {@code S9(10)V99} field occupies <strong>twelve encoded bytes</strong> - ten integer
 * digits plus two implied decimal digits - and carries its sign <strong>overpunched into the final
 * digit byte</strong>. There is no separate sign byte and no packed representation anywhere in this
 * record, so the twelve bytes are all digits except that the last one encodes a digit and a sign
 * together. All conversion in both directions goes through {@link ZonedDecimalCodec}, which is the
 * module's single point of decimal truth: it applies scale 2 with truncation toward zero, matching
 * a COBOL store into a {@code V99} receiving field, because no arithmetic statement anywhere in the
 * estate specifies rounding, as decision log entries D-02 and DL-013 record.
 * <strong>This class never calls {@code setScale} and never names a
 * rounding mode</strong>; that prohibition is what stops a second, inconsistent rounding policy
 * from appearing in the module. No binary floating-point type appears here either: the mandated
 * construct mapping requires decimal precision to be identical with no floating-point substitution,
 * and an IEEE-754 approximation of a cent is precisely what that forbids.
 *
 * <h2>Anomaly: the misspelled expiry-date field</h2>
 *
 * <p>The copybook genuinely spells the seventh item {@code ACCT-EXPIRAION-DATE}, without the
 * {@code T}, at physical line {@code [app/cpy/CVACT01Y.cpy:L11]}; some project documentation cites
 * this as line 10, and the physical line is 11. The defect is preserved where it is load bearing
 * and corrected where it is not: <strong>byte offset 58 and length 10 are reproduced exactly</strong>
 * so the record image stays byte compatible, while the Java property is spelled correctly as
 * {@code acctExpirationDate}. The geometry constants on this class deliberately mirror the legacy
 * misspelling so that the mapping remains findable by searching for the copybook's own name, and
 * the layout is not quietly "fixed". Row 1 of the source anomaly register records the defect and
 * this resolution.
 *
 * <h2>Worked example - the first sample record</h2>
 *
 * <p>The sample account file is 15,050 bytes, which is 50 records of 300 bytes each followed by a
 * single {@code 0x0A}; the terminator is a record separator and is never record content, so a
 * caller strips it before presenting a record here {@code [app/data/ASCII/acctdata.txt]}. Sliced at
 * the offsets above, its first record reads:
 *
 * <pre>
 * ACCT-ID                 00000000001      leading zeros are significant, never parsed
 * ACCT-ACTIVE-STATUS      Y
 * ACCT-CURR-BAL           00000001940&#123;     '&#123;' is the positive-zero overpunch -&gt;    194.00
 * ACCT-CREDIT-LIMIT       00000020200&#123;                                          -&gt;   2020.00
 * ACCT-CASH-CREDIT-LIMIT  00000010200&#123;                                          -&gt;   1020.00
 * ACCT-OPEN-DATE          2014-11-20
 * ACCT-EXPIRAION-DATE     2025-05-20       at offset 58, not 48 and not 68
 * ACCT-REISSUE-DATE       2025-05-20
 * ACCT-CURR-CYC-CREDIT    00000000000&#123;                                          -&gt;      0.00
 * ACCT-CURR-CYC-DEBIT     00000000000&#123;                                          -&gt;      0.00
 * ACCT-ADDR-ZIP           A000000000
 * ACCT-GROUP-ID           (ten spaces)
 * FILLER                  (178 spaces)
 * </pre>
 *
 * <h2>Sample-data anomalies that must survive the round trip</h2>
 *
 * <p><strong>The group identifier is ten spaces on all fifty records.</strong> It must round-trip
 * as ten spaces - never {@code null}, never the empty string and never trimmed. The value is load
 * bearing rather than cosmetic: because every seeded account carries a blank group identifier, the
 * disclosure-group lookup misses and the interest program's documented default-group fallback is
 * the path the sample data actually exercises. Trimming it here would silently disable that path,
 * which is why decision log entry DL-035 makes a padded key and a trimmed key different keys
 * everywhere.
 *
 * <p><strong>The address ZIP is {@code A000000000} on all fifty records</strong> - a ZIP field
 * whose first byte is a letter. It is copied verbatim: not validated, not reformatted and never
 * treated as a number.
 *
 * <p><strong>The active status is {@code Y} on all fifty records</strong> and is carried as a raw
 * one-character value. This class does not translate it to a constant, does not consult the status
 * enumeration and does not reject an unrecognised code; screen and service layers own that
 * vocabulary.
 *
 * <p><strong>All 250 monetary field images in the sample file - five fields across fifty records -
 * end in the positive-zero overpunch.</strong> No negative-zero image occurs, which matters because
 * the entity carries each amount as a {@link BigDecimal} and a {@code BigDecimal} cannot hold the
 * negative-zero bit. A hypothetical negatively-signed all-zero field would therefore decode to zero
 * here and re-emit with the positive sign, which is the module-wide behaviour decision log entry
 * D-04 records; a caller that must preserve that distinction byte for byte reads the field through
 * {@link ZonedDecimalCodec}'s signed entry points, which exist for exactly that purpose. The
 * asymmetry is observable only where <em>every</em> digit is zero, and an ordinary negative amount
 * whose cent digit happens to be zero is unaffected: it ends in the negative overpunch and
 * re-encodes to it.
 *
 * <h2>Filler</h2>
 *
 * <p>{@code FILLER X(178)} carries no {@code VALUE} clause, so COBOL leaves it uninitialised and no
 * byte value is canonical. The four master files happen to carry space filler while the four
 * reference-table files carry ASCII-zero filler, which is recorded as anomaly 20 and resolved by
 * decision D-10. This mapper emits <strong>spaces</strong>, matching every byte of the 178-byte
 * filler run in all fifty sample records. The consequence for verification is explicit: a
 * fixture round-trip assertion for this layout compares only the mapped data prefix, the byte range
 * from 0 up to but excluding {@value #MAPPED_PREFIX_LENGTH} - that is, the half-open interval
 * {@code [0, 122)} - and not the whole 300-byte line, since bytes beyond that point are not this
 * mapper's to guarantee.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p><strong>No business validation.</strong> Credit-score range checks, credit-limit rules, status
 * vocabularies and date validity all belong to the account service layer, which owns the screen's
 * field-level error contract. A record that the legacy system accepted must map here without
 * complaint, so the only checks performed are geometric.
 *
 * <p><strong>No arithmetic, of any kind.</strong> The two arithmetic contracts this record
 * participates in are exacting, and both belong elsewhere. The transaction-posting service derives
 * its over-limit basis by subtracting the current cycle debit from the current cycle credit and
 * only then adding the daily-transaction amount, evaluated strictly left to right in that order,
 * and the interest service multiplies a category balance by a disclosure rate and only then divides
 * by 1200, multiplying first. Because every
 * store truncates rather than rounds, arithmetic in this estate is not associative: re-ordering
 * either expression moves the truncation point and changes the cent. Keeping this class free of
 * arithmetic is what guarantees it cannot contribute such a defect.
 *
 * <p><strong>No optimistic-locking version.</strong> {@link Account} carries a version counter for
 * optimistic locking, the persistence provider owns it, and it has <strong>no representation in the
 * 300-byte image</strong>. Nothing here reads or writes it, and the entity exposes no setter for it,
 * so a decoded record leaves the counter at its default and an encoded record neither carries nor
 * consumes one.
 *
 * <p><strong>No logging, no persistence and no I/O.</strong> This class holds no logger: the
 * module's diagnostic channel is named per package and this package is not among them, so
 * emit-then-abend logging is the caller's obligation. It opens no file, reads no clock, consults no
 * environment and touches no repository or transaction, which is what makes every method pure and
 * trivially testable.
 *
 * <h2>Failure contract</h2>
 *
 * <p>A record image whose encoded byte length is not exactly {@value #RECORD_LENGTH} raises
 * {@link IllegalArgumentException} naming the artefact, the expected width and the actual encoded
 * byte length; when the overshoot is exactly one byte the message also names the overwhelmingly
 * likely cause, an unstripped line terminator. Input is never silently padded, never truncated,
 * never partially mapped and never returned as {@code null}, which is decision log entry D-08. A
 * {@code null} argument raises {@link NullPointerException} through
 * {@link Objects#requireNonNull(Object, String)}.
 *
 * <p>No exception type from this module's own exception package is used, and that is deliberate:
 * none of them models "the caller handed me 297 bytes instead of 300". A short record has no legacy
 * antecedent at all, because the legacy records are fixed length by construction, so the length
 * check is a Java-only defensive guard against a caller defect rather than a migrated failure mode.
 * Decision log entry D-11 records the choice.
 *
 * <h2>Shape and thread safety</h2>
 *
 * <p>A stateless static contract: the class is final, its constructor is private, every member is
 * static, and it holds no mutable static state. All methods are pure functions of their arguments,
 * so they are safe to call concurrently. Encoding uses a builder that is scoped to a single record
 * and never escapes the call that created it.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * Account account = AccountRecordMapper.fromRecord(line);       // line excludes the 0x0A
 * String image    = AccountRecordMapper.toRecord(account);      // exactly 300 bytes
 *
 * // Addressing record i of a whole newline-terminated file held in one buffer: the stride is
 * // RECORD_LENGTH + 1, and the terminator is left behind.
 * Account tenth = AccountRecordMapper.fromRecord(buffer, 9 * (AccountRecordMapper.RECORD_LENGTH + 1));
 * }</pre>
 *
 * <p>Provenance: origin checkout commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook, program
 * or job-stream text is reproduced here; traceability is carried by citation only.
 *
 * @see Account
 * @see ZonedDecimalCodec
 * @see FixedWidthFieldReader
 */
public final class AccountRecordMapper {

    /**
     * Encoded byte width of the whole record, corroborated by the cluster definition's fixed record
     * size {@code [app/jcl/ACCTFILE.jcl]} and by the file description's 11 + 289 split
     * {@code [app/cbl/CBACT01C.cbl:L42-L43]}.
     */
    public static final int RECORD_LENGTH = 300;

    /**
     * Length of the leading key substring, which is the account identifier. Every cluster in the
     * estate declares its key at offset zero, so the primary key is always the leading substring of
     * the record image and the entity identifier is the natural business key, never a generated
     * surrogate - decision log entries D-29 and DL-017. A surrogate would break the correspondence
     * between the record image and the table row on which byte-level output parity depends.
     */
    public static final int KEY_LENGTH = 11;

    /**
     * Length of the data remainder that follows the key, as the file description declares it;
     * {@link #KEY_LENGTH} + {@code DATA_LENGTH} = {@value #RECORD_LENGTH}
     * {@code [app/cbl/CBACT01C.cbl:L42-L43]}.
     */
    public static final int DATA_LENGTH = 289;

    /**
     * Number of leading bytes this mapper is the authority for, being everything before the
     * uninitialised filler run. It coincides with {@link #FILLER_OFFSET} and is published
     * separately because it is the comparison bound a fixture round-trip assertion must use: the
     * range from 0 up to but excluding this value is guaranteed, and the filler beyond it is not.
     */
    public static final int MAPPED_PREFIX_LENGTH = 122;

    /** Zero-based offset of {@code ACCT-ID}, {@code PIC 9(11)}. */
    public static final int ACCT_ID_OFFSET = 0;

    /** Byte length of {@code ACCT-ID}; leading zeros are significant, so it is carried as text. */
    public static final int ACCT_ID_LENGTH = 11;

    /** Zero-based offset of {@code ACCT-ACTIVE-STATUS}, {@code PIC X(01)}. */
    public static final int ACCT_ACTIVE_STATUS_OFFSET = 11;

    /** Byte length of {@code ACCT-ACTIVE-STATUS}; the raw code is never translated here. */
    public static final int ACCT_ACTIVE_STATUS_LENGTH = 1;

    /** Zero-based offset of {@code ACCT-CURR-BAL}, {@code PIC S9(10)V99}. */
    public static final int ACCT_CURR_BAL_OFFSET = 12;

    /** Byte length of {@code ACCT-CURR-BAL}: ten integer digits plus two implied decimals. */
    public static final int ACCT_CURR_BAL_LENGTH = 12;

    /** Zero-based offset of {@code ACCT-CREDIT-LIMIT}, {@code PIC S9(10)V99}. */
    public static final int ACCT_CREDIT_LIMIT_OFFSET = 24;

    /** Byte length of {@code ACCT-CREDIT-LIMIT}. */
    public static final int ACCT_CREDIT_LIMIT_LENGTH = 12;

    /** Zero-based offset of {@code ACCT-CASH-CREDIT-LIMIT}, {@code PIC S9(10)V99}. */
    public static final int ACCT_CASH_CREDIT_LIMIT_OFFSET = 36;

    /** Byte length of {@code ACCT-CASH-CREDIT-LIMIT}. */
    public static final int ACCT_CASH_CREDIT_LIMIT_LENGTH = 12;

    /**
     * Zero-based offset of {@code ACCT-OPEN-DATE}, {@code PIC X(10)}. First of the three date
     * fields that interrupt the monetary run.
     */
    public static final int ACCT_OPEN_DATE_OFFSET = 48;

    /** Byte length of {@code ACCT-OPEN-DATE}, held in its ten-character external form. */
    public static final int ACCT_OPEN_DATE_LENGTH = 10;

    /**
     * Zero-based offset of the expiry date, whose copybook name is genuinely misspelled
     * {@code ACCT-EXPIRAION-DATE} at {@code [app/cpy/CVACT01Y.cpy:L11]}. This constant mirrors that
     * spelling on purpose, so the mapping stays findable from the legacy name; the entity property
     * is spelled correctly as {@code acctExpirationDate}. The offset itself is not negotiable -
     * moving it would break byte compatibility with every existing record.
     */
    public static final int ACCT_EXPIRAION_DATE_OFFSET = 58;

    /**
     * Byte length of the misspelled expiry-date field, {@code PIC X(10)}. See
     * {@link #ACCT_EXPIRAION_DATE_OFFSET} for why the legacy spelling is retained here.
     */
    public static final int ACCT_EXPIRAION_DATE_LENGTH = 10;

    /** Zero-based offset of {@code ACCT-REISSUE-DATE}, {@code PIC X(10)}. */
    public static final int ACCT_REISSUE_DATE_OFFSET = 68;

    /** Byte length of {@code ACCT-REISSUE-DATE}. */
    public static final int ACCT_REISSUE_DATE_LENGTH = 10;

    /**
     * Zero-based offset of {@code ACCT-CURR-CYC-CREDIT}, {@code PIC S9(10)V99}. The monetary run
     * resumes here, after the three date fields.
     */
    public static final int ACCT_CURR_CYC_CREDIT_OFFSET = 78;

    /** Byte length of {@code ACCT-CURR-CYC-CREDIT}. */
    public static final int ACCT_CURR_CYC_CREDIT_LENGTH = 12;

    /** Zero-based offset of {@code ACCT-CURR-CYC-DEBIT}, {@code PIC S9(10)V99}. */
    public static final int ACCT_CURR_CYC_DEBIT_OFFSET = 90;

    /** Byte length of {@code ACCT-CURR-CYC-DEBIT}. */
    public static final int ACCT_CURR_CYC_DEBIT_LENGTH = 12;

    /**
     * Zero-based offset of {@code ACCT-ADDR-ZIP}, {@code PIC X(10)}. Alphanumeric rather than
     * numeric: every sample record carries a leading letter here.
     */
    public static final int ACCT_ADDR_ZIP_OFFSET = 102;

    /** Byte length of {@code ACCT-ADDR-ZIP}. */
    public static final int ACCT_ADDR_ZIP_LENGTH = 10;

    /**
     * Zero-based offset of {@code ACCT-GROUP-ID}, {@code PIC X(10)}. Ten spaces on every sample
     * record, and those spaces are contractual.
     */
    public static final int ACCT_GROUP_ID_OFFSET = 112;

    /** Byte length of {@code ACCT-GROUP-ID}; trailing spaces are significant and never trimmed. */
    public static final int ACCT_GROUP_ID_LENGTH = 10;

    /** Zero-based offset of the trailing {@code FILLER X(178)} run, which is not mapped. */
    public static final int FILLER_OFFSET = 122;

    /**
     * Byte length of the trailing filler run; {@link #FILLER_OFFSET} + {@code FILLER_LENGTH} =
     * {@value #RECORD_LENGTH}. Emitted as spaces, matching all fifty sample records.
     */
    public static final int FILLER_LENGTH = 178;

    /**
     * Layout name carried into every diagnostic. It names both the copybook group and the copybook
     * member, so a failure identifies the layout without the reader having to guess which of the
     * eleven record shapes was involved.
     */
    private static final String ARTEFACT = "ACCOUNT-RECORD (CVACT01Y)";

    /** Legacy name of field 1, used in diagnostics and in nothing else. */
    private static final String FIELD_ACCT_ID = "ACCT-ID";

    /** Legacy name of field 2. */
    private static final String FIELD_ACCT_ACTIVE_STATUS = "ACCT-ACTIVE-STATUS";

    /** Legacy name of field 3. */
    private static final String FIELD_ACCT_CURR_BAL = "ACCT-CURR-BAL";

    /** Legacy name of field 4. */
    private static final String FIELD_ACCT_CREDIT_LIMIT = "ACCT-CREDIT-LIMIT";

    /** Legacy name of field 5. */
    private static final String FIELD_ACCT_CASH_CREDIT_LIMIT = "ACCT-CASH-CREDIT-LIMIT";

    /** Legacy name of field 6. */
    private static final String FIELD_ACCT_OPEN_DATE = "ACCT-OPEN-DATE";

    /**
     * Legacy name of field 7, reproduced with the copybook's own misspelling so that a diagnostic
     * quotes a name a reader can find in {@code [app/cpy/CVACT01Y.cpy:L11]}.
     */
    private static final String FIELD_ACCT_EXPIRAION_DATE = "ACCT-EXPIRAION-DATE";

    /** Legacy name of field 8. */
    private static final String FIELD_ACCT_REISSUE_DATE = "ACCT-REISSUE-DATE";

    /** Legacy name of field 9. */
    private static final String FIELD_ACCT_CURR_CYC_CREDIT = "ACCT-CURR-CYC-CREDIT";

    /** Legacy name of field 10. */
    private static final String FIELD_ACCT_CURR_CYC_DEBIT = "ACCT-CURR-CYC-DEBIT";

    /** Legacy name of field 11. */
    private static final String FIELD_ACCT_ADDR_ZIP = "ACCT-ADDR-ZIP";

    /** Legacy name of field 12. */
    private static final String FIELD_ACCT_GROUP_ID = "ACCT-GROUP-ID";

    /**
     * Not instantiable: this is a stateless mapper exposing only static members, so an instance
     * would carry no state and confer no capability.
     */
    private AccountRecordMapper() {
    }

    /**
     * Maps a complete account record image, supplied as a string, onto a fully populated entity.
     *
     * <p>The image must be exactly {@value #RECORD_LENGTH} encoded bytes and must exclude any line
     * terminator. Its encoded byte length is checked before a single field is sliced, so a
     * mis-sized record is reported rather than mapped from the wrong offsets. Every character field
     * is copied verbatim - untrimmed, unstripped, not case folded and not normalised - and every
     * monetary field is decoded by {@link ZonedDecimalCodec} at scale 2.
     *
     * @param  recordImage the whole 300-byte record image, excluding any line terminator; must not
     *                     be {@code null}
     * @return a fully populated account, never {@code null} and never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image's encoded byte length is not exactly
     *                                  {@value #RECORD_LENGTH}, if it contains a character US-ASCII
     *                                  cannot represent, or if a monetary field's twelve bytes are
     *                                  not a valid zoned-decimal image
     */
    public static Account fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps a complete account record image, supplied as bytes, onto a fully populated entity.
     *
     * <p>Preferred over {@link #fromRecord(String)} when the caller already holds raw bytes, because
     * it removes any need for the caller to choose a charset: the bytes are verified to be 7-bit
     * ASCII and decoded as US-ASCII inside {@link FixedWidthFieldReader}. The array is only read; it
     * is neither retained nor modified.
     *
     * @param  recordImage the whole 300-byte record image as bytes, excluding any line terminator;
     *                     must not be {@code null}
     * @return a fully populated account, never {@code null} and never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the array's length is not exactly
     *                                  {@value #RECORD_LENGTH}, if any byte is not 7-bit ASCII, or
     *                                  if a monetary field's twelve bytes are not a valid
     *                                  zoned-decimal image
     */
    public static Account fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireRecordWidth(recordImage.length);
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps one account record held inside a larger byte buffer onto a fully populated entity.
     *
     * <p>This is the seam for a caller that has read a whole newline-terminated fixed-width file
     * into a single buffer. The stride of such a file is {@value #RECORD_LENGTH} + 1, so record
     * <em>i</em> is addressed at {@code i * (RECORD_LENGTH + 1)}, which selects the record and
     * leaves its {@code 0x0A} terminator behind. Stride arithmetic and file access stay with the
     * caller; this mapper only ever sees one record's worth of bytes.
     *
     * <p>No whole-buffer width check applies here, because the buffer is not the record: the
     * requested range must simply lie wholly inside it, and a range that does not is reported with
     * the offset, the record width and the buffer length.
     *
     * @param  buffer the buffer containing the record, and possibly many others; must not be
     *                {@code null}
     * @param  offset zero-based index in {@code buffer} at which the record starts
     * @return a fully populated account, never {@code null} and never partially mapped
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code offset} is negative, if the range starting at
     *                                  {@code offset} and {@value #RECORD_LENGTH} bytes long is not
     *                                  wholly inside {@code buffer}, if any byte in that range is
     *                                  not 7-bit ASCII, or if a monetary field's twelve bytes are
     *                                  not a valid zoned-decimal image
     */
    public static Account fromRecord(byte[] buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, buffer, offset, RECORD_LENGTH));
    }

    /**
     * Renders an account as its canonical {@value #RECORD_LENGTH}-byte record image, returned as a
     * string that carries no line terminator.
     *
     * <p>The exact inverse of {@link #fromRecord(String)} for the twelve mapped fields. Character
     * fields are placed left-justified and space-padded, matching a COBOL move into a {@code PIC
     * X(n)} item, so a nine-character value in a ten-byte field acquires one trailing space and a
     * value that is already ten characters - including ten spaces - is placed unchanged. The account
     * identifier is placed right-justified and zero-padded, matching {@code PIC 9(11)}. Each
     * monetary field is encoded by {@link ZonedDecimalCodec} into twelve bytes whose final byte
     * carries the overpunched sign. The 178-byte filler run is emitted as spaces.
     *
     * <p>The emitted width is guaranteed twice: by construction, because the builder allocates
     * exactly {@value #RECORD_LENGTH} bytes and rejects any value too wide for its field, and by an
     * explicit encoded-byte check before the image is returned.
     *
     * @param  account the account to render; must not be {@code null}, and none of its twelve
     *                 mapped properties may be {@code null}
     * @return the record image, exactly {@value #RECORD_LENGTH} encoded bytes wide, never
     *         {@code null}
     * @throws NullPointerException     if {@code account} is {@code null}
     * @throws IllegalArgumentException if any mapped property is {@code null}, if a character value
     *                                 is wider than its field, if a character value contains a
     *                                 character US-ASCII cannot represent, or if an amount needs
     *                                 more digits than its field provides
     */
    public static String toRecord(Account account) {
        String image = imageOf(account).image();
        requireRecordWidth(FixedWidthFieldReader.encodedLength(image));
        return image;
    }

    /**
     * Renders an account as its canonical {@value #RECORD_LENGTH}-byte record image, returned as
     * bytes ready to be written to a file or an object store.
     *
     * <p>Byte-for-byte identical to {@link #toRecord(Account)} and offered so that a writer need
     * not choose a charset. The returned array is fresh and unshared, and it carries no line
     * terminator: record separation belongs to the writer, which is decision log entry D-30.
     *
     * @param  account the account to render; must not be {@code null}, and none of its twelve
     *                 mapped properties may be {@code null}
     * @return a new array of exactly {@value #RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code account} is {@code null}
     * @throws IllegalArgumentException on exactly the same conditions as {@link #toRecord(Account)}
     */
    public static byte[] toRecordBytes(Account account) {
        byte[] image = imageOf(account).toByteArray();
        requireRecordWidth(image.length);
        return image;
    }

    /**
     * Places the twelve mapped fields and the filler run, in declaration order, and completes the
     * image.
     *
     * <p>Written as twelve separate placements against named offset constants rather than as a
     * single fluent chain, so that each field's offset and length are visible on the line that uses
     * them and the three-then-two split of the monetary fields is legible in the method body as
     * well as in the constant block.
     *
     * @param  account the account to render; must not be {@code null}
     * @return an immutable reader over the completed image, exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code account} is {@code null}
     * @throws IllegalArgumentException if a mapped property is {@code null} or does not fit its
     *                                  field
     */
    private static FixedWidthFieldReader imageOf(Account account) {
        Objects.requireNonNull(account, "account must not be null");
        FixedWidthFieldReader.Builder image = FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH);
        image.putNumeric(FIELD_ACCT_ID, ACCT_ID_OFFSET, ACCT_ID_LENGTH,
                requireText(FIELD_ACCT_ID, "acctId", account.getAcctId()));
        image.putAlphanumeric(FIELD_ACCT_ACTIVE_STATUS, ACCT_ACTIVE_STATUS_OFFSET,
                ACCT_ACTIVE_STATUS_LENGTH, requireText(FIELD_ACCT_ACTIVE_STATUS,
                        "acctActiveStatus", account.getAcctActiveStatus()));
        putAmount(image, FIELD_ACCT_CURR_BAL, "acctCurrBal", ACCT_CURR_BAL_OFFSET,
                ACCT_CURR_BAL_LENGTH, account.getAcctCurrBal());
        putAmount(image, FIELD_ACCT_CREDIT_LIMIT, "acctCreditLimit", ACCT_CREDIT_LIMIT_OFFSET,
                ACCT_CREDIT_LIMIT_LENGTH, account.getAcctCreditLimit());
        putAmount(image, FIELD_ACCT_CASH_CREDIT_LIMIT, "acctCashCreditLimit",
                ACCT_CASH_CREDIT_LIMIT_OFFSET, ACCT_CASH_CREDIT_LIMIT_LENGTH,
                account.getAcctCashCreditLimit());
        image.putAlphanumeric(FIELD_ACCT_OPEN_DATE, ACCT_OPEN_DATE_OFFSET, ACCT_OPEN_DATE_LENGTH,
                requireText(FIELD_ACCT_OPEN_DATE, "acctOpenDate", account.getAcctOpenDate()));
        image.putAlphanumeric(FIELD_ACCT_EXPIRAION_DATE, ACCT_EXPIRAION_DATE_OFFSET,
                ACCT_EXPIRAION_DATE_LENGTH, requireText(FIELD_ACCT_EXPIRAION_DATE,
                        "acctExpirationDate", account.getAcctExpirationDate()));
        image.putAlphanumeric(FIELD_ACCT_REISSUE_DATE, ACCT_REISSUE_DATE_OFFSET,
                ACCT_REISSUE_DATE_LENGTH, requireText(FIELD_ACCT_REISSUE_DATE, "acctReissueDate",
                        account.getAcctReissueDate()));
        putAmount(image, FIELD_ACCT_CURR_CYC_CREDIT, "acctCurrCycCredit",
                ACCT_CURR_CYC_CREDIT_OFFSET, ACCT_CURR_CYC_CREDIT_LENGTH,
                account.getAcctCurrCycCredit());
        putAmount(image, FIELD_ACCT_CURR_CYC_DEBIT, "acctCurrCycDebit", ACCT_CURR_CYC_DEBIT_OFFSET,
                ACCT_CURR_CYC_DEBIT_LENGTH, account.getAcctCurrCycDebit());
        image.putAlphanumeric(FIELD_ACCT_ADDR_ZIP, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH,
                requireText(FIELD_ACCT_ADDR_ZIP, "acctAddrZip", account.getAcctAddrZip()));
        image.putAlphanumeric(FIELD_ACCT_GROUP_ID, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH,
                requireText(FIELD_ACCT_GROUP_ID, "acctGroupId", account.getAcctGroupId()));
        image.putSpaceFiller(FILLER_OFFSET, FILLER_LENGTH);
        return image.build();
    }

    /**
     * Reads the twelve mapped fields at their declared offsets and builds the entity.
     *
     * <p>The entity is populated through its public twelve-argument constructor, whose parameter
     * order is the record-image order, because the no-argument constructor is protected for the
     * persistence provider's use and is not reachable from this package. The optimistic-locking
     * version counter is deliberately not touched: it has no representation in the record image and
     * the provider owns it.
     *
     * @param  record an immutable reader over a validated {@value #RECORD_LENGTH}-byte image
     * @return the populated entity, never {@code null}
     * @throws IllegalArgumentException if a monetary field's twelve bytes are not a valid
     *                                  zoned-decimal image
     */
    private static Account mapFrom(FixedWidthFieldReader record) {
        return new Account(
                record.field(FIELD_ACCT_ID, ACCT_ID_OFFSET, ACCT_ID_LENGTH),
                record.field(FIELD_ACCT_ACTIVE_STATUS, ACCT_ACTIVE_STATUS_OFFSET,
                        ACCT_ACTIVE_STATUS_LENGTH),
                amountAt(record, FIELD_ACCT_CURR_BAL, ACCT_CURR_BAL_OFFSET, ACCT_CURR_BAL_LENGTH),
                amountAt(record, FIELD_ACCT_CREDIT_LIMIT, ACCT_CREDIT_LIMIT_OFFSET,
                        ACCT_CREDIT_LIMIT_LENGTH),
                amountAt(record, FIELD_ACCT_CASH_CREDIT_LIMIT, ACCT_CASH_CREDIT_LIMIT_OFFSET,
                        ACCT_CASH_CREDIT_LIMIT_LENGTH),
                record.field(FIELD_ACCT_OPEN_DATE, ACCT_OPEN_DATE_OFFSET, ACCT_OPEN_DATE_LENGTH),
                record.field(FIELD_ACCT_EXPIRAION_DATE, ACCT_EXPIRAION_DATE_OFFSET,
                        ACCT_EXPIRAION_DATE_LENGTH),
                record.field(FIELD_ACCT_REISSUE_DATE, ACCT_REISSUE_DATE_OFFSET,
                        ACCT_REISSUE_DATE_LENGTH),
                amountAt(record, FIELD_ACCT_CURR_CYC_CREDIT, ACCT_CURR_CYC_CREDIT_OFFSET,
                        ACCT_CURR_CYC_CREDIT_LENGTH),
                amountAt(record, FIELD_ACCT_CURR_CYC_DEBIT, ACCT_CURR_CYC_DEBIT_OFFSET,
                        ACCT_CURR_CYC_DEBIT_LENGTH),
                record.field(FIELD_ACCT_ADDR_ZIP, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH),
                record.field(FIELD_ACCT_GROUP_ID, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH));
    }

    /**
     * Slices one zoned-decimal field and decodes it at the canonical monetary scale.
     *
     * <p>Slicing goes through {@link FixedWidthFieldReader} and decoding through
     * {@link ZonedDecimalCodec}, so this mapper performs neither substring arithmetic nor scale
     * arithmetic of its own.
     *
     * @param  record    the reader over the whole record image
     * @param  fieldName legacy field name, used only in diagnostics
     * @param  offset    zero-based byte offset of the field
     * @param  length    encoded byte width of the field, twelve for every amount in this layout
     * @return the decoded amount, never {@code null}, whose scale is exactly 2
     * @throws IllegalArgumentException if the slice is not a valid zoned-decimal image
     */
    private static BigDecimal amountAt(FixedWidthFieldReader record, String fieldName, int offset,
            int length) {
        return ZonedDecimalCodec.decodeMonetary(record.field(fieldName, offset, length), length,
                fieldName);
    }

    /**
     * Encodes one amount and places it right-justified at its declared offset.
     *
     * <p>Numeric placement is correct for a zoned-decimal image as well as for an unsigned numeric
     * field: it left-pads with ASCII zeros and leaves the overpunched sign in the final byte. The
     * codec has already produced exactly {@code length} bytes, so no padding actually occurs; the
     * placement mode is stated for the reader's benefit and for safety if a narrower value ever
     * arrives.
     *
     * @param image     the image under construction
     * @param fieldName legacy field name, used only in diagnostics
     * @param property  the entity property name, used only in diagnostics
     * @param offset    zero-based byte offset of the field
     * @param length    encoded byte width of the field
     * @param value     the amount to encode
     * @throws IllegalArgumentException if {@code value} is {@code null} or needs more digits than
     *                                  the field provides
     */
    private static void putAmount(FixedWidthFieldReader.Builder image, String fieldName,
            String property, int offset, int length, BigDecimal value) {
        image.putNumeric(fieldName, offset, length, ZonedDecimalCodec.encodeMonetary(
                requireAmount(fieldName, property, value), length, fieldName));
    }

    /**
     * Verifies that a character property is present, and returns it unchanged.
     *
     * <p>Returns the value verbatim: nothing is trimmed, padded, case folded, defaulted or
     * validated, because the fixed-width padding a value carries is part of the record contract.
     *
     * @param  fieldName legacy field name, used in the diagnostic
     * @param  property  the entity property name, used in the diagnostic
     * @param  value     the value to check
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static String requireText(String fieldName, String property, String value) {
        if (value == null) {
            throw new IllegalArgumentException(ARTEFACT + " field '" + fieldName + "' (Account."
                    + property + ") must not be null: a fixed-width record has no representation"
                    + " for an absent value, so an unset character field is presented as spaces"
                    + " rather than as null");
        }
        return value;
    }

    /**
     * Verifies that a monetary property is present, and returns it unchanged.
     *
     * <p>The value's scale is neither inspected nor adjusted here. Scale policy belongs to
     * {@link ZonedDecimalCodec}, which brings every amount to scale 2 by truncating toward zero,
     * and concentrating that decision in one class is what keeps the module's rounding policy
     * single-valued.
     *
     * @param  fieldName legacy field name, used in the diagnostic
     * @param  property  the entity property name, used in the diagnostic
     * @param  value     the value to check
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static BigDecimal requireAmount(String fieldName, String property, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException(ARTEFACT + " field '" + fieldName + "' (Account."
                    + property + ") must not be null: a zoned-decimal field has no representation"
                    + " for an absent amount, so an unset amount is presented as zero rather than"
                    + " as null");
        }
        return value;
    }

    /**
     * Compares an encoded byte length with the width this layout declares.
     *
     * <p>Serves both directions, so the input precondition and the output postcondition report the
     * same message shape. The message names the artefact, the expected width and the actual encoded
     * byte length, which is the whole failure contract for a mis-sized record; when the overshoot is
     * exactly one byte it also names the overwhelmingly likely cause, since the sample file is
     * newline terminated and its stride is one byte greater than its record width.
     *
     * @param actualEncodedBytes the encoded byte length actually observed
     * @throws IllegalArgumentException if {@code actualEncodedBytes} is not exactly
     *                                 {@value #RECORD_LENGTH}
     */
    private static void requireRecordWidth(int actualEncodedBytes) {
        if (actualEncodedBytes == RECORD_LENGTH) {
            return;
        }
        String terminatorHint = actualEncodedBytes == RECORD_LENGTH + 1
                ? " (an overshoot of exactly one byte is almost always an unstripped 0x0A line"
                        + " terminator: the sample file's stride is 301 bytes, of which only the"
                        + " leading 300 are the record)"
                : "";
        throw new IllegalArgumentException(ARTEFACT + " record image must be exactly "
                + RECORD_LENGTH + " encoded bytes in US-ASCII, but the supplied image is "
                + actualEncodedBytes + " encoded bytes; fixed-width records are never padded or"
                + " truncated to fit" + terminatorHint);
    }
}
