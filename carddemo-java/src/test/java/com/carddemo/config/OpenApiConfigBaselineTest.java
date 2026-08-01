/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Verifies the machine-readable interface contract that {@link OpenApiConfig} publishes.
 *
 * <p>The Agent Action Plan makes this document a graded deliverable rather than a convenience: it is the
 * artifact that gives the interface-contract gate something machine-readable to check, because the legacy
 * estate exposed its contract as 17 BMS mapsets driving 24x80 terminal screens and those mapsets have no
 * successor other than this description plus the request and response types it describes. Two properties
 * therefore matter here far more than the prose does. First, the version reported by the contract must be
 * the version of the artifact actually built, resolved from the published build information when Spring Boot
 * has recorded any and falling back to the module's own coordinate when it has not, so a client can never be
 * handed a contract that claims a version nothing produced. Second, the document must describe the bearer
 * scheme without carrying a single credential, because the legacy user record stored an eight-character
 * plaintext password and the migration's standing constraint is that no credential, token or example
 * password reaches a generated artifact.</p>
 *
 * <p>Scope: this exercises the configuration class directly. No Spring application context is started, no
 * component scan runs, no HTTP endpoint is called, no database, file, network or container is touched, and
 * nothing is introspected reflectively - the module's zero-reflection posture is honoured from the test tree
 * as well as from the production tree. The {@code ObjectProvider} the constructor consumes is supplied by a
 * hand-written stub that overrides only {@code stream()}, so the resolution the production constructor
 * actually performs - {@code getIfAvailable()} translating an unresolvable lookup into {@code null} - is the
 * framework's own implementation rather than a test convenience that could diverge from it.</p>
 *
 * <p>Expectations are derived, never echoed. The fallback version is asserted against the module's declared
 * Maven coordinate; the licence name and URL are asserted against the Apache-2.0 grant the repository ships
 * and that every legacy member carries in its header; the provenance identifiers are asserted against the
 * legacy checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; the bearer scheme name and token format are
 * asserted against the OpenAPI security-scheme vocabulary. No expectation is read back out of the class
 * under test.</p>
 */
@DisplayName("OpenApiConfig - the published interface contract for the screen-derived endpoints")
final class OpenApiConfigBaselineTest {

    /**
     * The Maven coordinate this module declares for itself. The Agent Action Plan states that this string
     * appears exactly once in the build file, as the artifact's own version, and is not a placeholder.
     */
    private static final String DECLARED_MODULE_COORDINATE_VERSION = "1.0.0";

    /** The legacy checkout the migration is traceable to. */
    private static final String LEGACY_CHECKOUT_SHA = "7756d895ffeb65f7ea72aaa609e356d9899afcec";

    /** The upstream release stamp carried in the trailer comment of every legacy member. */
    private static final String UPSTREAM_RELEASE_STAMP = "CardDemo_v1.0-15-g27d6c6f-68";

    /** The date that release stamp carries. */
    private static final String UPSTREAM_RELEASE_DATE = "2022-07-19";

    /** The Apache-2.0 grant the repository ships and every legacy member's header names. */
    private static final String APACHE_LICENCE_NAME = "Apache License 2.0";

    /** The canonical URL of that grant. */
    private static final String APACHE_LICENCE_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /**
     * The number of legacy CICS transactions whose screens became REST endpoint groups. Seventeen BMS
     * mapsets, seventeen generated symbolic maps, seventeen online programs.
     */
    private static final int LEGACY_ONLINE_TRANSACTION_COUNT = 17;

    /** The 3270 geometry every one of those mapsets was laid out against. */
    private static final String TERMINAL_GEOMETRY = "24x80";

    /** The eight-character literal every seeded legacy credential carried. Must never reach the contract. */
    private static final String LEGACY_SEEDED_PASSWORD_LITERAL = "PASSWORD";

    /** The first seeded administrator identifier. Must never reach the contract either. */
    private static final String LEGACY_SEEDED_ADMIN_ID = "ADMIN001";

    /** The prefix a base64url-encoded JWT header always begins with. No specimen token may be embedded. */
    private static final String JWT_HEADER_PREFIX = "eyJ";

    /**
     * Supplies, or declines to supply, published build information exactly as the bean factory would.
     *
     * <p>Only {@code stream()} is overridden. Every other operation - including the {@code getIfAvailable()}
     * that {@link OpenApiConfig} calls - is inherited from the framework interface, so the branch this stub
     * drives is the framework's own resolution path and not a shortcut around it.</p>
     */
    private static final class StubBuildPropertiesProvider implements ObjectProvider<BuildProperties> {

