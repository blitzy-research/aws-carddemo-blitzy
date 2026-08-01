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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Asserts that a token is trusted only when it is genuinely trustworthy, and that a rejection is silent
 * about why.
 *
 * <p>The token replaces the communication area the legacy pseudo-conversation handed from one program to
 * the next. That area was trusted because only the transaction manager could produce one; a token is
 * trusted only because of its signature, so the assertions that matter most here are the four rejections -
 * a tampered token, an expired one, one signed with different material, and one issued by somebody else.
 * A provider that accepted any of them would reproduce the convenience of the communication area without
 * reproducing the thing that made trusting it reasonable.</p>
 *
 * <p>Expiry is asserted by minting under one clock and verifying under a later one, which is possible only
 * because the provider judges expiry against the clock it was given rather than the platform clock. That
 * is the property being tested as much as the rejection itself: a provider reading the platform clock could
 * not be tested for expiry at all without making a test wait.</p>
 *
 * <p>No real credential appears in this class. Every secret is visibly a test value, long enough only
 * because the fixed signature algorithm has a minimum length, and no value any profile ships is restated
 * here - least of all the single shared password literal the legacy provisioning job stream carried.</p>
 */
@DisplayName("Bearer tokens: trusted only when genuinely trustworthy, and silent about why not")
class JwtTokenProviderTest {

    /** Signing material for the provider under test. Not a credential; length is its only relevant property. */
    private static final String SECRET = "unit-test-signing-secret-not-a-real-credential-0123456789";

    /** Different signing material of equal adequacy, used to prove a foreign signature is refused. */
    private static final String FOREIGN_SECRET = "a-different-unit-test-secret-value-9876543210abcdefghij";

    /** Issuer the provider claims and requires. */
    private static final String ISSUER = "carddemo-java";

    /** Lifetime the provider mints with. */
    private static final Duration LIFETIME = Duration.ofMinutes(30);

    /** Fixed instant every mint in this class happens at, so an expiry assertion is exact. */
    private static final Instant MINTED_AT = Instant.parse("2026-01-01T12:00:00Z");

    /** A signed-on identifier of the width the legacy sign-on record keyed on. */
    private static final String SUBJECT = "ADMIN001";

    /**
     * Builds a provider whose clock is fixed at a chosen instant.
     *
     * @param secret signing material
     * @param issuer issuer to claim and require
     * @param at     instant the provider treats as now, for both minting and judging expiry
     * @return the provider
     */
    private static JwtTokenProvider providerAt(final String secret, final String issuer, final Instant at) {
        return new JwtTokenProvider(new JwtProperties(secret, issuer, LIFETIME),
                Clock.fixed(at, ZoneOffset.UTC));
    }

    /**
     * Builds a provider at {@link #MINTED_AT} with the standard secret and issuer.
     *
     * @return the provider
     */
    private static JwtTokenProvider provider() {
        return providerAt(SECRET, ISSUER, MINTED_AT);
    }

