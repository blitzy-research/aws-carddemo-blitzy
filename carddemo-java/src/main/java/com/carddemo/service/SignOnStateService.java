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
package com.carddemo.service;

import com.carddemo.domain.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.util.FailureDiagnostics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fingerprints the authoritative security facts of a sign-on identity, so that a session already
 * issued can be checked against the record as it stands now rather than trusted for its whole life.
 *
 * <h2>What this exists to fix</h2>
 *
 * <p>The legacy system had nothing to revoke. Every terminal turn re-entered a transaction that read
 * the credential master again, so a record changed between two turns was simply read again on the
 * next one; there was no carried entitlement to go stale. Replacing that carriage with a signed
 * bearer token moves the entitlement off the server and into the caller's hands, and a signed claim
 * stays true to its signature long after it has stopped being true about the record it describes.
 * Without something in this file, an administrator who demoted an operator, deleted an operator, or
 * reset an operator's credential would have changed nothing until the token they already held
 * expired.
 *
 * <h2>What it fingerprints, and why exactly these three facts</h2>
 *
 * <p>The fingerprint covers the three fields of the user-security record that decide what a session
 * is entitled to do and whether it should exist at all:
 *
 * <ul>
 *   <li>the identifier, {@code SEC-USR-ID} {@code PIC X(08)} of the eighty-byte record laid out at
 *       {@code app/cpy/CSUSR01Y.cpy} L17-L23 - so a fingerprint cannot be replayed against a
 *       different identity;</li>
 *   <li>the raw one-character type code, {@code SEC-USR-TYPE} {@code PIC X(01)}, which is the only
 *       field the estate's sign-on program branches on ({@code app/cbl/COSGN00C.cbl} L230) and is
 *       therefore the whole of the entitlement - so a demotion or a promotion changes the
 *       fingerprint;</li>
 *   <li>the stored credential, {@code SEC-USR-PWD}, held as the adaptive digest
 *       {@link CredentialDigestService} produces - so setting a credential changes the fingerprint,
 *       because each digest carries a fresh random salt and no two digests of one value are equal.</li>
 * </ul>
 *
 * <p>The remaining two fields, the given and family names, are deliberately absent. They are not
 * security facts, and covering them would end an operator's session because somebody corrected the
 * spelling of their surname.
 *
 * <p>Deletion needs no fingerprint at all: there is no record to fingerprint, so
 * {@link #currentStateOf(String)} answers empty and {@link #stillNames(String, String, String)}
 * answers {@code false}.
 *
 * <p><strong>Nothing is versioned, incremented or stored for this.</strong> There is no revocation
 * list, no session table, no counter column and no schema change: the fingerprint is derived from the
 * record every time it is needed. That is what makes it impossible for a future write path to forget
 * to bump something - a write that changes any of the three covered fields changes the fingerprint by
 * construction, and a write that changes none of them leaves sessions alone, which is correct.
 *
 * <h2>Why the fingerprint discloses nothing</h2>
 *
 * <p>The fingerprint travels in a token, and a token is readable by whoever holds it, so the
 * question is what a holder learns. The answer is nothing usable. It is a {@value #DIGEST_ALGORITHM}
 * digest over a canonical image that includes the stored credential digest, and that digest embeds a
 * random salt, so inverting the fingerprint to recover it is not feasible and neither is testing a
 * guessed credential against it - testing a guess would require the salt, which is inside the value
 * being guessed at. The fingerprint is emphatically <strong>not</strong> the stored digest and is
 * never a substitute for it: nothing here can verify a credential, and
 * {@link CredentialDigestService#matches(CharSequence, String)} remains the only thing that can.
 *
 * <p>One property is disclosed and is recorded rather than glossed: the fingerprint is stable while
 * the three covered fields are unchanged, so a party holding two tokens issued to one identity at two
 * times can tell whether any of the three changed in between. That party already holds two of that
 * identity's credentials, so the observation grants nothing further, and the alternative - a
 * per-issue random value - could not be recomputed from the record and so could not do this job at
 * all.
 *
 * <h2>Canonical form</h2>
 *
 * <p>The image is length-prefixed field by field and separated by the ASCII unit separator, following
 * the same construction as the module's two concurrency-token services, so that no combination of
 * field values can produce the image of a different combination - without it, an identifier ending in
 * a separator could impersonate a different split of the same characters. The image opens with
 * {@value #FINGERPRINT_SCHEME}, a version marker: changing what is covered or how it is composed
 * means changing that marker, which invalidates every session issued under the previous composition
 * deliberately and at once, rather than leaving two incompatible readings of one claim in flight.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This class reads and never writes. It holds no key, no secret and no configuration; it is
 * stateless and safe for concurrent use. Nothing here logs an identifier's credential, its digest, a
 * fingerprint or any fragment of any of them, and the one failure it reports carries only the failure
 * chain {@link FailureDiagnostics} composes rather than a caught throwable.
 *
 * <p>Provenance: repository SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@Service
public class SignOnStateService {

    /**
     * Version marker opening every canonical image, and the whole of the migration path for this
     * mechanism.
     *
     * <p>A change to which fields are covered, to their order, or to the separator must come with a
     * change to this marker. Every fingerprint then differs from every fingerprint minted before, so
     * sessions issued under the old composition stop being established rather than being checked
     * against a rule they were never minted under.
     */
    public static final String FINGERPRINT_SCHEME = "carddemo-signon-state-v1";

    /** Digest algorithm the fingerprint is produced with. */
    public static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * Character length of a fingerprint: a {@value #DIGEST_ALGORITHM} digest rendered as lower-case
     * hexadecimal, two characters per byte.
     *
     * <p>Published so that a caller carrying the value across a boundary, and a test asserting its
     * shape, read one number rather than counting characters.
     */
    public static final int FINGERPRINT_LENGTH = 64;

    /** Separator between fields of the canonical image. */
    private static final char UNIT_SEPARATOR = '\u001f';

    /**
     * Length prefix marking a field the record did not hold, distinguishing it from a field present
     * and empty. The user-security columns are all declared not-null, so this is defensive rather
     * than expected - but a canonical form that conflated the two states would report a change as no
     * change, which is the direction that must not fail silently.
     */
    private static final int ABSENT_FIELD = -1;

    /**
     * Diagnostic channel. It records that a currency check could not reach the record, and nothing
     * else: never an identifier's credential, never a digest, never a fingerprint.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SignOnStateService.class);

    /** Reads the authoritative user-security record. This class never writes one. */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service.
     *
     * @param userSecurityRepository reader for the authoritative user-security record; must not be
     *                               {@code null}
     * @throws NullPointerException if the repository is {@code null}
     */
    public SignOnStateService(final UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository must not be null");
    }

    /**
     * Reads the authoritative security state of an identity as it stands now.
     *
     * <p>Used where a session is about to be issued: the caller places the fingerprint in the session
     * so that a later request can be checked against the record again.
     *
     * <p>Failures are <strong>not</strong> swallowed here, unlike in
     * {@link #stillNames(String, String, String)}. A record that cannot be read at the moment a
     * session is being issued must not produce a session, and the caller is inside a request that is
     * about to answer, so the failure belongs to that answer rather than being converted into a
     * session nobody can use.
     *
     * @param userId the identifier the record is keyed by, at the fixed width of that key and already
     *               folded exactly as the sign-on path folds it; must not be {@code null}
     * @return the state, or empty when no record carries that identifier
     * @throws NullPointerException if {@code userId} is {@code null}
     */
    public Optional<SignOnState> currentStateOf(final String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return this.userSecurityRepository.findById(userId).map(record -> stateOf(userId, record));
    }

    /**
     * Reports whether a session's claims still name the record as it stands now.
     *
     * <p>Three things must hold, and all three are checked against the record rather than against the
     * session:
     *
     * <ol>
     *   <li>the record exists - a deleted identity establishes nothing, immediately;</li>
     *   <li>the fingerprint recomputed from the record equals the one presented - so a credential set
     *       or a type changed since the session was issued establishes nothing, immediately;</li>
     *   <li>the type code presented alongside it equals the record's type code - so a session naming
     *       an entitlement the record does not carry establishes nothing even though its fingerprint
     *       would otherwise reconcile. The fingerprint covers the record's own type code, which
     *       proves what the record said, not what the session claimed; this third check is what ties
     *       the two together.</li>
     * </ol>
     *
     * <p><strong>A failure to reach the record answers {@code false}.</strong> The alternative -
     * letting the failure escape - would leave an already-issued session established while the one
     * mechanism that can revoke it is not working, which is the direction that must not fail open.
     * The refusal is logged as a failure chain so the outage is diagnosable rather than silent, and
     * it is not distinguishable by the caller from any other reason a session was not established,
     * because a caller handed that distinction could relay it to whoever presented the session.
     *
     * <p>Comparison of the two fingerprints is constant-time, so nothing about how far it got is
     * observable. That is hygiene rather than a defence against a live attack - a fingerprint cannot
     * be presented without a validly signed session carrying it - and it costs nothing.
     *
     * @param userId              the identifier the session names; a {@code null} value answers
     *                            {@code false} rather than raising, because an unusable session is an
     *                            ordinary condition on this path and not an error
     * @param presentedTypeCode   the raw one-character type code the session names; {@code null}
     *                            answers {@code false}
     * @param presentedFingerprint the fingerprint the session carries; {@code null} or blank answers
     *                            {@code false}, which is also how a session issued before this
     *                            mechanism existed is refused
     * @return {@code true} only when the record exists and both the fingerprint and the type code
     *         still describe it
     */
    public boolean stillNames(final String userId, final String presentedTypeCode,
            final String presentedFingerprint) {
        if (userId == null || presentedTypeCode == null
                || presentedFingerprint == null || presentedFingerprint.isBlank()) {
            return false;
        }
        final Optional<SignOnState> current;
        try {
            current = currentStateOf(userId);
        } catch (final RuntimeException unreachable) {
            // The failure CHAIN is recorded and the throwable is not, so no third-party message and no
            // stack frame reaches an appender. Failing closed is deliberate: see this method's contract.
            LOG.warn("Session currency could not be established because the credential master was "
                    + "unreachable; refusing the session. failureChain={}",
                    FailureDiagnostics.failureChainOf(unreachable));
            return false;
        }
        if (current.isEmpty()) {
            return false;
        }
        final SignOnState state = current.orElseThrow();
        return presentedTypeCode.equals(state.userTypeCode())
                && fingerprintsMatch(presentedFingerprint, state.fingerprint());
    }

    /**
     * Builds the state of one record.
     *
     * @param userId the identifier the record was read by, used in the image rather than the record's
     *               own copy of it so that a fingerprint is always over the key that was asked for
     * @param record the record as read
     * @return its state
     */
    private static SignOnState stateOf(final String userId, final UserSecurity record) {
        final String typeCode = record.getSecUsrType();
        return new SignOnState(typeCode,
                fingerprintOf(userId, typeCode, record.credentialDigest()));
    }

    /**
     * Fingerprints the three covered facts.
     *
     * @param userId           the identifier
     * @param userTypeCode     the raw one-character type code, or {@code null} when the record holds
     *                         none
     * @param credentialDigest the stored credential digest, or {@code null} when the record holds none
     * @return the fingerprint as lower-case hexadecimal, {@value #FINGERPRINT_LENGTH} characters
     */
    private static String fingerprintOf(final String userId, final String userTypeCode,
            final String credentialDigest) {
        final StringBuilder image = new StringBuilder(FINGERPRINT_SCHEME).append(UNIT_SEPARATOR);
        appendField(image, userId);
        appendField(image, userTypeCode);
        appendField(image, credentialDigest);
        return digestOf(image.toString());
    }

    /**
     * Appends one field to the canonical image, length-prefixed so that no combination of field
     * values can produce the same image as a different combination.
     *
     * @param image the image being built
     * @param value the field value, or {@code null} when the record holds none
     */
    private static void appendField(final StringBuilder image, final String value) {
        image.append(value == null ? ABSENT_FIELD : value.length()).append(UNIT_SEPARATOR);
        if (value != null) {
            image.append(value);
        }
        image.append(UNIT_SEPARATOR);
    }

    /**
     * Digests a canonical image.
     *
     * @param canonicalImage the image to digest
     * @return the digest as lower-case hexadecimal
     * @throws IllegalStateException if the runtime does not provide the digest algorithm, which is a
     *                               broken installation rather than a handleable condition
     */
    private static String digestOf(final String canonicalImage) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HexFormat.of()
                    .formatHex(digest.digest(canonicalImage.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    DIGEST_ALGORITHM + " is required and is not available in this runtime",
                    unavailable);
        }
    }

    /**
     * Compares two fingerprints without leaking how far the comparison got.
     *
     * @param presented  the fingerprint the session carries
     * @param recomputed the fingerprint of the record as it stands now
     * @return {@code true} when the two are identical
     */
    private static boolean fingerprintsMatch(final String presented, final String recomputed) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                recomputed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The authoritative security state of one identity: what it is entitled to, and a fingerprint of
     * the facts that decide it.
     *
     * <p>Both components are needed by a session issuer. The fingerprint is what travels in the
     * session; the type code is what a later check compares the session's own claim against, so that
     * a session cannot name an entitlement the record does not carry.
     *
     * @param userTypeCode the raw one-character type code exactly as the record stores it, with no
     *                     folding, padding or permitted-value screening - the column has none and the
     *                     estate applied none
     * @param fingerprint  the fingerprint of the covered facts, lower-case hexadecimal of
     *                     {@value #FINGERPRINT_LENGTH} characters
     */
    public record SignOnState(String userTypeCode, String fingerprint) {
    }
}
