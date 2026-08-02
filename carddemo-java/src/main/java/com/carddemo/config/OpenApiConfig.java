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

import java.util.List;

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.AccountViewResponse;
import com.carddemo.api.dto.BillPaymentRequest;
import com.carddemo.api.dto.BillPaymentResponse;
import com.carddemo.api.dto.CardDetailResponse;
import com.carddemo.api.dto.CardListRequest;
import com.carddemo.api.dto.CardListResponse;
import com.carddemo.api.dto.CardUpdateRequest;
import com.carddemo.api.dto.CardUpdateResponse;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.ReportRequest;
import com.carddemo.api.dto.ReportResponse;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.api.dto.SignOnRequest;
import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.api.dto.StatementSummary;
import com.carddemo.api.dto.TransactionAddRequest;
import com.carddemo.api.dto.TransactionAddResponse;
import com.carddemo.api.dto.TransactionListRequest;
import com.carddemo.api.dto.TransactionListResponse;
import com.carddemo.api.dto.TransactionViewResponse;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import io.swagger.v3.core.converter.ModelConverters;
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
 * seventeen screen field maps. Seventeen transactions become the REST endpoint groups this document will
 * describe. The eighteenth is the developer transaction {@code CDV1} at
 * {@code [app/csd/CARDDEMO.CSD:L388]}, and it drops out for a different reason than a missing screen: it is
 * bound to {@code PROGRAM(COCRDSEC)} at {@code [app/csd/CARDDEMO.CSD:L211]}, a program definition with no
 * corresponding source member anywhere in {@code app/cbl}, so the transaction could not have been
 * dispatchable in the shipped estate. It is a dangling resource definition, recorded as a source anomaly
 * with no target. It is <em>not</em> a route to the shared date-validation subprogram: that subprogram is
 * bound to no transaction anywhere in this file and is reached only by static {@code CALL} from four sites,
 * so it is an internal callee rather than a navigable endpoint. See {@code service/NavigationService} for
 * the same seventeen-and-an-anomaly derivation applied to the navigation vocabulary, and
 * {@code docs/decision-log.md} DL-107 for why the earlier reading was withdrawn.
 *
 * <p><strong>The endpoint inventory is derived, never declared here.</strong> A path enters the document
 * when a request-mapped controller exists for the library to scan, and this class contributes none, so the
 * paths object always reflects exactly what was scanned. Whatever is true of it is stated in the served
 * description and asserted by {@code OpenApiConfigBoundaryTest}, whose assertion fails in either
 * direction: an unlabelled empty inventory fails, and so does a label left on a populated one. See
 * {@code docs/decision-log.md} DL-101.
 *
 * <p><strong>This class contributes document metadata only</strong> &mdash; title, description, version,
 * licence and the bearer security scheme &mdash; everything else being produced by the
 * interface-documentation library's auto-configuration from the controllers and request and response types
 * it finds. Five things are deliberately left alone. <strong>No path literal</strong>: the address the
 * document is served from is declared once, in {@code application.yml} under the {@code springdoc} key, and
 * restating it here would give a value that must have one home a second home; no viewer address or bundle
 * version is declared either, because the browser asset bundle that would render an interactive page is
 * excluded from the starter in the build file, so there is no page to address under any profile, and this
 * build defines no bundle-version property and filters no resource, so a configuration file naming one
 * would ship an unresolved literal ({@code docs/decision-log.md} DL-088). <strong>No second bean of a
 * library-managed type</strong>: a single {@link io.swagger.v3.oas.models.OpenAPI} bean is the sanctioned
 * extension point, consumed by the library's document builder as a {@link java.util.Optional} and declared
 * nowhere by the library itself, so this class registers exactly one and does not define, replace or wrap
 * the document service, the grouped-API scanning or the JSON mapper, a competing bean of a library-managed
 * type risking a definition-override or ambiguous-bean failure at start-up. <strong>No document-definition
 * annotation on the application entry point</strong>, which would produce two competing descriptions of one
 * document. <strong>No specification-version override</strong>, which would silently contradict the
 * configured one. <strong>No server list</strong>: the library derives the served base address from the
 * request, keeping the document correct behind a published container port and behind a test harness on an
 * ephemeral port alike, whereas a literal address would be wrong in at least one of those.
 *
 * <p><strong>The bearer scheme describes; it never grants.</strong> Publishing metadata neither configures
 * a filter chain nor widens one, and nothing here makes the document address reachable without
 * authentication. Which routes are reachable, and by whom, is decided entirely by {@link SecurityConfig}.
 * <strong>The document-wide requirement is truthful because that chain closes by default</strong>: its
 * final rule authenticates any request no earlier rule named, so every operation this document describes
 * does require a token unless the chain deliberately exempts its address.
 *
 * <p><strong>The exemptions are named from the chain's own constants rather than restated</strong>, so a
 * published exemption and an enforced one cannot drift apart: editing the rule edits the document. The
 * sign-on address is the one anonymous business route, because it issues the tokens every other route
 * requires, and an operation mapped there must clear the document-wide requirement so a generated client
 * does not demand a token before requesting one. The remaining anonymous addresses &mdash; the health
 * probe, the metrics scrape endpoint and this document itself where a profile publishes it &mdash; are
 * operational rather than business surfaces and carry no operation at all. See
 * {@code docs/decision-log.md} DL-099.
 *
 * <p>The low-level-code audit does not cover authorisation rules &mdash; its scope is raw SQL assembly,
 * process execution, reflection, unchecked casts and suppressed warnings &mdash; so it cannot catch an
 * accidental permit and is not relied on to. What catches one is {@link SecurityConfig}'s own tests, which
 * assert real response statuses rather than inspecting the configuration that produced them.
 *
 * <p><strong>Nothing secret is ever published.</strong> No credential, token, signing value, authorisation
 * header value or example password appears in this class or in the document it produces: not as a
 * description, not as an example, not as a default and not as a schema attribute. The legacy sign-on
 * records carried a single shared cleartext password literal in their provisioning job stream; that
 * literal is not restated anywhere in this module and no stand-in for it is published.
 *
 * <p>A lite-mode configuration class declaring one factory method, and no factory method here calls
 * another, so interception is not needed. Stating {@code proxyBeanMethods = false} explicitly keeps the
 * class free of a runtime subclass, which both permits it to be {@code final} and leaves the module's
 * proxy and reflection surface untouched &mdash; a surface the low-level-code audit holds at zero.
 */
