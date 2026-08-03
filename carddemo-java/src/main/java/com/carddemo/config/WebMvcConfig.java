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

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
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
 * Substituting it, constructing a second one, replacing the JSON message converter or overriding the
 * converter list rather than extending it discards all four <em>silently</em>: the build stays green,
 * the endpoints keep answering, and the amounts are wrong.
 *
 * <p><strong>2a. Reading a request is strict, and one third of that strictness has to be written in
 * Java.</strong> A screen field is a fixed-width external text value, so a coercion into one produces a
 * corrupted value rather than a lenient reading. Two of the three coercions that must fail are
 * configuration properties and are declared in {@code application.yml}: a bare number binding an
 * enumerated component, which would select a constant by ordinal index that no 3270 attention
 * identifier ever had, and a fractional number binding an integral component, which would discard the
 * fraction and make {@code 7.9} indistinguishable from a {@code 7} the caller actually sent. The third -
 * a JSON number or boolean arriving where the contract declares text - has no configuration property in
 * this framework at all, and the mapper-level scalar-coercion switch does not govern it either, so it is
 * closed by {@link #strictScalarCoercionCustomizer()} below.
 *
 * <p>That method is the one builder interaction this class permits, and the distinction is exact.
 * {@link Jackson2ObjectMapperBuilderCustomizer} beans are consulted <em>by</em> the auto-configuration,
 * against the very builder that already carries the four settings above: the framework's own customiser
 * applies {@code spring.jackson.*} first and every additional customiser runs afterwards on the same
 * builder. Nothing is re-derived, no mapper is constructed here and no setting is restated, so the four
 * settings survive untouched - which {@code ApplicationJsonContractTest} asserts against a context that
 * includes this class, rather than leaving it to inspection. A customiser that instead <em>restated</em>
 * any of the four, or a bean that built its own mapper, would be the silent discard this point warns
 * about.
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
 * <p>A container-managed singleton with no field and no collaborator, and a single factory method that
 * injects nothing and closes over nothing, so it can never participate in an initialisation cycle with
 * the web infrastructure it configures and is safe for unsynchronised concurrent use. Lite mode is
 * stated explicitly to keep the class free of a runtime subclass, which both permits {@code final} and
 * leaves untouched the proxy and reflection surface the low-level-code audit measures. Every
 * {@link WebMvcConfigurer} method it exposes is an inherited default it does not override. That last
 * point is stated here only, rather than also on the constructor and again in its body as an earlier
 * revision did; see {@code docs/decision-log.md} DL-089.
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

    /**
     * Refuses a JSON number or boolean where the published contract declares text.
     *
     * <p>Every identifier, code, marker, date part and message in this module's request and response
     * types is an external text value of a declared width, because that is what a 3270 field is: an
     * account identifier is eleven characters whose leading zeros are part of the value, a card number is
     * sixteen, a report-type marker and a confirmation are one character each, and a date arrives as
     * separate two- and four-character parts. Left to its defaults this mapper accepts a JSON number in
     * any of those positions and stringifies it, so a body carrying {@code 1} would bind where
     * {@code "00000000001"} was required and the value would be silently wrong rather than rejected -
     * exactly the class of defect the fixed-width contract exists to prevent. A boolean is refused on the
     * same grounds: no screen field is two-state, not even the confirmation character, whose third
     * outcome is a value that is neither of the two accepted answers and which must stay reportable.
     *
     * <p>Three input shapes are therefore refused for text targets - an integral number, a fractional
     * number and a boolean. Nothing else is touched. A JSON string still binds, an absent property is
     * still absent, an explicit {@code null} is still {@code null}, and an empty string is still an empty
     * string, so a blank screen field submitted by an operator continues to arrive as a blank screen
     * field and reaches the ordered emptiness cascade that owns the resulting message. The refusal
     * surfaces as an ordinary deserialisation failure, which
     * {@link com.carddemo.api.GlobalExceptionHandler} already renders without echoing the rejected value.
     *
     * <p>This cannot be expressed in {@code application.yml}: the framework publishes properties for
     * Jackson's feature enumerations but none for its per-type coercion configuration, and the
     * mapper-level scalar-coercion switch governs coercions into enumerated and temporal types rather
     * than into text. The two coercions that <em>are</em> expressible as properties stay in
     * {@code application.yml} and are not restated here, so each setting has exactly one home.
     *
     * @return an additive customiser applied to the builder the auto-configuration already owns, after
     *     the framework's own customiser has applied every {@code spring.jackson.*} setting; it adds
     *     coercion rules and re-derives nothing
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictScalarCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            mapper.coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
    }

    /**
     * The validator every request-body and parameter constraint is checked by, with its message
     * rendering pinned to one locale so that a field error is the same bytes everywhere.
     *
     * <p><strong>What this fixes, and why it belongs in main rather than in a test.</strong> A
     * constraint failure reaches the client through {@code GlobalExceptionHandler}, which places
     * {@code ConstraintViolation.getMessage()} into {@code ErrorResponse.FieldError.message()}. That
     * text is not this module's own literal - it is the validation provider's bundled message, and the
     * provider resolves it against a locale. Left alone, the locale is whatever
     * {@code LocaleContextHolder} yields, which for a servlet request is the caller's
     * {@code Accept-Language} header and otherwise the JVM default. The same artifact given the same
     * input would then emit {@code "size must be between 0 and 8"} on one host and
     * {@code "boyut '0' ile '8' arasında olmalı"} on another, or on the same host to a different
     * caller. For a migration whose contract is stated in bytes that is a defect, not a courtesy:
     * nothing in the estate this module reproduces is multilingual, and no requirement asks for
     * negotiated message text.</p>
     *
     * <p>Decision {@code DL-118} records the rule. It lives in
     * {@link FixedLocaleMessageInterpolator} rather than here,
     * because the tests that assert rendered message text build their own provider and must be held to
     * the same statement. A test that pinned the locale its own way would pass while the application
     * stayed non-deterministic.</p>
     *
     * <p>This governs only how a message is <em>rendered</em>. Which constraints exist, which fields
     * carry them, and the two-state MISSING/INVALID decision derived from the message template are
     * untouched - the template is read before interpolation, so that decision never depended on a
     * locale in the first place.</p>
     *
     * @return the primary validator, rendering every constraint message in one fixed locale
     */
    @Bean
    public LocalValidatorFactoryBean defaultValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new FixedLocaleMessageInterpolator());
        return validator;
    }
}