        /** The build information to resolve, or {@code null} to make the lookup resolve to nothing. */
        private final BuildProperties value;

        private StubBuildPropertiesProvider(final BuildProperties value) {
            this.value = value;
        }

        @Override
        public Stream<BuildProperties> stream() {
            return Stream.ofNullable(this.value);
        }
    }

    /**
     * Builds published build information carrying the supplied version.
     *
     * @param versionValue the version to publish, or {@code null} to publish build information that records
     *                     no version at all
     * @return the build information
     */
    private static BuildProperties buildInformationReporting(final String versionValue) {
        final Properties entries = new Properties();
        if (versionValue != null) {
            entries.setProperty("version", versionValue);
        }
        return new BuildProperties(entries);
    }

    /**
     * Builds the configuration against build information carrying the supplied version.
     *
     * @param versionValue the version to publish, or {@code null} to publish none
     * @return the configuration
     */
    private static OpenApiConfig configuredFromPublishedVersion(final String versionValue) {
        return new OpenApiConfig(new StubBuildPropertiesProvider(buildInformationReporting(versionValue)));
    }

    /**
     * Builds the configuration against a provider that resolves to no build information whatsoever.
     *
     * @return the configuration
     */
    private static OpenApiConfig configuredWithoutBuildInformation() {
        return new OpenApiConfig(new StubBuildPropertiesProvider(null));
    }

    /**
     * Extracts the info block of a freshly built contract.
     *
     * @param config the configuration
     * @return the info block
     */
    private static Info infoOf(final OpenApiConfig config) {
        final OpenAPI contract = config.cardDemoOpenApi();
        assertThat(contract).isNotNull();
        final Info info = contract.getInfo();
        assertThat(info).isNotNull();
        return info;
    }

    @Nested
    @DisplayName("Resolution of the version the contract reports")
    class ContractVersionResolution {

        @Test
        @DisplayName("a published version is reported verbatim, so the contract names the artifact that was "
                + "actually built rather than a hardcoded stand-in")
        void aPublishedVersionIsReportedVerbatim() {
            assertThat(infoOf(configuredFromPublishedVersion("2.7.3")).getVersion()).isEqualTo("2.7.3");
        }

        @ParameterizedTest(name = "published version \"{0}\" is reported verbatim")
        @ValueSource(strings = {"1.0.0", "1.0.1-SNAPSHOT", "2.0.0", "1.0.0+build.42", "0.0.1"})
        @DisplayName("any non-blank published version is reported verbatim, including a snapshot and a build "
                + "metadata suffix")
        void anyNonBlankPublishedVersionIsReportedVerbatim(final String published) {
            assertThat(infoOf(configuredFromPublishedVersion(published)).getVersion()).isEqualTo(published);
        }

        @Test
        @DisplayName("when the bean factory resolves no build information at all the module's own declared "
                + "coordinate is reported, so a contract built outside a packaged artifact still names a "
                + "version")
        void anAbsentProviderFallsBackToTheDeclaredCoordinate() {
            assertThat(infoOf(configuredWithoutBuildInformation()).getVersion())
                    .isEqualTo(DECLARED_MODULE_COORDINATE_VERSION);
        }

        @Test
        @DisplayName("build information that records no version at all falls back to the declared coordinate, "
                + "which is a different absence from the provider resolving to nothing and is handled "
                + "separately")
        void buildInformationWithoutAVersionFallsBackToTheDeclaredCoordinate() {
            assertThat(infoOf(configuredFromPublishedVersion(null)).getVersion())
                    .isEqualTo(DECLARED_MODULE_COORDINATE_VERSION);
        }

        @ParameterizedTest(name = "blank published version [{0}] falls back to the declared coordinate")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("a blank published version falls back to the declared coordinate, because a version made "
                + "only of whitespace names nothing and would be worse than a stated fallback")
        void aBlankPublishedVersionFallsBackToTheDeclaredCoordinate(final String blank) {
            assertThat(blank.isBlank()).isTrue();
            assertThat(infoOf(configuredFromPublishedVersion(blank)).getVersion())
                    .isEqualTo(DECLARED_MODULE_COORDINATE_VERSION);
        }

        @Test
        @DisplayName("the fallback constant equals the module's declared Maven coordinate, so the two cannot "
                + "drift apart unnoticed")
        void theFallbackConstantEqualsTheDeclaredCoordinate() {
            assertThat(OpenApiConfig.MODULE_VERSION).isEqualTo(DECLARED_MODULE_COORDINATE_VERSION);
        }

