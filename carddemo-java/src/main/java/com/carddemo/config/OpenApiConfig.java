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

import java.util.Objects;

import com.carddemo.util.ContractTypeRoster;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

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
 * <p><strong>The endpoint inventory is derived, never declared here.</strong> The delivered
 * request-mapped controllers contribute the online transaction and operational batch paths when
 * springdoc builds the served document. This class contributes metadata and reusable schemas but no
 * hand-maintained path, so invoking {@link #cardDemoOpenApi()} directly still returns the unaugmented
 * metadata model while {@code /v3/api-docs} carries the scanned operations. The description states that
 * distinction explicitly, and {@code OpenApiConfigBoundaryTest} prevents the withdrawn empty-inventory
 * claim from returning. See {@code docs/decision-log.md} DL-101.
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
     * The roster of request and response families that are published as named schemas, supplied by the
     * boundary through {@link ContractTypeRoster}.
     *
     * <p><strong>Why the roster is not a list in this file.</strong> The families are transport types the
     * boundary package owns, and this package may not import that one. The roster therefore arrives through
     * a contract the base layer declares, which keeps the published document complete without inverting the
     * dependency direction. Its completeness, and the reason it is explicit rather than discovered, are
     * documented on the implementation.
     *
     * <p><strong>Nothing here is hand-written.</strong> The entries are types, not schemas: every property,
     * width, format and access mode in the published document is derived from the annotations on the type
     * itself, which is what keeps the derived document from disagreeing with the contracts it documents.
     */
    private final ContractTypeRoster contractTypeRoster;

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

    /** Component key of the bearer token returned by the sign-on operation. */
    public static final String AUTHORIZATION_HEADER_COMPONENT = "BearerAuthorization";

    /** Component reference used by the sign-on operation's successful response. */
    public static final String AUTHORIZATION_HEADER_REFERENCE =
            "#/components/headers/" + AUTHORIZATION_HEADER_COMPONENT;

    /** Shared bad-request response component key. */
    public static final String BAD_REQUEST_RESPONSE = "BadRequest";

    /** Shared unauthenticated response component key. */
    public static final String UNAUTHORIZED_RESPONSE = "Unauthorized";

    /** Shared forbidden response component key. */
    public static final String FORBIDDEN_RESPONSE = "Forbidden";

    /** Shared absent-resource response component key. */
    public static final String NOT_FOUND_RESPONSE = "NotFound";

    /** Shared optimistic/state-conflict response component key. */
    public static final String CONFLICT_RESPONSE = "Conflict";

    /** Shared terminal-failure response component key. */
    public static final String INTERNAL_SERVER_ERROR_RESPONSE = "InternalServerError";

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
     * {@code OpenApiConfigBoundaryTest} against the document actually built. The figure it states is held
     * to the code by measurement rather than by intention: it once said the inventory was empty, which
     * stopped being true the moment the first controller was mapped, and it then said nineteen while
     * twenty were mapped, because sign-on's first-entry turn was left out of the count. So every figure in
     * this text - the twenty operations, the nineteen paths, the eighteen screen-derived operations and the
     * two batch-control ones - is asserted against the routes the code actually maps by
     * {@code api.DeliveredApiSurfaceOracleTest}, which reads the count out of this text and compares it to
     * the router's own inventory, and the delivered total is pinned again by
     * {@code OpenApiConfigBoundaryTest}. A wrong figure here is therefore a failing test rather than a
     * misleading contract, and the text is edited only together with those assertions.
     */
    private static final String API_DESCRIPTION = """
            Machine-readable interface description for the CardDemo application, migrated from the AWS \
            CardDemo z/OS estate to Java and Spring Boot. This document is the interface contract itself \
            rather than an account of one held elsewhere, and no separate hand-maintained contract file \
            exists that could drift away from the code.

            The endpoint inventory is derived from the code's annotated controllers rather than written \
            here, so this description needs no edit when an operation moves. It publishes 20 operations \
            over 19 paths: the REST form of the 17 legacy transactions that each drove a 24x80 terminal \
            screen, including sign-on - which is 18 operations, because sign-on's two turns share one \
            path, first entry with no communication area and a submitted turn - plus 2 \
            administrator-only batch-control operations that launch and report the migrated Spring Batch \
            jobs. No path is hand-maintained in this description.

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
             and operations beneath \
            """ + SecurityConfig.BATCH_PATH_PREFIX + """
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
     *
     * @param buildPropertiesProvider provider for the build-information bean, which need not be present
     * @param contractTypeRoster      the boundary's roster of published request and response families; must
     *                                not be {@code null}
     * @throws NullPointerException if the roster is {@code null}
     */
    public OpenApiConfig(final ObjectProvider<BuildProperties> buildPropertiesProvider,
                         final ContractTypeRoster contractTypeRoster) {
        this.contractTypeRoster =
                Objects.requireNonNull(contractTypeRoster, "contractTypeRoster must not be null");
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
     * family the injected {@link ContractTypeRoster} names is converted to a schema and registered in the component
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
                .addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme)
                .addHeaders(AUTHORIZATION_HEADER_COMPONENT, authorizationHeader())
                .addResponses(BAD_REQUEST_RESPONSE, errorResponse(
                        "The request did not satisfy the published input contract."))
                .addResponses(UNAUTHORIZED_RESPONSE, errorResponse(
                        "A verifiable bearer credential is required."))
                .addResponses(FORBIDDEN_RESPONSE, errorResponse(
                        "The established principal is not permitted to perform this operation."))
                .addResponses(NOT_FOUND_RESPONSE, errorResponse(
                        "The addressed business or batch resource was not found."))
                .addResponses(CONFLICT_RESPONSE, errorResponse(
                        "The requested change conflicts with the current persisted or execution state."))
                .addResponses(INTERNAL_SERVER_ERROR_RESPONSE, errorResponse(
                        "The operation failed without disclosing internal implementation detail."));

        this.contractTypeRoster.publishedContractTypes().forEach(contractType ->
                ModelConverters.getInstance().readAll(contractType).forEach(components::addSchemas));

        return new OpenAPI()
                .info(info)
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }

    /**
     * Attaches to each served operation the reusable error responses that operation can actually reach.
     *
     * <p><strong>What this replaces, and why the difference matters.</strong> An earlier revision added
     * all six responses to every operation unconditionally. That published outcomes several routes cannot
     * produce, and each false entry misleads in a specific way. Advertising {@code 401} and {@code 403}
     * on the anonymous sign-on route tells a caller a credential might be required to obtain one, which
     * inverts the route's whole purpose. Advertising {@code 404} on a screen route contradicts the
     * contract those routes are built to keep: the legacy screens answer an absent record with a message
     * on the screen, so every one of them returns {@code 200} for absence and a client written against a
     * published {@code 404} would handle a case that never arrives while mishandling the one that does.
     * Advertising {@code 409} where no optimistic-lock check exists suggests a retry path that has
     * nothing to retry. A generated contract is read by people and by code generators, and an
     * unreachable status is a defect in it rather than harmless breadth.
     *
     * <p><strong>How reachability is decided.</strong> From the route's own security and exception
     * chain, using only what the operation and its handler already state:
     *
     * <ul>
     *   <li>{@code 500} on every operation. Any operation can fail internally, and the module answers
     *       that failure the same way everywhere.</li>
     *   <li>{@code 401} and {@code 403} only when the route is secured. An empty
     *       {@code @SecurityRequirements} on the handler is how this module declares a route anonymous -
     *       the two authentication routes carry it - and neither status is reachable on such a route.</li>
     *   <li>{@code 400} when the operation takes a request body, or takes a path or query value that has
     *       to be converted from text before the handler runs, or already declares {@code 400} itself.
     *       Those are the three ways a request can be refused before any business rule is consulted.</li>
     *   <li>{@code 404} when the operation addresses a resource by a path value, or already declares
     *       {@code 404} itself. This is the discriminator that keeps the status off the screen routes:
     *       every {@code RecordNotFoundException} in this module is raised by the batch-control surface,
     *       and that surface is also the only one that addresses anything by path.</li>
     *   <li>{@code 409} only when the operation declares it. A conflict arises solely where an
     *       optimistic-lock check exists, and an operation that has one says so.</li>
     * </ul>
     *
     * <p>Only Spring's {@code HandlerMethod} and {@code MethodParameter} are used to read the handler.
     * Neither is reflective object access, which keeps the module's reflection count at zero.
     *
     * @return an operation customizer attaching each operation's reachable error responses
     */
    @Bean
    public OperationCustomizer sharedOperationContract() {
        return (operation, handlerMethod) -> {
            io.swagger.v3.oas.models.responses.ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new io.swagger.v3.oas.models.responses.ApiResponses();
                operation.setResponses(responses);
            }

            if (declaresOrRefusesInput(operation, handlerMethod)) {
                responses.addApiResponse("400", responseReference(BAD_REQUEST_RESPONSE));
            }
            if (isSecured(handlerMethod)) {
                responses.addApiResponse("401", responseReference(UNAUTHORIZED_RESPONSE));
                responses.addApiResponse("403", responseReference(FORBIDDEN_RESPONSE));
            }
            if (addressesAPathResource(operation, handlerMethod)) {
                responses.addApiResponse("404", responseReference(NOT_FOUND_RESPONSE));
            }
            if (declares(operation, "409")) {
                responses.addApiResponse("409", responseReference(CONFLICT_RESPONSE));
            }
            responses.addApiResponse("500", responseReference(INTERNAL_SERVER_ERROR_RESPONSE));

            if ("signOn".equals(operation.getOperationId())) {
                final ApiResponse successful = responses.get("200");
                if (successful != null) {
                    successful.addHeaderObject(org.springframework.http.HttpHeaders.AUTHORIZATION,
                            new Header().$ref(AUTHORIZATION_HEADER_REFERENCE));
                }
            }
            return operation;
        };
    }

    private static Header authorizationHeader() {
        return new Header()
                .description("Bearer session issued by a successful sign-on, formatted as 'Bearer "
                        + "<token>'. Absent when the screen turn rejects the credential.")
                .schema(new StringSchema());
    }

    private static ApiResponse errorResponse(final String description) {
        final Schema<Object> errorSchema = new Schema<>();
        errorSchema.set$ref("#/components/schemas/ErrorResponse");
        final Content content = new Content().addMediaType(
                org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                new MediaType().schema(errorSchema));
        return new ApiResponse().description(description).content(content);
    }

    private static ApiResponse responseReference(final String component) {
        return new ApiResponse().$ref("#/components/responses/" + component);
    }

    /**
     * Reports whether the operation already declares one status itself.
     *
     * <p>A handler that states an outcome in its own {@code @ApiResponses} is the authority on that
     * outcome, so a declared status is always kept whatever the signature-derived rules would infer. This
     * is the escape hatch that keeps those rules honest: a route with an outcome the signature cannot
     * reveal declares it, rather than the rules being widened until they cover everything again.
     *
     * @param operation the operation springdoc has assembled so far
     * @param status    the three-digit status to look for
     * @return {@code true} when the operation already carries a response for that status
     */
    private static boolean declares(final Operation operation, final String status) {
        return operation.getResponses() != null && operation.getResponses().get(status) != null;
    }

    /**
     * Reports whether the route requires a credential.
     *
     * <p>An empty {@code @SecurityRequirements} on the handler is how this module declares a route
     * anonymous, and it is also what clears the operation's security list in the served document, so the
     * two statements cannot drift apart. Everything else inherits the document-level bearer requirement.
     *
     * @param handlerMethod the handler springdoc is describing
     * @return {@code true} unless the handler declares itself anonymous
     */
    private static boolean isSecured(final HandlerMethod handlerMethod) {
        final SecurityRequirements declared =
                handlerMethod.getMethodAnnotation(SecurityRequirements.class);
        return declared == null || declared.value().length > 0;
    }

    /**
     * Reports whether a request to this operation can be refused before any business rule runs.
     *
     * <p>Three ways, and all three are visible on the signature. A request body can fail to read or to
     * satisfy its constraints. A path or query value has to be converted from text, and a value that
     * will not convert is refused during binding - which is why the type is examined and not merely the
     * annotation: a path value already declared as text cannot fail a conversion. And an operation that
     * declares {@code 400} itself is taken at its word.
     *
     * @param operation     the operation springdoc has assembled so far
     * @param handlerMethod the handler springdoc is describing
     * @return {@code true} when a refusal before dispatch is reachable
     */
    private static boolean declaresOrRefusesInput(final Operation operation,
                                                  final HandlerMethod handlerMethod) {
        if (declares(operation, "400")) {
            return true;
        }
        for (final MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)) {
                return true;
            }
            final boolean bound = parameter.hasParameterAnnotation(PathVariable.class)
                    || parameter.hasParameterAnnotation(RequestParam.class);
            if (bound && !String.class.equals(parameter.getParameterType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether the operation addresses a resource that may not exist.
     *
     * <p>A path value names a resource by identity, and asking for one that is not held is what produces
     * an absent-resource answer. This is deliberately not true of a value carried in a request body: the
     * screen routes address records that way and answer absence with a message on the screen and status
     * {@code 200}, exactly as the legacy transactions did, so publishing {@code 404} against them would
     * contradict the contract they exist to keep.
     *
     * @param operation     the operation springdoc has assembled so far
     * @param handlerMethod the handler springdoc is describing
     * @return {@code true} when an absent-resource answer is reachable
     */
    private static boolean addressesAPathResource(final Operation operation,
                                                  final HandlerMethod handlerMethod) {
        if (declares(operation, "404")) {
            return true;
        }
        for (final MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(PathVariable.class)) {
                return true;
            }
        }
        return false;
    }
}
