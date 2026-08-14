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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Specification of the trust this deployment requires of the collector it posts every span to.
 *
 * <h2>What is being asserted, and why the address is worth a specification of its own</h2>
 *
 * <p>Until this rule existed the collector address was checked for presence and nothing else. Presence is a
 * weak property for a destination that receives the trace of every authenticated request: the route it
 * took, its timing, its span names and every attribute the instrumentation attached. A plain-{@code http}
 * address puts all of that on the wire in clear text and lets whatever answers on that port impersonate the
 * collector. A locator carrying user information puts a credential into a value that configuration dumps and
 * process listings reproduce. A query or a fragment is evidence the value was assembled out of something
 * that is not an OTLP endpoint. A path that is not the one the specification fixes is not addressing a
 * traces receiver at all. And a loopback host in production is an unset variable quietly discarding every
 * span while the deployment believes it is exporting.
 *
 * <p>Each of the five rules therefore has a case here that varies exactly one component of an otherwise
 * acceptable address, so no case can pass for a reason other than the rule it is named for. Two further
 * cases carry the properties a refusal itself must have: it must name every broken rule rather than the
 * first, because a deployer fixing one at a time learns about them one attempt at a time; and it must never
 * repeat the configured value, because the failure mode this rule exists for - a credential pasted into a
 * locator - is precisely the thing a diagnostic must not copy into a log.
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test over the static check, driven with a mock environment. No context is built and no
 * exporter exists, which is the point: the check runs as a bean-factory post-processor, before any singleton
 * is created, so a deployment addressing the wrong collector never starts rather than starting and posting
 * somewhere for a while.
 */
@DisplayName("the production profile's trace-collector posture")
class TraceCollectorTrustTest {

    /** An address that satisfies every rule, and the base every case below varies one component of. */
    private static final String ACCEPTABLE = "https://collector.internal:4318/v1/traces";

    /**
     * Builds an environment carrying one collector address.
     *
     * @param  configured the address to configure, or {@code null} to configure none
     * @return the environment
     */
    private static MockEnvironment withAddress(final String configured) {
        final MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(ProductionConfigurationValidator.PRODUCTION_PROFILE);
        if (configured != null) {
            environment.setProperty(
                    ProductionConfigurationValidator.TRACE_COLLECTOR_ENDPOINT_KEY, configured);
        }
        return environment;
    }

    /**
     * Asserts that one address is refused, and that the refusal says nothing it should not.
     *
     * @param configured the address to offer
     * @param because    the rule the address breaks, as it appears in the refusal
     */
    private static void assertRefused(final String configured, final String because) {
        assertThatExceptionOfType(IllegalStateException.class)
                .as("refused: %s", configured)
                .isThrownBy(() -> ProductionConfigurationValidator
                        .validateTraceCollectorAddress(withAddress(configured)))
                .withMessageContaining(
                        ProductionConfigurationValidator.TRACE_COLLECTOR_ENDPOINT_KEY)
                .withMessageContaining(because)
                .withMessageNotContaining(configured);
    }

    @Nested
    @DisplayName("an acceptable address is accepted, and an absent one is somebody else's report")
    class WhatIsAccepted {

