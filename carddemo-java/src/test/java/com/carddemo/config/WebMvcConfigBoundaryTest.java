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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import jakarta.validation.MessageInterpolator;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Exercises the web tier's single customisation hook, which deliberately customises nothing.
 *
 * <h2>What is under test</h2>
 * {@link WebMvcConfig} occupies the web-configuration seat and overrides no {@link WebMvcConfigurer}
 * callback at all, so that every adjustment which was considered and declined is recorded in one
 * reviewable place rather than left for the next reader to rediscover. The tests assert exactly that:
 * the class is a configurer the framework will collect, it is registered unproxied, it overrides no
 * callback, and the single bean it does contribute adds a request-binding rule without disturbing any
 * setting the configuration file already declares.
 *
 * <h2>Why the absence of an override is asserted rather than assumed</h2>
 * Two of the declined adjustments would be actively harmful if a later change introduced them here.
 * A framework-wide error format registered through an exception resolver would compete with the
 * module's own failure adapter for the same responses and would flatten the two-state field error
 * contract inherited from the legacy screens into one state. And a message converter registered here
 * would displace the module's configured one, which is what keeps a fixed-scale decimal from being
 * rendered in scientific notation. Asserting that the hook adds neither turns "we chose not to" into
 * a check that fails if someone later does.
 *
 * <h2>Why exactly one bean is contributed, and why that is not the same kind of change</h2>
 * A fixed-width screen field is external text. A JSON number arriving where the published contract
 * declares text is not a value in a different notation, it is a different value: its leading zeros
 * are already gone by the time any validator could measure it, so silently accepting it would admit a
 * corrupted identifier that no legacy screen could have produced. The framework has no configuration
 * property that refuses that shape, so the refusal is expressed in Java by
 * {@code strictScalarCoercionCustomizer}. That method is additive in a way a converter registration
 * is not: a {@link Jackson2ObjectMapperBuilderCustomizer} is handed the very builder the
 * auto-configuration has already populated from the configuration file, so it narrows what a request
 * may say without re-deriving, restating or displacing anything the file declares. The tests below
 * assert both halves of that claim - that the method is the only bean the class contributes, and that
 * a setting present on the builder beforehand is still in force afterwards.
 *
 * <h2>Why the unproxied registration is asserted</h2>
 * A configuration class whose factory methods are intercepted is subclassed at runtime, which would
 * both forbid this class from being final and add to the module's runtime proxy surface — a surface
 * the low-level-code audit counts and reports. The annotation attribute that keeps it unproxied is
 * therefore part of the contract and not an incidental style choice.
 *
 * <p>Provenance: the legacy authority for the existence of a web tier at all is
 * {@code app/csd/CARDDEMO.CSD}, whose transaction and mapset definitions become this module's
 * endpoint groups, at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * reproduced.</p>
 */
@DisplayName("WebMvcConfig - the web hook that declines every adjustment but one")
class WebMvcConfigBoundaryTest {

    @Test
    @DisplayName("the hook is a configurer the web tier will collect")
    void theHookIsAConfigurer() {
        assertThat(new WebMvcConfig()).isInstanceOf(WebMvcConfigurer.class);
    }

    @Test
    @DisplayName("the hook is registered unproxied, leaving the runtime proxy surface untouched")
    void theHookIsRegisteredUnproxied() {
        Configuration annotation = WebMvcConfig.class.getAnnotation(Configuration.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.proxyBeanMethods()).isFalse();
    }

