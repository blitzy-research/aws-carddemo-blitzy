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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the two configuration classes that publish the interface contract.
 *
 * <p>The contract description is a gate artifact, not a convenience: the screen layer is replaced by
 * REST endpoints, and the published document is what makes those endpoints a machine-readable
 * interface rather than a self-certified one. Three properties are therefore asserted rather than
 * assumed - that the document is produced at all, that it declares the bearer security scheme every
 * role-gated endpoint depends on, and that its version falls back to the module coordinate when the
 * build has not stamped one.
 *
 * <p>The version fallback is the subtle part, and it is worth being precise about when it applies.
 * The build now binds the framework's build-information goal, so {@code META-INF/build-info.properties}
 * is generated before the classes are compiled and is packaged into the artifact - which is what
 * lets the information endpoint publish a build identity rather than describing one it does not
 * have. Build information is nevertheless still optional at runtime, because the file can be absent
 * from a class tree assembled by something other than this build, and because a stamped file can
 * carry no version. The configuration must publish a usable version in every one of those cases and
 * must never publish the literal absence, so all of them are asserted here against a stub rather
 * than left to whichever case the surrounding build happens to produce.
 *
 * <p>A separate group asserts the generated file itself, because a fallback that works is no
 * evidence that the stamping works: the two are independent, and only the second makes the
 * information endpoint's build-identity claim true.
 */
@DisplayName("Contract publication: the OpenAPI document and the web configuration")
final class ContractPublicationConfigTest {

    /** The version stamped into the artifact coordinate, used when the build supplies none. */
    private static final String ORACLE_MODULE_VERSION = "1.0.0";

    /** Where the build-information goal writes what it generates. */
    private static final String BUILD_INFO_RESOURCE = "/META-INF/build-info.properties";

    /** The prefix the generated file uses and the build-information bean does not. */
    private static final String BUILD_INFO_PREFIX = "build.";

    /** The group coordinate the generated identity must carry. */
    private static final String ORACLE_BUILD_GROUP = "com.carddemo";

    /** The artifact coordinate the generated identity must carry. */
    private static final String ORACLE_BUILD_ARTIFACT = "carddemo-java";

    /** The scheme name every secured operation references. */
    private static final String ORACLE_BEARER_SCHEME_NAME = "bearerAuth";

    /** The transport scheme, lowercase as the specification requires. */
    private static final String ORACLE_HTTP_BEARER_SCHEME = "bearer";

    /** The token format the sign-on endpoint issues. */
    private static final String ORACLE_BEARER_TOKEN_FORMAT = "JWT";

    /** The published title. */
    private static final String ORACLE_API_TITLE = "CardDemo REST API";

    /** The licence under which every legacy artifact and every generated source is released. */
    private static final String ORACLE_LICENSE_NAME = "Apache License 2.0";

    /** The canonical licence location. */
    private static final String ORACLE_LICENSE_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /**
     * Supplies build information the way the container does, including the case where none exists.
     *
     * <p>Every method of the provider interface carries a default implementation, so only the single
     * lookup the configuration performs needs overriding. Hand-writing this keeps the test free of a
     * mocking framework, which matters because generic stubbing raises unchecked warnings and the
     * build promotes warnings to errors.
     */
    private static final class StubBuildPropertiesProvider
            implements ObjectProvider<BuildProperties> {

        /** The build information to publish, or {@code null} when the build stamped none. */
        private final BuildProperties value;

        /**
         * Creates a provider yielding the supplied build information.
         *
         * @param value the build information, or {@code null} to model an unstamped build
         */
        StubBuildPropertiesProvider(final BuildProperties value) {
            this.value = value;
        }

        @Override
        public BuildProperties getIfAvailable() {
            return this.value;
        }
    }

    /**
     * Builds stamped build information carrying the supplied version.
     *
     * @param version the version to stamp, or {@code null} to stamp none
     * @return build information as the packaging step would produce it
     */
    private static BuildProperties buildPropertiesWithVersion(final String version) {
        final Properties properties = new Properties();
        if (version != null) {
            properties.setProperty("version", version);
        }
        return new BuildProperties(properties);
    }

    /**
     * Builds the configuration against the supplied build information.
     *
     * @param buildProperties the build information, or {@code null} for an unstamped build
     * @return the published document
     */
    private static OpenAPI documentFor(final BuildProperties buildProperties) {
        return new OpenApiConfig(new StubBuildPropertiesProvider(buildProperties)).cardDemoOpenApi();
    }

    @Nested
    @DisplayName("OpenApiConfig: the published contract description")
    final class PublishedContract {

