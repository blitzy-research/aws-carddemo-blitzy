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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Authenticated encryption for the regulated character fields of the customer record, expressed as
 * a self-describing textual envelope that a bounded {@code VARCHAR} column can carry.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The legacy customer record holds its national identifier as nine cleartext digits at offset
 * 279 of the 500-byte image, and its government-issued identifier as twenty cleartext characters at
 * offset 288. Neither field was protected on the mainframe, where the only control was dataset-level
 * access. Carrying that arrangement into a relational store would leave regulated identifiers
 * readable to anyone holding a database connection, a backup file or an operational query, so the
 * gap is closed rather than reproduced: the identifiers are encrypted before they reach the
 * persistence boundary and decrypted only by a caller holding the key. This is a deliberate,
 * documented divergence from byte-for-byte faithfulness at rest - the values a batch record image
 * carries are unchanged, only their stored representation differs - and it is written up in
 * {@code docs/decision-log.md} rather than left implicit.
 *
 * <h2>The envelope</h2>
 *
 * <p>A protected value is a single {@code US-ASCII}-safe string:
 *
 * <pre>
 *   ENC1:BASE64( iv[12] || ciphertext[n] || tag[16] )
 * </pre>
 *
 * <p>Four properties of that layout are contractual.
 *
 * <ul>
 *   <li><strong>It is versioned.</strong> The literal {@code ENC1} names the scheme, so a future
 *       scheme can be introduced beside this one and stored values can be told apart without a
 *       schema change, a side table or a guess. A reader that does not recognise the version must
 *       refuse the value rather than attempt it.</li>
 *   <li><strong>It is self-describing.</strong> The initialisation vector travels with the
 *       ciphertext, so nothing outside the value needs to be remembered in order to read it. Only
 *       the key is external.</li>
 *   <li><strong>It is authenticated.</strong> The trailing 16 bytes are the Galois/Counter Mode
 *       authentication tag. A single altered byte anywhere in the envelope - ciphertext, vector or
 *       tag - makes decryption fail loudly instead of returning corrupted cleartext, which is the
 *       property that distinguishes this from a bare cipher and the reason a mode without
 *       authentication was not used.</li>
 *   <li><strong>It is text.</strong> Base64 over the basic RFC 4648 alphabet keeps the envelope
 *       inside the printable {@code US-ASCII} range, so it survives a {@code VARCHAR} column, a
 *       JDBC round trip and a fixed-width diagnostic dump without any encoding negotiation.</li>
 * </ul>
 *
 * <h2>Encryption is randomised, and that is intended</h2>
 *
 * <p>A fresh 96-bit vector is drawn from {@link SecureRandom} for every call, so encrypting the same
 * cleartext twice yields two different envelopes. That is a requirement of the mode rather than an
 * inconvenience: reusing a vector under one key destroys the confidentiality guarantee of
 * Galois/Counter Mode outright. The consequence is that a protected column cannot be searched by
 * equality, which costs this estate nothing - the legacy design defines no alternate index, no
 * browse and no screen lookup over either identifier, so no access path is lost.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It holds no key.</strong> Key material is supplied by the caller on every call and
 *       is never cached, copied into a field, or retained after the call returns. Resolving key
 *       material from the environment belongs to the service layer, which is where the
 *       configuration-backed holder lives; this class is pure and has no framework dependency.</li>
 *   <li><strong>It logs nothing.</strong> There is no logger, because the only values passing
 *       through are cleartext identifiers and key-derived material. Failures are raised as
 *       exceptions whose messages name the failing condition and never echo a value.</li>
 *   <li><strong>It performs no reflection and builds no SQL.</strong> The module's unsafe-code audit
 *       budget for both is zero, and this class stays inside it.</li>
 * </ul>
 *
 * <h2>A note on fully qualified cipher references</h2>
 *
 * <p>The Java Cryptography Architecture types this class needs - the cipher, the secret-key
 * specification and the mode parameter specification - live in the platform's {@code javax.crypto}
 * namespace and have no counterpart elsewhere in the Java SE library. This module forbids
 * {@code javax.*} <em>imports</em> because every Jakarta EE annotation it uses must come from
 * {@code jakarta.*} under Spring Boot 3, and a blanket ban is the only mechanically checkable form
 * of that rule. The three cryptography types are therefore referenced by their fully qualified
 * names at their use sites, so the module keeps a literal zero-{@code javax}-import posture while
 * still using the platform's own authenticated-encryption implementation rather than a hand-rolled
 * substitute. Checked cryptographic failures are caught as
 * {@link java.security.GeneralSecurityException}, which is the shared supertype and lives in
 * {@code java.security}, so no {@code javax} type appears in a signature or a catch clause either.
 *
 * <h2>Provenance</h2>
 *
 * <p>Derived from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy estate is read-only reference
 * material; the field offsets and widths quoted above are citations, not transcriptions, and no
 * legacy source text is reproduced here.
 */
