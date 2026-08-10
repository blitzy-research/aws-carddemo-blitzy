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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
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
 * <p><strong>2b. A text value carrying a transport control character is refused at the reader.</strong>
 * Width was the only property checked on an inbound screen field, so an embedded newline bound, passed
 * every edit and was stored - and was met far later by the fixed-length record writer that cannot carry
 * it. {@link #controlCharacterRefusingTextCustomizer()} closes that at the boundary. It is expressed on
 * the reader rather than as a per-field constraint because two components of the account-update contract
 * are required to carry no constraint at all, and because the rule is a statement about the transport
 * rather than a new business edit: the estate's input device cannot transmit one of these codes, so
 * refusing them rejects nothing the original accepted.
 *
 * <p>Those three methods are the only builder interactions this class permits, and the distinction is
 * exact. {@link Jackson2ObjectMapperBuilderCustomizer} beans are consulted <em>by</em> the auto-configuration,
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

    /** Every REST operation in the module is beneath this one prefix. */
    public static final String API_PATH_PATTERN = "/api/**";

    /** Exact-origin allow-list key. An empty value is the shipped deny-all policy. */
    public static final String CORS_ALLOWED_ORIGINS_PROPERTY =
            "carddemo.web.cors.allowed-origins";

    /** Generic request-body ceiling enforced before a message converter receives the body. */
    public static final String MAX_REQUEST_BODY_SIZE_PROPERTY =
            "carddemo.web.max-request-body-size";

    /**
     * Counter of API requests refused for an oversized body, before any handler sees them.
     *
     * <p>The framework's own {@code http.server.requests} timer records the refusal too, now that the
     * filter sits behind the observation filter, but it cannot say <em>why</em> and it cannot name the
     * route: a request rejected before dispatch never reaches the handler mapping, so the framework's
     * {@code uri} dimension resolves to its unknown marker. This counter carries the reason the framework
     * has no way to know, and the two are read together.
     */
    public static final String REQUEST_REFUSED_METER_NAME = "carddemo.http.request.refused";

    /** Description of {@link #REQUEST_REFUSED_METER_NAME}, stated once. */
    private static final String REQUEST_REFUSED_METER_DESCRIPTION =
            "CardDemo API requests refused before dispatch because the body exceeded its configured"
                    + " ceiling, by how the excess was detected";

    /** Tag naming how the excess was detected. */
    public static final String TAG_REASON = "reason";

    /** Tag naming the request method, folded to a closed vocabulary so the series cannot fork. */
    public static final String TAG_METHOD = "method";

    /** {@link #TAG_REASON} value for a body whose declared {@code Content-Length} exceeded the bound. */
    public static final String REASON_DECLARED_LENGTH = "DECLARED_LENGTH";

    /** {@link #TAG_REASON} value for a chunked body found to exceed the bound while being read. */
    public static final String REASON_STREAMED_LENGTH = "STREAMED_LENGTH";

    /**
     * Where the request-body limit filter is registered in the servlet chain.
     *
     * <h2>Why it is not first, which is where it used to be</h2>
     *
     * <p>Registered at {@code Ordered.HIGHEST_PRECEDENCE} the filter ran ahead of the entire chain, and
     * two things followed from that. Spring Boot registers its server observation filter one step behind
     * highest precedence, so every refusal happened <em>outside</em> any observation: the response carried
     * status 413 and appeared in no request metric, opened no span, and had no correlation identifiers in
     * scope for a log line to carry. A caller could drive refusals indefinitely and leave nothing behind
     * to detect, which is a monitoring gap rather than merely a telemetry one. And it ran ahead of
     * authentication, so an anonymous request's body was read and buffered before anyone had established
     * that the caller was entitled to send one at all.
     *
     * <p>One step behind the security chain fixes both at once. The refusal is now inside the server
     * observation, so it is timed, counted and traced like any other response; and an unauthenticated
     * request to a protected route is answered by the security chain before this filter allocates
     * anything for it.
     *
     * <h2>Why moving behind authentication is safe here, which it would not universally be</h2>
     *
     * <p>Placing a body bound after authentication is only sound if nothing between the two can read an
     * unbounded body first. Three facts establish that for this module, and all three would have to
     * remain true for the ordering to stay correct.
     *
     * <ul>
     *   <li>No Spring Security filter in either published chain reads the body. Authentication is a bearer
     *       token in a header, form login is not configured, and CSRF is disabled on both chains - so
     *       nothing consults a request parameter, which is the only way a security filter would end up
     *       parsing a body.</li>
     *   <li>The one filter ahead of this point that <em>can</em> read a body is Boot's form-content
     *       filter, and it acts only on form-encoded content. Its parse is bounded by the container at
     *       {@code server.tomcat.max-http-form-post-size}, which this module configures to the same
     *       figure as this filter's own default ceiling - so that path is bounded to the same size rather
     *       than being unbounded.</li>
     *   <li>The container bounds request headers and swallowed bytes independently, so the pre-filter
     *       surface is finite in every dimension a caller controls.</li>
     * </ul>
     *
     * <p>The value is derived from the framework constant rather than written as a literal, so that a
     * change to Boot's default security-filter order moves this filter with it instead of silently
     * reordering the two.
     *
     * <p>See {@code docs/decision-log.md} entry DL-307.
     */
    static final int REQUEST_BODY_LIMIT_FILTER_ORDER = SecurityProperties.DEFAULT_FILTER_ORDER + 1;

    private static final Logger LOGGER = LoggerFactory.getLogger(WebMvcConfig.class);

    private static final int MAX_CONFIGURED_ORIGINS = 16;

    private static final int MAX_ORIGIN_LENGTH = 2_048;

    private static final long MAX_SUPPORTED_REQUEST_BODY_BYTES = 16L * 1_024L * 1_024L;

    private static final long CORS_PREFLIGHT_MAX_AGE_SECONDS = 600L;

    private static final List<String> CORS_ALLOWED_METHODS =
            List.of(HttpMethod.GET.name(), HttpMethod.POST.name());

    private static final List<String> CORS_ALLOWED_HEADERS =
            List.of(HttpHeaders.ACCEPT, HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE);

    private static final List<String> CORS_EXPOSED_HEADERS =
            List.of(HttpHeaders.AUTHORIZATION);

    private final List<String> allowedOrigins;

    /**
     * Creates the configurer singleton.
     *
     * <p>Declared explicitly so that the absence of collaborators is a visible property: nothing is
     * injected, so nothing can be reconfigured from elsewhere.</p>
     */
    public WebMvcConfig() {
        this("");
    }

    /**
     * Creates the configurer from a comma-separated exact-origin allow-list.
     *
     * @param configuredAllowedOrigins exact HTTP or HTTPS origins; empty means deny all
     */
    @Autowired
    public WebMvcConfig(
            @Value("${" + CORS_ALLOWED_ORIGINS_PROPERTY + ":}")
            final String configuredAllowedOrigins) {
        this.allowedOrigins = parseAllowedOrigins(configuredAllowedOrigins);
    }

    /**
     * Registers the one cross-origin policy for every application endpoint.
     */
    @Override
    public void addCorsMappings(final CorsRegistry registry) {
        Objects.requireNonNull(registry, "registry").addMapping(API_PATH_PATTERN)
                .allowedOrigins(this.allowedOrigins.toArray(String[]::new))
                .allowedMethods(CORS_ALLOWED_METHODS.toArray(String[]::new))
                .allowedHeaders(CORS_ALLOWED_HEADERS.toArray(String[]::new))
                .exposedHeaders(CORS_EXPOSED_HEADERS.toArray(String[]::new))
                .allowCredentials(false)
                .maxAge(CORS_PREFLIGHT_MAX_AGE_SECONDS);
    }

    /**
     * Supplies the same exact-origin policy to Spring Security.
     *
     * @return a path-scoped policy source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(API_PATH_PATTERN, corsConfiguration());
        return source;
    }

    /**
     * Registers the bounded request-body reader behind observation and behind authentication.
     *
     * <p>The slot is {@link #REQUEST_BODY_LIMIT_FILTER_ORDER} and the reasoning for it is developed
     * there, because the choice of position is the whole substance of this registration.
     *
     * @param configuredMaxRequestBodySize the positive bounded size from configuration
     * @param meterRegistry                where a refusal is counted; must not be {@code null}
     * @return the ordered and path-scoped filter registration
     */
    @Bean
    public FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
            @Value("${" + MAX_REQUEST_BODY_SIZE_PROPERTY + ":64KB}")
            final String configuredMaxRequestBodySize,
            final MeterRegistry meterRegistry) {
        final FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>();
        registration.setFilter(
                new RequestBodyLimitFilter(configuredMaxRequestBodySize, meterRegistry));
        registration.setName("requestBodyLimitFilter");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(REQUEST_BODY_LIMIT_FILTER_ORDER);
        return registration;
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
     * Makes a body that declares itself closed genuinely closed, which the declaration cannot achieve
     * alone.
     *
     * <p><strong>The defect this closes.</strong> The batch-launch body is annotated
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)} and its schema states that an unknown property
     * is refused. Neither is true on its own: {@code @JsonIgnoreProperties(ignoreUnknown = false)} does
     * not <em>enable</em> the check, it merely declines to suppress it, and the decision then falls to
     * the mapper's {@code FAIL_ON_UNKNOWN_PROPERTIES} feature - which {@code application.yml} sets to
     * {@code false} for the whole application. A misspelled property therefore bound silently, and a
     * caller who wrote {@code interestParmDte} launched a job with no parameter at all rather than being
     * told they had made a typing error. On a surface whose nine operations include one that clears the
     * transaction master, silence is the wrong answer.
     *
     * <p><strong>Why the global setting stays as it is.</strong> Tolerating unknown properties is
     * deliberate for the screen contracts: every one of them is derived from a 3270 map and a client
     * echoing a whole screen back may legitimately carry a member a given operation does not read. That
     * tolerance is a decision about the screen surface and is not withdrawn here. What is withdrawn is
     * its application to the one body that is not a screen at all - a batch launch is an operational
     * instruction with a closed parameter set, and every name in it is either one the addressed job
     * declares or a mistake.
     *
     * <p><strong>Why a problem handler and not a per-type config override or a second mapper.</strong>
     * Three mechanisms look like they would do this and two of them do not.
     * {@code configOverride(...).setIgnorals(...)} sets the same switch the record's annotation already
     * sets, so it changes nothing; the annotation was never the missing piece. Jackson publishes
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} as a mapper-wide feature with no per-type form, so it cannot be
     * narrowed to one body. A second {@code ObjectMapper} would work and was rejected: it would have to
     * be kept in step by hand with every {@code spring.jackson.*} setting - the plain-decimal generator,
     * the non-null inclusion, the scalar coercions configured immediately above - and the first setting
     * that drifted would silently change how a batch body reads. A problem handler is consulted
     * <em>before</em> the mapper-wide feature decides, which is exactly the seam needed: one mapper, one
     * stated exception, and every other type still bound by the global tolerance.
     *
     * <p><strong>Why the rule reads the declaration rather than naming the type.</strong> A handler that
     * compared the target against one named class would leave a second closed body silently open, and
     * would make this package depend on the boundary package the layering rule keeps it out of. Reading
     * the type's own {@code @JsonIgnoreProperties} declaration avoids both: closure becomes a property a
     * body states about itself, next to the schema text that promises it, and this configuration honours
     * that statement without knowing which types make it. A type that declares nothing is untouched, and
     * so is a type that declares {@code ignoreUnknown = true}.
     *
     * <p>The refusal arrives as an ordinary deserialisation failure, so it reaches
     * {@link com.carddemo.api.GlobalExceptionHandler}'s unreadable-body arm and is answered {@code 400}
     * with the neutral summary and no echo of the rejected name. The published schema is unchanged: the
     * annotation on the record already says the body is closed, and this is what makes that statement
     * true.
     *
     * @return an additive customiser that closes every self-declared closed body to unknown properties
     *     and changes the binding of no other type
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer declaredClosedBodyCustomizer() {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(
                new DeserializationProblemHandler() {
                    @Override
                    public boolean handleUnknownProperty(final DeserializationContext context,
                                                         final JsonParser parser,
                                                         final JsonDeserializer<?> deserializer,
                                                         final Object beanOrClass,
                                                         final String propertyName)
                            throws IOException {
                        // The framework passes either the target type or a partially built instance of
                        // it, depending on where in the bind the unknown name was met.
                        final Class<?> target = (beanOrClass instanceof Class<?> declared)
                                ? declared
                                : beanOrClass.getClass();
                        final JsonIgnoreProperties declaration =
                                target.getAnnotation(JsonIgnoreProperties.class);
                        if (declaration != null && !declaration.ignoreUnknown()) {
                            throw UnrecognizedPropertyException.from(
                                    parser, target, propertyName, null);
                        }
                        // Every other type keeps the module-wide tolerance: false means "not handled
                        // here", which returns the decision to the global feature.
                        return false;
                    }
                }));
    }

    /**
     * Refuses a transport control character anywhere in an inbound text value, before it is bound.
     *
     * <p><strong>The defect this closes.</strong> Every screen field on this surface is external text of
     * a declared width, and the widths were the only thing checked. A body carrying
     * {@code "VALID\nPOISON"} in a transaction description or an address line therefore bound, passed
     * every field edit, and was written to the store - because a newline is a perfectly ordinary
     * character to a width check. The byte was met much later and much further away: the statement
     * generator moves a stored field into a fixed-length record and refuses a control byte there,
     * because one embedded newline splits a hundred-byte record into two and destroys the byte-parity
     * contract of the whole artefact. So a value accepted at the boundary failed a batch run hours
     * afterwards, at a point where the only remedy is to correct stored data. The guard belongs where
     * the value arrives.
     *
     * <p><strong>Why this is a transport rule and not a field edit.</strong> The estate's input device
     * cannot transmit one of these bytes: a 3270 field carries displayable characters, and the control
     * codes are the datastream's own framing rather than field content. Refusing them therefore rejects
     * nothing the legacy system would have accepted, which is what makes the rule a statement about the
     * transport rather than a new business validation. That distinction is load-bearing here. Two
     * components of the account-update contract - the middle name and the second address line - are
     * deliberately unannotated, because the program they reproduce codes no edit for either and any
     * constraint on them would refuse input the original accepted. A guard expressed as a per-field
     * annotation could not cover those two without breaking that requirement, and a guard that skipped
     * them would leave the exposure open on exactly the fields with no other check. Placing the rule on
     * the reader covers every text value on every body uniformly, adds no constraint to any contract
     * type, and leaves the published schema unchanged.
     *
     * <p><strong>What is refused, and what is deliberately not.</strong> The refusal is the C0 range
     * {@code U+0000}-{@code U+001F}, the delete character {@code U+007F}, and the C1 range
     * {@code U+0080}-{@code U+009F}. Nothing else is touched: a printable character binds, an empty
     * string is still an empty string, a blank field is still blank, an absent property is still
     * absent, and an explicit {@code null} is still {@code null} - so the ordered emptiness cascades
     * that own the resulting screen messages are reached exactly as before. Case, spacing, punctuation
     * and every non-ASCII printable character are carried through untouched, because this is a refusal
     * and never a rewrite: a rule that silently stripped a byte would shift every byte after it and
     * would be a byte-parity defect of its own.
     *
     * <p><strong>What this does not claim to close.</strong> Printable markup remains storable and is
     * still emitted byte for byte into the HTML statement artefact, because that artefact is compared
     * byte for byte against the emitting program's own output and escaping it would fail that
     * comparison. That residual is a property of the legacy design, is stated on
     * {@link com.carddemo.util.StatementHtmlTemplates} where the emission happens, and is recorded in
     * {@code docs/decision-log.md} entries DL-209 and DL-267. What this bean closes is the control-byte path, which is the half
     * of the exposure that produces a failing batch and a corrupted fixed-length record rather than a
     * rendering concern.
     *
     * <p>The refusal is raised as an ordinary mapping failure against the resolved property path, so
     * {@link com.carddemo.api.GlobalExceptionHandler} answers {@code 400} naming the offending declared
     * property with state {@code INVALID} and <strong>never echoes the rejected value</strong> - the
     * same treatment a wrong-shaped scalar already receives.
     *
     * @return an additive customiser that installs one text reader over the builder the
     *     auto-configuration already owns; it changes no {@code spring.jackson.*} setting and
     *     re-derives nothing
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer controlCharacterRefusingTextCustomizer() {
        final SimpleModule module = new SimpleModule("carddemo-control-character-refusal");
        module.addDeserializer(String.class, new ControlCharacterRefusingStringDeserializer());
        return builder -> builder.postConfigurer(mapper -> mapper.registerModule(module));
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

    private CorsConfiguration corsConfiguration() {
        final CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(this.allowedOrigins);
        configuration.setAllowedMethods(CORS_ALLOWED_METHODS);
        configuration.setAllowedHeaders(CORS_ALLOWED_HEADERS);
        configuration.setExposedHeaders(CORS_EXPOSED_HEADERS);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(CORS_PREFLIGHT_MAX_AGE_SECONDS);
        return configuration;
    }

    private static List<String> parseAllowedOrigins(final String configuredAllowedOrigins) {
        Objects.requireNonNull(configuredAllowedOrigins, "configuredAllowedOrigins");
        final Set<String> distinctOrigins = new LinkedHashSet<>();
        for (final String candidate : StringUtils.commaDelimitedListToStringArray(
                configuredAllowedOrigins)) {
            final String origin = candidate.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if (distinctOrigins.size() >= MAX_CONFIGURED_ORIGINS) {
                throw new IllegalArgumentException(
                        "At most " + MAX_CONFIGURED_ORIGINS + " CORS origins may be configured");
            }
            distinctOrigins.add(requireExactHttpOrigin(origin));
        }
        return List.copyOf(distinctOrigins);
    }

    private static String requireExactHttpOrigin(final String origin) {
        if (origin.length() > MAX_ORIGIN_LENGTH) {
            throw new IllegalArgumentException(
                    "A CORS origin may contain at most " + MAX_ORIGIN_LENGTH + " characters");
        }
        if ("*".equals(origin) || "null".equalsIgnoreCase(origin)) {
            throw new IllegalArgumentException("CORS origins must be exact HTTP or HTTPS origins");
        }

        final URI parsed;
        try {
            parsed = new URI(origin);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "CORS origins must be syntactically valid HTTP or HTTPS origins", exception);
        }

        final String scheme = parsed.getScheme();
        final boolean httpScheme = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);
        final int port = parsed.getPort();
        if (!httpScheme
                || !StringUtils.hasText(parsed.getHost())
                || port == 0
                || port > 65_535
                || parsed.getUserInfo() != null
                || StringUtils.hasText(parsed.getRawPath())
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "CORS origins must contain only an HTTP or HTTPS scheme, host, and optional port");
        }
        return origin;
    }

    private static int requireRequestBodyBytes(final String configuredSize) {
        Objects.requireNonNull(configuredSize, "maxRequestBodySize");
        final long bytes;
        try {
            bytes = DataSize.parse(configuredSize.trim()).toBytes();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Request body size must be a valid data-size value", exception);
        }
        if (bytes <= 0 || bytes > MAX_SUPPORTED_REQUEST_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "Request body size must be between 1 byte and "
                            + MAX_SUPPORTED_REQUEST_BODY_BYTES + " bytes");
        }
        return Math.toIntExact(bytes);
    }

    /**
     * Reader that binds an inbound text value only when it carries no transport control character.
     *
     * <p>Every accepted value is handed on <strong>byte for byte</strong>: the reader delegates the read
     * itself to the mapper's own text reader and then inspects the result, so nothing is trimmed,
     * folded, normalised, stripped or re-encoded on the way through. The single decision it makes is to
     * bind or to refuse, which is the only decision that cannot disturb a fixed-width contract.
     *
     * <p>The refusal is a {@link MismatchedInputException} raised through the parser, which is what
     * places the resolved property path on it. That path is what lets the module's error boundary name
     * the offending property; the rejected value is never attached and never logged here.
     *
     * <p>Stateless and therefore safe for concurrent use, which is required of a reader the mapper
     * shares across every request.
     */
    static final class ControlCharacterRefusingStringDeserializer extends JsonDeserializer<String> {

        /** Highest code point in the C0 control range. */
        private static final char C0_RANGE_END = '\u001F';

        /** The delete character, which is a control code that sits outside the C0 range. */
        private static final char DELETE_CHARACTER = '\u007F';

        /** Lowest code point in the C1 control range, the first code point above delete. */
        private static final char C1_RANGE_START = '\u0080';

        /** Highest code point in the C1 control range. */
        private static final char C1_RANGE_END = '\u009F';

        /**
         * Summary the refusal carries, which names the rule and never the value.
         *
         * <p>Deliberately free of the offending character and of its position. The boundary answers with
         * the module's own neutral summary and the property name, and a message that quoted the byte
         * would put caller-supplied content into a diagnostic that is written to a log.
         */
        private static final String REFUSAL =
                "a text value must not contain a transport control character";

        /** Creates the reader. */
        ControlCharacterRefusingStringDeserializer() {
            // Intentionally empty: the reader holds no state.
        }

        /**
         * Binds one text value, refusing it when it carries a control character.
         *
         * @param  parser  the parser positioned on the value, never {@code null} when invoked by the
         *                 mapper
         * @param  context the deserialization context, never {@code null} when invoked by the mapper
         * @return the value exactly as the mapper's own text reader produced it, or {@code null} where
         *         that reader produces {@code null}
         * @throws IOException if the underlying read fails, or the value carries a control character
         */
        @Override
        public String deserialize(final JsonParser parser, final DeserializationContext context)
                throws IOException {
            final String value = StringDeserializer.instance.deserialize(parser, context);
            if (value == null) {
                return null;
            }
            for (int position = 0; position < value.length(); position++) {
                if (isTransportControl(value.charAt(position))) {
                    throw MismatchedInputException.from(parser, String.class, REFUSAL);
                }
            }
            return value;
        }

        /**
         * Reports whether one character is a transport control code rather than field content.
         *
         * @param  character the character to classify
         * @return {@code true} for the C0 range, the delete character and the C1 range
         */
        private static boolean isTransportControl(final char character) {
            return character <= C0_RANGE_END
                    || character == DELETE_CHARACTER
                    || character >= C1_RANGE_START && character <= C1_RANGE_END;
        }
    }

    /**
     * Filter that rejects both declared-length and chunked bodies above the same finite limit.
     *
     * <p>A refusal is now reported as well as returned. It is counted on
     * {@link #REQUEST_REFUSED_METER_NAME} and logged once at warning level, and because the filter runs
     * inside the server observation it also appears in the framework's request timer and carries the
     * correlation identifiers that were previously out of scope.
     *
     * <p><strong>The diagnostic names no unsanitised caller content.</strong> Everything a caller controls
     * that reaches the log passes through a rule stated at the method that applies it: the method is
     * folded to a closed vocabulary, and the path is stripped of its context, bounded in length and
     * reduced to a conservative character set. That is not decoration - the request line is
     * attacker-controlled text on an unauthenticated path, so an unfiltered copy of it in a log is a
     * forged-record vector, and an unbounded one is a way to write a great deal of someone else's text
     * into an operator's log by sending one request.
     */
    static final class RequestBodyLimitFilter extends OncePerRequestFilter {

        /** Longest sanitised request path a refusal diagnostic will carry. */
        private static final int MAX_LOGGED_PATH_LENGTH = 120;

        /** Method names named as themselves; anything else is folded to one marker. */
        private static final Set<String> LOGGED_METHODS = Set.of(
                "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

        /** Replacement for a method outside {@link #LOGGED_METHODS}. */
        private static final String OTHER_METHOD = "OTHER";

        /** Substitute for any path character outside the conservative set. */
        private static final char PATH_SUBSTITUTE = '_';

        private final int maxRequestBodyBytes;

        private final MeterRegistry meterRegistry;

        RequestBodyLimitFilter(final String configuredMaxRequestBodySize,
                final MeterRegistry meterRegistry) {
            this.maxRequestBodyBytes = requireRequestBodyBytes(configuredMaxRequestBodySize);
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        }

        @Override
        protected boolean shouldNotFilter(final HttpServletRequest request) {
            final String apiPrefix = request.getContextPath() + "/api/";
            return !request.getRequestURI().startsWith(apiPrefix);
        }

        @Override
        protected void doFilterInternal(final HttpServletRequest request,
                final HttpServletResponse response, final FilterChain filterChain)
                throws ServletException, IOException {
            final long declaredLength = request.getContentLengthLong();
            if (declaredLength > this.maxRequestBodyBytes) {
                reject(request, response, REASON_DECLARED_LENGTH, declaredLength);
                return;
            }

            final byte[] body = request.getInputStream().readNBytes(this.maxRequestBodyBytes + 1);
            if (body.length > this.maxRequestBodyBytes) {
                // The read stops one byte past the ceiling, so this figure is a floor on the body's size
                // rather than its size. Reported as such rather than as a measurement.
                reject(request, response, REASON_STREAMED_LENGTH, body.length);
                return;
            }
            filterChain.doFilter(new BufferedHttpServletRequest(request, body), response);
        }

        /**
         * Answers 413, counts the refusal and records one bounded diagnostic.
         *
         * <p>The response is byte-for-byte what it always was. Nothing about the external contract moves
         * here: the status, the media type, the charset and the message body are unchanged, because a
         * caller must not be able to tell from the answer that the refusal is now being recorded.
         *
         * @param  request        the refused request, read only for its method and path
         * @param  response       the response to complete
         * @param  reason         the {@link #TAG_REASON} value
         * @param  observedBytes  the declared length, or the number of bytes read before stopping
         * @throws IOException if the response cannot be written
         */
        private void reject(final HttpServletRequest request, final HttpServletResponse response,
                final String reason, final long observedBytes) throws IOException {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"message\":\"Request could not be processed\",\"fieldErrors\":[]}");

            final String method = loggedMethod(request.getMethod());
            count(reason, method);
            LOGGER.warn("Refused an API request whose body exceeded its ceiling: method={} path={}"
                            + " limitBytes={} observedBytes={} reason={}",
                    method, loggedPath(request), this.maxRequestBodyBytes, observedBytes, reason);
        }

        /**
         * Counts one refusal, registering the counter on first use for that pair of tags.
         *
         * <p>A meter fault is absorbed at debug. This runs while completing a response that has already
         * been decided, and a telemetry problem must not become a second, different failure for the
         * caller.
         *
         * @param reason the {@link #TAG_REASON} value
         * @param method the folded {@link #TAG_METHOD} value
         */
        private void count(final String reason, final String method) {
            try {
                Counter.builder(REQUEST_REFUSED_METER_NAME)
                        .description(REQUEST_REFUSED_METER_DESCRIPTION)
                        .tag(TAG_REASON, reason)
                        .tag(TAG_METHOD, method)
                        .register(this.meterRegistry)
                        .increment();
            } catch (final RuntimeException meterFailure) {
                LOGGER.debug("Could not count a refused API request with reason {}: failureType={}",
                        reason, meterFailure.getClass().getSimpleName());
            }
        }

        /**
         * @param  method the request method as the container reported it, possibly {@code null}
         * @return the same name when it is one of the eight standard methods, otherwise one marker, so
         *         that a caller inventing method names cannot fork the metric series
         */
        private static String loggedMethod(final String method) {
            return method != null && LOGGED_METHODS.contains(method) ? method : OTHER_METHOD;
        }

        /**
         * Reduces the request path to something safe to write into a log.
         *
         * <p>Three reductions, each closing a distinct problem: the context path is removed because it is
         * deployment configuration rather than information about the request; the result is truncated,
         * because a container accepts a request line far longer than anything worth logging and an
         * attacker choosing its length chooses how much of an operator's log to occupy; and every
         * character outside letters, digits and the four path punctuation marks is replaced, which
         * removes carriage returns and line feeds and therefore the ability to forge a second log record
         * inside the first.
         *
         * @param  request the refused request
         * @return a bounded path over a conservative character set, never {@code null}
         */
        private static String loggedPath(final HttpServletRequest request) {
            final String uri = request.getRequestURI();
            if (uri == null) {
                return "";
            }
            final String contextPath = request.getContextPath();
            final String relative = contextPath != null && !contextPath.isEmpty()
                    && uri.startsWith(contextPath)
                    ? uri.substring(contextPath.length())
                    : uri;
            final int length = Math.min(relative.length(), MAX_LOGGED_PATH_LENGTH);
            final StringBuilder safe = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                final char character = relative.charAt(index);
                if (character >= 'A' && character <= 'Z'
                        || character >= 'a' && character <= 'z'
                        || character >= '0' && character <= '9'
                        || character == '/' || character == '.'
                        || character == '_' || character == '-') {
                    safe.append(character);
                } else {
                    safe.append(PATH_SUBSTITUTE);
                }
            }
            return safe.toString();
        }
    }

    /**
     * Repeatable servlet request over the one bounded copy read by {@link RequestBodyLimitFilter}.
     */
    private static final class BufferedHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedHttpServletRequest(final HttpServletRequest request, final byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return this.body.length;
        }

        @Override
        public long getContentLengthLong() {
            return this.body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BufferedServletInputStream(this.body);
        }

        @Override
        public BufferedReader getReader() {
            final String encoding = getCharacterEncoding();
            final java.nio.charset.Charset charset = encoding == null
                    ? StandardCharsets.UTF_8
                    : java.nio.charset.Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /**
     * Servlet input stream over a private bounded byte array.
     */
    private static final class BufferedServletInputStream extends ServletInputStream {

        private final java.io.ByteArrayInputStream delegate;

        private BufferedServletInputStream(final byte[] body) {
            this.delegate = new java.io.ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return this.delegate.read();
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) {
            return this.delegate.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return this.delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            Objects.requireNonNull(readListener, "readListener");
            try {
                if (!isFinished()) {
                    readListener.onDataAvailable();
                }
                if (isFinished()) {
                    readListener.onAllDataRead();
                }
            } catch (IOException exception) {
                readListener.onError(exception);
            }
        }
    }
}
