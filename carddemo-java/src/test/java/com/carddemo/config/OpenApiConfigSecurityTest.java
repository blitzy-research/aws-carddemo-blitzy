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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Unit test for {@link OpenApiConfig}, which contributes the OpenAPI document that <em>is</em> this
 * module's machine-readable interface description.
 *
 * <h2>Why this document is worth a test of its own</h2>
 *
 * <p>Gate 5 requires that every external interface be verified by a local test exercising the real
 * contract, and Gate 8 records the interface description as a sign-off artefact. Because the legacy
 * presentation layer was a 3270 terminal contract and no browser interface is built, this document is
 * the <em>only</em> published description of the REST surface. There is no hand-maintained contract
 * file that could drift, which is a strength - but it also means that if the description bean stopped
 * being contributed, or lost its security scheme, nothing else in the module would notice.</p>
 *
 * <h2>The document is built unconditionally, and that is deliberate</h2>
 *
 * <p>The shared configuration baseline ships with {@code springdoc.api-docs.enabled} and
 * {@code springdoc.swagger-ui.enabled} both {@code false}, so the HTTP surface that would <em>serve</em>
 * this document is closed unless a profile opens it. The bean below is nevertheless contributed under
 * every profile, which is precisely what lets this test assert the contract without opening a port. The
 * two facts are complementary rather than contradictory: closing the surface is a transport decision,
 * and building the document is a contract decision.</p>
 *
 * <h2>The version has two sources and both must work</h2>
 *
 * <p>The build-information bean exists only when the build publishes a build-information resource, so it
 * is injected as a provider rather than as a required collaborator - its absence must not fail start-up.
 * Both branches are exercised below, including the blank-version branch, because a published-but-blank
 * version would otherwise reach the document and describe the running artefact as having no version at
 * all.</p>
 */
@DisplayName("OpenApiConfig - the published interface description")
class OpenApiConfigSecurityTest {

    /** A version distinguishable from the module coordinate, to prove the published value is preferred. */
    private static final String PUBLISHED_VERSION = "9.9.9-FROM-BUILD-INFO";

    /**
     * A minimal {@link ObjectProvider} that resolves to a fixed instance, or to nothing.
     *
     * <p>Every method on {@code ObjectProvider} is a default method, so overriding
     * {@link ObjectProvider#getIfAvailable()} alone is sufficient and no unchecked cast is required -
     * which matters, because the test sources compile under the same {@code -Xlint:all -Werror}
     * configuration as the main sources and an unchecked cast would fail the build.
     *
     * @param instance the instance to resolve to, or {@code null} to resolve to nothing
     */
    private record FixedProvider(BuildProperties instance) implements ObjectProvider<BuildProperties> {

        @Override
        public BuildProperties getIfAvailable() {
            return instance;
        }
    }

    private static BuildProperties buildPropertiesWithVersion(final String version) {
        final Properties properties = new Properties();
        if (version != null) {
            properties.setProperty("version", version);
        }
        return new BuildProperties(properties);
    }

    private static OpenAPI documentWith(final BuildProperties buildProperties) {
        return new OpenApiConfig(new FixedProvider(buildProperties)).cardDemoOpenApi();
    }

    @Nested
    @DisplayName("Document identity")
    class DocumentIdentity {

        @Test
        @DisplayName("a document is produced rather than null, so the interface description always exists")
        void aDocumentIsProduced() {
            assertThat(documentWith(null)).isNotNull();
        }

        @Test
        @DisplayName("the document carries the published API title")
        void theDocumentCarriesTheApiTitle() {
            assertThat(documentWith(null).getInfo().getTitle()).isEqualTo(OpenApiConfig.API_TITLE);
            assertThat(OpenApiConfig.API_TITLE).isEqualTo("CardDemo REST API");
        }

        @Test
        @DisplayName("the document carries a non-blank description, so a consumer reading it learns what the surface "
                + "is rather than only which paths exist")
        void theDocumentCarriesANonBlankDescription() {
            assertThat(documentWith(null).getInfo().getDescription()).isNotBlank();
        }

        @Test
        @DisplayName("the document declares the Apache License 2.0 by name and by URL, matching the header carried "
                + "by every legacy source member and by every generated file")
        void theDocumentDeclaresTheApacheLicence() {
            assertThat(documentWith(null).getInfo().getLicense().getName())
                    .isEqualTo(OpenApiConfig.LICENSE_NAME)
                    .isEqualTo("Apache License 2.0");
            assertThat(documentWith(null).getInfo().getLicense().getUrl())
                    .isEqualTo(OpenApiConfig.LICENSE_URL)
                    .isEqualTo("http://www.apache.org/licenses/LICENSE-2.0");
        }
    }

    @Nested
    @DisplayName("Version resolution, which has two sources and a blank-value edge")
    class VersionResolution {