    /**
     * Decodes a token's claim segment to readable text.
     *
     * <p>Used only to assert what a token does <em>not</em> carry. A token is a bearer credential and is
     * readable by anyone holding it, so this decoding demonstrates a property of the token rather than
     * defeating one.</p>
     *
     * @param token a compact token
     * @return the decoded claim segment
     */
    private static String claimSegmentOf(final String token) {
        final String[] segments = token.split("\\.");
        return new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("A token this provider minted")
    class AMintedToken {

        @Test
        @DisplayName("verifies, and returns the identity and user type it was minted for")
        void roundTrips() {
            final JwtTokenProvider provider = provider();

            final String token = provider.issue(SUBJECT, UserType.ADMIN);
            final Jwt verified = provider.verify(token).orElseThrow();

            assertThat(verified.getSubject()).isEqualTo(SUBJECT);
            assertThat(provider.userTypeOf(verified)).contains(UserType.ADMIN);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("round-trips either user type the estate defines, carrying the legacy code itself")
        void roundTripsEveryUserType(final UserType userType) {
            final JwtTokenProvider provider = provider();

            final Jwt verified = provider.verify(provider.issue(SUBJECT, userType)).orElseThrow();

            assertThat(provider.userTypeOf(verified)).contains(userType);
            assertThat(verified.getClaimAsString(JwtTokenProvider.ROLE_CLAIM))
                    .as("the role claim carries the legacy user-type code rather than a renamed equivalent")
                    .isEqualTo(userType.getCode());
        }

        @Test
        @DisplayName("claims the configured issuer, which is what lets a verifier require it")
        void claimsTheConfiguredIssuer() {
            final JwtTokenProvider provider = provider();

            final Jwt verified = provider.verify(provider.issue(SUBJECT, UserType.USER)).orElseThrow();

            // Read as a string deliberately: the framework's typed issuer accessor is a URL, and this
            // module's issuer names a module rather than an address.
            assertThat(verified.getClaimAsString("iss")).isEqualTo(ISSUER);
        }

        @Test
        @DisplayName("expires exactly one configured lifetime after the instant it was minted at")
        void expiresOneLifetimeAfterMinting() {
            final Jwt verified = provider().verify(provider().issue(SUBJECT, UserType.USER)).orElseThrow();

            assertThat(verified.getIssuedAt()).isEqualTo(MINTED_AT);
            assertThat(verified.getExpiresAt()).isEqualTo(MINTED_AT.plus(LIFETIME));
        }

        @Test
        @DisplayName("carries no signing material, no credential and no personal data")
        void carriesNothingSecret() {
            final String claims = claimSegmentOf(provider().issue(SUBJECT, UserType.ADMIN));

            assertThat(claims)
                    .as("a token is readable by anyone holding it, so nothing secret may be placed in one")
                    .doesNotContain(SECRET)
                    .doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("secret");
        }

        @Test
        @DisplayName("carries only the claims this module places in it, and no others")
        void carriesOnlyTheDocumentedClaims() {
            final Jwt verified = provider().verify(provider().issue(SUBJECT, UserType.ADMIN)).orElseThrow();

            assertThat(verified.getClaims())
                    .as("an unexpected claim is either a leak or a contract change")
                    .containsOnlyKeys("iss", "sub", "iat", "exp", JwtTokenProvider.ROLE_CLAIM);
        }
    }

    @Nested
    @DisplayName("A token that may not be trusted")
    class AnUntrustworthyToken {

        @Test
        @DisplayName("is refused when its signature does not match its content")
        void isRefusedWhenTampered() {
            final JwtTokenProvider provider = provider();
            final String token = provider.issue(SUBJECT, UserType.USER);
            final String tampered = token.substring(0, token.length() - 4) + "AAAA";

            assertThat(provider.verify(tampered))
                    .as("a tampered signature must not verify")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused when its claims were altered after signing, which is the whole point "
                + "of signing them")
        void isRefusedWhenClaimsWereAltered() {
            final JwtTokenProvider provider = provider();
            final String issued = provider.issue(SUBJECT, UserType.USER);
            final String[] segments = issued.split("\\.");
            // Promote the standard user to the administrative type, leaving the original signature in
            // place. This is the attack the signature exists to defeat.
            final String forgedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    claimSegmentOf(issued)
                            .replace("\"" + UserType.USER.getCode() + "\"",
                                    "\"" + UserType.ADMIN.getCode() + "\"")
                            .getBytes(StandardCharsets.UTF_8));

            assertThat(forgedClaims)
                    .as("the forgery must actually differ from the original, or this proves nothing")
                    .isNotEqualTo(segments[1]);
            assertThat(provider.verify(segments[0] + '.' + forgedClaims + '.' + segments[2]))
                    .as("promoting a standard user to the administrative type must not verify")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused once its lifetime has elapsed, judged against the clock the provider "
                + "was given rather than the platform clock")
        void isRefusedWhenExpired() {
            final String token = providerAt(SECRET, ISSUER, MINTED_AT).issue(SUBJECT, UserType.USER);
            // Beyond the lifetime and beyond the verifier's permitted clock skew, so the rejection is
            // the expiry and not a borderline reading of it.
            final JwtTokenProvider later =
                    providerAt(SECRET, ISSUER, MINTED_AT.plus(LIFETIME).plus(Duration.ofMinutes(5)));

            assertThat(later.verify(token)).as("an elapsed token must not verify").isEmpty();
        }

        @Test
        @DisplayName("is accepted while its lifetime is still running, so the expiry rule is a boundary "
                + "rather than a blanket refusal")
        void isAcceptedBeforeExpiry() {
            final String token = providerAt(SECRET, ISSUER, MINTED_AT).issue(SUBJECT, UserType.USER);
            final JwtTokenProvider slightlyLater =
                    providerAt(SECRET, ISSUER, MINTED_AT.plus(Duration.ofMinutes(29)));

            assertThat(slightlyLater.verify(token)).isPresent();
        }

        @Test
        @DisplayName("is refused when signed with different material, so a token from another deployment "
                + "carries no authority here")
        void isRefusedWhenSignedWithForeignMaterial() {
            final String foreign = providerAt(FOREIGN_SECRET, ISSUER, MINTED_AT)
                    .issue(SUBJECT, UserType.ADMIN);

            assertThat(provider().verify(foreign))
                    .as("a valid signature under the wrong key is not a valid token")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused when issued by somebody else, even with matching signing material")
        void isRefusedWhenIssuedElsewhere() {
            final String elsewhere = providerAt(SECRET, "some-other-issuer", MINTED_AT)
                    .issue(SUBJECT, UserType.ADMIN);

            assertThat(provider().verify(elsewhere)).isEmpty();
        }

        @ParameterizedTest(name = "presented = [{0}]")
        @ValueSource(strings = {"", " ", "not-a-token", "a.b.c", "..", "eyJhbGciOiJub25lIn0..",
                "Bearer eyJhbGciOiJIUzI1NiJ9"})
        @DisplayName("is refused when it is not a token this provider could have produced, including "
                + "one offering no algorithm at all")
        void isRefusedWhenMalformed(final String presented) {
            assertThat(provider().verify(presented)).isEmpty();
        }

        @Test
        @DisplayName("is refused rather than raising, when absent, because an absent credential is an "
                + "ordinary condition and not an error")
        void isRefusedWhenAbsent() {
            assertThat(provider().verify(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Configuration this provider cannot work with")
    class UnusableConfiguration {

        @Test
        @DisplayName("is refused at construction when the secret is too short for the fixed algorithm, "
                + "naming the key rather than failing later inside the signing library")
        void isRefusedWhenTheSecretIsTooShort() {
            final JwtProperties tooShort = new JwtProperties("far-too-short", ISSUER, LIFETIME);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new JwtTokenProvider(tooShort, Clock.systemUTC()))
                    .withMessageContaining(JwtProperties.PREFIX + ".secret")
                    .withMessageContaining("32");
        }

        @Test
        @DisplayName("is refused at construction when the secret is absent, so no provider can exist "
                + "without material to sign with")
        void isRefusedWhenTheSecretIsAbsent() {
            final JwtProperties noSecret = new JwtProperties(null, ISSUER, LIFETIME);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new JwtTokenProvider(noSecret, Clock.systemUTC()))
                    .withMessageContaining(JwtProperties.PREFIX + ".secret");
        }

        @Test
        @DisplayName("is refused when no clock is supplied, since expiry could then be judged against "
                + "nothing")
        void isRefusedWithoutAClock() {
            final JwtProperties usable = new JwtProperties(SECRET, ISSUER, LIFETIME);

            assertThatNullPointerException()
                    .isThrownBy(() -> new JwtTokenProvider(usable, null));
        }

        @Test
        @DisplayName("does not disclose the secret in the refusal it raises")
        void doesNotDiscloseTheSecretWhenRefusing() {
            final JwtProperties tooShort = new JwtProperties("far-too-short", ISSUER, LIFETIME);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new JwtTokenProvider(tooShort, Clock.systemUTC()))
                    .withMessageNotContaining("far-too-short");
        }
    }

    @Nested
    @DisplayName("The authority a user type carries")
    class Authorities {

        @Test
        @DisplayName("is the administrative one for the administrative type")
        void isAdministrativeForAdministrators() {
            assertThat(JwtTokenProvider.authorityOf(UserType.ADMIN))
                    .isEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
        }

        @Test
        @DisplayName("is the standard one for the standard type")
        void isStandardForStandardUsers() {
            assertThat(JwtTokenProvider.authorityOf(UserType.USER))
                    .isEqualTo(JwtTokenProvider.USER_AUTHORITY);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("is defined for every user type the estate defines, so the mapping needs no fallback")
        void isDefinedForEveryUserType(final UserType userType) {
            assertThat(JwtTokenProvider.authorityOf(userType)).isNotBlank();
        }

        @Test
        @DisplayName("is spelled with the framework's role prefix, so a rule written as an authority "
                + "and one written as a role agree")
        void carriesTheRolePrefix() {
            assertThat(JwtTokenProvider.ADMIN_AUTHORITY).startsWith("ROLE_");
            assertThat(JwtTokenProvider.USER_AUTHORITY).startsWith("ROLE_");
        }

        @Test
        @DisplayName("distinguishes the two types, so one cannot satisfy a rule written for the other")
        void distinguishesTheTwoTypes() {
            assertThat(JwtTokenProvider.ADMIN_AUTHORITY).isNotEqualTo(JwtTokenProvider.USER_AUTHORITY);
        }

        @Test
        @DisplayName("refuses a missing user type rather than defaulting to the lesser one")
        void refusesAMissingUserType() {
            assertThatNullPointerException().isThrownBy(() -> JwtTokenProvider.authorityOf(null));
        }
    }

    @Nested
    @DisplayName("Reading the user type from a verified token")
    class ReadingTheUserType {

        /**
         * Builds claims directly, without minting or verifying.
         *
         * <p>Necessary to reach this reader in isolation: a token whose role claim was edited would fail
         * verification first - correctly - so the reader could never be handed one. Constructing the
         * claims instead tests the reader's own behaviour on input the chain could present to it if a
         * future minting path ever wrote a code this estate does not define.</p>
         *
         * @param roleClaim value to place in the role claim, or {@code null} to omit it entirely
         * @return claims carrying that role and nothing else of interest
         */
        private Jwt claimsCarryingRole(final String roleClaim) {
            final Jwt.Builder builder = Jwt.withTokenValue("token-value-not-parsed-by-this-reader")
                    .header("alg", "HS256")
                    .subject(SUBJECT);
            if (roleClaim != null) {
                builder.claim(JwtTokenProvider.ROLE_CLAIM, roleClaim);
            }
            return builder.build();
        }

        @ParameterizedTest(name = "role = [{0}]")
        @ValueSource(strings = {"Z", "a", "u", "ADMIN", "", " ", "AU"})
        @DisplayName("yields nothing when the token carries a code the estate does not define, so an "
                + "unreadable entitlement grants no authority instead of the lesser one")
        void yieldsNothingForAnUndefinedCode(final String undefined) {
            assertThat(provider().userTypeOf(claimsCarryingRole(undefined)))
                    .as("an undefined user-type code must grant nothing, not the standard type")
                    .isEmpty();
        }

        @Test
        @DisplayName("yields nothing when the token carries no role claim at all")
        void yieldsNothingWhenTheClaimIsAbsent() {
            assertThat(provider().userTypeOf(claimsCarryingRole(null))).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserType.class)
        @DisplayName("yields the user type whose legacy code the claim carries")
        void yieldsTheTypeMatchingTheCode(final UserType userType) {
            assertThat(provider().userTypeOf(claimsCarryingRole(userType.getCode())))
                    .contains(userType);
        }

        @Test
        @DisplayName("refuses a missing token rather than answering for one")
        void refusesAMissingToken() {
            assertThatNullPointerException().isThrownBy(() -> provider().userTypeOf(null));
        }
    }

    @Nested
    @DisplayName("The lifetime the provider reports")
    class ReportedLifetime {

        @Test
        @DisplayName("is the configured one, so a sign-on response can tell a client how long its "
                + "credential lasts")
        void isTheConfiguredLifetime() {
            assertThat(provider().tokenLifetime()).isEqualTo(LIFETIME);
        }

        @Test
        @DisplayName("matches the window of a token it actually mints, so the reported figure and the "
                + "issued one cannot disagree")
        void matchesTheWindowOfAMintedToken() {
            final JwtTokenProvider provider = provider();
            final Jwt verified = provider.verify(provider.issue(SUBJECT, UserType.USER)).orElseThrow();

            assertThat(Duration.between(verified.getIssuedAt(), verified.getExpiresAt()))
                    .isEqualTo(provider.tokenLifetime());
        }
    }

    @Nested
    @DisplayName("Minting")
    class Minting {

        @Test
        @DisplayName("refuses a missing identifier rather than minting an unattributed token")
        void refusesAMissingIdentifier() {
            assertThatNullPointerException()
                    .isThrownBy(() -> provider().issue(null, UserType.USER));
        }

        @Test
        @DisplayName("refuses a missing user type rather than minting a token of unknown entitlement")
        void refusesAMissingUserType() {
            assertThatNullPointerException().isThrownBy(() -> provider().issue(SUBJECT, null));
        }

        @Test
        @DisplayName("produces the three-segment compact form, signed rather than merely encoded")
        void producesASignedCompactToken() {
            final String token = provider().issue(SUBJECT, UserType.USER);

            assertThat(token.split("\\.")).hasSize(3);
            assertThat(token.split("\\.")[2])
                    .as("the third segment is the signature; an unsigned token would leave it empty")
                    .isNotEmpty();
        }
    }
}
