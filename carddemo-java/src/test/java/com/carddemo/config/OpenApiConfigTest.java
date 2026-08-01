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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link OpenApiConfig}, the bean that publishes this module's interface contract.
 *
 * <p><strong>Why this matters to the gates.</strong> The published contract is what discharges the
 * interface-verification obligation: it is the machine-readable description a client is written against.
 * Two properties of it are therefore load-bearing and are asserted here rather than assumed. First, the
 * document must carry the two provenance identifiers — the legacy checkout commit and the upstream release
 * stamp — because the migration's traceability requirement is that the target cites the source it was
 * translated from. Second, the document must contain no credential of any kind, because a contract that
 * pre-filled an authorisation value would leak one just as surely as source code would.
 *
 * <p><strong>Where the expectations come from.</strong> The declared module version is checked against the
 * build file itself, read from disk as text, so the assertion has an independent second description rather
 * than restating the constant. The provenance identifiers are checked for shape as well as value: the
 * commit is asserted to be forty lowercase hexadecimal characters, which a mistyped or truncated value
 * would fail. The bearer scheme's name is cross-checked between the declared security requirement and the
 * component map, because a requirement naming a scheme the components do not define produces a document
 * that reads plausibly and cannot be honoured.
 *
 * <p><strong>What is deliberately not asserted.</strong> The operation paths are not checked, because this
 * bean contributes document-level metadata only and the paths are contributed by the controller scan at
 * runtime. The suite asserts the paths are absent here, which is the correct state for this bean, and
 * leaves path verification to the tests that exercise the endpoints.
 */
@DisplayName("OpenApiConfig — the published interface contract")
class OpenApiConfigTest {

    /** The legacy checkout the migration was translated from. */
    private static final String LEGACY_CHECKOUT = "7756d895ffeb65f7ea72aaa609e356d9899afcec";

    /** The upstream release stamp carried in every legacy member's trailer. */
    private static final String UPSTREAM_RELEASE_STAMP = "CardDemo_v1.0-15-g27d6c6f-68";

    /** The date that release stamp carries. */
    private static final String UPSTREAM_RELEASE_DATE = "2022-07-19";

    /** Length of a full-length Git commit identifier. */
    private static final int COMMIT_IDENTIFIER_LENGTH = 40;

    /** Number of legacy online transactions the contract describes. */
    private static final int LEGACY_TRANSACTION_COUNT = 17;

    /** The eight-character password literal every legacy seed record carries. */
    private static final String LEGACY_PASSWORD_LITERAL = "PASSWORD";