        @Test
        @DisplayName("when no build-information bean is available the module coordinate version is published, so an "
                + "absent build resource does not fail start-up and does not leave the version empty")
        void anAbsentBuildInformationBeanFallsBackToTheModuleVersion() {
            assertThat(documentWith(null).getInfo().getVersion())
                    .isEqualTo(OpenApiConfig.MODULE_VERSION)
                    .isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("when a build-information bean publishes a version, that version is published, so the document "
                + "tracks the artefact actually running")
        void aPublishedVersionIsPreferred() {
            assertThat(documentWith(buildPropertiesWithVersion(PUBLISHED_VERSION)).getInfo().getVersion())
                    .isEqualTo(PUBLISHED_VERSION);
        }

        @Test
        @DisplayName("a build-information bean carrying no version falls back to the module coordinate rather than "
                + "publishing an absent version")
        void anAbsentPublishedVersionFallsBack() {
            assertThat(documentWith(buildPropertiesWithVersion(null)).getInfo().getVersion())
                    .isEqualTo(OpenApiConfig.MODULE_VERSION);
        }

        @Test
        @DisplayName("a build-information bean carrying a blank version falls back too, so the document never "
                + "describes the running artefact as having a whitespace version")
        void aBlankPublishedVersionFallsBack() {
            assertThat(documentWith(buildPropertiesWithVersion("   ")).getInfo().getVersion())
                    .isEqualTo(OpenApiConfig.MODULE_VERSION);
            assertThat(documentWith(buildPropertiesWithVersion("")).getInfo().getVersion())
                    .isEqualTo(OpenApiConfig.MODULE_VERSION);
        }

        @Test
        @DisplayName("the published version is never blank whichever branch is taken")
        void thePublishedVersionIsNeverBlank() {
            assertThat(documentWith(null).getInfo().getVersion()).isNotBlank();
            assertThat(documentWith(buildPropertiesWithVersion(PUBLISHED_VERSION))
                    .getInfo().getVersion()).isNotBlank();
            assertThat(documentWith(buildPropertiesWithVersion("  ")).getInfo().getVersion()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Bearer security scheme, without which the description would imply an open surface")
    class BearerSecurityScheme {

        @Test
        @DisplayName("exactly one security scheme is declared, registered under the bearer scheme name")
        void exactlyOneSchemeIsDeclaredUnderTheBearerName() {
            assertThat(documentWith(null).getComponents().getSecuritySchemes())
                    .hasSize(1)
                    .containsKey(OpenApiConfig.BEARER_SCHEME_NAME);
            assertThat(OpenApiConfig.BEARER_SCHEME_NAME).isEqualTo("bearerAuth");
        }

        @Test
        @DisplayName("the scheme is HTTP bearer carrying a JWT, which is what replaces the COMMAREA state the legacy "
                + "pseudo-conversation carried across turns")
        void theSchemeIsHttpBearerCarryingAJwt() {
            final SecurityScheme scheme = documentWith(null).getComponents()
                    .getSecuritySchemes().get(OpenApiConfig.BEARER_SCHEME_NAME);
            assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
            assertThat(scheme.getScheme()).isEqualTo(OpenApiConfig.HTTP_BEARER_SCHEME).isEqualTo("bearer");
            assertThat(scheme.getBearerFormat()).isEqualTo(OpenApiConfig.BEARER_TOKEN_FORMAT).isEqualTo("JWT");
        }

        @Test
        @DisplayName("the scheme carries a non-blank description, so a consumer learns how to obtain a token rather "
                + "than only that one is required")
        void theSchemeCarriesANonBlankDescription() {
            assertThat(documentWith(null).getComponents().getSecuritySchemes()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME).getDescription()).isNotBlank();
        }

        @Test
        @DisplayName("the scheme is also applied as a document-level requirement, so the description does not merely "
                + "define a scheme it never demands")
        void theSchemeIsAppliedAsADocumentLevelRequirement() {
            assertThat(documentWith(null).getSecurity())
                    .hasSize(1)
                    .allSatisfy(requirement ->
                            assertThat(requirement).containsKey(OpenApiConfig.BEARER_SCHEME_NAME));
        }

        @Test
        @DisplayName("the scheme name used in the requirement is the same name the scheme is registered under, so the "
                + "requirement resolves rather than dangling")
        void theRequirementResolvesToTheRegisteredScheme() {
            final OpenAPI document = documentWith(null);
            final String requiredName = document.getSecurity().get(0).keySet().iterator().next();
            assertThat(document.getComponents().getSecuritySchemes()).containsKey(requiredName);
        }
    }

    @Nested
    @DisplayName("Container contribution, proven against a real context")
    class ContainerContribution {

        private final ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(OpenApiConfig.class);

        @Test
        @DisplayName("the description bean is contributed to a context that publishes no build-information bean, "
                + "which is the shape every unit-scoped context has")
        void theBeanIsContributedWithoutBuildInformation() {
            runner.run(context -> assertThat(context)
                    .hasSingleBean(OpenAPI.class)
                    .hasSingleBean(OpenApiConfig.class));
        }

        @Test
        @DisplayName("the description bean is contributed when a build-information bean is present, and publishes "
                + "that bean's version")
        void theBeanIsContributedWithBuildInformation() {
            runner.withBean(BuildProperties.class, () -> buildPropertiesWithVersion(PUBLISHED_VERSION))
                    .run(context -> {
                        assertThat(context).hasSingleBean(OpenAPI.class);
                        assertThat(context.getBean(OpenAPI.class).getInfo().getVersion())
                                .isEqualTo(PUBLISHED_VERSION);
                    });
        }

        @Test
        @DisplayName("the context starts without the springdoc HTTP surface being enabled, which is what makes the "
                + "contract assertable under the closed shared baseline")
        void theContextStartsWithTheHttpSurfaceClosed() {
            runner.withPropertyValues("springdoc.api-docs.enabled=false",
                            "springdoc.swagger-ui.enabled=false")
                    .run(context -> assertThat(context).hasSingleBean(OpenAPI.class)
                            .hasNotFailed());
        }
    }
}
