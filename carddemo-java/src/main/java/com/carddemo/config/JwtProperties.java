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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The bearer-token settings this module signs and verifies with, bound from configuration and
 * validated before the container will start.
 *
 * <p><strong>What this replaces.</strong> The legacy sign-on program, transaction {@code CC00} at
 * {@code app/cbl/COSGN00C.cbl}, was pseudo-conversational: lines 98 to 102 of that member close every
 * turn by returning with the next transaction identifier together with the shared communication area,
 * so the area itself carried each fact the following turn needed. That area is
 * {@code CARDDEMO-COMMAREA}, declared at {@code app/cpy/COCOM01Y.cpy} line 19, 160 bytes wide, and
 * included textually by all 17 online programs. This module carries no such area. It signs the two
 * security facts that area held into a bearer token - the 8-character {@code CDEMO-USER-ID} at
 * {@code app/cpy/COCOM01Y.cpy} line 25 and the 1-character {@code CDEMO-USER-TYPE} at line 26, whose
 * only permitted codes are the level-88 condition names {@code CDEMO-USRTYP-ADMIN} and
 * {@code CDEMO-USRTYP-USER} at lines 27 and 28 - and leaves the mutable navigation remainder to
 * {@code com.carddemo.api.dto.NavigationContext}, which the client echoes on its next call. The legacy
 * estate is cited above by member path, field name, width and line number only; no COBOL, copybook or
 * screen-map source text is reproduced here or anywhere else in this module.
 *
 * <p>These settings therefore configure that token and nothing else, and the three values below are the
 * whole of what the substitution needs: the material the signature is computed with, the name the token
 * claims as its origin, and how long a token remains usable. There is deliberately no component for a
 * user identifier, a user type, a route or any other navigation concern - each of those travels in a
 * minted token or in the context record, never in configuration. Nothing else about the substitution is
 * configurable, because nothing else about it is a deployment decision.
 *
 * <p><strong>The shared baseline defaults the signing material nowhere, and neither does production.
 * The local and test overlays deliberately do.</strong> The baseline declares the issuer and the
 * lifetime and states no secret at all - not an empty string, not a placeholder, not a development
 * value promoted by accident - so there is no shared value for a profile to inherit or for a deployment
 * to fall back on. Production declares the {@code CARDDEMO_JWT_SECRET} reference with nothing beside
 * it. Local and test declare the same reference <em>with</em> a fallback, so a developer or a test run
 * that exports nothing still reaches a usable value, and each fallback's own text says what it is:
 * {@code local-development-only-...-do-not-reuse} and {@code test-only-...-not-used-outside-tests}. The
 * copy of the test overlay on the test class path states its literal outright rather than as a
 * reference, because a test run has no environment to read.
 *
 * <p>That asymmetry is the contract rather than an oversight. A fallback is admissible exactly where
 * the tokens it signs can never be presented to a production deployment, and inadmissible where they
 * can: a defaulted production secret would violate the no-hardcoded-credentials requirement exactly as
 * thoroughly as a literal one, because a deployment that forgot to set the variable would sign real
 * tokens with a value published in this repository.
 *
 * <p><strong>What actually stops a production deployment that has not set the variable, since it is not
 * the constraint below.</strong> Configuration-properties binding resolves placeholders leniently, so
 * an unset variable binds the reference's own text, and that text is not blank. An <em>empty</em>
 * variable is caught here, because an empty string is blank. An <em>absent</em> one is caught twice
 * over elsewhere: {@link ProductionConfigurationValidator} refuses a production value that still
 * carries its own placeholder text, naming the variable a deployer must set, and
 * {@link JwtTokenProvider} refuses it because placeholder text cannot meet the fixed algorithm's
 * key-length floor. Production fails fast, by those two mechanisms rather than by this record's
 * annotation, and the distinction is stated because a reader who believes the annotation is the guard
 * would remove one of the mechanisms that actually is.
 *
 * <p><strong>Validation is split between two mechanisms, and the split is not arbitrary.</strong> The
 * constraint annotations below express <em>presence</em>, which is what the binder can check on a value
 * it has just read, and they are what turns a missing or blank secret into a refusal to start - with the
 * one exception recorded above, where lenient placeholder resolution hands the binder text that is
 * present and not blank, and the two mechanisms named there take over. The compact constructor
 * expresses the one condition an annotation cannot state - that a lifetime must be a
 * positive span of time - and it deliberately tolerates {@code null} so that an absent lifetime is
 * reported by {@link NotNull} rather than by a null-pointer failure raised before validation runs. The
 * two never overlap, so there is never a question of which one fires: presence is the validator's,
 * positivity is the constructor's.
 *
 * <p>Two things are deliberately <em>not</em> here. There is no signing-algorithm setting: the algorithm
 * is fixed in {@link JwtTokenProvider}, because a token accepted under an algorithm chosen at deployment
 * time is a token whose verification strength is a deployment accident. And there is no key-length
 * setting: the minimum length is a property of the fixed algorithm, so it is enforced where the
 * algorithm is chosen rather than restated as configuration that could disagree with it.
 *
 * <p><strong>Nothing here is a threshold or a service level.</strong> {@link #expiration()} is a
 * credential lifetime, not a latency, time-out or capacity figure; the legacy estate documents no such
 * figure and this module asserts none.
 *
 * <p>The split of validation between annotations and the constructor, and the rule that neither the
 * shared baseline nor the production profile may default the signing value, are reasoned in
 * {@code docs/decision-log.md} DL-097.
 *
 * @param secret     material the token signature is computed with, supplied in production by the
 *                   {@code CARDDEMO_JWT_SECRET} environment variable and resolved there with <em>no
 *                   fallback default</em>. The shared baseline declares no value for it, so a production
 *                   deployment that has not set that variable cannot reach a running state - stopped by
 *                   the production configuration check and by the key-length floor rather than by the
 *                   constraint declared here, which catches a blank value. The local and test overlays
 *                   deliberately do declare a fallback, and it is an explicitly non-production one.
 *                   Never logged, never serialized and never carried in {@link #toString()}
 * @param issuer     name a minted token claims as its origin and the value a presented token is required
 *                   to carry. Every shipped profile states it directly rather than through a variable,
 *                   because it names this module rather than an environment. It is deliberately not this
 *                   module's metric service label: one identifies the token's origin and the other labels
 *                   a meter, and neither should be edited to match the other
 * @param expiration how long a minted token stays usable, as a span of time rather than a count of
 *                   units, so the configured value states its own scale. Supplied in production by the
 *                   {@code CARDDEMO_JWT_EXPIRATION} environment variable over a declared fallback -
 *                   admissible for this component precisely because a lifetime is not a credential
 */
@ConfigurationProperties(prefix = JwtProperties.PREFIX)
@Validated
public record JwtProperties(
        @NotBlank(message = "A bearer-token signing secret must be configured; none is defaulted")
        String secret,

        @NotBlank(message = "A bearer-token issuer must be configured")
        String issuer,

        @NotNull(message = "A bearer-token lifetime must be configured")
        Duration expiration) {

    /**
     * Configuration key prefix these settings bind from.
     *
     * <p>Published as a compile-time constant so the annotation above, the messages that name a missing
     * key, and any test asserting the bound contract all refer to one authority rather than repeating the
     * literal. The trailing segment is deliberately separate from the sibling
     * {@code carddemo.security.require-https} and {@code carddemo.security.field-encryption} keys: those
     * are transport and at-rest concerns and are bound by their own consumers.</p>
     */
    public static final String PREFIX = "carddemo.security.jwt";

    /** Replacement carried in {@link #toString()} in place of the signing material. */
    private static final String REDACTED = "<redacted>";

    /**
     * Rejects a lifetime that is not a positive span of time.
     *
     * <p>A zero or negative lifetime would mint tokens that are already unusable at the instant they are
     * issued, which presents as every authenticated request failing for no visible reason. Refusing it
     * here converts that into a start-up failure naming the key.</p>
     *
     * <p>A {@code null} lifetime is deliberately allowed through: it is a <em>missing</em> value rather
     * than an invalid one, and reporting it is {@link NotNull}'s job. Checking it here as well would
     * raise a null-pointer failure before validation ever ran, replacing a message that names the key
     * with one that names nothing.</p>
     *
     * @throws IllegalArgumentException if {@code expiration} is present and is zero or negative
     */
    public JwtProperties {
        if (expiration != null && (expiration.isZero() || expiration.isNegative())) {
            throw new IllegalArgumentException(
                    PREFIX + ".expiration must be a positive duration, but was " + expiration);
        }
    }

    /**
     * Describes these settings without disclosing the signing material.
     *
     * <p>The generated record description would carry every component, and one of them is a credential.
     * Anything that logs a configuration object, and any assertion failure that renders one, would then
     * publish the signing secret into a log or a build report. This override exists so that neither can:
     * the secret is replaced by a fixed marker, and its length is not disclosed either, since the length
     * of a secret narrows a search for it.</p>
     *
     * <p>The issuer and the lifetime are shown. Neither is a credential, and both are useful when
     * diagnosing a token that a peer refuses.</p>
     *
     * @return a description in which the signing material is replaced by a fixed marker
     */
    @Override
    public String toString() {
        return "JwtProperties[secret=" + REDACTED
                + ", issuer=" + this.issuer
                + ", expiration=" + this.expiration
                + ']';
    }

    /**
     * Reports whether a signing secret is present and not blank.
     *
     * <p>Exists so that a caller which must decide something before validation has run - and
     * {@link JwtTokenProvider} is the only one - can ask the question without writing its own blank
     * test and without placing the secret in a local variable it might then include in a message.</p>
     *
     * @return {@code true} when a non-blank signing secret is configured
     */
    public boolean hasSecret() {
        return this.secret != null && !this.secret.isBlank();
    }
}
