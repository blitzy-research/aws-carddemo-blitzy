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

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Exercises the web tier's single customisation hook, which deliberately customises nothing.
 *
 * <h2>What is under test</h2>
 * {@link WebMvcConfig} occupies the web-configuration seat and contributes no override, so that every
 * adjustment which was considered and declined is recorded in one reviewable place rather than left
 * for the next reader to rediscover. The tests assert exactly that: the class is a configurer the
 * framework will collect, it is registered unproxied, and it contributes nothing.
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
@DisplayName("WebMvcConfig - the web hook that deliberately adds nothing")
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
    @DisplayName("the hook declares no factory method, so it contributes no bean")
    void theHookDeclaresNoFactoryMethod() {
        assertThat(WebMvcConfig.class.getDeclaredMethods())
                .filteredOn(method -> !method.isSynthetic())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .isEmpty();
    }

    @Test
    @DisplayName("the hook declares no bean-producing method by annotation either")
    void theHookDeclaresNoAnnotatedBeanMethod() {
        assertThat(WebMvcConfig.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Bean.class));
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
