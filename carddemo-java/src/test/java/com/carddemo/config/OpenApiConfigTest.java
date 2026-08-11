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

import com.carddemo.api.PublishedContractTypeRoster;

import com.carddemo.CardDemoApplication;
import com.carddemo.api.AuthController;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.domain.enums.AccountStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;

import com.carddemo.domain.enums.UserType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.MergedAnnotations;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link OpenApiConfig}, the configuration class that constructs the
 * {@link io.swagger.v3.oas.models.OpenAPI} object graph this module publishes as its interface
 * description.
 *
 * <p><strong>Why this class is tested at all.</strong> {@code OpenApiConfig} does real work: it builds a
 * document carrying a title, a description, a version, a licence, a bearer security scheme registered as a
 * component and that same scheme applied as a document-wide requirement. Asserting that constructed object
 * graph is behaviour testing, not annotation checking, so every test method below makes at least one
 * assertion about the graph the production code actually produced. No test method here asserts only that an
 * annotation is present.
 *
 * <p><strong>Why the document's shape is contractual.</strong>
 * <strong>No browser interface and no single-page application is built anywhere in this migration</strong>,
 * and no component library or design system is in scope: the requirements name no frontend technology, no
 * design assets were supplied, and the repository carries no JavaScript, TypeScript or CSS source. The
 * published OpenAPI document is therefore not a convenience view over a contract held elsewhere - it
 * <em>is</em> the machine-readable interface description, and it is the artefact the interface-contract
 * acceptance criterion reads.
 *
 * <p>The legacy presentation layer it replaces was a CICS 3270 terminal contract of <strong>17 BMS
 * mapsets</strong> at 24x80, with <strong>17 generated symbolic-map copybooks</strong> supplying the
 * field-level contract. {@code app/csd/CARDDEMO.CSD} carries <strong>18</strong> {@code DEFINE TRANSACTION}
 * entries bound to 18 programs, and <strong>17 of the 18</strong> drive a screen and become the REST
 * endpoint groups this document describes. The eighteenth is the developer transaction {@code CDV1}, which
 * is bound to a program definition carrying no source member and so was never dispatchable; it is a
 * dangling resource definition recorded as a source anomaly, <em>not</em> a route to the shared
 * date-validation subprogram, which is bound to no transaction and is reached only by static
 * {@code CALL}. Those figures are cited here as metadata only. No program, copybook, screen map,
 * job stream or resource-definition source line is reproduced anywhere in this file.
 *
 * <p><strong>What this class deliberately does not assert.</strong>
 * Endpoint paths, request and response schemas, HTTP status codes and the per-field MISSING and INVALID
 * error contract belong to the controller and integration tiers, not here - this bean contributes
 * document-level metadata only, and the paths are contributed by the controller scan at runtime. The three
 * screen page sizes that survive into the REST contract (7 for the card list, 10 for the transaction list
 * and 10 for the user list) belong to the request and response object tests. The route-to-role table, the
 * password encoder, statelessness and CSRF belong to the HTTP security configuration's own test. Token
 * issuing, parsing, tampering and expiry belong to the token provider's own test. The contractual sign-on
 * message literals belong to the message catalogue service's test. Here the document is only required to
 * <em>describe</em> bearer authentication.
 *
 * <p><strong>Independent oracle.</strong>
 * Every expected title, version, licence name, licence URL and scheme key below is written out as a literal
 * in this class. None is read back from a constant on the class under test, because an expectation sourced
 * from the code it is meant to check cannot fail.
 *
 * <p><strong>Test tier.</strong>
 * A unit test. It starts no web server, issues no HTTP request, declares no container and needs no
 * datasource: it instantiates the configuration directly for the object-graph assertions and uses a narrow
 * {@link ApplicationContextRunner} slice registering only {@code OpenApiConfig} for the assertions that are
 * about which beans the class contributes.
 */
@DisplayName("OpenApiConfig - the published OpenAPI document, which is this module's interface artefact")
final class OpenApiConfigTest {

    // EXPECTED VALUES - literals only, never read back from the class under test

    /** Title the document is required to carry. */
    private static final String EXPECTED_TITLE = "CardDemo REST API";

    /**
     * Version the document is required to publish when the build publishes no build information.
     *
     * <p>This is the module's own Maven coordinate version for {@code com.carddemo:carddemo-java}. That
     * value appears in {@code pom.xml} exactly once, as the artefact's own coordinate, and it is
     * <strong>not</strong> a placeholder standing in for an unresolved third-party version: every
     * third-party coordinate in that file is pinned to an exact version read back out of an executed
     * resolution.</p>
     */
    private static final String EXPECTED_MODULE_VERSION = "1.0.0";

    /**
     * Licence name the document is required to carry.
     *
     * <p>Continuity rationale: every legacy artefact in the estate opens with an Apache-2.0 header, and the
     * repository ships both a licence file and a notice file attributing Amazon.com, Inc. or its
     * affiliates. The generated interface document must therefore carry the same grant, so that a consumer
     * reading only the published contract still learns the terms the surface is offered under.</p>
     */
    private static final String EXPECTED_LICENCE_NAME = "Apache License 2.0";

    /**
     * Canonical location of the licence named by {@link #EXPECTED_LICENCE_NAME}.
     *
     * <p>Asserted character for character. This is the same address the source-file licence header of every
     * file in this module points at, including the header of this file; the header block and this
     * expectation are two different things that must both be right, and both appearing in this file is
     * intentional.</p>
     */
    private static final String EXPECTED_LICENCE_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /** Key the bearer scheme is required to be registered under, and named by, in the requirement. */
    private static final String EXPECTED_SCHEME_KEY = "bearerAuth";

    /** HTTP authentication scheme the registered scheme is required to declare. */
    private static final String EXPECTED_HTTP_SCHEME = "bearer";

    /** Token format the registered scheme is required to advertise. */
    private static final String EXPECTED_BEARER_FORMAT = "JWT";

    // SUBSTANTIVE CLAIMS THE TWO DESCRIPTIONS MUST CARRY

    /**
     * The load-bearing statements the document description is required to make, keyed by what each one
     * tells a reader.
     *
     * <p>Every value here is established outside this module rather than read from the description it
     * judges. The two provenance identifiers are the analysed checkout and the designated release stamp,
     * which is the one embedded in the trailer of the measured cohort of legacy members rather than one
     * every member bears. The transaction count was counted from the resource definition
     * {@code [app/csd/CARDDEMO.CSD]}, which declares eighteen transactions of which one - the
     * date-validation transaction - drove no screen, leaving seventeen that did; that figure is
     * corroborated independently by the seventeen mapsets in {@code [app/bms]} and the seventeen
     * generated symbolic maps in {@code [app/cpy-bms]}. The terminal geometry is the 3270 model the
     * mapsets declare. The two decimal statements are the wire form every monetary and rate value is
     * required to travel in, which matters because a client parsing a fixed-width monetary field cannot
     * interpret an exponent.</p>
     *
     * <p>A description that merely exists tells a consumer nothing, which is why presence alone is not
     * asserted anywhere in this class.</p>
     */
    private static final Map<String, String> REQUIRED_DESCRIPTION_CLAIMS = Map.of(
            "the analysed legacy checkout", "7756d895ffeb65f7ea72aaa609e356d9899afcec",
            "the upstream release stamp", "CardDemo_v1.0-15-g27d6c6f-68",
            "the date that stamp carries", "2022-07-19",
            "the count of screen-driving legacy transactions", "17 legacy",
            "the terminal geometry those transactions drove", "24x80",
            "the wire form of every monetary and rate value", "plain decimal",
            "the exponent form that is therefore excluded", "never in scientific notation");

