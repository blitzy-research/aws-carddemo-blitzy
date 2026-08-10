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

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.carddemo.config.ProductionConfigurationValidator.RequiredSetting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Proves that {@link ProductionConfigurationValidator} refuses every unusable form of a required
 * production setting, and that the set it guards is the set the production profile actually declares.
 *
 * <h2>What this class establishes, and what its companion establishes</h2>
 *
 * <p>Two things had to be shown, and they need different instruments.
 *
 * <p><strong>Here:</strong> that each required variable is judged independently, in each of the three
 * ways any of them can be unusable - absent, empty and whitespace - and that the report names the
 * offending property and variable without blaming any other. This is done against a real environment
 * assembled from the real {@code application-prod.yml} and {@code application.yml}, so the keys, the
 * variable names and the fallback tails are the delivered ones rather than a copy of them. No container
 * and no application context are involved, which is why every independent case can be exercised.
 *
 * <p>One variable is judged on a fourth ground as well. The operator credential at
 * {@code carddemo.security.management.token} is refused for being TOO SHORT, because it is the module's
 * one guessable secret: it is presented as a bearer token on the management surface with no sign-on, no
 * lockout and no attempt counter behind it, so unguessability is its whole defence. That rule, its
 * boundary, and the fact that no other setting acquires it are asserted alongside the three general
 * ones.
 *
 * <p><strong>In {@code ProductionInfrastructureIsUntouchedTest}:</strong> that a real Spring context
 * activated on the production profile stops before any bean is created, and that no other profile is
 * affected. That question is about container lifecycle and can only be answered by starting a container.
 *
 * <h2>Why the environment here excludes the machine's own settings</h2>
 *
 * <p>Both system-backed property sources are removed from every environment this class builds. A build
 * agent that happens to export {@code AWS_REGION} - which agents hosted in that ecosystem routinely do -
 * would otherwise satisfy one of the twelve by accident and quietly turn a negative case positive. The
 * removal makes each case depend only on what the case itself supplies.
 *
 * <h2>Why the supplied values are shaped the way they are</h2>
 *
 * <p>Each variable is satisfied with text derived from its own name. The validator judges usability, not
 * shape - it does not parse a location, decode a key or inspect a queue suffix, because the components
 * that consume those values already do - so a value that merely exists and is not blank is exactly the
 * right fixture, and deriving it from the variable name keeps anything credential-shaped out of the
 * repository. That derivation also comfortably clears the operator credential's length floor, which is
 * why the general cases need no special value for it and the length cases supply their own.
 *
 * @see ProductionConfigurationValidator
 */
@DisplayName("A production start-up refuses every unusable required setting")
final class ProductionConfigurationValidatorTest {

    /** The production overlay, the document that declares the required settings. */
    private static final String PRODUCTION_DOCUMENT = "application-prod.yml";

    /** The shared baseline, loaded beneath the overlay exactly as a running application loads it. */
    private static final String SHARED_DOCUMENT = "application.yml";

    /**
     * An environment reference with no fallback tail: {@code ${NAME}}. A value of this shape is required
     * from the environment, because nothing else can supply it.
     */
    private static final Pattern BARE_REFERENCE = Pattern.compile("^\\$\\{([A-Za-z0-9_]+)}$");

    /**
     * An environment reference that carries a fallback: {@code ${NAME:something}}. A value of this shape
     * is <em>not</em> required from the environment, and guarding it would turn a documented default
     * into a deployment obligation.
     */
    private static final Pattern DEFAULTED_REFERENCE = Pattern.compile("^\\$\\{([A-Za-z0-9_]+):.*}$");

    /** Text a failure message must carry so a reader can find the recorded reasoning. */
    private static final String RECORDED_DECISION = "docs/decision-log.md DL-105";

    /**
     * The variable carrying the one required value that is also held to a minimum length.
     *
     * <p>Named here rather than inline because three assertions address it: the boundary is accepted, a
     * value one character below it is refused, and padding cannot be used to reach it.
     */
    private static final String OPERATOR_CREDENTIAL_VARIABLE = "CARDDEMO_MANAGEMENT_TOKEN";

    /**
     * The one data source location a production deployment may declare.
     *
     * <p>Every rule the transport check applies is satisfied here: the PostgreSQL sub-protocol, a named
     * non-loopback host, an explicit port and database, {@code sslmode=verify-full}, no embedded
     * credential and no SSL factory override. Held as a constant so that the accepting cases and the
     * rejecting cases below differ in exactly one component each, which is what makes each rejection
     * attributable to the component it names.
     */
    private static final String AUTHENTICATED_DATABASE_URL =
            "jdbc:postgresql://db.production.example:5432/carddemo?sslmode=verify-full";

    /**
     * Builds the text that satisfies one variable.
     *
     * <p>Derived from the variable's own name so that a failure message quoting a value is immediately
     * traceable, and so that nothing in this file resembles a credential.
     *
     * @param variable environment variable being satisfied
     * @return a non-blank value carrying no placeholder syntax
     */
    private static String suppliedValueFor(final String variable) {
        return "supplied-by-this-test-for-" + variable;
    }

