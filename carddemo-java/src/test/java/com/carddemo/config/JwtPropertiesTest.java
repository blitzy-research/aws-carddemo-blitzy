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

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Asserts that the bearer-token settings refuse to bind anything unusable, and that the signing secret
 * cannot escape through a description.
 *
 * <p>The review finding these tests answer was that the module published a token contract - an issuer, a
 * lifetime and a mandatory-transport claim - that no binder, provider or filter consumed, so nothing
 * stopped a deployment from starting without a signing secret. The decisive assertions here are therefore
 * the negative ones: a context with no secret, a blank secret, no issuer or a non-positive lifetime must
 * <em>fail to start</em>. A settings record that merely accepted those values would leave the same gap
 * open under a new name.</p>
 *
 * <p>No real or realistic credential appears in this class. Every secret below is visibly a test value,
 * long enough only because the fixed signature algorithm has a minimum length, and none of them is the
 * value any profile ships.</p>
 */
@DisplayName("Bearer-token settings: unusable configuration stops start-up, and the secret never "
        + "appears in a description")
class JwtPropertiesTest {

    /**
     * A signing secret long enough for the algorithm the provider fixes.
     *
     * <p>Its only property that matters here is its length; it is not a credential and is not the value
     * any profile ships.</p>
     */
    private static final String USABLE_SECRET = "unit-test-signing-secret-not-a-real-credential-0123456789";

    /** Issuer used by the settings under test, matching the value the shipped profiles declare. */
    private static final String ISSUER = "carddemo-java";

    /** Configuration key prefix under test, taken from the type rather than restated. */
    private static final String PREFIX = JwtProperties.PREFIX;

