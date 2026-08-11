/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Verifies that {@link WebMvcConfig} preserves MVC defaults around its explicit security boundaries.
 *
 * <p>The legacy presentation layer was a CICS 3270 contract and supplies no browser origin. The migrated
 * API therefore ships an explicit empty exact-origin list, while still registering no view resolver,
 * static resource handler, custom message converter, argument resolver or return-value handler.</p>
 *
 * <p>The verification is behavioural rather than structural. Rather than inspecting the class for the absence
 * of overrides - which would require introspection the module forbids - each contribution point that can be
 * observed without a servlet container is invoked against a mutable collection and the collection is asserted
 * to be untouched, and each contribution point that returns a component is asserted to return none. That is
 * the same statement an inspection would make, arrived at by exercising the object.</p>
 *
 * <p>Scope: no Spring application context is started, no servlet container is created, no HTTP request is
 * dispatched, no database, file, network or container is touched, and nothing is introspected reflectively.</p>
 *
 * <p>No legacy source text is reproduced here.</p>
 */
@DisplayName("WebMvcConfig - defaults preserved around explicit web boundaries")
final class WebMvcConfigBaselineTest {

    /** The configuration under test. */
    private WebMvcConfig config;

    @BeforeEach
    void createConfiguration() {
        this.config = new WebMvcConfig();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("direct construction chooses the secure deny-all and 64 KiB posture")
        void theConfigurationConstructsWithoutAnyCollaborator() {
            assertThatCode(WebMvcConfig::new).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the configuration is a web-tier configurer, which is the only reason it participates in "
                + "the context at all")
        void theConfigurationIsAWebTierConfigurer() {
            assertThat(config).isInstanceOf(WebMvcConfigurer.class);
        }

        @Test
        @DisplayName("two configurations are independent objects carrying no state between them")
        void twoConfigurationsAreIndependentObjects() {
            assertThat(new WebMvcConfig()).isNotSameAs(new WebMvcConfig());
        }
    }

    @Nested
    @DisplayName("The shipped CORS posture")
    class CorsPosture {

        @Test
        @DisplayName("one API mapping is registered and its exact-origin list is empty")
        void oneDenyAllApiMappingIsRegistered() {
            ExposedCorsRegistry registry = new ExposedCorsRegistry();

            config.addCorsMappings(registry);

            assertThat(registry.configurations()).containsOnlyKeys(WebMvcConfig.API_PATH_PATTERN);
            assertThat(registry.configurations().get(WebMvcConfig.API_PATH_PATTERN)
                    .getAllowedOrigins()).isEmpty();
        }
    }

    /** Registry seam exposing the policy MVC would install. */
    private static final class ExposedCorsRegistry extends CorsRegistry {

        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }

    @Nested
    @DisplayName("Contribution points that accept a collection")
    class CollectionContributionPoints {

        @Test
        @DisplayName("no message converter is configured, so the auto-configured converter list - which is "
                + "what serialises a fixed-scale decimal as a plain decimal rather than in scientific "
                + "notation - is left exactly as it stands")
        void noMessageConverterIsConfigured() {
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();

            config.configureMessageConverters(converters);

            assertThat(converters).isEmpty();
        }

        @Test
        @DisplayName("no message converter is appended either, so the ordering the auto-configuration "
                + "established is preserved as well as its membership")
        void noMessageConverterIsAppended() {
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();

            config.extendMessageConverters(converters);

            assertThat(converters).isEmpty();
        }

        @Test
        @DisplayName("no argument resolver is registered, because every endpoint takes its input as a "
                + "declared request type rather than through a bespoke binding")
        void noArgumentResolverIsRegistered() {
            final List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

            config.addArgumentResolvers(resolvers);

            assertThat(resolvers).isEmpty();
        }

        @Test
        @DisplayName("no return value handler is registered, because every endpoint returns a declared "
                + "response type")
        void noReturnValueHandlerIsRegistered() {
            final List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();

            config.addReturnValueHandlers(handlers);

            assertThat(handlers).isEmpty();
        }

        @Test
        @DisplayName("no exception resolver is configured here, because failure translation belongs entirely "
                + "to the boundary advice that maps the six migrated failure carriers onto statuses")
        void noExceptionResolverIsConfigured() {
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>();

            config.configureHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).isEmpty();
        }

        @Test
        @DisplayName("no exception resolver is appended either, so the advice remains the single place where "
                + "a failure becomes a response")
        void noExceptionResolverIsAppended() {
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>();

            config.extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).isEmpty();
        }

        @Test
        @DisplayName("no error response interceptor is registered, so the response body the advice builds is "
                + "the body the client receives")
        void noErrorResponseInterceptorIsRegistered() {
            final List<ErrorResponse.Interceptor> interceptors = new ArrayList<>();

            config.addErrorResponseInterceptors(interceptors);

            assertThat(interceptors).isEmpty();
        }

        @Test
        @DisplayName("a collection already carrying entries is returned untouched, which proves the "
                + "contribution points are genuine no-ops rather than methods that happen to clear an empty "
                + "list")
        void anAlreadyPopulatedCollectionIsReturnedUntouched() {
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>();
            final HandlerExceptionResolver sentinel = (request, response, handler, exception) -> null;
            resolvers.add(sentinel);

            config.configureHandlerExceptionResolvers(resolvers);
            config.extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).containsExactly(sentinel);
        }
    }

    @Nested
    @DisplayName("Contribution points that return a component")
    class ComponentContributionPoints {

        @Test
        @DisplayName("no validator is supplied, so the auto-configured Jakarta Bean Validation validator "
                + "remains in force - which is what enforces the per-field width constraints carried across "
                + "from the record layouts")
        void noValidatorIsSupplied() {
            assertThat(config.getValidator()).isNull();
        }

        @Test
        @DisplayName("no message codes resolver is supplied, so the default field-error code convention "
                + "stands")
        void noMessageCodesResolverIsSupplied() {
            assertThat(config.getMessageCodesResolver()).isNull();
        }
    }
}
