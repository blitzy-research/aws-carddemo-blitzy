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
import com.carddemo.service.SessionTokenIssuer;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
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
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies the bearer token that carries a signed-on identity across requests, replacing the
 * communication area the legacy pseudo-conversation handed from one program to the next.
 *
 * <p><strong>What is being replaced.</strong> The legacy sign-on transaction closed every turn by
 * returning with its own transaction identifier re-armed and the shared communication area passed back
 * at that area's own declared length ({@code app/cbl/COSGN00C.cbl} L98-L102). The terminal manager
 * handed the area to whichever program ran next, and nineteen such re-arms across the seventeen online
 * programs, together with twenty-five program-to-program transfers, all depended on it. The area is
 * {@code CARDDEMO-COMMAREA}, declared at {@code app/cpy/COCOM01Y.cpy} L19, 160 bytes wide and included
 * textually by every one of those programs. It could be trusted because only the transaction manager
 * could produce one. Nothing over HTTP has that property, so the facts that must survive a request
 * boundary travel instead in a token whose signature is the entire reason to trust them, and this class
 * is the only place in the module that produces or checks that signature.
 *
 * <p><strong>The split: exactly two facts are signed, and the whole remainder is echoed by the
 * client.</strong> That area carried five groups, and only two of its fields are security facts:
 *
 * <ul>
 *   <li>{@code CDEMO-USER-ID}, {@code PIC X(08)} at {@code app/cpy/COCOM01Y.cpy} L25, becomes the
 *       subject claim. It is carried through exactly as supplied - eight characters, never trimmed,
 *       never folded in case, never parsed as a number - because it is the fixed-width key of the user
 *       record and any change of width or case would stop matching it. Sign-on upper-cases what was
 *       keyed in before using it at all, and does so unconditionally
 *       ({@code app/cbl/COSGN00C.cbl} L132-L136), so the value reaching this class is already
 *       upper-cased and this class deliberately does not fold it again.</li>
 *   <li>{@code CDEMO-USER-TYPE}, {@code PIC X(01)} at L26, becomes the role claim, carrying the raw
 *       one-character code rather than a renamed equivalent, because that code is the vocabulary the
 *       estate used and round-tripping it unchanged keeps a token readable against the source record.
 *       Its two declared values are the level-88 condition names {@code CDEMO-USRTYP-ADMIN}, value
 *       {@code A} at L27, and {@code CDEMO-USRTYP-USER}, value {@code U} at L28, modelled by
 *       {@link UserType}.</li>
 * </ul>
 *
 * <p>Everything else that area held is mutable per-request navigation state, and it belongs to
 * {@code com.carddemo.api.dto.NavigationContext}, which the client echoes on its next call: the from-
 * and to-transaction identifiers and program names of the general-info group, the last map and mapset
 * names of the more-info group, the selected customer identifier and the three customer name fields,
 * the selected account identifier and status, the selected card number, and the one-digit
 * {@code CDEMO-PGM-CONTEXT} re-entry flag at L29 whose level-88 values are {@code CDEMO-PGM-ENTER},
 * value {@code 0} at L30, and {@code CDEMO-PGM-REENTER}, value {@code 1} at L31 - the flag that decides
 * whether field-level error decoration is applied at all. None of that is signed here.
 *
 * <p>Reproducing the area wholesale as claims would be wrong twice over. It would publish business
 * selections into an artifact that clients log, forward and cache, which the communication area never
 * was; and it would put server-carried navigation state back into a design whose whole point is that
 * the client drives the next call. So no customer name, account identifier, card number,
 * social-security number or credit score is ever placed in a token, and neither is a route, a target
 * program name, a screen name nor any other selection.
 *
 * <p><strong>Routing tolerance is preserved, not tightened.</strong> Sign-on tests the administrator
 * condition and, when it holds, transfers to the administrative menu; the alternative is
 * <em>unconditional</em> and transfers to the main menu, with no second test and no third branch for a
 * code the estate never declared ({@code app/cbl/COSGN00C.cbl} L230-L240). So reading a role claim never
 * throws on an unexpected character: an unrecognised code yields no user type, and a token whose type
 * cannot be read grants no authority rather than falling back to the lesser of the two.
 *
 * <p><strong>The algorithm is fixed here and is not configurable.</strong> A token is minted under
 * {@link MacAlgorithm#HS256}, stated on the header this class writes, and the verifier is built for that
 * algorithm alone - so a token offering a different one, including none at all, is refused rather than
 * accepted on the strength of its own claim about how it ought to be checked. Making this a setting
 * would make the strength of every verification a deployment accident, which is the whole of the reason
 * it is a constant. The same reasoning keeps the minimum key length out of configuration: it is a
 * property of the fixed algorithm, so it is enforced beside the algorithm rather than restated as a
 * setting that could disagree with it.
 *
 * <p><strong>Verification never throws for an untrustworthy token.</strong> {@link #verify(String)}
 * answers with an empty result for every rejection - absent, malformed, wrongly signed, expired, or
 * issued by somebody else - because the caller's decision is identical in all five cases, and a caller
 * handed the distinction would be tempted to relay it to whoever presented the token. What a client
 * learns is that it is not authenticated; which of the five reasons applies stays on this side.
 *
 * <p><strong>Two traps are avoided deliberately, and both were established against the library rather
 * than assumed.</strong> The first is that the issuer claim of a verified token must be read with
 * {@link Jwt#getClaimAsString(String)} and never through the locator-typed issuer accessor: this
 * module's issuer names a module rather than an address, and the decoded claim reads back as plain text.
 * The second is that expiry is judged against the {@link Clock} this class is given rather than against
 * the platform clock, so the instant a token is minted at and the instant it is judged against come
 * from one source - which is also what lets a test assert expiry without waiting.
 *
 * <p><strong>Nothing secret is ever logged, and nothing secret ever reaches a message.</strong> No
 * token, no fragment of one and no signing material appears in any log statement here; the only thing
 * recorded is the category of a verification failure, deliberately excluding the library's own failure
 * message, because such a message can quote the offending token. The refusals this class raises for
 * unusable configuration name the configuration key at fault and the length the fixed algorithm
 * requires, and disclose neither the configured value, nor any part of it, nor its length, nor a digest
 * of it - the length of a secret narrows a search for it.
 *
 * <p><strong>Why the signing key is expressed as a JOSE key rather than as a platform key
 * specification.</strong> Both consumers of the key are JOSE components, and the symmetric key type of
 * the JOSE library already on the compile class path is their native representation, so the key is
 * described once in that form and each consumer takes what it needs from it. The verifying side's
 * builder accepts only a platform secret key, so the JOSE key is asked to render itself as one, naming
 * the platform algorithm explicitly rather than letting the key be built without one. Expressing it
 * this way also keeps this file's type references inside the namespaces the module standardises on. No
 * dependency is added for any of it: the token library arrives with the framework security artifact the
 * manifest already declares, and the manifest is not touched.
 *
 * <p>The choice of signing primitive, the decision to add no dependency for it, the fixed algorithm, the
 * rule that no profile may default the signing material, and the reason a service-label issuer is read
 * from the raw claim rather than through the locator-typed accessor are reasoned in
 * {@code docs/decision-log.md} DL-097. The legacy estate is cited above by member path, field name,
 * width, condition-name value and line number only; no COBOL, copybook or screen-map source text is
 * reproduced here or anywhere else in this module.
 */
@Component
public class JwtTokenProvider implements SessionTokenIssuer {

    /**
     * Claim name under which a token carries the user type.
     *
     * <p>Published so that the minting side, the verifying side, the filter chain and any test naming
     * the claim all refer to one authority rather than repeating the literal.</p>
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

    /**
     * Logger. Receives the category of a verification failure and nothing else: never a token, never a
     * fragment of one, never signing material, and never a third-party failure message.
     */
    private static final Logger LOG = LoggerFactory.getLogger(JwtTokenProvider.class);

    /**
     * The one signature algorithm this module mints under and the only one it will verify.
     *
     * <p>A message-authentication algorithm rather than a public-key one, because a token minted and
     * verified by the same module has no second party needing a separate verification key.</p>
     */
    private static final MacAlgorithm SIGNATURE_ALGORITHM = MacAlgorithm.HS256;

    /**
     * Platform algorithm name for {@link #SIGNATURE_ALGORITHM}.
     *
     * <p>The two names describe one algorithm at two layers - the registered name the token header
     * carries, and the name the platform knows the keyed hash by. This one is stated so the signing key
     * is rendered as a key <em>for</em> that algorithm rather than as an unattributed block of bytes.</p>
     */
    private static final String MAC_KEY_ALGORITHM = "HmacSHA256";

    /**
     * Shortest signing secret {@link #SIGNATURE_ALGORITHM} may be used with, in bytes.
     *
     * <p>It is a property of the fixed algorithm, which is why it lives beside the algorithm rather than
     * being restated as configuration that could disagree with it. Checking it here converts a
     * key-length failure raised from inside the signing library on the first mint - that is, during a
     * request - into a start-up failure that names the configuration key at fault. It is a cryptographic
     * floor and not a capacity, latency or timing figure of any kind.</p>
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

    /** Value a minted token claims as its origin, and the value a presented token must carry. */
    private final String issuer;

    /**
     * Builds the minting and verifying sides from validated configuration.
     *
     * <p>The signing material arrives only through {@link JwtProperties}, which binds it from the
     * {@value JwtProperties#PREFIX} key group. Nothing here reads the environment, and nothing here
     * substitutes a value: there is no default, no generated fallback, no development key and no
     * convenience literal, because a defaulted signing secret violates the no-hardcoded-credentials
     * constraint exactly as thoroughly as a literal one would. The shared configuration baseline
     * declares no secret at all and the production profile resolves it from a bare environment
     * reference, so a deployment that has not supplied one cannot reach a running state.</p>
     *
     * <p>The material is described once as a JOSE symmetric key, from which the minting side takes a key
     * set of one and the verifying side takes the platform secret key its builder requires. The
     * intermediate byte array is then cleared. Clearing it is hygiene rather than a guarantee - the key
     * retains the material, as it must - but it removes one copy that would otherwise sit in the heap
     * for the life of the process for no purpose, since the key takes its own.</p>
     *
     * <p>The presence and length checks are raised here rather than left to the signing library because
     * the library would raise them on the first attempt to mint a token, which happens during a request
     * rather than at start-up, and its message names an algorithm rather than the configuration key that
     * has to be corrected. The length check is also the mechanism that catches an unresolved placeholder:
     * configuration-properties binding resolves placeholders leniently, so an unset environment variable
     * binds the reference's own text, which is present and not blank and therefore satisfies the bound
     * record's constraints - but cannot meet this algorithm's key-length floor.</p>
     *
     * @param properties validated token settings; the secret must be present and long enough for the
     *                   fixed algorithm
     * @param clock      time source for minting and for judging expiry, so that both come from one
     *                   source and a test can move it
     * @throws IllegalStateException if the configured signing secret is absent, blank, or shorter than
     *                               the fixed algorithm permits. The message names the configuration key
     *                               and the required length, and discloses nothing about the value
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
                // The shortfall is deliberately not stated. Naming the required length tells a deployer
                // what to supply; naming the configured length would disclose a property of the secret.
                throw new IllegalStateException(JwtProperties.PREFIX + ".secret must encode at least "
                        + MINIMUM_SECRET_BYTES + " bytes for " + SIGNATURE_ALGORITHM.getName()
                        + "; the configured value is too short");
            }
            final OctetSequenceKey signingKey = new OctetSequenceKey.Builder(keyMaterial).build();
            this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));

            final JwtTimestampValidator windowValidator = new JwtTimestampValidator();
            windowValidator.setClock(clock);
            final NimbusJwtDecoder tokenDecoder =
                    NimbusJwtDecoder.withSecretKey(signingKey.toSecretKey(MAC_KEY_ALGORITHM))
                            .macAlgorithm(SIGNATURE_ALGORITHM)
                            .build();
            tokenDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    windowValidator, new JwtIssuerValidator(properties.issuer())));
            this.decoder = tokenDecoder;
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
     * forward out of the sign-on program, and nothing else is added. The issue instant comes from this
     * class's clock and the expiry is that instant advanced by the configured lifetime, so a token's
     * window is determined entirely by when it was minted and never by when it is presented.</p>
     *
     * @param userId   the signed-on user identifier, already upper-cased by the sign-on path as the
     *                 legacy program upper-cases it, and placed in the subject claim verbatim at the
     *                 fixed width of the user record's key - it is neither trimmed, re-cased nor parsed
     * @param userType the signed-on user's type, whose raw one-character code becomes the role claim and
     *                 which decides the authority a verified token grants
     * @return the compact serialized token
     * @throws NullPointerException if either argument is {@code null}
     */
    @Override
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

        return this.encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SIGNATURE_ALGORITHM).build(), claims)).getTokenValue();
    }

    /**
     * Verifies a presented token and answers its claims, or nothing at all.
     *
     * <p>Signature, expiry and issuer are all required, and the algorithm is the one fixed by this class
     * rather than the one the presented token names. An empty result means the token may not be trusted
     * and carries no indication of which requirement it failed, deliberately: the caller's next action is
     * identical in every case, and a caller handed the distinction could relay it to whoever presented
     * the token.</p>
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
     * <p>The claim holds the raw legacy user-type code, so it is resolved through the same lookup the
     * source record's values resolve through. A token carrying no role claim, or one carrying a code the
     * estate does not declare, yields an empty result and therefore grants no authority - it does not
     * fall back to the lesser of the two types, because a token whose type cannot be read is a token
     * whose entitlement is unknown. Resolution never throws, matching the sign-on program's tolerance of
     * an unexpected code at {@code app/cbl/COSGN00C.cbl} L230-L240.</p>
     *
     * @param jwt a token already verified by {@link #verify(String)}
     * @return the user type the token carries, or empty when it carries none this estate declares
     * @throws NullPointerException if {@code jwt} is {@code null}
     */
    public Optional<UserType> userTypeOf(final Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        return UserType.fromCode(jwt.getClaimAsString(ROLE_CLAIM));
    }

    /**
     * Names the granted authority a user type carries.
     *
     * <p>The estate declares exactly two user types and this module grants exactly two authorities, so
     * the mapping is total and needs no fallback. Only the administrative type reaches the
     * administrative authority, mirroring the single condition the sign-on program tests at
     * {@code app/cbl/COSGN00C.cbl} L230; every other type reaches the standard authority, mirroring the
     * unconditional alternative at L235.</p>
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
     * It is a credential lifetime and not a service level, a latency budget or a time-out.</p>
     *
     * @return the configured token lifetime
     */
    public Duration tokenLifetime() {
        return this.expiration;
    }
}