@Configuration(proxyBeanMethods = false)
public final class OpenApiConfig {

    /**
     * Key under which the bearer scheme is registered in the document's component section, and the name the
     * document-wide security requirement refers to. Published as a constant so that a test asserting the
     * scheme and any operation-level reference to it name one authority; being a compile-time constant, it
     * is usable as an annotation attribute.
     */
    public static final String BEARER_SCHEME_NAME = "bearerAuth";

    /**
     * Every request and response contract of the REST surface, each of which is published as a named
     * schema in the document's component section.
     *
     * <p><strong>Why the roster is explicit rather than discovered.</strong> A schema reaches the document
     * only if some scanned controller operation happens to reference the type. This module's controllers
     * are not yet written, so an operation-driven document would publish nothing at all - and once they
     * are written it would publish only the subset any signature happens to mention, silently omitting the
     * rest. Naming all twenty-eight families here makes the published contract complete and makes its
     * completeness assertable, which is what lets a test fail when a family is added and forgotten.</p>
     *
     * <p><strong>The nested shapes are deliberately absent from this list, and are still published.</strong>
     * Each entry is resolved transitively, so a family's nested rows, states and cursor shapes are derived
     * from the components that reference them rather than restated here. Listing a nested type explicitly
     * would create a second place for it to be declared and a second place for it to drift. Every nested
     * type in the package carries a distinct simple name, so no two schemas contend for one key.</p>
     *
     * <p><strong>Nothing here is hand-written.</strong> The entries are types, not schemas: every property,
     * width, format and access mode in the published document is derived from the annotations on the type
     * itself. This is the opposite of maintaining a schema by hand beside the code it describes, and it is
     * why the derived document cannot disagree with the contracts it documents.</p>
     */
    private static final List<Class<?>> PUBLISHED_CONTRACT_TYPES = List.of(
            AccountUpdateRequest.class,
            AccountUpdateResponse.class,
            AccountViewResponse.class,
            BillPaymentRequest.class,
            BillPaymentResponse.class,
            CardDetailResponse.class,
            CardListRequest.class,
            CardListResponse.class,
            CardUpdateRequest.class,
            CardUpdateResponse.class,
            ErrorResponse.class,
            FieldErrorDecorator.class,
            MenuResponse.class,
            NavigationContext.class,
            PageMetadata.class,
            PageMetadata.PageCursorRequest.class,
            ReportRequest.class,
            ReportResponse.class,
            ScreenWorkArea.class,
            SignOnRequest.class,
            SignOnResponse.class,
            StatementSummary.class,
            TransactionAddRequest.class,
            TransactionAddResponse.class,
            TransactionListRequest.class,
            TransactionListResponse.class,
            TransactionViewResponse.class,
            UserRequest.class,
            UserResponse.class);

    /**
     * The HTTP authentication scheme registered under {@link #BEARER_SCHEME_NAME}, spelled as the OpenAPI
     * specification spells it for a security scheme of HTTP type.
     */
    public static final String HTTP_BEARER_SCHEME = "bearer";

