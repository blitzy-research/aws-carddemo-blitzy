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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Acceptance for the key-holding service that stands between the entity layer and the codec.
 *
 * <p>The service carries one security-bearing responsibility beyond delegation: it is the point at
 * which the deployment's key is admitted or refused. AAP section 0.8.1 requires that a secret be
 * resolved from the environment with <em>no fallback default</em>, so that a missing secret fails
 * startup rather than binding a placeholder and silently protecting nothing. Two kinds of assertion
 * discharge that: constructor-level refusals for material that is present but wrong, and a container
 * test proving that material which is entirely absent prevents the bean from being created at all.
 *
 * <p>The second kind is the one that actually pins the requirement, because a constructor cannot
 * distinguish "absent" from "blank" on its own - Spring decides that, and it decides it differently
 * depending on whether a default was written into the placeholder. Registering a strict placeholder
 * configurer reproduces the production arrangement and lets the absence be observed.
 */
@DisplayName("SensitiveFieldEncryptionService - the deployment key is admitted or refused here")
class SensitiveFieldEncryptionServiceTest {

    /** A 32-byte key, Base64-encoded, of the form the configuration property expects. */
    private static final String VALID_BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-service-test-key-01234!".getBytes(StandardCharsets.UTF_8));

    /** A second, different, valid key used to prove cross-key reads fail. */
    private static final String OTHER_BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-service-test-key-98765?".getBytes(StandardCharsets.UTF_8));

    /** A synthetic nine-digit value of the shape the national-identifier field carries. */
    private static final String NINE_DIGIT_IDENTIFIER = "123456789";

    @Nested
    @DisplayName("key material that is present but wrong is refused at construction")
    class ConstructionRefusals {