        @Test
        @DisplayName("an authenticated collector on the OTLP traces path is accepted")
        void anAuthenticatedCollectorIsAccepted() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateTraceCollectorAddress(withAddress(ACCEPTABLE)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a default port is accepted, because the rule is about the transport and the path "
                + "rather than about where a collector listens")
        void aDefaultPortIsAccepted() {
            assertThatCode(() -> ProductionConfigurationValidator.validateTraceCollectorAddress(
                    withAddress("https://collector.internal/v1/traces")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the scheme is compared without regard to case, because a locator's scheme is "
                + "case-insensitive and refusing HTTPS would be refusing a correct address")
        void theSchemeIsCaseInsensitive() {
            assertThatCode(() -> ProductionConfigurationValidator.validateTraceCollectorAddress(
                    withAddress("HTTPS://collector.internal:4318/v1/traces")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent address is not reported here, because the required-settings sweep already "
                + "reports it and one fault told twice sends a deployer looking for two")
        void anAbsentAddressIsNotReportedTwice() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateTraceCollectorAddress(withAddress(null)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateTraceCollectorAddress(withAddress("   ")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a null environment rather than treating an absence as acceptable")
        void refusesANullEnvironment() {
            assertThatNullPointerException().isThrownBy(
                    () -> ProductionConfigurationValidator.validateTraceCollectorAddress(null));
        }
    }

    @Nested
    @DisplayName("each of the five rules refuses on its own")
    class WhatIsRefused {

        @Test
        @DisplayName("plain http is refused, so the spans of an authenticated request are not carried in "
                + "clear text to a host that cannot prove it is the collector")
        void plainHttpIsRefused() {
            assertRefused("http://collector.internal:4318/v1/traces", "https");
        }

        @Test
        @DisplayName("a scheme that is neither is refused too, so the rule is an allow of one rather than "
                + "a deny of one")
        void anotherSchemeIsRefused() {
            assertRefused("grpc://collector.internal:4317/v1/traces", "https");
        }

        @Test
        @DisplayName("user information is refused, because a credential in a locator is a credential in "
                + "every place a locator is written down")
        void userInformationIsRefused() {
            assertRefused("https://operator:secret@collector.internal:4318/v1/traces",
                    "no user information");
        }

        @Test
        @DisplayName("a query is refused, because an OTLP receiver reads none and its presence says where "
                + "the value came from")
        void aQueryIsRefused() {
            assertRefused("https://collector.internal:4318/v1/traces?token=abc",
                    "neither a query nor a");
        }

        @Test
        @DisplayName("a fragment is refused, for the same reason as a query")
        void aFragmentIsRefused() {
            assertRefused("https://collector.internal:4318/v1/traces#part",
                    "neither a query nor a");
        }

        @Test
        @DisplayName("another path is refused, because the OTLP over HTTP specification fixes the traces "
                + "path and anything else is not a traces receiver")
        void anotherPathIsRefused() {
            assertRefused("https://collector.internal:4318/ingest", "/v1/traces");
        }

        @Test
        @DisplayName("no path at all is refused, so an address that merely names a host cannot pass")
        void noPathIsRefused() {
            assertRefused("https://collector.internal:4318", "/v1/traces");
        }

        @Test
        @DisplayName("a loopback name is refused, because the collector is not in this process and a "
                + "loopback address means every span is being discarded")
        void aLoopbackNameIsRefused() {
            assertRefused("https://localhost:4318/v1/traces", "loopback");
        }

        @Test
        @DisplayName("every loopback form a fallback or a typo actually produces is refused")
        void everyLoopbackFormIsRefused() {
            for (final String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3",
                    "[::1]")) {
                assertRefused("https://" + host + ":4318/v1/traces", "loopback");
            }
        }

        @Test
        @DisplayName("an address with no host at all is refused, which is the shape a partially expanded "
                + "variable produces and the one shape every other rule accepts")
        void anAddressWithoutAHostIsRefused() {
            // Well formed, https, no user information, no query, and the exact traces path: every other
            // rule is satisfied, so this case can only be refused by the rule it is named for.
            assertRefused("https:///v1/traces", "must name a host");
        }

        @Test
        @DisplayName("an address that is not a locator at all is refused, and the refusal still does not "
                + "repeat it")
        void aMalformedAddressIsRefused() {
            assertRefused("https://collector .internal/v1/traces", "well-formed");
        }
    }

    @Nested
    @DisplayName("what the refusal itself has to do")
    class WhatTheRefusalDoes {

        @Test
        @DisplayName("names every broken rule rather than the first, so a deployer with three faults does "
                + "not learn about them in three attempts")
        void namesEveryBrokenRule() {
            final String threeFaults = "http://localhost:4318/ingest";

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateTraceCollectorAddress(withAddress(threeFaults)))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains("https")
                            .contains("loopback")
                            .contains("/v1/traces")
                            .contains("3 rule(s)"));
        }

        @Test
        @DisplayName("never repeats the configured value, because the fault this rule exists for is a "
                + "credential pasted into the locator")
        void neverRepeatsTheConfiguredValue() {
            final String withCredential = "http://operator:a-real-looking-secret@collector.internal/x";

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateTraceCollectorAddress(withAddress(withCredential)))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain("a-real-looking-secret")
                            .doesNotContain("operator:")
                            .doesNotContain(withCredential)
                            .contains("deliberately not repeated"));
        }

        @Test
        @DisplayName("names the profile it refused and points at the recorded decision, so the message is "
                + "actionable without the code")
        void namesTheProfileAndTheDecision() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateTraceCollectorAddress(
                                    withAddress("http://collector.internal/v1/traces")))
                    .withMessageContaining(ProductionConfigurationValidator.PRODUCTION_PROFILE)
                    .withMessageContaining("DL-311")
                    .withMessageContaining("application-prod.yml");
        }
    }

    @Nested
    @DisplayName("the two literals the rule is stated in")
    class TheStatedLiterals {

        @Test
        @DisplayName("the required scheme and path are the ones the OTLP over HTTP specification fixes, "
                + "stated once so the check and the shipped profile cannot disagree")
        void theRequiredSchemeAndPathAreStatedOnce() {
            assertThat(ProductionConfigurationValidator.TRACE_COLLECTOR_REQUIRED_SCHEME)
                    .isEqualTo("https");
            assertThat(ProductionConfigurationValidator.TRACE_COLLECTOR_REQUIRED_PATH)
                    .isEqualTo("/v1/traces");
            assertThat(ProductionConfigurationValidator.TRACE_COLLECTOR_ENDPOINT_KEY)
                    .isEqualTo("management.otlp.tracing.endpoint");
        }
    }
}
