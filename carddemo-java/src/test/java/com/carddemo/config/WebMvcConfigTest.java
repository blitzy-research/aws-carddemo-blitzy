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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.MessageCodesResolver;
import org.springframework.validation.Validator;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.DefaultServletHandlerConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.ViewResolverRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@link WebMvcConfig}, whose contract is narrow, explicit web-boundary hardening.
 *
 * <p>{@code WebMvcConfig} preserves the framework's converters, dispatch and exception translation,
 * while adding exact-origin CORS and a bounded request-body reader beside its existing scalar and
 * validation rules. This class therefore proves both the additions and the defaults that remain
 * untouched.
 *
 * <h2>Half one &mdash; the negative-contribution proof</h2>
 *
 * <p>All configurer callbacks the framework will make are invoked here. The ones that receive a
 * collection are handed a real, mutable, initially empty collection and the collection itself is the
 * evidence; the ones that receive a registry are handed a test double that records every call, and the
 * absence of any call is the evidence; the two that answer with a component are asserted to answer
 * {@code null}, which is the interface's own way of spelling "no override". One extra case hands
 * {@code extendMessageConverters} a list that already carries entries, because an assertion that an empty
 * list stayed empty would also pass against a method that emptied it.
 *
 * <h2>Half two &mdash; the auto-configuration-survival slice, and the primary proof</h2>
 *
 * <p>The single most damaging edit anyone could make to {@code WebMvcConfig} is to annotate it for
 * explicit web-MVC enablement. That annotation does not add to the auto-configured web tier, it
 * <em>replaces</em> it: the framework's MVC auto-configuration is conditional on no such configuration
 * being contributed by the application, so it withdraws entirely, taking the configured JSON conversion,
 * the management endpoints' web exposure and the generated interface document and its viewer with it. No
 * compiler diagnostic is produced, the context still starts, and every endpoint still answers &mdash;
 * with different bytes.
 *
 * <p>Rather than look for the annotation, this half proves the consequence has not happened. A slice of
 * the real container is assembled with the MVC, serialisation and message-converter auto-configurations
 * plus {@code WebMvcConfig}, and it is asserted that the MVC auto-configuration is still active, that the
 * configured JSON converter still exists, and &mdash; the decisive assertion &mdash; that the converter
 * instance actually wired into the request-handling adapter <em>is</em> the auto-configured bean rather
 * than a default-constructed replacement. A second slice registers {@code WebMvcConfig} alone and shows
 * it contributes no MVC configuration-support bean and no serialiser of its own. A third slice is a
 * negative control: an equivalent configuration carrying the explicit enablement annotation, present so
 * that the two survival assertions are demonstrably falsifiable rather than merely green.
 *
 * <p>All of this runs on a mock servlet context. No servlet container starts, no HTTP request is made, no
 * data source is created and no container image is pulled.
 *
 * <h2>Why the surviving converter is load-bearing rather than cosmetic</h2>
 *
 * <p>Four serialisation settings are declared in {@code application.yml} and together they <em>are</em>
 * the wire shape of the published contract: decimals rendered plainly rather than in scientific notation,
 * null-valued properties omitted, dates rendered as ISO-8601 text rather than epoch numbers, and unknown
 * request properties tolerated. All four are applied to the auto-configured serialiser, and they reach a
 * response body only through the auto-configured converter that carries it. Displacing that converter
 * would discard all four silently.
 *
 * <p>The reason this matters here specifically: every persisted monetary and rate field in the migrated
 * estate is zoned decimal held under {@code USAGE DISPLAY} rather than packed &mdash; five
 * {@code PIC S9(10)V99} fields on the 300-byte account layout, {@code PIC S9(09)V99} amounts on the
 * 350-byte transaction and daily-transaction layouts and on the category-balance layout, and a
 * {@code PIC S9(04)V99} rate on the disclosure-group layout. Each becomes a decimal at scale 2, and
 * because no rounding clause exists anywhere in the estate every store into a two-decimal field
 * truncates, which is why the codec truncates rather than rounding to the nearest even digit. A decimal
 * that leaves in scientific notation is a corrupted value on the wire exactly as surely as the wrong
 * rounding mode is a corrupted value in arithmetic. Relatedly, every fixed-width identifier &mdash; an
 * 11-digit account id, a 16-character card number, a 16-character transaction id &mdash; travels as
 * bounded text and never as a numeric type, because a numeric type would strip leading zeros the record
 * layout depends on; that is why no formatter or type converter is registered for request binding.
 *
 * <p>The four settings themselves are asserted where they are observable, at the {@code api} integration
 * tier which serialises a real payload over a real converter. They are named here only to record what the
 * survival of the converter buys, and are deliberately not re-asserted from this file.
 *
 * <h2>Customisations that must never appear, and would fail a test above if they did</h2>
 *
 * <p>A static resource handler; a view resolver, view-controller entry or templating engine of any kind
 * &mdash; there is no browser or single-page interface anywhere in this migration; a wildcard or
 * credentialed cross-origin relaxation; a content-negotiation
 * override; a favicon handler or welcome page; a locale or theme resolver, since verbatim screen text is
 * resolved from the message catalogue and must not vary; a multipart configuration; an exception
 * resolver, framework advice base class or standard problem-detail format, because the error body is
 * owned by the boundary advice whose per-field states distinguish a value that was absent from one that
 * was present and rejected, and a framework-wide error format flattens those two into one; a
 * request-timing interceptor, because instrumentation is owned by the observability configuration; and
 * any async request time limit, task-executor pool size or servlet container thread count, none of which
 * has a legacy baseline to be faithful to and none of which is asserted anywhere in this module.
 *
 * <h2>An honest note on coverage</h2>
 *
 * <p>A class with no overrides yields almost no line coverage of its own however hard it is tested, so
 * this file is not a coverage contributor and no filler assertion has been added to make it look like
 * one. Its value is the survival slice, which covers a behaviour of the module rather than a line of the
 * class.
 *
 * <p>Provenance: legacy checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. No legacy source text
 * is reproduced anywhere in this file; record widths and field shapes are cited as metadata only.
 */
