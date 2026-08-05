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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * The single authority in the module for producing, verifying and recognising the stored form of a
 * sign-on credential.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>The legacy user-security record holds the credential in the clear: {@code SEC-USR-PWD} is an
 * eight-character alphanumeric field at offset 48 of the 80-byte record described by
 * {@code app/cpy/CSUSR01Y.cpy}, {@code app/jcl/DUSRSECJ.jcl} carries ten such records in-stream as
 * ASCII card images, and {@code app/cbl/COSGN00C.cbl} line 223 authenticates by comparing the
 * stored field directly against the value keyed at the terminal. Reproducing that storage decision
 * would satisfy byte-for-byte parity and would breach the migration's binding
 * no-hardcoded-credentials requirement at the same time, which is why the migrated column is sized
 * for a sixty-character BCrypt digest instead of the legacy width of eight. That substitution is the
 * module's flagship documented security parity exception and is written up in
 * {@code docs/decision-log.md}.
 *
 * <p>Declaring a wider column and documenting the intent does not by itself make the intent true.
 * This class is the mechanism that makes it true: it is the only place that turns a credential into
 * something the {@code user_security.sec_usr_pwd} column may legitimately hold, the only place that
 * verifies a supplied credential against a stored value, and the only place that can decide whether
 * a given string is a digest at all. Nothing about the 80-byte record layout changes, because the
 * fixed-width offsets live in the record mapper and are untouched by what this class produces.
 *
 * <h2>Why the entity does not do this itself</h2>
 *
 * <p>{@link com.carddemo.domain.UserSecurity} is a carrier: it declares the column, and it stores
 * and returns exactly what a caller hands it. It performs no hashing, no verification and no
 * comparison, it declares no attribute converter and no shape constraint, and it imports nothing
 * from this package, because the dependency direction is one-way - {@code service} and {@code util}
 * depend on {@code domain}, never the reverse. Its constructor and its setter are plain assignments,
 * so being outside {@code domain} is what keeps credential <em>logic</em> out of the entity; it is
 * not on its own what keeps a cleartext <em>value</em> out of the column. The enforcement point is
 * therefore the persistence boundary rather than the entity, and
 * {@link #requireDigest(String, String)} exists so that boundary has exactly one call to make and
 * exactly one thing to assert.
 *
 * <p>Defence in depth backs that boundary up. {@link #matches(CharSequence, String)} refuses a
 * stored value that is not digest-shaped before it consults the encoder at all, so a cleartext value
 * written by any path that bypasses the guard can never authenticate anyone - not even the person
 * whose credential it is.
 *
 * <h2>The stored form</h2>
 *
 * <p>A stored value is exactly {@value #DIGEST_LENGTH} characters: a four-character version tag of
 * {@code $2a$}, {@code $2b$} or {@code $2y$}, a two-digit cost factor between {@code 04} and
 * {@code 31} inclusive, a {@code $} separator, and fifty-three characters drawn from the BCrypt
 * radix-64 alphabet {@code ./0-9A-Za-z}. The alphabet deliberately excludes {@code +} and
 * {@code =}, which is what distinguishes a BCrypt digest from a standard base-64 encoding of similar
 * length. Each digest embeds its own random salt, so encoding the same credential twice yields two
 * different digests and no equality test between digests is ever meaningful.
 *
 * <p>The cost factor is <strong>{@value #HASHING_STRENGTH}</strong>, published by this class as
 * {@link #HASHING_STRENGTH} and deliberately above the encoder library's own default, because the
 * value it protects is a credential. It is not exposed as a configuration knob: the legacy system has
 * no antecedent for a work factor and the migration establishes no performance baseline to tune one
 * against, so a setting whose only possible justification would be a figure this project does not
 * have would be unwarranted. It is a resistance parameter - each increment doubles the work of both a
 * legitimate verification and an attacker's guess - and asserts no latency, throughput or service
 * level of any kind.
 *
 * <h2>Not to be confused with field encryption</h2>
 *
 * <p>{@link SensitiveFieldEncryptionService} is a different mechanism for a different column. It
 * performs reversible authenticated encryption so that {@code customer.cust_ssn} can be read back as
 * the value that was stored. A credential is never read back: this class produces a one-way digest
 * and verifies against it, and no method here can recover the credential that produced a digest.
 * Reach for that class when a value must survive a round trip, and for this one when a value must
 * never be recoverable.
 *
 * <h2>Boundaries</h2>
 *
 * <p>No credential literal of any kind appears in this file, and no default, fallback or placeholder
 * credential exists: {@link #encode(CharSequence)} refuses a null or blank input rather than
 * substituting anything for it, matching the legacy sign-on's own refusal to compare an unkeyed
 * password. Nothing here writes a credential, a digest or any fragment of either to a log.
 *
 * <p>This class holds one stateless encoder and is safe for concurrent use by any number of callers.
 *
 * <h2>One hashing policy, one constant, and where the other encoder comes from</h2>
 *
 * <p>The module publishes a second {@code PasswordEncoder} as a bean from its security
 * configuration, because the administrative user-maintenance path writes digests through the
 * framework's own abstraction rather than through this class. Two encoder <em>instances</em> are
 * therefore live at once, and that is only safe if they cannot disagree about strength - a digest
 * written at one cost and expected at another is not a verification failure, it is a silent policy
 * split, and the weaker of the two becomes the module's real strength.
 *
 * <p>They cannot disagree, because there is exactly one place the strength is written down:
 * {@link #HASHING_STRENGTH}, declared here. The configuration's encoder bean reads this constant
 * rather than restating a number, which is the permitted direction - configuration may depend on a
 * service and a service may never depend on configuration - so this class is the authority for the
 * policy even where it is not the instance doing the work. Changing the strength is a one-line change
 * here that both encoders follow.
 *
 * <p>Verification is unaffected by any past change of strength: a BCrypt digest carries its own cost
 * factor, so a value written at an earlier strength keeps verifying, and
 * {@link #isDigest(String)} accepts the whole declared range rather than only the current
 * strength.
 *
 * <p>Provenance: repository SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@Service
public final class CredentialDigestService {

    /** Logger for configuration reporting. No credential and no digest is ever logged. */
    private static final Logger LOG = LoggerFactory.getLogger(CredentialDigestService.class);

    /**
     * The qualified name of the one column in the migrated schema that holds a credential digest,
     * for use as the field argument of {@link #requireDigest(String, String)}. Naming the column
     * rather than passing a free-form string keeps every rejection message attributable.
     */
    public static final String USER_SECURITY_PWD_FIELD = "user_security.sec_usr_pwd";

    /**
     * The exact character length of a BCrypt digest, which is also the width of the
     * {@code sec_usr_pwd} column. A value of any other length is not a digest.
     */
    public static final int DIGEST_LENGTH = 60;

    /**
     * The module's single credential-hashing strength, and the only place it is written down.
     *
     * <p>Published so that every encoder in the module reads one number. This class builds its own
     * encoder at this strength, and the security configuration's {@code PasswordEncoder} bean is
     * built at this strength too by reading this constant - which is what makes "one hashing policy"
     * an enforced property rather than a coincidence of two literals that happen to agree today. The
     * dependency runs configuration-to-service, the direction the module's layering permits; the
     * reverse would not be allowed and is why the constant lives here rather than beside the bean.
     *
     * <p>The value is above the encoder library's own default because what it protects is a
     * credential. It is a resistance parameter and not a latency, throughput or capacity figure, and
     * it asserts no service level. Raising it later costs nothing to already-stored digests: each
     * digest carries the cost it was produced at, so old values keep verifying and are simply
     * rewritten at the current strength the next time the credential is set.
     */
    public static final int HASHING_STRENGTH = 12;

    /**
     * Lowest cost factor a digest may declare. Package-private so the test in this package asserts
     * the accepted range against the same constant the check uses.
     */
    static final int MINIMUM_COST = 4;

    /**
     * Highest cost factor a digest may declare. Package-private for the same reason as
     * {@link #MINIMUM_COST}.
     */
    static final int MAXIMUM_COST = 31;

    /**
     * Index at which the combined salt and hash begins: four characters of version tag, two of cost
     * factor and one separator.
     */
    private static final int SALT_AND_HASH_OFFSET = 7;

    /** The stateless encoder that produces and verifies digests. */
    private final BCryptPasswordEncoder encoder;

    /**
     * Creates the service at the module's single hashing strength.
     *
     * <p>The strength is {@link #HASHING_STRENGTH} rather than the encoder library's default, so that
     * a digest produced here and a digest produced by the security configuration's encoder bean - the
     * other live encoder in the module - are produced at the same cost. Leaving this at the library
     * default was the defect that made the module carry two hashing policies at once, the weaker of
     * which would have been its real strength.
     *
     * <p>The constructor takes no configuration and reads no property, because there is no secret and
     * no tunable involved: a BCrypt digest carries its own salt and its own cost factor inside the
     * stored value, so nothing external has to be supplied to verify one later.
     */
    public CredentialDigestService() {
        this.encoder = new BCryptPasswordEncoder(HASHING_STRENGTH);
        LOG.info("CREDENTIAL DIGESTS CONFIGURED FOR THE {}-CHARACTER BCRYPT FORM AT STRENGTH {} ON {}",
                DIGEST_LENGTH, HASHING_STRENGTH, USER_SECURITY_PWD_FIELD);
    }

    /**
     * Produces the stored form of a credential.
     *
     * <p>The returned digest embeds a freshly generated random salt, so two calls with the same
     * input return two different digests and neither may be compared with the other. Use
     * {@link #matches(CharSequence, String)} to verify, never an equality test.
     *
     * <p>A null or blank credential is refused rather than encoded. The legacy sign-on prompts for a
     * password instead of comparing an empty field, so a digest of nothing has no legitimate
     * meaning, and admitting one would create a stored value that an empty submission could satisfy.
     *
     * @param rawCredential the credential as supplied by the caller; never logged, never retained
     * @return a {@value #DIGEST_LENGTH}-character BCrypt digest
     * @throws IllegalArgumentException when the credential is null, empty or entirely whitespace
     */
    public String encode(final CharSequence rawCredential) {
        if (rawCredential == null) {
            throw new IllegalArgumentException(
                    "A credential is required to produce a digest for " + USER_SECURITY_PWD_FIELD);
        }
        if (isBlank(rawCredential)) {
            throw new IllegalArgumentException(
                    "A blank credential cannot be digested for " + USER_SECURITY_PWD_FIELD);
        }
        return encoder.encode(rawCredential);
    }

    /**
     * Verifies a supplied credential against a stored value.
     *
     * <p>This is the replacement for the legacy direct field comparison at
     * {@code app/cbl/COSGN00C.cbl} line 223: the verifier is an encoder rather than an equality
     * test, so the stored value never has to be, and never can be, turned back into a credential.
     *
     * <p>The stored value is checked for digest shape <em>before</em> the encoder is consulted. That
     * ordering is deliberate and is the module's defence in depth: if a cleartext value ever reached
     * the column through a path that bypassed {@link #requireDigest(String, String)}, it would
     * authenticate nobody, because a value that is not a digest can never match.
     *
     * @param rawCredential the credential to verify; a null value never matches
     * @param storedDigest  the value read from the column; anything that is not a digest never
     *                      matches
     * @return {@code true} only when the stored value is a digest and the credential produced it
     */
    public boolean matches(final CharSequence rawCredential, final String storedDigest) {
        if (rawCredential == null || !isDigest(storedDigest)) {
            return false;
        }
        return encoder.matches(rawCredential, storedDigest);
    }

    /**
     * Reports whether a value has the structure of a BCrypt digest.
     *
     * <p>The test is purely structural - length, version tag, cost-factor range and radix-64
     * alphabet - and never attempts a verification, so it cannot be used as an oracle for whether
     * any particular credential is the right one. It exists so that a caller, a persistence boundary
     * or a database-level assertion can distinguish a digest from a cleartext value without holding
     * a credential to try.
     *
     * @param value the value to inspect; a null value is not a digest
     * @return {@code true} when the value is shaped exactly as a BCrypt digest
     */
    public boolean isDigest(final String value) {
        if (value == null || value.length() != DIGEST_LENGTH) {
            return false;
        }
        if (!hasVersionTag(value) || !hasCostFactor(value)) {
            return false;
        }
        return hasRadix64Remainder(value);
    }

    /**
     * The persistence-boundary guard: returns the value when it is a digest and refuses it
     * otherwise.
     *
     * <p>Every path that assigns a value to {@code user_security.sec_usr_pwd} - a record mapper, a
     * seed loader, an administrative update, a repository save - calls this first, passing
     * {@link #USER_SECURITY_PWD_FIELD}. That is what turns "this column holds a digest" from a
     * documented intention into an enforced one, given that the entity's constructor and setter are
     * plain assignments by contract and cannot refuse anything themselves.
     *
     * <p>The rejection message names the column and the required form but never reports the rejected
     * value, and never reports its length or any other property of it. A refusal here means a
     * credential was about to be stored in the clear, so the value is at its most sensitive at
     * exactly the moment it is refused.
     *
     * @param fieldName the column being written, normally {@link #USER_SECURITY_PWD_FIELD}
     * @param value     the value about to be stored
     * @return the same value, unchanged, when it is a digest
     * @throws IllegalArgumentException when the field name is null or blank, or when the value is
     *                                  not a BCrypt digest
     */
    public String requireDigest(final String fieldName, final String value) {
        requireUsableFieldName(fieldName);
        if (!isDigest(value)) {
            throw new IllegalArgumentException("The value supplied for " + fieldName
                    + " is not a " + DIGEST_LENGTH + "-character BCrypt digest and will not be"
                    + " stored; obtain it from CredentialDigestService.encode before it reaches"
                    + " persistence. The rejected value is deliberately not reported.");
        }
        return value;
    }

    /**
     * Reports whether a character sequence is empty or entirely whitespace, without allocating.
     *
     * @param value the sequence to inspect, never null at the single call site
     * @return {@code true} when the sequence carries no non-whitespace character
     */
    private static boolean isBlank(final CharSequence value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks the four-character version tag. Three minor versions are accepted because all three
     * are produced by supported encoders and all three are verifiable.
     *
     * @param value a value already known to be {@value #DIGEST_LENGTH} characters long
     * @return {@code true} when the tag is {@code $2a$}, {@code $2b$} or {@code $2y$}
     */
    private static boolean hasVersionTag(final String value) {
        return value.charAt(0) == '$'
                && value.charAt(1) == '2'
                && (value.charAt(2) == 'a' || value.charAt(2) == 'b' || value.charAt(2) == 'y')
                && value.charAt(3) == '$';
    }

    /**
     * Checks the two-digit cost factor and its trailing separator. The two digits are always written
     * with a leading zero below ten, so a single-digit cost is not a valid encoding.
     *
     * @param value a value already known to be {@value #DIGEST_LENGTH} characters long
     * @return {@code true} when the cost factor is two digits within the accepted range and is
     *         followed by a {@code $}
     */
    private static boolean hasCostFactor(final String value) {
        final char tens = value.charAt(4);
        final char units = value.charAt(5);
        if (tens < '0' || tens > '9' || units < '0' || units > '9' || value.charAt(6) != '$') {
            return false;
        }
        final int cost = (tens - '0') * 10 + (units - '0');
        return cost >= MINIMUM_COST && cost <= MAXIMUM_COST;
    }

    /**
     * Checks that every character of the combined salt and hash belongs to the BCrypt radix-64
     * alphabet.
     *
     * @param value a value already known to be {@value #DIGEST_LENGTH} characters long
     * @return {@code true} when the remainder is entirely radix-64
     */
    private static boolean hasRadix64Remainder(final String value) {
        for (int index = SALT_AND_HASH_OFFSET; index < DIGEST_LENGTH; index++) {
            if (!isRadix64(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a character belongs to the BCrypt radix-64 alphabet {@code ./0-9A-Za-z}. The
     * alphabet excludes {@code +} and {@code =}, so a standard base-64 string of the same length is
     * correctly refused.
     *
     * @param candidate the character to test
     * @return {@code true} when the character is a radix-64 character
     */
    private static boolean isRadix64(final char candidate) {
        return candidate == '.'
                || candidate == '/'
                || (candidate >= '0' && candidate <= '9')
                || (candidate >= 'A' && candidate <= 'Z')
                || (candidate >= 'a' && candidate <= 'z');
    }

    /**
     * Requires a usable column name so that a rejection is always attributable to a column.
     *
     * @param fieldName the caller-supplied column name
     * @throws IllegalArgumentException when the name is null, empty or entirely whitespace
     */
    private static void requireUsableFieldName(final String fieldName) {
        if (fieldName == null || isBlank(fieldName)) {
            throw new IllegalArgumentException(
                    "A column name is required so a refused credential is attributable");
        }
    }
}
