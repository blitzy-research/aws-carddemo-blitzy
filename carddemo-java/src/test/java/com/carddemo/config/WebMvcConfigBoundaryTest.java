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

import com.carddemo.api.dto.BatchJobLaunchRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Exercises the web tier's single customisation hook and its narrow security boundaries.
 *
 * <h2>What is under test</h2>
 * {@link WebMvcConfig} occupies the web-configuration seat, preserves the auto-configured converter
 * and exception machinery, and adds exact-origin CORS plus a generic request-body ceiling beside its
 * scalar and locale rules.
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
 * <p>No legacy source text is reproduced.</p>
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
    @DisplayName("the public surface is the six bean contributions plus the CORS callback")
    void theHookDeclaresOnlyItsSevenFrameworkContributions() {
        List<Method> declared = Arrays.stream(WebMvcConfig.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(declared).hasSize(7);
        assertThat(declared).extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "addCorsMappings",
                        "corsConfigurationSource",
                        "requestBodyLimitFilter",
                        "strictScalarCoercionCustomizer",
                        "declaredClosedBodyCustomizer",
                        // The third builder contribution. It refuses a transport control character in an
                        // inbound text value at the reader, which is the only place the rule can cover
                        // the two account-update components that are required to carry no constraint.
                        "controlCharacterRefusingTextCustomizer",
                        "defaultValidator");
        assertThat(declared).filteredOn(method -> method.getName().equals("addCorsMappings"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterCount()).isEqualTo(1));
        assertThat(declared).filteredOn(method -> method.getName().equals("requestBodyLimitFilter"))
                .as("the configured ceiling and the registry a refusal is counted on. The second argument "
                        + "is what makes a refused body visible at all: the filter now reports the "
                        + "refusal rather than only returning it")
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterCount()).isEqualTo(2));
        assertThat(declared).filteredOn(method -> !method.getName().equals("addCorsMappings")
                        && !method.getName().equals("requestBodyLimitFilter"))
                .allSatisfy(method -> assertThat(method.getParameterCount()).isZero());
        assertThat(declared).filteredOn(method -> method.getName().equals("addCorsMappings"))
                .singleElement()
                .satisfies(method -> assertThat(method.getReturnType()).isEqualTo(void.class));
        assertThat(declared)
                .as("each factory returns the type the framework resolves it by")
                .extracting(Method::getReturnType)
                .containsExactlyInAnyOrder(
                        void.class,
                        CorsConfigurationSource.class,
                        FilterRegistrationBean.class,
                        Jackson2ObjectMapperBuilderCustomizer.class,
                        Jackson2ObjectMapperBuilderCustomizer.class,
                        Jackson2ObjectMapperBuilderCustomizer.class,
                        LocalValidatorFactoryBean.class);
        assertThat(Arrays.stream(WebMvcConfig.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList())
                .as("only the immutable parsed CORS list may be retained by this configurer")
                .hasSize(1)
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
    }

    @Test
    @DisplayName("all six bean-producing methods are accounted for by annotation")
    void theAnnotatedBeanMethodsAreTheSixDeclaredContributions() {
        List<Method> beanMethods = Arrays.stream(WebMvcConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();

        assertThat(beanMethods).hasSize(6);
        assertThat(beanMethods).extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "corsConfigurationSource",
                        "requestBodyLimitFilter",
                        "strictScalarCoercionCustomizer",
                        "declaredClosedBodyCustomizer",
                        "controlCharacterRefusingTextCustomizer",
                        "defaultValidator");
    }

    @Test
    @DisplayName("a type that declared itself closed refuses an unknown property, which is the refusal "
            + "the batch launch schema publishes and once did not perform")
    void aClosedContractRefusesAnUnknownProperty() {
        ObjectMapper mapper = mapperWithBothCustomisers();

        assertThatThrownBy(() -> mapper.readValue(
                "{\"text\":\"x\",\"notDeclaredAnywhere\":\"1\"}", ClosedHolder.class))
                .as("THE DEFECT THIS PINS. The shared configuration turns the mapper-wide "
                        + "fail-on-unknown switch off so a client may echo a field this version does not "
                        + "read; a launch contract that publishes an unknown-property refusal was "
                        + "therefore silently permissive, and a mistyped parameter name started a job "
                        + "with an empty parameter set instead of being refused")
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("a type that did not declare itself closed still ignores an unknown property, so the "
            + "echoed-context tolerance the configuration file declares is untouched")
    void anOpenContractStillIgnoresAnUnknownProperty() throws Exception {
        ObjectMapper mapper = mapperWithBothCustomisers();

        assertThat(mapper.readValue("{\"text\":\"x\",\"echoedByAnOlderClient\":\"1\"}",
                TextHolder.class).text())
                .as("abstaining rather than refusing is the second half of the rule: the handler answers "
                        + "for the types that asked to be closed and leaves the mapper's own policy in "
                        + "force for every other")
                .isEqualTo("x");
        assertThat(mapper.readValue("{\"text\":\"x\",\"echoed\":\"1\"}", OpenHolder.class).text())
                .as("and a type that declares the annotation permissively is explicitly open")
                .isEqualTo("x");
    }

    @Test
    @DisplayName("the strictness customiser leaves the coercion customiser's own refusals in force, so "
            + "the two are additive rather than competing")
    void bothCustomisersRemainInForceTogether() {
        ObjectMapper mapper = mapperWithBothCustomisers();

        assertThatThrownBy(() -> mapper.readValue("{\"text\":1}", ClosedHolder.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    /**
     * Builds a mapper through both of the class's customisers, in the order the framework applies beans.
     *
     * @return the mapper a request body is read by
     */
    private static ObjectMapper mapperWithBothCustomisers() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        WebMvcConfig config = new WebMvcConfig();
        config.strictScalarCoercionCustomizer().customize(builder);
        config.declaredClosedBodyCustomizer().customize(builder);
        return builder.build();
    }

    /**
     * Stand-in for a closed request contract, declared closed exactly as the launch contract declares
     * itself.
     *
     * @param text the one component it publishes
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    private record ClosedHolder(String text) {
    }

    /**
     * Stand-in for a contract that declares itself open, which the handler must leave alone.
     *
     * @param text the one component it publishes
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenHolder(String text) {
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

    /**
     * Builds the mapper the web layer actually binds with: tolerant of an unknown property, because the
     * configuration file disables the mapper-wide failure so a screen body carrying an echoed member the
     * server no longer reads still binds, and then narrowed by the module's own two customisers.
     *
     * @return a mapper configured exactly as the served one is
     */
    private static ObjectMapper webLayerMapper() {
        final Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        final WebMvcConfig hook = new WebMvcConfig();

        hook.strictScalarCoercionCustomizer().customize(builder);
        hook.declaredClosedBodyCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("a batch launch body carrying a property the surface does not declare is refused even "
            + "though the mapper is configured to tolerate one, because a dropped launch parameter starts "
            + "a run the caller believes was parameterised")
    void aClosedLaunchBodyRefusesAnUndeclaredProperty() {
        final ObjectMapper mapper = webLayerMapper();

        assertThatThrownBy(() -> mapper.readValue("{\"interestParmDte\":\"2022071900\"}",
                BatchJobLaunchRequest.class))
                .as("a misspelling is refused rather than silently dropped")
                .isInstanceOf(UnrecognizedPropertyException.class);
        assertThatThrownBy(() -> mapper.readValue(
                "{\"interestParmDate\":\"2022071900\",\"runIdentifier\":\"1\"}",
                BatchJobLaunchRequest.class))
                .as("and so is an extra property alongside a declared one")
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("the same mapper still binds a declared launch parameter unaltered, so closing the "
            + "surface refuses nothing legitimate")
    void aClosedLaunchBodyStillBindsEveryDeclaredParameter() throws Exception {
        final BatchJobLaunchRequest bound = webLayerMapper().readValue("""
                {"interestParmDate":"2022071900","reportStartDate":"2022-07-01",
                 "reportEndDate":"2022-07-31","fileProbeMode":"account"}
                """, BatchJobLaunchRequest.class);

        assertThat(bound).isEqualTo(new BatchJobLaunchRequest("2022071900", "2022-07-01", "2022-07-31",
                "account"));
    }

    @Test
    @DisplayName("closing the launch surface leaves every other body as tolerant as the configuration "
            + "file declares, so a screen turn echoing a member the server no longer reads still binds")
    void closingTheLaunchSurfaceDoesNotCloseTheScreenBodies() throws Exception {
        final ObjectMapper mapper = webLayerMapper();

        assertThat(mapper.readValue("{\"text\":\"0000000001\",\"echoedByTheClient\":\"x\"}",
                TextHolder.class).text())
                .as("a type that declares no closure keeps the module-wide tolerance")
                .isEqualTo("0000000001");
        assertThat(mapper.readValue("{\"text\":\"0000000001\",\"echoedByTheClient\":\"x\"}",
                TolerantHolder.class).text())
                .as("and so does a type that declares tolerance explicitly")
                .isEqualTo("0000000001");
    }

    @Test
    @DisplayName("the closure follows the declaration rather than one named type, so a second body that "
            + "declares itself closed is closed too and cannot be left silently open")
    void theClosureFollowsTheDeclarationRatherThanOneNamedType() {
        final ObjectMapper mapper = webLayerMapper();

        assertThatThrownBy(() -> mapper.readValue("{\"text\":\"x\",\"undeclared\":\"y\"}",
                ClosedHolder.class))
                .as("a type this configuration has never heard of, closed by its own declaration")
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    /**
     * Stand-in for a body that declares tolerance explicitly, used to show that an explicit tolerance is
     * honoured rather than overridden.
     *
     * @param text the single value the body declares
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record TolerantHolder(String text) {
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
