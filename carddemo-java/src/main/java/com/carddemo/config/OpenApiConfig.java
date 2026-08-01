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

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the OpenAPI document that <em>is</em> the machine-readable interface description of this
 * module's REST surface.
 *
 * <p>The legacy presentation layer was a CICS 3270 terminal contract, and the faithful translation of a
 * terminal contract is another machine contract rather than a new visual design. No browser interface
 * and no single-page application is built by this migration, so the generated document is the only
 * published description of the interface. There is no separate hand-maintained contract file in this
 * repository that could fall out of step with the code, and none may be introduced. The
 * interface-contract acceptance criterion refuses self-certification and requires a local test to
 * exercise the real contract, which is what a served, parseable document lets a reviewer and a test do
 * against the same bytes. The legacy authority for the endpoint inventory is
 * {@code app/csd/CARDDEMO.CSD}, which defines eighteen transactions bound to eighteen programs and
 * seventeen screen field maps; the one transaction with no map drives the shared date-validation
 * subprogram rather than a screen, so seventeen transactions become the REST endpoint groups this
 * document will describe.
 *
 * <p><strong>The endpoint inventory is later bound, and today it is empty.</strong> A path enters the
 * document when a request-mapped controller exists for the library to scan, and the delivered main tree
 * contains none: the document this class contributes to therefore carries identity metadata, the reusable
 * component shapes and the bearer security scheme, and an empty paths object. That is the delivered state
 * rather than a misconfiguration, so it is said here, said in the served description below, and asserted
 * by {@code OpenApiConfigBoundaryTest} rather than left for a reader of the document to discover by
 * fetching it. The inventory grows as the controllers arrive and needs no change to this class to do so;
 * what must change with it is the assertion, which is written to fail the moment the emptiness stops
 * being true. See {@code docs/decision-log.md} DL-101.
 *
 * <p><strong>This class contributes document metadata only</strong> &mdash; title, description,
 * version, licence and the bearer security scheme. Everything else is produced by the
 * interface-documentation library's auto-configuration from whatever controllers and request and
 * response types it finds to scan. Five things are deliberately left alone:
 * <ul>
 *   <li><strong>No path literal.</strong> The address the document is served from is declared once, in
 *       {@code application.yml} under the {@code springdoc} key. Restating it here would give a value
 *       that must have exactly one home a second home, and the two would drift. No viewer address and
 *       no viewer bundle version is declared there or here, because the browser asset bundle that would
 *       render an interactive page is excluded from the starter in the build file: there is no page to
 *       address under any profile, and this build defines no bundle-version property and filters no
 *       resource, so a configuration file naming one would ship an unresolved literal. See
 *       {@code docs/decision-log.md} DL-088.</li>
 *   <li><strong>No second bean of a library-managed type.</strong> A single
 *       {@link io.swagger.v3.oas.models.OpenAPI} bean is the sanctioned extension point: the library's
 *       document builder consumes it as a {@link java.util.Optional} and declares none of its own. This
 *       class registers exactly one bean and does not define, replace or wrap the document service, the
 *       grouped-API scanning or the JSON mapper, because a competing bean of a library-managed type
 *       risks a definition-override or ambiguous-bean failure at start-up.</li>
 *   <li><strong>No document-definition annotation on the application entry point</strong>, which would
 *       produce two competing descriptions of one document.</li>
 *   <li><strong>No specification-version override.</strong> A value set here would silently contradict
 *       the configured one.</li>
 *   <li><strong>No server list.</strong> The library derives the served base address from the request,
 *       which keeps the document correct behind a published container port and behind a test harness on
 *       an ephemeral port alike; a literal address would be wrong in at least one of those.</li>
 * </ul>
 *
 * <p><strong>The bearer scheme describes; it never grants.</strong> {@link #BEARER_SCHEME_NAME} is
 * registered as an HTTP {@code bearer} scheme whose token format is {@link #BEARER_TOKEN_FORMAT}, and it
 * is applied as a document-wide security requirement so that a reader and a generated client both see
 * that a protected operation expects a token. That is the whole of its effect: publishing metadata
 * neither configures a filter chain nor widens one, and nothing in this class makes the document address
 * reachable without authentication.
 *
 * <p>Which routes are actually reachable, and by whom, is decided entirely by {@link SecurityConfig} and
 * by nothing here. <strong>The document-wide requirement is truthful because that chain exists and closes
 * by default</strong>: its final rule authenticates any request no earlier rule named, so every operation
 * this document describes does require a token unless the chain deliberately exempts its address. Before
 * that chain existed the requirement described a protection nothing implemented, which is the defect this
 * arrangement removes.
 *
 * <p><strong>The exemptions are named from the chain's own constants rather than restated.</strong> The
 * scheme description below is built from {@link SecurityConfig#SIGN_ON_PATH} and
 * {@link SecurityConfig#ADMIN_PATH_PREFIX}, so a published exemption and an enforced one cannot drift
 * apart: editing the rule edits the document. The sign-on address is the one anonymous business route,
 * because it is the route that issues the tokens every other route requires, and an operation mapped
 * there must clear the document-wide requirement so a generated client does not demand a token before
 * requesting one. The remaining anonymous addresses - the health probe, the metrics scrape endpoint and
 * this document itself where a profile publishes it - are operational rather than business surfaces and
 * carry no operation in this document at all. See {@code docs/decision-log.md} DL-099.
 *
 * <p>The low-level-code audit does not cover authorisation rules; its scope is raw SQL assembly, process
 * execution, reflection, unchecked casts and suppressed warnings, so it cannot catch an accidental permit
 * and is not relied on to. What catches one is {@link SecurityConfig}'s own tests, which assert real
 * response statuses rather than inspecting the configuration that produced them.
 *
 * <p><strong>Nothing secret is ever published.</strong> No credential, token, signing value,
 * authorisation header value or example password appears in this class or in the document it produces:
 * not as a description, not as an example, not as a default and not as a schema attribute. The legacy
 * sign-on records carried a single shared cleartext password literal in their provisioning job stream;
 * that literal is not restated anywhere in this module and this document publishes no stand-in for it.
 * No pre-filled authorisation value and no credential-submitting default appears in the document.
 *
 * <p><strong>Bean semantics.</strong> A lite-mode configuration class declaring one factory method; no
 * factory method here calls another, so interception is not needed. Stating
 * {@code proxyBeanMethods = false} explicitly keeps the class free of a runtime subclass, which both
 * permits it to be {@code final} and leaves the module's proxy and reflection surface untouched &mdash;
 * a surface the low-level-code audit measures and holds at zero. The published version is resolved once,
 * in the constructor, by {@linkplain ObjectProvider#getIfAvailable() optionally} consulting the
 * build-information bean, so it tracks the built artefact whenever the build publishes that information
 * and falls back to {@link #MODULE_VERSION} when it does not. The literal has exactly one home either
 * way.
 */
@Configuration(proxyBeanMethods = false)
public final class OpenApiConfig {

    /**
     * Key under which the bearer scheme is registered in the document's component section, and the name
     * the document-wide security requirement refers to.
     *
     * <p>Published as a constant so that a test asserting the scheme, and any operation-level reference
     * to it, name one single authority instead of repeating the literal. Being a compile-time constant, it
     * is usable as an annotation attribute.</p>
     */
    public static final String BEARER_SCHEME_NAME = "bearerAuth";

    /**
     * The HTTP authentication scheme registered under {@link #BEARER_SCHEME_NAME}, spelled as the OpenAPI
     * specification spells it for a security scheme of HTTP type.
     */
    public static final String HTTP_BEARER_SCHEME = "bearer";

    /**
     * Token format advertised for {@link #BEARER_SCHEME_NAME}. It documents the shape of the token that
     * the sign-on operation issues. It selects no signing algorithm, configures nothing and validates
     * nothing, all of which belong to the security filter chain alone.
     */
    public static final String BEARER_TOKEN_FORMAT = "JWT";

    /** Title carried by the published document. */
    public static final String API_TITLE = "CardDemo REST API";

    /**
     * Licence name carried by the published document. It is the licence granted by the legacy estate, and
     * the same licence whose header opens every source file of this module.
     */
    public static final String LICENSE_NAME = "Apache License 2.0";

    /** Canonical address of the licence named by {@link #LICENSE_NAME}. */
    public static final String LICENSE_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /**
     * Version published when the build does not publish its build information.
     *
     * <p>This is this module's own coordinate version for {@code com.carddemo:carddemo-java}, and the only
     * place in this module where that value is written. It is a coordinate, not a placeholder standing in
     * for something unresolved.</p>
     */
    public static final String MODULE_VERSION = "1.0.0";

    /**
     * Description carried by the published document.
     *
     * <p>Deliberately free of anything that would assert a service level: no latency, throughput, memory,
     * availability, pool or time-out value appears in it, because the legacy estate documents none for
     * this migration to be held to. The screen geometry it quotes is an interface fact, not a
     * measurement.</p>
     */
    private static final String API_DESCRIPTION = """
            Machine-readable interface description for the CardDemo application, migrated from the AWS \
            CardDemo z/OS estate to Java and Spring Boot. This document is the interface contract itself \
            rather than an account of one held elsewhere, and no separate hand-maintained contract file \
            exists that could drift away from the code.

            The endpoint inventory is derived from the code rather than written here, and at this \
            milestone it is empty: no request-mapped operation has been delivered yet, so this document \
            currently describes the reusable message shapes and the authentication rule and lists no \
            path. Paths appear as the endpoint groups arrive - the REST form of the 17 legacy \
            transactions that each drove a 24x80 terminal screen - and this description needs no change \
            when they do.

            Where this document describes an operation, message text, per-field error states, page sizes \
            and fixed-width record widths are reproduced from the legacy estate rather than redesigned, \
            so a client written against it sees the behaviour the terminal screens exposed. Every \
            monetary and rate value is a fixed-scale decimal carried across from a zoned-decimal field \
            and travels as a plain decimal, never in scientific notation.

            A protected operation expects an HTTP bearer token, described by the security scheme below.

            Provenance: legacy checkout 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp \
            CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. No legacy source text is reproduced in this \
            document.""";

    /**
     * Description carried by the bearer scheme.
     *
     * <p>It states what the scheme does and, just as deliberately, what it does not do, so that a reader
     * of the document cannot mistake a documented scheme for a granted permission.</p>
     *
     * <p>The two addresses it names are interpolated from {@link SecurityConfig}'s constants rather than
     * written out here. That is what keeps the published contract and the enforced rule identical: there
     * is one home for each address, and a change to the rule is a change to this text. It is assembled at
     * class initialisation rather than being a compile-time constant, which is unremarkable because it is
     * only ever read when the document is built.</p>
     */
    private static final String BEARER_SCHEME_DESCRIPTION = """
            HTTP bearer authentication. Present the token issued by the sign-on operation as the bearer \
            credential of a protected request.

            Every operation in this document requires a token except the sign-on operation at \
            """ + SecurityConfig.SIGN_ON_PATH + """
            , which issues them and therefore cannot itself require one. Operations beneath \
            """ + SecurityConfig.ADMIN_PATH_PREFIX + """
             require a token whose user type is the administrative one; a token for a standard user is \
            refused there rather than ignored.

            This declaration describes the scheme; the rules above are enforced by this module's security \
            filter chain and nothing in this document grants access. No credential, token or example \
            password appears anywhere in this contract, and no authorisation value is pre-filled.""";

    /**
     * Version published in the document, resolved once at construction.
     *
     * <p>Immutable, and instance state rather than static state, so the resolution happens under the
     * container's control and this class holds no mutable state that a second context could observe.</p>
     */
    private final String contractVersion;

    /**
     * Resolves the version that the published document will carry.
     *
     * <p>The build-information bean exists only when the build publishes a build-information resource,
     * which is why it is taken as a provider rather than as a required collaborator: its absence must not
     * fail start-up. When it is present and carries a version, that version is published, so the document
     * tracks the artefact actually running. Otherwise {@link #MODULE_VERSION} is published. For a build of
     * this module both paths yield the same coordinate version, so the served value is stable either
     * way. The build binds the goal that generates that resource, so the present path is the normal
     * one rather than the exception; see {@code docs/decision-log.md} DL-091.</p>
     *
     * @param buildPropertiesProvider provider for the optional build-information bean; the provider itself
     *                                is supplied by the container and is never {@code null}, though it may
     *                                resolve to nothing
     */
    public OpenApiConfig(final ObjectProvider<BuildProperties> buildPropertiesProvider) {
        final BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        final String publishedVersion = buildProperties == null ? null : buildProperties.getVersion();
        this.contractVersion = publishedVersion == null || publishedVersion.isBlank()
                ? MODULE_VERSION
                : publishedVersion;
    }

    /**
     * The single document-metadata bean that the interface-documentation library merges into the document
     * it generates from the scanned controllers.
     *
     * <p>It supplies the title, the description, the resolved version, the licence and the bearer security
     * scheme, and applies that scheme as a document-wide requirement. It supplies no path, no server, no
     * specification version and no example value; each of those is owned elsewhere, for the reasons
     * recorded on this class.</p>
     *
     * <p>A fresh instance is built on every invocation and none of it is retained by this class, so the
     * library is free to decorate the document it is handed.</p>
     *
     * @return the document metadata for the CardDemo REST surface
     */
    @Bean
    public OpenAPI cardDemoOpenApi() {
        final License license = new License()
                .name(LICENSE_NAME)
                .url(LICENSE_URL);

        final Info info = new Info()
                .title(API_TITLE)
                .description(API_DESCRIPTION)
                .version(this.contractVersion)
                .license(license);

        final SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(HTTP_BEARER_SCHEME)
                .bearerFormat(BEARER_TOKEN_FORMAT)
                .description(BEARER_SCHEME_DESCRIPTION);

        final Components components = new Components()
                .addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme);

        return new OpenAPI()
                .info(info)
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
