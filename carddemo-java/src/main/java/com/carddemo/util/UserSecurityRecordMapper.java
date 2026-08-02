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
 * <p>This mapper is the odd one out twice over: it is the <strong>only</strong> one that is
 * deliberately <strong>not</strong> round-trippable, and the <strong>only</strong> one whose record
 * carries a credential. Both properties follow from a single asymmetry. The legacy record holds the
 * sign-on credential in the clear in eight bytes, the migrated column holds a sixty-character
 * one-way digest instead, and a one-way digest cannot be reversed back into the eight bytes it
 * replaced. Every decision below follows from that asymmetry rather than from taste.
 *
 * <p>Layout authority is the record group declared in {@code app/cpy/CSUSR01Y.cpy}: an
 * eight-character identifier that is also the JPA identifier and a natural key, two twenty-character
 * name fields, the eight-byte credential window, a one-character role code, and a trailing filler
 * run. Offsets are zero-based byte positions in the record image and lengths are encoded byte
 * counts, never character counts. The mapped fields are contiguous from zero, so
 * {@link #MAPPED_LENGTH} is declared as their sum and {@link #RECORD_LENGTH} as that sum plus the
 * filler: the record width is derived from the layout rather than asserted alongside it, and the
 * derived 80 is corroborated independently by the provisioning job, which writes the sequential
 * dataset at that width {@code [app/jcl/DUSRSECJ.jcl]}. Nothing in this layout is numeric, so
 * nothing is parsed and the module's zoned-decimal codec is neither imported nor invoked.
 *
 * <p>The trailing filler is <em>named</em> here, uniquely in this estate, where every other layout
 * ends in an anonymous run. The naming changes no offset, no width and no behaviour - the bytes are
 * still unmapped, unpersisted, without a Java property or column, and still reconstructed on output
 * from the declared width rather than carried on the entity. It is called out because a reader
 * comparing this layout with its siblings will notice the difference, and an unexplained difference
 * invites someone to "fix" it. Filler with no initialising clause is uninitialised, so no byte value
 * is canonical; space is the module-wide default, and this layout has no fixture to witness one.
 *
 * <p><strong>The credential is the estate's single deliberate parity exception.</strong> The legacy
 * field is eight cleartext characters held inside the record, and legacy sign-on authenticates by
 * comparing that stored field <em>directly</em> against the value keyed at the terminal
 * {@code [app/cbl/COSGN00C.cbl]}. Reproducing that faithfully would satisfy byte-for-byte parity and
 * breach the binding no-hardcoded-credentials requirement in the same stroke. The resolution, and it
 * is binding on this file: the eight cleartext bytes are read out of the image and
 * <strong>immediately</strong> transformed into a BCrypt digest, and the digest is what the entity
 * and its column hold - the entity never holds a cleartext value at any point. The legacy field is
 * eight characters wide and the target column is sixty; the widths are <strong>intentionally</strong>
 * different, this is the only column in the schema whose width does not equal its picture width, and
 * that is precisely why this layout is not round-trippable. The module's faithful-beats-idiomatic
 * tie-break yields here, and only here, because faithfulness would violate the credential
 * constraint. It is recorded as the flagship security entry in {@code docs/decision-log.md} so a
 * reviewer reads it as a documented exception rather than an accidental regression.
 *
 * <p>Because a digest is one way and sixty characters do not fit an eight-byte field,
 * {@link #toRecord(UserSecurity)} cannot reconstruct a legacy image and emits the credential window
 * as eight spaces. <strong>No part of a digest is ever written into an image</strong> - not
 * truncated, not as a prefix, not as a fragment - because a truncated digest would be wrong data and
 * a leaked credential fragment at the same time. The guarantee is structural rather than a matter of
 * care: <strong>this class never reads the stored digest at all</strong>, so it holds nothing it
 * could write, and the window is produced by declaring a blank run rather than by placing a field.
 * Round-trip verification therefore compares exactly two windows - the identifier with both names,
 * and the role code - published as {@link #REPRODUCIBLE_PREFIX_OFFSET} with
 * {@link #REPRODUCIBLE_PREFIX_LENGTH} and {@link #REPRODUCIBLE_SUFFIX_OFFSET} with
 * {@link #REPRODUCIBLE_SUFFIX_LENGTH} so a caller states them rather than recomputing them. The
 * credential window is excluded because it is emitted blank and the filler because its bytes are
 * uninitialised in the source. <strong>A whole-record comparison is meaningless for this layout and
 * must never be asserted.</strong>
 *
 * <p><strong>The digest step is injected, never performed here.</strong> Producing a digest is not a
 * pure operation - each embeds fresh randomness - and it needs an encoder belonging to a higher
 * layer. Two constraints meet: the utility layer may not depend on {@code service}, {@code config}
 * or on any cryptography type, and the build must stay hermetic, so no digest library may be added
 * for the sake of one field. Both are satisfied the same way. Every {@code fromRecord} overload
 * takes a {@link UnaryOperator} of {@link String} and hands the cleartext slice straight to it,
 * and the caller supplies it. No digest algorithm, randomness source or work factor exists anywhere
 * in this file; the work factor is a configuration decision.
 *
 * <p>Four properties of that parameter are contractual. It is <strong>required</strong>, never
 * optional. <strong>There is deliberately no overload, no convenience factory and no code path of
 * any kind that skips it or that places a cleartext value on the entity</strong> - if such a path
 * existed, someone would eventually call it. The cleartext slice is used <strong>exactly once</strong>
 * and is never bound to a local, a field, a collection, a return value, a message or an exception:
 * it is produced by the reader and consumed by the function inside a single expression, so no name
 * in this file ever refers to it. And it is handed over <strong>unaltered</strong> - not trimmed,
 * stripped, case-folded, normalised or validated - because the legacy field is a fixed eight bytes
 * and every remaining decision belongs to the caller's encoder. Defence in depth backs this up from
 * the other side: {@link UserSecurity} refuses any credential value that is not structurally a
 * digest, in both its constructor and its replacement method, so even a caller who mistakenly
 * supplies {@link UnaryOperator#identity()} cannot get a cleartext value into the column - the
 * construction fails instead, and fails without echoing the value it rejected.
 *
 * <p>The role code stays a raw one-character value and <strong>no enum translation happens
 * here</strong>; that belongs to the {@code service} and {@code config} layers. The reason is
 * behavioural rather than stylistic: legacy sign-on tests only the administrator condition and
 * reaches the main menu through an unconditional alternative, so an unrecognised code routes rather
 * than fails, and a value the legacy system accepted must survive this mapper rather than be
 * rejected by it.
 *
 * <p>This layout is the only one of the twelve mainframe datasets with no ASCII fixture, which would
 * ordinarily make its content the one thing in the estate needing an encoding conversion. It does
 * not: the provisioning job carries its records in stream as readable ASCII card images, so no
 * EBCDIC decode is required anywhere in the migration. Every seeded identifier is exactly eight
 * characters, so that field is naturally full and carries no padding, and both name fields are
 * space-padded to twenty. The seeded records share one credential literal, which is deliberately not
 * reproduced in this file, in any other source, in configuration, in a migration, in a log, in a
 * comment or in an assertion message; the seed migration stores digests rather than the literal and
 * is scoped to the local and test profiles. This class embeds no seeded identity and no digest as a
 * constant.
 *
 * <p>An image whose encoded length is not exactly {@value #RECORD_LENGTH} raises
 * {@link IllegalArgumentException} naming the artefact, the declaring copybook, the expected width
 * and the actual length; {@code null} arguments raise {@link NullPointerException}; a digest function
 * returning {@code null} raises {@link IllegalStateException}. Input is never silently padded,
 * truncated, partially mapped or returned as {@code null}. No type from this module's own exception
 * package is used, deliberately: none of them models a caller supplying the wrong number of bytes,
 * which has no legacy antecedent at all because the legacy records are fixed length by construction.
 *
 * <p><strong>Message hygiene is mandatory here in a way it is not elsewhere.</strong> No exception
 * message, comment or documentation line in this file contains a record image, any slice of one, a
 * cleartext credential or a digest. Diagnostics report field names, offsets and lengths and nothing
 * else, because a diagnostic that echoed the offending record would print a credential and an
 * exception message is one of the surfaces most likely to reach a log.
 *
 * <p>Boundaries: this class maps bytes to an entity and back. It does not authenticate, verify a
 * credential or compare one - the legacy direct comparison becomes a digest verification in the
 * sign-on service. It performs no validation of any kind, no identifier format check, no name check
 * and no role-code whitelist, because each would reject data the legacy system accepts. It seeds
 * nothing, persists nothing, holds no repository or entity manager, opens no transaction, reads no
 * optimistic-locking token - the entity has none - and <strong>logs nothing at all</strong>: this
 * package is not among the module's pinned logger names, so a logger here would be unconfigured, and
 * for a credential-bearing mapper an unconfigured logger is a leak waiting to happen. Every byte
 * position is placed by explicit offset arithmetic against the constants below and every slice and
 * placement goes through {@link FixedWidthFieldReader}, so there is no {@code substring} call, no
 * annotation-driven mapping, no reflection and no generated code. The class is final, holds only
 * immutable static members, and is safe for concurrent use.
 *
 * @see UserSecurity
 * @see FixedWidthFieldReader
 */
public final class UserSecurityRecordMapper {

    /** Name of the legacy record group, reported in every diagnostic this class raises. */
    public static final String ARTEFACT = "SEC-USER-DATA";

    public static final String COPYBOOK = "CSUSR01Y";

    public static final int SEC_USR_ID_OFFSET = 0;

    public static final int SEC_USR_ID_LENGTH = 8;

    public static final int SEC_USR_FNAME_OFFSET = 8;

    public static final int SEC_USR_FNAME_LENGTH = 20;

    public static final int SEC_USR_LNAME_OFFSET = 28;

    public static final int SEC_USR_LNAME_LENGTH = 20;

    /**
     * Offset of the credential window, published so the bound of the blank run emitted in its place is
     * self-documenting at its single call site.
     */
    public static final int SEC_USR_PWD_OFFSET = 48;

    /**
     * Width of the credential window in the <em>legacy record</em>: the eight cleartext bytes it
     * carries, and of the blank run emitted in their place. Deliberately <em>not</em> the width of the
     * migrated column, which holds a sixty-character digest instead.
     */
    public static final int SEC_USR_PWD_LENGTH = 8;

    public static final int SEC_USR_TYPE_OFFSET = 56;

    public static final int SEC_USR_TYPE_LENGTH = 1;

    /**
     * Sum of the mapped field lengths, written as the sum rather than a literal so the layout arithmetic
     * is enforced by the code instead of asserted next to it. Numerically also the offset at which the
     * filler begins, because the mapped fields are contiguous from zero.
     */
    public static final int MAPPED_LENGTH = SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH
            + SEC_USR_LNAME_LENGTH + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH;

    public static final int SEC_USR_FILLER_OFFSET = MAPPED_LENGTH;

    /**
     * Width of the named trailing filler: named in the source, uniquely in this estate, and still
     * neither mapped nor persisted.
     */
    public static final int SEC_USR_FILLER_LENGTH = 23;

    /** Full record width, derived from the mapped length plus the filler rather than declared. */
    public static final int RECORD_LENGTH = MAPPED_LENGTH + SEC_USR_FILLER_LENGTH;

    public static final int REPRODUCIBLE_PREFIX_OFFSET = SEC_USR_ID_OFFSET;

    /**
     * Length of the first reproducible comparison window - the identifier and both names - which ends
     * exactly where the credential window begins.
     */
    public static final int REPRODUCIBLE_PREFIX_LENGTH =
            SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH;

    public static final int REPRODUCIBLE_SUFFIX_OFFSET = SEC_USR_TYPE_OFFSET;

    /**
     * Length of the second reproducible comparison window - the role code alone - which ends exactly
     * where the trailing filler begins.
     */
    public static final int REPRODUCIBLE_SUFFIX_LENGTH = SEC_USR_TYPE_LENGTH;

    /** Artefact and copybook rendered as one subject for diagnostics. */
    private static final String DIAGNOSTIC_SUBJECT = ARTEFACT + " (" + COPYBOOK + ")";

    private static final String SEC_USR_ID = "SEC-USR-ID";

    private static final String SEC_USR_FNAME = "SEC-USR-FNAME";

    private static final String SEC_USR_LNAME = "SEC-USR-LNAME";

    /** Naming this field in a diagnostic is safe; naming its value never happens anywhere here. */
    private static final String SEC_USR_PWD = "SEC-USR-PWD";

    private static final String SEC_USR_TYPE = "SEC-USR-TYPE";

    /**
     * The single message for a missing digest function, shared by all three decoding entry points so the
     * same omission reports the same reason however the caller reached it.
     */
    private static final String DIGEST_FUNCTION_REQUIRED =
            "credentialDigestFunction must not be null: mapping a " + ARTEFACT + " record requires"
            + " the caller's digest function, because this mapper deliberately provides no path that"
            + " maps a record without hashing its credential";

    /** Not instantiable: static members only, no state. */
    private UserSecurityRecordMapper() {
        throw new AssertionError(
                "UserSecurityRecordMapper is a static contract and is not instantiable");
    }

    /**
     * Maps an 80-byte record image supplied as a string, hashing its credential through the caller's
     * digest function.
     *
     * <p>The image must carry no line terminator: the estate's fixtures are newline-terminated, and
     * the separator is never record content. The four non-credential fields are copied out raw -
     * untrimmed, not case-folded, not normalised - because the identifier is naturally full and both
     * names are space-padded, so trimming either would change the value the record carries. The
     * cleartext credential slice is handed straight to {@code credentialDigestFunction} inside a
     * single expression, unaltered, so it is never bound to a name anywhere in this class.
     *
     * @param recordImage              the complete record image, excluding any line terminator
     * @param credentialDigestFunction the caller's one-way digest function, owned by the
     *                                 {@code service} or {@code config} layer; required, applied
     *                                 exactly once, and must return a non-{@code null} digest
     * @return an entity whose stored credential is the function's output and never a cleartext value
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} encoded
     *                                  bytes, if a character cannot be represented in US-ASCII, or
     *                                  if the digest function's return is not structurally a digest,
     *                                  which {@link UserSecurity} refuses
     * @throws IllegalStateException    if the digest function returns {@code null}
     */
    public static UserSecurity fromRecord(String recordImage,
                                          UnaryOperator<String> credentialDigestFunction) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        Objects.requireNonNull(credentialDigestFunction, DIGEST_FUNCTION_REQUIRED);
        // Measured through the reader rather than by encoding here, because that helper rejects a
        // character US-ASCII cannot represent instead of substituting for it: a substitution would
        // report a plausible width for an image whose geometry is actually wrong.
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return map(FixedWidthFieldReader.of(DIAGNOSTIC_SUBJECT, recordImage, RECORD_LENGTH),
                credentialDigestFunction);
    }

    /**
     * Maps an 80-byte record image supplied as bytes, hashing its credential through the caller's
     * digest function.
     *
     * <p>Preferred when the caller already holds raw bytes, because it removes any need to choose a
     * charset. Identical in every other respect, including the single-use handling of the cleartext
     * credential and the absence of any overload that omits the digest function.
     *
     * @param recordImage              the complete record image as bytes, excluding any terminator
     * @param credentialDigestFunction the caller's one-way digest function; required, applied once
     * @return an entity whose stored credential is the function's output
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the array is not exactly {@value #RECORD_LENGTH} bytes, if
     *                                  any byte is not 7-bit ASCII, or if the digest function's
     *                                  return is not structurally a digest
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
     * <p>The seam for a batch reader holding a whole newline-terminated fixed-width file in one
     * buffer. Such a file has a stride one greater than the record width, so record <em>i</em>
     * starts at {@code i * (RECORD_LENGTH + 1)}, which selects the record and leaves its terminator
     * behind; stride arithmetic and file access stay with the caller. No width comparison is made
     * here, deliberately rather than by omission: the width is fixed and the caller declares only
     * where the record starts, so there is no supplied width to disagree with a declared one. The
     * remaining geometry check - that the range lies wholly inside the buffer - is made once, inside
     * {@link FixedWidthFieldReader}.
     *
     * @param buffer                   buffer containing the record, and possibly many others
     * @param from                     zero-based index at which the record starts
     * @param credentialDigestFunction the caller's one-way digest function; required, applied once
     * @return an entity whose stored credential is the function's output
     * @throws NullPointerException     if {@code buffer} or the digest function is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the selected range is not
     *                                  wholly inside the buffer, if any byte in it is not 7-bit
     *                                  ASCII, or if the digest function's return is not
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
     * Emits an 80-byte record image whose credential window is <strong>eight spaces</strong>, so the
     * result is <strong>not</strong> byte-identical to any legacy record and must never be treated
     * as one.
     *
     * <p>That is the contract, not a limitation to work around, and the class documentation records
     * why. <strong>No part of a digest is ever written into the image</strong>, and the guarantee is
     * structural: this method never reads the stored digest, so it holds nothing it could write, and
     * the window is produced by declaring a blank run rather than by placing a field. The named
     * trailing filler is emitted as spaces, the module-wide default. A round trip reproduces exactly
     * the two windows published by the {@code REPRODUCIBLE_} constants; <strong>a whole-record
     * comparison is meaningless for this layout and must never be asserted.</strong>
     *
     * <p>Field values are emitted exactly as the entity holds them, left-justified and space-padded
     * to the declared width, with no trimming, case folding or normalisation, so a value that
     * arrived padded leaves padded. A value wider than its field is rejected rather than truncated
     * and a {@code null} is rejected rather than replaced by spaces, because a fixed-width field can
     * only be completed from an absent value by inventing one.
     *
     * @param user the entity to render
     * @return an image of exactly {@value #RECORD_LENGTH} encoded bytes, credential window blank
     * @throws NullPointerException     if {@code user} or any reproducible property is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or cannot be represented
     *                                  in US-ASCII
     */
    public static String toRecord(UserSecurity user) {
        return assemble(user).image();
    }

    /**
     * Emits the same image as {@link #toRecord(UserSecurity)} as bytes, credential window blank and
     * therefore <strong>not</strong> byte-identical to any legacy record.
     *
     * <p>For a batch writer that streams bytes rather than strings; the contract is exactly that of
     * {@link #toRecord(UserSecurity)}. The returned array is a fresh copy the caller may mutate.
     *
     * @param user the entity to render
     * @return a new array of exactly {@value #RECORD_LENGTH} bytes, credential window blank
     * @throws NullPointerException     if {@code user} or any reproducible property is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or cannot be represented
     *                                  in US-ASCII
     */
    public static byte[] toRecordBytes(UserSecurity user) {
        return assemble(user).toByteArray();
    }

    /**
     * Places the declared fields and the named filler into a fresh image.
     *
     * <p>Shared by both encoding entry points so the placements, the blank credential window and the
     * filler run are written once and cannot drift apart. The placements cover the whole record
     * without a gap or an overlap, and the builder rejects any placement falling outside it, so an
     * offset that does not add up cannot survive a single execution.
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
                // The credential window is a blank run, not a field placement: this class never reads the
                // stored digest, so no digest fragment can reach these bytes however it is called.
                .putSpaceFiller(SEC_USR_PWD_OFFSET, SEC_USR_PWD_LENGTH)
                .putAlphanumeric(SEC_USR_TYPE, SEC_USR_TYPE_OFFSET, SEC_USR_TYPE_LENGTH,
                        requireProperty(user.getSecUsrType(), SEC_USR_TYPE))
                // Named trailing filler; space is the module default.
                .putSpaceFiller(SEC_USR_FILLER_OFFSET, SEC_USR_FILLER_LENGTH)
                .build();
    }

    /**
     * Builds the entity from a validated image, hashing the credential exactly once on the way.
     *
     * <p>Construction goes through the entity's all-argument constructor, the only accessible route,
     * which refuses any credential argument that is not structurally a digest. That refusal is why a
     * mis-supplied identity function cannot store a cleartext value: construction fails instead, and
     * fails without echoing what it rejected. The credential slice is produced and consumed within
     * one expression, so nothing in this class ever refers to the cleartext.
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
     * <p>There is no default, no fallback and no unhashed path, so a {@code null} return leaves the
     * mapper unable to complete a record rather than able to complete a degraded one. The message
     * names the broken contract and never a value, in either direction.
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
     * <p>Enforced here rather than inherited from the reader, so the width this layout requires is a
     * property of this mapper and fails visibly if the layout is ever edited. A Java-only defensive
     * guard with no legacy antecedent: the legacy records are fixed length by construction, so the
     * programs never had a wrong-length record to handle.
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
     * <p>The builder would reject it too, but with a message that cannot say which field was
     * missing, and on a record this small that difference is the whole diagnostic. The message names
     * the field and the layout, never the value.
     */
    private static String requireProperty(String value, String fieldName) {
        return Objects.requireNonNull(value, () -> DIAGNOSTIC_SUBJECT + " field '" + fieldName
                + "' must not be null when a record image is assembled: a fixed-width field is"
                + " emitted at its declared width, and an absent value has no width, so the record"
                + " could only be completed by inventing one");
    }
}
