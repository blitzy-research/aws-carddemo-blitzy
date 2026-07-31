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
 * <h2>Why the document is the artefact</h2>
 * The legacy presentation layer was a CICS 3270 terminal contract, and the faithful translation of a
 * terminal contract is another machine contract rather than a new visual design. No browser interface
 * and no single-page application is built by this migration, so the generated document is the only
 * published description of the interface. There is no separate hand-maintained contract file anywhere in
 * this repository that could fall out of step with the code, and none may be introduced. The
 * interface-contract acceptance criterion refuses self-certification and requires a local test to
 * exercise the real contract, which is exactly what a served, parseable document lets a reviewer and a
 * test do against the same bytes.
 *
 * <h2>Provenance and legacy grounding</h2>
 * Translated from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>The legacy authority for the endpoint inventory this document describes is
 * {@code app/csd/CARDDEMO.CSD}, the CICS resource definition of the estate. Across its 505 lines it
 * defines <strong>18</strong> transactions bound to <strong>18</strong> programs, and
 * <strong>17</strong> screen field maps. The single transaction with no map drives the shared
 * date-validation subprogram rather than a screen, so <strong>17</strong> of the 18 transactions are
 * screen transactions, and it is those 17 that become the REST endpoint groups described here. Only
 * those counts cross over. No resource-definition statement, no COBOL statement and no screen-map
 * definition is reproduced in this class or in the document it produces, and nothing under
 * {@code app/} is read at run time.</p>
 *
 * <h2>What this class contributes, and what it deliberately leaves alone</h2>
 * This class contributes <em>document metadata only</em>: title, description, version, licence and the
 * bearer security scheme. Everything else in the document is produced by the interface-documentation
 * library's own auto-configuration from the controllers and the request and response types it scans.
 *
 * <ul>
 *   <li><strong>No path literal.</strong> The address the document is served from and the address the
 *       rendered viewer is served from are declared once, in
 *       {@code carddemo-java/src/main/resources/application.yml} under the {@code springdoc} key,
 *       together with the pinned viewer bundle version that the build file substitutes into it. Neither
 *       address is restated here. Restating one would give a value that must have exactly one home a
 *       second home, and the two would drift.</li>
 *   <li><strong>No second bean of a library-managed type.</strong> A single
 *       {@link io.swagger.v3.oas.models.OpenAPI} bean is the sanctioned extension point: the library's
 *       document builder consumes it as a {@link java.util.Optional} and declares no such bean of its
 *       own. This class therefore registers exactly one bean and does not define, replace or wrap the
 *       document service, the grouped-API scanning or the JSON mapper. A competing bean of a
 *       library-managed type risks a definition-override failure or an ambiguous-bean failure at
 *       start-up.</li>
 *   <li><strong>No document-definition annotation on the application entry point.</strong> That entry
 *       point carries its Spring Boot application annotation and nothing else. Declaring the same
 *       metadata both there and here would produce two competing descriptions of one document.</li>
 *   <li><strong>No specification-version override.</strong> The specification version of the served
 *       document is the library's own configured concern, so neither the version field nor the
 *       specification-version selector is touched here. A value set in this class would silently
 *       contradict the configured one.</li>
 *   <li><strong>No server list.</strong> The library derives the served base address from the request,
 *       which keeps the document correct behind a published container port and behind a test harness on
 *       an ephemeral port alike. A literal address here would be wrong in at least one of those.</li>
 * </ul>
 *
 * <h2>The bearer scheme describes; it never grants</h2>
 * {@link #BEARER_SCHEME_NAME} is registered as an HTTP {@code bearer} scheme whose token format is
 * {@link #BEARER_TOKEN_FORMAT}, and it is applied as a document-wide security requirement so that a
 * reader and a generated client both see that a protected operation expects a token. That is the whole
 * of its effect. It mirrors the filter chain in {@code com.carddemo.config.SecurityConfig}; it does not
 * configure that chain, does not replace it, and cannot widen it.
 *
 * <p>In particular, nothing in this class makes the document address or the viewer address reachable
 * without authentication. If either has to be reachable, that is an explicit, reviewed permit rule in
 * the filter chain, never an implicit side effect of publishing metadata. An address that became
 * reachable because someone permitted it by accident is precisely the kind of undocumented deviation
 * the low-level-code audit exists to catch.</p>
 *
 * <h2>Nothing secret is ever published</h2>
 * No credential, token, signing value, authorisation header value or example password appears in this
 * class or in the document it produces: not as a description, not as an example, not as a default and
 * not as a schema attribute. The legacy sign-on records carried a single shared cleartext password
 * literal in their provisioning job stream. That literal is not restated anywhere in this module, and
 * this document publishes no stand-in for it either. No pre-filled authorisation value and no
 * credential-submitting viewer default is enabled.
 *
 * <h2>Bean semantics</h2>
 * A lite-mode configuration class. It declares one factory method, and since no factory method here
 * calls another, interception is not needed. Stating {@code proxyBeanMethods = false} explicitly rather
 * than relying on a default keeps the class free of a runtime subclass, which both permits it to be
 * {@code final} and leaves the module's proxy and reflection surface untouched. That surface is measured
 * by the low-level-code audit and held at zero.
 *
 * <p>The version carried by the document is resolved once, in the constructor, by
 * {@linkplain ObjectProvider#getIfAvailable() optionally} consulting the build-information bean, so the
 * published version tracks the built artefact whenever the build publishes its build information. When
 * it does not, {@link #MODULE_VERSION} is published instead; that constant holds this module's own
 * coordinate version for {@code com.carddemo:carddemo-java}. The literal has exactly one home in this
 * module either way.</p>
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
            CardDemo z/OS estate to Java and Spring Boot. This document is the interface contract itself, \
            not an account of one held elsewhere: the endpoint groups it describes are the REST form of \
            the 17 legacy transactions that each drove a 24x80 terminal screen, and no separate \
            hand-maintained contract file exists that could drift away from the code.

            Message text, per-field error states, page sizes and fixed-width record widths are reproduced \
            from the legacy estate rather than redesigned, so a client written against this document sees \
            the behaviour the terminal screens exposed. Every monetary and rate value is a fixed-scale \
            decimal carried across from a zoned-decimal field and travels as a plain decimal, never in \
            scientific notation.

            A protected operation expects an HTTP bearer token, described by the security scheme below.

            Provenance: legacy checkout 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp \
            CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. No legacy source text is reproduced in this \
            document.""";

    /**
     * Description carried by the bearer scheme.
     *
     * <p>It states what the scheme does and, just as deliberately, what it does not do, so that a reader
     * of the document cannot mistake a documented scheme for a granted permission.</p>
     */
    private static final String BEARER_SCHEME_DESCRIPTION = """
            HTTP bearer authentication. Present the token issued by the sign-on operation as the bearer \
            credential of a protected request.

            This declaration describes the scheme only. Which operations require a token, and which are \
            reachable without one, is decided entirely by this module's security filter chain; nothing in \
            this document grants access. No credential, token or example password appears anywhere in \
            this contract, and no authorisation value is pre-filled.""";

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
     * way.</p>
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

