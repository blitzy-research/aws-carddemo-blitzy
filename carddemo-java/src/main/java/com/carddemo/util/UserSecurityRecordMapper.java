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

import com.carddemo.domain.UserSecurity;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Hand-written, reflection-free mapper between the legacy 80-byte sign-on record and
 * {@link UserSecurity}.
 *
 * <p>This is the eleventh of the module's eleven record mappers and it is the odd one out twice
 * over: it is the <strong>only</strong> mapper that is deliberately <strong>not</strong>
 * round-trippable, and the <strong>only</strong> one whose record carries a credential. Both
 * properties come from a single asymmetry. The legacy record stores the sign-on credential in the
 * clear in eight bytes; the migrated column stores a sixty-character one-way digest instead; and a
 * one-way digest cannot be reversed back into the eight bytes it replaced. Every design decision
 * below follows from that asymmetry rather than from taste.
 *
 * <h2>Verified 80-byte layout</h2>
 *
 * <p>The authority is the group {@code SEC-USER-DATA} declared in copybook
 * {@code [app/cpy/CSUSR01Y.cpy]}, whose own Apache-2.0 block occupies that file's lines 2 through
 * 15 - the same block this file carries, so the licence header is attested twice for this layout.
 * The identifier is declared at the copybook's line 18 and the credential at its line 21. Offsets
 * are zero-based byte positions in the record image; lengths are encoded byte counts, never
 * character counts.
 *
 * <pre>
 * #   COBOL field       PIC     Offset  Length  Java property on UserSecurity
 * --  ----------------  ------  ------  ------  -----------------------------------------------
 * 1   SEC-USR-ID        X(08)        0       8  secUsrId      (the JPA @Id, a natural key)
 * 2   SEC-USR-FNAME     X(20)        8      20  secUsrFname
 * 3   SEC-USR-LNAME     X(20)       28      20  secUsrLname
 * 4   SEC-USR-PWD       X(08)       48       8  the credential column, VARCHAR(60), a digest
 * 5   SEC-USR-TYPE      X(01)       56       1  secUsrType    (raw one-character role code)
 * --  SEC-USR-FILLER    X(23)       57      23  not mapped, not persisted
 * </pre>
 *
 * <p>The mapped fields sum to 8 + 20 + 20 + 8 + 1 = 57 bytes, the trailing filler adds 23, and
 * 57 + 23 = 80. That arithmetic is not a comment here: {@link #MAPPED_LENGTH} is declared as the
 * sum of the five field lengths and {@link #RECORD_LENGTH} as that sum plus the filler length, so
 * the record width is derived from the layout rather than asserted alongside it. The derived 80 is
 * corroborated independently by the provisioning job {@code [app/jcl/DUSRSECJ.jcl]}, which writes
 * the sequential dataset with {@code LRECL=80 RECFM=FB} through {@code IEBGENER}, and again by that
 * job's cluster definition, which declares an 8-byte key at offset 0 and a record size of 80.
 *
 * <p>There are <strong>no</strong> zoned-decimal fields in this layout - every field is
 * {@code PIC X(n)} - so no overpunch decoding happens here and the module's zoned-decimal codec is
 * neither imported nor invoked. Nothing in this layout is numeric, so nothing is parsed.
 *
 * <h2>The trailing filler is named, uniquely in this estate</h2>
 *
 * <p>Every other layout in the estate ends in an anonymous {@code FILLER}. This one ends in
 * {@code SEC-USR-FILLER}, a <em>named</em> field. The naming changes no offset, no width and no
 * behaviour: the 23 bytes are still not mapped, still not persisted, still have no Java property
 * and no column, and are still reconstructed on output from the declared record width rather than
 * carried on the entity. The difference is recorded because a reader comparing this layout with its
 * ten siblings will notice it, and an unexplained difference invites someone to "fix" it.
 *
 * <p>COBOL filler with no {@code VALUE} clause is uninitialised, so no byte value is canonical for
 * it, and the estate's own fixtures disagree - the four master fixtures carry space filler while
 * the four reference-table fixtures carry ASCII-zero filler. That divergence is anomaly 20 and its
 * module-wide resolution is decision D-10: space is the default. This layout has no ASCII fixture
 * at all, so its only witness is the in-stream card images in the provisioning job, and
 * {@link #toRecord(UserSecurity)} therefore emits the filler as spaces.
 *
 * <h2>The credential is the estate's single deliberate parity exception</h2>
 *
 * <p>The legacy field {@code SEC-USR-PWD} is eight cleartext characters held inside the record, and
 * legacy sign-on authenticates by comparing that stored field <em>directly</em> against the value
 * keyed at the terminal, at line 223 of {@code [app/cbl/COSGN00C.cbl]}. Reproducing that faithfully
 * would satisfy byte-for-byte parity and breach the migration's binding no-hardcoded-credentials
 * requirement in the same stroke.
 *
 * <p>The resolution, binding on this file: the eight cleartext bytes are read out of the image and
 * <strong>immediately</strong> transformed into a BCrypt digest, and the digest is what the entity
 * and the {@code user_security.sec_usr_pwd} column hold. The entity never holds a cleartext value at
 * any point. The legacy column is 8 characters wide and the target column is 60; the widths are
 * <strong>intentionally</strong> different, this is the only column in the eleven-entity schema
 * whose width does not equal its picture width, and it is the reason this layout is not
 * round-trippable. The module's faithful-beats-idiomatic tie-break yields here, and only here,
 * because faithfulness would violate the credential constraint. It is recorded as the flagship
 * security entry in {@code docs/decision-log.md} so that a reviewer reads it as a deliberate,
 * documented exception rather than as an accidental regression.
 *
 * <h2>Not round-trippable, and the API says so</h2>
 *
 * <p>A digest is one way and sixty characters do not fit an eight-byte field, so
 * {@link #toRecord(UserSecurity)} cannot reconstruct a legacy image. It emits the credential window
 * {@code [48, 56)} as eight spaces. No part of a digest is ever written into an image - not
 * truncated to eight bytes, not as a prefix, not as a fragment - because a truncated digest would
 * be wrong data and a leaked credential fragment at the same time. The guarantee is structural
 * rather than a matter of care: <strong>this class never calls
 * {@code UserSecurity.credentialDigest()}</strong>, so it holds nothing it could write, and the
 * window is produced by a blank-run declaration rather than by a field placement.
 *
 * <p>Round-trip verification therefore compares only the four reproducible fields, in exactly two
 * windows: {@code [0, 48)} covering the identifier and both names, and {@code [56, 57)} covering the
 * role code. The credential window {@code [48, 56)} is excluded by design because it is emitted
 * blank, and the filler {@code [57, 80)} is excluded for the usual uninitialised-filler reason.
 * {@link #REPRODUCIBLE_PREFIX_OFFSET}, {@link #REPRODUCIBLE_PREFIX_LENGTH},
 * {@link #REPRODUCIBLE_SUFFIX_OFFSET} and {@link #REPRODUCIBLE_SUFFIX_LENGTH} publish those two
 * windows so a caller states them rather than recomputing them. <strong>A whole-record 80-byte
 * comparison is meaningless for this layout and must never be asserted.</strong>
 *
 * <h2>The digest step is injected, never performed here</h2>
 *
 * <p>Producing a digest is not a pure operation - each one embeds fresh randomness - and it needs an
 * encoder that belongs to a higher layer. Two constraints therefore meet in this file: the utility
 * layer may not depend on {@code service}, {@code config} or on any type from
 * {@code org.springframework.security.crypto}, and the build must stay hermetic, so no digest
 * library may be added for the sake of one field. Both are satisfied the same way. Every
 * {@code fromRecord} overload takes a {@link UnaryOperator} of {@link String} and hands the eight
 * cleartext bytes straight to it, and the caller - the {@code service} or {@code config} layer,
 * which owns the real encoder - supplies it. The encoder's abstraction is not imported here and its
 * work factor is <strong>not chosen here</strong>: that is a configuration decision, and no digest
 * algorithm, no randomness source and no work factor exists anywhere in this file.
 *
 * <p>Four properties of that parameter are contractual. It is <strong>required</strong>, never
 * optional. <strong>There is deliberately no overload, no convenience factory and no code path of
 * any kind that skips it or that places a cleartext value on the entity</strong> - if such a path
 * existed someone would eventually call it. The cleartext slice is used <strong>exactly once</strong>
 * and is never bound to a local variable, a field, a collection, a return value, a message or an
 * exception: it is produced by the reader and consumed by the function inside a single expression,
 * so no name in this file ever refers to it. And it is handed over <strong>unaltered</strong> - not
 * trimmed, not stripped, not case-folded, not normalised and not validated - because the legacy
 * field is a fixed eight bytes and every remaining decision belongs to the caller's encoder.
 *
 * <p>Defence in depth backs the contract up from the other side. {@link UserSecurity} refuses any
 * credential value that is not structurally a digest, in both its constructor and its replacement
 * method, so even a caller who mistakenly supplies {@link UnaryOperator#identity()} cannot cause a
 * cleartext value to reach the column: the construction fails instead, and it fails without echoing
 * the value it rejected.
 *
 * <h2>The role code stays a raw character</h2>
 *
 * <p>{@code SEC-USR-TYPE} is one byte holding {@code A} for an administrator or {@code U} for a
 * standard user, and it is mapped to a raw one-character {@link String}. <strong>No enum
 * translation happens here.</strong> Turning the character into the module's user-type enumeration
 * is a {@code service} and {@code config} concern, and within this package only the attention-key
 * translator may depend on {@code com.carddemo.domain.enums}. The reason is behavioural rather than
 * stylistic: legacy sign-on tests only the administrator condition and reaches the main menu through
 * an unconditional alternative, so an unexpected code routes rather than fails, and a value the
 * legacy system accepted must survive this mapper rather than be rejected by it. No role, authority
 * or route-gating decision is taken here either.
 *
 * <h2>Ten seeded identities, recovered without any encoding conversion</h2>
 *
 * <p>The mainframe dataset {@code app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS} is the only one of the
 * twelve EBCDIC datasets with no ASCII twin, which would ordinarily make its content the one thing
 * in the estate that needed decoding. It does not, because {@code [app/jcl/DUSRSECJ.jcl]} carries all
 * ten records in stream as readable ASCII card images at its lines 35 through 44, and that content is
 * what the dataset holds. No EBCDIC decode is required anywhere in the migration as a result. The ten
 * seeded identities - five administrators and five standard users - are:
 *
 * <pre>
 * SEC-USR-ID  First name  Last name   SEC-USR-TYPE
 * ----------  ----------  ----------  ------------
 * ADMIN001    MARGARET    GOLD        A
 * ADMIN002    RUSSELL     RUSSELL     A
 * ADMIN003    RAYMOND     WHITMORE    A
 * ADMIN004    EMMANUEL    CASGRAIN    A
 * ADMIN005    GRANVILLE   LACHAPELLE  A
 * USER0001    LAWRENCE    THOMAS      U
 * USER0002    AJITH       KUMAR       U
 * USER0003    LAURITZ     ALME        U
 * USER0004    AVERARDO    MAZZI       U
 * USER0005    LEE         TING        U
 * </pre>
 *
 * <p>Every identifier is exactly eight characters, so the identifier field is naturally full and
 * carries no padding; both name fields are upper case and space-padded to twenty. All ten records
 * carry <strong>one shared credential literal</strong>, and that literal is deliberately not
 * reproduced in this file, in any other Java source, in configuration, in a migration, in a log, in
 * a comment or in an assertion message. The seed migration that loads these ten stores digests
 * rather than the literal and is scoped to the local and test profiles only; it is a separate
 * artefact and no part of it lives here. This class embeds no seeded identity and no digest as a
 * constant - the table above is documentation of the fixture, not data this file holds.
 *
 * <h2>Failure contract and message hygiene</h2>
 *
 * <p>{@code fromRecord} validates the image as exactly {@value #RECORD_LENGTH} <em>encoded bytes</em>
 * and raises {@link IllegalArgumentException} for any other length, naming the artefact, the
 * declaring copybook, the expected width and the actual encoded byte length. {@code null} arguments
 * raise {@link NullPointerException} through {@link Objects#requireNonNull(Object, String)}. A
 * digest function that returns {@code null} raises {@link IllegalStateException}. Input is never
 * silently padded, never silently truncated, never partially mapped and never returned as
 * {@code null}.
 *
 * <p>No type from {@code com.carddemo.exception} is used, and that is deliberate: decision D-11
 * records that none of the module's six exception types models "the caller handed me the wrong number
 * of bytes", because a short record has no legacy antecedent at all - sequential and indexed dataset
 * records are fixed length by construction - so the condition is a caller defect rather than a
 * translated business outcome, and the platform exception is the honest one.
 *
 * <p><strong>Message hygiene is mandatory here in a way it is not elsewhere.</strong> No exception
 * message, no comment and no documentation line in this file contains a record image, any slice of
 * one, a cleartext credential or a digest. Diagnostics report field names, offsets and lengths and
 * nothing else. A diagnostic that echoed the offending record would print a credential, and an
 * exception message is one of the surfaces most likely to reach a log.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This class maps bytes to an entity and back. It does not authenticate, does not verify a
 * credential and does not compare one - the legacy direct comparison becomes a digest verification in
 * the sign-on service, and the sign-on message texts belong to the message catalogue. It performs no
 * validation of any kind: no identifier format check, no name check and no role-code whitelist,
 * because each would reject data the legacy system accepts. It seeds nothing, persists nothing, holds
 * no repository or entity manager, opens no transaction and logs nothing at all - this package is not
 * among the module's pinned logger names, so a logger here would be unconfigured, and for a
 * credential-bearing mapper an unconfigured logger is a leak waiting to happen. It carries no mutable
 * static state and no static field holding credential material of any kind. Note also that the schema
 * declares <strong>zero foreign keys in any migration version</strong>, so this mapper resolves no
 * association and reads no other table, and that the entity has <strong>no {@code @Version}
 * field</strong>, so no optimistic-locking token is read from or written to the record image.
 *
 * <p>Every byte position is placed by explicit offset arithmetic against the constants below, and
 * every slice and every placement goes through {@link FixedWidthFieldReader}: there is no
 * {@code substring} call in this file, no annotation-driven mapping, no reflection and no generated
 * code. The class is final, holds only immutable static members and is safe for concurrent use.
 *
 * <p>Provenance: repository SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @see UserSecurity
 * @see FixedWidthFieldReader
 */
public final class UserSecurityRecordMapper {

    /**
     * Name of the legacy record group, reported in every diagnostic this class raises so that a
     * failure identifies the layout it concerns rather than merely the class that noticed it.
     */
    public static final String ARTEFACT = "SEC-USER-DATA";

    /** Name of the copybook that declares the group, reported alongside {@link #ARTEFACT}. */
    public static final String COPYBOOK = "CSUSR01Y";

    /** Zero-based byte offset of {@code SEC-USR-ID}, the eight-character key and the JPA identifier. */
    public static final int SEC_USR_ID_OFFSET = 0;

    /** Encoded byte length of {@code SEC-USR-ID}, from {@code PIC X(08)}. */
    public static final int SEC_USR_ID_LENGTH = 8;

    /** Zero-based byte offset of {@code SEC-USR-FNAME}. */
    public static final int SEC_USR_FNAME_OFFSET = 8;

    /** Encoded byte length of {@code SEC-USR-FNAME}, from {@code PIC X(20)}. */
    public static final int SEC_USR_FNAME_LENGTH = 20;

    /** Zero-based byte offset of {@code SEC-USR-LNAME}. */
    public static final int SEC_USR_LNAME_OFFSET = 28;

    /** Encoded byte length of {@code SEC-USR-LNAME}, from {@code PIC X(20)}. */
    public static final int SEC_USR_LNAME_LENGTH = 20;

    /**
     * Zero-based byte offset of the credential window, {@code SEC-USR-PWD}.
     *
     * <p>Published as a constant so that the bound of the blank run
     * {@link #toRecord(UserSecurity)} emits is self-documenting at its single call site rather than
     * being a literal a reader has to check against the copybook.
     */
    public static final int SEC_USR_PWD_OFFSET = 48;

    /**
     * Encoded byte length of the credential window in the <em>legacy record</em>, from
     * {@code PIC X(08)}.
     *
     * <p>This is the width of the eight cleartext bytes the record carries and of the blank run
     * emitted in their place. It is deliberately <em>not</em> the width of the migrated column,
     * which holds a sixty-character digest instead: that intentional divergence is the estate's
     * single documented parity exception and is described in the class documentation.
     */
    public static final int SEC_USR_PWD_LENGTH = 8;

    /** Zero-based byte offset of {@code SEC-USR-TYPE}, the one-character role code. */
    public static final int SEC_USR_TYPE_OFFSET = 56;

    /** Encoded byte length of {@code SEC-USR-TYPE}, from {@code PIC X(01)}. */
    public static final int SEC_USR_TYPE_LENGTH = 1;

    /**
     * Sum of the five mapped field lengths, 8 + 20 + 20 + 8 + 1 = 57 encoded bytes.
     *
     * <p>Written as the sum rather than as the literal so that the layout arithmetic is enforced by
     * the code instead of being asserted next to it. Numerically it is also the offset at which the
     * filler begins, because the mapped fields are contiguous from zero: every byte below it belongs
     * to a mapped field and every byte at or above it is filler.
     */
    public static final int MAPPED_LENGTH = SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH
            + SEC_USR_LNAME_LENGTH + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH;

    /**
     * Zero-based byte offset at which the named trailing filler {@code SEC-USR-FILLER} begins, 57.
     *
     * <p>Equal to {@link #MAPPED_LENGTH} by construction, because the filler begins exactly where
     * the mapped fields end.
     */
    public static final int SEC_USR_FILLER_OFFSET = MAPPED_LENGTH;

    /**
     * Encoded byte length of {@code SEC-USR-FILLER}, from {@code PIC X(23)}.
     *
     * <p>Named in the source rather than anonymous, uniquely in this estate, and still neither
     * mapped nor persisted.
     */
    public static final int SEC_USR_FILLER_LENGTH = 23;

    /**
     * Full record width, 57 mapped bytes plus 23 filler bytes = 80 encoded bytes.
     *
     * <p>Derived from the layout rather than declared, and corroborated independently by the
     * provisioning job's {@code LRECL=80 RECFM=FB} and by its cluster record size.
     */
    public static final int RECORD_LENGTH = MAPPED_LENGTH + SEC_USR_FILLER_LENGTH;

    /**
     * Zero-based offset of the first reproducible comparison window, 0.
     *
     * <p>The window spans the identifier and both names - every leading field
     * {@link #toRecord(UserSecurity)} can reproduce byte for byte.
     */
    public static final int REPRODUCIBLE_PREFIX_OFFSET = SEC_USR_ID_OFFSET;

    /**
     * Length of the first reproducible comparison window, 8 + 20 + 20 = 48 encoded bytes, so the
     * window is {@code [0, 48)} and ends exactly where the credential window begins.
     */
    public static final int REPRODUCIBLE_PREFIX_LENGTH =
            SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH;

    /**
     * Zero-based offset of the second reproducible comparison window, 56 - the role code, the one
     * reproducible field that sits after the credential window.
     */
    public static final int REPRODUCIBLE_SUFFIX_OFFSET = SEC_USR_TYPE_OFFSET;

    /**
     * Length of the second reproducible comparison window, 1 encoded byte, so the window is
     * {@code [56, 57)} and ends exactly where the trailing filler begins.
     */
    public static final int REPRODUCIBLE_SUFFIX_LENGTH = SEC_USR_TYPE_LENGTH;

    /**
     * Artefact and copybook rendered as one subject for diagnostics, and the name handed to
     * {@link FixedWidthFieldReader} so that a diagnostic raised inside the reader names this layout
     * exactly as one raised here does.
     */
    private static final String DIAGNOSTIC_SUBJECT = ARTEFACT + " (" + COPYBOOK + ")";

    /** Legacy field name for the key, used only to name the field in diagnostics. */
    private static final String SEC_USR_ID = "SEC-USR-ID";

    /** Legacy field name for the given name, used only to name the field in diagnostics. */
    private static final String SEC_USR_FNAME = "SEC-USR-FNAME";

    /** Legacy field name for the family name, used only to name the field in diagnostics. */
    private static final String SEC_USR_LNAME = "SEC-USR-LNAME";

    /**
     * Legacy field name for the credential window, used only to name the field in diagnostics.
     * Naming the field is safe; naming its value never happens anywhere in this class.
     */
    private static final String SEC_USR_PWD = "SEC-USR-PWD";

    /** Legacy field name for the role code, used only to name the field in diagnostics. */
    private static final String SEC_USR_TYPE = "SEC-USR-TYPE";

    /**
     * The single message for a missing digest function, shared by all three decoding entry points so
     * that the same omission reports the same reason however the caller reached it.
     */
    private static final String DIGEST_FUNCTION_REQUIRED =
            "credentialDigestFunction must not be null: mapping a " + ARTEFACT + " record requires"
            + " the caller's digest function, because this mapper deliberately provides no path that"
            + " maps a record without hashing its credential";

    /**
     * Not instantiable: this class exposes static members only, holds no state and has nothing an
     * instance could usefully own.
     */
    private UserSecurityRecordMapper() {
        throw new AssertionError(
                "UserSecurityRecordMapper is a static contract and is not instantiable");
    }

    /**
     * Maps an 80-byte record image supplied as a string, hashing its credential through the caller's
     * digest function.
     *
     * <p>The image must be exactly {@value #RECORD_LENGTH} encoded bytes and must carry no line
     * terminator: the nine ASCII fixtures in this estate are newline-terminated, so a caller reading
     * lines must exclude the {@code 0x0A} separator, which is a record separator and never record
     * content. The four non-credential fields are copied out raw - untrimmed, unstripped, not
     * case-folded and not normalised - because the identifier is naturally full at eight characters
     * and both names are space-padded to twenty, and trimming either would change the value the
     * legacy record carries.
     *
     * <p>The eight cleartext credential bytes are sliced and handed straight to
     * {@code credentialDigestFunction} inside a single expression, so the cleartext is never bound to
     * a name anywhere in this class, and the digest the function returns is what reaches the entity.
     * The cleartext is passed over unaltered: it is not trimmed, validated, case-folded or
     * normalised here, because the caller's encoder owns every remaining decision about it. There is
     * deliberately no overload that omits this parameter.
     *
     * @param recordImage              the complete 80-byte record image, excluding any line
     *                                 terminator; must not be {@code null}
     * @param credentialDigestFunction the caller's one-way digest function, owned by the
     *                                 {@code service} or {@code config} layer; required, and applied
     *                                 exactly once to the cleartext credential slice. It must return
     *                                 a non-{@code null} digest
     * @return a fully populated entity whose stored credential is the function's output and never a
     *         cleartext value, never {@code null}
     * @throws NullPointerException     if {@code recordImage} or {@code credentialDigestFunction} is
     *                                  {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} encoded
     *                                  bytes, if it contains a character US-ASCII cannot represent,
     *                                  or if the value the digest function returns is not
     *                                  structurally a digest, which {@link UserSecurity} refuses
     * @throws IllegalStateException    if the digest function returns {@code null}, breaking its
     *                                  contract
     */
    public static UserSecurity fromRecord(String recordImage,
                                          UnaryOperator<String> credentialDigestFunction) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        Objects.requireNonNull(credentialDigestFunction, DIGEST_FUNCTION_REQUIRED);
        // Measured through the reader rather than by encoding here, because that helper rejects a
        // character US-ASCII cannot represent instead of substituting a question mark for it: a
        // substitution would report a plausible width for an image whose geometry is actually wrong.
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return map(FixedWidthFieldReader.of(DIAGNOSTIC_SUBJECT, recordImage, RECORD_LENGTH),
                credentialDigestFunction);
    }

    /**
     * Maps an 80-byte record image supplied as bytes, hashing its credential through the caller's
     * digest function.
     *
     * <p>Preferred over {@link #fromRecord(String, UnaryOperator)} when the caller already holds raw
     * bytes, because it removes any need for the caller to choose a charset. Behaves identically in
     * every other respect, including the single-use handling of the cleartext credential and the
     * absence of any overload that omits the digest function.
     *
     * @param recordImage              the complete 80-byte record image as bytes, excluding any line
     *                                 terminator; must not be {@code null}
     * @param credentialDigestFunction the caller's one-way digest function; required, and applied
     *                                 exactly once to the cleartext credential slice
     * @return a fully populated entity whose stored credential is the function's output, never
     *         {@code null}
     * @throws NullPointerException     if {@code recordImage} or {@code credentialDigestFunction} is
     *                                  {@code null}
     * @throws IllegalArgumentException if the array is not exactly {@value #RECORD_LENGTH} bytes, if
     *                                  any byte is not 7-bit ASCII, or if the value the digest
     *                                  function returns is not structurally a digest
     * @throws IllegalStateException    if the digest function returns {@code null}
     */
    public static UserSecurity fromRecord(byte[] recordImage,
                                          UnaryOperator<String> credentialDigestFunction) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        Objects.requireNonNull(credentialDigestFunction, DIGEST_FUNCTION_REQUIRED);
        requireRecordWidth(recordImage.length);
        return map(FixedWidthFieldReader.of(DIAGNOSTIC_SUBJECT, recordImage, RECORD_LENGTH),
                credentialDigestFunction);
    }

    /**
     * Maps one 80-byte record held inside a larger buffer, hashing its credential through the
     * caller's digest function.
     *
     * <p>This is the seam for a batch reader that holds a whole newline-terminated fixed-width file
     * in one buffer. The stride of such a file is one greater than the record width, so record
     * <em>i</em> is addressed as {@code fromRecord(buffer, i * (RECORD_LENGTH + 1), digest)}, which
     * selects the record and leaves the {@code 0x0A} terminator behind. Stride arithmetic and file
     * access stay with the caller.
     *
     * <p>No width comparison is made here, and that is deliberate rather than an omission: the record
     * width is fixed at {@value #RECORD_LENGTH} and the caller declares where the record starts, so
     * there is no supplied width to disagree with a declared one. The geometry check that remains -
     * that the range lies wholly inside the buffer - is made once, inside
     * {@link FixedWidthFieldReader}, so the bound is implemented in exactly one place.
     *
     * @param buffer                   buffer containing the record, and possibly many others; must
     *                                 not be {@code null}
     * @param from                     zero-based index in {@code buffer} at which the record starts;
     *                                 must not be negative, and the {@value #RECORD_LENGTH} bytes
     *                                 from it must lie inside the buffer
     * @param credentialDigestFunction the caller's one-way digest function; required, and applied
     *                                 exactly once to the cleartext credential slice
     * @return a fully populated entity whose stored credential is the function's output, never
     *         {@code null}
     * @throws NullPointerException     if {@code buffer} or {@code credentialDigestFunction} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the selected range is not
     *                                  wholly inside the buffer, if any byte in it is not 7-bit
     *                                  ASCII, or if the value the digest function returns is not
     *                                  structurally a digest
     * @throws IllegalStateException    if the digest function returns {@code null}
     */
    public static UserSecurity fromRecord(byte[] buffer, int from,
                                          UnaryOperator<String> credentialDigestFunction) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        Objects.requireNonNull(credentialDigestFunction, DIGEST_FUNCTION_REQUIRED);
        return map(FixedWidthFieldReader.of(DIAGNOSTIC_SUBJECT, buffer, from, RECORD_LENGTH),
                credentialDigestFunction);
    }

    /**
     * Emits an 80-byte record image whose credential window {@code [48, 56)} is <strong>eight
     * spaces</strong>, so the result is <strong>not</strong> byte-identical to any legacy record and
     * must never be treated as one.
     *
     * <p>That is not a limitation to be worked around; it is the contract. The migrated credential is
     * a one-way digest sixty characters wide, and neither property can be undone: a digest cannot be
     * reversed into the eight cleartext bytes the legacy record held, and sixty characters do not fit
     * an eight-byte field. <strong>No part of a digest is ever written into the image</strong> - not
     * truncated to eight bytes, not as a prefix, not as a fragment - because a truncated digest would
     * be simultaneously wrong data and a credential fragment leaked into a file artefact. The
     * guarantee is structural: this method never reads the stored digest at all, so it holds nothing
     * it could write, and the window is produced by declaring a blank run rather than by placing a
     * field.
     *
     * <p>The named trailing filler {@code [57, 80)} is emitted as spaces, the module-wide default
     * recorded as decision D-10. COBOL filler with no {@code VALUE} clause is uninitialised, so no
     * byte value is canonical, and this layout has no ASCII fixture to witness one.
     *
     * <p><strong>Comparison windows.</strong> A round trip through
     * {@link #fromRecord(String, UnaryOperator)} and back reproduces exactly two windows byte for
     * byte: {@code [0, 48)}, the identifier and both names, and {@code [56, 57)}, the role code.
     * Those bounds are published as {@link #REPRODUCIBLE_PREFIX_OFFSET} with
     * {@link #REPRODUCIBLE_PREFIX_LENGTH} and {@link #REPRODUCIBLE_SUFFIX_OFFSET} with
     * {@link #REPRODUCIBLE_SUFFIX_LENGTH}. The credential window is excluded because it is emitted
     * blank and the filler because its bytes are uninitialised in the source. <strong>A whole-record
     * 80-byte comparison is meaningless for this layout and must never be asserted.</strong>
     *
     * <p>Field values are emitted exactly as the entity holds them: left-justified and space-padded
     * to the declared width, with no trimming, case folding or normalisation, so a value that
     * arrived padded leaves padded. A value wider than its field is rejected rather than truncated,
     * and a {@code null} value is rejected rather than replaced by spaces, because a fixed-width
     * field can only be completed from an absent value by inventing one.
     *
     * @param user the entity to render; must not be {@code null}
     * @return an image of exactly {@value #RECORD_LENGTH} encoded bytes with the credential window
     *         blank, never {@code null}
     * @throws NullPointerException     if {@code user} is {@code null}, or if any of the four
     *                                  reproducible properties is {@code null}
     * @throws IllegalArgumentException if any value is wider than its declared field or contains a
     *                                  character US-ASCII cannot represent
     */
    public static String toRecord(UserSecurity user) {
        return assemble(user).image();
    }

    /**
     * Emits the same 80-byte image as {@link #toRecord(UserSecurity)} as bytes, with the credential
     * window {@code [48, 56)} blank and therefore <strong>not</strong> byte-identical to any legacy
     * record.
     *
     * <p>Provided for a batch writer that streams bytes rather than strings; the contract, the blank
     * credential window, the space filler and the two reproducible comparison windows are exactly
     * those documented on {@link #toRecord(UserSecurity)}. The returned array is a fresh copy that
     * the caller may mutate freely.
     *
     * @param user the entity to render; must not be {@code null}
     * @return a new array of exactly {@value #RECORD_LENGTH} bytes with the credential window blank
     * @throws NullPointerException     if {@code user} is {@code null}, or if any of the four
     *                                  reproducible properties is {@code null}
     * @throws IllegalArgumentException if any value is wider than its declared field or contains a
     *                                  character US-ASCII cannot represent
     */
    public static byte[] toRecordBytes(UserSecurity user) {
        return assemble(user).toByteArray();
    }

    /**
     * Places the five declared fields and the named filler into a fresh image.
     *
     * <p>Shared by both encoding entry points so that the placements, the blank credential window and
     * the filler run are written once and cannot drift apart. The six placements cover
     * {@code [0, 80)} without a gap and without an overlap, which is what makes a missing field
     * visible during review: the builder rejects any placement that falls outside the record, so an
     * offset that does not add up cannot survive a single execution.
     *
     * @param user the entity to render
     * @return a reader over the completed image, so a caller may slice it immediately
     */
    private static FixedWidthFieldReader assemble(UserSecurity user) {
        Objects.requireNonNull(user, "user must not be null");
        return FixedWidthFieldReader.builder(DIAGNOSTIC_SUBJECT, RECORD_LENGTH)
                .putAlphanumeric(SEC_USR_ID, SEC_USR_ID_OFFSET, SEC_USR_ID_LENGTH,
                        requireProperty(user.getSecUsrId(), SEC_USR_ID))
                .putAlphanumeric(SEC_USR_FNAME, SEC_USR_FNAME_OFFSET, SEC_USR_FNAME_LENGTH,
                        requireProperty(user.getSecUsrFname(), SEC_USR_FNAME))
                .putAlphanumeric(SEC_USR_LNAME, SEC_USR_LNAME_OFFSET, SEC_USR_LNAME_LENGTH,
                        requireProperty(user.getSecUsrLname(), SEC_USR_LNAME))
                // The credential window is declared as a blank run rather than placed as a field.
                // There is deliberately no value to place: this class never reads the stored digest,
                // so it holds nothing that could reach these eight bytes, and no digest fragment can
                // therefore appear in a record image no matter how this method is called.
                .putSpaceFiller(SEC_USR_PWD_OFFSET, SEC_USR_PWD_LENGTH)
                .putAlphanumeric(SEC_USR_TYPE, SEC_USR_TYPE_OFFSET, SEC_USR_TYPE_LENGTH,
                        requireProperty(user.getSecUsrType(), SEC_USR_TYPE))
                // The named trailing filler. Space is the module default, decision D-10.
                .putSpaceFiller(SEC_USR_FILLER_OFFSET, SEC_USR_FILLER_LENGTH)
                .build();
    }

    /**
     * Builds the entity from a validated image, hashing the credential exactly once on the way.
     *
     * <p>Construction goes through the entity's five-argument constructor because that is the only
     * accessible route - the no-argument constructor is reserved for the persistence provider - and
     * because the constructor refuses any credential argument that is not structurally a digest.
     * That refusal is the reason a mis-supplied identity function cannot store a cleartext value:
     * the construction fails instead, and it fails without echoing what it rejected.
     *
     * <p>The credential slice is produced and consumed within one expression, so no local variable,
     * field, collection or return value in this class ever refers to the cleartext.
     *
     * @param record                   a reader over an image already validated as
     *                                 {@value #RECORD_LENGTH} bytes
     * @param credentialDigestFunction the caller's digest function, already checked non-null
     * @return the populated entity, never {@code null}
     */
    private static UserSecurity map(FixedWidthFieldReader record,
                                    UnaryOperator<String> credentialDigestFunction) {
        return new UserSecurity(
                record.field(SEC_USR_ID, SEC_USR_ID_OFFSET, SEC_USR_ID_LENGTH),
                record.field(SEC_USR_FNAME, SEC_USR_FNAME_OFFSET, SEC_USR_FNAME_LENGTH),
                record.field(SEC_USR_LNAME, SEC_USR_LNAME_OFFSET, SEC_USR_LNAME_LENGTH),
                requireDigest(credentialDigestFunction.apply(
                        record.field(SEC_USR_PWD, SEC_USR_PWD_OFFSET, SEC_USR_PWD_LENGTH))),
                record.field(SEC_USR_TYPE, SEC_USR_TYPE_OFFSET, SEC_USR_TYPE_LENGTH));
    }

    /**
     * Rejects a digest function that returned nothing.
     *
     * <p>The mapper has no default, no fallback and no unhashed path to fall back on, so a
     * {@code null} return leaves it unable to complete a record rather than able to complete a
     * degraded one. The message names the broken contract and never the value, in either direction:
     * a rejected return is not printed and neither is the cleartext that produced it.
     *
     * @param digest whatever the function returned
     * @return the digest, unchanged, when the function honoured its contract
     * @throws IllegalStateException if the function returned {@code null}
     */
    private static String requireDigest(String digest) {
        if (digest == null) {
            throw new IllegalStateException("the supplied credentialDigestFunction returned null,"
                    + " breaking its contract: it must return a digest for every value handed to it,"
                    + " because " + DIAGNOSTIC_SUBJECT + " field '" + SEC_USR_PWD + "' maps to a"
                    + " non-nullable digest column and this mapper has no default, no fallback and"
                    + " no unhashed path to substitute");
        }
        return digest;
    }

    /**
     * Rejects an image whose encoded byte length is not exactly the declared record width.
     *
     * <p>Stated and enforced here rather than inherited from the reader, so that the width this
     * layout requires is a property of this mapper and fails visibly if the layout is ever edited.
     * The check is a Java-only defensive guard with no legacy antecedent: sequential and indexed
     * dataset records are fixed length by construction, so the legacy programs never had a
     * wrong-length record to handle. Decision D-11 records why the platform exception is used rather
     * than one of the module's own six types.
     *
     * @param actualEncodedLength the encoded byte length actually supplied
     * @throws IllegalArgumentException if it differs from {@value #RECORD_LENGTH}
     */
    private static void requireRecordWidth(int actualEncodedLength) {
        if (actualEncodedLength != RECORD_LENGTH) {
            throw new IllegalArgumentException(DIAGNOSTIC_SUBJECT + " record image must be exactly "
                    + RECORD_LENGTH + " encoded bytes (" + MAPPED_LENGTH + " mapped plus "
                    + SEC_USR_FILLER_LENGTH + " bytes of named trailing filler), but the supplied"
                    + " image is " + actualEncodedLength + " encoded bytes; a fixed-width record is"
                    + " never padded or truncated to fit, and this diagnostic reports lengths only,"
                    + " because echoing the record would print a credential");
        }
    }

    /**
     * Rejects a {@code null} field value on the encoding path, naming the field that was absent.
     *
     * <p>The builder would reject it too, but with a message that cannot say which field was missing,
     * and on a six-placement record that difference is the whole diagnostic. The message names the
     * field and the layout and never the value.
     *
     * @param value     the property value read from the entity
     * @param fieldName the legacy field name it is destined for
     * @return the value, unchanged, when it is present
     * @throws NullPointerException if the value is {@code null}
     */
    private static String requireProperty(String value, String fieldName) {
        return Objects.requireNonNull(value, () -> DIAGNOSTIC_SUBJECT + " field '" + fieldName
                + "' must not be null when a record image is assembled: a fixed-width field is"
                + " emitted at its declared width, and an absent value has no width, so the record"
                + " could only be completed by inventing one");
    }
}
