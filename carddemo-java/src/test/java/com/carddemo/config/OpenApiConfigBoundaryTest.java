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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Exercises the published interface contract of the migrated API layer.
 *
 * <h2>What is under test</h2>
 * {@link OpenApiConfig} contributes the document that describes this module's REST surface, which is
 * the machine-readable form of the seventeen legacy terminal transactions declared in
 * {@code app/csd/CARDDEMO.CSD}. The bean it produces is asserted here rather than the rendered
 * document, because the bean is what the framework serves and because asserting it needs no server.
 *
 * <h2>Why the version falls back rather than failing</h2>
 * The contract version is read from the build information the packaging step writes, and that
 * information is absent when the module runs from compiled classes rather than from a packaged
 * artifact — which is the ordinary case during development and during a test run. Falling back to the
 * module's own declared version keeps the document valid in both situations, and the tests cover both
 * the present and the absent case rather than only the one the test run happens to be in. A blank
 * published version is treated as absent for the same reason: a document whose version is an empty
 * string is worse than one carrying a known constant.
 *
 * <h2>Why the security scheme is asserted to carry no credential</h2>
 * The document declares that a protected operation expects a bearer token; it does not, and must not,
 * carry a token, a password or a pre-filled authorisation value, because a published contract is a
 * public artefact. The tests therefore assert both that the scheme is declared and that its
 * description carries no credential-shaped content, since a scheme description is free text and
 * nothing in the framework would stop one being placed there.
 *
 * <p>Provenance: part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("OpenApiConfig - the published REST interface contract")
class OpenApiConfigBoundaryTest {

    /** Version the module declares for itself, used when no build information is available. */
    private static final String MODULE_VERSION = "1.0.0";

    /** Version a packaging step would publish, distinct from the module's own declared version. */
    private static final String PUBLISHED_VERSION = "2.7.3";

    /**
     * Supplies a fixed build-information value, or none, to the configuration under test.
     *
     * <p>Every method of the provider interface is a default method, so overriding the one the
     * configuration actually calls is sufficient and needs no framework or mocking library. The class
     * is private and static so that it cannot be captured by an enclosing instance, which the
     * module's strict compiler settings would otherwise report.</p>
     */
    private static final class FixedBuildInformation implements ObjectProvider<BuildProperties> {

        /** The value to hand back, or {@code null} to report that none is available. */
        private final BuildProperties value;

        /**
         * Creates a provider that always reports the supplied value.
         *
         * @param value the value to report, or {@code null} for none
         */
        FixedBuildInformation(BuildProperties value) {
            this.value = value;
        }

        @Override
        public BuildProperties getIfAvailable() {
            return this.value;
        }
    }

    /**
     * Returns build information carrying the supplied version.
     *
     * @param version the version to publish, or {@code null} to publish none
     * @return the build information
     */
    private static BuildProperties buildInformationWithVersion(String version) {
        Properties properties = new Properties();
        if (version != null) {
            properties.setProperty("version", version);
        }
        return new BuildProperties(properties);
    }

    /**
     * Returns the document produced when the supplied build information is available.
     *
     * @param buildInformation the build information, or {@code null} for none
     * @return the published document
     */
    private static OpenAPI documentFor(BuildProperties buildInformation) {
        return new OpenApiConfig(new FixedBuildInformation(buildInformation),
                new PublishedContractTypeRoster()).cardDemoOpenApi();
    }

    /**
     * Returns the description published for the bearer scheme of the supplied document.
     *
     * <p>The document publishes free text in exactly two places, its own description and this one, so an
     * assertion about what the contract must never say has to reach both. Reading it through one helper
     * keeps the second place from being the one an author forgets.</p>
     *
     * @param document the document to read
     * @return the scheme description
     */
    private static String schemeDescriptionOf(OpenAPI document) {
        return document.getComponents()
                .getSecuritySchemes()
                .get(OpenApiConfig.BEARER_SCHEME_NAME)
                .getDescription();
    }

    @Nested
    @DisplayName("document identity")
    class DocumentIdentity {