    /**
     * The load-bearing statements the bearer scheme description is required to make.
     *
     * <p>Separate from {@link #REQUIRED_DESCRIPTION_CLAIMS} because the two descriptions answer to
     * different authorities: the document description answers to the legacy estate, and this one answers
     * to the filter chain that actually enforces the rule it describes. The two addresses it must name
     * are not written out here at all - they are read from {@link SecurityConfig}, because a second copy
     * of an address in this file could agree with the description while both disagreed with the rule.</p>
     */
    private static final Map<String, String> REQUIRED_SCHEME_CLAIMS = Map.of(
            "the route the chain exempts", SecurityConfig.SIGN_ON_PATH,
            "the region the chain gates", SecurityConfig.ADMIN_PATH_PREFIX,
            "where the rule is actually enforced", "filter chain",
            "that the document itself grants nothing", "nothing in this document grants access",
            "that no credential is carried", "No credential");

    /**
     * Matches an address-shaped literal, so the scheme description can be censused for any address
     * beyond the two it is meant to interpolate.
     *
     * <p>Deliberately anchored on the module's own API prefix rather than on a leading solidus, because a
     * description is prose and an unanchored pattern would match ordinary punctuation.</p>
     */
    private static final Pattern ADDRESS_SHAPED_LITERAL = Pattern.compile("/api/[A-Za-z0-9/_-]*");

    /**
     * Address the document itself is served from.
     *
     * <p>Declared in {@code application.yml} under the {@code springdoc} key and nowhere else. It appears in
     * this class for one purpose only: to be asserted <strong>absent</strong> from the constructed document,
     * so a future edit that restated it in code would fail rather than quietly give a value with exactly one
     * home a second home.</p>
     */
    private static final String CONFIGURED_API_DOCS_PATH = "/v3/api-docs";

    /**
     * Address the rendered viewer is served from.
     *
     * <p>Owned by {@code application.yml} exactly as {@link #CONFIGURED_API_DOCS_PATH} is, and present here
     * for the same single purpose: to be asserted <strong>absent</strong> from the constructed document.</p>
     */
    private static final String CONFIGURED_VIEWER_PATH = "/swagger-ui";

    // CREDENTIAL SCAN RULES

    /**
     * Field names that would identify a credential, matched case-insensitively as a substring of the name.
     *
     * <p>A field so named must be absent, or carry nothing but a shape declaration. This is the first of
     * four complementary rules, and it is the one that catches a credential parked under an honest label.</p>
     *
     * <p><strong>Why a declaration is permitted where a value is not.</strong> The document publishes a
     * named schema for every request and response contract, and a few of those contracts legitimately
     * declare a credential-named property: the sign-on and user-administration requests each declare a
     * {@code password}, and the account-update request declares the {@code concurrencyToken} it returns
     * to the service on the confirming turn. No response contract declares one. A property
     * declaration is a statement that the field exists and how wide it is; it is not the field's value. The
     * rule therefore rejects a credential-named field whose value is a scalar — text, a number or a
     * boolean, which is what an actual credential would be — while permitting one whose value is the nested
     * object that describes the property's shape. Permitting the declaration is not a relaxation, because
     * the rule is extended in the same breath to reach <em>inside</em> every such declaration and reject any
     * {@link #VALUE_BEARING_SCHEMA_KEYS value-bearing key} found there. Before schemas were published there
     * was nowhere for a credential to hide under a credential-named label; now there is exactly one place,
     * and it is policed.</p>
     */
    private static final List<String> CREDENTIAL_FIELD_NAME_FRAGMENTS = List.of(
            "password", "passwd", "secret", "credential", "token", "apikey", "api_key", "api-key",
            "authorization");

    /**
     * Schema keys under which a declared property could carry a literal value rather than describe a shape.
     *
     * <p>A derived schema states a property's type, width and description. These four keys are the only ones
     * that would carry a value <em>of</em> the property, so they are the only places a credential could hide
     * inside an otherwise legitimate declaration. Held separately from
     * {@link #EXAMPLE_FIELD_NAME_FRAGMENTS} because that rule forbids an example anywhere in the document
     * while this one additionally forbids a default, an enumeration or a constant beneath a credential-named
     * property.</p>
     */
    private static final List<String> VALUE_BEARING_SCHEMA_KEYS =
            List.of("example", "examples", "default", "enum", "const");

    /**
     * Field names under which an example value would sit.
     *
     * <p>Held separately from {@link #CREDENTIAL_FIELD_NAME_FRAGMENTS} because an example is not itself a
     * credential; it is the place a credential most easily hides in a published contract.</p>
     */
    private static final List<String> EXAMPLE_FIELD_NAME_FRAGMENTS = List.of("example", "examples");