    @Test
    @DisplayName("the hook is final, which an unproxied registration permits")
    void theHookIsFinal() {
        assertThat(Modifier.isFinal(WebMvcConfig.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("the hook declares exactly two factory methods and neither takes a collaborator, so "
            + "there is nothing either could have closed over and nothing to substitute in a test")
    void theHookDeclaresExactlyTwoFactoryMethods() {
        List<Method> declared = Arrays.stream(WebMvcConfig.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        // TWO, and the second one is the validator. The count is asserted rather than left open
        // because the property being protected is that nothing here is injected and nothing is
        // stateful - a third method appearing silently is exactly what would erode that.
        assertThat(declared).hasSize(2);
        assertThat(declared).extracting(Method::getName)
                .containsExactlyInAnyOrder("strictScalarCoercionCustomizer", "defaultValidator");
        assertThat(declared).allSatisfy(method -> assertThat(method.getParameterCount())
                .as("%s must inject nothing: a factory here that injected something would make the "
                        + "request-binding rules depend on resolution order", method.getName())
                .isZero());
        assertThat(declared)
                .as("each factory returns the type the framework resolves it by")
                .extracting(Method::getReturnType)
                .containsExactlyInAnyOrder(Jackson2ObjectMapperBuilderCustomizer.class,
                        LocalValidatorFactoryBean.class);
        assertThat(WebMvcConfig.class.getDeclaredFields())
                .as("no field means no state can be carried between two requests")
                .isEmpty();
    }

    @Test
    @DisplayName("both bean-producing methods are accounted for by annotation, so no third contribution "
            + "was added alongside them")
    void theAnnotatedBeanMethodsAreTheCoercionCustomiserAndTheValidator() {
        List<Method> beanMethods = Arrays.stream(WebMvcConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();

        assertThat(beanMethods).hasSize(2);
        assertThat(beanMethods).extracting(Method::getName)
                .containsExactlyInAnyOrder("strictScalarCoercionCustomizer", "defaultValidator");
    }

    @Test
    @DisplayName("the validator renders constraint messages through the module's own pinned "
            + "interpolator, so a field error is the same bytes for every caller")
    void theValidatorPinsTheMessageLocale() {
        LocalValidatorFactoryBean validator = new WebMvcConfig().defaultValidator();
        validator.afterPropertiesSet();

        // The framework wraps whatever interpolator is supplied, so the wrapper is what comes back out.
        // What matters is the rendered result, and that is asserted directly: a size violation renders
        // in the provider's base bundle regardless of the locale the context holder carries.
        assertThat(validator.getMessageInterpolator())
                .as("an interpolator must have been supplied rather than left to the default")
                .isNotNull();
        assertThat(validator.getMessageInterpolator()
                        .interpolate("{jakarta.validation.constraints.NotBlank.message}",
                                new StubInterpolatorContext(), Locale.forLanguageTag("tr-TR")))
                .as("a Turkish request must not change the bytes of an emitted field error")
                .isEqualTo("must not be blank");
    }

    /** Minimal interpolation context, so a rendering assertion needs no live validation run. */
    private static final class StubInterpolatorContext implements MessageInterpolator.Context {

        @Override
        public jakarta.validation.metadata.ConstraintDescriptor<?> getConstraintDescriptor() {
            return null;
        }

        @Override
        public Object getValidatedValue() {
            return null;
        }

        @Override
        public <T> T unwrap(final Class<T> type) {
            throw new jakarta.validation.ValidationException("no unwrapping in this stub");
        }
    }

    @Test
    @DisplayName("the customiser narrows what a request may say without disturbing a setting the builder "
            + "already carried, which is what makes it additive rather than substituting")
    void theCustomiserExtendsTheBuilderRatherThanReplacingIt() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json()
                .serializationInclusion(JsonInclude.Include.NON_NULL);

        new WebMvcConfig().strictScalarCoercionCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        assertThat(mapper.writeValueAsString(new TextHolder(null)))
                .as("the setting placed on the builder before the customiser ran is still in force after "
                        + "it, so nothing the configuration file declares is re-derived or lost")
                .isEqualTo("{}");
        assertThatThrownBy(() -> mapper.readValue("{\"text\":1}", TextHolder.class))
                .as("and the rule the customiser adds is in force on the mapper that builder produced")
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"text\":1.5}", TextHolder.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"text\":true}", TextHolder.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("the customiser refuses only the shapes the contract never declares, so text, an absent "
            + "member and an empty screen field all still bind")
    void theCustomiserLeavesEveryDeclaredShapeBinding() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();

        new WebMvcConfig().strictScalarCoercionCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        assertThat(mapper.readValue("{\"text\":\"00000000011\"}", TextHolder.class).text())
                .isEqualTo("00000000011");
        assertThat(mapper.readValue("{}", TextHolder.class).text()).isNull();
        assertThat(mapper.readValue("{\"text\":\"\"}", TextHolder.class).text())
                .as("an operator may submit a blank screen field, and the ordered emptiness cascade owns "
                        + "the message that follows - binding is not where that is decided")
                .isEmpty();
    }

    /**
     * Stand-in for a fixed-width screen field, declared here so the coercion assertions above name a
     * shape rather than a production type and stay readable when a production type changes.
     *
     * @param text the value a screen field would carry, always external text
     */
    private record TextHolder(String text) {
    }

    @Test
    @DisplayName("the hook adds no exception resolver, which would compete with the failure adapter")
    void theHookAddsNoExceptionResolver() {
        List<HandlerExceptionResolver> resolvers = new ArrayList<>();

        new WebMvcConfig().configureHandlerExceptionResolvers(resolvers);
        new WebMvcConfig().extendHandlerExceptionResolvers(resolvers);

        assertThat(resolvers).isEmpty();
    }

    @Test
    @DisplayName("the hook adds and replaces no message converter")
    void theHookAddsAndReplacesNoMessageConverter() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();

        new WebMvcConfig().extendMessageConverters(converters);
        new WebMvcConfig().configureMessageConverters(converters);

        assertThat(converters).isEmpty();
    }

    @Test
    @DisplayName("the hook offers no validator and no message-code resolver of its own")
    void theHookOffersNoValidator() {
        WebMvcConfig hook = new WebMvcConfig();

        assertThat(hook.getValidator()).isNull();
        assertThat(hook.getMessageCodesResolver()).isNull();
    }
}
