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
package com.carddemo.config;

import com.carddemo.domain.enums.UserType;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies the bearer tokens that carry sign-on state across requests, replacing the
 * communication area the legacy pseudo-conversation passed from one program to the next.
 *
 * <p>The legacy sign-on program established a user identifier and a user type, wrote both into a
 * communication area, and every subsequent turn of the conversation received that area back and trusted
 * it. The area was trusted because only the transaction manager could produce one. Nothing equivalent
 * exists over HTTP, so the same two facts travel in a token whose signature is what makes them
 * trustworthy, and this class is the only place in the module that produces or checks that signature.
 *
 * <p><strong>The algorithm is fixed here and is not configurable.</strong> A token is verified under
 * {@link MacAlgorithm#HS256}, stated on the minting header and required by the verifier, so a token
 * offering a different algorithm - including none at all - is refused rather than accepted on the
 * strength of its own claim about how it should be checked. Making this a setting would make the
 * strength of every verification a deployment accident, which is the whole of the reason it is a
 * constant.
 *
 * <p><strong>What a token carries, and nothing more.</strong> The issuer, the subject, the issue and
 * expiry instants, and one role claim. The role claim carries the legacy user-type code itself rather
 * than a renamed equivalent, because that code is the vocabulary the estate used and round-tripping it
 * unchanged keeps the token readable against the source record. No password, no hash, no salt, no
 * personal data and no account or card identifier is ever placed in a token: a token is a bearer
 * credential, it is readable by anyone holding it, and it is therefore treated as a public document that
 * happens to be signed.
 *
 * <p><strong>Verification never throws for an untrustworthy token.</strong> {@link #verify(String)}
 * answers with an empty result for every rejection - absent, malformed, wrongly signed, expired or
 * issued by someone else - because the caller's decision is the same in every one of those cases and a
 * caller that must distinguish them would be tempted to report the distinction to the client. What the
 * client learns is that it is not authenticated; which of the five reasons applies stays on this side.
 *
 * <p><strong>Two traps are avoided deliberately, and both were confirmed against the library rather
 * than assumed.</strong> The first is that the issuer claim of a verified token must be read with
 * {@link Jwt#getClaimAsString(String)} and never with {@code Jwt.getIssuer()}: that accessor is typed as
 * a URL, this module's issuer names a module rather than an address, and the decoded claim is a plain
 * string. The second is that expiry is evaluated against the {@link Clock} this class is given rather
 * than against the platform clock, so the instant a token is minted at and the instant it is judged
 * against come from one source and a test can move that source.
 *
 * <p><strong>Nothing secret is ever logged.</strong> No token, no fragment of one, no signing material
 * and no verification-failure message from the library reaches a log statement here. A rejection is
 * recorded as the fact of a rejection plus the library's exception type, because a failure message can
 * quote the offending token and a log is not a place a credential may reach.
 *
 * <p>The choice of signing primitive, the decision to add no dependency for it, the fixed algorithm, and
 * the reason a service-label issuer is read from the raw claim rather than through the locator-typed
 * accessor are reasoned in {@code docs/decision-log.md} DL-097.
 */
@Component
public class JwtTokenProvider {

    /**
     * Claim name under which a token carries the user type.
     *
     * <p>Published so that the minting side, the verifying side and any test naming the claim refer to
     * one authority.</p>
     */
    public static final String ROLE_CLAIM = "role";

    /**
     * Authority granted to a token whose user type is the administrative one.
     *
     * <p>Spelled with the framework's conventional role prefix so that authorization rules expressed as
     * an authority and rules expressed as a role agree, and published as a constant so the filter chain
     * and this class cannot drift apart on the spelling.</p>
     */
    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /** Authority granted to a token whose user type is the standard one. */
    public static final String USER_AUTHORITY = "ROLE_USER";

    /** Logger. Never receives a token, a token fragment, or signing material. */
    private static final Logger LOG = LoggerFactory.getLogger(JwtTokenProvider.class);

    /**
     * The one signature algorithm this module mints under and the only one it will verify.
     *
     * <p>A message-authentication algorithm rather than a public-key one, because a token minted and
     * verified by the same module has no second party needing a separate verification key.</p>
     */
    private static final MacAlgorithm SIGNATURE_ALGORITHM = MacAlgorithm.HS256;

    /** Platform key algorithm name matching {@link #SIGNATURE_ALGORITHM}. */
    private static final String MAC_KEY_ALGORITHM = "HmacSHA256";

    /**
     * Shortest signing secret {@link #SIGNATURE_ALGORITHM} may be used with, in bytes.
     *
     * <p>It is a property of the fixed algorithm, which is why it lives beside the algorithm rather than
     * being restated as configuration that could disagree with it. Checking it here converts a
     * key-length failure raised from inside the signing library on the first mint into a start-up failure
     * that names the configuration key at fault.</p>
     */
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** Mints signed tokens. Holds the signing key; never exposed. */
    private final JwtEncoder encoder;

    /** Verifies signature, expiry and issuer. Holds the signing key; never exposed. */
    private final JwtDecoder decoder;

    /** Time source for the issue instant, and the same source the expiry check uses. */
    private final Clock clock;

    /** How long a minted token stays usable. */
    private final Duration expiration;

    /** Value a minted token claims as its origin. */
    private final String issuer;

    /**
     * Builds the minting and verifying sides from validated configuration.
     *
     * <p>The signing material is turned into a key, handed to both sides, and the intermediate byte array
     * is then cleared. Clearing it is hygiene rather than a guarantee - the key object retains the
     * material, as it must - but it removes one copy that would otherwise sit in the heap for the life of
     * the process for no purpose, since both the key specification and the signing-key source take their
     * own copy.</p>
     *
     * <p>The presence and length checks are stated as {@link IllegalStateException} rather than left to
     * the signing library because the library would raise them on the first attempt to mint a token,
     * which is a request rather than a start-up, and its message names an algorithm rather than the
     * configuration key that must be corrected.</p>
     *
     * @param properties validated token settings; the secret must be present and long enough for the
     *                   fixed algorithm
     * @param clock      time source for minting and for judging expiry, so both come from one source
     * @throws IllegalStateException if the signing secret is absent, blank, or shorter than the fixed
     *                               algorithm permits
     * @throws NullPointerException  if either argument is {@code null}
     */
    public JwtTokenProvider(final JwtProperties properties, final Clock clock) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        if (!properties.hasSecret()) {
            throw new IllegalStateException(JwtProperties.PREFIX
                    + ".secret must be configured; no signing secret is defaulted anywhere in this module");
        }
        final byte[] keyMaterial = properties.secret().getBytes(StandardCharsets.UTF_8);
        try {
            if (keyMaterial.length < MINIMUM_SECRET_BYTES) {
                throw new IllegalStateException(JwtProperties.PREFIX + ".secret must encode at least "
                        + MINIMUM_SECRET_BYTES + " bytes for " + SIGNATURE_ALGORITHM.getName()
                        + ", but the configured value encodes " + keyMaterial.length);
            }
            final javax.crypto.SecretKey signingKey =
                    new javax.crypto.spec.SecretKeySpec(keyMaterial, MAC_KEY_ALGORITHM);
            this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));

            final JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
            timestampValidator.setClock(clock);
            final NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(signingKey)
                    .macAlgorithm(SIGNATURE_ALGORITHM)
                    .build();
            nimbusDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    timestampValidator, new JwtIssuerValidator(properties.issuer())));
            this.decoder = nimbusDecoder;
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
        }

        this.issuer = properties.issuer();
        this.expiration = properties.expiration();
    }

    /**
     * Mints a signed token establishing a signed-on identity and its user type.
     *
     * <p>The two facts placed in the token are exactly the two the legacy communication area carried
     * forward from the sign-on program. The issue instant comes from this class's clock and the expiry is
     * that instant plus the configured lifetime, so a token's window is determined entirely by when it
     * was minted and never by when it is presented.</p>
     *
     * @param userId   signed-on user identifier, as the legacy record keyed it
     * @param userType the signed-on user's type, which decides the authority the token grants
     * @return the compact serialized token
     * @throws NullPointerException if either argument is {@code null}
     */
    public String issue(final String userId, final UserType userType) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(userType, "userType must not be null");

        final Instant issuedAt = this.clock.instant();
        final JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(this.issuer)
                .subject(userId)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(this.expiration))
                .claim(ROLE_CLAIM, userType.getCode())
                .build();

        final Jwt minted = this.encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SIGNATURE_ALGORITHM).build(), claims));
        LOG.debug("Issued a bearer token for a signed-on identity, expiring at {}", minted.getExpiresAt());
        return minted.getTokenValue();
    }

    /**
     * Verifies a presented token and answers its claims, or nothing at all.
     *
     * <p>Signature, expiry and issuer are all required. An empty result means the token may not be
     * trusted and carries no indication of which requirement it failed, deliberately: the caller's next
     * action is identical in every case, and a caller handed the distinction could relay it to whoever
     * presented the token.</p>
     *
     * @param token the presented compact token; {@code null} and blank both yield an empty result rather
     *              than a failure, because an absent credential is an ordinary condition and not an error
     * @return the verified claims, or empty when the token is absent or may not be trusted
     */
    public Optional<Jwt> verify(final String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(this.decoder.decode(token));
        } catch (final JwtException rejected) {
            // The exception TYPE is recorded and its message is not: a verification-failure message can
            // quote the offending token, and a token is a credential.
            LOG.debug("Rejected a presented bearer token; verification failed as {}",
                    rejected.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Reads the user type a verified token carries.
     *
     * <p>The claim holds the legacy user-type code, so it is mapped back through the same lookup the
     * source record's values map through. A token carrying no role claim, or one carrying a code the
     * estate does not define, yields an empty result and therefore grants no authority - it does not
     * fall back to the lesser of the two types, because a token whose type cannot be read is a token
     * whose entitlement is unknown.</p>
     *
     * @param jwt a token already verified by {@link #verify(String)}
     * @return the user type the token carries, or empty when it carries none this estate defines
     * @throws NullPointerException if {@code jwt} is {@code null}
     */
    public Optional<UserType> userTypeOf(final Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        return UserType.fromCode(jwt.getClaimAsString(ROLE_CLAIM));
    }

    /**
     * Names the granted authority a user type carries.
     *
     * <p>The estate defines exactly two user types and this module grants exactly two authorities, so the
     * mapping is total and needs no fallback.</p>
     *
     * @param userType the user type to translate
     * @return {@link #ADMIN_AUTHORITY} for the administrative type, {@link #USER_AUTHORITY} otherwise
     * @throws NullPointerException if {@code userType} is {@code null}
     */
    public static String authorityOf(final UserType userType) {
        Objects.requireNonNull(userType, "userType must not be null");
        return userType.isAdmin() ? ADMIN_AUTHORITY : USER_AUTHORITY;
    }

    /**
     * The lifetime a minted token is given.
     *
     * <p>Exposed so that a sign-on response can tell a client how long the credential it was just handed
     * remains usable, which is the one piece of the token's configuration a client legitimately needs.
     * It is a credential lifetime and not a service level.</p>
     *
     * @return the configured token lifetime
     */
    public Duration tokenLifetime() {
        return this.expiration;
    }
}
