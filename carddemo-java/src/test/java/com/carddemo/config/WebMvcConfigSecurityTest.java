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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Unit test for {@link WebMvcConfig}, the web tier's bounded CORS and request-body policy.
 *
 * <h2>Asserting that a class does nothing is not a contradiction</h2>
 *
 * <p>An empty configuration hook is the most fragile kind of class to read, because a reader
 * cannot distinguish "deliberately empty" from "someone forgot". The class itself resolves that by
 * recording each declined customisation in prose. This test resolves the complementary risk, which is
 * the opposite direction of drift: that a future edit quietly <em>adds</em> a customisation. The
 * assertions below therefore prove emptiness behaviourally rather than by inspection - each hook is
 * invoked with a real, empty collection and the collection is asserted to be untouched afterwards.</p>
 *
 * <h2>Two hooks matter more than the rest, and both must keep answering absent</h2>
 *
 * <p>{@link WebMvcConfigurer#getValidator()} and
 * {@link WebMvcConfigurer#getMessageCodesResolver()} both signal "use the framework default" by
 * returning {@code null}. Replacing the validator would change which declarative constraints fire and
 * how their messages resolve - and those are exactly the inputs
 * {@code GlobalExceptionHandler.handleBindingFailure} and {@code handleConstraintViolation} translate
 * into the two-state field-error contract. A substituted validator would therefore change the response
 * body of a rejected request without any change to the handler that produces it, so the two nulls are
 * asserted explicitly.</p>
 */
@DisplayName("WebMvcConfig - explicit deny-by-default web boundaries")
class WebMvcConfigSecurityTest {

    private final WebMvcConfig config = new WebMvcConfig();

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("the class is instantiable through its public no-argument constructor, which is what the "
                + "container invokes")
        void theClassIsInstantiable() {
            assertThat(new WebMvcConfig()).isNotNull();
        }

        @Test
        @DisplayName("the class is a web configurer, so it genuinely occupies the customisation seat rather than "
                + "merely existing beside it")
        void theClassIsAWebConfigurer() {
            assertThat(config).isInstanceOf(WebMvcConfigurer.class);
        }
    }

    @Nested
    @DisplayName("Configuration values are strict and bounded")
    class ConfigurationValues {

        @Test
        @DisplayName("wildcards, non-HTTP schemes and path-bearing origins are refused")
        void unsafeOriginsAreRefused() {
            assertThatThrownBy(() -> new WebMvcConfig("*"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exact");
            assertThatThrownBy(() -> new WebMvcConfig("file:///tmp/client"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTP or HTTPS");
            assertThatThrownBy(() -> new WebMvcConfig("https://client.example/path"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scheme, host, and optional port");
            assertThatThrownBy(() -> new WebMvcConfig("https://client.example:70000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scheme, host, and optional port");
        }

        @Test
        @DisplayName("zero, malformed and excessively large body limits are refused")
        void unsafeBodyLimitsAreRefused() {
            assertThatThrownBy(() -> new WebMvcConfig.RequestBodyLimitFilter("0B", new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 1 byte");
            assertThatThrownBy(() -> new WebMvcConfig.RequestBodyLimitFilter("17MB", new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 1 byte");
            assertThatThrownBy(() -> new WebMvcConfig.RequestBodyLimitFilter("not-a-size", new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("valid data-size");
        }
    }

    @Nested
    @DisplayName("Every collection-shaped hook leaves its collection untouched")
    class CollectionHooks {

        @Test
        @DisplayName("configuring message converters adds none, so the auto-configured JSON conversion the interface "
                + "contract depends on is not displaced")
        void configuringMessageConvertersAddsNone() {
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();
            config.configureMessageConverters(converters);
            assertThat(converters).isEmpty();
        }

        @Test
        @DisplayName("extending message converters adds none, so no converter is appended after the defaults either")
        void extendingMessageConvertersAddsNone() {
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();
            config.extendMessageConverters(converters);
            assertThat(converters).isEmpty();
        }

        @Test
        @DisplayName("no argument resolver is contributed, so every controller parameter is bound by the framework's "
                + "own resolution rather than by a custom path")
        void noArgumentResolverIsContributed() {
            final List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
            config.addArgumentResolvers(resolvers);
            assertThat(resolvers).isEmpty();
        }

        @Test
        @DisplayName("no return-value handler is contributed, so a controller's response is rendered by the "
                + "framework's own handling")
        void noReturnValueHandlerIsContributed() {
            final List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
            config.addReturnValueHandlers(handlers);
            assertThat(handlers).isEmpty();
        }

        @Test
        @DisplayName("no exception resolver is contributed and none is extended, so the advice-based exception "
                + "boundary remains the only translation layer")
        void noExceptionResolverIsContributedOrExtended() {
            final List<org.springframework.web.servlet.HandlerExceptionResolver> resolvers = new ArrayList<>();
            config.configureHandlerExceptionResolvers(resolvers);
            assertThat(resolvers).isEmpty();
            config.extendHandlerExceptionResolvers(resolvers);
            assertThat(resolvers).isEmpty();
        }
    }

    @Nested
    @DisplayName("The two hooks that would change how a rejected request is answered")
    class ValidationHooks {

        @Test
        @DisplayName("no validator is substituted, so the declarative constraints the exception boundary translates "
                + "are the ones the framework's own validator produces")
        void noValidatorIsSubstituted() {
            assertThat(config.getValidator()).isNull();
        }

        @Test
        @DisplayName("no message-codes resolver is substituted, so a field error's resolved code stays in the shape "
                + "the presence-constraint mapping matches against")
        void noMessageCodesResolverIsSubstituted() {
            assertThat(config.getMessageCodesResolver()).isNull();
        }
    }

    @Nested
    @DisplayName("Container contribution, proven against a real context")
    class ContainerContribution {

        @Test
        @DisplayName("the hook is contributed as a single bean and the context starts, which is the only thing this "
                + "class has to do")
        void theHookIsContributedAndTheContextStarts() {
            new ApplicationContextRunner()
                    .withUserConfiguration(WebMvcConfig.class)
                    .withPropertyValues(
                            WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY + "=",
                            WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY + "=64KB")
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(WebMvcConfig.class)
                            .hasSingleBean(WebMvcConfigurer.class)
                            .hasSingleBean(CorsConfigurationSource.class)
                            .hasSingleBean(FilterRegistrationBean.class));
        }
    }
}