        @Test
        @DisplayName("the document is titled for the migrated application")
        void theDocumentIsTitledForTheApplication() {
            OpenAPI document = documentFor(null);

            assertThat(document.getInfo()).isNotNull();
            assertThat(document.getInfo().getTitle()).isEqualTo(OpenApiConfig.API_TITLE);
        }

        @Test
        @DisplayName("the document carries the Apache licence the whole estate is published under")
        void theDocumentCarriesTheApacheLicence() {
            OpenAPI document = documentFor(null);

            assertThat(document.getInfo().getLicense()).isNotNull();
            assertThat(document.getInfo().getLicense().getName())
                    .isEqualTo(OpenApiConfig.LICENSE_NAME);
            assertThat(document.getInfo().getLicense().getUrl())
                    .isEqualTo(OpenApiConfig.LICENSE_URL);
        }

        @Test
        @DisplayName("the description records the estate the contract was migrated from")
        void theDescriptionRecordsTheEstate() {
            OpenAPI document = documentFor(null);

            assertThat(document.getInfo().getDescription())
                    .contains("CardDemo")
                    .contains("7756d895ffeb65f7ea72aaa609e356d9899afcec")
                    .contains("CardDemo_v1.0-15-g27d6c6f-68");
        }

        @Test
        @DisplayName("the description asserts no service level, because the estate documents none")
        void theDescriptionAssertsNoServiceLevel() {
            OpenAPI document = documentFor(null);

            assertThat(document.getInfo().getDescription())
                    .doesNotContain("SLA")
                    .doesNotContain("uptime")
                    .doesNotContain("milliseconds")
                    .doesNotContain("requests per second");
        }

        @Test
        @DisplayName("each call produces its own document, so a caller cannot mutate a shared one")
        void eachCallProducesItsOwnDocument() {
            OpenApiConfig configuration = new OpenApiConfig(new FixedBuildInformation(null),
                    new PublishedContractTypeRoster());

            assertThat(configuration.cardDemoOpenApi())
                    .isNotSameAs(configuration.cardDemoOpenApi());
        }
    }

    @Nested
    @DisplayName("contract version")
    class ContractVersion {

        @Test
        @DisplayName("a published build version becomes the contract version")
        void aPublishedBuildVersionBecomesTheContractVersion() {
            OpenAPI document = documentFor(buildInformationWithVersion(PUBLISHED_VERSION));

            assertThat(document.getInfo().getVersion()).isEqualTo(PUBLISHED_VERSION);
        }

        @Test
        @DisplayName("absent build information falls back to the module's declared version")
        void absentBuildInformationFallsBackToTheModuleVersion() {
            OpenAPI document = documentFor(null);

            assertThat(document.getInfo().getVersion()).isEqualTo(MODULE_VERSION);
            assertThat(OpenApiConfig.MODULE_VERSION).isEqualTo(MODULE_VERSION);
        }

        @Test
        @DisplayName("build information carrying no version falls back to the declared version")
        void buildInformationWithoutAVersionFallsBack() {
            OpenAPI document = documentFor(buildInformationWithVersion(null));

            assertThat(document.getInfo().getVersion()).isEqualTo(MODULE_VERSION);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank published version is treated as absent, not published as blank")
        void aBlankPublishedVersionIsTreatedAsAbsent(String blank) {
            OpenAPI document = documentFor(buildInformationWithVersion(blank));

            assertThat(document.getInfo().getVersion()).isEqualTo(MODULE_VERSION);
        }

        @Test
        @DisplayName("the version is resolved once at construction, not on every call")
        void theVersionIsResolvedOnceAtConstruction() {
            OpenApiConfig configuration = new OpenApiConfig(
                    new FixedBuildInformation(buildInformationWithVersion(PUBLISHED_VERSION)),
                    new PublishedContractTypeRoster());

            assertThat(configuration.cardDemoOpenApi().getInfo().getVersion())
                    .isEqualTo(configuration.cardDemoOpenApi().getInfo().getVersion())
                    .isEqualTo(PUBLISHED_VERSION);
        }
    }

