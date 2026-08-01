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

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The web tier's single customisation hook, and it customises nothing.
 *
 * <p>This class contributes no override to the web framework's configuration. That is its purpose, not
 * an unfinished state: the auto-configured web tier is already exactly what this module's published
 * interface contract requires. A 3270 mapset has no view template, no static asset, no upload, no locale
 * negotiation and no theme, so none of the framework knobs that exist for those concepts has a legacy
 * counterpart to be faithful to. What the hook exists for is to record, in a reviewable place, the three
 * constraints that a well-meant override would silently break.
 *
 * <p><strong>1. This is a configurer, never an explicit web-MVC enablement.</strong> The class
 * implements {@link WebMvcConfigurer} and is <strong>not</strong> annotated with the framework's
 * explicit web-MVC enablement annotation. That annotation does not add configuration on top of the
 * auto-configured web tier; it replaces it, switching the auto-configuration off entirely. Doing so here
 * would discard the configured message converters, the management endpoints' web exposure, and the
 * generated interface document and its viewer, all at once and without a single compiler diagnostic.
 * Implementing the interface instead means this bean is collected by the auto-configuration and
 * consulted <em>as part of</em> it, which is what leaves every default intact.
 *
 * <p><strong>2. Deterministic JSON, and it is fragile.</strong> {@code application.yml} configures the
 * serialisation layer, and one setting there is load-bearing rather than cosmetic: plain decimal output.
 * Every monetary and rate value in this module is a fixed-scale decimal carried across from a
 * zoned-decimal field, and a decimal rendered in scientific notation is a corrupted value on the wire,
 * failing byte-equivalence and interface-contract verification in one stroke. Alongside it sit the
 * omission of null-valued properties, ISO-8601 rendering of dates rather than epoch numbers, and lenient
 * handling of unknown request properties; those four settings <em>are</em> the request and response
 * shape. All four are applied by the auto-configured serialiser and survive only as long as it and its
 * message converter do. Substituting either &mdash; a hand-built serialiser bean, a builder customiser
 * that re-derives one, a replacement JSON message converter, or overriding the converter list rather
 * than extending it &mdash; discards all four <em>silently</em>: the build stays green, the endpoints
 * keep answering, and the amounts are wrong. Hence no serialiser of any kind here, and the converter
 * list is left untouched.
 *
 * <p><strong>3. The management base path is resolved as text by three sibling files.</strong> The health
 * probe and the metrics scrape endpoint are spelled out literally in the {@code HEALTHCHECK} of the
 * module {@code Dockerfile}, in the health gate that dependent services in {@code docker-compose.yml}
 * wait on, and in the scrape target of {@code config/prometheus/prometheus.yml}, which is why the base
 * path is stated explicitly in {@code application.yml}. Introducing a servlet path prefix here, or moving
 * the dispatcher's mapping, would relocate those endpoints and break the container image and the metrics
 * pipeline together.
 *
 * <p>Two narrower rules follow from the contract rather than from the framework. No type converter is
 * registered for request binding: every fixed-width identifier in the estate is a zero-padded external
 * text field carried through as a bounded string and never as a numeric type, precisely because a
 * leading zero and an exact external width are part of the contract, so a converter would be the one
 * component able to trim or renumber a value that must not be trimmed or renumbered. And no exception
 * resolver, framework advice base class or standard problem-detail format is enabled: the error body is
 * owned by {@link com.carddemo.api.GlobalExceptionHandler} together with
 * {@link com.carddemo.api.dto.ErrorResponse}, whose per-field states distinguish a value that was absent
 * from one that was present and rejected, and a framework-wide error format flattens those two states
 * into one. {@code application.yml} is deliberately silent on the problem-detail switch for the same
 * reason, and this class does not enable from Java what configuration declined to enable.
 *
 * <p>Request instrumentation is likewise not added here: the meters and the trace bridge belong to the
 * module's observability configuration, which is not delivered yet, and an interceptor registered here
 * would either duplicate that instrumentation or compete with it for ownership of the measurement
 * surface. Verbatim screen message text is resolved by
 * {@link com.carddemo.service.MessageCatalogService} rather than per request, so no locale resolver is
 * registered either &mdash; one would introduce the possibility of fixed external text varying.
 *
 * <p><strong>Bean semantics and thread safety.</strong> A container-managed singleton, collected by the
 * web auto-configuration along with every other configurer bean. It declares no factory method, so lite
 * mode is stated explicitly: a full-mode class is subclassed at runtime to intercept factory-method
 * calls, which this class has no need of, which would forbid it from being {@code final}, and which would
 * add to the runtime proxy and reflection surface the low-level-code audit measures. It has no field, no
 * collaborator and no dependency on any other type in this package, so it can never participate in an
 * initialisation cycle with the web infrastructure it configures, and the singleton is safe for
 * unsynchronised concurrent use. Every method it exposes is an inherited default it does not override,
 * and each of those either does nothing or reports that it has no contribution to make.
 */
@Configuration(proxyBeanMethods = false)
public final class WebMvcConfig implements WebMvcConfigurer {

    /**
     * Creates the configurer singleton.
     *
     * <p>Declared explicitly, rather than left implicit, so that the absence of collaborators is a
     * visible and reviewable property: a reader can see at a glance that nothing is injected and
     * therefore that nothing can be reconfigured from elsewhere.</p>
     */
    public WebMvcConfig() {
        // Nothing to inject and nothing to initialise. Every method of the implemented interface is
        // inherited as its own no-op default, which is precisely the intended configuration: the
        // auto-configured web tier is left exactly as it stands.
    }
}
