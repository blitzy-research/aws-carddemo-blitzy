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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Asserts that {@link JwtProperties} refuses to bind anything unusable, never lets the signing material
 * escape through a description, and - the responsibility this file holds alone - that no secret the
 * production profile references carries a fallback default.
 *
 * <p><strong>Why a bearer token exists at all, as metadata.</strong> The legacy sign-on program
 * {@code app/cbl/COSGN00C.cbl} read the user-security record and, at line 223, decided the outcome by
 * comparing the stored password field against the one just typed, as text and in the clear; the record
 * itself is {@code app/cpy/CSUSR01Y.cpy}, 80 bytes wide, whose {@code SEC-USR-PWD} field is 8 characters
 * of cleartext beside an 8-character {@code SEC-USR-ID}, two 20-character names, a 1-character
 * {@code SEC-USR-TYPE} and 23 bytes of filler. This module hashes that credential with BCrypt instead -
 * the one documented departure from behavioural parity - and signs the outcome into a bearer token, so
 * the material that signs the token stands where a cleartext comparison used to, and may itself never be
 * defaulted or literal. No line of that member or copybook is reproduced here; only member paths, field
 * names, widths and a line number, which are metadata.
 *
 * <p><strong>What this class asserts.</strong> Binding under the exact prefix, including the relaxed
 * forms an operator may legitimately write; that every constraint is load-bearing rather than decorative,
 * so a missing or blank secret, a missing or blank issuer and a non-positive lifetime each stop start-up;
 * that the description withholds the secret while still showing what is useful for diagnosis; that the
 * record is registered by its owner rather than discovering itself; and the production placeholder sweep
 * described below.
 *
 * <p><strong>★ The production placeholder sweep, and why it is not a duplicate.</strong> Two sibling
 * classes already audit production configuration, and both work forwards from a list of keys somebody
 * maintained: {@code ApplicationProfileStartupTest} drives each enumerated variable through a started
 * context and cross-checks its list against every <em>bare</em> reference the document writes, and
 * {@code ConfigurationProfileBaselineTest} asserts individual named keys - the signing secret and the
 * field-encryption key among them - are written without a fallback. Neither can see the one mistake this
 * sweep exists to catch: a <em>new</em> secret added <em>with</em> a fallback never appears in a
 * bare-reference set and is on no list, so it would start cleanly, sign real tokens with a value
 * published in this repository, and nothing would fail. This sweep therefore works backwards from the
 * document - it reads {@code application-prod.yml}, extracts every placeholder occurrence, keeps the ones
 * whose <em>name</em> reads like a credential, and requires every one of them to be bare. It needs no
 * list, so nothing can be added behind its back.
 *
 * <p><strong>What this class deliberately does not assert.</strong> Token minting, parsing, tampering and
 * claim content belong to {@code JwtTokenProviderTest}; the route-to-role table, the password encoder,
 * statelessness and request forgery belong to {@code SecurityConfigTest}, which also owns the assertion
 * that the deprecated constructor-binding annotation is absent from the record's source; the cloud
 * property paths belong to {@code AwsPropertiesTest}, which does not repeat this sweep; the shared
 * baseline's silence on the signing secret and the comparison of the two test-overlay copies belong to
 * {@code ConfigurationProfileBaselineTest}; and the sign-on message texts belong to the authentication
 * and message-catalog service tests. No expectation below is computed by calling the class under test:
 * every duration is built from {@link Duration} in the test, and every constraint name is taken from the
 * constraint annotation itself.
 *
 * <p>No credential appears in this file. Every secret it uses is generated at run time from
 * {@link SecureRandom}, exists for the length of one test method, is never written to any stream, and is
 * never the value any profile ships.
 */
@DisplayName("Bearer-token settings: unusable configuration stops start-up, the secret never appears in "
        + "a description, and no production secret carries a fallback default")
class JwtPropertiesTest {

    /**
     * Configuration key prefix under test, taken from the type rather than restated.
     *
     * <p>One test - and only one - compares it against the literal the shipped profiles declare, which is
     * what stops the constant and the documents from drifting apart in step.</p>
     */
    private static final String PREFIX = JwtProperties.PREFIX;

    /** Issuer used throughout, matching the value every shipped profile declares. It is not a secret. */
    private static final String ISSUER = "carddemo-java";

    /** A lifetime used wherever a test needs a valid one, built in the test rather than read anywhere. */
    private static final Duration A_USABLE_LIFETIME = Duration.ofMinutes(30);

    /** Bytes of random material behind each generated secret, comfortably wider than any signature needs. */
    private static final int SECRET_MATERIAL_BYTES = 48;

    /** Source of the material behind every generated secret. Seeded by the platform, never by a literal. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The production profile document, read from the class path as text by the sweep. */
    private static final String PRODUCTION_DOCUMENT = "application-prod.yml";

    /** The local overlay, which deliberately does carry a self-describing non-production fallback. */
    private static final String LOCAL_DOCUMENT = "application-local.yml";

    /** The test overlay in force during a suite run, which carries the same kind of fallback. */
    private static final String TEST_DOCUMENT = "application-test.yml";