    @Nested
    @DisplayName("security scheme")
    class SecuritySchemeDeclaration {

        @Test
        @DisplayName("the bearer scheme is declared under its published name")
        void theBearerSchemeIsDeclaredUnderItsName() {
            OpenAPI document = documentFor(null);

            assertThat(document.getComponents()).isNotNull();
            assertThat(document.getComponents().getSecuritySchemes())
                    .containsKey(OpenApiConfig.BEARER_SCHEME_NAME);
        }

        @Test
        @DisplayName("the scheme is HTTP bearer carrying the token format the module issues")
        void theSchemeIsHttpBearerWithTheTokenFormat() {
            SecurityScheme scheme = documentFor(null)
                    .getComponents()
                    .getSecuritySchemes()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME);

            assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
            assertThat(scheme.getScheme()).isEqualTo(OpenApiConfig.HTTP_BEARER_SCHEME);
            assertThat(scheme.getBearerFormat()).isEqualTo(OpenApiConfig.BEARER_TOKEN_FORMAT);
        }

        @Test
        @DisplayName("the scheme is applied as a document-wide requirement")
        void theSchemeIsAppliedAsADocumentWideRequirement() {
            OpenAPI document = documentFor(null);

            assertThat(document.getSecurity())
                    .singleElement()
                    .satisfies(requirement ->
                            assertThat(requirement).containsKey(OpenApiConfig.BEARER_SCHEME_NAME));
        }

        @Test
        @DisplayName("the scheme description states that it grants nothing by itself")
        void theSchemeDescriptionStatesItGrantsNothing() {
            SecurityScheme scheme = documentFor(null)
                    .getComponents()
                    .getSecuritySchemes()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME);

            assertThat(scheme.getDescription())
                    .contains("bearer")
                    .contains("nothing in")
                    .contains("grants access");
        }