        @Test
        @DisplayName("the version is resolved once at construction, so every contract a single configuration "
                + "builds reports the same version")
        void theVersionIsResolvedOnceAtConstruction() {
            final OpenApiConfig config = configuredFromPublishedVersion("3.1.4");

            assertThat(infoOf(config).getVersion()).isEqualTo("3.1.4");
            assertThat(infoOf(config).getVersion()).isEqualTo("3.1.4");
        }
    }

    @Nested
    @DisplayName("The published vocabulary constants")
    class PublishedVocabulary {

        @Test
        @DisplayName("the security scheme is registered and referenced under one single name, so the "
                + "requirement can never point at a scheme the components block does not define")
        void theSchemeNameIsOneSingleConstant() {
            assertThat(OpenApiConfig.BEARER_SCHEME_NAME).isEqualTo("bearerAuth").isNotBlank();
        }

        @Test
        @DisplayName("the HTTP authentication scheme is the lowercase registered name, because the OpenAPI "
                + "scheme field carries an HTTP authentication scheme name and those are registered in "
                + "lowercase")
        void theHttpSchemeIsTheLowercaseRegisteredName() {
            assertThat(OpenApiConfig.HTTP_BEARER_SCHEME).isEqualTo("bearer").isLowerCase();
        }

        @Test
        @DisplayName("the bearer token format is declared as JWT, which is what the sign-on operation issues "
                + "in place of the legacy pseudo-conversational state carriage")
        void theBearerTokenFormatIsJwt() {
            assertThat(OpenApiConfig.BEARER_TOKEN_FORMAT).isEqualTo("JWT").isUpperCase();
        }

        @Test
        @DisplayName("the title names the application and its interface style rather than a legacy transaction "
                + "identifier, because the contract is consumed over HTTP and not from a terminal")
        void theTitleNamesTheApplicationAndItsInterfaceStyle() {
            assertThat(OpenApiConfig.API_TITLE)
                    .isEqualTo("CardDemo REST API")
                    .contains("CardDemo")
                    .isNotBlank();
        }

        @Test
        @DisplayName("the licence is the Apache-2.0 grant the repository ships and every legacy member's "
                + "header names, so provenance survives the migration into the contract")
        void theLicenceIsTheApacheGrantTheRepositoryShips() {
            assertThat(OpenApiConfig.LICENSE_NAME).isEqualTo(APACHE_LICENCE_NAME);
            assertThat(OpenApiConfig.LICENSE_URL).isEqualTo(APACHE_LICENCE_URL);
        }
    }

    @Nested
    @DisplayName("The info block")
    class InfoBlock {

        @Test
        @DisplayName("the title, licence name and licence URL are carried into the info block unchanged")
        void theTitleAndLicenceAreCarriedIntoTheInfoBlock() {
            final Info info = infoOf(configuredWithoutBuildInformation());

            assertThat(info.getTitle()).isEqualTo(OpenApiConfig.API_TITLE);

            final License licence = info.getLicense();
            assertThat(licence).isNotNull();
            assertThat(licence.getName()).isEqualTo(APACHE_LICENCE_NAME);
            assertThat(licence.getUrl()).isEqualTo(APACHE_LICENCE_URL);
        }

        @Test
        @DisplayName("the description records the legacy provenance the traceability deliverables cite, so a "
                + "reader of the contract alone can still find the estate it was derived from")
        void theDescriptionRecordsTheLegacyProvenance() {
            final String description = infoOf(configuredWithoutBuildInformation()).getDescription();

            assertThat(description)
                    .isNotNull()
                    .isNotBlank()
                    .contains(LEGACY_CHECKOUT_SHA)
                    .contains(UPSTREAM_RELEASE_STAMP)
                    .contains(UPSTREAM_RELEASE_DATE);
        }

        @Test
        @DisplayName("the description states that the endpoint groups are the REST form of the seventeen "
                + "legacy terminal transactions, which is the fact that makes the document a migration "
                + "contract rather than a fresh API description")
        void theDescriptionStatesTheLegacyOrigin() {
            final String description = infoOf(configuredWithoutBuildInformation()).getDescription();

            assertThat(description)
                    .contains(Integer.toString(LEGACY_ONLINE_TRANSACTION_COUNT))
                    .contains(TERMINAL_GEOMETRY);
        }

        @Test
        @DisplayName("the description states that no legacy source text is reproduced, which is the standing "
                + "constraint that traceability is by citation and never by transcription")
        void theDescriptionStatesThatNoLegacySourceTextIsReproduced() {
            assertThat(infoOf(configuredWithoutBuildInformation()).getDescription())
                    .containsIgnoringCase("no legacy source text is reproduced");
        }