    /**
     * Patterns that identify a credential appearing as a value, each paired with the rule name reported when
     * it matches.
     *
     * <p>Every pattern requires an actual value, never a bare word. That distinction is the whole point of
     * this rule set: the published document legitimately <em>talks about</em> tokens, credentials and
     * passwords in prose, because its security scheme has to explain itself. A scheme named for bearer
     * tokens is legitimate; a token value is not. A scan that merely searched the serialized text for the
     * word {@code password} would fail on the scheme's own description, which is why the scan is expressed
     * over values.</p>
     */
    private static final Map<String, Pattern> CREDENTIAL_VALUE_RULES = Map.of(
            "credential assignment",
            Pattern.compile("(?i)\\b(?:password|passwd|secret|credential|token|api[ _-]?key|authorization)"
                    + "\\b\\s*[:=]\\s*\\S"),
            "compact web token",
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            "payment provider key",
            Pattern.compile("(?:sk|pk)_(?:live|test)_[A-Za-z0-9]{8,}"),
            "cloud access key identifier",
            Pattern.compile("A(?:KIA|SIA)[0-9A-Z]{12,}"),
            "code forge access token",
            Pattern.compile("(?:ghp|gho|ghs|ghu|ghr|github_pat)_[A-Za-z0-9_]{16,}"),
            "cloud api key",
            Pattern.compile("AIza[0-9A-Za-z_-]{16,}"),
            "workspace token",
            Pattern.compile("xox[abpsr]-[0-9A-Za-z-]{8,}"),
            "private key block",
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            "basic authentication in a url",
            Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^\\s/?#@]*:[^\\s/?#@]*@"));

    /**
     * Shape of a fixed-width credential field carried as a bare value.
     *
     * <p>This is the generic expression of the requirement that the shared cleartext credential the legacy
     * provisioning job stream carried in-stream must not appear in the published contract. The literal is
     * deliberately not named here and is not needed: it was a run of uppercase letters occupying a
     * fixed-width field, so any bare value made only of uppercase letters and digits is rejected on shape
     * alone. The length floor sits below that field's width so a shortened variant is caught too.</p>
     *
     * <p>No metadata value in the document has this shape: the specification version and the module version
     * carry dots, the title and both descriptions carry whitespace and lowercase letters, the licence name
     * carries whitespace, the licence URL and the HTTP scheme are lowercase, and the scheme type and token
     * format are shorter than the floor.</p>
     *
     * <p><strong>The one legitimate exception, and why it is narrow.</strong> Publishing a named schema for
     * every contract also publishes the permitted values of every enumerated property, and a Java enumerated
     * constant is uppercase by convention, so constants such as the field states, the paging directions and
     * the program-entry contexts have exactly this shape. Those names are declared in this module's own
     * source, so they are enumerated in {@link #DECLARED_ENUM_CONSTANT_NAMES} and exempted by identity
     * rather than by pattern. Nothing else is exempted: any other bare uppercase value is still rejected,
     * including the literal the legacy provisioning stream carried, which is not a constant of any
     * enumeration declared here.</p>
     */
    private static final Pattern CREDENTIAL_SHAPED_BARE_VALUE = Pattern.compile("[A-Z0-9]{6,}");

    /**
     * Every enumerated constant name reachable from a published contract type.
     *
     * <p>Collected from the declaring enumerations themselves rather than from the document, so the exemption
     * can never widen to cover a value the document happens to contain. Adding a constant to any of these
     * enumerations widens the exemption by exactly that name; adding an uppercase value anywhere else in the
     * document still fails {@link #CREDENTIAL_SHAPED_BARE_VALUE}.</p>
     */
    private static final Set<String> DECLARED_ENUM_CONSTANT_NAMES = Set.copyOf(
            Stream.<Enum<?>[]>of(ErrorResponse.FieldState.values(), FieldErrorDecorator.FlagState.values(),
                            MenuResponse.MessageSeverity.values(), NavigationContext.ProgramContext.values(),
                            PageMetadata.PagingDirection.values(), AccountStatus.values(),
                            KeyAction.values(), ReportPeriod.values(), UserType.values())
                    .flatMap(Arrays::stream)
                    .map(Enum::name)
                    .toList());

    /**
     * Serializer used to turn the published document into text and back into a tree.
     *
     * <p>Built through the builder rather than by mutating a constructed mapper, because the mutators are
     * deprecated on this Jackson line and every diagnostic is promoted to an error by this build. No
     * inclusion filter is applied, so absent members appear as explicit nulls and the scan sees the whole
     * document rather than only its populated parts.</p>
     */
    private static final ObjectMapper CONTRACT_MAPPER = JsonMapper.builder().build();

    /** Parsed shape of the serialized document; parameterised so no raw type and no unchecked cast arises. */
    private static final TypeReference<Map<String, Object>> DOCUMENT_TREE =
            new TypeReference<Map<String, Object>>() { };

    /**
     * JSON pointers the credential scan must be shown to reach, so that a passing scan cannot be a scan
     * that walked nothing.
     */
    private static final List<String> DEEPEST_TEXT_POINTERS = List.of(
            "/info/title",
            "/info/description",
            "/info/license/name",
            "/info/license/url",
            "/components/securitySchemes/" + EXPECTED_SCHEME_KEY + "/description");

    // HELPERS

    /**
     * Builds the document the container would publish when the build supplies no build information, which is
     * the state of a locally compiled class tree.
     *
     * @return the published document
     */
    private static OpenAPI publishedDocument() {
        return new OpenApiConfig(new AbsentBuildInformation(), new PublishedContractTypeRoster()).cardDemoOpenApi();
    }

    /**
     * Builds the document the container would publish when the build supplies the given version.
     *
     * @param publishedVersion the version the build publishes, or {@code null} for a build that publishes
     *                         none
     * @return the published document
     */
    private static OpenAPI publishedDocumentForBuildVersion(final String publishedVersion) {
        final Properties entries = new Properties();
        entries.setProperty("group", "com.carddemo");
        entries.setProperty("artifact", "carddemo-java");
        if (publishedVersion != null) {
            entries.setProperty("version", publishedVersion);
        }
        return new OpenApiConfig(new PresentBuildInformation(new BuildProperties(entries)),
                new PublishedContractTypeRoster())
                .cardDemoOpenApi();
    }

    /**
     * Returns the scheme the document registers under the expected key.
     *
     * @param document the published document
     * @return the registered scheme, or {@code null} when the document registers none under that key
     */
    private static SecurityScheme registeredScheme(final OpenAPI document) {
        return document.getComponents().getSecuritySchemes().get(EXPECTED_SCHEME_KEY);
    }

    /**
     * Serializes the published document to JSON.
     *
     * @param document the published document
     * @return the serialized document
     */
    private static String serialized(final OpenAPI document) {
        try {
            return CONTRACT_MAPPER.writeValueAsString(document);
        } catch (final JsonProcessingException cause) {
            throw new IllegalStateException("the published document could not be serialized", cause);
        }
    }

    /**
     * Serializes the published document and parses it back into a tree, so the credential scan runs over
     * values and field names rather than over an undifferentiated run of text.
     *
     * @param document the published document
     * @return every node of the serialized document, in document order
     */
    private static List<DocumentNode> nodesOf(final OpenAPI document) {
        final Map<String, Object> tree;
        try {
            tree = CONTRACT_MAPPER.readValue(serialized(document), DOCUMENT_TREE);
        } catch (final JsonProcessingException cause) {
            throw new IllegalStateException("the serialized document could not be parsed", cause);
        }
        final List<DocumentNode> nodes = new ArrayList<>();
        collect("", null, tree, nodes);
        return nodes;
    }

    /**
     * Walks a parsed JSON value, recording every node with the pointer that locates it.
     *
     * <p>Wildcard-parameterised patterns are used for the container cases so that no raw type and no
     * unchecked cast is introduced; either would fail this build outright.</p>
     *
     * @param pointer   pointer locating the value being visited
     * @param fieldName name of the field carrying the value, or {@code null} for the root and for elements
     *                  of an array
     * @param value     the value being visited
     * @param sink      collector the visited nodes are added to
     */
    private static void collect(final String pointer, final String fieldName, final Object value,
            final List<DocumentNode> sink) {
        sink.add(new DocumentNode(pointer, fieldName, value));
        if (value instanceof Map<?, ?> object) {
            for (final Map.Entry<?, ?> member : object.entrySet()) {
                final String memberName = String.valueOf(member.getKey());
                collect(pointer + "/" + memberName, memberName, member.getValue(), sink);
            }
        } else if (value instanceof List<?> array) {
            for (int index = 0; index < array.size(); index++) {
                collect(pointer + "/" + index, null, array.get(index), sink);
            }
        }
    }

    /**
     * Returns every node of the document whose value is text.
     *
     * @param document the published document
     * @return the text-valued nodes
     */
    private static List<DocumentNode> textNodesOf(final OpenAPI document) {
        return nodesOf(document).stream().filter(node -> node.value() instanceof String).toList();
    }

    /**
     * Reports whether a field name identifies one of the given concerns.
     *
     * @param fieldName the field name, or {@code null} for a node that has none
     * @param fragments the fragments that identify the concern
     * @return {@code true} when the name carries any of the fragments
     */
    private static boolean names(final String fieldName, final List<String> fragments) {
        if (fieldName == null) {
            return false;
        }
        final String folded = fieldName.toLowerCase(Locale.ROOT);
        return fragments.stream().anyMatch(folded::contains);
    }

    /**
     * Reports whether a pointer locates something nested beneath a credential-named property.
     *
     * <p>Works on the pointer rather than on the node, because the walk hands each node only its own field
     * name and the pointer is the only place the enclosing property's name survives. Every segment is tested,
     * so a value nested arbitrarily deep beneath a credential-named property is still reached.</p>
     *
     * @param pointer JSON pointer locating the node under test
     * @return {@code true} when any segment of the pointer is named for a credential
     */
    private static boolean sitsUnderACredentialNamedProperty(final String pointer) {
        return Arrays.stream(pointer.split("/"))
                .anyMatch(segment -> names(segment, CREDENTIAL_FIELD_NAME_FRAGMENTS));
    }

    /**
     * A node of the serialized document: the pointer that locates it, the field name that carries it and its
     * value.
     *
     * @param pointer   JSON pointer locating this node
     * @param fieldName name of the field carrying this node, or {@code null} for the root and array elements
     * @param value     the node's value, which may be {@code null}
     */
    private record DocumentNode(String pointer, String fieldName, Object value) { }

    // TEST DOUBLES

    /**
     * A provider that resolves to no build information, which is what a locally compiled class tree yields
     * because the build-information resource is written by the packaging step.
     *
     * <p>Hand written rather than mocked: a mocked generic provider would force an unchecked conversion, and
     * an unchecked conversion fails this build.</p>
     */
    private static final class AbsentBuildInformation implements ObjectProvider<BuildProperties> {

        @Override
        public BuildProperties getObject() {
            throw new IllegalStateException("no build information bean is available");
        }

        @Override
        public BuildProperties getIfAvailable() {
            return null;
        }
    }

    /**
     * A provider that resolves to the build information it was given.
     */
    private static final class PresentBuildInformation implements ObjectProvider<BuildProperties> {

        /** The build information this provider resolves to. */
        private final BuildProperties buildProperties;

        /**
         * Creates a provider resolving to the given build information.
         *
         * @param buildProperties the build information to resolve to
         */
        PresentBuildInformation(final BuildProperties buildProperties) {
            this.buildProperties = buildProperties;
        }

        @Override
        public BuildProperties getObject() {
            return this.buildProperties;
        }

        @Override
        public BuildProperties getIfAvailable() {
            return this.buildProperties;
        }
    }

    /**
     * A provider that records how many times it was consulted, so that resolving the version once at
     * construction can be told apart from resolving it on every document.
     */
    private static final class CountingBuildInformation implements ObjectProvider<BuildProperties> {

        /** Version this provider resolves to; free of the module coordinate version so the two cannot be
         * confused. */
        private static final String PUBLISHED_VERSION = "4.2.9";

        /** Number of times this provider was consulted. */
        private int consultations;

        @Override
        public BuildProperties getObject() {
            return getIfAvailable();
        }

        @Override
        public BuildProperties getIfAvailable() {
            this.consultations++;
            final Properties entries = new Properties();
            entries.setProperty("group", "com.carddemo");
            entries.setProperty("artifact", "carddemo-java");
            entries.setProperty("version", PUBLISHED_VERSION);
            return new BuildProperties(entries);
        }

        /**
         * Returns how many times this provider was consulted.
         *
         * @return the consultation count
         */
        int consultations() {
            return this.consultations;
        }

        /**
         * Returns the version this provider resolves to.
         *
         * @return the published version
         */
        static String publishedVersion() {
            return PUBLISHED_VERSION;
        }
    }


    // DOCUMENT IDENTITY

    /**
     * Verifies the identity block the document publishes: its title, its description, its version and its
     * licence.
     */
    @Nested
    @DisplayName("document identity")
    final class DocumentIdentity {

        @Test
        @DisplayName("a document is produced, carrying an info block, a component section and a security "
                + "requirement")
        void aDocumentIsProduced() {
            final OpenAPI document = publishedDocument();

            assertThat(document).isNotNull();
            assertThat(document.getInfo()).isNotNull();
            assertThat(document.getComponents()).isNotNull();
            assertThat(document.getSecurity()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("the title identifies the CardDemo service")
        void theTitleIdentifiesTheCardDemoService() {
            assertThat(publishedDocument().getInfo().getTitle())
                    .isNotBlank()
                    .isEqualTo(EXPECTED_TITLE);
        }

        @Test
        @DisplayName("the description makes every statement a consumer needs, not merely some statement, "
                + "so what it says is pinned rather than only that it says something")
        void theDescriptionMakesEveryRequiredStatement() {
            final Info info = publishedDocument().getInfo();
            final String description = info.getDescription();

            // Judged as a whole rather than claim by claim. A loop of hard assertions would report only
            // the first missing statement and leave the rest of a gutted description unexamined, which is
            // how a description can lose six of seven claims while a suite still names one failure.
            final Map<String, String> missing = new LinkedHashMap<>();
            REQUIRED_DESCRIPTION_CLAIMS.forEach((whatItTells, statement) -> {
                if (description == null || !description.contains(statement)) {
                    missing.put(whatItTells, statement);
                }
            });

            assertThat(missing)
                    .as("every statement the description is required to make must be present")
                    .isEmpty();
            assertThat(description)
                    .as("a description that merely repeated the title would carry no information")
                    .isNotEqualTo(info.getTitle());
        }

        @Test
        @DisplayName("the version published when the build supplies none is the module coordinate version")
        void theVersionIsTheModuleCoordinateVersion() {
            assertThat(publishedDocument().getInfo().getVersion()).isEqualTo(EXPECTED_MODULE_VERSION);
        }

        @Test
        @DisplayName("the licence is the Apache 2.0 grant, by name and by exact address")
        void theLicenceIsTheApacheGrant() {
            final License licence = publishedDocument().getInfo().getLicense();

            assertThat(licence).isNotNull();
            assertThat(licence.getName()).isEqualTo(EXPECTED_LICENCE_NAME);
            assertThat(licence.getUrl()).isEqualTo(EXPECTED_LICENCE_URL);
        }

        @Test
        @DisplayName("this bean is the single source of the document's info block, with no competing "
                + "definition carried on the application entry point")
        void thisBeanIsTheSingleSourceOfTheInfoBlock() {
            final Info info = publishedDocument().getInfo();

            assertThat(info.getTitle()).isEqualTo(EXPECTED_TITLE);
            assertThat(info.getVersion()).isEqualTo(EXPECTED_MODULE_VERSION);
            assertThat(info.getLicense().getName()).isEqualTo(EXPECTED_LICENCE_NAME);

            final boolean competingDefinitionPresent = MergedAnnotations
                    .from(CardDemoApplication.class, SearchStrategy.TYPE_HIERARCHY)
                    .isPresent(OpenAPIDefinition.class);

            assertThat(competingDefinitionPresent)
                    .as("a document-definition annotation on the application entry point would produce a "
                            + "second, competing description of the same document")
                    .isFalse();
        }
    }

    // THE BEARER SECURITY SCHEME AND THE DOCUMENT-WIDE REQUIREMENT

    /**
     * Verifies the declared authentication scheme and the requirement that applies it.
     *
     * <p>Grounding, as metadata: the token this scheme describes is what replaces the legacy
     * communication-area state carriage that every online program copied in and echoed back across a
     * pseudo-conversational turn. The legacy sign-on compared the entered credential with the stored one in
     * cleartext at {@code app/cbl/COSGN00C.cbl} line 223; that comparison is replaced by a hashed
     * verification, which is a deliberate and documented departure from byte parity. Neither the legacy
     * source line nor any sign-on message literal is reproduced here - the message contract belongs to the
     * message catalogue service's own test.</p>
     *
     * <p>Both halves are asserted. A scheme registered as a component but never required would document
     * authentication without the document ever expecting it; a requirement naming a key the component
     * section does not define would produce a document that references an undefined scheme. Either half
     * alone reads plausibly and cannot be honoured.</p>
     */
    @Nested
    @DisplayName("the bearer security scheme")
    final class BearerSecurityScheme {

        @Test
        @DisplayName("the component section registers exactly one security scheme, under the bearer key")
        void theComponentSectionRegistersTheBearerScheme() {
            assertThat(publishedDocument().getComponents().getSecuritySchemes())
                    .hasSize(1)
                    .containsKey(EXPECTED_SCHEME_KEY);
        }

        @Test
        @DisplayName("the registered scheme is HTTP bearer carrying a JWT")
        void theRegisteredSchemeIsHttpBearerCarryingAJwt() {
            final SecurityScheme scheme = registeredScheme(publishedDocument());

            assertThat(scheme).isNotNull();
            assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
            assertThat(scheme.getScheme()).isEqualTo(EXPECTED_HTTP_SCHEME);
            assertThat(scheme.getBearerFormat()).isEqualTo(EXPECTED_BEARER_FORMAT);
        }

        @Test
        @DisplayName("the scheme description states where the rule is enforced, which addresses it applies "
                + "to and that the declaration grants nothing, rather than merely being non-empty")
        void theSchemeDescriptionMakesEveryRequiredStatement() {
            final String description = registeredScheme(publishedDocument()).getDescription();

            final Map<String, String> missing = new LinkedHashMap<>();
            REQUIRED_SCHEME_CLAIMS.forEach((whatItTells, statement) -> {
                if (description == null || !description.contains(statement)) {
                    missing.put(whatItTells, statement);
                }
            });

            assertThat(missing)
                    .as("every statement the scheme description is required to make must be present")
                    .isEmpty();
        }

        @Test
        @DisplayName("the scheme description names no address beyond the three the filter chain owns, so the "
                + "published contract cannot describe a rule the chain does not enforce")
        void theSchemeDescriptionNamesNoThirdAddress() {
            // The three addresses are interpolated from SecurityConfig rather than written out in the
            // description, which is what keeps the contract and the rule identical. Asserting that they
            // are present does not prove a fourth is absent - and an unowned address would be a documented
            // rule with nothing enforcing it, which is worse than an undocumented one.
            final String description = registeredScheme(publishedDocument()).getDescription();

            final List<String> addresses = new ArrayList<>();
            ADDRESS_SHAPED_LITERAL.matcher(description)
                    .results()
                    .forEach(match -> addresses.add(match.group()));

            assertThat(addresses)
                    .as("the description must name the addresses the chain owns, or this "
                            + "census would pass by finding nothing at all")
                    .isNotEmpty();
            assertThat(addresses)
                    .as("no address beyond the three the filter chain owns")
                    .containsOnly(SecurityConfig.SIGN_ON_PATH, SecurityConfig.ADMIN_PATH_PREFIX,
                            SecurityConfig.BATCH_PATH_PREFIX);
        }

        @Test
        @DisplayName("the scheme is also applied as a document-wide requirement, with no scope, because "
                + "HTTP bearer authentication has none")
        void theSchemeIsAlsoAppliedAsADocumentWideRequirement() {
            final List<SecurityRequirement> requirements = publishedDocument().getSecurity();

            assertThat(requirements).isNotNull().isNotEmpty();
            assertThat(requirements.getFirst()).containsOnlyKeys(EXPECTED_SCHEME_KEY);
            assertThat(requirements.getFirst().get(EXPECTED_SCHEME_KEY)).isEmpty();
        }

        @Test
        @DisplayName("every key named by a requirement is a key the component section defines, so the "
                + "document cannot reference a scheme it never declared")
        void everyRequirementKeyIsADefinedComponentKey() {
            final OpenAPI document = publishedDocument();

            final List<String> requiredKeys = document.getSecurity().stream()
                    .flatMap(requirement -> requirement.keySet().stream())
                    .toList();

            assertThat(requiredKeys).isNotEmpty().containsOnly(EXPECTED_SCHEME_KEY);
            assertThat(document.getComponents().getSecuritySchemes().keySet()).containsAll(requiredKeys);
        }
    }

    // WHAT THE DOCUMENT MUST NOT RESTATE

    /**
     * Verifies that the configuration restates none of the addresses that configuration owns.
     *
     * <p>The address the document is served from and the address the rendered viewer is served from are both
     * declared under the {@code springdoc} key of {@code application.yml}. A value that must have exactly one
     * home would drift the moment it acquired a second, so neither appears in the constructed document.</p>
     */
    @Nested
    @DisplayName("addresses the document must not restate")
    final class AddressesTheDocumentMustNotRestate {

        @Test
        @DisplayName("the document declares no server, so the served address is derived from the request "
                + "rather than restated in code")
        void theDocumentDeclaresNoServer() {
            assertThat(publishedDocument().getServers())
                    .as("a literal server address would be wrong behind a published container port or "
                            + "behind a harness on an ephemeral port")
                    .isNull();
        }

        @Test
        @DisplayName("the constructed document declares no operation path of its own, because the paths are "
                + "contributed by the controller scan and not maintained here")
        void theDocumentDeclaresNoOperationPath() {
            // This asserts a property of the configuration bean, not of the published system. Read on its
            // own it once implied a module with no operations at all, which is what the review objected
            // to; the companion test below now requires the delivered inventory, so the two together say
            // "this bean maintains no inventory" and "an inventory exists", which is the intended design.
            final OpenAPI document = publishedDocument();

            assertThat(document.getPaths())
                    .as("an operation inventory maintained here would drift from the controllers the "
                            + "moment either changed")
                    .isNull();
        }

        @Test
        @DisplayName("a scanned controller surface exists for the document to describe, so the absent "
                + "inventory above is a delegation rather than an empty system")
        void aScannedControllerSurfaceExistsToContributePaths() {
            // The review's finding was that nothing in the module carried @RestController, so the document
            // had nothing to publish and the assertion above was vacuously satisfied by an empty system.
            // Requiring a delivered surface here is what distinguishes the two states. It is asserted on
            // the production sources rather than on a booted context so that it holds without a servlet,
            // a datasource or key material - the same reason every other structural audit in this suite
            // reads the tree.
            final List<String> controllers = new ArrayList<>();
            final Path productionRoot = Path.of("src", "main", "java");
            assertThat(productionRoot)
                    .as("the production source root must be readable, or this assertion is vacuous")
                    .isDirectory();

            try (Stream<Path> tree = Files.walk(productionRoot)) {
                for (final Path path : tree.filter(Files::isRegularFile)
                        .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    final String text = Files.readString(path, StandardCharsets.UTF_8);
                    if (text.contains("@RestController")) {
                        controllers.add(path.getFileName().toString());
                    }
                }
            } catch (final IOException problem) {
                throw new UncheckedIOException("unable to walk " + productionRoot, problem);
            }

            assertThat(controllers)
                    .as("without a scanned controller the published document has no operation to describe")
                    .isNotEmpty()
                    .contains("AuthController.java");
        }

        @Test
        @DisplayName("the delivered sign-on operation is documented, so the scan contributes a described "
                + "operation rather than an unlabelled path")
        void theDeliveredOperationIsDocumented() {
            assertThat(AuthController.class.getAnnotation(RestController.class)).isNotNull();
            assertThat(Arrays.stream(AuthController.class.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(PostMapping.class) != null)
                    .filter(method -> method.getAnnotation(Operation.class) != null)
                    .count())
                    .as("springdoc publishes a summary and responses only where they are declared")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("neither the document address nor the viewer address is hardcoded anywhere in the "
                + "constructed document")
        void neitherServedAddressIsHardcoded() {
            assertThat(serialized(publishedDocument()))
                    .as("both addresses are owned by configuration and must appear in no constructed value")
                    .doesNotContain(CONFIGURED_API_DOCS_PATH)
                    .doesNotContain(CONFIGURED_VIEWER_PATH);
        }
    }

    // CONTRACT SCHEMA PUBLICATION

    /**
     * Verifies that the document publishes a named schema for every request and response contract.
     *
     * <p>Without this, the document declares who may call the module and says nothing about what they may
     * send or expect: a consumer would have thirty-two record types to reverse-engineer from source. The
     * schemas are derived from the declaring types rather than written here, so these tests assert that the
     * derivation reached every type and produced one uniquely named schema per shape.</p>
     *
     * <p><strong>Why the expected names are enumerated rather than computed.</strong> Computing them from the
     * configuration's own roster would assert only that the roster equals itself. The list below is written
     * independently, from the contract package's own membership, so a type added to the package and forgotten
     * in the roster fails here — which is the omission worth catching.</p>
     */
    @Nested
    @DisplayName("contract schema publication")
    final class ContractSchemaPublication {

        /**
         * Every request and response type declared in the contract package, by simple name.
         *
         * <p>Thirty-two entries, matching the thirty-two files of {@code com.carddemo.api.dto}. Each is a
         * top-level type a consumer sends or receives.</p>
         */
        private static final List<String> CONTRACT_FAMILIES = List.of(
                "AccountUpdateRequest", "AccountUpdateResponse", "AccountViewResponse",
                "BatchJobExecutionResponse", "BatchJobLaunchRequest", "BatchJobLaunchResponse",
                "BillPaymentRequest", "BillPaymentResponse", "CardDetailRequest", "CardDetailResponse",
                "CardListRequest", "CardListResponse", "CardUpdateRequest", "CardUpdateResponse",
                "ErrorResponse", "FieldErrorDecorator", "MenuResponse", "NavigationContext",
                "PageMetadata", "ReportRequest", "ReportResponse", "ScreenWorkArea", "SignOnRequest",
                "SignOnResponse", "StatementSummary", "TransactionAddRequest",
                "TransactionAddResponse", "TransactionListRequest", "TransactionListResponse",
                "TransactionViewResponse", "UserRequest", "UserResponse");

        /**
         * Every nested shape that is published as a schema of its own rather than inlined.
         *
         * <p>A nested record becomes its own named schema because it appears as the element type of a
         * collection or as a component of another shape; a nested enumeration does not, because its permitted
         * values are inlined on the property that declares it. These eight are the record and record-like
         * shapes, and the list is asserted to be complete so an inlined shape cannot quietly become a named
         * one, or the reverse, without this test noticing.</p>
         */
        private static final List<String> NESTED_SHAPES = List.of(
                "AdminMenuOption", "CardListRow", "FieldError", "MarkedField", "PageCursorRequest",
                "TransactionRow", "UserMenuOption", "UserRow");

        @Test
        @DisplayName("every contract family is published as a named schema, so a consumer reads the shape "
                + "from the document rather than from the record source")
        void everyContractFamilyIsPublished() {
            assertThat(publishedDocument().getComponents().getSchemas().keySet())
                    .as("a family missing here is a family absent from the configuration's roster")
                    .containsAll(CONTRACT_FAMILIES);
        }

        @Test
        @DisplayName("the roster holds one entry per contract family plus the single nested cursor shape, "
                + "which is what its own description claims")
        void theRosterHoldsEveryFamilyAndTheOneNamedNestedShape() {
            final List<String> rosterNames = new PublishedContractTypeRoster().publishedContractTypes()
                    .stream()
                    .map(Class::getSimpleName)
                    .toList();

            assertThat(rosterNames)
                    .as("a family declared in the contract package and forgotten in the roster is a "
                            + "shape the published document would never describe")
                    .containsAll(CONTRACT_FAMILIES);
            assertThat(rosterNames)
                    .as("the roster is one entry per family plus the one nested shape no family "
                            + "references by property, so any other size means the description above it "
                            + "no longer holds")
                    .hasSize(CONTRACT_FAMILIES.size() + 1);
            assertThat(rosterNames)
                    .as("that one extra entry, named exactly once")
                    .containsOnlyOnce("PageCursorRequest");
        }

        @Test
        @DisplayName("every nested shape is published under its own name, so a collection element is "
                + "described once and referenced rather than restated at each use")
        void everyNestedShapeIsPublished() {
            assertThat(publishedDocument().getComponents().getSchemas().keySet())
                    .containsAll(NESTED_SHAPES);
        }

        @Test
        @DisplayName("the schema set is exactly the contract families and their nested shapes, so nothing "
                + "unrelated is published and nothing declared is omitted")
        void theSchemaSetIsExactlyTheContractShapes() {
            final List<String> expected = new ArrayList<>(CONTRACT_FAMILIES);
            expected.addAll(NESTED_SHAPES);

            assertThat(publishedDocument().getComponents().getSchemas().keySet())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("no two shapes collide on a name, which is what would silently discard one of them")
        void noTwoShapesCollideOnAName() {
            final List<String> expected = new ArrayList<>(CONTRACT_FAMILIES);
            expected.addAll(NESTED_SHAPES);

            assertThat(expected)
                    .as("a schema map is keyed by name, so two shapes sharing a simple name would leave "
                            + "only one published and the count would fall below the declared total")
                    .doesNotHaveDuplicates();
            assertThat(publishedDocument().getComponents().getSchemas().keySet())
                    .hasSize(expected.size());
        }

        @Test
        @DisplayName("every published schema describes a shape rather than standing empty, so a derivation "
                + "that half-ran is caught")
        void everyPublishedSchemaDescribesAShape() {
            final List<String> pointers = nodesOf(publishedDocument()).stream()
                    .map(DocumentNode::pointer)
                    .toList();
            final List<String> shapeless = new ArrayList<>(CONTRACT_FAMILIES);
            shapeless.addAll(NESTED_SHAPES);
            shapeless.removeIf(name -> pointers.stream()
                    .anyMatch(pointer -> pointer.startsWith("/components/schemas/" + name + "/properties/")));

            assertThat(shapeless)
                    .as("a named schema declaring no property would describe nothing; offending "
                            + "schemas: %s", shapeless)
                    .isEmpty();
        }

        @Test
        @DisplayName("each nested shape is reached from the document by reference, so its description is "
                + "stated in one place")
        void eachNestedShapeIsReachedByReference() throws JsonProcessingException {
            final String document = serialized(publishedDocument());

            assertThat(NESTED_SHAPES)
                    .allSatisfy(shape -> assertThat(document)
                            .as("%s is published, so some owning shape must point at it", shape)
                            .contains("#/components/schemas/" + shape));
        }

        @Test
        @DisplayName("publishing the shapes leaves the operation inventory and the security scheme exactly "
                + "as they were, because the three are independent concerns")
        void publishingShapesDisturbsNothingElse() {
            final OpenAPI document = publishedDocument();

            assertThat(document.getPaths()).isNull();
            assertThat(document.getComponents().getSecuritySchemes()).hasSize(1);
            assertThat(document.getComponents().getResponses()).hasSize(6);
            assertThat(document.getComponents().getHeaders()).hasSize(1);
            assertThat(document.getComponents().getParameters()).isNull();
        }
    }

    // CREDENTIAL CONTAINMENT

    /**
     * Verifies that publishing the document discloses nothing.
     *
     * <p>The document is serialized with a Jackson mapper and the resulting tree is walked, so the four
     * rules are applied to field names and to values rather than to an undifferentiated run of text. That
     * distinction matters: the document legitimately explains its own security scheme in prose, so a scan
     * that simply searched the serialized text for a word such as {@code password} would fail on the
     * scheme's own description while catching nothing.</p>
     *
     * <p>Every diagnostic names the location and the rule that matched and deliberately never echoes the
     * offending value, because a build log must not print a credential it has just found.</p>
     */
    @Nested
    @DisplayName("credential containment")
    final class CredentialContainment {

        @Test
        @DisplayName("no field of the serialized document is named for a credential and carries a value, and "
                + "no credential-named shape declaration carries one either")
        void noFieldNamedForACredentialCarriesAValue() {
            final List<DocumentNode> nodes = nodesOf(publishedDocument());
            final List<String> offenders = new ArrayList<>();

            nodes.stream()
                    .filter(node -> names(node.fieldName(), CREDENTIAL_FIELD_NAME_FRAGMENTS))
                    .filter(node -> node.value() != null)
                    .filter(node -> !(node.value() instanceof Map<?, ?>))
                    .map(node -> node.pointer() + " carries a value rather than a shape declaration")
                    .forEach(offenders::add);

            nodes.stream()
                    .filter(node -> names(node.fieldName(), VALUE_BEARING_SCHEMA_KEYS))
                    .filter(node -> node.value() != null)
                    .filter(node -> sitsUnderACredentialNamedProperty(node.pointer()))
                    .map(node -> node.pointer() + " declares a value inside a credential-named shape")
                    .forEach(offenders::add);

            assertThat(offenders)
                    .as("a credential-named field may declare that the property exists and how wide it is, "
                            + "and may carry nothing else; offending locations: %s", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("no value of the serialized document is a credential literal")
        void noValueIsACredentialLiteral() {
            final List<String> offenders = new ArrayList<>();
            for (final DocumentNode node : textNodesOf(publishedDocument())) {
                final String text = String.valueOf(node.value());
                for (final Map.Entry<String, Pattern> rule : CREDENTIAL_VALUE_RULES.entrySet()) {
                    if (rule.getValue().matcher(text).find()) {
                        offenders.add(node.pointer() + " matched the " + rule.getKey() + " rule");
                    }
                }
            }

            assertThat(offenders)
                    .as("the published document must carry no credential value; offending locations and "
                            + "rules: %s", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("no value of the serialized document has the shape of a fixed-width credential field")
        void noValueHasTheShapeOfAFixedWidthCredentialField() {
            final List<String> offenders = textNodesOf(publishedDocument()).stream()
                    .filter(node -> CREDENTIAL_SHAPED_BARE_VALUE
                            .matcher(String.valueOf(node.value()).strip())
                            .matches())
                    .filter(node -> !DECLARED_ENUM_CONSTANT_NAMES
                            .contains(String.valueOf(node.value()).strip()))
                    .map(DocumentNode::pointer)
                    .toList();

            assertThat(offenders)
                    .as("the shared cleartext credential the legacy provisioning stream carried in-stream "
                            + "occupied a fixed-width field of uppercase letters, so any bare value of that "
                            + "shape other than a constant of an enumeration this module declares is "
                            + "rejected without the literal ever being named; offending locations: %s",
                            offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("the serialized document carries no example, which is where a credential most easily "
                + "hides in a published contract")
        void theDocumentCarriesNoExample() {
            final List<String> offenders = nodesOf(publishedDocument()).stream()
                    .filter(node -> names(node.fieldName(), EXAMPLE_FIELD_NAME_FRAGMENTS))
                    .filter(node -> node.value() != null)
                    .filter(node -> !("exampleSetFlag".equals(node.fieldName())
                            && Boolean.FALSE.equals(node.value())))
                    .map(DocumentNode::pointer)
                    .toList();

            assertThat(offenders)
                    .as("no example value of any kind may be published; offending locations: %s", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("the scan reaches the document's deepest prose, so a passing scan cannot be a scan "
                + "that walked nothing")
        void theScanReachesTheDocumentsDeepestProse() {
            final List<String> visited = textNodesOf(publishedDocument()).stream()
                    .map(DocumentNode::pointer)
                    .toList();

            assertThat(visited)
                    .as("the walk must reach the nested prose fields, not only the top-level members")
                    .containsAll(DEEPEST_TEXT_POINTERS)
                    .hasSizeGreaterThan(DEEPEST_TEXT_POINTERS.size());
        }

        @Test
        @DisplayName("the registered scheme pre-fills no authorisation value, names no header or parameter "
                + "and carries no extension")
        void theRegisteredSchemePreFillsNothing() {
            final SecurityScheme scheme = registeredScheme(publishedDocument());

            assertThat(scheme.getName()).isNull();
            assertThat(scheme.getIn()).isNull();
            assertThat(scheme.getFlows()).isNull();
            assertThat(scheme.getOpenIdConnectUrl()).isNull();
            assertThat(scheme.get$ref()).isNull();
            assertThat(scheme.getExtensions()).isNull();
        }
    }

    // WHAT THE CLASS CONTRIBUTES TO A CONTAINER

    /**
     * Verifies the bean set the class contributes, proved against a real context rather than asserted.
     *
     * <p>The slice registers nothing but the class under test, so every bean the context reports is a bean
     * this class put there. Each negative is paired with the positive, so a context that failed to start at
     * all could not be mistaken for a clean negative result.</p>
     */
    @Nested
    @DisplayName("what the class contributes to a container")
    final class ContainerContribution {

        /** A slice registering nothing but the class under test. No web server and no datasource. */
        private final ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(OpenApiConfig.class,
                                PublishedContractTypeRoster.class);

        @Test
        @DisplayName("exactly one OpenAPI bean is contributed, and it carries the published title")
        void exactlyOneDocumentBeanIsContributed() {
            this.runner.run(context -> {
                assertThat(context).hasSingleBean(OpenAPI.class);
                assertThat(context.getBeanNamesForType(OpenAPI.class)).hasSize(1);
                assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo(EXPECTED_TITLE);
            });
        }

        @Test
        @DisplayName("no JSON mapper bean is contributed, because the documentation library's own "
                + "auto-configuration owns that")
        void noJsonMapperBeanIsContributed() {
            this.runner.run(context -> {
                assertThat(context).hasSingleBean(OpenAPI.class);
                assertThat(context).doesNotHaveBean(ObjectMapper.class);
            });
        }

        @Test
        @DisplayName("no grouped-API bean is contributed, because the documentation library owns the "
                + "grouping and the controller scan")
        void noGroupedApiBeanIsContributed() {
            this.runner.run(context -> {
                assertThat(context).hasSingleBean(OpenAPI.class);
                assertThat(context).doesNotHaveBean(GroupedOpenApi.class);
            });
        }

        @Test
        @DisplayName("the container-published document falls back to the module coordinate version when "
                + "the context publishes no build information")
        void theContainerPublishedDocumentFallsBackToTheModuleVersion() {
            this.runner.run(context -> assertThat(context.getBean(OpenAPI.class).getInfo().getVersion())
                    .isEqualTo(EXPECTED_MODULE_VERSION));
        }
    }

    // HOW THE PUBLISHED VERSION IS RESOLVED

    /**
     * Verifies the two sources of the published version and the blank-value edge between them.
     */
    @Nested
    @DisplayName("how the published version is resolved")
    final class ContractVersionResolution {

        @Test
        @DisplayName("a version published by the build is used verbatim, so the document tracks the "
                + "artefact actually running")
        void aPublishedVersionIsUsedVerbatim() {
            assertThat(publishedDocumentForBuildVersion("2.7.3").getInfo().getVersion())
                    .isEqualTo("2.7.3");
        }

        @Test
        @DisplayName("a snapshot qualifier survives verbatim rather than being trimmed away")
        void aSnapshotQualifierSurvivesVerbatim() {
            assertThat(publishedDocumentForBuildVersion("2.7.4-SNAPSHOT").getInfo().getVersion())
                    .isEqualTo("2.7.4-SNAPSHOT");
        }

        @Test
        @DisplayName("build information carrying no version falls back to the module coordinate version")
        void buildInformationWithoutAVersionFallsBack() {
            assertThat(publishedDocumentForBuildVersion(null).getInfo().getVersion())
                    .isEqualTo(EXPECTED_MODULE_VERSION);
        }

        @Test
        @DisplayName("a published version made only of whitespace falls back rather than publishing an "
                + "empty version")
        void aBlankPublishedVersionFallsBack() {
            for (final String blank : List.of("", " ", "   ", "\t")) {
                assertThat(publishedDocumentForBuildVersion(blank).getInfo().getVersion())
                        .as("a version made only of whitespace must never reach the document")
                        .isEqualTo(EXPECTED_MODULE_VERSION);
            }
        }

        @Test
        @DisplayName("the build information is consulted once at construction, so every document a single "
                + "configuration produces reports the same version")
        void theBuildInformationIsConsultedOnceAtConstruction() {
            final CountingBuildInformation provider = new CountingBuildInformation();
            final OpenApiConfig configuration = new OpenApiConfig(provider, new PublishedContractTypeRoster());

            final String first = configuration.cardDemoOpenApi().getInfo().getVersion();
            final String second = configuration.cardDemoOpenApi().getInfo().getVersion();

            assertThat(first).isEqualTo(CountingBuildInformation.publishedVersion()).isEqualTo(second);
            assertThat(provider.consultations()).isOne();
        }

        @Test
        @DisplayName("each call yields an independent document, so a consumer that decorates one cannot "
                + "reach the one the container published")
        void eachCallYieldsAnIndependentDocument() {
            final OpenApiConfig configuration = new OpenApiConfig(new AbsentBuildInformation(), new PublishedContractTypeRoster());

            assertThat(configuration.cardDemoOpenApi()).isNotSameAs(configuration.cardDemoOpenApi());
        }
    }
}