        @Test
        @DisplayName("the constants are the values every consumer is written against")
        void theConstantsAreTheAdvertisedValues() {
            assertThat(OpenApiConfig.BEARER_SCHEME_NAME).isEqualTo(ORACLE_BEARER_SCHEME_NAME);
            assertThat(OpenApiConfig.HTTP_BEARER_SCHEME).isEqualTo(ORACLE_HTTP_BEARER_SCHEME);
            assertThat(OpenApiConfig.BEARER_TOKEN_FORMAT).isEqualTo(ORACLE_BEARER_TOKEN_FORMAT);
            assertThat(OpenApiConfig.API_TITLE).isEqualTo(ORACLE_API_TITLE);
            assertThat(OpenApiConfig.LICENSE_NAME).isEqualTo(ORACLE_LICENSE_NAME);
            assertThat(OpenApiConfig.LICENSE_URL).isEqualTo(ORACLE_LICENSE_URL);
            assertThat(OpenApiConfig.MODULE_VERSION).isEqualTo(ORACLE_MODULE_VERSION);
        }

        @Test
        @DisplayName("a document is produced, carrying title, description and licence")
        void aDocumentIsProducedWithItsIdentity() {
            final OpenAPI document = documentFor(buildPropertiesWithVersion("2.3.4"));

            assertThat(document).isNotNull();
            assertThat(document.getInfo()).isNotNull();
            assertThat(document.getInfo().getTitle()).isEqualTo(ORACLE_API_TITLE);
            assertThat(document.getInfo().getDescription()).isNotBlank();
            assertThat(document.getInfo().getLicense()).isNotNull();
            assertThat(document.getInfo().getLicense().getName()).isEqualTo(ORACLE_LICENSE_NAME);
            assertThat(document.getInfo().getLicense().getUrl()).isEqualTo(ORACLE_LICENSE_URL);
        }

        @Test
        @DisplayName("a stamped build version is published in preference to the module coordinate")
        void aStampedVersionIsPublished() {
            final OpenAPI document = documentFor(buildPropertiesWithVersion("2.3.4"));

            assertThat(document.getInfo().getVersion()).isEqualTo("2.3.4");
        }

        @Test
        @DisplayName("an unstamped build falls back to the module coordinate, never to an absent value")
        void anUnstampedBuildFallsBackToTheModuleCoordinate() {
            // This build does stamp build information, so the absent case is modelled deliberately
            // rather than observed: a class tree assembled by something other than this build carries
            // no such file, and publishing nothing would leave consumers without a contract version.
            final OpenAPI document = documentFor(null);

            assertThat(document.getInfo().getVersion()).isEqualTo(ORACLE_MODULE_VERSION);
        }

        @Test
        @DisplayName("build information present but carrying no version also falls back")
        void buildInformationWithoutAVersionAlsoFallsBack() {
            final OpenAPI document = documentFor(buildPropertiesWithVersion(null));

            assertThat(document.getInfo().getVersion()).isEqualTo(ORACLE_MODULE_VERSION);
        }

        @Test
        @DisplayName("a blank stamped version is treated as no version at all")
        void aBlankStampedVersionIsTreatedAsAbsent() {
            // A build that stamps whitespace is misconfigured; publishing the whitespace would put a
            // meaningless version into the contract.
            final OpenAPI document = documentFor(buildPropertiesWithVersion("   "));

            assertThat(document.getInfo().getVersion()).isEqualTo(ORACLE_MODULE_VERSION);
        }

        @Test
        @DisplayName("the bearer security scheme is declared, typed and formatted")
        void theBearerSecuritySchemeIsDeclared() {
            final OpenAPI document = documentFor(null);

            assertThat(document.getComponents()).isNotNull();
            assertThat(document.getComponents().getSecuritySchemes())
                    .containsKey(ORACLE_BEARER_SCHEME_NAME);

            final SecurityScheme scheme =
                    document.getComponents().getSecuritySchemes().get(ORACLE_BEARER_SCHEME_NAME);
            assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
            assertThat(scheme.getScheme()).isEqualTo(ORACLE_HTTP_BEARER_SCHEME);
            assertThat(scheme.getBearerFormat()).isEqualTo(ORACLE_BEARER_TOKEN_FORMAT);
            assertThat(scheme.getDescription()).isNotBlank();
        }

        @Test
        @DisplayName("the scheme is applied document-wide, so every operation inherits it")
        void theSchemeIsAppliedDocumentWide() {
            // Applying the requirement at the document root is what makes the token mandatory by
            // default; an endpoint that must be reachable unauthenticated opts out explicitly.
            final OpenAPI document = documentFor(null);

            assertThat(document.getSecurity()).isNotNull().hasSize(1);
            assertThat(document.getSecurity().get(0)).containsKey(ORACLE_BEARER_SCHEME_NAME);
        }

        @Test
        @DisplayName("each call yields an independent document, so a mutating consumer cannot leak")
        void eachCallYieldsAnIndependentDocument() {
            final OpenApiConfig config = new OpenApiConfig(new StubBuildPropertiesProvider(null));

            final OpenAPI first = config.cardDemoOpenApi();
            final OpenAPI second = config.cardDemoOpenApi();

            assertThat(first).isNotSameAs(second);
            assertThat(first.getInfo().getVersion()).isEqualTo(second.getInfo().getVersion());
        }
    }

    @Nested
    @DisplayName("Generated build identity: what the information endpoint actually publishes")
    final class GeneratedBuildIdentity {