    /**
     * Supplies every required variable with a usable value.
     *
     * @return a mutable map from variable name to value, in the order the validator checks them
     */
    private static Map<String, String> everyRequiredVariable() {
        final Map<String, String> variables = new LinkedHashMap<>();
        for (final RequiredSetting setting : ProductionConfigurationValidator.REQUIRED_SETTINGS) {
            variables.put(setting.environmentVariable(), suppliedValueFor(setting.environmentVariable()));
        }
        return variables;
    }

    /**
     * Assembles an environment that loads the delivered profile documents and nothing from the machine.
     *
     * <p>The overlay is added before the baseline so that a key declared in both resolves to the
     * production declaration, which is the precedence a running application applies.
     *
     * @param variables environment variables to make resolvable
     * @return an environment carrying the production overlay, the shared baseline and those variables
     */
    private static StandardEnvironment environmentWith(final Map<String, String> variables) {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (final PropertySource<?> source : documentSources(PRODUCTION_DOCUMENT)) {
            environment.getPropertySources().addLast(source);
        }
        for (final PropertySource<?> source : documentSources(SHARED_DOCUMENT)) {
            environment.getPropertySources().addLast(source);
        }
        environment.getPropertySources().addLast(new MapPropertySource(
                "variables-supplied-by-this-test", new LinkedHashMap<String, Object>(variables)));
        return environment;
    }

    /**
     * Loads one profile document from the class path.
     *
     * <p>Reads the single-copy form deliberately for the production overlay and the shared baseline,
     * because each of those exists exactly once. The test profile is the only document this module
     * ships twice, and the one assertion that reads it uses {@link #physicalCopiesOf} instead.
     *
     * @param document file name of the document
     * @return the property sources the document produces
     */
    private static List<PropertySource<?>> documentSources(final String document) {
        return sourcesOf(new ClassPathResource(document), document);
    }

    /**
     * Loads a named document from a specific physical location.
     *
     * @param resource location to read
     * @param name     name to record against the produced property sources
     * @return the property sources the document produces
     */
    private static List<PropertySource<?>> sourcesOf(final Resource resource, final String name) {
        try {
            return new YamlPropertySourceLoader().load(name, resource);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not read " + resource, ex);
        }
    }