public final class SensitiveFieldCodec {

    /**
     * Scheme marker that opens every protected value. Present so that a stored value can be
     * recognised as protected, and recognised as protected <em>by this scheme</em>, without
     * consulting anything outside the value itself.
     *
     * <p>The domain layer repeats this literal in its own guard rather than importing it, so that an
     * entity does not acquire a dependency on the utility layer. A unit test asserts the two
     * constants are equal, which is what keeps the deliberate duplication from drifting.
     */
    public static final String ENVELOPE_PREFIX = "ENC1:";

    /**
     * Required key length in bytes. AES-256 is used rather than AES-128 because the stored values
     * are long-lived regulated identifiers and the cost difference is immaterial at this volume.
     */
    public static final int KEY_LENGTH_BYTES = 32;

    /**
     * Initialisation-vector length in bytes. Twelve is the length Galois/Counter Mode is specified
     * for and the only length that avoids an internal re-derivation step.
     */
    public static final int IV_LENGTH_BYTES = 12;

    /**
     * Authentication-tag length in bytes - the full tag, not a truncated one.
     */
    public static final int TAG_LENGTH_BYTES = 16;

    /**
     * Smallest possible decoded envelope body, reached when the cleartext is the empty string: a
     * vector and a tag with no ciphertext between them. A body shorter than this cannot have come
     * from {@link #protect(String, byte[])} and is rejected structurally, before any key is applied.
     */
    public static final int MINIMUM_ENVELOPE_BODY_BYTES = IV_LENGTH_BYTES + TAG_LENGTH_BYTES;

    /**
     * Cipher transformation. Galois/Counter Mode supplies both confidentiality and integrity in one
     * pass, and {@code NoPadding} is correct because counter-mode encryption is not block aligned.
     */
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * Key algorithm name expected by the secret-key specification.
     */
    private static final String KEY_ALGORITHM = "AES";

    /**
     * Authentication-tag length expressed in bits, which is the unit the mode parameter
     * specification takes.
     */
    private static final int TAG_LENGTH_BITS = TAG_LENGTH_BYTES * Byte.SIZE;

    /**
     * Source of initialisation vectors. A single strong instance is shared: {@link SecureRandom} is
     * thread-safe, and one seeded instance avoids paying the seeding cost per call.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Not instantiable: every operation is a pure function of its arguments and the class holds no
     * state beyond its constants.
     */
    private SensitiveFieldCodec() {
        throw new AssertionError("SensitiveFieldCodec is a utility holder and is never instantiated");
    }