    /** The environment variable the signing secret is supplied by, as the production document names it. */
    private static final String SIGNING_SECRET_VARIABLE = "CARDDEMO_JWT_SECRET";

    /** How a comment opens in a YAML document, used to tell configuration apart from prose. */
    private static final String COMMENT_MARKER = "#";

    /** Separator between a placeholder's variable name and its fallback default. */
    private static final char FALLBACK_SEPARATOR = ':';

    /**
     * One placeholder occurrence: {@code ${NAME}} or {@code ${NAME:fallback}}.
     *
     * <p>The body deliberately excludes brace characters so that a nested placeholder cannot be matched
     * as though it were a flat one. Nothing in the shipped documents nests, and a companion assertion
     * proves this pattern saw every {@code ${} the document contains, so a nested form introduced later
     * fails the sweep rather than slipping past it.</p>
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]*)}");

    /** The opening of a placeholder, counted separately to prove the pattern above missed nothing. */
    private static final Pattern PLACEHOLDER_OPENING = Pattern.compile("\\$\\{");

    /**
     * Names that make a placeholder a credential for the purposes of this sweep.
     *
     * <p>{@link Pattern#CASE_INSENSITIVE} is used <em>without</em> {@link Pattern#UNICODE_CASE} on
     * purpose: ASCII-only folding is what a variable name needs, and it cannot change behaviour under a
     * default locale whose case rules differ from ASCII. Nothing here lower-cases a string, for the same
     * reason.</p>
     */
    private static final Pattern SECRET_NAMED =
            Pattern.compile("secret|password|passwd|pwd|key|token|credential", Pattern.CASE_INSENSITIVE);

    /**
     * Text by which a non-production fallback declares itself to be one.
     *
     * <p>A marker rather than a whole value, so that no credential-shaped literal enters this file while
     * the assertion still proves the shipped fallbacks say what they are.</p>
     */
    private static final Pattern NON_PRODUCTION_MARKER = Pattern.compile(
            "local-development-only|test-only|do-not-reuse|not-used-outside-tests",
            Pattern.CASE_INSENSITIVE);

    /**
     * Fallback values a defective earlier revision of this module used, which must never reappear.
     *
     * <p>Compared for equality rather than containment, because a resource name may legitimately begin
     * with the same word as one of them.</p>
     */
    private static final List<String> BANNED_FALLBACK_VALUES = List.of("carddemo", "test");

    /**
     * A variable name that belongs to the container stack and must not appear in the production profile.
     *
     * <p>The same defective revision reached a database through it with a fallback attached.</p>
     */
    private static final String BANNED_VARIABLE = "POSTGRES_PASSWORD";