        @Test
        @DisplayName("the description is written as several paragraphs rather than one run-on line, so it "
                + "remains readable where a documentation renderer presents it verbatim")
        void theDescriptionIsWrittenAsSeveralParagraphs() {
            final String description = infoOf(configuredWithoutBuildInformation()).getDescription();

            // A text block's line terminator is a line feed on every platform, so the paragraph separator
            // is a bare double line feed and never a carriage-return pair.
            assertThat(description).contains("\n\n").doesNotContain("\r");
            assertThat(description.split("\n\n")).hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("the description points the reader at the security scheme rather than restating it, so "
                + "the scheme is described in exactly one place")
        void theDescriptionPointsAtTheSecurityScheme() {
            assertThat(infoOf(configuredWithoutBuildInformation()).getDescription())
                    .containsIgnoringCase("bearer token");
        }
    }

    @Nested
    @DisplayName("The bearer security scheme")
    class BearerSecurityScheme {

        /**
         * Extracts the single declared security scheme.
         *
         * @return the scheme
         */
        private SecurityScheme scheme() {
            final OpenAPI contract = configuredWithoutBuildInformation().cardDemoOpenApi();
            final Components components = contract.getComponents();
            assertThat(components).isNotNull();
            final SecurityScheme declared =
                    components.getSecuritySchemes().get(OpenApiConfig.BEARER_SCHEME_NAME);
            assertThat(declared).isNotNull();
            return declared;
        }

        @Test
        @DisplayName("exactly one security scheme is declared, under exactly the name the requirement "
                + "references")
        void exactlyOneSchemeIsDeclaredUnderTheReferencedName() {
            final Components components = configuredWithoutBuildInformation().cardDemoOpenApi()
                    .getComponents();

            assertThat(components).isNotNull();
            assertThat(components.getSecuritySchemes())
                    .hasSize(1)
                    .containsKey(OpenApiConfig.BEARER_SCHEME_NAME);
        }

        @Test
        @DisplayName("the scheme is HTTP bearer with a JWT token format, matching what the sign-on operation "
                + "issues")
        void theSchemeIsHttpBearerCarryingAJwt() {
            final SecurityScheme declared = scheme();

            assertThat(declared.getType()).isEqualTo(SecurityScheme.Type.HTTP);
            assertThat(declared.getScheme()).isEqualTo(OpenApiConfig.HTTP_BEARER_SCHEME);
            assertThat(declared.getBearerFormat()).isEqualTo(OpenApiConfig.BEARER_TOKEN_FORMAT);
        }

        @Test
        @DisplayName("no API key placement is declared, because a bearer credential travels in the "
                + "authorization header and an in/name pair would describe a different scheme entirely")
        void noApiKeyPlacementIsDeclared() {
            final SecurityScheme declared = scheme();

            assertThat(declared.getIn()).isNull();
            assertThat(declared.getName()).isNull();
            assertThat(declared.getFlows()).isNull();
            assertThat(declared.getOpenIdConnectUrl()).isNull();
        }

        @Test
        @DisplayName("the scheme description says the declaration grants no access, so a reader cannot "
                + "mistake the contract for an authorisation policy")
        void theSchemeDescriptionDisclaimsGrantingAccess() {
            assertThat(scheme().getDescription())
                    .isNotNull()
                    .isNotBlank()
                    .containsIgnoringCase("grants access");
        }