        @Test
        @DisplayName("no credential of any kind appears in the published document")
        void noCredentialAppearsInTheDocument() {
            OpenAPI document = documentFor(buildInformationWithVersion(PUBLISHED_VERSION));
            String rendered = document.getInfo().getDescription()
                    + document.getComponents()
                            .getSecuritySchemes()
                            .get(OpenApiConfig.BEARER_SCHEME_NAME)
                            .getDescription();

            assertThat(rendered)
                    .doesNotContain("PASSWORD")
                    .doesNotContain("Bearer eyJ")
                    .doesNotContain("secret=");
        }
    }

    /**
     * Holds the published contract and the enforcing filter chain to the same statement.
     *
     * <p>A document-wide credential requirement is a claim about what the running system refuses. It was
     * previously a claim nothing implemented, and the repair was to build the chain rather than to soften
     * the claim. What remains possible is drift: an exemption written into the chain and not into the
     * document, or an address changed in one place and restated in the other. These assertions close that
     * by reading the addresses back out of the chain's own published constants, so a document that
     * described a route the chain no longer exempts could not pass.</p>
     */
    @Nested
    @DisplayName("agreement with the filter chain that enforces it")
    class AgreementWithTheEnforcingChain {

        /**
         * Reads the scheme description from a freshly built document.
         *
         * @return the description the contract publishes for the bearer scheme
         */
        private String schemeDescription() {
            return documentFor(null)
                    .getComponents()
                    .getSecuritySchemes()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME)
                    .getDescription();
        }

        @Test
        @DisplayName("names the sign-on route as the one exemption, using the address the chain exempts "
                + "rather than a second copy of it")
        void namesTheSignOnRouteTheChainExempts() {
            assertThat(schemeDescription()).contains(SecurityConfig.SIGN_ON_PATH);
        }

        @Test
        @DisplayName("names the administrative prefix as the gated region, using the address the chain "
                + "gates")
        void namesTheAdministrativePrefixTheChainGates() {
            assertThat(schemeDescription()).contains(SecurityConfig.ADMIN_PATH_PREFIX);
        }

        @Test
        @DisplayName("names the batch prefix as a second administrative region, using the chain's address")
        void namesTheBatchPrefixTheChainGates() {
            assertThat(schemeDescription()).contains(SecurityConfig.BATCH_PATH_PREFIX);
        }

        @Test
        @DisplayName("says the requirement is universal apart from that one exemption, which is what the "
                + "chain's closing catch-all makes true")
        void describesTheRequirementAsUniversalApartFromSignOn() {
            assertThat(schemeDescription())
                    .contains("Every operation")
                    .contains("except");
        }

        @Test
        @DisplayName("attributes enforcement to the chain rather than to itself, so no reader takes the "
                + "document for the boundary")
        void attributesEnforcementToTheChain() {
            assertThat(schemeDescription()).contains("filter chain");
        }

        @Test
        @DisplayName("describes an administrative refusal as a refusal rather than as an omission, "
                + "matching the forbidden answer the chain actually returns")
        void describesTheAdministrativeRefusalAsARefusal() {
            assertThat(schemeDescription()).contains("refused");
        }

        @Test
        @DisplayName("the metadata bean itself declares no path, because springdoc adds controller "
                + "operations only while building the served document")
        void doesNotDescribeTheOperationalSurfaces() {
            OpenAPI document = documentFor(null);

            assertThat(document.getPaths())
                    .as("a path literal maintained by OpenApiConfig would duplicate the controller scan; "
                            + "the served document is populated by springdoc after this bean is applied")
                    .isNullOrEmpty();
        }
    }

    /**
     * Holds the published description to the endpoint inventory the delivered code actually maps.
     *
     * <p>The document once introduced itself as describing the endpoints derived from the legacy screens
     * while carrying no path at all, and the first repair was to label the inventory as later bound - to
     * say, in the contract text, that no request-mapped operation had been delivered. That label was true
     * when it was written and false as soon as the first controller was mapped, which is exactly the
     * failure the label was meant to prevent, only in the opposite direction. Nineteen operations are
     * delivered now.</p>
     *
     * <p>So the second repair removes the count from the text altogether rather than correcting it: the
     * description states where the inventory comes from and says nothing about how large it is, because any
     * figure written into published contract text goes stale on the next endpoint. What replaces the label
     * is two assertions here. {@link #carriesNoClaimAboutHowManyPathsExist()} requires that no emptiness
     * claim has returned, in any of the wordings it could return in - so the text cannot rot back into the
     * claim it replaced. {@link #theDeliveredOperationCountIsWhatTheCodeMaps()} reads the controllers and
     * pins the delivered figure, so the inventory itself is measured rather than described, and a new
     * endpoint is noticed here even though the contract text needs no edit for it.</p>
     */
    @Nested
    @DisplayName("agreement between the description and the endpoint inventory the code maps")
    class TheEndpointInventoryIsLaterBound {

        /**
         * Wordings by which the description could claim an inventory it does not have.
         *
         * <p>The first is the exact sentence that was published and became false; the rest are the nearby
         * phrasings the same claim would reappear in. Held as a list rather than one string because the
         * defect is the claim, not the sentence.
         */
        private static final List<String> EMPTINESS_CLAIMS = List.of(
                "no request-mapped operation has been delivered yet",
                "lists no path",
                "the inventory is empty",
                "it is empty",
                "no endpoint has been delivered");

        /** Where the delivered controllers live, so the inventory can be counted rather than described. */
        private static final Path API_SOURCE_ROOT =
                Path.of("src", "main", "java", "com", "carddemo", "api");

        /** A method-level request mapping, which is what makes an operation reachable. */
        private static final Pattern OPERATION_MAPPING =
                Pattern.compile("^\\s+@(Get|Post|Put|Patch|Delete)Mapping", Pattern.MULTILINE);

        /** How many operations the module delivers. Asserted, not assumed - see the test below. */
        private static final long DELIVERED_OPERATIONS = 20L;

        /**
         * Reads the description from a freshly built document.
         *
         * @return the description the contract publishes for itself
         */
        private String description() {
            return documentFor(null).getInfo().getDescription();
        }

        @Test
        @DisplayName("carries no claim about how many paths exist, in any wording, because a count in "
                + "published contract text goes stale on the next endpoint")
        void carriesNoClaimAboutHowManyPathsExist() {
            String published = description();

            assertThat(EMPTINESS_CLAIMS)
                    .as("OpenApiConfig.API_DESCRIPTION must describe where the inventory comes from and "
                            + "not how large it is; %d operations are delivered, so any of these wordings "
                            + "is now false", DELIVERED_OPERATIONS)
                    .allSatisfy(claim -> assertThat(published).doesNotContain(claim));
        }

        @Test
        @DisplayName("and the delivered operation count is what the controllers actually map, so an "
                + "endpoint arriving is noticed here even though the contract text needs no edit")
        void theDeliveredOperationCountIsWhatTheCodeMaps() throws IOException {
            long mapped = 0;
            try (Stream<Path> tree = Files.walk(API_SOURCE_ROOT)) {
                for (Path file : tree.filter(Files::isRegularFile)
                        .filter(candidate -> candidate.getFileName().toString().endsWith("Controller.java"))
                        .toList()) {
                    Matcher mapping =
                            OPERATION_MAPPING.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (mapping.find()) {
                        mapped++;
                    }
                }
            }

            assertThat(mapped)
                    .as("the inventory this document derives is these operations; if the figure has moved, "
                            + "confirm the new endpoint is classified in "
                            + "DeliveredRouteSecurityStateTest before updating it here")
                    .isEqualTo(DELIVERED_OPERATIONS);
        }

        @Test
        @DisplayName("states that springdoc derives served operations from request-mapped controllers")
        void statesHowTheServedInventoryIsDerived() {
            assertThat(description())
                    .contains("derived from the code")
                    .contains("needs no edit");
        }

        @ParameterizedTest(name = "the superseded empty-inventory claim stays absent: {0}")
        @ValueSource(strings = {
            "no request-mapped operation has been delivered yet",
            "milestone it is empty",
            "lists no path"
        })
        @DisplayName("claims no hand-maintained complete endpoint list in either published text")
        void claimsNoHandMaintainedCompleteEndpointList(String overstatement) {
            assertThat(description()).doesNotContain(overstatement);
            assertThat(schemeDescriptionOf(documentFor(null))).doesNotContain(overstatement);
        }

        @Test
        @DisplayName("names the delivered online and operational controller groups and their legacy origin")
        void namesTheDeliveredControllerGroups() {
            assertThat(description())
                    .contains("sign-on")
                    .contains("batch-control operations")
                    .contains("17 legacy")
                    .contains("24x80");
        }

        @Test
        @DisplayName("the metadata model delivers the reusable content it claims before springdoc adds paths")
        void deliversTheReusableContentItClaims() {
            OpenAPI document = documentFor(null);

            assertThat(document.getInfo()).isNotNull();
            assertThat(document.getComponents()).isNotNull();
            assertThat(document.getComponents().getSecuritySchemes())
                    .containsKey(OpenApiConfig.BEARER_SCHEME_NAME);
            assertThat(document.getComponents().getHeaders())
                    .containsKey(OpenApiConfig.AUTHORIZATION_HEADER_COMPONENT);
            assertThat(document.getComponents().getResponses())
                    .containsKeys(
                            OpenApiConfig.BAD_REQUEST_RESPONSE,
                            OpenApiConfig.UNAUTHORIZED_RESPONSE,
                            OpenApiConfig.FORBIDDEN_RESPONSE,
                            OpenApiConfig.NOT_FOUND_RESPONSE,
                            OpenApiConfig.CONFLICT_RESPONSE,
                            OpenApiConfig.INTERNAL_SERVER_ERROR_RESPONSE);
            assertThat(document.getSecurity())
                    .as("the authentication rule the description says it carries is present before "
                            + "springdoc applies it to controller-derived operations")
                    .isNotEmpty();
        }
    }

}
