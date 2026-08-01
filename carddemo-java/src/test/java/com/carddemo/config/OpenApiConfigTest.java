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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import com.carddemo.CardDemoApplication;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

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
 * endpoint groups this document describes; the eighteenth drives the shared date-validation subprogram
 * rather than a screen. Those figures are cited here as metadata only. No program, copybook, screen map,
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
     * <p>A field so named must be absent or carry no value. This is the first of four complementary rules,
     * and it is the one that catches a credential parked under an honest label.</p>
     */
    private static final List<String> CREDENTIAL_FIELD_NAME_FRAGMENTS = List.of(
            "password", "passwd", "secret", "credential", "token", "apikey", "api_key", "api-key",
            "authorization");

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
     * <p>No legitimate value in the document has this shape: the specification version and the module
     * version carry dots, the title and both descriptions carry whitespace and lowercase letters, the
     * licence name carries whitespace, the licence URL and the HTTP scheme are lowercase, and the scheme
     * type and token format are shorter than the floor.</p>
     */
    private static final Pattern CREDENTIAL_SHAPED_BARE_VALUE = Pattern.compile("[A-Z0-9]{6,}");

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
        return new OpenApiConfig(new AbsentBuildInformation()).cardDemoOpenApi();
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
        return new OpenApiConfig(new PresentBuildInformation(new BuildProperties(entries)))
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
        @DisplayName("the description is present and says something the title does not, so a consumer "
                + "reading the document learns what the surface is")
        void theDescriptionIsPresentAndDistinctFromTheTitle() {
            final Info info = publishedDocument().getInfo();

            assertThat(info.getDescription())
                    .isNotBlank()
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
            assertThat(scheme.getDescription()).isNotBlank();
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
        @DisplayName("the document declares no operation path at all, because the paths are contributed by "
                + "the controller scan")
        void theDocumentDeclaresNoOperationPath() {
            final OpenAPI document = publishedDocument();

            assertThat(document.getPaths()).isNull();
            assertThat(document.getComponents().getSchemas()).isNull();
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
        @DisplayName("no field of the serialized document is named for a credential and carries a value")
        void noFieldNamedForACredentialCarriesAValue() {
            final List<String> offenders = nodesOf(publishedDocument()).stream()
                    .filter(node -> names(node.fieldName(), CREDENTIAL_FIELD_NAME_FRAGMENTS))
                    .filter(node -> node.value() != null)
                    .map(DocumentNode::pointer)
                    .toList();

            assertThat(offenders)
                    .as("the published document must carry no value under a credential-named field; "
                            + "offending locations: %s", offenders)
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
                    .map(DocumentNode::pointer)
                    .toList();

            assertThat(offenders)
                    .as("the shared cleartext credential the legacy provisioning stream carried in-stream "
                            + "occupied a fixed-width field of uppercase letters, so any bare value of that "
                            + "shape is rejected without the literal ever being named; offending "
                            + "locations: %s", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("the serialized document carries no example, which is where a credential most easily "
                + "hides in a published contract")
        void theDocumentCarriesNoExample() {
            final List<String> offenders = nodesOf(publishedDocument()).stream()
                    .filter(node -> names(node.fieldName(), EXAMPLE_FIELD_NAME_FRAGMENTS))
                    .filter(node -> node.value() != null)
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
                new ApplicationContextRunner().withUserConfiguration(OpenApiConfig.class);

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
            final OpenApiConfig configuration = new OpenApiConfig(provider);

            final String first = configuration.cardDemoOpenApi().getInfo().getVersion();
            final String second = configuration.cardDemoOpenApi().getInfo().getVersion();

            assertThat(first).isEqualTo(CountingBuildInformation.publishedVersion()).isEqualTo(second);
            assertThat(provider.consultations()).isOne();
        }

        @Test
        @DisplayName("each call yields an independent document, so a consumer that decorates one cannot "
                + "reach the one the container published")
        void eachCallYieldsAnIndependentDocument() {
            final OpenApiConfig configuration = new OpenApiConfig(new AbsentBuildInformation());

            assertThat(configuration.cardDemoOpenApi()).isNotSameAs(configuration.cardDemoOpenApi());
        }
    }
}

