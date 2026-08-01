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
 * <p>The auto-configured web tier is already what this module's published interface contract requires.
 * A 3270 mapset has no view template, static asset, upload, locale negotiation or theme, so none of the
 * framework knobs for those concepts has a legacy counterpart to be faithful to. What this hook exists
 * for is to record the four constraints a well-meant override would silently break.
 *
 * <p><strong>1. A configurer, never an explicit web-MVC enablement.</strong> The explicit enablement
 * annotation replaces the auto-configured web tier rather than adding to it, discarding the configured
 * message converters, the management endpoints' web exposure and the generated interface document at
 * once and without a compiler diagnostic. Implementing {@link WebMvcConfigurer} instead means this bean
 * is consulted <em>as part of</em> the auto-configuration, which is what leaves every default intact.
 *
 * <p><strong>2. Deterministic JSON, and it is fragile.</strong> Four settings in {@code application.yml}
 * <em>are</em> the request and response shape: plain decimal output, omission of null-valued properties,
 * ISO-8601 rendering of dates rather than epoch numbers, and lenient handling of unknown request
 * properties. The first is load-bearing rather than cosmetic, because every monetary and rate value here
 * is a fixed-scale decimal carried across from a zoned-decimal field and one rendered in scientific
 * notation is a corrupted value on the wire. All four are applied by the auto-configured serialiser.
 * Substituting it, re-deriving it through a builder customiser, replacing the JSON message converter or
 * overriding the converter list rather than extending it discards all four <em>silently</em>: the build
 * stays green, the endpoints keep answering, and the amounts are wrong.
 *
 * <p><strong>3. The management base path is resolved as text by three sibling files.</strong> The health
 * probe and the metrics scrape endpoint are spelled out literally in the {@code Dockerfile} health
 * check, in the {@code docker-compose.yml} health gate that dependent services wait on, and in the
 * {@code config/prometheus/prometheus.yml} scrape target, which is why the base path is stated
 * explicitly in {@code application.yml}. A servlet path prefix here, or a moved dispatcher mapping,
 * would break the container image and the metrics pipeline together.
 *
 * <p><strong>4. No request-binding type converter, and no framework-wide error format.</strong> Every
 * fixed-width identifier in the estate is a zero-padded external text field carried through as a
 * bounded string and never as a numeric type, precisely because a leading zero and an exact external
 * width are part of the contract, so a converter would be the one component able to trim or renumber a
 * value that must not be. And the error body is owned by
 * {@link com.carddemo.api.GlobalExceptionHandler} together with
 * {@link com.carddemo.api.dto.ErrorResponse}, whose per-field states distinguish a value that was
 * absent from one that was present and rejected; a standard problem-detail format flattens those two
 * states into one, which is why {@code application.yml} is deliberately silent on that switch and this
 * class does not enable from Java what configuration declined to enable.
 *
 * <p>Request instrumentation belongs to the module's observability configuration rather than here, so an
 * interceptor registered here would compete with it for ownership of the measurement surface. Verbatim
 * screen message text is resolved by {@link com.carddemo.service.MessageCatalogService} rather than per
 * request, so no locale resolver is registered either &mdash; one would introduce the possibility of
 * fixed external text varying.
 *
 * <p>A container-managed singleton with no field, no collaborator and no factory method, so it can never
 * participate in an initialisation cycle with the web infrastructure it configures and is safe for
 * unsynchronised concurrent use. Lite mode is stated explicitly to keep the class free of a runtime
 * subclass, which both permits {@code final} and leaves untouched the proxy and reflection surface the
 * low-level-code audit measures. Every method it exposes is an inherited default it does not override.
 * That last point is stated here only, rather than also on the constructor and again in its body as an
 * earlier revision did; see {@code docs/decision-log.md} DL-089.
 */
@Configuration(proxyBeanMethods = false)
public final class WebMvcConfig implements WebMvcConfigurer {

    /**
     * Creates the configurer singleton.
     *
     * <p>Declared explicitly so that the absence of collaborators is a visible property: nothing is
     * injected, so nothing can be reconfigured from elsewhere.</p>
     */
    public WebMvcConfig() {
    }
}
