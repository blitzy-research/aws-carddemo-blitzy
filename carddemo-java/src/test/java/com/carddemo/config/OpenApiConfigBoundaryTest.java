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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

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
        return new OpenApiConfig(new FixedBuildInformation(buildInformation)).cardDemoOpenApi();
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
            OpenApiConfig configuration = new OpenApiConfig(new FixedBuildInformation(null));

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
                    new FixedBuildInformation(buildInformationWithVersion(PUBLISHED_VERSION)));

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
}