        /**
         * Reads the generated build information from the class path the way the framework's own
         * auto-configuration does.
         *
         * @return the loaded properties
         * @throws IOException if the resource exists but cannot be read
         */
        private Properties loadGeneratedBuildInformation() throws IOException {
            final Properties properties = new Properties();
            try (InputStream stream = getClass().getResourceAsStream(BUILD_INFO_RESOURCE)) {
                assertThat(stream)
                        .as("the build must bind the build-information goal, otherwise the information "
                                + "endpoint describes a build identity it does not have")
                        .isNotNull();
                properties.load(stream);
            }
            return properties;
        }

        @Test
        @DisplayName("the build stamps build information onto the class path, so the claim is true")
        void theBuildStampsBuildInformation() throws IOException {
            final Properties generated = loadGeneratedBuildInformation();

            assertThat(generated.getProperty("build.group")).isEqualTo(ORACLE_BUILD_GROUP);
            assertThat(generated.getProperty("build.artifact")).isEqualTo(ORACLE_BUILD_ARTIFACT);
            assertThat(generated.getProperty("build.version")).isEqualTo(ORACLE_MODULE_VERSION);
            assertThat(generated.getProperty("build.time"))
                    .as("a build identity without a build time cannot distinguish two builds of one "
                            + "version")
                    .isNotBlank();
        }

        @Test
        @DisplayName("the stamped identity carries no environment value, so no secret can ride along")
        void theStampedIdentityCarriesNoEnvironmentValue() throws IOException {
            // The information endpoint's environment contributor stays disabled in configuration so
            // that a property whose name begins with the exposed prefix can never be published. The
            // generated file must not reintroduce by another route what that setting excludes, so the
            // payload is asserted to be exactly the four coordinate and timing keys and nothing else.
            final Properties generated = loadGeneratedBuildInformation();

            assertThat(generated.stringPropertyNames())
                    .containsExactlyInAnyOrder(
                            "build.group", "build.artifact", "build.name", "build.version",
                            "build.time");
        }

        @Test
        @DisplayName("the stamped version is what the published contract reports")
        void theStampedVersionIsWhatTheContractReports() throws IOException {
            // The two are independent mechanisms - one generates a file, the other reads a bean bound
            // from it - so agreement between them is asserted rather than assumed. Without this the
            // contract version could drift from the artifact it describes and nothing would notice.
            final Properties generated = loadGeneratedBuildInformation();
            final BuildProperties stamped = new BuildProperties(rebasedForBuildProperties(generated));

            final OpenAPI document = documentFor(stamped);

            assertThat(document.getInfo().getVersion()).isEqualTo(generated.getProperty("build.version"));
        }

        /**
         * Strips the {@code build.} prefix, which is how the framework binds the generated file into
         * its build-information bean.
         *
         * @param generated the generated properties, prefixed as written to disk
         * @return the same values under the unprefixed names the bean expects
         */
        private Properties rebasedForBuildProperties(final Properties generated) {
            final Properties rebased = new Properties();
            for (final String name : generated.stringPropertyNames()) {
                rebased.setProperty(
                        name.startsWith(BUILD_INFO_PREFIX)
                                ? name.substring(BUILD_INFO_PREFIX.length())
                                : name,
                        generated.getProperty(name));
            }
            return rebased;
        }
    }

    @Nested
    @DisplayName("WebMvcConfig: the deliberately empty web configuration")
    final class WebConfiguration {

        @Test
        @DisplayName("it is a web configurer, so the container will consult it")
        void itIsAWebConfigurer() {
            assertThat(new WebMvcConfig()).isInstanceOf(WebMvcConfigurer.class);
        }

        @Test
        @DisplayName("it contributes no message converter, leaving content negotiation as configured")
        void itContributesNoMessageConverter() {
            // Every interface method is inherited as its own no-op default, so the observable effect of
            // consulting this configurer must be no effect at all. Asserting the behaviour rather than
            // the declared-method list keeps the check independent of bytecode instrumentation, which
            // adds synthetic members of its own during a coverage run.
            final WebMvcConfig config = new WebMvcConfig();
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();

            config.configureMessageConverters(converters);
            config.extendMessageConverters(converters);

            assertThat(converters)
                    .as("replacing or extending the converter list would change every response body")
                    .isEmpty();
        }

        @Test
        @DisplayName("it contributes no argument resolver and no formatter")
        void itContributesNoArgumentResolverAndNoFormatter() {
            final WebMvcConfig config = new WebMvcConfig();
            final List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
            final FormattingConversionService formatters = new FormattingConversionService();
            final boolean convertedBefore = formatters.canConvert(String.class, Integer.class);

            config.addArgumentResolvers(resolvers);
            config.addFormatters(formatters);

            assertThat(resolvers).isEmpty();
            assertThat(formatters.canConvert(String.class, Integer.class))
                    .as("registering a formatter would change how every request parameter is bound")
                    .isEqualTo(convertedBefore);
        }
    }
}