    /**
     * Runner that registers the settings record exactly as the security configuration does, with a
     * validator present.
     *
     * <p>Binding is what is under test - validation included - so the record is enabled through the same
     * annotation the owner uses rather than constructed directly, because a directly constructed record
     * bypasses the validator entirely. The validation auto-configuration is registered explicitly so the
     * presence of a validator is a stated part of the fixture; the framework would supply one for
     * configuration-properties binding regardless, since a validation implementation is on the class
     * path, and registering it changes no outcome asserted here.</p>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(BindingHarness.class);

    /**
     * Registers the settings record for binding, and nothing else.
     *
     * <p>Nothing that consumes the settings is present, so a failure observed through this harness is a
     * binding or validation failure and can be nothing else.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class BindingHarness {
    }

    /**
     * Returns a freshly generated signing secret.
     *
     * <p>Derived at run time from {@link SecureRandom} and encoded with the URL-safe alphabet without
     * padding, so the value is safe to place in a property expression and cannot be mistaken for, or
     * reused as, anything a profile ships. It is never printed, logged or compared against a stored
     * value; the only thing asserted about it is that a description does <em>not</em> contain it.</p>
     *
     * @return a fresh secret whose material is {@value #SECRET_MATERIAL_BYTES} random bytes
     */
    private static String freshSecret() {
        byte[] material = new byte[SECRET_MATERIAL_BYTES];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * Returns a complete, valid property set for the runner, carrying a freshly generated secret.
     *
     * @param secret the signing secret to configure
     * @return {@code key=value} entries for all three components
     */
    private static String[] usableSettings(final String secret) {
        return new String[] {
            PREFIX + ".secret=" + secret,
            PREFIX + ".issuer=" + ISSUER,
            PREFIX + ".expiration=" + A_USABLE_LIFETIME,
        };
    }

    /**
     * Binds the settings from a property map, with no context and therefore no validator.
     *
     * <p>Used where what matters is the mapping from key to component - which keys reach which component,
     * and how a written value becomes a {@link Duration}. Refusal of an unusable value is asserted through
     * the runner instead, because a validator only participates when a context binds.</p>
     *
     * @param source property names and values to bind from
     * @return the bound settings
     */
    private static JwtProperties boundFrom(final Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind(PREFIX, Bindable.of(JwtProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                        "nothing bound under " + PREFIX + ", so the keys under test reached no component. "
                        + "Supplied names: " + source.keySet()));
    }

    /**
     * Reads a shipped configuration document from the class path as text, comments included.
     *
     * <p>Read as text rather than as parsed properties on purpose: a fallback default is part of the
     * <em>written</em> value and disappears the moment the document is resolved, so a parsed view cannot
     * tell a bare reference from a defaulted one.</p>
     *
     * @param resourceName class-path name of the document
     * @return the document's full text
     * @throws IOException if the document is present but cannot be read
     */
    private static String textOf(final String resourceName) throws IOException {
        ClassPathResource document = new ClassPathResource(resourceName);

        assertThat(document.exists())
                .as("%s must be on the class path. A sweep that cannot find the document it audits "
                        + "would pass while auditing nothing, so a missing document is a failure here "
                        + "rather than a skipped assertion", resourceName)
                .isTrue();

        return document.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Extracts every placeholder occurrence from a document.
     *
     * @param document          the document text
     * @param configurationOnly {@code true} to skip comment lines, leaving only lines that bind something;
     *                          {@code false} to sweep the whole document, prose included
     * @return the occurrences found, in the order the document writes them
     */
    private static List<Placeholder> placeholdersIn(final String document, final boolean configurationOnly) {
        List<Placeholder> found = new ArrayList<>();
        int lineNumber = 0;

        for (final String line : document.lines().toList()) {
            lineNumber++;
            if (configurationOnly && line.stripLeading().startsWith(COMMENT_MARKER)) {
                continue;
            }
            Matcher occurrence = PLACEHOLDER.matcher(line);
            while (occurrence.find()) {
                found.add(new Placeholder(lineNumber, occurrence.group(1)));
            }
        }
        return found;
    }

    /**
     * Keeps the placeholders whose variable name reads like a credential.
     *
     * @param placeholders occurrences to filter
     * @return the occurrences whose name matches {@link #SECRET_NAMED}
     */
    private static List<Placeholder> secretNamed(final List<Placeholder> placeholders) {
        return placeholders.stream()
                .filter(placeholder -> SECRET_NAMED.matcher(placeholder.name()).find())
                .toList();
    }

    /**
     * Finds the binding-validation failure inside a start-up failure's cause chain.
     *
     * @param startupFailure the failure a refused context recorded
     * @return the validation failure that caused it
     */
    private static BindValidationException validationFailureOf(final Throwable startupFailure) {
        for (Throwable cause = startupFailure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BindValidationException validation) {
                return validation;
            }
        }
        throw new AssertionError("the start-up failure carried no binding-validation failure, so the "
                + "context was not refused by a constraint on these settings and this assertion is "
                + "measuring something else: " + startupFailure);
    }

    /**
     * Asserts a context was refused by one named constraint on one named component.
     *
     * <p>Asserted by failure type, by the component the failure names and by the constraint's own simple
     * name - never by message text, which a framework generation may reword without any change in
     * behaviour, and which is interpolated under whatever locale the run happens to use.</p>
     *
     * @param context    the refused context
     * @param component  the component the failure must name
     * @param constraint the constraint annotation that must have rejected it
     */
    private static void assertRefusedByConstraint(final AssertableApplicationContext context,
            final String component, final Class<?> constraint) {

        assertThat(context)
                .as("settings that cannot mint or verify a token must stop start-up rather than bind")
                .hasFailed();
        assertThat(context)
                .getFailure()
                .as("the refusal must come from binding these very settings, so this cannot pass "
                        + "because the context broke for an unrelated reason")
                .isInstanceOf(ConfigurationPropertiesBindException.class)
                .hasMessageContaining(PREFIX);

        BindValidationException validation = validationFailureOf(context.getStartupFailure());

        assertThat(validation.getValidationErrors().getName())
                .as("the failure must be reported against the settings' own prefix")
                .hasToString(PREFIX);

        List<ObjectError> reported = validation.getValidationErrors().getAllErrors();
        List<FieldError> named = reported.stream()
                .filter(FieldError.class::isInstance)
                .map(FieldError.class::cast)
                .toList();

        assertThat(named)
                .as("the failure must name the component at fault - an operator reading it has to be "
                        + "told which key to supply. Reported: %s", reported)
                .isNotEmpty();
        assertThat(named).extracting(FieldError::getField)
                .as("%s is the component that must be reported", component)
                .containsExactly(component);
        assertThat(named).extracting(FieldError::getCode)
                .as("and %s is the constraint that must have rejected it. The expected name is taken "
                        + "from the annotation itself, so it follows a rename rather than going stale",
                        constraint.getSimpleName())
                .containsExactly(constraint.getSimpleName());
    }

    @Nested
    @DisplayName("Binding under the settings' own prefix")
    class BindingTheSettings {

        @Test
        @DisplayName("fills every component from the exact prefix, so all three deployment decisions "
                + "arrive rather than defaulting quietly")
        void fillsEveryComponentFromTheExactPrefix() {
            String secret = freshSecret();
            Map<String, Object> written = new LinkedHashMap<>();
            written.put(PREFIX + ".secret", secret);
            written.put(PREFIX + ".issuer", ISSUER);
            written.put(PREFIX + ".expiration", "PT30M");

            JwtProperties bound = boundFrom(written);

            assertThat(bound.secret())
                    .as("the signing material must arrive verbatim; a transformed secret verifies "
                            + "nothing the minting side signed")
                    .isEqualTo(secret);
            assertThat(bound.issuer()).isEqualTo(ISSUER);
            assertThat(bound.expiration())
                    .as("thirty minutes, computed here rather than read back from the class under test")
                    .isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("reads a lifetime written in the framework's suffix form, so a configured value may "
                + "state its own scale either way")
        void readsALifetimeWrittenInTheSuffixForm() {
            Map<String, Object> written = new LinkedHashMap<>();
            written.put(PREFIX + ".secret", freshSecret());
            written.put(PREFIX + ".issuer", ISSUER);
            written.put(PREFIX + ".expiration", "30m");

            assertThat(boundFrom(written).expiration())
                    .as("the suffix form and the ISO-8601 form must reach the same span, or a lifetime "
                            + "would depend on which spelling an operator happened to use")
                    .isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("accepts the relaxed spellings the framework provides - upper-cased elements bind "
                + "the same components as the canonical form")
        void acceptsUpperCasedElements() {
            String secret = freshSecret();
            Map<String, Object> written = new LinkedHashMap<>();
            written.put("CARDDEMO.SECURITY.JWT.SECRET", secret);
            written.put("CARDDEMO.SECURITY.JWT.ISSUER", ISSUER);
            written.put("CARDDEMO.SECURITY.JWT.EXPIRATION", "PT30M");

            JwtProperties bound = boundFrom(written);

            assertThat(bound.secret()).isEqualTo(secret);
            assertThat(bound.issuer()).isEqualTo(ISSUER);
            assertThat(bound.expiration()).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("binds from the environment-variable spelling, which is how a deployment overrides "
                + "these keys without editing a document")
        void bindsFromTheEnvironmentVariableSpelling() {
            String secret = freshSecret();
            Map<String, Object> exported = new LinkedHashMap<>();
            exported.put("CARDDEMO_SECURITY_JWT_SECRET", secret);
            exported.put("CARDDEMO_SECURITY_JWT_ISSUER", ISSUER);
            exported.put("CARDDEMO_SECURITY_JWT_EXPIRATION", "PT30M");

            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources()
                    .addFirst(new SystemEnvironmentPropertySource("exportedForThisTest", exported));

            JwtProperties bound = new Binder(ConfigurationPropertySources.get(environment))
                    .bind(PREFIX, Bindable.of(JwtProperties.class))
                    .orElseThrow(() -> new IllegalStateException(
                            "the environment-variable spelling of " + PREFIX + " bound nothing, so an "
                            + "operator could export these names and change nothing"));

            assertThat(bound.secret()).isEqualTo(secret);
            assertThat(bound.issuer()).isEqualTo(ISSUER);
            assertThat(bound.expiration()).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("takes nothing from the parent namespace, so a secret written one level up is not "
                + "silently adopted")
        void takesNothingFromTheParentNamespace() {
            Map<String, Object> written = new LinkedHashMap<>();
            written.put("carddemo.security.secret", freshSecret());
            written.put(PREFIX + ".issuer", ISSUER);
            written.put(PREFIX + ".expiration", "PT30M");

            assertThat(boundFrom(written).secret())
                    .as("the prefix is exact. A key one level up belongs to the transport and at-rest "
                            + "settings, and adopting it here would bind material nobody meant for a token")
                    .isNull();
        }

        @Test
        @DisplayName("starts a context cleanly and publishes the bound settings when every component is "
                + "usable, so the refusals below are about the values and not about the wiring")
        void startsCleanlyWhenEveryComponentIsUsable() {
            String secret = freshSecret();

            JwtPropertiesTest.this.runner.withPropertyValues(usableSettings(secret))
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        JwtProperties bound = context.getBean(JwtProperties.class);

                        assertThat(bound.secret()).isEqualTo(secret);
                        assertThat(bound.issuer()).isEqualTo(ISSUER);
                        assertThat(bound.expiration()).isEqualTo(Duration.ofMinutes(30));
                    });
        }
    }

    @Nested
    @DisplayName("A configuration that could not mint or verify a token")
    class UnusableConfiguration {

        @Test
        @DisplayName("stops start-up when no signing secret is configured, rather than defaulting one")
        void isRefusedWhenTheSecretIsAbsent() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=" + A_USABLE_LIFETIME)
                    .run(context -> assertRefusedByConstraint(context, "secret", NotBlank.class));
        }

        @ParameterizedTest(name = "secret = [{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("stops start-up when the configured secret is present but blank, because whitespace "
                + "signs nothing")
        void isRefusedWhenTheSecretIsBlank(final String blank) {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + blank,
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=" + A_USABLE_LIFETIME)
                    .run(context -> assertRefusedByConstraint(context, "secret", NotBlank.class));
        }

        @Test
        @DisplayName("stops start-up when no issuer is configured, since a token must claim an origin "
                + "the verifier can require")
        void isRefusedWhenTheIssuerIsAbsent() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + freshSecret(),
                            PREFIX + ".expiration=" + A_USABLE_LIFETIME)
                    .run(context -> assertRefusedByConstraint(context, "issuer", NotBlank.class));
        }

        @ParameterizedTest(name = "issuer = [{0}]")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("stops start-up when the configured issuer is blank, which no verifier could match")
        void isRefusedWhenTheIssuerIsBlank(final String blank) {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + freshSecret(),
                            PREFIX + ".issuer=" + blank,
                            PREFIX + ".expiration=" + A_USABLE_LIFETIME)
                    .run(context -> assertRefusedByConstraint(context, "issuer", NotBlank.class));
        }

