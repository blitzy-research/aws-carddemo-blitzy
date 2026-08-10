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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Mints and verifies the self-dating token that carries the identity of one logical report submission.
 *
 * <h2>Why the token has to carry its own age</h2>
 *
 * <p>The report screen publishes its request as a stream of fixed-width cards onto a FIFO queue, and the
 * only thing that stops a retry from doubling that stream is the queue service's own deduplication of
 * repeated deduplication identifiers. That deduplication has a fixed horizon: the broker remembers an
 * identifier for {@value #VALIDITY_MINUTES} minutes and then forgets it. The identifiers are a pure
 * function of the submission identity, and the identity is a pure function of the dates, the authenticated
 * operator and this token - so a retry presented an hour later reissues exactly the same identifiers, the
 * broker no longer recognises any of them, and the whole stream, or the prefix that had already landed, is
 * published a second time.
 *
 * <p>An opaque token a caller chose for itself cannot be aged, because nothing in it says when the
 * submission it names began. So the token is minted here, and it carries the instant it was minted. The
 * boundary that reads it can then answer the only question that matters - is the broker still able to keep
 * the promise this token represents - and refuse rather than publish a duplicate the caller was told was a
 * retry.
 *
 * <h2>Why the age is authenticated rather than merely present</h2>
 *
 * <p>A timestamp a caller can edit is a bound the caller can lift, and a bound the bounded party controls
 * is documentation rather than enforcement. So the instant is covered by a message authentication code
 * over a key the deployment holds and no caller has: a token whose instant has been moved, whose nonce has
 * been changed, or that was never minted here at all, fails verification and is refused. The tag is
 * {@value #TAG_LENGTH_BYTES} bytes, which is 128 bits of authentication over a value whose useful life is
 * measured in minutes.
 *
 * <p>Verification is a constant-time comparison. The tag is the only secret-derived material in the token
 * and a byte-at-a-time comparison over it would leak its bytes to a caller willing to submit repeatedly,
 * which is exactly the caller this check exists for.
 *
 * <h2>What the token does not carry, and why</h2>
 *
 * <p>Not the operator. The submission identity already folds the authenticated operator in, so one
 * caller's token cannot reach another caller's submission whatever the token says; binding the operator
 * into the tag as well would add nothing to that protection and would make the token unusable by the
 * boundary tests that have to prove the operator scoping from the outside. Not the dates, for the same
 * reason: they are already part of the identity. Not any caller-supplied text at all - the whole token is
 * minted material, so nothing a caller wrote can be reflected back out of it into a log or a queue.
 *
 * <h2>Shape</h2>
 *
 * <p>{@code crt1.<issued>.<nonce>.<tag>}: a version marker, the mint instant as eight big-endian bytes of
 * epoch second, eight bytes of random, and the truncated tag over the three fields that precede it, each
 * field encoded with the URL-safe unpadded alphabet so the whole token is safe in an HTTP header. The
 * version marker opens the token so that a second scheme can be introduced later and told apart from this
 * one by inspection rather than by guessing at lengths. Callers are told the token is opaque; the shape is
 * documented here because the deployment has to be able to read its own tokens in a diagnostic, not
 * because anything outside this class may depend on it.
 *
 * <p>See {@code docs/decision-log.md} entry DL-310 for why the window is the broker's figure, why the instant
 * is authenticated rather than merely present, and why the key is derived from the deployment's signing
 * material under a purpose label rather than configured separately.
 *
 * @since 1.0.0
 */
public final class ReportRetryTokens {

    /**
     * How long a minted token remains acceptable as a retry of the submission it names.
     *
     * <p>Five minutes, because that is the horizon over which the queue service deduplicates a repeated
     * deduplication identifier. The figure is the broker's, not a preference: setting it higher would
     * promise a collapse the broker will not perform, and setting it lower would refuse a retry the broker
     * would still have collapsed.
     */
    public static final int VALIDITY_MINUTES = 5;

    /** The window as a duration, derived from the one figure above so the two cannot disagree. */
    public static final Duration VALIDITY = Duration.ofMinutes(VALIDITY_MINUTES);

    /** Opens every token of this scheme, so a later scheme is distinguishable by inspection. */
    public static final String SCHEME = "crt1";

    /** How many bytes of the authentication code the token carries. */
    public static final int TAG_LENGTH_BYTES = 16;

    /** Field separator. Outside the URL-safe alphabet, so it cannot occur inside a field. */
    private static final char FIELD_SEPARATOR = '.';

    /** How many fields a well-formed token has. */
    private static final int FIELD_COUNT = 4;

    /** Width of the encoded mint instant before encoding: one big-endian {@code long}. */
    private static final int ISSUED_LENGTH_BYTES = 8;

    /** Width of the random component. Long enough that two tokens minted in one second differ. */
    private static final int NONCE_LENGTH_BYTES = 8;

    /** The keyed hash used both to derive the token key and to authenticate a token. */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /**
     * Separates this key's purpose from every other use of the same signing material.
     *
     * <p>The token key is derived from the deployment's signing secret rather than configured
     * separately, so a deployment gains no new secret to distribute, rotate or leak. Deriving it under a
     * fixed purpose label is what keeps the two uses apart: the derived key cannot mint or verify a
     * credential, and the credential key cannot mint or verify a token, because neither can be obtained
     * from the other without the label and the hash.
     */
    private static final byte[] KEY_DERIVATION_LABEL =
            "carddemo/report-retry-token/v1".getBytes(StandardCharsets.US_ASCII);

    /** Length of a derived key, which is the hash's own output width. */
    private static final int DERIVED_KEY_LENGTH_BYTES = 32;

    /** URL-safe unpadded encoder, so every field is header-safe and separator-free. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Matching decoder. */
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** One shared strong source for nonces and for the fallback key; {@link SecureRandom} is thread-safe. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Not instantiable: every operation is a pure function of its arguments and the class holds no state
     * beyond its constants.
     */
    private ReportRetryTokens() {
        throw new AssertionError("ReportRetryTokens is a utility holder and is never instantiated");
    }

    /**
     * Derives the token key from the deployment's signing secret.
     *
     * <p>When no secret is configured the key is random and lives only as long as the process. That is the
     * correct degradation rather than a failure: a deployment that configured no signing material has no
     * deployment-wide authority for anything, so a token cannot meaningfully outlive the process that
     * minted it, and the alternative - a fixed key compiled in, or a refusal to start - would either
     * fabricate an authority that does not exist or take down a context that has no need of one. Production
     * requires the signing secret with no fallback and a length floor, so the random branch is not
     * reachable there.
     *
     * @param  signingSecret the deployment's signing material, which may be {@code null} or blank
     * @return exactly {@value #DERIVED_KEY_LENGTH_BYTES} bytes of key material
     */
    public static byte[] deriveKey(final String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            final byte[] ephemeral = new byte[DERIVED_KEY_LENGTH_BYTES];
            RANDOM.nextBytes(ephemeral);
            return ephemeral;
        }
        return keyedHash(signingSecret.getBytes(StandardCharsets.UTF_8), KEY_DERIVATION_LABEL);
    }

    /**
     * Mints a token stamped with the instant the submission it names began.
     *
     * <p>The instant is truncated to the second, which is the resolution the window is expressed in; the
     * nonce is what keeps two submissions minted inside the same second from sharing a token, and
     * therefore from sharing a submission identity.
     *
     * @param  issuedAt when the submission began; truncated to the second
     * @param  key      the derived token key
     * @return a token this class will later recognise under the same key
     */
    public static String mint(final Instant issuedAt, final byte[] key) {
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        requireKey(key);
        final byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        RANDOM.nextBytes(nonce);
        final String issuedField = ENCODER.encodeToString(bigEndian(issuedAt.getEpochSecond()));
        final String nonceField = ENCODER.encodeToString(nonce);
        final String covered = SCHEME + FIELD_SEPARATOR + issuedField + FIELD_SEPARATOR + nonceField;
        return covered + FIELD_SEPARATOR + ENCODER.encodeToString(tagOf(covered, key));
    }

    /**
     * Reads the mint instant out of a token this key authenticates.
     *
     * <p>Total and never throwing: every way a token can fail - a {@code null}, the wrong scheme, the wrong
     * field count, a field that is not URL-safe Base64, a field of the wrong decoded width, or a tag that
     * does not verify - is answered as an empty result. A caller cannot tell from the answer which of those
     * it was, which is deliberate: the distinction is worth recording in a deployment's own diagnostic and
     * is worth nothing to whoever presented the value.
     *
     * @param  token the presented token, which may be {@code null} or arbitrary caller text
     * @param  key   the derived token key
     * @return the instant the token was minted, or empty when this key did not mint it
     */
    public static Optional<Instant> issuedAt(final String token, final byte[] key) {
        requireKey(key);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        final int lastSeparator = token.lastIndexOf(FIELD_SEPARATOR);
        if (lastSeparator < 0) {
            return Optional.empty();
        }
        final String covered = token.substring(0, lastSeparator);
        final String[] fields = token.split("\\" + FIELD_SEPARATOR, -1);
        if (fields.length != FIELD_COUNT || !SCHEME.equals(fields[0])) {
            return Optional.empty();
        }
        final byte[] issued = decodeOrEmpty(fields[1], ISSUED_LENGTH_BYTES);
        final byte[] nonce = decodeOrEmpty(fields[2], NONCE_LENGTH_BYTES);
        final byte[] presentedTag = decodeOrEmpty(fields[3], TAG_LENGTH_BYTES);
        if (issued.length == 0 || nonce.length == 0 || presentedTag.length == 0) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(tagOf(covered, key), presentedTag)) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(epochSecondOf(issued)));
    }

    /**
     * Reports whether a token minted at one instant is still acceptable at another.
     *
     * <p>Both ends are closed. A token from the future is refused as firmly as an expired one: a mint
     * instant later than now cannot have come from a submission that already happened, so it is either a
     * clock that moved backwards or a value that was not produced by this deployment's clock, and treating
     * it as fresh would extend the window by exactly the amount it was moved.
     *
     * @param  issuedAt when the token was minted
     * @param  now      the instant to judge it at
     * @return {@code true} only when the mint instant lies within the window ending at {@code now}
     */
    public static boolean isWithinValidity(final Instant issuedAt, final Instant now) {
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return !issuedAt.isAfter(now) && !issuedAt.isBefore(now.minus(VALIDITY));
    }

    /**
     * Computes the authentication tag over a token's covered prefix.
     *
     * @param  covered the scheme, instant and nonce fields with their separators
     * @param  key     the derived token key
     * @return the first {@value #TAG_LENGTH_BYTES} bytes of the keyed hash
     */
    private static byte[] tagOf(final String covered, final byte[] key) {
        final byte[] full = keyedHash(key, covered.getBytes(StandardCharsets.US_ASCII));
        final byte[] truncated = new byte[TAG_LENGTH_BYTES];
        System.arraycopy(full, 0, truncated, 0, TAG_LENGTH_BYTES);
        return truncated;
    }

    /**
     * Applies the keyed hash.
     *
     * @param  key     the key material
     * @param  message the bytes to authenticate
     * @return the full hash output
     */
    private static byte[] keyedHash(final byte[] key, final byte[] message) {
        try {
            // Fully qualified deliberately. The keyed-hash types live in the platform's javax.crypto
            // namespace and have no counterpart elsewhere in Java SE, while this module forbids
            // javax.* IMPORTS because every Jakarta EE annotation must come from jakarta.* under
            // Spring Boot 3 and a blanket import ban is the only mechanically checkable form of that
            // rule. SensitiveFieldCodec states the same reasoning at length; both classes keep a
            // literal zero-javax-import posture rather than hand-rolling a substitute. DL-316.
            final javax.crypto.Mac mac = javax.crypto.Mac.getInstance(MAC_ALGORITHM);
            mac.init(new javax.crypto.spec.SecretKeySpec(key, MAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (final GeneralSecurityException unavailable) {
            throw new IllegalStateException(
                    MAC_ALGORITHM + " must be available in every Java runtime", unavailable);
        }
    }

    /**
     * Decodes one field, answering an empty array for anything that is not the expected width.
     *
     * @param  field    the encoded field
     * @param  expected the decoded width the field must have
     * @return the decoded bytes, or a zero-length array when the field is unusable
     */
    private static byte[] decodeOrEmpty(final String field, final int expected) {
        final byte[] decoded;
        try {
            decoded = DECODER.decode(field);
        } catch (final IllegalArgumentException notBase64) {
            return new byte[0];
        }
        return decoded.length == expected ? decoded : new byte[0];
    }

    /**
     * Renders a {@code long} as eight big-endian bytes.
     *
     * @param  value the value to render
     * @return exactly {@value #ISSUED_LENGTH_BYTES} bytes
     */
    private static byte[] bigEndian(final long value) {
        final byte[] rendered = new byte[ISSUED_LENGTH_BYTES];
        for (int index = 0; index < ISSUED_LENGTH_BYTES; index++) {
            rendered[index] = (byte) (value >>> (Byte.SIZE * (ISSUED_LENGTH_BYTES - 1 - index)));
        }
        return rendered;
    }

    /**
     * Reads eight big-endian bytes back as a {@code long}.
     *
     * @param  rendered exactly {@value #ISSUED_LENGTH_BYTES} bytes
     * @return the value they carry
     */
    private static long epochSecondOf(final byte[] rendered) {
        long value = 0L;
        for (final byte octet : rendered) {
            value = (value << Byte.SIZE) | (octet & 0xFFL);
        }
        return value;
    }

    /**
     * Refuses a key that could not authenticate anything.
     *
     * @param key the key to check
     */
    private static void requireKey(final byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != DERIVED_KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("the report retry token key must be exactly "
                    + DERIVED_KEY_LENGTH_BYTES + " bytes, as produced by deriveKey");
        }
    }
}