    /**
     * Returns every physical copy of a document the test class path carries.
     *
     * <p>Resolved with {@code classpath*:} rather than {@code classpath:}. The single-copy form stops at
     * the first match, and for {@code application-test.yml} that is the suite-only overlay, which would
     * hide the packaged deliverable of the same name - the shadowing this checkpoint already had to
     * correct once.
     *
     * @param document file name of the document
     * @return every copy found, in class-path order
     */
    private static List<Resource> physicalCopiesOf(final String document) {
        try {
            final Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + document);
            final List<Resource> copies = Stream.of(found).filter(Resource::exists).toList();
            if (copies.isEmpty()) {
                throw new IllegalStateException("shipped configuration document is missing: " + document);
            }
            return copies;
        } catch (final IOException ex) {
            throw new UncheckedIOException("configuration document is unreadable: " + document, ex);
        }
    }

    /**
     * Reads a document's declarations without resolving anything.
     *
     * @param sources property sources produced by the document
     * @return every declared key mapped to its raw text, in declaration order
     */
    private static Map<String, String> rawDeclarationsIn(final List<PropertySource<?>> sources) {
        final Map<String, String> declarations = new LinkedHashMap<>();
        for (final PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (final String name : enumerable.getPropertyNames()) {
                    final Object raw = enumerable.getProperty(name);
                    declarations.put(name, raw == null ? "" : raw.toString());
                }
            }
        }
        return declarations;
    }

    /**
     * Selects the declarations whose raw text matches a reference shape.
     *
     * @param sources property sources produced by the document
     * @param shape   pattern whose first group is the variable name
     * @return every matching key mapped to the variable it references
     */
    private static Map<String, String> referencesIn(final List<PropertySource<?>> sources,
            final Pattern shape) {
        final Map<String, String> references = new LinkedHashMap<>();
        rawDeclarationsIn(sources).forEach((key, text) -> {
            final Matcher matcher = shape.matcher(text);
            if (matcher.matches()) {
                references.put(key, matcher.group(1));
            }
        });
        return references;
    }

    /**
     * Selects the declarations of one document whose raw text matches a reference shape.
     *
     * @param document file name of the document
     * @param shape    pattern whose first group is the variable name
     * @return every matching key mapped to the variable it references
     */
    private static Map<String, String> referencesIn(final String document, final Pattern shape) {
        return referencesIn(documentSources(document), shape);
    }

    /** @return the property keys the validator guards, in checking order */
    private static List<String> guardedKeys() {
        return ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                .map(RequiredSetting::propertyKey)
                .toList();
    }

    /**
     * Builds the exact line a report carries for one setting.
     *
     * <p>Used instead of a bare key comparison because three of the twelve keys are prefixes of each
     * other - the key store, its credential and its format - so "the report does not mention this key"
     * can only be asked safely against a whole line.
     *
     * @param setting setting to build the line prefix for
     * @return the report line's leading text, unique to that setting
     */
    private static String reportLineFor(final RequiredSetting setting) {
        return "  " + setting.propertyKey() + " <- " + setting.environmentVariable() + ":";
    }

    /** @return one argument pair per required setting: the property key and its variable */
    private static Stream<Arguments> requiredSettings() {
        return ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                .map(setting -> arguments(setting.propertyKey(), setting.environmentVariable()));
    }

    /**
     * Finds the recorded setting for a property key.
     *
     * @param propertyKey key to look up
     * @return the setting carrying that key
     */
    private static RequiredSetting settingFor(final String propertyKey) {
        return ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                .filter(setting -> setting.propertyKey().equals(propertyKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No required setting declares " + propertyKey));
    }

    @Nested
    @DisplayName("A fully supplied environment is accepted")
    class ACompleteEnvironmentIsAccepted {

        @Test
        @DisplayName("every required setting resolves, so the check passes silently")
        void everyRequiredSettingResolves() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateRequiredSettings(environmentWith(everyRequiredVariable())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the settings that carry a fallback resolve without being supplied")
        void theDefaultedSettingsAreNotRequired() {
            final StandardEnvironment environment = environmentWith(everyRequiredVariable());
            final Map<String, String> defaulted =
                    referencesIn(PRODUCTION_DOCUMENT, DEFAULTED_REFERENCE);

            assertThat(defaulted)
                    .as("the production profile must still contain settings that may default, "
                            + "otherwise the distinction this validator relies on is untested")
                    .isNotEmpty();

            defaulted.keySet().forEach(key -> {
                final String resolved = environment.resolvePlaceholders("${" + key + "}");
                assertThat(resolved)
                        .as("%s carries a fallback and no variable was supplied for it", key)
                        .isNotBlank()
                        .doesNotContain("${");
            });
        }

        @Test
        @DisplayName("a value supplied as a literal rather than through a variable is accepted too, and "
                + "the literal used is the authenticated production form")
        void aLiterallyDeclaredValueIsAccepted() {
            final Map<String, String> variables = everyRequiredVariable();
            variables.remove("CARDDEMO_DB_URL");
            final StandardEnvironment environment = environmentWith(variables);
            environment.getPropertySources().addFirst(new MapPropertySource("literal-override",
                    Map.<String, Object>of("spring.datasource.url", AUTHENTICATED_DATABASE_URL)));

            assertThatCode(() -> ProductionConfigurationValidator.validateRequiredSettings(environment))
                    .as("the check judges whether a value is usable, not how it came to be declared; a "
                            + "deployment that overrides the key outright is not missing anything")
                    .doesNotThrowAnyException();
            assertThatCode(() -> ProductionConfigurationValidator.validateDatabaseTransport(environment))
                    .as("THE FIXTURE ITSELF IS THE FINDING THIS ADDRESSES. This case used to supply "
                            + "jdbc:postgresql://named/db - a URL with no transport rule at all, which "
                            + "the driver reads as sslmode=prefer and therefore as a channel that may "
                            + "silently fall back to plaintext. A suite whose own example of a good "
                            + "production value was a downgradeable one had no way to notice the gap, so "
                            + "the literal is now the authenticated form and both checks are run on it")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a supplied operator credential at the floor is accepted, so the length rule "
                + "refuses what is too short rather than everything")
        void anOperatorCredentialAtTheFloorIsAccepted() {
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(OPERATOR_CREDENTIAL_VARIABLE,
                    "x".repeat(ProductionConfigurationValidator.MINIMUM_MANAGEMENT_TOKEN_LENGTH));

            assertThatCode(() -> ProductionConfigurationValidator
                    .validateRequiredSettings(environmentWith(variables)))
                    .as("exactly the minimum is enough; a rule that refused the boundary would be a "
                            + "different rule from the one documented")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null environment is rejected rather than silently passing")
        void aNullEnvironmentIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateRequiredSettings(null))
                    .withMessageContaining("environment");
        }
    }

    @Nested
    @DisplayName("Every required variable is judged independently")
    class EveryRequiredVariableIsJudgedIndependently {

        static Stream<Arguments> settings() {
            return requiredSettings();
        }

        @ParameterizedTest(name = "{0} is unusable when {1} is not set")
        @MethodSource("settings")
        @DisplayName("omitting one variable stops the start and names it")
        void omittingOneVariableStopsTheStart(final String propertyKey, final String variable) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.remove(variable);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environmentWith(variables)))
                    .withMessageContaining(reportLineFor(settingFor(propertyKey)))
                    .withMessageContaining("the variable is not set")
                    .withMessageContaining("${" + variable + "}");
        }

        @ParameterizedTest(name = "{0} is unusable when {1} is set to an empty string")
        @MethodSource("settings")
        @DisplayName("an empty variable stops the start and is described as empty")
        void anEmptyVariableStopsTheStart(final String propertyKey, final String variable) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(variable, "");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environmentWith(variables)))
                    .withMessageContaining(reportLineFor(settingFor(propertyKey)))
                    .withMessageContaining("set but empty");
        }

        @ParameterizedTest(name = "{0} is unusable when {1} is set to whitespace")
        @MethodSource("settings")
        @DisplayName("a whitespace-only variable stops the start and is described as whitespace")
        void aWhitespaceOnlyVariableStopsTheStart(final String propertyKey, final String variable) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(variable, "   ");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environmentWith(variables)))
                    .withMessageContaining(reportLineFor(settingFor(propertyKey)))
                    .withMessageContaining("whitespace only");
        }

        @Test
        @DisplayName("an operator credential one character below the floor stops the start, because a "
                + "guessable machine credential is a credential in name only")
        void aShortOperatorCredentialStopsTheStart() {
            // THE DEFECT THIS PINS. The sweep used to refuse only an absent, unresolved or blank value, so
            // a ONE-CHARACTER operator credential started production and then fell to a few hundred
            // guesses. Nothing else in the module catches it: the comparison in SecurityConfig's
            // management filter is constant-time, which defeats a timing side channel and does nothing
            // whatever about a value short enough to enumerate, and there is no sign-on, no lockout and no
            // attempt counter behind that surface by design. CWE-521, reachable in production with
            // nothing else misconfigured.
            final int floor = ProductionConfigurationValidator.MINIMUM_MANAGEMENT_TOKEN_LENGTH;
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(OPERATOR_CREDENTIAL_VARIABLE, "x".repeat(floor - 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environmentWith(variables)))
                    .withMessageContaining(reportLineFor(
                            settingFor(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY)))
                    .withMessageContaining("requires at least " + floor)
                    .withMessageContaining("openssl rand -hex 16");
        }

        @Test
        @DisplayName("a single character is refused, which is the value the earlier check accepted")
        void aSingleCharacterOperatorCredentialStopsTheStart() {
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(OPERATOR_CREDENTIAL_VARIABLE, "x");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environmentWith(variables)))
                    .withMessageContaining("is 1 characters");
        }

        @Test
        @DisplayName("padding cannot be used to reach the floor, because the surrounding whitespace is "
                + "not part of the credential the filter compares")
        void paddingDoesNotSatisfyTheFloor() {
            final int floor = ProductionConfigurationValidator.MINIMUM_MANAGEMENT_TOKEN_LENGTH;
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(OPERATOR_CREDENTIAL_VARIABLE, " ".repeat(floor) + "short" + " ".repeat(floor));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environmentWith(variables)))
                    .as("SecurityConfig strips the configured token before comparing it, so an attacker "
                            + "guessing this value does not have to guess the padding")
                    .withMessageContaining("is 5 characters");
        }

        @Test
        @DisplayName("and no other required setting acquires a length rule, so a region or a key alias "
                + "is still judged only on being supplied")
        void noOtherSettingIsHeldToALength() {
            assertThat(ProductionConfigurationValidator.MINIMUM_LENGTH_BY_KEY)
                    .as("a length floor is right for the one guessable machine credential and wrong for "
                            + "a value whose length is dictated by what produces it")
                    .containsOnlyKeys(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY);
            assertThat(ProductionConfigurationValidator.MINIMUM_LENGTH_BY_KEY
                    .get(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY))
                    .as("thirty-two characters is the width `openssl rand -hex 16` and "
                            + "`openssl rand -base64 24` each produce")
                    .isEqualTo(32);
        }

        @ParameterizedTest(name = "only {0} is blamed when only {1} is missing")
        @MethodSource("settings")
        @DisplayName("no other setting is blamed for one missing variable")
        void noOtherSettingIsBlamed(final String propertyKey, final String variable) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.remove(variable);

            final String message = messageFrom(variables);

            assertThat(message).contains(reportLineFor(settingFor(propertyKey)));
            assertThat(message).contains("1 of "
                    + ProductionConfigurationValidator.REQUIRED_SETTINGS.size());
            ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                    .filter(setting -> !setting.propertyKey().equals(propertyKey))
                    .forEach(other -> assertThat(message)
                            .as("%s was supplied and must not appear in the report", other.propertyKey())
                            .doesNotContain(reportLineFor(other)));
        }

        /**
         * Runs the check and returns the failure text.
         *
         * @param variables variables to make resolvable
         * @return the message of the failure the check raised
         */
        private String messageFrom(final Map<String, String> variables) {
            try {
                ProductionConfigurationValidator.validateRequiredSettings(environmentWith(variables));
            } catch (final IllegalStateException expected) {
                return expected.getMessage();
            }
            throw new AssertionError("The check accepted an environment it should have refused");
        }
    }

    @Nested
    @DisplayName("The production data source is held to an authenticated transport")
    class TheProductionDataSourceIsHeldToAnAuthenticatedTransport {

        /** Creates the nested test class. */
        TheProductionDataSourceIsHeldToAnAuthenticatedTransport() {
        }

        /**
         * Builds an environment whose data source location is the supplied text.
         *
         * <p>Every other required variable is satisfied, so a refusal can only come from the location.
         *
         * @param  url the location to declare
         * @return an environment carrying the delivered documents and that location
         */
        private StandardEnvironment environmentWithUrl(final String url) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.put("CARDDEMO_DB_URL", url);
            return environmentWith(variables);
        }

        @Test
        @DisplayName("the authenticated production form is accepted, so the rule refuses what is weaker "
                + "rather than everything")
        void theAuthenticatedFormIsAccepted() {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateDatabaseTransport(environmentWithUrl(AUTHENTICATED_DATABASE_URL)))
                    .as("a named non-loopback host, an explicit port and database, and "
                            + "sslmode=verify-full is the whole of the requirement; a check that refused "
                            + "this would be a different rule from the one documented")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} is accepted")
        @ValueSource(strings = {
            "jdbc:postgresql://db.production.example:5432/carddemo?sslmode=VERIFY-FULL",
            "jdbc:postgresql://db.production.example:5432/carddemo?sslMode=verify-full",
            "jdbc:postgresql://db.production.example/carddemo?sslmode=verify-full&ApplicationName=cd",
            "jdbc:postgresql://db.production.example:5432/carddemo?connectTimeout=10"
                    + "&sslmode=verify-full&sslrootcert=/etc/ssl/root.crt",
            "JDBC:POSTGRESQL://db.production.example:5432/carddemo?sslmode=verify-full",
        })
        @DisplayName("the equivalent spellings a real deployment produces are accepted, because the "
                + "driver reads them equivalently")
        void theEquivalentSpellingsAreAccepted(final String url) {
            assertThatCode(() -> ProductionConfigurationValidator
                    .validateDatabaseTransport(environmentWithUrl(url)))
                    .as("the driver reads the parameter name and its value case-insensitively, accepts "
                            + "further parameters beside the mode, and does not require an explicit "
                            + "port. A check stricter than the driver would refuse a correct deployment "
                            + "for a reason the driver does not have")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "sslmode={0} is refused")
        @ValueSource(strings = {"disable", "allow", "prefer", "require", "verify-ca"})
        @DisplayName("every weaker transport rule the driver accepts is refused, and the refusal names "
                + "the guarantee that rule gives up")
        void everyWeakerModeIsRefused(final String mode) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("%s is one of the driver's own modes and each drops a different guarantee: "
                            + "encryption, chain validation or host-name validation. Refusing them as a "
                            + "class would tell a deployer their value was wrong without telling them "
                            + "what it cost", mode)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                            environmentWithUrl("jdbc:postgresql://db.production.example:5432/carddemo"
                                    + "?sslmode=" + mode)))
                    .withMessageContaining("spring.datasource.url")
                    .withMessageContaining(ProductionConfigurationValidator
                            .DATABASE_REFUSED_SSL_MODES.get(mode))
                    .withMessageContaining(ProductionConfigurationValidator
                            .DATABASE_REQUIRED_SSL_MODE);
        }

        @Test
        @DisplayName("every mode the driver accepts is either the required one or carries a recorded "
                + "reason, so the refusal above can never be silent about one")
        void everyDriverModeIsAccountedFor() {
            final Set<String> accountedFor =
                    new LinkedHashSet<>(ProductionConfigurationValidator.DATABASE_REFUSED_SSL_MODES
                            .keySet());
            accountedFor.add(ProductionConfigurationValidator.DATABASE_REQUIRED_SSL_MODE);

            assertThat(accountedFor)
                    .as("these are the six modes the PostgreSQL driver defines. A mode the driver "
                            + "accepts and this map does not would still be refused, but with the "
                            + "unrecognised-value wording rather than with what it gives up")
                    .containsExactlyInAnyOrder("disable", "allow", "prefer", "require", "verify-ca",
                            "verify-full");
        }

        @Test
        @DisplayName("an ABSENT transport rule is refused, because the driver's own default is prefer "
                + "and a silent plaintext fallback is what this check exists for")
        void anAbsentModeIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("THIS IS THE FINDING. jdbc:postgresql://named/db declares no transport rule, "
                            + "and the driver defaults sslmode to prefer - which asks for encryption "
                            + "and falls back to a plaintext session WITHOUT REPORTING IT. Accepting "
                            + "this URL is accepting a downgradeable, unauthenticated channel that "
                            + "carries every credential and every sealed identifier this deployment "
                            + "reads")
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                            environmentWithUrl("jdbc:postgresql://named/db")))
                    .withMessageContaining("must declare sslmode=verify-full")
                    .withMessageContaining("WITHOUT REPORTING IT");
        }

        @Test
        @DisplayName("a transport rule declared with an empty value is refused as absent rather than "
                + "read as satisfied")
        void anEmptyModeIsRefusedAsAbsent() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("sslmode= binds an empty string, which the check must not read as a declared "
                            + "mode; it is the same posture as omitting the parameter")
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                            environmentWithUrl("jdbc:postgresql://db.production.example/carddemo"
                                    + "?sslmode=")))
                    .withMessageContaining("must declare sslmode=verify-full");
        }

        @Test
        @DisplayName("an unrecognised transport rule is refused here rather than by the driver later")
        void anUnrecognisedModeIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                            environmentWithUrl("jdbc:postgresql://db.production.example/carddemo"
                                    + "?sslmode=verify-everything")))
                    .withMessageContaining("does not recognise");
        }

        @Test
        @DisplayName("a repeated transport rule is judged on the LAST value, which is the one the "
                + "driver applies")
        void aRepeatedModeIsJudgedOnTheEffectiveValue() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("appending a weaker mode after a stronger one is how a check that read the "
                            + "first occurrence would be defeated; the driver keeps the last, so this "
                            + "check must too")
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                            environmentWithUrl("jdbc:postgresql://db.production.example/carddemo"
                                    + "?sslmode=verify-full&sslmode=prefer")))
                    .withMessageContaining(ProductionConfigurationValidator
                            .DATABASE_REFUSED_SSL_MODES.get("prefer"));
        }

        @Test
        @DisplayName("a non-validating SSL factory is refused, because it would leave verify-full "
                + "stated and unenforced")
        void aNonValidatingSslFactoryIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("this is the one setting that makes the mode a statement with no effect, so "
                            + "refusing the mode without refusing this would be a check with a "
                            + "documented bypass")
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                            environmentWithUrl(AUTHENTICATED_DATABASE_URL
                                    + "&sslfactory=org.postgresql.ssl.NonValidatingFactory")))
                    .withMessageContaining("non-validating sslfactory");
        }

        @Test
        @DisplayName("a validating SSL factory is accepted, so the rule names the bypass rather than "
                + "forbidding the setting")
        void aValidatingSslFactoryIsAccepted() {
            assertThatCode(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                    environmentWithUrl(AUTHENTICATED_DATABASE_URL
                            + "&sslfactory=org.postgresql.ssl.LibPQFactory")))
                    .as("a deployment with its own validating factory is not the failure this rule "
                            + "addresses, and refusing it would make the rule about the parameter "
                            + "rather than about the bypass")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "a credential carried as {0} is refused")
        @ValueSource(strings = {
            "jdbc:postgresql://operator:secret@db.production.example:5432/carddemo"
                    + "?sslmode=verify-full",
            "jdbc:postgresql://db.production.example:5432/carddemo?sslmode=verify-full&user=operator",
            "jdbc:postgresql://db.production.example:5432/carddemo?sslmode=verify-full"
                    + "&password=whatever",
        })
        @DisplayName("a credential embedded in the location is refused however it is carried")
        void anEmbeddedCredentialIsRefused(final String url) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("either form puts a credential in the one production value that appears in a "
                            + "configuration dump and a process listing, and either silently overrides "
                            + "spring.datasource.username and spring.datasource.password - the two "
                            + "settings this class guards beside the location")
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateDatabaseTransport(environmentWithUrl(url)))
                    .withMessageContaining("spring.datasource.url");
        }

        @ParameterizedTest(name = "{0} is refused for its host")
        @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/carddemo?sslmode=verify-full",
            "jdbc:postgresql://127.0.0.1:5432/carddemo?sslmode=verify-full",
            "jdbc:postgresql://[::1]:5432/carddemo?sslmode=verify-full",
        })
        @DisplayName("a loopback host is refused even with the required transport rule, because the "
                + "database is not in this process")
        void aLoopbackHostIsRefused(final String url) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a loopback host in production means the variable was never set and something "
                            + "else answered; it is also the one host for which relaxing the transport "
                            + "rule looks harmless")
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateDatabaseTransport(environmentWithUrl(url)))
                    .withMessageContaining("loopback");
        }

        @Test
        @DisplayName("a host-less location is refused, so the server is stated rather than inferred")
        void aHostlessLocationIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateDatabaseTransport(environmentWithUrl(
                                    "jdbc:postgresql:carddemo?sslmode=verify-full")))
                    .withMessageContaining("must name a host");
        }

        @ParameterizedTest(name = "{0} is refused for its sub-protocol")
        @ValueSource(strings = {
            "jdbc:h2:mem:carddemo",
            "jdbc:mysql://db.production.example:3306/carddemo?sslmode=verify-full",
            "postgresql://db.production.example:5432/carddemo?sslmode=verify-full",
        })
        @DisplayName("a location naming another sub-protocol is refused, because the transport rules "
                + "are the PostgreSQL driver's and no other driver honours them")
        void anotherSubProtocolIsRefused(final String url) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("this is also the one rule that refuses an embedded database outright, which "
                            + "the profile document's fixed driver declaration cannot do on its own")
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateDatabaseTransport(environmentWithUrl(url)))
                    .withMessageContaining("sub-protocol");
        }

        @Test
        @DisplayName("a malformed location is refused as malformed rather than passing the rules it "
                + "cannot be parsed for")
        void aMalformedLocationIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateDatabaseTransport(environmentWithUrl(
                                    "jdbc:postgresql://db production example/carddemo")))
                    .withMessageContaining("well-formed");
        }

        @Test
        @DisplayName("every fault is reported together, so a location wrong in three ways is corrected "
                + "in one attempt")
        void everyFaultIsReportedTogether() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(
                            environmentWithUrl("jdbc:postgresql://operator:secret@localhost/carddemo")))
                    .withMessageContaining("breaks 3 rule(s)")
                    .withMessageContaining("loopback")
                    .withMessageContaining("user information")
                    .withMessageContaining("must declare sslmode=verify-full");
        }

        @Test
        @DisplayName("the refusal never repeats the configured location, because a credential pasted "
                + "into it is one of the faults reported")
        void theRefusalNeverRepeatsTheConfiguredLocation() {
            final String url = "jdbc:postgresql://operator:"
                    + "value-this-test-supplies-and-the-message-must-not-carry@db.example/carddemo"
                    + "?sslmode=verify-full";

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateDatabaseTransport(environmentWithUrl(url)))
                    .extracting(Throwable::getMessage, as(STRING))
                    .as("echoing the value would write the credential the refusal is about into the "
                            + "start-up log the refusal is read from")
                    .doesNotContain("value-this-test-supplies-and-the-message-must-not-carry")
                    .contains("deliberately not repeated");
        }

        @Test
        @DisplayName("an absent location is left to the required-settings sweep, so one missing "
                + "variable does not produce two messages")
        void anAbsentLocationIsNotReportedHere() {
            final Map<String, String> variables = everyRequiredVariable();
            variables.remove("CARDDEMO_DB_URL");

            assertThatCode(() -> ProductionConfigurationValidator
                    .validateDatabaseTransport(environmentWith(variables)))
                    .as("validateRequiredSettings already refuses this, and a deployer told to both "
                            + "set a variable and correct its transport learns to fix one of them and "
                            + "stop reading")
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the sweep must still be the thing that reports it, or the value is unguarded")
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environmentWith(variables)));
        }

        @Test
        @DisplayName("a null environment is rejected rather than silently passing")
        void aNullEnvironmentIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateDatabaseTransport(null))
                    .withMessageContaining("environment");
        }
    }

    @Nested
    @DisplayName("The report is complete and actionable")
    class TheReportIsCompleteAndActionable {

        @Test
        @DisplayName("an empty environment is reported in full rather than one fault at a time")
        void everyFaultIsReportedTogether() {
            final IllegalStateException failure = failureFrom(environmentWith(Map.of()));
            final String message = failure.getMessage();
            final int required = ProductionConfigurationValidator.REQUIRED_SETTINGS.size();

            assertThat(message).contains(required + " of " + required + " required settings");
            ProductionConfigurationValidator.REQUIRED_SETTINGS.forEach(setting -> {
                assertThat(message).contains(reportLineFor(setting));
                assertThat(message).contains(setting.environmentVariable());
            });
        }

        @Test
        @DisplayName("the report names the profile it refused to start")
        void theReportNamesTheProfile() {
            assertThat(failureFrom(environmentWith(Map.of())).getMessage())
                    .contains("'" + ProductionConfigurationValidator.PRODUCTION_PROFILE + "' profile");
        }

        @Test
        @DisplayName("the report states that no fallback exists by design")
        void theReportExplainsTheAbsenceOfAFallback() {
            assertThat(failureFrom(environmentWith(Map.of())).getMessage())
                    .contains("carries no fallback by design");
        }

        @Test
        @DisplayName("the report points at the recorded decision and the profile document")
        void theReportPointsAtTheRecordedDecision() {
            assertThat(failureFrom(environmentWith(Map.of())).getMessage())
                    .contains(RECORDED_DECISION)
                    .contains(PRODUCTION_DOCUMENT);
        }

        @Test
        @DisplayName("an undeclared property is distinguished from an unset variable")
        void anUndeclaredPropertyIsDistinguishedFromAnUnsetVariable() {
            final StandardEnvironment bare = new StandardEnvironment();
            bare.getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            bare.getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

            final String message = failureFrom(bare).getMessage();

            assertThat(message)
                    .as("with no profile document loaded, the properties themselves are missing")
                    .contains("not declared by any active profile");
            ProductionConfigurationValidator.REQUIRED_SETTINGS.forEach(setting ->
                    assertThat(message).contains(reportLineFor(setting)));
        }

        @Test
        @DisplayName("the report lists faults in the order the profile declares them")
        void theReportPreservesDeclarationOrder() {
            final String message = failureFrom(environmentWith(Map.of())).getMessage();
            int previous = -1;
            for (final RequiredSetting setting : ProductionConfigurationValidator.REQUIRED_SETTINGS) {
                final int position = message.indexOf(reportLineFor(setting));
                assertThat(position)
                        .as("%s must appear in the report", setting.propertyKey())
                        .isGreaterThan(previous);
                previous = position;
            }
        }

        /**
         * Runs the check against an environment expected to be rejected.
         *
         * @param environment environment to check
         * @return the failure the check raised
         */
        private IllegalStateException failureFrom(final StandardEnvironment environment) {
            try {
                ProductionConfigurationValidator.validateRequiredSettings(environment);
            } catch (final IllegalStateException expected) {
                return expected;
            }
            throw new AssertionError("The check accepted an environment it should have refused");
        }
    }

    @Nested
    @DisplayName("The guarded set is the set the profile declares")
    class TheGuardedSetMatchesTheDocument {

        @Test
        @DisplayName("every bare environment reference in the production profile is guarded")
        void everyBareReferenceIsGuarded() {
            final Set<String> declared =
                    new LinkedHashSet<>(referencesIn(PRODUCTION_DOCUMENT, BARE_REFERENCE).keySet());

            assertThat(declared)
                    .as("a bare reference with no fallback can only be satisfied by the environment, "
                            + "so every one of them must be guarded; add the new key to "
                            + "REQUIRED_SETTINGS or give it a fallback")
                    .containsExactlyInAnyOrderElementsOf(guardedKeys());
        }

        @ParameterizedTest(name = "{0} is guarded against the variable the profile names, {1}")
        @MethodSource("settings")
        @DisplayName("each guarded key names the variable the profile actually references")
        void eachGuardedKeyNamesTheDeclaredVariable(final String propertyKey, final String variable) {
            assertThat(referencesIn(PRODUCTION_DOCUMENT, BARE_REFERENCE))
                    .containsEntry(propertyKey, variable);
        }

        static Stream<Arguments> settings() {
            return requiredSettings();
        }

        @Test
        @DisplayName("every guarded variable also appears in the profile's own list of the variables it "
                + "requires, which the list calls authoritative")
        void everyGuardedVariableIsListedInTheDocument() throws IOException {
            // The header of application-prod.yml states that its variable list is the authoritative one and
            // that the module README and any deployment runbook are written from it. Nothing checked that
            // until review, and one variable had already gone missing from it: CARDDEMO_MANAGEMENT_TOKEN,
            // the collector's credential and the sole source of the monitoring authority, was guarded and
            // bound but absent from the list a deployer reads. A list described as authoritative and not
            // asserted is a list that drifts. See docs/decision-log.md entry DL-312.
            final String document = Files.readString(
                    Path.of("src", "main", "resources", PRODUCTION_DOCUMENT));
            final String header = document.substring(0,
                    document.indexOf("ENVIRONMENT VARIABLES THIS PROFILE REQUIRES")
                            + document.substring(document.indexOf(
                                    "ENVIRONMENT VARIABLES THIS PROFILE REQUIRES")).indexOf("\nspring:"));

            for (final RequiredSetting setting : ProductionConfigurationValidator.REQUIRED_SETTINGS) {
                assertThat(header)
                        .as("%s is guarded, so the list a deployer reads must name it",
                                setting.environmentVariable())
                        .contains(setting.environmentVariable());
            }
        }

        @Test
        @DisplayName("no setting that carries a fallback is guarded")
        void noDefaultedSettingIsGuarded() {
            final Set<String> defaulted =
                    new LinkedHashSet<>(referencesIn(PRODUCTION_DOCUMENT, DEFAULTED_REFERENCE).keySet());

            assertThat(defaulted)
                    .as("a documented default must not become a deployment obligation")
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(guardedKeys());
        }

        @Test
        @DisplayName("no other profile document, in any physical copy, carries a bare reference")
        void theRequirementBelongsToProductionAlone() {
            final List<String> elsewhere = new ArrayList<>();
            for (final String document :
                    List.of(SHARED_DOCUMENT, "application-local.yml", "application-test.yml")) {
                for (final Resource copy : physicalCopiesOf(document)) {
                    referencesIn(sourcesOf(copy, document), BARE_REFERENCE).keySet()
                            .forEach(key -> elsewhere.add(copy.getDescription() + " -> " + key));
                }
            }

            assertThat(elsewhere)
                    .as("a bare reference outside the production profile would make some other profile "
                            + "require an environment variable that nothing guards; the test profile is "
                            + "checked in both of its physical copies for that reason")
                    .isEmpty();
        }

        @Test
        @DisplayName("no key is guarded twice and no variable is reused")
        void theGuardedSetHasNoDuplicate() {
            assertThat(guardedKeys()).doesNotHaveDuplicates();
            assertThat(ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                    .map(RequiredSetting::environmentVariable)
                    .toList())
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("A required setting refuses a half-built entry")
    class TheSettingRecordRefusesAHalfBuiltEntry {

        @Test
        @DisplayName("a null property key is rejected")
        void aNullPropertyKeyIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RequiredSetting(null, "CARDDEMO_DB_URL"))
                    .withMessageContaining("propertyKey");
        }

        @Test
        @DisplayName("a null variable name is rejected")
        void aNullVariableIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RequiredSetting("spring.datasource.url", null))
                    .withMessageContaining("environmentVariable");
        }

        @Test
        @DisplayName("a blank property key is rejected")
        void aBlankPropertyKeyIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RequiredSetting("  ", "CARDDEMO_DB_URL"))
                    .withMessageContaining("property key");
        }

        @Test
        @DisplayName("a blank variable name is rejected")
        void aBlankVariableIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RequiredSetting("spring.datasource.url", ""))
                    .withMessageContaining("variable name");
        }

        @Test
        @DisplayName("a well-formed entry keeps both halves")
        void aWellFormedEntryKeepsBothHalves() {
            final RequiredSetting setting =
                    new RequiredSetting("spring.datasource.url", "CARDDEMO_DB_URL");

            assertThat(setting.propertyKey()).isEqualTo("spring.datasource.url");
            assertThat(setting.environmentVariable()).isEqualTo("CARDDEMO_DB_URL");
        }
    }
}