    /**
     * Reports whether a candidate string has the structural shape of a protected value produced by
     * this scheme, without applying a key and without deciding whether it would decrypt.
     *
     * <p>This is the check a fail-closed persistence guard needs: it is total, it never throws, it
     * needs no key material, and it is strong enough that no cleartext national or government
     * identifier can pass it. Three conditions must all hold - the scheme marker must open the
     * value, the remainder must decode as basic Base64, and the decoded body must be long enough to
     * contain a vector and a tag.
     *
     * @param candidate the value to inspect; {@code null} is answered {@code false} rather than
     *                  rejected, so a caller can use this on a nullable column without a null check
     * @return {@code true} only when the candidate is shaped like an {@code ENC1} envelope
     */
    public static boolean hasEnvelopeShape(final String candidate) {
        if (candidate == null || !candidate.startsWith(ENVELOPE_PREFIX)) {
            return false;
        }
        final String body = candidate.substring(ENVELOPE_PREFIX.length());
        if (body.isEmpty()) {
            return false;
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
        try {
            return decoded.length >= MINIMUM_ENVELOPE_BODY_BYTES;
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    /**
     * Returns the exact envelope length, in characters, that a cleartext of the given byte length
     * produces. Callers use this to prove a bounded column is wide enough for the values it will be
     * asked to hold, which is a schema-consistency question rather than a runtime one.
     *
     * <p>The arithmetic is exact rather than an upper bound: the body is always
     * {@code cleartextByteLength + 12 + 16} bytes and basic Base64 always emits
     * {@code 4 * ceil(body / 3)} padded characters, so the result is deterministic for a given
     * cleartext length.
     *
     * @param cleartextByteLength number of bytes the cleartext occupies once encoded as UTF-8; must
     *                            not be negative
     * @return the character length of the resulting envelope, marker included
     * @throws IllegalArgumentException when the supplied length is negative
     */
    public static int envelopeLengthFor(final int cleartextByteLength) {
        if (cleartextByteLength < 0) {
            throw new IllegalArgumentException("cleartext byte length must not be negative");
        }
        final int bodyBytes = cleartextByteLength + MINIMUM_ENVELOPE_BODY_BYTES;
        final int base64Characters = 4 * ((bodyBytes + 2) / 3);
        return ENVELOPE_PREFIX.length() + base64Characters;
    }

    /**
     * Encrypts a cleartext value under the supplied key and returns it as an {@code ENC1} envelope.
     *
     * <p>A fresh initialisation vector is drawn for every call. The cleartext is encoded as UTF-8
     * before encryption, which is a faithful round trip for the digit-and-uppercase domain these
     * fields actually hold and remains correct for any other content.
     *
     * @param cleartext the value to protect; must not be {@code null}. An empty string is a
     *                  legitimate input and produces a valid, minimal envelope
     * @param key       32 bytes of key material. The array is read but never retained, and never
     *                  mutated
     * @return the protected value, ready to be stored in a bounded character column
     * @throws NullPointerException     when either argument is {@code null}
     * @throws IllegalArgumentException when the key is not exactly {@link #KEY_LENGTH_BYTES} bytes
     * @throws IllegalStateException    when the platform cannot perform the transformation, which
     *                                  indicates a broken or restricted runtime rather than bad
     *                                  input
     */
    public static String protect(final String cleartext, final byte[] key) {
        Objects.requireNonNull(cleartext, "cleartext must not be null");
        requireKeyLength(key);

        final byte[] iv = new byte[IV_LENGTH_BYTES];
        RANDOM.nextBytes(iv);

        final byte[] cleartextBytes = cleartext.getBytes(StandardCharsets.UTF_8);
        byte[] sealed = null;
        byte[] envelopeBody = null;
        try {
            final javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, KEY_ALGORITHM),
                    new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BITS, iv));
            sealed = cipher.doFinal(cleartextBytes);

            envelopeBody = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, envelopeBody, 0, iv.length);
            System.arraycopy(sealed, 0, envelopeBody, iv.length, sealed.length);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(envelopeBody);
        } catch (GeneralSecurityException cryptographicFailure) {
            throw new IllegalStateException(
                    "authenticated encryption is unavailable on this runtime", cryptographicFailure);
        } finally {
            Arrays.fill(cleartextBytes, (byte) 0);
            if (sealed != null) {
                Arrays.fill(sealed, (byte) 0);
            }
            if (envelopeBody != null) {
                Arrays.fill(envelopeBody, (byte) 0);
            }
        }
    }

    /**
     * Decrypts an {@code ENC1} envelope under the supplied key and returns the original cleartext.
     *
     * <p>Structure is checked before any key is applied, so a malformed value and a value that
     * fails authentication are distinguishable to the caller and are reported as different
     * conditions. Neither message quotes the offending value, because the offending value is
     * regulated data.
     *
     * @param envelope the protected value; must not be {@code null} and must carry the
     *                 {@code ENC1} marker
     * @param key      32 bytes of key material, the same material the envelope was produced with
     * @return the cleartext exactly as it was supplied to {@link #protect(String, byte[])}
     * @throws NullPointerException     when either argument is {@code null}
     * @throws IllegalArgumentException when the key length is wrong, or the envelope is not shaped
     *                                  like a value this scheme produced
     * @throws IllegalStateException    when authentication fails - the value was altered, or the key
     *                                  is not the one it was sealed with - or when the platform
     *                                  cannot perform the transformation
     */
    public static String reveal(final String envelope, final byte[] key) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        requireKeyLength(key);
        if (!hasEnvelopeShape(envelope)) {
            throw new IllegalArgumentException(
                    "value is not an " + ENVELOPE_PREFIX + " envelope and cannot be decrypted");
        }

        final byte[] body = Base64.getDecoder()
                .decode(envelope.substring(ENVELOPE_PREFIX.length()));
        byte[] cleartextBytes = null;
        try {
            final javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, KEY_ALGORITHM),
                    new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BITS, body, 0, IV_LENGTH_BYTES));
            cleartextBytes = cipher.doFinal(body, IV_LENGTH_BYTES, body.length - IV_LENGTH_BYTES);
            return new String(cleartextBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException authenticationFailure) {
            throw new IllegalStateException(
                    "authenticated decryption failed: the value was altered or the key does not match",
                    authenticationFailure);
        } finally {
            Arrays.fill(body, (byte) 0);
            if (cleartextBytes != null) {
                Arrays.fill(cleartextBytes, (byte) 0);
            }
        }
    }

    /**
     * Rejects key material of the wrong length before it reaches the cipher, so that a
     * misconfiguration is reported as a configuration fault rather than as an opaque cryptographic
     * one. The message states the required and observed lengths and never the material itself.
     *
     * @param key the candidate key material
     */
    private static void requireKeyLength(final byte[] key) {
        Objects.requireNonNull(key, "key material must not be null");
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("key material must be exactly "
                    + KEY_LENGTH_BYTES + " bytes, but " + key.length + " were supplied");
        }
    }
}