        @Test
        @DisplayName("stops start-up when no lifetime is configured, and reports it as a missing value "
                + "rather than as an invalid one")
        void isRefusedWhenTheLifetimeIsAbsent() {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + freshSecret(),
                            PREFIX + ".issuer=" + ISSUER)
                    .run(context -> assertRefusedByConstraint(context, "expiration", NotNull.class));
        }

        @ParameterizedTest(name = "expiration = {0}")
        @ValueSource(strings = {"PT0S", "PT-1S", "PT-30M"})
        @DisplayName("stops start-up when the lifetime is not a positive span, which would mint tokens "
                + "already unusable at the instant they were issued")
        void isRefusedWhenTheLifetimeIsNotPositive(final String lifetime) {
            JwtPropertiesTest.this.runner
                    .withPropertyValues(
                            PREFIX + ".secret=" + freshSecret(),
                            PREFIX + ".issuer=" + ISSUER,
                            PREFIX + ".expiration=" + lifetime)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context)
                                .getFailure()
                                .as("the refusal must come from binding these very settings")
                                .isInstanceOf(ConfigurationPropertiesBindException.class);
                        // A constraint annotation cannot express positivity, so this one is refused by
                        // the record's own constructor. The root cause must therefore be this module's
                        // own message naming the key at fault, not a generic binding complaint that
                        // leaves an operator guessing which value was wrong.
                        assertThat(NestedExceptionUtils
                                .getMostSpecificCause(context.getStartupFailure()))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining(PREFIX + ".expiration")
                                .hasMessageContaining("positive");
                    });
        }

        @Test
        @DisplayName("names the offending key when the lifetime is rejected, so the failure is actionable "
                + "wherever the settings are constructed")
        void namesTheKeyItRejected() {
            String secret = freshSecret();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JwtProperties(secret, ISSUER, Duration.ZERO))
                    .withMessageContaining(PREFIX + ".expiration");
        }

        @Test
        @DisplayName("tolerates an absent lifetime in the constructor, leaving that report to the "
                + "validator instead of raising a null-pointer failure that names nothing")
        void leavesAnAbsentLifetimeToTheValidator() {
            JwtProperties constructed = new JwtProperties(freshSecret(), ISSUER, null);

            assertThat(constructed.expiration())
                    .as("the constructor must not pre-empt the validator's report of a missing value")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("The description of the settings")
    class Description {

        @Test
        @DisplayName("never contains the signing secret, because anything logging or rendering a "
                + "settings object would otherwise publish a credential")
        void withholdsTheSigningSecret() {
            String secret = freshSecret();

            String described = new JwtProperties(secret, ISSUER, A_USABLE_LIFETIME).toString();

            assertThat(described)
                    .as("the description must not carry the secret. A settings object reaches a log "
                            + "through a start-up banner, a failure report and any assertion that "
                            + "renders it, and a credential in any of those is published")
                    .doesNotContain(secret)
                    .contains("<redacted>");
        }

        @Test
        @DisplayName("does not disclose the secret's length either, which would narrow a search for it")
        void withholdsTheSecretLength() {
            String secret = freshSecret();

            String described = new JwtProperties(secret, ISSUER, A_USABLE_LIFETIME).toString();

            assertThat(described)
                    .as("the length of a secret is itself information about it, so a redaction that "
                            + "reported the number of characters would still be a disclosure")
                    .doesNotContain(String.valueOf(secret.length()));
        }

        @Test
        @DisplayName("still shows the issuer and the lifetime, neither of which is a credential and "
                + "both of which are needed to diagnose a refused token")
        void showsWhatIsNotSecret() {
            String described = new JwtProperties(freshSecret(), ISSUER, A_USABLE_LIFETIME).toString();

            assertThat(described)
                    .as("a blanket suppression would leave nothing to diagnose with; this override is "
                            + "a redaction of one component, not of the object")
                    .contains(ISSUER)
                    .contains(Duration.ofMinutes(30).toString());
        }

        @Test
        @DisplayName("withholds the secret even when no secret was supplied, so the redaction is "
                + "unconditional rather than value-dependent")
        void redactsUnconditionally() {
            String described = new JwtProperties(null, ISSUER, A_USABLE_LIFETIME).toString();

            assertThat(described)
                    .as("a redaction that only applied when there was something to redact would "
                            + "disclose the absence of a secret, and would be one branch away from "
                            + "disclosing its presence")
                    .contains("<redacted>")
                    .doesNotContain("null,");
        }
    }

    @Nested
    @DisplayName("The helpers that let a consumer ask about the settings without holding them")
    class Helpers {

        @ParameterizedTest(name = "secret = [{0}]")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("report a blank secret as absent, so a consumer need not repeat the blank test")
        void treatBlankAsAbsent(final String blank) {
            assertThat(new JwtProperties(blank, ISSUER, A_USABLE_LIFETIME).hasSecret())
                    .as("a consumer that wrote its own blank test would place the secret in a local "
                            + "variable it might then include in a message")
                    .isFalse();
        }

        @Test
        @DisplayName("report a null secret as absent")
        void treatNullAsAbsent() {
            assertThat(new JwtProperties(null, ISSUER, A_USABLE_LIFETIME).hasSecret()).isFalse();
        }

        @Test
        @DisplayName("report a supplied secret as present")
        void treatASuppliedSecretAsPresent() {
            assertThat(new JwtProperties(freshSecret(), ISSUER, A_USABLE_LIFETIME).hasSecret()).isTrue();
        }

        @Test
        @DisplayName("compare issuers without either caller holding the signing material")
        void compareIssuersAlone() {
            JwtProperties minting = new JwtProperties(freshSecret(), ISSUER, A_USABLE_LIFETIME);
            JwtProperties verifying = new JwtProperties(freshSecret(), ISSUER, Duration.ofHours(1));

            assertThat(minting.sharesIssuerWith(verifying))
                    .as("agreement on the issuer is independent of the secret and the lifetime, which "
                            + "is what lets two separately configured sides be compared safely")
                    .isTrue();
        }

        @Test
        @DisplayName("report a differing issuer as a disagreement")
        void reportADifferingIssuer() {
            String secret = freshSecret();
            JwtProperties mine = new JwtProperties(secret, ISSUER, A_USABLE_LIFETIME);

            assertThat(mine.sharesIssuerWith(
                    new JwtProperties(secret, "somebody-else", A_USABLE_LIFETIME)))
                    .isFalse();
        }

        @Test
        @DisplayName("treat a missing comparison subject as a disagreement rather than failing")
        void treatNullComparisonAsDisagreement() {
            assertThat(new JwtProperties(freshSecret(), ISSUER, A_USABLE_LIFETIME).sharesIssuerWith(null))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The configuration key prefix")
    class KeyPrefix {

        @Test
        @DisplayName("is the one the shipped profiles declare these settings under")
        void isTheShippedPrefix() {
            assertThat(JwtProperties.PREFIX)
                    .as("this is the one place the literal is written twice on purpose: the constant "
                            + "and the shipped documents must agree, and a rename that reached only "
                            + "one of them would leave every key unbound and silently defaulted")
                    .isEqualTo("carddemo.security.jwt");
        }

        @Test
        @DisplayName("is a child of the security namespace rather than the transport or at-rest key, "
                + "each of which is bound by its own consumer")
        void isDistinctFromItsSiblings() {
            assertThat(JwtProperties.PREFIX)
                    .startsWith("carddemo.security.")
                    .isNotEqualTo("carddemo.security.require-https")
                    .isNotEqualTo("carddemo.security.field-encryption");
        }
    }

    @Nested
    @DisplayName("Registration of the settings")
    class Registration {

        @Test
        @DisplayName("yields exactly one bound instance when a participant enables it, so no consumer "
                + "can be handed a second copy bound from somewhere else")
        void yieldsExactlyOneBoundInstance() {
            JwtPropertiesTest.this.runner.withPropertyValues(usableSettings(freshSecret()))
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(JwtProperties.class));
        }

        @Test
        @DisplayName("is absent entirely when nothing enables it, so the record never registers itself - "
                + "which is what keeps that single registration single")
        void isAbsentWhenNothingEnablesIt() {
            new ApplicationContextRunner()
                    .withPropertyValues(usableSettings(freshSecret()))
                    .run(context -> assertThat(context)
                            .as("with no participant enabling the settings there must be no instance at "
                                    + "all. A record that registered itself would be bound both by its "
                                    + "owner and by discovery, and two bound copies of a signing secret "
                                    + "is the drift a single registration exists to prevent")
                            .doesNotHaveBean(JwtProperties.class));

            // Secondary guard for the behaviour just asserted: the absence above holds because no
            // stereotype makes the record discoverable. Read through the framework's own annotation
            // view rather than by hand, and never as this method's only assertion.
            MergedAnnotations declared =
                    MergedAnnotations.from(JwtProperties.class, SearchStrategy.TYPE_HIERARCHY);

            assertThat(declared.isPresent(Component.class))
                    .as("a stereotype would register the record a second time")
                    .isFalse();
            assertThat(declared.isPresent(ConfigurationPropertiesScan.class))
                    .as("and a scan would do the same from the other direction")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("★ Every secret the production profile references, swept out of the document itself")
    class ProductionSecretsCarryNoFallback {

        @Test
        @DisplayName("the sweep sees every placeholder the document contains, so it cannot be reading "
                + "past a form it does not recognise")
        void theSweepSeesEveryPlaceholderTheDocumentContains() throws IOException {
            String document = textOf(PRODUCTION_DOCUMENT);
            int written = Math.toIntExact(PLACEHOLDER_OPENING.matcher(document).results().count());

            assertThat(placeholdersIn(document, false))
                    .as("%s opens a placeholder %d times, and the sweep must have read every one of "
                            + "them. A nested or line-spanning form introduced later would be counted "
                            + "here and matched nowhere, which is exactly the blind spot that would "
                            + "make every assertion below vacuous", PRODUCTION_DOCUMENT, written)
                    .hasSize(written);
        }

        @Test
        @DisplayName("the sweep matches something, and among it the signing secret - a filter that "
                + "silently matched nothing would prove nothing")
        void theSweepMatchesTheSecretsTheDocumentReferences() throws IOException {
            List<Placeholder> candidates = secretNamed(placeholdersIn(textOf(PRODUCTION_DOCUMENT), true));

            assertThat(candidates)
                    .as("no placeholder in %s has a name reading like a credential, which cannot be "
                            + "true of a production profile that configures a database, a signing "
                            + "secret and a key store. Either the document was restructured or the "
                            + "name filter no longer matches how its variables are spelled - fix the "
                            + "filter, because everything below depends on it", PRODUCTION_DOCUMENT)
                    .isNotEmpty();
            assertThat(candidates).extracting(Placeholder::name)
                    .as("and %s in particular must be among them, since it is the very setting these "
                            + "tests are about", SIGNING_SECRET_VARIABLE)
                    .contains(SIGNING_SECRET_VARIABLE);
        }

        @Test
        @DisplayName("not one of them carries a fallback default, so a deployment that exported nothing "
                + "cannot start on a value published in this repository")
        void notOneOfThemCarriesAFallbackDefault() throws IOException {
            List<Placeholder> defaulted = secretNamed(placeholdersIn(textOf(PRODUCTION_DOCUMENT), true))
                    .stream()
                    .filter(Placeholder::carriesFallback)
                    .toList();

            assertThat(defaulted)
                    .as("PRODUCTION SECRETS ARE RESOLVED FROM THE ENVIRONMENT WITH NO FALLBACK. Each "
                            + "occurrence listed here is written in %s as a reference with a value "
                            + "behind it, so a deployment that set nothing would start cleanly and run "
                            + "on a value anyone can read in this repository - which violates the "
                            + "no-hardcoded-credentials requirement exactly as thoroughly as a literal "
                            + "would, and worse, because nobody finds out. Remove the fallback from "
                            + "each one and make sure the production start-up guard names its variable. "
                            + "Offending: %s", PRODUCTION_DOCUMENT, defaulted)
                    .isEmpty();
        }

        @Test
        @DisplayName("and none anywhere in the document, prose included, so the rule is not undermined "
                + "by an example of breaking it")
        void andNoneAnywhereInTheDocument() throws IOException {
            List<Placeholder> defaulted = secretNamed(placeholdersIn(textOf(PRODUCTION_DOCUMENT), false))
                    .stream()
                    .filter(Placeholder::carriesFallback)
                    .toList();

            assertThat(defaulted)
                    .as("this sweep reads %s whole, comments included, so a secret commented out with "
                            + "a fallback attached cannot wait in the document for someone to "
                            + "uncomment. If the assertion on configuration lines passed and this one "
                            + "failed, the offender is prose illustrating the forbidden form: reword it "
                            + "so the illustration does not spell a variable name that reads like a "
                            + "credential. Offending: %s", PRODUCTION_DOCUMENT, defaulted)
                    .isEmpty();
        }

        @Test
        @DisplayName("and none of the fallback values an earlier revision of this module drifted into")
        void andNoneOfTheValuesAnEarlierRevisionDriftedInto() throws IOException {
            String document = textOf(PRODUCTION_DOCUMENT);
            List<Placeholder> drifted = placeholdersIn(document, false).stream()
                    .filter(placeholder -> BANNED_FALLBACK_VALUES.contains(placeholder.fallback()))
                    .toList();

            assertThat(drifted)
                    .as("a defective earlier revision shipped these very fallbacks - a database "
                            + "credential and a pair of cloud keys resolved from throwaway values - and "
                            + "they are named here so their return is a build failure rather than a "
                            + "review finding. Offending: %s", drifted)
                    .isEmpty();
            assertThat(document)
                    .as("%s must not reference %s either: that variable belongs to the local container "
                            + "stack, and the same revision reached a database through it. The "
                            + "production data source is configured by its own variables",
                            PRODUCTION_DOCUMENT, BANNED_VARIABLE)
                    .doesNotContain(BANNED_VARIABLE);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {LOCAL_DOCUMENT, TEST_DOCUMENT})
        @DisplayName("a non-production overlay may default the signing secret, but only to a value that "
                + "says so and that production cannot reach")
        void aNonProductionOverlayDefaultsToAValueThatSaysSo(final String overlay) throws IOException {
            List<Placeholder> signing = placeholdersIn(textOf(overlay), true).stream()
                    .filter(placeholder -> SIGNING_SECRET_VARIABLE.equals(placeholder.name()))
                    .toList();

            assertThat(signing)
                    .as("%s must declare the signing secret. The binder requires a non-blank value, so "
                            + "an overlay that omitted it would leave a developer or a suite run unable "
                            + "to start at all", overlay)
                    .isNotEmpty();

            String production = textOf(PRODUCTION_DOCUMENT);

            for (final Placeholder declared : signing) {
                assertThat(declared.carriesFallback())
                        .as("%s is where a fallback IS admissible - the tokens it signs can never be "
                                + "presented to a production deployment - and it is why the same "
                                + "fallback is inadmissible in %s. Written bare here, a run with "
                                + "nothing exported would depend on a variable no guard covers: %s",
                                overlay, PRODUCTION_DOCUMENT, declared)
                        .isTrue();
                assertThat(declared.fallback())
                        .as("an empty fallback is not a value: it binds an empty string, which the "
                                + "not-blank constraint refuses at %s", overlay)
                        .isNotBlank();
                assertThat(NON_PRODUCTION_MARKER.matcher(declared.fallback()).find())
                        .as("and the fallback must say of itself that it is not for production. A "
                                + "value that read like a real credential would be indistinguishable "
                                + "from one, both to a reviewer and to a secret scanner: %s", declared)
                        .isTrue();
                assertThat(production)
                        .as("no value %s ships may appear in %s. That is the whole point of the "
                                + "asymmetry: a repository-known secret must be unable to sign a "
                                + "production token", overlay, PRODUCTION_DOCUMENT)
                        .doesNotContain(declared.fallback());
            }
        }

        @Test
        @DisplayName("flags a defaulted secret when there is one to flag, and flags nothing else - which "
                + "is what makes the assertions above evidence rather than a formality")
        void flagsADefaultedSecretWhenThereIsOneToFlag() {
            // An illustration assembled here rather than a document this module ships, so that the
            // detection itself is exercised without any shipped file being edited to break it. The
            // fallback below is deliberately a phrase no scanner could mistake for a credential.
            String illustration = String.join("\n",
                    "# a comment, which binds nothing",
                    "datasource:",
                    "  url: ${EXAMPLE_URL}",
                    "  password: ${EXAMPLE_PASSWORD:would-be-a-default}",
                    "  region: ${EXAMPLE_REGION:a-published-region}");

            List<Placeholder> flagged = secretNamed(placeholdersIn(illustration, true)).stream()
                    .filter(Placeholder::carriesFallback)
                    .toList();

            assertThat(flagged).extracting(Placeholder::name)
                    .as("exactly the defaulted credential-named reference must be reported. A bare "
                            + "credential reference and a defaulted reference that is not a credential "
                            + "are both admissible, so a filter that reported either of those would "
                            + "fail this document for the wrong reason and be turned off")
                    .containsExactly("EXAMPLE_PASSWORD");
            assertThat(flagged.getFirst().fallback())
                    .as("and the report must carry the fallback as written, so a failure tells a reader "
                            + "what to remove rather than only that something is wrong")
                    .isEqualTo("would-be-a-default");
            assertThat(flagged.getFirst()).hasToString("${EXAMPLE_PASSWORD:would-be-a-default} at line 4");
        }

        @Test
        @DisplayName("reads prose only in the whole-document mode, so the two diagnostics above tell a "
                + "reader apart the two things they distinguish")
        void readsProseOnlyInTheWholeDocumentMode() {
            String illustration = String.join("\n",
                    "# never write ${EXAMPLE_PASSWORD:would-be-a-default}",
                    "datasource:",
                    "  password: ${EXAMPLE_PASSWORD}");

            assertThat(secretNamed(placeholdersIn(illustration, true)))
                    .as("a commented illustration binds nothing, so the configuration sweep must not "
                            + "read it and must see only the bare reference")
                    .extracting(Placeholder::carriesFallback)
                    .containsExactly(false);
            assertThat(secretNamed(placeholdersIn(illustration, false)))
                    .as("while the whole-document sweep must read both, which is what stops a secret "
                            + "from sitting commented out with a fallback attached, waiting for someone "
                            + "to uncomment it")
                    .hasSize(2);
        }
    }

    /**
     * One placeholder occurrence read out of a configuration document.
     *
     * @param line the one-based line the occurrence was written on
     * @param body everything between the opening and closing brace
     */
    private record Placeholder(int line, String body) {

        /**
         * Returns the variable name, which is everything before a fallback separator.
         *
         * @return the referenced variable name
         */
        String name() {
            int separator = this.body.indexOf(FALLBACK_SEPARATOR);
            return separator < 0 ? this.body : this.body.substring(0, separator);
        }

        /**
         * Reports whether this occurrence carries a fallback default.
         *
         * @return {@code true} when a value sits behind the reference
         */
        boolean carriesFallback() {
            return this.body.indexOf(FALLBACK_SEPARATOR) >= 0;
        }

        /**
         * Returns the fallback default, or an empty string when the reference is bare.
         *
         * @return the text a deployment would resolve to when the variable is unset
         */
        String fallback() {
            int separator = this.body.indexOf(FALLBACK_SEPARATOR);
            return separator < 0 ? "" : this.body.substring(separator + 1);
        }

        /**
         * Describes this occurrence as an assertion failure should report it.
         *
         * @return the occurrence as written, with the line it was written on
         */
        @Override
        public String toString() {
            return "${" + this.body + "} at line " + this.line;
        }
    }
}