        @Test
        @DisplayName("an absent value is refused and the message names the property")
        void anAbsentValueIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SensitiveFieldEncryptionService(null))
                    .withMessageContaining(
                            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY)
                    .withMessageContaining("no default is provided");
        }

        @ParameterizedTest(name = "a blank value [{0}] is refused")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("a blank value is refused rather than decoded to an empty key")
        void aBlankValueIsRefused(String blank) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SensitiveFieldEncryptionService(blank))
                    .withMessageContaining(
                            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY)
                    .withMessageContaining("must not be blank");
        }

        @ParameterizedTest(name = "\"{0}\" is refused as not Base64")
        @ValueSource(strings = {
            "not-base64-key-material!!!!",
            "carddemo-plain-text-key-value!!!",
            "${CARDDEMO_FIELD_ENCRYPTION_KEY}"})
        @DisplayName("a value that is not Base64 is refused, including an unresolved placeholder")
        void aValueThatIsNotBase64IsRefused(String notBase64) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SensitiveFieldEncryptionService(notBase64))
                    .withMessageContaining(
                            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY)
                    .withMessageContaining("must be Base64-encoded");
        }

        @ParameterizedTest(name = "key material of {0} bytes is refused")
        @ValueSource(ints = {1, 15, 16, 24, 31, 33, 48, 64})
        @DisplayName("valid Base64 that decodes to the wrong length is refused")
        void validBase64OfTheWrongLengthIsRefused(int decodedLength) {
            String encoded = Base64.getEncoder().encodeToString(new byte[decodedLength]);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SensitiveFieldEncryptionService(encoded))
                    .withMessageContaining(
                            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY)
                    .withMessageContaining("must decode to exactly "
                            + SensitiveFieldCodec.KEY_LENGTH_BYTES + " bytes");
        }

        @Test
        @DisplayName("no refusal message quotes the configured value")
        void noRefusalMessageQuotesTheConfiguredValue() {
            String wrongLength = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SensitiveFieldEncryptionService(wrongLength))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(wrongLength));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SensitiveFieldEncryptionService("plainly-not-base64!!"))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain("plainly-not-base64!!"));
        }

        @Test
        @DisplayName("surrounding whitespace is tolerated, because a mounted secret often carries it")
        void surroundingWhitespaceIsTolerated() {
            SensitiveFieldEncryptionService service =
                    new SensitiveFieldEncryptionService("  " + VALID_BASE64_KEY + "\n");

            assertThat(service.reveal(service.protect(NINE_DIGIT_IDENTIFIER)))
                    .isEqualTo(NINE_DIGIT_IDENTIFIER);
        }

        @Test
        @DisplayName("the property name is the one the profiles bind")
        void thePropertyNameIsTheOneTheProfilesBind() {
            assertThat(SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY)
                    .isEqualTo("carddemo.security.field-encryption.key");
        }
    }

    @Nested
    @DisplayName("key material that is absent prevents the bean from being created")
    class StartupFailsWithoutAKey {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(StrictPlaceholderConfiguration.class,
                        SensitiveFieldEncryptionService.class);

        @Test
        @DisplayName("with no property bound the context refuses to start")
        void withNoPropertyBoundTheContextRefusesToStart() {
            runner.run(context -> assertThat(context)
                    .as("AAP section 0.8.1 requires a missing secret to fail startup rather than "
                            + "resolve to a default")
                    .hasFailed());
        }

        @Test
        @DisplayName("the startup failure names the unresolved property")
        void theStartupFailureNamesTheUnresolvedProperty() {
            runner.run(context -> assertThat(context).getFailure()
                    .rootCause()
                    .hasMessageContaining(
                            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY));
        }

        @Test
        @DisplayName("with a valid property bound the bean is created and works")
        void withAValidPropertyBoundTheBeanIsCreated() {
            runner.withPropertyValues(SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY
                            + "=" + VALID_BASE64_KEY)
                    .run(context -> {
                        assertThat(context).hasNotFailed()
                                .hasSingleBean(SensitiveFieldEncryptionService.class);
                        SensitiveFieldEncryptionService service =
                                context.getBean(SensitiveFieldEncryptionService.class);
                        assertThat(service.reveal(service.protect(NINE_DIGIT_IDENTIFIER)))
                                .isEqualTo(NINE_DIGIT_IDENTIFIER);
                    });
        }

        @Test
        @DisplayName("with a wrong-length property bound the context fails with the service's reason")
        void withAWrongLengthPropertyTheContextFails() {
            runner.withPropertyValues(SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY
                            + "=" + Base64.getEncoder().encodeToString(new byte[16]))
                    .run(context -> assertThat(context).getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("must decode to exactly "
                                    + SensitiveFieldCodec.KEY_LENGTH_BYTES + " bytes"));
        }

        /**
         * Registers the strict placeholder configurer that a Spring Boot application registers for
         * itself, so that an unresolvable {@code ${...}} is a failure here exactly as it is there.
         * Without it the plain container would hand the literal placeholder text to the constructor,
         * which would refuse it for the wrong reason and prove nothing about absence.
         */
        @Configuration(proxyBeanMethods = false)
        static class StrictPlaceholderConfiguration {

            @Bean
            static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
                return new PropertySourcesPlaceholderConfigurer();
            }
        }
    }

    @Nested
    @DisplayName("protection round-trips and agrees with the codec")
    class Protection {

        private final SensitiveFieldEncryptionService service =
                new SensitiveFieldEncryptionService(VALID_BASE64_KEY);

        @Test
        @DisplayName("a required value round-trips unchanged")
        void aRequiredValueRoundTripsUnchanged() {
            assertThat(service.reveal(service.protect(NINE_DIGIT_IDENTIFIER)))
                    .isEqualTo(NINE_DIGIT_IDENTIFIER);
        }

        @Test
        @DisplayName("an empty value is protected rather than treated as absent")
        void anEmptyValueIsProtected() {
            String envelope = service.protect("");

            assertThat(service.isProtected(envelope)).isTrue();
            assertThat(service.reveal(envelope)).isEmpty();
        }

        @Test
        @DisplayName("the produced envelope satisfies the entity's persistence guard shape")
        void theProducedEnvelopeSatisfiesTheGuardShape() {
            assertThat(service.isProtected(service.protect(NINE_DIGIT_IDENTIFIER))).isTrue();
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(service.protect(NINE_DIGIT_IDENTIFIER)))
                    .isTrue();
        }

        @Test
        @DisplayName("cleartext is not reported as protected")
        void cleartextIsNotReportedAsProtected() {
            assertThat(service.isProtected(NINE_DIGIT_IDENTIFIER)).isFalse();
            assertThat(service.isProtected("")).isFalse();
            assertThat(service.isProtected(null)).isFalse();
        }

        @Test
        @DisplayName("a required protect refuses an absent value rather than storing nothing")
        void aRequiredProtectRefusesAnAbsentValue() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.protect(null))
                    .withMessageContaining("cleartext must not be null");
        }

        @Test
        @DisplayName("a required reveal refuses an absent value")
        void aRequiredRevealRefusesAnAbsentValue() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.reveal(null))
                    .withMessageContaining("envelope must not be null");
        }

        @Test
        @DisplayName("the nullable pair maps absence to absence in both directions")
        void theNullablePairMapsAbsenceToAbsence() {
            assertThat(service.protectNullable(null)).isNull();
            assertThat(service.revealNullable(null)).isNull();
        }

        @Test
        @DisplayName("the nullable pair round-trips a present value like the required pair")
        void theNullablePairRoundTripsAPresentValue() {
            assertThat(service.revealNullable(service.protectNullable(NINE_DIGIT_IDENTIFIER)))
                    .isEqualTo(NINE_DIGIT_IDENTIFIER);
        }

        @Test
        @DisplayName("reading unprotected content is refused, which is how leaked cleartext surfaces")
        void readingUnprotectedContentIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.reveal(NINE_DIGIT_IDENTIFIER))
                    .withMessageContaining("is not an " + SensitiveFieldCodec.ENVELOPE_PREFIX
                            + " envelope");
        }

        @Test
        @DisplayName("a value sealed under a different deployment key cannot be read")
        void aValueSealedUnderADifferentKeyCannotBeRead() {
            String foreign = new SensitiveFieldEncryptionService(OTHER_BASE64_KEY)
                    .protect(NINE_DIGIT_IDENTIFIER);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.reveal(foreign))
                    .withMessageContaining("the key does not match");
        }

        @Test
        @DisplayName("protecting the same value twice yields two different envelopes")
        void protectingTheSameValueTwiceYieldsDifferentEnvelopes() {
            assertThat(service.protect(NINE_DIGIT_IDENTIFIER))
                    .as("randomised encryption is why neither protected column can be searched by "
                            + "equality; that consequence is recorded in the decision log")
                    .isNotEqualTo(service.protect(NINE_DIGIT_IDENTIFIER));
        }

        @Test
        @DisplayName("two services holding the same key can read each other's envelopes")
        void twoServicesHoldingTheSameKeyInteroperate() {
            String envelope = new SensitiveFieldEncryptionService(VALID_BASE64_KEY)
                    .protect(NINE_DIGIT_IDENTIFIER);

            assertThat(service.reveal(envelope)).isEqualTo(NINE_DIGIT_IDENTIFIER);
        }
    }
}