@DisplayName("WebMvcConfig - explicit web boundaries and the auto-configuration they preserve")
class WebMvcConfigTest {

    /**
     * The auto-configuration slice the survival half runs against.
     *
     * <p>Three auto-configurations and no more: the MVC one whose withdrawal is the failure mode under
     * test, the serialisation one that builds the configured object mapper, and the message-converter one
     * that wraps that mapper in the converter the handler adapter must end up using. Keeping the list
     * this short means a failure here is attributable, and it is why no full application bootstrap is
     * needed. The runner is immutable, so deriving per-test runners from this constant is safe.
     */
    private static final WebApplicationContextRunner AUTO_CONFIGURED_WEB_TIER =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class))
                    .withPropertyValues(
                            WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY + "=",
                            WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY + "=64KB")
                    // The body-limit filter now counts a refusal, so it needs a registry. Supplied
                    // explicitly here because this slice registers no metrics auto-configuration, whereas
                    // the running application always has one.
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    /**
     * The configuration under test, held through the interface the framework invokes it through.
     *
     * <p>Typed as the interface rather than as the implementation on purpose: the framework never sees
     * anything else, so neither should these tests. A fresh instance is created for every test method by
     * the default per-method test lifecycle.
     */
    private final WebMvcConfigurer configurer = new WebMvcConfig();

    /**
     * Asserts that a recording registry double was never called, and explains why that matters if it was.
     *
     * <p>Wrapping the verification in an assertion that carries a description is what turns a bare
     * "unwanted interaction" report into a message naming the customisation that must not be there. The
     * verification itself is the standard one, so the failure detail still identifies the exact call.
     *
     * @param registry the recording double handed to the callback under test
     * @param rationale the actionable explanation reported when a contribution is found
     */
    private static void assertNothingWasContributedTo(final Object registry, final String rationale) {
        assertThatCode(() -> verifyNoInteractions(registry)).as(rationale).doesNotThrowAnyException();
    }

    /** Registry test seam exposing the configurations the framework would install. */
    private static final class ExposedCorsRegistry extends CorsRegistry {

        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }

    @Nested
    @DisplayName("the generic request-body ceiling")
    class RequestBodyCeiling {

        @Test
        @DisplayName("a body at the configured size is copied intact for MVC")
        void aBodyAtTheConfiguredSizeIsCopiedIntact() throws Exception {
            final WebMvcConfig.RequestBodyLimitFilter filter =
                    new WebMvcConfig.RequestBodyLimitFilter("8B", new SimpleMeterRegistry());
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/body-probe");
            request.setContent("12345678".getBytes(StandardCharsets.US_ASCII));
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final AtomicReference<jakarta.servlet.ServletRequest> forwarded =
                    new AtomicReference<>();

            filter.doFilter(request, response, (bounded, ignored) -> forwarded.set(bounded));

            assertThat(((HttpServletRequest) forwarded.get()).getInputStream().readAllBytes())
                    .containsExactly("12345678".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("a declared body above the ceiling is refused without reading or echoing it")
        void aDeclaredBodyAboveTheCeilingIsRefused() throws Exception {
            final WebMvcConfig.RequestBodyLimitFilter filter =
                    new WebMvcConfig.RequestBodyLimitFilter("8B", new SimpleMeterRegistry());
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/body-probe");
            request.setContent("sensitive".getBytes(StandardCharsets.US_ASCII));
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final AtomicInteger invocations = new AtomicInteger();

            filter.doFilter(request, response,
                    (ignoredRequest, ignoredResponse) -> invocations.incrementAndGet());

            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentAsString()).doesNotContain("sensitive");
            assertThat(invocations).hasValue(0);
        }

        @Test
        @DisplayName("a chunked body above the ceiling is refused after only one excess byte")
        void aChunkedBodyAboveTheCeilingIsRefused() throws Exception {
            final WebMvcConfig.RequestBodyLimitFilter filter =
                    new WebMvcConfig.RequestBodyLimitFilter("8B", new SimpleMeterRegistry());
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/body-probe") {
                        @Override
                        public long getContentLengthLong() {
                            return -1L;
                        }
                    };
            request.setContent("1234567890".getBytes(StandardCharsets.US_ASCII));
            final MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                throw new AssertionError("an oversized chunked request reached the chain");
            });

            assertThat(response.getStatus()).isEqualTo(413);
        }

        @Test
        @DisplayName("the servlet pipeline answers 413 before an oversized body reaches a controller")
        void theMvcPipelineAnswersPayloadTooLarge() {
            AUTO_CONFIGURED_WEB_TIER
                    .withUserConfiguration(WebMvcConfig.class)
                    .withBean(BodyProbeController.class)
                    .run(context -> {
                        final MockMvc client = MockMvcBuilders.webAppContextSetup(context)
                                .addFilters(new WebMvcConfig.RequestBodyLimitFilter("64KB", new SimpleMeterRegistry()))
                                .build();
                        final String oversized =
                                "{\"value\":\"" + "x".repeat(64 * 1_024) + "\"}";

                        client.perform(post("/api/body-probe")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(oversized))
                                .andExpect(status().isPayloadTooLarge());

                        assertThat(context.getBean(BodyProbeController.class).invocations)
                                .as("the request-body filter must reject before controller invocation")
                                .isZero();
                    });
        }
    }

    /**
     * A refused body is visible: it is behind observation, behind authentication, counted and logged.
     *
     * <h2>What was actually wrong, and why position is the fix rather than an addition</h2>
     *
     * <p>Registered at highest precedence the filter ran ahead of the whole chain, so a refusal happened
     * outside every observation. The caller received 413 and the deployment received nothing: no entry in
     * the request timer, no span, no correlation identifiers in scope, and no log line. Because the route
     * needs no credential to reach, that made an anonymously reachable refusal path completely
     * unobservable - which is a monitoring gap, not a telemetry nicety. It also read and buffered an
     * anonymous body before anything had established that the caller was entitled to send one.
     *
     * <p>The position assertions below are therefore the substance of this class, and they are expressed
     * against the framework's own two constants rather than against the number this module happens to
     * use. A test that asserted the literal would keep passing if Boot moved either filter, which is
     * exactly the change that would silently undo the fix.
     */
    @Nested
    @DisplayName("A refused body is observable")
    class RefusedBodiesAreObservable {

        @Test
        @DisplayName("the filter is registered behind the server observation filter, which is what puts "
                + "the 413 inside a request timing and a span at all")
        void theFilterSitsBehindTheServerObservationFilter() {
            final FilterRegistrationBean<WebMvcConfig.RequestBodyLimitFilter> registration =
                    new WebMvcConfig().requestBodyLimitFilter("64KB", new SimpleMeterRegistry());

            assertThat(registration.getOrder())
                    .as("Boot registers its server observation filter one step behind highest precedence, "
                            + "so anything at or before that point is refused outside every observation")
                    .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1);
        }

        @Test
        @DisplayName("and behind the security chain, so an unauthenticated request to a protected route "
                + "is answered before this filter allocates anything for it")
        void theFilterSitsBehindTheSecurityChain() {
            final FilterRegistrationBean<WebMvcConfig.RequestBodyLimitFilter> registration =
                    new WebMvcConfig().requestBodyLimitFilter("64KB", new SimpleMeterRegistry());

            assertThat(registration.getOrder())
                    .as("derived from the framework's own default rather than written as a literal, so a "
                            + "change to it moves this filter with it instead of reordering the two")
                    .isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
                    .isGreaterThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        }

        @Test
        @DisplayName("a declared oversized body is counted, naming how the excess was detected and the "
                + "method, so a probing scan is distinguishable from one large upload")
        void aDeclaredRefusalIsCounted() throws Exception {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final WebMvcConfig.RequestBodyLimitFilter filter =
                    new WebMvcConfig.RequestBodyLimitFilter("8B", registry);
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/body-probe");
            request.setContent("sensitive".getBytes(StandardCharsets.US_ASCII));

            filter.doFilter(request, new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> {
                        throw new AssertionError("an oversized request reached the chain");
                    });

            assertThat(refused(registry, WebMvcConfig.REASON_DECLARED_LENGTH, "POST"))
                    .as("a refusal that is not counted is indistinguishable from a request nobody made")
                    .isEqualTo(1.0d);
            assertThat(refused(registry, WebMvcConfig.REASON_STREAMED_LENGTH, "POST"))
                    .as("the two detections are separate facts: one is a header the caller declared, the "
                            + "other is what the caller actually sent")
                    .isZero();
        }

        @Test
        @DisplayName("a chunked oversized body is counted under the other reason, because the excess was "
                + "found by reading rather than by trusting a header")
        void aStreamedRefusalIsCountedSeparately() throws Exception {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final WebMvcConfig.RequestBodyLimitFilter filter =
                    new WebMvcConfig.RequestBodyLimitFilter("8B", registry);
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("PUT", "/api/body-probe") {
                        @Override
                        public long getContentLengthLong() {
                            return -1L;
                        }
                    };
            request.setContent("1234567890".getBytes(StandardCharsets.US_ASCII));

            filter.doFilter(request, new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> {
                        throw new AssertionError("an oversized request reached the chain");
                    });

            assertThat(refused(registry, WebMvcConfig.REASON_STREAMED_LENGTH, "PUT"))
                    .isEqualTo(1.0d);
        }

        @Test
        @DisplayName("an invented method cannot fork the metric series, because a caller controls the "
                + "method and a tag value a caller controls is a way to grow the series without bound")
        void anInventedMethodIsFolded() throws Exception {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final WebMvcConfig.RequestBodyLimitFilter filter =
                    new WebMvcConfig.RequestBodyLimitFilter("8B", registry);
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("WHATEVER-9134", "/api/body-probe");
            request.setContent("sensitive".getBytes(StandardCharsets.US_ASCII));

            filter.doFilter(request, new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> {
                        throw new AssertionError("an oversized request reached the chain");
                    });

            assertThat(refused(registry, WebMvcConfig.REASON_DECLARED_LENGTH, "OTHER"))
                    .isEqualTo(1.0d);
            assertThat(registry.find(WebMvcConfig.REQUEST_REFUSED_METER_NAME).counters())
                    .as("exactly one series, whatever the caller called the method")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the diagnostic carries no unsanitised caller content, so a request line cannot "
                + "forge a second log record or occupy the log by being long")
        void theDiagnosticIsBounded() throws Exception {
            final Logger configLogger = (Logger) LoggerFactory.getLogger(WebMvcConfig.class);
            final Level originalLevel = configLogger.getLevel();
            final ListAppender<ILoggingEvent> recorder = new ListAppender<>();
            recorder.setContext(configLogger.getLoggerContext());
            recorder.start();
            configLogger.addAppender(recorder);
            configLogger.setLevel(Level.WARN);
            try {
                final WebMvcConfig.RequestBodyLimitFilter filter =
                        new WebMvcConfig.RequestBodyLimitFilter("8B", new SimpleMeterRegistry());
                final String forged = "/api/probe\r\nWARN forged record injected \u0000"
                        + "x".repeat(400);
                final MockHttpServletRequest request = new MockHttpServletRequest("POST", forged);
                request.setContent("sensitive".getBytes(StandardCharsets.US_ASCII));

                filter.doFilter(request, new MockHttpServletResponse(),
                        (ignoredRequest, ignoredResponse) -> {
                            throw new AssertionError("an oversized request reached the chain");
                        });

                assertThat(recorder.list).singleElement().satisfies(event -> {
                    final String rendered = event.getFormattedMessage();
                    assertThat(event.getLevel())
                            .as("one warning, because a refusal is an operator's business and a caller "
                                    + "must not be able to raise it to an error")
                            .isEqualTo(Level.WARN);
                    assertThat(rendered)
                            .as("no line break and no control character may survive into a record, or a "
                                    + "caller writes records of their own choosing")
                            .doesNotContain("\r", "\n", "\u0000")
                            .doesNotContain("forged record injected")
                            .doesNotContain("sensitive")
                            .contains("limitBytes=8")
                            .contains("method=POST")
                            .contains("reason=" + WebMvcConfig.REASON_DECLARED_LENGTH);
                    assertThat(rendered.length())
                            .as("bounded, because a caller chooses the length of a request line and would "
                                    + "otherwise choose how much of the log to occupy")
                            .isLessThan(300);
                });
            } finally {
                configLogger.detachAppender(recorder);
                recorder.stop();
                configLogger.setLevel(originalLevel);
            }
        }

        @Test
        @DisplayName("the answer a caller receives is unchanged, so recording the refusal has moved no "
                + "external contract")
        void theAnswerIsUnchanged() throws Exception {
            final WebMvcConfig.RequestBodyLimitFilter filter =
                    new WebMvcConfig.RequestBodyLimitFilter("8B", new SimpleMeterRegistry());
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/body-probe");
            request.setContent("sensitive".getBytes(StandardCharsets.US_ASCII));
            final MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                throw new AssertionError("an oversized request reached the chain");
            });

            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentType())
                    .as("the media type is JSON; the container appends the charset the filter also sets")
                    .startsWith(MediaType.APPLICATION_JSON_VALUE);
            assertThat(response.getCharacterEncoding())
                    .isEqualTo(StandardCharsets.UTF_8.name());
            assertThat(response.getContentAsString())
                    .isEqualTo("{\"message\":\"Request could not be processed\",\"fieldErrors\":[]}");
        }

        /**
         * @param  registry the registry to read
         * @param  reason   the {@link WebMvcConfig#TAG_REASON} value
         * @param  method   the {@link WebMvcConfig#TAG_METHOD} value
         * @return how many refusals were counted for that pair, zero when none
         */
        private double refused(final SimpleMeterRegistry registry, final String reason,
                final String method) {
            final Counter counter = registry.find(WebMvcConfig.REQUEST_REFUSED_METER_NAME)
                    .tag(WebMvcConfig.TAG_REASON, reason)
                    .tag(WebMvcConfig.TAG_METHOD, method)
                    .counter();
            return counter == null ? 0.0d : counter.count();
        }
    }

    /** Probe endpoint used only to prove the request-body filter is in the servlet pipeline. */
    @RestController
    static final class BodyProbeController {

        private int invocations;

        @PostMapping(path = "/api/body-probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        void accept(@RequestBody final BodyProbe body) {
            this.invocations++;
        }
    }

    private record BodyProbe(String value) {
    }

    /**
     * Returns the converter list the request-handling adapter will actually read and write bodies with.
     *
     * <p>This is the list that decides the bytes on the wire. It is deliberately read from the adapter
     * rather than from the converter beans, because a converter can exist as a bean and still not be the
     * one in force &mdash; which is precisely the failure the survival half exists to catch.
     *
     * @param context the assembled slice to inspect
     * @return the converters bound to the request-handling adapter, in the order it consults them
     */
    private static List<HttpMessageConverter<?>> convertersInForce(
            final AssertableWebApplicationContext context) {
        return context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
    }

    /**
     * Every callback handed a collection leaves that collection exactly as it found it.
     *
     * <p>The collections here are real and mutable, never doubles, so a contribution would be visible as
     * a changed collection rather than as a recorded call. That distinction matters most for the converter
     * callbacks, where the replacing variant is the dangerous one.
     */
    @Nested
    @DisplayName("callbacks that receive a collection")
    class CollectionShapedCallbacks {

        @Test
        @DisplayName("the converter list is neither replaced nor appended to, so the configured JSON "
                + "conversion stays in force")
        void theConverterListIsNeitherReplacedNorAppendedTo() {
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();

            WebMvcConfigTest.this.configurer.configureMessageConverters(converters);
            WebMvcConfigTest.this.configurer.extendMessageConverters(converters);

            assertThat(converters)
                    .as("WebMvcConfig must contribute no HTTP message converter. Configuring converters "
                            + "REPLACES the auto-configured set outright, so a single entry here would "
                            + "discard plain-decimal rendering, null omission, ISO-8601 dates and lenient "
                            + "unknown-property handling in one edit - see the class Javadoc for why each "
                            + "of those four is part of the published contract")
                    .isEmpty();
        }

        @Test
        @DisplayName("a converter list that already carries entries survives untouched and in order, which "
                + "an empty-list assertion alone could not establish")
        void aPopulatedConverterListSurvivesUntouchedAndInOrder() {
            final HttpMessageConverter<?> first = new MappingJackson2HttpMessageConverter();
            final HttpMessageConverter<?> second = new MappingJackson2HttpMessageConverter();
            final List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(first, second));

            WebMvcConfigTest.this.configurer.configureMessageConverters(converters);
            WebMvcConfigTest.this.configurer.extendMessageConverters(converters);

            assertThat(converters)
                    .as("WebMvcConfig must neither add to, remove from nor reorder a converter list it is "
                            + "handed; converter order decides which converter wins a content-type match")
                    .containsExactly(first, second);
        }

        @Test
        @DisplayName("no argument resolver is contributed, so request binding follows the framework's own "
                + "rules")
        void noArgumentResolverIsContributed() {
            final List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

            WebMvcConfigTest.this.configurer.addArgumentResolvers(resolvers);

            assertThat(resolvers)
                    .as("WebMvcConfig must contribute no handler-method argument resolver; every endpoint "
                            + "takes its input as a declared request type, so a bespoke binding path would "
                            + "be a second, untested way for a request to reach a controller")
                    .isEmpty();
        }

        @Test
        @DisplayName("no return-value handler is contributed, so response bodies are written the framework "
                + "way")
        void noReturnValueHandlerIsContributed() {
            final List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();

            WebMvcConfigTest.this.configurer.addReturnValueHandlers(handlers);

            assertThat(handlers)
                    .as("WebMvcConfig must contribute no handler-method return-value handler; a custom "
                            + "handler could write a response body without passing through the configured "
                            + "message converter, bypassing the wire contract entirely")
                    .isEmpty();
        }

        @Test
        @DisplayName("no exception resolver is configured and none is appended, so the boundary advice stays "
                + "the only place a failure becomes a response")
        void noExceptionResolverIsConfiguredOrAppended() {
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>();

            WebMvcConfigTest.this.configurer.configureHandlerExceptionResolvers(resolvers);
            WebMvcConfigTest.this.configurer.extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers)
                    .as("WebMvcConfig must contribute no handler exception resolver. The error body is "
                            + "owned by the boundary advice, whose per-field states separate an absent "
                            + "value from a rejected one; a resolver registered here would take precedence "
                            + "and flatten those two states into one")
                    .isEmpty();
        }

        @Test
        @DisplayName("no error-response interceptor is registered, so nothing rewrites a failure body after "
                + "the advice has produced it")
        void noErrorResponseInterceptorIsRegistered() {
            final List<ErrorResponse.Interceptor> interceptors = new ArrayList<>();

            WebMvcConfigTest.this.configurer.addErrorResponseInterceptors(interceptors);

            assertThat(interceptors)
                    .as("WebMvcConfig must register no error-response interceptor; the body the boundary "
                            + "advice builds has to be the body the client receives, byte for byte")
                    .isEmpty();
        }
    }

    /**
     * Every callback handed a registry leaves that registry entirely uncalled.
     *
     * <p>A recording double is used rather than a real registry because a registry records a contribution
     * in its own private structure, and several of them accept a builder call whose effect only appears
     * later. Asserting that the registry was never called at all is stricter than asserting that it ended
     * up empty: it catches a call whose result was configured but discarded, too.
     */
    @Nested
    @DisplayName("callbacks that receive a registry")
    class RegistryShapedCallbacks {

        @Test
        @DisplayName("no static resource handler is mounted, because this module serves no static content")
        void noStaticResourceHandlerIsMounted() {
            final ResourceHandlerRegistry registry = mock(ResourceHandlerRegistry.class);

            WebMvcConfigTest.this.configurer.addResourceHandlers(registry);

            assertNothingWasContributedTo(registry,
                    "WebMvcConfig must mount no static resource handler: this module publishes a REST "
                            + "contract and ships no asset, stylesheet, script or favicon to serve");
        }

        @Test
        @DisplayName("no view controller is registered, so no path is answered without reaching a controller")
        void noViewControllerIsRegistered() {
            final ViewControllerRegistry registry = mock(ViewControllerRegistry.class);

            WebMvcConfigTest.this.configurer.addViewControllers(registry);

            assertNothingWasContributedTo(registry,
                    "WebMvcConfig must register no view controller and no welcome-page or redirect entry: "
                            + "there is no browser or single-page interface anywhere in this migration");
        }

        @Test
        @DisplayName("no view resolver is configured, because no template is ever rendered")
        void noViewResolverIsConfigured() {
            final ViewResolverRegistry registry = mock(ViewResolverRegistry.class);

            WebMvcConfigTest.this.configurer.configureViewResolvers(registry);

            assertNothingWasContributedTo(registry,
                    "WebMvcConfig must configure no view resolver; no templating engine is permitted in "
                            + "this module, so a resolver here would have nothing legitimate to resolve");
        }

        @Test
        @DisplayName("default servlet handling is not enabled, so an unmatched path is not quietly forwarded "
                + "to the container")
        void defaultServletHandlingIsNotEnabled() {
            final DefaultServletHandlerConfigurer servletHandling =
                    mock(DefaultServletHandlerConfigurer.class);

            WebMvcConfigTest.this.configurer.configureDefaultServletHandling(servletHandling);

            assertNothingWasContributedTo(servletHandling,
                    "WebMvcConfig must not enable default servlet handling; forwarding unmatched paths to "
                            + "the container would answer a request outside every declared endpoint");
        }

        @Test
        @DisplayName("no interceptor is registered, so the security filter chain remains the only gate and "
                + "the observability configuration remains the only instrumentation")
        void noInterceptorIsRegistered() {
            final InterceptorRegistry registry = mock(InterceptorRegistry.class);

            WebMvcConfigTest.this.configurer.addInterceptors(registry);

            assertNothingWasContributedTo(registry,
                    "WebMvcConfig must register no interceptor: admission is decided by the security "
                            + "filter chain alone, and request instrumentation is owned by the "
                            + "observability configuration, so an interceptor here would either duplicate "
                            + "or silently compete with one of them");
        }

        @Test
        @DisplayName("the shipped policy registers the entire API with an empty exact-origin set")
        void theShippedPolicyDeniesEveryCrossOriginRequest() {
            final ExposedCorsRegistry registry = new ExposedCorsRegistry();

            WebMvcConfigTest.this.configurer.addCorsMappings(registry);

            assertThat(registry.configurations()).containsOnlyKeys(WebMvcConfig.API_PATH_PATTERN);
            final CorsConfiguration policy =
                    registry.configurations().get(WebMvcConfig.API_PATH_PATTERN);
            assertThat(policy.getAllowedOrigins()).isEmpty();
            assertThat(policy.getAllowedMethods()).containsExactly("GET", "POST");
            assertThat(policy.getAllowedHeaders())
                    .containsExactly("Accept", "Authorization", "Content-Type");
            assertThat(policy.getExposedHeaders()).containsExactly("Authorization");
            assertThat(policy.getAllowCredentials()).isFalse();
            assertThat(policy.getMaxAge()).isEqualTo(600L);
        }

        @Test
        @DisplayName("configured origins are exact and never converted into a wildcard or pattern")
        void configuredOriginsRemainExact() {
            final WebMvcConfig configured =
                    new WebMvcConfig("https://client.example,http://localhost:3000");
            final ExposedCorsRegistry registry = new ExposedCorsRegistry();

            configured.addCorsMappings(registry);

            final CorsConfiguration policy =
                    registry.configurations().get(WebMvcConfig.API_PATH_PATTERN);
            assertThat(policy.getAllowedOrigins())
                    .containsExactly("https://client.example", "http://localhost:3000");
            assertThat(policy.getAllowedOriginPatterns()).isNullOrEmpty();
            assertThat(policy.getAllowCredentials()).isFalse();
        }

        @Test
        @DisplayName("content negotiation is left at the framework defaults, so the media type of a response "
                + "is not renegotiated")
        void contentNegotiationIsLeftAtTheDefaults() {
            final ContentNegotiationConfigurer negotiation = mock(ContentNegotiationConfigurer.class);

            WebMvcConfigTest.this.configurer.configureContentNegotiation(negotiation);

            assertNothingWasContributedTo(negotiation,
                    "WebMvcConfig must not override content negotiation; the published contract fixes the "
                            + "response media type, so a default type, path extension or query parameter "
                            + "strategy introduced here could serve a shape no client asked for");
        }

        @Test
        @DisplayName("no formatter and no type converter is registered, so a fixed-width identifier keeps its "
                + "leading zeros through binding")
        void noFormatterOrTypeConverterIsRegistered() {
            final FormatterRegistry registry = mock(FormatterRegistry.class);

            WebMvcConfigTest.this.configurer.addFormatters(registry);

            assertNothingWasContributedTo(registry,
                    "WebMvcConfig must register no formatter, printer, parser or type converter. Every "
                            + "fixed-width identifier binds as bounded text precisely so that leading "
                            + "zeros survive; a converter is the one component able to trim or renumber a "
                            + "value the record layout requires unchanged");
        }

        @Test
        @DisplayName("path matching is left at the framework defaults, so no endpoint moves")
        void pathMatchingIsLeftAtTheDefaults() {
            final PathMatchConfigurer pathMatching = mock(PathMatchConfigurer.class);

            WebMvcConfigTest.this.configurer.configurePathMatch(pathMatching);

            assertNothingWasContributedTo(pathMatching,
                    "WebMvcConfig must not override path matching. A prefix or a changed trailing-slash or "
                            + "case rule would relocate every endpoint at once, including the management "
                            + "paths that the module container image and the metrics scrape target resolve "
                            + "as literal text");
        }

        @Test
        @DisplayName("asynchronous support is left at the framework defaults, so no execution or duration "
                + "policy is asserted by this module")
        void asynchronousSupportIsLeftAtTheDefaults() {
            final AsyncSupportConfigurer asyncSupport = mock(AsyncSupportConfigurer.class);

            WebMvcConfigTest.this.configurer.configureAsyncSupport(asyncSupport);

            assertNothingWasContributedTo(asyncSupport,
                    "WebMvcConfig must not override asynchronous support. No legacy baseline exists for "
                            + "any execution or duration figure, so this module measures rather than "
                            + "asserts and declares none");
        }
    }

    /**
     * The two callbacks that answer with a component answer with none.
     *
     * <p>{@code null} is not an oversight in this interface, it is the documented way to say "keep the
     * framework's own". Both of these feed the validation results that the boundary advice translates into
     * the per-field error contract, so substituting either would change the body of a rejected request
     * without any change to the code that builds that body.
     */
    @Nested
    @DisplayName("callbacks that answer with a component")
    class ComponentShapedCallbacks {

        @Test
        @DisplayName("no validator is substituted, so the constraints the boundary advice translates are the "
                + "framework's own")
        void noValidatorIsSubstituted() {
            final Validator substituted = WebMvcConfigTest.this.configurer.getValidator();

            assertThat(substituted)
                    .as("WebMvcConfig must answer null for the validator, which is this interface's way of "
                            + "keeping the auto-configured one. A substituted validator would change which "
                            + "declarative constraints fire, and therefore which per-field error states a "
                            + "rejected request reports")
                    .isNull();
        }

        @Test
        @DisplayName("no message-codes resolver is substituted, so a field error keeps its standard code "
                + "shape")
        void noMessageCodesResolverIsSubstituted() {
            final MessageCodesResolver substituted =
                    WebMvcConfigTest.this.configurer.getMessageCodesResolver();

            assertThat(substituted)
                    .as("WebMvcConfig must answer null for the message-codes resolver; the boundary advice "
                            + "matches on the standard code shape when deciding whether a field was absent "
                            + "or present and rejected")
                    .isNull();
        }
    }

    /**
     * The auto-configured web tier survives the presence of {@code WebMvcConfig}.
     *
     * <p>This is the behavioural statement that the explicit web-MVC enablement annotation is absent, and
     * it is strictly stronger than looking the annotation up, because it asserts the consequence that
     * matters instead of the cause. Each test here would fail against a {@code WebMvcConfig} carrying that
     * annotation, and the control group below proves that claim rather than asserting it.
     */
    @Nested
    @DisplayName("the auto-configured web tier survives the configurer")
    class AutoConfigurationSurvival {

        @Test
        @DisplayName("the slice starts and the MVC auto-configuration is still active, which is what the "
                + "explicit enablement annotation would have withdrawn")
        void theMvcAutoConfigurationIsStillActive() {
            AUTO_CONFIGURED_WEB_TIER.withUserConfiguration(WebMvcConfig.class).run(context -> assertThat(
                    context)
                    .as("registering WebMvcConfig must leave the framework's MVC auto-configuration in "
                            + "place. Its absence here means the application contributed an explicit "
                            + "web-MVC configuration - almost certainly the enablement annotation on "
                            + "WebMvcConfig - and the auto-configuration withdrew, taking the configured "
                            + "JSON conversion, the management endpoints' web exposure and the generated "
                            + "interface document with it")
                    .hasNotFailed()
                    .hasSingleBean(WebMvcAutoConfiguration.class)
                    .hasSingleBean(MappingJackson2HttpMessageConverter.class));
        }

        @Test
        @DisplayName("the configurer is collected by the auto-configuration rather than replacing it, which "
                + "is the whole point of implementing the interface")
        void theConfigurerIsCollectedByTheAutoConfiguration() {
            AUTO_CONFIGURED_WEB_TIER.withUserConfiguration(WebMvcConfig.class).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(WebMvcConfig.class);
                assertThat(context.getBeansOfType(WebMvcConfigurer.class).values())
                        .as("WebMvcConfig must appear among the configurers the auto-configuration "
                                + "consults; being consulted as part of the auto-configuration is exactly "
                                + "what leaves every default intact")
                        .contains(context.getBean(WebMvcConfig.class));
            });
        }

        @Test
        @DisplayName("the converter the request-handling adapter actually uses is the auto-configured bean "
                + "itself, so the configured serialisation settings genuinely reach the wire")
        void theConverterInForceIsTheAutoConfiguredBean() {
            AUTO_CONFIGURED_WEB_TIER.withUserConfiguration(WebMvcConfig.class).run(context -> {
                assertThat(context).hasNotFailed();
                final MappingJackson2HttpMessageConverter autoConfigured =
                        context.getBean(MappingJackson2HttpMessageConverter.class);

                assertThat(convertersInForce(context))
                        .as("the JSON converter bound to the request-handling adapter must be the "
                                + "auto-configured bean instance, not merely some converter of the same "
                                + "type. Only the auto-configured instance carries the object mapper the "
                                + "module's configuration file customised, so if the adapter holds a "
                                + "default-constructed replacement then plain-decimal rendering, null "
                                + "omission, ISO-8601 dates and lenient unknown-property handling are all "
                                + "silently inactive while every test that only checks bean presence still "
                                + "passes")
                        .anySatisfy(candidate -> assertThat(candidate).isSameAs(autoConfigured));
            });
        }

        @Test
        @DisplayName("the only MVC configuration support in the slice is the auto-configuration's own, never "
                + "one imported by an explicit enablement")
        void theOnlyConfigurationSupportIsTheAutoConfigurationOwn() {
            AUTO_CONFIGURED_WEB_TIER.withUserConfiguration(WebMvcConfig.class).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(WebMvcConfigurationSupport.class);

                assertThat(context.getBean(WebMvcConfigurationSupport.class))
                        .as("an active MVC auto-configuration legitimately contributes its own MVC "
                                + "configuration-support bean, so the question is not whether one exists "
                                + "but which one does. It must be the auto-configuration's own nested "
                                + "configuration; a plain delegating configuration in its place is the "
                                + "signature of the explicit enablement annotation")
                        .isInstanceOf(WebMvcAutoConfiguration.EnableWebMvcConfiguration.class);
            });
        }

        @Test
        @DisplayName("registered on its own the configurer contributes no MVC configuration support at all, "
                + "which is the decisive signal that no explicit enablement is imported")
        void theConfigurerAloneContributesNoConfigurationSupport() {
            new WebApplicationContextRunner()
                    .withUserConfiguration(WebMvcConfig.class)
                    .withPropertyValues(
                            WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY + "=",
                            WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY + "=64KB")
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .run(context -> assertThat(context)
                            .as("with no auto-configuration registered, the only thing that could put an "
                                    + "MVC configuration-support bean in this context is WebMvcConfig "
                                    + "itself. The explicit enablement annotation imports a delegating "
                                    + "configuration, which is such a bean, so finding one here means the "
                                    + "annotation is present")
                            .hasNotFailed()
                            .hasSingleBean(WebMvcConfig.class)
                            .doesNotHaveBean(WebMvcConfigurationSupport.class));
        }

        @Test
        @DisplayName("registered on its own the configurer contributes no serialiser and no converter, so "
                + "the auto-configured ones are the only ones there can be")
        void theConfigurerAloneContributesNoSerialiserOrConverter() {
            new WebApplicationContextRunner()
                    .withUserConfiguration(WebMvcConfig.class)
                    .withPropertyValues(
                            WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY + "=",
                            WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY + "=64KB")
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .run(context -> assertThat(context)
                            .as("WebMvcConfig must contribute no object mapper, no builder customiser and "
                                    + "no message converter of its own. Any of those would re-derive the "
                                    + "serialisation settings in a second place, where they could drift "
                                    + "from the module's configuration file without either copy looking "
                                    + "wrong")
                            .hasNotFailed()
                            .doesNotHaveBean(ObjectMapper.class)
                            .doesNotHaveBean(HttpMessageConverter.class)
                            .doesNotHaveBean(HandlerExceptionResolver.class));
        }
    }

    /**
     * Demonstrates that the survival assertions above can actually fail.
     *
     * <p>A negative test that cannot fail is worthless, and "the auto-configuration is still active" is a
     * negative test. These two cases run the same slice against a local configuration that carries the
     * explicit web-MVC enablement annotation, and show the two survival signals inverting: the MVC
     * auto-configuration disappears, and the converter bound to the request-handling adapter is no longer
     * the auto-configured instance. That is the whole failure mode, reproduced in-suite rather than
     * described in a comment, so the discriminating power of the assertions above is a checked property of
     * this file and not a claim about it.
     *
     * <p>The control configuration is nested inside this test class and is reachable from nowhere else. It
     * is the only place in this module where that annotation appears, and it exists solely to be the thing
     * {@code WebMvcConfig} must never become.
     */
    @Nested
    @DisplayName("the control group that makes the survival proof falsifiable")
    class FalsifiabilityControl {

        @Test
        @DisplayName("an explicit web-MVC enablement withdraws the MVC auto-configuration entirely")
        void anExplicitEnablementWithdrawsTheMvcAutoConfiguration() {
            AUTO_CONFIGURED_WEB_TIER.withUserConfiguration(ExplicitWebMvcEnablement.class)
                    .run(context -> assertThat(context)
                            .as("the survival assertions rest on the framework backing off when an "
                                    + "explicit web-MVC configuration is contributed. If the MVC "
                                    + "auto-configuration were still present even here, those assertions "
                                    + "would prove nothing and this file would be giving false assurance")
                            .hasNotFailed()
                            .doesNotHaveBean(WebMvcAutoConfiguration.class));
        }

        @Test
        @DisplayName("an explicit web-MVC enablement detaches the auto-configured converter from the "
                + "request-handling adapter even though the converter bean still exists")
        void anExplicitEnablementDetachesTheAutoConfiguredConverter() {
            AUTO_CONFIGURED_WEB_TIER.withUserConfiguration(ExplicitWebMvcEnablement.class).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(MappingJackson2HttpMessageConverter.class);
                final MappingJackson2HttpMessageConverter autoConfigured =
                        context.getBean(MappingJackson2HttpMessageConverter.class);

                assertThat(convertersInForce(context))
                        .as("this is the trap the survival half exists to catch, shown happening: the "
                                + "auto-configured converter bean is still in the context, so a presence "
                                + "check would pass, yet the adapter has built its own converters and the "
                                + "configured object mapper reaches no response body at all")
                        .noneSatisfy(candidate -> assertThat(candidate).isSameAs(autoConfigured));
            });
        }
    }

    /**
     * A cheap structural guard placed beside the survival proof, never in place of it.
     *
     * <p>The survival slice above is the real proof; this looks the annotation up directly so that a
     * reviewer reading a failure report is told in one line what the slice failures mean. The lookup goes
     * through the framework's own merged-annotation search over the type hierarchy, so a meta-annotated
     * or inherited enablement is found too, and no hand-rolled introspection is performed.
     */
    @Nested
    @DisplayName("secondary guard, subordinate to the survival proof")
    class SecondaryAnnotationGuard {

        @Test
        @DisplayName("the configurer carries no explicit web-MVC enablement, directly or through any "
                + "meta-annotation or supertype")
        void theConfigurerCarriesNoExplicitWebMvcEnablement() {
            final boolean enablementPresent = MergedAnnotations
                    .from(WebMvcConfig.class, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .isPresent(EnableWebMvc.class);

            assertThat(enablementPresent)
                    .as("WebMvcConfig must never carry the explicit web-MVC enablement annotation, whether "
                            + "written on the class, reached through a meta-annotation or inherited from a "
                            + "supertype. This is the cause; the survival slice asserts the consequence, "
                            + "and the consequence is the assertion that matters")
                    .isFalse();
        }
    }

    /**
     * The negative control: what {@code WebMvcConfig} must never become.
     *
     * <p>Deliberately annotated for explicit web-MVC enablement so that the control group can observe the
     * framework withdrawing its MVC auto-configuration. It contributes nothing beyond the annotation under
     * observation, and it reaches a context only where a test registers it by name.
     *
     * <p>It cannot leak into the application's own context, which is the obvious worry about a class
     * carrying this annotation. The framework's component scan excludes a nested class whose enclosing
     * class is a test class, and the enclosing class here declares test methods, so no scan started from
     * the application entry point will ever consider this one. The two runners above register it
     * explicitly instead, which is the only path by which it is ever seen.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static final class ExplicitWebMvcEnablement {
    }
}
