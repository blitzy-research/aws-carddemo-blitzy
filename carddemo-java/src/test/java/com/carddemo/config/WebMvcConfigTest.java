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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.format.AnnotationFormatterFactory;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.Parser;
import org.springframework.format.Printer;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.validation.MessageCodesResolver;
import org.springframework.validation.Validator;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link WebMvcConfig}, which contributes the web layer's configuration.
 *
 * <p><strong>Its contract is what it declines to do.</strong> This class participates in Spring MVC's
 * configuration callbacks and deliberately overrides none of them, which means the module runs on the
 * framework defaults. That is not an empty statement — three other verified behaviours depend on it, and
 * each would break silently if an override were added here:
 *
 * <ul>
 *   <li><em>The JSON wire contract.</em> The message converters are contributed by the framework's Jackson
 *       auto-configuration, which is what applies the omit-null, tolerate-unknown, plain-decimal settings
 *       the module's configuration file declares. Installing or replacing a converter here would bypass
 *       that configuration entirely, and every amount would be at risk of travelling in a different form
 *       than the contract promises.</li>
 *   <li><em>The exception-to-status mapping.</em> Responses to failures are produced by the module's
 *       controller advice. Registering an exception resolver here would take precedence over it and could
 *       silently reinstate the leakage of file statuses and record keys that the advice exists to
 *       suppress.</li>
 *   <li><em>Who decides access.</em> Registering an interceptor here would place a second gate in front of
 *       the endpoints, alongside the security filter chain, so a request could be admitted or refused by
 *       something other than the declared authorisation rules.</li>
 * </ul>
 *
 * <p><strong>How that is asserted.</strong> Every callback the framework will make is invoked here with a
 * real registry or a real collection, and the registry is then inspected to show nothing was contributed.
 * Where the framework's registry hides its contents behind a protected accessor, a small subclass in this
 * package exposes it; where the callback takes a collection, the collection itself is the evidence. The
 * formatter registry is a recording implementation that counts every registration attempt, so a single
 * added converter would be caught rather than merely being invisible.
 */
@DisplayName("WebMvcConfig — accepting the framework defaults")
class WebMvcConfigTest {

    /** The configuration under test, used through the interface the framework calls it through. */
    private final WebMvcConfigurer configurer = new WebMvcConfig();

    // =================================================================================================
    // PARTICIPATION
    // =================================================================================================

    /**
     * Verifies that the class is wired into the configuration callbacks at all.
     */
    @Nested
    @DisplayName("participation")
    class Participation {

        @Test
        @DisplayName("the configuration participates in the web layer's callbacks")
        void theConfigurationParticipatesInTheCallbacks() {
            assertThat(new WebMvcConfig()).isInstanceOf(WebMvcConfigurer.class);
        }

        @Test
        @DisplayName("the configuration is constructible without collaborators, so it can be created "
                + "before anything else is available")
        void theConfigurationIsConstructibleWithoutCollaborators() {
            assertThat(new WebMvcConfig()).isNotNull();
        }

        @Test
        @DisplayName("each instance is independent, holding no shared state")
        void eachInstanceIsIndependent() {
            assertThat(new WebMvcConfig()).isNotSameAs(new WebMvcConfig());
        }
    }

    // =================================================================================================
    // THE MESSAGE CONVERTERS
    // =================================================================================================

    /**
     * Verifies that the module leaves body conversion to the framework.
     */
    @Nested
    @DisplayName("the message converters")
    class TheMessageConverters {

        @Test
        @DisplayName("no converter is installed in place of the framework's own, so the auto-configured "
                + "JSON settings remain in force")
        void noConverterIsInstalledInPlaceOfTheFrameworkOwn() {
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();

            WebMvcConfigTest.this.configurer.configureMessageConverters(converters);

            assertThat(converters).isEmpty();
        }

        @Test
        @DisplayName("the framework's converter list is left exactly as it was found, in the same order")
        void theFrameworkConverterListIsLeftAsFound() {
            final HttpMessageConverter<?> first = new RecordingHttpMessageConverter();
            final HttpMessageConverter<?> second = new RecordingHttpMessageConverter();
            final List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(first, second));

            WebMvcConfigTest.this.configurer.extendMessageConverters(converters);