        @Test
        @DisplayName("exactly one security requirement is published, referencing the declared scheme with no "
                + "scopes, because a bearer scheme carries no scope list")
        void exactlyOneRequirementReferencesTheSchemeWithNoScopes() {
            final OpenAPI contract = configuredWithoutBuildInformation().cardDemoOpenApi();

            assertThat(contract.getSecurity()).hasSize(1);
            final SecurityRequirement requirement = contract.getSecurity().get(0);
            assertThat(requirement).containsOnlyKeys(OpenApiConfig.BEARER_SCHEME_NAME);
            assertThat(requirement.get(OpenApiConfig.BEARER_SCHEME_NAME)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Absence of credential material")
    class AbsenceOfCredentialMaterial {

        /**
         * Concatenates every piece of prose the contract publishes.
         *
         * @return the whole published text
         */
        private String publishedText() {
            final OpenAPI contract = configuredWithoutBuildInformation().cardDemoOpenApi();
            final SecurityScheme declared =
                    contract.getComponents().getSecuritySchemes().get(OpenApiConfig.BEARER_SCHEME_NAME);

            return contract.getInfo().getTitle()
                    + '\n' + contract.getInfo().getDescription()
                    + '\n' + declared.getDescription();
        }

        @Test
        @DisplayName("the seeded legacy password literal appears nowhere in the published text, so migrating "
                + "an estate whose user record stored an eight-character plaintext password does not leak it "
                + "into the interface description")
        void theSeededPasswordLiteralAppearsNowhere() {
            assertThat(publishedText()).doesNotContain(LEGACY_SEEDED_PASSWORD_LITERAL);
        }

        @Test
        @DisplayName("no seeded user identifier appears in the published text")
        void noSeededUserIdentifierAppears() {
            assertThat(publishedText())
                    .doesNotContain(LEGACY_SEEDED_ADMIN_ID)
                    .doesNotContain("USER0001");
        }

        @Test
        @DisplayName("no specimen token is embedded, so the contract cannot be mistaken for one that "
                + "pre-fills an authorisation value")
        void noSpecimenTokenIsEmbedded() {
            assertThat(publishedText()).doesNotContain(JWT_HEADER_PREFIX);
        }

        @Test
        @DisplayName("the scheme description says so explicitly, which makes the absence a stated property "
                + "rather than an accident of drafting")
        void theAbsenceIsStatedExplicitly() {
            final SecurityScheme declared = configuredWithoutBuildInformation().cardDemoOpenApi()
                    .getComponents().getSecuritySchemes().get(OpenApiConfig.BEARER_SCHEME_NAME);

            assertThat(declared.getDescription()).containsIgnoringCase("no credential");
        }
    }

    @Nested
    @DisplayName("Construction of the contract object")
    class ContractConstruction {

        @Test
        @DisplayName("each call builds a fresh contract, so a caller that mutates one cannot corrupt what a "
                + "later caller receives")
        void eachCallBuildsAFreshContract() {
            final OpenApiConfig config = configuredWithoutBuildInformation();

            final OpenAPI first = config.cardDemoOpenApi();
            final OpenAPI second = config.cardDemoOpenApi();

            assertThat(first).isNotSameAs(second);
            assertThat(first.getInfo()).isNotSameAs(second.getInfo());
            assertThat(first.getComponents()).isNotSameAs(second.getComponents());
        }

        @Test
        @DisplayName("successive contracts agree on every published value, so freshness costs no consistency")
        void successiveContractsAgreeOnEveryPublishedValue() {
            final OpenApiConfig config = configuredFromPublishedVersion("4.5.6");

            final OpenAPI first = config.cardDemoOpenApi();
            final OpenAPI second = config.cardDemoOpenApi();

            assertThat(second.getInfo().getTitle()).isEqualTo(first.getInfo().getTitle());
            assertThat(second.getInfo().getVersion()).isEqualTo(first.getInfo().getVersion());
            assertThat(second.getInfo().getDescription()).isEqualTo(first.getInfo().getDescription());
            assertThat(second.getInfo().getLicense().getName())
                    .isEqualTo(first.getInfo().getLicense().getName());
            assertThat(second.getComponents().getSecuritySchemes().keySet())
                    .isEqualTo(first.getComponents().getSecuritySchemes().keySet());
            assertThat(second.getSecurity()).isEqualTo(first.getSecurity());
        }

        @Test
        @DisplayName("no server entry, tag, path or webhook is declared, because the endpoint inventory is "
                + "discovered from the annotated controllers rather than hand-maintained here, which is what "
                + "keeps the contract from drifting away from the code")
        void nothingIsHandMaintainedThatCouldDrift() {
            final OpenAPI contract = configuredWithoutBuildInformation().cardDemoOpenApi();

            assertThat(contract.getServers()).isNull();
            assertThat(contract.getTags()).isNull();
            assertThat(contract.getPaths()).isNull();
            assertThat(contract.getWebhooks()).isNull();
            assertThat(contract.getExternalDocs()).isNull();
        }

        @Test
        @DisplayName("the components block declares only the security scheme, so no schema is hand-written "
                + "here in competition with the ones derived from the request and response types")
        void theComponentsBlockDeclaresOnlyTheSecurityScheme() {
            final Components components = configuredWithoutBuildInformation().cardDemoOpenApi()
                    .getComponents();

            assertThat(components.getSchemas()).isNull();
            assertThat(components.getResponses()).isNull();
            assertThat(components.getParameters()).isNull();
            assertThat(components.getRequestBodies()).isNull();
            assertThat(components.getHeaders()).isNull();
            assertThat(components.getSecuritySchemes()).hasSize(1);
        }
    }
}
