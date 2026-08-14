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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.web.servlet.WebMvcObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves in a real container that a refused oversized body is recorded rather than merely returned.
 *
 * <h2>Why an order assertion was not enough on its own</h2>
 *
 * <p>The companion unit test pins the filter's registration order against the framework's own two
 * constants, which is the mechanism. This class observes the outcome: it sends an oversized body over a
 * real socket to a real container and then asks the meter registry what was recorded. Those are different
 * claims, and the second is the one the deployment actually depends on - an ordering that is right on
 * paper still has to produce a timed, counted response, and nothing but a running chain can show that it
 * does.
 *
 * <p>The defect being closed left <em>no</em> trace: the caller received 413 and the deployment received
 * no request timing, no span and no log line. So the assertion that matters is that a series exists at all
 * for a status the previous arrangement could not produce a series for.
 *
 * <h2>Why the framework's own dimension is asserted to be unknown</h2>
 *
 * <p>A request refused before dispatch never reaches the handler mapping, so the framework has no route
 * to attribute it to and its {@code uri} dimension resolves to its unknown marker. That is inherent to
 * refusing early and is not a defect - but it is exactly why the application publishes a counter of its
 * own beside the framework's timer, and asserting it here is what keeps that justification honest rather
 * than asserted.
 *
 * <h2>What this context deliberately does and does not contain</h2>
 *
 * <p>It contains a real servlet container, the module's own filter registration, and the observation
 * auto-configuration whose filter position is the whole subject. It does not contain persistence,
 * security or the module's other configuration, because none of those participate in the claim and every
 * one of them would add a reason for this class to fail for an unrelated cause. The behind-authentication
 * half of the same fix is proved by the order assertion against the framework's default security-filter
 * order, which is the position Boot itself registers the chain at.
 *
 * <p>The body ceiling has no legacy antecedent: a 3270 field was fixed-width by construction and no caller
 * could send more than a screen.
 */
@SpringBootTest(classes = RequestBodyLimitObservabilityIT.ObservedWebTier.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.banner-mode=off",
            WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY + "=",
            WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY + "=1KB"})
@DisplayName("a refused oversized body is timed, counted and traced by the running chain")
class RequestBodyLimitObservabilityIT {

    /** The configured ceiling for this context, in bytes. */
    private static final int CEILING_BYTES = 1_024;

    /** The framework's server request timer. */
    private static final String SERVER_REQUEST_TIMER = "http.server.requests";

    /** The framework's marker for a request it could not attribute to a route. */
    private static final String UNKNOWN_ROUTE = "UNKNOWN";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("the 413 appears in the framework's request timer, which it could not do while the "
            + "filter ran ahead of the observation filter")
    void theRefusalIsTimedByTheFramework() {
        final ResponseEntity<String> answer = refuseAnOversizedBody();

        assertThat(answer.getStatusCode())
                .as("the refusal itself is unchanged; only its visibility is")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        final Timer timed = this.meterRegistry.find(SERVER_REQUEST_TIMER)
                .tag("status", String.valueOf(HttpStatus.PAYLOAD_TOO_LARGE.value()))
                .timer();
        assertThat(timed)
                .as("no series at all for status 413 is precisely the state this closes: the response "
                        + "existed and the deployment could not see it")
                .isNotNull();
        assertThat(timed.count()).isPositive();
        assertThat(timed.getId().getTag("outcome"))
                .as("classified as the client error it is, so an alert on client-error rate sees it")
                .isEqualTo("CLIENT_ERROR");
        assertThat(timed.getId().getTag("method")).isEqualTo(HttpMethod.POST.name());
        assertThat(timed.getId().getTag("uri"))
                .as("a request refused before dispatch has no route to attribute, which is inherent to "
                        + "refusing early and is why the application counts the refusal itself as well")
                .isEqualTo(UNKNOWN_ROUTE);
    }

    @Test
    @DisplayName("and in the application's own counter, which carries the reason the framework cannot know")
    void theRefusalIsCountedByTheApplication() {
        refuseAnOversizedBody();

        assertThat(this.meterRegistry.find(WebMvcConfig.REQUEST_REFUSED_METER_NAME)
                .tag(WebMvcConfig.TAG_REASON, WebMvcConfig.REASON_DECLARED_LENGTH)
                .tag(WebMvcConfig.TAG_METHOD, HttpMethod.POST.name())
                .counter())
                .as("the framework's timer says a client error happened; this says why, and it is the "
                        + "only thing that does")
                .isNotNull()
                .satisfies(counter -> assertThat(counter.count()).isPositive());
    }

    @Test
    @DisplayName("a body inside the ceiling still reaches its handler over the same running chain, so the "
            + "reordering did not simply refuse everything")
    void aBodyInsideTheCeilingStillReachesItsHandler() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        final ResponseEntity<String> answer = this.client.exchange(
                "http://localhost:" + this.port + "/api/observed-body-probe", HttpMethod.POST,
                new HttpEntity<>("{\"value\":\"" + "x".repeat(16) + "\"}", headers), String.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody()).isEqualTo("accepted");
    }

    /**
     * @return the answer to one oversized POST against the probe route
     */
    private ResponseEntity<String> refuseAnOversizedBody() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        final String oversized = "{\"value\":\"" + "x".repeat(CEILING_BYTES * 2) + "\"}";
        return this.client.exchange("http://localhost:" + this.port + "/api/observed-body-probe",
                HttpMethod.POST, new HttpEntity<>(oversized, headers), String.class);
    }

    /**
     * The smallest context in which the claim is meaningful: a real container, the module's filter
     * registration, and the observation auto-configuration whose position is the subject.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        // Registers the placeholder resolver, without which the module's own @Value defaults arrive as
        // literal placeholder text and the configurer refuses them at construction.
        PropertyPlaceholderAutoConfiguration.class,
        ServletWebServerFactoryAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        ObservationAutoConfiguration.class,
        WebMvcObservationAutoConfiguration.class})
    @Import(WebMvcConfig.class)
    static class ObservedWebTier {

        /**
         * @return the registry both the framework's timer and the module's counter are published on
         */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * @return the one route this context serves
         */
        @Bean
        ObservedBodyProbeController observedBodyProbeController() {
            return new ObservedBodyProbeController();
        }
    }

    /** Accepts a bounded body, so the in-ceiling path has somewhere to arrive. */
    @RestController
    static class ObservedBodyProbeController {

        /**
         * @param  body the bounded request body
         * @return a fixed acknowledgement
         */
        @PostMapping(path = "/api/observed-body-probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        String accept(@RequestBody final ObservedBodyProbe body) {
            return "accepted";
        }
    }

    /** The one field the probe route binds. */
    record ObservedBodyProbe(String value) {
    }
}