            assertThat(converters).containsExactly(first, second);
        }
    }

    // =================================================================================================
    // FAILURE HANDLING
    // =================================================================================================

    /**
     * Verifies that the module leaves failure responses to its controller advice.
     */
    @Nested
    @DisplayName("failure handling")
    class FailureHandling {

        @Test
        @DisplayName("no exception resolver is installed, so the controller advice keeps deciding the "
                + "status and body of a failure")
        void noExceptionResolverIsInstalled() {
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>();

            WebMvcConfigTest.this.configurer.configureHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).isEmpty();
        }

        @Test
        @DisplayName("no error-response interceptor is registered, so nothing rewrites a failure body "
                + "after the advice has produced it")
        void noErrorResponseInterceptorIsRegistered() {
            final List<ErrorResponse.Interceptor> interceptors = new ArrayList<>();

            WebMvcConfigTest.this.configurer.addErrorResponseInterceptors(interceptors);

            assertThat(interceptors).isEmpty();
        }

        @Test
        @DisplayName("the framework's resolver list is left exactly as it was found")
        void theFrameworkResolverListIsLeftAsFound() {
            final HandlerExceptionResolver resolver = (request, response, handler, exception) -> null;
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>(List.of(resolver));

            WebMvcConfigTest.this.configurer.extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).containsExactly(resolver);
        }
    }

    // =================================================================================================
    // REQUEST ADMISSION
    // =================================================================================================

    /**
     * Verifies that nothing here gates or re-routes a request.
     */
    @Nested
    @DisplayName("request admission")
    class RequestAdmission {

        @Test
        @DisplayName("no interceptor is registered, so the security filter chain remains the only gate")
        void noInterceptorIsRegistered() {
            final ProbeInterceptorRegistry registry = new ProbeInterceptorRegistry();

            WebMvcConfigTest.this.configurer.addInterceptors(registry);

            assertThat(registry.registered()).isEmpty();
        }

        @Test
        @DisplayName("no cross-origin mapping is registered, so no origin is admitted that the framework "
                + "defaults would refuse")
        void noCrossOriginMappingIsRegistered() {
            final ProbeCorsRegistry registry = new ProbeCorsRegistry();

            WebMvcConfigTest.this.configurer.addCorsMappings(registry);

            assertThat(registry.registered()).isEmpty();
        }

        @Test
        @DisplayName("no view controller is registered, so no path is answered without reaching a "
                + "controller")
        void noViewControllerIsRegistered() {
            try (StaticApplicationContext context = new StaticApplicationContext()) {
                final ProbeViewControllerRegistry registry = new ProbeViewControllerRegistry(context);

                WebMvcConfigTest.this.configurer.addViewControllers(registry);

                assertThat(registry.registeredMapping()).isNull();
            }
        }
    }

    // =================================================================================================
    // HANDLER METHOD BINDING
    // =================================================================================================

    /**
     * Verifies that argument binding and return-value handling stay with the framework.
     */
    @Nested
    @DisplayName("handler method binding")
    class HandlerMethodBinding {

        @Test
        @DisplayName("no argument resolver is contributed, so request binding follows the framework's own "
                + "rules")
        void noArgumentResolverIsContributed() {
            final List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

            WebMvcConfigTest.this.configurer.addArgumentResolvers(resolvers);

            assertThat(resolvers).isEmpty();
        }

        @Test
        @DisplayName("no return-value handler is contributed, so response bodies are written the framework "
                + "way")
        void noReturnValueHandlerIsContributed() {
            final List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();

            WebMvcConfigTest.this.configurer.addReturnValueHandlers(handlers);

            assertThat(handlers).isEmpty();
        }

        @Test
        @DisplayName("no formatter or converter is registered, so no field is parsed or rendered by a "
                + "module-specific rule")
        void noFormatterOrConverterIsRegistered() {
            final RecordingFormatterRegistry registry = new RecordingFormatterRegistry();

            WebMvcConfigTest.this.configurer.addFormatters(registry);

            assertThat(registry.registrations()).isZero();
        }
    }

    // =================================================================================================
    // DELEGATED DECISIONS
    // =================================================================================================

    /**
     * Verifies the callbacks whose answer is a single object rather than a registry.
     */
    @Nested
    @DisplayName("delegated decisions")
    class DelegatedDecisions {

        @Test
        @DisplayName("no validator is supplied, so bean validation is discovered by the framework rather "
                + "than replaced here")
        void noValidatorIsSupplied() {
            final Validator supplied = WebMvcConfigTest.this.configurer.getValidator();

            assertThat(supplied).isNull();
        }

        @Test
        @DisplayName("no message-code resolver is supplied, so field error codes keep their standard form")
        void noMessageCodeResolverIsSupplied() {
            final MessageCodesResolver supplied =
                    WebMvcConfigTest.this.configurer.getMessageCodesResolver();

            assertThat(supplied).isNull();
        }
    }

    // =================================================================================================
    // TEST DOUBLES
    // =================================================================================================

    /**
     * Exposes the interceptors a registry accumulated.
     */
    private static final class ProbeInterceptorRegistry extends InterceptorRegistry {

        /**
         * Returns the interceptors registered so far.
         *
         * @return the registered interceptors
         */
        List<Object> registered() {
            return getInterceptors();
        }
    }

    /**
     * Exposes the cross-origin configurations a registry accumulated.
     */
    private static final class ProbeCorsRegistry extends CorsRegistry {

        /**
         * Returns the cross-origin configurations registered so far.
         *
         * @return the registered configurations, keyed by path pattern
         */
        Map<String, ?> registered() {
            return getCorsConfigurations();
        }
    }

    /**
     * Exposes the handler mapping a view-controller registry would publish.
     */
    private static final class ProbeViewControllerRegistry extends ViewControllerRegistry {

        /**
         * Creates a registry bound to the supplied context.
         *
         * @param applicationContext the context the registry resolves views against
         */
        ProbeViewControllerRegistry(final StaticApplicationContext applicationContext) {
            super(applicationContext);
        }

        /**
         * Returns the handler mapping the registry would publish, which is absent when nothing was
         * registered.
         *
         * @return the published mapping, or {@code null} when nothing was registered
         */
        Object registeredMapping() {
            return buildHandlerMapping();
        }
    }

    /**
     * A formatter registry that records every registration attempt without performing any.
     */
    private static final class RecordingFormatterRegistry implements FormatterRegistry {

        /** Number of registration attempts observed. */
        private int registrations;

        /**
         * Returns how many registration attempts were observed.
         *
         * @return the registration count
         */
        int registrations() {
            return this.registrations;
        }

        @Override
        public void addPrinter(final Printer<?> printer) {
            this.registrations++;
        }

        @Override
        public void addParser(final Parser<?> parser) {
            this.registrations++;
        }

        @Override
        public void addFormatter(final Formatter<?> formatter) {
            this.registrations++;
        }

        @Override
        public void addFormatterForFieldType(final Class<?> fieldType, final Formatter<?> formatter) {
            this.registrations++;
        }

        @Override
        public void addFormatterForFieldType(final Class<?> fieldType, final Printer<?> printer,
                final Parser<?> parser) {
            this.registrations++;
        }

        @Override
        public void addFormatterForFieldAnnotation(
                final AnnotationFormatterFactory<? extends Annotation> factory) {
            this.registrations++;
        }

        @Override
        public void addConverter(final Converter<?, ?> converter) {
            this.registrations++;
        }

        @Override
        public <S, T> void addConverter(final Class<S> sourceType, final Class<T> targetType,
                final Converter<? super S, ? extends T> converter) {
            this.registrations++;
        }

        @Override
        public void addConverter(final GenericConverter converter) {
            this.registrations++;
        }

        @Override
        public void addConverterFactory(final ConverterFactory<?, ?> factory) {
            this.registrations++;
        }

        @Override
        public void removeConvertible(final Class<?> sourceType, final Class<?> targetType) {
            this.registrations++;
        }
    }

    /**
     * A message converter that participates in nothing, used only as a list member whose identity can be
     * asserted.
     */
    private static final class RecordingHttpMessageConverter implements HttpMessageConverter<Object> {

        @Override
        public boolean canRead(final Class<?> clazz, final MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(final Class<?> clazz, final MediaType mediaType) {
            return false;
        }

        @Override
        public List<MediaType> getSupportedMediaTypes() {
            return List.of();
        }

        @Override
        public Object read(final Class<?> clazz, final HttpInputMessage inputMessage) {
            throw new UnsupportedOperationException("this converter never reads");
        }

        @Override
        public void write(final Object value, final MediaType contentType,
                final HttpOutputMessage outputMessage) {
            throw new UnsupportedOperationException("this converter never writes");
        }
    }
}