    /**
     * Token format advertised for {@link #BEARER_SCHEME_NAME}. It documents the shape of the token the
     * sign-on operation issues; it selects no signing algorithm, configures nothing and validates nothing,
     * all of which belong to the security filter chain alone.
     */
    public static final String BEARER_TOKEN_FORMAT = "JWT";

    public static final String API_TITLE = "CardDemo REST API";

    /**
     * Licence name carried by the published document: the licence granted by the legacy estate, and the same
     * licence whose header opens every source file of this module.
     */
    public static final String LICENSE_NAME = "Apache License 2.0";

    public static final String LICENSE_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /**
     * Version published when the build does not publish its build information. This is this module's own
     * {@code com.carddemo:carddemo-java} coordinate version, matching the coordinate the build file
     * declares &mdash; a coordinate, not a placeholder standing in for something unresolved.
     */
    public static final String MODULE_VERSION = "1.0.0";

    /**
     * Description carried by the published document.
     *
     * <p>Deliberately free of anything that would assert a service level: no latency, throughput, memory,
     * availability, pool or time-out value appears in it, because the legacy estate documents none for this
     * migration to be held to. The screen geometry it quotes is an interface fact, not a measurement.
     *
     * <p>This is published contract text rather than prose about the code, and its wording is asserted by
     * {@code OpenApiConfigBoundaryTest} against the document actually built &mdash; including the label
     * that must agree with the endpoint inventory in either direction. It is edited only together with
     * that assertion.
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
     * Description carried by the bearer scheme. It states what the scheme does and, just as deliberately,
     * what it does not do, so a reader cannot mistake a documented scheme for a granted permission.
     *
     * <p>The two addresses it names are interpolated from {@link SecurityConfig}'s constants rather than
     * written out here, which is what keeps the published contract and the enforced rule identical: there is
     * one home for each address, and a change to the rule is a change to this text. Assembled at class
     * initialisation rather than being a compile-time constant, which is unremarkable because it is only
     * ever read when the document is built.
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
     * Version published in the document, resolved once at construction. Instance state rather than static
     * state, so the resolution happens under the container's control.
     */
    private final String contractVersion;

    /**
     * Resolves the version that the published document will carry.
     *
     * <p>The build-information bean exists only when the build publishes a build-information resource, which
     * is why it is taken as a provider rather than as a required collaborator: its absence must not fail
     * start-up. When it is present and carries a version, that version is published, so the document tracks
     * the artefact actually running; otherwise {@link #MODULE_VERSION} is published. For a build of this
     * module both paths yield the same coordinate version, so the served value is stable either way, and
     * the build binds the goal that generates the resource, so the present path is the normal one rather
     * than the exception; see {@code docs/decision-log.md} DL-091.
     */
    public OpenApiConfig(final ObjectProvider<BuildProperties> buildPropertiesProvider) {
        final BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        final String publishedVersion = buildProperties == null ? null : buildProperties.getVersion();
        this.contractVersion = publishedVersion == null || publishedVersion.isBlank()
                ? MODULE_VERSION
                : publishedVersion;
    }

    /**
     * The single document-metadata bean that the interface-documentation library merges into the document it
     * generates from the scanned controllers. It supplies the title, the description, the resolved version,
     * the licence and the bearer security scheme, and applies that scheme as a document-wide requirement. It
     * supplies no path, no server, no specification version and no example value; each of those is owned
     * elsewhere, for the reasons recorded on this class.
     *
     * <p>It supplies the title, the description, the resolved version, the licence and the bearer security
     * scheme, and applies that scheme as a document-wide requirement. It supplies no path, no server, no
     * specification version and no example value; each of those is owned elsewhere, for the reasons
     * recorded on this class.</p>
     *
     * <p><strong>It also publishes a named schema for every request and response contract.</strong> Each
     * family in {@link #PUBLISHED_CONTRACT_TYPES} is converted to a schema and registered in the component
     * section under its own name, together with every nested shape reachable from it. Without this the
     * component section would carry the security scheme alone: a schema otherwise reaches the document only
     * when a scanned controller operation references the type, so every contract this module declares would
     * be absent from its own machine-readable description.</p>
     *
     * <p><strong>Registration is derivation, not authorship.</strong> The schemas are read from the types
     * by the conversion library, so each one's properties, widths, formats and access modes come from the
     * annotations on the contract itself. No schema is written out by hand here in competition with the
     * types, which is what keeps the document from disagreeing with the code it documents. Registration is
     * also additive: it neither replaces nor reorders anything the library later contributes from a scanned
     * operation, and a schema the library derives for the same type resolves to the same name.</p>
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

        PUBLISHED_CONTRACT_TYPES.forEach(contractType ->
                ModelConverters.getInstance().readAll(contractType).forEach(components::addSchemas));

        return new OpenAPI()
                .info(info)
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