    /**
     * Runner that registers the settings record exactly as the security configuration does.
     *
     * <p>It deliberately enables the properties through the same annotation rather than constructing the
     * record directly, because what is under test is the <em>binding</em> - validation included - and a
     * directly constructed record would bypass the validator entirely.</p>
     */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingHarness.class);

    /**
     * Registers the settings record for binding, and nothing else.
     *
     * <p>Nothing that consumes the settings is present, so a failure observed here is a binding or
     * validation failure and can be nothing else.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class BindingHarness {
    }

    @Nested
    @DisplayName("A configuration that could not mint or verify a token")
    class UnusableConfiguration {

        @Test
        @DisplayName("stops start-up when no signing secret is configured, rather than defaulting one")
        void isRefusedWhenTheSecretIsAbsent() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=PT30M")
                    .run(context -> {
                        assertThat(context)
                                .as("a context with no signing secret must not start")
                                .hasFailed();
                        // Asserted by TYPE as well as by failure, so this cannot pass because the
                        // context broke for some unrelated reason and happened to fail.
                        assertThat(context)
                                .getFailure()
                                .as("the refusal must come from binding these very settings")
                                .isInstanceOf(ConfigurationPropertiesBindException.class)
                                .hasMessageContaining(PREFIX);
                    });
        }

        @ParameterizedTest(name = "secret = [{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("stops start-up when the configured secret is present but blank")
        void isRefusedWhenTheSecretIsBlank(final String blank) {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + blank,
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=PT30M")
                    .run(context -> assertThat(context)
                            .as("a blank signing secret is not a configured one")
                            .hasFailed());
        }

        @Test
        @DisplayName("stops start-up when no issuer is configured, since a token must claim an origin "
                + "the verifier can require")
        void isRefusedWhenTheIssuerIsAbsent() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + USABLE_SECRET,
                            PREFIX + ".expiration=PT30M")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("stops start-up when no lifetime is configured")
        void isRefusedWhenTheLifetimeIsAbsent() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + USABLE_SECRET,
                            PREFIX + ".issuer=" + ISSUER)
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "expiration = {0}")
        @ValueSource(strings = {"PT0S", "PT-1S", "PT-30M"})
        @DisplayName("stops start-up when the lifetime is not a positive span, which would mint tokens "
                + "already unusable at the instant they were issued")
        void isRefusedWhenTheLifetimeIsNotPositive(final String lifetime) {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + USABLE_SECRET,
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=" + lifetime)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        // The root cause must be this module's own message naming the key at fault,
                        // not a generic binding complaint that leaves an operator guessing.
                        assertThat(NestedExceptionUtils
                                .getMostSpecificCause(context.getStartupFailure()))
                                .hasMessageContaining(PREFIX + ".expiration")
                                .hasMessageContaining("positive");
                    });
        }

        @Test
        @DisplayName("names the offending key when the lifetime is rejected, so the failure is actionable")
        void namesTheKeyItRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JwtProperties(USABLE_SECRET, ISSUER, Duration.ZERO))
                    .withMessageContaining(PREFIX + ".expiration");
        }

        @Test
        @DisplayName("tolerates an absent lifetime in the constructor, leaving that report to the "
                + "validator instead of raising a null-pointer failure that names nothing")
        void leavesAnAbsentLifetimeToTheValidator() {
            final JwtProperties constructed = new JwtProperties(USABLE_SECRET, ISSUER, null);

            assertThat(constructed.expiration())
                    .as("the constructor must not pre-empt the validator's report of a missing value")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("A usable configuration")
    class UsableConfiguration {

        @Test
        @DisplayName("binds every component exactly as configured")
        void bindsEveryComponent() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + USABLE_SECRET,
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=PT45M")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final JwtProperties bound = context.getBean(JwtProperties.class);
                        assertThat(bound.secret()).isEqualTo(USABLE_SECRET);
                        assertThat(bound.issuer()).isEqualTo(ISSUER);
                        assertThat(bound.expiration()).isEqualTo(Duration.ofMinutes(45));
                    });
        }

        @Test
        @DisplayName("reads a lifetime written as a duration rather than a bare number, so the "
                + "configured value states its own scale")
        void readsALifetimeThatStatesItsScale() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + USABLE_SECRET,
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=PT8H")
                    .run(context -> assertThat(context.getBean(JwtProperties.class).expiration())
                            .isEqualTo(Duration.ofHours(8)));
        }
    }

    @Nested
    @DisplayName("The description of the settings")
    class Description {

        @Test
        @DisplayName("never contains the signing secret, because anything logging or rendering a "
                + "settings object would otherwise publish a credential")
        void withholdsTheSigningSecret() {
            final String described = new JwtProperties(USABLE_SECRET, ISSUER, Duration.ofMinutes(30))
                    .toString();

            assertThat(described)
                    .as("the description must not carry the secret")
                    .doesNotContain(USABLE_SECRET)
                    .contains("<redacted>");
        }

        @Test
        @DisplayName("does not disclose the secret's length either, which would narrow a search for it")
        void withholdsTheSecretLength() {
            final String described = new JwtProperties(USABLE_SECRET, ISSUER, Duration.ofMinutes(30))
                    .toString();

            assertThat(described)
                    .as("the length of a secret is itself information about it")
                    .doesNotContain(String.valueOf(USABLE_SECRET.length()));
        }

        @Test
        @DisplayName("still shows the issuer and the lifetime, neither of which is a credential and "
                + "both of which are needed to diagnose a refused token")
        void showsWhatIsNotSecret() {
            final String described = new JwtProperties(USABLE_SECRET, ISSUER, Duration.ofMinutes(30))
                    .toString();

            assertThat(described).contains(ISSUER, Duration.ofMinutes(30).toString());
        }

        @Test
        @DisplayName("withholds the secret even when no secret was supplied, so the redaction is "
                + "unconditional rather than value-dependent")
        void redactsUnconditionally() {
            assertThat(new JwtProperties(null, ISSUER, Duration.ofMinutes(30)).toString())
                    .contains("<redacted>")
                    .doesNotContain("null,");
        }
    }

    @Nested
    @DisplayName("The helpers that let a consumer ask about the settings without holding them")
    class Helpers {

        @ParameterizedTest(name = "secret = [{0}]")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("report a blank secret as absent, so a consumer need not repeat the blank test")
        void treatBlankAsAbsent(final String blank) {
            assertThat(new JwtProperties(blank, ISSUER, Duration.ofMinutes(30)).hasSecret()).isFalse();
        }

        @Test
        @DisplayName("report a null secret as absent")
        void treatNullAsAbsent() {
            assertThat(new JwtProperties(null, ISSUER, Duration.ofMinutes(30)).hasSecret()).isFalse();
        }

        @Test
        @DisplayName("report a supplied secret as present")
        void treatASuppliedSecretAsPresent() {
            assertThat(new JwtProperties(USABLE_SECRET, ISSUER, Duration.ofMinutes(30)).hasSecret())
                    .isTrue();
        }

        @Test
        @DisplayName("compare issuers without either caller holding the signing material")
        void compareIssuersAlone() {
            final JwtProperties minting = new JwtProperties(USABLE_SECRET, ISSUER, Duration.ofMinutes(30));
            final JwtProperties verifying =
                    new JwtProperties("a-completely-different-secret-value-0123456789012", ISSUER,
                            Duration.ofHours(1));

            assertThat(minting.sharesIssuerWith(verifying))
                    .as("agreement on the issuer is independent of the secret and the lifetime")
                    .isTrue();
        }

        @Test
        @DisplayName("report a differing issuer as a disagreement")
        void reportADifferingIssuer() {
            final JwtProperties mine = new JwtProperties(USABLE_SECRET, ISSUER, Duration.ofMinutes(30));

            assertThat(mine.sharesIssuerWith(
                    new JwtProperties(USABLE_SECRET, "somebody-else", Duration.ofMinutes(30))))
                    .isFalse();
        }

        @Test
        @DisplayName("treat a missing comparison subject as a disagreement rather than failing")
        void treatNullComparisonAsDisagreement() {
            assertThat(new JwtProperties(USABLE_SECRET, ISSUER, Duration.ofMinutes(30))
                    .sharesIssuerWith(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("The configuration key prefix")
    class KeyPrefix {

        @Test
        @DisplayName("is the one the shipped profiles declare these settings under")
        void isTheShippedPrefix() {
            assertThat(JwtProperties.PREFIX).isEqualTo("carddemo.security.jwt");
        }

        @Test
        @DisplayName("is a child of the security namespace rather than the transport or at-rest key, "
                + "each of which is bound by its own consumer")
        void isDistinctFromItsSiblings() {
            assertThat(JwtProperties.PREFIX)
                    .startsWith("carddemo.security.")
                    .isNotEqualTo("carddemo.security.require-https")
                    .isNotEqualTo("carddemo.security.field-encryption");
        }
    }
}