    /** The canonical Apache 2.0 licence location, matching the repository's own licence grant. */
    private static final String APACHE_LICENCE_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /**
     * Reads this module's own declared version out of the build file, giving the version assertion an
     * independent second description instead of restating the constant under test.
     *
     * @return the version the build file publishes for this module
     */
    private static String versionDeclaredByTheBuildFile() {
        final Path buildFile = locateBuildFile();
        final List<String> lines;
        try {
            lines = Files.readAllLines(buildFile, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("unable to read " + buildFile.toAbsolutePath(), cause);
        }

        boolean seenModuleCoordinate = false;
        for (final String line : lines) {
            if (line.contains("<artifactId>carddemo-java</artifactId>")) {
                seenModuleCoordinate = true;
            } else if (seenModuleCoordinate && line.contains("<version>")) {
                final int from = line.indexOf("<version>") + "<version>".length();
                final int to = line.indexOf("</version>", from);
                return line.substring(from, to).trim();
            }
        }
        throw new IllegalStateException("no module version found in " + buildFile.toAbsolutePath());
    }

    /**
     * Locates this module's build file relative to the directory the test was launched from, so the
     * assertion works whether the suite runs from the module directory or from a parent of it.
     *
     * @return the path of the build file
     */
    private static Path locateBuildFile() {
        Path candidate = Path.of("pom.xml").toAbsolutePath();
        Path directory = candidate.getParent();
        for (int depth = 0; depth < 3 && directory != null; depth++) {
            candidate = directory.resolve("pom.xml");
            if (Files.isRegularFile(candidate)
                    && directory.getFileName() != null
                    && "carddemo-java".equals(directory.getFileName().toString())) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("no carddemo-java build file found from "
                + Path.of("").toAbsolutePath());
    }

    /**
     * Builds the contract as the container would, with no published build information available.
     *
     * @return the published contract
     */
    private static OpenAPI contractWithoutBuildInformation() {
        return new OpenApiConfig(new AbsentBuildProperties()).cardDemoOpenApi();
    }

    /**
     * Builds the contract as the container would, with the supplied version published by the build.
     *
     * @param publishedVersion the version the build publishes, possibly {@code null}
     * @return the published contract
     */
    private static OpenAPI contractWithPublishedVersion(final String publishedVersion) {
        final Properties entries = new Properties();
        if (publishedVersion != null) {
            entries.setProperty("version", publishedVersion);
        }
        entries.setProperty("group", "com.carddemo");
        entries.setProperty("artifact", "carddemo-java");
        return new OpenApiConfig(new PresentBuildProperties(new BuildProperties(entries)))
                .cardDemoOpenApi();
    }

    /**
     * Returns every free-text field of the contract, so a single assertion can sweep all of them.
     *
     * @param contract the published contract
     * @return the contract's free text
     */
    private static List<String> freeTextOf(final OpenAPI contract) {
        return List.of(
                contract.getInfo().getTitle(),
                contract.getInfo().getDescription(),
                contract.getInfo().getVersion(),
                contract.getInfo().getLicense().getName(),
                contract.getInfo().getLicense().getUrl(),
                contract.getComponents()
                        .getSecuritySchemes()
                        .get(OpenApiConfig.BEARER_SCHEME_NAME)
                        .getDescription());
    }

    // =================================================================================================
    // DOCUMENT IDENTITY
    // =================================================================================================

    /**
     * Verifies the document's title, licence and version.
     */
    @Nested
    @DisplayName("document identity")
    class DocumentIdentity {

        @Test
        @DisplayName("the document is titled for this application")
        void theDocumentIsTitledForThisApplication() {
            assertThat(contractWithoutBuildInformation().getInfo().getTitle())
                    .isEqualTo(OpenApiConfig.API_TITLE)
                    .isEqualTo("CardDemo REST API");
        }

        @Test
        @DisplayName("the document carries the same licence grant as the repository it ships in")
        void theDocumentCarriesTheRepositoryLicence() {
            assertThat(OpenApiConfig.LICENSE_URL).isEqualTo(APACHE_LICENCE_URL);
            assertThat(contractWithoutBuildInformation().getInfo().getLicense().getName())
                    .isEqualTo("Apache License 2.0");
            assertThat(contractWithoutBuildInformation().getInfo().getLicense().getUrl())
                    .isEqualTo(APACHE_LICENCE_URL);
        }

        @Test
        @DisplayName("the fallback version is the version the build file itself declares for this module")
        void theFallbackVersionMatchesTheBuildFile() {
            assertThat(OpenApiConfig.MODULE_VERSION).isEqualTo(versionDeclaredByTheBuildFile());
        }

        @Test
        @DisplayName("the document contributes metadata only, leaving the operation paths to the "
                + "controller scan")
        void theDocumentContributesMetadataOnly() {
            assertThat(contractWithoutBuildInformation().getPaths()).isNull();
            assertThat(contractWithoutBuildInformation().getComponents().getSchemas()).isNull();
        }
    }

    // =================================================================================================
    // VERSION RESOLUTION
    // =================================================================================================

    /**
     * Verifies how the contract version is chosen.
     */
    @Nested
    @DisplayName("version resolution")
    class VersionResolution {

        @Test
        @DisplayName("an absent build information bean falls back to the module version")
        void anAbsentBuildInformationBeanFallsBackToTheModuleVersion() {
            assertThat(contractWithoutBuildInformation().getInfo().getVersion())
                    .isEqualTo(OpenApiConfig.MODULE_VERSION);
        }

        @Test
        @DisplayName("a published version is preferred over the module version")
        void aPublishedVersionIsPreferred() {
            assertThat(contractWithPublishedVersion("2.7.3").getInfo().getVersion())
                    .isEqualTo("2.7.3")
                    .isNotEqualTo(OpenApiConfig.MODULE_VERSION);
        }

        @Test
        @DisplayName("a build that publishes no version at all falls back to the module version")
        void aBuildPublishingNoVersionFallsBack() {
            assertThat(contractWithPublishedVersion(null).getInfo().getVersion())
                    .isEqualTo(OpenApiConfig.MODULE_VERSION);
        }

        @Test
        @DisplayName("a blank or whitespace-only published version falls back rather than publishing "
                + "an empty version")
        void aBlankPublishedVersionFallsBack() {
            for (final String blank : List.of("", " ", "   ", "\t", "\n")) {
                assertThat(contractWithPublishedVersion(blank).getInfo().getVersion())
                        .as("published version %s", blank.strip().isEmpty() ? "<blank>" : blank)
                        .isEqualTo(OpenApiConfig.MODULE_VERSION);
            }
        }

        @Test
        @DisplayName("the published version is used verbatim, including a snapshot qualifier")
        void thePublishedVersionIsUsedVerbatim() {
            assertThat(contractWithPublishedVersion("1.0.1-SNAPSHOT").getInfo().getVersion())
                    .isEqualTo("1.0.1-SNAPSHOT");
        }

        @Test
        @DisplayName("the version is resolved once when the configuration is built, so every document it "
                + "produces reports the same version")
        void theVersionIsResolvedOnceAndReusedConsistently() {
            final Properties entries = new Properties();
            entries.setProperty("version", "3.1.4");
            final OpenApiConfig configuration =
                    new OpenApiConfig(new PresentBuildProperties(new BuildProperties(entries)));

            assertThat(configuration.cardDemoOpenApi().getInfo().getVersion()).isEqualTo("3.1.4");
            assertThat(configuration.cardDemoOpenApi().getInfo().getVersion()).isEqualTo("3.1.4");
        }
    }

    // =================================================================================================
    // PROVENANCE
    // =================================================================================================

    /**
     * Verifies that the document cites the estate it was translated from.
     */
    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("the description cites the legacy checkout commit")
        void theDescriptionCitesTheLegacyCheckout() {
            assertThat(contractWithoutBuildInformation().getInfo().getDescription())
                    .contains(LEGACY_CHECKOUT);
        }

        @Test
        @DisplayName("the cited commit is a full-length lowercase hexadecimal identifier, so a truncated "
                + "or mistyped citation is caught")
        void theCitedCommitIsAFullLengthIdentifier() {
            assertThat(LEGACY_CHECKOUT)
                    .hasSize(COMMIT_IDENTIFIER_LENGTH)
                    .matches("[0-9a-f]{" + COMMIT_IDENTIFIER_LENGTH + "}");
        }

        @Test
        @DisplayName("the description cites the upstream release stamp and its date")
        void theDescriptionCitesTheUpstreamReleaseStamp() {
            assertThat(contractWithoutBuildInformation().getInfo().getDescription())
                    .contains(UPSTREAM_RELEASE_STAMP)
                    .contains(UPSTREAM_RELEASE_DATE);
        }

        @Test
        @DisplayName("the description states how many legacy transactions it describes, matching the "
                + "seventeen online programs")
        void theDescriptionStatesTheTransactionCount() {
            assertThat(contractWithoutBuildInformation().getInfo().getDescription())
                    .contains(String.valueOf(LEGACY_TRANSACTION_COUNT));
        }

        @Test
        @DisplayName("the description states that decimal values travel in plain form, which is the "
                + "property a client parsing an amount depends on")
        void theDescriptionStatesDecimalValuesTravelInPlainForm() {
            assertThat(contractWithoutBuildInformation().getInfo().getDescription())
                    .contains("plain decimal")
                    .contains("scientific notation");
        }
    }

    // =================================================================================================
    // THE SECURITY SCHEME
    // =================================================================================================

    /**
     * Verifies the declared authentication scheme.
     */
    @Nested
    @DisplayName("the security scheme")
    class TheSecurityScheme {

        @Test
        @DisplayName("exactly one scheme is declared, under the published name")
        void exactlyOneSchemeIsDeclared() {
            assertThat(contractWithoutBuildInformation().getComponents().getSecuritySchemes())
                    .hasSize(1)
                    .containsKey(OpenApiConfig.BEARER_SCHEME_NAME);
        }

        @Test
        @DisplayName("the scheme is HTTP bearer carrying a token")
        void theSchemeIsHttpBearer() {
            final SecurityScheme scheme = contractWithoutBuildInformation()
                    .getComponents()
                    .getSecuritySchemes()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME);

            assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
            assertThat(scheme.getScheme()).isEqualTo(OpenApiConfig.HTTP_BEARER_SCHEME);
            assertThat(scheme.getBearerFormat()).isEqualTo(OpenApiConfig.BEARER_TOKEN_FORMAT);
        }

        @Test
        @DisplayName("the scheme name is lowercase as the transport requires, and the token format is the "
                + "uppercase abbreviation")
        void theSchemeNameIsLowercaseAndTheFormatUppercase() {
            assertThat(OpenApiConfig.HTTP_BEARER_SCHEME)
                    .isEqualTo("bearer")
                    .isEqualTo(OpenApiConfig.HTTP_BEARER_SCHEME.toLowerCase(Locale.ROOT));
            assertThat(OpenApiConfig.BEARER_TOKEN_FORMAT).isEqualTo("JWT");
        }

        @Test
        @DisplayName("exactly one requirement is declared and it names the scheme the components define, "
                + "so the document cannot reference a scheme it never declared")
        void theRequirementNamesADeclaredScheme() {
            final OpenAPI contract = contractWithoutBuildInformation();

            assertThat(contract.getSecurity()).hasSize(1);
            assertThat(contract.getSecurity().getFirst())
                    .containsOnlyKeys(OpenApiConfig.BEARER_SCHEME_NAME);
            assertThat(contract.getComponents().getSecuritySchemes())
                    .containsKey(contract.getSecurity().getFirst().keySet().iterator().next());
        }

        @Test
        @DisplayName("the requirement lists no scope, because HTTP bearer authentication has none")
        void theRequirementListsNoScope() {
            assertThat(contractWithoutBuildInformation()
                    .getSecurity()
                    .getFirst()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME))
                    .isEmpty();
        }
    }

    // =================================================================================================
    // CREDENTIAL CONTAINMENT
    // =================================================================================================

    /**
     * Verifies that publishing the contract discloses nothing.
     */
    @Nested
    @DisplayName("credential containment")
    class CredentialContainment {

        @Test
        @DisplayName("no field of the document carries the legacy password literal")
        void noFieldCarriesTheLegacyPasswordLiteral() {
            for (final String text : freeTextOf(contractWithoutBuildInformation())) {
                assertThat(text).doesNotContain(LEGACY_PASSWORD_LITERAL);
            }
        }

        @Test
        @DisplayName("no field of the document carries a seeded identifier from the credential table")
        void noFieldCarriesASeededIdentifier() {
            for (final String text : freeTextOf(contractWithoutBuildInformation())) {
                assertThat(text)
                        .doesNotContain("ADMIN001")
                        .doesNotContain("USER0001");
            }
        }

        @Test
        @DisplayName("the scheme pre-fills no authorisation value and carries no example")
        void theSchemePreFillsNoAuthorisationValue() {
            final SecurityScheme scheme = contractWithoutBuildInformation()
                    .getComponents()
                    .getSecuritySchemes()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME);

            assertThat(scheme.getName()).isNull();
            assertThat(scheme.getIn()).isNull();
            assertThat(scheme.getFlows()).isNull();
            assertThat(scheme.getOpenIdConnectUrl()).isNull();
            assertThat(scheme.getExtensions()).isNull();
            assertThat(scheme.get$ref()).isNull();
        }

        @Test
        @DisplayName("the scheme description states that access is decided by the filter chain rather "
                + "than by this document")
        void theSchemeDescriptionDefersAccessToTheFilterChain() {
            assertThat(contractWithoutBuildInformation()
                    .getComponents()
                    .getSecuritySchemes()
                    .get(OpenApiConfig.BEARER_SCHEME_NAME)
                    .getDescription())
                    .contains("security filter chain")
                    .contains("nothing in")
                    .contains("grants access");
        }

        @Test
        @DisplayName("no field of the document names a host, port or connection string")
        void noFieldNamesAHostOrConnectionString() {
            for (final String text : freeTextOf(contractWithoutBuildInformation())) {
                assertThat(text)
                        .doesNotContain("jdbc:")
                        .doesNotContain("localhost")
                        .doesNotContain("amazonaws.com");
            }
            assertThat(contractWithoutBuildInformation().getServers()).isNull();
        }

        @Test
        @DisplayName("the document reproduces no legacy source text, only identifiers and counts")
        void theDocumentReproducesNoLegacySourceText() {
            final String description = contractWithoutBuildInformation().getInfo().getDescription();

            assertThat(description)
                    .contains("No legacy source text is reproduced")
                    .doesNotContain("PROCEDURE DIVISION")
                    .doesNotContain("EXEC CICS")
                    .doesNotContain("PIC X(");
        }
    }

    // =================================================================================================
    // BEAN BEHAVIOUR
    // =================================================================================================

    /**
     * Verifies that the bean method behaves as a factory rather than as shared state.
     */
    @Nested
    @DisplayName("bean behaviour")
    class BeanBehaviour {

        @Test
        @DisplayName("each invocation yields a distinct document, so a caller cannot mutate the one the "
                + "container published")
        void eachInvocationYieldsADistinctDocument() {
            final OpenApiConfig configuration = new OpenApiConfig(new AbsentBuildProperties());

            assertThat(configuration.cardDemoOpenApi())
                    .isNotSameAs(configuration.cardDemoOpenApi());
        }

        @Test
        @DisplayName("two documents built from the same configuration agree on every published field")
        void twoDocumentsAgreeOnEveryPublishedField() {
            final OpenApiConfig configuration = new OpenApiConfig(new AbsentBuildProperties());

            assertThat(freeTextOf(configuration.cardDemoOpenApi()))
                    .isEqualTo(freeTextOf(configuration.cardDemoOpenApi()));
        }

        @Test
        @DisplayName("the build information is consulted once, when the configuration is constructed, "
                + "rather than on every document")
        void theBuildInformationIsConsultedOnce() {
            final CountingBuildProperties provider = new CountingBuildProperties();
            final OpenApiConfig configuration = new OpenApiConfig(provider);

            final OpenAPI ignoredFirst = configuration.cardDemoOpenApi();
            final OpenAPI ignoredSecond = configuration.cardDemoOpenApi();

            assertThat(ignoredFirst.getInfo().getVersion())
                    .isEqualTo(ignoredSecond.getInfo().getVersion());
            assertThat(provider.lookups()).isOne();
        }
    }

    // =================================================================================================
    // TEST DOUBLES
    // =================================================================================================

    /**
     * A provider that reports no build information, as a plain {@code java -jar} run does.
     */
    private static final class AbsentBuildProperties implements ObjectProvider<BuildProperties> {

        @Override
        public BuildProperties getObject() {
            throw new IllegalStateException("no BuildProperties bean is available");
        }

        @Override
        public BuildProperties getIfAvailable() {
            return null;
        }
    }

    /**
     * A provider that reports the supplied build information.
     */
    private static final class PresentBuildProperties implements ObjectProvider<BuildProperties> {

        /** The build information to report. */
        private final BuildProperties buildProperties;

        /**
         * Creates a provider reporting the supplied build information.
         *
         * @param buildProperties the build information to report
         */
        PresentBuildProperties(final BuildProperties buildProperties) {
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
     * A provider that records how many times it was consulted.
     */
    private static final class CountingBuildProperties implements ObjectProvider<BuildProperties> {

        /** Number of times the provider was consulted. */
        private int lookups;

        @Override
        public BuildProperties getObject() {
            return getIfAvailable();
        }

        @Override
        public BuildProperties getIfAvailable() {
            this.lookups++;
            final Properties entries = new Properties();
            entries.setProperty("version", "9.9.9");
            return new BuildProperties(entries);
        }

        /**
         * Returns how many times the provider was consulted.
         *
         * @return the lookup count
         */
        int lookups() {
            return this.lookups;
        }
    }
}
